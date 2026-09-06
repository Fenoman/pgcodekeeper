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

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.pgcodekeeper.core.analysis.AnalysisReplay;
import org.pgcodekeeper.core.analysis.AnalysisReplayPayload;
import org.pgcodekeeper.core.api.ComparisonDepth;
import org.pgcodekeeper.core.database.api.IDatabaseProvider;
import org.pgcodekeeper.core.database.api.loader.ComparisonLoaderFactories;
import org.pgcodekeeper.core.database.api.loader.ILoaderFactory;
import org.pgcodekeeper.core.database.api.loader.IProjectLoader;
import org.pgcodekeeper.core.database.api.loader.ProjectInputFingerprint;
import org.pgcodekeeper.core.database.base.loader.LoaderFactories;
import org.pgcodekeeper.core.database.pg.loader.PreanalyzedProjectLoader;
import org.pgcodekeeper.core.database.pg.loader.PreloadedStructuralProjectLoader;
import org.pgcodekeeper.core.database.pg.routine.ReusableProjectRoutineBodySnapshot;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.settings.ISettings;
import org.pgcodekeeper.core.settings.ProjectFileFilter;

import ru.taximaxim.codekeeper.ui.DatabaseType;
import ru.taximaxim.codekeeper.ui.Log;
import ru.taximaxim.codekeeper.ui.libraries.LibraryUtils;
import ru.taximaxim.codekeeper.ui.localizations.Messages;
import ru.taximaxim.codekeeper.ui.pgdbproject.parser.ProjectComparisonProfile.EffectiveVersion;
import ru.taximaxim.codekeeper.ui.projectindex.IndexPathRef;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectFileStamp;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexSupportPolicy;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexWarmValidator.Result;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexWarmValidator.CurrentFile;
import ru.taximaxim.codekeeper.ui.settings.EclipseComparisonTelemetry;
import ru.taximaxim.codekeeper.ui.settings.EclipseComparisonTelemetry.ProjectAnalysisStoreStatus;
import ru.taximaxim.codekeeper.ui.settings.EclipseComparisonTelemetry.ProjectInputChangeStage;
import ru.taximaxim.codekeeper.ui.settings.EclipseComparisonTelemetry.ProjectModelCacheStatus;
import ru.taximaxim.codekeeper.ui.settings.EclipseComparisonTelemetry.ProjectModelFailClosedReason;
import ru.taximaxim.codekeeper.ui.settings.EclipseComparisonTelemetry.UiStage;
import ru.taximaxim.codekeeper.ui.utils.GetChangesProgressSink;
import ru.taximaxim.codekeeper.ui.utils.GetChangesProgressSink.Phase;
import ru.taximaxim.codekeeper.ui.utils.GetChangesProgressSink.Profile;
import ru.taximaxim.codekeeper.ui.utils.UIMonitor;

/**
 * Owns the one-generation OLD model cache for an Eclipse diff editor.
 * Model depth is part of its key; structural and analyzed models never mix.
 * NEW is always loaded from a fresh factory. Every uncertain state falls back
 * to a cold project load or rejects publication; it never publishes a cached
 * model on partial evidence.
 */
public final class ReusableProjectComparison implements AutoCloseable {

    private final ProjectComparisonModelCache<ReusableModel> cache =
            new ProjectComparisonModelCache<>();

    /**
     * Runs the reusable PostgreSQL path when it is safe for the current
     * settings. Empty means the caller must use the ordinary cold loader path.
     * <p>
     * Equivalent to {@link #load(IProject, DatabaseType, IDatabaseProvider,
     * Path, ILoaderFactory, ISettings, String, String, IProgressMonitor,
     * boolean, ComparisonDepth)} with {@link ComparisonDepth#FULL}, kept so
     * every existing caller that has no opinion on depth keeps reusing the
     * analyzed model exactly as before that parameter existed.
     */
    public Optional<PreparedComparison> load(
            IProject project,
            DatabaseType databaseType,
            IDatabaseProvider provider,
            Path projectRoot,
            ILoaderFactory newFactory,
            ISettings settings,
            String oldName,
            String newName,
            IProgressMonitor monitor,
            boolean hasOneTimePreferences)
            throws IOException, InterruptedException {
        return load(project, databaseType, provider, projectRoot, newFactory,
                settings, oldName, newName, monitor, hasOneTimePreferences,
                ComparisonDepth.FULL);
    }

    /**
     * Runs the reusable PostgreSQL path when it is safe for the current
     * settings and depth. Empty means the caller must use the ordinary cold
     * loader path.
     *
     * @param depth how deep this comparison must load. A retained model can
     *              serve only that exact depth. Structural comparisons use
     *              file fingerprints and mutation epochs without opening the
     *              analyzed reference index or its persisted analysis store.
     */
    public Optional<PreparedComparison> load(
            IProject project,
            DatabaseType databaseType,
            IDatabaseProvider provider,
            Path projectRoot,
            ILoaderFactory newFactory,
            ISettings settings,
            String oldName,
            String newName,
            IProgressMonitor monitor,
            boolean hasOneTimePreferences,
            ComparisonDepth depth)
            throws IOException, InterruptedException {
        Objects.requireNonNull(project, "project"); //$NON-NLS-1$
        Objects.requireNonNull(databaseType, "databaseType"); //$NON-NLS-1$
        Objects.requireNonNull(provider, "provider"); //$NON-NLS-1$
        Path root = Objects.requireNonNull(
                projectRoot, "projectRoot") //$NON-NLS-1$
                .toAbsolutePath().normalize();
        Objects.requireNonNull(newFactory, "newFactory"); //$NON-NLS-1$
        Objects.requireNonNull(settings, "settings"); //$NON-NLS-1$
        Objects.requireNonNull(oldName, "oldName"); //$NON-NLS-1$
        Objects.requireNonNull(newName, "newName"); //$NON-NLS-1$
        Objects.requireNonNull(depth, "depth"); //$NON-NLS-1$

        EclipseComparisonTelemetry telemetry = telemetry(settings);
        GetChangesProgressSink progress = progress(telemetry);
        var modelValidation = new ModelValidationRun(telemetry);
        try {
            if (hasOneTimePreferences) {
                cacheMiss(telemetry,
                        ProjectModelFailClosedReason.ONE_TIME);
                return Optional.empty();
            }
            if (!ProjectIndexSupportPolicy.supportsReusableComparisonModel(
                    databaseType)
                    || !settings.requiresComparisonLoaderFactories()
                    || !settings.getAdditionalExcludedSchemas().isEmpty()
                    || settings.getProjectFileFilter()
                            != ProjectFileFilter.ALLOW_ALL) {
                cacheMiss(telemetry,
                        ProjectModelFailClosedReason.UNSUPPORTED);
                return Optional.empty();
            }
            if (!PgDbParser.isPersistentProjectIndexSupported(root)) {
                cacheMiss(telemetry,
                        ProjectModelFailClosedReason.LIBRARIES);
                return Optional.empty();
            }
            progress.enterPhase(Phase.PROJECT_INDEX);
            // FULL needs the reference index. Structural comparisons only
            // register the parser's independent project mutation tracking.
            PgDbParser parser = depth == ComparisonDepth.FULL
                    ? PgDbParser.getParserForComparison(project,
                            slice(progress, Phase.PROJECT_INDEX, 70, monitor))
                    : PgDbParser.getParserForStructuralComparison(project);
            String configurationDigest =
                    ProjectConfigurationDigest.of(root);

            ILoaderFactory projectFactory = projectFactory(provider, root);
            // A model is captured from the settings Core hands back, which the
            // loader factories have already configured with the shared project
            // files. Looking a model up with the raw caller settings would
            // compare a configured key against an unconfigured one, so every
            // project that ships a non-empty ignore list would miss forever.
            ISettings lookupSettings =
                    commonConfigured(projectFactory, settings);
            Optional<ProjectComparisonModelCache<ReusableModel>.Lease>
                    reusable = cache.acquireMatching(
                            key -> key
                                    instanceof ModelKey modelKey
                                    && modelKey.depth() == depth
                                    && modelKey.profile().matchesSemantics(
                                            databaseType, lookupSettings,
                                            configurationDigest));
            boolean retainedModelRejected = false;
            if (reusable.isPresent()) {
                Optional<PreparedComparison> warm = tryWarm(
                        reusable.orElseThrow(), parser,
                        project, databaseType,
                        provider, root, newFactory, settings,
                        oldName, newName, monitor, telemetry, progress,
                        modelValidation, configurationDigest, depth);
                if (warm.isPresent()) {
                    return warm;
                }
                retainedModelRejected = true;
            } else {
                boolean profileChanged =
                        cache.retainedEntries() != 0;
                cacheMiss(telemetry,
                        !profileChanged
                                ? ProjectModelFailClosedReason.NO_MODEL
                                : ProjectModelFailClosedReason.PROFILE_CHANGED);
                if (profileChanged) {
                    cache.invalidateRetained();
                }
            }

            // No retained model, but an earlier session may have left its
            // analysis on disk. Replaying it replaces the analysis phase of this
            // run; everything else, including every fail-closed check below,
            // stays exactly as it is on a cold run.
            //
            // A retained model that was just rejected is not followed by a disk
            // attempt: both generations answer the same key, so the stored copy
            // would be rejected for the same reason and reading it would only
            // buy a second full load. That run goes cold and refreshes the
            // store for the next one.
            DiskAttempt disk = depth == ComparisonDepth.FULL
                    ? openDiskStore(project, root, databaseType,
                            provider, lookupSettings, settings, configurationDigest,
                            monitor, telemetry, !retainedModelRejected)
                    : DiskAttempt.unavailable();
            if (disk.hasPayload()) {
                Optional<PreparedComparison> warmDisk = loadProject(
                        parser, projectFactory, project, databaseType, provider,
                        root, newFactory, settings, oldName, newName, monitor,
                        telemetry, progress, modelValidation,
                        configurationDigest, disk, depth);
                if (warmDisk.isPresent()) {
                    return warmDisk;
                }
                // The stored result was produced under a different effective
                // database version than this run resolved. Drop it and load
                // this comparison the ordinary way.
                disk.discard();
            }
            return Optional.of(loadProject(parser, projectFactory, project,
                    databaseType, provider, root, newFactory, settings,
                    oldName, newName, monitor, telemetry, progress,
                    modelValidation, configurationDigest, disk.withoutPayload(), depth)
                    .orElseThrow());
        } finally {
            modelValidation.finish();
        }
    }

    /**
     * Looks the persistent analyzed-model store up for this run.
     * <p>
     * The lookup answers the very key the in-memory cache answers to - the
     * comparison profile including its ignore lists and project configuration,
     * plus the stamp of every input file - so a project that is stale for one
     * generation is stale for the other. Every uncertain outcome, including a
     * workbench without a state location, yields an attempt without a payload.
     */
    private static DiskAttempt openDiskStore(
            IProject project,
            Path projectRoot,
            DatabaseType databaseType,
            IDatabaseProvider provider,
            ISettings lookupSettings,
            ISettings settings,
            String configurationDigest,
            IProgressMonitor monitor,
            EclipseComparisonTelemetry telemetry,
            boolean readPayload)
            throws IOException, InterruptedException {
        Optional<ProjectAnalysisStore> store = ProjectAnalysisCache.store(projectRoot);
        if (store.isEmpty()) {
            storeEvent(telemetry, ProjectAnalysisStoreStatus.UNAVAILABLE);
            return DiskAttempt.unavailable();
        }
        if (!readPayload) {
            return new DiskAttempt(store.orElseThrow(), null, null);
        }
        Function<EffectiveVersion, Optional<String>> expectedDigest =
                version -> ProjectComparisonProfile.captureWith(
                        databaseType, lookupSettings, version,
                        configurationDigest)
                        .map(ProjectAnalysisIdentity::digest);
        // Enumerating the project is only worth it once the stored settings
        // already match, so a first-ever run and a settings change cost nothing
        // beyond opening one file.
        ProjectAnalysisStore.CurrentInputs inputs = () -> {
            ProjectComparisonInputSet current = inspectInputs(
                    project, projectRoot, provider, settings, monitor);
            return new ProjectAnalysisStore.Inputs(
                    current.files(), current.resolver());
        };
        BooleanSupplier cancelled =
                () -> monitor != null && monitor.isCanceled();
        Optional<ProjectAnalysisCache.Hit> hit = ProjectAnalysisCache.open(
                store.orElseThrow(), expectedDigest, inputs, cancelled,
                telemetry);
        return new DiskAttempt(store.orElseThrow(),
                hit.map(ProjectAnalysisCache.Hit::payload).orElse(null),
                hit.map(ProjectAnalysisCache.Hit::profileDigest).orElse(null));
    }

    /**
     * One run's relationship with the persistent store: the store itself, and
     * the analysis result it offered, if any.
     */
    private record DiskAttempt(ProjectAnalysisStore store,
            AnalysisReplayPayload payload, String storedDigest) {

        private static DiskAttempt unavailable() {
            return new DiskAttempt(null, null, null);
        }

        private boolean hasPayload() {
            return payload != null;
        }

        private DiskAttempt withoutPayload() {
            return payload == null ? this : new DiskAttempt(store, null, null);
        }

        private boolean matchesDigest(String digest) {
            return storedDigest != null && storedDigest.equals(digest);
        }

        private void discard() {
            if (store != null) {
                store.discard();
            }
        }
    }

    @Override
    public void close() {
        cache.close();
    }

    /**
     * Retires the cached OLD graph without permanently closing this manager.
     */
    public void invalidate() {
        cache.invalidateRetained();
    }

    private Optional<PreparedComparison> tryWarm(
            ProjectComparisonModelCache<ReusableModel>.Lease modelLease,
            PgDbParser parser,
            IProject project,
            DatabaseType databaseType,
            IDatabaseProvider provider,
            Path projectRoot,
            ILoaderFactory newFactory,
            ISettings settings,
            String oldName,
            String newName,
            IProgressMonitor monitor,
            EclipseComparisonTelemetry telemetry,
            GetChangesProgressSink progress,
            ModelValidationRun modelValidation,
            String configurationDigest, ComparisonDepth depth)
            throws IOException, InterruptedException {
        long cacheStart = startTimer(telemetry);
        PgDbParser.ValidatedProjectSnapshotLease snapshotLease = null;
        PgDbParser.ProjectMutationLease mutationLease = null;
        boolean transferred = false;
        try {
            // A warm run validates hashes instead of parsing the project, so
            // the plan must stop weighting the load like a cold one.
            progress.profile(Profile.WARM);
            ReusableModel model = modelLease.model();
            mutationLease = parser.acquireProjectMutationLease();
            if (depth == ComparisonDepth.STRUCTURAL_ONLY
                    && !model.mutationToken().equals(mutationLease.token())) {
                rejectWarm(modelLease, telemetry,
                        ProjectModelFailClosedReason.STALE, 0, 0, cacheStart);
                return Optional.empty();
            }
            if (depth == ComparisonDepth.FULL) {
                Optional<PgDbParser.ValidatedProjectSnapshotLease> snapshot =
                        parser.acquireValidatedProjectSnapshotLease(project,
                                slice(progress, Phase.PROJECT_INDEX, 100, monitor));
                if (snapshot.isEmpty()) {
                    rejectWarm(modelLease, telemetry,
                            ProjectModelFailClosedReason.STALE, 0, 0, cacheStart);
                    return Optional.empty();
                }
                snapshotLease = snapshot.orElseThrow();
                if (!model.snapshotToken().equals(snapshotLease.token())) {
                    rejectWarm(modelLease, telemetry,
                            ProjectModelFailClosedReason.STALE, 0, 0, cacheStart);
                    return Optional.empty();
                }
            }
            progress.completePhase(Phase.PROJECT_INDEX);

            progress.enterPhase(Phase.MODEL_VALIDATE);
            var inputValidation = new AtomicReference<WarmInputValidation>();
            if (depth == ComparisonDepth.FULL) {
                WarmInputValidation checked = validateWarmInputs(model, project,
                        projectRoot, provider, settings, monitor, depth);
                inputValidation.set(checked);
                progress.completePhase(Phase.MODEL_VALIDATE);
                if (!checked.hit() || !mutationLease.isCurrent() || !snapshotLease.isCurrent()) {
                    rejectWarm(modelLease, telemetry,
                            ProjectModelFailClosedReason.INPUT_CHANGED,
                            checked.current().files().size(),
                            checked.result().hashedFiles(), cacheStart);
                    return Optional.empty();
                }
            }

            ILoaderFactory oldFactory = LoaderFactories.project(
                    projectRoot, sideSettings ->
                            depth == ComparisonDepth.FULL
                            ? new PreanalyzedProjectLoader(
                                    model.database(),
                                    model.routineSnapshot(),
                                    sideSettings, oldName)
                            : new PreloadedStructuralProjectLoader(
                                    model.database(), model.routineSnapshot(),
                                    sideSettings, oldName) {
                                @Override
                                public PgDatabase load() throws IOException, InterruptedException {
                                    PgDatabase database = super.load();
                                    // Reuse the coordinator's OLD task so file validation
                                    // overlaps NEW without an additional executor.
                                    inputValidation.set(validateWarmInputs(model, project,
                                            projectRoot, provider, settings, monitor, depth));
                                    return database;
                                }
                            });
            progress.enterPhase(Phase.CORE_LOAD);
            UIComparisonLoader.LoadedModels loaded =
                    UIComparisonLoader.loadModels(
                            new ComparisonLoaderFactories(
                                    oldFactory, newFactory),
                            settings, depth);
            progress.completePhase(Phase.CORE_LOAD);
            WarmInputValidation checked = Objects.requireNonNull(inputValidation.get());
            ProjectComparisonInputSet current = checked.current();
            Result validation = checked.result();
            if (!checked.hit() || !mutationLease.isCurrent()
                    || snapshotLease != null && !snapshotLease.isCurrent()) {
                progress.completePhase(Phase.MODEL_VALIDATE);
                rejectWarm(modelLease, telemetry,
                        ProjectModelFailClosedReason.INPUT_CHANGED,
                        current.files().size(), validation.hashedFiles(), cacheStart);
                return Optional.empty();
            }
            // Hashing ran while the database was still loading, so the file set
            // it saw is only a claim about that moment. Settle it here, where
            // both sides have finished, exactly as a cold run does.
            ProjectComparisonInputSet after = inspectInputs(
                    project, projectRoot, provider, settings, monitor);
            progress.completePhase(Phase.MODEL_VALIDATE);
            if (!current.files().equals(after.files())) {
                rejectWarm(modelLease, telemetry,
                        ProjectModelFailClosedReason.INPUT_CHANGED,
                        current.files().size(), validation.hashedFiles(), cacheStart);
                throw inputsChanged(telemetry,
                        ProjectInputChangeStage.FILE_SET,
                        firstChangedPath(current.files(), after.files()));
            }

            Optional<ProjectComparisonProfile> finalProfile =
                    ProjectComparisonProfile.capture(
                            databaseType, loaded.settings(),
                            configurationDigest);
            if (finalProfile.isEmpty()
                    || !model.profile().equals(
                            finalProfile.orElseThrow())) {
                rejectWarm(modelLease, telemetry,
                        ProjectModelFailClosedReason.VERSION_MISMATCH,
                        current.files().size(),
                        validation.hashedFiles(), cacheStart);
                closeSnapshot(snapshotLease);
                snapshotLease = null;
                return Optional.empty();
            }
            if (snapshotLease != null && !snapshotLease.isCurrent()) {
                rejectWarm(modelLease, telemetry,
                        ProjectModelFailClosedReason.STALE,
                        current.files().size(),
                        validation.hashedFiles(), cacheStart);
                return Optional.empty();
            }

            UIComparisonLoader.Result result = createResult(
                    loaded, oldName, newName, telemetry, progress);
            if (!mutationLease.isCurrent()
                    || snapshotLease != null && !snapshotLease.isCurrent()) {
                rejectWarm(modelLease, telemetry,
                        ProjectModelFailClosedReason.STALE,
                        current.files().size(),
                        validation.hashedFiles(), cacheStart);
                return Optional.empty();
            }
            cacheEvent(telemetry, ProjectModelCacheStatus.HIT,
                    ProjectModelFailClosedReason.NONE,
                    current.files().size(),
                    validation.hashedFiles(), cacheStart);
            modelValidation.finish();

            var prepared = new PreparedComparison(
                    result, loaded.depth(), true, modelLease, null,
                    snapshotLease, mutationLease, telemetry);
            snapshotLease = null;
            mutationLease = null;
            transferred = true;
            return Optional.of(prepared);
        } finally {
            if (!transferred) {
                closeSnapshot(snapshotLease);
                closeMutation(mutationLease);
                modelLease.close();
            }
        }
    }

    private static WarmInputValidation validateWarmInputs(ReusableModel model,
            IProject project, Path projectRoot, IDatabaseProvider provider,
            ISettings settings, IProgressMonitor monitor, ComparisonDepth depth)
            throws IOException, InterruptedException {
        ProjectComparisonInputSet current = inspectInputs(
                project, projectRoot, provider, settings, monitor);
        BooleanSupplier cancelled = () -> Thread.currentThread().isInterrupted()
                || monitor != null && monitor.isCanceled();
        Result validation = depth == ComparisonDepth.STRUCTURAL_ONLY
                ? model.inputs().validateContent(current.files(), current.resolver(), cancelled)
                : model.inputs().validate(current.files(), current.resolver(), cancelled);
        return new WarmInputValidation(current, validation);
    }

    /**
     * Content validation of one warm pass against the enumeration it ran on.
     * A changed file set is not decided here: it is the caller that compares
     * this enumeration with a final one taken after both sides have finished.
     */
    private record WarmInputValidation(ProjectComparisonInputSet current,
            Result result) {
        boolean hit() {
            return result.hit();
        }
    }

    /**
     * Loads a comparison whose project side is parsed by this run.
     * <p>
     * This is the cold path, and it is also the path that serves a persisted
     * analysis: the only difference is whether the project loader is handed a
     * stored analysis result to replay instead of analyzing the model. Every
     * input, lease and profile check below is therefore identical for both, and
     * a replayed run is validated exactly as strictly as a freshly analyzed one.
     *
     * @param disk relationship with the persistent store, possibly carrying the
     *             analysis result to replay
     * @return the prepared comparison, or empty only when a replayed run turned
     *         out to have been analyzed under a different effective database
     *         version and must be redone without the stored result
     */
    private Optional<PreparedComparison> loadProject(
            PgDbParser parser,
            ILoaderFactory projectFactory,
            IProject project,
            DatabaseType databaseType,
            IDatabaseProvider provider,
            Path projectRoot,
            ILoaderFactory newFactory,
            ISettings settings,
            String oldName,
            String newName,
            IProgressMonitor monitor,
            EclipseComparisonTelemetry telemetry,
            GetChangesProgressSink progress,
            ModelValidationRun modelValidation,
            String configurationDigest,
            DiskAttempt disk, ComparisonDepth depth)
            throws IOException, InterruptedException {
        // A cold run hashes consumed files after parsing. Structural loads do
        // that on the OLD worker while NEW can still be loading; the final
        // file-set and mutation checks remain after both workers finish.
        progress.profile(Profile.COLD);
        progress.completePhase(Phase.PROJECT_INDEX);
        PgDbParser.ProjectMutationLease mutationLease =
                parser.acquireProjectMutationLease();
        ProjectComparisonModelCache<ReusableModel>.Candidate candidate = null;
        PgDbParser.ValidatedProjectSnapshotLease snapshotLease = null;
        boolean transferred = false;
        long cacheStart = startTimer(telemetry);
        try {
            var validatedInputs = new AtomicReference<ColdInputValidation>();
            var capturing = new CapturingPgProjectLoaderFactory(
                    projectFactory, disk.payload(), depth,
                    depth == ComparisonDepth.STRUCTURAL_ONLY
                            ? (fingerprints, sideSettings) -> validatedInputs.set(
                                    validateColdInputs(project, projectRoot, provider,
                                            sideSettings, monitor, fingerprints))
                            : null);
            progress.analysisReplayProbe(capturing::isAnalysisReplayed);
            progress.enterPhase(Phase.CORE_LOAD);
            UIComparisonLoader.LoadedModels loaded =
                    UIComparisonLoader.loadModels(
                            new ComparisonLoaderFactories(
                                    capturing, newFactory),
                            settings, depth);
            progress.completePhase(Phase.CORE_LOAD);
            if (!(loaded.oldDatabase()
                    instanceof PgDatabase oldDatabase)) {
                throw new IllegalStateException(
                        "PostgreSQL project loader returned a non-PG model"); //$NON-NLS-1$
            }
            Optional<ProjectComparisonProfile> profile =
                    ProjectComparisonProfile.capture(
                            databaseType, loaded.settings(),
                            configurationDigest);
            CapturingPgProjectLoaderFactory.Capture capture =
                    capturing.takeCapture(oldDatabase).orElseThrow(
                            () -> new IllegalStateException(
                                    "Project loader did not capture consumed inputs")); //$NON-NLS-1$
            progress.enterPhase(Phase.MODEL_VALIDATE);
            ColdInputValidation validation = depth == ComparisonDepth.STRUCTURAL_ONLY
                    ? Objects.requireNonNull(validatedInputs.get(), "input validation") //$NON-NLS-1$
                    : validateColdInputs(project, projectRoot, provider,
                            loaded.settings(), monitor, capture.inputFingerprints());
            ProjectComparisonInputSet current = validation.current();
            Optional<ProjectComparisonInputSnapshot> inputs = validation.inputs();
            ProjectComparisonInputSet after = inspectInputs(
                    project, projectRoot, provider,
                    loaded.settings(), monitor);
            if (inputs.isEmpty()) {
                throw rejectColdInputChange(telemetry,
                        ProjectInputChangeStage.FINGERPRINT_CAPTURE,
                        null, current.files().size(), cacheStart);
            }
            if (!current.files().equals(after.files())) {
                throw rejectColdInputChange(telemetry,
                        ProjectInputChangeStage.FILE_SET,
                        firstChangedPath(
                                current.files(), after.files()),
                        current.files().size(), cacheStart);
            }
            if (!mutationLease.isCurrent()) {
                throw rejectColdInputChange(telemetry,
                        ProjectInputChangeStage.MUTATION_EPOCH,
                        null, current.files().size(), cacheStart);
            }

            if (profile.isPresent() && loaded.settings().getErrors().isEmpty()) {
                if (depth == ComparisonDepth.FULL) {
                    Optional<PgDbParser.ValidatedProjectSnapshotLease> snapshot =
                            parser.acquireValidatedProjectSnapshotLease(project,
                                    slice(progress, Phase.MODEL_VALIDATE, 40, monitor));
                    if (snapshot.isPresent()) {
                        snapshotLease = snapshot.orElseThrow();
                        if (!mutationLease.isCurrent()) {
                            throw rejectColdInputChange(telemetry,
                                    ProjectInputChangeStage.MUTATION_EPOCH,
                                    null, current.files().size(), cacheStart);
                        }
                        if (!snapshotLease.isCurrent()) {
                            throw rejectColdInputChange(telemetry,
                                    ProjectInputChangeStage.INDEX_SNAPSHOT,
                                    null, current.files().size(), cacheStart);
                        }
                    }
                }
                if (depth == ComparisonDepth.STRUCTURAL_ONLY || snapshotLease != null) {
                    var reusableModel = new ReusableModel(
                            oldDatabase,
                            capture.routineSnapshot(),
                            inputs.orElseThrow(),
                            profile.orElseThrow(), depth, mutationLease.token(),
                            snapshotLease == null ? null : snapshotLease.token());
                    candidate = cache.prepare(
                            new ModelKey(profile.orElseThrow(), depth), reusableModel);
                }
            }
            boolean replayed = capturing.isAnalysisReplayed();
            String finalDigest = profile.map(ProjectAnalysisIdentity::digest)
                    .orElse(null);
            if (replayed && !diskProfileStillMatches(finalDigest, disk)) {
                // The stored result was analyzed against a different effective
                // database version, which can change what the analysis resolves.
                // Reject the whole attempt rather than trust a model that was
                // built for another server.
                cacheEvent(telemetry, ProjectModelCacheStatus.REJECTED,
                        ProjectModelFailClosedReason.VERSION_MISMATCH,
                        current.files().size(), current.files().size(),
                        cacheStart);
                return Optional.empty();
            }
            progress.completePhase(Phase.MODEL_VALIDATE);
            modelValidation.finish();

            UIComparisonLoader.Result result = createResult(
                    loaded, oldName, newName, telemetry, progress);
            if (!mutationLease.isCurrent()) {
                throw rejectColdInputChange(telemetry,
                        ProjectInputChangeStage.DIFF_TREE,
                        null, current.files().size(), cacheStart);
            }
            if (snapshotLease != null
                    && !snapshotLease.isCurrent()) {
                throw rejectColdInputChange(telemetry,
                        ProjectInputChangeStage.INDEX_SNAPSHOT,
                        null, current.files().size(), cacheStart);
            }
            if (replayed) {
                cacheEvent(telemetry, ProjectModelCacheStatus.HIT_DISK,
                        ProjectModelFailClosedReason.NONE,
                        current.files().size(), current.files().size(),
                        cacheStart);
            }
            var prepared = new PreparedComparison(
                    result, loaded.depth(), false, replayed, null, candidate,
                    snapshotLease, mutationLease, telemetry,
                    persistence(disk, replayed, finalDigest, profile,
                            inputs.orElseThrow(), oldDatabase, telemetry));
            candidate = null;
            snapshotLease = null;
            mutationLease = null;
            transferred = true;
            return Optional.of(prepared);
        } finally {
            if (!transferred) {
                closeCandidate(candidate);
                closeSnapshot(snapshotLease);
                closeMutation(mutationLease);
            }
        }
    }

    private record ColdInputValidation(ProjectComparisonInputSet current,
            Optional<ProjectComparisonInputSnapshot> inputs) {
    }

    private static ColdInputValidation validateColdInputs(IProject project,
            Path projectRoot, IDatabaseProvider provider, ISettings settings,
            IProgressMonitor monitor, List<ProjectInputFingerprint> fingerprints)
            throws IOException, InterruptedException {
        ProjectComparisonInputSet current = inspectInputs(
                project, projectRoot, provider, settings, monitor);
        Optional<ProjectComparisonInputSnapshot> inputs =
                ProjectComparisonInputSnapshot.capture(current.files(), fingerprints,
                        current.resolver(), () -> monitor != null && monitor.isCanceled());
        return new ColdInputValidation(current, inputs);
    }

    /**
     * Checks that a replayed analysis was produced under the very profile this
     * run resolved. The store already matched the profile before the load, but
     * the effective database version is only known once NEW has been read.
     */
    private static boolean diskProfileStillMatches(
            String finalDigest, DiskAttempt disk) {
        return finalDigest != null && disk.matchesDigest(finalDigest);
    }

    /**
     * Prepares the write-back of this run's analysis result, or {@code null}
     * when there is nothing to store.
     * <p>
     * The result is captured here, on the loader thread, while the model is
     * quiescent and nothing else can reach it. What the capture produces is a
     * graph of immutable values, so it outlives the model and can be encoded
     * anywhere - and it has to be, because the comparison is published on the
     * UI thread and encoding tens of megabytes there would freeze the editor.
     * A run that already replayed a stored result has nothing new to say.
     */
    private static Runnable persistence(DiskAttempt disk, boolean replayed,
            String finalDigest, Optional<ProjectComparisonProfile> profile,
            ProjectComparisonInputSnapshot inputs, PgDatabase oldDatabase,
            EclipseComparisonTelemetry telemetry) {
        if (replayed || disk.store() == null || finalDigest == null
                || profile.isEmpty()) {
            return null;
        }
        EffectiveVersion version = profile.orElseThrow().effectiveVersion();
        List<ProjectFileStamp> files = inputs.files();
        AnalysisReplayPayload payload = AnalysisReplay.capture(oldDatabase);
        ProjectAnalysisStore store = disk.store();
        return () -> {
            var job = new Job(Messages.ReusableProjectComparison_store_job) {

                @Override
                protected IStatus run(IProgressMonitor jobMonitor) {
                    long start = startTimer(telemetry);
                    long bytes = ProjectAnalysisCache.publish(
                            store, finalDigest, version, files, payload);
                    if (telemetry != null && bytes != 0) {
                        telemetry.projectAnalysisStoreFinished(
                                ProjectAnalysisStoreStatus.PUBLISHED, bytes,
                                files.size(), 0, start);
                    }
                    return Status.OK_STATUS;
                }
            };
            job.setSystem(true);
            job.setPriority(Job.DECORATE);
            job.schedule();
        };
    }

    /**
     * Applies the shared project configuration to a private copy of the caller
     * settings, exactly as Core does before it loads a comparison.
     * <p>
     * The reusable-model key is captured from the settings Core publishes back,
     * so it always carries the ignore lists and additional dependencies of the
     * project. Comparing that key against raw caller settings would never
     * match on a project that ships any of those files, so the lookup has to
     * start from the same configured state. The copy is private to this lookup
     * and never reaches a loader, so the comparison itself is unaffected.
     *
     * @param projectFactory factory that owns the project configuration files
     * @param settings caller settings of this run
     * @return configured settings to build the lookup key from
     */
    private static ISettings commonConfigured(
            ILoaderFactory projectFactory, ISettings settings)
            throws IOException, InterruptedException {
        ISettings configured = Objects.requireNonNull(
                settings.copy(), "settings copy"); //$NON-NLS-1$
        projectFactory.contributeCommonConfiguration(configured);
        return configured;
    }

    private static ILoaderFactory projectFactory(
            IDatabaseProvider provider, Path projectRoot) {
        return LoaderFactories.project(projectRoot,
                sideSettings -> provider.getProjectLoader(
                        projectRoot, sideSettings,
                        Collections.emptyList(),
                        Collections.emptyList(),
                        Collections.emptyList(),
                        LibraryUtils.META_PATH));
    }

    private static ProjectComparisonInputSet inspectInputs(
            IProject project,
            Path projectRoot,
            IDatabaseProvider provider,
            ISettings source,
            IProgressMonitor monitor)
            throws IOException, InterruptedException {
        ISettings settings = Objects.requireNonNull(
                source.copy(), "settings copy"); //$NON-NLS-1$
        settings.setMonitor(new UIMonitor(cancellationOnly(monitor)));
        IProjectLoader loader = provider.getProjectLoader(
                projectRoot, settings,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                LibraryUtils.META_PATH);
        return ProjectComparisonInputSet.inspect(
                project, projectRoot, loader);
    }

    /**
     * Wraps a monitor so that only cancellation is observed.
     * <p>
     * Input inspection runs several times inside one comparison and shares the
     * job monitor with the caller's own {@code SubMonitor} split. Reporting
     * work on it would consume ticks that the caller already allocated, so the
     * task and work callbacks are dropped here.
     */
    private static IProgressMonitor cancellationOnly(
            IProgressMonitor monitor) {
        if (monitor == null) {
            return null;
        }
        return new NullProgressMonitor() {

            @Override
            public boolean isCanceled() {
                return monitor.isCanceled();
            }

            @Override
            public void setCanceled(boolean canceled) {
                monitor.setCanceled(canceled);
            }
        };
    }

    private static UIComparisonLoader.Result createResult(
            UIComparisonLoader.LoadedModels loaded,
            String oldName,
            String newName,
            EclipseComparisonTelemetry telemetry,
            GetChangesProgressSink progress)
            throws InterruptedException {
        long start = startTimer(telemetry);
        progress.enterPhase(Phase.DIFF_TREE);
        try {
            return UIComparisonLoader.createResult(
                    loaded, oldName, newName);
        } finally {
            progress.completePhase(Phase.DIFF_TREE);
            finishStage(telemetry, UiStage.DIFF_TREE, start);
        }
    }

    private static EclipseComparisonTelemetry telemetry(
            ISettings settings) {
        return settings.getComparisonTelemetry()
                instanceof EclipseComparisonTelemetry eclipse
                        ? eclipse : null;
    }

    /**
     * Returns the staged progress sink of this run. A run whose telemetry
     * carries no sink gets an unbound one: every call is inert, so the
     * comparison behaves exactly as it did before staged progress existed.
     */
    private static GetChangesProgressSink progress(
            EclipseComparisonTelemetry telemetry) {
        GetChangesProgressSink sink =
                telemetry == null ? null : telemetry.progress();
        return sink == null ? new GetChangesProgressSink() : sink;
    }

    /**
     * Reserves a bounded share of a phase for a callee that reports its own
     * work. Without a bound sink the callee must still observe cancellation,
     * but it must never write progress the plan did not allot to it.
     */
    private static IProgressMonitor slice(GetChangesProgressSink progress,
            Phase phase, int percent, IProgressMonitor monitor) {
        IProgressMonitor sliced = progress.slice(phase, percent);
        return sliced != null ? sliced : cancellationOnly(monitor);
    }

    private static long startTimer(
            EclipseComparisonTelemetry telemetry) {
        return telemetry == null ? 0 : telemetry.startTimer();
    }

    private static void finishStage(
            EclipseComparisonTelemetry telemetry,
            UiStage stage, long start) {
        if (telemetry != null) {
            telemetry.uiStageFinished(stage, start);
        }
    }

    private static void storeEvent(
            EclipseComparisonTelemetry telemetry,
            ProjectAnalysisStoreStatus status) {
        if (telemetry != null) {
            telemetry.projectAnalysisStoreFinished(
                    status, 0, 0, 0, telemetry.startTimer());
        }
    }

    private static void cacheMiss(
            EclipseComparisonTelemetry telemetry,
            ProjectModelFailClosedReason reason) {
        cacheEvent(telemetry, ProjectModelCacheStatus.MISS,
                reason, 0, 0, startTimer(telemetry));
    }

    private static void cacheEvent(
            EclipseComparisonTelemetry telemetry,
            ProjectModelCacheStatus status,
            ProjectModelFailClosedReason reason,
            long filesInspected,
            long filesHashed,
            long start) {
        if (telemetry != null) {
            telemetry.projectModelCacheFinished(
                    status, reason, filesInspected,
                    filesHashed, start);
        }
    }

    private static ProjectInputsChangedException rejectColdInputChange(
            EclipseComparisonTelemetry telemetry,
            ProjectInputChangeStage stage, String relativePath,
            long filesInspected, long cacheStart) {
        cacheEvent(telemetry, ProjectModelCacheStatus.REJECTED,
                ProjectModelFailClosedReason.INPUT_CHANGED,
                filesInspected, filesInspected, cacheStart);
        return inputsChanged(telemetry, stage, relativePath);
    }

    /**
     * Reports the typed cancellation both paths answer an input change with.
     * The cache event is left to the caller, because a warm run counts hashed
     * files differently from a cold one.
     */
    private static ProjectInputsChangedException inputsChanged(
            EclipseComparisonTelemetry telemetry,
            ProjectInputChangeStage stage, String relativePath) {
        if (telemetry != null) {
            telemetry.projectInputsChanged(stage);
        }
        return new ProjectInputsChangedException(
                stage, relativePath);
    }

    static String firstChangedPath(
            List<CurrentFile> before, List<CurrentFile> after) {
        var remaining = new HashMap<IndexPathRef, CurrentFile>(
                after.size());
        for (CurrentFile file : after) {
            remaining.put(file.path(), file);
        }
        for (CurrentFile oldFile : before) {
            CurrentFile current = remaining.remove(
                    oldFile.path());
            if (current == null
                    || !oldFile.equals(current)) {
                return oldFile.path().relativePath();
            }
        }
        for (CurrentFile newFile : after) {
            if (remaining.containsKey(newFile.path())) {
                return newFile.path().relativePath();
            }
        }
        return null;
    }

    private void rejectWarm(
            ProjectComparisonModelCache<ReusableModel>.Lease lease,
            EclipseComparisonTelemetry telemetry,
            ProjectModelFailClosedReason reason,
            long filesInspected,
            long filesHashed,
            long start) {
        cache.invalidate(lease);
        cacheEvent(telemetry, ProjectModelCacheStatus.REJECTED,
                reason, filesInspected, filesHashed, start);
    }

    private static final class ModelValidationRun {

        private final EclipseComparisonTelemetry telemetry;
        private final long start;
        private boolean finished;

        private ModelValidationRun(
                EclipseComparisonTelemetry telemetry) {
            this.telemetry = telemetry;
            start = startTimer(telemetry);
        }

        private synchronized void finish() {
            if (finished) {
                return;
            }
            finished = true;
            finishStage(telemetry, UiStage.MODEL_VALIDATE,
                    start);
        }
    }

    private static void closeSnapshot(
            PgDbParser.ValidatedProjectSnapshotLease lease) {
        if (lease != null) {
            lease.close();
        }
    }

    private static void closeMutation(
            PgDbParser.ProjectMutationLease lease) {
        if (lease != null) {
            lease.close();
        }
    }

    private static void closeCandidate(
            ProjectComparisonModelCache<ReusableModel>.Candidate candidate) {
        if (candidate != null) {
            candidate.close();
        }
    }

    private record ModelKey(ProjectComparisonProfile profile, ComparisonDepth depth) { }

    private record ReusableModel(
            PgDatabase database,
            ReusableProjectRoutineBodySnapshot routineSnapshot,
            ProjectComparisonInputSnapshot inputs,
            ProjectComparisonProfile profile,
            ComparisonDepth depth,
            PgDbParser.ProjectMutationToken mutationToken,
            PgDbParser.ProjectSnapshotToken snapshotToken)
            implements AutoCloseable {

        private ReusableModel {
            Objects.requireNonNull(database, "database"); //$NON-NLS-1$
            Objects.requireNonNull(routineSnapshot,
                    "routineSnapshot"); //$NON-NLS-1$
            Objects.requireNonNull(inputs, "inputs"); //$NON-NLS-1$
            Objects.requireNonNull(profile, "profile"); //$NON-NLS-1$
            Objects.requireNonNull(depth, "depth"); //$NON-NLS-1$
            Objects.requireNonNull(mutationToken, "mutationToken"); //$NON-NLS-1$
            if (depth == ComparisonDepth.FULL) {
                Objects.requireNonNull(snapshotToken, "snapshotToken"); //$NON-NLS-1$
            }
        }

        @Override
        public void close() {
            // The model owns no external resource. Dropping the retained
            // generation makes the whole graph eligible for collection.
        }
    }

    /**
     * A comparison result that remains private until the editor accepts it.
     */
    public final class PreparedComparison implements AutoCloseable {

        private final UIComparisonLoader.Result result;
        private final ComparisonDepth depth;
        private final boolean reused;
        private final boolean analysisReplayed;
        private ProjectComparisonModelCache<ReusableModel>.Lease modelLease;
        private ProjectComparisonModelCache<ReusableModel>.Candidate candidate;
        private PgDbParser.ValidatedProjectSnapshotLease snapshotLease;
        private PgDbParser.ProjectMutationLease mutationLease;
        private final EclipseComparisonTelemetry telemetry;
        private final Runnable persistence;
        private boolean completed;

        private PreparedComparison(
                UIComparisonLoader.Result result,
                ComparisonDepth depth,
                boolean reused,
                ProjectComparisonModelCache<ReusableModel>.Lease modelLease,
                ProjectComparisonModelCache<ReusableModel>.Candidate candidate,
                PgDbParser.ValidatedProjectSnapshotLease snapshotLease,
                PgDbParser.ProjectMutationLease mutationLease,
                EclipseComparisonTelemetry telemetry) {
            this(result, depth, reused, false, modelLease, candidate, snapshotLease,
                    mutationLease, telemetry, null);
        }

        private PreparedComparison(
                UIComparisonLoader.Result result,
                ComparisonDepth depth,
                boolean reused,
                boolean analysisReplayed,
                ProjectComparisonModelCache<ReusableModel>.Lease modelLease,
                ProjectComparisonModelCache<ReusableModel>.Candidate candidate,
                PgDbParser.ValidatedProjectSnapshotLease snapshotLease,
                PgDbParser.ProjectMutationLease mutationLease,
                EclipseComparisonTelemetry telemetry,
                Runnable persistence) {
            this.result = Objects.requireNonNull(
                    result, "result"); //$NON-NLS-1$
            this.depth = Objects.requireNonNull(depth, "depth"); //$NON-NLS-1$
            this.reused = reused;
            this.analysisReplayed = analysisReplayed;
            this.modelLease = modelLease;
            this.candidate = candidate;
            this.snapshotLease = snapshotLease;
            this.mutationLease = Objects.requireNonNull(
                    mutationLease, "mutationLease"); //$NON-NLS-1$
            this.telemetry = telemetry;
            this.persistence = persistence;
        }

        /**
         * Schedules the write-back of this run's analysis result.
         * <p>
         * Only an accepted comparison publishes: a result the editor rejected
         * describes a project state that was already superseded, and storing it
         * would hand the next start a stale cache to validate and discard.
         */
        void persistAnalysis() {
            if (persistence == null) {
                return;
            }
            try {
                persistence.run();
            } catch (RuntimeException ex) {
                // A cache that could not be written costs the next start a cold
                // run and nothing else; it must never fail this comparison.
                Log.log(ex);
            }
        }

        public UIComparisonLoader.Result result() {
            return result;
        }

        public ComparisonDepth depth() {
            return depth;
        }

        /**
         * Reports that the retained model was reused, so the project
         * side was not loaded at all.
         *
         * @return true if this comparison reused the in-memory model
         */
        public boolean reused() {
            return reused;
        }

        /**
         * Reports that the project was parsed by this run but its analysis came
         * from the persistent cache instead of being redone. This is the branch
         * a restarted workbench takes.
         *
         * @return true if the analysis phase was replayed from disk
         */
        public boolean analysisReplayed() {
            return analysisReplayed;
        }

        public boolean isCurrent() {
            return !completed && mutationLease.isCurrent()
                    && (snapshotLease == null
                            || snapshotLease.isCurrent());
        }

        /**
         * Atomically makes a cold candidate reusable, or transfers the warm
         * model lease to the displayed UI result.
         *
         * @return an accepted result, optionally with a reusable-model lease,
         *         or a rejected result when the project/cache changed
         */
        public Publication publish() {
            return publish(() -> { });
        }

        Publication publish(Runnable beforeCommit) {
            Objects.requireNonNull(beforeCommit,
                    "beforeCommit"); //$NON-NLS-1$
            if (completed) {
                return Publication.rejected();
            }
            beforeCommit.run();
            long publishStart = startTimer(telemetry);
            Optional<Publication> committed =
                    mutationLease.commitIfCurrent(
                            this::publishIfSnapshotCurrent);
            Publication publication = committed.orElseGet(
                    Publication::rejected);
            completed = true;
            if (!publication.accepted()) {
                if (modelLease != null) {
                    modelLease.close();
                    modelLease = null;
                }
                closeCandidate(candidate);
                candidate = null;
            }
            closeSnapshot(snapshotLease);
            snapshotLease = null;
            closeMutation(mutationLease);
            mutationLease = null;
            if (!publication.accepted()) {
                cacheEvent(telemetry,
                        ProjectModelCacheStatus.REJECTED,
                        ProjectModelFailClosedReason.STALE,
                        0, 0, publishStart);
            } else if (publication.displayLease().isPresent()) {
                cacheEvent(telemetry,
                        ProjectModelCacheStatus.PUBLISHED,
                        ProjectModelFailClosedReason.NONE,
                        0, 0, publishStart);
            }
            if (publication.accepted()) {
                persistAnalysis();
            }
            finishStage(telemetry, UiStage.MODEL_PUBLISH,
                    publishStart);
            return publication;
        }

        private Publication publishIfSnapshotCurrent() {
            if (snapshotLease != null
                    && !snapshotLease.isCurrent()) {
                return Publication.rejected();
            }

            ProjectComparisonModelCache<ReusableModel>.Lease display =
                    modelLease;
            modelLease = null;
            if (candidate != null) {
                Optional<ProjectComparisonModelCache<ReusableModel>.Lease>
                        published = candidate.publish();
                candidate = null;
                if (published.isEmpty() && cache.isClosed()) {
                    return Publication.rejected();
                }
                // A cache that cannot retain this model, because an earlier
                // consumer still holds its generation, only costs the next
                // warm start. The comparison itself stays valid.
                display = published.orElse(null);
            }
            return display == null
                    ? Publication.acceptedWithoutLease()
                    : Publication.accepted(
                            new DisplayLease(display));
        }

        @Override
        public void close() {
            if (completed) {
                return;
            }
            completed = true;
            if (modelLease != null) {
                modelLease.close();
                modelLease = null;
            }
            closeCandidate(candidate);
            candidate = null;
            closeSnapshot(snapshotLease);
            snapshotLease = null;
            closeMutation(mutationLease);
            mutationLease = null;
        }
    }

    /**
     * Discriminates UI acceptance from optional reusable-model ownership.
     */
    public static final class Publication {

        private static final Publication REJECTED =
                new Publication(false, Optional.empty());
        private static final Publication ACCEPTED_WITHOUT_LEASE =
                new Publication(true, Optional.empty());

        private final boolean accepted;
        private final Optional<DisplayLease> displayLease;

        private Publication(boolean accepted,
                Optional<DisplayLease> displayLease) {
            this.accepted = accepted;
            this.displayLease = Objects.requireNonNull(
                    displayLease, "displayLease"); //$NON-NLS-1$
            if (!accepted && displayLease.isPresent()) {
                throw new IllegalArgumentException(
                        "Rejected publication cannot own a display lease"); //$NON-NLS-1$
            }
        }

        private static Publication accepted(
                DisplayLease displayLease) {
            return new Publication(true,
                    Optional.of(Objects.requireNonNull(
                            displayLease, "displayLease"))); //$NON-NLS-1$
        }

        private static Publication acceptedWithoutLease() {
            return ACCEPTED_WITHOUT_LEASE;
        }

        private static Publication rejected() {
            return REJECTED;
        }

        public boolean accepted() {
            return accepted;
        }

        public Optional<DisplayLease> displayLease() {
            return displayLease;
        }
    }

    /**
     * Keeps the reusable OLD model reachable for as long as its diff is shown.
     * <p>
     * The retained model is not immutable, so consumers that outlive the
     * displayed diff, such as script generation or a project update, must
     * {@link #retain()} it. Closing the lease then only stops the display; the
     * exclusive hold on the cached generation ends when the last consumer
     * releases it.
     */
    public final class DisplayLease implements AutoCloseable {

        private ProjectComparisonModelCache<ReusableModel>.Lease delegate;
        private int consumers;
        private boolean displayClosed;

        private DisplayLease(
                ProjectComparisonModelCache<ReusableModel>.Lease delegate) {
            this.delegate = Objects.requireNonNull(
                    delegate, "delegate"); //$NON-NLS-1$
        }

        /**
         * Holds the leased model for one consumer.
         *
         * @return a token that releases this consumer's hold exactly once
         */
        public Runnable retain() {
            synchronized (this) {
                if (delegate != null) {
                    consumers++;
                }
            }
            return new Runnable() {

                private boolean released;

                @Override
                public void run() {
                    synchronized (DisplayLease.this) {
                        if (released) {
                            return;
                        }
                        released = true;
                    }
                    releaseConsumer();
                }
            };
        }

        private void releaseConsumer() {
            ProjectComparisonModelCache<ReusableModel>.Lease close = null;
            synchronized (this) {
                if (consumers > 0) {
                    consumers--;
                }
                if (consumers == 0 && displayClosed) {
                    close = delegate;
                    delegate = null;
                }
            }
            closeLease(close);
        }

        @Override
        public void close() {
            ProjectComparisonModelCache<ReusableModel>.Lease close = null;
            synchronized (this) {
                displayClosed = true;
                if (consumers == 0) {
                    close = delegate;
                    delegate = null;
                }
            }
            closeLease(close);
        }

        private void closeLease(
                ProjectComparisonModelCache<ReusableModel>.Lease lease) {
            if (lease != null) {
                lease.close();
            }
        }
    }
}
