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
package ru.taximaxim.codekeeper.ui.projectindex;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What protects an index written before a file could be added to one
 * incrementally.
 *
 * <p>Not the format version. FORMAT_MINOR is deliberately left alone, so such
 * an index is read rather than refused, and what stops it hosting an addition is
 * its own content: every file claims it may hold an unresolved reference,
 * because that is what the flag says whenever it was not measured per file.
 *
 * <p>There is deliberately no test here for a container carrying some other
 * minor, and writing one is harder than it looks. Patching what looks like the
 * minor in the header of the published payload and reading the container's
 * refusal proves nothing: the payload begins with a container header whose
 * magic already carries the major, the codec header sits further in at the
 * container's codec offset, and no two-byte window of the outer header even
 * equals the major. Such a test corrupts an unrelated field, reads an unrelated
 * integrity refusal, and stays green with the reader's version check removed.
 *
 * <p>The guarantee needs no test of its own anyway: the minor is compared for
 * equality in six places — header, header against manifest section, locator,
 * writer, and twice in the record layer.
 */
class LegacyIndexSafetyTest {

    /**
     * An index whose content follows the old rule — every file claiming it may
     * hold an unresolved reference — refuses an addition instead of quietly
     * taking it.
     */
    @Test
    void contentFromTheOldRuleStillRefusesAnAddition(@TempDir Path directory)
            throws Exception {
        ProjectIndexData source = asWrittenBeforeTheRound(
                ProjectIndexFixtures.snapshotWithAllMetaKinds());
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(source.manifest());
        assertTrue(source.files().stream()
                .allMatch(FileContribution::unresolvedAny),
                "this stands in for a pre-round index, so every file has to"
                        + " carry the flag the old code hardcoded");

        try (var store = new ProjectIndexStore(directory)) {
            store.publish(source, () -> false);
        }
        try (var store = new ProjectIndexStore(directory);
                var opened = store.open(identity)) {
            ProjectIndexView view = opened.view().orElseThrow();
            assertTrue(view.anyFileMayHoldUnresolvedReferences(),
                    "the old content has to be visible as such");
            assertFalse(ProjectIndexIncrementalPlanner.permitsAddedFiles(
                    view, List.of(newFile())),
                    "an index built under the old rule cannot host an addition");
        }
    }

    /**
     * The same index as the old code would have written it: the flag was a
     * constant then, so every file carried it.
     */
    private static ProjectIndexData asWrittenBeforeTheRound(
            ProjectIndexData source) {
        return new ProjectIndexData(source.manifest(),
                source.files().stream()
                        .map(file -> new FileContribution(file.path(),
                                file.definitions(), file.locations(),
                                file.unresolvedCandidates(), true))
                        .toList());
    }

    private static FileContribution newFile() {
        IndexPathRef path = ProjectIndexFixtures.path("SCHEMA/new/table.sql");
        return new FileContribution(path, List.of(), List.of(), Set.of(), false);
    }
}
