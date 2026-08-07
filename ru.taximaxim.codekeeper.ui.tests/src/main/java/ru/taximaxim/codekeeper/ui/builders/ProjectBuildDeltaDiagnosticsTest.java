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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.junit.jupiter.api.Test;

import ru.taximaxim.codekeeper.ui.builders.ProjectBuildDeltaClassifier.Mode;
import ru.taximaxim.codekeeper.ui.builders.ProjectBuildDeltaClassifier.Reason;
import ru.taximaxim.codekeeper.ui.builders.ProjectBuildDeltaClassifier.Result;
import ru.taximaxim.codekeeper.ui.builders.ProjectBuildDeltaDiagnostics.DeltaPresence;

class ProjectBuildDeltaDiagnosticsTest {

    private final List<String> published = new ArrayList<>();
    private final ProjectBuildDeltaDiagnostics diagnostics =
            new ProjectBuildDeltaDiagnostics(published::add);

    /**
     * The line a pull is expected to produce while the cause is still unknown:
     * a hundred and fifty seven deltas, refused at the fourth of them because
     * nobody recognised its flags.
     */
    @Test
    void describesARefusedDeltaWhole() {
        Result refused = ProjectBuildDeltaClassifier.full(
                Reason.UNKNOWN_FLAGS,
                "entries=157,at=3,flags=0x40000000,segment=.git");

        assertEquals("pgCodeKeeper project build delta:"
                + " build_kind=auto delta_present=true verdict=full"
                + " reason=unknown_flags paths=0"
                + " continuity=reconciliation"
                + " evidence=entries=157,at=3,flags=0x40000000,segment=.git",
                diagnostics.format(IncrementalProjectBuilder.AUTO_BUILD,
                        DeltaPresence.PRESENT, refused, refused,
                        ProjectBuildContinuity.Kind.RECONCILIATION));
    }

    @Test
    void describesANarrowedDeltaWhole() {
        Result narrowed = new Result(Mode.INCREMENTAL,
                List.of("SCHEMA/app/FUNCTION/calculate.sql",
                        "SCHEMA/app/FUNCTION/report.sql"),
                Reason.NONE, "entries=3,candidates=3");

        assertEquals("pgCodeKeeper project build delta:"
                + " build_kind=incremental delta_present=true"
                + " verdict=incremental reason=none paths=2"
                + " continuity=incremental"
                + " evidence=entries=3,candidates=3",
                diagnostics.format(
                        IncrementalProjectBuilder.INCREMENTAL_BUILD,
                        DeltaPresence.PRESENT, narrowed, narrowed,
                        ProjectBuildContinuity.Kind.INCREMENTAL));
    }

    @Test
    void aDirectFullBuildConsultsNoDelta() {
        Result full = ProjectBuildDeltaClassifier.full(
                Reason.DIRECT_FULL_BUILD);

        assertEquals("pgCodeKeeper project build delta:"
                + " build_kind=full delta_present=n_a verdict=full"
                + " reason=direct_full_build paths=0"
                + " continuity=reconciliation",
                diagnostics.format(IncrementalProjectBuilder.FULL_BUILD,
                        DeltaPresence.NOT_CONSULTED, full, full,
                        ProjectBuildContinuity.Kind.RECONCILIATION));
    }

    /**
     * A verdict the classifier narrowed and continuity then widened is the one
     * case where the reason alone misleads, so the line keeps both.
     */
    @Test
    void namesTheClassifierReasonOnlyWhenContinuityOverrodeIt() {
        Result narrowed = new Result(Mode.INCREMENTAL,
                List.of("SCHEMA/app/FUNCTION/calculate.sql"),
                Reason.NONE, "entries=1,candidates=1");
        Result widened = ProjectBuildDeltaClassifier.full(
                Reason.CONTINUITY_RECONCILIATION, "paths=1");

        assertEquals("pgCodeKeeper project build delta:"
                + " build_kind=auto delta_present=true verdict=full"
                + " reason=continuity_reconciliation paths=0"
                + " continuity=reconciliation evidence=paths=1"
                + " classifier_reason=none",
                diagnostics.format(IncrementalProjectBuilder.AUTO_BUILD,
                        DeltaPresence.PRESENT, narrowed, widened,
                        ProjectBuildContinuity.Kind.RECONCILIATION));

        assertFalse(diagnostics.format(
                IncrementalProjectBuilder.AUTO_BUILD,
                DeltaPresence.PRESENT, widened, widened,
                ProjectBuildContinuity.Kind.RECONCILIATION)
                .contains("classifier_reason"));
        assertFalse(diagnostics.format(
                IncrementalProjectBuilder.AUTO_BUILD,
                DeltaPresence.PRESENT, narrowed, narrowed,
                ProjectBuildContinuity.Kind.INCREMENTAL)
                .contains("classifier_reason"));
    }

    /**
     * Every field but {@code classifier_reason} describes the verdict the build
     * actually ran with. Reading any of them off the classifier instead would
     * report a build that never happened.
     */
    @Test
    void reportsTheVerdictTheBuildRanWith() {
        Result narrowed = new Result(Mode.INCREMENTAL,
                List.of("SCHEMA/app/FUNCTION/calculate.sql"),
                Reason.NONE, "entries=1,candidates=1");
        Result widened = ProjectBuildDeltaClassifier.full(
                Reason.CONTINUITY_RECONCILIATION);

        assertEquals("pgCodeKeeper project build delta:"
                + " build_kind=auto delta_present=true verdict=full"
                + " reason=continuity_reconciliation paths=0"
                + " continuity=reconciliation classifier_reason=none",
                diagnostics.format(IncrementalProjectBuilder.AUTO_BUILD,
                        DeltaPresence.PRESENT, narrowed, widened,
                        ProjectBuildContinuity.Kind.RECONCILIATION));
    }

    @Test
    void aMissingDeltaIsToldApartFromOneNeverAskedFor() {
        Result absent = ProjectBuildDeltaClassifier.full(Reason.NO_DELTA);

        assertTrue(diagnostics.format(
                IncrementalProjectBuilder.AUTO_BUILD,
                DeltaPresence.ABSENT, absent, absent,
                ProjectBuildContinuity.Kind.RECONCILIATION)
                .contains(" delta_present=false "));
        assertTrue(diagnostics.format(
                IncrementalProjectBuilder.AUTO_BUILD,
                DeltaPresence.NOT_CONSULTED, absent, absent,
                ProjectBuildContinuity.Kind.RECONCILIATION)
                .contains(" delta_present=n_a "));
    }

    @Test
    void namesEveryBuildKindThePlatformCanAskFor() {
        assertEquals("auto", buildKind(
                IncrementalProjectBuilder.AUTO_BUILD));
        assertEquals("incremental", buildKind(
                IncrementalProjectBuilder.INCREMENTAL_BUILD));
        assertEquals("full", buildKind(
                IncrementalProjectBuilder.FULL_BUILD));
        assertEquals("clean", buildKind(
                IncrementalProjectBuilder.CLEAN_BUILD));
        assertEquals("other_42", buildKind(42));
    }

    /**
     * Evidence is assembled from a project's own paths, so it must not be able
     * to forge a field, break a line or grow one without bound.
     */
    @Test
    void reducesEvidenceToCharactersThatCannotBreakTheLine() {
        assertEquals("evidence=a_b_c_d",
                evidenceOf("a b\nc\"d"));
        assertEquals("evidence=at=1,x-y_z.q/w+e",
                evidenceOf("at=1,x-y_z.q/w+e"));
        assertEquals("evidence=" + "a".repeat(64),
                evidenceOf("a".repeat(100)));
        assertEquals("evidence=" + "_".repeat(64),
                evidenceOf(" ".repeat(100)));
    }

    /**
     * A sink that fails may not fail a build: the report is the only thing
     * lost.
     */
    @Test
    void publishSurvivesASinkThatThrows() {
        var calls = new ArrayList<String>();
        var failing = new ProjectBuildDeltaDiagnostics(line -> {
            calls.add(line);
            throw new IllegalStateException("sink is down");
        });
        Result full = ProjectBuildDeltaClassifier.full(Reason.NO_DELTA);

        failing.publish(IncrementalProjectBuilder.AUTO_BUILD,
                DeltaPresence.ABSENT, full, full,
                ProjectBuildContinuity.Kind.RECONCILIATION);

        assertEquals(1, calls.size());
        assertTrue(calls.getFirst().contains("reason=no_delta"),
                calls.getFirst());
    }

    /**
     * The line is built from the verdict itself, so a malformed call must be
     * swallowed exactly like a failing sink.
     */
    @Test
    void publishSurvivesAMalformedCall() {
        Result full = ProjectBuildDeltaClassifier.full(Reason.NO_DELTA);

        diagnostics.publish(IncrementalProjectBuilder.AUTO_BUILD,
                DeltaPresence.ABSENT, full, full, null);

        assertEquals(List.of(), published);
    }

    @Test
    void publishHandsTheFormattedLineToTheSink() {
        Result full = ProjectBuildDeltaClassifier.full(
                Reason.DIRECT_FULL_BUILD);

        diagnostics.publish(IncrementalProjectBuilder.FULL_BUILD,
                DeltaPresence.NOT_CONSULTED, full, full,
                ProjectBuildContinuity.Kind.RECONCILIATION);

        assertEquals(List.of(diagnostics.format(
                IncrementalProjectBuilder.FULL_BUILD,
                DeltaPresence.NOT_CONSULTED, full, full,
                ProjectBuildContinuity.Kind.RECONCILIATION)),
                published);
    }

    private String buildKind(int kind) {
        Result full = ProjectBuildDeltaClassifier.full(
                Reason.DIRECT_FULL_BUILD);
        String line = diagnostics.format(kind,
                DeltaPresence.NOT_CONSULTED, full, full,
                ProjectBuildContinuity.Kind.RECONCILIATION);
        int start = line.indexOf("build_kind=") + "build_kind=".length();
        return line.substring(start, line.indexOf(' ', start));
    }

    private String evidenceOf(String evidence) {
        Result full = ProjectBuildDeltaClassifier.full(
                Reason.NO_DELTA, evidence);
        String line = diagnostics.format(
                IncrementalProjectBuilder.AUTO_BUILD,
                DeltaPresence.ABSENT, full, full,
                ProjectBuildContinuity.Kind.RECONCILIATION);
        return line.substring(line.indexOf("evidence="));
    }
}
