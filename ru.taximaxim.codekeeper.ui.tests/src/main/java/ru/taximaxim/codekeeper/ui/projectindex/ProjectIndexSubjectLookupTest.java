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

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgcodekeeper.core.database.api.schema.ArgMode;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.base.schema.Argument;
import org.pgcodekeeper.core.database.base.schema.meta.MetaFunction;

import ru.taximaxim.codekeeper.ui.DatabaseType;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexDefinitionSubject;
import ru.taximaxim.codekeeper.ui.projectindex.ReferenceMatchKey.MatchFamily;

class ProjectIndexSubjectLookupTest {

    private static final int JOURNAL_HEADER_BYTES = 100;
    private static final int JOURNAL_PATH_CRC_OFFSET = 36;
    private static final int JOURNAL_HEADER_CRC_OFFSET = 96;

    @Test
    void subjectRangesReturnOnlyMatchingDefinitionsWithoutDecodingFiles(
            @TempDir Path directory) throws Exception {
        ProjectIndexData source = withFunctions(
                ProjectIndexFixtures.snapshotWithAllMetaKinds(),
                function("schema/function_text.sql", "app",
                        "calculate(text)", "calculate", "text"),
                function("schema/audit_function.sql", "audit",
                        "calculate(integer)", "calculate", "integer"));
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(source.manifest());

        try (var store = new ProjectIndexStore(directory)) {
            assertEquals(ProjectIndexStore.PublishResult.PUBLISHED,
                    store.publish(source, () -> false));
        }

        try (var store = new ProjectIndexStore(directory);
                var opened = store.open(identity)) {
            ProjectIndexView view = opened.view().orElseThrow();

            assertEquals(List.of("calculate(integer)", "calculate(text)"),
                    names(view.definitions(subject(
                            MatchFamily.ROUTINE, null, "app", null))));
            assertEquals(List.of("calculate(text)"),
                    names(view.definitions(subject(
                            MatchFamily.ROUTINE, null,
                            "app", "calculate(text)"))));
            assertEquals(List.of("item"),
                    names(view.definitions(subject(
                            MatchFamily.RELATION, null, null, null))));
            assertEquals(List.of("item_pair"),
                    names(view.definitions(subject(
                            MatchFamily.TYPE, null, "app", "item_pair"))));
            assertEquals(List.of("<=>"),
                    names(view.definitions(subject(
                            MatchFamily.EXACT, DbObjType.OPERATOR,
                            "app", "<=>"))));
            assertEquals(List.of("app.item AS jsonb"),
                    names(view.definitions(subject(
                            MatchFamily.EXACT, DbObjType.CAST,
                            "app.item AS jsonb", null))));
            assertEquals(List.of("item_pk"),
                    names(view.definitions(subject(
                            MatchFamily.EXACT, DbObjType.CONSTRAINT,
                            "app", "item"))));
            assertEquals(0, view.decodedContributionCount());
        }
    }

    @Test
    void subjectRangesApplyJournalOverlayAndExcludeChangedPath(
            @TempDir Path directory) throws Exception {
        FunctionFile changed = function("schema/changed.sql", "app",
                "calculate(integer)", "calculate", "integer");
        FunctionFile retained = function("schema/retained.sql", "app",
                "keep()", "keep", null);
        FunctionFile deleted = function("schema/deleted.sql", "app",
                "drop_me()", "drop_me", null);
        ProjectIndexData source = data(List.of(changed, retained, deleted));
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(source.manifest());
        FunctionFile replacement = function("schema/changed.sql", "app",
                "calculate(text)", "calculate", "text");

        try (var store = new ProjectIndexStore(directory)) {
            assertEquals(ProjectIndexStore.PublishResult.PUBLISHED,
                    store.publish(source, () -> false));
            try (var opened = store.open(identity)) {
                ProjectIndexView view = opened.view().orElseThrow();
                assertEquals(ProjectIndexStore.AppendResult.APPENDED,
                        store.append(view, new ProjectIndexDelta(List.of(
                                ProjectIndexDelta.Change.replace(
                                        replacement.stamp(),
                                        replacement.contribution()),
                                ProjectIndexDelta.Change.delete(
                                        deleted.path()))), () -> false));
            }
        }

        try (var store = new ProjectIndexStore(directory);
                var opened = store.open(identity)) {
            ProjectIndexView view = opened.view().orElseThrow();
            ProjectIndexDefinitionSubject routines = subject(
                    MatchFamily.ROUTINE, null, "app", null);

            assertEquals(List.of("calculate(text)", "keep()"),
                    names(view.definitions(routines)));
            assertEquals(List.of("keep()"),
                    names(view.definitions(routines, changed.path())));
            assertEquals(0, view.decodedContributionCount());
        }
    }

    @Test
    void subjectLookupRejectsJournalWhosePayloadBelongsToAnotherPath(
            @TempDir Path directory) throws Exception {
        FunctionFile alpha = function("schema/alpha.sql", "app",
                "alpha()", "alpha", null);
        FunctionFile bravo = function("schema/bravo.sql", "app",
                "bravo()", "bravo", null);
        ProjectIndexData source = data(List.of(alpha, bravo));
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(source.manifest());
        FunctionFile replacement = function("schema/bravo.sql", "app",
                "bravo(integer)", "bravo", "integer");
        Path journal;

        try (var store = new ProjectIndexStore(directory)) {
            assertEquals(ProjectIndexStore.PublishResult.PUBLISHED,
                    store.publish(source, () -> false));
            try (var opened = store.open(identity)) {
                ProjectIndexView view = opened.view().orElseThrow();
                assertEquals(ProjectIndexStore.AppendResult.APPENDED,
                        store.append(view, new ProjectIndexDelta(List.of(
                                ProjectIndexDelta.Change.replace(
                                        replacement.stamp(),
                                        replacement.contribution()))),
                                () -> false));
                journal = view.journalPathForTests();
            }
        }

        byte[] alphaPath = alpha.path().relativePath()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] bravoPath = bravo.path().relativePath()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(bravoPath.length, alphaPath.length);
        byte[] bytes = Files.readAllBytes(journal);
        assertArrayEquals(bravoPath, java.util.Arrays.copyOfRange(bytes,
                JOURNAL_HEADER_BYTES,
                JOURNAL_HEADER_BYTES + bravoPath.length));
        System.arraycopy(alphaPath, 0, bytes, JOURNAL_HEADER_BYTES,
                alphaPath.length);
        ByteBuffer header = ByteBuffer.wrap(bytes);
        header.putInt(JOURNAL_PATH_CRC_OFFSET,
                ProjectIndexFormat.crc32c(
                        alphaPath, 0, alphaPath.length));
        header.putInt(JOURNAL_HEADER_CRC_OFFSET,
                ProjectIndexFormat.crc32c(
                        bytes, 0, JOURNAL_HEADER_CRC_OFFSET));
        Files.write(journal, bytes);

        try (var store = new ProjectIndexStore(directory);
                var opened = store.open(identity)) {
            assertEquals(ProjectIndexOpenResult.Status.HIT, opened.status(),
                    "Opening must not eagerly checksum journal payload blocks");
            ProjectIndexView view = opened.view().orElseThrow();
            ProjectIndexDefinitionSubject routines = subject(
                    MatchFamily.ROUTINE, null, "app", null);
            assertThrows(ProjectIndexFormatException.class,
                    () -> view.definitions(routines));
            assertThrows(ProjectIndexFormatException.class,
                    () -> view.definitions(routines),
                    "A failed path validation must never be cached as successful");
            assertEquals(0, view.decodedContributionCount());
        }
    }

    @Test
    void subjectRangeCrossesSparseIndexWindows(
            @TempDir Path directory) throws Exception {
        List<FunctionFile> functions = new ArrayList<>();
        IntStream.range(0, 80).mapToObj(index -> functionUnchecked(
                "schema/app_" + index + ".sql", "app",
                "routine_" + index + "()", "routine_" + index, null))
                .forEach(functions::add);
        IntStream.range(0, 12).mapToObj(index -> functionUnchecked(
                "schema/audit_" + index + ".sql", "audit",
                "routine_" + index + "()", "routine_" + index, null))
                .forEach(functions::add);
        ProjectIndexData source = data(functions);
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(source.manifest());

        try (var store = new ProjectIndexStore(directory)) {
            assertEquals(ProjectIndexStore.PublishResult.PUBLISHED,
                    store.publish(source, () -> false));
        }

        try (var store = new ProjectIndexStore(directory);
                var opened = store.open(identity)) {
            ProjectIndexView view = opened.view().orElseThrow();
            List<PackedDefinition> definitions = view.definitions(subject(
                    MatchFamily.ROUTINE, null, "app", null));

            assertEquals(80, definitions.size());
            assertTrue(definitions.stream().allMatch(definition ->
                    "app".equals(definition.object()
                            .reference().schema())));
            assertEquals(0, view.decodedContributionCount());
        }
    }

    @Test
    void subjectLookupTraversesEachDefinitionBlockOnlyOnce(
            @TempDir Path directory) throws Exception {
        List<FunctionFile> functions = IntStream.range(0, 5_000)
                .mapToObj(index -> functionUnchecked(
                        "schema/app_" + index + ".sql", "app",
                        "routine_" + index + "()", "routine_" + index, null))
                .toList();
        ProjectIndexData source = data(functions);
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(source.manifest());

        try (var store = new ProjectIndexStore(directory)) {
            assertEquals(ProjectIndexStore.PublishResult.PUBLISHED,
                    store.publish(source, () -> false));
        }

        try (var store = new ProjectIndexStore(directory);
                var opened = store.open(identity)) {
            ProjectIndexView view = opened.view().orElseThrow();
            assertTrue(view.definitionBlockCountForTests() > 1);
            long before = view.definitionBlockTraversals();

            assertEquals(5_000, view.definitions(subject(
                    MatchFamily.ROUTINE, null, "app", null)).size());

            assertEquals(view.definitionBlockCountForTests(),
                    view.definitionBlockTraversals() - before);
            assertEquals(0, view.decodedContributionCount());
        }
    }

    @Test
    void subjectLookupAccountsForSortingCopiesBeforeAllocatingThem(
            @TempDir Path directory) throws Exception {
        List<FunctionFile> functions = IntStream.range(0, 80)
                .mapToObj(index -> functionUnchecked(
                        "schema/budget_" + index + ".sql", "app",
                        "budget_" + index + "()", "budget_" + index, null))
                .toList();
        ProjectIndexData source = data(functions);
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(source.manifest());

        try (var store = new ProjectIndexStore(directory)) {
            assertEquals(ProjectIndexStore.PublishResult.PUBLISHED,
                    store.publish(source, () -> false));
        }

        try (var store = new ProjectIndexStore(directory);
                var opened = store.open(identity)) {
            ProjectIndexView view = opened.view().orElseThrow();
            ProjectIndexDefinitionSubject routines = subject(
                    MatchFamily.ROUTINE, null, "app", null);
            long low = 0;
            long high = 1;
            while (failsAtSelectedIds(view, routines, high)) {
                low = high;
                high *= 2;
            }
            while (low + 1 < high) {
                long middle = (low + high) >>> 1;
                if (failsAtSelectedIds(view, routines, middle)) {
                    low = middle;
                } else {
                    high = middle;
                }
            }

            long sortingBoundary = high;
            ProjectIndexFormatException failure = assertThrows(
                    ProjectIndexFormatException.class,
                    () -> view.definitionsWithBudgetForTests(
                            routines, sortingBoundary));
            assertTrue(failure.getMessage().contains(
                    "sorted definition subject result"));
            assertEquals(0, view.decodedContributionCount());
        }
    }

    @Test
    void repeatedSubjectLookupsDecodeTheDictionaryOnlyOnce(
            @TempDir Path directory) throws Exception {
        ProjectIndexData source =
                ProjectIndexFixtures.largeIndexedProject(5_000);
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(source.manifest());

        try (var store = new ProjectIndexStore(directory)) {
            assertEquals(ProjectIndexStore.PublishResult.PUBLISHED,
                    store.publish(source, () -> false));
        }

        try (var store = new ProjectIndexStore(directory);
                var opened = store.open(identity)) {
            ProjectIndexView view = opened.view().orElseThrow();
            ProjectIndexDefinitionSubject routine = subject(
                    MatchFamily.ROUTINE, null, "app", "routine_18Z()");

            ProjectIndexStringProbes beforeFirst = view.stringProbes();
            List<String> firstNames = names(view.definitions(routine));
            ProjectIndexStringProbes first =
                    view.stringProbes().since(beforeFirst);

            ProjectIndexStringProbes beforeSecond = view.stringProbes();
            List<String> secondNames = names(view.definitions(routine));
            ProjectIndexStringProbes second =
                    view.stringProbes().since(beforeSecond);

            assertAll(
                    () -> assertEquals(List.of("routine_18Z()"), firstNames),
                    () -> assertEquals(firstNames, secondNames),
                    () -> assertTrue(first.records() > 0,
                            "the first lookup has to decode the blocks it"
                                    + " lands in: " + first),
                    // The second lookup asks the dictionary exactly as often
                    // as the first - nothing about the question changed - but
                    // the blocks that answer it are already decoded, so it
                    // walks no records at all. That gap is the whole point of
                    // keeping them: a probe stops being a block scan.
                    () -> assertEquals(first.probes(), second.probes(),
                            "the same subject asks the same questions: "
                                    + second),
                    () -> assertEquals(0, second.records(),
                            "a resident block must not be decoded again: "
                                    + second));
        }
    }

    private static ProjectIndexDefinitionSubject subject(MatchFamily family,
            DbObjType exactType, String schema, String objectName) {
        return new ProjectIndexDefinitionSubject(
                family, exactType, schema, objectName);
    }

    private static List<String> names(List<PackedDefinition> definitions) {
        return definitions.stream()
                .map(definition -> definition.object().reference().getName())
                .sorted()
                .toList();
    }

    private static boolean failsAtSelectedIds(ProjectIndexView view,
            ProjectIndexDefinitionSubject subject, long budget) {
        try {
            view.definitionsWithBudgetForTests(subject, budget);
            return false;
        } catch (ProjectIndexFormatException ex) {
            return ex.getMessage().contains("definition subject ids");
        }
    }

    private static ProjectIndexData withFunctions(ProjectIndexData source,
            FunctionFile... added) {
        List<ProjectFileStamp> stamps =
                new ArrayList<>(source.manifest().files());
        List<FileContribution> files = new ArrayList<>(source.files());
        for (FunctionFile file : added) {
            stamps.add(file.stamp());
            files.add(file.contribution());
        }
        ProjectIndexManifest original = source.manifest();
        ProjectIndexManifest manifest = new ProjectIndexManifest(
                original.formatMajor(), original.formatMinor(),
                original.parserAbi(), original.coreVersion(),
                original.uiVersion(), original.databaseType(),
                original.projectIdentity(), original.configSha256(),
                original.generation(), stamps);
        return new ProjectIndexData(manifest, files);
    }

    private static ProjectIndexData data(List<FunctionFile> functions)
            throws Exception {
        List<ProjectFileStamp> stamps =
                functions.stream().map(FunctionFile::stamp).toList();
        List<FileContribution> files =
                functions.stream().map(FunctionFile::contribution).toList();
        ProjectIndexManifest manifest = new ProjectIndexManifest(
                2, 0, 2, "15.0.0-neo1", "15.0.0-neo1",
                DatabaseType.PG,
                HexFormat.of().formatHex(
                        ProjectIndexFixtures.sha256("subject-project")),
                ProjectIndexFixtures.sha256("subject-configuration"),
                1L, stamps);
        return new ProjectIndexData(manifest, files);
    }

    private static FunctionFile function(String relativePath, String schema,
            String signature, String bareName, String argumentType)
            throws Exception {
        IndexPathRef path = ProjectIndexFixtures.path(relativePath);
        ObjectLocation location = new ObjectLocation.Builder()
                .setFilePath(ProjectIndexFixtures.ABSOLUTE_ROOT + '/'
                        + path.relativePath())
                .setOffset(1)
                .setLineNumber(1)
                .setCharPositionInLine(0)
                .setLength(1)
                .setReference(new ObjectReference(
                        schema, signature, DbObjType.FUNCTION))
                .setLocationType(ObjectLocation.LocationType.DEFINITION)
                .build();
        MetaFunction function =
                new MetaFunction(location, bareName);
        if (argumentType != null) {
            function.addArgument(new Argument(
                    ArgMode.IN, "value", argumentType));
        }
        function.setReturns("void");
        FileContribution contribution = new FileContribution(
                path, List.of(PackedDefinition.from(function, path)),
                List.of(), Set.of(), false);
        ProjectFileStamp stamp = new ProjectFileStamp(
                path, 1L, 1L, 1L,
                ProjectIndexFixtures.sha256(relativePath + signature));
        return new FunctionFile(path, stamp, contribution);
    }

    private static FunctionFile functionUnchecked(
            String relativePath, String schema, String signature,
            String bareName, String argumentType) {
        try {
            return function(relativePath, schema, signature,
                    bareName, argumentType);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private record FunctionFile(IndexPathRef path, ProjectFileStamp stamp,
            FileContribution contribution) {
    }
}
