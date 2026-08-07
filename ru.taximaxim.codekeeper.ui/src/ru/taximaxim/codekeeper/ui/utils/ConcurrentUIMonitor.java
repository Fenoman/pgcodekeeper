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
package ru.taximaxim.codekeeper.ui.utils;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.SubMonitor;
import org.pgcodekeeper.core.monitor.IMonitor;

/**
 * Adapts one Eclipse progress monitor for Core workers that may run in
 * parallel. Eclipse progress monitors are not thread-safe, so all observable
 * calls are serialized on one lock.
 * <p>
 * Worker progress is forwarded through a shared {@link SubMonitor} of a fixed
 * budget: lanes repartition it against each other, but {@code SubMonitor} caps
 * the total, so the wrapped monitor can never receive more work than the slice
 * the owner reserved for the whole comparison.
 */
public final class ConcurrentUIMonitor implements IMonitor {

    /**
     * Budget the shared worker progress is reported against. Core lanes call
     * {@code setWorkRemaining} with their own scales, so a fixed parent budget
     * is what keeps the forwarded total bounded.
     */
    public static final int WORK_TICKS = 100;

    private final State state;

    public ConcurrentUIMonitor(IProgressMonitor monitor) {
        this(new State(monitor));
    }

    private ConcurrentUIMonitor(State state) {
        this.state = state;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        // Cancellation is monotonic: a worker must not clear a user request.
        if (cancelled) {
            synchronized (state.lock) {
                if (state.monitor != null) {
                    state.monitor.setCanceled(true);
                }
            }
        }
    }

    @Override
    public boolean isCancelled() {
        synchronized (state.lock) {
            return state.monitor != null && state.monitor.isCanceled();
        }
    }

    @Override
    public void worked(int work) {
        if (work <= 0) {
            return;
        }
        synchronized (state.lock) {
            if (state.progress != null) {
                state.progress.worked(work);
            }
        }
    }

    @Override
    public IMonitor createSubMonitor() {
        return new ConcurrentUIMonitor(state);
    }

    @Override
    public void setWorkRemaining(int size) {
        if (size < 0) {
            return;
        }
        synchronized (state.lock) {
            if (state.progress != null) {
                state.progress.setWorkRemaining(size);
            }
        }
    }

    @Override
    public void setTaskName(String name) {
        synchronized (state.lock) {
            if (state.monitor != null) {
                state.monitor.setTaskName(name);
            }
        }
    }

    private static final class State {

        private final Object lock = new Object();
        private final IProgressMonitor monitor;
        private final SubMonitor progress;

        private State(IProgressMonitor monitor) {
            this.monitor = monitor;
            this.progress = monitor == null
                    ? null : SubMonitor.convert(monitor, WORK_TICKS);
        }
    }
}
