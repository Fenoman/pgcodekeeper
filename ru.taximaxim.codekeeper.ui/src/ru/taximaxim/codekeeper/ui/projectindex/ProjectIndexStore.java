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

import static ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexFormat.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.zip.CRC32C;

import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexDelta.Change;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexDelta.Operation;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexOpenResult.Status;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetry.Phase;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetry.PhaseTimer;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexView.OverlayValue;

/**
 * Persistent index store for one Eclipse workspace process. Eclipse owns the
 * workspace lock; store instances in that process serialize publications by
 * normalized state-directory path. Concurrent writers from another process
 * are outside this contract. The state directory is trusted private workspace
 * state: cleanup never follows stable symbolic links, while adversarial
 * same-user path replacement during cleanup is outside the workspace-lock
 * threat model.
 */
public final class ProjectIndexStore implements AutoCloseable {

    public static final long DEFAULT_CACHE_BYTES = 32L << 20;
    public static final long DEFAULT_COMPACTION_BYTES = 64L << 20;
    public static final long DEFAULT_TUPLE_BUDGET_BYTES = 64L << 20;

    private static final byte[] CURRENT_MAGIC =
            "PGCKCUR2".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] JOURNAL_MAGIC =
            "PGCKJNL2".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] JOURNAL_END_MAGIC =
            "PGCKJEND".getBytes(StandardCharsets.US_ASCII);
    private static final int CURRENT_VERSION = 2;
    private static final int PUBLICATION_ID_BYTES = 32;
    private static final String WRITER_WORKSPACE_PREFIX = ".writer-";
    private static final String WRITER_WORKSPACE_SUFFIX = ".tmp";
    private static final int CURRENT_BYTES = 8 + 4 + PUBLICATION_ID_BYTES
            + Long.BYTES * 3 + Integer.BYTES;
    private static final int JOURNAL_HEADER_BYTES = 100;
    private static final int JOURNAL_FOOTER_BYTES = 8;
    private static final int MAX_JOURNAL_PATH_BYTES = 1 << 20;
    /**
     * Hard cap on committed journal entries. This one is an on-disk format
     * invariant enforced by {@link #readJournal}, not a policy knob: a batch
     * that does not fit is folded into a fresh generation instead of being
     * rejected.
     */
    private static final int MAX_JOURNAL_ENTRIES = 32;
    private static final int MAX_INCREMENTAL_BATCH_ENTRIES =
            ProjectIndexIncrementalPlanner.MAX_BATCH_SIZE;
    private static final int PUBLICATION_MOVE_ATTEMPTS = 3;
    private static final long PUBLICATION_MOVE_BACKOFF_MILLIS = 10L;
    private static final boolean RETRY_PUBLICATION_RENAME =
            retriesPublicationRename(System.getProperty("os.name", ""));
    private static final byte[] EMPTY_SHA256 = new byte[32];
    private static final Object[] PUBLICATION_LOCKS =
            createPublicationLocks(64);

    public enum PublishResult {
        PUBLISHED,
        CANCELLED
    }

    public record PublishOutcome(PublishResult status,
            ProjectIndexRevision revision) {

        public PublishOutcome {
            Objects.requireNonNull(status, "status");
            if ((status == PublishResult.PUBLISHED)
                    != (revision != null)) {
                throw new IllegalArgumentException(
                        "Only a published index has a revision");
            }
        }
    }

    public enum AppendResult {
        APPENDED,
        CANCELLED
    }

    public enum IncrementalAppendStatus {
        APPENDED,
        CANCELLED,
        REQUIRES_FULL
    }

    /**
     * Outcome of one incremental revision. {@code compactionFailure} carries
     * the reason a journal fold was abandoned so that the caller can report
     * why it fell back to a full rebuild.
     */
    public record IncrementalAppendResult(
            IncrementalAppendStatus status,
            ProjectIndexRevision revision,
            IOException compactionFailure) {

        public IncrementalAppendResult(IncrementalAppendStatus status,
                ProjectIndexRevision revision) {
            this(status, revision, null);
        }

        public IncrementalAppendResult {
            Objects.requireNonNull(status, "status");
            if ((status == IncrementalAppendStatus.APPENDED)
                    != (revision != null)) {
                throw new IllegalArgumentException(
                        "Only an appended index has a revision");
            }
            if (compactionFailure != null
                    && status != IncrementalAppendStatus.REQUIRES_FULL) {
                throw new IllegalArgumentException(
                        "Only an abandoned compaction reports a failure");
            }
        }
    }

    enum IoPoint {
        BEFORE_OPEN,
        BASE_WRITE_CHUNK,
        JOURNAL_WRITE_CHUNK,
        AFTER_JOURNAL_ENTRY,
        LOCATOR_READ_CHUNK,
        AFTER_BASE_WRITE,
        AFTER_JOURNAL_WRITE,
        BEFORE_CURRENT_PUBLICATION,
        AFTER_CURRENT_MOVE,
        AFTER_CURRENT_PUBLICATION,
        BEFORE_CLEAN_PAYLOAD_DELETE
    }

    @FunctionalInterface
    interface IoHook {
        IoHook NONE = point -> {
        };

        void at(IoPoint point) throws IOException;
    }

    private final Path directory;
    private final Object publicationLock;
    private final long cacheBytes;
    private final IoHook hook;
    private final long compactionBytes;
    private final ProjectIndexDirectorySync directorySync;

    public ProjectIndexStore(Path directory) {
        this(directory, DEFAULT_CACHE_BYTES, IoHook.NONE,
                DEFAULT_COMPACTION_BYTES,
                ProjectIndexDirectorySync.platform());
    }

    ProjectIndexStore(Path directory, long cacheBytes, IoHook hook, long compactionBytes) {
        this(directory, cacheBytes, hook, compactionBytes,
                ProjectIndexDirectorySync.platform());
    }

    ProjectIndexStore(Path directory, long cacheBytes, IoHook hook,
            long compactionBytes,
            ProjectIndexDirectorySync directorySync) {
        this.directory = Objects.requireNonNull(directory, "directory")
                .toAbsolutePath().normalize();
        publicationLock = PUBLICATION_LOCKS[
                Math.floorMod(this.directory.hashCode(),
                        PUBLICATION_LOCKS.length)];
        if (cacheBytes < ProjectIndexBlockCache.BLOCK_BYTES
                || cacheBytes > DEFAULT_CACHE_BYTES) {
            throw new IllegalArgumentException("Project index cache must be between 64 KiB and 32 MiB");
        }
        if (compactionBytes <= 0) {
            throw new IllegalArgumentException("Project index compaction threshold must be positive");
        }
        this.cacheBytes = cacheBytes;
        this.hook = Objects.requireNonNull(hook, "hook");
        this.compactionBytes = compactionBytes;
        this.directorySync =
                Objects.requireNonNull(directorySync, "directorySync");
    }

    public synchronized PublishResult publish(ProjectIndexData data,
            BooleanSupplier cancelled) throws IOException {
        return publishWithReceipt(data, cancelled).status();
    }

    public synchronized PublishOutcome publishWithReceipt(
            ProjectIndexData data, BooleanSupplier cancelled)
            throws IOException {
        return publishWithReceipt(data, cancelled, null);
    }

    public synchronized PublishOutcome publishWithReceipt(
            ProjectIndexData data, BooleanSupplier cancelled,
            ProjectIndexTelemetry.Run telemetry)
            throws IOException {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(cancelled, "cancelled");
        return publishOutcome(data.manifest(), cancelled, telemetry,
                (channel, context) ->
                        ProjectIndexContainer.write(channel, data, context));
    }

    private PublishOutcome publishOutcome(ProjectIndexManifest manifest,
            BooleanSupplier cancelled, ProjectIndexTelemetry.Run telemetry,
            ContainerEncoder encoder) throws IOException {
        synchronized (publicationLock) {
            return publishLocked(manifest, cancelled, telemetry, encoder);
        }
    }

    private PublishOutcome publishLocked(ProjectIndexManifest manifest,
            BooleanSupplier cancelled, ProjectIndexTelemetry.Run telemetry,
            ContainerEncoder encoder) throws IOException {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(cancelled, "cancelled");
        Objects.requireNonNull(encoder, "encoder");
        if (cancelled.getAsBoolean()) {
            return new PublishOutcome(PublishResult.CANCELLED, null);
        }
        Files.createDirectories(directory);
        String id = newPublicationId();
        ProjectIndexWriteContext context = writeContext(
                id, cancelled, telemetry);
        Path baseTemp = directory.resolve(".base-" + id + ".tmp");
        Path journalTemp = directory.resolve(".journal-" + id + ".tmp");
        Path currentTemp = directory.resolve(".current-" + id + ".tmp");
        Path base = basePath(id);
        Path journal = journalPath(id);
        boolean published = false;
        ProjectIndexRevision revision =
                new ProjectIndexRevision(id, 0,
                        manifest.generation(),
                        ProjectIndexIdentity.from(manifest));
        try {
            try (FileChannel channel = FileChannel.open(baseTemp,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.READ,
                    StandardOpenOption.WRITE)) {
                encoder.write(new CancellableChannel(channel, cancelled, hook,
                        IoPoint.BASE_WRITE_CHUNK), context);
                hook.at(IoPoint.AFTER_BASE_WRITE);
                if (cancelled.getAsBoolean()) {
                    throw new WriteCancelledException();
                }
                PhaseTimer timer = phase(telemetry, Phase.FSYNC);
                try {
                    channel.force(true);
                } finally {
                    close(timer);
                }
            }
            atomicMove(baseTemp, base);
            try (FileChannel channel = FileChannel.open(journalTemp,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                PhaseTimer timer = phase(telemetry, Phase.FSYNC);
                try {
                    channel.force(true);
                } finally {
                    close(timer);
                }
            }
            atomicMove(journalTemp, journal);
            syncDirectory(ProjectIndexDirectorySync.Stage.PAYLOADS,
                    null, telemetry);
            if (cancelled.getAsBoolean()) {
                throw new WriteCancelledException();
            }
            Current current = new Current(id, manifest.generation(),
                    Files.size(base), 0);
            PhaseTimer currentTimer =
                    phase(telemetry, Phase.CURRENT);
            try {
                hook.at(IoPoint.BEFORE_CURRENT_PUBLICATION);
                requireNotCancelled(cancelled);
                writeCurrent(currentTemp, current);
                requireNotCancelled(cancelled);
                publishCurrent(currentTemp, current);
                published = true;
                syncDirectory(ProjectIndexDirectorySync.Stage.CURRENT,
                        revision, telemetry);
            } finally {
                close(currentTimer);
            }
            try {
                hook.at(IoPoint.AFTER_CURRENT_PUBLICATION);
            } catch (IOException | RuntimeException ex) {
                // The durable CURRENT already owns this revision.
            }
            cleanupOrphansQuietly(id);
            return new PublishOutcome(PublishResult.PUBLISHED,
                    revision);
        } catch (WriteCancelledException ex) {
            return new PublishOutcome(PublishResult.CANCELLED, null);
        } finally {
            deleteQuietly(baseTemp);
            deleteQuietly(journalTemp);
            deleteQuietly(currentTemp);
            if (!published) {
                deleteUnpublishedPayloadIfSafe(id, base, journal);
            }
        }
    }

    /**
     * Publishes v2 atomically and removes a legacy serialized index only after
     * the new generation became current. The legacy file is never opened.
     */
    public synchronized PublishResult publishAndDeleteLegacy(ProjectIndexData data,
            BooleanSupplier cancelled, Path legacyState) throws IOException {
        Objects.requireNonNull(legacyState, "legacyState");
        Path fileName = legacyState.getFileName();
        if (fileName == null || !fileName.toString().endsWith(".ser")) {
            throw new IllegalArgumentException(
                    "Legacy project index must have the .ser suffix");
        }
        PublishResult result = publish(data, cancelled);
        if (result == PublishResult.PUBLISHED) {
            Files.deleteIfExists(legacyState);
        }
        return result;
    }

    public synchronized ProjectIndexOpenResult open(
            ProjectIndexIdentity expected) {
        synchronized (publicationLock) {
            return openLocked(expected);
        }
    }

    private ProjectIndexOpenResult openLocked(
            ProjectIndexIdentity expected) {
        Objects.requireNonNull(expected, "expected");
        if (!Files.isRegularFile(currentPath())) {
            return ProjectIndexOpenResult.empty(Status.MISS);
        }
        FileChannel baseChannel = null;
        FileChannel journalChannel = null;
        LazyProjectIndexBase base = null;
        Map<IndexPathRef, OverlayValue> overlay = new HashMap<>();
        try {
            hook.at(IoPoint.BEFORE_OPEN);
            Current current = readCurrent();
            Path basePath = basePath(current.publicationId());
            Path journalPath = journalPath(current.publicationId());
            baseChannel = FileChannel.open(basePath, StandardOpenOption.READ);
            journalChannel = FileChannel.open(journalPath, StandardOpenOption.READ);
            if (baseChannel.size() != current.baseLength()
                    || current.journalLength() < 0
                    || current.journalLength() > journalChannel.size()) {
                throw new ProjectIndexFormatException(
                        "Project index generation lengths do not match CURRENT");
            }
            ProjectIndexBlockCache cache = new ProjectIndexBlockCache(cacheBytes);
            base = LazyProjectIndexBase.open(baseChannel, 0, current.baseLength(), cache);
            if (base.manifest().generation() != current.generation()) {
                throw new ProjectIndexFormatException(
                        "Project index generation does not match CURRENT");
            }
            if (!expected.matches(base.manifest())) {
                base.close();
                baseChannel.close();
                journalChannel.close();
                return ProjectIndexOpenResult.empty(Status.STALE);
            }
            int journalEntries =
                    readJournal(journalChannel, current, expected, cache, overlay);
            return ProjectIndexOpenResult.hit(new ProjectIndexView(base, overlay, cache,
                    baseChannel, journalChannel, basePath, journalPath,
                    current.publicationId(), current.journalLength(), journalEntries));
        } catch (ProjectIndexFormatException ex) {
            releaseFailedOpen(base, overlay, baseChannel, journalChannel);
            return ProjectIndexOpenResult.empty(Status.CORRUPT);
        } catch (IOException ex) {
            // A storage hiccup, an antivirus hold or an interrupted channel is
            // not format damage. The generation stays unusable for this run and
            // the caller still falls back to a full rebuild, but the store must
            // survive so that a later open can reuse it.
            releaseFailedOpen(base, overlay, baseChannel, journalChannel);
            return ProjectIndexOpenResult.empty(Status.RETRYABLE);
        } catch (RuntimeException ex) {
            releaseFailedOpen(base, overlay, baseChannel, journalChannel);
            return ProjectIndexOpenResult.empty(Status.CORRUPT);
        }
    }

    private static void releaseFailedOpen(LazyProjectIndexBase base,
            Map<IndexPathRef, OverlayValue> overlay, FileChannel baseChannel,
            FileChannel journalChannel) {
        if (base != null) {
            base.close();
        }
        overlay.values().forEach(OverlayValue::close);
        closeQuietly(baseChannel);
        closeQuietly(journalChannel);
    }

    public synchronized AppendResult append(ProjectIndexView view, ProjectIndexDelta delta,
            BooleanSupplier cancelled) throws IOException {
        return append(view, delta, cancelled, null);
    }

    public synchronized AppendResult append(ProjectIndexView view,
            ProjectIndexDelta delta, BooleanSupplier cancelled,
            ProjectIndexTelemetry.Run telemetry) throws IOException {
        synchronized (publicationLock) {
            return appendLocked(view, delta, cancelled, telemetry);
        }
    }

    private AppendResult appendLocked(ProjectIndexView view,
            ProjectIndexDelta delta, BooleanSupplier cancelled,
            ProjectIndexTelemetry.Run telemetry)
            throws IOException {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(delta, "delta");
        Objects.requireNonNull(cancelled, "cancelled");
        if (cancelled.getAsBoolean()) {
            return AppendResult.CANCELLED;
        }
        Current current = requireCurrent(view);
        if (!journalAbsorbs(view, delta.changes().size())) {
            PublishOutcome compacted = publishMerged(
                    view, delta, cancelled, telemetry);
            return compacted.status() == PublishResult.PUBLISHED
                    ? AppendResult.APPENDED : AppendResult.CANCELLED;
        }
        Current replacement = appendDirect(
                view, delta, current, cancelled, telemetry);
        return replacement == null
                ? AppendResult.CANCELLED : AppendResult.APPENDED;
    }

    /**
     * Reports whether the committed journal still has room for that many
     * entries. Both append paths ask the same question, so neither can drift
     * into rejecting a batch the other one would have folded.
     */
    private boolean journalAbsorbs(ProjectIndexView view, int entries) {
        return view.committedJournalEntries()
                        <= MAX_JOURNAL_ENTRIES - entries
                && !needsCompaction(view);
    }

    /**
     * Appends exactly one replacement through the atomic batch path.
     */
    public synchronized IncrementalAppendResult appendIncremental(
            ProjectIndexView view, Change replacement,
            BooleanSupplier cancelled) throws IOException {
        return appendIncremental(
                view, replacement, cancelled, null);
    }

    public synchronized IncrementalAppendResult appendIncremental(
            ProjectIndexView view, Change replacement,
            BooleanSupplier cancelled,
            ProjectIndexTelemetry.Run telemetry) throws IOException {
        Objects.requireNonNull(replacement, "replacement");
        return appendIncremental(view,
                new ProjectIndexDelta(java.util.List.of(replacement)),
                cancelled, telemetry);
    }

    /**
     * Publishes a batch of unique replacements as one revision. The batch
     * lands in the journal while the journal has room for it, and is folded
     * into a fresh generation once it has not. Folding streams the packed
     * data that is already on disk, so it stays bounded in memory and never
     * re-analyzes a single project file.
     */
    public synchronized IncrementalAppendResult appendIncremental(
            ProjectIndexView view, ProjectIndexDelta replacements,
            BooleanSupplier cancelled) throws IOException {
        return appendIncremental(
                view, replacements, cancelled, null);
    }

    public synchronized IncrementalAppendResult appendIncremental(
            ProjectIndexView view, ProjectIndexDelta replacements,
            BooleanSupplier cancelled,
            ProjectIndexTelemetry.Run telemetry) throws IOException {
        synchronized (publicationLock) {
            return appendIncrementalLocked(
                    view, replacements, cancelled, telemetry);
        }
    }

    private IncrementalAppendResult appendIncrementalLocked(
            ProjectIndexView view, ProjectIndexDelta replacements,
            BooleanSupplier cancelled,
            ProjectIndexTelemetry.Run telemetry) throws IOException {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(replacements, "replacements");
        Objects.requireNonNull(cancelled, "cancelled");
        if (replacements.changes().size()
                > MAX_INCREMENTAL_BATCH_ENTRIES
                || replacements.changes().stream().anyMatch(
                        change -> change.operation() != Operation.REPLACE)) {
            throw new IllegalArgumentException(
                    "Incremental project-index updates must contain 1 to "
                            + MAX_INCREMENTAL_BATCH_ENTRIES
                            + " unique replacements");
        }
        if (cancelled.getAsBoolean()) {
            return new IncrementalAppendResult(
                    IncrementalAppendStatus.CANCELLED, null);
        }
        Current current = requireCurrent(view);
        ProjectIndexIdentity identity = view.revision().identity();
        if (!journalAbsorbs(view, replacements.changes().size())) {
            return compactIncrementalLocked(
                    view, replacements, cancelled, telemetry);
        }
        Current appended = appendDirect(
                view, replacements, current, cancelled, telemetry);
        if (appended == null) {
            return new IncrementalAppendResult(
                    IncrementalAppendStatus.CANCELLED, null);
        }
        return new IncrementalAppendResult(
                IncrementalAppendStatus.APPENDED,
                new ProjectIndexRevision(appended.publicationId(),
                        appended.journalLength(), appended.generation(),
                        identity));
    }

    /**
     * Folds the batch into a fresh generation once the journal is full.
     *
     * <p>Fail closed: a publication either installs the complete folded
     * generation as CURRENT or leaves the previous CURRENT untouched, so an
     * abandoned fold can only cost a full rebuild, never a partially applied
     * index. A durability failure is the one case that must be propagated,
     * because CURRENT already names the new revision and only the caller
     * knows whether to retire exactly it.
     */
    private IncrementalAppendResult compactIncrementalLocked(
            ProjectIndexView view, ProjectIndexDelta replacements,
            BooleanSupplier cancelled,
            ProjectIndexTelemetry.Run telemetry)
            throws CurrentDurabilityException {
        try {
            PublishOutcome folded = publishMerged(
                    view, replacements, cancelled, telemetry);
            return folded.status() == PublishResult.PUBLISHED
                    ? new IncrementalAppendResult(
                            IncrementalAppendStatus.APPENDED,
                            folded.revision())
                    : new IncrementalAppendResult(
                            IncrementalAppendStatus.CANCELLED, null);
        } catch (CurrentDurabilityException ex) {
            throw ex;
        } catch (IOException ex) {
            return new IncrementalAppendResult(
                    IncrementalAppendStatus.REQUIRES_FULL, null, ex);
        }
    }

    /**
     * Invalidates only the exact revision named by the receipt. Payload files
     * remain available to already acquired readers and for later cleanup.
     */
    public synchronized boolean invalidateCurrent(
            ProjectIndexRevision expected) throws IOException {
        synchronized (publicationLock) {
            return invalidateCurrentLocked(expected);
        }
    }

    private boolean invalidateCurrentLocked(
            ProjectIndexRevision expected) throws IOException {
        Objects.requireNonNull(expected, "expected");
        if (!Files.isRegularFile(currentPath())) {
            return false;
        }
        Current current;
        try {
            current = readCurrent();
        } catch (IOException ex) {
            return Files.deleteIfExists(currentPath());
        }
        if (!current.publicationId().equals(expected.publicationId())
                || current.generation() != expected.generation()
                || current.journalLength()
                        != expected.committedJournalLength()) {
            return false;
        }
        return Files.deleteIfExists(currentPath());
    }

    private Current appendDirect(ProjectIndexView view,
            ProjectIndexDelta delta, Current current,
            BooleanSupplier cancelled,
            ProjectIndexTelemetry.Run telemetry) throws IOException {
        Path journal = journalPath(current.publicationId());
        long committed = current.journalLength();
        long newLength = committed;
        ProjectIndexManifest manifest = view.manifest();
        try (FileChannel channel = FileChannel.open(journal,
                StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            channel.truncate(committed);
            channel.position(committed);
            try {
                for (Change change : delta.changes()) {
                    if (cancelled.getAsBoolean()) {
                        throw new WriteCancelledException();
                    }
                    newLength = appendEntry(channel, change, manifest,
                            current.publicationId(), cancelled,
                            telemetry);
                    hook.at(IoPoint.AFTER_JOURNAL_ENTRY);
                    requireNotCancelled(cancelled);
                }
                hook.at(IoPoint.AFTER_JOURNAL_WRITE);
                if (cancelled.getAsBoolean()) {
                    throw new WriteCancelledException();
                }
                PhaseTimer timer = phase(telemetry, Phase.FSYNC);
                try {
                    channel.force(true);
                } finally {
                    close(timer);
                }
            } catch (WriteCancelledException ex) {
                return null;
            }
        }
        Current replacement = new Current(current.publicationId(), current.generation(),
                current.baseLength(), newLength);
        ProjectIndexRevision replacementRevision =
                new ProjectIndexRevision(
                        replacement.publicationId(),
                        replacement.journalLength(),
                        replacement.generation(),
                        view.revision().identity());
        Path currentTemp = directory.resolve(".current-" + newPublicationId() + ".tmp");
        PhaseTimer currentTimer =
                phase(telemetry, Phase.CURRENT);
        try {
            hook.at(IoPoint.BEFORE_CURRENT_PUBLICATION);
            if (cancelled.getAsBoolean()) {
                return null;
            }
            writeCurrent(currentTemp, replacement);
            if (cancelled.getAsBoolean()) {
                return null;
            }
            publishCurrent(currentTemp, replacement);
            syncDirectory(ProjectIndexDirectorySync.Stage.CURRENT,
                    replacementRevision, telemetry);
            try {
                hook.at(IoPoint.AFTER_CURRENT_PUBLICATION);
            } catch (IOException | RuntimeException ex) {
                // The durable CURRENT already owns this revision.
            }
            return replacement;
        } finally {
            close(currentTimer);
            deleteQuietly(currentTemp);
        }
    }

    public boolean needsCompaction(ProjectIndexView view) {
        Objects.requireNonNull(view, "view");
        return view.committedJournalLength() >= compactionBytes;
    }

    public synchronized PublishResult compact(ProjectIndexView view,
            BooleanSupplier cancelled) throws IOException, ProjectIndexFormatException {
        return compact(view, cancelled, null);
    }

    public synchronized PublishResult compact(ProjectIndexView view,
            BooleanSupplier cancelled,
            ProjectIndexTelemetry.Run telemetry)
            throws IOException, ProjectIndexFormatException {
        synchronized (publicationLock) {
            return compactLocked(view, cancelled, telemetry);
        }
    }

    private PublishResult compactLocked(ProjectIndexView view,
            BooleanSupplier cancelled,
            ProjectIndexTelemetry.Run telemetry)
            throws IOException, ProjectIndexFormatException {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(cancelled, "cancelled");
        if (cancelled.getAsBoolean()) {
            return PublishResult.CANCELLED;
        }
        requireCurrent(view);
        return publishMerged(view, null, cancelled, telemetry).status();
    }

    public synchronized void clean() throws IOException {
        synchronized (publicationLock) {
            cleanLocked();
        }
    }

    private void cleanLocked() throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        Files.deleteIfExists(currentPath());
        hook.at(IoPoint.BEFORE_CLEAN_PAYLOAD_DELETE);
        IOException failure = null;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path path : entries) {
                String name = path.getFileName().toString();
                if (isWriterWorkspaceName(name)
                        || name.startsWith("base-") && name.endsWith(".pgi")
                        || name.startsWith("journal-") && name.endsWith(".pij")
                        || name.startsWith(".base-") && name.endsWith(".tmp")
                        || name.startsWith(".journal-") && name.endsWith(".tmp")
                        || name.startsWith(".current-") && name.endsWith(".tmp")
                        || name.endsWith(".ser")) {
                    try {
                        if (isWriterWorkspaceName(name)) {
                            ProjectIndexSpillStore.cleanupWorkspaceIfInactive(
                                    path,
                                    ProjectIndexStore::deleteWriterWorkspace);
                        } else {
                            Files.deleteIfExists(path);
                        }
                    } catch (IOException ex) {
                        failure = addFailure(failure, ex);
                    }
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    Path currentPathForTests() {
        return currentPath();
    }

    @Override
    public void close() {
        // Views own all open channels; the store itself is stateless.
    }

    private long appendEntry(FileChannel channel, Change change,
            ProjectIndexManifest manifest, String publicationId,
            BooleanSupplier cancelled,
            ProjectIndexTelemetry.Run telemetry) throws IOException {
        requireNotCancelled(cancelled);
        if (change.operation() == Operation.REPLACE) {
            cleanupInactiveWriterWorkspace(publicationId);
            requireNotCancelled(cancelled);
        }
        long entryStart = channel.position();
        writeZeros(channel, JOURNAL_HEADER_BYTES);
        byte[] pathBytes = change.path().relativePath().getBytes(StandardCharsets.UTF_8);
        if (pathBytes.length == 0 || pathBytes.length > MAX_JOURNAL_PATH_BYTES) {
            throw new IOException("Project index journal path exceeds the format limit");
        }
        writeFully(channel, ByteBuffer.wrap(pathBytes));
        requireNotCancelled(cancelled);
        long payloadStart = channel.position();
        long payloadLength = 0;
        if (change.operation() == Operation.REPLACE) {
            ProjectIndexManifest miniManifest = new ProjectIndexManifest(
                    manifest.formatMajor(), manifest.formatMinor(), manifest.parserAbi(),
                    manifest.coreVersion(), manifest.uiVersion(), manifest.databaseType(),
                    manifest.projectIdentity(), manifest.configSha256(), manifest.generation(),
                    java.util.List.of(change.stamp()));
            var region = new ProjectIndexRegionChannel(channel, payloadStart);
            ProjectIndexContainer.write(
                    new CancellableChannel(region, cancelled, hook,
                            IoPoint.JOURNAL_WRITE_CHUNK),
                    new ProjectIndexData(miniManifest,
                            java.util.List.of(change.contribution())),
                    writeContext(publicationId, cancelled,
                            telemetry));
            payloadLength = region.position();
            if (payloadLength <= 0 || payloadLength > MAX_IN_MEMORY_INDEX_BYTES) {
                throw new IOException("Project index journal payload exceeds the format limit");
            }
            channel.position(payloadStart + payloadLength);
        }
        requireNotCancelled(cancelled);
        writeFully(channel, ByteBuffer.wrap(JOURNAL_END_MAGIC));
        long end = channel.position();
        ByteBuffer header = ByteBuffer.allocate(JOURNAL_HEADER_BYTES);
        header.put(JOURNAL_MAGIC);
        header.putInt(CURRENT_VERSION);
        header.put((byte) change.operation().ordinal());
        header.put((byte) change.path().origin().ordinal());
        header.putShort((short) 0);
        header.putInt(pathBytes.length);
        header.putLong(payloadLength);
        header.putLong(manifest.generation());
        header.putInt(crc32c(pathBytes, 0, pathBytes.length));
        ProjectFileStamp stamp = change.stamp();
        header.putLong(stamp == null ? 0 : stamp.eclipseModificationStamp());
        header.putLong(stamp == null ? 0 : stamp.size());
        header.putLong(stamp == null ? 0 : stamp.lastModifiedMillis());
        header.put(stamp == null ? EMPTY_SHA256 : stamp.contentSha256());
        int headerCrc = crc32c(header.array(), 0, JOURNAL_HEADER_BYTES - Integer.BYTES);
        header.putInt(headerCrc).flip();
        writeAt(channel, entryStart, header);
        requireNotCancelled(cancelled);
        channel.position(end);
        return end;
    }

    private void cleanupInactiveWriterWorkspace(String publicationId)
            throws IOException {
        Path workspace = directory.resolve(
                WRITER_WORKSPACE_PREFIX + publicationId + ".tmp");
        if (!ProjectIndexSpillStore.cleanupWorkspaceIfInactive(
                workspace, ProjectIndexStore::deleteWriterWorkspace)) {
            throw new IOException(
                    "Project index writer workspace is active");
        }
    }

    private int readJournal(FileChannel channel, Current current, ProjectIndexIdentity expected,
            ProjectIndexBlockCache cache, Map<IndexPathRef, OverlayValue> overlay)
            throws IOException {
        long position = 0;
        int entries = 0;
        while (position < current.journalLength()) {
            if (++entries > MAX_JOURNAL_ENTRIES) {
                throw new ProjectIndexFormatException(
                        "Project index journal entry count exceeds the hard limit");
            }
            if (current.journalLength() - position < JOURNAL_HEADER_BYTES) {
                throw new ProjectIndexFormatException("Project index journal header is truncated");
            }
            ByteBuffer header = read(channel, position, JOURNAL_HEADER_BYTES);
            byte[] headerBytes = header.array();
            byte[] magic = new byte[JOURNAL_MAGIC.length];
            header.get(magic);
            if (!Arrays.equals(magic, JOURNAL_MAGIC) || header.getInt() != CURRENT_VERSION) {
                throw new ProjectIndexFormatException("Invalid project index journal header");
            }
            int operationOrdinal = Byte.toUnsignedInt(header.get());
            int originOrdinal = Byte.toUnsignedInt(header.get());
            if (header.getShort() != 0
                    || operationOrdinal >= Operation.values().length
                    || originOrdinal >= IndexPathOrigin.values().length) {
                throw new ProjectIndexFormatException("Invalid project index journal flags");
            }
            int pathLength = header.getInt();
            long payloadLength = header.getLong();
            long generation = header.getLong();
            int pathCrc = header.getInt();
            long eclipseStamp = header.getLong();
            long fileSize = header.getLong();
            long lastModified = header.getLong();
            byte[] contentSha256 = new byte[32];
            header.get(contentSha256);
            int expectedHeaderCrc = header.getInt();
            if (pathLength <= 0 || pathLength > MAX_JOURNAL_PATH_BYTES
                    || payloadLength < 0 || payloadLength > MAX_IN_MEMORY_INDEX_BYTES
                    || generation != current.generation()
                    || crc32c(headerBytes, 0, JOURNAL_HEADER_BYTES - Integer.BYTES)
                            != expectedHeaderCrc) {
                throw new ProjectIndexFormatException("Invalid project index journal bounds");
            }
            long pathStart = position + JOURNAL_HEADER_BYTES;
            long payloadStart;
            long entryEnd;
            try {
                payloadStart = Math.addExact(pathStart, pathLength);
                entryEnd = Math.addExact(Math.addExact(payloadStart, payloadLength),
                        JOURNAL_FOOTER_BYTES);
            } catch (ArithmeticException ex) {
                throw new ProjectIndexFormatException("Project index journal range overflows", ex);
            }
            if (entryEnd > current.journalLength()) {
                throw new ProjectIndexFormatException("Project index journal entry is truncated");
            }
            byte[] encodedPath = read(channel, pathStart, pathLength).array();
            if (crc32c(encodedPath, 0, encodedPath.length) != pathCrc) {
                throw new ProjectIndexFormatException(
                        "Project index journal path CRC mismatch");
            }
            String relativePath = decodeUtf8(encodedPath);
            IndexPathRef path;
            try {
                path = new IndexPathRef(IndexPathOrigin.values()[originOrdinal], relativePath);
                if (!relativePath.equals(path.relativePath())) {
                    throw new IllegalArgumentException("non-canonical path");
                }
            } catch (IllegalArgumentException ex) {
                throw new ProjectIndexFormatException(
                        "Project index journal path is invalid", ex);
            }
            ByteBuffer footer = read(channel, entryEnd - JOURNAL_FOOTER_BYTES,
                    JOURNAL_FOOTER_BYTES);
            byte[] footerBytes = new byte[JOURNAL_FOOTER_BYTES];
            footer.get(footerBytes);
            if (!Arrays.equals(footerBytes, JOURNAL_END_MAGIC)) {
                throw new ProjectIndexFormatException("Invalid project index journal footer");
            }
            Operation operation = Operation.values()[operationOrdinal];
            if (operation == Operation.DELETE) {
                if (payloadLength != 0 || eclipseStamp != 0 || fileSize != 0
                        || lastModified != 0
                        || !Arrays.equals(contentSha256, EMPTY_SHA256)) {
                    throw new ProjectIndexFormatException(
                            "Deleted project index journal path has retained data");
                }
                OverlayValue previous = overlay.put(path, OverlayValue.tombstone());
                if (previous != null) {
                    previous.close();
                }
            } else {
                if (payloadLength == 0) {
                    throw new ProjectIndexFormatException(
                            "Project index journal replacement has no payload");
                }
                ProjectFileStamp stamp;
                try {
                    stamp = new ProjectFileStamp(path, eclipseStamp, fileSize,
                            lastModified, contentSha256);
                } catch (IllegalArgumentException ex) {
                    throw new ProjectIndexFormatException(
                            "Project index journal replacement stamp is invalid", ex);
                }
                LazyProjectIndexBase mini = LazyProjectIndexBase.open(
                        channel, payloadStart, payloadLength, cache);
                boolean retained = false;
                try {
                    if (!expected.matches(mini.manifest())
                            || mini.manifest().generation()
                                    != current.generation()
                            || mini.fileCount() != 1) {
                        throw new ProjectIndexFormatException(
                                "Project index journal replacement metadata mismatch");
                    }
                    OverlayValue previous = overlay.put(path,
                            OverlayValue.lazy(stamp, mini));
                    retained = true;
                    if (previous != null) {
                        previous.close();
                    }
                } finally {
                    if (!retained) {
                        mini.close();
                    }
                }
            }
            position = entryEnd;
        }
        if (position != current.journalLength()) {
            throw new ProjectIndexFormatException(
                    "Project index journal committed length is invalid");
        }
        return entries;
    }

    private Current readCurrent() throws IOException {
        if (Files.size(currentPath()) != CURRENT_BYTES) {
            throw new ProjectIndexFormatException("Project index CURRENT has an invalid length");
        }
        byte[] bytes = Files.readAllBytes(currentPath());
        ByteBuffer input = ByteBuffer.wrap(bytes);
        byte[] magic = new byte[CURRENT_MAGIC.length];
        input.get(magic);
        if (!Arrays.equals(magic, CURRENT_MAGIC) || input.getInt() != CURRENT_VERSION) {
            throw new ProjectIndexFormatException("Invalid project index CURRENT header");
        }
        byte[] encodedId = new byte[PUBLICATION_ID_BYTES];
        input.get(encodedId);
        String id = new String(encodedId, StandardCharsets.US_ASCII);
        if (!isPublicationId(id)) {
            throw new ProjectIndexFormatException("Invalid project index publication id");
        }
        long generation = input.getLong();
        long baseLength = input.getLong();
        long journalLength = input.getLong();
        int expectedCrc = input.getInt();
        if (generation < 0 || baseLength <= 0 || journalLength < 0
                || crc32c(bytes, 0, CURRENT_BYTES - Integer.BYTES) != expectedCrc) {
            throw new ProjectIndexFormatException("Invalid project index CURRENT values");
        }
        return new Current(id, generation, baseLength, journalLength);
    }

    private Current readCurrentQuietly() {
        try {
            return Files.isRegularFile(currentPath()) ? readCurrent() : null;
        } catch (IOException ex) {
            return null;
        }
    }

    private void writeCurrent(Path target, Current current) throws IOException {
        byte[] id = current.publicationId().getBytes(StandardCharsets.US_ASCII);
        if (id.length != PUBLICATION_ID_BYTES) {
            throw new IllegalArgumentException("Invalid project index publication id");
        }
        ByteBuffer output = ByteBuffer.allocate(CURRENT_BYTES);
        output.put(CURRENT_MAGIC);
        output.putInt(CURRENT_VERSION);
        output.put(id);
        output.putLong(current.generation());
        output.putLong(current.baseLength());
        output.putLong(current.journalLength());
        output.putInt(crc32c(output.array(), 0, CURRENT_BYTES - Integer.BYTES));
        output.flip();
        try (FileChannel channel = FileChannel.open(target,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            writeFully(channel, output);
            channel.force(true);
        }
    }

    private void cleanupOrphansQuietly(String keepId) {
        try (DirectoryStream<Path> entries =
                Files.newDirectoryStream(directory)) {
            for (Path path : entries) {
                String name = path.getFileName().toString();
                if (isWriterWorkspaceName(name)) {
                    try {
                        ProjectIndexSpillStore.cleanupWorkspaceIfInactive(
                                path,
                                ProjectIndexStore::deleteWriterWorkspace);
                    } catch (IOException | RuntimeException ex) {
                        // A writer may still own the workspace.
                    }
                }
                boolean generationFile = name.startsWith("base-") && name.endsWith(".pgi")
                        || name.startsWith("journal-") && name.endsWith(".pij");
                if (generationFile && !name.contains(keepId)) {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ex) {
                        // An older generation may still be open on Windows.
                    }
                }
            }
        } catch (IOException | RuntimeException ex) {
            // CURRENT is durable; orphan cleanup is best-effort.
        }
    }

    private static void deleteWriterWorkspace(Path workspace)
            throws IOException {
        if (Files.isSymbolicLink(workspace)) {
            Files.deleteIfExists(workspace);
            return;
        }
        if (!Files.isDirectory(workspace,
                LinkOption.NOFOLLOW_LINKS)) {
            return;
        }

        IOException failure = null;
        try (DirectoryStream<Path> entries =
                Files.newDirectoryStream(workspace)) {
            for (Path path : entries) {
                if (Files.isSymbolicLink(path)
                        || Files.isRegularFile(path,
                                LinkOption.NOFOLLOW_LINKS)) {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ex) {
                        failure = addFailure(failure, ex);
                    }
                }
            }
        } catch (IOException ex) {
            failure = addFailure(failure, ex);
        }
        try {
            Files.deleteIfExists(workspace);
        } catch (IOException ex) {
            failure = addFailure(failure, ex);
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static IOException addFailure(IOException failure,
            IOException next) {
        if (failure == null) {
            return next;
        }
        failure.addSuppressed(next);
        return failure;
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException | RuntimeException ex) {
            // Temporary and stale payload cleanup is best-effort.
        }
    }

    private void deleteUnpublishedPayloadIfSafe(String id,
            Path base, Path journal) {
        try {
            Path currentPath = currentPath();
            if (Files.notExists(currentPath)) {
                deleteQuietly(base);
                deleteQuietly(journal);
                return;
            }
            if (!Files.isRegularFile(currentPath)) {
                return;
            }
            Current current;
            try {
                current = readCurrent();
            } catch (IOException | RuntimeException ex) {
                // The move may have succeeded. Retaining an orphan is safer
                // than deleting payload that CURRENT could already reference.
                return;
            }
            if (!current.publicationId().equals(id)) {
                deleteQuietly(base);
                deleteQuietly(journal);
            }
        } catch (RuntimeException ex) {
            // Filesystem state is uncertain. Keep the payload.
        }
    }

    private Path currentPath() {
        return directory.resolve("current");
    }

    private Path basePath(String id) {
        return directory.resolve("base-" + id + ".pgi");
    }

    private Path journalPath(String id) {
        return directory.resolve("journal-" + id + ".pij");
    }

    private static String newPublicationId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static Object[] createPublicationLocks(int count) {
        Object[] locks = new Object[count];
        Arrays.setAll(locks, ignored -> new Object());
        return locks;
    }

    private static boolean isPublicationId(String value) {
        if (value.length() != PUBLICATION_ID_BYTES) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (!(character >= '0' && character <= '9'
                    || character >= 'a' && character <= 'f')) {
                return false;
            }
        }
        return true;
    }

    private static boolean isWriterWorkspaceName(String name) {
        int expectedLength = WRITER_WORKSPACE_PREFIX.length()
                + PUBLICATION_ID_BYTES
                + WRITER_WORKSPACE_SUFFIX.length();
        if (name.length() != expectedLength
                || !name.startsWith(WRITER_WORKSPACE_PREFIX)
                || !name.endsWith(WRITER_WORKSPACE_SUFFIX)) {
            return false;
        }
        return isPublicationId(name.substring(
                WRITER_WORKSPACE_PREFIX.length(),
                name.length() - WRITER_WORKSPACE_SUFFIX.length()));
    }

    @FunctionalInterface
    interface FileMove {
        void move() throws IOException;
    }

    /**
     * Reports whether publication renames should absorb short-lived access
     * denials. Windows antivirus scanners and search indexers open a freshly
     * written file for a few milliseconds, which makes an otherwise valid
     * rename fail with {@link AccessDeniedException}. POSIX systems never need
     * this, so the retry stays disabled there.
     */
    static boolean retriesPublicationRename(String osName) {
        return Objects.requireNonNull(osName, "osName")
                .toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /**
     * Runs a publication rename with a bounded retry. The last attempt keeps
     * the fail-closed contract: its failure is propagated unchanged.
     */
    static void moveWithBoundedRetry(FileMove move, boolean retryable)
            throws IOException {
        for (int attempt = 1;; attempt++) {
            try {
                move.move();
                return;
            } catch (AccessDeniedException ex) {
                if (!retryable || attempt >= PUBLICATION_MOVE_ATTEMPTS) {
                    throw ex;
                }
                awaitPublicationRenameRetry(ex, attempt);
            }
        }
    }

    private static void awaitPublicationRenameRetry(IOException failure,
            int attempt) throws IOException {
        try {
            Thread.sleep(PUBLICATION_MOVE_BACKOFF_MILLIS * attempt);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            failure.addSuppressed(ex);
            throw failure;
        }
    }

    private static void atomicMove(Path source, Path target) throws IOException {
        try {
            moveWithBoundedRetry(() -> Files.move(source, target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING),
                    RETRY_PUBLICATION_RENAME);
        } catch (AtomicMoveNotSupportedException ex) {
            throw new IOException("Atomic project index publication is not supported", ex);
        }
    }

    private void publishCurrent(Path temporary, Current expected)
            throws IOException {
        try {
            atomicMove(temporary, currentPath());
            hook.at(IoPoint.AFTER_CURRENT_MOVE);
        } catch (IOException | RuntimeException ex) {
            Current actual = readCurrentQuietly();
            if (!expected.equals(actual)) {
                throw ex;
            }
        }
    }

    private static ByteBuffer read(FileChannel channel, long position, int length)
            throws IOException {
        ByteBuffer result = ByteBuffer.allocate(length);
        long cursor = position;
        while (result.hasRemaining()) {
            int count = channel.read(result, cursor);
            if (count <= 0) {
                throw new ProjectIndexFormatException(
                        "Unable to make progress while reading project index");
            }
            cursor += count;
        }
        result.flip();
        return result;
    }

    private static void writeZeros(FileChannel channel, int length) throws IOException {
        writeFully(channel, ByteBuffer.wrap(new byte[length]));
    }

    private static void writeAt(FileChannel channel, long position, ByteBuffer source)
            throws IOException {
        long cursor = position;
        while (source.hasRemaining()) {
            int written = channel.write(source, cursor);
            if (written <= 0) {
                throw new IOException("Unable to make progress while writing project index");
            }
            cursor += written;
        }
    }

    private static int crc32c(byte[] value, int offset, int length) {
        CRC32C crc = new CRC32C();
        crc.update(value, offset, length);
        return (int) crc.getValue();
    }

    private static String decodeUtf8(byte[] value) throws ProjectIndexFormatException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value)).toString();
        } catch (CharacterCodingException ex) {
            throw new ProjectIndexFormatException(
                    "Project index journal path is not valid UTF-8", ex);
        }
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ex) {
                // Opening is already failing closed.
            }
        }
    }

    private static void requireNotCancelled(BooleanSupplier cancelled)
            throws WriteCancelledException {
        if (cancelled.getAsBoolean()) {
            throw new WriteCancelledException();
        }
    }

    /**
     * Folds the current generation and a delta into a fresh one. This rewrites
     * the whole index and costs seconds on a large project, with no cause the
     * user of an incremental build can see, so it reports itself as a phase of
     * its own rather than hiding inside the publication.
     */
    private PublishOutcome publishMerged(ProjectIndexView view,
            ProjectIndexDelta delta, BooleanSupplier cancelled,
            ProjectIndexTelemetry.Run telemetry) throws IOException {
        PhaseTimer timer = phase(telemetry, Phase.COMPACT);
        try {
            ProjectIndexReplaySource source =
                    ProjectIndexReplaySource.merged(view, delta, cancelled);
            return publishOutcome(source.manifest(), cancelled, telemetry,
                    (channel, context) -> ProjectIndexContainer.write(
                            channel, source, context));
        } finally {
            close(timer);
        }
    }

    private ProjectIndexWriteContext writeContext(String publicationId,
            BooleanSupplier cancelled,
            ProjectIndexTelemetry.Run telemetry) {
        return new ProjectIndexWriteContext(directory, publicationId,
                cancelled, DEFAULT_TUPLE_BUDGET_BYTES, telemetry);
    }

    private Current requireCurrent(ProjectIndexView view) throws IOException {
        Current current = readCurrent();
        if (!current.publicationId().equals(view.publicationId())
                || current.journalLength() != view.committedJournalLength()) {
            throw new IOException("Project index generation changed");
        }
        return current;
    }

    @FunctionalInterface
    private interface ContainerEncoder {
        long write(SeekableByteChannel channel,
                ProjectIndexWriteContext context) throws IOException;
    }

    private static PhaseTimer phase(
            ProjectIndexTelemetry.Run telemetry, Phase phase) {
        return telemetry == null ? null : telemetry.phase(phase);
    }

    private static void close(PhaseTimer timer) {
        if (timer != null) {
            timer.close();
        }
    }

    private void syncDirectory(ProjectIndexDirectorySync.Stage stage,
            ProjectIndexRevision currentRevision,
            ProjectIndexTelemetry.Run telemetry) throws IOException {
        PhaseTimer timer = phase(telemetry, Phase.FSYNC);
        try {
            directorySync.sync(directory, stage);
        } catch (IOException ex) {
            if (currentRevision != null) {
                throw new CurrentDurabilityException(
                        currentRevision, ex);
            }
            throw new DirectorySyncException(
                    stage, false, ex);
        } finally {
            close(timer);
        }
    }

    private record Current(String publicationId, long generation,
            long baseLength, long journalLength) {
    }

    static final class WriteCancelledException extends IOException {
        private static final long serialVersionUID = 1L;
    }

    static final class DirectorySyncException extends IOException {
        private static final long serialVersionUID = 1L;

        private final ProjectIndexDirectorySync.Stage stage;
        private final boolean currentInstalled;

        DirectorySyncException(ProjectIndexDirectorySync.Stage stage,
                boolean currentInstalled, IOException cause) {
            super("Unable to synchronize project index directory after "
                    + stage.name().toLowerCase(), cause);
            this.stage = Objects.requireNonNull(stage, "stage");
            this.currentInstalled = currentInstalled;
        }

        ProjectIndexDirectorySync.Stage stage() {
            return stage;
        }

        boolean currentInstalled() {
            return currentInstalled;
        }
    }

    /**
     * The exact revision was installed and verified as CURRENT, but forcing
     * the containing directory failed. The revision is visible but must not be
     * reported as durable. Callers can safely reopen, revalidate or invalidate
     * only this revision without damaging a concurrent winner.
     */
    public static final class CurrentDurabilityException
            extends IOException {

        private static final long serialVersionUID = 1L;

        private final ProjectIndexRevision revision;

        CurrentDurabilityException(ProjectIndexRevision revision,
                IOException cause) {
            super("Project index CURRENT directory synchronization failed",
                    cause);
            this.revision = Objects.requireNonNull(
                    revision, "revision");
        }

        public ProjectIndexRevision revision() {
            return revision;
        }
    }

    private static final class CancellableChannel implements SeekableByteChannel {
        private final SeekableByteChannel delegate;
        private final BooleanSupplier cancelled;
        private final IoHook hook;
        private final IoPoint point;

        private CancellableChannel(SeekableByteChannel delegate, BooleanSupplier cancelled,
                IoHook hook, IoPoint point) {
            this.delegate = delegate;
            this.cancelled = cancelled;
            this.hook = hook;
            this.point = point;
        }

        @Override
        public int read(ByteBuffer destination) throws IOException {
            hook.at(IoPoint.LOCATOR_READ_CHUNK);
            requireNotCancelled(cancelled);
            return delegate.read(destination);
        }

        @Override
        public int write(ByteBuffer source) throws IOException {
            if (cancelled.getAsBoolean()) {
                throw new WriteCancelledException();
            }
            hook.at(point);
            return delegate.write(source);
        }

        @Override
        public long position() throws IOException {
            return delegate.position();
        }

        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            delegate.position(newPosition);
            return this;
        }

        @Override
        public long size() throws IOException {
            return delegate.size();
        }

        @Override
        public SeekableByteChannel truncate(long size) throws IOException {
            delegate.truncate(size);
            return this;
        }

        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        @Override
        public void close() {
            // The owner controls the lifecycle of the underlying channel.
        }
    }

}
