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
package ru.taximaxim.codekeeper.ui.differ;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.errors.NoWorkTreeException;
import org.eclipse.jgit.lib.BaseRepositoryBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.IndexDiff;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.eclipse.jgit.util.io.NullOutputStream;

import ru.taximaxim.codekeeper.ui.Log;
import ru.taximaxim.codekeeper.ui.localizations.Messages;

final class GitUserReader implements AutoCloseable {

    public static boolean checkRepo(Path path) {
        FileRepositoryBuilder builder = new FileRepositoryBuilder().findGitDir(path.toFile());
        return builder.getGitDir() != null || builder.getWorkTree() != null;
    }

    private final Repository repo;

    /**
     * Base constructor that search git repository from given path
     *
     * @param path project path
     * @throws IOException the repository could not be accessed to configure the rest of the builder's parameters
     * @throws IllegalArgumentException if repository not found
     * @see BaseRepositoryBuilder#requireGitDirOrWorkTree
     */
    public GitUserReader(Path path) throws IOException {
        FileRepositoryBuilder builder = new FileRepositoryBuilder().findGitDir(path.toFile());
        repo = builder.build();
    }

    public Path getLocation() {
        return repo.isBare()
                ? repo.getDirectory().getParentFile().toPath()
                : repo.getWorkTree().toPath();
    }

    Path getGitDirectory() throws IOException {
        return repo.getDirectory().toPath().toRealPath();
    }

    /**
     * Looks for the author of the latest changes from the history of git.
     * The values ​​found will be removed from the map
     *
     * @param metas map with elements meta information grouped by their location
     */
    public void parseLastChange(Map<String, List<ElementMetaInfo>> metas) {
        parseLastChange(metas, () -> false);
    }

    public void parseLastChange(Map<String, List<ElementMetaInfo>> metas,
            BooleanSupplier isCancelled) {
        parseLastChange(metas, null, isCancelled);
    }

    HistoryStats parseLastChange(Map<String, List<ElementMetaInfo>> metas,
            GitAuthorCache cache, BooleanSupplier isCancelled) {
        int requested = metas.size();
        if (isCancelled.getAsBoolean() || metas.isEmpty()) {
            return HistoryStats.empty(requested,
                    isCancelled.getAsBoolean());
        }

        ObjectId head;
        try {
            head = repo.resolve(Constants.HEAD);
        } catch (IOException ex) {
            Log.log(Log.LOG_ERROR,
                    Messages.DiffTableViewer_error_reading_git_history, ex);
            return HistoryStats.empty(requested, false);
        }
        if (head == null || isCancelled.getAsBoolean()) {
            return HistoryStats.empty(requested,
                    isCancelled.getAsBoolean());
        }

        long cacheReadNanos = 0;
        int cacheHits = 0;
        boolean memoryHit = false;
        boolean diskHit = false;
        boolean snapshotMatched = false;
        Path gitDirectory = null;
        if (cache != null) {
            try {
                gitDirectory = getGitDirectory();
            } catch (IOException ex) {
                cache = null;
            }
        }
        if (cache != null) {
            long started = System.nanoTime();
            GitAuthorCache.Lookup lookup =
                    cache.lookup(gitDirectory, head.name(), metas.keySet());
            cacheReadNanos = System.nanoTime() - started;
            memoryHit = lookup.memoryHit();
            diskHit = lookup.diskHit();
            snapshotMatched = lookup.snapshotMatched();
            for (Map.Entry<String, String> hit
                    : lookup.authors().entrySet()) {
                if (isCancelled.getAsBoolean()) {
                    return new HistoryStats(requested, cacheHits, 0,
                            cacheReadNanos, 0, 0, memoryHit, diskHit,
                            snapshotMatched, false, true, false);
                }
                List<ElementMetaInfo> meta = metas.remove(hit.getKey());
                if (meta != null) {
                    meta.forEach(e -> e.setGitUser(hit.getValue()));
                    cacheHits++;
                }
            }
        }
        if (metas.isEmpty() || isCancelled.getAsBoolean()) {
            return new HistoryStats(requested, cacheHits, 0,
                    cacheReadNanos, 0, 0, memoryHit, diskHit,
                    snapshotMatched, false,
                    isCancelled.getAsBoolean(), true);
        }

        Map<String, String> resolved = new LinkedHashMap<>();
        boolean historyComplete = false;
        long historyStarted = System.nanoTime();
        try (RevWalk r = new RevWalk(repo);
                DiffFormatter df =
                        new DiffFormatter(NullOutputStream.INSTANCE)) {
            r.setRevFilter(RevFilter.NO_MERGES);
            r.markStart(r.lookupCommit(head));
            df.setRepository(repo);
            df.setPathFilter(requestedPathFilter(metas));
            Iterator<RevCommit> it = r.iterator();

            while (!isCancelled.getAsBoolean() && it.hasNext()
                    && !metas.isEmpty()) {
                RevCommit commit = it.next();
                String author = commit.getAuthorIdent().getName();
                RevTree parent = commit.getParentCount() > 0
                        ? r.parseCommit(commit.getParent(0).getId()).getTree()
                        : null;

                List<DiffEntry> de = df.scan(parent, commit.getTree());
                for (DiffEntry d : de) {
                    if (isCancelled.getAsBoolean()) {
                        break;
                    }
                    List<ElementMetaInfo> meta =
                            metas.remove(d.getNewPath());
                    if (meta != null) {
                        meta.forEach(e -> e.setGitUser(author));
                        resolved.put(d.getNewPath(), author);
                    }
                }
            }
            historyComplete = !isCancelled.getAsBoolean();
        } catch (IOException ex) {
            Log.log(Log.LOG_ERROR,
                    Messages.DiffTableViewer_error_reading_git_history, ex);
        }
        long historyNanos = System.nanoTime() - historyStarted;

        long cacheWriteNanos = 0;
        boolean cacheStored = false;
        if (cache != null && historyComplete && !resolved.isEmpty()) {
            long started = System.nanoTime();
            cacheStored = cache.store(gitDirectory, head.name(), resolved);
            cacheWriteNanos = System.nanoTime() - started;
        }
        return new HistoryStats(requested, cacheHits, resolved.size(),
                cacheReadNanos, historyNanos, cacheWriteNanos,
                memoryHit, diskHit, snapshotMatched, cacheStored,
                isCancelled.getAsBoolean(), historyComplete);
    }

    public void parseLocalChanges(Map<String, List<ElementMetaInfo>> metas) {
        parseLocalChanges(metas, () -> false);
    }

    public void parseLocalChanges(Map<String, List<ElementMetaInfo>> metas, BooleanSupplier isCancelled) {
        if (isCancelled.getAsBoolean() || repo.isBare() || metas.isEmpty()) {
            return;
        }
        try {
            ObjectId head = repo.resolve(Constants.HEAD);
            IndexDiff status = new IndexDiff(repo, head, new FileTreeIterator(repo));
            status.setFilter(requestedPathFilter(metas));
            status.diff(new CancellationProgressMonitor(isCancelled), 0, 0,
                    Messages.DiffTableViewer_reading_git_history);
            if (setChanged(status.getAdded(), metas, isCancelled)
                    && setChanged(status.getChanged(), metas, isCancelled)
                    && setChanged(status.getModified(), metas, isCancelled)
                    && setChanged(status.getUntracked(), metas, isCancelled)) {
                // These paths have no committed author at the current HEAD.
                for (String path : status.getAdded()) {
                    if (isCancelled.getAsBoolean()) {
                        return;
                    }
                    metas.remove(path);
                }
                for (String path : status.getUntracked()) {
                    if (isCancelled.getAsBoolean()) {
                        return;
                    }
                    metas.remove(path);
                }
            }
        } catch (IOException | NoWorkTreeException e) {
            Log.log(Log.LOG_ERROR, Messages.GitUserReader_error_reading_local_changes, e);
        }
    }

    static TreeFilter requestedPathFilter(
            Map<String, List<ElementMetaInfo>> metas) {
        return PathFilterGroup.createFromStrings(metas.keySet());
    }

    private static boolean setChanged(Iterable<String> paths, Map<String, List<ElementMetaInfo>> metas,
            BooleanSupplier isCancelled) {
        for (String path : paths) {
            if (isCancelled.getAsBoolean()) {
                return false;
            }
            List<ElementMetaInfo> meta = metas.get(path);
            if (meta != null) {
                meta.forEach(ElementMetaInfo::setChanged);
            }
        }
        return true;
    }

    record HistoryStats(
            int requested,
            int cacheHits,
            int historyResolved,
            long cacheReadNanos,
            long historyNanos,
            long cacheWriteNanos,
            boolean memoryHit,
            boolean diskHit,
            boolean snapshotMatched,
            boolean cacheStored,
            boolean cancelled,
            boolean historyComplete) {

        private static HistoryStats empty(int requested,
                boolean cancelled) {
            return new HistoryStats(requested, 0, 0, 0, 0, 0,
                    false, false, false, false, cancelled, false);
        }
    }

    private static final class CancellationProgressMonitor implements ProgressMonitor {

        private final BooleanSupplier isCancelled;

        private CancellationProgressMonitor(BooleanSupplier isCancelled) {
            this.isCancelled = isCancelled;
        }

        @Override
        public void start(int totalTasks) {
            // no progress reporting is needed here
        }

        @Override
        public void beginTask(String title, int totalWork) {
            // no progress reporting is needed here
        }

        @Override
        public void update(int completed) {
            // no progress reporting is needed here
        }

        @Override
        public void endTask() {
            // no progress reporting is needed here
        }

        @Override
        public boolean isCancelled() {
            return isCancelled.getAsBoolean();
        }

        @Override
        public void showDuration(boolean enabled) {
            // no progress reporting is needed here
        }
    }

    @Override
    public void close() {
        if (repo != null) {
            repo.close();
        }
    }
}
