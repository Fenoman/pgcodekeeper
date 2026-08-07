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
package ru.taximaxim.codekeeper.ui.pgdbproject.parser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgcodekeeper.core.database.api.loader.ILoader;
import org.pgcodekeeper.core.database.api.loader.IProjectInputFingerprintCapture;
import org.pgcodekeeper.core.database.api.loader.ProjectInputFingerprint;
import org.pgcodekeeper.core.database.api.parser.ParserListenerMode;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.base.schema.meta.MetaUtils;
import org.pgcodekeeper.core.database.base.schema.meta.MetaStatement;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.monitor.NullMonitor;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.settings.ISettings;

import ru.taximaxim.codekeeper.ui.DatabaseType;
import ru.taximaxim.codekeeper.ui.builders.ProjectBuilderTestSupport;
import ru.taximaxim.codekeeper.ui.projectindex.FileContribution;
import ru.taximaxim.codekeeper.ui.projectindex.IndexPathOrigin;
import ru.taximaxim.codekeeper.ui.projectindex.IndexPathRef;
import ru.taximaxim.codekeeper.ui.projectindex.PackedDefinition;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectFileStamp;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexDefinitionSubject;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexIdentity;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexRevision;

class PgDbParserTest {

    @Test
    void fingerprintCaptureEnableFailureIsFailOpenAndClosesLoader()
            throws Exception {
        var loader = new FailingCaptureLoader(true, false);

        PgDbParser.LoadedReferences loaded =
                new PgDbParser().loadReferenceData(
                        loader, null, true, true);

        Assertions.assertNull(loaded.inputFingerprints());
        Assertions.assertTrue(loader.loaded.get());
        Assertions.assertTrue(loader.closed.get());
    }

    @Test
    void fingerprintCaptureGetterFailureIsFailOpenAndClosesLoader()
            throws Exception {
        var loader = new FailingCaptureLoader(false, true);

        PgDbParser.LoadedReferences loaded =
                new PgDbParser().loadReferenceData(
                        loader, null, true, true);

        Assertions.assertNull(loaded.inputFingerprints());
        Assertions.assertTrue(loader.loaded.get());
        Assertions.assertTrue(loader.closed.get());
    }

    @Test
    void singleDumpAnalysisReadsCapturedBytesExactlyOnce()
            throws Exception {
        byte[] sql = """
                CREATE OR REPLACE FUNCTION public.answer()
                RETURNS integer
                LANGUAGE sql
                AS $function$
                SELECT 42;
                $function$;
                """.getBytes(StandardCharsets.UTF_8);
        var opens = new AtomicInteger();
        var settings = new CoreSettings();
        settings.setMonitor(new NullMonitor());

        try (var loader = DatabaseType.PG.getDatabaseProvider()
                .getDumpLoader(() -> {
                    opens.incrementAndGet();
                    return new ByteArrayInputStream(sql);
                }, "/captured/schema/answer.sql", settings)) {
            loader.setMode(ParserListenerMode.SINGLE);

            IDatabase parsed = loader.load();
            var metadata = MetaUtils.createTreeFromDb(parsed,
                    settings.getVersion(), settings.getMonitor());
            IDatabase analyzed = loader.loadAndAnalyze(metadata);

            Assertions.assertSame(parsed, analyzed);
            Assertions.assertEquals(1, opens.get(),
                    "The immutable captured bytes must be parsed once");
            Assertions.assertTrue(loader.getErrors().isEmpty());
        }
    }

    @Test
    void incrementalEligibilityFailsClosedForErrorsDriftAndShape()
            throws Exception {
        IndexPathRef path = new IndexPathRef(
                IndexPathOrigin.PROJECT, "schema/function.sql");
        byte[] oldHash = new byte[32];
        byte[] newHash = new byte[32];
        newHash[0] = 1;
        ProjectFileStamp captured =
                new ProjectFileStamp(path, 1, 10, 20, oldHash);
        ProjectFileStamp drifted =
                new ProjectFileStamp(path, 2, 11, 21, newHash);
        ObjectReference reference = new ObjectReference(
                "app", "calculate(integer)", DbObjType.FUNCTION);
        ObjectLocation location = new ObjectLocation.Builder()
                .setFilePath("/project/schema/function.sql")
                .setReference(reference)
                .setLocationType(
                        ObjectLocation.LocationType.DEFINITION)
                .build();
        var definition = new org.pgcodekeeper.core.database.base.schema.meta.MetaFunction(
                location, "calculate");
        definition.setReturns("integer");
        PackedDefinition packed =
                PackedDefinition.from(definition, path);
        FileContribution previous = new FileContribution(path,
                List.of(packed), List.of(), Set.of(), false);
        FileContribution changedSignature =
                new FileContribution(path,
                        List.of(packed.withReturns("jsonb")),
                        List.of(), Set.of(), false);
        FileContribution empty = new FileContribution(path,
                List.of(), List.of(), Set.of(), false);

        Assertions.assertTrue(PgDbParser.isIncrementalAnalysisUsable(
                captured, captured, true, List.of()));
        Assertions.assertFalse(PgDbParser.isIncrementalAnalysisUsable(
                captured, captured, true, List.of("parse error")));
        Assertions.assertFalse(PgDbParser.isIncrementalAnalysisUsable(
                captured, drifted, true, List.of()));
        Assertions.assertFalse(PgDbParser.isIncrementalAnalysisUsable(
                captured, captured, false, List.of()));

        Assertions.assertTrue(PgDbParser.isIncrementalReplacementSafe(
                previous.path(), previous.definitions(), previous, true));
        Assertions.assertFalse(PgDbParser.isIncrementalReplacementSafe(
                previous.path(), previous.definitions(),
                changedSignature, true));
        Assertions.assertFalse(PgDbParser.isIncrementalReplacementSafe(
                previous.path(), previous.definitions(), empty, true));
        Assertions.assertFalse(PgDbParser.isIncrementalReplacementSafe(
                previous.path(), previous.definitions(), previous, false));
    }

    @Test
    void incrementalSnapshotRequiresMatchingLeaseAndPublicationGeneration() {
        ProjectIndexIdentity identity = new ProjectIndexIdentity(
                2, "15.0.0-neo1", "15.0.0-neo1", DatabaseType.PG,
                "00".repeat(32), new byte[32]);
        ProjectIndexIdentity otherIdentity = new ProjectIndexIdentity(
                2, "15.0.0-neo1", "15.0.0-neo1", DatabaseType.PG,
                "11".repeat(32), new byte[32]);
        var analysis = analysisLease(identity, 7);
        var validation = analysisLease(identity, 7);

        Assertions.assertTrue(
                PgDbParser.isIncrementalSnapshotConsistent(
                        identity, analysis, validation,
                        new ProjectIndexRevision(
                                "publication", 0, 7, identity)));
        Assertions.assertFalse(
                PgDbParser.isIncrementalSnapshotConsistent(
                        identity, analysisLease(identity, 6),
                        validation, null));
        Assertions.assertFalse(
                PgDbParser.isIncrementalSnapshotConsistent(
                        identity, analysis,
                        analysisLease(otherIdentity, 7), null));
        Assertions.assertFalse(
                PgDbParser.isIncrementalSnapshotConsistent(
                        identity, analysis, validation,
                        new ProjectIndexRevision(
                                "publication", 0, 8, identity)));
    }

    private static IncrementalProjectReferenceIndex.AnalysisLease
            analysisLease(ProjectIndexIdentity identity, long generation) {
        return new IncrementalProjectReferenceIndex.AnalysisLease() {
            @Override
            public ProjectIndexIdentity identity() {
                return identity;
            }

            @Override
            public long generation() {
                return generation;
            }

            @Override
            public List<ProjectFileStamp> fileStamps() {
                return List.of();
            }

            @Override
            public Optional<IncrementalFileMetadata> file(IndexPathRef path) {
                return Optional.empty();
            }

            @Override
            public List<MetaStatement> definitions(
                    ProjectIndexDefinitionSubject subject,
                    IndexPathRef excludedPath) {
                return List.of();
            }

            @Override
            public boolean anyFileMayHoldUnresolvedReferences() {
                return false;
            }

            @Override
            public void close() {
                // Test lease owns no resources.
            }
        };
    }

    @Test
    void expectedProjectIndexCancellationDoesNotInterruptWorker() {
        Thread.interrupted();
        OperationCanceledException cancelled =
                Assertions.assertThrows(
                        OperationCanceledException.class,
                        () -> PgDbParser
                                .propagateProjectIndexInterruption(
                                        new InterruptedException(
                                                "configuration changed"),
                                        true));

        Assertions.assertFalse(
                Thread.currentThread().isInterrupted());
        Assertions.assertInstanceOf(InterruptedException.class,
                cancelled.getCause());
    }

    @Test
    void externalProjectIndexInterruptionRemainsChecked() {
        Thread.interrupted();
        InterruptedException external =
                new InterruptedException("external interrupt");

        InterruptedException thrown = Assertions.assertThrows(
                InterruptedException.class,
                () -> PgDbParser.propagateProjectIndexInterruption(
                        external, false));

        Assertions.assertSame(external, thrown);
        Assertions.assertFalse(
                Thread.currentThread().isInterrupted());
    }

    @Test
    void oversizedIncrementalSnapshotFailsBeforeReadingFile(
            @TempDir Path directory) throws Exception {
        Path sql = directory.resolve("large.sql");
        try (FileChannel channel = FileChannel.open(sql,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            channel.position(
                    PgDbParser.MAX_INCREMENTAL_CAPTURE_BYTES);
            channel.write(ByteBuffer.wrap(new byte[] { 1 }));
        }
        Assertions.assertEquals(
                PgDbParser.MAX_INCREMENTAL_CAPTURE_BYTES + 1,
                Files.size(sql));

        Assertions.assertThrows(IOException.class,
                () -> PgDbParser.readIncrementalSnapshot(sql));
    }

    @Test
    void incrementalSnapshotsAcceptExactlySixteenMiBButNotOneByteMore(
            @TempDir Path directory) throws Exception {
        long halfBudget =
                PgDbParser.MAX_INCREMENTAL_CAPTURE_BYTES / 2;
        Path first = directory.resolve("first.sql");
        Path second = directory.resolve("second.sql");
        for (Path sql : List.of(first, second)) {
            try (FileChannel channel = FileChannel.open(sql,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                channel.position(halfBudget - 1);
                channel.write(ByteBuffer.wrap(new byte[] { 1 }));
            }
        }

        Assertions.assertEquals(halfBudget,
                PgDbParser.readIncrementalSnapshot(
                        first, halfBudget).length);
        Assertions.assertEquals(halfBudget,
                PgDbParser.readIncrementalSnapshot(
                        second, halfBudget).length);

        try (FileChannel channel = FileChannel.open(second,
                StandardOpenOption.WRITE)) {
            channel.position(halfBudget);
            channel.write(ByteBuffer.wrap(new byte[] { 1 }));
        }
        Assertions.assertThrows(IOException.class,
                () -> PgDbParser.readIncrementalSnapshot(
                        second, halfBudget));
    }

    @Test
    void queryMaterializesUnderStateLockBeforePackedStorageIsClosed()
            throws Exception {
        String path = "schema/table.sql";
        PgDbParser source = parserWithDefinition(path, "stable");
        var storage = new BlockingReferenceIndex(
                source.getDefsForPath(path).getFirst());
        var parser = new PgDbParser();
        parser.replaceReferenceIndexForTests(storage);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var query = executor.submit(() -> parser.getDefsForPath(path));
            Assertions.assertTrue(storage.queryStarted.await(5,
                    TimeUnit.SECONDS));
            var clear = executor.submit(parser::clear);

            Assertions.assertFalse(storage.closed.await(100,
                    TimeUnit.MILLISECONDS),
                    "storage must stay open while a query is materializing");
            storage.releaseQuery.countDown();

            Assertions.assertEquals("stable",
                    query.get(5, TimeUnit.SECONDS).getFirst().getName());
            clear.get(5, TimeUnit.SECONDS);
            Assertions.assertTrue(storage.closed.await(5,
                    TimeUnit.SECONDS));
            Assertions.assertFalse(storage.readAfterClose.get());
        } finally {
            storage.releaseQuery.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void cancelCurrentLoadInterruptsLoaderAndKeepsLastGoodIndex() throws Exception {
        String path = "schema/table.sql";
        PgDbParser parser = parserWithDefinition(path, "last_good");
        var loader = new CancellableLoader();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var load = executor.submit(() -> parser.loadReferences(loader));
            Assertions.assertTrue(loader.started.await(5, TimeUnit.SECONDS));

            parser.cancelCurrentLoad();

            ExecutionException failure = Assertions.assertThrows(ExecutionException.class,
                    () -> load.get(5, TimeUnit.SECONDS));
            Assertions.assertInstanceOf(InterruptedException.class, failure.getCause());
            Assertions.assertTrue(loader.closed.await(5, TimeUnit.SECONDS));
            Assertions.assertEquals(1, loader.cancelCalls.get());
            Assertions.assertEquals("last_good", parser.getDefsForPath(path).getFirst().getName());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void configurationInvalidationCancelsOnlyCurrentProjectIndexLoad()
            throws Exception {
        String path = "schema/table.sql";
        PgDbParser parser = parserWithDefinition(path, "last_good");
        var loader = new CancellableLoader();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var load = executor.submit(() -> parser.loadReferences(loader));
            Assertions.assertTrue(loader.started.await(5, TimeUnit.SECONDS));

            parser.invalidateLiveProjectIndexConfiguration();

            ExecutionException failure = Assertions.assertThrows(
                    ExecutionException.class,
                    () -> load.get(5, TimeUnit.SECONDS));
            Assertions.assertInstanceOf(InterruptedException.class,
                    failure.getCause());
            Assertions.assertTrue(loader.closed.await(5, TimeUnit.SECONDS));
            Assertions.assertEquals(1, loader.cancelCalls.get());
            Assertions.assertTrue(parser.getDefsForPath(path).isEmpty());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void persistedInvalidationDoesNotHoldParserStateLock()
            throws Exception {
        String path = "schema/table.sql";
        PgDbParser parser =
                parserWithDefinition(path, "last_good");
        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var invalidation = executor.submit(() ->
                    parser.invalidateLiveProjectIndexConfiguration(
                            () -> {
                                callbackStarted.countDown();
                                try {
                                    if (!releaseCallback.await(
                                            5, TimeUnit.SECONDS)) {
                                        throw new AssertionError(
                                                "callback was not released");
                                    }
                                } catch (InterruptedException ex) {
                                    Thread.currentThread().interrupt();
                                    throw new AssertionError(ex);
                                }
                            }));
            Assertions.assertTrue(callbackStarted.await(
                    5, TimeUnit.SECONDS));

            var query = executor.submit(
                    () -> parser.getDefsForPath(path));
            Assertions.assertTrue(
                    query.get(1, TimeUnit.SECONDS).isEmpty(),
                    "Queries must see detached state while disk cleanup waits");
            releaseCallback.countDown();
            invalidation.get(5, TimeUnit.SECONDS);
        } finally {
            releaseCallback.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void monitorCancellationReachesBlockedLoaderAndFailsClosed() throws Exception {
        String path = "schema/table.sql";
        PgDbParser parser = parserWithDefinition(path, "last_good");
        var loader = new CancellableLoader();
        var monitor = new NullProgressMonitor();
        var persistenceCalls = new AtomicInteger();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var build = executor.submit(() -> ProjectBuilderTestSupport.execute(monitor,
                    () -> parser.loadReferences(loader, monitor), parser::cancelCurrentLoad,
                    persistenceCalls::incrementAndGet));
            Assertions.assertTrue(loader.started.await(5, TimeUnit.SECONDS));

            long startedAt = System.nanoTime();
            monitor.setCanceled(true);

            ExecutionException failure = Assertions.assertThrows(ExecutionException.class,
                    () -> build.get(2, TimeUnit.SECONDS));
            Assertions.assertInstanceOf(InterruptedException.class, failure.getCause());
            Assertions.assertTrue(loader.cancelled.await(100, TimeUnit.MILLISECONDS));
            Assertions.assertTrue(loader.closed.await(100, TimeUnit.MILLISECONDS));
            Assertions.assertEquals(1, loader.cancelCalls.get());
            Assertions.assertTrue(System.nanoTime() - startedAt < TimeUnit.SECONDS.toNanos(2));
            Assertions.assertEquals("last_good", parser.getDefsForPath(path).getFirst().getName());
            Assertions.assertEquals(0, persistenceCalls.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void preparedUpdatePublishesOnlyWhenCommitted() {
        String path = "schema/table.sql";
        PgDbParser parser = parserWithDefinition(path, "last_good");
        PgDbParser replacement = parserWithDefinition(path, "restored");
        var restoredStorage = new ProjectReferencesStorage();
        restoredStorage.putReferences(
                Map.of(path, new ArrayList<>(replacement.getDefsForPath(path))), Map.of());

        PgDbParser.PreparedUpdate publication =
                parser.prepareReferenceIndexForTests(restoredStorage);

        Assertions.assertEquals("last_good", parser.getDefsForPath(path).getFirst().getName());
        publication.publish();
        Assertions.assertEquals("restored", parser.getDefsForPath(path).getFirst().getName());
    }

    @Test
    void configurationInvalidationClearsLiveStateAndRejectsStalePreparedUpdate() {
        String path = "schema/table.sql";
        PgDbParser parser = parserWithDefinition(path, "last_good");
        PgDbParser replacement = parserWithDefinition(path, "stale");
        var replacementStorage = new ProjectReferencesStorage();
        replacementStorage.putReferences(
                Map.of(path, new ArrayList<>(replacement.getDefsForPath(path))),
                Map.of());
        PgDbParser.PreparedUpdate stale =
                parser.prepareReferenceIndexForTests(replacementStorage);
        var closed = new AtomicInteger();
        parser.attachProjectIndexResource(closed::incrementAndGet);

        parser.invalidateLiveProjectIndexConfiguration();
        stale.publish();

        Assertions.assertTrue(parser.getDefsForPath(path).isEmpty());
        Assertions.assertEquals(1, closed.get());
    }

    @Test
    void throwingListenerDoesNotAbortCommittedPublication() {
        String path = "schema/table.sql";
        PgDbParser parser = parserWithDefinition(path, "last_good");
        PgDbParser replacement = parserWithDefinition(path, "restored");
        var restoredStorage = new ProjectReferencesStorage();
        restoredStorage.putReferences(
                Map.of(path, new ArrayList<>(replacement.getDefsForPath(path))), Map.of());
        parser.addListener(event -> { throw new IllegalStateException("listener failure"); });

        PgDbParser.PreparedUpdate publication =
                parser.prepareReferenceIndexForTests(restoredStorage);

        Assertions.assertDoesNotThrow(publication::publish);
        Assertions.assertEquals("restored", parser.getDefsForPath(path).getFirst().getName());
    }

    @Test
    void publicationEventsCannotBeOvertakenByConfigurationInvalidation()
            throws Exception {
        String path = "schema/table.sql";
        PgDbParser parser =
                parserWithDefinition(path, "last_good");
        PgDbParser replacement =
                parserWithDefinition(path, "restored");
        var restoredStorage = new ProjectReferencesStorage();
        restoredStorage.putReferences(
                Map.of(path, new ArrayList<>(
                        replacement.getDefsForPath(path))),
                Map.of());
        CountDownLatch markerStarted = new CountDownLatch(1);
        CountDownLatch releaseMarker = new CountDownLatch(1);
        var events = new CopyOnWriteArrayList<String>();
        parser.addListener(event -> {
            List<MetaStatement> definitions =
                    parser.getDefsForPath(path);
            events.add(definitions.isEmpty()
                    ? "empty"
                    : definitions.getFirst().getName());
        });
        PgDbParser.PreparedUpdate publication =
                parser.prepareReferenceIndexForTests(
                        restoredStorage, () -> {
                            markerStarted.countDown();
                            try {
                                if (!releaseMarker.await(
                                        5, TimeUnit.SECONDS)) {
                                    throw new AssertionError(
                                            "marker was not released");
                                }
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                                throw new AssertionError(ex);
                            }
                        });
        ExecutorService executor =
                Executors.newFixedThreadPool(2);
        try {
            var published = executor.submit(publication::publish);
            Assertions.assertTrue(markerStarted.await(
                    5, TimeUnit.SECONDS));
            var invalidated = executor.submit(() -> {
                parser.invalidateLiveProjectIndexConfiguration();
            });
            Assertions.assertFalse(invalidated.isDone(),
                    "Invalidation must preserve publication event order");

            releaseMarker.countDown();
            published.get(5, TimeUnit.SECONDS);
            invalidated.get(5, TimeUnit.SECONDS);
            Assertions.assertEquals(
                    List.of("restored", "empty"), events);
        } finally {
            releaseMarker.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void loadedReferenceNotificationCannotBeOvertakenByInvalidation()
            throws Exception {
        String path = "schema/table.sql";
        PgDbParser parser =
                parserWithDefinition(path, "last_good");
        PgDbParser replacement =
                parserWithDefinition(path, "loaded");
        var loadedStorage = new ProjectReferencesStorage();
        loadedStorage.putReferences(
                Map.of(path, new ArrayList<>(
                        replacement.getDefsForPath(path))),
                Map.of());
        CountDownLatch listenerStarted = new CountDownLatch(1);
        CountDownLatch releaseListener = new CountDownLatch(1);
        var events = new CopyOnWriteArrayList<String>();
        parser.addListener(event -> {
            List<MetaStatement> definitions =
                    parser.getDefsForPath(path);
            events.add(definitions.isEmpty()
                    ? "empty"
                    : definitions.getFirst().getName());
            if (events.size() == 1) {
                listenerStarted.countDown();
                try {
                    if (!releaseListener.await(
                            5, TimeUnit.SECONDS)) {
                        throw new AssertionError(
                                "listener was not released");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(ex);
                }
            }
        });
        ExecutorService executor =
                Executors.newFixedThreadPool(2);
        try {
            var published = executor.submit(() ->
                    parser.publishLoadedReferences(
                            loadedStorage, 0L));
            Assertions.assertTrue(listenerStarted.await(
                    5, TimeUnit.SECONDS));
            var invalidated = executor.submit(() -> {
                parser.invalidateLiveProjectIndexConfiguration();
            });
            Assertions.assertFalse(invalidated.isDone(),
                    "Invalidation must not overtake a loaded-reference event");

            releaseListener.countDown();
            published.get(5, TimeUnit.SECONDS);
            invalidated.get(5, TimeUnit.SECONDS);
            Assertions.assertEquals(
                    List.of("loaded", "empty"), events);
        } finally {
            releaseListener.countDown();
            executor.shutdownNow();
        }
    }

    private static PgDbParser parserWithDefinition(String path, String name) {
        ObjectReference reference = new ObjectReference("public", name, DbObjType.TABLE);
        ObjectLocation location = new ObjectLocation.Builder()
                .setFilePath(path)
                .setOffset(1)
                .setLineNumber(1)
                .setCharPositionInLine(1)
                .setReference(reference)
                .setLocationType(ObjectLocation.LocationType.DEFINITION)
                .build();
        var definitions = new HashMap<String, List<MetaStatement>>();
        definitions.put(path, new ArrayList<>(List.of(new MetaStatement(location))));
        var storage = new ProjectReferencesStorage();
        storage.putReferences(definitions, Map.of());
        var parser = new PgDbParser();
        parser.replaceReferenceIndexForTests(storage);
        return parser;
    }

    private static final class CancellableLoader implements ILoader {

        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch cancelled = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private final AtomicInteger cancelCalls = new AtomicInteger();
        private final ISettings settings = new CoreSettings();

        @Override
        public IDatabase load() throws InterruptedException {
            return loadAndAnalyze();
        }

        @Override
        public IDatabase loadAndAnalyze() throws InterruptedException {
            started.countDown();
            if (!cancelled.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("loader was not cancelled");
            }
            throw new InterruptedException("cancelled");
        }

        @Override
        public void cancel() {
            cancelCalls.incrementAndGet();
            cancelled.countDown();
        }

        @Override
        public IDatabase getDatabase() {
            return null;
        }

        @Override
        public String getDatabaseName() {
            return "cancellable";
        }

        @Override
        public ISettings getSettings() {
            return settings;
        }

        @Override
        public List<Object> getErrors() {
            return List.of();
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    private static final class FailingCaptureLoader
            implements ILoader, IProjectInputFingerprintCapture {

        private final boolean failEnable;
        private final boolean failGetter;
        private final AtomicBoolean loaded = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final ISettings settings = new CoreSettings();
        private final IDatabase database = new PgDatabase();

        private FailingCaptureLoader(boolean failEnable,
                boolean failGetter) {
            this.failEnable = failEnable;
            this.failGetter = failGetter;
        }

        @Override
        public void enableInputFingerprintCapture() {
            if (failEnable) {
                throw new IllegalStateException("capture unavailable");
            }
        }

        @Override
        public List<ProjectInputFingerprint>
                getCapturedInputFingerprints() {
            if (failGetter) {
                throw new IllegalStateException("capture unavailable");
            }
            return List.of();
        }

        @Override
        public IDatabase load() {
            loaded.set(true);
            return database;
        }

        @Override
        public IDatabase loadAndAnalyze() {
            return load();
        }

        @Override
        public IDatabase getDatabase() {
            return database;
        }

        @Override
        public String getDatabaseName() {
            return "capture";
        }

        @Override
        public ISettings getSettings() {
            return settings;
        }

        @Override
        public List<Object> getErrors() {
            return List.of();
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    private static final class BlockingReferenceIndex
            implements ProjectReferenceIndex {

        private final MetaStatement definition;
        private final CountDownLatch queryStarted = new CountDownLatch(1);
        private final CountDownLatch releaseQuery = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private final AtomicBoolean open = new AtomicBoolean(true);
        private final AtomicBoolean readAfterClose = new AtomicBoolean();

        private BlockingReferenceIndex(MetaStatement definition) {
            this.definition = definition;
        }

        @Override
        public Set<ObjectLocation> referencesForPath(String path) {
            return Set.of();
        }

        @Override
        public List<MetaStatement> definitionsForPath(String path) {
            queryStarted.countDown();
            try {
                if (!releaseQuery.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("query timeout");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError(ex);
            }
            if (!open.get()) {
                readAfterClose.set(true);
                throw new IllegalStateException("closed during query");
            }
            return List.of(definition);
        }

        @Override
        public List<MetaStatement> definitionsMatching(
                ObjectLocation object) {
            return List.of(definition);
        }

        @Override
        public List<ObjectLocation> referencesMatching(
                ObjectLocation object) {
            return List.of();
        }

        @Override
        public List<MetaStatement> completionCandidates(String text) {
            return List.of(definition);
        }

        @Override
        public java.util.stream.Stream<MetaStatement> allDefinitions() {
            return java.util.stream.Stream.of(definition);
        }

        @Override
        public java.util.stream.Stream<ObjectLocation> allReferences() {
            return java.util.stream.Stream.empty();
        }

        @Override
        public ProjectReferencesStorage mutableCopy() {
            throw new AssertionError("must not materialize");
        }

        @Override
        public void close() {
            open.set(false);
            closed.countDown();
        }
    }
}
