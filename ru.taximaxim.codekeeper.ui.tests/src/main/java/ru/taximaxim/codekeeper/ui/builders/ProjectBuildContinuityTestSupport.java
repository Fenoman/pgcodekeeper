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

import java.util.Collection;

import ru.taximaxim.codekeeper.ui.builders.ProjectBuildContinuity.BuildContinuityProof;

/**
 * Creates real owner-bound continuity capabilities for tests in other
 * packages without adding a production test hook.
 */
public final class ProjectBuildContinuityTestSupport {

    public static final class Scenario {

        private final ProjectBuildContinuity continuity =
                new ProjectBuildContinuity();
        private long generation;
        private BuildContinuityProof anchor;

        private Scenario(long initialGeneration) {
            generation = initialGeneration;
            anchor = continuity.beginReconciliation(generation);
            if (!continuity.accept(anchor)) {
                throw new AssertionError(
                        "Initial reconciliation was not accepted"); //$NON-NLS-1$
            }
        }

        public BuildContinuityProof anchor() {
            return anchor;
        }

        public BuildContinuityProof nextSingle(String relativePath) {
            return continuity.beginSingleFile(
                    ++generation, relativePath);
        }

        public BuildContinuityProof nextBatch(
                Collection<String> relativePaths) {
            return continuity.beginIncremental(
                    ++generation, relativePaths);
        }

        public BuildContinuityProof nextNoOp() {
            return continuity.beginNoOp(++generation);
        }

        public BuildContinuityProof nextReconciliation() {
            return continuity.beginReconciliation(++generation);
        }

        public boolean accept(BuildContinuityProof proof) {
            boolean accepted = continuity.accept(proof);
            if (accepted && proof.kind()
                    != ProjectBuildContinuity.Kind.NO_OP) {
                anchor = proof;
            }
            return accepted;
        }

        public boolean invalidate(BuildContinuityProof proof) {
            return continuity.invalidate(proof);
        }
    }

    private ProjectBuildContinuityTestSupport() {
    }

    public static Scenario acceptedReconciliation(
            long generation) {
        return new Scenario(generation);
    }
}
