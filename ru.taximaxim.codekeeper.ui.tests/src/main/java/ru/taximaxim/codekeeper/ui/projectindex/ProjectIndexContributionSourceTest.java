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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetry.Mode;

class ProjectIndexContributionSourceTest {

    private static final int CANONICALIZATION_RECORDS = 8_192;
    private static final String PUBLICATION_ID = "0".repeat(32);

    @Test
    void dataAdapterReplaysCanonicalFilesWithStableRunningIds()
            throws Exception {
        ProjectIndexData original =
                ProjectIndexFixtures.snapshotWithAllMetaKinds();
        ProjectIndexContributionSource source =
                ProjectIndexContributionSource.from(
                        ProjectIndexFixtures.reordered(original));

        List<String> first = new ArrayList<>();
        ProjectIndexContributionSource.PassCounts firstCounts =
                source.replay(() -> false,
                        (fileId, definitionStart, locationStart, file) -> {
                            assertCanonical(file);
                            first.add(fileId + ":" + file.path() + ":"
                                    + definitionStart + ":"
                                    + locationStart);
                        });
        List<String> second = new ArrayList<>();
        ProjectIndexContributionSource.PassCounts secondCounts =
                source.replay(() -> false,
                        (fileId, definitionStart, locationStart, file) ->
                            second.add(fileId + ":" + file.path() + ":"
                                    + definitionStart + ":"
                                    + locationStart));

        assertEquals(first, second);
        assertEquals(firstCounts, secondCounts);
        assertEquals(original.files().size(), firstCounts.files());
        assertEquals(original.files().stream()
                .mapToInt(file -> file.definitions().size()).sum(),
                firstCounts.definitions());
        assertEquals(original.files().stream()
                .mapToInt(file -> file.locations().size()).sum(),
                firstCounts.locations());
        assertEquals(original.files().stream()
                .filter(file -> file.unresolvedAny()
                        || !file.unresolvedCandidates().isEmpty())
                .count(), firstCounts.unresolvedFiles());
        for (int index = 1;
                index < source.manifest().files().size(); index++) {
            assertTrue(ProjectIndexFormat.PATH_ORDER.compare(
                    source.manifest().files().get(index - 1).path(),
                    source.manifest().files().get(index).path()) < 0);
        }
    }

    @Test
    void dataAdapterDoesNotRevalidateCanonicalizedRecords()
            throws Exception {
        ProjectIndexContributionSource source =
                ProjectIndexContributionSource.from(
                        ProjectIndexFixtures.repeatedLocations(
                                CANONICALIZATION_RECORDS));
        AtomicInteger polls = new AtomicInteger();
        AtomicInteger delivered = new AtomicInteger();

        source.replay(() -> {
            polls.incrementAndGet();
            return false;
        }, (fileId, definitionStart, locationStart, file) ->
            delivered.incrementAndGet());

        assertEquals(1, delivered.get());
        assertEquals(11, polls.get(),
                "Data replay must scan each canonical list only once");
    }

    @Test
    void cancellationStopsBeforeDeliveringAContribution() throws Exception {
        ProjectIndexContributionSource source =
                ProjectIndexContributionSource.from(
                        ProjectIndexFixtures.minimalSnapshot());
        AtomicInteger delivered = new AtomicInteger();

        assertThrows(ProjectIndexStore.WriteCancelledException.class,
                () -> source.replay(() -> true,
                        (fileId, definitionStart, locationStart, file) ->
                            delivered.incrementAndGet()));

        assertEquals(0, delivered.get());
    }

    @Test
    void cancellationStopsInsideCanonicalityScan() throws Exception {
        assertCancellationDuringCanonicalization(
                ProjectIndexFixtures.repeatedLocations(
                        CANONICALIZATION_RECORDS),
                2);
    }

    @Test
    void cancellationStopsInsideCanonicalizationCopy() throws Exception {
        assertCancellationDuringCanonicalization(
                withReversedLocations(CANONICALIZATION_RECORDS),
                2);
    }

    @Test
    void cancellationStopsInsideCanonicalizationSort() throws Exception {
        // Poll 1 enters canonicalization. The reversed-order check exits after
        // one comparison. Polls 2 through 9 are copy strides, poll 10 enters
        // the sort, and poll 11 is the first comparator stride.
        assertCancellationDuringCanonicalization(
                withReversedLocations(CANONICALIZATION_RECORDS),
                11);
    }

    @Test
    void cancellationStopsInsidePassCanonicalValidation(
            @TempDir Path temporary) throws Exception {
        ProjectIndexData data =
                ProjectIndexFixtures.repeatedLocations(
                        CANONICALIZATION_RECORDS);
        var polls = new AtomicInteger();
        var delivered = new AtomicInteger();

        try (var store = new ProjectIndexStore(
                temporary.resolve("validation-store"))) {
            assertEquals(ProjectIndexStore.PublishResult.PUBLISHED,
                    store.publish(data, () -> false));
            try (var opened = store.open(
                    ProjectIndexIdentity.from(data.manifest()))) {
                ProjectIndexReplaySource replay =
                        ProjectIndexReplaySource.merged(
                                opened.view().orElseThrow(), null,
                                () -> false);
                ProjectIndexContributionSource source =
                        ProjectIndexContributionSource.from(replay);

                assertThrows(
                        ProjectIndexStore.WriteCancelledException.class,
                        () -> source.replay(
                                () -> polls.incrementAndGet() >= 2,
                                (fileId, definitionStart,
                                        locationStart, file) ->
                                    delivered.incrementAndGet()));
            }
        }

        assertEquals(2, polls.get());
        assertEquals(0, delivered.get(),
                "Cancellation must stop validation before delivery");
    }

    @Test
    void unvalidatedPassRejectsNoncanonicalReplayContribution()
            throws Exception {
        ProjectIndexData data = withReversedLocations(16);
        AtomicInteger delivered = new AtomicInteger();
        var pass = new ProjectIndexContributionSource.Pass(
                data.manifest(), () -> false,
                (fileId, definitionStart, locationStart, file) ->
                    delivered.incrementAndGet());

        assertThrows(IllegalArgumentException.class,
                () -> pass.accept(data.files().getFirst()));
        assertEquals(0, delivered.get(),
                "Replay input must stay fail-closed");
    }

    @Test
    void writeContextValidatesOperationScopeAndReportsMetrics(
            @TempDir Path temporary) throws Exception {
        List<String> telemetryLines = new ArrayList<>();
        var run = new ProjectIndexTelemetry(telemetryLines::add, () -> 0)
                .start(Mode.COLD);
        var context = new ProjectIndexWriteContext(
                temporary.resolve("state/../state"), PUBLICATION_ID,
                () -> false, 64L << 20, run);

        context.requireNotCancelled();
        context.recordWriterMetrics(new ProjectIndexSpillStore.Metrics(
                3, 4_096, 2_048, 3_072, 2, 2), 8_192);
        run.close();

        assertEquals(temporary.resolve("state").toAbsolutePath().normalize(),
                context.stateDirectory());
        assertTrue(telemetryLines.getFirst()
                .contains("writer_buffer_budget_bytes=67108864"));
        assertTrue(telemetryLines.getFirst().contains("writer_runs=3"));
        assertTrue(telemetryLines.getFirst()
                .contains("writer_spill_bytes=4096"));
        assertTrue(telemetryLines.getFirst()
                .contains("writer_packed_bytes=8192"));
        assertThrows(IllegalArgumentException.class,
                () -> new ProjectIndexWriteContext(temporary,
                        "A".repeat(32), () -> false, 1, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ProjectIndexWriteContext(temporary,
                        PUBLICATION_ID, () -> false, 0, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ProjectIndexWriteContext(temporary,
                        PUBLICATION_ID, () -> false,
                        ProjectIndexFormat.MAX_PROSPECTIVE_BUILD_BYTES + 1,
                        null));
        var cancelled = new ProjectIndexWriteContext(
                temporary, PUBLICATION_ID, () -> true, 1, null);
        assertThrows(ProjectIndexStore.WriteCancelledException.class,
                cancelled::requireNotCancelled);
    }

    private static void assertCanonical(FileContribution file) {
        for (int index = 1; index < file.definitions().size(); index++) {
            assertTrue(ProjectIndexFormat.DEFINITION_ORDER.compare(
                    file.definitions().get(index - 1),
                    file.definitions().get(index)) <= 0);
        }
        for (int index = 1; index < file.locations().size(); index++) {
            assertTrue(ProjectIndexFormat.LOCATION_ORDER.compare(
                    file.locations().get(index - 1),
                    file.locations().get(index)) <= 0);
        }
    }

    private static void assertCancellationDuringCanonicalization(
            ProjectIndexData data, int cancelAtPoll) throws Exception {
        ProjectIndexContributionSource source =
                ProjectIndexContributionSource.from(data);
        var polls = new AtomicInteger();
        var delivered = new AtomicInteger();

        assertThrows(ProjectIndexStore.WriteCancelledException.class,
                () -> source.replay(
                        () -> polls.incrementAndGet() >= cancelAtPoll,
                        (fileId, definitionStart, locationStart, file) ->
                            delivered.incrementAndGet()));

        assertEquals(cancelAtPoll, polls.get());
        assertEquals(0, delivered.get(),
                "Cancellation must stop canonicalization before delivery");
    }

    private static ProjectIndexData withReversedLocations(int count)
            throws Exception {
        ProjectIndexData data =
                ProjectIndexFixtures.repeatedLocations(count);
        FileContribution original = data.files().getFirst();
        List<PackedLocation> locations =
                new ArrayList<>(original.locations());
        Collections.reverse(locations);
        FileContribution reversed = new FileContribution(
                original.path(), original.definitions(), locations,
                original.unresolvedCandidates(), original.unresolvedAny());
        return new ProjectIndexData(data.manifest(), List.of(reversed));
    }
}
