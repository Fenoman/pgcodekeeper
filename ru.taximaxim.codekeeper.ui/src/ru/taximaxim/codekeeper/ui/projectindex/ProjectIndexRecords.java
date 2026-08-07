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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.ToIntFunction;

import org.pgcodekeeper.core.DangerStatement;
import org.pgcodekeeper.core.database.api.schema.ArgMode;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.ICast.CastContext;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;

import ru.taximaxim.codekeeper.ui.DatabaseType;
import ru.taximaxim.codekeeper.ui.projectindex.PackedDefinition.MetaKind;
import ru.taximaxim.codekeeper.ui.projectindex.PackedDefinition.NameType;
import ru.taximaxim.codekeeper.ui.projectindex.PackedDefinition.PackedArgument;

final class ProjectIndexRecords {

    private static final int CANCELLATION_POLL_MASK = 1_023;
    private static final int MAX_VARINT_BYTES = 5;
    private static final int MAX_MATCH_KEY_BYTES = 6 * MAX_VARINT_BYTES + 1;
    private static final int MAX_PATH_BYTES = 2 * MAX_VARINT_BYTES;
    private static final int PROSPECTIVE_MATCH_MEMBERSHIP_BYTES = 176;
    private static final int PROSPECTIVE_COMPLETION_MEMBERSHIP_BYTES = 72;
    private static final int PROSPECTIVE_REVERSE_MEMBERSHIP_BYTES = 208;
    private static final int PROSPECTIVE_STRING_ENTRY_BYTES = 112;
    private static final int MAX_TRIGRAM_UTF8_BYTES = 12;
    private static final int MAX_UPPERCASE_CHARS_PER_SOURCE_CHAR = 4;

    private ProjectIndexRecords() {
    }

    static CanonicalData canonicalize(ProjectIndexData source) {
        Map<IndexPathRef, ProjectFileStamp> stampsByPath = new HashMap<>();
        source.manifest().files().forEach(stamp -> stampsByPath.put(stamp.path(), stamp));
        List<FileContribution> sortedFiles = source.files().stream()
                .sorted(Comparator.comparing(FileContribution::path, PATH_ORDER))
                .map(file -> new FileContribution(file.path(),
                        file.definitions().stream().sorted(DEFINITION_ORDER).toList(),
                        file.locations().stream().sorted(LOCATION_ORDER).toList(),
                        new LinkedHashSet<>(file.unresolvedCandidates().stream()
                                .sorted(ReferenceMatchKey.CANONICAL_ORDER).toList()),
                        file.unresolvedAny()))
                .toList();
        List<ProjectFileStamp> stamps = sortedFiles.stream()
                .map(file -> stampsByPath.get(file.path())).toList();
        List<PackedDefinition> definitions = new ArrayList<>();
        List<PackedLocation> locations = new ArrayList<>();
        List<PathRange> ranges = new ArrayList<>();
        for (FileContribution file : sortedFiles) {
            int definitionStart = definitions.size();
            int locationStart = locations.size();
            definitions.addAll(file.definitions());
            locations.addAll(file.locations());
            ranges.add(new PathRange(file.path(), definitionStart, file.definitions().size(),
                    locationStart, file.locations().size()));
        }
        var manifest = new ProjectIndexManifest(source.manifest().formatMajor(),
                source.manifest().formatMinor(), source.manifest().parserAbi(),
                source.manifest().coreVersion(), source.manifest().uiVersion(),
                source.manifest().databaseType(), source.manifest().projectIdentity(),
                source.manifest().configSha256(), source.manifest().generation(), stamps);
        return new CanonicalData(manifest, stamps, sortedFiles,
                List.copyOf(definitions), List.copyOf(locations), List.copyOf(ranges));
    }

    static void validateSourceWriteLimits(ProjectIndexData source) {
        requireWriteCount("file", source.files().size(), MAX_RECORD_COUNT);
        requireWriteCount("manifest file", source.manifest().files().size(), MAX_RECORD_COUNT);
        long definitions = 0;
        long locations = 0;
        for (FileContribution file : source.files()) {
            definitions += file.definitions().size();
            locations += file.locations().size();
            requireWriteCount("unresolved candidate", file.unresolvedCandidates().size(),
                    MAX_RECORD_COUNT);
            file.definitions().forEach(ProjectIndexRecords::validateDefinitionWriteLimits);
        }
        requireWriteCount("definition", definitions, MAX_RECORD_COUNT);
        requireWriteCount("location", locations, MAX_RECORD_COUNT);
    }

    static void validateCanonicalWriteLimits(CanonicalData data) {
        requireWriteCount("file", data.stamps().size(), MAX_RECORD_COUNT);
        requireWriteCount("definition", data.definitions().size(), MAX_RECORD_COUNT);
        requireWriteCount("location", data.locations().size(), MAX_RECORD_COUNT);
        requireWriteCount("path range", data.ranges().size(), MAX_RECORD_COUNT);
        data.definitions().forEach(ProjectIndexRecords::validateDefinitionWriteLimits);
        for (FileContribution file : data.files()) {
            requireWriteCount("unresolved candidate", file.unresolvedCandidates().size(),
                    MAX_RECORD_COUNT);
        }
    }

    static ProspectiveWork preflightProspective(CanonicalData data, int maxSectionBytes) {
        long typedDefinitions = 0;
        long completionMemberships = 0;
        for (PackedDefinition definition : data.definitions()) {
            if (hasTypedReference(definition.object())) {
                typedDefinitions++;
            }
            String name = completionName(definition);
            if (name != null && !name.isEmpty()) {
                requireCompletionUppercaseBound(name, maxSectionBytes);
                String upper = name.toUpperCase(Locale.ROOT);
                completionMemberships = checkedProspectiveAdd(completionMemberships,
                        trigramCandidateCount(upper));
            }
        }

        long typedLocations = 0;
        long reverseMemberships = 0;
        for (PackedLocation location : data.locations()) {
            if (hasTypedReference(location)) {
                typedLocations++;
                if (location.locationType() != ObjectLocation.LocationType.DEFINITION) {
                    reverseMemberships++;
                }
            }
        }
        long matchMemberships = checkedProspectiveAdd(typedDefinitions, typedLocations);
        requireProspectiveCount("match membership", matchMemberships);
        requireProspectiveCount("completion membership", completionMemberships);
        requireProspectiveCount("reverse dependency membership", reverseMemberships);

        long completionBytes = checkedProspectiveAdd(5,
                checkedProspectiveMultiply(completionMemberships,
                        3L * MAX_VARINT_BYTES));
        requireProspectiveSection("COMPLETION_TRIGRAMS", completionBytes, maxSectionBytes);

        long matchBytes = checkedProspectiveAdd(5,
                checkedProspectiveMultiply(matchMemberships,
                        MAX_MATCH_KEY_BYTES + 3L * MAX_VARINT_BYTES));
        requireProspectiveSection("BY_MATCH_KEY", matchBytes, maxSectionBytes);

        long reverseBytes = checkedProspectiveAdd(5,
                checkedProspectiveMultiply(reverseMemberships,
                        MAX_MATCH_KEY_BYTES + MAX_VARINT_BYTES + MAX_PATH_BYTES));
        requireProspectiveSection("REVERSE_DEPENDENCIES", reverseBytes, maxSectionBytes);

        long auxiliaryBytes = 0;
        auxiliaryBytes = checkedProspectiveAdd(auxiliaryBytes,
                checkedProspectiveMultiply(matchMemberships,
                        PROSPECTIVE_MATCH_MEMBERSHIP_BYTES));
        auxiliaryBytes = checkedProspectiveAdd(auxiliaryBytes,
                checkedProspectiveMultiply(completionMemberships,
                        PROSPECTIVE_COMPLETION_MEMBERSHIP_BYTES));
        auxiliaryBytes = checkedProspectiveAdd(auxiliaryBytes,
                checkedProspectiveMultiply(reverseMemberships,
                        PROSPECTIVE_REVERSE_MEMBERSHIP_BYTES));
        requireProspectiveBuildBytes(auxiliaryBytes);

        ProspectiveStrings strings = new ProspectiveStrings(auxiliaryBytes);
        strings.add(data.manifest().coreVersion());
        strings.add(data.manifest().uiVersion());
        strings.add(data.manifest().projectIdentity());
        data.stamps().forEach(stamp -> strings.add(stamp.path().relativePath()));
        data.definitions().forEach(definition -> strings.addDefinition(definition));
        data.locations().forEach(strings::addLocation);
        data.files().forEach(file -> {
            strings.add(file.path().relativePath());
            file.unresolvedCandidates().forEach(strings::addMatchKey);
        });
        return strings.finish(completionMemberships, maxSectionBytes);
    }

    static StreamingPrepared prepareStreaming(ProjectIndexReplaySource source,
            int maxSectionBytes) throws IOException {
        Objects.requireNonNull(source, "source");
        if (maxSectionBytes <= 0 || maxSectionBytes > MAX_SECTION_BYTES) {
            throw new IllegalArgumentException("Invalid project index section size limit");
        }
        ProjectIndexManifest manifest = source.manifest();
        if (manifest.formatMajor() != FORMAT_MAJOR
                || manifest.formatMinor() != FORMAT_MINOR) {
            throw new IllegalArgumentException(
                    "Project index manifest must use format 2.0");
        }
        requireWriteCount("file", manifest.files().size(), MAX_RECORD_COUNT);
        StreamingAccumulator accumulator =
                new StreamingAccumulator(manifest, maxSectionBytes);
        source.forEach(accumulator::add);
        return accumulator.finish();
    }

    static BoundedPrepared prepareBounded(
            ProjectIndexContributionSource source,
            ProjectIndexWriteContext context, int maxSectionBytes)
            throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(context, "context");
        if (maxSectionBytes <= 0 || maxSectionBytes > MAX_SECTION_BYTES) {
            throw new IllegalArgumentException(
                    "Invalid project index section size limit");
        }
        ProjectIndexManifest manifest = source.manifest();
        if (manifest.formatMajor() != FORMAT_MAJOR
                || manifest.formatMinor() != FORMAT_MINOR) {
            throw new IllegalArgumentException(
                    "Project index manifest must use format 2.0");
        }
        requireWriteCount("file", manifest.files().size(),
                MAX_RECORD_COUNT);
        var accumulator = new BoundedAccumulator(
                manifest, maxSectionBytes, context);
        ProjectIndexContributionSource.PassCounts counts = source.replay(
                context.cancelled(), accumulator::add);
        return accumulator.finish(counts);
    }

    static void writeStreamingDefinitions(ChannelWriter output,
            ProjectIndexReplaySource source, StreamingPrepared prepared)
            throws IOException {
        var blocks = new StreamingBlocks<PackedDefinition>(
                output, prepared.definitionCount(),
                definition -> encodedDefinitionSize(definition, prepared.strings()),
                (record, definition) ->
                        writeDefinition(record, prepared.strings(), definition),
                null);
        source.forEach(file -> {
            for (PackedDefinition definition : file.definitions()) {
                blocks.add(definition);
            }
        });
        blocks.finish();
    }

    static void writeStreamingDefinitions(ChannelWriter output,
            ProjectIndexContributionSource source,
            ProjectIndexBoundedAuxiliaryWriter.Prepared prepared,
            ProjectIndexWriteContext context) throws IOException {
        writeStreamingDefinitions(output, source, prepared,
                context, null);
    }

    static void writeStreamingDefinitions(ChannelWriter output,
            ProjectIndexContributionSource source,
            ProjectIndexBoundedAuxiliaryWriter.Prepared prepared,
            ProjectIndexWriteContext context,
            ProjectIndexLocatorCapture.Blocks locator)
            throws IOException {
        var blocks = new StreamingBlocks<PackedDefinition>(
                output, prepared.counts().definitions(),
                definition -> encodedDefinitionSize(
                        definition, prepared.strings()),
                (record, definition) ->
                    writeDefinition(record, prepared.strings(),
                            definition),
                locator);
        long[] records = {0};
        ProjectIndexContributionSource.PassCounts actual = source.replay(
                context.cancelled(),
                (fileId, definitionStart, locationStart, file) -> {
                    for (PackedDefinition definition :
                            file.definitions()) {
                        pollCancellation(context, records[0]++);
                        blocks.add(definition);
                    }
                });
        blocks.finish();
        requireStableCounts(prepared.counts(), actual,
                "DEFINITIONS");
    }

    static void writeStreamingLocations(ChannelWriter output,
            ProjectIndexReplaySource source, StreamingPrepared prepared)
            throws IOException {
        var blocks = new StreamingBlocks<PackedLocation>(
                output, prepared.locationCount(),
                location -> encodedLocationSize(location, prepared.strings()),
                (record, location) ->
                        writeLocation(record, prepared.strings(), location),
                null);
        source.forEach(file -> {
            for (PackedLocation location : file.locations()) {
                blocks.add(location);
            }
        });
        blocks.finish();
    }

    static void writeStreamingLocations(ChannelWriter output,
            ProjectIndexContributionSource source,
            ProjectIndexBoundedAuxiliaryWriter.Prepared prepared,
            ProjectIndexWriteContext context) throws IOException {
        writeStreamingLocations(output, source, prepared,
                context, null);
    }

    static void writeStreamingLocations(ChannelWriter output,
            ProjectIndexContributionSource source,
            ProjectIndexBoundedAuxiliaryWriter.Prepared prepared,
            ProjectIndexWriteContext context,
            ProjectIndexLocatorCapture.Blocks locator)
            throws IOException {
        var blocks = new StreamingBlocks<PackedLocation>(
                output, prepared.counts().locations(),
                location -> encodedLocationSize(
                        location, prepared.strings()),
                (record, location) ->
                    writeLocation(record, prepared.strings(), location),
                locator);
        long[] records = {0};
        ProjectIndexContributionSource.PassCounts actual = source.replay(
                context.cancelled(),
                (fileId, definitionStart, locationStart, file) -> {
                    for (PackedLocation location : file.locations()) {
                        pollCancellation(context, records[0]++);
                        blocks.add(location);
                    }
                });
        blocks.finish();
        requireStableCounts(prepared.counts(), actual,
                "LOCATIONS");
    }

    static void writeStreamingByPath(ChannelWriter output,
            ProjectIndexReplaySource source, StreamingPrepared prepared)
            throws IOException {
        output.writeVarInt(prepared.fileCount());
        int[] definitionStart = {0};
        int[] locationStart = {0};
        source.forEach(file -> {
            writePath(output, prepared.strings(), file.path());
            output.writeVarInt(definitionStart[0]);
            output.writeVarInt(file.definitions().size());
            output.writeVarInt(locationStart[0]);
            output.writeVarInt(file.locations().size());
            definitionStart[0] = Math.addExact(
                    definitionStart[0], file.definitions().size());
            locationStart[0] = Math.addExact(
                    locationStart[0], file.locations().size());
        });
        if (definitionStart[0] != prepared.definitionCount()
                || locationStart[0] != prepared.locationCount()) {
            throw new IllegalStateException(
                    "Streaming path ranges do not cover all records");
        }
    }

    static void writeStreamingByPath(ChannelWriter output,
            ProjectIndexContributionSource source,
            ProjectIndexBoundedAuxiliaryWriter.Prepared prepared,
            ProjectIndexWriteContext context) throws IOException {
        writeStreamingByPath(output, source, prepared,
                context, null);
    }

    static void writeStreamingByPath(ChannelWriter output,
            ProjectIndexContributionSource source,
            ProjectIndexBoundedAuxiliaryWriter.Prepared prepared,
            ProjectIndexWriteContext context,
            ProjectIndexLocatorCapture capture) throws IOException {
        output.writeVarInt(prepared.counts().files());
        ProjectIndexContributionSource.PassCounts actual = source.replay(
                context.cancelled(),
                (fileId, definitionStart, locationStart, file) -> {
                    writePath(output, prepared.strings(), file.path());
                    output.writeVarInt(definitionStart);
                    output.writeVarInt(file.definitions().size());
                    output.writeVarInt(locationStart);
                    output.writeVarInt(file.locations().size());
                    if (capture != null) {
                        capture.range(fileId, file.path(),
                                prepared.strings(),
                                definitionStart,
                                file.definitions().size(),
                                locationStart,
                                file.locations().size());
                    }
                });
        requireStableCounts(prepared.counts(), actual, "BY_PATH");
    }

    static void writeStreamingUnresolved(ChannelWriter output,
            ProjectIndexReplaySource source, StreamingPrepared prepared)
            throws IOException {
        output.writeVarInt(prepared.unresolvedCount());
        int[] written = {0};
        source.forEach(file -> {
            if (!file.unresolvedAny() && file.unresolvedCandidates().isEmpty()) {
                return;
            }
            writePath(output, prepared.strings(), file.path());
            output.writeBoolean(file.unresolvedAny());
            List<ReferenceMatchKey> keys =
                    file.unresolvedCandidates().stream()
                            .sorted(ReferenceMatchKey.CANONICAL_ORDER)
                            .toList();
            output.writeVarInt(keys.size());
            for (ReferenceMatchKey key : keys) {
                writeMatchKey(output, prepared.strings(), key);
            }
            written[0]++;
        });
        if (written[0] != prepared.unresolvedCount()) {
            throw new IllegalStateException(
                    "Streaming unresolved file count changed between passes");
        }
    }

    static void writeStreamingUnresolved(ChannelWriter output,
            ProjectIndexContributionSource source,
            ProjectIndexBoundedAuxiliaryWriter.Prepared prepared,
            ProjectIndexWriteContext context) throws IOException {
        writeStreamingUnresolved(output, source, prepared,
                context, null);
    }

    static void writeStreamingUnresolved(ChannelWriter output,
            ProjectIndexContributionSource source,
            ProjectIndexBoundedAuxiliaryWriter.Prepared prepared,
            ProjectIndexWriteContext context,
            ProjectIndexLocatorCapture.Sparse locator)
            throws IOException {
        output.writeVarInt(prepared.counts().unresolvedFiles());
        int[] written = {0};
        ProjectIndexContributionSource.PassCounts actual = source.replay(
                context.cancelled(),
                (fileId, definitionStart, locationStart, file) -> {
                    if (!file.unresolvedAny()
                            && file.unresolvedCandidates().isEmpty()) {
                        return;
                    }
                    if (locator != null) {
                        locator.path(output.size(), file.path(),
                                prepared.strings());
                    }
                    writePath(output, prepared.strings(), file.path());
                    output.writeBoolean(file.unresolvedAny());
                    var keys = new TreeSet<>(
                            ReferenceMatchKey.CANONICAL_ORDER);
                    long operations = 0;
                    for (ReferenceMatchKey key :
                            file.unresolvedCandidates()) {
                        pollCancellation(context, operations++);
                        keys.add(key);
                    }
                    output.writeVarInt(keys.size());
                    for (ReferenceMatchKey key : keys) {
                        pollCancellation(context, operations++);
                        writeMatchKey(output, prepared.strings(), key);
                    }
                    written[0]++;
                });
        requireStableCounts(prepared.counts(), actual,
                "UNRESOLVED_FILES");
        if (written[0] != prepared.counts().unresolvedFiles()) {
            throw new IllegalStateException(
                    "Streaming unresolved file count changed between passes");
        }
    }

    private static void pollCancellation(
            ProjectIndexWriteContext context, long operations)
            throws ProjectIndexStore.WriteCancelledException {
        if ((operations & CANCELLATION_POLL_MASK) == 0) {
            context.requireNotCancelled();
        }
    }

    private static void requireStableCounts(
            ProjectIndexContributionSource.PassCounts expected,
            ProjectIndexContributionSource.PassCounts actual,
            String section) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException(
                    section
                            + " contribution counts changed between passes");
        }
    }

    private static void requireCompletionUppercaseBound(String name, int maxSectionBytes) {
        encodedStringSize(name);
        long maximumUppercaseLength = checkedProspectiveMultiply(name.length(),
                MAX_UPPERCASE_CHARS_PER_SOURCE_CHAR);
        long maximumCandidates = maximumUppercaseLength <= 3
                ? 1 : maximumUppercaseLength - 2;
        long maximumSectionBytes = checkedProspectiveAdd(5,
                checkedProspectiveMultiply(maximumCandidates,
                        3L * MAX_VARINT_BYTES));
        if (maximumCandidates > MAX_RECORD_COUNT
                || maximumSectionBytes > maxSectionBytes) {
            throw new IllegalArgumentException(
                    "Project index completion name uppercase bound exceeds the format limit");
        }
        requireProspectiveBuildBytes(checkedProspectiveAdd(64,
                checkedProspectiveMultiply(maximumUppercaseLength, Character.BYTES)));
    }

    private static void requireProspectiveCount(String label, long count) {
        if (count > MAX_RECORD_COUNT) {
            throw new IllegalArgumentException("Project index prospective " + label
                    + " count exceeds the format limit: " + count);
        }
    }

    private static void requireProspectiveSection(String section, long bytes,
            int maxSectionBytes) {
        if (bytes > maxSectionBytes) {
            throw new IllegalArgumentException("Project index prospective " + section
                    + " section exceeds the size limit");
        }
    }

    static void requireProspectiveBuildBytes(long bytes) {
        if (bytes > MAX_PROSPECTIVE_BUILD_BYTES) {
            throw new ProjectIndexPersistenceException(
                    ProjectIndexTelemetry.PersistenceReason
                            .AUXILIARY_MEMORY_LIMIT);
        }
    }

    private static long checkedProspectiveAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("Project index prospective size overflows", ex);
        }
    }

    private static long checkedProspectiveMultiply(long left, long right) {
        try {
            return Math.multiplyExact(left, right);
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("Project index prospective size overflows", ex);
        }
    }

    static void validateAuxiliaryWriteLimits(AuxiliaryIndexes indexes, StringTable strings) {
        requireWriteCount("string", strings.values().size(), MAX_STRING_COUNT);
        strings.values().forEach(ProjectIndexRecords::encodedStringSize);
        requireWriteCount("match key", indexes.byMatchKey().size(), MAX_RECORD_COUNT);
        requireWriteCount("completion trigram", indexes.completion().size(), MAX_RECORD_COUNT);
        requireWriteCount("reverse dependency key", indexes.reverseDependencies().size(),
                MAX_RECORD_COUNT);
        indexes.byMatchKey().values().forEach(ids -> {
            requireWriteCount("definition id", ids.definitionIds().size(), MAX_RECORD_COUNT);
            requireWriteCount("location id", ids.locationIds().size(), MAX_RECORD_COUNT);
        });
        indexes.completion().values().forEach(ids ->
                requireWriteCount("completion id", ids.size(), MAX_RECORD_COUNT));
        indexes.reverseDependencies().values().forEach(paths ->
                requireWriteCount("dependent path", paths.size(), MAX_RECORD_COUNT));
    }

    static void validateRecordWriteLimits(CanonicalData data, StringTable strings) {
        data.definitions().forEach(definition -> encodedDefinitionSize(definition, strings));
        data.locations().forEach(location -> encodedLocationSize(location, strings));
    }

    static long sectionPayloadSize(SectionType type, CanonicalData data,
            AuxiliaryIndexes indexes, StringTable strings) {
        return switch (type) {
        case MANIFEST -> manifestSize(data.manifest(), strings);
        case STRINGS -> blocksSize(strings.values(), ProjectIndexRecords::encodedStringSize);
        case FILES -> filesSize(data.stamps(), strings);
        case DEFINITIONS -> blocksSize(data.definitions(),
                definition -> encodedDefinitionSize(definition, strings));
        case LOCATIONS -> blocksSize(data.locations(),
                location -> encodedLocationSize(location, strings));
        case BY_PATH -> byPathSize(data.ranges(), strings);
        case BY_MATCH_KEY -> byMatchKeySize(indexes.byMatchKey(), strings);
        case COMPLETION_TRIGRAMS -> completionSize(indexes.completion(), strings);
        case REVERSE_DEPENDENCIES -> reverseDependenciesSize(
                indexes.reverseDependencies(), strings);
        case UNRESOLVED_FILES -> unresolvedSize(data.files(), strings);
        };
    }

    private static long manifestSize(ProjectIndexManifest manifest, StringTable strings) {
        return varIntSize(manifest.formatMajor()) + varIntSize(manifest.formatMinor())
                + varIntSize(manifest.parserAbi()) + stringIdSize(strings, manifest.coreVersion())
                + stringIdSize(strings, manifest.uiVersion())
                + varIntSize(manifest.databaseType().ordinal())
                + stringIdSize(strings, manifest.projectIdentity()) + 32L + Long.BYTES;
    }

    private static long filesSize(List<ProjectFileStamp> stamps, StringTable strings) {
        long size = varIntSize(stamps.size());
        for (ProjectFileStamp stamp : stamps) {
            size += encodedPathSize(stamp.path(), strings) + 3L * Long.BYTES + 32L;
        }
        return size;
    }

    private static long byPathSize(List<PathRange> ranges, StringTable strings) {
        long size = varIntSize(ranges.size());
        for (PathRange range : ranges) {
            size += encodedPathSize(range.path(), strings)
                    + varIntSize(range.definitionStart()) + varIntSize(range.definitionCount())
                    + varIntSize(range.locationStart()) + varIntSize(range.locationCount());
        }
        return size;
    }

    private static long byMatchKeySize(Map<ReferenceMatchKey, MatchIds> values,
            StringTable strings) {
        long size = varIntSize(values.size());
        for (var entry : values.entrySet()) {
            size += encodedMatchKeySize(entry.getKey(), strings)
                    + idsSize(entry.getValue().definitionIds())
                    + idsSize(entry.getValue().locationIds());
        }
        return size;
    }

    private static long completionSize(Map<String, List<Integer>> values,
            StringTable strings) {
        long size = varIntSize(values.size());
        for (var entry : values.entrySet()) {
            size += stringIdSize(strings, entry.getKey()) + idsSize(entry.getValue());
        }
        return size;
    }

    private static long reverseDependenciesSize(
            Map<ReferenceMatchKey, List<IndexPathRef>> values, StringTable strings) {
        long size = varIntSize(values.size());
        for (var entry : values.entrySet()) {
            size += encodedMatchKeySize(entry.getKey(), strings)
                    + varIntSize(entry.getValue().size());
            for (IndexPathRef path : entry.getValue()) {
                size += encodedPathSize(path, strings);
            }
        }
        return size;
    }

    private static long unresolvedSize(List<FileContribution> files, StringTable strings) {
        int count = 0;
        for (FileContribution file : files) {
            if (file.unresolvedAny() || !file.unresolvedCandidates().isEmpty()) {
                count++;
            }
        }
        long size = varIntSize(count);
        for (FileContribution file : files) {
            if (!file.unresolvedAny() && file.unresolvedCandidates().isEmpty()) {
                continue;
            }
            size += encodedPathSize(file.path(), strings) + 1L
                    + varIntSize(file.unresolvedCandidates().size());
            for (ReferenceMatchKey key : file.unresolvedCandidates()) {
                size += encodedMatchKeySize(key, strings);
            }
        }
        return size;
    }

    private static long encodedMatchKeySize(ReferenceMatchKey key, StringTable strings) {
        return varIntSize(key.family().ordinal())
                + varIntSize(key.exactType() == null ? 0 : key.exactType().ordinal() + 1)
                + stringIdSize(strings, key.schema()) + stringIdSize(strings, key.table())
                + stringIdSize(strings, key.column()) + stringIdSize(strings, key.alias()) + 1L;
    }

    private static long idsSize(List<Integer> ids) {
        long size = varIntSize(ids.size());
        int previous = -1;
        for (int id : ids) {
            size += varIntSize(id - previous - 1);
            previous = id;
        }
        return size;
    }

    private static <T> long blocksSize(List<T> records, ToIntFunction<T> sizeCalculator) {
        long size = varIntSize(records.size());
        int blockSize = 0;
        int blockCount = 0;
        for (T value : records) {
            int recordSize = sizeCalculator.applyAsInt(value);
            int framedSize = varIntSize(recordSize) + recordSize;
            if (blockSize > 0 && (long) blockSize + framedSize > MAX_RAW_BLOCK_BYTES) {
                size += varIntSize(blockSize) + (long) blockSize;
                blockSize = 0;
                blockCount++;
            }
            blockSize = Math.addExact(blockSize, framedSize);
            if (blockSize >= MAX_RAW_BLOCK_BYTES) {
                size += varIntSize(blockSize) + (long) blockSize;
                blockSize = 0;
                blockCount++;
            }
        }
        if (blockSize > 0) {
            size += varIntSize(blockSize) + (long) blockSize;
            blockCount++;
        }
        return size + varIntSize(blockCount);
    }

    private static <T> long blocksSizeBounded(List<T> records,
            ToIntFunction<T> sizeCalculator,
            ProjectIndexWriteContext context) throws IOException {
        long size = varIntSize(records.size());
        int blockSize = 0;
        int blockCount = 0;
        long operations = 0;
        for (T value : records) {
            pollCancellation(context, operations++);
            int recordSize = sizeCalculator.applyAsInt(value);
            int framedSize = varIntSize(recordSize) + recordSize;
            if (blockSize > 0
                    && (long) blockSize + framedSize
                            > MAX_RAW_BLOCK_BYTES) {
                size += varIntSize(blockSize) + (long) blockSize;
                blockSize = 0;
                blockCount++;
            }
            blockSize = Math.addExact(blockSize, framedSize);
            if (blockSize >= MAX_RAW_BLOCK_BYTES) {
                size += varIntSize(blockSize) + (long) blockSize;
                blockSize = 0;
                blockCount++;
            }
        }
        if (blockSize > 0) {
            size += varIntSize(blockSize) + (long) blockSize;
            blockCount++;
        }
        return size + varIntSize(blockCount);
    }

    private static void requireWriteCount(String label, int count, int maximum) {
        requireWriteCount(label, (long) count, maximum);
    }

    private static void requireWriteCount(String label, long count, int maximum) {
        if (count > maximum) {
            throw new IllegalArgumentException("Project index " + label
                    + " count exceeds the format limit: " + count);
        }
    }

    private static void validateDefinitionWriteLimits(PackedDefinition definition) {
        requireWriteCount("argument", definition.arguments().size(), MAX_NESTED_COUNT);
        requireWriteCount("order-by argument", definition.orderBy().size(), MAX_NESTED_COUNT);
        requireWriteCount("return column", definition.returnColumns().size(), MAX_NESTED_COUNT);
        requireWriteCount("relation column", definition.relationColumns().size(), MAX_NESTED_COUNT);
        requireWriteCount("composite attribute", definition.compositeAttributes().size(),
                MAX_NESTED_COUNT);
        requireWriteCount("constraint column", definition.constraintColumns().size(),
                MAX_NESTED_COUNT);
    }

    static void writeManifest(ChannelWriter output, ProjectIndexManifest manifest,
            StringTable strings) throws IOException {
        output.writeVarInt(manifest.formatMajor());
        output.writeVarInt(manifest.formatMinor());
        output.writeVarInt(manifest.parserAbi());
        output.writeStringId(strings, manifest.coreVersion());
        output.writeStringId(strings, manifest.uiVersion());
        output.writeVarInt(manifest.databaseType().ordinal());
        output.writeStringId(strings, manifest.projectIdentity());
        output.writeBytes(manifest.configSha256());
        output.writeLong(manifest.generation());
    }

    static ManifestCore readManifest(ProjectIndexSource source, DirectoryEntry entry,
            StringTable strings, AllocationBudget budget)
            throws ProjectIndexFormatException {
        BinaryReader input = payloadReader(source, entry, "MANIFEST", budget);
        int major = input.readVarInt();
        int minor = input.readVarInt();
        int parserAbi = input.readVarInt();
        String coreVersion = input.readRequiredString(strings, "core version");
        String uiVersion = input.readRequiredString(strings, "UI version");
        DatabaseType databaseType = input.readEnum(DatabaseType.values(), "database type");
        String projectIdentity = input.readRequiredString(strings, "project identity");
        byte[] configSha = input.readBytes(32);
        long generation = input.readLong();
        input.requireEnd();
        budget.claim(96, "MANIFEST record");
        try {
            return new ManifestCore(major, minor, parserAbi, coreVersion, uiVersion,
                    databaseType, projectIdentity, configSha, generation);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw format("Invalid MANIFEST values", ex);
        }
    }

    static void writeFiles(ChannelWriter output, List<ProjectFileStamp> stamps,
            StringTable strings) throws IOException {
        writeFiles(output, stamps, strings, null);
    }

    static void writeFiles(ChannelWriter output,
            List<ProjectFileStamp> stamps, StringTable strings,
            ProjectIndexLocatorCapture capture) throws IOException {
        output.writeVarInt(stamps.size());
        for (ProjectFileStamp stamp : stamps) {
            writePath(output, strings, stamp.path());
            output.writeLong(stamp.eclipseModificationStamp());
            output.writeLong(stamp.size());
            output.writeLong(stamp.lastModifiedMillis());
            output.writeBytes(stamp.contentSha256());
            if (capture != null) {
                capture.file(stamp, strings);
            }
        }
    }

    static List<ProjectFileStamp> readFiles(ProjectIndexSource source, DirectoryEntry entry,
            StringTable strings, AllocationBudget budget) throws ProjectIndexFormatException {
        BinaryReader input = payloadReader(source, entry, "FILES", budget);
        int count = input.readCount("file", MAX_RECORD_COUNT, 192);
        budget.claim(listBytes(count, 2), "FILES list and immutable copy");
        List<ProjectFileStamp> stamps = new ArrayList<>(count);
        IndexPathRef previous = null;
        for (int i = 0; i < count; i++) {
            IndexPathRef path = readPath(input, strings);
            if (previous != null && PATH_ORDER.compare(previous, path) >= 0) {
                throw format("FILES paths are not strictly sorted");
            }
            previous = path;
            long resourceStamp = input.readLong();
            long size = input.readLong();
            long modified = input.readLong();
            byte[] hash = input.readBytes(32);
            budget.claim(arrayBytes(32, Byte.BYTES), "FILES digest clone");
            try {
                stamps.add(new ProjectFileStamp(path, resourceStamp, size, modified, hash));
            } catch (IllegalArgumentException ex) {
                throw format("Invalid FILES record for " + path, ex);
            }
        }
        input.requireEnd();
        return List.copyOf(stamps);
    }

    static void writeStrings(ChannelWriter output, StringTable strings) throws IOException {
        writeStrings(output, strings, null);
    }

    static void writeStrings(ChannelWriter output, StringTable strings,
            ProjectIndexLocatorCapture.Blocks locator)
            throws IOException {
        writeBlocks(output, strings.values(), ProjectIndexRecords::encodedStringSize,
                ProjectIndexRecords::writeUtf8, locator);
    }

    static StringTable readStrings(ProjectIndexSource source, DirectoryEntry entry,
            AllocationBudget budget)
            throws ProjectIndexFormatException {
        List<String> values = readBlocks(source, entry, "STRINGS", MAX_STRING_COUNT, 96, budget,
                input -> input.readUtf8(input.remaining()));
        String previous = null;
        for (String value : values) {
            if (previous != null && previous.compareTo(value) >= 0) {
                throw format("STRINGS records are not strictly sorted");
            }
            previous = value;
        }
        return StringTable.decoded(values, budget);
    }

    static void writeDefinitions(ChannelWriter output, List<PackedDefinition> definitions,
            StringTable strings) throws IOException {
        writeBlocks(output, definitions, definition -> encodedDefinitionSize(definition, strings),
                (record, definition) -> writeDefinition(record, strings, definition));
    }

    static List<PackedDefinition> readDefinitions(ProjectIndexSource source, DirectoryEntry entry,
            StringTable strings, AllocationBudget budget) throws ProjectIndexFormatException {
        return readBlocks(source, entry, "DEFINITIONS", MAX_RECORD_COUNT, 384, budget,
                input -> readDefinition(input, strings));
    }

    static PackedDefinition readDefinition(BinaryReader input, StringTable strings)
            throws ProjectIndexFormatException {
        MetaKind kind = input.readEnum(MetaKind.values(), "metadata kind");
        PackedLocation object = readLocation(input, strings);
        String bareName = input.readString(strings);
        String comment = input.readString(strings);
        List<PackedArgument> arguments = readArguments(input, strings);
        List<PackedArgument> orderBy = readArguments(input, strings);
        List<NameType> returnsColumns = readNameTypes(input, strings);
        String returns = input.readString(strings);
        boolean setof = input.readBoolean();
        List<NameType> relationColumns = readNameTypes(input, strings);
        boolean relationColumnsKnown = input.readBoolean();
        List<NameType> compositeAttributes = readNameTypes(input, strings);
        boolean primaryKey = input.readBoolean();
        int constraintCount = input.readCount("constraint column", MAX_NESTED_COUNT, 32);
        input.claim(listBytes(constraintCount, 2),
                "constraint column list and immutable copy");
        List<String> constraintColumns = new ArrayList<>(constraintCount);
        for (int i = 0; i < constraintCount; i++) {
            constraintColumns.add(input.readRequiredString(strings, "constraint column"));
        }
        String operatorLeft = input.readString(strings);
        String operatorRight = input.readString(strings);
        String operatorReturns = input.readString(strings);
        String castSource = input.readString(strings);
        String castTarget = input.readString(strings);
        int castOrdinal = input.readVarInt();
        CastContext castContext = castOrdinal == 0 ? null
                : checkedEnum(CastContext.values(), castOrdinal - 1, "cast context");
        input.claim(192, "definition object");
        try {
            return new PackedDefinition(kind, object, bareName, comment, arguments, orderBy,
                    returnsColumns, returns, setof, relationColumns, relationColumnsKnown,
                    compositeAttributes, primaryKey, constraintColumns, operatorLeft,
                    operatorRight, operatorReturns, castSource, castTarget, castContext);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw format("Invalid DEFINITIONS record", ex);
        }
    }

    static void writeLocations(ChannelWriter output, List<PackedLocation> locations,
            StringTable strings) throws IOException {
        writeBlocks(output, locations, location -> encodedLocationSize(location, strings),
                (record, location) -> writeLocation(record, strings, location));
    }

    static List<PackedLocation> readLocations(ProjectIndexSource source, DirectoryEntry entry,
            StringTable strings, AllocationBudget budget) throws ProjectIndexFormatException {
        return readBlocks(source, entry, "LOCATIONS", MAX_RECORD_COUNT, 256, budget,
                input -> readLocation(input, strings));
    }

    static void writeByPath(ChannelWriter output, List<PathRange> ranges,
            StringTable strings) throws IOException {
        output.writeVarInt(ranges.size());
        for (PathRange range : ranges) {
            writePath(output, strings, range.path());
            output.writeVarInt(range.definitionStart());
            output.writeVarInt(range.definitionCount());
            output.writeVarInt(range.locationStart());
            output.writeVarInt(range.locationCount());
        }
    }

    static List<PathRange> readByPath(ProjectIndexSource source, DirectoryEntry entry,
            StringTable strings,
            int definitionCount, int locationCount, AllocationBudget budget)
            throws ProjectIndexFormatException {
        BinaryReader input = payloadReader(source, entry, "BY_PATH", budget);
        int count = input.readCount("path range", MAX_RECORD_COUNT, 96);
        budget.claim(listBytes(count, 1), "BY_PATH range list");
        List<PathRange> ranges = new ArrayList<>(count);
        IndexPathRef previous = null;
        int nextDefinition = 0;
        int nextLocation = 0;
        for (int i = 0; i < count; i++) {
            IndexPathRef path = readPath(input, strings);
            if (previous != null && PATH_ORDER.compare(previous, path) >= 0) {
                throw format("BY_PATH paths are not strictly sorted");
            }
            previous = path;
            int definitionStart = input.readVarInt();
            int definitions = input.readVarInt();
            int locationStart = input.readVarInt();
            int locations = input.readVarInt();
            if (definitionStart != nextDefinition || locationStart != nextLocation
                    || (long) definitionStart + definitions > definitionCount
                    || (long) locationStart + locations > locationCount) {
                throw format("BY_PATH ranges are incomplete or overlapping");
            }
            nextDefinition += definitions;
            nextLocation += locations;
            ranges.add(new PathRange(path, definitionStart, definitions, locationStart, locations));
        }
        input.requireEnd();
        if (nextDefinition != definitionCount || nextLocation != locationCount) {
            throw format("BY_PATH does not cover every definition and location");
        }
        return Collections.unmodifiableList(ranges);
    }

    static void validateCanonicalRecordOrder(List<PathRange> ranges,
            List<PackedDefinition> definitions, List<PackedLocation> locations)
            throws ProjectIndexFormatException {
        for (PathRange range : ranges) {
            requireNondecreasing("DEFINITIONS", definitions, range.definitionStart(),
                    range.definitionCount(), DEFINITION_ORDER);
            requireNondecreasing("LOCATIONS", locations, range.locationStart(),
                    range.locationCount(), LOCATION_ORDER);
        }
    }

    private static <T> void requireNondecreasing(String section, List<T> values,
            int start, int count, Comparator<T> order) throws ProjectIndexFormatException {
        int end = start + count;
        for (int i = start + 1; i < end; i++) {
            if (order.compare(values.get(i - 1), values.get(i)) > 0) {
                throw format(section + " records are not in canonical order within BY_PATH");
            }
        }
    }

    static void writeByMatchKey(ChannelWriter output, Map<ReferenceMatchKey, MatchIds> values,
            StringTable strings) throws IOException {
        output.writeVarInt(values.size());
        for (var entry : values.entrySet()) {
            writeMatchKey(output, strings, entry.getKey());
            writeIds(output, entry.getValue().definitionIds());
            writeIds(output, entry.getValue().locationIds());
        }
    }

    static void validateByMatchKey(ProjectIndexSource source, DirectoryEntry entry,
            StringTable strings, List<PackedDefinition> definitions,
            List<PackedLocation> locations, AllocationBudget budget)
            throws ProjectIndexFormatException {
        BinaryReader input = payloadReader(source, entry, "BY_MATCH_KEY", budget);
        int count = input.readCount("match key", MAX_RECORD_COUNT, 0);
        long coverageBytes = bitSetBytes(definitions.size()) + bitSetBytes(locations.size());
        try (var ignored = budget.reserve(coverageBytes, "BY_MATCH_KEY coverage bit sets")) {
            BitSet seenDefinitions = new BitSet(definitions.size());
            BitSet seenLocations = new BitSet(locations.size());
            MatchTuple previous = null;
            for (int i = 0; i < count; i++) {
                MatchTuple key = readMatchTuple(input, strings);
                if (previous != null && compareMatchTuples(previous, key) >= 0) {
                    throw format("BY_MATCH_KEY keys are not strictly sorted");
                }
                previous = key;
                int definitionIds = validateDefinitionMemberships(input, key, definitions,
                        seenDefinitions, strings);
                int locationIds = validateLocationMemberships(input, key, locations,
                        seenLocations, strings);
                if (definitionIds == 0 && locationIds == 0) {
                    throw format("BY_MATCH_KEY contains an empty key");
                }
            }
            input.requireEnd();
            requireExactCoverage("definition", definitions, seenDefinitions);
            requireExactCoverage("location", locations, seenLocations);
        }
    }

    static void writeCompletion(ChannelWriter output, Map<String, List<Integer>> values,
            StringTable strings) throws IOException {
        output.writeVarInt(values.size());
        for (var entry : values.entrySet()) {
            output.writeStringId(strings, entry.getKey());
            writeIds(output, entry.getValue());
        }
    }

    static void validateCompletion(ProjectIndexSource source, DirectoryEntry entry,
            StringTable strings, List<PackedDefinition> definitions, AllocationBudget budget)
            throws ProjectIndexFormatException {
        long pairCount = 0;
        for (PackedDefinition definition : definitions) {
            String name = completionName(definition);
            if (name != null && !name.isEmpty()) {
                try (var ignored = reserveUppercase(name, budget)) {
                    String upper = name.toUpperCase(Locale.ROOT);
                    int candidates = trigramCandidateCount(upper);
                    try (var trigrams = budget.reserve(arrayBytes(candidates, Long.BYTES),
                            "COMPLETION_TRIGRAMS temporary trigram keys")) {
                        pairCount += countUniqueTrigrams(sortedTrigramKeys(upper, candidates));
                    }
                }
                if (pairCount > Integer.MAX_VALUE) {
                    throw format("COMPLETION_TRIGRAMS pair count exceeds the decoder limit");
                }
            }
        }
        long expectedBytes = arrayBytes(pairCount, Long.BYTES);
        try (var expectedReservation = budget.reserve(expectedBytes,
                "COMPLETION_TRIGRAMS exact memberships")) {
            long[] expected = new long[(int) pairCount];
            int expectedCount = 0;
            for (int definitionId = 0; definitionId < definitions.size(); definitionId++) {
                String name = completionName(definitions.get(definitionId));
                if (name == null || name.isEmpty()) {
                    continue;
                }
                try (var ignored = reserveUppercase(name, budget)) {
                    String upper = name.toUpperCase(Locale.ROOT);
                    int width = upper.length() <= 3 ? upper.length() : 3;
                    int candidates = trigramCandidateCount(upper);
                    try (var trigrams = budget.reserve(arrayBytes(candidates, Long.BYTES),
                            "COMPLETION_TRIGRAMS temporary trigram keys")) {
                        long[] keys = sortedTrigramKeys(upper, candidates);
                        boolean hasPrevious = false;
                        long previous = 0;
                        for (long key : keys) {
                            if (hasPrevious && key == previous) {
                                continue;
                            }
                            hasPrevious = true;
                            previous = key;
                            int trigramId = strings.decodedId(key, width,
                                    "completion trigram");
                            expected[expectedCount++] =
                                    completionMembership(trigramId, definitionId);
                        }
                    }
                }
            }
            if (expectedCount != expected.length) {
                throw new IllegalStateException("Completion membership count mismatch");
            }
            Arrays.sort(expected);

            BinaryReader input = payloadReader(source, entry, "COMPLETION_TRIGRAMS", budget);
            int count = input.readCount("completion trigram", MAX_RECORD_COUNT, 0);
            int previousTrigramId = 0;
            int actualPairs = 0;
            for (int i = 0; i < count; i++) {
                int trigramId = input.readVarInt();
                if (strings.value(trigramId) == null) {
                    throw format("COMPLETION_TRIGRAMS has a null completion trigram");
                }
                if (trigramId <= previousTrigramId) {
                    throw format("COMPLETION_TRIGRAMS keys are not strictly sorted");
                }
                previousTrigramId = trigramId;
                int idCount = input.readCount("definition id", MAX_RECORD_COUNT, 0);
                if (idCount == 0) {
                    throw format("COMPLETION_TRIGRAMS contains an empty key");
                }
                int previousId = -1;
                for (int j = 0; j < idCount; j++) {
                    int definitionId = readDeltaId(input, "definition", definitions.size(),
                            previousId);
                    previousId = definitionId;
                    long pair = completionMembership(trigramId, definitionId);
                    if (actualPairs >= expected.length || expected[actualPairs] != pair) {
                        throw format("COMPLETION_TRIGRAMS has incorrect definition memberships");
                    }
                    actualPairs++;
                }
            }
            input.requireEnd();
            if (actualPairs != expected.length) {
                throw format("COMPLETION_TRIGRAMS does not cover every definition membership");
            }
        }
    }

    private static AllocationBudget.Reservation reserveUppercase(String value,
            AllocationBudget budget)
            throws ProjectIndexFormatException {
        return budget.reserve(64 + arrayBytes((long) value.length()
                * MAX_UPPERCASE_CHARS_PER_SOURCE_CHAR, Character.BYTES),
                "COMPLETION_TRIGRAMS uppercase name");
    }

    static void writeReverseDependencies(ChannelWriter output,
            Map<ReferenceMatchKey, List<IndexPathRef>> values, StringTable strings)
            throws IOException {
        output.writeVarInt(values.size());
        for (var entry : values.entrySet()) {
            writeMatchKey(output, strings, entry.getKey());
            output.writeVarInt(entry.getValue().size());
            for (IndexPathRef path : entry.getValue()) {
                writePath(output, strings, path);
            }
        }
    }

    static void validateReverseDependencies(ProjectIndexSource source, DirectoryEntry entry,
            StringTable strings, List<PackedLocation> locations, AllocationBudget budget)
            throws ProjectIndexFormatException {
        int upperBound = 0;
        for (PackedLocation location : locations) {
            if (hasTypedReference(location)
                    && location.locationType() != ObjectLocation.LocationType.DEFINITION) {
                upperBound++;
            }
        }
        long expectedBytes = arrayBytes(Math.multiplyExact((long) upperBound, 3), Long.BYTES);
        try (var ignored = budget.reserve(expectedBytes,
                "REVERSE_DEPENDENCIES exact memberships")) {
            long[] expected = new long[Math.multiplyExact(upperBound, 3)];
            int rows = 0;
            for (PackedLocation location : locations) {
                if (!hasTypedReference(location)
                        || location.locationType() == ObjectLocation.LocationType.DEFINITION) {
                    continue;
                }
                writeMatchTuple(location, strings, expected, rows * 3);
                expected[rows * 3 + 1] = withPathOrigin(expected[rows * 3 + 1],
                        location.origin().ordinal());
                expected[rows * 3 + 2] = strings.decodedId(location.relativePath());
                rows++;
            }
            sortTriples(expected, rows);
            int uniqueRows = deduplicateTriples(expected, rows);

            BinaryReader input = payloadReader(source, entry, "REVERSE_DEPENDENCIES", budget);
            int count = input.readCount("reverse dependency key", MAX_RECORD_COUNT, 0);
            MatchTuple previousKey = null;
            int actualRow = 0;
            for (int i = 0; i < count; i++) {
                MatchTuple key = readMatchTuple(input, strings);
                if (previousKey != null && compareMatchTuples(previousKey, key) >= 0) {
                    throw format("REVERSE_DEPENDENCIES keys are not strictly sorted");
                }
                previousKey = key;
                int pathCount = input.readCount("dependent path", MAX_RECORD_COUNT, 0);
                if (pathCount == 0) {
                    throw format("REVERSE_DEPENDENCIES contains an empty key");
                }
                long previousPathFirst = -1;
                long previousPathSecond = -1;
                for (int j = 0; j < pathCount; j++) {
                    int origin = input.readEnum(IndexPathOrigin.values(), "path origin").ordinal();
                    int pathId = input.readVarInt();
                    if (strings.value(pathId) == null) {
                        throw format("REVERSE_DEPENDENCIES has a null relative path");
                    }
                    long pathFirst = withPathOrigin(key.second(), origin);
                    long pathSecond = pathId;
                    if (j > 0 && comparePathTuple(previousPathFirst, previousPathSecond,
                            pathFirst, pathSecond) >= 0) {
                        throw format("REVERSE_DEPENDENCIES paths are not strictly sorted");
                    }
                    previousPathFirst = pathFirst;
                    previousPathSecond = pathSecond;
                    if (actualRow >= uniqueRows
                            || expected[actualRow * 3] != key.first()
                            || expected[actualRow * 3 + 1] != pathFirst
                            || expected[actualRow * 3 + 2] != pathSecond) {
                        throw format("REVERSE_DEPENDENCIES has incorrect key/path memberships");
                    }
                    actualRow++;
                }
            }
            input.requireEnd();
            if (actualRow != uniqueRows) {
                throw format("REVERSE_DEPENDENCIES does not cover every key/path membership");
            }
        }
    }

    static void writeUnresolved(ChannelWriter output, List<FileContribution> files,
            StringTable strings) throws IOException {
        List<FileContribution> unresolved = files.stream()
                .filter(file -> file.unresolvedAny() || !file.unresolvedCandidates().isEmpty()).toList();
        output.writeVarInt(unresolved.size());
        for (FileContribution file : unresolved) {
            writePath(output, strings, file.path());
            output.writeBoolean(file.unresolvedAny());
            List<ReferenceMatchKey> keys = file.unresolvedCandidates().stream()
                    .sorted(ReferenceMatchKey.CANONICAL_ORDER).toList();
            output.writeVarInt(keys.size());
            for (ReferenceMatchKey key : keys) {
                writeMatchKey(output, strings, key);
            }
        }
    }

    static List<UnresolvedRecord> readUnresolved(ProjectIndexSource source,
            DirectoryEntry entry,
            StringTable strings, AllocationBudget budget) throws ProjectIndexFormatException {
        BinaryReader input = payloadReader(source, entry, "UNRESOLVED_FILES", budget);
        int count = input.readCount("unresolved file", MAX_RECORD_COUNT, 96);
        budget.claim(listBytes(count, 2), "UNRESOLVED_FILES list and immutable copy");
        List<UnresolvedRecord> values = new ArrayList<>(count);
        IndexPathRef previous = null;
        for (int i = 0; i < count; i++) {
            IndexPathRef path = readPath(input, strings);
            if (previous != null && PATH_ORDER.compare(previous, path) >= 0) {
                throw format("UNRESOLVED_FILES paths are not strictly sorted");
            }
            previous = path;
            boolean any = input.readBoolean();
            int keyCount = input.readCount("unresolved candidate", MAX_RECORD_COUNT, 48);
            if (!any && keyCount == 0) {
                throw format("UNRESOLVED_FILES contains an empty record");
            }
            budget.claim(listBytes(keyCount, 1) + setBytes(keyCount),
                    "UNRESOLVED_FILES candidate containers");
            List<ReferenceMatchKey> keys = new ArrayList<>(keyCount);
            ReferenceMatchKey previousKey = null;
            for (int j = 0; j < keyCount; j++) {
                ReferenceMatchKey key = readMatchKey(input, strings);
                if (previousKey != null
                        && ReferenceMatchKey.CANONICAL_ORDER.compare(previousKey, key) >= 0) {
                    throw format("UNRESOLVED_FILES candidates are not strictly sorted");
                }
                previousKey = key;
                keys.add(key);
            }
            budget.claim(32, "UNRESOLVED_FILES record");
            values.add(new UnresolvedRecord(path, Set.copyOf(keys), any));
        }
        input.requireEnd();
        return List.copyOf(values);
    }

    static List<FileContribution> rebuildContributions(List<PathRange> ranges,
            List<PackedDefinition> definitions, List<PackedLocation> locations,
            List<UnresolvedRecord> unresolved, AllocationBudget budget)
            throws ProjectIndexFormatException {
        budget.claim(listBytes(ranges.size(), 2),
                "file contribution list and immutable copy");
        List<FileContribution> files = new ArrayList<>(ranges.size());
        int unresolvedIndex = 0;
        for (PathRange range : ranges) {
            List<PackedDefinition> fileDefinitions = definitions.subList(range.definitionStart(),
                    range.definitionStart() + range.definitionCount());
            List<PackedLocation> fileLocations = locations.subList(range.locationStart(),
                    range.locationStart() + range.locationCount());
            for (PackedDefinition definition : fileDefinitions) {
                if (!samePath(definition.object(), range.path())) {
                    throw format("BY_PATH record path does not match its definitions or locations");
                }
            }
            for (PackedLocation location : fileLocations) {
                if (!samePath(location, range.path())) {
                    throw format("BY_PATH record path does not match its definitions or locations");
                }
            }
            UnresolvedRecord state = UnresolvedRecord.EMPTY;
            if (unresolvedIndex < unresolved.size()) {
                UnresolvedRecord candidate = unresolved.get(unresolvedIndex);
                int comparison = PATH_ORDER.compare(candidate.path(), range.path());
                if (comparison < 0) {
                    throw format("UNRESOLVED_FILES references an unknown path");
                }
                if (comparison == 0) {
                    state = candidate;
                    unresolvedIndex++;
                }
            }
            budget.claim(80 + listBytes(fileDefinitions.size(), 1)
                    + listBytes(fileLocations.size(), 1), "file contribution object and lists");
            String relativePath = range.path().relativePath();
            long validationBytes = 608 + arrayBytes(relativePath.length(), Character.BYTES)
                    + pathNormalizationBytes(relativePath);
            try (var ignored = budget.reserve(validationBytes,
                    "temporary file contribution validation streams")) {
                files.add(new FileContribution(range.path(), fileDefinitions, fileLocations,
                        state.candidates(), state.any()));
            }
        }
        if (unresolvedIndex != unresolved.size()) {
            throw format("UNRESOLVED_FILES references an unknown path");
        }
        return List.copyOf(files);
    }

    private static boolean samePath(PackedLocation location, IndexPathRef path) {
        return location.origin() == path.origin()
                && location.relativePath().equals(path.relativePath());
    }

    private static int encodedStringSize(String value) {
        long bytes = 0;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current <= 0x7F) {
                bytes++;
            } else if (current <= 0x7FF) {
                bytes += 2;
            } else if (Character.isHighSurrogate(current)
                    && i + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(i + 1))) {
                bytes += 4;
                i++;
            } else if (Character.isSurrogate(current)) {
                throw new IllegalArgumentException(
                        "Project index string contains an unpaired UTF-16 surrogate");
            } else {
                bytes += 3;
            }
            if (bytes > MAX_RECORD_BYTES) {
                throw new IllegalArgumentException(
                        "Project index string UTF-8 payload exceeds the format limit");
            }
        }
        return (int) bytes;
    }

    private static void writeUtf8(BinaryOutput output, String value) throws IOException {
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current <= 0x7F) {
                output.writeByte(current);
            } else if (current <= 0x7FF) {
                output.writeByte(0xC0 | current >>> 6);
                output.writeByte(0x80 | current & 0x3F);
            } else if (Character.isHighSurrogate(current)
                    && i + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(i + 1))) {
                int codePoint = Character.toCodePoint(current, value.charAt(++i));
                output.writeByte(0xF0 | codePoint >>> 18);
                output.writeByte(0x80 | codePoint >>> 12 & 0x3F);
                output.writeByte(0x80 | codePoint >>> 6 & 0x3F);
                output.writeByte(0x80 | codePoint & 0x3F);
            } else if (Character.isSurrogate(current)) {
                throw new IllegalArgumentException(
                        "Project index string contains an unpaired UTF-16 surrogate");
            } else {
                output.writeByte(0xE0 | current >>> 12);
                output.writeByte(0x80 | current >>> 6 & 0x3F);
                output.writeByte(0x80 | current & 0x3F);
            }
        }
    }

    private static int encodedDefinitionSize(PackedDefinition definition, StringTable strings) {
        long constraintColumnsSize = 0;
        for (String column : definition.constraintColumns()) {
            constraintColumnsSize += stringIdSize(strings, column);
        }
        long size = varIntSize(definition.kind().ordinal())
                + encodedLocationSizeLong(definition.object(), strings)
                + stringIdSize(strings, definition.bareName())
                + stringIdSize(strings, definition.comment())
                + encodedArgumentsSize(definition.arguments(), strings)
                + encodedArgumentsSize(definition.orderBy(), strings)
                + encodedNameTypesSize(definition.returnColumns(), strings)
                + stringIdSize(strings, definition.returns())
                + 1L
                + encodedNameTypesSize(definition.relationColumns(), strings)
                + 1L
                + encodedNameTypesSize(definition.compositeAttributes(), strings)
                + 1L
                + varIntSize(definition.constraintColumns().size())
                + constraintColumnsSize
                + stringIdSize(strings, definition.operatorLeft())
                + stringIdSize(strings, definition.operatorRight())
                + stringIdSize(strings, definition.operatorReturns())
                + stringIdSize(strings, definition.castSource())
                + stringIdSize(strings, definition.castTarget())
                + varIntSize(definition.castContext() == null
                        ? 0 : definition.castContext().ordinal() + 1);
        return checkedRecordSize("definition", size);
    }

    private static int encodedLocationSize(PackedLocation location, StringTable strings) {
        return checkedRecordSize("location", encodedLocationSizeLong(location, strings));
    }

    private static long encodedLocationSizeLong(PackedLocation location, StringTable strings) {
        return encodedPathSize(location.origin(), location.relativePath(), strings)
                + varIntSize(location.offset())
                + varIntSize(location.lineNumber())
                + varIntSize(location.charPositionInLine())
                + varIntSize(location.length())
                + encodedReferenceSize(location.reference(), strings)
                + stringIdSize(strings, location.action())
                + stringIdSize(strings, location.alias())
                + varIntSize(location.locationType().ordinal())
                + varIntSize(location.danger() == null ? 0 : location.danger().ordinal() + 1);
    }

    private static long encodedArgumentsSize(List<PackedArgument> arguments, StringTable strings) {
        long size = varIntSize(arguments.size());
        for (PackedArgument argument : arguments) {
            size += varIntSize(argument.mode().ordinal())
                    + stringIdSize(strings, argument.name())
                    + stringIdSize(strings, argument.dataType())
                    + stringIdSize(strings, argument.defaultExpression())
                    + 1L;
        }
        return size;
    }

    private static long encodedNameTypesSize(List<NameType> values, StringTable strings) {
        long size = varIntSize(values.size());
        for (NameType value : values) {
            size += stringIdSize(strings, value.name()) + stringIdSize(strings, value.type());
        }
        return size;
    }

    private static int encodedPathSize(IndexPathRef path, StringTable strings) {
        return encodedPathSize(path.origin(), path.relativePath(), strings);
    }

    private static int encodedPathSize(IndexPathOrigin origin, String relativePath,
            StringTable strings) {
        return varIntSize(origin.ordinal()) + stringIdSize(strings, relativePath);
    }

    private static int encodedReferenceSize(ObjectReference reference, StringTable strings) {
        if (reference == null) {
            return 1;
        }
        return 1 + stringIdSize(strings, reference.schema())
                + stringIdSize(strings, reference.table())
                + stringIdSize(strings, reference.column())
                + varIntSize(reference.type() == null ? 0 : reference.type().ordinal() + 1);
    }

    private static int stringIdSize(StringTable strings, String value) {
        return varIntSize(strings.id(value));
    }

    private static int checkedRecordSize(String label, long size) {
        if (size > MAX_RECORD_BYTES) {
            throw new IllegalArgumentException(
                    "Project index " + label + " record exceeds the format limit");
        }
        return Math.toIntExact(size);
    }

    private static void writeDefinition(BinaryOutput output, StringTable strings,
            PackedDefinition definition) throws IOException {
        output.writeVarInt(definition.kind().ordinal());
        writeLocation(output, strings, definition.object());
        output.writeStringId(strings, definition.bareName());
        output.writeStringId(strings, definition.comment());
        writeArguments(output, strings, definition.arguments());
        writeArguments(output, strings, definition.orderBy());
        writeNameTypes(output, strings, definition.returnColumns());
        output.writeStringId(strings, definition.returns());
        output.writeBoolean(definition.setof());
        writeNameTypes(output, strings, definition.relationColumns());
        output.writeBoolean(definition.relationColumnsKnown());
        writeNameTypes(output, strings, definition.compositeAttributes());
        output.writeBoolean(definition.primaryKey());
        output.writeVarInt(definition.constraintColumns().size());
        for (String column : definition.constraintColumns()) {
            output.writeStringId(strings, column);
        }
        output.writeStringId(strings, definition.operatorLeft());
        output.writeStringId(strings, definition.operatorRight());
        output.writeStringId(strings, definition.operatorReturns());
        output.writeStringId(strings, definition.castSource());
        output.writeStringId(strings, definition.castTarget());
        output.writeVarInt(definition.castContext() == null ? 0 : definition.castContext().ordinal() + 1);
    }

    private static void writeLocation(BinaryOutput output, StringTable strings,
            PackedLocation location) throws IOException {
        writePath(output, strings, location.origin(), location.relativePath());
        output.writeVarInt(location.offset());
        output.writeVarInt(location.lineNumber());
        output.writeVarInt(location.charPositionInLine());
        output.writeVarInt(location.length());
        writeReference(output, strings, location.reference());
        output.writeStringId(strings, location.action());
        output.writeStringId(strings, location.alias());
        output.writeVarInt(location.locationType().ordinal());
        output.writeVarInt(location.danger() == null ? 0 : location.danger().ordinal() + 1);
    }

    static PackedLocation readLocation(BinaryReader input, StringTable strings)
            throws ProjectIndexFormatException {
        IndexPathOrigin origin = input.readEnum(IndexPathOrigin.values(), "path origin");
        String relativePath = input.readRequiredString(strings, "relative path");
        int offset = input.readVarInt();
        int line = input.readVarInt();
        int position = input.readVarInt();
        int length = input.readVarInt();
        ObjectReference reference = readReference(input, strings);
        String action = input.readString(strings);
        String alias = input.readString(strings);
        ObjectLocation.LocationType locationType = input.readEnum(
                ObjectLocation.LocationType.values(), "location type");
        int dangerOrdinal = input.readVarInt();
        DangerStatement danger = dangerOrdinal == 0 ? null
                : checkedEnum(DangerStatement.values(), dangerOrdinal - 1, "danger type");
        input.claim(192 + arrayBytes(relativePath.length(), Character.BYTES),
                "location object and retained normalized path");
        try (var ignored = input.reserve(pathNormalizationBytes(relativePath),
                "temporary path normalization")) {
            PackedLocation result = new PackedLocation(origin, relativePath, offset, line, position,
                    length, reference, action, alias, locationType, danger);
            if (!relativePath.equals(result.relativePath())) {
                throw new IllegalArgumentException(
                        "Index path is not canonical: " + relativePath);
            }
            return result;
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw format("Invalid or non-canonical location record", ex);
        }
    }

    private static void writeReference(BinaryOutput output, StringTable strings,
            ObjectReference reference) throws IOException {
        output.writeBoolean(reference != null);
        if (reference != null) {
            output.writeStringId(strings, reference.schema());
            output.writeStringId(strings, reference.table());
            output.writeStringId(strings, reference.column());
            output.writeVarInt(reference.type() == null ? 0 : reference.type().ordinal() + 1);
        }
    }

    private static ObjectReference readReference(BinaryReader input, StringTable strings)
            throws ProjectIndexFormatException {
        if (!input.readBoolean()) {
            return null;
        }
        String schema = input.readString(strings);
        String table = input.readString(strings);
        String column = input.readString(strings);
        int typeOrdinal = input.readVarInt();
        DbObjType type = typeOrdinal == 0 ? null
                : checkedEnum(DbObjType.values(), typeOrdinal - 1, "database object type");
        input.claim(64, "object reference");
        return new ObjectReference(schema, table, column, type);
    }

    private static void writeArguments(BinaryOutput output, StringTable strings,
            List<PackedArgument> arguments) throws IOException {
        output.writeVarInt(arguments.size());
        for (PackedArgument argument : arguments) {
            output.writeVarInt(argument.mode().ordinal());
            output.writeStringId(strings, argument.name());
            output.writeStringId(strings, argument.dataType());
            output.writeStringId(strings, argument.defaultExpression());
            output.writeBoolean(argument.readOnly());
        }
    }

    private static List<PackedArgument> readArguments(BinaryReader input, StringTable strings)
            throws ProjectIndexFormatException {
        int count = input.readCount("argument", MAX_NESTED_COUNT, 96);
        input.claim(listBytes(count, 2), "argument list and immutable copy");
        List<PackedArgument> arguments = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            arguments.add(new PackedArgument(input.readEnum(ArgMode.values(), "argument mode"),
                    input.readString(strings), input.readRequiredString(strings, "argument type"),
                    input.readString(strings), input.readBoolean()));
        }
        return List.copyOf(arguments);
    }

    private static void writeNameTypes(BinaryOutput output, StringTable strings,
            List<NameType> values) throws IOException {
        output.writeVarInt(values.size());
        for (NameType value : values) {
            output.writeStringId(strings, value.name());
            output.writeStringId(strings, value.type());
        }
    }

    private static List<NameType> readNameTypes(BinaryReader input, StringTable strings)
            throws ProjectIndexFormatException {
        int count = input.readCount("name/type pair", MAX_NESTED_COUNT, 64);
        input.claim(listBytes(count, 2), "name/type list and immutable copy");
        List<NameType> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            values.add(new NameType(input.readRequiredString(strings, "name"),
                    input.readRequiredString(strings, "type")));
        }
        return List.copyOf(values);
    }

    private static void writeMatchKey(ChannelWriter output, StringTable strings,
            ReferenceMatchKey key) throws IOException {
        output.writeVarInt(key.family().ordinal());
        output.writeVarInt(key.exactType() == null ? 0 : key.exactType().ordinal() + 1);
        output.writeStringId(strings, key.schema());
        output.writeStringId(strings, key.table());
        output.writeStringId(strings, key.column());
        output.writeStringId(strings, key.alias());
        output.writeBoolean(key.global());
    }

    static void writeMatchKey(ChannelWriter output, long first,
            long second) throws IOException {
        output.writeVarInt((int) (first >>> 56));
        output.writeVarInt((int) (first >>> 48 & 0xff));
        output.writeVarInt((int) (first >>> 24 & 0xffffff));
        output.writeVarInt((int) (first & 0xffffff));
        output.writeVarInt((int) (second >>> 40 & 0xffffff));
        output.writeVarInt((int) (second >>> 16 & 0xffffff));
        output.writeBoolean((second & 1L << 15) != 0);
    }

    private static ReferenceMatchKey readMatchKey(BinaryReader input, StringTable strings)
            throws ProjectIndexFormatException {
        ReferenceMatchKey.MatchFamily family = input.readEnum(
                ReferenceMatchKey.MatchFamily.values(), "match family");
        int exactOrdinal = input.readVarInt();
        DbObjType exact = exactOrdinal == 0 ? null
                : checkedEnum(DbObjType.values(), exactOrdinal - 1, "exact object type");
        input.claim(72, "reference match key");
        try {
            return new ReferenceMatchKey(family, exact, input.readString(strings),
                    input.readString(strings), input.readString(strings), input.readString(strings),
                    input.readBoolean());
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw format("Invalid reference match key", ex);
        }
    }

    private static MatchTuple readMatchTuple(BinaryReader input, StringTable strings)
            throws ProjectIndexFormatException {
        int family = input.readEnum(ReferenceMatchKey.MatchFamily.values(), "match family").ordinal();
        int exactOrdinal = input.readVarInt();
        if (exactOrdinal != 0) {
            checkedEnum(DbObjType.values(), exactOrdinal - 1, "exact object type");
        }
        boolean exactFamily = family == ReferenceMatchKey.MatchFamily.EXACT.ordinal();
        if (exactFamily != (exactOrdinal != 0)) {
            throw format("Invalid reference match key family/type combination");
        }
        int schema = readStringId(input, strings);
        int table = readStringId(input, strings);
        int column = readStringId(input, strings);
        int alias = readStringId(input, strings);
        boolean global = input.readBoolean();
        return packMatchTuple(family, exactOrdinal, schema, table, column, alias, global);
    }

    private static int readStringId(BinaryReader input, StringTable strings)
            throws ProjectIndexFormatException {
        int id = input.readVarInt();
        strings.value(id);
        return id;
    }

    private static void writeMatchTuple(PackedLocation location, StringTable strings,
            long[] target, int offset)
            throws ProjectIndexFormatException {
        ObjectReference reference = location.reference();
        if (reference == null || reference.type() == null) {
            throw format("A semantic match tuple requires a typed object reference");
        }
        ReferenceMatchKey.MatchFamily family = ReferenceMatchKey.family(reference.type());
        int exact = family == ReferenceMatchKey.MatchFamily.EXACT
                ? reference.type().ordinal() + 1 : 0;
        target[offset] = matchKeyWord1(family.ordinal(), exact,
                strings.decodedId(reference.schema()),
                strings.decodedId(reference.table()));
        target[offset + 1] = matchKeyWord2(
                strings.decodedId(reference.column()),
                strings.decodedId(location.alias()), location.isGlobal());
    }

    private static boolean matchesMatchTuple(MatchTuple expected, PackedLocation location,
            StringTable strings) throws ProjectIndexFormatException {
        ObjectReference reference = location.reference();
        if (reference == null || reference.type() == null) {
            return false;
        }
        ReferenceMatchKey.MatchFamily family = ReferenceMatchKey.family(reference.type());
        int exact = family == ReferenceMatchKey.MatchFamily.EXACT
                ? reference.type().ordinal() + 1 : 0;
        long first = matchKeyWord1(family.ordinal(), exact,
                strings.decodedId(reference.schema()),
                strings.decodedId(reference.table()));
        long second = matchKeyWord2(strings.decodedId(reference.column()),
                strings.decodedId(location.alias()), location.isGlobal());
        return expected.first() == first && expected.second() == second;
    }

    private static MatchTuple packMatchTuple(int family, int exact, int schema, int table,
            int column, int alias, boolean global) {
        return new MatchTuple(matchKeyWord1(family, exact, schema, table),
                matchKeyWord2(column, alias, global));
    }

    static long matchKeyWord1(ReferenceMatchKey key, StringTable strings) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(strings, "strings");
        int exact = key.exactType() == null ? 0 : key.exactType().ordinal() + 1;
        return matchKeyWord1(key.family().ordinal(), exact,
                strings.id(key.schema()), strings.id(key.table()));
    }

    static long matchKeyWord2(ReferenceMatchKey key, StringTable strings) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(strings, "strings");
        return matchKeyWord2(strings.id(key.column()),
                strings.id(key.alias()), key.global());
    }

    static long matchKeyWord1(PackedLocation location,
            StringTable strings) {
        ObjectReference reference = requireTypedReference(location);
        ReferenceMatchKey.MatchFamily family =
                ReferenceMatchKey.family(reference.type());
        int exact = family == ReferenceMatchKey.MatchFamily.EXACT
                ? reference.type().ordinal() + 1 : 0;
        return matchKeyWord1(family.ordinal(), exact,
                strings.id(reference.schema()),
                strings.id(reference.table()));
    }

    static long matchKeyWord2(PackedLocation location,
            StringTable strings) {
        ObjectReference reference = requireTypedReference(location);
        return matchKeyWord2(strings.id(reference.column()),
                strings.id(location.alias()), location.isGlobal());
    }

    private static ObjectReference requireTypedReference(
            PackedLocation location) {
        Objects.requireNonNull(location, "location");
        ObjectReference reference = location.reference();
        if (reference == null || reference.type() == null) {
            throw new IllegalArgumentException(
                    "A semantic match tuple requires a typed object reference");
        }
        return reference;
    }

    static long matchMembership(int kind, int id) {
        if (kind != 0 && kind != 1) {
            throw new IllegalArgumentException("Match membership kind must be 0 or 1");
        }
        if (id < 0) {
            throw new IllegalArgumentException("Match membership id must not be negative");
        }
        return (long) kind << 32 | Integer.toUnsignedLong(id);
    }

    static long completionMembership(int stringId, int definitionId) {
        if (stringId < 0 || definitionId < 0) {
            throw new IllegalArgumentException(
                    "Completion membership ids must not be negative");
        }
        return (long) stringId << 32 | Integer.toUnsignedLong(definitionId);
    }

    static int matchMembershipKind(long membership) {
        return (int) (membership >>> 32);
    }

    static int matchMembershipId(long membership) {
        return (int) membership;
    }

    static int completionStringId(long membership) {
        return (int) (membership >>> 32);
    }

    static int completionDefinitionId(long membership) {
        return (int) membership;
    }

    private static long matchKeyWord1(int family, int exact, int schema, int table) {
        return (long) family << 56 | (long) exact << 48
                | (long) schema << 24 | table;
    }

    private static long matchKeyWord2(int column, int alias, boolean global) {
        return (long) column << 40 | (long) alias << 16
                | (global ? 1L << 15 : 0);
    }

    private static int compareMatchTuples(MatchTuple left, MatchTuple right) {
        int result = Long.compareUnsigned(left.first(), right.first());
        return result != 0 ? result : Long.compareUnsigned(left.second(), right.second());
    }

    private static int validateDefinitionMemberships(BinaryReader input, MatchTuple key,
            List<PackedDefinition> definitions, BitSet seen, StringTable strings)
            throws ProjectIndexFormatException {
        int count = input.readCount("definition id", MAX_RECORD_COUNT, 0);
        int previous = -1;
        for (int i = 0; i < count; i++) {
            int id = readDeltaId(input, "definition", definitions.size(), previous);
            previous = id;
            PackedLocation object = definitions.get(id).object();
            if (!matchesMatchTuple(key, object, strings)) {
                throw format("BY_MATCH_KEY has an incorrect definition membership");
            }
            if (seen.get(id)) {
                throw format("BY_MATCH_KEY repeats a definition membership");
            }
            seen.set(id);
        }
        return count;
    }

    private static int validateLocationMemberships(BinaryReader input, MatchTuple key,
            List<PackedLocation> locations, BitSet seen, StringTable strings)
            throws ProjectIndexFormatException {
        int count = input.readCount("location id", MAX_RECORD_COUNT, 0);
        int previous = -1;
        for (int i = 0; i < count; i++) {
            int id = readDeltaId(input, "location", locations.size(), previous);
            previous = id;
            PackedLocation location = locations.get(id);
            if (!matchesMatchTuple(key, location, strings)) {
                throw format("BY_MATCH_KEY has an incorrect location membership");
            }
            if (seen.get(id)) {
                throw format("BY_MATCH_KEY repeats a location membership");
            }
            seen.set(id);
        }
        return count;
    }

    private static int readDeltaId(BinaryReader input, String label, int upperBound, int previous)
            throws ProjectIndexFormatException {
        int delta = input.readVarInt();
        long id = (long) previous + delta + 1;
        if (id < 0 || id >= upperBound) {
            throw format("Invalid " + label + " id: " + id);
        }
        return (int) id;
    }

    private static void requireExactCoverage(String label, List<?> values, BitSet seen)
            throws ProjectIndexFormatException {
        for (int i = 0; i < values.size(); i++) {
            PackedLocation location = values.get(i) instanceof PackedDefinition definition
                    ? definition.object() : (PackedLocation) values.get(i);
            if (hasTypedReference(location) != seen.get(i)) {
                throw format("BY_MATCH_KEY does not exactly cover every typed " + label);
            }
        }
    }

    private static long bitSetBytes(int bits) {
        return 32 + arrayBytes((bits + 63L) >>> 6, Long.BYTES);
    }

    static boolean hasTypedReference(PackedLocation location) {
        return location.reference() != null && location.reference().type() != null;
    }

    static String completionName(PackedDefinition definition) {
        String name = definition.bareName();
        if (name == null && definition.object().reference() != null) {
            name = definition.object().reference().getName();
        }
        return name;
    }

    private static int trigramCandidateCount(String value) {
        return value.length() <= 3 ? 1 : value.length() - 2;
    }

    static void forEachCompletionMembership(
            PackedDefinition definition, int definitionId,
            StringTable strings, LongMembershipConsumer consumer)
            throws IOException {
        String name = completionName(definition);
        if (name == null || name.isEmpty()) {
            return;
        }
        String upper = name.toUpperCase(Locale.ROOT);
        if (upper.length() <= 3) {
            long key = TrigramAccumulator.pack(
                    upper, 0, upper.length());
            consumer.accept(completionMembership(
                    strings.idPacked(key, upper.length()),
                    definitionId));
            return;
        }
        for (int start = 0; start <= upper.length() - 3; start++) {
            long key = TrigramAccumulator.pack(upper, start, 3);
            consumer.accept(completionMembership(
                    strings.idPacked(key, 3), definitionId));
        }
    }

    private static long[] sortedTrigramKeys(String value, int candidates) {
        int width = value.length() <= 3 ? value.length() : 3;
        long[] keys = new long[candidates];
        for (int start = 0; start < candidates; start++) {
            long key = 0;
            for (int i = 0; i < width; i++) {
                key = key << 16 | value.charAt(start + i);
            }
            keys[start] = key;
        }
        Arrays.sort(keys);
        return keys;
    }

    private static int countUniqueTrigrams(long[] keys) {
        int count = 0;
        boolean hasPrevious = false;
        long previous = 0;
        for (long key : keys) {
            if (!hasPrevious || key != previous) {
                count++;
                hasPrevious = true;
                previous = key;
            }
        }
        return count;
    }

    static long withPathOrigin(long matchSecond, int origin) {
        return matchSecond | (long) origin << 7;
    }

    static long withoutPathOrigin(long matchSecond) {
        return matchSecond & ~(0xffL << 7);
    }

    static int pathOrigin(long matchSecond) {
        return (int) (matchSecond >>> 7 & 0xff);
    }

    private static int comparePathTuple(long leftFirst, long leftSecond,
            long rightFirst, long rightSecond) {
        int result = Long.compareUnsigned(leftFirst, rightFirst);
        return result != 0 ? result : Long.compareUnsigned(leftSecond, rightSecond);
    }

    private static void sortTriples(long[] values, int rows) {
        ProjectIndexTupleBuffer.sortRows(values, 3, rows);
    }

    private static int deduplicateTriples(long[] values, int rows) {
        return ProjectIndexTupleBuffer.deduplicateRows(values, 3, rows);
    }

    private static void writeIds(ChannelWriter output, List<Integer> ids) throws IOException {
        output.writeVarInt(ids.size());
        int previous = -1;
        for (int id : ids) {
            output.writeVarInt(id - previous - 1);
            previous = id;
        }
    }

    private static int validateIds(BinaryReader input, String label, int upperBound)
            throws ProjectIndexFormatException {
        int count = input.readCount(label + " id", MAX_RECORD_COUNT, 0);
        int previous = -1;
        for (int i = 0; i < count; i++) {
            int delta = input.readVarInt();
            long id = (long) previous + delta + 1;
            if (id < 0 || id >= upperBound) {
                throw format("Invalid " + label + " id: " + id);
            }
            previous = (int) id;
        }
        return count;
    }

    private static void writePath(BinaryOutput output, StringTable strings,
            IndexPathOrigin origin, String relativePath) throws IOException {
        output.writeVarInt(origin.ordinal());
        output.writeStringId(strings, relativePath);
    }

    private static void writePath(ChannelWriter output, StringTable strings, IndexPathRef path)
            throws IOException {
        output.writeVarInt(path.origin().ordinal());
        output.writeStringId(strings, path.relativePath());
    }

    private static IndexPathRef readPath(BinaryReader input, StringTable strings)
            throws ProjectIndexFormatException {
        IndexPathOrigin origin = input.readEnum(IndexPathOrigin.values(), "path origin");
        String path = input.readRequiredString(strings, "relative path");
        input.claim(96 + arrayBytes(path.length(), Character.BYTES),
                "path object and retained normalized string");
        try (var ignored = input.reserve(pathNormalizationBytes(path),
                "temporary path normalization")) {
            IndexPathRef result = new IndexPathRef(origin, path);
            if (!path.equals(result.relativePath())) {
                throw new IllegalArgumentException("Index path is not canonical: " + path);
            }
            return result;
        } catch (IllegalArgumentException ex) {
            throw format("Invalid or non-canonical relative index path: " + path, ex);
        }
    }

    private static long pathNormalizationBytes(String path) {
        try {
            return Math.addExact(256, Math.multiplyExact((long) path.length(), 48));
        } catch (ArithmeticException ex) {
            return Long.MAX_VALUE;
        }
    }

    private static <T> void writeBlocks(ChannelWriter output,
            List<T> records, ToIntFunction<T> sizeCalculator,
            RecordEncoder<T> encoder) throws IOException {
        writeBlocks(output, records, sizeCalculator, encoder, null);
    }

    private static <T> void writeBlocks(ChannelWriter output,
            List<T> records, ToIntFunction<T> sizeCalculator,
            RecordEncoder<T> encoder,
            ProjectIndexLocatorCapture.Blocks locator)
            throws IOException {
        output.writeVarInt(records.size());
        var block = new BinaryWriter(
                MAX_RAW_BLOCK_BYTES, MAX_RAW_BLOCK_BYTES);
        int blockCount = 0;
        int blockRecords = 0;
        int writtenRecords = 0;
        for (T value : records) {
            int expectedSize = sizeCalculator.applyAsInt(value);
            if (expectedSize < 0
                    || expectedSize > MAX_RECORD_BYTES) {
                throw new IllegalArgumentException(
                        "Packed record is too large");
            }
            int framedLength =
                    varIntSize(expectedSize) + expectedSize;
            if (block.size() > 0
                    && block.size() + framedLength
                            > MAX_RAW_BLOCK_BYTES) {
                writeBlock(output, block, locator,
                        writtenRecords - blockRecords,
                        blockRecords);
                blockCount++;
                block.reset();
                blockRecords = 0;
            }

            if (framedLength > MAX_RAW_BLOCK_BYTES) {
                output.writeVarInt(framedLength);
                if (locator != null) {
                    locator.add(writtenRecords, 1,
                            output.size(), framedLength);
                }
                output.writeVarInt(expectedSize);
                long bodyStart = output.size();
                encoder.write(output, value);
                if (output.size() - bodyStart != expectedSize) {
                    throw new IllegalStateException(
                            "Packed record size calculation mismatch");
                }
                blockCount++;
                writtenRecords++;
                continue;
            }

            block.writeVarInt(expectedSize);
            int bodyStart = block.size();
            encoder.write(block, value);
            if (block.size() - bodyStart != expectedSize) {
                throw new IllegalStateException(
                        "Packed record size calculation mismatch");
            }
            blockRecords++;
            writtenRecords++;
            if (block.size() == MAX_RAW_BLOCK_BYTES) {
                writeBlock(output, block, locator,
                        writtenRecords - blockRecords,
                        blockRecords);
                blockCount++;
                block.reset();
                blockRecords = 0;
            }
        }
        if (block.size() > 0) {
            writeBlock(output, block, locator,
                    writtenRecords - blockRecords, blockRecords);
            blockCount++;
        }
        output.writeVarInt(blockCount);
    }

    private static final class StreamingBlocks<T> {
        private final ChannelWriter output;
        private final int expectedRecords;
        private final ToIntFunction<T> sizeCalculator;
        private final RecordEncoder<T> encoder;
        private final ProjectIndexLocatorCapture.Blocks locator;
        private final BinaryWriter block =
                new BinaryWriter(MAX_RAW_BLOCK_BYTES,
                        MAX_RAW_BLOCK_BYTES);
        private int records;
        private int blocks;
        private int blockRecords;

        private StreamingBlocks(ChannelWriter output,
                int expectedRecords,
                ToIntFunction<T> sizeCalculator,
                RecordEncoder<T> encoder,
                ProjectIndexLocatorCapture.Blocks locator)
                throws IOException {
            this.output = output;
            this.expectedRecords = expectedRecords;
            this.sizeCalculator = sizeCalculator;
            this.encoder = encoder;
            this.locator = locator;
            output.writeVarInt(expectedRecords);
        }

        private void add(T value) throws IOException {
            if (records >= expectedRecords) {
                throw new IllegalStateException(
                        "Streaming section contains too many records");
            }
            int expectedSize = sizeCalculator.applyAsInt(value);
            if (expectedSize < 0
                    || expectedSize > MAX_RECORD_BYTES) {
                throw new IllegalArgumentException(
                        "Packed record is too large");
            }
            int framedLength =
                    varIntSize(expectedSize) + expectedSize;
            if (block.size() > 0
                    && block.size() + framedLength
                            > MAX_RAW_BLOCK_BYTES) {
                writeBlock(output, block, locator,
                        records - blockRecords, blockRecords);
                blocks++;
                block.reset();
                blockRecords = 0;
            }

            if (framedLength > MAX_RAW_BLOCK_BYTES) {
                output.writeVarInt(framedLength);
                if (locator != null) {
                    locator.add(records, 1, output.size(),
                            framedLength);
                }
                output.writeVarInt(expectedSize);
                long bodyStart = output.size();
                encoder.write(output, value);
                if (output.size() - bodyStart != expectedSize) {
                    throw new IllegalStateException(
                            "Packed record size calculation mismatch");
                }
                blocks++;
                records++;
                return;
            }

            block.writeVarInt(expectedSize);
            int bodyStart = block.size();
            encoder.write(block, value);
            if (block.size() - bodyStart != expectedSize) {
                throw new IllegalStateException(
                        "Packed record size calculation mismatch");
            }
            blockRecords++;
            records++;
            if (block.size() == MAX_RAW_BLOCK_BYTES) {
                writeBlock(output, block, locator,
                        records - blockRecords, blockRecords);
                blocks++;
                block.reset();
                blockRecords = 0;
            }
        }

        private void finish() throws IOException {
            if (records != expectedRecords) {
                throw new IllegalStateException(
                        "Streaming section record count changed between passes");
            }
            if (block.size() > 0) {
                writeBlock(output, block, locator,
                        records - blockRecords, blockRecords);
                blocks++;
                block.reset();
                blockRecords = 0;
            }
            output.writeVarInt(blocks);
        }
    }

    private static void writeBlock(ChannelWriter output,
            BinaryWriter block) throws IOException {
        writeBlock(output, block, null, 0, 0);
    }

    private static void writeBlock(ChannelWriter output,
            BinaryWriter block,
            ProjectIndexLocatorCapture.Blocks locator,
            int startRecord, int recordCount) throws IOException {
        output.writeVarInt(block.size());
        if (locator != null) {
            locator.add(startRecord, recordCount,
                    output.size(), block.size());
        }
        block.writeTo(output);
    }

    private static <T> List<T> readBlocks(ProjectIndexSource source, DirectoryEntry entry,
            String section, int maxRecords, int estimatedRecordBytes, AllocationBudget budget,
            RecordDecoder<T> decoder)
            throws ProjectIndexFormatException {
        BinaryReader input = payloadReader(source, entry, section, budget);
        int recordCount = input.readCount("record", maxRecords, estimatedRecordBytes);
        budget.claim(listBytes(recordCount, 1), section + " record list");
        List<T> records = new ArrayList<>(recordCount);
        int blockCount = 0;
        while (records.size() < recordCount) {
            int blockLength = input.readVarInt();
            if (blockLength == 0) {
                throw format(section + " contains a zero-length raw block");
            }
            if (blockLength > MAX_RECORD_BYTES + 5) {
                throw format(section + " raw block exceeds the size limit");
            }
            long blockOffset = input.absolutePosition();
            if (blockLength > MAX_RAW_BLOCK_BYTES) {
                validateOversizedBlock(source, blockOffset, blockLength, section);
            }
            BinaryReader block = input.slice(blockLength, section + " block " + blockCount);
            while (block.hasRemaining()) {
                if (records.size() >= recordCount) {
                    throw format(section + " contains more records than declared");
                }
                int recordLength = block.readVarInt();
                if (recordLength > MAX_RECORD_BYTES) {
                    throw format(section + " record exceeds the size limit");
                }
                BinaryReader record = block.slice(recordLength, section + " record " + records.size());
                records.add(decoder.decode(record));
                record.requireEnd();
            }
            blockCount++;
        }
        int declaredBlockCount = input.readCount("block", maxRecords, 8);
        input.requireEnd();
        if (blockCount != declaredBlockCount || (recordCount == 0) != (blockCount == 0)) {
            throw format(section + " block count mismatch");
        }
        return Collections.unmodifiableList(records);
    }

    private static void validateOversizedBlock(ProjectIndexSource source, long offset, int length,
            String section)
            throws ProjectIndexFormatException {
        BinaryReader block = new BinaryReader(source, offset, length,
                section + " oversized block", null);
        int records = 0;
        while (block.hasRemaining()) {
            int recordLength = block.readVarInt();
            if (recordLength > MAX_RECORD_BYTES) {
                throw format(section + " record exceeds the size limit");
            }
            block.slice(recordLength, section + " oversized record");
            if (++records > 1) {
                throw format(section + " raw block exceeds the size limit for multiple records");
            }
        }
        if (records != 1) {
            throw format(section + " oversized raw block must contain exactly one record");
        }
    }

    private static BinaryReader payloadReader(ProjectIndexSource source, DirectoryEntry entry,
            String label,
            AllocationBudget budget) throws ProjectIndexFormatException {
        return new BinaryReader(source, entry.offset() + SECTION_FRAME_SIZE,
                entry.payloadLength(), label, budget);
    }

    record BoundedPrepared(
            ProjectIndexManifest manifest,
            StringTable strings,
            ProjectIndexContributionSource.PassCounts counts,
            long[] sectionMembershipCounts) {

        BoundedPrepared {
            sectionMembershipCounts = sectionMembershipCounts.clone();
        }

        @Override
        public long[] sectionMembershipCounts() {
            return sectionMembershipCounts.clone();
        }
    }

    private static final class BoundedAccumulator {

        private final ProjectIndexManifest manifest;
        private final int maxSectionBytes;
        private final ProjectIndexWriteContext context;
        private final ProspectiveStrings strings =
                new ProspectiveStrings(0, false);
        private final PackedTrigramSet completionStrings;
        private long matchMemberships;
        private long completionMemberships;
        private long reverseMemberships;
        private long completionCandidates;
        private long records;

        private BoundedAccumulator(ProjectIndexManifest manifest,
                int maxSectionBytes,
                ProjectIndexWriteContext context) {
            this.manifest = manifest;
            this.maxSectionBytes = maxSectionBytes;
            this.context = context;
            completionStrings = new PackedTrigramSet(context);
            strings.add(manifest.coreVersion());
            strings.add(manifest.uiVersion());
            strings.add(manifest.projectIdentity());
            manifest.files().forEach(stamp ->
                strings.add(stamp.path().relativePath()));
        }

        private void add(int fileId, int definitionStart,
                int locationStart, FileContribution file)
                throws IOException {
            context.requireNotCancelled();
            requireWriteCount("unresolved candidate",
                    file.unresolvedCandidates().size(), MAX_RECORD_COUNT);
            strings.add(file.path().relativePath());
            for (PackedDefinition definition : file.definitions()) {
                pollRecord();
                validateDefinitionWriteLimits(definition);
                strings.addDefinition(definition);
                if (hasTypedReference(definition.object())) {
                    matchMemberships = saturatingAdd(
                            matchMemberships, 1);
                }
                String name = completionName(definition);
                if (name == null || name.isEmpty()) {
                    continue;
                }
                String upper = name.toUpperCase(Locale.ROOT);
                int candidates = trigramCandidateCount(upper);
                completionMemberships = saturatingAdd(
                        completionMemberships, candidates);
                addCompletionStrings(upper, candidates);
            }
            for (PackedLocation location : file.locations()) {
                pollRecord();
                strings.addLocation(location);
                if (!hasTypedReference(location)) {
                    continue;
                }
                matchMemberships = saturatingAdd(
                        matchMemberships, 1);
                if (location.locationType()
                        != ObjectLocation.LocationType.DEFINITION) {
                    reverseMemberships = saturatingAdd(
                            reverseMemberships, 1);
                }
            }
            for (ReferenceMatchKey key :
                    file.unresolvedCandidates()) {
                pollRecord();
                strings.addMatchKey(key);
            }
        }

        private BoundedPrepared finish(
                ProjectIndexContributionSource.PassCounts counts)
                throws IOException {
            ProspectiveWork prospective = strings.finishBounded();
            StringTable table = StringTable.build(
                    prospective, completionStrings, context);
            requireWriteCount("string", table.values().size(),
                    MAX_STRING_COUNT);
            long operations = 0;
            for (String value : table.values()) {
                pollCancellation(context, operations++);
                encodedStringSize(value);
            }
            requireProspectiveSection("STRINGS",
                    blocksSizeBounded(table.values(),
                            ProjectIndexRecords::encodedStringSize,
                            context),
                    maxSectionBytes);
            long[] memberships =
                    new long[SectionType.values().length];
            memberships[SectionType.BY_MATCH_KEY.ordinal()] =
                    matchMemberships;
            memberships[SectionType.COMPLETION_TRIGRAMS.ordinal()] =
                    completionMemberships;
            memberships[SectionType.REVERSE_DEPENDENCIES.ordinal()] =
                    reverseMemberships;
            return new BoundedPrepared(manifest, table, counts,
                    memberships);
        }

        private void addCompletionStrings(String upper,
                int candidates) throws IOException {
            int width = upper.length() <= 3
                    ? upper.length() : 3;
            for (int start = 0; start < candidates; start++) {
                if ((completionCandidates++
                        & CANCELLATION_POLL_MASK) == 0) {
                    context.requireNotCancelled();
                }
                long key = TrigramAccumulator.pack(
                        upper, start, width);
                completionStrings.add(key);
            }
        }

        private void pollRecord()
                throws ProjectIndexStore.WriteCancelledException {
            records++;
            if ((records & CANCELLATION_POLL_MASK) == 0) {
                context.requireNotCancelled();
            }
        }

        private static long saturatingAdd(long current, long additional) {
            if (additional < 0) {
                throw new IllegalArgumentException(
                        "Project index membership hint must not be negative");
            }
            return current > Long.MAX_VALUE - additional
                    ? Long.MAX_VALUE : current + additional;
        }
    }

    record StreamingPrepared(
            ProjectIndexManifest manifest,
            AuxiliaryIndexes indexes,
            StringTable strings,
            int fileCount,
            int definitionCount,
            int locationCount,
            int unresolvedCount) {
    }

    private static final class StreamingAccumulator {
        private final ProjectIndexManifest manifest;
        private final int maxSectionBytes;
        private final ProspectiveStrings strings = new ProspectiveStrings(0);
        private final TreeMap<ReferenceMatchKey, MutableMatchIds> match =
                new TreeMap<>(ReferenceMatchKey.CANONICAL_ORDER);
        private final TreeMap<ReferenceMatchKey, Set<IndexPathRef>> reverse =
                new TreeMap<>(ReferenceMatchKey.CANONICAL_ORDER);
        private final TrigramAccumulator completion = new TrigramAccumulator();
        private int files;
        private int definitions;
        private int locations;
        private int unresolved;
        private long matchMemberships;
        private long completionMemberships;
        private long reverseMemberships;
        private long auxiliaryBytes;

        private StreamingAccumulator(ProjectIndexManifest manifest,
                int maxSectionBytes) {
            this.manifest = manifest;
            this.maxSectionBytes = maxSectionBytes;
            strings.add(manifest.coreVersion());
            strings.add(manifest.uiVersion());
            strings.add(manifest.projectIdentity());
            manifest.files().forEach(stamp ->
                    strings.add(stamp.path().relativePath()));
        }

        private void add(FileContribution file) {
            if (files >= manifest.files().size()
                    || !manifest.files().get(files).path().equals(file.path())) {
                throw new IllegalArgumentException(
                        "Streaming contributions are not in manifest order");
            }
            files++;
            requireWriteCount("unresolved candidate",
                    file.unresolvedCandidates().size(), MAX_RECORD_COUNT);
            strings.add(file.path().relativePath());

            for (PackedDefinition definition : file.definitions()) {
                if (definitions >= MAX_RECORD_COUNT) {
                    throw new IllegalArgumentException(
                            "Project index definition count exceeds the format limit");
                }
                validateDefinitionWriteLimits(definition);
                strings.addDefinition(definition);
                if (hasTypedReference(definition.object())) {
                    match.computeIfAbsent(ReferenceMatchKey.from(definition.object()),
                            ignored -> new MutableMatchIds()).definitions.add(definitions);
                    matchMemberships = checkedProspectiveAdd(matchMemberships, 1);
                    addAuxiliaryBytes(PROSPECTIVE_MATCH_MEMBERSHIP_BYTES);
                }
                String name = completionName(definition);
                if (name != null && !name.isEmpty()) {
                    requireCompletionUppercaseBound(name, maxSectionBytes);
                    String upper = name.toUpperCase(Locale.ROOT);
                    int candidates = trigramCandidateCount(upper);
                    completionMemberships = checkedProspectiveAdd(
                            completionMemberships, candidates);
                    addAuxiliaryBytes(checkedProspectiveMultiply(candidates,
                            PROSPECTIVE_COMPLETION_MEMBERSHIP_BYTES));
                    completion.add(upper, definitions);
                }
                definitions++;
            }

            for (PackedLocation location : file.locations()) {
                if (locations >= MAX_RECORD_COUNT) {
                    throw new IllegalArgumentException(
                            "Project index location count exceeds the format limit");
                }
                strings.addLocation(location);
                if (hasTypedReference(location)) {
                    ReferenceMatchKey key = ReferenceMatchKey.from(location);
                    match.computeIfAbsent(key,
                            ignored -> new MutableMatchIds()).locations.add(locations);
                    matchMemberships = checkedProspectiveAdd(matchMemberships, 1);
                    addAuxiliaryBytes(PROSPECTIVE_MATCH_MEMBERSHIP_BYTES);
                    if (location.locationType()
                            != ObjectLocation.LocationType.DEFINITION) {
                        reverse.computeIfAbsent(key,
                                ignored -> new TreeSet<>(PATH_ORDER)).add(file.path());
                        reverseMemberships = checkedProspectiveAdd(
                                reverseMemberships, 1);
                        addAuxiliaryBytes(
                                PROSPECTIVE_REVERSE_MEMBERSHIP_BYTES);
                    }
                }
                locations++;
            }

            file.unresolvedCandidates().forEach(strings::addMatchKey);
            if (file.unresolvedAny() || !file.unresolvedCandidates().isEmpty()) {
                unresolved++;
            }
            requireProspectiveBuildBytes(checkedProspectiveAdd(
                    auxiliaryBytes, strings.retainedBytes));
        }

        private StreamingPrepared finish() {
            if (files != manifest.files().size()) {
                throw new IllegalArgumentException(
                        "Streaming source did not provide every manifest contribution");
            }
            requireProspectiveCount("match membership", matchMemberships);
            requireProspectiveCount(
                    "completion membership", completionMemberships);
            requireProspectiveCount(
                    "reverse dependency membership", reverseMemberships);
            requireProspectiveSection("COMPLETION_TRIGRAMS",
                    checkedProspectiveAdd(5,
                            checkedProspectiveMultiply(completionMemberships,
                                    3L * MAX_VARINT_BYTES)),
                    maxSectionBytes);
            requireProspectiveSection("BY_MATCH_KEY",
                    checkedProspectiveAdd(5,
                            checkedProspectiveMultiply(matchMemberships,
                                    MAX_MATCH_KEY_BYTES
                                            + 3L * MAX_VARINT_BYTES)),
                    maxSectionBytes);
            requireProspectiveSection("REVERSE_DEPENDENCIES",
                    checkedProspectiveAdd(5,
                            checkedProspectiveMultiply(reverseMemberships,
                                    MAX_MATCH_KEY_BYTES + MAX_VARINT_BYTES
                                            + MAX_PATH_BYTES)),
                    maxSectionBytes);

            Map<ReferenceMatchKey, MatchIds> immutableMatch =
                    new TreeMap<>(ReferenceMatchKey.CANONICAL_ORDER);
            while (!match.isEmpty()) {
                var entry = match.pollFirstEntry();
                immutableMatch.put(entry.getKey(),
                        new MatchIds(entry.getValue().definitions,
                                entry.getValue().locations));
            }
            Map<ReferenceMatchKey, List<IndexPathRef>> immutableReverse =
                    new TreeMap<>(ReferenceMatchKey.CANONICAL_ORDER);
            while (!reverse.isEmpty()) {
                var entry = reverse.pollFirstEntry();
                immutableReverse.put(entry.getKey(),
                        List.copyOf(entry.getValue()));
            }
            AuxiliaryIndexes indexes = new AuxiliaryIndexes(
                    Collections.unmodifiableMap(immutableMatch),
                    Collections.unmodifiableMap(completion.toCanonicalMap()),
                    Collections.unmodifiableMap(immutableReverse));

            strings.retainedBytes = checkedProspectiveAdd(
                    strings.retainedBytes, auxiliaryBytes);
            ProspectiveWork prospective =
                    strings.finish(completionMemberships, maxSectionBytes);
            StringTable stringTable = StringTable.build(prospective, indexes);
            validateAuxiliaryWriteLimits(indexes, stringTable);
            return new StreamingPrepared(manifest, indexes, stringTable,
                    files, definitions, locations, unresolved);
        }

        private void addAuxiliaryBytes(long bytes) {
            auxiliaryBytes = checkedProspectiveAdd(auxiliaryBytes, bytes);
            requireProspectiveBuildBytes(auxiliaryBytes);
        }
    }

    record CanonicalData(
            ProjectIndexManifest manifest,
            List<ProjectFileStamp> stamps,
            List<FileContribution> files,
            List<PackedDefinition> definitions,
            List<PackedLocation> locations,
            List<PathRange> ranges) {
    }

    record ManifestCore(
            int formatMajor,
            int formatMinor,
            int parserAbi,
            String coreVersion,
            String uiVersion,
            DatabaseType databaseType,
            String projectIdentity,
            byte[] configSha256,
            long generation) {
    }

    record PathRange(
            IndexPathRef path,
            int definitionStart,
            int definitionCount,
            int locationStart,
            int locationCount) {
    }

    record MatchIds(List<Integer> definitionIds, List<Integer> locationIds) {
        MatchIds {
            definitionIds = List.copyOf(definitionIds);
            locationIds = List.copyOf(locationIds);
        }
    }

    private record MatchTuple(long first, long second) {
    }

    record UnresolvedRecord(IndexPathRef path, Set<ReferenceMatchKey> candidates, boolean any) {
        static final UnresolvedRecord EMPTY = new UnresolvedRecord(null, Set.of(), false);

        UnresolvedRecord {
            candidates = Set.copyOf(candidates);
        }
    }

    record AuxiliaryIndexes(
            Map<ReferenceMatchKey, MatchIds> byMatchKey,
            Map<String, List<Integer>> completion,
            Map<ReferenceMatchKey, List<IndexPathRef>> reverseDependencies) {

        static AuxiliaryIndexes build(CanonicalData data) {
            List<PackedDefinition> definitions = data.definitions();
            List<PackedLocation> locations = data.locations();
            TreeMap<ReferenceMatchKey, MutableMatchIds> match = new TreeMap<>(
                    ReferenceMatchKey.CANONICAL_ORDER);
            for (int i = 0; i < definitions.size(); i++) {
                PackedLocation object = definitions.get(i).object();
                if (object.reference() != null && object.reference().type() != null) {
                    match.computeIfAbsent(ReferenceMatchKey.from(object), ignored -> new MutableMatchIds())
                            .definitions.add(i);
                }
            }
            TreeMap<ReferenceMatchKey, Set<IndexPathRef>> reverse = new TreeMap<>(
                    ReferenceMatchKey.CANONICAL_ORDER);
            int rangeIndex = 0;
            PathRange range = data.ranges().isEmpty() ? null : data.ranges().getFirst();
            for (int i = 0; i < locations.size(); i++) {
                while (range != null
                        && i >= range.locationStart() + range.locationCount()) {
                    range = ++rangeIndex < data.ranges().size()
                            ? data.ranges().get(rangeIndex) : null;
                }
                PackedLocation location = locations.get(i);
                if (location.reference() == null || location.reference().type() == null) {
                    continue;
                }
                ReferenceMatchKey key = ReferenceMatchKey.from(location);
                match.computeIfAbsent(key, ignored -> new MutableMatchIds()).locations.add(i);
                if (location.locationType() != ObjectLocation.LocationType.DEFINITION) {
                    if (range == null || i < range.locationStart()) {
                        throw new IllegalStateException(
                                "Location is not covered by a canonical path range");
                    }
                    reverse.computeIfAbsent(key, ignored -> new TreeSet<>(PATH_ORDER))
                            .add(range.path());
                }
            }
            Map<ReferenceMatchKey, MatchIds> immutableMatch = new TreeMap<>(
                    ReferenceMatchKey.CANONICAL_ORDER);
            while (!match.isEmpty()) {
                var entry = match.pollFirstEntry();
                immutableMatch.put(entry.getKey(),
                        new MatchIds(entry.getValue().definitions, entry.getValue().locations));
            }
            Map<ReferenceMatchKey, List<IndexPathRef>> immutableReverse = new TreeMap<>(
                    ReferenceMatchKey.CANONICAL_ORDER);
            while (!reverse.isEmpty()) {
                var entry = reverse.pollFirstEntry();
                immutableReverse.put(entry.getKey(), List.copyOf(entry.getValue()));
            }

            TrigramAccumulator completionAccumulator = new TrigramAccumulator();
            for (int i = 0; i < definitions.size(); i++) {
                String name = completionName(definitions.get(i));
                if (name == null || name.isEmpty()) {
                    continue;
                }
                completionAccumulator.add(name.toUpperCase(Locale.ROOT), i);
            }
            Map<String, List<Integer>> completion = completionAccumulator.toCanonicalMap();
            return new AuxiliaryIndexes(Collections.unmodifiableMap(immutableMatch),
                    Collections.unmodifiableMap(completion),
                    Collections.unmodifiableMap(immutableReverse));
        }
    }

    private static final class PackedTrigramSet {
        private static final int INITIAL_CAPACITY = 16;

        private final ProjectIndexWriteContext context;
        private long[] keys = new long[INITIAL_CAPACITY];
        private int size;
        private int resizeAt = INITIAL_CAPACITY * 3 / 5;

        private PackedTrigramSet(
                ProjectIndexWriteContext context) {
            this.context = context;
        }

        void add(long key) throws IOException {
            if (key == 0) {
                throw new IllegalArgumentException(
                        "A packed completion trigram must not be empty");
            }
            if (size + 1 > resizeAt) {
                resize();
            }
            int slot = slot(key, keys);
            if (keys[slot] == 0) {
                if (size >= MAX_STRING_COUNT) {
                    throw new IllegalArgumentException(
                            "Project index completion string count exceeds the format limit");
                }
                keys[slot] = key;
                size++;
            }
        }

        void addTo(TreeSet<String> target) throws IOException {
            long operations = 0;
            for (long key : keys) {
                pollCancellation(context, operations++);
                if (key != 0) {
                    target.add(TrigramAccumulator.unpack(key));
                }
            }
        }

        private void resize() throws IOException {
            int newCapacity;
            try {
                newCapacity = Math.multiplyExact(keys.length, 2);
            } catch (ArithmeticException ex) {
                throw new IllegalArgumentException(
                        "Project index completion string set exceeds the format limit",
                        ex);
            }
            long[] resized = new long[newCapacity];
            long operations = 0;
            for (long key : keys) {
                pollCancellation(context, operations++);
                if (key != 0) {
                    resized[slot(key, resized)] = key;
                }
            }
            keys = resized;
            resizeAt = newCapacity * 3 / 5;
        }

        private int slot(long key, long[] table)
                throws IOException {
            long mixed = key;
            mixed ^= mixed >>> 33;
            mixed *= 0xff51afd7ed558ccdL;
            mixed ^= mixed >>> 33;
            mixed *= 0xc4ceb9fe1a85ec53L;
            mixed ^= mixed >>> 33;
            int slot = (int) mixed & (table.length - 1);
            long probes = 0;
            while (table[slot] != 0
                    && table[slot] != key) {
                pollCancellation(context, probes++);
                slot = slot + 1 & (table.length - 1);
            }
            return slot;
        }
    }

    private static final class TrigramAccumulator {
        private static final int INITIAL_CAPACITY = 16;

        private long[] keys = new long[INITIAL_CAPACITY];
        private IntIds[] values = new IntIds[INITIAL_CAPACITY];
        private int size;
        private int resizeAt = INITIAL_CAPACITY * 3 / 5;

        void add(String value, int definitionId) {
            if (value.length() <= 3) {
                put(pack(value, 0, value.length()), definitionId);
                return;
            }

            long raw = (long) value.charAt(0) << 32
                    | (long) value.charAt(1) << 16
                    | value.charAt(2);
            put(3L << 48 | raw, definitionId);
            for (int i = 3; i < value.length(); i++) {
                raw = (raw & 0xffffffffL) << 16 | value.charAt(i);
                put(3L << 48 | raw, definitionId);
            }
        }

        Map<String, List<Integer>> toCanonicalMap() {
            Map<String, List<Integer>> result = new TreeMap<>();
            for (int i = 0; i < keys.length; i++) {
                if (keys[i] != 0) {
                    result.put(unpack(keys[i]), values[i].toList());
                }
            }
            return result;
        }

        private void put(long key, int definitionId) {
            if (size + 1 > resizeAt) {
                resize();
            }
            int slot = slot(key, keys);
            if (keys[slot] == 0) {
                keys[slot] = key;
                values[slot] = new IntIds();
                size++;
            }
            values[slot].add(definitionId);
        }

        private void resize() {
            int newCapacity;
            try {
                newCapacity = Math.multiplyExact(keys.length, 2);
            } catch (ArithmeticException ex) {
                throw new IllegalArgumentException(
                        "Project index completion table exceeds the format limit", ex);
            }
            long[] newKeys = new long[newCapacity];
            IntIds[] newValues = new IntIds[newCapacity];
            for (int i = 0; i < keys.length; i++) {
                long key = keys[i];
                if (key != 0) {
                    int slot = slot(key, newKeys);
                    newKeys[slot] = key;
                    newValues[slot] = values[i];
                }
            }
            keys = newKeys;
            values = newValues;
            resizeAt = newCapacity * 3 / 5;
        }

        private static int slot(long key, long[] table) {
            long mixed = key;
            mixed ^= mixed >>> 33;
            mixed *= 0xff51afd7ed558ccdL;
            mixed ^= mixed >>> 33;
            mixed *= 0xc4ceb9fe1a85ec53L;
            mixed ^= mixed >>> 33;
            int slot = (int) mixed & (table.length - 1);
            while (table[slot] != 0 && table[slot] != key) {
                slot = slot + 1 & (table.length - 1);
            }
            return slot;
        }

        private static long pack(String value, int start, int width) {
            long result = 0;
            for (int i = 0; i < width; i++) {
                result = result << 16 | value.charAt(start + i);
            }
            return (long) width << 48 | result;
        }

        private static String unpack(long key) {
            int width = (int) (key >>> 48 & 3);
            char[] result = new char[width];
            long raw = key & 0x0000ffffffffffffL;
            for (int i = width - 1; i >= 0; i--) {
                result[i] = (char) raw;
                raw >>>= 16;
            }
            return new String(result);
        }
    }

    private static final class IntIds {
        private int[] values = new int[4];
        private int size;
        private int lastDefinitionId = -1;

        void add(int definitionId) {
            if (lastDefinitionId == definitionId) {
                return;
            }
            if (size == values.length) {
                values = Arrays.copyOf(values, Math.multiplyExact(values.length, 2));
            }
            values[size++] = definitionId;
            lastDefinitionId = definitionId;
        }

        List<Integer> toList() {
            List<Integer> result = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                result.add(values[i]);
            }
            return List.copyOf(result);
        }
    }

    private static final class MutableMatchIds {
        private final List<Integer> definitions = new ArrayList<>();
        private final List<Integer> locations = new ArrayList<>();
    }

    static final class StringTable {
        private final List<String> values;
        private final Map<String, Integer> ids;
        private final BitSet usedIds;
        private final LazyStrings lazy;

        private StringTable(List<String> values, boolean buildLookup, BitSet usedIds) {
            this.values = buildLookup ? List.copyOf(values) : values;
            this.usedIds = usedIds;
            lazy = null;
            if (buildLookup) {
                ids = new HashMap<>(values.size() * 2);
                for (int i = 0; i < values.size(); i++) {
                    ids.put(values.get(i), i + 1);
                }
            } else {
                ids = null;
            }
        }

        private StringTable(List<String> values,
                ProjectIndexWriteContext context) throws IOException {
            this.values = Collections.unmodifiableList(values);
            usedIds = null;
            lazy = null;
            ids = new HashMap<>(values.size() * 2);
            long operations = 0;
            for (int i = 0; i < values.size(); i++) {
                pollCancellation(context, operations++);
                ids.put(values.get(i), i + 1);
            }
        }

        private StringTable(LazyStrings lazy) {
            values = null;
            ids = null;
            usedIds = null;
            this.lazy = Objects.requireNonNull(lazy, "lazy");
        }

        static StringTable decoded(List<String> values, AllocationBudget budget)
                throws ProjectIndexFormatException {
            budget.claim(32 + bitSetBytes(values.size()),
                    "STRINGS table wrapper and coverage bit set");
            return new StringTable(values, false, new BitSet(values.size()));
        }

        static StringTable build(ProspectiveWork prospective, AuxiliaryIndexes indexes) {
            TreeSet<String> values = prospective.baseStrings();
            indexes.completion().keySet().forEach(values::add);
            return new StringTable(new ArrayList<>(values), true, null);
        }

        static StringTable build(ProspectiveWork prospective,
                PackedTrigramSet completionStrings,
                ProjectIndexWriteContext context) throws IOException {
            TreeSet<String> values = prospective.baseStrings();
            completionStrings.addTo(values);
            List<String> canonical = new ArrayList<>(values.size());
            long operations = 0;
            for (String value : values) {
                pollCancellation(context, operations++);
                canonical.add(value);
            }
            return new StringTable(canonical, context);
        }

        static StringTable lazy(LazyStrings strings) {
            return new StringTable(strings);
        }

        int id(String value) {
            if (value == null) {
                return 0;
            }
            Integer id = ids.get(value);
            if (id == null) {
                throw new IllegalStateException("String is missing from the canonical table: " + value);
            }
            return id;
        }

        int idPacked(long key, int length) {
            int low = 0;
            int high = values.size() - 1;
            while (low <= high) {
                int middle = (low + high) >>> 1;
                int comparison = compareStringToPacked(
                        values.get(middle), key, length);
                if (comparison < 0) {
                    low = middle + 1;
                } else if (comparison > 0) {
                    high = middle - 1;
                } else {
                    return middle + 1;
                }
            }
            throw new IllegalStateException(
                    "Completion trigram is missing from the canonical string table");
        }

        int decodedId(String value) throws ProjectIndexFormatException {
            if (value == null) {
                return 0;
            }
            if (lazy != null) {
                return lazy.find(value);
            }
            int index = Collections.binarySearch(values, value);
            if (index < 0) {
                throw format("A decoded semantic string is missing from STRINGS: " + value);
            }
            return index + 1;
        }

        int decodedId(long key, int length, String label)
                throws ProjectIndexFormatException {
            if (lazy != null) {
                return lazy.findPacked(key, length, label);
            }
            int low = 0;
            int high = values.size() - 1;
            while (low <= high) {
                int middle = (low + high) >>> 1;
                int comparison = compareStringToPacked(values.get(middle), key, length);
                if (comparison < 0) {
                    low = middle + 1;
                } else if (comparison > 0) {
                    high = middle - 1;
                } else {
                    return middle + 1;
                }
            }
            throw format("A decoded " + label + " is missing from STRINGS");
        }

        String value(int id) throws ProjectIndexFormatException {
            if (id == 0) {
                return null;
            }
            int size = lazy == null ? values.size() : lazy.size();
            if (id < 0 || id > size) {
                throw format("Invalid string id: " + id);
            }
            if (lazy != null) {
                return lazy.value(id);
            }
            if (usedIds != null) {
                usedIds.set(id - 1);
            }
            return values.get(id - 1);
        }

        void requireExactDecodedCoverage() throws ProjectIndexFormatException {
            if (lazy == null && usedIds != null && usedIds.nextClearBit(0) < values.size()) {
                throw format("STRINGS contains an unused record");
            }
        }

        List<String> values() {
            if (lazy != null) {
                throw new IllegalStateException("A lazy string table cannot be enumerated");
            }
            return values;
        }

        private static int compareStringToPacked(String candidate, long key, int length) {
            int shared = Math.min(candidate.length(), length);
            for (int i = 0; i < shared; i++) {
                int shift = (length - i - 1) * Character.SIZE;
                char packed = (char) (key >>> shift);
                int comparison = Character.compare(candidate.charAt(i), packed);
                if (comparison != 0) {
                    return comparison;
                }
            }
            return Integer.compare(candidate.length(), length);
        }

    }

    interface LazyStrings {
        int size();

        String value(int id) throws ProjectIndexFormatException;

        int find(String value) throws ProjectIndexFormatException;

        int findPacked(long key, int length, String label)
                throws ProjectIndexFormatException;
    }

    record ProspectiveWork(TreeSet<String> baseStrings) {
    }

    private static final class ProspectiveStrings {
        private final TreeSet<String> values = new TreeSet<>();
        private final boolean enforceBuildGuard;
        private long retainedBytes;
        private long encodedRecordBytes;

        ProspectiveStrings(long auxiliaryBytes) {
            this(auxiliaryBytes, true);
        }

        ProspectiveStrings(long auxiliaryBytes,
                boolean enforceBuildGuard) {
            retainedBytes = auxiliaryBytes;
            this.enforceBuildGuard = enforceBuildGuard;
        }

        void add(String value) {
            if (value == null || values.contains(value)) {
                return;
            }
            if (values.size() >= MAX_STRING_COUNT) {
                throw new IllegalArgumentException(
                        "Project index prospective string count exceeds the format limit");
            }
            int utf8Bytes = encodedStringSize(value);
            retainedBytes = checkedProspectiveAdd(retainedBytes,
                    PROSPECTIVE_STRING_ENTRY_BYTES);
            if (enforceBuildGuard) {
                requireProspectiveBuildBytes(retainedBytes);
            }
            encodedRecordBytes = checkedProspectiveAdd(encodedRecordBytes,
                    varIntSize(utf8Bytes) + (long) utf8Bytes);
            values.add(value);
        }

        void addDefinition(PackedDefinition definition) {
            addLocation(definition.object());
            add(definition.bareName());
            add(definition.comment());
            add(definition.returns());
            add(definition.operatorLeft());
            add(definition.operatorRight());
            add(definition.operatorReturns());
            add(definition.castSource());
            add(definition.castTarget());
            definition.arguments().forEach(this::addArgument);
            definition.orderBy().forEach(this::addArgument);
            definition.returnColumns().forEach(this::addNameType);
            definition.relationColumns().forEach(this::addNameType);
            definition.compositeAttributes().forEach(this::addNameType);
            definition.constraintColumns().forEach(this::add);
        }

        void addLocation(PackedLocation location) {
            add(location.relativePath());
            if (location.reference() != null) {
                add(location.reference().schema());
                add(location.reference().table());
                add(location.reference().column());
            }
            add(location.action());
            add(location.alias());
        }

        void addMatchKey(ReferenceMatchKey key) {
            add(key.schema());
            add(key.table());
            add(key.column());
            add(key.alias());
        }

        ProspectiveWork finish(long completionStrings, int maxSectionBytes) {
            long prospectiveCount = checkedProspectiveAdd(values.size(), completionStrings);
            if (prospectiveCount > MAX_STRING_COUNT) {
                throw new IllegalArgumentException(
                        "Project index prospective string count exceeds the format limit");
            }
            retainedBytes = checkedProspectiveAdd(retainedBytes,
                    checkedProspectiveMultiply(completionStrings,
                            PROSPECTIVE_STRING_ENTRY_BYTES));
            if (enforceBuildGuard) {
                requireProspectiveBuildBytes(retainedBytes);
            }

            long completionRecordBytes = checkedProspectiveMultiply(completionStrings,
                    varIntSize(MAX_TRIGRAM_UTF8_BYTES) + (long) MAX_TRIGRAM_UTF8_BYTES);
            long blockOverhead = checkedProspectiveMultiply(prospectiveCount,
                    MAX_VARINT_BYTES);
            long encodedBytes = checkedProspectiveAdd(10,
                    checkedProspectiveAdd(encodedRecordBytes,
                            checkedProspectiveAdd(completionRecordBytes, blockOverhead)));
            requireProspectiveSection("STRINGS", encodedBytes, maxSectionBytes);
            return new ProspectiveWork(values);
        }

        ProspectiveWork finishBounded() {
            return new ProspectiveWork(values);
        }

        private void addArgument(PackedArgument argument) {
            add(argument.name());
            add(argument.dataType());
            add(argument.defaultExpression());
        }

        private void addNameType(NameType value) {
            add(value.name());
            add(value.type());
        }
    }

    @FunctionalInterface
    private interface RecordEncoder<T> {
        void write(BinaryOutput output, T value) throws IOException;
    }

    @FunctionalInterface
    private interface RecordDecoder<T> {
        T decode(BinaryReader input) throws ProjectIndexFormatException;
    }

    @FunctionalInterface
    interface LongMembershipConsumer {
        void accept(long value) throws IOException;
    }
}
