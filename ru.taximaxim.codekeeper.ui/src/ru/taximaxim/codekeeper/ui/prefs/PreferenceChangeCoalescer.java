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

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Holds a reaction to preference changes back until the node it reads stops
 * changing, and collapses a burst of changes into a single reaction.
 * <p>
 * A reaction must never be run from the handler that observed the change. The
 * platform applies a preference file by removing and re-adding every key, and
 * it fires the removal synchronously from inside {@code remove}, while the key
 * is already gone: a handler that reads the node at that instant sees an
 * inherited default in place of the stored value and concludes that the
 * configuration changed. One workspace refresh of an untouched project file was
 * measured producing fourteen such events within a single second, two of which
 * retired a persisted project index and cost a 48-second rebuild each. Reading
 * the node again once it has settled is the only way to tell a real edit from
 * the middle of a file being re-applied.
 * <p>
 * Every request for a key restarts that key's settling window, so a burst
 * arrives as one reaction after its last event. Keys are independent and never
 * coalesce with each other.
 * <p>
 * A deferral may not become a loss. {@link #flush(Object)} runs a reaction that
 * is still waiting, and a window that cannot be scheduled at all runs its
 * reaction immediately rather than dropping it.
 * <p>
 * The scheduler, its cancellation and the reaction itself are always invoked
 * outside this object's monitor, so a reaction is free to request another one.
 */
public final class PreferenceChangeCoalescer {

    /** Revokes a settling window that has not fired yet. */
    @FunctionalInterface
    public interface Cancellation {
        void cancel();
    }

    /**
     * Runs a settling window somewhere other than the calling thread. The
     * production implementation is a system job; a test drives the window by
     * hand.
     */
    @FunctionalInterface
    public interface Scheduler {

        /**
         * @param window      what to run once the delay elapses
         * @param delayMillis how long the node is given to settle
         * @return a handle that revokes this window
         */
        Cancellation schedule(Runnable window, long delayMillis);
    }

    private static final class Pending {

        private Runnable reaction;
        private Cancellation cancellation;
        private long generation;
    }

    private final Map<Object, Pending> pendingReactions = new HashMap<>();
    private final long settleDelayMillis;
    private final Scheduler scheduler;
    private long nextGeneration;

    /**
     * @param settleDelayMillis how long a key is left untouched before its
     *                          reaction runs
     * @param scheduler         runs a settling window off the event thread
     */
    public PreferenceChangeCoalescer(long settleDelayMillis,
            Scheduler scheduler) {
        if (settleDelayMillis < 0) {
            throw new IllegalArgumentException(
                    "Settle delay must not be negative"); //$NON-NLS-1$
        }
        this.settleDelayMillis = settleDelayMillis;
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler"); //$NON-NLS-1$
    }

    public long getSettleDelayMillis() {
        return settleDelayMillis;
    }

    /**
     * Restarts the settling window for a key and remembers the reaction to run
     * when it elapses. A later request for the same key replaces an earlier
     * reaction that has not run yet.
     *
     * @param key      names the reaction; requests for different keys are
     *                 independent
     * @param reaction re-reads the settled node and acts on what it finds
     * @return {@code true} when this request opened a window rather than
     *         restarting one, which is what tells the first event of a burst
     *         from the rest of it
     */
    public boolean request(Object key, Runnable reaction) {
        Objects.requireNonNull(key, "key"); //$NON-NLS-1$
        Objects.requireNonNull(reaction, "reaction"); //$NON-NLS-1$

        long generation;
        boolean opened;
        Cancellation superseded;
        synchronized (this) {
            Pending pending = pendingReactions.get(key);
            opened = pending == null;
            if (opened) {
                pending = new Pending();
                pendingReactions.put(key, pending);
            }
            pending.reaction = reaction;
            generation = ++nextGeneration;
            pending.generation = generation;
            superseded = pending.cancellation;
            pending.cancellation = null;
        }
        if (superseded != null) {
            superseded.cancel();
        }

        Cancellation handle;
        try {
            handle = Objects.requireNonNull(
                    scheduler.schedule(() -> fire(key, generation),
                            settleDelayMillis),
                    "settling window handle"); //$NON-NLS-1$
        } catch (RuntimeException | Error ex) {
            // Nothing is left to run this reaction later, so run it here. That
            // is what this call site did before the window existed.
            try {
                fire(key, generation);
            } catch (RuntimeException | Error suppressed) {
                ex.addSuppressed(suppressed);
            }
            throw ex;
        }

        boolean accepted;
        synchronized (this) {
            Pending pending = pendingReactions.get(key);
            accepted = pending != null && pending.generation == generation;
            if (accepted) {
                pending.cancellation = handle;
            }
        }
        if (!accepted) {
            handle.cancel();
        }
        return opened;
    }

    /**
     * Runs a reaction that is still waiting, right now and on the calling
     * thread. A part that is going away has to settle its own debts: the change
     * it observed is real whether or not anything is left to react to it later.
     *
     * @param key names the reaction
     * @return {@code true} when a waiting reaction was run
     */
    public boolean flush(Object key) {
        Runnable reaction;
        Cancellation handle;
        synchronized (this) {
            Pending pending = pendingReactions.remove(key);
            if (pending == null) {
                return false;
            }
            reaction = pending.reaction;
            handle = pending.cancellation;
        }
        if (handle != null) {
            handle.cancel();
        }
        reaction.run();
        return true;
    }

    /**
     * Drops a reaction that is still waiting. Only for state that dies with the
     * caller, so that nothing is left for the reaction to repair.
     *
     * @param key names the reaction
     */
    public void cancel(Object key) {
        Cancellation handle;
        synchronized (this) {
            Pending pending = pendingReactions.remove(key);
            handle = pending == null ? null : pending.cancellation;
        }
        if (handle != null) {
            handle.cancel();
        }
    }

    private void fire(Object key, long generation) {
        Runnable reaction;
        synchronized (this) {
            Pending pending = pendingReactions.get(key);
            if (pending == null || pending.generation != generation) {
                return;
            }
            pendingReactions.remove(key);
            reaction = pending.reaction;
        }
        reaction.run();
    }
}
