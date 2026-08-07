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

import java.util.Collection;
import java.util.List;
import java.util.Objects;

import ru.taximaxim.codekeeper.ui.builders.ProjectBuildContinuity.BuildContinuityProof;
import ru.taximaxim.codekeeper.ui.builders.ProjectBuildContinuity.Kind;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexIdentity;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexRevision;

/**
 * Binds Eclipse delta continuity to the exact live parser index.
 */
final class ProjectIndexContinuity {

    private record Accepted(
            BuildContinuityProof proof,
            long configurationEpoch,
            ProjectIndexIdentity identity,
            ProjectReferenceIndex storage,
            long generation,
            ProjectIndexRevision revision) {
    }

    private Accepted accepted;

    synchronized boolean permitsIncremental(
            BuildContinuityProof proof, String relativePath,
            long configurationEpoch, ProjectIndexIdentity identity,
            ProjectReferenceIndex storage,
            IncrementalProjectReferenceIndex.AnalysisLease lease,
            ProjectIndexRevision revision) {
        return permitsIncremental(proof,
                relativePath == null
                        ? List.of() : List.of(relativePath),
                configurationEpoch, identity, storage, lease, revision);
    }

    synchronized boolean permitsIncremental(
            BuildContinuityProof proof,
            Collection<String> relativePaths,
            long configurationEpoch, ProjectIndexIdentity identity,
            ProjectReferenceIndex storage,
            IncrementalProjectReferenceIndex.AnalysisLease lease,
            ProjectIndexRevision revision) {
        Accepted current = accepted;
        return current != null
                && proof != null
                && proof.isCurrent()
                && proof.permitsIncremental(relativePaths)
                && proof.descendsFrom(current.proof())
                && current.configurationEpoch() == configurationEpoch
                && current.identity().equals(identity)
                && current.storage() == storage
                && matchesExactIndexVersion(current.identity(),
                        current.generation(), current.revision(),
                        lease.identity(), lease.generation(), revision);
    }

    synchronized boolean acceptReconciliation(BuildContinuityProof proof,
            long configurationEpoch, ProjectReferenceIndex storage) {
        return accept(proof, proof != null
                && proof.kind() != Kind.NO_OP,
                configurationEpoch, storage);
    }

    synchronized boolean acceptIncremental(BuildContinuityProof proof,
            boolean trustedContinuity, long configurationEpoch,
            ProjectReferenceIndex storage) {
        return accept(proof, trustedContinuity && proof != null
                && proof.kind() == Kind.INCREMENTAL,
                configurationEpoch, storage);
    }

    private boolean accept(BuildContinuityProof proof, boolean permitted,
            long configurationEpoch, ProjectReferenceIndex storage) {
        if (!permitted || !proof.isCurrent()
                || !(storage
                        instanceof IncrementalProjectReferenceIndex index)) {
            accepted = null;
            return false;
        }
        try (var lease = index.acquireAnalysisLease()) {
            ProjectIndexRevision revision =
                    storage instanceof PackedProjectReferenceIndex packed
                            ? packed.revision() : null;
            accepted = new Accepted(proof, configurationEpoch,
                    lease.identity(), storage, lease.generation(), revision);
            return true;
        } catch (RuntimeException ex) {
            accepted = null;
            return false;
        }
    }

    synchronized void invalidate() {
        accepted = null;
    }

    synchronized void invalidate(BuildContinuityProof proof) {
        if (accepted != null && accepted.proof() == proof) {
            accepted = null;
        }
    }

    static boolean matchesExactIndexVersion(
            ProjectIndexIdentity expectedIdentity,
            long expectedGeneration,
            ProjectIndexRevision expectedRevision,
            ProjectIndexIdentity actualIdentity,
            long actualGeneration,
            ProjectIndexRevision actualRevision) {
        return Objects.equals(expectedIdentity, actualIdentity)
                && expectedGeneration == actualGeneration
                && Objects.equals(expectedRevision, actualRevision);
    }
}
