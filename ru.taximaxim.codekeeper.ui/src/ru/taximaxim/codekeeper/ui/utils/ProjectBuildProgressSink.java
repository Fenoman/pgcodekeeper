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

import java.text.NumberFormat;
import java.util.Locale;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.SubMonitor;
import org.pgcodekeeper.core.monitor.IMonitor;

import ru.taximaxim.codekeeper.ui.localizations.Messages;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetry;

/**
 * Turns a project-index build into observable staged progress.
 * <p>
 * A build of a large project spends almost all of its time in one phase - Core
 * parsing and analyzing every project file - and the platform reports it as a
 * single unnamed "Building" bar. This sink names the phase the build is in and
 * counts the files that phase has finished, so the user can tell a build that
 * is progressing from one that is stuck.
 * <p>
 * The build monitor is owned by the platform build job and Eclipse monitors are
 * neither thread-safe nor re-entrant, while Core reports parsed files from
 * every parser worker thread. This sink is therefore the single writer of that
 * monitor: every event is serialized on one lock.
 * <p>
 * Progress is accounted in absolute ticks out of {@link #TOTAL_TICKS}. Each
 * phase owns a slice of that budget and can only move the run forward inside
 * its own slice, so a repeated input re-check late in the run cannot rewind the
 * bar to where the first enumeration was.
 * <p>
 * The phase weights come from measured builds of a 13775-file project: the load
 * phase is 87% of a cold build, publication is 10% and everything else together
 * is 3%. A warm build validates the stored index and stops, so it gets its own
 * plan.
 * <p>
 * An incremental build works through a batch of changed files rather than the
 * project, so it counts that batch out instead of the enumerated project, and
 * it can end in a fold of the whole stored index that costs seconds and has no
 * cause the user can see. Both are named phases of their own.
 */
public final class ProjectBuildProgressSink {

    /** Progress budget of one build. */
    public static final int TOTAL_TICKS = 1000;

    /**
     * Smallest share of the load phase worth a new label. Below this the label
     * would be rewritten thousands of times per build and cost more than the
     * work it reports.
     */
    private static final int LABEL_STEP_PERCENT = 1;

    /** Observable phases of one build, in reporting order. */
    public enum Phase {
        /** Collecting the project files that the index covers. */
        ENUMERATE,
        /** Checking the stored index against the files on disk. */
        RESTORE,
        /** Parsing and analyzing project files. */
        LOAD,
        /** Building the index payload out of the loaded model. */
        PACK,
        /** Folding a full journal into a fresh index generation. */
        COMPACT,
        /** Writing the index and re-checking that no input moved. */
        PUBLISH
    }

    /** Branch the build took. */
    public enum Profile {
        /** Every project file is parsed and analyzed. */
        COLD,
        /** The stored index matched the files and was reused. */
        WARM,
        /** Only the changed files are parsed. */
        INCREMENTAL
    }

    private static final Phase[] ORDER = {
            Phase.ENUMERATE, Phase.RESTORE, Phase.LOAD, Phase.PACK,
            Phase.COMPACT, Phase.PUBLISH };

    // A cold build of 13775 files measured 0.13 s enumerating, 0.4 s validating
    // the stored index, 47.2 s loading, 0.7 s packing and 6.4 s publishing
    // including the closing input re-checks. It writes a fresh generation
    // instead of folding a journal, so it never compacts.
    private static final int[] COLD_WEIGHTS = { 3, 8, 868, 12, 0, 109 };

    // A warm build only enumerates and validates: 0.18 s and 0.33 s measured.
    private static final int[] WARM_WEIGHTS = { 350, 650, 0, 0, 0, 0 };

    // An incremental build parses only the changed files, so publication owns a
    // much larger share of it than of a cold build. A batch that overflows the
    // journal is folded into a fresh generation, and that fold rewrites the
    // whole index, so it is worth more than the publication around it.
    private static final int[] INCREMENTAL_WEIGHTS = {
            30, 20, 700, 50, 120, 80 };

    /**
     * Share of the load phase that reading the files is worth. Measured on the
     * same project: 6.2 s of a 45.4 s load is spent parsing the files, the rest
     * analyzing the objects they declare. Splitting the phase this way keeps
     * the bar moving through the analysis instead of parking it at the end of
     * the parse for three quarters of the build.
     */
    private static final int PARSE_SHARE_PERCENT = 14;

    private final Object lock = new Object();

    private SubMonitor monitor;
    private Profile profile = Profile.COLD;
    private boolean profileDeclared;
    private Phase phase;
    private int furthestPhase = -1;
    private int consumed;
    private int base;
    private int span = TOTAL_TICKS;
    private int totalFiles;
    private int parsedFiles;
    private int totalObjects;
    private int analyzedObjects;
    private int changedFiles;
    private int loadedChangedFiles;
    private int reportedLoadPercent = -1;

    /**
     * Binds this sink to the build monitor. Events published before this call
     * are silently dropped: the build has no observable monitor yet.
     * <p>
     * Only the label of a phase carries information here. The bar cannot: an
     * automatic build hands each builder one tick out of the hundred thousand
     * it begins with, and the position it computes truncates, so a whole plan
     * of a thousand ticks moves the platform bar by nothing however long the
     * build runs. That budget belongs to the platform and cannot be widened
     * from this side.
     *
     * @param buildMonitor monitor of the running build, may be null
     */
    public void begin(IProgressMonitor buildMonitor) {
        synchronized (lock) {
            if (buildMonitor == null) {
                return;
            }
            monitor = SubMonitor.convert(buildMonitor, TOTAL_TICKS);
        }
    }

    /**
     * Declares which branch the build took.
     * <p>
     * The first declaration only selects the plan. A later change of mind - a
     * warm attempt that found a changed file, or an incremental build that had
     * to fall back to a full one - re-bases the new plan onto the ticks that are
     * still unspent. Without that, the budget the abandoned plan already spent
     * would swallow the whole beginning of the work that actually runs, and the
     * bar would stand still through it.
     *
     * @param declared branch the build is executing
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
            // An incremental attempt that fell back to a full build no longer
            // works through a batch, and a batch it already started counting
            // would otherwise keep naming a whole the build is not loading.
            changedFiles = 0;
            loadedChangedFiles = 0;
        }
    }

    /**
     * Reports how many files the index covers. The count is the denominator of
     * the load phase, so the user sees a share of a known whole instead of a
     * rising number with no end.
     *
     * @param count files enumerated by the current pass
     */
    public void filesEnumerated(long count) {
        if (count <= 0) {
            return;
        }
        synchronized (lock) {
            totalFiles = (int) Math.min(Integer.MAX_VALUE,
                    Math.max(totalFiles, count));
        }
    }

    /**
     * Reports how many changed files an incremental build accepted into one
     * batch. The batch is the whole such a build loads, so it is the
     * denominator of its load phase; the project file count that the input
     * validation enumerates is not.
     *
     * @param count changed files in the batch
     */
    public void changedFilesEnumerated(long count) {
        if (count <= 0) {
            return;
        }
        synchronized (lock) {
            changedFiles = (int) Math.min(Integer.MAX_VALUE,
                    Math.max(changedFiles, count));
            if (phase == Phase.LOAD) {
                label(changedLoadLabel());
            }
        }
    }

    /**
     * Reports changed files the incremental load has finished. A batch of up to
     * a few hundred files runs for far longer than the seconds a batch of
     * sixteen took, so it has to name how far through the batch it is.
     *
     * @param count changed files finished since the last report
     */
    public void changedFilesLoaded(long count) {
        if (count <= 0) {
            return;
        }
        synchronized (lock) {
            if (phase != Phase.LOAD || changedFiles <= 0) {
                return;
            }
            loadedChangedFiles = (int) Math.min(changedFiles,
                    loadedChangedFiles + count);
            int start = startOf(Phase.LOAD);
            report(start + (int) ((long) (endOf(Phase.LOAD) - start)
                    * loadedChangedFiles / changedFiles));
            label(changedLoadLabel());
        }
    }

    /**
     * Enters a phase: completes everything the plan puts before it and names
     * it. A phase entered again after the build already moved past it is
     * reported as a closing re-check rather than rewinding the plan.
     *
     * @param entered phase that is starting
     */
    public void enterPhase(Phase entered) {
        if (entered == null) {
            return;
        }
        synchronized (lock) {
            int index = indexOf(entered);
            if (index < furthestPhase) {
                label(Messages.ProjectBuildProgressSink_recheck);
                return;
            }
            furthestPhase = index;
            phase = entered;
            report(startOf(entered));
            label(label(entered));
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
            if (indexOf(completed) < furthestPhase) {
                return;
            }
            if (completed == Phase.LOAD
                    && loadedChangedFiles < changedFiles) {
                // An incremental build parses and analyzes one file of its
                // batch at a time and reports each of them as a finished load.
                // The load is over when the batch is, not when its first file
                // is, so the batch counter closes this phase instead.
                return;
            }
            report(endOf(completed));
        }
    }

    /**
     * Returns the monitor Core loads through. It carries cancellation both ways
     * and turns Core's work counters into progress inside the load phase: files
     * on the monitor itself, analyzed objects on the sub-monitor the analysis
     * asks for. The label stays owned by this sink, so parser threads cannot
     * overwrite the phase the user is waiting on.
     *
     * @return a monitor safe to hand to a Core loader
     */
    public IMonitor coreMonitor() {
        return new CoreMonitor();
    }

    /**
     * Returns the view that turns index-run events into this progress. The run
     * already announces every phase it enters and every input pass it makes, so
     * the build needs no second reporting channel of its own.
     *
     * @return observer to attach to a project-index run
     */
    public ProjectIndexTelemetry.Observer observer() {
        return new RunObserver();
    }

    /**
     * Completes the build and releases the monitor. A cancelled build is left
     * where it stopped: pushing the bar to full would claim work never done.
     */
    public void done() {
        synchronized (lock) {
            if (monitor == null || monitor.isCanceled()) {
                return;
            }
            report(TOTAL_TICKS);
            monitor.done();
            monitor = null;
        }
    }

    private void fileParsed(int count) {
        if (count <= 0) {
            return;
        }
        synchronized (lock) {
            if (phase != Phase.LOAD || totalFiles <= 0) {
                return;
            }
            parsedFiles = Math.min(parsedFiles + count, totalFiles);
            reportLoad(Messages.ProjectBuildProgressSink_load_files,
                    parsedFiles, totalFiles);
        }
    }

    private void objectsToAnalyze(int count) {
        if (count <= 0) {
            return;
        }
        synchronized (lock) {
            totalObjects = Math.max(totalObjects, count);
        }
    }

    private void objectAnalyzed(int count) {
        if (count <= 0) {
            return;
        }
        synchronized (lock) {
            if (phase != Phase.LOAD || totalObjects <= 0) {
                return;
            }
            analyzedObjects = Math.min(analyzedObjects + count, totalObjects);
            reportLoad(Messages.ProjectBuildProgressSink_load_objects,
                    analyzedObjects, totalObjects);
        }
    }

    /**
     * Moves the load phase to the share its two counters have finished and
     * names it, but only when the share moved far enough to be worth a new
     * label: the counters fire tens of thousands of times per build.
     */
    private void reportLoad(String template, int done, int total) {
        int parseEnd = PARSE_SHARE_PERCENT;
        int percent = totalObjects > 0
                ? parseEnd + (100 - parseEnd) * analyzedObjects / totalObjects
                : parseEnd * parsedFiles / Math.max(1, totalFiles);
        int start = startOf(Phase.LOAD);
        report(start + (int) ((long) (endOf(Phase.LOAD) - start)
                * percent / 100));
        if (percent >= reportedLoadPercent + LABEL_STEP_PERCENT) {
            reportedLoadPercent = percent;
            label(template.formatted(count(done), count(total)));
        }
    }

    private boolean isCancelled() {
        synchronized (lock) {
            return monitor != null && monitor.isCanceled();
        }
    }

    private void setCancelled(boolean cancelled) {
        synchronized (lock) {
            if (monitor != null) {
                monitor.setCanceled(cancelled);
            }
        }
    }

    private void report(int target) {
        if (monitor == null || monitor.isCanceled()) {
            return;
        }
        int bounded = Math.min(TOTAL_TICKS, Math.max(consumed, target));
        int delta = bounded - consumed;
        if (delta <= 0) {
            return;
        }
        consumed = bounded;
        monitor.worked(delta);
    }

    /**
     * Writes the phase into both slots the platform offers.
     * <p>
     * The sub-task slot alone is not enough. An automatic build runs under a
     * job whose own {@code beginTask} passes an empty name, so the task slot is
     * not occupied by the platform - it is blanked - and the surfaces that show
     * only a task name show nothing at all for the whole build. A full index
     * build of a large project runs for the better part of a minute, and a
     * minute of silence reads as an idle workbench: the user keeps editing, the
     * running build loses the race against the refresh, and its work is thrown
     * away. Visibility here is not decoration.
     */
    private void label(String text) {
        if (monitor != null && text != null && !monitor.isCanceled()) {
            monitor.subTask(text);
            monitor.setTaskName(text);
        }
    }

    private int startOf(Phase target) {
        return prefixOf(target, false);
    }

    private int endOf(Phase target) {
        return prefixOf(target, true);
    }

    private int prefixOf(Phase target, boolean inclusive) {
        int[] weights = weights();
        int prefix = 0;
        for (int i = 0; i < ORDER.length; i++) {
            if (ORDER[i] == target) {
                return scale(inclusive ? prefix + weights[i] : prefix);
            }
            prefix += weights[i];
        }
        return TOTAL_TICKS;
    }

    private int scale(int prefix) {
        return base + (int) Math.round(span * (prefix / (double) TOTAL_TICKS));
    }

    private int[] weights() {
        return switch (profile) {
            case WARM -> WARM_WEIGHTS;
            case INCREMENTAL -> INCREMENTAL_WEIGHTS;
            case COLD -> COLD_WEIGHTS;
        };
    }

    private static int indexOf(Phase target) {
        for (int i = 0; i < ORDER.length; i++) {
            if (ORDER[i] == target) {
                return i;
            }
        }
        return ORDER.length;
    }

    private String label(Phase target) {
        return switch (target) {
            case ENUMERATE -> Messages.ProjectBuildProgressSink_enumerate;
            case RESTORE -> Messages.ProjectBuildProgressSink_restore;
            case LOAD -> loadLabel();
            case PACK -> Messages.ProjectBuildProgressSink_pack;
            case COMPACT -> Messages.ProjectBuildProgressSink_compact;
            case PUBLISH -> Messages.ProjectBuildProgressSink_publish;
        };
    }

    private String loadLabel() {
        if (profile == Profile.INCREMENTAL) {
            return changedLoadLabel();
        }
        return totalFiles > 0
                ? Messages.ProjectBuildProgressSink_load_files.formatted(
                        count(parsedFiles), count(totalFiles))
                : Messages.ProjectBuildProgressSink_load;
    }

    /**
     * Names the incremental load. A batch whose size is already known counts
     * itself out; until it is, the phase can only say what it is loading.
     *
     * @return localized label of the incremental load
     */
    private String changedLoadLabel() {
        return changedFiles > 0
                ? Messages.ProjectBuildProgressSink_load_files.formatted(
                        count(loadedChangedFiles), count(changedFiles))
                : Messages.ProjectBuildProgressSink_load_changed;
    }

    private static String count(int value) {
        return NumberFormat.getIntegerInstance(Locale.getDefault())
                .format(value);
    }

    /**
     * Maps the phases of a project-index run onto the phases of this plan.
     * Phases nested inside publication carry no plan of their own: they are
     * already accounted for by the publication slice.
     */
    private final class RunObserver
            implements ProjectIndexTelemetry.Observer {

        @Override
        public void phaseStarted(ProjectIndexTelemetry.Phase phase) {
            enterPhase(map(phase));
        }

        @Override
        public void phaseFinished(ProjectIndexTelemetry.Phase phase) {
            completePhase(map(phase));
        }

        @Override
        public void filesEnumerated(long count) {
            ProjectBuildProgressSink.this.filesEnumerated(count);
        }

        @Override
        public void changedFilesEnumerated(long count) {
            ProjectBuildProgressSink.this.changedFilesEnumerated(count);
        }

        @Override
        public void changedFilesLoaded(long count) {
            ProjectBuildProgressSink.this.changedFilesLoaded(count);
        }

        @Override
        public IMonitor loadMonitor() {
            return coreMonitor();
        }

        @Override
        public void modeDeclared(ProjectIndexTelemetry.Mode mode) {
            profile(switch (mode) {
                case WARM -> Profile.WARM;
                case INCREMENTAL, MEMORY_INCREMENTAL -> Profile.INCREMENTAL;
                // A bypassed run still parses the whole project: only its
                // result is not stored.
                case COLD, BYPASS -> Profile.COLD;
            });
        }

        private Phase map(ProjectIndexTelemetry.Phase phase) {
            return switch (phase) {
                case INSPECT -> Phase.ENUMERATE;
                case VALIDATE -> Phase.RESTORE;
                case LOAD_ANALYZE, PARSE, ANALYZE -> Phase.LOAD;
                case PACK -> Phase.PACK;
                case COMPACT -> Phase.COMPACT;
                case PUBLISH -> Phase.PUBLISH;
                case ENCODE, LOCATOR_CRC, FSYNC, CURRENT -> null;
            };
        }
    }

    /**
     * The monitor Core loads through. Core reports one work unit per parsed
     * file, from the thread that parsed it, and asks for a sub-monitor before
     * the analysis it cannot otherwise report on.
     */
    private class CoreMonitor implements IMonitor {

        @Override
        public void worked(int work) {
            fileParsed(work);
        }

        @Override
        public IMonitor createSubMonitor() {
            return new AnalysisMonitor();
        }

        @Override
        public void setWorkRemaining(int size) {
            // The file count comes from the input enumeration, which is the
            // same set the loader walks and is known before the load starts.
        }

        @Override
        public boolean isCancelled() {
            return ProjectBuildProgressSink.this.isCancelled();
        }

        @Override
        public void setCancelled(boolean cancelled) {
            if (cancelled) {
                ProjectBuildProgressSink.this.setCancelled(true);
            }
        }

        @Override
        public void setTaskName(String name) {
            // The sink owns the progress text: parser threads must not
            // overwrite the phase the user is currently waiting on.
        }
    }

    /** The monitor the analysis of the loaded objects reports through. */
    private final class AnalysisMonitor extends CoreMonitor {

        @Override
        public void worked(int work) {
            objectAnalyzed(work);
        }

        @Override
        public void setWorkRemaining(int size) {
            objectsToAnalyze(size);
        }

        @Override
        public IMonitor createSubMonitor() {
            return this;
        }
    }
}
