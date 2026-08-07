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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.SubMonitor;
import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.monitor.IMonitor;
import org.pgcodekeeper.core.telemetry.ComparisonStage;

import ru.taximaxim.codekeeper.ui.utils.GetChangesProgressSink.Phase;
import ru.taximaxim.codekeeper.ui.utils.GetChangesProgressSink.Profile;

class GetChangesProgressSinkTest {

    @Test
    void coldRunSpendsTheWholeBudgetAndNeverMovesBackwards() {
        var monitor = new RecordingMonitor();
        var sink = new GetChangesProgressSink();
        sink.begin(monitor);

        runColdRun(sink);
        sink.done();

        assertEquals(GetChangesProgressSink.TOTAL_TICKS, monitor.worked);
        assertTrue(monitor.negative == 0, "progress must never move backwards");
    }

    @Test
    void warmRunSpendsTheWholeBudgetAndNeverMovesBackwards() {
        var monitor = new RecordingMonitor();
        var sink = new GetChangesProgressSink();
        sink.begin(monitor);

        runWarmRun(sink);
        sink.done();

        assertEquals(GetChangesProgressSink.TOTAL_TICKS, monitor.worked);
        assertTrue(monitor.negative == 0, "progress must never move backwards");
    }

    @Test
    void warmProfileWeighsValidationHigherAndTheLoadLowerThanCold() {
        assertEquals(120, phaseWidth(Profile.WARM, Phase.MODEL_VALIDATE));
        assertEquals(90, phaseWidth(Profile.COLD, Phase.MODEL_VALIDATE));
        assertEquals(680, phaseWidth(Profile.WARM, Phase.CORE_LOAD));
        assertEquals(730, phaseWidth(Profile.COLD, Phase.CORE_LOAD));
    }

    @Test
    void warmRunValidatesBeforeLoadingAndColdRunAfterwards() {
        var warm = new RecordingMonitor();
        var warmSink = new GetChangesProgressSink();
        warmSink.begin(warm);
        warmSink.profile(Profile.WARM);
        warmSink.enterPhase(Phase.MODEL_VALIDATE);
        int warmValidateStart = warm.worked;
        warmSink.enterPhase(Phase.CORE_LOAD);
        int warmLoadStart = warm.worked;

        var cold = new RecordingMonitor();
        var coldSink = new GetChangesProgressSink();
        coldSink.begin(cold);
        coldSink.profile(Profile.COLD);
        coldSink.enterPhase(Phase.CORE_LOAD);
        int coldLoadStart = cold.worked;
        coldSink.enterPhase(Phase.MODEL_VALIDATE);
        int coldValidateStart = cold.worked;

        assertTrue(warmValidateStart < warmLoadStart,
                "a warm run validates before it loads");
        assertTrue(coldLoadStart < coldValidateStart,
                "a cold run captures fingerprints after it loads");
    }

    @Test
    void duplicateAndOutOfOrderStagesAreCountedExactlyOnce() {
        var ordered = new RecordingMonitor();
        var orderedSink = loadedSink(ordered);
        orderedSink.comparisonStageFinished(ComparisonStage.PREPARE);
        orderedSink.comparisonStageFinished(ComparisonStage.OLD_STRUCTURAL_LOAD);
        orderedSink.comparisonStageFinished(ComparisonStage.NEW_STRUCTURAL_LOAD);

        var shuffled = new RecordingMonitor();
        var shuffledSink = loadedSink(shuffled);
        shuffledSink.comparisonStageFinished(ComparisonStage.NEW_STRUCTURAL_LOAD);
        shuffledSink.comparisonStageFinished(ComparisonStage.PREPARE);
        shuffledSink.comparisonStageFinished(ComparisonStage.NEW_STRUCTURAL_LOAD);
        shuffledSink.comparisonStageFinished(ComparisonStage.OLD_STRUCTURAL_LOAD);
        shuffledSink.comparisonStageFinished(ComparisonStage.PREPARE);

        assertTrue(ordered.worked > 0, "stage events must move the bar");
        assertEquals(ordered.worked, shuffled.worked);
    }

    @Test
    void extraCatalogReadersSaturateInsteadOfOverrunningTheLoadPhase() {
        var monitor = new RecordingMonitor();
        var sink = loadedSink(monitor);
        for (int i = 0; i < 26; i++) {
            sink.catalogReaderFinished();
        }
        int atExpectedCount = monitor.worked;
        for (int i = 0; i < 500; i++) {
            sink.catalogReaderFinished();
        }

        assertEquals(atExpectedCount, monitor.worked);
        assertTrue(atExpectedCount < loadPhaseEnd(Profile.COLD),
                "readers alone must not complete the load phase");
    }

    @Test
    void aStageCannotConsumeProgressAnotherPhaseWasAllotted() {
        var monitor = new RecordingMonitor();
        var sink = loadedSink(monitor);
        for (ComparisonStage stage : ComparisonStage.values()) {
            if (stage != ComparisonStage.DATABASE_LOAD_TOTAL
                    && stage != ComparisonStage.DIFF_TREE_CREATE) {
                sink.comparisonStageFinished(stage);
            }
        }
        for (int i = 0; i < 100; i++) {
            sink.catalogReaderFinished();
        }
        sink.workerMonitor().worked(GetChangesProgressSink.WORKER_TICKS * 10);

        assertTrue(monitor.worked <= loadPhaseEnd(Profile.COLD),
                "the load phase reported " + monitor.worked + " ticks");
    }

    @Test
    void databaseLoadTotalCompletesTheLoadPhaseWithoutReaderEvents() {
        var monitor = new RecordingMonitor();
        var sink = loadedSink(monitor);
        sink.comparisonStageFinished(ComparisonStage.DATABASE_LOAD_TOTAL);

        assertEquals(loadPhaseEnd(Profile.COLD), monitor.worked);
    }

    @Test
    void aSliceCannotOverrunItsPhase() {
        var monitor = new RecordingMonitor();
        var sink = new GetChangesProgressSink();
        sink.begin(monitor);
        sink.enterPhase(Phase.WORKSPACE_REFRESH);

        IProgressMonitor slice = sink.slice(Phase.WORKSPACE_REFRESH, 100);
        assertNotNull(slice);
        SubMonitor child = SubMonitor.convert(slice, 10);
        for (int i = 0; i < 1_000; i++) {
            child.setWorkRemaining(10);
            child.worked(10);
        }
        child.done();

        assertEquals(40, monitor.worked);
    }

    @Test
    void cancellationStopsReportingAtEveryStage() {
        var monitor = new RecordingMonitor();
        var sink = loadedSink(monitor);
        int beforeCancel = monitor.worked;
        monitor.setCanceled(true);

        sink.catalogReaderFinished();
        sink.comparisonStageFinished(ComparisonStage.NEW_STRUCTURAL_LOAD);
        sink.workerMonitor().worked(GetChangesProgressSink.WORKER_TICKS);
        sink.enterPhase(Phase.DIFF_TREE);
        sink.completePhase(Phase.DIFF_TREE);
        sink.done();

        assertEquals(beforeCancel, monitor.worked);
    }

    @Test
    void theWorkerMonitorCarriesCancellationBothWays() {
        var monitor = new RecordingMonitor();
        var sink = new GetChangesProgressSink();
        sink.begin(monitor);
        IProgressMonitor worker = sink.workerMonitor();

        worker.setCanceled(true);
        assertTrue(monitor.isCanceled(), "core cancellation must reach the job");
        assertTrue(worker.isCanceled(), "job cancellation must reach core");
    }

    @Test
    void everyStageNamesItselfSoTheUserCanSeeWhereTimeGoes() {
        var monitor = new RecordingMonitor();
        var sink = new GetChangesProgressSink();
        sink.begin(monitor);

        sink.enterPhase(Phase.WORKSPACE_REFRESH);
        String refresh = monitor.taskName;
        sink.enterPhase(Phase.CORE_LOAD);
        String load = monitor.taskName;
        sink.catalogReaderFinished();
        String catalogs = monitor.taskName;
        sink.comparisonStageFinished(ComparisonStage.NEW_STRUCTURAL_LOAD);
        String remote = monitor.taskName;

        assertNotNull(refresh);
        assertTrue(!refresh.equals(load), "each phase names itself");
        assertTrue(!load.equals(catalogs), "reader progress is readable");
        assertTrue(!catalogs.equals(remote), "stage progress is readable");
    }

    /**
     * The progress view already names the job, so a run-wide task name would
     * only be prepended to every phase label. Each label instead opens with the
     * pipeline phase it belongs to, in both localizations.
     */
    @Test
    void phaseLabelsCarryAPipelinePrefixAndNoRunPrefix() {
        var monitor = new RecordingMonitor();
        var sink = new GetChangesProgressSink();
        sink.begin(monitor);
        sink.profile(Profile.WARM);

        assertEquals("", monitor.beganWith,
                "a run-wide task name would prefix every phase label");

        sink.enterPhase(Phase.WORKSPACE_REFRESH);
        assertPrefix("Files: ", monitor.taskName);
        sink.enterPhase(Phase.PROJECT_INDEX);
        assertPrefix("Index: ", monitor.taskName);
        sink.enterPhase(Phase.MODEL_VALIDATE);
        assertPrefix("Model: ", monitor.taskName);
        sink.enterPhase(Phase.CORE_LOAD);
        assertPrefix("DB: ", monitor.taskName);
        sink.comparisonStageFinished(ComparisonStage.OLD_STRUCTURAL_LOAD);
        assertPrefix("DB: ", monitor.taskName);
        sink.catalogReaderFinished();
        assertPrefix("DB: ", monitor.taskName);
        sink.comparisonStageFinished(ComparisonStage.OLD_FULL_ANALYZE);
        assertPrefix("Analyze: ", monitor.taskName);
        sink.comparisonStageFinished(ComparisonStage.LOADERS_CLOSE);
        assertPrefix("DB: ", monitor.taskName);
        sink.enterPhase(Phase.DIFF_TREE);
        assertPrefix("Graph: ", monitor.taskName);
        sink.enterPhase(Phase.PUBLISH);
        assertPrefix("Publish: ", monitor.taskName);
    }

    private static void assertPrefix(String prefix, String label) {
        assertNotNull(label, "every stage must name itself");
        assertTrue(label.startsWith(prefix),
                () -> "expected '" + prefix + "' prefix, got: " + label);
    }

    @Test
    void anUnboundSinkIsInert() {
        var sink = new GetChangesProgressSink();

        assertNull(sink.slice(Phase.CORE_LOAD, 100));
        sink.profile(Profile.WARM);
        sink.enterPhase(Phase.CORE_LOAD);
        sink.catalogReaderFinished();
        sink.comparisonStageFinished(ComparisonStage.PREPARE);
        sink.workerMonitor().worked(10);
        sink.completePhase(Phase.CORE_LOAD);
        sink.done();
    }

    @Test
    void aWarmFallbackToColdKeepsReportingOnTheUnspentBudget() {
        var monitor = new RecordingMonitor();
        var sink = new GetChangesProgressSink();
        sink.begin(monitor);
        sink.profile(Profile.WARM);
        sink.enterPhase(Phase.MODEL_VALIDATE);
        sink.completePhase(Phase.MODEL_VALIDATE);
        int afterRejectedWarm = monitor.worked;

        sink.profile(Profile.COLD);
        sink.enterPhase(Phase.CORE_LOAD);
        sink.comparisonStageFinished(ComparisonStage.NEW_STRUCTURAL_LOAD);
        int duringColdLoad = monitor.worked;
        sink.completePhase(Phase.CORE_LOAD);

        assertTrue(duringColdLoad > afterRejectedWarm,
                "the cold reload must still have observable progress");
        assertTrue(monitor.worked < GetChangesProgressSink.TOTAL_TICKS,
                "the cold reload must not consume the whole budget");
    }

    @Test
    void concurrentLoaderLanesNeverOverrunTheBudget()
            throws InterruptedException {
        var monitor = new RecordingMonitor();
        var sink = loadedSink(monitor);
        IMonitor core = new ConcurrentUIMonitor(sink.workerMonitor());
        var start = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();

        Thread readers = worker(start, failure, () -> {
            for (int i = 0; i < 200; i++) {
                sink.catalogReaderFinished();
            }
        });
        Thread stages = worker(start, failure, () -> {
            for (ComparisonStage stage : ComparisonStage.values()) {
                sink.comparisonStageFinished(stage);
                sink.comparisonStageFinished(stage);
            }
        });
        Thread lanes = worker(start, failure, () -> {
            IMonitor lane = core.createSubMonitor();
            for (int i = 0; i < 500; i++) {
                lane.setWorkRemaining(50);
                lane.worked(50);
            }
        });

        start.countDown();
        readers.join();
        stages.join();
        lanes.join();

        assertNull(failure.get());
        assertEquals(0, monitor.negative);
        assertTrue(monitor.worked <= GetChangesProgressSink.TOTAL_TICKS,
                "reported " + monitor.worked + " ticks");
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

    /** A sink parked at the start of the load phase of a cold run. */
    private static GetChangesProgressSink loadedSink(
            RecordingMonitor monitor) {
        var sink = new GetChangesProgressSink();
        sink.begin(monitor);
        sink.profile(Profile.COLD);
        sink.enterPhase(Phase.CORE_LOAD);
        return sink;
    }

    private static int phaseWidth(Profile profile, Phase phase) {
        var monitor = new RecordingMonitor();
        var sink = new GetChangesProgressSink();
        sink.begin(monitor);
        sink.profile(profile);
        sink.enterPhase(phase);
        int start = monitor.worked;
        sink.completePhase(phase);
        return monitor.worked - start;
    }

    private static int loadPhaseEnd(Profile profile) {
        var monitor = new RecordingMonitor();
        var sink = new GetChangesProgressSink();
        sink.begin(monitor);
        sink.profile(profile);
        sink.completePhase(Phase.CORE_LOAD);
        return monitor.worked;
    }

    private static void runColdRun(GetChangesProgressSink sink) {
        sink.enterPhase(Phase.WORKSPACE_REFRESH);
        closeSlice(sink.slice(Phase.WORKSPACE_REFRESH, 100));
        sink.completePhase(Phase.WORKSPACE_REFRESH);

        sink.enterPhase(Phase.PROJECT_INDEX);
        closeSlice(sink.slice(Phase.PROJECT_INDEX, 70));
        sink.profile(Profile.COLD);
        sink.completePhase(Phase.PROJECT_INDEX);

        sink.enterPhase(Phase.CORE_LOAD);
        publishLoadEvents(sink);
        sink.completePhase(Phase.CORE_LOAD);

        sink.enterPhase(Phase.MODEL_VALIDATE);
        closeSlice(sink.slice(Phase.MODEL_VALIDATE, 40));
        sink.completePhase(Phase.MODEL_VALIDATE);

        sink.enterPhase(Phase.DIFF_TREE);
        sink.comparisonStageFinished(ComparisonStage.DIFF_TREE_CREATE);
        sink.completePhase(Phase.DIFF_TREE);
        sink.enterPhase(Phase.PUBLISH);
    }

    private static void runWarmRun(GetChangesProgressSink sink) {
        sink.enterPhase(Phase.WORKSPACE_REFRESH);
        closeSlice(sink.slice(Phase.WORKSPACE_REFRESH, 100));
        sink.completePhase(Phase.WORKSPACE_REFRESH);

        sink.enterPhase(Phase.PROJECT_INDEX);
        closeSlice(sink.slice(Phase.PROJECT_INDEX, 70));
        sink.profile(Profile.WARM);
        closeSlice(sink.slice(Phase.PROJECT_INDEX, 100));
        sink.completePhase(Phase.PROJECT_INDEX);

        sink.enterPhase(Phase.MODEL_VALIDATE);
        sink.completePhase(Phase.MODEL_VALIDATE);

        sink.enterPhase(Phase.CORE_LOAD);
        publishLoadEvents(sink);
        sink.completePhase(Phase.CORE_LOAD);

        sink.enterPhase(Phase.DIFF_TREE);
        sink.comparisonStageFinished(ComparisonStage.DIFF_TREE_CREATE);
        sink.completePhase(Phase.DIFF_TREE);
        sink.enterPhase(Phase.PUBLISH);
    }

    private static void publishLoadEvents(GetChangesProgressSink sink) {
        for (int i = 0; i < 26; i++) {
            sink.catalogReaderFinished();
        }
        sink.comparisonStageFinished(ComparisonStage.PREPARE);
        sink.comparisonStageFinished(ComparisonStage.OLD_STRUCTURAL_LOAD);
        sink.comparisonStageFinished(ComparisonStage.NEW_STRUCTURAL_LOAD);
        sink.comparisonStageFinished(ComparisonStage.STRUCTURAL_BARRIER);
        sink.comparisonStageFinished(ComparisonStage.OLD_FULL_ANALYZE);
        sink.comparisonStageFinished(ComparisonStage.NEW_FULL_ANALYZE);
        sink.comparisonStageFinished(ComparisonStage.ANALYSIS_BARRIER);
        sink.comparisonStageFinished(ComparisonStage.LOADERS_CLOSE);
        sink.comparisonStageFinished(ComparisonStage.DATABASE_LOAD_TOTAL);
    }

    private static void closeSlice(IProgressMonitor slice) {
        if (slice != null) {
            slice.done();
        }
    }

    private static final class RecordingMonitor implements IProgressMonitor {

        private int worked;
        private int negative;
        private boolean cancelled;
        private String taskName;
        private String beganWith;

        @Override
        public void beginTask(String name, int totalWork) {
            beganWith = name;
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
            // the sink owns the progress text through setTaskName
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
