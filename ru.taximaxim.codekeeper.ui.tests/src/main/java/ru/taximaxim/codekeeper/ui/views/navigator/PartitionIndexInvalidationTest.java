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
package ru.taximaxim.codekeeper.ui.views.navigator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ru.taximaxim.codekeeper.ui.utils.ProjectUtils;

/**
 * When a folder scan stops being worth keeping.
 *
 * <p>What a scan concluded is half text and half index. The text half is
 * answered by the resource listener: a file that changes drops its own folder.
 * The index half - whether the parser confirms that this file really is a
 * section of that table - moves for files nothing happened to, and it moves
 * exactly once: when a build publishes an index of the project. Before that
 * first publication the parser answers nothing at all, every link is one the
 * text merely claims, and the folder nests nothing; and it would go on nesting
 * nothing for the life of the workbench, because no file ever changed.</p>
 *
 * <p>So the reset is hung on the publication and narrowed to the project it
 * published. Both halves of that sentence are load-bearing, and both are
 * asserted here.</p>
 */
class PartitionIndexInvalidationTest {

    private static final String PARENT = "tmp.orders"; //$NON-NLS-1$
    private static final String SECTION = "orders_1.sql"; //$NON-NLS-1$

    @AfterEach
    void forgetTheMap() {
        PartitionIndex.getInstance().clear();
    }

    @Test
    void publishingOneProjectsIndexForgetsThatProjectAndNoOther(
            @TempDir Path temp) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject published = createProject(temp.resolve("published"), //$NON-NLS-1$
                monitor);
        IProject untouched = createProject(temp.resolve("untouched"), //$NON-NLS-1$
                monitor);
        try {
            IFolder publishedFolder = createTableFolder(published, monitor);
            IFolder untouchedFolder = createTableFolder(untouched, monitor);
            publish(publishedFolder);
            publish(untouchedFolder);
            PartitionIndex index = PartitionIndex.getInstance();
            assertNotNull(index.folderOf(publishedFolder, null));
            assertNotNull(index.folderOf(untouchedFolder, null));

            index.forgetProject(published);

            assertNull(index.folderOf(publishedFolder, null),
                    "the republished project kept its stale scan");
            PartitionIndex.Folder kept =
                    index.folderOf(untouchedFolder, null);
            assertNotNull(kept,
                    "a project nobody republished lost its scan");
            assertEquals(PARENT,
                    kept.parentOf(untouchedFolder.getFile(SECTION)));
        } finally {
            cleanUp(published, monitor);
            cleanUp(untouched, monitor);
        }
    }

    /** Nothing to forget is not an error, and neither is nothing to name. */
    @Test
    void forgettingNothingIsHarmless(@TempDir Path temp) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(temp.resolve("kept"), monitor); //$NON-NLS-1$
        try {
            IFolder folder = createTableFolder(project, monitor);
            publish(folder);
            PartitionIndex index = PartitionIndex.getInstance();

            index.forgetProject(null);

            assertNotNull(index.folderOf(folder, null),
                    "a null project emptied the map");
        } finally {
            cleanUp(project, monitor);
        }
    }

    private static void publish(IFolder folder) {
        Map<IPath, String> parentByFile = new LinkedHashMap<>();
        parentByFile.put(folder.getFile(SECTION).getFullPath(), PARENT);
        PartitionIndex.getInstance().put(folder.getFullPath(), parentByFile,
                Map.of());
    }

    private static IFolder createTableFolder(IProject project,
            NullProgressMonitor monitor) throws Exception {
        IFolder schema = project.getFolder("SCHEMA"); //$NON-NLS-1$
        schema.create(true, true, monitor);
        IFolder tmp = schema.getFolder("tmp"); //$NON-NLS-1$
        tmp.create(true, true, monitor);
        IFolder table = tmp.getFolder("TABLE"); //$NON-NLS-1$
        table.create(true, true, monitor);
        for (String name : new String[] { "orders.sql", SECTION }) { //$NON-NLS-1$
            table.getFile(name).create(new ByteArrayInputStream(
                    "-- fixture\n".getBytes(StandardCharsets.UTF_8)), //$NON-NLS-1$
                    true, monitor);
        }
        return table;
    }

    private static IProject createProject(Path location,
            NullProgressMonitor monitor) throws Exception {
        var workspace = ResourcesPlugin.getWorkspace();
        String name = "pgck-partition-invalidation-" //$NON-NLS-1$
                + location.getFileName();
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

    private static void cleanUp(IProject project,
            NullProgressMonitor monitor) {
        try {
            if (project.exists()) {
                project.delete(true, true, monitor);
            }
        } catch (Exception e) {
            fail(e);
        }
    }
}
