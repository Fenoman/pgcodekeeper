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

import static ru.taximaxim.codekeeper.ui.prefs.PreferenceScope.CUSTOM_APPLY_TO;
import static ru.taximaxim.codekeeper.ui.prefs.PreferenceScope.CUSTOM_GET_CHANGES;
import static ru.taximaxim.codekeeper.ui.prefs.PreferenceScope.DIFF_WIZARD;
import static ru.taximaxim.codekeeper.ui.prefs.PreferenceScope.GLOBAL;
import static ru.taximaxim.codekeeper.ui.prefs.PreferenceScope.PROJECT;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jface.preference.FieldEditor;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.swt.widgets.Composite;

import ru.taximaxim.codekeeper.ui.DatabaseType;
import ru.taximaxim.codekeeper.ui.UIConsts.DB_UPDATE_PREF;
import ru.taximaxim.codekeeper.ui.UIConsts.PREF;
import ru.taximaxim.codekeeper.ui.UIConsts.PROJ_PREF;
import ru.taximaxim.codekeeper.ui.localizations.Messages;

public enum Preferences {

    IGNORE_COLUMN_ORDER(
            new BooleanPreference(PREF.IGNORE_COLUMN_ORDER, PreferenceCategory.MAIN,
            Messages.GeneralPrefPage_ignore_column_order, null, null, true,
            Set.of(GLOBAL, PROJECT, DIFF_WIZARD, CUSTOM_GET_CHANGES))),
    // ClickHouse has no sequence of any kind, so the setting is offered to the
    // two dialects that do
    IGNORE_SEQUENCE_CACHE(
            new BooleanPreference(PREF.IGNORE_SEQUENCE_CACHE, PreferenceCategory.MAIN,
            Messages.GeneralPrefPage_ignore_sequence_cache, Messages.GeneralPrefPage_ignore_sequence_cache_tooltip,
            null, true, Set.of(DatabaseType.PG, DatabaseType.MS),
            Set.of(GLOBAL, PROJECT, DIFF_WIZARD, CUSTOM_GET_CHANGES))),
    // ONLY keeps a change away from the tables inheriting from the one named,
    // which is a PostgreSQL concept, so the setting is offered to that dialect
    // alone
    NO_ALTER_TABLE_ONLY(
            new BooleanPreference(PREF.NO_ALTER_TABLE_ONLY, PreferenceCategory.MAIN,
            Messages.GeneralPrefPage_no_alter_table_only, Messages.GeneralPrefPage_no_alter_table_only_tooltip,
            null, true, Set.of(DatabaseType.PG),
            Set.of(GLOBAL, PROJECT, DIFF_WIZARD, CUSTOM_GET_CHANGES))),
    // the statistics target lives in pg_attribute.attstattarget, so the setting
    // is offered to PostgreSQL alone
    IGNORE_COLUMN_STATISTICS(
            new BooleanPreference(PREF.IGNORE_COLUMN_STATISTICS, PreferenceCategory.MAIN,
            Messages.GeneralPrefPage_ignore_column_statistics,
            Messages.GeneralPrefPage_ignore_column_statistics_tooltip,
            null, true, Set.of(DatabaseType.PG),
            Set.of(GLOBAL, PROJECT, DIFF_WIZARD, CUSTOM_GET_CHANGES))),
    NO_PRIVILEGES(
            new BooleanPreference(PREF.NO_PRIVILEGES, PreferenceCategory.MAIN,
            Messages.dbUpdatePrefPage_ignore_privileges, null, null, true,
            Set.of(GLOBAL, PROJECT, DIFF_WIZARD, CUSTOM_GET_CHANGES))),
    ENABLE_BODY_DEPENDENCIES(
            new BooleanPreference(PREF.ENABLE_BODY_DEPENDENCIES, PreferenceCategory.MAIN,
                    Messages.GeneralPrefPage_enable_body_dependencies, Messages.GeneralPrefPage_body_depcy_tooltip,
                    null, true,
            Set.of(GLOBAL, PROJECT, DIFF_WIZARD, CUSTOM_GET_CHANGES))),
    PG_ROUTINE_BODY_SKIP_MATCHED_ANALYSIS(
            new BooleanPreference(PREF.PG_ROUTINE_BODY_SKIP_MATCHED_ANALYSIS, PreferenceCategory.MAIN,
                    Messages.GeneralPrefPage_pg_routine_body_skip_matched_analysis,
                    Messages.GeneralPrefPage_pg_routine_body_skip_matched_analysis_tooltip,
                    true, true, Set.of(DatabaseType.PG), Set.of(GLOBAL, PROJECT, CUSTOM_GET_CHANGES))),
    PG_CATALOG_CACHE_ROWS(
            new BooleanPreference(PREF.PG_CATALOG_CACHE_ROWS, PreferenceCategory.MAIN,
                    Messages.GeneralPrefPage_pg_catalog_cache_rows,
                    Messages.GeneralPrefPage_pg_catalog_cache_rows_tooltip,
                    true, true, Set.of(DatabaseType.PG), Set.of(GLOBAL, PROJECT, CUSTOM_GET_CHANGES))),
    PROJECT_INDEX_EXCLUDED_SCHEMAS(
            new MultilineStringPreference(PREF.PROJECT_INDEX_EXCLUDED_SCHEMAS,
                    PreferenceCategory.MAIN,
                    Messages.GeneralPrefPage_project_index_excluded_schemas,
                    Messages.GeneralPrefPage_project_index_excluded_schemas_tooltip,
                    "", false, EnumSet.allOf(DatabaseType.class),
                    Set.of(GLOBAL, PROJECT))),
    // every project type has a background index, and neither setting decides
    // anything about a comparison, so both are offered to every dialect and
    // stay out of the comparison scopes
    PROJECT_INDEX_INCREMENTAL_ADDED_FILES(
            new BooleanPreference(PREF.PROJECT_INDEX_INCREMENTAL_ADDED_FILES,
                    PreferenceCategory.MAIN,
                    Messages.GeneralPrefPage_project_index_incremental_added_files,
                    Messages.GeneralPrefPage_project_index_incremental_added_files_tooltip,
                    false, false, EnumSet.allOf(DatabaseType.class),
                    Set.of(GLOBAL, PROJECT))),
    // the mode changes what a comparison loads and whether the background index
    // is built at all, and neither is a question about one comparison, so it is
    // offered to every dialect and stays out of the comparison scopes
    PROJECT_UPDATED_FROM_DATABASE_ONLY(
            new BooleanPreference(PREF.PROJECT_UPDATED_FROM_DATABASE_ONLY,
                    PreferenceCategory.MAIN,
                    Messages.GeneralPrefPage_project_updated_from_database_only,
                    Messages.GeneralPrefPage_project_updated_from_database_only_tooltip,
                    false, false, EnumSet.allOf(DatabaseType.class),
                    Set.of(GLOBAL, PROJECT))),
    // an ordering of the project tree, so it belongs to the workbench and not
    // to a project or to a comparison: the same person looking at the same
    // folder wants the same order whichever project it is in
    GROUP_PARTITIONS_IN_TREE(
            new BooleanPreference(PREF.GROUP_PARTITIONS_IN_TREE,
                    PreferenceCategory.MAIN,
                    Messages.GeneralPrefPage_group_partitions_in_tree,
                    Messages.GeneralPrefPage_group_partitions_in_tree_tooltip,
                    true, false, EnumSet.allOf(DatabaseType.class),
                    Set.of(GLOBAL))),
    // off by default, unlike the ordering above: this one takes files out of
    // the folder they live in, and that is not something to discover
    NEST_PARTITIONS_UNDER_PARENT(
            new BooleanPreference(PREF.NEST_PARTITIONS_UNDER_PARENT,
                    PreferenceCategory.MAIN,
                    Messages.GeneralPrefPage_nest_partitions_under_parent,
                    Messages.GeneralPrefPage_nest_partitions_under_parent_tooltip,
                    false, false, EnumSet.allOf(DatabaseType.class),
                    Set.of(GLOBAL))),
    SIMPLIFY_NOT_NULL(
            new BooleanPreference(PREF.SIMPLIFY_NOT_NULL, PreferenceCategory.MAIN,
            Messages.GeneralPrefPage_simplify_not_null, null, null, true, Set.of(DatabaseType.PG),
            Set.of(GLOBAL, PROJECT, DIFF_WIZARD, CUSTOM_GET_CHANGES))),
    SIMPLIFY_VIEW(
            new BooleanPreference(PREF.SIMPLIFY_VIEW, PreferenceCategory.MAIN,
            Messages.GeneralPrefPage_simplify_view, null, null, true, Set.of(DatabaseType.PG),
            Set.of(GLOBAL, PROJECT, DIFF_WIZARD, CUSTOM_GET_CHANGES))),
    FORMAT_OBJECT_CODE_AUTOMATICALLY(
            new BooleanPreference(PREF.FORMAT_OBJECT_CODE_AUTOMATICALLY, PreferenceCategory.MAIN,
                    Messages.GeneralPrefPage_format_object_code_automatically, null, null, true,
            Set.of(GLOBAL, CUSTOM_GET_CHANGES))),
    USE_GLOBAL_IGNORE_LIST(
            new BooleanPreference(PROJ_PREF.USE_GLOBAL_IGNORE_LIST, PreferenceCategory.MAIN,
            Messages.ProjectProperties_use_global_ignore_list, null, true, true,
            Set.of(PROJECT, DIFF_WIZARD, CUSTOM_GET_CHANGES))),
    SCRIPT_IN_TRANSACTION(
            new BooleanPreference(DB_UPDATE_PREF.SCRIPT_IN_TRANSACTION, PreferenceCategory.DB_UPDATE,
            Messages.DbUpdatePrefPage_script_add_transaction,
            Set.of(GLOBAL, PROJECT, CUSTOM_APPLY_TO, DIFF_WIZARD))),
    DISABLE_CHECK_FUNCTION_BODIES(
            new BooleanPreference(DB_UPDATE_PREF.DISABLE_CHECK_FUNCTION_BODIES, PreferenceCategory.DB_UPDATE,
            Messages.dbUpdatePrefPage_check_function_bodies, null, null, false, Set.of(DatabaseType.PG),
            Set.of(GLOBAL, PROJECT, DIFF_WIZARD, CUSTOM_APPLY_TO))),
    PRINT_USING(
            new BooleanPreference(DB_UPDATE_PREF.PRINT_USING, PreferenceCategory.DB_UPDATE,
            Messages.dbUpdatePrefPage_switch_on_off_using, null, true, false, Set.of(DatabaseType.PG),
            Set.of(GLOBAL, PROJECT, DIFF_WIZARD, CUSTOM_APPLY_TO))),
    PRINT_INDEX_WITH_CONCURRENTLY(
            new BooleanPreference(DB_UPDATE_PREF.PRINT_INDEX_WITH_CONCURRENTLY, PreferenceCategory.DB_UPDATE,
            Messages.DbUpdatePrefPage_print_index_with_concurrently,
            Set.of(GLOBAL, PROJECT, DIFF_WIZARD, CUSTOM_APPLY_TO))),
    PRINT_CONSTRAINT_NOT_VALID(
            new BooleanPreference(DB_UPDATE_PREF.PRINT_CONSTRAINT_NOT_VALID, PreferenceCategory.DB_UPDATE,
            Messages.ApplyCustomDialog_constraint_not_valid, null, null, false, Set.of(DatabaseType.PG),
            Set.of(GLOBAL, PROJECT, DIFF_WIZARD, CUSTOM_APPLY_TO))),
    SCRIPT_FROM_SELECTED_OBJS(
            new BooleanPreference(DB_UPDATE_PREF.SCRIPT_FROM_SELECTED_OBJS, PreferenceCategory.DB_UPDATE,
            Messages.DbUpdatePrefPage_script_from_selected_objs,
            Set.of(GLOBAL, PROJECT, DIFF_WIZARD, CUSTOM_APPLY_TO))),
    GENERATE_EXISTS(
            new BooleanPreference(DB_UPDATE_PREF.GENERATE_EXISTS, PreferenceCategory.DB_UPDATE,
            Messages.DbUpdatePrefPage_option_if_exists,
            Set.of(GLOBAL, PROJECT, DIFF_WIZARD, CUSTOM_APPLY_TO))),
    GENERATE_EXIST_DO_BLOCK(
            new BooleanPreference(DB_UPDATE_PREF.GENERATE_EXIST_DO_BLOCK, PreferenceCategory.DB_UPDATE,
            Messages.DbUpdatePrefPage_generate_exist_do_block, null, null, false, Set.of(DatabaseType.PG),
            Set.of(GLOBAL, PROJECT, DIFF_WIZARD, CUSTOM_APPLY_TO))),
    DROP_BEFORE_CREATE(
            new BooleanPreference(DB_UPDATE_PREF.DROP_BEFORE_CREATE, PreferenceCategory.DB_UPDATE,
            Messages.DbUpdatePrefPage_option_drop_object,
            Set.of(GLOBAL, PROJECT, DIFF_WIZARD, CUSTOM_APPLY_TO))),
    ADD_PRE_POST_SCRIPT(
            new BooleanPreference(DB_UPDATE_PREF.ADD_PRE_POST_SCRIPT, PreferenceCategory.DB_UPDATE,
            Messages.DbUpdatePrefPage_add_pre_post_script,
            Set.of(PROJECT, CUSTOM_APPLY_TO))),
    COMMENTS_TO_END(
            new BooleanPreference(DB_UPDATE_PREF.COMMENTS_TO_END, PreferenceCategory.DB_UPDATE,
            Messages.DbUpdatePrefPage_comments_to_end, null, null, false, Set.of(DatabaseType.PG),
            Set.of(GLOBAL, PROJECT, DIFF_WIZARD, CUSTOM_APPLY_TO))),
    DATA_MOVEMENT_MODE(
            new BooleanPreference(DB_UPDATE_PREF.DATA_MOVEMENT_MODE, PreferenceCategory.DB_UPDATE,
            Messages.DbUpdatePrefPage_allow_data_movement,
            Set.of(GLOBAL, PROJECT, DIFF_WIZARD, CUSTOM_APPLY_TO))),
    USE_ACTUAL_VERSION_SYNTAX(
            new BooleanPreference(DB_UPDATE_PREF.USE_ACTUAL_VERSION_SYNTAX,
            PreferenceCategory.DB_UPDATE, Messages.dbUpdatePrefPage_use_actual_version_syntax,
            Set.of(GLOBAL, PROJECT, DIFF_WIZARD, CUSTOM_APPLY_TO))),
    CLUSTER_NAME(
            new StringPreference(DB_UPDATE_PREF.CLUSTER_NAME, PreferenceCategory.DB_UPDATE,
            Messages.DbUpdatePrefPage_cluster_name, null, null, false, Set.of(DatabaseType.CH),
            Set.of(PROJECT, CUSTOM_APPLY_TO)));

    public enum ChangeImpact {
        NONE,
        COMPARISON,
        PROJECT_INDEX,
        BOTH
    }

    private AbstractPreference<?> preference;

    private Preferences(AbstractPreference<?> preference) {
        this.preference = preference;
    }

    public static void initialize(IPreferenceStore store) {
        for (var value : values()) {
            var preference = value.preference;
            if (preference.getInitialValue() != null) {
                preference.initialize(store);
            }
        }
        store.setDefault(PREF.PARALLEL_LOADING, true);
    }

    public static PreferenceCategory getCategory(String preferenceName) {
        for (var value : values()) {
            if (value.getPreferenceName().equals(preferenceName)) {
                return value.preference.getCategory();
            }
        }
        throw new IllegalArgumentException();
    }

    public static List<FieldEditor> build(PreferenceScope scope, PreferenceCategory category, Composite parent,
            DatabaseType dbType) {
        List<FieldEditor> list = new ArrayList<>();
        for (var value : values()) {
            var preference = value.preference;
            if (category == preference.getCategory() && preference.inScope(scope)
                    && preference.isNeedForThisDbType(dbType, scope)) {
                list.add(preference.create(parent, scope));
            }
        }
        return list;
    }

    public static List<AbstractPreference<?>> getPreferencesForScope(PreferenceCategory category, PreferenceScope scope,
            DatabaseType dbType) {
        List<AbstractPreference<?>> list = new ArrayList<>();
        for (var value : values()) {
            AbstractPreference<?> preference = value.preference;
            if (category == preference.getCategory() && preference.inScope(scope)
                    && preference.isNeedForThisDbType(dbType, scope)) {
                list.add(preference);
            }
        }
        return list;
    }

    public static Set<String> getPreferencesWithNeedReset() {
        Set<String> prefs = new HashSet<>();
        for (var value : values()) {
            var preference = value.preference;
            if (preference.isNeedReset) {
                prefs.add(preference.getPreferenceName());
            }
        }

        prefs.add(PREF.IGNORE_CONCURRENT_MODIFICATION);
        prefs.add(PROJ_PREF.ENABLE_PROJ_PREF_ROOT);
        return prefs;
    }

    public static ChangeImpact getChangeImpact(String preferenceName) {
        if (PREF.PROJECT_INDEX_EXCLUDED_SCHEMAS.equals(preferenceName)
                || PREF.PROJECT_INDEX_INCREMENTAL_ADDED_FILES
                        .equals(preferenceName)) {
            return ChangeImpact.PROJECT_INDEX;
        }
        if (PREF.NO_PRIVILEGES.equals(preferenceName)
                || PREF.ENABLE_BODY_DEPENDENCIES.equals(preferenceName)
                || PROJ_PREF.ENABLE_PROJ_PREF_ROOT.equals(preferenceName)
                // changes what a comparison loads and whether the background
                // index is built at all
                || PREF.PROJECT_UPDATED_FROM_DATABASE_ONLY.equals(preferenceName)) {
            return ChangeImpact.BOTH;
        }
        return getPreferencesWithNeedReset().contains(preferenceName)
                ? ChangeImpact.COMPARISON
                : ChangeImpact.NONE;
    }

    public static void routeChange(String preferenceName,
            Runnable projectIndexInvalidation, Runnable comparisonReset) {
        switch (getChangeImpact(preferenceName)) {
        case PROJECT_INDEX -> projectIndexInvalidation.run();
        case COMPARISON -> comparisonReset.run();
        case BOTH -> {
            projectIndexInvalidation.run();
            comparisonReset.run();
        }
        case NONE -> {
            // No active state depends on this preference.
        }
        }
    }

    public String getPreferenceName() {
        return preference.getPreferenceName();
    }
}
