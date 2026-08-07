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
package ru.taximaxim.codekeeper.ui.differ;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

class GitUserLoadCoordinatorTest {

    @Test
    void newerLoadCancelsDelayedLoadAndRejectsItsPublication() throws Exception {
        GitUserLoadCoordinator coordinator = new GitUserLoadCoordinator();
        AtomicBoolean firstCancelled = new AtomicBoolean();
        AtomicBoolean stalePublished = new AtomicBoolean();
        AtomicBoolean currentPublished = new AtomicBoolean();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            GitUserLoadCoordinator.Request first = coordinator.start(() -> firstCancelled.set(true));
            Future<?> delayed = executor.submit(() -> {
                firstStarted.countDown();
                releaseFirst.await();
                first.publish(() -> stalePublished.set(true));
                first.complete();
                return null;
            });
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));

            GitUserLoadCoordinator.Request second = coordinator.start(() -> { });
            releaseFirst.countDown();
            delayed.get(5, TimeUnit.SECONDS);

            assertTrue(firstCancelled.get());
            assertFalse(stalePublished.get());
            assertTrue(second.publish(() -> currentPublished.set(true)));
            assertTrue(currentPublished.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cancelInvalidatesCurrentLoad() {
        GitUserLoadCoordinator coordinator = new GitUserLoadCoordinator();
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicBoolean published = new AtomicBoolean();
        GitUserLoadCoordinator.Request request = coordinator.start(() -> cancelled.set(true));

        coordinator.cancel();

        assertTrue(cancelled.get());
        assertFalse(request.publish(() -> published.set(true)));
        assertFalse(published.get());
    }
}
