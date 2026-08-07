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

/**
 * Immutable identity of the exact persistent-index revision visible to a reader.
 */
public record ProjectIndexRevision(
        String publicationId,
        long committedJournalLength,
        long generation,
        ProjectIndexIdentity identity) {

    public ProjectIndexRevision {
        if (Objects.requireNonNull(publicationId, "publicationId").isBlank()) {
            throw new IllegalArgumentException(
                    "Publication identifier must not be blank");
        }
        if (committedJournalLength < 0 || generation < 0) {
            throw new IllegalArgumentException(
                    "Project index revision values must not be negative");
        }
        Objects.requireNonNull(identity, "identity");
    }
}
