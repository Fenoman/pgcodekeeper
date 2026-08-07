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
import static ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexRecords.*;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;

import org.pgcodekeeper.core.database.api.schema.DbObjType;

import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexFormat.AllocationBudget;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexFormat.BinaryReader;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexLocator.BlockEntry;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexLocator.FileEntry;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexLocator.FileRange;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexLocator.PackedBlocks;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexLocator.SearchWindow;

/**
 * A disk-backed view of one immutable project-index container.
 *
 * Opening this class reads only the container header and its bounded locator.
 * Canonical codec blocks, strings and records are read and checksummed on first
 * use through {@link ProjectIndexBlockCache}.
 */
final class LazyProjectIndexBase {

    private final CachedProjectIndexSource source;
    private final ProjectIndexLocator locator;
    private final LazyDiskStrings diskStrings;
    private final StringTable strings;
    private final RecordSection<PackedDefinition> definitions;
    private final RecordSection<PackedLocation> locations;
    private final long codecOffset;
    private volatile List<ProjectFileStamp> stamps;

    private LazyProjectIndexBase(CachedProjectIndexSource source,
            ProjectIndexLocator locator, long codecOffset) {
        this.source = source;
        this.locator = locator;
        this.codecOffset = codecOffset;
        diskStrings = new LazyDiskStrings(source, locator.blocks(SectionType.STRINGS));
        strings = StringTable.lazy(diskStrings);
        definitions = new RecordSection<>(source,
                locator.blocks(SectionType.DEFINITIONS),
                input -> readDefinition(input, strings));
        locations = new RecordSection<>(source,
                locator.blocks(SectionType.LOCATIONS),
                input -> readLocation(input, strings));
    }

    static LazyProjectIndexBase open(FileChannel channel, long offset, long length,
            ProjectIndexBlockCache cache) throws IOException {
        ProjectIndexContainer.Opened container =
                ProjectIndexContainer.open(channel, offset, length);
        CachedProjectIndexSource source = new CachedProjectIndexSource(channel, cache,
                container.codecOffset(), container.codecLength(), container.locator());
        return new LazyProjectIndexBase(source, container.locator(),
                container.codecOffset());
    }

    ProjectIndexManifest manifest() {
        return locator.coreManifest();
    }

    List<ProjectFileStamp> stamps() throws ProjectIndexFormatException {
        List<ProjectFileStamp> result = stamps;
        if (result != null) {
            return result;
        }
        synchronized (this) {
            result = stamps;
            if (result == null) {
                AllocationBudget budget =
                        new AllocationBudget(MAX_DECODE_ALLOCATION);
                try (var ignored = diskStrings.openScope(budget)) {
                    budget.claim(listBytes(locator.fileCount(), 2)
                            + (long) locator.fileCount() * 224,
                            "project index file stamps");
                    List<ProjectFileStamp> decoded =
                            new ArrayList<>(locator.fileCount());
                    IndexPathRef previous = null;
                    for (int i = 0; i < locator.fileCount(); i++) {
                        FileEntry file = locator.file(i);
                        IndexPathRef path =
                                decodePath(file.origin(), file.pathId());
                        if (previous != null
                                && PATH_ORDER.compare(previous, path) >= 0) {
                            throw format(
                                    "Project index locator file paths are not sorted");
                        }
                        previous = path;
                        try {
                            decoded.add(new ProjectFileStamp(path,
                                    file.eclipseStamp(), file.size(),
                                    file.modified(), file.sha256()));
                        } catch (IllegalArgumentException ex) {
                            throw format(
                                    "Invalid project index file metadata", ex);
                        }
                    }
                    result = List.copyOf(decoded);
                }
                stamps = result;
            }
        }
        return result;
    }

    FileContribution contribution(IndexPathRef path)
            throws ProjectIndexFormatException {
        int pathId = diskStrings.findOptional(path.relativePath());
        if (pathId < 0) {
            return null;
        }
        int fileIndex = locator.findFile(path.origin().ordinal(), pathId);
        if (fileIndex < 0) {
            return null;
        }
        FileRange file = locator.fileRange(fileIndex);
        AllocationBudget budget = new AllocationBudget(MAX_DECODE_ALLOCATION);
        try (var ignored = diskStrings.openScope(budget)) {
            List<PackedDefinition> fileDefinitions = definitions.readRange(
                    file.definitionStart(), file.definitionCount(), budget);
            List<PackedLocation> fileLocations = locations.readRange(
                    file.locationStart(), file.locationCount(), budget);
            UnresolvedRecord unresolved = unresolved(
                    new RawPath(path.origin().ordinal(), pathId), budget);
            try {
                return new FileContribution(path, fileDefinitions, fileLocations,
                        unresolved.candidates(), unresolved.any());
            } catch (IllegalArgumentException ex) {
                throw format("Project index file contribution is inconsistent",
                        ex);
            }
        }
    }

    ProjectIndexFileMetadata file(IndexPathRef path)
            throws ProjectIndexFormatException {
        AllocationBudget budget =
                new AllocationBudget(MAX_DECODE_ALLOCATION);
        try (var ignored = diskStrings.openScope(budget)) {
            int pathId = diskStrings.findOptional(
                    path.relativePath());
            if (pathId < 0) {
                return null;
            }
            int fileIndex = locator.findFile(
                    path.origin().ordinal(), pathId);
            if (fileIndex < 0) {
                return null;
            }
            FileEntry file = locator.file(fileIndex);
            IndexPathRef storedPath =
                    decodePath(file.origin(), file.pathId());
            if (!path.equals(storedPath)) {
                throw format(
                        "Project index locator path lookup is inconsistent");
            }
            FileRange range = locator.fileRange(fileIndex);
            List<PackedDefinition> fileDefinitions =
                    definitions.readRange(
                            range.definitionStart(),
                            range.definitionCount(), budget);
            try {
                ProjectFileStamp stamp = new ProjectFileStamp(
                        storedPath, file.eclipseStamp(),
                        file.size(), file.modified(), file.sha256());
                return new ProjectIndexFileMetadata(
                        stamp, fileDefinitions);
            } catch (IllegalArgumentException ex) {
                throw format(
                        "Project index file metadata is inconsistent", ex);
            }
        }
    }

    void forEachContribution(ProjectIndexView.ContributionConsumer consumer)
            throws IOException {
        List<ProjectFileStamp> fileStamps = stamps();
        RecordSection<PackedDefinition>.SequentialReader definitionReader =
                definitions.sequentialReader();
        RecordSection<PackedLocation>.SequentialReader locationReader =
                locations.sequentialReader();
        SequentialUnresolvedReader unresolvedReader =
                new SequentialUnresolvedReader();
        try (var ignored = diskStrings.openReplayScope()) {
            for (int i = 0; i < fileStamps.size(); i++) {
                ProjectFileStamp stamp = fileStamps.get(i);
                FileRange range = locator.fileRange(i);
                if (range.definitionStart() != definitionReader.position()
                        || range.locationStart() != locationReader.position()) {
                    throw format(
                            "Project index sequential ranges are incomplete");
                }
                AllocationBudget budget =
                        new AllocationBudget(MAX_DECODE_ALLOCATION);
                List<PackedDefinition> fileDefinitions =
                        definitionReader.readNext(
                                range.definitionCount(), budget);
                List<PackedLocation> fileLocations =
                        locationReader.readNext(range.locationCount(), budget);
                UnresolvedRecord unresolved =
                        unresolvedReader.forPath(stamp.path(),
                                locator.filePathId(i), budget);
                try {
                    consumer.accept(new FileContribution(stamp.path(),
                            fileDefinitions, fileLocations,
                            unresolved.candidates(), unresolved.any()));
                } catch (IllegalArgumentException ex) {
                    throw format(
                            "Project index file contribution is inconsistent",
                            ex);
                }
            }
            definitionReader.requireEnd();
            locationReader.requireEnd();
            unresolvedReader.requireEnd();
        }
    }

    ProjectIndexMatches matches(ReferenceMatchKey wanted)
            throws ProjectIndexFormatException {
        RawMatchKey rawWanted = rawKey(wanted);
        if (rawWanted == null) {
            return ProjectIndexMatches.EMPTY;
        }
        SearchWindow window = locator.match().window(rawWanted);
        BinaryReader input = windowReader(window, "BY_MATCH_KEY", null);
        for (int i = 0; i < window.recordCount(); i++) {
            RawMatchKey key = readRawMatchKey(input);
            int comparison = key.compareTo(rawWanted);
            if (comparison > 0) {
                return ProjectIndexMatches.EMPTY;
            }
            if (comparison == 0) {
                AllocationBudget budget = new AllocationBudget(MAX_DECODE_ALLOCATION);
                int[] definitionIds = readIds(input, definitions.recordCount(),
                        "definition", true, budget);
                int[] locationIds = readIds(input, locations.recordCount(),
                        "location", true, budget);
                try (var ignored = diskStrings.openScope(budget)) {
                    return new ProjectIndexMatches(
                            definitions.readIds(definitionIds, budget),
                            locations.readIds(locationIds, budget));
                }
            }
            readIds(input, definitions.recordCount(), "definition", false, null);
            readIds(input, locations.recordCount(), "location", false, null);
        }
        return ProjectIndexMatches.EMPTY;
    }

    /**
     * How many files this container records as possibly holding an unresolved
     * reference. The UNRESOLVED_FILES section carries only those files, so its
     * record count answers the question without reading a byte of payload.
     */
    int unresolvedFileCount() {
        return locator.unresolved().recordCount();
    }

    List<PackedDefinition> definitions(ProjectIndexDefinitionSubject subject)
            throws ProjectIndexFormatException {
        return definitions(subject, MAX_DECODE_ALLOCATION);
    }

    List<PackedDefinition> definitions(ProjectIndexDefinitionSubject subject,
            long allocationLimit)
            throws ProjectIndexFormatException {
        if (allocationLimit <= 0
                || allocationLimit > MAX_DECODE_ALLOCATION) {
            throw new IllegalArgumentException(
                    "Definition subject allocation limit is invalid");
        }
        RawMatchKey lower = rawSubject(subject);
        if (lower == null) {
            return List.of();
        }
        SearchWindow window = locator.match().tail(lower);
        BinaryReader input = windowReader(window, "BY_MATCH_KEY", null);
        AllocationBudget budget =
                new AllocationBudget(allocationLimit);
        SelectedIds selected = new SelectedIds();
        try (var ignored = diskStrings.openScope(budget)) {
            for (int i = 0; i < window.recordCount(); i++) {
                RawMatchKey key = readRawMatchKey(input);
                int comparison = key.compareTo(lower);
                if (comparison >= 0 && !matchesSubject(key, subject, lower)) {
                    break;
                }
                if (comparison < 0) {
                    readIds(input, definitions.recordCount(),
                            "definition", false, null);
                    readIds(input, locations.recordCount(),
                            "location", false, null);
                    continue;
                }
                readSelectedIds(input, definitions.recordCount(),
                        "definition", selected, budget);
                readIds(input, locations.recordCount(),
                        "location", false, null);
            }
            selected.sortAndRequireUnique();
            budget.claim(listBytes(selected.size(), 2),
                    "sorted definition subject result");
            List<PackedDefinition> result = new ArrayList<>(
                    definitions.readIds(
                            selected.values(), selected.size(), budget));
            result.sort(DEFINITION_ORDER);
            return List.copyOf(result);
        }
    }

    List<PackedDefinition> completion(String requestedTrigram)
            throws ProjectIndexFormatException {
        String wanted = requestedTrigram.toUpperCase(Locale.ROOT);
        int wantedId = diskStrings.findOptional(wanted);
        if (wantedId < 0) {
            return List.of();
        }
        SearchWindow window = locator.completion().window(wantedId);
        BinaryReader input = windowReader(window, "COMPLETION_TRIGRAMS", null);
        for (int i = 0; i < window.recordCount(); i++) {
            int key = readRequiredStringId(input);
            if (key > wantedId) {
                return List.of();
            }
            if (key == wantedId) {
                AllocationBudget budget = new AllocationBudget(MAX_DECODE_ALLOCATION);
                int[] ids = readIds(input, definitions.recordCount(),
                        "definition", true, budget);
                try (var ignored = diskStrings.openScope(budget)) {
                    return definitions.readIds(ids, budget);
                }
            }
            readIds(input, definitions.recordCount(), "definition", false, null);
        }
        return List.of();
    }

    Set<IndexPathRef> reverseDependencies(ReferenceMatchKey wanted)
            throws ProjectIndexFormatException {
        RawMatchKey rawWanted = rawKey(wanted);
        if (rawWanted == null) {
            return Set.of();
        }
        SearchWindow window = locator.reverse().window(rawWanted);
        BinaryReader input = windowReader(window, "REVERSE_DEPENDENCIES", null);
        for (int i = 0; i < window.recordCount(); i++) {
            RawMatchKey key = readRawMatchKey(input);
            int comparison = key.compareTo(rawWanted);
            if (comparison > 0) {
                return Set.of();
            }
            int count = input.readCount("dependent path", MAX_RECORD_COUNT, 0);
            if (count > locator.fileCount()) {
                throw format("Reverse dependency path count exceeds the file count");
            }
            if (comparison == 0) {
                AllocationBudget budget = new AllocationBudget(MAX_DECODE_ALLOCATION);
                try (var ignored = diskStrings.openScope(budget)) {
                    budget.claim(setBytes(count), "reverse dependency paths");
                    Set<IndexPathRef> result = new LinkedHashSet<>();
                    RawPath previous = null;
                    for (int j = 0; j < count; j++) {
                        RawPath path = readRawPath(input);
                        requireOrdered(previous, path,
                                "REVERSE_DEPENDENCIES paths");
                        previous = path;
                        result.add(decodePath(path.origin(), path.pathId()));
                    }
                    return Collections.unmodifiableSet(result);
                }
            }
            for (int j = 0; j < count; j++) {
                readRawPath(input);
            }
        }
        return Set.of();
    }

    long metadataBytes() {
        return locator.metadataBytes();
    }

    int codecBlockCount() {
        return locator.codecBlockCount();
    }

    int fileCount() {
        return locator.fileCount();
    }

    long codecOffsetForTests() {
        return codecOffset;
    }

    long firstDefinitionBlockOffsetForTests() {
        PackedBlocks blocks = locator.blocks(SectionType.DEFINITIONS);
        return blocks.blockCount() == 0 ? -1 : codecOffset + blocks.block(0).offset();
    }

    int definitionBlockCountForTests() {
        return definitions.blockCount();
    }

    int residentStringBlocksForTests() {
        return diskStrings.residentBlockCount();
    }

    long definitionBlockTraversals() {
        return definitions.blockTraversals();
    }

    /**
     * What turning names into dictionary identifiers, and identifiers back
     * into names, has cost this index since it was opened.
     *
     * <p>Counted since opening rather than since a caller began, exactly like
     * {@link #definitionBlockTraversals()}: a caller that wants its own share
     * has to take a reading of its own first and subtract it.
     *
     * @return the running totals
     */
    ProjectIndexStringProbes stringProbes() {
        return diskStrings.probes();
    }

    void close() {
        source.closeCacheEntries();
        diskStrings.releaseBlocks();
    }

    private UnresolvedRecord unresolved(RawPath wanted, AllocationBudget budget)
            throws ProjectIndexFormatException {
        SearchWindow window = locator.unresolved().window(wanted);
        BinaryReader input = windowReader(window, "UNRESOLVED_FILES", budget);
        for (int i = 0; i < window.recordCount(); i++) {
            RawPath path = readRawPath(input);
            int comparison = path.compareTo(wanted);
            if (comparison > 0) {
                return UnresolvedRecord.EMPTY;
            }
            boolean any = input.readBoolean();
            int count = input.readCount("unresolved candidate",
                    MAX_RECORD_COUNT, 0);
            if (!any && count == 0) {
                throw format("UNRESOLVED_FILES contains an empty record");
            }
            if (comparison == 0) {
                budget.claim(setBytes(count), "unresolved candidate keys");
                Set<ReferenceMatchKey> result = new LinkedHashSet<>();
                RawMatchKey previous = null;
                for (int j = 0; j < count; j++) {
                    RawMatchKey key = readRawMatchKey(input);
                    requireOrdered(previous, key,
                            "UNRESOLVED_FILES candidates");
                    previous = key;
                    result.add(decodeMatchKey(key));
                }
                return new UnresolvedRecord(Collections.unmodifiableSet(result), any);
            }
            for (int j = 0; j < count; j++) {
                readRawMatchKey(input);
            }
        }
        return UnresolvedRecord.EMPTY;
    }

    private final class SequentialUnresolvedReader {
        private final BinaryReader input;
        private final int recordCount;
        private int recordsRead;
        private RawPath previousPath;
        private RawPath pendingPath;
        private boolean pendingAny;
        private int pendingCandidateCount;

        private SequentialUnresolvedReader()
                throws ProjectIndexFormatException {
            ProjectIndexLocator.SparseIndex index = locator.unresolved();
            input = new BinaryReader(new ReplayProjectIndexSource(source),
                    index.payloadOffset(), index.payloadLength(),
                    "UNRESOLVED_FILES sequential replay", null);
            recordCount = input.readCount(
                    "unresolved file", MAX_RECORD_COUNT, 0);
            if (recordCount != index.recordCount()) {
                throw format(
                        "UNRESOLVED_FILES record count does not match its locator");
            }
        }

        private UnresolvedRecord forPath(IndexPathRef wanted, int wantedPathId,
                AllocationBudget budget)
                throws ProjectIndexFormatException {
            ensurePending();
            if (pendingPath == null) {
                return UnresolvedRecord.EMPTY;
            }
            RawPath rawWanted = new RawPath(
                    wanted.origin().ordinal(), wantedPathId);
            int comparison = pendingPath.compareTo(rawWanted);
            if (comparison < 0) {
                throw format(
                        "UNRESOLVED_FILES contains a path outside FILES");
            }
            if (comparison > 0) {
                return UnresolvedRecord.EMPTY;
            }
            budget.claim(setBytes(pendingCandidateCount),
                    "sequential unresolved candidate keys");
            Set<ReferenceMatchKey> candidates = new LinkedHashSet<>();
            RawMatchKey previous = null;
            for (int i = 0; i < pendingCandidateCount; i++) {
                RawMatchKey key = readRawMatchKey(input);
                requireOrdered(previous, key,
                        "UNRESOLVED_FILES candidates");
                previous = key;
                candidates.add(decodeMatchKey(key));
            }
            UnresolvedRecord result = new UnresolvedRecord(
                    Collections.unmodifiableSet(candidates), pendingAny);
            pendingPath = null;
            pendingCandidateCount = 0;
            return result;
        }

        private void ensurePending()
                throws ProjectIndexFormatException {
            if (pendingPath != null || recordsRead >= recordCount) {
                return;
            }
            RawPath path = readRawPath(input);
            requireOrdered(previousPath, path,
                    "UNRESOLVED_FILES paths");
            previousPath = path;
            pendingPath = path;
            pendingAny = input.readBoolean();
            pendingCandidateCount = input.readCount(
                    "unresolved candidate", MAX_RECORD_COUNT, 0);
            if (!pendingAny && pendingCandidateCount == 0) {
                throw format(
                        "UNRESOLVED_FILES contains an empty record");
            }
            recordsRead++;
        }

        private void requireEnd()
                throws ProjectIndexFormatException {
            ensurePending();
            if (pendingPath != null || recordsRead != recordCount) {
                throw format(
                        "UNRESOLVED_FILES contains a path outside FILES");
            }
            input.requireEnd();
        }
    }

    private RawMatchKey rawKey(ReferenceMatchKey key)
            throws ProjectIndexFormatException {
        int schema = diskStrings.findNullable(key.schema());
        int table = diskStrings.findNullable(key.table());
        int column = diskStrings.findNullable(key.column());
        int alias = diskStrings.findNullable(key.alias());
        if (schema < 0 || table < 0 || column < 0 || alias < 0) {
            return null;
        }
        return new RawMatchKey(key.family().ordinal(),
                key.exactType() == null ? 0 : key.exactType().ordinal() + 1,
                schema, table, column, alias, key.global() ? 1 : 0);
    }

    private RawMatchKey rawSubject(ProjectIndexDefinitionSubject subject)
            throws ProjectIndexFormatException {
        int schema = diskStrings.findNullable(subject.schema());
        int object = diskStrings.findNullable(subject.objectName());
        if (schema < 0 || object < 0) {
            return null;
        }
        return new RawMatchKey(subject.family().ordinal(),
                subject.exactType() == null
                        ? 0 : subject.exactType().ordinal() + 1,
                schema, object, 0, 0, 0);
    }

    private static boolean matchesSubject(RawMatchKey key,
            ProjectIndexDefinitionSubject subject, RawMatchKey lower) {
        if (key.family() != lower.family()
                || key.exact() != lower.exact()) {
            return false;
        }
        if (subject.schema() != null && key.schema() != lower.schema()) {
            return false;
        }
        return subject.objectName() == null
                || key.table() == lower.table();
    }

    private RawMatchKey readRawMatchKey(BinaryReader input)
            throws ProjectIndexFormatException {
        int family = input.readVarInt();
        int exact = input.readVarInt();
        if (family >= ReferenceMatchKey.MatchFamily.values().length
                || exact > DbObjType.values().length
                || (family == ReferenceMatchKey.MatchFamily.EXACT.ordinal())
                        != (exact != 0)) {
            throw format("Invalid reference match key");
        }
        return new RawMatchKey(family, exact,
                readNullableStringId(input), readNullableStringId(input),
                readNullableStringId(input), readNullableStringId(input),
                input.readBoolean() ? 1 : 0);
    }

    private ReferenceMatchKey decodeMatchKey(RawMatchKey key)
            throws ProjectIndexFormatException {
        DbObjType exact = key.exact() == 0 ? null
                : DbObjType.values()[key.exact() - 1];
        try {
            return new ReferenceMatchKey(
                    ReferenceMatchKey.MatchFamily.values()[key.family()], exact,
                    strings.value(key.schema()), strings.value(key.table()),
                    strings.value(key.column()), strings.value(key.alias()),
                    key.global() != 0);
        } catch (IllegalArgumentException ex) {
            throw format("Invalid reference match key", ex);
        }
    }

    private RawPath readRawPath(BinaryReader input)
            throws ProjectIndexFormatException {
        int origin = input.readVarInt();
        if (origin >= IndexPathOrigin.values().length) {
            throw format("Invalid project index path origin");
        }
        return new RawPath(origin, readRequiredStringId(input));
    }

    private IndexPathRef decodePath(int origin, int pathId)
            throws ProjectIndexFormatException {
        if (origin < 0 || origin >= IndexPathOrigin.values().length) {
            throw format("Invalid project index path origin");
        }
        String path = strings.value(pathId);
        diskStrings.claim(96 + arrayBytes(path.length(), Character.BYTES),
                "decoded project index path");
        try {
            IndexPathRef result = new IndexPathRef(
                    IndexPathOrigin.values()[origin], path);
            if (!path.equals(result.relativePath())) {
                throw new IllegalArgumentException("non-canonical path");
            }
            return result;
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw format("Invalid project index path", ex);
        }
    }

    private int readRequiredStringId(BinaryReader input)
            throws ProjectIndexFormatException {
        int id = input.readVarInt();
        if (id <= 0 || id > diskStrings.size()) {
            throw format("Invalid required string id: " + id);
        }
        return id;
    }

    private int readNullableStringId(BinaryReader input)
            throws ProjectIndexFormatException {
        int id = input.readVarInt();
        if (id < 0 || id > diskStrings.size()) {
            throw format("Invalid string id: " + id);
        }
        return id;
    }

    private BinaryReader windowReader(SearchWindow window, String label,
            AllocationBudget budget) throws ProjectIndexFormatException {
        long length = window.sectionEnd() - window.offset();
        if (length < 0 || length > MAX_SECTION_BYTES) {
            throw format(label + " sparse search window is invalid");
        }
        return new BinaryReader(source, window.offset(), (int) length,
                label + " sparse window", budget);
    }

    private static int[] readIds(BinaryReader input, int upperBound, String label,
            boolean retain, AllocationBudget budget)
            throws ProjectIndexFormatException {
        int count = input.readCount(label + " id", MAX_RECORD_COUNT, 0);
        if (count > upperBound) {
            throw format("Project index " + label + " id count exceeds its section");
        }
        int[] result = retain ? new int[count] : null;
        if (retain) {
            budget.claim(arrayBytes(count, Integer.BYTES),
                    label + " id array");
        }
        int previous = -1;
        for (int i = 0; i < count; i++) {
            int delta = input.readVarInt();
            long id = (long) previous + delta + 1;
            if (id < 0 || id >= upperBound) {
                throw format("Invalid " + label + " id: " + id);
            }
            previous = (int) id;
            if (retain) {
                result[i] = previous;
            }
        }
        return retain ? result : EMPTY_IDS;
    }

    private static void readSelectedIds(BinaryReader input, int upperBound,
            String label, SelectedIds selected, AllocationBudget budget)
            throws ProjectIndexFormatException {
        int count = input.readCount(label + " id", MAX_RECORD_COUNT, 0);
        selected.ensureAdditional(count, upperBound, budget);
        int previous = -1;
        for (int i = 0; i < count; i++) {
            int delta = input.readVarInt();
            long id = (long) previous + delta + 1;
            if (id < 0 || id >= upperBound) {
                throw format("Invalid " + label + " id: " + id);
            }
            previous = (int) id;
            selected.add(previous);
        }
    }

    private static <T extends Comparable<T>> void requireOrdered(
            T previous, T current, String label)
            throws ProjectIndexFormatException {
        if (previous != null && previous.compareTo(current) >= 0) {
            throw format(label + " are not strictly sorted");
        }
    }

    private static final int[] EMPTY_IDS = new int[0];

    private static final class SelectedIds {
        private int[] values = EMPTY_IDS;
        private int size;

        int[] values() {
            return values;
        }

        int size() {
            return size;
        }

        void ensureAdditional(int count, int upperBound,
                AllocationBudget budget)
                throws ProjectIndexFormatException {
            if (count < 0 || count > upperBound - size) {
                throw format(
                        "Project index definition subject contains too many ids");
            }
            int required = size + count;
            if (required <= values.length) {
                return;
            }
            int capacity = Math.max(required,
                    Math.min(upperBound,
                            Math.max(16, values.length * 2)));
            budget.claim(arrayBytes(capacity, Integer.BYTES),
                    "definition subject ids");
            values = Arrays.copyOf(values, capacity);
        }

        void add(int id) {
            values[size++] = id;
        }

        void sortAndRequireUnique()
                throws ProjectIndexFormatException {
            Arrays.sort(values, 0, size);
            for (int i = 1; i < size; i++) {
                if (values[i] == values[i - 1]) {
                    throw format(
                            "Project index definition subject contains duplicate ids");
                }
            }
        }
    }

    private record UnresolvedRecord(
            Set<ReferenceMatchKey> candidates, boolean any) {
        private static final UnresolvedRecord EMPTY =
                new UnresolvedRecord(Set.of(), false);
    }

    @FunctionalInterface
    private interface Decoder<T> {
        T decode(BinaryReader input) throws ProjectIndexFormatException;
    }

    private static final class RecordSection<T> {
        private final ProjectIndexSource source;
        private final PackedBlocks blocks;
        private final Decoder<T> decoder;
        private final LongAdder blockTraversals = new LongAdder();

        private RecordSection(ProjectIndexSource source, PackedBlocks blocks,
                Decoder<T> decoder) {
            this.source = source;
            this.blocks = blocks;
            this.decoder = decoder;
        }

        int recordCount() {
            return blocks.recordCount();
        }

        int blockCount() {
            return blocks.blockCount();
        }

        long blockTraversals() {
            return blockTraversals.sum();
        }

        List<T> readRange(int start, int count, AllocationBudget budget)
                throws ProjectIndexFormatException {
            if (start < 0 || count < 0 || start > recordCount() - count) {
                throw format("Project index record range is outside its section");
            }
            if (count == 0) {
                return List.of();
            }
            budget.claim(listBytes(count, 2), "project index record result");
            List<T> values = new ArrayList<>(count);
            int end = start + count;
            int blockIndex = blocks.findBlock(start);
            if (blockIndex < 0) {
                throw format("Project index record range has no block");
            }
            while (blockIndex < blocks.blockCount()) {
                BlockEntry block = blocks.block(blockIndex++);
                if (block.startRecord() >= end) {
                    break;
                }
                decodeBlock(block, start, end, null, 0, 0, values, budget);
            }
            if (values.size() != count) {
                throw format("Project index record range is incomplete");
            }
            return List.copyOf(values);
        }

        List<T> readIds(int[] ids, AllocationBudget budget)
                throws ProjectIndexFormatException {
            return readIds(ids, ids.length, budget);
        }

        List<T> readIds(int[] ids, int count, AllocationBudget budget)
                throws ProjectIndexFormatException {
            if (count < 0 || count > ids.length) {
                throw format("Project index record id count is invalid");
            }
            if (count == 0) {
                return List.of();
            }
            int previous = -1;
            for (int i = 0; i < count; i++) {
                int id = ids[i];
                if (id <= previous || id < 0 || id >= recordCount()) {
                    throw format("Project index record ids are invalid");
                }
                previous = id;
            }
            budget.claim(listBytes(count, 2),
                    "project index selected record result");
            List<T> values = new ArrayList<>(count);
            int selected = 0;
            int blockIndex = blocks.findBlock(ids[0]);
            if (blockIndex < 0) {
                throw format("Project index record id has no block");
            }
            while (selected < count && blockIndex < blocks.blockCount()) {
                BlockEntry block = blocks.block(blockIndex++);
                if (ids[selected] < block.startRecord()) {
                    throw format("Project index record id is not covered by a block");
                }
                int end = selected;
                while (end < count && ids[end] < block.endRecord()) {
                    end++;
                }
                if (end > selected) {
                    decodeBlock(block, -1, -1, ids, selected, end,
                            values, budget);
                    selected = end;
                }
            }
            if (values.size() != count) {
                throw format("Project index selected records are incomplete");
            }
            return List.copyOf(values);
        }

        SequentialReader sequentialReader() {
            return new SequentialReader();
        }

        final class SequentialReader {
            private final ProjectIndexSource replaySource =
                    new ReplayProjectIndexSource(source);
            private int position;
            private int blockIndex;
            private int blockRecordsRead;
            private BlockEntry currentBlock;
            private BinaryReader blockInput;

            int position() {
                return position;
            }

            List<T> readNext(int count, AllocationBudget budget)
                    throws ProjectIndexFormatException {
                if (count < 0 || count > recordCount() - position) {
                    throw format(
                            "Project index sequential record range is outside its section");
                }
                if (count == 0) {
                    return List.of();
                }
                budget.claim(listBytes(count, 2),
                        "sequential project index records");
                List<T> values = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    ensureBlock();
                    int id = position;
                    int recordLength = blockInput.readVarInt();
                    if (recordLength > MAX_RECORD_BYTES) {
                        throw format(
                                "Project index record exceeds the size limit");
                    }
                    BinaryReader record = blockInput.slice(recordLength,
                            "sequential project index record " + id,
                            budget);
                    values.add(decoder.decode(record));
                    record.requireEnd();
                    position++;
                    blockRecordsRead++;
                    if (blockRecordsRead
                            == currentBlock.recordCount()) {
                        blockInput.requireEnd();
                        currentBlock = null;
                        blockInput = null;
                    }
                }
                return List.copyOf(values);
            }

            void requireEnd()
                    throws ProjectIndexFormatException {
                if (position != recordCount() || currentBlock != null
                        || blockIndex != blocks.blockCount()) {
                    throw format(
                            "Project index sequential record replay is incomplete");
                }
            }

            private void ensureBlock()
                    throws ProjectIndexFormatException {
                if (currentBlock != null) {
                    return;
                }
                if (blockIndex >= blocks.blockCount()) {
                    throw format(
                            "Project index sequential record block is missing");
                }
                currentBlock = blocks.block(blockIndex++);
                if (currentBlock.startRecord() != position) {
                    throw format(
                            "Project index sequential record blocks are not contiguous");
                }
                blockRecordsRead = 0;
                blockInput = new BinaryReader(replaySource,
                        currentBlock.offset(), currentBlock.length(),
                        "sequential project index record block", null);
            }
        }

        private void decodeBlock(BlockEntry block, int start, int end,
                int[] selectedIds, int selectedFrom, int selectedTo,
                List<T> values, AllocationBudget budget)
                throws ProjectIndexFormatException {
            blockTraversals.increment();
            BinaryReader input = new BinaryReader(source, block.offset(),
                    block.length(), "project index record block", budget);
            int selectedIndex = selectedFrom;
            for (int relative = 0; relative < block.recordCount(); relative++) {
                int id = block.startRecord() + relative;
                int recordLength = input.readVarInt();
                if (recordLength > MAX_RECORD_BYTES) {
                    throw format("Project index record exceeds the size limit");
                }
                BinaryReader record = input.slice(recordLength,
                        "project index record " + id);
                boolean selected = selectedIds == null
                        ? id >= start && id < end
                        : selectedIndex < selectedTo
                                && selectedIds[selectedIndex] == id;
                if (selected) {
                    values.add(decoder.decode(record));
                    record.requireEnd();
                    if (selectedIds != null) {
                        selectedIndex++;
                    }
                }
            }
            input.requireEnd();
            if (selectedIds != null && selectedIndex != selectedTo) {
                throw format("Project index selected records are incomplete");
            }
        }
    }

    private static final class LazyDiskStrings implements LazyStrings {
        private static final long REPLAY_STRING_CACHE_BYTES = 8L << 20;

        /**
         * Overrides {@link #DEFAULT_REPLAY_DICTIONARY_BYTES}. A value of zero
         * or less disables replay dictionary materialization and keeps the
         * bounded block cache.
         */
        private static final String REPLAY_DICTIONARY_PROPERTY =
                "pgcodekeeper.projectIndex.replayDictionaryBytes";
        private static final long DEFAULT_REPLAY_DICTIONARY_BYTES = 256L << 20;
        private static final int REPLAY_DICTIONARY_HEAP_DIVISOR = 4;

        /**
         * An upper bound for one decoded dictionary entry excluding its
         * characters: 64 bytes for the string object plus a padded array
         * header for its characters.
         */
        private static final int STRING_ENTRY_BOUND_BYTES = 88;

        /**
         * How much decoded dictionary a lookup path may keep resident.
         *
         * <p>A dictionary block is decoded from its first record, so reaching
         * one entry decodes every entry before it and then throws that work
         * away. Lookups do not touch the dictionary evenly - a binary search
         * returns to the same few blocks on every step, and the identifiers
         * carried by the definitions it returns land in the same places
         * repeatedly - so the blocks worth keeping are far fewer than the
         * dictionary, and keeping them turns a per-probe block scan into a
         * per-block one.
         *
         * <p>This is a second, much smaller peer of the raw block cache: the
         * bytes are already resident there, and what is bounded here is the
         * decoded form of them.
         */
        private static final long LOOKUP_BLOCK_CACHE_BYTES = 16L << 20;
        private static final int LOOKUP_BLOCK_CACHE_HEAP_DIVISOR = 16;

        private final ProjectIndexSource source;
        private final PackedBlocks blocks;
        private final ThreadLocal<DecodeScope> activeScope = new ThreadLocal<>();
        private final LongAdder stringProbes = new LongAdder();
        private final LongAdder stringProbeRecords = new LongAdder();
        private final LongAdder stringProbeNanos = new LongAdder();
        private final long residentBlockLimit;
        private final Map<Integer, DecodedStringBlock> residentBlocks =
                new LinkedHashMap<>(16, 0.75f, true);
        private long residentBlockBytes;

        private LazyDiskStrings(ProjectIndexSource source, PackedBlocks blocks) {
            this.source = source;
            this.blocks = blocks;
            this.residentBlockLimit = Math.min(LOOKUP_BLOCK_CACHE_BYTES,
                    Runtime.getRuntime().maxMemory()
                            / LOOKUP_BLOCK_CACHE_HEAP_DIVISOR);
        }

        @Override
        public int size() {
            return blocks.recordCount();
        }

        @Override
        public String value(int id) throws ProjectIndexFormatException {
            if (id <= 0 || id > size()) {
                throw format("Invalid string id: " + id);
            }
            DecodeScope scope = activeScope.get();
            if (scope != null) {
                if (scope.replay) {
                    return scope.replayValue(id);
                }
                String cached = scope.values.get(id);
                if (cached != null) {
                    return cached;
                }
                String decoded = decodeValue(id, scope.budget);
                scope.budget.claim(64, "lazy string lookup entry");
                scope.values.put(id, decoded);
                return decoded;
            }
            return decodeValue(id, null);
        }

        DecodeScope openScope(AllocationBudget budget) {
            if (activeScope.get() != null) {
                throw new IllegalStateException(
                        "A lazy string decode scope is already active");
            }
            DecodeScope scope = new DecodeScope(budget);
            activeScope.set(scope);
            return scope;
        }

        DecodeScope openReplayScope() throws ProjectIndexFormatException {
            if (activeScope.get() != null) {
                throw new IllegalStateException(
                        "A lazy string decode scope is already active");
            }
            DecodeScope scope = new DecodeScope();
            activeScope.set(scope);
            return scope;
        }

        /**
         * The largest dictionary this process is willing to materialize.
         *
         * Compaction already keeps every distinct string of the merged index
         * resident on the writer side, so a fully decoded reader dictionary of
         * the same order does not add a new peak. The limit still degrades to
         * the bounded block cache for indexes that outgrow the heap.
         */
        private long replayDictionaryLimit() {
            long configured = Long.getLong(REPLAY_DICTIONARY_PROPERTY,
                    DEFAULT_REPLAY_DICTIONARY_BYTES);
            if (configured <= 0) {
                return 0;
            }
            return Math.min(configured, Runtime.getRuntime().maxMemory()
                    / REPLAY_DICTIONARY_HEAP_DIVISOR);
        }

        /**
         * An upper bound for the fully decoded dictionary that needs no I/O.
         *
         * UTF-8 never spends less than one byte per character, so the packed
         * block payload bounds the decoded character count from above.
         */
        private long materializedDictionaryBound() {
            try {
                long packed = 0;
                for (int index = 0; index < blocks.blockCount(); index++) {
                    packed = Math.addExact(packed, blocks.block(index).length());
                }
                return Math.addExact(
                        arrayBytes(blocks.recordCount(), Long.BYTES),
                        Math.addExact(
                                Math.multiplyExact((long) blocks.recordCount(),
                                        STRING_ENTRY_BOUND_BYTES),
                                Math.multiplyExact(2L, packed)));
            } catch (ArithmeticException ex) {
                return Long.MAX_VALUE;
            }
        }

        void claim(long bytes, String label) throws ProjectIndexFormatException {
            DecodeScope scope = activeScope.get();
            if (scope != null && scope.budget != null) {
                scope.budget.claim(bytes, label);
            }
        }

        /**
         * The only place a dictionary entry is turned back into characters,
         * and so the only place the price of strings is paid. Counted here
         * rather than at the callers because the callers are two unrelated
         * populations - the binary searches that turn a subject into
         * identifiers, and the identifiers carried by the definitions a
         * lookup returned - and it is their sum that a decision about caching
         * strings has to be taken against.
         *
         * <p>An entry is reached through its whole decoded block rather than
         * by walking the block's records up to it: the walk cannot stop
         * earlier than the entry it wants, so a block is scanned nearly whole
         * on every probe into it anyway, and decoding it whole once is what
         * lets the probes after the first cost nothing. The records counter
         * therefore prices blocks decoded, not entries reached, and a probe
         * served from a resident block adds none.
         *
         * <p>The counters are raised even when the decode fails: a probe that
         * throws has already spent the block read that makes it expensive.
         */
        private String decodeValue(int id, AllocationBudget budget)
                throws ProjectIndexFormatException {
            long started = System.nanoTime();
            int scanned = 0;
            try {
                int recordId = id - 1;
                int blockIndex = blocks.findBlock(recordId);
                if (blockIndex < 0) {
                    throw format("Project index string id has no block");
                }
                BlockEntry block = blocks.block(blockIndex);
                String[] resident = residentBlock(blockIndex);
                if (resident == null) {
                    String[] decoded = new String[block.recordCount()];
                    long bytes = Math.addExact(
                            arrayBytes(decoded.length, Long.BYTES),
                            decodeBlockInto(source, block, decoded, 0,
                                    "STRINGS block"));
                    scanned = block.recordCount();
                    retainBlock(blockIndex,
                            new DecodedStringBlock(decoded, bytes));
                    resident = decoded;
                }
                int relative = recordId - block.startRecord();
                if (relative < 0 || relative >= resident.length) {
                    throw format("Project index string record is missing");
                }
                String result = resident[relative];
                if (budget != null) {
                    // What reading the entry off its block would have claimed,
                    // charged whether or not the block was already resident so
                    // that a caller's limit does not depend on cache state.
                    budget.claim(64 + arrayBytes(result.length(),
                            Character.BYTES), "retained dictionary string");
                }
                return result;
            } finally {
                stringProbes.increment();
                stringProbeRecords.add(scanned);
                stringProbeNanos.add(System.nanoTime() - started);
            }
        }

        /**
         * The decoded form of a dictionary block, or null when it is not
         * resident.
         *
         * <p>A block's bytes never change while the index that owns them is
         * open - a publication writes a new file rather than editing the one
         * in use - so a decoded block is a pure function of its index and
         * needs no invalidation beyond the lifetime of this dictionary.
         */
        private synchronized String[] residentBlock(int blockIndex) {
            DecodedStringBlock cached = residentBlocks.get(blockIndex);
            return cached == null ? null : cached.values();
        }

        /**
         * Keeps a decoded block if it fits, evicting least recently used
         * blocks to make room. A block too large for the whole budget is
         * simply not kept, so the caller still gets its answer.
         */
        private synchronized void retainBlock(int blockIndex,
                DecodedStringBlock decoded) {
            if (decoded.residentBytes() > residentBlockLimit
                    || residentBlocks.containsKey(blockIndex)) {
                return;
            }
            while (!residentBlocks.isEmpty() && residentBlockBytes
                    > residentBlockLimit - decoded.residentBytes()) {
                var iterator = residentBlocks.entrySet().iterator();
                residentBlockBytes -= iterator.next().getValue().residentBytes();
                iterator.remove();
            }
            residentBlocks.put(blockIndex, decoded);
            residentBlockBytes += decoded.residentBytes();
        }

        private synchronized void releaseBlocks() {
            residentBlocks.clear();
            residentBlockBytes = 0;
        }

        private synchronized int residentBlockCount() {
            return residentBlocks.size();
        }

        /**
         * Decodes a whole dictionary block into {@code target}, and answers
         * what keeping its strings costs.
         *
         * <p>Shared by the two readers that want a block whole: a lookup that
         * is about to make it resident, and a replay that walks the records in
         * storage order while their identifiers are scattered across the
         * dictionary.
         */
        private long decodeBlockInto(ProjectIndexSource from, BlockEntry block,
                String[] target, int offset, String label)
                throws ProjectIndexFormatException {
            BinaryReader input = new BinaryReader(from, block.offset(),
                    block.length(), label, null);
            long resident = 0;
            for (int i = 0; i < block.recordCount(); i++) {
                int length = input.readVarInt();
                if (length > MAX_RECORD_BYTES) {
                    throw format("STRINGS record exceeds the size limit");
                }
                BinaryReader record = input.slice(length, "STRINGS record");
                String value = record.readUtf8(record.remaining());
                record.requireEnd();
                target[offset + i] = value;
                resident = Math.addExact(resident,
                        64 + arrayBytes(value.length(), Character.BYTES));
            }
            input.requireEnd();
            return resident;
        }

        private ProjectIndexStringProbes probes() {
            return new ProjectIndexStringProbes(stringProbes.sum(),
                    stringProbeRecords.sum(), stringProbeNanos.sum());
        }

        @Override
        public int find(String value) throws ProjectIndexFormatException {
            int id = findOptional(value);
            if (id < 0) {
                throw format("A decoded semantic string is missing from STRINGS: "
                        + value);
            }
            return id;
        }

        @Override
        public int findPacked(long key, int length, String label)
                throws ProjectIndexFormatException {
            if (length < 0 || length > Long.SIZE / Character.SIZE) {
                throw format("Invalid packed " + label + " length");
            }
            char[] value = new char[length];
            for (int i = 0; i < length; i++) {
                int shift = (length - i - 1) * Character.SIZE;
                value[i] = (char) (key >>> shift);
            }
            int id = findOptional(new String(value));
            if (id < 0) {
                throw format("A decoded " + label + " is missing from STRINGS");
            }
            return id;
        }

        int findNullable(String value) throws ProjectIndexFormatException {
            return value == null ? 0 : findOptional(value);
        }

        int findOptional(String value) throws ProjectIndexFormatException {
            int low = 1;
            int high = size();
            while (low <= high) {
                int middle = (low + high) >>> 1;
                int comparison = value(middle).compareTo(value);
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

        final class DecodeScope implements AutoCloseable {
            private final AllocationBudget budget;
            private final Map<Integer, String> values = new HashMap<>();
            private final boolean replay;
            private final ReplayProjectIndexSource replaySource;
            private final Map<Integer, DecodedStringBlock> replayBlocks;
            private long replayBytes;
            private String[] dictionary;
            private boolean open = true;

            private DecodeScope(AllocationBudget budget) {
                this.budget = budget;
                replay = false;
                replaySource = null;
                replayBlocks = null;
            }

            private DecodeScope() throws ProjectIndexFormatException {
                budget = null;
                replay = true;
                replaySource = new ReplayProjectIndexSource(source);
                dictionary = materializeDictionary();
                replayBlocks = dictionary != null ? null
                        : new LinkedHashMap<>(16, 0.75f, true);
            }

            /**
             * Decodes every dictionary entry once, or returns null when the
             * dictionary does not fit {@link #replayDictionaryLimit()}.
             *
             * A replay walks the records in storage order while their string
             * identifiers are scattered across the whole dictionary, so a
             * bounded cache decodes the same blocks over and over again.
             */
            private String[] materializeDictionary()
                    throws ProjectIndexFormatException {
                long limit = replayDictionaryLimit();
                if (limit <= 0 || materializedDictionaryBound() > limit) {
                    return null;
                }
                String[] decoded = new String[blocks.recordCount()];
                for (int index = 0; index < blocks.blockCount(); index++) {
                    BlockEntry block = blocks.block(index);
                    decodeReplayBlock(block, decoded, block.startRecord());
                }
                return decoded;
            }

            private String replayValue(int id)
                    throws ProjectIndexFormatException {
                if (dictionary != null) {
                    return dictionary[id - 1];
                }
                int recordId = id - 1;
                int blockIndex = blocks.findBlock(recordId);
                if (blockIndex < 0) {
                    throw format(
                            "Project index string id has no block");
                }
                DecodedStringBlock decoded =
                        replayBlocks.get(blockIndex);
                if (decoded == null) {
                    decoded = decodeReplayBlock(blockIndex);
                    if (decoded.residentBytes()
                            <= REPLAY_STRING_CACHE_BYTES) {
                        while (!replayBlocks.isEmpty()
                                && replayBytes
                                        > REPLAY_STRING_CACHE_BYTES
                                                - decoded.residentBytes()) {
                            var iterator =
                                    replayBlocks.entrySet().iterator();
                            DecodedStringBlock removed =
                                    iterator.next().getValue();
                            replayBytes -= removed.residentBytes();
                            iterator.remove();
                        }
                        replayBlocks.put(blockIndex, decoded);
                        replayBytes += decoded.residentBytes();
                    }
                }
                BlockEntry block = blocks.block(blockIndex);
                return decoded.values()[
                        recordId - block.startRecord()];
            }

            private DecodedStringBlock decodeReplayBlock(int blockIndex)
                    throws ProjectIndexFormatException {
                BlockEntry block = blocks.block(blockIndex);
                String[] decoded = new String[block.recordCount()];
                long resident = Math.addExact(
                        arrayBytes(decoded.length, Long.BYTES),
                        decodeReplayBlock(block, decoded, 0));
                return new DecodedStringBlock(decoded, resident);
            }

            private long decodeReplayBlock(BlockEntry block, String[] target,
                    int offset) throws ProjectIndexFormatException {
                return decodeBlockInto(replaySource, block, target, offset,
                        "STRINGS sequential replay block");
            }

            @Override
            public void close() {
                if (!open) {
                    return;
                }
                open = false;
                values.clear();
                dictionary = null;
                if (replayBlocks != null) {
                    replayBlocks.clear();
                    replayBytes = 0;
                }
                activeScope.remove();
            }
        }

        private record DecodedStringBlock(
                String[] values, long residentBytes) {
        }
    }
}
