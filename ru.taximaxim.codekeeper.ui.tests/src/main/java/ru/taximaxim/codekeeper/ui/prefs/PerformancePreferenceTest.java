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
package ru.taximaxim.codekeeper.ui.prefs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.eclipse.jface.preference.PreferenceStore;
import org.junit.jupiter.api.Test;

import ru.taximaxim.codekeeper.ui.DatabaseType;
import ru.taximaxim.codekeeper.ui.UIConsts.PREF;
import ru.taximaxim.codekeeper.ui.UIConsts.PROJ_PREF;
import ru.taximaxim.codekeeper.ui.prefs.Preferences.ChangeImpact;
import ru.taximaxim.codekeeper.ui.settings.ParserWorkerDefaults;

class PerformancePreferenceTest {

    @Test
    void postgresPerformancePreferencesUseLowTrafficEclipseDefaults() {
        var store = new PreferenceStore();

        Preferences.initialize(store);

        assertTrue(store.getDefaultBoolean(PREF.PG_ROUTINE_BODY_SKIP_MATCHED_ANALYSIS));
        assertTrue(store.getDefaultBoolean(PREF.PG_CATALOG_CACHE_ROWS));
        assertTrue(store.getDefaultBoolean(PREF.PARALLEL_LOADING));
        assertEquals("", store.getDefaultString(
                PREF.PROJECT_INDEX_EXCLUDED_SCHEMAS));
    }

    @Test
    void earlySchemaExclusionsAreBuilderSettingsNotGetChangesSettings() {
        List<String> globalNames = Preferences
                .getPreferencesForScope(PreferenceCategory.MAIN,
                        PreferenceScope.GLOBAL, DatabaseType.PG)
                .stream()
                .map(AbstractPreference::getPreferenceName)
                .toList();
        List<String> projectNames = Preferences
                .getPreferencesForScope(PreferenceCategory.MAIN,
                        PreferenceScope.PROJECT, DatabaseType.PG)
                .stream()
                .map(AbstractPreference::getPreferenceName)
                .toList();
        List<String> getChangesNames = Preferences
                .getPreferencesForScope(PreferenceCategory.MAIN,
                        PreferenceScope.CUSTOM_GET_CHANGES, DatabaseType.PG)
                .stream()
                .map(AbstractPreference::getPreferenceName)
                .toList();

        assertTrue(globalNames.contains(PREF.PROJECT_INDEX_EXCLUDED_SCHEMAS));
        assertTrue(projectNames.contains(PREF.PROJECT_INDEX_EXCLUDED_SCHEMAS));
        assertFalse(getChangesNames.contains(
                PREF.PROJECT_INDEX_EXCLUDED_SCHEMAS));
        assertFalse(Preferences.getPreferencesWithNeedReset().contains(
                PREF.PROJECT_INDEX_EXCLUDED_SCHEMAS));
        assertEquals(ChangeImpact.PROJECT_INDEX,
                Preferences.getChangeImpact(
                        PREF.PROJECT_INDEX_EXCLUDED_SCHEMAS));
        // a committed project file carries this one too, and every key in that
        // file is re-applied whenever the workspace refreshes it
        assertEquals(ChangeImpact.PROJECT_INDEX,
                Preferences.getChangeImpact(
                        PREF.PROJECT_INDEX_INCREMENTAL_ADDED_FILES));
        assertEquals(ChangeImpact.BOTH,
                Preferences.getChangeImpact(PREF.NO_PRIVILEGES));
        assertEquals(ChangeImpact.BOTH,
                Preferences.getChangeImpact(PREF.ENABLE_BODY_DEPENDENCIES));
        assertEquals(ChangeImpact.BOTH,
                Preferences.getChangeImpact(
                        PROJ_PREF.ENABLE_PROJ_PREF_ROOT));
        assertEquals(ChangeImpact.COMPARISON,
                Preferences.getChangeImpact(PREF.IGNORE_COLUMN_ORDER));
    }

    @Test
    void postgresPerformancePreferencesAreVisibleForGetChanges() {
        List<String> preferenceNames = Preferences
                .getPreferencesForScope(PreferenceCategory.MAIN, PreferenceScope.CUSTOM_GET_CHANGES, DatabaseType.PG)
                .stream()
                .map(AbstractPreference::getPreferenceName)
                .toList();

        assertTrue(preferenceNames.contains(PREF.PG_ROUTINE_BODY_SKIP_MATCHED_ANALYSIS));
        assertTrue(preferenceNames.contains(PREF.PG_CATALOG_CACHE_ROWS));
    }

    @Test
    void parserWorkersScaleWithTheMachineInEveryDeployment() {
        var store = new PreferenceStore();

        PreferenceInitializer.initializeGeneralDefaults(store);

        int expected = ParserWorkerDefaults.defaultWorkers();
        assertEquals(expected, store.getDefaultInt(
                PREF.GET_CHANGES_PARSER_WORKERS));
        assertEquals(expected, store.getDefaultInt(
                PREF.PROJECT_INDEX_PARSER_WORKERS));
    }

    @Test
    void bothParserPreferencesShareOneDefault() {
        var store = new PreferenceStore();

        PreferenceInitializer.initializeGeneralDefaults(store, 6);

        assertEquals(6, store.getDefaultInt(
                PREF.GET_CHANGES_PARSER_WORKERS));
        assertEquals(6, store.getDefaultInt(
                PREF.PROJECT_INDEX_PARSER_WORKERS));
        assertTrue(store.isDefault(PREF.GET_CHANGES_PARSER_WORKERS),
                "the tuning is a default, so a user value still wins");
    }

    @Test
    void parserCacheCleaningDefaultsToTenMinutes() {
        var store = new PreferenceStore();

        PreferenceInitializer.initializeGeneralDefaults(store);

        assertEquals(10, store.getDefaultInt(PREF.PARSER_CACHE_CLEANING_INTERVAL));
        assertTrue(store.getDefaultBoolean(PREF.HEAP_SIZE_WARNING));
        store.setValue(PREF.PARSER_CACHE_CLEANING_INTERVAL, 0);
        assertEquals(0, store.getInt(PREF.PARSER_CACHE_CLEANING_INTERVAL));
    }
}
