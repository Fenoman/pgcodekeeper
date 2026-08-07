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

import java.util.List;
import java.util.Objects;

import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexFormat.DirectoryEntry;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexLocatorBuilder.BlockDirectory;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexLocatorBuilder.FileRow;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexLocatorBuilder.SparseTable;

/**
 * Bounded locator metadata captured while the format-2 codec is encoded.
 */
record ProjectIndexLocatorDraft(
        ProjectIndexManifest manifest,
        long codecLength,
        List<DirectoryEntry> entries,
        BlockDirectory strings,
        BlockDirectory definitions,
        BlockDirectory locations,
        List<FileRow> files,
        SparseTable match,
        SparseTable completion,
        SparseTable reverse,
        SparseTable unresolved) {

    ProjectIndexLocatorDraft {
        Objects.requireNonNull(manifest, "manifest");
        if (codecLength <= 0
                || codecLength
                        > ProjectIndexFormat.MAX_IN_MEMORY_INDEX_BYTES) {
            throw new IllegalArgumentException(
                    "Project index codec length is invalid");
        }
        entries = List.copyOf(entries);
        Objects.requireNonNull(strings, "strings");
        Objects.requireNonNull(definitions, "definitions");
        Objects.requireNonNull(locations, "locations");
        files = List.copyOf(files);
        Objects.requireNonNull(match, "match");
        Objects.requireNonNull(completion, "completion");
        Objects.requireNonNull(reverse, "reverse");
        Objects.requireNonNull(unresolved, "unresolved");
    }
}
