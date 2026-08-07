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

import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectIndexFileLookupTest {

    @Test
    void baseFileLookupReadsOnlyStampAndDefinitions(
            @TempDir Path directory) throws Exception {
        ProjectIndexData source =
                ProjectIndexFixtures.largeIndexedProject(5_000);
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(source.manifest());
        FileContribution expectedContribution =
                source.files().getLast();
        ProjectFileStamp expectedStamp =
                source.manifest().files().getLast();

        try (var store = new ProjectIndexStore(directory)) {
            assertEquals(ProjectIndexStore.PublishResult.PUBLISHED,
                    store.publish(source, () -> false));
            try (var opened = store.open(identity)) {
                ProjectIndexView view = opened.view().orElseThrow();
                ProjectIndexCorruptionTestSupport
                        .corruptLastBaseLocationBlock(view);

                ProjectIndexFileMetadata file =
                        view.file(expectedStamp.path()).orElseThrow();

                assertEquals(expectedStamp, file.stamp());
                assertEquals(expectedContribution.definitions(),
                        file.definitions());
                assertEquals(0, view.decodedContributionCount());
                assertThrows(ProjectIndexFormatException.class,
                        () -> view.contribution(expectedStamp.path()));
                assertEquals(0, view.decodedContributionCount());
            }
        }
    }

    @Test
    void directOverlayFileLookupDoesNotDecodeContribution(
            @TempDir Path directory) throws Exception {
        ProjectIndexData source =
                ProjectIndexFixtures.snapshotWithAllMetaKinds();
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(source.manifest());
        FileContribution replacement = ProjectIndexFixtures
                .withDefinitionComment("direct overlay").files().getFirst();
        ProjectFileStamp originalStamp =
                source.manifest().files().getFirst();
        ProjectFileStamp replacementStamp = new ProjectFileStamp(
                originalStamp.path(), 41, 42, 43,
                ProjectIndexFixtures.sha256("direct overlay"));
        Path basePath;
        Path journalPath;
        String publicationId;

        try (var store = new ProjectIndexStore(directory)) {
            assertEquals(ProjectIndexStore.PublishResult.PUBLISHED,
                    store.publish(source, () -> false));
            try (var opened = store.open(identity)) {
                ProjectIndexView persisted =
                        opened.view().orElseThrow();
                basePath = persisted.basePathForTests();
                journalPath = persisted.journalPathForTests();
                publicationId = persisted.publicationId();
            }
        }

        try (FileChannel baseChannel = FileChannel.open(
                basePath, StandardOpenOption.READ);
                FileChannel journalChannel = FileChannel.open(
                        journalPath, StandardOpenOption.READ)) {
            var cache = new ProjectIndexBlockCache(
                    ProjectIndexStore.DEFAULT_CACHE_BYTES);
            LazyProjectIndexBase base = LazyProjectIndexBase.open(
                    baseChannel, 0, baseChannel.size(), cache);
            try (var view = new ProjectIndexView(base,
                    Map.of(replacement.path(),
                            ProjectIndexView.OverlayValue.direct(
                                    replacementStamp, replacement)),
                    cache, baseChannel, journalChannel, basePath, journalPath,
                    publicationId, journalChannel.size(), 0)) {

                ProjectIndexFileMetadata file =
                        view.file(replacement.path()).orElseThrow();

                assertEquals(replacementStamp, file.stamp());
                assertEquals(replacement.definitions(),
                        file.definitions());
                assertEquals(0, view.decodedContributionCount());
            }
        }
    }

    @Test
    void reopenedJournalFileLookupReadsNoLocations(
            @TempDir Path directory) throws Exception {
        ProjectIndexData source =
                ProjectIndexFixtures.repeatedLocations(10_000);
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(source.manifest());
        ProjectFileStamp stamp =
                source.manifest().files().getFirst();
        FileContribution replacement =
                source.files().getFirst();

        try (var store = new ProjectIndexStore(directory)) {
            assertEquals(ProjectIndexStore.PublishResult.PUBLISHED,
                    store.publish(source, () -> false));
            try (var opened = store.open(identity)) {
                assertEquals(ProjectIndexStore.IncrementalAppendStatus.APPENDED,
                        store.appendIncremental(
                                opened.view().orElseThrow(),
                                ProjectIndexDelta.Change.replace(
                                        stamp, replacement),
                                () -> false).status());
            }
        }

        try (var store = new ProjectIndexStore(directory);
                var reopened = store.open(identity)) {
            ProjectIndexView view = reopened.view().orElseThrow();
            ProjectIndexCorruptionTestSupport
                    .corruptLastJournalLocationBlock(
                            view, stamp.path());

            ProjectIndexFileMetadata file =
                    view.file(stamp.path()).orElseThrow();

            assertEquals(stamp, file.stamp());
            assertEquals(List.of(), file.definitions());
            assertEquals(0, view.decodedContributionCount());
            assertThrows(ProjectIndexFormatException.class,
                    () -> view.contribution(stamp.path()));
            assertEquals(0, view.decodedContributionCount());
        }
    }

    @Test
    void fileMetadataCopiesDefinitionsAndRejectsOtherPaths()
            throws Exception {
        ProjectIndexData source =
                ProjectIndexFixtures.snapshotWithAllMetaKinds();
        ProjectFileStamp stamp =
                source.manifest().files().getFirst();
        var definitions = new ArrayList<>(
                source.files().getFirst().definitions());

        ProjectIndexFileMetadata metadata =
                new ProjectIndexFileMetadata(stamp, definitions);
        definitions.clear();

        assertEquals(1, metadata.definitions().size());
        ProjectFileStamp otherStamp = new ProjectFileStamp(
                new IndexPathRef(IndexPathOrigin.PROJECT, "other.sql"),
                1, 2, 3, ProjectIndexFixtures.sha256("other"));
        assertThrows(IllegalArgumentException.class,
                () -> new ProjectIndexFileMetadata(
                        otherStamp, metadata.definitions()));
    }

    @Test
    void closingABaseReleasesItsResidentDictionary(
            @TempDir Path directory) throws Exception {
        ProjectIndexData source =
                ProjectIndexFixtures.largeIndexedProject(5_000);
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(source.manifest());
        ProjectFileStamp stamp = source.manifest().files().getLast();
        Path basePath;

        try (var store = new ProjectIndexStore(directory)) {
            assertEquals(ProjectIndexStore.PublishResult.PUBLISHED,
                    store.publish(source, () -> false));
            try (var opened = store.open(identity)) {
                basePath = opened.view().orElseThrow().basePathForTests();
            }
        }

        try (FileChannel channel = FileChannel.open(
                basePath, StandardOpenOption.READ)) {
            var cache = new ProjectIndexBlockCache(
                    ProjectIndexStore.DEFAULT_CACHE_BYTES);
            LazyProjectIndexBase base = LazyProjectIndexBase.open(
                    channel, 0, channel.size(), cache);

            assertNotNull(base.file(stamp.path()));
            assertTrue(base.residentStringBlocksForTests() > 0,
                    "a lookup makes the dictionary blocks it read resident");

            base.close();

            // The decoded dictionary outlives no index: it mirrors bytes that
            // only stay valid while the file behind them is the one in use.
            assertEquals(0, base.residentStringBlocksForTests());
        }
    }

    @Test
    void aHeldCacheBlockAnswersWithTheBytesTheSourceWouldRead(
            @TempDir Path directory) throws Exception {
        ProjectIndexData source =
                ProjectIndexFixtures.largeIndexedProject(5_000);
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(source.manifest());
        Path basePath;

        try (var store = new ProjectIndexStore(directory)) {
            assertEquals(ProjectIndexStore.PublishResult.PUBLISHED,
                    store.publish(source, () -> false));
            try (var opened = store.open(identity)) {
                basePath = opened.view().orElseThrow().basePathForTests();
            }
        }

        try (FileChannel channel = FileChannel.open(
                basePath, StandardOpenOption.READ)) {
            var cache = new ProjectIndexBlockCache(
                    ProjectIndexStore.DEFAULT_CACHE_BYTES);
            ProjectIndexContainer.Opened container =
                    ProjectIndexContainer.open(channel, 0, channel.size());
            var cached = new CachedProjectIndexSource(channel, cache,
                    container.codecOffset(), container.codecLength(),
                    container.locator());
            var run = new ProjectIndexSource.ByteRun();
            long block = ProjectIndexBlockCache.BLOCK_BYTES;
            assertTrue(cached.size() > 2 * block,
                    "the fixture has to span more than one cache block");

            // Both sides of a block boundary: a run is indexed from its own
            // start, and a reader that got that wrong would still be inside
            // the array and would answer plausible, wrong bytes.
            for (long position : List.of(0L, 1L, block - 1, block, block + 1,
                    2 * block - 1, 2 * block, cached.size() - 1)) {
                assertTrue(cached.locate(run, position),
                        "a cached source hands out the block at " + position);
                assertTrue(run.holds(position),
                        "and the run it hands out covers " + position);
                assertEquals(cached.readUnsignedByte(position),
                        run.byteAt(position),
                        "a held block answers as the source does at "
                                + position);
            }
            cached.closeCacheEntries();
        }
    }
}
