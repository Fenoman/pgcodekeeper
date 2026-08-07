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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

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
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexDelta;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexDefinitionSubject;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexIdentity;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexManifest;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexStore;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetry.PersistenceReason;
import ru.taximaxim.codekeeper.ui.projectindex.ReferenceMatchKey.MatchFamily;

class MemoryProjectReferenceIndexTest {

    /**
     * The lease an analysis holds must let it read the index and nothing else.
     * The whitelist below is what makes widening it a deliberate act;
     * {@code definitionBlockTraversals} and {@code stringProbes} are on it
     * because they hand back counts rather than the view that produced them,
     * so they grant no capability the blacklist below is meant to keep out.
     */
    @Test
    void analysisLeaseContractContainsNoPersistenceOperations() {
        Set<String> methods = Arrays.stream(
                IncrementalProjectReferenceIndex.AnalysisLease.class
                        .getMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());

        assertEquals(Set.of("identity", "generation", "fileStamps",
                "file", "definitions", "definitionBlockTraversals",
                "stringProbes",
                "anyFileMayHoldUnresolvedReferences", "close"), methods);
        assertFalse(methods.stream().anyMatch(name -> name.contains("revision")
                || name.contains("view") || name.contains("store")
                || name.contains("append") || name.contains("capacity")
                || name.contains("persistence") || name.contains("cache")));
    }

    @Test
    void fileMetadataAndIdentityAreDefensivelyImmutable(
            @TempDir Path tempDir) throws Exception {
        Fixture fixture = fixture(tempDir);
        var definitions = new ArrayList<>(
                fixture.data().files().getFirst().definitions());
        var metadata = new IncrementalFileMetadata(
                fixture.data().manifest().files().getFirst(), definitions);
        definitions.clear();

        assertEquals(1, metadata.definitions().size());
        try (var lease = fixture.index().acquireAnalysisLease()) {
            byte[] first = lease.identity().configSha256();
            first[0] ^= 1;
            assertArrayEquals(fixture.identity().configSha256(),
                    lease.identity().configSha256());
            assertEquals(7L, lease.generation());
        } finally {
            fixture.index().close();
        }
    }

    @Test
    void definitionSubjectIndexHonorsEveryPrefixAndExactExcludedPath(
            @TempDir Path tempDir) throws Exception {
        Fixture fixture = fixture(tempDir);
        var relationFamily = new ProjectIndexDefinitionSubject(
                MatchFamily.RELATION, null, null, null);
        var relationSchema = new ProjectIndexDefinitionSubject(
                MatchFamily.RELATION, null, "app", null);
        var relationObject = new ProjectIndexDefinitionSubject(
                MatchFamily.RELATION, null, "app", "shared");
        var exactIndex = new ProjectIndexDefinitionSubject(
                MatchFamily.EXACT, DbObjType.INDEX, "app", "shared_idx");
        var wrongExactType = new ProjectIndexDefinitionSubject(
                MatchFamily.EXACT, DbObjType.CONSTRAINT,
                "app", "shared_idx");
        var routine = new ProjectIndexDefinitionSubject(
                MatchFamily.ROUTINE, null, "app", "calculate");
        var type = new ProjectIndexDefinitionSubject(
                MatchFamily.TYPE, null, "app", "payload");

        try (var lease = fixture.index().acquireAnalysisLease()) {
            assertEquals(Set.of("changed.sql", "other.sql"),
                    fileNames(lease.definitions(relationFamily, null)));
            assertEquals(Set.of("changed.sql", "other.sql"),
                    fileNames(lease.definitions(relationSchema, null)));
            assertEquals(Set.of("changed.sql", "other.sql"),
                    fileNames(lease.definitions(relationObject, null)));
            assertEquals(Set.of("other.sql"),
                    fileNames(lease.definitions(
                            relationObject, fixture.changedPath())));
            assertEquals(Set.of("shared_idx.sql"),
                    fileNames(lease.definitions(exactIndex, null)));
            assertTrue(lease.definitions(wrongExactType, null).isEmpty());
            assertEquals(Set.of("function.sql"),
                    fileNames(lease.definitions(routine, null)));
            assertEquals(Set.of("type.sql"),
                    fileNames(lease.definitions(type, null)));
        } finally {
            fixture.index().close();
        }
    }

    @Test
    void sourceMutationCannotChangeFrozenMemorySnapshot(
            @TempDir Path tempDir) throws Exception {
        Fixture fixture = fixture(tempDir);
        try {
            String other = fixture.otherFile().toString();
            fixture.sourceDefinitions().get(other).clear();
            fixture.sourceDefinitions().clear();
            fixture.sourceReferences().get(other).clear();
            fixture.sourceReferences().clear();

            assertFalse(fixture.index().definitionsForPath(other).isEmpty());
            assertFalse(fixture.index().referencesForPath(other).isEmpty());
            assertThrows(IllegalStateException.class,
                    () -> fixture.sourceStorage().remove(other));
            assertThrows(IllegalStateException.class,
                    () -> fixture.sourceStorage().putReferences(
                            Map.of(), Map.of()));
            assertThrows(UnsupportedOperationException.class,
                    () -> fixture.sourceStorage().getObjDefinitions()
                            .put(other, List.of()));
            assertThrows(UnsupportedOperationException.class,
                    () -> fixture.sourceStorage().getObjReferences()
                            .put(other, Set.of()));
        } finally {
            fixture.index().close();
        }
    }

    @Test
    void memoryIndexRejectsMissingPersistenceFailureReason(
            @TempDir Path tempDir) throws Exception {
        Fixture fixture = fixture(tempDir);
        fixture.index().close();
        ProjectReferencesStorage storage =
                storage(fixture.data().files(),
                        fixture.changedFile().getParent(),
                        tempDir.resolve("libraries")).storage();
        assertThrows(IllegalArgumentException.class,
                () -> MemoryProjectReferenceIndex.fromPrepared(
                        storage, fixture.data(),
                        fixture.changedFile().getParent(),
                        tempDir.resolve("libraries"),
                        PersistenceReason.NONE));
    }

    /**
     * The in-memory index holds its definitions in a map and could answer a
     * text of any length. It refuses a short one all the same, because the
     * packed index cannot answer it, and what the editor offers must not
     * depend on whether the project happens to have an index.
     */
    @Test
    void memoryRefusesTheShortTextThePackedIndexCannotAnswer(
            @TempDir Path tempDir) throws Exception {
        Fixture fixture = fixture(tempDir);
        try {
            String file = fixture.changedFile().toString();
            List<String> named = fixture.index().definitionsForPath(file).stream()
                    .map(MetaStatement::getName).toList();
            assertFalse(named.isEmpty(), "the fixture must hold a definition to search for");
            String name = named.get(0);
            assertTrue(name.length() > 3, "the name must outlast the trigram");

            assertEquals(List.of(), completionNames(fixture, ""));
            assertEquals(List.of(), completionNames(fixture, name.substring(0, 1)));
            assertEquals(List.of(), completionNames(fixture, name.substring(0, 2)));
            assertTrue(completionNames(fixture, name.substring(0, 3)).contains(name),
                    "a whole trigram must reach the name it spells");
        } finally {
            fixture.index().close();
        }
    }

    private static List<String> completionNames(Fixture fixture, String text) {
        return fixture.index().completionCandidates(text).stream()
                .map(MetaStatement::getName).toList();
    }

    @Test
    void randomizedMemorySubjectsMatchPackedOrderAndExclusion(
            @TempDir Path tempDir) throws Exception {
        Fixture fixture = fixture(tempDir, true);
        var subjects = List.of(
                new ProjectIndexDefinitionSubject(
                        MatchFamily.RELATION, null, null, null),
                new ProjectIndexDefinitionSubject(
                        MatchFamily.RELATION, null, "app", "shared"),
                new ProjectIndexDefinitionSubject(
                        MatchFamily.ROUTINE, null, "app", "calculate"),
                new ProjectIndexDefinitionSubject(
                        MatchFamily.TYPE, null, "app", "payload"),
                new ProjectIndexDefinitionSubject(
                        MatchFamily.EXACT, DbObjType.INDEX,
                        "app", "shared_idx"));
        try (var store = new ProjectIndexStore(
                tempDir.resolve("packed-store"))) {
            assertEquals(ProjectIndexStore.PublishResult.PUBLISHED,
                    store.publish(fixture.data(), () -> false));
            var opened = store.open(fixture.identity());
            var packed = new PackedProjectReferenceIndex(
                    opened.view().orElseThrow(),
                    fixture.changedFile().getParent(),
                    tempDir.resolve("libraries"));
            try (var memoryLease =
                    fixture.index().acquireAnalysisLease();
                    var packedLease =
                            packed.acquireAnalysisLease()) {
                for (ProjectIndexDefinitionSubject subject : subjects) {
                    for (IndexPathRef excluded :
                            Arrays.asList(null,
                                    fixture.changedPath())) {
                        assertEquals(
                                definitionDescriptors(
                                        packedLease.definitions(
                                                subject, excluded)),
                                definitionDescriptors(
                                        memoryLease.definitions(
                                                subject, excluded)));
                    }
                }
            } finally {
                packed.close();
                opened.close();
            }
        } finally {
            fixture.index().close();
        }
    }

    @Test
    void replacementSharesUnrelatedDataAndOldLeaseKeepsOldSnapshot(
            @TempDir Path tempDir) throws Exception {
        Fixture fixture = fixture(tempDir);
        MemoryProjectReferenceIndex original = fixture.index();
        var oldLease = original.acquireAnalysisLease();
        List<MetaStatement> unrelatedDefinitions =
                original.definitionsForPath(fixture.otherFile().toString());
        Set<ObjectLocation> unrelatedLocations =
                original.referencesForPath(fixture.otherFile().toString());
        List<PackedDefinition> unrelatedSubject =
                subjectBucket(original, MatchFamily.ROUTINE,
                        "app", "calculate");
        ProjectFileStamp oldStamp =
                oldLease.file(fixture.changedPath()).orElseThrow().stamp();

        ObjectLocation replacementDefinition = location(
                fixture.changedFile(), DbObjType.TABLE, "app", "shared",
                ObjectLocation.LocationType.DEFINITION, 20);
        ObjectLocation replacementReference = location(
                fixture.changedFile(), DbObjType.TABLE, "app", "dependency",
                ObjectLocation.LocationType.REFERENCE, 40);
        MetaStatement replacementStatement =
                new MetaStatement(replacementDefinition);
        ProjectFileStamp replacementStamp = stamp(
                fixture.changedPath(), 2L, "changed-v2");
        FileContribution replacement = new FileContribution(
                fixture.changedPath(),
                List.of(PackedDefinition.from(
                        replacementStatement, fixture.changedPath())),
                List.of(PackedLocation.from(
                        replacementDefinition, fixture.changedPath()),
                        PackedLocation.from(
                                replacementReference,
                                fixture.changedPath())),
                Set.of(), true);

        MemoryProjectReferenceIndex updated = original.withReplacement(
                oldLease, replacementStamp, replacement);
        var updatedLease = updated.acquireAnalysisLease();
        MemoryProjectReferenceIndex next = null;
        MemoryProjectReferenceIndex retiredCandidate = null;
        IncrementalProjectReferenceIndex.AnalysisLease nextLease = null;
        try {
            assertSame(unrelatedDefinitions,
                    updated.definitionsForPath(fixture.otherFile().toString()));
            assertSame(unrelatedLocations,
                    updated.referencesForPath(fixture.otherFile().toString()));
            assertSame(unrelatedSubject,
                    subjectBucket(updated, MatchFamily.ROUTINE,
                            "app", "calculate"));
            assertEquals(20, updated.definitionsForPath(
                    fixture.changedFile().toString()).getFirst()
                            .getObject().getOffset());
            assertEquals(replacementStamp,
                    updatedLease.file(
                            fixture.changedPath()).orElseThrow().stamp());
            assertEquals(7L, oldLease.generation());
            assertEquals(8L, updatedLease.generation());
            assertEquals(oldStamp,
                    oldLease.file(
                            fixture.changedPath()).orElseThrow().stamp());
            var subject = new ProjectIndexDefinitionSubject(
                    MatchFamily.RELATION, null, "app", "shared");
            assertEquals(1, oldLease.definitions(
                    subject, fixture.otherPath()).getFirst()
                            .getObject().getOffset());
            assertEquals(20, updatedLease.definitions(
                    subject, fixture.otherPath()).getFirst()
                            .getObject().getOffset());
            assertEquals(PersistenceReason.IO,
                    updated.persistenceReason());

            assertThrows(IllegalArgumentException.class,
                    () -> original.withReplacement(
                            updatedLease, replacementStamp, replacement));
            ProjectFileStamp nextStamp = stamp(
                    fixture.changedPath(), 3L, "changed-v3");
            assertThrows(IllegalArgumentException.class,
                    () -> updated.withReplacement(
                            oldLease, nextStamp, replacement));
            next = updated.withReplacement(
                    updatedLease, nextStamp, replacement);
            nextLease = next.acquireAnalysisLease();
            assertEquals(9L, nextLease.generation());
            assertEquals(nextStamp,
                    nextLease.file(
                            fixture.changedPath()).orElseThrow().stamp());
            assertThrows(IllegalArgumentException.class,
                    () -> original.withReplacement(
                            oldLease,
                            stamp(fixture.otherPath(), 2L, "wrong-path"),
                            replacement));

            original.close();
            assertThrows(IllegalStateException.class,
                    original::acquireAnalysisLease);
            assertEquals(oldStamp,
                    oldLease.file(
                            fixture.changedPath()).orElseThrow().stamp());
            retiredCandidate = original.withReplacement(
                    oldLease, replacementStamp, replacement);
            try (var retiredLease =
                    retiredCandidate.acquireAnalysisLease()) {
                assertEquals(8L, retiredLease.generation());
                assertEquals(replacementStamp,
                        retiredLease.file(fixture.changedPath())
                                .orElseThrow().stamp());
            }

            updated.close();
            assertThrows(IllegalStateException.class,
                    updated::acquireAnalysisLease);
            assertEquals(replacementStamp,
                    updatedLease.file(
                            fixture.changedPath()).orElseThrow().stamp());
        } finally {
            oldLease.close();
            updatedLease.close();
            if (nextLease != null) {
                nextLease.close();
            }
            if (next != null) {
                next.close();
            }
            if (retiredCandidate != null) {
                retiredCandidate.close();
            }
            assertThrows(IllegalStateException.class, oldLease::identity);
            assertThrows(IllegalStateException.class,
                    () -> updatedLease.file(fixture.changedPath()));
            assertThrows(IllegalStateException.class,
                    () -> original.withReplacement(
                            oldLease, replacementStamp, replacement));
        }
    }

    @Test
    void replacementBatchCreatesOneImmutableGeneration(
            @TempDir Path tempDir) throws Exception {
        Fixture fixture = fixture(tempDir);
        MemoryProjectReferenceIndex original = fixture.index();
        var oldLease = original.acquireAnalysisLease();
        ProjectFileStamp changedStamp = stamp(
                fixture.changedPath(), 2L, "changed-v2");
        ProjectFileStamp otherStamp = stamp(
                fixture.otherPath(), 2L, "other-v2");
        FileContribution changed = contribution(
                fixture.changedPath(),
                location(fixture.changedFile(), DbObjType.TABLE,
                        "app", "shared",
                        ObjectLocation.LocationType.DEFINITION, 20));
        FileContribution other = contribution(
                fixture.otherPath(),
                location(fixture.otherFile(), DbObjType.VIEW,
                        "app", "shared",
                        ObjectLocation.LocationType.DEFINITION, 30));
        var delta = new ProjectIndexDelta(List.of(
                ProjectIndexDelta.Change.replace(
                        changedStamp, changed),
                ProjectIndexDelta.Change.replace(otherStamp, other)));

        MemoryProjectReferenceIndex updated =
                original.withReplacements(oldLease, delta);
        try (var updatedLease = updated.acquireAnalysisLease()) {
            assertEquals(8L, updatedLease.generation());
            assertEquals(20, updated.definitionsForPath(
                    fixture.changedFile().toString()).getFirst()
                            .getObject().getOffset());
            assertEquals(30, updated.definitionsForPath(
                    fixture.otherFile().toString()).getFirst()
                            .getObject().getOffset());
            assertEquals(7L, oldLease.generation());
            assertEquals(1, original.definitionsForPath(
                    fixture.changedFile().toString()).getFirst()
                            .getObject().getOffset());
            assertEquals(2, original.definitionsForPath(
                    fixture.otherFile().toString()).getFirst()
                            .getObject().getOffset());

            // A path the index does not hold is an introduction, not an error:
            // the batch that carries it was already cleared against the index
            // it came from. The snapshot the lease still sees keeps its own
            // stamps, so the introduction cannot leak backwards.
            IndexPathRef missing =
                    new IndexPathRef(IndexPathOrigin.PROJECT,
                            "missing.sql");
            ProjectFileStamp missingStamp = stamp(missing, 2L, "missing");
            FileContribution missingContribution = contribution(
                    missing,
                    location(fixture.changedFile(), DbObjType.TABLE,
                            "app", "missing",
                            ObjectLocation.LocationType.DEFINITION, 40));
            var withAddition = new ProjectIndexDelta(List.of(
                    delta.changes().getFirst(),
                    ProjectIndexDelta.Change.replace(
                            missingStamp, missingContribution)));
            MemoryProjectReferenceIndex introduced =
                    original.withReplacements(oldLease, withAddition);
            try (var introducedLease =
                    introduced.acquireAnalysisLease()) {
                assertEquals(missingStamp,
                        introducedLease.file(missing).orElseThrow().stamp());
                assertTrue(introducedLease.fileStamps()
                        .contains(missingStamp));
                assertEquals(oldLease.fileStamps().size() + 1,
                        introducedLease.fileStamps().size());
                // Read off the contributions this snapshot owns rather than
                // fixed: every contribution of this fixture claims it may hold
                // an unresolved reference, and the snapshot has to say so.
                assertEquals(missingContribution.unresolvedAny(),
                        introducedLease
                                .anyFileMayHoldUnresolvedReferences());
            } finally {
                introduced.close();
            }
            assertTrue(oldLease.file(missing).isEmpty());
            assertEquals(7L, oldLease.generation());
        } finally {
            oldLease.close();
            updated.close();
            original.close();
        }
    }

    @Test
    void memoryStateRetainsNoPackedDataOrSecondLocationGraph() {
        assertNoPackedPayloadFields(MemoryProjectReferenceIndex.class);
        int storageOwners = 0;
        for (Class<?> nested : MemoryProjectReferenceIndex.class
                .getDeclaredClasses()) {
            assertNoPackedPayloadFields(nested);
            storageOwners += Arrays.stream(nested.getDeclaredFields())
                    .filter(field -> field.getType()
                            == ProjectReferencesStorage.class)
                    .count();
        }
        assertEquals(1, storageOwners);
    }

    private static void assertNoPackedPayloadFields(Class<?> type) {
        for (Field field : type.getDeclaredFields()) {
            assertFalse(field.getType() == ProjectIndexData.class
                    || field.getType() == FileContribution.class
                    || field.getType() == PackedLocation.class
                    || field.getType() == ObjectLocation.class,
                    () -> type.getSimpleName() + '.' + field.getName());
            String generic = field.getGenericType().getTypeName();
            assertFalse(generic.contains("ProjectIndexData")
                    || generic.contains("FileContribution")
                    || generic.contains("PackedLocation")
                    || generic.contains("ObjectLocation"),
                    () -> type.getSimpleName() + '.' + field.getName()
                            + " retains a second location graph");
        }
    }

    private static Set<String> fileNames(List<MetaStatement> definitions) {
        return definitions.stream()
                .map(definition -> Path.of(
                        definition.getObject().getFilePath())
                                .getFileName().toString())
                .collect(Collectors.toSet());
    }

    private static List<String> definitionDescriptors(
            List<MetaStatement> definitions) {
        return definitions.stream()
                .map(definition -> definition.getClass().getName()
                        + '|' + definition.getName()
                        + '|' + definition.getObject().getFilePath()
                        + '|' + definition.getObject().getOffset())
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static List<PackedDefinition> subjectBucket(
            MemoryProjectReferenceIndex index, MatchFamily family,
            String schema, String objectName) throws Exception {
        Field stateField =
                MemoryProjectReferenceIndex.class.getDeclaredField("state");
        stateField.setAccessible(true);
        Object state = stateField.get(index);
        Field subjectsField =
                state.getClass().getDeclaredField("subjects");
        subjectsField.setAccessible(true);
        Map<?, List<PackedDefinition>> subjects =
                (Map<?, List<PackedDefinition>>) subjectsField.get(state);
        for (var entry : subjects.entrySet()) {
            Object key = entry.getKey();
            Field familyField = key.getClass().getDeclaredField("family");
            Field schemaField = key.getClass().getDeclaredField("schema");
            Field objectField =
                    key.getClass().getDeclaredField("objectName");
            familyField.setAccessible(true);
            schemaField.setAccessible(true);
            objectField.setAccessible(true);
            if (family == familyField.get(key)
                    && java.util.Objects.equals(schema,
                            schemaField.get(key))
                    && java.util.Objects.equals(objectName,
                            objectField.get(key))) {
                return entry.getValue();
            }
        }
        throw new AssertionError("Subject bucket not found");
    }

    private static Fixture fixture(Path tempDir) throws Exception {
        return fixture(tempDir, false);
    }

    private static Fixture fixture(Path tempDir, boolean shuffle)
            throws Exception {
        Path projectRoot =
                Files.createDirectories(tempDir.resolve("project"));
        Path libraryRoot =
                Files.createDirectories(tempDir.resolve("libraries"));
        Path changedFile = createSql(projectRoot, "changed.sql");
        Path otherFile = createSql(projectRoot, "other.sql");
        Path functionFile = createSql(projectRoot, "function.sql");
        Path typeFile = createSql(projectRoot, "type.sql");
        Path indexFile = createSql(projectRoot, "shared_idx.sql");
        IndexPathRef changedPath = path(projectRoot, changedFile);
        IndexPathRef otherPath = path(projectRoot, otherFile);
        IndexPathRef functionPath = path(projectRoot, functionFile);
        IndexPathRef typePath = path(projectRoot, typeFile);
        IndexPathRef indexPath = path(projectRoot, indexFile);

        FileContribution changed = contribution(changedPath,
                location(changedFile, DbObjType.TABLE, "app", "shared",
                        ObjectLocation.LocationType.DEFINITION, 1));
        FileContribution other = contribution(otherPath,
                location(otherFile, DbObjType.VIEW, "app", "shared",
                        ObjectLocation.LocationType.DEFINITION, 2));
        FileContribution function = contribution(functionPath,
                location(functionFile, DbObjType.FUNCTION, "app", "calculate",
                        ObjectLocation.LocationType.DEFINITION, 3));
        FileContribution type = contribution(typePath,
                location(typeFile, DbObjType.TYPE, "app", "payload",
                        ObjectLocation.LocationType.DEFINITION, 5));
        FileContribution index = contribution(indexPath,
                location(indexFile, DbObjType.INDEX, "app", "shared_idx",
                        ObjectLocation.LocationType.DEFINITION, 4));
        List<FileContribution> files = new ArrayList<>(
                List.of(changed, other, function, type, index));
        List<ProjectFileStamp> stamps = new ArrayList<>(List.of(
                stamp(changedPath, 1L, "changed"),
                stamp(otherPath, 1L, "other"),
                stamp(functionPath, 1L, "function"),
                stamp(typePath, 1L, "type"),
                stamp(indexPath, 1L, "index")));
        if (shuffle) {
            Collections.shuffle(files, new Random(17));
            Collections.shuffle(stamps, new Random(29));
        }
        ProjectIndexIdentity identity = new ProjectIndexIdentity(
                2, "15.0.0-neo1", "15.0.0-neo1", DatabaseType.PG,
                HexFormat.of().formatHex(digest("project")),
                digest("configuration"));
        ProjectIndexManifest manifest = new ProjectIndexManifest(
                2, 0, identity.parserAbi(), identity.coreVersion(),
                identity.uiVersion(), identity.databaseType(),
                identity.projectIdentity(), identity.configSha256(),
                7L, stamps);
        ProjectIndexData data = new ProjectIndexData(manifest, files);
        StorageSource source = storage(
                files, projectRoot, libraryRoot);
        MemoryProjectReferenceIndex indexStorage =
                MemoryProjectReferenceIndex.fromPrepared(
                        source.storage(), data, projectRoot, libraryRoot,
                        PersistenceReason.IO);
        return new Fixture(indexStorage, data, identity,
                changedPath, otherPath, changedFile, otherFile,
                source.storage(), source.definitions(),
                source.references());
    }

    private static StorageSource storage(
            List<FileContribution> files, Path projectRoot, Path libraryRoot) {
        var definitions =
                new java.util.LinkedHashMap<String, List<MetaStatement>>();
        var locations =
                new java.util.LinkedHashMap<String, Set<ObjectLocation>>();
        for (FileContribution file : files) {
            String absolute = (file.path().origin() == IndexPathOrigin.PROJECT
                    ? projectRoot : libraryRoot)
                            .resolve(file.path().relativePath())
                            .toAbsolutePath().normalize().toString();
            definitions.put(absolute, new ArrayList<>(
                    file.definitions().stream()
                            .map(definition -> definition.toMetaStatement(
                                    path -> (path.origin()
                                            == IndexPathOrigin.PROJECT
                                                    ? projectRoot
                                                    : libraryRoot)
                                                            .resolve(path
                                                                    .relativePath())
                                                            .toString()))
                            .toList()));
            locations.put(absolute, new LinkedHashSet<>(
                    file.locations().stream()
                            .map(location -> location.toObjectLocation(
                                    path -> (path.origin()
                                            == IndexPathOrigin.PROJECT
                                                    ? projectRoot
                                                    : libraryRoot)
                                                            .resolve(path
                                                                    .relativePath())
                                                            .toString()))
                            .toList()));
        }
        var storage = new ProjectReferencesStorage();
        storage.putReferences(definitions, locations);
        return new StorageSource(storage, definitions, locations);
    }

    private static FileContribution contribution(
            IndexPathRef path, ObjectLocation definition) {
        MetaStatement statement = new MetaStatement(definition);
        return new FileContribution(path,
                List.of(PackedDefinition.from(statement, path)),
                List.of(PackedLocation.from(definition, path)),
                Set.of(), true);
    }

    private static ObjectLocation location(Path file, DbObjType type,
            String schema, String name,
            ObjectLocation.LocationType locationType, int offset) {
        return new ObjectLocation.Builder()
                .setFilePath(file.toAbsolutePath().normalize().toString())
                .setOffset(offset)
                .setLineNumber(1)
                .setCharPositionInLine(offset)
                .setLength(4)
                .setReference(new ObjectReference(schema, name, type))
                .setLocationType(locationType)
                .build();
    }

    private static Path createSql(Path root, String relative)
            throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "select 1;", StandardCharsets.UTF_8);
        return file;
    }

    private static IndexPathRef path(Path root, Path file) {
        return new IndexPathRef(IndexPathOrigin.PROJECT,
                root.relativize(file).toString());
    }

    private static ProjectFileStamp stamp(IndexPathRef path,
            long modificationStamp, String content) {
        return new ProjectFileStamp(path, modificationStamp,
                content.length(), modificationStamp, digest(content));
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private record Fixture(MemoryProjectReferenceIndex index,
            ProjectIndexData data, ProjectIndexIdentity identity,
            IndexPathRef changedPath, IndexPathRef otherPath,
            Path changedFile, Path otherFile,
            ProjectReferencesStorage sourceStorage,
            Map<String, List<MetaStatement>> sourceDefinitions,
            Map<String, Set<ObjectLocation>> sourceReferences) {
    }

    private record StorageSource(ProjectReferencesStorage storage,
            Map<String, List<MetaStatement>> definitions,
            Map<String, Set<ObjectLocation>> references) {
    }
}
