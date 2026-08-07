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
import java.nio.channels.SeekableByteChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32C;

import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexFormat.BinaryWriter;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexFormat.DirectoryEntry;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexLocatorBuilder.BlockDirectory;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexLocatorBuilder.BlockRow;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexLocatorBuilder.FileRow;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexLocatorBuilder.RawMatchKey;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexLocatorBuilder.RawPath;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexLocatorBuilder.SparseRow;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexLocatorBuilder.SparseTable;

/**
 * Performs the only post-encode codec pass. The pass is sequential and
 * simultaneously validates structural bytes, section CRCs and locator
 * semantics while building the lazy block CRC table. Locator metadata is
 * decoded from the bytes being reread; writer-captured metadata is only an
 * expected value and cannot become authoritative without this comparison.
 */
final class ProjectIndexBlockCrcScanner {

    private ProjectIndexBlockCrcScanner() {
    }

    static ScanResult scan(SeekableByteChannel codec,
            ProjectIndexLocatorDraft draft,
            ProjectIndexWriteContext context) throws IOException {
        Objects.requireNonNull(codec, "codec");
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(context, "context");
        Layout layout = layout(draft);
        if (codec.size() != draft.codecLength()) {
            throw new ProjectIndexFormatException(
                    "Project index codec length changed after encoding");
        }

        int blockCount = Math.toIntExact((draft.codecLength()
                + ProjectIndexBlockCache.BLOCK_BYTES - 1)
                / ProjectIndexBlockCache.BLOCK_BYTES);
        int[] blockCrcs = new int[blockCount];
        EnumMap<SectionType, CRC32C> sectionCrcs =
                new EnumMap<>(SectionType.class);
        for (DirectoryEntry entry : layout.entries()) {
            sectionCrcs.put(entry.type(), new CRC32C());
        }

        SequentialReader input = new SequentialReader(codec,
                draft.codecLength(), layout, context,
                blockCrcs, sectionCrcs);
        ProjectIndexLocatorDraft decoded =
                decode(input, draft, layout.entries());
        input.finish();
        for (DirectoryEntry entry : layout.entries()) {
            if ((int) sectionCrcs.get(entry.type()).getValue()
                    != entry.payloadCrc()) {
                throw new ProjectIndexFormatException(entry.type()
                        + " section payload CRC mismatch");
            }
        }
        if (!sameLocatorSemantics(draft, decoded)) {
            throw new ProjectIndexFormatException(
                    "Captured project index locator does not match encoded codec semantics");
        }
        byte[] locator = ProjectIndexLocatorBuilder.serialize(
                decoded, blockCrcs);
        context.recordPackedRereadBytes(input.rereadBytes());
        return new ScanResult(locator, input.rereadBytes());
    }

    private static ProjectIndexLocatorDraft decode(
            SequentialReader input, ProjectIndexLocatorDraft draft,
            List<DirectoryEntry> entries)
            throws IOException {
        input.skip(HEADER_SIZE);
        BlockDirectory strings = null;
        BlockDirectory definitions = null;
        BlockDirectory locations = null;
        List<FileRow> files = null;
        SparseTable match = null;
        SparseTable completion = null;
        SparseTable reverse = null;
        SparseTable unresolved = null;
        ManifestStrings manifestStrings = null;

        for (DirectoryEntry entry : entries) {
            input.requirePosition(entry.offset());
            input.skip(SECTION_FRAME_SIZE);
            BoundedInput payload = new BoundedInput(input,
                    entry.payloadLength(), entry.type().name());
            switch (entry.type()) {
            case MANIFEST -> manifestStrings = readManifest(
                    payload, draft.manifest());
            case STRINGS -> {
                requireAvailable(manifestStrings, "MANIFEST");
                strings = readBlocks(payload,
                        MAX_STRING_COUNT, entry.type(),
                        manifestStrings);
                manifestStrings.requireComplete();
            }
            case FILES -> {
                requireAvailable(strings, "STRINGS");
                files = readFiles(payload, strings.recordCount(),
                        draft.manifest().files().size());
            }
            case DEFINITIONS -> definitions = readBlocks(payload,
                    MAX_RECORD_COUNT, entry.type());
            case LOCATIONS -> locations = readBlocks(payload,
                    MAX_RECORD_COUNT, entry.type());
            case BY_PATH -> {
                requireAvailable(strings, "STRINGS");
                requireAvailable(definitions, "DEFINITIONS");
                requireAvailable(locations, "LOCATIONS");
                requireAvailable(files, "FILES");
                readRanges(payload, strings.recordCount(),
                        definitions.recordCount(),
                        locations.recordCount(), files);
            }
            case BY_MATCH_KEY -> {
                requireAvailable(strings, "STRINGS");
                requireAvailable(definitions, "DEFINITIONS");
                requireAvailable(locations, "LOCATIONS");
                match = readMatchSparse(payload, entry,
                        strings.recordCount(),
                        definitions.recordCount(),
                        locations.recordCount());
            }
            case COMPLETION_TRIGRAMS -> {
                requireAvailable(strings, "STRINGS");
                requireAvailable(definitions, "DEFINITIONS");
                completion = readCompletionSparse(payload, entry,
                        strings.recordCount(),
                        definitions.recordCount());
            }
            case REVERSE_DEPENDENCIES -> {
                requireAvailable(strings, "STRINGS");
                reverse = readReverseSparse(payload, entry,
                        strings.recordCount());
            }
            case UNRESOLVED_FILES -> {
                requireAvailable(strings, "STRINGS");
                unresolved = readUnresolvedSparse(payload, entry,
                        strings.recordCount());
            }
            }
            payload.requireEnd();
        }
        input.skipRemaining();
        return new ProjectIndexLocatorDraft(draft.manifest(),
                draft.codecLength(), entries,
                requireAvailable(strings, "STRINGS"),
                requireAvailable(definitions, "DEFINITIONS"),
                requireAvailable(locations, "LOCATIONS"),
                requireAvailable(files, "FILES"),
                requireAvailable(match, "BY_MATCH_KEY"),
                requireAvailable(completion, "COMPLETION_TRIGRAMS"),
                requireAvailable(reverse, "REVERSE_DEPENDENCIES"),
                requireAvailable(unresolved, "UNRESOLVED_FILES"));
    }

    private static BlockDirectory readBlocks(BoundedInput input,
            int maximumRecords, SectionType type)
            throws IOException {
        return readBlocks(input, maximumRecords, type, null);
    }

    private static BlockDirectory readBlocks(BoundedInput input,
            int maximumRecords, SectionType type,
            ManifestStrings manifestStrings)
            throws IOException {
        int recordCount = input.readCount(
                "record", maximumRecords);
        if (manifestStrings != null) {
            manifestStrings.requireValidIds(recordCount);
        }
        List<BlockRow> blocks = new ArrayList<>();
        int records = 0;
        while (records < recordCount) {
            int blockLength = input.readVarInt();
            if (blockLength == 0
                    || blockLength > MAX_RECORD_BYTES + 5) {
                throw format(type
                        + " raw block exceeds the size limit");
            }
            long blockOffset = input.absolutePosition();
            BoundedInput block = input.slice(
                    blockLength, type + " block");
            int blockRecords = 0;
            while (block.hasRemaining()) {
                int length = block.readVarInt();
                if (length > MAX_RECORD_BYTES) {
                    throw format(type
                            + " record exceeds the size limit");
                }
                int recordId = records + 1;
                if (manifestStrings == null) {
                    block.skip(length);
                } else {
                    manifestStrings.readRecord(
                            recordId, block, length);
                }
                blockRecords++;
                records++;
                if (records > recordCount) {
                    throw format(type
                            + " has too many records");
                }
            }
            block.requireEnd();
            if (blockLength > MAX_RAW_BLOCK_BYTES
                    && blockRecords != 1) {
                throw format(type
                        + " oversized block must contain exactly one record");
            }
            if ((long) (blocks.size() + 1)
                    * ProjectIndexLocatorBuilder.BLOCK_ROW_BYTES
                    > ProjectIndexLocatorBuilder.MAX_LOCATOR_BYTES) {
                throw format(type
                        + " locator block count exceeds the size limit");
            }
            blocks.add(new BlockRow(
                    records - blockRecords, blockRecords,
                    blockOffset, blockLength));
        }
        int declaredBlocks = input.readCount(
                "block", maximumRecords);
        input.requireEnd();
        if (declaredBlocks != blocks.size()
                || (recordCount == 0) != blocks.isEmpty()) {
            throw format(type + " block count mismatch");
        }
        return new BlockDirectory(recordCount,
                List.copyOf(blocks));
    }

    private static ManifestStrings readManifest(
            BoundedInput input, ProjectIndexManifest expected)
            throws IOException {
        int major = input.readVarInt();
        int minor = input.readVarInt();
        int parserAbi = input.readVarInt();
        int coreVersionId = readManifestStringId(
                input, "core version");
        int uiVersionId = readManifestStringId(
                input, "UI version");
        int databaseType = input.readVarInt();
        int projectIdentityId = readManifestStringId(
                input, "project identity");
        byte[] configSha = input.readBytes(32);
        long generation = input.readLong();
        input.requireEnd();

        if (major != expected.formatMajor()
                || minor != expected.formatMinor()
                || parserAbi != expected.parserAbi()
                || databaseType
                        != expected.databaseType().ordinal()
                || generation != expected.generation()
                || !Arrays.equals(
                        configSha, expected.configSha256())) {
            throw format(
                    "MANIFEST does not match captured manifest semantics");
        }
        return new ManifestStrings(
                coreVersionId, expected.coreVersion(),
                uiVersionId, expected.uiVersion(),
                projectIdentityId, expected.projectIdentity());
    }

    private static int readManifestStringId(
            BoundedInput input, String label) throws IOException {
        int value = input.readVarInt();
        if (value <= 0) {
            throw format("MANIFEST contains an invalid "
                    + label + " string id");
        }
        return value;
    }

    private static int encodedUtf8Length(String value)
            throws ProjectIndexFormatException {
        long bytes = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current <= 0x7F) {
                bytes++;
            } else if (current <= 0x7FF) {
                bytes += 2;
            } else if (Character.isHighSurrogate(current)
                    && index + 1 < value.length()
                    && Character.isLowSurrogate(
                            value.charAt(index + 1))) {
                bytes += 4;
                index++;
            } else if (Character.isSurrogate(current)) {
                throw format(
                        "Captured MANIFEST string contains an unpaired UTF-16 surrogate");
            } else {
                bytes += 3;
            }
            if (bytes > MAX_RECORD_BYTES) {
                throw format(
                        "Captured MANIFEST string exceeds the record size limit");
            }
        }
        return (int) bytes;
    }

    private static boolean readExpectedByte(
            BoundedInput input, int expected, boolean matches)
            throws IOException {
        return input.readUnsignedByte() == expected && matches;
    }

    private static void requireUtf8Record(
            BoundedInput input, int length, String expected,
            String label) throws IOException {
        if (encodedUtf8Length(expected) != length) {
            input.skip(length);
            throw format("MANIFEST " + label
                    + " does not match its STRINGS record");
        }

        boolean matches = true;
        for (int index = 0; index < expected.length(); index++) {
            char current = expected.charAt(index);
            if (current <= 0x7F) {
                matches = readExpectedByte(
                        input, current, matches);
            } else if (current <= 0x7FF) {
                matches = readExpectedByte(input,
                        0xC0 | current >>> 6, matches);
                matches = readExpectedByte(input,
                        0x80 | current & 0x3F, matches);
            } else if (Character.isHighSurrogate(current)) {
                int codePoint = Character.toCodePoint(
                        current, expected.charAt(++index));
                matches = readExpectedByte(input,
                        0xF0 | codePoint >>> 18, matches);
                matches = readExpectedByte(input,
                        0x80 | codePoint >>> 12 & 0x3F,
                        matches);
                matches = readExpectedByte(input,
                        0x80 | codePoint >>> 6 & 0x3F,
                        matches);
                matches = readExpectedByte(input,
                        0x80 | codePoint & 0x3F, matches);
            } else {
                matches = readExpectedByte(input,
                        0xE0 | current >>> 12, matches);
                matches = readExpectedByte(input,
                        0x80 | current >>> 6 & 0x3F,
                        matches);
                matches = readExpectedByte(input,
                        0x80 | current & 0x3F, matches);
            }
        }
        if (!matches) {
            throw format("MANIFEST " + label
                    + " does not match its STRINGS record");
        }
    }

    private static final class ManifestStrings {

        private final int coreVersionId;
        private final String coreVersion;
        private final int uiVersionId;
        private final String uiVersion;
        private final int projectIdentityId;
        private final String projectIdentity;
        private boolean coreVersionRead;
        private boolean uiVersionRead;
        private boolean projectIdentityRead;

        private ManifestStrings(
                int coreVersionId, String coreVersion,
                int uiVersionId, String uiVersion,
                int projectIdentityId, String projectIdentity) {
            this.coreVersionId = coreVersionId;
            this.coreVersion = coreVersion;
            this.uiVersionId = uiVersionId;
            this.uiVersion = uiVersion;
            this.projectIdentityId = projectIdentityId;
            this.projectIdentity = projectIdentity;
        }

        void requireValidIds(int stringCount)
                throws ProjectIndexFormatException {
            if (coreVersionId > stringCount
                    || uiVersionId > stringCount
                    || projectIdentityId > stringCount) {
                throw format(
                        "MANIFEST references an unknown STRINGS record");
            }
        }

        void readRecord(int recordId, BoundedInput input,
                int length) throws IOException {
            String expected = null;
            String label = null;
            if (recordId == coreVersionId) {
                expected = coreVersion;
                label = "core version";
            }
            if (recordId == uiVersionId) {
                requireSameReference(
                        expected, uiVersion, recordId);
                expected = uiVersion;
                label = label == null
                        ? "UI version" : label;
            }
            if (recordId == projectIdentityId) {
                requireSameReference(
                        expected, projectIdentity, recordId);
                expected = projectIdentity;
                label = label == null
                        ? "project identity" : label;
            }
            if (expected == null) {
                input.skip(length);
                return;
            }

            requireUtf8Record(input, length, expected, label);
            coreVersionRead |= recordId == coreVersionId;
            uiVersionRead |= recordId == uiVersionId;
            projectIdentityRead |= recordId == projectIdentityId;
        }

        void requireComplete()
                throws ProjectIndexFormatException {
            if (!coreVersionRead || !uiVersionRead
                    || !projectIdentityRead) {
                throw format(
                        "MANIFEST string references were not decoded");
            }
        }

        private static void requireSameReference(
                String previous, String current, int stringId)
                throws ProjectIndexFormatException {
            if (previous != null && !previous.equals(current)) {
                throw format("MANIFEST string id " + stringId
                        + " references conflicting captured values");
            }
        }
    }

    private static List<FileRow> readFiles(BoundedInput input,
            int stringCount, int expectedFiles)
            throws IOException {
        int count = input.readCount("file", MAX_RECORD_COUNT);
        if (count != expectedFiles
                || (long) count
                        * ProjectIndexLocatorBuilder.FILE_ROW_BYTES
                        > ProjectIndexLocatorBuilder.MAX_LOCATOR_BYTES) {
            throw format(
                    "FILES count does not match captured manifest bounds");
        }
        List<FileRow> files = new ArrayList<>(count);
        RawPath previous = null;
        for (int index = 0; index < count; index++) {
            RawPath path = readPath(input, stringCount);
            requireOrdered(previous, path, "FILES paths");
            previous = path;
            long stamp = input.readLong();
            long size = input.readLong();
            long modified = input.readLong();
            byte[] sha = input.readBytes(32);
            if (size < 0) {
                throw format(
                        "FILES contains a negative file size");
            }
            files.add(new FileRow(path.origin(), path.pathId(),
                    stamp, size, modified, sha,
                    0, 0, 0, 0));
        }
        input.requireEnd();
        return files;
    }

    private static void readRanges(BoundedInput input,
            int stringCount, int definitionCount,
            int locationCount, List<FileRow> files)
            throws IOException {
        int count = input.readCount(
                "path range", MAX_RECORD_COUNT);
        if (count != files.size()) {
            throw format("FILES and BY_PATH counts differ");
        }
        int nextDefinition = 0;
        int nextLocation = 0;
        RawPath previous = null;
        for (int index = 0; index < count; index++) {
            RawPath path = readPath(input, stringCount);
            requireOrdered(previous, path, "BY_PATH paths");
            previous = path;
            int definitionStart = input.readVarInt();
            int definitions = input.readVarInt();
            int locationStart = input.readVarInt();
            int locations = input.readVarInt();
            FileRow file = files.get(index);
            if (file.origin() != path.origin()
                    || file.pathId() != path.pathId()
                    || definitionStart != nextDefinition
                    || locationStart != nextLocation
                    || (long) definitionStart + definitions
                            > definitionCount
                    || (long) locationStart + locations
                            > locationCount) {
                throw format(
                        "FILES and BY_PATH are inconsistent");
            }
            files.set(index, file.withRanges(
                    definitionStart, definitions,
                    locationStart, locations));
            nextDefinition = Math.addExact(
                    nextDefinition, definitions);
            nextLocation = Math.addExact(
                    nextLocation, locations);
        }
        input.requireEnd();
        if (nextDefinition != definitionCount
                || nextLocation != locationCount) {
            throw format(
                    "BY_PATH does not cover every record");
        }
    }

    private static SparseTable readMatchSparse(
            BoundedInput input, DirectoryEntry entry,
            int stringCount, int definitionCount,
            int locationCount) throws IOException {
        int count = input.readCount(
                "match key", MAX_RECORD_COUNT);
        List<SparseRow> sparse = sparseRows(count);
        RawMatchKey previous = null;
        for (int index = 0; index < count; index++) {
            long offset = input.absolutePosition();
            RawMatchKey key = readMatchKey(
                    input, stringCount);
            requireOrdered(previous, key,
                    "BY_MATCH_KEY keys");
            previous = key;
            if (index % ProjectIndexLocatorBuilder.SPARSE_STRIDE
                    == 0) {
                sparse.add(SparseRow.match(
                        index, offset, key));
            }
            skipIds(input, definitionCount, "definition");
            skipIds(input, locationCount, "location");
        }
        input.requireEnd();
        return SparseTable.match(entry, count, sparse);
    }

    private static SparseTable readCompletionSparse(
            BoundedInput input, DirectoryEntry entry,
            int stringCount, int definitionCount)
            throws IOException {
        int count = input.readCount(
                "completion trigram", MAX_RECORD_COUNT);
        List<SparseRow> sparse = sparseRows(count);
        int previous = 0;
        for (int index = 0; index < count; index++) {
            long offset = input.absolutePosition();
            int stringId = readStringId(
                    input, stringCount);
            if (stringId <= previous) {
                throw format(
                        "COMPLETION_TRIGRAMS keys are not strictly sorted");
            }
            previous = stringId;
            if (index % ProjectIndexLocatorBuilder.SPARSE_STRIDE
                    == 0) {
                sparse.add(SparseRow.string(
                        index, offset, stringId));
            }
            skipIds(input, definitionCount, "definition");
        }
        input.requireEnd();
        return SparseTable.string(entry, count, sparse);
    }

    private static SparseTable readReverseSparse(
            BoundedInput input, DirectoryEntry entry,
            int stringCount) throws IOException {
        int count = input.readCount(
                "reverse dependency key", MAX_RECORD_COUNT);
        List<SparseRow> sparse = sparseRows(count);
        RawMatchKey previous = null;
        for (int index = 0; index < count; index++) {
            long offset = input.absolutePosition();
            RawMatchKey key = readMatchKey(
                    input, stringCount);
            requireOrdered(previous, key,
                    "REVERSE_DEPENDENCIES keys");
            previous = key;
            if (index % ProjectIndexLocatorBuilder.SPARSE_STRIDE
                    == 0) {
                sparse.add(SparseRow.match(
                        index, offset, key));
            }
            int paths = input.readCount(
                    "dependent path", MAX_RECORD_COUNT);
            RawPath previousPath = null;
            for (int pathIndex = 0;
                    pathIndex < paths; pathIndex++) {
                RawPath path = readPath(
                        input, stringCount);
                requireOrdered(previousPath, path,
                        "REVERSE_DEPENDENCIES paths");
                previousPath = path;
            }
        }
        input.requireEnd();
        return SparseTable.match(entry, count, sparse);
    }

    private static SparseTable readUnresolvedSparse(
            BoundedInput input, DirectoryEntry entry,
            int stringCount) throws IOException {
        int count = input.readCount(
                "unresolved file", MAX_RECORD_COUNT);
        List<SparseRow> sparse = sparseRows(count);
        RawPath previous = null;
        for (int index = 0; index < count; index++) {
            long offset = input.absolutePosition();
            RawPath path = readPath(input, stringCount);
            requireOrdered(previous, path,
                    "UNRESOLVED_FILES paths");
            previous = path;
            if (index % ProjectIndexLocatorBuilder.SPARSE_STRIDE
                    == 0) {
                sparse.add(SparseRow.path(
                        index, offset, path));
            }
            boolean any = input.readBoolean();
            int keys = input.readCount(
                    "unresolved candidate", MAX_RECORD_COUNT);
            if (!any && keys == 0) {
                throw format(
                        "UNRESOLVED_FILES contains an empty record");
            }
            RawMatchKey previousKey = null;
            for (int keyIndex = 0;
                    keyIndex < keys; keyIndex++) {
                RawMatchKey key = readMatchKey(
                        input, stringCount);
                requireOrdered(previousKey, key,
                        "UNRESOLVED_FILES keys");
                previousKey = key;
            }
        }
        input.requireEnd();
        return SparseTable.path(entry, count, sparse);
    }

    private static List<SparseRow> sparseRows(int count) {
        return new ArrayList<>((count
                + ProjectIndexLocatorBuilder.SPARSE_STRIDE - 1)
                / ProjectIndexLocatorBuilder.SPARSE_STRIDE);
    }

    private static void skipIds(BoundedInput input,
            int upperBound, String label) throws IOException {
        int count = input.readCount(
                label + " id", MAX_RECORD_COUNT);
        int previous = -1;
        for (int index = 0; index < count; index++) {
            int delta = input.readVarInt();
            long id = (long) previous + delta + 1;
            if (id < 0 || id >= upperBound) {
                throw format("Invalid " + label + " id");
            }
            previous = (int) id;
        }
    }

    private static RawMatchKey readMatchKey(
            BoundedInput input, int stringCount)
            throws IOException {
        int family = input.readVarInt();
        int exact = input.readVarInt();
        if (family
                >= ReferenceMatchKey.MatchFamily.values().length
                || exact > org.pgcodekeeper.core.database.api.schema
                        .DbObjType.values().length
                || (family
                        == ReferenceMatchKey.MatchFamily.EXACT
                                .ordinal())
                        != (exact != 0)) {
            throw format("Invalid reference match key");
        }
        return new RawMatchKey(family, exact,
                readNullableStringId(input, stringCount),
                readNullableStringId(input, stringCount),
                readNullableStringId(input, stringCount),
                readNullableStringId(input, stringCount),
                input.readBoolean() ? 1 : 0);
    }

    private static RawPath readPath(BoundedInput input,
            int stringCount) throws IOException {
        int origin = input.readVarInt();
        if (origin >= IndexPathOrigin.values().length) {
            throw format(
                    "Invalid project index path origin");
        }
        return new RawPath(origin,
                readStringId(input, stringCount));
    }

    private static int readStringId(BoundedInput input,
            int stringCount) throws IOException {
        int value = input.readVarInt();
        if (value <= 0 || value > stringCount) {
            throw format("Invalid required string id");
        }
        return value;
    }

    private static int readNullableStringId(
            BoundedInput input, int stringCount)
            throws IOException {
        int value = input.readVarInt();
        if (value < 0 || value > stringCount) {
            throw format("Invalid string id");
        }
        return value;
    }

    private static <T extends Comparable<T>> void requireOrdered(
            T previous, T current, String label)
            throws ProjectIndexFormatException {
        if (previous != null
                && previous.compareTo(current) >= 0) {
            throw format(label + " are not strictly sorted");
        }
    }

    private static <T> T requireAvailable(
            T value, String section)
            throws ProjectIndexFormatException {
        if (value == null) {
            throw format(section
                    + " was not decoded before its dependent section");
        }
        return value;
    }

    private static boolean sameLocatorSemantics(
            ProjectIndexLocatorDraft expected,
            ProjectIndexLocatorDraft actual) {
        return expected.codecLength() == actual.codecLength()
                && expected.entries().equals(actual.entries())
                && expected.strings().equals(actual.strings())
                && expected.definitions().equals(
                        actual.definitions())
                && expected.locations().equals(actual.locations())
                && sameFiles(expected.files(), actual.files())
                && expected.match().equals(actual.match())
                && expected.completion().equals(
                        actual.completion())
                && expected.reverse().equals(actual.reverse())
                && expected.unresolved().equals(
                        actual.unresolved());
    }

    private static boolean sameFiles(List<FileRow> expected,
            List<FileRow> actual) {
        if (expected.size() != actual.size()) {
            return false;
        }
        for (int index = 0; index < expected.size(); index++) {
            FileRow left = expected.get(index);
            FileRow right = actual.get(index);
            if (left.origin() != right.origin()
                    || left.pathId() != right.pathId()
                    || left.eclipseStamp() != right.eclipseStamp()
                    || left.size() != right.size()
                    || left.modified() != right.modified()
                    || !Arrays.equals(
                            left.sha256(), right.sha256())
                    || left.definitionStart()
                            != right.definitionStart()
                    || left.definitionCount()
                            != right.definitionCount()
                    || left.locationStart()
                            != right.locationStart()
                    || left.locationCount()
                            != right.locationCount()) {
                return false;
            }
        }
        return true;
    }

    private static Layout layout(ProjectIndexLocatorDraft draft)
            throws ProjectIndexFormatException {
        List<DirectoryEntry> entries =
                new ArrayList<>(draft.entries());
        entries.sort(Comparator.comparingLong(
                DirectoryEntry::offset));
        if (entries.size() != SectionType.values().length) {
            throw new ProjectIndexFormatException(
                    "Project index locator draft has incomplete sections");
        }

        long expectedOffset = HEADER_SIZE;
        for (int index = 0; index < entries.size(); index++) {
            DirectoryEntry entry = entries.get(index);
            if (entry.type() != SectionType.values()[index]
                    || entry.flags() != 0
                    || entry.offset() != expectedOffset
                    || entry.payloadLength() < 0
                    || entry.payloadLength() > MAX_SECTION_BYTES) {
                throw new ProjectIndexFormatException(
                        "Project index locator draft has invalid section layout");
            }
            expectedOffset = Math.addExact(entry.offset(),
                    Math.addExact(SECTION_FRAME_SIZE,
                            entry.payloadLength()));
        }
        long directoryOffset = expectedOffset;
        int directoryLength = Math.addExact(
                DIRECTORY_MAGIC.length + Integer.BYTES,
                Math.multiplyExact(entries.size(),
                        DIRECTORY_ENTRY_SIZE));
        if (directoryOffset + directoryLength + FOOTER_SIZE
                != draft.codecLength()) {
            throw new ProjectIndexFormatException(
                    "Project index locator draft has invalid codec bounds");
        }

        byte[] directory = directory(entries);
        int directoryCrc = crc32c(directory, 0,
                directory.length - Integer.BYTES);
        byte[] header = header(draft.manifest().generation(),
                directoryOffset, directory.length, entries.size(),
                directoryCrc);
        byte[] footer = footer(directoryOffset);
        List<ExpectedRange> ranges =
                new ArrayList<>(entries.size() + 3);
        ranges.add(new ExpectedRange(0, header));
        for (DirectoryEntry entry : entries) {
            ranges.add(new ExpectedRange(entry.offset(),
                    frame(entry)));
        }
        ranges.add(new ExpectedRange(directoryOffset, directory));
        ranges.add(new ExpectedRange(
                directoryOffset + directory.length, footer));
        ranges.sort(Comparator.comparingLong(ExpectedRange::offset));
        return new Layout(List.copyOf(entries),
                List.copyOf(ranges));
    }

    private static byte[] header(long generation,
            long directoryOffset, int directoryLength,
            int sectionCount, int directoryCrc) {
        BinaryWriter header = new BinaryWriter();
        header.writeBytes(HEADER_MAGIC);
        header.writeShort(FORMAT_MAJOR);
        header.writeShort(FORMAT_MINOR);
        header.writeLong(generation);
        header.writeLong(directoryOffset);
        header.writeInt(directoryLength);
        header.writeInt(sectionCount);
        header.writeInt(directoryCrc);
        header.writeInt(crc32c(header.bytes(), 0,
                header.size()));
        return header.bytes();
    }

    private static byte[] directory(
            List<DirectoryEntry> entries) {
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
        directory.writeInt(crc32c(directory.bytes(), 0,
                directory.size()));
        return directory.bytes();
    }

    private static byte[] footer(long directoryOffset) {
        BinaryWriter footer = new BinaryWriter();
        footer.writeBytes(FOOTER_MAGIC);
        footer.writeLong(directoryOffset);
        footer.writeInt(crc32c(footer.bytes(), 0,
                footer.size()));
        return footer.bytes();
    }

    private static byte[] frame(DirectoryEntry entry) {
        BinaryWriter frame = new BinaryWriter();
        frame.writeByte(entry.type().id());
        frame.writeByte(0);
        frame.writeShort(0);
        frame.writeInt(entry.payloadLength());
        frame.writeInt(entry.payloadCrc());
        return frame.bytes();
    }

    private static void validateExpectedRanges(
            List<ExpectedRange> ranges, long blockStart,
            byte[] bytes, int length)
            throws ProjectIndexFormatException {
        long blockEnd = blockStart + length;
        for (ExpectedRange range : ranges) {
            long rangeEnd = range.offset()
                    + range.bytes().length;
            long overlapStart = Math.max(blockStart,
                    range.offset());
            long overlapEnd = Math.min(blockEnd, rangeEnd);
            if (overlapStart >= overlapEnd) {
                continue;
            }
            int blockOffset = Math.toIntExact(
                    overlapStart - blockStart);
            int expectedOffset = Math.toIntExact(
                    overlapStart - range.offset());
            int overlapLength = Math.toIntExact(
                    overlapEnd - overlapStart);
            for (int index = 0; index < overlapLength; index++) {
                if (bytes[blockOffset + index]
                        != range.bytes()[expectedOffset + index]) {
                    throw new ProjectIndexFormatException(
                            "Project index structural validation failed");
                }
            }
        }
    }

    private static void updateSectionCrcs(
            List<DirectoryEntry> entries,
            EnumMap<SectionType, CRC32C> sectionCrcs,
            long blockStart, byte[] bytes, int length) {
        long blockEnd = blockStart + length;
        for (DirectoryEntry entry : entries) {
            long payloadStart =
                    entry.offset() + SECTION_FRAME_SIZE;
            long payloadEnd =
                    payloadStart + entry.payloadLength();
            long overlapStart = Math.max(blockStart,
                    payloadStart);
            long overlapEnd = Math.min(blockEnd, payloadEnd);
            if (overlapStart < overlapEnd) {
                sectionCrcs.get(entry.type()).update(bytes,
                        Math.toIntExact(overlapStart - blockStart),
                        Math.toIntExact(overlapEnd - overlapStart));
            }
        }
    }

    private static final class SequentialReader {

        private final SeekableByteChannel channel;
        private final long length;
        private final Layout layout;
        private final ProjectIndexWriteContext context;
        private final int[] blockCrcs;
        private final EnumMap<SectionType, CRC32C> sectionCrcs;
        private final byte[] bytes =
                new byte[ProjectIndexBlockCache.BLOCK_BYTES];
        private int buffered;
        private int cursor;
        private int nextBlock;
        private long position;
        private long rereadBytes;

        private SequentialReader(SeekableByteChannel channel,
                long length, Layout layout,
                ProjectIndexWriteContext context,
                int[] blockCrcs,
                EnumMap<SectionType, CRC32C> sectionCrcs)
                throws IOException {
            this.channel = channel;
            this.length = length;
            this.layout = layout;
            this.context = context;
            this.blockCrcs = blockCrcs;
            this.sectionCrcs = sectionCrcs;
            channel.position(0);
        }

        long position() {
            return position;
        }

        long rereadBytes() {
            return rereadBytes;
        }

        int readUnsignedByte() throws IOException {
            if (cursor == buffered) {
                load();
            }
            position++;
            return Byte.toUnsignedInt(bytes[cursor++]);
        }

        void skip(long count) throws IOException {
            if (count < 0 || count > length - position) {
                throw format(
                        "Project index sequential read exceeds codec bounds");
            }
            long remaining = count;
            while (remaining > 0) {
                if (cursor == buffered) {
                    load();
                }
                int chunk = (int) Math.min(
                        remaining, buffered - cursor);
                cursor += chunk;
                position += chunk;
                remaining -= chunk;
            }
        }

        void skipRemaining() throws IOException {
            skip(length - position);
        }

        void requirePosition(long expected)
                throws ProjectIndexFormatException {
            if (position != expected) {
                throw format(
                        "Project index sequential decoder lost section alignment");
            }
        }

        void finish() throws IOException {
            context.requireNotCancelled();
            if (position != length || cursor != buffered
                    || rereadBytes != length
                    || nextBlock != blockCrcs.length) {
                throw format(
                        "Project index codec reread length mismatch");
            }
        }

        private void load() throws IOException {
            context.requireNotCancelled();
            if (position >= length
                    || nextBlock >= blockCrcs.length) {
                throw format(
                        "Project index codec ended unexpectedly");
            }
            long blockStart = position;
            if (blockStart
                    != (long) nextBlock
                            * ProjectIndexBlockCache.BLOCK_BYTES) {
                throw format(
                        "Project index sequential decoder lost block alignment");
            }
            int blockLength = (int) Math.min(
                    ProjectIndexBlockCache.BLOCK_BYTES,
                    length - blockStart);
            ByteBuffer buffer = ByteBuffer.wrap(
                    bytes, 0, blockLength);
            while (buffer.hasRemaining()) {
                context.requireNotCancelled();
                int read = channel.read(buffer);
                if (read <= 0) {
                    throw format(
                            "Unable to make progress while rereading project index codec");
                }
                rereadBytes = Math.addExact(
                        rereadBytes, read);
            }

            CRC32C blockCrc = new CRC32C();
            blockCrc.update(bytes, 0, blockLength);
            blockCrcs[nextBlock++] =
                    (int) blockCrc.getValue();
            validateExpectedRanges(layout.expectedRanges(),
                    blockStart, bytes, blockLength);
            updateSectionCrcs(layout.entries(), sectionCrcs,
                    blockStart, bytes, blockLength);
            cursor = 0;
            buffered = blockLength;
        }
    }

    private static final class BoundedInput {

        private final SequentialReader source;
        private final long end;
        private final String context;

        private BoundedInput(SequentialReader source,
                int length, String context)
                throws ProjectIndexFormatException {
            this.source = source;
            this.context = context;
            if (length < 0
                    || length > source.length - source.position()) {
                throw format(context
                        + " range is outside the project index");
            }
            end = source.position() + length;
        }

        long absolutePosition() {
            return source.position();
        }

        boolean hasRemaining() {
            return source.position() < end;
        }

        int readUnsignedByte() throws IOException {
            require(1);
            return source.readUnsignedByte();
        }

        boolean readBoolean() throws IOException {
            int value = readUnsignedByte();
            if (value > 1) {
                throw format(context
                        + " contains an invalid boolean: "
                        + value);
            }
            return value == 1;
        }

        int readInt() throws IOException {
            require(Integer.BYTES);
            return readUnsignedByte() << 24
                    | readUnsignedByte() << 16
                    | readUnsignedByte() << 8
                    | readUnsignedByte();
        }

        long readLong() throws IOException {
            require(Long.BYTES);
            return (long) readInt() << 32
                    | Integer.toUnsignedLong(readInt());
        }

        int readVarInt() throws IOException {
            int value = 0;
            for (int shift = 0; shift < 35; shift += 7) {
                int current = readUnsignedByte();
                if (shift == 28
                        && (current & 0xF8) != 0) {
                    throw format(context
                            + " contains an overflowing varint");
                }
                value |= (current & 0x7F) << shift;
                if ((current & 0x80) == 0) {
                    if (shift != 0
                            && (current & 0x7F) == 0) {
                        throw format(context
                                + " contains a non-minimal varint");
                    }
                    return value;
                }
            }
            throw format(context
                    + " contains an unterminated varint");
        }

        int readCount(String label, int maximum)
                throws IOException {
            int count = readVarInt();
            if (count > maximum) {
                throw format(context + ' ' + label
                        + " count exceeds the limit: "
                        + count);
            }
            return count;
        }

        byte[] readBytes(int length) throws IOException {
            require(length);
            byte[] result = new byte[length];
            for (int index = 0;
                    index < length; index++) {
                result[index] =
                        (byte) source.readUnsignedByte();
            }
            return result;
        }

        void skip(int length) throws IOException {
            require(length);
            source.skip(length);
        }

        void skipRemaining() throws IOException {
            source.skip(end - source.position());
        }

        BoundedInput slice(int length, String label)
                throws ProjectIndexFormatException {
            require(length);
            return new BoundedInput(source, length, label);
        }

        void requireEnd()
                throws ProjectIndexFormatException {
            if (source.position() != end) {
                throw format(context
                        + " contains trailing bytes");
            }
        }

        private void require(long length)
                throws ProjectIndexFormatException {
            if (length < 0
                    || length > end - source.position()) {
                throw format(context
                        + " is truncated or has an invalid length");
            }
        }
    }

    record ScanResult(byte[] locatorBytes, long rereadBytes) {

        ScanResult {
            Objects.requireNonNull(locatorBytes, "locatorBytes");
            if (rereadBytes < 0) {
                throw new IllegalArgumentException(
                        "Project index reread bytes must not be negative");
            }
        }
    }

    private record Layout(List<DirectoryEntry> entries,
            List<ExpectedRange> expectedRanges) {
    }

    private record ExpectedRange(long offset, byte[] bytes) {
    }
}
