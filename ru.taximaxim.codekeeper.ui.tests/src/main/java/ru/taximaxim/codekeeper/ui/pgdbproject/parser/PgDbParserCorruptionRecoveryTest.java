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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.base.schema.meta.MetaStatement;

import ru.taximaxim.codekeeper.ui.DatabaseType;
import ru.taximaxim.codekeeper.ui.projectindex.FileContribution;
import ru.taximaxim.codekeeper.ui.projectindex.IndexPathOrigin;
import ru.taximaxim.codekeeper.ui.projectindex.IndexPathRef;
import ru.taximaxim.codekeeper.ui.projectindex.PackedDefinition;
import ru.taximaxim.codekeeper.ui.projectindex.PackedLocation;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectFileStamp;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexCorruptionTestSupport;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexData;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexDelta;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexFormatException;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexIdentity;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexManifest;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexOpenResult.Status;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexStore;
import ru.taximaxim.codekeeper.ui.projectindex.ReferenceMatchKey;
import ru.taximaxim.codekeeper.ui.utils.ProjectUtils;

class PgDbParserCorruptionRecoveryTest {

    @Test
    void backgroundBuildRuleContainsNestedProjectBuildRule() {
        var root = ResourcesPlugin.getWorkspace().getRoot();
        var rule = PgDbParser.projectIndexBuildRule();

        assertSame(root, rule);
        assertTrue(rule.contains(root));
    }

    private record Query(String name,
            BiFunction<PgDbParser, ObjectLocation, Collection<?>> run) { }

    @TempDir
    Path matrixTemp;

    @Test
    void lazyBaseCorruptionCleansOnceThenSupportsColdAndWarmRepair(
            @TempDir Path temp) throws Exception {
        Path projectRoot = Files.createDirectories(temp.resolve("project"));
        Path libraryRoot = Files.createDirectories(temp.resolve("library"));
        Path definitionFile = projectRoot.resolve("SCHEMA/app/TABLE/item.sql");
        ProjectIndexData source = data(projectRoot, definitionFile);
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(source.manifest());
        Path storeDirectory = temp.resolve("store");
        var store = new ProjectIndexStore(storeDirectory);
        store.publish(source, () -> false);
        var recoveries = new AtomicInteger();

        try (var opened = store.open(identity)) {
            assertTrue(opened.status() == Status.HIT);
            var view = opened.view().orElseThrow();
            var packed = new PackedProjectReferenceIndex(view, projectRoot,
                    libraryRoot);
            var parser = parserWithRecovery(() -> {
                PgDbParser.cleanProjectIndexStore(storeDirectory);
                recoveries.incrementAndGet();
            });
            parser.replaceReferenceIndexForTests(packed);
            ProjectIndexCorruptionTestSupport
                    .corruptFirstBaseDefinitionBlock(view);

            assertTrue(parser.getDefsForPath(
                    definitionFile.toString()).isEmpty());
            assertTrue(parser.getDefsForPath(
                    definitionFile.toString()).isEmpty());
            assertTrue(recoveries.get() == 1);
            assertTrue(Files.notExists(storeDirectory.resolve("current")));
            assertThrows(IllegalStateException.class,
                    () -> packed.definitionsForPath(
                            definitionFile.toString()));
        }

        store.publish(source, () -> false);
        try (var repaired = store.open(identity)) {
            assertEquals(Status.HIT, repaired.status());
            var parser = parserWithRecovery(recoveries::incrementAndGet);
            parser.replaceReferenceIndexForTests(
                    new PackedProjectReferenceIndex(
                            repaired.view().orElseThrow(), projectRoot,
                            libraryRoot));
            assertEquals(1, parser.getDefsForPath(
                    definitionFile.toString()).size());
            parser.clear();
        }
        try (var restarted = store.open(identity)) {
            assertEquals(Status.HIT, restarted.status());
            assertEquals(0,
                    restarted.view().orElseThrow().cacheBlocksRead());
            var parser = parserWithRecovery(recoveries::incrementAndGet);
            parser.replaceReferenceIndexForTests(
                    new PackedProjectReferenceIndex(
                            restarted.view().orElseThrow(), projectRoot,
                            libraryRoot));
            assertEquals(1, parser.getDefsForPath(
                    definitionFile.toString()).size());
            assertEquals(1, recoveries.get());
            parser.clear();
        }
    }

    @Test
    void lazyJournalReplacementCorruptionFailsClosedAndCleansStore(
            @TempDir Path temp) throws Exception {
        Path projectRoot = Files.createDirectories(temp.resolve("project"));
        Path libraryRoot = Files.createDirectories(temp.resolve("library"));
        Path definitionFile = projectRoot.resolve("SCHEMA/app/TABLE/item.sql");
        ProjectIndexData source = data(projectRoot, definitionFile);
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(source.manifest());
        Path storeDirectory = temp.resolve("store");
        var store = new ProjectIndexStore(storeDirectory);
        store.publish(source, () -> false);
        try (var initial = store.open(identity)) {
            assertEquals(ProjectIndexStore.AppendResult.APPENDED,
                    store.append(initial.view().orElseThrow(),
                            new ProjectIndexDelta(List.of(
                                    ProjectIndexDelta.Change.replace(
                                            source.manifest().files()
                                                    .getFirst(),
                                            source.files().getFirst()))),
                            () -> false));
        }
        var recoveries = new AtomicInteger();

        try (var opened = store.open(identity)) {
            assertEquals(Status.HIT, opened.status());
            var view = opened.view().orElseThrow();
            assertEquals(0, view.cacheBlocksRead());
            var packed = new PackedProjectReferenceIndex(view, projectRoot,
                    libraryRoot);
            var parser = parserWithRecovery(() -> {
                PgDbParser.cleanProjectIndexStore(storeDirectory);
                recoveries.incrementAndGet();
            });
            parser.replaceReferenceIndexForTests(packed);
            ProjectIndexCorruptionTestSupport
                    .corruptFirstJournalDefinitionBlock(view,
                            source.manifest().files().getFirst().path());

            assertTrue(parser.getDefsForPath(
                    definitionFile.toString()).isEmpty());
            assertEquals(1, recoveries.get());
            assertFalse(Files.exists(storeDirectory.resolve("current")));
            assertThrows(IllegalStateException.class,
                    () -> packed.definitionsForPath(
                            definitionFile.toString()));
        }
    }

    @Test
    void manualCleanupRetiresLivePackedIndexBeforeDeletingCurrent(
            @TempDir Path temp) throws Exception {
        Path projectRoot = Files.createDirectories(temp.resolve("project"));
        Path libraryRoot = Files.createDirectories(temp.resolve("library"));
        Path definitionFile = projectRoot.resolve("SCHEMA/app/TABLE/item.sql");
        ProjectIndexData source = data(projectRoot, definitionFile);
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(source.manifest());
        Path storeDirectory = temp.resolve("store");
        var store = new ProjectIndexStore(storeDirectory);
        store.publish(source, () -> false);
        var recoveries = new AtomicInteger();

        try (var opened = store.open(identity)) {
            assertEquals(Status.HIT, opened.status());
            var packed = new PackedProjectReferenceIndex(
                    opened.view().orElseThrow(), projectRoot, libraryRoot);
            var parser = parserWithRecovery(recoveries::incrementAndGet);
            parser.replaceReferenceIndexForTests(packed);
            assertEquals(1, parser.getDefsForPath(
                    definitionFile.toString()).size());

            PgDbParser.retireLiveProjectIndex(parser);
            PgDbParser.cleanProjectIndexStore(storeDirectory);

            assertTrue(parser.getDefsForPath(
                    definitionFile.toString()).isEmpty());
            assertFalse(Files.exists(storeDirectory.resolve("current")));
            assertEquals(0, recoveries.get());
            assertThrows(IllegalStateException.class,
                    () -> packed.definitionsForPath(
                            definitionFile.toString()));
        }
    }

    @Test
    void manualCleanAllUsesStoreCleanupForEveryProjectDirectory(
            @TempDir Path temp) throws Exception {
        Path projectRoot = Files.createDirectories(temp.resolve("project"));
        Path definitionFile = projectRoot.resolve("SCHEMA/app/TABLE/item.sql");
        ProjectIndexData source = data(projectRoot, definitionFile);
        Path root = Files.createDirectories(temp.resolve("projects-v2"));
        Path first = root.resolve("first");
        Path second = root.resolve("second");
        new ProjectIndexStore(first).publish(source, () -> false);
        new ProjectIndexStore(second).publish(source, () -> false);

        PgDbParser.cleanAllProjectIndexStores(root);

        assertFalse(Files.exists(first.resolve("current")));
        assertFalse(Files.exists(second.resolve("current")));
        assertFalse(Files.exists(root));
    }

    @Test
    void productionCorruptionRecoveryKeepsParserAndSchedulesFullBuild(
            @TempDir Path projectRoot) throws Exception {
        var buildStarted = new CountDownLatch(1);
        var buildKind = new AtomicInteger(-1);
        IProject project = project(projectRoot, "corrupt-recovery",
                buildStarted, buildKind);
        int[] initialBuild = { IncrementalProjectBuilder.AUTO_BUILD };
        PgDbParser parser =
                PgDbParser.getParserForBuilder(project, initialBuild);
        parser.replaceReferenceIndexForTests(new CorruptIndex());
        try {
            assertEquals(IncrementalProjectBuilder.AUTO_BUILD,
                    initialBuild[0]);
            assertTrue(parser.getDefsForPath("/project/item.sql").isEmpty());

            int[] nextBuild = {
                    IncrementalProjectBuilder.INCREMENTAL_BUILD };
            assertSame(parser,
                    PgDbParser.getParserForBuilder(project, nextBuild));
            assertEquals(IncrementalProjectBuilder.INCREMENTAL_BUILD,
                    nextBuild[0]);
            assertTrue(buildStarted.await(5, TimeUnit.SECONDS));
            assertEquals(IncrementalProjectBuilder.FULL_BUILD,
                    buildKind.get());
        } finally {
            PgDbParser.removeProject(project);
        }
    }

    @Test
    void configurationInvalidationKeepsParserIdentityAndSchedulesFullBuild(
            @TempDir Path projectRoot) throws Exception {
        var buildStarted = new CountDownLatch(1);
        var buildKind = new AtomicInteger(-1);
        IProject project = project(projectRoot, "config-invalidation",
                buildStarted, buildKind);
        int[] initialBuild = { IncrementalProjectBuilder.AUTO_BUILD };
        PgDbParser parser =
                PgDbParser.getParserForBuilder(project, initialBuild);
        var live = new TrackingIndex();
        parser.replaceReferenceIndexForTests(live);
        try {
            PgDbParser.invalidateProjectIndexConfiguration(project);

            assertEquals(1, live.closes.get());
            assertSame(parser, PgDbParser.getParserForBuilder(project,
                    new int[] {
                            IncrementalProjectBuilder.INCREMENTAL_BUILD }));
            assertTrue(buildStarted.await(5, TimeUnit.SECONDS));
            assertEquals(IncrementalProjectBuilder.FULL_BUILD,
                    buildKind.get());
        } finally {
            PgDbParser.removeProject(project);
        }
    }

    @Test
    void manualProjectCleanKeepsParserIdentityAndListeners(
            @TempDir Path projectRoot) throws Exception {
        IProject project = project(projectRoot, "manual-project-clean",
                new CountDownLatch(1), new AtomicInteger());
        PgDbParser parser = PgDbParser.getParserForBuilder(project,
                new int[] { IncrementalProjectBuilder.AUTO_BUILD });
        var listenerCalls = new AtomicInteger();
        parser.addListener(event -> listenerCalls.incrementAndGet());
        var live = new TrackingIndex();
        parser.replaceReferenceIndexForTests(live);
        try {
            PgDbParser.clean(project);

            assertEquals(1, live.closes.get());
            assertEquals(1, listenerCalls.get());
            assertSame(parser, PgDbParser.getParserForBuilder(project,
                    new int[] {
                            IncrementalProjectBuilder.INCREMENTAL_BUILD }));
            parser.notifyListeners();
            assertEquals(2, listenerCalls.get());
        } finally {
            PgDbParser.removeProject(project);
        }
    }

    @Test
    void manualCleanAllKeepsParserAndSchedulesOneFullBuild(
            @TempDir Path projectRoot) throws Exception {
        var buildStarted = new CountDownLatch(1);
        var buildKind = new AtomicInteger(-1);
        IProject project = project(projectRoot, "manual-clean-all",
                buildStarted, buildKind);
        PgDbParser parser = PgDbParser.getParserForBuilder(project,
                new int[] { IncrementalProjectBuilder.AUTO_BUILD });
        var listenerCalls = new AtomicInteger();
        parser.addListener(event -> listenerCalls.incrementAndGet());
        var live = new TrackingIndex();
        parser.replaceReferenceIndexForTests(live);
        try {
            PgDbParser.cleanAll();

            assertEquals(1, live.closes.get());
            assertEquals(1, listenerCalls.get());
            assertSame(parser, PgDbParser.getParserForBuilder(project,
                    new int[] {
                            IncrementalProjectBuilder.INCREMENTAL_BUILD }));
            assertTrue(buildStarted.await(5, TimeUnit.SECONDS));
            assertEquals(IncrementalProjectBuilder.FULL_BUILD,
                    buildKind.get());
        } finally {
            PgDbParser.removeProject(project);
        }
    }

    @TestFactory
    Stream<DynamicTest> everyQueryEntryPointFailsClosedOnActualLazyCorruption() {
        List<Query> queries = List.of(
                new Query("definitions matching",
                        (parser, object) -> parser
                                .getDefinitionsForObj(object).toList()),
                new Query("references matching",
                        (parser, object) -> parser
                                .getReferencesForObj(object).toList()),
                new Query("completion",
                        (parser, object) -> parser
                                .getCompletionCandidates("item")
                                .toList()),
                new Query("references for path",
                        (parser, object) -> parser.getObjsForPath(
                                object.getFilePath())),
                new Query("definitions for path",
                        (parser, object) -> parser.getDefsForPath(
                                object.getFilePath())),
                new Query("all definitions",
                        (parser, object) -> parser
                                .getAllObjDefinitions().toList()),
                new Query("all references",
                        (parser, object) -> parser
                                .getAllObjReferences().toList()));

        return Stream.of(false, true).flatMap(journal -> queries.stream()
                .map(query -> DynamicTest.dynamicTest(
                        (journal ? "journal: " : "base: ") + query.name(),
                        () -> assertActualLazyCorruptionFailsClosed(query,
                                journal))));
    }

    @Test
    void recoveryRunsOnceOutsideStateLock() throws Exception {
        var recoveryEntered = new CountDownLatch(1);
        var releaseRecovery = new CountDownLatch(1);
        var recoveries = new AtomicInteger();
        var corrupt = new CorruptIndex();
        var parser = parserWithRecovery(() -> {
            recoveries.incrementAndGet();
            recoveryEntered.countDown();
            try {
                if (!releaseRecovery.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Recovery was not released");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError(ex);
            }
        });
        parser.replaceReferenceIndexForTests(corrupt);
        var executor = Executors.newFixedThreadPool(8);
        try {
            var first = executor.submit(
                    () -> parser.getDefsForPath("/project/item.sql"));
            assertTrue(recoveryEntered.await(5, TimeUnit.SECONDS));
            var waiting = Stream.generate(() -> executor.submit(
                    () -> parser.getAllObjDefinitions().toList()))
                    .limit(16)
                    .toList();
            for (var result : waiting) {
                assertTrue(result.get(1, TimeUnit.SECONDS).isEmpty());
            }
            releaseRecovery.countDown();
            assertTrue(first.get(5, TimeUnit.SECONDS).isEmpty());
        } finally {
            releaseRecovery.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertEquals(1, recoveries.get());
        assertEquals(1, corrupt.closes.get());
    }

    @Test
    void corruptionNotificationCannotBeOvertakenByInvalidation()
            throws Exception {
        var parser = parserWithRecovery(() -> { });
        parser.replaceReferenceIndexForTests(new CorruptIndex());
        var listenerStarted = new CountDownLatch(1);
        var releaseListener = new CountDownLatch(1);
        var events = new CopyOnWriteArrayList<Integer>();
        parser.addListener(event -> {
            events.add(parser.getAllObjDefinitions().toList().size());
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
        var executor = Executors.newFixedThreadPool(2);
        try {
            var recovery = executor.submit(() ->
                    parser.getDefsForPath("/project/item.sql"));
            assertTrue(listenerStarted.await(5, TimeUnit.SECONDS));
            var invalidation = executor.submit(() -> {
                parser.invalidateLiveProjectIndexConfiguration();
            });
            assertFalse(invalidation.isDone(),
                    "Invalidation must not overtake recovery notification");

            releaseListener.countDown();
            assertTrue(recovery.get(
                    5, TimeUnit.SECONDS).isEmpty());
            invalidation.get(5, TimeUnit.SECONDS);
            assertEquals(List.of(0, 0), events);
        } finally {
            releaseListener.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(
                    5, TimeUnit.SECONDS));
        }
    }

    @Test
    void corruptionRejectsAlreadyPreparedPublication() {
        var parser = parserWithRecovery(() -> { });
        var stale = new TrackingIndex();
        var prepared = parser.prepareReferenceIndexForTests(stale);
        parser.replaceReferenceIndexForTests(new CorruptIndex());

        assertTrue(parser.getDefsForPath("/project/item.sql").isEmpty());
        prepared.publish();

        assertEquals(1, stale.closes.get());
        assertTrue(parser.getDefsForPath("/project/item.sql").isEmpty());
    }

    private void assertActualLazyCorruptionFailsClosed(Query query,
            boolean journal) throws Exception {
        Path caseRoot = Files.createDirectories(matrixTemp.resolve(
                (journal ? "journal-" : "base-")
                        + query.name().replace(' ', '-')));
        Path projectRoot =
                Files.createDirectories(caseRoot.resolve("project"));
        Path libraryRoot =
                Files.createDirectories(caseRoot.resolve("library"));
        Path definitionFile =
                projectRoot.resolve("SCHEMA/app/TABLE/item.sql");
        ProjectIndexData source = data(projectRoot, definitionFile);
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(source.manifest());
        Path storeDirectory = caseRoot.resolve("store");
        var store = new ProjectIndexStore(storeDirectory);
        store.publish(source, () -> false);
        if (journal) {
            try (var initial = store.open(identity)) {
                assertEquals(ProjectIndexStore.AppendResult.APPENDED,
                        store.append(initial.view().orElseThrow(),
                                new ProjectIndexDelta(List.of(
                                        ProjectIndexDelta.Change.replace(
                                                source.manifest().files()
                                                        .getFirst(),
                                                source.files().getFirst()))),
                                () -> false));
            }
        }
        var recoveries = new AtomicInteger();

        try (var opened = store.open(identity)) {
            assertEquals(Status.HIT, opened.status());
            var view = opened.view().orElseThrow();
            assertEquals(0, view.cacheBlocksRead());
            var packed = new PackedProjectReferenceIndex(view, projectRoot,
                    libraryRoot);
            var parser = parserWithRecovery(() -> {
                PgDbParser.cleanProjectIndexStore(storeDirectory);
                recoveries.incrementAndGet();
            });
            parser.replaceReferenceIndexForTests(packed);
            if (journal) {
                ProjectIndexCorruptionTestSupport
                        .corruptJournalPayloadBlocks(view,
                                source.manifest().files().getFirst().path());
            } else {
                ProjectIndexCorruptionTestSupport
                        .corruptBasePayloadBlocks(view);
            }

            ObjectLocation object = location(definitionFile);
            assertTrue(query.run().apply(parser, object).isEmpty());
            assertTrue(query.run().apply(parser, object).isEmpty());
            assertEquals(1, recoveries.get());
            assertFalse(Files.exists(storeDirectory.resolve("current")));
            assertThrows(IllegalStateException.class,
                    () -> packed.definitionsForPath(
                            definitionFile.toString()));
        }
    }

    private static PgDbParser parserWithRecovery(Runnable recovery) {
        return new PgDbParser(recovery);
    }

    private static IProject project(Path root, String name,
            CountDownLatch buildStarted, AtomicInteger buildKind)
            throws Exception {
        return (IProject) Proxy.newProxyInstance(
                PgDbParserCorruptionRecoveryTest.class.getClassLoader(),
                new Class<?>[] { IProject.class },
                (proxy, method, arguments) -> {
                    String methodName = method.getName();
                    if (methodName.equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    if (methodName.equals("equals")) {
                        return proxy == arguments[0];
                    }
                    if (methodName.equals("toString")) {
                        return "Test project " + name;
                    }
                    if (methodName.equals("getName")) {
                        return name;
                    }
                    if (methodName.equals("getProject")) {
                        return proxy;
                    }
                    if (methodName.equals("getLocationURI")) {
                        return root.toUri();
                    }
                    if (methodName.equals("getFullPath")) {
                        return new org.eclipse.core.runtime.Path("/" + name);
                    }
                    if (methodName.equals("getProjectRelativePath")) {
                        return org.eclipse.core.runtime.Path.EMPTY;
                    }
                    if (methodName.equals("getWorkspace")) {
                        return ResourcesPlugin.getWorkspace();
                    }
                    if (methodName.equals("getType")) {
                        return IResource.PROJECT;
                    }
                    if (methodName.equals("isAccessible")
                            || methodName.equals("exists")
                            || methodName.equals("isOpen")) {
                        return true;
                    }
                    if (methodName.equals("hasNature")) {
                        return ProjectUtils.NATURE_ID.equals(arguments[0]);
                    }
                    if (methodName.equals("build")) {
                        buildKind.set((int) arguments[0]);
                        buildStarted.countDown();
                        return null;
                    }
                    if (methodName.equals("contains")
                            || methodName.equals("isConflicting")) {
                        return proxy == arguments[0];
                    }
                    Class<?> type = method.getReturnType();
                    if (!type.isPrimitive()) {
                        return type.isArray()
                                ? Array.newInstance(
                                        type.getComponentType(), 0)
                                : null;
                    }
                    if (type == boolean.class) {
                        return false;
                    }
                    if (type == char.class) {
                        return '\0';
                    }
                    return 0;
                });
    }

    private static ProjectIndexData data(Path projectRoot,
            Path definitionFile) {
        IndexPathRef path = new IndexPathRef(IndexPathOrigin.PROJECT,
                projectRoot.relativize(definitionFile).toString());
        ObjectReference table =
                new ObjectReference("app", "item", DbObjType.TABLE);
        ObjectLocation definition = new ObjectLocation.Builder()
                .setFilePath(definitionFile.toString())
                .setOffset(1)
                .setLineNumber(1)
                .setCharPositionInLine(1)
                .setLength(4)
                .setReference(table)
                .setLocationType(ObjectLocation.LocationType.DEFINITION)
                .build();
        MetaStatement statement = new MetaStatement(definition);
        FileContribution contribution = new FileContribution(path,
                List.of(PackedDefinition.from(statement, path)),
                List.of(PackedLocation.from(definition, path)),
                Set.of(ReferenceMatchKey.from(definition)), false);
        ProjectFileStamp stamp = new ProjectFileStamp(path, 1L, 10L, 2L,
                digest(path.relativePath()));
        ProjectIndexManifest manifest = new ProjectIndexManifest(2, 0, 2,
                "15.0.0-neo1", "15.0.0-neo1", DatabaseType.PG,
                HexFormat.of().formatHex(digest("project")),
                digest("configuration"), 1L, List.of(stamp));
        return new ProjectIndexData(manifest, List.of(contribution));
    }

    private static ObjectLocation location(Path file) {
        ObjectReference table =
                new ObjectReference("app", "item", DbObjType.TABLE);
        return new ObjectLocation.Builder()
                .setFilePath(file.toString())
                .setOffset(1)
                .setLineNumber(1)
                .setCharPositionInLine(1)
                .setLength(4)
                .setReference(table)
                .setLocationType(ObjectLocation.LocationType.DEFINITION)
                .build();
    }

    private static PackedProjectReferenceIndex.ProjectIndexAccessException
            corruptFailure() {
        try {
            Constructor<PackedProjectReferenceIndex.ProjectIndexAccessException>
                    constructor =
                            PackedProjectReferenceIndex.ProjectIndexAccessException.class
                                    .getDeclaredConstructor(
                                            ProjectIndexFormatException.class);
            constructor.setAccessible(true);
            return constructor.newInstance(
                    new ProjectIndexFormatException("corrupt"));
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private static final class CorruptIndex
            implements ProjectReferenceIndex {

        private final AtomicInteger closes = new AtomicInteger();

        private static RuntimeException failure() {
            return corruptFailure();
        }

        @Override
        public Set<ObjectLocation> referencesForPath(String path) {
            throw failure();
        }

        @Override
        public List<MetaStatement> definitionsForPath(String path) {
            throw failure();
        }

        @Override
        public List<MetaStatement> definitionsMatching(
                ObjectLocation object) {
            throw failure();
        }

        @Override
        public List<ObjectLocation> referencesMatching(
                ObjectLocation object) {
            throw failure();
        }

        @Override
        public List<MetaStatement> completionCandidates(String text) {
            throw failure();
        }

        @Override
        public Stream<MetaStatement> allDefinitions() {
            throw failure();
        }

        @Override
        public Stream<ObjectLocation> allReferences() {
            throw failure();
        }

        @Override
        public ProjectReferencesStorage mutableCopy() {
            throw failure();
        }

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }

    private static final class TrackingIndex
            implements ProjectReferenceIndex {

        private final AtomicInteger closes = new AtomicInteger();

        @Override
        public Set<ObjectLocation> referencesForPath(String path) {
            return Set.of();
        }

        @Override
        public List<MetaStatement> definitionsForPath(String path) {
            return List.of();
        }

        @Override
        public List<MetaStatement> definitionsMatching(
                ObjectLocation object) {
            return List.of();
        }

        @Override
        public List<ObjectLocation> referencesMatching(
                ObjectLocation object) {
            return List.of();
        }

        @Override
        public List<MetaStatement> completionCandidates(String text) {
            return List.of();
        }

        @Override
        public Stream<MetaStatement> allDefinitions() {
            return Stream.empty();
        }

        @Override
        public Stream<ObjectLocation> allReferences() {
            return Stream.empty();
        }

        @Override
        public ProjectReferencesStorage mutableCopy() {
            return new ProjectReferencesStorage();
        }

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256") //$NON-NLS-1$
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
