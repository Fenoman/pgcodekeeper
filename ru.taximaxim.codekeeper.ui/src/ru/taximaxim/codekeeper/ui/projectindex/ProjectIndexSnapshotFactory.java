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

import static ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexFormat.DEFINITION_ORDER;
import static ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexFormat.LOCATION_ORDER;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.base.schema.meta.MetaStatement;

public final class ProjectIndexSnapshotFactory {

    private ProjectIndexSnapshotFactory() {
    }

    /**
     * The empty error set, for a build that is only allowed to reach packing
     * when its analysis reported nothing.
     *
     * <p>This is a guard, not a convenience. A full build with any error never
     * gets here — it bypasses persistence entirely — so passing an empty set is
     * true rather than merely convenient, and {@code unresolvedAny} being false
     * throughout follows from that and nothing else. Should the bypass ever be
     * relaxed, the flag would turn into a lie in the dangerous direction: an
     * index claiming every file resolved cleanly when one did not. That must
     * stop the build loudly here instead of quietly shipping a wrong index, and
     * whoever relaxes it owes the honest error paths first.
     *
     * @throws IllegalStateException if the analysis reported anything
     */
    public static Set<String> noPathsWithErrors(List<?> analysisErrors) {
        Objects.requireNonNull(analysisErrors, "analysisErrors");
        if (!analysisErrors.isEmpty()) {
            throw new IllegalStateException(
                    "Project index must not be packed from an analysis that"
                            + " reported errors, and this one reported "
                            + analysisErrors.size());
        }
        return Set.of();
    }

    /**
     * Packs one analysis result into index data.
     *
     * @param pathsWithErrors
     *            files whose parse or analysis produced at least one error,
     *            keyed exactly like {@code definitions} and {@code locations}.
     *            A file listed here gets {@code unresolvedAny}, which forbids
     *            any later increment from assuming the index resolved
     *            everything. Like the other two, this set must stay inside the
     *            enumerated {@code stamps}: a path outside them is rejected
     *            rather than ignored, because silently dropping it would claim
     *            a clean file where the analysis actually failed.
     */
    public static ProjectIndexData create(ProjectIndexIdentity identity,
            long generation, List<ProjectFileStamp> stamps,
            Map<String, ? extends List<MetaStatement>> definitions,
            Map<String, ? extends Set<ObjectLocation>> locations,
            ProjectIndexPathResolver resolver, Set<String> pathsWithErrors) {
        Objects.requireNonNull(identity, "identity");
        if (generation < 0) {
            throw new IllegalArgumentException("Generation must not be negative");
        }
        Objects.requireNonNull(stamps, "stamps");
        Objects.requireNonNull(definitions, "definitions");
        Objects.requireNonNull(locations, "locations");
        Objects.requireNonNull(resolver, "resolver");
        Objects.requireNonNull(pathsWithErrors, "pathsWithErrors");

        Map<Path, List<MetaStatement>> normalizedDefinitions =
                normalize(definitions, "definition");
        Map<Path, Set<ObjectLocation>> normalizedLocations =
                normalize(locations, "location");
        Set<Path> normalizedErrorPaths = new HashSet<>();
        for (String path : pathsWithErrors) {
            normalizedErrorPaths.add(normalize(path));
        }
        Set<Path> expectedPaths = new HashSet<>();
        List<FileContribution> files = new ArrayList<>(stamps.size());
        for (ProjectFileStamp stamp : stamps) {
            Path absolute = normalize(resolver.resolve(stamp.path()));
            if (!expectedPaths.add(absolute)) {
                throw new IllegalArgumentException(
                        "Duplicate resolved project index path");
            }
            List<MetaStatement> fileDefinitions =
                    normalizedDefinitions.getOrDefault(absolute, List.of());
            Set<ObjectLocation> fileLocations =
                    normalizedLocations.getOrDefault(absolute, Set.of());
            files.add(new FileContribution(stamp.path(),
                    fileDefinitions.stream()
                            .map(definition -> PackedDefinition.from(
                                    definition, stamp.path()))
                            .sorted(DEFINITION_ORDER)
                            .toList(),
                    fileLocations.stream()
                            .map(location -> PackedLocation.from(
                                    location, stamp.path()))
                            .sorted(LOCATION_ORDER)
                            .toList(),
                    Set.of(), normalizedErrorPaths.contains(absolute)));
        }

        rejectUnexpected(normalizedDefinitions.keySet(), expectedPaths,
                "definition");
        rejectUnexpected(normalizedLocations.keySet(), expectedPaths,
                "location");
        rejectUnexpected(normalizedErrorPaths, expectedPaths, "error");
        var manifest = new ProjectIndexManifest(ProjectIndexFormat.FORMAT_MAJOR,
                ProjectIndexFormat.FORMAT_MINOR, identity.parserAbi(),
                identity.coreVersion(), identity.uiVersion(),
                identity.databaseType(), identity.projectIdentity(),
                identity.configSha256(), generation, stamps);
        return new ProjectIndexData(manifest, files);
    }

    private static <T> Map<Path, T> normalize(Map<String, ? extends T> source,
            String label) {
        Map<Path, T> normalized = new HashMap<>();
        source.forEach((path, value) -> {
            Path key = normalize(path);
            if (normalized.put(key, Objects.requireNonNull(value, label)) != null) {
                throw new IllegalArgumentException(
                        "Duplicate normalized " + label + " path");
            }
        });
        return normalized;
    }

    private static Path normalize(String path) {
        return Path.of(Objects.requireNonNull(path, "path"))
                .toAbsolutePath().normalize();
    }

    private static void rejectUnexpected(Set<Path> actual, Set<Path> expected,
            String label) {
        if (!expected.containsAll(actual)) {
            throw new IllegalArgumentException(
                    "Project index contains " + label
                            + " data outside the enumerated input set");
        }
    }
}
