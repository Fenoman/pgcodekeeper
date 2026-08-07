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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgcodekeeper.core.database.api.schema.ArgMode;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.base.schema.Argument;
import org.pgcodekeeper.core.database.base.schema.meta.MetaFunction;

import ru.taximaxim.codekeeper.ui.DatabaseType;

/**
 * The rule that decides whether a batch may introduce files the index never
 * held. Both refusals are deliberately blunt, so the tests check that each one
 * fires on its own and that neither is skipped when the other already holds.
 */
class ProjectIndexAddedFilesRuleTest {

    @Test
    void aCleanIndexAcceptsAFileWithFreshKeys(@TempDir Path directory)
            throws Exception {
        try (Opened opened = index(directory, false)) {
            assertTrue(ProjectIndexIncrementalPlanner.permitsAddedFiles(
                    opened.view(), List.of(freshFile())),
                    "a new object that collides with nothing cannot"
                            + " re-resolve anything");
        }
    }

    @Test
    void anIndexHoldingAFileWithErrorsRefusesAnyAddition(
            @TempDir Path directory) throws Exception {
        try (Opened opened = index(directory, true)) {
            assertFalse(ProjectIndexIncrementalPlanner.permitsAddedFiles(
                    opened.view(), List.of(freshFile())),
                    "the new definition could bind a reference that never did,"
                            + " and the index cannot say whose");
        }
    }

    @Test
    void aKeyThatAlreadyExistsRefusesTheAddition(@TempDir Path directory)
            throws Exception {
        try (Opened opened = index(directory, false)) {
            assertFalse(ProjectIndexIncrementalPlanner.permitsAddedFiles(
                    opened.view(), List.of(collidingFile())),
                    "an overload can redirect references that resolve today");
        }
    }

    @Test
    void anEmptyAdditionIsVacuouslyPermitted(@TempDir Path directory)
            throws Exception {
        try (Opened opened = index(directory, true)) {
            assertTrue(ProjectIndexIncrementalPlanner.permitsAddedFiles(
                    opened.view(), List.of()),
                    "with nothing added this rule has nothing to say, even on"
                            + " an index it would otherwise refuse");
        }
    }

    /**
     * The journal half of the first refusal. This is the shape a developer's
     * index actually has — a published base plus a few replaced files — so a
     * check that only consulted the base would pass here while being wrong.
     */
    @Test
    void aJournalEntryWithErrorsRefusesTheAddition(@TempDir Path directory)
            throws Exception {
        ProjectIndexData source = data(false);
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(source.manifest());
        try (var store = new ProjectIndexStore(directory)) {
            store.publish(source, () -> false);
        }
        try (var store = new ProjectIndexStore(directory);
                var opened = store.open(identity)) {
            ProjectIndexView view = opened.view().orElseThrow();
            assertTrue(ProjectIndexIncrementalPlanner.permitsAddedFiles(
                    view, List.of(freshFile())),
                    "the published base is clean");

            ProjectFileStamp previous = source.manifest().files().getFirst();
            ProjectFileStamp bumped = new ProjectFileStamp(previous.path(),
                    previous.eclipseModificationStamp() + 10_000,
                    previous.size() + 1,
                    previous.lastModifiedMillis() + 10_000,
                    ProjectIndexFixtures.sha256("now-broken"));
            FileContribution broken = new FileContribution(previous.path(),
                    source.files().getFirst().definitions(),
                    source.files().getFirst().locations(), Set.of(), true);
            assertEquals(ProjectIndexStore.IncrementalAppendStatus.APPENDED,
                    store.appendIncremental(view,
                            new ProjectIndexDelta(List.of(
                                    ProjectIndexDelta.Change.replace(
                                            bumped, broken))),
                            () -> false).status());
        }

        try (var store = new ProjectIndexStore(directory);
                var opened = store.open(identity)) {
            assertFalse(ProjectIndexIncrementalPlanner.permitsAddedFiles(
                    opened.view().orElseThrow(), List.of(freshFile())),
                    "a journal entry that failed analysis must refuse the"
                            + " addition just as a base file would");
        }
    }

    /**
     * Every combination against an oracle that states the rule independently:
     * an empty batch is permitted whatever the index looks like, and otherwise
     * both a flagged file and a colliding key refuse.
     */
    @Test
    void theVerdictMatchesTheOracleOnEveryCombination(@TempDir Path root)
            throws Exception {
        List<String> mismatches = new ArrayList<>();
        for (boolean empty : List.of(false, true)) {
            for (boolean indexHasErrors : List.of(false, true)) {
                for (boolean keyCollides : List.of(false, true)) {
                    Path directory = root.resolve(
                            "e" + empty + "-r" + indexHasErrors
                                    + "-c" + keyCollides);
                    List<FileContribution> added = empty
                            ? List.of()
                            : List.of(keyCollides ? collidingFile()
                                    : freshFile());
                    boolean expected =
                            empty || (!indexHasErrors && !keyCollides);
                    boolean actual;
                    try (Opened opened = index(directory, indexHasErrors)) {
                        actual = ProjectIndexIncrementalPlanner
                                .permitsAddedFiles(opened.view(), added);
                    }
                    if (actual != expected) {
                        mismatches.add("empty=" + empty + " errors="
                                + indexHasErrors + " collides=" + keyCollides
                                + " expected=" + expected
                                + " actual=" + actual);
                    }
                }
            }
        }
        assertEquals(List.of(), mismatches,
                "the rule disagreed with the oracle on these combinations");
    }

    // --- fixtures ------------------------------------------------------------

    /** A published index holding {@code app.calculate(text)}. */
    private static ProjectIndexData data(boolean withErrors) throws Exception {
        FileContribution held = function("SCHEMA/app/calculate.sql", "app",
                "calculate(text)", "calculate", "text", withErrors);
        ProjectFileStamp stamp = new ProjectFileStamp(held.path(), 1L, 2L, 3L,
                ProjectIndexFixtures.sha256("calculate"));
        ProjectIndexManifest manifest = new ProjectIndexManifest(2, 0, 2,
                "15.0.0-neo1", "15.0.0-neo1", DatabaseType.PG,
                "b".repeat(64), ProjectIndexFixtures.sha256("configuration"),
                4L, List.of(stamp));
        return new ProjectIndexData(manifest, List.of(held));
    }

    /** A file claiming a subject the index does not have. */
    private static FileContribution freshFile() throws Exception {
        return function("SCHEMA/audit/report.sql", "audit", "report(integer)",
                "report", "integer", false);
    }

    /** A file claiming the very subject the index already holds. */
    private static FileContribution collidingFile() throws Exception {
        return function("SCHEMA/app/calculate_again.sql", "app",
                "calculate(text)", "calculate", "text", false);
    }

    private static FileContribution function(String relativePath, String schema,
            String signature, String bareName, String argumentType,
            boolean unresolvedAny) {
        IndexPathRef path = ProjectIndexFixtures.path(relativePath);
        ObjectLocation location = new ObjectLocation.Builder()
                .setFilePath(ProjectIndexFixtures.ABSOLUTE_ROOT + '/'
                        + path.relativePath())
                .setOffset(1)
                .setLineNumber(1)
                .setCharPositionInLine(0)
                .setLength(1)
                .setReference(new ObjectReference(schema, signature,
                        DbObjType.FUNCTION))
                .setLocationType(ObjectLocation.LocationType.DEFINITION)
                .build();
        MetaFunction function = new MetaFunction(location, bareName);
        function.addArgument(new Argument(ArgMode.IN, "value", argumentType));
        function.setReturns("void");
        return new FileContribution(path,
                List.of(PackedDefinition.from(function, path)),
                List.of(), Set.of(), unresolvedAny);
    }

    private static Opened index(Path directory, boolean withErrors)
            throws Exception {
        ProjectIndexData source = data(withErrors);
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(source.manifest());
        try (var store = new ProjectIndexStore(directory)) {
            store.publish(source, () -> false);
        }
        ProjectIndexStore store = new ProjectIndexStore(directory);
        return new Opened(store, store.open(identity));
    }

    private record Opened(ProjectIndexStore store,
            ProjectIndexOpenResult opened) implements AutoCloseable {

        ProjectIndexView view() {
            return opened.view().orElseThrow();
        }

        @Override
        public void close() throws Exception {
            opened.close();
            store.close();
        }
    }
}
