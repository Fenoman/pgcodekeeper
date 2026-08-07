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
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import ru.taximaxim.codekeeper.ui.UIConsts.PREF;
import ru.taximaxim.codekeeper.ui.UIConsts.PROJ_PREF;
import ru.taximaxim.codekeeper.ui.prefs.Preferences.ChangeImpact;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexConfigurationDiagnostics.ParserPresence;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexConfigurationDiagnostics.PreferenceNode;

class ProjectIndexConfigurationDiagnosticsTest {

    private final List<String> published = new ArrayList<>();
    private final ProjectIndexConfigurationDiagnostics diagnostics =
            new ProjectIndexConfigurationDiagnostics(published::add);

    /**
     * The line a workspace refresh is expected to produce while the cause is
     * still unknown: the project's own preference file re-applied key by key,
     * from inside the resource refresh that runs on the UI thread.
     */
    @Test
    void describesAPreferenceChangeWhole() {
        assertEquals("pgCodeKeeper preference change:"
                + " key=projectIndexIncrementalAddedFiles"
                + " impact=project_index node=project ui_thread=true",
                diagnostics.formatPreferenceChange(
                        PREF.PROJECT_INDEX_INCREMENTAL_ADDED_FILES,
                        ChangeImpact.PROJECT_INDEX,
                        PreferenceNode.PROJECT, true));
    }

    /**
     * The measurement itself: {@code invalidated} is what the fingerprint guard
     * returned, so {@code false} says the configuration was identical and the
     * persisted index survived the change.
     */
    @Test
    void describesAnInvalidationWhole() {
        assertEquals("pgCodeKeeper project index configuration:"
                + " invalidated=true parser=live origin=editor_preference",
                diagnostics.formatInvalidation(true, ParserPresence.LIVE,
                        ProjectIndexConfigurationDiagnostics
                                .ORIGIN_EDITOR_PREFERENCE));
        assertEquals("pgCodeKeeper project index configuration:"
                + " invalidated=false parser=absent origin=unknown",
                diagnostics.formatInvalidation(false, ParserPresence.ABSENT,
                        ProjectIndexConfigurationDiagnostics.ORIGIN_UNKNOWN));
    }

    /**
     * The state that appears between the other two lines once the editor stops
     * acting on the instant a change arrives at. Counting deferrals against
     * invalidations is how much a burst was collapsed by.
     */
    @Test
    void describesADeferralWhole() {
        assertEquals("pgCodeKeeper project index configuration deferred:"
                + " delay_ms=750 origin=editor_preference",
                diagnostics.formatDeferral(
                        ProjectIndexConfigurationDiagnostics
                                .ORIGIN_EDITOR_PREFERENCE, 750));
        assertEquals(List.of("delay_ms", "origin"),
                fieldNames(diagnostics.formatDeferral(
                        ProjectIndexConfigurationDiagnostics
                                .ORIGIN_PROJECT_PROPERTIES, 0)));
    }

    /**
     * The read line separates the two ways an exclusion list arrives empty.
     *
     * <p>A build that enumerated 22 386 paths where 11 693 were expected has
     * either ignored the project overrides or been handed none, and the count
     * alone cannot say which. {@code proj_pref_root} answers the first,
     * {@code project_excluded} the second: {@code unset} means the project
     * named nothing, {@code none} means it named an empty list.</p>
     */
    @Test
    void describesAConfigurationReadWhole() {
        assertEquals("pgCodeKeeper project index configuration read:"
                + " proj_pref_root=true global_excluded=none"
                + " project_excluded=2 effective_excluded=2"
                + " origin=effective_configuration",
                diagnostics.formatConfigurationRead(true, "",
                        "dummy_tmp\ntmp", "dummy_tmp\ntmp",
                        "effective_configuration"));
        assertEquals(List.of("proj_pref_root", "global_excluded",
                "project_excluded", "effective_excluded", "origin"),
                fieldNames(diagnostics.formatConfigurationRead(false, "", null,
                        "", "effective_configuration")));
    }

    /**
     * A project that named nothing reads differently from one that named an
     * empty list, and neither is mistaken for a list of one blank line.
     */
    @Test
    void tellsAnUnsetListFromAnEmptyOne() {
        assertTrue(diagnostics.formatConfigurationRead(true, "", null, "",
                "read").contains("project_excluded=unset"));
        assertTrue(diagnostics.formatConfigurationRead(true, "", "", "",
                "read").contains("project_excluded=none"));
        assertTrue(diagnostics.formatConfigurationRead(true, "", "  \n # note\n",
                "", "read").contains("project_excluded=none"));
        assertTrue(diagnostics.formatConfigurationRead(true, "audit", "tmp",
                "tmp", "read").contains("global_excluded=1"));
    }

    @Test
    void publishSurvivesAMalformedDeferral() {
        diagnostics.publishDeferral(null, 750);

        assertEquals(List.of(), published);

        diagnostics.publishDeferral(ProjectIndexConfigurationDiagnostics
                .ORIGIN_EDITOR_PREFERENCE, 750);

        assertEquals(List.of(diagnostics.formatDeferral(
                ProjectIndexConfigurationDiagnostics
                        .ORIGIN_EDITOR_PREFERENCE, 750)), published);
    }

    @Test
    void namesEveryImpactTheRoutingTableCanReport() {
        assertEquals("none", impactOf(ChangeImpact.NONE));
        assertEquals("comparison", impactOf(ChangeImpact.COMPARISON));
        assertEquals("project_index", impactOf(ChangeImpact.PROJECT_INDEX));
        assertEquals("both", impactOf(ChangeImpact.BOTH));
    }

    /**
     * One re-read of the project preference file routes every key it holds. If
     * the ones nothing depends on were reported too, they would push everything
     * else out of the bounded telemetry buffer, which is the only place this
     * evidence survives.
     */
    @Test
    void reportsOnlyThePreferencesSomethingDependsOn() {
        diagnostics.publishPreferenceChange(PREF.IGNORE_COLUMN_ORDER,
                ChangeImpact.NONE, PreferenceNode.PROJECT, true);

        assertEquals(List.of(), published);

        diagnostics.publishPreferenceChange(PREF.NO_PRIVILEGES,
                ChangeImpact.BOTH, PreferenceNode.PROJECT, true);
        diagnostics.publishPreferenceChange(PROJ_PREF.ENABLE_PROJ_PREF_ROOT,
                ChangeImpact.COMPARISON, PreferenceNode.MAIN, false);
        diagnostics.publishPreferenceChange(
                PREF.PROJECT_INDEX_EXCLUDED_SCHEMAS,
                ChangeImpact.PROJECT_INDEX, PreferenceNode.PROJECT, false);

        assertEquals(3, published.size(), published.toString());
    }

    @Test
    void tellsTheWorkspaceNodeApartFromTheProjectFile() {
        assertTrue(diagnostics.formatPreferenceChange(PREF.NO_PRIVILEGES,
                ChangeImpact.BOTH, PreferenceNode.MAIN, true)
                .endsWith(" node=main ui_thread=true"));
        assertTrue(diagnostics.formatPreferenceChange(PREF.NO_PRIVILEGES,
                ChangeImpact.BOTH, PreferenceNode.PROJECT, false)
                .endsWith(" node=project ui_thread=false"));
    }

    @Test
    void tellsALiveParserApartFromAnAbsentOne() {
        assertTrue(diagnostics.formatInvalidation(true, ParserPresence.LIVE,
                ProjectIndexConfigurationDiagnostics.ORIGIN_UNKNOWN)
                .contains(" parser=live "));
        assertTrue(diagnostics.formatInvalidation(true, ParserPresence.ABSENT,
                ProjectIndexConfigurationDiagnostics.ORIGIN_UNKNOWN)
                .contains(" parser=absent "));
    }

    /**
     * A preference key is read out of a file the project ships, so it must not
     * be able to forge a field, break a line or grow one without bound.
     */
    @Test
    void reducesAKeyToCharactersThatCannotBreakTheLine() {
        assertEquals("key=a_b_c_d", keyOf("a b\nc\"d"));
        assertEquals("key=projectIndexExcludedSchemas",
                keyOf("projectIndexExcludedSchemas"));
        assertEquals("key=" + "a".repeat(64), keyOf("a".repeat(100)));
        assertEquals("key=" + "_".repeat(64), keyOf(" ".repeat(100)));
    }

    @Test
    void reducesAnOriginToCharactersThatCannotBreakTheLine() {
        assertEquals("origin=a_b_c_d", originOf("a b\nc\"d"));
        assertEquals("origin=global_preferences",
                originOf("global_preferences"));
        assertEquals("origin=" + "a".repeat(64), originOf("a".repeat(100)));
        assertEquals("origin=" + "_".repeat(64), originOf(" ".repeat(100)));
    }

    /**
     * Both lines land in a log that is read by people who did not write the
     * project. A preference value or a path is not needed to answer who
     * retired the index, so neither is allowed to appear.
     */
    @Test
    void neitherLineCarriesAValueOrAPath() {
        assertEquals(List.of("key", "impact", "node", "ui_thread"),
                fieldNames(diagnostics.formatPreferenceChange(
                        PREF.PROJECT_INDEX_EXCLUDED_SCHEMAS,
                        ChangeImpact.PROJECT_INDEX,
                        PreferenceNode.PROJECT, true)));
        assertEquals(List.of("invalidated", "parser", "origin"),
                fieldNames(diagnostics.formatInvalidation(true,
                        ParserPresence.LIVE,
                        ProjectIndexConfigurationDiagnostics
                                .ORIGIN_PROJECT_PROPERTIES)));
    }

    /**
     * A sink that fails may not fail a preference change: the report is the
     * only thing lost.
     */
    @Test
    void publishSurvivesASinkThatThrows() {
        var calls = new ArrayList<String>();
        var failing = new ProjectIndexConfigurationDiagnostics(line -> {
            calls.add(line);
            throw new IllegalStateException("sink is down");
        });

        failing.publishPreferenceChange(PREF.NO_PRIVILEGES,
                ChangeImpact.BOTH, PreferenceNode.PROJECT, true);
        failing.publishInvalidation(false, ParserPresence.LIVE,
                ProjectIndexConfigurationDiagnostics.ORIGIN_UNKNOWN);

        assertEquals(2, calls.size());
        assertTrue(calls.getFirst().contains("impact=both"), calls.getFirst());
        assertTrue(calls.getLast().contains("invalidated=false"),
                calls.getLast());
    }

    /**
     * Both lines are built from values handed in by a listener, so a malformed
     * call must be swallowed exactly like a failing sink.
     */
    @Test
    void publishSurvivesAMalformedCall() {
        diagnostics.publishPreferenceChange(null, ChangeImpact.BOTH,
                PreferenceNode.PROJECT, true);
        diagnostics.publishPreferenceChange(PREF.NO_PRIVILEGES, null,
                PreferenceNode.PROJECT, true);
        diagnostics.publishPreferenceChange(PREF.NO_PRIVILEGES,
                ChangeImpact.BOTH, null, true);
        diagnostics.publishInvalidation(true, null,
                ProjectIndexConfigurationDiagnostics.ORIGIN_UNKNOWN);
        diagnostics.publishInvalidation(true, ParserPresence.LIVE, null);

        assertEquals(List.of(), published);
    }

    @Test
    void publishHandsTheFormattedLineToTheSink() {
        diagnostics.publishPreferenceChange(
                PREF.PROJECT_INDEX_INCREMENTAL_ADDED_FILES,
                ChangeImpact.PROJECT_INDEX, PreferenceNode.PROJECT, true);
        diagnostics.publishInvalidation(true, ParserPresence.LIVE,
                ProjectIndexConfigurationDiagnostics
                        .ORIGIN_GLOBAL_PREFERENCES);

        assertEquals(List.of(
                diagnostics.formatPreferenceChange(
                        PREF.PROJECT_INDEX_INCREMENTAL_ADDED_FILES,
                        ChangeImpact.PROJECT_INDEX,
                        PreferenceNode.PROJECT, true),
                diagnostics.formatInvalidation(true, ParserPresence.LIVE,
                        ProjectIndexConfigurationDiagnostics
                                .ORIGIN_GLOBAL_PREFERENCES)),
                published);
    }

    private String impactOf(ChangeImpact impact) {
        String line = diagnostics.formatPreferenceChange(
                PREF.NO_PRIVILEGES, impact, PreferenceNode.PROJECT, true);
        int start = line.indexOf("impact=") + "impact=".length();
        return line.substring(start, line.indexOf(' ', start));
    }

    private String keyOf(String key) {
        String line = diagnostics.formatPreferenceChange(key,
                ChangeImpact.PROJECT_INDEX, PreferenceNode.PROJECT, true);
        int start = line.indexOf("key=");
        return line.substring(start, line.indexOf(" impact=", start));
    }

    private String originOf(String origin) {
        String line = diagnostics.formatInvalidation(true,
                ParserPresence.LIVE, origin);
        return line.substring(line.indexOf("origin="));
    }

    private static List<String> fieldNames(String line) {
        String fields = line.substring(line.indexOf(": ") + 2);
        return Arrays.stream(fields.split(" "))
                .map(field -> field.substring(0, field.indexOf('=')))
                .toList();
    }
}
