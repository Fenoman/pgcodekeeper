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
package ru.taximaxim.codekeeper.ui.pgdbproject.parser;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgcodekeeper.core.api.ComparisonDepth;

import ru.taximaxim.codekeeper.ui.pgdbproject.parser.ReceiveOnlyTransferParityTestSupport.IgnoreListSnapshot;

/**
 * The receive-only preference ({@link ProjectReceiveOnlyMode}) exists to make
 * a project that only ever receives changes from a database faster to work
 * with - it loads the comparison structurally instead of fully, see {@link
 * UIComparisonLoader#loadModels(org.pgcodekeeper.core.database.api.loader.ComparisonLoaderFactories,
 * org.pgcodekeeper.core.settings.ISettings, org.pgcodekeeper.core.api.ComparisonDepth)}.
 * This class proves the other half of that claim, and the one carve-out made
 * to it.
 * <p>
 * <b>What must not depend on the mode.</b> A project that transfers
 * everything a database offers into its files, and an ignore-list rule of any
 * type other than {@code COLUMN}, must behave exactly as before the mode
 * started skipping analysis - a user whose migration ships through CI reads
 * "receive-only mode is on" as "faster", never as "different". {@link
 * #whatReachesTheProjectDoesNotDependOnTheMode} and the object-level rule
 * checked by {@link #columnRulesAreOffButObjectRulesStillHideInTheMode} prove
 * exactly that.
 * <p>
 * <b>The carve-out, for {@code type=COLUMN}.</b> Deciding whether a column a
 * rule would hide is still needed elsewhere - by a view, a foreign key, an
 * index on an expression - needs the very dependency analysis this mode skips.
 * Measured on a project carrying such a rule: trusting the rule anyway
 * silently drops a column a view still reads, byte for byte. Rather than guess
 * whether a given column is one of the safe ones, every {@code type=COLUMN}
 * rule is turned off for the mode entirely - in the tree, in the pane and in
 * the file a transfer writes - so a column such a rule would otherwise mark
 * reaches the project unmarked, whichever of the two things the rule would
 * have done to it outside the mode. {@link
 * #columnRulesAreOffButObjectRulesStillHideInTheMode} proves that this is
 * what actually happens, not only what the rule intends.
 * <p>
 * <b>Depth, not preference.</b> The carve-out answers to whether one
 * particular load actually carried dependencies, not to whether the
 * preference happens to be on. {@code ProjectEditorDiffer.reloadForScript()}
 * leaves the preference on but forces exactly one load to {@link
 * org.pgcodekeeper.core.api.ComparisonDepth#FULL} before a migration script is
 * built, because a script needs the dependencies a structural load never
 * carries - and a {@code type=COLUMN} rule must be trusted again for that one
 * load, the same as it would be with the preference off outright. Getting
 * this wrong - asking the preference instead of the depth this one load
 * reached - is exactly what would make a recomputed script disagree with an
 * ordinary one, the defect {@link
 * #columnRulesComeBackOnceTheSameComparisonIsRecomputedToFullDepth} exists to
 * catch.
 * <p>
 * <b>What is driven, and what is not.</b> {@code ProjectEditorDiffer.commit()}
 * and {@code CommitDialog} both need a live {@code Shell} to open, and SWTBot
 * cannot supply one headless in this environment. {@link
 * ReceiveOnlyTransferParityTestSupport} therefore drives the layer immediately
 * below the button instead: the exact {@link
 * org.pgcodekeeper.core.model.difftree.TreeFlattener} selection and the exact
 * {@link org.pgcodekeeper.core.api.PgCodeKeeperApi#exportToProject} call
 * {@code CommitDialog.JobProjectUpdater.run()} makes, reproduced verbatim
 * rather than reimplemented, together with the {@code
 * ProjectIgnoreLists#dropColumnRulesIfStructural} call {@code
 * ProjectEditorDiffer.setInput()} makes just before handing the ignore list
 * to the pane and the table. What stays uncovered is the dialog's own SWT
 * widgetry - which checkboxes a user left ticked, the version and
 * library-override guards {@code ProjectEditorDiffer.commit()} runs before
 * the dialog even opens - none of which writes a byte of a project file; see
 * that class's own javadoc for the full accounting.
 */
class ReceiveOnlyTransferParityTest {

    /** Where a transfer of the fixture puts {@code app.item}. */
    private static final String TRANSFERRED_TABLE = "SCHEMA/app/TABLE/item.sql";

    /**
     * Proof one: byte parity of the transfer. The same project, the same
     * source, the whole tree marked and transferred, once with the preference
     * off and once with it on - the set of files written and the bytes of
     * every one of them must be identical, because the preference is only
     * ever supposed to change how fast this runs. No {@code .pgcodekeeperignore}
     * is involved, so this proof is untouched by the carve-out for
     * {@code type=COLUMN} rules: there is no such rule here to turn off.
     * <p>
     * <b>Why each run is pinned to a file before the two are compared.</b>
     * Equality of two transfers proves they agree; it does not prove either of
     * them happened. A transfer that stopped writing anything - an empty
     * selection is all that would take - leaves both runs holding whatever the
     * project creation itself put there, and two identical answers of "nothing"
     * satisfy every comparison below without a single object having reached a
     * project. So the fixture's own table is demanded by name and by content
     * first, and only then are the two runs held against each other.
     */
    @Test
    void whatReachesTheProjectDoesNotDependOnTheMode(@TempDir Path temp)
            throws Exception {
        var monitor = new NullProgressMonitor();
        Map<String, byte[]> ordinary = ReceiveOnlyTransferParityTestSupport
                .transferEverything(temp.resolve("ordinary"), false, monitor);
        Map<String, byte[]> inMode = ReceiveOnlyTransferParityTestSupport
                .transferEverything(temp.resolve("mode"), true, monitor);

        assertTransferHappened(ordinary, "with the preference off");
        assertTransferHappened(inMode, "with the preference on");

        assertEquals(ordinary.keySet(), inMode.keySet(),
                "the same files must be written");
        ordinary.forEach((path, bytes) -> assertArrayEquals(bytes,
                inMode.get(path), "file differs: " + path));
    }

    /**
     * The fixture's own table, written where the project layout puts it and
     * carrying the columns the dump declared. One object of one kind is enough
     * to tell a transfer that ran from one that wrote nothing: the parity above
     * covers the rest of the fixture by comparing the two runs file by file,
     * but it can only do that once there is something to compare.
     */
    private static void assertTransferHappened(Map<String, byte[]> files, String run) {
        byte[] item = files.get(TRANSFERRED_TABLE);
        assertNotNull(item, () -> TRANSFERRED_TABLE + " must have been written " + run
                + "; the transfer wrote " + files.keySet());
        String sql = new String(item, StandardCharsets.UTF_8);
        assertTrue(sql.contains("CREATE TABLE app.item"),
                () -> TRANSFERRED_TABLE + " must hold the table of the fixture " + run + ": " + sql);
        assertTrue(sql.contains("secret"),
                () -> "and the columns the dump declared for it " + run + ": " + sql);
    }

    /**
     * Proof two: a {@code type=COLUMN} rule is retired whole in the mode,
     * while every rule of another type keeps doing exactly what it did
     * before.
     * <p>
     * The fixture ({@link ReceiveOnlyTransferParityTestSupport#treeWithIgnoreList})
     * carries three rules: one object-level ({@code audit_check type=TRIGGER},
     * the whole-object hiding this task's brief asked for by name) and two
     * column-level, chosen to be opposites of each other outside the mode -
     * {@code secret} is read by {@code secret_view} and so is protected
     * ({@code PINNED}, kept in the file with a mark), {@code internal_flag} is
     * read by nothing and is genuinely dropped ({@code HIDDEN}, absent from
     * the file). That contrast is what lets this test tell "the rule stopped
     * applying" from "the rule was never going to touch this column anyway":
     * a fixture with only a protected column could not.
     */
    @Test
    void columnRulesAreOffButObjectRulesStillHideInTheMode(@TempDir Path temp)
            throws Exception {
        var monitor = new NullProgressMonitor();
        IgnoreListSnapshot ordinary = ReceiveOnlyTransferParityTestSupport
                .treeWithIgnoreList(temp.resolve("ordinary"), false, monitor);
        IgnoreListSnapshot inMode = ReceiveOnlyTransferParityTestSupport
                .treeWithIgnoreList(temp.resolve("mode"), true, monitor);

        // The object-level rule is decided by the model alone and needs no
        // analysis, so the mode must keep honouring it exactly as before -
        // sanity-checked non-empty first, so an equality of two accidentally
        // empty answers could never be mistaken for this passing.
        assertFalse(ordinary.hiddenNames().isEmpty(),
                "the fixture's own object-level rule must hide something outside the mode");
        assertEquals(ordinary.hiddenNames(), inMode.hiddenNames(),
                "an object-level rule (type=TRIGGER) must hide the same objects in both modes");
        assertEquals(ordinary.hiddenCount(), inMode.hiddenCount(),
                "the counter of objects hidden by an object-level rule must agree in both modes");

        // Outside the mode both type=COLUMN rules still do exactly what they
        // always have: one column protected, kept and marked; the other
        // genuinely hidden and marked.
        assertEquals(Set.of("app.item.secret:PINNED", "app.item.internal_flag:HIDDEN"),
                ordinary.markedNames(),
                "outside the mode a type=COLUMN rule still marks its column, kept or hidden");

        // Inside the mode neither type=COLUMN rule is asked anything at all -
        // there is nothing left to mark, whether the column would have been
        // protected or genuinely hidden outside the mode.
        assertEquals(Set.of(), inMode.markedNames(),
                "in the mode a type=COLUMN rule has no effect left to mark, on either column");

        // And that is said out loud rather than left to be inferred from an
        // absence of marks. Two rules were turned off, the caller is told two,
        // and the note beside the object count is built from that number - it
        // cannot be recovered from the list afterwards, the rules are no longer
        // in it. See HiddenObjectsNoteTest for what is then written.
        assertEquals(0, ordinary.retiredColumnRules(),
                "outside the mode nothing is turned off and nothing is announced");
        assertEquals(2, inMode.retiredColumnRules(),
                "both type=COLUMN rules of the fixture are turned off, and the count says so");

        // The other half of not contradicting itself: a rule that was turned
        // off must not also be counted among the rules that could have hidden
        // something and did not, or the note would call the same rule idle and
        // retired in the same breath.
        assertEquals(0, inMode.idleRules(),
                "a retired rule is not an idle rule");
        assertEquals(ordinary.idleRules(), inMode.idleRules(),
                "and the mode does not change what is idle either");

        String itemPathOrdinary = tableFileText(ordinary, TRANSFERRED_TABLE);
        String itemPathMode = tableFileText(inMode, TRANSFERRED_TABLE);

        assertTrue(itemPathOrdinary.contains("secret"),
                "outside the mode the protected column still reaches the project file");
        assertFalse(itemPathOrdinary.contains("internal_flag"),
                "outside the mode the unprotected column is genuinely dropped from the project file");

        assertTrue(itemPathMode.contains("secret"),
                "in the mode the rule no longer applies, so the column reaches the project file");
        assertTrue(itemPathMode.contains("internal_flag"),
                "in the mode the rule no longer applies, so even the column nothing "
                        + "protects reaches the project file");
    }

    /**
     * Proof three: the carve-out follows the depth of one load, not the
     * receive-only preference that outlives it.
     * <p>
     * {@code ProjectEditorDiffer.reloadForScript()} is the scenario this
     * guards: it leaves the preference on - {@code forceFullDepthOnce} never
     * touches it, only the depth of the next load - and forces exactly that
     * one load to {@link ComparisonDepth#FULL} before a migration script is
     * built from it, because a script needs the dependencies a structural
     * load never carries. This test reproduces "preference on, this one load
     * forced to FULL regardless" through {@link
     * ReceiveOnlyTransferParityTestSupport#treeWithIgnoreList(Path, boolean,
     * ComparisonDepth, IProgressMonitor)} and asks the one question that
     * matters: does a {@code type=COLUMN} rule come back to exactly the
     * strength it has with the preference off outright, since the only
     * reason it was ever turned off - no dependencies to decide whether a
     * column is still needed - no longer holds for this one, fully analysed
     * load. A carve-out keyed to the preference instead of to this depth would
     * leave the rule off here too, and the file a script would be built
     * alongside would then differ from an ordinary one - a script built with
     * the mode on would stop being identical to one built with it off.
     */
    @Test
    void columnRulesComeBackOnceTheSameComparisonIsRecomputedToFullDepth(@TempDir Path temp)
            throws Exception {
        var monitor = new NullProgressMonitor();
        IgnoreListSnapshot ordinary = ReceiveOnlyTransferParityTestSupport
                .treeWithIgnoreList(temp.resolve("ordinary"), false, monitor);
        // Preference ON (as reloadForScript leaves it) but THIS load forced to
        // FULL (as reloadForScript forces it) - the exact combination the
        // preference-keyed carve-out could not tell apart from an ordinary
        // structural load.
        IgnoreListSnapshot recomputed = ReceiveOnlyTransferParityTestSupport
                .treeWithIgnoreList(temp.resolve("recomputed"), true, ComparisonDepth.FULL, monitor);

        assertEquals(ordinary.markedNames(), recomputed.markedNames(),
                "a type=COLUMN rule must mark exactly as it does with the preference off "
                        + "once this one load is forced back to FULL depth");

        String ordinaryItem = tableFileText(ordinary, TRANSFERRED_TABLE);
        String recomputedItem = tableFileText(recomputed, TRANSFERRED_TABLE);
        assertEquals(ordinaryItem, recomputedItem,
                "the file a script would be built alongside must match an ordinary load "
                        + "once the recompute is honest - a script must never come out "
                        + "different just because the preference happened to still be on");
    }

    private static String tableFileText(IgnoreListSnapshot snapshot, String path) {
        byte[] bytes = snapshot.files().get(path);
        assertNotNull(bytes, () -> path + " must have been written");
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
