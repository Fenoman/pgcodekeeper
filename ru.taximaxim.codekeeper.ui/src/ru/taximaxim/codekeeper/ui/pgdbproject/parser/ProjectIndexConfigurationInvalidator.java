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

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;

/**
 * Serializes configuration invalidation per project and coalesces rapid
 * configuration changes into one delayed full build.
 */
final class ProjectIndexConfigurationInvalidator {

    @FunctionalInterface
    interface StateInvalidation {
        void run(Runnable persistedInvalidation);
    }

    @FunctionalInterface
    interface BuildTask {
        void run(IProgressMonitor monitor) throws CoreException;
    }

    @FunctionalInterface
    interface ScheduledBuild {
        void cancel();
    }

    @FunctionalInterface
    interface BuildScheduler {
        ScheduledBuild schedule(Object project, BuildTask task);
    }

    private static final class PendingBuild {

        private final long scheduledGeneration;
        private long requestedGeneration;
        private long requestedInvalidationGeneration;
        private BuildTask task;
        private ScheduledBuild handle;
        private boolean running;

        private PendingBuild(long generation, BuildTask task,
                long invalidationGeneration) {
            this.scheduledGeneration = generation;
            this.requestedGeneration = generation;
            this.requestedInvalidationGeneration =
                    invalidationGeneration;
            this.task = task;
        }
    }

    private record InvalidationReservation(long generation,
            BuildTask task) { }

    private record ScheduleRequest(Object project,
            PendingBuild pending) { }

    private final Map<Object, String> fingerprints = new HashMap<>();
    private final Map<Object, InvalidationReservation> invalidations =
            new HashMap<>();
    private final Map<Object, PendingBuild> pendingBuilds = new HashMap<>();
    private final BuildScheduler scheduler;
    private long nextGeneration;

    ProjectIndexConfigurationInvalidator(BuildScheduler scheduler) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler"); //$NON-NLS-1$
    }

    synchronized void observe(Object project, String fingerprint) {
        fingerprints.putIfAbsent(project, fingerprint);
    }

    boolean invalidate(Object project, String fingerprint,
            StateInvalidation stateInvalidation,
            Runnable persistedInvalidation,
            BuildTask fullBuild) {
        Objects.requireNonNull(project, "project"); //$NON-NLS-1$
        Objects.requireNonNull(fingerprint, "fingerprint"); //$NON-NLS-1$
        Objects.requireNonNull(stateInvalidation,
                "stateInvalidation"); //$NON-NLS-1$
        Objects.requireNonNull(persistedInvalidation,
                "persistedInvalidation"); //$NON-NLS-1$
        Objects.requireNonNull(fullBuild, "fullBuild"); //$NON-NLS-1$

        String previousFingerprint;
        InvalidationReservation previousReservation;
        InvalidationReservation reservation;
        synchronized (this) {
            previousFingerprint = fingerprints.get(project);
            if (Objects.equals(previousFingerprint, fingerprint)) {
                return false;
            }
            previousReservation = invalidations.get(project);
            reservation = new InvalidationReservation(
                    ++nextGeneration, fullBuild);
            fingerprints.put(project, fingerprint);
            invalidations.put(project, reservation);
        }

        try {
            stateInvalidation.run(persistedInvalidation);
        } catch (RuntimeException | Error ex) {
            InvalidationReservation latest = null;
            synchronized (this) {
                InvalidationReservation current =
                        invalidations.get(project);
                if (current == reservation) {
                    if (previousFingerprint == null) {
                        fingerprints.remove(project);
                    } else {
                        fingerprints.put(project,
                                previousFingerprint);
                    }
                    if (previousReservation == null) {
                        invalidations.remove(project);
                    } else {
                        invalidations.put(project,
                                previousReservation);
                        latest = previousReservation;
                    }
                } else if (current != null) {
                    latest = current;
                }
            }
            if (latest != null) {
                try {
                    scheduleBuild(project, latest);
                } catch (RuntimeException | Error suppressed) {
                    ex.addSuppressed(suppressed);
                }
            }
            throw ex;
        }

        InvalidationReservation latest;
        synchronized (this) {
            latest = invalidations.get(project);
            if (latest == null) {
                return true;
            }
        }
        scheduleBuild(project, latest);
        return true;
    }

    void scheduleBuild(Object project, BuildTask fullBuild) {
        Objects.requireNonNull(project, "project"); //$NON-NLS-1$
        Objects.requireNonNull(fullBuild, "fullBuild"); //$NON-NLS-1$
        ScheduleRequest request;
        synchronized (this) {
            request = requestBuildLocked(project, fullBuild, -1);
        }
        schedule(request);
    }

    private void scheduleBuild(Object project,
            InvalidationReservation reservation) {
        ScheduleRequest request;
        synchronized (this) {
            InvalidationReservation current =
                    invalidations.get(project);
            if (current != reservation) {
                return;
            }
            request = requestBuildLocked(project,
                    reservation.task(), reservation.generation());
        }
        schedule(request);
    }

    void forget(Object project) {
        ScheduledBuild handle;
        synchronized (this) {
            fingerprints.remove(project);
            invalidations.remove(project);
            PendingBuild pending = pendingBuilds.remove(project);
            handle = pending == null ? null : pending.handle;
        }
        if (handle != null) {
            handle.cancel();
        }
    }

    private ScheduleRequest requestBuildLocked(
            Object project, BuildTask fullBuild,
            long invalidationGeneration) {
        PendingBuild pending = pendingBuilds.get(project);
        if (pending != null) {
            pending.requestedGeneration = ++nextGeneration;
            pending.requestedInvalidationGeneration =
                    invalidationGeneration;
            pending.task = fullBuild;
            return null;
        }

        long generation = ++nextGeneration;
        pending = new PendingBuild(generation, fullBuild,
                invalidationGeneration);
        pendingBuilds.put(project, pending);
        return new ScheduleRequest(project, pending);
    }

    private void schedule(ScheduleRequest request) {
        schedule(request, true);
    }

    private void schedule(ScheduleRequest request,
            boolean recoverCoalescedRequest) {
        if (request == null) {
            return;
        }
        Object project = request.project();
        PendingBuild scheduled = request.pending();
        ScheduledBuild handle;
        try {
            handle = Objects.requireNonNull(
                    scheduler.schedule(project,
                    monitor -> runScheduled(project,
                            scheduled.scheduledGeneration, monitor)),
                    "scheduled build handle"); //$NON-NLS-1$
        } catch (RuntimeException | Error ex) {
            ScheduleRequest retry = null;
            synchronized (this) {
                if (pendingBuilds.remove(project, scheduled)) {
                    boolean coalesced =
                            scheduled.requestedGeneration
                                    != scheduled.scheduledGeneration;
                    if (recoverCoalescedRequest && coalesced) {
                        retry = requestBuildLocked(project,
                                scheduled.task,
                                scheduled.requestedInvalidationGeneration);
                    } else {
                        InvalidationReservation current =
                                invalidations.get(project);
                        if (current != null
                                && current.generation()
                                        == scheduled
                                                .requestedInvalidationGeneration) {
                            fingerprints.remove(project);
                            invalidations.remove(project);
                        }
                    }
                }
            }
            if (retry != null) {
                try {
                    schedule(retry, false);
                    return;
                } catch (RuntimeException | Error retryFailure) {
                    ex.addSuppressed(retryFailure);
                }
            }
            throw ex;
        }

        boolean accepted;
        synchronized (this) {
            PendingBuild current = pendingBuilds.get(project);
            accepted = current == scheduled
                    && current.scheduledGeneration
                            == scheduled.scheduledGeneration;
            if (accepted) {
                current.handle = handle;
            }
        }
        if (!accepted) {
            handle.cancel();
        }
    }

    private void runScheduled(Object project, long scheduledGeneration,
            IProgressMonitor monitor) throws CoreException {
        BuildTask task;
        long runningGeneration;
        synchronized (this) {
            PendingBuild pending = pendingBuilds.get(project);
            if (pending == null
                    || pending.scheduledGeneration != scheduledGeneration
                    || pending.running) {
                return;
            }
            pending.running = true;
            runningGeneration = pending.requestedGeneration;
            task = pending.task;
        }

        try {
            task.run(monitor);
        } finally {
            completeBuild(project, scheduledGeneration, runningGeneration);
        }
    }

    private void completeBuild(Object project,
            long scheduledGeneration, long runningGeneration) {
        ScheduleRequest request = null;
        synchronized (this) {
            PendingBuild pending = pendingBuilds.get(project);
            if (pending == null
                    || pending.scheduledGeneration
                            != scheduledGeneration) {
                return;
            }
            pendingBuilds.remove(project);
            if (pending.requestedGeneration != runningGeneration) {
                request = requestBuildLocked(project,
                        pending.task,
                        pending.requestedInvalidationGeneration);
            }
        }
        schedule(request);
    }
}
