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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.stubbing.Answer;
import org.pgcodekeeper.core.api.ComparisonDepth;
import org.pgcodekeeper.core.database.api.IDatabaseProvider;
import org.pgcodekeeper.core.database.api.jdbc.ISupportedVersion;
import org.pgcodekeeper.core.database.api.loader.ILoader;
import org.pgcodekeeper.core.database.api.loader.ILoaderFactory;
import org.pgcodekeeper.core.database.api.loader.IProjectLoader;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.base.loader.AbstractProjectLoader;
import org.pgcodekeeper.core.database.base.loader.LoaderFactories;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.database.pg.jdbc.PgSupportedVersion;
import org.pgcodekeeper.core.library.Library;
import org.pgcodekeeper.core.library.LibraryXmlStore;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.settings.ISettings;

import ru.taximaxim.codekeeper.ui.DatabaseType;
import ru.taximaxim.codekeeper.ui.projectindex.IndexPathOrigin;
import ru.taximaxim.codekeeper.ui.projectindex.IndexPathRef;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexWarmValidator.CurrentFile;
import ru.taximaxim.codekeeper.ui.settings.EclipseComparisonTelemetry;
import ru.taximaxim.codekeeper.ui.settings.EclipseComparisonTelemetry.ProjectInputChangeStage;
import ru.taximaxim.codekeeper.ui.settings.EclipseComparisonTelemetry.ProjectModelCacheStatus;
import ru.taximaxim.codekeeper.ui.settings.EclipseComparisonTelemetry.ProjectModelFailClosedReason;
import ru.taximaxim.codekeeper.ui.settings.EclipseComparisonTelemetry.UiStage;
import ru.taximaxim.codekeeper.ui.utils.UIMonitor;

class ReusableProjectComparisonTest {

    @Test
    void changedPathReportsInsertedFileInsteadOfShiftedNeighbor() {
        CurrentFile first = currentFile(
                "SCHEMA/app/TABLE/a.sql"); //$NON-NLS-1$
        CurrentFile inserted = currentFile(
                "SCHEMA/app/TABLE/b.sql"); //$NON-NLS-1$
        CurrentFile last = currentFile(
                "SCHEMA/app/TABLE/c.sql"); //$NON-NLS-1$

        assertEquals(inserted.path().relativePath(),
                ReusableProjectComparison.firstChangedPath(
                        List.of(first, last),
                        List.of(first, inserted, last)));
    }

    @Test
    void changedPathReportsRemovedFileInsteadOfShiftedNeighbor() {
        CurrentFile first = currentFile(
                "SCHEMA/app/TABLE/a.sql"); //$NON-NLS-1$
        CurrentFile removed = currentFile(
                "SCHEMA/app/TABLE/b.sql"); //$NON-NLS-1$
        CurrentFile last = currentFile(
                "SCHEMA/app/TABLE/c.sql"); //$NON-NLS-1$

        assertEquals(removed.path().relativePath(),
                ReusableProjectComparison.firstChangedPath(
                        List.of(first, removed, last),
                        List.of(first, last)));
    }

    @Test
    void coldPublicationBecomesWarmWhileRemoteIsAlwaysFresh(
            @TempDir Path root) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(
                root.resolve("project"), monitor);
        var provider = new PgDatabaseProvider();
        var remoteCreates = new AtomicInteger();
        var telemetry = mock(
                EclipseComparisonTelemetry.class);
        Path remote = root.resolve("remote.sql");
        Files.writeString(remote, """
                CREATE SCHEMA app;
                CREATE TABLE app.item (id bigint);
                """);
        try (var reusable = new ReusableProjectComparison()) {
            writeProject(project);
            PgDbParser parser = PgDbParser.getParserForBuilder(
                    project, new int[] {
                            IncrementalProjectBuilder.FULL_BUILD });
            parser.prepareProjectIndex(project, monitor)
                    .update().commit(project.getName(), monitor);
            Path projectRoot = project.getLocation()
                    .toFile().toPath();

            var first = reusable.load(
                    project, DatabaseType.PG, provider,
                    projectRoot,
                    remoteFactory(provider, remote, remoteCreates),
                    settings(monitor, telemetry),
                    "project", "remote",
                    monitor, false).orElseThrow();
            verify(telemetry, times(1)).uiStageFinished(
                    eq(UiStage.MODEL_VALIDATE), anyLong());
            Object firstRemote =
                    first.result().newLoader().getDatabase();
            assertFalse(first.reused());
            var firstPublication = first.publish();
            assertTrue(firstPublication.accepted());
            try (var displayed = firstPublication
                    .displayLease().orElseThrow()) {
                assertTrue(firstRemote != null);
            }

            Files.writeString(remote, """
                    CREATE SCHEMA app;
                    CREATE TABLE app.item (
                        id bigint,
                        code text
                    );
                    """);
            clearInvocations(telemetry);
            var second = reusable.load(
                    project, DatabaseType.PG, provider,
                    projectRoot,
                    remoteFactory(provider, remote, remoteCreates),
                    settings(monitor, telemetry),
                    "project", "remote",
                    monitor, false).orElseThrow();
            verify(telemetry, times(1)).uiStageFinished(
                    eq(UiStage.MODEL_VALIDATE), anyLong());

            assertTrue(second.reused());
            assertNotSame(firstRemote,
                    second.result().newLoader().getDatabase());
            var secondPublication = second.publish();
            assertTrue(secondPublication.accepted());
            try (var displayed = secondPublication
                    .displayLease().orElseThrow()) {
                assertFalse(second.isCurrent(),
                        "published preparations are terminal");
            }
            assertEquals(2, remoteCreates.get(),
                    "NEW must be constructed for every comparison");
        } finally {
            cleanup(project, monitor);
        }
    }

    /**
     * A project ignore list is contributed to the comparison settings by the
     * loader factories, so it is present in the settings a model is captured
     * from and absent from the caller settings the next run looks the model up
     * with. The reuse key must be built from the same configured state on both
     * sides, or no project that ships a {@code .pgcodekeeperignore} can ever
     * reuse its analyzed model.
     */
    @Test
    void projectIgnoreListDoesNotDefeatModelReuse(@TempDir Path root)
            throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(
                root.resolve("ignore-list-project"), monitor);
        var provider = new PgDatabaseProvider();
        var telemetry = mock(EclipseComparisonTelemetry.class);
        Path remote = root.resolve("ignore-list-remote.sql");
        Files.writeString(remote, """
                CREATE SCHEMA app;
                CREATE TABLE app.item (id bigint);
                """);
        try (var reusable = new ReusableProjectComparison()) {
            writeProject(project);
            Path projectRoot = project.getLocation().toFile().toPath();
            Files.writeString(
                    projectRoot.resolve(AbstractProjectLoader.IGNORE_FILE),
                    """
                    SHOW ALL
                    HIDE REGEX '^tmp_' type=TABLE
                    """);
            Files.writeString(
                    projectRoot.resolve(
                            AbstractProjectLoader.IGNORE_SCHEMA_FILE),
                    """
                    SHOW ALL
                    HIDE NONE stage
                    """);
            project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
            PgDbParser parser = PgDbParser.getParserForBuilder(
                    project, new int[] {
                            IncrementalProjectBuilder.FULL_BUILD });
            parser.prepareProjectIndex(project, monitor)
                    .update().commit(project.getName(), monitor);

            var first = reusable.load(
                    project, DatabaseType.PG, provider, projectRoot,
                    remoteFactory(provider, remote, new AtomicInteger()),
                    settings(monitor, telemetry), "project", "remote",
                    monitor, false).orElseThrow();
            assertFalse(first.reused());
            try (var displayed = first.publish()
                    .displayLease().orElseThrow()) {
                // the model is retained for the next comparison
            }

            var second = reusable.load(
                    project, DatabaseType.PG, provider, projectRoot,
                    remoteFactory(provider, remote, new AtomicInteger()),
                    settings(monitor, telemetry), "project", "remote",
                    monitor, false).orElseThrow();
            assertTrue(second.reused(),
                    "a project ignore list must not defeat model reuse");
            second.publish();
        } finally {
            cleanup(project, monitor);
        }
    }

    /**
     * Editing the ignore list between comparisons changes the schemas that are
     * dropped before parsing, so the retained model must not be reused.
     */
    @Test
    void editedIgnoreListRetiresTheReusableModel(@TempDir Path root)
            throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(
                root.resolve("edited-ignore-project"), monitor);
        var provider = new PgDatabaseProvider();
        var telemetry = mock(EclipseComparisonTelemetry.class);
        Path remote = root.resolve("edited-ignore-remote.sql");
        Files.writeString(remote, """
                CREATE SCHEMA app;
                CREATE TABLE app.item (id bigint);
                """);
        try (var reusable = new ReusableProjectComparison()) {
            writeProject(project);
            Path projectRoot = project.getLocation().toFile().toPath();
            Path ignoreFile =
                    projectRoot.resolve(AbstractProjectLoader.IGNORE_FILE);
            Files.writeString(ignoreFile, """
                    SHOW ALL
                    HIDE REGEX '^tmp_' type=TABLE
                    """);
            project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
            PgDbParser parser = PgDbParser.getParserForBuilder(
                    project, new int[] {
                            IncrementalProjectBuilder.FULL_BUILD });
            parser.prepareProjectIndex(project, monitor)
                    .update().commit(project.getName(), monitor);

            var first = reusable.load(
                    project, DatabaseType.PG, provider, projectRoot,
                    remoteFactory(provider, remote, new AtomicInteger()),
                    settings(monitor, telemetry), "project", "remote",
                    monitor, false).orElseThrow();
            assertFalse(first.reused());
            try (var displayed = first.publish()
                    .displayLease().orElseThrow()) {
                // the model is retained for the next comparison
            }

            Files.writeString(ignoreFile, """
                    SHOW ALL
                    HIDE REGEX '^stage_' type=TABLE
                    """);
            project.refreshLocal(IResource.DEPTH_INFINITE, monitor);

            var second = reusable.load(
                    project, DatabaseType.PG, provider, projectRoot,
                    remoteFactory(provider, remote, new AtomicInteger()),
                    settings(monitor, telemetry), "project", "remote",
                    monitor, false).orElseThrow();
            assertFalse(second.reused(),
                    "an edited ignore list must retire the reusable model");
            second.publish();
        } finally {
            cleanup(project, monitor);
        }
    }

    @Test
    void persistedIndexIsRestoredBeforeFirstColdComparison(
            @TempDir Path root) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(
                root.resolve("restart-project"), monitor);
        var provider = new PgDatabaseProvider();
        Path remote = root.resolve("restart-remote.sql");
        Files.writeString(remote, """
                CREATE SCHEMA app;
                CREATE TABLE app.item (id bigint);
                """);
        try (var reusable = new ReusableProjectComparison()) {
            writeProject(project);
            PgDbParser parser = PgDbParser.getParserForBuilder(
                    project, new int[] {
                            IncrementalProjectBuilder.FULL_BUILD });
            parser.prepareProjectIndex(project, monitor)
                    .update().commit(project.getName(), monitor);
            parser.replaceReferenceIndexForTests(
                    new ProjectReferencesStorage());
            assertTrue(parser.acquireValidatedProjectSnapshotLease(
                    project, monitor).isEmpty());

            Path projectRoot = project.getLocation()
                    .toFile().toPath();
            var first = reusable.load(
                    project, DatabaseType.PG, provider,
                    projectRoot,
                    remoteFactory(provider, remote,
                            new AtomicInteger()),
                    settings(monitor), "project", "remote",
                    monitor, false).orElseThrow();

            assertFalse(first.reused());
            var firstPublication = first.publish();
            assertTrue(firstPublication.accepted());
            try (var displayed = firstPublication
                    .displayLease().orElseThrow()) {
                // The restored index anchors the first reusable candidate.
            }
            project.build(IncrementalProjectBuilder.FULL_BUILD,
                    monitor);

            var second = reusable.load(
                    project, DatabaseType.PG, provider,
                    projectRoot,
                    remoteFactory(provider, remote,
                            new AtomicInteger()),
                    settings(monitor), "project", "remote",
                    monitor, false).orElseThrow();
            assertTrue(second.reused());
            second.close();
        } finally {
            cleanup(project, monitor);
        }
    }

    @Test
    void effectiveVersionMismatchRetriesColdExactlyOnceAndClosesRemoteLoaders(
            @TempDir Path root) throws Exception {
        try (var fixture = new ReusableFixture(
                root.resolve("version-mismatch"))) {
            verify(fixture.seed(PgSupportedVersion.VERSION_16))
                    .close();
            var creates = new AtomicInteger();
            var loaders = new ArrayList<ILoader>();
            var telemetry = mock(
                    EclipseComparisonTelemetry.class);
            var mismatched = fixture.load(
                    trackingRemoteFactory(fixture.provider,
                            PgSupportedVersion.VERSION_15,
                            creates, loaders, false),
                    telemetry);

            assertFalse(mismatched.reused());
            assertEquals(2, creates.get(),
                    "warm NEW plus exactly one cold NEW retry");
            assertEquals(2, loaders.size());
            for (ILoader loader : loaders) {
                verify(loader).close();
            }
            verify(telemetry, times(1))
                    .projectModelCacheFinished(
                            eq(ProjectModelCacheStatus.REJECTED),
                            eq(ProjectModelFailClosedReason.VERSION_MISMATCH),
                            anyLong(), anyLong(), anyLong());
            try (var displayed = mismatched.publish()
                    .displayLease().orElseThrow()) {
                // the single cold retry publishes the version-15 model
            }
        }
    }

    @Test
    void editedIgnoreSchemaListForcesAColdReload(@TempDir Path root)
            throws Exception {
        try (var fixture = new ReusableFixture(
                root.resolve("ignore-schema"))) {
            Path remote = root.resolve("ignore-schema-remote.sql");
            Files.writeString(remote, "CREATE SCHEMA app;\n");
            Path ignoreSchema = fixture.projectRoot.resolve(
                    AbstractProjectLoader.IGNORE_SCHEMA_FILE);
            Files.writeString(ignoreSchema, "SHOW ALL\n");
            fixture.reindex();

            var seeded = fixture.load(remoteFactory(
                    fixture.provider, remote, new AtomicInteger()));
            try (var displayed = seeded.publish()
                    .displayLease().orElseThrow()) {
                // retain the model built under the current ignore rules
            }

            var unchanged = fixture.load(remoteFactory(
                    fixture.provider, remote, new AtomicInteger()));
            assertTrue(unchanged.reused(),
                    "an untouched configuration keeps the model reusable");
            try (var displayed = unchanged.publish()
                    .displayLease().orElseThrow()) {
                // keep the model retained for the next attempt
            }

            // The ignore-schema list drops schemas before parsing, and neither
            // listInputFiles() nor the consumed-input fingerprints cover it.
            Files.writeString(ignoreSchema, "SHOW ALL\nHIDE NONE app\n");
            fixture.reindex();

            var afterEdit = fixture.load(remoteFactory(
                    fixture.provider, remote, new AtomicInteger()));
            assertFalse(afterEdit.reused(),
                    "an edited ignore-schema list must retire the model");
            try (var displayed = afterEdit.publish()
                    .displayLease().orElseThrow()) {
                // the cold reload becomes the new reusable generation
            }
        }
    }

    @Test
    void retainedDisplayLeaseOutlivesTheDiffThatProducedIt(
            @TempDir Path root) throws Exception {
        try (var fixture = new ReusableFixture(
                root.resolve("lease-lifecycle"))) {
            Path remote = root.resolve("lease-remote.sql");
            Files.writeString(remote, "CREATE SCHEMA app;\n");

            var seeded = fixture.load(remoteFactory(
                    fixture.provider, remote, new AtomicInteger()));
            var display = seeded.publish().displayLease().orElseThrow();
            // A migration script job keeps reading the OLD model after the
            // editor stopped displaying this comparison.
            Runnable consumer = display.retain();
            display.close();

            var whileHeld = fixture.load(remoteFactory(
                    fixture.provider, remote, new AtomicInteger()));
            assertFalse(whileHeld.reused(),
                    "a model still read by a consumer must not be re-leased");
            var heldPublication = whileHeld.publish();
            assertTrue(heldPublication.accepted(),
                    "a cold comparison stays valid when the cache is busy");
            assertTrue(heldPublication.displayLease().isEmpty(),
                    "a busy cache cannot retain the new model");

            consumer.run();

            var afterRelease = fixture.load(remoteFactory(
                    fixture.provider, remote, new AtomicInteger()));
            assertFalse(afterRelease.reused());
            try (var displayed = afterRelease.publish()
                    .displayLease().orElseThrow()) {
                // the cache retains models again once nothing reads them
            }
        }
    }

    @Test
    void validAndCorruptDependenciesBypassReusablePipeline(
            @TempDir Path root) throws Exception {
        try (var fixture = new ReusableFixture(
                root.resolve("libraries-bypass"))) {
            fixture.seed(PgSupportedVersion.VERSION_16);
            Path dependencies = fixture.projectRoot.resolve(
                    LibraryXmlStore.FILE_NAME);
            new LibraryXmlStore(dependencies).writeDependencies(
                    List.of(new Library("test-library", "library",
                            false, "")), false);
            var internalCreates = new AtomicInteger();
            var telemetry = mock(
                    EclipseComparisonTelemetry.class);
            ILoaderFactory internal = trackingRemoteFactory(
                    fixture.provider, PgSupportedVersion.VERSION_16,
                    internalCreates, new ArrayList<>(), false);
            assertTrue(fixture.tryLoad(
                    fixture.provider, internal,
                    settings(fixture.monitor, telemetry)).isEmpty());

            Files.writeString(dependencies,
                    "<dependencies><dependency");
            assertTrue(fixture.tryLoad(
                    fixture.provider, internal,
                    settings(fixture.monitor, telemetry)).isEmpty());

            assertEquals(0, internalCreates.get(),
                    "libraries must bypass before internal remote creation");
            verify(telemetry, times(2))
                    .projectModelCacheFinished(
                            eq(ProjectModelCacheStatus.MISS),
                            eq(ProjectModelFailClosedReason.LIBRARIES),
                            anyLong(), anyLong(), anyLong());
        }
    }

    @Test
    void cancellationDuringEnumerationAndHashDoesNotRetryOrRetainCandidate(
            @TempDir Path root) throws Exception {
        assertLocalCancellation(
                root.resolve("cancel-enumeration"), true);
        assertLocalCancellation(
                root.resolve("cancel-hash"), false);
    }

    @Test
    void cancellationDuringWarmRemoteLoadDoesNotRetryAndReleasesModelLease(
            @TempDir Path root) throws Exception {
        Thread.interrupted();
        try (var fixture = new ReusableFixture(
                root.resolve("cancel-remote"))) {
            fixture.seed(PgSupportedVersion.VERSION_16);
            var creates = new AtomicInteger();
            var cancelledLoaders = new ArrayList<ILoader>();
            assertThrows(InterruptedException.class,
                    () -> fixture.tryLoad(
                            fixture.provider,
                            trackingRemoteFactory(fixture.provider,
                                    PgSupportedVersion.VERSION_16,
                                    creates, cancelledLoaders, true),
                            settings(fixture.monitor)));

            assertEquals(1, creates.get(),
                    "cancellation must not become a cold retry");
            assertEquals(1, cancelledLoaders.size());
            verify(cancelledLoaders.getFirst()).cancel();
            verify(cancelledLoaders.getFirst()).close();
            assertTrue(fixture.monitor.isCanceled());
            fixture.monitor.setCanceled(false);

            var recovered = fixture.load(
                    trackingRemoteFactory(fixture.provider,
                            PgSupportedVersion.VERSION_16,
                            new AtomicInteger(), new ArrayList<>(), false));
            assertTrue(recovered.reused(),
                    "the cancelled warm lease must be released");
            recovered.close();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void projectChangeBeforeUiAcceptanceRejectsPublication(
            @TempDir Path root) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(
                root.resolve("publication-race"), monitor);
        var provider = new PgDatabaseProvider();
        Path remote = root.resolve("remote.sql");
        Files.writeString(remote, """
                CREATE SCHEMA app;
                CREATE TABLE app.item (id bigint);
                """);
        try (var reusable = new ReusableProjectComparison()) {
            writeProject(project);
            PgDbParser parser = PgDbParser.getParserForBuilder(
                    project, new int[] {
                            IncrementalProjectBuilder.FULL_BUILD });
            parser.prepareProjectIndex(project, monitor)
                    .update().commit(project.getName(), monitor);

            var prepared = reusable.load(
                    project, DatabaseType.PG, provider,
                    project.getLocation().toFile().toPath(),
                    remoteFactory(provider, remote,
                            new AtomicInteger()),
                    settings(monitor), "project", "remote",
                    monitor, false).orElseThrow();
            project.getFile("SCHEMA/app/TABLE/item.sql")
                    .setContents(new ByteArrayInputStream("""
                            CREATE TABLE app.item (
                                id bigint,
                                changed text
                            );
                            """.getBytes(StandardCharsets.UTF_8)),
                            IResource.FORCE, monitor);

            assertFalse(prepared.isCurrent());
            var publication = prepared.publish();
            assertFalse(publication.accepted());
            assertTrue(publication.displayLease().isEmpty());
        } finally {
            cleanup(project, monitor);
        }
    }

    @Test
    void coldWithoutIndexRejectsProjectChangeBeforeUiAcceptance(
            @TempDir Path root) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(
                root.resolve("cold-without-index-race"), monitor);
        var provider = new PgDatabaseProvider();
        Path remote = root.resolve("remote.sql");
        Files.writeString(remote, """
                CREATE SCHEMA app;
                CREATE TABLE app.item (id bigint);
                """);
        try (var reusable = new ReusableProjectComparison()) {
            writeProject(project);
            var prepared = reusable.load(
                    project, DatabaseType.PG, provider,
                    project.getLocation().toFile().toPath(),
                    remoteFactory(provider, remote,
                            new AtomicInteger()),
                    settings(monitor), "project", "remote",
                    monitor, false).orElseThrow();
            project.getFile("SCHEMA/app/TABLE/item.sql")
                    .setContents(new ByteArrayInputStream("""
                            CREATE TABLE app.item (
                                id bigint,
                                changed text
                            );
                            """.getBytes(StandardCharsets.UTF_8)),
                            IResource.FORCE, monitor);

            assertFalse(prepared.reused());
            assertFalse(prepared.publish().accepted(),
                    "cold publication needs a stale guard even without an index lease");
        } finally {
            cleanup(project, monitor);
        }
    }

    @Test
    void projectChangeDuringColdCaptureHasTypedCancellation(
            @TempDir Path root) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(
                root.resolve("cold-capture-race"), monitor); //$NON-NLS-1$
        var provider = new PgDatabaseProvider();
        var loaderCalls = new AtomicInteger();
        var telemetry = mock(EclipseComparisonTelemetry.class);
        Path remote = root.resolve("remote.sql"); //$NON-NLS-1$
        Files.writeString(remote, """
                CREATE SCHEMA app;
                CREATE TABLE app.item (id bigint);
                """);
        try (var reusable = new ReusableProjectComparison()) {
            writeProject(project);
            IDatabaseProvider changingProvider =
                    mutateDuringSecondInspection(
                            provider, project, monitor, loaderCalls);

            ProjectInputsChangedException failure = assertThrows(
                    ProjectInputsChangedException.class,
                    () -> reusable.load(
                            project, DatabaseType.PG, changingProvider,
                            project.getLocation().toFile().toPath(),
                            remoteFactory(provider, remote,
                                    new AtomicInteger()),
                            settings(monitor, telemetry),
                            "project", "remote", monitor, false)); //$NON-NLS-1$ //$NON-NLS-2$

            assertEquals(ProjectInputChangeStage.FILE_SET,
                    failure.stage());
            assertEquals("SCHEMA/app/TABLE/item.sql", //$NON-NLS-1$
                    failure.relativePath().orElseThrow());
            verify(telemetry).projectInputsChanged(
                    ProjectInputChangeStage.FILE_SET);
        } finally {
            cleanup(project, monitor);
        }
    }

    @Test
    void unchangedColdWithoutIndexIsAcceptedWithoutDisplayLease(
            @TempDir Path root) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(
                root.resolve("cold-without-index"), monitor);
        var provider = new PgDatabaseProvider();
        Path remote = root.resolve("remote.sql");
        Files.writeString(remote, """
                CREATE SCHEMA app;
                CREATE TABLE app.item (id bigint);
                """);
        try (var reusable = new ReusableProjectComparison()) {
            writeProject(project);
            var prepared = reusable.load(
                    project, DatabaseType.PG, provider,
                    project.getLocation().toFile().toPath(),
                    remoteFactory(provider, remote,
                            new AtomicInteger()),
                    settings(monitor), "project", "remote",
                    monitor, false).orElseThrow();

            var publication = prepared.publish();

            assertTrue(publication.accepted());
            assertTrue(publication.displayLease().isEmpty(),
                    "a valid cold result must not invent a nullable lease");
        } finally {
            cleanup(project, monitor);
        }
    }

    @Test
    void mutationInsideFinalPublicationGateCannotAnchorAStaleModel(
            @TempDir Path root) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(
                root.resolve("linearized-publication"), monitor);
        var provider = new PgDatabaseProvider();
        Path remote = root.resolve("remote.sql");
        Files.writeString(remote, """
                CREATE SCHEMA app;
                CREATE TABLE app.item (id bigint);
                """);
        try (var reusable = new ReusableProjectComparison()) {
            writeProject(project);
            PgDbParser parser = PgDbParser.getParserForBuilder(
                    project, new int[] {
                            IncrementalProjectBuilder.FULL_BUILD });
            parser.prepareProjectIndex(project, monitor)
                    .update().commit(project.getName(), monitor);
            var prepared = reusable.load(
                    project, DatabaseType.PG, provider,
                    project.getLocation().toFile().toPath(),
                    remoteFactory(provider, remote,
                            new AtomicInteger()),
                    settings(monitor), "project", "remote",
                    monitor, false).orElseThrow();
            assertTrue(prepared.isCurrent());

            var publication = prepared.publish(() -> {
                try {
                    project.getFile("SCHEMA/app/TABLE/item.sql")
                            .setContents(new ByteArrayInputStream("""
                                    CREATE TABLE app.item (
                                        id bigint,
                                        raced text
                                    );
                                    """.getBytes(StandardCharsets.UTF_8)),
                                    IResource.FORCE, monitor);
                } catch (Exception ex) {
                    throw new AssertionError(ex);
                }
            });

            assertFalse(publication.accepted());
            assertTrue(publication.displayLease().isEmpty());
        } finally {
            cleanup(project, monitor);
        }
    }

    @Test
    void candidatePublicationFailureRejectsThePreparedResult(
            @TempDir Path root) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(
                root.resolve("candidate-rejected"), monitor);
        var provider = new PgDatabaseProvider();
        Path remote = root.resolve("remote.sql");
        Files.writeString(remote, """
                CREATE SCHEMA app;
                CREATE TABLE app.item (id bigint);
                """);
        var reusable = new ReusableProjectComparison();
        try {
            writeProject(project);
            PgDbParser parser = PgDbParser.getParserForBuilder(
                    project, new int[] {
                            IncrementalProjectBuilder.FULL_BUILD });
            parser.prepareProjectIndex(project, monitor)
                    .update().commit(project.getName(), monitor);
            var prepared = reusable.load(
                    project, DatabaseType.PG, provider,
                    project.getLocation().toFile().toPath(),
                    remoteFactory(provider, remote,
                            new AtomicInteger()),
                    settings(monitor), "project", "remote",
                    monitor, false).orElseThrow();

            reusable.close();

            assertFalse(prepared.publish().accepted(),
                    "a rejected cache candidate must reject UI publication");
        } finally {
            reusable.close();
            cleanup(project, monitor);
        }
    }

    @Test
    void sameIndexRevisionPublicationKeepsTheReusableModel(
            @TempDir Path root) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(
                root.resolve("new-index-publication"), monitor);
        var provider = new PgDatabaseProvider();
        Path remote = root.resolve("remote.sql");
        Files.writeString(remote, """
                CREATE SCHEMA app;
                CREATE TABLE app.item (id bigint);
                """);
        try (var reusable = new ReusableProjectComparison()) {
            writeProject(project);
            PgDbParser parser = PgDbParser.getParserForBuilder(
                    project, new int[] {
                            IncrementalProjectBuilder.FULL_BUILD });
            parser.prepareProjectIndex(project, monitor)
                    .update().commit(project.getName(), monitor);
            Path projectRoot = project.getLocation()
                    .toFile().toPath();

            var seed = reusable.load(
                    project, DatabaseType.PG, provider,
                    projectRoot,
                    remoteFactory(provider, remote,
                            new AtomicInteger()),
                    settings(monitor), "project", "remote",
                    monitor, false).orElseThrow();
            var seedPublication = seed.publish();
            assertTrue(seedPublication.accepted());
            try (var displayed = seedPublication
                    .displayLease().orElseThrow()) {
                // Release the displayed model before reconciling the index.
            }

            IncrementalProjectReferenceIndex storageBefore =
                    parser.incrementalIndexForTests();
            PgDbParser.ProjectSnapshotToken tokenBefore;
            try (var lease = parser
                    .acquireValidatedProjectSnapshotLease(
                            project, monitor).orElseThrow()) {
                tokenBefore = lease.token();
            }
            parser.prepareProjectIndex(project, monitor)
                    .update().commit(project.getName(), monitor);
            assertSame(storageBefore,
                    parser.incrementalIndexForTests());
            try (var lease = parser
                    .acquireValidatedProjectSnapshotLease(
                            project, monitor).orElseThrow()) {
                assertEquals(tokenBefore, lease.token());
            }
            var afterPublication = reusable.load(
                    project, DatabaseType.PG, provider,
                    projectRoot,
                    remoteFactory(provider, remote,
                            new AtomicInteger()),
                    settings(monitor), "project", "remote",
                    monitor, false).orElseThrow();

            assertTrue(afterPublication.reused());
            afterPublication.close();
        } finally {
            cleanup(project, monitor);
        }
    }

    @Test
    void differentIndexRevisionCannotReuseTheOldModel(
            @TempDir Path root) throws Exception {
        try (var fixture = new ReusableFixture(
                root.resolve("different-index-revision"))) {
            fixture.seed(PgSupportedVersion.VERSION_16);
            PgDbParser parser = PgDbParser.getParserForBuilder(
                    fixture.project, new int[] {
                            IncrementalProjectBuilder.FULL_BUILD });
            PgDbParser.ProjectSnapshotToken tokenBefore;
            try (var lease = parser
                    .acquireValidatedProjectSnapshotLease(
                            fixture.project,
                            fixture.monitor).orElseThrow()) {
                tokenBefore = lease.token();
            }

            PgDbParser.clean(fixture.project);
            parser.prepareProjectIndex(
                    fixture.project, fixture.monitor)
                    .update().commit(
                            fixture.project.getName(),
                            fixture.monitor);
            try (var lease = parser
                    .acquireValidatedProjectSnapshotLease(
                            fixture.project,
                            fixture.monitor).orElseThrow()) {
                assertNotEquals(tokenBefore, lease.token());
            }

            var afterReplacement = fixture.load(
                    trackingRemoteFactory(
                            fixture.provider,
                            PgSupportedVersion.VERSION_16,
                            new AtomicInteger(),
                            new ArrayList<>(), false));
            assertFalse(afterReplacement.reused());
            afterReplacement.close();
        }
    }

    @Test
    void projectContentChangeForcesColdFallback(
            @TempDir Path root) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(
                root.resolve("changed-project"), monitor);
        var provider = new PgDatabaseProvider();
        Path remote = root.resolve("remote.sql");
        Files.writeString(remote, """
                CREATE SCHEMA app;
                CREATE TABLE app.item (id bigint);
                """);
        try (var reusable = new ReusableProjectComparison()) {
            writeProject(project);
            PgDbParser parser = PgDbParser.getParserForBuilder(
                    project, new int[] {
                            IncrementalProjectBuilder.FULL_BUILD });
            parser.prepareProjectIndex(project, monitor)
                    .update().commit(project.getName(), monitor);
            Path projectRoot = project.getLocation()
                    .toFile().toPath();

            var seed = reusable.load(
                    project, DatabaseType.PG, provider,
                    projectRoot,
                    remoteFactory(provider, remote,
                            new AtomicInteger()),
                    settings(monitor), "project", "remote",
                    monitor, false).orElseThrow();
            var seedPublication = seed.publish();
            assertTrue(seedPublication.accepted());
            try (var displayed = seedPublication
                    .displayLease().orElseThrow()) {
                // release the displayed model before the next run
            }

            project.getFile("SCHEMA/app/TABLE/item.sql")
                    .setContents(new ByteArrayInputStream("""
                            CREATE TABLE app.item (
                                id bigint,
                                code text
                            );
                            """.getBytes(StandardCharsets.UTF_8)),
                            IResource.FORCE, monitor);

            var changed = reusable.load(
                    project, DatabaseType.PG, provider,
                    projectRoot,
                    remoteFactory(provider, remote,
                            new AtomicInteger()),
                    settings(monitor), "project", "remote",
                    monitor, false).orElseThrow();

            assertFalse(changed.reused());
            changed.close();
        } finally {
            cleanup(project, monitor);
        }
    }

    @Test
    void coldFailureStillFinishesModelValidationExactlyOnce(
            @TempDir Path root) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(
                root.resolve("cold-failure"), monitor);
        var telemetry = mock(
                EclipseComparisonTelemetry.class);
        try (var reusable = new ReusableProjectComparison()) {
            writeProject(project);
            var provider = new PgDatabaseProvider();

            assertThrows(IOException.class, () -> reusable.load(
                    project, DatabaseType.PG, provider,
                    project.getLocation().toFile().toPath(),
                    LoaderFactories.of(settings -> {
                        throw new IOException("expected");
                    }),
                    settings(monitor, telemetry),
                    "project", "remote", monitor, false));

            verify(telemetry, times(1)).uiStageFinished(
                    eq(UiStage.MODEL_VALIDATE), anyLong());
        } finally {
            cleanup(project, new NullProgressMonitor());
        }
    }

    @Test
    void warmRejectionFollowedByColdFailureFinishesValidationOnce(
            @TempDir Path root) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(
                root.resolve("warm-reject-cold-failure"), monitor);
        var provider = new PgDatabaseProvider();
        Path remote = root.resolve("remote.sql");
        Files.writeString(remote, """
                CREATE SCHEMA app;
                CREATE TABLE app.item (id bigint);
                """);
        try (var reusable = new ReusableProjectComparison()) {
            writeProject(project);
            PgDbParser parser = PgDbParser.getParserForBuilder(
                    project, new int[] {
                            IncrementalProjectBuilder.FULL_BUILD });
            parser.prepareProjectIndex(project, monitor)
                    .update().commit(project.getName(), monitor);
            Path projectRoot = project.getLocation()
                    .toFile().toPath();
            var seed = reusable.load(
                    project, DatabaseType.PG, provider,
                    projectRoot,
                    remoteFactory(provider, remote,
                            new AtomicInteger()),
                    settings(monitor), "project", "remote",
                    monitor, false).orElseThrow();
            try (var displayed = seed.publish()
                    .displayLease().orElseThrow()) {
                // leave the reusable model available for the next run
            }

            project.getFile("SCHEMA/app/TABLE/item.sql")
                    .setContents(new ByteArrayInputStream("""
                            CREATE TABLE app.item (
                                id bigint,
                                code text
                            );
                            """.getBytes(StandardCharsets.UTF_8)),
                            IResource.FORCE, monitor);
            var telemetry = mock(
                    EclipseComparisonTelemetry.class);

            assertThrows(IOException.class, () -> reusable.load(
                    project, DatabaseType.PG, provider,
                    projectRoot,
                    LoaderFactories.of(settings -> {
                        throw new IOException("expected");
                    }),
                    settings(monitor, telemetry),
                    "project", "remote", monitor, false));

            verify(telemetry, times(1)).uiStageFinished(
                    eq(UiStage.MODEL_VALIDATE), anyLong());
        } finally {
            cleanup(project, new NullProgressMonitor());
        }
    }

    @Test
    void oneTimePreferencesBypassReusablePipeline(
            @TempDir Path root) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(
                root.resolve("one-time"), monitor);
        try (var reusable = new ReusableProjectComparison()) {
            writeProject(project);
            var provider = new PgDatabaseProvider();
            Path remote = root.resolve("remote.sql");
            Files.writeString(remote,
                    "CREATE SCHEMA app;\n");

            assertTrue(reusable.load(
                    project, DatabaseType.PG, provider,
                    project.getLocation().toFile().toPath(),
                    remoteFactory(provider, remote,
                            new AtomicInteger()),
                    settings(monitor), "project", "remote",
                    monitor, true).isEmpty());
        } finally {
            cleanup(project, monitor);
        }
    }

    /**
     * The sixth early exit: a request for anything but {@link
     * ComparisonDepth#FULL} bypasses this pipeline before the cache is even
     * consulted, so a receive-only comparison gets none of its cost - not
     * NEW construction, not a cache lookup, nothing.
     */
    @Test
    void structuralDepthBypassesReusablePipelineBeforeTheCache(
            @TempDir Path root) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(
                root.resolve("structural-only"), monitor);
        try (var reusable = new ReusableProjectComparison()) {
            writeProject(project);
            var provider = new PgDatabaseProvider();
            Path remote = root.resolve("remote.sql");
            Files.writeString(remote,
                    "CREATE SCHEMA app;\n");
            var creates = new AtomicInteger();
            var telemetry = mock(EclipseComparisonTelemetry.class);

            assertTrue(reusable.load(
                    project, DatabaseType.PG, provider,
                    project.getLocation().toFile().toPath(),
                    remoteFactory(provider, remote, creates),
                    settings(monitor, telemetry), "project", "remote",
                    monitor, false,
                    ComparisonDepth.STRUCTURAL_ONLY).isEmpty());

            assertEquals(0, creates.get(),
                    "a structural request must bypass before NEW is even "
                            + "created");
            verify(telemetry, times(1))
                    .projectModelCacheFinished(
                            eq(ProjectModelCacheStatus.MISS),
                            eq(ProjectModelFailClosedReason.STRUCTURAL_ONLY),
                            anyLong(), anyLong(), anyLong());
        } finally {
            cleanup(project, monitor);
        }
    }

    /**
     * The default ten-argument {@code load} is kept exactly for callers with
     * no opinion on depth, and it must keep reusing the analyzed model
     * exactly as it did before the depth parameter existed.
     */
    @Test
    void theDepthlessOverloadStillReusesTheAnalyzedModel(
            @TempDir Path root) throws Exception {
        try (var fixture = new ReusableFixture(
                root.resolve("depthless-overload"))) {
            Path remote = root.resolve("remote.sql");
            Files.writeString(remote, "CREATE SCHEMA app;\n");

            var seeded = fixture.load(remoteFactory(
                    fixture.provider, remote, new AtomicInteger()));
            try (var displayed = seeded.publish()
                    .displayLease().orElseThrow()) {
                // retain the model for the next comparison
            }

            var reused = fixture.load(remoteFactory(
                    fixture.provider, remote, new AtomicInteger()));
            assertTrue(reused.reused(),
                    "a caller that never mentions depth must still get FULL");
            reused.close();
        }
    }

    private static void assertLocalCancellation(
            Path location, boolean duringEnumeration)
            throws Exception {
        try (var fixture = new ReusableFixture(location)) {
            var projectLoaderCalls = new AtomicInteger();
            var enumerationCompleted = new AtomicBoolean();
            var inspectionClosed = new AtomicBoolean();
            IDatabaseProvider cancellingProvider =
                    cancellationProvider(
                            fixture.provider, fixture.monitor,
                            duringEnumeration, projectLoaderCalls,
                            enumerationCompleted, inspectionClosed);
            var creates = new AtomicInteger();
            var loaders = new ArrayList<ILoader>();

            assertThrows(InterruptedException.class,
                    () -> fixture.tryLoad(
                            cancellingProvider,
                            trackingRemoteFactory(fixture.provider,
                                    PgSupportedVersion.VERSION_16,
                                    creates, loaders, false),
                            settings(fixture.monitor)));

            assertEquals(1, creates.get(),
                    "local cancellation must not retry NEW");
            assertEquals(2, projectLoaderCalls.get(),
                    "hash cancellation must stop before re-enumeration");
            assertEquals(!duringEnumeration,
                    enumerationCompleted.get());
            assertTrue(inspectionClosed.get());
            verify(loaders.getFirst()).close();

            fixture.monitor.setCanceled(false);
            var next = fixture.load(
                    trackingRemoteFactory(fixture.provider,
                            PgSupportedVersion.VERSION_16,
                            new AtomicInteger(), new ArrayList<>(), false));
            assertFalse(next.reused(),
                    "cancelled cold work must not retain a candidate");
            next.close();
        }
    }

    private static IDatabaseProvider cancellationProvider(
            PgDatabaseProvider delegate,
            NullProgressMonitor monitor,
            boolean duringEnumeration,
            AtomicInteger calls,
            AtomicBoolean enumerationCompleted,
            AtomicBoolean inspectionClosed)
            throws Exception {
        IDatabaseProvider provider =
                mock(IDatabaseProvider.class);
        when(provider.getProjectLoader(
                any(Path.class), any(ISettings.class),
                anyCollection(), anyCollection(),
                anyCollection(), any(Path.class)))
                .thenAnswer(invocation -> {
                    IProjectLoader real = delegate.getProjectLoader(
                            invocation.getArgument(0),
                            invocation.getArgument(1),
                            invocation.getArgument(2),
                            invocation.getArgument(3),
                            invocation.getArgument(4),
                            invocation.getArgument(5));
                    if (calls.incrementAndGet() != 2) {
                        return real;
                    }
                    IProjectLoader inspection = mock(
                            IProjectLoader.class,
                            delegatesTo(real));
                    doAnswer(ignored -> {
                        if (duringEnumeration) {
                            monitor.setCanceled(true);
                        }
                        List<Path> inputs =
                                real.listInputFiles();
                        enumerationCompleted.set(true);
                        monitor.setCanceled(true);
                        return inputs;
                    }).when(inspection).listInputFiles();
                    doAnswer(ignored -> {
                        try {
                            real.close();
                        } finally {
                            inspectionClosed.set(true);
                        }
                        return null;
                    }).when(inspection).close();
                    return inspection;
                });
        return provider;
    }

    private static IDatabaseProvider mutateDuringSecondInspection(
            PgDatabaseProvider delegate, IProject project,
            NullProgressMonitor monitor, AtomicInteger calls)
            throws Exception {
        IDatabaseProvider provider = mock(IDatabaseProvider.class);
        when(provider.getProjectLoader(
                any(Path.class), any(ISettings.class),
                anyCollection(), anyCollection(),
                anyCollection(), any(Path.class)))
                .thenAnswer(invocation -> {
                    IProjectLoader real = delegate.getProjectLoader(
                            invocation.getArgument(0),
                            invocation.getArgument(1),
                            invocation.getArgument(2),
                            invocation.getArgument(3),
                            invocation.getArgument(4),
                            invocation.getArgument(5));
                    if (calls.incrementAndGet() != 3) {
                        return real;
                    }
                    IProjectLoader inspection = mock(
                            IProjectLoader.class,
                            delegatesTo(real));
                    doAnswer(ignored -> {
                        project.getFile(
                                "SCHEMA/app/TABLE/item.sql") //$NON-NLS-1$
                                .setContents(new ByteArrayInputStream("""
                                        CREATE TABLE app.item (
                                            id bigint,
                                            changed text
                                        );
                                        """.getBytes(
                                                StandardCharsets.UTF_8)),
                                        IResource.FORCE, monitor);
                        return real.listInputFiles();
                    }).when(inspection).listInputFiles();
                    return inspection;
                });
        return provider;
    }

    private static CoreSettings settings(
            NullProgressMonitor monitor) {
        var settings = new CoreSettings();
        settings.setPgRoutineBodyHashFirst(true);
        settings.setPgRoutineBodySkipMatchedAnalysis(true);
        settings.setTimeZone("UTC");
        settings.setMonitor(new UIMonitor(monitor));
        return settings;
    }

    private static CoreSettings settings(
            NullProgressMonitor monitor,
            EclipseComparisonTelemetry telemetry) {
        CoreSettings settings = settings(monitor);
        settings.setComparisonTelemetry(telemetry);
        return settings;
    }

    private static ILoaderFactory remoteFactory(
            PgDatabaseProvider provider, Path remote,
            AtomicInteger creates) {
        return LoaderFactories.of(settings -> {
            creates.incrementAndGet();
            return provider.getDumpLoader(remote, settings);
        });
    }

    private static ILoaderFactory trackingRemoteFactory(
            PgDatabaseProvider provider,
            ISupportedVersion version,
            AtomicInteger creates,
            Collection<ILoader> loaders,
            boolean interrupt) {
        return LoaderFactories.of(settings -> {
            creates.incrementAndGet();
            IDatabase database = provider.createDatabase();
            ILoader loader = mock(ILoader.class);
            when(loader.getDatabase()).thenReturn(database);
            when(loader.getDatabaseName()).thenReturn("remote");
            when(loader.getSettings()).thenReturn(settings);
            when(loader.getErrors()).thenReturn(List.of());
            Answer<IDatabase> load = ignored -> {
                settings.setVersion(version);
                if (interrupt) {
                    throw new InterruptedException(
                            "expected remote cancellation");
                }
                return database;
            };
            doAnswer(load).when(loader).load();
            doAnswer(load).when(loader).loadAndAnalyze();
            loaders.add(loader);
            return loader;
        });
    }

    private static final class ReusableFixture
            implements AutoCloseable {

        private final NullProgressMonitor monitor =
                new NullProgressMonitor();
        private final IProject project;
        private final PgDatabaseProvider provider =
                new PgDatabaseProvider();
        private final Path projectRoot;
        private final ReusableProjectComparison reusable =
                new ReusableProjectComparison();

        private ReusableFixture(Path location)
                throws Exception {
            project = createProject(location, monitor);
            writeProject(project);
            projectRoot = project.getLocation()
                    .toFile().toPath();
            reindex();
        }

        /**
         * Refreshes the workspace and rebuilds the project index, so that
         * files written directly on disk do not leave a stale index behind.
         */
        private void reindex() throws Exception {
            project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
            PgDbParser parser = PgDbParser.getParserForBuilder(
                    project, new int[] {
                            IncrementalProjectBuilder.FULL_BUILD });
            parser.prepareProjectIndex(project, monitor)
                    .update().commit(project.getName(), monitor);
        }

        private ReusableProjectComparison.PreparedComparison load(
                ILoaderFactory remote)
                throws IOException, InterruptedException {
            return tryLoad(provider, remote,
                    settings(monitor)).orElseThrow();
        }

        private ReusableProjectComparison.PreparedComparison load(
                ILoaderFactory remote,
                EclipseComparisonTelemetry telemetry)
                throws IOException, InterruptedException {
            return tryLoad(provider, remote,
                    settings(monitor, telemetry)).orElseThrow();
        }

        private Optional<ReusableProjectComparison.PreparedComparison>
                tryLoad(IDatabaseProvider projectProvider,
                        ILoaderFactory remote,
                        CoreSettings comparisonSettings)
                throws IOException, InterruptedException {
            return reusable.load(
                    project, DatabaseType.PG,
                    projectProvider, projectRoot, remote,
                    comparisonSettings, "project", "remote",
                    monitor, false);
        }

        private ILoader seed(ISupportedVersion version)
                throws Exception {
            var loaders = new ArrayList<ILoader>();
            var prepared = load(trackingRemoteFactory(
                    provider, version, new AtomicInteger(),
                    loaders, false));
            try (var displayed = prepared.publish()
                    .displayLease().orElseThrow()) {
                // publish a warm model for the scenario
            }
            return loaders.getFirst();
        }

        @Override
        public void close() throws Exception {
            reusable.close();
            cleanup(project, new NullProgressMonitor());
        }
    }

    private static IProject createProject(Path location,
            NullProgressMonitor monitor) throws Exception {
        var workspace = ResourcesPlugin.getWorkspace();
        String name = "pgck-reusable-"
                + location.getFileName();
        IProject project =
                workspace.getRoot().getProject(name);
        if (project.exists()) {
            project.delete(true, true, monitor);
        }
        IProjectDescription description =
                workspace.newProjectDescription(name);
        description.setLocationURI(location.toUri());
        project.create(description, monitor);
        project.open(monitor);
        return project;
    }

    private static void writeProject(IProject project)
            throws Exception {
        Path root = project.getLocation().toFile().toPath();
        Path schema = Files.createDirectories(
                root.resolve("SCHEMA/app"));
        Path tables = Files.createDirectories(
                schema.resolve("TABLE"));
        Files.writeString(schema.resolve("app.sql"),
                "CREATE SCHEMA app;\n");
        Files.writeString(tables.resolve("item.sql"),
                "CREATE TABLE app.item (id bigint);\n");
        project.refreshLocal(IResource.DEPTH_INFINITE,
                new NullProgressMonitor());
    }

    private static void cleanup(IProject project,
            NullProgressMonitor monitor) throws Exception {
        PgDbParser.removeProject(project);
        if (project.exists()) {
            project.delete(true, true, monitor);
        }
    }

    private static CurrentFile currentFile(String relativePath) {
        return new CurrentFile(
                new IndexPathRef(IndexPathOrigin.PROJECT,
                        relativePath),
                1, 1, 1);
    }
}
