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

import java.util.Arrays;
import java.util.function.BooleanSupplier;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.SubMonitor;
import org.pgcodekeeper.core.telemetry.ComparisonStage;

import ru.taximaxim.codekeeper.ui.localizations.Messages;

/**
 * Turns the Get Changes comparison into observable staged progress.
 * <p>
 * The Eclipse job owns exactly one progress monitor and Eclipse monitors are
 * neither thread-safe nor re-entrant, so this sink is the single writer for the
 * whole run: the job, the reusable comparison and the Core worker lanes all
 * report through it and every call is serialized on one lock.
 * <p>
 * Progress is accounted in absolute ticks out of {@link #TOTAL_TICKS}. Each
 * phase owns a slice of that budget and every event can only move the run
 * forward inside its own slice, so no stage can consume progress another stage
 * was allotted and the bar never moves backwards.
 * <p>
 * Warm and cold runs spend their time very differently: a cold run parses the
 * whole project and hashes every input file, a warm run only validates hashes
 * and reuses the analyzed model. Both therefore get their own phase order and
 * weights, selected through {@link #profile(Profile)} as soon as the run knows
 * which branch it took.
 */
public final class GetChangesProgressSink {

    /** Progress budget of one Get Changes run. */
    public static final int TOTAL_TICKS = 1000;

    /**
     * Budget of the raw Core worker channel. Core reports its own fine-grained
     * work against this fixed total, which the sink then maps into a bounded
     * share of the load phase.
     */
    public static final int WORKER_TICKS = 100;

    /** Observable phases of one Get Changes run, in reporting order. */
    public enum Phase {
        WORKSPACE_REFRESH,
        PROJECT_INDEX,
        MODEL_VALIDATE,
        CORE_LOAD,
        DIFF_TREE,
        PUBLISH
    }

    /** Comparison branch the run took. */
    public enum Profile {
        COLD,
        WARM
    }

    // Cold runs parse the project from scratch and hash every input file after
    // the load, so the capture phase follows the load and is expensive.
    private static final Phase[] COLD_ORDER = {
            Phase.WORKSPACE_REFRESH, Phase.PROJECT_INDEX, Phase.CORE_LOAD,
            Phase.MODEL_VALIDATE, Phase.DIFF_TREE, Phase.PUBLISH };
    private static final int[] COLD_WEIGHTS = { 40, 60, 730, 90, 60, 20 };

    // Warm runs validate the retained model before loading, and their load
    // carries only the remote side, so validation is worth more and the load
    // less than on a cold run.
    private static final Phase[] WARM_ORDER = {
            Phase.WORKSPACE_REFRESH, Phase.PROJECT_INDEX, Phase.MODEL_VALIDATE,
            Phase.CORE_LOAD, Phase.DIFF_TREE, Phase.PUBLISH };
    private static final int[] WARM_WEIGHTS = { 40, 80, 120, 680, 60, 20 };

    // Split of the load phase between its three independent progress sources.
    private static final int STAGE_SHARE = 450;
    private static final int READER_SHARE = 350;
    private static final int WORKER_SHARE = 200;

    /**
     * Catalog readers a PostgreSQL side runs. Extra readers saturate instead of
     * overrunning the slice, missing readers are absorbed by the stage events.
     */
    private static final int EXPECTED_READERS = 26;

    private static final int STAGE_WEIGHT_TOTAL = 100;

    private final Object lock = new Object();
    private final StageWeights coldStages = StageWeights.cold();
    private final StageWeights warmStages = StageWeights.warm();
    private final boolean[] seenStages = new boolean[ComparisonStage.values().length];

    private SubMonitor progress;
    private BooleanSupplier analysisReplayed = () -> false;
    private Profile profile = Profile.COLD;
    private boolean profileDeclared;
    private Phase phase;
    private int consumed;
    private int base;
    private int span = TOTAL_TICKS;
    private int seenStageWeight;
    private int readers;
    private double workerUnits;

    /**
     * Binds this sink to the job monitor. Events published before this call are
     * silently dropped: the run has no observable monitor yet.
     * <p>
     * No task name is set here. The progress view already shows the job name,
     * and a task name would be prepended to every phase label, so the sink
     * writes the phase label as the task name instead.
     *
     * @param monitor job progress monitor, may be null
     */
    public void begin(IProgressMonitor monitor) {
        synchronized (lock) {
            if (monitor == null) {
                return;
            }
            progress = SubMonitor.convert(monitor, TOTAL_TICKS);
        }
    }

    /**
     * Binds the probe that reports whether the project side served its analysis
     * from the persistent cache instead of analyzing the model.
     * <p>
     * The probe is read every time the analysis label is written rather than
     * once up front, because a payload is only proven to fit the parsed model
     * at the very start of the analysis phase. Until it answers true the run is
     * reported as cold, so the label never claims a reuse that did not happen.
     *
     * @param replayed probe of the project loader, may be null
     */
    public void analysisReplayProbe(BooleanSupplier replayed) {
        synchronized (lock) {
            analysisReplayed = replayed == null ? () -> false : replayed;
        }
    }

    /**
     * Declares which comparison branch the run took.
     * <p>
     * The first declaration only selects the phase plan. A later change of mind
     * - a warm attempt that fell back to a cold load - re-bases the plan onto
     * the ticks that are still unspent, so the cold work that follows still has
     * observable progress.
     *
     * @param declared branch the run is executing
     */
    public void profile(Profile declared) {
        if (declared == null) {
            return;
        }
        synchronized (lock) {
            if (!profileDeclared) {
                profileDeclared = true;
                profile = declared;
                return;
            }
            if (profile == declared) {
                return;
            }
            profile = declared;
            base = consumed;
            span = TOTAL_TICKS - base;
            resetLoadCounters();
        }
    }

    /**
     * Enters a phase: completes everything the plan puts before it and shows
     * its localized label.
     *
     * @param entered phase that is starting
     */
    public void enterPhase(Phase entered) {
        if (entered == null) {
            return;
        }
        synchronized (lock) {
            phase = entered;
            report(startOf(entered));
            taskLabel(label(entered));
        }
    }

    /**
     * Completes a phase, consuming whatever it did not report itself.
     *
     * @param completed phase that has finished
     */
    public void completePhase(Phase completed) {
        if (completed == null) {
            return;
        }
        synchronized (lock) {
            report(endOf(completed));
        }
    }

    /**
     * Reserves a share of what is left of a phase for a nested Eclipse monitor.
     * <p>
     * The returned child is the only writer of the reserved ticks, so a callee
     * that reports its own progress cannot consume ticks the plan gave to
     * another phase.
     *
     * @param sliced phase to take the ticks from
     * @param percent share of the unspent phase budget, 1..100
     * @return a bounded child monitor, or null when this sink is not bound
     */
    public IProgressMonitor slice(Phase sliced, int percent) {
        synchronized (lock) {
            if (progress == null || sliced == null) {
                return null;
            }
            int remaining = Math.max(0, endOf(sliced) - consumed);
            int ticks = Math.max(0,
                    Math.min(remaining, remaining * percent / 100));
            consumed += ticks;
            // The sink owns the progress text: a callee that reports its own
            // work must not replace the phase the user is waiting on.
            return progress.newChild(ticks, SubMonitor.SUPPRESS_ALL_LABELS);
        }
    }

    /**
     * Returns the monitor for the Core worker lanes. It carries cancellation
     * both ways and feeds the raw work counters into a bounded share of the
     * load phase; the progress text stays owned by this sink so parallel lanes
     * cannot overwrite the current stage label.
     *
     * @return a monitor safe to hand to a Core {@code IMonitor} adapter
     */
    public IProgressMonitor workerMonitor() {
        return new WorkerMonitor();
    }

    /**
     * Consumes the share of the load phase that one finished catalog reader is
     * worth.
     */
    public void catalogReaderFinished() {
        synchronized (lock) {
            try {
                if (readers < EXPECTED_READERS) {
                    readers++;
                }
                if (phase == Phase.CORE_LOAD) {
                    report(loadTarget());
                    taskLabel(format(
                            Messages.GetChangesProgressSink_core_load_catalogs,
                            readers));
                }
            } catch (RuntimeException ex) {
                // Telemetry callbacks must never change comparison behavior.
            }
        }
    }

    /**
     * Consumes the share of the load phase that one finished comparison stage
     * is worth. Stages complete on several loader lanes, so events arrive out
     * of order and may repeat; each stage is therefore counted at most once.
     *
     * @param stage completed comparison stage
     */
    public void comparisonStageFinished(ComparisonStage stage) {
        if (stage == null) {
            return;
        }
        synchronized (lock) {
            try {
                if (stage == ComparisonStage.DATABASE_LOAD_TOTAL) {
                    report(endOf(Phase.CORE_LOAD));
                    return;
                }
                if (stage == ComparisonStage.DIFF_TREE_CREATE) {
                    report(endOf(Phase.DIFF_TREE));
                    return;
                }
                int index = stage.ordinal();
                if (seenStages[index]) {
                    return;
                }
                seenStages[index] = true;
                seenStageWeight = Math.min(STAGE_WEIGHT_TOTAL,
                        seenStageWeight + stages().weight(stage));
                if (phase == Phase.CORE_LOAD) {
                    report(loadTarget());
                    taskLabel(loadLabel(stage));
                }
            } catch (RuntimeException ex) {
                // Telemetry callbacks must never change comparison behavior.
            }
        }
    }

    /**
     * Completes the run and releases the job monitor. A cancelled run is left
     * where it stopped: the job framework closes its own monitor, and pushing
     * the bar to full would claim work that was never done.
     */
    public void done() {
        synchronized (lock) {
            if (progress == null || progress.isCanceled()) {
                return;
            }
            report(TOTAL_TICKS);
            progress.done();
        }
    }

    private void resetLoadCounters() {
        seenStageWeight = 0;
        readers = 0;
        workerUnits = 0;
        Arrays.fill(seenStages, false);
    }

    private void workerWorked(double units) {
        if (units <= 0) {
            return;
        }
        synchronized (lock) {
            workerUnits = Math.min(WORKER_TICKS, workerUnits + units);
            if (phase == Phase.CORE_LOAD) {
                report(loadTarget());
            }
        }
    }

    private boolean isCancelled() {
        synchronized (lock) {
            return progress != null && progress.isCanceled();
        }
    }

    private void setCancelled(boolean cancelled) {
        synchronized (lock) {
            if (progress != null) {
                progress.setCanceled(cancelled);
            }
        }
    }

    /** Absolute tick target of the load phase for the counters seen so far. */
    private int loadTarget() {
        double fraction = STAGE_SHARE * (seenStageWeight / (double) STAGE_WEIGHT_TOTAL)
                + READER_SHARE * Math.min(1d, readers / (double) EXPECTED_READERS)
                + WORKER_SHARE * (workerUnits / WORKER_TICKS);
        fraction = Math.min(1d, fraction / 1000d);
        int start = startOf(Phase.CORE_LOAD);
        return start + (int) Math.round(fraction * (endOf(Phase.CORE_LOAD) - start));
    }

    private void report(int target) {
        if (progress == null || progress.isCanceled()) {
            return;
        }
        int bounded = Math.min(TOTAL_TICKS, Math.max(consumed, target));
        int delta = bounded - consumed;
        if (delta <= 0) {
            return;
        }
        consumed = bounded;
        progress.worked(delta);
    }

    /**
     * Shows the current phase. The label is written as the task name, so the
     * progress view renders it on its own instead of appending it to a task
     * name of this run.
     *
     * @param text localized phase label
     */
    private void taskLabel(String text) {
        if (progress != null && text != null && !progress.isCanceled()) {
            progress.setTaskName(text);
        }
    }

    private int startOf(Phase target) {
        return scale(prefixOf(target, false));
    }

    private int endOf(Phase target) {
        return scale(prefixOf(target, true));
    }

    private int prefixOf(Phase target, boolean inclusive) {
        Phase[] order = profile == Profile.WARM ? WARM_ORDER : COLD_ORDER;
        int[] weights = profile == Profile.WARM ? WARM_WEIGHTS : COLD_WEIGHTS;
        int prefix = 0;
        for (int i = 0; i < order.length; i++) {
            if (order[i] == target) {
                return inclusive ? prefix + weights[i] : prefix;
            }
            prefix += weights[i];
        }
        return TOTAL_TICKS;
    }

    private int scale(int prefix) {
        return base + (int) Math.round(span * (prefix / (double) TOTAL_TICKS));
    }

    private StageWeights stages() {
        return profile == Profile.WARM ? warmStages : coldStages;
    }

    private static String label(Phase target) {
        return switch (target) {
            case WORKSPACE_REFRESH -> Messages.GetChangesProgressSink_workspace_refresh;
            case PROJECT_INDEX -> Messages.GetChangesProgressSink_project_index;
            case MODEL_VALIDATE -> Messages.GetChangesProgressSink_model_validate;
            case CORE_LOAD -> Messages.GetChangesProgressSink_core_load;
            case DIFF_TREE -> Messages.GetChangesProgressSink_diff_tree;
            case PUBLISH -> Messages.GetChangesProgressSink_publish;
        };
    }

    private static String format(String template, int value) {
        return template == null ? null : template.formatted(value);
    }

    private String loadLabel(ComparisonStage stage) {
        return switch (stage) {
            case PREPARE -> Messages.GetChangesProgressSink_core_load;
            case OLD_STRUCTURAL_LOAD -> Messages.GetChangesProgressSink_core_load_project;
            case NEW_STRUCTURAL_LOAD -> Messages.GetChangesProgressSink_core_load_remote;
            case STRUCTURAL_BARRIER, OLD_FULL_ANALYZE, NEW_FULL_ANALYZE -> analyzeLabel();
            default -> Messages.GetChangesProgressSink_core_load_finish;
        };
    }

    /**
     * Names the analysis phase after the origin of the project model, which is
     * the only observable difference between a run that reuses an analyzed
     * model and one that has to build it.
     *
     * @return localized analysis label of the current branch
     */
    private String analyzeLabel() {
        if (profile == Profile.WARM) {
            return Messages.GetChangesProgressSink_core_load_analyze_warm;
        }
        return isAnalysisReplayed()
                ? Messages.GetChangesProgressSink_core_load_analyze_warm_disk
                : Messages.GetChangesProgressSink_core_load_analyze_cold;
    }

    private boolean isAnalysisReplayed() {
        try {
            return analysisReplayed.getAsBoolean();
        } catch (RuntimeException ex) {
            // A probe that fails must not change what the comparison reports.
            return false;
        }
    }

    /** Relative cost of every comparison stage inside the load phase. */
    private static final class StageWeights {

        private final int[] weights = new int[ComparisonStage.values().length];

        private static StageWeights cold() {
            var stageWeights = new StageWeights();
            stageWeights.put(ComparisonStage.PREPARE, 5);
            stageWeights.put(ComparisonStage.OLD_STRUCTURAL_LOAD, 25);
            stageWeights.put(ComparisonStage.NEW_STRUCTURAL_LOAD, 30);
            stageWeights.put(ComparisonStage.STRUCTURAL_BARRIER, 5);
            stageWeights.put(ComparisonStage.OLD_FULL_ANALYZE, 15);
            stageWeights.put(ComparisonStage.NEW_FULL_ANALYZE, 15);
            stageWeights.put(ComparisonStage.ANALYSIS_BARRIER, 3);
            stageWeights.put(ComparisonStage.LOADERS_CLOSE, 2);
            return stageWeights;
        }

        private static StageWeights warm() {
            var stageWeights = new StageWeights();
            // The retained model replaces the project parse, so the OLD side
            // completes immediately and the remote side owns the wait.
            stageWeights.put(ComparisonStage.PREPARE, 5);
            stageWeights.put(ComparisonStage.OLD_STRUCTURAL_LOAD, 5);
            stageWeights.put(ComparisonStage.NEW_STRUCTURAL_LOAD, 45);
            stageWeights.put(ComparisonStage.STRUCTURAL_BARRIER, 5);
            stageWeights.put(ComparisonStage.OLD_FULL_ANALYZE, 5);
            stageWeights.put(ComparisonStage.NEW_FULL_ANALYZE, 30);
            stageWeights.put(ComparisonStage.ANALYSIS_BARRIER, 3);
            stageWeights.put(ComparisonStage.LOADERS_CLOSE, 2);
            return stageWeights;
        }

        private void put(ComparisonStage stage, int weight) {
            weights[stage.ordinal()] = weight;
        }

        private int weight(ComparisonStage stage) {
            return weights[stage.ordinal()];
        }
    }

    /**
     * Bounded Eclipse monitor for the Core worker lanes.
     */
    private final class WorkerMonitor extends NullProgressMonitor {

        private double total = WORKER_TICKS;

        @Override
        public void beginTask(String name, int totalWork) {
            if (totalWork > 0) {
                total = totalWork;
            }
        }

        @Override
        public void worked(int work) {
            internalWorked(work);
        }

        @Override
        public void internalWorked(double work) {
            workerWorked(work * WORKER_TICKS / total);
        }

        @Override
        public boolean isCanceled() {
            return GetChangesProgressSink.this.isCancelled();
        }

        @Override
        public void setCanceled(boolean cancelled) {
            if (cancelled) {
                GetChangesProgressSink.this.setCancelled(true);
            }
        }

        @Override
        public void setTaskName(String name) {
            // The sink owns the progress text: parallel lanes must not
            // overwrite the stage the user is currently waiting on.
        }

        @Override
        public void subTask(String name) {
            // See setTaskName.
        }

        @Override
        public void done() {
            // The owning job completes the reserved slice.
        }
    }
}
