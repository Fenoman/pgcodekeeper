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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import java.util.zip.CRC32C;

import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetry.Phase;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetry.PhaseTimer;

final class ProjectIndexContainer {

    static final int HEADER_BYTES = 64;
    private static final byte[] MAGIC = "PGCKPIV2".getBytes(StandardCharsets.US_ASCII);
    private static final int VERSION = 1;

    private ProjectIndexContainer() {
    }

    static long write(SeekableByteChannel target, ProjectIndexData data) throws IOException {
        return writeWithTemporaryContext(target, data.manifest(),
                (codec, context) ->
                    ProjectIndexCodec.writeBase(data, codec, context));
    }

    static long write(SeekableByteChannel target, ProjectIndexData data,
            ProjectIndexWriteContext context) throws IOException {
        return write(target, data.manifest(),
                context,
                codec -> ProjectIndexCodec.writeBase(
                        data, codec, context));
    }

    static long write(SeekableByteChannel target, ProjectIndexReplaySource source)
            throws IOException {
        return writeWithTemporaryContext(target, source.manifest(),
                (codec, context) ->
                    ProjectIndexCodec.writeBase(
                            source, codec, context));
    }

    static long write(SeekableByteChannel target,
            ProjectIndexReplaySource source,
            ProjectIndexWriteContext context) throws IOException {
        return write(target, source.manifest(),
                context,
                codec -> ProjectIndexCodec.writeBase(
                        source, codec, context));
    }

    private static long write(SeekableByteChannel target,
            ProjectIndexManifest manifest,
            ProjectIndexWriteContext context,
            CodecEncoder encoder) throws IOException {
        target.truncate(0);
        target.position(0);
        writeFully(target, ByteBuffer.wrap(new byte[HEADER_BYTES]));

        var codec = new ProjectIndexRegionChannel(target, HEADER_BYTES);
        ProjectIndexBaseWriter.WriteResult written;
        PhaseTimer encodeTimer = phase(context, Phase.ENCODE);
        try {
            written = encoder.write(codec);
        } finally {
            close(encodeTimer);
        }
        long codecLength = codec.position();
        if (codecLength <= 0 || codecLength > MAX_IN_MEMORY_INDEX_BYTES) {
            throw new IOException("Project index codec payload exceeds the container limit");
        }
        if (!sameLocatorManifest(
                written.locatorDraft().manifest(), manifest)
                || written.locatorDraft().codecLength()
                        != codecLength) {
            throw new IOException(
                    "Project index writer returned inconsistent locator metadata");
        }
        ProjectIndexBlockCrcScanner.ScanResult scan;
        PhaseTimer locatorTimer =
                phase(context, Phase.LOCATOR_CRC);
        try {
            scan = ProjectIndexBlockCrcScanner.scan(
                    codec, written.locatorDraft(), context);
        } finally {
            close(locatorTimer);
        }
        byte[] locator = scan.locatorBytes();
        long locatorOffset = HEADER_BYTES + codecLength;
        target.position(locatorOffset);
        writeFully(target, ByteBuffer.wrap(locator));

        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
        header.put(MAGIC);
        header.putInt(VERSION);
        header.putInt(0);
        header.putLong(manifest.generation());
        header.putLong(codecLength);
        header.putLong(locatorOffset);
        header.putInt(locator.length);
        header.putInt(crc32c(locator, 0, locator.length));
        header.putInt(crc32c(header.array(), 0, 48));
        while (header.hasRemaining()) {
            header.put((byte) 0);
        }
        header.flip();
        long end = locatorOffset + locator.length;
        target.position(0);
        writeFully(target, header);
        target.truncate(end);
        target.position(end);
        return end;
    }

    @FunctionalInterface
    private interface CodecEncoder {
        ProjectIndexBaseWriter.WriteResult write(
                SeekableByteChannel channel) throws IOException;
    }

    @FunctionalInterface
    private interface ContextCodecEncoder {
        ProjectIndexBaseWriter.WriteResult write(
                SeekableByteChannel channel,
                ProjectIndexWriteContext context) throws IOException;
    }

    private static long writeWithTemporaryContext(
            SeekableByteChannel target,
            ProjectIndexManifest manifest,
            ContextCodecEncoder encoder) throws IOException {
        Path stateDirectory = Files.createTempDirectory(
                "pgcodekeeper-project-index-container-");
        Throwable failure = null;
        try {
            ProjectIndexWriteContext context =
                    new ProjectIndexWriteContext(stateDirectory,
                            UUID.randomUUID().toString()
                                    .replace("-", ""),
                            () -> false,
                            ProjectIndexStore
                                    .DEFAULT_TUPLE_BUDGET_BYTES,
                            null);
            return write(target, manifest, context,
                    codec -> encoder.write(codec, context));
        } catch (IOException | RuntimeException | Error ex) {
            failure = ex;
            throw ex;
        } finally {
            try {
                Files.deleteIfExists(stateDirectory);
            } catch (IOException | RuntimeException ex) {
                if (failure != null) {
                    failure.addSuppressed(ex);
                } else {
                    throw ex;
                }
            }
        }
    }

    private static PhaseTimer phase(
            ProjectIndexWriteContext context, Phase phase) {
        return context.telemetry() == null ? null
                : context.telemetry().phase(phase);
    }

    private static void close(PhaseTimer timer) {
        if (timer != null) {
            timer.close();
        }
    }

    static Opened open(FileChannel channel, long regionOffset, long regionLength)
            throws IOException {
        if (regionOffset < 0 || regionLength < HEADER_BYTES
                || regionOffset > channel.size() - regionLength) {
            throw new ProjectIndexFormatException(
                    "Project index container range is invalid");
        }
        ByteBuffer header = read(channel, regionOffset, HEADER_BYTES);
        byte[] headerBytes = header.array();
        byte[] magic = new byte[MAGIC.length];
        header.get(magic);
        int version = header.getInt();
        int flags = header.getInt();
        long generation = header.getLong();
        long codecLength = header.getLong();
        long locatorOffset = header.getLong();
        int locatorLength = header.getInt();
        int locatorCrc = header.getInt();
        int headerCrc = header.getInt();
        for (int i = 52; i < HEADER_BYTES; i++) {
            if (headerBytes[i] != 0) {
                throw new ProjectIndexFormatException(
                        "Project index container has nonzero reserved bytes");
            }
        }
        if (!Arrays.equals(magic, MAGIC) || version != VERSION || flags != 0
                || generation < 0 || codecLength <= 0
                || codecLength > MAX_IN_MEMORY_INDEX_BYTES
                || locatorOffset != HEADER_BYTES + codecLength
                || locatorLength <= 0
                || locatorLength > ProjectIndexLocatorBuilder.MAX_LOCATOR_BYTES
                || locatorOffset > regionLength - locatorLength
                || locatorOffset + locatorLength != regionLength
                || crc32c(headerBytes, 0, 48) != headerCrc) {
            throw new ProjectIndexFormatException(
                    "Invalid project index container header");
        }
        ByteBuffer encodedLocator = read(channel, regionOffset + locatorOffset,
                locatorLength);
        byte[] locatorBytes = encodedLocator.array();
        if (crc32c(locatorBytes, 0, locatorBytes.length) != locatorCrc) {
            throw new ProjectIndexFormatException(
                    "Project index locator CRC mismatch");
        }
        ProjectIndexLocator locator = ProjectIndexLocator.parse(locatorBytes);
        locator.validateCodecLength(codecLength);
        if (locator.coreManifest().generation() != generation) {
            throw new ProjectIndexFormatException(
                    "Project index locator does not match its container");
        }
        return new Opened(regionOffset + HEADER_BYTES, codecLength, locator);
    }

    private static ByteBuffer read(FileChannel channel, long position, int length)
            throws IOException {
        ByteBuffer result = ByteBuffer.allocate(length);
        long cursor = position;
        while (result.hasRemaining()) {
            int read = channel.read(result, cursor);
            if (read <= 0) {
                throw new ProjectIndexFormatException(
                        "Unable to make progress while reading project index container");
            }
            cursor += read;
        }
        result.flip();
        return result;
    }

    private static int crc32c(byte[] bytes, int offset, int length) {
        CRC32C crc = new CRC32C();
        crc.update(bytes, offset, length);
        return (int) crc.getValue();
    }

    private static boolean sameLocatorManifest(
            ProjectIndexManifest left, ProjectIndexManifest right) {
        return left.formatMajor() == right.formatMajor()
                && left.formatMinor() == right.formatMinor()
                && left.parserAbi() == right.parserAbi()
                && left.generation() == right.generation()
                && left.coreVersion().equals(right.coreVersion())
                && left.uiVersion().equals(right.uiVersion())
                && left.databaseType() == right.databaseType()
                && left.projectIdentity().equals(
                        right.projectIdentity())
                && Arrays.equals(left.configSha256(),
                        right.configSha256());
    }

    record Opened(long codecOffset, long codecLength, ProjectIndexLocator locator) {
    }
}
