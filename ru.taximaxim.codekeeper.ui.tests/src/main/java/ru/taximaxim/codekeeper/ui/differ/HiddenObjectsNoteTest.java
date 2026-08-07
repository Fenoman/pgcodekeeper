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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.ignorelist.IgnoreList;
import org.pgcodekeeper.core.ignorelist.IgnoredObject;
import org.pgcodekeeper.core.model.difftree.HiddenObjects.HiddenByRule;
import org.pgcodekeeper.core.model.difftree.HiddenObjects.Report;

import ru.taximaxim.codekeeper.ui.localizations.Messages;

/**
 * What the note says, which is all of it that can be held to account without a
 * workbench to run in. What is left over is where the words sit and whether they
 * take room on the screen when there is nothing to say, and that wants a running
 * IDE and a pair of eyes.
 */
class HiddenObjectsNoteTest {

    private static final String PARTITIONS = "HIDE CONTENT,REGEX 'fw_charge_details_d\\d+_\\d{6}' type=TABLE";

    /**
     * A project that hides nothing is told nothing. This is what keeps the note
     * off the screen of everybody who never wrote an ignore rule, and it is the
     * only case where silence is the right answer.
     */
    @Test
    void aComparisonWithoutRulesGetsNoNote() {
        assertFalse(HiddenObjectsNote.speaks(Report.EMPTY, 0));
    }

    /**
     * The case the note exists for. Rules that hid nothing are not silence: they
     * are the shape a rule takes after the object was renamed or the pattern
     * mistyped, and until this was said out loud that looked exactly like a rule
     * doing its job.
     */
    @Test
    void rulesThatHidNothingAreStillSpokenAbout() {
        Report report = new Report(0, Map.of(), List.of(new HiddenByRule("HIDE NONE t type=TRIGGER", 0, List.of())));

        assertTrue(HiddenObjectsNote.speaks(report, 0), "nothing hidden is an answer, not an absence");
        assertEquals(Messages.DiffTableViewer_hidden_nothing, HiddenObjectsNote.text(report, 0),
                "a word is read as an event where a digit is read as an absence");
        assertTrue(HiddenObjectsNote.hint(report, null, 0)
                .contains(Messages.DiffTableViewer_hidden_nothing_hint.formatted(1)),
                "and the hint says how many rules could have hidden something");
    }

    @Test
    void whatWasHiddenIsCountedInTheWords() {
        Report report = new Report(137, Map.of(DbObjType.TABLE, 100, DbObjType.INDEX, 37), List.of());

        assertEquals(Messages.DiffTableViewer_hidden.formatted(137), HiddenObjectsNote.text(report, 0));
    }

    /**
     * The kinds of object come before any rule is read, most taken first: it is
     * the line that answers "did the rules take what they were written for"
     * without asking anybody to read a regular expression.
     */
    @Test
    void theKindsOfObjectComeFirstAndTheBiggestOfThemFirstOfAll() {
        Report report = new Report(137, Map.of(DbObjType.TABLE, 100, DbObjType.INDEX, 37), List.of());
        String hint = HiddenObjectsNote.hint(report, null, 0);

        assertTrue(hint.contains("TABLE 100, INDEX 37"), hint);
    }

    /**
     * A count says the rules are alive, a name says they are alive on the right
     * objects. Both, and neither alone is the answer: the rule as its author
     * wrote it, so that it can be found in the file, and a name or two of what it
     * really took.
     */
    @Test
    void everyFiringRuleIsNamedWithAnExampleOfWhatItTook() {
        Report report = new Report(84, Map.of(DbObjType.TABLE, 84),
                List.of(new HiddenByRule(PARTITIONS, 84,
                        List.of("fw.fw_charge_details_d1_202601", "fw.fw_charge_details_d1_202602"))));

        String hint = HiddenObjectsNote.hint(report, null, 0);
        assertTrue(hint.contains(Messages.DiffTableViewer_hidden_hint_rule.formatted(PARTITIONS, 84)), hint);
        assertTrue(hint.contains("fw.fw_charge_details_d1_202601"), hint);
        assertTrue(hint.contains("fw.fw_charge_details_d1_202602"), hint);
    }

    /**
     * Beyond a handful the rules are counted rather than named. On the list this
     * was built against a hundred and sixty rules is ordinary, and a hint naming
     * all of them is the wall of text the note exists to avoid.
     */
    @Test
    void aLongListOfRulesIsCutShortRatherThanPoured() {
        List<HiddenByRule> rules = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            rules.add(new HiddenByRule("HIDE NONE rule_" + i + " type=TABLE", 30 - i, List.of()));
        }
        Report report = new Report(465, Map.of(DbObjType.TABLE, 465), rules);

        String hint = HiddenObjectsNote.hint(report, null, 0);
        assertTrue(hint.contains("rule_0 "), "the rule that took most is named");
        assertFalse(hint.contains("rule_29 "), "the rule that took least is not");
        assertTrue(hint.contains(Messages.DiffTableViewer_hidden_hint_more_rules.formatted(22)), hint);
        assertTrue(hint.lines().count() < 40, "the hint stays readable: " + hint.lines().count() + " lines");
    }

    /**
     * The rules that took nothing are counted and not named, for the same reason
     * in reverse: on a real list most of them have nothing to take today, and a
     * hundred and forty names would bury the eight that matter.
     */
    @Test
    void theRulesThatTookNothingAreCountedNotNamed() {
        Report report = new Report(4, Map.of(DbObjType.TABLE, 4),
                List.of(new HiddenByRule("HIDE NONE taken type=TABLE", 4, List.of()),
                        new HiddenByRule("HIDE NONE idle_one type=TABLE", 0, List.of()),
                        new HiddenByRule("HIDE NONE idle_two type=TABLE", 0, List.of())));

        String hint = HiddenObjectsNote.hint(report, null, 0);
        assertTrue(hint.contains(Messages.DiffTableViewer_hidden_hint_idle.formatted(2)), hint);
        assertFalse(hint.contains("idle_one"), "an idle rule is counted, not named: " + hint);
    }

    /**
     * What a white list hides by being one belongs to no rule, and is said in
     * words rather than shown as a blank where a rule should be.
     */
    @Test
    void whatNoRuleNamedIsNamedAsThat() {
        Report report = new Report(3, Map.of(DbObjType.TABLE, 3), List.of(new HiddenByRule(null, 3, List.of())));

        assertTrue(HiddenObjectsNote.hint(report, null, 0).contains(Messages.DiffTableViewer_hidden_hint_no_rule),
                HiddenObjectsNote.hint(report, null, 0));
    }

    /**
     * A {@code type=COLUMN} rule hides no row of this table and is deliberately
     * absent from the count. Somebody who wrote one and does not find it here
     * must be told where it is answered instead, or the absence reads as a rule
     * that stopped working - which is the very confusion this note removes.
     */
    @Test
    void aListWithColumnRulesSaysWhereThoseAreAnswered() {
        Report report = new Report(2, Map.of(DbObjType.TRIGGER, 2), List.of());

        assertFalse(HiddenObjectsNote.hint(report, new IgnoreList(), 0)
                .contains(Messages.DiffTableViewer_hidden_hint_columns),
                "a list without column rules is not told about columns");
        assertTrue(HiddenObjectsNote.hint(report, listHidingColumns(), 0)
                .contains(Messages.DiffTableViewer_hidden_hint_columns),
                "a list with them is");
    }

    /**
     * A comparison loaded without analysis turns its {@code type=COLUMN} rules
     * off, and by the time this note is written they are gone from the list -
     * so nothing here can find them, and the previous paragraph, which points
     * a reader at the marks beside the columns, is a lie for this comparison:
     * there are no marks, nothing was asked. This is the only place in the
     * comparison that mentions those rules at all, so it says how many and
     * why.
     */
    @Test
    void retiredColumnRulesAreNamedInsteadOfVanishing() {
        Report report = new Report(2, Map.of(DbObjType.TRIGGER, 2), List.of());

        String hint = HiddenObjectsNote.hint(report, new IgnoreList(), 6);
        assertTrue(hint.contains(Messages.DiffTableViewer_hidden_hint_retired_columns.formatted(6)), hint);
        assertFalse(hint.contains(Messages.DiffTableViewer_hidden_hint_columns),
                "a rule that was never asked is not answered beside the columns either: " + hint);
    }

    /**
     * And it says it where a reader will see it without hovering anything: the
     * words beside the object count, not the hint behind them. The tooltip of a
     * label nobody has a reason to point at is the same silence in a longer
     * form.
     */
    @Test
    void retiredColumnRulesAreSaidInTheWordsAndNotOnlyInTheHint() {
        Report report = new Report(137, Map.of(DbObjType.TABLE, 137), List.of());

        String text = HiddenObjectsNote.text(report, 6);
        assertTrue(text.contains(Messages.DiffTableViewer_hidden.formatted(137)), text);
        assertTrue(text.contains(Messages.DiffTableViewer_retired_columns.formatted(6)), text);
    }

    /**
     * A list of nothing but {@code type=COLUMN} rules leaves an otherwise
     * silent report behind: such a rule can hide no row, so it is not among
     * the rules the report counts, and without this the note would be off the
     * screen entirely - a project with six audit rules and not a word anywhere
     * that they had stopped applying.
     */
    @Test
    void aReportSilentButForRetiredRulesStillSpeaks() {
        assertFalse(HiddenObjectsNote.speaks(Report.EMPTY, 0));
        assertTrue(HiddenObjectsNote.speaks(Report.EMPTY, 6),
                "a rule turned off is news even when nothing was hidden");

        String hint = HiddenObjectsNote.hint(Report.EMPTY, null, 6);
        assertTrue(hint.startsWith(Messages.DiffTableViewer_hidden_hint_retired_columns.formatted(6)),
                "with nothing else to say the note opens with it: " + hint);
        assertFalse(hint.contains(Messages.DiffTableViewer_hidden_nothing_hint.formatted(0)),
                "and does not first count the rules it does not have: " + hint);
    }

    /**
     * Nothing of this reaches a comparison that retired no rule, which is every
     * comparison outside the mode.
     */
    @Test
    void anOrdinaryComparisonIsToldNoneOfThis() {
        Report report = new Report(4, Map.of(DbObjType.TABLE, 4),
                List.of(new HiddenByRule("HIDE NONE taken type=TABLE", 4, List.of())));

        assertEquals(Messages.DiffTableViewer_hidden.formatted(4), HiddenObjectsNote.text(report, 0));
        assertFalse(HiddenObjectsNote.hint(report, listHidingColumns(), 0)
                .contains(Messages.DiffTableViewer_hidden_hint_retired_columns.formatted(1)),
                "a list whose column rules still apply is told where they are answered, not that they are off");
    }

    private static IgnoreList listHidingColumns() {
        IgnoreList list = new IgnoreList();
        list.add(new IgnoredObject("s_create_date", false, false, false, EnumSet.of(DbObjType.COLUMN)));
        return list;
    }
}
