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
package ru.taximaxim.codekeeper.ui.builders;

import static org.eclipse.core.resources.IResource.*;
import static org.eclipse.core.resources.IResourceDelta.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.runtime.CoreException;

import ru.taximaxim.codekeeper.ui.builders.ProjectBuildDeltaClassifier.Entry;
import ru.taximaxim.codekeeper.ui.builders.ProjectBuildDeltaClassifier.Kind;
import ru.taximaxim.codekeeper.ui.builders.ProjectBuildDeltaClassifier.Reason;
import ru.taximaxim.codekeeper.ui.builders.ProjectBuildDeltaClassifier.Result;

final class ProjectBuildDeltaCollector {

    private static final String OVERRIDES = "OVERRIDES"; //$NON-NLS-1$
    private static final int CONTENT_FLAGS = CONTENT | ENCODING;
    private static final int HARMLESS_FLAGS =
            MARKERS | SYNC | DERIVED_CHANGED;
    private static final int STRUCTURAL_FLAGS = MOVED_FROM | MOVED_TO
            | COPIED_FROM | OPEN | TYPE | REPLACED | DESCRIPTION
            | LOCAL_CHANGED | DELETE_CONTENT_PROPOSED;
    private static final int KNOWN_FLAGS =
            CONTENT_FLAGS | HARMLESS_FLAGS | STRUCTURAL_FLAGS;

    record Delta(String relativePath, int kind, int flags,
            int resourceType, boolean exists) {

        Delta {
            Objects.requireNonNull(relativePath, "relativePath"); //$NON-NLS-1$
        }
    }

    /**
     * @param incrementalAddedFiles whether the project permits an added file to
     *                              join an incremental batch instead of forcing
     *                              a full rebuild
     */
    record Layout(List<String> topLevelDirectories,
            List<String> schemaContainer, boolean splitBySchema,
            Set<String> excludedSchemas, boolean ignorePrivileges,
            boolean incrementalAddedFiles) {

        Layout {
            topLevelDirectories = List.copyOf(topLevelDirectories);
            schemaContainer = List.copyOf(schemaContainer);
            excludedSchemas = Set.copyOf(excludedSchemas);
            if (schemaContainer.isEmpty()) {
                throw new IllegalArgumentException(
                        "Schema container must not be empty"); //$NON-NLS-1$
            }
        }
    }

    private ProjectBuildDeltaCollector() {
    }

    static Result classify(IResourceDelta root, Layout layout)
            throws CoreException {
        Objects.requireNonNull(layout, "layout"); //$NON-NLS-1$
        if (root == null) {
            return ProjectBuildDeltaClassifier.full(Reason.NO_DELTA);
        }
        List<Delta> deltas = new ArrayList<>();
        boolean[] uncertain = { false };
        root.accept(delta -> {
            IResource resource = delta.getResource();
            if (resource == null || delta.getProjectRelativePath() == null) {
                uncertain[0] = true;
                return false;
            }
            String path = delta.getProjectRelativePath().toPortableString();
            deltas.add(new Delta(path, delta.getKind(),
                    delta.getFlags(), resource.getType(),
                    resource.exists()));
            return !shouldPruneDirectory(path,
                    resource.getType(), layout);
        });
        return uncertain[0]
                ? ProjectBuildDeltaClassifier.full(
                        Reason.DELTA_VISIT_FAILED,
                        "entries=" + deltas.size()) //$NON-NLS-1$
                : classify(deltas, layout);
    }

    static Result classify(Collection<Delta> deltas, Layout layout) {
        Objects.requireNonNull(layout, "layout"); //$NON-NLS-1$
        if (deltas == null) {
            return ProjectBuildDeltaClassifier.full(Reason.NULL_DELTAS);
        }
        List<Entry> entries = new ArrayList<>(deltas.size());
        int total = deltas.size();
        int at = 0;
        for (Delta delta : deltas) {
            String where = "entries=" + total + ",at=" + at; //$NON-NLS-1$ //$NON-NLS-2$
            at++;
            if (delta == null) {
                return ProjectBuildDeltaClassifier.full(
                        Reason.NULL_ENTRY, where);
            }
            if ((delta.flags() & ~KNOWN_FLAGS) != 0) {
                return ProjectBuildDeltaClassifier.full(
                        Reason.UNKNOWN_FLAGS, where
                                + ",flags=0x" //$NON-NLS-1$
                                + Integer.toHexString(
                                        delta.flags() & ~KNOWN_FLAGS)
                                + ",segment=" //$NON-NLS-1$
                                + segment(delta.relativePath()));
            }
            Kind kind = kind(delta.kind());
            if (kind == null) {
                return ProjectBuildDeltaClassifier.full(
                        Reason.UNKNOWN_KIND, where
                                + ",kind=" + delta.kind() //$NON-NLS-1$
                                + ",segment=" //$NON-NLS-1$
                                + segment(delta.relativePath()));
            }
            String path = delta.relativePath().replace('\\', '/');
            if (path.isEmpty()) {
                if (delta.kind() != CHANGED
                        || (delta.flags()
                                & ~(MARKERS | SYNC | DERIVED_CHANGED)) != 0) {
                    return ProjectBuildDeltaClassifier.full(
                            Reason.PROJECT_ROOT_CHANGED, where
                                    + ",kind=" + delta.kind() //$NON-NLS-1$
                                    + ",flags=0x" //$NON-NLS-1$
                                    + Integer.toHexString(delta.flags()));
                }
                continue;
            }
            if (delta.resourceType() != FILE
                    && delta.resourceType() != FOLDER) {
                return ProjectBuildDeltaClassifier.full(
                        Reason.UNSUPPORTED_RESOURCE, where
                                + ",type=" + delta.resourceType() //$NON-NLS-1$
                                + ",segment=" //$NON-NLS-1$
                                + segment(delta.relativePath()));
            }
            boolean effectiveChange = kind != Kind.CHANGED
                    || (delta.flags()
                            & (CONTENT_FLAGS | STRUCTURAL_FLAGS)) != 0;
            boolean directory = delta.resourceType() == FOLDER;
            boolean inHierarchy = inHierarchy(path, layout);
            if (!layout.ignorePrivileges()
                    && isOverridesPath(path) && inHierarchy
                    && effectiveChange) {
                return ProjectBuildDeltaClassifier.full(
                        Reason.PRIVILEGE_OVERRIDE, where);
            }
            boolean indexedHierarchy = inHierarchy;
            boolean excluded = indexedHierarchy
                    && isProvablyExcluded(path, layout);
            boolean contentChanged =
                    (delta.flags() & CONTENT_FLAGS) != 0;
            boolean structural = (delta.flags() & STRUCTURAL_FLAGS) != 0
                    || kind == Kind.CHANGED && indexedHierarchy
                            && !directory && !delta.exists();
            if (!layout.splitBySchema()
                    && !layout.excludedSchemas().isEmpty()
                    && indexedHierarchy && !excluded) {
                structural = true;
            }
            entries.add(new Entry(path, kind, contentChanged,
                    structural, indexedHierarchy, excluded,
                    directory));
        }
        return ProjectBuildDeltaClassifier.withEvidence(
                ProjectBuildDeltaClassifier.classify(entries,
                        layout.incrementalAddedFiles()),
                "entries=" + total //$NON-NLS-1$
                        + ",candidates=" + entries.size()); //$NON-NLS-1$
    }

    private static Kind kind(int value) {
        return switch (value) {
        case ADDED -> Kind.ADDED;
        case REMOVED -> Kind.REMOVED;
        case CHANGED -> Kind.CHANGED;
        default -> null;
        };
    }

    private static boolean inHierarchy(String path, Layout layout) {
        List<String> segments = segments(path);
        if (segments.isEmpty()) {
            return false;
        }
        if (segments.size() == 1
                && OVERRIDES.equals(segments.getFirst())) {
            return true;
        }
        int offset = OVERRIDES.equals(segments.getFirst()) ? 1 : 0;
        return segments.size() > offset
                && layout.topLevelDirectories().contains(
                        segments.get(offset));
    }

    private static boolean isOverridesPath(String path) {
        List<String> segments = segments(path);
        return !segments.isEmpty()
                && OVERRIDES.equals(segments.getFirst());
    }

    private static boolean isProvablyExcluded(String path, Layout layout) {
        List<String> segments = segments(path);
        if (segments.isEmpty()) {
            return false;
        }
        int offset = 0;
        if (OVERRIDES.equals(segments.getFirst())) {
            if (layout.ignorePrivileges()) {
                return true;
            }
            offset = 1;
        }
        if (!layout.splitBySchema()) {
            return false;
        }
        List<String> container = layout.schemaContainer();
        if (segments.size() <= offset + container.size()) {
            return false;
        }
        for (int i = 0; i < container.size(); i++) {
            if (!container.get(i).equals(segments.get(offset + i))) {
                return false;
            }
        }
        return layout.excludedSchemas().contains(
                segments.get(offset + container.size()));
    }

    static boolean shouldPruneDirectory(String path,
            int resourceType, Layout layout) {
        if (resourceType != FOLDER) {
            return false;
        }
        if (isOverridesPath(path)) {
            return layout.ignorePrivileges();
        }
        return isProvablyExcluded(path, layout);
    }

    /**
     * Returns the first non-empty segment of a path, or {@code "/"} when it has
     * none. Diagnostics name the top of a tree a delta came from and never the
     * path itself: a log line must stay free of project content.
     *
     * @param path project-relative path of a delta
     * @return first segment of the path
     */
    private static String segment(String path) {
        List<String> segments = segments(path);
        return segments.isEmpty() ? "/" : segments.getFirst(); //$NON-NLS-1$
    }

    private static List<String> segments(String path) {
        String[] raw = path.replace('\\', '/').split("/"); //$NON-NLS-1$
        List<String> result = new ArrayList<>(raw.length);
        for (String segment : raw) {
            if (!segment.isEmpty() && !".".equals(segment)) { //$NON-NLS-1$
                result.add(segment);
            }
        }
        return result;
    }
}
