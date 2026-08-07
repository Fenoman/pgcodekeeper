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
package ru.taximaxim.codekeeper.ui.projectindex;

import java.util.Objects;
import java.util.Optional;

public final class ProjectIndexOpenResult implements AutoCloseable {

    /**
     * Result of opening a persistent index generation. Only {@link
     * Status#CORRUPT} reports verified format-level damage and allows the
     * caller to clean the store. {@link Status#RETRYABLE} reports a transient
     * environment failure such as an antivirus file hold or a storage hiccup:
     * the index is unusable for this run and the caller must fall back to a
     * full rebuild, but the store must be kept because a later open can
     * succeed.
     */
    public enum Status {
        HIT,
        MISS,
        STALE,
        RETRYABLE,
        CORRUPT
    }

    private final Status status;
    private final ProjectIndexView view;

    private ProjectIndexOpenResult(Status status, ProjectIndexView view) {
        this.status = Objects.requireNonNull(status, "status");
        this.view = view;
        if ((status == Status.HIT) != (view != null)) {
            throw new IllegalArgumentException("Only a cache hit may contain an index view");
        }
    }

    static ProjectIndexOpenResult hit(ProjectIndexView view) {
        return new ProjectIndexOpenResult(Status.HIT, Objects.requireNonNull(view, "view"));
    }

    static ProjectIndexOpenResult empty(Status status) {
        return new ProjectIndexOpenResult(status, null);
    }

    public Status status() {
        return status;
    }

    public Optional<ProjectIndexView> view() {
        return Optional.ofNullable(view);
    }

    @Override
    public void close() {
        if (view != null) {
            view.close();
        }
    }
}
