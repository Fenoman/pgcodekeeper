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
import java.util.function.BooleanSupplier;

import ru.taximaxim.codekeeper.ui.DatabaseType;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetry.BypassReason;

/**
 * The one place that decides whether a project has a background index at all.
 *
 * <p>The index was PostgreSQL-only for as long as a path that read a file back
 * into it handed the bytes to the PostgreSQL parser by name. None does now -
 * the batch takes its loader from the type the index identity carries - and the
 * whole index package names a dialect in exactly one place, this one. So the
 * index is built, repaired, restored and read back for every project type, and
 * a measurement says it produces the same model the full load does: see
 * {@code ProjectIndexOtherDialectsTest}.</p>
 *
 * <p>Reusing a whole analyzed model between comparisons is a different
 * question, and its answer is still PostgreSQL - the loader that replays a
 * stored analysis exists for that dialect alone. That gate asks
 * {@link #supportsReusableComparisonModel} rather than this one, so a project
 * of another type keeps its index and loses only the reuse.</p>
 *
 * <p>A caller learns that the index is unavailable from {@link #refusal} and
 * names the reason in the line it publishes. Nothing stops it a second time
 * further in: the check that used to halt bytes already on their way to a
 * parser was there to keep a project of another type out of the PostgreSQL
 * one, and it went with the restriction it enforced.</p>
 */
public final class ProjectIndexSupportPolicy {

    private ProjectIndexSupportPolicy() {
    }

    /**
     * Whether a whole analyzed project model may be carried from one comparison
     * to the next for this database.
     *
     * <p>This is not the index. It is the second, larger cache built on top of
     * it, and it needs a loader that installs a stored analysis result into a
     * freshly parsed model. Only PostgreSQL has one, and Core refuses to
     * capture or replay such a model for anything else, so asking here keeps
     * that refusal from ever being reached.</p>
     *
     * @param databaseType type of the project, never null
     * @return whether the comparison may reuse a stored analyzed model
     */
    public static boolean supportsReusableComparisonModel(
            DatabaseType databaseType) {
        return Objects.requireNonNull(databaseType,
                "databaseType") == DatabaseType.PG; //$NON-NLS-1$
    }

    /**
     * Why this project has no background index, in the token the diagnostic
     * line already speaks.
     *
     * <p>Two questions decide this, and they are asked in that order for a
     * reason. A project that only receives changes from a database has no use
     * for an index of its own - see {@code ProjectReceiveOnlyMode} - and that
     * question is answered from a preference already held in memory. Only
     * once it comes back false is the layout even asked, and answering it
     * reads a file. A caller that already knows this project needs no index
     * must not pay for that read anyway: the same reasoning that made the
     * layout arrive as a supplier in the first place, applied one question
     * earlier.</p>
     *
     * @param receiveOnlyMode whether the project only receives changes from a
     *                        database, so nothing ever reads the index this
     *                        would otherwise build
     * @param layoutSupported whether the project layout is one the index can
     *                        be built from; not invoked when
     *                        {@code receiveOnlyMode} already answers the
     *                        question
     * @return the reason the index is unavailable, or null when it is
     */
    public static BypassReason refusal(boolean receiveOnlyMode,
            BooleanSupplier layoutSupported) {
        Objects.requireNonNull(layoutSupported, "layoutSupported"); //$NON-NLS-1$
        if (receiveOnlyMode) {
            return BypassReason.DISABLED_BY_PREFERENCE;
        }
        return layoutSupported.getAsBoolean()
                ? null : BypassReason.UNSUPPORTED_PROJECT_LAYOUT;
    }
}
