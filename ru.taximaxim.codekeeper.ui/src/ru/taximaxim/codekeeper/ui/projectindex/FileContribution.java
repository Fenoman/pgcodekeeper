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
import java.util.Set;

public record FileContribution(
        IndexPathRef path,
        List<PackedDefinition> definitions,
        List<PackedLocation> locations,
        Set<ReferenceMatchKey> unresolvedCandidates,
        boolean unresolvedAny) {

    public FileContribution {
        Objects.requireNonNull(path, "path");
        definitions = List.copyOf(definitions);
        locations = List.copyOf(locations);
        unresolvedCandidates = Set.copyOf(unresolvedCandidates);
        if (definitions.stream().anyMatch(definition -> !samePath(definition.object(), path))
                || locations.stream().anyMatch(location -> !samePath(location, path))) {
            throw new IllegalArgumentException("Contribution records must use the contribution path");
        }
    }

    private static boolean samePath(PackedLocation location, IndexPathRef path) {
        return location.origin() == path.origin()
                && location.relativePath().equals(path.relativePath());
    }
}
