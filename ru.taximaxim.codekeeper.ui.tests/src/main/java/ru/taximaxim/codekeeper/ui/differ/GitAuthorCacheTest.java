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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitAuthorCacheTest {

    private static final String HEAD_A = "a".repeat(40);
    private static final String HEAD_B = "b".repeat(40);

    @TempDir
    Path temporary;

    @Test
    void sameHeadSurvivesCacheInstanceRestart() throws Exception {
        Path cacheRoot = temporary.resolve("cache");
        Path gitDirectory = canonicalGitDirectory("repository");
        GitAuthorCache cache = new GitAuthorCache(cacheRoot);

        assertTrue(cache.store(gitDirectory, HEAD_A,
                Map.of("SCHEMA/app/FUNCTION/f.sql", "Alice")));

        GitAuthorCache restarted = new GitAuthorCache(cacheRoot);
        assertEquals(Map.of("SCHEMA/app/FUNCTION/f.sql", "Alice"),
                restarted.lookup(gitDirectory, HEAD_A,
                        Set.of("SCHEMA/app/FUNCTION/f.sql")).authors());
        assertTrue(Pattern.matches("[0-9a-f]{64}\\.bin",
                restarted.cacheFile(gitDirectory).getFileName().toString()));
    }

    @Test
    void newHeadReplacesOldSnapshotInsteadOfServingStaleAuthors()
            throws Exception {
        Path cacheRoot = temporary.resolve("cache");
        Path gitDirectory = canonicalGitDirectory("repository");
        GitAuthorCache cache = new GitAuthorCache(cacheRoot);
        cache.store(gitDirectory, HEAD_A, Map.of("object.sql", "Alice"));

        assertTrue(cache.lookup(gitDirectory, HEAD_B,
                Set.of("object.sql")).authors().isEmpty());
        cache.store(gitDirectory, HEAD_B, Map.of("object.sql", "Bob"));

        GitAuthorCache restarted = new GitAuthorCache(cacheRoot);
        assertEquals(Map.of("object.sql", "Bob"),
                restarted.lookup(gitDirectory, HEAD_B,
                        Set.of("object.sql")).authors());
        assertTrue(restarted.lookup(gitDirectory, HEAD_A,
                Set.of("object.sql")).authors().isEmpty());
    }

    @Test
    void corruptTruncatedOversizedAndUnsupportedFilesAreReplaceable()
            throws Exception {
        Path cacheRoot = temporary.resolve("cache");
        Path gitDirectory = canonicalGitDirectory("repository");
        GitAuthorCache initial = new GitAuthorCache(cacheRoot);
        initial.store(gitDirectory, HEAD_A, Map.of("object.sql", "Alice"));
        Path cacheFile = initial.cacheFile(gitDirectory);
        byte[] valid = Files.readAllBytes(cacheFile);

        byte[][] invalidPayloads = {
                { 1, 2, 3, 4 },
                java.util.Arrays.copyOf(valid, valid.length - 1),
                unsupportedVersion(valid)
        };
        for (byte[] invalid : invalidPayloads) {
            Files.write(cacheFile, invalid);
            assertReplaceable(cacheRoot, gitDirectory);
        }

        try (FileChannel channel = FileChannel.open(cacheFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.position(GitAuthorCache.MAX_FILE_BYTES);
            channel.write(ByteBuffer.wrap(new byte[] { 1 }));
        }
        assertReplaceable(cacheRoot, gitDirectory);
    }

    @Test
    void failedIoDoesNotPublishNegativeEntries() throws Exception {
        Path cacheRoot = temporary.resolve("not-a-directory");
        Files.writeString(cacheRoot, "occupied");
        Path gitDirectory = canonicalGitDirectory("repository");
        GitAuthorCache cache = new GitAuthorCache(cacheRoot);

        assertFalse(cache.store(gitDirectory, HEAD_A,
                Map.of("object.sql", "Alice")));
        assertTrue(cache.lookup(gitDirectory, HEAD_A,
                Set.of("object.sql")).authors().isEmpty());

        Files.delete(cacheRoot);
        assertTrue(cache.store(gitDirectory, HEAD_A,
                Map.of("object.sql", "Alice")));
        assertEquals("Alice", new GitAuthorCache(cacheRoot)
                .lookup(gitDirectory, HEAD_A, Set.of("object.sql"))
                .authors().get("object.sql"));
    }

    @Test
    void entryByteAndRepositoryBoundsAreEnforced() throws Exception {
        Path cacheRoot = temporary.resolve("cache");
        GitAuthorCache cache = new GitAuthorCache(cacheRoot, 3, 512, 2);
        Path first = canonicalGitDirectory("one");
        Path second = canonicalGitDirectory("two");
        Path third = canonicalGitDirectory("three");
        Map<String, String> authors = new LinkedHashMap<>();
        IntStream.range(0, 10).forEach(i ->
                authors.put("path-" + i, "author-" + i));

        cache.store(first, HEAD_A, authors);
        assertTrue(Files.size(cache.cacheFile(first)) <= 512);
        assertTrue(new GitAuthorCache(cacheRoot, 3, 512, 2)
                .lookup(first, HEAD_A, authors.keySet()).authors().size() <= 3);

        cache.store(second, HEAD_A, Map.of("two.sql", "Two"));
        cache.store(third, HEAD_A, Map.of("three.sql", "Three"));
        assertEquals(2, cache.inMemoryRepositoryCount());
    }

    @Test
    void oversizedSingleEntryDoesNotDiscardOtherCacheableAuthors()
            throws Exception {
        Path cacheRoot = temporary.resolve("cache");
        Path gitDirectory = canonicalGitDirectory("repository");
        Map<String, String> authors = new LinkedHashMap<>();
        authors.put("x".repeat((1 << 20) + 1), "Too large");
        authors.put("object.sql", "Alice");

        GitAuthorCache cache = new GitAuthorCache(cacheRoot);
        assertTrue(cache.store(gitDirectory, HEAD_A, authors));
        assertEquals(Map.of("object.sql", "Alice"),
                new GitAuthorCache(cacheRoot).lookup(gitDirectory, HEAD_A,
                        authors.keySet()).authors());
    }

    @Test
    void concurrentReadersSeeOnlyCompleteSnapshots() throws Exception {
        Path cacheRoot = temporary.resolve("cache");
        Path gitDirectory = canonicalGitDirectory("repository");
        GitAuthorCache writer = new GitAuthorCache(cacheRoot);
        writer.store(gitDirectory, HEAD_A, Map.of("object.sql", "author-0"));

        try (var executor = Executors.newFixedThreadPool(5)) {
            Callable<Void> write = () -> {
                for (int i = 1; i <= 100; i++) {
                    assertTrue(writer.store(gitDirectory, HEAD_A,
                            Map.of("object.sql", "author-" + i)));
                }
                return null;
            };
            Callable<Void> read = () -> {
                for (int i = 0; i < 100; i++) {
                    String author = new GitAuthorCache(cacheRoot)
                            .lookup(gitDirectory, HEAD_A,
                                    Set.of("object.sql"))
                            .authors().get("object.sql");
                    assertTrue(author != null
                            && author.matches("author-[0-9]+"));
                }
                return null;
            };

            var futures = executor.invokeAll(
                    java.util.List.of(write, read, read, read, read));
            for (var future : futures) {
                future.get();
            }
        }
    }

    private void assertReplaceable(Path cacheRoot, Path gitDirectory) {
        GitAuthorCache cache = new GitAuthorCache(cacheRoot);
        assertTrue(cache.lookup(gitDirectory, HEAD_A,
                Set.of("object.sql")).authors().isEmpty());
        assertTrue(cache.store(gitDirectory, HEAD_A,
                Map.of("object.sql", "Replacement")));
        assertEquals("Replacement", new GitAuthorCache(cacheRoot)
                .lookup(gitDirectory, HEAD_A, Set.of("object.sql"))
                .authors().get("object.sql"));
    }

    private Path canonicalGitDirectory(String name) throws IOException {
        return Files.createDirectories(temporary.resolve(name).resolve(".git"))
                .toRealPath();
    }

    private static byte[] unsupportedVersion(byte[] valid) {
        byte[] unsupported = valid.clone();
        ByteBuffer.wrap(unsupported).putInt(Integer.BYTES, Integer.MAX_VALUE);
        return unsupported;
    }
}
