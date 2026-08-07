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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ru.taximaxim.codekeeper.ui.UIConsts.PREF;
import ru.taximaxim.codekeeper.ui.UIConsts.PROJ_PREF;
import ru.taximaxim.codekeeper.ui.pgdbproject.PgDbProject;
import ru.taximaxim.codekeeper.ui.utils.ProjectUtils;

/**
 * {@link ProjectBuilder#prepareBuild} is where every build kind Eclipse can
 * ask for - an ordinary auto-build, an explicit incremental build, or a full
 * rebuild - is dispatched to the code that actually touches the project
 * index. A receive-only project has no use for that index at all, so this
 * pins down that the receive-only question is asked first, before
 * {@code parser}, {@code classified}, {@code proof} or {@code progress} is
 * ever touched.
 * <p>
 * Every one of those four is handed in as {@code null}. That is not a
 * shortcut: it is the proof itself. {@code prepareIncrement} defaults a null
 * {@code classified} to a full classification and then reaches {@code
 * parser.prepareRepairedProjectIndex(...)}; {@code prepareFullBuild} reaches
 * the same call directly. Either path dereferences the null {@code parser}
 * immediately. A regression that let a receive-only build fall through to
 * either one would therefore throw a {@link NullPointerException} out of this
 * test before any assertion ran - there is no way for the call to return
 * normally other than by refusing before any of the four are touched.
 * <p>
 * The captured configuration is handed in as {@code null} too, which is a
 * fifth null and a different claim: it says the build cycle read nothing for
 * itself, so the refusal below is decided by the preference node, exactly as
 * it was before a cycle could carry a configuration at all. That a cycle
 * <em>with</em> one is decided by that instead is
 * {@link ProjectBuilderConfigurationSnapshotTest}'s question.
 */
class ProjectBuilderReceiveOnlyTest {

    @Test
    void everyBuildKindOfAReceiveOnlyProjectRefusesBeforeTouchingTheParser(
            @TempDir Path temp) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(temp, monitor);
        try {
            setPreference(project, true);
            var builder = new ProjectBuilder();

            assertRefusedWithoutTouchingAnything(builder,
                    IncrementalProjectBuilder.AUTO_BUILD, project, monitor);
            assertRefusedWithoutTouchingAnything(builder,
                    IncrementalProjectBuilder.INCREMENTAL_BUILD, project,
                    monitor);
            // The third similar place: an explicit full/clean build must
            // refuse exactly the same way. It must not fall back to
            // prepareFullBuild's own bypass (which still parses the whole
            // project via PgDbParser#prepareFullDBFromPgDbProject), and the
            // returned update must not be the "fullBuild" shape either -
            // ProjectBuilder.prepareIncrement runs LibraryUtils.create(...)
            // on top of whatever it wraps whenever that flag is set.
            assertRefusedWithoutTouchingAnything(builder,
                    IncrementalProjectBuilder.FULL_BUILD, project, monitor);
        } finally {
            cleanUp(project, monitor);
        }
    }

    private static void assertRefusedWithoutTouchingAnything(
            ProjectBuilder builder, int kind, IProject project,
            NullProgressMonitor monitor) throws Exception {
        ProjectBuilder.PreparedUpdate update = builder.prepareBuild(
                kind, null, project, monitor, null, null, null, null);

        assertFalse(update.restoredFromDisk(),
                "a refusal restores nothing from disk");
        assertDoesNotThrow(() -> update.commit().run(),
                "a refusal must not run any prepared commit");
        assertDoesNotThrow(() -> update.discard().run(),
                "a refusal must not run any prepared discard");
    }

    /**
     * Writes the preference as a project override with the master
     * "enable project preferences" toggle on, so the verdict cannot depend
     * on what the workspace happens to hold. Mirrors
     * {@code ReceiveOnlyModeTest.setPreference}.
     */
    private static void setPreference(IProject project, boolean enabled)
            throws Exception {
        IEclipsePreferences prefs = PgDbProject.getPrefs(project, true);
        prefs.putBoolean(PROJ_PREF.ENABLE_PROJ_PREF_ROOT, true);
        prefs.putBoolean(PREF.PROJECT_UPDATED_FROM_DATABASE_ONLY, enabled);
        prefs.flush();
    }

    private static IProject createProject(Path location,
            NullProgressMonitor monitor) throws Exception {
        var workspace = ResourcesPlugin.getWorkspace();
        String name = "pgck-builder-receive-only-" + location.getFileName();
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
