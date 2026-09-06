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
package ru.taximaxim.codekeeper.ui.editors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.api.ComparisonDepth;

/**
 * {@link ProjectEditorDiffer#getChanges()} decides {@code loadedDepth} - and
 * therefore what {@link ProjectEditorDiffer#diff()}'s guard sees as {@code
 * comparisonDepth} - along a three-way branch that no SWTBot test can reach
 * headless. Both halves of it decide something, and both are extracted so
 * they can be pinned down here without one. A run the reusable pipeline
 * served is displayed at the depth the pipeline prepared, structural runs
 * included, which is {@link ProjectEditorDiffer#reusablePipelineDepth}; a run
 * the plain loader owns is displayed at the depth its loader path can
 * actually deliver, which is {@link ProjectEditorDiffer#plainLoaderDepth}.
 * <p>
 * The guard that reads the answer is pinned down here too. {@link
 * ProjectEditorDiffer#scriptNeedsFullReload} is the whole of what keeps a
 * structurally loaded model out of a migration script, and nothing else claims
 * it: rewriting its condition into one that can never be true at that point -
 * which is what deleting the guard amounts to - leaves forty-one tests across
 * five other classes green.
 */
class ProjectEditorDifferLoadedDepthTest {

    @Test
    void aServedRunIsDisplayedAtTheDepthThePipelinePrepared() {
        assertEquals(ComparisonDepth.STRUCTURAL_ONLY,
                ProjectEditorDiffer.reusablePipelineDepth(
                        ComparisonDepth.STRUCTURAL_ONLY),
                "the pipeline serves structural comparisons too, and the "
                        + "guard downstream has to be told so");
        assertEquals(ComparisonDepth.FULL,
                ProjectEditorDiffer.reusablePipelineDepth(ComparisonDepth.FULL));
    }

    @Test
    void theDepthAwareEntryPointHonorsWhateverWasRequested() {
        assertEquals(ComparisonDepth.FULL,
                ProjectEditorDiffer.plainLoaderDepth(true, ComparisonDepth.FULL));
        assertEquals(ComparisonDepth.STRUCTURAL_ONLY,
                ProjectEditorDiffer.plainLoaderDepth(true, ComparisonDepth.STRUCTURAL_ONLY));
    }

    @Test
    void theLegacyPathIsAlwaysFullRegardlessOfWhatWasRequested() {
        assertEquals(ComparisonDepth.FULL,
                ProjectEditorDiffer.plainLoaderDepth(false, ComparisonDepth.FULL));
        assertEquals(ComparisonDepth.FULL,
                ProjectEditorDiffer.plainLoaderDepth(false, ComparisonDepth.STRUCTURAL_ONLY),
                "the legacy path predates ComparisonDepth and always analyzes both sides");
    }

    /**
     * Both states of the guard, in one place, because only the pair says
     * anything: a guard that always demands a reload is as broken as one that
     * never does - it would send every single Get Changes through a second
     * full comparison before any script at all.
     * <p>
     * What a structural load actually costs a script is measured elsewhere:
     * {@code ReceiveOnlyModeTest.aScriptIsNeverBuiltFromAStructuralLoad}
     * builds one from each depth and shows the two differ.
     */
    @Test
    void aScriptIsBuiltOnlyFromAComparisonThatResolvedItsDependencies() {
        assertFalse(ProjectEditorDiffer.scriptNeedsFullReload(
                ComparisonDepth.FULL),
                "a full comparison is exactly what a script is built from");
        assertTrue(ProjectEditorDiffer.scriptNeedsFullReload(
                ComparisonDepth.STRUCTURAL_ONLY),
                "a model with no dependencies cannot order the statements of "
                        + "a script and must be recomputed first");
    }

    /**
     * The two halves in series, because that is the order they run in: a
     * structural comparison the pipeline served reaches the guard as
     * structural, and the guard turns it away. Either half answering
     * {@link ComparisonDepth#FULL} on its own would put a model with no
     * dependencies in front of the script generator.
     */
    @Test
    void aStructuralRunThePipelineServedStillCannotBuildAScript() {
        assertTrue(ProjectEditorDiffer.scriptNeedsFullReload(
                ProjectEditorDiffer.reusablePipelineDepth(
                        ComparisonDepth.STRUCTURAL_ONLY)));
        assertFalse(ProjectEditorDiffer.scriptNeedsFullReload(
                ProjectEditorDiffer.reusablePipelineDepth(
                        ComparisonDepth.FULL)));
    }

    /**
     * The state neither constant describes. {@code resetRemoteChanged} clears
     * the depth along with the models it belongs to, so a guard reading it
     * then is being asked about a comparison that is not there - and answers
     * the only safe thing.
     */
    @Test
    void anAbsentComparisonIsNotAScriptEither() {
        assertTrue(ProjectEditorDiffer.scriptNeedsFullReload(null),
                "no comparison is loaded at all, so there is nothing to "
                        + "build a script from");
    }
}
