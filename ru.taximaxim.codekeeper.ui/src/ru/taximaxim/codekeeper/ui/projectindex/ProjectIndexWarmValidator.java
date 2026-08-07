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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

public final class ProjectIndexWarmValidator {

    /**
     * Time a recorded modification stamp needs to settle before metadata alone
     * may stand in for a content comparison.
     *
     * <p>Filesystem modification times have millisecond granularity, so a
     * rewrite landing in the same millisecond as the indexed write keeps both
     * the size and the modification time and would otherwise pass validation
     * unnoticed. A file whose modification time is younger than this window,
     * or dated into the future by a skewed clock, is therefore always compared
     * by content. Such a collision can only be produced within one millisecond
     * of the write a full build recorded, and a full build revalidates its
     * stamps against the working tree before and after publication, so a
     * colliding rewrite is caught while the modification time is still inside
     * this window. A modification time that is preserved deliberately by a
     * copy or a restore is a different case and stays outside what metadata
     * validation can detect.</p>
     */
    public static final long MODIFICATION_SETTLE_MILLIS = 2_000L;

    /**
     * Number of divergent paths a validation run describes before it stops
     * enumerating and answers with a bare miss.
     *
     * <p>A miss must stay cheap. Before the divergence lists existed the run
     * left on the first difference, so a rejected index never paid for the
     * digests of the files behind it. The budget preserves that: a wholesale
     * change - a branch switch, a bulk regeneration, a first build against a
     * foreign working tree - reaches the limit within its first few dozen files
     * and stops exactly like the old early exit did.</p>
     *
     * <p>Sixty-four is the point where knowing the difference stops paying for
     * itself. A hand edit, a merge landing or a code generator run touches
     * single digits of files, and describing those is what lets a later caller
     * repair an index instead of rebuilding it; a change wider than sixty-four
     * files is a rebuild either way, so enumerating it further buys nothing.
     * The digests a miss adds are bounded by the files whose metadata already
     * differs from the indexed stamp - a matching stamp is never hashed - and
     * every miss is followed by a full parse that costs orders of magnitude
     * more than those reads.</p>
     */
    public static final int DEFAULT_MAX_DIVERGENCES = 64;

    /**
     * Total order over paths, so that a divergence list depends on the working
     * tree alone and never on enumeration or hash-map order.
     */
    private static final Comparator<IndexPathRef> PATH_ORDER =
            Comparator.<IndexPathRef, String>comparing(
                    IndexPathRef::relativePath)
                    .thenComparing(IndexPathRef::origin);

    @FunctionalInterface
    public interface DigestReader {
        byte[] sha256(CurrentFile file) throws IOException, InterruptedException;
    }

    public record CurrentFile(IndexPathRef path, long eclipseModificationStamp,
            long size, long lastModifiedMillis) {

        public CurrentFile {
            Objects.requireNonNull(path, "path");
            if (size < 0) {
                throw new IllegalArgumentException("File size must not be negative");
            }
        }
    }

    /**
     * Outcome of one validation run.
     *
     * <p>The three divergence lists are immutable and ordered by path, so two
     * runs over the same working tree describe it identically. They are only a
     * complete account of the difference when {@link #complete()} says so: a
     * run that gave up at the divergence limit, or one that refused a broken
     * enumeration, leaves lists that witness a miss and must not be read as a
     * work list.</p>
     *
     * @param hit         whether the index still describes the working tree;
     *                    false as soon as anything diverges or the enumeration
     *                    stopped early
     * @param hashedFiles number of files whose content was digested
     * @param changed     paths on both sides whose size or content differs
     * @param added       paths in the working tree that the index does not know
     * @param removed     paths the index knows that the working tree no longer
     *                    has
     * @param complete    whether the lists account for every difference
     */
    public record Result(boolean hit, int hashedFiles,
            List<IndexPathRef> changed, List<IndexPathRef> added,
            List<IndexPathRef> removed, boolean complete) {

        public Result {
            if (hashedFiles < 0) {
                throw new IllegalArgumentException("Hashed file count must not be negative");
            }
            changed = ordered(changed, "changed");
            added = ordered(added, "added");
            removed = ordered(removed, "removed");
        }

        private static List<IndexPathRef> ordered(List<IndexPathRef> paths,
                String name) {
            Objects.requireNonNull(paths, name);
            List<IndexPathRef> sorted = new ArrayList<>(paths);
            sorted.sort(PATH_ORDER);
            return List.copyOf(sorted);
        }
    }

    private ProjectIndexWarmValidator() {
    }

    /**
     * Compares an indexed set of file stamps against the working tree with the
     * default divergence budget.
     *
     * @param expected     stamps the index was built from, one per path
     * @param current      files the working tree holds now
     * @param digestReader reads the content digest of a file
     * @param cancelled    cancellation probe of this run
     * @return validation outcome
     * @throws IOException          if a file could not be read
     * @throws InterruptedException if the run was cancelled
     */
    public static Result validate(List<ProjectFileStamp> expected,
            List<CurrentFile> current, DigestReader digestReader,
            BooleanSupplier cancelled) throws IOException, InterruptedException {
        return validate(expected, current, digestReader, cancelled,
                DEFAULT_MAX_DIVERGENCES);
    }

    /**
     * Compares an indexed set of file stamps against the working tree.
     *
     * <p>Both sides are expected to name each path once. A path enumerated
     * twice by the working tree is a broken input and is refused outright,
     * because nothing can be told about a tree that describes itself
     * inconsistently.</p>
     *
     * @param expected        stamps the index was built from, one per path
     * @param current         files the working tree holds now
     * @param digestReader    reads the content digest of a file
     * @param cancelled       cancellation probe of this run
     * @param maxDivergences  how many divergent paths to describe before
     *                        giving up on describing the difference; zero
     *                        reproduces the bare first-divergence exit
     * @return validation outcome
     * @throws IOException              if a file could not be read
     * @throws InterruptedException     if the run was cancelled
     * @throws IllegalArgumentException if the budget is negative
     */
    public static Result validate(List<ProjectFileStamp> expected,
            List<CurrentFile> current, DigestReader digestReader,
            BooleanSupplier cancelled, int maxDivergences)
            throws IOException, InterruptedException {
        return validate(expected, current, digestReader, cancelled,
                maxDivergences, System.currentTimeMillis());
    }

    static Result validate(List<ProjectFileStamp> expected,
            List<CurrentFile> current, DigestReader digestReader,
            BooleanSupplier cancelled, int maxDivergences, long nowMillis)
            throws IOException, InterruptedException {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(digestReader, "digestReader");
        Objects.requireNonNull(cancelled, "cancelled");
        if (maxDivergences < 0) {
            throw new IllegalArgumentException("Divergence budget must not be negative");
        }
        checkCancelled(cancelled);

        Optional<Map<IndexPathRef, CurrentFile>> byPath =
                indexByPath(current, cancelled);
        if (byPath.isEmpty()) {
            return brokenInput();
        }

        Map<IndexPathRef, CurrentFile> unmatched = byPath.orElseThrow();
        var divergences = new Divergences(maxDivergences);
        compareIndexed(expected, unmatched, digestReader, cancelled, nowMillis,
                divergences);
        collectAdded(current, unmatched, cancelled, divergences);
        return divergences.toResult();
    }

    /**
     * @return the working tree files by path, or empty when a path is
     *         enumerated more than once
     */
    private static Optional<Map<IndexPathRef, CurrentFile>> indexByPath(
            List<CurrentFile> current, BooleanSupplier cancelled)
            throws InterruptedException {
        Map<IndexPathRef, CurrentFile> byPath = new HashMap<>();
        for (CurrentFile file : current) {
            checkCancelled(cancelled);
            if (byPath.put(file.path(), file) != null) {
                return Optional.empty();
            }
        }
        return Optional.of(byPath);
    }

    /**
     * Compares every indexed stamp against the working tree, removing each
     * matched path from {@code unmatched}; what stays behind is what the index
     * does not know.
     */
    private static void compareIndexed(List<ProjectFileStamp> expected,
            Map<IndexPathRef, CurrentFile> unmatched,
            DigestReader digestReader, BooleanSupplier cancelled,
            long nowMillis, Divergences divergences)
            throws IOException, InterruptedException {
        for (ProjectFileStamp stamp : expected) {
            if (divergences.isTruncated()) {
                return;
            }
            checkCancelled(cancelled);
            CurrentFile file = unmatched.remove(stamp.path());
            if (file == null) {
                divergences.recordRemoved(stamp.path());
            } else if (file.size() != stamp.size()) {
                divergences.recordChanged(stamp.path());
            } else if (!metadataMatches(stamp, file, nowMillis)) {
                compareContent(stamp, file, digestReader, cancelled,
                        divergences);
            }
        }
    }

    private static void compareContent(ProjectFileStamp stamp,
            CurrentFile file, DigestReader digestReader,
            BooleanSupplier cancelled, Divergences divergences)
            throws IOException, InterruptedException {
        checkCancelled(cancelled);
        byte[] digest = Objects.requireNonNull(digestReader.sha256(file),
                "digestReader result");
        divergences.recordHashed();
        checkCancelled(cancelled);
        if (!Arrays.equals(stamp.contentSha256(), digest)) {
            divergences.recordChanged(stamp.path());
        }
    }

    /**
     * Reports the files the index never claimed. They are read back off the
     * enumeration order rather than off the map, so a run that stops at the
     * budget reports the same paths every time.
     */
    private static void collectAdded(List<CurrentFile> current,
            Map<IndexPathRef, CurrentFile> unmatched,
            BooleanSupplier cancelled, Divergences divergences)
            throws InterruptedException {
        if (unmatched.isEmpty() || divergences.isTruncated()) {
            return;
        }
        for (CurrentFile file : current) {
            if (divergences.isTruncated()) {
                return;
            }
            checkCancelled(cancelled);
            if (unmatched.containsKey(file.path())) {
                divergences.recordAdded(file.path());
            }
        }
    }

    private static Result brokenInput() {
        return new Result(false, 0, List.of(), List.of(), List.of(), false);
    }

    /** Divergences of one run, collected under a fixed budget. */
    private static final class Divergences {

        private final int maxDivergences;
        private final List<IndexPathRef> changed = new ArrayList<>();
        private final List<IndexPathRef> added = new ArrayList<>();
        private final List<IndexPathRef> removed = new ArrayList<>();
        private int hashedFiles;
        private boolean truncated;

        Divergences(int maxDivergences) {
            this.maxDivergences = maxDivergences;
        }

        boolean isTruncated() {
            return truncated;
        }

        void recordHashed() {
            hashedFiles++;
        }

        void recordChanged(IndexPathRef path) {
            record(changed, path);
        }

        void recordAdded(IndexPathRef path) {
            record(added, path);
        }

        void recordRemoved(IndexPathRef path) {
            record(removed, path);
        }

        Result toResult() {
            boolean hit = !truncated && size() == 0;
            return new Result(hit, hashedFiles, changed, added, removed,
                    !truncated);
        }

        private void record(List<IndexPathRef> target, IndexPathRef path) {
            if (size() == maxDivergences) {
                // One divergence past the budget: the difference is wider than
                // this run agreed to describe, so it stops here and reports a
                // bare miss exactly as the first-divergence exit used to.
                truncated = true;
                return;
            }
            target.add(path);
        }

        private int size() {
            return changed.size() + added.size() + removed.size();
        }
    }

    private static boolean metadataMatches(ProjectFileStamp expected,
            CurrentFile current, long nowMillis) {
        return expected.eclipseModificationStamp() >= 0
                && current.eclipseModificationStamp() >= 0
                && expected.eclipseModificationStamp()
                        == current.eclipseModificationStamp()
                && expected.lastModifiedMillis() == current.lastModifiedMillis()
                && hasSettled(current.lastModifiedMillis(), nowMillis);
    }

    private static boolean hasSettled(long lastModifiedMillis, long nowMillis) {
        return nowMillis - lastModifiedMillis >= MODIFICATION_SETTLE_MILLIS;
    }

    private static void checkCancelled(BooleanSupplier cancelled)
            throws InterruptedException {
        if (cancelled.getAsBoolean()) {
            throw new InterruptedException("Project index validation cancelled");
        }
    }
}
