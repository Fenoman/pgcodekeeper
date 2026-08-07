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

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;

import ru.taximaxim.codekeeper.ui.Log;

/**
 * A process-local, single-generation cache with exclusive comparison leases.
 * Candidates remain private until the UI accepts the comparison result.
 *
 * @param <T> reusable model resource
 */
final class ProjectComparisonModelCache<T extends AutoCloseable>
        implements AutoCloseable {

    /**
     * Analyzed project models are the largest objects the plugin keeps alive,
     * and one editor per project would otherwise retain one each. Publishing a
     * model therefore retires every other retained model in this workbench.
     * A retired model that some editor still displays stays alive until that
     * editor releases it, so eviction never breaks a shown comparison.
     */
    private static final Set<ProjectComparisonModelCache<?>> RETAINING =
            Collections.newSetFromMap(new WeakHashMap<>());

    private Entry<T> retained;
    private boolean closed;

    synchronized Optional<Lease> acquire(Object key) {
        if (closed || retained == null || retained.retired
                || retained.leased
                || !Objects.equals(retained.key, key)) {
            return Optional.empty();
        }
        retained.leased = true;
        return Optional.of(new Lease(retained));
    }

    synchronized Optional<Lease> acquireMatching(
            Predicate<Object> keyPredicate) {
        Objects.requireNonNull(keyPredicate,
                "keyPredicate"); //$NON-NLS-1$
        if (closed || retained == null || retained.retired
                || retained.leased
                || !keyPredicate.test(retained.key)) {
            return Optional.empty();
        }
        retained.leased = true;
        return Optional.of(new Lease(retained));
    }

    Candidate prepare(Object key, T model) {
        return new Candidate(Objects.requireNonNull(key, "key"), //$NON-NLS-1$
                Objects.requireNonNull(model, "model")); //$NON-NLS-1$
    }

    void invalidate(Lease lease) {
        Objects.requireNonNull(lease, "lease"); //$NON-NLS-1$
        Entry<T> close = null;
        synchronized (this) {
            if (lease.owner != this || lease.closed) {
                return;
            }
            Entry<T> entry = lease.entry;
            entry.retired = true;
            if (retained == entry && !entry.leased) {
                retained = null;
            }
            if (!entry.leased) {
                close = entry;
            }
        }
        closeEntry(close);
    }

    synchronized int retainedEntries() {
        return retained == null || retained.retired ? 0 : 1;
    }

    /**
     * @return {@code true} when this cache no longer accepts models at all, as
     *         opposed to merely being busy with an earlier generation
     */
    synchronized boolean isClosed() {
        return closed;
    }

    void invalidateRetained() {
        Entry<T> close = null;
        synchronized (this) {
            if (retained != null) {
                retained.retired = true;
                if (!retained.leased) {
                    close = retained;
                    retained = null;
                }
            }
        }
        forgetRetaining();
        closeEntry(close);
    }

    @Override
    public void close() {
        Entry<T> close = null;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            if (retained != null) {
                retained.retired = true;
                if (!retained.leased) {
                    close = retained;
                }
                retained = null;
            }
        }
        forgetRetaining();
        closeEntry(close);
    }

    private Optional<Lease> publish(Candidate candidate) {
        Entry<T> previous = null;
        Entry<T> published = null;
        T rejected = null;
        synchronized (this) {
            synchronized (candidate) {
                if (candidate.state != CandidateState.PREPARED) {
                    return Optional.empty();
                }
                if (closed || retained != null && retained.leased) {
                    rejected = candidate.model;
                    candidate.model = null;
                    candidate.state = CandidateState.CLOSED;
                } else {
                    previous = retained;
                    if (previous != null) {
                        previous.retired = true;
                        retained = null;
                    }
                    published = new Entry<>(
                            candidate.key, candidate.model);
                    published.leased = true;
                    retained = published;
                    candidate.model = null;
                    candidate.state = CandidateState.PUBLISHED;
                }
            }
        }
        closeEntry(previous);
        closeModel(rejected);
        if (published == null) {
            return Optional.empty();
        }
        evictOtherRetainedModels();
        return Optional.of(new Lease(published));
    }

    /**
     * Enforces the workspace-wide budget of one retained analyzed model.
     * Victims are collected first: retiring them takes their own monitors, and
     * taking those while holding this cache's monitor could deadlock two
     * caches that publish at the same time.
     */
    private void evictOtherRetainedModels() {
        List<ProjectComparisonModelCache<?>> victims;
        synchronized (RETAINING) {
            victims = RETAINING.stream()
                    .filter(cache -> cache != this).toList();
            RETAINING.removeAll(victims);
            RETAINING.add(this);
        }
        victims.forEach(ProjectComparisonModelCache::invalidateRetained);
    }

    private void forgetRetaining() {
        synchronized (RETAINING) {
            RETAINING.remove(this);
        }
    }

    private void release(Lease lease) {
        Entry<T> close = null;
        synchronized (this) {
            if (lease.closed) {
                return;
            }
            lease.closed = true;
            Entry<T> entry = lease.entry;
            if (entry.leased) {
                entry.leased = false;
            }
            if (entry.retired || closed && retained != entry) {
                if (retained == entry) {
                    retained = null;
                }
                close = entry;
            }
        }
        closeEntry(close);
    }

    private static <T extends AutoCloseable> void closeEntry(
            Entry<T> entry) {
        if (entry == null || entry.resourceClosed) {
            return;
        }
        entry.resourceClosed = true;
        closeModel(entry.model);
    }

    private static void closeModel(AutoCloseable model) {
        if (model == null) {
            return;
        }
        try {
            model.close();
        } catch (Exception ex) {
            Log.log(ex);
        }
    }

    final class Candidate implements AutoCloseable {

        private final Object key;
        private T model;
        private CandidateState state = CandidateState.PREPARED;

        private Candidate(Object key, T model) {
            this.key = key;
            this.model = model;
        }

        Optional<Lease> publish() {
            return ProjectComparisonModelCache.this.publish(this);
        }

        synchronized boolean isPublished() {
            return state == CandidateState.PUBLISHED;
        }

        @Override
        public void close() {
            T close = null;
            synchronized (this) {
                if (state == CandidateState.PREPARED) {
                    close = model;
                    model = null;
                    state = CandidateState.CLOSED;
                }
            }
            closeModel(close);
        }
    }

    final class Lease implements AutoCloseable {

        private final ProjectComparisonModelCache<T> owner =
                ProjectComparisonModelCache.this;
        private final Entry<T> entry;
        private boolean closed;

        private Lease(Entry<T> entry) {
            this.entry = entry;
        }

        T model() {
            synchronized (ProjectComparisonModelCache.this) {
                if (closed) {
                    throw new IllegalStateException(
                            "Project comparison model lease is closed"); //$NON-NLS-1$
                }
                return entry.model;
            }
        }

        @Override
        public void close() {
            ProjectComparisonModelCache.this.release(this);
        }
    }

    private static final class Entry<T extends AutoCloseable> {

        private final Object key;
        private final T model;
        private boolean leased;
        private boolean retired;
        private boolean resourceClosed;

        private Entry(Object key, T model) {
            this.key = key;
            this.model = model;
        }
    }

    private enum CandidateState {
        PREPARED,
        PUBLISHED,
        CLOSED
    }
}
