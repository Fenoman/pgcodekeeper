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

import static ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexLocatorBuilder.SPARSE_STRIDE;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;

import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexFormat.DirectoryEntry;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexLocatorBuilder.BlockDirectory;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexLocatorBuilder.BlockRow;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexLocatorBuilder.FileRow;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexLocatorBuilder.RawMatchKey;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexLocatorBuilder.RawPath;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexLocatorBuilder.SparseRow;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexLocatorBuilder.SparseTable;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexRecords.StringTable;

final class ProjectIndexLocatorCapture {

    private final ProjectIndexManifest manifest;
    private final EnumMap<SectionType, Blocks> blocks =
            new EnumMap<>(SectionType.class);
    private final EnumMap<SectionType, Sparse> sparse =
            new EnumMap<>(SectionType.class);
    private final List<DirectoryEntry> entries =
            new ArrayList<>(SectionType.values().length);
    private final List<FileRow> files;
    private RawPath previousFile;
    private int rangedFiles;
    private int nextDefinition;
    private int nextLocation;

    ProjectIndexLocatorCapture(ProjectIndexManifest manifest) {
        this.manifest = Objects.requireNonNull(manifest, "manifest");
        files = new ArrayList<>(manifest.files().size());
    }

    Blocks beginBlocks(SectionType type, int recordCount,
            long payloadOffset) {
        if (type != SectionType.STRINGS
                && type != SectionType.DEFINITIONS
                && type != SectionType.LOCATIONS) {
            throw new IllegalArgumentException(
                    "Unsupported block locator section: " + type);
        }
        Blocks result = new Blocks(recordCount, payloadOffset);
        if (blocks.putIfAbsent(type, result) != null) {
            throw new IllegalStateException(
                    type + " locator blocks were already captured");
        }
        return result;
    }

    void file(ProjectFileStamp stamp, StringTable strings) {
        Objects.requireNonNull(stamp, "stamp");
        RawPath path = new RawPath(
                stamp.path().origin().ordinal(),
                strings.id(stamp.path().relativePath()));
        if (previousFile != null
                && previousFile.compareTo(path) >= 0) {
            throw new IllegalStateException(
                    "FILES paths changed canonical order while encoding");
        }
        previousFile = path;
        files.add(new FileRow(path.origin(), path.pathId(),
                stamp.eclipseModificationStamp(), stamp.size(),
                stamp.lastModifiedMillis(), stamp.contentSha256(),
                0, 0, 0, 0));
    }

    void range(int fileId, IndexPathRef path, StringTable strings,
            int definitionStart, int definitionCount,
            int locationStart, int locationCount) {
        if (fileId != rangedFiles || fileId >= files.size()
                || definitionStart != nextDefinition
                || locationStart != nextLocation
                || definitionCount < 0 || locationCount < 0) {
            throw new IllegalStateException(
                    "BY_PATH ranges changed canonical order while encoding");
        }
        FileRow file = files.get(fileId);
        int origin = path.origin().ordinal();
        int pathId = strings.id(path.relativePath());
        if (file.origin() != origin || file.pathId() != pathId) {
            throw new IllegalStateException(
                    "FILES and BY_PATH changed between encoding passes");
        }
        files.set(fileId, file.withRanges(
                definitionStart, definitionCount,
                locationStart, locationCount));
        rangedFiles++;
        nextDefinition = Math.addExact(
                nextDefinition, definitionCount);
        nextLocation = Math.addExact(
                nextLocation, locationCount);
    }

    Sparse beginSparse(SectionType type, int recordCount,
            long payloadOffset) {
        if (type != SectionType.BY_MATCH_KEY
                && type != SectionType.COMPLETION_TRIGRAMS
                && type != SectionType.REVERSE_DEPENDENCIES
                && type != SectionType.UNRESOLVED_FILES) {
            throw new IllegalArgumentException(
                    "Unsupported sparse locator section: " + type);
        }
        Sparse result = new Sparse(type, recordCount, payloadOffset);
        if (sparse.putIfAbsent(type, result) != null) {
            throw new IllegalStateException(
                    type + " sparse locator was already captured");
        }
        return result;
    }

    void section(DirectoryEntry entry) {
        Objects.requireNonNull(entry, "entry");
        if (entries.size() >= SectionType.values().length
                || entry.type()
                        != SectionType.values()[entries.size()]) {
            throw new IllegalStateException(
                    "Project index sections were not encoded canonically");
        }
        entries.add(entry);
    }

    ProjectIndexLocatorDraft finish(long codecLength) {
        if (entries.size() != SectionType.values().length
                || files.size() != manifest.files().size()) {
            throw new IllegalStateException(
                    "Project index locator capture is incomplete");
        }
        BlockDirectory stringBlocks =
                requireBlocks(SectionType.STRINGS).finish();
        BlockDirectory definitionBlocks =
                requireBlocks(SectionType.DEFINITIONS).finish();
        BlockDirectory locationBlocks =
                requireBlocks(SectionType.LOCATIONS).finish();
        if (rangedFiles != files.size()
                || nextDefinition
                        != definitionBlocks.recordCount()
                || nextLocation
                        != locationBlocks.recordCount()) {
            throw new IllegalStateException(
                    "BY_PATH does not cover every captured record");
        }
        return new ProjectIndexLocatorDraft(manifest, codecLength,
                entries, stringBlocks, definitionBlocks,
                locationBlocks,
                files,
                requireSparse(SectionType.BY_MATCH_KEY).finish(
                        entry(SectionType.BY_MATCH_KEY)),
                requireSparse(SectionType.COMPLETION_TRIGRAMS).finish(
                        entry(SectionType.COMPLETION_TRIGRAMS)),
                requireSparse(SectionType.REVERSE_DEPENDENCIES).finish(
                        entry(SectionType.REVERSE_DEPENDENCIES)),
                requireSparse(SectionType.UNRESOLVED_FILES).finish(
                        entry(SectionType.UNRESOLVED_FILES)));
    }

    private Blocks requireBlocks(SectionType type) {
        return Objects.requireNonNull(blocks.get(type),
                () -> type + " block locator was not captured");
    }

    private Sparse requireSparse(SectionType type) {
        return Objects.requireNonNull(sparse.get(type),
                () -> type + " sparse locator was not captured");
    }

    private DirectoryEntry entry(SectionType type) {
        return entries.get(type.ordinal());
    }

    static final class Blocks {

        private final int recordCount;
        private final long payloadOffset;
        private final List<BlockRow> rows = new ArrayList<>();
        private int capturedRecords;
        private long previousEnd = -1;

        private Blocks(int recordCount, long payloadOffset) {
            if (recordCount < 0 || payloadOffset < 0) {
                throw new IllegalArgumentException(
                        "Invalid block locator bounds");
            }
            this.recordCount = recordCount;
            this.payloadOffset = payloadOffset;
        }

        void add(int startRecord, int records,
                long relativeOffset, int length) {
            if (startRecord != capturedRecords || records <= 0
                    || relativeOffset < 0 || length <= 0
                    || previousEnd >= relativeOffset) {
                throw new IllegalStateException(
                        "Invalid captured project index block");
            }
            long end = Math.addExact(relativeOffset, length);
            rows.add(new BlockRow(startRecord, records,
                    Math.addExact(payloadOffset, relativeOffset),
                    length));
            capturedRecords = Math.addExact(
                    capturedRecords, records);
            previousEnd = end;
        }

        private BlockDirectory finish() {
            if (capturedRecords != recordCount
                    || (recordCount == 0) != rows.isEmpty()) {
                throw new IllegalStateException(
                        "Captured project index block count is incomplete");
            }
            return new BlockDirectory(recordCount,
                    List.copyOf(rows));
        }
    }

    static final class Sparse {

        private final SectionType type;
        private final int recordCount;
        private final long payloadOffset;
        private final List<SparseRow> rows;
        private int records;
        private RawMatchKey previousMatch;
        private int previousString;
        private RawPath previousPath;
        private long previousOffset = -1;

        private Sparse(SectionType type, int recordCount,
                long payloadOffset) {
            if (recordCount < 0 || payloadOffset < 0) {
                throw new IllegalArgumentException(
                        "Invalid sparse locator bounds");
            }
            this.type = type;
            this.recordCount = recordCount;
            this.payloadOffset = payloadOffset;
            rows = new ArrayList<>(
                    (recordCount + SPARSE_STRIDE - 1)
                            / SPARSE_STRIDE);
        }

        void match(long relativeOffset, long first, long second) {
            int index = next(relativeOffset);
            RawMatchKey key = rawMatch(first, second);
            if (previousMatch != null
                    && previousMatch.compareTo(key) >= 0) {
                throw new IllegalStateException(
                        type + " keys changed canonical order while encoding");
            }
            previousMatch = key;
            if (index % SPARSE_STRIDE == 0) {
                rows.add(SparseRow.match(index,
                        payloadOffset + relativeOffset,
                        key));
            }
        }

        void string(long relativeOffset, int stringId) {
            int index = next(relativeOffset);
            if (stringId <= previousString) {
                throw new IllegalStateException(
                        type + " keys changed canonical order while encoding");
            }
            previousString = stringId;
            if (index % SPARSE_STRIDE == 0) {
                rows.add(SparseRow.string(index,
                        payloadOffset + relativeOffset, stringId));
            }
        }

        void path(long relativeOffset, IndexPathRef path,
                StringTable strings) {
            int index = next(relativeOffset);
            RawPath raw = new RawPath(path.origin().ordinal(),
                    strings.id(path.relativePath()));
            if (previousPath != null
                    && previousPath.compareTo(raw) >= 0) {
                throw new IllegalStateException(
                        type + " paths changed canonical order while encoding");
            }
            previousPath = raw;
            if (index % SPARSE_STRIDE == 0) {
                rows.add(SparseRow.path(index,
                        payloadOffset + relativeOffset,
                        raw));
            }
        }

        private int next(long relativeOffset) {
            if (records >= recordCount || relativeOffset < 0
                    || previousOffset >= relativeOffset) {
                throw new IllegalStateException(
                        "Sparse locator record count changed while encoding "
                                + type);
            }
            previousOffset = relativeOffset;
            return records++;
        }

        private SparseTable finish(DirectoryEntry entry) {
            if (records != recordCount
                    || entry.offset()
                            + ProjectIndexFormat.SECTION_FRAME_SIZE
                            != payloadOffset) {
                throw new IllegalStateException(
                        type + " sparse locator capture is incomplete");
            }
            return switch (type) {
            case BY_MATCH_KEY, REVERSE_DEPENDENCIES ->
                SparseTable.match(entry, recordCount, rows);
            case COMPLETION_TRIGRAMS ->
                SparseTable.string(entry, recordCount, rows);
            case UNRESOLVED_FILES ->
                SparseTable.path(entry, recordCount, rows);
            default -> throw new AssertionError(type);
            };
        }

        private static RawMatchKey rawMatch(long first,
                long second) {
            return new RawMatchKey(
                    (int) (first >>> 56),
                    (int) (first >>> 48 & 0xff),
                    (int) (first >>> 24 & 0xffffff),
                    (int) (first & 0xffffff),
                    (int) (second >>> 40 & 0xffffff),
                    (int) (second >>> 16 & 0xffffff),
                    (second & 1L << 15) == 0 ? 0 : 1);
        }
    }
}
