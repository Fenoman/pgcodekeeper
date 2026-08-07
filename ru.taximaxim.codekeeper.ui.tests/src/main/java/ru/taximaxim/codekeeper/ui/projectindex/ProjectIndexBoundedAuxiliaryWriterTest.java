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

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;

import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexFormat.ChannelWriter;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexSpillStore.Metrics;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetry.Mode;
import ru.taximaxim.codekeeper.ui.projectindex.ReferenceMatchKey.MatchFamily;

class ProjectIndexBoundedAuxiliaryWriterTest {

    private static final int LEGACY_COMPLETION_MEMBERSHIP_BYTES = 72;
    private static final int HIGH_FANOUT_NAME_CHARS = 3_728_273;
    private static final String SINGLE_RUN_PUBLICATION_ID =
            "11111111111111111111111111111111";
    private static final String MULTI_RUN_PUBLICATION_ID =
            "22222222222222222222222222222222";
    private static final long BASELINE_BUDGET_BYTES = 256;

    @Test
    void boundedDataWriterMatchesLegacyBytesAcrossRunShapes(
            @TempDir Path temporary) throws Exception {
        ProjectIndexData source =
                ProjectIndexFixtures.snapshotWithAllMetaKinds();
        byte[] expected = legacyBytes(source);

        ProjectIndexSpillStore.Metrics baseline = assertBoundedParity(
                temporary, source, expected, "single-run",
                SINGLE_RUN_PUBLICATION_ID, BASELINE_BUDGET_BYTES);
        assertEquals(2, baseline.materializedRuns(),
                "The fixture must spill BY_MATCH_KEY and COMPLETION_TRIGRAMS once each");

        ProjectIndexSpillStore.Metrics multiRun = assertBoundedParity(
                temporary, source, expected, "multi-run",
                MULTI_RUN_PUBLICATION_ID, 64);
        assertTrue(multiRun.materializedRuns()
                        > baseline.materializedRuns(),
                "The fixture must exercise a multi-run merge");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("legacyParityFixtures")
    void boundedDataWriterMatchesLegacyAcrossCanonicalizationCases(
            String name, ProjectIndexData source,
            @TempDir Path temporary) throws Exception {
        assertBoundedParity(temporary, source, legacyBytes(source),
                name, publicationId(name), 256);
    }

    @Test
    void boundedReplayWriterMatchesDataAndLegacyBytes(
            @TempDir Path temporary) throws Exception {
        ProjectIndexData source = randomized(
                ProjectIndexFixtures.completionHeavy(96),
                0x50_47_43_4bL);
        byte[] expected = legacyBytes(source);
        Path storeDirectory = temporary.resolve("replay-store");

        try (var store = new ProjectIndexStore(storeDirectory)) {
            assertEquals(ProjectIndexStore.PublishResult.PUBLISHED,
                    store.publish(source, () -> false));
            try (var opened = store.open(
                    ProjectIndexIdentity.from(source.manifest()))) {
                ProjectIndexView view = opened.view().orElseThrow();
                ProjectIndexReplaySource replay =
                        ProjectIndexReplaySource.merged(
                                view, null, () -> false);
                byte[] replayBytes = boundedReplayBytes(
                        temporary, replay, "replay");
                assertArrayEquals(expected, replayBytes);
            }
        }
    }

    @Test
    void auxiliaryRunMetricsArePerSectionAndAggregateSequentially(
            @TempDir Path temporary) throws Exception {
        ProjectIndexData data =
                ProjectIndexFixtures.snapshotWithAllMetaKinds();
        ProjectIndexContributionSource source =
                ProjectIndexContributionSource.from(data);
        var context = new ProjectIndexWriteContext(
                temporary.resolve("state-sections"),
                "44444444444444444444444444444444",
                () -> false, BASELINE_BUDGET_BYTES, null);
        ProjectIndexBoundedAuxiliaryWriter.Prepared prepared =
                ProjectIndexBoundedAuxiliaryWriter.prepare(
                        source, context);

        assertEquals(1, writeAuxiliarySection(
                SectionType.BY_MATCH_KEY, source, prepared, context)
                        .materializedRuns());
        assertEquals(1, writeAuxiliarySection(
                SectionType.COMPLETION_TRIGRAMS,
                source, prepared, context).materializedRuns());
        assertEquals(0, writeAuxiliarySection(
                SectionType.REVERSE_DEPENDENCIES,
                source, prepared, context).materializedRuns());
    }

    @Test
    void cancellationInterruptsOneLargeFileBeforeLaterMemberships(
            @TempDir Path temporary) throws Exception {
        ProjectIndexData data =
                ProjectIndexFixtures.withDefinitionBareName(
                        "A".repeat(3_000));
        ProjectIndexContributionSource preparedSource =
                ProjectIndexContributionSource.from(data);
        var prepareContext = new ProjectIndexWriteContext(
                temporary.resolve("state-prepare"),
                "55555555555555555555555555555555",
                () -> false, 64 << 10, null);
        ProjectIndexBoundedAuxiliaryWriter.Prepared prepared =
                ProjectIndexBoundedAuxiliaryWriter.prepare(
                        preparedSource, prepareContext);

        AtomicBoolean armed = new AtomicBoolean();
        ProjectIndexContributionSource sentinelSource =
                sentinelAfterLongDefinition(data, armed);
        var cancelledContext = new ProjectIndexWriteContext(
                temporary.resolve("state-cancel"),
                "66666666666666666666666666666666",
                cancelAfterArmedPolls(armed, 2),
                64 << 10, null);

        try (var channel = new ProjectIndexFormat.MemoryChannel()) {
            var output = new ChannelWriter(channel, true,
                    ProjectIndexFormat.MAX_SECTION_BYTES);
            assertThrows(ProjectIndexStore.WriteCancelledException.class,
                    () -> ProjectIndexBoundedAuxiliaryWriter.writeSection(
                            SectionType.COMPLETION_TRIGRAMS,
                            output, sentinelSource, prepared,
                            cancelledContext));
        }
        assertFalse(Files.exists(
                cancelledContext.stateDirectory().resolve(
                        ".writer-"
                                + cancelledContext.publicationId()
                                + ".tmp"),
                LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void cancellationInterruptsBoundedPreparationInsideOneLargeFile(
            @TempDir Path temporary) throws Exception {
        ProjectIndexData data =
                ProjectIndexFixtures.withDefinitionBareName(
                        "A".repeat(3_000));
        AtomicBoolean armed = new AtomicBoolean();
        ProjectIndexContributionSource sentinelSource =
                sentinelAfterLongDefinition(data, armed);
        var context = new ProjectIndexWriteContext(
                temporary.resolve("state-prepare-cancel"),
                "77777777777777777777777777777777",
                cancelAfterArmedPolls(armed, 3),
                64 << 10, null);

        assertThrows(ProjectIndexStore.WriteCancelledException.class,
                () -> ProjectIndexBoundedAuxiliaryWriter.prepare(
                        sentinelSource, context));
    }

    @Test
    void cancellationInterruptsNonEmittingRecordsInsideOneLargeFile(
            @TempDir Path temporary) throws Exception {
        ProjectIndexData typed =
                ProjectIndexFixtures.repeatedLocations(3_000);
        FileContribution original = typed.files().getFirst();
        List<PackedLocation> untypedLocations =
                original.locations().stream()
                        .map(location -> new PackedLocation(
                                location.origin(),
                                location.relativePath(),
                                location.offset(),
                                location.lineNumber(),
                                location.charPositionInLine(),
                                location.length(), null,
                                location.action(), location.alias(),
                                ObjectLocation.LocationType.REFERENCE,
                                location.danger()))
                        .toList();
        FileContribution untyped = new FileContribution(
                original.path(), original.definitions(),
                untypedLocations,
                original.unresolvedCandidates(),
                original.unresolvedAny());
        ProjectIndexData data = new ProjectIndexData(
                typed.manifest(), List.of(untyped));
        ProjectIndexContributionSource stable =
                ProjectIndexContributionSource.from(data);
        var prepareContext = new ProjectIndexWriteContext(
                temporary.resolve("state-untyped-prepare"),
                "88888888888888888888888888888888",
                () -> false, 64 << 10, null);
        ProjectIndexBoundedAuxiliaryWriter.Prepared prepared =
                ProjectIndexBoundedAuxiliaryWriter.prepare(
                        stable, prepareContext);

        AtomicBoolean armed = new AtomicBoolean();
        ProjectIndexContributionSource sentinel =
                sentinelAfterPath(data, untyped.path(), armed);
        var cancelledContext = new ProjectIndexWriteContext(
                temporary.resolve("state-untyped-cancel"),
                "99999999999999999999999999999999",
                cancelAfterArmedPolls(armed, 2),
                64 << 10, null);
        try (var channel = new ProjectIndexFormat.MemoryChannel()) {
            var output = new ChannelWriter(channel, true,
                    ProjectIndexFormat.MAX_SECTION_BYTES);
            assertThrows(ProjectIndexStore.WriteCancelledException.class,
                    () -> ProjectIndexBoundedAuxiliaryWriter.writeSection(
                            SectionType.BY_MATCH_KEY, output,
                            sentinel, prepared, cancelledContext));
        }
    }

    @Test
    void cancellationInterruptsStreamingDefinitionsAndRetrySucceeds(
            @TempDir Path temporary) throws Exception {
        ProjectIndexData data =
                ProjectIndexFixtures.completionHeavy(3_000);
        ProjectIndexContributionSource stable =
                ProjectIndexContributionSource.from(data);
        var prepareContext = new ProjectIndexWriteContext(
                temporary.resolve("state-definitions-prepare"),
                "10101010101010101010101010101010",
                () -> false, 64 << 10, null);
        ProjectIndexBoundedAuxiliaryWriter.Prepared prepared =
                ProjectIndexBoundedAuxiliaryWriter.prepare(
                        stable, prepareContext);
        IndexPathRef target = data.files().stream()
                .filter(file -> file.definitions().size() == 3_000)
                .map(FileContribution::path)
                .findFirst().orElseThrow();
        AtomicBoolean armed = new AtomicBoolean();
        ProjectIndexContributionSource sentinel =
                sentinelAfterPath(data, target, armed);
        var cancelledContext = new ProjectIndexWriteContext(
                temporary.resolve("state-definitions-cancel"),
                "11101010101010101010101010101010",
                cancelAfterArmedPolls(armed, 2),
                64 << 10, null);

        try (var channel = new ProjectIndexFormat.MemoryChannel()) {
            var output = new ChannelWriter(channel, true,
                    ProjectIndexFormat.MAX_SECTION_BYTES);
            assertThrows(ProjectIndexStore.WriteCancelledException.class,
                    () -> ProjectIndexRecords
                            .writeStreamingDefinitions(
                                    output, sentinel, prepared,
                                    cancelledContext));
        }

        try (var channel = new ProjectIndexFormat.MemoryChannel()) {
            var output = new ChannelWriter(channel, true,
                    ProjectIndexFormat.MAX_SECTION_BYTES);
            ProjectIndexRecords.writeStreamingDefinitions(
                    output, stable, prepared, prepareContext);
            output.finish();
            assertTrue(channel.size() > 0);
        }
    }

    @Test
    void cancellationInterruptsStreamingLocationsAndRetrySucceeds(
            @TempDir Path temporary) throws Exception {
        ProjectIndexData data =
                ProjectIndexFixtures.repeatedLocations(3_000);
        ProjectIndexContributionSource stable =
                ProjectIndexContributionSource.from(data);
        var prepareContext = new ProjectIndexWriteContext(
                temporary.resolve("state-locations-prepare"),
                "12121212121212121212121212121212",
                () -> false, 64 << 10, null);
        ProjectIndexBoundedAuxiliaryWriter.Prepared prepared =
                ProjectIndexBoundedAuxiliaryWriter.prepare(
                        stable, prepareContext);
        IndexPathRef target = data.files().getFirst().path();
        AtomicBoolean armed = new AtomicBoolean();
        ProjectIndexContributionSource sentinel =
                sentinelAfterPath(data, target, armed);
        var cancelledContext = new ProjectIndexWriteContext(
                temporary.resolve("state-locations-cancel"),
                "13131313131313131313131313131313",
                cancelAfterArmedPolls(armed, 2),
                64 << 10, null);

        try (var channel = new ProjectIndexFormat.MemoryChannel()) {
            var output = new ChannelWriter(channel, true,
                    ProjectIndexFormat.MAX_SECTION_BYTES);
            assertThrows(ProjectIndexStore.WriteCancelledException.class,
                    () -> ProjectIndexRecords
                            .writeStreamingLocations(
                                    output, sentinel, prepared,
                                    cancelledContext));
        }

        try (var channel = new ProjectIndexFormat.MemoryChannel()) {
            var output = new ChannelWriter(channel, true,
                    ProjectIndexFormat.MAX_SECTION_BYTES);
            ProjectIndexRecords.writeStreamingLocations(
                    output, stable, prepared, prepareContext);
            output.finish();
            assertTrue(channel.size() > 0);
        }
    }

    @Test
    void cancellationInterruptsUnresolvedCandidateOrdering(
            @TempDir Path temporary) throws Exception {
        assertUnresolvedCancellation(temporary,
                "14141414141414141414141414141414", 2);
    }

    @Test
    void cancellationInterruptsUnresolvedCandidateWriting(
            @TempDir Path temporary) throws Exception {
        assertUnresolvedCancellation(temporary,
                "15151515151515151515151515151515", 4);
    }

    @Test
    void cancellationInterruptsCompletionStringMerge(
            @TempDir Path temporary) throws Exception {
        ProjectIndexData data =
                singleDefinitionWithBareName(
                        uniqueTrigramName(2_050));
        AtomicBoolean armed = new AtomicBoolean();
        ProjectIndexContributionSource sentinel =
                armAfterReplay(data, armed);
        var context = new ProjectIndexWriteContext(
                temporary.resolve(
                        "state-completion-merge-cancel"),
                "16161616161616161616161616161616",
                cancelAfterArmedPolls(armed, 2),
                64 << 10, null);

        assertThrows(ProjectIndexStore.WriteCancelledException.class,
                () -> ProjectIndexBoundedAuxiliaryWriter.prepare(
                        sentinel, context));
    }

    @Test
    void cancellationInterruptsStringTableCanonicalCopyAndRetrySucceeds(
            @TempDir Path temporary) throws Exception {
        ProjectIndexData data = unresolvedHeavy(3_000);
        AtomicBoolean armed = new AtomicBoolean();
        ProjectIndexContributionSource sentinel =
                armAfterReplay(data, armed);
        var cancelledContext = new ProjectIndexWriteContext(
                temporary.resolve("state-string-finish-cancel"),
                "17171717171717171717171717171717",
                cancelAfterArmedPolls(armed, 2),
                64 << 10, null);

        assertThrows(ProjectIndexStore.WriteCancelledException.class,
                () -> ProjectIndexBoundedAuxiliaryWriter.prepare(
                        sentinel, cancelledContext));

        var retryContext = new ProjectIndexWriteContext(
                temporary.resolve("state-string-finish-retry"),
                "18181818181818181818181818181818",
                () -> false, 64 << 10, null);
        ProjectIndexBoundedAuxiliaryWriter.Prepared retry =
                ProjectIndexBoundedAuxiliaryWriter.prepare(
                        ProjectIndexContributionSource.from(data),
                        retryContext);
        assertTrue(retry.strings().values().size() > 3_000);
    }

    @Test
    void cancellationInterruptsStringTableLookupMap(
            @TempDir Path temporary) throws Exception {
        ProjectIndexData data = unresolvedHeavy(3_000);
        int stringPolls = finishStringPolls(
                preparedStrings(temporary, data,
                        "lookup-map-baseline"));
        assertFinishCancellation(temporary, data,
                "lookup-map", 1 + stringPolls + 1);
    }

    @Test
    void cancellationInterruptsStringBlocksSizing(
            @TempDir Path temporary) throws Exception {
        ProjectIndexData data = unresolvedHeavy(3_000);
        int stringPolls = finishStringPolls(
                preparedStrings(temporary, data,
                        "blocks-size-baseline"));
        assertFinishCancellation(temporary, data,
                "blocks-size", 1 + 3 * stringPolls + 1);
    }

    @Test
    void cancellationInterruptsPackedTrigramTableMaintenance(
            @TempDir Path temporary) throws Exception {
        ProjectIndexData data =
                singleDefinitionWithBareName("ABCDEFGHIJKLM");
        AtomicBoolean armed = new AtomicBoolean();
        ProjectIndexContributionSource sentinel =
                sentinelAfterPath(data,
                        data.files().getFirst().path(), armed);
        var context = new ProjectIndexWriteContext(
                temporary.resolve("state-trigram-table-cancel"),
                "19191919191919191919191919191919",
                cancelAfterArmedPolls(armed, 3),
                64 << 10, null);

        assertThrows(ProjectIndexStore.WriteCancelledException.class,
                () -> ProjectIndexBoundedAuxiliaryWriter.prepare(
                        sentinel, context));
    }

    @Test
    void highFanoutBeyondLegacyMemoryEstimatePublishesReopensAndQueries(
            @TempDir Path temporary) throws Exception {
        String name = "A".repeat(HIGH_FANOUT_NAME_CHARS);
        long candidates = name.length() - 2L;
        long legacyEstimate = Math.multiplyExact(candidates,
                LEGACY_COMPLETION_MEMBERSHIP_BYTES);
        assertTrue(legacyEstimate
                        > ProjectIndexFormat
                                .MAX_PROSPECTIVE_BUILD_BYTES,
                "The fixture must exceed the removed boxed-graph guard");

        ProjectIndexData source =
                ProjectIndexFixtures.withDefinitionBareName(name);
        FileContribution targetFile = source.files().stream()
                .filter(file -> file.definitions().stream()
                        .anyMatch(definition ->
                            name.equals(definition.bareName())))
                .findFirst().orElseThrow();
        PackedDefinition targetDefinition =
                targetFile.definitions().stream()
                        .filter(definition ->
                            name.equals(definition.bareName()))
                        .findFirst().orElseThrow();
        PackedLocation targetReference =
                targetFile.locations().stream()
                        .filter(location ->
                            location.locationType()
                                    == ObjectLocation.LocationType
                                            .REFERENCE)
                        .findFirst().orElseThrow();
        ReferenceMatchKey key =
                ReferenceMatchKey.from(targetReference);
        ProjectIndexMatches expectedMatches =
                expectedMatches(source, key);
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(source.manifest());
        Path storeDirectory = temporary.resolve("high-fanout-store");

        try (var store = new ProjectIndexStore(storeDirectory)) {
            assertEquals(ProjectIndexStore.PublishResult.PUBLISHED,
                    store.publish(source, () -> false));
        }

        byte[] publishedCodec;
        try (var store = new ProjectIndexStore(storeDirectory);
                var opened = store.open(identity)) {
            ProjectIndexView view = opened.view().orElseThrow();
            assertEquals(expectedMatches, view.matches(key));
            assertEquals(List.of(targetDefinition),
                    view.completion("AAA"));
            assertEquals(Set.of(targetFile.path()),
                    view.reverseDependencies(key));
            assertEquals(targetFile,
                    view.contribution(targetFile.path())
                            .orElseThrow());
            publishedCodec =
                    readContainerCodec(view.basePathForTests());
        }

        Path forced = temporary.resolve("high-fanout-forced.idx");
        ProjectIndexSpillStore.Metrics metrics;
        try (FileChannel channel = FileChannel.open(forced,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE)) {
            metrics = ProjectIndexBaseWriter.writeBoundedForTests(
                    source, channel,
                    temporary.resolve("state-high-fanout"),
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    1L << 20);
        }
        assertTrue(metrics.materializedRuns() > 1,
                "The high-fanout fixture must force a multi-run merge");
        assertTrue(metrics.peakTrackedBufferBytes() <= 1L << 20);
        assertArrayEquals(publishedCodec,
                Files.readAllBytes(forced));
    }

    @Test
    void boundedWriterReportsOneCompleteCodecPayload(
            @TempDir Path temporary) throws Exception {
        ProjectIndexData source =
                ProjectIndexFixtures.snapshotWithAllMetaKinds();
        Path output = temporary.resolve("telemetry.idx");
        List<String> lines = new ArrayList<>();
        var telemetry = new ProjectIndexTelemetry(
                lines::add, () -> 0);
        ProjectIndexSpillStore.Metrics metrics;
        try (var run = telemetry.start(Mode.COLD);
                FileChannel channel = FileChannel.open(output,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.READ,
                        StandardOpenOption.WRITE)) {
            metrics = ProjectIndexBaseWriter.write(
                    source, channel, new ProjectIndexWriteContext(
                            temporary.resolve("state-telemetry"),
                            "33333333333333333333333333333333",
                            () -> false, 256, run)).metrics();
        }

        assertEquals(1, lines.size());
        String line = lines.getFirst();
        assertTrue(line.contains(
                "writer_buffer_budget_bytes=256 writer_runs="
                        + metrics.materializedRuns()
                        + " writer_spill_bytes="
                        + metrics.physicalSpillBytes()
                        + " writer_packed_bytes="
                        + Files.size(output)));
    }

    private static ProjectIndexSpillStore.Metrics assertBoundedParity(
            Path temporary, ProjectIndexData source, byte[] expected,
            String name, String publicationId, long tupleBudgetBytes)
            throws Exception {
        Path stateDirectory = temporary.resolve("state-" + name);
        Path output = temporary.resolve(name + ".idx");
        ProjectIndexSpillStore.Metrics metrics;
        try (FileChannel channel = FileChannel.open(output,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE)) {
            metrics = ProjectIndexBaseWriter.writeBoundedForTests(
                    source, channel, stateDirectory, publicationId,
                    tupleBudgetBytes);
        }

        assertArrayEquals(expected, Files.readAllBytes(output),
                "Bounded writing changed format 2.0 bytes for " + name);
        assertFalse(Files.exists(stateDirectory.resolve(
                ".writer-" + publicationId + ".tmp"),
                LinkOption.NOFOLLOW_LINKS),
                "Bounded writing leaked its spill workspace");
        assertTrue(metrics.peakTrackedBufferBytes()
                        <= tupleBudgetBytes,
                () -> "Bounded writing used "
                        + metrics.peakTrackedBufferBytes()
                        + " primitive buffer bytes with budget "
                        + tupleBudgetBytes);
        return metrics;
    }

    private static Metrics writeAuxiliarySection(SectionType type,
            ProjectIndexContributionSource source,
            ProjectIndexBoundedAuxiliaryWriter.Prepared prepared,
            ProjectIndexWriteContext context) throws Exception {
        try (var channel = new ProjectIndexFormat.MemoryChannel()) {
            var output = new ChannelWriter(channel, true,
                    ProjectIndexFormat.MAX_SECTION_BYTES);
            Metrics metrics =
                    ProjectIndexBoundedAuxiliaryWriter.writeSection(
                            type, output, source, prepared, context);
            output.finish();
            return metrics;
        }
    }

    private static byte[] boundedReplayBytes(Path temporary,
            ProjectIndexReplaySource source, String name)
            throws Exception {
        Path output = temporary.resolve(name + ".idx");
        try (FileChannel channel = FileChannel.open(output,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE)) {
            ProjectIndexBaseWriter.write(source, channel,
                    new ProjectIndexWriteContext(
                            temporary.resolve("state-" + name),
                            publicationId(name), () -> false,
                            256, null));
        }
        return Files.readAllBytes(output);
    }

    private static Stream<Arguments> legacyParityFixtures()
            throws Exception {
        ProjectIndexData allMeta =
                ProjectIndexFixtures.snapshotWithAllMetaKinds();
        return Stream.of(
                Arguments.of("reordered",
                        ProjectIndexFixtures.reordered(allMeta)),
                Arguments.of("unicode-completion",
                        ProjectIndexFixtures.withFunctionBareNames(
                                List.of("ABABA", "AB", "ABC",
                                        "a\u00DFa"))),
                Arguments.of("comparator-collisions-forward",
                        ProjectIndexFixtures.comparatorCollisions(false)),
                Arguments.of("comparator-collisions-reversed",
                        ProjectIndexFixtures.comparatorCollisions(true)),
                Arguments.of("repeated-locations",
                        ProjectIndexFixtures.repeatedLocations(257)),
                Arguments.of("completion-heavy",
                        ProjectIndexFixtures.completionHeavy(128)),
                Arguments.of("unresolved",
                        allMeta),
                Arguments.of("randomized-input",
                        randomized(
                                ProjectIndexFixtures.completionHeavy(64),
                                0x15_00_00L)));
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

    private static ProjectIndexMatches expectedMatches(
            ProjectIndexData source, ReferenceMatchKey key) {
        List<PackedDefinition> definitions = source.files().stream()
                .flatMap(file -> file.definitions().stream())
                .filter(definition ->
                    ProjectIndexRecords.hasTypedReference(
                            definition.object())
                            && ReferenceMatchKey.from(
                                    definition.object())
                                    .equals(key))
                .sorted(ProjectIndexFormat.DEFINITION_ORDER)
                .toList();
        List<PackedLocation> locations = source.files().stream()
                .flatMap(file -> file.locations().stream())
                .filter(location ->
                    ProjectIndexRecords.hasTypedReference(location)
                            && ReferenceMatchKey.from(location)
                                    .equals(key))
                .sorted(ProjectIndexFormat.LOCATION_ORDER)
                .toList();
        return new ProjectIndexMatches(definitions, locations);
    }

    private static byte[] readContainerCodec(Path path)
            throws Exception {
        try (FileChannel channel = FileChannel.open(
                path, StandardOpenOption.READ)) {
            ProjectIndexContainer.Opened opened =
                    ProjectIndexContainer.open(
                            channel, 0, channel.size());
            byte[] bytes = new byte[
                    Math.toIntExact(opened.codecLength())];
            ByteBuffer target = ByteBuffer.wrap(bytes);
            long position = opened.codecOffset();
            while (target.hasRemaining()) {
                int read = channel.read(target, position);
                if (read <= 0) {
                    throw new AssertionError(
                            "Unable to read published codec");
                }
                position += read;
            }
            return bytes;
        }
    }

    private static ProjectIndexContributionSource
            sentinelAfterLongDefinition(ProjectIndexData data,
                    AtomicBoolean armed) throws Exception {
        ProjectIndexContributionSource delegate =
                ProjectIndexContributionSource.from(data);
        List<ContributionCall> calls = new ArrayList<>();
        ProjectIndexContributionSource.PassCounts counts =
                delegate.replay(() -> false,
                        (fileId, definitionStart, locationStart, file) ->
                            calls.add(new ContributionCall(fileId,
                                    definitionStart, locationStart, file)));
        IndexPathRef target = calls.stream()
                .map(ContributionCall::file)
                .filter(file -> file.definitions().stream()
                        .anyMatch(definition ->
                            definition.bareName() != null
                                    && definition.bareName()
                                            .length() == 3_000))
                .map(FileContribution::path)
                .findFirst().orElseThrow();
        return sentinelAfterPath(
                delegate, calls, counts, target, armed);
    }

    private static void assertUnresolvedCancellation(
            Path temporary, String publicationId, int cancelAtPoll)
            throws Exception {
        ProjectIndexData data = unresolvedHeavy(3_000);
        ProjectIndexContributionSource stable =
                ProjectIndexContributionSource.from(data);
        var prepareContext = new ProjectIndexWriteContext(
                temporary.resolve(
                        "state-unresolved-prepare-" + cancelAtPoll),
                publicationId("unresolved-prepare-" + cancelAtPoll),
                () -> false, 64 << 10, null);
        ProjectIndexBoundedAuxiliaryWriter.Prepared prepared =
                ProjectIndexBoundedAuxiliaryWriter.prepare(
                        stable, prepareContext);
        AtomicBoolean armed = new AtomicBoolean();
        ProjectIndexContributionSource sentinel =
                sentinelAfterPath(data,
                        data.files().getFirst().path(), armed);
        var cancelledContext = new ProjectIndexWriteContext(
                temporary.resolve(
                        "state-unresolved-cancel-" + cancelAtPoll),
                publicationId, cancelAfterArmedPolls(
                        armed, cancelAtPoll),
                64 << 10, null);

        try (var channel = new ProjectIndexFormat.MemoryChannel()) {
            var output = new ChannelWriter(channel, true,
                    ProjectIndexFormat.MAX_SECTION_BYTES);
            assertThrows(ProjectIndexStore.WriteCancelledException.class,
                    () -> ProjectIndexRecords
                            .writeStreamingUnresolved(
                                    output, sentinel, prepared,
                                    cancelledContext));
        }
    }

    private static ProjectIndexData unresolvedHeavy(int count)
            throws Exception {
        ProjectIndexData source =
                ProjectIndexFixtures.minimalSnapshot();
        FileContribution original = source.files().getFirst();
        Set<ReferenceMatchKey> candidates =
                new LinkedHashSet<>(count);
        for (int index = count - 1; index >= 0; index--) {
            candidates.add(new ReferenceMatchKey(
                    MatchFamily.RELATION, null,
                    "schema_" + String.format("%05d", index),
                    "table", null, null, false));
        }
        return new ProjectIndexData(source.manifest(), List.of(
                new FileContribution(original.path(),
                        original.definitions(), original.locations(),
                        candidates, false)));
    }

    private static ProjectIndexData singleDefinitionWithBareName(
            String bareName) throws Exception {
        ProjectIndexData source =
                ProjectIndexFixtures.withDefinitionBareName(bareName);
        FileContribution target = source.files().stream()
                .filter(file -> file.definitions().stream()
                        .anyMatch(definition ->
                            bareName.equals(definition.bareName())))
                .findFirst().orElseThrow();
        ProjectFileStamp stamp = source.manifest().files().stream()
                .filter(candidate ->
                    candidate.path().equals(target.path()))
                .findFirst().orElseThrow();
        ProjectIndexManifest old = source.manifest();
        ProjectIndexManifest manifest = new ProjectIndexManifest(
                old.formatMajor(), old.formatMinor(), old.parserAbi(),
                old.coreVersion(), old.uiVersion(), old.databaseType(),
                old.projectIdentity(), old.configSha256(),
                old.generation(), List.of(stamp));
        return new ProjectIndexData(manifest, List.of(target));
    }

    private static String uniqueTrigramName(int length) {
        char[] characters = new char[length];
        for (int index = 0; index < length; index++) {
            characters[index] = (char) (0x1000 + index);
        }
        return new String(characters);
    }

    private static int preparedStrings(Path temporary,
            ProjectIndexData data, String name) throws Exception {
        var context = new ProjectIndexWriteContext(
                temporary.resolve("state-" + name),
                publicationId(name), () -> false,
                64 << 10, null);
        return ProjectIndexBoundedAuxiliaryWriter.prepare(
                ProjectIndexContributionSource.from(data),
                context).strings().values().size();
    }

    private static int finishStringPolls(int strings) {
        return (strings + 1_023) / 1_024;
    }

    private static void assertFinishCancellation(
            Path temporary, ProjectIndexData data, String name,
            int cancelAtPoll) throws Exception {
        AtomicBoolean armed = new AtomicBoolean();
        ProjectIndexContributionSource sentinel =
                armAfterReplay(data, armed);
        var context = new ProjectIndexWriteContext(
                temporary.resolve("state-" + name + "-cancel"),
                publicationId(name + "-cancel"),
                cancelAfterArmedPolls(armed, cancelAtPoll),
                64 << 10, null);
        assertThrows(ProjectIndexStore.WriteCancelledException.class,
                () -> ProjectIndexBoundedAuxiliaryWriter.prepare(
                        sentinel, context));
    }

    private static ProjectIndexContributionSource armAfterReplay(
            ProjectIndexData data, AtomicBoolean armed)
            throws Exception {
        ProjectIndexContributionSource delegate =
                ProjectIndexContributionSource.from(data);
        List<ContributionCall> calls = new ArrayList<>();
        ProjectIndexContributionSource.PassCounts counts =
                delegate.replay(() -> false,
                        (fileId, definitionStart, locationStart, file) ->
                            calls.add(new ContributionCall(fileId,
                                    definitionStart, locationStart,
                                    file)));
        return new ProjectIndexContributionSource() {
            @Override
            public ProjectIndexManifest manifest() {
                return delegate.manifest();
            }

            @Override
            public PassCounts replay(BooleanSupplier cancelled,
                    ContributionConsumer consumer)
                    throws java.io.IOException {
                for (ContributionCall call : calls) {
                    consumer.accept(call.fileId(),
                            call.definitionStart(),
                            call.locationStart(), call.file());
                }
                armed.set(true);
                return counts;
            }
        };
    }

    private static ProjectIndexContributionSource sentinelAfterPath(
            ProjectIndexData data, IndexPathRef target,
            AtomicBoolean armed) throws Exception {
        ProjectIndexContributionSource delegate =
                ProjectIndexContributionSource.from(data);
        List<ContributionCall> calls = new ArrayList<>();
        ProjectIndexContributionSource.PassCounts counts =
                delegate.replay(() -> false,
                        (fileId, definitionStart, locationStart, file) ->
                            calls.add(new ContributionCall(fileId,
                                    definitionStart, locationStart, file)));
        return sentinelAfterPath(
                delegate, calls, counts, target, armed);
    }

    private static ProjectIndexContributionSource sentinelAfterPath(
            ProjectIndexContributionSource delegate,
            List<ContributionCall> calls,
            ProjectIndexContributionSource.PassCounts counts,
            IndexPathRef target, AtomicBoolean armed) {
        return new ProjectIndexContributionSource() {
            @Override
            public ProjectIndexManifest manifest() {
                return delegate.manifest();
            }

            @Override
            public PassCounts replay(
                    java.util.function.BooleanSupplier cancelled,
                    ContributionConsumer consumer) throws java.io.IOException {
                for (ContributionCall call : calls) {
                    if (call.file().path().equals(target)) {
                        armed.set(true);
                    }
                    consumer.accept(call.fileId(),
                            call.definitionStart(),
                            call.locationStart(), call.file());
                    if (armed.get()) {
                        throw new ReplaySentinelException();
                    }
                }
                return counts;
            }
        };
    }

    private static String publicationId(String name) {
        return String.format("%032x",
                Integer.toUnsignedLong(name.hashCode()));
    }

    private static BooleanSupplier cancelAfterArmedPolls(
            AtomicBoolean armed, int limit) {
        AtomicInteger polls = new AtomicInteger();
        return () -> armed.get()
                && polls.incrementAndGet() >= limit;
    }

    private record ContributionCall(int fileId, int definitionStart,
            int locationStart, FileContribution file) {
    }

    private static final class ReplaySentinelException
            extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static byte[] legacyBytes(ProjectIndexData source)
            throws Exception {
        try (var channel = new ProjectIndexFormat.MemoryChannel()) {
            ProjectIndexBaseWriter.write(source, channel,
                    ProjectIndexFormat.MAX_SECTION_BYTES);
            return channel.toByteArray();
        }
    }
}
