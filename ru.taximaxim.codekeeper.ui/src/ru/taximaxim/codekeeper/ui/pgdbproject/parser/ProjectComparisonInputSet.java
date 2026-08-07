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

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IPath;
import org.pgcodekeeper.core.database.api.loader.IProjectLoader;

import ru.taximaxim.codekeeper.ui.projectindex.IndexPathOrigin;
import ru.taximaxim.codekeeper.ui.projectindex.IndexPathRef;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexFiles;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexPathResolver;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexWarmValidator.CurrentFile;

/**
 * One exact enumeration of every local input consumed by Get Changes. Unlike
 * the background index, this set deliberately retains early-excluded schemas.
 * <p>
 * It retains them because the comparison itself does. Schema exclusions are
 * placed on the settings of an index build alone and never on the ones Get
 * Changes loads by, so the model these stamps describe contains every excluded
 * schema. Narrowing the enumeration to the indexed subset would leave an edit
 * under an excluded schema unable to invalidate the stored analysis, and the
 * next comparison would replay a model that no longer describes the project -
 * a wrong migration, not a slow one. Core states the same rule from the other
 * side: a load carrying schema exclusions refuses to produce a reusable model
 * at all, and a profile carrying them is never eligible for the store.
 */
final class ProjectComparisonInputSet {

    private final List<CurrentFile> files;
    private final ProjectIndexPathResolver resolver;

    private ProjectComparisonInputSet(List<CurrentFile> files,
            ProjectIndexPathResolver resolver) {
        this.files = List.copyOf(files);
        this.resolver = resolver;
    }

    static ProjectComparisonInputSet inspect(IProject project,
            Path projectRoot, IProjectLoader loader)
            throws IOException, InterruptedException {
        Objects.requireNonNull(project, "project"); //$NON-NLS-1$
        Path root = Objects.requireNonNull(
                projectRoot, "projectRoot") //$NON-NLS-1$
                .toAbsolutePath().normalize();
        Objects.requireNonNull(loader, "loader"); //$NON-NLS-1$

        List<Path> inputs;
        try (loader) {
            inputs = loader.listInputFiles();
        }
        ProjectIndexPathResolver resolver =
                path -> root.resolve(path.relativePath()).toString();
        List<CurrentFile> current = ProjectIndexFiles.inspect(
                inputs, root, root,
                path -> modificationStamp(project, path));
        return new ProjectComparisonInputSet(current, resolver);
    }

    List<CurrentFile> files() {
        return files;
    }

    ProjectIndexPathResolver resolver() {
        return resolver;
    }

    private static long modificationStamp(
            IProject project, IndexPathRef path) {
        if (path.origin() != IndexPathOrigin.PROJECT) {
            return -1;
        }
        IPath relative = org.eclipse.core.runtime.Path
                .fromPortableString(path.relativePath());
        IFile file = project.getFile(relative);
        return file.exists() ? file.getModificationStamp() : -1;
    }
}
