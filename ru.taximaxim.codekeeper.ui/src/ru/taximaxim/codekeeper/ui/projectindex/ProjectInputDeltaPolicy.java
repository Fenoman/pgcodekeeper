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
package ru.taximaxim.codekeeper.ui.projectindex;

import static org.eclipse.core.resources.IResource.FILE;
import static org.eclipse.core.resources.IResource.FOLDER;
import static org.eclipse.core.resources.IResource.PROJECT;
import static org.eclipse.core.resources.IResource.ROOT;
import static org.eclipse.core.resources.IResourceDelta.*;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;

import ru.taximaxim.codekeeper.ui.utils.ProjectUtils;

/** Determines whether a workspace delta can affect comparison input. */
public final class ProjectInputDeltaPolicy {

    private static final Set<String> CONFIGURATION_INPUTS = Set.of(
            ".pgcodekeeper", //$NON-NLS-1$
            ".pgcodekeeperignore", //$NON-NLS-1$
            ".pgcodekeeperignoreschema", //$NON-NLS-1$
            ".pgcodekeeperdependencies", //$NON-NLS-1$
            ".dependencies", //$NON-NLS-1$
            ".project", //$NON-NLS-1$
            ".settings/ru.taximaxim.codekeeper.ui.prefs", //$NON-NLS-1$
            "structure.properties"); //$NON-NLS-1$

    private static final int HARMLESS_FLAGS =
            MARKERS | SYNC | DERIVED_CHANGED;
    private static final int CONTENT_FLAGS = CONTENT | ENCODING;
    private static final int STRUCTURAL_FLAGS = MOVED_FROM | MOVED_TO
            | COPIED_FROM | OPEN | TYPE | REPLACED | DESCRIPTION
            | LOCAL_CHANGED | DELETE_CONTENT_PROPOSED;
    private static final int KNOWN_FLAGS = HARMLESS_FLAGS | CONTENT_FLAGS
            | STRUCTURAL_FLAGS;

    private ProjectInputDeltaPolicy() {
    }

    public static boolean affectsComparison(IResourceDelta root,
            IProject project) throws CoreException {
        if (root == null || project == null) {
            return true;
        }
        return affectsComparison(root, project,
                ProjectUtils.getProjectDirNamesChecked(project));
    }

    static boolean affectsComparison(IResourceDelta root,
            IProject project, List<String> projectDirNames)
            throws CoreException {
        if (root == null || project == null || projectDirNames == null) {
            return true;
        }
        boolean[] result = { false };
        root.accept(delta -> {
            if (result[0]) {
                return false;
            }
            IResource resource = delta.getResource();
            if (resource == null) {
                result[0] = true;
                return false;
            }
            int resourceType = resource.getType();
            if (resourceType != ROOT) {
                IProject resourceProject = resource.getProject();
                if (resourceProject != null
                        && !project.equals(resourceProject)) {
                    return false;
                }
                if (resourceProject == null) {
                    result[0] = true;
                    return false;
                }
            }
            IPath path = delta.getProjectRelativePath();
            if (path == null) {
                result[0] = true;
                return false;
            }
            if (isRelevant(delta, path, resourceType,
                    projectDirNames)) {
                result[0] = true;
                return false;
            }
            return true;
        });
        return result[0];
    }

    public static boolean isConfigurationInput(String relativePath) {
        if (relativePath == null) {
            return false;
        }
        try {
            String canonical = new IndexPathRef(
                    IndexPathOrigin.PROJECT, relativePath)
                            .relativePath();
            return CONFIGURATION_INPUTS.contains(canonical);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static boolean isRelevant(IResourceDelta delta, IPath path,
            int resourceType, List<String> projectDirNames) {
        if (resourceType != FILE && resourceType != FOLDER
                && resourceType != PROJECT && resourceType != ROOT) {
            return true;
        }
        if (delta.getKind() != ADDED && delta.getKind() != REMOVED
                && delta.getKind() != CHANGED
                || (delta.getFlags() & ~KNOWN_FLAGS) != 0) {
            return true;
        }
        boolean effectiveChange = delta.getKind() != CHANGED
                || (delta.getFlags()
                        & (CONTENT_FLAGS | STRUCTURAL_FLAGS)) != 0;
        if (resourceType == PROJECT || resourceType == ROOT) {
            return effectiveChange;
        }
        String relativePath = path.toPortableString().replace('\\', '/');
        if (!effectiveChange) {
            return false;
        }
        if (isConfigurationInput(relativePath)) {
            return true;
        }
        if (resourceType == FOLDER) {
            return ".settings".equals(relativePath) //$NON-NLS-1$
                    && (delta.getKind() == ADDED
                            || delta.getKind() == REMOVED)
                    || ProjectUtils.isInProject(path, projectDirNames)
                            && (delta.getKind() != CHANGED
                                    || (delta.getFlags()
                                            & STRUCTURAL_FLAGS) != 0);
        }
        return ProjectUtils.isInProject(path, projectDirNames)
                && relativePath.toLowerCase(Locale.ROOT).endsWith(".sql"); //$NON-NLS-1$
    }
}
