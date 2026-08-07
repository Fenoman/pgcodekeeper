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
package ru.taximaxim.codekeeper.ui.builders;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import ru.taximaxim.codekeeper.ui.projectindex.IndexPathOrigin;
import ru.taximaxim.codekeeper.ui.projectindex.IndexPathRef;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexIncrementalPlanner;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectInputDeltaPolicy;

final class ProjectBuildDeltaClassifier {

    static final int MAX_INCREMENTAL_PATHS =
            ProjectIndexIncrementalPlanner.MAX_BATCH_SIZE;

    enum Kind {
        ADDED,
        REMOVED,
        CHANGED
    }

    enum Mode {
        NO_OP("no_op"), //$NON-NLS-1$
        INCREMENTAL("incremental"), //$NON-NLS-1$
        FULL("full"); //$NON-NLS-1$

        private final String token;

        Mode(String token) {
            this.token = token;
        }

        String token() {
            return token;
        }
    }

    /**
     * Why a delta was refused an incremental build. Purely diagnostic: the
     * reason names the check that produced a verdict and never takes part in
     * producing it.
     *
     * <p>{@link #NONE} is the reason of a verdict that is not a full rebuild;
     * every other constant belongs to a full rebuild and to exactly one place
     * that decides on one, so a log line identifies that place on its own.
     */
    enum Reason {
        /** No full rebuild was ordered. */
        NONE("none"), //$NON-NLS-1$
        /** A full rebuild whose origin predates this diagnostic. */
        UNSPECIFIED("unspecified"), //$NON-NLS-1$
        DIRECT_FULL_BUILD("direct_full_build"), //$NON-NLS-1$
        NO_DELTA("no_delta"), //$NON-NLS-1$
        DELTA_VISIT_FAILED("delta_visit_failed"), //$NON-NLS-1$
        NULL_DELTAS("null_deltas"), //$NON-NLS-1$
        NULL_ENTRY("null_entry"), //$NON-NLS-1$
        UNKNOWN_FLAGS("unknown_flags"), //$NON-NLS-1$
        UNKNOWN_KIND("unknown_kind"), //$NON-NLS-1$
        PROJECT_ROOT_CHANGED("project_root_changed"), //$NON-NLS-1$
        UNSUPPORTED_RESOURCE("unsupported_resource"), //$NON-NLS-1$
        PRIVILEGE_OVERRIDE("privilege_override"), //$NON-NLS-1$
        CONFIGURATION_INPUT("configuration_input"), //$NON-NLS-1$
        DIRECTORY("directory"), //$NON-NLS-1$
        NON_SQL("non_sql"), //$NON-NLS-1$
        REMOVED_FILE("removed_file"), //$NON-NLS-1$
        STRUCTURAL_CHANGE("structural_change"), //$NON-NLS-1$
        ADDED_FILE_DISABLED("added_file_disabled"), //$NON-NLS-1$
        DUPLICATE_PATH("duplicate_path"), //$NON-NLS-1$
        BATCH_TOO_LARGE("batch_too_large"), //$NON-NLS-1$
        CLASSIFICATION_FAILURE("classification_failure"), //$NON-NLS-1$
        CONTINUITY_RECONCILIATION("continuity_reconciliation"), //$NON-NLS-1$
        /**
         * The delta held nothing this project indexes, and the configuration
         * that decided so is no longer the settled one. A classification is
         * the only step of a build cycle that can end the cycle by itself, so
         * it is the only one whose configuration nothing downstream ever
         * checks.
         */
        CONFIGURATION_MOVED("configuration_moved"); //$NON-NLS-1$

        private final String token;

        Reason(String token) {
            this.token = token;
        }

        String token() {
            return token;
        }
    }

    record Entry(
            String relativePath,
            Kind kind,
            boolean contentChanged,
            boolean structuralChange,
            boolean indexedHierarchy,
            boolean excluded,
            boolean directory) {

        Entry(String relativePath, Kind kind, boolean contentChanged,
                boolean structuralChange, boolean indexedHierarchy,
                boolean excluded) {
            this(relativePath, kind, contentChanged, structuralChange,
                    indexedHierarchy, excluded, false);
        }

        Entry {
            relativePath = normalize(relativePath);
            Objects.requireNonNull(kind, "kind"); //$NON-NLS-1$
        }
    }

    /**
     * @param reason   why a full rebuild was ordered, {@link Reason#NONE} when
     *                 none was
     * @param evidence short diagnostic detail of the verdict, may be null and
     *                 may accompany any mode; carries no path, only counts,
     *                 flags and first path segments
     */
    record Result(Mode mode, List<String> relativePaths, Reason reason,
            String evidence) {

        Result {
            Objects.requireNonNull(mode, "mode"); //$NON-NLS-1$
            Objects.requireNonNull(reason, "reason"); //$NON-NLS-1$
            relativePaths = List.copyOf(Objects.requireNonNull(
                    relativePaths, "relativePaths")); //$NON-NLS-1$
            if ((mode == Mode.INCREMENTAL) != !relativePaths.isEmpty()) {
                throw new IllegalArgumentException(
                        "Only an incremental result may retain paths"); //$NON-NLS-1$
            }
            if ((mode == Mode.FULL) != (reason != Reason.NONE)) {
                throw new IllegalArgumentException(
                        "Only a full rebuild may name a reason, and it always must"); //$NON-NLS-1$
            }
            if (relativePaths.size() > MAX_INCREMENTAL_PATHS
                    || !isStrictlySorted(relativePaths)
                    || !isCanonical(relativePaths)) {
                throw new IllegalArgumentException(
                        "Incremental paths must be canonical, unique and sorted"); //$NON-NLS-1$
            }
        }

        Result(Mode mode, List<String> relativePaths) {
            this(mode, relativePaths, defaultReason(mode), null);
        }

        Result(Mode mode, String relativePath) {
            this(mode, relativePath == null
                    ? List.of() : List.of(relativePath));
        }

        String relativePath() {
            return relativePaths.size() == 1
                    ? relativePaths.getFirst() : null;
        }

        private static Reason defaultReason(Mode mode) {
            return mode == Mode.FULL
                    ? Reason.UNSPECIFIED : Reason.NONE;
        }
    }

    private ProjectBuildDeltaClassifier() {
    }

    static Result classify(Collection<Entry> entries,
            boolean allowAddedFiles) {
        Objects.requireNonNull(entries, "entries"); //$NON-NLS-1$
        Set<String> candidates = new TreeSet<>();
        for (Entry entry : entries) {
            Objects.requireNonNull(entry, "entry"); //$NON-NLS-1$
            boolean effectiveChange = entry.kind() != Kind.CHANGED
                    || entry.contentChanged() || entry.structuralChange();
            if (!effectiveChange) {
                continue;
            }
            if (ProjectInputDeltaPolicy.isConfigurationInput(
                    entry.relativePath())) {
                return full(Reason.CONFIGURATION_INPUT);
            }
            if (!entry.indexedHierarchy() || entry.excluded()) {
                continue;
            }
            if (entry.directory()) {
                return full(Reason.DIRECTORY);
            }
            if (!entry.relativePath().toLowerCase(Locale.ROOT)
                    .endsWith(".sql")) { //$NON-NLS-1$
                return full(Reason.NON_SQL);
            }
            Reason refusal = batchRefusal(entry, allowAddedFiles);
            if (refusal != null) {
                return full(refusal);
            }
            if (!candidates.add(entry.relativePath())) {
                return full(Reason.DUPLICATE_PATH);
            }
            if (candidates.size() > MAX_INCREMENTAL_PATHS) {
                return full(Reason.BATCH_TOO_LARGE);
            }
        }
        return candidates.isEmpty()
                ? new Result(Mode.NO_OP, List.of())
                : new Result(Mode.INCREMENTAL,
                        List.copyOf(candidates));
    }

    /**
     * Why one indexed file of the delta may not be re-analysed on its own, or
     * null when it may.
     *
     * <p>A removal never may. Which files held a reference to what the removal
     * took away cannot be narrowed down: a match key carries the alias and the
     * global flag of the location it came from, so one object referenced under
     * several spellings answers to several keys and no single query covers
     * them.
     *
     * <p>A structural change never may either. The flag says the resource
     * itself moved, was replaced or changed type, which is not the plain
     * appearance or edit of a file.
     *
     * <p>An addition may, but only while the preference is on, because whether
     * a new definition can disturb what the index already holds is decided
     * later, against the index itself, by
     * {@link ProjectIndexIncrementalPlanner#permitsAddedFiles}. With the
     * preference off the answer here is the one this classifier has always
     * given.
     *
     * <p>Refusal and its reason are one decision, so they are read from one
     * place: {@link #canJoinBatch} is this method seen as a predicate and the
     * two cannot drift apart.
     */
    static Reason batchRefusal(Entry entry, boolean allowAddedFiles) {
        if (entry.kind() == Kind.REMOVED) {
            return Reason.REMOVED_FILE;
        }
        if (entry.structuralChange()) {
            return Reason.STRUCTURAL_CHANGE;
        }
        if (entry.kind() == Kind.CHANGED || allowAddedFiles) {
            return null;
        }
        return Reason.ADDED_FILE_DISABLED;
    }

    /**
     * Whether one indexed file of the delta may be re-analysed on its own
     * instead of sending the whole project to a full rebuild. See
     * {@link #batchRefusal}, which decides this.
     */
    static boolean canJoinBatch(Entry entry, boolean allowAddedFiles) {
        return batchRefusal(entry, allowAddedFiles) == null;
    }

    static Result full() {
        return full(Reason.UNSPECIFIED);
    }

    static Result full(Reason reason) {
        return full(reason, null);
    }

    static Result full(Reason reason, String evidence) {
        return new Result(Mode.FULL, List.of(), reason, evidence);
    }

    /**
     * Returns the verdict with diagnostic detail attached. The verdict itself
     * is carried over untouched: evidence explains a decision and never
     * participates in one.
     *
     * @param result   verdict to describe
     * @param evidence short diagnostic detail, may be null
     * @return the same verdict carrying the given evidence
     */
    static Result withEvidence(Result result, String evidence) {
        return new Result(result.mode(), result.relativePaths(),
                result.reason(), evidence);
    }

    static String normalize(String value) {
        return new IndexPathRef(IndexPathOrigin.PROJECT,
                value).relativePath();
    }

    private static boolean isStrictlySorted(List<String> paths) {
        for (int i = 1; i < paths.size(); i++) {
            if (paths.get(i - 1).compareTo(paths.get(i)) >= 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isCanonical(List<String> paths) {
        return paths.stream()
                .allMatch(path -> path.equals(normalize(path)));
    }
}
