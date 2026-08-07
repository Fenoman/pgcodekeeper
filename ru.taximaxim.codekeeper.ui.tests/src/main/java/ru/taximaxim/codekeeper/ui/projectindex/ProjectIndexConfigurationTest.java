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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

import ru.taximaxim.codekeeper.ui.DatabaseType;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexConfiguration.GlobalSettings;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexConfiguration.ProjectOverrides;

class ProjectIndexConfigurationTest {

    @Test
    void digestChangesForEveryInputThatChangesProjectIndexSemantics() {
        var baseline = new ProjectIndexConfiguration(
                DatabaseType.PG, false, false, false, false, false,
                "dummy_tmp");

        assertNotEquals(baseline.digest(), new ProjectIndexConfiguration(
                DatabaseType.MS, false, false, false, false, false,
                "dummy_tmp").digest());
        assertNotEquals(baseline.digest(), new ProjectIndexConfiguration(
                DatabaseType.PG, true, false, false, false, false,
                "dummy_tmp").digest());
        assertNotEquals(baseline.digest(), new ProjectIndexConfiguration(
                DatabaseType.PG, false, true, false, false, false,
                "dummy_tmp").digest());
        assertNotEquals(baseline.digest(), new ProjectIndexConfiguration(
                DatabaseType.PG, false, false, true, false, false,
                "dummy_tmp").digest());
        assertNotEquals(baseline.digest(), new ProjectIndexConfiguration(
                DatabaseType.PG, false, false, false, true, false,
                "dummy_tmp").digest());
        assertNotEquals(baseline.digest(), new ProjectIndexConfiguration(
                DatabaseType.PG, false, false, false, false, true,
                "dummy_tmp").digest(),
                "an index nothing maintains is not the same index");
        assertNotEquals(baseline.digest(), new ProjectIndexConfiguration(
                DatabaseType.PG, false, false, false, false, false,
                "reporting").digest());
    }

    @Test
    void digestUsesCanonicalSchemaOrderButPreservesEffectiveSource() {
        var first = new ProjectIndexConfiguration(
                DatabaseType.PG, false, true, false, false, false,
                "reporting\ndummy_tmp");
        var reordered = new ProjectIndexConfiguration(
                DatabaseType.PG, false, true, false, false, false,
                "dummy_tmp\nreporting");
        var projectSource = new ProjectIndexConfiguration(
                DatabaseType.PG, true, true, false, false, false,
                "dummy_tmp\nreporting");

        assertEquals(first.digest(), reordered.digest());
        assertNotEquals(first.digest(), projectSource.digest());
    }

    /**
     * The platform applies a preference file by removing and re-adding every
     * key, and fires the change event from inside {@code remove}, while the key
     * is gone. Whoever reads the configuration during that window sees the
     * project without it.
     * <p>
     * This is the shape of a committed OmniX project file: the override root is
     * on, and the two index keys disagree with the workspace defaults they
     * would otherwise inherit. Every such key changes the fingerprint while it
     * is momentarily gone, so the guard in
     * {@code ProjectIndexConfigurationInvalidator} cannot hold and the
     * persisted index is retired for a setting nobody edited.
     */
    @Test
    void fingerprintChangesWhileTheKeyIsMomentarilyGone() {
        var workspaceDefaults = new GlobalSettings(false, false, false, false,
                "");
        var committed = new ProjectOverrides(true, false, true, true,
                "dummy_tmp");

        String settled = ProjectIndexConfiguration.resolve(DatabaseType.PG,
                true, workspaceDefaults, committed).digest();

        assertNotEquals(settled, ProjectIndexConfiguration.resolve(
                DatabaseType.PG, true, workspaceDefaults,
                new ProjectOverrides(true, false, null, true, "dummy_tmp"))
                .digest(),
                "projectIndexIncrementalAddedFiles falls back to the "
                        + "workspace default while it is gone");
        assertNotEquals(settled, ProjectIndexConfiguration.resolve(
                DatabaseType.PG, true, workspaceDefaults,
                new ProjectOverrides(true, false, true, true, null)).digest(),
                "projectIndexExcludedSchemas falls back to the empty "
                        + "workspace value while it is gone");
        assertNotEquals(settled, ProjectIndexConfiguration.resolve(
                DatabaseType.PG, true, workspaceDefaults,
                new ProjectOverrides(null, false, true, true, "dummy_tmp"))
                .digest(),
                "prefNoPrivileges falls back to the workspace default "
                        + "while it is gone");
        assertNotEquals(settled, ProjectIndexConfiguration.resolve(
                DatabaseType.PG, true, workspaceDefaults,
                new ProjectOverrides(true, false, true, null, "dummy_tmp"))
                .digest(),
                "projectUpdatedFromDatabaseOnly falls back to the workspace "
                        + "default while it is gone");
        assertNotEquals(settled, ProjectIndexConfiguration.resolve(
                DatabaseType.PG, false, workspaceDefaults, committed).digest(),
                "prefEnableProjPrefRoot disables every override at once "
                        + "while it is gone");
    }

    /**
     * The other half of the same rule, and the reason the report must be
     * measured rather than argued: the fingerprint follows the effective
     * configuration, not the file. A key whose value the workspace would have
     * supplied anyway is invisible while it is gone, and the guard holds.
     */
    @Test
    void fingerprintSurvivesTheRemovalOfAKeyThatMatchedTheWorkspace() {
        var workspaceDefaults = new GlobalSettings(false, false, false, false,
                "");
        var committed = new ProjectOverrides(true, false, true, false,
                "dummy_tmp");

        assertEquals(ProjectIndexConfiguration.resolve(DatabaseType.PG, true,
                workspaceDefaults, committed).digest(),
                ProjectIndexConfiguration.resolve(DatabaseType.PG, true,
                        workspaceDefaults,
                        new ProjectOverrides(true, null, true, false,
                                "dummy_tmp"))
                        .digest(),
                "prefEnableBodyDependencies=false is what the workspace "
                        + "default already says");
    }

    @Test
    void projectOverridesFallBackToGlobalSettingsPerMissingField() {
        var global = new GlobalSettings(true, false, false, true, "dummy_tmp");
        var overrides = new ProjectOverrides(false, null, null, null,
                "reporting");

        var effective = ProjectIndexConfiguration.resolve(
                DatabaseType.PG, true, global, overrides);

        assertEquals(new ProjectIndexConfiguration(
                DatabaseType.PG, true, false, false, false, true, "reporting"),
                effective);
    }

    @Test
    void globalChangeAffectsOnlyActuallyInheritedOverrideFields() {
        var before = new GlobalSettings(false, false, false, false,
                "dummy_tmp");
        var after = new GlobalSettings(true, true, false, true, "reporting");
        var explicit = new ProjectOverrides(false, false, false, false,
                "private");
        var partlyInherited = new ProjectOverrides(
                false, null, false, false, "private");

        assertEquals(
                ProjectIndexConfiguration.resolve(DatabaseType.PG, true,
                        before, explicit).digest(),
                ProjectIndexConfiguration.resolve(DatabaseType.PG, true,
                        after, explicit).digest());
        assertNotEquals(
                ProjectIndexConfiguration.resolve(DatabaseType.PG, true,
                        before, partlyInherited).digest(),
                ProjectIndexConfiguration.resolve(DatabaseType.PG, true,
                        after, partlyInherited).digest());
    }
}
