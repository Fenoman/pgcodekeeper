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

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import org.pgcodekeeper.core.analysis.AnalysisReplayPayload;

import ru.taximaxim.codekeeper.ui.Log;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectFileStamp;
import ru.taximaxim.codekeeper.ui.settings.AnalysisCacheStorage;
import ru.taximaxim.codekeeper.ui.settings.EclipseComparisonTelemetry;
import ru.taximaxim.codekeeper.ui.settings.EclipseComparisonTelemetry.ProjectAnalysisStoreStatus;

/**
 * The analyzed-model cache that survives a restart.
 * <p>
 * The in-memory cache retires with its workbench; this one keeps the same
 * result on disk, so reopening a project costs a structural parse and a bounded
 * copy instead of a full analysis of every routine body and view. It is the
 * outer generation of one two-level cache and answers to exactly the same key.
 * <p>
 * Every uncertain state resolves to a cold run. A missing state location, a
 * store that cannot be read, an unreadable input file or a profile that no
 * longer matches all end here without a payload, and the comparison then loads
 * and analyzes the project as it always did.
 */
final class ProjectAnalysisCache {

    /**
     * Analysis result that may be replayed for one comparison.
     *
     * @param payload       stored analysis result
     * @param profileDigest digest it was stored under, re-checked once the
     *                      effective database version of this run is known
     */
    record Hit(AnalysisReplayPayload payload, String profileDigest) {

        Hit {
            Objects.requireNonNull(payload, "payload"); //$NON-NLS-1$
            Objects.requireNonNull(profileDigest, "profileDigest"); //$NON-NLS-1$
        }
    }

    private ProjectAnalysisCache() {
    }

    /**
     * Resolves the store of one project, or empty when this workbench has no
     * usable state location.
     *
     * @param projectRoot canonical project directory
     * @return the store, or empty when the feature cannot be offered
     */
    static Optional<ProjectAnalysisStore> store(Path projectRoot) {
        try {
            return AnalysisCacheStorage.directory(projectRoot)
                    .map(ProjectAnalysisStore::new);
        } catch (IOException | RuntimeException ex) {
            Log.log(Log.LOG_INFO,
                    "Analysis cache location is unavailable", ex); //$NON-NLS-1$
            return Optional.empty();
        }
    }

    /**
     * Reads the persisted analysis result of this project if it still describes
     * the current inputs under the current settings.
     *
     * @param store          store of this project
     * @param expectedDigest digest the current settings produce for a stored
     *                       effective database version
     * @param inputs         enumerates the current input files on demand
     * @param cancelled      cancellation probe of this run
     * @param telemetry      run telemetry, may be {@code null}
     * @return the replayable result, or empty for every other outcome
     * @throws InterruptedException if the run was cancelled
     */
    static Optional<Hit> open(ProjectAnalysisStore store,
            Function<ProjectComparisonProfile.EffectiveVersion,
                    Optional<String>> expectedDigest,
            ProjectAnalysisStore.CurrentInputs inputs,
            BooleanSupplier cancelled, EclipseComparisonTelemetry telemetry)
            throws InterruptedException {
        long start = telemetry == null ? 0 : telemetry.startTimer();
        ProjectAnalysisStore.OpenResult result =
                store.open(expectedDigest, inputs, cancelled);
        int hashed = result.validation() == null ? 0
                : result.validation().hashedFiles();
        publish(telemetry, status(result.status()), result.sizeBytes(),
                result.inspectedFiles(), hashed, start);
        if (result.status() == ProjectAnalysisStore.Status.CORRUPT) {
            // Only proven-wrong bytes are removed. A store that merely could
            // not be read this time stays for the next attempt.
            store.discard();
            return Optional.empty();
        }
        if (result.status() != ProjectAnalysisStore.Status.HIT) {
            return Optional.empty();
        }
        return Optional.of(new Hit(
                result.payload(), result.storedProfileDigest()));
    }

    private static ProjectAnalysisStoreStatus status(
            ProjectAnalysisStore.Status status) {
        return switch (status) {
            case HIT -> ProjectAnalysisStoreStatus.HIT;
            case MISS -> ProjectAnalysisStoreStatus.MISS;
            case STALE -> ProjectAnalysisStoreStatus.STALE;
            case CORRUPT -> ProjectAnalysisStoreStatus.CORRUPT;
            case RETRYABLE -> ProjectAnalysisStoreStatus.RETRYABLE;
        };
    }

    private static void publish(EclipseComparisonTelemetry telemetry,
            ProjectAnalysisStoreStatus status, long bytes, long filesInspected,
            long filesHashed, long start) {
        if (telemetry != null) {
            telemetry.projectAnalysisStoreFinished(
                    status, bytes, filesInspected, filesHashed, start);
        }
    }

    /**
     * Writes an analysis result for the next session. Failures are logged and
     * dropped: a cache that could not be written only costs the next start.
     *
     * @param store   store of this project
     * @param digest  digest of the settings the result was produced under
     * @param version effective database version of that profile
     * @param files   stamps of the inputs the result was produced from
     * @param payload analysis result to persist
     * @return number of bytes published, or zero on failure
     */
    static long publish(ProjectAnalysisStore store, String digest,
            ProjectComparisonProfile.EffectiveVersion version,
            List<ProjectFileStamp> files, AnalysisReplayPayload payload) {
        try {
            return store.publish(digest, version, files, payload);
        } catch (IOException | RuntimeException ex) {
            Log.log(Log.LOG_WARNING,
                    "Analysis cache could not be published", ex); //$NON-NLS-1$
            return 0;
        }
    }
}
