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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.Region;
import org.eclipse.swt.graphics.RGB;
import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.model.difftree.IgnoredValues;
import org.pgcodekeeper.core.model.difftree.SqlMark;
import org.pgcodekeeper.core.model.difftree.SqlMarkup.Marked;

import ru.taximaxim.codekeeper.ui.localizations.Messages;

/**
 * The parts of the marking that can be held to account without a workbench to
 * run in: the shade a mark is drawn on, which marks put a letter in the margin,
 * and how much of a marked stretch a presentation is allowed to be told about.
 * <p>
 * What is left over is the drawing itself - that the shades reach the widget,
 * survive a repair of the syntax highlighting, and that the letters sit beside
 * the right lines - and that wants a running IDE and a pair of eyes.
 */
class PaneMarkingTest {

    private static final RGB LIGHT = new RGB(255, 255, 255);
    private static final RGB DARK = new RGB(43, 43, 43);

    /**
     * The shade is mixed into the background of the pane, so a light theme gets
     * a pale amber and a dark theme a dim one. A shade fixed for one of them
     * would be a glare in the other, and the text drawn on it would stop being
     * readable - which is the whole reason for deriving it rather than choosing
     * it.
     */
    @Test
    void theShadeIsMixedIntoWhateverTheThemeDrawsOn() {
        RGB onLight = PaneMarkColors.rgbOf(LIGHT, SqlMark.COLUMN_LEAVING);
        RGB onDark = PaneMarkColors.rgbOf(DARK, SqlMark.COLUMN_LEAVING);

        assertTrue(brightness(onLight) > brightness(DARK) * 2,
                "a light theme keeps a light background: " + onLight);
        assertTrue(brightness(onDark) < brightness(LIGHT) / 2,
                "and a dark theme a dark one: " + onDark);
        assertTrue(onLight.red > onLight.blue && onDark.red > onDark.blue,
                "both of them warm, which is what the mark is recognised by");
    }

    /**
     * A column something needs carries the calmer of the two shades, and the
     * mark of a column that leaves is the one that carries. Both are far enough
     * from the background to be seen at all, or the mark would be a rumour.
     */
    @Test
    void aKeptColumnIsMarkedMoreQuietlyThanOneThatLeaves() {
        for (RGB theme : new RGB[] { LIGHT, DARK }) {
            RGB leaving = PaneMarkColors.rgbOf(theme, SqlMark.COLUMN_LEAVING);
            RGB kept = PaneMarkColors.rgbOf(theme, SqlMark.COLUMN_KEPT);

            assertNotEquals(leaving, kept, "the two marks are told apart on " + theme);
            assertTrue(distance(theme, kept) > 8, "a kept column is still seen: " + kept);
            assertTrue(distance(theme, leaving) > distance(theme, kept) * 1.5,
                    "and a leaving one is seen first: " + leaving + " against " + kept);
        }
    }

    /**
     * A value the settings overlook wears the shade of a column that leaves,
     * because the shade answers one question - does this line take part in the
     * comparison - and the two are the same answer. What each of them is comes
     * from the legend, which shows them apart.
     */
    @Test
    void anOverlookedValueWearsTheShadeOfWhateverElseIsOverlooked() {
        for (RGB theme : new RGB[] { LIGHT, DARK }) {
            for (SqlMark mark : SqlMark.values()) {
                assertEquals(PaneMarkColors.rgbOf(theme, mark.ignored()
                        ? SqlMark.COLUMN_LEAVING : SqlMark.COLUMN_KEPT),
                        PaneMarkColors.rgbOf(theme, mark),
                        "the shade answers one question and " + mark + " is one of its two answers");
            }
            assertNotEquals(PaneMarkColors.rgbOf(theme, SqlMark.COLUMN_KEPT),
                    PaneMarkColors.rgbOf(theme, SqlMark.VALUE_IGNORED),
                    "and the one that says the opposite is told apart on " + theme);
        }
    }

    /**
     * A difference nothing can migrate is outside the comparison like the rest,
     * so it wears their shade and their letter - and the legend is where it is
     * told apart from them, because a hue and a single character cannot say why
     * a line is outside, only that it is.
     */
    @Test
    void aDifferenceNothingCanMigrateIsMarkedLikeTheRestAndNamedApart() {
        assertTrue(SqlMark.VALUE_UNMIGRATABLE.ignored(), "no migration will carry the line");
        assertEquals(PaneMarkColors.rgbOf(LIGHT, SqlMark.VALUE_IGNORED),
                PaneMarkColors.rgbOf(LIGHT, SqlMark.VALUE_UNMIGRATABLE), "so it wears the same shade");

        assertNotEquals(Messages.PaneMarkLegend_value, Messages.PaneMarkLegend_unmigratable,
                "and is named apart from a value a setting merely overlooks");
        assertNotEquals(Messages.PaneMarkLegend_value_hint, Messages.PaneMarkLegend_unmigratable_hint,
                "and explained apart from it, which is the whole of what tells them apart");
    }

    /**
     * The margin writes beside a line that takes no part in the comparison and
     * beside no other. A column a rule names while something in the database
     * still needs it is compared, migrated and written like any other, and a
     * letter reading "ignored" beside it would be false - the whole reason the
     * two are not one mark.
     */
    @Test
    void theMarginWritesBesideWhatIsOutsideTheComparisonAndNothingElse() {
        assertEquals(1, PaneMarkRuler.LETTER.length(), "a margin is one character wide");

        for (SqlMark mark : SqlMark.values()) {
            assertEquals(mark != SqlMark.COLUMN_KEPT, mark.ignored(), "the letter belongs to " + mark);
        }
    }

    /**
     * A pane told about values alone still has something to say, which is what
     * decides whether the margin is on the screen at all: a comparison of
     * default settings and no column rules gives it no width and costs it
     * nothing.
     */
    @Test
    void aPaneToldOnlyAboutValuesIsNotEmpty() {
        assertTrue(PaneMarks.NONE.isEmpty(), "nothing to say about anything");
        assertTrue(PaneMarks.NONE.rangesIn("CREATE SEQUENCE s\n\tCACHE 10;").isEmpty(),
                "and nothing to read a rendering for");

        PaneMarks values = new PaneMarks(Map.of(), Map.of(),
                new IgnoredValues(true, Set.of(), Set.of(), Set.of()));
        assertFalse(values.isEmpty(), "a cache the settings overlook is something to say");
        assertEquals(1, values.rangesIn("CREATE SEQUENCE s\n\tCACHE 10;").size(),
                "and the line stating it is where it is said");

        PaneMarks collation = new PaneMarks(Map.of(), Map.of(),
                new IgnoredValues(false, Set.of(), Set.of(), Set.of("title")));
        assertFalse(collation.isEmpty(), "and so is a collation no migration can carry");
        assertEquals(1, collation.rangesIn("CREATE TABLE t (\n\ttitle text COLLATE \"ru_RU\"\n);").size(),
                "which is said on the line that declares the column");
    }

    /** With no widget to ask, a light background is assumed rather than none. */
    @Test
    void anUnknownBackgroundIsTakenForALightOne() {
        assertEquals(PaneMarkColors.rgbOf(LIGHT, SqlMark.COLUMN_LEAVING),
                PaneMarkColors.rgbOf(null, SqlMark.COLUMN_LEAVING));
    }

    /**
     * A presentation states the styles of its own region and of nothing else,
     * so a stretch is offered to it cut to that region - and a stretch outside
     * it is not offered at all.
     */
    @Test
    void aStretchIsCutToTheRegionOfThePresentation() {
        Marked range = new Marked(100, 20, SqlMark.COLUMN_LEAVING);

        assertEquals(new Region(100, 20), clip(range, 0, 500), "a region holding it whole changes nothing");
        assertEquals(new Region(110, 10), clip(range, 110, 390), "a region beginning inside it cuts the head");
        assertEquals(new Region(100, 5), clip(range, 0, 105), "a region ending inside it cuts the tail");
        assertEquals(new Region(105, 5), clip(range, 105, 5), "a region inside it leaves that much");

        assertNull(clip(range, 0, 100), "a region ending where it begins holds none of it");
        assertNull(clip(range, 120, 100), "and neither does one beginning where it ends");
    }

    private static IRegion clip(Marked range, int offset, int length) {
        return PaneMarkHighlighter.clip(range, new Region(offset, length));
    }

    private static int brightness(RGB color) {
        return (color.red + color.green + color.blue) / 3;
    }

    private static int distance(RGB from, RGB to) {
        return Math.abs(from.red - to.red) + Math.abs(from.green - to.green) + Math.abs(from.blue - to.blue);
    }
}
