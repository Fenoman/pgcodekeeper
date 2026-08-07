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
package ru.taximaxim.codekeeper.ui.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ProjectScope;
import org.eclipse.jface.preference.IPreferenceStore;
import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.settings.ISettings;

import ru.taximaxim.codekeeper.ui.Activator;
import ru.taximaxim.codekeeper.ui.DatabaseType;
import ru.taximaxim.codekeeper.ui.UIConsts;
import ru.taximaxim.codekeeper.ui.UIConsts.FORMATTER_PREF;
import ru.taximaxim.codekeeper.ui.UIConsts.PREF;
import ru.taximaxim.codekeeper.ui.UIConsts.PROJ_PREF;

class UISettingsPerformanceTest {

    private static final Path CACHE_ROOT = Path.of("workspace", ".metadata", ".plugins",
            "ru.taximaxim.codekeeper.ui", "pg-catalog-row-cache");

    @Test
    void getChangesKeepsOptimizedPostgresProfileWithPersistentCacheOptedOut() {
        UISettings settings = createPgSettings(true, false);

        assertTrue(settings.isParallelLoad());
        assertTrue(settings.isPgRoutineBodyHashFirst());
        assertTrue(settings.isPgRoutineBodySkipMatchedAnalysis());
        assertEquals(512, settings.getJdbcFetchSize());
        assertEquals(3, settings.getPgParallelCatalogReaders());
        assertEquals(ISettings.DEFAULT_PG_ROUTINE_BODY_RESIDUAL_BATCH_COUNT,
                settings.getPgRoutineBodyResidualBatchCount());
        assertEquals(ISettings.DEFAULT_PG_ROUTINE_BODY_RESIDUAL_BATCH_BYTES,
                settings.getPgRoutineBodyResidualBatchBytes());
        assertEquals(ISettings.DEFAULT_PG_CATALOG_CACHE_MAX_MB, settings.getPgCatalogCacheMaxMb());
        assertFalse(settings.isPgCatalogCacheRows());
        assertFalse(settings.isPgCatalogCacheFingerprintProbe());
        // The row cache is opted out, but hash-first loading still needs the
        // persistent routine-body store.
        assertEquals(CACHE_ROOT.toString(), settings.getPgCatalogCacheDir());
        assertEquals(ParserWorkerDefaults.defaultWorkers(),
                settings.getParserExecutionPolicy().workers());
        assertEquals(2 * ParserWorkerDefaults.defaultWorkers(),
                settings.getParserExecutionPolicy().maxPending());
        assertEquals(64L << 20, settings.getParserExecutionPolicy().maxPendingBytes());
        assertTrue(settings.isReadAuthors());
        assertTrue(settings.isCollectObjectReferences());
    }

    @Test
    void persistentCatalogCacheUsesWorkspaceStateWhenEnabled() {
        UISettings settings = createPgSettings(false, true);

        assertFalse(settings.isPgRoutineBodySkipMatchedAnalysis());
        assertTrue(settings.isPgCatalogCacheRows());
        assertTrue(settings.isPgCatalogCacheFingerprintProbe());
        assertEquals(CACHE_ROOT.toString(), settings.getPgCatalogCacheDir());
    }

    @Test
    void projectOverrideCanDisableDefaultPersistentCatalogCache() throws Exception {
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        boolean oldCatalogCacheRows = store.getBoolean(PREF.PG_CATALOG_CACHE_ROWS);
        boolean oldCatalogCacheRowsDefault = store.isDefault(PREF.PG_CATALOG_CACHE_ROWS);
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn("UISettingsPerformanceTest-" + UUID.randomUUID()); //$NON-NLS-1$
        var projectPrefs = new ProjectScope(project).getNode(UIConsts.PLUGIN_ID.THIS);

        try {
            store.setToDefault(PREF.PG_CATALOG_CACHE_ROWS);
            projectPrefs.putBoolean(PROJ_PREF.ENABLE_PROJ_PREF_ROOT, true);
            projectPrefs.putBoolean(PREF.PG_CATALOG_CACHE_ROWS, false);

            UISettings settings = UISettings.forGetChanges(project, null, DatabaseType.PG, CACHE_ROOT);

            assertFalse(settings.isPgCatalogCacheRows());
            assertFalse(settings.isPgCatalogCacheFingerprintProbe());
            // Only the row cache follows the preference; the routine-body
            // store follows hash-first loading.
            assertEquals(CACHE_ROOT.toString(), settings.getPgCatalogCacheDir());
        } finally {
            projectPrefs.removeNode();
            restoreBoolean(store, PREF.PG_CATALOG_CACHE_ROWS,
                    oldCatalogCacheRows, oldCatalogCacheRowsDefault);
        }
    }

    @Test
    void disabledParallelLoadingKeepsLegacyProfileButAllowsRowCacheOptIn() {
        Map<String, Object> prefs = new HashMap<>();
        prefs.put(PREF.PARALLEL_LOADING, false);
        prefs.put(PREF.PG_ROUTINE_BODY_SKIP_MATCHED_ANALYSIS, true);
        prefs.put(PREF.PG_CATALOG_CACHE_ROWS, true);

        UISettings settings = UISettings.forGetChanges(null, prefs, DatabaseType.PG, CACHE_ROOT);

        assertFalse(settings.isParallelLoad());
        assertFalse(settings.isPgRoutineBodyHashFirst());
        assertFalse(settings.isPgRoutineBodySkipMatchedAnalysis());
        assertEquals(0, settings.getJdbcFetchSize());
        assertEquals(ISettings.DEFAULT_PG_PARALLEL_CATALOG_READERS,
                settings.getPgParallelCatalogReaders());
        assertFalse(settings.requiresComparisonLoaderFactories());
        assertTrue(settings.isPgCatalogCacheRows());
        assertTrue(settings.isPgCatalogCacheFingerprintProbe());
        assertEquals(CACHE_ROOT.toString(), settings.getPgCatalogCacheDir());
        assertEquals(ParserWorkerDefaults.defaultWorkers(),
                settings.getParserExecutionPolicy().workers());
    }

    @Test
    void getChangesKeepsCompatibilityDefaultsForOtherDatabaseTypes() {
        Map<String, Object> prefs = Map.of(
                PREF.PG_ROUTINE_BODY_SKIP_MATCHED_ANALYSIS, false,
                PREF.PG_CATALOG_CACHE_ROWS, true);

        for (DatabaseType dbType : new DatabaseType[] { DatabaseType.MS, DatabaseType.CH }) {
            UISettings settings = UISettings.forGetChanges(null, prefs, dbType, CACHE_ROOT);

            assertFalse(settings.isPgRoutineBodyHashFirst());
            assertEquals(ISettings.DEFAULT_PG_ROUTINE_BODY_SKIP_MATCHED_ANALYSIS,
                    settings.isPgRoutineBodySkipMatchedAnalysis());
            assertEquals(0, settings.getJdbcFetchSize());
            assertEquals(ISettings.DEFAULT_PG_PARALLEL_CATALOG_READERS,
                    settings.getPgParallelCatalogReaders());
            assertFalse(settings.isPgCatalogCacheRows());
            assertFalse(settings.isPgCatalogCacheFingerprintProbe());
            assertNull(settings.getPgCatalogCacheDir());
            assertFalse(settings.requiresComparisonLoaderFactories());
            assertTrue(settings.getParserExecutionPolicy().shared());
        }
    }

    @Test
    void dedicatedParserWorkerPreferencesStayIndependentAcrossOperations() {
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        int oldGetChanges = store.getInt(
                PREF.GET_CHANGES_PARSER_WORKERS);
        boolean oldGetChangesDefault = store.isDefault(
                PREF.GET_CHANGES_PARSER_WORKERS);
        int oldProjectIndex = store.getInt(
                PREF.PROJECT_INDEX_PARSER_WORKERS);
        boolean oldProjectIndexDefault = store.isDefault(
                PREF.PROJECT_INDEX_PARSER_WORKERS);

        try {
            store.setValue(PREF.GET_CHANGES_PARSER_WORKERS, 6);
            store.setValue(PREF.PROJECT_INDEX_PARSER_WORKERS, 5);

            var getChanges = createPgSettings(true, false);
            assertDedicatedPolicy(getChanges, 6, 12);

            for (DatabaseType dbType : DatabaseType.values()) {
                UISettings projectIndex = UISettings.forProjectIndex(
                        null, Map.of(), dbType);
                assertDedicatedPolicy(projectIndex, 5, 10);
            }
        } finally {
            restoreInt(store, PREF.GET_CHANGES_PARSER_WORKERS,
                    oldGetChanges, oldGetChangesDefault);
            restoreInt(store, PREF.PROJECT_INDEX_PARSER_WORKERS,
                    oldProjectIndex, oldProjectIndexDefault);
        }
    }

    @Test
    void copiedSettingsKeepTheWorkerPolicyCapturedAtOperationStart() {
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        int oldGetChanges = store.getInt(
                PREF.GET_CHANGES_PARSER_WORKERS);
        boolean oldGetChangesDefault = store.isDefault(
                PREF.GET_CHANGES_PARSER_WORKERS);
        int oldProjectIndex = store.getInt(
                PREF.PROJECT_INDEX_PARSER_WORKERS);
        boolean oldProjectIndexDefault = store.isDefault(
                PREF.PROJECT_INDEX_PARSER_WORKERS);

        try {
            store.setValue(PREF.GET_CHANGES_PARSER_WORKERS, 6);
            store.setValue(PREF.PROJECT_INDEX_PARSER_WORKERS, 5);
            UISettings getChanges = createPgSettings(true, false);
            UISettings projectIndex = UISettings.forProjectIndex(
                    null, Map.of(), DatabaseType.PG);
            ISettings getChangesCopy = getChanges.copy();
            ISettings projectIndexCopy = projectIndex.copy();

            store.setValue(PREF.GET_CHANGES_PARSER_WORKERS, 3);
            store.setValue(PREF.PROJECT_INDEX_PARSER_WORKERS, 4);

            assertDedicatedPolicy(getChanges, 6, 12);
            assertDedicatedPolicy(getChangesCopy, 6, 12);
            assertDedicatedPolicy(projectIndex, 5, 10);
            assertDedicatedPolicy(projectIndexCopy, 5, 10);
            assertDedicatedPolicy(createPgSettings(true, false), 3, 6);
            assertDedicatedPolicy(UISettings.forProjectIndex(
                    null, Map.of(), DatabaseType.PG), 4, 8);
        } finally {
            restoreInt(store, PREF.GET_CHANGES_PARSER_WORKERS,
                    oldGetChanges, oldGetChangesDefault);
            restoreInt(store, PREF.PROJECT_INDEX_PARSER_WORKERS,
                    oldProjectIndex, oldProjectIndexDefault);
        }
    }

    @Test
    void everyGetChangesRunGetsAnIndependentTelemetrySinkThatCopyKeeps() {
        for (DatabaseType dbType : DatabaseType.values()) {
            UISettings first = UISettings.forGetChanges(
                    null, Map.of(), dbType, CACHE_ROOT);
            UISettings second = UISettings.forGetChanges(
                    null, Map.of(), dbType, CACHE_ROOT);

            assertInstanceOf(EclipseComparisonTelemetry.class,
                    first.getComparisonTelemetry());
            assertInstanceOf(EclipseComparisonTelemetry.class,
                    second.getComparisonTelemetry());
            assertNotSame(first.getComparisonTelemetry(),
                    second.getComparisonTelemetry());
            assertSame(first.getComparisonTelemetry(),
                    first.copy().getComparisonTelemetry());
            assertSame(first.getComparisonTelemetry(),
                    first.getComparisonRunTelemetry());
            assertSame(first.getComparisonRunTelemetry(),
                    ((UISettings) first.copy()).getComparisonRunTelemetry());
        }
    }

    @Test
    void ordinarySettingsHaveNoGetChangesRunTelemetry() {
        assertNull(new UISettings(null, Map.of(), DatabaseType.PG)
                .getComparisonRunTelemetry());
    }

    @Test
    void copyPreservesGetChangesProfileAndCharset() {
        UISettings original = createPgSettings(false, true);
        original.setCharsetName("windows-1251");

        ISettings copy = original.copy();

        assertEquals(original.isPgRoutineBodyHashFirst(), copy.isPgRoutineBodyHashFirst());
        assertEquals(original.isPgRoutineBodySkipMatchedAnalysis(), copy.isPgRoutineBodySkipMatchedAnalysis());
        assertEquals(original.getJdbcFetchSize(), copy.getJdbcFetchSize());
        assertEquals(original.getPgParallelCatalogReaders(), copy.getPgParallelCatalogReaders());
        assertEquals(original.getPgRoutineBodyResidualBatchCount(), copy.getPgRoutineBodyResidualBatchCount());
        assertEquals(original.getPgRoutineBodyResidualBatchBytes(), copy.getPgRoutineBodyResidualBatchBytes());
        assertEquals(original.getPgCatalogCacheMaxMb(), copy.getPgCatalogCacheMaxMb());
        assertEquals(original.isPgCatalogCacheRows(), copy.isPgCatalogCacheRows());
        assertEquals(original.isPgCatalogCacheFingerprintProbe(),
                copy.isPgCatalogCacheFingerprintProbe());
        assertEquals(original.getPgCatalogCacheDir(), copy.getPgCatalogCacheDir());
        assertEquals(original.getParserExecutionPolicy(), copy.getParserExecutionPolicy());
        assertEquals(original.isReadAuthors(), copy.isReadAuthors());
        assertEquals(original.isCollectObjectReferences(), copy.isCollectObjectReferences());
        assertEquals(original.getInCharsetName(), copy.getInCharsetName());
        assertEquals(original.getTimeZone(), copy.getTimeZone());
    }

    @Test
    void copiedSettingsKeepOneTimeOverridesAfterEditorClearsItsMap() {
        Map<String, Object> oneTimePrefs = new HashMap<>();
        oneTimePrefs.put(PREF.PG_ROUTINE_BODY_SKIP_MATCHED_ANALYSIS, false);
        oneTimePrefs.put(PREF.PG_CATALOG_CACHE_ROWS, false);
        oneTimePrefs.put(PREF.NO_PRIVILEGES, true);

        ISettings copy = UISettings.forGetChanges(
                null, oneTimePrefs, DatabaseType.PG, CACHE_ROOT).copy();
        oneTimePrefs.clear();

        assertFalse(copy.isPgRoutineBodySkipMatchedAnalysis());
        assertFalse(copy.isPgCatalogCacheRows());
        assertTrue(copy.isIgnorePrivileges());
    }

    @Test
    void getChangesAndCopyFreezeGlobalFormatterAndOneTimeValues() {
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        boolean oldNoPrivileges = store.getBoolean(PREF.NO_PRIVILEGES);
        boolean oldNoPrivilegesDefault = store.isDefault(PREF.NO_PRIVILEGES);
        int oldIndentSize = store.getInt(FORMATTER_PREF.INDENT_SIZE);
        boolean oldIndentSizeDefault = store.isDefault(FORMATTER_PREF.INDENT_SIZE);
        Map<String, Object> oneTimePrefs = new HashMap<>();
        oneTimePrefs.put(PREF.PARALLEL_LOADING, true);
        oneTimePrefs.put(PREF.IGNORE_COLUMN_ORDER, true);
        oneTimePrefs.put(PREF.PG_ROUTINE_BODY_SKIP_MATCHED_ANALYSIS, true);
        oneTimePrefs.put(PREF.PG_CATALOG_CACHE_ROWS, false);

        try {
            store.setValue(PREF.NO_PRIVILEGES, false);
            store.setValue(FORMATTER_PREF.INDENT_SIZE, 2);
            UISettings original = UISettings.forGetChanges(
                    null, oneTimePrefs, DatabaseType.PG, CACHE_ROOT);

            store.setValue(PREF.NO_PRIVILEGES, true);
            store.setValue(FORMATTER_PREF.INDENT_SIZE, 9);
            oneTimePrefs.put(PREF.PARALLEL_LOADING, false);
            oneTimePrefs.put(PREF.IGNORE_COLUMN_ORDER, false);
            ISettings copy = original.copy();

            for (ISettings settings : new ISettings[] { original, copy }) {
                assertFalse(settings.isIgnorePrivileges());
                assertTrue(settings.isIgnoreColumnOrder());
                assertTrue(settings.isParallelLoad());
                assertEquals(2, settings.getFormatConfiguration().getIndentSize());
            }
        } finally {
            restoreBoolean(store, PREF.NO_PRIVILEGES, oldNoPrivileges, oldNoPrivilegesDefault);
            restoreInt(store, FORMATTER_PREF.INDENT_SIZE, oldIndentSize, oldIndentSizeDefault);
        }
    }

    @Test
    void copyFreezesEffectiveProjectOverrides() throws Exception {
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        boolean oldNoPrivileges = store.getBoolean(PREF.NO_PRIVILEGES);
        boolean oldNoPrivilegesDefault = store.isDefault(PREF.NO_PRIVILEGES);
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn("UISettingsPerformanceTest-" + UUID.randomUUID()); //$NON-NLS-1$
        var projectPrefs = new ProjectScope(project).getNode(UIConsts.PLUGIN_ID.THIS);

        try {
            store.setValue(PREF.NO_PRIVILEGES, false);
            projectPrefs.putBoolean(PROJ_PREF.ENABLE_PROJ_PREF_ROOT, true);
            projectPrefs.putBoolean(PREF.NO_PRIVILEGES, true);
            UISettings original = new UISettings(project, null, DatabaseType.PG);
            ISettings copy = original.copy();

            projectPrefs.putBoolean(PREF.NO_PRIVILEGES, false);
            store.setValue(PREF.NO_PRIVILEGES, true);

            assertTrue(original.isIgnorePrivileges());
            assertTrue(copy.isIgnorePrivileges());
        } finally {
            projectPrefs.removeNode();
            restoreBoolean(store, PREF.NO_PRIVILEGES, oldNoPrivileges, oldNoPrivilegesDefault);
        }
    }

    @Test
    void projectIndexParsesExactSchemaExclusionsOnlyForBuilderLoads() {
        Map<String, Object> prefs = new HashMap<>();
        prefs.put(PREF.PROJECT_INDEX_EXCLUDED_SCHEMAS, """
                # generated scratch objects
                  dummy_tmp

                reporting
                dummy_tmp
                """);

        UISettings ordinary = new UISettings(null, prefs, DatabaseType.PG);
        UISettings projectIndex = UISettings.forProjectIndex(
                null, prefs, DatabaseType.PG);

        assertFalse(ordinary.isAdditionalSchemaExcluded("dummy_tmp"));
        assertTrue(projectIndex.isAdditionalSchemaExcluded("dummy_tmp"));
        assertTrue(projectIndex.isAdditionalSchemaExcluded("reporting"));
        assertFalse(projectIndex.isAdditionalSchemaExcluded("DUMMY_TMP"));
        assertFalse(projectIndex.isAdditionalSchemaExcluded("dummy_tmp_2"));
        assertEquals(ParserWorkerDefaults.defaultWorkers(),
                projectIndex.getParserExecutionPolicy().workers());
        assertTrue(projectIndex.copy()
                .isAdditionalSchemaExcluded("dummy_tmp"));
    }

    @Test
    void getChangesNeverConsumesBuilderOnlySchemaExclusions() {
        Map<String, Object> prefs = new HashMap<>();
        prefs.put(PREF.PROJECT_INDEX_EXCLUDED_SCHEMAS, "dummy_tmp");
        prefs.put(PREF.PARALLEL_LOADING, true);
        prefs.put(PREF.PG_ROUTINE_BODY_SKIP_MATCHED_ANALYSIS, true);
        prefs.put(PREF.PG_CATALOG_CACHE_ROWS, true);

        UISettings settings = UISettings.forGetChanges(
                null, prefs, DatabaseType.PG, CACHE_ROOT);

        assertFalse(settings.isAdditionalSchemaExcluded("dummy_tmp"));
    }

    private UISettings createPgSettings(boolean skipMatchedAnalysis, boolean catalogCacheRows) {
        Map<String, Object> prefs = new HashMap<>();
        prefs.put(PREF.PARALLEL_LOADING, true);
        prefs.put(PREF.PG_ROUTINE_BODY_SKIP_MATCHED_ANALYSIS, skipMatchedAnalysis);
        prefs.put(PREF.PG_CATALOG_CACHE_ROWS, catalogCacheRows);
        return UISettings.forGetChanges(null, prefs, DatabaseType.PG, CACHE_ROOT);
    }

    private static void assertDedicatedPolicy(ISettings settings, int workers,
            int maxPending) {
        assertFalse(settings.getParserExecutionPolicy().shared());
        assertEquals(workers,
                settings.getParserExecutionPolicy().workers());
        assertEquals(maxPending,
                settings.getParserExecutionPolicy().maxPending());
        assertEquals(64L << 20,
                settings.getParserExecutionPolicy().maxPendingBytes());
    }

    private static void restoreBoolean(IPreferenceStore store, String key, boolean oldValue, boolean wasDefault) {
        if (wasDefault) {
            store.setToDefault(key);
        } else {
            store.setValue(key, oldValue);
        }
    }

    private static void restoreInt(IPreferenceStore store, String key, int oldValue, boolean wasDefault) {
        if (wasDefault) {
            store.setToDefault(key);
        } else {
            store.setValue(key, oldValue);
        }
    }
}
