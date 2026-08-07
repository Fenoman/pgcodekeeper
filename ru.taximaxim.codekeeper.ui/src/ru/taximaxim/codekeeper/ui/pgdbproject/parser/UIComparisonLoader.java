/*******************************************************************************
 * Copyright 2017-2026 TAXTELECOM, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package ru.taximaxim.codekeeper.ui.pgdbproject.parser;

import java.io.IOException;
import java.util.Objects;

import org.pgcodekeeper.core.api.ComparisonDepth;
import org.pgcodekeeper.core.api.LoadedComparison;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.api.loader.ComparisonLoaderFactories;
import org.pgcodekeeper.core.database.api.loader.ILoader;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.model.difftree.TreeElement;
import org.pgcodekeeper.core.settings.ISettings;

/**
 * Loads both comparison sides through the Core coordinator and adapts the
 * resulting immutable models for the Eclipse diff UI.
 */
public final class UIComparisonLoader {

    private UIComparisonLoader() {
    }

    public static Result load(ComparisonLoaderFactories factories, ISettings settings,
            String oldName, String newName) throws IOException, InterruptedException {
        return createResult(loadModels(factories, settings), oldName, newName);
    }

    /**
     * Loads and fully analyzes both comparison sides without creating a diff tree.
     * Resource-owning loaders are closed by Core before this method returns.
     * <p>
     * Equivalent to {@link #loadModels(ComparisonLoaderFactories, ISettings,
     * ComparisonDepth)} with {@link ComparisonDepth#FULL}, kept so every
     * existing caller that has no opinion on depth keeps analyzing both sides
     * exactly as before this overload existed.
     */
    public static LoadedModels loadModels(ComparisonLoaderFactories factories,
            ISettings settings) throws IOException, InterruptedException {
        return loadModels(factories, settings, ComparisonDepth.FULL);
    }

    /**
     * Loads both comparison sides through the Core coordinator, stopping after
     * the phase {@code depth} asks for, without creating a diff tree.
     * Resource-owning loaders are closed by Core before this method returns.
     *
     * @param factories loader factories for the logical old and new comparison sides
     * @param settings  configuration settings used for loading
     * @param depth     how deep to load; see {@link ComparisonDepth}. A
     *                  {@link ComparisonDepth#STRUCTURAL_ONLY} result must
     *                  never reach script generation - see
     *                  {@link LoadedModels#depth()}.
     * @return the loaded models, the final settings and the depth actually loaded
     */
    public static LoadedModels loadModels(ComparisonLoaderFactories factories,
            ISettings settings, ComparisonDepth depth) throws IOException, InterruptedException {
        var loaded = PgCodeKeeperApi.loadForComparison(factories, settings, depth);
        return new LoadedModels(loaded.oldDatabase(), loaded.newDatabase(),
                loaded.comparisonSettings(), loaded.depth());
    }

    /**
     * Creates the UI diff result from models loaded by {@link #loadModels}.
     */
    public static Result createResult(LoadedModels models, String oldName,
            String newName) throws InterruptedException {
        var loaded = new LoadedComparison(models.oldDatabase(),
                models.newDatabase(), models.settings(), models.depth());
        TreeElement diffTree = PgCodeKeeperApi.createTree(loaded);

        return new Result(
                new StubDatabaseLoader(models.oldDatabase(), oldName, models.settings()),
                new StubDatabaseLoader(models.newDatabase(), newName, models.settings()),
                diffTree,
                models.settings());
    }

    /**
     * Preserves the legacy sequential/parallel loader path for configurations
     * that do not require coordinated factories, then releases the real loaders
     * and exposes only their immutable models to the UI.
     */
    public static Result loadLegacy(ILoader oldLoader, ILoader newLoader,
            ISettings settings, String oldName, String newName)
            throws IOException, InterruptedException {
        try (oldLoader; newLoader) {
            TreeElement diffTree = PgCodeKeeperApi.createTree(oldLoader, newLoader, settings);
            return new Result(
                    new StubDatabaseLoader(oldLoader.getDatabase(), oldName, settings),
                    new StubDatabaseLoader(newLoader.getDatabase(), newName, settings),
                    diffTree,
                    settings);
        }
    }

    /**
     * Creates both legacy loaders with their common project configuration and
     * guarantees cleanup if construction of the second side fails.
     */
    public static Result loadLegacy(ComparisonLoaderFactories factories,
            ISettings settings, String oldName, String newName)
            throws IOException, InterruptedException {
        factories.oldFactory().contributeCommonConfiguration(settings);
        factories.newFactory().contributeCommonConfiguration(settings);

        ILoader oldLoader = factories.oldFactory().create(settings);
        ILoader newLoader;
        try {
            newLoader = factories.newFactory().create(settings);
        } catch (IOException | InterruptedException | RuntimeException | Error failure) {
            try {
                oldLoader.close();
            } catch (IOException | RuntimeException | Error closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
        if (oldLoader == newLoader) {
            try {
                oldLoader.close();
            } catch (IOException closeFailure) {
                var failure = new IllegalArgumentException(
                        "OLD and NEW factories must create different loader instances"); //$NON-NLS-1$
                failure.addSuppressed(closeFailure);
                throw failure;
            }
            throw new IllegalArgumentException(
                    "OLD and NEW factories must create different loader instances"); //$NON-NLS-1$
        }
        return loadLegacy(oldLoader, newLoader, settings, oldName, newName);
    }

    /**
     * Returns whether models loaded with these settings contain all routine-body
     * dependencies required for migration ordering.
     */
    public static boolean isMigrationGenerationSafe(ISettings settings) {
        return !settings.isPgRoutineBodyHashFirst()
                || !settings.isPgRoutineBodySkipMatchedAnalysis();
    }

    /**
     * Resource-independent comparison models loaded to some {@link
     * ComparisonDepth}. A caller may validate the effective settings and the
     * depth before explicitly creating the diff tree.
     *
     * @param depth how deep this load went. {@link ComparisonDepth#FULL}
     *              models are ready for script generation; {@link
     *              ComparisonDepth#STRUCTURAL_ONLY} models carry no
     *              dependencies and must not be used to build one.
     */
    public record LoadedModels(IDatabase oldDatabase, IDatabase newDatabase,
            ISettings settings, ComparisonDepth depth) {

        public LoadedModels {
            Objects.requireNonNull(oldDatabase, "oldDatabase"); //$NON-NLS-1$
            Objects.requireNonNull(newDatabase, "newDatabase"); //$NON-NLS-1$
            Objects.requireNonNull(settings, "settings"); //$NON-NLS-1$
            Objects.requireNonNull(depth, "depth"); //$NON-NLS-1$
        }
    }

    public record Result(ILoader oldLoader, ILoader newLoader,
            TreeElement diffTree, ISettings settings) {

        public Result {
            Objects.requireNonNull(oldLoader, "oldLoader"); //$NON-NLS-1$
            Objects.requireNonNull(newLoader, "newLoader"); //$NON-NLS-1$
            Objects.requireNonNull(diffTree, "diffTree"); //$NON-NLS-1$
            Objects.requireNonNull(settings, "settings"); //$NON-NLS-1$
        }
    }
}
