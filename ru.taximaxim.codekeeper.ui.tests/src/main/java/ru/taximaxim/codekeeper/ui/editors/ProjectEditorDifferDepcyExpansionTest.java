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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.settings.ISettings;

import ru.taximaxim.codekeeper.ui.TestUiSettings;
import ru.taximaxim.codekeeper.ui.pgdbproject.parser.UIComparisonLoader;

/**
 * The project update never blocks on unanalyzed routine bodies, it only reports
 * a dependency set that may be incomplete because of them. The report follows the
 * truncation flag alone, because that flag is a direct observation of the models
 * actually traversed while the settings are only a proxy for it.
 */
class ProjectEditorDifferDepcyExpansionTest {

    @Test
    void reportedWheneverAClosureWasTruncated() {
        // a direct observation is never overruled by the settings: filtering it
        // through them could only hide a gap that was actually seen
        assertTrue(ProjectEditorDiffer.isDepcyExpansionIncomplete(settings(true, true), true),
                "a closure that reached an unanalyzed routine is incomplete");
        assertTrue(ProjectEditorDiffer.isDepcyExpansionIncomplete(settings(true, false), true),
                "the observation stands even where the settings deny the skip");
        assertTrue(ProjectEditorDiffer.isDepcyExpansionIncomplete(settings(false, true), true),
                "the observation stands even where the settings deny the skip");
        assertTrue(ProjectEditorDiffer.isDepcyExpansionIncomplete(settings(false, false), true),
                "the observation stands even where the settings deny the skip");
    }

    @Test
    void notReportedWhenNoClosureWasTruncated() {
        assertFalse(ProjectEditorDiffer.isDepcyExpansionIncomplete(settings(true, true), false));
        assertFalse(ProjectEditorDiffer.isDepcyExpansionIncomplete(settings(true, false), false));
        assertFalse(ProjectEditorDiffer.isDepcyExpansionIncomplete(settings(false, true), false));
        assertFalse(ProjectEditorDiffer.isDepcyExpansionIncomplete(settings(false, false), false));
    }

    @Test
    void settingsOfEveryOrdinaryUpdateAreNotEnoughOnTheirOwn() {
        // the plugin ships both options on, so a gate on the settings alone
        // would fire on every project update
        ISettings shipped = settings(true, true);
        assertFalse(UIComparisonLoader.isMigrationGenerationSafe(shipped));
        assertFalse(ProjectEditorDiffer.isDepcyExpansionIncomplete(shipped, false));
    }

    private static ISettings settings(boolean hashFirst, boolean skipMatchedAnalysis) {
        var settings = new TestUiSettings();
        settings.setPgRoutineBodyHashFirst(hashFirst);
        settings.setPgRoutineBodySkipMatchedAnalysis(skipMatchedAnalysis);
        return settings;
    }
}
