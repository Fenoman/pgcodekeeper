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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

/**
 * The rule that decides whether a stale index is repaired from the files a warm
 * validation named, or rebuilt from nothing.
 *
 * <p>Every refusal is checked on its own, against a divergence that would
 * otherwise be accepted, so that no test passes for a second reason. The rule
 * is fail-closed: a refusal costs a rebuild the caller was going to pay for
 * anyway, while a wrong acceptance is the only outcome that can cost
 * correctness.</p>
 */
class ProjectIndexRepairPlannerTest {

    private static final int BUDGET = 64;

    /**
     * A validation orders each of its lists on its own, so the two interleave:
     * these four files are sorted inside the divergence and out of order the
     * moment the lists are put end to end. Only a batch that is sorted after
     * the merge names them in the order below.
     */
    @Test
    void aSmallCompleteDivergenceOfProjectFilesIsRepaired() {
        assertEquals(
                Optional.of(List.of("a.sql", "b.sql", "c.sql", "d.sql")),
                ProjectIndexRepairPlanner.plan(
                        result(List.of(project("b.sql"), project("d.sql")),
                                List.of(project("c.sql"), project("a.sql")),
                                List.of(), true),
                        true, BUDGET),
                "the batch is the changed and the added files, in one order"
                        + " that does not depend on which list they came from");
    }

    @Test
    void aTruncatedValidationIsRebuilt() {
        // Same divergence, and the only difference is that the run stopped
        // before it had described all of it.
        assertEquals(Optional.empty(),
                ProjectIndexRepairPlanner.plan(
                        result(List.of(project("a.sql")), List.of(),
                                List.of(), false),
                        true, BUDGET),
                "an incomplete list witnesses a miss and is not a work list");
    }

    @Test
    void aVanishedFileIsRebuilt() {
        assertEquals(Optional.empty(),
                ProjectIndexRepairPlanner.plan(
                        result(List.of(project("a.sql")), List.of(),
                                List.of(project("gone.sql")), true),
                        true, BUDGET),
                "a batch replaces and introduces files and cannot retire one");
    }

    @Test
    void aFileOutsideTheProjectIsRebuilt() {
        assertEquals(Optional.empty(),
                ProjectIndexRepairPlanner.plan(
                        result(List.of(project("a.sql"), library("lib.sql")),
                                List.of(), List.of(), true),
                        true, BUDGET),
                "a library path is not addressable as an incremental path");
        assertEquals(Optional.empty(),
                ProjectIndexRepairPlanner.plan(
                        result(List.of(project("a.sql")),
                                List.of(library("lib.sql")), List.of(), true),
                        true, BUDGET),
                "and neither is one that appeared");
    }

    @Test
    void aDivergenceWiderThanTheBudgetIsRebuilt() {
        assertTrue(ProjectIndexRepairPlanner.plan(
                divergenceOf(BUDGET), true, BUDGET).isPresent(),
                "the budget is what this caller agreed to repair, so a"
                        + " divergence of exactly that size is repaired");
        assertEquals(Optional.empty(),
                ProjectIndexRepairPlanner.plan(
                        divergenceOf(BUDGET + 1), true, BUDGET),
                "one file more is a rebuild");
    }

    @Test
    void anAdditionIsRebuiltWhileTheProjectRefusesAdditions() {
        var divergence = result(List.of(project("a.sql")),
                List.of(project("added.sql")), List.of(), true);

        assertEquals(Optional.empty(),
                ProjectIndexRepairPlanner.plan(divergence, false, BUDGET),
                "the incremental path refuses this batch as well, and finding"
                        + " that out costs a wasted preflight");
        assertTrue(ProjectIndexRepairPlanner.plan(divergence, true, BUDGET)
                .isPresent(),
                "the very same divergence is repaired once the project permits"
                        + " an addition");
    }

    @Test
    void anEmptyDivergenceIsRebuilt() {
        assertEquals(Optional.empty(),
                ProjectIndexRepairPlanner.plan(
                        result(List.of(), List.of(), List.of(), true),
                        true, BUDGET),
                "nothing diverged, so there is nothing to repair from");
    }

    @Test
    void anAbsentValidationIsRebuilt() {
        assertEquals(Optional.empty(),
                ProjectIndexRepairPlanner.plan(null, true, BUDGET),
                "no warm index was validated, so nothing describes the tree");
    }

    /** A divergence of {@code size} changed project files. */
    private static ProjectIndexWarmValidator.Result divergenceOf(int size) {
        return result(IntStream.range(0, size)
                .mapToObj(index -> project("file_" + index + ".sql"))
                .toList(), List.of(), List.of(), true);
    }

    private static ProjectIndexWarmValidator.Result result(
            List<IndexPathRef> changed, List<IndexPathRef> added,
            List<IndexPathRef> removed, boolean complete) {
        var everything = new ArrayList<IndexPathRef>(changed);
        everything.addAll(added);
        everything.addAll(removed);
        return new ProjectIndexWarmValidator.Result(everything.isEmpty(),
                0, changed, added, removed, complete);
    }

    private static IndexPathRef project(String relativePath) {
        return new IndexPathRef(IndexPathOrigin.PROJECT, relativePath);
    }

    private static IndexPathRef library(String relativePath) {
        return new IndexPathRef(IndexPathOrigin.LIBRARY, relativePath);
    }
}
