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
package ru.taximaxim.codekeeper.ui.pgdbproject.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgcodekeeper.core.database.api.loader.ProjectInputFingerprint;

import ru.taximaxim.codekeeper.ui.projectindex.IndexPathOrigin;
import ru.taximaxim.codekeeper.ui.projectindex.IndexPathRef;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexPathResolver;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexWarmValidator;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexWarmValidator.CurrentFile;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexWarmValidator.DigestReader;

class ProjectComparisonInputSnapshotTest {

    @Test
    void captureRequiresExactBytesForEveryFullProjectInput(
            @TempDir Path root) throws Exception {
        Path included = write(root.resolve("SCHEMA/app/TABLE/item.sql"),
                "CREATE TABLE app.item(id bigint);");
        Path earlyExcluded = write(
                root.resolve("SCHEMA/dummy_tmp/TABLE/work.sql"),
                "CREATE TABLE dummy_tmp.work(id bigint);");
        var files = List.of(
                current(root, included, 11),
                current(root, earlyExcluded, 12));
        var fingerprints = List.of(
                fingerprint(included),
                fingerprint(earlyExcluded));

        var snapshot = ProjectComparisonInputSnapshot.capture(
                files, fingerprints, resolver(root), () -> false)
                .orElseThrow();

        assertEquals(2, snapshot.fileCount());
        assertTrue(snapshot.validate(
                files, resolver(root), () -> false).hit());
    }

    @Test
    void captureRejectsAFileChangedAfterParserConsumption(
            @TempDir Path root) throws Exception {
        Path file = write(root.resolve("SCHEMA/app/TABLE/item.sql"),
                "CREATE TABLE app.item(id bigint);");
        ProjectInputFingerprint consumed = fingerprint(file);
        Files.writeString(file,
                "CREATE TABLE app.item(id bigint, code text);");
        var current = current(root, file, 2);

        assertTrue(ProjectComparisonInputSnapshot.capture(
                List.of(current), List.of(consumed),
                resolver(root), () -> false).isEmpty());
    }

    @Test
    void unchangedMetadataAvoidsWarmHashing(@TempDir Path root)
            throws Exception {
        Path file = write(root.resolve("SCHEMA/app/TABLE/item.sql"),
                "CREATE TABLE app.item(id bigint);");
        CurrentFile original = current(root, file, 31);
        var snapshot = ProjectComparisonInputSnapshot.capture(
                List.of(original), List.of(fingerprint(file)),
                resolver(root), () -> false).orElseThrow();
        var hashes = new AtomicInteger();

        DigestReader reader = ignored -> {
            hashes.incrementAndGet();
            return sha256(file);
        };
        var result = snapshot.validate(
                List.of(original), reader, () -> false);

        assertTrue(result.hit());
        assertEquals(0, result.hashedFiles());
        assertEquals(0, hashes.get());
    }

    @Test
    void metadataChangeHashesAndAcceptsIdenticalContent(
            @TempDir Path root) throws Exception {
        Path file = write(root.resolve("SCHEMA/app/TABLE/item.sql"),
                "CREATE TABLE app.item(id bigint);");
        CurrentFile original = current(root, file, 41);
        var snapshot = ProjectComparisonInputSnapshot.capture(
                List.of(original), List.of(fingerprint(file)),
                resolver(root), () -> false).orElseThrow();
        CurrentFile touched = new CurrentFile(original.path(), 42,
                original.size(), original.lastModifiedMillis() + 1);
        var hashes = new AtomicInteger();

        DigestReader reader = ignored -> {
            hashes.incrementAndGet();
            return sha256(file);
        };
        var result = snapshot.validate(
                List.of(touched), reader, () -> false);

        assertTrue(result.hit());
        assertEquals(1, result.hashedFiles());
        assertEquals(1, hashes.get());
    }

    @Test
    void addedDeletedAndChangedInputsInvalidate(@TempDir Path root)
            throws Exception {
        Path first = write(root.resolve("SCHEMA/app/TABLE/item.sql"),
                "CREATE TABLE app.item(id bigint);");
        Path second = write(root.resolve("SCHEMA/app/TABLE/code.sql"),
                "CREATE TABLE app.code(id bigint);");
        CurrentFile firstCurrent = current(root, first, 51);
        var snapshot = ProjectComparisonInputSnapshot.capture(
                List.of(firstCurrent), List.of(fingerprint(first)),
                resolver(root), () -> false).orElseThrow();

        assertFalse(snapshot.validate(List.of(), resolver(root),
                () -> false).hit());
        assertFalse(snapshot.validate(List.of(
                firstCurrent, current(root, second, 52)),
                resolver(root), () -> false).hit());

        Files.writeString(first,
                "CREATE TABLE app.item(id bigint, code text);");
        CurrentFile changed = current(root, first, 53);
        assertFalse(snapshot.validate(List.of(changed),
                resolver(root), () -> false).hit());
    }

    @Test
    void cancellationInterruptsCaptureAndValidation(
            @TempDir Path root) throws Exception {
        Path file = write(root.resolve("SCHEMA/app/TABLE/item.sql"),
                "CREATE TABLE app.item(id bigint);");
        CurrentFile current = current(root, file, 61);
        var cancelled = new AtomicBoolean(true);

        assertThrows(InterruptedException.class,
                () -> ProjectComparisonInputSnapshot.capture(
                        List.of(current), List.of(fingerprint(file)),
                        resolver(root), cancelled::get));

        cancelled.set(false);
        var snapshot = ProjectComparisonInputSnapshot.capture(
                List.of(current), List.of(fingerprint(file)),
                resolver(root), cancelled::get).orElseThrow();
        cancelled.set(true);

        assertThrows(InterruptedException.class,
                () -> snapshot.validate(List.of(current),
                        resolver(root), cancelled::get));
    }

    private static Path write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        // Back-date the fixture past the warm validator settle window. A file
        // whose modification time can still collide with a further write in
        // the same millisecond is always compared by content, which would
        // otherwise change the hashing counters these tests assert.
        Files.setLastModifiedTime(file,
                java.nio.file.attribute.FileTime.fromMillis(
                        System.currentTimeMillis() - 10
                                * ProjectIndexWarmValidator
                                        .MODIFICATION_SETTLE_MILLIS));
        return file;
    }

    private static CurrentFile current(Path root, Path file, long stamp)
            throws Exception {
        var attributes = Files.readAttributes(file,
                java.nio.file.attribute.BasicFileAttributes.class);
        return new CurrentFile(new IndexPathRef(IndexPathOrigin.PROJECT,
                root.relativize(file).toString()), stamp,
                attributes.size(),
                attributes.lastModifiedTime().toMillis());
    }

    private static ProjectInputFingerprint fingerprint(Path file)
            throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        return new ProjectInputFingerprint(file, bytes.length,
                MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static ProjectIndexPathResolver resolver(Path root) {
        return path -> root.resolve(path.relativePath()).toString();
    }

    private static byte[] sha256(Path file) throws java.io.IOException {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(file));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new AssertionError(ex);
        }
    }
}
