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

import java.nio.channels.SeekableByteChannel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntFunction;

final class ProjectIndexBaseReader {

    private ProjectIndexBaseReader() {
    }

    static ProjectIndexData read(byte[] bytes, long maxResidentBytes)
            throws ProjectIndexFormatException {
        AllocationBudget budget = newBudget(maxResidentBytes);
        budget.claim(32, "byte-array source wrapper");
        return read(ProjectIndexSource.of(Objects.requireNonNull(bytes, "bytes")), budget);
    }

    static ProjectIndexData read(SeekableByteChannel channel, long maxResidentBytes)
            throws ProjectIndexFormatException {
        return read(channel, maxResidentBytes, size -> new byte[size]);
    }

    static ProjectIndexData read(SeekableByteChannel channel, long maxResidentBytes,
            IntFunction<byte[]> pageAllocator) throws ProjectIndexFormatException {
        AllocationBudget budget = newBudget(maxResidentBytes);
        return read(ProjectIndexSource.of(Objects.requireNonNull(channel, "channel"), budget,
                pageAllocator), budget);
    }

    private static AllocationBudget newBudget(long maxResidentBytes) {
        if (maxResidentBytes <= 0) {
            throw new IllegalArgumentException("maxResidentBytes must be positive");
        }
        return new AllocationBudget(Math.min(MAX_DECODE_ALLOCATION, maxResidentBytes));
    }

    private static ProjectIndexData read(ProjectIndexSource source, AllocationBudget budget)
            throws ProjectIndexFormatException {
        budget.claim(4096, "decoder readers, directory containers, and checksum state");
        long fileSize = source.size();
        if (fileSize < HEADER_SIZE) {
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
        int expectedDirectoryCrc = header.readInt();
        int expectedHeaderCrc = header.readInt();
        if (source.crc32c(0, HEADER_SIZE - Integer.BYTES) != expectedHeaderCrc) {
            throw format("Project index header CRC mismatch");
        }
        if (major != FORMAT_MAJOR) {
            throw format("Unsupported project index version " + major + '.' + minor);
        }
        if (minor != FORMAT_MINOR) {
            throw format("Unsupported project index minor version " + major + '.' + minor);
        }
        if (generation < 0) {
            throw format("Project index generation must not be negative");
        }
        if (sectionCountLong != SectionType.values().length) {
            throw format("Invalid required section count: " + sectionCountLong);
        }
        int sectionCount = (int) sectionCountLong;
        long exactDirectoryLength = DIRECTORY_MAGIC.length
                + (long) sectionCount * DIRECTORY_ENTRY_SIZE + Integer.BYTES;
        if (directoryLengthLong != exactDirectoryLength) {
            throw format("Invalid section directory length: " + directoryLengthLong);
        }
        int directoryLength = (int) directoryLengthLong;
        if (directoryOffset < HEADER_SIZE) {
            throw format("Section directory overlaps the header");
        }
        long expectedLength;
        try {
            expectedLength = Math.addExact(Math.addExact(directoryOffset, directoryLength), FOOTER_SIZE);
        } catch (ArithmeticException ex) {
            throw format("Invalid section directory range", ex);
        }
        if (fileSize < expectedLength) {
            throw format("Project index has a truncated directory or footer");
        }
        if (fileSize > expectedLength) {
            throw format("Project index has trailing bytes after footer");
        }

        BinaryReader footer = new BinaryReader(source, fileSize - FOOTER_SIZE, FOOTER_SIZE,
                "footer", null);
        footer.requireMagic(FOOTER_MAGIC, "footer magic");
        long footerDirectoryOffset = footer.readLong();
        int expectedFooterCrc = footer.readInt();
        if (source.crc32c(fileSize - FOOTER_SIZE, FOOTER_SIZE - Integer.BYTES)
                != expectedFooterCrc) {
            throw format("Project index footer CRC mismatch");
        }
        if (footerDirectoryOffset != directoryOffset) {
            throw format("Project index footer directory offset mismatch");
        }

        BinaryReader directory = new BinaryReader(source, directoryOffset, directoryLength,
                "directory", null);
        directory.requireMagic(DIRECTORY_MAGIC, "directory magic");
        BinaryReader embeddedCrc = new BinaryReader(source,
                directoryOffset + directoryLength - Integer.BYTES, Integer.BYTES,
                "directory CRC", null);
        int embeddedDirectoryCrc = embeddedCrc.readInt();
        int actualDirectoryCrc = source.crc32c(
                directoryOffset, directoryLength - Integer.BYTES);
        if (actualDirectoryCrc != expectedDirectoryCrc || actualDirectoryCrc != embeddedDirectoryCrc) {
            throw format("Project index directory CRC mismatch");
        }

        EnumMap<SectionType, DirectoryEntry> entries = new EnumMap<>(SectionType.class);
        for (int i = 0; i < sectionCount; i++) {
            long entryPosition = directory.absolutePosition();
            int typeId = directory.readUnsignedByte();
            SectionType type;
            try {
                type = SectionType.fromId(typeId);
            } catch (IllegalArgumentException ex) {
                throw format("Unknown required section type: " + typeId, ex);
            }
            int flags = directory.readUnsignedByte();
            if (flags != 0 || directory.readUnsignedShort() != 0) {
                throw format(type + " section has unsupported flags or reserved bits");
            }
            long offset = directory.readLong();
            long payloadLengthLong = directory.readUnsignedInt();
            int payloadCrc = directory.readInt();
            if (payloadLengthLong > MAX_SECTION_BYTES) {
                throw format(type + " section exceeds the size limit");
            }
            DirectoryEntry previous = entries.put(type,
                    new DirectoryEntry(type, flags, offset, (int) payloadLengthLong,
                            payloadCrc, entryPosition));
            if (previous != null) {
                throw format("Duplicate required section " + type);
            }
        }
        directory.readInt();
        directory.requireEnd();
        if (entries.size() != SectionType.values().length) {
            throw format("A required project index section is missing");
        }

        validateSectionRanges(entries.values(), directoryOffset);
        for (DirectoryEntry entry : entries.values()) {
            validateSectionFrame(source, entry);
        }

        StringTable strings = readStrings(source, entries.get(SectionType.STRINGS), budget);
        ManifestCore manifestCore = readManifest(
                source, entries.get(SectionType.MANIFEST), strings, budget);
        if (manifestCore.formatMajor() != major || manifestCore.formatMinor() != minor
                || manifestCore.generation() != generation) {
            throw format("Manifest and header version or generation mismatch");
        }
        List<ProjectFileStamp> stamps = readFiles(
                source, entries.get(SectionType.FILES), strings, budget);
        List<PackedDefinition> definitions = readDefinitions(
                source, entries.get(SectionType.DEFINITIONS), strings, budget);
        List<PackedLocation> locations = readLocations(
                source, entries.get(SectionType.LOCATIONS), strings, budget);
        List<PathRange> ranges = readByPath(source, entries.get(SectionType.BY_PATH), strings,
                definitions.size(), locations.size(), budget);
        validateCanonicalRecordOrder(ranges, definitions, locations);
        List<UnresolvedRecord> unresolved = readUnresolved(
                source, entries.get(SectionType.UNRESOLVED_FILES), strings, budget);

        List<FileContribution> files = rebuildContributions(
                ranges, definitions, locations, unresolved, budget);
        ProjectIndexManifest manifest;
        try {
            budget.claim(128 + arrayBytes(32, Byte.BYTES),
                    "MANIFEST object and digest clone");
            manifest = new ProjectIndexManifest(manifestCore.formatMajor(),
                    manifestCore.formatMinor(), manifestCore.parserAbi(), manifestCore.coreVersion(),
                    manifestCore.uiVersion(), manifestCore.databaseType(),
                    manifestCore.projectIdentity(), manifestCore.configSha256(),
                    manifestCore.generation(), stamps);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw format("Invalid MANIFEST values", ex);
        }
        ProjectIndexData result;
        budget.claim(96, "project index result object");
        try (var ignored = budget.reserve(2 * setBytes(files.size()),
                "project index validation sets")) {
            result = new ProjectIndexData(manifest, files);
        } catch (IllegalArgumentException ex) {
            throw format("Inconsistent manifest and file contributions", ex);
        }

        // Validate derived sections directly from the channel. Rebuilding them here would retain
        // a second object graph that the reference full decoder does not return or consume.
        validateByMatchKey(source, entries.get(SectionType.BY_MATCH_KEY), strings,
                definitions, locations, budget);
        validateCompletion(source, entries.get(SectionType.COMPLETION_TRIGRAMS), strings,
                definitions, budget);
        validateReverseDependencies(source, entries.get(SectionType.REVERSE_DEPENDENCIES),
                strings, locations, budget);
        strings.requireExactDecodedCoverage();
        return result;
    }

    private static void validateSectionRanges(Iterable<DirectoryEntry> values, long directoryOffset)
            throws ProjectIndexFormatException {
        List<DirectoryEntry> entries = new ArrayList<>();
        values.forEach(entries::add);
        for (DirectoryEntry entry : entries) {
            long end;
            try {
                end = Math.addExact(Math.addExact(entry.offset(), SECTION_FRAME_SIZE),
                        entry.payloadLength());
            } catch (ArithmeticException ex) {
                throw format(entry.type() + " section range overflows", ex);
            }
            if (entry.offset() < HEADER_SIZE) {
                throw format(entry.type() + " section overlaps the header");
            }
            if (end > directoryOffset) {
                throw format(entry.type() + " section crosses directory boundary");
            }
        }
        entries.sort(Comparator.comparingLong(DirectoryEntry::offset));
        long previousEnd = HEADER_SIZE;
        for (DirectoryEntry entry : entries) {
            long end = entry.offset() + SECTION_FRAME_SIZE + entry.payloadLength();
            if (entry.offset() < previousEnd) {
                throw format("Project index sections overlap near " + entry.type());
            }
            if (entry.offset() > previousEnd) {
                throw format("Project index contains an unaccounted gap before " + entry.type());
            }
            previousEnd = end;
        }
        if (previousEnd != directoryOffset) {
            throw format("Project index contains an unaccounted gap before the directory");
        }
    }

    private static void validateSectionFrame(ProjectIndexSource source, DirectoryEntry entry)
            throws ProjectIndexFormatException {
        BinaryReader frame = new BinaryReader(source, entry.offset(), SECTION_FRAME_SIZE,
                entry.type() + " frame", null);
        if (frame.readUnsignedByte() != entry.type().id()) {
            throw format(entry.type() + " section frame type mismatch");
        }
        if (frame.readUnsignedByte() != entry.flags() || frame.readUnsignedShort() != 0) {
            throw format(entry.type() + " section frame flags mismatch");
        }
        if (frame.readUnsignedInt() != Integer.toUnsignedLong(entry.payloadLength())) {
            throw format(entry.type() + " section frame length mismatch");
        }
        if (frame.readInt() != entry.payloadCrc()) {
            throw format(entry.type() + " section frame CRC field mismatch");
        }
        long payloadOffset = entry.offset() + SECTION_FRAME_SIZE;
        if (source.crc32c(payloadOffset, entry.payloadLength()) != entry.payloadCrc()) {
            throw format(entry.type() + " section payload CRC mismatch");
        }
    }
}
