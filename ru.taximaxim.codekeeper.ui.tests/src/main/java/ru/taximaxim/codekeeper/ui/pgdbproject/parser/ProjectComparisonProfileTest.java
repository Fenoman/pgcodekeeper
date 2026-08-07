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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.base.parser.ParserExecutionPolicy;
import org.pgcodekeeper.core.database.pg.jdbc.PgSupportedVersion;
import org.pgcodekeeper.core.ignorelist.IgnoredObject;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.settings.ProjectFileFilter;
import org.pgcodekeeper.core.telemetry.IComparisonTelemetry;

import ru.taximaxim.codekeeper.ui.DatabaseType;

class ProjectComparisonProfileTest {

    private static final String DIGEST = "project-configuration-digest";

    @TempDir
    Path temporary;

    @TestFactory
    Stream<DynamicTest> everyModelSemanticSettingChangesTheProfile() {
        return Stream.of(
                mutation("input charset",
                        settings -> settings.setInCharsetName("windows-1251")),
                mutation("newline preservation",
                        settings -> settings.setKeepNewlines(true)),
                mutation("privilege loading",
                        settings -> settings.setIgnorePrivileges(true)),
                mutation("column ordering",
                        settings -> settings.setIgnoreColumnOrder(true)),
                mutation("function body dependencies",
                        settings -> settings.setEnableFunctionBodiesDependencies(true)),
                mutation("function body checking",
                        settings -> settings.setDisableCheckFunctionBodies(true)),
                mutation("object reference collection",
                        settings -> settings.collectObjectReferences = false),
                mutation("auxiliary file autoload",
                        settings -> settings.setDisableAutoLoad(true)),
                mutation("allowed object types",
                        settings -> settings.setAllowedTypes(List.of(DbObjType.VIEW))),
                mutation("effective database version", settings -> {
                    settings.resetVersion();
                    settings.setVersion(PgSupportedVersion.VERSION_15);
                }),
                mutation("view simplification",
                        settings -> settings.setSimplifyView(true)),
                mutation("not-null simplification",
                        settings -> settings.setSimplifyNotNull(true)),
                mutation("time zone",
                        settings -> settings.setTimeZone("Europe/Moscow")),
                mutation("version-specific syntax",
                        settings -> settings.setActualVersionSyntax(true)),
                mutation("object ignore list", settings ->
                        settings.getIgnoreList().add(new IgnoredObject(
                                "audit", false, false, false,
                                Set.of(DbObjType.SCHEMA)))))
                .map(mutation -> DynamicTest.dynamicTest(mutation.name(),
                        () -> assertChanges(mutation.change())));
    }

    @Test
    void databaseTypeChangesTheProfile() {
        var settings = semanticBaseline();

        assertNotEquals(capture(DatabaseType.PG, settings),
                capture(DatabaseType.MS, settings));
    }

    /**
     * Settings that change how a model is loaded and not what it contains.
     * <p>
     * The two routine-body analysis flags are deliberately not asserted here:
     * they are not transport, and grouping them under that name is what makes
     * their real justification unfindable. They have their own case below.
     */
    @Test
    void parserAndCatalogTransportSettingsDoNotChangeTheProfile() {
        var baseline = semanticBaseline();
        var tuned = semanticBaseline();

        tuned.setParserExecutionPolicy(ParserExecutionPolicy.dedicated(7));
        tuned.setComparisonTelemetry(new IComparisonTelemetry() { });
        tuned.setParallelLoad(true);
        tuned.setJdbcFetchSize(8192);
        tuned.setReadAuthors(false);
        tuned.setPgRoutineBodyResidualBatchCount(17);
        tuned.setPgRoutineBodyResidualBatchBytes(4L << 20);
        tuned.setPgCatalogCacheDir(temporary.resolve("catalog").toString());
        tuned.setPgCatalogCacheMaxMb(2048);
        tuned.setPgCatalogCacheRows(true);
        tuned.setPgCatalogCacheFingerprintProbe(true);
        tuned.setPgParallelCatalogReaders(8);

        assertEquals(capture(DatabaseType.PG, baseline),
                capture(DatabaseType.PG, tuned));
    }

    /**
     * The two PostgreSQL routine-body flags stay out of the profile, and not
     * because they are transport.
     * <p>
     * <b>This case documents; it does not protect.</b> What it asserts is that
     * this record does not carry the flags - a statement about this record and
     * nothing else. The guarantee that makes their absence safe lives in Core:
     * a project loader running under {@code
     * PgProjectLoader.enableReusableModelCapture} disarms the matched-body skip
     * on every routine launcher between parsing and analysis, so a model that
     * reaches the cache is analyzed in full whatever {@code
     * isPgRoutineBodySkipMatchedAnalysis} said. Delete that disarm and this
     * case stays green while the guarantee is gone; the case that goes red is
     * {@code PreanalyzedProjectLoaderTest
     * #captureKeepsProjectFullBodyAnalysisButDoesNotMutateSharedSetting}, which
     * counts parsed against skipped bodies on a matched PL/pgSQL routine. That
     * is where to look for the protection, and where to add to it.
     * <p>
     * {@code isPgRoutineBodyHashFirst} is kept out by a second, unrelated
     * mechanism: it is what {@code
     * ISettings.requiresComparisonLoaderFactories} returns, and {@code
     * ReusableProjectComparison.load} checks that before it consults the cache,
     * so no profile is captured or matched while the flag is off.
     */
    @Test
    void routineBodyAnalysisFlagsStayOutsideTheProfile() {
        var baseline = semanticBaseline();
        var flipped = semanticBaseline();

        // Both flags must actually leave their baseline value, or a changed
        // default would quietly turn this case into two identical captures.
        assertFalse(baseline.isPgRoutineBodyHashFirst());
        assertTrue(baseline.isPgRoutineBodySkipMatchedAnalysis());
        flipped.setPgRoutineBodyHashFirst(true);
        flipped.setPgRoutineBodySkipMatchedAnalysis(false);

        assertEquals(capture(DatabaseType.PG, baseline),
                capture(DatabaseType.PG, flipped));
    }

    @Test
    void allowedTypesHaveAStableOrderIndependentKey() {
        var first = semanticBaseline();
        first.setAllowedTypes(List.of(DbObjType.FUNCTION, DbObjType.TABLE,
                DbObjType.FUNCTION));
        var second = semanticBaseline();
        second.setAllowedTypes(List.of(DbObjType.TABLE, DbObjType.FUNCTION));

        var firstProfile = capture(DatabaseType.PG, first);
        var secondProfile = capture(DatabaseType.PG, second);

        assertEquals(firstProfile, secondProfile);
        assertEquals(firstProfile.hashCode(), secondProfile.hashCode());
        assertEquals(firstProfile.toString(), secondProfile.toString());
    }

    @Test
    void customProjectFileFilterIsIneligible() throws IOException {
        Path filter = temporary.resolve("project.filter");
        java.nio.file.Files.writeString(filter, "EXCLUDE PATH SCHEMA/a.sql\n");
        var settings = semanticBaseline();
        settings.setProjectFileFilter(ProjectFileFilter.parse(filter));

        assertTrue(ProjectComparisonProfile.capture(DatabaseType.PG, settings,
                DIGEST).isEmpty());
    }

    @Test
    void callerScopedSchemaExclusionsAreIneligible() {
        var settings = semanticBaseline();
        settings.setAdditionalExcludedSchemas(Set.of("audit"));

        assertTrue(ProjectComparisonProfile.capture(DatabaseType.PG, settings,
                DIGEST).isEmpty(),
                "Core refuses to capture or replay such a model at all");
    }

    @Test
    void changedProjectConfigurationChangesTheProfile() {
        var settings = semanticBaseline();

        assertNotEquals(
                ProjectComparisonProfile.capture(DatabaseType.PG, settings,
                        DIGEST).orElseThrow(),
                ProjectComparisonProfile.capture(DatabaseType.PG, settings,
                        "other-digest").orElseThrow());
    }

    @Test
    void unknownEffectiveVersionIsIneligible() {
        var settings = semanticBaseline();
        settings.resetVersion();

        assertTrue(ProjectComparisonProfile.capture(DatabaseType.PG, settings,
                DIGEST).isEmpty());
    }

    @Test
    void cachedVersionCanValidateRequestedSemanticsBeforeRemoteLoad() {
        var cachedSettings = semanticBaseline();
        ProjectComparisonProfile cached =
                capture(DatabaseType.PG, cachedSettings);
        var requested = semanticBaseline();
        requested.resetVersion();

        assertTrue(cached.matchesSemantics(
                DatabaseType.PG, requested, DIGEST));

        assertFalse(cached.matchesSemantics(
                DatabaseType.PG, requested, "other-digest"),
                "editing the ignore lists must retire the cached model");

        requested.setIgnorePrivileges(true);
        assertFalse(cached.matchesSemantics(
                DatabaseType.PG, requested, DIGEST));
    }

    private static void assertChanges(Consumer<MutableSettings> mutation) {
        var baseline = semanticBaseline();
        var changed = semanticBaseline();
        mutation.accept(changed);

        assertNotEquals(capture(DatabaseType.PG, baseline),
                capture(DatabaseType.PG, changed));
    }

    private static ProjectComparisonProfile capture(DatabaseType databaseType,
            MutableSettings settings) {
        return ProjectComparisonProfile.capture(databaseType, settings, DIGEST)
                .orElseThrow();
    }

    private static MutableSettings semanticBaseline() {
        var settings = new MutableSettings();
        settings.setInCharsetName("UTF-8");
        settings.setTimeZone("UTC");
        settings.setAllowedTypes(List.of(DbObjType.TABLE, DbObjType.FUNCTION));
        settings.setVersion(PgSupportedVersion.VERSION_16);
        return settings;
    }

    private static SemanticMutation mutation(String name,
            Consumer<MutableSettings> change) {
        return new SemanticMutation(name, change);
    }

    private record SemanticMutation(
            String name, Consumer<MutableSettings> change) {
    }

    private static final class MutableSettings extends CoreSettings {

        private boolean collectObjectReferences = true;

        @Override
        public boolean isCollectObjectReferences() {
            return collectObjectReferences;
        }
    }
}
