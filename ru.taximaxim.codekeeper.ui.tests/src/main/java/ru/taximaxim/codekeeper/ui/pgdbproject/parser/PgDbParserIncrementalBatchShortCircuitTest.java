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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;

import ru.taximaxim.codekeeper.ui.pgdbproject.parser.PgDbParser.IncrementalBatchFileLoader;
import ru.taximaxim.codekeeper.ui.pgdbproject.parser.PgDbParser.LoadedBatchFile;
import ru.taximaxim.codekeeper.ui.projectindex.FileContribution;
import ru.taximaxim.codekeeper.ui.projectindex.IndexPathOrigin;
import ru.taximaxim.codekeeper.ui.projectindex.IndexPathRef;
import ru.taximaxim.codekeeper.ui.projectindex.PackedDefinition;
import ru.taximaxim.codekeeper.ui.projectindex.PackedDefinition.MetaKind;
import ru.taximaxim.codekeeper.ui.projectindex.PackedLocation;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectFileStamp;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexDelta;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexIncrementalPlanner;

/**
 * Proves that an incremental batch stops at the first file that lost the right
 * to replace its indexed contribution, and that stopping there answers exactly
 * what the whole-batch check used to answer once every file had been analysed.
 */
class PgDbParserIncrementalBatchShortCircuitTest {

    private static final int BATCH_SIZE = 32;

    /** What the analysis of one batch file would produce. */
    private record BatchFile(ProjectFileStamp stamp,
            FileContribution contribution, boolean analysisReusable,
            boolean blockedOnlyByErrors) {
    }

    /** How a batch file diverged from the contribution the index holds. */
    private enum Divergence {
        /** The definitions kept their shape, only their order changed. */
        NONE,
        /** A definition changed its signature. */
        SHAPE,
        /** The analysis itself may not be reused. */
        ANALYSIS,
        /**
         * The analysis reported an error and nothing else moved: the file
         * still carries the definitions the index holds for it.
         */
        ERRORS,
        /** The file was analysed into a contribution of another path. */
        PATH
    }

    /**
     * Verdict of one batch run and the work it took.
     *
     * @param changes  the collected replacements, or null when the batch fell
     *                 back to a full rebuild
     * @param accepted whether the batch may be published
     * @param errors   whether errors alone turned the batch down
     * @param loaded   files the batch was answerable for
     * @param parsed   files the batch parsed
     * @param analysed files the batch analysed
     * @param reported files the batch reported as loaded
     */
    private record BatchRun(List<ProjectIndexDelta.Change> changes,
            boolean accepted, boolean errors, int loaded, int parsed,
            int analysed, int reported) {
    }

    @Test
    void stopsParsingAtTheFirstFileThatChangedShape() throws Exception {
        for (int diverging = 0; diverging < BATCH_SIZE; diverging++) {
            List<Divergence> plan = uniform(BATCH_SIZE, Divergence.NONE);
            plan.set(diverging, Divergence.SHAPE);
            BatchRun run = collect(plan);

            assertFalse(run.accepted());
            assertNull(run.changes());
            assertEquals(diverging + 1, run.parsed());
            assertEquals(diverging, run.analysed());
            assertEquals(diverging, run.reported());
        }
    }

    /**
     * The loop owns the check as well, so a divergence that somehow survived
     * until the analysis still stops the batch at that very file instead of
     * at the end of it.
     */
    @Test
    void stopsAtTheFirstDivergentFileThatWasAnalysedAnyway() throws Exception {
        List<IndexPathRef> paths = paths(BATCH_SIZE);
        Map<IndexPathRef, List<PackedDefinition>> previous =
                previousDefinitions(paths);
        List<Divergence> plan = uniform(BATCH_SIZE, Divergence.NONE);
        plan.set(6, Divergence.SHAPE);
        List<BatchFile> files = batchFiles(paths, plan);
        var analysed = new AtomicInteger();
        var loadedFiles = new AtomicLong();
        IncrementalBatchFileLoader loader = (index, path) -> {
            analysed.incrementAndGet();
            BatchFile file = files.get(index);
            return new LoadedBatchFile(file.stamp(), file.contribution(),
                    file.analysisReusable(), file.blockedOnlyByErrors());
        };

        PgDbParser.IncrementalBatch batch =
                PgDbParser.collectIncrementalReplacements(paths, previous,
                        Set.of(), loader, loadedFiles::addAndGet);

        assertNull(batch.changes());
        assertFalse(batch.analysisErrors());
        assertEquals(7, analysed.get());
        assertEquals(6, loadedFiles.get());
    }

    /**
     * The refusal this whole split exists for. A file that did not parse still
     * carries every definition the index holds for it, so the index has
     * nothing to rebuild - and the batch says so, instead of handing back the
     * one null that used to mean four different things.
     */
    @Test
    void aFileThatOnlyFailedToParseAsksForNoRebuild() throws Exception {
        for (int failing = 0; failing < BATCH_SIZE; failing++) {
            List<Divergence> plan = uniform(BATCH_SIZE, Divergence.NONE);
            plan.set(failing, Divergence.ERRORS);
            BatchRun run = collect(plan);

            assertFalse(run.accepted());
            assertNull(run.changes());
            assertTrue(run.errors(), "the batch did not name the errors");
            assertEquals(failing + 1, run.loaded());
            assertEquals(failing + 1, run.parsed());
            assertEquals(failing, run.reported());
        }
    }

    /**
     * The one that must not be mistaken for it. A definition that moved is
     * visible to every other file of the project, so the index really does
     * have to be rebuilt - and a parse error on the very same file does not
     * buy it a reprieve.
     */
    @Test
    void aFileThatMovedADefinitionAsksForARebuildEvenWhenItAlsoFailedToParse()
            throws Exception {
        List<IndexPathRef> paths = paths(4);
        Map<IndexPathRef, List<PackedDefinition>> previous =
                previousDefinitions(paths);
        List<BatchFile> files = new ArrayList<>(
                batchFiles(paths, uniform(4, Divergence.NONE)));
        BatchFile moved = batchFile(paths.get(2), Divergence.SHAPE);
        // A file claiming both at once, handed straight to the loop so that
        // the gate cannot turn it down first: the loop has to prefer the
        // refusal that costs a rebuild.
        files.set(2, new BatchFile(moved.stamp(), moved.contribution(),
                false, true));
        var loadedFiles = new AtomicLong();
        IncrementalBatchFileLoader loader = (index, path) -> {
            BatchFile file = files.get(index);
            return new LoadedBatchFile(file.stamp(), file.contribution(),
                    file.analysisReusable(), file.blockedOnlyByErrors());
        };

        PgDbParser.IncrementalBatch batch =
                PgDbParser.collectIncrementalReplacements(paths, previous,
                        Set.of(), loader, loadedFiles::addAndGet);

        assertNull(batch.changes());
        assertFalse(batch.analysisErrors(),
                "a moved definition was excused as a parse error");
        assertEquals(3, batch.loadedFiles());
    }

    @Test
    void stopsParsingAtTheFirstUnusableAnalysis() throws Exception {
        List<Divergence> plan = uniform(BATCH_SIZE, Divergence.NONE);
        plan.set(4, Divergence.ANALYSIS);
        BatchRun run = collect(plan);

        assertFalse(run.accepted());
        assertEquals(5, run.parsed());
        assertEquals(5, run.analysed());
        assertEquals(4, run.reported());
    }

    @Test
    void loadsEveryFileOfAShapePreservingBatch() throws Exception {
        BatchRun run = collect(uniform(BATCH_SIZE, Divergence.NONE));

        assertTrue(run.accepted());
        assertNotNull(run.changes());
        assertEquals(BATCH_SIZE, run.changes().size());
        assertEquals(BATCH_SIZE, run.parsed());
        assertEquals(BATCH_SIZE, run.analysed());
        assertEquals(BATCH_SIZE, run.reported());
    }

    /**
     * The batch answer must not depend on when the divergence is noticed, so
     * every combination of divergences of a four-file batch is answered by
     * both the loop that stops early and the loop that analysed the whole
     * batch before asking.
     */
    @Test
    void everyBatchIsAnsweredExactlyAsTheWholeBatchOracleAnsweredIt()
            throws Exception {
        Divergence[] alphabet = Divergence.values();
        int combinations = (int) Math.pow(alphabet.length, 4);
        int rejected = 0;
        for (int combination = 0; combination < combinations; combination++) {
            List<Divergence> plan = new ArrayList<>(4);
            int remaining = combination;
            for (int file = 0; file < 4; file++) {
                plan.add(alphabet[remaining % alphabet.length]);
                remaining /= alphabet.length;
            }
            BatchRun early = collect(plan);
            BatchRun whole = collectWholeBatchOracle(plan);

            assertEquals(whole.accepted(), early.accepted(), plan::toString);
            assertEquals(whole.changes(), early.changes(), plan::toString);
            assertTrue(early.parsed() <= whole.parsed(), plan::toString);
            assertTrue(early.analysed() <= whole.analysed(), plan::toString);
            // Only files that really joined the batch are reported as loaded.
            assertEquals(early.accepted() ? early.parsed()
                    : early.parsed() - 1, early.reported(), plan::toString);
            if (!early.accepted()) {
                rejected++;
            }
        }
        assertEquals(combinations - 1, rejected);
    }

    /**
     * A batch file is checked against the definitions the index holds for the
     * path the file was analysed into, exactly like the whole-batch check
     * files a change under the path of its contribution. A batch whose files
     * swapped contributions therefore stays acceptable.
     */
    @Test
    void answersASwappedBatchExactlyAsTheWholeBatchOracleAnswersIt()
            throws Exception {
        List<IndexPathRef> paths = paths(2);
        Map<IndexPathRef, List<PackedDefinition>> previous =
                previousDefinitions(paths);
        List<BatchFile> files = List.of(
                batchFile(paths.get(1), Divergence.NONE),
                batchFile(paths.get(0), Divergence.NONE));

        BatchRun early = collect(paths, previous, files);
        BatchRun whole = collectWholeBatchOracle(paths, previous, files);

        assertTrue(whole.accepted());
        assertEquals(whole.accepted(), early.accepted());
        assertEquals(whole.changes(), early.changes());
        assertEquals(2, early.parsed());
    }

    private BatchRun collect(List<Divergence> plan) throws Exception {
        List<IndexPathRef> paths = paths(plan.size());
        Map<IndexPathRef, List<PackedDefinition>> previous =
                previousDefinitions(paths);
        return collect(paths, previous, batchFiles(paths, plan));
    }

    private BatchRun collectWholeBatchOracle(List<Divergence> plan)
            throws Exception {
        List<IndexPathRef> paths = paths(plan.size());
        Map<IndexPathRef, List<PackedDefinition>> previous =
                previousDefinitions(paths);
        return collectWholeBatchOracle(paths, previous,
                batchFiles(paths, plan));
    }

    /** Runs the production loop, which stops at the first divergent file. */
    private BatchRun collect(List<IndexPathRef> paths,
            Map<IndexPathRef, List<PackedDefinition>> previous,
            List<BatchFile> files) throws Exception {
        var parsed = new AtomicInteger();
        var analysed = new AtomicInteger();
        var loadedFiles = new AtomicLong();
        IncrementalBatchFileLoader loader = (index, path) -> {
            parsed.incrementAndGet();
            BatchFile file = files.get(index);
            // The production loader turns a file down before analysing it
            // when the definitions its parse produced changed shape.
            if (!ProjectIndexIncrementalPlanner.isSafeReplacement(
                    previous, file.contribution())) {
                return null;
            }
            analysed.incrementAndGet();
            return new LoadedBatchFile(file.stamp(), file.contribution(),
                    file.analysisReusable(), file.blockedOnlyByErrors());
        };
        PgDbParser.IncrementalBatch batch =
                PgDbParser.collectIncrementalReplacements(paths, previous,
                        Set.of(), loader, loadedFiles::addAndGet);
        List<ProjectIndexDelta.Change> changes = batch.changes();
        boolean accepted = changes != null
                && ProjectIndexIncrementalPlanner.areSafeReplacements(
                        previous, new ProjectIndexDelta(changes));
        return new BatchRun(accepted ? changes : null, accepted,
                batch.analysisErrors(), batch.loadedFiles(),
                parsed.get(), analysed.get(), (int) loadedFiles.get());
    }

    /**
     * Runs the loop as it stood before the shape check moved into it: every
     * file of the batch is analysed, and only the assembled batch is asked
     * whether it preserved every definition's shape.
     */
    private BatchRun collectWholeBatchOracle(List<IndexPathRef> paths,
            Map<IndexPathRef, List<PackedDefinition>> previous,
            List<BatchFile> files) {
        var changes = new ArrayList<ProjectIndexDelta.Change>(paths.size());
        int parsed = 0;
        int analysed = 0;
        for (int index = 0; index < paths.size(); index++) {
            BatchFile file = files.get(index);
            parsed++;
            analysed++;
            if (!file.analysisReusable()) {
                return new BatchRun(null, false, false, parsed, parsed,
                        analysed, changes.size());
            }
            changes.add(ProjectIndexDelta.Change.replace(
                    file.stamp(), file.contribution()));
        }
        boolean accepted = ProjectIndexIncrementalPlanner.areSafeReplacements(
                previous, new ProjectIndexDelta(changes));
        return new BatchRun(accepted ? changes : null, accepted, false,
                parsed, parsed, analysed, changes.size());
    }

    private static List<Divergence> uniform(int size, Divergence value) {
        var plan = new ArrayList<Divergence>(size);
        for (int index = 0; index < size; index++) {
            plan.add(value);
        }
        return plan;
    }

    private static List<IndexPathRef> paths(int size) {
        var paths = new ArrayList<IndexPathRef>(size);
        for (int index = 0; index < size; index++) {
            paths.add(new IndexPathRef(IndexPathOrigin.PROJECT,
                    "SCHEMA/app/FUNCTION/pick_" + index + ".sql"));
        }
        return List.copyOf(paths);
    }

    private static Map<IndexPathRef, List<PackedDefinition>>
            previousDefinitions(List<IndexPathRef> paths) {
        var previous = new LinkedHashMap<IndexPathRef,
                List<PackedDefinition>>();
        for (IndexPathRef path : paths) {
            previous.put(path, definitions(path, "integer"));
        }
        return previous;
    }

    private static List<BatchFile> batchFiles(List<IndexPathRef> paths,
            List<Divergence> plan) {
        var files = new ArrayList<BatchFile>(plan.size());
        for (int index = 0; index < plan.size(); index++) {
            files.add(batchFile(paths.get(index), plan.get(index)));
        }
        return files;
    }

    private static BatchFile batchFile(IndexPathRef path,
            Divergence divergence) {
        IndexPathRef contributionPath = divergence == Divergence.PATH
                ? new IndexPathRef(path.origin(),
                        path.relativePath() + ".moved.sql")
                : path;
        String returns = divergence == Divergence.SHAPE ? "jsonb" : "integer";
        // A body-only edit reorders the definitions of a file without
        // changing any of their shapes, which the index accepts.
        List<PackedDefinition> definitions =
                definitions(contributionPath, returns).reversed();
        return new BatchFile(stamp(contributionPath),
                new FileContribution(contributionPath, definitions,
                        List.of(), Set.of(), true),
                divergence != Divergence.ANALYSIS
                        && divergence != Divergence.ERRORS,
                divergence == Divergence.ERRORS);
    }

    private static List<PackedDefinition> definitions(IndexPathRef path,
            String returns) {
        return List.of(definition(path, "pick_first", returns, 10),
                definition(path, "pick_second", "text", 60));
    }

    private static PackedDefinition definition(IndexPathRef path,
            String name, String returns, int offset) {
        var object = new PackedLocation(path.origin(), path.relativePath(),
                offset, offset / 10, 0, 40,
                new ObjectReference("app", name + "(integer)",
                        DbObjType.FUNCTION),
                "definition", null,
                ObjectLocation.LocationType.DEFINITION, null);
        return new PackedDefinition(MetaKind.FUNCTION, object, name, "",
                List.of(), List.of(), List.of(), returns, false,
                List.of(), false, List.of(), false, List.of(),
                null, null, null, null, null, null);
    }

    private static ProjectFileStamp stamp(IndexPathRef path) {
        return new ProjectFileStamp(path, 7L, 128L, 11L, new byte[32]);
    }
}
