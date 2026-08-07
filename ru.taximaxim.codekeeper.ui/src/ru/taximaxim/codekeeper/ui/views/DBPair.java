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
package ru.taximaxim.codekeeper.ui.views;

import org.pgcodekeeper.core.api.ComparisonDepth;
import org.pgcodekeeper.core.database.api.loader.ILoader;
import org.pgcodekeeper.core.settings.ISettings;

/**
 * The comparison a workbench selection event carries to whichever view or
 * pane wants to react to it - {@link DepcyGraphView} chief among them.
 *
 * @param depth how deeply {@code dbProject}/{@code dbRemote} were loaded, so a
 *              consumer can tell a structurally loaded comparison - which
 *              never resolved a dependency edge - from an ordinary one that
 *              simply has none. Always the depth of the comparison this exact
 *              pair was built from, not a preference read fresh by whoever
 *              constructs it: {@link ru.taximaxim.codekeeper.ui.editors.ProjectEditorDiffer}
 *              builds one instance from a not-yet-published candidate
 *              comparison whose depth can differ from the editor's own
 *              currently displayed one.
 */
public record DBPair(ILoader dbProject, ILoader dbRemote, ISettings settings, ComparisonDepth depth) {

}
