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

import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The one configuration a project-index build works by.
 *
 * <p>A build used to obtain the value of a setting twice: once to enumerate
 * the files it indexes, and once to stamp the identity those files are stored
 * under. The two reads were reconciled by policy - both applied the same
 * overriding rules - but never by value. The platform re-applies a preferences
 * file key by key and reports each key while it is gone, so the node passes
 * through configurations nobody ever chose; a build that read it twice could
 * read one of them and then the other.</p>
 *
 * <p>This is the snapshot that removes the second read. It is taken once, when
 * a build starts, and every step of that build answers to it: the enumeration
 * of the files, the identity they are stored under and the guard the result is
 * published through. Those steps cannot disagree about a setting because there
 * is only one value for them to read - not because the window in which they
 * could disagree was made small.</p>
 */
public final class ProjectIndexBuildConfiguration {

    private final ProjectIndexConfiguration configuration;
    private final Set<String> excludedSchemas;

    private ProjectIndexBuildConfiguration(
            ProjectIndexConfiguration configuration) {
        this.configuration = Objects.requireNonNull(configuration,
                "configuration"); //$NON-NLS-1$
        this.excludedSchemas = ProjectIndexSchemaExclusions.parse(
                configuration.excludedSchemas());
    }

    /**
     * Reads the configuration of a build. Exactly once: the supplier is asked
     * here and never again, and the build is handed what it answered.
     *
     * @param node the effective index configuration, as the preference node
     *             stands at the moment the build starts
     * @return the configuration this build is bound to for its whole life
     * @throws IllegalArgumentException if the excluded schemas cannot be read
     *                                  as schema names, which is also what
     *                                  stamping an identity from them would
     *                                  raise
     */
    public static ProjectIndexBuildConfiguration capture(
            Supplier<ProjectIndexConfiguration> node) {
        return new ProjectIndexBuildConfiguration(
                Objects.requireNonNull(node, "node").get()); //$NON-NLS-1$
    }

    /**
     * The configuration this build stamps its identity from and publishes
     * under.
     *
     * @return the captured configuration, never null
     */
    public ProjectIndexConfiguration configuration() {
        return configuration;
    }

    /**
     * Schemas this build leaves out of the files it enumerates.
     *
     * <p>Parsed once, from the captured value, for a project of any type, and
     * meaning the same thing for each: Core drops the files of the named
     * schemas before parsing whatever the dialect, whether the layout keeps a
     * schema in a directory of its own or encodes it in a file name. So there
     * is no type to ask about here and no type this answers empty for - see
     * {@code ProjectIndexBuildConfigurationTest}.</p>
     *
     * <p>The identity in {@link ProjectIndexConfiguration#canonicalForm()}
     * hashes these exclusions for every database type, which is what makes the
     * identity of an index of any type answer to them. That is explained
     * there, and is not settled here.</p>
     *
     * @return the excluded schema names, empty when none are configured
     */
    public Set<String> excludedSchemas() {
        return excludedSchemas;
    }

    @Override
    public String toString() {
        return "ProjectIndexBuildConfiguration" + configuration; //$NON-NLS-1$
    }
}
