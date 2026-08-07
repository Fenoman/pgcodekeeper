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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.settings.CoreSettings;

class ProjectComparisonInputSetTest {

    @Test
    void enumeratesFullGetChangesInputsIncludingEarlyExcludedSchemas(
            @TempDir Path root) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(root.resolve("project"), monitor);
        try {
            Path projectRoot = project.getLocation()
                    .toFile().toPath();
            Path app = Files.createDirectories(
                    projectRoot.resolve("SCHEMA/app"));
            Path dummy = Files.createDirectories(
                    projectRoot.resolve("SCHEMA/dummy_tmp"));
            Files.writeString(app.resolve("app.sql"),
                    "CREATE SCHEMA app;\n");
            Files.writeString(dummy.resolve("dummy_tmp.sql"),
                    "CREATE SCHEMA dummy_tmp;\n");
            project.refreshLocal(
                    IResource.DEPTH_INFINITE, monitor);

            var settings = new CoreSettings();
            var loader = new PgDatabaseProvider()
                    .getProjectLoader(projectRoot, settings,
                            List.of(), List.of(), List.of(),
                            root.resolve("meta"));

            ProjectComparisonInputSet inputs =
                    ProjectComparisonInputSet.inspect(
                            project, projectRoot, loader);
            Set<String> paths = inputs.files().stream()
                    .map(file -> file.path().relativePath())
                    .collect(Collectors.toSet());

            assertEquals(2, paths.size());
            assertTrue(paths.contains(
                    "SCHEMA/app/app.sql"));
            assertTrue(paths.contains(
                    "SCHEMA/dummy_tmp/dummy_tmp.sql"));
        } finally {
            if (project.exists()) {
                project.delete(true, true, monitor);
            }
        }
    }

    private static IProject createProject(Path location,
            NullProgressMonitor monitor) throws Exception {
        var workspace = ResourcesPlugin.getWorkspace();
        String name = "pgck-full-input-"
                + location.getFileName();
        IProject project =
                workspace.getRoot().getProject(name);
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
}
