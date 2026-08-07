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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitUserReaderCancellationTest {

    @TempDir
    Path repository;

    @Test
    void cancelledReaderDoesNotModifyMetadata() throws Exception {
        Path sql = repository.resolve("object.sql");
        Files.writeString(sql, "select 1;");
        try (Git git = Git.init().setDirectory(repository.toFile()).call()) {
            git.add().addFilepattern("object.sql").call();
            git.commit().setMessage("initial").setAuthor("Test Author", "author@example.com").call();
        }
        Files.writeString(sql, "select 2;");

        ElementMetaInfo meta = new ElementMetaInfo();
        Map<String, List<ElementMetaInfo>> metas = new HashMap<>();
        metas.put("object.sql", new ArrayList<>(List.of(meta)));
        try (GitUserReader reader = new GitUserReader(repository)) {
            reader.parseLocalChanges(metas, () -> true);
            reader.parseLastChange(metas, () -> true);
        }

        assertFalse(meta.isChanged());
        assertEquals("", meta.getGitUser());
        assertTrue(metas.containsKey("object.sql"));
    }

    @Test
    void bareRepositorySkipsWorkTreeComparison() throws Exception {
        Path gitDir = repository.resolve(".git");
        try (Git ignored = Git.init().setBare(true).setDirectory(gitDir.toFile()).call();
                GitUserReader reader = new GitUserReader(repository)) {
            assertDoesNotThrow(() -> reader.parseLocalChanges(new HashMap<>()));
        }
    }
}
