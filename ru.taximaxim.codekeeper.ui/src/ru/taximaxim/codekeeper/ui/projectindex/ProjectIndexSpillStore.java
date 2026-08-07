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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.zip.CRC32C;

/**
 * Bounded temporary storage inside trusted private Eclipse workspace state.
 * Cleanup never follows stable symbolic links. Concurrent adversarial
 * same-user replacement of state paths is outside the Eclipse workspace-lock
 * contract.
 */
final class ProjectIndexSpillStore implements AutoCloseable {

    static final int MAX_OPEN_RUNS = 8;
    static final int PAGE_BYTES = 64 << 10;

    private static final int CONSOLIDATION_INPUT_RUNS = MAX_OPEN_RUNS - 1;
    private static final byte[] RUN_MAGIC =
            "PGCKRUN2".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] RUN_END_MAGIC =
            "PGCKREND".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] COUNTS_MAGIC =
            "PGCKCNT2".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] COUNTS_END_MAGIC =
            "PGCKCEND".getBytes(StandardCharsets.US_ASCII);
    private static final int RUN_VERSION = 1;
    private static final int RUN_HEADER_BYTES = 40;
    private static final int RUN_FOOTER_BYTES =
            Integer.BYTES + RUN_END_MAGIC.length;
    private static final int COUNTS_VERSION = 1;
    private static final int COUNTS_HEADER_BYTES = 40;
    private static final int COUNTS_FOOTER_BYTES =
            Integer.BYTES + COUNTS_END_MAGIC.length;
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
            Set.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> RUN_PERMISSIONS =
            Set.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE);
    private static final Object WORKSPACE_OWNERSHIP_MONITOR = new Object();
    private static final Map<Path, WorkspaceOwnership> ACTIVE_WORKSPACES =
            new HashMap<>();

    enum DuplicatePolicy {
        DEDUP_EXACT,
        KEEP_REJECT_EXACT
    }

    enum IoPoint {
        WORKSPACE_CREATED,
        RUN_WRITE_CHUNK,
        CONSOLIDATION_WRITE_CHUNK,
        RUN_READ_CHUNK
    }

    @FunctionalInterface
    interface IoHook {
        IoHook NONE = point -> {
        };

        void at(IoPoint point) throws IOException;
    }

    @FunctionalInterface
    interface WorkspaceAction {

        void run(Path workspace) throws IOException;
    }

    /**
     * {@code materializedRuns} counts completed, validated tuple runs;
     * group-count sidecars are not runs. {@code physicalSpillBytes} is the
     * cumulative size of every completed, validated tuple run and group-count
     * sidecar, including intermediate consolidation runs.
     * {@code peakRegisteredRunDescriptors} counts registered merge inputs;
     * short-lived pending transaction descriptors are intentionally excluded.
     */
    record Metrics(long materializedRuns, long physicalSpillBytes,
            long peakTrackedBufferBytes, long peakTrackedResidentBytes,
            int peakOpenRuns, int peakRegisteredRunDescriptors) {
    }

    interface Cursor extends AutoCloseable {

        boolean next() throws IOException;

        int rowWidth();

        long word(int index);

        @Override
        void close();
    }

    private final Path stateDirectory;
    private final String publicationId;
    private final BooleanSupplier cancelled;
    private final IoHook ioHook;
    private final EnumMap<SectionType, SectionRuns> sections =
            new EnumMap<>(SectionType.class);
    private final Path workspace;
    private long materializedRuns;
    private long physicalSpillBytes;
    private long peakTrackedBufferBytes;
    private long peakTrackedResidentBytes;
    private int peakOpenRuns;
    private int peakRegisteredRunDescriptors;
    private long nextRunId;
    private int openRuns;
    private WorkspaceOwnership workspaceOwnership;
    private boolean posixPermissions;
    private boolean workspaceCreated;
    private boolean closed;

    ProjectIndexSpillStore(Path stateDirectory, String publicationId,
            BooleanSupplier cancelled) {
        this(stateDirectory, publicationId, cancelled, IoHook.NONE);
    }

    ProjectIndexSpillStore(Path stateDirectory, String publicationId,
            BooleanSupplier cancelled, IoHook ioHook) {
        this.stateDirectory = Objects.requireNonNull(
                stateDirectory, "stateDirectory").toAbsolutePath().normalize();
        this.publicationId = requirePublicationId(publicationId);
        this.cancelled = Objects.requireNonNull(cancelled, "cancelled");
        this.ioHook = Objects.requireNonNull(ioHook, "ioHook");
        workspace = this.stateDirectory.resolve(
                ".writer-" + this.publicationId + ".tmp").normalize();
        if (!workspace.getParent().equals(this.stateDirectory)) {
            throw new IllegalArgumentException(
                    "Project index spill workspace escapes the state directory");
        }
    }

    SectionRuns section(SectionType type) {
        requireOpen();
        SectionType checkedType = Objects.requireNonNull(type, "type");
        int rowWidth;
        DuplicatePolicy duplicatePolicy;
        switch (checkedType) {
        case BY_MATCH_KEY:
            rowWidth = 3;
            duplicatePolicy = DuplicatePolicy.KEEP_REJECT_EXACT;
            break;
        case COMPLETION_TRIGRAMS:
            rowWidth = 1;
            duplicatePolicy = DuplicatePolicy.DEDUP_EXACT;
            break;
        case REVERSE_DEPENDENCIES:
            rowWidth = 3;
            duplicatePolicy = DuplicatePolicy.DEDUP_EXACT;
            break;
        default:
            throw new IllegalArgumentException(
                    "Unsupported project index spill section: "
                            + checkedType);
        }
        var created = new SectionRuns(
                checkedType, rowWidth, duplicatePolicy);
        if (sections.putIfAbsent(checkedType, created) != null) {
            throw new IllegalStateException(
                    "Project index spill section is already registered");
        }
        return created;
    }

    Metrics metrics() {
        return new Metrics(materializedRuns, physicalSpillBytes,
                peakTrackedBufferBytes, peakTrackedResidentBytes,
                peakOpenRuns, peakRegisteredRunDescriptors);
    }

    int openRunChannelCountForTests() {
        return openRuns;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            sections.values().forEach(SectionRuns::close);
            sections.clear();
            deleteWorkspaceQuietly();
        } finally {
            unregisterWorkspace(workspaceOwnership);
            workspaceOwnership = null;
        }
    }

    static boolean cleanupWorkspaceIfInactive(Path path,
            WorkspaceAction cleanup) throws IOException {
        Path normalized = normalizeWorkspace(path);
        Objects.requireNonNull(cleanup, "cleanup");
        synchronized (WORKSPACE_OWNERSHIP_MONITOR) {
            if (ACTIVE_WORKSPACES.containsKey(normalized)) {
                return false;
            }
            cleanup.run(normalized);
            return true;
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException(
                    "Project index spill store is already closed");
        }
    }

    private static String requirePublicationId(String value) {
        Objects.requireNonNull(value, "publicationId");
        if (value.length() != 32) {
            throw new IllegalArgumentException(
                    "Project index publication id must contain 32 lowercase hex characters");
        }
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (!(character >= '0' && character <= '9'
                    || character >= 'a' && character <= 'f')) {
                throw new IllegalArgumentException(
                        "Project index publication id must contain 32 lowercase hex characters");
            }
        }
        return value;
    }

    private static Path normalizeWorkspace(Path path) {
        return Objects.requireNonNull(path, "workspace")
                .toAbsolutePath().normalize();
    }

    private static WorkspaceOwnership registerWorkspace(Path path,
            WorkspaceAction create) throws IOException {
        Path normalized = normalizeWorkspace(path);
        Objects.requireNonNull(create, "create");
        synchronized (WORKSPACE_OWNERSHIP_MONITOR) {
            var ownership = new WorkspaceOwnership(normalized);
            if (ACTIVE_WORKSPACES.putIfAbsent(
                    normalized, ownership) != null) {
                throw new IOException(
                        "Project index spill workspace is already owned");
            }
            boolean complete = false;
            try {
                create.run(normalized);
                complete = true;
                return ownership;
            } finally {
                if (!complete) {
                    ACTIVE_WORKSPACES.remove(normalized, ownership);
                }
            }
        }
    }

    private static void unregisterWorkspace(
            WorkspaceOwnership ownership) {
        if (ownership == null) {
            return;
        }
        synchronized (WORKSPACE_OWNERSHIP_MONITOR) {
            ACTIVE_WORKSPACES.remove(
                    ownership.workspace(), ownership);
        }
    }

    final class SectionRuns implements AutoCloseable {

        private final SectionType type;
        private final int rowWidth;
        private final DuplicatePolicy duplicatePolicy;
        private final RunDescriptor[] runs =
                new RunDescriptor[MAX_OPEN_RUNS];
        private int runCount;
        private long nextOrdinal;
        private ProjectIndexTupleBuffer tail;
        private Cursor activeCursor;
        private GroupCounts groupCounts;
        private boolean finished;
        private boolean cursorOpen;
        private boolean sectionClosed;

        private SectionRuns(SectionType type, int rowWidth,
                DuplicatePolicy duplicatePolicy) {
            this.type = type;
            this.rowWidth = rowWidth;
            this.duplicatePolicy = duplicatePolicy;
        }

        void spill(ProjectIndexTupleBuffer buffer) throws IOException {
            requireMutable();
            ProjectIndexTupleBuffer checked = requireCompatible(buffer);
            if (checked.rowCount() == 0) {
                return;
            }
            sort(checked);
            PendingConsolidation pending = null;
            if (runCount == MAX_OPEN_RUNS) {
                pending = consolidateRuns(checked);
            }
            boolean committed = false;
            try {
                observeBuffers(checked, 1, runCount + 1);
                RunDescriptor descriptor = writeRun(checked, nextOrdinal);
                if (pending == null) {
                    runs[runCount++] = descriptor;
                } else {
                    commitConsolidation(pending, descriptor);
                }
                nextOrdinal++;
                peakRegisteredRunDescriptors = Math.max(
                        peakRegisteredRunDescriptors, runCount);
                checked.clear();
                committed = true;
            } finally {
                if (pending != null && !committed) {
                    deleteRunQuietly(pending.merged().path());
                }
            }
        }

        /**
         * Finishes this section without materializing its final buffer.
         * On success, ownership of the buffer is transferred to the section
         * and retained until the section or store is closed.
         */
        void finish(ProjectIndexTupleBuffer buffer)
                throws ProjectIndexStore.WriteCancelledException {
            requireMutable();
            ProjectIndexTupleBuffer checked = requireCompatible(buffer);
            sort(checked);
            observeBuffers(checked, 0, runCount);
            tail = checked;
            finished = true;
        }

        Cursor openCursor() throws IOException {
            requireAvailable();
            if (!finished) {
                throw new IllegalStateException(
                        "Project index spill section is not finished");
            }
            if (cursorOpen) {
                throw new IllegalStateException(
                        "Project index spill cursor is already open");
            }
            cursorOpen = true;
            try {
                activeCursor = new MergeCursor();
                return activeCursor;
            } catch (IOException | RuntimeException ex) {
                cursorOpen = false;
                throw ex;
            }
        }

        GroupCounts openGroupCounts() throws IOException {
            requireAvailable();
            if (!finished) {
                throw new IllegalStateException(
                        "Project index spill section is not finished");
            }
            if (cursorOpen) {
                throw new IllegalStateException(
                        "Project index spill cursor is already open");
            }
            if (groupCounts != null) {
                throw new IllegalStateException(
                        "Project index spill group counts are already open");
            }
            consolidateForSidecar();
            groupCounts = new GroupCounts(this,
                    type == SectionType.BY_MATCH_KEY ? 2 : 1);
            return groupCounts;
        }

        int registeredRunCountForTests() {
            return runCount;
        }

        @Override
        public void close() {
            if (sectionClosed) {
                return;
            }
            if (activeCursor != null) {
                activeCursor.close();
            }
            if (groupCounts != null) {
                groupCounts.close();
            }
            sectionClosed = true;
            cursorOpen = false;
            tail = null;
            for (int run = 0; run < runCount; run++) {
                deleteRunQuietly(runs[run].path());
            }
            Arrays.fill(runs, null);
            runCount = 0;
        }

        private void consolidateForSidecar() throws IOException {
            if (runCount < MAX_OPEN_RUNS) {
                return;
            }
            RunDescriptor[] previous =
                    new RunDescriptor[CONSOLIDATION_INPUT_RUNS];
            System.arraycopy(runs, 0, previous, 0,
                    CONSOLIDATION_INPUT_RUNS);
            RunDescriptor merged;
            try (var cursor = new ConsolidationCursor(previous,
                    CONSOLIDATION_INPUT_RUNS)) {
                merged = writeRun(cursor, previous[0].ordinal(), tail);
            }
            RunDescriptor retained = runs[MAX_OPEN_RUNS - 1];
            runs[0] = merged;
            runs[1] = retained;
            Arrays.fill(runs, 2, MAX_OPEN_RUNS, null);
            runCount = 2;
            for (RunDescriptor descriptor : previous) {
                deleteRunQuietly(descriptor.path());
            }
        }

        private ProjectIndexTupleBuffer requireCompatible(
                ProjectIndexTupleBuffer buffer) {
            ProjectIndexTupleBuffer checked =
                    Objects.requireNonNull(buffer, "buffer");
            if (checked.rowWidth() != rowWidth) {
                throw new IllegalArgumentException(
                        "Tuple buffer width does not match spill section");
            }
            return checked;
        }

        private void requireMutable() {
            requireAvailable();
            if (finished) {
                throw new IllegalStateException(
                        "Project index spill section is already finished");
            }
        }

        private void requireAvailable() {
            ProjectIndexSpillStore.this.requireOpen();
            if (sectionClosed) {
                throw new IllegalStateException(
                        "Project index spill section is already closed");
            }
        }

        private void sort(ProjectIndexTupleBuffer buffer)
                throws ProjectIndexStore.WriteCancelledException {
            if (duplicatePolicy == DuplicatePolicy.DEDUP_EXACT) {
                buffer.sortAndDeduplicate(cancelled);
            } else {
                buffer.sort(cancelled);
            }
        }

        private RunDescriptor writeRun(ProjectIndexTupleBuffer buffer,
                long ordinal) throws IOException {
            requireNotCancelled();
            ensureWorkspace();
            Path path = workspace.resolve("run-"
                    + Long.toUnsignedString(nextRunId++, 16) + ".pgr");
            long payloadBytes = Math.multiplyExact(
                    Math.multiplyExact((long) buffer.rowCount(), rowWidth),
                    Long.BYTES);
            long expectedBytes = Math.addExact(
                    Math.addExact(RUN_HEADER_BYTES, payloadBytes),
                    RUN_FOOTER_BYTES);
            boolean complete = false;
            try {
                ByteBuffer header = ByteBuffer.allocate(RUN_HEADER_BYTES)
                        .order(ByteOrder.BIG_ENDIAN);
                header.put(RUN_MAGIC);
                header.putInt(RUN_VERSION);
                header.putInt(type.id());
                header.putInt(rowWidth);
                header.putInt(duplicatePolicy.ordinal());
                header.putLong(buffer.rowCount());
                header.putLong(payloadBytes);
                header.flip();
                var crc = new CRC32C();
                ByteBuffer page = ByteBuffer.allocate(PAGE_BYTES)
                        .order(ByteOrder.BIG_ENDIAN);
                try (var tracked = openRunFile(path, false)) {
                    FileChannel channel = tracked.channel();
                    writeFully(channel, header,
                            IoPoint.RUN_WRITE_CHUNK);
                    for (int row = 0; row < buffer.rowCount(); row++) {
                        for (int word = 0; word < rowWidth; word++) {
                            if (page.remaining() < Long.BYTES) {
                                flushPage(channel, page, crc,
                                        IoPoint.RUN_WRITE_CHUNK);
                            }
                            page.putLong(buffer.word(row, word));
                        }
                    }
                    flushPage(channel, page, crc,
                            IoPoint.RUN_WRITE_CHUNK);
                    ByteBuffer footer = ByteBuffer
                            .allocate(RUN_FOOTER_BYTES)
                            .order(ByteOrder.BIG_ENDIAN);
                    footer.putInt((int) crc.getValue());
                    footer.put(RUN_END_MAGIC);
                    footer.flip();
                    writeFully(channel, footer,
                            IoPoint.RUN_WRITE_CHUNK);
                }
                if (Files.size(path) != expectedBytes) {
                    throw new ProjectIndexFormatException(
                            "Project index spill run has an invalid length");
                }
                RunDescriptor descriptor = new RunDescriptor(path, ordinal,
                        buffer.rowCount(), expectedBytes);
                validateRun(descriptor);
                long nextPhysicalSpillBytes = Math.addExact(
                        physicalSpillBytes, expectedBytes);
                materializedRuns = Math.addExact(materializedRuns, 1);
                physicalSpillBytes = nextPhysicalSpillBytes;
                complete = true;
                return descriptor;
            } finally {
                if (!complete) {
                    deleteRunQuietly(path);
                }
            }
        }

        private PendingConsolidation consolidateRuns(
                ProjectIndexTupleBuffer retainedBuffer)
                throws IOException {
            RunDescriptor[] previous =
                    new RunDescriptor[CONSOLIDATION_INPUT_RUNS];
            System.arraycopy(runs, 0, previous, 0,
                    CONSOLIDATION_INPUT_RUNS);
            RunDescriptor merged;
            try (var cursor =
                    new ConsolidationCursor(previous,
                            CONSOLIDATION_INPUT_RUNS)) {
                merged = writeRun(cursor, previous[0].ordinal(),
                        retainedBuffer);
            }
            return new PendingConsolidation(merged, previous);
        }

        private void commitConsolidation(
                PendingConsolidation pending,
                RunDescriptor incoming) {
            RunDescriptor retained = runs[MAX_OPEN_RUNS - 1];
            runs[0] = pending.merged();
            runs[1] = retained;
            runs[2] = incoming;
            Arrays.fill(runs, 3, MAX_OPEN_RUNS, null);
            runCount = 3;
            for (RunDescriptor previous : pending.previous()) {
                deleteRunQuietly(previous.path());
            }
        }

        private RunDescriptor writeRun(SourceCursor source,
                long ordinal,
                ProjectIndexTupleBuffer retainedBuffer)
                throws IOException {
            requireNotCancelled();
            ensureWorkspace();
            Path path = workspace.resolve("run-"
                    + Long.toUnsignedString(nextRunId++, 16) + ".pgr");
            boolean complete = false;
            try {
                long rows = 0;
                var crc = new CRC32C();
                ByteBuffer page = ByteBuffer.allocate(PAGE_BYTES)
                        .order(ByteOrder.BIG_ENDIAN);
                long expectedBytes;
                try (var tracked = openRunFile(path, true)) {
                    FileChannel channel = tracked.channel();
                    observeBuffers(retainedBuffer, openRuns, runCount);
                    writeFully(channel,
                            ByteBuffer.allocate(RUN_HEADER_BYTES),
                            IoPoint.CONSOLIDATION_WRITE_CHUNK);
                    while (source.advance()) {
                        requireNotCancelled();
                        for (int word = 0; word < rowWidth; word++) {
                            if (page.remaining() < Long.BYTES) {
                                flushPage(channel, page, crc,
                                        IoPoint.CONSOLIDATION_WRITE_CHUNK);
                            }
                            page.putLong(source.word(word));
                        }
                        rows = Math.addExact(rows, 1);
                    }
                    flushPage(channel, page, crc,
                            IoPoint.CONSOLIDATION_WRITE_CHUNK);
                    ByteBuffer footer = ByteBuffer
                            .allocate(RUN_FOOTER_BYTES)
                            .order(ByteOrder.BIG_ENDIAN);
                    footer.putInt((int) crc.getValue());
                    footer.put(RUN_END_MAGIC);
                    footer.flip();
                    writeFully(channel, footer,
                            IoPoint.CONSOLIDATION_WRITE_CHUNK);

                    long payloadBytes = Math.multiplyExact(
                            Math.multiplyExact(rows, rowWidth),
                            Long.BYTES);
                    expectedBytes = Math.addExact(
                            Math.addExact(RUN_HEADER_BYTES,
                                    payloadBytes),
                            RUN_FOOTER_BYTES);
                    if (channel.size() != expectedBytes) {
                        throw new ProjectIndexFormatException(
                                "Project index spill run has an invalid length");
                    }
                    ByteBuffer header =
                            ByteBuffer.allocate(RUN_HEADER_BYTES)
                                    .order(ByteOrder.BIG_ENDIAN);
                    header.put(RUN_MAGIC);
                    header.putInt(RUN_VERSION);
                    header.putInt(type.id());
                    header.putInt(rowWidth);
                    header.putInt(duplicatePolicy.ordinal());
                    header.putLong(rows);
                    header.putLong(payloadBytes);
                    header.flip();
                    channel.position(0);
                    writeFully(channel, header,
                            IoPoint.CONSOLIDATION_WRITE_CHUNK);
                }

                RunDescriptor descriptor = new RunDescriptor(
                        path, ordinal, rows, expectedBytes);
                validateRun(descriptor);
                long nextPhysicalSpillBytes = Math.addExact(
                        physicalSpillBytes, expectedBytes);
                materializedRuns = Math.addExact(materializedRuns, 1);
                physicalSpillBytes = nextPhysicalSpillBytes;
                complete = true;
                return descriptor;
            } finally {
                if (!complete) {
                    deleteRunQuietly(path);
                }
            }
        }

        private void flushPage(FileChannel channel, ByteBuffer page,
                CRC32C crc, IoPoint point) throws IOException {
            if (page.position() == 0) {
                return;
            }
            requireNotCancelled();
            int bytes = page.position();
            crc.update(page.array(), 0, bytes);
            page.flip();
            writeFully(channel, page, point);
            page.clear();
        }

        private void validateRun(RunDescriptor descriptor)
                throws IOException {
            try (var cursor = new RunFileCursor(descriptor)) {
                while (cursor.advance()) {
                    // Full validation prevents a partial run from registering.
                }
            }
        }

        private final class ConsolidationCursor
                implements SourceCursor {

            private final SourceCursor[] sources =
                    new SourceCursor[MAX_OPEN_RUNS];
            private int sourceCount;
            private int selected = -1;
            private boolean hasCurrent;
            private long currentFirst;
            private long currentSecond;
            private long currentThird;
            private boolean hasPrevious;
            private long previousFirst;
            private long previousSecond;
            private long previousThird;
            private boolean consolidationClosed;

            private ConsolidationCursor(
                    RunDescriptor[] descriptors, int count)
                    throws IOException {
                boolean complete = false;
                try {
                    for (int run = 0; run < count; run++) {
                        addSource(new RunFileCursor(descriptors[run]));
                    }
                    complete = true;
                } finally {
                    if (!complete) {
                        close();
                    }
                }
            }

            @Override
            public boolean advance() throws IOException {
                requireConsolidationOpen();
                try {
                    requireNotCancelled();
                    while (true) {
                        if (selected >= 0) {
                            SourceCursor source = sources[selected];
                            if (!source.advance()) {
                                source.close();
                                sources[selected] = null;
                            }
                        }
                        selected = selectSmallest();
                        if (selected < 0) {
                            hasCurrent = false;
                            return false;
                        }
                        SourceCursor source = sources[selected];
                        long first = source.word(0);
                        long second =
                                rowWidth > 1 ? source.word(1) : 0;
                        long third =
                                rowWidth > 2 ? source.word(2) : 0;
                        if (hasPrevious
                                && sameRow(first, second, third,
                                        previousFirst, previousSecond,
                                        previousThird)) {
                            if (duplicatePolicy
                                    == DuplicatePolicy.KEEP_REJECT_EXACT) {
                                throw new ProjectIndexFormatException(
                                        "Project index spill contains a duplicate tuple");
                            }
                            continue;
                        }
                        currentFirst = first;
                        currentSecond = second;
                        currentThird = third;
                        previousFirst = first;
                        previousSecond = second;
                        previousThird = third;
                        hasPrevious = true;
                        hasCurrent = true;
                        return true;
                    }
                } catch (IOException | RuntimeException ex) {
                    close();
                    throw ex;
                }
            }

            @Override
            public int rowWidth() {
                requireConsolidationOpen();
                return rowWidth;
            }

            @Override
            public long word(int index) {
                requireConsolidationOpen();
                if (!hasCurrent) {
                    throw new IllegalStateException(
                            "Project index spill source has no current row");
                }
                if (index < 0 || index >= rowWidth) {
                    throw new IndexOutOfBoundsException(
                            "Project index spill word is outside the row");
                }
                return switch (index) {
                    case 0 -> currentFirst;
                    case 1 -> currentSecond;
                    default -> currentThird;
                };
            }

            @Override
            public long ordinal() {
                return sources[selected].ordinal();
            }

            @Override
            public void close() {
                if (consolidationClosed) {
                    return;
                }
                consolidationClosed = true;
                for (int source = 0;
                        source < sourceCount; source++) {
                    closeQuietly(sources[source]);
                    sources[source] = null;
                }
                sourceCount = 0;
                selected = -1;
                hasCurrent = false;
            }

            private void addSource(SourceCursor source)
                    throws IOException {
                boolean registered = false;
                try {
                    if (sourceCount >= sources.length) {
                        throw new IOException(
                                "Project index spill consolidation fan-in exceeded");
                    }
                    if (source.advance()) {
                        sources[sourceCount++] = source;
                        registered = true;
                    }
                } finally {
                    if (!registered) {
                        source.close();
                    }
                }
            }

            private int selectSmallest() {
                int smallest = -1;
                for (int source = 0;
                        source < sourceCount; source++) {
                    SourceCursor candidate = sources[source];
                    if (candidate == null) {
                        continue;
                    }
                    if (smallest < 0
                            || compareSources(candidate,
                                    sources[smallest]) < 0) {
                        smallest = source;
                    }
                }
                return smallest;
            }

            private void requireConsolidationOpen() {
                if (consolidationClosed) {
                    throw new IllegalStateException(
                            "Project index spill consolidation is closed");
                }
            }
        }

        private final class MergeCursor implements Cursor {

            private final SourceCursor[] sources =
                    new SourceCursor[MAX_OPEN_RUNS + 1];
            private int sourceCount;
            private int selected = -1;
            private boolean hasCurrent;
            private long currentFirst;
            private long currentSecond;
            private long currentThird;
            private boolean hasPrevious;
            private long previousFirst;
            private long previousSecond;
            private long previousThird;
            private boolean mergeClosed;

            private MergeCursor() throws IOException {
                boolean complete = false;
                try {
                    for (int i = 0; i < runCount; i++) {
                        addSource(new RunFileCursor(runs[i]));
                    }
                    if (tail.rowCount() != 0) {
                        addSource(new BufferCursor(tail, nextOrdinal));
                    }
                    observeBuffers(tail, runCount, runCount);
                    complete = true;
                } finally {
                    if (!complete) {
                        close();
                    }
                }
            }

            @Override
            public boolean next() throws IOException {
                requireMergeOpen();
                try {
                    requireNotCancelled();
                    while (true) {
                        if (selected >= 0) {
                            SourceCursor source = sources[selected];
                            if (!source.advance()) {
                                source.close();
                                sources[selected] = null;
                            }
                        }
                        selected = selectSmallest();
                        if (selected < 0) {
                            hasCurrent = false;
                            return false;
                        }
                        SourceCursor source = sources[selected];
                        long first = source.word(0);
                        long second =
                                rowWidth > 1 ? source.word(1) : 0;
                        long third =
                                rowWidth > 2 ? source.word(2) : 0;
                        if (hasPrevious
                                && sameRow(first, second, third,
                                        previousFirst, previousSecond,
                                        previousThird)) {
                            if (duplicatePolicy
                                    == DuplicatePolicy.KEEP_REJECT_EXACT) {
                                throw new ProjectIndexFormatException(
                                        "Project index spill contains a duplicate tuple");
                            }
                            continue;
                        }
                        currentFirst = first;
                        currentSecond = second;
                        currentThird = third;
                        previousFirst = first;
                        previousSecond = second;
                        previousThird = third;
                        hasPrevious = true;
                        hasCurrent = true;
                        return true;
                    }
                } catch (IOException | RuntimeException ex) {
                    close();
                    throw ex;
                }
            }

            @Override
            public int rowWidth() {
                requireMergeOpen();
                return rowWidth;
            }

            @Override
            public long word(int index) {
                requireMergeOpen();
                if (!hasCurrent) {
                    throw new IllegalStateException(
                            "Project index spill cursor has no current row");
                }
                if (index < 0 || index >= rowWidth) {
                    throw new IndexOutOfBoundsException(
                            "Project index spill word is outside the row");
                }
                return switch (index) {
                    case 0 -> currentFirst;
                    case 1 -> currentSecond;
                    default -> currentThird;
                };
            }

            @Override
            public void close() {
                if (mergeClosed) {
                    return;
                }
                mergeClosed = true;
                for (int i = 0; i < sourceCount; i++) {
                    closeQuietly(sources[i]);
                    sources[i] = null;
                }
                sourceCount = 0;
                selected = -1;
                hasCurrent = false;
                cursorOpen = false;
            }

            private void addSource(SourceCursor source)
                    throws IOException {
                boolean registered = false;
                try {
                    if (sourceCount >= sources.length) {
                        throw new IOException(
                                "Project index spill merge fan-in exceeded");
                    }
                    if (source.advance()) {
                        sources[sourceCount++] = source;
                        registered = true;
                    }
                } finally {
                    if (!registered) {
                        source.close();
                    }
                }
            }

            private int selectSmallest() {
                int smallest = -1;
                for (int i = 0; i < sourceCount; i++) {
                    SourceCursor candidate = sources[i];
                    if (candidate == null) {
                        continue;
                    }
                    if (smallest < 0
                            || compareSources(candidate,
                                    sources[smallest]) < 0) {
                        smallest = i;
                    }
                }
                return smallest;
            }

            private void requireMergeOpen() {
                if (mergeClosed || !cursorOpen) {
                    throw new IllegalStateException(
                            "Project index spill cursor is closed");
                }
                requireAvailable();
            }
        }

        private final class RunFileCursor implements SourceCursor {

            private final RunDescriptor descriptor;
            private final TrackedRunChannel trackedChannel;
            private final FileChannel channel;
            private final ByteBuffer page = ByteBuffer.allocate(PAGE_BYTES)
                    .order(ByteOrder.BIG_ENDIAN);
            private final CRC32C crc = new CRC32C();
            private long loadedPayloadBytes;
            private long consumedRows;
            private long first;
            private long second;
            private long third;
            private boolean hasPrevious;
            private long previousFirst;
            private long previousSecond;
            private long previousThird;
            private boolean footerValidated;
            private boolean runClosed;

            private RunFileCursor(RunDescriptor descriptor)
                    throws IOException {
                this.descriptor = descriptor;
                trackedChannel = openExistingRunFile(
                        descriptor.path());
                channel = trackedChannel.channel();
                page.limit(0);
                boolean complete = false;
                try {
                    readAndValidateHeader();
                    complete = true;
                } finally {
                    if (!complete) {
                        close();
                    }
                }
            }

            @Override
            public boolean advance() throws IOException {
                requireRunOpen();
                try {
                    requireNotCancelled();
                    if (consumedRows == descriptor.rows()) {
                        validateFooter();
                        return false;
                    }
                    first = readLong();
                    second = rowWidth > 1 ? readLong() : 0;
                    third = rowWidth > 2 ? readLong() : 0;
                    if (hasPrevious) {
                        int comparison = compareRow(first, second, third,
                                previousFirst, previousSecond,
                                previousThird, rowWidth);
                        if (comparison < 0
                                || comparison == 0
                                && duplicatePolicy
                                        == DuplicatePolicy.DEDUP_EXACT) {
                            throw new ProjectIndexFormatException(
                                    "Project index spill run is not canonical");
                        }
                    }
                    previousFirst = first;
                    previousSecond = second;
                    previousThird = third;
                    hasPrevious = true;
                    consumedRows++;
                    return true;
                } catch (IOException | RuntimeException ex) {
                    close();
                    throw ex;
                }
            }

            @Override
            public int rowWidth() {
                return rowWidth;
            }

            @Override
            public long word(int index) {
                if (!hasPrevious || consumedRows == 0) {
                    throw new IllegalStateException(
                            "Project index spill source has no current row");
                }
                if (index < 0 || index >= rowWidth) {
                    throw new IndexOutOfBoundsException(
                            "Project index spill word is outside the row");
                }
                return switch (index) {
                    case 0 -> first;
                    case 1 -> second;
                    default -> third;
                };
            }

            @Override
            public long ordinal() {
                return descriptor.ordinal();
            }

            @Override
            public void close() {
                if (runClosed) {
                    return;
                }
                runClosed = true;
                closeQuietly(trackedChannel);
            }

            private void readAndValidateHeader() throws IOException {
                if (channel.size() != descriptor.length()) {
                    throw new ProjectIndexFormatException(
                            "Project index spill run length changed");
                }
                ByteBuffer header = ByteBuffer.allocate(RUN_HEADER_BYTES)
                        .order(ByteOrder.BIG_ENDIAN);
                readFully(channel, header);
                header.flip();
                byte[] magic = new byte[RUN_MAGIC.length];
                header.get(magic);
                int version = header.getInt();
                int sectionId = header.getInt();
                int width = header.getInt();
                int policy = header.getInt();
                long rows = header.getLong();
                long payloadBytes = header.getLong();
                if (!Arrays.equals(RUN_MAGIC, magic)
                        || version != RUN_VERSION
                        || sectionId != type.id()
                        || width != rowWidth
                        || policy != duplicatePolicy.ordinal()
                        || rows < 0
                        || rows != descriptor.rows()
                        || payloadBytes < 0) {
                    throw new ProjectIndexFormatException(
                            "Project index spill run header is invalid");
                }
                long expectedPayload;
                long expectedLength;
                try {
                    expectedPayload = Math.multiplyExact(
                            Math.multiplyExact(rows, width),
                            Long.BYTES);
                    expectedLength = Math.addExact(
                            Math.addExact(RUN_HEADER_BYTES,
                                    expectedPayload),
                            RUN_FOOTER_BYTES);
                } catch (ArithmeticException ex) {
                    throw new ProjectIndexFormatException(
                            "Project index spill run layout overflows",
                            ex);
                }
                if (payloadBytes != expectedPayload
                        || expectedLength != descriptor.length()) {
                    throw new ProjectIndexFormatException(
                            "Project index spill run header is invalid");
                }
            }

            private long readLong() throws IOException {
                if (page.remaining() < Long.BYTES) {
                    fillPage();
                }
                if (page.remaining() < Long.BYTES) {
                    throw new ProjectIndexFormatException(
                            "Project index spill run payload is truncated");
                }
                return page.getLong();
            }

            private void fillPage() throws IOException {
                page.compact();
                int preserved = page.position();
                long payloadBytes = Math.multiplyExact(
                        Math.multiplyExact(descriptor.rows(), rowWidth),
                        Long.BYTES);
                long remaining = payloadBytes - loadedPayloadBytes;
                int requested = (int) Math.min(page.remaining(), remaining);
                if (requested > 0) {
                    int target = preserved + requested;
                    page.limit(target);
                    while (page.position() < target) {
                        requireNotCancelled();
                        int read = channel.read(page);
                        if (read < 0) {
                            throw new ProjectIndexFormatException(
                                    "Project index spill run payload is truncated");
                        }
                        if (read > 0) {
                            ioHook.at(IoPoint.RUN_READ_CHUNK);
                        }
                    }
                    crc.update(page.array(), preserved, requested);
                    loadedPayloadBytes += requested;
                }
                page.limit(page.position());
                page.position(0);
            }

            private void validateFooter() throws IOException {
                if (footerValidated) {
                    return;
                }
                if (page.hasRemaining()) {
                    throw new ProjectIndexFormatException(
                            "Project index spill run payload has trailing bytes");
                }
                ByteBuffer footer = ByteBuffer.allocate(RUN_FOOTER_BYTES)
                        .order(ByteOrder.BIG_ENDIAN);
                readFully(channel, footer);
                footer.flip();
                int expectedCrc = footer.getInt();
                byte[] magic = new byte[RUN_END_MAGIC.length];
                footer.get(magic);
                if (expectedCrc != (int) crc.getValue()
                        || !Arrays.equals(RUN_END_MAGIC, magic)
                        || channel.position() != descriptor.length()
                        || channel.size() != descriptor.length()) {
                    throw new ProjectIndexFormatException(
                            "Project index spill run footer is invalid");
                }
                footerValidated = true;
            }

            private void requireRunOpen() {
                if (runClosed) {
                    throw new IllegalStateException(
                            "Project index spill run is closed");
                }
            }
        }

        private final class BufferCursor implements SourceCursor {

            private final ProjectIndexTupleBuffer buffer;
            private final long ordinal;
            private int row = -1;

            private BufferCursor(ProjectIndexTupleBuffer buffer,
                    long ordinal) {
                this.buffer = buffer;
                this.ordinal = ordinal;
            }

            @Override
            public boolean advance() {
                row++;
                return row < buffer.rowCount();
            }

            @Override
            public int rowWidth() {
                return rowWidth;
            }

            @Override
            public long word(int index) {
                return buffer.word(row, index);
            }

            @Override
            public long ordinal() {
                return ordinal;
            }

            @Override
            public void close() {
                // The section owns the in-memory tail.
            }
        }
    }

    final class GroupCounts implements AutoCloseable {

        private final SectionRuns owner;
        private final int width;
        private final Path path;
        private final TrackedRunChannel trackedChannel;
        private final FileChannel channel;
        private final ByteBuffer writePage = ByteBuffer.allocate(PAGE_BYTES)
                .order(ByteOrder.BIG_ENDIAN);
        private final ByteBuffer readPage = ByteBuffer.allocate(PAGE_BYTES)
                .order(ByteOrder.BIG_ENDIAN);
        private final CRC32C writeCrc = new CRC32C();
        private final CRC32C readCrc = new CRC32C();
        private final int[] current = new int[2];
        private long rows;
        private long readRows;
        private boolean finished;
        private boolean readComplete;
        private boolean hasCurrent;
        private boolean countsClosed;

        private GroupCounts(SectionRuns owner, int width)
                throws IOException {
            this.owner = Objects.requireNonNull(owner, "owner");
            if (width != 1 && width != 2) {
                throw new IllegalArgumentException(
                        "Project index group count width must be 1 or 2");
            }
            this.width = width;
            ensureWorkspace();
            path = workspace.resolve("counts-" + owner.type.id() + ".pgc");
            TrackedRunChannel opened = null;
            boolean complete = false;
            try {
                opened = openRunFile(path, true);
                trackedChannel = opened;
                channel = opened.channel();
                writeFully(channel,
                        ByteBuffer.allocate(COUNTS_HEADER_BYTES),
                        IoPoint.RUN_WRITE_CHUNK);
                readPage.limit(0);
                complete = true;
            } finally {
                if (!complete) {
                    closeQuietly(opened);
                    deleteRunQuietly(path);
                }
            }
        }

        void add(int value) throws IOException {
            requireWidth(1);
            addValues(value, 0);
        }

        void add(int first, int second) throws IOException {
            requireWidth(2);
            addValues(first, second);
        }

        long rows() {
            requireCountsOpen();
            return rows;
        }

        void finish() throws IOException {
            requireCountsOpen();
            if (finished) {
                throw new IllegalStateException(
                        "Project index group counts are already finished");
            }
            flushWritePage();
            ByteBuffer footer = ByteBuffer.allocate(COUNTS_FOOTER_BYTES)
                    .order(ByteOrder.BIG_ENDIAN);
            footer.putInt((int) writeCrc.getValue());
            footer.put(COUNTS_END_MAGIC);
            footer.flip();
            writeFully(channel, footer, IoPoint.RUN_WRITE_CHUNK);

            long payloadBytes = Math.multiplyExact(
                    Math.multiplyExact(rows, width), Integer.BYTES);
            long expectedBytes = Math.addExact(
                    Math.addExact(COUNTS_HEADER_BYTES, payloadBytes),
                    COUNTS_FOOTER_BYTES);
            if (channel.size() != expectedBytes) {
                throw new ProjectIndexFormatException(
                        "Project index group count sidecar has an invalid length");
            }
            ByteBuffer header = ByteBuffer.allocate(COUNTS_HEADER_BYTES)
                    .order(ByteOrder.BIG_ENDIAN);
            header.put(COUNTS_MAGIC);
            header.putInt(COUNTS_VERSION);
            header.putInt(owner.type.id());
            header.putInt(width);
            header.putInt(0);
            header.putLong(rows);
            header.putLong(payloadBytes);
            header.flip();
            channel.position(0);
            writeFully(channel, header, IoPoint.RUN_WRITE_CHUNK);
            validateHeader(expectedBytes);
            channel.position(COUNTS_HEADER_BYTES);
            physicalSpillBytes = Math.addExact(
                    physicalSpillBytes, expectedBytes);
            finished = true;
        }

        boolean next() throws IOException {
            requireCountsOpen();
            if (!finished) {
                throw new IllegalStateException(
                        "Project index group counts are not finished");
            }
            if (readComplete) {
                hasCurrent = false;
                return false;
            }
            if (readRows == rows) {
                validateFooter();
                hasCurrent = false;
                readComplete = true;
                return false;
            }
            int rowBytes = width * Integer.BYTES;
            if (readPage.remaining() < rowBytes) {
                loadReadPage();
            }
            for (int index = 0; index < width; index++) {
                current[index] = readPage.getInt();
            }
            readRows++;
            hasCurrent = true;
            return true;
        }

        int value(int index) {
            requireCountsOpen();
            if (!hasCurrent) {
                throw new IllegalStateException(
                        "Project index group counts have no current row");
            }
            if (index < 0 || index >= width) {
                throw new IndexOutOfBoundsException(
                        "Project index group count is outside the row");
            }
            return current[index];
        }

        @Override
        public void close() {
            if (countsClosed) {
                return;
            }
            countsClosed = true;
            hasCurrent = false;
            closeQuietly(trackedChannel);
            deleteRunQuietly(path);
        }

        private void addValues(int first, int second)
                throws IOException {
            requireCountsOpen();
            if (finished) {
                throw new IllegalStateException(
                        "Project index group counts are already finished");
            }
            if (first < 0 || second < 0) {
                throw new IllegalArgumentException(
                        "Project index group counts must not be negative");
            }
            requireNotCancelled();
            int rowBytes = width * Integer.BYTES;
            if (writePage.remaining() < rowBytes) {
                flushWritePage();
            }
            writePage.putInt(first);
            if (width == 2) {
                writePage.putInt(second);
            }
            rows = Math.addExact(rows, 1);
        }

        private void flushWritePage() throws IOException {
            if (writePage.position() == 0) {
                return;
            }
            requireNotCancelled();
            int bytes = writePage.position();
            writeCrc.update(writePage.array(), 0, bytes);
            writePage.flip();
            writeFully(channel, writePage, IoPoint.RUN_WRITE_CHUNK);
            writePage.clear();
        }

        private void validateHeader(long expectedBytes)
                throws IOException {
            ByteBuffer header = ByteBuffer.allocate(COUNTS_HEADER_BYTES)
                    .order(ByteOrder.BIG_ENDIAN);
            channel.position(0);
            readFully(channel, header);
            header.flip();
            byte[] magic = new byte[COUNTS_MAGIC.length];
            header.get(magic);
            int version = header.getInt();
            int section = header.getInt();
            int encodedWidth = header.getInt();
            int reserved = header.getInt();
            long encodedRows = header.getLong();
            long payloadBytes = header.getLong();
            if (!Arrays.equals(magic, COUNTS_MAGIC)
                    || version != COUNTS_VERSION
                    || section != owner.type.id()
                    || encodedWidth != width || reserved != 0
                    || encodedRows != rows
                    || payloadBytes != rows * width * Integer.BYTES
                    || channel.size() != expectedBytes) {
                throw new ProjectIndexFormatException(
                        "Project index group count sidecar header is invalid");
            }
        }

        private void loadReadPage() throws IOException {
            requireNotCancelled();
            long remaining = Math.multiplyExact(
                    Math.multiplyExact(rows - readRows, width),
                    Integer.BYTES);
            int bytes = Math.toIntExact(Math.min(PAGE_BYTES, remaining));
            if (bytes == 0) {
                throw new ProjectIndexFormatException(
                        "Project index group count sidecar is truncated");
            }
            readPage.clear();
            readPage.limit(bytes);
            ioHook.at(IoPoint.RUN_READ_CHUNK);
            readFully(channel, readPage);
            readCrc.update(readPage.array(), 0, bytes);
            readPage.flip();
        }

        private void validateFooter() throws IOException {
            if (readPage.hasRemaining()) {
                throw new ProjectIndexFormatException(
                        "Project index group count sidecar payload has trailing bytes");
            }
            ByteBuffer footer = ByteBuffer.allocate(COUNTS_FOOTER_BYTES)
                    .order(ByteOrder.BIG_ENDIAN);
            readFully(channel, footer);
            footer.flip();
            int expectedCrc = footer.getInt();
            byte[] magic = new byte[COUNTS_END_MAGIC.length];
            footer.get(magic);
            if (expectedCrc != (int) readCrc.getValue()
                    || !Arrays.equals(magic, COUNTS_END_MAGIC)
                    || channel.position() != channel.size()) {
                throw new ProjectIndexFormatException(
                        "Project index group count sidecar footer is invalid");
            }
        }

        private void requireWidth(int expected) {
            if (width != expected) {
                throw new IllegalStateException(
                        "Project index group count row width mismatch");
            }
        }

        private void requireCountsOpen() {
            requireOpen();
            owner.requireAvailable();
            if (countsClosed) {
                throw new IllegalStateException(
                        "Project index group counts are closed");
            }
        }
    }

    private interface SourceCursor extends AutoCloseable {

        boolean advance() throws IOException;

        int rowWidth();

        long word(int index);

        long ordinal();

        @Override
        void close();
    }

    private record RunDescriptor(Path path, long ordinal,
            long rows, long length) {
    }

    private record PendingConsolidation(RunDescriptor merged,
            RunDescriptor[] previous) {
    }

    private record WorkspaceOwnership(Path workspace) {
    }

    private final class TrackedRunChannel implements AutoCloseable {

        private final FileChannel channel;
        private boolean channelClosed;

        private TrackedRunChannel(FileChannel channel)
                throws IOException {
            this.channel = Objects.requireNonNull(channel, "channel");
            if (openRuns >= MAX_OPEN_RUNS) {
                channel.close();
                throw new IOException(
                        "Project index spill run channel limit exceeded");
            }
            openRuns = Math.addExact(openRuns, 1);
            peakOpenRuns = Math.max(peakOpenRuns, openRuns);
        }

        private FileChannel channel() {
            return channel;
        }

        @Override
        public void close() throws IOException {
            if (channelClosed) {
                return;
            }
            channelClosed = true;
            try {
                channel.close();
            } finally {
                openRuns--;
            }
        }
    }

    private void requireNotCancelled()
            throws ProjectIndexStore.WriteCancelledException {
        if (cancelled.getAsBoolean()) {
            throw new ProjectIndexStore.WriteCancelledException();
        }
    }

    private void ensureWorkspace() throws IOException {
        if (workspaceCreated) {
            if (!Files.isDirectory(
                    workspace, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException(
                        "Project index spill workspace was replaced");
            }
            return;
        }
        workspaceOwnership = registerWorkspace(
                workspace, this::createWorkspace);
        workspaceCreated = true;
    }

    private void createWorkspace(Path target) throws IOException {
        boolean workspaceCreatedByThisCall = false;
        try {
            boolean stateDirectoryExisted =
                    Files.exists(stateDirectory,
                            LinkOption.NOFOLLOW_LINKS);
            Files.createDirectories(stateDirectory);
            if (!Files.isDirectory(
                    stateDirectory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException(
                        "Project index state path is not a directory");
            }
            posixPermissions = Files.getFileStore(stateDirectory)
                    .supportsFileAttributeView(
                            PosixFileAttributeView.class);
            if (posixPermissions && !stateDirectoryExisted) {
                Files.setPosixFilePermissions(
                        stateDirectory, DIRECTORY_PERMISSIONS);
            }
            if (posixPermissions) {
                FileAttribute<Set<PosixFilePermission>> permissions =
                        PosixFilePermissions.asFileAttribute(
                                DIRECTORY_PERMISSIONS);
                Files.createDirectory(target, permissions);
            } else {
                Files.createDirectory(target);
            }
            workspaceCreatedByThisCall = true;
            ioHook.at(IoPoint.WORKSPACE_CREATED);
            if (posixPermissions) {
                Files.setPosixFilePermissions(
                        target, DIRECTORY_PERMISSIONS);
            }
        } catch (IOException | RuntimeException ex) {
            if (workspaceCreatedByThisCall) {
                deleteEmptyWorkspaceAfterCreateFailure(target, ex);
            }
            throw ex;
        }
    }

    private static void deleteEmptyWorkspaceAfterCreateFailure(
            Path target, Exception originalFailure) {
        try {
            if (Files.isDirectory(
                    target, LinkOption.NOFOLLOW_LINKS)) {
                Files.deleteIfExists(target);
            }
        } catch (IOException | RuntimeException cleanupFailure) {
            originalFailure.addSuppressed(cleanupFailure);
        }
    }

    private TrackedRunChannel openRunFile(Path path, boolean readable)
            throws IOException {
        Set<OpenOption> options = readable
                ? Set.of(StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.READ,
                        StandardOpenOption.WRITE)
                : Set.of(StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
        FileChannel channel = posixPermissions
                ? FileChannel.open(path, options,
                        PosixFilePermissions.asFileAttribute(
                                RUN_PERMISSIONS))
                : FileChannel.open(path, options);
        var tracked = new TrackedRunChannel(channel);
        if (!posixPermissions) {
            return tracked;
        }
        try {
            Files.setPosixFilePermissions(path, RUN_PERMISSIONS);
            return tracked;
        } catch (IOException | RuntimeException ex) {
            closeQuietly(tracked);
            throw ex;
        }
    }

    private TrackedRunChannel openExistingRunFile(Path path)
            throws IOException {
        return new TrackedRunChannel(FileChannel.open(path,
                StandardOpenOption.READ));
    }

    private void observeBuffers(ProjectIndexTupleBuffer buffer,
            int openPages, int descriptors) {
        long words = Math.multiplyExact(
                (long) buffer.capacity(), buffer.rowWidth());
        long bufferBytes = ProjectIndexFormat.arrayBytes(words, Long.BYTES);
        peakTrackedBufferBytes =
                Math.max(peakTrackedBufferBytes, bufferBytes);
        long pages = Math.multiplyExact(
                (long) openPages,
                ProjectIndexFormat.arrayBytes(PAGE_BYTES, Byte.BYTES));
        long descriptorBytes = Math.multiplyExact((long) descriptors, 64);
        long resident = Math.addExact(bufferBytes,
                Math.addExact(pages, descriptorBytes));
        peakTrackedResidentBytes =
                Math.max(peakTrackedResidentBytes, resident);
    }

    private static int compareSources(SourceCursor left,
            SourceCursor right) {
        int compared = compareRow(
                left.word(0),
                left.rowWidth() > 1 ? left.word(1) : 0,
                left.rowWidth() > 2 ? left.word(2) : 0,
                right.word(0),
                right.rowWidth() > 1 ? right.word(1) : 0,
                right.rowWidth() > 2 ? right.word(2) : 0,
                left.rowWidth());
        return compared != 0 ? compared
                : Long.compareUnsigned(left.ordinal(), right.ordinal());
    }

    private static int compareRow(long leftFirst, long leftSecond,
            long leftThird, long rightFirst, long rightSecond,
            long rightThird, int rowWidth) {
        int compared = Long.compareUnsigned(leftFirst, rightFirst);
        if (compared != 0 || rowWidth == 1) {
            return compared;
        }
        compared = Long.compareUnsigned(leftSecond, rightSecond);
        return compared != 0 || rowWidth == 2
                ? compared
                : Long.compareUnsigned(leftThird, rightThird);
    }

    private static boolean sameRow(long leftFirst, long leftSecond,
            long leftThird, long rightFirst, long rightSecond,
            long rightThird) {
        return leftFirst == rightFirst
                && leftSecond == rightSecond
                && leftThird == rightThird;
    }

    private void writeFully(FileChannel channel, ByteBuffer source,
            IoPoint point) throws IOException {
        while (source.hasRemaining()) {
            requireNotCancelled();
            ioHook.at(point);
            if (channel.write(source) <= 0) {
                throw new IOException(
                        "Unable to make progress while writing project index spill");
            }
        }
    }

    private static void readFully(FileChannel channel, ByteBuffer target)
            throws IOException {
        while (target.hasRemaining()) {
            int read = channel.read(target);
            if (read < 0) {
                throw new ProjectIndexFormatException(
                        "Project index spill run is truncated");
            }
            if (read == 0) {
                throw new IOException(
                        "Unable to make progress while reading project index spill");
            }
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ex) {
            // Best-effort cleanup of temporary spill resources.
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException | RuntimeException ex) {
            // Best-effort cleanup of a temporary run.
        }
    }

    private void deleteRunQuietly(Path path) {
        try {
            if (path == null
                    || !workspace.equals(path.toAbsolutePath()
                            .normalize().getParent())
                    || !Files.isDirectory(
                            workspace, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            deleteQuietly(path);
        } catch (RuntimeException ex) {
            // Best-effort cleanup of a temporary run.
        }
    }

    private void deleteWorkspaceQuietly() {
        if (!workspaceCreated) {
            return;
        }
        if (!Files.isDirectory(
                workspace, LinkOption.NOFOLLOW_LINKS)) {
            deleteQuietly(workspace);
            workspaceCreated = false;
            return;
        }
        try (DirectoryStream<Path> entries =
                Files.newDirectoryStream(workspace)) {
            for (Path path : entries) {
                deleteQuietly(path);
            }
        } catch (IOException | RuntimeException ex) {
            // Best-effort cleanup continues with the workspace itself.
        }
        deleteQuietly(workspace);
        workspaceCreated = false;
    }

}
