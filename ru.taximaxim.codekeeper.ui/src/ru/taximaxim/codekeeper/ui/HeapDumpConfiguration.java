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

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.sun.management.HotSpotDiagnosticMXBean;

/**
 * Gives {@code -XX:+HeapDumpOnOutOfMemoryError} a place it is allowed to write.
 * <p>
 * The product enables the dump but cannot name a directory: {@code HeapDumpPath}
 * has to be a literal absolute path, and the launcher substitutions available in
 * the product configuration never reach the virtual machine. Left empty, HotSpot
 * writes {@code java_pid<pid>.hprof} relative to the <em>process</em> working
 * directory, which is not the same thing as {@code user.dir}. A macOS bundle is
 * started with the read-only filesystem root as its working directory and the
 * Eclipse launcher later moves it inside the application bundle, so the dump
 * either cannot be created at all or lands in a directory that the next build
 * of the product overwrites. Both outcomes are silent: a bundled application has
 * its standard streams on {@code /dev/null}, so the "Unable to create ..." line
 * HotSpot prints is lost.
 * <p>
 * {@code HeapDumpPath} is a manageable flag, so the directory is installed here
 * instead, once the workspace is known. Nothing in this class may throw: it runs
 * during bundle activation and it is only diagnostics. Every outcome, including
 * every failure, is reported as one line in the same {@code performance.log}
 * that {@link PerformanceTelemetry} keeps, because a diagnostic that is switched
 * on and quietly does nothing is what this class exists to prevent.
 */
public final class HeapDumpConfiguration {

    static final String DIRECTORY_NAME = "heapdumps"; //$NON-NLS-1$
    static final String PATH_OPTION = "HeapDumpPath"; //$NON-NLS-1$
    static final String ON_OOME_OPTION = "HeapDumpOnOutOfMemoryError"; //$NON-NLS-1$
    static final String PREFIX = "pgCodeKeeper heap dump: "; //$NON-NLS-1$
    private static final int MAX_FIELD_CHARS = 512;

    /**
     * Reads and writes virtual machine options. Separated from the diagnostic
     * bean so that the decisions around it can be exercised without one.
     */
    interface VmOptions {

        /**
         * @param name option to read
         * @return current value, possibly empty, never {@code null}
         */
        String get(String name);

        /**
         * @param name  option to write
         * @param value value to install
         */
        void set(String name, String value);
    }

    /**
     * Installs the workspace heap dump directory. Called once, from bundle
     * activation, after {@link PerformanceTelemetry} is running.
     */
    static void configure() {
        configure(stateLocation(), HeapDumpConfiguration::hotSpotOptions,
                PerformanceTelemetry::publish);
    }

    /**
     * @param stateLocation workspace metadata directory of this bundle, or
     *                      {@code null} when the workspace is unknown
     * @param options       supplier of the virtual machine option interface
     * @param report        sink for the single line this call produces
     */
    static void configure(Path stateLocation, Supplier<VmOptions> options,
            Consumer<String> report) {
        String outcome;
        try {
            outcome = apply(stateLocation, options.get());
        } catch (Exception | LinkageError ex) {
            outcome = "status=failed reason=" + describe(ex); //$NON-NLS-1$
        }
        try {
            report.accept(PREFIX + outcome);
        } catch (RuntimeException ex) {
            // A diagnostic that cannot be reported must still not break startup.
        }
    }

    private static String apply(Path stateLocation, VmOptions options)
            throws IOException {
        if (stateLocation == null) {
            return "status=skipped reason=state_location_unavailable"; //$NON-NLS-1$
        }
        if (options == null) {
            return "status=skipped reason=diagnostic_bean_unavailable"; //$NON-NLS-1$
        }

        String configured = options.get(PATH_OPTION);
        Path preferred = stateLocation.resolve(DIRECTORY_NAME);
        Optional<Path> chosen = chooseDirectory(configured, preferred,
                HeapDumpConfiguration::isUsableDirectory);
        if (chosen.isEmpty()) {
            return "status=kept" + onOutOfMemory(options) //$NON-NLS-1$
                    + " path=" + sanitize(configured); //$NON-NLS-1$
        }

        Path directory = chosen.get();
        // The virtual machine cannot create anything once the heap is gone, so
        // the directory has to exist before the dump is ever needed.
        Files.createDirectories(directory);
        if (!isUsableDirectory(directory)) {
            return "status=failed reason=directory_not_writable path=" //$NON-NLS-1$
                    + sanitize(directory.toString());
        }

        String wanted = directory.toString();
        options.set(PATH_OPTION, wanted);
        String applied = options.get(PATH_OPTION);
        if (!wanted.equals(applied)) {
            return "status=failed reason=option_rejected path=" //$NON-NLS-1$
                    + sanitize(applied);
        }
        return "status=configured" + onOutOfMemory(options) //$NON-NLS-1$
                + " path=" + sanitize(wanted); //$NON-NLS-1$
    }

    /**
     * Decides where heap dumps have to land. A value that somebody set on
     * purpose is kept; only a value the virtual machine cannot write to is
     * replaced.
     *
     * @param configured      current {@code HeapDumpPath}, empty when the
     *                        virtual machine would fall back to the process
     *                        working directory
     * @param preferred       workspace directory to install when the current
     *                        value cannot be trusted
     * @param usableDirectory tells whether a path is an existing writable
     *                        directory
     * @return directory to install, or empty when the current value is kept
     */
    static Optional<Path> chooseDirectory(String configured, Path preferred,
            Predicate<Path> usableDirectory) {
        Objects.requireNonNull(preferred, "preferred"); //$NON-NLS-1$
        Objects.requireNonNull(usableDirectory, "usableDirectory"); //$NON-NLS-1$
        if (configured == null || configured.isBlank()) {
            return Optional.of(preferred);
        }

        Path current;
        try {
            current = Path.of(configured);
        } catch (InvalidPathException ex) {
            return Optional.of(preferred);
        }
        if (!current.isAbsolute()) {
            // Resolved against the process working directory, which is the one
            // place this class may not rely on.
            return Optional.of(preferred);
        }

        // HotSpot appends java_pid<pid>.hprof when the value names a directory
        // and treats it as a file name otherwise, so in the second case it is
        // the parent that has to be writable.
        if (usableDirectory.test(current)) {
            return Optional.empty();
        }
        Path parent = current.getParent();
        if (parent != null && usableDirectory.test(parent)) {
            return Optional.empty();
        }
        return Optional.of(preferred);
    }

    static boolean isUsableDirectory(Path path) {
        try {
            return Files.isDirectory(path) && Files.isWritable(path);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static Path stateLocation() {
        try {
            Activator plugin = Activator.getDefault();
            if (plugin != null) {
                return Path.of(plugin.getStateLocation().toOSString());
            }
        } catch (RuntimeException ex) {
            // Reported as an unavailable state location.
        }
        return null;
    }

    private static VmOptions hotSpotOptions() {
        HotSpotDiagnosticMXBean diagnostics = ManagementFactory
                .getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        if (diagnostics == null) {
            return null;
        }
        return new VmOptions() {

            @Override
            public String get(String name) {
                return diagnostics.getVMOption(name).getValue();
            }

            @Override
            public void set(String name, String value) {
                diagnostics.setVMOption(name, value);
            }
        };
    }

    /**
     * @return the dump-on-exhaustion switch, so that a path installed against a
     *         disabled switch is visible instead of looking like a working setup
     */
    private static String onOutOfMemory(VmOptions options) {
        String value;
        try {
            value = options.get(ON_OOME_OPTION);
        } catch (RuntimeException ex) {
            value = null;
        }
        return " on_oome=" + (value == null || value.isBlank() //$NON-NLS-1$
                ? "unknown" : sanitize(value)); //$NON-NLS-1$
    }

    private static String describe(Throwable ex) {
        String message = ex.getMessage();
        return sanitize(ex.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : "/" + message)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Reduces a value to a bounded run of characters that cannot break the line
     * it lands in. Paths, option values and failure messages all come from
     * outside this bundle.
     */
    private static String sanitize(String value) {
        if (value == null) {
            return "none"; //$NON-NLS-1$
        }
        int length = Math.min(value.length(), MAX_FIELD_CHARS);
        var sanitized = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            char symbol = value.charAt(i);
            sanitized.append(isSafe(symbol) ? symbol : '_');
        }
        return length == 0 ? "empty" : sanitized.toString(); //$NON-NLS-1$
    }

    private static boolean isSafe(char symbol) {
        return symbol >= 'A' && symbol <= 'Z'
                || symbol >= 'a' && symbol <= 'z'
                || symbol >= '0' && symbol <= '9'
                || symbol == '.' || symbol == '_' || symbol == '='
                || symbol == ',' || symbol == '+' || symbol == '/'
                || symbol == '\\' || symbol == ':' || symbol == '-';
    }

    private HeapDumpConfiguration() {
    }
}
