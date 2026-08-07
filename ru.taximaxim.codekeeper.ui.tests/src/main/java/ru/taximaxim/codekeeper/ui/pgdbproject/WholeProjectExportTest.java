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
package ru.taximaxim.codekeeper.ui.pgdbproject;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgcodekeeper.core.database.api.IDatabaseProvider;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.base.loader.AbstractProjectLoader;
import org.pgcodekeeper.core.database.base.project.AbstractWorkDirs;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.settings.ISettings;
import org.pgcodekeeper.core.utils.InputStreamProvider;

import ru.taximaxim.codekeeper.ui.DatabaseType;
import ru.taximaxim.codekeeper.ui.settings.UISettings;
import ru.taximaxim.codekeeper.ui.utils.ProjectUtils;

/**
 * Which side of the line each of the two whole-project exports sits on.
 * <p>
 * The rules a project hides by apply when a database is written into its files
 * and must not apply when the project's own model is written back into them.
 * The two cases share an exporter and differ by one method call, so both are
 * pinned here: the first because a project created out of a database used to
 * take the path of the second and quietly wrote back everything its rules hide,
 * the second because the obvious repair for that - teaching the shared exporter
 * the rules - would delete those very columns from the files of a project being
 * laid out anew.
 * <p>
 * The core draws the same line for itself in
 * {@code ExportOmitsHiddenColumnsTest}; what is pinned here is which of the two
 * core calls each of the plugin's two whole-project writers makes.
 */
class WholeProjectExportTest {

    /** The project leaves one column and one whole table to the database. */
    private static final String RULES = """
            SHOW ALL
            HIDE NONE s_creator type=COLUMN
            HIDE NONE audit_log type=TABLE
            """;

    private static final String DATABASE = """
            CREATE SCHEMA dbo;

            CREATE TABLE dbo.doc (
                id bigint NOT NULL,
                title text,
                s_creator text
            );

            CREATE TABLE dbo.audit_log (
                id bigint NOT NULL
            );
            """;

    private static final String DOC_FILE = "SCHEMA/dbo/TABLE/doc.sql";
    private static final String AUDIT_LOG_FILE = "SCHEMA/dbo/TABLE/audit_log.sql";

    private final IDatabaseProvider provider = DatabaseType.PG.getDatabaseProvider();

    /**
     * The case that sent this here: a project initialised over a directory that
     * already carries a rules file. The wizard writes a database into it, so the
     * rules have their say, exactly as they do when anything else is applied to
     * a project later on.
     */
    @Test
    void aProjectCreatedFromADatabaseLeavesOutTheColumnsItsRulesHide(@TempDir Path projectDir)
            throws IOException, InterruptedException {
        Files.writeString(projectDir.resolve(AbstractProjectLoader.IGNORE_FILE), RULES);

        WholeProjectExport.fromDatabase(provider, load(DATABASE), projectDir, exportSettings(projectDir));

        String doc = Files.readString(projectDir.resolve(DOC_FILE), StandardCharsets.UTF_8);
        assertTrue(doc.contains("title"),
                "the columns the project manages must be written: " + doc);
        assertFalse(doc.contains("s_creator"),
                "a column the project declares unmanaged is not pgCodeKeeper's to write: " + doc);
    }

    /**
     * An object a rule hides gets no file at all, the same answer the comparison
     * gives it every day.
     */
    @Test
    void aProjectCreatedFromADatabaseGetsNoFileForAnObjectItsRulesHide(@TempDir Path projectDir)
            throws IOException, InterruptedException {
        Files.writeString(projectDir.resolve(AbstractProjectLoader.IGNORE_FILE), RULES);

        WholeProjectExport.fromDatabase(provider, load(DATABASE), projectDir, exportSettings(projectDir));

        assertTrue(Files.isRegularFile(projectDir.resolve(DOC_FILE)),
                "the objects the project manages must still be written");
        assertFalse(Files.exists(projectDir.resolve(AUDIT_LOG_FILE)),
                "a table the project declares unmanaged must not appear among its files");
    }

    /**
     * With no rules anywhere the wizard writes the database whole, as it always
     * has.
     */
    @Test
    void aProjectCreatedFromADatabaseThatDeclaresNoRulesGetsEverything(@TempDir Path projectDir)
            throws IOException, InterruptedException {
        WholeProjectExport.fromDatabase(provider, load(DATABASE), projectDir, exportSettings(projectDir));

        assertTrue(Files.readString(projectDir.resolve(DOC_FILE), StandardCharsets.UTF_8).contains("s_creator"),
                "without a rule naming it a column is written like any other");
        assertTrue(Files.isRegularFile(projectDir.resolve(AUDIT_LOG_FILE)),
                "without a rule naming it a table is written like any other");
    }

    /**
     * The other side of the line, and the reason the fix above may not be made
     * by teaching the shared exporter the rules.
     * <p>
     * The model handed over stands for the one the project loader hands
     * {@code NormalizeProject}: that loader installs the rules of the project
     * into its settings but drops nothing from the model, so every column and
     * every object the project declares reaches the writer. Applying the rules
     * there would delete them from the project's own files.
     * <p>
     * The settings deliberately carry the rules, which the handler's own do not:
     * the point is that this export refuses to act on them however it is called.
     */
    @Test
    void relayingOutAProjectKeepsTheColumnsItsRulesHide(@TempDir Path projectDir)
            throws IOException, InterruptedException {
        Files.writeString(projectDir.resolve(AbstractProjectLoader.IGNORE_FILE), RULES);

        WholeProjectExport.relayout(provider, load(DATABASE), projectDir, true,
                currentWorkDirs(projectDir), exportSettings(projectDir));

        String doc = Files.readString(projectDir.resolve(DOC_FILE), StandardCharsets.UTF_8);
        assertTrue(doc.contains("s_creator"),
                "a relayout that drops a column the project declares destroys the project: " + doc);
    }

    /**
     * The same for a whole object: laying a project out anew moves its files, it
     * does not decide which of them the project is allowed to keep.
     */
    @Test
    void relayingOutAProjectKeepsTheObjectsItsRulesHide(@TempDir Path projectDir)
            throws IOException, InterruptedException {
        Files.writeString(projectDir.resolve(AbstractProjectLoader.IGNORE_FILE), RULES);

        WholeProjectExport.relayout(provider, load(DATABASE), projectDir, true,
                currentWorkDirs(projectDir), exportSettings(projectDir));

        assertTrue(Files.isRegularFile(projectDir.resolve(AUDIT_LOG_FILE)),
                "a relayout that drops an object the project declares destroys the project");
    }

    /** The settings the writer of a project's files is built with. */
    private static ISettings exportSettings(Path projectDir) {
        return UISettings.forExport(project(projectDir));
    }

    /** The layout the files are in now, resolved as {@code NormalizeProject} resolves it. */
    private static AbstractWorkDirs currentWorkDirs(Path projectDir) {
        return ProjectUtils.createWorkDirs(DatabaseType.PG, AbstractWorkDirs.resolveAltDirsFile(projectDir));
    }

    /**
     * The model of a database, loaded with settings that know nothing of any
     * project: a database has no opinion about the rules of the project it is
     * about to be written into.
     */
    private IDatabase load(String sql) throws IOException, InterruptedException {
        var settings = new CoreSettings();
        InputStreamProvider source = () -> new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8));
        IDatabase db = provider.getDumpLoader(source, "fixture", settings).loadAndAnalyze();
        assertNotNull(db, "fixture must load");
        assertNotNull(db.getStatement(new ObjectReference("dbo", "doc", DbObjType.TABLE)),
                "fixture must hold the table every assertion is about");
        return db;
    }

    private static IProject project(Path location) {
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn("WholeProjectExportTest-" + UUID.randomUUID()); //$NON-NLS-1$
        when(project.getLocationURI()).thenReturn(location.toUri());
        when(project.getLocation()).thenReturn(IPath.fromOSString(location.toString()));
        return project;
    }
}
