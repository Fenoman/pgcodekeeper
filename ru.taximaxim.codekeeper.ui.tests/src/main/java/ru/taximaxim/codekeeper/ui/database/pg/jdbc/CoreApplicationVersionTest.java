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
package ru.taximaxim.codekeeper.ui.database.pg.jdbc;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.utils.Utils;

import ru.taximaxim.codekeeper.ui.dbstore.DbInfo;

class CoreApplicationVersionTest {

    private static final String CORE_VERSION = "15.3.0-neo2"; //$NON-NLS-1$

    @Test
    void packagedCoreVersionReachesPostgreSqlApplicationName() {
        Properties properties = new TestPgDbInfoConnector().properties();

        assertAll(
                () -> assertEquals(CORE_VERSION, Utils.getVersion()),
                () -> assertEquals(
                        "pgCodeKeeper, version: " + CORE_VERSION, //$NON-NLS-1$
                        properties.getProperty("ApplicationName"))); //$NON-NLS-1$
    }

    private static final class TestPgDbInfoConnector extends PgDbInfoConnector {

        TestPgDbInfoConnector() {
            super(new DbInfo("test", "database", "", "", "localhost", 5432), 0);
        }

        public Properties properties() {
            return super.makeProperties();
        }
    }
}
