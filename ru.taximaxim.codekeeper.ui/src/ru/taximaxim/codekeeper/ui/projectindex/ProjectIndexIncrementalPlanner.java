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

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class ProjectIndexIncrementalPlanner {

    /**
     * Largest batch one incremental revision may carry. Every incremental
     * limit in the plug-in derives from this single number so that the
     * classifier, the planner, the memory index and the store can never
     * disagree about what a batch is.
     *
     * <p>The daily pull of the reference project replaces 50 files at the
     * median, 72 at p75 and 130 at p90, so the limit has to clear 130 with
     * room to spare. The upper bound is the point where re-parsing the batch
     * costs as much as rebuilding the whole index: a full build of that
     * project takes 54 s locally and 128 s on the reference VM, while one
     * replaced file costs about 0.09 s locally and 0.21 s on the VM, which
     * puts the break-even near 600 files. This limit sits between the two,
     * covering p90 twice over while staying at less than half of break-even.
     */
    public static final int MAX_BATCH_SIZE = 256;

    private ProjectIndexIncrementalPlanner() {
    }

    /**
     * Returns whether a single existing file may replace its packed
     * contribution without rebuilding dependants. Only changes that preserve
     * every definition's dependency-visible shape are accepted.
     */
    public static boolean isSafeReplacement(FileContribution previous,
            FileContribution replacement) {
        Objects.requireNonNull(previous, "previous");
        return isSafeReplacement(previous.path(), previous.definitions(),
                replacement);
    }

    public static boolean isSafeReplacement(IndexPathRef previousPath,
            List<PackedDefinition> previousDefinitions,
            FileContribution replacement) {
        Objects.requireNonNull(previousPath, "previousPath");
        Objects.requireNonNull(previousDefinitions, "previousDefinitions");
        Objects.requireNonNull(replacement, "replacement");
        return previousPath.equals(replacement.path())
                && sameShapes(previousDefinitions,
                        replacement.definitions());
    }

    /**
     * Returns whether one file of a batch may replace the contribution the
     * index holds for it. This is the per-file half of
     * {@link #areSafeReplacements(Map, ProjectIndexDelta)}, which a batch
     * passes only when every one of its files passes it, so a batch that
     * fails it here is already known to need a full rebuild.
     *
     * @param previousDefinitions definitions the index holds for the batch
     * @param replacement         contribution the file was re-analysed into
     * @return whether the file preserved every definition's shape
     */
    public static boolean isSafeReplacement(
            Map<IndexPathRef, List<PackedDefinition>> previousDefinitions,
            FileContribution replacement) {
        Objects.requireNonNull(previousDefinitions, "previousDefinitions");
        Objects.requireNonNull(replacement, "replacement");
        List<PackedDefinition> previous =
                previousDefinitions.get(replacement.path());
        return previous != null
                && isSafeReplacement(replacement.path(), previous, replacement);
    }

    public static boolean areSafeReplacements(
            Map<IndexPathRef, List<PackedDefinition>> previousDefinitions,
            ProjectIndexDelta replacements) {
        return areSafeReplacements(previousDefinitions, replacements,
                Set.of());
    }

    /**
     * Returns whether a batch may be applied to the index it was built from.
     *
     * <p>The batch is two halves. Every path the index already holds has to
     * preserve the dependency-visible shape of its definitions, exactly as
     * before. Every path in {@code addedPaths} has no shape to preserve, and
     * its half of the question - whether a definition appearing out of nowhere
     * can disturb what the index holds - is answered by
     * {@link #permitsAddedFiles(AddedFilesGuard, Collection)} instead, because
     * it can only be answered against the index and not against a batch.
     *
     * @param previousDefinitions definitions the index holds, one entry per
     *                            replaced path and none for an added one
     * @param replacements        the batch
     * @param addedPaths          paths of the batch the index does not hold
     * @return whether the batch may be applied
     */
    public static boolean areSafeReplacements(
            Map<IndexPathRef, List<PackedDefinition>> previousDefinitions,
            ProjectIndexDelta replacements,
            Set<IndexPathRef> addedPaths) {
        Objects.requireNonNull(previousDefinitions, "previousDefinitions");
        Objects.requireNonNull(replacements, "replacements");
        Objects.requireNonNull(addedPaths, "addedPaths");
        List<ProjectIndexDelta.Change> changes = replacements.changes();
        if (changes.size() > MAX_BATCH_SIZE
                || previousDefinitions.size() + addedPaths.size()
                        != changes.size()) {
            return false;
        }
        var replaced = new HashSet<IndexPathRef>();
        var added = new HashSet<IndexPathRef>();
        for (ProjectIndexDelta.Change change : changes) {
            if (change.operation() != ProjectIndexDelta.Operation.REPLACE) {
                return false;
            }
            if (addedPaths.contains(change.path())) {
                // Nothing to compare: the index holds no shape for this path,
                // and one it did hold would make the batch a liar.
                if (!added.add(change.path())
                        || previousDefinitions.containsKey(change.path())) {
                    return false;
                }
                continue;
            }
            if (!replaced.add(change.path())) {
                return false;
            }
            // A replacement carries the path of its contribution, so the
            // per-file check below looks the previous definitions up by the
            // same key the change is filed under.
            if (!isSafeReplacement(previousDefinitions,
                    change.contribution())) {
                return false;
            }
        }
        return replaced.equals(previousDefinitions.keySet())
                && added.equals(addedPaths);
    }

    /**
     * Returns whether a batch may introduce these files without rebuilding the
     * index. There is no shape to preserve for a file the index never held, so
     * this takes the place of {@link #areSafeReplacements(Map,
     * ProjectIndexDelta)} for the added half of a batch.
     *
     * <p>Two things can go wrong when a definition appears out of nowhere, and
     * each one refuses the batch:
     *
     * <ul>
     * <li>a file of the index may hold a reference that never bound, and the
     * new definition could bind it — which would silently stale that file's
     * contribution;
     * <li>the new definition may claim a subject the index already has, and an
     * overload or a shadowing object can redirect references that resolve
     * today. Narrowing that down would mean enumerating every alias a reference
     * was written under, and the match key keeps alias and global inside
     * itself, so no single query covers them.
     * </ul>
     *
     * <p>Nothing checks the added files against each other, and that is not an
     * omission: two new files claiming one subject cannot redirect anything.
     * Any reference the index already holds for that subject either resolved —
     * so the index has a definition for it and the second check refuses — or it
     * did not, so its file is flagged and the first check refuses.
     *
     * @param added contributions for paths the index does not have
     * @return whether the addition cannot disturb what the index already holds
     */
    public static boolean permitsAddedFiles(ProjectIndexView view,
            Collection<FileContribution> added)
            throws ProjectIndexFormatException {
        Objects.requireNonNull(view, "view");
        return permitsAddedFiles(new ViewGuard(view), added);
    }

    /**
     * The two questions the rule asks of the index an addition is landing on.
     *
     * <p>The rule is expressed against this rather than against a
     * {@link ProjectIndexView} because the index is not always reachable as a
     * view: an increment holds a lease on the live index instead, and a lease
     * lives in another package which must not learn about this one. Both
     * answers are cheap by construction - neither decodes a contribution.
     */
    public interface AddedFilesGuard {

        /**
         * @return whether any file of the index may hold a reference that never
         *         bound
         */
        boolean anyFileMayHoldUnresolvedReferences()
                throws ProjectIndexFormatException;

        /**
         * @param subject subject a definition of an added file claims
         * @return whether the index already holds a definition for it
         */
        boolean holdsDefinitionFor(ProjectIndexDefinitionSubject subject)
                throws ProjectIndexFormatException;
    }

    /**
     * The same rule, asked of whatever holds the index. See
     * {@link #permitsAddedFiles(ProjectIndexView, Collection)} for why each
     * refusal is a refusal.
     *
     * @param guard the index the addition is landing on
     * @param added contributions for paths the index does not have
     * @return whether the addition cannot disturb what the index already holds
     */
    public static boolean permitsAddedFiles(AddedFilesGuard guard,
            Collection<FileContribution> added)
            throws ProjectIndexFormatException {
        Objects.requireNonNull(guard, "guard");
        Objects.requireNonNull(added, "added");
        if (added.isEmpty()) {
            // Vacuous: nothing is being added, so nothing here forbids the
            // batch. Callers combine this with their own checks, and a refusal
            // would veto a batch of pure replacements.
            return true;
        }
        if (guard.anyFileMayHoldUnresolvedReferences()) {
            return false;
        }
        for (FileContribution contribution : added) {
            for (PackedDefinition definition : contribution.definitions()) {
                Optional<ProjectIndexDefinitionSubject> subject =
                        ProjectIndexDefinitionSubject.of(definition);
                if (subject.isEmpty()
                        || guard.holdsDefinitionFor(subject.orElseThrow())) {
                    return false;
                }
            }
        }
        return true;
    }

    private record ViewGuard(ProjectIndexView view)
            implements AddedFilesGuard {

        @Override
        public boolean anyFileMayHoldUnresolvedReferences()
                throws ProjectIndexFormatException {
            return view.anyFileMayHoldUnresolvedReferences();
        }

        @Override
        public boolean holdsDefinitionFor(
                ProjectIndexDefinitionSubject subject)
                throws ProjectIndexFormatException {
            return !view.definitions(subject).isEmpty();
        }
    }

    private static boolean sameShapes(List<PackedDefinition> previous,
            List<PackedDefinition> replacement) {
        if (previous.size() != replacement.size()) {
            return false;
        }
        List<byte[]> previousHashes = sortedHashes(previous);
        List<byte[]> replacementHashes = sortedHashes(replacement);
        for (int i = 0; i < previousHashes.size(); i++) {
            if (!Arrays.equals(previousHashes.get(i), replacementHashes.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static List<byte[]> sortedHashes(List<PackedDefinition> definitions) {
        return definitions.stream()
                .map(DefinitionShapeHasher::hash)
                .sorted(Arrays::compareUnsigned)
                .toList();
    }
}
