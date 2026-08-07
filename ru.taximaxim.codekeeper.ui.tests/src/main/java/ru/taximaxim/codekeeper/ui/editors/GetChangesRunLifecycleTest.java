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
package ru.taximaxim.codekeeper.ui.editors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import ru.taximaxim.codekeeper.ui.editors.GetChangesRunLifecycle.PublicationOutcome;
import ru.taximaxim.codekeeper.ui.settings.EclipseComparisonTelemetry;
import ru.taximaxim.codekeeper.ui.settings.EclipseComparisonTelemetry.RunOutcome;
import ru.taximaxim.codekeeper.ui.settings.EclipseComparisonTelemetry.UiStage;

class GetChangesRunLifecycleTest {

    @Test
    void successWaitsForBothJobAndUiPublication() {
        var recorder = new Recorder();
        var lifecycle = recorder.lifecycle();

        lifecycle.jobStarted();
        lifecycle.jobFinished(
                GetChangesRunLifecycle.JobOutcome.SUCCESS);

        assertTrue(recorder.outcomes.isEmpty());
        assertTrue(lifecycle.queueUiPublication());
        assertTrue(lifecycle.publishUi(
                () -> PublicationOutcome.ACCEPTED));
        assertEquals(List.of(RunOutcome.SUCCESS), recorder.outcomes);
        assertEquals(1, recorder.count(UiStage.RUN_STARTED));
        assertEquals(1, recorder.count(UiStage.UI_QUEUE));
    }

    @Test
    void publisherRejectionIsNotReportedAsSuccessOrSuperseded() {
        var recorder = new Recorder();
        var lifecycle = recorder.lifecycle();

        lifecycle.jobStarted();
        assertTrue(lifecycle.queueUiPublication());
        assertTrue(lifecycle.publishUi(
                () -> PublicationOutcome.REJECTED));
        lifecycle.jobFinished(
                GetChangesRunLifecycle.JobOutcome.SUCCESS);

        assertEquals(List.of(RunOutcome.REJECTED),
                recorder.outcomes);

        lifecycle.jobFinished(
                GetChangesRunLifecycle.JobOutcome.SUCCESS);
        lifecycle.uiPublicationRejected();
        assertEquals(1, recorder.outcomes.size(),
                "terminal rejection must be published exactly once");
    }

    @Test
    void cancellationWinsOverAnInProgressPublisherRejection()
            throws Exception {
        var recorder = new Recorder();
        var lifecycle = recorder.lifecycle();
        lifecycle.jobStarted();
        assertTrue(lifecycle.queueUiPublication());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var publication = executor.submit(
                    () -> lifecycle.publishUi(() -> {
                        entered.countDown();
                        await(release);
                        return PublicationOutcome.REJECTED;
                    }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            lifecycle.cancelRequested();
            lifecycle.jobFinished(
                    GetChangesRunLifecycle.JobOutcome.SUCCESS);
            release.countDown();
            assertTrue(publication.get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }

        assertEquals(List.of(RunOutcome.CANCELLED),
                recorder.outcomes);
    }

    @Test
    void supersedeWinsOverPublisherRejection() {
        var recorder = new Recorder();
        var lifecycle = recorder.lifecycle();
        lifecycle.jobStarted();
        assertTrue(lifecycle.queueUiPublication());

        assertTrue(lifecycle.publishUi(() -> {
            lifecycle.supersede();
            return PublicationOutcome.REJECTED;
        }));
        lifecycle.jobFinished(
                GetChangesRunLifecycle.JobOutcome.SUCCESS);

        assertEquals(List.of(RunOutcome.SUPERSEDED),
                recorder.outcomes);
    }

    @Test
    void jobFailureFinishesWithoutUiPublication() {
        var recorder = new Recorder();
        var lifecycle = recorder.lifecycle();

        lifecycle.jobStarted();
        lifecycle.jobFinished(
                GetChangesRunLifecycle.JobOutcome.FAILED);

        assertEquals(List.of(RunOutcome.FAILED), recorder.outcomes);
    }

    @Test
    void cancelWaitsForJobDrain() {
        var recorder = new Recorder();
        var lifecycle = recorder.lifecycle();

        lifecycle.jobStarted();
        lifecycle.cancelRequested();
        assertTrue(recorder.outcomes.isEmpty());

        lifecycle.jobFinished(
                GetChangesRunLifecycle.JobOutcome.CANCELLED);
        assertEquals(List.of(RunOutcome.CANCELLED), recorder.outcomes);
    }

    @Test
    void cancelAfterJobDrainFinishesImmediately() {
        var recorder = new Recorder();
        var lifecycle = recorder.lifecycle();

        lifecycle.jobStarted();
        lifecycle.jobFinished(
                GetChangesRunLifecycle.JobOutcome.SUCCESS);
        assertTrue(recorder.outcomes.isEmpty());

        lifecycle.cancelRequested();
        assertEquals(List.of(RunOutcome.CANCELLED), recorder.outcomes);
    }

    @Test
    void staleUiPublicationBecomesSuperseded() {
        var recorder = new Recorder();
        var lifecycle = recorder.lifecycle();

        lifecycle.jobStarted();
        assertTrue(lifecycle.queueUiPublication());
        lifecycle.uiPublicationRejected();
        assertTrue(recorder.outcomes.isEmpty());

        lifecycle.jobFinished(
                GetChangesRunLifecycle.JobOutcome.SUCCESS);
        assertEquals(List.of(RunOutcome.SUPERSEDED), recorder.outcomes);
    }

    @Test
    void resetDuringSuccessfulQueuedUiWorkCannotPublishSuccess() {
        var recorder = new Recorder();
        var lifecycle = recorder.lifecycle();
        var published = new AtomicBoolean();

        lifecycle.jobStarted();
        assertTrue(lifecycle.queueUiPublication());
        lifecycle.jobFinished(
                GetChangesRunLifecycle.JobOutcome.SUCCESS);
        lifecycle.supersede();

        int eventsAtTerminal = recorder.events.size();
        assertFalse(lifecycle.publishUi(() -> {
            published.set(true);
            return PublicationOutcome.ACCEPTED;
        }));
        assertFalse(published.get());
        assertEquals(List.of(RunOutcome.SUPERSEDED), recorder.outcomes);
        assertEquals(eventsAtTerminal, recorder.events.size(),
                "a stale queued callback must emit nothing after terminal");
        assertTrue(recorder.events.indexOf("stage:UI_QUEUE")
                < recorder.events.indexOf("outcome:SUPERSEDED"));
    }

    @Test
    void concurrentDoneAndUiCallbacksProduceOneTerminalEvent()
            throws Exception {
        var recorder = new Recorder();
        var lifecycle = recorder.lifecycle();
        lifecycle.jobStarted();
        assertTrue(lifecycle.queueUiPublication());
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var jobDone = executor.submit(() -> {
                ready.countDown();
                await(start);
                lifecycle.jobFinished(
                        GetChangesRunLifecycle.JobOutcome.SUCCESS);
            });
            var uiDone = executor.submit(() -> {
                ready.countDown();
                await(start);
                lifecycle.publishUi(
                        () -> PublicationOutcome.ACCEPTED);
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            jobDone.get(5, TimeUnit.SECONDS);
            uiDone.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertEquals(List.of(RunOutcome.SUCCESS), recorder.outcomes);
    }

    @Test
    void queueTimerClosesWhenJobCancelledBeforeRun() {
        var recorder = new Recorder();
        var lifecycle = recorder.lifecycle();

        lifecycle.cancelRequested();
        lifecycle.jobFinished(
                GetChangesRunLifecycle.JobOutcome.CANCELLED);
        lifecycle.jobStarted();

        assertEquals(1, recorder.count(UiStage.RUN_STARTED));
        assertEquals(1, recorder.count(UiStage.JOB_QUEUE));
        assertEquals(List.of(RunOutcome.CANCELLED), recorder.outcomes);
    }

    @Test
    void uiPublicationExceptionBecomesFailedAfterJobFinished() {
        var recorder = new Recorder();
        var lifecycle = recorder.lifecycle();
        lifecycle.jobStarted();
        assertTrue(lifecycle.queueUiPublication());

        assertThrows(IllegalStateException.class,
                () -> lifecycle.publishUi(() -> {
                    throw new IllegalStateException("expected");
                }));
        assertTrue(recorder.outcomes.isEmpty());

        lifecycle.jobFinished(
                GetChangesRunLifecycle.JobOutcome.SUCCESS);
        assertEquals(List.of(RunOutcome.FAILED), recorder.outcomes);
    }

    @Test
    void cancelWaitsForInProgressUiPublication() throws Exception {
        var recorder = new Recorder();
        var lifecycle = recorder.lifecycle();
        lifecycle.jobStarted();
        assertTrue(lifecycle.queueUiPublication());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var publication = executor.submit(() -> lifecycle.publishUi(() -> {
                entered.countDown();
                await(release);
                return PublicationOutcome.ACCEPTED;
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            lifecycle.jobFinished(
                    GetChangesRunLifecycle.JobOutcome.SUCCESS);
            lifecycle.cancelRequested();
            assertTrue(recorder.outcomes.isEmpty());

            release.countDown();
            assertTrue(publication.get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }

        assertEquals(List.of(RunOutcome.CANCELLED), recorder.outcomes);
        assertTrue(recorder.events.indexOf("stage:UI_PUBLISH")
                < recorder.events.indexOf("outcome:CANCELLED"));
    }

    @Test
    void uiFailureWinsOverConcurrentCancellation() throws Exception {
        var recorder = new Recorder();
        var lifecycle = recorder.lifecycle();
        lifecycle.jobStarted();
        assertTrue(lifecycle.queueUiPublication());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var publication = executor.submit(() -> lifecycle.publishUi(() -> {
                entered.countDown();
                await(release);
                throw new IllegalStateException("expected");
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            lifecycle.jobFinished(
                    GetChangesRunLifecycle.JobOutcome.SUCCESS);
            lifecycle.cancelRequested();
            assertTrue(recorder.outcomes.isEmpty());

            release.countDown();
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> publication.get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }

        assertEquals(List.of(RunOutcome.FAILED), recorder.outcomes);
        assertTrue(recorder.events.indexOf("stage:UI_PUBLISH")
                < recorder.events.indexOf("outcome:FAILED"));
    }

    @Test
    void cancellationLogicallyDrainsQueuedCallbackBeforeTerminal() {
        var recorder = new Recorder();
        var lifecycle = recorder.lifecycle();
        var published = new AtomicBoolean();
        lifecycle.jobStarted();
        assertTrue(lifecycle.queueUiPublication());

        lifecycle.cancelRequested();
        lifecycle.jobFinished(
                GetChangesRunLifecycle.JobOutcome.CANCELLED);

        int eventsAtTerminal = recorder.events.size();
        assertFalse(lifecycle.publishUi(() -> {
            published.set(true);
            return PublicationOutcome.ACCEPTED;
        }));
        assertFalse(published.get());
        assertEquals(eventsAtTerminal, recorder.events.size());
        assertEquals(List.of(RunOutcome.CANCELLED), recorder.outcomes);
        assertTrue(recorder.events.indexOf("stage:UI_QUEUE")
                < recorder.events.indexOf("outcome:CANCELLED"));
    }

    @Test
    void coordinatorRetainsCompletedJobUntilQueuedUiIsDisposed() {
        var recorder = new Recorder();
        var coordinator = new GetChangesJobCoordinator();
        var requestRef =
                new AtomicReference<GetChangesJobCoordinator.Request>();
        var completions = new AtomicInteger();
        var lifecycle = recorder.lifecycle(() -> {
            completions.incrementAndGet();
            requestRef.get().complete();
        });
        var request = coordinator.start(lifecycle::supersede);
        requestRef.set(request);
        lifecycle.jobStarted();
        assertTrue(lifecycle.queueUiPublication());
        lifecycle.jobFinished(
                GetChangesRunLifecycle.JobOutcome.SUCCESS);

        coordinator.cancel();

        assertEquals(List.of(RunOutcome.SUPERSEDED), recorder.outcomes);
        assertEquals(1, completions.get());
        assertFalse(lifecycle.publishUi(() -> {
            throw new AssertionError("disposed callback must not publish");
        }));
        assertEquals(1, completions.get());
    }

    @Test
    void unexpectedUiEnqueueFailureDrainsQueueAsFailed() {
        var recorder = new Recorder();
        var completions = new AtomicInteger();
        var lifecycle = recorder.lifecycle(completions::incrementAndGet);
        lifecycle.jobStarted();
        assertTrue(lifecycle.queueUiPublication());

        lifecycle.uiPublicationFailed();
        lifecycle.jobFinished(
                GetChangesRunLifecycle.JobOutcome.FAILED);

        assertEquals(List.of(RunOutcome.FAILED), recorder.outcomes);
        assertEquals(1, completions.get());
        assertTrue(recorder.events.indexOf("stage:UI_QUEUE")
                < recorder.events.indexOf("outcome:FAILED"));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void abandoningADroppedUiCallbackTerminatesTheRun() {
        var recorder = new Recorder();
        var released = new AtomicBoolean();
        var lifecycle = recorder.lifecycle(() -> released.set(true));

        lifecycle.jobStarted();
        assertTrue(lifecycle.queueUiPublication());
        lifecycle.jobFinished(
                GetChangesRunLifecycle.JobOutcome.SUCCESS);

        assertTrue(recorder.outcomes.isEmpty(),
                "a queued publication still owns the run");
        assertFalse(released.get());

        // The display died with the callback still queued.
        lifecycle.abandon();

        assertEquals(List.of(RunOutcome.SUPERSEDED), recorder.outcomes);
        assertTrue(released.get(),
                "abandoning must release everything the run holds");

        assertFalse(lifecycle.publishUi(
                () -> PublicationOutcome.ACCEPTED));
        assertEquals(1, recorder.outcomes.size());
    }

    @Test
    void terminalCallbackDoesNotRunUnderTheLifecycleMonitor()
            throws Exception {
        var recorder = new Recorder();
        var reentered = new AtomicBoolean();
        var lifecycleRef =
                new AtomicReference<GetChangesRunLifecycle>();
        var lifecycle = recorder.lifecycle(() -> {
            // The real callback takes the coordinator monitor, which other
            // threads take before this monitor. Holding this one here would
            // invert the lock order.
            var executor = Executors.newSingleThreadExecutor();
            try {
                reentered.set(executor.submit(() -> {
                    lifecycleRef.get().supersede();
                    return true;
                }).get(5, TimeUnit.SECONDS));
            } catch (Exception ex) {
                reentered.set(false);
            } finally {
                executor.shutdownNow();
            }
        });
        lifecycleRef.set(lifecycle);

        lifecycle.jobStarted();
        lifecycle.jobFinished(
                GetChangesRunLifecycle.JobOutcome.CANCELLED);

        assertTrue(reentered.get(),
                "another thread must be able to enter the lifecycle");
        assertEquals(List.of(RunOutcome.CANCELLED), recorder.outcomes);
    }

    private static final class Recorder {

        private final List<UiStage> stages = new CopyOnWriteArrayList<>();
        private final List<RunOutcome> outcomes = new CopyOnWriteArrayList<>();
        private final List<String> events = new CopyOnWriteArrayList<>();
        private final AtomicLong clock = new AtomicLong();
        private final EclipseComparisonTelemetry telemetry =
                mock(EclipseComparisonTelemetry.class);

        private Recorder() {
            when(telemetry.startTimer()).thenAnswer(
                    ignored -> clock.incrementAndGet());
            doAnswer(invocation -> {
                UiStage stage = invocation.getArgument(0);
                stages.add(stage);
                events.add("stage:" + stage);
                return null;
            }).when(telemetry).uiStageFinished(any(UiStage.class), anyLong());
            doAnswer(invocation -> {
                RunOutcome outcome = invocation.getArgument(0);
                outcomes.add(outcome);
                events.add("outcome:" + outcome);
                return null;
            }).when(telemetry).runFinished(any(RunOutcome.class), anyLong());
        }

        private GetChangesRunLifecycle lifecycle() {
            return new GetChangesRunLifecycle(telemetry);
        }

        private GetChangesRunLifecycle lifecycle(Runnable terminalCallback) {
            return new GetChangesRunLifecycle(telemetry, terminalCallback);
        }

        private long count(UiStage stage) {
            return stages.stream().filter(stage::equals).count();
        }
    }
}
