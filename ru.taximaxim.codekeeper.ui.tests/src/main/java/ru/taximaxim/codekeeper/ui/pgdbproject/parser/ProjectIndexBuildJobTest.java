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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.junit.jupiter.api.Test;

import ru.taximaxim.codekeeper.ui.localizations.Messages;

/**
 * The job that reindexes a project after a configuration change is all the
 * user gets to see of a full rebuild, and a full rebuild of a large project
 * runs for the better part of a minute while holding the workspace root rule.
 * A job the platform never displays turns that minute into an idle looking
 * workbench, which invites the very edit that makes the running build lose its
 * race against the refresh and throw its work away.
 * <p>
 * The scheduling itself cannot be reached from here: {@code WorkspaceJob} asks
 * the platform for a workspace inside its own constructor. What is reachable
 * is what that path decides -- the label and the visibility.
 */
class ProjectIndexBuildJobTest {

    private static final String PROJECT = "OmniX_DB";

    /**
     * The platform decides displayability by {@code isSystem()} alone: a
     * system job is never shown, on any surface, however long it runs. This
     * path schedules nothing but full rebuilds, so there is no short and noisy
     * case that would earn hiding.
     */
    @Test
    void theRebuildJobIsVisibleToTheUser() {
        Job job = probeJob();

        PgDbParser.makeProjectIndexBuildVisible(job);

        assertFalse(job.isSystem(),
                "a full project reindex may not hide from the progress view");
    }

    @Test
    void theRebuildJobNamesTheProjectItRebuilds() {
        String name = PgDbParser.projectIndexBuildJobName(PROJECT);

        assertTrue(name.contains(PROJECT),
                "a user with several projects has to see which one is busy");
        assertEquals(Messages.PgDbParser_project_index_rebuild_job
                .formatted(PROJECT), name);
        assertFalse(name.startsWith("pgCodeKeeper project index:"),
                "the label reaches a user, so it comes from the bundle "
                        + "rather than from a technical literal");
    }

    /**
     * A visible label that exists only in English is half a fix: the plugin
     * ships localized, and the progress view is where a Russian user reads
     * what the workbench is doing.
     */
    @Test
    void bothBundlesCarryTheJobLabel() throws IOException {
        String english = label("messages.properties");
        String russian = label("messages_ru_RU.properties");

        assertTrue(english.contains("%s"),
                "the English label has to take the project name");
        assertTrue(russian.contains("%s"),
                "the Russian label has to take the project name");
        assertTrue(russian.chars().anyMatch(ProjectIndexBuildJobTest::cyrillic),
                "the Russian label is not translated: " + russian);
        assertFalse(english.chars().anyMatch(ProjectIndexBuildJobTest::cyrillic),
                "the English label is not English: " + english);
    }

    private static Job probeJob() {
        return new Job("probe") {

            @Override
            protected IStatus run(IProgressMonitor monitor) {
                return Status.OK_STATUS;
            }
        };
    }

    private static boolean cyrillic(int codePoint) {
        return Character.UnicodeBlock.of(codePoint)
                == Character.UnicodeBlock.CYRILLIC;
    }

    private static String label(String bundle) throws IOException {
        var properties = new Properties();
        try (InputStream source = Messages.class
                .getResourceAsStream(bundle)) {
            assertNotNull(source, "missing bundle " + bundle);
            // Properties#load is the same ISO-8859-1 plus \\uXXXX reader the
            // platform uses, so this reads exactly what a user would see.
            properties.load(source);
        }
        String label = properties.getProperty(
                "PgDbParser_project_index_rebuild_job");
        assertNotNull(label, bundle + " has no label for the rebuild job");
        return label;
    }
}
