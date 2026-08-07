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
package ru.taximaxim.codekeeper.ui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import ru.taximaxim.codekeeper.ui.HeapDumpConfiguration.VmOptions;

class HeapDumpConfigurationTest {

    private static final Path PREFERRED = Path.of("/workspace/heapdumps");
    private static final Predicate<Path> NOTHING_IS_WRITABLE = path -> false;

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void installsTheWorkspaceDirectoryWhenNothingIsConfigured(String configured) {
        assertEquals(Optional.of(PREFERRED), HeapDumpConfiguration
                .chooseDirectory(configured, PREFERRED, NOTHING_IS_WRITABLE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"java_pid.hprof", "dumps", "./dumps"})
    void installsTheWorkspaceDirectoryForARelativePath(String configured) {
        // A relative value is exactly the production failure: it follows the
        // process working directory, which the application does not own.
        List<Path> asked = new ArrayList<>();
        Predicate<Path> everythingIsWritable = path -> {
            asked.add(path);
            return true;
        };

        assertEquals(Optional.of(PREFERRED), HeapDumpConfiguration
                .chooseDirectory(configured, PREFERRED, everythingIsWritable));
        assertEquals(List.of(), asked,
                "a relative value must be replaced without asking the filesystem");
    }

    @Test
    void installsTheWorkspaceDirectoryWhenNeitherTheValueNorItsParentIsWritable() {
        assertEquals(Optional.of(PREFERRED), HeapDumpConfiguration.chooseDirectory(
                "/Applications/pgCodeKeeper.app/Contents/MacOS", PREFERRED,
                NOTHING_IS_WRITABLE));
    }

    @Test
    void installsTheWorkspaceDirectoryForAnUnparseablePath() {
        // A value the platform refuses to turn into a path at all.
        String unparseable = "/dumps" + (char) 0 + "app.hprof";

        assertEquals(Optional.of(PREFERRED), HeapDumpConfiguration
                .chooseDirectory(unparseable, PREFERRED, path -> true));
    }

    @Test
    void installsTheWorkspaceDirectoryWhenTheValueHasNoParentToFallBackOn() {
        assertEquals(Optional.of(PREFERRED), HeapDumpConfiguration
                .chooseDirectory("/", PREFERRED, NOTHING_IS_WRITABLE));
    }

    @Test
    void keepsAnExplicitWritableDirectory() {
        Path chosen = Path.of("/var/dumps");

        assertEquals(Optional.empty(), HeapDumpConfiguration
                .chooseDirectory(chosen.toString(), PREFERRED, chosen::equals));
    }

    @Test
    void keepsAnExplicitFileInsideAWritableDirectory() {
        Path directory = Path.of("/var/dumps");

        assertEquals(Optional.empty(), HeapDumpConfiguration.chooseDirectory(
                directory.resolve("app.hprof").toString(), PREFERRED,
                directory::equals));
    }

    @Test
    void acceptsOnlyRealWritableDirectories() throws Exception {
        Path file = Files.createFile(tempDir.resolve("not-a-directory"));

        assertTrue(HeapDumpConfiguration.isUsableDirectory(tempDir));
        assertFalse(HeapDumpConfiguration.isUsableDirectory(file));
        assertFalse(HeapDumpConfiguration
                .isUsableDirectory(tempDir.resolve("missing")));
    }

    @Test
    void rejectsADirectoryTheVirtualMachineCouldNotWriteInto() throws Exception {
        Path readOnly = Files.createDirectory(tempDir.resolve("read-only"));
        Files.setPosixFilePermissions(readOnly,
                PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assumeFalse(Files.isWritable(readOnly),
                    "this user writes anywhere, the case cannot be staged");

            assertFalse(HeapDumpConfiguration.isUsableDirectory(readOnly));
        } finally {
            Files.setPosixFilePermissions(readOnly,
                    PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void createsTheDirectoryBeforeTheOptionIsInstalled() {
        Path expected = tempDir.resolve(HeapDumpConfiguration.DIRECTORY_NAME);
        var options = new RecordingVmOptions();
        options.watchForDirectory(expected);

        HeapDumpConfiguration.configure(tempDir, () -> options, message -> {
            // outcome asserted separately
        });

        assertTrue(Files.isDirectory(expected));
        assertEquals(List.of(true), options.directoryExistedOnWrite(),
                "the virtual machine cannot create the directory during an"
                        + " exhausted heap, so it must exist beforehand");
        assertEquals(expected.toString(),
                options.value(HeapDumpConfiguration.PATH_OPTION));
    }

    @Test
    void reportsTheInstalledPath() {
        Path expected = tempDir.resolve(HeapDumpConfiguration.DIRECTORY_NAME);
        var options = new RecordingVmOptions();

        assertEquals("pgCodeKeeper heap dump: status=configured on_oome=true"
                + " path=" + expected, reportOf(tempDir, options));
    }

    @Test
    void reportsADisabledSwitchInsteadOfLookingLikeAWorkingSetup() {
        var options = new RecordingVmOptions();
        options.put(HeapDumpConfiguration.ON_OOME_OPTION, "false");

        assertTrue(reportOf(tempDir, options).contains(" on_oome=false "),
                "an installed path against a disabled switch must be visible");
    }

    @Test
    void keepsAnExternallyConfiguredPathAndInstallsNothing() {
        var options = new RecordingVmOptions();
        options.put(HeapDumpConfiguration.PATH_OPTION, tempDir.toString());

        assertEquals("pgCodeKeeper heap dump: status=kept on_oome=true path="
                + tempDir, reportOf(tempDir, options));
        assertEquals(List.of(), options.writes());
        assertFalse(Files.exists(
                tempDir.resolve(HeapDumpConfiguration.DIRECTORY_NAME)),
                "an externally configured path must not be second-guessed");
    }

    @Test
    void replacesAConfiguredPathThatNoLongerLeadsAnywhereWritable() {
        var options = new RecordingVmOptions();
        options.put(HeapDumpConfiguration.PATH_OPTION,
                tempDir.resolve("gone").resolve("dump.hprof").toString());

        assertEquals("pgCodeKeeper heap dump: status=configured on_oome=true"
                + " path=" + tempDir.resolve(HeapDumpConfiguration.DIRECTORY_NAME),
                reportOf(tempDir, options));
    }

    @Test
    void reportsFailureWhenTheOptionDoesNotStick() {
        var options = new RecordingVmOptions();
        options.refuseWrites();

        assertEquals("pgCodeKeeper heap dump: status=failed"
                + " reason=option_rejected path=empty",
                reportOf(tempDir, options));
    }

    @Test
    void reportsFailureWhenTheDiagnosticInterfaceIsMissing() {
        List<String> reported = new ArrayList<>();

        assertDoesNotThrow(() -> HeapDumpConfiguration.configure(tempDir, () -> {
            throw new NoClassDefFoundError(
                    "com/sun/management/HotSpotDiagnosticMXBean");
        }, reported::add));

        assertEquals(List.of("pgCodeKeeper heap dump: status=failed"
                + " reason=NoClassDefFoundError/com/sun/management"
                + "/HotSpotDiagnosticMXBean"), reported);
    }

    @Test
    void skipsWhenTheDiagnosticBeanIsAbsent() {
        assertEquals("pgCodeKeeper heap dump: status=skipped"
                + " reason=diagnostic_bean_unavailable",
                reportOf(tempDir, null));
    }

    @Test
    void skipsWhenTheWorkspaceLocationIsUnknown() {
        var options = new RecordingVmOptions();

        assertEquals("pgCodeKeeper heap dump: status=skipped"
                + " reason=state_location_unavailable", reportOf(null, options));
        assertEquals(List.of(), options.writes());
    }

    @Test
    void reportsFailureWhenTheDirectoryCannotBeCreated() throws Exception {
        Path blocked = Files.createFile(tempDir.resolve("blocked"));

        String reported = reportOf(blocked, new RecordingVmOptions());

        assertTrue(reported.startsWith(
                "pgCodeKeeper heap dump: status=failed reason=FileSystemException/"),
                reported);
    }

    @Test
    void neverThrowsWhenTheReportSinkFails() {
        var options = new RecordingVmOptions();

        assertDoesNotThrow(() -> HeapDumpConfiguration.configure(tempDir,
                () -> options, message -> {
                    throw new IllegalStateException("expected");
                }));

        assertEquals(
                tempDir.resolve(HeapDumpConfiguration.DIRECTORY_NAME).toString(),
                options.value(HeapDumpConfiguration.PATH_OPTION));
    }

    @Test
    void reportsExactlyOneLinePerCall() {
        List<String> reported = new ArrayList<>();

        HeapDumpConfiguration.configure(tempDir, RecordingVmOptions::new,
                reported::add);

        assertEquals(1, reported.size(), reported.toString());
    }

    private String reportOf(Path stateLocation, VmOptions options) {
        List<String> reported = new ArrayList<>();
        HeapDumpConfiguration.configure(stateLocation, () -> options,
                reported::add);
        assertEquals(1, reported.size(), reported.toString());
        return reported.get(0);
    }

    private static final class RecordingVmOptions implements VmOptions {

        private final Map<String, String> values = new HashMap<>();
        private final List<String> writes = new ArrayList<>();
        private final List<Boolean> directoryExistedOnWrite = new ArrayList<>();
        private Path watched;
        private boolean refuseWrites;

        RecordingVmOptions() {
            values.put(HeapDumpConfiguration.PATH_OPTION, "");
            values.put(HeapDumpConfiguration.ON_OOME_OPTION, "true");
        }

        void put(String name, String value) {
            values.put(name, value);
        }

        void refuseWrites() {
            refuseWrites = true;
        }

        void watchForDirectory(Path directory) {
            watched = directory;
        }

        List<String> writes() {
            return List.copyOf(writes);
        }

        List<Boolean> directoryExistedOnWrite() {
            return List.copyOf(directoryExistedOnWrite);
        }

        String value(String name) {
            return values.get(name);
        }

        @Override
        public String get(String name) {
            return values.getOrDefault(name, "");
        }

        @Override
        public void set(String name, String value) {
            writes.add(name + '=' + value);
            if (watched != null) {
                directoryExistedOnWrite.add(Files.isDirectory(watched));
            }
            if (!refuseWrites) {
                values.put(name, value);
            }
        }
    }
}
