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

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.pgcodekeeper.core.database.api.jdbc.ISupportedVersion;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.ignorelist.IgnoreList;
import org.pgcodekeeper.core.settings.ISettings;
import org.pgcodekeeper.core.settings.ProjectFileFilter;

import ru.taximaxim.codekeeper.ui.DatabaseType;

/**
 * Immutable semantic profile for a reusable project model. The in-memory
 * cache also keys models by comparison depth; persisted models are FULL only.
 * <p>
 * Execution policy, telemetry, JDBC transport, catalog cache and parallel
 * reader settings are deliberately absent: they can change how a model is
 * loaded, but not the resulting project model. A non-default project file
 * filter and caller-scoped schema exclusions cannot be reused safely and
 * therefore make capture ineligible.
 * <p>
 * The two PostgreSQL routine-body flags are absent for reasons of their own,
 * and neither of them is the one above - they are not transport, and reading
 * this list for a category to file them under finds none. Named here so the
 * next reader does not have to reconstruct why:
 * <ul>
 * <li>{@code isPgRoutineBodySkipMatchedAnalysis} does change what a routine
 * body contributes to an ordinary project load, but never to a captured one.
 * A FULL loader running under {@code PgProjectLoader.enableReusableModelCapture}
 * disarms the matched-body skip on every routine launcher between parsing and
 * analysis, so a FULL model is analyzed completely whatever the flag said.
 * Structural models perform no body analysis regardless of that flag.
 * See {@code PgProjectLoader.loadInternal}, held there by
 * {@code PreanalyzedProjectLoaderTest
 * #captureKeepsProjectFullBodyAnalysisButDoesNotMutateSharedSetting}.
 * <li>{@code isPgRoutineBodyHashFirst} is what
 * {@code ISettings.requiresComparisonLoaderFactories} returns, and
 * {@code ReusableProjectComparison.load} checks that before it consults the
 * cache at all. No profile is captured or matched while the flag is off, so
 * the field could only ever hold one value.
 * </ul>
 */
public record ProjectComparisonProfile(
        DatabaseType databaseType,
        String inputCharsetName,
        boolean keepNewlines,
        boolean ignorePrivileges,
        boolean ignoreColumnOrder,
        boolean enableFunctionBodiesDependencies,
        boolean disableCheckFunctionBodies,
        boolean collectObjectReferences,
        boolean disableAutoLoad,
        List<DbObjType> allowedTypes,
        EffectiveVersion effectiveVersion,
        boolean simplifyView,
        boolean simplifyNotNull,
        String timeZone,
        boolean useActualVersionSyntax,
        List<String> additionalExcludedSchemas,
        String ignoreListCode,
        String projectConfigurationDigest) {

    public ProjectComparisonProfile {
        Objects.requireNonNull(databaseType, "databaseType");
        Objects.requireNonNull(inputCharsetName, "inputCharsetName");
        Objects.requireNonNull(effectiveVersion, "effectiveVersion");
        Objects.requireNonNull(timeZone, "timeZone");
        Objects.requireNonNull(ignoreListCode, "ignoreListCode");
        Objects.requireNonNull(projectConfigurationDigest,
                "projectConfigurationDigest");
        allowedTypes = normalizeAllowedTypes(allowedTypes);
        additionalExcludedSchemas = normalizeNames(additionalExcludedSchemas);
    }

    /**
     * Captures a fail-closed comparison profile.
     *
     * @param projectConfigurationDigest digest of the shared project
     *                                   configuration files, see
     *                                   {@code ProjectConfigurationDigest}
     * @return the profile, or empty when an exact reusable-model key cannot be
     *         formed
     */
    public static Optional<ProjectComparisonProfile> capture(
            DatabaseType databaseType, ISettings settings,
            String projectConfigurationDigest) {
        Objects.requireNonNull(databaseType, "databaseType");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(projectConfigurationDigest,
                "projectConfigurationDigest");

        ISupportedVersion version = settings.getVersion();
        if (version == null || version.getText() == null) {
            return Optional.empty();
        }
        return capture(databaseType, settings,
                new EffectiveVersion(
                        version.getVersion(), version.getText(),
                        version.getClass().getName()),
                projectConfigurationDigest);
    }

    /**
     * Checks all model semantics before the remote side has republished its
     * effective version. The cached version remains part of the exact profile
     * and is checked again after loading NEW, before building the diff tree.
     */
    public boolean matchesSemantics(
            DatabaseType databaseType, ISettings settings,
            String projectConfigurationDigest) {
        return capture(databaseType, settings, effectiveVersion,
                projectConfigurationDigest)
                .map(this::equals).orElse(false);
    }

    /**
     * Captures a profile for settings that have not yet learned the effective
     * database version, substituting a version recorded by an earlier run.
     * <p>
     * A persisted model carries the version it was analyzed under, exactly as
     * a retained one does, and the same substitution keeps the on-disk lookup
     * identical to {@link #matchesSemantics}.
     *
     * @param effectiveVersion version the stored model was produced under
     * @return the profile, or empty when no exact key can be formed
     */
    static Optional<ProjectComparisonProfile> captureWith(
            DatabaseType databaseType, ISettings settings,
            EffectiveVersion effectiveVersion,
            String projectConfigurationDigest) {
        Objects.requireNonNull(databaseType, "databaseType"); //$NON-NLS-1$
        Objects.requireNonNull(settings, "settings"); //$NON-NLS-1$
        Objects.requireNonNull(effectiveVersion, "effectiveVersion"); //$NON-NLS-1$
        Objects.requireNonNull(projectConfigurationDigest,
                "projectConfigurationDigest"); //$NON-NLS-1$
        return capture(databaseType, settings, effectiveVersion,
                projectConfigurationDigest);
    }

    private static Optional<ProjectComparisonProfile> capture(
            DatabaseType databaseType, ISettings settings,
            EffectiveVersion effectiveVersion,
            String projectConfigurationDigest) {
        if (settings.getProjectFileFilter() != ProjectFileFilter.ALLOW_ALL) {
            return Optional.empty();
        }
        // Caller-scoped exclusions drop project files before parsing, so a
        // model is valid only for the very same exclusion set. Core refuses to
        // capture or replay such a model at all, so stay ineligible here
        // instead of letting that hard failure be reachable.
        Set<String> excludedSchemas = settings.getAdditionalExcludedSchemas();
        IgnoreList ignoreList = settings.getIgnoreList();
        if (excludedSchemas == null || !excludedSchemas.isEmpty()
                || ignoreList == null) {
            return Optional.empty();
        }

        String charset = settings.getInCharsetName();
        String timeZone = settings.getTimeZone();
        Collection<DbObjType> allowedTypes = settings.getAllowedTypes();
        if (charset == null || timeZone == null || allowedTypes == null
                || allowedTypes.stream().anyMatch(Objects::isNull)) {
            return Optional.empty();
        }

        return Optional.of(new ProjectComparisonProfile(
                databaseType,
                charset,
                settings.isKeepNewlines(),
                settings.isIgnorePrivileges(),
                settings.isIgnoreColumnOrder(),
                settings.isEnableFunctionBodiesDependencies(),
                settings.isDisableCheckFunctionBodies(),
                settings.isCollectObjectReferences(),
                settings.isDisableAutoLoad(),
                normalizeAllowedTypes(allowedTypes),
                effectiveVersion,
                settings.isSimplifyView(),
                settings.isSimplifyNotNull(),
                timeZone,
                settings.isUseActualVersionSyntax(),
                List.copyOf(excludedSchemas),
                ignoreList.getListCode(),
                projectConfigurationDigest));
    }

    private static List<DbObjType> normalizeAllowedTypes(
            Collection<DbObjType> allowedTypes) {
        Objects.requireNonNull(allowedTypes, "allowedTypes");
        if (allowedTypes.isEmpty()) {
            return List.of();
        }
        return List.copyOf(EnumSet.copyOf(allowedTypes));
    }

    private static List<String> normalizeNames(Collection<String> names) {
        Objects.requireNonNull(names, "names");
        if (names.isEmpty()) {
            return List.of();
        }
        return names.stream().map(name -> Objects.requireNonNull(
                name, "name")).sorted().distinct().toList();
    }

    /**
     * Stable value representation of the effective database version.
     */
    public record EffectiveVersion(
            int value, String text, String implementationClass) {

        public EffectiveVersion {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(implementationClass, "implementationClass");
        }
    }
}
