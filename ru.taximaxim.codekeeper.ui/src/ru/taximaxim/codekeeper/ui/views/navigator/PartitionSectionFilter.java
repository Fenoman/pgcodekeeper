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

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.jface.viewers.TreePath;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;

/**
 * Takes a section out of the folder it lives in - and only when it has
 * somewhere else to be.
 *
 * <p>{@code SCHEMA/tmp/TABLE} of OmniX holds 2101 files, of which 81 are tables
 * anyone edits and 2020 are hash sections. With this filter on, that folder is
 * 101 rows and every family is one click away under its own table. That is the
 * whole of the feature; everything else here is about not losing a file while
 * doing it.</p>
 *
 * <p>A filter in the Common Navigator Framework is a filter of the whole
 * viewer, not of the extension that declared it, so it is asked about every
 * element under every parent - including the sections shown under the grouping
 * node, which are the same {@link IFile} objects it has just hidden from the
 * folder. Hiding them there too would hide them everywhere. Hence the parent is
 * part of the question: a section is removed from its <em>folder</em> and
 * nowhere else.</p>
 *
 * <p>A section is only removed when {@link PartitionNesting#parentNodeOf} names
 * a file, which is the same answer the content provider nests it under. A
 * section whose table is in another schema, in a library, in an ignore list, or
 * simply not added to the project yet has no such answer, and stays where it
 * is. Nothing disappears; that is not a nicety but the condition the whole
 * approach rests on - taking an object out of the tree with nowhere to find it
 * again is what ruled out rearranging the files instead.</p>
 */
public class PartitionSectionFilter extends ViewerFilter {

    @Override
    public boolean select(Viewer viewer, Object parentElement, Object element) {
        if (!(element instanceof IFile file) || !(parentOf(parentElement) instanceof IFolder)) {
            return true;
        }
        return PartitionNesting.parentNodeOf(file, viewer) == null;
    }

    /**
     * The parent, however JFace chose to name it.
     *
     * <p>A tree viewer that supports tree paths - and the Common Navigator's is
     * one - filters through {@code ViewerFilter.filter(Viewer, TreePath,
     * Object[])}, whose {@code selectPath} hands the <em>path</em> itself down
     * to {@code select} as the parent element. So the argument is a
     * {@link TreePath} in the tree and the plain parent everywhere else, and a
     * filter that only understands the second one silently never matches.
     * Measured, not assumed: the first build of this class tested for
     * {@code IFolder} directly and hid nothing at all.</p>
     */
    private static Object parentOf(Object parentElement) {
        return parentElement instanceof TreePath path
                ? path.getLastSegment() : parentElement;
    }

    /**
     * The filter is asked once per element per refresh and answers from a map,
     * so it does not have to be told when the map changes. It does have to say
     * that it is not a property-dependent filter, or JFace will call
     * {@code isFilterProperty} on every update of every element.
     */
    @Override
    public boolean isFilterProperty(Object element, String property) {
        return false;
    }
}
