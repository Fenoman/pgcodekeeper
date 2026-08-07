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

import java.util.Objects;
import java.util.function.BooleanSupplier;

final class ProjectIndexTupleBuffer {

    private static final BooleanSupplier NEVER_CANCELLED = () -> false;

    private final int rowWidth;
    private final int capacity;
    private final long[] words;
    private int rowCount;

    ProjectIndexTupleBuffer(int rowWidth, int capacity) {
        if (rowWidth != 1 && rowWidth != 3) {
            throw new IllegalArgumentException("Tuple row width must be 1 or 3");
        }
        if (capacity < 0) {
            throw new IllegalArgumentException("Tuple capacity must not be negative");
        }
        this.rowWidth = rowWidth;
        this.capacity = capacity;
        words = new long[Math.multiplyExact(rowWidth, capacity)];
    }

    void add(long value) {
        requireWidth(1);
        requireCapacity();
        words[rowCount++] = value;
    }

    void add(long first, long second, long third) {
        requireWidth(3);
        requireCapacity();
        int offset = rowCount * rowWidth;
        words[offset] = first;
        words[offset + 1] = second;
        words[offset + 2] = third;
        rowCount++;
    }

    void sort() {
        try {
            sort(NEVER_CANCELLED);
        } catch (ProjectIndexStore.WriteCancelledException ex) {
            throw new AssertionError("Non-cancellable tuple sort was cancelled", ex);
        }
    }

    void sort(BooleanSupplier cancelled)
            throws ProjectIndexStore.WriteCancelledException {
        sortRows(words, rowWidth, rowCount, cancelled);
    }

    void sortAndDeduplicate() {
        try {
            sortAndDeduplicate(NEVER_CANCELLED);
        } catch (ProjectIndexStore.WriteCancelledException ex) {
            throw new AssertionError("Non-cancellable tuple deduplication was cancelled", ex);
        }
    }

    void sortAndDeduplicate(BooleanSupplier cancelled)
            throws ProjectIndexStore.WriteCancelledException {
        sort(cancelled);
        rowCount = deduplicateRows(words, rowWidth, rowCount, cancelled);
    }

    void clear() {
        rowCount = 0;
    }

    int rowWidth() {
        return rowWidth;
    }

    int capacity() {
        return capacity;
    }

    int rowCount() {
        return rowCount;
    }

    /**
     * Returns the primitive payload size, excluding the JVM array header.
     */
    long allocatedBytes() {
        return Math.multiplyExact((long) words.length, Long.BYTES);
    }

    long packedBytes() {
        return Math.multiplyExact(
                Math.multiplyExact((long) rowCount, rowWidth), Long.BYTES);
    }

    long word(int row, int word) {
        if (row < 0 || row >= rowCount) {
            throw new IndexOutOfBoundsException("Tuple row is outside the packed range");
        }
        if (word < 0 || word >= rowWidth) {
            throw new IndexOutOfBoundsException("Tuple word is outside the row");
        }
        return words[row * rowWidth + word];
    }

    private void requireWidth(int expected) {
        if (rowWidth != expected) {
            throw new IllegalStateException(
                    "Tuple append does not match the configured row width");
        }
    }

    private void requireCapacity() {
        if (rowCount >= capacity) {
            throw new IllegalStateException("Tuple buffer capacity exceeded");
        }
    }

    static void sortRows(long[] values, int width, int rows) {
        try {
            sortRows(values, width, rows, NEVER_CANCELLED);
        } catch (ProjectIndexStore.WriteCancelledException ex) {
            throw new AssertionError("Non-cancellable tuple sort was cancelled", ex);
        }
    }

    static void sortRows(long[] values, int width, int rows,
            BooleanSupplier cancelled)
            throws ProjectIndexStore.WriteCancelledException {
        requireShape(values, width, rows);
        var polling = new CancellationPolling(cancelled);
        polling.checkNow();
        for (int root = rows / 2 - 1; root >= 0; root--) {
            siftDown(values, width, root, rows, polling);
        }
        for (int end = rows - 1; end > 0; end--) {
            swap(values, width, 0, end, polling);
            siftDown(values, width, 0, end, polling);
        }
        polling.checkNow();
    }

    static int deduplicateRows(long[] values, int width, int rows) {
        try {
            return deduplicateRows(values, width, rows, NEVER_CANCELLED);
        } catch (ProjectIndexStore.WriteCancelledException ex) {
            throw new AssertionError(
                    "Non-cancellable tuple deduplication was cancelled", ex);
        }
    }

    static int deduplicateRows(long[] values, int width, int rows,
            BooleanSupplier cancelled)
            throws ProjectIndexStore.WriteCancelledException {
        requireShape(values, width, rows);
        var polling = new CancellationPolling(cancelled);
        polling.checkNow();
        int unique = 0;
        for (int row = 0; row < rows; row++) {
            if (unique == 0
                    || compare(values, width, unique - 1, row, polling) != 0) {
                if (unique != row) {
                    int source = row * width;
                    int target = unique * width;
                    for (int word = 0; word < width; word++) {
                        values[target + word] = values[source + word];
                    }
                }
                unique++;
            }
        }
        polling.checkNow();
        return unique;
    }

    private static void requireShape(long[] values, int width, int rows) {
        if (values == null) {
            throw new NullPointerException("values");
        }
        if (width != 1 && width != 3) {
            throw new IllegalArgumentException("Tuple row width must be 1 or 3");
        }
        if (rows < 0 || Math.multiplyExact(rows, width) > values.length) {
            throw new IllegalArgumentException("Tuple rows exceed primitive storage");
        }
    }

    private static void siftDown(long[] values, int width, int root, int size,
            CancellationPolling polling)
            throws ProjectIndexStore.WriteCancelledException {
        while (true) {
            int child = root * 2 + 1;
            if (child >= size) {
                return;
            }
            if (child + 1 < size
                    && compare(values, width, child, child + 1, polling) < 0) {
                child++;
            }
            if (compare(values, width, root, child, polling) >= 0) {
                return;
            }
            swap(values, width, root, child, polling);
            root = child;
        }
    }

    private static int compare(long[] values, int width, int left, int right,
            CancellationPolling polling)
            throws ProjectIndexStore.WriteCancelledException {
        polling.operation();
        int leftOffset = left * width;
        int rightOffset = right * width;
        for (int i = 0; i < width; i++) {
            int result = Long.compareUnsigned(
                    values[leftOffset + i], values[rightOffset + i]);
            if (result != 0) {
                return result;
            }
        }
        return 0;
    }

    private static void swap(long[] values, int width, int left, int right,
            CancellationPolling polling)
            throws ProjectIndexStore.WriteCancelledException {
        polling.operation();
        int leftOffset = left * width;
        int rightOffset = right * width;
        for (int i = 0; i < width; i++) {
            long value = values[leftOffset + i];
            values[leftOffset + i] = values[rightOffset + i];
            values[rightOffset + i] = value;
        }
    }

    private static final class CancellationPolling {
        private static final int POLL_MASK = 4096 - 1;

        private final BooleanSupplier cancelled;
        private int operations;

        private CancellationPolling(BooleanSupplier cancelled) {
            this.cancelled = Objects.requireNonNull(cancelled, "cancelled");
        }

        private void operation()
                throws ProjectIndexStore.WriteCancelledException {
            if ((++operations & POLL_MASK) == 0) {
                checkNow();
            }
        }

        private void checkNow()
                throws ProjectIndexStore.WriteCancelledException {
            if (cancelled.getAsBoolean()) {
                throw new ProjectIndexStore.WriteCancelledException();
            }
        }
    }
}
