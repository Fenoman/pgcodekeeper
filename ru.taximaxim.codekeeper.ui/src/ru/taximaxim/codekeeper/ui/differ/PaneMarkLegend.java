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

import java.util.Set;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.pgcodekeeper.core.model.difftree.ColumnMark;
import org.pgcodekeeper.core.model.difftree.SqlMark;

import ru.taximaxim.codekeeper.ui.UIConsts;
import ru.taximaxim.codekeeper.ui.comparetools.PaneMarkColors;
import ru.taximaxim.codekeeper.ui.comparetools.PaneMarkRuler;
import ru.taximaxim.codekeeper.ui.comparetools.PaneMarks;
import ru.taximaxim.codekeeper.ui.localizations.Messages;

/**
 * Says what the marks in the comparison pane mean, above the pane and only while
 * there is something below them to explain.
 * <p>
 * <b>Why a legend and not a hover.</b> A mark nobody can name is worse than no
 * mark: the reader is told that something is special about a line and left to
 * guess what. A hover would answer that, but only once the reader thought to
 * point at the line and wait - which is exactly what somebody who has not
 * noticed the marks will never do - and the pane has no hover of its own to
 * extend, its source viewer being built without an annotation model. A row of a
 * few words is read before the question is asked, costs one line of the screen,
 * and costs nothing at all where a comparison has nothing to say, since it is
 * then not there. Each part of it appears only where that mark is really on the
 * screen, so a table with nothing kept is not asked to read about keeping.
 * <p>
 * <b>The letter is a part of it.</b> Three of the four marks put a letter in the
 * margin beside their lines and the fourth deliberately does not, see
 * {@code PaneMarkRuler}; a legend naming the shades and not the letter would
 * leave a reader who noticed the letter first with nothing to look it up by. So
 * the letter stands beside the shade it goes with, and does not beside the shade
 * it does not.
 * <p>
 * <b>Which is why the words carry the rest.</b> Neither the shade nor the letter
 * can say more than whether a line takes part in the comparison, and three marks
 * share that one answer while meaning quite different things by it. The words of
 * an entry are the only place where a value an operator chose to overlook is
 * told apart from a difference no migration will ever carry - the first comes
 * back the day the setting is turned off, the second never comes back at all -
 * so those two entries are worded to be read against each other and not merely
 * one after the other.
 * <p>
 * <b>And why the tooltips.</b> A few words can say what a mark means; they
 * cannot say <em>why this column</em>, which is the next question and the one
 * the rules have already answered. The columns are named there, and each kept one
 * is named together with what keeps it - the index, the view, the key that would
 * break without it, see {@code ColumnVisibility.pinnedColumns}. The values are
 * not enumerated: what a reader wants of a marked cache is not its name, which is
 * on the line in front of them, but what becomes of it - and that is the same
 * sentence every time.
 */
final class PaneMarkLegend extends Composite {

    /** Three cells to an entry: the shade, the letter, the words. */
    private static final int CELLS_PER_ENTRY = 3;

    private final Entry leaving;
    private final Entry kept;
    private final Entry ignoredValue;
    private final Entry unmigratable;

    private boolean shown;

    PaneMarkLegend(Composite parent) {
        super(parent, SWT.NONE);

        GridLayout layout = new GridLayout(CELLS_PER_ENTRY * 4, false);
        layout.marginWidth = layout.marginHeight = 0;
        setLayout(layout);
        setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, true, false));

        leaving = new Entry(SqlMark.COLUMN_LEAVING,
                Messages.PaneMarkLegend_leaving, Messages.PaneMarkLegend_leaving_hint);
        kept = new Entry(SqlMark.COLUMN_KEPT,
                Messages.PaneMarkLegend_kept, Messages.PaneMarkLegend_kept_hint);
        ignoredValue = new Entry(SqlMark.VALUE_IGNORED,
                Messages.PaneMarkLegend_value, Messages.PaneMarkLegend_value_hint);
        unmigratable = new Entry(SqlMark.VALUE_UNMIGRATABLE,
                Messages.PaneMarkLegend_unmigratable, Messages.PaneMarkLegend_unmigratable_hint);

        setMarks(PaneMarks.NONE, Set.of(), null);
    }

    /**
     * Shows what the comparison said about the object now on display, or takes
     * the row away when it said nothing.
     *
     * @param marks      what the comparison said
     * @param present    the marks that really fall on one of the two renderings,
     *                   so that nothing is explained that is not on the screen
     * @param background what the pane is drawn on, so that the swatches are the
     *                   very colours the pane draws with; {@code null} while the
     *                   pane cannot be asked
     * @return whether the row appeared or disappeared, so that the caller lays
     * out again only when the shape of it changed
     */
    boolean setMarks(PaneMarks marks, Set<SqlMark> present, RGB background) {
        boolean moved = leaving.setMarks(marks, present, background);
        moved |= kept.setMarks(marks, present, background);
        moved |= ignoredValue.setMarks(marks, present, background);
        moved |= unmigratable.setMarks(marks, present, background);

        boolean wasShown = shown;
        shown = leaving.shown || kept.shown || ignoredValue.shown || unmigratable.shown;
        exclude(this, !shown);
        return moved || shown != wasShown;
    }

    private static void exclude(Control control, boolean excluded) {
        GridData data = (GridData) control.getLayoutData();
        if (data != null) {
            data.exclude = excluded;
        }
        control.setVisible(!excluded);
    }

    /** One mark: its shade, its letter where it has one, and its name. */
    private final class Entry {

        private static final int SWATCH = 12;

        private final SqlMark mark;
        private final Label swatch;
        private final Label letter;
        private final Label text;
        private final String meaning;

        private boolean shown;

        private Entry(SqlMark mark, String name, String meaning) {
            this.mark = mark;
            this.meaning = meaning;

            swatch = new Label(PaneMarkLegend.this, SWT.BORDER);
            GridData size = new GridData(SWT.LEFT, SWT.CENTER, false, false);
            size.widthHint = size.heightHint = SWATCH;
            swatch.setLayoutData(size);

            letter = new Label(PaneMarkLegend.this, SWT.NONE);
            letter.setText(PaneMarkRuler.LETTER);
            letter.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));

            text = new Label(PaneMarkLegend.this, SWT.NONE);
            text.setText(name);
            text.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        }

        /**
         * Shows this mark while any line of the object wears it, and takes it
         * away otherwise: a table with nothing kept is not asked to read about
         * keeping.
         *
         * @return whether the entry appeared or disappeared, so that the row is
         * laid out again only when its shape changed
         */
        private boolean setMarks(PaneMarks marks, Set<SqlMark> present, RGB background) {
            boolean wasShown = shown;
            shown = present.contains(mark);
            exclude(swatch, !shown);
            exclude(letter, !shown || !mark.ignored());
            exclude(text, !shown);
            if (shown) {
                String hint = meaning + columnsWearing(marks);
                swatch.setBackground(PaneMarkColors.of(background, mark));
                swatch.setToolTipText(hint);
                letter.setToolTipText(hint);
                text.setToolTipText(hint);
            }
            return shown != wasShown;
        }

        /**
         * The columns of the object that wear this mark, each on its own line; a
         * kept one together with what keeps it.
         * <p>
         * Only the two marks a {@code type=COLUMN} rule produces are enumerated.
         * What a reader wants of a marked cache is not its name, which is on the
         * line in front of them, but what becomes of it - and that is the same
         * sentence every time. The same holds of an unmigratable collation, and
         * the columns wearing it are named on the lines themselves.
         */
        private String columnsWearing(PaneMarks marks) {
            if (mark == SqlMark.VALUE_IGNORED || mark == SqlMark.VALUE_UNMIGRATABLE) {
                return ""; //$NON-NLS-1$
            }

            ColumnMark wanted = mark == SqlMark.COLUMN_KEPT ? ColumnMark.PINNED : ColumnMark.HIDDEN;
            StringBuilder sb = new StringBuilder();
            for (var column : marks.marks().entrySet()) {
                if (column.getValue() != wanted) {
                    continue;
                }
                String keeper = marks.kept().get(column.getKey());
                sb.append(UIConsts._NL).append('\t').append(keeper == null ? column.getKey()
                        : Messages.PaneMarkLegend_kept_by.formatted(column.getKey(), keeper));
            }
            return sb.toString();
        }
    }
}
