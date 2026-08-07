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

import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.jface.viewers.StructuredViewer;
import org.eclipse.jface.viewers.Viewer;

import ru.taximaxim.codekeeper.ui.Activator;
import ru.taximaxim.codekeeper.ui.UIConsts.PREF;
import ru.taximaxim.codekeeper.ui.UiSync;

/**
 * The switch, and the single reading of the map that both sides of the nesting
 * are obliged to use.
 *
 * <p>Two different subsystems decide the same thing here: a filter decides
 * whether a section is taken out of its folder, and a content provider decides
 * which node it is put under instead. If they ever disagreed, a file would be
 * removed from one place without appearing in the other - the object would be
 * gone from the user interface, which is exactly the failure that ruled out
 * changing the layout of the files. They cannot disagree if there is only one
 * question, so there is only one: {@link #parentNodeOf}. The filter hides a
 * section when that question has an answer, and the provider shows it under
 * that answer.</p>
 */
public final class PartitionNesting {

    private PartitionNesting() {
    }

    /**
     * Whether sections are shown under their table instead of beside it. Off by
     * default: it takes files out of the folder they live in, and a person who
     * has not asked for that should not have to discover it.
     */
    public static boolean isEnabled() {
        Activator activator = Activator.getDefault();
        return activator != null && activator.getPreferenceStore()
                .getBoolean(PREF.NEST_PARTITIONS_UNDER_PARENT);
    }

    /**
     * The file this one is shown under, or {@code null} when it is shown where
     * it lives - which covers the switch being off, the folder not being
     * scanned yet, the file not being a section, and the section's table not
     * being a node of this folder.
     *
     * @param whenReady a viewer to refresh once a running scan lands
     */
    public static IFile parentNodeOf(IFile file, Viewer whenReady) {
        PartitionIndex.Folder folder = folderOf(file, whenReady);
        return folder == null ? null : folder.parentFileOf(file);
    }

    /**
     * The sections shown under this file. Empty unless the switch is on and
     * this file is the table of a family that lives in the same folder.
     *
     * @param whenReady a viewer to refresh once a running scan lands
     */
    public static List<IFile> sectionsUnder(IFile file, Viewer whenReady) {
        PartitionIndex.Folder folder = folderOf(file, whenReady);
        return folder == null ? List.of() : folder.sectionsUnder(file);
    }

    /**
     * What is known about the folder the file is in, or {@code null} when the
     * switch is off or nothing is known yet. Never blocks: a miss schedules the
     * scan and answers nothing, so the tree keeps showing what it shows until
     * the answer exists.
     */
    private static PartitionIndex.Folder folderOf(IFile file, Viewer viewer) {
        if (file == null || !isEnabled()
                || !(file.getParent() instanceof IFolder parent)) {
            return null;
        }
        return PartitionIndex.getInstance().folderOf(parent,
                () -> refresh(viewer, parent));
    }

    private static void refresh(Viewer viewer, IFolder folder) {
        if (!(viewer instanceof StructuredViewer structured)
                || structured.getControl() == null) {
            return;
        }
        UiSync.exec(structured.getControl(), () -> {
            if (!structured.getControl().isDisposed()) {
                structured.refresh(folder);
            }
        });
    }
}
