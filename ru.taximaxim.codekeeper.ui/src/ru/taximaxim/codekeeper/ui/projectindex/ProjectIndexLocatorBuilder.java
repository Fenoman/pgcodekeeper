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

import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32C;

import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexFormat.AllocationBudget;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexFormat.BinaryReader;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexFormat.BinaryWriter;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexFormat.DirectoryEntry;

final class ProjectIndexLocatorBuilder {

    static final byte[] LOCATOR_MAGIC = "PGCKLOC2".getBytes(StandardCharsets.US_ASCII);
    static final int LOCATOR_VERSION = 1;
    static final int SPARSE_STRIDE = 64;
    static final int MAX_LOCATOR_BYTES = 16 << 20;
    static final int FILE_ROW_BYTES = 80;
    static final int BLOCK_ROW_BYTES = 20;
    static final int MATCH_SPARSE_ROW_BYTES = 40;
    static final int STRING_SPARSE_ROW_BYTES = 16;
    static final int PATH_SPARSE_ROW_BYTES = 20;

    private ProjectIndexLocatorBuilder() {
    }

    static byte[] build(SeekableByteChannel codec, ProjectIndexManifest manifest)
            throws ProjectIndexFormatException {
        AllocationBudget budget = new AllocationBudget(2L << 20);
        ProjectIndexSource source = ProjectIndexSource.of(codec, budget,
                size -> new byte[size]);
        Layout layout = inspect(source);
        if (layout.generation() != manifest.generation()) {
            throw format("Project index manifest and codec generation differ");
        }
        int[] codecCrcs = validatePayloadsAndBuildBlockCrcs(source,
                layout.entries());
        BlockDirectory strings = scanBlocks(source,
                layout.entries().get(SectionType.STRINGS), MAX_STRING_COUNT);
        BlockDirectory definitions = scanBlocks(source,
                layout.entries().get(SectionType.DEFINITIONS), MAX_RECORD_COUNT);
        BlockDirectory locations = scanBlocks(source,
                layout.entries().get(SectionType.LOCATIONS), MAX_RECORD_COUNT);
        List<FileRow> files = readFiles(source, layout.entries().get(SectionType.FILES),
                strings.recordCount());
        readRanges(source, layout.entries().get(SectionType.BY_PATH),
                strings.recordCount(), definitions.recordCount(), locations.recordCount(), files);
        SparseTable match = readMatchSparse(source,
                layout.entries().get(SectionType.BY_MATCH_KEY), strings.recordCount(),
                definitions.recordCount(), locations.recordCount());
        SparseTable completion = readCompletionSparse(source,
                layout.entries().get(SectionType.COMPLETION_TRIGRAMS),
                strings.recordCount(), definitions.recordCount());
        SparseTable reverse = readReverseSparse(source,
                layout.entries().get(SectionType.REVERSE_DEPENDENCIES), strings.recordCount());
        SparseTable unresolved = readUnresolvedSparse(source,
                layout.entries().get(SectionType.UNRESOLVED_FILES), strings.recordCount());

        return serialize(new ProjectIndexLocatorDraft(manifest,
                source.size(), new ArrayList<>(layout.entries().values()),
                strings, definitions, locations, files,
                match, completion, reverse, unresolved), codecCrcs);
    }

    static byte[] serialize(ProjectIndexLocatorDraft draft,
            int[] codecCrcs) {
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(codecCrcs, "codecCrcs");
        ProjectIndexManifest manifest = draft.manifest();
        BinaryWriter output = new BinaryWriter(MAX_LOCATOR_BYTES);
        output.writeBytes(LOCATOR_MAGIC);
        output.writeInt(LOCATOR_VERSION);
        output.writeInt(manifest.parserAbi());
        output.writeInt(manifest.databaseType().ordinal());
        output.writeLong(manifest.generation());
        output.writeBytes(manifest.configSha256());
        writeUtf8(output, manifest.coreVersion());
        writeUtf8(output, manifest.uiVersion());
        writeUtf8(output, manifest.projectIdentity());

        output.writeInt(codecCrcs.length);
        for (int crc : codecCrcs) {
            output.writeInt(crc);
        }
        writeBlocks(output, draft.strings());
        writeBlocks(output, draft.definitions());
        writeBlocks(output, draft.locations());
        output.writeInt(draft.files().size());
        for (FileRow file : draft.files()) {
            output.writeInt(file.origin());
            output.writeInt(file.pathId());
            output.writeLong(file.eclipseStamp());
            output.writeLong(file.size());
            output.writeLong(file.modified());
            output.writeBytes(file.sha256());
            output.writeInt(file.definitionStart());
            output.writeInt(file.definitionCount());
            output.writeInt(file.locationStart());
            output.writeInt(file.locationCount());
        }
        writeSparse(output, draft.match());
        writeSparse(output, draft.completion());
        writeSparse(output, draft.reverse());
        writeSparse(output, draft.unresolved());
        return output.bytes();
    }

    private static Layout inspect(ProjectIndexSource source) throws ProjectIndexFormatException {
        if (source.size() < HEADER_SIZE) {
            throw format("Project index has a truncated header");
        }
        BinaryReader header = new BinaryReader(source, 0, HEADER_SIZE, "header", null);
        header.requireMagic(HEADER_MAGIC, "header magic");
        int major = header.readUnsignedShort();
        int minor = header.readUnsignedShort();
        long generation = header.readLong();
        long directoryOffset = header.readLong();
        long directoryLengthLong = header.readUnsignedInt();
        long sectionCountLong = header.readUnsignedInt();
        int directoryCrc = header.readInt();
        int headerCrc = header.readInt();
        if (major != FORMAT_MAJOR || minor != FORMAT_MINOR || generation < 0
                || source.crc32c(0, HEADER_SIZE - Integer.BYTES) != headerCrc
                || sectionCountLong != SectionType.values().length) {
            throw format("Invalid project index header");
        }
        int sectionCount = (int) sectionCountLong;
        int directoryLength = Math.toIntExact(directoryLengthLong);
        long expectedDirectoryLength = DIRECTORY_MAGIC.length
                + (long) sectionCount * DIRECTORY_ENTRY_SIZE + Integer.BYTES;
        if (directoryLengthLong != expectedDirectoryLength
                || directoryOffset < HEADER_SIZE
                || directoryOffset + directoryLength + FOOTER_SIZE != source.size()) {
            throw format("Invalid project index directory range");
        }
        BinaryReader directory = new BinaryReader(source, directoryOffset,
                directoryLength, "directory", null);
        directory.requireMagic(DIRECTORY_MAGIC, "directory magic");
        EnumMap<SectionType, DirectoryEntry> entries = new EnumMap<>(SectionType.class);
        for (int i = 0; i < sectionCount; i++) {
            long position = directory.absolutePosition();
            SectionType type;
            try {
                type = SectionType.fromId(directory.readUnsignedByte());
            } catch (IllegalArgumentException ex) {
                throw format("Unknown project index section", ex);
            }
            int flags = directory.readUnsignedByte();
            int reserved = directory.readUnsignedShort();
            long offset = directory.readLong();
            long length = directory.readUnsignedInt();
            int crc = directory.readInt();
            if (flags != 0 || reserved != 0 || length > MAX_SECTION_BYTES
                    || entries.put(type, new DirectoryEntry(type, flags, offset,
                            (int) length, crc, position)) != null) {
                throw format("Invalid or duplicate project index section");
            }
        }
        int embeddedCrc = directory.readInt();
        directory.requireEnd();
        if (embeddedCrc != directoryCrc
                || source.crc32c(directoryOffset,
                        directoryLength - Integer.BYTES) != directoryCrc
                || entries.size() != SectionType.values().length) {
            throw format("Project index directory CRC mismatch");
        }
        BinaryReader footer = new BinaryReader(source,
                source.size() - FOOTER_SIZE, FOOTER_SIZE,
                "footer", null);
        footer.requireMagic(FOOTER_MAGIC, "footer magic");
        if (footer.readLong() != directoryOffset) {
            throw format("Project index footer directory offset mismatch");
        }
        int footerCrc = footer.readInt();
        footer.requireEnd();
        if (source.crc32c(source.size() - FOOTER_SIZE,
                FOOTER_SIZE - Integer.BYTES) != footerCrc) {
            throw format("Project index footer CRC mismatch");
        }

        List<DirectoryEntry> sorted = new ArrayList<>(entries.values());
        sorted.sort(Comparator.comparingLong(DirectoryEntry::offset));
        long end = HEADER_SIZE;
        for (DirectoryEntry entry : sorted) {
            if (entry.offset() != end) {
                throw format("Project index sections overlap or contain a gap");
            }
            BinaryReader frame = new BinaryReader(source, entry.offset(),
                    SECTION_FRAME_SIZE, entry.type() + " frame", null);
            if (frame.readUnsignedByte() != entry.type().id()
                    || frame.readUnsignedByte() != 0
                    || frame.readUnsignedShort() != 0
                    || frame.readUnsignedInt()
                            != Integer.toUnsignedLong(entry.payloadLength())
                    || frame.readInt() != entry.payloadCrc()) {
                throw format("Invalid project index section frame");
            }
            end = entry.offset() + SECTION_FRAME_SIZE + entry.payloadLength();
        }
        if (end != directoryOffset) {
            throw format("Project index contains a gap before its directory");
        }
        return new Layout(generation, entries);
    }

    private static int[] validatePayloadsAndBuildBlockCrcs(
            ProjectIndexSource source,
            EnumMap<SectionType, DirectoryEntry> entries)
            throws ProjectIndexFormatException {
        int blockCount = Math.toIntExact((source.size()
                + ProjectIndexBlockCache.BLOCK_BYTES - 1)
                / ProjectIndexBlockCache.BLOCK_BYTES);
        int[] result = new int[blockCount];
        byte[] buffer = new byte[ProjectIndexBlockCache.BLOCK_BYTES];
        EnumMap<SectionType, CRC32C> sectionCrcs =
                new EnumMap<>(SectionType.class);
        entries.keySet().forEach(type -> sectionCrcs.put(type, new CRC32C()));
        for (int block = 0; block < blockCount; block++) {
            long blockStart =
                    (long) block * ProjectIndexBlockCache.BLOCK_BYTES;
            int length = (int) Math.min(ProjectIndexBlockCache.BLOCK_BYTES,
                    source.size() - blockStart);
            source.readFully(blockStart, buffer, 0, length);
            CRC32C blockCrc = new CRC32C();
            blockCrc.update(buffer, 0, length);
            result[block] = (int) blockCrc.getValue();
            long blockEnd = blockStart + length;
            for (DirectoryEntry entry : entries.values()) {
                long payloadStart = entry.offset() + SECTION_FRAME_SIZE;
                long payloadEnd = payloadStart + entry.payloadLength();
                long overlapStart = Math.max(blockStart, payloadStart);
                long overlapEnd = Math.min(blockEnd, payloadEnd);
                if (overlapStart < overlapEnd) {
                    sectionCrcs.get(entry.type()).update(buffer,
                            Math.toIntExact(overlapStart - blockStart),
                            Math.toIntExact(overlapEnd - overlapStart));
                }
            }
        }
        for (DirectoryEntry entry : entries.values()) {
            if ((int) sectionCrcs.get(entry.type()).getValue()
                    != entry.payloadCrc()) {
                throw format(entry.type()
                        + " section payload CRC mismatch");
            }
        }
        return result;
    }

    private static BlockDirectory scanBlocks(ProjectIndexSource source,
            DirectoryEntry entry, int maximumRecords) throws ProjectIndexFormatException {
        BinaryReader input = payload(source, entry);
        int recordCount = input.readCount("record", maximumRecords, 0);
        List<BlockRow> blocks = new ArrayList<>();
        int records = 0;
        while (records < recordCount) {
            int blockLength = input.readVarInt();
            if (blockLength == 0 || blockLength > MAX_RECORD_BYTES + 5) {
                throw format(entry.type() + " raw block exceeds the size limit");
            }
            long blockOffset = input.absolutePosition();
            BinaryReader block = input.slice(blockLength, entry.type() + " block");
            int blockRecords = 0;
            while (block.hasRemaining()) {
                int length = block.readVarInt();
                if (length > MAX_RECORD_BYTES) {
                    throw format(entry.type() + " record exceeds the size limit");
                }
                block.slice(length, entry.type() + " record");
                blockRecords++;
                records++;
                if (records > recordCount) {
                    throw format(entry.type() + " has too many records");
                }
            }
            if (blockLength > MAX_RAW_BLOCK_BYTES && blockRecords != 1) {
                throw format(entry.type()
                        + " oversized block must contain exactly one record");
            }
            blocks.add(new BlockRow(records - blockRecords, blockRecords,
                    blockOffset, blockLength));
        }
        int declaredBlocks = input.readCount("block", maximumRecords, 0);
        input.requireEnd();
        if (declaredBlocks != blocks.size()
                || (recordCount == 0) != blocks.isEmpty()) {
            throw format(entry.type() + " block count mismatch");
        }
        return new BlockDirectory(recordCount, List.copyOf(blocks));
    }

    private static List<FileRow> readFiles(ProjectIndexSource source,
            DirectoryEntry entry, int stringCount) throws ProjectIndexFormatException {
        BinaryReader input = payload(source, entry);
        int count = input.readCount("file", MAX_RECORD_COUNT, 0);
        List<FileRow> files = new ArrayList<>(count);
        RawPath previous = null;
        for (int i = 0; i < count; i++) {
            RawPath path = readPath(input, stringCount);
            requireOrdered(previous, path, "FILES paths");
            previous = path;
            long stamp = input.readLong();
            long size = input.readLong();
            long modified = input.readLong();
            byte[] sha = input.readBytes(32);
            if (size < 0) {
                throw format("FILES contains a negative file size");
            }
            files.add(new FileRow(path.origin(), path.pathId(), stamp, size,
                    modified, sha, 0, 0, 0, 0));
        }
        input.requireEnd();
        return files;
    }

    private static void readRanges(ProjectIndexSource source, DirectoryEntry entry,
            int stringCount, int definitionCount, int locationCount, List<FileRow> files)
            throws ProjectIndexFormatException {
        BinaryReader input = payload(source, entry);
        int count = input.readCount("path range", MAX_RECORD_COUNT, 0);
        if (count != files.size()) {
            throw format("FILES and BY_PATH counts differ");
        }
        int nextDefinition = 0;
        int nextLocation = 0;
        RawPath previous = null;
        for (int i = 0; i < count; i++) {
            RawPath path = readPath(input, stringCount);
            requireOrdered(previous, path, "BY_PATH paths");
            previous = path;
            int definitionStart = input.readVarInt();
            int definitions = input.readVarInt();
            int locationStart = input.readVarInt();
            int locations = input.readVarInt();
            FileRow file = files.get(i);
            if (file.origin() != path.origin() || file.pathId() != path.pathId()
                    || definitionStart != nextDefinition || locationStart != nextLocation
                    || (long) definitionStart + definitions > definitionCount
                    || (long) locationStart + locations > locationCount) {
                throw format("FILES and BY_PATH are inconsistent");
            }
            files.set(i, file.withRanges(definitionStart, definitions,
                    locationStart, locations));
            nextDefinition += definitions;
            nextLocation += locations;
        }
        input.requireEnd();
        if (nextDefinition != definitionCount || nextLocation != locationCount) {
            throw format("BY_PATH does not cover every record");
        }
    }

    private static SparseTable readMatchSparse(ProjectIndexSource source,
            DirectoryEntry entry, int stringCount, int definitionCount, int locationCount)
            throws ProjectIndexFormatException {
        BinaryReader input = payload(source, entry);
        int count = input.readCount("match key", MAX_RECORD_COUNT, 0);
        List<SparseRow> sparse = new ArrayList<>((count + SPARSE_STRIDE - 1) / SPARSE_STRIDE);
        RawMatchKey previous = null;
        for (int i = 0; i < count; i++) {
            long offset = input.absolutePosition();
            RawMatchKey key = readMatchKey(input, stringCount);
            requireOrdered(previous, key, "BY_MATCH_KEY keys");
            previous = key;
            if (i % SPARSE_STRIDE == 0) {
                sparse.add(SparseRow.match(i, offset, key));
            }
            skipIds(input, definitionCount, "definition");
            skipIds(input, locationCount, "location");
        }
        input.requireEnd();
        return SparseTable.match(entry, count, sparse);
    }

    private static SparseTable readCompletionSparse(ProjectIndexSource source,
            DirectoryEntry entry, int stringCount, int definitionCount)
            throws ProjectIndexFormatException {
        BinaryReader input = payload(source, entry);
        int count = input.readCount("completion trigram", MAX_RECORD_COUNT, 0);
        List<SparseRow> sparse = new ArrayList<>((count + SPARSE_STRIDE - 1) / SPARSE_STRIDE);
        int previous = 0;
        for (int i = 0; i < count; i++) {
            long offset = input.absolutePosition();
            int stringId = readStringId(input, stringCount);
            if (stringId <= previous) {
                throw format("COMPLETION_TRIGRAMS keys are not strictly sorted");
            }
            previous = stringId;
            if (i % SPARSE_STRIDE == 0) {
                sparse.add(SparseRow.string(i, offset, stringId));
            }
            skipIds(input, definitionCount, "definition");
        }
        input.requireEnd();
        return SparseTable.string(entry, count, sparse);
    }

    private static SparseTable readReverseSparse(ProjectIndexSource source,
            DirectoryEntry entry, int stringCount) throws ProjectIndexFormatException {
        BinaryReader input = payload(source, entry);
        int count = input.readCount("reverse dependency key", MAX_RECORD_COUNT, 0);
        List<SparseRow> sparse = new ArrayList<>((count + SPARSE_STRIDE - 1) / SPARSE_STRIDE);
        RawMatchKey previous = null;
        for (int i = 0; i < count; i++) {
            long offset = input.absolutePosition();
            RawMatchKey key = readMatchKey(input, stringCount);
            requireOrdered(previous, key, "REVERSE_DEPENDENCIES keys");
            previous = key;
            if (i % SPARSE_STRIDE == 0) {
                sparse.add(SparseRow.match(i, offset, key));
            }
            int paths = input.readCount("dependent path", MAX_RECORD_COUNT, 0);
            RawPath previousPath = null;
            for (int j = 0; j < paths; j++) {
                RawPath path = readPath(input, stringCount);
                requireOrdered(previousPath, path, "REVERSE_DEPENDENCIES paths");
                previousPath = path;
            }
        }
        input.requireEnd();
        return SparseTable.match(entry, count, sparse);
    }

    private static SparseTable readUnresolvedSparse(ProjectIndexSource source,
            DirectoryEntry entry, int stringCount) throws ProjectIndexFormatException {
        BinaryReader input = payload(source, entry);
        int count = input.readCount("unresolved file", MAX_RECORD_COUNT, 0);
        List<SparseRow> sparse = new ArrayList<>((count + SPARSE_STRIDE - 1) / SPARSE_STRIDE);
        RawPath previous = null;
        for (int i = 0; i < count; i++) {
            long offset = input.absolutePosition();
            RawPath path = readPath(input, stringCount);
            requireOrdered(previous, path, "UNRESOLVED_FILES paths");
            previous = path;
            if (i % SPARSE_STRIDE == 0) {
                sparse.add(SparseRow.path(i, offset, path));
            }
            boolean any = input.readBoolean();
            int keys = input.readCount("unresolved candidate", MAX_RECORD_COUNT, 0);
            if (!any && keys == 0) {
                throw format("UNRESOLVED_FILES contains an empty record");
            }
            RawMatchKey previousKey = null;
            for (int j = 0; j < keys; j++) {
                RawMatchKey key = readMatchKey(input, stringCount);
                requireOrdered(previousKey, key, "UNRESOLVED_FILES keys");
                previousKey = key;
            }
        }
        input.requireEnd();
        return SparseTable.path(entry, count, sparse);
    }

    private static void skipIds(BinaryReader input, int upperBound, String label)
            throws ProjectIndexFormatException {
        int count = input.readCount(label + " id", MAX_RECORD_COUNT, 0);
        int previous = -1;
        for (int i = 0; i < count; i++) {
            int delta = input.readVarInt();
            long id = (long) previous + delta + 1;
            if (id < 0 || id >= upperBound) {
                throw format("Invalid " + label + " id");
            }
            previous = (int) id;
        }
    }

    private static RawMatchKey readMatchKey(BinaryReader input, int stringCount)
            throws ProjectIndexFormatException {
        int family = input.readVarInt();
        int exact = input.readVarInt();
        if (family >= ReferenceMatchKey.MatchFamily.values().length
                || exact > org.pgcodekeeper.core.database.api.schema.DbObjType.values().length
                || (family == ReferenceMatchKey.MatchFamily.EXACT.ordinal()) != (exact != 0)) {
            throw format("Invalid reference match key");
        }
        return new RawMatchKey(family, exact,
                readNullableStringId(input, stringCount),
                readNullableStringId(input, stringCount),
                readNullableStringId(input, stringCount),
                readNullableStringId(input, stringCount),
                input.readBoolean() ? 1 : 0);
    }

    private static RawPath readPath(BinaryReader input, int stringCount)
            throws ProjectIndexFormatException {
        int origin = input.readVarInt();
        if (origin >= IndexPathOrigin.values().length) {
            throw format("Invalid project index path origin");
        }
        return new RawPath(origin, readStringId(input, stringCount));
    }

    private static int readStringId(BinaryReader input, int stringCount)
            throws ProjectIndexFormatException {
        int value = input.readVarInt();
        if (value <= 0 || value > stringCount) {
            throw format("Invalid required string id");
        }
        return value;
    }

    private static int readNullableStringId(BinaryReader input, int stringCount)
            throws ProjectIndexFormatException {
        int value = input.readVarInt();
        if (value < 0 || value > stringCount) {
            throw format("Invalid string id");
        }
        return value;
    }

    private static <T extends Comparable<T>> void requireOrdered(T previous, T current,
            String label) throws ProjectIndexFormatException {
        if (previous != null && previous.compareTo(current) >= 0) {
            throw format(label + " are not strictly sorted");
        }
    }

    private static BinaryReader payload(ProjectIndexSource source, DirectoryEntry entry)
            throws ProjectIndexFormatException {
        return new BinaryReader(source, entry.offset() + SECTION_FRAME_SIZE,
                entry.payloadLength(), entry.type().name(), null);
    }

    private static void writeUtf8(BinaryWriter output, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.writeBytes(bytes);
    }

    private static void writeBlocks(BinaryWriter output, BlockDirectory directory) {
        output.writeInt(directory.recordCount());
        output.writeInt(directory.blocks().size());
        for (BlockRow block : directory.blocks()) {
            output.writeInt(block.startRecord());
            output.writeInt(block.recordCount());
            output.writeLong(block.offset());
            output.writeInt(block.length());
        }
    }

    private static void writeSparse(BinaryWriter output, SparseTable table) {
        output.writeInt(table.kind().ordinal());
        output.writeLong(table.payloadOffset());
        output.writeInt(table.payloadLength());
        output.writeInt(table.recordCount());
        output.writeInt(table.rows().size());
        for (SparseRow row : table.rows()) {
            output.writeInt(row.recordIndex());
            output.writeLong(row.offset());
            switch (table.kind()) {
            case MATCH -> {
                RawMatchKey key = row.matchKey();
                output.writeInt(key.family());
                output.writeInt(key.exact());
                output.writeInt(key.schema());
                output.writeInt(key.table());
                output.writeInt(key.column());
                output.writeInt(key.alias());
                output.writeInt(key.global());
            }
            case STRING -> output.writeInt(row.stringId());
            case PATH -> {
                output.writeInt(row.path().origin());
                output.writeInt(row.path().pathId());
            }
            }
        }
    }

    enum SparseKind {
        MATCH,
        STRING,
        PATH
    }

    record BlockDirectory(int recordCount, List<BlockRow> blocks) {
    }

    record BlockRow(int startRecord, int recordCount, long offset, int length) {
    }

    record FileRow(int origin, int pathId, long eclipseStamp, long size,
            long modified, byte[] sha256, int definitionStart, int definitionCount,
            int locationStart, int locationCount) {
        FileRow withRanges(int newDefinitionStart, int newDefinitionCount,
                int newLocationStart, int newLocationCount) {
            return new FileRow(origin, pathId, eclipseStamp, size, modified, sha256,
                    newDefinitionStart, newDefinitionCount,
                    newLocationStart, newLocationCount);
        }
    }

    record SparseTable(SparseKind kind, long payloadOffset, int payloadLength,
            int recordCount, List<SparseRow> rows) {
        static SparseTable match(DirectoryEntry entry, int count, List<SparseRow> rows) {
            return create(SparseKind.MATCH, entry, count, rows);
        }

        static SparseTable string(DirectoryEntry entry, int count, List<SparseRow> rows) {
            return create(SparseKind.STRING, entry, count, rows);
        }

        static SparseTable path(DirectoryEntry entry, int count, List<SparseRow> rows) {
            return create(SparseKind.PATH, entry, count, rows);
        }

        private static SparseTable create(SparseKind kind, DirectoryEntry entry,
                int count, List<SparseRow> rows) {
            return new SparseTable(kind, entry.offset() + SECTION_FRAME_SIZE,
                    entry.payloadLength(), count, List.copyOf(rows));
        }
    }

    record SparseRow(int recordIndex, long offset, RawMatchKey matchKey,
            int stringId, RawPath path) {
        static SparseRow match(int index, long offset, RawMatchKey key) {
            return new SparseRow(index, offset, key, 0, null);
        }

        static SparseRow string(int index, long offset, int stringId) {
            return new SparseRow(index, offset, null, stringId, null);
        }

        static SparseRow path(int index, long offset, RawPath path) {
            return new SparseRow(index, offset, null, 0, path);
        }
    }

    record RawPath(int origin, int pathId) implements Comparable<RawPath> {
        @Override
        public int compareTo(RawPath other) {
            int result = Integer.compare(origin, other.origin);
            return result != 0 ? result : Integer.compare(pathId, other.pathId);
        }
    }

    record RawMatchKey(int family, int exact, int schema, int table,
            int column, int alias, int global) implements Comparable<RawMatchKey> {
        @Override
        public int compareTo(RawMatchKey other) {
            int result = Integer.compare(family, other.family);
            if (result == 0) {
                result = Integer.compare(exact, other.exact);
            }
            if (result == 0) {
                result = Integer.compare(schema, other.schema);
            }
            if (result == 0) {
                result = Integer.compare(table, other.table);
            }
            if (result == 0) {
                result = Integer.compare(column, other.column);
            }
            if (result == 0) {
                result = Integer.compare(alias, other.alias);
            }
            return result != 0 ? result : Integer.compare(global, other.global);
        }
    }

    private record Layout(long generation,
            EnumMap<SectionType, DirectoryEntry> entries) {
    }
}
