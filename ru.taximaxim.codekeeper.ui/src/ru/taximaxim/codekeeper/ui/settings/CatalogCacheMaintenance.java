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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.pgcodekeeper.core.utils.ContentAddressedFileStore;

import ru.taximaxim.codekeeper.ui.Log;

/**
 * Removes persistent catalog caches that no comparison can reach any more.
 * <p>
 * Every comparison target owns a {@code target-vN-<digest>} directory under
 * one cache root. Core bounds each of those directories, but nothing bounds
 * their number: every database, role or server setting the user ever compared
 * against leaves one behind, and a cache ABI upgrade orphans whole
 * directories at once.
 */
public final class CatalogCacheMaintenance {

    static final String TARGET_PREFIX = "target-v"; //$NON-NLS-1$
    static final int TARGET_DIGEST_LENGTH = 64;
    /**
     * A target that was not used for this long is very unlikely to be compared
     * against again, and rebuilding its cache costs one cold comparison.
     */
    static final Duration MAX_UNUSED_AGE = Duration.ofDays(30);

    private static final AtomicBoolean SCHEDULED = new AtomicBoolean();

    private CatalogCacheMaintenance() {
    }

    /**
     * Runs the cleanup once per session, off the calling thread. Comparisons
     * must never wait for cache maintenance.
     */
    static void scheduleOnce(Path cacheRoot) {
        Objects.requireNonNull(cacheRoot, "cacheRoot"); //$NON-NLS-1$
        if (!SCHEDULED.compareAndSet(false, true)) {
            return;
        }
        Thread worker = new Thread(() -> prune(cacheRoot, MAX_UNUSED_AGE),
                "pgCodeKeeper-catalog-cache-maintenance"); //$NON-NLS-1$
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Deletes orphaned target caches under the root.
     *
     * @param cacheRoot   root that holds the per-target directories
     * @param maxUnusedAge age after which an untouched target is dropped
     * @return number of deleted target directories
     */
    static int prune(Path cacheRoot, Duration maxUnusedAge) {
        Objects.requireNonNull(cacheRoot, "cacheRoot"); //$NON-NLS-1$
        Objects.requireNonNull(maxUnusedAge, "maxUnusedAge"); //$NON-NLS-1$
        if (!Files.isDirectory(cacheRoot, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }

        Path lockPath = cacheRoot.resolve(
                ContentAddressedFileStore.PRUNE_LOCK_FILE);
        try (FileChannel channel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock acquired;
            try {
                acquired = channel.tryLock();
            } catch (OverlappingFileLockException | IOException ex) {
                return 0;
            }
            if (acquired == null) {
                // Another workspace is already maintaining this root.
                return 0;
            }
            try (acquired) {
                return pruneLocked(cacheRoot, maxUnusedAge);
            }
        } catch (IOException | RuntimeException ex) {
            Log.log(Log.LOG_WARNING,
                    "Catalog cache maintenance skipped", ex); //$NON-NLS-1$
            return 0;
        }
    }

    private static int pruneLocked(Path cacheRoot, Duration maxUnusedAge) {
        List<Path> targets = listTargetDirectories(cacheRoot);
        OptionalInt currentAbi = targets.stream()
                .mapToInt(target -> targetAbi(
                        target.getFileName().toString()).orElse(-1))
                .filter(abi -> abi >= 0)
                .max();
        if (currentAbi.isEmpty()) {
            return 0;
        }

        long oldest = System.currentTimeMillis() - maxUnusedAge.toMillis();
        int deleted = 0;
        for (Path target : targets) {
            String name = target.getFileName().toString();
            boolean obsoleteAbi = targetAbi(name)
                    .orElse(-1) != currentAbi.getAsInt();
            if (obsoleteAbi || lastUsedMillis(target) < oldest) {
                deleted += deleteTree(target) ? 1 : 0;
            }
        }
        return deleted;
    }

    private static List<Path> listTargetDirectories(Path cacheRoot) {
        try (Stream<Path> entries = Files.list(cacheRoot)) {
            return entries
                    .filter(entry -> Files.isDirectory(
                            entry, LinkOption.NOFOLLOW_LINKS))
                    .filter(entry -> targetAbi(
                            entry.getFileName().toString()).isPresent())
                    .toList();
        } catch (IOException | UncheckedIOException ex) {
            return List.of();
        }
    }

    /**
     * @return the cache ABI version of a {@code target-vN-<digest>} directory,
     *         or empty when the name does not belong to this plugin
     */
    static OptionalInt targetAbi(String directoryName) {
        if (!directoryName.startsWith(TARGET_PREFIX)) {
            return OptionalInt.empty();
        }
        int separator = directoryName.indexOf('-', TARGET_PREFIX.length());
        if (separator < 0 || separator == TARGET_PREFIX.length()
                || directoryName.length()
                        != separator + 1 + TARGET_DIGEST_LENGTH) {
            return OptionalInt.empty();
        }
        for (int i = TARGET_PREFIX.length(); i < separator; i++) {
            char c = directoryName.charAt(i);
            if (c < '0' || c > '9') {
                return OptionalInt.empty();
            }
        }
        for (int i = separator + 1; i < directoryName.length(); i++) {
            char c = directoryName.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
                return OptionalInt.empty();
            }
        }
        try {
            return OptionalInt.of(Integer.parseInt(directoryName.substring(
                    TARGET_PREFIX.length(), separator)));
        } catch (NumberFormatException ex) {
            return OptionalInt.empty();
        }
    }

    /**
     * @return the newest modification time inside the target, because the
     *         directory itself is not touched when a published file is read
     */
    private static long lastUsedMillis(Path target) {
        long newest = modifiedMillis(target);
        try (Stream<Path> tree = Files.walk(target)) {
            return Math.max(newest, tree
                    .mapToLong(CatalogCacheMaintenance::modifiedMillis)
                    .max().orElse(newest));
        } catch (IOException | UncheckedIOException ex) {
            // Unreadable trees are kept: deleting them is riskier than leaving
            // them for the next run.
            return System.currentTimeMillis();
        }
    }

    private static long modifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ex) {
            return 0;
        }
    }

    private static boolean deleteTree(Path root) {
        List<Path> entries = new ArrayList<>();
        try (Stream<Path> tree = Files.walk(root)) {
            tree.sorted(Comparator.reverseOrder()).forEach(entries::add);
        } catch (IOException | UncheckedIOException ex) {
            return false;
        }
        boolean deleted = true;
        for (Path entry : entries) {
            try {
                Files.deleteIfExists(entry);
            } catch (IOException ex) {
                // A cache in use by another process stays where it is.
                deleted = false;
            }
        }
        return deleted && !Files.exists(root, LinkOption.NOFOLLOW_LINKS);
    }
}
