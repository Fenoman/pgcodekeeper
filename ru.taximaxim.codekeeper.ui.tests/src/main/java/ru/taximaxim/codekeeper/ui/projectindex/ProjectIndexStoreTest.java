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

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ru.taximaxim.codekeeper.ui.DatabaseType;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexOpenResult.Status;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetry.Mode;

class ProjectIndexStoreTest {

    @Test
    void fullPublicationSyncsPayloadsBeforeCurrentAndCurrentBeforeSuccess(
            @TempDir Path directory) throws Exception {
        List<String> events = new ArrayList<>();
        ProjectIndexDirectorySync directorySync = (path, stage) ->
                events.add("sync-" + stage);
        ProjectIndexStore.IoHook hook = point -> {
            if (point == ProjectIndexStore.IoPoint.BEFORE_CURRENT_PUBLICATION
                    || point == ProjectIndexStore.IoPoint.AFTER_CURRENT_MOVE
                    || point == ProjectIndexStore.IoPoint.AFTER_CURRENT_PUBLICATION) {
                events.add(point.name());
            }
        };

        try (var store = new ProjectIndexStore(directory,
                ProjectIndexStore.DEFAULT_CACHE_BYTES, hook,
                ProjectIndexStore.DEFAULT_COMPACTION_BYTES,
                directorySync)) {
            assertEquals(ProjectIndexStore.PublishResult.PUBLISHED,
                    store.publish(ProjectIndexFixtures.minimalSnapshot(),
                            () -> false));
        }

        assertEquals(List.of(
                "sync-PAYLOADS",
                "BEFORE_CURRENT_PUBLICATION",
                "AFTER_CURRENT_MOVE",
                "sync-CURRENT",
                "AFTER_CURRENT_PUBLICATION"), events);
    }

    @Test
    void incrementalAppendSyncsOnlyRenamedCurrent(
            @TempDir Path directory) throws Exception {
        List<ProjectIndexDirectorySync.Stage> stages =
                new ArrayList<>();
        ProjectIndexData source =
                ProjectIndexFixtures.snapshotWithAllMetaKinds();
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(source.manifest());

        try (var store = new ProjectIndexStore(directory,
                ProjectIndexStore.DEFAULT_CACHE_BYTES,
                ProjectIndexStore.IoHook.NONE,
                ProjectIndexStore.DEFAULT_COMPACTION_BYTES,
                (path, stage) -> stages.add(stage))) {
            store.publish(source, () -> false);
            stages.clear();
            try (var opened = store.open(identity)) {
                assertEquals(
                        ProjectIndexStore.IncrementalAppendStatus.APPENDED,
                        store.appendIncremental(
                                opened.view().orElseThrow(),
                                replacement(source, 0, "directory-sync"),
                                () -> false).status());
            }
        }

        assertEquals(List.of(ProjectIndexDirectorySync.Stage.CURRENT),
                stages);
    }

    @Test
    void compactionUsesFullPublicationDirectorySyncBoundaries(
            @TempDir Path directory) throws Exception {
        List<ProjectIndexDirectorySync.Stage> stages =
                new ArrayList<>();
        ProjectIndexData source =
                ProjectIndexFixtures.snapshotWithAllMetaKinds();
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(source.manifest());

        try (var store = new ProjectIndexStore(directory,
                ProjectIndexStore.DEFAULT_CACHE_BYTES,
                ProjectIndexStore.IoHook.NONE, 1,
                (path, stage) -> stages.add(stage))) {
            store.publish(source, () -> false);
            stages.clear();
            try (var opened = store.open(identity)) {
                assertEquals(ProjectIndexStore.PublishResult.PUBLISHED,
                        store.compact(opened.view().orElseThrow(),
                                () -> false));
            }
        }

        assertEquals(List.of(
                ProjectIndexDirectorySync.Stage.PAYLOADS,
                ProjectIndexDirectorySync.Stage.CURRENT), stages);
    }

    @Test
    void payloadDirectorySyncFailurePreservesOldReadableGeneration(
            @TempDir Path directory) throws Exception {
        ProjectIndexData old =
                ProjectIndexFixtures.withDefinitionComment("old");
        ProjectIndexData replacement =
                ProjectIndexFixtures.withDefinitionComment("replacement");
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(old.manifest());
        try (var store = new ProjectIndexStore(directory)) {
            store.publish(old, () -> false);
        }

        try (var failing = new ProjectIndexStore(directory,
                ProjectIndexStore.DEFAULT_CACHE_BYTES,
                ProjectIndexStore.IoHook.NONE,
                ProjectIndexStore.DEFAULT_COMPACTION_BYTES,
                (path, stage) -> {
                    if (stage
                            == ProjectIndexDirectorySync.Stage.PAYLOADS) {
                        throw new IOException(
                                "simulated payload directory sync failure");
                    }
                })) {
            ProjectIndexStore.DirectorySyncException failure =
                    assertThrows(
                            ProjectIndexStore.DirectorySyncException.class,
                            () -> failing.publish(replacement,
                                    () -> false));
            assertEquals(ProjectIndexDirectorySync.Stage.PAYLOADS,
                    failure.stage());
            assertFalse(failure.currentInstalled());
        }

        try (var store = new ProjectIndexStore(directory);
                var opened = store.open(identity)) {
            assertEquals("old", opened.view().orElseThrow()
                    .contribution(old.files().getFirst().path())
                    .orElseThrow().definitions().getFirst().comment());
        }
    }

    @Test
    void currentDirectorySyncFailureKeepsPossiblyCurrentFullPayload(
            @TempDir Path directory) throws Exception {
        ProjectIndexData old =
                ProjectIndexFixtures.withDefinitionComment("old");
        ProjectIndexData replacement =
                ProjectIndexFixtures.withDefinitionComment("replacement");
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(old.manifest());
        try (var store = new ProjectIndexStore(directory)) {
            store.publish(old, () -> false);
        }

        ProjectIndexRevision uncertainRevision;
        try (var failing = new ProjectIndexStore(directory,
                ProjectIndexStore.DEFAULT_CACHE_BYTES,
                ProjectIndexStore.IoHook.NONE,
                ProjectIndexStore.DEFAULT_COMPACTION_BYTES,
                (path, stage) -> {
                    if (stage
                            == ProjectIndexDirectorySync.Stage.CURRENT) {
                        throw new IOException(
                                "simulated CURRENT directory sync failure");
                    }
                })) {
            ProjectIndexStore.CurrentDurabilityException failure =
                    assertThrows(
                            ProjectIndexStore
                                    .CurrentDurabilityException.class,
                            () -> failing.publish(replacement,
                                    () -> false));
            uncertainRevision = failure.revision();
            assertEquals(identity, uncertainRevision.identity());
            assertEquals(0,
                    uncertainRevision.committedJournalLength());
        }

        try (var store = new ProjectIndexStore(directory);
                var opened = store.open(identity)) {
            ProjectIndexView view = opened.view().orElseThrow();
            assertEquals(uncertainRevision, view.revision());
            assertEquals("replacement", view
                    .contribution(replacement.files().getFirst().path())
                    .orElseThrow().definitions().getFirst().comment());
        }
    }

    @Test
    void currentDirectorySyncFailureKeepsPossiblyCurrentAppend(
            @TempDir Path directory) throws Exception {
        ProjectIndexData source =
                ProjectIndexFixtures.snapshotWithAllMetaKinds();
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(source.manifest());
        ProjectIndexDelta.Change replacement =
                replacement(source, 0, "possibly-current");
        try (var store = new ProjectIndexStore(directory)) {
            store.publish(source, () -> false);
        }

        ProjectIndexRevision uncertainRevision;
        try (var failing = new ProjectIndexStore(directory,
                ProjectIndexStore.DEFAULT_CACHE_BYTES,
                ProjectIndexStore.IoHook.NONE,
                ProjectIndexStore.DEFAULT_COMPACTION_BYTES,
                (path, stage) -> {
                    if (stage
                            == ProjectIndexDirectorySync.Stage.CURRENT) {
                        throw new IOException(
                                "simulated CURRENT directory sync failure");
                    }
                });
                var opened = failing.open(identity)) {
            ProjectIndexStore.CurrentDurabilityException failure =
                    assertThrows(
                            ProjectIndexStore
                                    .CurrentDurabilityException.class,
                            () -> failing.appendIncremental(
                                    opened.view().orElseThrow(),
                                    replacement, () -> false));
            uncertainRevision = failure.revision();
            assertEquals(identity, uncertainRevision.identity());
            assertTrue(uncertainRevision
                    .committedJournalLength() > 0);
        }

        try (var store = new ProjectIndexStore(directory);
                var opened = store.open(identity)) {
            ProjectIndexView view = opened.view().orElseThrow();
            assertEquals(uncertainRevision, view.revision());
            assertEquals(1, view.committedJournalEntriesForTests());
            assertTrue(view.fileStamps().contains(replacement.stamp()));
        }
    }

    @Test
    void platformDirectorySyncDegradesOnlyOnWindows(
            @TempDir Path directory) throws Exception {
        Path missing = directory.resolve("missing");

        assertDoesNotThrow(() ->
                ProjectIndexDirectorySync.forOs("Windows 11")
                        .sync(missing,
                                ProjectIndexDirectorySync.Stage.CURRENT));
        assertThrows(IOException.class, () ->
                ProjectIndexDirectorySync.forOs("Linux")
                        .sync(missing,
                                ProjectIndexDirectorySync.Stage.CURRENT));
    }

    @Test
    void publicationReportsDefaultBoundedWriterBudget(
            @TempDir Path directory) throws Exception {
        List<String> lines = new ArrayList<>();
        var telemetry = new ProjectIndexTelemetry(lines::add, () -> 0)
                .start(Mode.COLD);
        try (var store = new ProjectIndexStore(directory)) {
            ProjectIndexStore.PublishOutcome outcome =
                    store.publishWithReceipt(
                            ProjectIndexFixtures.snapshotWithAllMetaKinds(),
                            () -> false, telemetry);

            assertEquals(ProjectIndexStore.PublishResult.PUBLISHED,
                    outcome.status());
        } finally {
            telemetry.close();
        }

        assertEquals(64L << 20,
                ProjectIndexStore.DEFAULT_TUPLE_BUDGET_BYTES);
        assertEquals(1, lines.size());
        assertTrue(lines.getFirst().contains(
                "writer_buffer_budget_bytes=67108864"));
        assertTrue(lines.getFirst().contains("writer_packed_bytes="));
        assertTrue(lines.getFirst().contains(
                "phase_encode_ms=0 phase_locator_crc_ms=0 "
                        + "phase_fsync_ms=0 phase_current_ms=0"));
        assertEquals(tokenValue(lines.getFirst(),
                "writer_packed_bytes"),
                tokenValue(lines.getFirst(),
                        "writer_reread_bytes"));
    }

    @Test
    void incrementalAndCompactionReportEveryPublicationSubphase(
            @TempDir Path directory) throws Exception {
        List<String> lines = new ArrayList<>();
        ProjectIndexData source =
                ProjectIndexFixtures.snapshotWithAllMetaKinds();
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(source.manifest());
        ProjectIndexDelta.Change replacement =
                replacement(source, 0, "telemetry");

        try (var store = new ProjectIndexStore(directory)) {
            store.publish(source, () -> false);
            try (var opened = store.open(identity);
                    var telemetry =
                            new ProjectIndexTelemetry(
                                    lines::add, () -> 0)
                                    .start(Mode.INCREMENTAL)) {
                assertEquals(
                        ProjectIndexStore.IncrementalAppendStatus.APPENDED,
                        store.appendIncremental(
                                opened.view().orElseThrow(),
                                replacement, () -> false,
                                telemetry).status());
            }
            try (var opened = store.open(identity);
                    var telemetry =
                            new ProjectIndexTelemetry(
                                    lines::add, () -> 0)
                                    .start(Mode.INCREMENTAL)) {
                assertEquals(ProjectIndexStore.PublishResult.PUBLISHED,
                        store.compact(opened.view().orElseThrow(),
                                () -> false, telemetry));
            }
        }

        assertEquals(2, lines.size());
        lines.forEach(
                ProjectIndexStoreTest::assertPackedPublicationTelemetry);
    }

    @Test
    void journalWriterUsesCurrentPublicationWorkspace(
            @TempDir Path directory) throws Exception {
        var expectedPublicationId = new AtomicReference<String>();
        var observedWorkspace = new AtomicBoolean();
        ProjectIndexStore.IoHook hook = point -> {
            String publicationId = expectedPublicationId.get();
            if (point == ProjectIndexStore.IoPoint.JOURNAL_WRITE_CHUNK
                    && publicationId != null
                    && Files.isDirectory(
                            writerWorkspace(directory, publicationId),
                            LinkOption.NOFOLLOW_LINKS)) {
                observedWorkspace.set(true);
            }
        };
        ProjectIndexData source =
                ProjectIndexFixtures.repeatedLocations(100_000);
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(source.manifest());
        ProjectFileStamp stamp = source.manifest().files().getFirst();
        FileContribution replacement = source.files().getFirst();

        try (var store = new ProjectIndexStore(directory,
                ProjectIndexStore.DEFAULT_CACHE_BYTES, hook,
                ProjectIndexStore.DEFAULT_COMPACTION_BYTES)) {
            ProjectIndexStore.PublishOutcome published =
                    store.publishWithReceipt(source, () -> false);
            expectedPublicationId.set(
                    published.revision().publicationId());

            try (var opened = store.open(identity)) {
                assertEquals(
                        ProjectIndexStore.IncrementalAppendStatus.APPENDED,
                        store.appendIncremental(
                                opened.view().orElseThrow(),
                                ProjectIndexDelta.Change.replace(
                                        stamp, replacement),
                                () -> false).status());
            }
        }

        assertTrue(observedWorkspace.get(),
                "The journal writer must use the current publication id");
        assertFalse(Files.exists(writerWorkspace(directory,
                expectedPublicationId.get()), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void journalReplaceRecoversInactiveCurrentPublicationWorkspace(
            @TempDir Path directory) throws Exception {
        ProjectIndexData source =
                ProjectIndexFixtures.snapshotWithAllMetaKinds();
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(source.manifest());
        ProjectFileStamp stamp =
                source.manifest().files().getFirst();
        FileContribution replacement = source.files().stream()
                .filter(file -> file.path().equals(stamp.path()))
                .findFirst().orElseThrow();
        String publicationId;

        try (var store = new ProjectIndexStore(directory)) {
            ProjectIndexStore.PublishOutcome published =
                    store.publishWithReceipt(source, () -> false);
            publicationId = published.revision().publicationId();
            Path stale = Files.createDirectories(
                    writerWorkspace(directory, publicationId));
            Files.writeString(stale.resolve("stale.run"),
                    "interrupted journal writer");

            try (var opened = store.open(identity)) {
                assertEquals(
                        ProjectIndexStore.IncrementalAppendStatus.APPENDED,
                        store.appendIncremental(
                                opened.view().orElseThrow(),
                                ProjectIndexDelta.Change.replace(
                                        stamp, replacement),
                                () -> false).status());
            }
            assertFalse(Files.exists(
                    writerWorkspace(directory, publicationId),
                    LinkOption.NOFOLLOW_LINKS));
        }

        try (var reopenedStore = new ProjectIndexStore(directory);
                var reopened = reopenedStore.open(identity)) {
            assertEquals(Status.HIT, reopened.status());
            assertEquals(replacement,
                    reopened.view().orElseThrow()
                            .contribution(stamp.path())
                            .orElseThrow());
        }
    }

    @Test
    void journalReplaceNeverDeletesActiveCurrentPublicationWorkspace(
            @TempDir Path directory) throws Exception {
        ProjectIndexData source =
                ProjectIndexFixtures.snapshotWithAllMetaKinds();
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(source.manifest());
        ProjectFileStamp stamp =
                source.manifest().files().getFirst();
        FileContribution replacement = source.files().stream()
                .filter(file -> file.path().equals(stamp.path()))
                .findFirst().orElseThrow();

        try (var store = new ProjectIndexStore(directory)) {
            ProjectIndexStore.PublishOutcome published =
                    store.publishWithReceipt(source, () -> false);
            String publicationId =
                    published.revision().publicationId();
            Path workspace =
                    writerWorkspace(directory, publicationId);
            try (var active = new ProjectIndexSpillStore(
                    directory, publicationId, () -> false)) {
                var runs = active.section(
                        SectionType.COMPLETION_TRIGRAMS);
                var row = new ProjectIndexTupleBuffer(1, 1);
                row.add(42);
                runs.spill(row);

                try (var opened = store.open(identity)) {
                    ProjectIndexView view =
                            opened.view().orElseThrow();
                    assertThrows(IOException.class,
                            () -> store.appendIncremental(
                                    view,
                                    ProjectIndexDelta.Change.replace(
                                            stamp, replacement),
                                    () -> false));
                }
                assertTrue(Files.isDirectory(workspace,
                        LinkOption.NOFOLLOW_LINKS));
                runs.finish(new ProjectIndexTupleBuffer(1, 0));
                try (var cursor = runs.openCursor()) {
                    assertTrue(cursor.next());
                    assertEquals(42, cursor.word(0));
                    assertFalse(cursor.next());
                }
            }

            try (var retry = store.open(identity)) {
                assertEquals(
                        ProjectIndexStore.IncrementalAppendStatus.APPENDED,
                        store.appendIncremental(
                                retry.view().orElseThrow(),
                                ProjectIndexDelta.Change.replace(
                                        stamp, replacement),
                                () -> false).status());
            }
        }
    }

    @Test
    void bodyOnlyIncrementalResultMatchesIndependentFullSnapshot(
            @TempDir Path directory) throws Exception {
        ProjectIndexData source =
                ProjectIndexFixtures.snapshotWithAllMetaKinds();
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(source.manifest());
        int changedIndex = 1;
        FileContribution previous = source.files().get(changedIndex);
        var bodyReferences = new ArrayList<>(
                previous.locations().reversed());
        FileContribution replacement = new FileContribution(
                previous.path(), previous.definitions(),
                bodyReferences, Set.of(), true);
        ProjectFileStamp oldStamp =
                source.manifest().files().get(changedIndex);
        ProjectFileStamp newStamp = new ProjectFileStamp(
                oldStamp.path(),
                oldStamp.eclipseModificationStamp() + 1,
                oldStamp.size() + 17,
                oldStamp.lastModifiedMillis() + 1,
                ProjectIndexFixtures.sha256("changed routine body"));

        assertTrue(ProjectIndexIncrementalPlanner.isSafeReplacement(
                previous.path(), previous.definitions(), replacement));

        List<ProjectFileStamp> expectedStamps =
                new ArrayList<>(source.manifest().files());
        expectedStamps.set(changedIndex, newStamp);
        ProjectIndexManifest manifest = source.manifest();
        ProjectIndexManifest expectedManifest =
                new ProjectIndexManifest(
                        manifest.formatMajor(),
                        manifest.formatMinor(),
                        manifest.parserAbi(),
                        manifest.coreVersion(),
                        manifest.uiVersion(),
                        manifest.databaseType(),
                        manifest.projectIdentity(),
                        manifest.configSha256(),
                        manifest.generation(),
                        expectedStamps);
        List<FileContribution> expectedFiles =
                new ArrayList<>(source.files());
        expectedFiles.set(changedIndex, replacement);
        ProjectIndexData independentlyBuiltFull =
                new ProjectIndexData(expectedManifest,
                        expectedFiles);

        try (var store = new ProjectIndexStore(directory)) {
            store.publish(source, () -> false);
            try (var opened = store.open(identity)) {
                assertEquals(
                        ProjectIndexStore.IncrementalAppendStatus.APPENDED,
                        store.appendIncremental(
                                opened.view().orElseThrow(),
                                ProjectIndexDelta.Change.replace(
                                        newStamp, replacement),
                                () -> false).status());
            }
            try (var opened = store.open(identity)) {
                ProjectIndexView incremental =
                        opened.view().orElseThrow();
                assertArrayEquals(
                        ProjectIndexFixtures.canonicalBytes(
                                independentlyBuiltFull),
                        ProjectIndexFixtures.canonicalBytes(
                                incremental.materialize()));
                assertEquals(ProjectIndexStore.PublishResult.PUBLISHED,
                        store.compact(incremental, () -> false));
            }
        }

        try (var store = new ProjectIndexStore(directory);
                var opened = store.open(identity)) {
            assertArrayEquals(ProjectIndexCodec.encodeBase(
                    independentlyBuiltFull),
                    readCanonicalCodec(opened.view().orElseThrow()
                            .basePathForTests()));
        }
    }

    @Test
    void incrementalReplacementAppendsOneEntryAndReturnsExactRevision(
            @TempDir Path directory) throws Exception {
        ProjectIndexData source = ProjectIndexFixtures.snapshotWithAllMetaKinds();
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(source.manifest());
        ProjectFileStamp stamp = source.manifest().files().getFirst();
        FileContribution replacement = ProjectIndexFixtures
                .withDefinitionComment("incremental").files().getFirst();

        try (var store = new ProjectIndexStore(directory)) {
            store.publish(source, () -> false);
            try (var opened = store.open(identity)) {
                ProjectIndexView view = opened.view().orElseThrow();
                var result = store.appendIncremental(view,
                        ProjectIndexDelta.Change.replace(
                                stamp, replacement),
                        () -> false);

                assertEquals(
                        ProjectIndexStore.IncrementalAppendStatus.APPENDED,
                        result.status());
                assertEquals(view.revision().publicationId(),
                        result.revision().publicationId());
                assertTrue(result.revision().committedJournalLength()
                        > view.revision().committedJournalLength());
                assertEquals(identity, result.revision().identity());
                assertEquals(0, view.decodedContributionCount(),
                        "The incremental writer must not scan contributions");
            }
            try (var opened = store.open(identity)) {
                ProjectIndexView view = opened.view().orElseThrow();
                assertEquals(1,
                        view.committedJournalEntriesForTests());
                assertEquals("incremental",
                        view.contribution(stamp.path()).orElseThrow()
                                .definitions().getFirst().comment());
            }
        }
    }

    @Test
    void incrementalBatchPublishesAllReplacementsWithOneRevisionAndCurrentMove(
            @TempDir Path directory) throws Exception {
        ProjectIndexData source =
                ProjectIndexFixtures.snapshotWithAllMetaKinds();
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(source.manifest());
        ProjectIndexDelta delta = replacementDelta(source, 3, "batch");
        AtomicInteger journalEntries = new AtomicInteger();
        AtomicInteger journalPublications = new AtomicInteger();
        AtomicInteger currentMoves = new AtomicInteger();
        AtomicBoolean armed = new AtomicBoolean();
        ProjectIndexStore.IoHook hook = point -> {
            if (!armed.get()) {
                return;
            }
            switch (point) {
            case AFTER_JOURNAL_ENTRY -> journalEntries.incrementAndGet();
            case AFTER_JOURNAL_WRITE -> journalPublications.incrementAndGet();
            case AFTER_CURRENT_MOVE -> currentMoves.incrementAndGet();
            default -> {
                // only publication boundaries are counted
            }
            }
        };

        ProjectIndexRevision previous;
        ProjectIndexStore.IncrementalAppendResult result;
        try (var store = new ProjectIndexStore(directory,
                ProjectIndexStore.DEFAULT_CACHE_BYTES, hook,
                ProjectIndexStore.DEFAULT_COMPACTION_BYTES)) {
            store.publish(source, () -> false);
            try (var opened = store.open(identity)) {
                ProjectIndexView view = opened.view().orElseThrow();
                previous = view.revision();
                armed.set(true);

                result = store.appendIncremental(
                        view, delta, () -> false);
            }
        }

        assertEquals(ProjectIndexStore.IncrementalAppendStatus.APPENDED,
                result.status());
        assertEquals(previous.publicationId(),
                result.revision().publicationId());
        assertEquals(previous.generation(),
                result.revision().generation());
        assertEquals(identity, result.revision().identity());
        assertTrue(result.revision().committedJournalLength()
                > previous.committedJournalLength());
        assertEquals(3, journalEntries.get());
        assertEquals(1, journalPublications.get());
        assertEquals(1, currentMoves.get());

        try (var store = new ProjectIndexStore(directory);
                var reopened = store.open(identity)) {
            ProjectIndexView view = reopened.view().orElseThrow();
            assertEquals(result.revision(), view.revision());
            assertEquals(3, view.committedJournalEntriesForTests());
            List<ProjectFileStamp> stamps = view.fileStamps();
            for (ProjectIndexDelta.Change change : delta.changes()) {
                assertTrue(stamps.contains(change.stamp()));
                assertEquals(change.contribution(),
                        view.contribution(change.path()).orElseThrow());
            }
        }
    }

    @Test
    void incrementalBatchAcceptsTheWholeLimitAndRejectsLargerOrNonReplacementDelta(
            @TempDir Path directory) throws Exception {
        int limit = ProjectIndexIncrementalPlanner.MAX_BATCH_SIZE;
        ProjectIndexData source =
                ProjectIndexFixtures.largeIndexedProject(limit + 1);
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(source.manifest());
        ProjectIndexDelta whole =
                replacementDelta(source, limit, "whole");

        try (var store = new ProjectIndexStore(directory)) {
            store.publish(source, () -> false);
            try (var opened = store.open(identity)) {
                assertEquals(
                        ProjectIndexStore.IncrementalAppendStatus.APPENDED,
                        store.appendIncremental(
                                opened.view().orElseThrow(), whole,
                                () -> false).status());
            }
            try (var opened = store.open(identity)) {
                ProjectIndexView view = opened.view().orElseThrow();
                ProjectIndexRevision revision = view.revision();
                ProjectIndexDelta oversized =
                        replacementDelta(source, limit + 1, "oversized");
                ProjectIndexDelta mixed = new ProjectIndexDelta(List.of(
                        replacement(source, 0, "replace"),
                        ProjectIndexDelta.Change.delete(
                                source.files().get(1).path())));

                assertThrows(IllegalArgumentException.class,
                        () -> store.appendIncremental(
                                view, oversized, () -> false));
                assertThrows(IllegalArgumentException.class,
                        () -> store.appendIncremental(
                                view, mixed, () -> false));
                assertEquals(revision, view.revision());
            }
            try (var reopened = store.open(identity)) {
                ProjectIndexView view = reopened.view().orElseThrow();
                for (ProjectIndexDelta.Change change : whole.changes()) {
                    assertTrue(view.fileStamps().contains(change.stamp()));
                    assertEquals(change.contribution(),
                            view.contribution(change.path()).orElseThrow());
                }
            }
        }
    }

    @Test
    void incrementalBatchOverflowingTheJournalFoldsItAndKeepsPublishing(
            @TempDir Path directory) throws Exception {
        ProjectIndexData source =
                ProjectIndexFixtures.snapshotWithAllMetaKinds();
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(source.manifest());
        ProjectIndexDelta overflow =
                replacementDelta(source, 2, "overflow");

        try (var store = new ProjectIndexStore(directory)) {
            store.publish(source, () -> false);
            for (int i = 0; i < 31; i++) {
                try (var opened = store.open(identity)) {
                    assertEquals(
                            ProjectIndexStore.IncrementalAppendStatus.APPENDED,
                            store.appendIncremental(
                                    opened.view().orElseThrow(),
                                    replacement(source, 0, "fill-" + i),
                                    () -> false).status());
                }
            }
            ProjectIndexRevision folded;
            String previousPublication;
            try (var opened = store.open(identity)) {
                ProjectIndexView view = opened.view().orElseThrow();
                previousPublication = view.revision().publicationId();

                var result = store.appendIncremental(
                        view, overflow, () -> false);

                assertEquals(
                        ProjectIndexStore.IncrementalAppendStatus.APPENDED,
                        result.status());
                assertNull(result.compactionFailure());
                folded = result.revision();
            }
            assertNotEquals(previousPublication, folded.publicationId(),
                    "An overflowing batch must start a new generation");
            assertEquals(0, folded.committedJournalLength());

            try (var reopened = store.open(identity)) {
                ProjectIndexView view = reopened.view().orElseThrow();
                assertEquals(folded, view.revision());
                assertEquals(0, view.committedJournalEntriesForTests(),
                        "Folding the journal must leave it empty");
                for (ProjectIndexDelta.Change change : overflow.changes()) {
                    assertTrue(view.fileStamps().contains(change.stamp()));
                    assertEquals(change.contribution(),
                            view.contribution(change.path()).orElseThrow());
                }
            }
        }
    }

    @Test
    void sequentialIncrementalBatchesNeverDemandAFullRebuild(
            @TempDir Path directory) throws Exception {
        ProjectIndexData source =
                ProjectIndexFixtures.largeIndexedProject(16);
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(source.manifest());
        int batches = 8;

        try (var store = new ProjectIndexStore(directory)) {
            store.publish(source, () -> false);
            ProjectIndexDelta last = null;
            String publication = null;
            int folds = 0;
            for (int batch = 0; batch < batches; batch++) {
                last = replacementDelta(source, 16, "batch-" + batch);
                try (var opened = store.open(identity)) {
                    ProjectIndexView view = opened.view().orElseThrow();

                    var result = store.appendIncremental(
                            view, last, () -> false);

                    assertEquals(
                            ProjectIndexStore.IncrementalAppendStatus.APPENDED,
                            result.status(),
                            "Batch " + batch
                                    + " must publish instead of demanding a rebuild");
                    if (!result.revision().publicationId()
                            .equals(publication)) {
                        publication = result.revision().publicationId();
                        folds++;
                    }
                }
                try (var reopened = store.open(identity)) {
                    assertTrue(reopened.view().orElseThrow()
                            .committedJournalEntriesForTests() <= 32,
                            "The committed journal must stay bounded");
                }
            }
            assertTrue(folds > 1,
                    "Repeated batches must have folded the journal at least once");
            try (var reopened = store.open(identity)) {
                ProjectIndexView view = reopened.view().orElseThrow();
                assertEquals(source.files().size(),
                        view.fileStamps().size());
                for (ProjectIndexDelta.Change change : last.changes()) {
                    assertTrue(view.fileStamps().contains(change.stamp()));
                    assertEquals(change.contribution(),
                            view.contribution(change.path()).orElseThrow());
                }
            }
        }
    }

    @Test
    void failureAtEveryFoldBoundaryRequiresFullRebuildAndKeepsTheRevision(
            @TempDir Path root) throws Exception {
        ProjectIndexData source =
                ProjectIndexFixtures.snapshotWithAllMetaKinds();
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(source.manifest());

        for (ProjectIndexStore.IoPoint point : List.of(
                ProjectIndexStore.IoPoint.BASE_WRITE_CHUNK,
                ProjectIndexStore.IoPoint.AFTER_BASE_WRITE,
                ProjectIndexStore.IoPoint.BEFORE_CURRENT_PUBLICATION)) {
            Path directory = root.resolve(point.name());
            AtomicBoolean armed = new AtomicBoolean();
            AtomicInteger reached = new AtomicInteger();
            ProjectIndexStore.IoHook hook = actual -> {
                if (armed.get() && actual == point) {
                    reached.incrementAndGet();
                    throw new IOException(
                            "simulated fold failure at " + point);
                }
            };
            // A journal past its byte threshold folds on the next revision.
            try (var store = new ProjectIndexStore(directory,
                    ProjectIndexStore.DEFAULT_CACHE_BYTES, hook, 1)) {
                ProjectIndexDelta.Change committedChange =
                        replacement(source, 0, "committed-" + point);
                store.publish(source, () -> false);
                try (var opened = store.open(identity)) {
                    assertEquals(
                            ProjectIndexStore.IncrementalAppendStatus.APPENDED,
                            store.appendIncremental(
                                    opened.view().orElseThrow(),
                                    committedChange, () -> false).status());
                }
                ProjectIndexRevision committed;
                try (var opened = store.open(identity)) {
                    ProjectIndexView view = opened.view().orElseThrow();
                    committed = view.revision();
                    assertTrue(store.needsCompaction(view));
                    armed.set(true);

                    var result = store.appendIncremental(view,
                            replacement(source, 1, "abandoned"),
                            () -> false);

                    assertEquals(
                            ProjectIndexStore.IncrementalAppendStatus.REQUIRES_FULL,
                            result.status(),
                            "A failed fold at " + point
                                    + " must demand a full rebuild");
                    assertNull(result.revision());
                    assertNotNull(result.compactionFailure(),
                            "An abandoned fold must report why it failed");
                    assertEquals(committed, view.revision());
                }
                assertEquals(1, reached.get(),
                        "The fold must have reached " + point);
                armed.set(false);
                try (var reopened = store.open(identity)) {
                    ProjectIndexView view = reopened.view().orElseThrow();
                    assertEquals(committed, view.revision(),
                            "CURRENT must still name the committed revision");
                    assertEquals(1,
                            view.committedJournalEntriesForTests());
                    assertTrue(view.fileStamps()
                            .contains(committedChange.stamp()),
                            "The committed revision must survive intact");
                    assertFalse(view.fileStamps().contains(
                            replacement(source, 1, "abandoned").stamp()),
                            "An abandoned fold must publish nothing");
                }
            }
        }
    }

    @Test
    void cancellationAfterEveryBatchItemNeverPublishesPartialRevision(
            @TempDir Path root) throws Exception {
        ProjectIndexData source =
                ProjectIndexFixtures.snapshotWithAllMetaKinds();
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(source.manifest());
        ProjectIndexDelta delta =
                replacementDelta(source, 3, "cancelled");

        for (int cancelAfter = 1;
                cancelAfter <= delta.changes().size(); cancelAfter++) {
            Path directory = root.resolve("item-" + cancelAfter);
            AtomicBoolean cancelled = new AtomicBoolean();
            AtomicInteger completed = new AtomicInteger();
            int boundary = cancelAfter;
            ProjectIndexStore.IoHook hook = point -> {
                if (point == ProjectIndexStore.IoPoint.AFTER_JOURNAL_ENTRY
                        && completed.incrementAndGet() == boundary) {
                    cancelled.set(true);
                }
            };
            try (var store = new ProjectIndexStore(directory,
                    ProjectIndexStore.DEFAULT_CACHE_BYTES, hook,
                    ProjectIndexStore.DEFAULT_COMPACTION_BYTES)) {
                store.publish(source, () -> false);
                try (var opened = store.open(identity)) {
                    assertEquals(
                            ProjectIndexStore.IncrementalAppendStatus.CANCELLED,
                            store.appendIncremental(
                                    opened.view().orElseThrow(), delta,
                                    cancelled::get).status());
                }
            }
            assertOriginalRevision(root.resolve("item-" + cancelAfter),
                    source, identity);
        }
    }

    @Test
    void failureAtEveryPreCurrentBatchBoundaryNeverPublishesPartialRevision(
            @TempDir Path root) throws Exception {
        ProjectIndexData source =
                ProjectIndexFixtures.snapshotWithAllMetaKinds();
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(source.manifest());
        ProjectIndexDelta delta =
                replacementDelta(source, 3, "failed");

        for (int failAfter = 1;
                failAfter <= delta.changes().size(); failAfter++) {
            Path directory = root.resolve("entry-" + failAfter);
            AtomicInteger completed = new AtomicInteger();
            int boundary = failAfter;
            ProjectIndexStore.IoHook hook = point -> {
                if (point == ProjectIndexStore.IoPoint.AFTER_JOURNAL_ENTRY
                        && completed.incrementAndGet() == boundary) {
                    throw new IOException(
                            "simulated entry-boundary failure");
                }
            };
            assertBatchFailureKeepsOriginal(directory, source, identity,
                    delta, hook);
        }

        for (ProjectIndexStore.IoPoint point : List.of(
                ProjectIndexStore.IoPoint.JOURNAL_WRITE_CHUNK,
                ProjectIndexStore.IoPoint.AFTER_JOURNAL_WRITE,
                ProjectIndexStore.IoPoint.BEFORE_CURRENT_PUBLICATION)) {
            Path directory = root.resolve(point.name());
            ProjectIndexStore.IoHook hook = actual -> {
                if (actual == point) {
                    throw new IOException(
                            "simulated pre-CURRENT failure at " + point);
                }
            };
            assertBatchFailureKeepsOriginal(directory, source, identity,
                    delta, hook);
        }
    }

    @Test
    void durableCurrentWinsBatchPublicationAcknowledgementFailures(
            @TempDir Path root) throws Exception {
        ProjectIndexData source =
                ProjectIndexFixtures.snapshotWithAllMetaKinds();
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(source.manifest());
        ProjectIndexDelta delta =
                replacementDelta(source, 3, "committed");

        for (ProjectIndexStore.IoPoint point : List.of(
                ProjectIndexStore.IoPoint.AFTER_CURRENT_MOVE,
                ProjectIndexStore.IoPoint.AFTER_CURRENT_PUBLICATION)) {
            Path directory = root.resolve(point.name());
            ProjectIndexStore.IoHook hook = actual -> {
                if (actual == point) {
                    throw new IOException(
                            "simulated durable publication failure at "
                                    + point);
                }
            };
            ProjectIndexStore.IncrementalAppendResult result;
            try (var store = new ProjectIndexStore(directory,
                    ProjectIndexStore.DEFAULT_CACHE_BYTES, hook,
                    ProjectIndexStore.DEFAULT_COMPACTION_BYTES)) {
                store.publish(source, () -> false);
                try (var opened = store.open(identity)) {
                    result = store.appendIncremental(
                            opened.view().orElseThrow(), delta,
                            () -> false);
                }
            }
            assertEquals(
                    ProjectIndexStore.IncrementalAppendStatus.APPENDED,
                    result.status());
            try (var store = new ProjectIndexStore(directory);
                    var reopened = store.open(identity)) {
                ProjectIndexView view = reopened.view().orElseThrow();
                assertEquals(result.revision(), view.revision());
                assertEquals(3,
                        view.committedJournalEntriesForTests());
            }
        }
    }

    @Test
    void staleViewCannotPublishAnyPartOfIncrementalBatch(
            @TempDir Path directory) throws Exception {
        ProjectIndexData source =
                ProjectIndexFixtures.snapshotWithAllMetaKinds();
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(source.manifest());
        ProjectIndexDelta batch =
                replacementDelta(source, 2, "stale");

        try (var store = new ProjectIndexStore(directory)) {
            store.publish(source, () -> false);
            try (var opened = store.open(identity)) {
                ProjectIndexView stale = opened.view().orElseThrow();
                assertEquals(
                        ProjectIndexStore.IncrementalAppendStatus.APPENDED,
                        store.appendIncremental(stale,
                                replacement(source, 2, "winner"),
                                () -> false).status());

                assertThrows(IOException.class,
                        () -> store.appendIncremental(
                                stale, batch, () -> false));
            }
            try (var reopened = store.open(identity)) {
                ProjectIndexView view =
                        reopened.view().orElseThrow();
                assertEquals(1,
                        view.committedJournalEntriesForTests());
                assertFalse(view.fileStamps().contains(
                        batch.changes().getFirst().stamp()));
                assertFalse(view.fileStamps().contains(
                        batch.changes().getLast().stamp()));
            }
        }
    }

    @Test
    void incrementalReplacementAtTheJournalLimitFoldsInsteadOfRebuilding(
            @TempDir Path directory) throws Exception {
        ProjectIndexData source = ProjectIndexFixtures.snapshotWithAllMetaKinds();
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(source.manifest());
        ProjectFileStamp stamp = source.manifest().files().getFirst();

        try (var store = new ProjectIndexStore(directory)) {
            store.publish(source, () -> false);
            for (int i = 0; i < 32; i++) {
                try (var opened = store.open(identity)) {
                    ProjectIndexView view = opened.view().orElseThrow();
                    FileContribution replacement = ProjectIndexFixtures
                            .withDefinitionComment("incremental-" + i)
                            .files().getFirst();
                    assertEquals(
                            ProjectIndexStore.IncrementalAppendStatus.APPENDED,
                            store.appendIncremental(view,
                                    ProjectIndexDelta.Change.replace(
                                            stamp, replacement),
                                    () -> false).status());
                }
            }
            ProjectIndexRevision folded;
            try (var opened = store.open(identity)) {
                ProjectIndexView view = opened.view().orElseThrow();
                assertEquals(32, view.committedJournalEntriesForTests());
                FileContribution replacement = ProjectIndexFixtures
                        .withDefinitionComment("past-the-limit")
                        .files().getFirst();

                var result = store.appendIncremental(view,
                        ProjectIndexDelta.Change.replace(
                                stamp, replacement),
                        () -> false);

                assertEquals(
                        ProjectIndexStore.IncrementalAppendStatus.APPENDED,
                        result.status());
                folded = result.revision();
            }
            try (var reopened = store.open(identity)) {
                ProjectIndexView view = reopened.view().orElseThrow();
                assertEquals(folded, view.revision());
                assertEquals(0, view.committedJournalEntriesForTests());
                assertEquals("past-the-limit",
                        view.contribution(stamp.path()).orElseThrow()
                                .definitions().getFirst().comment());
            }
        }
    }

    @Test
    void incrementalReplacementPastTheCompactionThresholdFolds(
            @TempDir Path directory) throws Exception {
        ProjectIndexData source = ProjectIndexFixtures.snapshotWithAllMetaKinds();
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(source.manifest());
        ProjectFileStamp stamp = source.manifest().files().getFirst();
        try (var store = new ProjectIndexStore(directory,
                ProjectIndexStore.DEFAULT_CACHE_BYTES,
                ProjectIndexStore.IoHook.NONE, 1)) {
            store.publish(source, () -> false);
            try (var opened = store.open(identity)) {
                assertEquals(
                        ProjectIndexStore.IncrementalAppendStatus.APPENDED,
                        store.appendIncremental(
                                opened.view().orElseThrow(),
                                ProjectIndexDelta.Change.replace(stamp,
                                        ProjectIndexFixtures
                                                .withDefinitionComment("one")
                                                .files().getFirst()),
                                () -> false).status());
            }
            try (var opened = store.open(identity)) {
                ProjectIndexView view = opened.view().orElseThrow();
                assertTrue(store.needsCompaction(view));
                assertEquals(
                        ProjectIndexStore.IncrementalAppendStatus.APPENDED,
                        store.appendIncremental(view,
                                ProjectIndexDelta.Change.replace(stamp,
                                        ProjectIndexFixtures
                                                .withDefinitionComment("two")
                                                .files().getFirst()),
                                () -> false).status());
            }
            try (var reopened = store.open(identity)) {
                ProjectIndexView view = reopened.view().orElseThrow();
                assertEquals(0, view.committedJournalEntriesForTests(),
                        "A journal past its byte threshold must be folded");
                assertEquals("two",
                        view.contribution(stamp.path()).orElseThrow()
                                .definitions().getFirst().comment());
            }
        }
    }

    @Test
    void currentOnlyInvalidationIsReceiptGuardedAndKeepsPayload(
            @TempDir Path directory) throws Exception {
        ProjectIndexData source = ProjectIndexFixtures.snapshotWithAllMetaKinds();
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(source.manifest());
        ProjectFileStamp stamp = source.manifest().files().getFirst();
        Path base;
        Path journal;

        try (var store = new ProjectIndexStore(directory)) {
            store.publish(source, () -> false);
            ProjectIndexRevision stale;
            ProjectIndexRevision appended;
            try (var opened = store.open(identity)) {
                ProjectIndexView view = opened.view().orElseThrow();
                stale = view.revision();
                base = view.basePathForTests();
                journal = view.journalPathForTests();
                appended = store.appendIncremental(view,
                        ProjectIndexDelta.Change.replace(stamp,
                                ProjectIndexFixtures
                                        .withDefinitionComment("new")
                                        .files().getFirst()),
                        () -> false).revision();
            }

            assertFalse(store.invalidateCurrent(stale));
            assertTrue(Files.isRegularFile(
                    store.currentPathForTests()));
            assertTrue(store.invalidateCurrent(appended));
            assertFalse(Files.exists(store.currentPathForTests()));
            assertTrue(Files.isRegularFile(base));
            assertTrue(Files.isRegularFile(journal));
            assertEquals(Status.MISS, store.open(identity).status());
        }
    }

    @Test
    void currentOnlyInvalidationDeletesUnreadableCurrentAndKeepsPayload(
            @TempDir Path directory) throws Exception {
        ProjectIndexData source = ProjectIndexFixtures.minimalSnapshot();
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(source.manifest());
        try (var store = new ProjectIndexStore(directory)) {
            store.publish(source, () -> false);
            ProjectIndexRevision revision;
            Path base;
            Path journal;
            try (var opened = store.open(identity)) {
                ProjectIndexView view = opened.view().orElseThrow();
                revision = view.revision();
                base = view.basePathForTests();
                journal = view.journalPathForTests();
            }
            Files.write(store.currentPathForTests(),
                    new byte[] {1, 2, 3},
                    StandardOpenOption.TRUNCATE_EXISTING);

            assertTrue(store.invalidateCurrent(revision));
            assertFalse(Files.exists(store.currentPathForTests()));
            assertTrue(Files.isRegularFile(base));
            assertTrue(Files.isRegularFile(journal));
        }
    }

    @Test
    void cleanAndAppendAcrossStoreInstancesCannotResurrectCurrent(
            @TempDir Path directory) throws Exception {
        ProjectIndexData source = ProjectIndexFixtures.snapshotWithAllMetaKinds();
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(source.manifest());
        ProjectFileStamp stamp = source.manifest().files().getFirst();
        try (var publishing = new ProjectIndexStore(directory)) {
            publishing.publish(source, () -> false);
        }

        CountDownLatch appendBeforeCurrent = new CountDownLatch(1);
        CountDownLatch releaseAppend = new CountDownLatch(1);
        CountDownLatch cleanAttempted = new CountDownLatch(1);
        var hook = new ProjectIndexStore.IoHook() {
            @Override
            public void at(ProjectIndexStore.IoPoint point)
                    throws IOException {
                if (point
                        == ProjectIndexStore.IoPoint.BEFORE_CURRENT_PUBLICATION) {
                    appendBeforeCurrent.countDown();
                    try {
                        if (!releaseAppend.await(10, TimeUnit.SECONDS)) {
                            throw new IOException(
                                    "Timed out waiting to release append");
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IOException(ex);
                    }
                }
            }
        };
        try (var appending = new ProjectIndexStore(directory,
                ProjectIndexStore.DEFAULT_CACHE_BYTES, hook,
                ProjectIndexStore.DEFAULT_COMPACTION_BYTES);
                var opened = appending.open(identity);
                var executor = Executors.newFixedThreadPool(2)) {
            ProjectIndexView staleView = opened.view().orElseThrow();
            var append = executor.submit(() -> appending.appendIncremental(
                    staleView, ProjectIndexDelta.Change.replace(stamp,
                            ProjectIndexFixtures
                                    .withDefinitionComment("race")
                                    .files().getFirst()),
                    () -> false));
            assertTrue(appendBeforeCurrent.await(10, TimeUnit.SECONDS));
            var clean = executor.submit(() -> {
                cleanAttempted.countDown();
                try (var cleaner = new ProjectIndexStore(directory)) {
                    cleaner.clean();
                }
                return null;
            });

            assertTrue(cleanAttempted.await(10, TimeUnit.SECONDS));
            assertFalse(clean.isDone(),
                    "Clean must serialize behind current publication");
            releaseAppend.countDown();
            assertEquals(
                    ProjectIndexStore.IncrementalAppendStatus.APPENDED,
                    append.get(10, TimeUnit.SECONDS).status());
            clean.get(10, TimeUnit.SECONDS);
            assertFalse(Files.exists(
                    appending.currentPathForTests()));
            assertThrows(IOException.class,
                    () -> appending.appendIncremental(
                            staleView,
                            ProjectIndexDelta.Change.replace(stamp,
                                    ProjectIndexFixtures
                                            .withDefinitionComment("stale")
                                            .files().getFirst()),
                            () -> false));
        }
    }

    @Test
    void postCurrentHousekeepingFailureCannotInvalidateNewerWinner(
            @TempDir Path directory) throws Exception {
        ProjectIndexData first =
                ProjectIndexFixtures.withDefinitionComment("older");
        ProjectIndexData second =
                ProjectIndexFixtures.withDefinitionComment("newer");
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(first.manifest());
        var hook = new ProjectIndexStore.IoHook() {
            @Override
            public void at(ProjectIndexStore.IoPoint point)
                    throws IOException {
                if (point
                        == ProjectIndexStore.IoPoint.AFTER_CURRENT_PUBLICATION) {
                    throw new IllegalStateException(
                            "simulated housekeeping failure");
                }
            }
        };
        ProjectIndexStore.PublishOutcome older;
        try (var store = new ProjectIndexStore(directory,
                ProjectIndexStore.DEFAULT_CACHE_BYTES, hook,
                ProjectIndexStore.DEFAULT_COMPACTION_BYTES)) {
            older = store.publishWithReceipt(first, () -> false);
            assertEquals(ProjectIndexStore.PublishResult.PUBLISHED,
                    older.status());
        }
        try (var winner = new ProjectIndexStore(directory)) {
            ProjectIndexStore.PublishOutcome newer =
                    winner.publishWithReceipt(second, () -> false);
            assertEquals(ProjectIndexStore.PublishResult.PUBLISHED,
                    newer.status());
            assertFalse(winner.invalidateCurrent(older.revision()));
            try (var opened = winner.open(identity)) {
                assertEquals("newer", opened.view().orElseThrow()
                        .contribution(second.files().getFirst().path())
                        .orElseThrow().definitions().getFirst()
                        .comment());
            }
        }
    }

    @Test
    void ambiguousCurrentMoveFailureRetainsPublishedPayload(
            @TempDir Path directory) throws Exception {
        ProjectIndexData source = ProjectIndexFixtures.minimalSnapshot();
        var hook = new ProjectIndexStore.IoHook() {
            @Override
            public void at(ProjectIndexStore.IoPoint point)
                    throws IOException {
                if (point == ProjectIndexStore.IoPoint.AFTER_CURRENT_MOVE) {
                    Files.write(directory.resolve("current"),
                            new byte[] {1, 2, 3},
                            StandardOpenOption.TRUNCATE_EXISTING);
                    throw new IOException(
                            "simulated ambiguous move result");
                }
            }
        };
        try (var store = new ProjectIndexStore(directory,
                ProjectIndexStore.DEFAULT_CACHE_BYTES, hook,
                ProjectIndexStore.DEFAULT_COMPACTION_BYTES)) {
            assertThrows(IOException.class,
                    () -> store.publishWithReceipt(source,
                            () -> false));
        }

        try (var paths = Files.list(directory)) {
            List<String> names = paths
                    .map(path -> path.getFileName().toString())
                    .toList();
            assertEquals(1, names.stream()
                    .filter(name -> name.startsWith("base-")
                            && name.endsWith(".pgi"))
                    .count());
            assertEquals(1, names.stream()
                    .filter(name -> name.startsWith("journal-")
                            && name.endsWith(".pij"))
                    .count());
        }
    }

    @Test
    void exactCurrentAfterMoveExceptionStillReturnsReceipt(
            @TempDir Path directory) throws Exception {
        ProjectIndexData source =
                ProjectIndexFixtures.minimalSnapshot();
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(source.manifest());
        ProjectIndexStore.IoHook hook = point -> {
            if (point == ProjectIndexStore.IoPoint.AFTER_CURRENT_MOVE) {
                throw new IOException(
                        "simulated lost move acknowledgement");
            }
        };
        try (var store = new ProjectIndexStore(directory,
                ProjectIndexStore.DEFAULT_CACHE_BYTES, hook,
                ProjectIndexStore.DEFAULT_COMPACTION_BYTES)) {
            ProjectIndexStore.PublishOutcome outcome =
                    store.publishWithReceipt(source,
                            () -> false);

            assertEquals(ProjectIndexStore.PublishResult.PUBLISHED,
                    outcome.status());
            assertNotNull(outcome.revision());
            try (var opened = store.open(identity)) {
                assertEquals(Status.HIT, opened.status());
                assertEquals(outcome.revision(),
                        opened.view().orElseThrow().revision());
            }
        }
    }

    @Test
    void incrementalPostCurrentHousekeepingFailureStillReturnsReceipt(
            @TempDir Path directory) throws Exception {
        ProjectIndexData source = ProjectIndexFixtures.snapshotWithAllMetaKinds();
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(source.manifest());
        try (var publishing = new ProjectIndexStore(directory)) {
            publishing.publish(source, () -> false);
        }
        var hook = new ProjectIndexStore.IoHook() {
            @Override
            public void at(ProjectIndexStore.IoPoint point)
                    throws IOException {
                if (point
                        == ProjectIndexStore.IoPoint.AFTER_CURRENT_PUBLICATION) {
                    throw new IOException(
                            "simulated housekeeping failure");
                }
            }
        };
        try (var store = new ProjectIndexStore(directory,
                ProjectIndexStore.DEFAULT_CACHE_BYTES, hook,
                ProjectIndexStore.DEFAULT_COMPACTION_BYTES);
                var opened = store.open(identity)) {
            ProjectIndexView view = opened.view().orElseThrow();
            ProjectFileStamp stamp =
                    source.manifest().files().getFirst();
            var result = store.appendIncremental(view,
                    ProjectIndexDelta.Change.replace(stamp,
                            ProjectIndexFixtures
                                    .withDefinitionComment("committed")
                                    .files().getFirst()),
                    () -> false);
            assertEquals(
                    ProjectIndexStore.IncrementalAppendStatus.APPENDED,
                    result.status());
            assertNotNull(result.revision());
        }
    }

    @Test
    void incrementalReceiptDoesNotReadViewAfterCurrentPublication(
            @TempDir Path directory) throws Exception {
        ProjectIndexData source =
                ProjectIndexFixtures.snapshotWithAllMetaKinds();
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(source.manifest());
        AtomicBoolean armed = new AtomicBoolean();
        var viewToClose =
                new AtomicReference<ProjectIndexView>();
        ProjectIndexStore.IoHook hook = point -> {
            if (armed.get()
                    && point
                            == ProjectIndexStore.IoPoint.AFTER_CURRENT_MOVE) {
                viewToClose.get().close();
            }
        };
        try (var store = new ProjectIndexStore(directory,
                ProjectIndexStore.DEFAULT_CACHE_BYTES, hook,
                ProjectIndexStore.DEFAULT_COMPACTION_BYTES)) {
            store.publish(source, () -> false);
            try (var opened = store.open(identity)) {
                ProjectIndexView view =
                        opened.view().orElseThrow();
                viewToClose.set(view);
                armed.set(true);
                ProjectFileStamp stamp =
                        source.manifest().files().getFirst();

                var result = store.appendIncremental(view,
                        ProjectIndexDelta.Change.replace(stamp,
                                ProjectIndexFixtures
                                        .withDefinitionComment(
                                                "committed")
                                        .files().getFirst()),
                        () -> false);

                assertEquals(
                        ProjectIndexStore.IncrementalAppendStatus.APPENDED,
                        result.status());
                assertEquals(identity,
                        result.revision().identity());
            }
        }
    }

    @Test
    void coldPublishAndProcessStyleWarmOpenAreLazyAndByteExact(@TempDir Path directory)
            throws Exception {
        ProjectIndexData source = ProjectIndexFixtures.snapshotWithAllMetaKinds();
        ProjectIndexIdentity identity = ProjectIndexIdentity.from(source.manifest());
        Path current;
        long currentMtime;

        try (var store = new ProjectIndexStore(directory)) {
            assertEquals(ProjectIndexStore.PublishResult.PUBLISHED,
                    store.publish(source, () -> false));
            current = store.currentPathForTests();
            currentMtime = Files.getLastModifiedTime(current).toMillis();
        }

        try (var store = new ProjectIndexStore(directory);
                var result = store.open(identity)) {
            assertEquals(Status.HIT, result.status());
            ProjectIndexView view = result.view().orElseThrow();
            ProjectIndexData canonical = ProjectIndexCodec.decodeBase(
                    ProjectIndexCodec.encodeBase(source), 32L << 20);
            assertEquals(0, view.decodedContributionCount());
            assertEquals(canonical.manifest(), view.manifest());
            assertEquals(canonical.manifest().files(), view.fileStamps());
            assertArrayEquals(ProjectIndexFixtures.canonicalBytes(source),
                    ProjectIndexFixtures.canonicalBytes(view.materialize()));
            assertEquals(source.files().size(), view.decodedContributionCount());
            assertTrue(view.cachedBytes() <= ProjectIndexStore.DEFAULT_CACHE_BYTES);
        }
        assertEquals(currentMtime, Files.getLastModifiedTime(current).toMillis(),
                "A warm open must not rewrite the current generation");
    }

    @Test
    void everyIdentityFieldIsFailClosed(@TempDir Path directory) throws Exception {
        ProjectIndexData source = ProjectIndexFixtures.snapshotWithAllMetaKinds();
        ProjectIndexIdentity exact = ProjectIndexIdentity.from(source.manifest());
        try (var store = new ProjectIndexStore(directory)) {
            store.publish(source, () -> false);
            List<ProjectIndexIdentity> stale = List.of(
                    new ProjectIndexIdentity(exact.parserAbi() + 1, exact.coreVersion(),
                            exact.uiVersion(), exact.databaseType(), exact.projectIdentity(),
                            exact.configSha256()),
                    new ProjectIndexIdentity(exact.parserAbi(), exact.coreVersion() + ".x",
                            exact.uiVersion(), exact.databaseType(), exact.projectIdentity(),
                            exact.configSha256()),
                    new ProjectIndexIdentity(exact.parserAbi(), exact.coreVersion(),
                            exact.uiVersion() + ".x", exact.databaseType(),
                            exact.projectIdentity(), exact.configSha256()),
                    new ProjectIndexIdentity(exact.parserAbi(), exact.coreVersion(),
                            exact.uiVersion(), DatabaseType.MS, exact.projectIdentity(),
                            exact.configSha256()),
                    new ProjectIndexIdentity(exact.parserAbi(), exact.coreVersion(),
                            exact.uiVersion(), exact.databaseType(), "0".repeat(64),
                            exact.configSha256()),
                    new ProjectIndexIdentity(exact.parserAbi(), exact.coreVersion(),
                            exact.uiVersion(), exact.databaseType(), exact.projectIdentity(),
                            ProjectIndexFixtures.sha256("different-config")));
            for (ProjectIndexIdentity mismatch : stale) {
                try (var result = store.open(mismatch)) {
                    assertEquals(Status.STALE, result.status());
                    assertTrue(result.view().isEmpty());
                }
            }
        }
    }

    @Test
    void journalOverlayAddsReplacesAndDeletesWithoutRewritingBase(@TempDir Path directory)
            throws Exception {
        ProjectIndexData source = ProjectIndexFixtures.snapshotWithAllMetaKinds();
        ProjectIndexIdentity identity = ProjectIndexIdentity.from(source.manifest());
        FileContribution replacement = ProjectIndexFixtures
                .withDefinitionComment("journal replacement").files().getFirst();
        ProjectFileStamp replacementStamp = source.manifest().files().getFirst();
        IndexPathRef deleted = source.files().get(2).path();
        ProjectIndexData additionSource = ProjectIndexFixtures.minimalSnapshot();
        ProjectFileStamp addedStamp = additionSource.manifest().files().getFirst();
        FileContribution added = additionSource.files().getFirst();
        long baseSize;

        try (var store = new ProjectIndexStore(directory)) {
            store.publish(source, () -> false);
            try (var opened = store.open(identity)) {
                ProjectIndexView view = opened.view().orElseThrow();
                baseSize = Files.size(view.basePathForTests());
                ProjectIndexDelta delta = new ProjectIndexDelta(List.of(
                        ProjectIndexDelta.Change.replace(replacementStamp, replacement),
                        ProjectIndexDelta.Change.delete(deleted),
                        ProjectIndexDelta.Change.replace(addedStamp, added)));
                assertEquals(ProjectIndexStore.AppendResult.APPENDED,
                        store.append(view, delta, () -> false));
            }
        }

        try (var store = new ProjectIndexStore(directory);
                var opened = store.open(identity)) {
            ProjectIndexView view = opened.view().orElseThrow();
            assertEquals("journal replacement",
                    view.contribution(replacement.path()).orElseThrow()
                            .definitions().getFirst().comment());
            assertTrue(view.contribution(deleted).isEmpty());
            assertEquals(added, view.contribution(added.path()).orElseThrow());
            assertEquals(source.files().size(), view.fileStamps().size());
            assertEquals(baseSize, Files.size(view.basePathForTests()));
        }
    }

    /**
     * The incremental path carries an addition too. It only ever admits
     * REPLACE, and a path the base never had is still a replacement of nothing:
     * the overlay is keyed by path and does not care whether the key existed.
     */
    @Test
    void incrementalAppendIntroducesAPathTheBaseDidNotHave(
            @TempDir Path directory) throws Exception {
        ProjectIndexData source = ProjectIndexFixtures.snapshotWithAllMetaKinds();
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(source.manifest());
        ProjectIndexData additionSource = ProjectIndexFixtures.minimalSnapshot();
        ProjectFileStamp addedStamp = additionSource.manifest().files().getFirst();
        FileContribution added = additionSource.files().getFirst();

        try (var store = new ProjectIndexStore(directory)) {
            store.publish(source, () -> false);
            try (var opened = store.open(identity)) {
                ProjectIndexView view = opened.view().orElseThrow();
                assertTrue(view.contribution(added.path()).isEmpty(),
                        "the fixture has to be a path the base does not hold");
                assertEquals(0, view.committedJournalEntriesForTests());

                assertEquals(
                        ProjectIndexStore.IncrementalAppendStatus.APPENDED,
                        store.appendIncremental(view,
                                ProjectIndexDelta.Change.replace(
                                        addedStamp, added),
                                () -> false).status());
            }
        }

        try (var store = new ProjectIndexStore(directory);
                var opened = store.open(identity)) {
            ProjectIndexView view = opened.view().orElseThrow();
            assertEquals(added, view.contribution(added.path()).orElseThrow());
            assertEquals(1, view.committedJournalEntriesForTests());
            assertEquals(source.files().size() + 1, view.fileStamps().size());
            assertTrue(view.fileStamps().contains(addedStamp),
                    "a file the index now holds has to appear in its stamps");
        }
    }

    /**
     * Folding the journal into a fresh base has to carry the added path with
     * it. This is where an addition could quietly disappear: compaction writes
     * a new container from the merged source, and a path that exists only in
     * the journal is exactly the case a replay keyed on the base would miss.
     */
    @Test
    void compactionKeepsAPathThatOnlyTheJournalHad(@TempDir Path directory)
            throws Exception {
        ProjectIndexData source = ProjectIndexFixtures.snapshotWithAllMetaKinds();
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(source.manifest());
        ProjectFileStamp replaced = source.manifest().files().getFirst();
        ProjectIndexData additionSource = ProjectIndexFixtures.minimalSnapshot();
        ProjectFileStamp addedStamp = additionSource.manifest().files().getFirst();
        FileContribution added = additionSource.files().getFirst();

        try (var store = new ProjectIndexStore(directory)) {
            store.publish(source, () -> false);
            try (var opened = store.open(identity)) {
                assertEquals(
                        ProjectIndexStore.IncrementalAppendStatus.APPENDED,
                        store.appendIncremental(opened.view().orElseThrow(),
                                ProjectIndexDelta.Change.replace(
                                        addedStamp, added),
                                () -> false).status());
            }
            for (int i = 0; i < 40; i++) {
                try (var opened = store.open(identity)) {
                    FileContribution replacement = ProjectIndexFixtures
                            .withDefinitionComment("past-the-limit-" + i)
                            .files().getFirst();
                    assertEquals(
                            ProjectIndexStore.IncrementalAppendStatus.APPENDED,
                            store.appendIncremental(
                                    opened.view().orElseThrow(),
                                    ProjectIndexDelta.Change.replace(
                                            replaced, replacement),
                                    () -> false).status());
                }
            }
        }

        try (var store = new ProjectIndexStore(directory);
                var opened = store.open(identity)) {
            ProjectIndexView view = opened.view().orElseThrow();
            assertTrue(view.committedJournalEntriesForTests() < 41,
                    "41 appends without a fold would mean the journal never"
                            + " compacted and this test proved nothing");
            assertEquals(added, view.contribution(added.path()).orElseThrow(),
                    "the added path has to survive the fold unchanged");
            assertEquals(source.files().size() + 1, view.fileStamps().size());
            assertEquals("past-the-limit-39",
                    view.contribution(replaced.path()).orElseThrow()
                            .definitions().getFirst().comment());
        }
    }

    @Test
    void sequentialJournalReplacementsStayBoundedAfterAutomaticCompaction(
            @TempDir Path directory) throws Exception {
        ProjectIndexData source = ProjectIndexFixtures.snapshotWithAllMetaKinds();
        ProjectIndexIdentity identity = ProjectIndexIdentity.from(source.manifest());
        ProjectFileStamp stamp = source.manifest().files().getFirst();

        try (var store = new ProjectIndexStore(directory)) {
            store.publish(source, () -> false);
            for (int i = 0; i < 512; i++) {
                FileContribution replacement = ProjectIndexFixtures
                        .withDefinitionComment("journal-" + i).files().getFirst();
                try (var opened = store.open(identity)) {
                    assertEquals(ProjectIndexStore.AppendResult.APPENDED,
                            store.append(opened.view().orElseThrow(),
                                    new ProjectIndexDelta(List.of(
                                            ProjectIndexDelta.Change.replace(
                                                    stamp, replacement))),
                                    () -> false));
                }
            }
        }

        try (var store = new ProjectIndexStore(directory);
                var opened = store.open(identity)) {
            ProjectIndexView view = opened.view().orElseThrow();
            assertTrue(view.committedJournalEntriesForTests() <= 32,
                    "The committed journal entry count must have a hard cap");
            assertEquals(0, view.cacheBlocksRead(),
                    "Warm open must not decode journal replacement codecs");
            long beforeLookup = view.cacheBlocksRead();
            ReferenceMatchKey key = ReferenceMatchKey.from(
                    source.files().getFirst().locations().getFirst());
            view.matches(key);
            assertTrue(view.cacheBlocksRead() - beforeLookup <= 40,
                    "A point lookup must stay bounded after many replacements");
            assertEquals("journal-511",
                    view.contribution(stamp.path()).orElseThrow()
                            .definitions().getFirst().comment());
        }
    }

    @Test
    void lazyDerivedLookupsMatchTheFullCodecAndStayDeterministic(@TempDir Path directory)
            throws Exception {
        ProjectIndexData source = ProjectIndexCodec.decodeBase(ProjectIndexCodec.encodeBase(
                ProjectIndexFixtures.snapshotWithAllMetaKinds()), 32L << 20);
        PackedLocation reference = source.files().stream()
                .flatMap(file -> file.locations().stream())
                .filter(location -> location.locationType()
                        == org.pgcodekeeper.core.database.api.schema.ObjectLocation.LocationType.REFERENCE)
                .findFirst().orElseThrow();
        ReferenceMatchKey key = ReferenceMatchKey.from(reference);
        List<String> expectedDefinitions = source.files().stream()
                .flatMap(file -> file.definitions().stream())
                .filter(definition -> definition.object().reference() != null
                        && definition.object().reference().type() != null
                        && ReferenceMatchKey.from(definition.object()).equals(key))
                .map(PackedDefinition::canonicalForm).sorted().toList();
        List<String> expectedLocations = source.files().stream()
                .flatMap(file -> file.locations().stream())
                .filter(location -> location.reference() != null
                        && location.reference().type() != null
                        && ReferenceMatchKey.from(location).equals(key))
                .map(PackedLocation::canonicalForm).sorted().toList();

        try (var store = new ProjectIndexStore(directory)) {
            store.publish(source, () -> false);
            try (var opened = store.open(ProjectIndexIdentity.from(source.manifest()))) {
                ProjectIndexView view = opened.view().orElseThrow();
                ProjectIndexMatches matches = view.matches(key);
                assertEquals(expectedDefinitions,
                        matches.definitions().stream()
                                .map(PackedDefinition::canonicalForm).sorted().toList());
                assertEquals(expectedLocations,
                        matches.locations().stream()
                                .map(PackedLocation::canonicalForm).sorted().toList());
                assertEquals(matches, view.matches(key));
                assertEquals(List.of("calculate"),
                        view.completion("CAL").stream()
                                .map(PackedDefinition::bareName).toList());
                assertEquals(Set.of(reference.path()), view.reverseDependencies(key));
                assertEquals(0, view.decodedContributionCount(),
                        "Derived slices must not decode whole file contributions");
            }
        }
    }

    @Test
    void largeWarmOpenAndPointLookupsReadOnlyBoundedCodecBlocks(
            @TempDir Path directory) throws Exception {
        ProjectIndexData source = ProjectIndexFixtures.largeIndexedProject(40_000);
        ProjectIndexIdentity identity = ProjectIndexIdentity.from(source.manifest());
        FileContribution target = source.files().get(39_731);
        ReferenceMatchKey matchKey =
                ReferenceMatchKey.from(target.locations().getFirst());

        try (var store = new ProjectIndexStore(directory)) {
            store.publish(source, () -> false);
        }

        try (var store = new ProjectIndexStore(directory);
                var opened = store.open(identity)) {
            ProjectIndexView view = opened.view().orElseThrow();
            assertEquals(0, view.cacheBlocksRead());
            assertEquals(0, view.cacheReadBytes());
            assertEquals(0, view.cachedBytes());
            assertTrue(view.metadataBytes() <= 16L << 20);
            assertTrue(view.canonicalCodecBlocks() >= 64,
                    "The fixture must be large enough to detect a linear scan: "
                            + view.canonicalCodecBlocks());

            ProjectIndexMatches matches = view.matches(matchKey);
            assertEquals(1, matches.locations().size());
            assertBoundedPointRead(view.cacheBlocksRead(),
                    view.canonicalCodecBlocks(), "match lookup");
            assertTrue(view.cacheReadBytes()
                    <= view.cacheBlocksRead() * ProjectIndexBlockCache.BLOCK_BYTES);
            assertTrue(view.cachedBytes() <= ProjectIndexStore.DEFAULT_CACHE_BYTES);
        }

        try (var store = new ProjectIndexStore(directory);
                var opened = store.open(identity)) {
            ProjectIndexView view = opened.view().orElseThrow();
            assertEquals(List.of(target.definitions().getFirst()),
                    view.completion(target.definitions().getFirst().bareName()));
            assertBoundedPointRead(view.cacheBlocksRead(),
                    view.canonicalCodecBlocks(), "completion lookup");
            assertTrue(view.cachedBytes() <= ProjectIndexStore.DEFAULT_CACHE_BYTES);
        }

        try (var store = new ProjectIndexStore(directory);
                var opened = store.open(identity)) {
            ProjectIndexView view = opened.view().orElseThrow();
            assertEquals(target,
                    view.contribution(target.path()).orElseThrow());
            assertBoundedPointRead(view.cacheBlocksRead(),
                    view.canonicalCodecBlocks(), "file contribution lookup");
            assertTrue(view.cachedBytes() <= ProjectIndexStore.DEFAULT_CACHE_BYTES);
        }
    }

    @Test
    void codecCorruptionIsDetectedOnlyWhenTheAffectedBlockIsTouched(
            @TempDir Path directory) throws Exception {
        ProjectIndexData source = ProjectIndexFixtures.snapshotWithAllMetaKinds();
        ProjectIndexIdentity identity = ProjectIndexIdentity.from(source.manifest());
        Path base;
        long definitionBlock;
        try (var store = new ProjectIndexStore(directory)) {
            store.publish(source, () -> false);
            try (var opened = store.open(identity)) {
                ProjectIndexView view = opened.view().orElseThrow();
                base = view.basePathForTests();
                definitionBlock = view.firstDefinitionBlockOffsetForTests();
                assertTrue(definitionBlock >= view.codecOffsetForTests());
                assertEquals(0, view.cacheBlocksRead());
            }
        }
        try (FileChannel channel = FileChannel.open(base,
                StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ByteBuffer value = ByteBuffer.allocate(1);
            assertEquals(1, channel.read(value, definitionBlock));
            value.flip();
            value.put(0, (byte) (value.get(0) ^ 1));
            assertEquals(1, channel.write(value, definitionBlock));
        }

        try (var store = new ProjectIndexStore(directory);
                var opened = store.open(identity)) {
            assertEquals(Status.HIT, opened.status(),
                    "Warm open must not scan or checksum canonical codec blocks");
            ProjectIndexView view = opened.view().orElseThrow();
            assertEquals(0, view.cacheBlocksRead());
            assertThrows(ProjectIndexFormatException.class,
                    () -> view.contribution(source.files().getFirst().path()));
        }
    }

    @Test
    void compactionRejectsCorruptPackedBlocksWithoutPublishing(
            @TempDir Path directory) throws Exception {
        ProjectIndexData source = ProjectIndexFixtures.snapshotWithAllMetaKinds();
        ProjectIndexIdentity identity = ProjectIndexIdentity.from(source.manifest());
        Path base;
        long definitionBlock;
        String publicationId;
        try (var store = new ProjectIndexStore(directory)) {
            store.publish(source, () -> false);
            try (var opened = store.open(identity)) {
                ProjectIndexView view = opened.view().orElseThrow();
                base = view.basePathForTests();
                definitionBlock = view.firstDefinitionBlockOffsetForTests();
                publicationId = view.publicationId();
            }
        }
        try (FileChannel channel = FileChannel.open(base,
                StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ByteBuffer value = ByteBuffer.allocate(1);
            assertEquals(1, channel.read(value, definitionBlock));
            value.flip();
            value.put(0, (byte) (value.get(0) ^ 1));
            assertEquals(1, channel.write(value, definitionBlock));
        }

        try (var store = new ProjectIndexStore(directory);
                var opened = store.open(identity)) {
            ProjectIndexView view = opened.view().orElseThrow();
            assertThrows(ProjectIndexFormatException.class,
                    () -> store.compact(view, () -> false));
        }
        try (var store = new ProjectIndexStore(directory);
                var opened = store.open(identity)) {
            assertEquals(Status.HIT, opened.status());
            assertEquals(publicationId,
                    opened.view().orElseThrow().publicationId());
        }
    }

    @Test
    void journalReplacementPayloadIsChecksummedLazily(
            @TempDir Path directory) throws Exception {
        ProjectIndexData source = ProjectIndexFixtures.snapshotWithAllMetaKinds();
        ProjectIndexIdentity identity = ProjectIndexIdentity.from(source.manifest());
        ProjectFileStamp stamp = source.manifest().files().getFirst();
        FileContribution replacement = ProjectIndexFixtures
                .withDefinitionComment("journal replacement").files().getFirst();
        Path journal;

        try (var store = new ProjectIndexStore(directory)) {
            store.publish(source, () -> false);
            try (var opened = store.open(identity)) {
                ProjectIndexView view = opened.view().orElseThrow();
                assertEquals(ProjectIndexStore.AppendResult.APPENDED,
                        store.append(view, new ProjectIndexDelta(List.of(
                                ProjectIndexDelta.Change.replace(
                                        stamp, replacement))), () -> false));
                journal = view.journalPathForTests();
            }
        }

        byte[] pathBytes = stamp.path().relativePath()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] journalBytes = Files.readAllBytes(journal);
        int pathOffset = indexOf(journalBytes, pathBytes);
        assertTrue(pathOffset >= 0);
        long payloadStart = pathOffset + pathBytes.length;
        try (FileChannel channel = FileChannel.open(journal,
                StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ProjectIndexContainer.Opened container = ProjectIndexContainer.open(
                    channel, payloadStart, channel.size() - payloadStart - 8);
            ProjectIndexLocator.BlockEntry block = container.locator()
                    .blocks(SectionType.DEFINITIONS).block(0);
            long corruptAt = container.codecOffset() + block.offset();
            ByteBuffer value = ByteBuffer.allocate(1);
            assertEquals(1, channel.read(value, corruptAt));
            value.flip();
            value.put(0, (byte) (value.get(0) ^ 1));
            assertEquals(1, channel.write(value, corruptAt));
        }

        try (var store = new ProjectIndexStore(directory);
                var opened = store.open(identity)) {
            assertEquals(Status.HIT, opened.status(),
                    "Warm open must not checksum replacement payload blocks");
            ProjectIndexView view = opened.view().orElseThrow();
            assertEquals(0, view.cacheBlocksRead());
            assertThrows(ProjectIndexFormatException.class,
                    () -> view.contribution(stamp.path()));
        }
    }

    @Test
    void uncommittedJournalTailAndInterruptedAppendKeepPriorGeneration(@TempDir Path directory)
            throws Exception {
        ProjectIndexData source = ProjectIndexFixtures.snapshotWithAllMetaKinds();
        ProjectIndexIdentity identity = ProjectIndexIdentity.from(source.manifest());
        try (var store = new ProjectIndexStore(directory)) {
            store.publish(source, () -> false);
            try (var opened = store.open(identity)) {
                ProjectIndexView view = opened.view().orElseThrow();
                try (FileChannel channel = FileChannel.open(view.journalPathForTests(),
                        StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                    channel.write(ByteBuffer.wrap(new byte[127]));
                    channel.force(true);
                }
            }
        }
        try (var store = new ProjectIndexStore(directory);
                var opened = store.open(identity)) {
            assertEquals(Status.HIT, opened.status());
            assertEquals(source.files().size(),
                    opened.view().orElseThrow().fileStamps().size());
        }

        var hook = new ProjectIndexStore.IoHook() {
            @Override
            public void at(ProjectIndexStore.IoPoint point) throws IOException {
                if (point == ProjectIndexStore.IoPoint.BEFORE_CURRENT_PUBLICATION) {
                    throw new IOException("simulated publication failure");
                }
            }
        };
        try (var failing = new ProjectIndexStore(directory,
                ProjectIndexStore.DEFAULT_CACHE_BYTES, hook,
                ProjectIndexStore.DEFAULT_COMPACTION_BYTES)) {
            try (var opened = failing.open(identity)) {
                ProjectIndexView view = opened.view().orElseThrow();
                assertThrows(IOException.class, () -> failing.append(view,
                        new ProjectIndexDelta(List.of(ProjectIndexDelta.Change.delete(
                                source.files().getFirst().path()))), () -> false));
            }
        }
        try (var store = new ProjectIndexStore(directory);
                var opened = store.open(identity)) {
            assertTrue(opened.view().orElseThrow()
                    .contribution(source.files().getFirst().path()).isPresent());
        }
    }

    @Test
    void cancellationAndPublishFailureDoNotReplaceCurrent(@TempDir Path directory)
            throws Exception {
        ProjectIndexData source = ProjectIndexFixtures.snapshotWithAllMetaKinds();
        ProjectIndexIdentity identity = ProjectIndexIdentity.from(source.manifest());
        try (var store = new ProjectIndexStore(directory)) {
            store.publish(source, () -> false);
            assertEquals(ProjectIndexStore.PublishResult.CANCELLED,
                    store.publish(ProjectIndexFixtures.minimalSnapshot(), () -> true));
        }
        try (var store = new ProjectIndexStore(directory);
                var opened = store.open(identity)) {
            assertEquals(Status.HIT, opened.status());
        }

        var hook = new ProjectIndexStore.IoHook() {
            @Override
            public void at(ProjectIndexStore.IoPoint point) throws IOException {
                if (point == ProjectIndexStore.IoPoint.BEFORE_CURRENT_PUBLICATION) {
                    throw new IOException("simulated publication failure");
                }
            }
        };
        try (var failing = new ProjectIndexStore(directory,
                ProjectIndexStore.DEFAULT_CACHE_BYTES, hook,
                ProjectIndexStore.DEFAULT_COMPACTION_BYTES)) {
            assertThrows(IOException.class,
                    () -> failing.publish(ProjectIndexFixtures.minimalSnapshot(), () -> false));
        }
        try (var store = new ProjectIndexStore(directory);
                var opened = store.open(identity)) {
            assertEquals(Status.HIT, opened.status());
        }
    }

    @Test
    void cancellationDuringLocatorReadDoesNotPublish(
            @TempDir Path directory) throws Exception {
        AtomicBoolean enteredLocatorRead = new AtomicBoolean();
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicBoolean cancelOnLocatorRead =
                new AtomicBoolean(true);
        ProjectIndexStore.IoHook hook = point -> {
            if (point
                    == ProjectIndexStore.IoPoint
                            .LOCATOR_READ_CHUNK
                    && cancelOnLocatorRead.get()) {
                enteredLocatorRead.set(true);
                cancelled.set(true);
            }
        };
        ProjectIndexData source =
                ProjectIndexFixtures.snapshotWithAllMetaKinds();

        try (var store = new ProjectIndexStore(directory,
                ProjectIndexStore.DEFAULT_CACHE_BYTES, hook,
                ProjectIndexStore.DEFAULT_COMPACTION_BYTES)) {
            assertEquals(ProjectIndexStore.PublishResult.CANCELLED,
                    store.publish(source, cancelled::get));
            assertTrue(enteredLocatorRead.get(),
                    "Cancellation must be armed only after locator scanning starts");
            assertFalse(Files.exists(store.currentPathForTests()));

            cancelOnLocatorRead.set(false);
            cancelled.set(false);
            assertEquals(ProjectIndexStore.PublishResult.PUBLISHED,
                    store.publish(source, cancelled::get));
            assertTrue(Files.isRegularFile(
                    store.currentPathForTests()));
        }
    }

    @Test
    void corruptionDuringLocatorReadDoesNotPublishCurrent(
            @TempDir Path directory) throws Exception {
        AtomicBoolean corrupted = new AtomicBoolean();
        ProjectIndexStore.IoHook hook = point -> {
            if (point != ProjectIndexStore.IoPoint.LOCATOR_READ_CHUNK
                    || !corrupted.compareAndSet(false, true)) {
                return;
            }
            try (var paths = Files.newDirectoryStream(
                    directory, ".base-*.tmp")) {
                Path temporaryBase =
                        paths.iterator().next();
                long payloadOffset =
                        ProjectIndexContainer.HEADER_BYTES
                                + ProjectIndexFormat.HEADER_SIZE
                                + ProjectIndexFormat
                                        .SECTION_FRAME_SIZE;
                try (FileChannel channel = FileChannel.open(
                        temporaryBase, StandardOpenOption.READ,
                        StandardOpenOption.WRITE)) {
                    ByteBuffer value = ByteBuffer.allocate(1);
                    assertEquals(1,
                            channel.read(value, payloadOffset));
                    value.flip();
                    value.put(0,
                            (byte) (value.get(0) ^ 1));
                    assertEquals(1,
                            channel.write(value, payloadOffset));
                }
            }
        };
        ProjectIndexData source =
                ProjectIndexFixtures.snapshotWithAllMetaKinds();
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(source.manifest());
        byte[] currentBefore;
        try (var baseline = new ProjectIndexStore(directory)) {
            assertEquals(ProjectIndexStore.PublishResult.PUBLISHED,
                    baseline.publish(source, () -> false));
            currentBefore = Files.readAllBytes(
                    baseline.currentPathForTests());
        }

        try (var store = new ProjectIndexStore(directory,
                ProjectIndexStore.DEFAULT_CACHE_BYTES, hook,
                ProjectIndexStore.DEFAULT_COMPACTION_BYTES)) {
            assertThrows(ProjectIndexFormatException.class,
                    () -> store.publish(source, () -> false));
            assertTrue(corrupted.get());
            assertArrayEquals(currentBefore, Files.readAllBytes(
                    store.currentPathForTests()));
            try (var opened = store.open(identity)) {
                assertEquals(Status.HIT, opened.status());
            }
        }

        try (var retry = new ProjectIndexStore(directory)) {
            assertEquals(ProjectIndexStore.PublishResult.PUBLISHED,
                    retry.publish(source, () -> false));
        }
    }

    @Test
    void cancellationDuringCompactionWriteKeepsCommittedJournalGeneration(
            @TempDir Path directory) throws Exception {
        ProjectIndexData source = ProjectIndexFixtures.snapshotWithAllMetaKinds();
        ProjectIndexIdentity identity = ProjectIndexIdentity.from(source.manifest());
        IndexPathRef deleted = source.files().getLast().path();
        AtomicBoolean armed = new AtomicBoolean();
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicInteger writeChunks = new AtomicInteger();
        ProjectIndexStore.IoHook hook = point -> {
            if (armed.get() && point == ProjectIndexStore.IoPoint.BASE_WRITE_CHUNK
                    && writeChunks.incrementAndGet() == 3) {
                cancelled.set(true);
            }
        };

        try (var store = new ProjectIndexStore(directory,
                ProjectIndexStore.DEFAULT_CACHE_BYTES, hook, 1)) {
            store.publish(source, () -> false);
            try (var opened = store.open(identity)) {
                store.append(opened.view().orElseThrow(),
                        new ProjectIndexDelta(List.of(
                                ProjectIndexDelta.Change.delete(deleted))),
                        () -> false);
            }
            try (var opened = store.open(identity)) {
                armed.set(true);
                assertEquals(ProjectIndexStore.PublishResult.CANCELLED,
                        store.compact(opened.view().orElseThrow(), cancelled::get));
            }
        }

        try (var store = new ProjectIndexStore(directory);
                var opened = store.open(identity)) {
            assertEquals(Status.HIT, opened.status());
            assertTrue(opened.view().orElseThrow().contribution(deleted).isEmpty());
        }
    }

    @Test
    void staleViewCannotOverwriteNewerJournalDuringCompaction(
            @TempDir Path directory) throws Exception {
        ProjectIndexData source = ProjectIndexFixtures.snapshotWithAllMetaKinds();
        ProjectIndexIdentity identity = ProjectIndexIdentity.from(source.manifest());
        IndexPathRef deleted = source.files().getLast().path();

        try (var store = new ProjectIndexStore(directory)) {
            store.publish(source, () -> false);
            try (var opened = store.open(identity)) {
                ProjectIndexView stale = opened.view().orElseThrow();
                assertEquals(ProjectIndexStore.AppendResult.APPENDED,
                        store.append(stale, new ProjectIndexDelta(List.of(
                                ProjectIndexDelta.Change.delete(deleted))),
                                () -> false));
                assertThrows(IOException.class,
                        () -> store.compact(stale, () -> false));
            }
        }

        try (var store = new ProjectIndexStore(directory);
                var opened = store.open(identity)) {
            assertTrue(opened.view().orElseThrow().contribution(deleted).isEmpty());
        }
    }

    @Test
    void cancellationAndWriteFailureDuringJournalAppendLeaveCommittedViewUntouched(
            @TempDir Path directory) throws Exception {
        ProjectIndexData source = ProjectIndexFixtures.snapshotWithAllMetaKinds();
        ProjectIndexIdentity identity = ProjectIndexIdentity.from(source.manifest());
        IndexPathRef path = source.files().getFirst().path();
        try (var store = new ProjectIndexStore(directory)) {
            store.publish(source, () -> false);
            AtomicInteger probes = new AtomicInteger();
            try (var opened = store.open(identity)) {
                assertEquals(ProjectIndexStore.AppendResult.CANCELLED,
                        store.append(opened.view().orElseThrow(),
                                new ProjectIndexDelta(List.of(
                                        ProjectIndexDelta.Change.replace(
                                                source.manifest().files().getFirst(),
                                                source.files().getFirst()))),
                                () -> probes.incrementAndGet() >= 5));
            }
            try (var opened = store.open(identity)) {
                assertTrue(opened.view().orElseThrow().contribution(path).isPresent());
            }
        }

        var hook = new ProjectIndexStore.IoHook() {
            @Override
            public void at(ProjectIndexStore.IoPoint point) throws IOException {
                if (point == ProjectIndexStore.IoPoint.JOURNAL_WRITE_CHUNK) {
                    throw new IOException("simulated journal failure");
                }
            }
        };
        try (var store = new ProjectIndexStore(directory,
                ProjectIndexStore.DEFAULT_CACHE_BYTES, hook,
                ProjectIndexStore.DEFAULT_COMPACTION_BYTES);
                var opened = store.open(identity)) {
            assertThrows(IOException.class, () -> store.append(opened.view().orElseThrow(),
                    new ProjectIndexDelta(List.of(ProjectIndexDelta.Change.replace(
                            source.manifest().files().getFirst(),
                            source.files().getFirst()))), () -> false));
        }
        try (var store = new ProjectIndexStore(directory);
                var opened = store.open(identity)) {
            assertEquals(Status.HIT, opened.status());
            assertTrue(opened.view().orElseThrow().contribution(path).isPresent());
        }
    }

    @Test
    void corruptAndTruncatedBaseOrCommittedJournalFailClosed(@TempDir Path directory)
            throws Exception {
        ProjectIndexData source = ProjectIndexFixtures.snapshotWithAllMetaKinds();
        ProjectIndexIdentity identity = ProjectIndexIdentity.from(source.manifest());
        Path base;
        try (var store = new ProjectIndexStore(directory)) {
            store.publish(source, () -> false);
            try (var opened = store.open(identity)) {
                base = opened.view().orElseThrow().basePathForTests();
            }
        }
        try (FileChannel channel = FileChannel.open(base, StandardOpenOption.WRITE)) {
            channel.truncate(11);
        }
        try (var store = new ProjectIndexStore(directory);
                var opened = store.open(identity)) {
            assertEquals(Status.CORRUPT, opened.status());
        }

        try (var store = new ProjectIndexStore(directory)) {
            store.publish(source, () -> false);
            try (var opened = store.open(identity)) {
                base = opened.view().orElseThrow().basePathForTests();
            }
        }
        try (FileChannel channel = FileChannel.open(base,
                StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            long locatorTail = channel.size() - 1;
            ByteBuffer value = ByteBuffer.allocate(1);
            assertEquals(1, channel.read(value, locatorTail));
            value.flip();
            value.put(0, (byte) (value.get(0) ^ 1));
            assertEquals(1, channel.write(value, locatorTail));
        }
        try (var store = new ProjectIndexStore(directory);
                var opened = store.open(identity)) {
            assertEquals(Status.CORRUPT, opened.status());
        }
    }

    @Test
    void transientOpenFailureIsRetryableAndFormatFailureIsCorrupt(
            @TempDir Path directory) throws Exception {
        ProjectIndexData source = ProjectIndexFixtures.snapshotWithAllMetaKinds();
        ProjectIndexIdentity identity = ProjectIndexIdentity.from(source.manifest());
        try (var store = new ProjectIndexStore(directory)) {
            assertEquals(ProjectIndexStore.PublishResult.PUBLISHED,
                    store.publish(source, () -> false));
        }
        List<Path> published = payloadFiles(directory);
        assertFalse(published.isEmpty());

        try (var store = ProjectIndexStoreTestSupport.failOpen(directory,
                () -> new java.nio.file.AccessDeniedException(
                        directory.toString()));
                var opened = store.open(identity)) {
            assertEquals(Status.RETRYABLE, opened.status());
        }
        assertEquals(published, payloadFiles(directory),
                "a transient open failure must never delete the index");
        try (var store = new ProjectIndexStore(directory);
                var opened = store.open(identity)) {
            assertEquals(Status.HIT, opened.status(),
                    "the index must still be usable after a transient failure");
        }

        try (var store = ProjectIndexStoreTestSupport.failOpen(directory,
                () -> new ProjectIndexFormatException("simulated damage"));
                var opened = store.open(identity)) {
            assertEquals(Status.CORRUPT, opened.status());
        }
        assertEquals(published, payloadFiles(directory),
                "the store itself never cleans, it only reports the status");
    }

    @Test
    void missingPayloadIsRetryableAndNeverDeletesTheStore(
            @TempDir Path directory) throws Exception {
        ProjectIndexData source = ProjectIndexFixtures.snapshotWithAllMetaKinds();
        ProjectIndexIdentity identity = ProjectIndexIdentity.from(source.manifest());
        Path journal;
        try (var store = new ProjectIndexStore(directory)) {
            assertEquals(ProjectIndexStore.PublishResult.PUBLISHED,
                    store.publish(source, () -> false));
            try (var opened = store.open(identity)) {
                journal = opened.view().orElseThrow().journalPathForTests();
            }
        }
        Files.delete(journal);

        try (var store = new ProjectIndexStore(directory);
                var opened = store.open(identity)) {
            assertEquals(Status.RETRYABLE, opened.status());
        }
        assertTrue(Files.isRegularFile(directory.resolve("current")));
    }

    @Test
    void publicationRenameRetriesOnlyOnWindowsAndStaysFailClosed()
            throws Exception {
        assertTrue(ProjectIndexStore.retriesPublicationRename("Windows 11"));
        assertTrue(ProjectIndexStore.retriesPublicationRename("windows server 2022"));
        assertFalse(ProjectIndexStore.retriesPublicationRename("Mac OS X"));
        assertFalse(ProjectIndexStore.retriesPublicationRename("Linux"));

        AtomicInteger attempts = new AtomicInteger();
        ProjectIndexStore.moveWithBoundedRetry(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new java.nio.file.AccessDeniedException("held");
            }
        }, true);
        assertEquals(3, attempts.get(),
                "a held target is retried until the rename succeeds");

        AtomicInteger exhausted = new AtomicInteger();
        assertThrows(java.nio.file.AccessDeniedException.class, () ->
                ProjectIndexStore.moveWithBoundedRetry(() -> {
                    exhausted.incrementAndGet();
                    throw new java.nio.file.AccessDeniedException("held");
                }, true));
        assertEquals(3, exhausted.get(),
                "the retry stays bounded and then fails closed");

        AtomicInteger posix = new AtomicInteger();
        assertThrows(java.nio.file.AccessDeniedException.class, () ->
                ProjectIndexStore.moveWithBoundedRetry(() -> {
                    posix.incrementAndGet();
                    throw new java.nio.file.AccessDeniedException("held");
                }, false));
        assertEquals(1, posix.get(), "POSIX publication never retries");

        AtomicInteger other = new AtomicInteger();
        assertThrows(IOException.class, () ->
                ProjectIndexStore.moveWithBoundedRetry(() -> {
                    other.incrementAndGet();
                    throw new IOException("unrelated");
                }, true));
        assertEquals(1, other.get(),
                "only an access denial is treated as a transient file hold");
    }

    private static List<Path> payloadFiles(Path directory) throws IOException {
        try (var entries = Files.list(directory)) {
            return entries.map(Path::getFileName)
                    .map(Path::toString)
                    .sorted()
                    .map(directory::resolve)
                    .toList();
        }
    }

    @Test
    void corruptCommittedJournalAndHostileCurrentFailClosedBeforeLargeAllocation(
            @TempDir Path directory) throws Exception {
        ProjectIndexData source = ProjectIndexFixtures.snapshotWithAllMetaKinds();
        ProjectIndexIdentity identity = ProjectIndexIdentity.from(source.manifest());
        Path journal;
        try (var store = new ProjectIndexStore(directory)) {
            store.publish(source, () -> false);
            try (var opened = store.open(identity)) {
                ProjectIndexView view = opened.view().orElseThrow();
                store.append(view, new ProjectIndexDelta(List.of(
                        ProjectIndexDelta.Change.delete(source.files().getFirst().path()))),
                        () -> false);
                journal = view.journalPathForTests();
            }
        }
        try (FileChannel channel = FileChannel.open(journal,
                StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ByteBuffer byteValue = ByteBuffer.allocate(1);
            channel.read(byteValue, 12);
            byteValue.flip();
            byteValue.put(0, (byte) (byteValue.get(0) ^ 1));
            channel.write(byteValue, 12);
        }
        try (var store = new ProjectIndexStore(directory);
                var opened = store.open(identity)) {
            assertEquals(Status.CORRUPT, opened.status());
        }

        try (var store = new ProjectIndexStore(directory)) {
            Files.write(store.currentPathForTests(), new byte[2 << 20],
                    StandardOpenOption.TRUNCATE_EXISTING);
            try (var opened = store.open(identity)) {
                assertEquals(Status.CORRUPT, opened.status());
            }
        }
    }

    @Test
    void corruptedJournalPathsFailClosedForDeleteAndReplace(@TempDir Path directory)
            throws Exception {
        ProjectIndexData source = ProjectIndexFixtures.snapshotWithAllMetaKinds();
        ProjectIndexIdentity identity = ProjectIndexIdentity.from(source.manifest());
        assertJournalPathCorruptionFailsClosed(directory.resolve("delete"), source,
                identity, ProjectIndexDelta.Change.delete(
                        source.files().getFirst().path()));
        assertJournalPathCorruptionFailsClosed(directory.resolve("replace"), source,
                identity, ProjectIndexDelta.Change.replace(
                        source.manifest().files().getFirst(),
                        ProjectIndexFixtures
                                .withDefinitionComment("replacement")
                                .files().getFirst()));
    }

    private static void assertJournalPathCorruptionFailsClosed(Path directory,
            ProjectIndexData source, ProjectIndexIdentity identity,
            ProjectIndexDelta.Change change) throws Exception {
        Path journal;
        try (var store = new ProjectIndexStore(directory)) {
            store.publish(source, () -> false);
            try (var opened = store.open(identity)) {
                ProjectIndexView view = opened.view().orElseThrow();
                assertEquals(ProjectIndexStore.AppendResult.APPENDED,
                        store.append(view, new ProjectIndexDelta(List.of(
                                change)), () -> false));
                journal = view.journalPathForTests();
            }
        }

        byte[] journalBytes = Files.readAllBytes(journal);
        byte[] pathBytes = change.path().relativePath()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int pathOffset = indexOf(journalBytes, pathBytes);
        assertTrue(pathOffset >= 0, "The journal must contain the changed path");
        journalBytes[pathOffset] ^= 1;
        Files.write(journal, journalBytes, StandardOpenOption.TRUNCATE_EXISTING);

        try (var store = new ProjectIndexStore(directory);
                var opened = store.open(identity)) {
            assertEquals(Status.CORRUPT, opened.status());
        }
    }

    @Test
    void writerWorkspaceCleanupRunsOnlyAfterDurablePublish(
            @TempDir Path directory) throws Exception {
        ProjectIndexData source = ProjectIndexFixtures.minimalSnapshot();
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(source.manifest());
        Path workspace = writerWorkspace(directory, "0".repeat(32));
        Path run = Files.createDirectories(workspace).resolve("run.tmp");
        Files.writeString(run, "orphan");

        try (var store = new ProjectIndexStore(directory);
                var opened = store.open(identity)) {
            assertEquals(Status.MISS, opened.status());
            assertEquals(ProjectIndexStore.PublishResult.CANCELLED,
                    store.publish(source, () -> true));
            assertTrue(Files.exists(run),
                    "Constructor, open, and cancellation must not clean");
        }
        assertTrue(Files.exists(run), "Close must not clean");

        ProjectIndexStore.IoHook failingHook = point -> {
            if (point == ProjectIndexStore.IoPoint.BEFORE_CURRENT_PUBLICATION) {
                throw new IOException("simulated pre-CURRENT failure");
            }
        };
        try (var failing = new ProjectIndexStore(directory,
                ProjectIndexStore.DEFAULT_CACHE_BYTES, failingHook,
                ProjectIndexStore.DEFAULT_COMPACTION_BYTES)) {
            assertThrows(IOException.class,
                    () -> failing.publish(source, () -> false));
        }
        assertTrue(Files.exists(run),
                "A publication that did not install CURRENT must not clean");

        try (var store = new ProjectIndexStore(directory)) {
            assertEquals(ProjectIndexStore.PublishResult.PUBLISHED,
                    store.publish(source, () -> false));
            assertFalse(Files.exists(workspace, LinkOption.NOFOLLOW_LINKS));
            try (var opened = store.open(identity)) {
                assertEquals(Status.HIT, opened.status());
            }
        }
    }

    @Test
    void activeWriterSurvivesPublishAndCleanWhileOrphansAreRemoved(
            @TempDir Path directory) throws Exception {
        String activeId = "d".repeat(32);
        Path activeWorkspace = writerWorkspace(directory, activeId);
        Path orphanWorkspace =
                writerWorkspace(directory, "e".repeat(32));
        ProjectIndexData source =
                ProjectIndexFixtures.minimalSnapshot();

        try (var active = new ProjectIndexSpillStore(
                directory, activeId, () -> false)) {
            var runs = active.section(
                    SectionType.COMPLETION_TRIGRAMS);
            var spilled = new ProjectIndexTupleBuffer(1, 1);
            spilled.add(42);
            runs.spill(spilled);
            Files.createDirectories(orphanWorkspace);
            Files.writeString(
                    orphanWorkspace.resolve("orphan.run"), "orphan");

            try (var store = new ProjectIndexStore(directory)) {
                ProjectIndexStore.PublishOutcome outcome =
                        store.publishWithReceipt(source, () -> false);

                assertNotEquals(activeId,
                        outcome.revision().publicationId());
                assertTrue(Files.isDirectory(activeWorkspace,
                        LinkOption.NOFOLLOW_LINKS));
                assertFalse(Files.exists(orphanWorkspace,
                        LinkOption.NOFOLLOW_LINKS));

                Files.createDirectories(orphanWorkspace);
                Files.writeString(
                        orphanWorkspace.resolve("orphan.run"), "orphan");
                store.clean();

                assertTrue(Files.isDirectory(activeWorkspace,
                        LinkOption.NOFOLLOW_LINKS));
                assertFalse(Files.exists(orphanWorkspace,
                        LinkOption.NOFOLLOW_LINKS));
            }

            runs.finish(new ProjectIndexTupleBuffer(1, 0));
            try (var cursor = runs.openCursor()) {
                assertTrue(cursor.next());
                assertEquals(42, cursor.word(0));
                assertFalse(cursor.next());
            }
        }
        assertFalse(Files.exists(activeWorkspace,
                LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void appendAndCloseDoNotCleanWriterWorkspace(
            @TempDir Path directory) throws Exception {
        ProjectIndexData source =
                ProjectIndexFixtures.snapshotWithAllMetaKinds();
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(source.manifest());
        Path workspace = writerWorkspace(directory, "1".repeat(32));
        Path run;

        try (var store = new ProjectIndexStore(directory)) {
            assertEquals(ProjectIndexStore.PublishResult.PUBLISHED,
                    store.publish(source, () -> false));
            run = Files.createDirectories(workspace).resolve("run.tmp");
            Files.writeString(run, "active");
            try (var opened = store.open(identity)) {
                assertEquals(ProjectIndexStore.AppendResult.APPENDED,
                        store.append(opened.view().orElseThrow(),
                                new ProjectIndexDelta(List.of(
                                        ProjectIndexDelta.Change.delete(
                                                source.files().getLast()
                                                        .path()))),
                                () -> false));
            }
            assertTrue(Files.exists(run), "Append must not clean");
        }
        assertTrue(Files.exists(run), "Close must not clean");
    }

    @Test
    void cleanDeletesOnlyExactWriterWorkspaceDirectories(
            @TempDir Path directory) throws Exception {
        Path exact = Files.createDirectories(
                writerWorkspace(directory, "2".repeat(32)));
        Files.writeString(exact.resolve("run.tmp"), "orphan");
        Path exactRegular = writerWorkspace(directory, "3".repeat(32));
        Files.writeString(exactRegular, "preserve regular file");

        List<Path> preserved = List.of(
                Files.createDirectories(directory.resolve(
                        ".writer-" + "4".repeat(31) + ".tmp")),
                Files.createDirectories(directory.resolve(
                        ".writer-" + "5".repeat(33) + ".tmp")),
                Files.createDirectories(directory.resolve(
                        ".writer-" + "A".repeat(32) + ".tmp")),
                Files.createDirectories(directory.resolve(
                        ".writer-" + "g".repeat(32) + ".tmp")),
                Files.createDirectories(directory.resolve(
                        ".writer-" + "6".repeat(32) + ".tmp.extra")),
                Files.createDirectories(directory.resolve(
                        "writer-" + "7".repeat(32) + ".tmp")),
                Files.createDirectories(directory.resolve(
                        ".writer-" + "8".repeat(32) + "tmp")),
                Files.createDirectories(directory.resolve("unrelated")));

        try (var store = new ProjectIndexStore(directory)) {
            store.clean();
        }

        assertFalse(Files.exists(exact, LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.isRegularFile(exactRegular,
                LinkOption.NOFOLLOW_LINKS));
        for (Path path : preserved) {
            assertTrue(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS),
                    () -> "Unexpected cleanup of " + path.getFileName());
        }
    }

    @Test
    void cleanUnlinksWorkspaceSymlinksWithoutFollowingThem(
            @TempDir Path directory) throws Exception {
        Path external = Files.createDirectories(
                directory.resolve("external-target"));
        Path sentinel = Files.writeString(
                external.resolve("sentinel.txt"), "keep");
        assumeTrue(supportsSymbolicLinks(directory, external),
                "Symbolic links are not supported by this file system");
        Path topLevelLink =
                writerWorkspace(directory, "9".repeat(32));
        Files.createSymbolicLink(topLevelLink, external);

        Path workspace = Files.createDirectories(
                writerWorkspace(directory, "a".repeat(32)));
        Files.writeString(workspace.resolve("direct.run"), "delete");
        Path childLink = workspace.resolve("external.link");
        Files.createSymbolicLink(childLink, sentinel);

        try (var store = new ProjectIndexStore(directory)) {
            store.clean();
        }

        assertFalse(Files.exists(topLevelLink,
                LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(childLink,
                LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(workspace,
                LinkOption.NOFOLLOW_LINKS));
        assertEquals("keep", Files.readString(sentinel));
    }

    @Test
    void cleanContinuesPayloadCleanupWhenNestedWorkspaceCannotBeRemoved(
            @TempDir Path directory) throws Exception {
        ProjectIndexData source = ProjectIndexFixtures.minimalSnapshot();
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(source.manifest());
        Path base;
        Path journal;
        Path workspace;
        Path direct;
        Path nested;
        Path nestedSentinel;
        Path legacy;

        try (var store = new ProjectIndexStore(directory)) {
            assertEquals(ProjectIndexStore.PublishResult.PUBLISHED,
                    store.publish(source, () -> false));
            try (var opened = store.open(identity)) {
                ProjectIndexView view = opened.view().orElseThrow();
                base = view.basePathForTests();
                journal = view.journalPathForTests();
            }
            workspace = writerWorkspace(directory, "b".repeat(32));
            direct = Files.createDirectories(workspace)
                    .resolve("direct.run");
            Files.writeString(direct, "delete");
            nested = Files.createDirectories(
                    workspace.resolve("nested"));
            nestedSentinel = Files.writeString(
                    nested.resolve("sentinel.txt"), "keep");
            legacy = directory.resolve("legacy.ser");
            Files.writeString(legacy, "delete");

            assertThrows(IOException.class, store::clean);
            assertFalse(Files.exists(store.currentPathForTests()));
            try (var opened = store.open(identity)) {
                assertEquals(Status.MISS, opened.status());
            }
        }

        assertFalse(Files.exists(direct));
        assertFalse(Files.exists(base));
        assertFalse(Files.exists(journal));
        assertFalse(Files.exists(legacy));
        assertTrue(Files.isDirectory(workspace,
                LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.isDirectory(nested,
                LinkOption.NOFOLLOW_LINKS));
        assertEquals("keep", Files.readString(nestedSentinel));
    }

    @Test
    void durablePublishKeepsHitWhenNestedWorkspaceCleanupFails(
            @TempDir Path directory) throws Exception {
        ProjectIndexData older =
                ProjectIndexFixtures.withDefinitionComment("older");
        ProjectIndexData newer =
                ProjectIndexFixtures.withDefinitionComment("newer");
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(older.manifest());
        Path oldBase;
        Path oldJournal;

        try (var store = new ProjectIndexStore(directory)) {
            assertEquals(ProjectIndexStore.PublishResult.PUBLISHED,
                    store.publish(older, () -> false));
            try (var opened = store.open(identity)) {
                ProjectIndexView view = opened.view().orElseThrow();
                oldBase = view.basePathForTests();
                oldJournal = view.journalPathForTests();
            }

            Path workspace = Files.createDirectories(
                    writerWorkspace(directory, "c".repeat(32)));
            Path direct = Files.writeString(
                    workspace.resolve("direct.run"), "delete");
            Path nested = Files.createDirectories(
                    workspace.resolve("nested"));
            Path nestedSentinel = Files.writeString(
                    nested.resolve("sentinel.txt"), "keep");

            assertEquals(ProjectIndexStore.PublishResult.PUBLISHED,
                    store.publish(newer, () -> false));
            assertFalse(Files.exists(direct));
            assertTrue(Files.isDirectory(workspace,
                    LinkOption.NOFOLLOW_LINKS));
            assertTrue(Files.isDirectory(nested,
                    LinkOption.NOFOLLOW_LINKS));
            assertEquals("keep", Files.readString(nestedSentinel));
            assertFalse(Files.exists(oldBase));
            assertFalse(Files.exists(oldJournal));

            try (var opened = store.open(identity)) {
                assertEquals(Status.HIT, opened.status());
                assertEquals("newer", opened.view().orElseThrow()
                        .contribution(newer.files().getFirst().path())
                        .orElseThrow().definitions().getFirst()
                        .comment());
            }
        }
    }

    @Test
    void legacySerializedStateIsNeverReadAndIsRemovedOnlyByClean(@TempDir Path directory)
            throws Exception {
        ProjectIndexData source = ProjectIndexFixtures.minimalSnapshot();
        ProjectIndexIdentity identity = ProjectIndexIdentity.from(source.manifest());
        Path legacy = directory.resolve("OmniX_DB.ser");
        Files.createDirectories(directory);
        Files.write(legacy, new byte[] {(byte) 0xac, (byte) 0xed, 0, 5, 127, 127, 127});
        try (var store = new ProjectIndexStore(directory)) {
            try (var opened = store.open(identity)) {
                assertEquals(Status.MISS, opened.status());
            }
            assertEquals(ProjectIndexStore.PublishResult.CANCELLED,
                    store.publishAndDeleteLegacy(source, () -> true, legacy));
            assertTrue(Files.exists(legacy));
        }

        var hook = new ProjectIndexStore.IoHook() {
            @Override
            public void at(ProjectIndexStore.IoPoint point) throws IOException {
                if (point == ProjectIndexStore.IoPoint.BEFORE_CURRENT_PUBLICATION) {
                    throw new IOException("simulated publication failure");
                }
            }
        };
        try (var failing = new ProjectIndexStore(directory,
                ProjectIndexStore.DEFAULT_CACHE_BYTES, hook,
                ProjectIndexStore.DEFAULT_COMPACTION_BYTES)) {
            assertThrows(IOException.class, () -> failing.publishAndDeleteLegacy(
                    source, () -> false, legacy));
            assertTrue(Files.exists(legacy));
        }

        try (var store = new ProjectIndexStore(directory)) {
            assertEquals(ProjectIndexStore.PublishResult.PUBLISHED,
                    store.publishAndDeleteLegacy(source, () -> false, legacy));
            assertFalse(Files.exists(legacy));
            try (var opened = store.open(identity)) {
                assertEquals(Status.HIT, opened.status());
            }
            Files.write(legacy, new byte[] {(byte) 0xac, (byte) 0xed});
            assertTrue(Files.exists(legacy));
            store.clean();
            assertFalse(Files.exists(legacy));
        }
    }

    @Test
    void boundedCompactionPublishesEquivalentGenerationAndCleanRemovesOnlyCacheState(
            @TempDir Path directory) throws Exception {
        ProjectIndexData source = ProjectIndexFixtures.snapshotWithAllMetaKinds();
        ProjectIndexIdentity identity = ProjectIndexIdentity.from(source.manifest());
        Path unrelated = directory.resolve("keep.sql");
        Files.writeString(unrelated, "select 1;");
        try (var store = new ProjectIndexStore(directory, ProjectIndexStore.DEFAULT_CACHE_BYTES,
                ProjectIndexStore.IoHook.NONE, 1)) {
            store.publish(source, () -> false);
            try (var opened = store.open(identity)) {
                ProjectIndexView view = opened.view().orElseThrow();
                store.append(view, new ProjectIndexDelta(List.of(
                        ProjectIndexDelta.Change.delete(source.files().getLast().path()))),
                        () -> false);
            }
            try (var opened = store.open(identity)) {
                ProjectIndexView view = opened.view().orElseThrow();
                assertTrue(store.needsCompaction(view));
                assertEquals(ProjectIndexStore.PublishResult.PUBLISHED,
                        store.compact(view, () -> false));
            }
            try (var opened = store.open(identity)) {
                assertTrue(opened.view().orElseThrow()
                        .contribution(source.files().getLast().path()).isEmpty());
            }
            store.clean();
            assertEquals(Status.MISS, store.open(identity).status());
        }
        assertEquals("select 1;", Files.readString(unrelated));
    }

    @Test
    void cleanInvalidatesCurrentBeforePayloadDeletionCanFail(@TempDir Path directory)
            throws Exception {
        ProjectIndexData source = ProjectIndexFixtures.minimalSnapshot();
        ProjectIndexIdentity identity = ProjectIndexIdentity.from(source.manifest());
        try (var publishing = new ProjectIndexStore(directory)) {
            assertEquals(ProjectIndexStore.PublishResult.PUBLISHED,
                    publishing.publish(source, () -> false));
        }

        var hook = new ProjectIndexStore.IoHook() {
            @Override
            public void at(ProjectIndexStore.IoPoint point) throws IOException {
                if (point == ProjectIndexStore.IoPoint.BEFORE_CLEAN_PAYLOAD_DELETE) {
                    throw new IOException("simulated locked payload");
                }
            }
        };
        try (var failing = new ProjectIndexStore(directory,
                ProjectIndexStore.DEFAULT_CACHE_BYTES, hook,
                ProjectIndexStore.DEFAULT_COMPACTION_BYTES)) {
            assertThrows(IOException.class, failing::clean);
            assertFalse(Files.exists(failing.currentPathForTests()),
                    "CURRENT must disappear before potentially locked payload files");
            assertEquals(Status.MISS, failing.open(identity).status(),
                    "An interrupted clean must remain fail-closed");
        }
    }

    @Test
    void compactionStreamsContributionsAndKeepsExactCodecBytes(
            @TempDir Path directory) throws Exception {
        ProjectIndexData source = ProjectIndexFixtures.largeIndexedProject(2_000);
        ProjectIndexIdentity identity = ProjectIndexIdentity.from(source.manifest());
        IndexPathRef deleted = source.files().getLast().path();
        ProjectIndexManifest manifest = source.manifest();
        ProjectIndexData expected = new ProjectIndexData(new ProjectIndexManifest(
                manifest.formatMajor(), manifest.formatMinor(), manifest.parserAbi(),
                manifest.coreVersion(), manifest.uiVersion(), manifest.databaseType(),
                manifest.projectIdentity(), manifest.configSha256(), manifest.generation(),
                manifest.files().subList(0, manifest.files().size() - 1)),
                source.files().subList(0, source.files().size() - 1));

        try (var store = new ProjectIndexStore(directory)) {
            store.publish(source, () -> false);
            try (var opened = store.open(identity)) {
                assertEquals(ProjectIndexStore.AppendResult.APPENDED,
                        store.append(opened.view().orElseThrow(),
                                new ProjectIndexDelta(List.of(
                                        ProjectIndexDelta.Change.delete(deleted))),
                                () -> false));
            }
            try (var opened = store.open(identity)) {
                ProjectIndexView view = opened.view().orElseThrow();
                assertEquals(0, view.decodedContributionCount());
                assertEquals(ProjectIndexStore.PublishResult.PUBLISHED,
                        store.compact(view, () -> false));
                assertEquals(0, view.decodedContributionCount(),
                        "Compaction must not call eager materialize");
                assertTrue(view.cachedBytes() <= ProjectIndexStore.DEFAULT_CACHE_BYTES);
            }
        }

        try (var store = new ProjectIndexStore(directory);
                var opened = store.open(identity)) {
            ProjectIndexView view = opened.view().orElseThrow();
            assertArrayEquals(ProjectIndexCodec.encodeBase(expected),
                    readCanonicalCodec(view.basePathForTests()));
        }
    }

    @Test
    void compactionKeepsCanonicalOrderForMultipleUnresolvedKeys(
            @TempDir Path directory) throws Exception {
        ProjectIndexData original =
                ProjectIndexFixtures.snapshotWithAllMetaKinds();
        List<FileContribution> files =
                new ArrayList<>(original.files());
        FileContribution functionFile = files.get(1);
        Set<ReferenceMatchKey> unresolved = Set.of(
                ReferenceMatchKey.from(functionFile.locations().get(1)),
                ReferenceMatchKey.from(functionFile.locations().get(2)),
                ReferenceMatchKey.from(functionFile.locations().get(3)));
        files.set(1, new FileContribution(functionFile.path(),
                functionFile.definitions(), functionFile.locations(),
                unresolved, functionFile.unresolvedAny()));
        ProjectIndexData source =
                new ProjectIndexData(original.manifest(), files);
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(source.manifest());
        byte[] expected = ProjectIndexCodec.encodeBase(source);

        try (var store = new ProjectIndexStore(directory)) {
            store.publish(source, () -> false);
            try (var opened = store.open(identity)) {
                assertEquals(ProjectIndexStore.PublishResult.PUBLISHED,
                        store.compact(opened.view().orElseThrow(),
                                () -> false));
            }
        }
        try (var store = new ProjectIndexStore(directory);
                var opened = store.open(identity)) {
            assertArrayEquals(expected, readCanonicalCodec(
                    opened.view().orElseThrow().basePathForTests()));
        }
    }

    @Test
    void compactionReplaysPackedBlocksSequentially(@TempDir Path directory)
            throws Exception {
        ProjectIndexData source = ProjectIndexFixtures.largeIndexedProject(1_000);
        ProjectIndexIdentity identity = ProjectIndexIdentity.from(source.manifest());

        try (var store = new ProjectIndexStore(directory,
                ProjectIndexBlockCache.BLOCK_BYTES,
                ProjectIndexStore.IoHook.NONE,
                ProjectIndexStore.DEFAULT_COMPACTION_BYTES)) {
            store.publish(source, () -> false);
            try (var opened = store.open(identity)) {
                ProjectIndexView view = opened.view().orElseThrow();
                long readsBefore = view.cacheBlocksRead();
                assertEquals(ProjectIndexStore.PublishResult.PUBLISHED,
                        store.compact(view, () -> false));
                long replayReads = view.cacheBlocksRead() - readsBefore;
                // Eight bounded writer passes replay every canonical block.
                // The manifest lookup may read up to two FILES blocks first.
                assertTrue(replayReads
                        <= view.canonicalCodecBlocks() * 8L + 2,
                        "Compaction re-read too many packed blocks: "
                                + replayReads + '/' + view.canonicalCodecBlocks());
            }
        }
    }

    @Test
    void repeatedReadsRespectHardCacheCapAndClosedViewRejectsReads(@TempDir Path directory)
            throws Exception {
        ProjectIndexData source = ProjectIndexFixtures.snapshotWithAllMetaKinds();
        ProjectIndexIdentity identity = ProjectIndexIdentity.from(source.manifest());
        try (var store = new ProjectIndexStore(directory, 128 << 10,
                ProjectIndexStore.IoHook.NONE, ProjectIndexStore.DEFAULT_COMPACTION_BYTES)) {
            store.publish(source, () -> false);
            ProjectIndexView view;
            try (var opened = store.open(identity)) {
                view = opened.view().orElseThrow();
                List<IndexPathRef> paths = new ArrayList<>();
                source.files().forEach(file -> paths.add(file.path()));
                for (int i = 0; i < 20; i++) {
                    for (IndexPathRef path : paths) {
                        assertTrue(view.contribution(path).isPresent());
                    }
                }
                assertTrue(view.cachedBytes() <= 128 << 10);
            }
            assertThrows(IllegalStateException.class,
                    () -> view.contribution(source.files().getFirst().path()));
        }
    }

    private static void assertBoundedPointRead(long blocksRead, int totalBlocks,
            String operation) {
        assertTrue(blocksRead > 0, operation + " must touch canonical data");
        assertTrue(blocksRead <= 40,
                operation + " exceeded its fixed block-read budget: " + blocksRead);
        assertTrue(blocksRead * 2 < totalBlocks,
                operation + " appears to scan a linear fraction of the codec: "
                        + blocksRead + '/' + totalBlocks);
    }

    private static int indexOf(byte[] source, byte[] target) {
        outer:
        for (int i = 0; i <= source.length - target.length; i++) {
            for (int j = 0; j < target.length; j++) {
                if (source[i + j] != target[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static ProjectIndexDelta replacementDelta(
            ProjectIndexData source, int count, String marker)
            throws Exception {
        List<ProjectIndexDelta.Change> changes =
                new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            changes.add(replacement(source, i, marker + '-' + i));
        }
        return new ProjectIndexDelta(changes);
    }

    private static ProjectIndexDelta.Change replacement(
            ProjectIndexData source, int index, String marker)
            throws Exception {
        ProjectFileStamp previous =
                source.manifest().files().get(index);
        ProjectFileStamp replacement = new ProjectFileStamp(
                previous.path(),
                previous.eclipseModificationStamp() + 10_000,
                previous.size() + marker.length(),
                previous.lastModifiedMillis() + 10_000,
                ProjectIndexFixtures.sha256(marker));
        return ProjectIndexDelta.Change.replace(
                replacement, source.files().get(index));
    }

    private static void assertBatchFailureKeepsOriginal(
            Path directory, ProjectIndexData source,
            ProjectIndexIdentity identity, ProjectIndexDelta delta,
            ProjectIndexStore.IoHook hook) throws Exception {
        try (var store = new ProjectIndexStore(directory)) {
            store.publish(source, () -> false);
        }
        try (var store = new ProjectIndexStore(directory,
                ProjectIndexStore.DEFAULT_CACHE_BYTES, hook,
                ProjectIndexStore.DEFAULT_COMPACTION_BYTES)) {
            try (var opened = store.open(identity)) {
                assertThrows(IOException.class,
                        () -> store.appendIncremental(
                                opened.view().orElseThrow(), delta,
                                () -> false));
            }
        }
        assertOriginalRevision(directory, source, identity);
    }

    private static void assertOriginalRevision(
            Path directory, ProjectIndexData source,
            ProjectIndexIdentity identity) throws Exception {
        try (var store = new ProjectIndexStore(directory);
                var reopened = store.open(identity)) {
            ProjectIndexView view = reopened.view().orElseThrow();
            assertEquals(0, view.committedJournalEntriesForTests());
            assertEquals(Set.copyOf(source.manifest().files()),
                    Set.copyOf(view.fileStamps()));
            assertArrayEquals(ProjectIndexFixtures.canonicalBytes(source),
                    ProjectIndexFixtures.canonicalBytes(
                            view.materialize()));
        }
    }

    private static Path writerWorkspace(Path directory,
            String publicationId) {
        return directory.resolve(
                ".writer-" + publicationId + ".tmp");
    }

    private static long tokenValue(String line, String name) {
        String prefix = name + '=';
        for (String token : line.split(" ")) {
            if (token.startsWith(prefix)) {
                return Long.parseLong(
                        token.substring(prefix.length()));
            }
        }
        throw new AssertionError(
                "Telemetry token is missing: " + name);
    }

    private static void assertPackedPublicationTelemetry(String line) {
        assertTrue(line.contains(
                "phase_encode_ms=0 phase_locator_crc_ms=0 "
                        + "phase_fsync_ms=0 phase_current_ms=0"));
        assertEquals(tokenValue(line, "writer_packed_bytes"),
                tokenValue(line, "writer_reread_bytes"));
    }

    private static boolean supportsSymbolicLinks(
            Path directory, Path target) throws IOException {
        Path probe = directory.resolve("symlink-probe");
        try {
            Files.createSymbolicLink(probe, target);
            return true;
        } catch (IOException | UnsupportedOperationException
                | SecurityException ex) {
            return false;
        } finally {
            Files.deleteIfExists(probe);
        }
    }

    private static byte[] readCanonicalCodec(Path base) throws Exception {
        try (FileChannel channel = FileChannel.open(base, StandardOpenOption.READ)) {
            ProjectIndexContainer.Opened opened =
                    ProjectIndexContainer.open(channel, 0, channel.size());
            byte[] result = new byte[Math.toIntExact(opened.codecLength())];
            ByteBuffer target = ByteBuffer.wrap(result);
            long position = opened.codecOffset();
            while (target.hasRemaining()) {
                int read = channel.read(target, position);
                assertTrue(read > 0);
                position += read;
            }
            return result;
        }
    }
}
