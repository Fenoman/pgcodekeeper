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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetry.RepairRefusal;

/**
 * The reason the rule gives for every refusal.
 *
 * <p>A build that refused a repair and a build that was never offered one both
 * end in the same full rebuild, so the reason is the only thing that tells them
 * apart. Each refusal is driven on its own, against a divergence that is
 * otherwise repaired, so no case can pass for a second reason - and the reasons
 * are checked to be distinct, because a refusal that reads like another one
 * answers nothing.</p>
 */
class ProjectIndexRepairPlannerRefusalTest {

    private static final int BUDGET = 64;

    @Test
    void anAbsentValidationNamesThatNothingWasValidated() {
        assertRefused(RepairRefusal.NO_VALIDATION, null, true, BUDGET);
    }

    @Test
    void aTruncatedValidationNamesItsTruncation() {
        assertRefused(RepairRefusal.TRUNCATED_VALIDATION,
                result(List.of(project("a.sql")), List.of(), List.of(), false),
                true, BUDGET);
    }

    @Test
    void aVanishedFileNamesTheRemoval() {
        assertRefused(RepairRefusal.REMOVED_FILES,
                result(List.of(project("a.sql")), List.of(),
                        List.of(project("gone.sql")), true),
                true, BUDGET);
    }

    @Test
    void anAdditionAPreferenceForbidsNamesThePreference() {
        var divergence = result(List.of(project("a.sql")),
                List.of(project("added.sql")), List.of(), true);

        assertRefused(RepairRefusal.ADDED_FILES_DISABLED, divergence, false,
                BUDGET);
        assertRepaired(List.of("a.sql", "added.sql"), divergence, true, BUDGET);
    }

    @Test
    void anEmptyDivergenceNamesItsEmptiness() {
        assertRefused(RepairRefusal.EMPTY_DIVERGENCE,
                result(List.of(), List.of(), List.of(), true), true, BUDGET);
    }

    @Test
    void aDivergenceWiderThanTheBudgetNamesTheBudget() {
        assertRefused(RepairRefusal.OVER_BUDGET, divergenceOf(BUDGET + 1),
                true, BUDGET);
        // One file narrower is the same rule, deciding the other way.
        assertEquals(Optional.of(BUDGET),
                ProjectIndexRepairPlanner
                        .decide(divergenceOf(BUDGET), true, BUDGET)
                        .paths().map(List::size));
    }

    @Test
    void aPathOutsideTheProjectNamesItsOrigin() {
        assertRefused(RepairRefusal.NON_PROJECT_PATH,
                result(List.of(project("a.sql"), library("lib.sql")),
                        List.of(), List.of(), true),
                true, BUDGET);
        assertRefused(RepairRefusal.NON_PROJECT_PATH,
                result(List.of(project("a.sql")), List.of(library("lib.sql")),
                        List.of(), true),
                true, BUDGET);
    }

    @Test
    void everyRefusalOfTheRuleIsItsOwnReason() {
        var reasons = new HashSet<RepairRefusal>();
        reasons.add(refusalOf(null, true, BUDGET));
        reasons.add(refusalOf(result(List.of(project("a.sql")), List.of(),
                List.of(), false), true, BUDGET));
        reasons.add(refusalOf(result(List.of(project("a.sql")), List.of(),
                List.of(project("gone.sql")), true), true, BUDGET));
        reasons.add(refusalOf(result(List.of(project("a.sql")),
                List.of(project("added.sql")), List.of(), true), false,
                BUDGET));
        reasons.add(refusalOf(result(List.of(), List.of(), List.of(), true),
                true, BUDGET));
        reasons.add(refusalOf(divergenceOf(BUDGET + 1), true, BUDGET));
        reasons.add(refusalOf(result(List.of(library("lib.sql")), List.of(),
                List.of(), true), true, BUDGET));

        assertEquals(7, reasons.size(),
                "seven ways to refuse, and a line that cannot tell two of them"
                        + " apart is not worth publishing");
    }

    /**
     * The seven cases above are refusals. A set counts a case that stopped
     * refusing as one more distinct value, so this is what keeps that test
     * about the reasons and not about how many ways the rule can answer.
     */
    @Test
    void everyOneOfThemRefuses() {
        assertRefusal(refusalOf(null, true, BUDGET));
        assertRefusal(refusalOf(result(List.of(project("a.sql")), List.of(),
                List.of(), false), true, BUDGET));
        assertRefusal(refusalOf(result(List.of(project("a.sql")), List.of(),
                List.of(project("gone.sql")), true), true, BUDGET));
        assertRefusal(refusalOf(result(List.of(project("a.sql")),
                List.of(project("added.sql")), List.of(), true), false,
                BUDGET));
        assertRefusal(refusalOf(result(List.of(), List.of(), List.of(), true),
                true, BUDGET));
        assertRefusal(refusalOf(divergenceOf(BUDGET + 1), true, BUDGET));
        assertRefusal(refusalOf(result(List.of(library("lib.sql")), List.of(),
                List.of(), true), true, BUDGET));
    }

    @Test
    void theDecisionIsTheOneThePlanTakes() {
        List<ProjectIndexWarmValidator.Result> cases = new ArrayList<>();
        cases.add(null);
        cases.add(result(List.of(project("a.sql")), List.of(), List.of(),
                false));
        cases.add(result(List.of(project("a.sql")), List.of(),
                List.of(project("gone.sql")), true));
        cases.add(result(List.of(project("a.sql")),
                List.of(project("added.sql")), List.of(), true));
        cases.add(result(List.of(), List.of(), List.of(), true));
        cases.add(divergenceOf(BUDGET + 1));
        cases.add(result(List.of(library("lib.sql")), List.of(), List.of(),
                true));
        cases.add(result(List.of(project("b.sql")), List.of(project("a.sql")),
                List.of(), true));

        for (boolean addedFilesPermitted : List.of(false, true)) {
            for (ProjectIndexWarmValidator.Result divergence : cases) {
                assertEquals(
                        ProjectIndexRepairPlanner.plan(divergence,
                                addedFilesPermitted, BUDGET),
                        ProjectIndexRepairPlanner.decide(divergence,
                                addedFilesPermitted, BUDGET).paths(),
                        "the reason is added to the decision and takes nothing"
                                + " away from it");
            }
        }
    }

    @Test
    void aDecisionEitherRepairsOrRefuses() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProjectIndexRepairPlanner.Decision(
                        Optional.of(List.of("a.sql")),
                        RepairRefusal.OVER_BUDGET));
        assertThrows(IllegalArgumentException.class,
                () -> new ProjectIndexRepairPlanner.Decision(Optional.empty(),
                        null));
    }

    private static void assertRefused(RepairRefusal expected,
            ProjectIndexWarmValidator.Result validation,
            boolean addedFilesPermitted, int maxDivergences) {
        var decision = ProjectIndexRepairPlanner.decide(validation,
                addedFilesPermitted, maxDivergences);

        assertEquals(Optional.empty(), decision.paths());
        assertEquals(expected, decision.refusal());
    }

    private static void assertRepaired(List<String> expected,
            ProjectIndexWarmValidator.Result validation,
            boolean addedFilesPermitted, int maxDivergences) {
        var decision = ProjectIndexRepairPlanner.decide(validation,
                addedFilesPermitted, maxDivergences);

        assertEquals(Optional.of(expected), decision.paths());
        assertNull(decision.refusal());
    }

    private static void assertRefusal(RepairRefusal reason) {
        assertNotNull(reason, "the rule answered with a repair, not a refusal");
    }

    private static RepairRefusal refusalOf(
            ProjectIndexWarmValidator.Result validation,
            boolean addedFilesPermitted, int maxDivergences) {
        return ProjectIndexRepairPlanner.decide(validation,
                addedFilesPermitted, maxDivergences).refusal();
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
