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
package ru.taximaxim.codekeeper.ui.prefs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ProjectScope;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.jface.preference.IPreferenceStore;
import org.junit.jupiter.api.Test;

import ru.taximaxim.codekeeper.ui.Activator;
import ru.taximaxim.codekeeper.ui.DatabaseType;
import ru.taximaxim.codekeeper.ui.UIConsts;
import ru.taximaxim.codekeeper.ui.UIConsts.PREF;
import ru.taximaxim.codekeeper.ui.UIConsts.PROJ_PREF;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexConfiguration;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexConfiguration.GlobalSettings;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexConfiguration.ProjectOverrides;
import ru.taximaxim.codekeeper.ui.properties.OverridablePrefs;

/**
 * Guards the contract that project-scoped tuning relies on.
 * <p>
 * Project-specific tuning such as the OmniX {@code dummy_tmp} scratch schema
 * must not become a global default for every user. It is shipped instead in the
 * project's own {@code .settings/ru.taximaxim.codekeeper.ui.prefs}, so these
 * exact preference key strings are part of a file committed to a DDL
 * repository and cannot be renamed silently.
 */
class ProjectScopedTuningTest {

    /** Key written into the committed project preference file. */
    private static final String COMMITTED_ENABLE_KEY = "prefEnableProjPrefRoot";
    /** Key written into the committed project preference file. */
    private static final String COMMITTED_EXCLUSION_KEY =
            "projectIndexExcludedSchemas";

    @Test
    void committedProjectPreferenceKeysAreStable() {
        assertEquals(COMMITTED_ENABLE_KEY, PROJ_PREF.ENABLE_PROJ_PREF_ROOT);
        assertEquals(COMMITTED_EXCLUSION_KEY,
                PREF.PROJECT_INDEX_EXCLUDED_SCHEMAS);
        assertEquals("ru.taximaxim.codekeeper.ui", UIConsts.PLUGIN_ID.THIS,
                "the preference node names the .prefs file in .settings");
    }

    @Test
    void projectExclusionsApplyOnlyWhenTheProjectOverrideIsEnabled()
            throws Exception {
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        String oldGlobal = store.getString(
                PREF.PROJECT_INDEX_EXCLUDED_SCHEMAS);
        boolean oldGlobalDefault = store.isDefault(
                PREF.PROJECT_INDEX_EXCLUDED_SCHEMAS);
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn(
                "ProjectScopedTuningTest-" + UUID.randomUUID()); //$NON-NLS-1$
        IEclipsePreferences projectPrefs = new ProjectScope(project)
                .getNode(UIConsts.PLUGIN_ID.THIS);

        try {
            store.setValue(PREF.PROJECT_INDEX_EXCLUDED_SCHEMAS, ""); //$NON-NLS-1$
            projectPrefs.put(COMMITTED_EXCLUSION_KEY, "dummy_tmp"); //$NON-NLS-1$

            projectPrefs.putBoolean(COMMITTED_ENABLE_KEY, false);
            assertEquals("", read(project), //$NON-NLS-1$
                    "a project value is inert until the override is enabled");

            projectPrefs.putBoolean(COMMITTED_ENABLE_KEY, true);
            assertEquals("dummy_tmp", read(project), //$NON-NLS-1$
                    "the enabled project value wins over the workspace value");

            store.setValue(PREF.PROJECT_INDEX_EXCLUDED_SCHEMAS, "reporting"); //$NON-NLS-1$
            assertEquals("dummy_tmp", read(project), //$NON-NLS-1$
                    "the project value also wins over a non-empty workspace value");

            projectPrefs.remove(COMMITTED_EXCLUSION_KEY);
            assertEquals("reporting", read(project), //$NON-NLS-1$
                    "an absent project key keeps inheriting the workspace value");
        } finally {
            projectPrefs.removeNode();
            if (oldGlobalDefault) {
                store.setToDefault(PREF.PROJECT_INDEX_EXCLUDED_SCHEMAS);
            } else {
                store.setValue(PREF.PROJECT_INDEX_EXCLUDED_SCHEMAS, oldGlobal);
            }
        }
    }

    @Test
    void aProjectExclusionChangesTheIndexIdentity() {
        var global = new GlobalSettings(true, false, false, false, ""); //$NON-NLS-1$
        var noOverride = new ProjectOverrides(null, null, null, null, null);
        var withExclusion = new ProjectOverrides(
                null, null, null, null, "dummy_tmp"); //$NON-NLS-1$

        String workspaceOnly = ProjectIndexConfiguration
                .resolve(DatabaseType.PG, false, global, withExclusion)
                .digest();
        String enabledWithoutValue = ProjectIndexConfiguration
                .resolve(DatabaseType.PG, true, global, noOverride).digest();
        String enabledWithValue = ProjectIndexConfiguration
                .resolve(DatabaseType.PG, true, global, withExclusion)
                .digest();

        assertNotEquals(enabledWithValue, workspaceOnly,
                "committing the file must retire indexes built without it");
        assertNotEquals(enabledWithValue, enabledWithoutValue);
        assertEquals("dummy_tmp", ProjectIndexConfiguration //$NON-NLS-1$
                .resolve(DatabaseType.PG, true, global, withExclusion)
                .excludedSchemas());
    }

    private static String read(IProject project) {
        return (String) new OverridablePrefs(project, null).get(
                PreferenceCategory.MAIN,
                PREF.PROJECT_INDEX_EXCLUDED_SCHEMAS);
    }
}
