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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ProjectScope;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ru.taximaxim.codekeeper.ui.UIConsts;
import ru.taximaxim.codekeeper.ui.UIConsts.PREF;
import ru.taximaxim.codekeeper.ui.UIConsts.PROJ_PREF;
import ru.taximaxim.codekeeper.ui.settings.UISettings;

class PgDbParserValidatedProjectSnapshotLeaseTest {

    @Test
    void mutationLeaseGuardsColdPublicationWithoutAnIndex(
            @TempDir Path temp) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(
                temp.resolve("mutation-only"), monitor);
        try {
            writeProject(project);
            PgDbParser parser = PgDbParser.getParser(project);
            var publications = new java.util.concurrent.atomic.AtomicInteger();
            try (var lease =
                    parser.acquireProjectMutationLease()) {
                assertTrue(lease.isCurrent());
                assertEquals("accepted",
                        lease.commitIfCurrent(() -> {
                            publications.incrementAndGet();
                            return "accepted";
                        }).orElseThrow());

                project.getFile("SCHEMA/app/TABLE/item.sql")
                        .setContents(new ByteArrayInputStream("""
                                CREATE TABLE app.item (
                                    id bigint,
                                    changed text
                                );
                                """.getBytes(StandardCharsets.UTF_8)),
                                IResource.FORCE, monitor);

                assertFalse(lease.isCurrent());
                assertTrue(lease.commitIfCurrent(() -> {
                    publications.incrementAndGet();
                    return "stale";
                }).isEmpty());
            }
            assertEquals(1, publications.get());
        } finally {
            cleanup(project, monitor);
        }
    }

    @Test
    void mutationLeaseIgnoresIdeaWorkspaceMetadata(
            @TempDir Path temp) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(
                temp.resolve("idea-workspace"), monitor);
        try {
            writeProject(project);
            PgDbParser parser = PgDbParser.getParser(project);
            var idea = project.getFolder(".idea");
            idea.create(IResource.FORCE, true, monitor);
            var workspace = idea.getFile("workspace.xml");

            try (var lease = parser.acquireProjectMutationLease()) {
                workspace.create(new ByteArrayInputStream("<project/>\n"
                        .getBytes(StandardCharsets.UTF_8)), IResource.FORCE,
                        monitor);
                workspace.setContents(new ByteArrayInputStream(
                        "<project version=\"2\"/>\n"
                                .getBytes(StandardCharsets.UTF_8)),
                        IResource.FORCE, monitor);

                assertTrue(lease.isCurrent(),
                        "known unrelated workspace metadata must not stale a lease");
            }
        } finally {
            cleanup(project, monitor);
        }
    }

    @Test
    void mutationLeaseInvalidatesOnIgnoreConfigurationChange(
            @TempDir Path temp) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(
                temp.resolve("ignore-configuration"), monitor);
        try {
            writeProject(project);
            PgDbParser parser = PgDbParser.getParser(project);
            var ignore = project.getFile(".pgcodekeeperignore");

            try (var lease = parser.acquireProjectMutationLease()) {
                ignore.create(new ByteArrayInputStream("dummy_tmp\n"
                        .getBytes(StandardCharsets.UTF_8)), IResource.FORCE,
                        monitor);

                assertFalse(lease.isCurrent(),
                        "ignore configuration is comparison input");
            }
            try (var lease = parser.acquireProjectMutationLease()) {
                ignore.setContents(new ByteArrayInputStream("other_tmp\n"
                        .getBytes(StandardCharsets.UTF_8)), IResource.FORCE,
                        monitor);

                assertFalse(lease.isCurrent(),
                        "ignore configuration is comparison input");
            }
        } finally {
            cleanup(project, monitor);
        }
    }

    @Test
    void mutationLeaseInvalidatesOnExcludedSqlChange(
            @TempDir Path temp) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(
                temp.resolve("excluded-sql"), monitor);
        var projectPrefs = new ProjectScope(project)
                .getNode(UIConsts.PLUGIN_ID.THIS);
        try {
            projectPrefs.putBoolean(PROJ_PREF.ENABLE_PROJ_PREF_ROOT, true);
            projectPrefs.put(PREF.PROJECT_INDEX_EXCLUDED_SCHEMAS,
                    "dummy_tmp");
            projectPrefs.flush();
            writeProject(project);
            PgDbParser parser = PgDbParser.getParser(project);
            var parent = project.getFolder("SCHEMA/dummy_tmp");
            parent.create(IResource.FORCE, true, monitor);
            var directory = project.getFolder("SCHEMA/dummy_tmp/TABLE");
            directory.create(IResource.FORCE, true, monitor);
            var table = directory.getFile("item.sql");
            table.create(new ByteArrayInputStream(
                    "CREATE TABLE dummy_tmp.item (id bigint);\n"
                            .getBytes(StandardCharsets.UTF_8)),
                    IResource.FORCE, monitor);

            assertTrue(UISettings.forProjectIndex(project)
                    .isAdditionalSchemaExcluded("dummy_tmp"));
            try (var lease = parser.acquireProjectMutationLease()) {
                table.setContents(new ByteArrayInputStream(
                        "CREATE TABLE dummy_tmp.item (id text);\n"
                                .getBytes(StandardCharsets.UTF_8)),
                        IResource.FORCE, monitor);

                assertFalse(lease.isCurrent(),
                        "SQL must invalidate even when background indexing excludes its directory");
            }
        } finally {
            try {
                projectPrefs.removeNode();
            } finally {
                cleanup(project, monitor);
            }
        }
    }

    @Test
    void leasePinsExactPackedPublicationAndInvalidatesOnFileDelta(
            @TempDir Path temp) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(temp.resolve("content"), monitor);
        PgDbParser parser = PgDbParser.getParserForBuilder(
                project, new int[] { IncrementalProjectBuilder.FULL_BUILD });
        try {
            writeProject(project);
            parser.prepareProjectIndex(project, monitor).update()
                    .commit(project.getName(), monitor);

            try (var lease = parser.acquireValidatedProjectSnapshotLease(
                    project, monitor).orElseThrow()) {
                assertTrue(lease.isCurrent());

                var table = project.getFile(
                        "SCHEMA/app/TABLE/item.sql");
                table.setContents(new ByteArrayInputStream("""
                        CREATE TABLE app.item (
                            id bigint,
                            code text
                        );
                        """.getBytes(StandardCharsets.UTF_8)),
                        IResource.FORCE, monitor);

                assertFalse(lease.isCurrent(),
                        "POST_CHANGE must invalidate before the builder publishes");
            }
            assertTrue(parser.acquireValidatedProjectSnapshotLease(
                    project, monitor).isEmpty(),
                    "A stale live index must fail closed");
        } finally {
            cleanup(project, monitor);
        }
    }

    @Test
    void sameStorageRevisionKeepsTheVersionToken(
            @TempDir Path temp) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(temp.resolve("publication"), monitor);
        PgDbParser parser = PgDbParser.getParserForBuilder(
                project, new int[] { IncrementalProjectBuilder.FULL_BUILD });
        try {
            writeProject(project);
            parser.prepareProjectIndex(project, monitor).update()
                    .commit(project.getName(), monitor);

            var first = parser.acquireValidatedProjectSnapshotLease(
                    project, monitor).orElseThrow();
            var firstToken = first.token();
            parser.prepareProjectIndex(project, monitor).update()
                    .commit(project.getName(), monitor);

            assertTrue(first.isCurrent());
            first.close();
            try (var second =
                    parser.acquireValidatedProjectSnapshotLease(
                            project, monitor).orElseThrow()) {
                assertTrue(second.isCurrent());
                assertEquals(firstToken, second.token());
            }
        } finally {
            cleanup(project, monitor);
        }
    }

    @Test
    void differentStorageRevisionChangesTheVersionToken(
            @TempDir Path temp) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(
                temp.resolve("changed-publication"), monitor);
        PgDbParser parser = PgDbParser.getParserForBuilder(
                project, new int[] { IncrementalProjectBuilder.FULL_BUILD });
        try {
            writeProject(project);
            parser.prepareProjectIndex(project, monitor).update()
                    .commit(project.getName(), monitor);

            var first = parser.acquireValidatedProjectSnapshotLease(
                    project, monitor).orElseThrow();
            var firstToken = first.token();
            var table = project.getFile("SCHEMA/app/TABLE/item.sql");
            table.setContents(new ByteArrayInputStream("""
                    CREATE TABLE app.item (
                        id bigint,
                        code text
                    );
                    """.getBytes(StandardCharsets.UTF_8)),
                    IResource.FORCE, monitor);
            parser.prepareProjectIndex(project, monitor).update()
                    .commit(project.getName(), monitor);

            assertFalse(first.isCurrent());
            first.close();
            try (var second =
                    parser.acquireValidatedProjectSnapshotLease(
                            project, monitor).orElseThrow()) {
                assertTrue(second.isCurrent());
                assertNotEquals(firstToken, second.token());
            }
        } finally {
            cleanup(project, monitor);
        }
    }

    @Test
    void cancelledAcquisitionNeverReturnsAUsableLease(
            @TempDir Path temp) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(temp.resolve("cancel"), monitor);
        PgDbParser parser = PgDbParser.getParserForBuilder(
                project, new int[] { IncrementalProjectBuilder.FULL_BUILD });
        try {
            writeProject(project);
            parser.prepareProjectIndex(project, monitor).update()
                    .commit(project.getName(), monitor);
            monitor.setCanceled(true);

            assertThrows(InterruptedException.class,
                    () -> parser.acquireValidatedProjectSnapshotLease(
                            project, monitor));
        } finally {
            monitor.setCanceled(false);
            cleanup(project, monitor);
        }
    }

    private static IProject createProject(Path location,
            NullProgressMonitor monitor) throws Exception {
        var workspace = ResourcesPlugin.getWorkspace();
        String name = "pgck-model-lease-" + location.getFileName();
        IProject project = workspace.getRoot().getProject(name);
        if (project.exists()) {
            project.delete(true, true, monitor);
        }
        IProjectDescription description =
                workspace.newProjectDescription(name);
        description.setLocationURI(location.toUri());
        project.create(description, monitor);
        project.open(monitor);
        return project;
    }

    private static void writeProject(IProject project) throws Exception {
        Path root = project.getLocation().toFile().toPath();
        Path schema = Files.createDirectories(
                root.resolve("SCHEMA/app"));
        Path tables = Files.createDirectories(
                schema.resolve("TABLE"));
        Files.writeString(schema.resolve("app.sql"),
                "CREATE SCHEMA app;\n");
        Files.writeString(tables.resolve("item.sql"),
                "CREATE TABLE app.item (id bigint);\n");
        project.refreshLocal(IResource.DEPTH_INFINITE,
                new NullProgressMonitor());
    }

    private static void cleanup(IProject project,
            NullProgressMonitor monitor) throws Exception {
        PgDbParser.removeProject(project);
        if (project.exists()) {
            project.delete(true, true, monitor);
        }
    }
}
