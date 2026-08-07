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
package ru.taximaxim.codekeeper.ui.projectindex;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HexFormat;
import java.util.Set;

import org.junit.jupiter.api.Test;

import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexSchemaExclusions.InvalidReason;

class ProjectIndexSchemaExclusionsTest {

    @Test
    void parserKeepsExactUnquotedPostgresNamesAndIgnoresFormatting() {
        Set<String> names = ProjectIndexSchemaExclusions.parse("""
                # generated schemas
                  dummy_tmp
                Camel Case
                Отчёты
                dummy_tmp
                """);

        assertEquals(Set.of("dummy_tmp", "Camel Case", "Отчёты"), names);
        assertTrue(names.contains("Camel Case"));
        assertTrue(names.contains("Отчёты"));
        assertFalse(names.contains("camel case"));
    }

    @Test
    void validationRejectsNamesThatCoreCannotFilterSafely() {
        var dotted = ProjectIndexSchemaExclusions.validate("tenant.eu");
        var quoted = ProjectIndexSchemaExclusions.validate("\"Camel Case\"");
        var path = ProjectIndexSchemaExclusions.validate("sales/eu");

        assertEquals(InvalidReason.DOTTED, dotted.orElseThrow().reason());
        assertEquals(1, dotted.orElseThrow().lineNumber());
        assertEquals(InvalidReason.FILESYSTEM_UNSAFE,
                quoted.orElseThrow().reason());
        assertEquals(InvalidReason.FILESYSTEM_UNSAFE,
                path.orElseThrow().reason());
        assertThrows(IllegalArgumentException.class,
                () -> ProjectIndexSchemaExclusions.parse("tenant.eu"));
    }

    @Test
    void canonicalFormAndDigestAreStableAndOrderIndependent() {
        String first = """
                Отчёты
                dummy_tmp
                # ignored
                dummy_tmp
                """;
        String second = "dummy_tmp\nОтчёты\n";
        byte[] expected = HexFormat.of().parseHex(
                "5047434b5f534348454d415f4558434c5553494f4e535f5631"
                + "00000002"
                + "0000000964756d6d795f746d70"
                + "0000000cd09ed182d187d191d182d18b");

        assertArrayEquals(expected,
                ProjectIndexSchemaExclusions.canonicalForm(first));
        assertArrayEquals(ProjectIndexSchemaExclusions.canonicalForm(first),
                ProjectIndexSchemaExclusions.canonicalForm(second));
        assertEquals(
                "cf0f9b2b8f29e99e86c9fb71979562b64f8db99fa8459f7143b66a17971ee7e6",
                ProjectIndexSchemaExclusions.digest(first));
        assertEquals(ProjectIndexSchemaExclusions.digest(first),
                ProjectIndexSchemaExclusions.digest(second));
    }

    @Test
    void digestPreservesCaseAndUnicodeCodePointsExactly() {
        assertFalse(ProjectIndexSchemaExclusions.digest("Отчёты")
                .equals(ProjectIndexSchemaExclusions.digest("отчёты")));
        assertFalse(ProjectIndexSchemaExclusions.digest("\u00e9")
                .equals(ProjectIndexSchemaExclusions.digest("e\u0301")));
    }
}
