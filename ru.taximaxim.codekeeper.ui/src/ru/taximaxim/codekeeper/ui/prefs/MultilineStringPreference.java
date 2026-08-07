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

import java.util.Set;

import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.swt.widgets.Composite;

import ru.taximaxim.codekeeper.ui.DatabaseType;
import ru.taximaxim.codekeeper.ui.localizations.Messages;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexSchemaExclusions;
import ru.taximaxim.codekeeper.ui.settings.CustomMultilineStringFieldEditor;

final class MultilineStringPreference extends AbstractPreference<String> {

    MultilineStringPreference(String preferenceName,
            PreferenceCategory category, String label, String toolTipText,
            String initialValue, boolean isNeedReset,
            Set<DatabaseType> validDbTypes, Set<PreferenceScope> scopes) {
        super(preferenceName, category, label, toolTipText, initialValue,
                isNeedReset, validDbTypes, scopes);
    }

    @Override
    protected void initialize(IPreferenceStore store) {
        store.setDefault(preferenceName, initialValue);
    }

    @Override
    protected CustomMultilineStringFieldEditor create(Composite parent,
            PreferenceScope scope) {
        var field = new CustomMultilineStringFieldEditor(preferenceName,
                label, parent);
        field.setDefaultValue(initialValue);
        field.setValidator(MultilineStringPreference::validateSchemaExclusions);
        if (toolTipText != null) {
            field.getControl().setToolTipText(toolTipText);
            field.getLabelControl(parent).setToolTipText(toolTipText);
        }
        return field;
    }

    private static String validateSchemaExclusions(String value) {
        return ProjectIndexSchemaExclusions.validate(value)
                .map(error -> switch (error.reason()) {
                case DOTTED ->
                    Messages.GeneralPrefPage_project_index_excluded_schemas_invalid_dotted
                            .formatted(error.lineNumber(), error.value());
                case FILESYSTEM_UNSAFE ->
                    Messages.GeneralPrefPage_project_index_excluded_schemas_invalid_filesystem
                            .formatted(error.lineNumber(), error.value());
                })
                .orElse(null);
    }
}
