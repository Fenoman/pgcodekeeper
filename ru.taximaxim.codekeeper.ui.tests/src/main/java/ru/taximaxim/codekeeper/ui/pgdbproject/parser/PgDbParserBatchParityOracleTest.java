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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ProjectScope;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Platform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgcodekeeper.core.DangerStatement;
import org.pgcodekeeper.core.database.api.schema.ArgMode;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IArgument;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.base.schema.meta.MetaFunction;
import org.pgcodekeeper.core.database.base.schema.meta.MetaRelation;
import org.pgcodekeeper.core.database.base.schema.meta.MetaStatement;

import ru.taximaxim.codekeeper.ui.Activator;
import ru.taximaxim.codekeeper.ui.UIConsts;
import ru.taximaxim.codekeeper.ui.builders.ProjectBuildContinuity.BuildContinuityProof;
import ru.taximaxim.codekeeper.ui.builders.ProjectBuildContinuityTestSupport;
import ru.taximaxim.codekeeper.ui.builders.ProjectBuildContinuityTestSupport.Scenario;
import ru.taximaxim.codekeeper.ui.libraries.LibraryUtils;
import ru.taximaxim.codekeeper.ui.projectindex.FileContribution;
import ru.taximaxim.codekeeper.ui.projectindex.IndexPathOrigin;
import ru.taximaxim.codekeeper.ui.projectindex.IndexPathRef;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectFileStamp;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexDefinitionSubject;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexIdentity;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexOpenResult.Status;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexRevision;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexState;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexStore;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetry.Mode;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetryTestSupport;
import ru.taximaxim.codekeeper.ui.projectindex.ReferenceMatchKey;
import ru.taximaxim.codekeeper.ui.projectindex.ReferenceMatchKey.MatchFamily;

class PgDbParserBatchParityOracleTest {

    private static final Comparator<String>
            NULLABLE_TEXT_ORDER =
                    Comparator.nullsFirst(
                            Comparator.naturalOrder());
    private static final Comparator<ReferenceDto>
            REFERENCE_ORDER = Comparator
                    .comparing(ReferenceDto::schema,
                            NULLABLE_TEXT_ORDER)
                    .thenComparing(ReferenceDto::table,
                            NULLABLE_TEXT_ORDER)
                    .thenComparing(ReferenceDto::column,
                            NULLABLE_TEXT_ORDER)
                    .thenComparing(ReferenceDto::type,
                            Comparator.nullsFirst(
                                    Comparator.naturalOrder()));
    private static final Comparator<LocationDto>
            LOCATION_ORDER = Comparator
                    .comparing(LocationDto::origin)
                    .thenComparing(LocationDto::path)
                    .thenComparingInt(LocationDto::offset)
                    .thenComparingInt(LocationDto::lineNumber)
                    .thenComparingInt(
                            LocationDto::charPositionInLine)
                    .thenComparingInt(LocationDto::length)
                    .thenComparing(LocationDto::reference,
                            Comparator.nullsFirst(
                                    REFERENCE_ORDER))
                    .thenComparing(LocationDto::action,
                            NULLABLE_TEXT_ORDER)
                    .thenComparing(LocationDto::alias,
                            NULLABLE_TEXT_ORDER)
                    .thenComparing(LocationDto::locationType)
                    .thenComparing(LocationDto::danger,
                            Comparator.nullsFirst(
                                    Comparator.naturalOrder()));
    private static final Comparator<DefinitionDto>
            DEFINITION_ORDER = Comparator.comparing(
                    DefinitionDto::object, LOCATION_ORDER);
    private static final Comparator<MatchDto>
            MATCH_ORDER = Comparator
                    .comparing((MatchDto match) ->
                            match.family)
                    .thenComparing(MatchDto::exactType,
                            Comparator.nullsFirst(
                                    Comparator.naturalOrder()))
                    .thenComparing(MatchDto::schema,
                            NULLABLE_TEXT_ORDER)
                    .thenComparing(MatchDto::table,
                            NULLABLE_TEXT_ORDER)
                    .thenComparing(MatchDto::column,
                            NULLABLE_TEXT_ORDER)
                    .thenComparing(MatchDto::alias,
                            NULLABLE_TEXT_ORDER)
                    .thenComparing(MatchDto::global);
    private static final List<Integer> BATCH_SIZES =
            List.of(2, 5, 10, 16);
    private static final List<Integer> ADDITION_EDIT_COUNTS =
            List.of(0, 1, 5);
    private static final String SCHEMA = "app"; //$NON-NLS-1$
    private static final String OLD_TABLE = "parity_old"; //$NON-NLS-1$
    private static final String NEW_TABLE = "parity_new"; //$NON-NLS-1$
    private static final String UNTOUCHED_PATH =
            "SCHEMA/app/FUNCTION/parity_untouched.sql"; //$NON-NLS-1$
    private static final String ADDED_TABLE =
            "parity_added"; //$NON-NLS-1$
    private static final String ADDED_VIEW =
            "parity_added_view"; //$NON-NLS-1$
    private static final String ADDED_FUNCTION =
            "parity_added_fn"; //$NON-NLS-1$
    private static final String ADDED_TABLE_PATH =
            "SCHEMA/app/TABLE/" + ADDED_TABLE + ".sql"; //$NON-NLS-1$ //$NON-NLS-2$
    private static final String ADDED_VIEW_PATH =
            "SCHEMA/app/VIEW/" + ADDED_VIEW + ".sql"; //$NON-NLS-1$ //$NON-NLS-2$
    private static final String ADDED_FUNCTION_PATH =
            "SCHEMA/app/FUNCTION/" + ADDED_FUNCTION //$NON-NLS-1$
                    + ".sql"; //$NON-NLS-1$
    private static final List<String> ADDED_PATHS = List.of(
            ADDED_FUNCTION_PATH, ADDED_TABLE_PATH, ADDED_VIEW_PATH);
    /**
     * A sequence answers to the very match-key family a table answers to, so
     * this file claims the subject the indexed {@link #OLD_TABLE} already owns
     * without being a duplicate object: a second file declaring the table again
     * would make the whole project unloadable, and the refusal being measured
     * has to degrade to a full build that still works.
     */
    private static final String COLLIDING_SEQUENCE_PATH =
            "SCHEMA/app/SEQUENCE/" + OLD_TABLE + ".sql"; //$NON-NLS-1$ //$NON-NLS-2$
    /**
     * Every path a case may introduce. The project starts without them, and
     * {@link #allPaths()} counts only the ones a case actually wrote, so the
     * replacement cases keep seeing the very project they always saw.
     */
    private static final List<String> ADDABLE_PATHS = List.of(
            ADDED_FUNCTION_PATH, ADDED_TABLE_PATH, ADDED_VIEW_PATH,
            COLLIDING_SEQUENCE_PATH);

    @TempDir
    Path temp;

    private final NullProgressMonitor monitor =
            new NullProgressMonitor();
    private final List<PgDbParser> parsers = new ArrayList<>();
    private IProject project;
    private Path failingStore;

    @BeforeEach
    void createWorkspaceProject() throws Exception {
        project = createProject(temp.resolve("project")); //$NON-NLS-1$
        writeProject(project);
    }

    @AfterEach
    void cleanWorkspaceProject() throws Exception {
        parsers.forEach(PgDbParser::clear);
        if (failingStore != null
                && Files.isRegularFile(failingStore)) {
            Files.deleteIfExists(failingStore);
        }
        if (project != null && project.exists()) {
            PgDbParser.clean(project);
            project.delete(true, true, monitor);
        }
    }

    private void assertAuxiliaryMetadata(
            StorageMode mode,
            IncrementalProjectReferenceIndex current,
            IndexState state,
            QuerySnapshot snapshot) throws Exception {
        if (mode == StorageMode.MEMORY) {
            assertInstanceOf(MemoryProjectReferenceIndex.class,
                    current);
            return;
        }
        assertInstanceOf(PackedProjectReferenceIndex.class,
                current);
        Path stateRoot = Path.of(Platform.getStateLocation(
                Activator.getContext().getBundle()).toString());
        Path directory = ProjectIndexState.directory(
                stateRoot, projectRoot());
        try (var opened =
                new ProjectIndexStore(directory)
                        .open(state.identity)) {
            assertEquals(Status.HIT, opened.status());
            var view = opened.view().orElseThrow();
            for (String path : allPaths()) {
                FileContribution contribution =
                        view.contribution(
                                new IndexPathRef(
                                        IndexPathOrigin.PROJECT,
                                        path))
                                .orElseThrow();
                // An index is packed only from an analysis that reported
                // nothing, in both the full and the incremental path, so a
                // published contribution cannot claim it may hold an
                // unresolved reference. This assertion demanded the opposite
                // while the flag was a hardcoded true.
                assertFalse(contribution.unresolvedAny(),
                        () -> path + ": a published contribution is packed" //$NON-NLS-1$
                                + " only from an analysis that reported" //$NON-NLS-1$
                                + " nothing, so it must not claim it may" //$NON-NLS-1$
                                + " hold an unresolved reference"); //$NON-NLS-1$
                assertTrue(contribution
                        .unresolvedCandidates().isEmpty(),
                        path);
            }
            for (var entry :
                    snapshot.reverseDependencies.entrySet()) {
                Set<IndexPathRef> expected =
                        entry.getValue().stream()
                                .map(path ->
                                        new IndexPathRef(
                                                IndexPathOrigin.PROJECT,
                                                path))
                                .collect(java.util.stream.Collectors
                                        .toCollection(
                                                LinkedHashSet::new));
                assertEquals(expected,
                        view.reverseDependencies(
                                entry.getKey()
                                        .toReferenceMatchKey()));
            }
            assertTrue(view.reverseDependencies(
                    MatchDto.relation("not_present") //$NON-NLS-1$
                            .toReferenceMatchKey())
                    .isEmpty());
        }
    }

    @ParameterizedTest(name = "{0}, {1} files")
    @MethodSource("parityCases")
    void boundedBatchMatchesIndependentFullParser(
            StorageMode mode, int batchSize) throws Exception {
        runParityCase(mode, batchSize);
    }

    /**
     * The addition half of the oracle: a batch that introduces files the index
     * never held has to leave the index answering exactly what a full parse of
     * the same working tree answers. Zero edits is the pure addition; the other
     * counts put an addition and a replacement in one batch, which is the shape
     * a pull actually has.
     */
    @ParameterizedTest(name = "{0}, {1} edits beside three additions")
    @MethodSource("additionParityCases")
    void addedFilesMatchIndependentFullParser(
            StorageMode mode, int editCount) throws Exception {
        runAdditionParityCase(mode, editCount);
    }

    /**
     * The refusal has to be inert, not merely correct. An addition claiming a
     * subject the index already holds is refused before anything is written,
     * and every surface the parser publishes has to read exactly as it did
     * before the batch was offered - the fall back to a full build is prepared
     * and not yet committed, so nothing may have moved.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("storageModes")
    void aRefusedAdditionLeavesEveryPublishedSurfaceUnchanged(
            StorageMode mode) throws Exception {
        if (mode == StorageMode.MEMORY) {
            failingStore = createFailingProjectStore();
        }
        permitAddedFiles(true);
        var scheduled = new AtomicInteger();
        PgDbParser incremental = parser(scheduled);
        Scenario continuity = establishContinuity(incremental);
        IncrementalProjectReferenceIndex baseline =
                incrementalIndex(incremental);
        assertInstanceOf(mode.storageType, baseline);
        IndexState before = indexState(baseline);
        ProjectIndexRevision packedBefore =
                packedRevision(baseline);

        write(COLLIDING_SEQUENCE_PATH,
                "CREATE SEQUENCE app." + OLD_TABLE + ";\n"); //$NON-NLS-1$ //$NON-NLS-2$
        project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
        // Taken after the file exists on disk, so both snapshots describe the
        // same working tree: a file the index does not hold contributes nothing
        // either way, and a difference can then only mean the batch landed.
        QuerySnapshot publicBefore = querySnapshot(incremental);
        Map<String, Integer> markersBefore = markerSnapshot();

        List<String> batch = List.of(COLLIDING_SEQUENCE_PATH);
        BuildContinuityProof proof = continuity.nextBatch(batch);
        List<String> telemetryLines = new ArrayList<>();
        var listenerEvents = new AtomicInteger();
        incremental.addListener(
                ignored -> listenerEvents.incrementAndGet());
        PgDbParser.PreparedIncrementalProjectIndex prepared =
                incremental.prepareIncrementalProjectIndex(
                        project, batch, monitor,
                        ProjectIndexTelemetryTestSupport.start(
                                telemetryLines, Mode.INCREMENTAL),
                        proof);

        assertTrue(prepared.fullBuild(),
                "an addition claiming a subject the index already holds" //$NON-NLS-1$
                        + " must not be applied incrementally"); //$NON-NLS-1$
        assertEquals(0, listenerEvents.get(),
                "a refused batch must not notify a single listener"); //$NON-NLS-1$
        assertEquals(0, scheduled.get(),
                "a refused batch already carries its rebuild and must" //$NON-NLS-1$
                        + " not schedule another"); //$NON-NLS-1$
        assertEquals(publicBefore, querySnapshot(incremental),
                "a refused addition must leave every answer the parser" //$NON-NLS-1$
                        + " gives exactly as it was"); //$NON-NLS-1$
        IncrementalProjectReferenceIndex current =
                incrementalIndex(incremental);
        assertSame(baseline, current,
                "a refused addition must not swap the live index"); //$NON-NLS-1$
        assertEquals(before, indexState(current),
                "a refused addition must not move the identity, the" //$NON-NLS-1$
                        + " generation or a single file stamp"); //$NON-NLS-1$
        assertEquals(packedBefore, packedRevision(current),
                "a refused addition must not touch the published" //$NON-NLS-1$
                        + " revision"); //$NON-NLS-1$
        assertEquals(markersBefore, markerSnapshot(),
                "a refused addition must not clear a marker it did not" //$NON-NLS-1$
                        + " earn"); //$NON-NLS-1$
        if (mode == StorageMode.MEMORY) {
            assertEquals("store-sentinel", //$NON-NLS-1$
                    Files.readString(failingStore));
        }

        // Releases the full build the refusal prepared, and only then can the
        // telemetry line be read: it is published when the run closes.
        prepared.prepared().update().commit(project.getName(), monitor);
        assertEquals(1, telemetryLines.size());
        assertTrue(telemetryLines.getFirst().contains("mode=cold"), //$NON-NLS-1$
                "a refusal has to report the full build it degraded to: " //$NON-NLS-1$
                        + telemetryLines.getFirst());
    }

    @ParameterizedTest(name = "{0}, {1} files, {2}")
    @MethodSource("rejectionCases")
    void rejectedBatchLeavesEveryPublishedSurfaceUnchanged(
            StorageMode mode, int batchSize,
            Rejection rejection) throws Exception {
        if (mode == StorageMode.MEMORY) {
            failingStore = createFailingProjectStore();
        }
        var scheduled = new AtomicInteger();
        PgDbParser incremental = parser(scheduled);
        Scenario continuity = establishContinuity(incremental);
        IncrementalProjectReferenceIndex baseline =
                incrementalIndex(incremental);
        assertInstanceOf(mode.storageType, baseline);
        IndexState before = indexState(baseline);
        ProjectIndexRevision packedBefore =
                packedRevision(baseline);
        QuerySnapshot publicBefore = querySnapshot(incremental);

        List<String> changedPaths = changedPaths(batchSize);
        for (int i = 0; i < batchSize; i++) {
            IFile file = file(changedPaths.get(i));
            file.createMarker(UIConsts.MARKER.ERROR);
            replaceFunctionBody(file, i, NEW_TABLE);
        }
        IFile untouched = file(UNTOUCHED_PATH);
        untouched.createMarker(UIConsts.MARKER.ERROR);
        Map<String, Integer> markersBefore =
                markerSnapshot();
        BuildContinuityProof proof =
                continuity.nextBatch(changedPaths);
        List<String> telemetryLines = new ArrayList<>();
        var listenerEvents = new AtomicInteger();
        incremental.addListener(
                ignored -> listenerEvents.incrementAndGet());
        PgDbParser.PreparedIncrementalProjectIndex prepared =
                incremental.prepareIncrementalProjectIndex(
                        project, changedPaths.reversed(), monitor,
                        ProjectIndexTelemetryTestSupport.start(
                                telemetryLines, Mode.INCREMENTAL),
                        proof);
        assertFalse(prepared.fullBuild());

        PgDbParser.PreparedUpdate update =
                prepared.prepared().update();
        rejection.reject(update, project, changedPaths,
                this::replaceFunctionBody);
        assertFalse(update.wasPublished());
        assertFalse(update.wasContinuityAccepted());
        assertEquals(rejection.scheduledBuilds,
                scheduled.get());
        assertEquals(0, listenerEvents.get());
        assertEquals(1, telemetryLines.size());
        String telemetryLine = telemetryLines.getFirst();
        assertTrue(telemetryLine.contains(
                "persistence_status=failed " //$NON-NLS-1$
                        + "persistence_reason=" //$NON-NLS-1$
                        + rejection.persistenceReason(mode)),
                telemetryLine);
        assertEquals(publicBefore, querySnapshot(incremental));
        IncrementalProjectReferenceIndex current =
                incrementalIndex(incremental);
        assertSame(baseline, current);
        assertEquals(before, indexState(current));
        assertEquals(packedBefore, packedRevision(
                current));
        assertEquals(markersBefore, markerSnapshot());
        if (mode == StorageMode.MEMORY) {
            assertEquals("store-sentinel", //$NON-NLS-1$
                    Files.readString(failingStore));
        }
    }

    /**
     * The repair half of the oracle: nobody names the batch. The build is
     * asked for a whole project, the warm validation of the stored index is
     * the only thing that knows which files moved, and the index it leaves
     * behind has to answer exactly what a full parse of the same tree answers.
     *
     * <p>Only the packed storage takes part. A repair validates the index on
     * disk, and the memory mode of this test has no readable store at all, so
     * there is nothing there to disagree with a working tree.</p>
     */
    @ParameterizedTest(name = "{0} files")
    @MethodSource("repairSizes")
    void repairedStaleIndexMatchesIndependentFullParser(int batchSize)
            throws Exception {
        PgDbParser repaired = parser();
        Scenario continuity = establishContinuity(repaired);
        IncrementalProjectReferenceIndex baseline =
                incrementalIndex(repaired);
        assertInstanceOf(PackedProjectReferenceIndex.class,
                baseline);
        IndexState before = indexState(baseline);
        ProjectIndexRevision packedBefore =
                packedRevision(baseline);
        QuerySnapshot publicBefore = querySnapshot(repaired);

        List<String> changedPaths = changedPaths(batchSize);
        for (int i = 0; i < batchSize; i++) {
            IFile file = file(changedPaths.get(i));
            file.createMarker(UIConsts.MARKER.ERROR);
            replaceFunctionBody(file, i, NEW_TABLE);
        }
        IFile untouched = file(UNTOUCHED_PATH);
        untouched.createMarker(UIConsts.MARKER.ERROR);

        // A reconciliation proof is what a build of the whole project carries.
        // It never reaches the batch: the repair hands the incremental path a
        // null proof so that every input of the batch is proved instead of
        // taken on trust.
        BuildContinuityProof proof = continuity.nextReconciliation();
        List<String> telemetryLines = new ArrayList<>();
        var telemetry = ProjectIndexTelemetryTestSupport.start(
                telemetryLines, Mode.COLD);
        var listenerEvents = new AtomicInteger();
        repaired.addListener(
                ignored -> listenerEvents.incrementAndGet());

        PgDbParser.PreparedProjectIndex prepared =
                repaired.prepareRepairedProjectIndex(project, monitor,
                        telemetry, proof);

        assertFalse(prepared.restoredFromDisk(),
                "the stored index disagrees with the working tree, so" //$NON-NLS-1$
                        + " nothing may be restored from it as it is"); //$NON-NLS-1$
        assertEquals(0, listenerEvents.get());
        PgDbParser.PreparedUpdate update = prepared.update();
        update.commit(project.getName(), monitor);
        assertTrue(update.wasPublished());
        assertFalse(update.wasContinuityAccepted(),
                "a repair offers the batch no proof, so the build it" //$NON-NLS-1$
                        + " belongs to may not be trusted afterwards"); //$NON-NLS-1$
        assertEquals(1, listenerEvents.get());
        for (String path : changedPaths) {
            assertEquals(0, errorMarkerCount(file(path)), path);
        }
        assertEquals(1, errorMarkerCount(untouched));

        PgDbParser full = parser();
        full.prepareFullDBFromPgDbProject(project, monitor)
                .commit(project.getName(), monitor);
        QuerySnapshot fullSnapshot = querySnapshot(full);
        QuerySnapshot repairedSnapshot = querySnapshot(repaired);
        assertQuerySnapshotIsNonVacuous(fullSnapshot, changedPaths);
        assertEquals(fullSnapshot, repairedSnapshot,
                "an index repaired from a batch nobody named must" //$NON-NLS-1$
                        + " answer exactly what a full parse of the same" //$NON-NLS-1$
                        + " tree answers"); //$NON-NLS-1$
        DiffFingerprint fullDiff =
                diffFingerprint(publicBefore, fullSnapshot);
        assertDiffIsNonVacuous(fullDiff, changedPaths);
        assertEquals(fullDiff,
                diffFingerprint(publicBefore, repairedSnapshot),
                "the repair and the full build must describe the same" //$NON-NLS-1$
                        + " change to the published surface"); //$NON-NLS-1$

        assertRepairPublication(batchSize, repaired, full, fullSnapshot,
                before, packedBefore, listenerEvents, telemetryLines);
    }

    /**
     * What a repair leaves behind. This is {@link #assertPublication} for a
     * build nobody handed a batch to, and it pins the same index, the same
     * revision and the same answers.
     *
     * <p>The enumeration counters are the one thing it does not pin. A repair
     * enumerates the whole tree for its warm attempt and again for the
     * preflight of the batch it derived, so those counters describe the cost
     * of the repair rather than its result, and no measurement of them exists
     * yet. What the run parsed is pinned instead, because that is the claim: a
     * repair reads the files the validation named and no others.</p>
     */
    private void assertRepairPublication(int batchSize,
            PgDbParser repaired, PgDbParser full,
            QuerySnapshot fullSnapshot, IndexState before,
            ProjectIndexRevision packedBefore,
            AtomicInteger listenerEvents,
            List<String> telemetryLines) throws Exception {
        assertEquals(1, listenerEvents.get());
        assertEquals(1, telemetryLines.size());
        String telemetry = telemetryLines.getFirst();
        assertTrue(telemetry.contains("mode=incremental"), //$NON-NLS-1$
                "a repair has to report the work it actually did, and" //$NON-NLS-1$
                        + " a full parse is not it: " + telemetry); //$NON-NLS-1$
        assertTrue(telemetry.contains(
                "paths_parsed=" + batchSize //$NON-NLS-1$
                        + " paths_analyzed=" + batchSize), //$NON-NLS-1$
                "only the files the validation named may be parsed: " //$NON-NLS-1$
                        + telemetry);

        IncrementalProjectReferenceIndex current =
                incrementalIndex(repaired);
        assertInstanceOf(PackedProjectReferenceIndex.class,
                current);
        IndexState after = indexState(current);
        assertEquals(before.identity, after.identity);
        assertCurrentFileStamps(after.fileStamps);
        assertSubjectParity(current, full, replacementSubjects());
        assertAuxiliaryMetadata(StorageMode.PACKED, current, after,
                fullSnapshot);
        ProjectIndexRevision packedAfter = packedRevision(current);
        assertEquals(packedBefore.publicationId(),
                packedAfter.publicationId());
        assertEquals(packedBefore.generation(),
                packedAfter.generation());
        assertTrue(packedAfter.committedJournalLength()
                > packedBefore.committedJournalLength());
        assertEquals(before.generation, after.generation);

        PgDbParser reopened = parser();
        PgDbParser.PreparedProjectIndex warm =
                reopened.prepareProjectIndex(project, monitor);
        assertTrue(warm.restoredFromDisk(),
                "a repaired index has to be the one the next session" //$NON-NLS-1$
                        + " restores without touching a parser"); //$NON-NLS-1$
        warm.update().commit(project.getName(), monitor);
        IncrementalProjectReferenceIndex reopenedIndex =
                incrementalIndex(reopened);
        assertInstanceOf(PackedProjectReferenceIndex.class,
                reopenedIndex);
        assertEquals(after, indexState(reopenedIndex));
        assertEquals(packedAfter, packedRevision(reopenedIndex));
        assertEquals(fullSnapshot, querySnapshot(reopened));
    }

    /**
     * A repair that cannot be applied has to end in a rebuild and stop there.
     * The rebuild it ends in is the one every incremental fallback reaches, so
     * a rebuild that repaired again would derive this very batch a second
     * time, refuse it again, and never return at all: this case returning is
     * the whole assertion, and the ones written out below only describe what
     * it returned with.
     *
     * <p>The edit changes what the file declares, not only what it reads, so
     * the batch loses the right to replace the contribution the index holds
     * for it and the fallback is unavoidable.</p>
     */
    @Test
    void aRepairThatCannotBeAppliedRebuildsExactlyOnce() throws Exception {
        PgDbParser repaired = parser();
        Scenario continuity = establishContinuity(repaired);
        assertInstanceOf(PackedProjectReferenceIndex.class,
                incrementalIndex(repaired));

        write(functionPath(0), """
                CREATE OR REPLACE FUNCTION app.%s(extra integer)
                RETURNS integer
                LANGUAGE sql
                AS $function$
                SELECT id FROM app.%s LIMIT 1;
                $function$;
                """.formatted(functionName(0), NEW_TABLE));
        project.refreshLocal(IResource.DEPTH_INFINITE, monitor);

        List<String> telemetryLines = new ArrayList<>();
        PgDbParser.PreparedProjectIndex prepared =
                repaired.prepareRepairedProjectIndex(project, monitor,
                        ProjectIndexTelemetryTestSupport.start(
                                telemetryLines, Mode.COLD),
                        continuity.nextReconciliation());

        PgDbParser.PreparedUpdate update = prepared.update();
        update.commit(project.getName(), monitor);
        assertTrue(update.wasPublished());
        assertEquals(1, telemetryLines.size(),
                "one build, and one run reporting it: " //$NON-NLS-1$
                        + telemetryLines);
        assertTrue(telemetryLines.getFirst().contains("mode=cold"), //$NON-NLS-1$
                "a repair that could not be applied has to report the" //$NON-NLS-1$
                        + " rebuild it degraded to: " //$NON-NLS-1$
                        + telemetryLines.getFirst());

        PgDbParser full = parser();
        full.prepareFullDBFromPgDbProject(project, monitor)
                .commit(project.getName(), monitor);
        assertEquals(querySnapshot(full), querySnapshot(repaired),
                "the rebuild a refused repair falls back to is still a" //$NON-NLS-1$
                        + " rebuild, and has to answer like one"); //$NON-NLS-1$
    }

    private static Stream<Arguments> parityCases() {
        return Stream.of(StorageMode.values())
                .flatMap(mode -> BATCH_SIZES.stream()
                        .map(size -> Arguments.of(mode, size)));
    }

    private static Stream<Arguments> repairSizes() {
        return BATCH_SIZES.stream().map(Arguments::of);
    }

    private static Stream<Arguments> rejectionCases() {
        return Stream.of(StorageMode.values())
                .flatMap(mode -> BATCH_SIZES.stream()
                        .flatMap(size ->
                                Stream.of(Rejection.values())
                                        .map(rejection -> Arguments.of(
                                                mode, size,
                                                rejection))));
    }

    private static Stream<Arguments> additionParityCases() {
        return Stream.of(StorageMode.values())
                .flatMap(mode -> ADDITION_EDIT_COUNTS.stream()
                        .map(count -> Arguments.of(mode, count)));
    }

    private static Stream<Arguments> storageModes() {
        return Stream.of(StorageMode.values())
                .map(Arguments::of);
    }

    private void runParityCase(StorageMode mode, int batchSize)
            throws Exception {
        if (mode == StorageMode.MEMORY) {
            failingStore = createFailingProjectStore();
        }
        PgDbParser incremental = parser();
        Scenario continuity = establishContinuity(incremental);
        IncrementalProjectReferenceIndex baseline =
                incrementalIndex(incremental);
        assertInstanceOf(mode.storageType, baseline);
        IndexState before = indexState(baseline);
        ProjectIndexRevision packedBefore =
                packedRevision(baseline);
        QuerySnapshot publicBefore =
                querySnapshot(incremental);

        List<String> changedPaths = changedPaths(batchSize);
        for (int i = 0; i < batchSize; i++) {
            IFile file = file(changedPaths.get(i));
            file.createMarker(UIConsts.MARKER.ERROR);
            replaceFunctionBody(file, i, NEW_TABLE);
        }
        IFile untouched = file(UNTOUCHED_PATH);
        untouched.createMarker(UIConsts.MARKER.ERROR);

        BuildContinuityProof proof =
                continuity.nextBatch(changedPaths);
        List<String> telemetryLines = new ArrayList<>();
        var telemetry = ProjectIndexTelemetryTestSupport.start(
                telemetryLines, Mode.INCREMENTAL);
        var listenerEvents = new AtomicInteger();
        incremental.addListener(
                ignored -> listenerEvents.incrementAndGet());

        PgDbParser.PreparedIncrementalProjectIndex prepared =
                incremental.prepareIncrementalProjectIndex(
                        project, changedPaths.reversed(), monitor,
                        telemetry, proof);

        assertFalse(prepared.fullBuild());
        assertEquals(0, listenerEvents.get());
        PgDbParser.PreparedUpdate update =
                prepared.prepared().update();
        update.commit(project.getName(), monitor);
        assertTrue(update.wasPublished());
        assertTrue(update.wasContinuityAccepted());
        assertEquals(1, listenerEvents.get());
        for (String path : changedPaths) {
            assertEquals(0, errorMarkerCount(file(path)));
        }
        assertEquals(1, errorMarkerCount(untouched));

        PgDbParser full = parser();
        full.prepareFullDBFromPgDbProject(project, monitor)
                .commit(project.getName(), monitor);
        QuerySnapshot fullSnapshot = querySnapshot(full);
        QuerySnapshot incrementalSnapshot =
                querySnapshot(incremental);
        assertQuerySnapshotIsNonVacuous(
                fullSnapshot, changedPaths);
        assertEquals(fullSnapshot, incrementalSnapshot);
        DiffFingerprint fullDiff =
                diffFingerprint(publicBefore, fullSnapshot);
        assertDiffIsNonVacuous(fullDiff, changedPaths);
        assertEquals(fullDiff,
                diffFingerprint(publicBefore,
                        incrementalSnapshot));

        assertPublication(mode, batchSize, incremental, full,
                fullSnapshot, continuity, proof, before,
                packedBefore, listenerEvents, telemetryLines,
                replacementSubjects());
    }

    /**
     * The addition case, built out of the same pieces the replacement case is
     * built out of: the batch runs against a live index, an independent parser
     * parses the whole tree from nothing, and the two have to answer alike.
     *
     * @param editCount replacements to put in the batch beside the three
     *                  additions; zero exercises the pure addition
     */
    private void runAdditionParityCase(StorageMode mode, int editCount)
            throws Exception {
        if (mode == StorageMode.MEMORY) {
            failingStore = createFailingProjectStore();
        }
        permitAddedFiles(true);
        PgDbParser incremental = parser();
        Scenario continuity = establishContinuity(incremental);
        IncrementalProjectReferenceIndex baseline =
                incrementalIndex(incremental);
        assertInstanceOf(mode.storageType, baseline);
        IndexState before = indexState(baseline);
        ProjectIndexRevision packedBefore =
                packedRevision(baseline);
        QuerySnapshot publicBefore =
                querySnapshot(incremental);

        List<String> editedPaths = changedPaths(editCount);
        for (int i = 0; i < editCount; i++) {
            IFile file = file(editedPaths.get(i));
            file.createMarker(UIConsts.MARKER.ERROR);
            replaceFunctionBody(file, i, NEW_TABLE);
        }
        writeAddedFiles();
        for (String path : ADDED_PATHS) {
            file(path).createMarker(UIConsts.MARKER.ERROR);
        }
        IFile untouched = file(UNTOUCHED_PATH);
        untouched.createMarker(UIConsts.MARKER.ERROR);
        List<String> batch = new ArrayList<>(ADDED_PATHS);
        batch.addAll(editedPaths);

        BuildContinuityProof proof =
                continuity.nextBatch(batch);
        List<String> telemetryLines = new ArrayList<>();
        var telemetry = ProjectIndexTelemetryTestSupport.start(
                telemetryLines, Mode.INCREMENTAL);
        var listenerEvents = new AtomicInteger();
        incremental.addListener(
                ignored -> listenerEvents.incrementAndGet());

        PgDbParser.PreparedIncrementalProjectIndex prepared =
                incremental.prepareIncrementalProjectIndex(
                        project, batch.reversed(), monitor,
                        telemetry, proof);

        assertFalse(prepared.fullBuild(),
                "a batch that introduces a file must stay incremental" //$NON-NLS-1$
                        + " while the preference permits it"); //$NON-NLS-1$
        assertEquals(0, listenerEvents.get());
        PgDbParser.PreparedUpdate update =
                prepared.prepared().update();
        update.commit(project.getName(), monitor);
        assertTrue(update.wasPublished());
        assertTrue(update.wasContinuityAccepted());
        assertEquals(1, listenerEvents.get());
        for (String path : batch) {
            assertEquals(0, errorMarkerCount(file(path)), path);
        }
        assertEquals(1, errorMarkerCount(untouched));

        PgDbParser full = parser();
        full.prepareFullDBFromPgDbProject(project, monitor)
                .commit(project.getName(), monitor);
        QuerySnapshot fullSnapshot = querySnapshot(full);
        QuerySnapshot incrementalSnapshot =
                querySnapshot(incremental);
        assertAdditionSnapshotIsNonVacuous(
                fullSnapshot, editedPaths);
        assertEquals(fullSnapshot, incrementalSnapshot,
                "an index that took the addition incrementally must" //$NON-NLS-1$
                        + " answer exactly what a full parse of the same" //$NON-NLS-1$
                        + " tree answers"); //$NON-NLS-1$
        DiffFingerprint fullDiff =
                diffFingerprint(publicBefore, fullSnapshot);
        assertAdditionDiffIsNonVacuous(fullDiff, editedPaths);
        assertEquals(fullDiff,
                diffFingerprint(publicBefore,
                        incrementalSnapshot),
                "the increment and the full build must describe the" //$NON-NLS-1$
                        + " same change to the published surface"); //$NON-NLS-1$

        assertPublication(mode, batch.size(), incremental, full,
                fullSnapshot, continuity, proof, before,
                packedBefore, listenerEvents, telemetryLines,
                additionSubjects());
    }

    private void assertPublication(StorageMode mode, int batchSize,
            PgDbParser incremental, PgDbParser full,
            QuerySnapshot fullSnapshot, Scenario continuity,
            BuildContinuityProof proof,
            IndexState before, ProjectIndexRevision packedBefore,
            AtomicInteger listenerEvents,
            List<String> telemetryLines,
            List<SubjectExpectation> subjects)
            throws Exception {
        assertTrue(continuity.accept(proof));
        assertEquals(1, listenerEvents.get());
        assertEquals(1, telemetryLines.size());
        String telemetry = telemetryLines.getFirst();
        assertTrue(telemetry.contains(
                "mode=" + mode.telemetryToken), //$NON-NLS-1$
                "the run has to report the mode it actually took: " //$NON-NLS-1$
                        + telemetry);
        assertTrue(telemetry.contains(
                "paths_enumerated=" + batchSize)); //$NON-NLS-1$
        assertTrue(telemetry.contains(
                "paths_parsed=" + batchSize //$NON-NLS-1$
                        + " paths_analyzed=" + batchSize)); //$NON-NLS-1$
        assertTrue(telemetry.contains("enumeration_passes=0")); //$NON-NLS-1$

        IncrementalProjectReferenceIndex current =
                incrementalIndex(incremental);
        assertInstanceOf(mode.storageType, current);
        IndexState after = indexState(current);
        assertEquals(before.identity, after.identity);
        assertCurrentFileStamps(after.fileStamps);
        assertSubjectParity(current, full, subjects);
        assertAuxiliaryMetadata(mode, current, after,
                fullSnapshot);
        if (mode == StorageMode.PACKED) {
            ProjectIndexRevision packedAfter =
                    packedRevision(current);
            assertEquals(packedBefore.publicationId(),
                    packedAfter.publicationId());
            assertEquals(packedBefore.generation(),
                    packedAfter.generation());
            assertTrue(packedAfter.committedJournalLength()
                    > packedBefore.committedJournalLength());
            assertEquals(before.generation, after.generation);

            PgDbParser reopened = parser();
            PgDbParser.PreparedProjectIndex warm =
                    reopened.prepareProjectIndex(project, monitor);
            assertTrue(warm.restoredFromDisk());
            warm.update().commit(project.getName(), monitor);
            IncrementalProjectReferenceIndex reopenedIndex =
                    incrementalIndex(reopened);
            assertInstanceOf(PackedProjectReferenceIndex.class,
                    reopenedIndex);
            assertEquals(after, indexState(reopenedIndex));
            assertEquals(packedAfter,
                    packedRevision(reopenedIndex));
            assertEquals(fullSnapshot,
                    querySnapshot(reopened));
        } else {
            assertEquals(before.generation + 1,
                    after.generation);
            assertEquals("store-sentinel", //$NON-NLS-1$
                    Files.readString(failingStore));
        }
    }

    private void assertQuerySnapshotIsNonVacuous(
            QuerySnapshot snapshot,
            List<String> changedPaths) {
        assertEquals(20, snapshot.definitions.size());
        assertEquals(17, snapshot.definitions.stream()
                .filter(definition ->
                        definition.kind
                                == DefinitionKind.FUNCTION)
                .count());
        assertEquals(2, snapshot.definitions.stream()
                .filter(definition ->
                        definition.kind
                                == DefinitionKind.RELATION)
                .count());
        assertEquals(1, snapshot.definitions.stream()
                .filter(definition ->
                        definition.kind
                                == DefinitionKind.STATEMENT)
                .count());
        assertEquals(66, snapshot.references.size());
        assertEquals(allPaths(), snapshot.definitionsByPath
                .keySet().stream().toList());
        assertEquals(allPaths(), snapshot.referencesByPath
                .keySet().stream().toList());

        assertSeededFunctionsFollowTheBatch(snapshot, changedPaths);
        assertEquals(1, snapshot.definitionsByObject
                .get(NEW_TABLE).size());
        List<String> changedDependencies =
                resolvableFunctionPaths(changedPaths);
        assertEquals(1 + changedDependencies.size(),
                snapshot.referencesByObject
                        .get(NEW_TABLE).size());
        assertEquals(10 - changedDependencies.size(),
                snapshot.referencesByObject
                        .get(OLD_TABLE).size());
        assertTrue(snapshot.definitionsByObject
                .get("not_present").isEmpty()); //$NON-NLS-1$
        assertTrue(snapshot.referencesByObject
                .get("not_present").isEmpty()); //$NON-NLS-1$
        assertEquals(19,
                snapshot.completions.get("PAR").size()); //$NON-NLS-1$
        assertEquals(10,
                snapshot.completions.get("FN_0").size()); //$NON-NLS-1$
        assertEquals(1,
                snapshot.completions.get("NEW").size()); //$NON-NLS-1$

        assertEquals(changedDependencies,
                snapshot.reverseDependencies.get(
                        MatchDto.relation(NEW_TABLE)));
        List<String> expectedOld = new ArrayList<>();
        IntStream.range(0, 16)
                .filter(i -> i % 2 == 0)
                .mapToObj(PgDbParserBatchParityOracleTest
                        ::functionPath)
                .filter(path -> !changedPaths.contains(path))
                .forEach(expectedOld::add);
        expectedOld.add(UNTOUCHED_PATH);
        expectedOld.sort(String::compareTo);
        assertEquals(expectedOld,
                snapshot.reverseDependencies.get(
                        MatchDto.relation(OLD_TABLE)));
    }

    /**
     * The sixteen seeded functions and the untouched one: each still owns its
     * single definition, and each reads the table the batch left it reading.
     * Applies to every case, because no case is allowed to disturb a file it
     * did not name.
     */
    private void assertSeededFunctionsFollowTheBatch(
            QuerySnapshot snapshot, List<String> changedPaths) {
        for (int i = 0; i < 16; i++) {
            String path = functionPath(i);
            assertEquals(1, snapshot.definitionsByPath
                    .get(path).size(), path);
            String expectedTable = changedPaths.contains(path)
                    ? NEW_TABLE : OLD_TABLE;
            List<LocationDto> references =
                    snapshot.referencesByPath.get(path);
            assertEquals(i % 2 == 0
                            ? List.of(expectedTable)
                            : List.of(),
                    referencedRelations(references),
                    path + " => " + references); //$NON-NLS-1$
        }
        assertEquals(List.of(OLD_TABLE),
                referencedRelations(snapshot.referencesByPath
                        .get(UNTOUCHED_PATH)));
    }

    /**
     * What the fixture is worth: the three introduced files have to be in the
     * snapshot, one definition each, in three different index sections, with
     * the references they wrote resolved against objects only the index could
     * have supplied. Without this the equality above would hold just as well
     * for a batch that changed nothing at all.
     */
    private void assertAdditionSnapshotIsNonVacuous(
            QuerySnapshot snapshot, List<String> editedPaths) {
        assertEquals(20 + ADDED_PATHS.size(),
                snapshot.definitions.size(),
                "the three introduced definitions have to join the" //$NON-NLS-1$
                        + " twenty the project was seeded with, and" //$NON-NLS-1$
                        + " nothing else may appear or vanish"); //$NON-NLS-1$
        assertSeededFunctionsFollowTheBatch(snapshot, editedPaths);

        assertAddedDefinition(snapshot, ADDED_TABLE_PATH,
                DefinitionKind.RELATION, ADDED_TABLE,
                List.of(new NameTypeDto("id", "integer"), //$NON-NLS-1$ //$NON-NLS-2$
                        new NameTypeDto("label", "text"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertAddedDefinition(snapshot, ADDED_VIEW_PATH,
                DefinitionKind.RELATION, ADDED_VIEW, null);
        assertAddedDefinition(snapshot, ADDED_FUNCTION_PATH,
                DefinitionKind.FUNCTION, ADDED_FUNCTION, null);

        assertEquals(List.of(ADDED_TABLE),
                referencedRelations(snapshot.referencesByPath
                        .get(ADDED_TABLE_PATH)),
                "the introduced table has to own the only relation" //$NON-NLS-1$
                        + " location its file carries"); //$NON-NLS-1$
        assertEquals(List.of(OLD_TABLE),
                referencedRelations(snapshot.referencesByPath
                        .get(ADDED_FUNCTION_PATH)),
                "the introduced function reads a table only the index" //$NON-NLS-1$
                        + " holds, so an unresolved read would show here"); //$NON-NLS-1$
        assertEquals(List.of(ADDED_VIEW, NEW_TABLE),
                referencedRelations(snapshot.referencesByPath
                        .get(ADDED_VIEW_PATH)),
                "the introduced view has to own its own definition and" //$NON-NLS-1$
                        + " resolve the table it selects from"); //$NON-NLS-1$

        assertEquals(List.of(ADDED_FUNCTION_PATH),
                snapshot.reverseDependencies
                        .getOrDefault(
                                MatchDto.relation(OLD_TABLE),
                                List.of())
                        .stream()
                        .filter(ADDED_PATHS::contains)
                        .toList(),
                "the introduced function has to become a dependent of" //$NON-NLS-1$
                        + " the table it reads"); //$NON-NLS-1$
        assertTrue(snapshot.reverseDependencies.values().stream()
                .anyMatch(paths ->
                        paths.contains(ADDED_VIEW_PATH)),
                "the introduced view has to become a dependent of" //$NON-NLS-1$
                        + " something it reads"); //$NON-NLS-1$

        List<String> completions = snapshot.completions
                .get("PAR").stream() //$NON-NLS-1$
                .map(DefinitionDto::bareName)
                .filter(addedBareNames()::contains)
                .sorted()
                .toList();
        assertEquals(addedBareNames(), completions,
                "all three introduced objects have to be offered as" //$NON-NLS-1$
                        + " completion candidates"); //$NON-NLS-1$
    }

    private void assertAddedDefinition(QuerySnapshot snapshot,
            String path, DefinitionKind kind, String bareName,
            List<NameTypeDto> columns) {
        List<DefinitionDto> definitions =
                snapshot.definitionsByPath.get(path);
        assertEquals(1, definitions.size(),
                path + " has to carry exactly one definition, not " //$NON-NLS-1$
                        + definitions);
        DefinitionDto definition = definitions.getFirst();
        assertEquals(kind, definition.kind,
                path + " has to land in the " + kind //$NON-NLS-1$
                        + " section of the index"); //$NON-NLS-1$
        assertEquals(bareName, definition.bareName, path);
        // Columns are asserted only where the caller names them. A view is a
        // RELATION too, and a full build of this project leaves its columns
        // unresolved — measured, not assumed: this assertion fired on the
        // snapshot of the independent full parse, not on the increment. So
        // demanding resolution for every RELATION describes the product
        // wrongly. Whether the two paths agree about the view is not weakened
        // by dropping it; that is what comparing the whole snapshot is for.
        if (columns != null) {
            assertTrue(definition.relation.columnsKnown,
                    path + " has to arrive with its columns resolved," //$NON-NLS-1$
                            + " otherwise the two build paths cannot" //$NON-NLS-1$
                            + " agree on its shape"); //$NON-NLS-1$
            assertEquals(columns, definition.relation.columns, path);
        }
    }

    private static List<String> addedBareNames() {
        return List.of(ADDED_TABLE, ADDED_FUNCTION, ADDED_VIEW)
                .stream().sorted().toList();
    }

    /**
     * The diff the addition has to produce: exactly three new definitions and
     * none lost, the replaced bodies swapping one table for the other, and the
     * introduced files contributing locations of their own.
     */
    private void assertAdditionDiffIsNonVacuous(
            DiffFingerprint diff, List<String> editedPaths) {
        assertEquals(addedBareNames(),
                diff.addedDefinitions.stream()
                        .map(DefinitionDto::bareName)
                        .sorted()
                        .toList(),
                "the three introduced objects are the only definitions" //$NON-NLS-1$
                        + " the batch may add"); //$NON-NLS-1$
        assertTrue(diff.removedDefinitions.isEmpty(),
                "an introduced file takes nothing away: " //$NON-NLS-1$
                        + diff.removedDefinitions);

        List<LocationDto> fromAdded = diff.addedReferences.stream()
                .filter(location ->
                        ADDED_PATHS.contains(location.path))
                .toList();
        List<LocationDto> fromEdited = diff.addedReferences.stream()
                .filter(location ->
                        !ADDED_PATHS.contains(location.path))
                .toList();
        assertFalse(fromAdded.isEmpty(),
                "the introduced files have to contribute locations of" //$NON-NLS-1$
                        + " their own"); //$NON-NLS-1$
        List<String> editedDependencies =
                resolvableFunctionPaths(editedPaths);
        long editedLocations = 2L * editedDependencies.size();
        assertEquals(editedLocations, fromEdited.size(),
                "a replaced body changes two locations and no more: " //$NON-NLS-1$
                        + fromEdited);
        assertTrue(fromEdited.stream()
                .allMatch(location ->
                        location.references(NEW_TABLE)),
                "every location a replaced body gained has to read the" //$NON-NLS-1$
                        + " new table: " + fromEdited); //$NON-NLS-1$
        assertEquals(editedLocations,
                diff.removedReferences.size(),
                "only the replaced bodies may lose a location: " //$NON-NLS-1$
                        + diff.removedReferences);
        assertTrue(diff.removedReferences.stream()
                .allMatch(location ->
                        location.references(OLD_TABLE)),
                "every location a replaced body lost has to have read" //$NON-NLS-1$
                        + " the old table: " + diff.removedReferences); //$NON-NLS-1$

        assertEquals(new PathDelta(editedDependencies,
                        List.of(ADDED_FUNCTION_PATH)),
                diff.reverseDependencies.get(
                        MatchDto.relation(OLD_TABLE)),
                "the old table loses the replaced bodies and gains the" //$NON-NLS-1$
                        + " introduced function"); //$NON-NLS-1$
    }

    private static List<String> referencedRelations(
            List<LocationDto> locations) {
        return locations.stream()
                .filter(location ->
                        location.reference != null
                                && location.reference.type
                                        .in(DbObjType.TABLE,
                                                DbObjType.VIEW,
                                                DbObjType.SEQUENCE))
                .map(location -> location.reference.table)
                .distinct()
                .sorted()
                .toList();
    }

    private void assertDiffIsNonVacuous(
            DiffFingerprint diff,
            List<String> changedPaths) {
        List<String> changedDependencies =
                resolvableFunctionPaths(changedPaths);
        assertTrue(diff.addedDefinitions.isEmpty());
        assertTrue(diff.removedDefinitions.isEmpty());
        long changedLocations =
                2L * changedDependencies.size();
        assertEquals(changedLocations,
                diff.addedReferences.size());
        assertEquals(changedLocations,
                diff.removedReferences.size());
        assertTrue(diff.addedReferences.stream()
                .allMatch(location ->
                        location.references(NEW_TABLE)));
        assertTrue(diff.removedReferences.stream()
                .allMatch(location ->
                        location.references(OLD_TABLE)));
        MatchDto newRelation =
                MatchDto.relation(NEW_TABLE);
        MatchDto newColumn =
                MatchDto.column(NEW_TABLE, "id"); //$NON-NLS-1$
        MatchDto oldRelation =
                MatchDto.relation(OLD_TABLE);
        MatchDto oldColumn =
                MatchDto.column(OLD_TABLE, "id"); //$NON-NLS-1$
        assertEquals(Set.of(newRelation, newColumn,
                        oldRelation, oldColumn),
                diff.reverseDependencies.keySet());
        PathDelta added = new PathDelta(
                List.of(), changedDependencies);
        PathDelta removed = new PathDelta(
                changedDependencies, List.of());
        assertEquals(added,
                diff.reverseDependencies.get(newRelation));
        assertEquals(added,
                diff.reverseDependencies.get(newColumn));
        assertEquals(removed,
                diff.reverseDependencies.get(oldRelation));
        assertEquals(removed,
                diff.reverseDependencies.get(oldColumn));
    }

    private static List<String> resolvableFunctionPaths(
            List<String> paths) {
        return IntStream.range(0, 16)
                .filter(i -> i % 2 == 0)
                .mapToObj(PgDbParserBatchParityOracleTest
                        ::functionPath)
                .filter(paths::contains)
                .toList();
    }

    /** The subjects the seeded project alone answers to. */
    private static List<SubjectExpectation> replacementSubjects() {
        return List.of(
                new SubjectExpectation(
                        new ProjectIndexDefinitionSubject(
                                MatchFamily.RELATION, null,
                                SCHEMA, null),
                        List.of(
                                "SCHEMA/app/TABLE/" //$NON-NLS-1$
                                        + NEW_TABLE + ".sql", //$NON-NLS-1$
                                "SCHEMA/app/TABLE/" //$NON-NLS-1$
                                        + OLD_TABLE + ".sql")), //$NON-NLS-1$
                new SubjectExpectation(
                        new ProjectIndexDefinitionSubject(
                                MatchFamily.RELATION, null,
                                SCHEMA, NEW_TABLE),
                        List.of("SCHEMA/app/TABLE/" //$NON-NLS-1$
                                + NEW_TABLE + ".sql")), //$NON-NLS-1$
                new SubjectExpectation(
                        new ProjectIndexDefinitionSubject(
                                MatchFamily.ROUTINE, null,
                                SCHEMA, null),
                        seededRoutinePaths()),
                new SubjectExpectation(
                        new ProjectIndexDefinitionSubject(
                                MatchFamily.ROUTINE, null,
                                SCHEMA, functionName(0) + "()"), //$NON-NLS-1$
                        List.of(functionPath(0))));
    }

    /**
     * The same subjects once the three files are in, plus one per introduced
     * object. The relation family has to answer with the introduced table and
     * the introduced view together, which is what makes the two index sections
     * a single subject query has to cross visible.
     */
    private static List<SubjectExpectation> additionSubjects() {
        List<String> routinePaths =
                new ArrayList<>(seededRoutinePaths());
        routinePaths.add(ADDED_FUNCTION_PATH);
        routinePaths.sort(String::compareTo);
        List<String> relationPaths = new ArrayList<>(List.of(
                "SCHEMA/app/TABLE/" + NEW_TABLE + ".sql", //$NON-NLS-1$ //$NON-NLS-2$
                "SCHEMA/app/TABLE/" + OLD_TABLE + ".sql", //$NON-NLS-1$ //$NON-NLS-2$
                ADDED_TABLE_PATH, ADDED_VIEW_PATH));
        relationPaths.sort(String::compareTo);
        return List.of(
                new SubjectExpectation(
                        new ProjectIndexDefinitionSubject(
                                MatchFamily.RELATION, null,
                                SCHEMA, null),
                        relationPaths),
                new SubjectExpectation(
                        new ProjectIndexDefinitionSubject(
                                MatchFamily.RELATION, null,
                                SCHEMA, ADDED_TABLE),
                        List.of(ADDED_TABLE_PATH)),
                new SubjectExpectation(
                        new ProjectIndexDefinitionSubject(
                                MatchFamily.RELATION, null,
                                SCHEMA, ADDED_VIEW),
                        List.of(ADDED_VIEW_PATH)),
                new SubjectExpectation(
                        new ProjectIndexDefinitionSubject(
                                MatchFamily.ROUTINE, null,
                                SCHEMA, null),
                        routinePaths),
                new SubjectExpectation(
                        new ProjectIndexDefinitionSubject(
                                MatchFamily.ROUTINE, null,
                                SCHEMA, ADDED_FUNCTION + "()"), //$NON-NLS-1$
                        List.of(ADDED_FUNCTION_PATH)),
                new SubjectExpectation(
                        new ProjectIndexDefinitionSubject(
                                MatchFamily.ROUTINE, null,
                                SCHEMA, functionName(0) + "()"), //$NON-NLS-1$
                        List.of(functionPath(0))));
    }

    private static List<String> seededRoutinePaths() {
        List<String> routinePaths = new ArrayList<>();
        IntStream.range(0, 16)
                .mapToObj(PgDbParserBatchParityOracleTest
                        ::functionPath)
                .forEach(routinePaths::add);
        routinePaths.add(UNTOUCHED_PATH);
        routinePaths.sort(String::compareTo);
        return List.copyOf(routinePaths);
    }

    private void assertSubjectParity(
            IncrementalProjectReferenceIndex incremental,
            PgDbParser full, List<SubjectExpectation> subjects)
            throws Exception {
        IndexPathRef excluded = new IndexPathRef(
                IndexPathOrigin.PROJECT, functionPath(0));
        try (var lease = incremental.acquireAnalysisLease()) {
            for (SubjectExpectation expectation : subjects) {
                ProjectIndexDefinitionSubject subject =
                        expectation.subject;
                assertCanonicalSubject(
                        expectation.paths,
                        subjectDefinitions(full, subject, null),
                        definitionDtos(
                                lease.definitions(subject, null)));
                List<String> expectedWithoutExcluded =
                        expectation.paths.stream()
                                .filter(path -> !path.equals(
                                        excluded.relativePath()))
                                .toList();
                assertCanonicalSubject(
                        expectedWithoutExcluded,
                        subjectDefinitions(
                                full, subject, excluded),
                        definitionDtos(
                                lease.definitions(
                                        subject, excluded)));
            }
        }
    }

    private static void assertCanonicalSubject(
            List<String> expectedPaths,
            List<DefinitionDto> expected,
            List<DefinitionDto> actual) {
        assertEquals(expectedPaths,
                expected.stream()
                        .map(definition ->
                                definition.object.path)
                        .toList());
        assertEquals(expected, actual);
        assertEquals(actual.stream()
                .sorted(DEFINITION_ORDER).toList(), actual);
    }

    private QuerySnapshot querySnapshot(PgDbParser parser) {
        Path projectRoot = projectRoot();
        List<String> paths = allPaths();
        Map<String, List<DefinitionDto>> definitionsByPath =
                new LinkedHashMap<>();
        Map<String, List<LocationDto>> locationsByPath =
                new LinkedHashMap<>();
        for (String path : paths) {
            String absolute =
                    projectRoot.resolve(path).toString();
            definitionsByPath.put(path,
                    canonicalDefinitions(
                            parser.getDefsForPath(absolute)));
            locationsByPath.put(path,
                    canonicalLocations(
                            parser.getObjsForPath(absolute)
                                    .stream()));
        }

        Map<String, List<DefinitionDto>> definitionsByObject =
                new LinkedHashMap<>();
        Map<String, List<LocationDto>> referencesByObject =
                new LinkedHashMap<>();
        for (String table :
                List.of(OLD_TABLE, NEW_TABLE, "not_present")) { //$NON-NLS-1$
            ObjectLocation query = queryLocation(table);
            definitionsByObject.put(table,
                    canonicalDefinitions(
                            parser.getDefinitionsForObj(query)
                                    .toList()));
            referencesByObject.put(table,
                    canonicalLocations(
                            parser.getReferencesForObj(query)));
        }

        Map<String, List<DefinitionDto>> completions =
                new LinkedHashMap<>();
        for (String text : List.of("PAR", "FN_0", "NEW")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            completions.put(text, canonicalDefinitions(
                    parser.getCompletionCandidates(text).toList()));
        }
        List<DefinitionDto> allDefinitions =
                canonicalDefinitions(
                        parser.getAllObjDefinitions().toList());
        List<LocationDto> allReferences =
                canonicalLocations(
                        parser.getAllObjReferences());
        return new QuerySnapshot(
                allDefinitions, allReferences,
                immutableMap(definitionsByPath),
                immutableMap(locationsByPath),
                immutableMap(definitionsByObject),
                immutableMap(referencesByObject),
                immutableMap(completions),
                reverseDependencies(allReferences));
    }

    private List<DefinitionDto> subjectDefinitions(
            PgDbParser parser,
            ProjectIndexDefinitionSubject subject,
            IndexPathRef excluded) {
        return canonicalDefinitions(
                parser.getAllObjDefinitions()
                        .filter(definition -> {
                            IndexPathRef path = indexPath(
                                    definition.getFilePath());
                            return (excluded == null
                                    || !excluded.equals(path))
                                    && matches(subject,
                                            definition.getObject());
                        })
                        .toList());
    }

    private static boolean matches(
            ProjectIndexDefinitionSubject subject,
            ObjectLocation location) {
        try {
            MatchDto key = MatchDto.from(location);
            return subject.family() == key.family()
                    && subject.exactType() == key.exactType()
                    && (subject.schema() == null
                            || subject.schema()
                                    .equals(key.schema()))
                    && (subject.objectName() == null
                            || subject.objectName()
                                    .equals(key.table()));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static Map<MatchDto, List<String>>
            reverseDependencies(
                    List<LocationDto> references) {
        Map<MatchDto, Set<String>> collected =
                new TreeMap<>(MATCH_ORDER);
        for (LocationDto location : references) {
            if (location.locationType
                    == ObjectLocation.LocationType.DEFINITION) {
                continue;
            }
            try {
                collected.computeIfAbsent(
                        MatchDto.from(location),
                        ignored -> new TreeSet<>())
                        .add(location.path);
            } catch (IllegalArgumentException ex) {
                // Object locations without a typed reference cannot
                // participate in reverse dependency lookup.
            }
        }
        Map<MatchDto, List<String>> result =
                new LinkedHashMap<>();
        collected.forEach((key, paths) ->
                result.put(key, List.copyOf(paths)));
        return Collections.unmodifiableMap(result);
    }

    private static DiffFingerprint diffFingerprint(
            QuerySnapshot before, QuerySnapshot after) {
        Map<MatchDto, PathDelta> reverse =
                new TreeMap<>(MATCH_ORDER);
        Set<MatchDto> keys = new TreeSet<>(MATCH_ORDER);
        keys.addAll(before.reverseDependencies.keySet());
        keys.addAll(after.reverseDependencies.keySet());
        for (MatchDto key : keys) {
            List<String> oldPaths =
                    before.reverseDependencies
                            .getOrDefault(key, List.of());
            List<String> newPaths =
                    after.reverseDependencies
                            .getOrDefault(key, List.of());
            PathDelta delta = new PathDelta(
                    subtract(oldPaths, newPaths,
                            Comparator.naturalOrder()),
                    subtract(newPaths, oldPaths,
                            Comparator.naturalOrder()));
            if (!delta.removed.isEmpty()
                    || !delta.added.isEmpty()) {
                reverse.put(key, delta);
            }
        }
        return new DiffFingerprint(
                subtract(after.definitions,
                        before.definitions,
                        DEFINITION_ORDER),
                subtract(before.definitions,
                        after.definitions,
                        DEFINITION_ORDER),
                subtract(after.references,
                        before.references,
                        LOCATION_ORDER),
                subtract(before.references,
                        after.references,
                        LOCATION_ORDER),
                Collections.unmodifiableMap(reverse));
    }

    private static <T> List<T> subtract(
            List<T> source, List<T> removed,
            Comparator<? super T> order) {
        List<T> result = new ArrayList<>(source);
        removed.forEach(result::remove);
        result.sort(order);
        return List.copyOf(result);
    }

    private static <K, V> Map<K, V> immutableMap(
            Map<K, V> source) {
        return Collections.unmodifiableMap(
                new LinkedHashMap<>(source));
    }

    private IndexState indexState(
            IncrementalProjectReferenceIndex index) {
        try (var lease = index.acquireAnalysisLease()) {
            return new IndexState(lease.identity(),
                    lease.generation(),
                    List.copyOf(lease.fileStamps()));
        }
    }

    private void assertCurrentFileStamps(
            List<ProjectFileStamp> stamps) throws Exception {
        assertEquals(allPaths().size(), stamps.size());
        for (ProjectFileStamp stamp : stamps) {
            assertEquals(IndexPathOrigin.PROJECT,
                    stamp.path().origin());
            IFile resource = file(stamp.path().relativePath());
            Path path = resource.getLocation().toFile().toPath();
            assertEquals(resource.getModificationStamp(),
                    stamp.eclipseModificationStamp());
            assertEquals(Files.size(path), stamp.size());
            assertEquals(Files.getLastModifiedTime(path)
                    .toMillis(), stamp.lastModifiedMillis());
            assertArrayEquals(
                    MessageDigest.getInstance("SHA-256") //$NON-NLS-1$
                            .digest(Files.readAllBytes(path)),
                    stamp.contentSha256());
        }
    }

    private Scenario establishContinuity(PgDbParser parser)
            throws Exception {
        Scenario scenario =
                ProjectBuildContinuityTestSupport
                        .acceptedReconciliation(1);
        BuildContinuityProof proof =
                scenario.nextReconciliation();
        PgDbParser.PreparedUpdate update =
                parser.prepareProjectIndex(
                        project, monitor, proof).update();
        update.commit(project.getName(), monitor);
        assertTrue(update.wasPublished());
        assertTrue(update.wasContinuityAccepted());
        assertTrue(scenario.accept(proof));
        return scenario;
    }

    private PgDbParser parser() {
        PgDbParser parser = new PgDbParser();
        parsers.add(parser);
        return parser;
    }

    private PgDbParser parser(AtomicInteger scheduled) {
        PgDbParser parser = new PgDbParser(
                ignored -> { },
                ignored -> scheduled.incrementAndGet());
        parsers.add(parser);
        return parser;
    }

    private static IncrementalProjectReferenceIndex
            incrementalIndex(PgDbParser parser) {
        return parser.incrementalIndexForTests();
    }

    private static ProjectIndexRevision packedRevision(
            IncrementalProjectReferenceIndex index) {
        return index instanceof PackedProjectReferenceIndex packed
                ? packed.revision() : null;
    }

    private Path createFailingProjectStore()
            throws Exception {
        Path stateRoot = Path.of(Platform.getStateLocation(
                Activator.getContext().getBundle()).toString());
        Path directory = ProjectIndexState.directory(
                stateRoot, projectRoot());
        if (Files.isDirectory(directory)) {
            PgDbParser.cleanProjectIndexStore(directory);
            Files.deleteIfExists(directory);
        } else {
            Files.deleteIfExists(directory);
        }
        Files.createDirectories(directory.getParent());
        return Files.writeString(directory, "store-sentinel"); //$NON-NLS-1$
    }

    private IProject createProject(Path location)
            throws Exception {
        var workspace = ResourcesPlugin.getWorkspace();
        String name = "pgck-parity-" //$NON-NLS-1$
                + temp.getFileName();
        IProject created =
                workspace.getRoot().getProject(name);
        if (created.exists()) {
            created.delete(true, true, monitor);
        }
        IProjectDescription description =
                workspace.newProjectDescription(name);
        description.setLocationURI(location.toUri());
        created.create(description, monitor);
        created.open(monitor);
        return created;
    }

    private static void writeProject(IProject project)
            throws Exception {
        Path root = project.getLocation().toFile().toPath();
        Path schema = Files.createDirectories(
                root.resolve("SCHEMA/app")); //$NON-NLS-1$
        Path tables = Files.createDirectories(
                schema.resolve("TABLE")); //$NON-NLS-1$
        Path functions = Files.createDirectories(
                schema.resolve("FUNCTION")); //$NON-NLS-1$
        Files.writeString(schema.resolve("app.sql"), //$NON-NLS-1$
                "CREATE SCHEMA app;\n"); //$NON-NLS-1$
        Files.writeString(tables.resolve(OLD_TABLE + ".sql"), //$NON-NLS-1$
                tableSql(OLD_TABLE));
        Files.writeString(tables.resolve(NEW_TABLE + ".sql"), //$NON-NLS-1$
                tableSql(NEW_TABLE));
        for (int i = 0; i < 16; i++) {
            Files.writeString(functions.resolve(
                    functionName(i) + ".sql"), //$NON-NLS-1$
                    functionSql(i, OLD_TABLE));
        }
        Files.writeString(functions.resolve(
                "parity_untouched.sql"), //$NON-NLS-1$
                untouchedFunctionSql());
        project.refreshLocal(IResource.DEPTH_INFINITE,
                new NullProgressMonitor());
    }

    /**
     * Three files the index has never held, one per index section a definition
     * can land in: a table and a view both pack as relations, a function packs
     * as a routine. A single table would leave two of the three unvisited.
     *
     * <p>None of them reads a sibling of its own batch. An incremental analysis
     * resolves against the index plus the file in hand, and the index does not
     * hold a sibling yet - so each reads a table the index already holds, which
     * is what makes resolution against the index the thing being proved.
     */
    private void writeAddedFiles() throws Exception {
        write(ADDED_TABLE_PATH, "CREATE TABLE app." //$NON-NLS-1$
                + ADDED_TABLE + " (id integer, label text);\n"); //$NON-NLS-1$
        write(ADDED_VIEW_PATH, "CREATE VIEW app." + ADDED_VIEW //$NON-NLS-1$
                + " AS SELECT id FROM app." + NEW_TABLE + ";\n"); //$NON-NLS-1$ //$NON-NLS-2$
        write(ADDED_FUNCTION_PATH, """
                CREATE OR REPLACE FUNCTION app.%s()
                RETURNS integer
                LANGUAGE sql
                AS $function$
                SELECT id FROM app.%s LIMIT 1;
                $function$;
                """.formatted(ADDED_FUNCTION, OLD_TABLE));
        project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
    }

    private void write(String relativePath, String content)
            throws Exception {
        Path file = projectRoot().resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    /**
     * Opens or closes the added-files path for this project, with the project
     * override on so the verdict cannot depend on what the workspace holds.
     *
     * <p>Every caller does this before the seeding build, and that ordering is
     * load bearing: the preference is a component of the index identity, so
     * flipping it once an index exists retires that index, and the batch would
     * then fall back for a reason that has nothing to do with the addition.
     */
    private void permitAddedFiles(boolean permitted) throws Exception {
        var prefs = new ProjectScope(project)
                .getNode(UIConsts.PLUGIN_ID.THIS);
        prefs.putBoolean(UIConsts.PROJ_PREF.ENABLE_PROJ_PREF_ROOT,
                true);
        prefs.putBoolean(
                UIConsts.PREF.PROJECT_INDEX_INCREMENTAL_ADDED_FILES,
                permitted);
        prefs.flush();
    }

    private void replaceFunctionBody(IFile function, int index,
            String table) throws Exception {
        byte[] bytes = functionSql(index, table)
                .getBytes(StandardCharsets.UTF_8);
        try (var input = new ByteArrayInputStream(bytes)) {
            function.setContents(input, true, false, monitor);
        }
    }

    private static String tableSql(String table) {
        return "CREATE TABLE app." + table //$NON-NLS-1$
                + " (id integer);\n"; //$NON-NLS-1$
    }

    private static String functionSql(int index,
            String table) {
        String relation = index % 2 == 0
                ? SCHEMA + '.' + table : table;
        return """
                CREATE OR REPLACE FUNCTION app.%s()
                RETURNS integer
                LANGUAGE sql
                AS $function$
                SELECT id FROM %s LIMIT 1;
                $function$;
                """.formatted(functionName(index), relation);
    }

    private static String untouchedFunctionSql() {
        return """
                CREATE OR REPLACE FUNCTION app.parity_untouched()
                RETURNS integer
                LANGUAGE sql
                AS $function$
                SELECT id FROM app.parity_old LIMIT 1;
                $function$;
                """;
    }

    private List<String> changedPaths(int batchSize) {
        return IntStream.range(0, batchSize)
                .boxed()
                .map(PgDbParserBatchParityOracleTest
                        ::functionPath)
                .toList();
    }

    /**
     * Every project file a snapshot has to cover. The seeded twenty are always
     * there; an introduced one joins the moment its file exists, so a case that
     * never writes it sees the very project the replacement cases see.
     */
    private List<String> allPaths() {
        List<String> result = new ArrayList<>();
        result.add("SCHEMA/app/app.sql"); //$NON-NLS-1$
        result.add("SCHEMA/app/TABLE/" + OLD_TABLE + ".sql"); //$NON-NLS-1$ //$NON-NLS-2$
        result.add("SCHEMA/app/TABLE/" + NEW_TABLE + ".sql"); //$NON-NLS-1$ //$NON-NLS-2$
        for (int i = 0; i < 16; i++) {
            result.add(functionPath(i));
        }
        result.add(UNTOUCHED_PATH);
        for (String path : ADDABLE_PATHS) {
            if (file(path).exists()) {
                result.add(path);
            }
        }
        return result.stream().sorted().toList();
    }

    private static String functionName(int index) {
        return "parity_fn_%02d".formatted(index); //$NON-NLS-1$
    }

    private static String functionPath(int index) {
        return "SCHEMA/app/FUNCTION/" //$NON-NLS-1$
                + functionName(index) + ".sql"; //$NON-NLS-1$
    }

    private IFile file(String relativePath) {
        return project.getFile(
                org.eclipse.core.runtime.Path
                        .fromPortableString(relativePath));
    }

    private Path projectRoot() {
        return project.getLocation().toFile().toPath()
                .toAbsolutePath().normalize();
    }

    private IndexPathRef indexPath(String value) {
        Path path = Path.of(value).toAbsolutePath().normalize();
        Path projectPath = projectRoot();
        if (path.startsWith(projectPath)) {
            return new IndexPathRef(IndexPathOrigin.PROJECT,
                    projectPath.relativize(path).toString());
        }
        Path library = LibraryUtils.META_PATH
                .toAbsolutePath().normalize();
        if (path.startsWith(library)) {
            return new IndexPathRef(IndexPathOrigin.LIBRARY,
                    library.relativize(path).toString());
        }
        throw new AssertionError(
                "Index location is outside supported roots: " //$NON-NLS-1$
                        + value);
    }

    private List<DefinitionDto> canonicalDefinitions(
            List<MetaStatement> definitions) {
        return definitionDtos(definitions).stream()
                .sorted(DEFINITION_ORDER)
                .toList();
    }

    private List<DefinitionDto> definitionDtos(
            List<MetaStatement> definitions) {
        return definitions.stream()
                .map(this::definitionDto)
                .toList();
    }

    private DefinitionDto definitionDto(
            MetaStatement definition) {
        FunctionDto function = null;
        RelationDto relation = null;
        DefinitionKind kind;
        if (definition.getClass() == MetaStatement.class) {
            kind = DefinitionKind.STATEMENT;
        } else if (definition
                instanceof MetaFunction metaFunction) {
            kind = DefinitionKind.FUNCTION;
            function = FunctionDto.from(metaFunction);
        } else if (definition
                instanceof MetaRelation metaRelation) {
            kind = DefinitionKind.RELATION;
            relation = RelationDto.from(metaRelation);
        } else {
            kind = DefinitionKind.OTHER;
        }
        return new DefinitionDto(kind,
                definition.getClass().getName(),
                locationDto(definition.getObject()),
                definition.getBareName(),
                definition.getComment(),
                function, relation);
    }

    private List<LocationDto> canonicalLocations(
            Stream<ObjectLocation> locations) {
        return locations.map(this::locationDto)
                .sorted(LOCATION_ORDER)
                .toList();
    }

    private LocationDto locationDto(
            ObjectLocation location) {
        IndexPathRef path =
                indexPath(location.getFilePath());
        return new LocationDto(path.origin(),
                path.relativePath(), location.getOffset(),
                location.getLineNumber(),
                location.getCharPositionInLine(),
                location.getObjLength(),
                ReferenceDto.from(
                        location.getObjectReference()),
                location.getAction(), location.getAlias(),
                location.getLocationType(),
                location.getDanger());
    }

    private ObjectLocation queryLocation(String table) {
        return new ObjectLocation.Builder()
                .setFilePath(projectRoot()
                        .resolve("query.sql").toString()) //$NON-NLS-1$
                .setOffset(0)
                .setLineNumber(1)
                .setCharPositionInLine(0)
                .setLength(table.length())
                .setReference(new ObjectReference(
                        SCHEMA, table, DbObjType.TABLE))
                .setLocationType(
                        ObjectLocation.LocationType.REFERENCE)
                .build();
    }

    private static int errorMarkerCount(IFile file)
            throws Exception {
        return file.findMarkers(UIConsts.MARKER.ERROR,
                false, IResource.DEPTH_ZERO).length;
    }

    private Map<String, Integer> markerSnapshot()
            throws Exception {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String path : allPaths()) {
            result.put(path, errorMarkerCount(file(path)));
        }
        return Collections.unmodifiableMap(result);
    }

    @FunctionalInterface
    private interface BodyReplacer {
        void replace(IFile file, int index,
                String table) throws Exception;
    }

    private enum Rejection {
        CANCELLATION("cancelled", 0) { //$NON-NLS-1$
            @Override
            void reject(PgDbParser.PreparedUpdate update,
                    IProject project, List<String> changedPaths,
                    BodyReplacer replacer) {
                var cancelled = new NullProgressMonitor();
                cancelled.setCanceled(true);
                assertThrows(OperationCanceledException.class,
                        () -> update.commit(project.getName(),
                                cancelled));
            }
        },
        STALE_INPUT("stale_input", 1) { //$NON-NLS-1$
            @Override
            void reject(PgDbParser.PreparedUpdate update,
                    IProject project, List<String> changedPaths,
                    BodyReplacer replacer) throws Exception {
                int last = changedPaths.size() - 1;
                IFile file = project.getFile(
                        org.eclipse.core.runtime.Path
                                .fromPortableString(
                                        changedPaths.get(last)));
                replacer.replace(file, last, OLD_TABLE);
                update.commit(project.getName(),
                        new NullProgressMonitor());
            }
        },
        FAILURE("io", 1) { //$NON-NLS-1$
            @Override
            void reject(PgDbParser.PreparedUpdate update,
                    IProject project, List<String> changedPaths,
                    BodyReplacer replacer) {
                var failure = new UncheckedIOException(
                        new java.io.IOException(
                                "injected publication failure")); //$NON-NLS-1$
                var failingMonitor =
                        new NullProgressMonitor() {
                    @Override
                    public boolean isCanceled() {
                        throw failure;
                    }
                };
                assertSame(failure, assertThrows(
                        UncheckedIOException.class,
                        () -> update.commit(
                                project.getName(),
                                failingMonitor)));
            }
        };

        private final String packedPersistenceReason;
        private final int scheduledBuilds;

        Rejection(String packedPersistenceReason,
                int scheduledBuilds) {
            this.packedPersistenceReason =
                    packedPersistenceReason;
            this.scheduledBuilds = scheduledBuilds;
        }

        String persistenceReason(StorageMode mode) {
            if (this == FAILURE
                    && mode == StorageMode.MEMORY) {
                return "validation"; //$NON-NLS-1$
            }
            return packedPersistenceReason;
        }

        abstract void reject(
                PgDbParser.PreparedUpdate update,
                IProject project, List<String> changedPaths,
                BodyReplacer replacer) throws Exception;
    }

    private enum StorageMode {
        PACKED(PackedProjectReferenceIndex.class,
                "incremental"), //$NON-NLS-1$
        MEMORY(MemoryProjectReferenceIndex.class,
                "memory_incremental"); //$NON-NLS-1$

        private final Class<? extends
                IncrementalProjectReferenceIndex> storageType;
        /** The mode the run reports when it publishes against this storage. */
        private final String telemetryToken;

        StorageMode(Class<? extends
                IncrementalProjectReferenceIndex> storageType,
                String telemetryToken) {
            this.storageType = storageType;
            this.telemetryToken = telemetryToken;
        }
    }

    private enum DefinitionKind {
        STATEMENT,
        FUNCTION,
        RELATION,
        OTHER
    }

    private record IndexState(
            ProjectIndexIdentity identity,
            long generation,
            List<ProjectFileStamp> fileStamps) {
    }

    private record SubjectExpectation(
            ProjectIndexDefinitionSubject subject,
            List<String> paths) {

        private SubjectExpectation {
            paths = List.copyOf(paths);
        }
    }

    private record QuerySnapshot(
            List<DefinitionDto> definitions,
            List<LocationDto> references,
            Map<String, List<DefinitionDto>> definitionsByPath,
            Map<String, List<LocationDto>> referencesByPath,
            Map<String, List<DefinitionDto>> definitionsByObject,
            Map<String, List<LocationDto>> referencesByObject,
            Map<String, List<DefinitionDto>> completions,
            Map<MatchDto, List<String>> reverseDependencies) {
    }

    private record DiffFingerprint(
            List<DefinitionDto> addedDefinitions,
            List<DefinitionDto> removedDefinitions,
            List<LocationDto> addedReferences,
            List<LocationDto> removedReferences,
            Map<MatchDto, PathDelta> reverseDependencies) {
    }

    private record PathDelta(
            List<String> removed, List<String> added) {

        private PathDelta {
            removed = List.copyOf(removed);
            added = List.copyOf(added);
        }
    }

    private record DefinitionDto(
            DefinitionKind kind,
            String implementation,
            LocationDto object,
            String bareName,
            String comment,
            FunctionDto function,
            RelationDto relation) {
    }

    private record FunctionDto(
            List<ArgumentDto> arguments,
            List<ArgumentDto> orderBy,
            List<NameTypeDto> returnColumns,
            String returns,
            boolean setof) {

        private static FunctionDto from(
                MetaFunction function) {
            return new FunctionDto(
                    function.getArguments().stream()
                            .map(ArgumentDto::from)
                            .toList(),
                    function.getOrderBy().stream()
                            .map(ArgumentDto::from)
                            .toList(),
                    function.getReturnsColumns().entrySet()
                            .stream()
                            .map(entry -> new NameTypeDto(
                                    entry.getKey(),
                                    entry.getValue()))
                            .toList(),
                    function.getReturns(),
                    function.isSetof());
        }

        private FunctionDto {
            arguments = List.copyOf(arguments);
            orderBy = List.copyOf(orderBy);
            returnColumns = List.copyOf(returnColumns);
        }
    }

    private record RelationDto(
            boolean columnsKnown,
            List<NameTypeDto> columns) {

        private static RelationDto from(
                MetaRelation relation) {
            Stream<org.pgcodekeeper.core.utils.Pair<
                    String, String>> columns =
                            relation.getRelationColumns();
            return new RelationDto(columns != null,
                    columns == null ? List.of()
                            : columns.map(pair ->
                                    new NameTypeDto(
                                            pair.getFirst(),
                                            pair.getSecond()))
                                    .toList());
        }

        private RelationDto {
            columns = List.copyOf(columns);
        }
    }

    private record ArgumentDto(
            ArgMode mode, String name, String dataType,
            String defaultExpression, boolean readOnly) {

        private static ArgumentDto from(
                IArgument argument) {
            return new ArgumentDto(
                    argument.getMode(),
                    argument.getName(),
                    argument.getDataType(),
                    argument.getDefaultExpression(),
                    argument.isReadOnly());
        }
    }

    private record NameTypeDto(String name, String type) {
    }

    private record LocationDto(
            IndexPathOrigin origin,
            String path,
            int offset,
            int lineNumber,
            int charPositionInLine,
            int length,
            ReferenceDto reference,
            String action,
            String alias,
            ObjectLocation.LocationType locationType,
            DangerStatement danger) {

        private boolean references(String table) {
            return reference != null
                    && table.equals(reference.table);
        }

        private boolean global() {
            return locationType
                            == ObjectLocation.LocationType
                                    .DEFINITION
                    || locationType
                            == ObjectLocation.LocationType
                                    .REFERENCE;
        }
    }

    private record ReferenceDto(
            String schema, String table, String column,
            DbObjType type) {

        private static ReferenceDto from(
                ObjectReference reference) {
            return reference == null ? null
                    : new ReferenceDto(
                            reference.schema(),
                            reference.table(),
                            reference.column(),
                            reference.type());
        }
    }

    private record MatchDto(
            MatchFamily family,
            DbObjType exactType,
            String schema,
            String table,
            String column,
            String alias,
            boolean global) {

        private static MatchDto from(
                ObjectLocation location) {
            ObjectReference reference =
                    location.getObjectReference();
            if (reference == null
                    || reference.type() == null) {
                throw new IllegalArgumentException(
                        "A match key requires a typed reference"); //$NON-NLS-1$
            }
            return create(reference.type(),
                    reference.schema(),
                    reference.table(),
                    reference.column(),
                    location.getAlias(),
                    location.isGlobal());
        }

        private static MatchDto from(
                LocationDto location) {
            ReferenceDto reference =
                    location.reference;
            if (reference == null
                    || reference.type == null) {
                throw new IllegalArgumentException(
                        "A match key requires a typed reference"); //$NON-NLS-1$
            }
            return create(reference.type,
                    reference.schema,
                    reference.table,
                    reference.column,
                    location.alias,
                    location.global());
        }

        private static MatchDto relation(String table) {
            return create(DbObjType.TABLE, SCHEMA,
                    table, null, null, true);
        }

        private static MatchDto column(
                String table, String column) {
            return create(DbObjType.COLUMN, SCHEMA,
                    table, column, null, true);
        }

        private static MatchDto create(
                DbObjType type, String schema,
                String table, String column,
                String alias, boolean global) {
            MatchFamily family = family(type);
            return new MatchDto(family,
                    family == MatchFamily.EXACT
                            ? type : null,
                    schema, table, column, alias, global);
        }

        private static MatchFamily family(
                DbObjType type) {
            if (type.in(DbObjType.TABLE,
                    DbObjType.VIEW,
                    DbObjType.SEQUENCE)) {
                return MatchFamily.RELATION;
            }
            if (type.in(DbObjType.FUNCTION,
                    DbObjType.AGGREGATE,
                    DbObjType.PROCEDURE)) {
                return MatchFamily.ROUTINE;
            }
            if (type.in(DbObjType.TYPE,
                    DbObjType.DOMAIN)) {
                return MatchFamily.TYPE;
            }
            return MatchFamily.EXACT;
        }

        private ReferenceMatchKey
                toReferenceMatchKey() {
            return new ReferenceMatchKey(family,
                    exactType, schema, table, column,
                    alias, global);
        }
    }
}
