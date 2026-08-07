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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.jface.preference.IPreferenceStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgcodekeeper.core.api.ComparisonDepth;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.api.loader.ComparisonLoaderFactories;
import org.pgcodekeeper.core.database.api.schema.IStatement;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.settings.CoreSettings;

import ru.taximaxim.codekeeper.ui.Activator;
import ru.taximaxim.codekeeper.ui.UIConsts.PREF;
import ru.taximaxim.codekeeper.ui.UIConsts.PROJ_PREF;
import ru.taximaxim.codekeeper.ui.dialogs.CommitDialog;
import ru.taximaxim.codekeeper.ui.localizations.Messages;
import ru.taximaxim.codekeeper.ui.pgdbproject.PgDbProject;
import ru.taximaxim.codekeeper.ui.prefs.Preferences;
import ru.taximaxim.codekeeper.ui.prefs.Preferences.ChangeImpact;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetry.BypassReason;
import ru.taximaxim.codekeeper.ui.utils.ProjectUtils;

/**
 * {@link ProjectReceiveOnlyMode#isEnabled(IProject)} is the only place that
 * decides whether a project only receives changes from a database - this
 * pins down its states before any caller starts asking it anything: off for
 * a project that says nothing, on when the workspace default says so, on when
 * the project overrides it explicitly, and back to the workspace when the
 * project holds a value its master toggle has switched off - the same
 * resolution every other MAIN preference gets through
 * {@code OverridablePrefs}.
 * <p>
 * {@link ProjectReceiveOnlyMode#depth(IProject)} is a thin, one-line mirror
 * of {@code isEnabled} and is deliberately not re-tested state by state here.
 * What the two tests below pin down instead is the thing that actually
 * matters: that a comparison loaded with the depth it returns behaves
 * exactly as {@link ComparisonDepth} promises - {@code STRUCTURAL_ONLY} for
 * an enabled project leaves every loaded statement without dependencies, and
 * {@code FULL} for an ordinary one resolves at least one.
 */
class ReceiveOnlyModeTest {

    @Test
    void theModeIsOffUntilTheProjectTurnsItOn(@TempDir Path temp)
            throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(temp, monitor);
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        String key = PREF.PROJECT_UPDATED_FROM_DATABASE_ONLY;
        boolean oldGlobalDefault = store.isDefault(key);
        boolean oldGlobal = store.getBoolean(key);
        try {
            assertFalse(ProjectReceiveOnlyMode.isEnabled(project),
                    "the mode must be off for a project that says nothing");

            // A workspace default must reach a project that never overrode
            // it - the project node holds no key of its own at this point,
            // not even the master "enable project preferences" toggle.
            store.setValue(key, true);
            assertTrue(ProjectReceiveOnlyMode.isEnabled(project),
                    "a workspace default must reach a project that never "
                            + "overrode it");

            // The reverse: workspace off, project on with its override
            // enabled - the project value must win regardless of the
            // workspace.
            store.setValue(key, false);
            setPreference(project, true);
            assertTrue(ProjectReceiveOnlyMode.isEnabled(project),
                    "an enabled project override must win over an off "
                            + "workspace default");

            // The same project value with the master toggle off, which is the
            // whole reason this resolution goes through OverridablePrefs
            // rather than reading the node. The shape is not hypothetical:
            // ProjectProperties.fillPrefs writes every field editor's value
            // into the project node on every OK whether the toggle is on or
            // not, so any project whose properties dialog was ever opened
            // holds a value here - and the toggle is what says whether it
            // applies. A reader of the raw node would turn the mode on for a
            // project that switched its overrides off.
            setOverrideRoot(project, false);
            assertFalse(ProjectReceiveOnlyMode.isEnabled(project),
                    "a project value the master toggle disabled must lose to "
                            + "the workspace");

            // ...and lose to it, not merely be dropped: the same node with the
            // workspace on answers on again, which no hardcoded off could.
            store.setValue(key, true);
            assertTrue(ProjectReceiveOnlyMode.isEnabled(project),
                    "with the master toggle off it is the workspace value "
                            + "that answers, whatever it says");
        } finally {
            if (oldGlobalDefault) {
                store.setToDefault(key);
            } else {
                store.setValue(key, oldGlobal);
            }
            cleanUp(project, monitor);
        }
    }

    @Test
    void aComparisonOfSuchAProjectLoadsStructureAlone(@TempDir Path temp)
            throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(temp, monitor);
        try {
            writeViewOverTableFixture(temp);
            setPreference(project, true);

            ComparisonDepth depth = ProjectReceiveOnlyMode.depth(project);
            assertEquals(ComparisonDepth.STRUCTURAL_ONLY, depth,
                    "an enabled project must ask for a structural load");

            UIComparisonLoader.LoadedModels models = UIComparisonLoader.loadModels(
                    viewOverTableFactories(temp), new CoreSettings(), depth);

            assertEquals(ComparisonDepth.STRUCTURAL_ONLY, models.depth());
            assertFalse(anyDependency(models),
                    "the analysis must not have run");
        } finally {
            cleanUp(project, monitor);
        }
    }

    @Test
    void aComparisonOfAnOrdinaryProjectLoadsFully(@TempDir Path temp)
            throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(temp, monitor);
        try {
            writeViewOverTableFixture(temp);
            // The mode is off by default here, see
            // theModeIsOffUntilTheProjectTurnsItOn.

            ComparisonDepth depth = ProjectReceiveOnlyMode.depth(project);
            assertEquals(ComparisonDepth.FULL, depth,
                    "an ordinary project must ask for a full load");

            UIComparisonLoader.LoadedModels models = UIComparisonLoader.loadModels(
                    viewOverTableFactories(temp), new CoreSettings(), depth);

            assertEquals(ComparisonDepth.FULL, models.depth());
            assertTrue(anyDependency(models),
                    "a full load resolves the view onto its table");
        } finally {
            cleanUp(project, monitor);
        }
    }

    /**
     * {@code ProjectEditorDiffer.diff()} must never build a migration script
     * from a structurally loaded comparison - see its {@code comparisonDepth}
     * check and {@code reloadForScript()}. This pins down both halves of that
     * claim without a workbench, which {@code diff()} itself needs and
     * SWTBot cannot supply headless in this environment:
     * <ol>
     * <li>the danger is real - a script built straight from a structural
     * load silently reorders two new views because neither carries the
     * dependency that says which one must be created first;</li>
     * <li>the fix works - reloading once at {@link ComparisonDepth#FULL}
     * before generating the script, exactly what {@code reloadForScript()}
     * does, produces the same script an ordinary full comparison would.</li>
     * </ol>
     */
    @Test
    void aScriptIsNeverBuiltFromAStructuralLoad(@TempDir Path temp) throws Exception {
        var monitor = new NullProgressMonitor();
        Path projectDir = temp.resolve("project");
        IProject project = createProject(projectDir, monitor);
        try {
            Path remote = temp.resolve("remote.sql");
            writeDependentViewsFixture(remote);
            // The project side stays empty: every object in the dump is new,
            // so the script must create both views in dependency order -
            // exactly what an empty dependency set cannot tell it to do.
            var provider = new PgDatabaseProvider();
            var factories = new ComparisonLoaderFactories(
                    sideSettings -> provider.getProjectLoader(projectDir, sideSettings),
                    sideSettings -> provider.getDumpLoader(remote, sideSettings));

            setPreference(project, true);
            String withoutTheGuard = scriptAt(factories, provider,
                    ProjectReceiveOnlyMode.depth(project));

            setPreference(project, false);
            String ordinary = scriptAt(factories, provider,
                    ProjectReceiveOnlyMode.depth(project));

            assertNotEquals(ordinary, withoutTheGuard,
                    "a script built straight from a structural load must "
                            + "lose the dependency that orders these two "
                            + "views - this is exactly what "
                            + "ProjectEditorDiffer.diff() must never be "
                            + "allowed to do");

            // The guard itself, mirroring ProjectEditorDiffer.diff(): a
            // comparison that answers anything but FULL is reloaded once, in
            // full, before a script is generated from it.
            setPreference(project, true);
            ComparisonDepth depth = ProjectReceiveOnlyMode.depth(project);
            UIComparisonLoader.LoadedModels loaded = UIComparisonLoader.loadModels(
                    factories, new CoreSettings(), depth);
            if (loaded.depth() != ComparisonDepth.FULL) {
                loaded = UIComparisonLoader.loadModels(
                        factories, new CoreSettings(), ComparisonDepth.FULL);
            }
            String guarded = scriptFrom(loaded, provider);

            assertEquals(ordinary, guarded,
                    "a script must not depend on how the comparison was loaded");
        } finally {
            cleanUp(project, monitor);
        }
    }

    /**
     * A third consequence of the same mode, alongside the depth a comparison
     * loads at: a receive-only project has no background index at all. That
     * index would only ever serve editor hints and outline structure for a
     * project edited by hand, and a project in this mode is written by a
     * database pull instead - so nothing is left to pay the cold build or the
     * per-file update that keeping it warm would otherwise cost.
     * {@link PgDbParser#projectIndexRefusal} is where every build asks this,
     * and it is asked before the project layout ever is - see
     * {@code ProjectIndexSupportPolicy.refusal}.
     */
    @Test
    void noBackgroundIndexIsBuiltAndTheLineSaysWhy(@TempDir Path temp)
            throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(temp, monitor);
        try {
            writeViewOverTableFixture(temp);
            setPreference(project, true);

            assertEquals(BypassReason.DISABLED_BY_PREFERENCE,
                    PgDbParser.projectIndexRefusal(project,
                            ProjectUtils.getPath(project)));

            setPreference(project, false);
            assertNull(PgDbParser.projectIndexRefusal(project,
                    ProjectUtils.getPath(project)),
                    "an ordinary project still has an index");
        } finally {
            cleanUp(project, monitor);
        }
    }

    /**
     * The index a project already had when the mode was turned on is the one
     * thing refusing every later build cannot deal with. Nothing maintains it
     * from that moment on, and nothing reading it checks: a pull overwrites
     * fifty files and the outline, the hover hints and the error markers keep
     * answering from the definitions those files used to hold.
     * <p>
     * What retires it is the mode being part of the index identity. Every
     * invalidation is guarded by that identity -
     * {@code ProjectIndexConfigurationInvalidator.invalidate} returns false and
     * does nothing at all when the fingerprint it is handed equals the one it
     * already holds - so a mode absent from the fingerprint makes the whole
     * index half of {@code ChangeImpact.BOTH} inert, however correctly the
     * change is routed. Both halves are asserted below, because either one
     * alone is silent.
     * <p>
     * The reverse switch matters just as much: the fingerprint moving back is
     * what orders the full rebuild that makes an index true again after the
     * mode is turned off.
     */
    @Test
    void switchingTheModeRetiresTheIndexBuiltUnderTheOtherOne(
            @TempDir Path temp) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(temp, monitor);
        try {
            assertEquals(ChangeImpact.BOTH, Preferences.getChangeImpact(
                    PREF.PROJECT_UPDATED_FROM_DATABASE_ONLY),
                    "the change has to be routed to the index at all");

            setPreference(project, false);
            String ordinary = PgDbParser
                    .projectIndexConfigurationFingerprint(project);

            setPreference(project, true);
            assertNotEquals(ordinary, PgDbParser
                    .projectIndexConfigurationFingerprint(project),
                    "an index nobody maintains any more must not keep the "
                            + "identity of one that was maintained");

            setPreference(project, false);
            assertEquals(ordinary, PgDbParser
                    .projectIndexConfigurationFingerprint(project),
                    "leaving the mode must order the rebuild that makes the "
                            + "index true again");
        } finally {
            cleanUp(project, monitor);
        }
    }

    /**
     * A fourth consequence of the same mode, alongside depth, script safety and
     * the background index: a structurally loaded comparison resolves only the
     * dependency edges that are readable off the model itself, so the set the
     * commit dialog offers for a receive-only project is short - short, not
     * empty, and written into the project all the same. Neither half of that
     * may be silent: the pane keeps its checkboxes, and the dialog says the set
     * is incomplete rather than letting a short list pass for a whole one.
     * {@link CommitDialog#isDependencySetComplete(ComparisonDepth)} is the
     * predicate it asks to decide that.
     */
    @Test
    void aStructuralDependencySetIsKnownToBeIncomplete() {
        assertFalse(CommitDialog.isDependencySetComplete(
                ComparisonDepth.STRUCTURAL_ONLY),
                "a set collected without analysis must not pass for a whole one");
        assertTrue(CommitDialog.isDependencySetComplete(
                ComparisonDepth.FULL));
    }

    /**
     * A mismatched key between {@code Messages.java} and a {@code .properties}
     * file compiles cleanly and only fails at runtime, with the field silently
     * holding an "NLS missing message" placeholder instead of the explanation a
     * user is meant to read - exactly the kind of silent gap this task exists
     * to rule out, so the two new messages get the same check
     * {@code CommitDialogWarningTest.reportedMessageIsLocalized} already runs
     * for an existing one.
     */
    @Test
    void theTwoNewExplanationsAreActuallyLocalized() {
        assertNotNull(Messages.CommitDialog_depcy_set_incomplete_in_receive_only);
        assertFalse(Messages.CommitDialog_depcy_set_incomplete_in_receive_only.startsWith("NLS missing message"),
                "CommitDialog_depcy_set_incomplete_in_receive_only must exist in the localization bundles");

        assertNotNull(Messages.DiffTableViewer_depcy_unavailable_in_receive_only);
        assertFalse(Messages.DiffTableViewer_depcy_unavailable_in_receive_only.startsWith("NLS missing message"),
                "DiffTableViewer_depcy_unavailable_in_receive_only must exist in the localization bundles");
    }

    private static String scriptAt(ComparisonLoaderFactories factories,
            PgDatabaseProvider provider, ComparisonDepth depth) throws Exception {
        return scriptFrom(UIComparisonLoader.loadModels(
                factories, new CoreSettings(), depth), provider);
    }

    private static String scriptFrom(UIComparisonLoader.LoadedModels loaded,
            PgDatabaseProvider provider) throws Exception {
        return PgCodeKeeperApi.diff(provider, loaded.oldDatabase(),
                loaded.newDatabase(), loaded.settings());
    }

    /**
     * Two views of the same {@link org.pgcodekeeper.core.database.api.schema.DbObjType},
     * so the order the script builder falls back to when nothing else orders
     * two new objects cannot save it - the base view is declared second,
     * after the one that selects from it, so only a real dependency edge can
     * put it first.
     */
    private static void writeDependentViewsFixture(Path dumpFile) throws IOException {
        Files.writeString(dumpFile, """
                CREATE SCHEMA app;
                CREATE VIEW app.a_report AS SELECT id FROM app.z_base;
                CREATE VIEW app.z_base AS SELECT 1 AS id;
                """);
    }

    /**
     * Writes a schema, a table and a view selecting from that table - the
     * smallest fixture with a real dependency for a full load to resolve and
     * a structural load to leave alone. Mirrors
     * {@code ComparisonDepthTestSupport.projectWithAViewOverATable} in Core,
     * which pins down the same claim for {@code PgCodeKeeperApi} directly.
     */
    private static void writeViewOverTableFixture(Path projectDir) throws IOException {
        writeFile(projectDir, "SCHEMA/app/app.sql", "CREATE SCHEMA app;\n");
        writeFile(projectDir, "SCHEMA/app/TABLE/item.sql", "CREATE TABLE app.item (id integer);\n");
        writeFile(projectDir, "SCHEMA/app/VIEW/pick.sql",
                "CREATE VIEW app.pick AS SELECT id FROM app.item;\n");
    }

    private static void writeFile(Path projectDir, String relativePath, String content)
            throws IOException {
        Path file = projectDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    /**
     * Both comparison sides load the same directory: only depth is under
     * test here, so which side is OLD versus NEW makes no difference.
     */
    private static ComparisonLoaderFactories viewOverTableFactories(Path projectDir) {
        var provider = new PgDatabaseProvider();
        return new ComparisonLoaderFactories(
                sideSettings -> provider.getProjectLoader(projectDir, sideSettings),
                sideSettings -> provider.getProjectLoader(projectDir, sideSettings));
    }

    private static boolean anyDependency(UIComparisonLoader.LoadedModels models) {
        return models.oldDatabase().getDescendants()
                .anyMatch(statement -> !((IStatement) statement)
                        .getDependencies().isEmpty());
    }

    /**
     * Writes the preference as a project override with the master
     * "enable project preferences" toggle on, so the verdict cannot depend
     * on what the workspace happens to hold - the same shape
     * {@code ProjectProperties.fillPrefs} writes when a user actually turns
     * the checkbox on for this project.
     */
    private static void setPreference(IProject project, boolean enabled)
            throws Exception {
        IEclipsePreferences prefs = PgDbProject.getPrefs(project, true);
        prefs.putBoolean(PROJ_PREF.ENABLE_PROJ_PREF_ROOT, true);
        prefs.putBoolean(PREF.PROJECT_UPDATED_FROM_DATABASE_ONLY, enabled);
        prefs.flush();
    }

    /**
     * Moves the master "enable project preferences" toggle alone, leaving
     * every value {@link #setPreference} wrote where it is - the state a user
     * leaves behind by clearing that one checkbox in the project properties.
     */
    private static void setOverrideRoot(IProject project, boolean enabled)
            throws Exception {
        IEclipsePreferences prefs = PgDbProject.getPrefs(project, true);
        prefs.putBoolean(PROJ_PREF.ENABLE_PROJ_PREF_ROOT, enabled);
        prefs.flush();
    }

    private static IProject createProject(Path location,
            NullProgressMonitor monitor) throws Exception {
        var workspace = ResourcesPlugin.getWorkspace();
        String name = "pgck-receive-only-" + location.getFileName();
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

    private static void cleanUp(IProject project, NullProgressMonitor monitor)
            throws Exception {
        if (project.exists()) {
            project.delete(true, true, monitor);
        }
    }
}
