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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.runtime.OperationCanceledException;
import org.junit.jupiter.api.Test;

import ru.taximaxim.codekeeper.ui.pgdbproject.parser.ProjectIndexBuildSupersededException;

class BuilderPublicationGateTest {

    @Test
    void changedProjectInputsSupersedeBuildWithoutDatabaseFailure() {
        assertTrue(ProjectBuilder.isSupersededBuildFailure(
                new ProjectIndexBuildSupersededException(
                        "Project inputs changed during full build"))); //$NON-NLS-1$
        assertFalse(ProjectBuilder.isSupersededBuildFailure(
                new IOException("disk failure"))); //$NON-NLS-1$
        assertFalse(ProjectBuilder.isSupersededBuildFailure(
                new IOException("disk failure", //$NON-NLS-1$
                        new ProjectIndexBuildSupersededException(
                                "older superseded build")))); //$NON-NLS-1$
    }

    @Test
    void multiFileBatchReachesIncrementalPreparationAsOneProof() {
        var batch = ProjectBuildDeltaClassifier.classify(List.of(
                changedSql("SCHEMA/app/TABLE/first.sql"), //$NON-NLS-1$
                changedSql("SCHEMA/app/TABLE/second.sql")), //$NON-NLS-1$
                false);

        assertEquals(ProjectBuildDeltaClassifier.Mode.INCREMENTAL,
                batch.mode());
        assertNull(batch.relativePath());

        var continuity = new ProjectBuildContinuity();
        ProjectBuilder.BuildStart reconciliation =
                ProjectBuilder.beginClassifiedBuild(
                        continuity, 1,
                        ProjectBuildDeltaClassifier.full());
        assertTrue(continuity.accept(reconciliation.proof()));
        ProjectBuilder.BuildStart adapted =
                ProjectBuilder.beginClassifiedBuild(
                        continuity, 2, batch);

        assertEquals(ProjectBuildDeltaClassifier.Mode.INCREMENTAL,
                adapted.classified().mode());
        assertEquals(List.of(
                "SCHEMA/app/TABLE/first.sql", //$NON-NLS-1$
                "SCHEMA/app/TABLE/second.sql"), //$NON-NLS-1$
                adapted.classified().relativePaths());
        assertEquals(ProjectBuildContinuity.Kind.RECONCILIATION,
                reconciliation.proof().kind());
        assertEquals(ProjectBuildContinuity.Kind.INCREMENTAL,
                adapted.proof().kind());
        assertTrue(adapted.proof().permitsIncremental(
                batch.relativePaths()));
    }

    @Test
    void generationAndContinuityProofAreStartedAtomically()
            throws Exception {
        var gate = new BuilderPublicationGate();
        var continuity = new ProjectBuildContinuity();
        var firstEntered = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var secondReady = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> gate.start(generation -> {
                firstEntered.countDown();
                await(releaseFirst);
                return continuity.beginReconciliation(generation);
            }));
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));

            var second = executor.submit(() -> {
                secondReady.countDown();
                return gate.start(
                        continuity::beginReconciliation);
            });
            assertTrue(secondReady.await(5, TimeUnit.SECONDS));
            assertFalse(second.isDone());

            releaseFirst.countDown();
            var firstStarted = first.get(5, TimeUnit.SECONDS);
            var secondStarted = second.get(5, TimeUnit.SECONDS);

            assertEquals(1, firstStarted.generation());
            assertEquals(2, secondStarted.generation());
            assertFalse(firstStarted.value().isCurrent());
            assertTrue(secondStarted.value().isCurrent());
            assertTrue(continuity.accept(secondStarted.value()));
            assertFalse(continuity.invalidate(firstStarted.value()));
        }
    }

    @Test
    void cancelledGenerationCannotPublish() throws Exception {
        var gate = new BuilderPublicationGate();
        long generation = gate.start();
        var publications = new AtomicInteger();

        gate.cancel(generation, () -> { });

        assertFalse(gate.publish(generation, publications::incrementAndGet));
        assertEquals(0, publications.get());
    }

    @Test
    void supersededGenerationCannotPublish() throws Exception {
        var gate = new BuilderPublicationGate();
        long staleGeneration = gate.start();
        long currentGeneration = gate.start();
        var publications = new AtomicInteger();

        assertFalse(gate.publish(staleGeneration, publications::incrementAndGet));
        assertTrue(gate.publish(currentGeneration, publications::incrementAndGet));
        assertEquals(1, publications.get());
    }

    @Test
    void stalePreparedBuildCannotPublishPackedIndex() throws Exception {
        var gate = new BuilderPublicationGate();
        long staleGeneration = gate.start();
        gate.start();
        var publications = new AtomicInteger();
        var diskPublications = new AtomicInteger();

        assertFalse(ProjectBuilder.executeGeneration(gate, staleGeneration, null,
                () -> new ProjectBuilder.PreparedUpdate(false,
                        () -> {
                            diskPublications.incrementAndGet();
                            publications.incrementAndGet();
                        }, () -> { }),
                () -> { }));
        assertEquals(0, publications.get());
        assertEquals(0, diskPublications.get());
    }

    @Test
    void stalePreparedBuildDiscardsOwnedPackedView() throws Exception {
        var gate = new BuilderPublicationGate();
        long staleGeneration = gate.start();
        gate.start();
        var publications = new AtomicInteger();
        var discards = new AtomicInteger();

        assertFalse(ProjectBuilder.executeGeneration(gate, staleGeneration, null,
                () -> new ProjectBuilder.PreparedUpdate(true,
                        () -> { }, publications::incrementAndGet,
                        discards::incrementAndGet),
                () -> { }));

        assertEquals(0, publications.get());
        assertEquals(1, discards.get());
    }

    @Test
    void staleCancellationDoesNotInvalidateCurrentGeneration() throws Exception {
        var gate = new BuilderPublicationGate();
        long staleGeneration = gate.start();
        long currentGeneration = gate.start();
        var publications = new AtomicInteger();
        var cancellations = new AtomicInteger();

        gate.cancel(staleGeneration, cancellations::incrementAndGet);

        assertTrue(gate.publish(currentGeneration, publications::incrementAndGet));
        assertEquals(1, publications.get());
        assertEquals(0, cancellations.get());
    }

    @Test
    void warmOpenPublishesWithoutDiskRewrite() throws Exception {
        var gate = new BuilderPublicationGate();
        long generation = gate.start();
        var publications = new AtomicInteger();
        var diskPublications = new AtomicInteger();

        assertTrue(ProjectBuilder.executeGeneration(gate, generation, null,
                () -> new ProjectBuilder.PreparedUpdate(true,
                        diskPublications::incrementAndGet,
                        publications::incrementAndGet),
                () -> { }));
        assertEquals(1, publications.get());
        assertEquals(0, diskPublications.get());
    }

    @Test
    void changedBuildPublishesDiskAndLiveAsOneGeneration() throws Exception {
        var gate = new BuilderPublicationGate();
        long generation = gate.start();
        var publications = new AtomicInteger();
        var diskPublications = new AtomicInteger();

        assertTrue(ProjectBuilder.executeGeneration(gate, generation, null,
                () -> new ProjectBuilder.PreparedUpdate(false,
                        () -> {
                            diskPublications.incrementAndGet();
                            publications.incrementAndGet();
                        }, () -> { }),
                () -> { }));
        assertEquals(1, publications.get());
        assertEquals(1, diskPublications.get());
    }

    @Test
    void actualParserContinuityPublicationAcceptsProof()
            throws Exception {
        var gate = new BuilderPublicationGate();
        var continuity = new ProjectBuildContinuity();
        var started = gate.start(
                continuity::beginReconciliation);
        var accepted = new AtomicBoolean();

        assertTrue(ProjectBuilder.executeGeneration(
                gate, started.generation(), null,
                () -> new ProjectBuilder.PreparedUpdate(false,
                        () -> accepted.set(true), () -> { },
                        () -> { }, accepted::get),
                () -> { }, continuity, started.value()));

        var single = continuity.beginSingleFile(
                started.generation() + 1,
                "SCHEMA/app/TABLE/item.sql"); //$NON-NLS-1$
        assertTrue(single.permitsIncremental());
        assertTrue(single.descendsFrom(started.value()));
    }

    @Test
    void promotedFullPublicationAcceptsCurrentSingleProof()
            throws Exception {
        var gate = new BuilderPublicationGate();
        var continuity = new ProjectBuildContinuity();
        var reconciliation = gate.start(
                continuity::beginReconciliation);
        assertTrue(continuity.accept(reconciliation.value()));
        var single = gate.start(generation ->
                continuity.beginSingleFile(generation,
                        "SCHEMA/app/TABLE/item.sql")); //$NON-NLS-1$

        assertTrue(ProjectBuilder.executeGeneration(
                gate, single.generation(), null,
                () -> new ProjectBuilder.PreparedUpdate(false,
                        () -> { }, () -> { }, () -> { },
                        () -> true),
                () -> { }, continuity, single.value()));

        var next = continuity.beginSingleFile(
                single.generation() + 1,
                "SCHEMA/app/TABLE/next.sql"); //$NON-NLS-1$
        assertTrue(next.descendsFrom(single.value()));
        assertFalse(next.descendsFrom(
                reconciliation.value()));
    }

    @Test
    void normalReturnWithoutParserContinuityPublicationRevokesProof()
            throws Exception {
        var gate = new BuilderPublicationGate();
        var continuity = new ProjectBuildContinuity();
        var started = gate.start(
                continuity::beginReconciliation);

        assertTrue(ProjectBuilder.executeGeneration(
                gate, started.generation(), null,
                () -> new ProjectBuilder.PreparedUpdate(false,
                        () -> { }, () -> { }, () -> { },
                        () -> false),
                () -> { }, continuity, started.value()));

        var next = continuity.beginSingleFile(
                started.generation() + 1,
                "SCHEMA/app/TABLE/item.sql"); //$NON-NLS-1$
        assertEquals(ProjectBuildContinuity.Kind.RECONCILIATION,
                next.kind());
        assertFalse(next.permitsIncremental());
    }

    @Test
    void noOpPublicationAdvancesContinuityWithoutParser()
            throws Exception {
        var gate = new BuilderPublicationGate();
        var continuity = new ProjectBuildContinuity();
        var reconciliation = gate.start(
                continuity::beginReconciliation);
        assertTrue(continuity.accept(reconciliation.value()));
        var noOp = gate.start(continuity::beginNoOp);

        assertTrue(ProjectBuilder.executeGeneration(
                gate, noOp.generation(), null,
                ProjectBuilder.PreparedUpdate::noOp, () -> { },
                continuity, noOp.value()));

        var single = continuity.beginSingleFile(
                noOp.generation() + 1,
                "SCHEMA/app/TABLE/item.sql"); //$NON-NLS-1$
        assertTrue(single.descendsFrom(
                reconciliation.value()));
    }

    @Test
    void trueNoOpConsumesGenerationWithoutAnyPublicationCallback()
            throws Exception {
        var gate = new BuilderPublicationGate();
        long generation = gate.start();

        assertTrue(ProjectBuilder.executeGeneration(gate, generation, null,
                ProjectBuilder.PreparedUpdate::noOp, () -> {
                    throw new AssertionError("no-op build was cancelled");
                }));
    }

    @Test
    void trueNoOpDoesNotAcquireParser() {
        AtomicInteger parserFactoryCalls = new AtomicInteger();

        Object parser = ProjectBuilder.parserUnlessNoOp(
                new ProjectBuildDeltaClassifier.Result(
                        ProjectBuildDeltaClassifier.Mode.NO_OP,
                        List.of()),
                () -> {
                    parserFactoryCalls.incrementAndGet();
                    return new Object();
                });

        assertNull(parser);
        assertEquals(0, parserFactoryCalls.get());
    }

    @Test
    void fullLibraryPreparationFailureDiscardsPreparedIndex() {
        AtomicInteger discards = new AtomicInteger();
        var prepared = new ProjectBuilder.PreparedUpdate(false,
                () -> { }, () -> { }, discards::incrementAndGet);

        assertThrows(IOException.class,
                () -> ProjectBuilder.prepareLibraries(prepared,
                        () -> {
                            throw new IOException(
                                    "library preparation failed");
                        }));
        assertEquals(1, discards.get());
    }

    @Test
    void interruptedPreparationCancelsLoaderAndInvalidatesGeneration() throws Exception {
        var gate = new BuilderPublicationGate();
        long generation = gate.start();
        var cancellations = new AtomicInteger();
        var diskPublications = new AtomicInteger();

        org.junit.jupiter.api.Assertions.assertThrows(InterruptedException.class,
                () -> ProjectBuilder.executeGeneration(gate, generation, null,
                        () -> { throw new InterruptedException("cancelled"); },
                        cancellations::incrementAndGet));

        assertEquals(1, cancellations.get());
        assertEquals(0, diskPublications.get());
        assertFalse(gate.publish(generation, () -> { }));
        Thread.interrupted();
    }

    @Test
    void monitorCancellationDoesNotInterruptBuilderWorker() {
        Thread.interrupted();
        var monitor =
                new org.eclipse.core.runtime.NullProgressMonitor();
        monitor.setCanceled(true);

        OperationCanceledException cancelled =
                ProjectBuilder.cancellationFromInterruption(
                        new InterruptedException(
                                "monitor cancellation"),
                        monitor);

        assertFalse(Thread.currentThread().isInterrupted());
        assertInstanceOf(InterruptedException.class,
                cancelled.getCause());
    }

    @Test
    void externalInterruptionPreservesBuilderWorkerFlag() {
        Thread.interrupted();
        try {
            OperationCanceledException cancelled =
                    ProjectBuilder.cancellationFromInterruption(
                            new InterruptedException(
                                    "external interrupt"),
                            null);

            assertTrue(Thread.currentThread().isInterrupted());
            assertInstanceOf(InterruptedException.class,
                    cancelled.getCause());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void cancelledMonitorPreventsPreparedPublication() {
        var gate = new BuilderPublicationGate();
        long generation = gate.start();
        var monitor = new org.eclipse.core.runtime.NullProgressMonitor();
        monitor.setCanceled(true);
        var publications = new AtomicInteger();
        var cancellations = new AtomicInteger();

        org.junit.jupiter.api.Assertions.assertThrows(OperationCanceledException.class,
                () -> ProjectBuilder.executeGeneration(gate, generation, monitor,
                        () -> new ProjectBuilder.PreparedUpdate(false,
                                () -> { }, publications::incrementAndGet),
                        cancellations::incrementAndGet));

        assertEquals(0, publications.get());
        assertEquals(1, cancellations.get());
    }

    @Test
    void cancelledMonitorDiscardsPreparedPackedView() {
        var gate = new BuilderPublicationGate();
        long generation = gate.start();
        var monitor = new org.eclipse.core.runtime.NullProgressMonitor();
        monitor.setCanceled(true);
        var discards = new AtomicInteger();

        org.junit.jupiter.api.Assertions.assertThrows(OperationCanceledException.class,
                () -> ProjectBuilder.executeGeneration(gate, generation, monitor,
                        () -> new ProjectBuilder.PreparedUpdate(true,
                                () -> { }, () -> { },
                                discards::incrementAndGet),
                        () -> { }));

        assertEquals(1, discards.get());
    }

    @Test
    void unhandledCheckedPublicationFailureInvalidatesGeneration()
            throws Exception {
        var gate = new BuilderPublicationGate();
        long generation = gate.start();
        var publications = new AtomicInteger();

        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> ProjectBuilder.executeGeneration(gate, generation, null,
                        () -> new ProjectBuilder.PreparedUpdate(false,
                                () -> { throw new java.io.IOException("publication failure"); },
                                publications::incrementAndGet),
                        () -> { }));

        assertEquals(0, publications.get());
        assertFalse(gate.publish(generation, () -> { }));
    }

    @Test
    void unhandledRuntimePublicationFailurePreventsLivePublication() {
        var gate = new BuilderPublicationGate();
        long generation = gate.start();
        var publications = new AtomicInteger();

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> ProjectBuilder.executeGeneration(gate, generation, null,
                        () -> new ProjectBuilder.PreparedUpdate(false,
                                () -> { throw new IllegalStateException("publication failure"); },
                                publications::incrementAndGet),
                        () -> { }));

        assertEquals(0, publications.get());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for test latch"); //$NON-NLS-1$
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }

    private static ProjectBuildDeltaClassifier.Entry changedSql(
            String path) {
        return new ProjectBuildDeltaClassifier.Entry(path,
                ProjectBuildDeltaClassifier.Kind.CHANGED,
                true, false, true, false);
    }
}
