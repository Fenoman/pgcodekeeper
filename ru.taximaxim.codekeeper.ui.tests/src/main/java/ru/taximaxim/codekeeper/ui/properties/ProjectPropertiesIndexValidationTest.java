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
package ru.taximaxim.codekeeper.ui.properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import ru.taximaxim.codekeeper.ui.DatabaseType;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexBuildConfiguration;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexConfiguration;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexSchemaExclusions;

/**
 * The project properties page refuses a schema exclusion no build could read
 * back, for a project of every database type.
 * <p>
 * The validation may not be gated on the database type. The field is offered to
 * every dialect and its value is parsed for every dialect, so a gate would let a
 * MS SQL or ClickHouse project save a name with a dot in it - or one the file
 * system cannot carry - with nothing said, and pay for it later. Not with one
 * refused build either: the exclusions are hashed into the identity of an index
 * of every type, so the fingerprint of such a project matches nothing and every
 * cycle re-reads it, fails to stamp an identity and falls back to a full build
 * that fails the same way.
 */
class ProjectPropertiesIndexValidationTest {

    /** Both shapes {@code ProjectIndexSchemaExclusions} knows how to refuse. */
    private static final List<String> UNREADABLE =
            List.of("tenant.eu", "sales/eu");

    @Test
    void everyProjectTypeHasItsSchemaExclusionsValidated() {
        for (DatabaseType databaseType : DatabaseType.values()) {
            assertTrue(ProjectProperties
                    .validatesProjectIndexExcludedSchemas(databaseType),
                    databaseType + ": this page shows the excluded schemas"
                            + " field and saves what it holds without"
                            + " validating it");
        }
    }

    /**
     * And what the page refuses is exactly what a build cannot read: the same
     * value, through the same parser, for a project of the same type. The two
     * ends have to agree - a page stricter than the build refuses a usable
     * project, and a page looser than the build is what this was.
     */
    @Test
    void whatThePageRefusesIsWhatNoBuildCanRead() {
        for (DatabaseType databaseType : DatabaseType.values()) {
            for (String unreadable : UNREADABLE) {
                assertTrue(ProjectIndexSchemaExclusions.validate(unreadable)
                        .isPresent(),
                        databaseType + ": the page lets \"" + unreadable
                                + "\" through");
                assertThrows(IllegalArgumentException.class,
                        () -> capture(databaseType, unreadable),
                        databaseType + ": a build reads \"" + unreadable
                                + "\" that the page refuses");
            }
            assertDoesNotThrow(() -> capture(databaseType, "audit\n# a note"),
                    databaseType + ": a build cannot read a value the page"
                            + " accepts");
        }
    }

    private static ProjectIndexBuildConfiguration capture(
            DatabaseType databaseType, String excludedSchemas) {
        return ProjectIndexBuildConfiguration.capture(
                () -> new ProjectIndexConfiguration(databaseType, true, false,
                        false, false, false, excludedSchemas));
    }
}
