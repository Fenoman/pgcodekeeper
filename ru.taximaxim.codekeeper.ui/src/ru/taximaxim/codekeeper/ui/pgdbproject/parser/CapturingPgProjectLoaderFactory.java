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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.pgcodekeeper.core.analysis.AnalysisReplayPayload;
import org.pgcodekeeper.core.api.ComparisonDepth;
import org.pgcodekeeper.core.database.api.loader.ILoader;
import org.pgcodekeeper.core.database.api.loader.ILoaderFactory;
import org.pgcodekeeper.core.database.api.loader.IProjectInputFingerprintCapture;
import org.pgcodekeeper.core.database.api.loader.ProjectInputFingerprint;
import org.pgcodekeeper.core.database.pg.loader.PgProjectLoader;
import org.pgcodekeeper.core.database.pg.loader.PgProjectLoader.InputFingerprintValidator;
import org.pgcodekeeper.core.database.pg.routine.ReusableProjectRoutineBodySnapshot;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.settings.ISettings;

/**
 * Enables both reusable-model captures on the exact PostgreSQL project loader
 * constructed by the Core comparison coordinator.
 */
final class CapturingPgProjectLoaderFactory implements ILoaderFactory {

    record Capture(
            ReusableProjectRoutineBodySnapshot routineSnapshot,
            List<ProjectInputFingerprint> inputFingerprints) {

        Capture {
            Objects.requireNonNull(routineSnapshot,
                    "routineSnapshot"); //$NON-NLS-1$
            inputFingerprints = List.copyOf(inputFingerprints);
        }
    }

    private final ILoaderFactory delegate;
    private final AnalysisReplayPayload replayPayload;
    private final ComparisonDepth depth;
    private final InputFingerprintValidator inputValidator;
    private final AtomicReference<PgProjectLoader> created =
            new AtomicReference<>();
    private volatile boolean analysisReplayed;

    CapturingPgProjectLoaderFactory(ILoaderFactory delegate) {
        this(delegate, null);
    }

    /**
     * @param replayPayload analysis result to replay instead of analyzing, or
     *                      {@code null} to analyze this project normally
     */
    CapturingPgProjectLoaderFactory(ILoaderFactory delegate,
            AnalysisReplayPayload replayPayload) {
        this(delegate, replayPayload, ComparisonDepth.FULL);
    }

    CapturingPgProjectLoaderFactory(ILoaderFactory delegate,
            AnalysisReplayPayload replayPayload, ComparisonDepth depth) {
        this(delegate, replayPayload, depth, null);
    }

    CapturingPgProjectLoaderFactory(ILoaderFactory delegate,
            AnalysisReplayPayload replayPayload, ComparisonDepth depth,
            InputFingerprintValidator inputValidator) {
        this.delegate = Objects.requireNonNull(
                delegate, "delegate"); //$NON-NLS-1$
        this.replayPayload = replayPayload;
        this.depth = Objects.requireNonNull(depth, "depth"); //$NON-NLS-1$
        this.inputValidator = inputValidator;
        if (depth != ComparisonDepth.FULL && replayPayload != null) {
            throw new IllegalArgumentException("Structural comparisons cannot replay analysis"); //$NON-NLS-1$
        }
    }

    @Override
    public void contributeCommonConfiguration(ISettings settings)
            throws IOException, InterruptedException {
        delegate.contributeCommonConfiguration(settings);
    }

    @Override
    public ILoader create(ISettings settings)
            throws IOException, InterruptedException {
        ILoader loader = delegate.create(settings);
        try {
            if (!(loader instanceof PgProjectLoader projectLoader)) {
                throw new IllegalArgumentException(
                        "Reusable model capture requires PgProjectLoader"); //$NON-NLS-1$
            }
            projectLoader.enableReusableModelCapture(depth);
            if (replayPayload != null) {
                projectLoader.enableAnalysisReplay(replayPayload);
            }
            ((IProjectInputFingerprintCapture) projectLoader)
                    .enableInputFingerprintCapture();
            if (inputValidator != null) {
                projectLoader.setInputFingerprintValidator(inputValidator);
            }
            if (!created.compareAndSet(null, projectLoader)) {
                throw new IllegalStateException(
                        "Project loader factory may be used only once"); //$NON-NLS-1$
            }
            return projectLoader;
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(loader, failure);
            throw failure;
        }
    }

    /**
     * Reports whether the loader served its analysis from the installed result
     * instead of analyzing the project. A payload that did not fit the parsed
     * model leaves this false, and the model was analyzed in full.
     *
     * @return true if the analysis phase was replayed
     */
    boolean isAnalysisReplayed() {
        PgProjectLoader loader = created.get();
        return loader != null ? loader.isAnalysisReplayed() : analysisReplayed;
    }

    Optional<Capture> takeCapture(PgDatabase expectedDatabase) {
        Objects.requireNonNull(expectedDatabase,
                "expectedDatabase"); //$NON-NLS-1$
        PgProjectLoader loader = created.getAndSet(null);
        if (loader == null) {
            return Optional.empty();
        }
        analysisReplayed = loader.isAnalysisReplayed();
        if (loader.getDatabase() != expectedDatabase) {
            return Optional.empty();
        }
        Optional<ReusableProjectRoutineBodySnapshot> routine =
                loader.takeReusableProjectRoutineBodySnapshot();
        if (routine.isEmpty()) {
            return Optional.empty();
        }
        List<ProjectInputFingerprint> inputs =
                ((IProjectInputFingerprintCapture) loader)
                        .getCapturedInputFingerprints();
        return Optional.of(new Capture(routine.orElseThrow(), inputs));
    }

    private static void closeAfterFailure(
            ILoader loader, Throwable failure) {
        try {
            loader.close();
        } catch (IOException | RuntimeException | Error closeFailure) {
            if (closeFailure != failure) {
                failure.addSuppressed(closeFailure);
            }
        }
    }
}
