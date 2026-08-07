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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgcodekeeper.core.database.api.loader.ProjectInputFingerprint;

class ProjectIndexFilesTest {

    @Test
    void createsStampsFromCompleteCapturedFingerprints(
            @TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(
                temp.resolve("project"));
        Path library = Files.createDirectories(
                temp.resolve("library"));
        Path first = write(
                project.resolve("SCHEMA/app/first.sql"),
                "first");
        Path second = write(
                library.resolve("lib/second.sql"),
                "second");
        var current = ProjectIndexFiles.inspect(
                List.of(first, second), project, library,
                path -> path.origin()
                        == IndexPathOrigin.PROJECT ? 11 : 22);
        var resolver = (ProjectIndexPathResolver) path ->
                (path.origin() == IndexPathOrigin.PROJECT
                        ? project : library)
                                .resolve(path.relativePath())
                                .toString();
        var captured = List.of(
                fingerprint(second, "second"),
                fingerprint(first, "first"));

        var stamps = ProjectIndexFiles
                .stampsFromCapturedFingerprints(
                        current, captured, resolver);

        Assertions.assertTrue(stamps.isPresent());
        Assertions.assertEquals(2, stamps.orElseThrow().size());
        Assertions.assertArrayEquals(sha256("first"),
                stamps.orElseThrow().getFirst()
                        .contentSha256());
        Assertions.assertArrayEquals(sha256("second"),
                stamps.orElseThrow().get(1)
                        .contentSha256());
    }

    @Test
    void incompleteOrMismatchedCaptureRequiresReread(
            @TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(
                temp.resolve("project"));
        Path library = Files.createDirectories(
                temp.resolve("library"));
        Path first = write(project.resolve("first.sql"),
                "first");
        Path extra = write(project.resolve("extra.sql"),
                "extra");
        var current = ProjectIndexFiles.inspect(
                List.of(first), project, library, path -> 1);
        var resolver = (ProjectIndexPathResolver) path ->
                project.resolve(path.relativePath()).toString();
        var valid = fingerprint(first, "first");

        Assertions.assertTrue(ProjectIndexFiles
                .stampsFromCapturedFingerprints(
                        current, List.of(), resolver)
                .isEmpty());
        Assertions.assertTrue(ProjectIndexFiles
                .stampsFromCapturedFingerprints(
                        current, List.of(valid, valid),
                        resolver)
                .isEmpty());
        Assertions.assertTrue(ProjectIndexFiles
                .stampsFromCapturedFingerprints(
                        current,
                        List.of(valid,
                                fingerprint(extra, "extra")),
                        resolver)
                .isEmpty());
        Assertions.assertTrue(ProjectIndexFiles
                .stampsFromCapturedFingerprints(
                        current,
                        List.of(new ProjectInputFingerprint(
                                first, 99,
                                sha256("first"))),
                        resolver)
                .isEmpty());
    }

    @Test
    void inspectsBothRootsAndHashesInBoundedOrder(@TempDir Path temp)
            throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path library = Files.createDirectories(temp.resolve("library"));
        Path first = write(project.resolve("SCHEMA/app/first.sql"), "first");
        Path second = write(library.resolve("lib/second.sql"), "second");

        var current = ProjectIndexFiles.inspect(List.of(first, second),
                project, library, path -> path.origin() == IndexPathOrigin.PROJECT
                        ? 11 : 22);
        var resolver = (ProjectIndexPathResolver) path ->
                (path.origin() == IndexPathOrigin.PROJECT ? project : library)
                        .resolve(path.relativePath()).toString();
        var stamps = ProjectIndexFiles.hashAll(current, resolver, () -> false);

        Assertions.assertEquals(2, current.size());
        Assertions.assertEquals(IndexPathOrigin.PROJECT,
                current.getFirst().path().origin());
        Assertions.assertEquals("SCHEMA/app/first.sql",
                current.getFirst().path().relativePath());
        Assertions.assertEquals(11,
                current.getFirst().eclipseModificationStamp());
        Assertions.assertEquals(IndexPathOrigin.LIBRARY,
                current.get(1).path().origin());
        Assertions.assertEquals(22,
                current.get(1).eclipseModificationStamp());
        Assertions.assertArrayEquals(sha256("first"),
                stamps.getFirst().contentSha256());
        Assertions.assertArrayEquals(sha256("second"),
                stamps.get(1).contentSha256());
    }

    @Test
    void rejectsOutsideAndDuplicateInputs(@TempDir Path temp)
            throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path library = Files.createDirectories(temp.resolve("library"));
        Path inside = write(project.resolve("a.sql"), "a");
        Path outside = write(temp.resolve("outside.sql"), "outside");

        Assertions.assertThrows(java.io.IOException.class,
                () -> ProjectIndexFiles.inspect(List.of(outside),
                        project, library, path -> 1));
        Assertions.assertThrows(java.io.IOException.class,
                () -> ProjectIndexFiles.inspect(List.of(inside, inside),
                        project, library, path -> 1));
    }

    @Test
    void cancellationFailsClosed(@TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path library = Files.createDirectories(temp.resolve("library"));
        Path input = write(project.resolve("large.sql"), "x".repeat(200_000));
        var current = ProjectIndexFiles.inspect(List.of(input), project,
                library, path -> 1);
        var cancelled = new AtomicBoolean(true);

        Assertions.assertThrows(InterruptedException.class,
                () -> ProjectIndexFiles.hashAll(current,
                        path -> project.resolve(path.relativePath()).toString(),
                        cancelled::get));
    }

    private static Path write(Path path, String value) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, value);
        return path;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new AssertionError(ex);
        }
    }

    private static ProjectInputFingerprint fingerprint(
            Path path, String value) {
        return new ProjectInputFingerprint(path,
                value.getBytes(StandardCharsets.UTF_8).length,
                sha256(value));
    }
}
