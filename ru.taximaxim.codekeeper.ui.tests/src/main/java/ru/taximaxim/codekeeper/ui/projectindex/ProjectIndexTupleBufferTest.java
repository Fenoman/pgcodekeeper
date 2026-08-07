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

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class ProjectIndexTupleBufferTest {

    @Test
    void cancellableSortStopsAndLeavesReusableOwnedBuffer() throws Exception {
        var buffer = new ProjectIndexTupleBuffer(1, 16_384);
        for (int i = 16_384; i > 0; i--) {
            buffer.add(i);
        }
        var polls = new AtomicInteger();

        assertThrows(ProjectIndexStore.WriteCancelledException.class,
                () -> buffer.sort(() -> polls.incrementAndGet() >= 2));
        assertEquals(16_384, buffer.rowCount());
        assertFalse(isUnsignedSorted(buffer),
                "Cancellation must stop before the sort completes");

        buffer.sort();
        for (int row = 0; row < buffer.rowCount(); row++) {
            assertEquals(row + 1L, buffer.word(row, 0));
        }
    }

    @Test
    void cancellableDedupScanStopsAndSameArrayCanRetry() throws Exception {
        int rows = 16_384;
        int uniqueRows = rows / 2;
        long[] values = new long[rows];
        for (int row = 0; row < rows; row++) {
            values[row] = row / 2;
        }
        var polls = new AtomicInteger();

        assertThrows(ProjectIndexStore.WriteCancelledException.class,
                () -> ProjectIndexTupleBuffer.deduplicateRows(
                        values, 1, rows,
                        () -> polls.incrementAndGet() >= 3));
        // Poll 1 is the entry check. Polls 2 and 3 happen after 4,096
        // and 8,192 comparisons, so the cancellation cannot be the
        // final exit check and proves polling inside the dedup scan.
        assertEquals(3, polls.get());
        assertEquals(1, values[1],
                "Deduplication must compact rows before cancellation");

        ProjectIndexTupleBuffer.sortRows(values, 1, rows);
        int deduplicated = ProjectIndexTupleBuffer.deduplicateRows(
                values, 1, rows);
        assertEquals(uniqueRows, deduplicated);
        for (int row = 0; row < deduplicated; row++) {
            assertEquals(row, values[row]);
        }
    }

    @Test
    void sortsSingleWordsAsUnsignedValues() {
        var buffer = new ProjectIndexTupleBuffer(1, 4);
        buffer.add(-1L);
        buffer.add(0L);
        buffer.add(Long.MIN_VALUE);
        buffer.add(1L);

        buffer.sort();

        assertEquals(0L, buffer.word(0, 0));
        assertEquals(1L, buffer.word(1, 0));
        assertEquals(Long.MIN_VALUE, buffer.word(2, 0));
        assertEquals(-1L, buffer.word(3, 0));
    }

    @Test
    void sortsThreeWordRowsAsUnsignedLexicographicValues() {
        var buffer = new ProjectIndexTupleBuffer(3, 6);
        buffer.add(-1L, 0L, 0L);
        buffer.add(0L, -1L, 0L);
        buffer.add(0L, Long.MIN_VALUE, -1L);
        buffer.add(Long.MIN_VALUE, 0L, 0L);
        buffer.add(0L, Long.MIN_VALUE, 0L);
        buffer.add(0L, 0L, -1L);

        buffer.sort();

        assertRow(buffer, 0, 0L, 0L, -1L);
        assertRow(buffer, 1, 0L, Long.MIN_VALUE, 0L);
        assertRow(buffer, 2, 0L, Long.MIN_VALUE, -1L);
        assertRow(buffer, 3, 0L, -1L, 0L);
        assertRow(buffer, 4, Long.MIN_VALUE, 0L, 0L);
        assertRow(buffer, 5, -1L, 0L, 0L);
    }

    @Test
    void deduplicatesOnlyCompleteEqualRows() {
        var buffer = new ProjectIndexTupleBuffer(3, 5);
        buffer.add(4L, 5L, 7L);
        buffer.add(4L, 5L, 6L);
        buffer.add(4L, 5L, 7L);
        buffer.add(4L, 5L, 8L);
        buffer.add(4L, 5L, 6L);

        buffer.sortAndDeduplicate();

        assertEquals(3, buffer.rowCount());
        assertRow(buffer, 0, 4L, 5L, 6L);
        assertRow(buffer, 1, 4L, 5L, 7L);
        assertRow(buffer, 2, 4L, 5L, 8L);
    }

    @Test
    void deduplicatesEmptySingleAndWidthOneBuffers() {
        var empty = new ProjectIndexTupleBuffer(1, 0);
        empty.sortAndDeduplicate();
        assertEquals(0, empty.rowCount());

        var single = new ProjectIndexTupleBuffer(3, 1);
        single.add(-1L, Long.MIN_VALUE, 0L);
        single.sortAndDeduplicate();
        assertEquals(1, single.rowCount());
        assertRow(single, 0, -1L, Long.MIN_VALUE, 0L);

        var words = new ProjectIndexTupleBuffer(1, 6);
        words.add(-1L);
        words.add(0L);
        words.add(Long.MIN_VALUE);
        words.add(-1L);
        words.add(0L);
        words.add(Long.MIN_VALUE);
        words.sortAndDeduplicate();
        assertEquals(3, words.rowCount());
        assertEquals(0L, words.word(0, 0));
        assertEquals(Long.MIN_VALUE, words.word(1, 0));
        assertEquals(-1L, words.word(2, 0));
    }

    @Test
    void randomizedSortMatchesUnsignedReferenceAtBoundedSize() {
        var random = new Random(0x50_47_43_4bL);
        for (int width : new int[] {1, 3}) {
            int rows = 2_049;
            var buffer = new ProjectIndexTupleBuffer(width, rows);
            long[][] expected = new long[rows][width];
            for (int row = 0; row < rows; row++) {
                for (int word = 0; word < width; word++) {
                    expected[row][word] = random.nextLong();
                }
                if (width == 1) {
                    buffer.add(expected[row][0]);
                } else {
                    buffer.add(expected[row][0], expected[row][1],
                            expected[row][2]);
                }
            }
            Arrays.sort(expected, ProjectIndexTupleBufferTest::compareRows);

            buffer.sort();

            for (int row = 0; row < rows; row++) {
                for (int word = 0; word < width; word++) {
                    assertEquals(expected[row][word],
                            buffer.word(row, word));
                }
            }
        }
    }

    @Test
    void rejectsWrongWidthAndOverflowBeforeChangingContent() {
        var single = new ProjectIndexTupleBuffer(1, 1);
        single.add(9L);
        assertThrows(IllegalStateException.class, () -> single.add(1L, 2L, 3L));
        assertThrows(IllegalStateException.class, () -> single.add(10L));
        assertEquals(1, single.rowCount());
        assertEquals(9L, single.word(0, 0));

        var triple = new ProjectIndexTupleBuffer(3, 1);
        triple.add(1L, 2L, 3L);
        assertThrows(IllegalStateException.class, () -> triple.add(4L));
        assertThrows(IllegalStateException.class, () -> triple.add(4L, 5L, 6L));
        assertEquals(1, triple.rowCount());
        assertRow(triple, 0, 1L, 2L, 3L);
    }

    @Test
    void reportsExactPrimitiveBytesAndClearReusesCapacity() {
        var buffer = new ProjectIndexTupleBuffer(3, 2);
        assertEquals(3, buffer.rowWidth());
        assertEquals(2, buffer.capacity());
        assertEquals(48L, buffer.allocatedBytes());
        assertEquals(0L, buffer.packedBytes());

        buffer.add(1L, 2L, 3L);
        assertEquals(24L, buffer.packedBytes());
        buffer.clear();

        assertEquals(0, buffer.rowCount());
        assertEquals(48L, buffer.allocatedBytes());
        assertEquals(0L, buffer.packedBytes());
        buffer.add(4L, 5L, 6L);
        assertRow(buffer, 0, 4L, 5L, 6L);
    }

    @Test
    void rejectsInvalidWidthsCapacitiesAndAllocationOverflow() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProjectIndexTupleBuffer(0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ProjectIndexTupleBuffer(2, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ProjectIndexTupleBuffer(4, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ProjectIndexTupleBuffer(1, -1));
        assertThrows(ArithmeticException.class,
                () -> new ProjectIndexTupleBuffer(3, Integer.MAX_VALUE));
    }

    private static void assertRow(ProjectIndexTupleBuffer buffer, int row,
            long first, long second, long third) {
        assertEquals(first, buffer.word(row, 0));
        assertEquals(second, buffer.word(row, 1));
        assertEquals(third, buffer.word(row, 2));
    }

    private static int compareRows(long[] left, long[] right) {
        for (int word = 0; word < left.length; word++) {
            int result = Long.compareUnsigned(left[word], right[word]);
            if (result != 0) {
                return result;
            }
        }
        return 0;
    }

    private static int compareRows(ProjectIndexTupleBuffer buffer,
            int left, int right) {
        for (int word = 0; word < buffer.rowWidth(); word++) {
            int result = Long.compareUnsigned(buffer.word(left, word),
                    buffer.word(right, word));
            if (result != 0) {
                return result;
            }
        }
        return 0;
    }

    private static boolean isUnsignedSorted(
            ProjectIndexTupleBuffer buffer) {
        for (int row = 1; row < buffer.rowCount(); row++) {
            if (compareRows(buffer, row - 1, row) > 0) {
                return false;
            }
        }
        return true;
    }
}
