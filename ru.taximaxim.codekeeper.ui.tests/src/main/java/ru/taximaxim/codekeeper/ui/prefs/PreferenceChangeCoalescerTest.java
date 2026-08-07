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
package ru.taximaxim.codekeeper.ui.prefs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class PreferenceChangeCoalescerTest {

    private final ManualScheduler scheduler = new ManualScheduler();
    private final PreferenceChangeCoalescer coalescer =
            new PreferenceChangeCoalescer(750, scheduler);
    private final List<String> reactions = new ArrayList<>();

    @Test
    void theLastReactionOfABurstIsTheOneThatRuns() {
        coalescer.request("index", () -> reactions.add("first"));
        coalescer.request("index", () -> reactions.add("second"));
        coalescer.request("index", () -> reactions.add("third"));

        assertEquals(List.of(), reactions);
        scheduler.elapseLiveWindows();
        assertEquals(List.of("third"), reactions);
    }

    /**
     * A job that has already started cannot be cancelled, so a window opened by
     * an earlier event of a burst may still fire -- and it elapses while the
     * burst is still arriving. Recognizing that it was superseded is the whole
     * of "wait until the node settles": otherwise the first event of a burst
     * decides the outcome and the deferral buys nothing.
     */
    @Test
    void aSupersededWindowThatFiresAnywayDoesNothing() {
        scheduler.cancellable = false;
        coalescer.request("index", () -> reactions.add("first"));
        coalescer.request("index", () -> reactions.add("second"));

        scheduler.elapseWindow(0);
        assertEquals(List.of(), reactions,
                "A window from the middle of a burst ran its reaction");

        scheduler.elapseWindow(1);
        assertEquals(List.of("second"), reactions);
    }

    /**
     * The same window fired twice -- a job rescheduled by the platform -- may
     * not run a reaction that was already spent.
     */
    @Test
    void aWindowThatFiresTwiceRunsItsReactionOnce() {
        coalescer.request("index", () -> reactions.add("only"));

        scheduler.elapseLiveWindows();
        scheduler.elapseEveryWindow();

        assertEquals(List.of("only"), reactions);
    }

    @Test
    void flushRunsAWaitingReactionAndCancelDropsIt() {
        coalescer.request("index", () -> reactions.add("index"));
        coalescer.request("comparison", () -> reactions.add("comparison"));

        coalescer.cancel("comparison");
        assertTrue(coalescer.flush("index"));
        assertFalse(coalescer.flush("index"),
                "A flushed reaction was left waiting");
        assertFalse(coalescer.flush("comparison"),
                "A cancelled reaction was left waiting");

        scheduler.elapseEveryWindow();
        assertEquals(List.of("index"), reactions);
    }

    /**
     * A reaction is free to ask for another one. Everything a caller hands in
     * is invoked outside this object's monitor, so a re-entrant request cannot
     * be the thing that hangs a workbench.
     */
    @Test
    void aReactionMayRequestAnotherOneFromAnotherThread() {
        coalescer.request("index", () -> {
            reactions.add("first");
            offThread(() -> coalescer.request("index",
                    () -> reactions.add("second")));
        });

        scheduler.elapseLiveWindows();
        assertEquals(List.of("first"), reactions);
        scheduler.elapseLiveWindows();
        assertEquals(List.of("first", "second"), reactions);
    }

    /**
     * Only the first event of a burst opens a window; the rest restart it. A
     * caller reports a deferral off that answer, and a re-read of a project
     * preference file routes every key it holds -- one line per key would bury
     * the rest of a bounded telemetry buffer.
     */
    @Test
    void onlyTheFirstEventOfABurstOpensAWindow() {
        assertTrue(coalescer.request("index", () -> reactions.add("first")));
        assertFalse(coalescer.request("index", () -> reactions.add("second")));
        assertFalse(coalescer.request("index", () -> reactions.add("third")));
        assertTrue(coalescer.request("comparison",
                () -> reactions.add("comparison")),
                "A second reaction of the same editor shared a window");

        scheduler.elapseLiveWindows();

        assertTrue(coalescer.request("index", () -> reactions.add("later")),
                "A burst that already ran left its window open");
    }

    @Test
    void everyWindowIsAskedForWithTheSettleDelay() {
        coalescer.request("index", () -> reactions.add("index"));
        coalescer.request("comparison", () -> reactions.add("comparison"));

        assertEquals(750, coalescer.getSettleDelayMillis());
        assertEquals(List.of(750L, 750L), scheduler.delays());
    }

    private static void offThread(Runnable action) {
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
                "A re-entrant request was blocked by the coalescer monitor");
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }

    private static final class ManualScheduler
            implements PreferenceChangeCoalescer.Scheduler {

        private final List<Window> windows = new ArrayList<>();
        private boolean cancellable = true;

        @Override
        public PreferenceChangeCoalescer.Cancellation schedule(
                Runnable window, long delayMillis) {
            var scheduled = new Window(window, delayMillis);
            windows.add(scheduled);
            return () -> scheduled.cancelled = cancellable;
        }

        private void elapseWindow(int index) {
            windows.get(index).window.run();
        }

        private void elapseLiveWindows() {
            elapse(false);
        }

        private void elapseEveryWindow() {
            elapse(true);
        }

        private void elapse(boolean includeCancelled) {
            for (Window window : List.copyOf(windows)) {
                if (includeCancelled || !window.cancelled) {
                    window.window.run();
                }
            }
        }

        private List<Long> delays() {
            return windows.stream().map(window -> window.delayMillis).toList();
        }
    }

    private static final class Window {

        private final Runnable window;
        private final long delayMillis;
        private boolean cancelled;

        private Window(Runnable window, long delayMillis) {
            this.window = window;
            this.delayMillis = delayMillis;
        }
    }
}
