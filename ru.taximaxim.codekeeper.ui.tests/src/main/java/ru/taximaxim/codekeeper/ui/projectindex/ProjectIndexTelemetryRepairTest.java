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
package ru.taximaxim.codekeeper.ui.projectindex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetry.Mode;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetry.RepairRefusal;

/**
 * What the published line says about the repair of a stale index.
 *
 * <p>A repair that was refused and a build that was never offered one both end
 * in the same full rebuild, and until now the line said the same thing about
 * both. Every run below is driven identically except for what it is told about
 * a repair, so a difference from {@link #WITHOUT_REPAIR} can only come from
 * that.</p>
 */
class ProjectIndexTelemetryRepairTest {

    private static final String HEAD = "pgCodeKeeper project index: mode=cold";

    private static final String TAIL =
            " persistence_status=not_attempted persistence_reason=none"
                    + " paths_enumerated=0 enumeration_passes=0"
                    + " single_file_validations=0 paths_hashed=0"
                    + " paths_hash_inline=0 paths_hash_reread=0"
                    + " paths_parsed=unknown paths_analyzed=unknown"
                    + " elapsed_ms=0";

    /** The line of a run that was never told anything about a repair. */
    private static final String WITHOUT_REPAIR = HEAD + TAIL;

    /** A token is a token: no path, no space, nothing to leak. */
    private static final Pattern TOKEN = Pattern.compile("[a-z0-9_]+");

    @Test
    void aRunNeverAskedAboutARepairSaysNothingAboutOne() {
        assertEquals(WITHOUT_REPAIR, publishedLine(run -> {
            // A run outside the repairing path entirely.
        }));
        assertFalse(WITHOUT_REPAIR.contains("repair="),
                "an absent field is what an unasked question looks like");
    }

    @Test
    void theThreeStatesEachPublishTheirOwnToken() {
        assertEquals(HEAD + " repair=not_considered" + TAIL,
                publishedLine(ProjectIndexTelemetry.Run::repairNotConsidered));
        assertEquals(HEAD + " repair=applied" + TAIL,
                publishedLine(ProjectIndexTelemetry.Run::repairApplied));
        assertEquals(HEAD + " repair=refused repair_reason=over_budget" + TAIL,
                publishedLine(run -> run.repairRefused(
                        RepairRefusal.OVER_BUDGET)));
    }

    @Test
    void onlyARefusalNamesAReason() {
        assertFalse(publishedLine(
                ProjectIndexTelemetry.Run::repairNotConsidered)
                .contains("repair_reason="),
                "a repair nobody asked for has no reason to give");
        assertFalse(publishedLine(ProjectIndexTelemetry.Run::repairApplied)
                .contains("repair_reason="),
                "and neither has one that went through");
    }

    @Test
    void everyRefusalIsItsOwnTokenAndNamesNoPath() {
        var tokens = new HashSet<String>();
        for (RepairRefusal reason : RepairRefusal.values()) {
            String line = publishedLine(run -> run.repairRefused(reason));
            String token = reasonToken(line);
            assertTrue(TOKEN.matcher(token).matches(),
                    reason + " published " + token
                            + ", which is not a bare token");
            assertTrue(tokens.add(token),
                    reason + " published " + token
                            + ", which another refusal already publishes");
        }
        assertEquals(RepairRefusal.values().length, tokens.size());
    }

    @Test
    void aRebuildDoesNotOverwriteTheRefusalItFellBackTo() {
        // What a refused repair does next: it rebuilds, and the rebuild is the
        // one path that reports itself as never having considered a repair.
        assertEquals(HEAD + " repair=refused repair_reason=removed_files"
                + TAIL,
                publishedLine(run -> {
                    run.repairRefused(RepairRefusal.REMOVED_FILES);
                    run.repairNotConsidered();
                }));
    }

    @Test
    void anIncrementalBuildThatFallsBackRefusedNoRepair() {
        // An ordinary incremental batch gives up through the same fallback,
        // and it turned nothing down: nothing asked it to repair anything.
        // What it did give up on is the batch, and that it names.
        assertEquals(HEAD + " repair=not_considered"
                + " batch_abandoned=unsafe_replacements" + TAIL,
                publishedLine(run -> {
                    run.repairBatchAbandoned(
                            RepairRefusal.UNSAFE_REPLACEMENTS);
                    run.repairNotConsidered();
                }));
    }

    /**
     * The reason a batch was given up on belongs to the batch, not to a repair,
     * and a build that came by the ordinary incremental path has one to report
     * just as a repairing one does. Publishing it only where a repair had been
     * applied lost it exactly where the line was hardest to read: a rebuild
     * nothing had asked for, and no reason for it anywhere.
     */
    @Test
    void theReasonABatchWasGivenUpOnIsPublishedWithoutARepair() {
        assertEquals(HEAD + " batch_abandoned=preflight_inputs_stale" + TAIL,
                publishedLine(run -> run.repairBatchAbandoned(
                        RepairRefusal.PREFLIGHT_INPUTS_STALE)),
                "on its own the abandoned batch is the whole of what it says");
        assertFalse(WITHOUT_REPAIR.contains("batch_abandoned="),
                "a run that abandoned no batch names none");
        assertFalse(publishedLine(ProjectIndexTelemetry.Run::repairApplied)
                .contains("batch_abandoned="),
                "and neither does a batch that went through");
        assertFalse(publishedLine(run ->
                run.repairRefused(RepairRefusal.OVER_BUDGET))
                .contains("batch_abandoned="),
                "a repair turned down before it started abandoned no batch");
    }

    @Test
    void anAbandonedBatchDowngradesTheRepairItWasApplying() {
        assertEquals(HEAD
                + " repair=refused repair_reason=unsafe_replacements"
                + " batch_abandoned=unsafe_replacements" + TAIL,
                publishedLine(run -> {
                    run.repairApplied();
                    run.repairBatchAbandoned(
                            RepairRefusal.UNSAFE_REPLACEMENTS);
                }));
    }

    @Test
    void aRefusalAlreadyTakenIsNotRestatedByALaterBatch() {
        // The two fields answer two questions, so the later batch neither
        // overwrites the refusal already taken nor loses what stopped it.
        assertEquals(HEAD + " repair=refused repair_reason=over_budget"
                + " batch_abandoned=unsafe_replacements" + TAIL,
                publishedLine(run -> {
                    run.repairRefused(RepairRefusal.OVER_BUDGET);
                    run.repairBatchAbandoned(
                            RepairRefusal.UNSAFE_REPLACEMENTS);
                }));
    }

    /**
     * A fallback rebuild derives no second batch, so a run has one batch to
     * give up on. Should a later one ever be reported, the first is what
     * describes the build that was abandoned.
     */
    @Test
    void theBatchThatWasGivenUpOnFirstIsTheOneNamed() {
        assertEquals(HEAD + " batch_abandoned=batch_file_unsafe" + TAIL,
                publishedLine(run -> {
                    run.repairBatchAbandoned(RepairRefusal.BATCH_FILE_UNSAFE);
                    run.repairBatchAbandoned(
                            RepairRefusal.UNSAFE_REPLACEMENTS);
                }));
    }

    @Test
    void aRepairOutcomeOutlivesEveryBranchTheRunDeclaresAfterIt() {
        // The fallback declares a mode and a bypass after it has recorded the
        // refusal, and neither may take the refusal back.
        assertTrue(publishedLine(run -> {
            run.repairRefused(RepairRefusal.BATCH_FILE_UNSAFE);
            run.mode(Mode.COLD);
        }).contains(" repair=refused repair_reason=batch_file_unsafe "));
        assertTrue(publishedLine(run -> {
            run.repairApplied();
            run.mode(Mode.INCREMENTAL);
        }).contains(" repair=applied "));
    }

    private static String reasonToken(String line) {
        int start = line.indexOf(" repair_reason=");
        assertTrue(start >= 0, "no reason in: " + line);
        start += " repair_reason=".length();
        int end = line.indexOf(' ', start);
        return end < 0 ? line.substring(start) : line.substring(start, end);
    }

    /**
     * Publishes one run, driven identically every time except for what it is
     * told about a repair.
     *
     * @param repair what this run learns about a repair
     * @return the single line the run published
     */
    private static String publishedLine(
            Consumer<ProjectIndexTelemetry.Run> repair) {
        List<String> lines = new ArrayList<>();
        var run = new ProjectIndexTelemetry(lines::add, () -> 0)
                .start(Mode.COLD);

        repair.accept(run);
        run.close();

        assertEquals(1, lines.size());
        return lines.getFirst();
    }
}
