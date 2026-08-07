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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.jupiter.api.Test;

class ProjectIndexConfigurationInvalidatorTest {

    @Test
    void projectApplyInvalidatesStateBeforeDelayedFullBuild() {
        var scheduler = new ManualScheduler();
        var invalidator = new ProjectIndexConfigurationInvalidator(scheduler);
        Object project = new Object();
        var live = new AtomicInteger();
        var persisted = new AtomicInteger();
        var builds = new AtomicInteger();

        assertTrue(invalidator.invalidate(project, "digest-a",
                persistedInvalidation -> {
                    live.incrementAndGet();
                    persistedInvalidation.run();
                }, persisted::incrementAndGet,
                monitor -> builds.incrementAndGet()));

        assertEquals(1, live.get());
        assertEquals(1, persisted.get());
        assertEquals(0, builds.get());
        assertEquals(1, scheduler.scheduleCalls);

        scheduler.run(0);
        assertEquals(1, builds.get());
    }

    @Test
    void duplicateDigestIsSafeNoOp() {
        var scheduler = new ManualScheduler();
        var invalidator = new ProjectIndexConfigurationInvalidator(scheduler);
        Object project = new Object();
        var invalidations = new AtomicInteger();
        var builds = new AtomicInteger();

        assertTrue(invalidator.invalidate(project, "digest-a",
                persistedInvalidation -> {
                    invalidations.incrementAndGet();
                    persistedInvalidation.run();
                }, invalidations::incrementAndGet,
                monitor -> builds.incrementAndGet()));
        assertFalse(invalidator.invalidate(project, "digest-a",
                persistedInvalidation -> {
                    invalidations.incrementAndGet();
                    persistedInvalidation.run();
                }, invalidations::incrementAndGet,
                monitor -> builds.incrementAndGet()));

        assertEquals(2, invalidations.get());
        assertEquals(1, scheduler.scheduleCalls);
        scheduler.run(0);
        assertEquals(1, builds.get());
    }

    @Test
    void observedUnchangedEffectiveConfigurationDoesNotInvalidate() {
        var scheduler = new ManualScheduler();
        var invalidator = new ProjectIndexConfigurationInvalidator(scheduler);
        Object project = new Object();
        var invalidations = new AtomicInteger();
        invalidator.observe(project, "project-override");

        assertFalse(invalidator.invalidate(project, "project-override",
                persistedInvalidation -> {
                    invalidations.incrementAndGet();
                    persistedInvalidation.run();
                }, invalidations::incrementAndGet,
                monitor -> invalidations.incrementAndGet()));

        assertEquals(0, invalidations.get());
        assertEquals(0, scheduler.scheduleCalls);
    }

    @Test
    void rapidAtoBtoCChangesScheduleExactlyOneBuildWithLatestGeneration() {
        var scheduler = new ManualScheduler();
        var invalidator = new ProjectIndexConfigurationInvalidator(scheduler);
        Object project = new Object();
        var builds = new AtomicInteger();

        for (String digest : List.of("a", "b", "c")) {
            assertTrue(invalidator.invalidate(project, digest,
                    Runnable::run, () -> { },
                    monitor -> builds.incrementAndGet()));
        }

        assertEquals(1, scheduler.scheduleCalls);
        assertEquals(0, builds.get());
        scheduler.run(0);
        assertEquals(1, builds.get());
    }

    @Test
    void forgottenGenerationCannotBuildAfterProjectReopens() {
        var scheduler = new ManualScheduler();
        var invalidator = new ProjectIndexConfigurationInvalidator(scheduler);
        Object project = new Object();
        var builds = new AtomicInteger();

        assertTrue(invalidator.invalidate(project, "a", Runnable::run,
                () -> { }, monitor -> builds.incrementAndGet()));
        invalidator.forget(project);
        assertTrue(invalidator.invalidate(project, "a", Runnable::run,
                () -> { }, monitor -> builds.incrementAndGet()));

        assertTrue(scheduler.tasks.get(0).cancelled);
        scheduler.runEvenIfCancelled(0);
        assertEquals(0, builds.get());
        scheduler.run(1);
        assertEquals(1, builds.get());
    }

    @Test
    void invalidationDoesNotHoldMonitorWhileWaitingForPublicationGate()
            throws Exception {
        Object eventLock = new Object();
        Object project = new Object();
        CountDownLatch eventHeld = new CountDownLatch(1);
        CountDownLatch invalidationWaiting = new CountDownLatch(1);
        var invalidator = new ProjectIndexConfigurationInvalidator(
                (ignored, task) -> () -> { });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> publisher = executor.submit(() -> {
                synchronized (eventLock) {
                    eventHeld.countDown();
                    await(invalidationWaiting);
                    invalidator.scheduleBuild(project,
                            monitor -> { });
                }
            });
            assertTrue(eventHeld.await(5, TimeUnit.SECONDS));

            Future<?> invalidation = executor.submit(() ->
                    invalidator.invalidate(project, "digest",
                            persisted -> {
                                invalidationWaiting.countDown();
                                synchronized (eventLock) {
                                    persisted.run();
                                }
                            }, () -> { }, monitor -> { }));

            publisher.get(5, TimeUnit.SECONDS);
            invalidation.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void schedulerAndCancellationCallbacksMayReenterInvalidator()
            throws Exception {
        Object project = new Object();
        var invalidatorRef =
                new AtomicReference<ProjectIndexConfigurationInvalidator>();
        var scheduledHandle = new AtomicReference<
                ProjectIndexConfigurationInvalidator.ScheduledBuild>();
        var scheduler =
                new ProjectIndexConfigurationInvalidator.BuildScheduler() {

                    @Override
                    public ProjectIndexConfigurationInvalidator.ScheduledBuild
                            schedule(Object ignored,
                                    ProjectIndexConfigurationInvalidator.BuildTask task) {
                        assertReentrantCallCompletes(() ->
                                invalidatorRef.get().observe(
                                        new Object(), "scheduled"));
                        var handle =
                                (ProjectIndexConfigurationInvalidator.ScheduledBuild)
                                        () -> assertReentrantCallCompletes(
                                                () -> invalidatorRef.get()
                                                        .observe(new Object(),
                                                                "cancelled"));
                        scheduledHandle.set(handle);
                        return handle;
                    }
                };
        var invalidator =
                new ProjectIndexConfigurationInvalidator(scheduler);
        invalidatorRef.set(invalidator);

        invalidator.scheduleBuild(project, monitor -> { });
        assertTrue(scheduledHandle.get() != null);
        invalidator.forget(project);
    }

    @Test
    void lateOlderInvalidationSchedulesLatestBuildTask()
            throws Exception {
        var scheduler = new ManualScheduler();
        var invalidator = new ProjectIndexConfigurationInvalidator(scheduler);
        Object project = new Object();
        CountDownLatch olderEntered = new CountDownLatch(1);
        CountDownLatch releaseOlder = new CountDownLatch(1);
        var builds = new ArrayList<String>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> older = executor.submit(() ->
                    invalidator.invalidate(project, "a",
                            persisted -> {
                                olderEntered.countDown();
                                await(releaseOlder);
                                persisted.run();
                            }, () -> { },
                            monitor -> builds.add("a")));
            assertTrue(olderEntered.await(5, TimeUnit.SECONDS));

            Future<?> latest = executor.submit(() ->
                    invalidator.invalidate(project, "b",
                            Runnable::run, () -> { },
                            monitor -> builds.add("b")));
            latest.get(5, TimeUnit.SECONDS);
            releaseOlder.countDown();
            older.get(5, TimeUnit.SECONDS);
        } finally {
            releaseOlder.countDown();
        }

        assertEquals(1, scheduler.scheduleCalls);
        scheduler.run(0);
        assertEquals(List.of("b"), builds);
    }

    @Test
    void failedOlderSchedulingRetriesCoalescedLatestBuild()
            throws Exception {
        Object project = new Object();
        CountDownLatch firstScheduleEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstSchedule = new CountDownLatch(1);
        var scheduleCalls = new AtomicInteger();
        var scheduledTask =
                new AtomicReference<
                        ProjectIndexConfigurationInvalidator.BuildTask>();
        var scheduler =
                new ProjectIndexConfigurationInvalidator.BuildScheduler() {

                    @Override
                    public ProjectIndexConfigurationInvalidator.ScheduledBuild
                            schedule(Object ignored,
                                    ProjectIndexConfigurationInvalidator.BuildTask task) {
                        if (scheduleCalls.incrementAndGet() == 1) {
                            firstScheduleEntered.countDown();
                            await(releaseFirstSchedule);
                            throw new IllegalStateException(
                                    "first scheduling failed");
                        }
                        scheduledTask.set(task);
                        return () -> { };
                    }
                };
        var invalidator =
                new ProjectIndexConfigurationInvalidator(scheduler);
        var builds = new ArrayList<String>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> older = executor.submit(() ->
                    invalidator.invalidate(project, "a",
                            Runnable::run, () -> { },
                            monitor -> builds.add("a")));
            assertTrue(firstScheduleEntered.await(
                    5, TimeUnit.SECONDS));

            Future<?> latest = executor.submit(() ->
                    invalidator.invalidate(project, "b",
                            Runnable::run, () -> { },
                            monitor -> builds.add("b")));
            latest.get(5, TimeUnit.SECONDS);
            releaseFirstSchedule.countDown();
            older.get(5, TimeUnit.SECONDS);
        } finally {
            releaseFirstSchedule.countDown();
        }

        assertEquals(2, scheduleCalls.get());
        assertTrue(scheduledTask.get() != null);
        scheduledTask.get().run(new NullProgressMonitor());
        assertEquals(List.of("b"), builds);
    }

    @Test
    void failedOlderSchedulingPreservesReservedLatestInvalidation()
            throws Exception {
        Object project = new Object();
        CountDownLatch firstScheduleEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstSchedule = new CountDownLatch(1);
        CountDownLatch latestInvalidationEntered =
                new CountDownLatch(1);
        CountDownLatch releaseLatestInvalidation =
                new CountDownLatch(1);
        var scheduleCalls = new AtomicInteger();
        var scheduledTask =
                new AtomicReference<
                        ProjectIndexConfigurationInvalidator.BuildTask>();
        var scheduler =
                new ProjectIndexConfigurationInvalidator.BuildScheduler() {

                    @Override
                    public ProjectIndexConfigurationInvalidator.ScheduledBuild
                            schedule(Object ignored,
                                    ProjectIndexConfigurationInvalidator.BuildTask task) {
                        if (scheduleCalls.incrementAndGet() == 1) {
                            firstScheduleEntered.countDown();
                            await(releaseFirstSchedule);
                            throw new IllegalStateException(
                                    "first scheduling failed");
                        }
                        scheduledTask.set(task);
                        return () -> { };
                    }
                };
        var invalidator =
                new ProjectIndexConfigurationInvalidator(scheduler);
        var builds = new ArrayList<String>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> older = executor.submit(() ->
                    invalidator.invalidate(project, "a",
                            Runnable::run, () -> { },
                            monitor -> builds.add("a")));
            assertTrue(firstScheduleEntered.await(
                    5, TimeUnit.SECONDS));

            Future<?> latest = executor.submit(() ->
                    invalidator.invalidate(project, "b",
                            persisted -> {
                                latestInvalidationEntered.countDown();
                                await(releaseLatestInvalidation);
                                persisted.run();
                            }, () -> { },
                            monitor -> builds.add("b")));
            assertTrue(latestInvalidationEntered.await(
                    5, TimeUnit.SECONDS));

            releaseFirstSchedule.countDown();
            org.junit.jupiter.api.Assertions.assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> older.get(5, TimeUnit.SECONDS));
            releaseLatestInvalidation.countDown();
            latest.get(5, TimeUnit.SECONDS);
        } finally {
            releaseFirstSchedule.countDown();
            releaseLatestInvalidation.countDown();
        }

        assertEquals(2, scheduleCalls.get());
        assertTrue(scheduledTask.get() != null);
        scheduledTask.get().run(new NullProgressMonitor());
        assertEquals(List.of("b"), builds);
    }

    private static void assertReentrantCallCompletes(Runnable action) {
        var failure = new AtomicReference<Throwable>();
        Thread thread = Thread.ofVirtual().start(() -> {
            try {
                action.run();
            } catch (Throwable ex) {
                failure.set(ex);
            }
        });
        try {
            thread.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
        assertFalse(thread.isAlive(),
                "External callback was invoked under invalidator monitor");
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Latch timed out");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }

    private static final class ManualScheduler
            implements ProjectIndexConfigurationInvalidator.BuildScheduler {

        private final List<Scheduled> tasks = new ArrayList<>();
        private int scheduleCalls;

        @Override
        public synchronized ProjectIndexConfigurationInvalidator.ScheduledBuild schedule(
                Object project,
                ProjectIndexConfigurationInvalidator.BuildTask task) {
            scheduleCalls++;
            var scheduled = new Scheduled(task);
            tasks.add(scheduled);
            return () -> scheduled.cancelled = true;
        }

        synchronized void run(int index) {
            Scheduled scheduled = tasks.get(index);
            if (!scheduled.cancelled) {
                runTask(scheduled);
            }
        }

        synchronized void runEvenIfCancelled(int index) {
            runTask(tasks.get(index));
        }

        private static void runTask(Scheduled scheduled) {
            try {
                scheduled.task.run(new NullProgressMonitor());
            } catch (CoreException ex) {
                throw new AssertionError(ex);
            }
        }
    }

    private static final class Scheduled {

        private final ProjectIndexConfigurationInvalidator.BuildTask task;
        private boolean cancelled;

        private Scheduled(
                ProjectIndexConfigurationInvalidator.BuildTask task) {
            this.task = task;
        }
    }
}
