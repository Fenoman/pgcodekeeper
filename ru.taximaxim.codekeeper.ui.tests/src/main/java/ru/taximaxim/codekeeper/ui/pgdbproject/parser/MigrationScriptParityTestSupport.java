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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.stream.Stream;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.pgcodekeeper.core.database.api.loader.ComparisonLoaderFactories;
import org.pgcodekeeper.core.database.api.loader.ILoaderFactory;
import org.pgcodekeeper.core.database.base.loader.LoaderFactories;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.model.difftree.TreeElement;
import org.pgcodekeeper.core.settings.CoreSettings;

import ru.taximaxim.codekeeper.ui.DatabaseType;
import ru.taximaxim.codekeeper.ui.differ.Differ;
import ru.taximaxim.codekeeper.ui.libraries.LibraryUtils;
import ru.taximaxim.codekeeper.ui.localizations.Messages;
import ru.taximaxim.codekeeper.ui.utils.UIMonitor;

/**
 * A project, a database dump to migrate it onto, and the two ways of turning
 * that pair into a migration script that {@link MigrationScriptParityTest}
 * holds against each other.
 * <p>
 * <b>The oracle is an independent path, not a second way into the same cache.</b>
 * {@link #oracleScript} loads both comparison sides through {@link
 * UIComparisonLoader} directly, without {@link ReusableProjectComparison}
 * existing at all - no model is retained, no analysis is replayed, the project
 * is parsed and analyzed from its files every single time. {@link
 * #pipelineScript} runs the production pipeline. The two therefore differ in
 * exactly one thing: whether anything was reused. This mirrors {@code
 * PgPipelineParityTest} in Core, which compares a bounded against an unbounded
 * run of the same fixture and asserts {@code assertEquals(expected.sql(),
 * actual.sql())}.
 * <p>
 * <b>The script is built by the production class, wired verbatim.</b> {@link
 * #script} reproduces {@code ProjectEditorDiffer.diff()} line for line -
 * {@code dbProject} is {@code result.oldLoader()} and {@code dbRemote} is
 * {@code result.newLoader()} as {@code setInput} assigns them, and the {@link
 * Differ} is constructed with {@code (dbRemote.getDatabase(),
 * dbProject.getDatabase(), diffTree.getRevertedCopy(), ...)} as that method
 * constructs it. It is called identically for both sides of every parity
 * assertion, so it can only ever cancel out; what it cannot do is let a
 * reimplemented script builder answer for the real one. The two constructor
 * arguments passed as {@code null} - the project and the one-time preferences -
 * are accepted and discarded by {@code Differ} itself, which stores neither.
 * <p>
 * What is not driven: {@code ProjectEditorDiffer.diff()} needs a live {@code
 * Shell} for its checked-element warning and its version guard, and SWTBot
 * cannot supply one headless in this environment - {@code EditorTest} and
 * {@code WizardsTest}, the only tests that press this button, do not run here.
 * The tree is fully checked below, which is the state a user leaves by ticking
 * everything; which subset of boxes a user actually left ticked is a claim no
 * test makes yet.
 */
final class MigrationScriptParityTestSupport {

    /** Where the fixture puts the table every scenario migrates. */
    static final String TABLE_PATH = "SCHEMA/app/TABLE/item.sql"; //$NON-NLS-1$
    /** Where the fixture puts the view that reads that table. */
    static final String VIEW_PATH = "SCHEMA/app/VIEW/pick.sql"; //$NON-NLS-1$

    /** How long a persisted analysis is waited for before giving up. */
    private static final long STORE_TIMEOUT_MS = 30_000;

    private MigrationScriptParityTestSupport() {
    }

    /**
     * One comparison the production pipeline produced, with the two facts that
     * say which of its branches did it.
     *
     * @param script           the migration script built from that comparison
     * @param reused           whether a retained analyzed model served this run
     * @param analysisReplayed whether a stored analysis result served this run
     */
    record PipelineRun(String script, boolean reused, boolean analysisReplayed) {
    }

    /**
     * A project with an analyzed background index and a dump to migrate it onto.
     */
    static final class Fixture implements AutoCloseable {

        private final IProject project;
        private final Path projectRoot;
        private final Path remote;
        private final PgDatabaseProvider provider = new PgDatabaseProvider();
        private final IProgressMonitor monitor;

        private Fixture(IProject project, Path remote, IProgressMonitor monitor) {
            this.project = project;
            this.projectRoot = project.getLocation().toFile().toPath();
            this.remote = remote;
            this.monitor = monitor;
        }

        /**
         * Rewrites one project file and brings the workspace and the background
         * index back in step with it, exactly as a save and a build would.
         */
        void edit(String relativePath, String content) throws Exception {
            Files.writeString(projectRoot.resolve(relativePath), content);
            reindex();
        }

        private void reindex() throws Exception {
            project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
            PgDbParser parser = PgDbParser.getParserForBuilder(project,
                    new int[] { IncrementalProjectBuilder.FULL_BUILD });
            parser.prepareProjectIndex(project, monitor)
                    .update().commit(project.getName(), monitor);
        }

        /**
         * Runs one comparison through the production pipeline and builds the
         * script the migration button would build from it.
         * <p>
         * The comparison is published exactly as {@code
         * ProjectEditorDiffer.publishComparison} publishes it, because it is
         * publication that retains the analyzed model for the next run and
         * writes this run's analysis to the persistent store. A prepared
         * comparison that is never published leaves nothing behind for either
         * warm branch to find.
         */
        PipelineRun pipelineScript(ReusableProjectComparison reusable)
                throws Exception {
            var prepared = reusable.load(project, DatabaseType.PG, provider,
                    projectRoot, remoteFactory(), scriptSafeSettings(monitor),
                    "project", "remote", monitor, false) //$NON-NLS-1$ //$NON-NLS-2$
                    .orElseThrow();
            String script = script(prepared.result(), monitor);
            var run = new PipelineRun(script, prepared.reused(),
                    prepared.analysisReplayed());
            var lease = prepared.publish().displayLease();
            if (lease.isPresent()) {
                try (var held = lease.orElseThrow()) {
                    // holding and releasing the lease is what retains the
                    // analyzed model for the next comparison
                }
            }
            return run;
        }

        /**
         * Builds the same script without {@link ReusableProjectComparison} in
         * the picture at all: the project is parsed and analyzed from its files,
         * nothing is retained and nothing is replayed.
         */
        String oracleScript() throws Exception {
            var factories = new ComparisonLoaderFactories(
                    LoaderFactories.project(projectRoot,
                            sideSettings -> provider.getProjectLoader(
                                    projectRoot, sideSettings,
                                    Collections.emptyList(),
                                    Collections.emptyList(),
                                    Collections.emptyList(),
                                    LibraryUtils.META_PATH)),
                    remoteFactory());
            var models = UIComparisonLoader.loadModels(factories,
                    scriptSafeSettings(monitor));
            return script(UIComparisonLoader.createResult(models,
                    "project", "remote"), monitor); //$NON-NLS-1$ //$NON-NLS-2$
        }

        private ILoaderFactory remoteFactory() {
            return LoaderFactories.of(
                    settings -> provider.getDumpLoader(remote, settings));
        }

        /**
         * Waits for the analysis this run captured to actually reach the
         * persistent store.
         * <p>
         * {@code publish()} does not write it - it schedules a system job that
         * encodes and writes it, deliberately, because the publication happens
         * on the UI thread and encoding tens of megabytes there would freeze
         * the editor (see {@code ReusableProjectComparison.persistence}). A
         * restart that follows a publication too closely therefore finds
         * nothing, which is correct behaviour and not what the replay scenario
         * is about. The job is already scheduled by the time {@link
         * #pipelineScript} returns, so its disappearance from the manager means
         * it finished.
         */
        void awaitPersistedAnalysis() throws InterruptedException {
            long deadline = System.currentTimeMillis() + STORE_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                boolean pending = Stream.of(Job.getJobManager().find(null))
                        .anyMatch(job -> Messages
                                .ReusableProjectComparison_store_job
                                .equals(job.getName()));
                if (!pending) {
                    return;
                }
                Thread.sleep(20);
            }
            throw new AssertionError(
                    "the analysis store job did not finish within " //$NON-NLS-1$
                            + STORE_TIMEOUT_MS + " ms"); //$NON-NLS-1$
        }

        @Override
        public void close() throws Exception {
            PgDbParser.removeProject(project);
            if (project.exists()) {
                project.delete(true, true, monitor);
            }
        }
    }

    /**
     * Creates the fixture: a project holding a table with a privilege, a view
     * reading that table and a function reading it too, against a dump that
     * knows only a bare table.
     * <p>
     * Every element is there for a reason a parity assertion can name. The
     * missing column makes the migration alter a table; the view makes it
     * create an object that cannot be created before that column exists, which
     * is a dependency edge only a carried analysis can order; the {@code GRANT}
     * makes the script carry a privilege, which is what a comparison run under
     * different privilege settings would silently drop; the function body reads
     * the same table, so a run that lost routine-body analysis would differ
     * too.
     */
    static Fixture create(Path root, String name, IProgressMonitor monitor)
            throws Exception {
        IProject project = createProject(root.resolve(name), name, monitor);
        Path remote = root.resolve(name + "-remote.sql"); //$NON-NLS-1$
        Files.writeString(remote, """
                CREATE SCHEMA app;
                CREATE TABLE app.item (id bigint);
                """);
        Path projectRoot = project.getLocation().toFile().toPath();
        Path schema = Files.createDirectories(projectRoot.resolve("SCHEMA/app")); //$NON-NLS-1$
        Files.createDirectories(schema.resolve("TABLE")); //$NON-NLS-1$
        Files.createDirectories(schema.resolve("VIEW")); //$NON-NLS-1$
        Files.createDirectories(schema.resolve("FUNCTION")); //$NON-NLS-1$
        Files.writeString(schema.resolve("app.sql"), "CREATE SCHEMA app;\n"); //$NON-NLS-1$ //$NON-NLS-2$
        Files.writeString(projectRoot.resolve(TABLE_PATH), TABLE_SQL);
        Files.writeString(projectRoot.resolve(VIEW_PATH), VIEW_SQL);
        Files.writeString(schema.resolve("FUNCTION/count_items.sql"), //$NON-NLS-1$
                """
                CREATE OR REPLACE FUNCTION app.count_items() RETURNS bigint
                    LANGUAGE sql AS $$SELECT count(*) FROM app.item$$;
                """);
        var fixture = new Fixture(project, remote, monitor);
        fixture.reindex();
        return fixture;
    }

    /** The fixture's table as {@link #create} writes it. */
    static final String TABLE_SQL = """
            CREATE TABLE app.item (
                id bigint,
                code text
            );

            GRANT SELECT ON TABLE app.item TO PUBLIC;
            """;

    /** The fixture's view as {@link #create} writes it. */
    static final String VIEW_SQL = """
            CREATE VIEW app.pick AS
                SELECT id, code FROM app.item;
            """;

    /**
     * Settings a migration script can actually be generated under.
     * <p>
     * Not a detail: {@code ProjectEditorDiffer.diff()} refuses to build a script
     * at all when {@code UIComparisonLoader.isMigrationGenerationSafe} says no,
     * and it says no exactly when routine bodies are both hashed first and
     * skipped when matched - which is what {@code ReusableProjectComparisonTest}
     * sets. Asserting parity under those settings would assert it in the one
     * configuration where the behaviour under test does not exist.
     */
    static CoreSettings scriptSafeSettings(IProgressMonitor monitor) {
        var settings = new CoreSettings();
        settings.setPgRoutineBodyHashFirst(true);
        settings.setPgRoutineBodySkipMatchedAnalysis(false);
        settings.setTimeZone("UTC"); //$NON-NLS-1$
        settings.setMonitor(new UIMonitor(monitor));
        return settings;
    }

    /**
     * Builds a migration script from a loaded comparison exactly as {@code
     * ProjectEditorDiffer.diff()} does - see this class's javadoc for the
     * correspondence, argument by argument.
     */
    static String script(UIComparisonLoader.Result loaded,
            IProgressMonitor monitor) throws Exception {
        // setInput(loaded.oldLoader(), loaded.newLoader(), ...): the project is
        // the OLD side of the comparison and the database is the NEW one, and
        // diff() then reverts them, because a migration is applied to the
        // database and the project is the state it must reach.
        var dbProject = loaded.oldLoader();
        var dbRemote = loaded.newLoader();
        TreeElement diffTree = loaded.diffTree();
        diffTree.setAllChecked();
        var differ = new Differ(dbRemote.getDatabase(), dbProject.getDatabase(),
                diffTree.getRevertedCopy(), "UTC", null, null, //$NON-NLS-1$
                DatabaseType.PG, loaded.settings());
        differ.run(monitor);
        return differ.getDiffDirect();
    }

    private static IProject createProject(Path location, String name,
            IProgressMonitor monitor) throws Exception {
        var workspace = ResourcesPlugin.getWorkspace();
        String projectName = "pgck-script-parity-" + name; //$NON-NLS-1$
        IProject project = workspace.getRoot().getProject(projectName);
        if (project.exists()) {
            project.delete(true, true, monitor);
        }
        IProjectDescription description =
                workspace.newProjectDescription(projectName);
        description.setLocationURI(location.toUri());
        project.create(description, monitor);
        project.open(monitor);
        return project;
    }
}
