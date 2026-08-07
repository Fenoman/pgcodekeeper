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
package ru.taximaxim.codekeeper.ui.projectindex;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

import ru.taximaxim.codekeeper.ui.PerformanceTelemetry;
import ru.taximaxim.codekeeper.ui.prefs.Preferences.ChangeImpact;

/**
 * Publishes two secret-free lines naming who retires a persisted project index.
 * <p>
 * Every full build observed so far arrives as {@code direct_full_build}, so the
 * platform is asking for it and the delta classifier never refused anything. One
 * suspect asks for exactly that: a workspace refresh re-reads
 * {@code .settings/ru.taximaxim.codekeeper.ui.prefs}, and the platform applies a
 * file by removing and re-adding each key, firing a change event from inside
 * {@code remove} while the key is gone. If the configuration fingerprint differs
 * at that moment, the fingerprint guard lets the invalidation through and the
 * index is dropped for a setting nobody edited.
 * <p>
 * The lines below turn that story into a reading: which key was routed, and
 * whether the guard held. They did: fourteen changes of an untouched project
 * file within one second, two of them retiring the index. The editor now waits
 * for the node to settle and re-reads it, which is what the third line reports.
 * Diagnostics observe a build and may never change one: every method here is
 * total and swallows its own failures.
 */
public final class ProjectIndexConfigurationDiagnostics {

    public static final ProjectIndexConfigurationDiagnostics INSTANCE =
            new ProjectIndexConfigurationDiagnostics(
                    PerformanceTelemetry::publish);

    /** No caller named itself, so the line cannot say where it came from. */
    public static final String ORIGIN_UNKNOWN = "unknown"; //$NON-NLS-1$
    /** A preference change routed by an open project editor. */
    public static final String ORIGIN_EDITOR_PREFERENCE =
            "editor_preference"; //$NON-NLS-1$
    /** A workspace preference page whose change reached this project. */
    public static final String ORIGIN_GLOBAL_PREFERENCES =
            "global_preferences"; //$NON-NLS-1$
    /** The project property page, applied or reset by a user. */
    public static final String ORIGIN_PROJECT_PROPERTIES =
            "project_properties"; //$NON-NLS-1$

    private static final int MAX_EVIDENCE_CHARS = 64;

    private final Consumer<String> logger;

    ProjectIndexConfigurationDiagnostics(Consumer<String> logger) {
        this.logger = Objects.requireNonNull(logger, "logger"); //$NON-NLS-1$
    }

    /**
     * Preference node a change arrived from. The workspace node and the
     * project's own file are edited by different people for different reasons,
     * and only the project file is re-applied by a workspace refresh.
     */
    public enum PreferenceNode {
        MAIN("main"), //$NON-NLS-1$
        PROJECT("project"); //$NON-NLS-1$

        private final String token;

        PreferenceNode(String token) {
            this.token = token;
        }

        String token() {
            return token;
        }
    }

    /**
     * Whether a parser was live for the project. Without one the invalidation
     * takes the fail-closed branch, which costs a whole persisted index rather
     * than an exact receipt.
     */
    public enum ParserPresence {
        LIVE("live"), //$NON-NLS-1$
        ABSENT("absent"); //$NON-NLS-1$

        private final String token;

        ParserPresence(String token) {
            this.token = token;
        }

        String token() {
            return token;
        }
    }

    /**
     * Reports one preference change and where it was routed. Changes nothing
     * depends on are dropped: one re-read of the project file would otherwise
     * bury every other line in the bounded telemetry buffer.
     *
     * @param key      name of the preference that changed
     * @param impact   what the routing table says the change invalidates
     * @param node     node the change arrived from
     * @param uiThread whether the handler ran on the UI thread
     */
    public void publishPreferenceChange(String key, ChangeImpact impact,
            PreferenceNode node, boolean uiThread) {
        try {
            if (impact != ChangeImpact.NONE) {
                logger.accept(formatPreferenceChange(key, impact, node,
                        uiThread));
            }
        } catch (RuntimeException ex) {
            // Diagnostics must never change what the workbench does.
        }
    }

    /**
     * @param key      name of the preference that changed
     * @param impact   what the routing table says the change invalidates
     * @param node     node the change arrived from
     * @param uiThread whether the handler ran on the UI thread
     * @return the line describing this preference change
     */
    public String formatPreferenceChange(String key, ChangeImpact impact,
            PreferenceNode node, boolean uiThread) {
        return new StringBuilder(128)
                .append("pgCodeKeeper preference change: key=") //$NON-NLS-1$
                .append(sanitize(key))
                .append(" impact=") //$NON-NLS-1$
                .append(impact.name().toLowerCase(Locale.ROOT))
                .append(" node=") //$NON-NLS-1$
                .append(node.token())
                .append(" ui_thread=") //$NON-NLS-1$
                .append(uiThread)
                .toString();
    }

    /**
     * Reports that a preference change was observed but not acted on yet. The
     * line closes the gap the other two would otherwise leave: a change routed
     * with no invalidation behind it now says why, and the count of deferrals
     * against the count of invalidations is exactly how much a burst was
     * collapsed by.
     *
     * @param origin      short tag of the call site that observed the change
     * @param delayMillis how long the node is given to settle
     */
    public void publishDeferral(String origin, long delayMillis) {
        try {
            logger.accept(formatDeferral(origin, delayMillis));
        } catch (RuntimeException ex) {
            // Diagnostics must never change what the workbench does.
        }
    }

    /**
     * @param origin      short tag of the call site that observed the change
     * @param delayMillis how long the node is given to settle
     * @return the line describing this deferral
     */
    public String formatDeferral(String origin, long delayMillis) {
        return new StringBuilder(128)
                .append("pgCodeKeeper project index configuration deferred: delay_ms=") //$NON-NLS-1$
                .append(delayMillis)
                .append(" origin=") //$NON-NLS-1$
                .append(sanitize(origin))
                .toString();
    }

    /**
     * Reports one invalidation attempt and whether the fingerprint guard let it
     * through. This is the measurement: {@code invalidated=false} means the
     * configuration was identical and the index survived.
     *
     * @param invalidated whether the fingerprint differed and the index was
     *                    retired
     * @param parser      whether a live parser held the project
     * @param origin      short tag of the call site that asked
     */
    public void publishInvalidation(boolean invalidated,
            ParserPresence parser, String origin) {
        try {
            logger.accept(formatInvalidation(invalidated, parser, origin));
        } catch (RuntimeException ex) {
            // Diagnostics must never change what the workbench does.
        }
    }

    /**
     * @param invalidated whether the fingerprint differed and the index was
     *                    retired
     * @param parser      whether a live parser held the project
     * @param origin      short tag of the call site that asked
     * @return the line describing this invalidation attempt
     */
    public String formatInvalidation(boolean invalidated,
            ParserPresence parser, String origin) {
        return new StringBuilder(128)
                .append("pgCodeKeeper project index configuration: invalidated=") //$NON-NLS-1$
                .append(invalidated)
                .append(" parser=") //$NON-NLS-1$
                .append(parser.token())
                .append(" origin=") //$NON-NLS-1$
                .append(sanitize(origin))
                .toString();
    }

    /**
     * Reduces a name to a bounded run of characters that cannot break the line
     * it lands in. A preference key is read out of a file a project ships, so a
     * project may in principle put anything into it.
     */
    /**
     * Reports the inputs a configuration was resolved from, not just the answer.
     *
     * <p>The telemetry already says how many paths a build enumerated, which is
     * where a wrong exclusion list shows up -- 22 386 instead of 11 693. It does
     * not say why, and the two candidates leave the same trace: a project whose
     * overrides were ignored because the root flag read false, and a project
     * whose overrides were read but empty. This line separates them.</p>
     *
     * @param projectPreferencesEnabled whether project overrides were honoured
     * @param globalExclusions          schemas named by the global preferences
     * @param projectExclusions         schemas named by the project, or null
     *                                  when the project names none
     * @param effectiveExclusions       what the build will actually leave out
     * @param origin                    short tag of the call site that read it
     */
    public void publishConfigurationRead(boolean projectPreferencesEnabled,
            String globalExclusions, String projectExclusions,
            String effectiveExclusions, String origin) {
        try {
            logger.accept(formatConfigurationRead(projectPreferencesEnabled,
                    globalExclusions, projectExclusions, effectiveExclusions,
                    origin));
        } catch (RuntimeException ex) {
            // Diagnostics must never change what the workbench does.
        }
    }

    /**
     * @param projectPreferencesEnabled whether project overrides were honoured
     * @param globalExclusions          schemas named by the global preferences
     * @param projectExclusions         schemas named by the project, or null
     * @param effectiveExclusions       what the build will actually leave out
     * @param origin                    short tag of the call site that read it
     * @return the line describing this read
     */
    public String formatConfigurationRead(boolean projectPreferencesEnabled,
            String globalExclusions, String projectExclusions,
            String effectiveExclusions, String origin) {
        return new StringBuilder(192)
                .append("pgCodeKeeper project index configuration read: proj_pref_root=") //$NON-NLS-1$
                .append(projectPreferencesEnabled)
                .append(" global_excluded=") //$NON-NLS-1$
                .append(describe(globalExclusions))
                .append(" project_excluded=") //$NON-NLS-1$
                .append(describe(projectExclusions))
                .append(" effective_excluded=") //$NON-NLS-1$
                .append(describe(effectiveExclusions))
                .append(" origin=") //$NON-NLS-1$
                .append(sanitize(origin))
                .toString();
    }

    /**
     * Names an exclusion list without spelling out schema names: a count, and
     * the difference between "the project said nothing" and "the project said
     * nothing to exclude".
     *
     * @param exclusions the raw preference value, may be null
     * @return unset, none, or the number of names in it
     */
    private static String describe(String exclusions) {
        if (exclusions == null) {
            return "unset"; //$NON-NLS-1$
        }
        long names = exclusions.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#")) //$NON-NLS-1$
                .count();
        return names == 0 ? "none" : Long.toString(names); //$NON-NLS-1$
    }

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
