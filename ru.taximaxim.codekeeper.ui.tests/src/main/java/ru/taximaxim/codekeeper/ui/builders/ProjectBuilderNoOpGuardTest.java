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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ru.taximaxim.codekeeper.ui.DatabaseType;
import ru.taximaxim.codekeeper.ui.UIConsts.PREF;
import ru.taximaxim.codekeeper.ui.UIConsts.PROJ_PREF;
import ru.taximaxim.codekeeper.ui.builders.ProjectBuildDeltaClassifier.Mode;
import ru.taximaxim.codekeeper.ui.builders.ProjectBuildDeltaClassifier.Reason;
import ru.taximaxim.codekeeper.ui.builders.ProjectBuildDeltaClassifier.Result;
import ru.taximaxim.codekeeper.ui.builders.ProjectBuildDeltaCollector.Delta;
import ru.taximaxim.codekeeper.ui.builders.ProjectBuildDeltaCollector.Layout;
import ru.taximaxim.codekeeper.ui.pgdbproject.PgDbProject;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexBuildConfiguration;
import ru.taximaxim.codekeeper.ui.utils.ProjectUtils;

/**
 * The one verdict of a build cycle that nothing downstream ever checks.
 *
 * <p>An incremental or full classification asks for a build; the build stamps
 * an identity and the publication guard compares it against the workbench
 * before anything is kept. {@code NO_OP} asks for nothing, so no build starts,
 * so no guard runs. If the configuration that decided there was nothing to do
 * was a passing state of the preference node - and the platform re-applies a
 * preferences file key by key, reporting each key while it is gone - then the
 * change it dropped is dropped for good: the index keeps a definition the
 * working tree no longer holds, and nothing will notice until something
 * unrelated forces a rebuild.</p>
 *
 * <p>Each test below builds a real {@code NO_OP} the way the defect builds
 * one, out of a real preference node, and asks what becomes of it.</p>
 */
class ProjectBuilderNoOpGuardTest {

    private static final String EXCLUDED_SCHEMA = "dummy_tmp"; //$NON-NLS-1$

    private static final String FILE_IN_EXCLUDED_SCHEMA =
            "SCHEMA/dummy_tmp/FUNCTION/calculate.sql"; //$NON-NLS-1$

    private static final String FILE_IN_INDEXED_SCHEMA =
            "SCHEMA/app/FUNCTION/calculate.sql"; //$NON-NLS-1$

    /**
     * The loss itself, and the refusal to accept it. The change is real, the
     * classification that drops it is real, and the only thing wrong with it
     * is that nobody excludes that schema any more.
     */
    @Test
    void aNothingToDoDecidedByAConfigurationThatMovedIsNotHonoured(
            @TempDir Path temp) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(temp, monitor);
        try {
            setExcludedSchemas(project, EXCLUDED_SCHEMA);
            ProjectIndexBuildConfiguration captured =
                    ProjectBuilder.captureConfiguration(project);
            Result classified = classify(project, captured,
                    FILE_IN_EXCLUDED_SCHEMA);

            assertEquals(Mode.NO_OP, classified.mode(),
                    "the change this cycle would have thrown away");

            // Nobody excludes that schema; the value that dropped the change
            // was one the node was passing through.
            setExcludedSchemas(project, ""); //$NON-NLS-1$

            Result guarded = ProjectBuilder.guardNoOp(project, classified,
                    captured);

            assertEquals(Mode.FULL, guarded.mode(),
                    "a change was dropped by a configuration nobody asks for");
            assertEquals(Reason.CONFIGURATION_MOVED, guarded.reason());
            assertTrue(guarded.evidence().contains("moved"), //$NON-NLS-1$
                    "the escalation has to say what it was: "
                            + guarded.evidence());
        } finally {
            cleanUp(project, monitor);
        }
    }

    /**
     * The control that keeps the guard from being an unconditional escalation:
     * a standing-still workbench must go on doing nothing when there is
     * nothing to do. Every ordinary marker-only delta of every ordinary
     * session takes this path.
     */
    @Test
    void aNothingToDoDecidedByTheSettledConfigurationStands(
            @TempDir Path temp) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(temp, monitor);
        try {
            setExcludedSchemas(project, EXCLUDED_SCHEMA);
            ProjectIndexBuildConfiguration captured =
                    ProjectBuilder.captureConfiguration(project);
            Result classified = classify(project, captured,
                    FILE_IN_EXCLUDED_SCHEMA);

            assertSame(classified, ProjectBuilder.guardNoOp(project,
                    classified, captured),
                    "a cycle that agrees with the workbench was disturbed");
        } finally {
            cleanUp(project, monitor);
        }
    }

    /**
     * And the other side of the narrowness: a verdict that asks for work is
     * left alone even when the configuration moved under it. The work it asks
     * for carries the same configuration into the publication guard, which
     * asks this same question with the same fail-closed answer, so escalating
     * here would only buy a second refusal of the same build.
     */
    @Test
    void averdictThatAsksForWorkIsLeftToThePublicationGuard(
            @TempDir Path temp) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(temp, monitor);
        try {
            setExcludedSchemas(project, EXCLUDED_SCHEMA);
            ProjectIndexBuildConfiguration captured =
                    ProjectBuilder.captureConfiguration(project);
            Result classified = classify(project, captured,
                    FILE_IN_INDEXED_SCHEMA);

            assertEquals(Mode.INCREMENTAL, classified.mode());

            setExcludedSchemas(project, ""); //$NON-NLS-1$

            assertSame(classified, ProjectBuilder.guardNoOp(project,
                    classified, captured),
                    "a build that will be guarded on publication was "
                            + "escalated twice");
        } finally {
            cleanUp(project, monitor);
        }
    }

    /**
     * A cycle that read no configuration has nothing to compare, and is
     * therefore never refused. It is also the cycle that already classifies
     * as a full build, so there is no verdict of "nothing to do" left for
     * this to protect.
     */
    @Test
    void aCycleThatReadNoConfigurationIsNeverRefused(@TempDir Path temp)
            throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(temp, monitor);
        try {
            setExcludedSchemas(project, EXCLUDED_SCHEMA);
            Result classified = classify(project,
                    ProjectBuilder.captureConfiguration(project),
                    FILE_IN_EXCLUDED_SCHEMA);

            setExcludedSchemas(project, ""); //$NON-NLS-1$

            assertSame(classified,
                    ProjectBuilder.guardNoOp(project, classified, null));
        } finally {
            cleanUp(project, monitor);
        }
    }

    private static Result classify(IProject project,
            ProjectIndexBuildConfiguration captured, String relativePath) {
        Layout layout = ProjectBuilder.layout(project, DatabaseType.PG,
                captured);
        return ProjectBuildDeltaCollector.classify(
                List.of(new Delta(relativePath, CHANGED, CONTENT,
                        IResource.FILE, true)),
                layout);
    }

    private static void setExcludedSchemas(IProject project, String value)
            throws Exception {
        IEclipsePreferences prefs = PgDbProject.getPrefs(project, true);
        prefs.putBoolean(PROJ_PREF.ENABLE_PROJ_PREF_ROOT, true);
        prefs.put(PREF.PROJECT_INDEX_EXCLUDED_SCHEMAS, value);
        prefs.flush();
    }

    private static IProject createProject(Path location,
            NullProgressMonitor monitor) throws Exception {
        var workspace = ResourcesPlugin.getWorkspace();
        String name = "pgck-builder-noop-guard-" + location.getFileName();
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
