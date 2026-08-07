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
package ru.taximaxim.codekeeper.ui.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.runtime.IProgressMonitor;
import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.monitor.IMonitor;

import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetry;
import ru.taximaxim.codekeeper.ui.utils.ProjectBuildProgressSink.Phase;
import ru.taximaxim.codekeeper.ui.utils.ProjectBuildProgressSink.Profile;

class ProjectBuildProgressSinkTest {

    private static final int FILES = 13775;
    private static final int OBJECTS = 44680;
    private static final int BATCH_FILES = 256;

    @Test
    void aColdBuildSpendsTheWholeBudgetAndNeverMovesBackwards() {
        var monitor = new RecordingMonitor();
        var sink = new ProjectBuildProgressSink();
        sink.begin(monitor);

        runColdBuild(sink);
        sink.done();

        assertEquals(ProjectBuildProgressSink.TOTAL_TICKS, monitor.worked);
        assertEquals(0, monitor.negative,
                "progress must never move backwards");
    }

    @Test
    void aWarmBuildSpendsTheWholeBudgetAndNeverMovesBackwards() {
        var monitor = new RecordingMonitor();
        var sink = new ProjectBuildProgressSink();
        sink.begin(monitor);

        sink.profile(Profile.WARM);
        sink.enterPhase(Phase.ENUMERATE);
        sink.filesEnumerated(FILES);
        sink.completePhase(Phase.ENUMERATE);
        sink.enterPhase(Phase.RESTORE);
        sink.completePhase(Phase.RESTORE);
        sink.done();

        assertEquals(ProjectBuildProgressSink.TOTAL_TICKS, monitor.worked);
        assertEquals(0, monitor.negative);
    }

    /**
     * The load phase is 87% of a cold build and Core parses the files before it
     * analyzes the objects they declare. Both halves therefore have to move the
     * bar, or it would sit at one seventh of the load for the three quarters of
     * the build that the analysis takes.
     */
    @Test
    void bothHalvesOfTheLoadMoveTheBar() {
        var monitor = new RecordingMonitor();
        var sink = loadingSink(monitor);
        int loadStart = monitor.worked;

        IMonitor core = sink.coreMonitor();
        core.worked(FILES);
        int afterParse = monitor.worked;

        IMonitor analysis = core.createSubMonitor();
        analysis.setWorkRemaining(OBJECTS);
        analysis.worked(OBJECTS / 2);
        int halfAnalyzed = monitor.worked;
        analysis.worked(OBJECTS - OBJECTS / 2);
        int afterAnalysis = monitor.worked;

        assertTrue(afterParse > loadStart, "parsing must be observable");
        assertTrue(halfAnalyzed > afterParse, "analysis must be observable");
        assertTrue(afterAnalysis > halfAnalyzed,
                "the analysis must move the bar to the end of the load");
        assertTrue(afterParse - loadStart < afterAnalysis - afterParse,
                "the analysis owns the larger share of the load");
    }

    @Test
    void theLoadNamesHowManyFilesAndObjectsItHasFinished() {
        var monitor = new RecordingMonitor();
        var sink = loadingSink(monitor);
        String opening = monitor.subTask;

        IMonitor core = sink.coreMonitor();
        core.worked(FILES / 2);
        String parsing = monitor.subTask;

        IMonitor analysis = core.createSubMonitor();
        analysis.setWorkRemaining(OBJECTS);
        analysis.worked(OBJECTS / 2);
        String analyzing = monitor.subTask;

        assertNotNull(opening);
        assertNotEquals(opening, parsing, "the file count must be readable");
        assertNotEquals(parsing, analyzing,
                "the analysis must name its own counter");
        assertTrue(analyzing.contains("44"),
                () -> "expected the object count, got: " + analyzing);
    }

    /**
     * A cold build enumerates its inputs six times: once before the load and
     * again around packing and publication. Those closing passes must not rewind
     * the bar to where the first enumeration left it.
     */
    @Test
    void aClosingInputRecheckNeitherRewindsNorRenamesThePhase() {
        var monitor = new RecordingMonitor();
        var sink = new ProjectBuildProgressSink();
        sink.begin(monitor);
        runColdBuild(sink);
        int afterPublish = monitor.worked;

        sink.enterPhase(Phase.ENUMERATE);
        sink.completePhase(Phase.ENUMERATE);

        assertEquals(afterPublish, monitor.worked,
                "a re-check may not spend budget the build already spent");
        assertEquals(0, monitor.negative);
        assertTrue(monitor.subTask.startsWith("Index: "),
                () -> "expected an index label, got: " + monitor.subTask);
    }

    @Test
    void everyPhaseNamesItselfWithAPipelinePrefix() {
        var monitor = new RecordingMonitor();
        var sink = new ProjectBuildProgressSink();
        sink.begin(monitor);

        sink.enterPhase(Phase.ENUMERATE);
        assertPrefix("Index: ", monitor.subTask);
        sink.enterPhase(Phase.RESTORE);
        assertPrefix("Index: ", monitor.subTask);
        sink.enterPhase(Phase.LOAD);
        assertPrefix("Parse: ", monitor.subTask);
        sink.enterPhase(Phase.PACK);
        assertPrefix("Index: ", monitor.subTask);
        sink.enterPhase(Phase.PUBLISH);
        assertPrefix("Index: ", monitor.subTask);
    }

    /**
     * An automatic build runs under a job that begins with an empty task name,
     * so the task slot is blank rather than owned, and a surface that renders
     * only that slot shows nothing for the whole build. A full index build of a
     * large project takes the better part of a minute; a minute that looks idle
     * invites the edit that makes the running build lose its race and throw its
     * work away. Every phase therefore has to reach both slots.
     */
    @Test
    void everyPhaseAlsoReachesTheTaskNameSlot() {
        var monitor = new RecordingMonitor();
        var sink = new ProjectBuildProgressSink();
        sink.begin(monitor);

        assertNull(monitor.taskName,
                "nothing is named before a phase is entered");

        sink.enterPhase(Phase.ENUMERATE);
        assertEquals(monitor.subTask, monitor.taskName);
        assertPrefix("Index: ", monitor.taskName);
        sink.enterPhase(Phase.LOAD);
        assertEquals(monitor.subTask, monitor.taskName);
        assertPrefix("Parse: ", monitor.taskName);
        sink.enterPhase(Phase.PUBLISH);
        assertEquals(monitor.subTask, monitor.taskName);
        assertPrefix("Index: ", monitor.taskName);
    }

    /**
     * A cancelled build stops naming itself in both slots at once: a stale
     * phase left standing in the task name would outlive the run that wrote it.
     */
    @Test
    void aCancelledBuildNamesItselfInNeitherSlot() {
        var monitor = new RecordingMonitor();
        var sink = new ProjectBuildProgressSink();
        sink.begin(monitor);
        sink.enterPhase(Phase.ENUMERATE);
        String named = monitor.taskName;
        assertNotNull(named);

        monitor.setCanceled(true);
        sink.enterPhase(Phase.LOAD);

        assertEquals(named, monitor.taskName,
                "a cancelled build may not rename itself");
        assertEquals(named, monitor.subTask,
                "both slots stop together");
    }

    @Test
    void theCoreMonitorCarriesCancellationBothWays() {
        var monitor = new RecordingMonitor();
        var sink = new ProjectBuildProgressSink();
        sink.begin(monitor);
        IMonitor core = sink.coreMonitor();

        core.setCancelled(true);
        assertTrue(monitor.isCanceled(), "core cancellation must reach the build");
        assertTrue(core.isCancelled(), "build cancellation must reach core");
        assertTrue(core.createSubMonitor().isCancelled(),
                "the analysis must observe the same cancellation");
    }

    @Test
    void aCancelledBuildIsLeftWhereItStopped() {
        var monitor = new RecordingMonitor();
        var sink = loadingSink(monitor);
        int beforeCancel = monitor.worked;

        monitor.setCanceled(true);
        sink.coreMonitor().worked(FILES);
        sink.completePhase(Phase.LOAD);
        sink.done();

        assertEquals(beforeCancel, monitor.worked);
    }

    @Test
    void anUnboundSinkIsInert() {
        var sink = new ProjectBuildProgressSink();

        sink.profile(Profile.COLD);
        sink.filesEnumerated(FILES);
        sink.enterPhase(Phase.LOAD);
        sink.coreMonitor().worked(10);
        sink.completePhase(Phase.LOAD);
        sink.done();
    }

    /**
     * Core reports a parsed file from the thread that parsed it, so the sink is
     * written by every parser worker at once.
     */
    @Test
    void concurrentParserLanesNeverOverrunTheBudget()
            throws InterruptedException {
        var monitor = new RecordingMonitor();
        var sink = loadingSink(monitor);
        IMonitor core = sink.coreMonitor();
        var start = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();

        var lanes = new Thread[6];
        for (int lane = 0; lane < lanes.length; lane++) {
            lanes[lane] = worker(start, failure, () -> {
                for (int i = 0; i < FILES; i++) {
                    core.worked(1);
                }
            });
        }
        start.countDown();
        for (Thread lane : lanes) {
            lane.join();
        }

        assertNull(failure.get());
        assertEquals(0, monitor.negative);
        assertTrue(monitor.worked <= ProjectBuildProgressSink.TOTAL_TICKS,
                "reported " + monitor.worked + " ticks");
    }

    /** The observer must map every phase a project-index run can report. */
    @Test
    void theObserverMapsIndexRunPhasesOntoThePlan() {
        var monitor = new RecordingMonitor();
        var sink = new ProjectBuildProgressSink();
        sink.begin(monitor);
        ProjectIndexTelemetry.Observer observer = sink.observer();

        observer.modeDeclared(ProjectIndexTelemetry.Mode.COLD);
        observer.filesEnumerated(FILES);
        for (ProjectIndexTelemetry.Phase phase
                : ProjectIndexTelemetry.Phase.values()) {
            observer.phaseStarted(phase);
            observer.phaseFinished(phase);
        }

        assertEquals(0, monitor.negative);
        assertNotNull(observer.loadMonitor());
        assertTrue(monitor.worked <= ProjectBuildProgressSink.TOTAL_TICKS);
    }

    /**
     * A warm attempt that finds a changed file falls back to a full load. The
     * budget it already spent on the warm plan must not strand the cold work
     * that follows without observable progress.
     */
    @Test
    void aWarmAttemptThatFallsBackToAColdLoadKeepsReporting() {
        var monitor = new RecordingMonitor();
        var sink = new ProjectBuildProgressSink();
        sink.begin(monitor);
        sink.profile(Profile.WARM);
        sink.enterPhase(Phase.ENUMERATE);
        sink.filesEnumerated(FILES);
        sink.completePhase(Phase.ENUMERATE);
        int afterWarmEnumerate = monitor.worked;

        sink.profile(Profile.COLD);
        sink.enterPhase(Phase.LOAD);
        sink.coreMonitor().worked(FILES);
        int duringColdLoad = monitor.worked;

        assertTrue(duringColdLoad > afterWarmEnumerate,
                "the cold load must still have observable progress");
        assertTrue(monitor.worked < ProjectBuildProgressSink.TOTAL_TICKS,
                "the cold load must not consume the whole budget");
        assertEquals(0, monitor.negative);
    }

    /**
     * An incremental build parses its batch one file at a time and reports each
     * of them as a finished load. Without a counter of the batch the bar would
     * jump to the end of the load after the first file and stand there for the
     * rest of a batch that runs for tens of seconds.
     */
    @Test
    void anIncrementalBatchCountsItselfOutInsteadOfEndingAtItsFirstFile() {
        var monitor = new RecordingMonitor();
        var sink = batchSink(monitor);
        int loadStart = monitor.worked;

        loadChangedFile(sink);
        int afterFirstFile = monitor.worked;
        String firstLabel = monitor.subTask;
        for (int file = 1; file < BATCH_FILES; file++) {
            loadChangedFile(sink);
        }
        int afterBatch = monitor.worked;

        assertTrue(afterFirstFile > loadStart, "one file must be observable");
        assertTrue(afterBatch - afterFirstFile > afterFirstFile - loadStart,
                "the first file may not consume the whole load");
        assertNotEquals(firstLabel, monitor.subTask,
                "the batch must name how far through it is");
        assertTrue(monitor.subTask.contains(Integer.toString(BATCH_FILES)),
                () -> "expected the batch size, got: " + monitor.subTask);
        assertTrue(monitor.worked <= ProjectBuildProgressSink.TOTAL_TICKS);
        assertEquals(0, monitor.negative);
    }

    /**
     * Folding a full journal rewrites the whole stored index and costs seconds,
     * with no cause the user of a one-file edit can see.
     */
    @Test
    void theFoldOfAFullJournalNamesItselfAndMovesTheBar() {
        var monitor = new RecordingMonitor();
        var sink = batchSink(monitor);
        for (int file = 0; file < BATCH_FILES; file++) {
            loadChangedFile(sink);
        }
        String loading = monitor.subTask;
        int afterLoad = monitor.worked;

        sink.enterPhase(Phase.COMPACT);
        String compacting = monitor.subTask;
        sink.completePhase(Phase.COMPACT);

        assertPrefix("Index: ", compacting);
        assertNotEquals(loading, compacting, "the fold must name itself");
        assertTrue(monitor.worked > afterLoad,
                "the fold must own a share of the run");
        assertTrue(monitor.worked < ProjectBuildProgressSink.TOTAL_TICKS,
                "the publication after the fold must keep a share");
        assertEquals(0, monitor.negative);
    }

    /** The batch reaches the sink through the events an index run publishes. */
    @Test
    void theObserverRoutesTheBatchAndTheFoldOntoThePlan() {
        var monitor = new RecordingMonitor();
        var sink = new ProjectBuildProgressSink();
        sink.begin(monitor);
        ProjectIndexTelemetry.Observer observer = sink.observer();

        observer.modeDeclared(ProjectIndexTelemetry.Mode.INCREMENTAL);
        observer.changedFilesEnumerated(BATCH_FILES);
        observer.phaseStarted(ProjectIndexTelemetry.Phase.PARSE);
        int loadStart = monitor.worked;
        observer.phaseFinished(ProjectIndexTelemetry.Phase.PARSE);
        observer.changedFilesLoaded(1);
        int afterFirstFile = monitor.worked;
        observer.phaseStarted(ProjectIndexTelemetry.Phase.COMPACT);
        int compactionStart = monitor.worked;

        assertTrue(afterFirstFile > loadStart, "the batch must be observable");
        assertTrue(compactionStart > afterFirstFile,
                "the fold must complete the load it follows");
        assertPrefix("Index: ", monitor.subTask);
        assertEquals(0, monitor.negative);
    }

    /**
     * An incremental attempt that falls back to a full build no longer works
     * through a batch, and the batch it already started must not keep the full
     * load it turned into from ever finishing.
     */
    @Test
    void anIncrementalAttemptThatFallsBackDropsItsBatch() {
        var monitor = new RecordingMonitor();
        var sink = batchSink(monitor);
        loadChangedFile(sink);

        sink.profile(Profile.COLD);
        sink.enterPhase(Phase.LOAD);
        sink.filesEnumerated(FILES);
        sink.coreMonitor().worked(FILES);
        int beforeComplete = monitor.worked;
        sink.completePhase(Phase.LOAD);

        assertTrue(monitor.worked > beforeComplete,
                "an abandoned batch may not block the full load from ending");
        assertFalse(monitor.subTask.contains(Integer.toString(BATCH_FILES)),
                () -> "expected the project counter, got: " + monitor.subTask);
        assertEquals(0, monitor.negative);
    }

    /**
     * Nothing in the plugin guarantees which thread publishes an index-run
     * event, and Eclipse monitors are neither thread-safe nor re-entrant.
     */
    @Test
    void concurrentBatchReportsNeverOverrunTheBudget()
            throws InterruptedException {
        var monitor = new RecordingMonitor();
        var sink = batchSink(monitor);
        var start = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();

        var lanes = new Thread[6];
        for (int lane = 0; lane < lanes.length; lane++) {
            lanes[lane] = worker(start, failure, () -> {
                for (int file = 0; file < BATCH_FILES; file++) {
                    loadChangedFile(sink);
                }
            });
        }
        start.countDown();
        for (Thread lane : lanes) {
            lane.join();
        }

        assertNull(failure.get());
        assertEquals(0, monitor.negative);
        assertTrue(monitor.worked <= ProjectBuildProgressSink.TOTAL_TICKS,
                "reported " + monitor.worked + " ticks");
    }

    /** A sink parked at the start of the load phase of an incremental build. */
    private static ProjectBuildProgressSink batchSink(
            RecordingMonitor monitor) {
        var sink = new ProjectBuildProgressSink();
        sink.begin(monitor);
        sink.profile(Profile.INCREMENTAL);
        sink.enterPhase(Phase.ENUMERATE);
        sink.completePhase(Phase.ENUMERATE);
        sink.changedFilesEnumerated(BATCH_FILES);
        sink.enterPhase(Phase.LOAD);
        return sink;
    }

    /** Replays what one file of a batch reports: a parse, an analysis, a file. */
    private static void loadChangedFile(ProjectBuildProgressSink sink) {
        sink.enterPhase(Phase.LOAD);
        sink.completePhase(Phase.LOAD);
        sink.enterPhase(Phase.LOAD);
        sink.completePhase(Phase.LOAD);
        sink.changedFilesLoaded(1);
    }

    private static void assertPrefix(String prefix, String label) {
        assertNotNull(label, "every phase must name itself");
        assertTrue(label.startsWith(prefix),
                () -> "expected '" + prefix + "' prefix, got: " + label);
    }

    /** A sink parked at the start of the load phase of a cold build. */
    private static ProjectBuildProgressSink loadingSink(
            RecordingMonitor monitor) {
        var sink = new ProjectBuildProgressSink();
        sink.begin(monitor);
        sink.profile(Profile.COLD);
        sink.enterPhase(Phase.ENUMERATE);
        sink.filesEnumerated(FILES);
        sink.completePhase(Phase.ENUMERATE);
        sink.enterPhase(Phase.LOAD);
        return sink;
    }

    private static void runColdBuild(ProjectBuildProgressSink sink) {
        sink.profile(Profile.COLD);
        sink.enterPhase(Phase.ENUMERATE);
        sink.filesEnumerated(FILES);
        sink.completePhase(Phase.ENUMERATE);
        sink.enterPhase(Phase.RESTORE);
        sink.completePhase(Phase.RESTORE);

        sink.enterPhase(Phase.LOAD);
        IMonitor core = sink.coreMonitor();
        core.worked(FILES);
        IMonitor analysis = core.createSubMonitor();
        analysis.setWorkRemaining(OBJECTS);
        analysis.worked(OBJECTS);
        sink.completePhase(Phase.LOAD);

        sink.enterPhase(Phase.PACK);
        sink.completePhase(Phase.PACK);
        sink.enterPhase(Phase.PUBLISH);
        sink.completePhase(Phase.PUBLISH);
    }

    private static Thread worker(CountDownLatch start,
            AtomicReference<Throwable> failure, Runnable task) {
        return Thread.ofPlatform().start(() -> {
            try {
                start.await();
                task.run();
            } catch (Throwable ex) {
                failure.compareAndSet(null, ex);
            }
        });
    }

    private static final class RecordingMonitor implements IProgressMonitor {

        private volatile int worked;
        private volatile int negative;
        private volatile boolean cancelled;
        private volatile String subTask;
        private volatile String taskName;

        @Override
        public void beginTask(String name, int totalWork) {
            // the sink converts this monitor and owns the tick budget
        }

        @Override
        public void done() {
            // recorded through worked()
        }

        @Override
        public void internalWorked(double work) {
            worked((int) work);
        }

        @Override
        public boolean isCanceled() {
            return cancelled;
        }

        @Override
        public void setCanceled(boolean value) {
            cancelled = value;
        }

        @Override
        public void setTaskName(String name) {
            taskName = name;
        }

        @Override
        public void subTask(String name) {
            subTask = name;
        }

        @Override
        public synchronized void worked(int work) {
            if (work < 0) {
                negative++;
                return;
            }
            worked += work;
        }
    }
}
