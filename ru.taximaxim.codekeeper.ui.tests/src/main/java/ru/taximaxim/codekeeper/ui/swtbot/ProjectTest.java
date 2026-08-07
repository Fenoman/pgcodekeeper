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
package ru.taximaxim.codekeeper.ui.swtbot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.awt.AWTException;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import org.eclipse.swtbot.swt.finder.widgets.SWTBotTree;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import ru.taximaxim.codekeeper.ui.DatabaseType;
import ru.taximaxim.codekeeper.ui.localizations.Messages;

public class ProjectTest extends AbstractSwtBotTest {

    private static final Map<DatabaseType, Integer> EXPECTED_NODES_SIZE = Map.of(DatabaseType.PG, 6, DatabaseType.MS, 9,
            DatabaseType.CH, 4);

    @ParameterizedTest
    @EnumSource(DatabaseType.class)
    void createEmptyPgcodekeeperProjectTest(DatabaseType dbType) throws AWTException, IOException, URISyntaxException {
        var projectName = createName(dbType,  "_empty_test_project");

        createProject(projectName, dbType);
        assertNotNull(getProjectExplorer().getTreeItem(projectName));
    }

    @ParameterizedTest
    @EnumSource(DatabaseType.class)
    void createPgcodekeeperProjectWithDumpTest(DatabaseType dbType)
            throws AWTException, IOException, URISyntaxException {
        var projectName = createName(dbType, "_test_project");
        var dumpName = createName(dbType, "_dump_test.sql");
        createProject(projectName, dbType, dumpName);

        SWTBotTree projectExplorer = getProjectExplorer();

        projectExplorer.expandNode(projectName);
        projectExplorer.getTreeItem(projectName).selectAll();
        assertEquals(EXPECTED_NODES_SIZE.get(dbType), projectExplorer.getTreeItem(projectName).getNodes().size());
    }

    @ParameterizedTest
    @EnumSource(DatabaseType.class)
    void convertProjectTest(DatabaseType dbType) {
        var projectName = createName(dbType, "_convert_test_project");
        BOT.menu("File").menu("New").menu("Project...").click();
        BOT.tree().getTreeItem("General").expand().getNode("Project").select();
        BOT.button("Next >").click();
        BOT.textWithLabel("Project name:").setText(projectName);
        BOT.button("Finish").click();
        waitProjectCreation();

        getProjectExplorer().getTreeItem(projectName).contextMenu().menu("Configure")
                .menu("Convert to pgCodeKeeper project").click();
        BOT.comboBox().setSelection(dbType.getDbTypeName());
        BOT.button("OK").click();
        closeShell(Messages.ConvertProject_convert_dialog_title, "Yes");
        waitProjectCreation();

        var projectTree = getProjectExplorer().getTreeItem(projectName);
        projectTree.expand();
        projectTree.selectAll();

        assertEquals(2, projectTree.getNodes().size(), "DatabaseType: " + dbType.getDbTypeName());
    }

    @ParameterizedTest
    @EnumSource(DatabaseType.class)
    void importPgcodekeeperProjectTest(DatabaseType dbType) throws IOException, URISyntaxException {
        var projectName = createName(dbType,  "_import_test_project");
        // The import writes .project into the resource directory itself, and a
        // directory that already holds one cannot be imported again: the page
        // never completes and Finish stays disabled. Removing it has to happen
        // whatever the outcome. As the last statement of the happy path it did
        // not, so a single failure left the descriptor behind and every later
        // run failed on it — the suite could not recover without mvn clean.
        Path descriptor = Paths.get(getPath(projectName), ".project");
        Files.deleteIfExists(descriptor);
        try {
            BOT.menu("File").menu("Import...").click();
            BOT.tree().getTreeItem("pgCodeKeeper").select().expand().getNode("pgCodeKeeper Project").select();
            BOT.button("Next >").click();
            BOT.comboBoxWithLabel(Messages.database_type).setSelection(dbType.getDbTypeName());
            // Set the root directory on the page's own field instead of driving
            // the chooser. Browse opens a native DirectoryDialog, which SWTBot
            // cannot reach, so the path used to travel through the X11 clipboard
            // and a blind Ctrl+V, Tab, Enter aimed at whatever held focus —
            // nothing checked that it landed. The dialog only writes this same
            // field, and writing it fires the same modify listener, so the
            // wizard sees exactly what a user's choice gives it.
            BOT.textWithLabel(Messages.PgImportWizardImportPage_select_root_directory)
                    .setText(getPath(projectName));
            BOT.button("Finish").click();
            closeShell(Messages.ConvertProject_convert_dialog_title, "Yes");
            SWTBotTree projectExplorer = getProjectExplorer();
            projectExplorer.expandNode(projectName);
            assertEquals(2, projectExplorer.getTreeItem(projectName).getNodes().size());
        } finally {
            Files.deleteIfExists(descriptor);
        }
    }
}
