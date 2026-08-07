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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ProjectScope;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.base.schema.meta.MetaStatement;

import ru.taximaxim.codekeeper.ui.UIConsts;
import ru.taximaxim.codekeeper.ui.UIConsts.PREF;
import ru.taximaxim.codekeeper.ui.UIConsts.PROJ_PREF;
import ru.taximaxim.codekeeper.ui.projectindex.FileContribution;
import ru.taximaxim.codekeeper.ui.projectindex.IndexPathOrigin;
import ru.taximaxim.codekeeper.ui.projectindex.IndexPathRef;
import ru.taximaxim.codekeeper.ui.projectindex.PackedDefinition;
import ru.taximaxim.codekeeper.ui.projectindex.PackedDefinition.MetaKind;
import ru.taximaxim.codekeeper.ui.projectindex.PackedLocation;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectFileStamp;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexDefinitionSubject;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexDelta;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexIncrementalPlanner;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetry.Mode;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetryTestSupport;

/**
 * The path a pull takes when it brings a file the index never had.
 *
 * <p>The three end-to-end tests need a live workspace and a live
 * {@code Activator} - the preference that opens the path is read through the
 * plug-in's preference store and the batch is captured through Eclipse
 * resources - so they run under Tycho only. On a flat classpath they fail in
 * {@code createProject} with an {@code IllegalStateException} about the
 * workspace, never with an assertion. The batch-level tests below them need
 * neither and run anywhere.
 *
 * <p>The preference is a component of the index identity, so it is written
 * before the seeding build in every end-to-end test: flipping it between the
 * seed and the increment would retire the index and produce a full rebuild for
 * a reason that has nothing to do with what is being measured.
 */
class PgDbParserAddedFilesPathTest {

    private static final String FUNCTION_PATH =
            "SCHEMA/app/FUNCTION/pick.sql";
    private static final String ADDED_TABLE_PATH =
            "SCHEMA/app/TABLE/extra.sql";
    private static final String ADDED_FUNCTION_PATH =
            "SCHEMA/app/FUNCTION/extra_pick.sql";
    private static final String COLLIDING_TABLE_PATH =
            "SCHEMA/app/TABLE/item_again.sql";

    /**
     * The measured case: eighteen edits and one new file used to cost two full
     * builds. The batch has to stay incremental and has to leave the index
     * saying exactly what a full parse of the same tree says.
     */
    @Test
    void aBatchThatAddsAFileStaysIncrementalWhileThePreferenceIsOn(
            @TempDir Path temp) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(
                temp.resolve("added-incremental"), monitor);
        PgDbParser seedParser = new PgDbParser();
        PgDbParser incrementalParser = new PgDbParser();
        PgDbParser fullParser = new PgDbParser();
        try {
            permitAddedFiles(project, true);
            writeInitialProject(project);
            PgDbParser.PreparedProjectIndex cold =
                    seedParser.prepareProjectIndex(project, monitor);
            assertFalse(cold.restoredFromDisk());
            cold.update().commit(project.getName(), monitor);
            seedParser.clear();

            addFreshFiles(project, monitor);
            replaceFunctionBody(project, monitor);
            List<String> lines = new ArrayList<>();
            var telemetry = ProjectIndexTelemetryTestSupport.start(
                    lines, Mode.INCREMENTAL, millisecondClock());

            PgDbParser.PreparedIncrementalProjectIndex incremental =
                    incrementalParser.prepareIncrementalProjectIndex(
                            project, List.of(ADDED_TABLE_PATH,
                                    ADDED_FUNCTION_PATH, FUNCTION_PATH),
                            monitor, telemetry, null);

            assertFalse(incremental.fullBuild(),
                    "a pull that adds a file must not cost a full build");
            incremental.prepared().update().commit(
                    project.getName(), monitor);
            assertEquals(1, lines.size());
            assertTrue(lines.getFirst().contains("mode=incremental"),
                    lines.getFirst());

            fullParser.prepareFullDBFromPgDbProject(project, monitor)
                    .commit(project.getName(), monitor);
            assertEquals(semanticSnapshot(fullParser),
                    semanticSnapshot(incrementalParser),
                    "an incremental add must leave the index a full build's");
        } finally {
            cleanUp(project, monitor,
                    seedParser, incrementalParser, fullParser);
        }
    }

    @Test
    void theSameBatchFallsBackToAFullBuildWhileThePreferenceIsOff(
            @TempDir Path temp) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(
                temp.resolve("added-refused"), monitor);
        PgDbParser seedParser = new PgDbParser();
        PgDbParser incrementalParser = new PgDbParser();
        try {
            permitAddedFiles(project, false);
            writeInitialProject(project);
            seedParser.prepareProjectIndex(project, monitor)
                    .update().commit(project.getName(), monitor);
            seedParser.clear();

            addFreshFiles(project, monitor);
            replaceFunctionBody(project, monitor);

            PgDbParser.PreparedIncrementalProjectIndex incremental =
                    incrementalParser.prepareIncrementalProjectIndex(
                            project, List.of(ADDED_TABLE_PATH,
                                    ADDED_FUNCTION_PATH, FUNCTION_PATH),
                            monitor);

            assertTrue(incremental.fullBuild(),
                    "the preference is the only thing that opens this path");
            incremental.prepared().update().commit(
                    project.getName(), monitor);
        } finally {
            cleanUp(project, monitor, seedParser, incrementalParser);
        }
    }

    /**
     * An addition claiming a subject the index already holds is the case the
     * rule exists for: an overload or a shadowing object can redirect
     * references that resolve today, and which ones cannot be narrowed down.
     */
    @Test
    void anAdditionThatClaimsAnIndexedSubjectFallsBackToAFullBuild(
            @TempDir Path temp) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(
                temp.resolve("added-collision"), monitor);
        PgDbParser seedParser = new PgDbParser();
        PgDbParser incrementalParser = new PgDbParser();
        try {
            permitAddedFiles(project, true);
            writeInitialProject(project);
            seedParser.prepareProjectIndex(project, monitor)
                    .update().commit(project.getName(), monitor);
            seedParser.clear();

            write(project, COLLIDING_TABLE_PATH,
                    "CREATE TABLE app.item (id integer);\n", monitor);

            PgDbParser.PreparedIncrementalProjectIndex incremental =
                    incrementalParser.prepareIncrementalProjectIndex(
                            project, COLLIDING_TABLE_PATH, monitor);

            assertTrue(incremental.fullBuild(),
                    "a subject the index already holds refuses the batch");
            incremental.prepared().update().commit(
                    project.getName(), monitor);
        } finally {
            cleanUp(project, monitor, seedParser, incrementalParser);
        }
    }

    /**
     * The batch collector exempts an added path from the shape check because
     * there is no indexed shape to compare it against - and exempts nothing
     * else. The third case is the one that matters: the exemption follows the
     * set the batch declared, so a path that is merely missing from the
     * previous definitions is still refused.
     */
    @Test
    void theShapeCheckSkipsADeclaredAddedPathAndHoldsForEveryOther()
            throws Exception {
        IndexPathRef replaced = path("SCHEMA/app/FUNCTION/pick.sql");
        IndexPathRef added = path("SCHEMA/app/FUNCTION/extra_pick.sql");
        List<IndexPathRef> batch = List.of(replaced, added);
        Map<IndexPathRef, List<PackedDefinition>> previous =
                Map.of(replaced, definitions(replaced, "integer"));

        var loaded = new AtomicLong();
        List<ProjectIndexDelta.Change> accepted =
                PgDbParser.collectIncrementalReplacements(batch, previous,
                        Set.of(added),
                        loader(shapePreserved(replaced), reshaped(added)),
                        loaded::addAndGet).changes();
        assertNotNull(accepted,
                "an added file has no shape the index could refuse");
        assertEquals(2, accepted.size());
        assertEquals(2, loaded.get());

        assertNull(PgDbParser.collectIncrementalReplacements(batch, previous,
                Set.of(added),
                loader(reshaped(replaced), reshaped(added)),
                ignored -> { }).changes(),
                "a replaced file keeps the shape check it always had");

        assertNull(PgDbParser.collectIncrementalReplacements(batch, previous,
                Set.of(),
                loader(shapePreserved(replaced), reshaped(added)),
                ignored -> { }).changes(),
                "an undeclared path is refused, not silently exempted");
    }

    /**
     * The whole-batch check answers the same way: it exempts the declared added
     * paths from the shape comparison, still demands one change per replaced
     * path, and refuses a batch that declares as added a path the index holds.
     */
    @Test
    void theBatchCheckExemptsOnlyTheDeclaredAddedPaths() {
        IndexPathRef replaced = path("SCHEMA/app/FUNCTION/pick.sql");
        IndexPathRef added = path("SCHEMA/app/FUNCTION/extra_pick.sql");
        Map<IndexPathRef, List<PackedDefinition>> previous =
                Map.of(replaced, definitions(replaced, "integer"));
        var delta = new ProjectIndexDelta(List.of(
                change(shapePreserved(replaced)),
                change(reshaped(added))));

        assertTrue(ProjectIndexIncrementalPlanner.areSafeReplacements(
                previous, delta, Set.of(added)));
        assertFalse(ProjectIndexIncrementalPlanner.areSafeReplacements(
                previous, delta, Set.of()),
                "an undeclared addition has no shape to pass the check with");
        assertFalse(ProjectIndexIncrementalPlanner.areSafeReplacements(
                previous, delta, Set.of(added, replaced)),
                "a path cannot be added and replaced at once");
    }

    /**
     * The rule asked through the guard the parser hands it, rather than through
     * a packed view. Both refusals have to survive that door.
     */
    @Test
    void theRuleRefusesThroughTheGuardTheParserHandsIt() throws Exception {
        IndexPathRef added = path("SCHEMA/app/FUNCTION/extra_pick.sql");
        List<FileContribution> contributions = List.of(reshaped(added));

        assertTrue(ProjectIndexIncrementalPlanner.permitsAddedFiles(
                guard(false, false), contributions));
        assertFalse(ProjectIndexIncrementalPlanner.permitsAddedFiles(
                guard(true, false), contributions),
                "a file that may hold an unresolved reference refuses it");
        assertFalse(ProjectIndexIncrementalPlanner.permitsAddedFiles(
                guard(false, true), contributions),
                "a subject the index already holds refuses it");
        assertTrue(ProjectIndexIncrementalPlanner.permitsAddedFiles(
                guard(true, true), List.of()),
                "nothing added, nothing to forbid");
    }

    private static ProjectIndexIncrementalPlanner.AddedFilesGuard guard(
            boolean unresolved, boolean collides) {
        return new ProjectIndexIncrementalPlanner.AddedFilesGuard() {

            @Override
            public boolean anyFileMayHoldUnresolvedReferences() {
                return unresolved;
            }

            @Override
            public boolean holdsDefinitionFor(
                    ProjectIndexDefinitionSubject subject) {
                return collides;
            }
        };
    }

    private static PgDbParser.IncrementalBatchFileLoader loader(
            FileContribution... contributions) {
        return (index, path) -> new PgDbParser.LoadedBatchFile(
                stamp(contributions[index].path()),
                contributions[index], true, false);
    }

    /** A body-only edit: same shapes, different order. */
    private static FileContribution shapePreserved(IndexPathRef path) {
        return new FileContribution(path,
                definitions(path, "integer").reversed(),
                List.of(), Set.of(), false);
    }

    /** A signature change: no indexed shape survives it. */
    private static FileContribution reshaped(IndexPathRef path) {
        return new FileContribution(path, definitions(path, "jsonb"),
                List.of(), Set.of(), false);
    }

    private static ProjectIndexDelta.Change change(
            FileContribution contribution) {
        return ProjectIndexDelta.Change.replace(
                stamp(contribution.path()), contribution);
    }

    private static List<PackedDefinition> definitions(IndexPathRef path,
            String returns) {
        return List.of(definition(path, "pick_first", returns, 10),
                definition(path, "pick_second", "text", 60));
    }

    private static PackedDefinition definition(IndexPathRef path,
            String name, String returns, int offset) {
        var object = new PackedLocation(path.origin(), path.relativePath(),
                offset, offset / 10, 0, 40,
                new ObjectReference("app", name + "(integer)",
                        DbObjType.FUNCTION),
                "definition", null,
                ObjectLocation.LocationType.DEFINITION, null);
        return new PackedDefinition(MetaKind.FUNCTION, object, name, "",
                List.of(), List.of(), List.of(), returns, false,
                List.of(), false, List.of(), false, List.of(),
                null, null, null, null, null, null);
    }

    private static IndexPathRef path(String relativePath) {
        return new IndexPathRef(IndexPathOrigin.PROJECT, relativePath);
    }

    private static ProjectFileStamp stamp(IndexPathRef path) {
        return new ProjectFileStamp(path, 7L, 128L, 11L, new byte[32]);
    }

    private static void permitAddedFiles(IProject project, boolean permitted)
            throws Exception {
        IEclipsePreferences prefs = new ProjectScope(project)
                .getNode(UIConsts.PLUGIN_ID.THIS);
        // Written as a project value with the project override enabled, so the
        // verdict cannot depend on what the workspace happens to hold.
        prefs.putBoolean(PROJ_PREF.ENABLE_PROJ_PREF_ROOT, true);
        prefs.putBoolean(PREF.PROJECT_INDEX_INCREMENTAL_ADDED_FILES,
                permitted);
        prefs.flush();
    }

    private static IProject createProject(Path location,
            NullProgressMonitor monitor) throws Exception {
        var workspace = ResourcesPlugin.getWorkspace();
        String name = "pgck-added-files-"
                + location.getParent().getFileName();
        IProject project = workspace.getRoot().getProject(name);
        if (project.exists()) {
            project.delete(true, true, monitor);
        }
        IProjectDescription description =
                workspace.newProjectDescription(name);
        description.setLocationURI(location.toUri());
        project.create(description, monitor);
        project.open(monitor);
        return project;
    }

    private static void writeInitialProject(IProject project)
            throws Exception {
        Path root = project.getLocation().toFile().toPath();
        Path schema = Files.createDirectories(
                root.resolve("SCHEMA/app"));
        Path tables = Files.createDirectories(schema.resolve("TABLE"));
        Path functions = Files.createDirectories(
                schema.resolve("FUNCTION"));
        Files.writeString(schema.resolve("app.sql"),
                "CREATE SCHEMA app;\n");
        Files.writeString(tables.resolve("item.sql"),
                "CREATE TABLE app.item (id integer, payload integer);\n");
        Files.writeString(tables.resolve("other_item.sql"),
                "CREATE TABLE app.other_item (id integer);\n");
        Files.writeString(functions.resolve("pick.sql"),
                functionSql("pick", "item"));
        project.refreshLocal(IResource.DEPTH_INFINITE,
                new NullProgressMonitor());
    }

    /**
     * Two files with subjects the index does not hold, one of which references
     * an object it does - so the batch exercises resolution against the index
     * and not only against itself.
     */
    private static void addFreshFiles(IProject project,
            NullProgressMonitor monitor) throws Exception {
        write(project, ADDED_TABLE_PATH,
                "CREATE TABLE app.extra (id integer);\n", monitor);
        write(project, ADDED_FUNCTION_PATH,
                functionSql("extra_pick", "item"), monitor);
    }

    private static void replaceFunctionBody(IProject project,
            NullProgressMonitor monitor) throws Exception {
        IFile function = project.getFile(
                org.eclipse.core.runtime.Path.fromPortableString(
                        FUNCTION_PATH));
        try (var input = new ByteArrayInputStream(
                functionSql("pick", "other_item")
                        .getBytes(StandardCharsets.UTF_8))) {
            function.setContents(input, true, false, monitor);
        }
    }

    private static void write(IProject project, String relativePath,
            String content, NullProgressMonitor monitor) throws Exception {
        Path file = project.getLocation().toFile().toPath()
                .resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
    }

    private static String functionSql(String name, String table) {
        return """
                CREATE OR REPLACE FUNCTION app.%s()
                RETURNS integer
                LANGUAGE sql
                AS $function$
                SELECT id FROM app.%s LIMIT 1;
                $function$;
                """.formatted(name, table);
    }

    private static void cleanUp(IProject project,
            NullProgressMonitor monitor, PgDbParser... parsers)
            throws Exception {
        for (PgDbParser parser : parsers) {
            parser.clear();
        }
        if (project.exists()) {
            new ProjectScope(project)
                    .getNode(UIConsts.PLUGIN_ID.THIS).removeNode();
            PgDbParser.clean(project);
            project.delete(true, true, monitor);
        }
    }

    private static LongSupplier millisecondClock() {
        AtomicLong now = new AtomicLong();
        return () -> now.getAndAdd(1_000_000L);
    }

    private record SemanticSnapshot(List<String> definitions,
            List<String> references) { }

    private static SemanticSnapshot semanticSnapshot(PgDbParser parser) {
        return new SemanticSnapshot(
                parser.getAllObjDefinitions()
                        .map(PgDbParserAddedFilesPathTest::definitionKey)
                        .sorted()
                        .toList(),
                parser.getAllObjReferences()
                        .map(PgDbParserAddedFilesPathTest::referenceKey)
                        .sorted()
                        .toList());
    }

    private static String definitionKey(MetaStatement definition) {
        return definition.getClass().getName() + '|'
                + definition.getObjectReference();
    }

    private static String referenceKey(ObjectLocation location) {
        return location.getLocationType() + "|"
                + location.getObjectReference() + '|'
                + location.getAction() + '|'
                + location.getAlias();
    }
}
