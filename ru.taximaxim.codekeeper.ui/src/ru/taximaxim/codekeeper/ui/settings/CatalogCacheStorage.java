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
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import org.pgcodekeeper.core.utils.ContentAddressedFileStore;

import ru.taximaxim.codekeeper.ui.Activator;
import ru.taximaxim.codekeeper.ui.Log;

/**
 * Read and clear side of the persistent catalog cache, for the preference page.
 * <p>
 * {@link CatalogCacheMaintenance} removes what no comparison can reach any
 * more, automatically and silently. This class answers the two questions the
 * automatic pass cannot: how much disk the cache holds right now, and how to
 * drop all of it on demand. Both operations are potentially long, so they never
 * run on the UI thread.
 */
public final class CatalogCacheStorage {

    /**
     * Cache directory inside the bundle state location.
     * <p>
     * Must stay equal to {@code UISettings.PG_CATALOG_CACHE_DIR_NAME}, which is
     * private to the comparison side. {@code CatalogCacheStorageTest} fails if
     * the two ever drift apart.
     */
    public static final String CACHE_DIR_NAME = "pg-catalog-row-cache"; //$NON-NLS-1$

    private static final int BYTES_PER_UNIT = 1024;
    private static final int CANCELLATION_CHECK_MASK = 255;

    /**
     * Outcome of a {@link #clear(Path)} call.
     *
     * @param removedTargets number of deleted per-target cache directories
     * @param keptTargets    number of target directories that could not be
     *                       deleted, typically because a running comparison
     *                       still holds their files
     * @param freedBytes     bytes released by the deleted directories
     * @param busy           {@code true} when the cache lock could not be
     *                       taken, so nothing was even attempted
     */
    public record ClearResult(int removedTargets, int keptTargets,
            long freedBytes, boolean busy) {

        public boolean isEmpty() {
            return removedTargets == 0 && keptTargets == 0 && !busy;
        }
    }

    private CatalogCacheStorage() {
    }

    /**
     * @return the cache root of the running workspace, or {@code null} when the
     *         bundle has no state location, for example outside a workbench
     */
    public static Path root() {
        Activator activator = Activator.getDefault();
        if (activator == null) {
            return null;
        }
        try {
            return Paths.get(activator.getStateLocation()
                    .append(CACHE_DIR_NAME).toOSString());
        } catch (IllegalStateException ex) {
            // No instance location: a read-only or -data @none workbench.
            Log.log(Log.LOG_WARNING,
                    "Catalog cache root is unavailable", ex); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Sums every regular file below the root. Symbolic links are never
     * followed, and unreadable entries are skipped instead of failing the whole
     * measurement: an approximate size is more useful than none.
     *
     * @param cacheRoot cache root, may be missing
     * @param cancelled polled regularly so a closed preference page stops the
     *                  walk
     * @return total size in bytes, {@code 0} when the root does not exist
     * @throws InterruptedException when {@code cancelled} became {@code true}
     */
    public static long sizeInBytes(Path cacheRoot, BooleanSupplier cancelled)
            throws InterruptedException {
        Objects.requireNonNull(cancelled, "cancelled"); //$NON-NLS-1$
        if (cacheRoot == null
                || !Files.isDirectory(cacheRoot, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        long[] total = { 0 };
        int[] visited = { 0 };
        try {
            Files.walkFileTree(cacheRoot, new SimpleFileVisitor<Path>() {

                @Override
                public FileVisitResult visitFile(Path file,
                        BasicFileAttributes attrs) throws IOException {
                    if (attrs.isRegularFile()) {
                        total[0] += attrs.size();
                    }
                    if ((++visited[0] & CANCELLATION_CHECK_MASK) == 0
                            && cancelled.getAsBoolean()) {
                        return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file,
                        IOException ex) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | UncheckedIOException ex) {
            Log.log(Log.LOG_WARNING,
                    "Catalog cache size is incomplete", ex); //$NON-NLS-1$
        }
        if (cancelled.getAsBoolean()) {
            throw new InterruptedException("Catalog cache measurement cancelled"); //$NON-NLS-1$
        }
        return total[0];
    }

    /**
     * Deletes every recognized per-target cache directory under the root.
     * <p>
     * Only {@code target-vN-<digest>} directories are touched, exactly the
     * names {@link CatalogCacheMaintenance} creates and prunes. Anything else
     * under the root, including the prune lock file itself and directories of
     * other tools, is left alone. The whole pass runs under the same
     * {@link ContentAddressedFileStore#PRUNE_LOCK_FILE} lock the automatic
     * maintenance takes, so it can never race a concurrent prune or another
     * workspace.
     *
     * @param cacheRoot cache root, may be missing
     * @return what was removed and freed
     */
    public static ClearResult clear(Path cacheRoot) {
        if (cacheRoot == null
                || !Files.isDirectory(cacheRoot, LinkOption.NOFOLLOW_LINKS)) {
            return new ClearResult(0, 0, 0, false);
        }
        Path lockPath = cacheRoot.resolve(
                ContentAddressedFileStore.PRUNE_LOCK_FILE);
        try (FileChannel channel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock acquired;
            try {
                acquired = channel.tryLock();
            } catch (OverlappingFileLockException | IOException ex) {
                return new ClearResult(0, 0, 0, true);
            }
            if (acquired == null) {
                // Another workspace or the automatic maintenance owns the root.
                return new ClearResult(0, 0, 0, true);
            }
            try (acquired) {
                return clearLocked(cacheRoot);
            }
        } catch (IOException | RuntimeException ex) {
            Log.log(Log.LOG_WARNING,
                    "Catalog cache could not be cleared", ex); //$NON-NLS-1$
            return new ClearResult(0, 0, 0, true);
        }
    }

    private static ClearResult clearLocked(Path cacheRoot) {
        int removed = 0;
        int kept = 0;
        long freed = 0;
        for (Path target : listTargetDirectories(cacheRoot)) {
            long size = sizeQuietly(target);
            if (deleteTree(target)) {
                removed++;
                freed += size;
            } else {
                kept++;
            }
        }
        return new ClearResult(removed, kept, freed, false);
    }

    private static List<Path> listTargetDirectories(Path cacheRoot) {
        try (Stream<Path> entries = Files.list(cacheRoot)) {
            return entries
                    .filter(entry -> Files.isDirectory(
                            entry, LinkOption.NOFOLLOW_LINKS))
                    .filter(entry -> CatalogCacheMaintenance.targetAbi(
                            entry.getFileName().toString()).isPresent())
                    .toList();
        } catch (IOException | UncheckedIOException ex) {
            return List.of();
        }
    }

    private static long sizeQuietly(Path target) {
        try {
            return sizeInBytes(target, () -> false);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
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

    /**
     * @param bytes size to render
     * @param units localized unit names, smallest first, at least one
     * @return the size in the largest unit that keeps the number below 1024
     */
    public static String formatBytes(long bytes, List<String> units) {
        return formatBytes(bytes, units, Locale.getDefault());
    }

    static String formatBytes(long bytes, List<String> units, Locale locale) {
        if (units.isEmpty()) {
            throw new IllegalArgumentException("units must not be empty"); //$NON-NLS-1$
        }
        double scaled = Math.max(bytes, 0);
        int unit = 0;
        while (scaled >= BYTES_PER_UNIT && unit < units.size() - 1) {
            scaled /= BYTES_PER_UNIT;
            unit++;
        }
        String number = unit == 0
                ? Long.toString((long) scaled)
                : String.format(locale, "%.1f", scaled); //$NON-NLS-1$
        return number + ' ' + units.get(unit);
    }

    /**
     * @param units comma separated localized unit names, smallest first
     * @return the parsed units, never empty
     */
    public static List<String> parseUnits(String units) {
        List<String> parsed = new ArrayList<>();
        for (String unit : units.split(",")) { //$NON-NLS-1$
            String trimmed = unit.trim();
            if (!trimmed.isEmpty()) {
                parsed.add(trimmed);
            }
        }
        return parsed.isEmpty() ? List.of("B") : List.copyOf(parsed); //$NON-NLS-1$
    }
}
