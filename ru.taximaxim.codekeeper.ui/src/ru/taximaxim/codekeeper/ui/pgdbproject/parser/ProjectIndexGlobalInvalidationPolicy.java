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

import org.eclipse.core.resources.IProject;

import ru.taximaxim.codekeeper.ui.DatabaseType;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexSupportPolicy;
import ru.taximaxim.codekeeper.ui.utils.ProjectUtils;

/**
 * Which projects a change of the global index preferences reaches. Only the
 * ones that have a background index at all, which
 * {@link ProjectIndexSupportPolicy} alone decides.
 */
final class ProjectIndexGlobalInvalidationPolicy {

    private ProjectIndexGlobalInvalidationPolicy() {
    }

    /**
     * The type of a project whose index a global preference change invalidates.
     *
     * <p>The type is what the caller needs - it resolves the configuration of
     * the project by it - and answering with the type instead of a yes spares
     * the caller from restating the very condition this decided.</p>
     *
     * @param project project to consider, may be null
     * @return its database type, or null when it has no index to invalidate
     */
    static DatabaseType acceptedDatabaseType(IProject project) {
        if (project == null || !project.isAccessible()
                || !ProjectUtils.isPgCodeKeeperProject(project)) {
            return null;
        }
        return ProjectUtils.getDatabaseType(project);
    }

    static boolean accepts(boolean accessible, boolean pgCodeKeeperProject) {
        return accessible && pgCodeKeeperProject;
    }
}
