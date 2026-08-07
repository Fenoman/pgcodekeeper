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
package ru.taximaxim.codekeeper.ui.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgcodekeeper.core.utils.ContentAddressedFileStore;

class CatalogCacheStorageTest {

    private static final List<String> UNITS =
            List.of("B", "KB", "MB", "GB", "TB");
    private static final String DIGEST_A = "a".repeat(
            CatalogCacheMaintenance.TARGET_DIGEST_LENGTH);
    private static final String DIGEST_B = "b".repeat(
            CatalogCacheMaintenance.TARGET_DIGEST_LENGTH);

    @Test
    void sizeSumsEveryRegularFileBelowTheRoot(@TempDir Path root)
            throws Exception {
        write(root.resolve("target-v2-" + DIGEST_A).resolve("rows.bin"), 1500);
        write(root.resolve("target-v2-" + DIGEST_A)
                .resolve("nested").resolve("more.bin"), 500);
        write(root.resolve("target-v1-" + DIGEST_B).resolve("rows.bin"), 24);
        write(root.resolve(ContentAddressedFileStore.PRUNE_LOCK_FILE), 0);

        assertEquals(2024, CatalogCacheStorage.sizeInBytes(root, () -> false));
    }

    @Test
    void missingRootIsZeroInsteadOfAnError(@TempDir Path root)
            throws Exception {
        assertEquals(0, CatalogCacheStorage.sizeInBytes(
                root.resolve("never-created"), () -> false));
        assertEquals(0, CatalogCacheStorage.sizeInBytes(null, () -> false));
    }

    @Test
    void cancellationStopsTheWalk(@TempDir Path root) throws Exception {
        write(root.resolve("target-v2-" + DIGEST_A).resolve("rows.bin"), 16);

        assertThrows(InterruptedException.class,
                () -> CatalogCacheStorage.sizeInBytes(root, () -> true));
    }

    @Test
    void clearRemovesOnlyRecognizedTargets(@TempDir Path root)
            throws Exception {
        write(root.resolve("target-v2-" + DIGEST_A).resolve("rows.bin"), 1024);
        write(root.resolve("target-v1-" + DIGEST_B).resolve("rows.bin"), 512);
        Path foreign = Files.createDirectories(root.resolve("not-a-target"));
        write(foreign.resolve("keep.bin"), 8);
        Path shortName = Files.createDirectories(
                root.resolve("target-v2-" + DIGEST_A.substring(1)));
        write(root.resolve("loose-file.bin"), 4);

        var result = CatalogCacheStorage.clear(root);

        assertFalse(result.busy());
        assertEquals(2, result.removedTargets());
        assertEquals(0, result.keptTargets());
        assertEquals(1536, result.freedBytes());
        assertFalse(Files.exists(root.resolve("target-v2-" + DIGEST_A)));
        assertFalse(Files.exists(root.resolve("target-v1-" + DIGEST_B)));
        assertTrue(Files.isDirectory(foreign),
                "directories of other components must never be deleted");
        assertTrue(Files.isDirectory(shortName),
                "a truncated digest is not a cache target");
        assertTrue(Files.isRegularFile(root.resolve("loose-file.bin")));
        assertTrue(Files.isDirectory(root), "the root itself survives");
    }

    @Test
    void clearOfAnEmptyOrMissingRootReportsNothingToDo(@TempDir Path root) {
        var missing = CatalogCacheStorage.clear(root.resolve("never-created"));
        assertTrue(missing.isEmpty());
        assertFalse(missing.busy());

        var empty = CatalogCacheStorage.clear(root);
        assertTrue(empty.isEmpty());
        assertEquals(0, empty.freedBytes());
    }

    @Test
    void clearRefusesToRunWhileTheCacheIsLocked(@TempDir Path root)
            throws Exception {
        write(root.resolve("target-v2-" + DIGEST_A).resolve("rows.bin"), 64);
        Path lockPath = root.resolve(ContentAddressedFileStore.PRUNE_LOCK_FILE);

        try (FileChannel channel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock held = channel.lock()) {
            var result = CatalogCacheStorage.clear(root);

            assertTrue(result.busy());
            assertEquals(0, result.removedTargets());
            assertEquals(0, result.freedBytes());
            assertTrue(Files.isDirectory(root.resolve(
                    "target-v2-" + DIGEST_A)),
                    "a locked cache must be left untouched");
        }
    }

    @Test
    void sizeIsRenderedInTheLargestFittingUnit() {
        assertEquals("0 B", CatalogCacheStorage.formatBytes(
                0, UNITS, Locale.ROOT));
        assertEquals("1023 B", CatalogCacheStorage.formatBytes(
                1023, UNITS, Locale.ROOT));
        assertEquals("1.0 KB", CatalogCacheStorage.formatBytes(
                1024, UNITS, Locale.ROOT));
        assertEquals("1.5 MB", CatalogCacheStorage.formatBytes(
                1024 * 1024 * 3 / 2, UNITS, Locale.ROOT));
        assertEquals("2.0 GB", CatalogCacheStorage.formatBytes(
                2L << 30, UNITS, Locale.ROOT));
        assertEquals("0 B", CatalogCacheStorage.formatBytes(
                -5, UNITS, Locale.ROOT), "a broken size never renders negative");
        assertEquals("1024.0 TB", CatalogCacheStorage.formatBytes(
                1L << 50, UNITS, Locale.ROOT),
                "the largest known unit keeps growing instead of overflowing");
    }

    @Test
    void unitsComeFromTheLocalizedBundle() {
        assertEquals(List.of("B", "KB", "MB", "GB", "TB"),
                CatalogCacheStorage.parseUnits("B,KB,MB,GB,TB"));
        assertEquals(List.of("B", "KB"),
                CatalogCacheStorage.parseUnits(" B , KB , "));
        assertEquals(List.of("B"), CatalogCacheStorage.parseUnits(""),
                "a broken translation must not break the preference page");
    }

    @Test
    void cacheDirectoryNameMatchesTheComparisonSide() throws Exception {
        // UISettings owns the directory the comparisons write into. The
        // preference page must show and clear that exact directory.
        Field field = UISettings.class.getDeclaredField(
                "PG_CATALOG_CACHE_DIR_NAME");
        field.setAccessible(true);
        assertEquals(field.get(null), CatalogCacheStorage.CACHE_DIR_NAME);
    }

    private static void write(Path file, int bytes) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[bytes]);
    }
}
