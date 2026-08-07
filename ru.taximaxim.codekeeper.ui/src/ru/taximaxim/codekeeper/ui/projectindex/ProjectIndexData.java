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

public record ProjectIndexData(ProjectIndexManifest manifest, List<FileContribution> files) {

    public ProjectIndexData {
        Objects.requireNonNull(manifest, "manifest");
        files = List.copyOf(files);
        var contributionPaths = new HashSet<IndexPathRef>();
        for (FileContribution file : files) {
            if (!contributionPaths.add(file.path())) {
                throw new IllegalArgumentException("Duplicate file contribution: " + file.path());
            }
        }
        var stampPaths = new HashSet<IndexPathRef>();
        for (ProjectFileStamp stamp : manifest.files()) {
            if (!stampPaths.add(stamp.path())) {
                throw new IllegalArgumentException("Duplicate manifest file: " + stamp.path());
            }
        }
        if (!contributionPaths.equals(stampPaths)) {
            throw new IllegalArgumentException("Manifest files and contributions must match");
        }
    }
}
