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

import java.util.EnumMap;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.pgcodekeeper.core.monitor.IMonitor;

import ru.taximaxim.codekeeper.ui.PerformanceTelemetry;

/**
 * Publishes one aggregate, secret-free diagnostic line for a project-index run.
 * <p>
 * The same events also drive the observable progress of a build: a run already
 * announces every phase it enters, how many files it enumerated and which
 * branch it took, which is exactly what a progress bar has to show. An attached
 * {@link Observer} receives those events instead of the plugin threading a
 * second reporting channel through the whole index pipeline.
 */
public final class ProjectIndexTelemetry {

    public static final ProjectIndexTelemetry INSTANCE =
            new ProjectIndexTelemetry(PerformanceTelemetry::publish,
                    System::nanoTime);

    private final Consumer<String> logger;
    private final LongSupplier nanoTime;

    ProjectIndexTelemetry(Consumer<String> logger, LongSupplier nanoTime) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    public Run start(Mode mode) {
        return new Run(this, mode, nanoTime.getAsLong());
    }

    private void publish(Run run) {
        long elapsedNanos = Math.max(0, nanoTime.getAsLong() - run.startedNanos);
        Mode publishedMode = run.mode.get();
        var message = new StringBuilder(512)
                .append("pgCodeKeeper project index: mode=")
                .append(publishedMode.token);
        BypassReason bypassReason = run.bypassReason.get();
        if (publishedMode == Mode.BYPASS && bypassReason != null) {
            message.append(" bypass_reason=")
                    .append(bypassReason.token);
        }
        RepairState repair = run.repair.get();
        if (repair != null) {
            message.append(" repair=")
                    .append(repair.outcome.token);
            if (repair.outcome == Repair.REFUSED) {
                message.append(" repair_reason=")
                        .append(repair.reason.token);
            }
        }
        RepairRefusal abandonedBatch = run.abandonedBatch.get();
        if (abandonedBatch != null) {
            message.append(" batch_abandoned=")
                    .append(abandonedBatch.token);
        }
        RuntimeIdentity identity = run.runtimeIdentity.get();
        if (identity != null) {
            message.append(" core_version=")
                    .append(identity.coreVersion)
                    .append(" ui_version=")
                    .append(identity.uiVersion);
        }
        Persistence persistence = run.persistence.get();
        message.append(" persistence_status=")
                .append(persistence.status.token)
                .append(" persistence_reason=")
                .append(persistence.reason.token)
                .append(" paths_enumerated=")
                .append(run.enumeratedPaths.get())
                .append(" enumeration_passes=")
                .append(run.enumerationPasses.sum())
                .append(" single_file_validations=")
                .append(run.singleFileValidations.sum())
                .append(" paths_hashed=")
                .append(run.hashedPaths.sum())
                .append(" paths_hash_inline=")
                .append(run.inlineHashedPaths.sum())
                .append(" paths_hash_reread=")
                .append(run.rereadHashedPaths.sum())
                .append(" paths_parsed=")
                .append(counterValue(run.parsedPathsKnown,
                        run.parsedPaths))
                .append(" paths_analyzed=")
                .append(counterValue(run.analyzedPathsKnown,
                        run.analyzedPaths));
        if (run.excludedPathsKnown.get()) {
            message.append(" paths_excluded=")
                    .append(run.excludedPaths.sum());
        }
        BlockCache blockCache = run.blockCache.get();
        if (blockCache != null) {
            message.append(" block_read_bytes=")
                    .append(blockCache.readBytes)
                    .append(" block_cache_bytes=")
                    .append(blockCache.residentBytes)
                    .append(" block_reads=")
                    .append(blockCache.reads);
        }
        for (Phase phase : Phase.values()) {
            if (run.phaseObserved.get(phase).get()) {
                message.append(" phase_")
                        .append(phase.token)
                        .append("_ms=")
                        .append(TimeUnit.NANOSECONDS.toMillis(
                                run.phaseNanos.get(phase).sum()));
            }
        }
        if (run.writerMetricsKnown.get()) {
            message.append(" writer_buffer_budget_bytes=")
                    .append(run.writerBufferBudgetBytes.get())
                    .append(" writer_runs=")
                    .append(run.writerRuns.sum())
                    .append(" writer_spill_bytes=")
                    .append(run.writerSpillBytes.sum())
                    .append(" writer_packed_bytes=")
                    .append(run.writerPackedBytes.sum());
        }
        if (run.packedRereadBytesKnown.get()) {
            message.append(" writer_reread_bytes=")
                    .append(run.packedRereadBytes.sum());
        }
        WarmMiss warmMiss = run.warmMiss.get();
        if (warmMiss != null) {
            message.append(" warm_miss_changed=")
                    .append(warmMiss.changed)
                    .append(" warm_miss_added=")
                    .append(warmMiss.added)
                    .append(" warm_miss_removed=")
                    .append(warmMiss.removed)
                    .append(" warm_miss_complete=")
                    .append(warmMiss.complete);
        }
        ConfigurationGuard configurationGuard = run.configurationGuard.get();
        if (configurationGuard != null) {
            message.append(" config_guard=")
                    .append(configurationGuard.token);
        }
        message.append(" elapsed_ms=")
                .append(TimeUnit.NANOSECONDS.toMillis(elapsedNanos));
        try {
            logger.accept(message.toString());
        } catch (RuntimeException ex) {
            // Telemetry must never change indexing behavior.
        }
    }

    private static String counterValue(AtomicBoolean known,
            LongAdder counter) {
        return known.get()
                ? Long.toString(counter.sum()) : "unknown"; //$NON-NLS-1$
    }

    public enum BypassReason {
        /**
         * The project only receives changes from a database, and an index of
         * it would serve nothing this mode leaves switched on. See
         * {@link ProjectIndexSupportPolicy} and {@code ProjectReceiveOnlyMode}.
         */
        DISABLED_BY_PREFERENCE("disabled_by_preference"),
        DIRECT_FULL_BUILD("direct_full_build"),
        /**
         * The project does not lay its files out in a way an index can be
         * built from. The database type has no part in this and never reaches
         * this line: {@link ProjectIndexSupportPolicy#refusal} answers with
         * this reason from the layout alone.
         */
        UNSUPPORTED_PROJECT_LAYOUT("unsupported_project_layout"),
        IDENTITY_UNAVAILABLE("identity_unavailable"),
        INPUT_INSPECTION_FAILURE("input_inspection_failure"),
        ANALYSIS_ERRORS("analysis_errors"),
        PERSISTENCE_INPUT_FAILURE("persistence_input_failure"),
        SNAPSHOT_PACK_FAILURE("snapshot_pack_failure");

        private final String token;

        BypassReason(String token) {
            this.token = token;
        }
    }

    /**
     * What became of the repair of a stale index.
     *
     * <p>A build that ends in a full rebuild says nothing about why on its
     * own: the rebuild is what a repair falls back to, so the line has to name
     * whether a repair was even on the table.</p>
     */
    public enum Repair {
        /** This build did not come by the repairing path at all. */
        NOT_CONSIDERED("not_considered"),
        /**
         * The rule accepted a batch and the build went on to apply it, which
         * the published mode confirms.
         */
        APPLIED("applied"),
        /** A repair was possible to ask about and was turned down. */
        REFUSED("refused");

        private final String token;

        Repair(String token) {
            this.token = token;
        }
    }

    /**
     * Why a repair was turned down. The reasons come from two layers and every
     * one of them is its own value: a line that cannot tell two refusals apart
     * answers nothing.
     *
     * <p>The first group is the rule of {@link ProjectIndexRepairPlanner},
     * which decides before any work is done. The second group is the
     * application of an accepted batch, where the refusal means the batch
     * itself did not go through.</p>
     */
    public enum RepairRefusal {

        // The rule, before any file is read.

        /** No warm index was validated, so nothing described a divergence. */
        NO_VALIDATION("no_validation"),
        /**
         * The validation stopped at its budget, so the files it named are a
         * witness of a miss and not a work list.
         */
        TRUNCATED_VALIDATION("truncated_validation"),
        /** A file vanished, and a batch has no way to retire one. */
        REMOVED_FILES("removed_files"),
        /** A file appeared while the project does not permit an addition. */
        ADDED_FILES_DISABLED("added_files_disabled"),
        /** Nothing diverged, so there is nothing to repair. */
        EMPTY_DIVERGENCE("empty_divergence"),
        /** The divergence is wider than this caller agreed to repair. */
        OVER_BUDGET("over_budget"),
        /** A file outside the project moved, which no batch can address. */
        NON_PROJECT_PATH("non_project_path"),

        // The batch, once the rule accepted it.

        /** The batch was not a well-formed list of project paths. */
        BATCH_PATHS_REJECTED("batch_paths_rejected"),
        /** The live index was replaced before a stored one could be adopted. */
        BOOTSTRAP_NOT_INITIAL("bootstrap_not_initial"),
        /** The store held no index this batch could be applied to. */
        STORED_INDEX_UNAVAILABLE("stored_index_unavailable"),
        /** The adopted index stopped being the current one. */
        BOOTSTRAP_SUPERSEDED("bootstrap_superseded"),
        /** The indexed snapshot did not agree with itself. */
        SNAPSHOT_INCONSISTENT("snapshot_inconsistent"),
        /** The batch named a file the index does not hold, which is refused. */
        BATCH_ADDED_FILES_DISABLED("batch_added_files_disabled"),
        /** The indexed metadata of the batch could not be read. */
        INDEX_READ_FAILURE("index_read_failure"),
        /** The indexed metadata of the batch was not understood. */
        INDEX_METADATA_REJECTED("index_metadata_rejected"),
        /** The build offered a continuity proof the index did not accept. */
        CONTINUITY_UNTRUSTED("continuity_untrusted"),
        /** The inputs of the batch did not match the index before the load. */
        PREFLIGHT_INPUTS_STALE("preflight_inputs_stale"),
        /** The inputs of the batch could not be inspected at all. */
        PREFLIGHT_FAILURE("preflight_failure"),
        /** A file of the batch could not be read, parsed or analyzed. */
        BATCH_LOAD_FAILURE("batch_load_failure"),
        /** A file of the batch lost the right to replace its contribution. */
        BATCH_FILE_UNSAFE("batch_file_unsafe"),
        /**
         * A file of the batch did not parse, while keeping every definition
         * the index holds for it. Told apart from
         * {@link #BATCH_FILE_UNSAFE} because it is the one refusal that costs
         * no rebuild: the index is left exactly as it was, so the next build
         * repairs one file rather than reading the whole project.
         */
        BATCH_ANALYSIS_ERRORS("batch_analysis_errors"),
        /** The rule about added files could not be asked. */
        ADDED_FILES_CHECK_FAILURE("added_files_check_failure"),
        /** The rule about added files turned the batch down. */
        ADDED_FILES_REFUSED("added_files_refused"),
        /** The inputs of the batch could not be proven again after the load. */
        REVALIDATION_FAILURE("revalidation_failure"),
        /** The inputs of the batch moved while it was being loaded. */
        CONTEXT_UNSTABLE("context_unstable"),
        /** The loaded batch changed the shape of an indexed definition. */
        UNSAFE_REPLACEMENTS("unsafe_replacements");

        private final String token;

        RepairRefusal(String token) {
            this.token = token;
        }
    }

    /**
     * Why a build refused to publish the index it had already built.
     *
     * <p>Published only when it happened. A build whose configuration is still
     * the settled one has nothing to report and its line says nothing, which
     * is what makes a line that does say something worth counting: every one
     * of them is a complete build thrown away.</p>
     */
    public enum ConfigurationGuard {
        /**
         * The configuration the build worked by is no longer the settled one.
         * The index is whole and consistent with itself, and no check of it
         * would ever accept it again.
         */
        MOVED("moved"),
        /**
         * The settled configuration could not be read at all. A build that
         * cannot see the configuration must not conclude that it did not move:
         * the index is worth less than the rebuild.
         */
        UNREADABLE("unreadable");

        private final String token;

        ConfigurationGuard(String token) {
            this.token = token;
        }

        /**
         * @return the bare token this refusal is published as, so that a
         *         caller which refuses before a line is ever published can
         *         name the reason the same way
         */
        public String token() {
            return token;
        }
    }

    public enum Mode {
        COLD("cold"),
        WARM("warm"),
        INCREMENTAL("incremental"),
        MEMORY_INCREMENTAL("memory_incremental"),
        BYPASS("bypass");

        private final String token;

        Mode(String token) {
            this.token = token;
        }
    }

    public enum PersistenceStatus {
        NOT_ATTEMPTED("not_attempted"),
        HIT("hit"),
        PUBLISHED("published"),
        MEMORY_ONLY("memory_only"),
        FAILED("failed");

        private final String token;

        PersistenceStatus(String token) {
            this.token = token;
        }
    }

    public enum PersistenceReason {
        NONE("none"),
        AUXILIARY_MEMORY_LIMIT("auxiliary_memory_limit"),
        IO("io"),
        VALIDATION("validation"),
        REOPEN("reopen"),
        CANCELLED("cancelled"),
        STALE_INPUT("stale_input");

        private final String token;

        PersistenceReason(String token) {
            this.token = token;
        }
    }

    /**
     * Live view of a run. Implementations are called from every thread the run
     * touches and must not throw: a reporting failure may never change what the
     * index does.
     */
    public interface Observer {

        /**
         * @param phase phase the run has entered
         */
        void phaseStarted(Phase phase);

        /**
         * @param phase phase the run has left
         */
        void phaseFinished(Phase phase);

        /**
         * @param count files the run enumerated in one pass
         */
        void filesEnumerated(long count);

        /**
         * Reports the size of the batch of changed files an incremental run is
         * about to parse. That batch, not the project, is the whole the run
         * works through, so it is the only denominator its progress can use.
         *
         * @param count changed files the run accepted into one batch
         */
        void changedFilesEnumerated(long count);

        /**
         * @param count changed files the run has finished parsing and
         *              analyzing
         */
        void changedFilesLoaded(long count);

        /**
         * @param mode branch the run declared
         */
        void modeDeclared(Mode mode);

        /**
         * Returns the monitor the load of this run reports its per-file and
         * per-object work through. Core writes it from every parser thread, so
         * an implementation must be thread-safe.
         *
         * @return monitor to hand to the loader, or null to leave the load
         *         unobserved
         */
        IMonitor loadMonitor();
    }

    public enum Phase {
        INSPECT("inspect"),
        PARSE("parse"),
        ANALYZE("analyze"),
        LOAD_ANALYZE("load_analyze"),
        PACK("pack"),
        /** Folding the journal of an existing index into a fresh generation. */
        COMPACT("compact"),
        PUBLISH("publish"),
        VALIDATE("validate"),
        ENCODE("encode"),
        LOCATOR_CRC("locator_crc"),
        FSYNC("fsync"),
        CURRENT("current");

        private final String token;

        Phase(String token) {
            this.token = token;
        }
    }

    /**
     * Thread-safe accumulator. Close it after all worker tasks have completed.
     */
    public static final class Run implements AutoCloseable {

        private final ProjectIndexTelemetry owner;
        private final long startedNanos;
        private final AtomicReference<Mode> mode;
        private final AtomicReference<BypassReason> bypassReason =
                new AtomicReference<>();
        private final AtomicReference<RepairState> repair =
                new AtomicReference<>();
        private final AtomicReference<RepairRefusal> abandonedBatch =
                new AtomicReference<>();
        private final AtomicReference<RuntimeIdentity> runtimeIdentity =
                new AtomicReference<>();
        private final AtomicReference<Persistence> persistence =
                new AtomicReference<>(new Persistence(
                        PersistenceStatus.NOT_ATTEMPTED,
                        PersistenceReason.NONE));
        private final AtomicLong enumeratedPaths = new AtomicLong();
        private final LongAdder enumerationPasses = new LongAdder();
        private final LongAdder singleFileValidations = new LongAdder();
        private final LongAdder hashedPaths = new LongAdder();
        private final LongAdder inlineHashedPaths = new LongAdder();
        private final LongAdder rereadHashedPaths = new LongAdder();
        private final LongAdder parsedPaths = new LongAdder();
        private final AtomicBoolean parsedPathsKnown = new AtomicBoolean();
        private final LongAdder analyzedPaths = new LongAdder();
        private final AtomicBoolean analyzedPathsKnown = new AtomicBoolean();
        private final LongAdder excludedPaths = new LongAdder();
        private final AtomicBoolean excludedPathsKnown = new AtomicBoolean();
        private final AtomicReference<BlockCache> blockCache = new AtomicReference<>();
        private final AtomicReference<WarmMiss> warmMiss =
                new AtomicReference<>();
        private final AtomicReference<ConfigurationGuard> configurationGuard =
                new AtomicReference<>();
        private final EnumMap<Phase, LongAdder> phaseNanos =
                new EnumMap<>(Phase.class);
        private final EnumMap<Phase, AtomicBoolean> phaseObserved =
                new EnumMap<>(Phase.class);
        private final AtomicLong writerBufferBudgetBytes = new AtomicLong();
        private final LongAdder writerRuns = new LongAdder();
        private final LongAdder writerSpillBytes = new LongAdder();
        private final LongAdder writerPackedBytes = new LongAdder();
        private final AtomicBoolean writerMetricsKnown = new AtomicBoolean();
        private final LongAdder packedRereadBytes = new LongAdder();
        private final AtomicBoolean packedRereadBytesKnown =
                new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicReference<Observer> observer =
                new AtomicReference<>();

        private Run(ProjectIndexTelemetry owner, Mode mode, long startedNanos) {
            this.owner = owner;
            this.mode = new AtomicReference<>(Objects.requireNonNull(mode, "mode"));
            this.startedNanos = startedNanos;
            for (Phase phase : Phase.values()) {
                phaseNanos.put(phase, new LongAdder());
                phaseObserved.put(phase, new AtomicBoolean());
            }
        }

        /**
         * Attaches the live view of this run. Only one observer is supported:
         * a run belongs to exactly one operation and that operation owns the
         * progress the user is watching.
         *
         * @param value observer of this run, may be null
         * @return this run
         */
        public Run observer(Observer value) {
            observer.set(value);
            if (value != null) {
                value.modeDeclared(mode.get());
            }
            return this;
        }

        /**
         * Returns the monitor the loader of this run must report through.
         *
         * @param fallback monitor to use when nothing observes this run
         * @return monitor for the loader
         */
        public IMonitor coreMonitor(Supplier<IMonitor> fallback) {
            Objects.requireNonNull(fallback, "fallback");
            Observer current = observer.get();
            IMonitor observed = null;
            if (current != null) {
                try {
                    observed = current.loadMonitor();
                } catch (RuntimeException ex) {
                    // Reporting must never change indexing behavior.
                }
            }
            return observed == null ? fallback.get() : observed;
        }

        public Run mode(Mode value) {
            Mode checked = Objects.requireNonNull(value, "value");
            mode.set(checked);
            if (checked != Mode.BYPASS) {
                bypassReason.set(null);
            }
            notifyObserver(current -> current.modeDeclared(checked));
            return this;
        }

        public Run bypass(BypassReason reason) {
            bypassReason.set(Objects.requireNonNull(reason, "reason"));
            mode.set(Mode.BYPASS);
            notifyObserver(current -> current.modeDeclared(Mode.BYPASS));
            return this;
        }

        /**
         * Records that this build never came by the repairing path.
         *
         * <p>A decision already taken is kept: the rebuild a refused repair
         * falls back to arrives here too, and it is the refusal, not the
         * rebuild, that describes what happened.</p>
         *
         * @return this run
         */
        public Run repairNotConsidered() {
            repair.compareAndSet(null,
                    new RepairState(Repair.NOT_CONSIDERED, null));
            return this;
        }

        /**
         * Records that the rule accepted a batch to repair the index from.
         *
         * @return this run
         */
        public Run repairApplied() {
            repair.set(new RepairState(Repair.APPLIED, null));
            return this;
        }

        /**
         * Records that a repair was turned down before it started.
         *
         * @param reason why the rule refused
         * @return this run
         */
        public Run repairRefused(RepairRefusal reason) {
            repair.set(new RepairState(Repair.REFUSED,
                    Objects.requireNonNull(reason, "reason")));
            return this;
        }

        /**
         * Records that an incremental batch gave up and rebuilt instead.
         *
         * <p>Only a repair is described by the repair outcome. An ordinary
         * incremental build that falls back refused nothing, because nothing
         * asked it to repair anything, and its line keeps saying so.</p>
         *
         * <p>The reason is published either way, in a field of its own. It is
         * a property of the batch and not of a repair, and reporting it only
         * where a repair had been applied threw it away exactly where it was
         * needed: an incremental build that gave up published a line naming a
         * rebuild that nothing had asked for, while the reason it gave up was
         * known here and printed nowhere. The first reason is kept, because
         * later ones would describe a batch this one has already replaced.</p>
         *
         * @param reason why the batch was not applied
         * @return this run
         */
        public Run repairBatchAbandoned(RepairRefusal reason) {
            Objects.requireNonNull(reason, "reason");
            abandonedBatch.compareAndSet(null, reason);
            repair.updateAndGet(current ->
                    current != null && current.outcome == Repair.APPLIED
                            ? new RepairState(Repair.REFUSED, reason)
                            : current);
            return this;
        }

        public Run persistence(PersistenceStatus status,
                PersistenceReason reason) {
            persistence.set(new Persistence(status, reason));
            return this;
        }

        public Run identity(ProjectIndexIdentity identity) {
            Objects.requireNonNull(identity, "identity");
            runtimeIdentity.set(new RuntimeIdentity(
                    identity.coreVersion(), identity.uiVersion()));
            return this;
        }

        /**
         * Retains the largest completed input snapshot. Revalidation passes
         * therefore do not multiply the reported project size.
         */
        public Run observeEnumeratedPaths(long count) {
            enumeratedPaths.accumulateAndGet(nonNegative(count), Math::max);
            return this;
        }

        public Run observeEnumerationPass(long count) {
            observeEnumeratedPaths(count);
            enumerationPasses.increment();
            notifyObserver(current -> current.filesEnumerated(count));
            return this;
        }

        /**
         * Announces the batch of changed files an incremental run accepted.
         * The batch size is already published as {@code paths_enumerated}, so
         * this only opens the observable counter of the batch.
         *
         * @param count changed files in the batch
         * @return this run
         */
        public Run observeChangedFiles(long count) {
            notifyObserver(current -> current.changedFilesEnumerated(count));
            return this;
        }

        /**
         * Reports changed files an incremental run has finished. The files are
         * already published as {@code paths_parsed}, so this only advances the
         * observable counter of the batch.
         *
         * @param count changed files finished since the last report
         * @return this run
         */
        public Run observeChangedFilesLoaded(long count) {
            notifyObserver(current -> current.changedFilesLoaded(count));
            return this;
        }

        public Run addSingleFileValidations(long count) {
            add(singleFileValidations, count);
            return this;
        }

        public PhaseTimer phase(Phase value) {
            Phase checked = Objects.requireNonNull(value, "value");
            notifyObserver(current -> current.phaseStarted(checked));
            return new PhaseTimer(this, checked,
                    owner.nanoTime.getAsLong());
        }

        public Run addPhaseNanos(Phase phase, long nanos) {
            Phase checked = Objects.requireNonNull(phase, "phase");
            phaseObserved.get(checked).set(true);
            add(phaseNanos.get(checked), nanos);
            return this;
        }

        /**
         * Records bounded-writer usage. {@code packedBytes} is the complete
         * format-2 codec payload and excludes any outer container framing.
         */
        public Run writerMetrics(long configuredBufferBudgetBytes,
                long runs, long spillBytes, long packedBytes) {
            writerMetricsKnown.set(true);
            writerBufferBudgetBytes.accumulateAndGet(
                    nonNegative(configuredBufferBudgetBytes), Math::max);
            add(writerRuns, runs);
            add(writerSpillBytes, spillBytes);
            add(writerPackedBytes, packedBytes);
            return this;
        }

        public Run addPackedRereadBytes(long count) {
            packedRereadBytesKnown.set(true);
            add(packedRereadBytes, count);
            return this;
        }

        public Run addHashedPaths(long count) {
            add(hashedPaths, count);
            return this;
        }

        /**
         * Records fingerprints calculated from bytes already consumed by the
         * parser, without an additional file read.
         */
        public Run addInlineHashedPaths(long count) {
            add(hashedPaths, count);
            add(inlineHashedPaths, count);
            return this;
        }

        /**
         * Records fingerprints that required reading file contents solely for
         * validation or index publication.
         */
        public Run addRereadHashedPaths(long count) {
            add(hashedPaths, count);
            add(rereadHashedPaths, count);
            return this;
        }

        public Run addParsedPaths(long count) {
            parsedPathsKnown.set(true);
            add(parsedPaths, count);
            return this;
        }

        public Run addAnalyzedPaths(long count) {
            analyzedPathsKnown.set(true);
            add(analyzedPaths, count);
            return this;
        }

        public Run markParserCountsUnknown() {
            parsedPaths.reset();
            parsedPathsKnown.set(false);
            analyzedPaths.reset();
            analyzedPathsKnown.set(false);
            return this;
        }

        public Run addExcludedPaths(long count) {
            excludedPathsKnown.set(true);
            add(excludedPaths, count);
            return this;
        }

        public Run blockCache(ProjectIndexView view) {
            if (view != null) {
                blockCache(view.cacheReadBytes(), view.cachedBytes(),
                        view.cacheBlocksRead());
            }
            return this;
        }

        public Run blockCache(long readBytes, long residentBytes, long reads) {
            blockCache.set(new BlockCache(nonNegative(readBytes),
                    nonNegative(residentBytes), nonNegative(reads)));
            return this;
        }

        /**
         * Records how far the working tree had moved away from a warm index.
         *
         * <p>Only a miss is published, and only the size of it: a hit has no
         * divergence to describe, and a run that never validated a warm index
         * has not even asked the question. The paths themselves stay out of
         * the line - the size is what says whether a divergence is small
         * enough to be worth repairing instead of rebuilt.</p>
         *
         * @param validation outcome of a warm validation run, may be null when
         *                   no warm index was validated at all
         * @return this run
         */
        public Run warmMiss(ProjectIndexWarmValidator.Result validation) {
            if (validation != null && !validation.hit()) {
                warmMiss.set(new WarmMiss(validation.changed().size(),
                        validation.added().size(),
                        validation.removed().size(),
                        validation.complete()));
            }
            return this;
        }

        /**
         * Records that this build refused to publish what it had built,
         * because the configuration it worked by is no longer the settled one.
         *
         * <p>The first refusal is the one that is kept. Publication is asked
         * about once per path a build can leave by, and the first path to
         * refuse is the one that decided the fate of the build.</p>
         *
         * @param reason why the build did not publish
         * @return this run
         */
        public Run configurationGuard(ConfigurationGuard reason) {
            configurationGuard.compareAndSet(null,
                    Objects.requireNonNull(reason, "reason"));
            return this;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                owner.publish(this);
            }
        }

        private void notifyObserver(Consumer<Observer> event) {
            Observer current = observer.get();
            if (current == null) {
                return;
            }
            try {
                event.accept(current);
            } catch (RuntimeException ex) {
                // Reporting must never change indexing behavior.
            }
        }

        private static void add(LongAdder counter, long count) {
            if (count > 0) {
                counter.add(count);
            }
        }

        private static long nonNegative(long value) {
            return Math.max(0, value);
        }
    }

    public static final class PhaseTimer implements AutoCloseable {

        private final Run run;
        private final Phase phase;
        private final long startedNanos;
        private final AtomicBoolean closed = new AtomicBoolean();

        private PhaseTimer(Run run, Phase phase, long startedNanos) {
            this.run = run;
            this.phase = phase;
            this.startedNanos = startedNanos;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                run.addPhaseNanos(phase, Math.max(0,
                        run.owner.nanoTime.getAsLong() - startedNanos));
                run.notifyObserver(current -> current.phaseFinished(phase));
            }
        }
    }

    private record BlockCache(long readBytes, long residentBytes, long reads) {
    }

    /**
     * Size of a warm divergence, as far as the validation described it. A run
     * that stopped at its divergence budget counts only what it enumerated and
     * reports itself incomplete.
     */
    private record WarmMiss(int changed, int added, int removed,
            boolean complete) {
    }

    /**
     * What became of a repair, and why when it was refused. Only a refusal
     * names a reason: the other two outcomes have nothing more to say.
     */
    private record RepairState(Repair outcome, RepairRefusal reason) {

        private RepairState {
            Objects.requireNonNull(outcome, "outcome");
            if ((outcome == Repair.REFUSED) != (reason != null)) {
                throw new IllegalArgumentException(
                        "Only a refused repair names a reason");
            }
        }
    }

    private record Persistence(PersistenceStatus status,
            PersistenceReason reason) {

        private Persistence {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(reason, "reason");
            boolean failure = status == PersistenceStatus.FAILED
                    || status == PersistenceStatus.MEMORY_ONLY;
            if (failure == (reason == PersistenceReason.NONE)) {
                throw new IllegalArgumentException(
                        "Persistence reason must describe only a failed or memory-only result");
            }
        }
    }

    private record RuntimeIdentity(String coreVersion, String uiVersion) {

        private RuntimeIdentity {
            Objects.requireNonNull(coreVersion, "coreVersion");
            Objects.requireNonNull(uiVersion, "uiVersion");
        }
    }
}
