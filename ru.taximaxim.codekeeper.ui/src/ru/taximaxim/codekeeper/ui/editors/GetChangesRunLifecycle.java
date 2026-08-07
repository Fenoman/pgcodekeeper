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

import java.util.Objects;
import java.util.function.Supplier;

import ru.taximaxim.codekeeper.ui.settings.EclipseComparisonTelemetry;
import ru.taximaxim.codekeeper.ui.settings.EclipseComparisonTelemetry.RunOutcome;
import ru.taximaxim.codekeeper.ui.settings.EclipseComparisonTelemetry.UiStage;

final class GetChangesRunLifecycle {

    enum JobOutcome {
        SUCCESS,
        FAILED,
        CANCELLED
    }

    enum PublicationOutcome {
        ACCEPTED,
        REJECTED
    }

    private enum UiPublication {
        PENDING,
        QUEUED,
        IN_PROGRESS,
        ACCEPTED,
        REJECTED,
        FAILED
    }

    private final EclipseComparisonTelemetry telemetry;
    private final Runnable terminalCallback;
    private final long runStartNanos;
    private final long jobQueueStartNanos;

    private UiPublication uiPublication = UiPublication.PENDING;
    private long uiQueueStartNanos;
    private JobOutcome jobOutcome;
    private boolean jobQueueFinished;
    private boolean jobFinished;
    private boolean cancellationRequested;
    private boolean superseded;
    private boolean terminalPublished;

    GetChangesRunLifecycle(EclipseComparisonTelemetry telemetry) {
        this(telemetry, () -> { });
    }

    GetChangesRunLifecycle(EclipseComparisonTelemetry telemetry,
            Runnable terminalCallback) {
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry"); //$NON-NLS-1$
        this.terminalCallback = Objects.requireNonNull(
                terminalCallback, "terminalCallback"); //$NON-NLS-1$
        runStartNanos = telemetry.startTimer();
        jobQueueStartNanos = runStartNanos;
        telemetry.uiStageFinished(UiStage.RUN_STARTED, runStartNanos);
    }

    synchronized void jobStarted() {
        finishJobQueue();
    }

    void jobFinished(JobOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome"); //$NON-NLS-1$
        Runnable terminal;
        synchronized (this) {
            finishJobQueue();
            if (jobFinished) {
                return;
            }
            jobFinished = true;
            jobOutcome = outcome;
            terminal = publishTerminalIfReady();
        }
        runTerminal(terminal);
    }

    void cancelRequested() {
        Runnable terminal;
        synchronized (this) {
            if (terminalPublished) {
                return;
            }
            cancellationRequested = true;
            rejectQueuedPublication();
            terminal = publishTerminalIfReady();
        }
        runTerminal(terminal);
    }

    void uiPublicationRejected() {
        runTerminal(markSuperseded());
    }

    void uiPublicationFailed() {
        Runnable terminal;
        synchronized (this) {
            if (terminalPublished) {
                return;
            }
            if (uiPublication == UiPublication.QUEUED) {
                finishUiQueue();
            }
            if (uiPublication == UiPublication.PENDING
                    || uiPublication == UiPublication.QUEUED) {
                uiPublication = UiPublication.FAILED;
            }
            terminal = publishTerminalIfReady();
        }
        runTerminal(terminal);
    }

    void supersede() {
        runTerminal(markSuperseded());
    }

    /**
     * Terminates a run whose queued UI publication can no longer be delivered,
     * for example when the editor is disposed while its callback still waits in
     * the display queue. A dropped callback would otherwise keep the run in
     * {@link UiPublication#QUEUED} forever and leak everything the terminal
     * callback releases.
     */
    void abandon() {
        supersede();
    }

    synchronized boolean queueUiPublication() {
        if (terminalPublished || superseded || cancellationRequested
                || uiPublication != UiPublication.PENDING) {
            return false;
        }
        uiQueueStartNanos = telemetry.startTimer();
        uiPublication = UiPublication.QUEUED;
        return true;
    }

    boolean publishUi(Supplier<PublicationOutcome> publisher) {
        Objects.requireNonNull(publisher, "publisher"); //$NON-NLS-1$
        Runnable rejectedTerminal = null;
        boolean started;
        synchronized (this) {
            if (terminalPublished
                    || uiPublication != UiPublication.QUEUED) {
                return false;
            }
            started = !superseded && !cancellationRequested;
            if (started) {
                finishUiQueue();
                uiPublication = UiPublication.IN_PROGRESS;
            } else {
                rejectQueuedPublication();
                rejectedTerminal = publishTerminalIfReady();
            }
        }
        if (!started) {
            runTerminal(rejectedTerminal);
            return false;
        }

        long publishStartNanos = telemetry.startTimer();
        Throwable failure = null;
        PublicationOutcome publicationOutcome = null;
        try {
            publicationOutcome = Objects.requireNonNull(
                    publisher.get(), "publicationOutcome"); //$NON-NLS-1$
        } catch (RuntimeException | Error ex) {
            failure = ex;
        } finally {
            telemetry.uiStageFinished(UiStage.UI_PUBLISH,
                    publishStartNanos);
        }

        Runnable terminal;
        synchronized (this) {
            uiPublication = failure != null
                    ? UiPublication.FAILED
                    : publicationOutcome
                            == PublicationOutcome.ACCEPTED
                                    ? UiPublication.ACCEPTED
                                    : UiPublication.REJECTED;
            terminal = publishTerminalIfReady();
        }
        runTerminal(terminal);

        if (failure instanceof RuntimeException ex) {
            throw ex;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return true;
    }

    private Runnable markSuperseded() {
        synchronized (this) {
            if (terminalPublished) {
                return null;
            }
            superseded = true;
            rejectQueuedPublication();
            return publishTerminalIfReady();
        }
    }

    private void rejectQueuedPublication() {
        if (uiPublication == UiPublication.QUEUED) {
            finishUiQueue();
            uiPublication = UiPublication.REJECTED;
        } else if (uiPublication == UiPublication.PENDING) {
            uiPublication = UiPublication.REJECTED;
        }
    }

    private void finishUiQueue() {
        telemetry.uiStageFinished(UiStage.UI_QUEUE, uiQueueStartNanos);
    }

    private void finishJobQueue() {
        if (!jobQueueFinished) {
            jobQueueFinished = true;
            telemetry.uiStageFinished(UiStage.JOB_QUEUE,
                    jobQueueStartNanos);
        }
    }

    /**
     * @return the terminal action to run after this monitor is released, or
     *         {@code null} when the run is not terminal yet
     */
    private Runnable publishTerminalIfReady() {
        if (terminalPublished || !jobFinished
                || uiPublication == UiPublication.QUEUED
                || uiPublication == UiPublication.IN_PROGRESS) {
            return null;
        }

        RunOutcome outcome;
        if (uiPublication == UiPublication.FAILED) {
            outcome = RunOutcome.FAILED;
        } else if (superseded) {
            outcome = RunOutcome.SUPERSEDED;
        } else if (jobOutcome == JobOutcome.FAILED) {
            outcome = RunOutcome.FAILED;
        } else if (jobOutcome == JobOutcome.CANCELLED
                || cancellationRequested) {
            outcome = RunOutcome.CANCELLED;
        } else if (uiPublication == UiPublication.ACCEPTED) {
            outcome = RunOutcome.SUCCESS;
        } else if (uiPublication == UiPublication.REJECTED) {
            outcome = RunOutcome.REJECTED;
        } else {
            return null;
        }

        terminalPublished = true;
        RunOutcome terminalOutcome = outcome;
        return () -> {
            try {
                telemetry.runFinished(terminalOutcome, runStartNanos);
            } finally {
                terminalCallback.run();
            }
        };
    }

    /**
     * Runs a terminal action outside this monitor. The callback releases
     * coordinator state, so holding the lifecycle monitor across it would
     * invert the lock order against the publication path and can deadlock.
     */
    private static void runTerminal(Runnable terminal) {
        if (terminal != null) {
            terminal.run();
        }
    }
}
