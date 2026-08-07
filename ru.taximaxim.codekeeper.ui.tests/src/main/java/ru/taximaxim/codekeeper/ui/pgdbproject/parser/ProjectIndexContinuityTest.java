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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.base.schema.meta.MetaStatement;

import ru.taximaxim.codekeeper.ui.DatabaseType;
import ru.taximaxim.codekeeper.ui.builders.ProjectBuildContinuityTestSupport;
import ru.taximaxim.codekeeper.ui.projectindex.IndexPathRef;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectFileStamp;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexDefinitionSubject;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexIdentity;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexRevision;

class ProjectIndexContinuityTest {

    @Test
    void packedRevisionRequiresExactJournalLengthAtSameGeneration() {
        ProjectIndexIdentity identity = identity();
        ProjectIndexRevision expected = new ProjectIndexRevision(
                "publication", 128, 7, identity);
        ProjectIndexRevision stale = new ProjectIndexRevision(
                "publication", 64, 7, identity);

        assertTrue(ProjectIndexContinuity.matchesExactIndexVersion(
                identity, 7, expected, identity, 7, expected));
        assertFalse(ProjectIndexContinuity.matchesExactIndexVersion(
                identity, 7, expected, identity, 7, stale));
    }

    @Test
    void memoryVersionRequiresExactIdentityAndGeneration() {
        ProjectIndexIdentity identity = identity();
        ProjectIndexIdentity otherIdentity =
                new ProjectIndexIdentity(2, "15.0.0-neo1",
                        "15.0.0-neo1", DatabaseType.PG,
                        "other-project", new byte[32]);

        assertTrue(ProjectIndexContinuity.matchesExactIndexVersion(
                identity, 7, null, identity, 7, null));
        assertFalse(ProjectIndexContinuity.matchesExactIndexVersion(
                identity, 7, null, identity, 8, null));
        assertFalse(ProjectIndexContinuity.matchesExactIndexVersion(
                identity, 7, null, otherIdentity, 7, null));
    }

    @Test
    void continuityAuthorizesOnlyTheExactCanonicalBatch() {
        ProjectIndexIdentity identity = identity();
        var storage = new TestIncrementalIndex(identity, 7L);
        var builder = ProjectBuildContinuityTestSupport
                .acceptedReconciliation(1L);
        var continuity = new ProjectIndexContinuity();
        assertTrue(continuity.acceptReconciliation(
                builder.anchor(), 3L, storage));
        var batch = builder.nextBatch(List.of(
                "SCHEMA/app/TABLE/second.sql",
                "SCHEMA/app/TABLE/first.sql"));

        try (var lease = storage.acquireAnalysisLease()) {
            assertTrue(continuity.permitsIncremental(
                    batch,
                    List.of("SCHEMA/app/TABLE/first.sql",
                            "SCHEMA/app/TABLE/second.sql"),
                    3L, identity, storage, lease, null));
            assertFalse(continuity.permitsIncremental(
                    batch,
                    List.of("SCHEMA/app/TABLE/first.sql"),
                    3L, identity, storage, lease, null));
        }
    }

    private static ProjectIndexIdentity identity() {
        return new ProjectIndexIdentity(2, "15.0.0-neo1",
                "15.0.0-neo1", DatabaseType.PG,
                "project", new byte[32]);
    }

    private static final class TestIncrementalIndex
            implements IncrementalProjectReferenceIndex {

        private final ProjectIndexIdentity identity;
        private final long generation;

        private TestIncrementalIndex(
                ProjectIndexIdentity identity, long generation) {
            this.identity = identity;
            this.generation = generation;
        }

        @Override
        public AnalysisLease acquireAnalysisLease() {
            return new AnalysisLease() {
                private boolean open = true;

                @Override
                public ProjectIndexIdentity identity() {
                    requireOpen();
                    return identity;
                }

                @Override
                public long generation() {
                    requireOpen();
                    return generation;
                }

                @Override
                public List<ProjectFileStamp> fileStamps() {
                    requireOpen();
                    return List.of();
                }

                @Override
                public Optional<IncrementalFileMetadata> file(
                        IndexPathRef path) {
                    requireOpen();
                    return Optional.empty();
                }

                @Override
                public List<MetaStatement> definitions(
                        ProjectIndexDefinitionSubject subject,
                        IndexPathRef excludedPath) {
                    requireOpen();
                    return List.of();
                }

                @Override
                public boolean anyFileMayHoldUnresolvedReferences() {
                    requireOpen();
                    return false;
                }

                @Override
                public void close() {
                    open = false;
                }

                private void requireOpen() {
                    if (!open) {
                        throw new IllegalStateException();
                    }
                }
            };
        }

        @Override
        public Set<ObjectLocation> referencesForPath(String path) {
            return Set.of();
        }

        @Override
        public List<MetaStatement> definitionsForPath(String path) {
            return List.of();
        }

        @Override
        public List<MetaStatement> definitionsMatching(
                ObjectLocation object) {
            return List.of();
        }

        @Override
        public List<ObjectLocation> referencesMatching(
                ObjectLocation object) {
            return List.of();
        }

        @Override
        public List<MetaStatement> completionCandidates(String text) {
            return List.of();
        }

        @Override
        public Stream<MetaStatement> allDefinitions() {
            return Stream.empty();
        }

        @Override
        public Stream<ObjectLocation> allReferences() {
            return Stream.empty();
        }

        @Override
        public ProjectReferencesStorage mutableCopy() {
            return new ProjectReferencesStorage();
        }
    }
}
