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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.pgcodekeeper.core.DangerStatement;
import org.pgcodekeeper.core.database.api.schema.ArgMode;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.ICast.CastContext;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.base.schema.Argument;
import org.pgcodekeeper.core.database.base.schema.meta.MetaCast;
import org.pgcodekeeper.core.database.base.schema.meta.MetaCompositeType;
import org.pgcodekeeper.core.database.base.schema.meta.MetaConstraint;
import org.pgcodekeeper.core.database.base.schema.meta.MetaFunction;
import org.pgcodekeeper.core.database.base.schema.meta.MetaOperator;
import org.pgcodekeeper.core.database.base.schema.meta.MetaRelation;
import org.pgcodekeeper.core.database.base.schema.meta.MetaStatement;
import org.pgcodekeeper.core.utils.Pair;

import ru.taximaxim.codekeeper.ui.DatabaseType;

final class ProjectIndexFixtures {

    static final String ABSOLUTE_ROOT = "/Users/fenoman/work/OmniX_DB";
    static final String ROUTINE_BODY = "SELECT secret_body_payload";

    private ProjectIndexFixtures() {
    }

    static ProjectIndexData snapshotWithAllMetaKinds() throws Exception {
        List<IndexPathRef> paths = List.of(
                path("schema/simple.sql"),
                path("schema/function.sql"),
                path("schema/relation.sql"),
                path("schema/composite.sql"),
                path("schema/constraint.sql"),
                path("schema/operator.sql"),
                path("schema/cast.sql"));

        List<MetaStatement> statements = new ArrayList<>();

        MetaStatement simple = new MetaStatement(location(paths.get(0), 10,
                new ObjectReference("app", DbObjType.SCHEMA),
                ObjectLocation.LocationType.DEFINITION, null, ROUTINE_BODY));
        simple.setComment("schema comment");
        statements.add(simple);

        MetaFunction function = new MetaFunction(location(paths.get(1), 20,
                new ObjectReference("app", "calculate(integer)", DbObjType.FUNCTION),
                ObjectLocation.LocationType.DEFINITION, "fn_alias", ROUTINE_BODY), "calculate");
        var input = new Argument(ArgMode.IN, "input_id", "integer");
        input.setDefaultExpression("42");
        input.setReadOnly(true);
        function.addArgument(input);
        function.addArgument(new Argument(ArgMode.OUT, "result", "text"));
        function.addOrderBy(new Argument(ArgMode.IN, "sort_key", "integer"));
        function.setReturns("record");
        function.setSetof(true);
        function.addReturnsColumn("id", "integer");
        function.addReturnsColumn("label", "text");
        function.setComment("function comment");
        statements.add(function);

        MetaRelation relation = new MetaRelation(location(paths.get(2), 30,
                new ObjectReference("app", "item", DbObjType.TABLE),
                ObjectLocation.LocationType.DEFINITION, null, "CREATE TABLE body"));
        relation.addColumns(List.of(new Pair<>("id", "bigint"), new Pair<>("payload", "jsonb")));
        relation.setComment("relation comment");
        statements.add(relation);

        MetaCompositeType composite = new MetaCompositeType(location(paths.get(3), 40,
                new ObjectReference("app", "item_pair", DbObjType.TYPE),
                ObjectLocation.LocationType.DEFINITION, null, "CREATE TYPE body"));
        composite.addAttr("left_id", "bigint");
        composite.addAttr("right_id", "bigint");
        composite.setComment("composite comment");
        statements.add(composite);

        MetaConstraint constraint = new MetaConstraint(location(paths.get(4), 50,
                new ObjectReference("app", "item", "item_pk", DbObjType.CONSTRAINT),
                ObjectLocation.LocationType.DEFINITION, null, "PRIMARY KEY body"));
        constraint.setPrimaryKey(true);
        constraint.addColumn("tenant_id");
        constraint.addColumn("id");
        constraint.setComment("constraint comment");
        statements.add(constraint);

        MetaOperator operator = new MetaOperator(location(paths.get(5), 60,
                new ObjectReference("app", "<=>", DbObjType.OPERATOR),
                ObjectLocation.LocationType.DEFINITION, null, "OPERATOR body"));
        operator.setLeftArg("app.item");
        operator.setRightArg("app.item");
        operator.setReturns("boolean");
        operator.setComment("operator comment");
        statements.add(operator);

        MetaCast cast = new MetaCast("app.item", "jsonb", CastContext.ASSIGNMENT,
                location(paths.get(6), 70,
                        new ObjectReference("app.item AS jsonb", DbObjType.CAST),
                        ObjectLocation.LocationType.DEFINITION, null, "CAST body"));
        cast.setComment("cast comment");
        statements.add(cast);

        List<ProjectFileStamp> stamps = new ArrayList<>();
        List<FileContribution> files = new ArrayList<>();
        for (int i = 0; i < paths.size(); i++) {
            IndexPathRef path = paths.get(i);
            ProjectFileStamp stamp = new ProjectFileStamp(path, 1000L + i, 2000L + i,
                    3000L + i, sha256("file-" + i));
            stamps.add(stamp);

            List<PackedLocation> locations = new ArrayList<>();
            locations.add(PackedLocation.from(statements.get(i).getObject(), path));
            if (i == 1) {
                locations.add(PackedLocation.from(location(path, 100,
                        new ObjectReference("app", "item", DbObjType.TABLE),
                        ObjectLocation.LocationType.REFERENCE, "table_alias", ROUTINE_BODY), path));
                locations.add(PackedLocation.from(location(path, 110,
                        new ObjectReference(null, null, "local_value", DbObjType.COLUMN),
                        ObjectLocation.LocationType.VARIABLE, "variable_alias", ROUTINE_BODY), path));
                locations.add(PackedLocation.from(location(path, 120,
                        new ObjectReference(null, null, "local_value", DbObjType.COLUMN),
                        ObjectLocation.LocationType.LOCAL_REF, null, ROUTINE_BODY), path));
            }

            Set<ReferenceMatchKey> unresolved = i == 1
                    ? Set.of(ReferenceMatchKey.from(locations.get(1))) : Set.of();
            files.add(new FileContribution(path,
                    List.of(PackedDefinition.from(statements.get(i), path)), locations,
                    unresolved, i == 2));
        }

        ProjectIndexManifest manifest = new ProjectIndexManifest(2, 0, 2,
                "15.0.0-neo1", "15.0.0-neo1", DatabaseType.PG, identity("snapshot"),
                sha256("configuration"), 123456789L, stamps);
        return new ProjectIndexData(manifest, files);
    }

    static ProjectIndexData repeatedLocations(int count) throws Exception {
        IndexPathRef path = path("schema/repeated.sql");
        ObjectReference reference = new ObjectReference("app", "item", DbObjType.TABLE);
        List<PackedLocation> locations = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            locations.add(PackedLocation.from(location(path, i * 4, reference,
                    ObjectLocation.LocationType.REFERENCE, null, null), path));
        }
        ProjectFileStamp stamp = new ProjectFileStamp(path, 1L, count * 4L, 2L, sha256("repeated"));
        ProjectIndexManifest manifest = new ProjectIndexManifest(2, 0, 2,
                "15.0.0-neo1", "15.0.0-neo1", DatabaseType.PG, identity("size-fixture"),
                sha256("configuration"), 7L, List.of(stamp));
        return new ProjectIndexData(manifest,
                List.of(new FileContribution(path, List.of(), locations, Set.of(), false)));
    }

    static ProjectIndexData twoLargePaths() throws Exception {
        IndexPathRef first = path("schema/" + "a".repeat(40_000) + ".sql");
        IndexPathRef second = path("schema/" + "b".repeat(40_000) + ".sql");
        List<ProjectFileStamp> stamps = List.of(
                new ProjectFileStamp(first, 1L, 1L, 1L, sha256("first")),
                new ProjectFileStamp(second, 2L, 2L, 2L, sha256("second")));
        ProjectIndexManifest manifest = new ProjectIndexManifest(2, 0, 2,
                "15.0.0-neo1", "15.0.0-neo1", DatabaseType.PG, identity("large-path-fixture"),
                sha256("configuration"), 8L, stamps);
        return new ProjectIndexData(manifest, List.of(
                new FileContribution(first, List.of(), List.of(), Set.of(), false),
                new FileContribution(second, List.of(), List.of(), Set.of(), false)));
    }

    static ProjectIndexData minimalSnapshot() throws Exception {
        IndexPathRef path = path("x.sql");
        ProjectFileStamp stamp = new ProjectFileStamp(path, 1L, 2L, 3L, sha256("minimal"));
        ProjectIndexManifest manifest = new ProjectIndexManifest(2, 0, 2,
                "15.0.0-neo1", "15.0.0-neo1", DatabaseType.PG, identity("minimal-fixture"),
                sha256("configuration"), 10L, List.of(stamp));
        return new ProjectIndexData(manifest, List.of(
                new FileContribution(path, List.of(), List.of(), Set.of(), false)));
    }

    static ProjectIndexData operatorWithDerivedReturnSnapshot() throws Exception {
        IndexPathRef path = path("schema/operator.sql");
        ObjectLocation location = location(path, 10,
                new ObjectReference("app", "<=>", DbObjType.OPERATOR),
                ObjectLocation.LocationType.DEFINITION, null,
                "CREATE OPERATOR app.<=> (PROCEDURE = app.compare_items,"
                        + " LEFTARG = app.item, RIGHTARG = app.item)");
        MetaOperator operator = new MetaOperator(location);
        operator.setLeftArg("app.item");
        operator.setRightArg("app.item");

        ProjectFileStamp stamp = new ProjectFileStamp(path, 1L, 2L, 3L,
                sha256("operator-with-derived-return"));
        ProjectIndexManifest manifest = new ProjectIndexManifest(2, 0, 2,
                "15.0.0-neo1", "15.0.0-neo1", DatabaseType.PG,
                identity("operator-with-derived-return"),
                sha256("configuration"), 11L, List.of(stamp));
        return new ProjectIndexData(manifest, List.of(new FileContribution(path,
                List.of(PackedDefinition.from(operator, path)),
                List.of(PackedLocation.from(location, path)), Set.of(), false)));
    }

    static ProjectIndexData comparatorCollisions(boolean reversed) throws Exception {
        IndexPathRef path = path("schema/collisions.sql");
        ObjectLocation functionLocation = new ObjectLocation.Builder()
                .setFilePath(ABSOLUTE_ROOT + '/' + path.relativePath())
                .setOffset(10)
                .setLineNumber(1)
                .setCharPositionInLine(2)
                .setLength(30)
                .setReference(new ObjectReference("app", "same(integer)", DbObjType.FUNCTION))
                .setAction("definition")
                .setAlias("same")
                .setLocationType(ObjectLocation.LocationType.DEFINITION)
                .build();
        MetaFunction function = new MetaFunction(functionLocation, "same");
        function.addArgument(new Argument(ArgMode.IN, "value", "integer"));
        function.setReturns("text");
        PackedDefinition firstDefinition = PackedDefinition.from(function, path);
        PackedDefinition secondDefinition = firstDefinition.withReturns("jsonb");

        ObjectReference table = new ObjectReference("app", "same_table", DbObjType.TABLE);
        PackedLocation firstLocation = PackedLocation.from(new ObjectLocation.Builder()
                .setFilePath(ABSOLUTE_ROOT + '/' + path.relativePath())
                .setOffset(100)
                .setLineNumber(10)
                .setCharPositionInLine(1)
                .setLength(20)
                .setReference(table)
                .setAction("first")
                .setAlias("same")
                .setLocationType(ObjectLocation.LocationType.REFERENCE)
                .build(), path);
        PackedLocation secondLocation = PackedLocation.from(new ObjectLocation.Builder()
                .setFilePath(ABSOLUTE_ROOT + '/' + path.relativePath())
                .setOffset(100)
                .setLineNumber(11)
                .setCharPositionInLine(2)
                .setLength(21)
                .setReference(table)
                .setAction("second")
                .setAlias("same")
                .setLocationType(ObjectLocation.LocationType.REFERENCE)
                .build(), path);

        List<PackedDefinition> definitions = reversed
                ? List.of(secondDefinition, firstDefinition)
                : List.of(firstDefinition, secondDefinition);
        List<PackedLocation> locations = reversed
                ? List.of(secondLocation, firstLocation)
                : List.of(firstLocation, secondLocation);
        ProjectFileStamp stamp = new ProjectFileStamp(path, 1L, 1L, 1L, sha256("collisions"));
        ProjectIndexManifest manifest = new ProjectIndexManifest(2, 0, 2,
                "15.0.0-neo1", "15.0.0-neo1", DatabaseType.PG, identity("collision-fixture"),
                sha256("configuration"), 9L, List.of(stamp));
        return new ProjectIndexData(manifest, List.of(
                new FileContribution(path, definitions, locations, Set.of(), false)));
    }

    static ProjectIndexData reordered(ProjectIndexData data) {
        List<ProjectFileStamp> stamps = new ArrayList<>(data.manifest().files());
        java.util.Collections.reverse(stamps);
        ProjectIndexManifest old = data.manifest();
        ProjectIndexManifest manifest = new ProjectIndexManifest(old.formatMajor(), old.formatMinor(),
                old.parserAbi(), old.coreVersion(), old.uiVersion(), old.databaseType(),
                old.projectIdentity(), old.configSha256(), old.generation(), stamps);
        List<FileContribution> files = new ArrayList<>();
        for (FileContribution file : data.files().reversed()) {
            List<PackedDefinition> definitions = new ArrayList<>(file.definitions());
            List<PackedLocation> locations = new ArrayList<>(file.locations());
            java.util.Collections.reverse(definitions);
            java.util.Collections.reverse(locations);
            files.add(new FileContribution(file.path(), definitions, locations,
                    new LinkedHashSet<>(file.unresolvedCandidates().stream().toList()),
                    file.unresolvedAny()));
        }
        return new ProjectIndexData(manifest, files);
    }

    static ProjectIndexData withConstraintColumnCount(int count) throws Exception {
        ProjectIndexData source = snapshotWithAllMetaKinds();
        List<FileContribution> files = new ArrayList<>(source.files());
        FileContribution originalFile = files.get(4);
        PackedDefinition original = originalFile.definitions().getFirst();
        PackedDefinition replacement = new PackedDefinition(original.kind(), original.object(),
                original.bareName(), original.comment(), original.arguments(), original.orderBy(),
                original.returnColumns(), original.returns(), original.setof(),
                original.relationColumns(), original.relationColumnsKnown(),
                original.compositeAttributes(), original.primaryKey(),
                java.util.Collections.nCopies(count, "id"), original.operatorLeft(),
                original.operatorRight(), original.operatorReturns(), original.castSource(),
                original.castTarget(), original.castContext());
        files.set(4, new FileContribution(originalFile.path(), List.of(replacement),
                originalFile.locations(), originalFile.unresolvedCandidates(),
                originalFile.unresolvedAny()));
        return new ProjectIndexData(source.manifest(), files);
    }

    static ProjectIndexData withDefinitionComment(String comment) throws Exception {
        ProjectIndexData source = snapshotWithAllMetaKinds();
        List<FileContribution> files = new ArrayList<>(source.files());
        FileContribution originalFile = files.getFirst();
        PackedDefinition original = originalFile.definitions().getFirst();
        PackedDefinition replacement = new PackedDefinition(original.kind(), original.object(),
                original.bareName(), comment, original.arguments(), original.orderBy(),
                original.returnColumns(), original.returns(), original.setof(),
                original.relationColumns(), original.relationColumnsKnown(),
                original.compositeAttributes(), original.primaryKey(),
                original.constraintColumns(), original.operatorLeft(), original.operatorRight(),
                original.operatorReturns(), original.castSource(), original.castTarget(),
                original.castContext());
        files.set(0, new FileContribution(originalFile.path(), List.of(replacement),
                originalFile.locations(), originalFile.unresolvedCandidates(),
                originalFile.unresolvedAny()));
        return new ProjectIndexData(source.manifest(), files);
    }

    static ProjectIndexData withDefinitionBareName(String bareName) throws Exception {
        return withFunctionBareNames(List.of(bareName));
    }

    static ProjectIndexData completionHeavy(int count) throws Exception {
        List<String> names = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            names.add("routine_" + String.format("%05d", i));
        }
        return withFunctionBareNames(names);
    }

    static ProjectIndexData largeIndexedProject(int count) throws Exception {
        if (count <= 0 || count > 36 * 36 * 36) {
            throw new IllegalArgumentException("Fixture count is outside its key space");
        }
        ProjectIndexData template = snapshotWithAllMetaKinds();
        PackedDefinition original = template.files().get(1).definitions().getFirst();
        List<ProjectFileStamp> stamps = new ArrayList<>(count);
        List<FileContribution> files = new ArrayList<>(count);
        byte[] contentSha = sha256("large-index-fixture");
        for (int i = 0; i < count; i++) {
            String key = fixedBase36(i);
            IndexPathRef path = path("schema/generated/" + key + ".sql");
            PackedLocation object = new PackedLocation(path.origin(), path.relativePath(),
                    i, i, 0, 1,
                    new ObjectReference("app", "routine_" + key + "()", DbObjType.FUNCTION),
                    null, null, ObjectLocation.LocationType.DEFINITION, null);
            PackedDefinition definition = new PackedDefinition(original.kind(), object,
                    key, original.comment(), original.arguments(), original.orderBy(),
                    original.returnColumns(), original.returns(), original.setof(),
                    original.relationColumns(), original.relationColumnsKnown(),
                    original.compositeAttributes(), original.primaryKey(),
                    original.constraintColumns(), original.operatorLeft(),
                    original.operatorRight(), original.operatorReturns(),
                    original.castSource(), original.castTarget(), original.castContext());
            PackedLocation reference = new PackedLocation(path.origin(), path.relativePath(),
                    i + 1, i, 1, 1,
                    new ObjectReference("app", "item_" + key, DbObjType.TABLE),
                    "read", null, ObjectLocation.LocationType.REFERENCE, null);
            ProjectFileStamp stamp = new ProjectFileStamp(path, i, 1, i, contentSha);
            stamps.add(stamp);
            files.add(new FileContribution(path, List.of(definition),
                    List.of(reference), Set.of(), false));
        }
        ProjectIndexManifest old = template.manifest();
        ProjectIndexManifest manifest = new ProjectIndexManifest(
                old.formatMajor(), old.formatMinor(), old.parserAbi(),
                old.coreVersion(), old.uiVersion(), old.databaseType(),
                old.projectIdentity(), old.configSha256(), old.generation(), stamps);
        return new ProjectIndexData(manifest, files);
    }

    static ProjectIndexData withFunctionBareNames(List<String> bareNames) throws Exception {
        ProjectIndexData source = snapshotWithAllMetaKinds();
        List<FileContribution> files = new ArrayList<>(source.files());
        FileContribution originalFile = files.get(1);
        PackedDefinition original = originalFile.definitions().getFirst();
        List<PackedDefinition> replacements = bareNames.stream()
                .map(bareName -> new PackedDefinition(original.kind(), original.object(),
                        bareName, original.comment(), original.arguments(), original.orderBy(),
                        original.returnColumns(), original.returns(), original.setof(),
                        original.relationColumns(), original.relationColumnsKnown(),
                        original.compositeAttributes(), original.primaryKey(),
                        original.constraintColumns(), original.operatorLeft(),
                        original.operatorRight(), original.operatorReturns(),
                        original.castSource(), original.castTarget(), original.castContext()))
                .toList();
        files.set(1, new FileContribution(originalFile.path(), replacements,
                originalFile.locations(), originalFile.unresolvedCandidates(),
                originalFile.unresolvedAny()));
        return new ProjectIndexData(source.manifest(), files);
    }

    static List<ObjectLocation> legacyLocations(ProjectIndexData data) {
        return data.files().stream().flatMap(file -> file.locations().stream())
                .map(location -> location.toObjectLocation(path -> ABSOLUTE_ROOT + '/' + path.relativePath()))
                .toList();
    }

    private static String fixedBase36(int value) {
        String encoded = Integer.toString(value, 36).toUpperCase(java.util.Locale.ROOT);
        return "0".repeat(3 - encoded.length()) + encoded;
    }

    static byte[] canonicalBytes(ProjectIndexData data) {
        StringBuilder sb = new StringBuilder();
        ProjectIndexManifest manifest = data.manifest();
        sb.append(manifest.formatMajor()).append('|').append(manifest.formatMinor()).append('|')
                .append(manifest.parserAbi()).append('|').append(manifest.coreVersion()).append('|')
                .append(manifest.uiVersion()).append('|').append(manifest.databaseType()).append('|')
                .append(manifest.projectIdentity()).append('|')
                .append(hex(manifest.configSha256())).append('|').append(manifest.generation()).append('\n');
        manifest.files().stream().sorted(java.util.Comparator.comparing(stamp -> stamp.path().toString()))
                .forEach(stamp -> sb.append(stamp.path()).append('|')
                .append(stamp.eclipseModificationStamp()).append('|').append(stamp.size()).append('|')
                .append(stamp.lastModifiedMillis()).append('|').append(hex(stamp.contentSha256())).append('\n'));
        data.files().stream().sorted(java.util.Comparator.comparing(file -> file.path().toString()))
                .forEach(file -> {
            sb.append("FILE|").append(file.path()).append('|').append(file.unresolvedAny()).append('|')
                    .append(file.unresolvedCandidates().stream().sorted(ReferenceMatchKey.CANONICAL_ORDER).toList())
                    .append('\n');
            file.definitions().stream().sorted(java.util.Comparator.comparing(PackedDefinition::canonicalForm))
                    .forEach(definition -> sb.append("DEF|")
                    .append(definition.canonicalForm()).append('|')
                    .append(hex(DefinitionShapeHasher.hash(definition))).append('\n'));
            file.locations().stream().sorted(java.util.Comparator.comparing(PackedLocation::canonicalForm))
                    .forEach(location -> sb.append("LOC|").append(location.canonicalForm()).append('\n'));
        });
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    static IndexPathRef path(String relative) {
        return new IndexPathRef(IndexPathOrigin.PROJECT, relative);
    }

    private static ObjectLocation location(IndexPathRef path, int offset, ObjectReference reference,
            ObjectLocation.LocationType type, String alias, String sql) {
        ObjectLocation location = new ObjectLocation.Builder()
                .setFilePath(ABSOLUTE_ROOT + '/' + path.relativePath())
                .setOffset(offset)
                .setLineNumber(offset / 10 + 1)
                .setCharPositionInLine(offset % 7)
                .setLength(37 + offset % 11)
                .setReference(reference)
                .setAction("ACTION_" + type)
                .setAlias(alias)
                .setSql(sql)
                .setLocationType(type)
                .build();
        if (offset % 20 == 0) {
            location.setWarning(DangerStatement.ALTER_COLUMN);
        }
        return location;
    }

    static byte[] sha256(String value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String identity(String value) throws Exception {
        return hex(sha256(value));
    }

    private static String hex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }
}
