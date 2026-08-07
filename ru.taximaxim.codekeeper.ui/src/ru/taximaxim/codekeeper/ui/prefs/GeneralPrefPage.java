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

import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.IntegerFieldEditor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.pgcodekeeper.core.database.ch.parser.ChParserUtils;
import org.pgcodekeeper.core.database.ms.parser.MsParserUtils;
import org.pgcodekeeper.core.database.pg.parser.PgParserUtils;

import ru.taximaxim.codekeeper.ui.Activator;
import ru.taximaxim.codekeeper.ui.UIConsts.PREF;
import ru.taximaxim.codekeeper.ui.localizations.Messages;
import ru.taximaxim.codekeeper.ui.pgdbproject.parser.PgDbParser;
import ru.taximaxim.codekeeper.ui.settings.ParserWorkerPreferences;

public final class GeneralPrefPage extends FieldEditorPreferencePage
        implements IWorkbenchPreferencePage {

    private static final int GROUP_HORIZONTAL_SPAN = 2;

    private CatalogCachePrefGroup catalogCacheGroup;

    public GeneralPrefPage() {
        super(GRID);
    }

    @Override
    public void init(IWorkbench workbench) {
        setPreferenceStore(Activator.getDefault().getPreferenceStore());
    }

    @Override
    protected void createFieldEditors() {

        Preferences
            .build(PreferenceScope.GLOBAL, PreferenceCategory.MAIN, getFieldEditorParent(), null)
            .forEach(this::addField);

        new Label(getFieldEditorParent(), SWT.SEPARATOR | SWT.HORIZONTAL)
                .setLayoutData(new GridData(SWT.FILL, SWT.DEFAULT, true, false, 2, 1));

        addField(new BooleanFieldEditor(PREF.FORCE_SHOW_CONSOLE,
                Messages.generalPrefPage_show_console_when_program_write_to_console, getFieldEditorParent()));

        addField(new BooleanFieldEditor(PREF.REUSE_OPEN_COMPARE_EDITOR,
                Messages.GeneralPrefPage_reuse_open_compare_editor, getFieldEditorParent()));

        addField(new BooleanFieldEditor(PREF.IGNORE_CONCURRENT_MODIFICATION,
                Messages.GeneralPrefPage_ignore_concurrent_modification, getFieldEditorParent()));

        addField(new BooleanFieldEditor(PREF.PARALLEL_LOADING,
                Messages.GeneralPrefPage_use_parallel_load, getFieldEditorParent()));

        addParserWorkerField(PREF.GET_CHANGES_PARSER_WORKERS,
                Messages.GeneralPrefPage_get_changes_parser_workers);
        addParserWorkerField(PREF.PROJECT_INDEX_PARSER_WORKERS,
                Messages.GeneralPrefPage_project_index_parser_workers);

        addField(new BooleanFieldEditor(PREF.HEAP_SIZE_WARNING,
                Messages.GeneralPrefPage_alert_if_heap_size_less_than_necessary, getFieldEditorParent()));

        addField(new IntegerFieldEditor(PREF.PARSER_CACHE_CLEANING_INTERVAL,
                Messages.GeneralPrefPage_time_to_clean_parser_cache, getFieldEditorParent(), 3));

        Button button = new Button(getFieldEditorParent(), SWT.PUSH);
        button.setText(Messages.GeneralPrefPage_clean_parser_cache);
        button.addSelectionListener(new SelectionAdapter() {

            @Override
            public void widgetSelected(SelectionEvent e) {
                PgParserUtils.cleanCachePgParser();
                MsParserUtils.cleanCacheMsParser();
                ChParserUtils.cleanCacheChParser();
                System.gc();
                PgDbParser.cleanAll();
            }
        });

        // The in-memory parser cache above is cleared by one button click; the
        // persistent catalog cache needs its own block because measuring and
        // deleting it may take seconds on a large workspace.
        catalogCacheGroup = new CatalogCachePrefGroup(getFieldEditorParent(),
                GROUP_HORIZONTAL_SPAN);
    }

    @Override
    public void dispose() {
        if (catalogCacheGroup != null) {
            catalogCacheGroup.dispose();
            catalogCacheGroup = null;
        }
        super.dispose();
    }

    private void addParserWorkerField(String preferenceName, String label) {
        var field = new IntegerFieldEditor(preferenceName, label,
                getFieldEditorParent(), 2);
        field.setValidRange(ParserWorkerPreferences.MIN_WORKERS,
                ParserWorkerPreferences.MAX_WORKERS);
        field.getLabelControl(getFieldEditorParent()).setToolTipText(
                Messages.GeneralPrefPage_parser_workers_tooltip);
        field.getTextControl(getFieldEditorParent()).setToolTipText(
                Messages.GeneralPrefPage_parser_workers_tooltip);
        addField(field);
    }

    @Override
    public boolean performOk() {
        var before = PgDbParser.getGlobalProjectIndexConfiguration();
        if (!super.performOk()) {
            return false;
        }
        var after = PgDbParser.getGlobalProjectIndexConfiguration();
        PgDbParser.invalidateGlobalProjectIndexConfiguration(before, after);
        return true;
    }
}
