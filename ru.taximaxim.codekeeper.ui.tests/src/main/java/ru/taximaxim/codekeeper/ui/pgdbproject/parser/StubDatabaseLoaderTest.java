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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.settings.ISettings;

class StubDatabaseLoaderTest {

    @Test
    void legacyWrapperKeepsCompatibilityForCallersWithoutSettings() {
        IDatabase database = new PgDatabaseProvider().createDatabase();
        var loader = new StubDatabaseLoader(database, "legacy");

        assertSame(database, loader.getDatabase());
        assertThrows(IllegalStateException.class, loader::getSettings);
        assertEquals(0, loader.getErrors().size());
    }

    @Test
    void exposesFinalSettingsForAlreadyLoadedComparisonModel() {
        IDatabase database = new PgDatabaseProvider().createDatabase();
        ISettings settings = new CoreSettings();
        settings.addError("load warning");

        var loader = new StubDatabaseLoader(database, "project", settings);

        assertSame(database, loader.getDatabase());
        assertSame(settings, loader.getSettings());
        assertEquals(settings.getErrors(), loader.getErrors());
        assertThrows(UnsupportedOperationException.class,
                () -> loader.getErrors().add("must stay read-only"));
    }
}
