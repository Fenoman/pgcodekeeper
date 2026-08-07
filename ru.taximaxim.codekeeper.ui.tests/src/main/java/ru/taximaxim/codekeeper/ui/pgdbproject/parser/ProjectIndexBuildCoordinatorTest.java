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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgcodekeeper.core.database.api.loader.ProjectInputFingerprint;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.base.schema.meta.MetaStatement;

import ru.taximaxim.codekeeper.ui.DatabaseType;
import ru.taximaxim.codekeeper.ui.projectindex.FileContribution;
import ru.taximaxim.codekeeper.ui.projectindex.IndexPathOrigin;
import ru.taximaxim.codekeeper.ui.projectindex.IndexPathRef;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectFileStamp;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexCodec;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexConfigDigest;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexConfiguration;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexData;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexFiles;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexFormatException;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexIdentity;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexOpenResult.Status;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexPathResolver;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexPersistenceException;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexRevision;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexState;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexStore;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexStoreTestSupport;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetry.Mode;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetry.PersistenceReason;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetryTestSupport;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexWarmValidator;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexWarmValidator.CurrentFile;

class ProjectIndexBuildCoordinatorTest {

    /**
     * Version an identity of these fixtures is stamped with. Fixed on purpose
     * and unrelated to the version the product reports: encoded bytes have to
     * stay reproducible while the product's own coordinate moves.
     */
    private static final String FIXTURE_VERSION = "15.0.0-neo1"; //$NON-NLS-1$

    /**
     * A version that is not {@link #FIXTURE_VERSION} and is not a coordinate
     * anything was ever released under, so a later version sweep cannot
     * collapse the two and leave a difference test asking nothing.
     */
    private static final String OTHER_VERSION = "15.1.0-other"; //$NON-NLS-1$

    @Test
    void classifiesAuxiliaryMemoryLimitByType() {
        var failure = new ProjectIndexPersistenceException(
                PersistenceReason.AUXILIARY_MEMORY_LIMIT);

        assertEquals(PersistenceReason.AUXILIARY_MEMORY_LIMIT,
                ProjectIndexBuildCoordinator
                        .persistenceFailureReason(failure));
    }

    @Test
    void classifiesWrappedIOExceptionByTypedCause() {
        var failure = new UncheckedIOException(
                new java.io.IOException("secret path"));

        assertEquals(PersistenceReason.IO,
                ProjectIndexBuildCoordinator
                        .persistenceFailureReason(failure));
    }

    @Test
    void stateDirectoryUsesCanonicalProjectPathHash(@TempDir Path temp)
            throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path state = temp.resolve("state");

        Path directory = ProjectIndexState.directory(state,
                project.resolve("..").resolve("project"));

        assertEquals(state.toAbsolutePath().normalize()
                .resolve("projects-v2")
                .resolve(ProjectIndexState.projectIdentity(project)),
                directory);
    }

    @Test
    void unchangedProjectRestartsFromPackedIndexWithoutAnotherFullBuild(
            @TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path library = Files.createDirectories(temp.resolve("library"));
        Path sql = Files.writeString(project.resolve("table.sql"),
                "CREATE TABLE app.item(id bigint);");
        var input = new FilesInput(project, library, sql);
        var fullBuilds = new AtomicInteger();
        var request = request(temp, project, library, identity(project,
                DatabaseType.PG, digest("baseline")));

        try (var first = ProjectIndexBuildCoordinator.prepare(request, input,
                () -> {
                    fullBuilds.incrementAndGet();
                    return build(project, "item", List.of());
                }, () -> false)) {
            assertFalse(first.restoredFromDisk());
            try (var publication = first.publish(() -> false)) {
                assertTrue(publication.v2Current());
                assertEquals("item", publication.storage()
                        .definitionsForPath(sql.toString()).getFirst().getName());
                transferAndClose(publication);
            }
        }
        assertEquals(1, fullBuilds.get());

        try (var restarted = ProjectIndexBuildCoordinator.prepare(request,
                input, () -> {
                    fullBuilds.incrementAndGet();
                    return build(project, "must_not_run", List.of());
                }, () -> false)) {
            assertTrue(restarted.restoredFromDisk());
            try (var publication = restarted.publish(() -> false)) {
                assertTrue(publication.v2Current());
                assertEquals("item", publication.storage()
                        .definitionsForPath(sql.toString()).getFirst().getName());
                transferAndClose(publication);
            }
        }
        assertEquals(1, fullBuilds.get());
    }

    @Test
    void warmOnlyRestoreHitsExistingIndexWithoutFullBuild(
            @TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path library = Files.createDirectories(temp.resolve("library"));
        Path sql = Files.writeString(project.resolve("table.sql"),
                "CREATE TABLE app.item(id bigint);");
        var input = new FilesInput(project, library, sql);
        var request = request(temp, project, library, identity(project,
                DatabaseType.PG, digest("warm-only-hit")));

        try (var prepared = ProjectIndexBuildCoordinator.prepare(request,
                input, () -> build(project, "item", List.of()),
                () -> false);
                var publication = prepared.publish(() -> false)) {
            transferAndClose(publication);
        }

        var restored = ProjectIndexBuildCoordinator.tryPrepareWarm(
                request, input, () -> false).orElseThrow();
        try (restored;
                var publication = restored.publish(() -> false)) {
            assertTrue(restored.restoredFromDisk());
            assertEquals("item", publication.storage()
                    .definitionsForPath(sql.toString()).getFirst().getName());
            transferAndClose(publication);
        }
    }

    @Test
    void retainedWarmPublicationClosesDuplicateAndKeepsCurrent(
            @TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path library = Files.createDirectories(temp.resolve("library"));
        Path sql = Files.writeString(project.resolve("table.sql"),
                "CREATE TABLE app.item(id bigint);");
        var input = new FilesInput(project, library, sql);
        var request = request(temp, project, library, identity(project,
                DatabaseType.PG, digest("warm-retained")));

        try (var prepared = ProjectIndexBuildCoordinator.prepare(request,
                input, () -> build(project, "item", List.of()),
                () -> false);
                var publication = prepared.publish(() -> false)) {
            transferAndClose(publication);
        }

        var restored = ProjectIndexBuildCoordinator.tryPrepareWarm(
                request, input, () -> false).orElseThrow();
        ProjectReferenceIndex duplicate;
        try (restored;
                var publication = restored.publish(() -> false)) {
            duplicate = publication.storage();
            publication.retainExistingStorage();
            assertThrows(IllegalStateException.class,
                    publication::transferStorage);
        }
        assertThrows(IllegalStateException.class,
                () -> duplicate.definitionsForPath(sql.toString()));

        try (var restoredAgain =
                ProjectIndexBuildCoordinator.tryPrepareWarm(
                        request, input, () -> false).orElseThrow();
                var publication =
                        restoredAgain.publish(() -> false)) {
            assertEquals("item", publication.storage()
                    .definitionsForPath(sql.toString()).getFirst().getName());
            transferAndClose(publication);
        }
    }

    @Test
    void warmOnlyRestoreMissNeverStartsFullBuild(
            @TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path library = Files.createDirectories(temp.resolve("library"));
        Path sql = Files.writeString(project.resolve("table.sql"),
                "CREATE TABLE app.item(id bigint);");
        var request = request(temp, project, library, identity(project,
                DatabaseType.PG, digest("warm-only-miss")));

        assertTrue(ProjectIndexBuildCoordinator.tryPrepareWarm(
                request, new FilesInput(project, library, sql),
                () -> false).isEmpty());
        assertFalse(Files.exists(request.storeDirectory()
                .resolve("current")));
    }

    @Test
    void warmRestoreCarriesEveryKindOfDivergenceToItsCaller(
            @TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path library = Files.createDirectories(temp.resolve("library"));
        Path sql = Files.writeString(project.resolve("table.sql"),
                "CREATE TABLE app.item(id bigint);");
        Path vanishing = Files.writeString(project.resolve("removed.sql"),
                "CREATE TABLE app.gone(id bigint);");
        var input = new TreeInput(project, library, sql, vanishing);
        var request = request(temp, project, library, identity(project,
                DatabaseType.PG, digest("warm-divergence")));

        try (var prepared = ProjectIndexBuildCoordinator.prepare(request,
                input, () -> build(project, "item", List.of()),
                () -> false);
                var publication = prepared.publish(() -> false)) {
            transferAndClose(publication);
        }

        var hit = ProjectIndexBuildCoordinator.restoreWarm(request, input,
                input.inspect(), () -> false, null, false,
                ProjectIndexStoreFactory.PLATFORM);
        try (var restored = hit.prepared().orElseThrow()) {
            assertTrue(hit.hit());
            assertTrue(hit.validation().hit());
            assertEquals(List.of(), hit.validation().changed());
            assertEquals(List.of(), hit.validation().added());
            assertEquals(List.of(), hit.validation().removed());
            assertTrue(hit.validation().complete());
            assertTrue(restored.restoredFromDisk());
        }
        try (var stillHits = ProjectIndexBuildCoordinator.tryPrepareWarm(
                request, input, () -> false).orElseThrow()) {
            assertTrue(stillHits.restoredFromDisk());
        }

        Files.writeString(sql,
                "CREATE TABLE app.item(id bigint, added_column text);");
        Files.delete(vanishing);
        Path appearing = Files.writeString(project.resolve("added.sql"),
                "CREATE TABLE app.fresh(id bigint);");
        input.enumerate(sql, appearing);

        var miss = ProjectIndexBuildCoordinator.restoreWarm(request, input,
                input.inspect(), () -> false, null, false,
                ProjectIndexStoreFactory.PLATFORM);

        assertFalse(miss.hit());
        assertTrue(miss.prepared().isEmpty());
        assertFalse(miss.validation().hit());
        assertEquals(List.of(projectPath("table.sql")),
                miss.validation().changed());
        assertEquals(List.of(projectPath("added.sql")),
                miss.validation().added());
        assertEquals(List.of(projectPath("removed.sql")),
                miss.validation().removed());
        assertTrue(miss.validation().complete());
        assertTrue(ProjectIndexBuildCoordinator.tryPrepareWarm(
                request, input, () -> false).isEmpty());
    }

    @Test
    void warmMissPublishesTheSizeOfTheDivergenceAndNoPath(
            @TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path library = Files.createDirectories(temp.resolve("library"));
        Path sql = Files.writeString(project.resolve("table.sql"),
                "CREATE TABLE app.item(id bigint);");
        Path vanishing = Files.writeString(project.resolve("removed.sql"),
                "CREATE TABLE app.gone(id bigint);");
        var input = new TreeInput(project, library, sql, vanishing);
        var request = request(temp, project, library, identity(project,
                DatabaseType.PG, digest("warm-divergence-telemetry")));

        try (var prepared = ProjectIndexBuildCoordinator.prepare(request,
                input, () -> build(project, "item", List.of()),
                () -> false);
                var publication = prepared.publish(() -> false)) {
            transferAndClose(publication);
        }

        List<String> hitTelemetry = new ArrayList<>();
        try (var restored = ProjectIndexBuildCoordinator.tryPrepareWarm(
                request, input, () -> false,
                ProjectIndexTelemetryTestSupport.start(hitTelemetry,
                        Mode.WARM),
                ProjectIndexStoreFactory.PLATFORM).orElseThrow()) {
            assertTrue(restored.restoredFromDisk());
        }
        assertEquals(1, hitTelemetry.size());
        assertFalse(hitTelemetry.getFirst().contains("warm_miss"));

        Files.writeString(sql,
                "CREATE TABLE app.item(id bigint, added_column text);");
        Files.delete(vanishing);
        input.enumerate(sql, Files.writeString(project.resolve("added.sql"),
                "CREATE TABLE app.fresh(id bigint);"));

        List<String> missTelemetry = new ArrayList<>();
        assertTrue(ProjectIndexBuildCoordinator.tryPrepareWarm(
                request, input, () -> false,
                ProjectIndexTelemetryTestSupport.start(missTelemetry,
                        Mode.WARM),
                ProjectIndexStoreFactory.PLATFORM).isEmpty());

        assertEquals(1, missTelemetry.size());
        String line = missTelemetry.getFirst();
        assertTrue(line.contains("warm_miss_changed=1 warm_miss_added=1 "
                + "warm_miss_removed=1 warm_miss_complete=true"), line);
        assertFalse(line.contains(".sql"), line);
    }

    /**
     * The two halves of a prepare, taken apart. A caller that looks at a warm
     * miss before it decides what to do about it must not pay for that look:
     * the build it resumes has to enumerate the working tree exactly as often
     * as the one that never stopped, and end in the same index.
     */
    @Test
    void aResumedFullBuildEnumeratesTheTreeNoMoreOftenThanAnUninterruptedOne(
            @TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path library = Files.createDirectories(temp.resolve("library"));
        Path sql = Files.writeString(project.resolve("table.sql"),
                "CREATE TABLE app.item(id bigint);");
        var request = request(temp, project, library, identity(project,
                DatabaseType.PG, digest("warm-resume")));

        try (var seeded = ProjectIndexBuildCoordinator.prepare(request,
                new FilesInput(project, library, sql),
                () -> build(project, "item", List.of()), () -> false);
                var publication = seeded.publish(() -> false)) {
            transferAndClose(publication);
        }
        Files.writeString(sql,
                "CREATE TABLE app.moved(id bigint, added_column text);");

        // The build that never stopped. It is not published, so the store
        // keeps the stale index and the second run below sees the very same
        // divergence this one saw.
        var uninterrupted = new CountingInput(
                new FilesInput(project, library, sql));
        ProjectIndexBuildCoordinator.prepare(request, uninterrupted,
                () -> build(project, "moved", List.of()), () -> false)
                .close();

        var resumed = new CountingInput(
                new FilesInput(project, library, sql));
        var attempt = ProjectIndexBuildCoordinator.attemptWarm(request,
                resumed, () -> false, null, false,
                ProjectIndexStoreFactory.PLATFORM);

        assertFalse(attempt.hit());
        assertEquals(List.of(projectPath("table.sql")),
                attempt.validation().changed());
        assertTrue(attempt.validation().complete());

        int resumedBuild;
        try (var prepared = attempt.completeFullBuild(
                () -> build(project, "moved", List.of()))) {
            // Counted before the publication, which the control run never
            // reached and which enumerates the tree again on its own.
            resumedBuild = resumed.inspections();
            assertFalse(prepared.restoredFromDisk());
            try (var publication = prepared.publish(() -> false)) {
                assertEquals("moved", publication.storage()
                        .definitionsForPath(sql.toString()).getFirst()
                        .getName());
                transferAndClose(publication);
            }
        }
        assertEquals(3, resumedBuild,
                "one enumeration for the warm attempt, one to prove the tree"
                        + " stood still while the build ran and one to prove"
                        + " it stood still while the build was hashed. A"
                        + " fourth would mean the attempt was enumerated"
                        + " twice");
        assertEquals(uninterrupted.inspections(), resumedBuild,
                "the inspection the warm attempt already took is what the"
                        + " build resumes from, so stopping to look at the"
                        + " miss costs no enumeration of its own");
    }

    @Test
    void aWarmAttemptThatRestoredAnIndexRefusesToRebuildOverIt(
            @TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path library = Files.createDirectories(temp.resolve("library"));
        Path sql = Files.writeString(project.resolve("table.sql"),
                "CREATE TABLE app.item(id bigint);");
        var input = new FilesInput(project, library, sql);
        var request = request(temp, project, library, identity(project,
                DatabaseType.PG, digest("warm-resume-hit")));

        try (var seeded = ProjectIndexBuildCoordinator.prepare(request,
                input, () -> build(project, "item", List.of()),
                () -> false);
                var publication = seeded.publish(() -> false)) {
            transferAndClose(publication);
        }

        var attempt = ProjectIndexBuildCoordinator.attemptWarm(request,
                input, () -> false, null, false,
                ProjectIndexStoreFactory.PLATFORM);
        try (var restored = attempt.prepared().orElseThrow()) {
            assertTrue(attempt.hit());
            assertTrue(restored.restoredFromDisk());
            assertThrows(IllegalStateException.class,
                    () -> attempt.completeFullBuild(
                            () -> build(project, "must_not_run", List.of())),
                    "a rebuild would drop the index this attempt restored,"
                            + " and drop it unclosed");
        }
    }

    @Test
    void warmOnlyRestoreRejectsStaleAndCorruptState(
            @TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path library = Files.createDirectories(temp.resolve("library"));
        Path sql = Files.writeString(project.resolve("table.sql"),
                "CREATE TABLE app.item(id bigint);");
        var input = new FilesInput(project, library, sql);
        var request = request(temp, project, library, identity(project,
                DatabaseType.PG, digest("warm-only-rejection")));
        Path current = request.storeDirectory().resolve("current");

        try (var prepared = ProjectIndexBuildCoordinator.prepare(request,
                input, () -> build(project, "item", List.of()),
                () -> false);
                var publication = prepared.publish(() -> false)) {
            transferAndClose(publication);
        }

        Files.writeString(sql,
                "CREATE TABLE app.item(id bigint, changed text);");
        List<String> staleTelemetry = new ArrayList<>();
        assertTrue(ProjectIndexBuildCoordinator.tryPrepareWarm(
                request, input, () -> false,
                ProjectIndexTelemetryTestSupport.start(
                        staleTelemetry, Mode.WARM),
                ProjectIndexStoreFactory.PLATFORM).isEmpty());
        assertTrue(Files.isRegularFile(current),
                "a stale input must not delete a potentially reusable revision");
        assertEquals(1, staleTelemetry.size());
        assertTrue(staleTelemetry.getFirst().contains(
                "persistence_status=failed "
                        + "persistence_reason=stale_input"));

        Files.writeString(current, "corrupt");
        List<String> corruptTelemetry = new ArrayList<>();
        assertTrue(ProjectIndexBuildCoordinator.tryPrepareWarm(
                request, input, () -> false,
                ProjectIndexTelemetryTestSupport.start(
                        corruptTelemetry, Mode.WARM),
                ProjectIndexStoreFactory.PLATFORM).isEmpty());
        assertFalse(Files.exists(current));
        assertEquals(1, corruptTelemetry.size());
        assertTrue(corruptTelemetry.getFirst().contains(
                "persistence_status=failed "
                        + "persistence_reason=validation"));
    }

    @Test
    void freshlyWrittenInputIsContentValidatedBeforeAndAfterPublication(
            @TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path library = Files.createDirectories(temp.resolve("library"));
        Path sql = Files.writeString(project.resolve("table.sql"),
                "CREATE TABLE app.item(id bigint);");
        var request = request(temp, project, library, identity(project,
                DatabaseType.PG, digest("unsettled")));
        List<String> lines = new ArrayList<>();
        var telemetry = ProjectIndexTelemetryTestSupport.start(lines,
                Mode.COLD);

        try (var prepared = ProjectIndexBuildCoordinator.prepare(request,
                new FilesInput(project, library, sql),
                () -> build(project, "item", List.of()), () -> false,
                telemetry);
                var publication = prepared.publish(() -> false)) {
            assertTrue(publication.v2Current());
            transferAndClose(publication);
        }

        assertEquals(1, lines.size());
        // One hash for the stamp plus one for each revalidation pass. The
        // modification time of the file is still inside the settle window, so
        // metadata alone may not stand in for a content comparison and an
        // equally sized same-millisecond rewrite cannot be published unseen.
        assertTrue(lines.getFirst().contains(
                "paths_hashed=3 paths_hash_inline=0 paths_hash_reread=3"),
                lines.getFirst());
    }

    @Test
    void transientOpenFailureKeepsTheIndexAndFormatDamageCleansIt(
            @TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path library = Files.createDirectories(temp.resolve("library"));
        Path sql = Files.writeString(project.resolve("table.sql"),
                "CREATE TABLE app.item(id bigint);");
        var input = new FilesInput(project, library, sql);
        var request = request(temp, project, library, identity(project,
                DatabaseType.PG, digest("transient-open-failure")));
        Path current = request.storeDirectory().resolve("current");

        try (var prepared = ProjectIndexBuildCoordinator.prepare(request,
                input, () -> build(project, "item", List.of()),
                () -> false);
                var publication = prepared.publish(() -> false)) {
            transferAndClose(publication);
        }
        assertTrue(Files.isRegularFile(current));

        List<String> transientTelemetry = new ArrayList<>();
        assertTrue(ProjectIndexBuildCoordinator.tryPrepareWarm(
                request, input, () -> false,
                ProjectIndexTelemetryTestSupport.start(
                        transientTelemetry, Mode.WARM),
                directory -> ProjectIndexStoreTestSupport.failOpen(directory,
                        () -> new java.nio.file.AccessDeniedException(
                                directory.toString()))).isEmpty());
        assertTrue(Files.isRegularFile(current),
                "a transient open failure must not delete the index");
        assertEquals(1, transientTelemetry.size());
        assertTrue(transientTelemetry.getFirst().contains(
                "persistence_status=failed persistence_reason=io"));

        var restored = ProjectIndexBuildCoordinator.tryPrepareWarm(
                request, input, () -> false).orElseThrow();
        try (restored;
                var publication = restored.publish(() -> false)) {
            assertTrue(restored.restoredFromDisk(),
                    "the kept index must still restore once the hold is gone");
            transferAndClose(publication);
        }

        List<String> corruptTelemetry = new ArrayList<>();
        assertTrue(ProjectIndexBuildCoordinator.tryPrepareWarm(
                request, input, () -> false,
                ProjectIndexTelemetryTestSupport.start(
                        corruptTelemetry, Mode.WARM),
                directory -> ProjectIndexStoreTestSupport.failOpen(directory,
                        () -> new ProjectIndexFormatException(
                                "simulated damage"))).isEmpty());
        assertFalse(Files.exists(current),
                "verified format damage still cleans the store");
        assertEquals(1, corruptTelemetry.size());
        assertTrue(corruptTelemetry.getFirst().contains(
                "persistence_status=failed persistence_reason=validation"));
    }

    @Test
    void warmOnlyRestoreHonorsCancellation(
            @TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path library = Files.createDirectories(temp.resolve("library"));
        Path sql = Files.writeString(project.resolve("table.sql"),
                "CREATE TABLE app.item(id bigint);");
        var request = request(temp, project, library, identity(project,
                DatabaseType.PG, digest("warm-only-cancel")));

        assertThrows(InterruptedException.class,
                () -> ProjectIndexBuildCoordinator.tryPrepareWarm(
                        request,
                        new FilesInput(project, library, sql),
                        () -> true));
    }

    @Test
    void warmOnlyValidationCancellationKeepsCurrentAndClosesTelemetry(
            @TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path library = Files.createDirectories(temp.resolve("library"));
        Path sql = Files.writeString(project.resolve("table.sql"),
                "CREATE TABLE app.item(id bigint);");
        var input = new FilesInput(project, library, sql);
        var request = request(temp, project, library, identity(project,
                DatabaseType.PG, digest("warm-only-validation-cancel")));
        Path current = request.storeDirectory().resolve("current");

        try (var prepared = ProjectIndexBuildCoordinator.prepare(request,
                input, () -> build(project, "item", List.of()),
                () -> false);
                var publication = prepared.publish(() -> false)) {
            transferAndClose(publication);
        }
        input.modificationStamp.incrementAndGet();
        var validationEntered = new AtomicBoolean();
        ProjectIndexBuildCoordinator.InputSource cancelling =
                new ProjectIndexBuildCoordinator.InputSource() {

                    @Override
                    public List<CurrentFile> inspect()
                            throws java.io.IOException,
                            InterruptedException {
                        return input.inspect();
                    }

                    @Override
                    public List<ProjectFileStamp> hashAll(
                            List<CurrentFile> files,
                            BooleanSupplier cancelled)
                            throws InterruptedException {
                        validationEntered.set(true);
                        throw new InterruptedException(
                                "expected validation cancellation");
                    }

                    @Override
                    public ProjectIndexPathResolver resolver() {
                        return input.resolver();
                    }
                };
        List<String> lines = new ArrayList<>();
        var telemetry = ProjectIndexTelemetryTestSupport.start(
                lines, Mode.WARM);

        assertThrows(InterruptedException.class,
                () -> ProjectIndexBuildCoordinator.tryPrepareWarm(
                        request, cancelling, () -> false,
                        telemetry, ProjectIndexStoreFactory.PLATFORM));

        assertTrue(validationEntered.get());
        assertTrue(Files.isRegularFile(current));
        assertEquals(1, lines.size());
        assertTrue(lines.getFirst().contains(
                "persistence_status=failed "
                        + "persistence_reason=cancelled"));
    }

    @Test
    void coldBuildPublishesOneAggregateFromNormalOperations(
            @TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path library = Files.createDirectories(temp.resolve("library"));
        Path sql = settled(Files.writeString(project.resolve("table.sql"),
                "CREATE TABLE app.item(id bigint);"));
        var request = request(temp, project, library, identity(project,
                DatabaseType.PG, digest("baseline")));
        List<String> lines = new ArrayList<>();
        var telemetry = ProjectIndexTelemetryTestSupport.start(lines,
                Mode.COLD);

        try (var prepared = ProjectIndexBuildCoordinator.prepare(request,
                new FilesInput(project, library, sql),
                () -> build(project, "item", List.of()), () -> false,
                telemetry);
                var publication = prepared.publish(() -> false)) {
            assertTrue(publication.v2Current());
            transferAndClose(publication);
        }

        assertEquals(1, lines.size());
        String line = lines.getFirst();
        assertTrue(line.contains("mode=cold"));
        assertTrue(line.contains(
                "persistence_status=published persistence_reason=none"));
        assertTrue(line.contains("paths_enumerated=1"));
        assertTrue(line.contains("enumeration_passes=7"));
        assertTrue(line.contains("paths_hashed=1"));
        assertTrue(line.contains("paths_hash_inline=0"));
        assertTrue(line.contains("paths_hash_reread=1"));
        assertTrue(line.contains("paths_parsed=unknown"));
        assertTrue(line.contains("paths_analyzed=unknown"));
        assertFalse(line.contains("paths_excluded="));
        assertTrue(line.contains("block_read_bytes="));
        assertTrue(line.contains(
                "writer_buffer_budget_bytes=67108864"));
        assertTrue(line.contains("writer_packed_bytes="));
        assertFalse(line.contains(project.toString()));
        assertFalse(line.contains("CREATE TABLE"));
    }

    @Test
    void coldUndecidedCloseInvalidatesCurrentAndReportsStale(
            @TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path library = Files.createDirectories(temp.resolve("library"));
        Path sql = Files.writeString(project.resolve("table.sql"),
                "CREATE TABLE app.item(id bigint);");
        var request = request(temp, project, library, identity(project,
                DatabaseType.PG, digest("cold-undecided")));
        Path current = request.storeDirectory().resolve("current");
        List<String> lines = new ArrayList<>();
        var telemetry = ProjectIndexTelemetryTestSupport.start(lines,
                Mode.COLD);

        try (var prepared = ProjectIndexBuildCoordinator.prepare(request,
                new FilesInput(project, library, sql),
                () -> build(project, "item", List.of()), () -> false,
                telemetry);
                var publication = prepared.publish(() -> false)) {
            assertTrue(publication.v2Current());
            assertTrue(Files.isRegularFile(current));
        }

        assertFalse(Files.exists(current));
        assertEquals(1, lines.size());
        assertTrue(lines.getFirst().contains(
                "persistence_status=failed "
                        + "persistence_reason=stale_input"));
        assertFalse(lines.getFirst().contains(
                "persistence_status=published"));
        assertFalse(lines.getFirst().contains(
                "persistence_status=hit"));
    }

    @Test
    void warmUndecidedCloseKeepsInheritedCurrentAndReportsStale(
            @TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path library = Files.createDirectories(temp.resolve("library"));
        Path sql = Files.writeString(project.resolve("table.sql"),
                "CREATE TABLE app.item(id bigint);");
        var input = new FilesInput(project, library, sql);
        var request = request(temp, project, library, identity(project,
                DatabaseType.PG, digest("warm-undecided")));
        Path current = request.storeDirectory().resolve("current");
        ProjectIndexRevision published;
        try (var prepared = ProjectIndexBuildCoordinator.prepare(request,
                input, () -> build(project, "item", List.of()),
                () -> false);
                var publication = prepared.publish(() -> false)) {
            assertTrue(publication.v2Current());
            published = publication.revision();
            transferAndClose(publication);
        }
        assertTrue(Files.isRegularFile(current));
        List<String> lines = new ArrayList<>();
        var telemetry = ProjectIndexTelemetryTestSupport.start(lines,
                Mode.COLD);

        try (var prepared = ProjectIndexBuildCoordinator.prepare(request,
                input, () -> {
                    throw new AssertionError(
                            "warm undecided close ran full build");
                }, () -> false, telemetry);
                var publication = prepared.publish(() -> false)) {
            assertTrue(prepared.restoredFromDisk());
            assertTrue(publication.v2Current());
        }

        // The abandoned build wrote nothing, so the revision it inherited is
        // still the exact revision it validated.
        assertTrue(Files.isRegularFile(current));
        assertEquals(published, currentRevision(request));
        assertEquals(1, lines.size());
        assertTrue(lines.getFirst().contains(
                "persistence_status=failed "
                        + "persistence_reason=stale_input"));
        assertFalse(lines.getFirst().contains(
                "persistence_status=published"));
        assertFalse(lines.getFirst().contains(
                "persistence_status=hit"));
    }

    @Test
    void publicationDecisionIsIdempotentButCannotBeReversed(
            @TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path library = Files.createDirectories(temp.resolve("library"));
        Path sql = Files.writeString(project.resolve("table.sql"),
                "CREATE TABLE app.item(id bigint);");
        var input = new FilesInput(project, library, sql);
        var transferredRequest = request(temp.resolve("transferred"),
                project, library, identity(project, DatabaseType.PG,
                        digest("transferred")));
        Path transferredCurrent =
                transferredRequest.storeDirectory().resolve("current");

        try (var prepared = ProjectIndexBuildCoordinator.prepare(
                transferredRequest, input,
                () -> build(project, "item", List.of()), () -> false);
                var publication = prepared.publish(() -> false)) {
            publication.transferStorage();
            assertDoesNotThrow(publication::transferStorage);
            assertThrows(IllegalStateException.class,
                    () -> publication.reject(
                            PersistenceReason.STALE_INPUT));
            transferAndClose(publication);
            assertThrows(IllegalStateException.class,
                    publication::transferStorage);
            assertThrows(IllegalStateException.class,
                    () -> publication.reject(
                            PersistenceReason.STALE_INPUT));
            assertThrows(IllegalStateException.class,
                    publication::deleteLegacy);
        }
        assertTrue(Files.isRegularFile(transferredCurrent));

        var rejectedRequest = request(temp.resolve("rejected"),
                project, library, identity(project, DatabaseType.PG,
                        digest("rejected")));
        Path rejectedCurrent =
                rejectedRequest.storeDirectory().resolve("current");
        try (var prepared = ProjectIndexBuildCoordinator.prepare(
                rejectedRequest, input,
                () -> build(project, "item", List.of()), () -> false);
                var publication = prepared.publish(() -> false)) {
            publication.reject(PersistenceReason.STALE_INPUT);
            assertDoesNotThrow(() -> publication.reject(
                    PersistenceReason.STALE_INPUT));
            assertThrows(IllegalStateException.class,
                    () -> publication.reject(
                            PersistenceReason.CANCELLED));
            assertThrows(IllegalStateException.class,
                    publication::transferStorage);
            assertThrows(IllegalStateException.class,
                    publication::deleteLegacy);
            publication.close();
            assertThrows(IllegalStateException.class,
                    () -> publication.reject(
                            PersistenceReason.STALE_INPUT));
            assertThrows(IllegalStateException.class,
                    publication::transferStorage);
            assertThrows(IllegalStateException.class,
                    publication::deleteLegacy);
        }
        assertFalse(Files.exists(rejectedCurrent));
    }

    @Test
    void liveGateRejectionInvalidatesDurableRevisionAndReportsStale(
            @TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path library = Files.createDirectories(temp.resolve("library"));
        Path sql = Files.writeString(project.resolve("table.sql"),
                "CREATE TABLE app.item(id bigint);");
        var request = request(temp, project, library, identity(project,
                DatabaseType.PG, digest("live-gate")));
        Path current = request.storeDirectory().resolve("current");
        List<String> lines = new ArrayList<>();
        var telemetry = ProjectIndexTelemetryTestSupport.start(lines,
                Mode.COLD);

        try (var prepared = ProjectIndexBuildCoordinator.prepare(request,
                new FilesInput(project, library, sql),
                () -> build(project, "item", List.of()), () -> false,
                telemetry);
                var publication = prepared.publish(() -> false)) {
            assertTrue(publication.v2Current());
            assertTrue(Files.isRegularFile(current));

            publication.reject(PersistenceReason.STALE_INPUT);
        }

        assertFalse(Files.exists(current));
        assertEquals(1, lines.size());
        assertTrue(lines.getFirst().contains(
                "persistence_status=failed "
                        + "persistence_reason=stale_input"));
        assertFalse(lines.getFirst().contains(
                "persistence_status=published"));
    }

    @Test
    void warmLiveGateRejectionKeepsInheritedCurrentAndReportsStale(
            @TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path library = Files.createDirectories(temp.resolve("library"));
        Path sql = Files.writeString(project.resolve("table.sql"),
                "CREATE TABLE app.item(id bigint);");
        var request = request(temp, project, library, identity(project,
                DatabaseType.PG, digest("warm-live-gate")));
        var input = new FilesInput(project, library, sql);
        Path current = request.storeDirectory().resolve("current");
        ProjectIndexRevision published;
        try (var prepared = ProjectIndexBuildCoordinator.prepare(request,
                input, () -> build(project, "item", List.of()),
                () -> false);
                var publication = prepared.publish(() -> false)) {
            assertTrue(publication.v2Current());
            published = publication.revision();
            transferAndClose(publication);
        }
        assertTrue(Files.isRegularFile(current));
        List<String> lines = new ArrayList<>();
        var telemetry = ProjectIndexTelemetryTestSupport.start(lines,
                Mode.COLD);

        try (var prepared = ProjectIndexBuildCoordinator.prepare(request,
                input, () -> {
                    throw new AssertionError(
                            "warm rejection ran full build");
                }, () -> false, telemetry);
                var publication = prepared.publish(() -> false)) {
            assertTrue(prepared.restoredFromDisk());
            assertTrue(publication.v2Current());

            publication.reject(PersistenceReason.STALE_INPUT);
        }

        // Losing a race with the live gate is not damage: this build inherited
        // the revision and never wrote to the store.
        assertTrue(Files.isRegularFile(current));
        assertEquals(published, currentRevision(request));
        assertEquals(1, lines.size());
        assertTrue(lines.getFirst().contains(
                "persistence_status=failed "
                        + "persistence_reason=stale_input"));
        assertFalse(lines.getFirst().contains(
                "persistence_status=hit"));
    }

    @Test
    void warmRejectionForAnyOtherReasonStillInvalidatesCurrent(
            @TempDir Path temp) throws Exception {
        for (PersistenceReason reason : PersistenceReason.values()) {
            if (reason == PersistenceReason.NONE
                    || reason == PersistenceReason.STALE_INPUT) {
                continue;
            }
            Path root = Files.createDirectories(
                    temp.resolve(reason.name()));
            Path project = Files.createDirectories(
                    root.resolve("project"));
            Path library = Files.createDirectories(
                    root.resolve("library"));
            Path sql = Files.writeString(project.resolve("table.sql"),
                    "CREATE TABLE app.item(id bigint);");
            var request = request(root, project, library,
                    identity(project, DatabaseType.PG,
                            digest("warm-other-" + reason.name())));
            var input = new FilesInput(project, library, sql);
            Path current = request.storeDirectory().resolve("current");
            try (var prepared = ProjectIndexBuildCoordinator.prepare(
                    request, input,
                    () -> build(project, "item", List.of()),
                    () -> false);
                    var publication = prepared.publish(() -> false)) {
                transferAndClose(publication);
            }
            assertTrue(Files.isRegularFile(current));

            try (var prepared = ProjectIndexBuildCoordinator.prepare(
                    request, input, () -> {
                        throw new AssertionError(
                                "warm rejection ran full build");
                    }, () -> false);
                    var publication = prepared.publish(() -> false)) {
                assertTrue(prepared.restoredFromDisk());
                publication.reject(reason);
            }

            assertFalse(Files.exists(current),
                    "rejection by " + reason
                            + " must keep invalidating the revision");
        }
    }

    @Test
    void racedWarmRejectionLeavesTheIndexReusableByTheNextBuild(
            @TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path library = Files.createDirectories(temp.resolve("library"));
        Path sql = Files.writeString(project.resolve("table.sql"),
                "CREATE TABLE app.item(id bigint);");
        var request = request(temp, project, library, identity(project,
                DatabaseType.PG, digest("raced-warm-reuse")));
        var input = new FilesInput(project, library, sql);
        var fullBuilds = new AtomicInteger();
        ProjectIndexBuildCoordinator.FullBuild fullBuild = () -> {
            fullBuilds.incrementAndGet();
            return build(project, "item", List.of());
        };

        try (var prepared = ProjectIndexBuildCoordinator.prepare(request,
                input, fullBuild, () -> false);
                var publication = prepared.publish(() -> false)) {
            transferAndClose(publication);
        }
        assertEquals(1, fullBuilds.get());

        try (var prepared = ProjectIndexBuildCoordinator.prepare(request,
                input, fullBuild, () -> false);
                var publication = prepared.publish(() -> false)) {
            assertTrue(prepared.restoredFromDisk());
            publication.reject(PersistenceReason.STALE_INPUT);
        }

        // The build after the race must still restore from disk. Before the
        // relaxation this run was a cold rebuild of the whole project.
        try (var restarted = ProjectIndexBuildCoordinator.prepare(request,
                input, fullBuild, () -> false);
                var publication = restarted.publish(() -> false)) {
            assertTrue(restarted.restoredFromDisk());
            assertEquals("item", publication.storage()
                    .definitionsForPath(sql.toString()).getFirst()
                    .getName());
            transferAndClose(publication);
        }
        assertEquals(1, fullBuilds.get());
    }

    @Test
    void coldBuildUsesCapturedFingerprintsWithoutSecondHashRead(
            @TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(
                temp.resolve("project"));
        Path library = Files.createDirectories(
                temp.resolve("library"));
        Path sql = settled(Files.writeString(project.resolve("table.sql"),
                "CREATE TABLE app.item(id bigint);"));
        var delegate = new FilesInput(project, library, sql);
        var hashReads = new AtomicInteger();
        ProjectIndexBuildCoordinator.InputSource inputs =
                countingHashes(delegate, hashReads);
        var request = request(temp, project, library,
                identity(project, DatabaseType.PG,
                        digest("captured")));
        byte[] bytes = Files.readAllBytes(sql);
        var fingerprint = new ProjectInputFingerprint(
                sql, bytes.length,
                java.security.MessageDigest
                        .getInstance("SHA-256")
                        .digest(bytes));

        List<String> lines = new ArrayList<>();
        var telemetry = ProjectIndexTelemetryTestSupport.start(
                lines, Mode.COLD);
        try (var prepared =
                ProjectIndexBuildCoordinator.prepare(
                        request, inputs,
                        () -> {
                            var built = build(project,
                                    "item", List.of());
                            return new ProjectIndexBuildCoordinator
                                    .BuildResult(
                                            built.storage(),
                                            built.errors(),
                                            List.of(fingerprint));
                        }, () -> false, telemetry);
                var publication =
                        prepared.publish(() -> false)) {
            assertTrue(publication.v2Current());
            transferAndClose(publication);
        }

        assertEquals(0, hashReads.get());
        assertEquals(1, lines.size());
        assertTrue(lines.getFirst().contains(
                "paths_hashed=1 paths_hash_inline=1 "
                        + "paths_hash_reread=0"));
    }

    @Test
    void unsupportedCaptureFallsBackToExistingHashAll(
            @TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(
                temp.resolve("project"));
        Path library = Files.createDirectories(
                temp.resolve("library"));
        Path sql = settled(Files.writeString(project.resolve("table.sql"),
                "CREATE TABLE app.item(id bigint);"));
        var delegate = new FilesInput(project, library, sql);
        var hashReads = new AtomicInteger();
        ProjectIndexBuildCoordinator.InputSource inputs =
                countingHashes(delegate, hashReads);
        var request = request(temp, project, library,
                identity(project, DatabaseType.PG,
                        digest("fallback")));

        List<String> lines = new ArrayList<>();
        var telemetry = ProjectIndexTelemetryTestSupport.start(
                lines, Mode.COLD);
        try (var prepared =
                ProjectIndexBuildCoordinator.prepare(
                        request, inputs,
                        () -> build(project, "item",
                                List.of()),
                        () -> false, telemetry);
                var publication =
                        prepared.publish(() -> false)) {
            assertTrue(publication.v2Current());
            transferAndClose(publication);
        }

        assertEquals(1, hashReads.get());
        assertEquals(1, lines.size());
        assertTrue(lines.getFirst().contains(
                "paths_hashed=1 paths_hash_inline=0 "
                        + "paths_hash_reread=1"));
    }

    @Test
    void capturedAndRereadColdPipelinesProduceIdenticalPackedBytes(
            @TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(
                temp.resolve("project"));
        Path library = Files.createDirectories(
                temp.resolve("library"));
        Path sql = Files.writeString(project.resolve("table.sql"),
                "CREATE TABLE app.item(id bigint);");
        var identity = identity(project, DatabaseType.PG,
                digest("pipeline-parity"));
        byte[] bytes = Files.readAllBytes(sql);
        var fingerprint = new ProjectInputFingerprint(
                sql, bytes.length,
                java.security.MessageDigest
                        .getInstance("SHA-256")
                        .digest(bytes));
        var inlineRequest = request(temp.resolve("inline"),
                project, library, identity);
        var rereadRequest = request(temp.resolve("reread"),
                project, library, identity);

        try (var prepared = ProjectIndexBuildCoordinator.prepare(
                inlineRequest,
                new FilesInput(project, library, sql),
                () -> {
                    var built = build(project, "item",
                            List.of());
                    return new ProjectIndexBuildCoordinator.BuildResult(
                            built.storage(), built.errors(),
                            List.of(fingerprint));
                }, () -> false);
                var publication = prepared.publish(() -> false)) {
            assertTrue(publication.v2Current());
            transferAndClose(publication);
        }
        try (var prepared = ProjectIndexBuildCoordinator.prepare(
                rereadRequest,
                new FilesInput(project, library, sql),
                () -> build(project, "item", List.of()),
                () -> false);
                var publication = prepared.publish(() -> false)) {
            assertTrue(publication.v2Current());
            transferAndClose(publication);
        }

        assertArrayEquals(
                encodedCurrent(inlineRequest),
                encodedCurrent(rereadRequest));
    }

    @Test
    void warmRestartPublishesZeroParseAndAnalyzeCounters(
            @TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path library = Files.createDirectories(temp.resolve("library"));
        Path sql = settled(Files.writeString(project.resolve("table.sql"),
                "CREATE TABLE app.item(id bigint);"));
        var input = new FilesInput(project, library, sql);
        var request = request(temp, project, library, identity(project,
                DatabaseType.PG, digest("baseline")));
        try (var prepared = ProjectIndexBuildCoordinator.prepare(request,
                input, () -> build(project, "item", List.of()), () -> false);
                var publication = prepared.publish(() -> false)) {
            assertTrue(publication.v2Current());
            transferAndClose(publication);
        }
        List<String> lines = new ArrayList<>();
        var telemetry = ProjectIndexTelemetryTestSupport.start(lines,
                Mode.COLD);

        try (var prepared = ProjectIndexBuildCoordinator.prepare(request,
                input, () -> {
                    throw new AssertionError("warm restart ran full build");
                }, () -> false, telemetry);
                var publication = prepared.publish(() -> false)) {
            assertTrue(prepared.restoredFromDisk());
            assertTrue(publication.v2Current());
            transferAndClose(publication);
        }

        assertEquals(1, lines.size());
        String line = lines.getFirst();
        assertTrue(line.contains("mode=warm"));
        assertTrue(line.contains(
                "persistence_status=hit persistence_reason=none"));
        assertTrue(line.contains("paths_enumerated=1"));
        assertTrue(line.contains("enumeration_passes=3"));
        assertTrue(line.contains("paths_hashed=0"));
        assertTrue(line.contains("paths_hash_inline=0"));
        assertTrue(line.contains("paths_hash_reread=0"));
        assertTrue(line.contains("paths_parsed=0"));
        assertTrue(line.contains("paths_analyzed=0"));
        assertTrue(line.contains("block_read_bytes="));
    }

    @Test
    void unsupportedInputsPublishOneBypassAggregate(@TempDir Path temp)
            throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path library = Files.createDirectories(temp.resolve("library"));
        Files.writeString(project.resolve("table.sql"),
                "CREATE TABLE app.item(id bigint);");
        var request = request(temp, project, library, identity(project,
                DatabaseType.PG, digest("baseline")));
        ProjectIndexBuildCoordinator.InputSource unsupported =
                unsupportedInputs();
        List<String> lines = new ArrayList<>();
        var telemetry = ProjectIndexTelemetryTestSupport.start(lines,
                Mode.COLD);

        try (var prepared = ProjectIndexBuildCoordinator.prepare(request,
                unsupported, () -> build(project, "live", List.of()),
                () -> false, telemetry);
                var publication = prepared.publish(() -> false)) {
            assertFalse(publication.v2Current());
        }

        assertEquals(1, lines.size());
        assertTrue(lines.getFirst().contains("mode=bypass"));
        assertTrue(lines.getFirst().contains(
                "bypass_reason=input_inspection_failure"));
        assertTrue(lines.getFirst().contains(
                "core_version=" + FIXTURE_VERSION
                        + " ui_version=" + FIXTURE_VERSION));
        assertTrue(lines.getFirst().contains(
                "paths_enumerated=0 enumeration_passes=0 "
                        + "single_file_validations=0 paths_hashed=0 "
                        + "paths_hash_inline=0 paths_hash_reread=0 "
                        + "paths_parsed=unknown paths_analyzed=unknown"));
        assertFalse(lines.getFirst().contains("paths_excluded="));
        assertFalse(lines.getFirst().contains("block_read_bytes="));
    }

    @Test
    void cancellationClosesTelemetryExactlyOnce(@TempDir Path temp)
            throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path library = Files.createDirectories(temp.resolve("library"));
        Path sql = Files.writeString(project.resolve("table.sql"),
                "CREATE TABLE app.item(id bigint);");
        var request = request(temp, project, library, identity(project,
                DatabaseType.PG, digest("baseline")));
        List<String> lines = new ArrayList<>();
        var telemetry = ProjectIndexTelemetryTestSupport.start(lines,
                Mode.COLD);

        try (var prepared = ProjectIndexBuildCoordinator.prepare(request,
                new FilesInput(project, library, sql),
                () -> build(project, "item", List.of()), () -> false,
                telemetry)) {
            assertThrows(InterruptedException.class,
                    () -> prepared.publish(() -> true));
        }

        assertEquals(1, lines.size());
    }

    @Test
    void fullBuildFailureClosesTelemetryExactlyOnce(@TempDir Path temp)
            throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path library = Files.createDirectories(temp.resolve("library"));
        Path sql = Files.writeString(project.resolve("table.sql"),
                "CREATE TABLE app.item(id bigint);");
        var request = request(temp, project, library, identity(project,
                DatabaseType.PG, digest("baseline")));
        List<String> lines = new ArrayList<>();
        var telemetry = ProjectIndexTelemetryTestSupport.start(lines,
                Mode.COLD);

        assertThrows(java.io.IOException.class,
                () -> ProjectIndexBuildCoordinator.prepare(request,
                        new FilesInput(project, library, sql),
                        () -> {
                            throw new java.io.IOException("full loader");
                        }, () -> false, telemetry));

        assertEquals(1, lines.size());
    }

    /**
     * The other half of the same rule. A build gives up before the full build
     * as well - the working tree can move while it is being enumerated - and
     * such a run still has to publish its line exactly once.
     */
    @Test
    void warmAttemptFailureClosesTelemetryExactlyOnce(@TempDir Path temp)
            throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path library = Files.createDirectories(temp.resolve("library"));
        var request = request(temp, project, library, identity(project,
                DatabaseType.PG, digest("warm-attempt-failure")));
        List<String> lines = new ArrayList<>();
        var telemetry = ProjectIndexTelemetryTestSupport.start(lines,
                Mode.COLD);
        var fullBuilds = new AtomicInteger();

        assertThrows(ProjectIndexBuildCoordinator.StaleBuildException.class,
                () -> ProjectIndexBuildCoordinator.prepare(request,
                        new FailingInspection(project, library),
                        () -> {
                            fullBuilds.incrementAndGet();
                            return build(project, "must_not_run", List.of());
                        }, () -> false, telemetry));

        assertEquals(0, fullBuilds.get(),
                "a tree that moved while it was enumerated is not a tree"
                        + " anything can be built from");
        assertEquals(1, lines.size());
    }

    @Test
    void everyEffectiveIdentityDeltaRejectsWarmStateAndRunsOneFullBuild(
            @TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path library = Files.createDirectories(temp.resolve("library"));
        Path sql = Files.writeString(project.resolve("table.sql"),
                "CREATE TABLE app.item(id bigint);");
        var input = new FilesInput(project, library, sql);
        ProjectIndexIdentity baseline = identity(project, DatabaseType.PG,
                digest("baseline"));
        var baselineRequest = request(temp, project, library, baseline);
        try (var prepared = ProjectIndexBuildCoordinator.prepare(
                baselineRequest, input, () -> build(project, "item", List.of()),
                () -> false);
                var publication = prepared.publish(() -> false)) {
            assertTrue(publication.v2Current());
            transferAndClose(publication);
        }

        // OTHER_VERSION only has to differ from the version the baseline
        // identity carries. It is deliberately not a released coordinate: a
        // literal that happened to equal the product version would leave these
        // two variants identical to the baseline, and the case would go green
        // while asking nothing.
        List<ProjectIndexIdentity> changed = List.of(
                new ProjectIndexIdentity(3, baseline.coreVersion(),
                        baseline.uiVersion(), baseline.databaseType(),
                        baseline.projectIdentity(), baseline.configSha256()),
                new ProjectIndexIdentity(2, OTHER_VERSION,
                        baseline.uiVersion(), baseline.databaseType(),
                        baseline.projectIdentity(), baseline.configSha256()),
                new ProjectIndexIdentity(2, baseline.coreVersion(),
                        OTHER_VERSION, baseline.databaseType(),
                        baseline.projectIdentity(), baseline.configSha256()),
                new ProjectIndexIdentity(2, baseline.coreVersion(),
                        baseline.uiVersion(), DatabaseType.MS,
                        baseline.projectIdentity(), baseline.configSha256()),
                new ProjectIndexIdentity(2, baseline.coreVersion(),
                        baseline.uiVersion(), baseline.databaseType(),
                        baseline.projectIdentity(), digest("global-pref")),
                new ProjectIndexIdentity(2, baseline.coreVersion(),
                        baseline.uiVersion(), baseline.databaseType(),
                        baseline.projectIdentity(), digest("project-override")));

        for (int i = 0; i < changed.size(); i++) {
            Path state = Files.createDirectories(temp.resolve("variant-" + i));
            var seeded = request(state, project, library, baseline);
            try (var prepared = ProjectIndexBuildCoordinator.prepare(seeded,
                    input, () -> build(project, "item", List.of()), () -> false);
                    var publication = prepared.publish(() -> false)) {
                assertTrue(publication.v2Current());
                transferAndClose(publication);
            }
            var fullBuilds = new AtomicInteger();
            var mismatch = request(state, project, library, changed.get(i));
            try (var prepared = ProjectIndexBuildCoordinator.prepare(mismatch,
                    input, () -> {
                        fullBuilds.incrementAndGet();
                        return build(project, "replacement", List.of());
                    }, () -> false)) {
                assertFalse(prepared.restoredFromDisk());
            }
            assertEquals(1, fullBuilds.get(), "identity variant " + i);
        }
    }

    @Test
    void configurationFileDeltaRejectsWarmState(@TempDir Path temp)
            throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path library = Files.createDirectories(temp.resolve("library"));
        Path sql = Files.writeString(project.resolve("table.sql"),
                "CREATE TABLE app.item(id bigint);");
        Files.writeString(project.resolve(".pgcodekeeperignore"), "old");
        var config = new ProjectIndexConfiguration(DatabaseType.PG, false,
                true, false, false, false, "");
        ProjectIndexIdentity before = identity(project, DatabaseType.PG,
                ProjectIndexConfigDigest.calculate(project, config, () -> false));
        var input = new FilesInput(project, library, sql);
        try (var prepared = ProjectIndexBuildCoordinator.prepare(
                request(temp, project, library, before), input,
                () -> build(project, "item", List.of()), () -> false);
                var publication = prepared.publish(() -> false)) {
            assertTrue(publication.v2Current());
            transferAndClose(publication);
        }

        Files.writeString(project.resolve(".pgcodekeeperignore"), "new");
        ProjectIndexIdentity after = identity(project, DatabaseType.PG,
                ProjectIndexConfigDigest.calculate(project, config, () -> false));
        var fullBuilds = new AtomicInteger();
        try (var prepared = ProjectIndexBuildCoordinator.prepare(
                request(temp, project, library, after), input, () -> {
                    fullBuilds.incrementAndGet();
                    return build(project, "replacement", List.of());
                }, () -> false)) {
            assertFalse(prepared.restoredFromDisk());
        }
        assertEquals(1, fullBuilds.get());
    }

    @Test
    void corruptCacheFallsBackToExactlyOneFullBuild(@TempDir Path temp)
            throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path library = Files.createDirectories(temp.resolve("library"));
        Path sql = Files.writeString(project.resolve("table.sql"),
                "CREATE TABLE app.item(id bigint);");
        var input = new FilesInput(project, library, sql);
        var request = request(temp, project, library, identity(project,
                DatabaseType.PG, digest("baseline")));
        try (var prepared = ProjectIndexBuildCoordinator.prepare(request,
                input, () -> build(project, "item", List.of()), () -> false);
                var publication = prepared.publish(() -> false)) {
            assertTrue(publication.v2Current());
            transferAndClose(publication);
        }
        Files.writeString(request.storeDirectory().resolve("current"),
                "corrupt");

        var fullBuilds = new AtomicInteger();
        try (var prepared = ProjectIndexBuildCoordinator.prepare(request,
                input, () -> {
                    fullBuilds.incrementAndGet();
                    return build(project, "replacement", List.of());
                }, () -> false);
                var publication = prepared.publish(() -> false)) {
            assertFalse(prepared.restoredFromDisk());
            assertTrue(publication.v2Current());
            transferAndClose(publication);
        }
        try (var prepared = ProjectIndexBuildCoordinator.prepare(request,
                input, () -> {
                    fullBuilds.incrementAndGet();
                    return build(project, "must_not_run", List.of());
                }, () -> false)) {
            assertTrue(prepared.restoredFromDisk());
        }
        assertEquals(1, fullBuilds.get());
    }

    @Test
    void cancellationDoesNotPublishDiskOrLiveCandidate(@TempDir Path temp)
            throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path library = Files.createDirectories(temp.resolve("library"));
        Path sql = Files.writeString(project.resolve("table.sql"),
                "CREATE TABLE app.item(id bigint);");
        var request = request(temp, project, library, identity(project,
                DatabaseType.PG, digest("baseline")));
        try (var prepared = ProjectIndexBuildCoordinator.prepare(request,
                new FilesInput(project, library, sql),
                () -> build(project, "item", List.of()), () -> false)) {
            assertThrows(InterruptedException.class,
                    () -> prepared.publish(() -> true));
        }
        assertFalse(Files.exists(request.storeDirectory().resolve("current")));
    }

    @Test
    void cancellationImmediatelyAfterAtomicPublishRemovesNewCurrent(
            @TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path library = Files.createDirectories(temp.resolve("library"));
        Path sql = Files.writeString(project.resolve("table.sql"),
                "CREATE TABLE app.item(id bigint);");
        var request = request(temp, project, library, identity(project,
                DatabaseType.PG, digest("baseline")));
        Path current = request.storeDirectory().resolve("current");
        try (var prepared = ProjectIndexBuildCoordinator.prepare(request,
                new FilesInput(project, library, sql),
                () -> build(project, "item", List.of()), () -> false)) {
            assertThrows(InterruptedException.class,
                    () -> prepared.publish(() -> Files.exists(current)));
        }
        assertFalse(Files.exists(current));
    }

    /**
     * A cancelled warm restore keeps the revision it inherited. Such a build
     * opened the published revision, validated it and wrote nothing of its
     * own, so CURRENT still names bytes this same build had just proven sound;
     * being cancelled damaged none of them. Dropping CURRENT there protects
     * nothing and costs the next build a full cold rebuild.
     */
    @Test
    void cancellationAfterAWarmRestoreKeepsTheInheritedRevision(
            @TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path library = Files.createDirectories(temp.resolve("library"));
        Path sql = Files.writeString(project.resolve("table.sql"),
                "CREATE TABLE app.item(id bigint);");
        var input = new FilesInput(project, library, sql);
        var request = request(temp, project, library, identity(project,
                DatabaseType.PG, digest("warm-cancelled")));
        Path current = request.storeDirectory().resolve("current");
        var fullBuilds = new AtomicInteger();
        ProjectIndexBuildCoordinator.FullBuild fullBuild = () -> {
            fullBuilds.incrementAndGet();
            return build(project, "item", List.of());
        };
        ProjectIndexRevision published;
        try (var prepared = ProjectIndexBuildCoordinator.prepare(request,
                input, fullBuild, () -> false);
                var publication = prepared.publish(() -> false)) {
            published = publication.revision();
            transferAndClose(publication);
        }
        assertEquals(1, fullBuilds.get());

        var late = new LateCancellation(input, current);
        try (var prepared = ProjectIndexBuildCoordinator.prepare(request,
                late, fullBuild, () -> false)) {
            assertTrue(prepared.restoredFromDisk());
            late.beginPublication();
            late.assertCancelledInTheIntendedWindow(
                    assertThrows(InterruptedException.class,
                            () -> prepared.publish(late.cancellation())));
        }

        assertTrue(Files.isRegularFile(current));
        assertEquals(published, currentRevision(request));

        // The payoff: the build after the cancellation still starts warm.
        try (var restarted = ProjectIndexBuildCoordinator.prepare(request,
                input, fullBuild, () -> false);
                var publication = restarted.publish(() -> false)) {
            assertTrue(restarted.restoredFromDisk());
            transferAndClose(publication);
        }
        assertEquals(1, fullBuilds.get());
    }

    /**
     * The other half of the same guard. A revision this build published is
     * still removed when the build is cancelled: nothing adopted it, so the
     * pointer would name an index no live view ever stood behind.
     */
    @Test
    void cancellationAfterThisBuildPublishedStillRemovesItsRevision(
            @TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path library = Files.createDirectories(temp.resolve("library"));
        Path sql = Files.writeString(project.resolve("table.sql"),
                "CREATE TABLE app.item(id bigint);");
        var input = new FilesInput(project, library, sql);
        var request = request(temp, project, library, identity(project,
                DatabaseType.PG, digest("cold-cancelled")));
        Path current = request.storeDirectory().resolve("current");

        var late = new LateCancellation(input, current);
        try (var prepared = ProjectIndexBuildCoordinator.prepare(request,
                late, () -> build(project, "item", List.of()),
                () -> false)) {
            assertFalse(prepared.restoredFromDisk());
            late.beginPublication();
            late.assertCancelledInTheIntendedWindow(
                    assertThrows(InterruptedException.class,
                            () -> prepared.publish(late.cancellation())));
        }

        assertFalse(Files.exists(current));
    }

    @Test
    void mutationImmediatelyAfterAtomicPublishRejectsLiveAndRemovesCurrent(
            @TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path library = Files.createDirectories(temp.resolve("library"));
        Path sql = Files.writeString(project.resolve("table.sql"),
                "CREATE TABLE app.item(id bigint);");
        var input = new FilesInput(project, library, sql);
        var request = request(temp, project, library, identity(project,
                DatabaseType.PG, digest("baseline")));
        Path current = request.storeDirectory().resolve("current");
        var mutated = new AtomicBoolean();
        List<String> lines = new ArrayList<>();
        var telemetry = ProjectIndexTelemetryTestSupport.start(lines,
                Mode.COLD);
        BooleanSupplier mutateAfterPublish = () -> {
            if (Files.exists(current)
                    && mutated.compareAndSet(false, true)) {
                try {
                    Files.writeString(sql,
                            "CREATE TABLE app.changed(id bigint);");
                    input.modificationStamp.incrementAndGet();
                } catch (java.io.IOException ex) {
                    throw new AssertionError(ex);
                }
            }
            return false;
        };

        try (var prepared = ProjectIndexBuildCoordinator.prepare(request,
                input, () -> build(project, "stale", List.of()),
                () -> false, telemetry)) {
            assertThrows(ProjectIndexBuildCoordinator.StaleBuildException.class,
                    () -> prepared.publish(mutateAfterPublish));
        }
        assertTrue(mutated.get());
        assertFalse(Files.exists(current));
        assertEquals(1, lines.size());
        assertTrue(lines.getFirst().contains(
                "persistence_status=failed "
                        + "persistence_reason=stale_input"));
    }

    @Test
    void errorsAndUnsupportedInputsKeepLiveIndexButNeverPublishCache(
            @TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path library = Files.createDirectories(temp.resolve("library"));
        Path sql = Files.writeString(project.resolve("table.sql"),
                "CREATE TABLE app.item(id bigint);");
        var request = request(temp, project, library, identity(project,
                DatabaseType.PG, digest("baseline")));
        ProjectIndexBuildCoordinator.InputSource unsupported =
                new ProjectIndexBuildCoordinator.InputSource() {
                    @Override
                    public List<CurrentFile> inspect() {
                        throw new UnsupportedOperationException("unsupported");
                    }

                    @Override
                    public List<ProjectFileStamp> hashAll(
                            List<CurrentFile> files, BooleanSupplier cancelled) {
                        throw new AssertionError();
                    }

                    @Override
                    public ProjectIndexPathResolver resolver() {
                        throw new AssertionError();
                    }
                };

        try (var prepared = ProjectIndexBuildCoordinator.prepare(request,
                unsupported, () -> build(project, "live", List.of()), () -> false);
                var publication = prepared.publish(() -> false)) {
            assertFalse(publication.v2Current());
            assertEquals("live", publication.storage()
                    .definitionsForPath(sql.toString()).getFirst().getName());
        }
        assertFalse(Files.exists(request.storeDirectory().resolve("current")));

        try (var prepared = ProjectIndexBuildCoordinator.prepare(request,
                new FilesInput(project, library, sql),
                () -> build(project, "with_error", List.of("parse error")),
                () -> false);
                var publication = prepared.publish(() -> false)) {
            assertFalse(publication.v2Current());
            assertEquals("with_error", publication.storage()
                    .definitionsForPath(sql.toString()).getFirst().getName());
        }
        assertFalse(Files.exists(request.storeDirectory().resolve("current")));
    }

    @Test
    void snapshotPackingFailurePublishesReasonAndKeepsLiveIndex(
            @TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path library = Files.createDirectories(temp.resolve("library"));
        Path sql = Files.writeString(project.resolve("table.sql"),
                "CREATE TABLE app.item(id bigint);");
        var request = request(temp, project, library, identity(project,
                DatabaseType.PG, digest("baseline")));
        List<String> lines = new ArrayList<>();
        var telemetry = ProjectIndexTelemetryTestSupport.start(lines,
                Mode.COLD);

        try (var prepared = ProjectIndexBuildCoordinator.prepare(request,
                new FilesInput(project, library, sql),
                () -> unsupportedMetadataBuild(project), () -> false,
                telemetry);
                var publication = prepared.publish(() -> false)) {
            assertFalse(publication.v2Current());
            assertEquals(1, publication.storage()
                    .definitionsForPath(sql.toString()).size());
        }

        assertFalse(Files.exists(request.storeDirectory().resolve("current")));
        assertEquals(1, lines.size());
        assertTrue(lines.getFirst().contains("mode=bypass"));
        assertTrue(lines.getFirst().contains(
                "bypass_reason=snapshot_pack_failure"));
        assertTrue(lines.getFirst().contains(
                "persistence_status=failed "
                        + "persistence_reason=validation"));
        assertFalse(lines.getFirst().contains(
                "persistence_status=not_attempted"));
        assertFalse(lines.getFirst().contains(
                "persistence_status=memory_only"));
        assertTrue(lines.getFirst().contains(
                "core_version=" + FIXTURE_VERSION
                        + " ui_version=" + FIXTURE_VERSION));
        assertFalse(lines.getFirst().contains(project.toString()));
        assertFalse(lines.getFirst().contains("CREATE TABLE"));
    }

    @Test
    void persistenceInputIOExceptionFallsBackAndReportsTypedFailure(
            @TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path library = Files.createDirectories(temp.resolve("library"));
        Path sql = Files.writeString(project.resolve("table.sql"),
                "CREATE TABLE secret_schema.secret_table(id bigint);");
        var delegate = new FilesInput(project, library, sql);
        var request = request(temp, project, library, identity(project,
                DatabaseType.PG, digest("input-io")));
        List<String> lines = new ArrayList<>();
        var telemetry = ProjectIndexTelemetryTestSupport.start(lines,
                Mode.COLD);

        try (var prepared = ProjectIndexBuildCoordinator.prepare(request,
                withHash(delegate, (files, cancelled) -> {
                    throw new java.io.IOException(
                            "secret persistence input path");
                }), () -> build(project, "live", List.of()), () -> false,
                telemetry);
                var publication = prepared.publish(() -> false)) {
            assertFalse(publication.v2Current());
            assertFalse(publication.storage()
                    instanceof MemoryProjectReferenceIndex);
            assertEquals("live", publication.storage()
                    .definitionsForPath(sql.toString()).getFirst().getName());
        }

        assertEquals(1, lines.size());
        String line = lines.getFirst();
        assertTrue(line.contains("mode=bypass"));
        assertTrue(line.contains(
                "bypass_reason=persistence_input_failure"));
        assertTrue(line.contains(
                "persistence_status=failed persistence_reason=io"));
        assertFalse(line.contains("persistence_status=not_attempted"));
        assertFalse(line.contains("persistence_status=memory_only"));
        assertFalse(line.contains(project.toString()));
        assertFalse(line.contains("secret_schema"));
        assertFalse(line.contains("secret_table"));
        assertFalse(line.contains("secret persistence input path"));
    }

    @Test
    void typedAuxiliaryLimitFallsBackAndKeepsTypedReason(
            @TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path library = Files.createDirectories(temp.resolve("library"));
        Path sql = Files.writeString(project.resolve("table.sql"),
                "CREATE TABLE app.item(id bigint);");
        var delegate = new FilesInput(project, library, sql);
        var request = request(temp, project, library, identity(project,
                DatabaseType.PG, digest("input-aux")));
        List<String> lines = new ArrayList<>();
        var telemetry = ProjectIndexTelemetryTestSupport.start(lines,
                Mode.COLD);

        try (var prepared = ProjectIndexBuildCoordinator.prepare(request,
                withHash(delegate, (files, cancelled) -> {
                    throw new ProjectIndexPersistenceException(
                            PersistenceReason.AUXILIARY_MEMORY_LIMIT);
                }), () -> build(project, "live", List.of()), () -> false,
                telemetry);
                var publication = prepared.publish(() -> false)) {
            assertFalse(publication.v2Current());
        }

        assertEquals(1, lines.size());
        assertTrue(lines.getFirst().contains(
                "mode=bypass "
                        + "bypass_reason=persistence_input_failure"));
        assertTrue(lines.getFirst().contains(
                "persistence_status=failed "
                        + "persistence_reason=auxiliary_memory_limit"));
        assertFalse(lines.getFirst().contains(
                "persistence_status=memory_only"));
    }

    @Test
    void persistencePreparationCancellationReportsCancelled(
            @TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path library = Files.createDirectories(temp.resolve("library"));
        Path sql = Files.writeString(project.resolve("table.sql"),
                "CREATE TABLE app.item(id bigint);");
        var delegate = new FilesInput(project, library, sql);
        var request = request(temp, project, library, identity(project,
                DatabaseType.PG, digest("input-cancelled")));
        List<String> lines = new ArrayList<>();
        var telemetry = ProjectIndexTelemetryTestSupport.start(lines,
                Mode.COLD);

        assertThrows(InterruptedException.class,
                () -> ProjectIndexBuildCoordinator.prepare(request,
                        withHash(delegate, (files, cancelled) -> {
                            throw new InterruptedException(
                                    "secret cancellation detail");
                        }), () -> build(project, "live", List.of()),
                        () -> false, telemetry));

        assertEquals(1, lines.size());
        assertTrue(lines.getFirst().contains(
                "mode=bypass "
                        + "bypass_reason=persistence_input_failure"));
        assertTrue(lines.getFirst().contains(
                "persistence_status=failed "
                        + "persistence_reason=cancelled"));
        assertFalse(lines.getFirst().contains(
                "secret cancellation detail"));
    }

    @Test
    void optionalInputInspectionFailureFallsBackToOneLiveFullBuild(
            @TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path library = Files.createDirectories(temp.resolve("library"));
        Path sql = Files.writeString(project.resolve("table.sql"),
                "CREATE TABLE app.item(id bigint);");
        var request = request(temp, project, library, identity(project,
                DatabaseType.PG, digest("baseline")));
        var fullBuilds = new AtomicInteger();
        List<String> lines = new ArrayList<>();
        var telemetry = ProjectIndexTelemetryTestSupport.start(lines,
                Mode.COLD);
        ProjectIndexBuildCoordinator.InputSource unreadable =
                new ProjectIndexBuildCoordinator.InputSource() {
                    @Override
                    public List<CurrentFile> inspect()
                            throws java.io.IOException {
                        throw new java.io.IOException(
                                "secret optional metadata");
                    }

                    @Override
                    public List<ProjectFileStamp> hashAll(
                            List<CurrentFile> files,
                            BooleanSupplier cancelled) {
                        throw new AssertionError();
                    }

                    @Override
                    public ProjectIndexPathResolver resolver() {
                        throw new AssertionError();
                    }
                };

        try (var prepared = ProjectIndexBuildCoordinator.prepare(request,
                unreadable, () -> {
                    fullBuilds.incrementAndGet();
                    return build(project, "live", List.of());
                }, () -> false, telemetry);
                var publication = prepared.publish(() -> false)) {
            assertFalse(publication.v2Current());
            assertEquals("live", publication.storage()
                    .definitionsForPath(sql.toString()).getFirst().getName());
        }
        assertEquals(1, fullBuilds.get());
        assertEquals(1, lines.size());
        assertTrue(lines.getFirst().contains(
                "mode=bypass "
                        + "bypass_reason=input_inspection_failure"));
        assertTrue(lines.getFirst().contains(
                "persistence_status=failed persistence_reason=io"));
        assertFalse(lines.getFirst().contains(
                "persistence_status=not_attempted"));
        assertFalse(lines.getFirst().contains(
                "secret optional metadata"));
    }

    @Test
    void fullLoaderIOExceptionStillPropagates(@TempDir Path temp)
            throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path library = Files.createDirectories(temp.resolve("library"));
        Path sql = Files.writeString(project.resolve("table.sql"),
                "CREATE TABLE app.item(id bigint);");
        var request = request(temp, project, library, identity(project,
                DatabaseType.PG, digest("baseline")));

        java.io.IOException failure = assertThrows(java.io.IOException.class,
                () -> ProjectIndexBuildCoordinator.prepare(request,
                        new FilesInput(project, library, sql),
                        () -> {
                            throw new java.io.IOException("full loader");
                        }, () -> false));
        assertEquals("full loader", failure.getMessage());
    }

    @Test
    void mutationDuringHashRejectsStaleLiveAndV2Publication(
            @TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path library = Files.createDirectories(temp.resolve("library"));
        Path sql = Files.writeString(project.resolve("table.sql"),
                "CREATE TABLE app.item(id bigint);");
        var delegate = new FilesInput(project, library, sql);
        ProjectIndexBuildCoordinator.InputSource changing =
                new ProjectIndexBuildCoordinator.InputSource() {
                    @Override
                    public List<CurrentFile> inspect()
                            throws java.io.IOException {
                        return delegate.inspect();
                    }

                    @Override
                    public List<ProjectFileStamp> hashAll(
                            List<CurrentFile> files,
                            BooleanSupplier cancelled)
                            throws java.io.IOException, InterruptedException {
                        List<ProjectFileStamp> result =
                                delegate.hashAll(files, cancelled);
                        Files.writeString(sql,
                                "CREATE TABLE app.changed(id bigint);");
                        delegate.modificationStamp.incrementAndGet();
                        return result;
                    }

                    @Override
                    public ProjectIndexPathResolver resolver() {
                        return delegate.resolver();
                    }
                };
        var request = request(temp, project, library, identity(project,
                DatabaseType.PG, digest("baseline")));
        List<String> lines = new ArrayList<>();
        var telemetry = ProjectIndexTelemetryTestSupport.start(lines,
                Mode.COLD);

        assertThrows(ProjectIndexBuildCoordinator.StaleBuildException.class,
                () -> ProjectIndexBuildCoordinator.prepare(request,
                        changing, () -> build(project, "stale", List.of()),
                        () -> false, telemetry));
        assertFalse(Files.exists(request.storeDirectory().resolve("current")));
        assertEquals(1, lines.size());
        assertTrue(lines.getFirst().contains(
                "mode=bypass "
                        + "bypass_reason=persistence_input_failure"));
        assertTrue(lines.getFirst().contains(
                "persistence_status=failed "
                        + "persistence_reason=stale_input"));
    }

    @Test
    void mutationDuringFullBuildRejectsStaleLiveAndV2Publication(
            @TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path library = Files.createDirectories(temp.resolve("library"));
        Path sql = Files.writeString(project.resolve("table.sql"),
                "CREATE TABLE app.item(id bigint);");
        var input = new FilesInput(project, library, sql);
        var request = request(temp, project, library, identity(project,
                DatabaseType.PG, digest("baseline")));

        assertThrows(ProjectIndexBuildCoordinator.StaleBuildException.class,
                () -> ProjectIndexBuildCoordinator.prepare(request, input,
                        () -> {
                            Files.writeString(sql,
                                    "CREATE TABLE app.changed(id bigint);");
                            input.modificationStamp.incrementAndGet();
                            return build(project, "stale", List.of());
                        }, () -> false));
        assertFalse(Files.exists(request.storeDirectory().resolve("current")));
    }

    /**
     * A full build that loses a race after a warm miss keeps the previously
     * published revision. Such a build only reads, so the store it leaves
     * behind is the store it found, and the next build still starts warm.
     */
    @Test
    void mutationDuringFullBuildKeepsTheAlreadyPublishedRevision(
            @TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path library = Files.createDirectories(temp.resolve("library"));
        Path sql = Files.writeString(project.resolve("table.sql"),
                "CREATE TABLE app.item(id bigint);");
        var input = new FilesInput(project, library, sql);
        var request = request(temp, project, library, identity(project,
                DatabaseType.PG, digest("full-build-race")));
        Path current = request.storeDirectory().resolve("current");
        ProjectIndexRevision published;
        try (var prepared = ProjectIndexBuildCoordinator.prepare(request,
                input, () -> build(project, "item", List.of()),
                () -> false);
                var publication = prepared.publish(() -> false)) {
            published = publication.revision();
            transferAndClose(publication);
        }
        assertTrue(Files.isRegularFile(current));

        // Miss the warm restore first, then move the input again while the
        // full build runs: exactly the shape of a bypassed racing build.
        Files.writeString(sql, "CREATE TABLE app.item(id bigint, a text);");
        input.modificationStamp.incrementAndGet();
        assertThrows(ProjectIndexBuildCoordinator.StaleBuildException.class,
                () -> ProjectIndexBuildCoordinator.prepare(request, input,
                        () -> {
                            Files.writeString(sql,
                                    "CREATE TABLE app.changed(id bigint);");
                            input.modificationStamp.incrementAndGet();
                            return build(project, "stale", List.of());
                        }, () -> false));

        assertTrue(Files.isRegularFile(current));
        assertEquals(published, currentRevision(request));
    }

    @Test
    void effectiveConfigurationMutationDuringFullBuildRejectsLiveAndV2(
            @TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path library = Files.createDirectories(temp.resolve("library"));
        Path sql = Files.writeString(project.resolve("table.sql"),
                "CREATE TABLE app.item(id bigint);");
        var delegate = new FilesInput(project, library, sql);
        var identityCurrent = new AtomicBoolean(true);
        ProjectIndexBuildCoordinator.InputSource changing =
                new ProjectIndexBuildCoordinator.InputSource() {
                    @Override
                    public List<CurrentFile> inspect()
                            throws java.io.IOException {
                        return delegate.inspect();
                    }

                    @Override
                    public List<ProjectFileStamp> hashAll(
                            List<CurrentFile> files,
                            BooleanSupplier cancelled)
                            throws java.io.IOException, InterruptedException {
                        return delegate.hashAll(files, cancelled);
                    }

                    @Override
                    public ProjectIndexPathResolver resolver() {
                        return delegate.resolver();
                    }

                    @Override
                    public void verifyIdentity() throws java.io.IOException {
                        if (!identityCurrent.get()) {
                            throw new ProjectIndexBuildCoordinator.StaleBuildException(
                                    "configuration changed");
                        }
                    }
                };
        var request = request(temp, project, library, identity(project,
                DatabaseType.PG, digest("baseline")));

        assertThrows(ProjectIndexBuildCoordinator.StaleBuildException.class,
                () -> ProjectIndexBuildCoordinator.prepare(request,
                        changing, () -> {
                            identityCurrent.set(false);
                            return build(project, "stale", List.of());
                        }, () -> false));
        assertFalse(Files.exists(request.storeDirectory().resolve("current")));
    }

    @Test
    void mutationAfterPrepareBeforePublishRejectsLiveAndV2(
            @TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path library = Files.createDirectories(temp.resolve("library"));
        Path sql = Files.writeString(project.resolve("table.sql"),
                "CREATE TABLE app.item(id bigint);");
        var input = new FilesInput(project, library, sql);
        var request = request(temp, project, library, identity(project,
                DatabaseType.PG, digest("baseline")));

        try (var prepared = ProjectIndexBuildCoordinator.prepare(request,
                input, () -> build(project, "stale", List.of()),
                () -> false)) {
            Files.writeString(sql, "CREATE TABLE app.changed(id bigint);");
            input.modificationStamp.incrementAndGet();

            assertThrows(ProjectIndexBuildCoordinator.StaleBuildException.class,
                    () -> prepared.publish(() -> false));
        }
        assertFalse(Files.exists(request.storeDirectory().resolve("current")));
    }

    @Test
    void identityMutationAfterPrepareBeforePublishRejectsLiveAndV2(
            @TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path library = Files.createDirectories(temp.resolve("library"));
        Path sql = Files.writeString(project.resolve("table.sql"),
                "CREATE TABLE app.item(id bigint);");
        var delegate = new FilesInput(project, library, sql);
        var identityCurrent = new AtomicBoolean(true);
        ProjectIndexBuildCoordinator.InputSource changing =
                new ProjectIndexBuildCoordinator.InputSource() {
                    @Override
                    public List<CurrentFile> inspect()
                            throws java.io.IOException {
                        return delegate.inspect();
                    }

                    @Override
                    public List<ProjectFileStamp> hashAll(
                            List<CurrentFile> files,
                            BooleanSupplier cancelled)
                            throws java.io.IOException, InterruptedException {
                        return delegate.hashAll(files, cancelled);
                    }

                    @Override
                    public ProjectIndexPathResolver resolver() {
                        return delegate.resolver();
                    }

                    @Override
                    public void verifyIdentity()
                            throws ProjectIndexBuildCoordinator.StaleBuildException {
                        if (!identityCurrent.get()) {
                            throw new ProjectIndexBuildCoordinator.StaleBuildException(
                                    "configuration changed");
                        }
                    }
                };
        var request = request(temp, project, library, identity(project,
                DatabaseType.PG, digest("baseline")));

        try (var prepared = ProjectIndexBuildCoordinator.prepare(request,
                changing, () -> build(project, "stale", List.of()),
                () -> false)) {
            identityCurrent.set(false);

            assertThrows(ProjectIndexBuildCoordinator.StaleBuildException.class,
                    () -> prepared.publish(() -> false));
        }
        assertFalse(Files.exists(request.storeDirectory().resolve("current")));
    }

    @Test
    void diskFailureTransfersMemoryIndexWithOriginalTypedReason(
            @TempDir Path temp)
            throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path library = Files.createDirectories(temp.resolve("library"));
        Path sql = Files.writeString(project.resolve("table.sql"),
                "CREATE TABLE secret_schema.secret_table(id bigint);");
        ProjectIndexIdentity identity = identity(project, DatabaseType.PG,
                digest("baseline"));
        Path invalidStore = Files.writeString(temp.resolve("secret-store-path"),
                "occupied");
        Path legacy = Files.writeString(temp.resolve("legacy.ser"),
                "legacy-sentinel");
        var request = new ProjectIndexBuildCoordinator.Request(invalidStore,
                legacy, project, library, identity, 1);
        List<String> lines = new ArrayList<>();
        var telemetry = ProjectIndexTelemetryTestSupport.start(lines,
                Mode.COLD);
        Throwable persistenceFailure;

        try (var prepared = ProjectIndexBuildCoordinator.prepare(request,
                new FilesInput(project, library, sql),
                () -> build(project, "live", List.of()), () -> false,
                telemetry);
                var publication = prepared.publish(() -> false)) {
            assertFalse(publication.v2Current());
            assertInstanceOf(MemoryProjectReferenceIndex.class,
                    publication.storage());
            persistenceFailure = publication.persistenceFailure();
            assertTrue(persistenceFailure != null);
            assertEquals(PersistenceReason.IO,
                    ProjectIndexBuildCoordinator.persistenceFailureReason(
                            persistenceFailure));
            publication.transferStorage();
            assertSame(persistenceFailure,
                    publication.persistenceFailure());
            assertEquals("live", publication.storage()
                    .definitionsForPath(sql.toString()).getFirst().getName());
            assertNull(publication.deleteLegacy());
            publication.close();
            publication.storage().close();
        }

        assertEquals("occupied", Files.readString(invalidStore));
        assertEquals("legacy-sentinel", Files.readString(legacy));
        assertEquals(1, lines.size());
        String line = lines.getFirst();
        assertTrue(line.contains(
                "persistence_status=memory_only persistence_reason=io"));
        assertFalse(line.contains("persistence_status=published"));
        assertFalse(line.contains(invalidStore.toString()));
        assertFalse(line.contains("secret_schema"));
        assertFalse(line.contains("secret_table"));
        assertFalse(line.contains(persistenceFailure.getMessage()));
    }

    @Test
    void rejectingMemoryPublicationReportsStaleWithoutTouchingStore(
            @TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path library = Files.createDirectories(temp.resolve("library"));
        Path sql = Files.writeString(project.resolve("table.sql"),
                "CREATE TABLE app.item(id bigint);");
        Path invalidStore = Files.writeString(temp.resolve("occupied-store"),
                "store-sentinel");
        var request = new ProjectIndexBuildCoordinator.Request(invalidStore,
                temp.resolve("legacy.ser"), project, library,
                identity(project, DatabaseType.PG, digest("reject-memory")),
                1);
        List<String> lines = new ArrayList<>();
        var telemetry = ProjectIndexTelemetryTestSupport.start(lines,
                Mode.COLD);
        byte[] beforeReject;
        java.nio.file.attribute.FileTime modifiedBeforeReject;

        try (var prepared = ProjectIndexBuildCoordinator.prepare(request,
                new FilesInput(project, library, sql),
                () -> build(project, "live", List.of()), () -> false,
                telemetry);
                var publication = prepared.publish(() -> false)) {
            assertFalse(publication.v2Current());
            assertInstanceOf(MemoryProjectReferenceIndex.class,
                    publication.storage());
            Throwable persistenceFailure =
                    publication.persistenceFailure();
            assertTrue(persistenceFailure != null);
            beforeReject = Files.readAllBytes(invalidStore);
            modifiedBeforeReject =
                    Files.getLastModifiedTime(invalidStore);

            publication.reject(PersistenceReason.STALE_INPUT);

            assertSame(persistenceFailure,
                    publication.persistenceFailure());
            assertDoesNotThrow(() -> publication.reject(
                    PersistenceReason.STALE_INPUT));
            assertThrows(IllegalStateException.class,
                    publication::transferStorage);
        }

        assertArrayEquals(beforeReject, Files.readAllBytes(invalidStore));
        assertEquals(modifiedBeforeReject,
                Files.getLastModifiedTime(invalidStore));
        assertFalse(Files.exists(invalidStore.resolve("current")));
        assertEquals(1, lines.size());
        assertTrue(lines.getFirst().contains(
                "persistence_status=failed "
                        + "persistence_reason=stale_input"));
        assertFalse(lines.getFirst().contains(
                "persistence_status=memory_only"));
    }

    @Test
    void reopenFailureIsTypedAndKeepsFailOpenPublication(
            @TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path library = Files.createDirectories(temp.resolve("library"));
        Path sql = Files.writeString(project.resolve("table.sql"),
                "CREATE TABLE app.item(id bigint);");
        var request = request(temp, project, library, identity(project,
                DatabaseType.PG, digest("reopen")));
        Path current = request.storeDirectory().resolve("current");
        var corrupted = new AtomicBoolean();
        BooleanSupplier corruptAfterPublish = () -> {
            if (Files.isRegularFile(current)
                    && corrupted.compareAndSet(false, true)) {
                try {
                    Files.writeString(current,
                            "secret reopen validation detail");
                } catch (java.io.IOException ex) {
                    throw new AssertionError(ex);
                }
            }
            return false;
        };
        List<String> lines = new ArrayList<>();
        var telemetry = ProjectIndexTelemetryTestSupport.start(lines,
                Mode.COLD);

        try (var prepared = ProjectIndexBuildCoordinator.prepare(request,
                new FilesInput(project, library, sql),
                () -> build(project, "live", List.of()), () -> false,
                telemetry);
                var publication = prepared.publish(corruptAfterPublish)) {
            assertFalse(publication.v2Current());
            assertInstanceOf(MemoryProjectReferenceIndex.class,
                    publication.storage());
            assertTrue(publication.persistenceFailure() != null);
            assertEquals("live", publication.storage()
                    .definitionsForPath(sql.toString()).getFirst().getName());
            transferAndClose(publication);
        }

        assertTrue(corrupted.get());
        assertFalse(Files.exists(current));
        assertEquals(1, lines.size());
        assertTrue(lines.getFirst().contains(
                "persistence_status=memory_only "
                        + "persistence_reason=reopen"));
        assertFalse(lines.getFirst().contains(
                "secret reopen validation detail"));
    }

    @Test
    void uncertainCurrentKeepsIoFailureAndCannotDeleteNewerWinner(
            @TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path library = Files.createDirectories(temp.resolve("library"));
        Path sql = Files.writeString(project.resolve("table.sql"),
                "CREATE TABLE app.item(id bigint);");
        ProjectIndexIdentity identity = identity(project,
                DatabaseType.PG, digest("uncertain-current"));
        var request = request(temp, project, library, identity);
        Path legacy = request.legacyState();
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, "legacy-sentinel");
        var armed = new AtomicBoolean();
        var winner = new java.util.concurrent.atomic.AtomicReference<
                ru.taximaxim.codekeeper.ui.projectindex
                        .ProjectIndexRevision>();
        Runnable publishWinner = () -> {
            if (!armed.compareAndSet(true, false)) {
                return;
            }
            try (var store = new ProjectIndexStore(
                    request.storeDirectory());
                    var opened = store.open(identity)) {
                ProjectIndexData data =
                        opened.view().orElseThrow().materialize();
                winner.set(store.publishWithReceipt(
                        data, () -> false).revision());
            } catch (java.io.IOException ex) {
                throw new UncheckedIOException(ex);
            }
        };
        ProjectIndexStoreFactory storeFactory = directory ->
                ProjectIndexStoreTestSupport
                        .failCurrentDirectorySync(directory,
                                armed::get, publishWinner);
        List<String> lines = new ArrayList<>();
        var telemetry = ProjectIndexTelemetryTestSupport.start(
                lines, Mode.COLD);

        try (var prepared = ProjectIndexBuildCoordinator.prepare(
                request, new FilesInput(project, library, sql),
                () -> build(project, "live", List.of()),
                () -> false, telemetry, false, storeFactory)) {
            armed.set(true);
            try (var publication = prepared.publish(() -> false)) {
                assertFalse(publication.v2Current());
                assertInstanceOf(MemoryProjectReferenceIndex.class,
                        publication.storage());
                var failure = assertInstanceOf(
                        ProjectIndexStore.CurrentDurabilityException.class,
                        publication.persistenceFailure());
                assertTrue(failure.revision() != null);
                assertTrue(winner.get() != null);
                assertFalse(failure.revision().equals(winner.get()));
                publication.transferStorage();
                assertNull(publication.deleteLegacy());
                publication.close();
                publication.storage().close();
            }
        }

        assertEquals("legacy-sentinel", Files.readString(legacy));
        try (var store = new ProjectIndexStore(
                request.storeDirectory());
                var opened = store.open(identity)) {
            assertEquals(Status.HIT, opened.status());
            assertEquals(winner.get(),
                    opened.view().orElseThrow().revision());
        }
        assertEquals(1, lines.size());
        assertTrue(lines.getFirst().contains(
                "persistence_status=memory_only "
                        + "persistence_reason=io"));
        assertFalse(lines.getFirst().contains(
                "persistence_status=published"));
    }

    @Test
    void dependenciesMustBeEmptyAndWellFormedForPersistence(
            @TempDir Path project) throws Exception {
        assertTrue(PgDbParser.isPersistentProjectIndexSupported(project));

        Files.writeString(project.resolve(".dependencies"),
                "<dependencies/>");
        assertTrue(PgDbParser.isPersistentProjectIndexSupported(project));

        Files.writeString(project.resolve(".dependencies"),
                "<dependencies><dependency path=\"lib\""
                        + " ignorePriv=\"false\"/></dependencies>");
        assertFalse(PgDbParser.isPersistentProjectIndexSupported(project));

        Files.delete(project.resolve(".dependencies"));
        Files.createDirectory(project.resolve(".dependencies"));
        assertFalse(PgDbParser.isPersistentProjectIndexSupported(project));
    }

    /**
     * Back-dates a fixture past the warm validator settle window. A file whose
     * modification time can still collide with a further write in the same
     * millisecond is always compared by content, which would otherwise change
     * the hashing counters these tests assert.
     */
    private static Path settled(Path file) throws java.io.IOException {
        Files.setLastModifiedTime(file,
                java.nio.file.attribute.FileTime.fromMillis(
                        System.currentTimeMillis() - 10
                                * ProjectIndexWarmValidator
                                        .MODIFICATION_SETTLE_MILLIS));
        return file;
    }

    private static ProjectIndexBuildCoordinator.Request request(Path state,
            Path project, Path library, ProjectIndexIdentity identity)
            throws Exception {
        return new ProjectIndexBuildCoordinator.Request(
                ProjectIndexState.directory(state, project),
                state.resolve("projects").resolve("legacy.ser"),
                project, library, identity, 1);
    }

    private static void transferAndClose(
            ProjectIndexBuildCoordinator.Publication publication) {
        publication.transferStorage();
        try {
            publication.close();
        } finally {
            publication.storage().close();
        }
    }

    private static ProjectIndexRevision currentRevision(
            ProjectIndexBuildCoordinator.Request request)
            throws Exception {
        try (var store = new ProjectIndexStore(
                request.storeDirectory());
                var opened = store.open(request.identity())) {
            assertEquals(Status.HIT, opened.status());
            return opened.view().orElseThrow().revision();
        }
    }

    private static byte[] encodedCurrent(
            ProjectIndexBuildCoordinator.Request request)
            throws Exception {
        try (var store = new ProjectIndexStore(
                request.storeDirectory());
                var opened = store.open(request.identity())) {
            assertEquals(Status.HIT, opened.status());
            var view = opened.view().orElseThrow();
            var contributions =
                    new ArrayList<FileContribution>();
            for (ProjectFileStamp stamp : view.fileStamps()) {
                contributions.add(view.contribution(
                        stamp.path()).orElseThrow());
            }
            return ProjectIndexCodec.encodeBase(
                    new ProjectIndexData(
                            view.manifest(), contributions));
        }
    }

    private static ProjectIndexIdentity identity(Path project,
            DatabaseType databaseType, byte[] config) throws Exception {
        return new ProjectIndexIdentity(2, FIXTURE_VERSION,
                FIXTURE_VERSION, databaseType,
                ProjectIndexState.projectIdentity(project), config);
    }

    private static ProjectIndexBuildCoordinator.BuildResult build(
            Path project, String name, List<Object> errors) {
        String path = project.resolve("table.sql").toString();
        ObjectReference reference = new ObjectReference("app", name,
                DbObjType.TABLE);
        ObjectLocation location = new ObjectLocation.Builder()
                .setFilePath(path)
                .setOffset(1)
                .setLineNumber(1)
                .setCharPositionInLine(1)
                .setReference(reference)
                .setLocationType(ObjectLocation.LocationType.DEFINITION)
                .build();
        var storage = new ProjectReferencesStorage();
        storage.putReferences(Map.of(path, new ArrayList<>(
                List.of(new MetaStatement(location)))), Map.of());
        return new ProjectIndexBuildCoordinator.BuildResult(storage, errors);
    }

    private static ProjectIndexBuildCoordinator.BuildResult
            unsupportedMetadataBuild(Path project) {
        String path = project.resolve("table.sql").toString();
        ObjectLocation location = new ObjectLocation.Builder()
                .setFilePath(path)
                .setOffset(1)
                .setLineNumber(1)
                .setCharPositionInLine(1)
                .setReference(new ObjectReference("app", "item",
                        DbObjType.TABLE))
                .setLocationType(ObjectLocation.LocationType.DEFINITION)
                .build();
        MetaStatement unsupported = new MetaStatement(location) {
            private static final long serialVersionUID = 1L;
        };
        var storage = new ProjectReferencesStorage();
        storage.putReferences(Map.of(path,
                new ArrayList<>(List.of(unsupported))), Map.of());
        return new ProjectIndexBuildCoordinator.BuildResult(storage,
                List.of());
    }

    private static IndexPathRef projectPath(String relativePath) {
        return new IndexPathRef(IndexPathOrigin.PROJECT, relativePath);
    }

    private static byte[] digest(String value) throws Exception {
        return java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static ProjectIndexBuildCoordinator.InputSource
            unsupportedInputs() {
        return new ProjectIndexBuildCoordinator.InputSource() {
            @Override
            public List<CurrentFile> inspect() {
                throw new UnsupportedOperationException("unsupported");
            }

            @Override
            public List<ProjectFileStamp> hashAll(
                    List<CurrentFile> files, BooleanSupplier cancelled) {
                throw new AssertionError();
            }

            @Override
            public ProjectIndexPathResolver resolver() {
                throw new AssertionError();
            }
        };
    }

    private static ProjectIndexBuildCoordinator.InputSource
            countingHashes(FilesInput delegate,
                    AtomicInteger hashReads) {
        return new ProjectIndexBuildCoordinator.InputSource() {
            @Override
            public List<CurrentFile> inspect()
                    throws java.io.IOException {
                return delegate.inspect();
            }

            @Override
            public List<ProjectFileStamp> hashAll(
                    List<CurrentFile> files,
                    BooleanSupplier cancelled)
                    throws java.io.IOException,
                    InterruptedException {
                hashReads.incrementAndGet();
                return delegate.hashAll(files, cancelled);
            }

            @Override
            public ProjectIndexPathResolver resolver() {
                return delegate.resolver();
            }
        };
    }

    @FunctionalInterface
    private interface HashAction {

        List<ProjectFileStamp> apply(List<CurrentFile> files,
                BooleanSupplier cancelled)
                throws java.io.IOException, InterruptedException;
    }

    private static ProjectIndexBuildCoordinator.InputSource withHash(
            FilesInput delegate, HashAction action) {
        return new ProjectIndexBuildCoordinator.InputSource() {
            @Override
            public List<CurrentFile> inspect()
                    throws java.io.IOException {
                return delegate.inspect();
            }

            @Override
            public List<ProjectFileStamp> hashAll(
                    List<CurrentFile> files,
                    BooleanSupplier cancelled)
                    throws java.io.IOException,
                    InterruptedException {
                return action.apply(files, cancelled);
            }

            @Override
            public ProjectIndexPathResolver resolver() {
                return delegate.resolver();
            }
        };
    }

    /**
     * Delivers a cancellation in the one window where a build has decided
     * everything except whether it may hand its result out: after the closing
     * validation of the publication has passed and before the result leaves
     * {@code publish}. Production cancellation is owned by a monitor another
     * thread writes, so it can flip at any instant, this window included --
     * the store is reopened inside it, which is not a short moment.
     * <p>
     * The window opens at the last input call of that validation:
     * {@code verifyIdentity} seen twice while CURRENT already exists, which is
     * the second call of the validation that follows a publication and the
     * only validation at all after a warm restore. Exactly one cancellation
     * probe stands between that call and the branch under test -- the one
     * closing the validation -- so the first probe after arming still answers
     * false. That count is asserted rather than assumed: a probe added in
     * between would silently move the test to another branch.
     */
    private static final class LateCancellation
            implements ProjectIndexBuildCoordinator.InputSource {

        private final ProjectIndexBuildCoordinator.InputSource delegate;
        private final Path current;
        private final AtomicInteger identityChecks = new AtomicInteger();
        private final AtomicInteger probesAfterArming = new AtomicInteger();
        private final AtomicBoolean armed = new AtomicBoolean();

        private LateCancellation(
                ProjectIndexBuildCoordinator.InputSource delegate,
                Path current) {
            this.delegate = delegate;
            this.current = current;
        }

        /** Forgets what the preparation did, so only publication counts. */
        private void beginPublication() {
            identityChecks.set(0);
            probesAfterArming.set(0);
            armed.set(false);
        }

        private BooleanSupplier cancellation() {
            return () -> armed.get()
                    && probesAfterArming.incrementAndGet() > 1;
        }

        /**
         * Proves the cancellation landed in the intended window rather than at
         * some earlier probe. Counting probes cannot prove it -- an earlier
         * window answers the same two times -- but every cancellation point
         * names itself: the warm validator says "validation", the coordinator's
         * own checks say "build", and only the branch under test says
         * "publication". The one other branch with that wording is the store
         * reporting its own publication cancelled, and it cannot be armed here:
         * arming needs CURRENT, which exists only once that publication
         * succeeded, and after a warm restore there is no publication at all.
         */
        private void assertCancelledInTheIntendedWindow(
                InterruptedException cancelled) {
            assertTrue(armed.get(),
                    "the publication validation never closed");
            assertEquals("Project index publication cancelled",
                    cancelled.getMessage(),
                    "cancellation was observed before the publication branch");
            assertEquals(2, probesAfterArming.get(),
                    "cancellation must be answered exactly twice after the "
                            + "validation: once for the check closing it, "
                            + "once for the branch under test");
        }

        @Override
        public List<CurrentFile> inspect()
                throws java.io.IOException, InterruptedException {
            return delegate.inspect();
        }

        @Override
        public List<ProjectFileStamp> hashAll(List<CurrentFile> files,
                BooleanSupplier cancelled)
                throws java.io.IOException, InterruptedException {
            return delegate.hashAll(files, cancelled);
        }

        @Override
        public ProjectIndexPathResolver resolver() {
            return delegate.resolver();
        }

        @Override
        public void verifyIdentity()
                throws java.io.IOException, InterruptedException {
            delegate.verifyIdentity();
            if (Files.exists(current)
                    && identityChecks.incrementAndGet() == 2) {
                armed.set(true);
            }
        }
    }

    /**
     * A working tree of several files whose membership changes between
     * enumerations, so that one validation can be shown a changed, an added
     * and a removed path at once.
     *
     * <p>No file carries an Eclipse modification stamp, so every path the
     * index still claims is compared by content and the outcome never depends
     * on how recently the test wrote it.</p>
     */
    private static final class TreeInput
            implements ProjectIndexBuildCoordinator.InputSource {

        private final Path project;
        private final Path library;
        private volatile List<Path> files;

        private TreeInput(Path project, Path library, Path... files) {
            this.project = project;
            this.library = library;
            this.files = List.of(files);
        }

        /** Replaces what the next enumeration of this tree will report. */
        private void enumerate(Path... value) {
            files = List.of(value);
        }

        @Override
        public List<CurrentFile> inspect() throws java.io.IOException {
            return ProjectIndexFiles.inspect(files, project, library,
                    path -> -1);
        }

        @Override
        public List<ProjectFileStamp> hashAll(List<CurrentFile> current,
                BooleanSupplier cancelled)
                throws java.io.IOException, InterruptedException {
            return ProjectIndexFiles.hashAll(current, resolver(), cancelled);
        }

        @Override
        public ProjectIndexPathResolver resolver() {
            return path -> (path.origin() == IndexPathOrigin.PROJECT
                    ? project : library).resolve(path.relativePath()).toString();
        }
    }

    /**
     * A working tree that reports it moved the moment it is enumerated, which
     * is what an Eclipse project does when a file lands during a build.
     */
    private static final class FailingInspection
            implements ProjectIndexBuildCoordinator.InputSource {

        private final Path project;
        private final Path library;

        private FailingInspection(Path project, Path library) {
            this.project = project;
            this.library = library;
        }

        @Override
        public List<CurrentFile> inspect() throws java.io.IOException {
            throw new ProjectIndexBuildCoordinator.StaleBuildException(
                    "Project inputs changed while they were enumerated");
        }

        @Override
        public List<ProjectFileStamp> hashAll(List<CurrentFile> files,
                BooleanSupplier cancelled) {
            throw new AssertionError("nothing may be hashed");
        }

        @Override
        public ProjectIndexPathResolver resolver() {
            return path -> (path.origin() == IndexPathOrigin.PROJECT
                    ? project : library).resolve(path.relativePath()).toString();
        }
    }

    /**
     * Counts how often a build enumerated the working tree, and passes
     * everything else through untouched.
     */
    private static final class CountingInput
            implements ProjectIndexBuildCoordinator.InputSource {

        private final ProjectIndexBuildCoordinator.InputSource delegate;
        private final AtomicInteger inspections = new AtomicInteger();

        private CountingInput(
                ProjectIndexBuildCoordinator.InputSource delegate) {
            this.delegate = delegate;
        }

        private int inspections() {
            return inspections.get();
        }

        @Override
        public List<CurrentFile> inspect()
                throws java.io.IOException, InterruptedException {
            inspections.incrementAndGet();
            return delegate.inspect();
        }

        @Override
        public List<ProjectFileStamp> hashAll(List<CurrentFile> files,
                BooleanSupplier cancelled)
                throws java.io.IOException, InterruptedException {
            return delegate.hashAll(files, cancelled);
        }

        @Override
        public ProjectIndexPathResolver resolver() {
            return delegate.resolver();
        }

        @Override
        public void verifyIdentity()
                throws java.io.IOException, InterruptedException {
            delegate.verifyIdentity();
        }
    }

    private static final class FilesInput
            implements ProjectIndexBuildCoordinator.InputSource {

        private final Path project;
        private final Path library;
        private final Path sql;
        private final AtomicLong modificationStamp = new AtomicLong(1);

        private FilesInput(Path project, Path library, Path sql) {
            this.project = project;
            this.library = library;
            this.sql = sql;
        }

        @Override
        public List<CurrentFile> inspect() throws java.io.IOException {
            return ProjectIndexFiles.inspect(List.of(sql), project, library,
                    path -> path.origin() == IndexPathOrigin.PROJECT
                            ? modificationStamp.get() : -1);
        }

        @Override
        public List<ProjectFileStamp> hashAll(List<CurrentFile> files,
                BooleanSupplier cancelled)
                throws java.io.IOException, InterruptedException {
            return ProjectIndexFiles.hashAll(files, resolver(), cancelled);
        }

        @Override
        public ProjectIndexPathResolver resolver() {
            return path -> (path.origin() == IndexPathOrigin.PROJECT
                    ? project : library).resolve(path.relativePath()).toString();
        }
    }
}
