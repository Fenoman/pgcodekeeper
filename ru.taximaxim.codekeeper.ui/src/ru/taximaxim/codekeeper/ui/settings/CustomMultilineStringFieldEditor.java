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

import java.util.function.Function;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Text;

import ru.taximaxim.codekeeper.ui.properties.OverridablePrefs;

/**
 * String preference editor that keeps line separators instead of flattening a
 * list into a single-line text control.
 */
public final class CustomMultilineStringFieldEditor extends StringFieldEditor
        implements ICustomFieldEditor<String> {

    private static final int DEFAULT_HEIGHT = 72;

    private String defaultValue = ""; //$NON-NLS-1$
    private Function<String, String> validator = value -> null;

    public CustomMultilineStringFieldEditor(String name, String label,
            Composite parent) {
        super(name, label, parent);
    }

    @Override
    protected Text createTextWidget(Composite parent) {
        return new Text(parent, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL
                | SWT.H_SCROLL);
    }

    @Override
    protected void doFillIntoGrid(Composite parent, int numColumns) {
        super.doFillIntoGrid(parent, numColumns);
        getLabelControl(parent).setLayoutData(new GridData(SWT.FILL,
                SWT.CENTER, true, false, numColumns, 1));
        Text text = getTextControl(parent);
        var data = new GridData(SWT.FILL, SWT.FILL, true, false,
                numColumns, 1);
        data.heightHint = DEFAULT_HEIGHT;
        data.widthHint = 320;
        text.setLayoutData(data);
    }

    @Override
    protected boolean doCheckState() {
        Function<String, String> currentValidator = validator;
        String error = currentValidator == null
                ? null
                : currentValidator.apply(getStringValue());
        setErrorMessage(error);
        return error == null;
    }

    @Override
    public int getNumberOfControls() {
        return 2;
    }

    @Override
    public Text getControl() {
        return getTextControl();
    }

    @Override
    public void setValue(String value) {
        setStringValue(value == null ? defaultValue : value);
    }

    @Override
    public String getValue() {
        return getStringValue();
    }

    @Override
    public void setDefaultValue(String value) {
        defaultValue = value == null ? "" : value; //$NON-NLS-1$
    }

    public void setValidator(Function<String, String> validator) {
        this.validator = validator == null ? value -> null : validator;
        refreshValidState();
    }

    @Override
    public void setValue(OverridablePrefs prefs) {
        setValue((String) prefs.get(getPreferenceName()));
    }

    @Override
    public void setValue(IEclipsePreferences prefs) {
        setValue(prefs.get(getPreferenceName(), defaultValue));
    }

    @Override
    public void setValue(IPreferenceStore mainPrefs) {
        String name = getPreferenceName();
        setValue(mainPrefs.contains(name)
                ? mainPrefs.getString(name)
                : defaultValue);
    }

    @Override
    public void fillValue(IEclipsePreferences prefs) {
        prefs.put(getPreferenceName(), getValue());
    }
}
