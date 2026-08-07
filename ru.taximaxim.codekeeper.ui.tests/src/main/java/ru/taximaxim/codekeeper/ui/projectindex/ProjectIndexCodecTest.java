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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.CRC32C;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.base.schema.meta.MetaRelation;

import ru.taximaxim.codekeeper.ui.pgdbproject.parser.LegacyProjectIndexSizeReference;
import ru.taximaxim.codekeeper.ui.projectindex.PackedDefinition.MetaKind;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexRecords.AuxiliaryIndexes;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexRecords.ProspectiveWork;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexRecords.StringTable;
import ru.taximaxim.codekeeper.ui.projectindex.ReferenceMatchKey.MatchFamily;

class ProjectIndexCodecTest {

    private static final int HEADER_SIZE = 44;
    private static final int HEADER_DIRECTORY_OFFSET = 20;
    private static final int HEADER_DIRECTORY_LENGTH = 28;
    private static final int HEADER_DIRECTORY_COUNT = 32;
    private static final int HEADER_DIRECTORY_CRC = 36;
    private static final int HEADER_CRC = 40;
    private static final int DIRECTORY_ENTRY_SIZE = 20;
    private static final int FOOTER_SIZE = 20;

    @Test
    void packedRoundTripIsCanonicalAndDeterministic() throws Exception {
        ProjectIndexData source = ProjectIndexFixtures.snapshotWithAllMetaKinds();

        byte[] first = ProjectIndexCodec.encodeBase(source);
        byte[] second = ProjectIndexCodec.encodeBase(ProjectIndexFixtures.reordered(source));
        ProjectIndexData restored = ProjectIndexCodec.decodeBase(first, 32L << 20);

        assertArrayEquals(first, second);
        assertArrayEquals(ProjectIndexFixtures.canonicalBytes(source),
                ProjectIndexFixtures.canonicalBytes(restored));
        assertArrayEquals(first, ProjectIndexCodec.encodeBase(restored));
    }

    /**
     * These hashes pin the layout, and FORMAT_MINOR is part of it: the minor
     * sits in the header and again as a manifest varint, with the block and
     * footer checksums covering both. So a bump moves all three hashes, and one
     * was tried and reverted for the cost it carries — see the constant.
     *
     * <p>If a bump is ever right, do not repin from the new run. Encode the
     * same fixtures at both minors and diff the bytes first: the tried bump
     * moved 22 positions in each fixture, 2 of them semantic and 20 the
     * checksums covering them, with the length unchanged. Anything wider means
     * the layout moved and the hash must not be touched.
     */
    @Test
    void versionTwoWireBytesRemainStable() throws Exception {
        assertEquals("743712e293ca8f9ad181ecacbf3ceb04315102291baee835b2052c5cfffd15c6",
                sha256Hex(ProjectIndexCodec.encodeBase(
                        ProjectIndexFixtures.snapshotWithAllMetaKinds())));
        assertEquals("3f23fb2f8135c8e633c6d338019856d8c0944a7aa7f18279969e2621f817529f",
                sha256Hex(ProjectIndexCodec.encodeBase(
                        ProjectIndexFixtures.withDefinitionComment(
                                "a\u00A2\u20AC\uD83D\uDE00"))));
        assertEquals("d876cf2ccb537e19a0882adc076d7044dee457e4567b4a9f2d50a5f44466f9c3",
                sha256Hex(ProjectIndexCodec.encodeBase(
                        ProjectIndexFixtures.repeatedLocations(20_000))));
    }

    @Test
    void canonicalOrderDoesNotDependOnStableSortTies() throws Exception {
        assertArrayEquals(ProjectIndexCodec.encodeBase(ProjectIndexFixtures.comparatorCollisions(false)),
                ProjectIndexCodec.encodeBase(ProjectIndexFixtures.comparatorCollisions(true)));
    }

    @Test
    void streamingWriterMatchesByteArrayConvenience(@TempDir Path directory) throws Exception {
        ProjectIndexData source = ProjectIndexFixtures.snapshotWithAllMetaKinds();
        Path index = directory.resolve("project.idx");

        try (FileChannel channel = FileChannel.open(index, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.READ,
                StandardOpenOption.WRITE)) {
            ProjectIndexCodec.writeBase(source, channel);
        }

        byte[] streamed = Files.readAllBytes(index);
        assertArrayEquals(ProjectIndexCodec.encodeBase(source), streamed);
        assertArrayEquals(ProjectIndexFixtures.canonicalBytes(source),
                ProjectIndexFixtures.canonicalBytes(ProjectIndexCodec.decodeBase(streamed, 32L << 20)));
    }

    @Test
    void streamingWriterAcceptsExplicitBoundedContext(
            @TempDir Path directory) throws Exception {
        ProjectIndexData source =
                ProjectIndexFixtures.snapshotWithAllMetaKinds();
        Path index = directory.resolve("project.idx");
        var context = new ProjectIndexWriteContext(directory,
                "a".repeat(32), () -> false, 1L << 20, null);

        try (FileChannel channel = FileChannel.open(index,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE)) {
            ProjectIndexCodec.writeBase(source, channel, context);
        }

        assertArrayEquals(ProjectIndexCodec.encodeBase(source),
                Files.readAllBytes(index));
    }

    @Test
    void channelReaderUsesBoundedReadWindows(@TempDir Path directory) throws Exception {
        ProjectIndexData source = ProjectIndexFixtures.repeatedLocations(100_000);
        Path index = directory.resolve("large-project.idx");

        try (FileChannel channel = FileChannel.open(index, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.READ,
                StandardOpenOption.WRITE)) {
            ProjectIndexCodec.writeBase(source, channel);
        }

        try (FileChannel file = FileChannel.open(index, StandardOpenOption.READ);
                BoundedReadChannel channel = new BoundedReadChannel(file,
                        ProjectIndexCodec.MAX_RAW_BLOCK_BYTES)) {
            ProjectIndexData restored = ProjectIndexCodec.readBase(channel, 128L << 20);

            assertEquals(100_000, restored.files().getFirst().locations().size());
            assertTrue(channel.maxRequestedBytes() <= ProjectIndexCodec.MAX_RAW_BLOCK_BYTES);
        }
    }

    @Test
    void relationWithUnknownColumnsRoundTripsAsUnknown() {
        IndexPathRef path = ProjectIndexFixtures.path("schema/unknown_view.sql");
        ObjectLocation location = new ObjectLocation.Builder()
                .setFilePath(ProjectIndexFixtures.ABSOLUTE_ROOT + '/' + path.relativePath())
                .setReference(new ObjectReference("app", "unknown_view", DbObjType.VIEW))
                .setLocationType(ObjectLocation.LocationType.DEFINITION)
                .setLength(12)
                .build();
        PackedDefinition packed = PackedDefinition.from(new MetaRelation(location), path);

        MetaRelation restored = (MetaRelation) packed.toMetaStatement(
                ref -> ProjectIndexFixtures.ABSOLUTE_ROOT + '/' + ref.relativePath());

        assertNull(restored.getRelationColumns());
    }

    @Test
    void packedMetadataRehydratesExactEditorFieldsWithoutSqlBody() throws Exception {
        ProjectIndexData data = ProjectIndexFixtures.snapshotWithAllMetaKinds();
        PackedDefinition packed = data.files().get(1).definitions().getFirst();

        var restored = packed.toMetaStatement(
                path -> ProjectIndexFixtures.ABSOLUTE_ROOT + '/' + path.relativePath());

        assertEquals(packed.object().offset(), restored.getOffset());
        assertEquals(packed.object().length(), restored.getObjLength());
        assertEquals(packed.object().alias(), restored.getObject().getAlias());
        assertEquals(packed.object().danger(), restored.getObject().getDanger());
        assertNull(restored.getObject().getSql());
        assertEquals(packed.comment(), restored.getComment());
        assertEquals(1, ((org.pgcodekeeper.core.database.base.schema.meta.MetaFunction) restored)
                .getOrderBy().size());
    }

    @Test
    void allMetadataAndLocationKindsRoundTripWithoutSqlBodies() throws Exception {
        ProjectIndexData source = ProjectIndexFixtures.snapshotWithAllMetaKinds();
        ProjectIndexData restored = ProjectIndexCodec.decodeBase(
                ProjectIndexCodec.encodeBase(source), 32L << 20);

        EnumSet<MetaKind> metadataKinds = EnumSet.noneOf(MetaKind.class);
        EnumSet<ObjectLocation.LocationType> locationTypes =
                EnumSet.noneOf(ObjectLocation.LocationType.class);
        for (FileContribution file : restored.files()) {
            for (PackedDefinition definition : file.definitions()) {
                metadataKinds.add(definition.kind());
                assertNull(definition.toMetaStatement(
                        path -> ProjectIndexFixtures.ABSOLUTE_ROOT + '/'
                                + path.relativePath()).getObject().getSql());
            }
            for (PackedLocation location : file.locations()) {
                locationTypes.add(location.locationType());
                assertNull(location.toObjectLocation(
                        path -> ProjectIndexFixtures.ABSOLUTE_ROOT + '/'
                                + path.relativePath()).getSql());
            }
        }

        assertEquals(EnumSet.allOf(MetaKind.class), metadataKinds);
        assertEquals(EnumSet.allOf(ObjectLocation.LocationType.class), locationTypes);
        assertArrayEquals(ProjectIndexFixtures.canonicalBytes(source),
                ProjectIndexFixtures.canonicalBytes(restored));
    }

    @Test
    void operatorWithDerivedReturnTypeRoundTrips() throws Exception {
        ProjectIndexData source = ProjectIndexFixtures.operatorWithDerivedReturnSnapshot();

        ProjectIndexData restored = ProjectIndexCodec.decodeBase(
                ProjectIndexCodec.encodeBase(source), 32L << 20);
        PackedDefinition operator = restored.files().getFirst().definitions().getFirst();

        assertEquals(MetaKind.OPERATOR, operator.kind());
        assertNull(operator.operatorReturns());
        assertNull(((org.pgcodekeeper.core.database.base.schema.meta.MetaOperator)
                operator.toMetaStatement(path -> ProjectIndexFixtures.ABSOLUTE_ROOT + '/'
                        + path.relativePath())).getReturns());
    }

    @Test
    void baseNeverContainsAbsoluteRootsOrRoutineBody() throws Exception {
        byte[] bytes = ProjectIndexCodec.encodeBase(ProjectIndexFixtures.snapshotWithAllMetaKinds());
        String binary = new String(bytes, StandardCharsets.ISO_8859_1);

        assertFalse(binary.contains(ProjectIndexFixtures.ABSOLUTE_ROOT));
        assertFalse(binary.contains(ProjectIndexFixtures.ROUTINE_BODY));
    }

    @Test
    void rejectsAbsoluteAndEscapingPathsBeforeEncoding() {
        assertThrows(IllegalArgumentException.class,
                () -> new IndexPathRef(IndexPathOrigin.PROJECT, "/tmp/schema.sql"));
        assertThrows(IllegalArgumentException.class,
                () -> new IndexPathRef(IndexPathOrigin.PROJECT, "C:\\tmp\\schema.sql"));
        assertThrows(IllegalArgumentException.class,
                () -> new IndexPathRef(IndexPathOrigin.LIBRARY, "schema/../../outside.sql"));
        assertThrows(IllegalArgumentException.class,
                () -> new IndexPathRef(IndexPathOrigin.PROJECT, "file:/tmp/schema.sql"));
        assertThrows(IllegalArgumentException.class,
                () -> new IndexPathRef(IndexPathOrigin.PROJECT, "C:schema.sql"));
        assertEquals("schema/item.sql",
                new IndexPathRef(IndexPathOrigin.PROJECT, "./schema\\item.sql").relativePath());
    }

    @Test
    void canonicalRelativePathsPreserveCallerStringIdentity() {
        String canonical = new String("schema/\u043E\u0431\u044A\u0435\u043A\u0442.sql");
        IndexPathRef path = new IndexPathRef(IndexPathOrigin.PROJECT, canonical);
        PackedLocation location = packed(path, DbObjType.TABLE, null, true);

        assertSame(canonical, path.relativePath());
        assertSame(canonical, location.relativePath());
        assertSame(canonical, location.path().relativePath());
    }

    @Test
    void hundredThousandLocationsShareOnePathStringAcrossRoundTrip() throws Exception {
        ProjectIndexData source = ProjectIndexFixtures.repeatedLocations(100_000);
        assertOnePathStringIdentity(source);

        ProjectIndexData restored = ProjectIndexCodec.decodeBase(
                ProjectIndexCodec.encodeBase(source), 128L << 20);
        assertOnePathStringIdentity(restored);
    }

    @Test
    void residentBudgetIsAHardUpperBound() throws Exception {
        byte[] bytes = ProjectIndexCodec.encodeBase(ProjectIndexFixtures.snapshotWithAllMetaKinds());

        ProjectIndexFormatException error = assertThrows(ProjectIndexFormatException.class,
                () -> ProjectIndexCodec.decodeBase(bytes, 1));

        assertTrue(error.getMessage().contains("allocation limit"));
    }

    @Test
    void writerRejectsNestedCountsThatItsReaderCannotAccept() throws Exception {
        ProjectIndexData source = ProjectIndexFixtures.withConstraintColumnCount(1_000_001);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ProjectIndexCodec.encodeBase(source));

        assertTrue(error.getMessage().contains("constraint column"));
    }

    @Test
    void writerRejectsOversizedStringBeforeEncodingItsUtf8Payload() throws Exception {
        String oversized = "x".repeat(ProjectIndexFormat.MAX_RECORD_BYTES + 1);
        ProjectIndexData source = ProjectIndexFixtures.withDefinitionComment(oversized);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ProjectIndexCodec.encodeBase(source));

        assertTrue(error.getMessage().contains("UTF-8 payload"));
    }

    @Test
    void writerRejectsUnpairedSurrogateInsteadOfApplyingLossyUtf8Replacement() throws Exception {
        ProjectIndexData source = ProjectIndexFixtures.withDefinitionComment("\uD800");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ProjectIndexCodec.encodeBase(source));

        assertTrue(error.getMessage().contains("unpaired UTF-16 surrogate"));
    }

    @Test
    void recordSizePreflightMatchesUtf8EncodingAcrossAllCodePointWidths() throws Exception {
        ProjectIndexData source = ProjectIndexFixtures.withDefinitionComment("a\u00A2\u20AC\uD83D\uDE00");

        ProjectIndexData decoded = ProjectIndexCodec.decodeBase(
                ProjectIndexCodec.encodeBase(source), 32L << 20);

        assertArrayEquals(ProjectIndexFixtures.canonicalBytes(source),
                ProjectIndexFixtures.canonicalBytes(decoded));
    }

    @Test
    void oversizedUtf8RecordStreamsAsOneDirectRawBlock() throws Exception {
        ProjectIndexData source = ProjectIndexFixtures.withDefinitionComment(
                "\u20AC".repeat(ProjectIndexCodec.MAX_RAW_BLOCK_BYTES / 2));
        byte[] encoded = ProjectIndexCodec.encodeBase(source);
        ProjectIndexData decoded = ProjectIndexCodec.decodeBase(encoded, 32L << 20);

        assertArrayEquals(ProjectIndexFixtures.canonicalBytes(source),
                ProjectIndexFixtures.canonicalBytes(decoded));
        assertArrayEquals(encoded, ProjectIndexCodec.encodeBase(decoded));

        DirectoryEntry strings = entries(encoded).get(SectionType.STRINGS);
        int cursor = Math.toIntExact(strings.offset()) + 12;
        int records = readVarInt(encoded, cursor);
        cursor = skipVarInt(encoded, cursor);
        int decodedRecords = 0;
        int oversizedBlocks = 0;
        while (decodedRecords < records) {
            int blockLength = readVarInt(encoded, cursor);
            cursor = skipVarInt(encoded, cursor);
            int blockEnd = cursor + blockLength;
            int recordsInBlock = 0;
            while (cursor < blockEnd) {
                int recordLength = readVarInt(encoded, cursor);
                cursor = skipVarInt(encoded, cursor) + recordLength;
                recordsInBlock++;
                decodedRecords++;
            }
            if (blockLength > ProjectIndexCodec.MAX_RAW_BLOCK_BYTES) {
                oversizedBlocks++;
                assertEquals(1, recordsInBlock);
            }
        }
        assertEquals(1, oversizedBlocks);
    }

    @Test
    void referenceMatchKeyExactlyTracksObjectLocationCompareFamiliesAndAlias() {
        IndexPathRef path = ProjectIndexFixtures.path("schema/match.sql");
        PackedLocation table = packed(path, DbObjType.TABLE, null, true);
        PackedLocation view = packed(path, DbObjType.VIEW, null, true);
        PackedLocation function = packed(path, DbObjType.FUNCTION, null, true);
        PackedLocation procedure = packed(path, DbObjType.PROCEDURE, null, true);
        PackedLocation type = packed(path, DbObjType.TYPE, null, true);
        PackedLocation domain = packed(path, DbObjType.DOMAIN, null, true);
        PackedLocation column = packed(path, DbObjType.COLUMN, null, true);
        PackedLocation aliased = packed(path, DbObjType.TABLE, "same_name", true);
        PackedLocation local = packed(path, DbObjType.TABLE, null, false);

        assertEquals(ReferenceMatchKey.from(table), ReferenceMatchKey.from(view));
        assertEquals(ReferenceMatchKey.from(function), ReferenceMatchKey.from(procedure));
        assertEquals(ReferenceMatchKey.from(type), ReferenceMatchKey.from(domain));
        assertEquals(DbObjType.COLUMN, ReferenceMatchKey.from(column).exactType());
        assertFalse(ReferenceMatchKey.from(table).equals(ReferenceMatchKey.from(aliased)));
        assertFalse(ReferenceMatchKey.from(table).equals(ReferenceMatchKey.from(local)));
    }

    @Test
    void primitiveMatchPackingPreservesCanonicalReferenceOrder() {
        List<ReferenceMatchKey> keys = List.of(
                new ReferenceMatchKey(MatchFamily.EXACT, DbObjType.CONSTRAINT,
                        "app", "item", null, null, false),
                new ReferenceMatchKey(MatchFamily.RELATION, null,
                        null, null, null, null, false),
                new ReferenceMatchKey(MatchFamily.TYPE, null,
                        "app", "item", null, null, false),
                new ReferenceMatchKey(MatchFamily.ROUTINE, null,
                        "app", "item", null, null, false),
                new ReferenceMatchKey(MatchFamily.RELATION, null,
                        "app", "item", null, "alias", false),
                new ReferenceMatchKey(MatchFamily.RELATION, null,
                        "app", "item", null, null, true),
                new ReferenceMatchKey(MatchFamily.EXACT, DbObjType.COLUMN,
                        "app", "item", "id", null, false),
                new ReferenceMatchKey(MatchFamily.RELATION, null,
                        "app", "item", null, null, false));
        StringTable strings = canonicalStringTable(keys, List.of());
        for (ReferenceMatchKey left : keys) {
            for (ReferenceMatchKey right : keys) {
                int expectedOrder = Integer.signum(
                        ReferenceMatchKey.CANONICAL_ORDER.compare(left, right));
                int actualOrder = Long.compareUnsigned(
                        ProjectIndexRecords.matchKeyWord1(left, strings),
                        ProjectIndexRecords.matchKeyWord1(right, strings));
                if (actualOrder == 0) {
                    actualOrder = Long.compareUnsigned(
                            ProjectIndexRecords.matchKeyWord2(left, strings),
                            ProjectIndexRecords.matchKeyWord2(right, strings));
                }
                assertEquals(expectedOrder, Integer.signum(actualOrder),
                        () -> left + " compared with " + right);
            }
        }
        assertTrue(Long.compareUnsigned(
                ProjectIndexRecords.matchMembership(0, Integer.MAX_VALUE),
                ProjectIndexRecords.matchMembership(1, 0)) < 0);

        var buffer = new ProjectIndexTupleBuffer(3, keys.size());
        for (int i = keys.size() - 1; i >= 0; i--) {
            ReferenceMatchKey key = keys.get(i);
            buffer.add(ProjectIndexRecords.matchKeyWord1(key, strings),
                    ProjectIndexRecords.matchKeyWord2(key, strings),
                    ProjectIndexRecords.matchMembership(0, i));
        }

        buffer.sort();
        List<ReferenceMatchKey> expected = new ArrayList<>(keys);
        expected.sort(ReferenceMatchKey.CANONICAL_ORDER);

        for (int row = 0; row < expected.size(); row++) {
            ReferenceMatchKey key = expected.get(row);
            assertEquals(ProjectIndexRecords.matchKeyWord1(key, strings),
                    buffer.word(row, 0));
            assertEquals(ProjectIndexRecords.matchKeyWord2(key, strings),
                    buffer.word(row, 1));
        }
    }

    @Test
    void primitiveCompletionPackingPreservesStringsThenDefinitionOrder() {
        assertEquals("SS", "\u00DF".toUpperCase(Locale.ROOT));
        List<CompletionMembership> memberships = List.of(
                new CompletionMembership("A\u0000", 3),
                new CompletionMembership("A", 7),
                new CompletionMembership("A\uD83D\uDE00", 2),
                new CompletionMembership("AAA", 4),
                new CompletionMembership("SS", 1),
                new CompletionMembership("A\uD83D\uDE00X", 8),
                new CompletionMembership("AAA", 4),
                new CompletionMembership("A", 1));
        StringTable strings = canonicalStringTable(
                List.of(), memberships.stream().map(CompletionMembership::trigram).toList());
        var buffer = new ProjectIndexTupleBuffer(1, memberships.size());
        memberships.forEach(value -> buffer.add(ProjectIndexRecords.completionMembership(
                strings.id(value.trigram()), value.definitionId())));

        buffer.sortAndDeduplicate();
        List<CompletionMembership> expected =
                new ArrayList<>(new LinkedHashSet<>(memberships));
        expected.sort(Comparator.comparing(CompletionMembership::trigram)
                .thenComparingInt(CompletionMembership::definitionId));

        assertEquals(expected.size(), buffer.rowCount());
        for (int row = 0; row < expected.size(); row++) {
            CompletionMembership value = expected.get(row);
            assertEquals(ProjectIndexRecords.completionMembership(
                    strings.id(value.trigram()), value.definitionId()),
                    buffer.word(row, 0));
        }
    }

    @Test
    void primitiveReversePackingPreservesMatchThenOriginPathOrder() {
        ReferenceMatchKey base = new ReferenceMatchKey(MatchFamily.RELATION, null,
                "app", "item", null, null, false);
        ReferenceMatchKey aliased = new ReferenceMatchKey(MatchFamily.RELATION, null,
                "app", "item", null, "alias", false);
        List<ReverseMembership> memberships = List.of(
                new ReverseMembership(aliased,
                        new IndexPathRef(IndexPathOrigin.PROJECT, "schema/a.sql")),
                new ReverseMembership(base,
                        new IndexPathRef(IndexPathOrigin.LIBRARY, "schema/a.sql")),
                new ReverseMembership(base,
                        new IndexPathRef(IndexPathOrigin.PROJECT, "schema/a\u0000.sql")),
                new ReverseMembership(base,
                        new IndexPathRef(IndexPathOrigin.PROJECT, "schema/a.sql")),
                new ReverseMembership(base,
                        new IndexPathRef(IndexPathOrigin.PROJECT,
                                "schema/a\uD83D\uDE00-extra.sql")),
                new ReverseMembership(base,
                        new IndexPathRef(IndexPathOrigin.PROJECT, "schema/a\uD83D\uDE00.sql")),
                new ReverseMembership(base,
                        new IndexPathRef(IndexPathOrigin.PROJECT, "schema/a.sql")));
        List<ReferenceMatchKey> keys =
                memberships.stream().map(ReverseMembership::key).toList();
        List<String> paths = memberships.stream()
                .map(value -> value.path().relativePath()).toList();
        StringTable strings = canonicalStringTable(keys, paths);
        var buffer = new ProjectIndexTupleBuffer(3, memberships.size());
        memberships.forEach(value -> {
            long second = ProjectIndexRecords.matchKeyWord2(value.key(), strings);
            buffer.add(ProjectIndexRecords.matchKeyWord1(value.key(), strings),
                    ProjectIndexRecords.withPathOrigin(
                            second, value.path().origin().ordinal()),
                    Integer.toUnsignedLong(strings.id(value.path().relativePath())));
        });

        buffer.sortAndDeduplicate();
        List<ReverseMembership> expected =
                new ArrayList<>(new LinkedHashSet<>(memberships));
        expected.sort(Comparator
                .comparing(ReverseMembership::key, ReferenceMatchKey.CANONICAL_ORDER)
                .thenComparing(ReverseMembership::path, ProjectIndexFormat.PATH_ORDER));

        assertEquals(expected.size(), buffer.rowCount());
        for (int row = 0; row < expected.size(); row++) {
            ReverseMembership value = expected.get(row);
            assertEquals(ProjectIndexRecords.matchKeyWord1(value.key(), strings),
                    buffer.word(row, 0));
            assertEquals(ProjectIndexRecords.withPathOrigin(
                    ProjectIndexRecords.matchKeyWord2(value.key(), strings),
                    value.path().origin().ordinal()), buffer.word(row, 1));
            assertEquals(Integer.toUnsignedLong(strings.id(value.path().relativePath())),
                    buffer.word(row, 2));
        }
    }

    @Test
    void definitionShapeExcludesBodyPathAndOffsetsButIncludesSignature() throws Exception {
        ProjectIndexData data = ProjectIndexFixtures.snapshotWithAllMetaKinds();
        PackedDefinition simple = data.files().getFirst().definitions().getFirst();
        PackedDefinition commentOnly = ProjectIndexFixtures.withDefinitionComment("new comment")
                .files().getFirst().definitions().getFirst();
        PackedDefinition function = data.files().get(1).definitions().getFirst();
        PackedLocation moved = new PackedLocation(IndexPathOrigin.LIBRARY,
                "different/location.sql", function.object().offset() + 1000,
                function.object().lineNumber() + 100, function.object().charPositionInLine() + 3,
                function.object().length(), function.object().reference(), function.object().action(),
                function.object().alias(), function.object().locationType(), function.object().danger());
        PackedDefinition relocated = function.withObject(moved);
        PackedDefinition changedSignature = function.withReturns("jsonb");
        PackedDefinition changedAlias = function.withObject(new PackedLocation(
                function.object().origin(), function.object().relativePath(),
                function.object().offset(), function.object().lineNumber(),
                function.object().charPositionInLine(), function.object().length(),
                function.object().reference(), function.object().action(), "different_alias",
                function.object().locationType(), function.object().danger()));
        PackedDefinition changedGlobalFamily = function.withObject(new PackedLocation(
                function.object().origin(), function.object().relativePath(),
                function.object().offset(), function.object().lineNumber(),
                function.object().charPositionInLine(), function.object().length(),
                function.object().reference(), function.object().action(),
                function.object().alias(), ObjectLocation.LocationType.LOCAL_REF,
                function.object().danger()));

        assertArrayEquals(DefinitionShapeHasher.hash(simple),
                DefinitionShapeHasher.hash(commentOnly));
        assertArrayEquals(DefinitionShapeHasher.hash(function), DefinitionShapeHasher.hash(relocated));
        assertFalse(Arrays.equals(DefinitionShapeHasher.hash(function),
                DefinitionShapeHasher.hash(changedSignature)));
        assertFalse(Arrays.equals(DefinitionShapeHasher.hash(function),
                DefinitionShapeHasher.hash(changedAlias)));
        assertFalse(Arrays.equals(DefinitionShapeHasher.hash(function),
                DefinitionShapeHasher.hash(changedGlobalFamily)));
    }

    @Test
    void payloadCrcFailureNamesTheSection() throws Exception {
        byte[] bytes = ProjectIndexCodec.encodeBase(ProjectIndexFixtures.snapshotWithAllMetaKinds());
        DirectoryEntry entry = entries(bytes).get(SectionType.DEFINITIONS);
        bytes[Math.toIntExact(entry.offset()) + 12] ^= 1;

        ProjectIndexFormatException error = assertThrows(ProjectIndexFormatException.class,
                () -> ProjectIndexCodec.decodeBase(bytes, 32L << 20));
        assertTrue(error.getMessage().contains("DEFINITIONS"));
        assertTrue(error.getMessage().contains("CRC"));
    }

    @Test
    void unsupportedMajorIsRejectedBeforeSections() throws Exception {
        byte[] bytes = ProjectIndexCodec.encodeBase(ProjectIndexFixtures.snapshotWithAllMetaKinds());
        putShort(bytes, 8, 3);
        updateHeaderCrc(bytes);

        ProjectIndexFormatException error = assertThrows(ProjectIndexFormatException.class,
                () -> ProjectIndexCodec.decodeBase(bytes, 32L << 20));
        assertTrue(error.getMessage().contains("Unsupported project index version 3"));
    }

    @Test
    void oversizedVarIntRecordIsRejectedWithoutAllocation() throws Exception {
        byte[] bytes = ProjectIndexCodec.encodeBase(ProjectIndexFixtures.snapshotWithAllMetaKinds());
        DirectoryEntry strings = entries(bytes).get(SectionType.STRINGS);
        int payload = Math.toIntExact(strings.offset()) + 12;
        int recordLengthOffset = skipVarInt(bytes, skipVarInt(bytes, payload));
        Arrays.fill(bytes, recordLengthOffset, Math.min(recordLengthOffset + 5, payload + strings.length()),
                (byte) 0xFF);
        rewriteSectionCrcs(bytes, SectionType.STRINGS);

        ProjectIndexFormatException error = assertThrows(ProjectIndexFormatException.class,
                () -> ProjectIndexCodec.decodeBase(bytes, 32L << 20));
        assertTrue(error.getMessage().contains("STRINGS"));
        assertTrue(error.getMessage().contains("varint") || error.getMessage().contains("remaining"));
    }

    @Test
    void signedVarIntOverflowIsRejectedBeforeAllocation() throws Exception {
        byte[] bytes = ProjectIndexCodec.encodeBase(ProjectIndexFixtures.snapshotWithAllMetaKinds());
        DirectoryEntry strings = entries(bytes).get(SectionType.STRINGS);
        int payload = Math.toIntExact(strings.offset()) + 12;
        bytes[payload] = (byte) 0x80;
        bytes[payload + 1] = (byte) 0x80;
        bytes[payload + 2] = (byte) 0x80;
        bytes[payload + 3] = (byte) 0x80;
        bytes[payload + 4] = (byte) 0x08;
        rewriteSectionCrcs(bytes, SectionType.STRINGS);

        ProjectIndexFormatException error = assertThrows(ProjectIndexFormatException.class,
                () -> ProjectIndexCodec.decodeBase(bytes, 32L << 20));
        assertTrue(error.getMessage().contains("STRINGS"));
        assertTrue(error.getMessage().contains("overflowing varint"));
    }

    @Test
    void oversizedRawBlockMayNotHideMultipleRecords() throws Exception {
        byte[] bytes = ProjectIndexCodec.encodeBase(ProjectIndexFixtures.twoLargePaths());
        DirectoryEntry strings = entries(bytes).get(SectionType.STRINGS);
        int payload = Math.toIntExact(strings.offset()) + 12;
        int firstBlockLengthOffset = skipVarInt(bytes, payload);
        int firstBlockLength = readVarInt(bytes, firstBlockLengthOffset);
        int firstBlockStart = skipVarInt(bytes, firstBlockLengthOffset);
        int secondBlockLengthOffset = firstBlockStart + firstBlockLength;
        int secondBlockLength = readVarInt(bytes, secondBlockLengthOffset);
        int secondBlockStart = skipVarInt(bytes, secondBlockLengthOffset);
        int mergedLength = secondBlockStart + secondBlockLength - firstBlockStart;
        assertTrue(mergedLength > ProjectIndexCodec.MAX_RAW_BLOCK_BYTES);
        putVarInt(bytes, firstBlockLengthOffset, mergedLength);
        rewriteSectionCrcs(bytes, SectionType.STRINGS);

        ProjectIndexFormatException error = assertThrows(ProjectIndexFormatException.class,
                () -> ProjectIndexCodec.decodeBase(bytes, 32L << 20));
        assertTrue(error.getMessage().contains("STRINGS raw block exceeds"));
    }

    @Test
    void zeroLengthRawBlockIsRejectedImmediately() throws Exception {
        byte[] bytes = ProjectIndexCodec.encodeBase(ProjectIndexFixtures.minimalSnapshot());
        DirectoryEntry strings = entries(bytes).get(SectionType.STRINGS);
        int payload = Math.toIntExact(strings.offset()) + 12;
        int blockLengthOffset = skipVarInt(bytes, payload);
        assertEquals(blockLengthOffset + 1, skipVarInt(bytes, blockLengthOffset));
        bytes[blockLengthOffset] = 0;
        rewriteSectionCrcs(bytes, SectionType.STRINGS);

        ProjectIndexFormatException error = assertThrows(ProjectIndexFormatException.class,
                () -> ProjectIndexCodec.decodeBase(bytes, 32L << 20));

        assertTrue(error.getMessage().contains("zero-length raw block"));
    }

    @Test
    void rejectsTruncatedDirectory() throws Exception {
        byte[] bytes = ProjectIndexCodec.encodeBase(ProjectIndexFixtures.snapshotWithAllMetaKinds());
        byte[] truncated = Arrays.copyOf(bytes, bytes.length - FOOTER_SIZE - 1);
        ProjectIndexFormatException error = assertThrows(ProjectIndexFormatException.class,
                () -> ProjectIndexCodec.decodeBase(truncated, 32L << 20));
        assertTrue(error.getMessage().contains("truncated"));
    }

    @Test
    void rejectsBadDirectoryMagic() throws Exception {
        byte[] bytes = ProjectIndexCodec.encodeBase(ProjectIndexFixtures.snapshotWithAllMetaKinds());
        bytes[Math.toIntExact(getLong(bytes, HEADER_DIRECTORY_OFFSET))] ^= 1;
        ProjectIndexFormatException error = assertThrows(ProjectIndexFormatException.class,
                () -> ProjectIndexCodec.decodeBase(bytes, 32L << 20));
        assertTrue(error.getMessage().contains("directory magic"));
    }

    @Test
    void rejectsBadDirectoryCount() throws Exception {
        byte[] bytes = ProjectIndexCodec.encodeBase(ProjectIndexFixtures.snapshotWithAllMetaKinds());
        putInt(bytes, HEADER_DIRECTORY_COUNT, 9);
        updateHeaderCrc(bytes);
        ProjectIndexFormatException error = assertThrows(ProjectIndexFormatException.class,
                () -> ProjectIndexCodec.decodeBase(bytes, 32L << 20));
        assertTrue(error.getMessage().contains("section count"));
    }

    @Test
    void rejectsBadDirectoryLength() throws Exception {
        byte[] bytes = ProjectIndexCodec.encodeBase(ProjectIndexFixtures.snapshotWithAllMetaKinds());
        putInt(bytes, HEADER_DIRECTORY_LENGTH, getInt(bytes, HEADER_DIRECTORY_LENGTH) + 1);
        updateHeaderCrc(bytes);
        ProjectIndexFormatException error = assertThrows(ProjectIndexFormatException.class,
                () -> ProjectIndexCodec.decodeBase(bytes, 32L << 20));
        assertTrue(error.getMessage().contains("directory length"));
    }

    @Test
    void rejectsBadDirectoryCrc() throws Exception {
        byte[] bytes = ProjectIndexCodec.encodeBase(ProjectIndexFixtures.snapshotWithAllMetaKinds());
        putInt(bytes, HEADER_DIRECTORY_CRC, getInt(bytes, HEADER_DIRECTORY_CRC) ^ 1);
        updateHeaderCrc(bytes);
        ProjectIndexFormatException error = assertThrows(ProjectIndexFormatException.class,
                () -> ProjectIndexCodec.decodeBase(bytes, 32L << 20));
        assertTrue(error.getMessage().contains("directory CRC"));
    }

    @Test
    void rejectsDuplicateRequiredSection() throws Exception {
        byte[] bytes = ProjectIndexCodec.encodeBase(ProjectIndexFixtures.snapshotWithAllMetaKinds());
        Map<SectionType, DirectoryEntry> entries = entries(bytes);
        DirectoryEntry source = entries.get(SectionType.DEFINITIONS);
        DirectoryEntry duplicate = entries.get(SectionType.LOCATIONS);
        bytes[duplicate.directoryEntryOffset()] = (byte) SectionType.DEFINITIONS.id();
        bytes[Math.toIntExact(duplicate.offset())] = (byte) SectionType.DEFINITIONS.id();
        updateDirectoryAndHeaderCrc(bytes);

        ProjectIndexFormatException error = assertThrows(ProjectIndexFormatException.class,
                () -> ProjectIndexCodec.decodeBase(bytes, 32L << 20));
        assertTrue(error.getMessage().contains("Duplicate required section DEFINITIONS"));
    }

    @Test
    void rejectsOverlappingSectionsBeforeReadingPayloads() throws Exception {
        byte[] bytes = ProjectIndexCodec.encodeBase(ProjectIndexFixtures.snapshotWithAllMetaKinds());
        Map<SectionType, DirectoryEntry> entries = entries(bytes);
        DirectoryEntry first = entries.get(SectionType.MANIFEST);
        DirectoryEntry second = entries.get(SectionType.STRINGS);
        putLong(bytes, second.directoryEntryOffset() + 4, first.offset());
        updateDirectoryAndHeaderCrc(bytes);

        ProjectIndexFormatException error = assertThrows(ProjectIndexFormatException.class,
                () -> ProjectIndexCodec.decodeBase(bytes, 32L << 20));
        assertTrue(error.getMessage().contains("overlap"));
    }

    @Test
    void rejectsUnaccountedGapAfterHeader() throws Exception {
        byte[] bytes = ProjectIndexCodec.encodeBase(ProjectIndexFixtures.snapshotWithAllMetaKinds());
        byte[] withGap = insertGap(bytes, HEADER_SIZE);

        ProjectIndexFormatException error = assertThrows(ProjectIndexFormatException.class,
                () -> ProjectIndexCodec.decodeBase(withGap, 32L << 20));

        assertTrue(error.getMessage().contains("gap"));
    }

    @Test
    void rejectsUnaccountedGapBeforeDirectory() throws Exception {
        byte[] bytes = ProjectIndexCodec.encodeBase(ProjectIndexFixtures.snapshotWithAllMetaKinds());
        byte[] withGap = insertGap(bytes,
                Math.toIntExact(getLong(bytes, HEADER_DIRECTORY_OFFSET)));

        ProjectIndexFormatException error = assertThrows(ProjectIndexFormatException.class,
                () -> ProjectIndexCodec.decodeBase(withGap, 32L << 20));

        assertTrue(error.getMessage().contains("gap"));
    }

    @Test
    void rejectsUnaccountedGapBetweenSections() throws Exception {
        byte[] bytes = ProjectIndexCodec.encodeBase(ProjectIndexFixtures.snapshotWithAllMetaKinds());
        DirectoryEntry manifest = entries(bytes).get(SectionType.MANIFEST);
        int sectionEnd = Math.toIntExact(manifest.offset()) + 12 + manifest.length();
        byte[] withGap = insertGap(bytes, sectionEnd);

        ProjectIndexFormatException error = assertThrows(ProjectIndexFormatException.class,
                () -> ProjectIndexCodec.decodeBase(withGap, 32L << 20));

        assertTrue(error.getMessage().contains("gap"));
    }

    @Test
    void rejectsSectionCrossingDirectory() throws Exception {
        byte[] bytes = ProjectIndexCodec.encodeBase(ProjectIndexFixtures.snapshotWithAllMetaKinds());
        DirectoryEntry entry = entries(bytes).get(SectionType.MANIFEST);
        long directoryOffset = getLong(bytes, HEADER_DIRECTORY_OFFSET);
        putLong(bytes, entry.directoryEntryOffset() + 4, directoryOffset - 8);
        updateDirectoryAndHeaderCrc(bytes);

        ProjectIndexFormatException error = assertThrows(ProjectIndexFormatException.class,
                () -> ProjectIndexCodec.decodeBase(bytes, 32L << 20));
        assertTrue(error.getMessage().contains("crosses directory"));
    }

    @Test
    void rejectsMismatchedFooterDirectoryOffset() throws Exception {
        byte[] bytes = ProjectIndexCodec.encodeBase(ProjectIndexFixtures.snapshotWithAllMetaKinds());
        int footer = bytes.length - FOOTER_SIZE;
        putLong(bytes, footer + 8, getLong(bytes, footer + 8) + 1);
        putInt(bytes, footer + 16, crc(bytes, footer, 16));

        ProjectIndexFormatException error = assertThrows(ProjectIndexFormatException.class,
                () -> ProjectIndexCodec.decodeBase(bytes, 32L << 20));
        assertTrue(error.getMessage().contains("footer directory offset"));
    }

    @Test
    void rejectsTrailingBytesAfterFooter() throws Exception {
        byte[] bytes = ProjectIndexCodec.encodeBase(ProjectIndexFixtures.snapshotWithAllMetaKinds());
        byte[] trailing = Arrays.copyOf(bytes, bytes.length + 1);

        ProjectIndexFormatException error = assertThrows(ProjectIndexFormatException.class,
                () -> ProjectIndexCodec.decodeBase(trailing, 32L << 20));
        assertTrue(error.getMessage().contains("trailing bytes"));
    }

    @Test
    void rawBlocksAreBoundedAndNeverSplitRecords() throws Exception {
        byte[] bytes = ProjectIndexCodec.encodeBase(ProjectIndexFixtures.repeatedLocations(20_000));
        for (SectionType type : List.of(SectionType.STRINGS, SectionType.DEFINITIONS, SectionType.LOCATIONS)) {
            DirectoryEntry entry = entries(bytes).get(type);
            int cursor = Math.toIntExact(entry.offset()) + 12;
            int records = readVarInt(bytes, cursor);
            cursor = skipVarInt(bytes, cursor);
            int decodedRecords = 0;
            int decodedBlocks = 0;
            while (decodedRecords < records) {
                int blockLength = readVarInt(bytes, cursor);
                cursor = skipVarInt(bytes, cursor);
                assertTrue(blockLength <= ProjectIndexCodec.MAX_RAW_BLOCK_BYTES,
                        () -> type + " block is too large: " + blockLength);
                int blockEnd = cursor + blockLength;
                while (cursor < blockEnd) {
                    int recordLength = readVarInt(bytes, cursor);
                    cursor = skipVarInt(bytes, cursor) + recordLength;
                    assertTrue(cursor <= blockEnd, () -> type + " record crosses a block boundary");
                    decodedRecords++;
                }
                assertEquals(blockEnd, cursor);
                decodedBlocks++;
            }
            assertEquals(records, decodedRecords);
            assertEquals(decodedBlocks, readVarInt(bytes, cursor));
            cursor = skipVarInt(bytes, cursor);
            assertEquals(Math.toIntExact(entry.offset()) + 12 + entry.length(), cursor);
        }
    }

    @Test
    void packedHundredThousandLocationsIsBelowSixtyPercentOfLegacySerialization() throws Exception {
        ProjectIndexData data = ProjectIndexFixtures.repeatedLocations(100_000);
        byte[] packed = ProjectIndexCodec.encodeBase(data);
        byte[] legacy = LegacyProjectIndexSizeReference.serialize(
                ProjectIndexFixtures.ABSOLUTE_ROOT + "/schema/repeated.sql",
                ProjectIndexFixtures.legacyLocations(data));

        assertTrue(packed.length < legacy.length * 0.60,
                () -> "packed=" + packed.length + ", legacy=" + legacy.length);
    }

    private static PackedLocation packed(IndexPathRef path, DbObjType type, String alias, boolean global) {
        ObjectLocation.LocationType locationType = global
                ? ObjectLocation.LocationType.REFERENCE : ObjectLocation.LocationType.LOCAL_REF;
        ObjectLocation location = new ObjectLocation.Builder()
                .setFilePath(ProjectIndexFixtures.ABSOLUTE_ROOT + '/' + path.relativePath())
                .setOffset(1)
                .setLineNumber(1)
                .setCharPositionInLine(0)
                .setLength(3)
                .setReference(new ObjectReference("app", "same_name", type))
                .setAlias(alias)
                .setLocationType(locationType)
                .build();
        return PackedLocation.from(location, path);
    }

    private static void assertOnePathStringIdentity(ProjectIndexData data) {
        Set<String> identities = Collections.newSetFromMap(new IdentityHashMap<>());
        for (FileContribution file : data.files()) {
            identities.add(file.path().relativePath());
            file.definitions().forEach(
                    definition -> identities.add(definition.object().relativePath()));
            file.locations().forEach(location -> identities.add(location.relativePath()));
        }
        assertEquals(1, identities.size());
    }

    private static StringTable canonicalStringTable(List<ReferenceMatchKey> keys,
            List<String> additional) {
        TreeSet<String> values = new TreeSet<>();
        for (ReferenceMatchKey key : keys) {
            addIfPresent(values, key.schema());
            addIfPresent(values, key.table());
            addIfPresent(values, key.column());
            addIfPresent(values, key.alias());
        }
        additional.forEach(value -> addIfPresent(values, value));
        return StringTable.build(new ProspectiveWork(values),
                new AuxiliaryIndexes(Map.of(), Map.of(), Map.of()));
    }

    private static void addIfPresent(Set<String> values, String value) {
        if (value != null) {
            values.add(value);
        }
    }

    private static String sha256Hex(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static Map<SectionType, DirectoryEntry> entries(byte[] bytes) {
        int directory = Math.toIntExact(getLong(bytes, HEADER_DIRECTORY_OFFSET));
        int count = getInt(bytes, HEADER_DIRECTORY_COUNT);
        Map<SectionType, DirectoryEntry> result = new HashMap<>();
        int cursor = directory + 8;
        for (int i = 0; i < count; i++) {
            int entryOffset = cursor;
            SectionType type = SectionType.fromId(Byte.toUnsignedInt(bytes[cursor]));
            long offset = getLong(bytes, cursor + 4);
            int length = getInt(bytes, cursor + 12);
            int payloadCrc = getInt(bytes, cursor + 16);
            result.put(type, new DirectoryEntry(entryOffset, offset, length, payloadCrc));
            cursor += DIRECTORY_ENTRY_SIZE;
        }
        return result;
    }

    private static void rewriteSectionCrcs(byte[] bytes, SectionType type) {
        DirectoryEntry entry = entries(bytes).get(type);
        int payloadOffset = Math.toIntExact(entry.offset()) + 12;
        int payloadCrc = crc(bytes, payloadOffset, entry.length());
        putInt(bytes, Math.toIntExact(entry.offset()) + 8, payloadCrc);
        putInt(bytes, entry.directoryEntryOffset() + 16, payloadCrc);
        updateDirectoryAndHeaderCrc(bytes);
    }

    private static byte[] insertGap(byte[] original, int insertionOffset) {
        int oldDirectoryOffset = Math.toIntExact(getLong(original, HEADER_DIRECTORY_OFFSET));
        byte[] result = new byte[original.length + 1];
        System.arraycopy(original, 0, result, 0, insertionOffset);
        result[insertionOffset] = 0x5A;
        System.arraycopy(original, insertionOffset, result, insertionOffset + 1,
                original.length - insertionOffset);

        int newDirectoryOffset = oldDirectoryOffset + (insertionOffset <= oldDirectoryOffset ? 1 : 0);
        putLong(result, HEADER_DIRECTORY_OFFSET, newDirectoryOffset);
        int count = getInt(result, HEADER_DIRECTORY_COUNT);
        int cursor = newDirectoryOffset + 8;
        for (int i = 0; i < count; i++) {
            long sectionOffset = getLong(result, cursor + 4);
            if (sectionOffset >= insertionOffset) {
                putLong(result, cursor + 4, sectionOffset + 1);
            }
            cursor += DIRECTORY_ENTRY_SIZE;
        }

        int footer = result.length - FOOTER_SIZE;
        putLong(result, footer + 8, newDirectoryOffset);
        putInt(result, footer + 16, crc(result, footer, FOOTER_SIZE - Integer.BYTES));
        updateDirectoryAndHeaderCrc(result);
        return result;
    }

    private static void updateDirectoryAndHeaderCrc(byte[] bytes) {
        int directory = Math.toIntExact(getLong(bytes, HEADER_DIRECTORY_OFFSET));
        int directoryLength = getInt(bytes, HEADER_DIRECTORY_LENGTH);
        int directoryCrc = crc(bytes, directory, directoryLength - 4);
        putInt(bytes, directory + directoryLength - 4, directoryCrc);
        putInt(bytes, HEADER_DIRECTORY_CRC, directoryCrc);
        updateHeaderCrc(bytes);
    }

    private static void updateHeaderCrc(byte[] bytes) {
        putInt(bytes, HEADER_CRC, crc(bytes, 0, HEADER_CRC));
    }

    private static int readVarInt(byte[] bytes, int offset) {
        int value = 0;
        int shift = 0;
        for (int i = 0; i < 5; i++) {
            int current = Byte.toUnsignedInt(bytes[offset + i]);
            value |= (current & 0x7F) << shift;
            if ((current & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
        throw new IllegalArgumentException("invalid fixture varint");
    }

    private static int skipVarInt(byte[] bytes, int offset) {
        for (int i = 0; i < 5; i++) {
            if ((bytes[offset + i] & 0x80) == 0) {
                return offset + i + 1;
            }
        }
        throw new IllegalArgumentException("invalid fixture varint");
    }

    private static void putVarInt(byte[] bytes, int offset, int value) {
        int originalEnd = skipVarInt(bytes, offset);
        int cursor = offset;
        while ((value & ~0x7F) != 0) {
            bytes[cursor++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        bytes[cursor++] = (byte) value;
        assertEquals(originalEnd, cursor, "corruption fixture must preserve varint width");
    }

    private static int crc(byte[] bytes, int offset, int length) {
        var crc = new CRC32C();
        crc.update(bytes, offset, length);
        return (int) crc.getValue();
    }

    private static int getInt(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes, offset, Integer.BYTES).order(ByteOrder.BIG_ENDIAN).getInt();
    }

    private static long getLong(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes, offset, Long.BYTES).order(ByteOrder.BIG_ENDIAN).getLong();
    }

    private static void putShort(byte[] bytes, int offset, int value) {
        ByteBuffer.wrap(bytes, offset, Short.BYTES).order(ByteOrder.BIG_ENDIAN).putShort((short) value);
    }

    private static void putInt(byte[] bytes, int offset, int value) {
        ByteBuffer.wrap(bytes, offset, Integer.BYTES).order(ByteOrder.BIG_ENDIAN).putInt(value);
    }

    private static void putLong(byte[] bytes, int offset, long value) {
        ByteBuffer.wrap(bytes, offset, Long.BYTES).order(ByteOrder.BIG_ENDIAN).putLong(value);
    }

    private record DirectoryEntry(int directoryEntryOffset, long offset, int length, int payloadCrc) {
    }

    private record CompletionMembership(String trigram, int definitionId) {
    }

    private record ReverseMembership(ReferenceMatchKey key, IndexPathRef path) {
    }

    private static final class BoundedReadChannel implements SeekableByteChannel {
        private final SeekableByteChannel delegate;
        private final int maximumRequestBytes;
        private int maxRequestedBytes;

        private BoundedReadChannel(SeekableByteChannel delegate, int maximumRequestBytes) {
            this.delegate = delegate;
            this.maximumRequestBytes = maximumRequestBytes;
        }

        int maxRequestedBytes() {
            return maxRequestedBytes;
        }

        @Override
        public int read(ByteBuffer destination) throws IOException {
            int requested = destination.remaining();
            maxRequestedBytes = Math.max(maxRequestedBytes, requested);
            if (requested > maximumRequestBytes) {
                throw new IOException("Reader requested an unbounded buffer: " + requested);
            }
            return delegate.read(destination);
        }

        @Override
        public int write(ByteBuffer source) throws IOException {
            throw new NonWritableChannelException();
        }

        @Override
        public long position() throws IOException {
            return delegate.position();
        }

        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            delegate.position(newPosition);
            return this;
        }

        @Override
        public long size() throws IOException {
            return delegate.size();
        }

        @Override
        public SeekableByteChannel truncate(long size) throws IOException {
            throw new NonWritableChannelException();
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
}
