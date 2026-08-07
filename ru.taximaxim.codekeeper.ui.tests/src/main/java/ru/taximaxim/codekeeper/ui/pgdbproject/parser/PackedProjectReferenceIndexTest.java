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

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
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
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexData;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexDefinitionSubject;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexIdentity;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexManifest;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexStore;
import ru.taximaxim.codekeeper.ui.projectindex.ReferenceMatchKey;

class PackedProjectReferenceIndexTest {

    @Test
    void packedQueriesMatchInMemorySemanticsWithoutMaterializingAllFiles(
            @TempDir Path tempDir) throws Exception {
        Path projectRoot = Files.createDirectories(tempDir.resolve("project"));
        Path libraryRoot = Files.createDirectories(tempDir.resolve("libraries"));
        Path definitionFile = createSql(projectRoot, "SCHEMA/app/TABLE/item.sql");
        Path referenceFile = createSql(projectRoot, "SCHEMA/app/FUNCTION/use_item.sql");
        IndexPathRef definitionPath = path(projectRoot, definitionFile);
        IndexPathRef referencePath = path(projectRoot, referenceFile);
        ObjectReference table = new ObjectReference("app", "item", DbObjType.TABLE);
        ObjectLocation definition = location(definitionFile, table,
                ObjectLocation.LocationType.DEFINITION, 4);
        ObjectLocation reference = location(referenceFile, table,
                ObjectLocation.LocationType.REFERENCE, 18);
        MetaStatement statement = new MetaStatement(definition);

        ProjectIndexData data = data(List.of(
                contribution(definitionPath, statement, definition),
                new FileContribution(referencePath, List.of(),
                        List.of(PackedLocation.from(reference, referencePath)),
                        Set.of(ReferenceMatchKey.from(reference)), false)));
        Path storePath = tempDir.resolve("store");
        var store = new ProjectIndexStore(storePath);
        assertEquals(ProjectIndexStore.PublishResult.PUBLISHED,
                store.publish(data, () -> false));
        var opened = store.open(ProjectIndexIdentity.from(data.manifest()));
        assertEquals(ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexOpenResult.Status.HIT,
                opened.status());
        var view = opened.view().orElseThrow();
        var packed = new PackedProjectReferenceIndex(view, projectRoot, libraryRoot);

        assertEquals(List.of("item"), packed.definitionsMatching(reference).stream()
                .map(MetaStatement::getName).toList());
        assertEquals(Set.of(definition, reference),
                Set.copyOf(packed.referencesMatching(reference)));
        assertEquals(List.of("item"), packed.completionCandidates("ITEM").stream()
                .map(MetaStatement::getName).toList());
        assertEquals(0, view.decodedContributionCount());

        assertEquals(List.of("item"), packed.definitionsForPath(
                definitionFile.toString()).stream().map(MetaStatement::getName).toList());
        assertEquals(Set.of(reference), packed.referencesForPath(referenceFile.toString()));
        assertTrue(packed.definitionsForPath(
                tempDir.resolve("outside.sql").toString()).isEmpty());
        assertThrows(UnsupportedOperationException.class,
                packed::mutableCopy);

        packed.close();
        assertThrows(IllegalStateException.class,
                () -> packed.referencesMatching(reference));
    }

    @Test
    void matchKeyFromLiveLocationPreservesLegacyCompareIdentity(
            @TempDir Path tempDir) {
        ObjectReference routine = new ObjectReference("app", "calculate", DbObjType.FUNCTION);
        ObjectLocation definition = location(tempDir.resolve("definition.sql"), routine,
                ObjectLocation.LocationType.DEFINITION, 1);
        ObjectLocation compatible = location(tempDir.resolve("reference.sql"),
                new ObjectReference("app", "calculate", DbObjType.PROCEDURE),
                ObjectLocation.LocationType.REFERENCE, 2);
        ObjectLocation local = location(tempDir.resolve("local.sql"), routine,
                ObjectLocation.LocationType.LOCAL_REF, 3);

        assertTrue(definition.compare(compatible));
        assertEquals(ReferenceMatchKey.from(definition),
                ReferenceMatchKey.from(compatible));
        assertFalse(definition.compare(local));
        assertNotEquals(ReferenceMatchKey.from(definition),
                ReferenceMatchKey.from(local));
    }

    @Test
    void leasesKeepRetiredIndexAvailableUntilLastConcurrentUserCloses(
            @TempDir Path tempDir) throws Exception {
        Path projectRoot = Files.createDirectories(tempDir.resolve("project"));
        Path libraryRoot = Files.createDirectories(tempDir.resolve("libraries"));
        Path definitionFile =
                createSql(projectRoot, "SCHEMA/app/TABLE/item.sql");
        IndexPathRef definitionPath = path(projectRoot, definitionFile);
        ObjectLocation definition = location(definitionFile,
                new ObjectReference("app", "item", DbObjType.TABLE),
                ObjectLocation.LocationType.DEFINITION, 4);
        ProjectIndexData data = data(List.of(contribution(
                definitionPath, new MetaStatement(definition), definition)));
        var store = new ProjectIndexStore(tempDir.resolve("store"));
        assertEquals(ProjectIndexStore.PublishResult.PUBLISHED,
                store.publish(data, () -> false));
        var opened =
                store.open(ProjectIndexIdentity.from(data.manifest()));
        var view = opened.view().orElseThrow();
        var packed =
                new PackedProjectReferenceIndex(view, projectRoot, libraryRoot);
        var subject = new ProjectIndexDefinitionSubject(
                ReferenceMatchKey.MatchFamily.RELATION,
                null, "app", "item");

        IncrementalProjectReferenceIndex.AnalysisLease first =
                packed.acquireAnalysisLease();
        IncrementalProjectReferenceIndex.AnalysisLease second =
                packed.acquireAnalysisLease();
        assertNotSame(first, second);
        var revision = packed.revision();
        assertEquals(revision.identity(), first.identity());
        assertEquals(1L, revision.generation());
        assertEquals(1L, first.generation());
        assertFalse(revision.publicationId().isBlank());
        assertTrue(first.file(definitionPath).isPresent());
        packed.close();

        assertThrows(IllegalStateException.class,
                packed::acquireAnalysisLease);
        assertThrows(IllegalStateException.class,
                () -> packed.definitionsForPath(definitionFile.toString()));
        assertFalse(view.fileStamps().isEmpty());
        assertEquals(List.of("item"),
                first.definitions(subject, null).stream()
                        .map(MetaStatement::getName).toList());
        assertEquals(revision.identity(), second.identity());

        try (var executor = Executors.newFixedThreadPool(4)) {
            var futures = IntStream.range(0, 32)
                    .mapToObj(ignored -> executor.submit(() ->
                            second.definitions(subject, null).size()))
                    .toList();
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            for (var future : futures) {
                assertEquals(1, future.get());
            }
        }

        first.close();
        assertFalse(view.fileStamps().isEmpty());
        second.close();
        assertThrows(IllegalStateException.class, view::fileStamps);
        assertThrows(IllegalStateException.class,
                () -> second.definitions(subject, null));
        assertThrows(IllegalStateException.class, second::identity);
        store.close();
    }

    /**
     * A text shorter than a trigram is refused outright.
     * <p>
     * What is asserted is not that it answers nothing but that it reads
     * nothing: this used to walk every file of the index, and the empty text a
     * trailing dot leaves behind took that walk on every {@code alias.} typed.
     * The decoded contribution count is the walk made visible - a file walked
     * is a file decoded.
     */
    @Test
    void aTextShorterThanATrigramDecodesNothing(@TempDir Path tempDir)
            throws Exception {
        Path projectRoot = Files.createDirectories(tempDir.resolve("project"));
        Path libraryRoot = Files.createDirectories(tempDir.resolve("libraries"));
        Path itemFile = createSql(projectRoot, "SCHEMA/app/TABLE/item.sql");
        Path idFile = createSql(projectRoot, "SCHEMA/app/TABLE/id.sql");
        IndexPathRef itemPath = path(projectRoot, itemFile);
        IndexPathRef idPath = path(projectRoot, idFile);
        ObjectLocation item = location(itemFile,
                new ObjectReference("app", "item", DbObjType.TABLE),
                ObjectLocation.LocationType.DEFINITION, 4);
        ObjectLocation id = location(idFile,
                new ObjectReference("app", "id", DbObjType.TABLE),
                ObjectLocation.LocationType.DEFINITION, 4);
        ProjectIndexData data = data(List.of(
                contribution(itemPath, new MetaStatement(item), item),
                contribution(idPath, new MetaStatement(id), id)));
        var store = new ProjectIndexStore(tempDir.resolve("store"));
        assertEquals(ProjectIndexStore.PublishResult.PUBLISHED,
                store.publish(data, () -> false));
        var view = store.open(ProjectIndexIdentity.from(data.manifest()))
                .view().orElseThrow();
        var packed = new PackedProjectReferenceIndex(view, projectRoot, libraryRoot);

        assertEquals(List.of(), packed.completionCandidates(""));
        assertEquals(List.of(), packed.completionCandidates("I"));
        assertEquals(List.of(), packed.completionCandidates("IT"),
                "two letters name 'item' as surely as three, and are refused all the same");
        assertEquals(List.of(), packed.completionCandidates("ID"),
                "a name that is itself shorter than a trigram is refused too");
        assertEquals(0, view.decodedContributionCount(),
                "a refused text must not decode a single file");

        assertEquals(List.of("item"), packed.completionCandidates("ITE").stream()
                .map(MetaStatement::getName).toList());
        assertEquals(0, view.decodedContributionCount());

        packed.close();
        store.close();
    }

    private static FileContribution contribution(IndexPathRef path,
            MetaStatement definition, ObjectLocation location) {
        return new FileContribution(path,
                List.of(PackedDefinition.from(definition, path)),
                List.of(PackedLocation.from(location, path)), Set.of(), false);
    }

    private static ProjectIndexData data(List<FileContribution> files)
            throws Exception {
        List<ProjectFileStamp> stamps = files.stream()
                .map(file -> new ProjectFileStamp(file.path(), 1L, 10L, 2L,
                        digest(file.path().relativePath())))
                .toList();
        ProjectIndexManifest manifest = new ProjectIndexManifest(2, 0, 2,
                "15.0.0-neo1", "15.0.0-neo1", DatabaseType.PG,
                HexFormat.of().formatHex(digest("project")),
                digest("configuration"), 1L, stamps);
        return new ProjectIndexData(manifest, files);
    }

    private static ObjectLocation location(Path file, ObjectReference reference,
            ObjectLocation.LocationType type, int offset) {
        return new ObjectLocation.Builder()
                .setFilePath(file.toAbsolutePath().normalize().toString())
                .setOffset(offset)
                .setLineNumber(1)
                .setCharPositionInLine(offset)
                .setLength(4)
                .setReference(reference)
                .setLocationType(type)
                .build();
    }

    private static Path createSql(Path root, String relative) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "select 1;", StandardCharsets.UTF_8);
        return file;
    }

    private static IndexPathRef path(Path root, Path file) {
        return new IndexPathRef(IndexPathOrigin.PROJECT,
                root.relativize(file).toString());
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
