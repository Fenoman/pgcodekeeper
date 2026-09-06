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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ProjectScope;
import org.pgcodekeeper.core.Consts;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.base.formatter.FormatConfiguration;
import org.pgcodekeeper.core.database.base.parser.ParserExecutionPolicy;
import org.pgcodekeeper.core.ignorelist.IgnoreList;
import org.pgcodekeeper.core.settings.AbstractSettings;
import org.pgcodekeeper.core.settings.ISettings;

import ru.taximaxim.codekeeper.ui.Activator;
import ru.taximaxim.codekeeper.ui.DatabaseType;
import ru.taximaxim.codekeeper.ui.UIConsts;
import ru.taximaxim.codekeeper.ui.UIConsts.DB_UPDATE_PREF;
import ru.taximaxim.codekeeper.ui.UIConsts.FILE;
import ru.taximaxim.codekeeper.ui.UIConsts.PREF;
import ru.taximaxim.codekeeper.ui.UIConsts.PROJ_PREF;
import ru.taximaxim.codekeeper.ui.formatter.Formatter;
import ru.taximaxim.codekeeper.ui.prefs.PrePostScriptPrefPage;
import ru.taximaxim.codekeeper.ui.prefs.PreferenceCategory;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexBuildConfiguration;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexConfiguration;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexSchemaExclusions;
import ru.taximaxim.codekeeper.ui.properties.OverridablePrefs;
import ru.taximaxim.codekeeper.ui.utils.ProjectUtils;

public final class UISettings extends AbstractSettings {

    private static final int GET_CHANGES_JDBC_FETCH_SIZE = 512;
    private static final int GET_CHANGES_PG_PARALLEL_CATALOG_READERS = 3;
    private static final String PG_CATALOG_CACHE_DIR_NAME = "pg-catalog-row-cache"; //$NON-NLS-1$

    private static final String[] MAIN_BOOLEAN_PREFS = {
            PREF.FORMAT_OBJECT_CODE_AUTOMATICALLY,
            PREF.NO_PRIVILEGES,
            PREF.IGNORE_COLUMN_ORDER,
            PREF.IGNORE_SEQUENCE_CACHE,
            PREF.NO_ALTER_TABLE_ONLY,
            PREF.IGNORE_COLUMN_STATISTICS,
            PREF.ENABLE_BODY_DEPENDENCIES,
            PREF.IGNORE_CONCURRENT_MODIFICATION,
            PREF.PARALLEL_LOADING,
            PREF.SIMPLIFY_VIEW,
            PREF.SIMPLIFY_NOT_NULL,
            PREF.PG_ROUTINE_BODY_SKIP_MATCHED_ANALYSIS,
            PREF.PG_CATALOG_CACHE_ROWS,
            PREF.PROJECT_INDEX_INCREMENTAL_ADDED_FILES
    };

    private static final String[] MAIN_STRING_PREFS = {
            PREF.PROJECT_INDEX_EXCLUDED_SCHEMAS
    };

    private static final String[] DB_UPDATE_BOOLEAN_PREFS = {
            DB_UPDATE_PREF.PRINT_INDEX_WITH_CONCURRENTLY,
            DB_UPDATE_PREF.SCRIPT_IN_TRANSACTION,
            DB_UPDATE_PREF.GENERATE_EXISTS,
            DB_UPDATE_PREF.PRINT_CONSTRAINT_NOT_VALID,
            DB_UPDATE_PREF.GENERATE_EXIST_DO_BLOCK,
            DB_UPDATE_PREF.PRINT_USING,
            DB_UPDATE_PREF.COMMENTS_TO_END,
            DB_UPDATE_PREF.DATA_MOVEMENT_MODE,
            DB_UPDATE_PREF.DROP_BEFORE_CREATE,
            DB_UPDATE_PREF.SCRIPT_FROM_SELECTED_OBJS,
            DB_UPDATE_PREF.DISABLE_CHECK_FUNCTION_BODIES,
            DB_UPDATE_PREF.ADD_PRE_POST_SCRIPT,
            DB_UPDATE_PREF.USE_ACTUAL_VERSION_SYNTAX
    };

    private final String timeZone;
    private String inCharsetName = Consts.UTF_8;
    private int jdbcFetchSize;
    private boolean collectObjectReferences = true;

    private final IProject project;
    private final DatabaseType dbType;
    private final FormatConfiguration formatConfiguration;
    private final List<String> preFilePaths;
    private final List<String> postFilePaths;
    private volatile Map<String, Object> preferenceSnapshot;

    public UISettings(IProject project, Map<String, Object> oneTimePS, DatabaseType dbType) {
        this.project = project;
        this.preferenceSnapshot = snapshotPreferences(project, oneTimePS);
        this.formatConfiguration = Formatter.getFormatterConfig();

        if (project != null) {
            var projPS = new ProjectScope(project).getNode(UIConsts.PLUGIN_ID.THIS);
            this.timeZone = projPS.get(PROJ_PREF.TIMEZONE, Consts.UTC);
        } else {
            this.timeZone = null;
        }

        if (dbType != null) {
            this.dbType = dbType;
        } else if (project != null) {
            this.dbType = ProjectUtils.getDatabaseType(project);
        } else {
            this.dbType = DatabaseType.PG;
        }

        if (getDbUpdateBoolean(DB_UPDATE_PREF.ADD_PRE_POST_SCRIPT)) {
            this.preFilePaths = addPathsIfExists(FILE.PRE_DIR, FILE.PRE_SCRIPT);
            this.postFilePaths = addPathsIfExists(FILE.POST_DIR, FILE.POST_SCRIPT);
        } else {
            this.preFilePaths = List.of();
            this.postFilePaths = List.of();
        }
    }

    public UISettings(IProject project) {
        this(project, null, null);
    }

    private UISettings(UISettings source) {
        this.project = source.project;
        this.dbType = source.dbType;
        this.timeZone = source.timeZone;
        this.inCharsetName = source.inCharsetName;
        this.jdbcFetchSize = source.jdbcFetchSize;
        this.collectObjectReferences = source.collectObjectReferences;
        this.preferenceSnapshot = source.preferenceSnapshot;
        this.formatConfiguration = source.formatConfiguration.copy();
        this.preFilePaths = source.preFilePaths;
        this.postFilePaths = source.postFilePaths;
    }

    public static UISettings forGetChanges(IProject project, Map<String, Object> oneTimePS, DatabaseType dbType) {
        return forGetChanges(project, oneTimePS, dbType, null);
    }

    public static UISettings forProjectIndex(IProject project) {
        return forProjectIndex(project, null, null);
    }

    /**
     * Settings a project-index build works by, bound to the one configuration
     * that build was captured with.
     * <p>
     * Nothing about the index is read from the preference node here. The build
     * already read it, once, and what it read is what stamps the identity of
     * the index; if these settings read it a second time they would answer to
     * a different moment, and the build would enumerate one project while
     * claiming the identity of another. Every index preference therefore comes
     * from {@code build} and overrides whatever the node says now.
     *
     * @param project the project being indexed
     * @param build   the configuration this build was captured with
     * @return settings that enumerate and parse by that one configuration
     */
    public static UISettings forProjectIndex(IProject project,
            ProjectIndexBuildConfiguration build) {
        Objects.requireNonNull(build, "build"); //$NON-NLS-1$
        var settings = new UISettings(project, null,
                build.configuration().databaseType());
        settings.overrideProjectIndexPreferences(build.configuration());
        settings.setParserExecutionPolicy(
                ParserExecutionPolicy.dedicated(
                        ParserWorkerPreferences.getProjectIndexWorkers()));
        settings.setAdditionalExcludedSchemas(build.excludedSchemas());
        return settings;
    }

    static UISettings forProjectIndex(IProject project,
            Map<String, Object> oneTimePS, DatabaseType dbType) {
        var settings = new UISettings(project, oneTimePS, dbType);
        settings.setParserExecutionPolicy(
                ParserExecutionPolicy.dedicated(
                        ParserWorkerPreferences.getProjectIndexWorkers()));
        settings.setAdditionalExcludedSchemas(parseSchemaExclusions(
                (String) settings.preferenceSnapshot.get(
                        PREF.PROJECT_INDEX_EXCLUDED_SCHEMAS)));
        return settings;
    }

    /**
     * Replaces every preference the project index is identified by <em>and</em>
     * loads files by with the value the build captured. The resolution the
     * snapshot performed and the one the captured configuration performed are
     * the same rules over the same two stores, so on a configuration that is
     * standing still this changes nothing; on one that is moving it is the
     * difference between one build and two.
     * <p>
     * {@code receiveOnly} is the one component of the identity with nothing to
     * replace here. It decides whether a build happens at all - {@code
     * ProjectBuilder.prepareBuild} refuses before any of this is reached - and
     * no loader ever asks for it, so the snapshot does not carry the key.
     */
    private void overrideProjectIndexPreferences(
            ProjectIndexConfiguration configuration) {
        Map<String, Object> updated = new HashMap<>(preferenceSnapshot);
        updated.put(PREF.NO_PRIVILEGES, configuration.ignorePrivileges());
        updated.put(PREF.ENABLE_BODY_DEPENDENCIES,
                configuration.bodyDependencies());
        updated.put(PREF.PROJECT_INDEX_INCREMENTAL_ADDED_FILES,
                configuration.incrementalAddedFiles());
        updated.put(PREF.PROJECT_INDEX_EXCLUDED_SCHEMAS,
                configuration.excludedSchemas());
        preferenceSnapshot = Map.copyOf(updated);
    }

    static UISettings forGetChanges(IProject project, Map<String, Object> oneTimePS, DatabaseType dbType,
            Path catalogCacheRoot) {
        var settings = new UISettings(project, oneTimePS, dbType);
        settings.configureForGetChanges(catalogCacheRoot);
        return settings;
    }

    /**
     * Settings for writing objects of a database into the files of a project.
     * <p>
     * They carry the rules the project hides by, because the writer answers to
     * them: a column a {@code type=COLUMN} rule hides is not pgCodeKeeper's to
     * write and never enters a project file. Plain {@link #UISettings(IProject)}
     * reports an empty list, so an export built from it would quietly write back
     * the very columns the project declared it does not manage.
     *
     * @param project the project the objects are written into
     * @return settings for the export
     */
    public static UISettings forExport(IProject project) {
        return forExport(project, ProjectIgnoreLists.read(project, null, null));
    }

    /**
     * Settings for an export whose rules are already known - the comparison the
     * user is applying assembled them, and the export must hide by the same ones.
     *
     * @param project the project the objects are written into
     * @param rules   the rules the project hides by
     * @return settings for the export
     */
    public static UISettings forExport(IProject project, IgnoreList rules) {
        var settings = new UISettings(project);
        ProjectIgnoreLists.install(settings, rules);
        return settings;
    }

    public EclipseComparisonTelemetry getComparisonRunTelemetry() {
        var telemetry = getComparisonTelemetry();
        return telemetry instanceof EclipseComparisonTelemetry eclipseTelemetry
                ? eclipseTelemetry : null;
    }

    private void configureForGetChanges(Path catalogCacheRoot) {
        setComparisonTelemetry(new EclipseComparisonTelemetry());
        // The project editor compares the project against the database, that is
        // (old = project, right = database): DiffSide.LEFT is the project side
        // all over the editor. The migration script it offers runs the other
        // way, from the database to the project, and is generated from a
        // reverted copy of this tree. Its target is therefore the old side.
        setMigrationTargetOldSide(true);
        if (dbType != DatabaseType.PG) {
            return;
        }

        // Comparison uses semantic dependencies, while editor navigation has
        // its own project index and reference locations.
        collectObjectReferences = false;
        setParserExecutionPolicy(
                ParserExecutionPolicy.dedicated(
                        ParserWorkerPreferences.getChangesWorkers()));
        setPgCatalogCacheMaxMb(ISettings.DEFAULT_PG_CATALOG_CACHE_MAX_MB);
        // The preference is about catalog rows only. The routine-body store
        // and the fingerprint probe are separate mechanisms and must not be
        // switched by it silently.
        boolean catalogCacheRows = getMainBoolean(PREF.PG_CATALOG_CACHE_ROWS);
        setPgCatalogCacheRows(catalogCacheRows);
        setPgCatalogCacheFingerprintProbe(catalogCacheRows);

        boolean parallelLoad = isParallelLoad();
        boolean hashFirst = parallelLoad
                || ISettings.DEFAULT_PG_ROUTINE_BODY_HASH_FIRST;
        if (hashFirst || catalogCacheRows) {
            // Hash-first loading persists routine-body hashes, so it needs the
            // store even when catalog rows are not cached.
            setPgCatalogCacheDir(catalogCacheDir(catalogCacheRoot).toString());
        }

        if (!parallelLoad) {
            setPgRoutineBodyHashFirst(ISettings.DEFAULT_PG_ROUTINE_BODY_HASH_FIRST);
            setPgRoutineBodySkipMatchedAnalysis(false);
            setPgParallelCatalogReaders(ISettings.DEFAULT_PG_PARALLEL_CATALOG_READERS);
            jdbcFetchSize = 0;
            return;
        }

        setPgRoutineBodyHashFirst(true);
        setPgRoutineBodySkipMatchedAnalysis(getMainBoolean(PREF.PG_ROUTINE_BODY_SKIP_MATCHED_ANALYSIS));
        setPgParallelCatalogReaders(GET_CHANGES_PG_PARALLEL_CATALOG_READERS);
        jdbcFetchSize = GET_CHANGES_JDBC_FETCH_SIZE;
    }

    private static Path catalogCacheDir(Path catalogCacheRoot) {
        Path cacheRoot = catalogCacheRoot == null
                ? Paths.get(Activator.getDefault().getStateLocation()
                        .append(PG_CATALOG_CACHE_DIR_NAME).toOSString())
                : catalogCacheRoot;
        CatalogCacheMaintenance.scheduleOnce(cacheRoot);
        return cacheRoot;
    }

    @Override
    public boolean isConcurrentlyMode() {
        return getDbUpdateBoolean(DB_UPDATE_PREF.PRINT_INDEX_WITH_CONCURRENTLY);
    }

    @Override
    public boolean isAddTransaction() {
        return getDbUpdateBoolean(DB_UPDATE_PREF.SCRIPT_IN_TRANSACTION);
    }

    @Override
    public boolean isGenerateExists() {
        return getDbUpdateBoolean(DB_UPDATE_PREF.GENERATE_EXISTS);
    }

    @Override
    public boolean isGenerateConstraintNotValid() {
        return getDbUpdateBoolean(DB_UPDATE_PREF.PRINT_CONSTRAINT_NOT_VALID);
    }

    @Override
    public boolean isGenerateExistDoBlock() {
        return getDbUpdateBoolean(DB_UPDATE_PREF.GENERATE_EXIST_DO_BLOCK);
    }

    @Override
    public boolean isPrintUsing() {
        return getDbUpdateBoolean(DB_UPDATE_PREF.PRINT_USING);
    }

    @Override
    public boolean isKeepNewlines() {
        return !(boolean) preferenceSnapshot.get(PROJ_PREF.FORCE_UNIX_NEWLINES);
    }

    @Override
    public boolean isCommentsToEnd() {
        return getDbUpdateBoolean(DB_UPDATE_PREF.COMMENTS_TO_END);
    }

    @Override
    public boolean isAutoFormatObjectCode() {
        return getMainBoolean(PREF.FORMAT_OBJECT_CODE_AUTOMATICALLY);
    }

    @Override
    public boolean isIgnorePrivileges() {
        return getMainBoolean(PREF.NO_PRIVILEGES);
    }

    @Override
    public boolean isIgnoreColumnOrder() {
        return getMainBoolean(PREF.IGNORE_COLUMN_ORDER);
    }

    @Override
    public boolean isIgnoreSequenceCache() {
        return getMainBoolean(PREF.IGNORE_SEQUENCE_CACHE);
    }

    @Override
    public boolean isNoAlterTableOnly() {
        return getMainBoolean(PREF.NO_ALTER_TABLE_ONLY);
    }

    @Override
    public boolean isIgnoreColumnStatistics() {
        return getMainBoolean(PREF.IGNORE_COLUMN_STATISTICS);
    }

    @Override
    public boolean isEnableFunctionBodiesDependencies() {
        return getMainBoolean(PREF.ENABLE_BODY_DEPENDENCIES);
    }

    /**
     * Tells whether a batch that adds files may be reindexed incrementally
     * instead of forcing a full rebuild of the project index. Not a Core
     * setting: it decides nothing about a comparison and only opens a build
     * path.
     *
     * @return true when the added-files path is open
     */
    public boolean isProjectIndexIncrementalAddedFiles() {
        return getMainBoolean(PREF.PROJECT_INDEX_INCREMENTAL_ADDED_FILES);
    }

    @Override
    public boolean isDataMovementMode() {
        return getDbUpdateBoolean(DB_UPDATE_PREF.DATA_MOVEMENT_MODE);
    }

    @Override
    public boolean isDropBeforeCreate() {
        return getDbUpdateBoolean(DB_UPDATE_PREF.DROP_BEFORE_CREATE);
    }

    @Override
    public boolean isStopNotAllowed() {
        return false;
    }

    @Override
    public boolean isSelectedOnly() {
        return getDbUpdateBoolean(DB_UPDATE_PREF.SCRIPT_FROM_SELECTED_OBJS);
    }

    @Override
    public boolean isIgnoreConcurrentModification() {
        return getMainBoolean(PREF.IGNORE_CONCURRENT_MODIFICATION);
    }

    @Override
    public boolean isParallelLoad() {
        return getMainBoolean(PREF.PARALLEL_LOADING);
    }

    @Override
    public int getJdbcFetchSize() {
        return jdbcFetchSize;
    }

    @Override
    public boolean isCollectObjectReferences() {
        return collectObjectReferences;
    }

    @Override
    public boolean isSimplifyView() {
        return getMainBoolean(PREF.SIMPLIFY_VIEW);
    }

    @Override
    public String getInCharsetName() {
        return inCharsetName;
    }

    @Override
    public String getTimeZone() {
        return timeZone;
    }

    @Override
    public FormatConfiguration getFormatConfiguration() {
        return formatConfiguration.copy();
    }

    @Override
    public Collection<DbObjType> getAllowedTypes() {
        return Collections.emptyList();
    }

    @Override
    public boolean isDisableCheckFunctionBodies() {
        return getDbUpdateBoolean(DB_UPDATE_PREF.DISABLE_CHECK_FUNCTION_BODIES);
    }

    @Override
    public Collection<String> getPreFilePath() {
        return preFilePaths;
    }

    @Override
    public Collection<String> getPostFilePath() {
        return postFilePaths;
    }

    @Override
    public String getClusterName() {
        return (String) preferenceSnapshot.get(DB_UPDATE_PREF.CLUSTER_NAME);
    }

    private static Map<String, Object> snapshotPreferences(IProject project, Map<String, Object> oneTimePS) {
        Map<String, Object> oneTimeSnapshot = oneTimePS == null ? null : new HashMap<>(oneTimePS);
        var prefs = new OverridablePrefs(project, oneTimeSnapshot);
        Map<String, Object> snapshot = new HashMap<>();

        for (String key : MAIN_BOOLEAN_PREFS) {
            snapshot.put(key, prefs.get(PreferenceCategory.MAIN, key));
        }
        for (String key : MAIN_STRING_PREFS) {
            snapshot.put(key, prefs.get(PreferenceCategory.MAIN, key));
        }
        for (String key : DB_UPDATE_BOOLEAN_PREFS) {
            snapshot.put(key, prefs.get(PreferenceCategory.DB_UPDATE, key));
        }
        snapshot.put(PROJ_PREF.FORCE_UNIX_NEWLINES,
                prefs.getBoolean(PROJ_PREF.FORCE_UNIX_NEWLINES, true));
        snapshot.put(DB_UPDATE_PREF.CLUSTER_NAME,
                prefs.get(PreferenceCategory.DB_UPDATE, DB_UPDATE_PREF.CLUSTER_NAME));
        return Map.copyOf(snapshot);
    }

    private static Set<String> parseSchemaExclusions(String value) {
        return ProjectIndexSchemaExclusions.parse(value);
    }

    private boolean getMainBoolean(String key) {
        return (boolean) preferenceSnapshot.get(key);
    }

    private boolean getDbUpdateBoolean(String key) {
        return (boolean) preferenceSnapshot.get(key);
    }

    private List<String> addPathsIfExists(String dir, String script) {
        List<String> paths = new ArrayList<>();
        if (project != null) {
            addPathIfExists(paths, Paths.get(project.getLocationURI()).resolve(dir));
        }
        addPathIfExists(paths, PrePostScriptPrefPage.getScriptPath(script));
        return List.copyOf(paths);
    }

    private void addPathIfExists(List<String> paths, Path path) {
        if (Files.exists(path)) {
            paths.add(path.toString());
        }
    }

    @Override
    protected AbstractSettings shallowCopy() {
        return new UISettings(this);
    }

    @Override
    public void setIgnorePrivileges(boolean ignorePrivileges) {
        Map<String, Object> updated = new HashMap<>(preferenceSnapshot);
        updated.put(PREF.NO_PRIVILEGES, ignorePrivileges);
        preferenceSnapshot = Map.copyOf(updated);
    }

    public void setCharsetName(String inCharsetName) {
        this.inCharsetName = inCharsetName;
    }

    @Override
    public boolean isDisableAutoLoad() {
        return false;
    }

    @Override
    public boolean isUseActualVersionSyntax() {
        return getDbUpdateBoolean(DB_UPDATE_PREF.USE_ACTUAL_VERSION_SYNTAX);
    }

    @Override
    public boolean isSimplifyNotNull() {
        return getMainBoolean(PREF.SIMPLIFY_NOT_NULL);
    }
}
