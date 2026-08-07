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

final class GetChangesJobCoordinator {

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
            return generation == GetChangesJobCoordinator.this.generation;
        }

        /**
         * Commits state that only the current request may install.
         * <p>
         * The publisher runs under this coordinator's monitor, which the
         * resource-change thread also needs in order to cancel a stale run.
         * It must therefore stay short and strictly non-interactive: a modal
         * dialog would run a nested event loop under the monitor, block
         * resource notifications, and allow a re-entrant Get Changes to
         * invalidate the generation this publisher already passed.
         *
         * @return {@code false} when a newer request superseded this one
         */
        boolean publish(Runnable publisher) {
            synchronized (GetChangesJobCoordinator.this) {
                if (generation != GetChangesJobCoordinator.this.generation) {
                    return false;
                }
                publisher.run();
                return true;
            }
        }

        void complete() {
            synchronized (GetChangesJobCoordinator.this) {
                if (current == this) {
                    current = null;
                }
                cancelable = null;
            }
        }

        private void cancel() {
            Cancelable action;
            synchronized (GetChangesJobCoordinator.this) {
                action = cancelable;
                cancelable = null;
            }
            if (action != null) {
                action.cancel();
            }
        }
    }

    final class Cancellation {

        private final long generation;
        private final boolean activeRequestCancelled;

        private Cancellation(long generation,
                boolean activeRequestCancelled) {
            this.generation = generation;
            this.activeRequestCancelled =
                    activeRequestCancelled;
        }

        boolean cancelledActiveRequest() {
            return activeRequestCancelled;
        }

        boolean publish(Runnable publisher) {
            synchronized (GetChangesJobCoordinator.this) {
                if (generation
                        != GetChangesJobCoordinator.this.generation) {
                    return false;
                }
                pendingActiveCancellation = false;
                publisher.run();
                return true;
            }
        }
    }

    private volatile long generation;
    private Request current;
    private boolean pendingActiveCancellation;

    Request start(Cancelable cancelable) {
        Request previous;
        Request request;
        synchronized (this) {
            previous = current;
            request = new Request(++generation, cancelable);
            current = request;
            pendingActiveCancellation = false;
        }
        if (previous != null) {
            previous.cancel();
        }
        return request;
    }

    Cancellation cancel() {
        return cancel(false);
    }

    Cancellation cancelForNotification() {
        return cancel(true);
    }

    private Cancellation cancel(boolean retainActiveReason) {
        Request previous;
        long cancellationGeneration;
        boolean activeRequestCancelled;
        synchronized (this) {
            cancellationGeneration = ++generation;
            previous = current;
            current = null;
            activeRequestCancelled = previous != null;
            if (retainActiveReason) {
                pendingActiveCancellation |=
                        activeRequestCancelled;
                activeRequestCancelled =
                        pendingActiveCancellation;
            } else {
                pendingActiveCancellation = false;
            }
        }
        if (previous != null) {
            previous.cancel();
        }
        return new Cancellation(cancellationGeneration,
                activeRequestCancelled);
    }
}
