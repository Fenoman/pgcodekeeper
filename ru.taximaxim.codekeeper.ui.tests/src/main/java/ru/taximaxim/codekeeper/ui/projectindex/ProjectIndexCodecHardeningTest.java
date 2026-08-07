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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexFormat.*;
import static ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexRecords.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32C;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.schema.ICast.CastContext;

import ru.taximaxim.codekeeper.ui.projectindex.PackedDefinition.MetaKind;
import ru.taximaxim.codekeeper.ui.projectindex.PackedDefinition.NameType;

class ProjectIndexCodecHardeningTest {

    @Test
    void rejectsSwappedDefinitionsInsideCanonicalPathRange() throws Exception {
        Fixture fixture = fixture(ProjectIndexFixtures.comparatorCollisions(false));
        byte[] corrupted = replaceSection(fixture.bytes(), SectionType.DEFINITIONS,
                swapFirstTwoBlockRecords(payload(fixture.bytes(), SectionType.DEFINITIONS)));

        assertOrderFailure(corrupted, "DEFINITIONS");
    }

    @Test
    void rejectsSwappedLocationsInsideCanonicalPathRange() throws Exception {
        Fixture fixture = fixture(ProjectIndexFixtures.comparatorCollisions(false));
        byte[] corrupted = replaceSection(fixture.bytes(), SectionType.LOCATIONS,
                swapFirstTwoBlockRecords(payload(fixture.bytes(), SectionType.LOCATIONS)));

        assertOrderFailure(corrupted, "LOCATIONS");
    }

    @Test
    void rejectsNonMinimalVarIntsButAcceptsEveryLegitimateBoundary() throws Exception {
        for (byte[] invalid : List.of(
                new byte[] {(byte) 0x80, 0},
                new byte[] {(byte) 0x82, 0},
                new byte[] {(byte) 0xFF, (byte) 0x80, 0})) {
            BinaryReader reader = new BinaryReader(ProjectIndexSource.of(invalid), 0,
                    invalid.length, "test varint", null);
            ProjectIndexFormatException error = assertThrows(ProjectIndexFormatException.class,
                    reader::readVarInt);
            assertTrue(error.getMessage().contains("non-minimal"), error::getMessage);
        }

        for (int value : new int[] {0, 1, 127, 128, 16_383, 16_384, 2_097_151,
                2_097_152, 268_435_455, Integer.MAX_VALUE}) {
            byte[] encoded = encodeVarInt(value);
            BinaryReader reader = new BinaryReader(ProjectIndexSource.of(encoded), 0,
                    encoded.length, "test varint", null);
            assertEquals(value, reader.readVarInt());
            reader.requireEnd();
        }
    }

    @Test
    void aReaderHoldsOneRunInsteadOfAskingForEveryByte() throws Exception {
        byte[] bytes = new byte[64];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i + 1);
        }
        CountingSource source = new CountingSource(bytes, bytes.length);
        BinaryReader reader = new BinaryReader(source, 0, bytes.length, "held run", null);

        for (int i = 0; i < bytes.length; i++) {
            assertEquals(i + 1, reader.readUnsignedByte());
        }

        assertEquals(1, source.locates(),
                "one hold answers every byte inside it");
        assertEquals(0, source.byteReads(),
                "so no byte pays its own way back to the source");
    }

    @Test
    void slicesReadThroughTheHoldTheirParentAlreadyHas() throws Exception {
        // Eight records of "length, payload" - the shape every packed section
        // is read in: a varint off the parent, then a slice over the payload,
        // then the parent again for the next one.
        byte[] bytes = new byte[8 * 4];
        for (int record = 0; record < 8; record++) {
            bytes[record * 4] = 3;
            for (int i = 1; i < 4; i++) {
                bytes[record * 4 + i] = (byte) (record * 10 + i);
            }
        }
        CountingSource source = new CountingSource(bytes, bytes.length);
        BinaryReader reader = new BinaryReader(source, 0, bytes.length, "records", null);

        for (int record = 0; record < 8; record++) {
            BinaryReader payload = reader.slice(reader.readVarInt(), "record");
            for (int i = 1; i < 4; i++) {
                assertEquals(record * 10 + i, payload.readUnsignedByte());
            }
            payload.requireEnd();
        }
        reader.requireEnd();

        assertEquals(1, source.locates(),
                "a record and the block it sits in share one hold");
        assertEquals(0, source.byteReads());
    }

    @Test
    void readingPastAHeldRunAsksForTheNextOne() throws Exception {
        byte[] bytes = new byte[48];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) i;
        }
        CountingSource source = new CountingSource(bytes, 16);
        BinaryReader reader = new BinaryReader(source, 0, bytes.length, "runs", null);

        for (int i = 0; i < bytes.length; i++) {
            assertEquals(i, reader.readUnsignedByte(),
                    "every byte reads as itself across run boundaries");
        }

        assertEquals(3, source.locates(), "one hold per run of 16 over 48 bytes");
        assertEquals(0, source.byteReads());
    }

    @Test
    void aSourceThatHandsOutNoRunIsAskedForOneOnlyOnce() throws Exception {
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i + 1);
        }
        CountingSource source = new CountingSource(bytes, 0);
        BinaryReader reader = new BinaryReader(source, 0, bytes.length, "no runs", null);

        for (int i = 0; i < bytes.length; i++) {
            assertEquals(i + 1, reader.readUnsignedByte());
        }

        assertEquals(1, source.locateAttempts(),
                "a refusal is remembered rather than asked again per byte");
        assertEquals(0, source.locates());
        assertEquals(bytes.length, source.byteReads(),
                "and such a source keeps the per-byte path, which is always correct");
    }

    @Test
    void aBulkReadInsideTheHeldRunComesOffItWithoutAskingAgain() throws Exception {
        byte[] text = "схема.таблица".getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[text.length + 4];
        System.arraycopy(text, 0, bytes, 2, text.length);
        CountingSource source = new CountingSource(bytes, bytes.length);
        BinaryReader reader = new BinaryReader(source, 0, bytes.length, "utf8", null);

        assertEquals(0, reader.readUnsignedByte());
        assertEquals(0, reader.readUnsignedByte());
        assertEquals("схема.таблица", reader.readUtf8(text.length));
        assertArrayEquals(new byte[2], reader.readBytes(2));
        reader.requireEnd();

        assertEquals(1, source.locates());
        assertEquals(0, source.bulkReads(),
                "a string inside the held run is copied out of it");
    }

    @Test
    void aBulkReadPastTheHeldRunGoesBackToTheSource() throws Exception {
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i + 1);
        }
        CountingSource source = new CountingSource(bytes, 16);
        BinaryReader reader = new BinaryReader(source, 0, bytes.length, "spanning", null);

        assertEquals(1, reader.readUnsignedByte());
        byte[] spanning = reader.readBytes(24);

        assertArrayEquals(Arrays.copyOfRange(bytes, 1, 25), spanning,
                "a read wider than one run still reads the right bytes");
        assertEquals(1, source.bulkReads(), "and it goes to the source for them");
    }

    @Test
    void onlySourcesOverBytesThatStayPutHandOutARun() throws Exception {
        byte[] bytes = ProjectIndexCodec.encodeBase(ProjectIndexFixtures.minimalSnapshot());
        var run = new ProjectIndexSource.ByteRun();

        assertTrue(ProjectIndexSource.of(bytes).locate(run, 0),
                "an array source hands out the array it was given");

        try (var channel = memoryChannel(bytes)) {
            ProjectIndexSource fromChannel = ProjectIndexSource.of(channel,
                    new AllocationBudget(ProjectIndexStore.DEFAULT_CACHE_BYTES),
                    size -> new byte[size]);

            // Both of these decode into one page they refill in place, so a
            // run over that page would keep claiming positions whose bytes
            // another reader - or their own crc32c - has since replaced.
            assertFalse(fromChannel.locate(run, 0),
                    "a channel source refills one page under every reader over it");
            assertFalse(new ReplayProjectIndexSource(fromChannel).locate(run, 0),
                    "and so does a replay window");
        }
    }

    @Test
    void inMemoryWritersRejectGrowthBeforeOverflowOrAllocation() {
        BinaryWriter writer = new BinaryWriter(4);
        writer.writeInt(42);
        assertEquals(4, writer.size());
        assertThrows(IllegalArgumentException.class, () -> writer.writeByte(1));
        assertEquals(4, writer.size());

        int limit = 1 << 30;
        assertEquals(limit, nextInMemoryCapacity(limit / 2, (long) limit / 2 + 1, limit));
        assertEquals(limit, nextInMemoryCapacity(limit, limit, limit));
        assertThrows(IllegalArgumentException.class,
                () -> nextInMemoryCapacity(limit, (long) limit + 1, limit));
    }

    @Test
    void auxiliaryMemoryGuardThrowsTypedPersistenceReason() {
        var failure = assertThrows(ProjectIndexPersistenceException.class,
                () -> requireProspectiveBuildBytes(
                        MAX_PROSPECTIVE_BUILD_BYTES + 1));

        assertEquals(
                ProjectIndexTelemetry.PersistenceReason
                        .AUXILIARY_MEMORY_LIMIT,
                failure.reason());
    }

    @Test
    void writerPreflightLeavesExistingChannelByteIdentical() throws Exception {
        ProjectIndexData source = ProjectIndexFixtures.snapshotWithAllMetaKinds();
        try (var channel = memoryChannel(new byte[] {1, 2, 3, 4, 5})) {
            channel.position(3);
            byte[] before = channel.toByteArray();
            long position = channel.position();

            assertThrows(IllegalArgumentException.class,
                    () -> ProjectIndexBaseWriter.write(source, channel, 16));

            assertArrayEquals(before, channel.toByteArray());
            assertEquals(position, channel.position());
        }
    }

    @Test
    void writerExactPreflightSizeMatchesEncodedBytes() throws Exception {
        for (ProjectIndexData source : List.of(
                ProjectIndexFixtures.snapshotWithAllMetaKinds(),
                ProjectIndexFixtures.twoLargePaths(),
                ProjectIndexFixtures.repeatedLocations(20_000))) {
            assertEquals(ProjectIndexCodec.encodeBase(source).length,
                    ProjectIndexBaseWriter.encodedSize(source));
        }
    }

    @Test
    void repeatedCompletionTrigramsRoundTripWithoutDuplicateMemberships() throws Exception {
        ProjectIndexData source = ProjectIndexFixtures.withDefinitionBareName(
                "ABABA".repeat(2_000));
        byte[] encoded = ProjectIndexCodec.encodeBase(source);

        ProjectIndexData decoded = ProjectIndexCodec.decodeBase(encoded, 128L << 20);
        assertArrayEquals(encoded, ProjectIndexCodec.encodeBase(decoded));
    }

    @Test
    void completionIndexHandlesOverlapShortNamesAndUnicodeExpansion() throws Exception {
        ProjectIndexData source = ProjectIndexFixtures.withFunctionBareNames(
                List.of("ABABA", "AB", "ABC", "aßa"));
        CanonicalData data = canonicalize(source);
        AuxiliaryIndexes indexes = AuxiliaryIndexes.build(data);
        Map<String, Integer> ids = new HashMap<>();
        for (int i = 0; i < data.definitions().size(); i++) {
            PackedDefinition definition = data.definitions().get(i);
            if (definition.kind() == MetaKind.FUNCTION) {
                ids.put(definition.bareName(), i);
            }
        }

        assertEquals(List.of(ids.get("AB")), indexes.completion().get("AB"));
        assertEquals(List.of(ids.get("ABABA")), indexes.completion().get("ABA"));
        assertEquals(List.of(ids.get("ABABA")), indexes.completion().get("BAB"));
        assertEquals(List.of(ids.get("ABC")), indexes.completion().get("ABC"));
        assertEquals(List.of(ids.get("aßa")), indexes.completion().get("ASS"));
        assertEquals(List.of(ids.get("aßa")), indexes.completion().get("SSA"));
    }

    @Test
    void writerRejectsProspectiveCompletionGraphBeforeBuildingIt() throws Exception {
        ProjectIndexData source = ProjectIndexFixtures.completionHeavy(5_000);
        try (var channel = memoryChannel(new byte[] {1, 2, 3, 4, 5})) {
            byte[] before = channel.toByteArray();
            long position = channel.position();

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> ProjectIndexBaseWriter.write(source, channel, 16 << 10));

            assertTrue(error.getMessage().contains("prospective COMPLETION_TRIGRAMS"),
                    error::getMessage);
            assertArrayEquals(before, channel.toByteArray());
            assertEquals(position, channel.position());
        }
    }

    @Test
    void writerBoundsUnicodeCompletionExpansionBeforeMutatingChannel() throws Exception {
        ProjectIndexData source = ProjectIndexFixtures.withFunctionBareNames(
                List.of("\u00DF".repeat(2_000)));
        try (var channel = memoryChannel(new byte[] {1, 2, 3, 4, 5})) {
            channel.position(3);
            byte[] before = channel.toByteArray();
            long position = channel.position();

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> ProjectIndexBaseWriter.write(source, channel, 16 << 10));

            assertTrue(error.getMessage().contains("completion name uppercase bound"),
                    error::getMessage);
            assertArrayEquals(before, channel.toByteArray());
            assertEquals(position, channel.position());
        }
    }

    @Test
    void rejectsNonCanonicalWirePathWithValidCrc() throws Exception {
        Fixture fixture = fixture(ProjectIndexFixtures.minimalSnapshot());
        List<String> values = new ArrayList<>(fixture.strings().values());
        values.set(values.indexOf("x.sql"), "x/../x.sql");
        byte[] corrupted = replaceSection(fixture.bytes(), SectionType.STRINGS,
                encodeStrings(values));

        ProjectIndexFormatException error = assertThrows(ProjectIndexFormatException.class,
                () -> ProjectIndexCodec.decodeBase(corrupted, 32L << 20));

        assertTrue(error.getMessage().contains("canonical"), error::getMessage);
    }

    @Test
    void rejectsNullDefinitionCommentWithValidCrc() throws Exception {
        Fixture fixture = fixture();
        byte[] definitions = payload(fixture.bytes(), SectionType.DEFINITIONS);
        int cursor = firstBlockRecordBody(definitions);
        cursor = skipVarInt(definitions, cursor);
        cursor = skipLocation(definitions, cursor);
        cursor = skipVarInt(definitions, cursor);
        definitions[cursor] = 0;
        byte[] corrupted = replaceSection(fixture.bytes(), SectionType.DEFINITIONS, definitions);

        ProjectIndexFormatException error = assertThrows(ProjectIndexFormatException.class,
                () -> ProjectIndexCodec.decodeBase(corrupted, 32L << 20));

        assertTrue(error.getMessage().contains("DEFINITIONS"), error::getMessage);
    }

    @Test
    void rejectsEmptyUnresolvedRecordWithValidCrc() throws Exception {
        Fixture fixture = fixture();
        byte[] unresolved = payload(fixture.bytes(), SectionType.UNRESOLVED_FILES);
        int anyOnly = anyOnlyUnresolvedBoolean(unresolved);
        unresolved[anyOnly] = 0;
        byte[] corrupted = replaceSection(fixture.bytes(), SectionType.UNRESOLVED_FILES,
                unresolved);

        ProjectIndexFormatException error = assertThrows(ProjectIndexFormatException.class,
                () -> ProjectIndexCodec.decodeBase(corrupted, 32L << 20));

        assertTrue(error.getMessage().contains("empty record"), error::getMessage);
    }

    @Test
    void rejectsUnusedStringRecordWithValidCrc() throws Exception {
        Fixture fixture = fixture();
        List<String> values = new ArrayList<>(fixture.strings().values());
        values.add("zzzz_unused");
        byte[] corrupted = replaceSection(fixture.bytes(), SectionType.STRINGS,
                encodeStrings(values));

        ProjectIndexFormatException error = assertThrows(ProjectIndexFormatException.class,
                () -> ProjectIndexCodec.decodeBase(corrupted, 32L << 20));

        assertTrue(error.getMessage().contains("unused record"), error::getMessage);
    }

    @Test
    void packedDefinitionRejectsFieldsOutsideItsDiscriminatedShape() throws Exception {
        PackedLocation object = ProjectIndexFixtures.snapshotWithAllMetaKinds()
                .files().getFirst().definitions().getFirst().object();

        for (MetaKind kind : MetaKind.values()) {
            assertThrows(IllegalArgumentException.class,
                    () -> invalidDefinition(kind, object), kind::name);
        }
    }

    @Test
    void decoderRejectsDefinitionWhoseKindDoesNotMatchItsFields() throws Exception {
        Fixture fixture = fixture();
        byte[] definitions = payload(fixture.bytes(), SectionType.DEFINITIONS);
        definitions[firstBlockRecordBody(definitions)] = (byte) MetaKind.FUNCTION.ordinal();
        byte[] corrupted = replaceSection(fixture.bytes(), SectionType.DEFINITIONS, definitions);

        ProjectIndexFormatException error = assertThrows(ProjectIndexFormatException.class,
                () -> ProjectIndexCodec.decodeBase(corrupted, 32L << 20));

        assertTrue(error.getMessage().contains("DEFINITIONS"), error::getMessage);
    }

    @Test
    void channelPageIsRejectedBeforeAllocatorIsInvoked() throws Exception {
        byte[] bytes = ProjectIndexCodec.encodeBase(ProjectIndexFixtures.minimalSnapshot());
        AtomicInteger allocations = new AtomicInteger();
        try (var channel = memoryChannel(bytes)) {
            ProjectIndexFormatException error = assertThrows(ProjectIndexFormatException.class,
                    () -> ProjectIndexBaseReader.read(channel,
                            ProjectIndexSource.CHANNEL_SOURCE_RESIDENT_BYTES - 1,
                            size -> {
                                allocations.incrementAndGet();
                                return new byte[size];
                            }));

            assertTrue(error.getMessage().contains("allocation limit"));
            assertEquals(0, allocations.get());
        }
    }

    @Test
    void channelResidentBudgetHasAnExactAcceptanceBoundary() throws Exception {
        byte[] bytes = ProjectIndexCodec.encodeBase(ProjectIndexFixtures.minimalSnapshot());
        long rejected = 0;
        long accepted = 32L << 20;
        while (rejected + 1 < accepted) {
            long candidate = rejected + (accepted - rejected) / 2;
            if (canDecodeChannel(bytes, candidate)) {
                accepted = candidate;
            } else {
                rejected = candidate;
            }
        }

        assertTrue(canDecodeChannel(bytes, accepted));
        assertTrue(!canDecodeChannel(bytes, accepted - 1));
    }

    @Test
    void rejectsMissingByMatchKeyMembershipWithValidCrc() throws Exception {
        Fixture fixture = fixture();
        Map<ReferenceMatchKey, MatchIds> changed = copyMatch(fixture.indexes().byMatchKey());
        var target = changed.entrySet().stream()
                .filter(entry -> !entry.getValue().definitionIds().isEmpty()
                        && !entry.getValue().locationIds().isEmpty())
                .findFirst().orElseThrow();
        List<Integer> definitions = new ArrayList<>(target.getValue().definitionIds());
        definitions.removeLast();
        changed.put(target.getKey(), new MatchIds(definitions, target.getValue().locationIds()));

        byte[] corrupted = replaceSection(fixture.bytes(), SectionType.BY_MATCH_KEY,
                encode(output -> writeByMatchKey(output, changed, fixture.strings())));

        assertSemanticFailure(corrupted, "BY_MATCH_KEY");
    }

    @Test
    void rejectsWrongByMatchKeyMembershipWithValidCrc() throws Exception {
        Fixture fixture = fixture();
        Map<ReferenceMatchKey, MatchIds> changed = copyMatch(fixture.indexes().byMatchKey());
        var target = changed.entrySet().stream()
                .filter(entry -> entry.getValue().definitionIds().size() == 1)
                .findFirst().orElseThrow();
        int replacement = mismatchingDefinition(target.getKey(), fixture.data().definitions());
        changed.put(target.getKey(), new MatchIds(List.of(replacement),
                target.getValue().locationIds()));

        byte[] corrupted = replaceSection(fixture.bytes(), SectionType.BY_MATCH_KEY,
                encode(output -> writeByMatchKey(output, changed, fixture.strings())));

        assertSemanticFailure(corrupted, "BY_MATCH_KEY");
    }

    @Test
    void rejectsExtraByMatchKeyMembershipWithValidCrc() throws Exception {
        Fixture fixture = fixture();
        Map<ReferenceMatchKey, MatchIds> changed = copyMatch(fixture.indexes().byMatchKey());
        var target = changed.entrySet().stream()
                .filter(entry -> !entry.getValue().definitionIds().isEmpty())
                .findFirst().orElseThrow();
        int extra = mismatchingDefinition(target.getKey(), fixture.data().definitions());
        List<Integer> definitions = new ArrayList<>(target.getValue().definitionIds());
        definitions.add(extra);
        definitions.sort(Comparator.naturalOrder());
        changed.put(target.getKey(), new MatchIds(definitions, target.getValue().locationIds()));

        byte[] corrupted = replaceSection(fixture.bytes(), SectionType.BY_MATCH_KEY,
                encode(output -> writeByMatchKey(output, changed, fixture.strings())));

        assertSemanticFailure(corrupted, "BY_MATCH_KEY");
    }

    @Test
    void rejectsMissingCompletionMembershipWithValidCrc() throws Exception {
        Fixture fixture = fixture();
        TreeMap<String, List<Integer>> changed = new TreeMap<>(fixture.indexes().completion());
        changed.pollFirstEntry();

        byte[] corrupted = replaceSection(fixture.bytes(), SectionType.COMPLETION_TRIGRAMS,
                encode(output -> writeCompletion(output, changed, fixture.strings())));

        assertSemanticFailure(corrupted, "COMPLETION_TRIGRAMS");
    }

    @Test
    void rejectsWrongCompletionMembershipWithValidCrc() throws Exception {
        Fixture fixture = fixture();
        Map<String, List<Integer>> changed = new TreeMap<>(fixture.indexes().completion());
        var target = changed.entrySet().stream()
                .filter(entry -> entry.getValue().size() == 1)
                .findFirst().orElseThrow();
        int replacement = 0;
        while (target.getValue().contains(replacement)
                || fixture.indexes().completion().get(target.getKey()).contains(replacement)) {
            replacement++;
        }
        changed.put(target.getKey(), List.of(replacement));

        byte[] corrupted = replaceSection(fixture.bytes(), SectionType.COMPLETION_TRIGRAMS,
                encode(output -> writeCompletion(output, changed, fixture.strings())));

        assertSemanticFailure(corrupted, "COMPLETION_TRIGRAMS");
    }

    @Test
    void rejectsMissingReverseDependencyWithValidCrc() throws Exception {
        Fixture fixture = fixture();
        TreeMap<ReferenceMatchKey, List<IndexPathRef>> changed = new TreeMap<>(
                ReferenceMatchKey.CANONICAL_ORDER);
        changed.putAll(fixture.indexes().reverseDependencies());
        changed.pollFirstEntry();

        byte[] corrupted = replaceSection(fixture.bytes(), SectionType.REVERSE_DEPENDENCIES,
                encode(output -> writeReverseDependencies(output, changed, fixture.strings())));

        assertSemanticFailure(corrupted, "REVERSE_DEPENDENCIES");
    }

    @Test
    void rejectsWrongReverseDependencyPathWithValidCrc() throws Exception {
        Fixture fixture = fixture();
        TreeMap<ReferenceMatchKey, List<IndexPathRef>> changed = new TreeMap<>(
                ReferenceMatchKey.CANONICAL_ORDER);
        changed.putAll(fixture.indexes().reverseDependencies());
        var target = changed.firstEntry();
        IndexPathRef replacement = fixture.data().ranges().stream().map(PathRange::path)
                .filter(path -> !target.getValue().contains(path)).findFirst().orElseThrow();
        changed.put(target.getKey(), List.of(replacement));

        byte[] corrupted = replaceSection(fixture.bytes(), SectionType.REVERSE_DEPENDENCIES,
                encode(output -> writeReverseDependencies(output, changed, fixture.strings())));

        assertSemanticFailure(corrupted, "REVERSE_DEPENDENCIES");
    }

    @Test
    void rejectsNullRequiredManifestStringAsFormatError() throws Exception {
        Fixture fixture = fixture();
        byte[] payload = payload(fixture.bytes(), SectionType.MANIFEST);
        int coreVersionOffset = skipVarInts(payload, 0, 3);
        payload = replaceVarInt(payload, coreVersionOffset, 0);
        byte[] corrupted = replaceSection(fixture.bytes(), SectionType.MANIFEST, payload);

        ProjectIndexFormatException error = assertThrows(ProjectIndexFormatException.class,
                () -> ProjectIndexCodec.decodeBase(corrupted, 32L << 20));

        assertTrue(error.getMessage().contains("MANIFEST"));
    }

    @Test
    void rejectsInvalidManifestIdentityAsFormatError() throws Exception {
        ProjectIndexData original = ProjectIndexFixtures.snapshotWithAllMetaKinds();
        Fixture fixture = fixture(original);
        byte[] strings = payload(fixture.bytes(), SectionType.STRINGS);
        byte[] identity = original.manifest().projectIdentity()
                .getBytes(StandardCharsets.US_ASCII);
        int identityBytes = indexOf(strings, identity);
        strings[identityBytes + identity.length - 1] = 'g';
        byte[] corrupted = replaceSection(fixture.bytes(), SectionType.STRINGS, strings);

        ProjectIndexFormatException error = assertThrows(ProjectIndexFormatException.class,
                () -> ProjectIndexCodec.decodeBase(corrupted, 32L << 20));

        assertTrue(error.getMessage().contains("MANIFEST"));
    }

    @Test
    void manifestIdentityAcceptsOnlyLowercaseSha256Hex() throws Exception {
        ProjectIndexManifest valid = ProjectIndexFixtures.snapshotWithAllMetaKinds().manifest();
        for (String invalid : List.of("FILE:/tmp/project", "http://host/project", "/tmp/project",
                "A".repeat(64), "a".repeat(63), "a".repeat(63) + "g")) {
            assertThrows(IllegalArgumentException.class, () -> new ProjectIndexManifest(
                    valid.formatMajor(), valid.formatMinor(), valid.parserAbi(),
                    valid.coreVersion(), valid.uiVersion(), valid.databaseType(), invalid,
                    valid.configSha256(), valid.generation(), valid.files()));
        }
    }

    private static Fixture fixture() throws Exception {
        return fixture(ProjectIndexFixtures.snapshotWithAllMetaKinds());
    }

    private static Fixture fixture(ProjectIndexData source) throws Exception {
        CanonicalData data = canonicalize(source);
        AuxiliaryIndexes indexes = AuxiliaryIndexes.build(data);
        StringTable strings = StringTable.build(
                preflightProspective(data, MAX_SECTION_BYTES), indexes);
        return new Fixture(ProjectIndexCodec.encodeBase(source), data, indexes, strings);
    }

    private static int indexOf(byte[] bytes, byte[] needle) {
        for (int i = 0; i <= bytes.length - needle.length; i++) {
            boolean matches = true;
            for (int j = 0; j < needle.length; j++) {
                if (bytes[i + j] != needle[j]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return i;
            }
        }
        throw new AssertionError("Fixture bytes do not contain the requested value");
    }

    private static Map<ReferenceMatchKey, MatchIds> copyMatch(
            Map<ReferenceMatchKey, MatchIds> source) {
        Map<ReferenceMatchKey, MatchIds> result = new TreeMap<>(
                ReferenceMatchKey.CANONICAL_ORDER);
        result.putAll(source);
        return result;
    }

    private static int mismatchingDefinition(ReferenceMatchKey key,
            List<PackedDefinition> definitions) {
        for (int i = 0; i < definitions.size(); i++) {
            PackedLocation location = definitions.get(i).object();
            if (location.reference() != null && location.reference().type() != null
                    && !ReferenceMatchKey.from(location).equals(key)) {
                return i;
            }
        }
        throw new AssertionError("Fixture needs definitions with different match keys");
    }

    private static byte[] encode(ThrowingEncoder encoder) throws IOException {
        try (var channel = new MemoryChannel()) {
            var output = new ChannelWriter(channel, true, MAX_SECTION_BYTES);
            encoder.write(output);
            output.finish();
            return channel.toByteArray();
        }
    }

    private static boolean canDecodeChannel(byte[] bytes, long budget) throws Exception {
        try (var channel = memoryChannel(bytes)) {
            try {
                ProjectIndexBaseReader.read(channel, budget);
                return true;
            } catch (ProjectIndexFormatException ex) {
                assertTrue(ex.getMessage().contains("allocation limit"), ex::getMessage);
                return false;
            }
        }
    }

    private static MemoryChannel memoryChannel(byte[] bytes) throws IOException {
        var channel = new MemoryChannel();
        channel.write(ByteBuffer.wrap(bytes));
        channel.position(0);
        return channel;
    }

    private static void assertSemanticFailure(byte[] bytes, String section) {
        ProjectIndexFormatException error = assertThrows(ProjectIndexFormatException.class,
                () -> ProjectIndexCodec.decodeBase(bytes, 32L << 20));
        assertTrue(error.getMessage().contains(section), error::getMessage);
    }

    private static void assertOrderFailure(byte[] bytes, String section) {
        ProjectIndexFormatException error = assertThrows(ProjectIndexFormatException.class,
                () -> ProjectIndexCodec.decodeBase(bytes, 32L << 20));
        assertTrue(error.getMessage().contains(section), error::getMessage);
        assertTrue(error.getMessage().contains("canonical order"), error::getMessage);
    }

    private static byte[] swapFirstTwoBlockRecords(byte[] payload) {
        int blockLengthOffset = skipVarInt(payload, 0);
        int blockStart = skipVarInt(payload, blockLengthOffset);
        int firstBody = skipVarInt(payload, blockStart);
        int firstEnd = firstBody + decodeVarInt(payload, blockStart);
        int secondBody = skipVarInt(payload, firstEnd);
        int secondEnd = secondBody + decodeVarInt(payload, firstEnd);
        byte[] result = payload.clone();
        int firstLength = firstEnd - blockStart;
        int secondLength = secondEnd - firstEnd;
        System.arraycopy(payload, firstEnd, result, blockStart, secondLength);
        System.arraycopy(payload, blockStart, result, blockStart + secondLength, firstLength);
        return result;
    }

    private static PackedDefinition invalidDefinition(MetaKind kind, PackedLocation object) {
        return switch (kind) {
            case STATEMENT -> new PackedDefinition(kind, object, "unexpected", "",
                    List.of(), List.of(), List.of(), null, false, List.of(), false, List.of(),
                    false, List.of(), null, null, null, null, null, null);
            case FUNCTION -> new PackedDefinition(kind, object, null, "",
                    List.of(), List.of(), List.of(), null, false, List.of(), false, List.of(),
                    false, List.of(), null, null, null, null, null, null);
            case RELATION -> new PackedDefinition(kind, object, null, "",
                    List.of(), List.of(), List.of(), null, false,
                    List.of(new NameType("id", "bigint")), false, List.of(),
                    false, List.of(), null, null, null, null, null, null);
            case COMPOSITE_TYPE -> new PackedDefinition(kind, object, "unexpected", "",
                    List.of(), List.of(), List.of(), null, false, List.of(), false, List.of(),
                    false, List.of(), null, null, null, null, null, null);
            case CONSTRAINT -> new PackedDefinition(kind, object, null, "",
                    List.of(), List.of(), List.of(), "unexpected", false, List.of(), false,
                    List.of(), false, List.of(), null, null, null, null, null, null);
            case OPERATOR -> new PackedDefinition(kind, object, null, "",
                    List.of(), List.of(), List.of(), null, false, List.of(), false, List.of(),
                    false, List.of(), null, null, null, null, null, null);
            case CAST -> new PackedDefinition(kind, object, null, "",
                    List.of(), List.of(), List.of(), null, false, List.of(), false, List.of(),
                    false, List.of(), null, null, null, null, "text", CastContext.ASSIGNMENT);
        };
    }

    private static byte[] encodeStrings(List<String> values) {
        var payload = new BinaryWriter();
        payload.writeVarInt(values.size());
        if (!values.isEmpty()) {
            var block = new BinaryWriter();
            for (String value : values) {
                byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
                block.writeVarInt(utf8.length);
                block.writeBytes(utf8);
            }
            payload.writeVarInt(block.size());
            payload.writeBytes(block.bytes());
            payload.writeVarInt(1);
        } else {
            payload.writeVarInt(0);
        }
        return payload.bytes();
    }

    private static int firstBlockRecordBody(byte[] payload) {
        int blockLength = skipVarInt(payload, 0);
        int firstRecord = skipVarInt(payload, blockLength);
        return skipVarInt(payload, firstRecord);
    }

    private static int skipLocation(byte[] bytes, int offset) {
        int cursor = skipVarInts(bytes, offset, 6);
        boolean reference = bytes[cursor++] != 0;
        if (reference) {
            cursor = skipVarInts(bytes, cursor, 4);
        }
        return skipVarInts(bytes, cursor, 4);
    }

    private static int anyOnlyUnresolvedBoolean(byte[] payload) {
        int count = decodeVarInt(payload, 0);
        int cursor = skipVarInt(payload, 0);
        for (int i = 0; i < count; i++) {
            cursor = skipVarInts(payload, cursor, 2);
            int anyOffset = cursor++;
            boolean any = payload[anyOffset] != 0;
            int keyCount = decodeVarInt(payload, cursor);
            cursor = skipVarInt(payload, cursor);
            if (any && keyCount == 0) {
                return anyOffset;
            }
            for (int j = 0; j < keyCount; j++) {
                cursor = skipVarInts(payload, cursor, 6);
                cursor++;
            }
        }
        throw new AssertionError("Fixture needs an any-only unresolved record");
    }

    private static byte[] payload(byte[] bytes, SectionType type) {
        Entry entry = entries(bytes).get(type);
        int start = Math.toIntExact(entry.offset()) + SECTION_FRAME_SIZE;
        return Arrays.copyOfRange(bytes, start, start + entry.length());
    }

    private static byte[] replaceSection(byte[] original, SectionType type, byte[] payload) {
        Entry oldEntry = entries(original).get(type);
        int oldPayloadStart = Math.toIntExact(oldEntry.offset()) + SECTION_FRAME_SIZE;
        int oldPayloadEnd = oldPayloadStart + oldEntry.length();
        int delta = payload.length - oldEntry.length();
        byte[] result = new byte[original.length + delta];
        System.arraycopy(original, 0, result, 0, oldPayloadStart);
        System.arraycopy(payload, 0, result, oldPayloadStart, payload.length);
        System.arraycopy(original, oldPayloadEnd, result, oldPayloadStart + payload.length,
                original.length - oldPayloadEnd);

        int payloadCrc = crc(payload, 0, payload.length);
        putInt(result, Math.toIntExact(oldEntry.offset()) + 4, payload.length);
        putInt(result, Math.toIntExact(oldEntry.offset()) + 8, payloadCrc);

        long oldDirectoryOffset = getLong(original, 20);
        long newDirectoryOffset = oldDirectoryOffset + delta;
        putLong(result, 20, newDirectoryOffset);
        int count = getInt(result, 32);
        int cursor = Math.toIntExact(newDirectoryOffset) + DIRECTORY_MAGIC.length;
        for (int i = 0; i < count; i++) {
            SectionType current = SectionType.fromId(Byte.toUnsignedInt(result[cursor]));
            long offset = getLong(result, cursor + 4);
            if (offset > oldEntry.offset()) {
                putLong(result, cursor + 4, offset + delta);
            }
            if (current == type) {
                putInt(result, cursor + 12, payload.length);
                putInt(result, cursor + 16, payloadCrc);
            }
            cursor += DIRECTORY_ENTRY_SIZE;
        }

        int footer = result.length - FOOTER_SIZE;
        putLong(result, footer + 8, newDirectoryOffset);
        putInt(result, footer + 16, crc(result, footer, FOOTER_SIZE - Integer.BYTES));
        updateDirectoryAndHeaderCrc(result);
        return result;
    }

    private static Map<SectionType, Entry> entries(byte[] bytes) {
        int directory = Math.toIntExact(getLong(bytes, 20));
        int count = getInt(bytes, 32);
        Map<SectionType, Entry> result = new TreeMap<>();
        int cursor = directory + DIRECTORY_MAGIC.length;
        for (int i = 0; i < count; i++) {
            SectionType type = SectionType.fromId(Byte.toUnsignedInt(bytes[cursor]));
            result.put(type, new Entry(getLong(bytes, cursor + 4), getInt(bytes, cursor + 12)));
            cursor += DIRECTORY_ENTRY_SIZE;
        }
        return result;
    }

    private static byte[] replaceVarInt(byte[] bytes, int offset, int value) {
        int end = skipVarInt(bytes, offset);
        byte[] encoded = encodeVarInt(value);
        byte[] result = new byte[bytes.length - (end - offset) + encoded.length];
        System.arraycopy(bytes, 0, result, 0, offset);
        System.arraycopy(encoded, 0, result, offset, encoded.length);
        System.arraycopy(bytes, end, result, offset + encoded.length, bytes.length - end);
        return result;
    }

    private static byte[] encodeVarInt(int value) {
        byte[] result = new byte[5];
        int cursor = 0;
        while ((value & ~0x7F) != 0) {
            result[cursor++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        result[cursor++] = (byte) value;
        return Arrays.copyOf(result, cursor);
    }

    private static int decodeVarInt(byte[] bytes, int offset) {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int current = Byte.toUnsignedInt(bytes[offset++]);
            value |= (current & 0x7F) << shift;
            if ((current & 0x80) == 0) {
                return value;
            }
        }
        throw new IllegalArgumentException("Invalid fixture varint");
    }

    private static int skipVarInts(byte[] bytes, int offset, int count) {
        int cursor = offset;
        for (int i = 0; i < count; i++) {
            cursor = skipVarInt(bytes, cursor);
        }
        return cursor;
    }

    private static int skipVarInt(byte[] bytes, int offset) {
        for (int i = 0; i < 5; i++) {
            if ((bytes[offset + i] & 0x80) == 0) {
                return offset + i + 1;
            }
        }
        throw new IllegalArgumentException("Invalid fixture varint");
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

    private static void putInt(byte[] bytes, int offset, int value) {
        ByteBuffer.wrap(bytes, offset, Integer.BYTES).order(ByteOrder.BIG_ENDIAN).putInt(value);
    }

    private static void putLong(byte[] bytes, int offset, long value) {
        ByteBuffer.wrap(bytes, offset, Long.BYTES).order(ByteOrder.BIG_ENDIAN).putLong(value);
    }

    private static void updateDirectoryAndHeaderCrc(byte[] bytes) {
        int directory = Math.toIntExact(getLong(bytes, 20));
        int directoryLength = getInt(bytes, 28);
        int directoryCrc = crc(bytes, directory, directoryLength - Integer.BYTES);
        putInt(bytes, directory + directoryLength - Integer.BYTES, directoryCrc);
        putInt(bytes, 36, directoryCrc);
        putInt(bytes, 40, crc(bytes, 0, 40));
    }

    /**
     * A source that records how it was asked, and hands its bytes out in runs
     * of a fixed width - or refuses to, when that width is zero.
     *
     * <p>Each run is handed out as its own array, indexed from the run's
     * start, which is how the cached source hands out a block.
     */
    private static final class CountingSource extends ProjectIndexSource {

        private final byte[] bytes;
        private final int runLength;
        private int locateAttempts;
        private int locates;
        private int byteReads;
        private int bulkReads;

        private CountingSource(byte[] bytes, int runLength) {
            this.bytes = bytes;
            this.runLength = runLength;
        }

        int locateAttempts() {
            return locateAttempts;
        }

        int locates() {
            return locates;
        }

        int byteReads() {
            return byteReads;
        }

        int bulkReads() {
            return bulkReads;
        }

        @Override
        long size() {
            return bytes.length;
        }

        @Override
        int readUnsignedByte(long position) throws ProjectIndexFormatException {
            requireRange(position, 1);
            byteReads++;
            return Byte.toUnsignedInt(bytes[(int) position]);
        }

        @Override
        void readFully(long position, byte[] target, int offset, int length)
                throws ProjectIndexFormatException {
            requireRange(position, length);
            bulkReads++;
            System.arraycopy(bytes, (int) position, target, offset, length);
        }

        @Override
        int crc32c(long position, long length) throws ProjectIndexFormatException {
            requireRange(position, length);
            var crc = new CRC32C();
            crc.update(bytes, (int) position, (int) length);
            return (int) crc.getValue();
        }

        @Override
        boolean locate(ByteRun run, long position) throws ProjectIndexFormatException {
            requireRange(position, 1);
            locateAttempts++;
            if (runLength <= 0) {
                return false;
            }
            locates++;
            int start = (int) (position / runLength * runLength);
            int length = Math.min(runLength, bytes.length - start);
            run.hold(Arrays.copyOfRange(bytes, start, start + length), start, length);
            return true;
        }
    }

    private record Fixture(byte[] bytes, CanonicalData data, AuxiliaryIndexes indexes,
            StringTable strings) {
    }

    private record Entry(long offset, int length) {
    }

    @FunctionalInterface
    private interface ThrowingEncoder {
        void write(ChannelWriter output) throws IOException;
    }
}
