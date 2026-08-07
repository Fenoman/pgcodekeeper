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

import static org.eclipse.core.resources.IResourceDelta.CHANGED;
import static org.eclipse.core.resources.IResourceDelta.CONTENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ru.taximaxim.codekeeper.ui.DatabaseType;
import ru.taximaxim.codekeeper.ui.UIConsts.PREF;
import ru.taximaxim.codekeeper.ui.UIConsts.PROJ_PREF;
import ru.taximaxim.codekeeper.ui.builders.ProjectBuildDeltaClassifier.Mode;
import ru.taximaxim.codekeeper.ui.builders.ProjectBuildDeltaCollector.Delta;
import ru.taximaxim.codekeeper.ui.builders.ProjectBuildDeltaCollector.Layout;
import ru.taximaxim.codekeeper.ui.pgdbproject.PgDbProject;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexBuildConfiguration;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexConfiguration;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexConfiguration.GlobalSettings;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexConfiguration.ProjectOverrides;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexSchemaExclusions;
import ru.taximaxim.codekeeper.ui.utils.ProjectUtils;

/**
 * One turn of the builder, and the one reading of the preference node it is
 * entitled to.
 *
 * <p>The reading is taken at the start of the cycle, before the delta is
 * classified, and everything after is handed what it answered. That ordering
 * is the whole point: the classification decides which files the build is even
 * told about, and it decides that by the configuration. A change under a
 * schema the classification excludes is dropped from the batch, and a delta
 * holding nothing else classifies as {@code NO_OP} - no build starts, so no
 * build publishes, so the publication guard is never asked whether the
 * configuration moved. Where a second reading inside a build costs a build,
 * a second reading before one costs the change itself.</p>
 *
 * <p>Nothing here asks whether the window is narrow. The node is moved between
 * the reading and every use of it, and each test asserts that the use still
 * answers to what was read - which is the only form of the property a moving
 * node cannot break.</p>
 */
class ProjectBuilderConfigurationSnapshotTest {

    /** The workspace an OmniX project inherits from: everything off. */
    private static final GlobalSettings WORKSPACE =
            new GlobalSettings(false, false, false, false, ""); //$NON-NLS-1$

    private static final String EXCLUDED_SCHEMA = "dummy_tmp"; //$NON-NLS-1$

    private static final String FILE_IN_EXCLUDED_SCHEMA =
            "SCHEMA/dummy_tmp/FUNCTION/calculate.sql"; //$NON-NLS-1$

    /**
     * The classification is bound to what the cycle read, not to what the node
     * holds by the time the layout is built.
     *
     * <p>Both halves of this are asserted, because either alone is satisfied
     * by an accident. That the layout excludes the schema says the value
     * survived; that a fresh reading of the node disagrees says there were two
     * values to choose between in the first place.</p>
     */
    @Test
    void theClassificationAnswersToTheReadingTheCycleTookNotToTheNode(
            @TempDir Path temp) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(temp, monitor);
        try {
            setExcludedSchemas(project, EXCLUDED_SCHEMA);
            ProjectIndexBuildConfiguration captured =
                    ProjectBuilder.captureConfiguration(project);

            // The platform re-applies the preferences file, and the key is
            // gone for as long as that takes.
            setExcludedSchemas(project, ""); //$NON-NLS-1$

            Layout layout = ProjectBuilder.layout(project, DatabaseType.PG,
                    captured);

            assertNotEquals(captured.configuration().excludedSchemas(),
                    ProjectBuilder.captureConfiguration(project)
                            .configuration().excludedSchemas(),
                    "the node did not move, so nothing below is a choice");
            assertEquals(Set.of(EXCLUDED_SCHEMA), layout.excludedSchemas(),
                    "the classification left out what the node says now");
            assertEquals(ProjectIndexSchemaExclusions.parse(
                    captured.configuration().excludedSchemas()),
                    layout.excludedSchemas(),
                    "the files left out and the identity the build will be "
                            + "stamped with disagree");
            assertEquals(Mode.NO_OP, classify(layout,
                    file(FILE_IN_EXCLUDED_SCHEMA)),
                    "a change the captured configuration excludes");
        } finally {
            cleanUp(project, monitor);
        }
    }

    /**
     * The same rule stated over the other two settings the layout is built
     * from, so that a fix which only carried the exclusions would still be
     * caught.
     */
    @Test
    void everySettingOfTheLayoutComesFromThatOneReading(@TempDir Path temp)
            throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(temp, monitor);
        try {
            setBooleans(project, true, true);
            ProjectIndexBuildConfiguration captured =
                    ProjectBuilder.captureConfiguration(project);

            setBooleans(project, false, false);

            Layout layout = ProjectBuilder.layout(project, DatabaseType.PG,
                    captured);

            assertNotEquals(
                    ProjectBuilder.captureConfiguration(project)
                            .configuration().ignorePrivileges(),
                    captured.configuration().ignorePrivileges(),
                    "the node did not move, so nothing below is a choice");
            assertEquals(true, layout.ignorePrivileges());
            assertEquals(true, layout.incrementalAddedFiles());
        } finally {
            cleanUp(project, monitor);
        }
    }

    /**
     * A build is refused by the configuration its cycle captured, not by the
     * node. The parser is null, so a build that fell through to either
     * dispatch would dereference it - a refusal is the only way this returns.
     */
    @Test
    void aBuildIsRefusedByTheConfigurationItWasHanded(@TempDir Path temp)
            throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(temp, monitor);
        try {
            setReceiveOnly(project, false);

            ProjectBuilder.PreparedUpdate update = new ProjectBuilder()
                    .prepareBuild(IncrementalProjectBuilder.AUTO_BUILD, null,
                            project, monitor, null, null, null,
                            receiveOnly(true));

            assertFalse(update.restoredFromDisk(),
                    "a refusal restores nothing from disk");
        } finally {
            cleanUp(project, monitor);
        }
    }

    /**
     * And the other way round, which is what keeps the test above from
     * passing on a build that refuses everything: a cycle whose configuration
     * says the index is wanted must build, however the node reads at the
     * moment it is asked.
     */
    @Test
    void aBuildIsNotRefusedByANodeItsConfigurationDisagreesWith(
            @TempDir Path temp) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(temp, monitor);
        try {
            setReceiveOnly(project, true);
            var builder = new ProjectBuilder();

            assertThrows(NullPointerException.class,
                    () -> builder.prepareBuild(
                            IncrementalProjectBuilder.AUTO_BUILD, null,
                            project, monitor, null, null, null,
                            receiveOnly(false)),
                    "the build refused by a node its configuration "
                            + "disagrees with");
        } finally {
            cleanUp(project, monitor);
        }
    }

    private static ProjectIndexBuildConfiguration receiveOnly(
            boolean enabled) {
        return ProjectIndexBuildConfiguration.capture(
                () -> ProjectIndexConfiguration.resolve(DatabaseType.PG, true,
                        WORKSPACE, new ProjectOverrides(null, null, null,
                                enabled, null)));
    }

    private static Mode classify(Layout layout, Delta... deltas) {
        return ProjectBuildDeltaCollector.classify(List.of(deltas), layout)
                .mode();
    }

    private static Delta file(String relativePath) {
        return new Delta(relativePath, CHANGED, CONTENT, IResource.FILE,
                true);
    }

    private static void setExcludedSchemas(IProject project, String value)
            throws Exception {
        IEclipsePreferences prefs = PgDbProject.getPrefs(project, true);
        prefs.putBoolean(PROJ_PREF.ENABLE_PROJ_PREF_ROOT, true);
        prefs.put(PREF.PROJECT_INDEX_EXCLUDED_SCHEMAS, value);
        prefs.flush();
    }

    private static void setBooleans(IProject project, boolean ignorePrivileges,
            boolean incrementalAddedFiles) throws Exception {
        IEclipsePreferences prefs = PgDbProject.getPrefs(project, true);
        prefs.putBoolean(PROJ_PREF.ENABLE_PROJ_PREF_ROOT, true);
        prefs.putBoolean(PREF.NO_PRIVILEGES, ignorePrivileges);
        prefs.putBoolean(PREF.PROJECT_INDEX_INCREMENTAL_ADDED_FILES,
                incrementalAddedFiles);
        prefs.flush();
    }

    private static void setReceiveOnly(IProject project, boolean enabled)
            throws Exception {
        IEclipsePreferences prefs = PgDbProject.getPrefs(project, true);
        prefs.putBoolean(PROJ_PREF.ENABLE_PROJ_PREF_ROOT, true);
        prefs.putBoolean(PREF.PROJECT_UPDATED_FROM_DATABASE_ONLY, enabled);
        prefs.flush();
    }

    private static IProject createProject(Path location,
            NullProgressMonitor monitor) throws Exception {
        var workspace = ResourcesPlugin.getWorkspace();
        String name = "pgck-builder-configuration-"
                + location.getFileName();
        IProject project = workspace.getRoot().getProject(name);
        if (project.exists()) {
            project.delete(true, true, monitor);
        }
        IProjectDescription description =
                workspace.newProjectDescription(name);
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
