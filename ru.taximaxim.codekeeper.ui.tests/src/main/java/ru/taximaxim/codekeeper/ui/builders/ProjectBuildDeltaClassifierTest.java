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
package ru.taximaxim.codekeeper.ui.builders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import ru.taximaxim.codekeeper.ui.builders.ProjectBuildDeltaClassifier.Entry;
import ru.taximaxim.codekeeper.ui.builders.ProjectBuildDeltaClassifier.Kind;
import ru.taximaxim.codekeeper.ui.builders.ProjectBuildDeltaClassifier.Mode;
import ru.taximaxim.codekeeper.ui.builders.ProjectBuildDeltaClassifier.Reason;

class ProjectBuildDeltaClassifierTest {

    @Test
    void selectsExactlyOneExistingSqlContentChange() {
        var result = classifyWithoutAddedFiles(List.of(
                changed("SCHEMA/app/FUNCTION/calculate.sql")));

        assertEquals(Mode.INCREMENTAL, result.mode());
        assertEquals(List.of(
                "SCHEMA/app/FUNCTION/calculate.sql"),
                result.relativePaths());
    }

    @Test
    void selectsCanonicalIncrementalBatchesAtEveryBoundary() {
        for (int count : List.of(2, 10, 16)) {
            List<Entry> entries = IntStream.range(0, count)
                    .mapToObj(index -> changed(String.format(
                            "./SCHEMA\\app\\FUNCTION\\function_%02d.sql",
                            count - index - 1)))
                    .toList();

            var result =
                    classifyWithoutAddedFiles(entries);

            assertEquals(Mode.INCREMENTAL, result.mode(),
                    Integer.toString(count));
            assertEquals(IntStream.range(0, count)
                    .mapToObj(index -> String.format(
                            "SCHEMA/app/FUNCTION/function_%02d.sql",
                            index))
                    .toList(), result.relativePaths(),
                    Integer.toString(count));
            assertThrows(UnsupportedOperationException.class,
                    () -> result.relativePaths().add(
                            "SCHEMA/app/FUNCTION/extra.sql"));
        }
    }

    @Test
    void ignoresMarkerOnlyAndUnrelatedChanges() {
        var result = classifyWithoutAddedFiles(List.of(
                entry("SCHEMA/app/FUNCTION/calculate.sql",
                        Kind.CHANGED, false, false, true, false),
                entry("SCHEMA/app/README.md",
                        Kind.CHANGED, true, false, false, false),
                entry(".settings/project.prefs",
                        Kind.CHANGED, true, false, false, false)));

        assertEquals(Mode.NO_OP, result.mode());
    }

    @Test
    void ignoresAllChangesInsideEarlyExcludedSchemas() {
        var result = classifyWithoutAddedFiles(List.of(
                entry("SCHEMA/dummy_tmp/FUNCTION/generated.sql",
                        Kind.CHANGED, true, false, true, true),
                entry("SCHEMA/dummy_tmp/TABLE/new_table.sql",
                        Kind.ADDED, true, false, true, true),
                entry("SCHEMA/dummy_tmp/VIEW/old_view.sql",
                        Kind.REMOVED, false, false, true, true)));

        assertEquals(Mode.NO_OP, result.mode());
    }

    @Test
    void forcesFullBuildBeyondBatchLimit() {
        int limit = ProjectBuildDeltaClassifier.MAX_INCREMENTAL_PATHS;
        List<Entry> withinLimit = IntStream.range(0, limit)
                .mapToObj(index -> changed(String.format(
                        "SCHEMA/app/FUNCTION/function_%04d.sql",
                        index)))
                .toList();
        List<Entry> beyondLimit = IntStream.range(0, limit + 1)
                .mapToObj(index -> changed(String.format(
                        "SCHEMA/app/FUNCTION/function_%04d.sql",
                        index)))
                .toList();

        assertEquals(Mode.INCREMENTAL,
                classifyWithoutAddedFiles(withinLimit).mode());
        assertEquals(Mode.FULL,
                classifyWithoutAddedFiles(beyondLimit).mode());
    }

    @Test
    void forcesFullBuildForDuplicateNormalizedPaths() {
        var result = classifyWithoutAddedFiles(List.of(
                changed("./SCHEMA/app/FUNCTION/calculate.sql"),
                changed("SCHEMA\\app\\FUNCTION\\calculate.sql")));

        assertEquals(Mode.FULL, result.mode());
    }

    @Test
    void forcesFullBuildForNonSqlAndDirectorySchemaChanges() {
        assertEquals(Mode.FULL,
                classifyWithoutAddedFiles(List.of(
                        entry("SCHEMA/app/README.md",
                                Kind.CHANGED, true, false,
                                true, false))).mode());
        assertEquals(Mode.FULL,
                classifyWithoutAddedFiles(List.of(
                        new Entry("SCHEMA/app/FUNCTION",
                                Kind.CHANGED, true, false,
                                true, false, true))).mode());
    }

    @Test
    void forcesFullBuildForAddRemoveReplaceAndMove() {
        assertEquals(Mode.FULL,
                classifyWithoutAddedFiles(List.of(
                        entry("SCHEMA/app/TABLE/new.sql",
                                Kind.ADDED, true, false, true, false))).mode());
        assertEquals(Mode.FULL,
                classifyWithoutAddedFiles(List.of(
                        entry("SCHEMA/app/TABLE/old.sql",
                                Kind.REMOVED, false, false, true, false))).mode());
        assertEquals(Mode.FULL,
                classifyWithoutAddedFiles(List.of(
                        entry("SCHEMA/app/TABLE/replaced.sql",
                                Kind.CHANGED, true, true, true, false))).mode());
    }

    @Test
    void forcesFullBuildForMixedDeltaKinds() {
        var result = classifyWithoutAddedFiles(List.of(
                changed("SCHEMA/app/TABLE/existing.sql"),
                entry("SCHEMA/app/TABLE/added.sql",
                        Kind.ADDED, true, false,
                        true, false)));

        assertEquals(Mode.FULL, result.mode());
    }

    @Test
    void forcesFullBuildForRootConfigurationContentChanges() {
        for (String path : List.of(
                ".pgcodekeeper",
                ".pgcodekeeperignore",
                ".pgcodekeeperignoreschema",
                ".pgcodekeeperdependencies",
                ".dependencies",
                "structure.properties")) {
            var result = classifyWithoutAddedFiles(List.of(
                    entry(path, Kind.CHANGED,
                            true, false, false, false)));
            assertEquals(Mode.FULL, result.mode(), path);
        }
    }

    @Test
    void ignoresMarkerOnlyRootConfigurationDelta() {
        var result = classifyWithoutAddedFiles(List.of(
                entry(".pgcodekeeperignore", Kind.CHANGED,
                        false, false, false, false)));

        assertEquals(Mode.NO_OP, result.mode());
    }

    @Test
    void configurationChangeDominatesSingleFileCandidate() {
        var result = classifyWithoutAddedFiles(List.of(
                changed("SCHEMA/app/FUNCTION/calculate.sql"),
                entry("structure.properties", Kind.CHANGED,
                        true, false, false, false)));

        assertEquals(Mode.FULL, result.mode());
    }

    @Test
    void nonIncrementalResultsRetainNoPaths() {
        assertEquals(List.of(),
                classifyWithoutAddedFiles(
                        List.of()).relativePaths());
        assertEquals(List.of(),
                ProjectBuildDeltaClassifier.full()
                        .relativePaths());
    }

    @Test
    void resultRejectsNonCanonicalRetainedPaths() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProjectBuildDeltaClassifier.Result(
                        Mode.INCREMENTAL,
                        List.of("./SCHEMA/app/TABLE/item.sql")));
    }

    /**
     * The delta a pull carries most often: files that already exist plus one
     * that does not. Before the preference existed the single addition sent all
     * of them to a full rebuild.
     */
    @Test
    void anAddedFileJoinsTheBatchOnlyWhileThePreferenceIsOn() {
        List<Entry> delta = List.of(
                changed("SCHEMA/app/FUNCTION/calculate.sql"),
                added("SCHEMA/app/TABLE/item.sql"));

        var permitted = ProjectBuildDeltaClassifier.classify(delta, true);
        assertEquals(Mode.INCREMENTAL, permitted.mode());
        assertEquals(List.of("SCHEMA/app/FUNCTION/calculate.sql",
                "SCHEMA/app/TABLE/item.sql"),
                permitted.relativePaths());

        assertEquals(Mode.FULL,
                ProjectBuildDeltaClassifier.classify(delta, false).mode());
    }

    @Test
    void anAdditionAloneIsAnIncrementWhileThePreferenceIsOn() {
        var result = ProjectBuildDeltaClassifier.classify(List.of(
                added("SCHEMA/app/TABLE/item.sql")), true);

        assertEquals(Mode.INCREMENTAL, result.mode());
        assertEquals(List.of("SCHEMA/app/TABLE/item.sql"),
                result.relativePaths());
    }

    /**
     * Removal is out of the preference's reach. Nothing can narrow which files
     * held a reference to what a removal took away, so it stays a full rebuild
     * whatever the preference says.
     */
    @Test
    void aRemovedFileForcesAFullBuildUnderEitherPreference() {
        for (boolean allowAddedFiles : List.of(false, true)) {
            assertEquals(Mode.FULL,
                    ProjectBuildDeltaClassifier.classify(List.of(
                            entry("SCHEMA/app/TABLE/old.sql", Kind.REMOVED,
                                    false, false, true, false)),
                            allowAddedFiles).mode(),
                    Boolean.toString(allowAddedFiles));
            assertEquals(Mode.FULL,
                    ProjectBuildDeltaClassifier.classify(List.of(
                            changed("SCHEMA/app/FUNCTION/calculate.sql"),
                            entry("SCHEMA/app/TABLE/old.sql", Kind.REMOVED,
                                    false, false, true, false)),
                            allowAddedFiles).mode(),
                    Boolean.toString(allowAddedFiles));
        }
    }

    /**
     * A structural flag says the resource itself moved, was replaced or changed
     * type, and that is not the plain appearance of a new file the preference
     * opens the door for.
     */
    @Test
    void aStructuralAdditionForcesAFullBuildEvenWhenAdditionsArePermitted() {
        assertEquals(Mode.FULL,
                ProjectBuildDeltaClassifier.classify(List.of(
                        entry("SCHEMA/app/TABLE/moved.sql", Kind.ADDED,
                                true, true, true, false)), true).mode());
    }

    @Test
    void thePreferenceChangesNothingOutsideTheIndexedHierarchy() {
        for (boolean allowAddedFiles : List.of(false, true)) {
            String reason = Boolean.toString(allowAddedFiles);
            assertEquals(Mode.NO_OP,
                    ProjectBuildDeltaClassifier.classify(List.of(
                            entry("docs/NOTES.md", Kind.ADDED,
                                    true, false, false, false)),
                            allowAddedFiles).mode(), reason);
            assertEquals(Mode.NO_OP,
                    ProjectBuildDeltaClassifier.classify(List.of(
                            entry("SCHEMA/dummy_tmp/TABLE/item.sql",
                                    Kind.ADDED, true, false, true, true)),
                            allowAddedFiles).mode(), reason);
            assertEquals(Mode.FULL,
                    ProjectBuildDeltaClassifier.classify(List.of(
                            entry("SCHEMA/app/TABLE/notes.md", Kind.ADDED,
                                    true, false, true, false)),
                            allowAddedFiles).mode(), reason);
            assertEquals(Mode.FULL,
                    ProjectBuildDeltaClassifier.classify(List.of(
                            new Entry("SCHEMA/app/TABLE", Kind.ADDED,
                                    true, false, true, false, true)),
                            allowAddedFiles).mode(), reason);
        }
    }

    @Test
    void permittedAdditionsObeyTheBatchLimit() {
        int limit = ProjectBuildDeltaClassifier.MAX_INCREMENTAL_PATHS;
        List<Entry> withinLimit = IntStream.range(0, limit)
                .mapToObj(index -> added(String.format(
                        "SCHEMA/app/TABLE/item_%04d.sql", index)))
                .toList();
        List<Entry> beyondLimit = IntStream.range(0, limit + 1)
                .mapToObj(index -> added(String.format(
                        "SCHEMA/app/TABLE/item_%04d.sql", index)))
                .toList();

        assertEquals(Mode.INCREMENTAL, ProjectBuildDeltaClassifier
                .classify(withinLimit, true).mode());
        assertEquals(Mode.FULL, ProjectBuildDeltaClassifier
                .classify(beyondLimit, true).mode());
    }

    /**
     * A log line names a reason by its token, so two reasons sharing one token
     * would be indistinguishable in exactly the situation the line exists for.
     */
    @Test
    void everyReasonCarriesItsOwnNonEmptyToken() {
        Set<String> tokens = new HashSet<>();
        for (Reason reason : Reason.values()) {
            String token = reason.token();
            assertNotEquals("", token, reason.name());
            assertEquals(true, tokens.add(token), token);
        }
        assertEquals(Reason.values().length, tokens.size());
    }

    /**
     * A reason describes a refusal, so a verdict that refused nothing may not
     * carry one and a full rebuild may not go unexplained.
     */
    @Test
    void resultRejectsAReasonThatContradictsItsMode() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProjectBuildDeltaClassifier.Result(
                        Mode.NO_OP, List.of(), Reason.NO_DELTA, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ProjectBuildDeltaClassifier.Result(
                        Mode.INCREMENTAL,
                        List.of("SCHEMA/app/TABLE/item.sql"),
                        Reason.NON_SQL, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ProjectBuildDeltaClassifier.Result(
                        Mode.FULL, List.of(), Reason.NONE, null));
    }

    @Test
    void theCompatibilityConstructorNamesTheDefaultReason() {
        var noOp = new ProjectBuildDeltaClassifier.Result(
                Mode.NO_OP, List.of());
        var incremental = new ProjectBuildDeltaClassifier.Result(
                Mode.INCREMENTAL,
                List.of("SCHEMA/app/TABLE/item.sql"));
        var full = new ProjectBuildDeltaClassifier.Result(
                Mode.FULL, List.of());

        assertEquals(Reason.NONE, noOp.reason());
        assertEquals(Reason.NONE, incremental.reason());
        assertEquals(Reason.UNSPECIFIED, full.reason());
        assertNull(noOp.evidence());
        assertNull(incremental.evidence());
        assertNull(full.evidence());
        assertEquals(Reason.NONE,
                new ProjectBuildDeltaClassifier.Result(
                        Mode.NO_OP, (String) null).reason());
        assertEquals(Reason.UNSPECIFIED,
                ProjectBuildDeltaClassifier.full().reason());
    }

    @Test
    void namesTheConfigurationInputThatForcedTheRebuild() {
        assertReason(Reason.CONFIGURATION_INPUT,
                classifyWithoutAddedFiles(List.of(
                        entry("structure.properties", Kind.CHANGED,
                                true, false, false, false))));
    }

    @Test
    void namesTheDirectoryThatForcedTheRebuild() {
        assertReason(Reason.DIRECTORY,
                classifyWithoutAddedFiles(List.of(
                        new Entry("SCHEMA/app/FUNCTION", Kind.CHANGED,
                                true, false, true, false, true))));
    }

    @Test
    void namesTheNonSqlFileThatForcedTheRebuild() {
        assertReason(Reason.NON_SQL,
                classifyWithoutAddedFiles(List.of(
                        entry("SCHEMA/app/README.md", Kind.CHANGED,
                                true, false, true, false))));
    }

    @Test
    void namesTheRemovalThatForcedTheRebuild() {
        for (boolean allowAddedFiles : List.of(false, true)) {
            assertReason(Reason.REMOVED_FILE,
                    ProjectBuildDeltaClassifier.classify(List.of(
                            entry("SCHEMA/app/TABLE/old.sql", Kind.REMOVED,
                                    false, false, true, false)),
                            allowAddedFiles));
        }
    }

    @Test
    void namesTheStructuralChangeThatForcedTheRebuild() {
        for (boolean allowAddedFiles : List.of(false, true)) {
            assertReason(Reason.STRUCTURAL_CHANGE,
                    ProjectBuildDeltaClassifier.classify(List.of(
                            entry("SCHEMA/app/TABLE/replaced.sql",
                                    Kind.CHANGED, true, true, true, false)),
                            allowAddedFiles));
        }
    }

    /**
     * The refusal the preference exists to lift, and the one a pull runs into
     * most often. It must be told apart from a structural change, which the
     * preference cannot lift.
     */
    @Test
    void namesTheAdditionRefusedWhileThePreferenceIsOff() {
        assertReason(Reason.ADDED_FILE_DISABLED,
                classifyWithoutAddedFiles(List.of(
                        added("SCHEMA/app/TABLE/item.sql"))));
    }

    @Test
    void namesTheDuplicatePathThatForcedTheRebuild() {
        assertReason(Reason.DUPLICATE_PATH,
                classifyWithoutAddedFiles(List.of(
                        changed("./SCHEMA/app/FUNCTION/calculate.sql"),
                        changed("SCHEMA\\app\\FUNCTION\\calculate.sql"))));
    }

    @Test
    void namesTheBatchLimitThatForcedTheRebuild() {
        int limit = ProjectBuildDeltaClassifier.MAX_INCREMENTAL_PATHS;

        assertReason(Reason.BATCH_TOO_LARGE,
                classifyWithoutAddedFiles(IntStream.range(0, limit + 1)
                        .mapToObj(index -> changed(String.format(
                                "SCHEMA/app/FUNCTION/function_%04d.sql",
                                index)))
                        .toList()));
    }

    /**
     * The predicate and the reason are one decision read twice. Were they two,
     * a refusal could be reported under a reason that did not cause it.
     */
    @Test
    void theBatchPredicateAgreesWithTheReasonItReports() {
        for (Kind kind : Kind.values()) {
            for (boolean structuralChange : List.of(false, true)) {
                for (boolean allowAddedFiles : List.of(false, true)) {
                    Entry entry = entry("SCHEMA/app/TABLE/item.sql",
                            kind, true, structuralChange, true, false);
                    String combination = kind + "/" + structuralChange
                            + "/" + allowAddedFiles;

                    assertEquals(
                            ProjectBuildDeltaClassifier.batchRefusal(
                                    entry, allowAddedFiles) == null,
                            ProjectBuildDeltaClassifier.canJoinBatch(
                                    entry, allowAddedFiles),
                            combination);
                }
            }
        }
    }

    private static void assertReason(Reason expected,
            ProjectBuildDeltaClassifier.Result result) {
        assertEquals(Mode.FULL, result.mode(), expected.name());
        assertEquals(expected, result.reason());
        assertTrue(result.relativePaths().isEmpty());
    }

    /**
     * Every assertion written before the preference existed describes the
     * behaviour with it off, which is the behaviour that must not move.
     */
    private static ProjectBuildDeltaClassifier.Result
            classifyWithoutAddedFiles(List<Entry> entries) {
        return ProjectBuildDeltaClassifier.classify(entries, false);
    }

    private static Entry changed(String path) {
        return entry(path, Kind.CHANGED,
                true, false, true, false);
    }

    private static Entry added(String path) {
        return entry(path, Kind.ADDED,
                true, false, true, false);
    }

    private static Entry entry(String path, Kind kind,
            boolean contentChanged, boolean structuralChange,
            boolean indexedHierarchy, boolean excluded) {
        return new Entry(path, kind, contentChanged,
                structuralChange, indexedHierarchy, excluded);
    }
}
