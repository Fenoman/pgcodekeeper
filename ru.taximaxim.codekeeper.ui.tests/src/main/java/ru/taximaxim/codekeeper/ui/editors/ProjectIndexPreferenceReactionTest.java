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
package ru.taximaxim.codekeeper.ui.editors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ru.taximaxim.codekeeper.ui.DatabaseType;
import ru.taximaxim.codekeeper.ui.prefs.PreferenceChangeCoalescer;
import ru.taximaxim.codekeeper.ui.utils.ProjectUtils;

/**
 * What an open editor does with a preference change that reaches the background
 * index, for a project of every database type.
 * <p>
 * The reaction may not be gated on the database type. The index covers every
 * type - {@code ProjectIndexOtherDialectsTest} measures that - and every
 * setting the index answers to is offered to every dialect, so a gate here
 * would leave an editor open on a MS SQL or ClickHouse project ignoring a
 * workspace-level change of its own index settings.
 * <p>
 * What this pins is that the method holds no dialect term at all. It cannot see
 * which node the change came from either - that argument went with the gate that
 * was its only reader - so the question "does a project of this type get its
 * index re-read" has one answer here rather than two.
 */
class ProjectIndexPreferenceReactionTest {

    private static final long SETTLE_DELAY_MILLIS = 750;

    @Test
    void everyProjectTypeGetsItsIndexConfigurationReRead(@TempDir Path temp)
            throws Exception {
        var monitor = new NullProgressMonitor();
        for (DatabaseType databaseType : DatabaseType.values()) {
            IProject project = createProject(temp, monitor, databaseType);
            try {
                var scheduler = new ManualScheduler();
                var reactions = new PreferenceChangeCoalescer(
                        SETTLE_DELAY_MILLIS, scheduler);

                assertTrue(ProjectEditorDiffer.deferProjectIndexInvalidation(
                        reactions, project),
                        databaseType + ": no settling window was opened for"
                                + " the index of this project");
                assertEquals(1, scheduler.liveWindows(),
                        databaseType + ": the reaction did not leave exactly"
                                + " one window to fire");
                assertEquals(List.of(SETTLE_DELAY_MILLIS),
                        scheduler.requestedDelays(),
                        databaseType + ": the reaction did not wait for the"
                                + " node to settle");
            } finally {
                cleanUp(project, monitor);
            }
        }
    }

    /**
     * A burst of changes still collapses into one reaction, for a project of
     * every type. The key the reaction is filed under is what does that, and it
     * is the one thing the extraction of this method could have dropped without
     * anything else noticing.
     */
    @Test
    void aBurstStillCoalescesIntoOneReaction(@TempDir Path temp)
            throws Exception {
        var monitor = new NullProgressMonitor();
        for (DatabaseType databaseType : DatabaseType.values()) {
            IProject project = createProject(temp, monitor, databaseType);
            try {
                var scheduler = new ManualScheduler();
                var reactions = new PreferenceChangeCoalescer(
                        SETTLE_DELAY_MILLIS, scheduler);

                assertTrue(ProjectEditorDiffer.deferProjectIndexInvalidation(
                        reactions, project),
                        databaseType + ": the first event of a burst opened no"
                                + " window");
                assertFalse(ProjectEditorDiffer.deferProjectIndexInvalidation(
                        reactions, project),
                        databaseType + ": the second event of a burst opened a"
                                + " window of its own");
                assertFalse(ProjectEditorDiffer.deferProjectIndexInvalidation(
                        reactions, project));

                assertEquals(3, scheduler.windows.size(),
                        databaseType + ": every event has to restart the"
                                + " settling window");
                assertEquals(1, scheduler.liveWindows(),
                        databaseType + ": the burst left more than one window"
                                + " to fire");
            } finally {
                cleanUp(project, monitor);
            }
        }
    }

    private static IProject createProject(Path temp,
            NullProgressMonitor monitor, DatabaseType databaseType)
            throws Exception {
        var workspace = ResourcesPlugin.getWorkspace();
        String name = "pgck-index-reaction-"
                + databaseType.name().toLowerCase();
        IProject project = workspace.getRoot().getProject(name);
        // Before the start rather than after the finish: a project left behind
        // by an interrupted run would make every later one fail on creation.
        if (project.exists()) {
            project.delete(true, true, monitor);
        }
        IProjectDescription description = workspace.newProjectDescription(name);
        description.setLocationURI(temp.resolve(name).toUri());
        project.create(description, monitor);
        project.open(monitor);
        IProjectDescription open = project.getDescription();
        open.setNatureIds(ProjectUtils.getProjectNatures(databaseType));
        project.setDescription(open, monitor);
        return project;
    }

    private static void cleanUp(IProject project, NullProgressMonitor monitor)
            throws Exception {
        if (project.exists()) {
            project.delete(true, true, monitor);
        }
    }

    /** Holds every window instead of running it, so nothing waits. */
    private static final class ManualScheduler
            implements PreferenceChangeCoalescer.Scheduler {

        private final List<Window> windows = new ArrayList<>();

        @Override
        public PreferenceChangeCoalescer.Cancellation schedule(
                Runnable window, long delayMillis) {
            var scheduled = new Window(delayMillis);
            windows.add(scheduled);
            return () -> scheduled.cancelled = true;
        }

        private long liveWindows() {
            return windows.stream().filter(window -> !window.cancelled).count();
        }

        private List<Long> requestedDelays() {
            return windows.stream().map(window -> window.delayMillis)
                    .distinct().toList();
        }
    }

    private static final class Window {

        private final long delayMillis;
        private boolean cancelled;

        private Window(long delayMillis) {
            this.delayMillis = delayMillis;
        }
    }
}
