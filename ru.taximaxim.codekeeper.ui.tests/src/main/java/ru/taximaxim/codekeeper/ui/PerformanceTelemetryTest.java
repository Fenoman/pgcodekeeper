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
package ru.taximaxim.codekeeper.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PerformanceTelemetryTest {

    @TempDir
    Path tempDir;

    @Test
    void usesProductionQueueAndFileLimits() {
        assertEquals(128, PerformanceTelemetry.MAX_PENDING_EVENTS);
        assertEquals(256L << 10, PerformanceTelemetry.MAX_FILE_BYTES);
    }

    @Test
    void keepsRecentCompleteLinesWithinTheConfiguredLimit() throws Exception {
        Path file = tempDir.resolve("performance.log");

        String last = "last=" + "5".repeat(40);
        PerformanceTelemetry.append(file, "first=" + "1".repeat(40), 64);
        PerformanceTelemetry.append(file, last, 64);

        assertTrue(Files.size(file) <= 64);
        assertEquals(last + '\n', Files.readString(file));
    }

    @Test
    void replacesOversizedInputWithABoundedDiagnostic() throws Exception {
        Path file = tempDir.resolve("performance.log");

        PerformanceTelemetry.append(file, "x".repeat(1_000), 64);

        assertTrue(Files.size(file) <= 64);
        assertTrue(Files.readString(file).contains("exceeded"));
    }

    @Test
    void blockedWriterReportsDroppedEventsBeforeTheNextRetainedMessage()
            throws Exception {
        var writerEntered = new CountDownLatch(1);
        var releaseWriter = new CountDownLatch(1);
        List<String> written = new CopyOnWriteArrayList<>();
        try (var sink = new PerformanceTelemetry.AsyncSink(2, message -> {
            writerEntered.countDown();
            try {
                releaseWriter.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            written.add(message);
        })) {
            sink.publish("first");
            assertTrue(writerEntered.await(5, TimeUnit.SECONDS));

            var publisher = Executors.newSingleThreadExecutor();
            try {
                publisher.submit(() -> {
                    sink.publish("second");
                    sink.publish("third");
                    sink.publish("fourth");
                    sink.publish("last");
                }).get(1, TimeUnit.SECONDS);
            } finally {
                publisher.shutdownNow();
            }
            assertTrue(sink.pendingCount() <= 2);
            releaseWriter.countDown();
        }

        assertEquals(List.of("first",
                "pgCodeKeeper telemetry: dropped_events=2",
                "fourth", "last"), written);
    }

    @Test
    void writerFailureDoesNotStopFollowingTelemetry() {
        List<String> written = new CopyOnWriteArrayList<>();
        try (var sink = new PerformanceTelemetry.AsyncSink(2, message -> {
            if ("broken".equals(message)) {
                throw new IllegalStateException("expected");
            }
            written.add(message);
        })) {
            sink.publish("broken");
            sink.publish("survives");
        }

        assertEquals(List.of("survives"), written);
    }

    @Test
    void droppedEventReportFailureDoesNotStopTheRetainedMessage()
            throws Exception {
        var writerEntered = new CountDownLatch(1);
        var releaseWriter = new CountDownLatch(1);
        List<String> written = new CopyOnWriteArrayList<>();
        try (var sink = new PerformanceTelemetry.AsyncSink(1, message -> {
            if ("first".equals(message)) {
                writerEntered.countDown();
                try {
                    releaseWriter.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            if (message.startsWith(
                    "pgCodeKeeper telemetry: dropped_events=")) {
                throw new IllegalStateException("expected");
            }
            written.add(message);
        })) {
            sink.publish("first");
            assertTrue(writerEntered.await(5, TimeUnit.SECONDS));
            sink.publish("discarded");
            sink.publish("survives");
            releaseWriter.countDown();
        }

        assertEquals(List.of("first", "survives"), written);
    }

    @Test
    void concurrentPublishersReportEveryDroppedEvent()
            throws Exception {
        var writerEntered = new CountDownLatch(1);
        var releaseWriter = new CountDownLatch(1);
        List<String> written = new CopyOnWriteArrayList<>();
        try (var sink = new PerformanceTelemetry.AsyncSink(8, message -> {
            if ("blocked".equals(message)) {
                writerEntered.countDown();
                try {
                    releaseWriter.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            written.add(message);
        })) {
            sink.publish("blocked");
            assertTrue(writerEntered.await(5, TimeUnit.SECONDS));

            var publishers = Executors.newFixedThreadPool(8);
            try {
                for (int thread = 0; thread < 8; thread++) {
                    publishers.submit(() -> {
                        for (int event = 0; event < 1_000; event++) {
                            sink.publish("event-" + event);
                        }
                    });
                }
            } finally {
                publishers.shutdown();
            }

            assertTrue(publishers.awaitTermination(5, TimeUnit.SECONDS),
                    "publishers waited for the blocked telemetry writer");
            assertTrue(sink.pendingCount() <= 8);
            releaseWriter.countDown();
        }

        assertEquals(10, written.size());
        assertEquals("blocked", written.get(0));
        assertEquals("pgCodeKeeper telemetry: dropped_events=7992",
                written.get(1));
    }

    @Test
    void loggerFailureDoesNotPreventBoundedFileWrite() throws Exception {
        Path file = tempDir.resolve("performance.log");

        PerformanceTelemetry.write("persisted", file, message -> {
            assertTrue(Files.exists(file),
                    "the primary state file must be written first");
            throw new IllegalStateException("expected");
        });

        assertTrue(Files.readString(file).contains("persisted"));
        assertTrue(Files.size(file) <= 64L << 10);
    }

    @Test
    void normalizesAndBoundsMessagesBeforeTheyReachTheQueue() {
        String normalized = PerformanceTelemetry.normalize(
                "first\r\n" + "x".repeat(3_000));

        assertEquals(2_048, normalized.length());
        assertTrue(normalized.startsWith("first  "));
        assertTrue(!normalized.contains("\r"));
        assertTrue(!normalized.contains("\n"));
    }

    @Test
    void stopDoesNotBlockWorkerFromDrainingTheStateFile() throws Exception {
        Path file = tempDir.resolve("performance.log");
        var writerEntered = new CountDownLatch(1);
        var releaseWriter = new CountDownLatch(1);
        var sink = new PerformanceTelemetry.AsyncSink(2, message -> {
            writerEntered.countDown();
            boolean released = false;
            while (!released) {
                try {
                    released = releaseWriter.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    // Stop wakes the worker; it must still flush pending data.
                }
            }
            try {
                PerformanceTelemetry.append(file, message, 1_024);
            } catch (java.io.IOException ex) {
                throw new IllegalStateException(ex);
            }
        });
        boolean restoreProductionSink = PerformanceTelemetry.isStarted();
        PerformanceTelemetry.start(sink);
        try {
            PerformanceTelemetry.publish("first");
            assertTrue(writerEntered.await(5, TimeUnit.SECONDS));
            PerformanceTelemetry.publish("pending");

            Thread stopper = new Thread(PerformanceTelemetry::stop);
            stopper.start();
            waitUntilWaiting(stopper);
            releaseWriter.countDown();
            stopper.join(1_000);

            assertFalse(stopper.isAlive(),
                    "stop held the lock required by the telemetry writer");
            assertFalse(sink.isAlive(),
                    "telemetry worker survived a successful stop");
            assertTrue(Files.readString(file).contains("pending"));
        } finally {
            releaseWriter.countDown();
            PerformanceTelemetry.stop();
            if (restoreProductionSink) {
                PerformanceTelemetry.start();
            }
        }
    }

    private static void waitUntilWaiting(Thread thread) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (thread.getState() != Thread.State.TIMED_WAITING
                && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(Thread.State.TIMED_WAITING, thread.getState(),
                "stop did not enter bounded worker join");
    }
}
