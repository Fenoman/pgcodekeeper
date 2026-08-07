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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ru.taximaxim.codekeeper.ui.DatabaseType;
import ru.taximaxim.codekeeper.ui.UIConsts.PREF;

/**
 * The project editor compares the project against the database and reverts the
 * tree before it generates its script, so its comparison runs in the opposite
 * direction to its migration. A difference the migration cannot express - a
 * column collation the state it produces does not name - can only be told from
 * one it can when the core knows which of the two compared states that is.
 */
class UISettingsComparisonDirectionTest {

    private static final Path CACHE_ROOT = Path.of("workspace", ".metadata", ".plugins",
            "ru.taximaxim.codekeeper.ui", "pg-catalog-row-cache");

    @Test
    void gettingChangesDeclaresThatItsMigrationProducesTheOldSide() {
        for (DatabaseType dbType : DatabaseType.values()) {
            assertTrue(UISettings.forGetChanges(null, Map.of(), dbType, CACHE_ROOT).isMigrationTargetOldSide(),
                    "broken for " + dbType);
        }
    }

    @Test
    void everyOtherComparisonRunsInTheDirectionOfItsScript() {
        assertFalse(new UISettings(null, Map.of(), DatabaseType.PG).isMigrationTargetOldSide(),
                "the diff wizard and the quick update compare the source with the target of their script");
    }

    /**
     * The name order of columns is display only: the settings that carry a
     * generated script must never ask for it, whatever the preferences say.
     */
    @Test
    void gettingChangesNeverAsksForTheDisplayOrderOfColumns() {
        UISettings generating = UISettings.forGetChanges(null, Map.of(PREF.IGNORE_COLUMN_ORDER, true),
                DatabaseType.PG, CACHE_ROOT);

        assertTrue(generating.isIgnoreColumnOrder());
        assertFalse(generating.isSortColumnsForDisplay(), "a generated script renders the stored column order");
        assertFalse(generating.copy().isSortColumnsForDisplay(), "and so does every copy of those settings");
    }
}
