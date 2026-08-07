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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import ru.taximaxim.codekeeper.ui.UIConsts.PREF;
import ru.taximaxim.codekeeper.ui.UIConsts.PROJ_PREF;
import ru.taximaxim.codekeeper.ui.prefs.PreferenceChangeCoalescer;

/**
 * What an editor does with a preference change, as far as it can be assembled
 * without a workbench: the real coalescer in front of the real fingerprint
 * guard, over a preference node that behaves the way the platform makes one
 * behave while it applies a file.
 * <p>
 * The measurement this answers: fourteen preference changes of an untouched
 * project file inside one second, two of which retired a persisted index and
 * bought a 48-second rebuild each. The platform re-applies a file by removing
 * and re-adding every key and fires the removal while the key is gone, so a
 * handler that reads the node right then is reading a configuration that never
 * existed.
 */
class PreferenceSettlingInvalidationTest {

    /**
     * Every key the effective index configuration is made of, including the
     * override switch that decides whether the rest are read at all. The
     * fingerprint reads all of them, which is why one absent key is enough to
     * make an untouched file look edited.
     */
    private static final List<String> INDEX_KEYS = List.of(
            PROJ_PREF.ENABLE_PROJ_PREF_ROOT,
            PREF.ENABLE_BODY_DEPENDENCIES,
            PREF.PROJECT_INDEX_INCREMENTAL_ADDED_FILES,
            PREF.NO_PRIVILEGES,
            PREF.PROJECT_UPDATED_FROM_DATABASE_ONLY,
            PREF.PROJECT_INDEX_EXCLUDED_SCHEMAS);

    private static final long SETTLE_DELAY_MILLIS = 750;

    @Test
    void reapplyingAnUntouchedFileDoesNotInvalidate() {
        var harness = new Harness();
        harness.storeFile(untouchedFile());
        harness.observeCurrentConfiguration();

        harness.reapplyStoredFile();
        harness.settle();

        assertEquals(0, harness.invalidations.get(),
                "A file that was re-applied unchanged retired the index");
        assertEquals(0, harness.liveInvalidations.get());
        assertEquals(0, harness.persistedInvalidations.get());
        assertEquals(0, harness.scheduledBuilds.get());
    }

    /**
     * The same burst, with one key genuinely different in the file being
     * applied. The change is real, so it has to be acted on -- once for the
     * burst, not once per key.
     */
    @Test
    void reapplyingAChangedFileInvalidatesExactlyOnce() {
        var harness = new Harness();
        harness.storeFile(untouchedFile());
        harness.observeCurrentConfiguration();

        Map<String, String> edited = untouchedFile();
        edited.put(PREF.PROJECT_INDEX_INCREMENTAL_ADDED_FILES, "true");
        harness.reapplyFile(edited);
        harness.settle();

        assertEquals(1, harness.invalidations.get());
        assertEquals(1, harness.liveInvalidations.get());
        assertEquals(1, harness.persistedInvalidations.get());
        assertEquals(1, harness.scheduledBuilds.get());
    }

    /**
     * A setting a person changed by hand still costs exactly what it used to
     * cost. Deferral is not an excuse to lose one.
     */
    @Test
    void oneRealChangeStillInvalidates() {
        var harness = new Harness();
        harness.storeFile(untouchedFile());
        harness.observeCurrentConfiguration();

        harness.put(PREF.PROJECT_INDEX_EXCLUDED_SCHEMAS, "audit");
        harness.settle();

        assertEquals(1, harness.invalidations.get());
        assertEquals(1, harness.liveInvalidations.get());
        assertEquals(1, harness.scheduledBuilds.get());
    }

    /**
     * Nothing may be decided on the thread that delivered the event: the node
     * it would be read from is still being written to. Synchronously after the
     * whole burst there is no invalidation yet; one window outlived the burst
     * and every window was asked for with the settling delay.
     */
    @Test
    void nothingIsDecidedOnTheEventThread() {
        var harness = new Harness();
        harness.storeFile(untouchedFile());
        harness.observeCurrentConfiguration();

        Map<String, String> edited = untouchedFile();
        edited.put(PREF.ENABLE_BODY_DEPENDENCIES, "false");
        harness.reapplyFile(edited);

        assertEquals(0, harness.invalidations.get(),
                "The reaction ran inside the preference change event");
        assertEquals(0, harness.liveInvalidations.get());
        assertEquals(0, harness.scheduledBuilds.get());
        assertEquals(2 * untouchedFile().size(),
                harness.scheduler.windows.size(),
                "Every event has to restart the settling window");
        assertEquals(1, harness.scheduler.liveWindows(),
                "The burst left more than one window to fire");
        assertEquals(List.of(SETTLE_DELAY_MILLIS),
                harness.scheduler.requestedDelays());

        harness.settle();
        assertEquals(1, harness.invalidations.get());
    }

    /**
     * An editor closing inside the settling window is the one way a deferral
     * could turn into a loss: no listener is left to observe the change a
     * second time, and the persisted index outlives the editor.
     */
    @Test
    void closingInsideTheWindowStillSettlesTheChange() {
        var harness = new Harness();
        harness.storeFile(untouchedFile());
        harness.observeCurrentConfiguration();

        harness.put(PREF.NO_PRIVILEGES, "true");
        assertEquals(0, harness.invalidations.get());

        harness.disposeEditor();

        assertEquals(1, harness.invalidations.get(),
                "A change observed before the editor closed was dropped");
        assertEquals(1, harness.scheduledBuilds.get());

        harness.settle();
        assertEquals(1, harness.invalidations.get(),
                "The flushed reaction ran a second time from its window");
    }

    /**
     * A window that cannot be scheduled at all leaves nothing to run the
     * reaction later, so it runs now -- which is what this call site did before
     * the window existed.
     */
    @Test
    void aWindowThatCannotBeScheduledStillInvalidates() {
        var harness = new Harness();
        harness.storeFile(untouchedFile());
        harness.observeCurrentConfiguration();
        harness.scheduler.failing = true;

        assertThrows(IllegalStateException.class, () ->
                harness.put(PREF.NO_PRIVILEGES, "true"));

        assertEquals(1, harness.invalidations.get());
        assertEquals(1, harness.scheduledBuilds.get());
    }

    /**
     * The two reactions of one editor are coalesced apart: a key that retires
     * both an index and a comparison must not let one of them swallow the
     * other.
     */
    @Test
    void reactionsOfDifferentKindsDoNotCoalesceIntoOne() {
        var harness = new Harness();
        harness.storeFile(untouchedFile());
        harness.observeCurrentConfiguration();
        var comparisonResets = new AtomicInteger();

        harness.put(PREF.NO_PRIVILEGES, "true");
        harness.coalescer.request(Reaction.COMPARISON,
                comparisonResets::incrementAndGet);
        harness.settle();

        assertEquals(1, harness.invalidations.get());
        assertEquals(1, comparisonResets.get());
    }

    private static Map<String, String> untouchedFile() {
        var file = new LinkedHashMap<String, String>();
        file.put(PROJ_PREF.ENABLE_PROJ_PREF_ROOT, "true");
        file.put(PREF.ENABLE_BODY_DEPENDENCIES, "true");
        file.put(PREF.PROJECT_INDEX_INCREMENTAL_ADDED_FILES, "false");
        file.put(PREF.NO_PRIVILEGES, "false");
        return file;
    }

    private enum Reaction {
        PROJECT_INDEX,
        COMPARISON
    }

    /**
     * One project, one editor, one preference node. The node reports an
     * inherited default for a key it does not hold, which is what makes the
     * middle of a re-applied file look like a configuration change.
     */
    private static final class Harness {

        private static final String INHERITED = "<inherited>";

        private final Object project = new Object();
        private final Map<String, String> stored = new LinkedHashMap<>();
        private final ManualScheduler scheduler = new ManualScheduler();
        private final PreferenceChangeCoalescer coalescer =
                new PreferenceChangeCoalescer(SETTLE_DELAY_MILLIS, scheduler);
        private final AtomicInteger invalidations = new AtomicInteger();
        private final AtomicInteger liveInvalidations = new AtomicInteger();
        private final AtomicInteger persistedInvalidations =
                new AtomicInteger();
        private final AtomicInteger scheduledBuilds = new AtomicInteger();
        private final ProjectIndexConfigurationInvalidator invalidator =
                new ProjectIndexConfigurationInvalidator((ignored, task) -> {
                    scheduledBuilds.incrementAndGet();
                    return () -> { };
                });

        /** Seeds the node without pretending that anything changed. */
        private void storeFile(Map<String, String> file) {
            stored.putAll(file);
        }

        private void observeCurrentConfiguration() {
            invalidator.observe(project, fingerprint());
        }

        private void reapplyStoredFile() {
            reapplyFile(new LinkedHashMap<>(stored));
        }

        /**
         * Applies a file the way the platform applies one: key by key, removing
         * before putting, and firing the change from inside the removal.
         */
        private void reapplyFile(Map<String, String> file) {
            file.forEach((key, value) -> {
                remove(key);
                put(key, value);
            });
        }

        private void put(String key, String value) {
            stored.put(key, value);
            preferenceChange();
        }

        private void remove(String key) {
            stored.remove(key);
            preferenceChange();
        }

        /** What the editor's own listener does with an event. */
        private void preferenceChange() {
            coalescer.request(Reaction.PROJECT_INDEX,
                    this::invalidateSettledConfiguration);
        }

        private void invalidateSettledConfiguration() {
            boolean invalidated = invalidator.invalidate(project,
                    fingerprint(),
                    persistedInvalidation -> {
                        liveInvalidations.incrementAndGet();
                        persistedInvalidation.run();
                    },
                    persistedInvalidations::incrementAndGet,
                    monitor -> {
                        // The build itself belongs to a workspace job this
                        // stand cannot start; the reading is that one was
                        // asked for.
                    });
            if (invalidated) {
                invalidations.incrementAndGet();
            }
        }

        /**
         * The whole configuration, read as one value. Every key of it is read,
         * so a key that is momentarily absent changes the answer.
         */
        private String fingerprint() {
            var digest = new StringBuilder();
            for (String key : INDEX_KEYS) {
                digest.append(key).append('=')
                        .append(stored.getOrDefault(key, INHERITED))
                        .append(';');
            }
            return digest.toString();
        }

        private void settle() {
            scheduler.runSettledWindows();
        }

        private void disposeEditor() {
            coalescer.cancel(Reaction.COMPARISON);
            coalescer.flush(Reaction.PROJECT_INDEX);
        }
    }

    private static final class ManualScheduler
            implements PreferenceChangeCoalescer.Scheduler {

        private final List<Window> windows = new ArrayList<>();
        private boolean failing;

        @Override
        public PreferenceChangeCoalescer.Cancellation schedule(
                Runnable window, long delayMillis) {
            if (failing) {
                throw new IllegalStateException("the platform is going down");
            }
            var scheduled = new Window(window, delayMillis);
            windows.add(scheduled);
            return () -> scheduled.cancelled = true;
        }

        /** Elapses every window that was not superseded by a later event. */
        private void runSettledWindows() {
            for (Window window : List.copyOf(windows)) {
                if (!window.cancelled) {
                    window.window.run();
                }
            }
        }

        private long liveWindows() {
            return windows.stream().filter(window -> !window.cancelled).count();
        }

        private List<Long> requestedDelays() {
            return windows.stream().map(window -> window.delayMillis)
                    .distinct().toList();
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
