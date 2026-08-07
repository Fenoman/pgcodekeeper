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

import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.swt.graphics.Image;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.navigator.IDescriptionProvider;

import ru.taximaxim.codekeeper.ui.Activator;
import ru.taximaxim.codekeeper.ui.localizations.Messages;

/**
 * Names the grouping node, and nothing else.
 *
 * <p>Answers {@code null} for everything that is not one so the framework falls
 * back to whoever owns the element - the resource extension for the section
 * files themselves, which keeps their names, icons and decorations exactly as
 * they are anywhere else in the tree.</p>
 *
 * <p>The count is in the label because it is the one thing a collapsed node
 * cannot show otherwise, and because a family of a hundred and a family of one
 * want different treatment from the reader.</p>
 */
public class PartitionNestingLabelProvider extends LabelProvider
        implements IDescriptionProvider {

    @Override
    public String getText(Object element) {
        if (element instanceof PartitionGroupNode group) {
            return Messages.PartitionGroupNode_label.formatted(
                    PartitionNestingContentProvider.sectionsOf(group).size());
        }
        return null;
    }

    @Override
    public Image getImage(Object element) {
        if (element instanceof PartitionGroupNode) {
            return Activator.getEclipseImage(ISharedImages.IMG_OBJ_FOLDER);
        }
        return null;
    }

    @Override
    public String getDescription(Object element) {
        if (element instanceof PartitionGroupNode group) {
            return Messages.PartitionGroupNode_description
                    .formatted(group.getParentFile().getFullPath().toString());
        }
        return null;
    }
}
