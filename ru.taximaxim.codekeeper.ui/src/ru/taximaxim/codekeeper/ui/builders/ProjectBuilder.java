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
package ru.taximaxim.codekeeper.ui.builders;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.pgcodekeeper.core.exception.MonitorCancelledRuntimeException;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.base.project.AbstractWorkDirs;

import ru.taximaxim.codekeeper.ui.DatabaseType;
import ru.taximaxim.codekeeper.ui.Log;
import ru.taximaxim.codekeeper.ui.UIConsts.PLUGIN_ID;
import ru.taximaxim.codekeeper.ui.builders.ProjectBuildContinuity.BuildContinuityProof;
import ru.taximaxim.codekeeper.ui.builders.ProjectBuildContinuity.Kind;
import ru.taximaxim.codekeeper.ui.builders.ProjectBuildDeltaClassifier.Reason;
import ru.taximaxim.codekeeper.ui.builders.ProjectBuildDeltaDiagnostics.DeltaPresence;
import ru.taximaxim.codekeeper.ui.libraries.LibraryUtils;
import ru.taximaxim.codekeeper.ui.localizations.Messages;
import ru.taximaxim.codekeeper.ui.pgdbproject.parser.PgDbParser;
import ru.taximaxim.codekeeper.ui.pgdbproject.parser.ProjectIndexBuildSupersededException;
import ru.taximaxim.codekeeper.ui.pgdbproject.parser.ProjectReceiveOnlyMode;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexBuildConfiguration;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexSchemaExclusions;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexSupportPolicy;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetry;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetry.BypassReason;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetry.Mode;
import ru.taximaxim.codekeeper.ui.utils.ProjectBuildProgressSink;
import ru.taximaxim.codekeeper.ui.utils.ProjectUtils;
import ru.taximaxim.codekeeper.ui.views.navigator.PgDecorator;

public class ProjectBuilder extends IncrementalProjectBuilder {

    @FunctionalInterface
    interface Commit {
        void run() throws IOException;
    }

    record PreparedUpdate(boolean restoredFromDisk, Commit commit,
            Commit warmPublication, Runnable discard,
            BooleanSupplier continuityAccepted) {

        PreparedUpdate(boolean restoredFromDisk, Commit commit,
                Commit warmPublication) {
            this(restoredFromDisk, commit, warmPublication,
                    () -> { });
        }

        PreparedUpdate(boolean restoredFromDisk, Commit commit,
                Commit warmPublication, Runnable discard) {
            this(restoredFromDisk, commit, warmPublication,
                    discard, () -> true);
        }

        static PreparedUpdate noOp() {
            return new PreparedUpdate(false, () -> { }, () -> { });
        }
    }

    @FunctionalInterface
    interface BuildPreparation {
        PreparedUpdate prepare() throws CoreException, IOException, InterruptedException;
    }

    /**
     * Everything one turn of this builder was decided by.
     *
     * <p>The configuration is the reading of the preference node this whole
     * cycle answers to - the classification that produced {@code classified},
     * and the build that classification asks for. It is captured here, at the
     * earliest moment of the cycle, and that is a decision rather than an
     * accident of where the code sits: see {@link #beginBuild}.</p>
     *
     * @param configuration the one configuration of this cycle, or null when
     *                      it could not be read, in which case
     *                      {@code classified} is a full build and every step
     *                      below reads for itself exactly as it used to
     */
    record BuildStart(
            ProjectBuildDeltaClassifier.Result classified,
            BuildContinuityProof proof,
            ProjectIndexBuildConfiguration configuration) {
    }

    private static final class BuildClassificationException
            extends RuntimeException {

        private static final long serialVersionUID =
                5161921084343301342L;

        private BuildClassificationException(
                CoreException cause) {
            super(cause);
        }
    }

    private final BuilderPublicationGate publicationGate = new BuilderPublicationGate();
    private final ProjectBuildContinuity buildContinuity =
            new ProjectBuildContinuity();

    static boolean publishUpdate(BuilderPublicationGate gate, long generation,
            PreparedUpdate update) throws IOException {
        return publishUpdate(gate, generation, update, null, null);
    }

    static boolean publishUpdate(BuilderPublicationGate gate, long generation,
            PreparedUpdate update, ProjectBuildContinuity continuity,
            BuildContinuityProof proof) throws IOException {
        if ((continuity == null) != (proof == null)) {
            throw new IllegalArgumentException(
                    "Continuity owner and proof must be paired"); //$NON-NLS-1$
        }
        boolean published = false;
        try {
            published = gate.publish(generation, () -> {
                if (update.restoredFromDisk()) {
                    update.warmPublication().run();
                } else {
                    update.commit().run();
                }
                if (continuity != null) {
                    if (update.continuityAccepted()
                            .getAsBoolean()) {
                        if (!continuity.accept(proof)) {
                            throw new IllegalStateException(
                                    "Current continuity proof was not accepted"); //$NON-NLS-1$
                        }
                    } else {
                        continuity.invalidate(proof);
                    }
                }
            });
            return published;
        } finally {
            if (!published) {
                if (continuity != null) {
                    continuity.invalidate(proof);
                }
                update.discard().run();
            }
        }
    }

    static boolean executeGeneration(BuilderPublicationGate gate, long generation,
            IProgressMonitor monitor, BuildPreparation preparation, Runnable cancellation)
            throws CoreException, IOException, InterruptedException {
        return executeGeneration(gate, generation, monitor,
                preparation, cancellation, null, null);
    }

    static boolean executeGeneration(BuilderPublicationGate gate,
            long generation, IProgressMonitor monitor,
            BuildPreparation preparation, Runnable cancellation,
            ProjectBuildContinuity continuity,
            BuildContinuityProof proof)
            throws CoreException, IOException, InterruptedException {
        Runnable invalidation = () -> {
            if (continuity != null) {
                continuity.invalidate(proof);
            }
            cancellation.run();
        };
        try {
            PreparedUpdate update = preparation.prepare();
            if (monitor != null && monitor.isCanceled()) {
                update.discard().run();
                throw new OperationCanceledException();
            }
            return publishUpdate(gate, generation, update,
                    continuity, proof);
        } catch (InterruptedException ex) {
            invalidateGeneration(gate, generation, invalidation);
            throw ex;
        } catch (OperationCanceledException | MonitorCancelledRuntimeException ex) {
            invalidateGeneration(gate, generation, invalidation);
            throw new OperationCanceledException();
        } catch (CoreException | IOException | RuntimeException ex) {
            invalidateGeneration(gate, generation, invalidation);
            throw ex;
        }
    }

    private static void invalidateGeneration(BuilderPublicationGate gate, long generation,
            Runnable cancellation) {
        gate.cancel(generation, cancellation);
    }

    @Override
    protected IProject[] build(int kind, Map<String, String> args,
            IProgressMonitor monitor) throws CoreException {
        IProject proj = getProject();
        if (!ProjectUtils.isPgCodeKeeperProject(proj) ||
                !ProjectUtils.checkVersion(proj, new StringBuilder())) {
            revokeBuildContinuity();
            return null;
        }

        BuilderPublicationGate.Started<BuildStart> started;
        try {
            started = publicationGate.start(generation -> {
                try {
                    return beginBuild(generation, kind, proj);
                } catch (CoreException ex) {
                    BuildContinuityProof failed =
                            buildContinuity
                                    .beginReconciliation(
                                            generation);
                    buildContinuity.invalidate(failed);
                    throw new BuildClassificationException(ex);
                }
            });
        } catch (BuildClassificationException ex) {
            throw (CoreException) ex.getCause();
        }
        long generation = started.generation();
        BuildStart build = started.value();
        if (build.classified().mode()
                == ProjectBuildDeltaClassifier.Mode.NO_OP) {
            try {
                executeGeneration(publicationGate, generation,
                        monitor, PreparedUpdate::noOp, () -> { },
                        buildContinuity, build.proof());
                return new IProject[] { proj };
            } catch (InterruptedException ex) {
                throw cancellationFromInterruption(ex,
                        monitor);
            } catch (IOException ex) {
                throw new CoreException(new Status(IStatus.ERROR,
                        PLUGIN_ID.THIS,
                        Messages.PgDbParser_error_loading_db, ex));
            } finally {
                SubMonitor.done(monitor);
            }
        }

        PgDbParser parser = null;
        // A build of a large project runs for a minute behind one unnamed
        // platform bar. One plan owns the whole run and every phase reports
        // into its own slice, so the user can tell which phase is running and
        // how far into it the build is.
        var progress = new ProjectBuildProgressSink();
        progress.begin(monitor);
        try {
            int[] buildType = { kind };
            ProjectBuildDeltaClassifier.Result parserClassification =
                    build.classified();
            parser = parserUnlessNoOp(parserClassification,
                    () -> PgDbParser.getParserForBuilder(
                            proj, buildType));
            PgDbParser completedParser = parser;
            ProjectBuildDeltaClassifier.Result completedClassification =
                    parserClassification;
            if (!executeGeneration(publicationGate, generation, monitor,
                    () -> prepareBuild(buildType[0], completedParser,
                            proj, monitor, completedClassification,
                            build.proof(), progress,
                            build.configuration()),
                    completedParser::cancelCurrentLoad,
                    buildContinuity, build.proof())) {
                return new IProject[] { proj };
            }
            progress.done();
        } catch (InterruptedException ex) {
            throw cancellationFromInterruption(ex, monitor);
        } catch (OperationCanceledException ex) {
            throw new OperationCanceledException();
        } catch (IOException ex) {
            if (isSupersededBuildFailure(ex)) {
                Log.log(Log.LOG_INFO, ex.getLocalizedMessage());
                return new IProject[] { proj };
            }
            throw new CoreException(
                    new Status(IStatus.ERROR, PLUGIN_ID.THIS,
                            Messages.PgDbParser_error_loading_db, ex));
        } catch (IllegalStateException ex) {
            throw new CoreException(
                    new Status(IStatus.ERROR, PLUGIN_ID.THIS, Messages.PgDbParser_error_loading_db, ex));
        } finally {
            // update decorators if any kind of build was run
            PgDecorator.update();
            SubMonitor.done(monitor);
        }
        return new IProject[] { proj };
    }

    /**
     * Opens one turn of this builder, and reads the index configuration the
     * whole turn will answer to.
     *
     * <p>This is the earliest moment of a build cycle, and the reading is
     * placed here on purpose. The classification below decides which files the
     * build is even told about, and it decides that by the configuration: a
     * change under an excluded schema is dropped from the batch, and a delta
     * that holds nothing else classifies as {@code NO_OP}. Nothing then runs -
     * no build starts, so no build publishes, so
     * {@code ProjectIndexPublicationGuard} never gets to ask whether the
     * configuration moved. A classification made under one configuration and a
     * build made under another therefore does not cost a rebuild; it costs the
     * change, silently, and the index keeps a definition the working tree no
     * longer has.</p>
     *
     * <p>So the earliest reading wins, and every later step is handed it
     * instead of asking the node again. A cycle bound to a configuration that
     * has since moved is not thereby made right - it is made <em>loud</em>: it
     * reaches the publication guard, which refuses it and asks for a rebuild.
     * That is the trade this takes deliberately. A thrown-away build is paid
     * once and announced; an index that quietly disagrees with the tree is
     * paid by everything that reads it afterwards.</p>
     *
     * <p>A configuration that cannot be read at all yields no snapshot. The
     * cycle then classifies as a full build, exactly as it did when the read
     * lived inside {@link #layout}, and every step below falls back to reading
     * for itself - which is what those steps did before this and is still what
     * they do when nobody hands them anything.</p>
     */
    private BuildStart beginBuild(long generation, int kind,
            IProject project) throws CoreException {
        boolean consultsDelta =
                kind == IncrementalProjectBuilder.AUTO_BUILD
                        || kind == IncrementalProjectBuilder.INCREMENTAL_BUILD;
        IResourceDelta delta = consultsDelta ? getDelta(project) : null;
        ProjectIndexBuildConfiguration configuration = null;
        String captureFailure = null;
        try {
            configuration = captureConfiguration(project);
        } catch (IllegalArgumentException
                | UnsupportedOperationException ex) {
            captureFailure = ex.getClass().getSimpleName();
        }
        ProjectBuildDeltaClassifier.Result classified;
        if (!consultsDelta) {
            classified = ProjectBuildDeltaClassifier.full(
                    Reason.DIRECT_FULL_BUILD);
        } else if (configuration == null) {
            classified = ProjectBuildDeltaClassifier.full(
                    Reason.CLASSIFICATION_FAILURE,
                    "ex=" + captureFailure); //$NON-NLS-1$
        } else {
            classified = guardNoOp(project,
                    classifyDelta(project, delta, configuration),
                    configuration);
        }
        BuildStart start = beginClassifiedBuild(
                buildContinuity, generation, classified, configuration);
        ProjectBuildDeltaDiagnostics.INSTANCE.publish(kind,
                presence(consultsDelta, delta), classified,
                start.classified(), start.proof().kind());
        return start;
    }

    /**
     * Refuses to believe "there is nothing to do" when the configuration that
     * decided it is no longer the settled one.
     *
     * <p>Every other verdict is checked by somebody. An incremental or full
     * classification asks for a build, the build stamps an identity, and the
     * publication guard compares that identity against the workbench before
     * anything is kept. {@code NO_OP} asks for nothing, so nothing downstream
     * ever runs and nothing ever compares: the cycle ends inside this method
     * or it ends nowhere. That is the whole reason this exists here rather
     * than one layer down with the rest of the guarding.</p>
     *
     * <p>What it costs when it fires is a full classification, and a full
     * classification is not a rebuild - it is the repair path, which begins
     * by validating the index it already has. An index that was in fact fine
     * is confirmed warm; one that quietly lost the change is the case this is
     * for. The reverse mistake has no such floor: a dropped change stays
     * dropped until something unrelated forces a rebuild, and until then
     * every reader of the index is answered from a file the working tree no
     * longer holds.</p>
     *
     * <p>Only {@code NO_OP} is guarded. A verdict that asks for work is left
     * exactly as classified, because the work it asks for carries the same
     * configuration into the publication guard, which asks this same question
     * with the same fail-closed answer.</p>
     *
     * @param classified what the delta was classified as
     * @param build      the configuration it was classified by, or null when
     *                   the cycle read none and this cannot be asked
     * @return the classification to act on
     */
    static ProjectBuildDeltaClassifier.Result guardNoOp(IProject project,
            ProjectBuildDeltaClassifier.Result classified,
            ProjectIndexBuildConfiguration build) {
        if (classified.mode() != ProjectBuildDeltaClassifier.Mode.NO_OP) {
            return classified;
        }
        Optional<ProjectIndexTelemetry.ConfigurationGuard> refusal =
                PgDbParser.projectIndexConfigurationRefusal(project, build);
        if (refusal.isEmpty()) {
            return classified;
        }
        return ProjectBuildDeltaClassifier.full(Reason.CONFIGURATION_MOVED,
                "guard=" + refusal.orElseThrow().token()); //$NON-NLS-1$
    }

    /**
     * Takes the one reading of the index configuration a build cycle gets.
     *
     * @param project project about to be built
     * @return the configuration this cycle is bound to, never null
     * @throws IllegalArgumentException if the configuration cannot be read as
     *                                  one, which is what stamping an identity
     *                                  from it would have raised anyway
     */
    static ProjectIndexBuildConfiguration captureConfiguration(
            IProject project) {
        return ProjectIndexBuildConfiguration.capture(
                () -> PgDbParser.getEffectiveProjectIndexConfiguration(
                        project));
    }

    private static DeltaPresence presence(boolean consultsDelta,
            IResourceDelta delta) {
        if (!consultsDelta) {
            return DeltaPresence.NOT_CONSULTED;
        }
        return delta == null
                ? DeltaPresence.ABSENT : DeltaPresence.PRESENT;
    }

    static BuildStart beginClassifiedBuild(
            ProjectBuildContinuity continuity, long generation,
            ProjectBuildDeltaClassifier.Result classified) {
        return beginClassifiedBuild(continuity, generation, classified, null);
    }

    static BuildStart beginClassifiedBuild(
            ProjectBuildContinuity continuity, long generation,
            ProjectBuildDeltaClassifier.Result classified,
            ProjectIndexBuildConfiguration configuration) {
        Objects.requireNonNull(continuity, "continuity"); //$NON-NLS-1$
        BuildContinuityProof proof = switch (classified.mode()) {
        case NO_OP -> continuity.beginNoOp(generation);
        case INCREMENTAL -> continuity.beginIncremental(
                generation, classified.relativePaths());
        case FULL -> continuity.beginReconciliation(
                generation);
        };
        if (classified.mode()
                == ProjectBuildDeltaClassifier.Mode.INCREMENTAL
                && proof.kind() == Kind.RECONCILIATION) {
            classified = ProjectBuildDeltaClassifier.full(
                    Reason.CONTINUITY_RECONCILIATION,
                    "paths=" + classified.relativePaths().size()); //$NON-NLS-1$
        }
        return new BuildStart(classified, proof, configuration);
    }

    static OperationCanceledException cancellationFromInterruption(
            InterruptedException cause, IProgressMonitor monitor) {
        if (monitor == null || !monitor.isCanceled()) {
            Thread.currentThread().interrupt();
        }
        var cancelled = new OperationCanceledException();
        cancelled.initCause(cause);
        return cancelled;
    }

    static boolean isSupersededBuildFailure(Throwable failure) {
        return failure
                instanceof ProjectIndexBuildSupersededException;
    }

    PreparedUpdate prepareBuild(int kind, PgDbParser parser,
            IProject proj, IProgressMonitor monitor,
            ProjectBuildDeltaClassifier.Result classified,
            BuildContinuityProof proof,
            ProjectBuildProgressSink progress,
            ProjectIndexBuildConfiguration configuration)
            throws CoreException, IOException, InterruptedException {
        if (receiveOnly(proj, configuration)) {
            return receiveOnlyBypass();
        }
        return switch (kind) {
        case IncrementalProjectBuilder.AUTO_BUILD, IncrementalProjectBuilder.INCREMENTAL_BUILD ->
            prepareIncrement(classified, parser, proj, monitor,
                    proof, progress, configuration);
        case IncrementalProjectBuilder.FULL_BUILD ->
            prepareFullBuild(parser, proj, monitor, proof, progress,
                    configuration);
        default -> throw new IllegalStateException("Unknown build type!"); //$NON-NLS-1$
        };
    }

    /**
     * Whether this build is refused because the project is only ever updated
     * from a database.
     *
     * <p>The answer comes from the cycle's configuration when there is one:
     * {@code receiveOnly} is a field of the very snapshot the index is
     * identified by, so reading the node for it again would be the same second
     * reading this class has just stopped making everywhere else. A cycle
     * without a snapshot asks {@link ProjectReceiveOnlyMode}, which is the
     * only reading there is in that case and is what this always did.</p>
     */
    private static boolean receiveOnly(IProject project,
            ProjectIndexBuildConfiguration build) {
        return build == null
                ? ProjectReceiveOnlyMode.isEnabled(project)
                : build.configuration().receiveOnly();
    }

    /**
     * An honest, silence-free refusal in place of a build, for every kind
     * Eclipse can ask this builder for.
     *
     * <p>A receive-only project has no use for the background index at any
     * depth a build could ask for - not a cold build, not a repair, not a
     * single changed file - so {@link ProjectReceiveOnlyMode#isEnabled} is
     * the first thing {@link #prepareBuild} asks, before the build kind is
     * ever dispatched to {@link #prepareIncrement} or
     * {@link #prepareFullBuild}. Those two are what actually open the
     * project-index store and parse a file; refusing here means neither one
     * ever runs. The delta was already classified and the parser already
     * looked up by the time {@code prepareBuild} is reached - both are
     * inspections of state Eclipse already holds, not work this preference
     * has any reason to skip - but this is the last point before either one
     * would otherwise touch the store or the parser.</p>
     *
     * <p>The refusal is asked here and not one level down, inside {@code
     * PgDbParser.prepareIncrementalProjectIndex}, because nothing that method
     * can return is free of side effects: every value it hands back already
     * prepared something, if only a storage swap, so a refusal placed there
     * could only stay silent or fall back to the same full-project rebuild
     * every other refusal takes - both of which this preference is meant to
     * avoid, not reproduce. {@link PreparedUpdate#noOp} has neither problem:
     * it is the exact value an unchanged delta already publishes, so the same
     * shape carries this refusal too, with one line of telemetry standing in
     * for what would otherwise be silence.</p>
     *
     * @return an update that changes nothing when committed or discarded
     */
    static PreparedUpdate receiveOnlyBypass() {
        ProjectIndexTelemetry.Run telemetry =
                ProjectIndexTelemetry.INSTANCE.start(Mode.BYPASS);
        telemetry.bypass(BypassReason.DISABLED_BY_PREFERENCE);
        telemetry.close();
        return PreparedUpdate.noOp();
    }

    @Override
    protected void clean(IProgressMonitor monitor) throws CoreException {
        revokeBuildContinuity();
        PgDbParser.clean(getProject());
    }

    private void revokeBuildContinuity() {
        var started = publicationGate.start(
                buildContinuity::beginReconciliation);
        publicationGate.cancel(started.generation(),
                () -> buildContinuity.invalidate(
                        started.value()));
    }

    private static PreparedUpdate adapt(boolean restoredFromDisk,
            PgDbParser.PreparedUpdate update, String projectName, IProgressMonitor monitor) {
        return new PreparedUpdate(restoredFromDisk, () -> update.commit(projectName, monitor),
                () -> update.commit(projectName, monitor),
                update::discard, update::wasContinuityAccepted);
    }

    private PreparedUpdate prepareIncrement(
            ProjectBuildDeltaClassifier.Result result,
            PgDbParser parser, IProject project,
            IProgressMonitor monitor,
            BuildContinuityProof proof,
            ProjectBuildProgressSink progress,
            ProjectIndexBuildConfiguration configuration)
            throws CoreException, InterruptedException, IOException {
        if (result == null) {
            result = ProjectBuildDeltaClassifier.full();
        }
        return switch (result.mode()) {
        case NO_OP -> PreparedUpdate.noOp();
        case FULL -> prepareFullBuild(parser, project, monitor,
                proof, progress, configuration);
        case INCREMENTAL -> {
            PgDbParser.PreparedIncrementalProjectIndex incremental =
                    parser.prepareIncrementalProjectIndex(project,
                            result.relativePaths(), monitor, proof,
                            progress, configuration);
            PreparedUpdate prepared = adapt(
                    incremental.prepared().restoredFromDisk(),
                    incremental.prepared().update(),
                    project.getName(), monitor);
            yield incremental.fullBuild()
                    ? prepareLibraries(prepared,
                            () -> LibraryUtils.create(project))
                    : prepared;
        }
        };
    }

    private ProjectBuildDeltaClassifier.Result classifyDelta(
            IProject project, IResourceDelta delta,
            ProjectIndexBuildConfiguration configuration)
            throws CoreException {
        DatabaseType databaseType = ProjectUtils.getDatabaseType(project);
        try {
            return ProjectBuildDeltaCollector.classify(delta,
                    layout(project, databaseType, configuration));
        } catch (IllegalArgumentException
                | UnsupportedOperationException ex) {
            return ProjectBuildDeltaClassifier.full(
                    Reason.CLASSIFICATION_FAILURE,
                    "ex=" + ex.getClass().getSimpleName()); //$NON-NLS-1$
        }
    }

    static <T> T parserUnlessNoOp(
            ProjectBuildDeltaClassifier.Result classified,
            Supplier<T> parserFactory) {
        return classified.mode()
                == ProjectBuildDeltaClassifier.Mode.NO_OP
                        ? null : parserFactory.get();
    }

    /**
     * Prepares a build of the whole project. A build gets here when nothing
     * proved a narrower one was enough, which says nothing about how much of
     * the project actually moved - so the index is repaired from the files it
     * disagrees with when there are few of them, and rebuilt otherwise.
     *
     * <p>The libraries are prepared either way. A repair leaves them exactly
     * as a rebuild does, and a full build is the only place that creates
     * them.</p>
     */
    private static PreparedUpdate prepareFullBuild(PgDbParser parser,
            IProject project, IProgressMonitor monitor,
            BuildContinuityProof proof,
            ProjectBuildProgressSink progress,
            ProjectIndexBuildConfiguration configuration)
            throws InterruptedException, IOException, CoreException {
        PgDbParser.PreparedProjectIndex prepared =
                parser.prepareRepairedProjectIndex(project, monitor, proof,
                        progress, configuration);
        return prepareLibraries(adapt(prepared.restoredFromDisk(),
                prepared.update(), project.getName(), monitor),
                () -> LibraryUtils.create(project));
    }

    static PreparedUpdate prepareLibraries(PreparedUpdate prepared,
            Commit preparation) throws IOException {
        try {
            preparation.run();
            return prepared;
        } catch (IOException | RuntimeException ex) {
            prepared.discard().run();
            throw ex;
        }
    }

    /**
     * The shape the delta is read against, taken from the one configuration
     * this cycle captured.
     *
     * <p>Nothing here asks the preference node. The build this classification
     * leads to is bound to {@code build}, and a classification bound to
     * anything else can drop a change the build would have indexed - see
     * {@link #beginBuild} for why that loss is silent and why this is the
     * reading that wins.</p>
     *
     * <p>The exclusions are taken as the raw value of the captured
     * configuration and parsed here rather than read from
     * {@link ProjectIndexBuildConfiguration#excludedSchemas()}, which is the
     * same value by construction: the snapshot parses that very string once,
     * for a project of any type, and hands out what it got. The two used to
     * differ - the snapshot answered an empty set for anything but PostgreSQL,
     * because only PostgreSQL had an index - and the re-parse below is what is
     * left of that. It cannot throw here, the capture having already read the
     * same value.</p>
     *
     * @param build the configuration this cycle captured
     */
    static ProjectBuildDeltaCollector.Layout layout(
            IProject project, DatabaseType databaseType,
            ProjectIndexBuildConfiguration build) {
        var workDirs = ProjectUtils.createWorkDirs(databaseType,
                AbstractWorkDirs.resolveAltDirsFile(
                        ProjectUtils.getPath(project)));
        List<String> schemaContainer = Arrays.stream(
                workDirs.getDirNameForType(DbObjType.SCHEMA)
                        .replace('\\', '/').split("/")) //$NON-NLS-1$
                .filter(segment -> !segment.isBlank())
                .toList();
        var configuration = build.configuration();
        Set<String> exclusions = ProjectIndexSchemaExclusions.parse(
                configuration.excludedSchemas());
        return new ProjectBuildDeltaCollector.Layout(
                ProjectUtils.getDefaultTopLevelDirNames(workDirs),
                schemaContainer, workDirs.isSplitBySchema(),
                exclusions, configuration.ignorePrivileges(),
                configuration.incrementalAddedFiles());
    }
}
