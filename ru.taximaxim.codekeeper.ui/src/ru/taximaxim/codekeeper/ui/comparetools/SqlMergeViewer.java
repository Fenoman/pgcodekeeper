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

import java.util.ArrayList;
import java.util.List;

import org.eclipse.compare.CompareConfiguration;
import org.eclipse.compare.contentmergeviewer.TextMergeViewer;
import org.eclipse.jface.text.TextViewer;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Composite;

import ru.taximaxim.codekeeper.ui.localizations.Messages;
import ru.taximaxim.codekeeper.ui.sqledit.SqlSourceViewer;

public class SqlMergeViewer extends TextMergeViewer {

    /**
     * What the comparison on display has to say about the object on display, see
     * {@link #setMarks(PaneMarks)}.
     * <p>
     * Deliberately without an initialiser, and so are the fields below.
     * {@link #createSourceViewer} runs from the constructor of the superclass,
     * which is to say before a single field declared here is initialised; an
     * initialiser would run afterwards and undo whatever that call had set. They
     * are therefore only ever written after construction, and are read through
     * methods the highlighters and the margins call.
     */
    private PaneMarks paneMarks;

    /** One of the three sides, kept for the background the theme gives them. */
    private SourceViewer anySide;

    /** The margin of every side, kept because each is refreshed by hand. */
    private List<PaneMarkRuler> rulers;

    public SqlMergeViewer(Composite parent, int style, CompareConfiguration conf) {
        super(parent, style, conf);
        // add initial input in order to avoid problems when disposing the viewer later
        updateContent(null, null, null);
    }

    /**
     * Tells the pane what the comparison says about the object about to be
     * shown, so that every side of it marks the same lines.
     * <p>
     * To be set before the input it belongs to: the shades are read when the
     * presentation of a side is built, which is what setting an input does for
     * every side.
     *
     * @param marks what the comparison says, {@link PaneMarks#NONE} when it says
     *              nothing, which is when nothing at all is done
     */
    public void setMarks(PaneMarks marks) {
        this.paneMarks = marks;
    }

    /**
     * Lets every margin read the rendering its side has just been given.
     * <p>
     * To be called after the input and not before. A margin takes the width the
     * letters in one rendering need, and until that rendering is there it has no
     * way of knowing whether it needs any - a comparison may overlook a
     * statistics target of a table that states none, and a margin on the screen
     * with nothing in it is a question asked of the reader for no reason.
     */
    public void refreshMargins() {
        if (rulers != null) {
            rulers.forEach(PaneMarkRuler::refresh);
        }
    }

    /**
     * What the sides of this pane are drawn on, for whoever has to derive a
     * colour that stays readable on it. {@code null} before there is a widget to
     * ask.
     *
     * @return the background of the rendering, as the theme in force made it
     */
    public RGB getPaneBackground() {
        if (anySide == null) {
            return null;
        }
        StyledText widget = anySide.getTextWidget();
        return widget == null || widget.isDisposed() || widget.getBackground() == null
                ? null : widget.getBackground().getRGB();
    }

    private PaneMarks getMarks() {
        return paneMarks == null ? PaneMarks.NONE : paneMarks;
    }

    @Override
    protected void configureTextViewer(TextViewer textViewer) {
        // viewer configures itself
    }

    @Override
    protected SourceViewer createSourceViewer(Composite parent,
            int textOrientation) {
        SqlSourceViewer viewer = new SqlSourceViewer(parent, textOrientation);
        // before the superclass adds the listener that highlights the changes,
        // see PaneMarkHighlighter on why the order is this way round
        viewer.addTextPresentationListener(new PaneMarkHighlighter(viewer, this::getMarks));

        // and before the compare framework adds its line numbers, so that this
        // one stands at the edge of the window, see PaneMarkRuler
        PaneMarkRuler ruler = new PaneMarkRuler(viewer, this::getMarks);
        viewer.addVerticalRulerColumn(ruler);
        if (rulers == null) {
            rulers = new ArrayList<>(3);
        }
        rulers.add(ruler);

        anySide = viewer;
        return viewer;
    }

    @Override
    public String getTitle() {
        return Messages.SqlMergeViewer_compare_label;
    }
}
