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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CatalogCacheMaintenanceTest {

    private static final String DIGEST_A = "a".repeat(
            CatalogCacheMaintenance.TARGET_DIGEST_LENGTH);
    private static final String DIGEST_B = "b".repeat(
            CatalogCacheMaintenance.TARGET_DIGEST_LENGTH);

    @Test
    void obsoleteAbiAndUnusedTargetsAreRemoved(@TempDir Path root)
            throws IOException {
        Path current = target(root, "target-v2-" + DIGEST_A);
        Path stale = target(root, "target-v2-" + DIGEST_B);
        Path obsoleteAbi = target(root, "target-v1-" + DIGEST_A);
        Path foreign = Files.createDirectories(root.resolve("not-a-target"));
        touch(stale, Duration.ofDays(90));
        touch(obsoleteAbi, Duration.ZERO);

        assertEquals(2, CatalogCacheMaintenance.prune(
                root, Duration.ofDays(30)));

        assertTrue(Files.isDirectory(current));
        assertFalse(Files.exists(stale));
        assertFalse(Files.exists(obsoleteAbi),
                "a target of a retired cache ABI is unreachable");
        assertTrue(Files.isDirectory(foreign),
                "directories of other components must be left alone");
    }

    @Test
    void recentTargetsOfTheCurrentAbiSurvive(@TempDir Path root)
            throws IOException {
        Path first = target(root, "target-v2-" + DIGEST_A);
        Path second = target(root, "target-v2-" + DIGEST_B);

        assertEquals(0, CatalogCacheMaintenance.prune(
                root, Duration.ofDays(30)));

        assertTrue(Files.isDirectory(first));
        assertTrue(Files.isDirectory(second));
    }

    @Test
    void missingRootIsNotAnError(@TempDir Path root) {
        assertEquals(0, CatalogCacheMaintenance.prune(
                root.resolve("absent"), Duration.ofDays(30)));
    }

    @Test
    void onlyPluginTargetDirectoriesAreRecognized() {
        assertTrue(CatalogCacheMaintenance.targetAbi(
                "target-v2-" + DIGEST_A).isPresent());
        assertTrue(CatalogCacheMaintenance.targetAbi(
                "target-v10-" + DIGEST_A).isPresent());
        assertFalse(CatalogCacheMaintenance.targetAbi(
                "target-v2-" + DIGEST_A + "0").isPresent());
        assertFalse(CatalogCacheMaintenance.targetAbi(
                "target-vx-" + DIGEST_A).isPresent());
        assertFalse(CatalogCacheMaintenance.targetAbi(
                ".pgck-retired-v1-" + DIGEST_A).isPresent());
        assertFalse(CatalogCacheMaintenance.targetAbi(
                "target-v2-" + "z".repeat(
                        CatalogCacheMaintenance.TARGET_DIGEST_LENGTH))
                .isPresent());
    }

    private static Path target(Path root, String name) throws IOException {
        Path target = Files.createDirectories(
                root.resolve(name).resolve("reader-packs"));
        Files.writeString(target.resolve("current.bin"), "payload");
        return root.resolve(name);
    }

    private static void touch(Path target, Duration age) throws IOException {
        FileTime time = FileTime.from(Instant.now().minus(age));
        try (var tree = Files.walk(target)) {
            for (Path path : tree.toList()) {
                Files.setLastModifiedTime(path, time);
            }
        }
    }
}
