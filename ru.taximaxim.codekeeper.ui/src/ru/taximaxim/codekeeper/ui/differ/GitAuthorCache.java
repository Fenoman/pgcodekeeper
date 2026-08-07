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
package ru.taximaxim.codekeeper.ui.differ;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.CheckedOutputStream;

import ru.taximaxim.codekeeper.ui.Activator;

/**
 * Bounded persistent cache for committed Git authors. Local working-tree and
 * index status deliberately stays outside this cache.
 */
final class GitAuthorCache {

    static final int MAX_ENTRIES = 100_000;
    static final long MAX_FILE_BYTES = 32L << 20;
    static final int MAX_MEMORY_REPOSITORIES = 4;

    private static final int MAGIC = 0x50474341;
    private static final int VERSION = 1;
    private static final int HEADER_BYTES =
            3 * Integer.BYTES + Long.BYTES;
    private static final int MAX_STRING_BYTES = 1 << 20;
    private static final int PUBLICATION_MOVE_ATTEMPTS = 3;
    private static final long PUBLICATION_MOVE_BACKOFF_MILLIS = 10L;
    private static final boolean RETRY_PUBLICATION_RENAME =
            retriesPublicationRename(
                    System.getProperty("os.name", "")); //$NON-NLS-1$ //$NON-NLS-2$
    private static final int IO_LOCK_COUNT = 32;
    private static final Object[] IO_LOCKS = new Object[IO_LOCK_COUNT];
    private static final Object WORKSPACE_LOCK = new Object();
    private static volatile GitAuthorCache workspaceCache;

    static {
        for (int i = 0; i < IO_LOCKS.length; i++) {
            IO_LOCKS[i] = new Object();
        }
    }

    private final Path cacheDirectory;
    private final int maxEntries;
    private final long maxFileBytes;
    private final int maxMemoryRepositories;
    private final LinkedHashMap<String, Snapshot> memory =
            new LinkedHashMap<>(8, 0.75F, true);

    GitAuthorCache(Path cacheDirectory) {
        this(cacheDirectory, MAX_ENTRIES, MAX_FILE_BYTES,
                MAX_MEMORY_REPOSITORIES);
    }

    GitAuthorCache(Path cacheDirectory, int maxEntries, long maxFileBytes,
            int maxMemoryRepositories) {
        this.cacheDirectory = Objects.requireNonNull(cacheDirectory,
                "cacheDirectory").toAbsolutePath().normalize(); //$NON-NLS-1$
        if (maxEntries < 1) {
            throw new IllegalArgumentException(
                    "maxEntries must be positive"); //$NON-NLS-1$
        }
        if (maxFileBytes <= HEADER_BYTES) {
            throw new IllegalArgumentException(
                    "maxFileBytes is too small"); //$NON-NLS-1$
        }
        if (maxMemoryRepositories < 1) {
            throw new IllegalArgumentException(
                    "maxMemoryRepositories must be positive"); //$NON-NLS-1$
        }
        this.maxEntries = maxEntries;
        this.maxFileBytes = maxFileBytes;
        this.maxMemoryRepositories = maxMemoryRepositories;
    }

    static GitAuthorCache workspaceCache() {
        try {
            Activator plugin = Activator.getDefault();
            if (plugin == null) {
                return null;
            }
            Path directory = Path.of(plugin.getStateLocation().toOSString())
                    .resolve("git-author-cache") //$NON-NLS-1$
                    .toAbsolutePath().normalize();
            GitAuthorCache current = workspaceCache;
            if (current != null
                    && current.cacheDirectory.equals(directory)) {
                return current;
            }
            synchronized (WORKSPACE_LOCK) {
                current = workspaceCache;
                if (current == null
                        || !current.cacheDirectory.equals(directory)) {
                    current = new GitAuthorCache(directory);
                    workspaceCache = current;
                }
                return current;
            }
        } catch (RuntimeException ex) {
            return null;
        }
    }

    Lookup lookup(Path gitDirectory, String head, Set<String> paths) {
        Objects.requireNonNull(gitDirectory, "gitDirectory"); //$NON-NLS-1$
        Objects.requireNonNull(head, "head"); //$NON-NLS-1$
        Objects.requireNonNull(paths, "paths"); //$NON-NLS-1$
        if (paths.isEmpty()) {
            return Lookup.EMPTY;
        }

        String repository;
        try {
            repository = canonicalRepository(gitDirectory);
        } catch (IOException | RuntimeException ex) {
            return Lookup.EMPTY;
        }

        Object ioLock = ioLock(repository);
        synchronized (ioLock) {
            Snapshot snapshot = memorySnapshot(repository);
            boolean memoryHit = snapshot != null;
            boolean diskHit = false;
            if (snapshot == null) {
                snapshot = read(cacheFile(repository), repository);
                if (snapshot != null) {
                    remember(repository, snapshot);
                    diskHit = true;
                }
            }
            if (snapshot == null || !head.equals(snapshot.head())) {
                return new Lookup(Map.of(), memoryHit, diskHit, false);
            }

            Map<String, String> hits = new LinkedHashMap<>();
            for (String path : paths) {
                String author = snapshot.authors().get(path);
                if (author != null) {
                    hits.put(path, author);
                }
            }
            return new Lookup(Collections.unmodifiableMap(hits),
                    memoryHit, diskHit, true);
        }
    }

    boolean store(Path gitDirectory, String head,
            Map<String, String> authors) {
        Objects.requireNonNull(gitDirectory, "gitDirectory"); //$NON-NLS-1$
        Objects.requireNonNull(head, "head"); //$NON-NLS-1$
        Objects.requireNonNull(authors, "authors"); //$NON-NLS-1$
        if (authors.isEmpty()) {
            return false;
        }

        String repository;
        try {
            repository = canonicalRepository(gitDirectory);
        } catch (IOException | RuntimeException ex) {
            return false;
        }

        Object ioLock = ioLock(repository);
        synchronized (ioLock) {
            Snapshot previous = memorySnapshot(repository);
            if (previous == null) {
                previous = read(cacheFile(repository), repository);
            }

            Map<String, String> merged = new LinkedHashMap<>();
            authors.forEach((path, author) -> {
                if (path != null && !path.isEmpty()
                        && author != null && !author.isEmpty()) {
                    merged.put(path, author);
                }
            });
            if (previous != null && head.equals(previous.head())) {
                previous.authors().forEach(merged::putIfAbsent);
            }
            Snapshot bounded = boundedSnapshot(repository, head, merged);
            if (bounded.authors().isEmpty()
                    || !write(cacheFile(repository), bounded)) {
                return false;
            }
            remember(repository, bounded);
            return true;
        }
    }

    Path cacheFile(Path gitDirectory) {
        try {
            return cacheFile(canonicalRepository(gitDirectory));
        } catch (IOException ex) {
            return cacheFile(gitDirectory.toAbsolutePath().normalize()
                    .toString());
        }
    }

    synchronized int inMemoryRepositoryCount() {
        return memory.size();
    }

    private Snapshot boundedSnapshot(String repository, String head,
            Map<String, String> authors) {
        long repositoryBytes = serializedStringBytes(repository);
        long headBytes = serializedStringBytes(head);
        if (repositoryBytes < 0 || headBytes < 0) {
            return new Snapshot(repository, head, Map.of());
        }
        long payloadBytes = repositoryBytes + headBytes + Integer.BYTES;
        long maxPayloadBytes = maxFileBytes - HEADER_BYTES;
        if (payloadBytes > maxPayloadBytes) {
            return new Snapshot(repository, head, Map.of());
        }
        Map<String, String> bounded = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : authors.entrySet()) {
            if (bounded.size() == maxEntries) {
                break;
            }
            long pathBytes = serializedStringBytes(entry.getKey());
            long authorBytes = serializedStringBytes(entry.getValue());
            if (pathBytes < 0 || authorBytes < 0
                    || payloadBytes + pathBytes + authorBytes
                            > maxPayloadBytes) {
                continue;
            }
            bounded.put(entry.getKey(), entry.getValue());
            payloadBytes += pathBytes + authorBytes;
        }
        return new Snapshot(repository, head,
                Collections.unmodifiableMap(bounded));
    }

    private Snapshot read(Path file, String expectedRepository) {
        try {
            long fileSize = Files.size(file);
            if (fileSize < HEADER_BYTES || fileSize > maxFileBytes) {
                return null;
            }
            try (InputStream raw = new BufferedInputStream(
                    Files.newInputStream(file));
                    DataInputStream header = new DataInputStream(raw)) {
                if (header.readInt() != MAGIC || header.readInt() != VERSION) {
                    return null;
                }
                int payloadLength = header.readInt();
                long expectedChecksum = header.readLong();
                if (payloadLength < 0
                        || payloadLength != fileSize - HEADER_BYTES) {
                    return null;
                }

                var limited = new LimitedInputStream(raw, payloadLength);
                var checksum = new CRC32();
                var checked = new CheckedInputStream(limited, checksum);
                var input = new DataInputStream(checked);
                String repository = readString(input, limited);
                String head = readString(input, limited);
                int count = input.readInt();
                if (!expectedRepository.equals(repository)
                        || count < 0 || count > maxEntries) {
                    return null;
                }
                Map<String, String> authors = new LinkedHashMap<>(Math.min(
                        count + count / 3 + 1, maxEntries));
                for (int i = 0; i < count; i++) {
                    String path = readString(input, limited);
                    String author = readString(input, limited);
                    if (path.isEmpty() || author.isEmpty()
                            || authors.put(path, author) != null) {
                        return null;
                    }
                }
                if (limited.remaining() != 0
                        || checksum.getValue() != expectedChecksum
                        || raw.read() != -1) {
                    return null;
                }
                return new Snapshot(repository, head,
                        Collections.unmodifiableMap(authors));
            }
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    private boolean write(Path target, Snapshot snapshot) {
        Path temporary = null;
        try {
            Files.createDirectories(cacheDirectory);
            temporary = Files.createTempFile(cacheDirectory,
                    target.getFileName().toString(), ".tmp"); //$NON-NLS-1$
            var checksum = new CRC32();
            try (OutputStream raw = new BufferedOutputStream(
                    Files.newOutputStream(temporary,
                            StandardOpenOption.TRUNCATE_EXISTING));
                    DataOutputStream output = new DataOutputStream(raw)) {
                output.writeInt(MAGIC);
                output.writeInt(VERSION);
                output.writeInt(0);
                output.writeLong(0);
                output.flush();

                var checked = new CheckedOutputStream(raw, checksum);
                var payload = new DataOutputStream(checked);
                writeString(payload, snapshot.repository());
                writeString(payload, snapshot.head());
                payload.writeInt(snapshot.authors().size());
                for (Map.Entry<String, String> entry
                        : snapshot.authors().entrySet()) {
                    writeString(payload, entry.getKey());
                    writeString(payload, entry.getValue());
                }
                payload.flush();
            }

            long size = Files.size(temporary);
            if (size > maxFileBytes || size - HEADER_BYTES > Integer.MAX_VALUE) {
                return false;
            }
            try (var channel = Files.newByteChannel(temporary,
                    StandardOpenOption.WRITE)) {
                var header = ByteBuffer.allocate(HEADER_BYTES)
                        .putInt(MAGIC)
                        .putInt(VERSION)
                        .putInt((int) (size - HEADER_BYTES))
                        .putLong(checksum.getValue())
                        .flip();
                while (header.hasRemaining()) {
                    channel.write(header);
                }
            }

            Path source = temporary;
            try {
                moveWithBoundedRetry(() -> Files.move(source, target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING));
            } catch (AtomicMoveNotSupportedException ex) {
                moveWithBoundedRetry(() -> Files.move(source, target,
                        StandardCopyOption.REPLACE_EXISTING));
            }
            temporary = null;
            return true;
        } catch (IOException | RuntimeException ex) {
            return false;
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ex) {
                    // A stale temporary file is never used for lookup.
                }
            }
        }
    }

    @FunctionalInterface
    private interface FileMove {
        void move() throws IOException;
    }

    /**
     * Reports whether cache publication renames should absorb short-lived
     * access denials. Windows antivirus scanners and search indexers open a
     * freshly written file for a few milliseconds, which makes an otherwise
     * valid rename fail with {@link AccessDeniedException}.
     */
    static boolean retriesPublicationRename(String osName) {
        return Objects.requireNonNull(osName, "osName") //$NON-NLS-1$
                .toLowerCase(Locale.ROOT).startsWith("windows"); //$NON-NLS-1$
    }

    /**
     * Runs a cache publication rename with a bounded retry. The failure of the
     * last attempt is propagated unchanged, so the caller still discards the
     * cache file instead of publishing a partial one.
     */
    private static void moveWithBoundedRetry(FileMove move) throws IOException {
        for (int attempt = 1;; attempt++) {
            try {
                move.move();
                return;
            } catch (AccessDeniedException ex) {
                if (!RETRY_PUBLICATION_RENAME
                        || attempt >= PUBLICATION_MOVE_ATTEMPTS) {
                    throw ex;
                }
                try {
                    Thread.sleep(PUBLICATION_MOVE_BACKOFF_MILLIS * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    ex.addSuppressed(interrupted);
                    throw ex;
                }
            }
        }
    }

    private synchronized Snapshot memorySnapshot(String repository) {
        return memory.get(repository);
    }

    private synchronized void remember(String repository, Snapshot snapshot) {
        memory.put(repository, snapshot);
        while (memory.size() > maxMemoryRepositories) {
            String eldest = memory.keySet().iterator().next();
            memory.remove(eldest);
        }
    }

    private Path cacheFile(String canonicalRepository) {
        return cacheDirectory.resolve(sha256(canonicalRepository) + ".bin"); //$NON-NLS-1$
    }

    private static String canonicalRepository(Path gitDirectory)
            throws IOException {
        return gitDirectory.toRealPath().toString();
    }

    private static Object ioLock(String repository) {
        return IO_LOCKS[(repository.hashCode() & Integer.MAX_VALUE)
                % IO_LOCKS.length];
    }

    private static long serializedStringBytes(String value) {
        int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        return bytes > MAX_STRING_BYTES ? -1 : Integer.BYTES + (long) bytes;
    }

    private static void writeString(DataOutputStream output, String value)
            throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            throw new IOException("Git author cache string is too large"); //$NON-NLS-1$
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input,
            LimitedInputStream limited) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES
                || length > limited.remaining()) {
            throw new IOException("Invalid Git author cache string"); //$NON-NLS-1$
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new IOException("Truncated Git author cache string"); //$NON-NLS-1$
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException ex) {
            throw new IOException("Invalid UTF-8 in Git author cache", ex); //$NON-NLS-1$
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest
                    .getInstance("SHA-256") //$NON-NLS-1$
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex); //$NON-NLS-1$
        }
    }

    record Lookup(
            Map<String, String> authors,
            boolean memoryHit,
            boolean diskHit,
            boolean snapshotMatched) {

        private static final Lookup EMPTY =
                new Lookup(Map.of(), false, false, false);
    }

    private record Snapshot(
            String repository,
            String head,
            Map<String, String> authors) {
    }

    private static final class LimitedInputStream extends FilterInputStream {

        private long remaining;

        private LimitedInputStream(InputStream input, long remaining) {
            super(input);
            this.remaining = remaining;
        }

        long remaining() {
            return remaining;
        }

        @Override
        public int read() throws IOException {
            if (remaining == 0) {
                return -1;
            }
            int value = super.read();
            if (value != -1) {
                remaining--;
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length)
                throws IOException {
            if (remaining == 0) {
                return -1;
            }
            int read = super.read(bytes, offset,
                    (int) Math.min(length, remaining));
            if (read > 0) {
                remaining -= read;
            }
            return read;
        }
    }
}
