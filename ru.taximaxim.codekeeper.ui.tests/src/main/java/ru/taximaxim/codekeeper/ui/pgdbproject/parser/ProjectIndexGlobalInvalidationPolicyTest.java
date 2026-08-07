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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ProjectIndexGlobalInvalidationPolicyTest {

    @Test
    void acceptsEveryAccessiblePgCodeKeeperProject() {
        // The database type stopped deciding this when the index stopped being
        // PostgreSQL-only: a MS SQL or ClickHouse project has an index to
        // invalidate like any other.
        assertTrue(ProjectIndexGlobalInvalidationPolicy.accepts(true, true));
        assertFalse(ProjectIndexGlobalInvalidationPolicy.accepts(false, true));
        assertFalse(ProjectIndexGlobalInvalidationPolicy.accepts(true, false));
        assertFalse(ProjectIndexGlobalInvalidationPolicy.accepts(false, false));
    }
}
