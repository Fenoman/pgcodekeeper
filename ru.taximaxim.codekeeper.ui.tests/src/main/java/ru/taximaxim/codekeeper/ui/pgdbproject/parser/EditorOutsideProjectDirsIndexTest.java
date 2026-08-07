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
 * What the SQL editor does when it opens a file that lives inside a
 * pgCodeKeeper project but outside the directories the project is made of -
 * OmniX keeps 1686 such files under MIGRATION, Scripts and Oracle.
 * <p>
 * The editor refreshes a parser only for a resource that is not
 * {@link ProjectUtils#isInProject(IResource)}, and that predicate answers
 * "inside a pgCodeKeeper project AND under one of its object directories". The
 * branch it then takes asks the weaker question - is this a pgCodeKeeper
 * project at all - so such a file passes both. Reading that alone suggests the
 * editor publishes a project index from its own thread, in parallel with the
 * builder.
 * <p>
 * It does not, and these tests pin down why: the parser reaching that branch is
 * always the private one the editor just created, whose storage is a plain
 * {@code ProjectReferencesStorage}, so the update stays a single-file one that
 * never touches the shared index or the disk. The costly branch - a whole
 * project index prepared through the shared coordinator - is only reachable
 * from a parser holding a packed index, and no such parser reaches here.
 */
class EditorOutsideProjectDirsIndexTest {

    private static final String OUTSIDE_PATH = "MIGRATION/patch.sql";

    @Test
    void aFileOutsideTheProjectDirectoriesPassesBothEditorGuards(
            @TempDir Path temp) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(temp.resolve("guards"), monitor);
        try {
            writeProject(project);
            IFile outside = project.getFile(
                    org.eclipse.core.runtime.Path
                            .fromPortableString(OUTSIDE_PATH));

            assertTrue(ProjectUtils.isPgCodeKeeperProject(project),
                    "the branch the editor takes asks only this");
            assertFalse(ProjectUtils.isInProject(outside),
                    "the guard that lets the editor in asks this");
        } finally {
            cleanUp(project, monitor);
        }
    }

    /**
     * The one observation that tells the two branches apart. A parser holding
     * a plain storage takes the single-file branch, which parses the opened
     * file and nothing else; a parser holding a packed index takes the branch
     * that prepares a whole project index through the shared coordinator and
     * ends up knowing every object of the project.
     * <p>
     * Substituting the shared project parser for the editor's private one -
     * the very confusion the guard mismatch invites - makes this fail, which
     * is what the assertion is for.
     */
    @Test
    void theEditorPathParsesOnlyTheOpenedFileAndBuildsNoProjectIndex(
            @TempDir Path temp) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(temp.resolve("shared"), monitor);
        PgDbParser shared = PgDbParser.getParserForBuilder(project, new int[1]);
        PgDbParser editor = new PgDbParser();
        try {
            writeProject(project);
            shared.prepareProjectIndex(project, monitor)
                    .update().commit(project.getName(), monitor);
            assertTrue(definitions(shared).stream()
                    .anyMatch(name -> name.contains("item")),
                    "the shared index holds what the project directories say");

            IFile outside = project.getFile(
                    org.eclipse.core.runtime.Path
                            .fromPortableString(OUTSIDE_PATH));
            // Exactly what SQLEditor.refreshParser does for this file.
            editor.prepareObjFromProjFile(outside, monitor)
                    .commit(project.getName(), monitor);

            assertEquals(List.of(), definitions(editor),
                    "the editor path must not build a project index");
            assertFalse(definitions(shared).stream()
                    .anyMatch(name -> name.contains("patch_marker")),
                    "a file outside the project directories must stay out");
        } finally {
            shared.clear();
            editor.clear();
            PgDbParser.clean(project);
            cleanUp(project, monitor);
        }
    }

    @Test
    void theEditorPathReadsSuchAFileExactlyAsTheExternalPathDoes(
            @TempDir Path temp) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(temp.resolve("parity"), monitor);
        PgDbParser editor = new PgDbParser();
        PgDbParser external = new PgDbParser();
        try {
            writeProject(project);
            IFile outside = project.getFile(
                    org.eclipse.core.runtime.Path
                            .fromPortableString(OUTSIDE_PATH));
            String path = outside.getLocation().toOSString();

            editor.prepareObjFromProjFile(outside, monitor)
                    .commit(project.getName(), monitor);
            try (var input = Files.newInputStream(
                    outside.getLocation().toFile().toPath())) {
                external.fillRefsFromInputStream(input, path, monitor,
                        project, DatabaseType.PG);
            }

            assertEquals(external.getObjsForPath(path).size(),
                    editor.getObjsForPath(path).size(),
                    "both paths see the same objects in the same file");
            assertEquals(2, editor.getObjsForPath(path).size(),
                    "the schema and the table of the outside file");
        } finally {
            editor.clear();
            external.clear();
            cleanUp(project, monitor);
        }
    }

    private static List<String> definitions(PgDbParser parser) {
        return parser.getAllObjDefinitions()
                .map(definition -> definition.getObjectReference().toString())
                .sorted()
                .toList();
    }

    private static IProject createProject(Path location,
            NullProgressMonitor monitor) throws Exception {
        var workspace = ResourcesPlugin.getWorkspace();
        String name = "pgck-editor-outside-" + location.getFileName();
        IProject project = workspace.getRoot().getProject(name);
        if (project.exists()) {
            project.delete(true, true, monitor);
        }
        IProjectDescription description = workspace.newProjectDescription(name);
        description.setLocationURI(location.toUri());
        project.create(description, monitor);
        project.open(monitor);
        IProjectDescription open = project.getDescription();
        open.setNatureIds(new String[] { ProjectUtils.NATURE_ID });
        project.setDescription(open, monitor);
        return project;
    }

    private static void writeProject(IProject project) throws Exception {
        Path root = project.getLocation().toFile().toPath();
        Path schema = Files.createDirectories(root.resolve("SCHEMA/app"));
        Path tables = Files.createDirectories(schema.resolve("TABLE"));
        Files.writeString(schema.resolve("app.sql"), "CREATE SCHEMA app;\n");
        Files.writeString(tables.resolve("item.sql"),
                "CREATE TABLE app.item (id integer);\n");
        Path migration = Files.createDirectories(root.resolve("MIGRATION"));
        Files.writeString(migration.resolve("patch.sql"),
                "CREATE TABLE app.patch_marker (id integer);\n");
        project.refreshLocal(IResource.DEPTH_INFINITE,
                new NullProgressMonitor());
    }

    private static void cleanUp(IProject project, NullProgressMonitor monitor)
            throws Exception {
        if (project.exists()) {
            project.delete(true, true, monitor);
        }
    }
}
