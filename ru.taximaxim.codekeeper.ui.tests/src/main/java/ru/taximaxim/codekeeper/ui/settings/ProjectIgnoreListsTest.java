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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.ignorelist.IIgnoreList;
import org.pgcodekeeper.core.ignorelist.IgnoreList;
import org.pgcodekeeper.core.ignorelist.IgnoredObject;
import org.pgcodekeeper.core.settings.ISettings;

import ru.taximaxim.codekeeper.ui.DatabaseType;
import ru.taximaxim.codekeeper.ui.TestUiSettings;
import ru.taximaxim.codekeeper.ui.dbstore.DbInfo;

/**
 * The rules a project hides by must reach every operation that answers to them.
 * <p>
 * The comparison drops what they hide and the writer of project files leaves a
 * hidden column out of a file, so settings built without them make the second
 * rule silently do nothing: the six audit columns of a database land in the
 * project file of a table the project declared it does not manage them for.
 */
class ProjectIgnoreListsTest {

    private static final String PROJECT_RULES = """
            SHOW ALL
            HIDE NONE created_by type=COLUMN
            """;

    @Test
    void exportSettingsCarryTheRulesOfTheProject(@TempDir Path projectDir) throws IOException {
        Files.writeString(projectDir.resolve(".pgcodekeeperignore"), PROJECT_RULES);

        ISettings settings = UISettings.forExport(project(projectDir));

        assertEquals(List.of("created_by"), ruleNames(settings.getIgnoreList()),
                "an export built without the project rules writes back the very columns they hide");
    }

    @Test
    void aProjectThatDeclaresNoRulesHidesNothingFromItsFiles(@TempDir Path projectDir) {
        ISettings settings = UISettings.forExport(project(projectDir));

        assertTrue(settings.getIgnoreList().getList().isEmpty(),
                "without rules the export must write exactly what it has always written");
    }

    @Test
    void exportSettingsCarryTheRulesTheComparisonHidBy(@TempDir Path projectDir) {
        IgnoreList comparisonRules = new IgnoreList();
        comparisonRules.add(rule("created_by", DbObjType.COLUMN));

        ISettings settings = UISettings.forExport(project(projectDir), comparisonRules);

        assertEquals(List.of("created_by"), ruleNames(settings.getIgnoreList()));
        assertNotSame(comparisonRules.getList().getFirst(), settings.getIgnoreList().getList().getFirst(),
                "merging a list rewrites its rules in place, so the comparison must keep its own");
    }

    @Test
    void theProjectFileIsRead(@TempDir Path projectDir) throws IOException {
        Files.writeString(projectDir.resolve(".pgcodekeeperignore"), PROJECT_RULES);

        assertEquals(List.of("created_by"),
                ruleNames(ProjectIgnoreLists.read(project(projectDir), null, null)));
    }

    @Test
    void theListsOfTheDatabaseOfThisRunAreRead(@TempDir Path projectDir) throws Exception {
        Path remoteRules = projectDir.resolve("remote.ignore");
        Files.writeString(remoteRules, """
                SHOW ALL
                HIDE REGEX 'archived_.*' type=TABLE
                """);
        var remote = new DbInfo(
                "candidate", "database", "", "", "localhost", 5432,
                true, false, List.of(remoteRules.toString()), Map.of(),
                DatabaseType.PG, false, "", "", null);

        assertEquals(List.of("archived_.*"),
                ruleNames(ProjectIgnoreLists.read(project(projectDir), null, remote)));
    }

    @Test
    void aRemoteThatIsNoDatabaseConnectionCarriesNoLists(@TempDir Path projectDir) {
        assertTrue(ProjectIgnoreLists.read(project(projectDir), null, "a project, not a connection")
                .getList().isEmpty());
    }

    @Test
    void rulesReachTheSettings() {
        ISettings settings = new TestUiSettings();
        IgnoreList rules = new IgnoreList();
        rules.add(rule("t_audit", DbObjType.TRIGGER));

        ProjectIgnoreLists.install(settings, rules);

        assertEquals(List.of("t_audit"), ruleNames(settings.getIgnoreList()));
    }

    @Test
    void rulesAreCopiedInsteadOfShared() {
        ISettings settings = new TestUiSettings();
        IgnoreList rules = new IgnoreList();
        IgnoredObject rule = rule("t_audit", DbObjType.TRIGGER);
        rules.add(rule);

        ProjectIgnoreLists.install(settings, rules);

        assertNotSame(rule, settings.getIgnoreList().getList().getFirst(),
                "merging a list rewrites its rules in place, so the caller must keep its own");
    }

    @Test
    void aWhiteListKeepsHiding() {
        ISettings settings = new TestUiSettings();
        IgnoreList rules = new IgnoreList();
        rules.setShow(false);

        ProjectIgnoreLists.install(settings, rules);

        assertTrue(!settings.getIgnoreList().isShow(),
                "a list that hides everything by default must keep doing so");
    }

    @Test
    void aBlackListLeavesAWhiteListAlone() {
        ISettings settings = new TestUiSettings();
        settings.getIgnoreList().setShow(false);

        ProjectIgnoreLists.install(settings, new IgnoreList());

        assertTrue(!settings.getIgnoreList().isShow(),
                "a black list must not turn an already hiding list into a showing one");
    }

    @Test
    void noRulesChangeNothing() {
        ISettings settings = new TestUiSettings();

        ProjectIgnoreLists.install(settings, new IgnoreList());

        assertTrue(settings.getIgnoreList().getList().isEmpty());
        assertTrue(settings.getIgnoreList().isShow(),
                "without rules everything must behave exactly as before");
    }

    private static IProject project(Path location) {
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn("ProjectIgnoreListsTest-" + UUID.randomUUID()); //$NON-NLS-1$
        when(project.getLocationURI()).thenReturn(location.toUri());
        when(project.getLocation()).thenReturn(IPath.fromOSString(location.toString()));
        return project;
    }

    private static List<String> ruleNames(IIgnoreList list) {
        return list.getList().stream().map(IgnoredObject::getName).toList();
    }

    private static IgnoredObject rule(String name, DbObjType type) {
        return new IgnoredObject(name, false, false, false, EnumSet.of(type));
    }
}
