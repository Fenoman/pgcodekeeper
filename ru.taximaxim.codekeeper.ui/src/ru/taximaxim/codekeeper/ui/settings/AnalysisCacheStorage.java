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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import ru.taximaxim.codekeeper.ui.Activator;
import ru.taximaxim.codekeeper.ui.Log;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexState;

/**
 * Disk accounting of the persistent analyzed-model cache.
 * <p>
 * The cache is one directory per project under the plugin state location, so
 * its size is visible and clearable next to the catalog row cache, and a
 * project the user no longer opens does not keep its analysis forever.
 */
public final class AnalysisCacheStorage {

    /** Root directory name of the analyzed-model cache. */
    public static final String CACHE_DIR_NAME = "pg-project-analysis-cache"; //$NON-NLS-1$

    /**
     * A project directory untouched for this long is dropped. The cache is
     * rewritten on every cold comparison, so anything older belongs to a
     * project this workbench no longer compares.
     */
    static final Duration MAX_UNUSED_AGE = Duration.ofDays(30);

    private static final AtomicBoolean SCHEDULED = new AtomicBoolean();
    private static final int CANCELLATION_CHECK_MASK = 255;

    private AnalysisCacheStorage() {
    }

    /**
     * Returns the cache root, or empty when this workbench has no state
     * location, which is the case for a workbench started without a workspace.
     *
     * @return the cache root directory
     */
    public static Optional<Path> root() {
        Activator activator = Activator.getDefault();
        if (activator == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Paths.get(
                    activator.getStateLocation().append(CACHE_DIR_NAME).toString()));
        } catch (IllegalStateException ex) {
            Log.log(Log.LOG_INFO, "Analysis cache has no state location", ex); //$NON-NLS-1$
            return Optional.empty();
        }
    }

    /**
     * Returns the cache directory of one project and schedules the one-shot
     * maintenance pass of this session.
     *
     * @param projectRoot canonical project directory
     * @return per-project cache directory, or empty when unavailable
     * @throws IOException if the project path cannot be canonicalized
     */
    public static Optional<Path> directory(Path projectRoot) throws IOException {
        Objects.requireNonNull(projectRoot, "projectRoot"); //$NON-NLS-1$
        Optional<Path> root = root();
        if (root.isEmpty()) {
            return Optional.empty();
        }
        scheduleOnce(root.orElseThrow());
        return Optional.of(root.orElseThrow()
                .resolve(ProjectIndexState.projectIdentity(projectRoot)));
    }

    /**
     * Runs the maintenance pass at most once per session, off the UI thread.
     *
     * @param cacheRoot cache root to prune
     */
    static void scheduleOnce(Path cacheRoot) {
        if (!SCHEDULED.compareAndSet(false, true)) {
            return;
        }
        var thread = new Thread(() -> prune(cacheRoot, MAX_UNUSED_AGE),
                "pgCodeKeeper-analysis-cache-maintenance"); //$NON-NLS-1$
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Drops per-project directories that have not been rewritten recently.
     *
     * @param cacheRoot    cache root to prune
     * @param maxUnusedAge age after which a project directory is dropped
     * @return number of project directories removed
     */
    static int prune(Path cacheRoot, Duration maxUnusedAge) {
        Objects.requireNonNull(cacheRoot, "cacheRoot"); //$NON-NLS-1$
        long cutoff = System.currentTimeMillis() - maxUnusedAge.toMillis();
        int removed = 0;
        try (Stream<Path> children = Files.list(cacheRoot)) {
            for (Path child : children.sorted().toList()) {
                if (Files.isDirectory(child) && lastUsedMillis(child) < cutoff
                        && deleteTree(child)) {
                    removed++;
                }
            }
        } catch (IOException | RuntimeException ex) {
            Log.log(Log.LOG_INFO, "Analysis cache was not pruned", ex); //$NON-NLS-1$
        }
        return removed;
    }

    /**
     * Sums the regular files below the cache root.
     *
     * @param cacheRoot cache root to measure
     * @param cancelled cancellation probe, polled while walking
     * @return total size in bytes, zero when the root does not exist
     * @throws InterruptedException if the walk was cancelled
     */
    public static long sizeInBytes(Path cacheRoot, BooleanSupplier cancelled)
            throws InterruptedException {
        Objects.requireNonNull(cacheRoot, "cacheRoot"); //$NON-NLS-1$
        Objects.requireNonNull(cancelled, "cancelled"); //$NON-NLS-1$
        long total = 0;
        int seen = 0;
        try (Stream<Path> tree = Files.walk(cacheRoot)) {
            for (Path path : tree.toList()) {
                if ((seen++ & CANCELLATION_CHECK_MASK) == 0 && cancelled.getAsBoolean()) {
                    throw new InterruptedException();
                }
                try {
                    if (Files.isRegularFile(path)) {
                        total += Files.size(path);
                    }
                } catch (IOException ex) {
                    // An entry that vanished mid-walk contributes nothing.
                    Log.log(Log.LOG_INFO, "Analysis cache entry was skipped", ex); //$NON-NLS-1$
                }
            }
        } catch (IOException | RuntimeException ex) {
            Log.log(Log.LOG_INFO, "Analysis cache size is unavailable", ex); //$NON-NLS-1$
        }
        return total;
    }

    /**
     * Removes every project directory of the cache.
     *
     * @param cacheRoot cache root to clear
     * @return number of bytes freed
     */
    public static long clear(Path cacheRoot) {
        Objects.requireNonNull(cacheRoot, "cacheRoot"); //$NON-NLS-1$
        long freed = 0;
        try (Stream<Path> children = Files.list(cacheRoot)) {
            for (Path child : children.sorted().toList()) {
                if (!Files.isDirectory(child)) {
                    continue;
                }
                long size = sizeInBytes(child, () -> false);
                if (deleteTree(child)) {
                    freed += size;
                }
            }
        } catch (IOException | InterruptedException | RuntimeException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Log.log(Log.LOG_INFO, "Analysis cache was not cleared", ex); //$NON-NLS-1$
        }
        return freed;
    }

    /**
     * Returns the newest modification time below a project directory. An
     * unreadable tree reports now, so it is kept rather than dropped.
     */
    private static long lastUsedMillis(Path directory) {
        try (Stream<Path> tree = Files.walk(directory)) {
            return tree.mapToLong(AnalysisCacheStorage::lastModified).max()
                    .orElse(System.currentTimeMillis());
        } catch (IOException | RuntimeException ex) {
            Log.log(Log.LOG_INFO, "Analysis cache age is unavailable", ex); //$NON-NLS-1$
            return System.currentTimeMillis();
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ex) {
            return System.currentTimeMillis();
        }
    }

    private static boolean deleteTree(Path root) {
        try (Stream<Path> tree = Files.walk(root)) {
            List<Path> entries = tree.sorted(Comparator.reverseOrder()).toList();
            for (Path entry : entries) {
                Files.deleteIfExists(entry);
            }
        } catch (IOException | RuntimeException ex) {
            Log.log(Log.LOG_INFO, "Analysis cache entry was not removed", ex); //$NON-NLS-1$
        }
        return !Files.exists(root);
    }

}
