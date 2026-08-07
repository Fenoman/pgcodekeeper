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

import static ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexFormat.MAX_SECTION_BYTES;
import static ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexFormat.MAX_RECORD_COUNT;
import static ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexFormat.arrayBytes;
import static ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexRecords.*;

import java.io.IOException;
import java.util.Objects;

import org.pgcodekeeper.core.database.api.schema.ObjectLocation;

import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexFormat.ChannelWriter;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexSpillStore.Cursor;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexSpillStore.GroupCounts;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexSpillStore.Metrics;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexSpillStore.SectionRuns;

final class ProjectIndexBoundedAuxiliaryWriter {

    private static final int CANCELLATION_POLL_MASK = 1_023;

    private ProjectIndexBoundedAuxiliaryWriter() {
    }

    static Prepared prepare(ProjectIndexContributionSource source,
            ProjectIndexWriteContext context) throws IOException {
        ProjectIndexRecords.BoundedPrepared prepared =
                ProjectIndexRecords.prepareBounded(source, context,
                        MAX_SECTION_BYTES);
        return new Prepared(prepared.manifest(), prepared.strings(),
                prepared.counts(),
                prepared.sectionMembershipCounts());
    }

    static Metrics writeSection(SectionType type, ChannelWriter output,
            ProjectIndexContributionSource source, Prepared prepared,
            ProjectIndexWriteContext context) throws IOException {
        return writeSection(type, output, source, prepared,
                context, null, 0);
    }

    static Metrics writeSection(SectionType type, ChannelWriter output,
            ProjectIndexContributionSource source, Prepared prepared,
            ProjectIndexWriteContext context,
            ProjectIndexLocatorCapture capture,
            long payloadOffset) throws IOException {
        SectionType checkedType = requireAuxiliary(type);
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(context, "context");
        context.requireNotCancelled();

        long expectedRows = prepared.membershipCount(checkedType);
        int rowWidth = checkedType == SectionType.COMPLETION_TRIGRAMS
                ? 1 : 3;
        int capacity = tupleCapacity(expectedRows, rowWidth,
                context.tupleBudgetBytes());
        try (var store = new ProjectIndexSpillStore(
                context.stateDirectory(), context.publicationId(),
                context.cancelled());
                SectionRuns runs = store.section(checkedType)) {
            var buffer = new ProjectIndexTupleBuffer(rowWidth, capacity);
            var appender = new TupleAppender(
                    runs, buffer, capacity, context);
            ProjectIndexContributionSource.PassCounts actual = switch (checkedType) {
            case BY_MATCH_KEY -> populateByMatch(
                    source, prepared, context, appender);
            case COMPLETION_TRIGRAMS -> populateCompletion(
                    source, prepared, context, appender);
            case REVERSE_DEPENDENCIES -> populateReverse(
                    source, prepared, context, appender);
            default -> throw new AssertionError(checkedType);
            };
            requireStable(prepared, actual, checkedType);
            runs.finish(buffer);

            try (GroupCounts counts = runs.openGroupCounts()) {
                int groups = switch (checkedType) {
                case BY_MATCH_KEY -> countByMatch(runs, counts);
                case COMPLETION_TRIGRAMS ->
                    countCompletion(runs, counts);
                case REVERSE_DEPENDENCIES ->
                    countReverse(runs, counts);
                default -> throw new AssertionError(checkedType);
                };
                counts.finish();
                ProjectIndexLocatorCapture.Sparse locator =
                        capture == null ? null
                                : capture.beginSparse(checkedType,
                                        groups, payloadOffset);
                output.writeVarInt(groups);
                switch (checkedType) {
                case BY_MATCH_KEY ->
                    writeByMatch(output, runs, counts, groups,
                            locator);
                case COMPLETION_TRIGRAMS ->
                    writeCompletion(output, runs, counts, groups,
                            locator);
                case REVERSE_DEPENDENCIES ->
                    writeReverse(output, runs, counts, groups,
                            locator);
                default -> throw new AssertionError(checkedType);
                }
            }
            Metrics metrics = store.metrics();
            return metrics;
        }
    }

    private static ProjectIndexContributionSource.PassCounts populateByMatch(
            ProjectIndexContributionSource source, Prepared prepared,
            ProjectIndexWriteContext context, TupleAppender appender)
            throws IOException {
        long[] records = {0};
        return source.replay(context.cancelled(),
                (fileId, definitionStart, locationStart, file) -> {
                    for (int index = 0;
                            index < file.definitions().size(); index++) {
                        pollRecord(context, ++records[0]);
                        PackedLocation object =
                                file.definitions().get(index).object();
                        if (hasTypedReference(object)) {
                            appender.add(matchKeyWord1(
                                    object, prepared.strings()),
                                    matchKeyWord2(
                                            object, prepared.strings()),
                                    matchMembership(0,
                                            definitionStart + index));
                        }
                    }
                    for (int index = 0;
                            index < file.locations().size(); index++) {
                        pollRecord(context, ++records[0]);
                        PackedLocation location =
                                file.locations().get(index);
                        if (hasTypedReference(location)) {
                            appender.add(matchKeyWord1(
                                    location, prepared.strings()),
                                    matchKeyWord2(
                                            location, prepared.strings()),
                                    matchMembership(1,
                                            locationStart + index));
                        }
                    }
                });
    }

    private static ProjectIndexContributionSource.PassCounts populateCompletion(
            ProjectIndexContributionSource source, Prepared prepared,
            ProjectIndexWriteContext context, TupleAppender appender)
            throws IOException {
        long[] records = {0};
        return source.replay(context.cancelled(),
                (fileId, definitionStart, locationStart, file) -> {
                    for (int index = 0;
                            index < file.definitions().size(); index++) {
                        pollRecord(context, ++records[0]);
                        forEachCompletionMembership(
                                file.definitions().get(index),
                                definitionStart + index,
                                prepared.strings(), appender::add);
                    }
                });
    }

    private static ProjectIndexContributionSource.PassCounts populateReverse(
            ProjectIndexContributionSource source, Prepared prepared,
            ProjectIndexWriteContext context, TupleAppender appender)
            throws IOException {
        long[] records = {0};
        return source.replay(context.cancelled(),
                (fileId, definitionStart, locationStart, file) -> {
                    for (PackedLocation location : file.locations()) {
                        pollRecord(context, ++records[0]);
                        if (!hasTypedReference(location)
                                || location.locationType()
                                        == ObjectLocation.LocationType.DEFINITION) {
                            continue;
                        }
                        long second = withPathOrigin(
                                matchKeyWord2(location, prepared.strings()),
                                file.path().origin().ordinal());
                        appender.add(matchKeyWord1(
                                location, prepared.strings()), second,
                                Integer.toUnsignedLong(prepared.strings()
                                        .id(file.path().relativePath())));
                    }
                });
    }

    private static void pollRecord(ProjectIndexWriteContext context,
            long records)
            throws ProjectIndexStore.WriteCancelledException {
        if ((records & CANCELLATION_POLL_MASK) == 0) {
            context.requireNotCancelled();
        }
    }

    private static int countByMatch(SectionRuns runs,
            GroupCounts counts) throws IOException {
        int groups = 0;
        boolean hasGroup = false;
        long previousFirst = 0;
        long previousSecond = 0;
        int definitions = 0;
        int locations = 0;
        try (Cursor cursor = runs.openCursor()) {
            while (cursor.next()) {
                long first = cursor.word(0);
                long second = cursor.word(1);
                if (!hasGroup || first != previousFirst
                        || second != previousSecond) {
                    if (hasGroup) {
                        counts.add(definitions, locations);
                    }
                    groups = incrementCount("match key", groups);
                    hasGroup = true;
                    previousFirst = first;
                    previousSecond = second;
                    definitions = 0;
                    locations = 0;
                }
                int kind = matchMembershipKind(cursor.word(2));
                if (kind == 0) {
                    definitions = incrementCount(
                            "definition id", definitions);
                } else if (kind == 1) {
                    locations = incrementCount(
                            "location id", locations);
                } else {
                    throw new ProjectIndexFormatException(
                            "Project index match membership kind is invalid");
                }
            }
        }
        if (hasGroup) {
            counts.add(definitions, locations);
        }
        return groups;
    }

    private static int countCompletion(SectionRuns runs,
            GroupCounts counts) throws IOException {
        int groups = 0;
        int members = 0;
        int previousString = -1;
        try (Cursor cursor = runs.openCursor()) {
            while (cursor.next()) {
                int stringId = completionStringId(cursor.word(0));
                if (stringId != previousString) {
                    if (previousString >= 0) {
                        counts.add(members);
                    }
                    groups = incrementCount(
                            "completion trigram", groups);
                    members = 0;
                    previousString = stringId;
                }
                members = incrementCount(
                        "completion id", members);
            }
        }
        if (previousString >= 0) {
            counts.add(members);
        }
        return groups;
    }

    private static int countReverse(SectionRuns runs,
            GroupCounts counts) throws IOException {
        int groups = 0;
        int paths = 0;
        boolean hasGroup = false;
        long previousFirst = 0;
        long previousSecond = 0;
        try (Cursor cursor = runs.openCursor()) {
            while (cursor.next()) {
                long first = cursor.word(0);
                long second = withoutPathOrigin(cursor.word(1));
                if (!hasGroup || first != previousFirst
                        || second != previousSecond) {
                    if (hasGroup) {
                        counts.add(paths);
                    }
                    groups = incrementCount(
                            "reverse dependency key", groups);
                    paths = 0;
                    hasGroup = true;
                    previousFirst = first;
                    previousSecond = second;
                }
                paths = incrementCount(
                        "dependent path", paths);
            }
        }
        if (hasGroup) {
            counts.add(paths);
        }
        return groups;
    }

    private static void writeByMatch(ChannelWriter output,
            SectionRuns runs, GroupCounts counts, int groups,
            ProjectIndexLocatorCapture.Sparse locator)
            throws IOException {
        try (Cursor cursor = runs.openCursor()) {
            boolean hasRow = cursor.next();
            for (int group = 0; group < groups; group++) {
                requireRow(hasRow, "BY_MATCH_KEY");
                long first = cursor.word(0);
                long second = cursor.word(1);
                requireCountRow(counts, "BY_MATCH_KEY");
                int definitions = counts.value(0);
                int locations = counts.value(1);
                if (locator != null) {
                    locator.match(output.size(), first, second);
                }
                writeMatchKey(output, first, second);
                output.writeVarInt(definitions);
                int previous = -1;
                for (int index = 0; index < definitions; index++) {
                    requireMatchRow(cursor, hasRow, first, second, 0);
                    int id = matchMembershipId(cursor.word(2));
                    output.writeVarInt(id - previous - 1);
                    previous = id;
                    hasRow = cursor.next();
                }
                output.writeVarInt(locations);
                previous = -1;
                for (int index = 0; index < locations; index++) {
                    requireMatchRow(cursor, hasRow, first, second, 1);
                    int id = matchMembershipId(cursor.word(2));
                    output.writeVarInt(id - previous - 1);
                    previous = id;
                    hasRow = cursor.next();
                }
            }
            requireEnd(hasRow, cursor, counts, "BY_MATCH_KEY");
        }
    }

    private static void writeCompletion(ChannelWriter output,
            SectionRuns runs, GroupCounts counts, int groups,
            ProjectIndexLocatorCapture.Sparse locator)
            throws IOException {
        try (Cursor cursor = runs.openCursor()) {
            boolean hasRow = cursor.next();
            for (int group = 0; group < groups; group++) {
                requireRow(hasRow, "COMPLETION_TRIGRAMS");
                int stringId = completionStringId(cursor.word(0));
                requireCountRow(counts, "COMPLETION_TRIGRAMS");
                int members = counts.value(0);
                if (locator != null) {
                    locator.string(output.size(), stringId);
                }
                output.writeVarInt(stringId);
                output.writeVarInt(members);
                int previous = -1;
                for (int index = 0; index < members; index++) {
                    requireRow(hasRow, "COMPLETION_TRIGRAMS");
                    if (completionStringId(cursor.word(0))
                            != stringId) {
                        throw changed("COMPLETION_TRIGRAMS");
                    }
                    int id = completionDefinitionId(cursor.word(0));
                    output.writeVarInt(id - previous - 1);
                    previous = id;
                    hasRow = cursor.next();
                }
            }
            requireEnd(hasRow, cursor, counts,
                    "COMPLETION_TRIGRAMS");
        }
    }

    private static void writeReverse(ChannelWriter output,
            SectionRuns runs, GroupCounts counts, int groups,
            ProjectIndexLocatorCapture.Sparse locator)
            throws IOException {
        try (Cursor cursor = runs.openCursor()) {
            boolean hasRow = cursor.next();
            for (int group = 0; group < groups; group++) {
                requireRow(hasRow, "REVERSE_DEPENDENCIES");
                long first = cursor.word(0);
                long second = withoutPathOrigin(cursor.word(1));
                requireCountRow(counts, "REVERSE_DEPENDENCIES");
                int paths = counts.value(0);
                if (locator != null) {
                    locator.match(output.size(), first, second);
                }
                writeMatchKey(output, first, second);
                output.writeVarInt(paths);
                for (int index = 0; index < paths; index++) {
                    requireRow(hasRow, "REVERSE_DEPENDENCIES");
                    if (cursor.word(0) != first
                            || withoutPathOrigin(cursor.word(1))
                                    != second) {
                        throw changed("REVERSE_DEPENDENCIES");
                    }
                    output.writeVarInt(pathOrigin(cursor.word(1)));
                    output.writeVarInt(Math.toIntExact(cursor.word(2)));
                    hasRow = cursor.next();
                }
            }
            requireEnd(hasRow, cursor, counts,
                    "REVERSE_DEPENDENCIES");
        }
    }

    private static void requireMatchRow(Cursor cursor, boolean hasRow,
            long first, long second, int kind)
            throws ProjectIndexFormatException {
        requireRow(hasRow, "BY_MATCH_KEY");
        if (cursor.word(0) != first || cursor.word(1) != second
                || matchMembershipKind(cursor.word(2)) != kind) {
            throw changed("BY_MATCH_KEY");
        }
    }

    private static void requireCountRow(GroupCounts counts,
            String section) throws IOException {
        if (!counts.next()) {
            throw changed(section);
        }
    }

    private static void requireEnd(boolean hasRow, Cursor cursor,
            GroupCounts counts, String section) throws IOException {
        if (hasRow || counts.next()) {
            throw changed(section);
        }
    }

    private static void requireRow(boolean hasRow, String section)
            throws ProjectIndexFormatException {
        if (!hasRow) {
            throw changed(section);
        }
    }

    private static ProjectIndexFormatException changed(String section) {
        return new ProjectIndexFormatException(
                section + " memberships changed between merge passes");
    }

    private static void requireStable(Prepared prepared,
            ProjectIndexContributionSource.PassCounts actual,
            SectionType type) {
        if (!prepared.counts().equals(actual)) {
            throw new IllegalStateException(
                    type + " contribution counts changed between passes");
        }
    }

    private static int tupleCapacity(long rows, int rowWidth,
            long budgetBytes) {
        if (rows == 0) {
            return 0;
        }
        long maximum = Math.min(rows,
                Math.min(Integer.MAX_VALUE / rowWidth,
                        budgetBytes / Long.BYTES / rowWidth));
        long low = 1;
        long high = maximum;
        long capacity = 0;
        while (low <= high) {
            long candidate = (low + high) >>> 1;
            long elements = Math.multiplyExact(
                    candidate, rowWidth);
            if (arrayBytes(elements, Long.BYTES)
                    <= budgetBytes) {
                capacity = candidate;
                low = candidate + 1;
            } else {
                high = candidate - 1;
            }
        }
        if (capacity == 0) {
            throw new IllegalArgumentException(
                    "Project index tuple budget cannot hold one row");
        }
        return Math.toIntExact(capacity);
    }

    private static int incrementCount(String label, int count) {
        if (count >= MAX_RECORD_COUNT) {
            throw new IllegalArgumentException(
                    "Project index " + label
                            + " count exceeds the format limit");
        }
        return count + 1;
    }

    private static SectionType requireAuxiliary(SectionType type) {
        return switch (Objects.requireNonNull(type, "type")) {
        case BY_MATCH_KEY, COMPLETION_TRIGRAMS,
                REVERSE_DEPENDENCIES -> type;
        default -> throw new IllegalArgumentException(
                "Unsupported bounded auxiliary section: " + type);
        };
    }

    record Prepared(ProjectIndexManifest manifest,
            ProjectIndexRecords.StringTable strings,
            ProjectIndexContributionSource.PassCounts counts,
            long[] sectionMembershipCounts) {

        Prepared {
            Objects.requireNonNull(manifest, "manifest");
            Objects.requireNonNull(strings, "strings");
            Objects.requireNonNull(counts, "counts");
            sectionMembershipCounts =
                    sectionMembershipCounts.clone();
        }

        @Override
        public long[] sectionMembershipCounts() {
            return sectionMembershipCounts.clone();
        }

        long membershipCount(SectionType type) {
            return sectionMembershipCounts[type.ordinal()];
        }
    }

    private static final class TupleAppender {

        private final SectionRuns runs;
        private final ProjectIndexTupleBuffer buffer;
        private final int capacity;
        private final ProjectIndexWriteContext context;
        private long rows;

        private TupleAppender(SectionRuns runs,
                ProjectIndexTupleBuffer buffer, int capacity,
                ProjectIndexWriteContext context) {
            this.runs = runs;
            this.buffer = buffer;
            this.capacity = capacity;
            this.context = context;
        }

        private void add(long value) throws IOException {
            prepareAppend();
            buffer.add(value);
            rows = Math.addExact(rows, 1);
        }

        private void add(long first, long second, long third)
                throws IOException {
            prepareAppend();
            buffer.add(first, second, third);
            rows = Math.addExact(rows, 1);
        }

        private long rows() {
            return rows;
        }

        private void prepareAppend() throws IOException {
            if (capacity == 0) {
                throw new IllegalStateException(
                        "Project index auxiliary row count changed between passes");
            }
            if ((rows & CANCELLATION_POLL_MASK) == 0) {
                context.requireNotCancelled();
            }
            if (buffer.rowCount() == capacity) {
                runs.spill(buffer);
            }
        }
    }
}
