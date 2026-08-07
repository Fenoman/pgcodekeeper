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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32C;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectIndexSpillStoreTest {

    private static final String PUBLICATION_ID =
            "0123456789abcdef0123456789abcdef";
    private static final String SECOND_PUBLICATION_ID =
            "fedcba9876543210fedcba9876543210";
    private static final int RUN_HEADER_BYTES = 40;
    private static final int RUN_FOOTER_BYTES = 12;
    private static final int COUNTS_HEADER_BYTES = 40;
    private static final int COUNTS_FOOTER_BYTES = 12;

    @Test
    void forcedMultiRunMergeIsUnsignedAndRepeatable(
            @TempDir Path stateDirectory) throws Exception {
        try (var store = new ProjectIndexSpillStore(stateDirectory,
                PUBLICATION_ID, () -> false)) {
            var runs = store.section(SectionType.BY_MATCH_KEY);
            runs.spill(triples(
                    row(-1L, 1, 0),
                    row(0, Long.MIN_VALUE, 5),
                    row(0, 1, 9)));
            runs.spill(triples(
                    row(Long.MIN_VALUE, 0, 0),
                    row(0, -1L, 0)));
            runs.finish(triples(
                    row(0, 0, -1L),
                    row(0, Long.MIN_VALUE, 4)));

            List<long[]> first = readAll(runs);
            List<long[]> second = readAll(runs);

            assertRowsEqual(first, second);
            assertRowsEqual(List.of(
                    row(0, 0, -1L),
                    row(0, 1, 9),
                    row(0, Long.MIN_VALUE, 4),
                    row(0, Long.MIN_VALUE, 5),
                    row(0, -1L, 0),
                    row(Long.MIN_VALUE, 0, 0),
                    row(-1L, 1, 0)), first);
        }
    }

    @Test
    void deduplicatesExactRowsAcrossRunsAndTail(
            @TempDir Path stateDirectory) throws Exception {
        try (var store = new ProjectIndexSpillStore(stateDirectory,
                PUBLICATION_ID, () -> false)) {
            var runs = store.section(SectionType.COMPLETION_TRIGRAMS);
            runs.spill(words(9, 2, 9, Long.MIN_VALUE));
            runs.spill(words(-1L, 2, Long.MIN_VALUE));
            runs.finish(words(9, 0, -1L, 0));

            List<long[]> actual = readAll(runs);

            assertRowsEqual(List.of(
                    row(0),
                    row(2),
                    row(9),
                    row(Long.MIN_VALUE),
                    row(-1L)), actual);
        }
    }

    @Test
    void reverseDependenciesDeduplicateAcrossConsolidationsAndTail(
            @TempDir Path stateDirectory) throws Exception {
        try (var store = new ProjectIndexSpillStore(stateDirectory,
                PUBLICATION_ID, () -> false)) {
            var runs = store.section(SectionType.REVERSE_DEPENDENCIES);
            for (int value = 11; value >= 0; value--) {
                runs.spill(triples(
                        row(2, value, 99),
                        row(1, 1, 1),
                        row(2, value, 99)));
            }
            runs.finish(triples(
                    row(1, 1, 1),
                    row(2, 5, 99),
                    row(2, 12, 99),
                    row(2, 12, 99)));

            var expected = new ArrayList<long[]>();
            expected.add(row(1, 1, 1));
            for (int value = 0; value <= 12; value++) {
                expected.add(row(2, value, 99));
            }
            assertRowsEqual(expected, readAll(runs));
            assertTrue(store.metrics().materializedRuns() > 12);
        }
    }

    @Test
    void duplicateMatchKeyAcrossRunsFailsClosed(
            @TempDir Path stateDirectory) throws Exception {
        try (var store = new ProjectIndexSpillStore(stateDirectory,
                PUBLICATION_ID, () -> false)) {
            var runs = store.section(SectionType.BY_MATCH_KEY);
            runs.spill(triples(row(4, 5, 6)));
            runs.spill(triples(row(4, 5, 6)));
            runs.finish(triples());

            assertThrows(ProjectIndexFormatException.class,
                    () -> readAll(runs));
        }
    }

    @Test
    void duplicateMatchKeyWithinRunFailsClosed(
            @TempDir Path stateDirectory) throws Exception {
        try (var store = new ProjectIndexSpillStore(stateDirectory,
                PUBLICATION_ID, () -> false)) {
            var runs = store.section(SectionType.BY_MATCH_KEY);
            runs.spill(triples(
                    row(4, 5, 6),
                    row(4, 5, 6)));
            runs.finish(triples());

            assertThrows(ProjectIndexFormatException.class,
                    () -> readAll(runs));
        }
    }

    @Test
    void duplicateMatchKeyAcrossRunAndTailFailsClosed(
            @TempDir Path stateDirectory) throws Exception {
        try (var store = new ProjectIndexSpillStore(stateDirectory,
                PUBLICATION_ID, () -> false)) {
            var runs = store.section(SectionType.BY_MATCH_KEY);
            runs.spill(triples(row(4, 5, 6)));
            runs.finish(triples(row(4, 5, 6)));

            assertThrows(ProjectIndexFormatException.class,
                    () -> readAll(runs));
        }
    }

    @Test
    void duplicateMatchKeyAfterConsolidationFailsClosed(
            @TempDir Path stateDirectory) throws Exception {
        try (var store = new ProjectIndexSpillStore(stateDirectory,
                PUBLICATION_ID, () -> false)) {
            var runs = store.section(SectionType.BY_MATCH_KEY);
            for (int value = 0;
                    value < ProjectIndexSpillStore.MAX_OPEN_RUNS;
                    value++) {
                runs.spill(triples(row(value, 1, 2)));
            }
            runs.spill(triples(
                    row(ProjectIndexSpillStore.MAX_OPEN_RUNS, 1, 2)));
            assertEquals(3, runs.registeredRunCountForTests());
            runs.spill(triples(row(0, 1, 2)));
            runs.finish(triples());

            assertThrows(ProjectIndexFormatException.class,
                    () -> readAll(runs));
        }
    }

    @Test
    void sameMatchKeyWithDifferentMembershipRemainsDistinct(
            @TempDir Path stateDirectory) throws Exception {
        try (var store = new ProjectIndexSpillStore(stateDirectory,
                PUBLICATION_ID, () -> false)) {
            var runs = store.section(SectionType.BY_MATCH_KEY);
            runs.spill(triples(row(4, 5, 7)));
            runs.finish(triples(row(4, 5, 6)));

            assertRowsEqual(List.of(
                    row(4, 5, 6),
                    row(4, 5, 7)), readAll(runs));
        }
    }

    @Test
    void unsupportedSectionCannotRegister(
            @TempDir Path stateDirectory) {
        try (var store = new ProjectIndexSpillStore(stateDirectory,
                PUBLICATION_ID, () -> false)) {
            assertThrows(IllegalArgumentException.class,
                    () -> store.section(SectionType.MANIFEST));
        }
    }

    @Test
    void emptyAndSingleInMemoryTailsNeverCreateWorkspace(
            @TempDir Path temporary) throws Exception {
        Path stateDirectory = temporary.resolve("state");
        try (var store = new ProjectIndexSpillStore(stateDirectory,
                PUBLICATION_ID, () -> false)) {
            var empty = store.section(SectionType.COMPLETION_TRIGRAMS);
            empty.finish(words());
            assertTrue(readAll(empty).isEmpty());

            var single = store.section(SectionType.REVERSE_DEPENDENCIES);
            single.finish(triples(row(-1L, Long.MIN_VALUE, 0)));
            assertRowsEqual(List.of(row(-1L, Long.MIN_VALUE, 0)),
                    readAll(single));

            assertFalse(Files.exists(stateDirectory));
            assertEquals(0, store.metrics().materializedRuns());
            assertEquals(0, store.metrics().physicalSpillBytes());
        }
        assertFalse(Files.exists(stateDirectory));
    }

    @Test
    void tailOnlyFinishAccountsResidentBytesImmediately(
            @TempDir Path stateDirectory) throws Exception {
        int capacity = 257;
        try (var store = new ProjectIndexSpillStore(stateDirectory,
                PUBLICATION_ID, () -> false)) {
            var runs = store.section(SectionType.BY_MATCH_KEY);
            var tail = new ProjectIndexTupleBuffer(3, capacity);
            tail.add(3, 2, 1);

            runs.finish(tail);

            long expectedBufferBytes = ProjectIndexFormat.arrayBytes(
                    (long) capacity * 3, Long.BYTES);
            assertEquals(expectedBufferBytes,
                    store.metrics().peakTrackedBufferBytes());
            assertEquals(expectedBufferBytes,
                    store.metrics().peakTrackedResidentBytes());
        }
    }

    @Test
    void hundredsOfRunsKeepFanInAndDescriptorsBounded(
            @TempDir Path stateDirectory) throws Exception {
        try (var store = new ProjectIndexSpillStore(stateDirectory,
                PUBLICATION_ID, () -> false)) {
            var runs = store.section(SectionType.COMPLETION_TRIGRAMS);
            for (int value = 199; value >= 0; value--) {
                runs.spill(words(value));
            }
            runs.finish(words());

            var expected = new ArrayList<long[]>(200);
            for (int value = 0; value < 200; value++) {
                expected.add(row(value));
            }
            assertRowsEqual(expected, readAll(runs));
            assertTrue(store.metrics().materializedRuns() > 200);
            assertTrue(store.metrics().peakOpenRuns()
                    <= ProjectIndexSpillStore.MAX_OPEN_RUNS);
            assertTrue(store.metrics().peakRegisteredRunDescriptors()
                    <= ProjectIndexSpillStore.MAX_OPEN_RUNS);
            assertTrue(runs.registeredRunCountForTests()
                    <= ProjectIndexSpillStore.MAX_OPEN_RUNS);
        }
    }

    @Test
    void actualRunChannelCountIsBoundedAndReturnsToZero(
            @TempDir Path stateDirectory) throws Exception {
        AtomicInteger observedPeak = new AtomicInteger();
        AtomicReference<ProjectIndexSpillStore> storeReference =
                new AtomicReference<>();
        ProjectIndexSpillStore.IoHook hook = point -> {
            ProjectIndexSpillStore observed = storeReference.get();
            if (observed != null) {
                observedPeak.accumulateAndGet(
                        observed.openRunChannelCountForTests(), Math::max);
            }
        };
        var store = new ProjectIndexSpillStore(stateDirectory,
                PUBLICATION_ID, () -> false, hook);
        storeReference.set(store);
        try {
            var runs = store.section(SectionType.COMPLETION_TRIGRAMS);
            for (int value = 0;
                    value <= ProjectIndexSpillStore.MAX_OPEN_RUNS;
                    value++) {
                runs.spill(words(value));
            }

            assertEquals(0, store.openRunChannelCountForTests());
            assertEquals(observedPeak.get(),
                    store.metrics().peakOpenRuns());
            assertTrue(observedPeak.get()
                    <= ProjectIndexSpillStore.MAX_OPEN_RUNS,
                    "Observed " + observedPeak.get()
                            + " simultaneously open run channels");

            runs.finish(words());
            int registered = runs.registeredRunCountForTests();
            try (var cursor = runs.openCursor()) {
                assertEquals(registered,
                        store.openRunChannelCountForTests());
                while (cursor.next()) {
                    // Consume all readers so each must close.
                }
            }
            assertEquals(0, store.openRunChannelCountForTests());
        } finally {
            store.close();
        }
        assertEquals(0, store.openRunChannelCountForTests());
    }

    @Test
    void groupCountsConsolidationKeepsCombinedChannelsBounded(
            @TempDir Path stateDirectory) throws Exception {
        try (var store = new ProjectIndexSpillStore(stateDirectory,
                PUBLICATION_ID, () -> false)) {
            var runs = store.section(SectionType.COMPLETION_TRIGRAMS);
            for (int value = 0;
                    value < ProjectIndexSpillStore.MAX_OPEN_RUNS;
                    value++) {
                runs.spill(words(value));
            }
            runs.finish(words());

            try (var counts = runs.openGroupCounts()) {
                assertEquals(2, runs.registeredRunCountForTests());
                assertEquals(1, store.openRunChannelCountForTests());
                assertEquals(ProjectIndexSpillStore.MAX_OPEN_RUNS,
                        store.metrics().peakOpenRuns());

                for (int value = 0;
                        value < ProjectIndexSpillStore.MAX_OPEN_RUNS;
                        value++) {
                    counts.add(1);
                }
                counts.finish();

                try (var cursor = runs.openCursor()) {
                    assertEquals(3,
                            store.openRunChannelCountForTests());
                    int rows = 0;
                    while (cursor.next()) {
                        assertEquals(rows, cursor.word(0));
                        rows++;
                    }
                    assertEquals(ProjectIndexSpillStore.MAX_OPEN_RUNS,
                            rows);
                }
                assertEquals(1, store.openRunChannelCountForTests());

                int groups = 0;
                while (counts.next()) {
                    assertEquals(1, counts.value(0));
                    groups++;
                }
                assertEquals(ProjectIndexSpillStore.MAX_OPEN_RUNS,
                        groups);
            }
            assertEquals(0, store.openRunChannelCountForTests());
        }
    }

    @Test
    void finishedGroupCountsAccountsPhysicalBytesExactlyOnce(
            @TempDir Path stateDirectory) throws Exception {
        Path countsPath = groupCountsPath(
                stateDirectory, SectionType.BY_MATCH_KEY);
        try (var store = new ProjectIndexSpillStore(stateDirectory,
                PUBLICATION_ID, () -> false)) {
            var runs = store.section(SectionType.BY_MATCH_KEY);
            runs.finish(triples());

            try (var counts = runs.openGroupCounts()) {
                counts.add(2, 3);
                counts.add(5, 7);
                counts.finish();

                long expectedBytes = COUNTS_HEADER_BYTES
                        + 2L * 2 * Integer.BYTES
                        + COUNTS_FOOTER_BYTES;
                assertEquals(expectedBytes, Files.size(countsPath));
                ByteBuffer raw = ByteBuffer
                        .wrap(Files.readAllBytes(countsPath))
                        .order(ByteOrder.BIG_ENDIAN);
                raw.position(COUNTS_HEADER_BYTES);
                assertEquals(2, raw.getInt());
                assertEquals(3, raw.getInt());
                assertEquals(5, raw.getInt());
                assertEquals(7, raw.getInt());
                assertEquals(0, store.metrics().materializedRuns());
                assertEquals(expectedBytes,
                        store.metrics().physicalSpillBytes());

                assertThrows(IllegalStateException.class,
                        counts::finish);
                assertEquals(expectedBytes,
                        store.metrics().physicalSpillBytes());
            }
        }
    }

    @Test
    void groupCountsHeaderWriteFailureCleansUpAndCanRetry(
            @TempDir Path stateDirectory) throws Exception {
        AtomicBoolean failWrite = new AtomicBoolean(true);
        ProjectIndexSpillStore.IoHook hook = point -> {
            if (point == ProjectIndexSpillStore.IoPoint.RUN_WRITE_CHUNK
                    && failWrite.getAndSet(false)) {
                throw new IOException(
                        "injected group count header failure");
            }
        };
        Path countsPath = groupCountsPath(
                stateDirectory, SectionType.COMPLETION_TRIGRAMS);
        try (var store = new ProjectIndexSpillStore(stateDirectory,
                PUBLICATION_ID, () -> false, hook)) {
            var runs = store.section(SectionType.COMPLETION_TRIGRAMS);
            runs.finish(words());

            assertThrows(IOException.class, runs::openGroupCounts);

            assertEquals(0, store.openRunChannelCountForTests());
            assertFalse(Files.exists(countsPath));
            assertTrue(workspaceEntries(stateDirectory).isEmpty());

            try (var counts = runs.openGroupCounts()) {
                counts.add(4);
                counts.finish();
                assertTrue(counts.next());
                assertEquals(4, counts.value(0));
                assertFalse(counts.next());
            }
            assertEquals(0, store.openRunChannelCountForTests());
            assertFalse(Files.exists(countsPath));
        }
    }

    @Test
    void cancelledGroupCountsFinishCanRetryAndCloseCleansUp(
            @TempDir Path stateDirectory) throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        Path countsPath = groupCountsPath(
                stateDirectory, SectionType.BY_MATCH_KEY);
        try (var store = new ProjectIndexSpillStore(stateDirectory,
                PUBLICATION_ID, cancelled::get)) {
            var runs = store.section(SectionType.BY_MATCH_KEY);
            runs.finish(triples());

            try (var counts = runs.openGroupCounts()) {
                counts.add(2, 3);
                cancelled.set(true);

                assertThrows(
                        ProjectIndexStore.WriteCancelledException.class,
                        counts::finish);

                assertEquals(1, counts.rows());
                assertEquals(1, store.openRunChannelCountForTests());
                assertTrue(Files.exists(countsPath));

                cancelled.set(false);
                counts.finish();
                assertTrue(counts.next());
                assertEquals(2, counts.value(0));
                assertEquals(3, counts.value(1));
                assertFalse(counts.next());
            }
            assertEquals(0, store.openRunChannelCountForTests());
            assertFalse(Files.exists(countsPath));
        }
    }

    @Test
    void groupCountsReadFailureCleansUpAndFreshStoreCanRetry(
            @TempDir Path stateDirectory) throws Exception {
        AtomicBoolean failRead = new AtomicBoolean(true);
        ProjectIndexSpillStore.IoHook hook = point -> {
            if (point == ProjectIndexSpillStore.IoPoint.RUN_READ_CHUNK
                    && failRead.getAndSet(false)) {
                throw new IOException(
                        "injected group count read failure");
            }
        };
        Path countsPath = groupCountsPath(
                stateDirectory, SectionType.COMPLETION_TRIGRAMS);
        try (var store = new ProjectIndexSpillStore(stateDirectory,
                PUBLICATION_ID, () -> false, hook)) {
            var runs = store.section(SectionType.COMPLETION_TRIGRAMS);
            runs.finish(words());
            try (var counts = runs.openGroupCounts()) {
                counts.add(9);
                counts.finish();

                assertThrows(IOException.class, counts::next);
            }
            assertEquals(0, store.openRunChannelCountForTests());
            assertFalse(Files.exists(countsPath));
        }
        assertFalse(Files.exists(
                workspace(stateDirectory, PUBLICATION_ID)));

        try (var retryStore = new ProjectIndexSpillStore(stateDirectory,
                PUBLICATION_ID, () -> false)) {
            var retryRuns = retryStore.section(
                    SectionType.COMPLETION_TRIGRAMS);
            retryRuns.finish(words());
            try (var retryCounts = retryRuns.openGroupCounts()) {
                retryCounts.add(9);
                retryCounts.finish();
                assertTrue(retryCounts.next());
                assertEquals(9, retryCounts.value(0));
                assertFalse(retryCounts.next());
            }
            assertEquals(0,
                    retryStore.openRunChannelCountForTests());
        }
    }

    @Test
    void ninthRunChannelIsRejectedWithoutChangingPendingSpill(
            @TempDir Path stateDirectory) throws Exception {
        try (var store = new ProjectIndexSpillStore(stateDirectory,
                PUBLICATION_ID, () -> false)) {
            var readers = store.section(SectionType.COMPLETION_TRIGRAMS);
            for (int value = 0;
                    value < ProjectIndexSpillStore.MAX_OPEN_RUNS;
                    value++) {
                readers.spill(words(value));
            }
            readers.finish(words());
            var writer = store.section(SectionType.BY_MATCH_KEY);
            ProjectIndexTupleBuffer pending = triples(row(9, 8, 7));

            try (var cursor = readers.openCursor()) {
                assertEquals(ProjectIndexSpillStore.MAX_OPEN_RUNS,
                        store.openRunChannelCountForTests());
                assertThrows(IOException.class,
                        () -> writer.spill(pending));
                assertEquals(1, pending.rowCount());
                assertEquals(0, writer.registeredRunCountForTests());
                assertEquals(ProjectIndexSpillStore.MAX_OPEN_RUNS,
                        store.openRunChannelCountForTests());
                assertEquals(ProjectIndexSpillStore.MAX_OPEN_RUNS,
                        store.metrics().peakOpenRuns());
            }
            assertEquals(0, store.openRunChannelCountForTests());

            writer.spill(pending);
            assertEquals(0, pending.rowCount());
            assertEquals(1, writer.registeredRunCountForTests());
        }
    }

    @Test
    void consolidationAccountsIncomingBufferAndEightPages(
            @TempDir Path stateDirectory) throws Exception {
        try (var store = new ProjectIndexSpillStore(stateDirectory,
                PUBLICATION_ID, () -> false)) {
            var runs = store.section(SectionType.COMPLETION_TRIGRAMS);
            for (int value = 0;
                    value < ProjectIndexSpillStore.MAX_OPEN_RUNS;
                    value++) {
                runs.spill(words(value));
            }
            var incoming = new ProjectIndexTupleBuffer(
                    1, ProjectIndexSpillStore.PAGE_BYTES);
            incoming.add(99);

            runs.spill(incoming);

            long expectedBufferBytes = ProjectIndexFormat.arrayBytes(
                    ProjectIndexSpillStore.PAGE_BYTES, Long.BYTES);
            long pageBytes = ProjectIndexFormat.arrayBytes(
                    ProjectIndexSpillStore.PAGE_BYTES, Byte.BYTES);
            long expectedResidentBytes = expectedBufferBytes
                    + (long) ProjectIndexSpillStore.MAX_OPEN_RUNS * pageBytes
                    + (long) ProjectIndexSpillStore.MAX_OPEN_RUNS * 64;
            assertEquals(expectedBufferBytes,
                    store.metrics().peakTrackedBufferBytes());
            assertEquals(expectedResidentBytes,
                    store.metrics().peakTrackedResidentBytes());
        }
    }

    @Test
    void onlyOneSequentialCursorMayBeOpenAtATime(
            @TempDir Path stateDirectory) throws Exception {
        try (var store = new ProjectIndexSpillStore(stateDirectory,
                PUBLICATION_ID, () -> false)) {
            var runs = store.section(SectionType.COMPLETION_TRIGRAMS);
            runs.finish(words(2, 1));

            try (var first = runs.openCursor()) {
                assertThrows(IllegalStateException.class, runs::openCursor);
            }
            assertRowsEqual(List.of(row(1), row(2)), readAll(runs));
        }
    }

    @Test
    void invalidRunFormatFailsClosed(
            @TempDir Path temporary) throws Exception {
        assertRunMutationRejected(temporary.resolve("header-magic"), path -> {
            byte[] bytes = Files.readAllBytes(path);
            bytes[0] ^= 1;
            Files.write(path, bytes);
        });
        assertRunMutationRejected(temporary.resolve("version"), path -> {
            byte[] bytes = Files.readAllBytes(path);
            ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                    .putInt(8, -1);
            Files.write(path, bytes);
        });
        assertRunMutationRejected(temporary.resolve("section"), path -> {
            byte[] bytes = Files.readAllBytes(path);
            ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                    .putInt(12, SectionType.REVERSE_DEPENDENCIES.id());
            Files.write(path, bytes);
        });
        assertRunMutationRejected(temporary.resolve("width"), path -> {
            byte[] bytes = Files.readAllBytes(path);
            ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                    .putInt(16, 3);
            Files.write(path, bytes);
        });
        assertRunMutationRejected(temporary.resolve("policy"), path -> {
            byte[] bytes = Files.readAllBytes(path);
            ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                    .putInt(20, 1);
            Files.write(path, bytes);
        });
        assertRunMutationRejected(temporary.resolve("payload-crc"), path -> {
            byte[] bytes = Files.readAllBytes(path);
            bytes[RUN_HEADER_BYTES + Long.BYTES - 1] = 0;
            Files.write(path, bytes);
        });
        assertRunMutationRejected(temporary.resolve("truncated"), path -> {
            try (FileChannel channel = FileChannel.open(
                    path, StandardOpenOption.WRITE)) {
                channel.truncate(channel.size() - 1);
            }
        });
        assertRunMutationRejected(temporary.resolve("layout"), path -> {
            byte[] bytes = Files.readAllBytes(path);
            ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                    .putLong(24, Long.MAX_VALUE);
            Files.write(path, bytes);
        });
        assertRunMutationRejected(temporary.resolve("footer-crc"), path -> {
            byte[] bytes = Files.readAllBytes(path);
            bytes[bytes.length - RUN_FOOTER_BYTES] ^= 1;
            Files.write(path, bytes);
        });
        assertRunMutationRejected(temporary.resolve("footer-magic"), path -> {
            byte[] bytes = Files.readAllBytes(path);
            bytes[bytes.length - 1] ^= 1;
            Files.write(path, bytes);
        });
        assertRunMutationRejected(temporary.resolve("unsigned-order"), path -> {
            byte[] bytes = Files.readAllBytes(path);
            ByteBuffer payload =
                    ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
            payload.putLong(RUN_HEADER_BYTES, Long.MIN_VALUE);
            payload.putLong(RUN_HEADER_BYTES + Long.BYTES, -1L);
            payload.putLong(RUN_HEADER_BYTES + 2 * Long.BYTES, 0);
            rewritePayloadCrc(bytes);
            Files.write(path, bytes);
        });
        assertRunMutationRejected(temporary.resolve("trailing"), path ->
                Files.write(path, new byte[] { 0 },
                        StandardOpenOption.APPEND));
    }

    @Test
    void corruptedGroupCountsPayloadFailsAtFooterAndCloseCleansUp(
            @TempDir Path stateDirectory) throws Exception {
        Path countsPath = groupCountsPath(
                stateDirectory, SectionType.COMPLETION_TRIGRAMS);
        try (var store = new ProjectIndexSpillStore(stateDirectory,
                PUBLICATION_ID, () -> false)) {
            var runs = store.section(SectionType.COMPLETION_TRIGRAMS);
            runs.finish(words());

            try (var counts = runs.openGroupCounts()) {
                counts.add(17);
                counts.finish();
                byte[] bytes = Files.readAllBytes(countsPath);
                bytes[COUNTS_HEADER_BYTES] ^= 1;
                Files.write(countsPath, bytes);

                assertTrue(counts.next());
                assertThrows(ProjectIndexFormatException.class,
                        counts::next);
            }
            assertEquals(0, store.openRunChannelCountForTests());
            assertFalse(Files.exists(countsPath));
        }
    }

    @Test
    void appendedRunAfterCursorOpenFailsAtFooter(
            @TempDir Path stateDirectory) throws Exception {
        try (var store = new ProjectIndexSpillStore(stateDirectory,
                PUBLICATION_ID, () -> false)) {
            var runs = store.section(SectionType.COMPLETION_TRIGRAMS);
            runs.spill(words(1, 2));
            runs.finish(words());
            Path run = workspaceEntries(stateDirectory).get(0);

            try (var cursor = runs.openCursor()) {
                assertTrue(cursor.next());
                Files.write(run, new byte[] { 0 },
                        StandardOpenOption.APPEND);

                assertThrows(ProjectIndexFormatException.class, () -> {
                    while (cursor.next()) {
                        // Consume the run through footer validation.
                    }
                });
            }
        }
    }

    @Test
    void partialRunWriteFailurePreservesBufferAndRegistration(
            @TempDir Path stateDirectory) throws Exception {
        AtomicInteger runWrites = new AtomicInteger();
        ProjectIndexSpillStore.IoHook hook = point -> {
            if (point == ProjectIndexSpillStore.IoPoint.RUN_WRITE_CHUNK
                    && runWrites.incrementAndGet() == 2) {
                throw new IOException("injected partial run write");
            }
        };
        try (var store = new ProjectIndexSpillStore(stateDirectory,
                PUBLICATION_ID, () -> false, hook)) {
            var runs = store.section(SectionType.COMPLETION_TRIGRAMS);
            ProjectIndexTupleBuffer buffer = words(3, 1, 2);

            assertThrows(IOException.class, () -> runs.spill(buffer));

            assertEquals(3, buffer.rowCount());
            assertEquals(0, runs.registeredRunCountForTests());
            assertEquals(0, store.metrics().materializedRuns());
            assertEquals(0, store.openRunChannelCountForTests());
            assertTrue(workspaceEntries(stateDirectory).isEmpty());

            runs.spill(buffer);
            assertEquals(0, buffer.rowCount());
            assertEquals(1, runs.registeredRunCountForTests());
        }
    }

    @Test
    void workspaceCreateFailureRollsBackDirectoryAndOwnershipForRetry(
            @TempDir Path stateDirectory) throws Exception {
        AtomicBoolean failCreate = new AtomicBoolean(true);
        ProjectIndexSpillStore.IoHook hook = point -> {
            if (point
                    == ProjectIndexSpillStore.IoPoint.WORKSPACE_CREATED
                    && failCreate.getAndSet(false)) {
                throw new IOException(
                        "injected failure after workspace creation");
            }
        };
        Path workspace = workspace(stateDirectory, PUBLICATION_ID);
        try (var store = new ProjectIndexSpillStore(stateDirectory,
                PUBLICATION_ID, () -> false, hook)) {
            var runs = store.section(SectionType.COMPLETION_TRIGRAMS);
            ProjectIndexTupleBuffer buffer = words(7);

            assertThrows(IOException.class, () -> runs.spill(buffer));

            assertEquals(1, buffer.rowCount());
            assertFalse(Files.exists(
                    workspace, LinkOption.NOFOLLOW_LINKS));

            runs.spill(buffer);
            runs.finish(words());
            assertRowsEqual(List.of(row(7)), readAll(runs));
        }
        assertFalse(Files.exists(
                workspace, LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void cancellationAfterRunWriteBeginsDeletesPartialRunAndCanRetry(
            @TempDir Path stateDirectory) throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicBoolean armed = new AtomicBoolean(true);
        AtomicInteger runWriteChunks = new AtomicInteger();
        ProjectIndexSpillStore.IoHook hook = point -> {
            if (armed.get()
                    && point == ProjectIndexSpillStore.IoPoint.RUN_WRITE_CHUNK
                    && runWriteChunks.incrementAndGet() == 2) {
                cancelled.set(true);
            }
        };
        try (var store = new ProjectIndexSpillStore(stateDirectory,
                PUBLICATION_ID, cancelled::get, hook)) {
            var runs = store.section(SectionType.COMPLETION_TRIGRAMS);
            ProjectIndexTupleBuffer buffer = words(3, 1, 2);

            assertThrows(ProjectIndexStore.WriteCancelledException.class,
                    () -> runs.spill(buffer));

            assertEquals(2, runWriteChunks.get());
            assertEquals(3, buffer.rowCount());
            assertEquals(0, runs.registeredRunCountForTests());
            assertEquals(0, store.metrics().materializedRuns());
            assertEquals(0, store.metrics().physicalSpillBytes());
            assertEquals(0, store.openRunChannelCountForTests());
            assertTrue(workspaceEntries(stateDirectory).isEmpty());

            armed.set(false);
            cancelled.set(false);
            runs.spill(buffer);
            assertEquals(0, buffer.rowCount());
            runs.finish(words());
            assertRowsEqual(List.of(row(1), row(2), row(3)),
                    readAll(runs));
            assertEquals(0, store.openRunChannelCountForTests());
        }
        assertFalse(Files.exists(workspace(
                stateDirectory, PUBLICATION_ID)));
    }

    @Test
    void cancelledSpillPreservesBufferAndCanRetry(
            @TempDir Path stateDirectory) throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean(true);
        try (var store = new ProjectIndexSpillStore(stateDirectory,
                PUBLICATION_ID, cancelled::get)) {
            var runs = store.section(SectionType.COMPLETION_TRIGRAMS);
            ProjectIndexTupleBuffer buffer = words(3, 1, 2);

            assertThrows(ProjectIndexStore.WriteCancelledException.class,
                    () -> runs.spill(buffer));

            assertEquals(3, buffer.rowCount());
            assertEquals(0, runs.registeredRunCountForTests());
            assertEquals(0, store.metrics().materializedRuns());
            assertEquals(0, store.openRunChannelCountForTests());
            assertFalse(Files.exists(stateDirectory.resolve(
                    ".writer-" + PUBLICATION_ID + ".tmp")));

            cancelled.set(false);
            runs.spill(buffer);
            assertEquals(0, buffer.rowCount());
            assertEquals(1, runs.registeredRunCountForTests());
        }
    }

    @Test
    void cancelledPartialConsolidationPreservesInputsAndCanRetry(
            @TempDir Path stateDirectory) throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicBoolean armed = new AtomicBoolean();
        AtomicBoolean triggered = new AtomicBoolean();
        ProjectIndexSpillStore.IoHook hook = point -> {
            if (armed.get()
                    && point == ProjectIndexSpillStore.IoPoint
                            .CONSOLIDATION_WRITE_CHUNK
                    && triggered.compareAndSet(false, true)) {
                cancelled.set(true);
            }
        };
        try (var store = new ProjectIndexSpillStore(stateDirectory,
                PUBLICATION_ID, cancelled::get, hook)) {
            var runs = store.section(SectionType.COMPLETION_TRIGRAMS);
            for (int value = 0;
                    value < ProjectIndexSpillStore.MAX_OPEN_RUNS;
                    value++) {
                runs.spill(words(value));
            }
            Set<Path> before =
                    Set.copyOf(workspaceEntries(stateDirectory));
            ProjectIndexTupleBuffer incoming = words(99);
            armed.set(true);

            assertThrows(ProjectIndexStore.WriteCancelledException.class,
                    () -> runs.spill(incoming));

            assertEquals(1, incoming.rowCount());
            assertEquals(ProjectIndexSpillStore.MAX_OPEN_RUNS,
                    runs.registeredRunCountForTests());
            assertEquals(ProjectIndexSpillStore.MAX_OPEN_RUNS,
                    store.metrics().materializedRuns());
            assertEquals(0, store.openRunChannelCountForTests());
            assertEquals(before,
                    Set.copyOf(workspaceEntries(stateDirectory)));

            armed.set(false);
            cancelled.set(false);
            runs.spill(incoming);
            runs.finish(words());
            assertEquals(9, readAll(runs).size());
        }
    }

    @Test
    void injectedConsolidationWriteFailurePreservesInputsAndCanRetry(
            @TempDir Path stateDirectory) throws Exception {
        AtomicBoolean armed = new AtomicBoolean();
        AtomicInteger consolidationWrites = new AtomicInteger();
        ProjectIndexSpillStore.IoHook hook = point -> {
            if (armed.get()
                    && point == ProjectIndexSpillStore.IoPoint
                            .CONSOLIDATION_WRITE_CHUNK
                    && consolidationWrites.incrementAndGet() == 2) {
                throw new IOException(
                        "injected partial consolidation write");
            }
        };
        try (var store = new ProjectIndexSpillStore(stateDirectory,
                PUBLICATION_ID, () -> false, hook)) {
            var runs = store.section(SectionType.COMPLETION_TRIGRAMS);
            for (int value = 0;
                    value < ProjectIndexSpillStore.MAX_OPEN_RUNS;
                    value++) {
                runs.spill(words(value));
            }
            Set<Path> before =
                    Set.copyOf(workspaceEntries(stateDirectory));
            ProjectIndexTupleBuffer incoming = words(99);
            armed.set(true);

            assertThrows(IOException.class,
                    () -> runs.spill(incoming));

            assertEquals(1, incoming.rowCount());
            assertEquals(ProjectIndexSpillStore.MAX_OPEN_RUNS,
                    runs.registeredRunCountForTests());
            assertEquals(ProjectIndexSpillStore.MAX_OPEN_RUNS,
                    store.metrics().materializedRuns());
            assertEquals(0, store.openRunChannelCountForTests());
            assertEquals(before,
                    Set.copyOf(workspaceEntries(stateDirectory)));

            armed.set(false);
            runs.spill(incoming);
            runs.finish(words());
            assertEquals(9, readAll(runs).size());
        }
    }

    @Test
    void incomingWriteFailureAfterConsolidationKeepsRegisteredRuns(
            @TempDir Path stateDirectory) throws Exception {
        AtomicBoolean armed = new AtomicBoolean();
        AtomicBoolean consolidationStarted = new AtomicBoolean();
        AtomicBoolean failed = new AtomicBoolean();
        ProjectIndexSpillStore.IoHook hook = point -> {
            if (!armed.get()) {
                return;
            }
            if (point == ProjectIndexSpillStore.IoPoint
                    .CONSOLIDATION_WRITE_CHUNK) {
                consolidationStarted.set(true);
            } else if (point == ProjectIndexSpillStore.IoPoint
                    .RUN_WRITE_CHUNK
                    && consolidationStarted.get()
                    && failed.compareAndSet(false, true)) {
                throw new IOException(
                        "injected incoming run failure");
            }
        };
        try (var store = new ProjectIndexSpillStore(stateDirectory,
                PUBLICATION_ID, () -> false, hook)) {
            var runs = store.section(SectionType.COMPLETION_TRIGRAMS);
            for (int value = 0;
                    value < ProjectIndexSpillStore.MAX_OPEN_RUNS;
                    value++) {
                runs.spill(words(value));
            }
            Set<Path> before =
                    Set.copyOf(workspaceEntries(stateDirectory));
            ProjectIndexTupleBuffer incoming = words(99);
            armed.set(true);

            assertThrows(IOException.class,
                    () -> runs.spill(incoming));

            assertEquals(1, incoming.rowCount());
            assertEquals(ProjectIndexSpillStore.MAX_OPEN_RUNS,
                    runs.registeredRunCountForTests());
            assertEquals(0, store.openRunChannelCountForTests());
            assertEquals(before,
                    Set.copyOf(workspaceEntries(stateDirectory)));

            armed.set(false);
            runs.spill(incoming);
            runs.finish(words());
            assertEquals(9, readAll(runs).size());
            assertEquals(0, store.openRunChannelCountForTests());
        }
    }

    @Test
    void cancellationBeforeFirstMergeAdvanceClosesReaderAndCanRetry(
            @TempDir Path stateDirectory) throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        try (var store = new ProjectIndexSpillStore(stateDirectory,
                PUBLICATION_ID, cancelled::get)) {
            var runs = store.section(SectionType.COMPLETION_TRIGRAMS);
            runs.spill(words(7));
            runs.finish(words());
            cancelled.set(true);

            assertThrows(ProjectIndexStore.WriteCancelledException.class,
                    runs::openCursor);
            assertEquals(0, store.openRunChannelCountForTests());

            cancelled.set(false);
            assertRowsEqual(List.of(row(7)), readAll(runs));
            assertEquals(0, store.openRunChannelCountForTests());
        }
    }

    @Test
    void runReadFailureReleasesCursorForARepeatableRetry(
            @TempDir Path stateDirectory) throws Exception {
        try (var store = new ProjectIndexSpillStore(stateDirectory,
                PUBLICATION_ID, () -> false)) {
            var runs = store.section(SectionType.COMPLETION_TRIGRAMS);
            runs.spill(words(7));
            runs.finish(words());
            Path run = workspaceEntries(stateDirectory).get(0);
            byte[] bytes = Files.readAllBytes(run);
            bytes[40] ^= 1;
            Files.write(run, bytes);

            try (var cursor = runs.openCursor()) {
                assertTrue(cursor.next());
                assertThrows(ProjectIndexFormatException.class,
                        cursor::next);
            }
            assertEquals(0, store.openRunChannelCountForTests());
            assertThrows(ProjectIndexFormatException.class,
                    () -> readAll(runs));
            assertEquals(0, store.openRunChannelCountForTests());
        }
    }

    @Test
    void partialSecondPageReadPoisonsCursorAndFreshCursorCanRetry(
            @TempDir Path stateDirectory) throws Exception {
        AtomicBoolean armed = new AtomicBoolean();
        AtomicInteger pageReads = new AtomicInteger();
        ProjectIndexSpillStore.IoHook hook = point -> {
            if (armed.get()
                    && point
                            == ProjectIndexSpillStore.IoPoint.RUN_READ_CHUNK
                    && pageReads.incrementAndGet() == 2) {
                throw new IOException(
                        "injected failure after second page read");
            }
        };
        int rowCount =
                ProjectIndexSpillStore.PAGE_BYTES / Long.BYTES + 1;
        var buffer = new ProjectIndexTupleBuffer(1, rowCount);
        for (int row = 0; row < rowCount; row++) {
            buffer.add(row);
        }

        try (var store = new ProjectIndexSpillStore(stateDirectory,
                PUBLICATION_ID, () -> false, hook)) {
            var runs = store.section(SectionType.COMPLETION_TRIGRAMS);
            runs.spill(buffer);
            runs.finish(words());
            armed.set(true);

            var cursor = runs.openCursor();
            assertThrows(IOException.class, () -> {
                while (cursor.next()) {
                    // Consume through the second page fill.
                }
            });

            assertEquals(0, store.openRunChannelCountForTests());
            assertThrows(IllegalStateException.class, cursor::next);
            cursor.close();

            armed.set(false);
            try (var retry = runs.openCursor()) {
                for (int row = 0; row < rowCount; row++) {
                    assertTrue(retry.next());
                    assertEquals(row, retry.word(0));
                }
                assertFalse(retry.next());
            }
            assertEquals(0, store.openRunChannelCountForTests());
        }
    }

    @Test
    void closeReleasesActiveCursorAndDeletesWorkspace(
            @TempDir Path stateDirectory) throws Exception {
        var store = new ProjectIndexSpillStore(stateDirectory,
                PUBLICATION_ID, () -> false);
        var runs = store.section(SectionType.COMPLETION_TRIGRAMS);
        runs.spill(words(1, 2));
        runs.finish(words());
        Path workspace = stateDirectory.resolve(
                ".writer-" + PUBLICATION_ID + ".tmp");
        var cursor = runs.openCursor();
        assertTrue(cursor.next());

        store.close();

        assertEquals(0, store.openRunChannelCountForTests());
        assertFalse(Files.exists(workspace));
        assertThrows(IllegalStateException.class, cursor::next);
        cursor.close();
        store.close();
    }

    @Test
    void publicationIdMustBeExactlyLowercaseHex(
            @TempDir Path stateDirectory) {
        assertThrows(IllegalArgumentException.class,
                () -> new ProjectIndexSpillStore(stateDirectory,
                        "0123456789abcdef", () -> false));
        assertThrows(IllegalArgumentException.class,
                () -> new ProjectIndexSpillStore(stateDirectory,
                        "0123456789ABCDEF0123456789ABCDEF",
                        () -> false));
        assertThrows(IllegalArgumentException.class,
                () -> new ProjectIndexSpillStore(stateDirectory,
                        "../23456789abcdef0123456789abcdef",
                        () -> false));
    }

    @Test
    void publicationWorkspacesAreIsolatedAndCloseOnlyOwnFiles(
            @TempDir Path stateDirectory) throws Exception {
        var first = new ProjectIndexSpillStore(stateDirectory,
                PUBLICATION_ID, () -> false);
        var second = new ProjectIndexSpillStore(stateDirectory,
                SECOND_PUBLICATION_ID, () -> false);
        try {
            var firstRuns = first.section(
                    SectionType.COMPLETION_TRIGRAMS);
            var secondRuns = second.section(
                    SectionType.COMPLETION_TRIGRAMS);
            firstRuns.spill(words(1));
            secondRuns.spill(words(2));
            secondRuns.finish(words());
            Path firstWorkspace = workspace(
                    stateDirectory, PUBLICATION_ID);
            Path secondWorkspace = workspace(
                    stateDirectory, SECOND_PUBLICATION_ID);
            assertTrue(Files.isDirectory(firstWorkspace));
            assertTrue(Files.isDirectory(secondWorkspace));

            first.close();

            assertFalse(Files.exists(firstWorkspace));
            assertTrue(Files.isDirectory(secondWorkspace));
            assertRowsEqual(List.of(row(2)), readAll(secondRuns));
        } finally {
            first.close();
            second.close();
        }
        assertFalse(Files.exists(
                workspace(stateDirectory, SECOND_PUBLICATION_ID)));
    }

    @Test
    void posixWorkspaceAndRunsAreOwnerOnly(
            @TempDir Path temporary) throws Exception {
        assumeTrue(Files.getFileStore(temporary)
                .supportsFileAttributeView(
                        PosixFileAttributeView.class),
                "POSIX permissions are not supported by this file system");
        Path stateDirectory = temporary.resolve("state");
        try (var store = new ProjectIndexSpillStore(stateDirectory,
                PUBLICATION_ID, () -> false)) {
            var runs = store.section(SectionType.COMPLETION_TRIGRAMS);
            runs.spill(words(1));
            Path workspace = workspace(
                    stateDirectory, PUBLICATION_ID);
            Path run = workspaceEntries(stateDirectory).get(0);

            assertEquals(Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE),
                    Files.getPosixFilePermissions(stateDirectory));
            assertEquals(Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE),
                    Files.getPosixFilePermissions(workspace));
            assertEquals(Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(run));
        }
    }

    @Test
    void closeNeverTraversesAReplacedWorkspaceSymlink(
            @TempDir Path temporary) throws Exception {
        Path stateDirectory = temporary.resolve("state");
        Path external = temporary.resolve("external");
        Files.createDirectories(external);
        Path probe = temporary.resolve("symlink-probe");
        try {
            Files.createSymbolicLink(probe, external);
            Files.delete(probe);
        } catch (IOException | UnsupportedOperationException ex) {
            assumeTrue(false,
                    "Symbolic links are not supported by this file system");
        }

        Path sentinel = external.resolve("must-survive");
        Files.writeString(sentinel, "sentinel");
        var store = new ProjectIndexSpillStore(stateDirectory,
                PUBLICATION_ID, () -> false);
        var runs = store.section(SectionType.COMPLETION_TRIGRAMS);
        runs.spill(words(1));
        Path workspace = workspace(stateDirectory, PUBLICATION_ID);
        Files.move(workspace, temporary.resolve("displaced-workspace"));
        Files.createSymbolicLink(workspace, external);

        store.close();

        assertTrue(Files.isRegularFile(sentinel));
        store.close();
    }

    private static void rewritePayloadCrc(byte[] bytes) {
        var crc = new CRC32C();
        crc.update(bytes, RUN_HEADER_BYTES,
                bytes.length - RUN_HEADER_BYTES - RUN_FOOTER_BYTES);
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                .putInt(bytes.length - RUN_FOOTER_BYTES,
                        (int) crc.getValue());
    }

    private static void assertRunMutationRejected(
            Path stateDirectory, RunMutation mutation) throws Exception {
        try (var store = new ProjectIndexSpillStore(stateDirectory,
                PUBLICATION_ID, () -> false)) {
            var runs = store.section(SectionType.COMPLETION_TRIGRAMS);
            runs.spill(words(1, 2, 3));
            runs.finish(words());
            Path workspace = stateDirectory.resolve(
                    ".writer-" + PUBLICATION_ID + ".tmp");
            Path run;
            try (var entries = Files.list(workspace)) {
                List<Path> paths = entries.toList();
                assertEquals(1, paths.size());
                run = paths.get(0);
            }
            mutation.mutate(run);
            assertThrows(ProjectIndexFormatException.class,
                    () -> readAll(runs));
        }
    }

    private static List<Path> workspaceEntries(
            Path stateDirectory) throws IOException {
        try (var entries = Files.list(
                workspace(stateDirectory, PUBLICATION_ID))) {
            return entries.toList();
        }
    }

    private static Path workspace(
            Path stateDirectory, String publicationId) {
        return stateDirectory.resolve(
                ".writer-" + publicationId + ".tmp");
    }

    private static Path groupCountsPath(Path stateDirectory,
            SectionType type) {
        return workspace(stateDirectory, PUBLICATION_ID)
                .resolve("counts-" + type.id() + ".pgc");
    }

    @FunctionalInterface
    private interface RunMutation {

        void mutate(Path path) throws Exception;
    }

    private static ProjectIndexTupleBuffer words(long... values) {
        var buffer = new ProjectIndexTupleBuffer(1, values.length);
        for (long value : values) {
            buffer.add(value);
        }
        return buffer;
    }

    private static ProjectIndexTupleBuffer triples(long[]... values) {
        var buffer = new ProjectIndexTupleBuffer(3, values.length);
        for (long[] value : values) {
            buffer.add(value[0], value[1], value[2]);
        }
        return buffer;
    }

    private static long[] row(long... words) {
        return words;
    }

    private static List<long[]> readAll(
            ProjectIndexSpillStore.SectionRuns runs) throws Exception {
        var rows = new ArrayList<long[]>();
        try (var cursor = runs.openCursor()) {
            while (cursor.next()) {
                long[] row = new long[cursor.rowWidth()];
                for (int word = 0; word < row.length; word++) {
                    row[word] = cursor.word(word);
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private static void assertRowsEqual(List<long[]> expected,
            List<long[]> actual) {
        assertEquals(expected.size(), actual.size());
        for (int row = 0; row < expected.size(); row++) {
            assertEquals(expected.get(row).length,
                    actual.get(row).length);
            for (int word = 0; word < expected.get(row).length; word++) {
                assertEquals(expected.get(row)[word],
                        actual.get(row)[word],
                        "row " + row + ", word " + word);
            }
        }
    }
}
