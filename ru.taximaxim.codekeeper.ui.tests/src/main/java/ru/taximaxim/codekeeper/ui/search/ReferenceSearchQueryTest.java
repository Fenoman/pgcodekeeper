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
package ru.taximaxim.codekeeper.ui.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.eclipse.search.ui.text.Match;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;

class ReferenceSearchQueryTest {

    @Test
    void groupedReadsMatchLegacyResultsAndOpenEachFileOnce(@TempDir Path dir)
            throws Exception {
        Path first = dir.resolve("first.sql");
        Path second = dir.resolve("second.sql");
        Files.writeString(first, "first-1\nfirst-2\nfirst-3\n");
        Files.writeString(second, "second-1\nsecond-2\n");
        List<ObjectLocation> locations = List.of(
                location(first, 3, 30),
                location(second, 2, 21),
                location(first, 1, 10),
                location(second, 2, 20),
                location(first, 2, 15));
        List<MatchSnapshot> actual = new ArrayList<>();
        Map<String, Integer> opens = new LinkedHashMap<>();
        List<ReadFailure> failures = new ArrayList<>();

        boolean completed = ReferenceSearchQuery.collectMatches(locations,
                () -> false,
                match -> actual.add(MatchSnapshot.from(match)),
                path -> open(path, opens),
                (path, error) -> failures.add(new ReadFailure(path, error)));

        assertTrue(completed);
        assertEquals(legacyMatches(locations), actual);
        assertEquals(Map.of(first.toString(), 1, second.toString(), 1), opens);
        assertTrue(failures.isEmpty());
    }

    @Test
    void cancellationWhileAdvancingStopsBeforeLaterMatchesAndFiles(
            @TempDir Path dir) throws Exception {
        Path first = dir.resolve("first.sql");
        Path second = dir.resolve("second.sql");
        Files.writeString(first, "first-1\nfirst-2\nfirst-3\nfirst-4\n");
        Files.writeString(second, "second-1\n");
        List<ObjectLocation> locations = List.of(
                location(first, 1, 10),
                location(first, 4, 40),
                location(second, 1, 50));
        List<MatchSnapshot> actual = new ArrayList<>();
        Map<String, Integer> opens = new LinkedHashMap<>();
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicInteger firstFileLines = new AtomicInteger();

        boolean completed = ReferenceSearchQuery.collectMatches(locations,
                cancelled::get,
                match -> actual.add(MatchSnapshot.from(match)),
                path -> new BufferedReader(open(path, opens)) {

                    @Override
                    public String readLine() throws IOException {
                        String line = super.readLine();
                        if (path.equals(first.toString())
                                && firstFileLines.incrementAndGet() == 2) {
                            cancelled.set(true);
                        }
                        return line;
                    }
                },
                (path, error) -> {
                    throw new AssertionError(error);
                });

        assertFalse(completed);
        assertEquals(List.of(MatchSnapshot.from(locations.getFirst(), "first-1")),
                actual);
        assertEquals(Map.of(first.toString(), 1), opens);
    }

    @Test
    void cancellationDuringOnlyLineReadStopsBeforeMatchWithOrdinaryClose() {
        ObjectLocation location = location(Path.of("cancelled-read.sql"), 1, 10);
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicBoolean closed = new AtomicBoolean();
        List<MatchSnapshot> actual = new ArrayList<>();
        List<ReadFailure> failures = new ArrayList<>();

        boolean completed = ReferenceSearchQuery.collectMatches(
                List.of(location),
                cancelled::get,
                match -> actual.add(MatchSnapshot.from(match)),
                path -> new BufferedReader(new StringReader("only line")) {

                    @Override
                    public String readLine() throws IOException {
                        String line = super.readLine();
                        cancelled.set(true);
                        return line;
                    }

                    @Override
                    public void close() throws IOException {
                        closed.set(true);
                        super.close();
                    }
                },
                (path, error) -> failures.add(new ReadFailure(path, error)));

        assertFalse(completed);
        assertTrue(closed.get());
        assertTrue(actual.isEmpty());
        assertTrue(failures.isEmpty());
    }

    @Test
    void cancellationDuringOnlyLineReadWinsOverCloseFailure() {
        ObjectLocation location = location(Path.of("close-fails.sql"), 1, 10);
        AtomicBoolean cancelled = new AtomicBoolean();
        List<MatchSnapshot> actual = new ArrayList<>();
        List<ReadFailure> failures = new ArrayList<>();

        boolean completed = ReferenceSearchQuery.collectMatches(
                List.of(location),
                cancelled::get,
                match -> actual.add(MatchSnapshot.from(match)),
                path -> new BufferedReader(new StringReader("first\nsecond")) {

                    @Override
                    public String readLine() throws IOException {
                        String line = super.readLine();
                        cancelled.set(true);
                        return line;
                    }

                    @Override
                    public void close() throws IOException {
                        super.close();
                        throw new IOException("close after cancellation");
                    }
                },
                (path, error) -> failures.add(new ReadFailure(path, error)));

        assertFalse(completed);
        assertTrue(actual.isEmpty());
        assertEquals(1, failures.size());
        assertEquals("close after cancellation",
                failures.getFirst().error().getMessage());
    }

    @Test
    void cancellationByVisitorStopsFlushAfterFirstMatch() {
        Path file = Path.of("visitor-cancel.sql");
        List<ObjectLocation> locations = List.of(
                location(file, 1, 10),
                location(file, 2, 20));
        AtomicBoolean cancelled = new AtomicBoolean();
        List<MatchSnapshot> actual = new ArrayList<>();

        boolean completed = ReferenceSearchQuery.collectMatches(
                locations,
                cancelled::get,
                match -> {
                    actual.add(MatchSnapshot.from(match));
                    cancelled.set(true);
                },
                path -> new BufferedReader(new StringReader("first\nsecond")),
                (path, error) -> {
                    throw new AssertionError(error);
                });

        assertFalse(completed);
        assertEquals(List.of(MatchSnapshot.from(locations.getFirst(), "first")),
                actual);
    }

    @Test
    void completedPrefixIsFlushedBeforeReadingNextLine() {
        Path file = Path.of("large-lines.sql");
        List<ObjectLocation> locations = List.of(
                location(file, 1, 10),
                location(file, 3, 30));
        AtomicInteger delivered = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();

        boolean completed = ReferenceSearchQuery.collectMatches(
                locations,
                () -> false,
                match -> delivered.incrementAndGet(),
                path -> new BufferedReader(
                        new StringReader("first\nsecond\nthird")) {

                    @Override
                    public String readLine() throws IOException {
                        if (reads.incrementAndGet() == 2) {
                            assertEquals(1, delivered.get(),
                                    "Resolved prefix retained until end of file");
                        }
                        return super.readLine();
                    }
                },
                (path, error) -> {
                    throw new AssertionError(error);
                });

        assertTrue(completed);
        assertEquals(2, delivered.get());
    }

    @Test
    void cancellationDuringCloseWinsOverSuccessfulScan() {
        ObjectLocation location = location(Path.of("cancelled-close.sql"), 1, 10);
        AtomicBoolean cancelled = new AtomicBoolean();
        List<MatchSnapshot> actual = new ArrayList<>();

        boolean completed = ReferenceSearchQuery.collectMatches(
                List.of(location),
                cancelled::get,
                match -> actual.add(MatchSnapshot.from(match)),
                path -> new BufferedReader(new StringReader("only line")) {

                    @Override
                    public void close() throws IOException {
                        cancelled.set(true);
                        super.close();
                    }
                },
                (path, error) -> {
                    throw new AssertionError(error);
                });

        assertFalse(completed);
        assertEquals(List.of(MatchSnapshot.from(location, "only line")), actual);
    }

    @Test
    void cancellationStopsGroupingBeforeWalkingLargeInput() {
        AtomicInteger visitedLocations = new AtomicInteger();

        boolean completed = ReferenceSearchQuery.collectMatches(
                countedLocations(50_000, visitedLocations),
                () -> true,
                match -> {
                    throw new AssertionError("No match is expected after cancellation");
                },
                path -> {
                    throw new AssertionError("No file should be opened after cancellation");
                },
                (path, error) -> {
                    throw new AssertionError(error);
                });

        assertFalse(completed);
        assertTrue(visitedLocations.get() <= 1,
                () -> "Grouping visited " + visitedLocations.get()
                        + " locations after cancellation");
    }

    @Test
    void cancellationIsPolledPeriodicallyWhileGrouping() {
        AtomicInteger visitedLocations = new AtomicInteger();

        boolean completed = ReferenceSearchQuery.collectMatches(
                countedLocations(50_000, visitedLocations),
                () -> visitedLocations.get() >= 1_500,
                match -> {
                    throw new AssertionError("No match is expected after cancellation");
                },
                path -> {
                    throw new AssertionError("No file should be opened after cancellation");
                },
                (path, error) -> {
                    throw new AssertionError(error);
                });

        assertFalse(completed);
        assertTrue(visitedLocations.get() >= 1_500);
        assertTrue(visitedLocations.get() <= 4_096,
                () -> "Grouping visited " + visitedLocations.get()
                        + " locations after cancellation");
    }

    @Test
    void cancelledLazySourceIsNotVisited() {
        AtomicInteger visitedLocations = new AtomicInteger();
        AtomicInteger openedFiles = new AtomicInteger();
        AtomicBoolean sourceClosed = new AtomicBoolean();

        boolean completed = ReferenceSearchQuery.collectMatches(
                Stream.generate(() -> location(
                        Path.of("never-visited.sql"), 1, 10))
                        .peek(location -> visitedLocations.incrementAndGet())
                        .limit(50_000)
                        .onClose(() -> sourceClosed.set(true)),
                location -> true,
                () -> true,
                match -> {
                    throw new AssertionError("No match is expected after cancellation");
                },
                path -> {
                    openedFiles.incrementAndGet();
                    throw new AssertionError("No file should be opened after cancellation");
                },
                (path, error) -> {
                    throw new AssertionError(error);
                });

        assertFalse(completed);
        assertEquals(0, visitedLocations.get());
        assertEquals(0, openedFiles.get());
        assertTrue(sourceClosed.get());
    }

    @Test
    void cancellationPollsNonmatchingLazySourceBeforeOpeningFiles() {
        AtomicInteger visitedLocations = new AtomicInteger();
        AtomicInteger openedFiles = new AtomicInteger();

        boolean completed = ReferenceSearchQuery.collectMatches(
                Stream.generate(() -> location(
                        Path.of("never-opened.sql"), 1, 10))
                        .peek(location -> visitedLocations.incrementAndGet())
                        .limit(50_000),
                location -> false,
                () -> visitedLocations.get() >= 1_500,
                match -> {
                    throw new AssertionError("No match is expected after cancellation");
                },
                path -> {
                    openedFiles.incrementAndGet();
                    throw new AssertionError("No file should be opened after cancellation");
                },
                (path, error) -> {
                    throw new AssertionError(error);
                });

        assertFalse(completed);
        assertTrue(visitedLocations.get() >= 1_500);
        assertTrue(visitedLocations.get() <= 2_048,
                () -> "Grouping visited " + visitedLocations.get()
                        + " nonmatching locations after cancellation");
        assertEquals(0, openedFiles.get());
    }

    @Test
    void lazyFilteringPreservesLegacyMatchOrder(@TempDir Path dir)
            throws Exception {
        Path first = dir.resolve("first.sql");
        Path second = dir.resolve("second.sql");
        Files.writeString(first, "first-1\nfirst-2\nfirst-3\n");
        Files.writeString(second, "second-1\nsecond-2\n");
        List<ObjectLocation> source = List.of(
                location(first, 3, 30),
                location(second, 1, 11),
                location(first, 1, 10),
                location(second, 2, 20),
                location(first, 2, 15));
        List<ObjectLocation> expectedLocations = source.stream()
                .filter(location -> location.getOffset() % 10 == 0)
                .toList();
        List<MatchSnapshot> actual = new ArrayList<>();
        Map<String, Integer> opens = new LinkedHashMap<>();

        boolean completed = ReferenceSearchQuery.collectMatches(source.stream(),
                location -> location.getOffset() % 10 == 0,
                () -> false,
                match -> actual.add(MatchSnapshot.from(match)),
                path -> open(path, opens),
                (path, error) -> {
                    throw new AssertionError(error);
                });

        assertTrue(completed);
        assertEquals(legacyMatches(expectedLocations), actual);
        assertEquals(Map.of(first.toString(), 1, second.toString(), 1), opens);
    }

    @Test
    void readFailuresLogOncePerFileWithConcretePathAndContinue(
            @TempDir Path dir) throws Exception {
        Path missing = dir.resolve("missing.sql");
        Path malformed = dir.resolve("malformed.sql");
        Path good = dir.resolve("good.sql");
        Files.write(malformed, new byte[] {(byte) 0xC3, 0x28});
        Files.writeString(good, "visible line\nsecret file contents\n");
        List<ObjectLocation> locations = List.of(
                location(missing, 1, 10),
                location(missing, 2, 20),
                location(malformed, 1, 30),
                location(malformed, 2, 40),
                location(good, 1, 50));
        List<MatchSnapshot> actual = new ArrayList<>();
        Map<String, Integer> opens = new LinkedHashMap<>();
        List<ReadFailure> failures = new ArrayList<>();

        boolean completed = ReferenceSearchQuery.collectMatches(locations,
                () -> false,
                match -> actual.add(MatchSnapshot.from(match)),
                path -> open(path, opens),
                (path, error) -> failures.add(new ReadFailure(path, error)));

        assertTrue(completed);
        assertEquals(Map.of(
                missing.toString(), 1,
                malformed.toString(), 1,
                good.toString(), 1), opens);
        assertEquals(2, failures.size());
        assertEquals(missing.toString(), failures.get(0).path());
        assertEquals(malformed.toString(), failures.get(1).path());
        assertInstanceOf(MalformedInputException.class, failures.get(1).error());
        assertEquals(List.of(MatchSnapshot.from(locations.getLast(), "visible line")),
                actual);
        assertEquals("Unable to read reference source file: " + malformed,
                ReferenceSearchQuery.readFailureMessage(malformed.toString()));
        assertFalse(ReferenceSearchQuery.readFailureMessage(malformed.toString())
                .contains("secret file contents"));
    }

    private static List<ObjectLocation> countedLocations(int size,
            AtomicInteger visits) {
        ObjectLocation location = location(Path.of("never-opened.sql"), 1, 10);
        return new AbstractList<>() {

            @Override
            public ObjectLocation get(int index) {
                visits.incrementAndGet();
                return location;
            }

            @Override
            public int size() {
                return size;
            }
        };
    }

    private static ObjectLocation location(Path file, int line, int offset) {
        return new ObjectLocation.Builder()
                .setFilePath(file.toString())
                .setLineNumber(line)
                .setOffset(offset)
                .setCharPositionInLine(offset % 7)
                .setReference(new ObjectReference("app", "object_" + offset,
                        DbObjType.TABLE))
                .build();
    }

    private static BufferedReader open(String path, Map<String, Integer> opens)
            throws IOException {
        opens.merge(path, 1, Integer::sum);
        return Files.newBufferedReader(Path.of(path));
    }

    private static List<MatchSnapshot> legacyMatches(
            List<ObjectLocation> locations) throws IOException {
        List<MatchSnapshot> matches = new ArrayList<>();
        for (ObjectLocation location : locations) {
            try (Stream<String> lines = Files.lines(
                    Path.of(location.getFilePath()))) {
                lines.skip(location.getLineNumber() - 1L).findFirst()
                        .ifPresent(line -> matches.add(
                                MatchSnapshot.from(location, line)));
            }
        }
        return matches;
    }

    private record MatchSnapshot(String path, int elementOffset,
            int lineNumber, int charPositionInLine, String displayLine,
            int matchOffset, int matchLength) {

        private static MatchSnapshot from(Match match) {
            ObjectLocation location = (ObjectLocation) match.getElement();
            return new MatchSnapshot(location.getFilePath(), location.getOffset(),
                    location.getLineNumber(), location.getCharPositionInLine(),
                    location.getSql(), match.getOffset(), match.getLength());
        }

        private static MatchSnapshot from(ObjectLocation location, String line) {
            return new MatchSnapshot(location.getFilePath(), location.getOffset(),
                    location.getLineNumber(), location.getCharPositionInLine(),
                    line, location.getOffset(), location.getObjLength());
        }
    }

    private record ReadFailure(String path, Exception error) {
    }
}
