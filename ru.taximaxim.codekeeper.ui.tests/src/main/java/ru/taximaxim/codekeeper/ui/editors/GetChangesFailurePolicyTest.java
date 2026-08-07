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
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ru.taximaxim.codekeeper.ui.localizations.Messages;
import ru.taximaxim.codekeeper.ui.pgdbproject.parser.ProjectInputsChangedException;
import ru.taximaxim.codekeeper.ui.settings.EclipseComparisonTelemetry.ProjectInputChangeStage;

class GetChangesFailurePolicyTest {

    @Test
    void changedProjectInputsProduceHumanReadableNotice() {
        var failure = new ProjectInputsChangedException(
                ProjectInputChangeStage.FILE_SET,
                "SCHEMA/app/TABLE/item.sql"); //$NON-NLS-1$

        assertEquals(
                Messages.ProjectEditorDiffer_project_inputs_changed,
                GetChangesFailurePolicy.notice(failure).orElseThrow());
    }

    @Test
    void ordinaryCancellationRemainsSilent() {
        assertTrue(GetChangesFailurePolicy.notice(
                new InterruptedException("cancelled")).isEmpty()); //$NON-NLS-1$
    }

    @Test
    void activeComparisonChangeIsVisibleWithoutAnExistingDiff() {
        assertEquals(
                Messages.ProjectEditorDiffer_project_inputs_changed,
                GetChangesFailurePolicy.projectChangeNotice(
                        true, false).orElseThrow());
        assertTrue(GetChangesFailurePolicy.projectChangeNotice(
                false, false).isEmpty());
        assertEquals(Messages.DiffPresentationPane_project_modified,
                GetChangesFailurePolicy.projectChangeNotice(
                        false, true).orElseThrow());
    }
}
