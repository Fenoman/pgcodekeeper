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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.ignorelist.IgnoreList;
import org.pgcodekeeper.core.ignorelist.IgnoredObject;
import org.pgcodekeeper.core.model.difftree.HiddenObjects.HiddenByRule;
import org.pgcodekeeper.core.model.difftree.HiddenObjects.Report;

import ru.taximaxim.codekeeper.ui.UIConsts;
import ru.taximaxim.codekeeper.ui.localizations.Messages;

/**
 * Tells a reader of the object table how much of the comparison the ignore rules
 * are holding back, in the few words that fit beside the object count.
 * <p>
 * <b>Why here and not in the pane.</b> Everything else a rule does now shows
 * where it happens: a column a rule names stays on the screen and wears a mark,
 * and the legend above the pane explains the mark, see {@code PaneMarkLegend}.
 * One kind of hiding cannot be shown that way, and it is the widest one - a
 * table, a function, a schema hidden whole never becomes a row, so there is no
 * row to mark. The only place it can be spoken about is beside the count of the
 * rows that are there, which is why this stands next to "Objects" and is worded
 * against it: one says what the comparison holds, the other what it was not
 * allowed to hold.
 * <p>
 * <b>Why zero is still said out loud.</b> The whole complaint this answers is
 * that a rule which stopped matching looks exactly like a rule that is working:
 * both leave silence. So the note is shown whenever the comparison had a rule
 * that could hide an object, and at nothing hidden it says so in a word rather
 * than in a digit - "nothing" is read as an event, "0" is read as an absence.
 * The note disappears completely only for a comparison whose list could hide
 * nothing at all, where there is genuinely nothing to say. A rule this
 * comparison turned off keeps the note on the screen for the same reason and a
 * stronger one: a rule that took nothing might still be working, while a rule
 * that was never asked certainly is not, and unlike a mistyped pattern it is
 * not the reader's doing and cannot be found by re-reading their own file.
 * <p>
 * <b>Why the count alone is not the answer.</b> A number says the rules are
 * alive; it does not say they are alive on the right objects. What answers that
 * is which rule took how much, and a name or two of what it took - and unlike a
 * list of everything hidden, which is twenty thousand lines on the project this
 * was built for, that is bounded by the rules somebody wrote and reads in a
 * glance. The rules that took nothing are counted rather than named for the same
 * reason: on a real list most of them will have nothing to take today, and a
 * hundred and forty names would bury the eight that matter.
 *
 * @see org.pgcodekeeper.core.model.difftree.HiddenObjects where the number comes
 * from and what it counts
 */
final class HiddenObjectsNote {

    /** How many rules are named before the rest are only counted. */
    private static final int RULES_NAMED = 8;

    private static final String INDENT = "\t"; //$NON-NLS-1$

    private HiddenObjectsNote() {
    }

    /** Between the count and what is said about the rules turned off beside it. */
    private static final String SEPARATOR = "; "; //$NON-NLS-1$

    /**
     * Reports whether this comparison has anything to say about hiding at all.
     *
     * @param report              what the rules of the comparison took
     * @param retiredColumnRules  how many {@code type=COLUMN} rules this
     *                            comparison turned off, see {@code
     *                            ProjectIgnoreLists#dropColumnRulesIfStructural}
     * @return true when the note belongs on the screen, which includes a
     * comparison whose rules took nothing, and one whose only rules were turned
     * off before they could take anything
     */
    static boolean speaks(Report report, int retiredColumnRules) {
        return !report.isSilent() || retiredColumnRules > 0;
    }

    /**
     * The words that stand beside the object count.
     * <p>
     * A rule turned off is said here rather than only in the hint, and for the
     * same reason the count itself is: a hint is read by somebody who already
     * suspects something, and the reader this is for suspects nothing - they
     * turned the mode on, their column rules stopped applying, and the first
     * they would otherwise learn of it is a project file carrying columns they
     * spent a rule to keep out of it.
     *
     * @param report             what the rules of the comparison took
     * @param retiredColumnRules how many {@code type=COLUMN} rules were turned
     *                           off for it
     * @return the whole of what is read without pointing at anything
     */
    static String text(Report report, int retiredColumnRules) {
        String taken = report.total() == 0 ? Messages.DiffTableViewer_hidden_nothing
                : Messages.DiffTableViewer_hidden.formatted(report.total());
        if (retiredColumnRules == 0) {
            return taken;
        }
        return taken + SEPARATOR + Messages.DiffTableViewer_retired_columns.formatted(retiredColumnRules);
    }

    /**
     * What the words leave out: which kinds of object went, which rules took
     * them, an example or two of what each rule took, and why a rule that took
     * nothing was never asked.
     *
     * @param report             what the rules of the comparison took
     * @param ignoreList         the rules of the comparison, so that a list
     *                           which also names columns can say where those
     *                           are answered instead; may be {@code null}
     * @param retiredColumnRules how many {@code type=COLUMN} rules were turned
     *                           off for this comparison
     * @return the hint of the note
     */
    static String hint(Report report, IgnoreList ignoreList, int retiredColumnRules) {
        StringBuilder sb = new StringBuilder();
        if (!report.isSilent()) {
            if (report.total() == 0) {
                sb.append(Messages.DiffTableViewer_hidden_nothing_hint.formatted(report.rules().size()));
            } else {
                sb.append(Messages.DiffTableViewer_hidden_hint.formatted(report.total()));
                sb.append(UIConsts._NL).append(byType(report.byType()));
                appendRules(sb, report);
            }
            appendIdleRules(sb, report);
        }
        appendColumnRules(sb, ignoreList, retiredColumnRules);
        return sb.toString();
    }

    /**
     * The kinds of object that went, the most taken first, which is the line
     * that answers "did the rules take what they were written for" before any
     * rule is read at all.
     */
    private static String byType(Map<DbObjType, Integer> byType) {
        List<Map.Entry<DbObjType, Integer>> types = new ArrayList<>(byType.entrySet());
        Comparator<Map.Entry<DbObjType, Integer>> mostFirst =
                Comparator.comparingInt(Map.Entry::getValue);
        types.sort(mostFirst.reversed().thenComparing(Map.Entry::getKey));

        StringBuilder sb = new StringBuilder();
        for (var type : types) {
            if (!sb.isEmpty()) {
                sb.append(", "); //$NON-NLS-1$
            }
            sb.append(type.getKey()).append(' ').append(type.getValue());
        }
        return sb.toString();
    }

    private static void appendRules(StringBuilder sb, Report report) {
        List<HiddenByRule> firing = report.firingRules();
        int named = Math.min(firing.size(), RULES_NAMED);
        for (int i = 0; i < named; i++) {
            HiddenByRule rule = firing.get(i);
            sb.append(UIConsts._NL).append(UIConsts._NL);
            sb.append(Messages.DiffTableViewer_hidden_hint_rule.formatted(
                    rule.rule() == null ? Messages.DiffTableViewer_hidden_hint_no_rule : rule.rule(),
                    rule.hidden()));
            for (String example : rule.examples()) {
                sb.append(UIConsts._NL).append(INDENT).append(example);
            }
        }
        if (firing.size() > named) {
            sb.append(UIConsts._NL).append(UIConsts._NL);
            sb.append(Messages.DiffTableViewer_hidden_hint_more_rules.formatted(firing.size() - named));
        }
    }

    private static void appendIdleRules(StringBuilder sb, Report report) {
        int idle = report.idleRules();
        if (idle > 0 && report.total() > 0) {
            // at nothing hidden every rule is idle and the paragraph above has
            // already said so in more words than a tally could
            paragraph(sb).append(Messages.DiffTableViewer_hidden_hint_idle.formatted(idle));
        }
    }

    /**
     * Says what became of the {@code type=COLUMN} rules of this list, in one of
     * the two ways that can be true of them.
     * <p>
     * Ordinarily such a rule is answered elsewhere - it can hide no row of this
     * table, a column is not a row of it, so it is not in the count and a
     * reader who wrote one is pointed at the marks beside the columns instead
     * of left to conclude that it stopped working.
     * <p>
     * A comparison loaded without analysis cannot answer it anywhere: deciding
     * whether a column a rule names is still read by a view, a foreign key or
     * an expression needs the dependencies that load never resolved, so the
     * rules are turned off outright, see {@code
     * ProjectIgnoreLists#dropColumnRulesIfStructural}. Then the two statements
     * are mutually exclusive and only this one is true: nothing is marked
     * because nothing was asked, and the columns reach the project's files. The
     * count arrives from the caller rather than from the list, because by the
     * time the list is read here those rules are no longer in it - which is
     * precisely why saying nothing would leave a reader with no way to find
     * out.
     */
    private static void appendColumnRules(StringBuilder sb, IgnoreList ignoreList, int retiredColumnRules) {
        if (retiredColumnRules > 0) {
            paragraph(sb).append(
                    Messages.DiffTableViewer_hidden_hint_retired_columns.formatted(retiredColumnRules));
            return;
        }
        if (ignoreList == null) {
            return;
        }
        for (IgnoredObject rule : ignoreList.getList()) {
            if (!rule.isShow() && rule.getObjTypes().contains(DbObjType.COLUMN)) {
                paragraph(sb).append(Messages.DiffTableViewer_hidden_hint_columns);
                return;
            }
        }
    }

    /** Opens the next paragraph, or the first one where nothing was said yet. */
    private static StringBuilder paragraph(StringBuilder sb) {
        if (!sb.isEmpty()) {
            sb.append(UIConsts._NL).append(UIConsts._NL);
        }
        return sb;
    }
}
