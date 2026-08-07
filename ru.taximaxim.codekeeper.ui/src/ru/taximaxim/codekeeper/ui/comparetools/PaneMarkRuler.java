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
package ru.taximaxim.codekeeper.ui.comparetools;

import java.util.BitSet;
import java.util.function.Supplier;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.source.AbstractRulerColumn;
import org.eclipse.jface.text.source.CompositeRuler;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.pgcodekeeper.core.model.difftree.SqlMarkup.Marked;

import ru.taximaxim.codekeeper.ui.localizations.Messages;

/**
 * Writes a letter in the margin of one side of the comparison, beside every line
 * that takes no part in that comparison.
 * <p>
 * <b>Why a letter at all.</b> A shade behind a line was the whole of what the
 * marking said before this, and a shade is nothing to a reader who does not see
 * colour, nothing on a projector that eats it and nothing in a screenshot pasted
 * into a ticket. The letter says the same thing in a form that survives all
 * three, and stands where a reader of an editor already looks for such things.
 * <p>
 * <b>And only beside the lines that are really ignored.</b> A column a rule names
 * while something in the database still needs it keeps its shade and gets no
 * letter: it is compared, migrated and written exactly like a column no rule
 * mentions, and calling it ignored would be false. The shade there says "a rule
 * named this and did not apply", which is a footnote; the letter says "this is
 * not in the comparison", which that is not, see
 * {@code SqlMark.ignored}.
 * <p>
 * <b>Why a ruler column and not an annotation.</b> The pane builds its source
 * viewers without an annotation model and without a
 * {@code SourceViewerDecorationSupport}, so the machinery that would normally
 * carry a marker into a margin - annotations, their access, their painter -
 * is not there, and standing it up per side would be a second decoration
 * mechanism inside a viewer that has none. A column of the composite ruler is
 * what is there: {@code SqlSourceViewer} already constructs the ruler, the
 * compare framework itself hangs the line-number column off it, see
 * {@code MergeSourceViewer.updateLineNumberRuler}, and such a column needs an
 * annotation model as little as that one does. Added first, from
 * {@code createSourceViewer}, so it stands at the edge of the window with the
 * line numbers - when they are switched on at all - between it and the text.
 * <p>
 * <b>Nothing on the screen while there is nothing to write.</b> The width is
 * zero unless this very rendering really holds a line to write beside, so an
 * object the comparison has nothing to say about costs it not one pixel - and so
 * does a table whose settings overlook a statistics target that the table does
 * not state. The width is therefore taken from the text, at {@link #refresh},
 * which the pane calls once the sides have been given their documents; it is
 * never taken while painting, because a column of no width is never asked to
 * paint and could never have grown back.
 */
public final class PaneMarkRuler extends AbstractRulerColumn {

    /**
     * Deliberately not translated. It has to fit a margin one character wide,
     * and it is the first letter of "ignored" in the language of the settings
     * that produce it - {@code --ignore-sequence-cache},
     * {@code --ignore-column-statistics}, the rules of an ignore list - which is
     * the word the legend above the pane uses in every language it is read in.
     * <p>
     * One letter for every mark that has one, and not a letter each. A margin of
     * one character can say that a line is outside the comparison and cannot say
     * why it is; a second letter would be a code to look up rather than a word
     * to read, and the legend is where the reasons are told apart in words, see
     * {@code PaneMarkLegend}.
     * <p>
     * Read by the legend as well, so that what is explained there is the very
     * character that stands in the margin.
     */
    public static final String LETTER = "i"; //$NON-NLS-1$

    /** Room on either side of the letter, so it is not drawn against the text. */
    private static final int PADDING = 3;

    private final SourceViewer viewer;
    private final Supplier<PaneMarks> marks;

    /** The lines of {@link #read} that carry the letter. */
    private BitSet marked = new BitSet();

    /** The document those lines were read from, and its length when they were. */
    private IDocument read;
    private int readLength = -1;

    /**
     * @param viewer the side of the comparison to write beside
     * @param marks  what the comparison has to say about the object being shown,
     *               asked afresh at every refresh because the pane is given a new
     *               object whenever one is chosen
     */
    PaneMarkRuler(SourceViewer viewer, Supplier<PaneMarks> marks) {
        this.viewer = viewer;
        this.marks = marks;
        setWidth(0);
    }

    @Override
    public Control createControl(CompositeRuler parentRuler, Composite parentControl) {
        Control control = super.createControl(parentRuler, parentControl);
        control.setToolTipText(Messages.PaneMarkRuler_hint);
        refresh();
        return control;
    }

    /**
     * Reads the rendering now on display and takes the width the letters in it
     * need, which is none when there are none.
     * <p>
     * To be called once the side has been given its document and not before: what
     * the comparison has to say is only half of what decides this, and the other
     * half is whether this rendering states any of it.
     */
    void refresh() {
        forget();
        setWidth(linesToMark().isEmpty() ? 0 : letterWidth());
        redraw();
    }

    /**
     * Draws the letter in the font and the colour of the text beside it, so that
     * it sits on the line it belongs to whatever font the pane has been given
     * since this column was built.
     */
    @Override
    protected void paintLine(GC gc, int modelLine, int widgetLine, int linePixel, int lineHeight) {
        gc.setBackground(computeBackground(modelLine));
        gc.fillRectangle(0, linePixel, getWidth(), lineHeight);
        if (!linesToMark().get(modelLine)) {
            return;
        }

        Font font = textFont();
        if (font != null) {
            gc.setFont(font);
        }
        gc.setForeground(computeForeground(modelLine));
        Point extent = gc.stringExtent(LETTER);
        gc.drawString(LETTER, Math.max(0, (getWidth() - extent.x) / 2),
                linePixel + Math.max(0, (lineHeight - extent.y) / 2), true);
    }

    /**
     * The letter is drawn in the colour of the text rather than in the grey a
     * ruler gives line numbers: it is not furniture, it is the one thing this
     * column has to say.
     */
    @Override
    protected Color computeForeground(int line) {
        StyledText widget = viewer.getTextWidget();
        return widget == null || widget.isDisposed() || widget.getForeground() == null
                ? super.computeForeground(line) : widget.getForeground();
    }

    /**
     * The lines of the document now on display that carry the letter, read once
     * per document and remembered until that document is replaced.
     * <p>
     * A margin is painted on every scroll and on every exposure, and reading a
     * whole rendering per paint would read the same few kilobytes hundreds of
     * times over. The pane hands each side a fresh document per object and never
     * edits one - these viewers are not editable - so a document that has been
     * neither replaced nor changed in length is the document these lines were
     * read from.
     */
    private BitSet linesToMark() {
        IDocument document = viewer.getDocument();
        if (document == read && (document == null || document.getLength() == readLength)) {
            return marked;
        }

        read = document;
        readLength = document == null ? -1 : document.getLength();
        marked = new BitSet();
        if (document == null) {
            return marked;
        }

        for (Marked range : marks.get().rangesIn(document.get())) {
            if (!range.mark().ignored()) {
                continue;
            }
            try {
                int from = document.getLineOfOffset(range.offset());
                int to = document.getLineOfOffset(range.offset() + Math.max(0, range.length() - 1));
                marked.set(from, to + 1);
            } catch (BadLocationException e) {
                // the stretch was read out of this very text, so this cannot
                // happen; a margin is no place to fail loudly if it ever does
                break;
            }
        }
        return marked;
    }

    private void forget() {
        read = null;
        readLength = -1;
        marked = new BitSet();
    }

    /**
     * How wide the margin has to be for the letter and its padding.
     * <p>
     * Measured when a rendering arrives and not while painting, because setting
     * a width lays the viewer out again and a layout asked for from inside a
     * paint is a loop waiting to be closed. A font changed while one object stays
     * on the screen therefore leaves the margin a pixel or two off until the next
     * object is chosen, which is the whole of what that buys.
     */
    private int letterWidth() {
        Control control = getControl();
        if (control == null || control.isDisposed()) {
            // built before there is a canvas to measure on; createControl asks again
            return getWidth();
        }

        GC gc = new GC(control);
        try {
            Font font = textFont();
            if (font != null) {
                gc.setFont(font);
            }
            return gc.stringExtent(LETTER).x + 2 * PADDING;
        } finally {
            gc.dispose();
        }
    }

    private Font textFont() {
        StyledText widget = viewer.getTextWidget();
        return widget == null || widget.isDisposed() ? getFont() : widget.getFont();
    }
}
