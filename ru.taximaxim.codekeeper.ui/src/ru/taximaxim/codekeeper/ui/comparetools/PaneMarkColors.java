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

import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.ui.editors.text.EditorsUI;
import org.pgcodekeeper.core.model.difftree.SqlMark;

/**
 * The two shades a marked stretch of the comparison pane is drawn on.
 * <p>
 * <b>Background and never foreground.</b> The foreground of this text is taken:
 * it is what the syntax highlighting says, and a rendering that argued with it
 * would take a keyword and a string apart to say something about a column. A
 * background says the same thing beside them instead of over them.
 * <p>
 * <b>Two shades and four marks.</b> What the shade answers is one question -
 * does this line take part in the comparison - and there are only two answers to
 * it, see {@link SqlMark#ignored()}. A column that leaves, a value the settings
 * overlook and a difference no migration can carry are one answer said about
 * three different things, and giving them a shade each would have a reader
 * hunting for a difference in meaning that the colour is not carrying - and
 * would need three ambers a theme can still be read through. Which of the three
 * a line is comes from the legend and from the words there, not from the hue.
 * <p>
 * <b>Mixed into the background of the pane rather than fixed.</b> An amber fixed
 * for a white editor is a glare in a dark one and the text on it stops being
 * readable; the same amber mixed into whatever the pane is drawn on comes out
 * pale over white and dark over dark, and keeps the contrast the theme was built
 * with. That background is asked of the widget itself, which is where the theme
 * of the workbench has already been applied, so this follows a theme change with
 * nothing to listen for.
 * <p>
 * <b>Two weights of one hue.</b> A stretch the comparison overlooks gets the
 * stronger of them, because it is the one a reader has to notice; a column a
 * rule names and something needs gets a version of the same shade that carries
 * about half as far, because it says "look again later" rather than "look now".
 * One hue and two weights, so that the pair reads as one thing said twice rather
 * than as two unrelated marks - and neither of them is the red or the green the
 * comparison itself is entitled to.
 * <p>
 * The colours are held by {@link EditorsUI#getSharedTextColors()}, which is
 * where the syntax highlighting of this very viewer holds its own, see
 * {@code SQLEditorSourceViewerConfiguration}. It keeps one instance per
 * {@link RGB} for the lifetime of the workbench, so asking for a colour per
 * rendering allocates nothing and there is nothing to dispose.
 */
public final class PaneMarkColors {

    /** Amber, as the request was for yellow and yellow alone is not seen on white. */
    private static final RGB AMBER = new RGB(255, 176, 0);

    /** How much of the amber a stretch the comparison overlooks carries. */
    private static final double IGNORED = 0.30;

    /** And a column a rule names while something needs it, which is compared as usual. */
    private static final double MANAGED = 0.14;

    private PaneMarkColors() {
    }

    /**
     * The background a marked stretch is drawn on.
     *
     * @param background what the pane itself is drawn on, {@code null} while the
     *                   widget cannot be asked
     * @param mark       what the comparison has to say about the stretch
     * @return a shared colour, never to be disposed by the caller
     */
    public static Color of(RGB background, SqlMark mark) {
        return EditorsUI.getSharedTextColors().getColor(rgbOf(background, mark));
    }

    /**
     * The same colour as a plain value, which is what makes the mixing above
     * something a test can hold to account without a workbench to run in.
     *
     * @param background what the pane itself is drawn on, {@code null} while the
     *                   widget cannot be asked, which is taken for white
     * @param mark       what the comparison has to say about the stretch
     * @return the shade to draw that stretch on
     */
    public static RGB rgbOf(RGB background, SqlMark mark) {
        RGB under = background == null ? new RGB(255, 255, 255) : background;
        double weight = mark.ignored() ? IGNORED : MANAGED;
        return new RGB(mix(under.red, AMBER.red, weight),
                mix(under.green, AMBER.green, weight),
                mix(under.blue, AMBER.blue, weight));
    }

    private static int mix(int background, int tint, double weight) {
        return (int) Math.round(background * (1 - weight) + tint * weight);
    }
}
