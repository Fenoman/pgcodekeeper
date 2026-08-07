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
package ru.taximaxim.codekeeper.ui.settings;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import org.pgcodekeeper.core.telemetry.ComparisonCancellationDrainTelemetry;
import org.pgcodekeeper.core.telemetry.ComparisonStageTelemetry;
import org.pgcodekeeper.core.telemetry.IComparisonTelemetry;
import org.pgcodekeeper.core.telemetry.PgCatalogCacheRunTelemetry;
import org.pgcodekeeper.core.telemetry.PgCatalogReaderCacheTelemetry;
import org.pgcodekeeper.core.telemetry.PgConnectionLifecycleTelemetry;
import org.pgcodekeeper.core.telemetry.PgRoutineBodyCacheTelemetry;

import ru.taximaxim.codekeeper.ui.PerformanceTelemetry;
import ru.taximaxim.codekeeper.ui.utils.GetChangesProgressSink;

public final class EclipseComparisonTelemetry implements IComparisonTelemetry {

    public enum UiStage {
        RUN_STARTED,
        JOB_QUEUE,
        WORKSPACE_REFRESH,
        MODEL_VALIDATE,
        CORE_LOAD,
        DIFF_TREE,
        UI_QUEUE,
        UI_PUBLISH,
        MODEL_PUBLISH
    }

    public enum RunOutcome {
        SUCCESS,
        REJECTED,
        CANCELLED,
        FAILED,
        SUPERSEDED
    }

    public enum ProjectModelCacheStatus {
        HIT("hit"), //$NON-NLS-1$
        HIT_DISK("hit_disk"), //$NON-NLS-1$
        MISS("miss"), //$NON-NLS-1$
        PUBLISHED("published"), //$NON-NLS-1$
        REJECTED("rejected"); //$NON-NLS-1$

        private final String wireName;

        ProjectModelCacheStatus(String wireName) {
            this.wireName = wireName;
        }

        @Override
        public String toString() {
            return wireName;
        }
    }

    public enum ProjectModelFailClosedReason {
        NONE("none"), //$NON-NLS-1$
        INPUT_CHANGED("input_changed"), //$NON-NLS-1$
        PROFILE_CHANGED("profile_changed"), //$NON-NLS-1$
        VERSION_MISMATCH("version_mismatch"), //$NON-NLS-1$
        LIBRARIES("libraries"), //$NON-NLS-1$
        ONE_TIME("one_time"), //$NON-NLS-1$
        UNSUPPORTED("unsupported"), //$NON-NLS-1$
        NO_MODEL("no_model"), //$NON-NLS-1$
        CANCELLED("cancelled"), //$NON-NLS-1$
        STALE("stale"), //$NON-NLS-1$
        /**
         * The caller did not ask for {@link
         * org.pgcodekeeper.core.api.ComparisonDepth#FULL}. The reusable
         * pipeline exists to serve a fully analyzed model from cache, so a
         * request for anything else bypasses it before the cache is even
         * consulted.
         */
        STRUCTURAL_ONLY("structural_only"); //$NON-NLS-1$

        private final String wireName;

        ProjectModelFailClosedReason(String wireName) {
            this.wireName = wireName;
        }

        @Override
        public String toString() {
            return wireName;
        }
    }

    /** Outcome of one attempt to serve the persistent analyzed-model store. */
    public enum ProjectAnalysisStoreStatus {
        HIT("hit"), //$NON-NLS-1$
        MISS("miss"), //$NON-NLS-1$
        STALE("stale"), //$NON-NLS-1$
        CORRUPT("corrupt"), //$NON-NLS-1$
        RETRYABLE("retryable"), //$NON-NLS-1$
        PUBLISHED("published"), //$NON-NLS-1$
        UNAVAILABLE("unavailable"); //$NON-NLS-1$

        private final String wireName;

        ProjectAnalysisStoreStatus(String wireName) {
            this.wireName = wireName;
        }

        @Override
        public String toString() {
            return wireName;
        }
    }

    public enum ProjectInputChangeStage {
        FINGERPRINT_CAPTURE("fingerprint_capture"), //$NON-NLS-1$
        FILE_SET("file_set"), //$NON-NLS-1$
        MUTATION_EPOCH("mutation_epoch"), //$NON-NLS-1$
        INDEX_SNAPSHOT("index_snapshot"), //$NON-NLS-1$
        DIFF_TREE("diff_tree"); //$NON-NLS-1$

        private final String wireName;

        ProjectInputChangeStage(String wireName) {
            this.wireName = wireName;
        }

        @Override
        public String toString() {
            return wireName;
        }
    }

    private static final int MAX_READER_NAME_LENGTH = 128;

    private final UUID runId;
    private final Consumer<String> logger;
    private final LongSupplier nanoTime;
    private final AtomicLong sequence = new AtomicLong();
    private final Object publishLock = new Object();
    private final GetChangesProgressSink progress = new GetChangesProgressSink();

    EclipseComparisonTelemetry() {
        this(UUID.randomUUID(), PerformanceTelemetry::publish,
                System::nanoTime);
    }

    EclipseComparisonTelemetry(UUID runId, Consumer<String> logger) {
        this(runId, logger, System::nanoTime);
    }

    EclipseComparisonTelemetry(UUID runId, Consumer<String> logger,
            LongSupplier nanoTime) {
        this.runId = Objects.requireNonNull(runId, "runId"); //$NON-NLS-1$
        this.logger = Objects.requireNonNull(logger, "logger");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime"); //$NON-NLS-1$
    }

    /**
     * Returns the staged progress sink of this run. Typed load events are
     * forwarded to it so the Eclipse progress bar advances while the
     * comparison is still running.
     *
     * @return the run-scoped progress sink, never null
     */
    public GetChangesProgressSink progress() {
        return progress;
    }

    public long startTimer() {
        try {
            return nanoTime.getAsLong();
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    public void uiStageFinished(UiStage stage, long startNanos) {
        if (stage == null) {
            return;
        }
        long elapsedNanos = elapsedSince(startNanos);
        publish(("ui_stage_finished stage=%s elapsed_nanos=%d") //$NON-NLS-1$
                .formatted(stage, elapsedNanos));
    }

    public void runFinished(RunOutcome outcome, long startNanos) {
        if (outcome == null) {
            return;
        }
        long elapsedNanos = elapsedSince(startNanos);
        publish(("run_finished outcome=%s elapsed_nanos=%d") //$NON-NLS-1$
                .formatted(outcome, elapsedNanos));
    }

    public void projectModelCacheFinished(ProjectModelCacheStatus status,
            ProjectModelFailClosedReason reason, long filesInspected,
            long filesHashed, long startNanos) {
        if (status == null || reason == null) {
            return;
        }
        long elapsedNanos = elapsedSince(startNanos);
        publish(("project_model_cache_finished status=%s reason=%s" //$NON-NLS-1$
                + " files_inspected=%d files_hashed=%d elapsed_nanos=%d") //$NON-NLS-1$
                        .formatted(status, reason,
                                Math.max(0L, filesInspected),
                                Math.max(0L, filesHashed), elapsedNanos));
    }

    /**
     * Publishes one outcome of the persistent analyzed-model store, so a run
     * that started cold can be traced to the reason its cache did not serve it.
     *
     * @param status         outcome of this attempt
     * @param bytes          size of the store file involved
     * @param filesInspected number of project inputs the run enumerated
     * @param filesHashed    number of inputs whose content had to be hashed
     * @param startNanos     timer start of this attempt
     */
    public void projectAnalysisStoreFinished(ProjectAnalysisStoreStatus status,
            long bytes, long filesInspected, long filesHashed, long startNanos) {
        if (status == null) {
            return;
        }
        long elapsedNanos = elapsedSince(startNanos);
        publish(("project_analysis_store_finished status=%s bytes=%d" //$NON-NLS-1$
                + " files_inspected=%d files_hashed=%d elapsed_nanos=%d") //$NON-NLS-1$
                        .formatted(status, Math.max(0L, bytes),
                                Math.max(0L, filesInspected),
                                Math.max(0L, filesHashed), elapsedNanos));
    }

    public void projectInputsChanged(ProjectInputChangeStage stage) {
        if (stage != null) {
            publish(("project_inputs_changed stage=%s") //$NON-NLS-1$
                    .formatted(stage));
        }
    }

    @Override
    public void pgCatalogReaderFinished(PgCatalogReaderCacheTelemetry event) {
        if (event == null) {
            return;
        }
        progress.catalogReaderFinished();
        publish(("pg_catalog_reader_finished reader=%s mode=%s" //$NON-NLS-1$
                + " bypass_reason=%s rows=%d hits=%d misses=%d" //$NON-NLS-1$
                + " fetched_rows=%d published_rows=%d" //$NON-NLS-1$
                + " hash_payload_bytes=%d encoded_row_bytes=%d" //$NON-NLS-1$
                + " pack_bytes_read=%d pack_bytes_written=%d" //$NON-NLS-1$
                + " fingerprint_probe_used=%s fingerprint_matched=%s" //$NON-NLS-1$
                + " elapsed_nanos=%d").formatted( //$NON-NLS-1$
                        sanitizeReaderName(event.readerName()),
                        event.mode(), event.bypassReason(), event.rows(),
                        event.hits(), event.misses(), event.fetchedRows(),
                        event.publishedRows(), event.hashPayloadBytes(),
                        event.encodedRowBytes(), event.packBytesRead(),
                        event.packBytesWritten(), event.fingerprintProbeUsed(),
                        event.fingerprintMatched(), event.elapsedNanos()));
    }

    @Override
    public void pgCatalogCacheFinished(PgCatalogCacheRunTelemetry event) {
        if (event == null) {
            return;
        }
        publish(("pg_catalog_cache_finished readers=%d" //$NON-NLS-1$
                + " bypassed_readers=%d rows=%d hits=%d misses=%d" //$NON-NLS-1$
                + " fetched_rows=%d published_rows=%d" //$NON-NLS-1$
                + " hash_payload_bytes=%d encoded_row_bytes=%d" //$NON-NLS-1$
                + " pack_bytes_read=%d pack_bytes_written=%d" //$NON-NLS-1$
                + " pruned_bytes=%d elapsed_nanos=%d").formatted( //$NON-NLS-1$
                        event.readers(), event.bypassedReaders(),
                        event.rows(), event.hits(), event.misses(),
                        event.fetchedRows(), event.publishedRows(),
                        event.hashPayloadBytes(), event.encodedRowBytes(),
                        event.packBytesRead(), event.packBytesWritten(),
                        event.prunedBytes(), event.elapsedNanos()));
    }

    @Override
    public void pgRoutineBodyCacheFinished(
            PgRoutineBodyCacheTelemetry event) {
        if (event == null) {
            return;
        }
        publish(("pg_routine_body_cache_finished hits=%d misses=%d" //$NON-NLS-1$
                + " stored=%d saved_utf8_bytes=%d pruned_bytes=%d" //$NON-NLS-1$
                + " elapsed_nanos=%d").formatted( //$NON-NLS-1$
                        event.hits(), event.misses(), event.stored(),
                        event.savedUtf8Bytes(), event.prunedBytes(),
                        event.elapsedNanos()));
    }

    @Override
    public void comparisonStageFinished(ComparisonStageTelemetry event) {
        if (event == null) {
            return;
        }
        progress.comparisonStageFinished(event.stage());
        publish(("comparison_stage_finished stage=%s elapsed_nanos=%d") //$NON-NLS-1$
                .formatted(event.stage(), event.elapsedNanos()));
    }

    @Override
    public void pgConnectionLifecycle(
            PgConnectionLifecycleTelemetry event) {
        if (event == null) {
            return;
        }
        publish(("pg_connection_lifecycle side=%s role=%s lane=%d" //$NON-NLS-1$
                + " lifecycle=%s backend_pid=%d").formatted( //$NON-NLS-1$
                        event.side(), event.role(), event.lane(),
                        event.lifecycle(), event.backendPid()));
    }

    @Override
    public void comparisonCancellationDrainFinished(
            ComparisonCancellationDrainTelemetry event) {
        if (event == null) {
            return;
        }
        publish(("comparison_cancellation_drain_finished" //$NON-NLS-1$
                + " result=%s elapsed_nanos=%d").formatted( //$NON-NLS-1$
                        event.result(), event.elapsedNanos()));
    }

    private void publish(String event) {
        synchronized (publishLock) {
            try {
                long currentSequence = sequence.incrementAndGet();
                logger.accept(("pgCodeKeeper comparison: run_id=%s" //$NON-NLS-1$
                        + " seq=%d event=%s").formatted( //$NON-NLS-1$
                                runId, currentSequence, event));
            } catch (RuntimeException ex) {
                // Telemetry must never change comparison behavior.
            }
        }
    }

    private long elapsedSince(long startNanos) {
        try {
            return Math.max(0L, nanoTime.getAsLong() - startNanos);
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    private static String sanitizeReaderName(String readerName) {
        if (readerName == null || readerName.isEmpty()
                || readerName.length() > MAX_READER_NAME_LENGTH
                || !isIdentifierStart(readerName.charAt(0))) {
            return "unknown"; //$NON-NLS-1$
        }
        for (int i = 1; i < readerName.length(); i++) {
            if (!isIdentifierPart(readerName.charAt(i))) {
                return "unknown"; //$NON-NLS-1$
            }
        }
        return readerName;
    }

    private static boolean isIdentifierStart(char ch) {
        return ch >= 'A' && ch <= 'Z'
                || ch >= 'a' && ch <= 'z'
                || ch == '_' || ch == '$';
    }

    private static boolean isIdentifierPart(char ch) {
        return isIdentifierStart(ch) || ch >= '0' && ch <= '9';
    }
}
