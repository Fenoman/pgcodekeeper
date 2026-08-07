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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

import org.pgcodekeeper.core.database.api.loader.ProjectInputFingerprint;

import ru.taximaxim.codekeeper.ui.projectindex.ProjectFileStamp;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexData;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexFiles;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexFormatException;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexIdentity;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexOpenResult.Status;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexPathResolver;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexPersistenceException;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexRevision;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexSnapshotFactory;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexStore;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetry;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetry.BypassReason;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetry.Mode;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetry.PersistenceReason;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetry.PersistenceStatus;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetry.Phase;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetry.PhaseTimer;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexWarmValidator;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexWarmValidator.CurrentFile;

final class ProjectIndexBuildCoordinator {

    static final class StaleBuildException
            extends ProjectIndexBuildSupersededException {

        private static final long serialVersionUID = -7194377822925721641L;

        StaleBuildException(String message) {
            super(message);
        }

        StaleBuildException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    interface InputSource {

        List<CurrentFile> inspect() throws IOException, InterruptedException;

        List<ProjectFileStamp> hashAll(List<CurrentFile> files,
                BooleanSupplier cancelled)
                throws IOException, InterruptedException;

        ProjectIndexPathResolver resolver();

        default void verifyIdentity()
                throws IOException, InterruptedException {
            // Most callers have an immutable request. Eclipse overrides this
            // to re-read effective preferences and configuration files.
        }
    }

    private static final class MeteredInputSource implements InputSource {

        private final InputSource delegate;
        private final ProjectIndexTelemetry.Run telemetry;

        private MeteredInputSource(InputSource delegate,
                ProjectIndexTelemetry.Run telemetry) {
            this.delegate = delegate;
            this.telemetry = telemetry;
        }

        @Override
        public List<CurrentFile> inspect()
                throws IOException, InterruptedException {
            List<CurrentFile> result;
            try (var ignored = telemetry.phase(Phase.INSPECT)) {
                result = delegate.inspect();
            }
            telemetry.observeEnumerationPass(result.size());
            return result;
        }

        @Override
        public List<ProjectFileStamp> hashAll(List<CurrentFile> files,
                BooleanSupplier cancelled)
                throws IOException, InterruptedException {
            List<ProjectFileStamp> result =
                    delegate.hashAll(files, cancelled);
            telemetry.addRereadHashedPaths(result.size());
            return result;
        }

        @Override
        public ProjectIndexPathResolver resolver() {
            return delegate.resolver();
        }

        @Override
        public void verifyIdentity()
                throws IOException, InterruptedException {
            delegate.verifyIdentity();
        }
    }

    private record PersistenceFailure(Throwable cause,
            PersistenceReason reason) {

        private PersistenceFailure {
            Objects.requireNonNull(cause, "cause");
            Objects.requireNonNull(reason, "reason");
            if (reason == PersistenceReason.NONE) {
                throw new IllegalArgumentException(
                        "Persistence failure must have a reason");
            }
        }
    }

    @FunctionalInterface
    interface FullBuild {
        BuildResult load() throws IOException, InterruptedException;
    }

    record BuildResult(ProjectReferencesStorage storage,
            List<Object> errors,
            List<ProjectInputFingerprint> inputFingerprints) {

        BuildResult(ProjectReferencesStorage storage,
                List<Object> errors) {
            this(storage, errors, null);
        }

        BuildResult {
            Objects.requireNonNull(storage, "storage"); //$NON-NLS-1$
            errors = List.copyOf(errors);
            inputFingerprints = inputFingerprints == null
                    ? null : List.copyOf(inputFingerprints);
        }
    }

    record Request(Path storeDirectory, Path legacyState, Path projectRoot,
            Path libraryRoot, ProjectIndexIdentity identity, long generation) {

        Request {
            storeDirectory = normalize(storeDirectory, "storeDirectory"); //$NON-NLS-1$
            legacyState = normalize(legacyState, "legacyState"); //$NON-NLS-1$
            projectRoot = normalize(projectRoot, "projectRoot"); //$NON-NLS-1$
            libraryRoot = normalize(libraryRoot, "libraryRoot"); //$NON-NLS-1$
            Objects.requireNonNull(identity, "identity"); //$NON-NLS-1$
            if (generation < 0) {
                throw new IllegalArgumentException(
                        "Project index generation must not be negative"); //$NON-NLS-1$
            }
        }

        private static Path normalize(Path value, String name) {
            return Objects.requireNonNull(value, name)
                    .toAbsolutePath().normalize();
        }
    }

    static final class Publication implements AutoCloseable {

        private enum Decision {
            UNDECIDED,
            TRANSFERRED,
            RETAINED,
            REJECTED
        }

        private final ProjectReferenceIndex storage;
        private final ProjectIndexRevision revision;
        private final boolean revisionWrittenByThisBuild;
        private final Request request;
        private final Throwable persistenceFailure;
        private final PersistenceStatus acceptedPersistenceStatus;
        private final PersistenceReason acceptedPersistenceReason;
        private ProjectIndexTelemetry.Run telemetry;
        private Decision decision = Decision.UNDECIDED;
        private PersistenceReason rejectionReason;
        private boolean closed;

        private Publication(ProjectReferenceIndex storage,
                ProjectIndexRevision revision,
                boolean revisionWrittenByThisBuild,
                Request request, Throwable persistenceFailure,
                PersistenceStatus acceptedPersistenceStatus,
                PersistenceReason acceptedPersistenceReason,
                ProjectIndexTelemetry.Run telemetry) {
            this.storage = Objects.requireNonNull(storage, "storage"); //$NON-NLS-1$
            this.revision = revision;
            this.revisionWrittenByThisBuild = revisionWrittenByThisBuild;
            this.request = request;
            this.persistenceFailure = persistenceFailure;
            this.acceptedPersistenceStatus = acceptedPersistenceStatus;
            this.acceptedPersistenceReason =
                    Objects.requireNonNull(acceptedPersistenceReason,
                            "acceptedPersistenceReason"); //$NON-NLS-1$
            if ((acceptedPersistenceStatus
                    == PersistenceStatus.MEMORY_ONLY)
                            != (acceptedPersistenceReason
                                    != PersistenceReason.NONE)) {
                throw new IllegalArgumentException(
                        "Only memory-only publication requires a failure reason"); //$NON-NLS-1$
            }
            this.telemetry = telemetry;
        }

        ProjectReferenceIndex storage() {
            return storage;
        }

        boolean v2Current() {
            return revision != null;
        }

        ProjectIndexRevision revision() {
            return revision;
        }

        Throwable persistenceFailure() {
            return persistenceFailure;
        }

        synchronized void transferStorage() {
            ensureOpen();
            if (decision == Decision.TRANSFERRED) {
                return;
            }
            if (decision != Decision.UNDECIDED) {
                throw new IllegalStateException(
                        "Only an undecided publication can be transferred"); //$NON-NLS-1$
            }
            decision = Decision.TRANSFERRED;
            if (acceptedPersistenceStatus != null) {
                setPersistence(telemetry, acceptedPersistenceStatus,
                        acceptedPersistenceReason);
            }
        }

        synchronized void retainExistingStorage() {
            ensureOpen();
            if (decision == Decision.RETAINED) {
                return;
            }
            if (decision != Decision.UNDECIDED) {
                throw new IllegalStateException(
                        "Only an undecided publication can retain existing storage"); //$NON-NLS-1$
            }
            decision = Decision.RETAINED;
            if (acceptedPersistenceStatus != null) {
                setPersistence(telemetry, acceptedPersistenceStatus,
                        acceptedPersistenceReason);
            }
        }

        synchronized void reject(PersistenceReason reason) {
            Objects.requireNonNull(reason, "reason"); //$NON-NLS-1$
            if (reason == PersistenceReason.NONE) {
                throw new IllegalArgumentException(
                        "Publication rejection must have a reason"); //$NON-NLS-1$
            }
            ensureOpen();
            if (decision == Decision.REJECTED) {
                if (rejectionReason == reason) {
                    return;
                }
                throw new IllegalStateException(
                        "Publication already has a different rejection reason"); //$NON-NLS-1$
            }
            if (decision == Decision.TRANSFERRED
                    || decision == Decision.RETAINED) {
                throw new IllegalStateException(
                        "Accepted publication cannot be rejected"); //$NON-NLS-1$
            }
            rejectUndecided(reason);
        }

        synchronized Throwable deleteLegacy() {
            ensureOpen();
            if (decision != Decision.TRANSFERRED
                    && decision != Decision.RETAINED) {
                throw new IllegalStateException(
                        "Legacy state can only be deleted after acceptance"); //$NON-NLS-1$
            }
            if (revision == null || persistenceFailure != null) {
                return null;
            }
            try {
                Files.deleteIfExists(request.legacyState());
                return null;
            } catch (IOException | RuntimeException ex) {
                return ex;
            }
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            if (decision == Decision.UNDECIDED) {
                rejectUndecided(PersistenceReason.STALE_INPUT);
            }
            closed = true;
            finishTelemetry(storage, telemetry);
            telemetry = null;
            if (decision != Decision.TRANSFERRED) {
                closeQuietly(storage);
            }
        }

        private void rejectUndecided(PersistenceReason reason) {
            decision = Decision.REJECTED;
            rejectionReason = reason;
            if (revision != null
                    || acceptedPersistenceStatus
                            == PersistenceStatus.MEMORY_ONLY) {
                setPersistenceFailure(telemetry, reason);
            }
            if (revision != null && !retainsInheritedRevision(reason)) {
                invalidateCurrent(request.storeDirectory(), revision);
            }
        }

        /**
         * Reports whether a rejection must leave the current revision alone.
         * Rejection stays fail-closed except for one proven case: a build that
         * inherited an already published revision, wrote nothing of its own and
         * then lost a race with a newer workspace state. Such a build never
         * touched the store, so the bytes behind {@link #revision} are exactly
         * the bytes this same build had just validated as a warm hit. Deleting
         * CURRENT there costs a full cold rebuild and protects nothing:
         * whatever moved under the build is caught again by the identity and
         * file-stamp validation of the next open. Every other reason, and every
         * revision this build wrote itself, keeps invalidating as before.
         */
        private boolean retainsInheritedRevision(PersistenceReason reason) {
            return reason == PersistenceReason.STALE_INPUT
                    && !revisionWrittenByThisBuild;
        }

        private void ensureOpen() {
            if (closed) {
                throw new IllegalStateException(
                        "Publication is already closed"); //$NON-NLS-1$
            }
        }
    }

    static final class Prepared implements AutoCloseable {

        private final Request request;
        private final ProjectIndexStore store;
        private ProjectIndexData data;
        private final InputSource inputs;
        private final List<ProjectFileStamp> expectedFiles;
        private ProjectReferenceIndex storage;
        private ProjectIndexTelemetry.Run telemetry;
        private final boolean restoredFromDisk;
        private final boolean revisionOnlyValidation;
        private final List<Object> errors;
        private boolean consumed;

        private Prepared(Request request, ProjectReferenceIndex storage,
                ProjectIndexData data, boolean restoredFromDisk,
                List<Object> errors, InputSource inputs,
                List<ProjectFileStamp> expectedFiles,
                ProjectIndexTelemetry.Run telemetry,
                boolean revisionOnlyValidation,
                ProjectIndexStoreFactory storeFactory) {
            this.request = request;
            this.store = Objects.requireNonNull(
                    storeFactory, "storeFactory")
                    .open(request.storeDirectory());
            this.storage = Objects.requireNonNull(storage, "storage"); //$NON-NLS-1$
            this.data = data;
            this.inputs = inputs;
            this.expectedFiles = expectedFiles == null
                    ? null : List.copyOf(expectedFiles);
            this.restoredFromDisk = restoredFromDisk;
            this.revisionOnlyValidation = revisionOnlyValidation;
            this.errors = List.copyOf(errors);
            this.telemetry = telemetry;
        }

        boolean restoredFromDisk() {
            return restoredFromDisk;
        }

        List<Object> errors() {
            return errors;
        }

        private void validateExactRevision() throws IOException {
            if (!(storage
                    instanceof PackedProjectReferenceIndex packed)) {
                throw stale(
                        "Trusted warm validation requires a packed index"); //$NON-NLS-1$
            }
            ProjectIndexRevision expected = packed.revision();
            try (var opened = store.open(request.identity())) {
                if (opened.status() != Status.HIT
                        || !expected.equals(opened.view()
                                .orElseThrow().revision())) {
                    throw stale(
                            "Warm project index revision changed before publication"); //$NON-NLS-1$
                }
            }
        }

        synchronized Publication publish(BooleanSupplier cancelled)
                throws IOException, InterruptedException {
            Objects.requireNonNull(cancelled, "cancelled"); //$NON-NLS-1$
            if (consumed) {
                throw new IllegalStateException(
                        "Prepared project index has already been consumed"); //$NON-NLS-1$
            }
            try {
                checkCancelled(cancelled);
                if (revisionOnlyValidation) {
                    inputs.verifyIdentity();
                    checkCancelled(cancelled);
                    validateExactRevision();
                } else if (expectedFiles != null) {
                    validateCurrent(inputs, expectedFiles, cancelled,
                            telemetry);
                }
            } catch (StaleBuildException ex) {
                setPersistenceFailure(telemetry,
                        PersistenceReason.STALE_INPUT);
                close();
                throw ex;
            } catch (InterruptedException ex) {
                setPersistenceFailure(telemetry,
                        PersistenceReason.CANCELLED);
                close();
                throw ex;
            } catch (IOException ex) {
                setPersistenceFailure(telemetry,
                        PersistenceReason.VALIDATION);
                close();
                throw ex;
            } catch (RuntimeException ex) {
                setPersistenceFailure(telemetry,
                        PersistenceReason.VALIDATION);
                close();
                throw stale("Project inputs cannot be revalidated", ex); //$NON-NLS-1$
            }

            ProjectReferenceIndex candidate = storage;
            boolean current = restoredFromDisk;
            ProjectIndexRevision revision =
                    restoredFromDisk
                            && storage
                                    instanceof PackedProjectReferenceIndex packed
                                    ? packed.revision() : null;
            // A revision inherited from a warm restore was written by an
            // earlier build; only a publication below makes this build its
            // author. Rejection treats the two cases differently.
            boolean revisionWrittenByThisBuild = false;
            PersistenceFailure failure = null;
            if (data != null) {
                ProjectIndexStore.PublishOutcome outcome = null;
                ProjectIndexRevision publicationRevision = null;
                PhaseTimer publishPhase = startPhase(telemetry,
                        Phase.PUBLISH);
                try {
                    outcome = store.publishWithReceipt(
                            data, cancelled, telemetry);
                } catch (ProjectIndexStore
                        .CurrentDurabilityException ex) {
                    publicationRevision = ex.revision();
                    failure = publicationFailure(ex);
                } catch (IOException | RuntimeException ex) {
                    failure = publicationFailure(ex);
                } finally {
                    closePhase(publishPhase);
                }
                if (outcome != null
                        && outcome.status()
                                == ProjectIndexStore.PublishResult.CANCELLED) {
                    setPersistenceFailure(telemetry,
                            PersistenceReason.CANCELLED);
                    consumed = true;
                    finishTelemetry(candidate);
                    closeQuietly(candidate);
                    storage = null;
                    data = null;
                    throw new InterruptedException(
                            "Project index publication cancelled"); //$NON-NLS-1$
                }
                if (outcome != null
                        && outcome.status()
                                == ProjectIndexStore.PublishResult.PUBLISHED) {
                    publicationRevision = outcome.revision();
                }
                try {
                    validateCurrent(inputs, expectedFiles, cancelled,
                            telemetry);
                } catch (StaleBuildException ex) {
                    setPersistenceFailure(telemetry,
                            PersistenceReason.STALE_INPUT);
                    consumed = true;
                    finishTelemetry(candidate);
                    closeQuietly(candidate);
                    storage = null;
                    data = null;
                    if (publicationRevision != null) {
                        invalidateCurrent(request.storeDirectory(),
                                publicationRevision);
                    }
                    attachFailure(ex, failure);
                    throw ex;
                } catch (InterruptedException ex) {
                    setPersistenceFailure(telemetry,
                            PersistenceReason.CANCELLED);
                    consumed = true;
                    finishTelemetry(candidate);
                    closeQuietly(candidate);
                    storage = null;
                    data = null;
                    if (publicationRevision != null) {
                        invalidateCurrent(request.storeDirectory(),
                                publicationRevision);
                    }
                    attachFailure(ex, failure);
                    throw ex;
                } catch (IOException ex) {
                    setPersistenceFailure(telemetry,
                            PersistenceReason.VALIDATION);
                    consumed = true;
                    finishTelemetry(candidate);
                    closeQuietly(candidate);
                    storage = null;
                    data = null;
                    if (publicationRevision != null) {
                        invalidateCurrent(request.storeDirectory(),
                                publicationRevision);
                    }
                    attachFailure(ex, failure);
                    throw ex;
                } catch (RuntimeException ex) {
                    setPersistenceFailure(telemetry,
                            PersistenceReason.VALIDATION);
                    consumed = true;
                    finishTelemetry(candidate);
                    closeQuietly(candidate);
                    storage = null;
                    data = null;
                    if (publicationRevision != null) {
                        invalidateCurrent(request.storeDirectory(),
                                publicationRevision);
                    }
                    StaleBuildException stale =
                            stale("Project inputs cannot be revalidated", ex); //$NON-NLS-1$
                    attachFailure(stale, failure);
                    throw stale;
                }
                if (publicationRevision != null) {
                    revision = publicationRevision;
                    revisionWrittenByThisBuild = true;
                    try {
                        var opened = store.open(request.identity());
                        boolean transferred = false;
                        try {
                            if (opened.status() != Status.HIT) {
                                failure = retainPrimaryFailure(failure,
                                        reopenFailure(new IOException(
                                                "Published project index cannot be reopened")));
                                invalidateCurrent(request.storeDirectory(),
                                        revision);
                                revision = null;
                                current = false;
                            } else if (!publicationRevision.equals(
                                    opened.view().orElseThrow()
                                            .revision())) {
                                failure = retainPrimaryFailure(failure,
                                        validationFailure(new IOException(
                                                "Published project index revision does not match")));
                                invalidateCurrent(request.storeDirectory(),
                                        revision);
                                revision = null;
                                current = false;
                            } else {
                                candidate =
                                        new PackedProjectReferenceIndex(
                                                opened.view().orElseThrow(),
                                                request.projectRoot(),
                                                request.libraryRoot());
                                transferred = true;
                                closeQuietly(storage);
                                current = true;
                            }
                        } finally {
                            if (!transferred) {
                                opened.close();
                            }
                        }
                    } catch (RuntimeException ex) {
                        failure = retainPrimaryFailure(failure,
                                reopenFailure(ex));
                        invalidateCurrent(request.storeDirectory(),
                                revision);
                        revision = null;
                        candidate = storage;
                        current = false;
                    }
                }
            }
            if (cancelled.getAsBoolean()) {
                setPersistenceFailure(telemetry,
                        PersistenceReason.CANCELLED);
                finishTelemetry(candidate);
                closeQuietly(candidate);
                // The same argument as
                // Publication#retainsInheritedRevision, for the other way a
                // build can end without a verdict. A build that inherited its
                // revision from a warm restore wrote nothing to the store, so
                // CURRENT still names the exact bytes this build had just
                // opened and validated; being cancelled damaged none of them,
                // and whatever moved meanwhile is caught again by the identity
                // and stamp checks of the next open. Dropping CURRENT there
                // buys nothing and costs the next build a full cold rebuild. A
                // revision this build published itself is still invalidated:
                // cancellation leaves it adopted by nobody.
                if (current && revisionWrittenByThisBuild) {
                    invalidateCurrent(request.storeDirectory(), revision);
                }
                consumed = true;
                storage = null;
                data = null;
                throw new InterruptedException(
                        "Project index publication cancelled"); //$NON-NLS-1$
            }
            if (failure != null
                    && candidate instanceof ProjectReferencesStorage memory) {
                candidate = MemoryProjectReferenceIndex.fromPrepared(
                        memory, data, request.projectRoot(),
                        request.libraryRoot(), failure.reason());
            } else if (failure != null) {
                setPersistenceFailure(telemetry, failure.reason());
            }
            PersistenceStatus acceptedPersistenceStatus =
                    candidate instanceof MemoryProjectReferenceIndex
                            ? PersistenceStatus.MEMORY_ONLY
                            : failure == null && current
                            ? restoredFromDisk
                                    ? PersistenceStatus.HIT
                                    : PersistenceStatus.PUBLISHED
                            : null;
            PersistenceReason acceptedPersistenceReason =
                    candidate instanceof MemoryProjectReferenceIndex memory
                            ? memory.persistenceReason()
                            : PersistenceReason.NONE;
            consumed = true;
            storage = null;
            data = null;
            return new Publication(candidate,
                    current ? revision : null,
                    revisionWrittenByThisBuild, request,
                    failure == null ? null : failure.cause(),
                    acceptedPersistenceStatus,
                    acceptedPersistenceReason,
                    takeTelemetry());
        }

        /**
         * Drops this build without publishing anything, because the
         * configuration it was built under is no longer the settled one.
         *
         * <p>Nothing was written, so nothing has to be taken back: the guard
         * runs before {@link #publish(BooleanSupplier)} and the store still
         * holds exactly what it held before this build started. What the
         * refused build leaves behind is the caller's to decide; all this does
         * is say in the line why the build ended without one.</p>
         *
         * @param reason why this build did not publish
         */
        synchronized void refuseConfiguration(
                ProjectIndexTelemetry.ConfigurationGuard reason) {
            Objects.requireNonNull(reason, "reason"); //$NON-NLS-1$
            if (telemetry != null) {
                telemetry.configurationGuard(reason);
            }
            close();
        }

        @Override
        public synchronized void close() {
            if (!consumed) {
                consumed = true;
                finishTelemetry(storage);
                closeQuietly(storage);
                storage = null;
                data = null;
            }
        }

        private ProjectIndexTelemetry.Run takeTelemetry() {
            ProjectIndexTelemetry.Run result = telemetry;
            telemetry = null;
            return result;
        }

        private void finishTelemetry(ProjectReferenceIndex index) {
            ProjectIndexBuildCoordinator.finishTelemetry(index,
                    takeTelemetry());
        }
    }

    private ProjectIndexBuildCoordinator() {
    }

    static Prepared prepare(Request request, InputSource inputs,
            FullBuild fullBuild, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        return prepare(request, inputs, fullBuild, cancelled, null);
    }

    static Prepared prepare(Request request, InputSource inputs,
            FullBuild fullBuild, BooleanSupplier cancelled,
            ProjectIndexTelemetry.Run telemetry)
            throws IOException, InterruptedException {
        return prepare(request, inputs, fullBuild, cancelled,
                telemetry, false,
                ProjectIndexStoreFactory.PLATFORM);
    }

    static Prepared prepare(Request request, InputSource inputs,
            FullBuild fullBuild, BooleanSupplier cancelled,
            ProjectIndexTelemetry.Run telemetry,
            boolean trustedWarmValidation)
            throws IOException, InterruptedException {
        return prepare(request, inputs, fullBuild, cancelled,
                telemetry, trustedWarmValidation,
                ProjectIndexStoreFactory.PLATFORM);
    }

    static Prepared prepare(Request request, InputSource inputs,
            FullBuild fullBuild, BooleanSupplier cancelled,
            ProjectIndexTelemetry.Run telemetry,
            boolean trustedWarmValidation,
            ProjectIndexStoreFactory storeFactory)
            throws IOException, InterruptedException {
        Objects.requireNonNull(request, "request"); //$NON-NLS-1$
        Objects.requireNonNull(inputs, "inputs"); //$NON-NLS-1$
        Objects.requireNonNull(fullBuild, "fullBuild"); //$NON-NLS-1$
        Objects.requireNonNull(cancelled, "cancelled"); //$NON-NLS-1$
        Objects.requireNonNull(storeFactory, "storeFactory"); //$NON-NLS-1$
        WarmAttempt attempt = attemptWarm(request, inputs, cancelled,
                telemetry, trustedWarmValidation, storeFactory);
        return attempt.hit()
                ? attempt.prepared().orElseThrow()
                : attempt.completeFullBuild(fullBuild);
    }

    /**
     * Takes the warm half of {@link #prepare} on its own, leaving the full
     * build it would have gone on to run in the caller's hands.
     *
     * <p>A caller that only wants an index calls {@link #prepare} and never
     * sees this. The two halves come apart for a caller that can do better
     * than a full build with a miss - repair the index from the files the
     * validation named - because such a caller has to see what the validation
     * found, and must not pay for a second enumeration of the working tree if
     * its repair turns out to be impossible after all.</p>
     *
     * @return the attempt, which either restored an index or holds everything
     *         the full build it did not run will need
     */
    static WarmAttempt attemptWarm(Request request, InputSource inputs,
            BooleanSupplier cancelled, ProjectIndexTelemetry.Run telemetry,
            boolean trustedWarmValidation,
            ProjectIndexStoreFactory storeFactory)
            throws IOException, InterruptedException {
        Objects.requireNonNull(request, "request"); //$NON-NLS-1$
        Objects.requireNonNull(inputs, "inputs"); //$NON-NLS-1$
        Objects.requireNonNull(cancelled, "cancelled"); //$NON-NLS-1$
        Objects.requireNonNull(storeFactory, "storeFactory"); //$NON-NLS-1$
        if (telemetry != null) {
            telemetry.identity(request.identity());
        }
        InputSource effectiveInputs = telemetry == null
                ? inputs : new MeteredInputSource(inputs, telemetry);
        try {
            checkCancelled(cancelled);
            List<CurrentFile> before = inspectForWarm(
                    effectiveInputs, cancelled, telemetry);
            WarmRestore restored = restoreWarm(request, effectiveInputs,
                    before, cancelled, telemetry, trustedWarmValidation,
                    storeFactory);
            return new WarmAttempt(request, effectiveInputs, before,
                    restored, cancelled, telemetry, storeFactory);
        } catch (IOException | InterruptedException
                | RuntimeException | Error ex) {
            finishTelemetry(null, telemetry);
            throw ex;
        }
    }

    static Optional<Prepared> tryPrepareWarm(
            Request request, InputSource inputs,
            BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        return tryPrepareWarm(request, inputs, cancelled, null,
                ProjectIndexStoreFactory.PLATFORM);
    }

    static Optional<Prepared> tryPrepareWarm(
            Request request, InputSource inputs,
            BooleanSupplier cancelled,
            ProjectIndexTelemetry.Run telemetry,
            ProjectIndexStoreFactory storeFactory)
            throws IOException, InterruptedException {
        Objects.requireNonNull(request, "request"); //$NON-NLS-1$
        Objects.requireNonNull(inputs, "inputs"); //$NON-NLS-1$
        Objects.requireNonNull(cancelled, "cancelled"); //$NON-NLS-1$
        Objects.requireNonNull(storeFactory, "storeFactory"); //$NON-NLS-1$
        if (telemetry != null) {
            telemetry.identity(request.identity());
        }
        InputSource effectiveInputs = telemetry == null
                ? inputs : new MeteredInputSource(inputs, telemetry);
        try {
            checkCancelled(cancelled);
            List<CurrentFile> before = inspectForWarm(
                    effectiveInputs, cancelled, telemetry);
            WarmRestore restored = restoreWarm(
                    request, effectiveInputs, before, cancelled,
                    telemetry, false, storeFactory);
            if (!restored.hit()) {
                finishTelemetry(null, telemetry);
            }
            return restored.prepared();
        } catch (StaleBuildException ex) {
            setPersistenceFailure(telemetry,
                    PersistenceReason.STALE_INPUT);
            finishTelemetry(null, telemetry);
            throw ex;
        } catch (InterruptedException ex) {
            setPersistenceFailure(telemetry,
                    PersistenceReason.CANCELLED);
            finishTelemetry(null, telemetry);
            throw ex;
        } catch (IOException | RuntimeException | Error ex) {
            finishTelemetry(null, telemetry);
            throw ex;
        }
    }

    /**
     * One warm attempt, and everything the full build it did not run would
     * need. Its whole reason to exist is that the inspection of the working
     * tree the attempt already took is reusable: a caller that decides against
     * repairing a miss resumes the build here instead of enumerating the tree
     * a second time.
     */
    static final class WarmAttempt {

        private final Request request;
        private final InputSource inputs;
        private final List<CurrentFile> before;
        private final WarmRestore restored;
        private final BooleanSupplier cancelled;
        private final ProjectIndexTelemetry.Run telemetry;
        private final ProjectIndexStoreFactory storeFactory;

        private WarmAttempt(Request request, InputSource inputs,
                List<CurrentFile> before, WarmRestore restored,
                BooleanSupplier cancelled,
                ProjectIndexTelemetry.Run telemetry,
                ProjectIndexStoreFactory storeFactory) {
            this.request = request;
            this.inputs = inputs;
            this.before = before;
            this.restored = restored;
            this.cancelled = cancelled;
            this.telemetry = telemetry;
            this.storeFactory = storeFactory;
        }

        /** @return whether this attempt restored an index */
        boolean hit() {
            return restored.hit();
        }

        /** @return the restored build, empty when this attempt missed */
        Optional<Prepared> prepared() {
            return restored.prepared();
        }

        /**
         * @return what the warm validation of this attempt found, null when no
         *         warm index was validated at all
         */
        ProjectIndexWarmValidator.Result validation() {
            return restored.validation();
        }

        /**
         * Runs the full build this attempt did not make unnecessary.
         *
         * @param fullBuild the build to run
         * @return the prepared index
         * @throws IllegalStateException if this attempt restored an index,
         *                               which a full build would then drop
         *                               unclosed
         */
        Prepared completeFullBuild(FullBuild fullBuild)
                throws IOException, InterruptedException {
            Objects.requireNonNull(fullBuild, "fullBuild"); //$NON-NLS-1$
            if (hit()) {
                throw new IllegalStateException(
                        "A restored index must not be rebuilt"); //$NON-NLS-1$
            }
            try {
                return fullBuild(request, inputs, before, fullBuild,
                        cancelled, telemetry, storeFactory);
            } catch (IOException | InterruptedException
                    | RuntimeException | Error ex) {
                finishTelemetry(null, telemetry);
                throw ex;
            }
        }
    }

    /**
     * Builds the whole index, packing it against the inspection a warm attempt
     * already took of the working tree.
     *
     * @param before what the working tree held when the warm attempt looked at
     *               it, null when it could not be inspected at all
     */
    private static Prepared fullBuild(Request request,
            InputSource inputs, List<CurrentFile> before,
            FullBuild fullBuild, BooleanSupplier cancelled,
            ProjectIndexTelemetry.Run telemetry,
            ProjectIndexStoreFactory storeFactory)
            throws IOException, InterruptedException {
        checkCancelled(cancelled);
        BuildResult built = fullBuild.load();
        checkCancelled(cancelled);
        if (before == null || !built.errors().isEmpty()) {
            setBypass(telemetry, before == null
                    ? BypassReason.INPUT_INSPECTION_FAILURE
                    : BypassReason.ANALYSIS_ERRORS);
            return new Prepared(request, built.storage(), null, false,
                    built.errors(), null, null, telemetry, false,
                    storeFactory);
        }

        BypassReason failureReason =
                BypassReason.PERSISTENCE_INPUT_FAILURE;
        try {
            List<CurrentFile> after = inputs.inspect();
            if (!before.equals(after)) {
                throw stale("Project inputs changed during full build"); //$NON-NLS-1$
            }
            Optional<List<ProjectFileStamp>> captured =
                    Optional.empty();
            if (built.inputFingerprints() != null) {
                try {
                    captured = ProjectIndexFiles
                            .stampsFromCapturedFingerprints(
                                    after,
                                    built.inputFingerprints(),
                                    inputs.resolver());
                } catch (RuntimeException ex) {
                    captured = Optional.empty();
                }
            }
            List<ProjectFileStamp> stamps;
            if (captured.isPresent()) {
                stamps = captured.orElseThrow();
                if (telemetry != null) {
                    telemetry.addInlineHashedPaths(
                            stamps.size());
                }
            } else {
                stamps = inputs.hashAll(after, cancelled);
                List<CurrentFile> afterHash =
                        inputs.inspect();
                if (!after.equals(afterHash)) {
                    throw stale(
                            "Project inputs changed while hashing"); //$NON-NLS-1$
                }
            }
            inputs.verifyIdentity();
            failureReason = BypassReason.SNAPSHOT_PACK_FAILURE;
            ProjectIndexData data;
            PhaseTimer packPhase = startPhase(telemetry, Phase.PACK);
            try {
                data = ProjectIndexSnapshotFactory.create(
                        request.identity(), request.generation(), stamps,
                        built.storage().getObjDefinitions(),
                        built.storage().getObjReferences(),
                        inputs.resolver(),
                        ProjectIndexSnapshotFactory.noPathsWithErrors(
                                built.errors()));
            } finally {
                closePhase(packPhase);
            }
            return new Prepared(request, built.storage(), data, false,
                    built.errors(), inputs, stamps, telemetry, false,
                    storeFactory);
        } catch (StaleBuildException ex) {
            setBypass(telemetry, failureReason);
            setPersistenceFailure(telemetry,
                    PersistenceReason.STALE_INPUT);
            closeQuietly(built.storage());
            throw ex;
        } catch (InterruptedException ex) {
            setBypass(telemetry, failureReason);
            setPersistenceFailure(telemetry,
                    PersistenceReason.CANCELLED);
            closeQuietly(built.storage());
            throw ex;
        } catch (IOException | RuntimeException ex) {
            try {
                checkCancelled(cancelled);
            } catch (InterruptedException cancelledException) {
                setBypass(telemetry, failureReason);
                setPersistenceFailure(telemetry,
                        PersistenceReason.CANCELLED);
                closeQuietly(built.storage());
                throw cancelledException;
            }
            setBypass(telemetry, failureReason);
            setPersistenceFailure(telemetry,
                    persistenceFailureReason(ex));
            return new Prepared(request, built.storage(), null, false,
                    built.errors(), null, null, telemetry, false,
                    storeFactory);
        }
    }

    private static List<CurrentFile> inspectForWarm(
            InputSource inputs, BooleanSupplier cancelled,
            ProjectIndexTelemetry.Run telemetry)
            throws IOException, InterruptedException {
        try {
            return inputs.inspect();
        } catch (StaleBuildException ex) {
            setBypass(telemetry,
                    BypassReason.INPUT_INSPECTION_FAILURE);
            setPersistenceFailure(telemetry,
                    PersistenceReason.STALE_INPUT);
            throw ex;
        } catch (InterruptedException ex) {
            setBypass(telemetry,
                    BypassReason.INPUT_INSPECTION_FAILURE);
            setPersistenceFailure(telemetry,
                    PersistenceReason.CANCELLED);
            throw ex;
        } catch (IOException | RuntimeException ex) {
            try {
                checkCancelled(cancelled);
            } catch (InterruptedException cancelledException) {
                setBypass(telemetry,
                        BypassReason.INPUT_INSPECTION_FAILURE);
                setPersistenceFailure(telemetry,
                        PersistenceReason.CANCELLED);
                throw cancelledException;
            }
            setBypass(telemetry,
                    BypassReason.INPUT_INSPECTION_FAILURE);
            setPersistenceFailure(telemetry,
                    persistenceFailureReason(ex));
            return null;
        }
    }

    /**
     * Outcome of one warm restore attempt.
     *
     * <p>An attempt answers two questions, and only the first one decides what
     * happens next: whether an index could be restored, and - when a warm index
     * was validated and refused - how far the working tree had moved away from
     * it. The second is computed here anyway, and it leaves with the answer so
     * that the size of a divergence can be observed instead of guessed.</p>
     *
     * @param prepared   restored build, empty when this attempt produced none
     * @param validation what the warm validation of this attempt found, null
     *                   when no warm index was validated at all
     */
    record WarmRestore(Optional<Prepared> prepared,
            ProjectIndexWarmValidator.Result validation) {

        WarmRestore {
            Objects.requireNonNull(prepared, "prepared"); //$NON-NLS-1$
        }

        static WarmRestore hit(Prepared prepared,
                ProjectIndexWarmValidator.Result validation) {
            return new WarmRestore(Optional.of(prepared), validation);
        }

        static WarmRestore miss(ProjectIndexWarmValidator.Result validation) {
            return new WarmRestore(Optional.empty(), validation);
        }

        /**
         * @return whether this attempt restored an index, which is the only
         *         question its callers branch on
         */
        boolean hit() {
            return prepared.isPresent();
        }
    }

    static WarmRestore restoreWarm(
            Request request, InputSource inputs,
            List<CurrentFile> before,
            BooleanSupplier cancelled,
            ProjectIndexTelemetry.Run telemetry,
            boolean trustedWarmValidation,
            ProjectIndexStoreFactory storeFactory)
            throws IOException, InterruptedException {
        if (before == null) {
            return WarmRestore.miss(null);
        }

        ProjectIndexWarmValidator.Result validation = null;
        try (var store = storeFactory.open(
                request.storeDirectory())) {
            var opened = store.open(request.identity());
            boolean transferred = false;
            try {
                if (opened.status() == Status.HIT) {
                    var view = opened.view().orElseThrow();
                    PhaseTimer validationPhase = startPhase(telemetry,
                            Phase.VALIDATE);
                    try {
                        validation = ProjectIndexWarmValidator.validate(
                                view.fileStamps(), before,
                                file -> inputs.hashAll(List.of(file),
                                        cancelled).getFirst()
                                        .contentSha256(),
                                cancelled);
                    } finally {
                        closePhase(validationPhase);
                    }
                    setWarmMiss(telemetry, validation);
                    if (validation.hit()) {
                        setMode(telemetry, Mode.WARM);
                        inputs.verifyIdentity();
                        List<ProjectFileStamp> expected =
                                trustedWarmValidation
                                        ? null : view.fileStamps();
                        var packed = new PackedProjectReferenceIndex(view,
                                request.projectRoot(),
                                request.libraryRoot());
                        Prepared prepared = new Prepared(request, packed,
                                null, true, List.of(), inputs, expected,
                                telemetry, trustedWarmValidation,
                                storeFactory);
                        transferred = true;
                        return WarmRestore.hit(prepared, validation);
                    }
                    setPersistenceFailure(telemetry,
                            PersistenceReason.STALE_INPUT);
                } else if (opened.status() == Status.STALE) {
                    setPersistenceFailure(telemetry,
                            PersistenceReason.STALE_INPUT);
                } else if (opened.status() == Status.RETRYABLE) {
                    // A transient environment failure must never cost the
                    // whole persistent index: fall back to a full rebuild and
                    // keep the store for the next open.
                    setPersistenceFailure(telemetry,
                            PersistenceReason.IO);
                } else if (opened.status() == Status.CORRUPT) {
                    setPersistenceFailure(telemetry,
                            PersistenceReason.VALIDATION);
                    cleanStore(request.storeDirectory());
                }
            } finally {
                if (!transferred) {
                    opened.close();
                }
            }
        } catch (StaleBuildException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            checkCancelled(cancelled);
            setPersistenceFailure(telemetry,
                    persistenceFailureReason(ex));
            if (isFormatCorruption(ex)) {
                cleanStore(request.storeDirectory());
            }
        }
        return WarmRestore.miss(validation);
    }

    /**
     * Reports whether a failure proves format-level damage of the persistent
     * index. Only verified corruption may delete the store; every other
     * failure keeps it and falls back to a full rebuild.
     */
    static boolean isFormatCorruption(Throwable cause) {
        Throwable current = cause;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof ProjectIndexFormatException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static void validateCurrent(InputSource inputs,
            List<ProjectFileStamp> expected, BooleanSupplier cancelled,
            ProjectIndexTelemetry.Run telemetry)
            throws IOException, InterruptedException {
        PhaseTimer validationPhase = startPhase(telemetry, Phase.VALIDATE);
        try {
            inputs.verifyIdentity();
            List<CurrentFile> current = inputs.inspect();
            ProjectIndexWarmValidator.Result validation =
                    ProjectIndexWarmValidator.validate(expected, current,
                            file -> inputs.hashAll(List.of(file), cancelled)
                                    .getFirst().contentSha256(),
                            cancelled);
            if (!validation.hit()) {
                throw stale("Project inputs changed before publication"); //$NON-NLS-1$
            }
            List<CurrentFile> afterValidation = inputs.inspect();
            if (!current.equals(afterValidation)) {
                throw stale(
                        "Project inputs changed during publication validation"); //$NON-NLS-1$
            }
            inputs.verifyIdentity();
            checkCancelled(cancelled);
        } finally {
            closePhase(validationPhase);
        }
    }

    private static StaleBuildException stale(String message) {
        return new StaleBuildException(message);
    }

    private static StaleBuildException stale(String message,
            Throwable cause) {
        return new StaleBuildException(message, cause);
    }

    private static void checkCancelled(BooleanSupplier cancelled)
            throws InterruptedException {
        if (cancelled.getAsBoolean()) {
            throw new InterruptedException(
                    "Project index build cancelled"); //$NON-NLS-1$
        }
    }

    private static void cleanStore(Path directory) {
        try {
            new ProjectIndexStore(directory).clean();
        } catch (IOException | RuntimeException ex) {
            // A stale cache remains fail-closed because identity is validated.
        }
    }

    private static void invalidateCurrent(Path directory,
            ProjectIndexRevision revision) {
        if (revision == null) {
            return;
        }
        try {
            new ProjectIndexStore(directory)
                    .invalidateCurrent(revision);
        } catch (IOException | RuntimeException ex) {
            // A successfully decoded different revision is preserved.
        }
    }

    private static void setMode(ProjectIndexTelemetry.Run telemetry,
            Mode mode) {
        if (telemetry != null) {
            telemetry.mode(mode);
            if (mode == Mode.WARM) {
                telemetry.addParsedPaths(0).addAnalyzedPaths(0);
            }
        }
    }

    private static void setBypass(ProjectIndexTelemetry.Run telemetry,
            BypassReason reason) {
        if (telemetry != null) {
            telemetry.bypass(reason);
        }
    }

    /**
     * Offers the outcome of a warm validation to the run. Only a miss reaches
     * the published line; deciding that is the reporter's business, not this
     * one's, so every completed validation is offered.
     */
    private static void setWarmMiss(ProjectIndexTelemetry.Run telemetry,
            ProjectIndexWarmValidator.Result validation) {
        if (telemetry != null) {
            telemetry.warmMiss(validation);
        }
    }

    private static PersistenceFailure publicationFailure(Throwable cause) {
        return new PersistenceFailure(cause,
                persistenceFailureReason(cause));
    }

    private static PersistenceFailure retainPrimaryFailure(
            PersistenceFailure primary,
            PersistenceFailure secondary) {
        if (primary == null) {
            return secondary;
        }
        if (primary.cause() != secondary.cause()) {
            primary.cause().addSuppressed(secondary.cause());
        }
        return primary;
    }

    private static void attachFailure(Throwable target,
            PersistenceFailure failure) {
        if (failure != null && target != failure.cause()) {
            target.addSuppressed(failure.cause());
        }
    }

    static PersistenceReason persistenceFailureReason(Throwable cause) {
        Objects.requireNonNull(cause, "cause");
        Throwable current = cause;
        boolean staleFailure = false;
        boolean ioFailure = false;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof ProjectIndexPersistenceException typed) {
                return typed.reason();
            }
            if (current instanceof StaleBuildException) {
                staleFailure = true;
            } else if (current instanceof IOException) {
                ioFailure = true;
            }
            current = current.getCause();
        }
        return staleFailure
                ? PersistenceReason.STALE_INPUT
                : ioFailure
                ? PersistenceReason.IO : PersistenceReason.VALIDATION;
    }

    private static PersistenceFailure validationFailure(Throwable cause) {
        return new PersistenceFailure(cause,
                PersistenceReason.VALIDATION);
    }

    private static PersistenceFailure reopenFailure(Throwable cause) {
        return new PersistenceFailure(cause, PersistenceReason.REOPEN);
    }

    private static void setPersistenceFailure(
            ProjectIndexTelemetry.Run telemetry,
            PersistenceReason reason) {
        setPersistence(telemetry, PersistenceStatus.FAILED, reason);
    }

    private static void setPersistence(ProjectIndexTelemetry.Run telemetry,
            PersistenceStatus status, PersistenceReason reason) {
        if (telemetry != null) {
            telemetry.persistence(status, reason);
        }
    }

    private static PhaseTimer startPhase(
            ProjectIndexTelemetry.Run telemetry, Phase phase) {
        return telemetry == null ? null : telemetry.phase(phase);
    }

    private static void closePhase(PhaseTimer phase) {
        if (phase != null) {
            phase.close();
        }
    }

    private static void finishTelemetry(ProjectReferenceIndex index,
            ProjectIndexTelemetry.Run telemetry) {
        if (telemetry == null) {
            return;
        }
        try {
            if (index instanceof PackedProjectReferenceIndex packed) {
                packed.recordBlockCache(telemetry);
            }
        } catch (RuntimeException ex) {
            // Telemetry must never change indexing behavior.
        } finally {
            telemetry.close();
        }
    }

    private static void closeQuietly(ProjectReferenceIndex index) {
        if (index == null) {
            return;
        }
        try {
            index.close();
        } catch (RuntimeException ex) {
            // Closing a discarded optimization must not break the editor.
        }
    }
}
