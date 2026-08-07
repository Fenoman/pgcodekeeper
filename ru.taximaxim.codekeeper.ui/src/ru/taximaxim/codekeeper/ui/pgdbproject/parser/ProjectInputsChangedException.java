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

import java.util.Objects;
import java.util.Optional;

import ru.taximaxim.codekeeper.ui.settings.EclipseComparisonTelemetry.ProjectInputChangeStage;

/**
 * Cancels a comparison whose project inputs changed while the result was being
 * prepared. The caller may present this expected fail-closed outcome without
 * treating it as a database or parser error.
 */
public final class ProjectInputsChangedException
        extends InterruptedException {

    private static final long serialVersionUID = 7996129367224309692L;

    private final ProjectInputChangeStage stage;
    private final String relativePath;

    public ProjectInputsChangedException(
            ProjectInputChangeStage stage, String relativePath) {
        super(message(stage, relativePath));
        this.stage = Objects.requireNonNull(stage, "stage"); //$NON-NLS-1$
        this.relativePath = relativePath == null
                || relativePath.isBlank() ? null : relativePath;
    }

    public ProjectInputChangeStage stage() {
        return stage;
    }

    public Optional<String> relativePath() {
        return Optional.ofNullable(relativePath);
    }

    private static String message(
            ProjectInputChangeStage stage, String relativePath) {
        String message = "Project inputs changed during " //$NON-NLS-1$
                + Objects.requireNonNull(stage, "stage"); //$NON-NLS-1$
        return relativePath == null || relativePath.isBlank()
                ? message : message + ": " + relativePath; //$NON-NLS-1$
    }
}
