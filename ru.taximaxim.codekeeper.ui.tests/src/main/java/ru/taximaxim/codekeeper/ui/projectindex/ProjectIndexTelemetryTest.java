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

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetry.Mode;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetry.PersistenceReason;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetry.PersistenceStatus;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetry.Phase;

class ProjectIndexTelemetryTest {

    /**
     * The line a run publishes when nothing tells it about a warm validation.
     * Every warm-miss test drives exactly the same run, so a difference from
     * this line can only come from what the run was told about one.
     */
    private static final String WITHOUT_WARM_MISS =
            "pgCodeKeeper project index: mode=warm "
                    + "persistence_status=not_attempted "
                    + "persistence_reason=none paths_enumerated=120 "
                    + "enumeration_passes=1 single_file_validations=0 "
                    + "paths_hashed=2 paths_hash_inline=0 "
                    + "paths_hash_reread=2 paths_parsed=unknown "
                    + "paths_analyzed=unknown elapsed_ms=0";

    @Test
    void emitsOneSecretFreeAggregateLineWithAvailableBlockStatistics() {
        List<String> lines = new ArrayList<>();
        AtomicLong now = new AtomicLong(1_000_000);
        var telemetry = new ProjectIndexTelemetry(lines::add, now::get);

        try (var run = telemetry.start(Mode.COLD)) {
            run.mode(Mode.WARM);
            run.persistence(PersistenceStatus.HIT, PersistenceReason.NONE);
            run.observeEnumerationPass(120);
            run.addSingleFileValidations(3);
            run.addInlineHashedPaths(3);
            run.addRereadHashedPaths(1);
            run.addParsedPaths(0);
            run.addAnalyzedPaths(0);
            run.addExcludedPaths(16);
            run.blockCache(196_608, 131_072, 3);
            now.set(8_500_000);
        }

        assertEquals(List.of(
                "pgCodeKeeper project index: mode=warm "
                        + "persistence_status=hit persistence_reason=none "
                        + "paths_enumerated=120 enumeration_passes=1 "
                        + "single_file_validations=3 "
                        + "paths_hashed=4 paths_hash_inline=3 "
                        + "paths_hash_reread=1 paths_parsed=0 paths_analyzed=0 "
                        + "paths_excluded=16 block_read_bytes=196608 "
                        + "block_cache_bytes=131072 block_reads=3 "
                        + "elapsed_ms=7"),
                lines);
    }

    @Test
    void omitsUnavailableBlockStatisticsAndPublishesOnlyOnce() {
        List<String> lines = new ArrayList<>();
        AtomicLong now = new AtomicLong(10);
        var run = new ProjectIndexTelemetry(lines::add, now::get)
                .start(Mode.BYPASS);

        now.set(20);
        run.close();
        run.close();

        assertEquals(List.of(
                "pgCodeKeeper project index: mode=bypass "
                        + "persistence_status=not_attempted "
                        + "persistence_reason=none paths_enumerated=0 "
                        + "enumeration_passes=0 "
                        + "single_file_validations=0 "
                        + "paths_hashed=0 paths_hash_inline=0 "
                        + "paths_hash_reread=0 paths_parsed=unknown "
                        + "paths_analyzed=unknown "
                        + "elapsed_ms=0"), lines);
    }

    @Test
    void reportsLargestEnumerationAndCountsEveryCompletedPass() {
        List<String> lines = new ArrayList<>();
        var run = new ProjectIndexTelemetry(lines::add, () -> 0)
                .start(Mode.COLD);

        run.observeEnumerationPass(120);
        run.observeEnumerationPass(120);
        run.observeEnumerationPass(80);
        run.close();

        assertEquals(1, lines.size());
        assertTrue(lines.getFirst().contains("paths_enumerated=120"));
        assertTrue(lines.getFirst().contains("enumeration_passes=3"));
    }

    @Test
    void singlePathObservationDoesNotCountAsFullEnumeration() {
        List<String> lines = new ArrayList<>();
        var run = new ProjectIndexTelemetry(lines::add, () -> 0)
                .start(Mode.INCREMENTAL);

        run.observeEnumeratedPaths(1);
        run.close();

        assertTrue(lines.getFirst().contains(
                "paths_enumerated=1 enumeration_passes=0"));
    }

    @Test
    void aggregatesPhaseAndWriterMetricsWithStableTokens() {
        List<String> lines = new ArrayList<>();
        AtomicLong now = new AtomicLong();
        var run = new ProjectIndexTelemetry(lines::add, now::get)
                .start(Mode.COLD);

        run.persistence(PersistenceStatus.PUBLISHED, PersistenceReason.NONE);
        for (Phase phase : Phase.values()) {
            try (var ignored = run.phase(phase)) {
                now.addAndGet((phase.ordinal() + 1) * 1_000_000L);
            }
        }
        run.writerMetrics(4_096, 1, 512, 8_192);
        run.writerMetrics(4_096, 2, 256, 4_096);
        run.close();

        assertEquals(1, lines.size());
        String line = lines.getFirst();
        assertTrue(line.contains(
                "persistence_status=published persistence_reason=none"));
        assertTrue(line.contains(
                "phase_inspect_ms=1 phase_parse_ms=2 "
                        + "phase_analyze_ms=3 phase_load_analyze_ms=4 "
                        + "phase_pack_ms=5 phase_compact_ms=6 "
                        + "phase_publish_ms=7 phase_validate_ms=8 "
                        + "phase_encode_ms=9 phase_locator_crc_ms=10 "
                        + "phase_fsync_ms=11 phase_current_ms=12"));
        assertTrue(line.contains(
                "writer_buffer_budget_bytes=4096 writer_runs=3 "
                        + "writer_spill_bytes=768 writer_packed_bytes=12288"));
    }

    @Test
    void publishesTypedPackedPublicationSubphasesAndBoundedRereadBytes() {
        List<String> lines = new ArrayList<>();
        var run = new ProjectIndexTelemetry(lines::add, () -> 0)
                .start(Mode.COLD);

        run.addPhaseNanos(Phase.ENCODE, 1_000_000);
        run.addPhaseNanos(Phase.LOCATOR_CRC, 2_000_000);
        run.addPhaseNanos(Phase.FSYNC, 3_000_000);
        run.addPhaseNanos(Phase.CURRENT, 4_000_000);
        run.addPackedRereadBytes(-1);
        run.addPackedRereadBytes(65_536);
        run.close();

        String line = lines.getFirst();
        assertTrue(line.contains(
                "phase_encode_ms=1 phase_locator_crc_ms=2 "
                        + "phase_fsync_ms=3 phase_current_ms=4"));
        assertTrue(line.contains("writer_reread_bytes=65536"));
    }

    @Test
    void emitsObservedZeroDurationPhaseAndOmitsUnobservedPhases() {
        List<String> lines = new ArrayList<>();
        var run = new ProjectIndexTelemetry(lines::add, () -> 0)
                .start(Mode.INCREMENTAL);

        try (var ignored = run.phase(Phase.PARSE)) {
            // Deterministic zero-duration observation.
        }
        run.close();

        assertTrue(lines.getFirst().contains("phase_parse_ms=0"));
        assertFalse(lines.getFirst().contains("phase_analyze_ms="));
        assertFalse(lines.getFirst().contains("phase_load_analyze_ms="));
    }

    @Test
    void countersAndCompletionAreThreadSafe() throws Exception {
        List<String> lines = new ArrayList<>();
        var run = new ProjectIndexTelemetry(lines::add, () -> 0)
                .start(Mode.INCREMENTAL);
        int workers = 4;
        int iterations = 1_000;
        var pool = Executors.newFixedThreadPool(workers);
        try {
            var updates = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < workers; i++) {
                updates.add(pool.submit(() -> {
                    for (int j = 0; j < iterations; j++) {
                        run.addParsedPaths(1);
                        run.addAnalyzedPaths(1);
                    }
                }));
            }
            for (var update : updates) {
                update.get(5, TimeUnit.SECONDS);
            }
            var completions = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < workers; i++) {
                completions.add(pool.submit(run::close));
            }
            for (var completion : completions) {
                completion.get(5, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(1, lines.size());
        assertTrue(lines.getFirst().contains(
                "paths_parsed=4000 paths_analyzed=4000"));
    }

    @Test
    void loggerFailureCannotAffectIndexer() {
        var telemetry = new ProjectIndexTelemetry(message -> {
            throw new IllegalStateException("logging unavailable");
        }, () -> 0);

        assertDoesNotThrow(() -> telemetry.start(Mode.COLD).close());
    }

    @Test
    void warmMissPublishesHowFarTheTreeMovedAndTellsATruncatedCountApart() {
        var described = new ProjectIndexWarmValidator.Result(false, 2,
                List.of(path("a.sql"), path("b.sql")), List.of(path("c.sql")),
                List.of(path("d.sql"), path("e.sql"), path("f.sql")), true);
        // What a run that gave up at a divergence budget of one leaves behind:
        // one path, and the flag saying the rest was never looked at.
        var truncated = new ProjectIndexWarmValidator.Result(false, 0,
                List.of(path("a.sql")), List.of(), List.of(), false);

        assertEquals("pgCodeKeeper project index: mode=warm "
                + "persistence_status=not_attempted "
                + "persistence_reason=none paths_enumerated=120 "
                + "enumeration_passes=1 single_file_validations=0 "
                + "paths_hashed=2 paths_hash_inline=0 "
                + "paths_hash_reread=2 paths_parsed=unknown "
                + "paths_analyzed=unknown warm_miss_changed=2 "
                + "warm_miss_added=1 warm_miss_removed=3 "
                + "warm_miss_complete=true elapsed_ms=0",
                publishedLine(run -> run.warmMiss(described)));
        assertEquals("pgCodeKeeper project index: mode=warm "
                + "persistence_status=not_attempted "
                + "persistence_reason=none paths_enumerated=120 "
                + "enumeration_passes=1 single_file_validations=0 "
                + "paths_hashed=2 paths_hash_inline=0 "
                + "paths_hash_reread=2 paths_parsed=unknown "
                + "paths_analyzed=unknown warm_miss_changed=1 "
                + "warm_miss_added=0 warm_miss_removed=0 "
                + "warm_miss_complete=false elapsed_ms=0",
                publishedLine(run -> run.warmMiss(truncated)));
    }

    @Test
    void aWarmHitAndAnUnvalidatedRunPublishTheLineTheyAlwaysPublished() {
        var hit = new ProjectIndexWarmValidator.Result(true, 4, List.of(),
                List.of(), List.of(), true);

        assertEquals(WITHOUT_WARM_MISS, publishedLine(run -> {
            // A run that never validated a warm index.
        }));
        assertEquals(WITHOUT_WARM_MISS, publishedLine(run -> run.warmMiss(hit)));
        assertEquals(WITHOUT_WARM_MISS, publishedLine(run -> run.warmMiss(null)));
    }

    /**
     * Publishes one run, driven identically every time except for what it is
     * told about a warm validation.
     *
     * @param warmValidation what this run learns about a warm validation
     * @return the single line the run published
     */
    private static String publishedLine(
            Consumer<ProjectIndexTelemetry.Run> warmValidation) {
        List<String> lines = new ArrayList<>();
        var run = new ProjectIndexTelemetry(lines::add, () -> 0)
                .start(Mode.WARM);

        run.observeEnumerationPass(120);
        run.addRereadHashedPaths(2);
        warmValidation.accept(run);
        run.close();

        assertEquals(1, lines.size());
        return lines.getFirst();
    }

    private static IndexPathRef path(String relativePath) {
        return new IndexPathRef(IndexPathOrigin.PROJECT, relativePath);
    }
}
