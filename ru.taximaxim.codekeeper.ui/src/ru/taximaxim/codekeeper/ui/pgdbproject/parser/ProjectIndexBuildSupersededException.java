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

import java.io.IOException;

/**
 * Signals that a project-index build became obsolete because its inputs
 * changed while it was running. This is an expected concurrency outcome, not
 * a database loading failure.
 */
public class ProjectIndexBuildSupersededException extends IOException {

    private static final long serialVersionUID = -2851754960145730398L;

    public ProjectIndexBuildSupersededException(String message) {
        super(message);
    }

    public ProjectIndexBuildSupersededException(
            String message, Throwable cause) {
        super(message, cause);
    }
}
