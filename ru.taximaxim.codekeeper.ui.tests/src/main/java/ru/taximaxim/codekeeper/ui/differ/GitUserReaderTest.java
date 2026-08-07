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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.IndexDiff;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitUserReaderTest {

    @TempDir
    Path repository;

    @Test
    void requestedPathFilterBoundsLocalStatusWalk() throws Exception {
        Path requested = repository.resolve("requested.sql");
        Path unrelated = repository.resolve("unrelated.sql");
        Files.writeString(requested, "select 1;");
        Files.writeString(unrelated, "select 1;");

        try (Git git = Git.init().setDirectory(repository.toFile()).call()) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage("initial")
                    .setAuthor("Initial Author", "initial@example.com").call();

            Files.writeString(requested, "select 2;");
            Files.writeString(unrelated, "select 2;");

            Map<String, List<ElementMetaInfo>> metas = metadata("requested.sql");
            IndexDiff status = new IndexDiff(git.getRepository(),
                    git.getRepository().resolve(Constants.HEAD),
                    new FileTreeIterator(git.getRepository()));
            status.setFilter(GitUserReader.requestedPathFilter(metas));
            status.diff();

            assertEquals(Set.of("requested.sql"), status.getModified());
            assertFalse(status.getModified().contains("unrelated.sql"));
        }
    }

    @Test
    void filteredHistoryKeepsLatestRequestedAuthor() throws Exception {
        Path requested = repository.resolve("requested.sql");
        Path unrelated = repository.resolve("unrelated.sql");
        Files.writeString(requested, "select 1;");
        Files.writeString(unrelated, "select 1;");

        try (Git git = Git.init().setDirectory(repository.toFile()).call()) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage("initial")
                    .setAuthor("Requested Author", "requested@example.com").call();

            Files.writeString(unrelated, "select 2;");
            git.add().addFilepattern("unrelated.sql").call();
            git.commit().setMessage("unrelated")
                    .setAuthor("Unrelated Author", "unrelated@example.com").call();
        }

        Map<String, List<ElementMetaInfo>> metas = metadata("requested.sql");
        ElementMetaInfo meta = metas.get("requested.sql").get(0);
        try (GitUserReader reader = new GitUserReader(repository)) {
            reader.parseLastChange(metas);
        }

        assertEquals("Requested Author", meta.getGitUser());
        assertFalse(metas.containsKey("requested.sql"));
    }

    @Test
    void coldAndWarmLookupKeepLocalMarkersIdentical() throws Exception {
        Files.writeString(repository.resolve("clean.sql"), "select 1;");
        Files.writeString(repository.resolve("modified.sql"), "select 1;");
        Files.writeString(repository.resolve("staged-modified.sql"), "select 1;");
        try (Git git = Git.init().setDirectory(repository.toFile()).call()) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage("initial")
                    .setAuthor("Committed Author", "author@example.com").call();

            Files.writeString(repository.resolve("modified.sql"), "select 2;");
            Files.writeString(repository.resolve("staged-modified.sql"), "select 2;");
            git.add().addFilepattern("staged-modified.sql").call();
            Files.writeString(repository.resolve("staged-added.sql"), "select 1;");
            git.add().addFilepattern("staged-added.sql").call();
            Files.writeString(repository.resolve("untracked.sql"), "select 1;");
        }

        Path cacheRoot = repository.resolveSibling("git-author-cache");
        LookupResult cold = lookup(cacheRoot);
        LookupResult warm = lookup(cacheRoot);

        assertEquals(cold.markers(), warm.markers());
        assertEquals(Map.of(
                "clean.sql", "Committed Author",
                "modified.sql", "*Committed Author",
                "staged-modified.sql", "*Committed Author",
                "staged-added.sql", "*",
                "untracked.sql", "*"), warm.markers());
        assertEquals(0, cold.stats().cacheHits());
        assertEquals(3, warm.stats().cacheHits());
        assertEquals(0, warm.stats().historyResolved());
    }

    @Test
    void cancelledHistoryDoesNotCreateNegativeCacheEntry() throws Exception {
        Files.writeString(repository.resolve("object.sql"), "select 1;");
        try (Git git = Git.init().setDirectory(repository.toFile()).call()) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage("initial")
                    .setAuthor("Committed Author", "author@example.com").call();
        }

        Path cacheRoot = repository.resolveSibling("git-author-cache");
        Map<String, List<ElementMetaInfo>> cancelled = metadata("object.sql");
        try (GitUserReader reader = new GitUserReader(repository)) {
            reader.parseLastChange(cancelled, new GitAuthorCache(cacheRoot),
                    () -> true);
        }

        Map<String, List<ElementMetaInfo>> retry = metadata("object.sql");
        ElementMetaInfo retryMeta = retry.get("object.sql").get(0);
        GitUserReader.HistoryStats stats;
        try (GitUserReader reader = new GitUserReader(repository)) {
            stats = reader.parseLastChange(retry,
                    new GitAuthorCache(cacheRoot), () -> false);
        }
        assertEquals(0, stats.cacheHits());
        assertEquals(1, stats.historyResolved());
        assertEquals("Committed Author", retryMeta.getGitUser());
    }

    @Test
    void headChangeAndBranchSwitchNeverReuseStaleAuthor() throws Exception {
        Files.writeString(repository.resolve("object.sql"), "select 1;");
        Path cacheRoot = repository.resolveSibling("git-author-cache");
        String firstHead;
        try (Git git = Git.init().setDirectory(repository.toFile()).call()) {
            git.add().addFilepattern(".").call();
            firstHead = git.commit().setMessage("first")
                    .setAuthor("First Author", "first@example.com")
                    .call().name();
            assertEquals("First Author",
                    lookupAuthor(cacheRoot).meta().getGitUser());

            Files.writeString(repository.resolve("object.sql"), "select 2;");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("second")
                    .setAuthor("Second Author", "second@example.com").call();
            AuthorLookup second = lookupAuthor(cacheRoot);
            assertEquals("Second Author", second.meta().getGitUser());
            assertEquals(0, second.stats().cacheHits());

            git.checkout().setName(firstHead).call();
            AuthorLookup switched = lookupAuthor(cacheRoot);
            assertEquals("First Author", switched.meta().getGitUser());
            assertEquals(0, switched.stats().cacheHits());
        }
    }

    @Test
    void corruptPersistentSnapshotFallsBackToHistoryAndIsReplaced()
            throws Exception {
        Files.writeString(repository.resolve("object.sql"), "select 1;");
        try (Git git = Git.init().setDirectory(repository.toFile()).call()) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage("initial")
                    .setAuthor("Committed Author", "author@example.com").call();
        }
        Path cacheRoot = repository.resolveSibling("git-author-cache");
        lookupAuthor(cacheRoot);

        try (GitUserReader reader = new GitUserReader(repository)) {
            GitAuthorCache cache = new GitAuthorCache(cacheRoot);
            Files.write(cache.cacheFile(reader.getGitDirectory()),
                    new byte[] { 1, 2, 3 });
        }

        AuthorLookup recovered = lookupAuthor(cacheRoot);
        assertEquals("Committed Author", recovered.meta().getGitUser());
        assertEquals(0, recovered.stats().cacheHits());
        assertEquals(1, recovered.stats().historyResolved());
        assertEquals("Committed Author",
                lookupAuthor(cacheRoot).meta().getGitUser());
    }

    private LookupResult lookup(Path cacheRoot) throws Exception {
        List<String> paths = List.of("clean.sql", "modified.sql",
                "staged-modified.sql", "staged-added.sql", "untracked.sql");
        Map<String, List<ElementMetaInfo>> metas = new LinkedHashMap<>();
        Map<String, ElementMetaInfo> all = new LinkedHashMap<>();
        paths.forEach(path -> {
            ElementMetaInfo meta = new ElementMetaInfo();
            metas.put(path, new ArrayList<>(List.of(meta)));
            all.put(path, meta);
        });

        GitUserReader.HistoryStats stats;
        try (GitUserReader reader = new GitUserReader(repository)) {
            reader.parseLocalChanges(metas);
            stats = reader.parseLastChange(metas,
                    new GitAuthorCache(cacheRoot), () -> false);
        }
        Map<String, String> markers = new LinkedHashMap<>();
        all.forEach((path, meta) -> markers.put(path, meta.getGitUser()));
        assertTrue(metas.isEmpty());
        return new LookupResult(markers, stats);
    }

    private AuthorLookup lookupAuthor(Path cacheRoot) throws Exception {
        Map<String, List<ElementMetaInfo>> metas = metadata("object.sql");
        ElementMetaInfo meta = metas.get("object.sql").get(0);
        GitUserReader.HistoryStats stats;
        try (GitUserReader reader = new GitUserReader(repository)) {
            reader.parseLocalChanges(metas);
            stats = reader.parseLastChange(metas,
                    new GitAuthorCache(cacheRoot), () -> false);
        }
        return new AuthorLookup(meta, stats);
    }

    private static Map<String, List<ElementMetaInfo>> metadata(String path) {
        Map<String, List<ElementMetaInfo>> metas = new HashMap<>();
        metas.put(path, new ArrayList<>(List.of(new ElementMetaInfo())));
        return metas;
    }

    private record LookupResult(
            Map<String, String> markers,
            GitUserReader.HistoryStats stats) {
    }

    private record AuthorLookup(
            ElementMetaInfo meta,
            GitUserReader.HistoryStats stats) {
    }
}
