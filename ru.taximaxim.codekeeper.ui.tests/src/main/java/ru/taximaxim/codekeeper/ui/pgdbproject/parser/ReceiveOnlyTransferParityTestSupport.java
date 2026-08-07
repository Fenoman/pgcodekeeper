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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.preference.IPreferenceStore;
import org.pgcodekeeper.core.api.ComparisonDepth;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.api.loader.ComparisonLoaderFactories;
import org.pgcodekeeper.core.database.api.loader.ILoaderFactory;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.api.schema.IStatement;
import org.pgcodekeeper.core.database.api.schema.ITable;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.base.loader.AbstractProjectLoader;
import org.pgcodekeeper.core.database.base.loader.LoaderFactories;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.ignorelist.IgnoreList;
import org.pgcodekeeper.core.model.difftree.ColumnVisibility;
import org.pgcodekeeper.core.model.difftree.HiddenObjects;
import org.pgcodekeeper.core.model.difftree.TreeElement;
import org.pgcodekeeper.core.model.difftree.TreeFlattener;
import org.pgcodekeeper.core.settings.CoreSettings;

import ru.taximaxim.codekeeper.ui.Activator;
import ru.taximaxim.codekeeper.ui.UIConsts.PREF;
import ru.taximaxim.codekeeper.ui.settings.ProjectIgnoreLists;
import ru.taximaxim.codekeeper.ui.settings.UISettings;
import ru.taximaxim.codekeeper.ui.utils.ProjectUtils;

/**
 * Drives the real "database into project" pipeline end to end - the same
 * {@link UIComparisonLoader} load, the same {@link TreeFlattener} selection
 * and the same {@link PgCodeKeeperApi#exportToProject} write that {@code
 * CommitDialog.JobProjectUpdater} uses - with the receive-only preference
 * either on or off, so a test can compare the two runs and answer whether the
 * preference changes what reaches the project's files.
 * <p>
 * {@code ProjectEditorDiffer.commit()} and {@code CommitDialog} themselves are
 * deliberately not driven here: both need a live {@code Shell}, which SWTBot
 * cannot supply in this environment (see the task brief). What is driven
 * instead is everything below the button that a click on it would run -
 * {@code CommitDialog.JobProjectUpdater.run()}'s own body is three lines
 * ({@code TreeFlattener}, then {@code PgCodeKeeperApi.exportToProject}) and
 * every one of them is reproduced here verbatim. The part this class cannot
 * exercise is therefore the dialog's own selection UI (which checkboxes a user
 * left ticked) and the version/library-override guards {@code
 * ProjectEditorDiffer.commit()} runs before opening it - none of which write a
 * byte of a project file. "Mark everything" ({@link TreeElement#setAllChecked()})
 * stands in for a user who left every checkbox ticked, which is the scenario
 * the task asks this to prove.
 */
public final class ReceiveOnlyTransferParityTestSupport {

    /**
     * A schema, a table with an index, an audit trigger and a dependent view -
     * six different {@link DbObjType}s in one small fixture, so "everything"
     * transferred is not just one kind of object. No ignore list is involved:
     * {@link #transferEverything} answers a question this fixture is not meant
     * to complicate.
     */
    private static final String TRANSFER_FIXTURE_SQL = """
            CREATE SCHEMA app;

            CREATE TABLE app.item (
                id integer NOT NULL,
                name text,
                secret text
            );

            CREATE INDEX item_name_idx ON app.item (name);

            CREATE FUNCTION app.audit_fn() RETURNS trigger
                LANGUAGE plpgsql
                AS $$ BEGIN RETURN NEW; END; $$;

            CREATE TRIGGER audit_check
                AFTER INSERT ON app.item
                FOR EACH ROW
                EXECUTE PROCEDURE app.audit_fn();

            CREATE VIEW app.item_view AS SELECT id, name FROM app.item;
            """;

    /**
     * A table with two triggers - one matching the ignore rule below, one not
     * - and two columns two different {@code type=COLUMN} rules name: one
     * selected by a view outside its own table, one nothing at all references.
     * <p>
     * Neither column is decoration. {@code secret} is named from outside its
     * own table, which is exactly the case {@code ColumnVisibility#neededColumns}
     * protects by keeping a column that a rule would otherwise drop - and the
     * one source of that protection ({@code IStatement#getReferencedColumns},
     * built from {@code getDependencies()}) is empty on a structurally loaded
     * model, which is why the receive-only mode cannot trust the rule that
     * names it and must turn the rule off instead of guessing. {@code
     * internal_flag} is the other half of the same question: nothing needs it,
     * so its rule genuinely hides it outside the mode - the byte a plain
     * transfer would drop - and inside the mode the rule that would have
     * hidden it does not apply either, so the column reaches the project
     * regardless of whether anything would have protected it. Between the two,
     * {@link #treeWithIgnoreList} can tell "the rule stopped hiding" from "the
     * rule was never going to hide this one anyway", which a fixture with only
     * a protected column could not.
     */
    private static final String IGNORE_LIST_FIXTURE_SQL = """
            CREATE SCHEMA app;

            CREATE TABLE app.item (
                id integer NOT NULL,
                secret text,
                internal_flag boolean
            );

            CREATE FUNCTION app.audit_fn() RETURNS trigger
                LANGUAGE plpgsql
                AS $$ BEGIN RETURN NEW; END; $$;

            CREATE TRIGGER audit_check
                AFTER INSERT ON app.item
                FOR EACH ROW
                EXECUTE PROCEDURE app.audit_fn();

            CREATE TRIGGER business_rule
                BEFORE INSERT ON app.item
                FOR EACH ROW
                EXECUTE PROCEDURE app.audit_fn();

            CREATE VIEW app.secret_view AS SELECT secret FROM app.item;
            """;

    /**
     * {@code audit_.*} is the pattern the task brief names, but the ignore
     * list grammar's bare {@code Identifier} token excludes {@code .} and
     * {@code *} - see {@code IgnoreList.g4} - so a literal, unquoted {@code
     * audit_.*} does not parse at all. This fixture has exactly one
     * audit-named trigger, so an exact {@code NONE} match names it just as
     * precisely as a pattern would, the same way {@code
     * DiffPaneHiddenChildrenTest} names its own audit triggers exactly rather
     * than by pattern.
     * <p>
     * {@code audit_check} is an object-level rule, decided by the model alone,
     * which the receive-only mode must keep honouring exactly as before - see
     * {@code ProjectIgnoreLists#dropColumnRulesIfStructural}'s javadoc for why
     * only the two {@code type=COLUMN} rules beside it are turned off for a
     * structural load.
     */
    private static final String IGNORE_LIST_RULES = """
            SHOW ALL
            HIDE NONE audit_check type=TRIGGER
            HIDE NONE secret type=COLUMN
            HIDE NONE internal_flag type=COLUMN
            """;

    private ReceiveOnlyTransferParityTestSupport() {
    }

    /**
     * Creates a project at {@code projectDir}, turns the receive-only
     * preference on or off, compares it against a fixture dump, marks the
     * whole diff tree selected and transfers it into the project exactly as
     * {@code CommitDialog.JobProjectUpdater} would.
     *
     * @param projectDir     where the project is created; must not exist yet
     * @param receiveOnly    whether the receive-only preference is turned on
     *                       for this project before the comparison runs
     * @param monitor        progress monitor for the Eclipse resource calls
     * @return every file the transfer wrote, keyed by its path relative to
     * {@code projectDir} with {@code /} separators, mapped to its exact bytes
     */
    public static Map<String, byte[]> transferEverything(Path projectDir,
            boolean receiveOnly, IProgressMonitor monitor) throws Exception {
        Path dumpFile = siblingDumpFile(projectDir);
        Files.createDirectories(projectDir);
        Files.writeString(dumpFile, TRANSFER_FIXTURE_SQL);

        IProject project = createProject(projectDir, monitor);
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        String key = PREF.PROJECT_UPDATED_FROM_DATABASE_ONLY;
        boolean wasDefault = store.isDefault(key);
        boolean oldValue = store.getBoolean(key);
        store.setValue(key, receiveOnly);
        try {
            var provider = new PgDatabaseProvider();
            var factories = comparisonFactories(provider, projectDir, dumpFile);
            var settings = new CoreSettings();
            ComparisonDepth depth = ProjectReceiveOnlyMode.depth(project);
            var models = UIComparisonLoader.loadModels(factories, settings, depth);
            // The real editor does this in ProjectEditorDiffer.setInput(), the
            // one place a freshly loaded comparison's settings become final
            // before anything reads their ignore list; this test cannot reach
            // that method (see the class javadoc) and calls the same static
            // helper it calls, with the same depth this load actually reached,
            // so the two paths retire a type=COLUMN rule identically instead
            // of by two copies of the same rule.
            ProjectIgnoreLists.dropColumnRulesIfStructural(depth, models.settings());
            var loaded = UIComparisonLoader.createResult(models, "project", "dump");
            loaded.diffTree().setAllChecked();

            IDatabase oldDb = loaded.oldLoader().getDatabase();
            IDatabase newDb = loaded.newLoader().getDatabase();
            List<TreeElement> checked = new TreeFlattener()
                    .onlySelected()
                    .onlyEdits(oldDb, newDb)
                    .flatten(loaded.diffTree());

            // The very ignore list the comparison hid by, threaded into a
            // fresh export settings instance - exactly what CommitDialog
            // passes to JobProjectUpdater, see its own javadoc.
            var exportSettings = UISettings.forExport(project, models.settings().getIgnoreList());
            PgCodeKeeperApi.exportToProject(provider, oldDb, newDb, checked,
                    projectDir, false, exportSettings);

            return readProjectFiles(projectDir);
        } finally {
            restorePreference(store, key, wasDefault, oldValue);
            cleanUp(project, monitor);
        }
    }

    /**
     * Creates a project at {@code projectDir} carrying a {@code
     * .pgcodekeeperignore} that hides an object whole and marks two columns,
     * turns the receive-only preference on or off, loads the comparison,
     * reports what the ignore list did to it - the same things {@code
     * DiffTableViewer}/{@code DiffPaneViewer} read to paint the comparison
     * pane's legend and markings - and then transfers the whole tree into the
     * project exactly as {@link #transferEverything} does, so a test can read
     * a column's fate at both ends of the same run: what the rules decided and
     * what actually reached a file.
     *
     * @param projectDir  where the project is created; must not exist yet
     * @param receiveOnly whether the receive-only preference is turned on for
     *                    this project before the comparison runs
     * @param monitor     progress monitor for the Eclipse resource calls
     * @return what the rules hid and marked in this comparison, and the files
     * the same comparison then wrote
     */
    public static IgnoreListSnapshot treeWithIgnoreList(Path projectDir,
            boolean receiveOnly, IProgressMonitor monitor) throws Exception {
        return treeWithIgnoreList(projectDir, receiveOnly, null, monitor);
    }

    /**
     * Same as {@link #treeWithIgnoreList(Path, boolean, IProgressMonitor)},
     * except the depth this one load reaches can be forced instead of derived
     * from the preference - reproducing {@code
     * ProjectEditorDiffer.reloadForScript()}, which leaves the receive-only
     * preference exactly as {@code receiveOnly} left it but still forces the
     * next {@code getChanges()} to {@link ComparisonDepth#FULL} ({@code
     * forceFullDepthOnce}). A caller asking for {@code receiveOnly=true,
     * forcedDepth=FULL} reproduces precisely that: the preference is on, and
     * this one load is not structural regardless.
     *
     * @param forcedDepth the depth to load at instead of {@link
     *                    ProjectReceiveOnlyMode#depth(IProject)}, or {@code
     *                    null} to use that natural answer
     */
    public static IgnoreListSnapshot treeWithIgnoreList(Path projectDir,
            boolean receiveOnly, ComparisonDepth forcedDepth, IProgressMonitor monitor) throws Exception {
        Path dumpFile = siblingDumpFile(projectDir);
        Files.createDirectories(projectDir);
        Files.writeString(dumpFile, IGNORE_LIST_FIXTURE_SQL);
        Files.writeString(projectDir.resolve(AbstractProjectLoader.IGNORE_FILE), IGNORE_LIST_RULES);

        IProject project = createProject(projectDir, monitor);
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        String key = PREF.PROJECT_UPDATED_FROM_DATABASE_ONLY;
        boolean wasDefault = store.isDefault(key);
        boolean oldValue = store.getBoolean(key);
        store.setValue(key, receiveOnly);
        try {
            var provider = new PgDatabaseProvider();
            var factories = comparisonFactories(provider, projectDir, dumpFile);
            var settings = new CoreSettings();
            // Mirrors ProjectEditorDiffer.getChanges(): 'ComparisonDepth depth
            // = forceFullDepth ? FULL : ProjectReceiveOnlyMode.depth(getProject())'.
            // The preference (receiveOnly, above) and the depth this one load
            // actually reaches are two different questions once reloadForScript()
            // is in play, and dropColumnRulesIfStructural below must answer to
            // the second, not the first.
            ComparisonDepth depth = forcedDepth != null ? forcedDepth : ProjectReceiveOnlyMode.depth(project);
            var models = UIComparisonLoader.loadModels(factories, settings, depth);
            // See the identical call in transferEverything: the real editor's
            // ProjectEditorDiffer.setInput() does this before the pane or the
            // table reads settings.getIgnoreList(), and this test reproduces
            // that call rather than the SWT method around it - including what
            // it returns, which the editor hands straight to the object table
            // so that the note beside the object count can say how many rules
            // were turned off.
            int retiredColumnRules = ProjectIgnoreLists.dropColumnRulesIfStructural(depth, models.settings());
            var loaded = UIComparisonLoader.createResult(models, "project", "dump");

            // Pass.TREE of HiddenObjects is recorded while DiffTree.create()
            // (called from createResult above) walks the tree; the rule here
            // carries no db= scope, so it is fully decided there and this
            // holder already carries the whole answer - see HiddenObjects'
            // own class javadoc on the two passes.
            HiddenObjects.Report report = models.settings().getHiddenObjects().report();
            Set<String> hiddenNames = report.rules().stream()
                    .flatMap(rule -> rule.examples().stream())
                    .collect(Collectors.toCollection(TreeSet::new));

            IDatabase oldDb = loaded.oldLoader().getDatabase();
            IDatabase newDb = loaded.newLoader().getDatabase();
            Set<String> markedNames = markedColumnNames(
                    models.settings().getIgnoreList(), oldDb, newDb);

            loaded.diffTree().setAllChecked();
            List<TreeElement> checked = new TreeFlattener()
                    .onlySelected()
                    .onlyEdits(oldDb, newDb)
                    .flatten(loaded.diffTree());

            // The very ignore list that produced hiddenNames/markedNames
            // above, threaded into a fresh export settings instance - exactly
            // what CommitDialog passes to JobProjectUpdater - so the file this
            // writes answers to the same rules the marking already did, not a
            // second reading of them that could disagree.
            var exportSettings = UISettings.forExport(project, models.settings().getIgnoreList());
            PgCodeKeeperApi.exportToProject(provider, oldDb, newDb, checked,
                    projectDir, false, exportSettings);

            return new IgnoreListSnapshot(hiddenNames, report.total(), report.idleRules(),
                    retiredColumnRules, markedNames, readProjectFiles(projectDir));
        } finally {
            restorePreference(store, key, wasDefault, oldValue);
            cleanUp(project, monitor);
        }
    }

    /**
     * What one comparison's ignore list did to it: the objects it removed from
     * the difference tree whole, how many it removed in total, the columns it
     * marked - kept or dropped - without removing the table they belong to,
     * and the files the same comparison wrote once transferred.
     *
     * @param hiddenNames qualified names of objects the rules hid whole, from
     *                     {@link HiddenObjects.Report#rules()}'s own examples
     * @param hiddenCount how many objects the rules hid whole in total, {@link
     *                     HiddenObjects.Report#total()}
     * @param idleRules   how many rules of the list could have hidden an object
     *                     and did not, {@link HiddenObjects.Report#idleRules()};
     *                     a rule the mode turned off must not be counted here,
     *                     or the note would call it idle and turned off at once
     * @param retiredColumnRules how many rules lost their {@code type=COLUMN}
     *                     facet for this comparison, exactly as {@code
     *                     ProjectIgnoreLists#dropColumnRulesIfStructural}
     *                     reported it to the caller that has to say so on the
     *                     screen
     * @param markedNames every column a {@code type=COLUMN} rule named, as
     *                     {@code "<qualified table>.<column>:<mark>"}, so that
     *                     two comparisons that mark the same column
     *                     differently - kept in one, dropped in the other -
     *                     disagree here even though the column's bare name is
     *                     identical in both
     * @param files       every file the transfer wrote, keyed by its path
     *                     relative to the project directory with {@code /}
     *                     separators, mapped to its exact bytes - the same
     *                     shape {@link #transferEverything} returns, so a test
     *                     can read a column's presence or absence straight out
     *                     of the written file instead of trusting the marking
     *                     alone
     */
    public record IgnoreListSnapshot(Set<String> hiddenNames, int hiddenCount,
            int idleRules, int retiredColumnRules, Set<String> markedNames,
            Map<String, byte[]> files) {
    }

    /**
     * Every column a {@code type=COLUMN} rule names, on either side of the
     * comparison, paired with what {@link ColumnVisibility} decided to do
     * about it - {@code MANAGED} is never reported, since a rule that names a
     * column and does nothing to it is not why the column is on this list.
     * <p>
     * Mirrors {@code DiffPaneViewer.marksOf}: bind both states of a table
     * together before asking, so a column kept alive by an object that exists
     * on one side only is not asked about the side that lacks it.
     */
    private static Set<String> markedColumnNames(IgnoreList ignoreList, IDatabase oldDb, IDatabase newDb) {
        ColumnVisibility visibility = ColumnVisibility.of(ignoreList);
        if (!visibility.hidesAnything()) {
            return Set.of();
        }

        Set<String> marked = new TreeSet<>();
        Set<String> visitedTables = new TreeSet<>();
        Stream.concat(tablesOf(oldDb), tablesOf(newDb)).forEach(table -> {
            String qname = table.getQualifiedName();
            if (!visitedTables.add(qname)) {
                return;
            }
            ITable inOld = findTable(oldDb, table);
            ITable inNew = findTable(newDb, table);
            ColumnVisibility bound = visibility.forPair(inOld, inNew);
            for (ITable side : new ITable[] { inOld, inNew }) {
                if (side != null) {
                    bound.marksIn(side).forEach((column, mark) ->
                            marked.add(qname + '.' + column + ':' + mark));
                }
            }
        });
        return marked;
    }

    private static Stream<ITable> tablesOf(IDatabase db) {
        return db == null ? Stream.empty()
                : db.getDescendants().filter(ITable.class::isInstance).map(ITable.class::cast);
    }

    private static ITable findTable(IDatabase db, ITable like) {
        if (db == null) {
            return null;
        }
        IStatement parent = like.getParent();
        IStatement found = db.getStatement(
                new ObjectReference(parent.getName(), like.getName(), DbObjType.TABLE));
        return found instanceof ITable table ? table : null;
    }

    /**
     * The project side loads through {@link LoaderFactories#project}, not a
     * bare lambda: only that adapter's {@code contributeCommonConfiguration}
     * reads {@code .pgcodekeeperignore} off {@code projectDir} before either
     * side is loaded - a bare {@link LoaderFactories#of} leaves it at the
     * default no-op, see {@code ILoaderFactory} itself.
     */
    private static ComparisonLoaderFactories comparisonFactories(PgDatabaseProvider provider,
            Path projectDir, Path dumpFile) {
        ILoaderFactory oldFactory = LoaderFactories.project(projectDir,
                sideSettings -> provider.getProjectLoader(projectDir, sideSettings));
        ILoaderFactory newFactory = LoaderFactories.of(
                sideSettings -> provider.getDumpLoader(dumpFile, sideSettings));
        return new ComparisonLoaderFactories(oldFactory, newFactory);
    }

    private static Path siblingDumpFile(Path projectDir) {
        return projectDir.resolveSibling(projectDir.getFileName() + "-dump.sql");
    }

    private static Map<String, byte[]> readProjectFiles(Path projectDir) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(projectDir)) {
            for (Path path : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                String relative = projectDir.relativize(path).toString().replace('\\', '/');
                files.put(relative, Files.readAllBytes(path));
            }
        }
        return files;
    }

    /**
     * Turns the receive-only preference on or off through the workspace
     * default rather than a per-project override, so that the mode being
     * tested never itself writes into the project directory - neither run
     * leaves a {@code .settings/} file behind that would make the two
     * directories differ for a reason that has nothing to do with what the
     * transfer wrote. {@link ProjectReceiveOnlyMode#isEnabled} resolves a
     * workspace default exactly as it resolves a project override, see that
     * class's own javadoc, so this reaches the same code path.
     */
    private static void restorePreference(IPreferenceStore store, String key,
            boolean wasDefault, boolean oldValue) {
        if (wasDefault) {
            store.setToDefault(key);
        } else {
            store.setValue(key, oldValue);
        }
    }

    private static IProject createProject(Path location, IProgressMonitor monitor) throws Exception {
        var workspace = ResourcesPlugin.getWorkspace();
        // Named after the shared @TempDir root rather than "ordinary"/"mode"
        // themselves: the exported .project file records the project's own
        // name, and the ordinary and receive-only runs must end up with the
        // byte-identical file, not one that merely differs by a name this
        // test chose.
        Path nameRoot = location.getParent() != null ? location.getParent() : location;
        String name = "pgck-receive-only-parity-" + nameRoot.getFileName();
        IProject project = workspace.getRoot().getProject(name);
        if (project.exists()) {
            project.delete(true, true, monitor);
        }
        IProjectDescription description = workspace.newProjectDescription(name);
        description.setLocationURI(location.toUri());
        project.create(description, monitor);
        project.open(monitor);
        IProjectDescription open = project.getDescription();
        open.setNatureIds(new String[] { ProjectUtils.NATURE_ID });
        project.setDescription(open, monitor);
        return project;
    }

    private static void cleanUp(IProject project, IProgressMonitor monitor) throws Exception {
        if (project != null && project.exists()) {
            project.delete(true, true, monitor);
        }
    }
}
