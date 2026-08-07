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

import org.eclipse.jface.preference.IPreferenceStore;

import ru.taximaxim.codekeeper.ui.Activator;
import ru.taximaxim.codekeeper.ui.UIConsts.PREF;

/**
 * Resolves bounded parser-worker preferences for a newly created operation.
 */
public final class ParserWorkerPreferences {

    public static final int MIN_WORKERS = 1;
    public static final int MAX_WORKERS = 64;
    public static final int GENERIC_DEFAULT_WORKERS = 2;

    public static int getChangesWorkers() {
        return getChangesWorkers(
                Activator.getDefault().getPreferenceStore());
    }

    public static int getProjectIndexWorkers() {
        return getProjectIndexWorkers(
                Activator.getDefault().getPreferenceStore());
    }

    static int getChangesWorkers(IPreferenceStore store) {
        return resolve(store, PREF.GET_CHANGES_PARSER_WORKERS);
    }

    static int getProjectIndexWorkers(IPreferenceStore store) {
        return resolve(store, PREF.PROJECT_INDEX_PARSER_WORKERS);
    }

    private static int resolve(IPreferenceStore store, String key) {
        int value = store.getInt(key);
        if (isValid(value)) {
            return value;
        }

        int activeDefault = store.getDefaultInt(key);
        return isValid(activeDefault)
                ? activeDefault : GENERIC_DEFAULT_WORKERS;
    }

    private static boolean isValid(int value) {
        return value >= MIN_WORKERS && value <= MAX_WORKERS;
    }

    private ParserWorkerPreferences() {
    }
}
