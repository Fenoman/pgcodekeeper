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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.CRC32C;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexLocatorBuilder.BlockDirectory;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexLocatorBuilder.BlockRow;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexLocatorBuilder.FileRow;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexLocatorBuilder.SparseRow;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexLocatorBuilder.SparseTable;

class ProjectIndexPackedPublicationTest {

    private static final int CONTAINER_HEADER_BYTES = 64;
    private static final byte[] CONTAINER_MAGIC =
            "PGCKPIV2".getBytes(StandardCharsets.US_ASCII);

    @ParameterizedTest(name = "{0}")
    @MethodSource("publicationFixtures")
    void publicationKeepsExactContainerBytesWithOneSequentialCodecReread(
            String name, ProjectIndexData source, long tupleBudgetBytes,
            String publicationId, @TempDir Path temporary) throws Exception {
        byte[] expected = legacyContainerBytes(source);
        long codecLength = ByteBuffer.wrap(expected)
                .getLong(24);
        var delegate = new ProjectIndexFormat.MemoryChannel();
        var target = new ReadTrackingChannel(delegate);
        var context = new ProjectIndexWriteContext(
                temporary.resolve("state-" + name),
                publicationId, () -> false, tupleBudgetBytes, null);

        ProjectIndexContainer.write(target, source, context);
        byte[] actual = delegate.toByteArray();

        assertArrayEquals(expected, actual,
                "Packed publication changed container bytes");
        assertArrayEquals(sha256(expected), sha256(actual),
                "Packed publication changed container SHA-256");
        assertEquals(1, ByteBuffer.wrap(actual).getInt(8),
                "Packed publication must not bump the container version");
        assertEquals(codecLength, target.readBytes(),
                "Publication must reread the codec exactly once");
        assertEquals(CONTAINER_HEADER_BYTES, target.firstReadPosition(),
                "The bounded reread must start at the codec header");
        assertTrue(target.sequentialReads(),
                "Publication must not seek while rereading the codec");
        assertEquals(CONTAINER_HEADER_BYTES + codecLength,
                target.lastReadEnd(),
                "The bounded reread must end at the codec footer");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("publicationFixtures")
    void capturedLocatorMatchesLegacySemanticOracle(
            String name, ProjectIndexData source, long tupleBudgetBytes,
            String publicationId, @TempDir Path temporary) throws Exception {
        var context = new ProjectIndexWriteContext(
                temporary.resolve("draft-" + name),
                publicationId, () -> false, tupleBudgetBytes, null);
        try (var codec = new ProjectIndexFormat.MemoryChannel()) {
            ProjectIndexBaseWriter.WriteResult written =
                    ProjectIndexBaseWriter.write(
                            source, codec, context);
            codec.position(0);
            byte[] expected = ProjectIndexLocatorBuilder.build(
                    codec, source.manifest());

            ProjectIndexBlockCrcScanner.ScanResult scanned =
                    ProjectIndexBlockCrcScanner.scan(
                            codec, written.locatorDraft(), context);

            assertArrayEquals(expected, scanned.locatorBytes());
            assertEquals(codec.size(), scanned.rereadBytes());
        }
    }

    @org.junit.jupiter.api.Test
    void spillAndNoSpillProduceIdenticalCodecAndLocator(
            @TempDir Path temporary) throws Exception {
        ProjectIndexData source =
                ProjectIndexFixtures.completionHeavy(96);

        EncodedPublication noSpill = encode(
                source, 1L << 20,
                "88888888888888888888888888888888",
                temporary.resolve("no-spill"));
        EncodedPublication spill = encode(
                source, 64L,
                "99999999999999999999999999999999",
                temporary.resolve("spill"));

        assertArrayEquals(noSpill.codec(), spill.codec());
        assertArrayEquals(noSpill.locator(), spill.locator());
        assertEquals(0, noSpill.materializedRuns());
        assertTrue(spill.materializedRuns() > 0);
    }

    @org.junit.jupiter.api.Test
    void onePassScannerRejectsPayloadCorruption(
            @TempDir Path temporary) throws Exception {
        ProjectIndexData source =
                ProjectIndexFixtures.snapshotWithAllMetaKinds();
        var writerContext = new ProjectIndexWriteContext(
                temporary.resolve("corrupt-writer"),
                "55555555555555555555555555555555",
                () -> false, 1L << 20, null);
        try (var codec = new ProjectIndexFormat.MemoryChannel()) {
            ProjectIndexBaseWriter.WriteResult written =
                    ProjectIndexBaseWriter.write(
                            source, codec, writerContext);
            long payload = written.locatorDraft().entries()
                    .get(SectionType.MANIFEST.ordinal()).offset()
                    + ProjectIndexFormat.SECTION_FRAME_SIZE;
            codec.position(payload);
            codec.write(ByteBuffer.wrap(new byte[] {
                    (byte) (codec.toByteArray()[(int) payload] ^ 1)
            }));

            assertThrows(ProjectIndexFormatException.class,
                    () -> ProjectIndexBlockCrcScanner.scan(
                            codec, written.locatorDraft(),
                            writerContext));
        }
    }

    @org.junit.jupiter.api.Test
    void onePassScannerRejectsStructuralCorruption(
            @TempDir Path temporary) throws Exception {
        ProjectIndexData source =
                ProjectIndexFixtures.snapshotWithAllMetaKinds();
        var writerContext = new ProjectIndexWriteContext(
                temporary.resolve("structural-writer"),
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                () -> false, 1L << 20, null);
        try (var codec = new ProjectIndexFormat.MemoryChannel()) {
            ProjectIndexBaseWriter.WriteResult written =
                    ProjectIndexBaseWriter.write(
                            source, codec, writerContext);
            ProjectIndexLocatorDraft draft =
                    written.locatorDraft();
            long directoryOffset = draft.entries().stream()
                    .mapToLong(entry -> entry.offset()
                            + ProjectIndexFormat.SECTION_FRAME_SIZE
                            + entry.payloadLength())
                    .max().orElseThrow();
            long[] offsets = {
                    0,
                    draft.entries().getFirst().offset(),
                    directoryOffset,
                    codec.size() - ProjectIndexFormat.FOOTER_SIZE
            };
            byte[] original = codec.toByteArray();
            for (long offset : offsets) {
                codec.position(offset);
                codec.write(ByteBuffer.wrap(new byte[] {
                        (byte) (original[(int) offset] ^ 1)
                }));
                assertThrows(ProjectIndexFormatException.class,
                        () -> ProjectIndexBlockCrcScanner.scan(
                                codec, draft, writerContext));
                codec.position(offset);
                codec.write(ByteBuffer.wrap(new byte[] {
                        original[(int) offset]
                }));
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("wrongCapturedSemantics")
    void onePassScannerRejectsValidCodecWithWrongCapturedSemantics(
            String name, CapturedFault fault,
            @TempDir Path temporary) throws Exception {
        ProjectIndexData source =
                ProjectIndexFixtures.snapshotWithAllMetaKinds();
        var context = new ProjectIndexWriteContext(
                temporary.resolve("semantic-writer-" + name),
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                () -> false, 1L << 20, null);
        try (var codec = new ProjectIndexFormat.MemoryChannel()) {
            ProjectIndexBaseWriter.WriteResult written =
                    ProjectIndexBaseWriter.write(
                            source, codec, context);

            codec.position(0);
            ProjectIndexLocatorBuilder.build(
                    codec, source.manifest());
            ProjectIndexLocatorDraft wrong =
                    fault.apply(written.locatorDraft());

            assertThrows(ProjectIndexFormatException.class,
                    () -> ProjectIndexBlockCrcScanner.scan(
                            codec, wrong, context));
        }
    }

    @org.junit.jupiter.api.Test
    void onePassScannerRejectsValidCodecWithUnexpectedManifestDigest(
            @TempDir Path temporary) throws Exception {
        ProjectIndexData expected =
                ProjectIndexFixtures.snapshotWithAllMetaKinds();
        ProjectIndexManifest original = expected.manifest();
        byte[] changedDigest = original.configSha256();
        changedDigest[0] ^= 1;
        ProjectIndexManifest changed = new ProjectIndexManifest(
                original.formatMajor(), original.formatMinor(),
                original.parserAbi(), original.coreVersion(),
                original.uiVersion(), original.databaseType(),
                original.projectIdentity(), changedDigest,
                original.generation(), original.files());

        assertManifestMismatchRejected(expected,
                new ProjectIndexData(changed, expected.files()),
                temporary.resolve("manifest-digest"));
    }

    @org.junit.jupiter.api.Test
    void onePassScannerRejectsValidCodecWithUnexpectedManifestString(
            @TempDir Path temporary) throws Exception {
        ProjectIndexData expected =
                ProjectIndexFixtures.snapshotWithAllMetaKinds();
        ProjectIndexManifest original = expected.manifest();
        ProjectIndexManifest changed = new ProjectIndexManifest(
                original.formatMajor(), original.formatMinor(),
                original.parserAbi(),
                original.coreVersion() + "-unexpected",
                original.uiVersion(), original.databaseType(),
                original.projectIdentity(), original.configSha256(),
                original.generation(), original.files());

        assertManifestMismatchRejected(expected,
                new ProjectIndexData(changed, expected.files()),
                temporary.resolve("manifest-string"));
    }

    @org.junit.jupiter.api.Test
    void cancellationStopsScannerBetweenBoundedBlocks(
            @TempDir Path temporary) throws Exception {
        ProjectIndexData source =
                ProjectIndexFixtures.repeatedLocations(20_000);
        var writerContext = new ProjectIndexWriteContext(
                temporary.resolve("cancel-writer"),
                "66666666666666666666666666666666",
                () -> false, 1L << 20, null);
        try (var codec = new ProjectIndexFormat.MemoryChannel()) {
            ProjectIndexBaseWriter.WriteResult written =
                    ProjectIndexBaseWriter.write(
                            source, codec, writerContext);
            AtomicInteger polls = new AtomicInteger();
            var scanContext = new ProjectIndexWriteContext(
                    temporary.resolve("cancel-scan"),
                    "77777777777777777777777777777777",
                    () -> polls.incrementAndGet() >= 3,
                    1L << 20, null);
            var tracked = new ReadTrackingChannel(codec);

            assertThrows(
                    ProjectIndexStore.WriteCancelledException.class,
                    () -> ProjectIndexBlockCrcScanner.scan(
                            tracked, written.locatorDraft(),
                            scanContext));
            assertTrue(tracked.readBytes() > 0);
            assertTrue(tracked.readBytes() < codec.size());
            assertTrue(tracked.sequentialReads());
        }
    }

    private static void assertManifestMismatchRejected(
            ProjectIndexData expected, ProjectIndexData encoded,
            Path stateDirectory) throws Exception {
        var context = new ProjectIndexWriteContext(
                stateDirectory,
                "cccccccccccccccccccccccccccccccc",
                () -> false, 1L << 20, null);
        try (var codec = new ProjectIndexFormat.MemoryChannel()) {
            ProjectIndexBaseWriter.WriteResult written =
                    ProjectIndexBaseWriter.write(
                            encoded, codec, context);
            ProjectIndexLocatorDraft actual =
                    written.locatorDraft();
            ProjectIndexLocatorDraft expectedDraft =
                    new ProjectIndexLocatorDraft(
                            expected.manifest(),
                            actual.codecLength(), actual.entries(),
                            actual.strings(), actual.definitions(),
                            actual.locations(), actual.files(),
                            actual.match(), actual.completion(),
                            actual.reverse(), actual.unresolved());

            ProjectIndexFormatException error = assertThrows(
                    ProjectIndexFormatException.class,
                    () -> ProjectIndexBlockCrcScanner.scan(
                            codec, expectedDraft, context));
            assertTrue(error.getMessage().contains("MANIFEST"));
        }
    }

    private static Stream<Arguments> publicationFixtures()
            throws Exception {
        ProjectIndexData allMeta =
                ProjectIndexFixtures.snapshotWithAllMetaKinds();
        return Stream.of(
                Arguments.of("no-spill", allMeta, 1L << 20,
                        "11111111111111111111111111111111"),
                Arguments.of("spill-same-data", allMeta, 64L,
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1"),
                Arguments.of("manifest-multibyte-utf8",
                        withManifestVersions(allMeta,
                                "core-\u00e9-\u4e2d-\uD83D\uDE80",
                                "ui-\u00f8-\u754c-\uD83C\uDF10"),
                        1L << 20,
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa2"),
                Arguments.of("spill-randomized",
                        randomized(
                                ProjectIndexFixtures.completionHeavy(96),
                                0x50_47_43_4bL),
                        64L,
                        "22222222222222222222222222222222"),
                Arguments.of("oversized-string-block",
                        ProjectIndexFixtures.withDefinitionComment(
                                "x".repeat(70_000)),
                        1L << 20,
                        "33333333333333333333333333333333"),
                Arguments.of("multi-block-locations",
                        ProjectIndexFixtures.repeatedLocations(20_000),
                        1L << 20,
                        "44444444444444444444444444444444"));
    }

    private static Stream<Arguments> wrongCapturedSemantics() {
        return Stream.of(
                Arguments.of("file-row", CapturedFault.FILE_ROW),
                Arguments.of("by-path-range",
                        CapturedFault.BY_PATH_RANGE),
                Arguments.of("strings-block",
                        CapturedFault.STRINGS_BLOCK),
                Arguments.of("definitions-block",
                        CapturedFault.DEFINITIONS_BLOCK),
                Arguments.of("locations-block",
                        CapturedFault.LOCATIONS_BLOCK),
                Arguments.of("match-sparse",
                        CapturedFault.MATCH_SPARSE),
                Arguments.of("completion-sparse",
                        CapturedFault.COMPLETION_SPARSE),
                Arguments.of("reverse-sparse",
                        CapturedFault.REVERSE_SPARSE),
                Arguments.of("unresolved-sparse",
                        CapturedFault.UNRESOLVED_SPARSE));
    }

    private static ProjectIndexData withManifestVersions(
            ProjectIndexData source, String coreVersion,
            String uiVersion) {
        ProjectIndexManifest old = source.manifest();
        ProjectIndexManifest manifest = new ProjectIndexManifest(
                old.formatMajor(), old.formatMinor(), old.parserAbi(),
                coreVersion, uiVersion, old.databaseType(),
                old.projectIdentity(), old.configSha256(),
                old.generation(), old.files());
        return new ProjectIndexData(manifest, source.files());
    }

    private static EncodedPublication encode(
            ProjectIndexData source, long tupleBudgetBytes,
            String publicationId, Path stateDirectory)
            throws Exception {
        var context = new ProjectIndexWriteContext(
                stateDirectory, publicationId, () -> false,
                tupleBudgetBytes, null);
        try (var codec =
                new ProjectIndexFormat.MemoryChannel()) {
            ProjectIndexBaseWriter.WriteResult written =
                    ProjectIndexBaseWriter.write(
                            source, codec, context);
            ProjectIndexBlockCrcScanner.ScanResult scanned =
                    ProjectIndexBlockCrcScanner.scan(
                            codec, written.locatorDraft(), context);
            return new EncodedPublication(codec.toByteArray(),
                    scanned.locatorBytes(),
                    written.metrics().materializedRuns());
        }
    }

    private static byte[] legacyContainerBytes(ProjectIndexData source)
            throws Exception {
        byte[] codec;
        byte[] locator;
        try (var channel = new ProjectIndexFormat.MemoryChannel()) {
            ProjectIndexBaseWriter.write(source, channel,
                    ProjectIndexFormat.MAX_SECTION_BYTES);
            codec = channel.toByteArray();
            channel.position(0);
            locator = ProjectIndexLocatorBuilder.build(
                    channel, source.manifest());
        }

        int locatorOffset = Math.addExact(
                CONTAINER_HEADER_BYTES, codec.length);
        ByteBuffer header = ByteBuffer.allocate(CONTAINER_HEADER_BYTES);
        header.put(CONTAINER_MAGIC);
        header.putInt(1);
        header.putInt(0);
        header.putLong(source.manifest().generation());
        header.putLong(codec.length);
        header.putLong(locatorOffset);
        header.putInt(locator.length);
        header.putInt(crc32c(locator, 0, locator.length));
        header.putInt(crc32c(header.array(), 0, 48));

        ByteBuffer result = ByteBuffer.allocate(
                Math.addExact(locatorOffset, locator.length));
        result.put(header.array());
        result.put(codec);
        result.put(locator);
        return result.array();
    }

    private static byte[] sha256(byte[] value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value);
    }

    private static int crc32c(byte[] bytes, int offset, int length) {
        CRC32C crc = new CRC32C();
        crc.update(bytes, offset, length);
        return (int) crc.getValue();
    }

    private static ProjectIndexData randomized(
            ProjectIndexData source, long seed) {
        Random random = new Random(seed);
        List<ProjectFileStamp> stamps =
                new ArrayList<>(source.manifest().files());
        Collections.shuffle(stamps, random);
        ProjectIndexManifest old = source.manifest();
        ProjectIndexManifest manifest = new ProjectIndexManifest(
                old.formatMajor(), old.formatMinor(), old.parserAbi(),
                old.coreVersion(), old.uiVersion(), old.databaseType(),
                old.projectIdentity(), old.configSha256(),
                old.generation(), stamps);

        List<FileContribution> files =
                new ArrayList<>(source.files().size());
        for (FileContribution file : source.files()) {
            List<PackedDefinition> definitions =
                    new ArrayList<>(file.definitions());
            List<PackedLocation> locations =
                    new ArrayList<>(file.locations());
            List<ReferenceMatchKey> candidates =
                    new ArrayList<>(file.unresolvedCandidates());
            Collections.shuffle(definitions, random);
            Collections.shuffle(locations, random);
            Collections.shuffle(candidates, random);
            Set<ReferenceMatchKey> unresolved =
                    new LinkedHashSet<>(candidates);
            files.add(new FileContribution(file.path(), definitions,
                    locations, unresolved, file.unresolvedAny()));
        }
        Collections.shuffle(files, random);
        return new ProjectIndexData(manifest, files);
    }

    private static final class ReadTrackingChannel
            implements SeekableByteChannel {

        private final SeekableByteChannel delegate;
        private long readBytes;
        private long firstReadPosition = -1;
        private long lastReadEnd = -1;
        private boolean sequentialReads = true;

        private ReadTrackingChannel(SeekableByteChannel delegate) {
            this.delegate = delegate;
        }

        long readBytes() {
            return readBytes;
        }

        long firstReadPosition() {
            return firstReadPosition;
        }

        long lastReadEnd() {
            return lastReadEnd;
        }

        boolean sequentialReads() {
            return sequentialReads;
        }

        @Override
        public int read(ByteBuffer destination) throws IOException {
            long start = delegate.position();
            if (firstReadPosition < 0) {
                firstReadPosition = start;
            } else if (start != lastReadEnd) {
                sequentialReads = false;
            }
            int read = delegate.read(destination);
            if (read > 0) {
                readBytes = Math.addExact(readBytes, read);
                lastReadEnd = start + read;
            }
            return read;
        }

        @Override
        public int write(ByteBuffer source) throws IOException {
            return delegate.write(source);
        }

        @Override
        public long position() throws IOException {
            return delegate.position();
        }

        @Override
        public SeekableByteChannel position(long newPosition)
                throws IOException {
            delegate.position(newPosition);
            return this;
        }

        @Override
        public long size() throws IOException {
            return delegate.size();
        }

        @Override
        public SeekableByteChannel truncate(long size)
                throws IOException {
            delegate.truncate(size);
            return this;
        }

        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    private record EncodedPublication(byte[] codec, byte[] locator,
            long materializedRuns) {
    }

    private enum CapturedFault {
        FILE_ROW {
            @Override
            ProjectIndexLocatorDraft apply(
                    ProjectIndexLocatorDraft draft) {
                List<FileRow> files =
                        new ArrayList<>(draft.files());
                FileRow first = files.getFirst();
                files.set(0, new FileRow(
                        first.origin(), first.pathId(),
                        first.eclipseStamp() + 1,
                        first.size(), first.modified(),
                        first.sha256(),
                        first.definitionStart(),
                        first.definitionCount(),
                        first.locationStart(),
                        first.locationCount()));
                return copy(draft, draft.strings(),
                        draft.definitions(), draft.locations(),
                        files, draft.match(), draft.completion(),
                        draft.reverse(), draft.unresolved());
            }
        },
        BY_PATH_RANGE {
            @Override
            ProjectIndexLocatorDraft apply(
                    ProjectIndexLocatorDraft draft) {
                List<FileRow> files =
                        new ArrayList<>(draft.files());
                FileRow first = files.getFirst();
                files.set(0, first.withRanges(
                        first.definitionStart() + 1,
                        first.definitionCount(),
                        first.locationStart(),
                        first.locationCount()));
                return copy(draft, draft.strings(),
                        draft.definitions(), draft.locations(),
                        files, draft.match(), draft.completion(),
                        draft.reverse(), draft.unresolved());
            }
        },
        STRINGS_BLOCK {
            @Override
            ProjectIndexLocatorDraft apply(
                    ProjectIndexLocatorDraft draft) {
                return copy(draft,
                        moveFirstBlock(draft.strings()),
                        draft.definitions(), draft.locations(),
                        draft.files(), draft.match(),
                        draft.completion(), draft.reverse(),
                        draft.unresolved());
            }
        },
        DEFINITIONS_BLOCK {
            @Override
            ProjectIndexLocatorDraft apply(
                    ProjectIndexLocatorDraft draft) {
                return copy(draft, draft.strings(),
                        moveFirstBlock(draft.definitions()),
                        draft.locations(), draft.files(),
                        draft.match(), draft.completion(),
                        draft.reverse(), draft.unresolved());
            }
        },
        LOCATIONS_BLOCK {
            @Override
            ProjectIndexLocatorDraft apply(
                    ProjectIndexLocatorDraft draft) {
                return copy(draft, draft.strings(),
                        draft.definitions(),
                        moveFirstBlock(draft.locations()),
                        draft.files(), draft.match(),
                        draft.completion(), draft.reverse(),
                        draft.unresolved());
            }
        },
        MATCH_SPARSE {
            @Override
            ProjectIndexLocatorDraft apply(
                    ProjectIndexLocatorDraft draft) {
                return copy(draft, draft.strings(),
                        draft.definitions(), draft.locations(),
                        draft.files(),
                        moveFirstSparse(draft.match()),
                        draft.completion(), draft.reverse(),
                        draft.unresolved());
            }
        },
        COMPLETION_SPARSE {
            @Override
            ProjectIndexLocatorDraft apply(
                    ProjectIndexLocatorDraft draft) {
                return copy(draft, draft.strings(),
                        draft.definitions(), draft.locations(),
                        draft.files(), draft.match(),
                        moveFirstSparse(draft.completion()),
                        draft.reverse(), draft.unresolved());
            }
        },
        REVERSE_SPARSE {
            @Override
            ProjectIndexLocatorDraft apply(
                    ProjectIndexLocatorDraft draft) {
                return copy(draft, draft.strings(),
                        draft.definitions(), draft.locations(),
                        draft.files(), draft.match(),
                        draft.completion(),
                        moveFirstSparse(draft.reverse()),
                        draft.unresolved());
            }
        },
        UNRESOLVED_SPARSE {
            @Override
            ProjectIndexLocatorDraft apply(
                    ProjectIndexLocatorDraft draft) {
                return copy(draft, draft.strings(),
                        draft.definitions(), draft.locations(),
                        draft.files(), draft.match(),
                        draft.completion(), draft.reverse(),
                        moveFirstSparse(draft.unresolved()));
            }
        };

        abstract ProjectIndexLocatorDraft apply(
                ProjectIndexLocatorDraft draft);

        private static ProjectIndexLocatorDraft copy(
                ProjectIndexLocatorDraft draft,
                BlockDirectory strings,
                BlockDirectory definitions,
                BlockDirectory locations,
                List<FileRow> files,
                SparseTable match,
                SparseTable completion,
                SparseTable reverse,
                SparseTable unresolved) {
            return new ProjectIndexLocatorDraft(
                    draft.manifest(), draft.codecLength(),
                    draft.entries(), strings,
                    definitions, locations, files, match,
                    completion, reverse, unresolved);
        }

        private static BlockDirectory moveFirstBlock(
                BlockDirectory directory) {
            List<BlockRow> blocks =
                    new ArrayList<>(directory.blocks());
            BlockRow first = blocks.getFirst();
            blocks.set(0, new BlockRow(
                    first.startRecord(), first.recordCount(),
                    first.offset() + 1, first.length()));
            return new BlockDirectory(
                    directory.recordCount(),
                    List.copyOf(blocks));
        }

        private static SparseTable moveFirstSparse(
                SparseTable table) {
            List<SparseRow> rows =
                    new ArrayList<>(table.rows());
            SparseRow first = rows.getFirst();
            rows.set(0, new SparseRow(
                    first.recordIndex(),
                    first.offset() + 1,
                    first.matchKey(), first.stringId(),
                    first.path()));
            return new SparseTable(
                    table.kind(), table.payloadOffset(),
                    table.payloadLength(), table.recordCount(),
                    List.copyOf(rows));
        }
    }
}
