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
package ru.taximaxim.codekeeper.ui.views;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.api.ComparisonDepth;

/**
 * {@link DepcyGraphView#selectionChanged(org.eclipse.ui.IWorkbenchPart,
 * org.eclipse.jface.viewers.ISelection)} cannot be exercised headless - it
 * needs a live {@code IViewSite} (for the status line) and a real Zest
 * {@code GraphViewer}, neither of which exists without a workbench, and
 * SWTBot cannot open one in this environment. What is pinned down instead is
 * the decision the guard at the top of that method reduces to:
 * {@link DepcyGraphView#isDependencyGraphAvailable(ComparisonDepth)}, the same
 * predicate {@code CommitDialog.isDependencySetComplete} already covers for the
 * project update dialog - this test exists to confirm the delegation itself is
 * wired, not to re-derive the rule a second time.
 */
class DepcyGraphViewTest {

    @Test
    void aStructurallyLoadedComparisonOffersNoGraph() {
        assertFalse(DepcyGraphView.isDependencyGraphAvailable(ComparisonDepth.STRUCTURAL_ONLY),
                "a structural closure is short, not empty, and a drawing of one cannot say so");
    }

    @Test
    void aFullyLoadedComparisonOffersAGraph() {
        assertTrue(DepcyGraphView.isDependencyGraphAvailable(ComparisonDepth.FULL));
    }
}
