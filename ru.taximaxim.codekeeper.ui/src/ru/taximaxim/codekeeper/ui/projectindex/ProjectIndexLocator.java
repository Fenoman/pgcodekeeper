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
import static ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexLocatorBuilder.*;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

import ru.taximaxim.codekeeper.ui.DatabaseType;

final class ProjectIndexLocator {

    private final byte[] bytes;
    private final ByteBuffer data;
    private final ProjectIndexManifest coreManifest;
    private final int codecCrcOffset;
    private final int codecBlockCount;
    private final EnumMap<SectionType, PackedBlocks> blocks =
            new EnumMap<>(SectionType.class);
    private final int filesOffset;
    private final int fileCount;
    private final SparseIndex match;
    private final SparseIndex completion;
    private final SparseIndex reverse;
    private final SparseIndex unresolved;

    private ProjectIndexLocator(byte[] bytes, ByteBuffer data,
            ProjectIndexManifest coreManifest, int codecCrcOffset, int codecBlockCount,
            EnumMap<SectionType, PackedBlocks> parsedBlocks,
            int filesOffset, int fileCount, SparseIndex match,
            SparseIndex completion, SparseIndex reverse, SparseIndex unresolved) {
        this.bytes = bytes;
        this.data = data;
        this.coreManifest = coreManifest;
        this.codecCrcOffset = codecCrcOffset;
        this.codecBlockCount = codecBlockCount;
        blocks.putAll(parsedBlocks);
        this.filesOffset = filesOffset;
        this.fileCount = fileCount;
        this.match = match;
        this.completion = completion;
        this.reverse = reverse;
        this.unresolved = unresolved;
    }

    static ProjectIndexLocator parse(byte[] bytes) throws ProjectIndexFormatException {
        if (bytes.length > MAX_LOCATOR_BYTES) {
            throw format("Project index locator exceeds the size limit");
        }
        ByteBuffer input = ByteBuffer.wrap(bytes).asReadOnlyBuffer();
        require(input, LOCATOR_MAGIC.length + Integer.BYTES * 3 + Long.BYTES + 32);
        byte[] magic = new byte[LOCATOR_MAGIC.length];
        input.get(magic);
        if (!java.util.Arrays.equals(magic, LOCATOR_MAGIC)
                || input.getInt() != LOCATOR_VERSION) {
            throw format("Invalid project index locator header");
        }
        int parserAbi = input.getInt();
        int databaseOrdinal = input.getInt();
        long generation = input.getLong();
        byte[] configSha = new byte[32];
        input.get(configSha);
        String coreVersion = readUtf8(input, "Core version", 4096);
        String uiVersion = readUtf8(input, "UI version", 4096);
        String projectIdentity = readUtf8(input, "project identity", 128);
        DatabaseType[] databaseTypes = DatabaseType.values();
        if (parserAbi < 0 || databaseOrdinal < 0 || databaseOrdinal >= databaseTypes.length
                || generation < 0) {
            throw format("Invalid project index locator identity");
        }
        ProjectIndexManifest manifest;
        try {
            manifest = new ProjectIndexManifest(FORMAT_MAJOR, FORMAT_MINOR, parserAbi,
                    coreVersion, uiVersion, databaseTypes[databaseOrdinal], projectIdentity,
                    configSha, generation, List.of());
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw format("Invalid project index locator identity", ex);
        }
        require(input, Integer.BYTES);
        int codecBlocks = input.getInt();
        if (codecBlocks <= 0 || codecBlocks > MAX_IN_MEMORY_INDEX_BYTES
                / ProjectIndexBlockCache.BLOCK_BYTES + 1) {
            throw format("Invalid project index codec block count");
        }
        int crcOffset = input.position();
        skip(input, Math.multiplyExact(codecBlocks, Integer.BYTES));

        EnumMap<SectionType, PackedBlocks> blockDirectories =
                new EnumMap<>(SectionType.class);
        blockDirectories.put(SectionType.STRINGS, readBlocks(input, MAX_STRING_COUNT));
        blockDirectories.put(SectionType.DEFINITIONS, readBlocks(input, MAX_RECORD_COUNT));
        blockDirectories.put(SectionType.LOCATIONS, readBlocks(input, MAX_RECORD_COUNT));

        require(input, Integer.BYTES);
        int fileCount = input.getInt();
        if (fileCount < 0 || fileCount > MAX_RECORD_COUNT) {
            throw format("Invalid project index file count");
        }
        int filesOffset = input.position();
        skip(input, Math.multiplyExact(fileCount, FILE_ROW_BYTES));
        validateFiles(input, filesOffset, fileCount,
                blockDirectories.get(SectionType.STRINGS).recordCount(),
                blockDirectories.get(SectionType.DEFINITIONS).recordCount(),
                blockDirectories.get(SectionType.LOCATIONS).recordCount());

        int stringCount = blockDirectories.get(SectionType.STRINGS).recordCount();
        SparseIndex match = readSparse(input, SparseKind.MATCH,
                MATCH_SPARSE_ROW_BYTES, stringCount);
        SparseIndex completion = readSparse(input, SparseKind.STRING,
                STRING_SPARSE_ROW_BYTES, stringCount);
        SparseIndex reverse = readSparse(input, SparseKind.MATCH,
                MATCH_SPARSE_ROW_BYTES, stringCount);
        SparseIndex unresolved = readSparse(input, SparseKind.PATH,
                PATH_SPARSE_ROW_BYTES, stringCount);
        if (input.hasRemaining()) {
            throw format("Project index locator has trailing bytes");
        }
        return new ProjectIndexLocator(bytes, ByteBuffer.wrap(bytes).asReadOnlyBuffer(),
                manifest, crcOffset, codecBlocks, blockDirectories,
                filesOffset, fileCount, match, completion, reverse, unresolved);
    }

    ProjectIndexManifest coreManifest() {
        return coreManifest;
    }

    int codecBlockCount() {
        return codecBlockCount;
    }

    int codecBlockCrc(int block) {
        if (block < 0 || block >= codecBlockCount) {
            throw new IndexOutOfBoundsException(block);
        }
        return data.getInt(codecCrcOffset + block * Integer.BYTES);
    }

    PackedBlocks blocks(SectionType type) {
        PackedBlocks value = blocks.get(type);
        if (value == null) {
            throw new IllegalArgumentException("Section has no record blocks: " + type);
        }
        return value;
    }

    int fileCount() {
        return fileCount;
    }

    FileEntry file(int index) {
        if (index < 0 || index >= fileCount) {
            throw new IndexOutOfBoundsException(index);
        }
        int offset = filesOffset + index * FILE_ROW_BYTES;
        int origin = data.getInt(offset);
        int pathId = data.getInt(offset + 4);
        long eclipseStamp = data.getLong(offset + 8);
        long size = data.getLong(offset + 16);
        long modified = data.getLong(offset + 24);
        byte[] sha = new byte[32];
        ByteBuffer digest = data.duplicate();
        digest.position(offset + 32);
        digest.get(sha);
        return new FileEntry(origin, pathId, eclipseStamp, size, modified, sha,
                data.getInt(offset + 64), data.getInt(offset + 68),
                data.getInt(offset + 72), data.getInt(offset + 76));
    }

    FileRange fileRange(int index) {
        if (index < 0 || index >= fileCount) {
            throw new IndexOutOfBoundsException(index);
        }
        int offset = filesOffset + index * FILE_ROW_BYTES;
        return new FileRange(data.getInt(offset + 64),
                data.getInt(offset + 68), data.getInt(offset + 72),
                data.getInt(offset + 76));
    }

    int filePathId(int index) {
        if (index < 0 || index >= fileCount) {
            throw new IndexOutOfBoundsException(index);
        }
        return data.getInt(filesOffset + index * FILE_ROW_BYTES + 4);
    }

    int findFile(int origin, int pathId) {
        int low = 0;
        int high = fileCount - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int offset = filesOffset + middle * FILE_ROW_BYTES;
            int comparison = Integer.compare(data.getInt(offset), origin);
            if (comparison == 0) {
                comparison = Integer.compare(data.getInt(offset + 4), pathId);
            }
            if (comparison < 0) {
                low = middle + 1;
            } else if (comparison > 0) {
                high = middle - 1;
            } else {
                return middle;
            }
        }
        return -1;
    }

    SparseIndex match() {
        return match;
    }

    SparseIndex completion() {
        return completion;
    }

    SparseIndex reverse() {
        return reverse;
    }

    SparseIndex unresolved() {
        return unresolved;
    }

    long metadataBytes() {
        return bytes.length;
    }

    void validateCodecLength(long codecLength)
            throws ProjectIndexFormatException {
        if (codecLength <= 0
                || codecBlockCount != (codecLength
                        + ProjectIndexBlockCache.BLOCK_BYTES - 1)
                        / ProjectIndexBlockCache.BLOCK_BYTES) {
            throw format("Project index locator codec length is invalid");
        }
        for (PackedBlocks directory : blocks.values()) {
            for (int i = 0; i < directory.blockCount(); i++) {
                BlockEntry block = directory.block(i);
                if (block.offset() < 0 || block.length() <= 0
                        || block.offset() > codecLength - block.length()) {
                    throw format("Project index locator record block is outside the codec");
                }
            }
        }
        validateSparseRange(match, codecLength);
        validateSparseRange(completion, codecLength);
        validateSparseRange(reverse, codecLength);
        validateSparseRange(unresolved, codecLength);
    }

    private static PackedBlocks readBlocks(ByteBuffer input, int maximumRecords)
            throws ProjectIndexFormatException {
        require(input, Integer.BYTES * 2);
        int records = input.getInt();
        int blockCount = input.getInt();
        if (records < 0 || records > maximumRecords || blockCount < 0
                || blockCount > maximumRecords
                || (records == 0) != (blockCount == 0)) {
            throw format("Invalid project index locator block directory");
        }
        int rowsOffset = input.position();
        skip(input, Math.multiplyExact(blockCount, BLOCK_ROW_BYTES));
        int nextRecord = 0;
        long previousEnd = -1;
        for (int i = 0; i < blockCount; i++) {
            int offset = rowsOffset + i * BLOCK_ROW_BYTES;
            int start = input.getInt(offset);
            int count = input.getInt(offset + 4);
            long blockOffset = input.getLong(offset + 8);
            int length = input.getInt(offset + 16);
            if (start != nextRecord || count <= 0 || length <= 0
                    || length > MAX_RECORD_BYTES + 5 || blockOffset < 0
                    || blockOffset > Long.MAX_VALUE - length
                    || previousEnd > blockOffset) {
                throw format("Invalid project index locator record block");
            }
            nextRecord += count;
            previousEnd = blockOffset + length;
        }
        if (nextRecord != records) {
            throw format("Project index locator blocks do not cover every record");
        }
        return new PackedBlocks(input.asReadOnlyBuffer(), records, blockCount, rowsOffset);
    }

    private static void validateFiles(ByteBuffer input, int offset, int count,
            int stringCount, int definitionCount, int locationCount)
            throws ProjectIndexFormatException {
        int previousOrigin = -1;
        int previousPath = -1;
        int nextDefinition = 0;
        int nextLocation = 0;
        for (int i = 0; i < count; i++) {
            int row = offset + i * FILE_ROW_BYTES;
            int origin = input.getInt(row);
            int path = input.getInt(row + 4);
            long size = input.getLong(row + 16);
            int definitionStart = input.getInt(row + 64);
            int definitions = input.getInt(row + 68);
            int locationStart = input.getInt(row + 72);
            int locations = input.getInt(row + 76);
            boolean ordered = i == 0 || origin > previousOrigin
                    || origin == previousOrigin && path > previousPath;
            if (origin < 0 || origin >= IndexPathOrigin.values().length
                    || path <= 0 || path > stringCount || size < 0 || !ordered
                    || definitionStart != nextDefinition || definitions < 0
                    || locationStart != nextLocation || locations < 0
                    || (long) definitionStart + definitions > definitionCount
                    || (long) locationStart + locations > locationCount) {
                throw format("Invalid project index locator file row");
            }
            previousOrigin = origin;
            previousPath = path;
            nextDefinition += definitions;
            nextLocation += locations;
        }
        if (nextDefinition != definitionCount || nextLocation != locationCount) {
            throw format("Project index locator files do not cover every record");
        }
    }

    private static SparseIndex readSparse(ByteBuffer input, SparseKind expected,
            int rowBytes, int stringCount)
            throws ProjectIndexFormatException {
        require(input, Integer.BYTES * 4 + Long.BYTES);
        int kindOrdinal = input.getInt();
        long payloadOffset = input.getLong();
        int payloadLength = input.getInt();
        int records = input.getInt();
        int sparseCount = input.getInt();
        if (kindOrdinal != expected.ordinal() || payloadOffset < 0 || payloadLength <= 0
                || payloadOffset > Long.MAX_VALUE - payloadLength
                || records < 0 || records > MAX_RECORD_COUNT
                || sparseCount != (records + SPARSE_STRIDE - 1) / SPARSE_STRIDE) {
            throw format("Invalid project index locator sparse table");
        }
        int rowsOffset = input.position();
        skip(input, Math.multiplyExact(sparseCount, rowBytes));
        long previousOffset = -1;
        RawMatchKey previousMatch = null;
        RawPath previousPath = null;
        int previousString = 0;
        for (int i = 0; i < sparseCount; i++) {
            int row = rowsOffset + i * rowBytes;
            long offset = input.getLong(row + 4);
            if (input.getInt(row) != i * SPARSE_STRIDE
                    || offset < payloadOffset
                    || offset >= payloadOffset + payloadLength
                    || i != 0 && offset <= previousOffset) {
                throw format("Invalid project index locator sparse row");
            }
            switch (expected) {
            case MATCH -> {
                RawMatchKey key = new RawMatchKey(input.getInt(row + 12),
                        input.getInt(row + 16), input.getInt(row + 20),
                        input.getInt(row + 24), input.getInt(row + 28),
                        input.getInt(row + 32), input.getInt(row + 36));
                boolean exactFamily = key.family()
                        == ReferenceMatchKey.MatchFamily.EXACT.ordinal();
                if (key.family() < 0
                        || key.family() >= ReferenceMatchKey.MatchFamily.values().length
                        || key.exact() < 0
                        || key.exact() > org.pgcodekeeper.core.database.api.schema.DbObjType
                                .values().length
                        || exactFamily != (key.exact() != 0)
                        || key.schema() < 0 || key.schema() > stringCount
                        || key.table() < 0 || key.table() > stringCount
                        || key.column() < 0 || key.column() > stringCount
                        || key.alias() < 0 || key.alias() > stringCount
                        || key.global() < 0 || key.global() > 1
                        || previousMatch != null && previousMatch.compareTo(key) >= 0) {
                    throw format("Project index locator match keys are not sorted");
                }
                previousMatch = key;
            }
            case STRING -> {
                int key = input.getInt(row + 12);
                if (key <= previousString || key > stringCount) {
                    throw format("Project index locator string keys are not sorted");
                }
                previousString = key;
            }
            case PATH -> {
                RawPath path = new RawPath(input.getInt(row + 12),
                        input.getInt(row + 16));
                if (path.origin() < 0
                        || path.origin() >= IndexPathOrigin.values().length
                        || path.pathId() <= 0 || path.pathId() > stringCount
                        || previousPath != null && previousPath.compareTo(path) >= 0) {
                    throw format("Project index locator paths are not sorted");
                }
                previousPath = path;
            }
            }
            previousOffset = offset;
        }
        return new SparseIndex(input.asReadOnlyBuffer(), expected, payloadOffset,
                payloadLength, records, sparseCount, rowsOffset, rowBytes);
    }

    private static void validateSparseRange(SparseIndex index, long codecLength)
            throws ProjectIndexFormatException {
        if (index.payloadOffset < 0 || index.payloadLength < 0
                || index.payloadOffset > codecLength - index.payloadLength) {
            throw format("Project index locator sparse section is outside the codec");
        }
        long end = index.payloadEnd();
        for (int i = 0; i < index.sparseCount; i++) {
            long offset = index.data.getLong(index.rowsOffset + i * index.rowBytes + 4);
            if (offset < index.payloadOffset || offset >= end) {
                throw format("Project index locator sparse row is outside its section");
            }
        }
    }

    private static String readUtf8(ByteBuffer input, String label, int maximum)
            throws ProjectIndexFormatException {
        require(input, Integer.BYTES);
        int length = input.getInt();
        if (length < 0 || length > maximum) {
            throw format("Invalid project index locator " + label + " length");
        }
        require(input, length);
        ByteBuffer encoded = input.slice();
        encoded.limit(length);
        input.position(input.position() + length);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(encoded).toString();
        } catch (CharacterCodingException ex) {
            throw format("Invalid project index locator " + label, ex);
        }
    }

    private static void skip(ByteBuffer input, int bytes)
            throws ProjectIndexFormatException {
        require(input, bytes);
        input.position(input.position() + bytes);
    }

    private static void require(ByteBuffer input, int bytes)
            throws ProjectIndexFormatException {
        if (bytes < 0 || bytes > input.remaining()) {
            throw format("Project index locator is truncated");
        }
    }

    record FileEntry(int origin, int pathId, long eclipseStamp, long size,
            long modified, byte[] sha256, int definitionStart, int definitionCount,
            int locationStart, int locationCount) {
    }

    record FileRange(int definitionStart, int definitionCount,
            int locationStart, int locationCount) {
    }

    static final class PackedBlocks {
        private final ByteBuffer data;
        private final int recordCount;
        private final int blockCount;
        private final int rowsOffset;

        private PackedBlocks(ByteBuffer data, int recordCount, int blockCount, int rowsOffset) {
            this.data = data;
            this.recordCount = recordCount;
            this.blockCount = blockCount;
            this.rowsOffset = rowsOffset;
        }

        int recordCount() {
            return recordCount;
        }

        int blockCount() {
            return blockCount;
        }

        BlockEntry block(int index) {
            if (index < 0 || index >= blockCount) {
                throw new IndexOutOfBoundsException(index);
            }
            int offset = rowsOffset + index * BLOCK_ROW_BYTES;
            return new BlockEntry(data.getInt(offset), data.getInt(offset + 4),
                    data.getLong(offset + 8), data.getInt(offset + 16));
        }

        int findBlock(int recordId) {
            if (recordId < 0 || recordId >= recordCount) {
                return -1;
            }
            int low = 0;
            int high = blockCount - 1;
            while (low <= high) {
                int middle = (low + high) >>> 1;
                int offset = rowsOffset + middle * BLOCK_ROW_BYTES;
                int start = data.getInt(offset);
                int count = data.getInt(offset + 4);
                if (recordId < start) {
                    high = middle - 1;
                } else if (recordId >= start + count) {
                    low = middle + 1;
                } else {
                    return middle;
                }
            }
            return -1;
        }
    }

    record BlockEntry(int startRecord, int recordCount, long offset, int length) {
        int endRecord() {
            return startRecord + recordCount;
        }
    }

    static final class SparseIndex {
        private final ByteBuffer data;
        private final SparseKind kind;
        private final long payloadOffset;
        private final int payloadLength;
        private final int recordCount;
        private final int sparseCount;
        private final int rowsOffset;
        private final int rowBytes;

        private SparseIndex(ByteBuffer data, SparseKind kind, long payloadOffset,
                int payloadLength, int recordCount, int sparseCount,
                int rowsOffset, int rowBytes) {
            this.data = data;
            this.kind = kind;
            this.payloadOffset = payloadOffset;
            this.payloadLength = payloadLength;
            this.recordCount = recordCount;
            this.sparseCount = sparseCount;
            this.rowsOffset = rowsOffset;
            this.rowBytes = rowBytes;
        }

        int recordCount() {
            return recordCount;
        }

        long payloadOffset() {
            return payloadOffset;
        }

        int payloadLength() {
            return payloadLength;
        }

        long payloadEnd() {
            return payloadOffset + payloadLength;
        }

        SearchWindow window(RawMatchKey key) {
            requireKind(SparseKind.MATCH);
            int group = greatestLessOrEqual(row -> matchKey(row).compareTo(key));
            return windowForGroup(group);
        }

        SearchWindow tail(RawMatchKey key) {
            requireKind(SparseKind.MATCH);
            int group = greatestLessOrEqual(row -> matchKey(row).compareTo(key));
            if (group < 0) {
                return new SearchWindow(payloadEnd(), 0, payloadEnd());
            }
            int row = rowsOffset + group * rowBytes;
            int firstRecord = data.getInt(row);
            long offset = data.getLong(row + 4);
            return new SearchWindow(
                    offset, recordCount - firstRecord, payloadEnd());
        }

        SearchWindow window(int stringId) {
            requireKind(SparseKind.STRING);
            int group = greatestLessOrEqual(row ->
                    Integer.compare(data.getInt(row + 12), stringId));
            return windowForGroup(group);
        }

        SearchWindow window(RawPath path) {
            requireKind(SparseKind.PATH);
            int group = greatestLessOrEqual(row -> {
                int result = Integer.compare(data.getInt(row + 12), path.origin());
                return result != 0 ? result
                        : Integer.compare(data.getInt(row + 16), path.pathId());
            });
            return windowForGroup(group);
        }

        private int greatestLessOrEqual(java.util.function.IntUnaryOperator comparison) {
            if (sparseCount == 0) {
                return -1;
            }
            int low = 0;
            int high = sparseCount - 1;
            int result = 0;
            while (low <= high) {
                int middle = (low + high) >>> 1;
                int row = rowsOffset + middle * rowBytes;
                int compare = comparison.applyAsInt(row);
                if (compare <= 0) {
                    result = middle;
                    low = middle + 1;
                } else {
                    high = middle - 1;
                }
            }
            return result;
        }

        private SearchWindow windowForGroup(int group) {
            if (group < 0) {
                return new SearchWindow(payloadEnd(), 0, payloadEnd());
            }
            int row = rowsOffset + group * rowBytes;
            int firstRecord = data.getInt(row);
            long offset = data.getLong(row + 4);
            int records = Math.min(SPARSE_STRIDE, recordCount - firstRecord);
            return new SearchWindow(offset, records, payloadEnd());
        }

        private RawMatchKey matchKey(int row) {
            return new RawMatchKey(data.getInt(row + 12), data.getInt(row + 16),
                    data.getInt(row + 20), data.getInt(row + 24),
                    data.getInt(row + 28), data.getInt(row + 32),
                    data.getInt(row + 36));
        }

        private void requireKind(SparseKind expected) {
            if (kind != expected) {
                throw new IllegalStateException("Wrong sparse index kind");
            }
        }
    }

    record SearchWindow(long offset, int recordCount, long sectionEnd) {
    }
}
