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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetry.RepairRefusal;

/**
 * Decides whether a warm miss is worth repairing from the files it named
 * instead of rebuilding the whole index.
 *
 * <p>A warm validation already knows exactly which files the index disagrees
 * with. Reading those files back costs about as much as one incremental batch
 * of the same size, while a rebuild costs a full parse of the project no
 * matter how small the disagreement is. On the reference project - 13 777
 * files, 105 s cold - a repair of one file takes 1.65 s and a repair of 128
 * files takes 75.5 s, so the two meet somewhere near 217 files and everything
 * below that is worth repairing. Nothing here is measured, though: this only
 * decides, and every number it needs is an argument.</p>
 *
 * <p>The decision is fail-closed and lives here alone. A caller either gets a
 * batch it may repair from or gets nothing and rebuilds; no caller is expected
 * to add a condition of its own, and none may weaken one.</p>
 */
public final class ProjectIndexRepairPlanner {

    private ProjectIndexRepairPlanner() {
    }

    /**
     * Returns the files a stale index may be repaired from.
     *
     * <p>Every refusal below sends the caller to a full rebuild:</p>
     *
     * <ul>
     * <li>nothing was validated at all, so there is no divergence to describe;
     * <li>the validation stopped before it described the whole divergence, so
     * the files it named are a witness of a miss and not a work list;
     * <li>a file vanished - an incremental batch replaces and introduces files
     * and has no way to retire one;
     * <li>a file outside the project moved: a library is not addressable as an
     * incremental path, and a library that moved can restate what the whole
     * index resolved against;
     * <li>the divergence is wider than the caller agreed to repair;
     * <li>a file appeared while the project does not permit an addition to join
     * a batch. The incremental path refuses such a batch as well, so this only
     * saves the work of finding that out;
     * <li>nothing diverged, which is either a hit or a run that answered
     * nothing, and neither has anything to repair.
     * </ul>
     *
     * @param validation          what a warm validation found, may be null when
     *                            no warm index was validated at all
     * @param addedFilesPermitted whether the project permits a file the index
     *                            does not hold to join an incremental batch
     * @param maxDivergences      widest divergence this caller will repair
     *                            rather than rebuild; must not exceed
     *                            {@link ProjectIndexIncrementalPlanner#MAX_BATCH_SIZE},
     *                            which is the widest batch that can be applied
     *                            at all
     * @return project-relative paths to repair from, ordered so that one
     *         working tree always produces one batch, or empty when the index
     *         has to be rebuilt
     */
    public static Optional<List<String>> plan(
            ProjectIndexWarmValidator.Result validation,
            boolean addedFilesPermitted, int maxDivergences) {
        return decide(validation, addedFilesPermitted, maxDivergences).paths();
    }

    /**
     * Decides the same thing {@link #plan} decides and names the refusal when
     * it refuses.
     *
     * <p>The reason is for the diagnostic line alone: a build that ends in a
     * rebuild looks exactly like a build that was never offered a repair, and
     * only this can tell the two apart. The decision itself is the one above -
     * {@code plan} is this method with the reason dropped - so no caller can
     * act on a condition this one does not apply.</p>
     *
     * @param validation          what a warm validation found, may be null when
     *                            no warm index was validated at all
     * @param addedFilesPermitted whether the project permits a file the index
     *                            does not hold to join an incremental batch
     * @param maxDivergences      widest divergence this caller will repair
     * @return the files to repair from, or the reason there are none
     */
    public static Decision decide(
            ProjectIndexWarmValidator.Result validation,
            boolean addedFilesPermitted, int maxDivergences) {
        if (validation == null) {
            return Decision.refused(RepairRefusal.NO_VALIDATION);
        }
        if (!validation.complete()) {
            return Decision.refused(RepairRefusal.TRUNCATED_VALIDATION);
        }
        if (!validation.removed().isEmpty()) {
            return Decision.refused(RepairRefusal.REMOVED_FILES);
        }
        if (!addedFilesPermitted && !validation.added().isEmpty()) {
            return Decision.refused(RepairRefusal.ADDED_FILES_DISABLED);
        }
        int size = validation.changed().size() + validation.added().size();
        if (size == 0) {
            return Decision.refused(RepairRefusal.EMPTY_DIVERGENCE);
        }
        if (size > maxDivergences) {
            return Decision.refused(RepairRefusal.OVER_BUDGET);
        }
        var paths = new ArrayList<String>(size);
        if (!collectProjectPaths(validation.changed(), paths)
                || !collectProjectPaths(validation.added(), paths)) {
            return Decision.refused(RepairRefusal.NON_PROJECT_PATH);
        }
        paths.sort(Comparator.naturalOrder());
        return Decision.repairFrom(List.copyOf(paths));
    }

    /**
     * What the rule decided. Exactly one half is present: either the files a
     * repair may read back, or the reason it will not happen.
     */
    public record Decision(Optional<List<String>> paths,
            RepairRefusal refusal) {

        public Decision {
            Objects.requireNonNull(paths, "paths");
            if (paths.isPresent() == (refusal != null)) {
                throw new IllegalArgumentException(
                        "A decision either repairs from files or names why it refused");
            }
        }

        private static Decision repairFrom(List<String> paths) {
            return new Decision(Optional.of(paths), null);
        }

        private static Decision refused(RepairRefusal refusal) {
            return new Decision(Optional.empty(),
                    Objects.requireNonNull(refusal, "refusal"));
        }
    }

    /**
     * Collects the paths of one divergence list, refusing the whole plan as
     * soon as one of them does not belong to the project.
     *
     * @return whether every path of the list was collected
     */
    private static boolean collectProjectPaths(List<IndexPathRef> divergence,
            List<String> paths) {
        for (IndexPathRef path : divergence) {
            if (path.origin() != IndexPathOrigin.PROJECT) {
                return false;
            }
            paths.add(path.relativePath());
        }
        return true;
    }
}
