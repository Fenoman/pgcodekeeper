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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.telemetry.ComparisonStage;

import ru.taximaxim.codekeeper.ui.localizations.Messages;
import ru.taximaxim.codekeeper.ui.utils.GetChangesProgressSink.Phase;
import ru.taximaxim.codekeeper.ui.utils.GetChangesProgressSink.Profile;

/**
 * The analysis label is how a user checks whether the analyzed-model cache did
 * its job, so it has to name the branch the run actually took - and it must not
 * claim a reuse before the reuse is proven.
 */
class GetChangesProgressSinkLabelTest {

    @Test
    void aColdRunNamesItsAnalysisCold() {
        var monitor = new RecordingMonitor();
        assertEquals(Messages.GetChangesProgressSink_core_load_analyze_cold,
                analyzeLabel(monitor, Profile.COLD, () -> false));
    }

    @Test
    void aRetainedModelNamesItsAnalysisWarm() {
        var monitor = new RecordingMonitor();
        assertEquals(Messages.GetChangesProgressSink_core_load_analyze_warm,
                analyzeLabel(monitor, Profile.WARM, () -> false));
    }

    @Test
    void aReplayedAnalysisNamesItselfWarmDisk() {
        var monitor = new RecordingMonitor();
        assertEquals(Messages.GetChangesProgressSink_core_load_analyze_warm_disk,
                analyzeLabel(monitor, Profile.COLD, () -> true));
    }

    @Test
    void aRetainedModelWinsOverTheDiskProbe() {
        var monitor = new RecordingMonitor();
        assertEquals(Messages.GetChangesProgressSink_core_load_analyze_warm,
                analyzeLabel(monitor, Profile.WARM, () -> true),
                "an in-memory reuse skips the load entirely, so it outranks disk");
    }

    @Test
    void theLabelFollowsTheProbeInsteadOfBeingDecidedUpFront() {
        var replayed = new AtomicBoolean();
        var monitor = new RecordingMonitor();
        var sink = new GetChangesProgressSink();
        sink.begin(monitor);
        sink.profile(Profile.COLD);
        sink.analysisReplayProbe(replayed::get);
        sink.enterPhase(Phase.CORE_LOAD);

        sink.comparisonStageFinished(ComparisonStage.STRUCTURAL_BARRIER);
        assertEquals(Messages.GetChangesProgressSink_core_load_analyze_cold,
                monitor.lastTask(),
                "before the payload is proven to fit, the run is cold");

        replayed.set(true);
        sink.comparisonStageFinished(ComparisonStage.OLD_FULL_ANALYZE);
        assertEquals(Messages.GetChangesProgressSink_core_load_analyze_warm_disk,
                monitor.lastTask(),
                "once the replay is confirmed the label must follow");
    }

    @Test
    void aProbeThatFailsLeavesTheRunReportedAsCold() {
        var monitor = new RecordingMonitor();
        assertEquals(Messages.GetChangesProgressSink_core_load_analyze_cold,
                analyzeLabel(monitor, Profile.COLD, () -> {
                    throw new IllegalStateException("probe failed");
                }));
    }

    @Test
    void theThreeAnalysisLabelsAreDistinctAndNonEmpty() {
        List<String> labels = List.of(
                Messages.GetChangesProgressSink_core_load_analyze_cold,
                Messages.GetChangesProgressSink_core_load_analyze_warm,
                Messages.GetChangesProgressSink_core_load_analyze_warm_disk);
        labels.forEach(label -> assertTrue(label != null && !label.isBlank(),
                "every analysis label must be localized"));
        assertNotEquals(labels.get(0), labels.get(1));
        assertNotEquals(labels.get(1), labels.get(2));
        assertNotEquals(labels.get(0), labels.get(2));
    }

    private static String analyzeLabel(RecordingMonitor monitor, Profile profile,
            java.util.function.BooleanSupplier replayed) {
        var sink = new GetChangesProgressSink();
        sink.begin(monitor);
        sink.profile(profile);
        sink.analysisReplayProbe(replayed);
        sink.enterPhase(Phase.CORE_LOAD);
        sink.comparisonStageFinished(ComparisonStage.OLD_FULL_ANALYZE);
        return monitor.lastTask();
    }

    /** Captures the task names the sink writes. */
    private static final class RecordingMonitor extends NullProgressMonitor {

        private final List<String> tasks = new ArrayList<>();

        @Override
        public void setTaskName(String name) {
            tasks.add(name);
        }

        private String lastTask() {
            return tasks.isEmpty() ? null : tasks.get(tasks.size() - 1);
        }
    }
}
