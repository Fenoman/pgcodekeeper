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

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.base.schema.meta.MetaOperator;
import org.pgcodekeeper.core.database.base.schema.meta.MetaStatement;

import ru.taximaxim.codekeeper.ui.DatabaseType;

class ProjectIndexSnapshotFactoryTest {

    @Test
    void packsEveryEnumeratedFileAndPreservesEmptyContributions(
            @TempDir Path root) throws Exception {
        IndexPathRef first =
                new IndexPathRef(IndexPathOrigin.PROJECT, "SCHEMA/app/table.sql");
        IndexPathRef empty =
                new IndexPathRef(IndexPathOrigin.PROJECT, "SCHEMA/app/empty.sql");
        Path firstPath = root.resolve(first.relativePath());
        ObjectLocation definitionLocation = location(firstPath, 1,
                ObjectLocation.LocationType.DEFINITION);
        ObjectLocation referenceLocation = location(firstPath, 20,
                ObjectLocation.LocationType.REFERENCE);
        List<ProjectFileStamp> stamps = List.of(stamp(first), stamp(empty));

        ProjectIndexData result = ProjectIndexSnapshotFactory.create(identity(),
                7, stamps,
                Map.of(firstPath.toString(),
                        List.of(new MetaStatement(definitionLocation))),
                Map.of(firstPath.toString(), Set.of(referenceLocation)),
                path -> root.resolve(path.relativePath()).toString(),
                Set.of());

        Assertions.assertEquals(2, result.files().size());
        Assertions.assertEquals(stamps, result.manifest().files());
        FileContribution populated = result.files().getFirst();
        Assertions.assertEquals(first, populated.path());
        Assertions.assertEquals(1, populated.definitions().size());
        Assertions.assertEquals(1, populated.locations().size());
        Assertions.assertFalse(populated.unresolvedAny());
        FileContribution emptyFile = result.files().get(1);
        Assertions.assertEquals(empty, emptyFile.path());
        Assertions.assertTrue(emptyFile.definitions().isEmpty());
        Assertions.assertTrue(emptyFile.locations().isEmpty());
    }

    @Test
    void rejectsAnalysisDataOutsideEnumeratedInputs(@TempDir Path root) {
        IndexPathRef expected =
                new IndexPathRef(IndexPathOrigin.PROJECT, "SCHEMA/app/table.sql");
        Path unexpected = root.resolve("SCHEMA/app/unexpected.sql");

        IllegalArgumentException failure = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> ProjectIndexSnapshotFactory.create(identity(), 1,
                        List.of(stamp(expected)),
                        Map.of(unexpected.toString(),
                                List.of(new MetaStatement(location(unexpected, 1,
                                        ObjectLocation.LocationType.DEFINITION)))),
                        Map.of(),
                        path -> root.resolve(path.relativePath()).toString(),
                        Set.of()));

        Assertions.assertTrue(failure.getMessage().contains("outside"));
    }

    @Test
    void identityFieldsAreCopiedIntoManifest(@TempDir Path root) {
        IndexPathRef path =
                new IndexPathRef(IndexPathOrigin.PROJECT, "SCHEMA/app/table.sql");
        ProjectIndexIdentity identity = identity();

        ProjectIndexManifest manifest = ProjectIndexSnapshotFactory.create(
                identity, 99, List.of(stamp(path)), Map.of(), Map.of(),
                value -> root.resolve(value.relativePath()).toString(),
                Set.of()).manifest();

        Assertions.assertTrue(identity.matches(manifest));
        Assertions.assertEquals(99, manifest.generation());
    }

    @Test
    void packsOperatorWhoseReturnTypeIsDerived(@TempDir Path root) {
        IndexPathRef path =
                new IndexPathRef(IndexPathOrigin.PROJECT, "SCHEMA/app/operator.sql");
        Path absolutePath = root.resolve(path.relativePath());
        ObjectLocation location = new ObjectLocation.Builder()
                .setFilePath(absolutePath.toString())
                .setOffset(1)
                .setLineNumber(1)
                .setCharPositionInLine(1)
                .setLength(10)
                .setReference(new ObjectReference("app", "<=>",
                        DbObjType.OPERATOR))
                .setLocationType(ObjectLocation.LocationType.DEFINITION)
                .build();
        MetaOperator operator = new MetaOperator(location);
        operator.setLeftArg("app.item");
        operator.setRightArg("app.item");

        ProjectIndexData result = ProjectIndexSnapshotFactory.create(identity(),
                1, List.of(stamp(path)),
                Map.of(absolutePath.toString(), List.of(operator)),
                Map.of(), value -> root.resolve(value.relativePath()).toString(),
                Set.of());

        Assertions.assertNull(result.files().getFirst().definitions().getFirst()
                .operatorReturns());
    }

    @Test
    void canonicalizesDefinitionsAndLocationsInsideEachFile(
            @TempDir Path root) {
        IndexPathRef path =
                new IndexPathRef(IndexPathOrigin.PROJECT,
                        "SCHEMA/app/table.sql");
        Path absolutePath = root.resolve(path.relativePath());
        ObjectLocation laterDefinition = location(absolutePath, 20,
                ObjectLocation.LocationType.DEFINITION);
        ObjectLocation earlierDefinition = location(absolutePath, 1,
                ObjectLocation.LocationType.DEFINITION);
        ObjectLocation laterReference = location(absolutePath, 40,
                ObjectLocation.LocationType.REFERENCE);
        ObjectLocation earlierReference = location(absolutePath, 10,
                ObjectLocation.LocationType.REFERENCE);

        ProjectIndexData result = ProjectIndexSnapshotFactory.create(
                identity(), 1, List.of(stamp(path)),
                Map.of(absolutePath.toString(), List.of(
                        new MetaStatement(laterDefinition),
                        new MetaStatement(earlierDefinition))),
                Map.of(absolutePath.toString(), new LinkedHashSet<>(
                        List.of(laterReference, earlierReference))),
                value -> root.resolve(value.relativePath()).toString(),
                Set.of());

        FileContribution contribution = result.files().getFirst();
        Assertions.assertEquals(List.of(1, 20),
                contribution.definitions().stream()
                        .map(definition -> definition.object().offset())
                        .toList());
        Assertions.assertEquals(List.of(10, 40),
                contribution.locations().stream()
                        .map(PackedLocation::offset)
                        .toList());
    }

    private static ProjectIndexIdentity identity() {
        return new ProjectIndexIdentity(2, "15.0.0-neo1", "15.0.0-neo1",
                DatabaseType.PG, "a".repeat(64), sha256("configuration"));
    }

    private static ProjectFileStamp stamp(IndexPathRef path) {
        return new ProjectFileStamp(path, 1, 2, 3, sha256(path.toString()));
    }

    private static ObjectLocation location(Path path, int offset,
            ObjectLocation.LocationType type) {
        return new ObjectLocation.Builder()
                .setFilePath(path.toString())
                .setOffset(offset)
                .setLineNumber(1)
                .setCharPositionInLine(1)
                .setLength(10)
                .setReference(new ObjectReference("app", "item",
                        DbObjType.TABLE))
                .setLocationType(type)
                .build();
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new AssertionError(ex);
        }
    }
}
