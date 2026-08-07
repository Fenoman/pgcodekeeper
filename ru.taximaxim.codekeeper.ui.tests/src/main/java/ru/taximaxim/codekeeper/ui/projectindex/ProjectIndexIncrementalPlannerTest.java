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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ProjectIndexIncrementalPlannerTest {

    @Test
    void acceptsBodyReferenceAndOrderChangesWithStableDefinitions()
            throws Exception {
        ProjectIndexData data = ProjectIndexFixtures.snapshotWithAllMetaKinds();
        FileContribution previous = data.files().get(1);
        var reversedLocations = new ArrayList<>(previous.locations());
        java.util.Collections.reverse(reversedLocations);
        FileContribution replacement = new FileContribution(previous.path(),
                previous.definitions().reversed(), reversedLocations,
                java.util.Set.of(), !previous.unresolvedAny());

        assertTrue(ProjectIndexIncrementalPlanner.isSafeReplacement(
                previous.path(), previous.definitions(), replacement));
        assertTrue(ProjectIndexIncrementalPlanner.isSafeReplacement(
                previous, replacement));
    }

    @Test
    void rejectsSignatureAddDeleteAndRename() throws Exception {
        FileContribution previous = ProjectIndexFixtures
                .snapshotWithAllMetaKinds().files().get(1);
        PackedDefinition definition = previous.definitions().getFirst();
        FileContribution changedSignature = replacement(previous,
                List.of(definition.withReturns("jsonb")));
        FileContribution addedDefinition = replacement(previous,
                List.of(definition, definition));
        FileContribution deletedDefinition = replacement(previous, List.of());
        FileContribution renamed = new FileContribution(
                ProjectIndexFixtures.path("schema/renamed.sql"),
                previous.definitions().stream()
                        .map(value -> value.withObject(new PackedLocation(
                                IndexPathOrigin.PROJECT, "schema/renamed.sql",
                                value.object().offset(),
                                value.object().lineNumber(),
                                value.object().charPositionInLine(),
                                value.object().length(),
                                value.object().reference(),
                                value.object().action(),
                                value.object().alias(),
                                value.object().locationType(),
                                value.object().danger())))
                        .toList(),
                List.of(), java.util.Set.of(), true);

        assertFalse(ProjectIndexIncrementalPlanner.isSafeReplacement(
                previous.path(), previous.definitions(), changedSignature));
        assertFalse(ProjectIndexIncrementalPlanner.isSafeReplacement(
                previous.path(), previous.definitions(), addedDefinition));
        assertFalse(ProjectIndexIncrementalPlanner.isSafeReplacement(
                previous.path(), previous.definitions(), deletedDefinition));
        assertFalse(ProjectIndexIncrementalPlanner.isSafeReplacement(
                previous.path(), previous.definitions(), renamed));
    }

    @Test
    void acceptsOnlyWholeShapePreservingReplacementBatch()
            throws Exception {
        List<FileContribution> files = ProjectIndexFixtures
                .snapshotWithAllMetaKinds().files().subList(0, 2);
        FileContribution first = files.get(0);
        FileContribution second = files.get(1);
        FileContribution firstReplacement = replacement(
                first, first.definitions().reversed());
        FileContribution secondReplacement = replacement(
                second, second.definitions().reversed());
        ProjectFileStamp firstStamp = new ProjectFileStamp(
                first.path(), 2L, 10L, 2L, new byte[32]);
        ProjectFileStamp secondStamp = new ProjectFileStamp(
                second.path(), 2L, 10L, 2L, new byte[32]);
        var safe = new ProjectIndexDelta(List.of(
                ProjectIndexDelta.Change.replace(
                        firstStamp, firstReplacement),
                ProjectIndexDelta.Change.replace(
                        secondStamp, secondReplacement)));
        Map<IndexPathRef, List<PackedDefinition>> previous = Map.of(
                first.path(), first.definitions(),
                second.path(), second.definitions());

        assertTrue(ProjectIndexIncrementalPlanner
                .areSafeReplacements(previous, safe));

        PackedDefinition changed =
                second.definitions().getFirst().withReturns("jsonb");
        var unsafe = new ProjectIndexDelta(List.of(
                safe.changes().getFirst(),
                ProjectIndexDelta.Change.replace(
                        secondStamp,
                        replacement(second, List.of(changed)))));
        assertFalse(ProjectIndexIncrementalPlanner
                .areSafeReplacements(previous, unsafe));
        assertFalse(ProjectIndexIncrementalPlanner
                .areSafeReplacements(
                        Map.of(first.path(), first.definitions()),
                        safe));
    }

    private static FileContribution replacement(FileContribution previous,
            List<PackedDefinition> definitions) {
        return new FileContribution(previous.path(), definitions,
                previous.locations(), previous.unresolvedCandidates(),
                previous.unresolvedAny());
    }
}
