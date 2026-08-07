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
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Tracks the trusted sequence of resource deltas observed by one project
 * builder instance.
 */
public final class ProjectBuildContinuity {

    public enum Kind {
        RECONCILIATION,
        INCREMENTAL,
        NO_OP
    }

    private enum State {
        PENDING,
        ACCEPTED,
        INVALIDATED
    }

    /**
     * An owner-bound proof of one exact builder generation. Callers may inspect
     * and validate the capability, but only its owning builder continuity can
     * create, accept or invalidate it.
     */
    public static final class BuildContinuityProof {

        private final ProjectBuildContinuity owner;
        private final Kind kind;
        private final List<String> relativePaths;
        private final long lineage;
        private final long generation;
        private final long sequence;
        private final long indexedAnchorSequence;
        private final boolean incremental;
        private State state = State.PENDING;

        private BuildContinuityProof(ProjectBuildContinuity owner,
                Kind kind, List<String> relativePaths, long lineage,
                long generation, long sequence,
                long indexedAnchorSequence,
                boolean incremental) {
            this.owner = owner;
            this.kind = kind;
            this.relativePaths = relativePaths;
            this.lineage = lineage;
            this.generation = generation;
            this.sequence = sequence;
            this.indexedAnchorSequence =
                    indexedAnchorSequence;
            this.incremental = incremental;
        }

        public Kind kind() {
            return kind;
        }

        public String relativePath() {
            return relativePaths.size() == 1
                    ? relativePaths.getFirst() : null;
        }

        public List<String> relativePaths() {
            return relativePaths;
        }

        public boolean isCurrent() {
            return owner.isCurrent(this);
        }

        public boolean permitsIncremental() {
            return owner.permitsIncremental(this);
        }

        public boolean permitsIncremental(
                Collection<String> expectedPaths) {
            return owner.permitsIncremental(
                    this, expectedPaths);
        }

        public boolean descendsFrom(BuildContinuityProof anchor) {
            return owner.descendsFrom(this, anchor);
        }
    }

    private long lastGeneration;
    private long nextSequence;
    private long nextLineage;
    private long activeLineage;
    private BuildContinuityProof current;
    private BuildContinuityProof indexedAnchor;

    ProjectBuildContinuity() {
    }

    synchronized BuildContinuityProof beginReconciliation(
            long generation) {
        beginGeneration(generation);
        revokeLineage();
        return current = newProof(Kind.RECONCILIATION, List.of(),
                ++nextLineage, generation, 0, false);
    }

    synchronized BuildContinuityProof beginSingleFile(
            long generation, String relativePath) {
        return beginIncremental(generation,
                List.of(ProjectBuildDeltaClassifier
                        .normalize(relativePath)));
    }

    synchronized BuildContinuityProof beginIncremental(
            long generation, Collection<String> relativePaths) {
        List<String> paths = canonicalPaths(relativePaths);
        boolean continuous = beginGeneration(generation)
                && activeLineage != 0 && indexedAnchor != null;
        if (!continuous) {
            revokeLineage();
            return current = newProof(Kind.RECONCILIATION,
                    List.of(), ++nextLineage, generation, 0,
                    false);
        }
        return current = newProof(Kind.INCREMENTAL, paths,
                activeLineage, generation,
                indexedAnchor.sequence, true);
    }

    synchronized BuildContinuityProof beginNoOp(long generation) {
        boolean continuous = beginGeneration(generation)
                && activeLineage != 0 && indexedAnchor != null;
        if (!continuous) {
            revokeLineage();
        }
        return current = newProof(Kind.NO_OP, List.of(),
                continuous ? activeLineage : 0, generation,
                continuous ? indexedAnchor.sequence : 0,
                false);
    }

    synchronized boolean accept(BuildContinuityProof proof) {
        if (!isPendingCurrent(proof)) {
            return false;
        }
        switch (proof.kind) {
        case RECONCILIATION:
            activeLineage = proof.lineage;
            indexedAnchor = proof;
            break;
        case INCREMENTAL:
            if (!proof.incremental
                    || activeLineage != proof.lineage
                    || indexedAnchor == null
                    || indexedAnchor.sequence
                            != proof.indexedAnchorSequence) {
                proof.state = State.INVALIDATED;
                revokeLineage();
                return false;
            }
            indexedAnchor = proof;
            break;
        case NO_OP:
            if (proof.lineage != 0
                    && (activeLineage != proof.lineage
                            || indexedAnchor == null
                            || indexedAnchor.sequence
                                    != proof.indexedAnchorSequence)) {
                proof.state = State.INVALIDATED;
                revokeLineage();
                return false;
            }
            break;
        }
        proof.state = State.ACCEPTED;
        return true;
    }

    synchronized boolean invalidate(BuildContinuityProof proof) {
        if (!ownsCurrent(proof)
                || proof.state == State.INVALIDATED) {
            return false;
        }
        proof.state = State.INVALIDATED;
        current = null;
        revokeLineage();
        return true;
    }

    private synchronized boolean isCurrent(
            BuildContinuityProof proof) {
        return ownsCurrent(proof)
                && proof.state != State.INVALIDATED;
    }

    private synchronized boolean permitsIncremental(
            BuildContinuityProof proof) {
        return isPendingCurrent(proof)
                && proof.kind == Kind.INCREMENTAL
                && proof.incremental
                && activeLineage == proof.lineage
                && indexedAnchor != null
                && indexedAnchor.sequence
                        == proof.indexedAnchorSequence;
    }

    private synchronized boolean permitsIncremental(
            BuildContinuityProof proof,
            Collection<String> expectedPaths) {
        if (!permitsIncremental(proof)) {
            return false;
        }
        try {
            return proof.relativePaths.equals(
                    canonicalPaths(expectedPaths));
        } catch (IllegalArgumentException
                | NullPointerException ex) {
            return false;
        }
    }

    private synchronized boolean descendsFrom(
            BuildContinuityProof proof,
            BuildContinuityProof anchor) {
        return permitsIncremental(proof)
                && anchor != null
                && anchor.owner == this
                && anchor.state == State.ACCEPTED
                && anchor.sequence
                        == proof.indexedAnchorSequence
                && anchor.lineage == proof.lineage;
    }

    private boolean beginGeneration(long generation) {
        if (generation <= 0 || generation <= lastGeneration) {
            throw new IllegalArgumentException(
                    "Builder generation must increase"); //$NON-NLS-1$
        }
        boolean exactNext = lastGeneration == 0
                || generation == lastGeneration + 1;
        boolean settled = current == null
                || current.state == State.ACCEPTED;
        if (!settled && current != null) {
            current.state = State.INVALIDATED;
        }
        if (!exactNext || !settled) {
            revokeLineage();
        }
        lastGeneration = generation;
        return exactNext && settled;
    }

    private BuildContinuityProof newProof(Kind kind,
            List<String> relativePaths, long lineage,
            long generation,
            long anchorSequence, boolean incremental) {
        return new BuildContinuityProof(this, kind,
                List.copyOf(relativePaths),
                lineage, generation, ++nextSequence,
                anchorSequence,
                incremental);
    }

    private boolean isPendingCurrent(
            BuildContinuityProof proof) {
        return ownsCurrent(proof)
                && proof.state == State.PENDING;
    }

    private boolean ownsCurrent(BuildContinuityProof proof) {
        return proof != null
                && proof.owner == this
                && current == proof
                && proof.generation == lastGeneration
                && proof.sequence == nextSequence;
    }

    private void revokeLineage() {
        activeLineage = 0;
        indexedAnchor = null;
    }

    private static List<String> canonicalPaths(
            Collection<String> values) {
        Objects.requireNonNull(values, "relativePaths"); //$NON-NLS-1$
        if (values.isEmpty()
                || values.size()
                        > ProjectBuildDeltaClassifier
                                .MAX_INCREMENTAL_PATHS) {
            throw new IllegalArgumentException(
                    "Incremental batch must contain 1 to " //$NON-NLS-1$
                            + ProjectBuildDeltaClassifier
                                    .MAX_INCREMENTAL_PATHS
                            + " paths"); //$NON-NLS-1$
        }
        var paths = new TreeSet<String>();
        for (String value : values) {
            if (!paths.add(ProjectBuildDeltaClassifier
                    .normalize(value))) {
                throw new IllegalArgumentException(
                        "Incremental paths must be unique"); //$NON-NLS-1$
            }
        }
        return List.copyOf(paths);
    }
}
