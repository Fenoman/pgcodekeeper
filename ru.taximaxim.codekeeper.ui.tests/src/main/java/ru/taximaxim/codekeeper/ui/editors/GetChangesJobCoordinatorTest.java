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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

class GetChangesJobCoordinatorTest {

    @Test
    void newerRequestCancelsPreviousAndRejectsItsPublication() {
        GetChangesJobCoordinator coordinator = new GetChangesJobCoordinator();
        AtomicBoolean firstCancelled = new AtomicBoolean();
        AtomicBoolean stalePublished = new AtomicBoolean();
        AtomicBoolean currentPublished = new AtomicBoolean();

        GetChangesJobCoordinator.Request first = coordinator.start(
                () -> firstCancelled.set(true));
        GetChangesJobCoordinator.Request second = coordinator.start(() -> { });

        assertTrue(firstCancelled.get());
        assertFalse(first.publish(() -> stalePublished.set(true)));
        assertFalse(stalePublished.get());
        assertTrue(second.publish(() -> currentPublished.set(true)));
        assertTrue(currentPublished.get());
    }

    @Test
    void cancelInvalidatesCurrentRequestAndCancelsItsWork() {
        GetChangesJobCoordinator coordinator = new GetChangesJobCoordinator();
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicBoolean published = new AtomicBoolean();
        GetChangesJobCoordinator.Request request = coordinator.start(
                () -> cancelled.set(true));

        GetChangesJobCoordinator.Cancellation cancellation =
                coordinator.cancel();

        assertTrue(cancellation.cancelledActiveRequest());
        assertTrue(cancelled.get());
        assertFalse(request.publish(() -> published.set(true)));
        assertFalse(published.get());
        assertFalse(coordinator.cancel().cancelledActiveRequest());
    }

    @Test
    void staleCancellationCallbackCannotCancelOrResetNewRequest() {
        GetChangesJobCoordinator coordinator = new GetChangesJobCoordinator();
        AtomicBoolean oldCallbackRan = new AtomicBoolean();
        AtomicBoolean newPublished = new AtomicBoolean();
        coordinator.start(() -> { });

        GetChangesJobCoordinator.Cancellation oldCancellation =
                coordinator.cancelForNotification();
        GetChangesJobCoordinator.Request replacement =
                coordinator.start(() -> { });

        assertFalse(oldCancellation.publish(
                () -> oldCallbackRan.set(true)));
        assertFalse(oldCallbackRan.get());
        assertTrue(replacement.publish(
                () -> newPublished.set(true)));
        assertTrue(newPublished.get());
    }

    @Test
    void burstCancellationKeepsReasonOnNewestCallback() {
        GetChangesJobCoordinator coordinator = new GetChangesJobCoordinator();
        AtomicBoolean firstCallbackRan = new AtomicBoolean();
        AtomicBoolean secondCallbackRan = new AtomicBoolean();
        coordinator.start(() -> { });

        GetChangesJobCoordinator.Cancellation first =
                coordinator.cancelForNotification();
        GetChangesJobCoordinator.Cancellation second =
                coordinator.cancelForNotification();

        assertFalse(first.publish(
                () -> firstCallbackRan.set(true)));
        assertFalse(firstCallbackRan.get());
        assertTrue(second.cancelledActiveRequest());
        assertTrue(second.publish(
                () -> secondCallbackRan.set(true)));
        assertTrue(secondCallbackRan.get());
    }

    @Test
    void completedRequestMayPublishQueuedUiWorkUntilInvalidated() {
        GetChangesJobCoordinator coordinator = new GetChangesJobCoordinator();
        AtomicBoolean published = new AtomicBoolean();
        GetChangesJobCoordinator.Request request = coordinator.start(() -> { });

        request.complete();

        assertTrue(request.publish(() -> published.set(true)));
        assertTrue(published.get());

        coordinator.cancel();
        assertFalse(request.publish(() -> { }));
    }
}
