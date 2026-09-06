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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.api.loader.ComparisonLoaderFactories;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.api.schema.IStatement;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.base.loader.LoaderFactories;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.library.Library;
import org.pgcodekeeper.core.library.LibraryXmlStore;
import org.pgcodekeeper.core.model.difftree.TreeFlattener;

import ru.taximaxim.codekeeper.ui.DatabaseType;
import ru.taximaxim.codekeeper.ui.UIConsts.PREF;
import ru.taximaxim.codekeeper.ui.settings.UISettings;

class GetChangesReferencePolicyTest {

    private static final String LIBRARY_PATH = "../library.sql";

    private static final List<String> LIBRARY_OBJECTS = List.of(
            "FUNCTION|lib.caption_count()|" + LIBRARY_PATH,
            "SCHEMA|lib|" + LIBRARY_PATH,
            "TABLE|lib.dictionary|" + LIBRARY_PATH,
            "VIEW|lib.captions|" + LIBRARY_PATH);

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void comparisonDropsReferenceLocationsButPreservesScriptsAndExport(
            boolean parallelLoad, @TempDir Path temp) throws Exception {
        Path project = temp.resolve("project");
        writeProject(project);
        Path remote = temp.resolve("remote.sql");
        Files.writeString(remote, """
                CREATE SCHEMA app;
                CREATE TABLE app.item (id integer, remote_value text);
                CREATE VIEW app.current_items AS SELECT id FROM app.item;
                """);
        var provider = new PgDatabaseProvider();
        var factories = new ComparisonLoaderFactories(
                LoaderFactories.project(project,
                        settings -> provider.getProjectLoader(project, settings)),
                LoaderFactories.of(settings -> provider.getDumpLoader(remote, settings)));
        Map<String, Object> prefs = Map.of(
                PREF.PARALLEL_LOADING, parallelLoad,
                PREF.PG_CATALOG_CACHE_ROWS, false,
                PREF.PG_ROUTINE_BODY_SKIP_MATCHED_ANALYSIS, false,
                PREF.ENABLE_BODY_DEPENDENCIES, true);
        var optimizedSettings = UISettings.forGetChanges(null, prefs, DatabaseType.PG);
        var baselineSettings = new UISettings(null, prefs, DatabaseType.PG);
        baselineSettings.setMigrationTargetOldSide(true);
        baselineSettings.setPgRoutineBodyHashFirst(parallelLoad);
        baselineSettings.setParserExecutionPolicy(optimizedSettings.getParserExecutionPolicy());

        var baseline = UIComparisonLoader.loadModels(factories, baselineSettings);
        var optimized = UIComparisonLoader.loadModels(factories, optimizedSettings);

        assertTrue(referenceCount(baseline.oldDatabase()) > 0);
        assertTrue(referenceCount(baseline.newDatabase()) > 0);
        assertEquals(0, referenceCount(optimized.oldDatabase()));
        assertEquals(0, referenceCount(optimized.newDatabase()));
        System.out.printf("Get Changes reference locations (parallel=%s): project %d -> %d, remote %d -> %d%n",
                parallelLoad, referenceCount(baseline.oldDatabase()), referenceCount(optimized.oldDatabase()),
                referenceCount(baseline.newDatabase()), referenceCount(optimized.newDatabase()));
        assertEquals(dependencies(baseline.oldDatabase()), dependencies(optimized.oldDatabase()));
        assertEquals(dependencies(baseline.newDatabase()), dependencies(optimized.newDatabase()));
        var item = new ObjectReference("app", "item", DbObjType.TABLE);
        assertTrue(optimized.oldDatabase().getStatement(
                new ObjectReference("app", "count_items()", DbObjType.FUNCTION))
                .getDependencies().contains(item), "routine-body dependencies must survive");

        var baselineResult = UIComparisonLoader.createResult(baseline, "project", "remote");
        var optimizedResult = UIComparisonLoader.createResult(optimized, "project", "remote");
        assertEquals(diffSnapshot(baselineResult), diffSnapshot(optimizedResult));
        var monitor = new NullProgressMonitor();
        String script = MigrationScriptParityTestSupport.script(optimizedResult, monitor);
        assertEquals(MigrationScriptParityTestSupport.script(baselineResult, monitor), script);
        int baseView = script.indexOf("CREATE VIEW app.z_base");
        int dependentView = script.indexOf("CREATE VIEW app.a_report");
        assertTrue(baseView >= 0 && dependentView > baseView,
                () -> "the migration must create views in dependency order: " + script);
        assertTrue(script.contains("CREATE OR REPLACE FUNCTION app.count_items()"));

        Path baselineExport = temp.resolve("baseline-export");
        Path optimizedExport = temp.resolve("optimized-export");
        writeProject(baselineExport);
        writeProject(optimizedExport);
        export(provider, baselineResult, baselineExport);
        export(provider, optimizedResult, optimizedExport);
        assertEquals(projectFiles(baselineExport), projectFiles(optimizedExport));
        assertTrue(Files.readString(optimizedExport.resolve("SCHEMA/app/TABLE/item.sql"))
                .contains("remote_value text"));
        assertFalse(Files.exists(optimizedExport.resolve("SCHEMA/app/VIEW/a_report.sql")));

        var indexSettings = UISettings.forProjectIndex(null);
        assertTrue(referenceCount(provider.getProjectLoader(project, indexSettings)
                .loadAndAnalyze()) > 0, "the independent editor index must retain navigation locations");
    }

    /**
     * A project that merges a library keeps every merged object, override and
     * dependency when the comparison stops collecting reference locations.
     * <p>
     * The merge reads the library's own reference map - {@code IDatabase.addLib}
     * copies it into the project model - so a policy that empties that map is
     * only safe if nothing about the merge is carried by it. The library below
     * contributes a schema of its own, a table, a view and a routine that no
     * project file declares, and redefines one object the project already owns,
     * which is what makes the merge produce objects, analysis launchers and an
     * override all at once.
     */
    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void libraryMergeSurvivesWithoutReferenceLocations(
            boolean parallelLoad, @TempDir Path temp) throws Exception {
        Path project = temp.resolve("project");
        writeProject(project);
        writeLibrary(temp, project);
        Path remote = temp.resolve("remote.sql");
        Files.writeString(remote, """
                CREATE SCHEMA app;
                CREATE TABLE app.item (id integer, remote_value text);
                CREATE VIEW app.current_items AS SELECT id FROM app.item;
                """);
        var provider = new PgDatabaseProvider();
        var factories = new ComparisonLoaderFactories(
                LoaderFactories.project(project,
                        settings -> provider.getProjectLoader(project, settings)),
                LoaderFactories.of(settings -> provider.getDumpLoader(remote, settings)));
        Map<String, Object> prefs = Map.of(
                PREF.PARALLEL_LOADING, parallelLoad,
                PREF.PG_CATALOG_CACHE_ROWS, false,
                PREF.PG_ROUTINE_BODY_SKIP_MATCHED_ANALYSIS, false,
                PREF.ENABLE_BODY_DEPENDENCIES, true);
        var optimizedSettings = UISettings.forGetChanges(null, prefs, DatabaseType.PG);
        var baselineSettings = new UISettings(null, prefs, DatabaseType.PG);
        baselineSettings.setMigrationTargetOldSide(true);
        baselineSettings.setPgRoutineBodyHashFirst(parallelLoad);
        baselineSettings.setParserExecutionPolicy(optimizedSettings.getParserExecutionPolicy());

        var baseline = UIComparisonLoader.loadModels(factories, baselineSettings);
        var optimized = UIComparisonLoader.loadModels(factories, optimizedSettings);

        assertTrue(referenceCount(baseline.oldDatabase()) > 0);
        assertEquals(0, referenceCount(optimized.oldDatabase()));
        assertEquals(objects(baseline.oldDatabase()), objects(optimized.oldDatabase()));
        assertEquals(LIBRARY_OBJECTS, libraryObjects(baseline.oldDatabase()));
        assertEquals(LIBRARY_OBJECTS, libraryObjects(optimized.oldDatabase()));
        assertEquals(overrides(baseline.oldDatabase()), overrides(optimized.oldDatabase()));
        assertFalse(overrides(optimized.oldDatabase()).isEmpty(),
                "the project must override the library definition it redeclares");
        assertEquals(dependencies(baseline.oldDatabase()), dependencies(optimized.oldDatabase()));
        assertTrue(optimized.oldDatabase().getStatement(
                new ObjectReference("lib", "caption_count()", DbObjType.FUNCTION))
                .getDependencies().contains(new ObjectReference("lib", "dictionary", DbObjType.TABLE)),
                "library routine-body dependencies must survive");

        var baselineResult = UIComparisonLoader.createResult(baseline, "project", "remote");
        var optimizedResult = UIComparisonLoader.createResult(optimized, "project", "remote");
        assertEquals(diffSnapshot(baselineResult), diffSnapshot(optimizedResult));
        var monitor = new NullProgressMonitor();
        String script = MigrationScriptParityTestSupport.script(optimizedResult, monitor);
        assertEquals(MigrationScriptParityTestSupport.script(baselineResult, monitor), script);
        assertTrue(script.contains("CREATE TABLE lib.dictionary"),
                () -> "the migration must carry the library table: " + script);
        assertTrue(script.contains("caption text"),
                () -> "the migration must carry the library table columns: " + script);
        assertTrue(script.contains("CREATE OR REPLACE FUNCTION lib.caption_count()"),
                () -> "the migration must carry the library routine: " + script);
        assertTrue(script.contains("CREATE VIEW lib.captions"),
                () -> "the migration must carry the library view: " + script);
        assertFalse(script.contains("numeric(38,0)"),
                () -> "the project definition must win over the library one: " + script);
    }

    private static void writeLibrary(Path temp, Path project) throws IOException {
        Files.writeString(temp.resolve("library.sql"), """
                CREATE SCHEMA app;
                CREATE SCHEMA lib;
                CREATE TABLE lib.dictionary (id integer, caption text);
                CREATE VIEW lib.captions AS SELECT caption FROM lib.dictionary;
                CREATE OR REPLACE FUNCTION lib.caption_count() RETURNS bigint
                    LANGUAGE sql AS $$SELECT count(*) FROM lib.dictionary$$;
                CREATE TABLE app.item (id numeric(38,0));
                """);
        new LibraryXmlStore(project.resolve(LibraryXmlStore.FILE_NAME)).writeDependencies(
                List.of(new Library("", LIBRARY_PATH, false, "")), false);
    }

    private static List<String> objects(IDatabase database) {
        return database.getDescendants()
                .map(statement -> statement.getStatementType() + "|"
                        + statement.getQualifiedName() + "|" + statement.getLibName())
                .sorted().toList();
    }

    private static List<String> libraryObjects(IDatabase database) {
        return database.getDescendants().filter(IStatement::isLib)
                .map(statement -> statement.getStatementType() + "|"
                        + statement.getQualifiedName() + "|" + statement.getLibName())
                .sorted().toList();
    }

    private static List<String> overrides(IDatabase database) {
        return database.getOverrides().stream()
                .map(override -> override.getType() + "|" + override.getName()
                        + "|" + override.getNewPath() + "|" + override.getOldPath())
                .sorted().toList();
    }

    private static void export(PgDatabaseProvider provider, UIComparisonLoader.Result result,
            Path project) throws Exception {
        result.diffTree().setAllChecked();
        IDatabase oldDb = result.oldLoader().getDatabase();
        IDatabase newDb = result.newLoader().getDatabase();
        var selected = new TreeFlattener().onlySelected().onlyEdits(oldDb, newDb)
                .flatten(result.diffTree());
        PgCodeKeeperApi.exportToProject(provider, oldDb, newDb, selected, project, false,
                UISettings.forExport(null, result.settings().getIgnoreList()));
    }

    private static List<String> diffSnapshot(UIComparisonLoader.Result result) {
        return new TreeFlattener().onlyEdits(result.oldLoader().getDatabase(),
                result.newLoader().getDatabase()).flatten(result.diffTree()).stream()
                .map(element -> element.getType() + "|" + element.getQualifiedName()
                        + "|" + element.getSide())
                .sorted().toList();
    }

    private static long referenceCount(IDatabase database) {
        return database.getObjReferences().values().stream().mapToLong(Collection::size).sum();
    }

    private static Map<String, Set<ObjectReference>> dependencies(IDatabase database) {
        var result = new TreeMap<String, Set<ObjectReference>>();
        database.getDescendants().forEach(statement -> result.put(
                statement.getStatementType() + "|" + statement.getQualifiedName(),
                Set.copyOf(statement.getDependencies())));
        return result;
    }

    private static Map<String, String> projectFiles(Path project) throws IOException {
        var result = new TreeMap<String, String>();
        try (var files = Files.walk(project)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                result.put(project.relativize(file).toString(), Files.readString(file));
            }
        }
        return result;
    }

    private static void writeProject(Path project) throws IOException {
        Map<String, String> files = Map.of(
                "SCHEMA/app/app.sql", "CREATE SCHEMA app;\n",
                "SCHEMA/app/TABLE/item.sql", "CREATE TABLE app.item (id integer);\n",
                "SCHEMA/app/VIEW/current_items.sql",
                "CREATE VIEW app.current_items AS SELECT id FROM app.item;\n",
                "SCHEMA/app/VIEW/a_report.sql",
                "CREATE VIEW app.a_report AS SELECT id FROM app.z_base;\n",
                "SCHEMA/app/VIEW/z_base.sql", "CREATE VIEW app.z_base AS SELECT id FROM app.item;\n",
                "SCHEMA/app/FUNCTION/count_items.sql", """
                CREATE OR REPLACE FUNCTION app.count_items() RETURNS bigint
                    LANGUAGE sql AS $$SELECT count(*) FROM app.item$$;
                """);
        for (var entry : files.entrySet()) {
            Path file = project.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue());
        }
    }
}
