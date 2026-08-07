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
package ru.taximaxim.codekeeper.ui.settings;

/**
 * Hardware-scaled default for the parser worker preferences.
 * <p>
 * The tuned worker counts used to live only in the standalone product's
 * {@code plugin_customization.ini}, so anyone running pgCodeKeeper as a plug-in
 * inside their own Eclipse kept the conservative two-worker defaults. The
 * defaults are computed here instead, which makes both deployments share them.
 * <p>
 * The formula is {@code clamp(availableProcessors / 2, 2, 8)}:
 * <ul>
 * <li>Half of the logical CPUs, because parser workers are not the only load:
 * the comparison also runs JDBC catalog readers, the workspace builders, and
 * the UI thread on the same machine. The measured optimum for the standalone
 * product was six workers on a twelve-CPU machine, which is exactly what this
 * ratio produces; eight workers were slower in that same workload.</li>
 * <li>A floor of two keeps the previous conservative default on small machines
 * and never degrades a two-CPU box to a single worker.</li>
 * <li>A ceiling of eight bounds the pool on many-core machines. Past the
 * measured optimum the extra workers only add heap pressure and contention on
 * the shared parser caches, so a 32-CPU machine gains nothing from a 16-worker
 * pool.</li>
 * </ul>
 * The result is only a preference default: an explicit value stored in the
 * preference store always wins, and users may still set anything between
 * {@link ParserWorkerPreferences#MIN_WORKERS} and
 * {@link ParserWorkerPreferences#MAX_WORKERS}.
 */
public final class ParserWorkerDefaults {

    /**
     * Never fewer workers than the previous conservative generic default.
     */
    public static final int MIN_DEFAULT_WORKERS =
            ParserWorkerPreferences.GENERIC_DEFAULT_WORKERS;

    /**
     * Never more workers than this, whatever the CPU count is.
     */
    public static final int MAX_DEFAULT_WORKERS = 8;

    private static final int CPUS_PER_WORKER = 2;

    private ParserWorkerDefaults() {
    }

    /**
     * @return the default worker count for this machine
     */
    public static int defaultWorkers() {
        return scale(Runtime.getRuntime().availableProcessors());
    }

    /**
     * @param availableProcessors logical CPU count reported by the JVM
     * @return the bounded worker count for that CPU count
     */
    public static int scale(int availableProcessors) {
        if (availableProcessors < 1) {
            // A JVM that cannot count its CPUs must not disable parallelism.
            return MIN_DEFAULT_WORKERS;
        }
        return Math.clamp(availableProcessors / CPUS_PER_WORKER,
                MIN_DEFAULT_WORKERS, MAX_DEFAULT_WORKERS);
    }
}
