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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import java.util.stream.Stream;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ProjectScope;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IURIEditorInput;
import org.eclipse.ui.editors.text.TextFileDocumentProvider;
import org.eclipse.ui.ide.ResourceUtil;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.pgcodekeeper.core.database.api.loader.IDumpLoader;
import org.pgcodekeeper.core.database.api.loader.ILoader;
import org.pgcodekeeper.core.database.api.loader.IProjectInputFingerprintCapture;
import org.pgcodekeeper.core.database.api.loader.IProjectLoader;
import org.pgcodekeeper.core.database.api.loader.ProjectInputFingerprint;
import org.pgcodekeeper.core.database.api.parser.ParserListenerMode;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.api.schema.meta.IMetaContainer;
import org.pgcodekeeper.core.database.pg.utils.PgDiffUtils;
import org.pgcodekeeper.core.database.base.parser.AntlrError;
import org.pgcodekeeper.core.database.base.parser.ErrorTypes;
import org.pgcodekeeper.core.database.base.schema.meta.MetaStatement;
import org.pgcodekeeper.core.database.base.schema.meta.MetaUtils;
import org.pgcodekeeper.core.settings.ISettings;
import org.pgcodekeeper.core.library.LibraryXmlStore;
import org.pgcodekeeper.core.utils.FileUtils;

import ru.taximaxim.codekeeper.ui.Activator;
import ru.taximaxim.codekeeper.ui.DatabaseType;
import ru.taximaxim.codekeeper.ui.Log;
import ru.taximaxim.codekeeper.ui.PerformanceTelemetry;
import ru.taximaxim.codekeeper.ui.UIConsts.MARKER;
import ru.taximaxim.codekeeper.ui.UIConsts.PREF;
import ru.taximaxim.codekeeper.ui.UIConsts.PROJ_PREF;
import ru.taximaxim.codekeeper.ui.builders.ProjectBuildContinuity.BuildContinuityProof;
import ru.taximaxim.codekeeper.ui.builders.ProjectBuildContinuity.Kind;
import ru.taximaxim.codekeeper.ui.libraries.LibraryUtils;
import ru.taximaxim.codekeeper.ui.localizations.Messages;
import ru.taximaxim.codekeeper.ui.projectindex.IndexPathOrigin;
import ru.taximaxim.codekeeper.ui.projectindex.IndexPathRef;
import ru.taximaxim.codekeeper.ui.projectindex.PackedDefinition;
import ru.taximaxim.codekeeper.ui.projectindex.FileContribution;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectFileStamp;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexBuildConfiguration;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexConfiguration;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexConfiguration.GlobalSettings;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexConfiguration.ProjectOverrides;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexConfigDigest;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexConfigurationDiagnostics;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexConfigurationDiagnostics.ParserPresence;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexFiles;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexIdentity;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexIncrementalPlanner;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexDelta;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexDefinitionSubject;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexPathResolver;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexPublicationGuard;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexRepairPlanner;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexRevision;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexSnapshotFactory;
import ru.taximaxim.codekeeper.ui.projectindex.ReferenceMatchKey;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexState;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexSupportPolicy;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetry;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetry.BypassReason;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetry.ConfigurationGuard;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetry.Mode;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetry.PersistenceReason;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetry.PersistenceStatus;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetry.Phase;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetry.RepairRefusal;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexStore;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexWarmValidator;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexWarmValidator.CurrentFile;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectInputDeltaPolicy;
import ru.taximaxim.codekeeper.ui.settings.UISettings;
import ru.taximaxim.codekeeper.ui.utils.FileUtilsUi;
import ru.taximaxim.codekeeper.ui.utils.ProjectBuildProgressSink;
import ru.taximaxim.codekeeper.ui.utils.ProjectUtils;
import ru.taximaxim.codekeeper.ui.utils.UIMonitor;

public final class PgDbParser implements IResourceChangeListener {

    record LoadedReferences(
            ProjectReferencesStorage storage,
            List<Object> errors,
            List<ProjectInputFingerprint> inputFingerprints) {

        private LoadedReferences(
                ProjectReferencesStorage storage,
                List<Object> errors) {
            this(storage, errors, null);
        }
    }

    private record CapturedProjectFile(ProjectFileStamp stamp,
            byte[] bytes, Path absolutePath) { }

    /**
     * Decides from the definitions a parse produced whether the file can still
     * replace the contribution the index holds for it. Definitions are
     * complete as soon as the file is parsed, so a file that changed a
     * definition's shape can be rejected before its analysis is paid for.
     */
    @FunctionalInterface
    interface ParsedDefinitionGate {

        boolean acceptsParsedDefinitions(
                Map<String, List<MetaStatement>> definitions);
    }

    /**
     * One analysed file of an incremental batch.
     *
     * @param stamp               stamp taken after the analysis
     * @param contribution        contribution the file was analysed into
     * @param analysisReusable    whether the analysis may be reused at all
     * @param blockedOnlyByErrors whether the reported errors are the only thing
     *                            standing in the way. The file still holds the
     *                            definitions the index holds for it, it did not
     *                            move while it was being read, and no other file
     *                            owns a definition it carries - so the index has
     *                            nothing to rebuild, and refusing this batch
     *                            costs the batch and not the index
     */
    record LoadedBatchFile(ProjectFileStamp stamp,
            FileContribution contribution, boolean analysisReusable,
            boolean blockedOnlyByErrors) {

        LoadedBatchFile {
            Objects.requireNonNull(stamp, "stamp"); //$NON-NLS-1$
            Objects.requireNonNull(contribution, "contribution"); //$NON-NLS-1$
            if (analysisReusable && blockedOnlyByErrors) {
                throw new IllegalArgumentException(
                        "A reusable analysis is blocked by nothing"); //$NON-NLS-1$
            }
        }
    }

    /**
     * What collecting one incremental batch produced.
     *
     * <p>A batch that may not be applied says why in the only terms that
     * change what happens next. A file whose <em>definitions</em> moved makes
     * the index disagree with every other file that could see them, and only a
     * whole rebuild settles that. A file that merely did not parse leaves the
     * index exactly as correct as it was a moment ago: the batch is dropped,
     * its errors are marked, and nothing is rebuilt. Both used to arrive here
     * as one null.</p>
     *
     * @param changes        the replacements, or null when the batch may not be
     *                       applied to the index
     * @param analysisErrors whether the batch was turned down by errors alone
     * @param loadedFiles    how many files of the batch the loader was asked
     *                       for, which is the prefix whose markers this batch
     *                       is answerable for
     */
    record IncrementalBatch(List<ProjectIndexDelta.Change> changes,
            boolean analysisErrors, int loadedFiles) {

        IncrementalBatch {
            if (changes != null && analysisErrors) {
                throw new IllegalArgumentException(
                        "An applied batch reported no analysis errors"); //$NON-NLS-1$
            }
        }

        static IncrementalBatch applied(
                List<ProjectIndexDelta.Change> changes, int loadedFiles) {
            return new IncrementalBatch(
                    Objects.requireNonNull(changes, "changes"), //$NON-NLS-1$
                    false, loadedFiles);
        }

        /** The index has to be rebuilt before this batch can be believed. */
        static IncrementalBatch unsafe(int loadedFiles) {
            return new IncrementalBatch(null, false, loadedFiles);
        }

        /** Nothing about the index moved; a file of the batch did not parse. */
        static IncrementalBatch analysisErrors(int loadedFiles) {
            return new IncrementalBatch(null, true, loadedFiles);
        }
    }

    /** Captures, parses and analyses one file of an incremental batch. */
    @FunctionalInterface
    interface IncrementalBatchFileLoader {

        /**
         * @param index position of the file in the batch
         * @param path  file to load
         * @return the analysed file, or null when it already lost the right to
         *         replace its indexed contribution
         */
        LoadedBatchFile load(int index, IndexPathRef path)
                throws IOException, InterruptedException;
    }

    private record IndexSnapshot(long configurationEpoch,
            ProjectReferenceIndex storage) { }

    private record RetiredIndex(ProjectReferenceIndex storage,
            AutoCloseable resource) { }

    /**
     * Exact process-local identity of one validated background-index
     * publication. The full Get Changes input digest is kept separately by the
     * reusable project-model cache.
     */
    public record ProjectSnapshotToken(
            ProjectIndexIdentity identity,
            long generation,
            Optional<ProjectIndexRevision> revision,
            long publicationSequence) {

        public ProjectSnapshotToken {
            Objects.requireNonNull(identity, "identity"); //$NON-NLS-1$
            Objects.requireNonNull(revision, "revision"); //$NON-NLS-1$
            if (generation < 0 || publicationSequence < 0) {
                throw new IllegalArgumentException(
                        "Project snapshot values must not be negative"); //$NON-NLS-1$
            }
        }
    }

    /**
     * Pins the exact live reference-index generation only while one comparison
     * is being validated. The reusable database model has its own independent
     * lease and may outlive this object.
     */
    public final class ValidatedProjectSnapshotLease
            implements AutoCloseable {

        private final ProjectReferenceIndex expectedStorage;
        private final IncrementalProjectReferenceIndex.AnalysisLease analysis;
        private final long expectedConfigurationEpoch;
        private final long expectedContentEpoch;
        private final ProjectSnapshotToken token;
        private boolean open = true;

        private ValidatedProjectSnapshotLease(
                ProjectReferenceIndex expectedStorage,
                IncrementalProjectReferenceIndex.AnalysisLease analysis,
                long expectedConfigurationEpoch,
                long expectedContentEpoch,
                ProjectSnapshotToken token) {
            this.expectedStorage = expectedStorage;
            this.analysis = analysis;
            this.expectedConfigurationEpoch =
                    expectedConfigurationEpoch;
            this.expectedContentEpoch = expectedContentEpoch;
            this.token = token;
        }

        public ProjectSnapshotToken token() {
            return token;
        }

        public boolean isCurrent() {
            synchronized (stateLock) {
                return open && isValidatedSnapshotCurrentLocked(
                        expectedStorage, analysis,
                        expectedConfigurationEpoch,
                        expectedContentEpoch, token);
            }
        }

        @Override
        public void close() {
            boolean release;
            synchronized (stateLock) {
                release = open;
                open = false;
            }
            if (release) {
                analysis.close();
            }
        }
    }

    /**
     * Pins the project/configuration mutation epochs independently of the
     * background reference index. This is the final publication gate for
     * comparison results, including cold comparisons for which no index
     * snapshot is available.
     */
    public final class ProjectMutationLease implements AutoCloseable {

        private final long expectedConfigurationEpoch;
        private final long expectedContentEpoch;
        private boolean open = true;

        private ProjectMutationLease(long expectedConfigurationEpoch,
                long expectedContentEpoch) {
            this.expectedConfigurationEpoch =
                    expectedConfigurationEpoch;
            this.expectedContentEpoch = expectedContentEpoch;
        }

        public boolean isCurrent() {
            synchronized (stateLock) {
                return isCurrentLocked();
            }
        }

        /**
         * Linearizes a short publication action with project mutations.
         * Expensive validation and resource disposal must happen outside this
         * callback.
         */
        public <T> Optional<T> commitIfCurrent(
                Supplier<T> publication) {
            Objects.requireNonNull(publication,
                    "publication"); //$NON-NLS-1$
            synchronized (stateLock) {
                if (!isCurrentLocked()) {
                    return Optional.empty();
                }
                return Optional.ofNullable(publication.get());
            }
        }

        private boolean isCurrentLocked() {
            return open
                    && configurationEpoch
                            == expectedConfigurationEpoch
                    && contentEpoch == expectedContentEpoch;
        }

        @Override
        public void close() {
            synchronized (stateLock) {
                open = false;
            }
        }
    }

    private static final class IncrementalPublicationCancelledException
            extends InterruptedException {

        private static final long serialVersionUID =
                -7272364240745130467L;

        private IncrementalPublicationCancelledException(
                String message) {
            super(message);
        }
    }

    public record PreparedProjectIndex(boolean restoredFromDisk,
            PreparedUpdate update) {

        public PreparedProjectIndex {
            Objects.requireNonNull(update, "update"); //$NON-NLS-1$
        }
    }

    public record PreparedIncrementalProjectIndex(boolean fullBuild,
            PreparedProjectIndex prepared) {

        public PreparedIncrementalProjectIndex {
            Objects.requireNonNull(prepared, "prepared"); //$NON-NLS-1$
        }
    }

    private record IncrementalPublication(
            ProjectIndexBuildScope scope,
            ProjectReferenceIndex expectedLiveStorage,
            PackedProjectReferenceIndex bootstrapStorage,
            IncrementalProjectReferenceIndex.AnalysisLease validationLease,
            PackedProjectReferenceIndex.PublicationLease publicationLease,
            ProjectIndexRevision revision,
            ProjectIndexDelta replacements,
            Set<IndexPathRef> addedPaths,
            BuildContinuityProof continuityProof,
            boolean trustedContinuity) {

        private IncrementalPublication {
            Objects.requireNonNull(scope, "scope"); //$NON-NLS-1$
            Objects.requireNonNull(expectedLiveStorage,
                    "expectedLiveStorage"); //$NON-NLS-1$
            Objects.requireNonNull(validationLease,
                    "validationLease"); //$NON-NLS-1$
            if ((publicationLease == null) != (revision == null)) {
                throw new IllegalArgumentException(
                        "Packed publication lease and revision must be paired"); //$NON-NLS-1$
            }
            Objects.requireNonNull(replacements,
                    "replacements"); //$NON-NLS-1$
            addedPaths = Set.copyOf(addedPaths);
            if (replacements.changes().size()
                            > ProjectIndexIncrementalPlanner.MAX_BATCH_SIZE
                    || replacements.changes().stream().anyMatch(
                            change -> change.operation()
                                    != ProjectIndexDelta.Operation.REPLACE)) {
                throw new IllegalArgumentException(
                        "Incremental publication requires 1 to " //$NON-NLS-1$
                                + ProjectIndexIncrementalPlanner.MAX_BATCH_SIZE
                                + " replacements"); //$NON-NLS-1$
            }
        }

        private IProject project() {
            return scope.project();
        }

        private ProjectIndexBuildCoordinator.Request request() {
            return scope.request();
        }

        private List<IndexPathRef> paths() {
            return replacements.changes().stream()
                    .map(ProjectIndexDelta.Change::path)
                    .toList();
        }

        private List<String> relativePaths() {
            return paths().stream()
                    .map(IndexPathRef::relativePath)
                    .toList();
        }
    }

    public final class PreparedUpdate {

        private ProjectReferenceIndex storage;
        private final ProjectIndexBuildCoordinator.Prepared persistent;
        private final Runnable markerPublication;
        private final long configurationEpoch;
        private final boolean ownsStorage;
        private final ProjectIndexTelemetry.Run telemetry;
        private final BuildContinuityProof continuityProof;
        private final ProjectIndexBuildScope scope;
        private final boolean markersOnly;
        /**
         * The project whose whole index this publication replaces, or null
         * when it replaces no such thing - an increment appended to an index
         * that stays, a single re-read file, a batch that only drew markers.
         */
        private final IProject publishedProjectIndex;
        private IncrementalPublication incremental;
        private boolean completed;
        private boolean published;
        private boolean continuityAccepted;

        private PreparedUpdate(ProjectReferenceIndex storage,
                Runnable markerPublication, long configurationEpoch) {
            this(storage, markerPublication, configurationEpoch, true);
        }

        private PreparedUpdate(ProjectReferenceIndex storage,
                Runnable markerPublication, long configurationEpoch,
                boolean ownsStorage) {
            this(storage, markerPublication, configurationEpoch, ownsStorage,
                    null);
        }

        private PreparedUpdate(ProjectReferenceIndex storage,
                Runnable markerPublication, long configurationEpoch,
                boolean ownsStorage, ProjectIndexTelemetry.Run telemetry) {
            this(storage, markerPublication, configurationEpoch,
                    ownsStorage, telemetry, null);
        }

        private PreparedUpdate(ProjectReferenceIndex storage,
                Runnable markerPublication, long configurationEpoch,
                boolean ownsStorage, ProjectIndexTelemetry.Run telemetry,
                BuildContinuityProof continuityProof) {
            this(storage, markerPublication, configurationEpoch, ownsStorage,
                    telemetry, continuityProof, null);
        }

        /**
         * @param publishedProjectIndex the project this storage is the whole
         *                              index of, or null when it is a narrower
         *                              thing than that
         */
        private PreparedUpdate(ProjectReferenceIndex storage,
                Runnable markerPublication, long configurationEpoch,
                boolean ownsStorage, ProjectIndexTelemetry.Run telemetry,
                BuildContinuityProof continuityProof,
                IProject publishedProjectIndex) {
            this.storage = Objects.requireNonNull(storage, "storage"); //$NON-NLS-1$
            this.persistent = null;
            this.markerPublication = markerPublication;
            this.configurationEpoch = configurationEpoch;
            this.ownsStorage = ownsStorage;
            this.telemetry = telemetry;
            this.continuityProof = continuityProof;
            this.scope = null;
            this.incremental = null;
            this.markersOnly = false;
            this.publishedProjectIndex = publishedProjectIndex;
        }

        /**
         * An update that draws the error markers of one batch and publishes
         * nothing else. It owns no storage, moves no index and accepts no
         * continuity - committing it is exactly as much of a publication as
         * the markers themselves.
         */
        private PreparedUpdate(Runnable markerPublication,
                long configurationEpoch,
                ProjectIndexTelemetry.Run telemetry) {
            this.storage = null;
            this.persistent = null;
            this.markerPublication = Objects.requireNonNull(
                    markerPublication, "markerPublication"); //$NON-NLS-1$
            this.configurationEpoch = configurationEpoch;
            this.ownsStorage = false;
            this.telemetry = telemetry;
            this.continuityProof = null;
            this.scope = null;
            this.incremental = null;
            this.markersOnly = true;
            this.publishedProjectIndex = null;
        }

        private PreparedUpdate(ProjectIndexBuildCoordinator.Prepared persistent,
                Runnable markerPublication, long configurationEpoch) {
            this(persistent, markerPublication, configurationEpoch,
                    null, null);
        }

        private PreparedUpdate(ProjectIndexBuildCoordinator.Prepared persistent,
                Runnable markerPublication, long configurationEpoch,
                BuildContinuityProof continuityProof,
                ProjectIndexBuildScope scope) {
            this.storage = null;
            this.persistent = Objects.requireNonNull(persistent, "persistent"); //$NON-NLS-1$
            this.markerPublication = markerPublication;
            this.configurationEpoch = configurationEpoch;
            this.ownsStorage = false;
            this.telemetry = null;
            this.continuityProof = continuityProof;
            this.scope = scope;
            this.incremental = null;
            this.markersOnly = false;
            // A persistent build always produces the index of a whole project;
            // the only reason this can be null is a caller that named none.
            this.publishedProjectIndex =
                    scope == null ? null : scope.project();
        }

        private PreparedUpdate(IncrementalPublication incremental,
                Runnable markerPublication, long configurationEpoch,
                ProjectIndexTelemetry.Run telemetry) {
            this.storage = null;
            this.persistent = null;
            this.markerPublication = markerPublication;
            this.configurationEpoch = configurationEpoch;
            this.ownsStorage = false;
            this.telemetry = telemetry;
            this.continuityProof = incremental.continuityProof();
            this.scope = incremental.scope();
            this.incremental = Objects.requireNonNull(
                    incremental, "incremental"); //$NON-NLS-1$
            this.markersOnly = false;
            // An increment is appended to an index that stays; what it changed
            // is the files it named, and those are already known to have moved.
            this.publishedProjectIndex = null;
        }

        public boolean wasPublished() {
            return published;
        }

        public boolean wasContinuityAccepted() {
            return continuityAccepted;
        }

        public void commit(String name, IProgressMonitor monitor) throws IOException {
            if (completed) {
                return;
            }
            BooleanSupplier cancelled = () -> monitor != null
                    && monitor.isCanceled() || !isCurrent(configurationEpoch)
                    || continuityProof != null
                            && !continuityProof.isCurrent();
            if (markersOnly) {
                publishMarkers(cancelled);
                return;
            }
            if (incremental != null) {
                publishIncremental(cancelled, monitor);
                return;
            }
            if (persistent == null) {
                publishMemory(cancelled, monitor);
                return;
            }

            Optional<ConfigurationGuard> refusal = configurationRefusal();
            if (refusal.isPresent()) {
                completed = true;
                persistent.refuseConfiguration(refusal.orElseThrow());
                projectIndexContinuity.invalidate(continuityProof);
                afterConfigurationRefusal(refusal.orElseThrow());
                return;
            }

            ProjectIndexBuildCoordinator.Publication publication;
            try {
                publication = persistent.publish(cancelled);
            } catch (ProjectIndexBuildCoordinator.StaleBuildException ex) {
                completed = true;
                projectIndexContinuity.invalidate(continuityProof);
                if (scope != null) {
                    projectIndexRebuild.accept(scope.project());
                }
                return;
            } catch (InterruptedException ex) {
                completed = true;
                if (monitor != null && monitor.isCanceled()) {
                    throw new OperationCanceledException();
                }
                return;
            }
            boolean accepted = false;
            RetiredIndex retired = null;
            try (publication) {
                if (publication.persistenceFailure() != null) {
                    Log.log(Log.LOG_DEBUG,
                            Messages.PgDbParser_serialize_error,
                            publication.persistenceFailure());
                }
                synchronized (publicationEventLock) {
                    synchronized (stateLock) {
                        if (cancelled.getAsBoolean()) {
                            completed = true;
                        } else {
                            if (samePersistentRevisionLocked(
                                    publication)) {
                                publication.retainExistingStorage();
                            } else {
                                retired = swapStorageLocked(
                                        publication.storage());
                                publication.transferStorage();
                            }
                            continuityAccepted =
                                    projectIndexContinuity
                                            .acceptReconciliation(
                                    continuityProof,
                                    configurationEpoch,
                                    referencesStorage);
                            completed = true;
                            accepted = true;
                            published = true;
                        }
                    }
                    if (accepted) {
                        if (retired != null) {
                            closeRetiredOutsideLock(retired.storage(),
                                    retired.resource());
                        }
                        publishMarkersAndListeners();
                    }
                }
                if (accepted) {
                    Throwable legacyCleanupFailure =
                            publication.deleteLegacy();
                    if (legacyCleanupFailure != null) {
                        Log.log(Log.LOG_DEBUG,
                                Messages.PgDbParser_clean_parser_error,
                                legacyCleanupFailure);
                    }
                } else {
                    publication.reject(liveRejectionReason(
                            monitor != null && monitor.isCanceled()));
                }
            }
            if (monitor != null && monitor.isCanceled()) {
                throw new OperationCanceledException();
            }
        }

        /**
         * Asks whether this build may still publish.
         *
         * <p>A build is bound to the configuration it started under, and this
         * is where that binding is finally checked against the world: an index
         * built for a configuration nobody is asking for any more is whole,
         * self-consistent and dead on arrival, because every later check of it
         * will find an identity it does not expect and pay for a rebuild.
         * Refusing here costs the build that was already made; publishing
         * costs that one and the next one.</p>
         *
         * <p>A build with no scope - the bypass, which stamps no identity -
         * has nothing to compare and is never refused.</p>
         *
         * @return why this build must not publish, or empty when it may
         */
        private Optional<ConfigurationGuard> configurationRefusal() {
            if (scope == null) {
                return Optional.empty();
            }
            return projectIndexConfigurationRefusal(scope.project(),
                    scope.configuration());
        }

        /**
         * Asks for the build the refused one should have been.
         *
         * <p>Only a configuration that moved gets one, and it gets exactly the
         * one a stale build has always got: the project is known to want an
         * index under a configuration no build has produced yet. A
         * configuration that could not be read gets none - the next build
         * would read it with the same result and ask for another, and the
         * counter that makes the refusal fail-closed guarantees it would never
         * converge.</p>
         *
         * @param reason why this build did not publish
         */
        private void afterConfigurationRefusal(ConfigurationGuard reason) {
            if (reason == ConfigurationGuard.MOVED && scope != null) {
                projectIndexRebuild.accept(scope.project());
            }
        }

        private void publishIncremental(BooleanSupplier cancelled,
                IProgressMonitor monitor) throws IOException {
            IncrementalPublication prepared = incremental;
            if (prepared.publicationLease() == null) {
                publishMemoryIncremental(prepared, cancelled, monitor);
                return;
            }
            ProjectIndexStore store =
                    projectIndexStoreFactory.open(
                            prepared.request().storeDirectory());
            PackedProjectReferenceIndex candidate = null;
            RetiredIndex retired = null;
            ProjectIndexRevision appendedRevision = null;
            PersistenceReason postAppendFailureReason = null;
            PersistenceReason interruptionReason =
                    PersistenceReason.NONE;
            boolean accepted = false;
            try {
                Optional<ConfigurationGuard> refusal =
                        configurationRefusal();
                if (refusal.isPresent()) {
                    telemetry.configurationGuard(refusal.orElseThrow());
                    afterConfigurationRefusal(refusal.orElseThrow());
                    return;
                }
                requireTrustedIncrementalCurrent(
                        prepared, cancelled);
                if (!prepared.trustedContinuity()
                        && !validateIncremental(prepared, cancelled,
                                monitor)
                        || !isIncrementalLive(prepared)) {
                    telemetry.persistence(PersistenceStatus.FAILED,
                            PersistenceReason.STALE_INPUT);
                    invalidateAndSchedule(prepared,
                            prepared.revision());
                    return;
                }
                ProjectIndexStore.IncrementalAppendResult result;
                try {
                    result = prepared.publicationLease()
                            .appendIncremental(
                                    store, prepared.replacements(),
                                    cancelled, telemetry);
                } catch (ProjectIndexStore
                        .CurrentDurabilityException ex) {
                    appendedRevision = ex.revision();
                    postAppendFailureReason =
                            PersistenceReason.IO;
                    throw ex;
                }
                if (result.status()
                        == ProjectIndexStore.IncrementalAppendStatus.CANCELLED) {
                    interruptionReason =
                            PersistenceReason.CANCELLED;
                    throw new IncrementalPublicationCancelledException(
                            "Project index append cancelled"); //$NON-NLS-1$
                }
                if (result.status()
                        == ProjectIndexStore.IncrementalAppendStatus.REQUIRES_FULL) {
                    // The store refused to fold the journal, so the batch was
                    // never applied and the previous revision is still whole.
                    if (result.compactionFailure() != null) {
                        telemetry.persistence(PersistenceStatus.FAILED,
                                ProjectIndexBuildCoordinator
                                        .persistenceFailureReason(
                                                result.compactionFailure()));
                        Log.log(Log.LOG_DEBUG,
                                Messages.PgDbParser_serialize_error,
                                result.compactionFailure());
                    }
                    invalidateAndSchedule(prepared,
                            prepared.revision());
                    return;
                }
                appendedRevision = result.revision();
                postAppendFailureReason = PersistenceReason.REOPEN;
                if (cancelled.getAsBoolean()) {
                    throw new IncrementalPublicationCancelledException(
                            "Project index publication cancelled"); //$NON-NLS-1$
                }

                var opened = store.open(
                        prepared.request().identity());
                boolean transferred = false;
                try {
                    if (opened.status()
                            != ru.taximaxim.codekeeper.ui.projectindex
                                    .ProjectIndexOpenResult.Status.HIT) {
                        telemetry.persistence(PersistenceStatus.FAILED,
                                PersistenceReason.REOPEN);
                        invalidateAndSchedule(prepared,
                                appendedRevision);
                        return;
                    }
                    var view = opened.view().orElseThrow();
                    if (!appendedRevision.equals(view.revision())) {
                        telemetry.persistence(PersistenceStatus.FAILED,
                                PersistenceReason.VALIDATION);
                        invalidateAndSchedule(prepared,
                                appendedRevision);
                        return;
                    }
                    candidate = new PackedProjectReferenceIndex(
                            view,
                            prepared.request().projectRoot(),
                            prepared.request().libraryRoot());
                    transferred = true;
                } finally {
                    if (!transferred) {
                        opened.close();
                    }
                }

                postAppendFailureReason = PersistenceReason.VALIDATION;
                if (!validateIncremental(prepared, cancelled,
                        monitor)) {
                    telemetry.persistence(PersistenceStatus.FAILED,
                            PersistenceReason.STALE_INPUT);
                    invalidateAndSchedule(prepared,
                            appendedRevision);
                    return;
                }

                synchronized (publicationEventLock) {
                    synchronized (stateLock) {
                        if (!cancelled.getAsBoolean()
                                && isIncrementalLive(prepared)) {
                            retired = swapStorageLocked(candidate);
                            candidate = null;
                            continuityAccepted =
                                    projectIndexContinuity
                                            .acceptIncremental(
                                    prepared.continuityProof(),
                                    prepared.trustedContinuity(),
                                    configurationEpoch,
                                    referencesStorage);
                            accepted = true;
                            published = true;
                        }
                    }
                    if (accepted) {
                        closeRetiredOutsideLock(retired.storage(),
                                retired.resource());
                        publishMarkersAndListeners();
                    }
                }
                if (!accepted) {
                    telemetry.persistence(PersistenceStatus.FAILED,
                            liveRejectionReason(
                                    monitor != null
                                            && monitor.isCanceled()));
                    invalidateAndSchedule(prepared,
                            appendedRevision);
                    return;
                }
                telemetry.persistence(PersistenceStatus.PUBLISHED,
                        PersistenceReason.NONE);

            } catch (InterruptedException ex) {
                telemetry.persistence(PersistenceStatus.FAILED,
                        incrementalInterruptionReason(
                                interruptionReason,
                                monitor != null && monitor.isCanceled(),
                                isCurrent(configurationEpoch)));
                if (appendedRevision != null) {
                    try {
                        store.invalidateCurrent(appendedRevision);
                    } catch (IOException | RuntimeException suppressed) {
                        ex.addSuppressed(suppressed);
                        Log.log(Log.LOG_DEBUG,
                                Messages.PgDbParser_clean_parser_error,
                                suppressed);
                    }
                }
                boolean synthetic =
                        ex instanceof
                                IncrementalPublicationCancelledException;
                boolean expectedCancellation =
                        synthetic
                        || monitor != null && monitor.isCanceled()
                        || !isCurrent(configurationEpoch);
                if (!expectedCancellation) {
                    Thread.currentThread().interrupt();
                }
                OperationCanceledException cancelledException =
                        new OperationCanceledException();
                cancelledException.initCause(ex);
                throw cancelledException;
            } catch (IOException | RuntimeException ex) {
                if (appendedRevision != null) {
                    telemetry.persistence(PersistenceStatus.FAILED,
                            postAppendFailureReason);
                } else {
                    telemetry.persistence(PersistenceStatus.FAILED,
                            ProjectIndexBuildCoordinator
                                    .persistenceFailureReason(ex));
                }
                if (appendedRevision != null) {
                    try {
                        store.invalidateCurrent(appendedRevision);
                    } catch (IOException | RuntimeException suppressed) {
                        ex.addSuppressed(suppressed);
                    }
                } else if (ex instanceof
                        PackedProjectReferenceIndex
                                .ProjectIndexAccessException) {
                    invalidateIncrementalRevision(
                            prepared.request(),
                            prepared.revision(), ex);
                }
                projectIndexRebuild.accept(prepared.project());
                throw ex;
            } finally {
                completed = true;
                incremental = null;
                if (candidate != null) {
                    try {
                        candidate.close();
                    } catch (RuntimeException ex) {
                        Log.log(ex);
                    }
                }
                closeIncrementalLeases(
                        prepared.validationLease(),
                        prepared.publicationLease());
                closeBootstrapStorage(prepared);
                finishTelemetry();
            }
        }

        private void publishMemoryIncremental(
                IncrementalPublication prepared,
                BooleanSupplier cancelled,
                IProgressMonitor monitor) throws IOException {
            MemoryProjectReferenceIndex candidate = null;
            RetiredIndex retired = null;
            boolean accepted = false;
            try {
                Optional<ConfigurationGuard> refusal =
                        configurationRefusal();
                if (refusal.isPresent()) {
                    telemetry.configurationGuard(refusal.orElseThrow());
                    afterConfigurationRefusal(refusal.orElseThrow());
                    return;
                }
                requireTrustedIncrementalCurrent(
                        prepared, cancelled);
                if (!prepared.trustedContinuity()
                        && !validateIncremental(prepared, cancelled,
                                monitor)
                        || !isIncrementalLive(prepared)) {
                    telemetry.persistence(PersistenceStatus.FAILED,
                            PersistenceReason.STALE_INPUT);
                    invalidateAndSchedule(prepared, null);
                    return;
                }
                if (!(prepared.expectedLiveStorage()
                        instanceof MemoryProjectReferenceIndex memory)) {
                    throw new IllegalStateException(
                            "Memory publication requires a memory baseline"); //$NON-NLS-1$
                }
                candidate = memory.withReplacements(
                        prepared.validationLease(),
                        prepared.replacements());
                if (!validateIncremental(prepared, cancelled, monitor)) {
                    telemetry.persistence(PersistenceStatus.FAILED,
                            PersistenceReason.STALE_INPUT);
                    invalidateAndSchedule(prepared, null);
                    return;
                }

                synchronized (publicationEventLock) {
                    synchronized (stateLock) {
                        if (!cancelled.getAsBoolean()
                                && isIncrementalLive(prepared)) {
                            retired = swapStorageLocked(candidate);
                            candidate = null;
                            continuityAccepted =
                                    projectIndexContinuity
                                            .acceptIncremental(
                                    prepared.continuityProof(),
                                    prepared.trustedContinuity(),
                                    configurationEpoch,
                                    referencesStorage);
                            accepted = true;
                            published = true;
                        }
                    }
                    if (accepted) {
                        closeRetiredOutsideLock(retired.storage(),
                                retired.resource());
                        publishMarkersAndListeners();
                    }
                }
                if (!accepted) {
                    telemetry.persistence(PersistenceStatus.FAILED,
                            liveRejectionReason(
                                    monitor != null
                                            && monitor.isCanceled()));
                    invalidateAndSchedule(prepared, null);
                    return;
                }
                telemetry.persistence(PersistenceStatus.MEMORY_ONLY,
                        memory.persistenceReason());
            } catch (InterruptedException ex) {
                telemetry.persistence(PersistenceStatus.FAILED,
                        incrementalInterruptionReason(
                                PersistenceReason.NONE,
                                monitor != null && monitor.isCanceled(),
                                isCurrent(configurationEpoch)));
                boolean expectedCancellation =
                        monitor != null && monitor.isCanceled()
                        || !isCurrent(configurationEpoch)
                        || ex instanceof
                                IncrementalPublicationCancelledException;
                if (!expectedCancellation) {
                    Thread.currentThread().interrupt();
                }
                OperationCanceledException cancelledException =
                        new OperationCanceledException();
                cancelledException.initCause(ex);
                throw cancelledException;
            } catch (IOException | RuntimeException ex) {
                telemetry.persistence(PersistenceStatus.FAILED,
                        PersistenceReason.VALIDATION);
                projectIndexRebuild.accept(prepared.project());
                throw ex;
            } finally {
                completed = true;
                incremental = null;
                if (candidate != null) {
                    try {
                        candidate.close();
                    } catch (RuntimeException ex) {
                        Log.log(ex);
                    }
                }
                prepared.validationLease().close();
                closeBootstrapStorage(prepared);
                finishTelemetry();
            }
        }

        private void invalidateAndSchedule(
                IncrementalPublication prepared,
                ProjectIndexRevision revision) {
            if (revision != null) {
                try {
                    new ProjectIndexStore(
                            prepared.request().storeDirectory())
                                    .invalidateCurrent(revision);
                } catch (IOException | RuntimeException ex) {
                    Log.log(Log.LOG_DEBUG,
                            Messages.PgDbParser_clean_parser_error, ex);
                }
            }
            projectIndexRebuild.accept(prepared.project());
        }

        private void requireTrustedIncrementalCurrent(
                IncrementalPublication prepared,
                BooleanSupplier cancelled)
                throws IncrementalPublicationCancelledException {
            if (prepared.trustedContinuity()
                    && cancelled.getAsBoolean()) {
                throw new IncrementalPublicationCancelledException(
                        "Project index publication cancelled"); //$NON-NLS-1$
            }
        }

        private boolean validateIncremental(
                IncrementalPublication prepared,
                BooleanSupplier cancelled,
                IProgressMonitor monitor)
                throws IOException, InterruptedException {
            if (cancelled.getAsBoolean()) {
                throw new IncrementalPublicationCancelledException(
                        "Project index validation cancelled"); //$NON-NLS-1$
            }
            ProjectIndexIdentity identity =
                    createProjectIndexIdentity(
                            prepared.project(),
                            prepared.request().projectRoot(), null);
            if (!prepared.request().identity().equals(identity)) {
                return false;
            }
            for (ProjectIndexDelta.Change change :
                    prepared.replacements().changes()) {
                if (!change.stamp().equals(
                        singleFileStamp(prepared.project(),
                                prepared.request(),
                                change.path(), cancelled,
                                telemetry))) {
                    return false;
                }
            }
            if (prepared.trustedContinuity()) {
                return projectIndexContinuity.permitsIncremental(
                        prepared.continuityProof(),
                        prepared.relativePaths(),
                        configurationEpoch,
                        prepared.request().identity(),
                        prepared.expectedLiveStorage(),
                        prepared.validationLease(),
                        prepared.revision());
            }
            return validateIncrementalInputs(prepared.scope(),
                            prepared.validationLease(),
                            prepared.replacements(),
                            prepared.addedPaths(), monitor,
                            cancelled, telemetry);
        }

        private boolean isIncrementalLive(
                IncrementalPublication prepared) {
            synchronized (stateLock) {
                return isCurrent(configurationEpoch)
                        && referencesStorage
                                == prepared.expectedLiveStorage()
                        && (prepared.revision() == null
                                || prepared.revision().equals(
                                        prepared.publicationLease()
                                                .revision()));
            }
        }

        private void closeBootstrapStorage(
                IncrementalPublication prepared) {
            if (prepared.bootstrapStorage() == null) {
                return;
            }
            try {
                prepared.bootstrapStorage().close();
            } catch (RuntimeException ex) {
                Log.log(ex);
            }
        }

        /**
         * Draws the error markers of a batch the index would not take, and
         * nothing else.
         *
         * <p>Deliberately silent towards the listeners: the model they read
         * did not move, and telling them it did would make every editor
         * re-read an answer that is byte for byte the one it already has.
         * Neither {@code published} nor {@code continuityAccepted} is raised,
         * because neither happened.</p>
         */
        private void publishMarkers(BooleanSupplier cancelled) {
            try {
                synchronized (publicationEventLock) {
                    if (!cancelled.getAsBoolean()) {
                        try {
                            markerPublication.run();
                        } catch (RuntimeException ex) {
                            Log.log(ex);
                        }
                    }
                    completed = true;
                }
            } finally {
                finishTelemetry();
            }
        }

        private void publishMemory(BooleanSupplier cancelled,
                IProgressMonitor monitor) {
            try {
                RetiredIndex retired = null;
                boolean accepted = false;
                synchronized (publicationEventLock) {
                    synchronized (stateLock) {
                        if (cancelled.getAsBoolean()) {
                            completed = true;
                        } else {
                            retired = swapStorageLocked(storage);
                            storage = null;
                            continuityAccepted =
                                    projectIndexContinuity
                                            .acceptReconciliation(
                                    continuityProof,
                                    configurationEpoch,
                                    referencesStorage);
                            completed = true;
                            accepted = true;
                            published = true;
                        }
                    }
                    if (accepted) {
                        closeRetiredOutsideLock(retired.storage(),
                                retired.resource());
                        publishMarkersAndListeners();
                    }
                }
                if (!accepted) {
                    closeOwnedStorage();
                }
            } finally {
                finishTelemetry();
            }
            if (monitor != null && monitor.isCanceled()) {
                throw new OperationCanceledException();
            }
        }

        public void publish() {
            try {
                commit("", null); //$NON-NLS-1$
            } catch (IOException ex) {
                Log.log(ex);
                discard();
            }
        }

        public void discard() {
            if (completed) {
                return;
            }
            completed = true;
            projectIndexContinuity.invalidate(continuityProof);
            try {
                if (persistent != null) {
                    persistent.close();
                } else if (incremental != null) {
                    incremental.validationLease().close();
                    if (incremental.publicationLease() != null) {
                        incremental.publicationLease().close();
                    }
                    closeBootstrapStorage(incremental);
                    incremental = null;
                } else {
                    closeOwnedStorage();
                }
            } finally {
                finishTelemetry();
            }
        }

        private void finishTelemetry() {
            if (telemetry != null) {
                telemetry.close();
            }
        }

        private void closeOwnedStorage() {
            ProjectReferenceIndex candidate = storage;
            storage = null;
            if (!ownsStorage || candidate == null) {
                return;
            }
            try {
                candidate.close();
            } catch (RuntimeException ex) {
                Log.log(ex);
            }
        }

        private void publishMarkersAndListeners() {
            try {
                markerPublication.run();
            } catch (RuntimeException ex) {
                Log.log(ex);
            }
            notifyListeners();
            if (publishedProjectIndex != null) {
                notifyProjectIndexPublished(publishedProjectIndex);
            }
        }
    }

    private static final long CANCELLATION_POLL_MILLIS = 20;
    private static final long CANCELLATION_JOIN_MILLIS = 1_000;
    private static final long CONFIGURATION_REBUILD_DELAY_MILLIS = 200;
    /**
     * Largest single file an incremental capture may read. One file is held
     * in memory at a time, so this is the memory bound of the whole batch.
     */
    static final long MAX_INCREMENTAL_CAPTURE_BYTES = 16L << 20;

    /**
     * Largest amount of source text one incremental batch may read in total.
     * Files are read and released one by one, so this bounds work rather than
     * memory: a batch that has to re-read as much text as the whole packed
     * index is no longer cheaper than a full rebuild, and falls back to one.
     */
    static final long MAX_INCREMENTAL_BATCH_CAPTURE_BYTES = 64L << 20;

    private static final ConcurrentMap<IProject, PgDbParser> PROJ_PARSERS = new ConcurrentHashMap<>();
    private static final List<Consumer<IProject>>
            INDEX_PUBLICATION_LISTENERS = new CopyOnWriteArrayList<>();
    /**
     * Makes every fingerprint of a configuration that could not be read
     * distinct, so that two unreadable reads in a row cannot look equal to each
     * other and let an invalidation through the guard unnoticed.
     */
    private static final AtomicLong UNREADABLE_CONFIGURATIONS = new AtomicLong();
    private static final ProjectIndexConfigurationInvalidator CONFIGURATION_INVALIDATOR =
            new ProjectIndexConfigurationInvalidator(
                    PgDbParser::scheduleProjectIndexBuild);

    private final Object loadLock = new Object();
    private final Object stateLock = new Object();
    private final Object publicationEventLock = new Object();
    private final AtomicReference<ILoader> activeLoader = new AtomicReference<>();
    private final ProjectReferenceIndex initialReferencesStorage =
            new ProjectReferencesStorage();
    private volatile ProjectReferenceIndex referencesStorage =
            initialReferencesStorage;
    private final ProjectIndexContinuity projectIndexContinuity =
            new ProjectIndexContinuity();
    private AutoCloseable projectIndexResource;
    private volatile long configurationEpoch;
    private long contentEpoch;
    private long publicationSequence;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Consumer<ProjectIndexRevision> packedIndexRecovery;
    private final Consumer<IProject> projectIndexRebuild;
    private final IProject observedProject;
    private final ProjectIndexStoreFactory projectIndexStoreFactory;
    private boolean packedIndexRecoveryPending;

    public PgDbParser() {
        this(ignored -> { }, PgDbParser::startBuildJob,
                ProjectIndexStoreFactory.PLATFORM);
    }

    PgDbParser(Runnable packedIndexRecovery) {
        this(ignored -> packedIndexRecovery.run(),
                PgDbParser::startBuildJob,
                ProjectIndexStoreFactory.PLATFORM);
    }

    PgDbParser(Consumer<ProjectIndexRevision> packedIndexRecovery,
            Consumer<IProject> projectIndexRebuild) {
        this(packedIndexRecovery, projectIndexRebuild,
                ProjectIndexStoreFactory.PLATFORM);
    }

    PgDbParser(Consumer<ProjectIndexRevision> packedIndexRecovery,
            Consumer<IProject> projectIndexRebuild,
            ProjectIndexStoreFactory projectIndexStoreFactory) {
        this.packedIndexRecovery = Objects.requireNonNull(
                packedIndexRecovery, "packedIndexRecovery"); //$NON-NLS-1$
        this.projectIndexRebuild = Objects.requireNonNull(
                projectIndexRebuild, "projectIndexRebuild"); //$NON-NLS-1$
        observedProject = null;
        this.projectIndexStoreFactory = Objects.requireNonNull(
                projectIndexStoreFactory,
                "projectIndexStoreFactory"); //$NON-NLS-1$
    }

    private PgDbParser(IProject project) {
        observedProject = Objects.requireNonNull(
                project, "project"); //$NON-NLS-1$
        this.projectIndexRebuild = PgDbParser::startBuildJob;
        this.projectIndexStoreFactory =
                ProjectIndexStoreFactory.PLATFORM;
        this.packedIndexRecovery =
                revision -> recoverPackedProjectIndex(
                        project, this, revision);
    }

    private boolean isCurrent(long expectedEpoch) {
        return configurationEpoch == expectedEpoch;
    }

    private long currentConfigurationEpoch() {
        synchronized (stateLock) {
            return configurationEpoch;
        }
    }

    public void addListener(Listener e) {
        listeners.add(e);
    }

    public void removeListener(Listener e) {
        listeners.remove(e);
    }

    /**
     * Registers interest in a build having published a new index of a whole
     * project.
     *
     * <p>Deliberately not {@link #addListener}, and the difference is the
     * point. That one fires whenever the live model is republished, which
     * includes every saved file; anything that caches an answer <em>per
     * project</em> and is rebuilt by walking that project would be thrown away
     * on every keystroke-sized change. This one fires only when the index the
     * answers come from was replaced as a whole - a cold build, a warm
     * restore, a rebuild - which is when an answer can move for a file nothing
     * happened to.</p>
     *
     * <p>Listeners are told from the publishing thread, inside no lock of this
     * parser but with the publication event lock held, so what they do must be
     * short. One that throws is logged and the rest are still told.</p>
     */
    public static void addProjectIndexPublicationListener(
            Consumer<IProject> listener) {
        INDEX_PUBLICATION_LISTENERS.add(Objects.requireNonNull(
                listener, "listener")); //$NON-NLS-1$
    }

    public static void removeProjectIndexPublicationListener(
            Consumer<IProject> listener) {
        INDEX_PUBLICATION_LISTENERS.remove(listener);
    }

    private static void notifyProjectIndexPublished(IProject project) {
        for (Consumer<IProject> listener : INDEX_PUBLICATION_LISTENERS) {
            try {
                listener.accept(project);
            } catch (RuntimeException ex) {
                Log.log(ex);
            }
        }
    }

    private static Path getPathToObject(String name) {
        return getPathToFolder().resolve(name + ".ser"); //$NON-NLS-1$
    }

    private static Path getPathToFolder() {
        return getStateRoot().resolve("projects"); //$NON-NLS-1$
    }

    private static Path getStateRoot() {
        return Paths.get(Platform.getStateLocation(
                Activator.getContext().getBundle()).toString());
    }

    ProjectReferencesStorage loadReferences(ILoader createdLoader)
            throws IOException, InterruptedException {
        return loadReferences(createdLoader, null);
    }

    ProjectReferencesStorage loadReferences(ILoader createdLoader, IProgressMonitor monitor)
            throws IOException, InterruptedException {
        return loadReferenceData(createdLoader, monitor).storage();
    }

    private LoadedReferences loadReferenceData(ILoader createdLoader, IProgressMonitor monitor)
            throws IOException, InterruptedException {
        return loadReferenceData(
                createdLoader, monitor, true, false);
    }

    private LoadedReferences loadReferenceData(ILoader createdLoader, IProgressMonitor monitor,
            boolean analyze)
            throws IOException, InterruptedException {
        return loadReferenceData(
                createdLoader, monitor, analyze, false);
    }

    LoadedReferences loadReferenceData(
            ILoader createdLoader,
            IProgressMonitor monitor, boolean analyze,
            boolean captureInputFingerprints)
            throws IOException, InterruptedException {
        return loadReferenceData(createdLoader, monitor, analyze,
                captureInputFingerprints, null);
    }

    private LoadedReferences loadReferenceData(
            ILoader createdLoader,
            IProgressMonitor monitor, boolean analyze,
            boolean captureInputFingerprints,
            ProjectIndexTelemetry.Run telemetry)
            throws IOException, InterruptedException {
        synchronized (loadLock) {
            IProjectInputFingerprintCapture capture =
                    captureInputFingerprints
                            && createdLoader
                                    instanceof
                                    IProjectInputFingerprintCapture
                                            supported
                                            ? supported : null;
            try (ILoader loader = createdLoader) {
                if (capture != null) {
                    try {
                        capture.enableInputFingerprintCapture();
                    } catch (RuntimeException ex) {
                        capture = null;
                    }
                }
                if (!activeLoader.compareAndSet(null, loader)) {
                    throw new IllegalStateException("Concurrent project parser load"); //$NON-NLS-1$
                }
                Thread cancellationWatcher = null;
                try {
                    cancellationWatcher = startCancellationWatcher(loader, monitor);
                    IDatabase db;
                    if (telemetry == null) {
                        db = analyze ? loader.loadAndAnalyze() : loader.load();
                    } else {
                        Phase phase = analyze
                                ? Phase.LOAD_ANALYZE : Phase.PARSE;
                        try (var ignored = telemetry.phase(phase)) {
                            db = analyze
                                    ? loader.loadAndAnalyze() : loader.load();
                        }
                    }
                    var loaded = new ProjectReferencesStorage();
                    loaded.putReferences(MetaUtils.getObjDefinitions(db), db.getObjReferences());
                    List<ProjectInputFingerprint> fingerprints = null;
                    if (capture != null) {
                        try {
                            fingerprints =
                                    capture.getCapturedInputFingerprints();
                        } catch (RuntimeException ex) {
                            // Fingerprint capture is optional. The caller will
                            // safely fall back to a separate content pass.
                        }
                    }
                    return new LoadedReferences(
                            loaded,
                            new ArrayList<>(loader.getErrors()),
                            fingerprints);
                } finally {
                    activeLoader.compareAndSet(loader, null);
                    stopCancellationWatcher(cancellationWatcher);
                }
            }
        }
    }

    /**
     * Parses and analyses one file of an incremental batch.
     *
     * @param parsedDefinitionGate decides from the parsed definitions whether
     *                             the analysis is still worth running
     * @return the analysed references, or null when the gate turned the file
     *         down before its analysis
     */
    private LoadedReferences loadIncrementalReferenceData(
            IDumpLoader createdLoader,
            IncrementalProjectReferenceIndex.AnalysisLease analysisLease,
            IndexPathRef changedPath, IProgressMonitor monitor,
            ProjectIndexTelemetry.Run telemetry,
            ParsedDefinitionGate parsedDefinitionGate)
            throws IOException, InterruptedException {
        synchronized (loadLock) {
            try (IDumpLoader loader = createdLoader) {
                if (!activeLoader.compareAndSet(null, loader)) {
                    throw new IllegalStateException(
                            "Concurrent project parser load"); //$NON-NLS-1$
                }
                Thread cancellationWatcher = null;
                try {
                    cancellationWatcher =
                            startCancellationWatcher(loader, monitor);
                    IDatabase parsed;
                    try (var ignored = telemetry.phase(Phase.PARSE)) {
                        parsed = loader.load();
                    }
                    telemetry.addParsedPaths(1);
                    // Definitions are complete once the file is parsed: the
                    // analysis reuses the very same statement tree and only
                    // adds dependencies and references to it. A file that
                    // changed a definition's shape rebuilds the whole index,
                    // so its analysis would be thrown away.
                    if (!parsedDefinitionGate.acceptsParsedDefinitions(
                            MetaUtils.getObjDefinitions(parsed))) {
                        return null;
                    }
                    IMetaContainer localAndSystem =
                            MetaUtils.createTreeFromDb(parsed,
                                    loader.getSettings().getVersion(),
                                    loader.getSettings().getMonitor());
                    IDatabase analyzed;
                    try (var metadata = IncrementalMetaContainer.borrowed(
                            () -> localAndSystem, analysisLease,
                            changedPath)) {
                        try (var ignored =
                                telemetry.phase(Phase.ANALYZE)) {
                            analyzed = loader.loadAndAnalyze(metadata);
                        }
                        // What the analysis spent asking the index, as opposed
                        // to reading the file: one number cannot separate them.
                        PerformanceTelemetry.publish(
                                "pgCodeKeeper incremental analysis: " //$NON-NLS-1$
                                        + metadata.packedLookupSummary()
                                        + " routine_names_resolved=" //$NON-NLS-1$
                                        + resolvedRoutineNames(analyzed));
                    }
                    telemetry.addAnalyzedPaths(1);
                    var loaded = new ProjectReferencesStorage();
                    loaded.putReferences(
                            MetaUtils.getObjDefinitions(analyzed),
                            analyzed.getObjReferences());
                    return new LoadedReferences(loaded,
                            new ArrayList<>(loader.getErrors()));
                } finally {
                    activeLoader.compareAndSet(loader, null);
                    stopCancellationWatcher(cancellationWatcher);
                }
            }
        }
    }

    /**
     * How many distinct routine names an analysis bound, counted off what it
     * left behind rather than off what it asked for.
     *
     * <p>The container's own {@code routine_name} counter cannot answer this.
     * It reads the memo map that {@code findFunction} fills, and the only
     * callers of {@code findFunction} in the analyser are the aggregate and
     * operator launchers: an ordinary call is resolved by lifting a whole
     * schema with {@code availableFunctions} and filtering it in memory, so
     * the name being looked for never reaches the container. This counts the
     * other end - the references the analysis wrote out - and so says how wide
     * a schema-wide lift was against the demand it actually served.
     *
     * <p>A pair is (schema, bare name): one overloaded routine called under
     * three argument lists is three references, three signatures and one name,
     * and it is the name a narrower lift would have to cover. The signature is
     * cut at its first {@code (}; a routine recorded without one counts under
     * its whole name.
     *
     * <p><b>This is a lower bound on the demand, not the demand.</b> Only
     * bound references reach {@code getObjReferences()}: a call the analyser
     * failed to resolve - an unknown name, an ambiguous overload, a call whose
     * argument types did not fit any candidate - leaves nothing behind and is
     * invisible here, even though the index was lifted for it just the same.
     * The number therefore supports one question only: whether a schema-wide
     * lift is orders of magnitude wider than what the file consumed. It does
     * not support any statement about how many calls the file makes, about
     * analysis correctness, or about what a narrower lift would have to hold.
     *
     * <p>Definitions are excluded. {@code getObjReferences()} carries the
     * file's own {@code CREATE} locations next to its references, and a
     * routine the file defines is not demand the index answered. Only
     * {@code LocationType.REFERENCE} is counted; system schemas are dropped
     * because the analyser refuses to record a dependency on them at all, so
     * whatever slips in past that comes from a literal
     * {@code pg_catalog.f(...)} in the source and never touched the index.
     *
     * @param analyzed the analysed database, straight out of the analysis
     * @return distinct bound (schema, bare routine name) pairs
     */
    static int resolvedRoutineNames(IDatabase analyzed) {
        Set<String> names = new HashSet<>();
        for (Set<ObjectLocation> locations
                : analyzed.getObjReferences().values()) {
            for (ObjectLocation location : locations) {
                if (location.getLocationType()
                        != ObjectLocation.LocationType.REFERENCE) {
                    continue;
                }
                DbObjType type = location.getType();
                if (type == null || !type.in(DbObjType.FUNCTION,
                        DbObjType.PROCEDURE, DbObjType.AGGREGATE)) {
                    continue;
                }
                String schema = location.getSchema();
                if (PgDiffUtils.isSystemSchema(schema)) {
                    continue;
                }
                names.add(schema + '.' + bareRoutineName(location.getName()));
            }
        }
        return names.size();
    }

    private static String bareRoutineName(String signature) {
        int arguments = signature.indexOf('(');
        return arguments < 0 ? signature : signature.substring(0, arguments);
    }

    private List<Path> listInputFiles(IProjectLoader createdLoader,
            IProgressMonitor monitor) throws IOException, InterruptedException {
        synchronized (loadLock) {
            try (IProjectLoader loader = createdLoader) {
                if (!activeLoader.compareAndSet(null, loader)) {
                    throw new IllegalStateException(
                            "Concurrent project parser load"); //$NON-NLS-1$
                }
                Thread cancellationWatcher = null;
                try {
                    cancellationWatcher = startCancellationWatcher(loader,
                            monitor);
                    return loader.listInputFiles();
                } finally {
                    activeLoader.compareAndSet(loader, null);
                    stopCancellationWatcher(cancellationWatcher);
                }
            }
        }
    }

    private Thread startCancellationWatcher(ILoader loader, IProgressMonitor monitor) {
        if (monitor == null) {
            return null;
        }
        return Thread.ofVirtual().name("pgCodeKeeper parser cancellation").start(() -> { //$NON-NLS-1$
            try {
                while (activeLoader.get() == loader && !monitor.isCanceled()) {
                    Thread.sleep(CANCELLATION_POLL_MILLIS);
                }
                if (monitor.isCanceled() && activeLoader.get() == loader) {
                    cancelLoader(loader);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private static void stopCancellationWatcher(Thread watcher) throws InterruptedException {
        if (watcher == null) {
            return;
        }
        watcher.interrupt();
        watcher.join(CANCELLATION_JOIN_MILLIS);
    }

    public void cancelCurrentLoad() {
        ILoader loader = activeLoader.get();
        if (loader != null) {
            cancelLoader(loader);
        }
    }

    private static void cancelLoader(ILoader loader) {
        try {
            loader.cancel();
        } catch (IOException | RuntimeException ex) {
            Log.log(ex);
        }
    }

    public void getObjFromProjFile(IFile file, IProgressMonitor monitor)
            throws InterruptedException, IOException {
        prepareObjFromProjFile(file, monitor).publish();
    }

    public PreparedUpdate prepareObjFromProjFile(IFile file, IProgressMonitor monitor)
            throws InterruptedException, IOException {
        IndexSnapshot snapshot = indexSnapshot();
        if (!(snapshot.storage() instanceof ProjectReferencesStorage)) {
            return prepareProjectIndex(file.getProject(), monitor).update();
        }
        ISettings settings = UISettings.forProjectIndex(file.getProject());
        settings.setMonitor(new UIMonitor(monitor));
        var provider = ProjectUtils.getDatabaseType(file.getProject()).getDatabaseProvider();
        IDumpLoader loader = provider.getDumpLoader(file.getLocation().toFile().toPath(),
                settings);
        loader.setMode(ParserListenerMode.REF);
        LoadedReferences loaded = loadReferenceData(loader, monitor);
        ProjectReferencesStorage updated = snapshot.storage().mutableCopy();
        updated.remove(file.getLocation().toOSString());
        updated.putReferences(loaded.storage().getObjDefinitions(), loaded.storage().getObjReferences());
        return publication(updated, loaded.errors(), List.of(file),
                snapshot.configurationEpoch());
    }

    public void getObjFromProjFiles(Collection<IFile> files, IProgressMonitor monitor, DatabaseType dbType)
            throws InterruptedException, IOException {
        prepareObjFromProjFiles(files, Collections.emptyList(), monitor, dbType).publish();
    }

    public PreparedUpdate prepareObjFromProjFiles(Collection<IFile> files,
            Collection<? extends IResource> removed, IProgressMonitor monitor, DatabaseType dbType)
            throws InterruptedException, IOException {
        IndexSnapshot snapshot = indexSnapshot();
        if (files.isEmpty()) {
            if (removed.isEmpty()) {
                return new PreparedUpdate(snapshot.storage(), () -> { },
                        snapshot.configurationEpoch(), false);
            }
            if (!(snapshot.storage() instanceof ProjectReferencesStorage)) {
                return prepareProjectIndex(
                        removed.iterator().next().getProject(), monitor)
                                .update();
            }
            ProjectReferencesStorage updated = snapshot.storage().mutableCopy();
            removed.forEach(res -> updated.remove(res.getLocation().toOSString()));
            return publication(updated, Collections.emptyList(),
                    Collections.emptyList(), snapshot.configurationEpoch());
        }
        IProject proj = files.iterator().next().getProject();
        if (!(snapshot.storage() instanceof ProjectReferencesStorage)) {
            return prepareProjectIndex(proj, monitor).update();
        }
        ISettings settings = UISettings.forProjectIndex(proj);
        settings.setMonitor(new UIMonitor(monitor));
        var loader = dbType.getDatabaseProvider().getProjectLoader(ProjectUtils.getPath(proj), settings,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                LibraryUtils.META_PATH);
        LoadedReferences loaded = loadReferenceData(loader, monitor);
        ProjectReferencesStorage updated = snapshot.storage().mutableCopy();
        files.forEach(file -> updated.remove(file.getLocation().toOSString()));
        removed.forEach(res -> updated.remove(res.getLocation().toOSString()));
        updated.putReferences(loaded.storage().getObjDefinitions(), loaded.storage().getObjReferences());
        return publication(updated, loaded.errors(), files,
                snapshot.configurationEpoch());
    }

    public void getFullDBFromPgDbProject(IProject proj, IProgressMonitor monitor)
            throws InterruptedException, IOException {
        prepareFullDBFromPgDbProject(proj, monitor).publish();
    }

    public PreparedIncrementalProjectIndex
            prepareIncrementalProjectIndex(IProject project,
                    String relativePath, IProgressMonitor monitor)
                    throws InterruptedException, IOException {
        return prepareIncrementalProjectIndex(project,
                List.of(relativePath),
                monitor, ProjectIndexTelemetry.INSTANCE.start(
                        Mode.INCREMENTAL), null);
    }

    public PreparedIncrementalProjectIndex
            prepareIncrementalProjectIndex(IProject project,
                    String relativePath, IProgressMonitor monitor,
                    BuildContinuityProof continuityProof)
                    throws InterruptedException, IOException {
        return prepareIncrementalProjectIndex(project,
                List.of(relativePath),
                monitor, ProjectIndexTelemetry.INSTANCE.start(
                        Mode.INCREMENTAL), continuityProof);
    }

    public PreparedIncrementalProjectIndex
            prepareIncrementalProjectIndex(IProject project,
                    Collection<String> relativePaths,
                    IProgressMonitor monitor)
                    throws InterruptedException, IOException {
        return prepareIncrementalProjectIndex(project,
                relativePaths, monitor,
                ProjectIndexTelemetry.INSTANCE.start(
                        Mode.INCREMENTAL), null);
    }

    public PreparedIncrementalProjectIndex
            prepareIncrementalProjectIndex(IProject project,
                    Collection<String> relativePaths,
                    IProgressMonitor monitor,
                    BuildContinuityProof continuityProof)
                    throws InterruptedException, IOException {
        return prepareIncrementalProjectIndex(project, relativePaths,
                monitor, continuityProof, null);
    }

    /**
     * Prepares an incremental project index and reports its phases to the
     * build. A run that has to fall back to a full rebuild keeps reporting
     * through the same observer.
     *
     * @param project         project to index
     * @param relativePaths   changed project files
     * @param monitor         monitor of the running build
     * @param continuityProof proof the build is still the current one
     * @param progress        observable progress of the build, may be null
     * @return the prepared index
     * @throws InterruptedException if the build was cancelled
     * @throws IOException          if the project could not be read
     */
    public PreparedIncrementalProjectIndex
            prepareIncrementalProjectIndex(IProject project,
                    Collection<String> relativePaths,
                    IProgressMonitor monitor,
                    BuildContinuityProof continuityProof,
                    ProjectBuildProgressSink progress)
                    throws InterruptedException, IOException {
        return prepareIncrementalProjectIndex(project, relativePaths,
                monitor, continuityProof, progress, null);
    }

    /**
     * Prepares an incremental project index by a configuration that was
     * already read.
     *
     * <p>This is the form a builder uses. The batch handed in was selected by
     * {@code configuration} - a change under a schema that configuration
     * excludes never reached this list - so the enumeration and the identity
     * of the index have to answer to it too. Reading the node again here would
     * put the three at odds for as long as the node is moving, and the
     * platform moves it for a whole second every time it applies a
     * preferences file.</p>
     *
     * @param configuration the configuration this build was classified by, or
     *                      null to read one here
     */
    public PreparedIncrementalProjectIndex
            prepareIncrementalProjectIndex(IProject project,
                    Collection<String> relativePaths,
                    IProgressMonitor monitor,
                    BuildContinuityProof continuityProof,
                    ProjectBuildProgressSink progress,
                    ProjectIndexBuildConfiguration configuration)
                    throws InterruptedException, IOException {
        return prepareIncrementalProjectIndex(project,
                relativePaths, monitor,
                observed(ProjectIndexTelemetry.INSTANCE.start(
                        Mode.INCREMENTAL), progress), continuityProof,
                configuration);
    }

    PreparedIncrementalProjectIndex
            prepareIncrementalProjectIndex(IProject project,
                    String relativePath, IProgressMonitor monitor,
                    ProjectIndexTelemetry.Run telemetry)
                    throws InterruptedException, IOException {
        return prepareIncrementalProjectIndex(project,
                List.of(relativePath),
                monitor, telemetry, null);
    }

    PreparedIncrementalProjectIndex
            prepareIncrementalProjectIndex(IProject project,
                    String relativePath, IProgressMonitor monitor,
                    ProjectIndexTelemetry.Run telemetry,
                    BuildContinuityProof continuityProof)
                    throws InterruptedException, IOException {
        return prepareIncrementalProjectIndex(project,
                List.of(relativePath), monitor, telemetry,
                continuityProof);
    }

    PreparedIncrementalProjectIndex
            prepareIncrementalProjectIndex(IProject project,
                    Collection<String> relativePaths,
                    IProgressMonitor monitor,
                    ProjectIndexTelemetry.Run telemetry,
                    BuildContinuityProof continuityProof)
                    throws InterruptedException, IOException {
        return prepareIncrementalProjectIndex(project, relativePaths, monitor,
                telemetry, continuityProof, null);
    }

    PreparedIncrementalProjectIndex
            prepareIncrementalProjectIndex(IProject project,
                    Collection<String> relativePaths,
                    IProgressMonitor monitor,
                    ProjectIndexTelemetry.Run telemetry,
                    BuildContinuityProof continuityProof,
                    ProjectIndexBuildConfiguration build)
                    throws InterruptedException, IOException {
        Objects.requireNonNull(telemetry, "telemetry"); //$NON-NLS-1$
        IncrementalProjectReferenceIndex.AnalysisLease validationLease = null;
        PackedProjectReferenceIndex.PublicationLease publicationLease = null;
        PackedProjectReferenceIndex bootstrapStorage = null;
        BooleanSupplier incrementalCancelled = null;
        boolean transferred = false;
        try {
            List<IndexPathRef> paths;
            try {
                paths = canonicalIncrementalPaths(relativePaths);
            } catch (IllegalArgumentException ex) {
                transferred = true;
                return incrementalFullFallback(project, build, monitor,
                        telemetry,
                        RepairRefusal.BATCH_PATHS_REJECTED,
                        validationLease,
                        publicationLease, bootstrapStorage,
                        continuityProof);
            }
            List<String> canonicalRelativePaths = paths.stream()
                    .map(IndexPathRef::relativePath)
                    .toList();
            long epoch;
            ProjectReferenceIndex expectedLiveStorage;
            IncrementalProjectReferenceIndex incrementalIndex = null;
            PackedProjectReferenceIndex packed = null;
            synchronized (stateLock) {
                epoch = configurationEpoch;
                expectedLiveStorage = referencesStorage;
                if (referencesStorage
                        instanceof IncrementalProjectReferenceIndex candidate) {
                    incrementalIndex = candidate;
                    if (candidate
                            instanceof PackedProjectReferenceIndex packedCandidate) {
                        packed = packedCandidate;
                        publicationLease =
                                packedCandidate.acquirePublicationLease();
                        validationLease = publicationLease;
                    } else {
                        validationLease =
                                candidate.acquireAnalysisLease();
                    }
                }
            }

            Path projectRoot = ProjectUtils.getPath(project)
                    .toAbsolutePath().normalize();
            ProjectIndexBuildScope scope = createProjectIndexBuildScope(
                    project, projectRoot, build, monitor);
            ProjectIndexBuildCoordinator.Request request = scope.request();
            telemetry.identity(request.identity());
            ProjectIndexStore store = null;
            if (incrementalIndex == null) {
                store = projectIndexStoreFactory.open(
                        request.storeDirectory());
                if (expectedLiveStorage != initialReferencesStorage) {
                    transferred = true;
                    return incrementalFullFallback(project, build, monitor,
                            telemetry,
                            RepairRefusal.BOOTSTRAP_NOT_INITIAL,
                            validationLease, publicationLease,
                            bootstrapStorage, continuityProof);
                }
                var opened = store.open(request.identity());
                var openStatus = opened.status();
                boolean adopted = false;
                try {
                    if (openStatus
                            == ru.taximaxim.codekeeper.ui.projectindex
                                    .ProjectIndexOpenResult.Status.HIT) {
                        packed = new PackedProjectReferenceIndex(
                                opened.view().orElseThrow(),
                                request.projectRoot(),
                                request.libraryRoot());
                        bootstrapStorage = packed;
                        incrementalIndex = packed;
                        adopted = true;
                    }
                } finally {
                    if (!adopted) {
                        opened.close();
                    }
                }
                if (openStatus
                        == ru.taximaxim.codekeeper.ui.projectindex
                                .ProjectIndexOpenResult.Status.CORRUPT) {
                    cleanProjectIndexStore(request.storeDirectory());
                }
                if (packed == null) {
                    transferred = true;
                    return incrementalFullFallback(project, build, monitor,
                            telemetry,
                            RepairRefusal.STORED_INDEX_UNAVAILABLE,
                            validationLease, publicationLease,
                            bootstrapStorage, continuityProof);
                }
                boolean bootstrapCurrent;
                synchronized (stateLock) {
                    bootstrapCurrent = isCurrent(epoch)
                            && referencesStorage == expectedLiveStorage;
                    if (bootstrapCurrent) {
                        publicationLease =
                                packed.acquirePublicationLease();
                        validationLease = publicationLease;
                    }
                }
                if (!bootstrapCurrent) {
                    transferred = true;
                    return incrementalFullFallback(project, build, monitor,
                            telemetry,
                            RepairRefusal.BOOTSTRAP_SUPERSEDED,
                            validationLease, publicationLease,
                            bootstrapStorage, continuityProof);
                }
            }

            ProjectIndexRevision revision = null;
            Map<IndexPathRef, List<PackedDefinition>>
                    previousDefinitions = new LinkedHashMap<>();
            Set<IndexPathRef> addedPaths = new LinkedHashSet<>();
            try {
                if (publicationLease != null) {
                    revision = publicationLease.revision();
                }
                if (!isIncrementalSnapshotConsistent(
                        request.identity(), validationLease,
                        validationLease, revision)) {
                    transferred = true;
                    return incrementalFullFallback(project, build, monitor,
                            telemetry,
                            RepairRefusal.SNAPSHOT_INCONSISTENT,
                            validationLease,
                            publicationLease, bootstrapStorage,
                            continuityProof);
                }
                for (IndexPathRef path : paths) {
                    IncrementalFileMetadata previous =
                            validationLease.file(path)
                                    .orElse(null);
                    if (previous == null) {
                        // A file the index does not hold. There is no previous
                        // shape to preserve, so this path answers to
                        // permitsAddedFiles once its definitions are known.
                        addedPaths.add(path);
                        continue;
                    }
                    previousDefinitions.put(path,
                            previous.definitions());
                }
                // The classifier does not offer an addition unless the project
                // permits one, and this closes the same door from the inside:
                // every other caller of this method reaches it directly.
                if (!addedPaths.isEmpty()
                        && !scope.configuration().configuration()
                                .incrementalAddedFiles()) {
                    transferred = true;
                    return incrementalFullFallback(project, build, monitor,
                            telemetry,
                            RepairRefusal.BATCH_ADDED_FILES_DISABLED,
                            validationLease,
                            publicationLease, bootstrapStorage,
                            continuityProof);
                }
            } catch (PackedProjectReferenceIndex.ProjectIndexAccessException
                    ex) {
                invalidateIncrementalRevision(request, revision, ex);
                Log.log(Log.LOG_DEBUG,
                        Messages.PgDbParser_deserialize_error, ex);
                transferred = true;
                return incrementalFullFallback(project, build, monitor,
                        telemetry,
                        RepairRefusal.INDEX_READ_FAILURE,
                        validationLease, publicationLease,
                        bootstrapStorage, continuityProof);
            } catch (IllegalArgumentException
                    | UnsupportedOperationException ex) {
                Log.log(Log.LOG_DEBUG,
                        Messages.PgDbParser_deserialize_error, ex);
                transferred = true;
                return incrementalFullFallback(project, build, monitor,
                        telemetry,
                        RepairRefusal.INDEX_METADATA_REJECTED,
                        validationLease, publicationLease,
                        bootstrapStorage, continuityProof);
            }
            if (expectedLiveStorage
                    instanceof MemoryProjectReferenceIndex) {
                telemetry.mode(Mode.MEMORY_INCREMENTAL);
            }
            boolean trustedContinuity = continuityProof != null
                    && projectIndexContinuity.permitsIncremental(
                            continuityProof, canonicalRelativePaths, epoch,
                            request.identity(), expectedLiveStorage,
                            validationLease, revision);
            if (continuityProof != null && !trustedContinuity) {
                transferred = true;
                return incrementalFullFallback(project, build, monitor,
                        telemetry,
                        RepairRefusal.CONTINUITY_UNTRUSTED,
                        validationLease, publicationLease,
                        bootstrapStorage,
                        continuityProof);
            }

            BooleanSupplier baseCancelled = cancelled(monitor, epoch);
            BooleanSupplier cancelled = () -> baseCancelled.getAsBoolean()
                    || continuityProof != null
                            && !continuityProof.isCurrent();
            incrementalCancelled = cancelled;
            List<CurrentFile> preflight;
            try {
                if (!trustedContinuity
                        && !validateIncrementalInputs(scope,
                                validationLease, paths, addedPaths,
                                null, monitor,
                                cancelled, telemetry)) {
                    transferred = true;
                    return incrementalFullFallback(project, build, monitor,
                            telemetry,
                            RepairRefusal.PREFLIGHT_INPUTS_STALE,
                            validationLease,
                            publicationLease, bootstrapStorage,
                            continuityProof);
                }
                preflight = inspectIncrementalBatch(project,
                        request, paths, telemetry);
            } catch (IOException | RuntimeException ex) {
                if (cancelled.getAsBoolean()) {
                    throw operationCancelled(ex);
                }
                if (ex instanceof
                        PackedProjectReferenceIndex.ProjectIndexAccessException) {
                    invalidateIncrementalRevision(request, revision, ex);
                }
                Log.log(Log.LOG_DEBUG,
                        Messages.PgDbParser_deserialize_error, ex);
                transferred = true;
                return incrementalFullFallback(project, build, monitor,
                        telemetry,
                        RepairRefusal.PREFLIGHT_FAILURE,
                        validationLease, publicationLease,
                        bootstrapStorage, continuityProof);
            }
            UISettings settings = UISettings.forProjectIndex(project,
                    scope.configuration());
            settings.setMonitor(new UIMonitor(monitor));
            // The batch is what this build works through, and a batch of a few
            // hundred files runs long enough that a bar without a count reads
            // as a hung build.
            telemetry.observeChangedFiles(paths.size());
            var batchLease = validationLease;
            var capturedBytes = new long[1];
            IncrementalBatch batch;
            try {
                batch = collectIncrementalReplacements(paths,
                        previousDefinitions, addedPaths,
                        (index, path) -> loadIncrementalBatchFile(
                                project, request, path,
                                preflight.get(index), capturedBytes,
                                settings, batchLease,
                                previousDefinitions,
                                addedPaths.contains(path), monitor,
                                cancelled, telemetry),
                        telemetry::observeChangedFilesLoaded);
            } catch (IOException | RuntimeException ex) {
                if (cancelled.getAsBoolean()) {
                    throw operationCancelled(ex);
                }
                if (ex instanceof
                        PackedProjectReferenceIndex.ProjectIndexAccessException) {
                    invalidateIncrementalRevision(
                            request, revision, ex);
                }
                Log.log(Log.LOG_DEBUG,
                        Messages.PgDbParser_deserialize_error, ex);
                transferred = true;
                return incrementalFullFallback(project, build, monitor,
                        telemetry,
                        RepairRefusal.BATCH_LOAD_FAILURE,
                        validationLease,
                        publicationLease, bootstrapStorage,
                        continuityProof);
            }
            if (batch.analysisErrors()) {
                transferred = true;
                return incrementalAnalysisErrors(project, telemetry,
                        canonicalRelativePaths, batch.loadedFiles(),
                        settings.getErrors(), epoch, validationLease,
                        publicationLease, bootstrapStorage);
            }
            List<ProjectIndexDelta.Change> changes = batch.changes();
            if (changes == null) {
                transferred = true;
                return incrementalFullFallback(project, build, monitor,
                        telemetry,
                        RepairRefusal.BATCH_FILE_UNSAFE,
                        validationLease,
                        publicationLease, bootstrapStorage,
                        continuityProof);
            }
            // Now, and not earlier: the rule needs the definitions the added
            // files turned out to carry, which only their analysis can say.
            boolean additionPermitted;
            try {
                additionPermitted = ProjectIndexIncrementalPlanner
                        .permitsAddedFiles(addedFilesGuard(validationLease),
                                addedContributions(changes, addedPaths));
            } catch (IOException | RuntimeException ex) {
                Log.log(Log.LOG_DEBUG,
                        Messages.PgDbParser_deserialize_error, ex);
                transferred = true;
                return incrementalFullFallback(project, build, monitor,
                        telemetry,
                        RepairRefusal.ADDED_FILES_CHECK_FAILURE,
                        validationLease,
                        publicationLease, bootstrapStorage,
                        continuityProof);
            }
            if (!additionPermitted) {
                transferred = true;
                return incrementalFullFallback(project, build, monitor,
                        telemetry,
                        RepairRefusal.ADDED_FILES_REFUSED,
                        validationLease,
                        publicationLease, bootstrapStorage,
                        continuityProof);
            }
            if (publicationLease != null) {
                publicationLease.recordBlockCache(telemetry);
            }
            ProjectIndexDelta replacements =
                    new ProjectIndexDelta(changes);
            boolean contextStable;
            try {
                contextStable = trustedContinuity
                        ? projectIndexContinuity.permitsIncremental(
                                continuityProof,
                                canonicalRelativePaths, epoch,
                                request.identity(),
                                expectedLiveStorage,
                                validationLease, revision)
                        : validateIncrementalInputs(scope,
                                validationLease,
                                replacements, addedPaths, monitor,
                                cancelled, telemetry);
            } catch (IOException | RuntimeException ex) {
                if (cancelled.getAsBoolean()) {
                    throw operationCancelled(ex);
                }
                Log.log(Log.LOG_DEBUG,
                        Messages.PgDbParser_deserialize_error, ex);
                transferred = true;
                return incrementalFullFallback(project, build, monitor,
                        telemetry,
                        RepairRefusal.REVALIDATION_FAILURE,
                        validationLease,
                        publicationLease, bootstrapStorage,
                        continuityProof);
            }
            // Two refusals, kept apart so the line can say which one it was.
            // The shape check stays behind the stability one and is still not
            // reached when the inputs moved.
            if (!contextStable) {
                transferred = true;
                return incrementalFullFallback(project, build, monitor,
                        telemetry,
                        RepairRefusal.CONTEXT_UNSTABLE,
                        validationLease,
                        publicationLease, bootstrapStorage,
                        continuityProof);
            }
            if (!ProjectIndexIncrementalPlanner
                    .areSafeReplacements(
                            previousDefinitions,
                            replacements, addedPaths)) {
                transferred = true;
                return incrementalFullFallback(project, build, monitor,
                        telemetry,
                        RepairRefusal.UNSAFE_REPLACEMENTS,
                        validationLease,
                        publicationLease, bootstrapStorage,
                        continuityProof);
            }

            List<IFile> markerFiles = canonicalRelativePaths.stream()
                    .map(relativePath -> project.getFile(
                            org.eclipse.core.runtime.Path
                                    .fromPortableString(
                                            relativePath)))
                    .toList();
            var incremental = new IncrementalPublication(scope,
                    expectedLiveStorage, bootstrapStorage,
                    validationLease, publicationLease, revision,
                    replacements, addedPaths, continuityProof,
                    trustedContinuity);
            validationLease = null;
            publicationLease = null;
            bootstrapStorage = null;
            var update = new PreparedUpdate(incremental,
                    () -> clearMarkers(markerFiles),
                    epoch, telemetry);
            transferred = true;
            return new PreparedIncrementalProjectIndex(false,
                    new PreparedProjectIndex(false, update));
        } catch (InterruptedException ex) {
            propagateProjectIndexInterruption(ex,
                    incrementalCancelled != null
                            && incrementalCancelled.getAsBoolean());
            throw new AssertionError("unreachable"); //$NON-NLS-1$
        } finally {
            if (!transferred) {
                closeIncrementalLeases(
                        validationLease, publicationLease);
                if (bootstrapStorage != null) {
                    bootstrapStorage.close();
                }
                telemetry.close();
            }
        }
    }

    /**
     * Collects the replacements of an incremental batch, stopping at the first
     * file that lost the right to replace its indexed contribution. Such a
     * file forces a full rebuild of the index, which makes loading the rest of
     * the batch wasted work.
     *
     * <p>The per-file check is the one
     * {@link ProjectIndexIncrementalPlanner#areSafeReplacements} applies to
     * every change of the batch, so stopping here can only reject batches that
     * the whole-batch check rejects too.
     *
     * <p>A file the index does not hold is exempt from the shape check and from
     * nothing else: there is no indexed shape to compare it against, and
     * {@link ProjectIndexIncrementalPlanner#permitsAddedFiles} decides its case
     * once the whole batch is loaded.
     *
     * <p>The shape of the batch is asked first and the errors second, so that
     * a file which both failed to parse and moved a definition is reported as
     * what it is - a file the index cannot be repaired around.</p>
     *
     * @param paths               batch files, in load order
     * @param previousDefinitions definitions the index holds for the batch
     * @param addedPaths          batch files the index does not hold
     * @param loader              loads one batch file
     * @param loaded              reports files that joined the batch
     * @return the collected replacements, or why the batch was turned down
     * @throws IOException          if a batch file could not be read
     * @throws InterruptedException if the build was cancelled
     */
    static IncrementalBatch collectIncrementalReplacements(
            List<IndexPathRef> paths,
            Map<IndexPathRef, List<PackedDefinition>> previousDefinitions,
            Set<IndexPathRef> addedPaths,
            IncrementalBatchFileLoader loader, LongConsumer loaded)
            throws IOException, InterruptedException {
        Objects.requireNonNull(addedPaths, "addedPaths"); //$NON-NLS-1$
        var changes = new ArrayList<ProjectIndexDelta.Change>(paths.size());
        for (int i = 0; i < paths.size(); i++) {
            LoadedBatchFile file = loader.load(i, paths.get(i));
            int loadedFiles = i + 1;
            if (file == null
                    || !addedPaths.contains(paths.get(i))
                            && !ProjectIndexIncrementalPlanner
                                    .isSafeReplacement(previousDefinitions,
                                            file.contribution())) {
                return IncrementalBatch.unsafe(loadedFiles);
            }
            if (!file.analysisReusable()) {
                return file.blockedOnlyByErrors()
                        ? IncrementalBatch.analysisErrors(loadedFiles)
                        : IncrementalBatch.unsafe(loadedFiles);
            }
            changes.add(ProjectIndexDelta.Change.replace(
                    file.stamp(), file.contribution()));
            loaded.accept(1);
        }
        return IncrementalBatch.applied(changes, paths.size());
    }

    /**
     * Captures, parses and analyses one file of an incremental batch. The
     * analysis is skipped for a file whose parsed definitions already changed
     * shape, because the batch then has to fall back to a full rebuild.
     *
     * @param preflight     inspection the batch took of this file
     * @param capturedBytes running total of the bytes the batch captured,
     *                      raised in place by what this file cost
     * @param added         whether the index does not hold this file, which
     *                      leaves it no shape to be checked against and makes
     *                      the early exit below inapplicable
     * @return the analysed file, or null when it lost the right to replace its
     *         indexed contribution
     * @throws IOException          if the file could not be read
     * @throws InterruptedException if the build was cancelled
     */
    private LoadedBatchFile loadIncrementalBatchFile(IProject project,
            ProjectIndexBuildCoordinator.Request request, IndexPathRef path,
            CurrentFile preflight, long[] capturedBytes, UISettings settings,
            IncrementalProjectReferenceIndex.AnalysisLease validationLease,
            Map<IndexPathRef, List<PackedDefinition>> previousDefinitions,
            boolean added,
            IProgressMonitor monitor, BooleanSupplier cancelled,
            ProjectIndexTelemetry.Run telemetry)
            throws IOException, InterruptedException {
        CapturedProjectFile captured = captureSingleFile(project, request,
                path, preflight,
                Math.min(MAX_INCREMENTAL_CAPTURE_BYTES,
                        MAX_INCREMENTAL_BATCH_CAPTURE_BYTES
                                - capturedBytes[0]),
                cancelled, telemetry);
        capturedBytes[0] = Math.addExact(capturedBytes[0],
                captured.bytes().length);
        // The parser of the index this file is being read back into, which the
        // identity of that index names - not the one this plugin was written
        // around.
        IDumpLoader loader = request.identity().databaseType()
                .getDatabaseProvider()
                .getDumpLoader(
                        () -> new ByteArrayInputStream(captured.bytes()),
                        captured.absolutePath().toString(), settings);
        loader.setMode(ParserListenerMode.SINGLE);
        LoadedReferences loaded = loadIncrementalReferenceData(loader,
                validationLease, path, monitor, telemetry,
                definitions -> added
                        || ProjectIndexIncrementalPlanner
                                .isSafeReplacement(previousDefinitions,
                                        incrementalContribution(request,
                                                validationLease,
                                                captured.stamp(),
                                                definitions, Map.of(),
                                                false)));
        if (loaded == null) {
            return null;
        }
        try {
            ProjectFileStamp after = singleFileStamp(project, request, path,
                    cancelled, telemetry);
            FileContribution replacement = incrementalContribution(request,
                    validationLease, after, loaded.storage(),
                    !loaded.errors().isEmpty());
            boolean owned = hasUniqueDefinitionOwnership(validationLease,
                    path, replacement);
            boolean reusable = isIncrementalAnalysisUsable(captured.stamp(),
                    after, true, loaded.errors()) && owned;
            // The same rule, asked again with the errors taken away: whatever
            // it answers then is what everything except the errors decided.
            // Asking the rule rather than repeating half of it is what keeps
            // the two verdicts from drifting apart.
            boolean withoutErrors = isIncrementalAnalysisUsable(
                    captured.stamp(), after, true, List.of()) && owned;
            return new LoadedBatchFile(after, replacement, reusable,
                    !reusable && withoutErrors);
        } finally {
            loaded.storage().close();
        }
    }

    /**
     * Asks the invalidation rule's two questions of a leased index.
     *
     * <p>The rule cannot take the lease itself - it lives in the package that
     * defines the index format, and a lease belongs to the parser - so it takes
     * this instead. A read that fails arrives as the lease's own unchecked
     * access exception and is handled where every other index read is.
     */
    private static ProjectIndexIncrementalPlanner.AddedFilesGuard
            addedFilesGuard(
                    IncrementalProjectReferenceIndex.AnalysisLease lease) {
        return new ProjectIndexIncrementalPlanner.AddedFilesGuard() {

            @Override
            public boolean anyFileMayHoldUnresolvedReferences() {
                return lease.anyFileMayHoldUnresolvedReferences();
            }

            @Override
            public boolean holdsDefinitionFor(
                    ProjectIndexDefinitionSubject subject) {
                return !lease.definitions(subject, null).isEmpty();
            }
        };
    }

    private static List<FileContribution> addedContributions(
            List<ProjectIndexDelta.Change> changes,
            Set<IndexPathRef> addedPaths) {
        return changes.stream()
                .filter(change -> addedPaths.contains(change.path()))
                .map(ProjectIndexDelta.Change::contribution)
                .toList();
    }

    static void propagateProjectIndexInterruption(
            InterruptedException cause, boolean expectedCancellation)
            throws InterruptedException {
        if (!expectedCancellation) {
            throw cause;
        }
        throw operationCancelled(cause);
    }

    private static OperationCanceledException operationCancelled(
            Throwable cause) {
        var cancelled = new OperationCanceledException();
        cancelled.initCause(cause);
        return cancelled;
    }

    private static void invalidateIncrementalRevision(
            ProjectIndexBuildCoordinator.Request request,
            ProjectIndexRevision revision, Throwable failure) {
        if (revision == null) {
            return;
        }
        try {
            new ProjectIndexStore(request.storeDirectory())
                    .invalidateCurrent(revision);
        } catch (IOException | RuntimeException ex) {
            failure.addSuppressed(ex);
            Log.log(Log.LOG_DEBUG,
                    Messages.PgDbParser_clean_parser_error, ex);
        }
    }

    /**
     * Asks the repair rule what to do about a warm attempt that has already
     * been made. An attempt that restored an index is not a candidate and is
     * not even asked about, so a hit never depends on what the preferences
     * say.
     *
     * @param build     the configuration this build works by, which is where
     *                  the rule about added files comes from - the same value
     *                  the index of this build is identified by, not whatever
     *                  the node says by the time the attempt is over
     * @param telemetry run that publishes what became of the repair
     * @return the files to repair the index from, or empty when it has to be
     *         rebuilt instead
     */
    private static Optional<List<String>> repairPlan(
            ProjectIndexBuildCoordinator.WarmAttempt attempt,
            ProjectIndexBuildConfiguration build,
            ProjectIndexTelemetry.Run telemetry) {
        if (attempt.hit()) {
            telemetry.repairNotConsidered();
            return Optional.empty();
        }
        ProjectIndexRepairPlanner.Decision decision =
                ProjectIndexRepairPlanner.decide(attempt.validation(),
                        build.configuration().incrementalAddedFiles(),
                        ProjectIndexWarmValidator.DEFAULT_MAX_DIVERGENCES);
        if (decision.refusal() != null) {
            telemetry.repairRefused(decision.refusal());
        }
        return decision.paths();
    }

    /**
     * Repairs a stale index by re-reading the files a warm validation named,
     * which is the same work an incremental batch does and is applied through
     * the same path.
     *
     * <p>No continuity proof is offered, and that is what makes the repair
     * safe. A proof would let the batch skip the check of its own inputs,
     * which is exactly the check that has to run here: the batch was derived
     * from a validation of a working tree, not from a build delta, so nothing
     * outside this method knows it is right. Without a proof the untrusted
     * path proves every input of the batch four times over, including that the
     * indexed stamps minus the batch still match the tree minus the batch. A
     * batch that named too few files fails that preflight and falls back to a
     * rebuild; one that named too many re-reads files that did not need it.
     * Neither can publish a wrong index.</p>
     *
     * @param paths files the validation disagreed with
     * @return the repaired index, or the full rebuild the batch fell back to
     */
    private PreparedProjectIndex repairProjectIndex(IProject project,
            ProjectIndexBuildConfiguration build,
            List<String> paths, IProgressMonitor monitor,
            ProjectIndexTelemetry.Run telemetry)
            throws InterruptedException, IOException {
        telemetry.mode(Mode.INCREMENTAL);
        telemetry.repairApplied();
        return prepareIncrementalProjectIndex(project, paths, monitor,
                telemetry, null, build).prepared();
    }

    /**
     * Gives up on an incremental batch and rebuilds the whole index.
     *
     * <p>The rebuild below must stay the plain one. A repair ends here when it
     * cannot be applied, and a rebuild that repaired again would derive the
     * very batch that just failed and hand it back to this method - forever.
     * That is why a repair is a separate entry point
     * ({@link #prepareRepairedProjectIndex}) and not a mode of the rebuild
     * every fallback reaches.</p>
     *
     * @param refusal what stopped this batch. It only describes a repair: an
     *                ordinary incremental build that falls back turned no
     *                repair down, and its line says nothing about one.
     */
    private PreparedIncrementalProjectIndex incrementalFullFallback(
            IProject project, ProjectIndexBuildConfiguration build,
            IProgressMonitor monitor,
            ProjectIndexTelemetry.Run telemetry,
            RepairRefusal refusal,
            IncrementalProjectReferenceIndex.AnalysisLease validationLease,
            PackedProjectReferenceIndex.PublicationLease publicationLease,
            PackedProjectReferenceIndex bootstrapStorage,
            BuildContinuityProof continuityProof)
            throws InterruptedException, IOException {
        closeIncrementalLeases(
                validationLease, publicationLease);
        if (bootstrapStorage != null) {
            bootstrapStorage.close();
        }
        telemetry.repairBatchAbandoned(refusal);
        telemetry.markParserCountsUnknown();
        telemetry.mode(Mode.COLD);
        return new PreparedIncrementalProjectIndex(true,
                prepareProjectIndex(project, monitor, telemetry,
                        continuityProof, StaleIndexPolicy.REBUILD, build));
    }

    /**
     * Drops an incremental batch a file of which did not parse, and rebuilds
     * nothing.
     *
     * <p>The index is not published - that refusal is deliberate and stands:
     * an index that recorded what a half-parsed file appeared to say would
     * answer wrongly for as long as it lived. What does not follow from it is
     * the rebuild. The file kept every definition the index holds for it, so
     * the index disagrees with the working tree about this one file and about
     * nothing else, and parsing the other two thousand files answers a
     * question nobody asked. The typing mistake that caused this is normally
     * corrected within seconds, and the correction arrives as a batch naming
     * the very same file.</p>
     *
     * <p><b>The markers are the whole reason this is not simply a return.</b>
     * Nothing else in the incremental path ever draws one: a published
     * increment only clears the markers of the files it published, and the red
     * underline the user sees today is drawn by the full rebuild this method
     * replaces. So it is drawn here, over exactly the prefix of the batch that
     * was read - the files after the one that stopped it were never opened,
     * and clearing their markers would erase what was last known about them
     * without putting anything in its place.</p>
     *
     * <p>The build reports no accepted continuity. It observed a change and
     * did not apply it, so the delta chain it belongs to is broken and saying
     * otherwise would let a later increment publish on top of an index that
     * quietly disagrees with the tree. The next build therefore reconciles -
     * and reconciling is cheap here precisely because this method left the
     * live index alone: the warm validation finds the one file that moved and
     * repairs it, instead of bootstrapping from nothing.</p>
     *
     * @param relativePaths the whole batch, in load order
     * @param loadedFiles   how many of them were read
     * @param errors        everything the batch's parses reported
     */
    private PreparedIncrementalProjectIndex incrementalAnalysisErrors(
            IProject project, ProjectIndexTelemetry.Run telemetry,
            List<String> relativePaths, int loadedFiles,
            List<Object> errors, long epoch,
            IncrementalProjectReferenceIndex.AnalysisLease validationLease,
            PackedProjectReferenceIndex.PublicationLease publicationLease,
            PackedProjectReferenceIndex bootstrapStorage) {
        closeIncrementalLeases(validationLease, publicationLease);
        if (bootstrapStorage != null) {
            bootstrapStorage.close();
        }
        telemetry.repairBatchAbandoned(RepairRefusal.BATCH_ANALYSIS_ERRORS);
        telemetry.bypass(BypassReason.ANALYSIS_ERRORS);
        List<IFile> markerFiles = relativePaths
                .subList(0, loadedFiles).stream()
                .map(relativePath -> project.getFile(
                        org.eclipse.core.runtime.Path
                                .fromPortableString(relativePath)))
                .toList();
        List<Object> reported = List.copyOf(errors);
        var update = new PreparedUpdate(() -> {
            clearMarkers(markerFiles);
            markErrors(reported);
        }, epoch, telemetry);
        return new PreparedIncrementalProjectIndex(false,
                new PreparedProjectIndex(false, update));
    }

    private static void closeIncrementalLeases(
            IncrementalProjectReferenceIndex.AnalysisLease validationLease,
            PackedProjectReferenceIndex.PublicationLease publicationLease) {
        if (validationLease != null) {
            validationLease.close();
        }
        if (publicationLease != null
                && publicationLease != validationLease) {
            publicationLease.close();
        }
    }

    private static List<IndexPathRef> canonicalIncrementalPaths(
            Collection<String> relativePaths) {
        Objects.requireNonNull(relativePaths, "relativePaths"); //$NON-NLS-1$
        var canonical = new TreeSet<String>();
        for (String relativePath : relativePaths) {
            String path = new IndexPathRef(
                    IndexPathOrigin.PROJECT,
                    relativePath).relativePath();
            if (!canonical.add(path)) {
                throw new IllegalArgumentException(
                        "Duplicate incremental project-index path"); //$NON-NLS-1$
            }
            if (canonical.size()
                    > ProjectIndexIncrementalPlanner.MAX_BATCH_SIZE) {
                throw new IllegalArgumentException(
                        "Incremental project-index batch exceeds " //$NON-NLS-1$
                                + ProjectIndexIncrementalPlanner.MAX_BATCH_SIZE
                                + " files"); //$NON-NLS-1$
            }
        }
        if (canonical.isEmpty()) {
            throw new IllegalArgumentException(
                    "Incremental project-index batch must not be empty"); //$NON-NLS-1$
        }
        return canonical.stream()
                .map(path -> new IndexPathRef(
                        IndexPathOrigin.PROJECT, path))
                .toList();
    }

    private static List<CurrentFile> inspectIncrementalBatch(
            IProject project,
            ProjectIndexBuildCoordinator.Request request,
            List<IndexPathRef> paths,
            ProjectIndexTelemetry.Run telemetry)
            throws IOException {
        List<Path> absolutePaths = paths.stream()
                .map(path -> request.projectRoot()
                        .resolve(path.relativePath()).normalize())
                .toList();
        List<CurrentFile> inspected = ProjectIndexFiles.inspect(
                absolutePaths, request.projectRoot(),
                request.libraryRoot(), modificationStamp(project));
        if (inspected.size() != paths.size()) {
            throw new IOException(
                    "Incremental project-index preflight is incomplete"); //$NON-NLS-1$
        }
        long totalBytes = 0;
        for (int i = 0; i < paths.size(); i++) {
            CurrentFile file = inspected.get(i);
            if (!paths.get(i).equals(file.path())
                    || file.size() > MAX_INCREMENTAL_CAPTURE_BYTES
                    || file.size()
                            > MAX_INCREMENTAL_BATCH_CAPTURE_BYTES
                                    - totalBytes) {
                throw new IOException(
                        "Incremental project-index batch exceeds its capture budget"); //$NON-NLS-1$
            }
            totalBytes += file.size();
        }
        telemetry.observeEnumeratedPaths(paths.size());
        telemetry.addSingleFileValidations(paths.size());
        return inspected;
    }

    private static FileContribution incrementalContribution(
            ProjectIndexBuildCoordinator.Request request,
            IncrementalProjectReferenceIndex.AnalysisLease validationLease,
            ProjectFileStamp stamp,
            ProjectReferencesStorage storage, boolean analysisFailed) {
        return incrementalContribution(request, validationLease, stamp,
                storage.getObjDefinitions(), storage.getObjReferences(),
                analysisFailed);
    }

    /**
     * Packs one re-analysed file.
     *
     * <p>Unlike the full build, this runs before the batch decides whether it
     * may keep the file: a file whose analysis reported errors is packed here
     * and discarded later by {@code collectIncrementalReplacements}. So this
     * must not refuse a failed analysis — it has to describe it honestly
     * instead, in case a later round starts publishing what it packs.
     *
     * @param analysisFailed whether this file's analysis reported any error,
     *                       which makes its contribution claim it may hold an
     *                       unresolved reference
     */
    private static FileContribution incrementalContribution(
            ProjectIndexBuildCoordinator.Request request,
            IncrementalProjectReferenceIndex.AnalysisLease validationLease,
            ProjectFileStamp stamp,
            Map<String, List<MetaStatement>> definitions,
            Map<String, Set<ObjectLocation>> references,
            boolean analysisFailed) {
        ProjectIndexPathResolver resolver =
                candidate -> (candidate.origin()
                        == IndexPathOrigin.PROJECT
                                ? request.projectRoot()
                                : request.libraryRoot())
                                        .resolve(candidate
                                                .relativePath())
                                        .toString();
        // Resolved through the very resolver the factory is given, so the two
        // path forms cannot drift apart. A mismatch would be rejected by the
        // factory, turning an ordinary abandoned increment into a failure.
        Set<String> pathsWithErrors = analysisFailed
                ? Set.of(resolver.resolve(stamp.path()))
                : Set.of();
        return ProjectIndexSnapshotFactory.create(
                request.identity(), validationLease.generation(),
                List.of(stamp), definitions, references,
                resolver, pathsWithErrors).files().getFirst();
    }

    static boolean isIncrementalAnalysisUsable(
            ProjectFileStamp captured, ProjectFileStamp current,
            boolean contextStable, List<?> errors) {
        return Objects.requireNonNull(captured, "captured") //$NON-NLS-1$
                .equals(Objects.requireNonNull(current, "current")) //$NON-NLS-1$
                && contextStable
                && Objects.requireNonNull(errors, "errors").isEmpty(); //$NON-NLS-1$
    }

    static boolean isIncrementalSnapshotConsistent(
            ProjectIndexIdentity requested,
            IncrementalProjectReferenceIndex.AnalysisLease analysis,
            IncrementalProjectReferenceIndex.AnalysisLease validation,
            ProjectIndexRevision publication) {
        Objects.requireNonNull(requested, "requested"); //$NON-NLS-1$
        Objects.requireNonNull(analysis, "analysis"); //$NON-NLS-1$
        Objects.requireNonNull(validation, "validation"); //$NON-NLS-1$
        ProjectIndexIdentity identity = validation.identity();
        long generation = validation.generation();
        return requested.equals(identity)
                && analysis.identity().equals(identity)
                && analysis.generation() == generation
                && (publication == null
                        || publication.identity().equals(identity)
                                && publication.generation() == generation);
    }

    static boolean isIncrementalReplacementSafe(
            IndexPathRef previousPath,
            List<PackedDefinition> previousDefinitions,
            FileContribution replacement,
            boolean uniqueDefinitionOwnership) {
        Objects.requireNonNull(previousPath, "previousPath"); //$NON-NLS-1$
        Objects.requireNonNull(previousDefinitions,
                "previousDefinitions"); //$NON-NLS-1$
        Objects.requireNonNull(replacement, "replacement"); //$NON-NLS-1$
        return !replacement.definitions().isEmpty()
                && uniqueDefinitionOwnership
                && ProjectIndexIncrementalPlanner.isSafeReplacement(
                        previousPath, previousDefinitions, replacement);
    }

    static PersistenceReason liveRejectionReason(
            boolean monitorCancelled) {
        return monitorCancelled
                ? PersistenceReason.CANCELLED
                : PersistenceReason.STALE_INPUT;
    }

    static PersistenceReason incrementalInterruptionReason(
            PersistenceReason explicitReason,
            boolean monitorCancelled, boolean epochCurrent) {
        PersistenceReason reason =
                Objects.requireNonNull(explicitReason,
                        "explicitReason"); //$NON-NLS-1$
        if (reason != PersistenceReason.NONE) {
            return reason;
        }
        return monitorCancelled || epochCurrent
                ? PersistenceReason.CANCELLED
                : PersistenceReason.STALE_INPUT;
    }

    private boolean hasIncrementalProjectIndex() {
        synchronized (stateLock) {
            return referencesStorage
                    instanceof IncrementalProjectReferenceIndex;
        }
    }

    private enum ComparisonIndexState {
        LIVE,
        RESTORED,
        UNAVAILABLE
    }

    private ComparisonIndexState restorePersistedProjectIndex(
            IProject project, IProgressMonitor monitor)
            throws InterruptedException {
        if (hasIncrementalProjectIndex()) {
            return ComparisonIndexState.LIVE;
        }

        ProjectIndexTelemetry.Run telemetry =
                ProjectIndexTelemetry.INSTANCE.start(Mode.WARM);
        Path projectRoot = ProjectUtils.getPath(project)
                .toAbsolutePath().normalize();
        BypassReason refusal = projectIndexRefusal(project, projectRoot);
        if (refusal != null) {
            telemetry.bypass(refusal);
            telemetry.close();
            return ComparisonIndexState.UNAVAILABLE;
        }

        ProjectIndexBuildScope scope;
        try {
            scope = createProjectIndexBuildScope(
                    project, projectRoot, monitor);
        } catch (InterruptedException ex) {
            telemetry.close();
            throw ex;
        } catch (IOException | IllegalArgumentException
                | UnsupportedOperationException ex) {
            Log.log(Log.LOG_DEBUG,
                    Messages.PgDbParser_deserialize_error, ex);
            telemetry.bypass(BypassReason.IDENTITY_UNAVAILABLE);
            telemetry.close();
            return ComparisonIndexState.UNAVAILABLE;
        }

        Optional<ProjectIndexBuildCoordinator.Prepared> restored;
        try {
            restored = ProjectIndexBuildCoordinator.tryPrepareWarm(
                    scope.request(),
                    new EclipseProjectIndexInputs(scope, monitor),
                    cancelled(monitor, currentConfigurationEpoch()),
                    telemetry, projectIndexStoreFactory);
        } catch (InterruptedException ex) {
            throw ex;
        } catch (OperationCanceledException ex) {
            throw interruptedComparisonRestore(ex);
        } catch (IOException | RuntimeException ex) {
            Log.log(Log.LOG_DEBUG,
                    Messages.PgDbParser_deserialize_error, ex);
            return ComparisonIndexState.UNAVAILABLE;
        }
        if (restored.isEmpty()) {
            return ComparisonIndexState.UNAVAILABLE;
        }

        ProjectIndexBuildCoordinator.Prepared prepared =
                restored.orElseThrow();
        var update = new PreparedUpdate(prepared, () -> {
            clearMarkers(project);
            markErrors(prepared.errors());
        }, currentConfigurationEpoch(), null, scope);
        try {
            update.commit(project.getName(), monitor);
        } catch (OperationCanceledException ex) {
            update.discard();
            throw interruptedComparisonRestore(ex);
        } catch (IOException | RuntimeException ex) {
            Log.log(Log.LOG_DEBUG,
                    Messages.PgDbParser_deserialize_error, ex);
            update.discard();
            return ComparisonIndexState.UNAVAILABLE;
        }
        if (update.wasPublished()) {
            return ComparisonIndexState.RESTORED;
        }
        update.discard();
        return ComparisonIndexState.UNAVAILABLE;
    }

    private static InterruptedException interruptedComparisonRestore(
            OperationCanceledException cause) {
        var interrupted = new InterruptedException(
                "Project index restore cancelled"); //$NON-NLS-1$
        interrupted.initCause(cause);
        return interrupted;
    }

    public PreparedProjectIndex prepareProjectIndex(IProject proj,
            IProgressMonitor monitor) throws InterruptedException, IOException {
        return prepareProjectIndex(proj, monitor,
                ProjectIndexTelemetry.INSTANCE.start(Mode.COLD), null);
    }

    public PreparedProjectIndex prepareProjectIndex(IProject proj,
            IProgressMonitor monitor,
            BuildContinuityProof continuityProof)
            throws InterruptedException, IOException {
        return prepareProjectIndex(proj, monitor, continuityProof, null);
    }

    /**
     * Prepares the project index and reports its phases to the build.
     *
     * @param proj            project to index
     * @param monitor         monitor of the running build
     * @param continuityProof proof the build is still the current one
     * @param progress        observable progress of the build, may be null
     * @return the prepared index
     * @throws InterruptedException if the build was cancelled
     * @throws IOException          if the project could not be read
     */
    public PreparedProjectIndex prepareProjectIndex(IProject proj,
            IProgressMonitor monitor,
            BuildContinuityProof continuityProof,
            ProjectBuildProgressSink progress)
            throws InterruptedException, IOException {
        return prepareProjectIndex(proj, monitor,
                observed(ProjectIndexTelemetry.INSTANCE.start(Mode.COLD),
                        progress),
                continuityProof);
    }

    /**
     * Prepares the project index, repairing a stale one from the files it
     * disagrees with when that disagreement is small enough to be worth
     * reading, and rebuilding it from nothing otherwise.
     *
     * <p>A rebuild of the reference project costs 105 s no matter how little
     * of it moved, while reading one file back costs 1.65 s and reading 128
     * costs 75.5 s. Every case this decides to repair is therefore a case a
     * rebuild would have overpaid for, and every case it refuses costs the
     * 1.65 s of the warm attempt that a rebuild pays anyway.</p>
     *
     * <p>A repair is never the default. {@link #prepareProjectIndex(IProject,
     * IProgressMonitor, BuildContinuityProof, ProjectBuildProgressSink)}
     * rebuilds, and it has to: a repair that turns out to be impossible ends
     * in a rebuild, so a rebuild that repaired again would derive the batch
     * that just failed and repair the same divergence forever.</p>
     *
     * @param proj            project to index
     * @param monitor         monitor of the running build
     * @param continuityProof proof the build is still the current one
     * @param progress        observable progress of the build, may be null
     * @return the prepared index, whether it was repaired or rebuilt
     * @throws InterruptedException if the build was cancelled
     * @throws IOException          if the project could not be read
     */
    public PreparedProjectIndex prepareRepairedProjectIndex(IProject proj,
            IProgressMonitor monitor,
            BuildContinuityProof continuityProof,
            ProjectBuildProgressSink progress)
            throws InterruptedException, IOException {
        return prepareRepairedProjectIndex(proj, monitor, continuityProof,
                progress, null);
    }

    /**
     * Prepares the project index by a configuration that was already read.
     *
     * <p>This is the form a builder uses; see
     * {@link #prepareIncrementalProjectIndex(IProject, Collection,
     * IProgressMonitor, BuildContinuityProof, ProjectBuildProgressSink,
     * ProjectIndexBuildConfiguration)} for why the build must not read the
     * node for itself once its caller has.</p>
     *
     * @param configuration the configuration this build was classified by, or
     *                      null to read one here
     */
    public PreparedProjectIndex prepareRepairedProjectIndex(IProject proj,
            IProgressMonitor monitor,
            BuildContinuityProof continuityProof,
            ProjectBuildProgressSink progress,
            ProjectIndexBuildConfiguration configuration)
            throws InterruptedException, IOException {
        return prepareProjectIndex(proj, monitor,
                observed(ProjectIndexTelemetry.INSTANCE.start(Mode.COLD),
                        progress),
                continuityProof, StaleIndexPolicy.REPAIR, configuration);
    }

    private static ProjectIndexTelemetry.Run observed(
            ProjectIndexTelemetry.Run telemetry,
            ProjectBuildProgressSink progress) {
        return progress == null
                ? telemetry : telemetry.observer(progress.observer());
    }

    PreparedProjectIndex prepareProjectIndex(IProject proj,
            IProgressMonitor monitor,
            ProjectIndexTelemetry.Run telemetry)
            throws InterruptedException, IOException {
        return prepareProjectIndex(proj, monitor, telemetry, null);
    }

    PreparedProjectIndex prepareProjectIndex(IProject proj,
            IProgressMonitor monitor,
            ProjectIndexTelemetry.Run telemetry,
            BuildContinuityProof continuityProof)
            throws InterruptedException, IOException {
        return prepareProjectIndex(proj, monitor, telemetry, continuityProof,
                StaleIndexPolicy.REBUILD);
    }

    PreparedProjectIndex prepareRepairedProjectIndex(IProject proj,
            IProgressMonitor monitor,
            ProjectIndexTelemetry.Run telemetry,
            BuildContinuityProof continuityProof)
            throws InterruptedException, IOException {
        return prepareRepairedProjectIndex(proj, monitor, telemetry,
                continuityProof, null);
    }

    PreparedProjectIndex prepareRepairedProjectIndex(IProject proj,
            IProgressMonitor monitor,
            ProjectIndexTelemetry.Run telemetry,
            BuildContinuityProof continuityProof,
            ProjectIndexBuildConfiguration build)
            throws InterruptedException, IOException {
        return prepareProjectIndex(proj, monitor, telemetry, continuityProof,
                StaleIndexPolicy.REPAIR, build);
    }

    /**
     * What this build does with an index the working tree has moved away
     * from.
     */
    private enum StaleIndexPolicy {
        /** Parses the whole project again. */
        REBUILD,
        /**
         * Re-reads the files the warm validation named, when the rule of
         * {@link ProjectIndexRepairPlanner} permits it, and rebuilds
         * otherwise.
         */
        REPAIR
    }

    private PreparedProjectIndex prepareProjectIndex(IProject proj,
            IProgressMonitor monitor,
            ProjectIndexTelemetry.Run telemetry,
            BuildContinuityProof continuityProof,
            StaleIndexPolicy stalePolicy)
            throws InterruptedException, IOException {
        return prepareProjectIndex(proj, monitor, telemetry, continuityProof,
                stalePolicy, null);
    }

    private PreparedProjectIndex prepareProjectIndex(IProject proj,
            IProgressMonitor monitor,
            ProjectIndexTelemetry.Run telemetry,
            BuildContinuityProof continuityProof,
            StaleIndexPolicy stalePolicy,
            ProjectIndexBuildConfiguration build)
            throws InterruptedException, IOException {
        if (stalePolicy == StaleIndexPolicy.REBUILD) {
            // A refused repair lands in a rebuild too, and the refusal it
            // already published is what describes that build.
            telemetry.repairNotConsidered();
        }
        long epoch = currentConfigurationEpoch();
        BooleanSupplier buildCancelled =
                cancelled(monitor, epoch, continuityProof);
        boolean transferred = false;
        try {
            if (buildCancelled.getAsBoolean()) {
                throw new InterruptedException(
                        "Project index reconciliation cancelled"); //$NON-NLS-1$
            }
            Path projectRoot = ProjectUtils.getPath(proj)
                    .toAbsolutePath().normalize();
            BypassReason refusal = projectIndexRefusal(proj, projectRoot);
            if (refusal != null) {
                telemetry.bypass(refusal);
                PreparedUpdate update = prepareFullDBFromPgDbProject(proj,
                        monitor, telemetry);
                transferred = true;
                return new PreparedProjectIndex(false, update);
            }

            ProjectIndexBuildScope scope;
            try {
                scope = createProjectIndexBuildScope(proj, projectRoot,
                        build, monitor);
            } catch (IOException | IllegalArgumentException
                    | UnsupportedOperationException ex) {
                Log.log(Log.LOG_DEBUG,
                        Messages.PgDbParser_deserialize_error, ex);
                telemetry.bypass(BypassReason.IDENTITY_UNAVAILABLE);
                PreparedUpdate update = prepareFullDBFromPgDbProject(proj,
                        monitor, telemetry);
                transferred = true;
                return new PreparedProjectIndex(false, update);
            }
            ProjectIndexBuildCoordinator.Request request = scope.request();
            var inputs = new EclipseProjectIndexInputs(scope, monitor);
            ProjectIndexBuildCoordinator.FullBuild fullBuild = () -> {
                LoadedReferences loaded = loadFullReferenceData(
                        proj, monitor, true, scope.configuration(),
                        telemetry);
                return new ProjectIndexBuildCoordinator.BuildResult(
                        loaded.storage(), loaded.errors(),
                        loaded.inputFingerprints());
            };
            boolean trustedWarmValidation = continuityProof != null
                    && continuityProof.isCurrent()
                    && continuityProof.kind() != Kind.NO_OP;
            ProjectIndexBuildCoordinator.Prepared prepared;
            if (stalePolicy == StaleIndexPolicy.REPAIR) {
                var attempt = ProjectIndexBuildCoordinator.attemptWarm(
                        request, inputs, buildCancelled, telemetry,
                        trustedWarmValidation, projectIndexStoreFactory);
                Optional<List<String>> repair = repairPlan(attempt,
                        scope.configuration(), telemetry);
                if (repair.isPresent()) {
                    transferred = true;
                    return repairProjectIndex(proj, scope.configuration(),
                            repair.orElseThrow(),
                            monitor, telemetry);
                }
                prepared = attempt.hit()
                        ? attempt.prepared().orElseThrow()
                        : attempt.completeFullBuild(fullBuild);
            } else {
                prepared = ProjectIndexBuildCoordinator.prepare(request,
                        inputs, fullBuild, buildCancelled, telemetry,
                        trustedWarmValidation, projectIndexStoreFactory);
            }
            var update = new PreparedUpdate(prepared, () -> {
                clearMarkers(proj);
                markErrors(prepared.errors());
            }, epoch, continuityProof, scope);
            transferred = true;
            return new PreparedProjectIndex(
                    prepared.restoredFromDisk(), update);
        } catch (InterruptedException ex) {
            propagateProjectIndexInterruption(ex,
                    monitor != null && monitor.isCanceled()
                            || !isCurrent(epoch)
                            || continuityProof != null
                                    && !continuityProof.isCurrent());
            throw new AssertionError("unreachable"); //$NON-NLS-1$
        } finally {
            if (!transferred) {
                telemetry.close();
            }
        }
    }

    public PreparedUpdate prepareFullDBFromPgDbProject(IProject proj, IProgressMonitor monitor)
            throws InterruptedException, IOException {
        ProjectIndexTelemetry.Run telemetry =
                ProjectIndexTelemetry.INSTANCE.start(Mode.BYPASS)
                        .bypass(BypassReason.DIRECT_FULL_BUILD);
        return prepareFullDBFromPgDbProject(proj, monitor, telemetry);
    }

    private PreparedUpdate prepareFullDBFromPgDbProject(IProject proj,
            IProgressMonitor monitor, ProjectIndexTelemetry.Run telemetry)
            throws InterruptedException, IOException {
        long epoch = currentConfigurationEpoch();
        boolean transferred = false;
        try {
            // A bypass stamps no identity, so nothing can later be found to
            // disagree with this walk; it still gets one configuration for the
            // whole of it, because a walk that changed its mind halfway
            // through would be no project at all.
            LoadedReferences loaded = loadFullReferenceData(proj, monitor,
                    false, ProjectIndexBuildConfiguration.capture(
                            () -> effectiveProjectIndexConfiguration(proj)),
                    telemetry);
            // A bypass still parses the whole project into a whole new model,
            // so what it publishes is this project's index by every measure
            // except the store it was not written to.
            var update = new PreparedUpdate(loaded.storage(), () -> {
                clearMarkers(proj);
                markErrors(loaded.errors());
            }, epoch, true, telemetry, null, proj);
            transferred = true;
            return update;
        } catch (InterruptedException ex) {
            propagateProjectIndexInterruption(ex,
                    monitor != null && monitor.isCanceled()
                            || !isCurrent(epoch));
            throw new AssertionError("unreachable"); //$NON-NLS-1$
        } finally {
            if (!transferred) {
                telemetry.close();
            }
        }
    }

    /**
     * Parses the whole project.
     *
     * <p>The configuration is a parameter and there is no form of this without
     * one: the walk this starts is what decides which files end up in the
     * index, so it must be the walk the identity of that index was stamped
     * for. A build hands in the configuration it captured; a bypass, which
     * stamps no identity at all, captures one of its own and is the only
     * caller that may.</p>
     *
     * @param build the configuration this load enumerates and parses by
     */
    private LoadedReferences loadFullReferenceData(
            IProject proj, IProgressMonitor monitor,
            boolean captureInputFingerprints,
            ProjectIndexBuildConfiguration build,
            ProjectIndexTelemetry.Run telemetry)
            throws IOException, InterruptedException {
        UISettings settings = UISettings.forProjectIndex(proj, build);
        // Core counts parsed files and analyzed objects from its worker
        // threads, and an Eclipse monitor is not safe to write from several of
        // them. The observer serializes those counts; without one the load
        // still has to carry cancellation, so the plain monitor stays.
        settings.setMonitor(telemetry.coreMonitor(
                () -> new UIMonitor(monitor)));
        var provider = build.configuration().databaseType()
                .getDatabaseProvider();
        ILoader loader = provider.getProjectLoader(ProjectUtils.getPath(proj),
                settings,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                LibraryUtils.META_PATH);
        return loadReferenceData(loader, monitor, true,
                captureInputFingerprints, telemetry);
    }

    /**
     * Everything one project-index build is: what it was asked to build, and
     * the configuration it works by.
     *
     * <p>The two are made together, from one reading of the preference node,
     * and nothing can pair them differently - this record is the only way a
     * build obtains either of them, and only {@link
     * #createProjectIndexBuildScope} makes one. Every step of the build is
     * handed the scope and none of them is handed the node: the files that are
     * enumerated, the identity they are stored under and the guard the result
     * is published through all answer to the same value, by construction.</p>
     */
    private record ProjectIndexBuildScope(IProject project,
            ProjectIndexBuildCoordinator.Request request,
            ProjectIndexBuildConfiguration configuration) {

        private ProjectIndexBuildScope {
            Objects.requireNonNull(project, "project"); //$NON-NLS-1$
            Objects.requireNonNull(request, "request"); //$NON-NLS-1$
            Objects.requireNonNull(configuration, "configuration"); //$NON-NLS-1$
        }
    }

    private ProjectIndexBuildScope createProjectIndexBuildScope(
            IProject project, Path projectRoot, IProgressMonitor monitor)
            throws IOException, InterruptedException {
        return createProjectIndexBuildScope(project, projectRoot, null,
                monitor);
    }

    /**
     * @param captured the configuration the caller already read for this
     *                 build, or null when the caller read none and this is
     *                 where the build's one reading happens. A builder hands
     *                 in what it classified by: the classification decides
     *                 which files this build is told about, so a build that
     *                 read the node again could enumerate for one
     *                 configuration what was selected for another.
     */
    private ProjectIndexBuildScope createProjectIndexBuildScope(
            IProject project, Path projectRoot,
            ProjectIndexBuildConfiguration captured,
            IProgressMonitor monitor)
            throws IOException, InterruptedException {
        // The one reading of the preference node this build is allowed.
        ProjectIndexBuildConfiguration configuration = captured != null
                ? captured
                : ProjectIndexBuildConfiguration.capture(
                        () -> effectiveProjectIndexConfiguration(project));
        ProjectIndexIdentity identity = createProjectIndexIdentity(project,
                projectRoot, configuration, monitor);
        var request = new ProjectIndexBuildCoordinator.Request(
                ProjectIndexState.directory(getStateRoot(), projectRoot),
                getPathToObject(project.getName()), projectRoot,
                LibraryUtils.META_PATH, identity,
                Math.max(0, System.currentTimeMillis()));
        return new ProjectIndexBuildScope(project, request, configuration);
    }

    /**
     * The identity the project has right now, read fresh from the node.
     *
     * <p>This is a question, not a build: it is asked by whoever needs to know
     * whether a build is still describing the project it was started for, and
     * answering it from that build's own snapshot would only ever say yes. No
     * build may call it to obtain a configuration to work by - a build has
     * exactly one, and it is in its scope.</p>
     */
    private ProjectIndexIdentity createProjectIndexIdentity(IProject project,
            Path projectRoot, IProgressMonitor monitor)
            throws IOException, InterruptedException {
        return createProjectIndexIdentity(project, projectRoot,
                ProjectIndexBuildConfiguration.capture(
                        () -> effectiveProjectIndexConfiguration(project)),
                monitor);
    }

    private ProjectIndexIdentity createProjectIndexIdentity(IProject project,
            Path projectRoot, ProjectIndexBuildConfiguration build,
            IProgressMonitor monitor)
            throws IOException, InterruptedException {
        BooleanSupplier cancelled = monitor == null
                ? () -> false : monitor::isCanceled;
        ProjectIndexConfiguration configuration = build.configuration();
        byte[] config = ProjectIndexConfigDigest.calculate(projectRoot,
                configuration, cancelled);
        var coreBundle = Platform.getBundle("org.pgcodekeeper.core"); //$NON-NLS-1$
        var uiBundle = Activator.getContext() == null
                ? null : Activator.getContext().getBundle();
        if (coreBundle == null || uiBundle == null) {
            throw new IOException(
                    "Project index bundle versions are unavailable"); //$NON-NLS-1$
        }
        return new ProjectIndexIdentity(2,
                coreBundle.getVersion().toString(),
                uiBundle.getVersion().toString(),
                configuration.databaseType(),
                ProjectIndexState.projectIdentity(projectRoot), config);
    }

    /**
     * Acquires a fail-closed lease for the exact live project-index
     * publication after independently validating all inputs covered by that
     * index. Callers must separately validate the complete Get Changes input
     * set because early schema exclusions intentionally make the index a
     * subset of the comparison project.
     */
    public Optional<ValidatedProjectSnapshotLease>
            acquireValidatedProjectSnapshotLease(
                    IProject project, IProgressMonitor monitor)
                    throws InterruptedException {
        Objects.requireNonNull(project, "project"); //$NON-NLS-1$
        if (monitor != null && monitor.isCanceled()) {
            throw new InterruptedException(
                    "Project snapshot validation cancelled"); //$NON-NLS-1$
        }

        IncrementalProjectReferenceIndex.AnalysisLease analysis = null;
        ProjectReferenceIndex expectedStorage;
        ProjectIndexRevision revision = null;
        long expectedConfigurationEpoch;
        long expectedContentEpoch;
        long expectedPublicationSequence;
        boolean transferred = false;
        try {
            Path projectRoot = ProjectUtils.getPath(project)
                    .toAbsolutePath().normalize();
            if (projectIndexRefusal(project, projectRoot) != null) {
                return Optional.empty();
            }
            synchronized (stateLock) {
                if (!(referencesStorage
                        instanceof IncrementalProjectReferenceIndex
                                incremental)) {
                    return Optional.empty();
                }
                expectedStorage = referencesStorage;
                expectedConfigurationEpoch = configurationEpoch;
                expectedContentEpoch = contentEpoch;
                expectedPublicationSequence = publicationSequence;
                analysis = incremental.acquireAnalysisLease();
                if (expectedStorage
                        instanceof PackedProjectReferenceIndex packed) {
                    revision = packed.revision();
                }
            }

            ProjectIndexBuildScope scope =
                    createProjectIndexBuildScope(
                            project, projectRoot, monitor);
            if (!scope.request().identity().equals(analysis.identity())) {
                return Optional.empty();
            }
            var inputs = new EclipseProjectIndexInputs(scope, monitor);
            List<CurrentFile> current = inputs.inspect();
            BooleanSupplier cancelled = () -> monitor != null
                    && monitor.isCanceled();
            ProjectIndexWarmValidator.Result validation =
                    ProjectIndexWarmValidator.validate(
                            analysis.fileStamps(), current,
                            file -> inputs.hashAll(
                                    List.of(file), cancelled)
                                    .getFirst().contentSha256(),
                            cancelled);
            if (!validation.hit()) {
                return Optional.empty();
            }
            List<CurrentFile> after = inputs.inspect();
            inputs.verifyIdentity();
            if (!current.equals(after)) {
                return Optional.empty();
            }

            var token = new ProjectSnapshotToken(
                    analysis.identity(), analysis.generation(),
                    Optional.ofNullable(revision),
                    expectedPublicationSequence);
            synchronized (stateLock) {
                if (!isValidatedSnapshotCurrentLocked(
                        expectedStorage, analysis,
                        expectedConfigurationEpoch,
                        expectedContentEpoch, token)) {
                    return Optional.empty();
                }
            }
            var result = new ValidatedProjectSnapshotLease(
                    expectedStorage, analysis,
                    expectedConfigurationEpoch,
                    expectedContentEpoch, token);
            analysis = null;
            transferred = true;
            return Optional.of(result);
        } catch (IOException | RuntimeException ex) {
            Log.log(Log.LOG_DEBUG,
                    Messages.PgDbParser_deserialize_error, ex);
            return Optional.empty();
        } finally {
            if (!transferred && analysis != null) {
                analysis.close();
            }
        }
    }

    /**
     * Acquires a lightweight publication gate even when the background
     * project index has not been built or cannot be restored.
     */
    public ProjectMutationLease acquireProjectMutationLease() {
        synchronized (stateLock) {
            return new ProjectMutationLease(
                    configurationEpoch, contentEpoch);
        }
    }

    private boolean isValidatedSnapshotCurrentLocked(
            ProjectReferenceIndex expectedStorage,
            IncrementalProjectReferenceIndex.AnalysisLease analysis,
            long expectedConfigurationEpoch,
            long expectedContentEpoch,
            ProjectSnapshotToken token) {
        if (referencesStorage != expectedStorage
                || configurationEpoch != expectedConfigurationEpoch
                || contentEpoch != expectedContentEpoch
                || publicationSequence != token.publicationSequence()
                || !token.identity().equals(analysis.identity())
                || token.generation() != analysis.generation()) {
            return false;
        }
        if (!(expectedStorage
                instanceof IncrementalProjectReferenceIndex incremental)) {
            return false;
        }
        try (var current = incremental.acquireAnalysisLease()) {
            if (!token.identity().equals(current.identity())
                    || token.generation() != current.generation()) {
                return false;
            }
        } catch (RuntimeException ex) {
            return false;
        }
        if (expectedStorage
                instanceof PackedProjectReferenceIndex packed) {
            try {
                return token.revision().isPresent()
                        && token.revision().get().equals(
                                packed.revision());
            } catch (RuntimeException ex) {
                return false;
            }
        }
        return token.revision().isEmpty();
    }

    private BooleanSupplier cancelled(IProgressMonitor monitor, long epoch) {
        return cancelled(monitor, epoch, null);
    }

    private BooleanSupplier cancelled(IProgressMonitor monitor, long epoch,
            BuildContinuityProof continuityProof) {
        return () -> monitor != null && monitor.isCanceled()
                || !isCurrent(epoch)
                || continuityProof != null
                        && !continuityProof.isCurrent();
    }

    static boolean isPersistentProjectIndexSupported(Path projectRoot) {
        try {
            return new LibraryXmlStore(projectRoot.resolve(
                    LibraryXmlStore.FILE_NAME)).readObjects().isEmpty();
        } catch (IOException | RuntimeException ex) {
            return false;
        }
    }

    /**
     * Why this project has no background index, or null when it has one.
     *
     * <p>Three questions, asked in one place so that every caller refuses the
     * same projects for the same stated reason and in the same order: whether
     * the project only receives changes from a database and so has no use for
     * an index of its own, whether the index covers the type of the project
     * at all, and whether it can be built from the layout this one has. Only
     * the last two were ever asked here, and a project of another type passed
     * them whenever it had no libraries.</p>
     *
     * <p>The receive-only question comes first and is answered from
     * {@code project} alone - a preference lookup, not a file read - so a
     * project this build never indexes anyway does not also pay to learn
     * that its layout would have allowed one. See
     * {@link ProjectIndexSupportPolicy#refusal}.</p>
     *
     * <p>Package-visible, like {@link #isPersistentProjectIndexSupported},
     * so a test can pin down the refusal itself instead of only the builds
     * that consult it.</p>
     */
    static BypassReason projectIndexRefusal(IProject project,
            Path projectRoot) {
        return ProjectIndexSupportPolicy.refusal(
                ProjectReceiveOnlyMode.isEnabled(project),
                () -> isPersistentProjectIndexSupported(projectRoot));
    }

    private final class EclipseProjectIndexInputs
            implements ProjectIndexBuildCoordinator.InputSource {

        private final IProject project;
        private final ProjectIndexBuildCoordinator.Request request;
        private final ProjectIndexBuildConfiguration configuration;
        private final IProgressMonitor monitor;
        private final ProjectIndexPathResolver resolver;

        /**
         * The scope is the only thing this takes, and that is the point: the
         * files it enumerates and the identity it enumerates them for come out
         * of one object, so no reading of the preference node can put them at
         * odds.
         */
        private EclipseProjectIndexInputs(ProjectIndexBuildScope scope,
                IProgressMonitor monitor) {
            this.project = scope.project();
            this.request = scope.request();
            this.configuration = scope.configuration();
            this.monitor = monitor;
            resolver = path -> (path.origin() == IndexPathOrigin.PROJECT
                    ? request.projectRoot() : request.libraryRoot())
                            .resolve(path.relativePath()).toString();
        }

        @Override
        public List<CurrentFile> inspect()
                throws IOException, InterruptedException {
            requireCurrentIdentity();
            UISettings settings = UISettings.forProjectIndex(project,
                    configuration);
            settings.setMonitor(new UIMonitor(monitor));
            var provider = request.identity().databaseType()
                    .getDatabaseProvider();
            IProjectLoader loader = provider.getProjectLoader(
                    request.projectRoot(), settings,
                    Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList(), request.libraryRoot());
            List<Path> paths = listInputFiles(loader, monitor);
            List<CurrentFile> files = ProjectIndexFiles.inspect(paths,
                    request.projectRoot(), request.libraryRoot(),
                    modificationStamp(project));
            requireCurrentIdentity();
            return files;
        }

        @Override
        public List<ru.taximaxim.codekeeper.ui.projectindex.ProjectFileStamp>
                hashAll(List<CurrentFile> files, BooleanSupplier cancelled)
                        throws IOException, InterruptedException {
            return ProjectIndexFiles.hashAll(files, resolver, cancelled);
        }

        @Override
        public ProjectIndexPathResolver resolver() {
            return resolver;
        }

        @Override
        public void verifyIdentity()
                throws IOException, InterruptedException {
            requireCurrentIdentity();
        }

        private void requireCurrentIdentity()
                throws IOException, InterruptedException {
            ProjectIndexIdentity current = createProjectIndexIdentity(project,
                    request.projectRoot(), monitor);
            if (!request.identity().equals(current)) {
                throw new ProjectIndexBuildCoordinator.StaleBuildException(
                        "Project index configuration changed during build"); //$NON-NLS-1$
            }
        }
    }

    private static ToLongFunction<IndexPathRef> modificationStamp(
            IProject project) {
        return path -> {
            if (path.origin() != IndexPathOrigin.PROJECT) {
                return -1;
            }
            IFile file = project.getFile(
                    org.eclipse.core.runtime.Path.fromPortableString(
                            path.relativePath()));
            return file.exists() ? file.getModificationStamp() : -1;
        };
    }

    private static ProjectFileStamp singleFileStamp(IProject project,
            ProjectIndexBuildCoordinator.Request request,
            IndexPathRef path, BooleanSupplier cancelled,
            ProjectIndexTelemetry.Run telemetry)
            throws IOException, InterruptedException {
        if (path.origin() != IndexPathOrigin.PROJECT) {
            throw new IOException(
                    "Incremental project-index path is not a project file"); //$NON-NLS-1$
        }
        Path absolute = request.projectRoot()
                .resolve(path.relativePath()).normalize();
        List<CurrentFile> inspected = ProjectIndexFiles.inspect(
                List.of(absolute), request.projectRoot(),
                request.libraryRoot(), modificationStamp(project));
        if (telemetry != null) {
            telemetry.observeEnumeratedPaths(1);
            telemetry.addSingleFileValidations(1);
        }
        List<ProjectFileStamp> hashed = ProjectIndexFiles.hashAll(
                inspected, candidate -> (candidate.origin()
                        == IndexPathOrigin.PROJECT
                                ? request.projectRoot()
                                : request.libraryRoot())
                                        .resolve(candidate.relativePath())
                                        .toString(),
                cancelled);
        if (telemetry != null) {
            telemetry.addRereadHashedPaths(1);
        }
        ProjectFileStamp result = hashed.getFirst();
        if (!path.equals(result.path())) {
            throw new IOException(
                    "Incremental project-index path changed"); //$NON-NLS-1$
        }
        return result;
    }

    private static CapturedProjectFile captureSingleFile(
            IProject project,
            ProjectIndexBuildCoordinator.Request request,
            IndexPathRef path, CurrentFile expected,
            long remainingBatchBytes,
            BooleanSupplier cancelled,
            ProjectIndexTelemetry.Run telemetry)
            throws IOException, InterruptedException {
        if (path.origin() != IndexPathOrigin.PROJECT) {
            throw new IOException(
                    "Incremental project-index path is not a project file"); //$NON-NLS-1$
        }
        if (cancelled.getAsBoolean()) {
            throw new InterruptedException(
                    "Incremental project-index capture cancelled"); //$NON-NLS-1$
        }
        Path absolute = request.projectRoot()
                .resolve(path.relativePath()).normalize();
        if (!path.equals(expected.path())) {
            throw new IOException(
                    "Incremental project-index preflight path changed"); //$NON-NLS-1$
        }
        byte[] bytes = readIncrementalSnapshot(
                absolute, remainingBatchBytes);
        if (cancelled.getAsBoolean()) {
            throw new InterruptedException(
                    "Incremental project-index capture cancelled"); //$NON-NLS-1$
        }
        List<CurrentFile> after = ProjectIndexFiles.inspect(
                List.of(absolute), request.projectRoot(),
                request.libraryRoot(), modificationStamp(project));
        if (!List.of(expected).equals(after)
                || bytes.length != after.getFirst().size()
                || !path.equals(after.getFirst().path())) {
            throw new IOException(
                    "Incremental project-index file changed during capture"); //$NON-NLS-1$
        }
        byte[] sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256") //$NON-NLS-1$
                    .digest(bytes);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable", ex); //$NON-NLS-1$
        }
        if (telemetry != null) {
            telemetry.observeEnumeratedPaths(1);
            telemetry.addSingleFileValidations(1);
            telemetry.addInlineHashedPaths(1);
        }
        CurrentFile file = after.getFirst();
        return new CapturedProjectFile(
                new ProjectFileStamp(path,
                        file.eclipseModificationStamp(),
                        file.size(), file.lastModifiedMillis(),
                        sha256),
                bytes, absolute);
    }

    static byte[] readIncrementalSnapshot(Path absolute)
            throws IOException {
        return readIncrementalSnapshot(
                absolute, MAX_INCREMENTAL_CAPTURE_BYTES);
    }

    static byte[] readIncrementalSnapshot(Path absolute,
            long maximumBytes) throws IOException {
        if (maximumBytes < 0
                || maximumBytes > MAX_INCREMENTAL_CAPTURE_BYTES) {
            throw new IllegalArgumentException(
                    "Invalid incremental project-index byte budget"); //$NON-NLS-1$
        }
        long size = Files.size(absolute);
        if (size < 0 || size > maximumBytes) {
            throw new IOException(
                    "Incremental project-index file is too large"); //$NON-NLS-1$
        }
        try (InputStream input = Files.newInputStream(absolute)) {
            byte[] bytes = new byte[Math.toIntExact(size)];
            int offset = 0;
            while (offset < bytes.length) {
                int count = input.read(bytes, offset,
                        bytes.length - offset);
                if (count < 0) {
                    throw new IOException(
                            "Incremental project-index file shrank during capture"); //$NON-NLS-1$
                }
                if (count == 0) {
                    continue;
                }
                offset += count;
            }
            if (input.read() != -1) {
                throw new IOException(
                        "Incremental project-index file grew during capture"); //$NON-NLS-1$
            }
            return bytes;
        }
    }

    private boolean validateIncrementalInputs(ProjectIndexBuildScope scope,
            IncrementalProjectReferenceIndex.AnalysisLease packed,
            ProjectIndexDelta replacements,
            Set<IndexPathRef> addedPaths,
            IProgressMonitor monitor,
            BooleanSupplier cancelled,
            ProjectIndexTelemetry.Run telemetry)
            throws IOException, InterruptedException {
        var expectedChanged =
                new LinkedHashMap<IndexPathRef, ProjectFileStamp>();
        for (ProjectIndexDelta.Change change :
                replacements.changes()) {
            expectedChanged.put(change.path(), change.stamp());
        }
        return validateIncrementalInputs(scope,
                packed, replacements.changes().stream()
                        .map(ProjectIndexDelta.Change::path)
                        .toList(),
                addedPaths, expectedChanged, monitor, cancelled,
                telemetry);
    }

    /**
     * Revalidates the whole working tree against the index the batch was built
     * from.
     *
     * <p>A path in {@code addedPaths} inverts one of these checks: the index
     * must <em>not</em> hold a stamp for it, since a stamp would mean the batch
     * misread the index it is amending. The working tree must hold it either
     * way, and the file it holds is compared against the stamp the batch
     * captured, so an addition is validated as strictly as a replacement.
     */
    private boolean validateIncrementalInputs(ProjectIndexBuildScope scope,
            IncrementalProjectReferenceIndex.AnalysisLease packed,
            Collection<IndexPathRef> changedPaths,
            Set<IndexPathRef> addedPaths,
            Map<IndexPathRef, ProjectFileStamp> expectedChanged,
            IProgressMonitor monitor,
            BooleanSupplier cancelled,
            ProjectIndexTelemetry.Run telemetry)
            throws IOException, InterruptedException {
        Set<IndexPathRef> changed = Set.copyOf(changedPaths);
        if (changed.isEmpty()
                || changed.size() != changedPaths.size()
                || !changed.containsAll(addedPaths)
                || expectedChanged != null
                        && !expectedChanged.keySet()
                                .equals(changed)) {
            return false;
        }
        var inputs = new EclipseProjectIndexInputs(scope, monitor);
        inputs.verifyIdentity();
        List<CurrentFile> current = inputs.inspect();
        telemetry.observeEnumerationPass(current.size());
        List<ProjectFileStamp> packedStamps =
                packed.fileStamps();
        for (IndexPathRef changedPath : changed) {
            long indexed = packedStamps.stream()
                    .filter(stamp -> changedPath.equals(stamp.path()))
                    .count();
            if (indexed != (addedPaths.contains(changedPath) ? 0 : 1)
                    || current.stream()
                            .filter(file -> changedPath.equals(
                                    file.path()))
                            .count() != 1) {
                return false;
            }
        }
        List<ProjectFileStamp> expected = expectedChanged == null
                ? packedStamps.stream()
                        .filter(stamp -> !changed.contains(
                                stamp.path()))
                        .toList()
                : expectedStamps(packedStamps, changed,
                        addedPaths, expectedChanged);
        List<CurrentFile> validatedCurrent =
                expectedChanged == null
                        ? current.stream()
                                .filter(file -> !changed.contains(
                                        file.path()))
                                .toList()
                        : current;
        ProjectIndexWarmValidator.Result validation =
                ProjectIndexWarmValidator.validate(
                        expected, validatedCurrent, file -> {
                            telemetry.addRereadHashedPaths(1);
                            return inputs.hashAll(
                                    List.of(file), cancelled)
                                            .getFirst()
                                            .contentSha256();
                        }, cancelled);
        if (!validation.hit()) {
            return false;
        }
        List<CurrentFile> after = inputs.inspect();
        telemetry.observeEnumerationPass(after.size());
        inputs.verifyIdentity();
        return current.equals(after);
    }

    /**
     * The stamps the working tree is expected to carry once the batch lands:
     * every indexed file, with the batch's stamp in place of the one it
     * replaced, plus the stamp of every file the batch introduces. The added
     * stamps are appended in the batch's own order, so two runs over one batch
     * describe the same expectation.
     */
    private static List<ProjectFileStamp> expectedStamps(
            List<ProjectFileStamp> packedStamps,
            Set<IndexPathRef> changed, Set<IndexPathRef> addedPaths,
            Map<IndexPathRef, ProjectFileStamp> expectedChanged) {
        var stamps = new ArrayList<ProjectFileStamp>(
                packedStamps.size() + addedPaths.size());
        for (ProjectFileStamp stamp : packedStamps) {
            stamps.add(changed.contains(stamp.path())
                    ? expectedChanged.get(stamp.path()) : stamp);
        }
        for (var entry : expectedChanged.entrySet()) {
            if (addedPaths.contains(entry.getKey())) {
                stamps.add(entry.getValue());
            }
        }
        return List.copyOf(stamps);
    }

    private static boolean hasUniqueDefinitionOwnership(
            IncrementalProjectReferenceIndex.AnalysisLease packed,
            IndexPathRef changedPath,
            FileContribution replacement) {
        for (var definition : replacement.definitions()) {
            try {
                ReferenceMatchKey key =
                        ReferenceMatchKey.from(definition.object());
                var subject = new ProjectIndexDefinitionSubject(
                        key.family(), key.exactType(),
                        key.schema(), key.table());
                if (!packed.definitions(subject, changedPath)
                        .isEmpty()) {
                    return false;
                }
            } catch (IllegalArgumentException ex) {
                return false;
            }
        }
        return true;
    }

    private PreparedUpdate publication(ProjectReferencesStorage updated, List<Object> errors,
            Collection<IFile> markerFiles, long epoch) {
        return new PreparedUpdate(updated, () -> {
            clearMarkers(markerFiles);
            markErrors(errors);
        }, epoch);
    }

    private IndexSnapshot indexSnapshot() {
        synchronized (stateLock) {
            return new IndexSnapshot(configurationEpoch, referencesStorage);
        }
    }

    private static void clearMarkers(Collection<IFile> files) {
        for (IFile file : files) {
            try {
                file.deleteMarkers(MARKER.ERROR, false, IResource.DEPTH_ZERO);
            } catch (CoreException ex) {
                Log.log(ex);
            }
        }
    }

    private static void clearMarkers(IProject proj) {
        try {
            proj.deleteMarkers(MARKER.ERROR, false, IResource.DEPTH_INFINITE);
        } catch (CoreException ex) {
            Log.log(ex);
        }
    }

    private static void markErrors(List<Object> errors) {
        for (Object error : errors) {
            if (error instanceof AntlrError antlrError) {
                IFile file = FileUtilsUi.getFileForLocation(antlrError);
                if (file != null) {
                    addMarker(file, antlrError);
                }
            }
        }
    }

    private static void addMarker(IFile file, AntlrError antlrError) {
        try {
            IMarker marker = file.createMarker(MARKER.ERROR);
            int line = antlrError.getLineNumber();
            marker.setAttribute(IMarker.LINE_NUMBER, line);
            marker.setAttribute(IMarker.SEVERITY, IMarker.SEVERITY_ERROR);
            marker.setAttribute(IMarker.MESSAGE, antlrError.getMsg());
            marker.setAttribute(IMarker.BOOKMARK, antlrError.getMsg());
            if (antlrError.getErrorType() == ErrorTypes.MISPLACEERROR) {
                marker.setAttribute(MARKER.ERROR_TYPE, MARKER.MISPLACE_ERROR);
            }
            int start = antlrError.getStart();
            int stop = antlrError.getStop();
            if (start == -1 || stop == -1) {
                IDocumentProvider provider = new TextFileDocumentProvider();
                provider.connect(file);
                IDocument doc = provider.getDocument(file);
                int lineOffset = doc.getLineOffset(line - 1);
                start = lineOffset + antlrError.getCharPositionInLine();
                stop = start;
            }
            marker.setAttribute(IMarker.CHAR_START, start);
            marker.setAttribute(IMarker.CHAR_END, stop + 1);
        } catch (BadLocationException | CoreException ex) {
            Log.log(ex);
        }
    }

    public void removeResFromRefs(IResource res) {
        String path = res.getLocation().toOSString();
        boolean rebuild = false;
        RetiredIndex retired = null;
        synchronized (publicationEventLock) {
            synchronized (stateLock) {
                if (referencesStorage
                        instanceof ProjectReferencesStorage) {
                    ProjectReferencesStorage updated =
                            referencesStorage.mutableCopy();
                    updated.remove(path);
                    retired = swapStorageLocked(updated);
                } else {
                    // Packed indexes stay immutable; rebuild instead of
                    // eagerly materializing the whole index for one resource.
                    rebuild = true;
                }
            }
            if (retired != null) {
                closeRetiredOutsideLock(retired.storage(),
                        retired.resource());
            }
        }
        if (rebuild) {
            startBuildJob(res.getProject());
        }
    }

    public void fillRefsFromInputStream(InputStream input, String fileName, IProgressMonitor monitor, IProject project,
            DatabaseType dbType)
            throws InterruptedException, IOException {
        long epoch = currentConfigurationEpoch();
        var provider = dbType.getDatabaseProvider();
        var settings = new UISettings(project);
        settings.setMonitor(new UIMonitor(monitor));
        var loader = provider.getDumpLoader(() -> input, fileName, settings);
        loader.setMode(ParserListenerMode.REF);
        LoadedReferences loaded = loadReferenceData(loader, monitor, false);
        publishLoadedReferences(loaded.storage(), epoch);
    }

    void publishLoadedReferences(ProjectReferenceIndex candidate,
            long epoch) {
        RetiredIndex retired = null;
        boolean accepted = false;
        synchronized (publicationEventLock) {
            synchronized (stateLock) {
                if (isCurrent(epoch)) {
                    retired = swapStorageLocked(candidate);
                    accepted = true;
                }
            }
            if (accepted) {
                closeRetiredOutsideLock(retired.storage(),
                        retired.resource());
                notifyListeners();
            }
        }
        if (!accepted) {
            closeRetiredOutsideLock(candidate, null);
        }
    }

    public Stream<MetaStatement> getDefinitionsForObj(ObjectLocation obj) {
        return queryReferenceIndex(
                storage -> List.copyOf(storage.definitionsMatching(obj)),
                List.<MetaStatement>of()).stream();
    }

    public Stream<ObjectLocation> getReferencesForObj(ObjectLocation obj) {
        return queryReferenceIndex(
                storage -> List.copyOf(storage.referencesMatching(obj)),
                List.<ObjectLocation>of()).stream();
    }

    /**
     * Shortest text {@link #getCompletionCandidates(String)} answers, re-exported
     * for the editor: the completion has to know where its own silence begins,
     * and the number belongs to the index that has to honour it.
     */
    public static final int MIN_COMPLETION_PREFIX_LENGTH =
            ProjectReferenceIndex.MIN_COMPLETION_PREFIX_LENGTH;

    /**
     * Definitions whose name holds the given text, ignoring case.
     *
     * @param text text to look for
     * @return matching definitions, empty when {@code text} is shorter than
     *         {@link #MIN_COMPLETION_PREFIX_LENGTH}
     */
    public Stream<MetaStatement> getCompletionCandidates(String text) {
        return queryReferenceIndex(
                storage -> List.copyOf(
                        storage.completionCandidates(text)),
                List.<MetaStatement>of()).stream();
    }

    public Set<ObjectLocation> getObjsForEditor(IEditorInput in) {
        String path = getPathFromInput(in);
        return path == null ? Collections.emptySet() : getObjsForPath(path);
    }

    public Set<ObjectLocation> getObjsForPath(String pathToFile) {
        return queryReferenceIndex(
                storage -> Set.copyOf(
                        storage.referencesForPath(pathToFile)),
                Set.of());
    }

    public List<MetaStatement> getDefsForPath(String pathToFile) {
        return queryReferenceIndex(
                storage -> List.copyOf(
                        storage.definitionsForPath(pathToFile)),
                List.of());
    }

    private <T> T queryReferenceIndex(
            Function<ProjectReferenceIndex, T> query, T empty) {
        PackedProjectReferenceIndex.ProjectIndexAccessException failure;
        ProjectReferenceIndex failedStorage;
        ProjectIndexRevision recoveryRevision = null;
        synchronized (stateLock) {
            try {
                return query.apply(referencesStorage);
            } catch (PackedProjectReferenceIndex.ProjectIndexAccessException ex) {
                failure = ex;
                failedStorage = referencesStorage;
                if (referencesStorage
                        instanceof PackedProjectReferenceIndex packed) {
                    try {
                        recoveryRevision = packed.revision();
                    } catch (RuntimeException suppressed) {
                        ex.addSuppressed(suppressed);
                    }
                }
            }
        }
        Log.log(Log.LOG_DEBUG, Messages.PgDbParser_deserialize_error,
                failure);
        boolean recover = false;
        RetiredIndex retired = null;
        synchronized (publicationEventLock) {
            synchronized (stateLock) {
                if (referencesStorage == failedStorage) {
                    recover = !packedIndexRecoveryPending;
                    configurationEpoch++;
                    retired = swapStorageLocked(
                            new ProjectReferencesStorage());
                    packedIndexRecoveryPending = true;
                }
            }
            if (retired != null) {
                closeRetiredOutsideLock(retired.storage(),
                        retired.resource());
            }
            if (recover) {
                cancelCurrentLoad();
                notifyListeners();
            }
        }
        if (recover) {
            try {
                packedIndexRecovery.accept(recoveryRevision);
            } catch (RuntimeException ex) {
                Log.log(ex);
            }
        }
        return empty;
    }

    public Stream<MetaStatement> getAllObjDefinitions() {
        return queryReferenceIndex(
                storage -> storage.allDefinitions().toList(),
                List.<MetaStatement>of()).stream();
    }

    public Stream<ObjectLocation> getAllObjReferences() {
        return queryReferenceIndex(
                storage -> storage.allReferences().toList(),
                List.<ObjectLocation>of()).stream();
    }

    public void clear() {
        RetiredIndex retired;
        synchronized (publicationEventLock) {
            synchronized (stateLock) {
                retired = swapStorageLocked(
                        new ProjectReferencesStorage());
            }
            closeRetiredOutsideLock(retired.storage(),
                    retired.resource());
        }
    }

    public void clearAndNotify() {
        RetiredIndex retired;
        synchronized (publicationEventLock) {
            synchronized (stateLock) {
                retired = swapStorageLocked(
                        new ProjectReferencesStorage());
            }
            closeRetiredOutsideLock(retired.storage(),
                    retired.resource());
            notifyListeners();
        }
    }

    void replaceReferenceIndexForTests(ProjectReferenceIndex replacement) {
        RetiredIndex retired;
        synchronized (publicationEventLock) {
            synchronized (stateLock) {
                retired = swapStorageLocked(replacement);
            }
            closeRetiredOutsideLock(retired.storage(),
                    retired.resource());
        }
    }

    IncrementalProjectReferenceIndex incrementalIndexForTests() {
        synchronized (stateLock) {
            if (referencesStorage
                    instanceof IncrementalProjectReferenceIndex incremental) {
                return incremental;
            }
            throw new IllegalStateException(
                    "The parser has no incremental project index"); //$NON-NLS-1$
        }
    }

    PreparedUpdate prepareReferenceIndexForTests(
            ProjectReferenceIndex replacement) {
        return prepareReferenceIndexForTests(replacement,
                () -> { });
    }

    PreparedUpdate prepareReferenceIndexForTests(
            ProjectReferenceIndex replacement,
            Runnable markerPublication) {
        return new PreparedUpdate(replacement, markerPublication,
                currentConfigurationEpoch());
    }

    /**
     * Transfers ownership of a closeable backing view to this parser. The
     * current implementation uses an in-memory storage; the hook allows the
     * packed project-index reader to be closed by the same lifecycle gate.
     */
    public void attachProjectIndexResource(AutoCloseable resource) {
        AutoCloseable previous;
        synchronized (publicationEventLock) {
            synchronized (stateLock) {
                previous = projectIndexResource;
                projectIndexResource = resource;
            }
            if (previous != resource) {
                closeResourceOutsideLock(previous);
            }
        }
    }

    void invalidateLiveProjectIndexConfiguration() {
        invalidateLiveProjectIndexConfiguration(() -> { });
    }

    void invalidateLiveProjectIndexConfiguration(
            Runnable persistedInvalidation) {
        invalidateLiveProjectIndexConfigurationWithRevision(
                ignored -> persistedInvalidation.run());
    }

    private void invalidateLiveProjectIndexConfigurationWithRevision(
            Consumer<ProjectIndexRevision> persistedInvalidation) {
        ProjectIndexRevision retiredRevision = null;
        synchronized (publicationEventLock) {
            RetiredIndex retired;
            synchronized (stateLock) {
                if (referencesStorage
                        instanceof PackedProjectReferenceIndex packed) {
                    try {
                        retiredRevision = packed.revision();
                    } catch (RuntimeException ex) {
                        Log.log(Log.LOG_DEBUG,
                                Messages.PgDbParser_deserialize_error,
                                ex);
                    }
                }
                configurationEpoch++;
                retired = swapStorageLocked(
                        new ProjectReferencesStorage());
            }
            closeRetiredOutsideLock(retired.storage(),
                    retired.resource());
            cancelCurrentLoad();
            notifyListeners();
        }
        persistedInvalidation.accept(retiredRevision);
    }

    private static void closeRetiredOutsideLock(
            ProjectReferenceIndex retired,
            AutoCloseable retiredResource) {
        if (retiredResource != null) {
            try {
                retiredResource.close();
            } catch (Exception ex) {
                Log.log(ex);
            }
        }
        if (retired != null) {
            try {
                retired.close();
            } catch (RuntimeException ex) {
                Log.log(ex);
            }
        }
    }

    private boolean samePersistentRevisionLocked(
            ProjectIndexBuildCoordinator.Publication publication) {
        return publication.revision() != null
                && publication.storage()
                        instanceof PackedProjectReferenceIndex
                && referencesStorage
                        instanceof PackedProjectReferenceIndex current
                && publication.revision().equals(
                        current.revision());
    }

    private static void closeResourceOutsideLock(
            AutoCloseable resource) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception ex) {
            Log.log(ex);
        }
    }

    private RetiredIndex swapStorageLocked(
            ProjectReferenceIndex replacement) {
        projectIndexContinuity.invalidate();
        ProjectReferenceIndex previous = referencesStorage;
        AutoCloseable previousResource = projectIndexResource;
        referencesStorage = Objects.requireNonNull(
                replacement, "replacement"); //$NON-NLS-1$
        projectIndexResource = null;
        packedIndexRecoveryPending = false;
        if (previous != replacement) {
            publicationSequence++;
        }
        return new RetiredIndex(
                previous == replacement ? null : previous,
                previousResource);
    }

    public void notifyListeners() {
        for (Listener e : listeners) {
            try {
                e.handleEvent(new Event());
            } catch (RuntimeException ex) {
                Log.log(ex);
            }
        }
    }

    @Override
    public void resourceChanged(IResourceChangeEvent event) {
        int type = event.getType();
        if (IResourceChangeEvent.POST_CHANGE == type) {
            observeProjectContentDelta(event.getDelta());
            return;
        }
        if ((IResourceChangeEvent.PRE_CLOSE == type || IResourceChangeEvent.PRE_DELETE == type)
                && PROJ_PARSERS.remove(event.getResource(), this)) {
            ResourcesPlugin.getWorkspace().removeResourceChangeListener(this);
            IProject project = event.getResource().getProject();
            if (IResourceChangeEvent.PRE_DELETE == type) {
                invalidateLiveProjectIndexConfiguration(
                        () -> invalidatePersistedProjectIndex(project));
            } else {
                invalidateLiveProjectIndexConfiguration();
            }
            CONFIGURATION_INVALIDATOR.forget(project);
        }
    }

    private void observeProjectContentDelta(IResourceDelta root) {
        if (observedProject == null) {
            return;
        }

        boolean changed;
        try {
            changed = ProjectInputDeltaPolicy.affectsComparison(root,
                    observedProject);
        } catch (CoreException | RuntimeException ex) {
            Log.log(ex);
            changed = true;
        }

        if (changed) {
            synchronized (stateLock) {
                contentEpoch++;
            }
        }
    }

    public static String getPathFromInput(IEditorInput in) {
        IResource res = ResourceUtil.getResource(in);
        if (res != null && ProjectUtils.isPgCodeKeeperProject(res.getProject())) {
            return res.getLocation().toOSString();
        }

        if (in.exists() && in instanceof IURIEditorInput uriInput) {
            return Paths.get(uriInput.getURI()).toString();
        }

        return null;
    }

    public static PgDbParser getParser(IResource res) {
        return getParserForBuilder(res.getProject(), null);
    }

    static PgDbParser getParserForComparison(
            IProject project, IProgressMonitor monitor)
            throws InterruptedException {
        PgDbParser parser = getOrCreateParser(project, false);
        ComparisonIndexState state;
        try {
            state = parser.restorePersistedProjectIndex(
                    project, monitor);
        } catch (InterruptedException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            startBuildJob(project);
            throw ex;
        }
        if (state != ComparisonIndexState.LIVE) {
            startBuildJob(project);
        }
        return parser;
    }

    /**
     * @param buildType
     *            single-element array containing the requested build type
     */
    public static PgDbParser getParserForBuilder(IProject proj, int[] buildType) {
        return getOrCreateParser(proj, buildType == null);
    }

    private static PgDbParser getOrCreateParser(
            IProject proj, boolean scheduleBuild) {
        PgDbParser pnew = new PgDbParser(proj);
        PgDbParser p = PROJ_PARSERS.putIfAbsent(proj, pnew);
        if (p == null) {
            p = pnew;
            // prepare newly created parser
            ResourcesPlugin.getWorkspace().addResourceChangeListener(p,
                    IResourceChangeEvent.PRE_CLOSE
                    | IResourceChangeEvent.PRE_DELETE
                    | IResourceChangeEvent.POST_CHANGE);

            if (scheduleBuild) {
                // not a builder call, start builder
                startBuildJob(proj);
            }
        }
        return p;
    }

    private static void startBuildJob(IProject proj) {
        CONFIGURATION_INVALIDATOR.scheduleBuild(proj,
                monitor -> buildProjectIndex(proj, monitor));
    }

    private static void recoverPackedProjectIndex(IProject project,
            PgDbParser parser,
            ProjectIndexRevision revision) {
        if (!project.isAccessible() || PROJ_PARSERS.get(project) != parser) {
            return;
        }
        invalidatePersistedProjectIndex(project, revision);
        if (project.isAccessible() && PROJ_PARSERS.get(project) == parser) {
            startBuildJob(project);
        }
    }

    static void removeProject(IResource res) {
        IProject proj = res.getProject();
        PgDbParser parser = PROJ_PARSERS.remove(proj);
        if (parser != null) {
            ResourcesPlugin.getWorkspace().removeResourceChangeListener(parser);
            parser.invalidateLiveProjectIndexConfiguration(
                    () -> invalidatePersistedProjectIndex(proj));
        } else {
            invalidatePersistedProjectIndex(proj);
        }
        CONFIGURATION_INVALIDATOR.forget(proj);
    }

    public static void clean(String name) {
        try {
            Path path = getPathToObject(name);
            Files.deleteIfExists(path);
        } catch (IOException e) {
            Log.log(Log.LOG_DEBUG, Messages.PgDbParser_clean_parser_error, e);
        }
    }

    public static void clean(IProject project) {
        PgDbParser parser = PROJ_PARSERS.get(project);
        CONFIGURATION_INVALIDATOR.forget(project);
        retireLiveProjectIndex(parser);
        cleanPersistedProjectIndex(project);
    }

    private static void cleanPersistedProjectIndex(IProject project) {
        Path directory = null;
        try {
            directory = ProjectIndexState.directory(
                    getStateRoot(), ProjectUtils.getPath(project));
        } catch (IOException | RuntimeException ex) {
            Log.log(Log.LOG_DEBUG,
                    Messages.PgDbParser_clean_parser_error, ex);
        }
        cleanProjectIndexStore(directory);
        clean(project.getName());
    }

    static void retireLiveProjectIndex(PgDbParser parser) {
        if (parser != null) {
            parser.invalidateLiveProjectIndexConfiguration();
        }
    }

    static void cleanProjectIndexStore(Path directory) {
        if (directory == null) {
            return;
        }
        try {
            new ProjectIndexStore(directory).clean();
        } catch (IOException | RuntimeException ex) {
            Log.log(Log.LOG_DEBUG,
                    Messages.PgDbParser_clean_parser_error, ex);
        }
    }

    public static void cleanAll() {
        List<Map.Entry<IProject, PgDbParser>> liveParsers =
                List.copyOf(PROJ_PARSERS.entrySet());
        for (var entry : liveParsers) {
            IProject project = entry.getKey();
            PgDbParser parser = entry.getValue();
            CONFIGURATION_INVALIDATOR.forget(project);
            retireLiveProjectIndex(parser);
        }
        cleanAllProjectIndexStores(
                getStateRoot().resolve("projects-v2")); //$NON-NLS-1$
        try {
            FileUtils.deleteRecursive(getPathToFolder());
        } catch (IOException | RuntimeException e) {
            Log.log(Log.LOG_DEBUG, Messages.PgDbParser_clean_parser_error, e);
        }
        for (var entry : liveParsers) {
            IProject project = entry.getKey();
            if (PROJ_PARSERS.get(project) == entry.getValue()
                    && project.isAccessible()
                    && ProjectUtils.isPgCodeKeeperProject(project)) {
                startBuildJob(project);
            }
        }
    }

    static void cleanAllProjectIndexStores(Path root) {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (DirectoryStream<Path> directories =
                Files.newDirectoryStream(root)) {
            for (Path directory : directories) {
                if (Files.isDirectory(directory)) {
                    cleanProjectIndexStore(directory);
                    try {
                        Files.deleteIfExists(directory);
                    } catch (IOException | RuntimeException ex) {
                        Log.log(Log.LOG_DEBUG,
                                Messages.PgDbParser_clean_parser_error,
                                ex);
                    }
                }
            }
        } catch (IOException | RuntimeException ex) {
            Log.log(Log.LOG_DEBUG,
                    Messages.PgDbParser_clean_parser_error, ex);
        }
        try {
            Files.deleteIfExists(root);
        } catch (IOException | RuntimeException ex) {
            Log.log(Log.LOG_DEBUG,
                    Messages.PgDbParser_clean_parser_error, ex);
        }
    }

    /**
     * Atomically invalidates the live and persisted background project index,
     * then requests a full rebuild for this project. Database comparison
     * loaders are independent and are never touched.
     */
    public static void invalidateProjectIndexConfiguration(IProject project) {
        invalidateProjectIndexConfiguration(project,
                ProjectIndexConfigurationDiagnostics.ORIGIN_UNKNOWN);
    }

    /**
     * Atomically invalidates the live and persisted background project index,
     * then requests a full rebuild for this project. Database comparison
     * loaders are independent and are never touched.
     *
     * @param origin short tag naming the call site, for the diagnostic line
     *               that reports whether the fingerprint guard held
     */
    public static void invalidateProjectIndexConfiguration(IProject project,
            String origin) {
        if (project == null) {
            return;
        }
        String fingerprint = settledConfigurationFingerprint(project);
        PgDbParser parser = PROJ_PARSERS.get(project);
        boolean invalidated = CONFIGURATION_INVALIDATOR.invalidate(
                project, fingerprint,
                parser == null
                        ? Runnable::run
                        : ignored -> parser
                                .invalidateLiveProjectIndexConfigurationWithRevision(
                                        revision ->
                                                invalidatePersistedProjectIndex(
                                                        project,
                                                        revision)),
                parser == null
                        // Without a live packed view there is no exact
                        // publication receipt. Leave v2 fail-closed for the
                        // next identity check instead of racing a new build.
                        ? () -> clean(project.getName())
                        : () -> { },
                monitor -> buildProjectIndex(project, monitor));
        ProjectIndexConfigurationDiagnostics.INSTANCE.publishInvalidation(
                invalidated,
                parser == null ? ParserPresence.ABSENT : ParserPresence.LIVE,
                origin);
    }

    public static void observeProjectIndexConfiguration(IProject project) {
        if (project != null) {
            CONFIGURATION_INVALIDATOR.observe(project,
                    projectIndexConfigurationFingerprint(project));
        }
    }

    public static GlobalSettings getGlobalProjectIndexConfiguration() {
        var store = Activator.getDefault().getPreferenceStore();
        return new GlobalSettings(store.getBoolean(PREF.NO_PRIVILEGES),
                store.getBoolean(PREF.ENABLE_BODY_DEPENDENCIES),
                store.getBoolean(PREF.PROJECT_INDEX_INCREMENTAL_ADDED_FILES),
                store.getBoolean(PREF.PROJECT_UPDATED_FROM_DATABASE_ONLY),
                store.getString(PREF.PROJECT_INDEX_EXCLUDED_SCHEMAS));
    }

    /**
     * Invalidates every project whose effective index settings changed.
     * Project fields that are absent continue to inherit their corresponding
     * global value. Which projects are considered at all is
     * {@link ProjectIndexGlobalInvalidationPolicy}'s to say, and it stopped
     * saying it by database type when the index stopped being
     * PostgreSQL-only.
     */
    public static void invalidateGlobalProjectIndexConfiguration(
            GlobalSettings before, GlobalSettings after) {
        if (Objects.equals(before, after)) {
            return;
        }
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot()
                .getProjects()) {
            // The type the policy accepted, rather than the type it must have
            // accepted for control to be here: the two are the same value and
            // only one of them says so.
            DatabaseType databaseType = ProjectIndexGlobalInvalidationPolicy
                    .acceptedDatabaseType(project);
            if (databaseType == null) {
                continue;
            }
            var prefs = projectPreferences(project);
            boolean override = prefs.getBoolean(
                    PROJ_PREF.ENABLE_PROJ_PREF_ROOT, false);
            ProjectOverrides projectOverrides = projectOverrides(prefs);
            String beforeFingerprint = configurationFingerprint(
                    ProjectIndexConfiguration.resolve(databaseType,
                            override, before, projectOverrides));
            String afterFingerprint = configurationFingerprint(
                    ProjectIndexConfiguration.resolve(databaseType,
                            override, after, projectOverrides));
            if (!beforeFingerprint.equals(afterFingerprint)) {
                invalidateProjectIndexConfiguration(project,
                        ProjectIndexConfigurationDiagnostics
                                .ORIGIN_GLOBAL_PREFERENCES);
            }
        }
    }

    private static void invalidatePersistedProjectIndex(IProject project) {
        cleanPersistedProjectIndex(project);
    }

    private static void invalidatePersistedProjectIndex(
            IProject project, ProjectIndexRevision revision) {
        if (revision != null) {
            try {
                Path directory = ProjectIndexState.directory(
                        getStateRoot(), ProjectUtils.getPath(project));
                new ProjectIndexStore(directory)
                        .invalidateCurrent(revision);
            } catch (IOException | RuntimeException ex) {
                Log.log(Log.LOG_DEBUG,
                        Messages.PgDbParser_clean_parser_error, ex);
            }
        }
        clean(project.getName());
    }

    /**
     * Asks whether work done under {@code build} still describes the project
     * the workbench has settled on.
     *
     * <p>This is the same question a finished build asks before it publishes,
     * offered to callers that never get that far. A cycle whose delta
     * classified as "nothing to do" is the one that needs it: it produces no
     * build, so it reaches no publication, so nothing else in this class will
     * ever ask on its behalf - and if the configuration it classified by was
     * a passing state of the preference node, the change it dropped is
     * dropped for good.</p>
     *
     * <p>Fail-closed on both sides, exactly as the publication guard is: a
     * settled configuration that cannot be read counts as moved.</p>
     *
     * @param project project the work was classified or built for
     * @param build   the configuration it was classified or built by, or null
     *                when there was none, which is never refused
     * @return why the work must not be trusted, or empty when it may
     */
    public static Optional<ConfigurationGuard>
            projectIndexConfigurationRefusal(IProject project,
                    ProjectIndexBuildConfiguration build) {
        if (build == null) {
            return Optional.empty();
        }
        return ProjectIndexPublicationGuard.refusal(
                configurationFingerprint(build.configuration()),
                settledConfigurationFingerprint(project));
    }

    public static String projectIndexConfigurationFingerprint(
            IProject project) {
        return configurationFingerprint(
                effectiveProjectIndexConfiguration(project));
    }

    /**
     * Reads the fingerprint of the configuration as it stands now, and answers
     * a value no stored fingerprint can match when the read fails. A caller
     * that could not see the configuration must not conclude that it did not
     * change: the index is worth less than a rebuild.
     */
    private static String settledConfigurationFingerprint(IProject project) {
        try {
            return projectIndexConfigurationFingerprint(project);
        } catch (RuntimeException ex) {
            Log.log(ex);
            return ProjectIndexPublicationGuard.UNREADABLE_PREFIX
                    + UNREADABLE_CONFIGURATIONS.incrementAndGet();
        }
    }

    private static ProjectIndexConfiguration
            effectiveProjectIndexConfiguration(IProject project) {
        var prefs = projectPreferences(project);
        boolean projectPreferencesEnabled =
                prefs.getBoolean(PROJ_PREF.ENABLE_PROJ_PREF_ROOT, false);
        var global = getGlobalProjectIndexConfiguration();
        var overrides = projectOverrides(prefs);
        var configuration = ProjectIndexConfiguration.resolve(
                ProjectUtils.getDatabaseType(project),
                projectPreferencesEnabled, global, overrides);
        ProjectIndexConfigurationDiagnostics.INSTANCE.publishConfigurationRead(
                projectPreferencesEnabled, global.excludedSchemas(),
                overrides.excludedSchemas(), configuration.excludedSchemas(),
                "effective_configuration"); //$NON-NLS-1$
        return configuration;
    }

    public static ProjectIndexConfiguration
            getEffectiveProjectIndexConfiguration(IProject project) {
        return effectiveProjectIndexConfiguration(
                Objects.requireNonNull(project, "project")); //$NON-NLS-1$
    }

    private static String configurationFingerprint(
            ProjectIndexConfiguration configuration) {
        try {
            return configuration.digest();
        } catch (IllegalArgumentException ex) {
            return "invalid:" + configuration; //$NON-NLS-1$
        }
    }

    private static org.eclipse.core.runtime.preferences.IEclipsePreferences
            projectPreferences(IProject project) {
        return new ProjectScope(project)
                .getNode(ru.taximaxim.codekeeper.ui.UIConsts.PLUGIN_ID.THIS);
    }

    private static ProjectOverrides projectOverrides(
            org.eclipse.core.runtime.preferences.IEclipsePreferences prefs) {
        return new ProjectOverrides(
                optionalBoolean(prefs, PREF.NO_PRIVILEGES),
                optionalBoolean(prefs, PREF.ENABLE_BODY_DEPENDENCIES),
                optionalBoolean(prefs, PREF.PROJECT_INDEX_INCREMENTAL_ADDED_FILES),
                optionalBoolean(prefs, PREF.PROJECT_UPDATED_FROM_DATABASE_ONLY),
                prefs.get(PREF.PROJECT_INDEX_EXCLUDED_SCHEMAS, null));
    }

    private static Boolean optionalBoolean(
            org.eclipse.core.runtime.preferences.IEclipsePreferences prefs,
            String key) {
        return prefs.get(key, null) == null
                ? null
                : prefs.getBoolean(key, false);
    }

    private static ProjectIndexConfigurationInvalidator.ScheduledBuild
            scheduleProjectIndexBuild(Object project,
                    ProjectIndexConfigurationInvalidator.BuildTask task) {
        IProject eclipseProject = (IProject) project;
        WorkspaceJob job = new WorkspaceJob(projectIndexBuildJobName(
                eclipseProject.getName())) {

            @Override
            public IStatus runInWorkspace(IProgressMonitor monitor) {
                try {
                    task.run(monitor);
                    return monitor.isCanceled()
                            ? Status.CANCEL_STATUS
                            : Status.OK_STATUS;
                } catch (CoreException ex) {
                    Log.log(ex);
                    return ex.getStatus();
                } catch (RuntimeException ex) {
                    Log.log(ex);
                    return new Status(IStatus.ERROR,
                            ru.taximaxim.codekeeper.ui.UIConsts.PLUGIN_ID.THIS,
                            Messages.PgDbParser_error_loading_db, ex);
                }
            }
        };
        job.setRule(projectIndexBuildRule());
        makeProjectIndexBuildVisible(job);
        job.schedule(CONFIGURATION_REBUILD_DELAY_MILLIS);
        return job::cancel;
    }

    /**
     * Names the job that reindexes a project whose effective index
     * configuration changed. The label reaches a user, and a workbench holds
     * several projects while only one of them is being rebuilt, so it is
     * localized and carries the project name.
     *
     * @param projectName name of the project being reindexed
     */
    static String projectIndexBuildJobName(String projectName) {
        return Messages.PgDbParser_project_index_rebuild_job
                .formatted(projectName);
    }

    /**
     * Keeps the reindex job displayable. Stated rather than left to the
     * default, because the opposite was stated here before and cost the user
     * everything this job does.
     * <p>
     * The platform decides displayability by {@code isSystem()} alone: a
     * system job is never shown, on any surface, however long it runs. The
     * only task this path ever schedules is a full rebuild of the whole
     * project -- the better part of a minute on a large one -- and it holds
     * the workspace root rule throughout. Nothing short is hidden by leaving
     * it visible either: the job is scheduled only when the configuration
     * fingerprint actually changed, and rapid changes are coalesced into a
     * single delayed build. A minute of silence reads as an idle workbench:
     * the user keeps editing, the running build loses its race against the
     * refresh and throws its work away.
     */
    static void makeProjectIndexBuildVisible(Job job) {
        job.setSystem(false);
    }

    static ISchedulingRule projectIndexBuildRule() {
        return ResourcesPlugin.getWorkspace().getRoot();
    }

    private static void buildProjectIndex(IProject project,
            IProgressMonitor monitor) throws CoreException {
        if (monitor.isCanceled() || !project.isAccessible()
                || !ProjectUtils.isPgCodeKeeperProject(project)) {
            return;
        }
        project.build(IncrementalProjectBuilder.FULL_BUILD, monitor);
    }

}
