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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ProjectIndexWarmValidatorTest {

    private static final IndexPathRef PATH =
            new IndexPathRef(IndexPathOrigin.PROJECT, "SCHEMA/app/table.sql");

    private static final int DEFAULT_BUDGET =
            ProjectIndexWarmValidator.DEFAULT_MAX_DIVERGENCES;

    @Test
    void unchangedMetadataRequiresNoContentRead() throws Exception {
        ProjectFileStamp expected = stamp(10, 20, 30, "same");
        var reads = new AtomicInteger();

        var result = ProjectIndexWarmValidator.validate(List.of(expected),
                List.of(current(10, 20, 30)), file -> {
                    reads.incrementAndGet();
                    return sha256("same");
                }, () -> false);

        Assertions.assertTrue(result.hit());
        Assertions.assertEquals(0, result.hashedFiles());
        Assertions.assertEquals(0, reads.get());
    }

    @Test
    void uncertainMetadataFallsBackToHash() throws Exception {
        ProjectFileStamp expected = stamp(10, 20, 30, "same");

        var result = ProjectIndexWarmValidator.validate(List.of(expected),
                List.of(current(11, 20, 30)), file -> sha256("same"),
                () -> false);

        Assertions.assertTrue(result.hit());
        Assertions.assertEquals(1, result.hashedFiles());
    }

    @Test
    void unavailableModificationStampIsAlwaysHashed() throws Exception {
        ProjectFileStamp expected = stamp(-1, 20, 30, "same");

        var result = ProjectIndexWarmValidator.validate(List.of(expected),
                List.of(current(-1, 20, 30)), file -> sha256("same"),
                () -> false);

        Assertions.assertTrue(result.hit());
        Assertions.assertEquals(1, result.hashedFiles());
    }

    @Test
    void unsettledModificationTimeIsAlwaysHashed() throws Exception {
        ProjectFileStamp expected = stamp(10, 20, 1_000_000, "same");
        var reads = new AtomicInteger();

        long justWritten = 1_000_000
                + ProjectIndexWarmValidator.MODIFICATION_SETTLE_MILLIS - 1;
        var unsettled = ProjectIndexWarmValidator.validate(List.of(expected),
                List.of(current(10, 20, 1_000_000)), file -> {
                    reads.incrementAndGet();
                    return sha256("same");
                }, () -> false, DEFAULT_BUDGET, justWritten);

        Assertions.assertTrue(unsettled.hit());
        Assertions.assertEquals(1, unsettled.hashedFiles(),
                "a modification time that can still collide is never trusted");

        long settled = 1_000_000
                + ProjectIndexWarmValidator.MODIFICATION_SETTLE_MILLIS;
        var trusted = ProjectIndexWarmValidator.validate(List.of(expected),
                List.of(current(10, 20, 1_000_000)), file -> {
                    reads.incrementAndGet();
                    return sha256("same");
                }, () -> false, DEFAULT_BUDGET, settled);

        Assertions.assertTrue(trusted.hit());
        Assertions.assertEquals(0, trusted.hashedFiles());
        Assertions.assertEquals(1, reads.get());
    }

    @Test
    void sameMillisecondRewriteIsCaughtInsideTheSettleWindow()
            throws Exception {
        ProjectFileStamp expected = stamp(10, 20, 1_000_000, "old");

        var result = ProjectIndexWarmValidator.validate(List.of(expected),
                List.of(current(10, 20, 1_000_000)), file -> sha256("new"),
                () -> false, DEFAULT_BUDGET, 1_000_500);

        Assertions.assertFalse(result.hit(),
                "an equally sized rewrite in the same millisecond must not pass");
        Assertions.assertEquals(1, result.hashedFiles());
    }

    @Test
    void futureModificationTimeIsNeverTrusted() throws Exception {
        ProjectFileStamp expected = stamp(10, 20, 5_000_000, "same");

        var result = ProjectIndexWarmValidator.validate(List.of(expected),
                List.of(current(10, 20, 5_000_000)), file -> sha256("same"),
                () -> false, DEFAULT_BUDGET, 1_000_000);

        Assertions.assertTrue(result.hit());
        Assertions.assertEquals(1, result.hashedFiles(),
                "a skewed clock must not enable metadata-only validation");
    }

    @Test
    void changedContentRejectsWarmIndex() throws Exception {
        ProjectFileStamp expected = stamp(10, 20, 30, "old");

        var result = ProjectIndexWarmValidator.validate(List.of(expected),
                List.of(current(11, 20, 31)), file -> sha256("new"),
                () -> false);

        Assertions.assertFalse(result.hit());
        Assertions.assertEquals(1, result.hashedFiles());
    }

    @Test
    void pathOrSizeDeltaRejectsWithoutHashing() throws Exception {
        ProjectFileStamp expected = stamp(10, 20, 30, "same");
        var reads = new AtomicInteger();

        var missing = ProjectIndexWarmValidator.validate(List.of(expected),
                List.of(), file -> {
                    reads.incrementAndGet();
                    return sha256("same");
                }, () -> false);
        var changedSize = ProjectIndexWarmValidator.validate(List.of(expected),
                List.of(current(10, 21, 30)), file -> {
                    reads.incrementAndGet();
                    return sha256("same");
                }, () -> false);

        Assertions.assertFalse(missing.hit());
        Assertions.assertFalse(changedSize.hit());
        Assertions.assertEquals(0, reads.get());
    }

    @Test
    void acceptedIndexReportsNoDivergence() throws Exception {
        ProjectFileStamp expected = stamp(10, 20, 30, "same");

        var result = ProjectIndexWarmValidator.validate(List.of(expected),
                List.of(current(10, 20, 30)), file -> sha256("same"),
                () -> false);

        Assertions.assertTrue(result.hit());
        Assertions.assertTrue(result.complete());
        Assertions.assertEquals(List.of(), result.changed());
        Assertions.assertEquals(List.of(), result.added());
        Assertions.assertEquals(List.of(), result.removed());
    }

    @Test
    void changedContentIsReportedAsChanged() throws Exception {
        ProjectFileStamp expected = stamp(10, 20, 30, "old");

        var result = ProjectIndexWarmValidator.validate(List.of(expected),
                List.of(current(11, 20, 30)), file -> sha256("new"),
                () -> false);

        Assertions.assertFalse(result.hit());
        Assertions.assertTrue(result.complete());
        Assertions.assertEquals(1, result.hashedFiles());
        Assertions.assertEquals(List.of(PATH), result.changed());
        Assertions.assertEquals(List.of(), result.added());
        Assertions.assertEquals(List.of(), result.removed());
    }

    @Test
    void changedSizeIsReportedAsChangedWithoutHashing() throws Exception {
        ProjectFileStamp expected = stamp(10, 20, 30, "same");
        var reads = new AtomicInteger();

        var result = ProjectIndexWarmValidator.validate(List.of(expected),
                List.of(current(10, 21, 30)), file -> {
                    reads.incrementAndGet();
                    return sha256("same");
                }, () -> false);

        Assertions.assertFalse(result.hit());
        Assertions.assertTrue(result.complete());
        Assertions.assertEquals(List.of(PATH), result.changed());
        Assertions.assertEquals(0, reads.get(),
                "a size difference already proves the divergence");
    }

    @Test
    void aFileAddedToTheWorkingTreeIsReportedAsAdded() throws Exception {
        IndexPathRef known = path("SCHEMA/app/known.sql");
        IndexPathRef extra = path("SCHEMA/app/extra.sql");

        var result = ProjectIndexWarmValidator.validate(
                List.of(stamp(known, 10, 20, 30, "same")),
                List.of(current(known, 10, 20, 30),
                        current(extra, 10, 20, 30)),
                file -> sha256("same"), () -> false);

        Assertions.assertFalse(result.hit());
        Assertions.assertTrue(result.complete(),
                "a differing file count is a classified difference, "
                        + "not an unreadable one");
        Assertions.assertEquals(List.of(extra), result.added());
        Assertions.assertEquals(List.of(), result.changed());
        Assertions.assertEquals(List.of(), result.removed());
    }

    @Test
    void aFileDeletedFromTheWorkingTreeIsReportedAsRemoved()
            throws Exception {
        IndexPathRef known = path("SCHEMA/app/known.sql");
        IndexPathRef gone = path("SCHEMA/app/gone.sql");

        var result = ProjectIndexWarmValidator.validate(
                List.of(stamp(known, 10, 20, 30, "same"),
                        stamp(gone, 10, 20, 30, "same")),
                List.of(current(known, 10, 20, 30)),
                file -> sha256("same"), () -> false);

        Assertions.assertFalse(result.hit());
        Assertions.assertTrue(result.complete());
        Assertions.assertEquals(List.of(gone), result.removed());
        Assertions.assertEquals(List.of(), result.changed());
        Assertions.assertEquals(List.of(), result.added());
    }

    @Test
    void divergenceListsAreOrderedByPathAndImmutable() throws Exception {
        IndexPathRef firstChanged = path("SCHEMA/app/a.sql");
        IndexPathRef secondChanged = path("SCHEMA/app/c.sql");
        IndexPathRef firstRemoved = path("SCHEMA/app/r1.sql");
        IndexPathRef secondRemoved = path("SCHEMA/app/r2.sql");
        IndexPathRef firstAdded = path("SCHEMA/app/y.sql");
        IndexPathRef secondAdded = path("SCHEMA/app/z.sql");

        var result = ProjectIndexWarmValidator.validate(
                List.of(stamp(secondChanged, 10, 20, 30, "same"),
                        stamp(secondRemoved, 10, 20, 30, "same"),
                        stamp(firstChanged, 10, 20, 30, "same"),
                        stamp(firstRemoved, 10, 20, 30, "same")),
                List.of(current(secondAdded, 10, 20, 30),
                        current(secondChanged, 10, 21, 30),
                        current(firstAdded, 10, 20, 30),
                        current(firstChanged, 10, 21, 30)),
                file -> sha256("same"), () -> false);

        Assertions.assertFalse(result.hit());
        Assertions.assertTrue(result.complete());
        Assertions.assertEquals(List.of(firstChanged, secondChanged),
                result.changed(), "enumeration order must not leak out");
        Assertions.assertEquals(List.of(firstRemoved, secondRemoved),
                result.removed());
        Assertions.assertEquals(List.of(firstAdded, secondAdded),
                result.added());
        Assertions.assertThrows(UnsupportedOperationException.class,
                () -> result.changed().add(firstAdded));
        Assertions.assertThrows(UnsupportedOperationException.class,
                () -> result.added().clear());
        Assertions.assertThrows(UnsupportedOperationException.class,
                () -> result.removed().clear());
    }

    @Test
    void divergenceBudgetStopsTheEnumerationAndTheHashing()
            throws Exception {
        IndexPathRef first = path("SCHEMA/app/a.sql");
        IndexPathRef second = path("SCHEMA/app/b.sql");
        IndexPathRef third = path("SCHEMA/app/c.sql");
        List<ProjectFileStamp> expected = List.of(
                stamp(first, 10, 20, 30, "old"),
                stamp(second, 10, 20, 30, "old"),
                stamp(third, 10, 20, 30, "old"));
        List<ProjectIndexWarmValidator.CurrentFile> tree = List.of(
                current(first, 11, 20, 30), current(second, 11, 20, 30),
                current(third, 11, 20, 30));
        var reads = new AtomicInteger();

        var result = ProjectIndexWarmValidator.validate(expected, tree,
                file -> {
                    reads.incrementAndGet();
                    return sha256("new");
                }, () -> false, 1);

        Assertions.assertFalse(result.hit());
        Assertions.assertFalse(result.complete(),
                "a difference wider than the budget is not described");
        Assertions.assertEquals(List.of(first), result.changed());
        Assertions.assertEquals(2, reads.get(),
                "the third file is never read once the budget is spent");
        Assertions.assertEquals(2, result.hashedFiles());
    }

    @Test
    void spentBudgetLeavesTheAddedFilesUnenumerated() throws Exception {
        IndexPathRef changed = path("SCHEMA/app/a.sql");
        IndexPathRef added = path("SCHEMA/app/z.sql");

        var result = ProjectIndexWarmValidator.validate(
                List.of(stamp(changed, 10, 20, 30, "same")),
                List.of(current(changed, 10, 21, 30),
                        current(added, 10, 20, 30)),
                file -> sha256("same"), () -> false, 1);

        Assertions.assertFalse(result.hit());
        Assertions.assertFalse(result.complete());
        Assertions.assertEquals(List.of(changed), result.changed());
        Assertions.assertEquals(List.of(), result.added());
    }

    @Test
    void zeroBudgetReproducesTheFirstDivergenceExit() throws Exception {
        IndexPathRef first = path("SCHEMA/app/a.sql");
        IndexPathRef second = path("SCHEMA/app/b.sql");
        var reads = new AtomicInteger();

        var result = ProjectIndexWarmValidator.validate(
                List.of(stamp(first, 10, 20, 30, "old"),
                        stamp(second, 10, 20, 30, "old")),
                List.of(current(first, 11, 20, 30),
                        current(second, 11, 20, 30)),
                file -> {
                    reads.incrementAndGet();
                    return sha256("new");
                }, () -> false, 0);

        Assertions.assertFalse(result.hit());
        Assertions.assertFalse(result.complete());
        Assertions.assertEquals(List.of(), result.changed());
        Assertions.assertEquals(1, reads.get(),
                "a miss stays as cheap as it was before the lists existed");
    }

    @Test
    void aNegativeBudgetIsRejected() {
        ProjectFileStamp expected = stamp(10, 20, 30, "same");

        Assertions.assertThrows(IllegalArgumentException.class,
                () -> ProjectIndexWarmValidator.validate(List.of(expected),
                        List.of(current(10, 20, 30)), file -> sha256("same"),
                        () -> false, -1));
    }

    @Test
    void aDuplicatedPathIsRefusedImmediately() throws Exception {
        ProjectFileStamp expected = stamp(10, 20, 30, "same");
        var reads = new AtomicInteger();

        var result = ProjectIndexWarmValidator.validate(List.of(expected),
                List.of(current(10, 20, 30), current(10, 20, 30)), file -> {
                    reads.incrementAndGet();
                    return sha256("same");
                }, () -> false);

        Assertions.assertFalse(result.hit());
        Assertions.assertFalse(result.complete(),
                "a tree that names a path twice describes nothing");
        Assertions.assertEquals(List.of(), result.changed());
        Assertions.assertEquals(List.of(), result.added());
        Assertions.assertEquals(List.of(), result.removed());
        Assertions.assertEquals(0, result.hashedFiles());
        Assertions.assertEquals(0, reads.get());
    }

    @Test
    void cancellationStopsBeforeHashing() {
        ProjectFileStamp expected = stamp(10, 20, 30, "same");
        var cancelled = new AtomicBoolean(false);

        Assertions.assertThrows(InterruptedException.class,
                () -> ProjectIndexWarmValidator.validate(List.of(expected),
                        List.of(current(11, 20, 30)), file -> {
                            cancelled.set(true);
                            return sha256("same");
                        }, cancelled::get));
    }

    private static IndexPathRef path(String relativePath) {
        return new IndexPathRef(IndexPathOrigin.PROJECT, relativePath);
    }

    private static ProjectFileStamp stamp(long modificationStamp, long size,
            long modified, String content) {
        return stamp(PATH, modificationStamp, size, modified, content);
    }

    private static ProjectFileStamp stamp(IndexPathRef path,
            long modificationStamp, long size, long modified, String content) {
        return new ProjectFileStamp(path, modificationStamp, size, modified,
                sha256(content));
    }

    private static ProjectIndexWarmValidator.CurrentFile current(
            long modificationStamp, long size, long modified) {
        return current(PATH, modificationStamp, size, modified);
    }

    private static ProjectIndexWarmValidator.CurrentFile current(
            IndexPathRef path, long modificationStamp, long size,
            long modified) {
        return new ProjectIndexWarmValidator.CurrentFile(path,
                modificationStamp, size, modified);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new AssertionError(ex);
        }
    }
}
