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

import java.text.Collator;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IResource;
import org.eclipse.jface.viewers.StructuredViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.ui.views.navigator.ResourceComparator;

import ru.taximaxim.codekeeper.ui.Activator;
import ru.taximaxim.codekeeper.ui.UiSync;
import ru.taximaxim.codekeeper.ui.UIConsts.PREF;

/**
 * Sends the sections of a folder below the tables of that folder.
 *
 * <p>The folder they share is the one place the sections hurt. {@code
 * SCHEMA/tmp/TABLE} of OmniX holds 2101 files; 81 of them are tables anyone
 * edits and the other 2020 are hash sections, twenty walls of a hundred nearly
 * identical names standing between them. Alphabetically the first fifty rows
 * of that folder show seven of the eighty-one. With the sections below, they
 * show fifty, and all eighty-one fit in two screens.</p>
 *
 * <p>Nothing is hidden and nothing is reparented: every file stays in its own
 * folder, at its own path, findable by every operation that finds it today.
 * The only thing that changes is which row it is on. Within the sections the
 * order is by parent table, so a family stays whole and follows the table it
 * belongs to.</p>
 *
 * <p>Ordering that is not about sections is the platform's, unchanged - this
 * extends {@link ResourceComparator} and repeats the collator that
 * {@code ResourceExtensionComparator} uses, so a folder without sections is
 * sorted exactly as the resource extension would have sorted it. That matters
 * more than it looks: the sort-only extension this comparator is declared in
 * outranks the resource extension for <em>every</em> folder of a pgCodeKeeper
 * project, so everything it does not mean to change it has to reproduce.</p>
 */
public class PartitionAwareComparator extends ResourceComparator {

    /**
     * A section sorts after everything that is not one. Two ranks, so the
     * comparison stays a subtraction.
     */
    private static final int PLAIN = 0;
    private static final int SECTION = 1;

    private final Collator collator = Collator.getInstance();

    public PartitionAwareComparator() {
        super(NAME);
    }

    @Override
    public int compare(Viewer viewer, Object e1, Object e2) {
        if (!(e1 instanceof IFile) || !(e2 instanceof IFile) || !isEnabled()) {
            return super.compare(viewer, e1, e2);
        }
        PartitionIndex.Folder folder = folderOf((IFile) e1, viewer);
        if (folder == null || folder.isEmpty()) {
            return super.compare(viewer, e1, e2);
        }
        String parent1 = folder.parentOf((IFile) e1);
        String parent2 = folder.parentOf((IFile) e2);
        int rank = rank(parent1) - rank(parent2);
        if (rank != 0) {
            return rank;
        }
        if (parent1 != null && parent2 != null) {
            int byParent = collator.compare(
                    PartitionLinkReader.groupingKey(parent1),
                    PartitionLinkReader.groupingKey(parent2));
            if (byParent != 0) {
                return byParent;
            }
        }
        return super.compare(viewer, e1, e2);
    }

    /**
     * The same collator {@code ResourceExtensionComparator} uses, on the same
     * string. Reproduced rather than borrowed because that class is internal
     * to {@code org.eclipse.ui.navigator.resources}.
     */
    @Override
    protected int compareNames(IResource r1, IResource r2) {
        return collator.compare(r1.getName(), r2.getName());
    }

    private static int rank(String parent) {
        return parent == null ? PLAIN : SECTION;
    }

    /**
     * What is known about the folder the files are in, or {@code null} while
     * the scan is still running. A miss asks the viewer to come back once the
     * answer exists; asking here rather than blocking is the whole contract of
     * {@link PartitionIndex}.
     */
    private static PartitionIndex.Folder folderOf(IFile file, Viewer viewer) {
        if (!(file.getParent() instanceof IFolder parent)) {
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

    private static boolean isEnabled() {
        Activator activator = Activator.getDefault();
        return activator == null
                || activator.getPreferenceStore().getBoolean(PREF.GROUP_PARTITIONS_IN_TREE);
    }
}
