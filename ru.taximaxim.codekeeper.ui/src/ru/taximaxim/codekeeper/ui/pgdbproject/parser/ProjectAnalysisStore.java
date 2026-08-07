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
package ru.taximaxim.codekeeper.ui.pgdbproject.parser;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.zip.CRC32C;

import org.pgcodekeeper.core.analysis.AnalysisReplayCodec;
import org.pgcodekeeper.core.analysis.AnalysisReplayFormatException;
import org.pgcodekeeper.core.analysis.AnalysisReplayPayload;

import ru.taximaxim.codekeeper.ui.Log;
import ru.taximaxim.codekeeper.ui.projectindex.IndexPathOrigin;
import ru.taximaxim.codekeeper.ui.projectindex.IndexPathRef;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectFileStamp;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexPathResolver;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexWarmValidator.CurrentFile;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexWarmValidator.Result;
import ru.taximaxim.codekeeper.ui.pgdbproject.parser.ProjectComparisonProfile.EffectiveVersion;

/**
 * Persistent store of one project's analyzed-model result.
 * <p>
 * The store holds a single self-describing file per project, published by an
 * atomic rename onto its final name. A concurrent workspace or a second editor
 * therefore either sees the previous complete file or the new complete one, and
 * never a mixture; there is no journal to replay because an analysis result is
 * only ever replaced wholesale.
 * <p>
 * The file is a bounded identity section - the semantic profile digest and the
 * stamp of every input file - followed by the payload container that Core
 * encodes. Both regions carry their own CRC32C, and reading classifies a
 * failure as either {@link Status#CORRUPT}, meaning the bytes are provably
 * wrong and the file may be discarded, or {@link Status#RETRYABLE}, meaning
 * only that this attempt could not read them. A transient storage error never
 * costs the user a rebuild.
 */
final class ProjectAnalysisStore {

    /** Root directory name, versioned so a format change starts a new tree. */
    static final String DIRECTORY = "analysis-v1"; //$NON-NLS-1$

    private static final String FILE_NAME = "analysis.pga"; //$NON-NLS-1$
    private static final String TEMP_PREFIX = ".analysis-"; //$NON-NLS-1$
    private static final String TEMP_SUFFIX = ".tmp"; //$NON-NLS-1$

    private static final long MAGIC = 0x5047434B41535431L; // PGCKAST1
    private static final int STORE_VERSION = 1;
    private static final int MAX_FILE_STAMPS = 1 << 22;
    private static final int MAX_TEXT_BYTES = 1 << 16;
    private static final int STREAM_BUFFER_BYTES = 1 << 20;

    /** Outcome of one attempt to serve a persisted analysis result. */
    enum Status {
        /** The file describes exactly this project under these settings. */
        HIT,
        /** No file has been published for this project yet. */
        MISS,
        /** A file exists but describes different settings or different inputs. */
        STALE,
        /** The bytes are provably damaged; the file may be discarded. */
        CORRUPT,
        /** The file could not be read this time; it must be kept as it is. */
        RETRYABLE
    }

    /**
     * Result of {@link #open}.
     *
     * @param status              classification of this attempt
     * @param payload             analysis result, only on {@link Status#HIT}
     * @param storedProfileDigest digest the stored result was written under
     * @param validation          warm validation outcome, when validation ran
     * @param sizeBytes           size of the file read, zero when absent
     * @param inspectedFiles      inputs enumerated, zero when the settings
     *                            already ruled the stored result out
     */
    record OpenResult(Status status, AnalysisReplayPayload payload,
            String storedProfileDigest, Result validation, long sizeBytes,
            long inspectedFiles) {

        static OpenResult of(Status status) {
            return new OpenResult(status, null, null, null, 0, 0);
        }
    }

    /** Current input files of the project, enumerated on demand. */
    @FunctionalInterface
    interface CurrentInputs {

        Inputs enumerate() throws IOException, InterruptedException;
    }

    /**
     * @param files    every input file with its stamp
     * @param resolver resolves an input file to its absolute path
     */
    record Inputs(List<CurrentFile> files, ProjectIndexPathResolver resolver) {

        Inputs {
            Objects.requireNonNull(files, "files"); //$NON-NLS-1$
            Objects.requireNonNull(resolver, "resolver"); //$NON-NLS-1$
        }
    }

    private final Path directory;

    ProjectAnalysisStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory") //$NON-NLS-1$
                .toAbsolutePath().normalize();
    }

    Path file() {
        return directory.resolve(FILE_NAME);
    }

    /**
     * Reads the persisted analysis result if it still describes this project.
     * <p>
     * This method never throws on a damaged or unreadable store: an uncertain
     * store must degrade into a cold run, not into a failed comparison.
     *
     * @param expectedDigest given the effective database version the stored
     *                      result was produced under, the digest the current
     *                      settings produce, or empty when they produce no
     *                      reusable profile at all
     * @param inputs        enumerates the current input files, evaluated only
     *                      once the stored settings match
     * @param cancelled     cancellation probe of this run
     * @return classification, with the payload only when it is a hit
     * @throws InterruptedException if the run was cancelled
     */
    OpenResult open(Function<EffectiveVersion, Optional<String>> expectedDigest,
            CurrentInputs inputs, BooleanSupplier cancelled)
            throws InterruptedException {
        Objects.requireNonNull(expectedDigest, "expectedDigest"); //$NON-NLS-1$
        Objects.requireNonNull(inputs, "inputs"); //$NON-NLS-1$
        Objects.requireNonNull(cancelled, "cancelled"); //$NON-NLS-1$
        Path path = file();
        long size;
        try {
            size = Files.size(path);
        } catch (NoSuchFileException ex) {
            return OpenResult.of(Status.MISS);
        } catch (IOException ex) {
            Log.log(Log.LOG_INFO, "Analysis cache size is unavailable", ex); //$NON-NLS-1$
            return OpenResult.of(Status.RETRYABLE);
        }
        try (InputStream raw = Files.newInputStream(path);
                InputStream input = new BufferedInputStream(raw, STREAM_BUFFER_BYTES)) {
            Header header = readHeader(input);
            Optional<String> expected = expectedDigest.apply(header.version());
            if (expected.isEmpty()
                    || !expected.orElseThrow().equals(header.profileDigest())) {
                return new OpenResult(
                        Status.STALE, null, null, null, size, 0);
            }
            Inputs current;
            Result validation;
            try {
                current = inputs.enumerate();
                var snapshot = ProjectComparisonInputSnapshot.of(header.files());
                validation = snapshot.validate(
                        current.files(), current.resolver(), cancelled);
            } catch (IOException | RuntimeException ex) {
                // The project, not the store, could not be read. Nothing here
                // says the stored bytes are wrong, so this run goes cold and
                // the store stays for the next one.
                Log.log(Log.LOG_INFO,
                        "Project inputs could not be validated", ex); //$NON-NLS-1$
                return new OpenResult(
                        Status.RETRYABLE, null, null, null, size, 0);
            }
            if (!validation.hit()) {
                return new OpenResult(Status.STALE, null, null, validation,
                        size, current.files().size());
            }
            AnalysisReplayPayload payload = AnalysisReplayCodec.read(input);
            if (input.read() != -1) {
                throw new AnalysisReplayFormatException(
                        "Analysis cache has bytes after its payload"); //$NON-NLS-1$
            }
            return new OpenResult(Status.HIT, payload,
                    header.profileDigest(), validation, size,
                    current.files().size());
        } catch (NoSuchFileException ex) {
            // The store was removed between sizing it and opening it.
            return OpenResult.of(Status.MISS);
        } catch (AnalysisReplayFormatException | ProjectAnalysisFormatException ex) {
            Log.log(Log.LOG_WARNING, "Analysis cache is damaged", ex); //$NON-NLS-1$
            return new OpenResult(Status.CORRUPT, null, null, null, size, 0);
        } catch (IOException ex) {
            Log.log(Log.LOG_INFO, "Analysis cache could not be read", ex); //$NON-NLS-1$
            return new OpenResult(Status.RETRYABLE, null, null, null, size, 0);
        } catch (RuntimeException ex) {
            // A decoder that trips on its own invariants proves the bytes are
            // not what this format describes, exactly like a format exception.
            Log.log(Log.LOG_WARNING, "Analysis cache decoding failed", ex); //$NON-NLS-1$
            return new OpenResult(Status.CORRUPT, null, null, null, size, 0);
        }
    }

    /**
     * Publishes an analysis result, replacing whatever was stored before.
     *
     * @param profileDigest digest of the settings the result was produced under
     * @param version       effective database version of that profile
     * @param files         stamps of the inputs the result was produced from
     * @param payload       analysis result to persist
     * @return number of bytes published
     * @throws IOException if the result could not be written or published
     */
    long publish(String profileDigest, EffectiveVersion version,
            List<ProjectFileStamp> files,
            AnalysisReplayPayload payload) throws IOException {
        Objects.requireNonNull(profileDigest, "profileDigest"); //$NON-NLS-1$
        Objects.requireNonNull(version, "version"); //$NON-NLS-1$
        Objects.requireNonNull(files, "files"); //$NON-NLS-1$
        Objects.requireNonNull(payload, "payload"); //$NON-NLS-1$
        Files.createDirectories(directory);
        Path temp = directory.resolve(
                TEMP_PREFIX + UUID.randomUUID() + TEMP_SUFFIX);
        boolean published = false;
        try {
            long size;
            try (FileChannel channel = FileChannel.open(temp,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                // The buffered stream is flushed but deliberately not closed:
                // closing it would close the channel this method still needs to
                // size and to force.
                OutputStream output = new BufferedOutputStream(
                        Channels.newOutputStream(channel), STREAM_BUFFER_BYTES);
                writeHeader(output, profileDigest, version, files);
                AnalysisReplayCodec.write(payload, output);
                output.flush();
                size = channel.size();
                // The rename below only publishes bytes that already reached
                // the device; otherwise a crash could leave a valid name over
                // an empty file.
                channel.force(true);
            }
            move(temp, file());
            published = true;
            syncDirectory();
            return size;
        } finally {
            if (!published) {
                deleteQuietly(temp);
            }
        }
    }

    /**
     * Deletes a store whose bytes were proven wrong. Never called for a store
     * that merely could not be read.
     */
    void discard() {
        deleteQuietly(file());
    }

    private void syncDirectory() {
        // Directory metadata durability is a POSIX concern; on Windows the
        // directory cannot be opened for reading and the rename is durable.
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | RuntimeException ex) {
            Log.log(Log.LOG_INFO,
                    "Analysis cache directory was not synced", ex); //$NON-NLS-1$
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            throw new IOException(
                    "Analysis cache cannot be published atomically", ex); //$NON-NLS-1$
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException | RuntimeException ex) {
            Log.log(Log.LOG_INFO, "Analysis cache file was not removed", ex); //$NON-NLS-1$
        }
    }

    private static void writeHeader(OutputStream output, String profileDigest,
            EffectiveVersion version, List<ProjectFileStamp> files)
            throws IOException {
        var body = new ByteArrayOutputStream();
        try (var data = new DataOutputStream(body)) {
            data.writeUTF(profileDigest);
            data.writeInt(version.value());
            data.writeUTF(version.text());
            data.writeUTF(version.implementationClass());
            data.writeInt(files.size());
            for (ProjectFileStamp stamp : files) {
                data.writeUTF(stamp.path().origin().name());
                data.writeUTF(stamp.path().relativePath());
                data.writeLong(stamp.eclipseModificationStamp());
                data.writeLong(stamp.size());
                data.writeLong(stamp.lastModifiedMillis());
                data.write(stamp.contentSha256());
            }
        }
        byte[] bytes = body.toByteArray();
        var crc = new CRC32C();
        crc.update(bytes, 0, bytes.length);
        var header = new DataOutputStream(output);
        header.writeLong(MAGIC);
        header.writeInt(STORE_VERSION);
        header.writeInt(bytes.length);
        header.writeInt((int) crc.getValue());
        header.flush();
        output.write(bytes);
    }

    private static Header readHeader(InputStream input)
            throws IOException, ProjectAnalysisFormatException {
        var head = new DataInputStream(input);
        long magic;
        try {
            magic = head.readLong();
        } catch (EOFException ex) {
            throw new ProjectAnalysisFormatException(
                    "Analysis cache is shorter than its header", ex); //$NON-NLS-1$
        }
        if (magic != MAGIC) {
            throw new ProjectAnalysisFormatException(
                    "Analysis cache has a foreign magic"); //$NON-NLS-1$
        }
        int version = head.readInt();
        if (version != STORE_VERSION) {
            throw new ProjectAnalysisFormatException(
                    "Analysis cache store version " + version //$NON-NLS-1$
                            + " is not supported"); //$NON-NLS-1$
        }
        int length = head.readInt();
        if (length < 0 || length > MAX_FILE_STAMPS * 512L) {
            throw new ProjectAnalysisFormatException(
                    "Analysis cache declares an unusable header length"); //$NON-NLS-1$
        }
        int expectedCrc = head.readInt();
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new ProjectAnalysisFormatException(
                    "Analysis cache header is truncated"); //$NON-NLS-1$
        }
        var crc = new CRC32C();
        crc.update(bytes, 0, bytes.length);
        if ((int) crc.getValue() != expectedCrc) {
            throw new ProjectAnalysisFormatException(
                    "Analysis cache header fails its checksum"); //$NON-NLS-1$
        }
        return decodeHeader(bytes);
    }

    private static Header decodeHeader(byte[] bytes)
            throws IOException, ProjectAnalysisFormatException {
        try (var data = new DataInputStream(
                new ByteArrayInputStream(bytes))) {
            String digest = readText(data);
            var version = new EffectiveVersion(data.readInt(),
                    readText(data), readText(data));
            int count = data.readInt();
            if (count < 0 || count > MAX_FILE_STAMPS) {
                throw new ProjectAnalysisFormatException(
                        "Analysis cache declares an unusable file count: " + count); //$NON-NLS-1$
            }
            var files = new ArrayList<ProjectFileStamp>(count);
            for (int i = 0; i < count; i++) {
                IndexPathOrigin origin = readOrigin(readText(data));
                String relativePath = readText(data);
                long eclipseStamp = data.readLong();
                long size = data.readLong();
                long lastModified = data.readLong();
                var sha256 = new byte[32];
                data.readFully(sha256);
                files.add(new ProjectFileStamp(
                        new IndexPathRef(origin, relativePath),
                        eclipseStamp, size, lastModified, sha256));
            }
            if (data.read() != -1) {
                throw new ProjectAnalysisFormatException(
                        "Analysis cache header has trailing bytes"); //$NON-NLS-1$
            }
            return new Header(digest, version, files);
        } catch (EOFException ex) {
            throw new ProjectAnalysisFormatException(
                    "Analysis cache header is truncated", ex); //$NON-NLS-1$
        } catch (IllegalArgumentException ex) {
            throw new ProjectAnalysisFormatException(
                    "Analysis cache header holds an unusable value", ex); //$NON-NLS-1$
        }
    }

    private static String readText(DataInputStream data)
            throws IOException, ProjectAnalysisFormatException {
        String value = data.readUTF();
        if (value.length() > MAX_TEXT_BYTES) {
            throw new ProjectAnalysisFormatException(
                    "Analysis cache header holds an oversized value"); //$NON-NLS-1$
        }
        return value;
    }

    private static IndexPathOrigin readOrigin(String name)
            throws ProjectAnalysisFormatException {
        try {
            return IndexPathOrigin.valueOf(name);
        } catch (IllegalArgumentException ex) {
            throw new ProjectAnalysisFormatException(
                    "Analysis cache holds an unknown path origin: " + name, ex); //$NON-NLS-1$
        }
    }

    private record Header(String profileDigest, EffectiveVersion version,
            List<ProjectFileStamp> files) {
    }

    /** Signals bytes that provably do not match this store format. */
    static final class ProjectAnalysisFormatException extends IOException {

        private static final long serialVersionUID = 1L;

        ProjectAnalysisFormatException(String message) {
            super(message);
        }

        ProjectAnalysisFormatException(String message, Throwable cause) {
            super(message, cause);
        }
    }

}
