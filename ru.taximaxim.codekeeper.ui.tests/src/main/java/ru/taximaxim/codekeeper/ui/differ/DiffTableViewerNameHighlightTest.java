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
package ru.taximaxim.codekeeper.ui.differ;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.eclipse.jface.util.Policy;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.PlatformUI;
import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.model.difftree.TreeElement;
import org.pgcodekeeper.core.model.difftree.TreeElement.DiffSide;

import ru.taximaxim.codekeeper.ui.DatabaseType;
import ru.taximaxim.codekeeper.ui.localizations.Messages;

/**
 * The object name column highlights what the filter matched, and highlighting
 * takes an owner-draw label provider. That provider forces a redraw per cell
 * through {@code ViewerCell.getBounds()}, which on Cocoa locates the row by
 * walking the outline view, so every row a refresh builds costs O(rows) - the
 * refresh that follows a cleared filter rebuilt the whole comparison and hung
 * the IDE for tens of seconds. An empty filter matches nothing, so it needs no
 * highlighting and must not be paying for it.
 */
class DiffTableViewerNameHighlightTest {

    /**
     * Where JFace keeps the style ranges a cell was given, see
     * {@code ViewerRow.setStyleRanges(int, StyleRange[])}. Read off the widget
     * because that is where the highlight lives: the provider hands the ranges
     * to the cell and the cell puts them on the item.
     */
    private static final String STYLE_RANGES_KEY = Policy.JFACE + "styled_label_key_"; //$NON-NLS-1$

    private static final String MATCHING = "xp_sp_subscr_get_code_epd"; //$NON-NLS-1$
    private static final String OTHER = "sd_close_periods"; //$NON-NLS-1$
    private static final String FILTER = "subscr"; //$NON-NLS-1$

    @Test
    void nameColumnHighlightsOnlyWhileAFilterIsSet() {
        runOnUiThread(display -> {
            Shell shell = new Shell(display);
            try {
                shell.setLayout(new FillLayout());
                shell.setSize(900, 600);
                DiffTableViewer table = new DiffTableViewer(shell, true, DatabaseType.PG, null);
                shell.open();
                dispatchPendingEvents(display);

                table.setInputCollection(List.of(
                        new TreeElement(MATCHING, DbObjType.TABLE, DiffSide.BOTH),
                        new TreeElement(OTHER, DbObjType.TABLE, DiffSide.BOTH)),
                        null, null, Set.of());
                dispatchPendingEvents(display);

                Tree tree = ((TreeViewer) table.getViewer()).getTree();
                int nameColumn = nameColumnIndex(tree);

                assertFalse(tree.isListening(SWT.PaintItem),
                        "a viewer opens unfiltered, so the name column must open on the plain provider"); //$NON-NLS-1$
                assertNull(styleRanges(itemNamed(tree, nameColumn, MATCHING), nameColumn),
                        "nothing is filtered, so nothing is highlighted"); //$NON-NLS-1$

                table.applyNameFilter(FILTER);
                dispatchPendingEvents(display);

                assertTrue(tree.isListening(SWT.PaintItem),
                        "a filter is set, so the highlighting provider must be on the name column"); //$NON-NLS-1$
                assertEquals(1, tree.getItemCount(), "only the matching object survives the filter"); //$NON-NLS-1$
                StyleRange[] ranges = styleRanges(itemNamed(tree, nameColumn, MATCHING), nameColumn);
                assertNotNull(ranges, "the matched object must carry the highlight"); //$NON-NLS-1$
                assertEquals(1, ranges.length, "one match, one range"); //$NON-NLS-1$
                assertEquals(MATCHING.indexOf(FILTER), ranges[0].start, "the range starts where the filter matched"); //$NON-NLS-1$
                assertEquals(FILTER.length(), ranges[0].length, "the range covers what the filter matched"); //$NON-NLS-1$
                assertSame(display.getSystemColor(SWT.COLOR_YELLOW), ranges[0].background,
                        "the match is picked out in yellow"); //$NON-NLS-1$

                table.applyNameFilter(""); //$NON-NLS-1$
                dispatchPendingEvents(display);

                assertFalse(tree.isListening(SWT.PaintItem),
                        "the filter is cleared, so the name column must be back on the plain provider"); //$NON-NLS-1$
                assertEquals(2, tree.getItemCount(), "clearing the filter brings every object back"); //$NON-NLS-1$
                assertNull(styleRanges(itemNamed(tree, nameColumn, MATCHING), nameColumn),
                        "a row that is no longer highlighted must not be carrying a highlight"); //$NON-NLS-1$
                assertNull(styleRanges(itemNamed(tree, nameColumn, OTHER), nameColumn),
                        "a row that never matched must not be carrying a highlight"); //$NON-NLS-1$
            } finally {
                shell.dispose();
            }
        });
    }

    private static int nameColumnIndex(Tree tree) {
        for (int i = 0; i < tree.getColumnCount(); i++) {
            // the header carries a sort marker once the viewer has been sorted
            if (tree.getColumn(i).getText().contains(Messages.diffTableViewer_object_name)) {
                return i;
            }
        }
        return fail("no object name column on the viewer"); //$NON-NLS-1$
    }

    private static TreeItem itemNamed(Tree tree, int nameColumn, String name) {
        for (TreeItem item : tree.getItems()) {
            if (name.equals(item.getText(nameColumn))) {
                return item;
            }
        }
        return fail("no row rendering " + name + " in the object name column"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static StyleRange[] styleRanges(TreeItem item, int columnIndex) {
        return (StyleRange[]) item.getData(STYLE_RANGES_KEY + columnIndex);
    }

    private static void runOnUiThread(Consumer<Display> action) {
        Display display = PlatformUI.getWorkbench().getDisplay();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        display.syncExec(() -> {
            try {
                action.accept(display);
            } catch (Throwable ex) {
                failure.set(ex);
            }
        });
        Throwable thrown = failure.get();
        // rethrown as it stands so the failure a reader is shown is the one that
        // was asserted, not a wrapper around it
        if (thrown instanceof AssertionError error) {
            throw error;
        }
        if (thrown != null) {
            throw new AssertionError("SWT work failed", thrown); //$NON-NLS-1$
        }
    }

    private static void dispatchPendingEvents(Display display) {
        while (display.readAndDispatch()) {
            // drain the events the viewer queued while it was rebuilt
        }
    }
}
