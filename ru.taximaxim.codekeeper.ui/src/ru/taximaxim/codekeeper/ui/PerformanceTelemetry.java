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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Keeps recent aggregate performance diagnostics available even when the
 * Eclipse log is configured to hide informational messages.
 */
public final class PerformanceTelemetry {

    private static final String FILE_NAME = "performance.log"; //$NON-NLS-1$
    static final long MAX_FILE_BYTES = 256L << 10;
    static final int MAX_PENDING_EVENTS = 128;
    private static final int MAX_EVENT_CHARS = 2_048;
    private static final byte[] OVERSIZED_LINE =
            "pgCodeKeeper telemetry line exceeded limit\n" //$NON-NLS-1$
                    .getBytes(StandardCharsets.UTF_8);
    private static final Object LIFECYCLE_LOCK = new Object();
    private static final Object FILE_LOCK = new Object();
    private static final AtomicReference<AsyncSink> ACTIVE =
            new AtomicReference<>();

    public static void publish(String message) {
        AsyncSink sink = ACTIVE.get();
        if (sink != null) {
            sink.publish(message);
        }
    }

    static void start() {
        start(new AsyncSink(MAX_PENDING_EVENTS,
                PerformanceTelemetry::write));
    }

    static void start(AsyncSink replacement) {
        Objects.requireNonNull(replacement, "replacement"); //$NON-NLS-1$
        AsyncSink previous;
        synchronized (LIFECYCLE_LOCK) {
            previous = ACTIVE.getAndSet(replacement);
        }
        if (previous != null) {
            previous.close();
        }
    }

    static void stop() {
        AsyncSink sink;
        synchronized (LIFECYCLE_LOCK) {
            sink = ACTIVE.getAndSet(null);
        }
        if (sink != null) {
            sink.close();
        }
    }

    static boolean isStarted() {
        return ACTIVE.get() != null;
    }

    static String normalize(String message) {
        Objects.requireNonNull(message, "message"); //$NON-NLS-1$
        int length = Math.min(message.length(), MAX_EVENT_CHARS);
        boolean copy = length != message.length();
        for (int i = 0; !copy && i < length; i++) {
            char ch = message.charAt(i);
            copy = ch == '\r' || ch == '\n';
        }
        if (!copy) {
            return message;
        }

        StringBuilder normalized = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            char ch = message.charAt(i);
            normalized.append(ch == '\r' || ch == '\n' ? ' ' : ch);
        }
        return normalized.toString();
    }

    private static void write(String message) {
        Path file = null;
        boolean mirrorToPlatformLog = false;
        try {
            Activator plugin = Activator.getDefault();
            if (plugin != null) {
                file = Path.of(plugin.getStateLocation().toOSString())
                        .resolve(FILE_NAME);
                mirrorToPlatformLog = plugin.isDebugging();
            }
        } catch (RuntimeException ex) {
            // The bounded state file below is the primary sink anyway.
        }
        // The platform log is unbounded and every comparison writes several
        // lines, so the mirror is opt-in through the bundle debug option and
        // stays at debug severity. The bounded state file is always written.
        write(message, file, mirrorToPlatformLog
                ? value -> Log.log(Log.LOG_DEBUG, value)
                : value -> {
                    // platform-log mirror disabled
                });
    }

    static void write(String message, Path file, Consumer<String> logger) {
        if (file != null) {
            try {
                append(file, Instant.now() + " " + message, MAX_FILE_BYTES); //$NON-NLS-1$
            } catch (IOException | RuntimeException ex) {
                // Diagnostics must never affect comparison or indexing.
            }
        }

        try {
            logger.accept(message);
        } catch (RuntimeException ex) {
            // The bounded state file above remains the primary production sink.
        }
    }

    static final class AsyncSink implements AutoCloseable {

        private static final long CLOSE_TIMEOUT_SECONDS = 2;

        private final ArrayBlockingQueue<String> pending;
        private final Consumer<String> writer;
        private final AtomicBoolean accepting = new AtomicBoolean(true);
        private final AtomicLong droppedEvents = new AtomicLong();
        private final Object enqueueLock = new Object();
        private final Thread worker;

        AsyncSink(int capacity, Consumer<String> writer) {
            if (capacity < 1) {
                throw new IllegalArgumentException(
                        "capacity must be positive"); //$NON-NLS-1$
            }
            this.pending = new ArrayBlockingQueue<>(capacity);
            this.writer = Objects.requireNonNull(writer, "writer"); //$NON-NLS-1$
            worker = new Thread(this::run,
                    "pgCodeKeeper-performance-telemetry"); //$NON-NLS-1$
            worker.setDaemon(true);
            worker.start();
        }

        void publish(String message) {
            if (message == null || !accepting.get()) {
                return;
            }
            String normalized = normalize(message);
            synchronized (enqueueLock) {
                if (!accepting.get()) {
                    return;
                }
                if (!pending.offer(normalized)) {
                    if (pending.poll() != null) {
                        droppedEvents.incrementAndGet();
                    }
                    pending.offer(normalized);
                }
            }
        }

        int pendingCount() {
            return pending.size();
        }

        boolean isAlive() {
            return worker.isAlive();
        }

        @Override
        public void close() {
            synchronized (enqueueLock) {
                accepting.set(false);
            }
            worker.interrupt();
            try {
                worker.join(TimeUnit.SECONDS.toMillis(
                        CLOSE_TIMEOUT_SECONDS));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            if (worker.isAlive()) {
                worker.interrupt();
            }
        }

        private void run() {
            while (accepting.get() || !pending.isEmpty()) {
                try {
                    String message = pending.take();
                    long dropped = droppedEvents.getAndSet(0);
                    if (dropped != 0) {
                        writeSafely("pgCodeKeeper telemetry: dropped_events=" //$NON-NLS-1$
                                + dropped);
                    }
                    writeSafely(message);
                } catch (InterruptedException ex) {
                    // Re-check lifecycle and drain queued diagnostics on stop.
                }
            }
        }

        private void writeSafely(String message) {
            try {
                writer.accept(message);
            } catch (RuntimeException ex) {
                // One broken diagnostic must not stop later events.
            }
        }
    }

    static void append(Path file, String message, long maxBytes)
            throws IOException {
        Objects.requireNonNull(file, "file"); //$NON-NLS-1$
        Objects.requireNonNull(message, "message"); //$NON-NLS-1$
        if (maxBytes < OVERSIZED_LINE.length) {
            throw new IllegalArgumentException(
                    "maxBytes is too small for a diagnostic line"); //$NON-NLS-1$
        }

        String oneLine = message.replace('\r', ' ').replace('\n', ' ') + '\n';
        byte[] payload = oneLine.getBytes(StandardCharsets.UTF_8);
        if (payload.length > maxBytes) {
            payload = OVERSIZED_LINE;
        }

        synchronized (FILE_LOCK) {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            long currentSize = Files.exists(file) ? Files.size(file) : 0;
            if (currentSize > maxBytes
                    || currentSize + payload.length > maxBytes) {
                Files.write(file, payload, StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING);
            } else {
                Files.write(file, payload, StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            }
        }
    }

    private PerformanceTelemetry() {
    }
}
