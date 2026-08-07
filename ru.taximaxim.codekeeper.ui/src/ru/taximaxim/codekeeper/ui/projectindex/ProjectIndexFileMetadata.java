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

public record ProjectIndexFileMetadata(
        ProjectFileStamp stamp,
        List<PackedDefinition> definitions) {

    public ProjectIndexFileMetadata {
        Objects.requireNonNull(stamp, "stamp");
        definitions = List.copyOf(definitions);
        if (definitions.stream().anyMatch(definition ->
                !stamp.path().equals(definition.object().path()))) {
            throw new IllegalArgumentException(
                    "Project index file definitions must use the stamped path");
        }
    }
}
