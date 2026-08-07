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

import org.eclipse.jface.preference.PreferenceStore;
import org.junit.jupiter.api.Test;

import ru.taximaxim.codekeeper.ui.UIConsts.PREF;

class ParserWorkerPreferencesTest {

    @Test
    void resolvesIndependentExplicitWorkerCounts() {
        var store = defaults(2, 2);
        store.setValue(PREF.GET_CHANGES_PARSER_WORKERS, 6);
        store.setValue(PREF.PROJECT_INDEX_PARSER_WORKERS, 5);

        assertEquals(6,
                ParserWorkerPreferences.getChangesWorkers(store));
        assertEquals(5,
                ParserWorkerPreferences.getProjectIndexWorkers(store));
    }

    @Test
    void acceptsBothEndsOfTheSupportedRange() {
        var store = defaults(2, 2);
        store.setValue(PREF.GET_CHANGES_PARSER_WORKERS, 1);
        store.setValue(PREF.PROJECT_INDEX_PARSER_WORKERS, 64);

        assertEquals(1,
                ParserWorkerPreferences.getChangesWorkers(store));
        assertEquals(64,
                ParserWorkerPreferences.getProjectIndexWorkers(store));
    }

    @Test
    void invalidCurrentValuesFallBackToTheActiveDefaults() {
        var store = defaults(6, 5);

        for (int invalid : new int[] { -1, 0, 65 }) {
            store.setValue(PREF.GET_CHANGES_PARSER_WORKERS, invalid);
            store.setValue(PREF.PROJECT_INDEX_PARSER_WORKERS, invalid);

            assertEquals(6,
                    ParserWorkerPreferences.getChangesWorkers(store));
            assertEquals(5,
                    ParserWorkerPreferences.getProjectIndexWorkers(store));
        }
    }

    @Test
    void invalidActiveDefaultFallsBackToTheGenericDefault() {
        var store = defaults(0, 65);
        store.setValue(PREF.GET_CHANGES_PARSER_WORKERS, -1);
        store.setValue(PREF.PROJECT_INDEX_PARSER_WORKERS, 0);

        assertEquals(2,
                ParserWorkerPreferences.getChangesWorkers(store));
        assertEquals(2,
                ParserWorkerPreferences.getProjectIndexWorkers(store));
    }

    private static PreferenceStore defaults(int getChanges,
            int projectIndex) {
        var store = new PreferenceStore();
        store.setDefault(PREF.GET_CHANGES_PARSER_WORKERS, getChanges);
        store.setDefault(PREF.PROJECT_INDEX_PARSER_WORKERS, projectIndex);
        return store;
    }
}
