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

import java.util.function.Supplier;

import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextPresentationListener;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.text.TextPresentation;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.graphics.RGB;
import org.pgcodekeeper.core.model.difftree.SqlMarkup.Marked;

/**
 * Colours the lines of one side of the comparison that the comparison itself has
 * something to say about - a column an ignore rule names, a value the settings
 * overlook.
 * <p>
 * <b>Why a presentation listener and not style ranges.</b> The presentation of
 * this viewer belongs to the syntax highlighting: a
 * {@code PresentationReconciler} rebuilds it whenever the document changes or a
 * region is damaged, and hands the whole thing to
 * {@code StyledText.setStyleRanges}. Anything written straight onto the widget
 * is thrown away by the next repair, and the reader would watch the marks
 * disappear as they scrolled. A listener is called with the presentation before
 * it is applied, so what it adds is a part of the presentation rather than
 * something painted over one, see {@code TextViewer.changeTextPresentation}.
 * <p>
 * <b>Merged, not replaced.</b> {@link TextPresentation#mergeStyleRange} takes
 * from a range only what that range states, and a range that states a background
 * alone therefore leaves every colour, weight and slant the highlighting decided
 * exactly where it was.
 * <p>
 * <b>Registered before the comparison highlights its own changes.</b> The
 * compare framework marks the tokens that differ through a listener of the same
 * kind, added right after the source viewer is built, see
 * {@code TextMergeViewer.ChangeHighlighter}. Registering from
 * {@code createSourceViewer} therefore puts this one first, and a line that both
 * of them speak about comes out in the colour of the difference: what a
 * migration is about to do outranks what a rule says about a column.
 * <p>
 * The whole rendering is read for every presentation and nothing is remembered.
 * A presentation is built when an object is chosen and when a damaged region is
 * repaired - a handful of times per click, not per keystroke, since nothing here
 * is editable - and the rendering of one object is a few kilobytes. A comparison
 * with nothing to say leaves before reading anything at all.
 * <p>
 * The colour is half of what a reader is given, and the half that a reader who
 * does not see colour is given nothing by. The other half is a letter beside the
 * line, see {@link PaneMarkRuler}, and both are drawn from the same stretches.
 */
final class PaneMarkHighlighter implements ITextPresentationListener {

    private final SourceViewer viewer;
    private final Supplier<PaneMarks> marks;

    /**
     * @param viewer the side of the comparison to colour
     * @param marks  what the comparison has to say about the object being shown,
     *               asked afresh at every presentation because the pane is given
     *               a new object whenever one is chosen
     */
    PaneMarkHighlighter(SourceViewer viewer, Supplier<PaneMarks> marks) {
        this.viewer = viewer;
        this.marks = marks;
    }

    @Override
    public void applyTextPresentation(TextPresentation presentation) {
        PaneMarks marked = marks.get();
        IDocument document = viewer.getDocument();
        StyledText widget = viewer.getTextWidget();
        if (marked.isEmpty() || document == null || widget == null || widget.isDisposed()) {
            return;
        }

        IRegion extent = presentation.getExtent();
        RGB background = widget.getBackground() == null ? null : widget.getBackground().getRGB();
        for (Marked range : marked.rangesIn(document.get())) {
            IRegion visible = clip(range, extent);
            if (visible != null) {
                // foreground left null on purpose: see the class comment on merging
                presentation.mergeStyleRange(new StyleRange(visible.getOffset(), visible.getLength(),
                        null, PaneMarkColors.of(background, range.mark())));
            }
        }
    }

    /**
     * As much of a marked stretch as the presentation being built covers, or
     * {@code null} where it covers none of it.
     * <p>
     * A presentation states the styles of its own region and of nothing else -
     * a repair after an edit or a scroll may well be handed a few lines of the
     * middle of a rendering - and a range reaching outside that region is
     * refused by the widget it is on its way to.
     */
    static IRegion clip(Marked range, IRegion extent) {
        int start = Math.max(range.offset(), extent.getOffset());
        int end = Math.min(range.offset() + range.length(), extent.getOffset() + extent.getLength());
        return end <= start ? null : new Region(start, end - start);
    }
}
