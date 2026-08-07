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

final class GitUserLoadCoordinator {

    @FunctionalInterface
    interface Cancelable {
        void cancel();
    }

    final class Request {

        private final long generation;
        private Cancelable cancelable;

        private Request(long generation, Cancelable cancelable) {
            this.generation = generation;
            this.cancelable = cancelable;
        }

        boolean isCurrent() {
            return generation == GitUserLoadCoordinator.this.generation;
        }

        boolean publish(Runnable publisher) {
            synchronized (GitUserLoadCoordinator.this) {
                if (generation != GitUserLoadCoordinator.this.generation) {
                    return false;
                }
                publisher.run();
                return true;
            }
        }

        void complete() {
            synchronized (GitUserLoadCoordinator.this) {
                if (current == this) {
                    current = null;
                }
                cancelable = null;
            }
        }

        private void cancel() {
            Cancelable action;
            synchronized (GitUserLoadCoordinator.this) {
                action = cancelable;
                cancelable = null;
            }
            if (action != null) {
                action.cancel();
            }
        }
    }

    private volatile long generation;
    private Request current;

    Request start(Cancelable cancelable) {
        Request previous;
        Request request;
        synchronized (this) {
            previous = current;
            request = new Request(++generation, cancelable);
            current = request;
        }
        if (previous != null) {
            previous.cancel();
        }
        return request;
    }

    void cancel() {
        Request previous;
        synchronized (this) {
            ++generation;
            previous = current;
            current = null;
        }
        if (previous != null) {
            previous.cancel();
        }
    }
}
