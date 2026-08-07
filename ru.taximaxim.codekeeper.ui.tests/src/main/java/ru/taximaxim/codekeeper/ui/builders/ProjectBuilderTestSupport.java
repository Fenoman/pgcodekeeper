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
package ru.taximaxim.codekeeper.ui.builders;

import java.io.IOException;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;

public final class ProjectBuilderTestSupport {

    @FunctionalInterface
    public interface Preparation {
        void run() throws CoreException, IOException, InterruptedException;
    }

    @FunctionalInterface
    public interface Commit {
        void run() throws IOException;
    }

    public static boolean execute(IProgressMonitor monitor, Preparation preparation,
            Runnable cancellation, Commit commit)
            throws CoreException, IOException, InterruptedException {
        var gate = new BuilderPublicationGate();
        long generation = gate.start();
        return ProjectBuilder.executeGeneration(gate, generation, monitor, () -> {
            preparation.run();
            return new ProjectBuilder.PreparedUpdate(false, commit::run, () -> { });
        }, cancellation);
    }

    private ProjectBuilderTestSupport() { }
}
