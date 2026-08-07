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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import ru.taximaxim.codekeeper.ui.DatabaseType;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetry.BypassReason;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetry.Mode;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetry.RepairRefusal;

/**
 * The three rules this policy states: a project that only receives changes
 * from a database has no background index at all, every other project type
 * has one, and only PostgreSQL may carry a whole analyzed model from one
 * comparison to the next.
 */
class ProjectIndexSupportPolicyTest {

    /** A token is a token: no path, no space, nothing to leak. */
    private static final Pattern TOKEN = Pattern.compile("[a-z0-9_]+");

    /** Records whether the layout question was ever put. */
    private static final class Layout implements BooleanSupplier {

        private final boolean answer;
        private int asked;

        private Layout(boolean answer) {
            this.answer = answer;
        }

        @Override
        public boolean getAsBoolean() {
            asked++;
            return answer;
        }
    }

    @Test
    void everyProjectTypeHasABackgroundIndex() {
        // Measured, not assumed: a MS SQL and a ClickHouse project build,
        // restore and repair an index that holds what a full load holds. See
        // ProjectIndexOtherDialectsTest.
        for (DatabaseType databaseType : DatabaseType.values()) {
            var supported = new Layout(true);
            assertNull(ProjectIndexSupportPolicy.refusal(false, supported),
                    databaseType + " has an index when its layout allows one");
            assertEquals(1, supported.asked);
        }
    }

    @Test
    void aProjectIsJudgedByItsLayoutAlone() {
        var supported = new Layout(true);
        assertNull(ProjectIndexSupportPolicy.refusal(false, supported),
                "an indexable layout has an index");
        assertEquals(1, supported.asked);

        var unsupported = new Layout(false);
        assertEquals(BypassReason.UNSUPPORTED_PROJECT_LAYOUT,
                ProjectIndexSupportPolicy.refusal(false, unsupported));
        assertEquals(1, unsupported.asked);
    }

    @Test
    void receiveOnlyModeIsAskedBeforeTheLayoutEverPaysForAFileRead() {
        // An indexable layout says nothing once the mode is on: the project
        // still has no index, and the layout question - which reads a file -
        // is never even put to it.
        var indexable = new Layout(true);
        assertEquals(BypassReason.DISABLED_BY_PREFERENCE,
                ProjectIndexSupportPolicy.refusal(true, indexable),
                "a receive-only project has no index regardless of its layout");
        assertEquals(0, indexable.asked,
                "the mode must decide before the layout ever reads a file");

        // Nor does an unsupported layout change the reason: the mode is the
        // whole of the answer, so the same refusal comes back either way.
        var unsupported = new Layout(false);
        assertEquals(BypassReason.DISABLED_BY_PREFERENCE,
                ProjectIndexSupportPolicy.refusal(true, unsupported));
        assertEquals(0, unsupported.asked);
    }

    @Test
    void onlyPostgresMayReuseAWholeAnalyzedModel() {
        assertTrue(ProjectIndexSupportPolicy
                .supportsReusableComparisonModel(DatabaseType.PG));
        assertFalse(ProjectIndexSupportPolicy
                .supportsReusableComparisonModel(DatabaseType.MS));
        assertFalse(ProjectIndexSupportPolicy
                .supportsReusableComparisonModel(DatabaseType.CH));
        // The loader that replays a stored analysis exists for one dialect, so
        // a type added tomorrow is refused until one is written for it.
        assertEquals(1, Arrays.stream(DatabaseType.values())
                .filter(ProjectIndexSupportPolicy
                        ::supportsReusableComparisonModel)
                .count(),
                "exactly one database type may reuse an analyzed model");
    }

    @Test
    void bothDiagnosticFieldsNameTheRefusalAndKeepEveryTokenApart() {
        assertEquals("pgCodeKeeper project index: mode=bypass "
                + "bypass_reason=unsupported_project_layout" + tail(),
                publishedLine(run -> run.bypass(
                        BypassReason.UNSUPPORTED_PROJECT_LAYOUT)));
        assertTrue(publishedLine(run -> run.repairRefused(
                RepairRefusal.BATCH_LOAD_FAILURE))
                .contains(" repair=refused "
                        + "repair_reason=batch_load_failure "),
                "a refused batch must name what refused it");

        var tokens = new HashSet<String>();
        for (BypassReason reason : BypassReason.values()) {
            String token = bypassToken(
                    publishedLine(run -> run.bypass(reason)));
            assertTrue(TOKEN.matcher(token).matches(),
                    reason + " published " + token
                            + ", which is not a bare token");
            assertTrue(tokens.add(token),
                    reason + " published " + token
                            + ", which another bypass already publishes");
        }
        assertEquals(BypassReason.values().length, tokens.size());
    }

    private static String tail() {
        return " persistence_status=not_attempted persistence_reason=none"
                + " paths_enumerated=0 enumeration_passes=0"
                + " single_file_validations=0 paths_hashed=0"
                + " paths_hash_inline=0 paths_hash_reread=0"
                + " paths_parsed=unknown paths_analyzed=unknown"
                + " elapsed_ms=0";
    }

    private static String bypassToken(String line) {
        int start = line.indexOf(" bypass_reason=");
        assertTrue(start >= 0, "no reason in: " + line);
        start += " bypass_reason=".length();
        int end = line.indexOf(' ', start);
        return end < 0 ? line.substring(start) : line.substring(start, end);
    }

    private static String publishedLine(
            java.util.function.Consumer<ProjectIndexTelemetry.Run> refusal) {
        List<String> lines = new ArrayList<>();
        var run = new ProjectIndexTelemetry(lines::add, () -> 0)
                .start(Mode.COLD);

        refusal.accept(run);
        run.close();

        assertEquals(1, lines.size());
        return lines.getFirst();
    }
}
