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
package ru.taximaxim.codekeeper.ui.builders;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongFunction;

final class BuilderPublicationGate {

    record Started<T>(long generation, T value) {
    }

    @FunctionalInterface
    interface Publication {
        void run() throws IOException;
    }

    private final AtomicLong generation = new AtomicLong();
    private boolean cancelled;

    synchronized long start() {
        return start(ignored -> null).generation();
    }

    synchronized <T> Started<T> start(
            LongFunction<? extends T> starter) {
        cancelled = false;
        long startedGeneration = generation.incrementAndGet();
        try {
            return new Started<>(startedGeneration,
                    starter.apply(startedGeneration));
        } catch (RuntimeException | Error ex) {
            cancelled = true;
            generation.incrementAndGet();
            throw ex;
        }
    }

    synchronized boolean cancel(long expected, Runnable cancellation) {
        if (generation.get() != expected) {
            return false;
        }
        cancelled = true;
        generation.incrementAndGet();
        cancellation.run();
        return true;
    }

    synchronized boolean publish(long expected, Publication publisher) throws IOException {
        if (cancelled || generation.get() != expected) {
            return false;
        }
        publisher.run();
        return true;
    }
}
