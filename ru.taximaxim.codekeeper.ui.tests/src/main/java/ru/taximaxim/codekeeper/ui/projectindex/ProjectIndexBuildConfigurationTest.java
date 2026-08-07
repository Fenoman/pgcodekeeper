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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import ru.taximaxim.codekeeper.ui.DatabaseType;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexConfiguration.GlobalSettings;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexConfiguration.ProjectOverrides;

/**
 * The measurement this answers: one live session enumerated 13 794 files under
 * {@code projectIndexExcludedSchemas=dummy_tmp} and 22 384 files without it,
 * and the difference of 8 590 is the excluded schema. Both builds were
 * internally consistent; what made one of them worthless is that the value it
 * enumerated by and the value it stamped its identity with came from two
 * separate readings of the preference node, taken at two separate moments.
 *
 * <p>The node moves for a whole second at a time: the platform applies a
 * preferences file by removing and re-adding every key, and reports each key
 * while it is gone. Nothing below asks whether that window is narrow. It asks
 * whether a build that lives entirely inside it still answers one value to
 * every question - which is the only form of the property that a moving node
 * cannot break.</p>
 */
class ProjectIndexBuildConfigurationTest {

    /**
     * The workspace an OmniX project inherits from: no exclusions, everything
     * off. Every key of the committed file below disagrees with it, which is
     * what makes a momentarily absent key visible at all.
     */
    private static final GlobalSettings WORKSPACE =
            new GlobalSettings(false, false, false, false, "");

    @Test
    void enumerationAndIdentityAnswerToOneReadingOfTheNode() {
        var node = new ReapplyingNode();
        var build = ProjectIndexBuildConfiguration.capture(node);

        // The enumeration asks first. Then the platform re-applies the file,
        // key by key, all the way through. Then the identity asks.
        Set<String> enumerated = build.excludedSchemas();
        node.reapplyTheFile();
        String identity = build.configuration().digest();

        assertEquals(Set.of("dummy_tmp"), enumerated);
        assertEquals(settled().digest(), identity);
        assertEquals(1, node.readings(),
                "one build read the preference node more than once");
    }

    /**
     * The same rule stated without naming a value: whatever the enumeration
     * left out is what the identity was stamped for, however far the node
     * travelled in between.
     */
    @Test
    void whatTheEnumerationLeavesOutIsWhatTheIdentityIsStampedFor() {
        var node = new ReapplyingNode();
        var build = ProjectIndexBuildConfiguration.capture(node);

        Set<String> enumerated = build.excludedSchemas();
        node.removeExcludedSchemas();
        ProjectIndexConfiguration stamped = build.configuration();
        node.removeOverrideRoot();
        Set<String> revalidated = build.excludedSchemas();

        assertEquals(enumerated, revalidated,
                "two passes of one build enumerated different projects");
        assertEquals(ProjectIndexSchemaExclusions.parse(
                stamped.excludedSchemas()), enumerated,
                "the files left out and the identity stamped disagree");
        assertSame(stamped, build.configuration(),
                "a build must not restate its configuration");
    }

    /**
     * A build captured inside the window is bound to what it read, and says so
     * plainly. It is not the snapshot's job to be right about the settled
     * configuration - it cannot be - only to be one configuration. Noticing
     * that it is the wrong one belongs to
     * {@link ProjectIndexPublicationGuard}.
     */
    @Test
    void aBuildCapturedInsideTheWindowIsBoundToWhatItRead() {
        var node = new ReapplyingNode();
        node.removeExcludedSchemas();

        var build = ProjectIndexBuildConfiguration.capture(node);
        node.restoreTheFile();

        assertEquals(Set.of(), build.excludedSchemas(),
                "the build that enumerated 22 384 files");
        assertNotEquals(settled().digest(),
                build.configuration().digest(),
                "and it is honest about which project that was");
    }

    /**
     * The exclusion means one thing for every project type: the files of the
     * named schemas are left out of the enumeration, and the name is hashed
     * into the identity the result is stored under.
     */
    @Test
    void everyProjectTypeLeavesOutTheSchemasItNames() {
        // The asymmetry this used to pin down is gone: the exclusion was
        // hashed into every identity but applied to PostgreSQL alone, because
        // only PostgreSQL had an index. Core drops the named schemas before
        // parsing whatever the dialect, so now the setting means the same
        // thing everywhere.
        for (DatabaseType databaseType : DatabaseType.values()) {
            var captured = ProjectIndexBuildConfiguration.capture(
                    () -> configuration(databaseType, "dummy_tmp"));

            assertEquals(Set.of("dummy_tmp"), captured.excludedSchemas(),
                    databaseType + " leaves out the schema it names");
            assertEquals("dummy_tmp",
                    captured.configuration().excludedSchemas(),
                    databaseType + " hashes it into its identity as before");
        }
    }

    /**
     * An exclusion that is not a schema name fails at the capture, which is
     * where stamping an identity from it would have failed anyway - the digest
     * canonicalises the very same names. The build never starts on a value
     * neither of its halves could have used.
     */
    @Test
    void anExclusionThatIsNotASchemaNameStopsTheBuildAtTheCapture() {
        Supplier<ProjectIndexConfiguration> dotted =
                () -> configuration(DatabaseType.PG, "public.dummy_tmp");

        assertThrows(IllegalArgumentException.class,
                () -> ProjectIndexBuildConfiguration.capture(dotted));
        assertThrows(IllegalArgumentException.class,
                () -> dotted.get().digest(),
                "the identity would have refused the same value");
    }

    @Test
    void aCapturedConfigurationHandsOutNothingItsCallerCanChange() {
        var build = ProjectIndexBuildConfiguration.capture(
                () -> configuration(DatabaseType.PG, "dummy_tmp\nreporting"));
        Set<String> excluded = build.excludedSchemas();

        assertThrows(UnsupportedOperationException.class,
                () -> excluded.add("public"));
        assertTrue(build.toString().contains("dummy_tmp"),
                "a build has to be able to say what it is indexing by");
    }

    /**
     * The captured configuration is laid over settings that read the very same
     * preferences for themselves, and the whole claim that a standing-still
     * workbench notices nothing rests on the two agreeing. They are two
     * separately written resolutions of one rule - {@code OverridablePrefs}
     * asks the project node with the global value as its default, while
     * {@link ProjectIndexConfiguration#resolve} takes the project field when
     * it is present - so the agreement is worth stating and worth failing on.
     *
     * <p>{@code OverridablePrefs} cannot be built without a workbench; what is
     * modelled below is its rule, not its code. Over every combination of a
     * present and an absent override, the two answers have to be the same
     * value.</p>
     */
    @Test
    void theCapturedValueIsWhatSettingsWouldHaveReadForThemselves() {
        // receiveOnly is the one field with a second reader outside this
        // record: ProjectReceiveOnlyMode.isEnabled asks OverridablePrefs for
        // the very same key, and the two answers decide whether an index is
        // maintained and whether it is retired. Its own dimension below, so a
        // resolution that read a neighbouring field instead cannot pass.
        var global = new GlobalSettings(true, true, false, true, "reporting");
        for (Boolean privileges : new Boolean[] {null, false, true}) {
            for (Boolean bodies : new Boolean[] {null, false, true}) {
                for (Boolean added : new Boolean[] {null, false, true}) {
                    for (Boolean receive : new Boolean[] {null, false, true}) {
                        for (String excluded : new String[] {null, "", "dummy_tmp"}) {
                            for (boolean root : new boolean[] {false, true}) {
                                assertResolvedLikeOverridablePrefs(global,
                                        new ProjectOverrides(privileges, bodies,
                                                added, receive, excluded),
                                        root);
                            }
                        }
                    }
                }
            }
        }
    }

    private static void assertResolvedLikeOverridablePrefs(
            GlobalSettings global, ProjectOverrides overrides, boolean root) {
        var captured = ProjectIndexConfiguration.resolve(
                DatabaseType.PG, root, global, overrides);

        assertEquals(overridablePrefs(global, overrides, root,
                GlobalSettings::ignorePrivileges,
                ProjectOverrides::ignorePrivileges),
                captured.ignorePrivileges());
        assertEquals(overridablePrefs(global, overrides, root,
                GlobalSettings::bodyDependencies,
                ProjectOverrides::bodyDependencies),
                captured.bodyDependencies());
        assertEquals(overridablePrefs(global, overrides, root,
                GlobalSettings::incrementalAddedFiles,
                ProjectOverrides::incrementalAddedFiles),
                captured.incrementalAddedFiles());
        assertEquals(overridablePrefs(global, overrides, root,
                GlobalSettings::receiveOnly,
                ProjectOverrides::receiveOnly),
                captured.receiveOnly());
        assertEquals(overridablePrefs(global, overrides, root,
                GlobalSettings::excludedSchemas,
                ProjectOverrides::excludedSchemas),
                captured.excludedSchemas());
    }

    /**
     * The rule {@code OverridablePrefs} applies: a project value is read only
     * while the override root is on, and an absent one falls back to the
     * global store, which is what {@code node.get(key, globalValue)} means.
     */
    private static <T> T overridablePrefs(GlobalSettings global,
            ProjectOverrides overrides, boolean overrideRoot,
            java.util.function.Function<GlobalSettings, T> globalValue,
            java.util.function.Function<ProjectOverrides, T> projectValue) {
        T project = projectValue.apply(overrides);
        return overrideRoot && project != null
                ? project : globalValue.apply(global);
    }

    private static ProjectIndexConfiguration settled() {
        return new ReapplyingNode().get();
    }

    private static ProjectIndexConfiguration configuration(
            DatabaseType databaseType, String excludedSchemas) {
        return ProjectIndexConfiguration.resolve(databaseType, true, WORKSPACE,
                new ProjectOverrides(true, false, true, false, excludedSchemas));
    }

    /**
     * One preference node, holding the shape of a committed OmniX project
     * file, and behaving the way the platform makes one behave while it
     * applies that file: every key is removed before it is put back, and the
     * node answers for whoever reads it while the key is gone.
     */
    private static final class ReapplyingNode
            implements Supplier<ProjectIndexConfiguration> {

        private static final String OVERRIDE_ROOT = "root";
        private static final String EXCLUDED_SCHEMAS = "excluded";
        private static final String NO_PRIVILEGES = "privileges";
        private static final String ADDED_FILES = "added";

        private final Map<String, Object> stored = new LinkedHashMap<>();
        private int readings;

        private ReapplyingNode() {
            restoreTheFile();
        }

        @Override
        public ProjectIndexConfiguration get() {
            readings++;
            return ProjectIndexConfiguration.resolve(DatabaseType.PG,
                    (boolean) stored.getOrDefault(OVERRIDE_ROOT, false),
                    WORKSPACE,
                    new ProjectOverrides(
                            (Boolean) stored.get(NO_PRIVILEGES), null,
                            (Boolean) stored.get(ADDED_FILES), null,
                            (String) stored.get(EXCLUDED_SCHEMAS)));
        }

        private int readings() {
            return readings;
        }

        private void restoreTheFile() {
            stored.put(OVERRIDE_ROOT, true);
            stored.put(NO_PRIVILEGES, true);
            stored.put(ADDED_FILES, true);
            stored.put(EXCLUDED_SCHEMAS, "dummy_tmp");
        }

        /** Removes and re-adds every key, the way the platform does. */
        private void reapplyTheFile() {
            for (String key : Set.copyOf(stored.keySet())) {
                Object value = stored.remove(key);
                stored.put(key, value);
            }
        }

        private void removeExcludedSchemas() {
            stored.remove(EXCLUDED_SCHEMAS);
        }

        private void removeOverrideRoot() {
            stored.remove(OVERRIDE_ROOT);
        }
    }
}
