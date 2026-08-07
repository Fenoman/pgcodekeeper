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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import ru.taximaxim.codekeeper.ui.DatabaseType;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexConfiguration.GlobalSettings;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexConfiguration.ProjectOverrides;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetry.ConfigurationGuard;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetry.Mode;

/**
 * The last question a build asks before it publishes: is the configuration it
 * worked by still the one the workbench has settled on.
 *
 * <p>A build that answers no is complete and consistent with itself and worth
 * nothing - every later check of the index it produced will find an identity
 * it does not expect and buy a 48 to 105 second rebuild. Refusing costs the
 * build that was already made; publishing costs that one and the next one.</p>
 */
class ProjectIndexPublicationGuardTest {

    private static final GlobalSettings WORKSPACE =
            new GlobalSettings(false, false, false, false, "");

    private static final String HEAD = "pgCodeKeeper project index: mode=cold";

    private static final String TAIL =
            " persistence_status=not_attempted persistence_reason=none"
                    + " paths_enumerated=0 enumeration_passes=0"
                    + " single_file_validations=0 paths_hashed=0"
                    + " paths_hash_inline=0 paths_hash_reread=0"
                    + " paths_parsed=unknown paths_analyzed=unknown"
                    + " elapsed_ms=0";

    /** The line of a run that was never refused a publication. */
    private static final String WITHOUT_GUARD = HEAD + TAIL;

    /** A token is a token: no path, no space, nothing to leak. */
    private static final Pattern TOKEN = Pattern.compile("[a-z0-9_]+");

    @Test
    void aBuildWhoseConfigurationStillStandsPublishes() {
        String settled = fingerprint("dummy_tmp");

        assertEquals(Optional.empty(),
                ProjectIndexPublicationGuard.refusal(settled, settled));
    }

    @Test
    void aBuildWhoseConfigurationMovedDoesNotPublish() {
        assertEquals(Optional.of(ConfigurationGuard.MOVED),
                ProjectIndexPublicationGuard.refusal(fingerprint(""),
                        fingerprint("dummy_tmp")),
                "the build that enumerated 22 384 files, published into a "
                        + "workbench that excludes dummy_tmp");
        assertEquals(Optional.of(ConfigurationGuard.MOVED),
                ProjectIndexPublicationGuard.refusal(fingerprint("dummy_tmp"),
                        fingerprint("")),
                "and the other way round");
    }

    /**
     * Fail-closed. A build that could not see the configuration must not
     * conclude that it did not move: the index is worth less than the rebuild.
     */
    @Test
    void aConfigurationThatCannotBeReadRefusesRatherThanAssumingItHeld() {
        assertEquals(Optional.of(ConfigurationGuard.UNREADABLE),
                ProjectIndexPublicationGuard.refusal(fingerprint("dummy_tmp"),
                        ProjectIndexPublicationGuard.UNREADABLE_PREFIX + "7"));
    }

    /**
     * The counter is what makes the refusal fail-closed rather than merely
     * likely: no two unreadable answers are equal, so none of them can ever
     * be equal to a build either.
     */
    @Test
    void noTwoUnreadableAnswersAreEqual() {
        var failures = new AtomicInteger();
        var seen = new HashSet<String>();
        for (int attempt = 0; attempt < 4; attempt++) {
            String unreadable = ProjectIndexPublicationGuard.UNREADABLE_PREFIX
                    + failures.incrementAndGet();
            assertTrue(seen.add(unreadable));
            assertEquals(Optional.of(ConfigurationGuard.UNREADABLE),
                    ProjectIndexPublicationGuard.refusal(unreadable,
                            unreadable),
                    "an unreadable configuration matched itself");
        }
    }

    @Test
    void neitherSideOfTheComparisonMayBeAbsent() {
        assertThrows(NullPointerException.class, () ->
                ProjectIndexPublicationGuard.refusal(null,
                        fingerprint("dummy_tmp")));
        assertThrows(NullPointerException.class, () ->
                ProjectIndexPublicationGuard.refusal(fingerprint("dummy_tmp"),
                        null));
    }

    @Test
    void aRunNeverRefusedAPublicationSaysNothingAboutOne() {
        assertEquals(WITHOUT_GUARD, publishedLine(run -> {
            // A build the guard let through.
        }));
        assertFalse(WITHOUT_GUARD.contains("config_guard="),
                "an absent field is what an unrefused build looks like");
    }

    @Test
    void eachRefusalPublishesItsOwnToken() {
        assertEquals(HEAD + TAIL.replace(" elapsed_ms=0",
                " config_guard=moved elapsed_ms=0"),
                publishedLine(run -> run.configurationGuard(
                        ConfigurationGuard.MOVED)));
        assertEquals(HEAD + TAIL.replace(" elapsed_ms=0",
                " config_guard=unreadable elapsed_ms=0"),
                publishedLine(run -> run.configurationGuard(
                        ConfigurationGuard.UNREADABLE)));
    }

    @Test
    void everyRefusalIsItsOwnBareToken() {
        var tokens = new HashSet<String>();
        for (ConfigurationGuard reason : ConfigurationGuard.values()) {
            String token = guardToken(
                    publishedLine(run -> run.configurationGuard(reason)));
            assertTrue(TOKEN.matcher(token).matches(),
                    reason + " published " + token
                            + ", which is not a bare token");
            assertTrue(tokens.add(token),
                    reason + " published " + token
                            + ", which another refusal already publishes");
        }
        assertEquals(ConfigurationGuard.values().length, tokens.size());
    }

    /**
     * Publication is asked about once per path a build can leave by, and the
     * first path to refuse is the one that decided the fate of the build.
     */
    @Test
    void theFirstRefusalIsTheOneThatIsPublished() {
        assertEquals(HEAD + TAIL.replace(" elapsed_ms=0",
                " config_guard=moved elapsed_ms=0"),
                publishedLine(run -> {
                    run.configurationGuard(ConfigurationGuard.MOVED);
                    run.configurationGuard(ConfigurationGuard.UNREADABLE);
                }));
    }

    @Test
    void aRefusalOutlivesEveryBranchTheRunDeclaresAfterIt() {
        assertTrue(publishedLine(run -> {
            run.configurationGuard(ConfigurationGuard.MOVED);
            run.mode(Mode.INCREMENTAL);
        }).contains(" config_guard=moved "));
    }

    private static String fingerprint(String excludedSchemas) {
        return ProjectIndexConfiguration.resolve(DatabaseType.PG, true,
                WORKSPACE,
                new ProjectOverrides(true, false, true, false, excludedSchemas))
                .digest();
    }

    private static String guardToken(String line) {
        int start = line.indexOf(" config_guard=");
        assertTrue(start >= 0, "no guard in: " + line);
        start += " config_guard=".length();
        int end = line.indexOf(' ', start);
        return end < 0 ? line.substring(start) : line.substring(start, end);
    }

    /**
     * Publishes one run, driven identically every time except for what it is
     * told about the guard.
     *
     * @param guard what this run learns about its publication
     * @return the single line the run published
     */
    private static String publishedLine(
            Consumer<ProjectIndexTelemetry.Run> guard) {
        List<String> lines = new ArrayList<>();
        var run = new ProjectIndexTelemetry(lines::add, () -> 0)
                .start(Mode.COLD);

        guard.accept(run);
        run.close();

        assertEquals(1, lines.size());
        return lines.getFirst();
    }
}
