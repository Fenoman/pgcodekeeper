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

import org.eclipse.core.resources.IProject;
import org.pgcodekeeper.core.api.ComparisonDepth;

import ru.taximaxim.codekeeper.ui.UIConsts.PREF;
import ru.taximaxim.codekeeper.ui.prefs.PreferenceCategory;
import ru.taximaxim.codekeeper.ui.properties.OverridablePrefs;
import ru.taximaxim.codekeeper.ui.utils.ProjectUtils;

/**
 * The single place that answers whether a project only receives changes from a
 * database.
 * <p>
 * Every gate asks this rather than reading the preference itself, so the mode
 * cannot be on for one step of a run and off for the next. The preference
 * itself, {@link PREF#PROJECT_UPDATED_FROM_DATABASE_ONLY}, changes what a
 * comparison loads - {@link #depth(IProject)} answers that - and whether the
 * background index is built at all, which remains later work.
 * <p>
 * The read goes through {@link OverridablePrefs}, the same path every other
 * MAIN preference uses, rather than the project's raw {@code IEclipsePreferences}
 * node. A plain {@code IEclipsePreferences.getBoolean(key, false)} call on
 * that node only ever sees that one node: it does not fall back to the
 * workspace value, and it ignores the project's master
 * "enable project preferences" toggle
 * ({@code PROJ_PREF.ENABLE_PROJ_PREF_ROOT}) - a project whose properties
 * dialog was never opened would then read as off even though the workspace
 * default is on. Worse, {@code ProjectProperties.fillPrefs} writes every
 * field editor's value into that same project node on every OK regardless of
 * whether the master toggle is on, so the node can hold a stale copy of an
 * old workspace value; reading it directly can then resurrect a value the
 * user believes no longer applies. {@code OverridablePrefs} encodes the
 * actual rule: the workspace value, replaced by the project's own value only
 * when the project turned the override on.
 */
public final class ProjectReceiveOnlyMode {

    private ProjectReceiveOnlyMode() {
    }

    /**
     * @param project the project to check, may be {@code null}
     * @return {@code true} if the project exists, is a pgCodeKeeper project,
     *         and the preference resolves to on for it - the workspace value,
     *         unless the project turned its own override on; {@code false}
     *         otherwise, including for a {@code null} project, so callers
     *         never need a null check of their own
     */
    public static boolean isEnabled(IProject project) {
        if (!ProjectUtils.isPgCodeKeeperProject(project)) {
            return false;
        }
        return (boolean) new OverridablePrefs(project, null).get(
                PreferenceCategory.MAIN, PREF.PROJECT_UPDATED_FROM_DATABASE_ONLY);
    }

    /**
     * Answers how deep a comparison of this project should load.
     * <p>
     * Deliberately reuses {@link #isEnabled(IProject)} instead of reading the
     * preference a second time: there must be exactly one resolution of the
     * mode, or a future change to that resolution could leave this method and
     * {@code isEnabled} disagreeing about the same project.
     *
     * @param project the project to check, may be {@code null}
     * @return {@link ComparisonDepth#STRUCTURAL_ONLY} when {@link #isEnabled}
     *         resolves to on for this project - a receive-only project never
     *         has its own changes diffed against the database, so nothing
     *         ever needs the dependency graph a full load would resolve;
     *         {@link ComparisonDepth#FULL} otherwise
     */
    public static ComparisonDepth depth(IProject project) {
        return isEnabled(project) ? ComparisonDepth.STRUCTURAL_ONLY : ComparisonDepth.FULL;
    }
}
