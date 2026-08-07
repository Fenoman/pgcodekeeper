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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.jupiter.api.Test;

import ru.taximaxim.codekeeper.ui.builders.ProjectBuildContinuity.BuildContinuityProof;
import ru.taximaxim.codekeeper.ui.builders.ProjectBuildContinuityTestSupport;
import ru.taximaxim.codekeeper.ui.builders.ProjectBuildContinuityTestSupport.Scenario;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexIncrementalPlanner;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetry.Mode;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetryTestSupport;

/**
 * Ad-hoc BENCHMARK harness -- NOT a regression test.
 *
 * <p>Measures the production incremental project-index path
 * ({@link PgDbParser#prepareIncrementalProjectIndex}) against a real,
 * full-size pgcodekeeper project and compares it to a full cold build
 * ({@link PgDbParser#prepareProjectIndex}), for a single (N, repeat) point
 * per JVM invocation.
 *
 * <p>Three modes, selected via the {@code mode} param:
 * <ul>
 * <li>{@code full} -- cold build only (baseline sample).</li>
 * <li>{@code incremental} -- cold build that ESTABLISHES trusted builder
 * continuity ({@link ProjectBuildContinuityTestSupport}, mirroring
 * {@code PgDbParserIncrementalProductionPathTest#establishContinuity}), then
 * {@code n} files are touched and pushed through
 * {@code ceil(n / batchSize)} SEQUENTIAL trusted incremental batches on the
 * SAME live parser (no restart in between -- this is the steady-state cost
 * of a build session processing consecutive incremental deltas, and is what
 * isolates per-batch fixed cost from per-file cost).</li>
 * <li>{@code reopen} -- cold build, then the live parser is dropped and a
 * FRESH {@code PgDbParser} instance (no continuity proof, mirroring
 * {@code PgDbParserIncrementalProductionPathTest
 * #firstSingleFileChangeAfterRestartUsesPersistedIndex}) processes ONE batch
 * of {@code n} (&lt;= 16) freshly changed files. This isolates the one-time
 * "IDE was closed, index must be restored from disk" cost from the
 * steady-state batch cost measured by {@code incremental}.</li>
 * </ul>
 *
 * <p>Deliberately named without a {@code Test} suffix: Tycho/Surefire's
 * default include patterns ({@code **}{@code /*Test.java},
 * {@code **}{@code /Test*.java}, ...) skip it in a normal {@code mvn test} /
 * {@code mvn verify} run. Invoke it explicitly and alone, e.g.:
 *
 * <pre>
 * mvn test -Dtest=PgDbParserIncrementalBenchmark
 * </pre>
 *
 * <p>Parameters are read from a plain {@code key=value} properties file
 * (NOT from {@code -D} system properties: those are not guaranteed to
 * propagate into the forked Tycho/Equinox test JVM), at a fixed path given
 * by the {@code PGCK_BENCH_PARAMS} environment variable, defaulting to
 * {@code /home/bench/bench/pgck-bench-params.properties}:
 *
 * <pre>
 * projectPath=/home/bench/bench/OmniX_DB
 * mode=incremental   # full | incremental | reopen
 * n=8                # required for mode=incremental / mode=reopen
 * repeat=1           # label only, for log correlation
 * batchSize=16       # mode=incremental only; 1..MAX_BATCH_SIZE
 * </pre>
 *
 * <p>Results are printed to stdout as greppable {@code BENCH_RESULT ...}
 * lines (the module's tycho-surefire config sets {@code -consoleLog}, so
 * they land in the Tycho console log / redirected build log). This class is
 * throwaway benchmarking scaffolding, not part of the regression suite.
 */
class PgDbParserIncrementalBenchmark {

    private static final String PARAMS_PATH_ENV = "PGCK_BENCH_PARAMS";
    private static final String DEFAULT_PARAMS_PATH =
            "/home/bench/bench/pgck-bench-params.properties";

    @Test
    void run() throws Exception {
        Properties params = loadParams();
        String projectPath = require(params, "projectPath");
        String mode = params.getProperty("mode", "full");
        int n = Integer.parseInt(params.getProperty("n", "0"));
        int repeat = Integer.parseInt(params.getProperty("repeat", "1"));
        int batchSize = Integer.parseInt(params.getProperty("batchSize", "16"));
        boolean incremental = "incremental".equals(mode);
        boolean reopen = "reopen".equals(mode);
        if (incremental || reopen) {
            assertTrue(n > 0, "n must be > 0 for mode=" + mode);
        }
        int limit = ProjectIndexIncrementalPlanner.MAX_BATCH_SIZE;
        if (incremental) {
            assertTrue(batchSize > 0 && batchSize <= limit,
                    "batchSize must be in 1.." + limit
                            + " (production MAX_BATCH_SIZE), was "
                            + batchSize);
        }
        if (reopen) {
            assertTrue(n <= limit,
                    "mode=reopen models a single post-restart batch; n must be <= "
                            + limit + ", was " + n);
        }

        var monitor = new NullProgressMonitor();
        Path location = Path.of(projectPath);
        assertTrue(Files.isDirectory(location),
                "projectPath does not exist: " + location);
        IProject project = createProject(location, monitor);
        PgDbParser seedParser = new PgDbParser();
        try {
            long refreshMs = timed(() ->
                    project.refreshLocal(IResource.DEPTH_INFINITE, monitor));
            report("workspace_refresh", mode, n, repeat, -1, -1, refreshMs, null);

            Scenario scenario = ProjectBuildContinuityTestSupport
                    .acceptedReconciliation(1);
            BuildContinuityProof coldProof = scenario.nextReconciliation();

            List<String> coldLines = new ArrayList<>();
            var coldTelemetry = ProjectIndexTelemetryTestSupport.start(
                    coldLines, Mode.COLD);
            long coldStart = System.nanoTime();
            PgDbParser.PreparedProjectIndex cold =
                    seedParser.prepareProjectIndex(project, monitor,
                            coldTelemetry, coldProof);
            PgDbParser.PreparedUpdate coldUpdate = cold.update();
            coldUpdate.commit(project.getName(), monitor);
            assertTrue(scenario.accept(coldProof),
                    "cold build continuity proof was not accepted");
            long coldMs = elapsedMs(coldStart);
            report("full_build", mode, n, repeat, -1,
                    cold.restoredFromDisk() ? 1 : 0, coldMs, oneLine(coldLines));

            if (incremental) {
                runTrustedIncrementalSweep(seedParser, project, monitor,
                        scenario, location, mode, n, repeat, batchSize);
            } else if (reopen) {
                runReopenBatch(seedParser, project, monitor, location,
                        mode, n, repeat);
            }
        } finally {
            seedParser.clear();
        }
    }

    /**
     * Steady-state path: the SAME live parser processes {@code n} files as
     * {@code ceil(n / batchSize)} sequential TRUSTED incremental batches,
     * threading one {@link BuildContinuityProof} per batch through the SAME
     * {@link Scenario} lineage established by the cold build. No parser is
     * ever recreated here, so no batch pays a disk-restore ("reopen") cost;
     * this isolates the steady-state per-batch fixed cost from the
     * per-file parse/analyze cost.
     */
    private static void runTrustedIncrementalSweep(PgDbParser parser,
            IProject project, NullProgressMonitor monitor, Scenario scenario,
            Path location, String mode, int n, int repeat, int batchSize)
            throws Exception {
        List<Path> targets = pickTargetFiles(location, n);
        assertTrue(targets.size() == n,
                "expected " + n + " candidate .sql files under SCHEMA, found "
                        + targets.size());
        long totalMs = 0;
        int batchIndex = 0;
        for (int from = 0; from < targets.size(); from += batchSize) {
            batchIndex++;
            List<Path> batch = targets.subList(from,
                    Math.min(from + batchSize, targets.size()));
            List<String> relPaths = new ArrayList<>();
            for (Path file : batch) {
                String rel = toRelativePath(location, file);
                relPaths.add(rel);
                appendBenchmarkComment(project, rel, monitor);
            }
            BuildContinuityProof proof = relPaths.size() == 1
                    ? scenario.nextSingle(relPaths.get(0))
                    : scenario.nextBatch(relPaths);
            List<String> lines = new ArrayList<>();
            var telemetry = ProjectIndexTelemetryTestSupport.start(
                    lines, Mode.INCREMENTAL);
            long batchStart = System.nanoTime();
            PgDbParser.PreparedIncrementalProjectIndex incrementalResult =
                    parser.prepareIncrementalProjectIndex(
                            project, relPaths, monitor, telemetry, proof);
            assertFalse(incrementalResult.fullBuild(),
                    "n=" + n + " batchSize=" + batchSize + " batch=" + batchIndex
                            + " unexpectedly fell back to a full rebuild");
            PgDbParser.PreparedUpdate update =
                    incrementalResult.prepared().update();
            update.commit(project.getName(), monitor);
            assertTrue(scenario.accept(proof),
                    "n=" + n + " batch=" + batchIndex
                            + " continuity proof was not accepted");
            assertTrue(update.wasContinuityAccepted(),
                    "n=" + n + " batch=" + batchIndex
                            + " update reports continuity NOT accepted");
            long batchMs = elapsedMs(batchStart);
            totalMs += batchMs;
            report("incremental_batch", mode, n, repeat, batchIndex,
                    batch.size(), batchMs, oneLine(lines));
        }
        report("incremental_total", mode, n, repeat, batchIndex, n,
                totalMs, null);
    }

    /**
     * Restart path: drops the live parser and lets a FRESH {@link PgDbParser}
     * (no continuity proof at all, mirroring
     * {@code firstSingleFileChangeAfterRestartUsesPersistedIndex}) process a
     * single batch of {@code n} freshly changed files, forcing the
     * disk-restore ("warm bootstrap") branch inside
     * {@link PgDbParser#prepareIncrementalProjectIndex}. This is the
     * one-time cost of reopening the IDE against an already-published index.
     */
    private static void runReopenBatch(PgDbParser seedParser, IProject project,
            NullProgressMonitor monitor, Path location, String mode, int n,
            int repeat) throws Exception {
        seedParser.clear();
        PgDbParser restartedParser = new PgDbParser();
        try {
            List<Path> targets = pickTargetFiles(location, n);
            assertTrue(targets.size() == n,
                    "expected " + n + " candidate .sql files under SCHEMA, found "
                            + targets.size());
            List<String> relPaths = new ArrayList<>();
            for (Path file : targets) {
                String rel = toRelativePath(location, file);
                relPaths.add(rel);
                appendBenchmarkComment(project, rel, monitor);
            }
            List<String> lines = new ArrayList<>();
            var telemetry = ProjectIndexTelemetryTestSupport.start(
                    lines, Mode.INCREMENTAL);
            long start = System.nanoTime();
            PgDbParser.PreparedIncrementalProjectIndex incrementalResult =
                    restartedParser.prepareIncrementalProjectIndex(
                            project, relPaths, monitor, telemetry, null);
            assertFalse(incrementalResult.fullBuild(),
                    "n=" + n + " reopen batch unexpectedly fell back to a full rebuild");
            incrementalResult.prepared().update()
                    .commit(project.getName(), monitor);
            long ms = elapsedMs(start);
            report("reopen_batch", mode, n, repeat, 1, n, ms, oneLine(lines));
        } finally {
            restartedParser.clear();
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static long timed(ThrowingRunnable action) throws Exception {
        long start = System.nanoTime();
        action.run();
        return elapsedMs(start);
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static Properties loadParams() throws IOException {
        String path = System.getenv(PARAMS_PATH_ENV);
        if (path == null || path.isBlank()) {
            path = DEFAULT_PARAMS_PATH;
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(Path.of(path))) {
            properties.load(input);
        }
        return properties;
    }

    private static String require(Properties params, String key) {
        String value = params.getProperty(key);
        assertTrue(value != null && !value.isBlank(),
                key + " is required in the benchmark params file");
        return value;
    }

    private static void report(String kind, String mode, int n, int repeat,
            int batch, int extra, long wallMs, String telemetry) {
        var line = new StringBuilder("BENCH_RESULT kind=").append(kind)
                .append(" mode=").append(mode)
                .append(" n=").append(n)
                .append(" repeat=").append(repeat);
        if (batch >= 0) {
            line.append(" batch=").append(batch);
        }
        if (extra >= 0) {
            line.append(" extra=").append(extra);
        }
        line.append(" wall_ms=").append(wallMs);
        if (telemetry != null) {
            line.append(" telemetry=\"").append(telemetry).append('"');
        }
        System.out.println(line);
    }

    private static String oneLine(List<String> lines) {
        return String.join(" ~ ", lines);
    }

    private static IProject createProject(Path location, NullProgressMonitor monitor)
            throws Exception {
        var workspace = ResourcesPlugin.getWorkspace();
        String name = "pgck-bench-" + Integer.toHexString(location.hashCode());
        IProject project = workspace.getRoot().getProject(name);
        if (project.exists()) {
            project.delete(false, true, monitor);
        }
        IProjectDescription description = workspace.newProjectDescription(name);
        description.setLocationURI(location.toUri());
        project.create(description, monitor);
        project.open(monitor);
        return project;
    }

    private static String toRelativePath(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static void appendBenchmarkComment(IProject project, String relativePath,
            NullProgressMonitor monitor) throws Exception {
        IFile file = project.getFile(
                org.eclipse.core.runtime.Path.fromPortableString(relativePath));
        byte[] current = Files.readAllBytes(file.getLocation().toFile().toPath());
        byte[] comment = ("\n-- pgck-bench touch " + System.nanoTime() + "\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] updated = new byte[current.length + comment.length];
        System.arraycopy(current, 0, updated, 0, current.length);
        System.arraycopy(comment, 0, updated, current.length, comment.length);
        try (var input = new ByteArrayInputStream(updated)) {
            file.setContents(input, true, false, monitor);
        }
    }

    /**
     * Picks {@code n} real, non-empty {@code .sql} leaf files spread evenly
     * (by stride) across the project's {@code SCHEMA} tree, so a batch is not
     * concentrated in whichever schema sorts first alphabetically. The
     * {@code dummy_tmp} schema is excluded on purpose: it is excluded from
     * the real project index too (see
     * {@code .settings/ru.taximaxim.codekeeper.ui.prefs}:
     * {@code projectIndexExcludedSchemas=dummy_tmp}), so a target picked from
     * it would never be a valid "previously indexed" incremental path.
     */
    private static List<Path> pickTargetFiles(Path root, int n) throws IOException {
        Path schema = root.resolve("SCHEMA");
        Path excludedSchema = schema.resolve("dummy_tmp");
        List<Path> all;
        try (Stream<Path> walk = Files.walk(schema)) {
            all = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .filter(PgDbParserIncrementalBenchmark::isNonEmpty)
                    .filter(p -> !p.startsWith(excludedSchema))
                    .filter(PgDbParserIncrementalBenchmark::isOrdinaryDefinition)
                    .sorted(Comparator.comparing(Path::toString))
                    .collect(Collectors.toList());
        }
        assertTrue(all.size() >= n,
                "project has only " + all.size() + " candidate .sql files, need " + n);
        int stride = Math.max(1, all.size() / n);
        List<Path> picked = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            picked.add(all.get((i * stride) % all.size()));
        }
        return picked;
    }

    private static boolean isNonEmpty(Path path) {
        try {
            return Files.size(path) > 0;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * Excludes declarative-partition child definitions
     * ({@code CREATE TABLE ... PARTITION OF ...}). A content-only,
     * shape-preserving edit (see {@link #appendBenchmarkComment}) to these
     * was empirically observed to still fail
     * {@code ProjectIndexIncrementalPlanner.areSafeReplacements} on a second
     * sequential trusted batch against the real project (investigated
     * 2026-07-29: {@code SCHEMA/tmp/TABLE/ee_rollback_apps_96.sql} and
     * {@code SCHEMA/tmp/TABLE/ee_rollback_registr_pts_applied_48.sql}, both
     * {@code PARTITION OF} children, forced a full-rebuild fallback even
     * though their definition count was unchanged). That is a genuine
     * product finding worth reporting on its own, but it is a confound for
     * THIS benchmark's steady-state per-batch/per-file cost measurement, so
     * partition children are excluded from the sampled population here.
     */
    private static boolean isOrdinaryDefinition(Path path) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            return !content.toUpperCase(java.util.Locale.ROOT)
                    .contains("PARTITION OF");
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
