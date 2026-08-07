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
import java.util.Optional;

import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetry.ConfigurationGuard;

/**
 * Answers whether a build may still publish what it built.
 *
 * <p>A build is bound to the configuration it started under. That is the right
 * thing for it to be bound to - the files it enumerated and the identity it
 * stamped both come from it - but it says nothing about whether that
 * configuration is still the one the workbench has settled on. An index built
 * under a configuration nobody is asking for any more is complete, internally
 * consistent and worthless: the next check of it will find the identity it
 * does not expect and pay for a rebuild.</p>
 *
 * <p>So the last thing a build does before publishing is ask this. There is no
 * notion of time in the question and none in the answer: it compares two
 * fingerprints and says whether they are the same one. Waiting for a
 * configuration to settle belongs to whoever reads it, not here.</p>
 */
public final class ProjectIndexPublicationGuard {

    /**
     * What a fingerprint that could not be read begins with.
     *
     * <p>Such a fingerprint carries a counter, so no two of them are equal and
     * none of them can ever equal a build. A caller that could not see the
     * configuration therefore never concludes that it did not move.</p>
     */
    public static final String UNREADABLE_PREFIX = "unreadable:"; //$NON-NLS-1$

    private ProjectIndexPublicationGuard() {
    }

    /**
     * Decides whether a build may publish.
     *
     * @param built   fingerprint of the configuration the build worked by
     * @param settled fingerprint of the configuration as it stands now, which
     *                is fail-closed: an unreadable one refuses
     * @return why the build must not publish, or empty when it may
     */
    public static Optional<ConfigurationGuard> refusal(String built,
            String settled) {
        Objects.requireNonNull(built, "built"); //$NON-NLS-1$
        Objects.requireNonNull(settled, "settled"); //$NON-NLS-1$
        if (settled.startsWith(UNREADABLE_PREFIX)) {
            return Optional.of(ConfigurationGuard.UNREADABLE);
        }
        return built.equals(settled)
                ? Optional.empty()
                : Optional.of(ConfigurationGuard.MOVED);
    }
}
