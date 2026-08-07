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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgcodekeeper.core.database.api.loader.ComparisonLoaderFactories;
import org.pgcodekeeper.core.database.api.loader.ILoader;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.base.loader.LoaderFactories;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.settings.ISettings;

class CapturingPgProjectLoaderFactoryTest {

    @Test
    void captureIsPublishedOnlyAfterSuccessfulCoordinatedAnalysis(
            @TempDir Path root) throws Exception {
        Path project = root.resolve("project");
        Path schema = Files.createDirectories(
                project.resolve("SCHEMA/app"));
        Path functions = Files.createDirectories(
                schema.resolve("FUNCTION"));
        Files.writeString(schema.resolve("app.sql"),
                "CREATE SCHEMA app;\n");
        Files.writeString(functions.resolve("f.sql"), """
                CREATE FUNCTION app.f() RETURNS integer
                LANGUAGE sql AS $$ SELECT 1 $$;
                """);
        Path remote = root.resolve("remote.sql");
        Files.writeString(remote, """
                CREATE SCHEMA app;
                CREATE FUNCTION app.f() RETURNS integer
                LANGUAGE sql AS $$ SELECT 1 $$;
                """);

        var provider = new PgDatabaseProvider();
        var delegate = LoaderFactories.project(project,
                settings -> provider.getProjectLoader(
                        project, settings, List.of(), List.of(),
                        List.of(), root.resolve("meta")));
        var capturing =
                new CapturingPgProjectLoaderFactory(delegate);
        var settings = new CoreSettings();
        settings.setPgRoutineBodyHashFirst(true);

        UIComparisonLoader.Result loaded = UIComparisonLoader.load(
                new ComparisonLoaderFactories(capturing,
                        LoaderFactories.of(sideSettings ->
                                provider.getDumpLoader(
                                        remote, sideSettings))),
                settings, "project", "remote");
        PgDatabase oldDatabase =
                (PgDatabase) loaded.oldLoader().getDatabase();

        var capture = capturing.takeCapture(
                oldDatabase).orElseThrow();

        assertTrue(capture.routineSnapshot()
                .isCompatibleWith(oldDatabase));
        assertEquals(2, capture.inputFingerprints().size());
        assertTrue(capturing.takeCapture(oldDatabase).isEmpty(),
                "the reusable snapshot must transfer once");
    }

    @Test
    void unsupportedProjectLoaderIsClosedBeforeRejection() {
        var provider = new PgDatabaseProvider();
        var settings = new CoreSettings();
        var loader = new TrackingLoader(
                provider.createDatabase(), settings);
        var capturing = new CapturingPgProjectLoaderFactory(
                ignored -> loader);

        assertThrows(IllegalArgumentException.class,
                () -> capturing.create(settings));
        assertTrue(loader.closed);
    }

    private static final class TrackingLoader implements ILoader {

        private final IDatabase database;
        private final ISettings settings;
        private boolean closed;

        private TrackingLoader(
                IDatabase database, ISettings settings) {
            this.database = database;
            this.settings = settings;
        }

        @Override
        public IDatabase load() {
            return database;
        }

        @Override
        public IDatabase loadAndAnalyze() {
            return database;
        }

        @Override
        public IDatabase getDatabase() {
            return database;
        }

        @Override
        public String getDatabaseName() {
            return "tracking";
        }

        @Override
        public ISettings getSettings() {
            return settings;
        }

        @Override
        public List<Object> getErrors() {
            return Collections.unmodifiableList(
                    settings.getErrors());
        }

        @Override
        public void close() throws IOException {
            closed = true;
        }
    }
}
