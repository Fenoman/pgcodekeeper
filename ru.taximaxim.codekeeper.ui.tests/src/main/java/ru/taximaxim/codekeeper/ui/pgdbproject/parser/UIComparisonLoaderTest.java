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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.loader.ComparisonLoaderFactories;
import org.pgcodekeeper.core.database.api.loader.ILoader;
import org.pgcodekeeper.core.database.api.loader.ILoaderFactory;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.settings.AbstractSettings;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.settings.ISettings;
import org.pgcodekeeper.core.telemetry.ComparisonStage;
import org.pgcodekeeper.core.telemetry.ComparisonStageTelemetry;
import org.pgcodekeeper.core.telemetry.IComparisonTelemetry;

class UIComparisonLoaderTest {

    @Test
    void modelLoadingDoesNotCreateDiffTreeUntilResultIsRequested() throws Exception {
        IDatabase project = new PgDatabaseProvider().createDatabase();
        IDatabase remote = new PgDatabaseProvider().createDatabase();
        var settings = new CoreSettings();
        var telemetry = new RecordingTelemetry();
        settings.setComparisonTelemetry(telemetry);
        var factories = new ComparisonLoaderFactories(
                loaderSettings -> new TrackingLoader(
                        project, "project", loaderSettings),
                loaderSettings -> new TrackingLoader(
                        remote, "remote", loaderSettings));

        UIComparisonLoader.LoadedModels models =
                UIComparisonLoader.loadModels(factories, settings);

        assertSame(project, models.oldDatabase());
        assertSame(remote, models.newDatabase());
        assertEquals(0, telemetry.count(ComparisonStage.DIFF_TREE_CREATE));

        UIComparisonLoader.Result result = UIComparisonLoader.createResult(
                models, "OmniX project", "omnix database");

        assertNotNull(result.diffTree());
        assertEquals(1, telemetry.count(ComparisonStage.DIFF_TREE_CREATE));
    }

    @Test
    void newLoaderFactoryIsInvokedOnEveryModelLoad() throws Exception {
        IDatabase project = new PgDatabaseProvider().createDatabase();
        IDatabase remote = new PgDatabaseProvider().createDatabase();
        var newFactoryCalls = new AtomicInteger();
        var factories = new ComparisonLoaderFactories(
                settings -> new TrackingLoader(project, "project", settings),
                settings -> {
                    newFactoryCalls.incrementAndGet();
                    return new TrackingLoader(remote, "remote", settings);
                });

        UIComparisonLoader.loadModels(factories, new CoreSettings());
        UIComparisonLoader.loadModels(factories, new CoreSettings());

        assertEquals(2, newFactoryCalls.get());
    }

    @Test
    void failedTreeCreationDoesNotConsumeLoadedModels() throws Exception {
        IDatabase project = new PgDatabaseProvider().createDatabase();
        IDatabase remote = new PgDatabaseProvider().createDatabase();
        var settings = new CoreSettings();
        var factories = new ComparisonLoaderFactories(
                loaderSettings -> new TrackingLoader(
                        project, "project", loaderSettings),
                loaderSettings -> new TrackingLoader(
                        remote, "remote", loaderSettings));
        UIComparisonLoader.LoadedModels models =
                UIComparisonLoader.loadModels(factories, settings);

        models.settings().getMonitor().setCancelled(true);
        assertThrows(InterruptedException.class, () -> UIComparisonLoader.createResult(
                models, "project", "remote"));

        models.settings().getMonitor().setCancelled(false);
        UIComparisonLoader.Result result = UIComparisonLoader.createResult(
                models, "project", "remote");

        assertSame(project, result.oldLoader().getDatabase());
        assertSame(remote, result.newLoader().getDatabase());
        assertNotNull(result.diffTree());
    }

    @Test
    void modelLoadFailureClosesBothResourceOwningLoaders() {
        IDatabase project = new PgDatabaseProvider().createDatabase();
        IDatabase remote = new PgDatabaseProvider().createDatabase();
        var oldLoader = new AtomicReference<TrackingLoader>();
        var newLoader = new AtomicReference<TrackingLoader>();
        var factories = new ComparisonLoaderFactories(
                settings -> {
                    var loader = new TrackingLoader(project, "project", settings);
                    oldLoader.set(loader);
                    return loader;
                },
                settings -> {
                    var loader = new TrackingLoader(remote, "remote", settings,
                            new IOException("remote load failed"));
                    newLoader.set(loader);
                    return loader;
                });

        assertThrows(IOException.class,
                () -> UIComparisonLoader.loadModels(factories, new CoreSettings()));

        assertTrue(oldLoader.get().closed);
        assertTrue(newLoader.get().closed);
    }

    @Test
    void keepsProjectOldAndRemoteNewWithFinalComparisonSettings() throws Exception {
        IDatabase project = new PgDatabaseProvider().createDatabase();
        IDatabase remote = new PgDatabaseProvider().createDatabase();
        var oldLoader = new AtomicReference<TrackingLoader>();
        var newLoader = new AtomicReference<TrackingLoader>();

        ILoaderFactory projectFactory = new ILoaderFactory() {
            @Override
            public void contributeCommonConfiguration(ISettings settings) {
                ((AbstractSettings) settings).setPgParallelCatalogReaders(7);
            }

            @Override
            public ILoader create(ISettings settings) {
                var loader = new TrackingLoader(project, "project-loader", settings);
                oldLoader.set(loader);
                return loader;
            }
        };
        ILoaderFactory remoteFactory = settings -> {
            var loader = new TrackingLoader(remote, "remote-loader", settings);
            newLoader.set(loader);
            return loader;
        };

        var requestedSettings = new CoreSettings();
        var telemetry = new RecordingTelemetry();
        requestedSettings.setComparisonTelemetry(telemetry);
        requestedSettings.setPgRoutineBodyHashFirst(true);
        UIComparisonLoader.Result result = UIComparisonLoader.load(
                new ComparisonLoaderFactories(projectFactory, remoteFactory),
                requestedSettings, "OmniX project", "omnix database");

        assertSame(project, result.oldLoader().getDatabase());
        assertSame(remote, result.newLoader().getDatabase());
        assertEquals("OmniX project", result.oldLoader().getDatabaseName());
        assertEquals("omnix database", result.newLoader().getDatabaseName());
        assertSame(result.settings(), result.oldLoader().getSettings());
        assertSame(result.settings(), result.newLoader().getSettings());
        assertNotSame(requestedSettings, result.settings());
        assertEquals(7, result.settings().getPgParallelCatalogReaders());
        assertNotNull(result.diffTree());

        assertEquals(1, oldLoader.get().loadCalls);
        assertEquals(1, oldLoader.get().analyzeCalls);
        assertEquals(1, newLoader.get().loadCalls);
        assertEquals(1, newLoader.get().analyzeCalls);
        assertTrue(oldLoader.get().closed);
        assertTrue(newLoader.get().closed);
        assertEquals(1, telemetry.count(ComparisonStage.DATABASE_LOAD_TOTAL));
        assertEquals(1, telemetry.count(ComparisonStage.DIFF_TREE_CREATE));
    }

    @Test
    void legacyPathKeepsSidesAndClosesResourceOwningLoaders() throws Exception {
        IDatabase project = new PgDatabaseProvider().createDatabase();
        IDatabase remote = new PgDatabaseProvider().createDatabase();
        var settings = new CoreSettings();
        var telemetry = new RecordingTelemetry();
        settings.setComparisonTelemetry(telemetry);
        var projectLoader = new TrackingLoader(project, "ignored-project-name", settings);
        var remoteLoader = new TrackingLoader(remote, "ignored-remote-name", settings);

        UIComparisonLoader.Result result = UIComparisonLoader.loadLegacy(
                projectLoader, remoteLoader, settings, "OmniX project", "omnix database");

        assertSame(project, result.oldLoader().getDatabase());
        assertSame(remote, result.newLoader().getDatabase());
        assertEquals("OmniX project", result.oldLoader().getDatabaseName());
        assertEquals("omnix database", result.newLoader().getDatabaseName());
        assertSame(settings, result.settings());
        assertEquals(1, projectLoader.analyzeCalls);
        assertEquals(1, remoteLoader.analyzeCalls);
        assertTrue(projectLoader.closed);
        assertTrue(remoteLoader.closed);
        assertEquals(1, telemetry.count(ComparisonStage.DATABASE_LOAD_TOTAL));
        assertEquals(1, telemetry.count(ComparisonStage.DIFF_TREE_CREATE));
    }

    @Test
    void legacyFactoryPathClosesOldLoaderWhenNewConstructionFails() {
        IDatabase project = new PgDatabaseProvider().createDatabase();
        var settings = new CoreSettings();
        var projectLoader = new TrackingLoader(project, "project", settings);
        var factories = new ComparisonLoaderFactories(
                ignored -> projectLoader,
                ignored -> { throw new IOException("remote construction failed"); });

        assertThrows(IOException.class, () -> UIComparisonLoader.loadLegacy(
                factories, settings, "project", "remote"));

        assertTrue(projectLoader.closed);
    }

    @Test
    void migrationRequiresCompleteRoutineBodyAnalysis() {
        var settings = new CoreSettings();

        assertTrue(UIComparisonLoader.isMigrationGenerationSafe(settings));

        settings.setPgRoutineBodyHashFirst(true);
        settings.setPgRoutineBodySkipMatchedAnalysis(false);
        assertTrue(UIComparisonLoader.isMigrationGenerationSafe(settings));

        settings.setPgRoutineBodySkipMatchedAnalysis(true);
        assertFalse(UIComparisonLoader.isMigrationGenerationSafe(settings));
    }

    private static final class TrackingLoader implements ILoader {

        private final IDatabase database;
        private final String name;
        private final ISettings settings;
        private int loadCalls;
        private int analyzeCalls;
        private boolean closed;
        private final IOException analyzeFailure;

        private TrackingLoader(IDatabase database, String name, ISettings settings) {
            this(database, name, settings, null);
        }

        private TrackingLoader(IDatabase database, String name, ISettings settings,
                IOException analyzeFailure) {
            this.database = database;
            this.name = name;
            this.settings = settings;
            this.analyzeFailure = analyzeFailure;
        }

        @Override
        public IDatabase load() {
            loadCalls++;
            return database;
        }

        @Override
        public IDatabase loadAndAnalyze() throws IOException {
            analyzeCalls++;
            if (analyzeFailure != null) {
                throw analyzeFailure;
            }
            return database;
        }

        @Override
        public IDatabase getDatabase() {
            return database;
        }

        @Override
        public String getDatabaseName() {
            return name;
        }

        @Override
        public ISettings getSettings() {
            return settings;
        }

        @Override
        public List<Object> getErrors() {
            return Collections.unmodifiableList(settings.getErrors());
        }

        @Override
        public void close() throws IOException {
            closed = true;
        }
    }

    private static final class RecordingTelemetry implements IComparisonTelemetry {

        private final List<ComparisonStageTelemetry> stages =
                new CopyOnWriteArrayList<>();

        @Override
        public void comparisonStageFinished(ComparisonStageTelemetry event) {
            stages.add(event);
        }

        private long count(ComparisonStage stage) {
            return stages.stream().filter(event -> event.stage() == stage).count();
        }
    }
}
