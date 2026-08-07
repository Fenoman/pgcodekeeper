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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class UiPublicationHandoffTest {

    @Test
    void queuedResourceDroppedBeforeCallbackIsClosedExactlyOnce() {
        var closes = new AtomicInteger();
        var resource = new TestResource(closes);
        var handoff =
                new UiPublicationHandoff<>(resource);

        handoff.close();
        handoff.close();

        assertTrue(handoff.claim().isEmpty(),
                "a dropped callback must not recover a released resource");
        assertEquals(1, closes.get());
    }

    @Test
    void claimedResourceIsOwnedOnlyByTheCallback() {
        var closes = new AtomicInteger();
        var resource = new TestResource(closes);
        var handoff =
                new UiPublicationHandoff<>(resource);

        TestResource claimed = handoff.claim().orElseThrow();
        handoff.close();

        assertSame(resource, claimed);
        assertEquals(0, closes.get());
        claimed.close();
        assertEquals(1, closes.get());
        assertTrue(handoff.claim().isEmpty());
    }

    private record TestResource(AtomicInteger closes)
            implements AutoCloseable {

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }
}
