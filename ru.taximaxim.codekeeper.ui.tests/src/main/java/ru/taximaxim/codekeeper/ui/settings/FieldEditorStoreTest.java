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
package ru.taximaxim.codekeeper.ui.settings;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.jface.preference.IPreferenceStore;
import org.junit.jupiter.api.Test;

class FieldEditorStoreTest {

    private static final String PREF_NAME = "pgCatalogCacheRows"; //$NON-NLS-1$

    @Test
    void missingProjectValueInheritsGlobalOptOut() {
        var editor = mock(ICustomFieldEditor.class);
        var projectPrefs = mock(IEclipsePreferences.class);
        var globalPrefs = mock(IPreferenceStore.class);
        when(editor.getPreferenceName()).thenReturn(PREF_NAME);
        when(projectPrefs.get(PREF_NAME, null)).thenReturn(null);

        var store = new FieldEditorStore();
        store.add(editor);
        store.loadProjectValues(projectPrefs, globalPrefs);

        verify(editor).setValue(globalPrefs);
        verifyNoInteractions(globalPrefs);
    }

    @Test
    void explicitProjectValueIsPreserved() {
        var editor = mock(ICustomFieldEditor.class);
        var projectPrefs = mock(IEclipsePreferences.class);
        var globalPrefs = mock(IPreferenceStore.class);
        when(editor.getPreferenceName()).thenReturn(PREF_NAME);
        when(projectPrefs.get(PREF_NAME, null)).thenReturn(Boolean.FALSE.toString());

        var store = new FieldEditorStore();
        store.add(editor);
        store.loadProjectValues(projectPrefs, globalPrefs);

        verify(editor).setValue(projectPrefs);
        verifyNoInteractions(globalPrefs);
    }
}
