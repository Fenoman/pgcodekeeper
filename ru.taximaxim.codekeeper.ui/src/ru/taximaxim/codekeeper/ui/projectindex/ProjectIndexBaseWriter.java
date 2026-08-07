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
import static ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexRecords.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

final class ProjectIndexBaseWriter {

    private static final ProjectIndexSpillStore.Metrics EMPTY_METRICS =
            new ProjectIndexSpillStore.Metrics(0, 0, 0, 0, 0, 0);

    private ProjectIndexBaseWriter() {
    }

    static WriteResult write(ProjectIndexData source,
            SeekableByteChannel channel) throws IOException {
        return writeWithTemporaryContext(ProjectIndexContributionSource.from(
                Objects.requireNonNull(source, "source")), channel);
    }

    static void write(ProjectIndexData source, SeekableByteChannel channel, int maxSectionBytes)
            throws IOException {
        // Legacy boxed encoder retained as a format-2 byte oracle for tests.
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(channel, "channel");
        Prepared prepared = prepare(source, maxSectionBytes);
        CanonicalData data = prepared.data();
        AuxiliaryIndexes indexes = prepared.indexes();
        StringTable strings = prepared.strings();

        channel.truncate(0);
        channel.position(0);
        writeFully(channel, ByteBuffer.wrap(new byte[HEADER_SIZE]));

        List<DirectoryEntry> entries = new ArrayList<>(SectionType.values().length);
        for (SectionType type : SectionType.values()) {
            entries.add(writeSection(channel, type, output -> {
                switch (type) {
                case MANIFEST -> writeManifest(output, data.manifest(), strings);
                case STRINGS -> writeStrings(output, strings);
                case FILES -> writeFiles(output, data.stamps(), strings);
                case DEFINITIONS -> writeDefinitions(output, data.definitions(), strings);
                case LOCATIONS -> writeLocations(output, data.locations(), strings);
                case BY_PATH -> writeByPath(output, data.ranges(), strings);
                case BY_MATCH_KEY -> writeByMatchKey(output, indexes.byMatchKey(), strings);
                case COMPLETION_TRIGRAMS -> writeCompletion(output, indexes.completion(), strings);
                case REVERSE_DEPENDENCIES -> writeReverseDependencies(
                        output, indexes.reverseDependencies(), strings);
                case UNRESOLVED_FILES -> writeUnresolved(output, data.files(), strings);
                }
            }, prepared.sectionSizes().get(type), maxSectionBytes));
        }

        finish(channel, data.manifest().generation(), entries);
    }

    static WriteResult write(ProjectIndexReplaySource source,
            SeekableByteChannel channel)
            throws IOException {
        return writeWithTemporaryContext(ProjectIndexContributionSource.from(
                Objects.requireNonNull(source, "source")), channel);
    }

    static WriteResult write(ProjectIndexData source,
            SeekableByteChannel channel, ProjectIndexWriteContext context)
            throws IOException {
        return write(ProjectIndexContributionSource.from(
                Objects.requireNonNull(source, "source")),
                channel, context);
    }

    static WriteResult write(
            ProjectIndexReplaySource source, SeekableByteChannel channel,
            ProjectIndexWriteContext context) throws IOException {
        return write(ProjectIndexContributionSource.from(
                Objects.requireNonNull(source, "source")),
                channel, context);
    }

    static ProjectIndexSpillStore.Metrics writeBoundedForTests(
            ProjectIndexData source, SeekableByteChannel channel,
            Path stateDirectory, String publicationId,
            long tupleBudgetBytes) throws IOException {
        return write(source, channel, new ProjectIndexWriteContext(
                stateDirectory, publicationId, () -> false,
                tupleBudgetBytes, null)).metrics();
    }

    static long encodedSize(ProjectIndexData source) {
        return prepare(Objects.requireNonNull(source, "source"), MAX_SECTION_BYTES).totalSize();
    }

    private static WriteResult write(
            ProjectIndexContributionSource source,
            SeekableByteChannel channel, ProjectIndexWriteContext context)
            throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(context, "context");
        ProjectIndexBoundedAuxiliaryWriter.Prepared prepared =
                ProjectIndexBoundedAuxiliaryWriter.prepare(source, context);
        ProjectIndexManifest manifest = prepared.manifest();
        StringTable strings = prepared.strings();
        var capture = new ProjectIndexLocatorCapture(manifest);

        channel.truncate(0);
        channel.position(0);
        writeFully(channel, ByteBuffer.wrap(new byte[HEADER_SIZE]));

        List<DirectoryEntry> entries =
                new ArrayList<>(SectionType.values().length);
        ProjectIndexSpillStore.Metrics aggregate = EMPTY_METRICS;
        for (SectionType type : SectionType.values()) {
            context.requireNotCancelled();
            long payloadOffset =
                    channel.position() + SECTION_FRAME_SIZE;
            ProjectIndexSpillStore.Metrics[] sectionMetrics =
                    {EMPTY_METRICS};
            DirectoryEntry entry = writeSection(channel, type, output -> {
                switch (type) {
                case MANIFEST ->
                    writeManifest(output, manifest, strings);
                case STRINGS ->
                    writeStrings(output, strings,
                            capture.beginBlocks(type,
                                    strings.values().size(),
                                    payloadOffset));
                case FILES ->
                    writeFiles(output, manifest.files(), strings,
                            capture);
                case DEFINITIONS ->
                    writeStreamingDefinitions(
                            output, source, prepared, context,
                            capture.beginBlocks(type,
                                    prepared.counts().definitions(),
                                    payloadOffset));
                case LOCATIONS ->
                    writeStreamingLocations(
                            output, source, prepared, context,
                            capture.beginBlocks(type,
                                    prepared.counts().locations(),
                                    payloadOffset));
                case BY_PATH ->
                    writeStreamingByPath(
                            output, source, prepared, context,
                            capture);
                case BY_MATCH_KEY, COMPLETION_TRIGRAMS,
                        REVERSE_DEPENDENCIES ->
                    sectionMetrics[0] =
                            ProjectIndexBoundedAuxiliaryWriter.writeSection(
                                    type, output, source, prepared,
                                    context, capture, payloadOffset);
                case UNRESOLVED_FILES ->
                    writeStreamingUnresolved(
                            output, source, prepared, context,
                            capture.beginSparse(type,
                                    prepared.counts()
                                            .unresolvedFiles(),
                                    payloadOffset));
                }
            }, MAX_SECTION_BYTES);
            entries.add(entry);
            capture.section(entry);
            if (isAuxiliary(type)) {
                aggregate = aggregateSequential(
                        aggregate, sectionMetrics[0]);
            }
        }

        context.requireNotCancelled();
        finish(channel, manifest.generation(), entries);
        long packedBytes = channel.size();
        context.recordWriterMetrics(aggregate, packedBytes);
        return new WriteResult(aggregate,
                capture.finish(packedBytes));
    }

    private static WriteResult writeWithTemporaryContext(
            ProjectIndexContributionSource source,
            SeekableByteChannel channel) throws IOException {
        Objects.requireNonNull(channel, "channel");
        Path stateDirectory = Files.createTempDirectory(
                "pgcodekeeper-project-index-");
        Throwable failure = null;
        try {
            return write(source, channel,
                    new ProjectIndexWriteContext(
                            stateDirectory,
                            UUID.randomUUID().toString().replace("-", ""),
                            () -> false,
                            ProjectIndexStore.DEFAULT_TUPLE_BUDGET_BYTES,
                            null));
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

    private static boolean isAuxiliary(SectionType type) {
        return type == SectionType.BY_MATCH_KEY
                || type == SectionType.COMPLETION_TRIGRAMS
                || type == SectionType.REVERSE_DEPENDENCIES;
    }

    private static ProjectIndexSpillStore.Metrics aggregateSequential(
            ProjectIndexSpillStore.Metrics left,
            ProjectIndexSpillStore.Metrics right) {
        return new ProjectIndexSpillStore.Metrics(
                Math.addExact(left.materializedRuns(),
                        right.materializedRuns()),
                Math.addExact(left.physicalSpillBytes(),
                        right.physicalSpillBytes()),
                Math.max(left.peakTrackedBufferBytes(),
                        right.peakTrackedBufferBytes()),
                Math.max(left.peakTrackedResidentBytes(),
                        right.peakTrackedResidentBytes()),
                Math.max(left.peakOpenRuns(), right.peakOpenRuns()),
                Math.max(left.peakRegisteredRunDescriptors(),
                        right.peakRegisteredRunDescriptors()));
    }

    private static Prepared prepare(ProjectIndexData source, int maxSectionBytes) {
        if (maxSectionBytes <= 0 || maxSectionBytes > MAX_SECTION_BYTES) {
            throw new IllegalArgumentException("Invalid project index section size limit");
        }
        if (source.manifest().formatMajor() != FORMAT_MAJOR
                || source.manifest().formatMinor() != FORMAT_MINOR) {
            throw new IllegalArgumentException("Project index manifest must use format 2.0");
        }

        validateSourceWriteLimits(source);
        CanonicalData data = canonicalize(source);
        validateCanonicalWriteLimits(data);
        ProspectiveWork prospective = preflightProspective(data, maxSectionBytes);
        AuxiliaryIndexes indexes = AuxiliaryIndexes.build(data);
        StringTable strings = StringTable.build(prospective, indexes);
        validateAuxiliaryWriteLimits(indexes, strings);
        validateRecordWriteLimits(data, strings);

        EnumMap<SectionType, Long> sectionSizes = new EnumMap<>(SectionType.class);
        long totalSize = HEADER_SIZE;
        try {
            for (SectionType type : SectionType.values()) {
                long payloadSize = sectionPayloadSize(type, data, indexes, strings);
                if (payloadSize > maxSectionBytes) {
                    throw new IllegalArgumentException(type
                            + " project index section exceeds the size limit");
                }
                sectionSizes.put(type, payloadSize);
                totalSize = Math.addExact(totalSize,
                        Math.addExact(SECTION_FRAME_SIZE, payloadSize));
            }
            long directorySize = DIRECTORY_MAGIC.length
                    + (long) SectionType.values().length * DIRECTORY_ENTRY_SIZE + Integer.BYTES;
            totalSize = Math.addExact(totalSize, Math.addExact(directorySize, FOOTER_SIZE));
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("Project index encoded size overflows", ex);
        }
        return new Prepared(data, indexes, strings, sectionSizes, totalSize);
    }

    private static DirectoryEntry writeSection(SeekableByteChannel channel, SectionType type,
            SectionEncoder encoder, long expectedSize, int maxSectionBytes) throws IOException {
        return writeSection(channel, type, encoder, expectedSize,
                maxSectionBytes, true);
    }

    private static DirectoryEntry writeSection(SeekableByteChannel channel, SectionType type,
            SectionEncoder encoder, int maxSectionBytes) throws IOException {
        return writeSection(channel, type, encoder, 0, maxSectionBytes, false);
    }

    private static DirectoryEntry writeSection(SeekableByteChannel channel, SectionType type,
            SectionEncoder encoder, long expectedSize, int maxSectionBytes,
            boolean verifySize) throws IOException {
        long offset = channel.position();
        writeFully(channel, ByteBuffer.wrap(new byte[SECTION_FRAME_SIZE]));

        ChannelWriter output = new ChannelWriter(channel, true, maxSectionBytes);
        encoder.write(output);
        output.finish();
        if (verifySize && output.size() != expectedSize) {
            throw new IllegalStateException(type
                    + " project index section size mismatch: expected "
                    + expectedSize + ", wrote " + output.size());
        }
        int length = Math.toIntExact(output.size());
        int crc = output.crc();

        BinaryWriter frame = new BinaryWriter();
        frame.writeByte(type.id());
        frame.writeByte(0);
        frame.writeShort(0);
        frame.writeInt(length);
        frame.writeInt(crc);
        writeAt(channel, offset, frame.bytes());
        return new DirectoryEntry(type, 0, offset, length, crc, -1);
    }

    private static void finish(SeekableByteChannel channel, long generation,
            List<DirectoryEntry> entries) throws IOException {
        long directoryOffset = channel.position();
        BinaryWriter directory = new BinaryWriter();
        directory.writeBytes(DIRECTORY_MAGIC);
        for (DirectoryEntry entry : entries) {
            directory.writeByte(entry.type().id());
            directory.writeByte(entry.flags());
            directory.writeShort(0);
            directory.writeLong(entry.offset());
            directory.writeInt(entry.payloadLength());
            directory.writeInt(entry.payloadCrc());
        }
        int directoryCrc = crc32c(directory.bytes(), 0, directory.size());
        directory.writeInt(directoryCrc);
        byte[] directoryBytes = directory.bytes();
        writeFully(channel, ByteBuffer.wrap(directoryBytes));

        BinaryWriter footer = new BinaryWriter();
        footer.writeBytes(FOOTER_MAGIC);
        footer.writeLong(directoryOffset);
        int footerCrc = crc32c(footer.bytes(), 0, footer.size());
        footer.writeInt(footerCrc);
        writeFully(channel, ByteBuffer.wrap(footer.bytes()));

        BinaryWriter header = new BinaryWriter();
        header.writeBytes(HEADER_MAGIC);
        header.writeShort(FORMAT_MAJOR);
        header.writeShort(FORMAT_MINOR);
        header.writeLong(generation);
        header.writeLong(directoryOffset);
        header.writeInt(directoryBytes.length);
        header.writeInt(entries.size());
        header.writeInt(directoryCrc);
        int headerCrc = crc32c(header.bytes(), 0, header.size());
        header.writeInt(headerCrc);
        writeAt(channel, 0, header.bytes());
        channel.truncate(channel.position());
    }

    @FunctionalInterface
    private interface SectionEncoder {
        void write(ChannelWriter output) throws IOException;
    }

    private record Prepared(
            CanonicalData data,
            AuxiliaryIndexes indexes,
            StringTable strings,
            EnumMap<SectionType, Long> sectionSizes,
            long totalSize) {
    }

    record WriteResult(ProjectIndexSpillStore.Metrics metrics,
            ProjectIndexLocatorDraft locatorDraft) {

        WriteResult {
            Objects.requireNonNull(metrics, "metrics");
            Objects.requireNonNull(locatorDraft, "locatorDraft");
        }
    }
}
