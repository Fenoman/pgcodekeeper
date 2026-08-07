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

import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetry.PersistenceReason;

public final class ProjectIndexPersistenceException
        extends IllegalArgumentException {

    private static final long serialVersionUID = -4382598758575462557L;

    private final PersistenceReason reason;

    public ProjectIndexPersistenceException(PersistenceReason reason) {
        super("Project index persistence failed: "
                + Objects.requireNonNull(reason, "reason").name());
        if (reason == PersistenceReason.NONE) {
            throw new IllegalArgumentException(
                    "Persistence failure reason must not be NONE");
        }
        this.reason = reason;
    }

    public PersistenceReason reason() {
        return reason;
    }
}
