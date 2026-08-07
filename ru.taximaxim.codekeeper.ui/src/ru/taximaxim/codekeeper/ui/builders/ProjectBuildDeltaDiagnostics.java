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
package ru.taximaxim.codekeeper.ui.builders;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

import org.eclipse.core.resources.IncrementalProjectBuilder;

import ru.taximaxim.codekeeper.ui.PerformanceTelemetry;
import ru.taximaxim.codekeeper.ui.builders.ProjectBuildDeltaClassifier.Result;

/**
 * Publishes one secret-free line naming why a build took the branch it took.
 * <p>
 * The index already reports the branch it ran ({@code mode=cold} and the rest),
 * but not who chose it. A cold run of an unchanged project is either a delta the
 * classifier could not narrow or a continuity gap, and the two want opposite
 * fixes. This line names the single check that decided, so the answer is read
 * off a log instead of guessed.
 * <p>
 * Diagnostics observe a build and may never change one: every method here is
 * total and swallows its own failures.
 */
final class ProjectBuildDeltaDiagnostics {

    static final ProjectBuildDeltaDiagnostics INSTANCE =
            new ProjectBuildDeltaDiagnostics(PerformanceTelemetry::publish);

    private static final int MAX_EVIDENCE_CHARS = 64;

    private final Consumer<String> logger;

    ProjectBuildDeltaDiagnostics(Consumer<String> logger) {
        this.logger = Objects.requireNonNull(logger, "logger"); //$NON-NLS-1$
    }

    /**
     * Whether the builder held a delta for this run. A full build never asks
     * for one, and that is not the same as asking and getting nothing.
     */
    enum DeltaPresence {
        PRESENT("true"), //$NON-NLS-1$
        ABSENT("false"), //$NON-NLS-1$
        NOT_CONSULTED("n_a"); //$NON-NLS-1$

        private final String token;

        DeltaPresence(String token) {
            this.token = token;
        }

        String token() {
            return token;
        }
    }

    /**
     * Reports one build classification.
     *
     * @param buildKind  kind the platform asked for
     * @param presence   whether a delta was held for this run
     * @param classified verdict of the classifier, before continuity
     * @param effective  verdict the build ran with, after continuity
     * @param proofKind  continuity proof the run holds
     */
    void publish(int buildKind, DeltaPresence presence, Result classified,
            Result effective, ProjectBuildContinuity.Kind proofKind) {
        try {
            logger.accept(format(buildKind, presence, classified,
                    effective, proofKind));
        } catch (RuntimeException ex) {
            // Diagnostics must never change what a build does.
        }
    }

    /**
     * @param buildKind  kind the platform asked for
     * @param presence   whether a delta was held for this run
     * @param classified verdict of the classifier, before continuity
     * @param effective  verdict the build ran with, after continuity
     * @param proofKind  continuity proof the run holds
     * @return the line describing this classification
     */
    String format(int buildKind, DeltaPresence presence, Result classified,
            Result effective, ProjectBuildContinuity.Kind proofKind) {
        var message = new StringBuilder(256)
                .append("pgCodeKeeper project build delta: build_kind=") //$NON-NLS-1$
                .append(buildKind(buildKind))
                .append(" delta_present=") //$NON-NLS-1$
                .append(presence.token())
                .append(" verdict=") //$NON-NLS-1$
                .append(effective.mode().token())
                .append(" reason=") //$NON-NLS-1$
                .append(effective.reason().token())
                .append(" paths=") //$NON-NLS-1$
                .append(effective.relativePaths().size())
                .append(" continuity=") //$NON-NLS-1$
                .append(proofKind.name().toLowerCase(Locale.ROOT));
        if (effective.evidence() != null) {
            message.append(" evidence=") //$NON-NLS-1$
                    .append(sanitize(effective.evidence()));
        }
        if (classified.reason() != effective.reason()) {
            message.append(" classifier_reason=") //$NON-NLS-1$
                    .append(classified.reason().token());
        }
        return message.toString();
    }

    private static String buildKind(int value) {
        return switch (value) {
        case IncrementalProjectBuilder.AUTO_BUILD -> "auto"; //$NON-NLS-1$
        case IncrementalProjectBuilder.INCREMENTAL_BUILD -> "incremental"; //$NON-NLS-1$
        case IncrementalProjectBuilder.FULL_BUILD -> "full"; //$NON-NLS-1$
        case IncrementalProjectBuilder.CLEAN_BUILD -> "clean"; //$NON-NLS-1$
        default -> "other_" + value; //$NON-NLS-1$
        };
    }

    /**
     * Reduces evidence to a bounded run of characters that cannot break the
     * line it lands in. Evidence is assembled from counters and path segments,
     * so a project may in principle put anything into it.
     */
    private static String sanitize(String evidence) {
        int length = Math.min(evidence.length(), MAX_EVIDENCE_CHARS);
        var sanitized = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            char symbol = evidence.charAt(i);
            sanitized.append(isSafe(symbol) ? symbol : '_');
        }
        return sanitized.toString();
    }

    private static boolean isSafe(char symbol) {
        return symbol >= 'A' && symbol <= 'Z'
                || symbol >= 'a' && symbol <= 'z'
                || symbol >= '0' && symbol <= '9'
                || symbol == '.' || symbol == '_' || symbol == '='
                || symbol == ',' || symbol == '+' || symbol == '/'
                || symbol == '-';
    }
}
