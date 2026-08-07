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

import java.util.Objects;

import org.eclipse.core.resources.IFile;

/**
 * The one node the sections of a table hang from.
 *
 * <p>They are not hung on the table's own node directly, and that is not a
 * matter of taste. A {@code .sql} file in the Project Explorer already has
 * children - the statements of its own text, from
 * {@link NavigatorOutlineContentProvider}, whose {@code hasChildren} answers
 * true for very nearly everything. Putting a hundred section files next to
 * those statements would mix two different kinds of thing under one node and
 * bury the outline. With this node the table gains exactly one extra child
 * whatever the size of the family, and the family is one keystroke away.</p>
 *
 * <p>Its identity is the parent file and nothing else. A tree viewer maps
 * elements to items by equality, so a node built freshly on every
 * {@code getChildren} has to equal the one already in the tree or the branch
 * collapses on every refresh. The number of sections is deliberately <em>not</em>
 * part of it: the count changes when a section is added, and a node that
 * changed identity because of that would lose its expansion state exactly when
 * the user is watching.</p>
 */
public final class PartitionGroupNode {

    private final IFile parentFile;

    public PartitionGroupNode(IFile parentFile) {
        this.parentFile = Objects.requireNonNull(parentFile);
    }

    /** The file defining the table whose sections these are. */
    public IFile getParentFile() {
        return parentFile;
    }

    @Override
    public int hashCode() {
        return parentFile.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return this == obj || (obj instanceof PartitionGroupNode other
                && parentFile.equals(other.parentFile));
    }

    @Override
    public String toString() {
        return "sections of " + parentFile.getFullPath(); //$NON-NLS-1$
    }
}
