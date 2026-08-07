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
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.Viewer;

/**
 * Puts the sections of a table under the table, behind one grouping node.
 *
 * <p>Contributes children to a node the resource extension owns, which the
 * Common Navigator Framework allows: children of an element are the
 * concatenation of what every enabled extension whose trigger points match it
 * returns. So a {@code .sql} file keeps the statements
 * {@link NavigatorOutlineContentProvider} gives it and gains at most one node
 * on top.</p>
 *
 * <p><b>{@link #getParent(Object)} is the dangerous half of this class.</b> The
 * framework asks every extension that declares the element among its possible
 * children and takes the first answer that is not null, highest priority first;
 * this extension is the highest, so whatever it says wins over the resource
 * extension's answer, which is the folder the file lives in. Answering with the
 * grouping node while the section is still shown in its folder would give the
 * platform two routes to one resource, and "Show In" and "Link with Editor"
 * would follow whichever it found first. So the new chain is only ever returned
 * for a file that {@link PartitionSectionFilter} is really taking out of its
 * folder - the same question, asked through {@link PartitionNesting}.</p>
 */
public class PartitionNestingContentProvider implements ITreeContentProvider {

    private Viewer viewer;

    @Override
    public void inputChanged(Viewer viewer, Object oldInput, Object newInput) {
        this.viewer = viewer;
    }

    @Override
    public Object[] getElements(Object inputElement) {
        return getChildren(inputElement);
    }

    @Override
    public Object[] getChildren(Object parentElement) {
        if (parentElement instanceof PartitionGroupNode group) {
            return PartitionNesting
                    .sectionsUnder(group.getParentFile(), viewer).toArray();
        }
        if (parentElement instanceof IFile file
                && !PartitionNesting.sectionsUnder(file, viewer).isEmpty()) {
            return new Object[] { new PartitionGroupNode(file) };
        }
        return new Object[0];
    }

    @Override
    public Object getParent(Object element) {
        if (element instanceof PartitionGroupNode group) {
            return group.getParentFile();
        }
        if (element instanceof IFile file) {
            IFile parentFile = PartitionNesting.parentNodeOf(file, viewer);
            return parentFile == null ? null : new PartitionGroupNode(parentFile);
        }
        return null;
    }

    @Override
    public boolean hasChildren(Object element) {
        if (element instanceof PartitionGroupNode group) {
            return !PartitionNesting
                    .sectionsUnder(group.getParentFile(), viewer).isEmpty();
        }
        return element instanceof IFile file
                && !PartitionNesting.sectionsUnder(file, viewer).isEmpty();
    }

    /**
     * The sections of a table as the tree shows them. Used by the label
     * provider for the count and by the tests; here so that both read the same
     * list the children come from.
     */
    static List<IFile> sectionsOf(PartitionGroupNode group) {
        return PartitionNesting.sectionsUnder(group.getParentFile(), null);
    }
}
