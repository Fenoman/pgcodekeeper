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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.base.schema.meta.MetaStatement;

import ru.taximaxim.codekeeper.ui.Activator;
import ru.taximaxim.codekeeper.ui.UIConsts;
import ru.taximaxim.codekeeper.ui.builders.ProjectBuildContinuity.BuildContinuityProof;
import ru.taximaxim.codekeeper.ui.builders.ProjectBuildContinuityTestSupport;
import ru.taximaxim.codekeeper.ui.builders.ProjectBuildContinuityTestSupport.Scenario;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexBuildConfiguration;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexConfiguration;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexIncrementalPlanner;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexState;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexStore;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexStoreTestSupport;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetry.Mode;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetry.PersistenceReason;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetryTestSupport;

class PgDbParserIncrementalProductionPathTest {

    private static final String FUNCTION_PATH =
            "SCHEMA/app/FUNCTION/pick.sql";
    private static final String TABLE_PATH =
            "SCHEMA/app/TABLE/item.sql";

    @Test
    void fullProductionPathReportsOnlyObservedCombinedLoadAndAnalyzePhase(
            @TempDir Path temp) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(
                temp.resolve("full-phase-project"), monitor);
        PgDbParser parser = new PgDbParser();
        try {
            writeInitialProject(project);
            List<String> lines = new ArrayList<>();
            var telemetry = ProjectIndexTelemetryTestSupport.start(
                    lines, Mode.COLD, millisecondClock());

            PgDbParser.PreparedProjectIndex cold =
                    parser.prepareProjectIndex(
                            project, monitor, telemetry);
            assertFalse(cold.restoredFromDisk());
            cold.update().commit(project.getName(), monitor);

            assertEquals(1, lines.size());
            String line = lines.getFirst();
            assertTrue(line.contains("phase_load_analyze_ms=1"));
            assertFalse(line.contains("phase_parse_ms="));
            assertFalse(line.contains("phase_analyze_ms="));
        } finally {
            parser.clear();
            if (project.exists()) {
                PgDbParser.clean(project);
                project.delete(true, true, monitor);
            }
        }
    }

    @Test
    void firstSingleFileChangeAfterRestartUsesPersistedIndex(
            @TempDir Path temp) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(
                temp.resolve("restart-project"), monitor);
        PgDbParser seedParser = new PgDbParser();
        PgDbParser restartedParser = new PgDbParser();
        PgDbParser fullParser = new PgDbParser();
        try {
            writeInitialProject(project);
            PgDbParser.PreparedProjectIndex cold =
                    seedParser.prepareProjectIndex(project, monitor);
            assertFalse(cold.restoredFromDisk());
            cold.update().commit(project.getName(), monitor);
            seedParser.clear();

            IFile function = project.getFile(
                    org.eclipse.core.runtime.Path
                            .fromPortableString(FUNCTION_PATH));
            replaceFunctionBody(function, monitor);
            var listenerEvents = new AtomicInteger();
            restartedParser.addListener(
                    event -> listenerEvents.incrementAndGet());
            List<String> lines = new ArrayList<>();
            var telemetry = ProjectIndexTelemetryTestSupport.start(
                    lines, Mode.INCREMENTAL, millisecondClock());

            PgDbParser.PreparedIncrementalProjectIndex incremental =
                    restartedParser.prepareIncrementalProjectIndex(
                            project, FUNCTION_PATH, monitor, telemetry);

            assertFalse(incremental.fullBuild());
            assertEquals(0, listenerEvents.get());
            incremental.prepared().update().commit(
                    project.getName(), monitor);
            assertEquals(1, listenerEvents.get());
            assertEquals(1, lines.size());
            assertTrue(lines.getFirst().contains(
                    "persistence_status=published "
                            + "persistence_reason=none"));
            assertTrue(lines.getFirst().contains(
                    "phase_parse_ms=1 phase_analyze_ms=1"));
            assertFalse(lines.getFirst().contains(
                    "phase_load_analyze_ms="));

            fullParser.prepareFullDBFromPgDbProject(project, monitor)
                    .commit(project.getName(), monitor);
            assertEquals(semanticSnapshot(fullParser),
                    semanticSnapshot(restartedParser));
        } finally {
            seedParser.clear();
            restartedParser.clear();
            fullParser.clear();
            if (project.exists()) {
                PgDbParser.clean(project);
                project.delete(true, true, monitor);
            }
        }
    }

    /**
     * A build runs by the configuration it was handed, not by one it reads
     * for itself.
     *
     * <p>This is the half of the build cycle that cannot be seen from the
     * builder: the configuration is threaded in, and nothing about the call
     * itself says whether it arrived. What says so is the identity. A build
     * stamps its identity from the configuration it works by, and
     * {@code EclipseProjectIndexInputs.requireCurrentIdentity} re-derives the
     * identity from the node and refuses a build whose own no longer matches
     * it. So the build below is handed a configuration that differs from the
     * node, and the refusal is the proof: a build that read the node for
     * itself would have stamped the node's identity, matched, and finished.
     * The refusal cannot be produced by a configuration that never
     * arrived.</p>
     *
     * <p>The two differ in one field: an exclusion naming a schema this
     * project does not have. Everything else is carried across from the real
     * reading, so the enumeration, the parse and the analysis are identical
     * to the control in every respect but the fingerprint - the refusal is
     * about the configuration and cannot be some other disagreement wearing
     * its name.</p>
     *
     * <p>The identity check is not the publication guard, and reaching it
     * first is the point rather than a detail: a build whose configuration
     * moved is stopped while it is still working, and never gets as far as
     * asking whether it may publish.</p>
     */
    @Test
    void anIncrementalBuildWorksByTheConfigurationItWasHanded(
            @TempDir Path temp) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject moved = createProject(
                temp.resolve("moved/incremental"), monitor);
        IProject settled = createProject(
                temp.resolve("settled/incremental"), monitor);
        try {
            assertThrows(
                    ProjectIndexBuildCoordinator.StaleBuildException.class,
                    () -> incrementalLine(moved, movedConfiguration(moved),
                            monitor),
                    "a build handed a configuration the workbench has left "
                            + "ran as though it had not been");
            assertTrue(incrementalLine(settled,
                    settledConfiguration(settled), monitor)
                            .contains("persistence_status=published"),
                    "the same build, by the configuration the node holds, "
                            + "did not finish");
        } finally {
            cleanUpProject(moved, monitor);
            cleanUpProject(settled, monitor);
        }
    }

    /**
     * The same question of the other entry point a builder uses. See
     * {@link #anIncrementalBuildWorksByTheConfigurationItWasHanded} for why a
     * refusal is what proves the configuration arrived.
     */
    @Test
    void aRepairedBuildWorksByTheConfigurationItWasHanded(
            @TempDir Path temp) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject moved = createProject(
                temp.resolve("moved/repaired"), monitor);
        IProject settled = createProject(
                temp.resolve("settled/repaired"), monitor);
        try {
            assertThrows(
                    ProjectIndexBuildCoordinator.StaleBuildException.class,
                    () -> repairedLine(moved, movedConfiguration(moved),
                            monitor),
                    "a build handed a configuration the workbench has left "
                            + "ran as though it had not been");
            assertTrue(repairedLine(settled,
                    settledConfiguration(settled), monitor)
                            .contains("persistence_status=published"),
                    "the same build, by the configuration the node holds, "
                            + "did not finish");
        } finally {
            cleanUpProject(moved, monitor);
            cleanUpProject(settled, monitor);
        }
    }

    /**
     * Seeds a project with a published index, edits one file, and prepares
     * the incremental build a builder would prepare for that edit.
     *
     * @param configuration what the build is handed to work by
     * @return the one line that build published
     */
    private static String incrementalLine(IProject project,
            ProjectIndexBuildConfiguration configuration,
            NullProgressMonitor monitor) throws Exception {
        PgDbParser seedParser = new PgDbParser();
        PgDbParser parser = new PgDbParser();
        try {
            seedIndexedProject(project, seedParser, monitor);
            replaceFunctionBody(project.getFile(
                    org.eclipse.core.runtime.Path
                            .fromPortableString(FUNCTION_PATH)),
                    monitor);

            List<String> lines = new ArrayList<>();
            var telemetry = ProjectIndexTelemetryTestSupport.start(
                    lines, Mode.INCREMENTAL);
            parser.prepareIncrementalProjectIndex(project,
                    List.of(FUNCTION_PATH), monitor, telemetry, null,
                    configuration)
                    .prepared().update().commit(project.getName(), monitor);

            assertEquals(1, lines.size());
            return lines.getFirst();
        } finally {
            seedParser.clear();
            parser.clear();
        }
    }

    /**
     * The same, for the entry point a full build of the cycle reaches.
     *
     * @param configuration what the build is handed to work by
     * @return the one line that build published
     */
    private static String repairedLine(IProject project,
            ProjectIndexBuildConfiguration configuration,
            NullProgressMonitor monitor) throws Exception {
        PgDbParser seedParser = new PgDbParser();
        PgDbParser parser = new PgDbParser();
        try {
            seedIndexedProject(project, seedParser, monitor);
            replaceFunctionBody(project.getFile(
                    org.eclipse.core.runtime.Path
                            .fromPortableString(FUNCTION_PATH)),
                    monitor);

            List<String> lines = new ArrayList<>();
            var telemetry = ProjectIndexTelemetryTestSupport.start(
                    lines, Mode.COLD);
            parser.prepareRepairedProjectIndex(project, monitor, telemetry,
                    null, configuration)
                    .update().commit(project.getName(), monitor);

            assertEquals(1, lines.size());
            return lines.getFirst();
        } finally {
            seedParser.clear();
            parser.clear();
        }
    }

    private static void seedIndexedProject(IProject project,
            PgDbParser seedParser, NullProgressMonitor monitor)
            throws Exception {
        writeInitialProject(project);
        seedParser.prepareProjectIndex(project, monitor).update()
                .commit(project.getName(), monitor);
        seedParser.clear();
    }

    /** Exactly what a build cycle captures for itself. */
    private static ProjectIndexBuildConfiguration settledConfiguration(
            IProject project) {
        return ProjectIndexBuildConfiguration.capture(
                () -> PgDbParser.getEffectiveProjectIndexConfiguration(
                        project));
    }

    /**
     * The settled configuration with one field moved: a schema this project
     * does not have, so nothing is enumerated, parsed or analysed
     * differently, and the fingerprint is the only thing that changes.
     */
    private static ProjectIndexBuildConfiguration movedConfiguration(
            IProject project) {
        ProjectIndexConfiguration settled =
                PgDbParser.getEffectiveProjectIndexConfiguration(project);
        return ProjectIndexBuildConfiguration.capture(
                () -> new ProjectIndexConfiguration(settled.databaseType(),
                        settled.projectPreferencesEnabled(),
                        settled.ignorePrivileges(),
                        settled.bodyDependencies(),
                        settled.incrementalAddedFiles(),
                        settled.receiveOnly(),
                        "no_such_schema_in_this_project"));
    }

    private static void cleanUpProject(IProject project,
            NullProgressMonitor monitor) throws Exception {
        if (project.exists()) {
            PgDbParser.clean(project);
            project.delete(true, true, monitor);
        }
    }

    @Test
    void trustedBuilderContinuityAvoidsProjectEnumerationAcrossSequentialEdits(
            @TempDir Path temp) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(
                temp.resolve("trusted-continuity-project"), monitor);
        PgDbParser parser = new PgDbParser();
        PgDbParser fullParser = new PgDbParser();
        try {
            writeInitialProject(project);
            Scenario scenario =
                    ProjectBuildContinuityTestSupport
                            .acceptedReconciliation(1);
            BuildContinuityProof reconciliation =
                    scenario.nextReconciliation();
            PgDbParser.PreparedProjectIndex cold =
                    parser.prepareProjectIndex(
                            project, monitor, reconciliation);
            cold.update().commit(project.getName(), monitor);
            assertTrue(cold.update().wasPublished());
            assertTrue(cold.update().wasContinuityAccepted());
            assertTrue(scenario.accept(reconciliation));

            BuildContinuityProof noOp = scenario.nextNoOp();
            assertTrue(scenario.accept(noOp));

            IFile function = project.getFile(
                    org.eclipse.core.runtime.Path
                            .fromPortableString(FUNCTION_PATH));
            replaceFunctionBody(function, monitor);
            assertTrustedIncremental(parser, project, monitor,
                    scenario, FUNCTION_PATH);

            replaceFunctionBody(function, "item", monitor);
            assertTrustedIncremental(parser, project, monitor,
                    scenario, FUNCTION_PATH);

            IFile table = project.getFile(
                    org.eclipse.core.runtime.Path
                            .fromPortableString(TABLE_PATH));
            replaceIndexColumns(table, monitor);
            assertTrustedIncremental(parser, project, monitor,
                    scenario, TABLE_PATH);

            fullParser.prepareFullDBFromPgDbProject(project, monitor)
                    .commit(project.getName(), monitor);
            assertEquals(semanticSnapshot(fullParser),
                    semanticSnapshot(parser));
        } finally {
            parser.clear();
            fullParser.clear();
            if (project.exists()) {
                PgDbParser.clean(project);
                project.delete(true, true, monitor);
            }
        }
    }

    @Test
    void trustedTwoFileBatchPublishesAtomicallyAndMatchesFullParser(
            @TempDir Path temp) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(
                temp.resolve("trusted-batch-project"), monitor);
        PgDbParser parser = new PgDbParser();
        PgDbParser fullParser = new PgDbParser();
        try {
            writeInitialProject(project);
            Scenario scenario =
                    establishContinuity(parser, project, monitor);
            IFile function = project.getFile(
                    org.eclipse.core.runtime.Path
                            .fromPortableString(FUNCTION_PATH));
            IFile table = project.getFile(
                    org.eclipse.core.runtime.Path
                            .fromPortableString(TABLE_PATH));
            function.createMarker(UIConsts.MARKER.ERROR);
            table.createMarker(UIConsts.MARKER.ERROR);
            replaceFunctionBody(function, monitor);
            replaceIndexColumns(table, monitor);

            BuildContinuityProof proof = scenario.nextBatch(
                    List.of(FUNCTION_PATH, TABLE_PATH));
            List<String> lines = new ArrayList<>();
            var telemetry = ProjectIndexTelemetryTestSupport.start(
                    lines, Mode.INCREMENTAL, millisecondClock());
            var listenerEvents = new AtomicInteger();
            parser.addListener(
                    event -> listenerEvents.incrementAndGet());

            PgDbParser.PreparedIncrementalProjectIndex incremental =
                    parser.prepareIncrementalProjectIndex(
                            project,
                            List.of(TABLE_PATH, FUNCTION_PATH),
                            monitor, telemetry, proof);

            assertFalse(incremental.fullBuild());
            assertEquals(0, listenerEvents.get());
            assertEquals(1, errorMarkerCount(function));
            assertEquals(1, errorMarkerCount(table));

            PgDbParser.PreparedUpdate update =
                    incremental.prepared().update();
            update.commit(project.getName(), monitor);

            assertTrue(update.wasPublished());
            assertTrue(update.wasContinuityAccepted());
            assertTrue(scenario.accept(proof));
            assertEquals(1, listenerEvents.get());
            assertEquals(0, errorMarkerCount(function));
            assertEquals(0, errorMarkerCount(table));
            assertEquals(1, lines.size());
            String line = lines.getFirst();
            assertTrue(line.contains("paths_enumerated=2"));
            assertTrue(line.contains("enumeration_passes=0"));
            assertTrue(line.contains("single_file_validations=8"));
            assertTrue(line.contains(
                    "paths_parsed=2 paths_analyzed=2"));

            fullParser.prepareFullDBFromPgDbProject(project, monitor)
                    .commit(project.getName(), monitor);
            assertEquals(semanticSnapshot(fullParser),
                    semanticSnapshot(parser));
        } finally {
            parser.clear();
            fullParser.clear();
            if (project.exists()) {
                PgDbParser.clean(project);
                project.delete(true, true, monitor);
            }
        }
    }

    /**
     * The saved file did not parse. The index refuses it - that part is
     * deliberate and stays - but it no longer answers by reading the whole
     * project: nothing about the index moved, so nothing is rebuilt, and the
     * live index stays the incremental one the next build will repair from.
     * The red underline is drawn here, because on this path nobody else
     * draws it.
     */
    @Test
    void aFileThatDidNotParseKeepsTheIndexAndDrawsItsMarkers(
            @TempDir Path temp) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(
                temp.resolve("unparsed-file-project"), monitor);
        PgDbParser parser = new PgDbParser();
        try {
            writeInitialProject(project);
            Scenario scenario =
                    establishContinuity(parser, project, monitor);
            IFile function = project.getFile(
                    org.eclipse.core.runtime.Path
                            .fromPortableString(FUNCTION_PATH));
            IFile unchangedTable = project.getFile(
                    org.eclipse.core.runtime.Path
                            .fromPortableString(TABLE_PATH));
            function.createMarker(UIConsts.MARKER.ERROR);
            unchangedTable.createMarker(UIConsts.MARKER.ERROR);
            appendUnparsableTail(function, monitor);
            BuildContinuityProof proof =
                    scenario.nextSingle(FUNCTION_PATH);
            List<String> lines = new ArrayList<>();
            var telemetry = ProjectIndexTelemetryTestSupport.start(
                    lines, Mode.INCREMENTAL, millisecondClock());
            var listenerEvents = new AtomicInteger();
            parser.addListener(
                    event -> listenerEvents.incrementAndGet());

            PgDbParser.PreparedIncrementalProjectIndex incremental =
                    parser.prepareIncrementalProjectIndex(project,
                            List.of(FUNCTION_PATH), monitor, telemetry,
                            proof);

            assertFalse(incremental.fullBuild(),
                    "an unparsed file still forced a full rebuild");
            assertEquals(List.of(Optional.<String>empty()),
                    errorMarkerMessages(function));

            PgDbParser.PreparedUpdate update =
                    incremental.prepared().update();
            update.commit(project.getName(), monitor);

            assertFalse(update.wasPublished());
            assertFalse(update.wasContinuityAccepted());
            // The model did not move, so nobody is told it did.
            assertEquals(0, listenerEvents.get());
            List<Optional<String>> drawn = errorMarkerMessages(function);
            assertFalse(drawn.isEmpty(),
                    "the user was left with no error marker at all");
            assertTrue(drawn.stream().allMatch(Optional::isPresent),
                    "a marker survived that no error accounts for: " + drawn);
            assertEquals(1, errorMarkerCount(unchangedTable),
                    "a file outside the batch lost its marker");
            assertEquals(1, lines.size());
            String line = lines.getFirst();
            assertTrue(line.contains(
                    "batch_abandoned=batch_analysis_errors"), line);
            assertTrue(line.contains("bypass_reason=analysis_errors"), line);
            // The point of the whole change: the live index is still the one
            // the next build can repair, instead of a plain storage that
            // sends every later save down the cold path.
            assertNotNull(parser.incrementalIndexForTests());
        } finally {
            parser.clear();
            if (project.exists()) {
                PgDbParser.clean(project);
                project.delete(true, true, monitor);
            }
        }
    }

    /**
     * What the correction costs. The build that refused the unparsed file
     * accepted no continuity, so the next one reconciles - and reconciling
     * finds the one file that moved and repairs it, because the index it is
     * reconciling against is still there. That is the difference between this
     * and reading two thousand files twice.
     */
    @Test
    void theCorrectionAfterAnUnparsedFileIsRepairedRatherThanRebuilt(
            @TempDir Path temp) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(
                temp.resolve("unparsed-repair-project"), monitor);
        PgDbParser parser = new PgDbParser();
        PgDbParser fullParser = new PgDbParser();
        try {
            writeInitialProject(project);
            Scenario scenario =
                    establishContinuity(parser, project, monitor);
            IFile function = project.getFile(
                    org.eclipse.core.runtime.Path
                            .fromPortableString(FUNCTION_PATH));
            appendUnparsableTail(function, monitor);
            BuildContinuityProof broken =
                    scenario.nextSingle(FUNCTION_PATH);
            PgDbParser.PreparedUpdate refused =
                    parser.prepareIncrementalProjectIndex(project,
                            List.of(FUNCTION_PATH), monitor,
                            ProjectIndexTelemetryTestSupport.start(
                                    new ArrayList<>(), Mode.INCREMENTAL,
                                    millisecondClock()),
                            broken).prepared().update();
            refused.commit(project.getName(), monitor);
            assertFalse(refused.wasContinuityAccepted());
            assertTrue(scenario.invalidate(broken));

            replaceFunctionBody(function, monitor);
            List<String> lines = new ArrayList<>();
            var telemetry = ProjectIndexTelemetryTestSupport.start(
                    lines, Mode.COLD, millisecondClock());
            PgDbParser.PreparedUpdate repaired =
                    parser.prepareRepairedProjectIndex(project, monitor,
                            telemetry, scenario.nextReconciliation())
                            .update();
            repaired.commit(project.getName(), monitor);

            assertTrue(repaired.wasPublished());
            assertEquals(1, lines.size());
            String line = lines.getFirst();
            assertTrue(line.contains("mode=incremental"), line);
            assertTrue(line.contains("repair=applied"), line);
            assertEquals(0, errorMarkerCount(function));

            fullParser.prepareFullDBFromPgDbProject(project, monitor)
                    .commit(project.getName(), monitor);
            assertEquals(semanticSnapshot(fullParser),
                    semanticSnapshot(parser));
        } finally {
            parser.clear();
            fullParser.clear();
            if (project.exists()) {
                PgDbParser.clean(project);
                project.delete(true, true, monitor);
            }
        }
    }

    /**
     * Which publications are worth telling a per-project cache about. A whole
     * index is: every answer in it may have moved, including answers about
     * files nothing happened to. An increment is not: it moved the files it
     * named, and whoever cares about those was told by the resource delta.
     */
    @Test
    void onlyAWholeProjectIndexIsAnnouncedAsPublished(@TempDir Path temp)
            throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(
                temp.resolve("publication-listener-project"), monitor);
        PgDbParser parser = new PgDbParser();
        var announced = new ArrayList<IProject>();
        Consumer<IProject> listener = announced::add;
        PgDbParser.addProjectIndexPublicationListener(listener);
        try {
            writeInitialProject(project);
            Scenario scenario =
                    establishContinuity(parser, project, monitor);

            assertEquals(List.of(project), announced,
                    "a whole project index was published unannounced");

            IFile function = project.getFile(
                    org.eclipse.core.runtime.Path
                            .fromPortableString(FUNCTION_PATH));
            replaceFunctionBody(function, monitor);
            BuildContinuityProof proof =
                    scenario.nextSingle(FUNCTION_PATH);
            PgDbParser.PreparedUpdate update =
                    parser.prepareIncrementalProjectIndex(project,
                            List.of(FUNCTION_PATH), monitor,
                            ProjectIndexTelemetryTestSupport.start(
                                    new ArrayList<>(), Mode.INCREMENTAL,
                                    millisecondClock()),
                            proof).prepared().update();
            update.commit(project.getName(), monitor);

            assertTrue(update.wasPublished());
            assertEquals(List.of(project), announced,
                    "an increment was announced as a whole index");
        } finally {
            PgDbParser.removeProjectIndexPublicationListener(listener);
            parser.clear();
            if (project.exists()) {
                PgDbParser.clean(project);
                project.delete(true, true, monitor);
            }
        }
    }

    @Test
    void batchLargerThanTheBatchLimitFallsBackWithoutPartialPublication(
            @TempDir Path temp) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(
                temp.resolve("oversized-batch-project"), monitor);
        PgDbParser parser = new PgDbParser();
        try {
            writeInitialProject(project);
            parser.prepareProjectIndex(project, monitor)
                    .update().commit(project.getName(), monitor);
            SemanticSnapshot before = semanticSnapshot(parser);
            var paths = new ArrayList<String>();
            int oversized =
                    ProjectIndexIncrementalPlanner.MAX_BATCH_SIZE + 1;
            for (int i = 0; i < oversized; i++) {
                paths.add("SCHEMA/app/FUNCTION/missing_" //$NON-NLS-1$
                        + i + ".sql"); //$NON-NLS-1$
            }

            PgDbParser.PreparedIncrementalProjectIndex fallback =
                    parser.prepareIncrementalProjectIndex(
                            project, paths, monitor);

            assertTrue(fallback.fullBuild());
            fallback.prepared().update().commit(
                    project.getName(), monitor);
            assertTrue(fallback.prepared().update().wasPublished());
            assertEquals(before, semanticSnapshot(parser));
        } finally {
            parser.clear();
            if (project.exists()) {
                PgDbParser.clean(project);
                project.delete(true, true, monitor);
            }
        }
    }

    @Test
    void lostParserTokenPerformsOneFullReconciliationThenReturnsToFastPath(
            @TempDir Path temp) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(
                temp.resolve("lost-continuity-project"), monitor);
        PgDbParser seedParser = new PgDbParser();
        PgDbParser restartedParser = new PgDbParser();
        try {
            writeInitialProject(project);
            Scenario scenario =
                    ProjectBuildContinuityTestSupport
                            .acceptedReconciliation(1);
            BuildContinuityProof reconciliation =
                    scenario.nextReconciliation();
            PgDbParser.PreparedUpdate cold =
                    seedParser.prepareProjectIndex(
                            project, monitor, reconciliation)
                            .update();
            cold.commit(project.getName(), monitor);
            assertTrue(cold.wasContinuityAccepted());
            assertTrue(scenario.accept(reconciliation));
            seedParser.clear();

            IFile function = project.getFile(
                    org.eclipse.core.runtime.Path
                            .fromPortableString(FUNCTION_PATH));
            replaceFunctionBody(function, monitor);
            BuildContinuityProof fallbackProof =
                    scenario.nextSingle(FUNCTION_PATH);
            PgDbParser.PreparedIncrementalProjectIndex fallback =
                    restartedParser.prepareIncrementalProjectIndex(
                            project, FUNCTION_PATH, monitor,
                            fallbackProof);
            assertTrue(fallback.fullBuild());
            PgDbParser.PreparedUpdate fallbackUpdate =
                    fallback.prepared().update();
            fallbackUpdate.commit(project.getName(), monitor);
            assertTrue(fallbackUpdate.wasPublished());
            assertTrue(fallbackUpdate.wasContinuityAccepted());
            assertTrue(scenario.accept(fallbackProof));

            replaceFunctionBody(function, "item", monitor);
            assertTrustedIncremental(restartedParser, project,
                    monitor, scenario, FUNCTION_PATH);
        } finally {
            seedParser.clear();
            restartedParser.clear();
            if (project.exists()) {
                PgDbParser.clean(project);
                project.delete(true, true, monitor);
            }
        }
    }

    @Test
    void trustedWarmReconciliationEnumeratesMetadataExactlyOnce(
            @TempDir Path temp) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(
                temp.resolve("trusted-warm-project"), monitor);
        PgDbParser seedParser = new PgDbParser();
        PgDbParser warmParser = new PgDbParser();
        try {
            writeInitialProject(project);
            seedParser.prepareProjectIndex(project, monitor)
                    .update().commit(project.getName(), monitor);
            seedParser.clear();

            Scenario scenario =
                    ProjectBuildContinuityTestSupport
                            .acceptedReconciliation(1);
            BuildContinuityProof proof =
                    scenario.nextReconciliation();
            List<String> lines = new ArrayList<>();
            var telemetry = ProjectIndexTelemetryTestSupport.start(
                    lines, Mode.COLD, millisecondClock());
            PgDbParser.PreparedProjectIndex warm =
                    warmParser.prepareProjectIndex(
                            project, monitor, telemetry, proof);
            assertTrue(warm.restoredFromDisk());
            warm.update().commit(project.getName(), monitor);
            assertTrue(warm.update().wasContinuityAccepted());
            assertTrue(scenario.accept(proof));

            assertEquals(1, lines.size());
            String line = lines.getFirst();
            assertTrue(line.contains("mode=warm"));
            assertTrue(line.contains("enumeration_passes=1"));
            assertTrue(line.contains(
                    "paths_parsed=0 paths_analyzed=0"));
        } finally {
            seedParser.clear();
            warmParser.clear();
            if (project.exists()) {
                PgDbParser.clean(project);
                project.delete(true, true, monitor);
            }
        }
    }

    @Test
    void trustedStaleWarmCacheReconcilesColdInTheSameBuild(
            @TempDir Path temp) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(
                temp.resolve("trusted-stale-warm-project"), monitor);
        PgDbParser seedParser = new PgDbParser();
        PgDbParser parser = new PgDbParser();
        try {
            writeInitialProject(project);
            seedParser.prepareProjectIndex(project, monitor)
                    .update().commit(project.getName(), monitor);
            seedParser.clear();
            replaceFunctionBody(project.getFile(
                    org.eclipse.core.runtime.Path
                            .fromPortableString(FUNCTION_PATH)),
                    monitor);

            Scenario scenario =
                    ProjectBuildContinuityTestSupport
                            .acceptedReconciliation(1);
            BuildContinuityProof proof =
                    scenario.nextReconciliation();
            PgDbParser.PreparedProjectIndex reconciled =
                    parser.prepareProjectIndex(
                            project, monitor, proof);
            assertFalse(reconciled.restoredFromDisk());
            PgDbParser.PreparedUpdate update = reconciled.update();
            update.commit(project.getName(), monitor);
            assertTrue(update.wasPublished());
            assertTrue(update.wasContinuityAccepted());
            assertTrue(scenario.accept(proof));
        } finally {
            seedParser.clear();
            parser.clear();
            if (project.exists()) {
                PgDbParser.clean(project);
                project.delete(true, true, monitor);
            }
        }
    }

    @Test
    void staleWarmRevisionKeepsNewerContinuityAndSchedulesOneRebuild(
            @TempDir Path temp) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(
                temp.resolve("warm-revision-race-project"), monitor);
        var scheduled = new AtomicInteger();
        PgDbParser parser = new PgDbParser(ignored -> { },
                ignored -> scheduled.incrementAndGet());
        try {
            writeInitialProject(project);
            Scenario scenario = establishContinuity(
                    parser, project, monitor);

            PgDbParser.PreparedProjectIndex stale =
                    parser.prepareProjectIndex(project, monitor);
            assertTrue(stale.restoredFromDisk());

            IFile function = project.getFile(
                    org.eclipse.core.runtime.Path
                            .fromPortableString(FUNCTION_PATH));
            replaceFunctionBody(function, monitor);
            BuildContinuityProof newerProof =
                    scenario.nextReconciliation();
            PgDbParser.PreparedProjectIndex newer =
                    parser.prepareProjectIndex(
                            project, monitor, newerProof);
            assertFalse(newer.restoredFromDisk());
            PgDbParser.PreparedUpdate newerUpdate = newer.update();
            newerUpdate.commit(project.getName(), monitor);
            assertTrue(newerUpdate.wasPublished());
            assertTrue(newerUpdate.wasContinuityAccepted());
            assertTrue(scenario.accept(newerProof));
            SemanticSnapshot liveBeforeStaleCommit =
                    semanticSnapshot(parser);

            PgDbParser.PreparedUpdate update = stale.update();
            update.commit(project.getName(), monitor);

            assertFalse(update.wasPublished());
            assertFalse(update.wasContinuityAccepted());
            assertEquals(liveBeforeStaleCommit,
                    semanticSnapshot(parser));
            assertEquals(1, scheduled.get());

            update.commit(project.getName(), monitor);
            assertEquals(1, scheduled.get());

            replaceFunctionBody(function, "item", monitor);
            assertTrustedIncremental(parser, project, monitor,
                    scenario, FUNCTION_PATH);
            assertEquals(1, scheduled.get());
        } finally {
            parser.clear();
            if (project.exists()) {
                PgDbParser.clean(project);
                project.delete(true, true, monitor);
            }
        }
    }

    @Test
    void trustedChangedFileDriftDoesNotPublishAndForcesReconciliation(
            @TempDir Path temp) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(
                temp.resolve("trusted-drift-project"), monitor);
        PgDbParser parser = new PgDbParser();
        try {
            writeInitialProject(project);
            Scenario scenario = establishContinuity(
                    parser, project, monitor);
            IFile function = project.getFile(
                    org.eclipse.core.runtime.Path
                            .fromPortableString(FUNCTION_PATH));
            replaceFunctionBody(function, monitor);
            BuildContinuityProof proof =
                    scenario.nextSingle(FUNCTION_PATH);
            PgDbParser.PreparedIncrementalProjectIndex incremental =
                    parser.prepareIncrementalProjectIndex(
                            project, FUNCTION_PATH, monitor, proof);
            assertFalse(incremental.fullBuild());

            replaceFunctionBody(function, "item", monitor);
            PgDbParser.PreparedUpdate update =
                    incremental.prepared().update();
            update.commit(project.getName(), monitor);
            assertFalse(update.wasPublished());
            assertFalse(update.wasContinuityAccepted());
            assertTrue(scenario.invalidate(proof));

            replaceFunctionBody(function, monitor);
            BuildContinuityProof recovery =
                    scenario.nextSingle(FUNCTION_PATH);
            PgDbParser.PreparedIncrementalProjectIndex fallback =
                    parser.prepareIncrementalProjectIndex(
                            project, FUNCTION_PATH, monitor, recovery);
            assertTrue(fallback.fullBuild());
            fallback.prepared().update().commit(
                    project.getName(), monitor);
            assertTrue(fallback.prepared().update()
                    .wasContinuityAccepted());
            assertTrue(scenario.accept(recovery));
        } finally {
            parser.clear();
            if (project.exists()) {
                PgDbParser.clean(project);
                project.delete(true, true, monitor);
            }
        }
    }

    @Test
    void driftOfSecondBatchFileRejectsTheWholePublication(
            @TempDir Path temp) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(
                temp.resolve("trusted-batch-drift-project"), monitor);
        var scheduled = new AtomicInteger();
        PgDbParser parser = new PgDbParser(ignored -> { },
                ignored -> scheduled.incrementAndGet());
        try {
            writeInitialProject(project);
            Scenario scenario = establishContinuity(
                    parser, project, monitor);
            SemanticSnapshot baseline = semanticSnapshot(parser);
            IFile function = project.getFile(
                    org.eclipse.core.runtime.Path
                            .fromPortableString(FUNCTION_PATH));
            IFile table = project.getFile(
                    org.eclipse.core.runtime.Path
                            .fromPortableString(TABLE_PATH));
            replaceFunctionBody(function, monitor);
            replaceIndexColumns(table, monitor);
            BuildContinuityProof proof = scenario.nextBatch(
                    List.of(FUNCTION_PATH, TABLE_PATH));
            List<String> lines = new ArrayList<>();
            var telemetry = ProjectIndexTelemetryTestSupport.start(
                    lines, Mode.INCREMENTAL, millisecondClock());
            var listenerEvents = new AtomicInteger();
            parser.addListener(
                    event -> listenerEvents.incrementAndGet());

            PgDbParser.PreparedIncrementalProjectIndex incremental =
                    parser.prepareIncrementalProjectIndex(
                            project,
                            List.of(FUNCTION_PATH, TABLE_PATH),
                            monitor, telemetry, proof);
            assertFalse(incremental.fullBuild());

            try (var input = new ByteArrayInputStream(
                    tableSql("id")
                            .getBytes(StandardCharsets.UTF_8))) {
                table.setContents(input, true, false, monitor);
            }
            PgDbParser.PreparedUpdate update =
                    incremental.prepared().update();
            update.commit(project.getName(), monitor);

            assertFalse(update.wasPublished());
            assertFalse(update.wasContinuityAccepted());
            assertEquals(0, listenerEvents.get());
            assertEquals(baseline, semanticSnapshot(parser));
            assertEquals(1, scheduled.get());
            assertEquals(1, lines.size());
            assertTrue(lines.getFirst().contains(
                    "persistence_status=failed "
                            + "persistence_reason=stale_input"));
        } finally {
            parser.clear();
            if (project.exists()) {
                PgDbParser.clean(project);
                project.delete(true, true, monitor);
            }
        }
    }

    @Test
    void trustedMemoryIndexAlsoAvoidsWholeProjectEnumeration(
            @TempDir Path temp) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(
                temp.resolve("trusted-memory-project"), monitor);
        PgDbParser parser = new PgDbParser();
        Path storeDirectory = null;
        try {
            writeInitialProject(project);
            storeDirectory = createFailingProjectStore(project);
            Scenario scenario = establishContinuity(
                    parser, project, monitor);

            IFile function = project.getFile(
                    org.eclipse.core.runtime.Path
                            .fromPortableString(FUNCTION_PATH));
            replaceFunctionBody(function, monitor);
            BuildContinuityProof proof =
                    scenario.nextSingle(FUNCTION_PATH);
            List<String> lines = new ArrayList<>();
            var telemetry = ProjectIndexTelemetryTestSupport.start(
                    lines, Mode.INCREMENTAL, millisecondClock());
            PgDbParser.PreparedIncrementalProjectIndex incremental =
                    parser.prepareIncrementalProjectIndex(
                            project, FUNCTION_PATH, monitor,
                            telemetry, proof);
            assertFalse(incremental.fullBuild());
            PgDbParser.PreparedUpdate update =
                    incremental.prepared().update();
            update.commit(project.getName(), monitor);
            assertTrue(update.wasContinuityAccepted());
            assertTrue(scenario.accept(proof));

            assertEquals(1, lines.size());
            String line = lines.getFirst();
            assertTrue(line.contains("mode=memory_incremental"));
            assertTrue(line.contains("enumeration_passes=0"));
            assertTrue(line.contains("single_file_validations=4"));
        } finally {
            parser.clear();
            cleanupProject(project, storeDirectory, monitor);
        }
    }

    @Test
    void trustedMemoryIndexPublishesTwoFileBatchAsOneGeneration(
            @TempDir Path temp) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(
                temp.resolve("trusted-memory-batch-project"), monitor);
        PgDbParser parser = new PgDbParser();
        PgDbParser fullParser = new PgDbParser();
        Path storeDirectory = null;
        try {
            writeInitialProject(project);
            storeDirectory = createFailingProjectStore(project);
            Scenario scenario = establishContinuity(
                    parser, project, monitor);
            StoreArtifact before = storeArtifact(storeDirectory);
            IFile function = project.getFile(
                    org.eclipse.core.runtime.Path
                            .fromPortableString(FUNCTION_PATH));
            IFile table = project.getFile(
                    org.eclipse.core.runtime.Path
                            .fromPortableString(TABLE_PATH));
            replaceFunctionBody(function, monitor);
            replaceIndexColumns(table, monitor);
            BuildContinuityProof proof = scenario.nextBatch(
                    List.of(FUNCTION_PATH, TABLE_PATH));
            List<String> lines = new ArrayList<>();
            var telemetry = ProjectIndexTelemetryTestSupport.start(
                    lines, Mode.INCREMENTAL, millisecondClock());
            var listenerEvents = new AtomicInteger();
            parser.addListener(
                    event -> listenerEvents.incrementAndGet());

            PgDbParser.PreparedIncrementalProjectIndex incremental =
                    parser.prepareIncrementalProjectIndex(
                            project,
                            List.of(TABLE_PATH, FUNCTION_PATH),
                            monitor, telemetry, proof);
            assertFalse(incremental.fullBuild());
            PgDbParser.PreparedUpdate update =
                    incremental.prepared().update();
            update.commit(project.getName(), monitor);

            assertTrue(update.wasPublished());
            assertTrue(update.wasContinuityAccepted());
            assertTrue(scenario.accept(proof));
            assertEquals(1, listenerEvents.get());
            assertEquals(1, lines.size());
            String line = lines.getFirst();
            assertTrue(line.contains("mode=memory_incremental"));
            assertTrue(line.contains("enumeration_passes=0"));
            assertTrue(line.contains("single_file_validations=8"));
            assertTrue(line.contains(
                    "paths_parsed=2 paths_analyzed=2"));
            assertStoreArtifact(before,
                    storeArtifact(storeDirectory));

            fullParser.prepareFullDBFromPgDbProject(project, monitor)
                    .commit(project.getName(), monitor);
            assertEquals(semanticSnapshot(fullParser),
                    semanticSnapshot(parser));
        } finally {
            parser.clear();
            fullParser.clear();
            cleanupProject(project, storeDirectory, monitor);
        }
    }

    @Test
    void discardingOlderPreparedBuildDoesNotClearNewerAcceptedContinuity(
            @TempDir Path temp) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(
                temp.resolve("continuity-race-project"), monitor);
        PgDbParser parser = new PgDbParser();
        try {
            writeInitialProject(project);
            Scenario scenario =
                    ProjectBuildContinuityTestSupport
                            .acceptedReconciliation(1);
            BuildContinuityProof oldProof =
                    scenario.nextReconciliation();
            PgDbParser.PreparedUpdate oldUpdate =
                    parser.prepareProjectIndex(
                            project, monitor, oldProof).update();

            BuildContinuityProof newerProof =
                    scenario.nextReconciliation();
            PgDbParser.PreparedUpdate newerUpdate =
                    parser.prepareProjectIndex(
                            project, monitor, newerProof).update();
            newerUpdate.commit(project.getName(), monitor);
            assertTrue(newerUpdate.wasContinuityAccepted());
            assertTrue(scenario.accept(newerProof));

            oldUpdate.discard();

            IFile function = project.getFile(
                    org.eclipse.core.runtime.Path
                            .fromPortableString(FUNCTION_PATH));
            replaceFunctionBody(function, monitor);
            assertTrustedIncremental(parser, project, monitor,
                    scenario, FUNCTION_PATH);
        } finally {
            parser.clear();
            if (project.exists()) {
                PgDbParser.clean(project);
                project.delete(true, true, monitor);
            }
        }
    }

    private static LongSupplier millisecondClock() {
        AtomicLong now = new AtomicLong();
        return () -> now.getAndAdd(1_000_000L);
    }

    @Test
    void firstIndexChangeInsideTableFileAfterRestartUsesPersistedIndex(
            @TempDir Path temp) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(
                temp.resolve("restart-index-project"), monitor);
        PgDbParser seedParser = new PgDbParser();
        PgDbParser restartedParser = new PgDbParser();
        PgDbParser fullParser = new PgDbParser();
        try {
            writeInitialProject(project);
            PgDbParser.PreparedProjectIndex cold =
                    seedParser.prepareProjectIndex(project, monitor);
            assertFalse(cold.restoredFromDisk());
            cold.update().commit(project.getName(), monitor);
            seedParser.clear();

            IFile table = project.getFile(
                    org.eclipse.core.runtime.Path
                            .fromPortableString(TABLE_PATH));
            replaceIndexColumns(table, monitor);

            PgDbParser.PreparedIncrementalProjectIndex incremental =
                    restartedParser.prepareIncrementalProjectIndex(
                            project, TABLE_PATH, monitor);

            assertFalse(incremental.fullBuild());
            incremental.prepared().update().commit(
                    project.getName(), monitor);
            fullParser.prepareFullDBFromPgDbProject(project, monitor)
                    .commit(project.getName(), monitor);
            assertEquals(semanticSnapshot(fullParser),
                    semanticSnapshot(restartedParser));
        } finally {
            seedParser.clear();
            restartedParser.clear();
            fullParser.clear();
            if (project.exists()) {
                PgDbParser.clean(project);
                project.delete(true, true, monitor);
            }
        }
    }

    @Test
    void discardingFirstRestartIncrementalKeepsPersistedBaselineCurrent(
            @TempDir Path temp) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(
                temp.resolve("restart-discard-project"),
                monitor);
        PgDbParser seedParser = new PgDbParser();
        PgDbParser restartedParser = new PgDbParser();
        PgDbParser warmParser = new PgDbParser();
        try {
            writeInitialProject(project);
            seedParser.prepareProjectIndex(project, monitor)
                    .update().commit(project.getName(), monitor);
            seedParser.clear();

            IFile function = project.getFile(
                    org.eclipse.core.runtime.Path
                            .fromPortableString(FUNCTION_PATH));
            replaceFunctionBody(function, monitor);
            var listenerEvents = new AtomicInteger();
            restartedParser.addListener(
                    event -> listenerEvents.incrementAndGet());
            List<String> lines = new ArrayList<>();
            var telemetry = ProjectIndexTelemetryTestSupport.start(
                    lines, Mode.INCREMENTAL);

            PgDbParser.PreparedIncrementalProjectIndex incremental =
                    restartedParser.prepareIncrementalProjectIndex(
                            project, FUNCTION_PATH, monitor, telemetry);
            assertFalse(incremental.fullBuild());
            incremental.prepared().update().discard();
            assertEquals(0, listenerEvents.get());
            assertEquals(1, lines.size());
            assertTrue(lines.getFirst().contains(
                    "persistence_status=not_attempted "
                            + "persistence_reason=none"));
            assertFalse(lines.getFirst().contains(
                    "persistence_status=memory_only"));

            replaceFunctionBody(function, "item", monitor);
            PgDbParser.PreparedProjectIndex warm =
                    warmParser.prepareProjectIndex(project, monitor);
            assertTrue(warm.restoredFromDisk());
            warm.update().commit(project.getName(), monitor);
        } finally {
            seedParser.clear();
            restartedParser.clear();
            warmParser.clear();
            if (project.exists()) {
                PgDbParser.clean(project);
                project.delete(true, true, monitor);
            }
        }
    }

    @Test
    void liveRejectionReasonDistinguishesCancellationFromStaleEpoch() {
        assertEquals(PersistenceReason.CANCELLED,
                PgDbParser.liveRejectionReason(true));
        assertEquals(PersistenceReason.STALE_INPUT,
                PgDbParser.liveRejectionReason(false));
    }

    @Test
    void changedInputBeforeAppendReportsStaleInsteadOfNotAttempted(
            @TempDir Path temp) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(
                temp.resolve("pre-append-stale-project"), monitor);
        PgDbParser seedParser = new PgDbParser();
        PgDbParser restartedParser = new PgDbParser();
        try {
            writeInitialProject(project);
            seedParser.prepareProjectIndex(project, monitor)
                    .update().commit(project.getName(), monitor);
            seedParser.clear();

            IFile function = project.getFile(
                    org.eclipse.core.runtime.Path
                            .fromPortableString(FUNCTION_PATH));
            replaceFunctionBody(function, monitor);
            List<String> lines = new ArrayList<>();
            var telemetry = ProjectIndexTelemetryTestSupport.start(
                    lines, Mode.INCREMENTAL);
            PgDbParser.PreparedIncrementalProjectIndex incremental =
                    restartedParser.prepareIncrementalProjectIndex(
                            project, FUNCTION_PATH, monitor, telemetry);
            assertFalse(incremental.fullBuild());

            replaceFunctionBody(function, "item", monitor);
            incremental.prepared().update().commit(
                    project.getName(), monitor);

            assertEquals(1, lines.size());
            assertTrue(lines.getFirst().contains(
                    "persistence_status=failed "
                            + "persistence_reason=stale_input"));
            assertFalse(lines.getFirst().contains(
                    "persistence_status=not_attempted"));
            assertFalse(lines.getFirst().contains(
                    "persistence_status=memory_only"));
        } finally {
            seedParser.clear();
            restartedParser.clear();
            if (project.exists()) {
                PgDbParser.clean(project);
                project.delete(true, true, monitor);
            }
        }
    }

    @Test
    void replacedLiveIndexBeforeAppendReportsStale(
            @TempDir Path temp) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(
                temp.resolve("pre-append-non-live-project"), monitor);
        PgDbParser seedParser = new PgDbParser();
        PgDbParser restartedParser = new PgDbParser();
        try {
            writeInitialProject(project);
            seedParser.prepareProjectIndex(project, monitor)
                    .update().commit(project.getName(), monitor);
            seedParser.clear();

            IFile function = project.getFile(
                    org.eclipse.core.runtime.Path
                            .fromPortableString(FUNCTION_PATH));
            replaceFunctionBody(function, monitor);
            List<String> lines = new ArrayList<>();
            var telemetry = ProjectIndexTelemetryTestSupport.start(
                    lines, Mode.INCREMENTAL);
            PgDbParser.PreparedIncrementalProjectIndex incremental =
                    restartedParser.prepareIncrementalProjectIndex(
                            project, FUNCTION_PATH, monitor, telemetry);
            assertFalse(incremental.fullBuild());

            restartedParser.replaceReferenceIndexForTests(
                    new ProjectReferencesStorage());
            incremental.prepared().update().commit(
                    project.getName(), monitor);

            assertEquals(1, lines.size());
            assertTrue(lines.getFirst().contains(
                    "persistence_status=failed "
                            + "persistence_reason=stale_input"));
            assertFalse(lines.getFirst().contains(
                    "persistence_status=not_attempted"));
        } finally {
            seedParser.clear();
            restartedParser.clear();
            if (project.exists()) {
                PgDbParser.clean(project);
                project.delete(true, true, monitor);
            }
        }
    }

    @Test
    void exceptionBeforeAppendUsesCentralPersistenceClassifier(
            @TempDir Path temp) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(
                temp.resolve("pre-append-exception-project"), monitor);
        PgDbParser seedParser = new PgDbParser();
        PgDbParser restartedParser = new PgDbParser();
        try {
            writeInitialProject(project);
            seedParser.prepareProjectIndex(project, monitor)
                    .update().commit(project.getName(), monitor);
            seedParser.clear();

            IFile function = project.getFile(
                    org.eclipse.core.runtime.Path
                            .fromPortableString(FUNCTION_PATH));
            replaceFunctionBody(function, monitor);
            List<String> lines = new ArrayList<>();
            var telemetry = ProjectIndexTelemetryTestSupport.start(
                    lines, Mode.INCREMENTAL);
            PgDbParser.PreparedIncrementalProjectIndex incremental =
                    restartedParser.prepareIncrementalProjectIndex(
                            project, FUNCTION_PATH, monitor, telemetry);
            assertFalse(incremental.fullBuild());
            var failingMonitor = new NullProgressMonitor() {
                @Override
                public boolean isCanceled() {
                    throw new UncheckedIOException(
                            new java.io.IOException(
                                    "secret pre-append path"));
                }
            };

            assertThrows(UncheckedIOException.class,
                    () -> incremental.prepared().update().commit(
                            project.getName(), failingMonitor));

            assertEquals(1, lines.size());
            assertTrue(lines.getFirst().contains(
                    "persistence_status=failed "
                            + "persistence_reason=io"));
            assertFalse(lines.getFirst().contains(
                    "persistence_status=not_attempted"));
            assertFalse(lines.getFirst().contains(
                    "secret pre-append path"));
        } finally {
            seedParser.clear();
            restartedParser.clear();
            if (project.exists()) {
                PgDbParser.clean(project);
                project.delete(true, true, monitor);
            }
        }
    }

    @Test
    void explicitAppendCancellationRemainsStickyAfterEpochInvalidation() {
        assertEquals(PersistenceReason.CANCELLED,
                PgDbParser.incrementalInterruptionReason(
                        PersistenceReason.CANCELLED, false, false));
        assertEquals(PersistenceReason.STALE_INPUT,
                PgDbParser.incrementalInterruptionReason(
                        PersistenceReason.NONE, false, false));
        assertEquals(PersistenceReason.CANCELLED,
                PgDbParser.incrementalInterruptionReason(
                        PersistenceReason.NONE, true, false));
        assertEquals(PersistenceReason.CANCELLED,
                PgDbParser.incrementalInterruptionReason(
                        PersistenceReason.NONE, false, true));
    }

    @Test
    void memoryBaselineSupportsTwoSequentialSingleFileEditsWithoutDiskWrites(
            @TempDir Path temp) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(
                temp.resolve("memory-project"), monitor);
        PgDbParser parser = new PgDbParser();
        PgDbParser fullParser = new PgDbParser();
        Path storeDirectory = null;
        try {
            writeInitialProject(project);
            storeDirectory = createFailingProjectStore(project);
            List<String> coldLines = new ArrayList<>();
            var coldTelemetry = ProjectIndexTelemetryTestSupport.start(
                    coldLines, Mode.COLD, millisecondClock());

            PgDbParser.PreparedProjectIndex cold =
                    parser.prepareProjectIndex(project, monitor,
                            coldTelemetry);
            assertFalse(cold.restoredFromDisk());
            cold.update().commit(project.getName(), monitor);
            assertEquals(1, coldLines.size());
            assertTrue(coldLines.getFirst().contains(
                    "mode=cold"));
            assertTrue(coldLines.getFirst().contains(
                    "persistence_status=memory_only "
                            + "persistence_reason=io"));

            StoreArtifact beforeIncremental =
                    storeArtifact(storeDirectory);
            IFile function = project.getFile(
                    org.eclipse.core.runtime.Path
                            .fromPortableString(FUNCTION_PATH));
            replaceFunctionBody(function, "other_item", monitor);
            assertMemoryIncremental(parser, project, monitor);
            replaceFunctionBody(function, "item", monitor);
            assertMemoryIncremental(parser, project, monitor);

            fullParser.prepareFullDBFromPgDbProject(project, monitor)
                    .commit(project.getName(), monitor);
            assertEquals(semanticSnapshot(fullParser),
                    semanticSnapshot(parser));
            assertStoreArtifact(beforeIncremental,
                    storeArtifact(storeDirectory));
        } finally {
            parser.clear();
            fullParser.clear();
            cleanupProject(project, storeDirectory, monitor);
        }
    }

    @Test
    void memoryBaselineRestartsColdAndContextMismatchFallsBackOnce(
            @TempDir Path temp) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(
                temp.resolve("memory-restart-project"), monitor);
        PgDbParser seedParser = new PgDbParser();
        PgDbParser restartedParser = new PgDbParser();
        PgDbParser fullParser = new PgDbParser();
        Path storeDirectory = null;
        try {
            writeInitialProject(project);
            storeDirectory = createFailingProjectStore(project);
            seedParser.prepareProjectIndex(project, monitor)
                    .update().commit(project.getName(), monitor);
            seedParser.clear();

            List<String> restartLines = new ArrayList<>();
            var restartTelemetry =
                    ProjectIndexTelemetryTestSupport.start(
                            restartLines, Mode.COLD,
                            millisecondClock());
            PgDbParser.PreparedProjectIndex restarted =
                    restartedParser.prepareProjectIndex(
                            project, monitor, restartTelemetry);
            assertFalse(restarted.restoredFromDisk());
            restarted.update().commit(project.getName(), monitor);
            assertEquals(1, restartLines.size());
            assertTrue(restartLines.getFirst().contains("mode=cold"));
            assertTrue(restartLines.getFirst().contains(
                    "persistence_status=memory_only "
                            + "persistence_reason=io"));

            StoreArtifact beforeMismatch =
                    storeArtifact(storeDirectory);
            IFile function = project.getFile(
                    org.eclipse.core.runtime.Path
                            .fromPortableString(FUNCTION_PATH));
            IFile table = project.getFile(
                    org.eclipse.core.runtime.Path
                            .fromPortableString(TABLE_PATH));
            replaceFunctionBody(function, monitor);
            replaceIndexColumns(table, monitor);
            List<String> mismatchLines = new ArrayList<>();
            var mismatchTelemetry =
                    ProjectIndexTelemetryTestSupport.start(
                            mismatchLines, Mode.INCREMENTAL,
                            millisecondClock());
            var listenerEvents = new AtomicInteger();
            restartedParser.addListener(
                    event -> listenerEvents.incrementAndGet());

            PgDbParser.PreparedIncrementalProjectIndex mismatch =
                    restartedParser.prepareIncrementalProjectIndex(
                            project, FUNCTION_PATH, monitor,
                            mismatchTelemetry);
            assertTrue(mismatch.fullBuild());
            mismatch.prepared().update().commit(
                    project.getName(), monitor);

            assertEquals(1, listenerEvents.get());
            assertEquals(1, mismatchLines.size());
            String mismatchLine = mismatchLines.getFirst();
            assertTrue(mismatchLine.contains("mode=cold"));
            assertFalse(mismatchLine.contains(
                    "mode=memory_incremental"));
            assertTrue(mismatchLine.contains(
                    "persistence_status=memory_only "
                            + "persistence_reason=io"));
            assertStoreArtifact(beforeMismatch,
                    storeArtifact(storeDirectory));

            fullParser.prepareFullDBFromPgDbProject(project, monitor)
                    .commit(project.getName(), monitor);
            assertEquals(semanticSnapshot(fullParser),
                    semanticSnapshot(restartedParser));
        } finally {
            seedParser.clear();
            restartedParser.clear();
            fullParser.clear();
            cleanupProject(project, storeDirectory, monitor);
        }
    }

    @Test
    void bodyOnlyChangeUsesIncrementalPathAndMatchesIndependentFullParser(
            @TempDir Path temp) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(
                temp.resolve("project"), monitor);
        PgDbParser incrementalParser = new PgDbParser();
        PgDbParser fullParser = new PgDbParser();
        try {
            writeInitialProject(project);
            IFile function = project.getFile(
                    org.eclipse.core.runtime.Path
                            .fromPortableString(FUNCTION_PATH));
            String absoluteFunctionPath =
                    function.getLocation().toOSString();

            PgDbParser.PreparedProjectIndex cold =
                    incrementalParser.prepareProjectIndex(
                            project, monitor);
            assertFalse(cold.restoredFromDisk());
            cold.update().commit(project.getName(), monitor);

            PgDbParser warmParser = new PgDbParser();
            try {
                PgDbParser.PreparedProjectIndex warm =
                        warmParser.prepareProjectIndex(project, monitor);
                assertTrue(warm.restoredFromDisk());
                warm.update().commit(project.getName(), monitor);
                assertEquals(semanticSnapshot(incrementalParser),
                        semanticSnapshot(warmParser));
            } finally {
                warmParser.clear();
            }

            var listenerEvents = new AtomicInteger();
            var listenerSnapshot =
                    new AtomicReference<List<ObjectReference>>();
            incrementalParser.addListener(event -> {
                listenerEvents.incrementAndGet();
                listenerSnapshot.set(referencesForPath(
                        incrementalParser, absoluteFunctionPath));
            });
            IFile unchangedTable = project.getFile(
                    org.eclipse.core.runtime.Path.fromPortableString(
                            "SCHEMA/app/TABLE/item.sql"));
            function.createMarker(UIConsts.MARKER.ERROR);
            unchangedTable.createMarker(UIConsts.MARKER.ERROR);
            replaceFunctionBody(function, monitor);

            PgDbParser.PreparedIncrementalProjectIndex incremental =
                    incrementalParser.prepareIncrementalProjectIndex(
                            project, FUNCTION_PATH, monitor);

            assertFalse(incremental.fullBuild());
            assertEquals(0, listenerEvents.get());
            assertEquals(1, errorMarkerCount(function));

            incremental.prepared().update().commit(
                    project.getName(), monitor);

            assertEquals(1, listenerEvents.get());
            assertEquals(0, errorMarkerCount(function));
            assertEquals(1, errorMarkerCount(unchangedTable));
            assertReferencesOnlyCurrentBody(
                    listenerSnapshot.get());
            assertReferencesOnlyCurrentBody(referencesForPath(
                    incrementalParser, absoluteFunctionPath));

            fullParser.prepareFullDBFromPgDbProject(project, monitor)
                    .commit(project.getName(), monitor);

            assertEquals(semanticSnapshot(fullParser),
                    semanticSnapshot(incrementalParser));
        } finally {
            incrementalParser.clear();
            fullParser.clear();
            if (project.exists()) {
                PgDbParser.clean(project);
                project.delete(true, true, monitor);
            }
        }
    }

    @Test
    void uncertainIncrementalCurrentInvalidatesOnlyExactRevisionAndRebuilds(
            @TempDir Path temp) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(
                temp.resolve("uncertain-incremental-project"), monitor);
        var armed = new java.util.concurrent.atomic.AtomicBoolean();
        var scheduled = new AtomicInteger();
        var oldCurrent = new AtomicReference<byte[]>();
        var storeDirectory = new AtomicReference<Path>();
        Runnable restoreOldWinner = () -> {
            if (!armed.compareAndSet(true, false)) {
                return;
            }
            try {
                Path directory = storeDirectory.get();
                Path temporary =
                        directory.resolve(".older-winner.tmp");
                Files.write(temporary, oldCurrent.get());
                Files.move(temporary, directory.resolve("current"),
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.io.IOException ex) {
                throw new UncheckedIOException(ex);
            }
        };
        ProjectIndexStoreFactory storeFactory = directory ->
                ProjectIndexStoreTestSupport
                        .failCurrentDirectorySync(directory,
                                armed::get, restoreOldWinner);
        PgDbParser parser = new PgDbParser(ignored -> { },
                ignored -> scheduled.incrementAndGet(), storeFactory);
        try {
            writeInitialProject(project);
            parser.prepareProjectIndex(project, monitor)
                    .update().commit(project.getName(), monitor);

            Path directory = projectStoreDirectory(project);
            storeDirectory.set(directory);
            byte[] expectedOldCurrent =
                    Files.readAllBytes(directory.resolve("current"));
            oldCurrent.set(expectedOldCurrent);

            IFile function = project.getFile(
                    org.eclipse.core.runtime.Path
                            .fromPortableString(FUNCTION_PATH));
            replaceFunctionBody(function, monitor);
            List<String> lines = new ArrayList<>();
            var telemetry = ProjectIndexTelemetryTestSupport.start(
                    lines, Mode.INCREMENTAL, millisecondClock());
            PgDbParser.PreparedIncrementalProjectIndex incremental =
                    parser.prepareIncrementalProjectIndex(
                            project, FUNCTION_PATH, monitor, telemetry);
            assertFalse(incremental.fullBuild());

            armed.set(true);
            var failure = assertThrows(
                    ProjectIndexStore.CurrentDurabilityException.class,
                    () -> incremental.prepared().update().commit(
                            project.getName(), monitor));

            assertTrue(failure.revision() != null);
            assertArrayEquals(expectedOldCurrent,
                    Files.readAllBytes(directory.resolve("current")));
            assertEquals(1, scheduled.get());
            assertEquals(1, lines.size());
            assertTrue(lines.getFirst().contains(
                    "persistence_status=failed "
                            + "persistence_reason=io"));
            assertFalse(lines.getFirst().contains(
                    "persistence_status=published"));
        } finally {
            parser.clear();
            if (project.exists()) {
                PgDbParser.clean(project);
                project.delete(true, true, monitor);
            }
        }
    }

    private static void assertTrustedIncremental(PgDbParser parser,
            IProject project, NullProgressMonitor monitor,
            Scenario scenario, String relativePath)
            throws Exception {
        BuildContinuityProof proof =
                scenario.nextSingle(relativePath);
        List<String> lines = new ArrayList<>();
        var telemetry = ProjectIndexTelemetryTestSupport.start(
                lines, Mode.INCREMENTAL, millisecondClock());

        PgDbParser.PreparedIncrementalProjectIndex incremental =
                parser.prepareIncrementalProjectIndex(
                        project, relativePath, monitor,
                        telemetry, proof);

        assertFalse(incremental.fullBuild());
        PgDbParser.PreparedUpdate update =
                incremental.prepared().update();
        update.commit(project.getName(), monitor);
        assertTrue(update.wasPublished());
        assertTrue(update.wasContinuityAccepted());
        assertTrue(scenario.accept(proof));
        assertEquals(1, lines.size());
        String line = lines.getFirst();
        assertTrue(line.contains("paths_enumerated=1"));
        assertTrue(line.contains("enumeration_passes=0"));
        assertTrue(line.contains("single_file_validations=4"));
        assertTrue(line.contains(
                "paths_parsed=1 paths_analyzed=1"));
    }

    private static Scenario establishContinuity(PgDbParser parser,
            IProject project, NullProgressMonitor monitor)
            throws Exception {
        Scenario scenario =
                ProjectBuildContinuityTestSupport
                        .acceptedReconciliation(1);
        BuildContinuityProof proof =
                scenario.nextReconciliation();
        PgDbParser.PreparedUpdate update =
                parser.prepareProjectIndex(project, monitor, proof)
                        .update();
        update.commit(project.getName(), monitor);
        assertTrue(update.wasPublished());
        assertTrue(update.wasContinuityAccepted());
        assertTrue(scenario.accept(proof));
        return scenario;
    }

    private static void assertMemoryIncremental(PgDbParser parser,
            IProject project, NullProgressMonitor monitor)
            throws Exception {
        List<String> lines = new ArrayList<>();
        var telemetry = ProjectIndexTelemetryTestSupport.start(
                lines, Mode.INCREMENTAL, millisecondClock());

        PgDbParser.PreparedIncrementalProjectIndex incremental =
                parser.prepareIncrementalProjectIndex(
                        project, FUNCTION_PATH, monitor, telemetry);

        assertFalse(incremental.fullBuild());
        incremental.prepared().update().commit(
                project.getName(), monitor);
        assertEquals(1, lines.size());
        String line = lines.getFirst();
        assertTrue(line.contains("mode=memory_incremental"));
        assertTrue(line.contains(
                "persistence_status=memory_only "
                        + "persistence_reason=io"));
        assertTrue(line.contains(
                "paths_parsed=1 paths_analyzed=1"));
    }

    private static Path createFailingProjectStore(IProject project)
            throws Exception {
        Path directory = projectStoreDirectory(project);
        if (Files.isDirectory(directory)) {
            PgDbParser.cleanProjectIndexStore(directory);
            Files.deleteIfExists(directory);
        } else {
            Files.deleteIfExists(directory);
        }
        Files.createDirectories(directory.getParent());
        return Files.writeString(directory, "store-sentinel");
    }

    private static Path projectStoreDirectory(IProject project)
            throws Exception {
        Path stateRoot = Path.of(Platform.getStateLocation(
                Activator.getContext().getBundle()).toString());
        return ProjectIndexState.directory(stateRoot,
                project.getLocation().toFile().toPath());
    }

    private static StoreArtifact storeArtifact(Path store)
            throws Exception {
        return new StoreArtifact(Files.readAllBytes(store),
                Files.size(store), Files.getLastModifiedTime(store),
                Files.getAttribute(store, "basic:fileKey"));
    }

    private static void assertStoreArtifact(StoreArtifact expected,
            StoreArtifact actual) {
        assertArrayEquals(expected.contents(), actual.contents());
        assertEquals(expected.size(), actual.size());
        assertEquals(expected.modified(), actual.modified());
        assertEquals(expected.fileKey(), actual.fileKey());
    }

    private static void cleanupProject(IProject project,
            Path storeDirectory, NullProgressMonitor monitor)
            throws Exception {
        if (storeDirectory != null && Files.isRegularFile(storeDirectory)) {
            Files.deleteIfExists(storeDirectory);
        }
        if (project.exists()) {
            PgDbParser.clean(project);
            project.delete(true, true, monitor);
        }
    }

    private static IProject createProject(Path location,
            NullProgressMonitor monitor) throws Exception {
        var workspace = ResourcesPlugin.getWorkspace();
        String name = "pgck-incremental-" //$NON-NLS-1$
                + location.getParent().getFileName();
        IProject project = workspace.getRoot().getProject(name);
        if (project.exists()) {
            project.delete(true, true, monitor);
        }
        IProjectDescription description =
                workspace.newProjectDescription(name);
        description.setLocationURI(location.toUri());
        project.create(description, monitor);
        project.open(monitor);
        return project;
    }

    private static void writeInitialProject(IProject project)
            throws Exception {
        Path root = project.getLocation().toFile().toPath();
        Path schema = Files.createDirectories(
                root.resolve("SCHEMA/app"));
        Path tables = Files.createDirectories(
                schema.resolve("TABLE"));
        Path functions = Files.createDirectories(
                schema.resolve("FUNCTION"));
        Files.writeString(schema.resolve("app.sql"),
                "CREATE SCHEMA app;\n");
        Files.writeString(tables.resolve("item.sql"),
                tableSql("id"));
        Files.writeString(tables.resolve("other_item.sql"),
                "CREATE TABLE app.other_item (id integer);\n");
        Files.writeString(functions.resolve("pick.sql"),
                functionSql("item"));
        project.refreshLocal(IResource.DEPTH_INFINITE,
                new NullProgressMonitor());
    }

    private static void replaceFunctionBody(IFile function,
            NullProgressMonitor monitor) throws Exception {
        replaceFunctionBody(function, "other_item", monitor);
    }

    private static void replaceFunctionBody(IFile function,
            String table, NullProgressMonitor monitor)
            throws Exception {
        try (var input = new ByteArrayInputStream(
                functionSql(table)
                        .getBytes(StandardCharsets.UTF_8))) {
            function.setContents(input, true, false, monitor);
        }
    }

    private static void replaceIndexColumns(IFile table,
            NullProgressMonitor monitor) throws Exception {
        try (var input = new ByteArrayInputStream(
                tableSql("id, payload")
                        .getBytes(StandardCharsets.UTF_8))) {
            table.setContents(input, true, false, monitor);
        }
    }

    private static String tableSql(String indexColumns) {
        return """
                CREATE TABLE app.item (id integer, payload integer);
                CREATE INDEX item_idx ON app.item (%s);
                """.formatted(indexColumns);
    }

    private static String functionSql(String table) {
        return """
                CREATE OR REPLACE FUNCTION app.pick()
                RETURNS integer
                LANGUAGE sql
                AS $function$
                SELECT id FROM app.%s LIMIT 1;
                $function$;
                """.formatted(table);
    }

    private static int errorMarkerCount(IFile function)
            throws Exception {
        return function.findMarkers(UIConsts.MARKER.ERROR,
                false, IResource.DEPTH_ZERO).length;
    }

    /**
     * The message of every error marker on a file, empty where there is none.
     * A marker a test put there carries no message and one a parse error put
     * there always does, which is how "the old markers were cleared and the
     * real ones drawn" is told apart from "nothing was touched".
     */
    private static List<Optional<String>> errorMarkerMessages(IFile file)
            throws Exception {
        var messages = new ArrayList<Optional<String>>();
        for (IMarker marker : file.findMarkers(UIConsts.MARKER.ERROR,
                false, IResource.DEPTH_ZERO)) {
            messages.add(Optional.ofNullable(
                    marker.getAttribute(IMarker.MESSAGE, null)));
        }
        return messages;
    }

    /**
     * Leaves the function exactly as the index holds it and adds a tail no
     * parser can make sense of - the half-typed statement of somebody still
     * writing. The file keeps every definition it had, so nothing about the
     * index moved; what it no longer does is parse.
     */
    private static void appendUnparsableTail(IFile function,
            NullProgressMonitor monitor) throws Exception {
        String broken = functionSql("item") + """

                );
                CREATE
                """;
        try (var input = new ByteArrayInputStream(
                broken.getBytes(StandardCharsets.UTF_8))) {
            function.setContents(input, true, false, monitor);
        }
    }

    private static void assertReferencesOnlyCurrentBody(
            List<ObjectReference> references) {
        assertTrue(references != null);
        assertTrue(references.stream().anyMatch(reference ->
                reference.type() == DbObjType.TABLE
                        && "app".equals(reference.schema())
                        && "other_item".equals(reference.table())));
        assertFalse(references.stream().anyMatch(reference ->
                reference.type() == DbObjType.TABLE
                        && "app".equals(reference.schema())
                        && "item".equals(reference.table())));
    }

    private static List<ObjectReference> referencesForPath(
            PgDbParser parser, String functionPath) {
        return parser
                .getObjsForPath(functionPath).stream()
                .filter(location -> location.getLocationType()
                        == ObjectLocation.LocationType.REFERENCE)
                .map(ObjectLocation::getObjectReference)
                .toList();
    }

    private static SemanticSnapshot semanticSnapshot(
            PgDbParser parser) {
        List<String> definitions = parser.getAllObjDefinitions()
                .map(PgDbParserIncrementalProductionPathTest
                        ::definitionKey)
                .sorted()
                .toList();
        List<String> references = parser.getAllObjReferences()
                .map(PgDbParserIncrementalProductionPathTest
                        ::referenceKey)
                .sorted()
                .toList();
        return new SemanticSnapshot(definitions, references);
    }

    private static String definitionKey(MetaStatement definition) {
        return definition.getClass().getName() + '|'
                + definition.getObjectReference();
    }

    private static String referenceKey(ObjectLocation location) {
        return location.getLocationType() + "|"
                + location.getObjectReference() + '|'
                + location.getAction() + '|'
                + location.getAlias();
    }

    private record SemanticSnapshot(List<String> definitions,
            List<String> references) {
    }

    private record StoreArtifact(byte[] contents, long size,
            java.nio.file.attribute.FileTime modified, Object fileKey) {
    }
}
