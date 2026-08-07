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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class ProjectComparisonModelCacheTest {

    @Test
    void candidateIsInvisibleUntilPublication() {
        var closed = new AtomicInteger();
        try (var cache = new ProjectComparisonModelCache<TestModel>()) {
            var model = new TestModel("first", closed);
            var candidate = cache.prepare("profile", model);

            assertTrue(cache.acquire("profile").isEmpty());
            assertEquals(0, cache.retainedEntries());

            try (var lease = candidate.publish().orElseThrow()) {
                assertSame(model, lease.model());
                assertEquals(1, cache.retainedEntries());
                assertTrue(cache.acquire("profile").isEmpty(),
                        "A reusable model must have one exclusive consumer");
            }

            assertEquals(0, closed.get());
            assertTrue(cache.acquire("other-profile").isEmpty());
        }
        assertEquals(1, closed.get());
    }

    @Test
    void publicationEvictsRetainedModelsOfOtherCaches() {
        var firstClosed = new AtomicInteger();
        var secondClosed = new AtomicInteger();
        try (var first = new ProjectComparisonModelCache<TestModel>();
                var second = new ProjectComparisonModelCache<TestModel>()) {
            first.prepare("profile", new TestModel("first", firstClosed))
                    .publish().orElseThrow().close();
            assertEquals(1, first.retainedEntries());

            second.prepare("profile", new TestModel("second", secondClosed))
                    .publish().orElseThrow().close();

            assertEquals(0, first.retainedEntries(),
                    "only one analyzed model may be retained at a time");
            assertEquals(1, firstClosed.get());
            assertEquals(1, second.retainedEntries());
            assertEquals(0, secondClosed.get());
        }
        assertEquals(1, secondClosed.get());
    }

    @Test
    void evictionWaitsForAModelThatIsStillDisplayed() {
        var closed = new AtomicInteger();
        try (var displaying = new ProjectComparisonModelCache<TestModel>();
                var other = new ProjectComparisonModelCache<TestModel>()) {
            var displayed = displaying.prepare(
                    "profile", new TestModel("displayed", closed))
                    .publish().orElseThrow();

            other.prepare("profile", new TestModel("other",
                    new AtomicInteger())).publish().orElseThrow().close();

            assertEquals(0, closed.get(),
                    "an evicted model stays alive while it is displayed");
            assertEquals("displayed", displayed.model().name());
            displayed.close();
            assertEquals(1, closed.get());
        }
    }

    @Test
    void abandonedCandidateIsClosedAndNeverPublished() {
        var closed = new AtomicInteger();
        try (var cache = new ProjectComparisonModelCache<TestModel>();
                var candidate = cache.prepare(
                        "profile", new TestModel("candidate", closed))) {
            assertTrue(cache.acquire("profile").isEmpty());
        }

        assertEquals(1, closed.get());
    }

    @Test
    void invalidationWaitsForTheActiveLease() {
        var closed = new AtomicInteger();
        try (var cache = new ProjectComparisonModelCache<TestModel>()) {
            var candidate = cache.prepare(
                    "profile", new TestModel("first", closed));
            var lease = candidate.publish().orElseThrow();

            cache.invalidate(lease);

            assertEquals(0, cache.retainedEntries());
            assertEquals(0, closed.get(),
                    "An in-flight comparison still owns the model");
            assertTrue(cache.acquire("profile").isEmpty());

            lease.close();
            assertEquals(1, closed.get());
        }
        assertEquals(1, closed.get());
    }

    @Test
    void publicationReplacesAndClosesThePreviousGeneration() {
        var closed = new AtomicInteger();
        try (var cache = new ProjectComparisonModelCache<TestModel>()) {
            try (var first = cache.prepare(
                    "profile", new TestModel("first", closed))
                    .publish().orElseThrow()) {
                assertEquals("first", first.model().name());
            }

            try (var second = cache.prepare(
                    "profile", new TestModel("second", closed))
                    .publish().orElseThrow()) {
                assertEquals("second", second.model().name());
                assertEquals(1, closed.get());
                assertEquals(1, cache.retainedEntries());
            }
        }
        assertEquals(2, closed.get());
    }

    @Test
    void repeatedPublicationsRetainOnlyOneGeneration() {
        var closed = new AtomicInteger();
        try (var cache = new ProjectComparisonModelCache<TestModel>()) {
            for (int i = 0; i < 20; i++) {
                try (var lease = cache.prepare(
                        "profile", new TestModel("model-" + i, closed))
                        .publish().orElseThrow()) {
                    assertEquals(1, cache.retainedEntries());
                }
            }
            assertEquals(19, closed.get());
        }
        assertEquals(20, closed.get());
    }

    @Test
    void publicationFailsClosedWhilePreviousGenerationIsLeased() {
        var closed = new AtomicInteger();
        try (var cache = new ProjectComparisonModelCache<TestModel>()) {
            var first = cache.prepare(
                    "profile", new TestModel("first", closed))
                    .publish().orElseThrow();
            var rejected = cache.prepare(
                    "profile", new TestModel("second", closed));

            Optional<ProjectComparisonModelCache<TestModel>.Lease> published =
                    rejected.publish();

            assertTrue(published.isEmpty());
            assertEquals(1, closed.get(),
                    "The rejected candidate must release its anchor immediately");
            assertSame(first.model(), first.model());
            first.close();
        }
        assertEquals(2, closed.get());
    }

    @Test
    void closeRejectsFurtherCandidatesAndAcquisitions() {
        var closed = new AtomicInteger();
        var cache = new ProjectComparisonModelCache<TestModel>();
        var candidate = cache.prepare(
                "profile", new TestModel("candidate", closed));

        cache.close();

        assertTrue(candidate.publish().isEmpty());
        assertTrue(cache.acquire("profile").isEmpty());
        assertEquals(1, closed.get());
        assertFalse(candidate.isPublished());
    }

    @Test
    void semanticLookupCanMatchAKeyWithoutKnowingItsRuntimeVersion() {
        var closed = new AtomicInteger();
        try (var cache =
                new ProjectComparisonModelCache<TestModel>()) {
            try (var published = cache.prepare(
                    "profile:pg17",
                    new TestModel("model", closed))
                    .publish().orElseThrow()) {
                assertEquals("model",
                        published.model().name());
            }

            try (var matched = cache.acquireMatching(
                    key -> key.toString()
                            .startsWith("profile:"))
                    .orElseThrow()) {
                assertEquals("model",
                        matched.model().name());
            }
            assertTrue(cache.acquireMatching(
                    key -> false).isEmpty());
        }
        assertEquals(1, closed.get());
    }

    @Test
    void invalidatingAnUnusableRetainedModelReleasesItBeforeColdLoad() {
        var closed = new AtomicInteger();
        try (var cache =
                new ProjectComparisonModelCache<TestModel>()) {
            try (var lease = cache.prepare(
                    "old-profile",
                    new TestModel("old", closed))
                    .publish().orElseThrow()) {
                // make the retained generation available
            }

            cache.invalidateRetained();

            assertEquals(0, cache.retainedEntries());
            assertEquals(1, closed.get());
            assertTrue(cache.acquire(
                    "old-profile").isEmpty());
        }
        assertEquals(1, closed.get());
    }

    @Test
    void invalidatedActiveLeaseBlocksASecondGenerationUntilRelease() {
        var closed = new AtomicInteger();
        try (var cache =
                new ProjectComparisonModelCache<TestModel>()) {
            var active = cache.prepare(
                    "profile",
                    new TestModel("active", closed))
                    .publish().orElseThrow();

            cache.invalidateRetained();
            var rejected = cache.prepare(
                    "profile",
                    new TestModel("rejected", closed));

            assertTrue(rejected.publish().isEmpty(),
                    "retirement must remember an active generation");
            assertEquals(1, closed.get(),
                    "only the rejected second model is released");

            active.close();
            assertEquals(2, closed.get(),
                    "the retired active model closes on release");

            try (var replacement = cache.prepare(
                    "profile",
                    new TestModel("replacement", closed))
                    .publish().orElseThrow()) {
                assertEquals("replacement",
                        replacement.model().name());
            }
        }
        assertEquals(3, closed.get());
    }

    private record TestModel(String name, AtomicInteger closed)
            implements AutoCloseable {

        @Override
        public void close() {
            closed.incrementAndGet();
        }
    }
}
