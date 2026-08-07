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
package ru.taximaxim.codekeeper.ui.workbench;

import org.eclipse.core.runtime.Platform;
import org.eclipse.e4.ui.model.application.MApplication;
import org.eclipse.e4.ui.workbench.lifecycle.ProcessAdditions;

public final class StandaloneWorkbenchLifecycle {

    @ProcessAdditions
    public void processAdditions(MApplication application) {
        try {
            PersistedWorkbenchModelMigration.migrate(application,
                    bundleId -> Platform.getBundle(bundleId) != null);
        } catch (RuntimeException ex) {
            logSafely(ex);
        }
    }

    private static void logSafely(RuntimeException ex) {
        try {
            Platform.getLog(StandaloneWorkbenchLifecycle.class).error(
                    "Unable to migrate persisted standalone workbench state", //$NON-NLS-1$
                    ex);
        } catch (RuntimeException | LinkageError logFailure) {
            try {
                System.err.println(
                        "Unable to migrate persisted standalone workbench state"); //$NON-NLS-1$
                ex.printStackTrace(System.err);
            } catch (RuntimeException | LinkageError ignored) {
                // Startup must continue even when no logging path is ready.
            }
        }
    }
}
