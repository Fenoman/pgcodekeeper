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

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ru.taximaxim.codekeeper.ui.DatabaseType;

/**
 * The {@code unresolvedAny} flag decides whether a later increment may assume
 * the index resolved everything it could. A file that wrongly claims to be
 * clean is the dangerous direction: the index would keep a stale contribution
 * and the migration script would order dependencies wrongly. So these tests
 * check attribution, not just the flag — a set that reaches the factory in the
 * wrong path form would leave every file clean and stay silent otherwise.
 */
class ProjectIndexSnapshotFactoryUnresolvedTest {

    @Test
    void aFileWithoutErrorsIsNotMarkedUnresolved(@TempDir Path root) {
        IndexPathRef path = ref("SCHEMA/app/app.sql");

        ProjectIndexData data = ProjectIndexSnapshotFactory.create(identity(),
                1L, List.of(stamp(path)), Map.of(), Map.of(), resolver(root),
                Set.of());

        Assertions.assertFalse(data.files().getFirst().unresolvedAny(),
                "a clean file must not claim it may hold an unresolved reference");
    }

    @Test
    void aFileWithAnErrorIsMarkedUnresolved(@TempDir Path root) {
        IndexPathRef path = ref("SCHEMA/app/app.sql");

        ProjectIndexData data = ProjectIndexSnapshotFactory.create(identity(),
                1L, List.of(stamp(path)), Map.of(), Map.of(), resolver(root),
                Set.of(absolute(root, path)));

        Assertions.assertTrue(data.files().getFirst().unresolvedAny(),
                "a file whose analysis failed must forbid a later increment");
    }

    @Test
    void onlyTheFileThatFailedIsMarkedUnresolved(@TempDir Path root) {
        IndexPathRef broken = ref("SCHEMA/app/broken.sql");
        IndexPathRef clean = ref("SCHEMA/app/clean.sql");

        ProjectIndexData data = ProjectIndexSnapshotFactory.create(identity(),
                1L, List.of(stamp(broken), stamp(clean)), Map.of(), Map.of(),
                resolver(root), Set.of(absolute(root, broken)));

        Assertions.assertEquals(List.of(true, false),
                data.files().stream()
                        .map(FileContribution::unresolvedAny)
                        .toList(),
                "the flag must follow the file that failed, not spread or vanish");
    }

    @Test
    void anErrorPathOutsideTheEnumeratedFilesIsRejected(@TempDir Path root) {
        IndexPathRef enumerated = ref("SCHEMA/app/app.sql");
        String stranger = root.resolve("SCHEMA/app/stranger.sql").toString();

        IllegalArgumentException failure = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> ProjectIndexSnapshotFactory.create(identity(), 1L,
                        List.of(stamp(enumerated)), Map.of(), Map.of(),
                        resolver(root), Set.of(stranger)));

        Assertions.assertTrue(failure.getMessage().contains("outside"),
                "an unmatched error path means the caller keyed the set wrongly;"
                        + " dropping it would report a clean file: "
                        + failure.getMessage());
    }

    /**
     * The full build is only reachable here with a clean analysis, which is what
     * makes an empty error set true rather than merely convenient. Relaxing that
     * has to stop the build, not ship an index claiming every file resolved.
     */
    @Test
    void packingRefusesAnAnalysisThatReportedErrors() {
        IllegalStateException failure = Assertions.assertThrows(
                IllegalStateException.class,
                () -> ProjectIndexSnapshotFactory.noPathsWithErrors(
                        List.of("one error", "another")));

        Assertions.assertTrue(failure.getMessage().contains("2"),
                "the refusal should say how much it found: "
                        + failure.getMessage());
    }

    @Test
    void packingAcceptsAnAnalysisThatReportedNothing() {
        Assertions.assertEquals(Set.of(),
                ProjectIndexSnapshotFactory.noPathsWithErrors(List.of()));
    }

    private static ProjectIndexPathResolver resolver(Path root) {
        return path -> root.resolve(path.relativePath()).toString();
    }

    private static String absolute(Path root, IndexPathRef path) {
        return root.resolve(path.relativePath()).toString();
    }

    private static IndexPathRef ref(String relativePath) {
        return new IndexPathRef(IndexPathOrigin.PROJECT, relativePath);
    }

    private static ProjectIndexIdentity identity() {
        return new ProjectIndexIdentity(2, "15.0.0-neo1", "15.0.0-neo1",
                DatabaseType.PG, "a".repeat(64), sha256("configuration"));
    }

    private static ProjectFileStamp stamp(IndexPathRef path) {
        return new ProjectFileStamp(path, 1, 2, 3, sha256(path.toString()));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new AssertionError(ex);
        }
    }
}
