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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import ru.taximaxim.codekeeper.ui.builders.ProjectBuildContinuity.BuildContinuityProof;
import ru.taximaxim.codekeeper.ui.builders.ProjectBuildContinuity.Kind;

class ProjectBuildContinuityTest {

    private static final String FIRST_PATH =
            "SCHEMA/app/TABLE/first.sql"; //$NON-NLS-1$
    private static final String SECOND_PATH =
            "SCHEMA/app/TABLE/second.sql"; //$NON-NLS-1$

    @Test
    void proofCannotBeConstructedOutsideItsOwner() {
        assertTrue(Modifier.isPublic(
                BuildContinuityProof.class.getModifiers()));
        assertTrue(Modifier.isFinal(
                BuildContinuityProof.class.getModifiers()));
        assertTrue(BuildContinuityProof.class.getDeclaredConstructors().length > 0);
        for (var constructor
                : BuildContinuityProof.class.getDeclaredConstructors()) {
            assertTrue(Modifier.isPrivate(constructor.getModifiers()));
        }
    }

    @Test
    void proofRetainsAnchorIdentityWithoutPredecessorReference()
            throws Exception {
        assertFalse(Arrays.stream(
                BuildContinuityProof.class.getDeclaredFields())
                .anyMatch(field -> field.getType()
                        == BuildContinuityProof.class));
        assertEquals(long.class,
                BuildContinuityProof.class
                        .getDeclaredField(
                                "indexedAnchorSequence") //$NON-NLS-1$
                        .getType());
    }

    @Test
    void reconciliationEstablishesLineageOnlyAfterPublication() {
        var continuity = new ProjectBuildContinuity();
        BuildContinuityProof reconciliation =
                continuity.beginReconciliation(1);

        assertEquals(Kind.RECONCILIATION,
                reconciliation.kind());
        assertNull(reconciliation.relativePath());
        assertTrue(reconciliation.isCurrent());
        assertFalse(reconciliation.permitsIncremental());

        BuildContinuityProof beforePublication =
                continuity.beginSingleFile(2, FIRST_PATH);
        assertEquals(Kind.RECONCILIATION,
                beforePublication.kind());
        assertFalse(beforePublication.permitsIncremental());
        assertFalse(beforePublication.descendsFrom(
                reconciliation));
    }

    @Test
    void acceptedReconciliationAuthorizesExactNextSingleFile() {
        var continuity = new ProjectBuildContinuity();
        BuildContinuityProof reconciliation =
                continuity.beginReconciliation(1);
        assertTrue(continuity.accept(reconciliation));

        BuildContinuityProof single =
                continuity.beginSingleFile(2, FIRST_PATH);

        assertEquals(Kind.INCREMENTAL, single.kind());
        assertEquals(FIRST_PATH, single.relativePath());
        assertEquals(List.of(FIRST_PATH),
                single.relativePaths());
        assertTrue(single.isCurrent());
        assertTrue(single.permitsIncremental());
        assertTrue(single.descendsFrom(reconciliation));
    }

    @Test
    void acceptedReconciliationAuthorizesCanonicalBatch() {
        var continuity = new ProjectBuildContinuity();
        BuildContinuityProof reconciliation =
                continuity.beginReconciliation(1);
        assertTrue(continuity.accept(reconciliation));

        BuildContinuityProof batch =
                continuity.beginIncremental(2,
                        List.of(SECOND_PATH, FIRST_PATH));

        assertEquals(Kind.INCREMENTAL, batch.kind());
        assertEquals(List.of(FIRST_PATH, SECOND_PATH),
                batch.relativePaths());
        assertNull(batch.relativePath());
        assertTrue(batch.permitsIncremental(
                List.of(SECOND_PATH, FIRST_PATH)));
        assertFalse(batch.permitsIncremental(
                List.of(FIRST_PATH)));
        assertTrue(batch.descendsFrom(reconciliation));
        assertThrows(UnsupportedOperationException.class,
                () -> batch.relativePaths().add(
                        "SCHEMA/app/TABLE/third.sql"));
    }

    @Test
    void successfulSinglePublicationAdvancesIndexedAnchor() {
        var continuity = new ProjectBuildContinuity();
        BuildContinuityProof reconciliation =
                continuity.beginReconciliation(1);
        assertTrue(continuity.accept(reconciliation));
        BuildContinuityProof first =
                continuity.beginSingleFile(2, FIRST_PATH);
        assertTrue(continuity.accept(first));

        assertFalse(first.permitsIncremental());
        assertFalse(continuity.accept(first));

        BuildContinuityProof second =
                continuity.beginSingleFile(3, SECOND_PATH);
        assertTrue(second.permitsIncremental());
        assertTrue(second.descendsFrom(first));
        assertFalse(second.descendsFrom(reconciliation));
    }

    @Test
    void noOpBuildsPreserveParserAnchorAcrossBuilderGenerations() {
        var continuity = new ProjectBuildContinuity();
        BuildContinuityProof reconciliation =
                continuity.beginReconciliation(10);
        assertTrue(continuity.accept(reconciliation));

        BuildContinuityProof firstNoOp =
                continuity.beginNoOp(11);
        assertEquals(Kind.NO_OP, firstNoOp.kind());
        assertNull(firstNoOp.relativePath());
        assertTrue(continuity.accept(firstNoOp));

        BuildContinuityProof secondNoOp =
                continuity.beginNoOp(12);
        assertTrue(continuity.accept(secondNoOp));

        BuildContinuityProof single =
                continuity.beginSingleFile(13, FIRST_PATH);
        assertTrue(single.permitsIncremental());
        assertTrue(single.descendsFrom(reconciliation));
    }

    @Test
    void generationGapRevokesLineageAndForcesReconciliation() {
        var continuity = new ProjectBuildContinuity();
        BuildContinuityProof reconciliation =
                continuity.beginReconciliation(1);
        assertTrue(continuity.accept(reconciliation));

        BuildContinuityProof afterGap =
                continuity.beginSingleFile(3, FIRST_PATH);

        assertEquals(Kind.RECONCILIATION, afterGap.kind());
        assertFalse(afterGap.permitsIncremental());
        assertFalse(afterGap.descendsFrom(reconciliation));
    }

    @Test
    void staleProofAndPathMismatchFailClosed() {
        var continuity = new ProjectBuildContinuity();
        BuildContinuityProof reconciliation =
                continuity.beginReconciliation(1);
        assertTrue(continuity.accept(reconciliation));
        BuildContinuityProof incremental =
                continuity.beginIncremental(2,
                        List.of(FIRST_PATH, SECOND_PATH));

        assertFalse(incremental.permitsIncremental(
                List.of(FIRST_PATH)));

        BuildContinuityProof newer =
                continuity.beginReconciliation(3);
        assertFalse(incremental.permitsIncremental(
                List.of(FIRST_PATH, SECOND_PATH)));
        assertEquals(Kind.RECONCILIATION, newer.kind());
    }

    @Test
    void failedFullBuildLeavesPreviousLineageRevoked() {
        var continuity = new ProjectBuildContinuity();
        BuildContinuityProof initial =
                continuity.beginReconciliation(1);
        assertTrue(continuity.accept(initial));

        BuildContinuityProof failedFull =
                continuity.beginReconciliation(2);
        assertTrue(continuity.invalidate(failedFull));

        BuildContinuityProof next =
                continuity.beginSingleFile(3, FIRST_PATH);
        assertEquals(Kind.RECONCILIATION, next.kind());
        assertFalse(next.descendsFrom(initial));
    }

    /**
     * Pins where a full build loses the lineage. A reconciliation drops it the
     * moment it starts, long before anyone learns how it ended, so no
     * relaxation on the failure path can hand that lineage back. An
     * incremental generation is the opposite: its lineage is still alive while
     * it runs, and only the failure handler takes it away.
     */
    @Test
    void reconciliationDropsTheLineageBeforeItsOutcomeIsKnown() {
        var incremental = new ProjectBuildContinuity();
        BuildContinuityProof incrementalAnchor =
                incremental.beginReconciliation(1);
        assertTrue(incremental.accept(incrementalAnchor));
        BuildContinuityProof racedIncremental =
                incremental.beginIncremental(2, List.of(FIRST_PATH));
        assertTrue(racedIncremental.descendsFrom(
                incrementalAnchor));

        var full = new ProjectBuildContinuity();
        BuildContinuityProof fullAnchor =
                full.beginReconciliation(1);
        assertTrue(full.accept(fullAnchor));
        BuildContinuityProof racedFull =
                full.beginReconciliation(2);
        assertEquals(Kind.RECONCILIATION, racedFull.kind());
        assertFalse(racedFull.descendsFrom(fullAnchor));
    }

    @Test
    void failedSingleBuildRevokesLineage() {
        var continuity = new ProjectBuildContinuity();
        BuildContinuityProof reconciliation =
                continuity.beginReconciliation(1);
        assertTrue(continuity.accept(reconciliation));
        BuildContinuityProof failed =
                continuity.beginSingleFile(2, FIRST_PATH);

        assertTrue(continuity.invalidate(failed));

        BuildContinuityProof next =
                continuity.beginSingleFile(3, SECOND_PATH);
        assertEquals(Kind.RECONCILIATION, next.kind());
        assertFalse(next.descendsFrom(reconciliation));
    }

    @Test
    void staleCancellationCannotInvalidateNewerAcceptedLineage() {
        var continuity = new ProjectBuildContinuity();
        BuildContinuityProof initial =
                continuity.beginReconciliation(1);
        BuildContinuityProof newer =
                continuity.beginReconciliation(2);
        assertTrue(continuity.accept(newer));

        assertFalse(continuity.invalidate(initial));

        BuildContinuityProof single =
                continuity.beginSingleFile(3, FIRST_PATH);
        assertTrue(single.permitsIncremental());
        assertTrue(single.descendsFrom(newer));
    }

    @Test
    void failedNoOpRevokesLineage() {
        var continuity = new ProjectBuildContinuity();
        BuildContinuityProof reconciliation =
                continuity.beginReconciliation(1);
        assertTrue(continuity.accept(reconciliation));
        BuildContinuityProof failedNoOp =
                continuity.beginNoOp(2);

        assertTrue(continuity.invalidate(failedNoOp));

        BuildContinuityProof next =
                continuity.beginSingleFile(3, FIRST_PATH);
        assertEquals(Kind.RECONCILIATION, next.kind());
    }

    @Test
    void proofsFromAnotherBuilderInstanceNeverShareLineage() {
        var firstOwner = new ProjectBuildContinuity();
        BuildContinuityProof first =
                firstOwner.beginReconciliation(1);
        assertTrue(firstOwner.accept(first));

        var secondOwner = new ProjectBuildContinuity();
        BuildContinuityProof second =
                secondOwner.beginReconciliation(1);
        assertTrue(secondOwner.accept(second));
        BuildContinuityProof secondSingle =
                secondOwner.beginSingleFile(2, FIRST_PATH);

        assertFalse(secondSingle.descendsFrom(first));
        assertTrue(secondSingle.descendsFrom(second));
    }

    @Test
    void generationsAndPathsAreValidatedFailClosed() {
        var continuity = new ProjectBuildContinuity();

        assertThrows(IllegalArgumentException.class,
                () -> continuity.beginReconciliation(0));
        assertThrows(IllegalArgumentException.class,
                () -> continuity.beginSingleFile(1, "")); //$NON-NLS-1$
        assertThrows(IllegalArgumentException.class,
                () -> continuity.beginSingleFile(1, "/absolute.sql")); //$NON-NLS-1$

        BuildContinuityProof first =
                continuity.beginReconciliation(1);
        assertThrows(IllegalArgumentException.class,
                () -> continuity.beginNoOp(1));
        assertTrue(continuity.invalidate(first));
    }
}
