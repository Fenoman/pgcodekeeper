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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.SubMonitor;
import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.monitor.IMonitor;

class ConcurrentUIMonitorTest {

    /**
     * A SubMonitor reports its whole tree against a fixed resolution, so one
     * third of the owner's budget is one third of that resolution.
     */
    private static final int SLICE_LIMIT = 1000 / 3 + 1;

    @Test
    void forwardsWorkerProgressWithoutOverreportingTheReservedSlice()
            throws InterruptedException {
        var parent = new ConcurrencyCheckingMonitor();
        // A third of the owner's budget is reserved for the comparison, so no
        // amount of worker progress may report more than that third.
        SubMonitor owner = SubMonitor.convert(parent, 3);
        IMonitor root = new ConcurrentUIMonitor(owner.newChild(1));
        IMonitor oldSide = root.createSubMonitor();
        IMonitor newSide = root.createSubMonitor();

        assertNotSame(oldSide, newSide);
        var start = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        Thread oldThread = startWorker(start, failure, () -> {
            oldSide.setWorkRemaining(100);
            oldSide.worked(100);
            oldSide.setTaskName("OLD");
        });
        Thread newThread = startWorker(start, failure, () -> {
            newSide.setWorkRemaining(100);
            newSide.worked(100);
            newSide.setTaskName("NEW");
        });

        start.countDown();
        oldThread.join();
        newThread.join();

        assertNull(failure.get());
        assertTrue(parent.worked.get() > 0,
                "worker progress must reach the reserved slice");
        assertTrue(parent.worked.get() <= SLICE_LIMIT,
                "reported " + parent.worked.get() + " ticks");
        assertFalse(parent.isCanceled());

        newSide.setCancelled(true);
        assertTrue(parent.isCanceled());
        assertTrue(oldSide.isCancelled());

        oldSide.setCancelled(false);
        assertTrue(parent.isCanceled());
    }

    private Thread startWorker(CountDownLatch start, AtomicReference<Throwable> failure,
            Runnable task) {
        return Thread.ofPlatform().start(() -> {
            try {
                start.await();
                for (int i = 0; i < 1_000; i++) {
                    task.run();
                }
            } catch (Throwable ex) {
                failure.compareAndSet(null, ex);
            }
        });
    }

    private static final class ConcurrencyCheckingMonitor implements IProgressMonitor {

        private final AtomicBoolean entered = new AtomicBoolean();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicInteger worked = new AtomicInteger();

        @Override
        public void beginTask(String name, int totalWork) {
            guarded(() -> { });
        }

        @Override
        public void done() {
            guarded(() -> { });
        }

        @Override
        public void internalWorked(double work) {
            guarded(() -> worked.addAndGet((int) work));
        }

        @Override
        public boolean isCanceled() {
            var value = new AtomicBoolean();
            guarded(() -> value.set(cancelled.get()));
            return value.get();
        }

        @Override
        public void setCanceled(boolean value) {
            guarded(() -> cancelled.set(value));
        }

        @Override
        public void setTaskName(String name) {
            guarded(() -> Thread.yield());
        }

        @Override
        public void subTask(String name) {
            guarded(() -> { });
        }

        @Override
        public void worked(int work) {
            guarded(() -> worked.addAndGet(work));
        }

        private void guarded(Runnable action) {
            if (!entered.compareAndSet(false, true)) {
                throw new AssertionError("Concurrent access to Eclipse progress monitor");
            }
            try {
                action.run();
            } finally {
                entered.set(false);
            }
        }
    }
}
