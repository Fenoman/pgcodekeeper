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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ru.taximaxim.codekeeper.ui.DatabaseType;
import ru.taximaxim.codekeeper.ui.utils.ProjectUtils;

/**
 * The background project index is not PostgreSQL-only, and this is what says
 * so: a MS SQL and a ClickHouse project go through the same four paths a
 * PostgreSQL one does - build, incremental change, restart, repair - and end up
 * holding what a full load of the same project holds.
 * <p>
 * The index reads a file back with the parser named by the identity it was
 * built under, so the dialect follows the project rather than the plugin. What
 * stays PostgreSQL is one step further out: carrying a whole analyzed model
 * from one comparison to the next needs a loader that installs a stored
 * analysis into a freshly parsed model, and only PostgreSQL has one.
 */
class ProjectIndexOtherDialectsTest {

    @Test
    void aMsSqlProjectIsIndexedRestoredAndRepairedLikeAPostgresOne(
            @TempDir Path temp) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(temp.resolve("ms"), monitor,
                DatabaseType.MS);
        try {
            write(project, "Security/Schemas/app.sql",
                    "CREATE SCHEMA [app];\nGO\n");
            write(project, "Tables/app.item.sql",
                    "CREATE TABLE [app].[item] ([id] int);\nGO\n");
            write(project, "Views/app.pick.sql",
                    "CREATE VIEW [app].[pick] AS"
                            + " SELECT [id] FROM [app].[item];\nGO\n");
            write(project, "Stored Procedures/app.touch.sql",
                    "CREATE PROCEDURE [app].[touch] AS"
                            + " SELECT [id] FROM [app].[pick];\nGO\n");

            assertIndexedLikePostgres(project, monitor,
                    "Views/app.pick.sql",
                    "CREATE VIEW [app].[pick] AS SELECT [id]"
                            + " FROM [app].[item] WHERE [id] > 0;\nGO\n",
                    List.of("app (SCHEMA)", "app.item (TABLE)",
                            "app.pick (VIEW)", "app.touch (PROCEDURE)"));
        } finally {
            cleanUp(project, monitor);
        }
    }

    @Test
    void aClickHouseProjectIsIndexedRestoredAndRepairedLikeAPostgresOne(
            @TempDir Path temp) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(temp.resolve("ch"), monitor,
                DatabaseType.CH);
        try {
            write(project, "DATABASE/app/app.sql",
                    "CREATE DATABASE app ENGINE = Atomic;\n");
            write(project, "DATABASE/app/TABLE/item.sql",
                    "CREATE TABLE app.item (id Int32)"
                            + " ENGINE = MergeTree ORDER BY id;\n");
            write(project, "DATABASE/app/VIEW/pick.sql",
                    "CREATE VIEW app.pick AS SELECT id FROM app.item;\n");
            write(project, "DATABASE/app/VIEW/touch.sql",
                    "CREATE VIEW app.touch AS SELECT id FROM app.pick;\n");

            assertIndexedLikePostgres(project, monitor,
                    "DATABASE/app/VIEW/pick.sql",
                    "CREATE VIEW app.pick AS"
                            + " SELECT id FROM app.item WHERE id > 0;\n",
                    List.of("app (SCHEMA)", "app.item (TABLE)",
                            "app.pick (VIEW)", "app.touch (VIEW)"));
        } finally {
            cleanUp(project, monitor);
        }
    }

    /**
     * Walks one project through every path the index has, comparing what it
     * holds against a full load of the same project at each step.
     */
    private static void assertIndexedLikePostgres(IProject project,
            NullProgressMonitor monitor, String changedPath,
            String changedContent, List<String> expected) throws Exception {
        PgDbParser index = new PgDbParser();
        PgDbParser full = new PgDbParser();
        PgDbParser restarted = new PgDbParser();
        PgDbParser repaired = new PgDbParser();
        try {
            PgDbParser.PreparedProjectIndex cold =
                    index.prepareProjectIndex(project, monitor);
            cold.update().commit(project.getName(), monitor);
            assertTrue(cold.update().wasPublished(),
                    "a cold build publishes an index for this project");
            assertEquals(expected, definitions(index));

            IFile changed = project.getFile(
                    org.eclipse.core.runtime.Path
                            .fromPortableString(changedPath));
            setContents(changed, changedContent, monitor);
            PgDbParser.PreparedIncrementalProjectIndex incremental =
                    index.prepareIncrementalProjectIndex(
                            project, changedPath, monitor);
            assertFalse(incremental.fullBuild(),
                    "one changed file is read back, not rebuilt from nothing");
            incremental.prepared().update().commit(project.getName(), monitor);

            full.prepareFullDBFromPgDbProject(project, monitor)
                    .commit(project.getName(), monitor);
            assertEquals(definitions(full), definitions(index),
                    "the index holds what a full load holds");
            assertEquals(full.getAllObjReferences().count(),
                    index.getAllObjReferences().count(),
                    "and the references that come with them");

            // A restart: the index is found on disk rather than rebuilt.
            PgDbParser.PreparedProjectIndex warm =
                    restarted.prepareProjectIndex(project, monitor);
            warm.update().commit(project.getName(), monitor);
            assertTrue(warm.restoredFromDisk(),
                    "a published index of this type is restored, not rebuilt");
            assertEquals(definitions(full), definitions(restarted));

            // A file that moved while nothing watched: repaired from the files
            // the index disagrees with.
            setContents(changed, changedContent.replace("> 0", "> 1"), monitor);
            PgDbParser.PreparedIncrementalProjectIndex repair =
                    repaired.prepareIncrementalProjectIndex(
                            project, changedPath, monitor);
            assertFalse(repair.fullBuild(),
                    "a stale index of this type is repaired, not rebuilt");
            repair.prepared().update().commit(project.getName(), monitor);
            assertEquals(definitions(full), definitions(repaired));
        } finally {
            index.clear();
            full.clear();
            restarted.clear();
            repaired.clear();
        }
    }

    private static void setContents(IFile file, String content,
            NullProgressMonitor monitor) throws Exception {
        try (var input = new ByteArrayInputStream(
                content.getBytes(StandardCharsets.UTF_8))) {
            file.setContents(input, true, false, monitor);
        }
    }

    private static List<String> definitions(PgDbParser parser) {
        return parser.getAllObjDefinitions()
                .map(definition -> definition.getObjectReference().toString())
                .sorted()
                .toList();
    }

    private static IProject createProject(Path location,
            NullProgressMonitor monitor, DatabaseType databaseType)
            throws Exception {
        var workspace = ResourcesPlugin.getWorkspace();
        String name = "pgck-dialect-" + location.getFileName();
        IProject project = workspace.getRoot().getProject(name);
        if (project.exists()) {
            project.delete(true, true, monitor);
        }
        IProjectDescription description = workspace.newProjectDescription(name);
        description.setLocationURI(location.toUri());
        project.create(description, monitor);
        project.open(monitor);
        IProjectDescription open = project.getDescription();
        open.setNatureIds(ProjectUtils.getProjectNatures(databaseType));
        project.setDescription(open, monitor);
        return project;
    }

    private static void write(IProject project, String path, String content)
            throws Exception {
        Path file = project.getLocation().toFile().toPath().resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        project.refreshLocal(IResource.DEPTH_INFINITE,
                new NullProgressMonitor());
    }

    private static void cleanUp(IProject project, NullProgressMonitor monitor)
            throws Exception {
        if (project.exists()) {
            PgDbParser.clean(project);
            project.delete(true, true, monitor);
        }
    }
}
