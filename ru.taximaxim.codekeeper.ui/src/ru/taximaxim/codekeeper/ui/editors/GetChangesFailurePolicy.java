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

import java.util.Optional;

import ru.taximaxim.codekeeper.ui.localizations.Messages;
import ru.taximaxim.codekeeper.ui.pgdbproject.parser.ProjectInputsChangedException;

final class GetChangesFailurePolicy {

    static Optional<String> notice(InterruptedException failure) {
        return failure instanceof ProjectInputsChangedException
                ? Optional.of(
                        Messages.ProjectEditorDiffer_project_inputs_changed)
                : Optional.empty();
    }

    static Optional<String> projectChangeNotice(
            boolean activeComparison, boolean hasDiff) {
        if (activeComparison) {
            return Optional.of(
                    Messages.ProjectEditorDiffer_project_inputs_changed);
        }
        return hasDiff
                ? Optional.of(
                        Messages.DiffPresentationPane_project_modified)
                : Optional.empty();
    }

    private GetChangesFailurePolicy() {
    }
}
