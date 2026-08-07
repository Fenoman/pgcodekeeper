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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgcodekeeper.core.database.api.schema.ArgMode;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.ICast.CastContext;
import org.pgcodekeeper.core.database.api.schema.IConstraintPk;
import org.pgcodekeeper.core.database.api.schema.IFunction;
import org.pgcodekeeper.core.database.api.schema.IOperator;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.base.schema.Argument;
import org.pgcodekeeper.core.database.base.schema.meta.MetaCast;
import org.pgcodekeeper.core.database.base.schema.meta.MetaCompositeType;
import org.pgcodekeeper.core.database.base.schema.meta.MetaConstraint;
import org.pgcodekeeper.core.database.base.schema.meta.MetaContainer;
import org.pgcodekeeper.core.database.base.schema.meta.MetaFunction;
import org.pgcodekeeper.core.database.base.schema.meta.MetaOperator;
import org.pgcodekeeper.core.database.base.schema.meta.MetaRelation;
import org.pgcodekeeper.core.database.base.schema.meta.MetaStatement;

import ru.taximaxim.codekeeper.ui.DatabaseType;
import ru.taximaxim.codekeeper.ui.projectindex.FileContribution;
import ru.taximaxim.codekeeper.ui.projectindex.IndexPathOrigin;
import ru.taximaxim.codekeeper.ui.projectindex.IndexPathRef;
import ru.taximaxim.codekeeper.ui.projectindex.PackedDefinition;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectFileStamp;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexData;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexIdentity;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexManifest;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexStore;

class IncrementalMetaContainerTest {

    @Test
    void borrowedContainersDoNotCloseSharedBaselineLease(
            @TempDir Path tempDir) throws Exception {
        Path projectRoot = Files.createDirectories(
                tempDir.resolve("project"));
        Path libraryRoot = Files.createDirectories(
                tempDir.resolve("libraries"));
        MetaRelation relation =
                new MetaRelation("app", "shared", DbObjType.TABLE);
        ProjectIndexData data = data(List.of(
                file(path("schema/base.sql"), relation)));
        var store = new ProjectIndexStore(tempDir.resolve("store"));
        assertEquals(ProjectIndexStore.PublishResult.PUBLISHED,
                store.publish(data, () -> false));
        var opened = store.open(
                ProjectIndexIdentity.from(data.manifest()));
        var packed = new PackedProjectReferenceIndex(
                opened.view().orElseThrow(), projectRoot, libraryRoot);

        try (var lease = packed.acquireAnalysisLease()) {
            var first = IncrementalMetaContainer.borrowed(
                    MetaContainer::new, lease, null);
            var second = IncrementalMetaContainer.borrowed(
                    MetaContainer::new, lease, null);
            first.close();

            assertEquals(ProjectIndexIdentity.from(data.manifest()),
                    lease.identity());
            assertEquals("shared", second.findRelation(
                    "app", "shared").getName());

            second.close();
            assertEquals(ProjectIndexIdentity.from(data.manifest()),
                    lease.identity());
        } finally {
            packed.close();
            opened.close();
            store.close();
        }
    }

    @Test
    void mergesLocalPackedAndSystemMetadataWithoutDecodingFiles(
            @TempDir Path tempDir) throws Exception {
        Path projectRoot = Files.createDirectories(tempDir.resolve("project"));
        Path libraryRoot = Files.createDirectories(tempDir.resolve("libraries"));
        IndexPathRef changedPath =
                path("schema/changed.sql");

        MetaRelation packedRelation =
                new MetaRelation("app", "packed_relation", DbObjType.TABLE);
        MetaRelation staleRelation =
                new MetaRelation("app", "shared_relation", DbObjType.TABLE);
        MetaFunction packedFunction =
                function("app", "calculate", "integer", "packed");
        MetaFunction packedShared =
                function("app", "shared", null, "packed");
        MetaFunction obsolete =
                function("app", "obsolete", null, "packed");
        MetaCompositeType packedType = type("app", "item_pair");
        MetaOperator packedOperator =
                operator("app", "<=>", "app.item", "app.item", "boolean");
        MetaConstraint packedPk =
                primaryKey("app", "item", "item_pk", "id");
        MetaConstraint tenantPk =
                primaryKey("app", "item", "tenant_pk", "tenant_id");
        MetaCast packedCast =
                new MetaCast("text", "app.code", CastContext.IMPLICIT);

        ProjectIndexData data = data(List.of(
                file(path("schema/base.sql"), packedRelation,
                        staleRelation, packedFunction, packedShared,
                        packedType, packedOperator, packedPk,
                        tenantPk, packedCast),
                file(changedPath, obsolete)));
        var store = new ProjectIndexStore(tempDir.resolve("store"));
        assertEquals(ProjectIndexStore.PublishResult.PUBLISHED,
                store.publish(data, () -> false));
        var opened =
                store.open(ProjectIndexIdentity.from(data.manifest()));
        var view = opened.view().orElseThrow();
        var packed = new PackedProjectReferenceIndex(
                view, projectRoot, libraryRoot);

        MetaContainer local = new MetaContainer();
        MetaRelation localRelation =
                new MetaRelation("app", "shared_relation", DbObjType.VIEW);
        MetaFunction localShared =
                function("app", "shared", null, "local");
        MetaOperator localOperator =
                operator("app", "<=>", "app.item", "app.item", "local_result");
        MetaConstraint localPk =
                primaryKey("app", "item", "item_pk", "local_id");
        local.addStatement(localRelation);
        local.addStatement(localShared);
        local.addStatement(localOperator);
        local.addStatement(localPk);

        MetaRelation systemRelation =
                new MetaRelation("pg_catalog", "pg_type", DbObjType.TABLE);
        MetaFunction systemFunction =
                function("pg_catalog", "format_type", "oid", "text");
        MetaOperator systemOperator =
                operator("pg_catalog", "=", "oid", "oid", "boolean");
        local.addStatement(systemRelation);
        local.addStatement(systemFunction);
        local.addStatement(systemOperator);

        AtomicBoolean metadataReady = new AtomicBoolean();
        AtomicInteger metadataLoads = new AtomicInteger();
        var metadata = new IncrementalMetaContainer(
                () -> {
                    assertTrue(metadataReady.get());
                    metadataLoads.incrementAndGet();
                    return local;
                },
                packed.acquireAnalysisLease(), changedPath);
        assertEquals(0, metadataLoads.get());
        metadataReady.set(true);
        try {
            try (var executor = Executors.newFixedThreadPool(8)) {
                var futures = IntStream.range(0, 32)
                        .mapToObj(index -> executor.submit(() ->
                                metadata.availableFunctions("app").size()))
                        .toList();
                executor.shutdown();
                assertTrue(executor.awaitTermination(
                        10, TimeUnit.SECONDS));
                for (var future : futures) {
                    assertEquals(2, future.get());
                }
            }
            assertEquals(1, metadataLoads.get());

            assertSame(localRelation,
                    metadata.findRelation("app", "shared_relation"));
            assertEquals("packed_relation",
                    metadata.findRelation(
                            "app", "packed_relation").getName());
            assertSame(systemRelation,
                    metadata.findRelation("pg_catalog", "pg_type"));

            assertSame(localShared,
                    metadata.findFunction("app", "shared()"));
            assertEquals("packed",
                    metadata.findFunction(
                            "app", "calculate(integer)").getReturns());
            assertSame(systemFunction, metadata.findFunction(
                    "pg_catalog", "format_type(oid)"));
            assertNull(metadata.findFunction("app", "obsolete()"));
            assertEquals(Set.of("calculate(integer)", "shared()"),
                    metadata.availableFunctions("app").stream()
                            .map(IFunction::getName).collect(
                                    java.util.stream.Collectors.toSet()));

            assertEquals("text",
                    metadata.findType(
                            "app", "item_pair").getAttrType("value"));
            assertSame(localOperator,
                    metadata.findOperator(
                            "app", "<=>(app.item, app.item)"));
            assertSame(systemOperator, metadata.findOperator(
                    "pg_catalog", "=(oid, oid)"));
            assertEquals(Set.of("<=>(app.item, app.item)"),
                    metadata.availableOperators("app").stream()
                            .map(IOperator::getName).collect(
                                    java.util.stream.Collectors.toSet()));

            List<IConstraintPk> primaryKeys =
                    List.copyOf(metadata.getPrimaryKeys("app", "item"));
            assertEquals(Set.of("item_pk", "tenant_pk"),
                    primaryKeys.stream().map(IConstraintPk::getName)
                            .collect(java.util.stream.Collectors.toSet()));
            assertSame(localPk, primaryKeys.stream()
                    .filter(key -> key.getName().equals("item_pk"))
                    .findFirst().orElseThrow());
            assertTrue(metadata.containsCastImplicit("text", "app.code"));
            assertFalse(metadata.containsCastImplicit("integer", "app.code"));

            assertSame(localRelation,
                    metadata.getRelations().get("app")
                            .get("shared_relation"));
            assertEquals("packed_relation",
                    metadata.getRelations().get("app")
                            .get("packed_relation").getName());
            assertSame(systemRelation,
                    metadata.getRelations().get("pg_catalog")
                            .get("pg_type"));
            assertThrows(UnsupportedOperationException.class,
                    () -> metadata.getRelations().put("other", java.util.Map.of()));
            assertThrows(UnsupportedOperationException.class,
                    () -> metadata.getRelations().get("app")
                            .put("other", packedRelation));
            assertEquals(0, view.decodedContributionCount());
            assertEquals(1, metadataLoads.get());

            try (var executor = Executors.newFixedThreadPool(8)) {
                var futures = IntStream.range(0, 64)
                        .mapToObj(index -> executor.submit(() -> {
                            assertNotNull(metadata.findRelation(
                                    "app", "packed_relation"));
                            assertNotNull(metadata.findFunction(
                                    "app", "calculate(integer)"));
                            assertFalse(metadata.availableOperators(
                                    "app").isEmpty());
                            assertTrue(metadata.containsCastImplicit(
                                    "text", "app.code"));
                        })).toList();
                executor.shutdown();
                assertTrue(executor.awaitTermination(
                        10, TimeUnit.SECONDS));
                for (var future : futures) {
                    future.get();
                }
            }
            assertEquals(1, metadataLoads.get());
        } finally {
            metadata.close();
            packed.close();
            store.close();
        }
        assertThrows(IllegalStateException.class,
                () -> metadata.findRelation("app", "packed_relation"));
    }

    /**
     * Pins the arithmetic the lookup summary claims to report.
     *
     * <p>A named subject asks the index for one object; a schema-shaped one
     * makes it answer with a whole family of a whole schema. The summary
     * exists to let those be divided against each other, so every number it
     * prints is asserted against a hand-counted sequence of calls rather than
     * merely being checked for presence. The blocks walked are asserted
     * against the index's own counter, which is a source the container does
     * not keep.
     */
    @Test
    void lookupSummaryCountsSchemaWideLiftsApartFromNamedLookups(
            @TempDir Path tempDir) throws Exception {
        Path projectRoot = Files.createDirectories(
                tempDir.resolve("project"));
        Path libraryRoot = Files.createDirectories(
                tempDir.resolve("libraries"));

        ProjectIndexData data = data(List.of(
                file(path("schema/base.sql"),
                        new MetaRelation("app", "t1", DbObjType.TABLE),
                        new MetaRelation("app", "t2", DbObjType.TABLE),
                        function("app", "pick", "integer", "integer"),
                        function("app", "pick", "text", "text"),
                        function("app", "pick", "numeric", "numeric"),
                        function("app", "other", null, "void"))));
        var store = new ProjectIndexStore(tempDir.resolve("store"));
        assertEquals(ProjectIndexStore.PublishResult.PUBLISHED,
                store.publish(data, () -> false));
        var opened =
                store.open(ProjectIndexIdentity.from(data.manifest()));
        var view = opened.view().orElseThrow();
        var packed = new PackedProjectReferenceIndex(
                view, projectRoot, libraryRoot);

        long traversalsBefore = view.definitionBlockTraversals();
        var metadata = new IncrementalMetaContainer(MetaContainer::new,
                packed.acquireAnalysisLease(), null);
        String frozen;
        try {
            // One schema-wide routine lift, three named relations of which
            // one is absent, two named routines: six lookups, eight
            // statements, and nothing that asks the whole index yet.
            assertEquals(4, metadata.availableFunctions("app").size());
            assertNotNull(metadata.findRelation("app", "t1"));
            assertNotNull(metadata.findRelation("app", "t2"));
            assertNull(metadata.findRelation("app", "absent"));
            assertNotNull(metadata.findFunction("app", "pick(integer)"));
            assertNotNull(metadata.findFunction("app", "pick(text)"));

            String named = metadata.packedLookupSummary();
            assertAll(
                    () -> assertTrue(named.startsWith(
                            "index_lookups=6 index_statements=8 "), named),
                    () -> assertTrue(named.contains(
                            " index_named=5/4/"), named),
                    () -> assertTrue(named.contains(
                            " index_schema=1/4/"), named),
                    () -> assertTrue(named.contains(
                            " index_wholeindex=0/0/0 "), named),
                    () -> assertTrue(named.contains("RELATION 3/2/"), named),
                    () -> assertTrue(named.contains("ROUTINE 3/6/"), named));

            // The only subject that asks every schema at once is the one
            // getRelations() raises, so its counter is that call's witness.
            assertEquals(2, metadata.getRelations().get("app").size());

            frozen = metadata.packedLookupSummary();
            long traversals =
                    view.definitionBlockTraversals() - traversalsBefore;
            assertTrue(traversals > 0,
                    "a packed lookup has to walk a block");
            String summary = frozen;
            long stringProbes = triple(summary, " index_string_probes=")[0];
            long stringRecords = triple(summary, " index_string_probes=")[1];
            assertAll(
                    () -> assertTrue(summary.startsWith(
                            "index_lookups=7 index_statements=10 "), summary),
                    () -> assertTrue(summary.contains(
                            " index_block_traversals=" + traversals
                                    + " index_string_probes="), summary),
                    () -> assertTrue(summary.contains(
                            " index_named=5/4/"), summary),
                    // Every one of those lookups had to find its schema in the
                    // dictionary before it could read anything, and the first
                    // of them had to decode the block holding it.
                    () -> assertTrue(stringProbes > 0,
                            "a packed lookup has to search the dictionary: "
                                    + summary),
                    () -> assertTrue(stringRecords > 0,
                            "and the dictionary has to be decoded to be"
                                    + " searched: " + summary),
                    // Decoding is per block and happens once, so seven lookups
                    // ask far more often than they decode. The two counters
                    // are not a ratio.
                    () -> assertTrue(stringRecords < stringProbes,
                            "a decoded block answers many probes: " + summary),
                    () -> assertTrue(summary.contains(
                            " index_schema=1/4/"), summary),
                    () -> assertTrue(summary.contains(
                            " index_wholeindex=1/2/"), summary),
                    () -> assertTrue(summary.contains("RELATION 4/4/"), summary),
                    () -> assertTrue(summary.contains("ROUTINE 3/6/"), summary),
                    () -> assertFalse(summary.contains("EXACT."), summary),
                    () -> assertTrue(summary.endsWith(
                            " index_asked=[relation=3 routine=2"
                                    + " routine_name=1 routine_schema=1"
                                    + " operator=0 operator_schema=0 type=0"
                                    + " primary_key=0 cast=0]"), summary));
        } finally {
            metadata.close();
            packed.close();
            opened.close();
            store.close();
        }
        // Closing drops the memo maps and the lease the counts are read from,
        // so the summary has to have been frozen before either went away.
        assertEquals(frozen, metadata.packedLookupSummary());
    }

    /**
     * The reason {@code index_string_probes} is reported at all: a lookup that
     * returned nothing is not a lookup that cost nothing.
     *
     * <p>A subject is spelled in characters and the index is keyed by
     * dictionary identifiers, so a lookup searches the dictionary before it
     * reads anything - and when the search fails there is nothing left to
     * read. The existing counters describe such a lookup as
     * {@code 1 lookup / 0 statements}, and the blocks-walked counter does not
     * see it either, because no definition block was ever opened. Only the
     * probes show that it was paid for.
     *
     * <p>Asserted as a difference across one call rather than as a total, so
     * that whatever the lookups before it cost cannot stand in for it.
     */
    @Test
    void aLookupThatMatchedNothingStillPaidToSearchTheDictionary(
            @TempDir Path tempDir) throws Exception {
        Path projectRoot = Files.createDirectories(
                tempDir.resolve("project"));
        Path libraryRoot = Files.createDirectories(
                tempDir.resolve("libraries"));

        ProjectIndexData data = data(List.of(
                file(path("schema/base.sql"),
                        new MetaRelation("app", "t1", DbObjType.TABLE),
                        new MetaRelation("app", "t2", DbObjType.TABLE))));
        var store = new ProjectIndexStore(tempDir.resolve("store"));
        assertEquals(ProjectIndexStore.PublishResult.PUBLISHED,
                store.publish(data, () -> false));
        var opened =
                store.open(ProjectIndexIdentity.from(data.manifest()));
        var view = opened.view().orElseThrow();
        var packed = new PackedProjectReferenceIndex(
                view, projectRoot, libraryRoot);

        var metadata = new IncrementalMetaContainer(MetaContainer::new,
                packed.acquireAnalysisLease(), null);
        try {
            assertNotNull(metadata.findRelation("app", "t1"));
            String before = metadata.packedLookupSummary();
            long[] probesBefore = triple(before, " index_string_probes=");
            long traversalsBefore = view.definitionBlockTraversals();

            // A schema this index has never heard of. The lookup ends in the
            // dictionary, having neither found a subject nor opened a block.
            assertNull(metadata.findRelation("nowhere", "absent"));

            String after = metadata.packedLookupSummary();
            long[] probesAfter = triple(after, " index_string_probes=");
            assertAll(
                    () -> assertEquals(field(before, "index_lookups=") + 1,
                            field(after, "index_lookups="),
                            "the lookup has to have happened: " + after),
                    () -> assertEquals(field(before, " index_statements="),
                            field(after, " index_statements="),
                            "and to have returned nothing: " + after),
                    () -> assertEquals(traversalsBefore,
                            view.definitionBlockTraversals(),
                            "without walking a definition block: " + after),
                    () -> assertTrue(probesAfter[0] > probesBefore[0],
                            "yet it searched the dictionary: " + after),
                    // And searched it without decoding anything: the blocks
                    // this search walks are the ones the lookup before it
                    // already decoded, so the price of asking is now separate
                    // from the price of answering.
                    () -> assertEquals(probesBefore[1], probesAfter[1],
                            "over a dictionary already decoded: " + after));
        } finally {
            metadata.close();
            packed.close();
            opened.close();
            store.close();
        }
    }

    /**
     * Oracle for the incremental and full parity of ambiguous overload
     * resolution. Two equally ranked overloads of the same bare name live in
     * two different files, one of them in the changed file. The core analyzer
     * resolves such a call with {@code Collections.max}, which keeps the first
     * best-ranked candidate, so the incremental container has to offer the
     * candidates in the same order a full rebuild does. Signature order and
     * file order disagree here on purpose: {@code pick(bigint)} sorts before
     * {@code pick(numeric)} by name and after it by file.
     */
    @Test
    void tieRankedOverloadsSplitAcrossFilesKeepFullRebuildOrder(
            @TempDir Path tempDir) throws Exception {
        Path projectRoot = Files.createDirectories(
                tempDir.resolve("project"));
        Path libraryRoot = Files.createDirectories(
                tempDir.resolve("libraries"));
        IndexPathRef basePath = path("schema/a_base.sql");
        IndexPathRef changedPath = path("schema/b_changed.sql");

        MetaFunction packedOverload = functionAt("app", "pick(numeric)",
                "pick", "numeric", "numeric",
                projectRoot.resolve("schema/a_base.sql"), 10);
        MetaFunction changedOverload = functionAt("app", "pick(bigint)",
                "pick", "bigint", "bigint",
                projectRoot.resolve("schema/b_changed.sql"), 20);
        MetaFunction obsoleteOverload = functionAt("app", "pick(bigint)",
                "pick", "bigint", "obsolete",
                projectRoot.resolve("schema/b_changed.sql"), 20);

        ProjectIndexData data = data(List.of(
                file(basePath, packedOverload),
                file(changedPath, obsoleteOverload)));
        var store = new ProjectIndexStore(tempDir.resolve("store"));
        assertEquals(ProjectIndexStore.PublishResult.PUBLISHED,
                store.publish(data, () -> false));
        var opened =
                store.open(ProjectIndexIdentity.from(data.manifest()));
        var packed = new PackedProjectReferenceIndex(
                opened.view().orElseThrow(), projectRoot, libraryRoot);

        MetaContainer full = new MetaContainer();
        full.addStatement(packedOverload);
        full.addStatement(changedOverload);

        MetaContainer local = new MetaContainer();
        local.addStatement(changedOverload);

        var metadata = new IncrementalMetaContainer(() -> local,
                packed.acquireAnalysisLease(), changedPath);
        try {
            List<String> fullOrder = signatures(full.availableFunctions("app"));
            assertEquals(List.of("pick(numeric)", "pick(bigint)"), fullOrder,
                    "the full rebuild keeps both overloads in file order");
            assertEquals(fullOrder,
                    signatures(metadata.availableFunctions("app")));
            assertEquals(firstCandidate(full.availableFunctions("app"), "pick"),
                    firstCandidate(
                            metadata.availableFunctions("app"), "pick"));
            assertEquals("bigint", metadata.findFunction(
                    "app", "pick(bigint)").getReturns(),
                    "the changed file overrides its own packed definition");
        } finally {
            metadata.close();
            packed.close();
            opened.close();
            store.close();
        }
    }

    private static List<String> signatures(
            java.util.Collection<IFunction> functions) {
        return functions.stream().map(IFunction::getName).toList();
    }

    /** The whole number the summary prints under {@code key}. */
    private static long field(String summary, String key) {
        int at = summary.indexOf(key);
        assertTrue(at >= 0, key + " is missing from " + summary);
        int from = at + key.length();
        int to = from;
        while (to < summary.length()
                && Character.isDigit(summary.charAt(to))) {
            to++;
        }
        assertTrue(to > from, key + " has no number in " + summary);
        return Long.parseLong(summary.substring(from, to));
    }

    /** The {@code a/b/c} the summary prints under {@code key}. */
    private static long[] triple(String summary, String key) {
        int at = summary.indexOf(key);
        assertTrue(at >= 0, key + " is missing from " + summary);
        int from = at + key.length();
        int end = summary.indexOf(' ', from);
        String[] parts = summary.substring(from,
                end < 0 ? summary.length() : end).split("/");
        assertEquals(3, parts.length,
                key + " is not a triple in " + summary);
        return new long[] {Long.parseLong(parts[0]),
                Long.parseLong(parts[1]), Long.parseLong(parts[2])};
    }

    /**
     * Reproduces the tie break the core analyzer performs for equally ranked
     * overloads: {@code Collections.max} returns the first maximal element.
     */
    private static String firstCandidate(
            java.util.Collection<IFunction> functions, String bareName) {
        return functions.stream()
                .filter(function -> bareName.equals(function.getBareName()))
                .map(IFunction::getName)
                .findFirst().orElseThrow();
    }

    private static MetaFunction functionAt(String schema, String signature,
            String bareName, String argumentType, String returns,
            Path file, int offset) {
        MetaFunction function = new MetaFunction(
                new ObjectLocation.Builder()
                        .setReference(new ObjectReference(
                                schema, signature, DbObjType.FUNCTION))
                        .setFilePath(file.toString())
                        .setOffset(offset)
                        .setLocationType(
                                ObjectLocation.LocationType.DEFINITION)
                        .build(),
                bareName);
        function.addArgument(new Argument(ArgMode.IN, "value", argumentType));
        function.setReturns(returns);
        return function;
    }

    private static FileContribution file(
            IndexPathRef path, MetaStatement... statements) {
        return new FileContribution(path,
                java.util.Arrays.stream(statements)
                        .map(statement -> PackedDefinition.from(
                                statement, path))
                        .toList(),
                List.of(), Set.of(), false);
    }

    private static ProjectIndexData data(List<FileContribution> files) {
        List<ProjectFileStamp> stamps = files.stream()
                .map(file -> new ProjectFileStamp(
                        file.path(), 1L, 10L, 2L,
                        digest(file.path().relativePath())))
                .toList();
        ProjectIndexManifest manifest = new ProjectIndexManifest(
                2, 0, 2, "15.0.0-neo1", "15.0.0-neo1",
                DatabaseType.PG,
                HexFormat.of().formatHex(digest("project")),
                digest("configuration"), 1L, stamps);
        return new ProjectIndexData(manifest, files);
    }

    private static MetaFunction function(String schema, String name,
            String argumentType, String returns) {
        String signature = name + '('
                + (argumentType == null ? "" : argumentType) + ')';
        MetaFunction function =
                new MetaFunction(schema, signature, name);
        if (argumentType != null) {
            function.addArgument(new Argument(
                    ArgMode.IN, "value", argumentType));
        }
        function.setReturns(returns);
        return function;
    }

    private static MetaCompositeType type(String schema, String name) {
        MetaCompositeType type = new MetaCompositeType(
                location(new ObjectReference(
                        schema, name, DbObjType.TYPE)));
        type.addAttr("value", "text");
        return type;
    }

    private static MetaOperator operator(String schema, String name,
            String left, String right, String returns) {
        MetaOperator operator = new MetaOperator(schema, name);
        operator.setLeftArg(left);
        operator.setRightArg(right);
        operator.setReturns(returns);
        return operator;
    }

    private static MetaConstraint primaryKey(String schema, String table,
            String name, String column) {
        MetaConstraint constraint = new MetaConstraint(location(
                new ObjectReference(
                        schema, table, name, DbObjType.CONSTRAINT)));
        constraint.setPrimaryKey(true);
        constraint.addColumn(column);
        return constraint;
    }

    private static ObjectLocation location(ObjectReference reference) {
        return new ObjectLocation.Builder()
                .setReference(reference)
                .setLocationType(
                        ObjectLocation.LocationType.DEFINITION)
                .build();
    }

    private static IndexPathRef path(String relativePath) {
        return new IndexPathRef(
                IndexPathOrigin.PROJECT, relativePath);
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
