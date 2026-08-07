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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.AWTException;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.swtbot.eclipse.finder.widgets.SWTBotEclipseEditor;
import org.eclipse.swtbot.swt.finder.waits.DefaultCondition;
import org.eclipse.swtbot.swt.finder.widgets.SWTBotTreeItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import ru.taximaxim.codekeeper.ui.DatabaseType;
import ru.taximaxim.codekeeper.ui.localizations.Messages;
import ru.taximaxim.codekeeper.ui.swtbot.utils.ShellBotProvider;

public class WizardsTest extends AbstractSwtBotTest {

    private static final Map<DatabaseType, String> DEFAULT_TYPE = Map.of(
            DatabaseType.PG, "dataType",
            DatabaseType.MS, "typeName",
            DatabaseType.CH, "dataType"
    );

    @ParameterizedTest
    @EnumSource(DatabaseType.class)
    void createObjectAndMockDataTest(DatabaseType dbType)
            throws AWTException, IOException, URISyntaxException {
        var projectName = createName(dbType, "_wizzard_test_project");
        createProject(projectName, dbType);
        SWTBotTreeItem project = getProjectExplorer().getTreeItem(projectName);

        project.contextMenu("New").menu("SQL Object").click();
        BOT.textWithLabel("Object name: ").setText("test.table1");
        BOT.comboBox(1).setSelection("TABLE");
        BOT.button("Finish").click();

        SWTBotEclipseEditor editor = BOT.activeEditor().toTextEditor();
        editor.setText(editor.getText().replace(DEFAULT_TYPE.get(dbType), "int"));
        editor.save();

        waitAction();

        editor.setFocus();
        editor.contextMenu("Generate mock data...").click();

        BOT.button("Finish").click();
        assertEquals(3, BOT.editors().size());
        var mocDataEditor = BOT.activeEditor();
        assertTrue(mocDataEditor.getTitle().contains("table1"), "mock data editor didn't open");
        assertNotNull(mocDataEditor.toTextEditor().getText());
    }

    @Test
    void diffWizzardTest() throws AWTException, IOException, URISyntaxException {
        int sourceIndex = 3;
        int targetIndex = 4;
        var dumpName = "pg_dump_test.sql";
        var emptyDumpName = "test.sql";

        BOT.menu("pgCodeKeeper").menu("Diff Wizard").click();

        ShellBotProvider shell = new ShellBotProvider(BOT.activeShell());
        selectDump(shell, dumpName, 0);
        selectDump(shell, emptyDumpName, 1);
        BOT.button("Next >").click();

        waitForLabelContains(sourceIndex, dumpName);
        waitForLabelContains(targetIndex, emptyDumpName);
        assertTrue(BOT.label(sourceIndex).getText().contains(dumpName));
        assertTrue(BOT.label(targetIndex).getText().contains(emptyDumpName));

        BOT.toolbarButtonWithTooltip(Messages.select_all).click();

        BOT.button(Messages.DiffWizard_generate_script).click();
        checkMigrationScript("diff_wizard_result");

        BOT.shell(Messages.diffWizard_Diff).activate();
        BOT.button(Messages.DiffWizard_swap_sides).click();
        waitForLabelContains(sourceIndex, emptyDumpName);
        waitForLabelContains(targetIndex, dumpName);
        assertTrue(BOT.label(sourceIndex).getText().contains(emptyDumpName));
        assertTrue(BOT.label(targetIndex).getText().contains(dumpName));

        BOT.button(Messages.DiffWizard_generate_script).click();
        checkMigrationScript("diff_wizard_result");

        BOT.shell(Messages.diffWizard_Diff).activate();
        BOT.button("Finish").click();
    }

    /**
     * Waits until the label shows the expected text instead of reading it right
     * away: the wizard fills both labels only when the comparison it runs in a
     * forked thread is over, and the event loop keeps running meanwhile, so a
     * plain read races the repaint and sees the previous side.
     * <p>
     * The text last seen is remembered while polling and reported on timeout, so
     * that a failure says what was expected and what was shown. Re-reading the
     * label in the failure message would risk throwing there and losing both.
     *
     * @param labelIndex
     *            index of the label among the visible ones
     * @param expected
     *            the substring the label is expected to show
     */
    private void waitForLabelContains(int labelIndex, String expected) {
        AtomicReference<String> lastSeenText = new AtomicReference<>("<label not found>");
        BOT.waitUntil(new DefaultCondition() {

            @Override
            public boolean test() {
                String text = BOT.label(labelIndex).getText();
                lastSeenText.set(text);
                return text.contains(expected);
            }

            @Override
            public String getFailureMessage() {
                return "The label by index " + labelIndex + " has not been updated. Current text: "
                        + lastSeenText.get() + "; expected to contain: " + expected;
            }
        }, ACTION_TIMEOUT);
    }
}
