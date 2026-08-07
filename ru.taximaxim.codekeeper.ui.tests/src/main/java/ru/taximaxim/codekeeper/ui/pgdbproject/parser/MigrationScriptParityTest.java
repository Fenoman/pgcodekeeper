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
package ru.taximaxim.codekeeper.ui.pgdbproject.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ru.taximaxim.codekeeper.ui.pgdbproject.parser.MigrationScriptParityTestSupport.PipelineRun;

/**
 * Whether reuse is <i>correct</i>, which is a different question from whether
 * reuse <i>happened</i>.
 * <p>
 * {@code ReusableProjectComparisonTest} asks the second question twenty-five
 * times and never the first: every one of its assertions is about {@code
 * reused()}, {@code accepted()}, a lease identity or a loader-construction
 * count, and none is about what the reused model then produces. A model served
 * from the retained cache or replayed from disk could disagree with a freshly
 * parsed one in any way at all and that suite would stay green, because the
 * only artefact a user ever sees - the migration script - is not built by any
 * test in this plugin. {@code Differ}, the one production class that builds it,
 * is never constructed outside {@code ProjectEditorDiffer}, {@code DiffWizard}
 * and {@code QuickUpdate}, and the two tests that press those buttons are
 * SWTBot tests that do not run headless here.
 * <p>
 * So this class asserts the thing the pipeline exists to guarantee and nobody
 * had written down: <b>a script built through the reuse pipeline is byte for
 * byte the script a full parse of the same project would have built</b>. The
 * shape is {@code PgPipelineParityTest}'s in Core - one fixture, two
 * pipelines, {@code assertEquals} on the SQL - with its non-vacuity guard kept:
 * two identical answers of "nothing" satisfy every equality below without a
 * single object having been compared, so what the script must contain is
 * demanded by name first and only then are the two runs held against each
 * other.
 * <p>
 * <b>On the settings.</b> These runs are configured by {@link
 * MigrationScriptParityTestSupport#scriptSafeSettings}, which differs
 * deliberately from the settings {@code ReusableProjectComparisonTest} uses.
 * That suite sets routine bodies to be both hashed first and skipped when
 * matched, and {@code ProjectEditorDiffer.diff()} refuses to generate a script
 * at all in that configuration - it calls {@code requestFullAnalysisRerun()}
 * instead. Parity asserted there would be parity in the one configuration
 * where the behaviour under test cannot occur.
 */
class MigrationScriptParityTest {

    /**
     * Warm reuse: the retained analyzed model must produce the same script as
     * parsing the project again from its files.
     * <p>
     * Both halves are asserted because either alone is silent. That the second
     * run was actually warm is asserted, or two cold runs would agree trivially
     * and prove nothing about reuse; and that the script says something is
     * asserted before the two are compared, or two empty scripts would agree
     * just as trivially.
     */
    @Test
    void warmReuseBuildsTheSameScriptAsAFullParse(@TempDir Path temp)
            throws Exception {
        var monitor = new NullProgressMonitor();
        try (var fixture = MigrationScriptParityTestSupport.create(
                temp, "warm", monitor)) { //$NON-NLS-1$
            String fullParse = fixture.oracleScript();
            assertScriptIsNotVacuous(fullParse);

            try (var reusable = new ReusableProjectComparison()) {
                PipelineRun cold = fixture.pipelineScript(reusable);
                assertFalse(cold.reused(),
                        "the first comparison of a session has nothing to reuse"); //$NON-NLS-1$
                assertEquals(fullParse, cold.script(),
                        "a cold pipeline run must build the script a full parse builds"); //$NON-NLS-1$

                PipelineRun warm = fixture.pipelineScript(reusable);
                assertTrue(warm.reused(),
                        "the second comparison must reuse the retained model, or this " //$NON-NLS-1$
                                + "test compares two cold runs and asserts nothing about reuse"); //$NON-NLS-1$
                assertEquals(fullParse, warm.script(),
                        "a script must not depend on whether the analyzed model was reused"); //$NON-NLS-1$
            }
        }
    }

    /**
     * The reuse pipeline must answer for the project as it is now, not for the
     * generation it happens to be holding.
     * <p>
     * This is the failure a cache actually has: it keeps serving what it
     * retained. The edit below is chosen so that a stale answer is visible
     * rather than merely unequal - the new column appears in the table and in
     * the view that reads it, so its absence from the script names the defect.
     * Which branch serves the second run is deliberately not asserted: the
     * pipeline is free to reuse, to reject and reload, or to replay, and it is
     * the answer that must be right whichever it chooses.
     */
    @Test
    void anEditedProjectYieldsTheScriptOfItsNewContent(@TempDir Path temp)
            throws Exception {
        var monitor = new NullProgressMonitor();
        try (var fixture = MigrationScriptParityTestSupport.create(
                temp, "edited", monitor)) { //$NON-NLS-1$
            try (var reusable = new ReusableProjectComparison()) {
                String beforeEdit = fixture.oracleScript();
                assertScriptIsNotVacuous(beforeEdit);
                PipelineRun cold = fixture.pipelineScript(reusable);
                assertFalse(cold.reused());
                assertEquals(beforeEdit, cold.script());

                fixture.edit(MigrationScriptParityTestSupport.TABLE_PATH, """
                        CREATE TABLE app.item (
                            id bigint,
                            code text,
                            note text
                        );

                        GRANT SELECT ON TABLE app.item TO PUBLIC;
                        """);
                fixture.edit(MigrationScriptParityTestSupport.VIEW_PATH, """
                        CREATE VIEW app.pick AS
                            SELECT id, code, note FROM app.item;
                        """);

                String afterEdit = fixture.oracleScript();
                assertScriptIsNotVacuous(afterEdit);
                assertNotEquals(beforeEdit, afterEdit,
                        "the edit must change the script, or this test cannot tell a " //$NON-NLS-1$
                                + "stale answer from a current one"); //$NON-NLS-1$

                PipelineRun afterwards = fixture.pipelineScript(reusable);
                assertTrue(afterwards.script().contains("note"), //$NON-NLS-1$
                        () -> "the script must carry the edited column; without it the " //$NON-NLS-1$
                                + "pipeline answered for the project it was holding, not " //$NON-NLS-1$
                                + "the one on disk: " + afterwards.script()); //$NON-NLS-1$
                assertEquals(afterEdit, afterwards.script(),
                        "after an edit the pipeline must build the script a full parse " //$NON-NLS-1$
                                + "of the edited project builds"); //$NON-NLS-1$
            }
        }
    }

    /**
     * Disk reuse: an analysis result replayed from the persistent store must
     * produce the same script as running the analysis again.
     * <p>
     * This is the half with no coverage at all today - {@code
     * ProjectAnalysisCache}, the {@code HIT_DISK} status and {@code
     * analysisReplayed()} are not named by any test in this plugin, and {@code
     * ProjectAnalysisStoreTest} exercises the store in isolation rather than
     * the comparison that replays it. A fresh {@link ReusableProjectComparison}
     * is what a restarted workbench brings: the in-memory cache is empty, the
     * store on disk is not.
     */
    @Test
    void aReplayedAnalysisBuildsTheSameScript(@TempDir Path temp)
            throws Exception {
        var monitor = new NullProgressMonitor();
        try (var fixture = MigrationScriptParityTestSupport.create(
                temp, "replayed", monitor)) { //$NON-NLS-1$
            String fullParse = fixture.oracleScript();
            assertScriptIsNotVacuous(fullParse);

            try (var seeding = new ReusableProjectComparison()) {
                PipelineRun cold = fixture.pipelineScript(seeding);
                assertFalse(cold.reused());
                assertFalse(cold.analysisReplayed(),
                        "there is nothing on disk to replay for a first-ever run"); //$NON-NLS-1$
            }
            fixture.awaitPersistedAnalysis();

            try (var restarted = new ReusableProjectComparison()) {
                PipelineRun replayed = fixture.pipelineScript(restarted);
                assertTrue(replayed.analysisReplayed(),
                        "a new comparison must find the stored analysis, or this test " //$NON-NLS-1$
                                + "compares two cold runs and asserts nothing about replay"); //$NON-NLS-1$
                assertEquals(fullParse, replayed.script(),
                        "a script must not depend on whether the analysis was replayed " //$NON-NLS-1$
                                + "from disk or computed again"); //$NON-NLS-1$
            }
        }
    }

    /**
     * What the fixture's migration must say, demanded by name.
     * <p>
     * An equality of two scripts proves they agree; it does not prove either of
     * them describes a migration. Every element below is one a defective reuse
     * could drop on its own: the privilege is carried only when privileges are
     * compared, the added column and the view that reads it are ordered only by
     * a carried dependency, and the routine is rendered only when its body
     * survived the analysis. The ordering assertion is the one that a lost
     * dependency edge breaks without dropping anything.
     */
    private static void assertScriptIsNotVacuous(String script) {
        assertFalse(script.isBlank(),
                "the fixture must produce a migration at all"); //$NON-NLS-1$
        assertTrue(script.contains("GRANT SELECT ON TABLE app.item TO PUBLIC;"), //$NON-NLS-1$
                () -> "the migration must carry the table privilege: " + script); //$NON-NLS-1$
        assertTrue(script.contains("ADD COLUMN code text"), //$NON-NLS-1$
                () -> "the migration must add the column the dump lacks: " + script); //$NON-NLS-1$
        assertTrue(script.contains("CREATE VIEW app.pick"), //$NON-NLS-1$
                () -> "the migration must create the view: " + script); //$NON-NLS-1$
        assertTrue(script.contains("CREATE OR REPLACE FUNCTION app.count_items()"), //$NON-NLS-1$
                () -> "the migration must create the routine: " + script); //$NON-NLS-1$
        assertTrue(script.indexOf("ADD COLUMN code text") //$NON-NLS-1$
                < script.indexOf("CREATE VIEW app.pick"), //$NON-NLS-1$
                () -> "the column must be added before the view that selects it, which " //$NON-NLS-1$
                        + "only a carried dependency can order: " + script); //$NON-NLS-1$
    }
}
