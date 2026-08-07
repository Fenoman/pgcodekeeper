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

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.telemetry.ComparisonCancellationDrainResult;
import org.pgcodekeeper.core.telemetry.ComparisonCancellationDrainTelemetry;
import org.pgcodekeeper.core.telemetry.ComparisonStage;
import org.pgcodekeeper.core.telemetry.ComparisonStageTelemetry;
import org.pgcodekeeper.core.telemetry.PgCatalogCacheBypassReason;
import org.pgcodekeeper.core.telemetry.PgCatalogCacheMode;
import org.pgcodekeeper.core.telemetry.PgCatalogCacheRunTelemetry;
import org.pgcodekeeper.core.telemetry.PgCatalogReaderCacheTelemetry;
import org.pgcodekeeper.core.telemetry.PgConnectionLifecycleTelemetry;
import org.pgcodekeeper.core.telemetry.PgConnectionLifecycleTelemetry.Lifecycle;
import org.pgcodekeeper.core.telemetry.PgConnectionLifecycleTelemetry.LogicalSide;
import org.pgcodekeeper.core.telemetry.PgConnectionRole;
import org.pgcodekeeper.core.telemetry.PgRoutineBodyCacheTelemetry;

class EclipseComparisonTelemetryTest {

    private static final UUID RUN_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000123"); //$NON-NLS-1$

    @Test
    void emitsRunScopedSequenceAndEverySupportedAggregateEvent() {
        List<String> lines = new ArrayList<>();
        var telemetry = new EclipseComparisonTelemetry(RUN_ID, lines::add);

        telemetry.pgCatalogReaderFinished(new PgCatalogReaderCacheTelemetry(
                "PgTablesReader", PgCatalogCacheMode.WARM_CHANGED, //$NON-NLS-1$
                PgCatalogCacheBypassReason.MISS_RATIO, 101, 71, 30, 31, 29,
                401, 501, 601, 701, true, false, 1_500_001));
        telemetry.pgCatalogCacheFinished(new PgCatalogCacheRunTelemetry(
                12, 1, 100, 70, 30, 30, 20, 400, 500, 600, 700, 80,
                2_500_000));
        telemetry.pgRoutineBodyCacheFinished(new PgRoutineBodyCacheTelemetry(
                8, 2, 2, 900, 10, 3_500_000));
        telemetry.comparisonStageFinished(new ComparisonStageTelemetry(
                ComparisonStage.NEW_FULL_ANALYZE, 4_500_000));
        telemetry.pgConnectionLifecycle(new PgConnectionLifecycleTelemetry(
                LogicalSide.NEW, PgConnectionRole.CATALOG_LANE, 2,
                Lifecycle.OPENED, 12_345));
        telemetry.comparisonCancellationDrainFinished(
                new ComparisonCancellationDrainTelemetry(
                        ComparisonCancellationDrainResult.COMPLETED,
                        5_500_000));

        Assertions.assertEquals(6, lines.size());
        Assertions.assertEquals(
                prefix(1) + "pg_catalog_reader_finished" //$NON-NLS-1$
                        + " reader=PgTablesReader mode=WARM_CHANGED" //$NON-NLS-1$
                        + " bypass_reason=MISS_RATIO rows=101 hits=71" //$NON-NLS-1$
                        + " misses=30 fetched_rows=31 published_rows=29" //$NON-NLS-1$
                        + " hash_payload_bytes=401 encoded_row_bytes=501" //$NON-NLS-1$
                        + " pack_bytes_read=601 pack_bytes_written=701" //$NON-NLS-1$
                        + " fingerprint_probe_used=true" //$NON-NLS-1$
                        + " fingerprint_matched=false elapsed_nanos=1500001", //$NON-NLS-1$
                lines.getFirst());
        Assertions.assertEquals(
                prefix(2) + "pg_catalog_cache_finished readers=12" //$NON-NLS-1$
                        + " bypassed_readers=1 rows=100 hits=70 misses=30" //$NON-NLS-1$
                        + " fetched_rows=30 published_rows=20" //$NON-NLS-1$
                        + " hash_payload_bytes=400 encoded_row_bytes=500" //$NON-NLS-1$
                        + " pack_bytes_read=600 pack_bytes_written=700" //$NON-NLS-1$
                        + " pruned_bytes=80 elapsed_nanos=2500000", //$NON-NLS-1$
                lines.get(1));
        Assertions.assertEquals(
                prefix(3) + "pg_routine_body_cache_finished hits=8 misses=2" //$NON-NLS-1$
                        + " stored=2 saved_utf8_bytes=900 pruned_bytes=10" //$NON-NLS-1$
                        + " elapsed_nanos=3500000", //$NON-NLS-1$
                lines.get(2));
        Assertions.assertEquals(
                prefix(4) + "comparison_stage_finished" //$NON-NLS-1$
                        + " stage=NEW_FULL_ANALYZE elapsed_nanos=4500000", //$NON-NLS-1$
                lines.get(3));
        Assertions.assertEquals(
                prefix(5) + "pg_connection_lifecycle side=NEW" //$NON-NLS-1$
                        + " role=CATALOG_LANE lane=2 lifecycle=OPENED" //$NON-NLS-1$
                        + " backend_pid=12345", //$NON-NLS-1$
                lines.get(4));
        Assertions.assertEquals(
                prefix(6) + "comparison_cancellation_drain_finished" //$NON-NLS-1$
                        + " result=COMPLETED elapsed_nanos=5500000", //$NON-NLS-1$
                lines.get(5));
    }

    @Test
    void replacesUnsafeReaderNameWithoutPublishingItsContents() {
        List<String> lines = new ArrayList<>();
        var telemetry = new EclipseComparisonTelemetry(RUN_ID, lines::add);
        String unsafe = "PgTablesReader\nsql=select secret"; //$NON-NLS-1$

        telemetry.pgCatalogReaderFinished(new PgCatalogReaderCacheTelemetry(
                unsafe, PgCatalogCacheMode.BYPASS,
                PgCatalogCacheBypassReason.CACHE_ERROR, 0, 0, 0, 0, 0,
                0, 0, 0, 0, false, false, 0));

        Assertions.assertTrue(lines.getFirst().contains(" reader=unknown ")); //$NON-NLS-1$
        Assertions.assertFalse(lines.getFirst().contains(unsafe));
        Assertions.assertFalse(lines.getFirst().contains("secret")); //$NON-NLS-1$
    }

    @Test
    void concurrentCallbacksReceiveUniqueMonotonicSequenceNumbers() {
        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        var telemetry = new EclipseComparisonTelemetry(RUN_ID, lines::add);

        IntStream.range(0, 256).parallel().forEach(ignored ->
                telemetry.comparisonStageFinished(new ComparisonStageTelemetry(
                        ComparisonStage.PREPARE, 1)));

        Assertions.assertEquals(256, lines.size());
        var sequences = new HashSet<Long>();
        List<String> orderedLines = List.copyOf(lines);
        for (int i = 0; i < orderedLines.size(); i++) {
            String line = orderedLines.get(i);
            int start = line.indexOf(" seq=") + 5; //$NON-NLS-1$
            int end = line.indexOf(" event=", start); //$NON-NLS-1$
            long sequence = Long.parseLong(line.substring(start, end));
            Assertions.assertEquals(i + 1L, sequence);
            sequences.add(sequence);
        }
        Assertions.assertEquals(256, sequences.size());
        Assertions.assertEquals(1L, Collections.min(sequences));
        Assertions.assertEquals(256L, Collections.max(sequences));
    }

    @Test
    void exposesTypedUiStagesAndRunOutcomeOnTheSameRunSequence() {
        List<String> lines = new ArrayList<>();
        var clock = new AtomicLong(1_000);
        var telemetry = new EclipseComparisonTelemetry(
                RUN_ID, lines::add, clock::get);

        long runStartedNanos = telemetry.startTimer();
        clock.set(1_125);
        telemetry.uiStageFinished(
                EclipseComparisonTelemetry.UiStage.CORE_LOAD,
                runStartedNanos);
        clock.set(900);
        telemetry.uiStageFinished(
                EclipseComparisonTelemetry.UiStage.UI_QUEUE,
                runStartedNanos);
        clock.set(1_250);
        telemetry.runFinished(
                EclipseComparisonTelemetry.RunOutcome.CANCELLED,
                runStartedNanos);

        Assertions.assertTrue(Modifier.isPublic(
                EclipseComparisonTelemetry.class.getModifiers()));
        Assertions.assertEquals(List.of(
                prefix(1) + "ui_stage_finished" //$NON-NLS-1$
                        + " stage=CORE_LOAD elapsed_nanos=125", //$NON-NLS-1$
                prefix(2) + "ui_stage_finished" //$NON-NLS-1$
                        + " stage=UI_QUEUE elapsed_nanos=0", //$NON-NLS-1$
                prefix(3) + "run_finished" //$NON-NLS-1$
                        + " outcome=CANCELLED elapsed_nanos=250"), //$NON-NLS-1$
                lines);
    }

    @Test
    void emitsTypedProjectModelCacheEventsWithExactSafeFormatting() {
        List<String> lines = new ArrayList<>();
        var clock = new AtomicLong(10_000);
        var telemetry = new EclipseComparisonTelemetry(
                RUN_ID, lines::add, clock::get);

        long validationStartedNanos = telemetry.startTimer();
        clock.set(10_125);
        telemetry.projectModelCacheFinished(
                EclipseComparisonTelemetry.ProjectModelCacheStatus.HIT,
                EclipseComparisonTelemetry.ProjectModelFailClosedReason.NONE,
                22_183, 7, validationStartedNanos);

        clock.set(10_250);
        telemetry.projectModelCacheFinished(
                EclipseComparisonTelemetry.ProjectModelCacheStatus.REJECTED,
                EclipseComparisonTelemetry.ProjectModelFailClosedReason.INPUT_CHANGED,
                22_183, 1, validationStartedNanos);

        Assertions.assertEquals(List.of(
                prefix(1) + "project_model_cache_finished status=hit" //$NON-NLS-1$
                        + " reason=none files_inspected=22183" //$NON-NLS-1$
                        + " files_hashed=7 elapsed_nanos=125", //$NON-NLS-1$
                prefix(2) + "project_model_cache_finished status=rejected" //$NON-NLS-1$
                        + " reason=input_changed files_inspected=22183" //$NON-NLS-1$
                        + " files_hashed=1 elapsed_nanos=250"), //$NON-NLS-1$
                lines);
    }

    @Test
    void emitsExactProjectInputChangeStageWithoutFilePaths() {
        List<String> lines = new ArrayList<>();
        var telemetry = new EclipseComparisonTelemetry(
                RUN_ID, lines::add);

        telemetry.projectInputsChanged(
                EclipseComparisonTelemetry.ProjectInputChangeStage.FILE_SET);

        Assertions.assertEquals(List.of(
                prefix(1) + "project_inputs_changed stage=file_set"), //$NON-NLS-1$
                lines);
    }

    @Test
    void projectModelCacheEventHasClosedVocabularyAndSanitizesNumbers() {
        List<String> lines = new ArrayList<>();
        var clock = new AtomicLong(900);
        var telemetry = new EclipseComparisonTelemetry(
                RUN_ID, lines::add, clock::get);

        telemetry.projectModelCacheFinished(
                EclipseComparisonTelemetry.ProjectModelCacheStatus.MISS,
                EclipseComparisonTelemetry.ProjectModelFailClosedReason.NO_MODEL,
                -1, -2, 1_000);
        telemetry.projectModelCacheFinished(null,
                EclipseComparisonTelemetry.ProjectModelFailClosedReason.STALE,
                1, 1, 1_000);
        telemetry.projectModelCacheFinished(
                EclipseComparisonTelemetry.ProjectModelCacheStatus.REJECTED,
                null, 1, 1, 1_000);

        Assertions.assertEquals(List.of(
                prefix(1) + "project_model_cache_finished status=miss" //$NON-NLS-1$
                        + " reason=no_model files_inspected=0" //$NON-NLS-1$
                        + " files_hashed=0 elapsed_nanos=0"), //$NON-NLS-1$
                lines);
        Assertions.assertArrayEquals(new String[] {
                "hit", "hit_disk", "miss", "published", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "rejected" //$NON-NLS-1$
        }, java.util.Arrays.stream(
                EclipseComparisonTelemetry.ProjectModelCacheStatus.values())
                .map(Object::toString)
                .toArray(String[]::new));
        Assertions.assertArrayEquals(new String[] {
                "hit", "miss", "stale", "corrupt", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "retryable", "published", "unavailable" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }, java.util.Arrays.stream(
                EclipseComparisonTelemetry.ProjectAnalysisStoreStatus.values())
                .map(Object::toString)
                .toArray(String[]::new));
        Assertions.assertArrayEquals(new String[] {
                "none", "input_changed", "profile_changed", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "version_mismatch", "libraries", "one_time", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "unsupported", "no_model", "cancelled", "stale", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "structural_only" //$NON-NLS-1$
        }, java.util.Arrays.stream(
                EclipseComparisonTelemetry.ProjectModelFailClosedReason
                        .values())
                .map(Object::toString)
                .toArray(String[]::new));
    }

    @Test
    void exposesProjectModelStagesOnExistingUiStageApi() {
        List<String> lines = new ArrayList<>();
        var clock = new AtomicLong(2_000);
        var telemetry = new EclipseComparisonTelemetry(
                RUN_ID, lines::add, clock::get);

        telemetry.uiStageFinished(
                EclipseComparisonTelemetry.UiStage.MODEL_VALIDATE, 1_900);
        telemetry.uiStageFinished(
                EclipseComparisonTelemetry.UiStage.MODEL_PUBLISH, 1_800);

        Assertions.assertEquals(List.of(
                prefix(1) + "ui_stage_finished" //$NON-NLS-1$
                        + " stage=MODEL_VALIDATE elapsed_nanos=100", //$NON-NLS-1$
                prefix(2) + "ui_stage_finished" //$NON-NLS-1$
                        + " stage=MODEL_PUBLISH elapsed_nanos=200"), //$NON-NLS-1$
                lines);
    }

    @Test
    void loggerFailureCannotAffectComparison() {
        var telemetry = new EclipseComparisonTelemetry(RUN_ID, message -> {
            throw new IllegalStateException("logging unavailable");
        });

        Assertions.assertDoesNotThrow(() -> telemetry.pgCatalogCacheFinished(
                new PgCatalogCacheRunTelemetry(0, 0, 0, 0, 0, 0, 0, 0,
                        0, 0, 0, 0, 0)));
    }

    private static String prefix(long sequence) {
        return "pgCodeKeeper comparison: run_id=" + RUN_ID //$NON-NLS-1$
                + " seq=" + sequence + " event="; //$NON-NLS-1$ //$NON-NLS-2$
    }
}
