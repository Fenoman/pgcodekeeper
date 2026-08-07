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

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record ProjectIndexDelta(List<Change> changes) {

    public ProjectIndexDelta {
        changes = List.copyOf(changes);
        if (changes.isEmpty()) {
            throw new IllegalArgumentException("Project index delta must not be empty");
        }
        var paths = new HashSet<IndexPathRef>();
        for (Change change : changes) {
            if (!paths.add(change.path())) {
                throw new IllegalArgumentException("Duplicate project index delta path: "
                        + change.path());
            }
        }
    }

    public record Change(
            Operation operation,
            IndexPathRef path,
            ProjectFileStamp stamp,
            FileContribution contribution) {

        public Change {
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(path, "path");
            if (operation == Operation.REPLACE) {
                Objects.requireNonNull(stamp, "stamp");
                Objects.requireNonNull(contribution, "contribution");
                if (!path.equals(stamp.path()) || !path.equals(contribution.path())) {
                    throw new IllegalArgumentException(
                            "Replacement stamp and contribution paths must match");
                }
            } else if (stamp != null || contribution != null) {
                throw new IllegalArgumentException("Deletion must not retain file data");
            }
        }

        /**
         * States what the index should hold for a path. The path need not be
         * one the index already has: a journal entry is keyed by path and
         * applied as an assignment, so the same record introduces a file the
         * index never held. Compaction folds it into the new base like any
         * other. Do not add a presence check here — additions depend on its
         * absence, and two tests in ProjectIndexStoreTest pin that.
         */
        public static Change replace(ProjectFileStamp stamp, FileContribution contribution) {
            Objects.requireNonNull(stamp, "stamp");
            return new Change(Operation.REPLACE, stamp.path(), stamp, contribution);
        }

        public static Change delete(IndexPathRef path) {
            return new Change(Operation.DELETE, path, null, null);
        }
    }

    public enum Operation {
        REPLACE,
        DELETE
    }
}
