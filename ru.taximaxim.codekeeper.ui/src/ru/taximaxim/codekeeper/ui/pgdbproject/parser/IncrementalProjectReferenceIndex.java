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

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.pgcodekeeper.core.database.base.schema.meta.MetaStatement;

import ru.taximaxim.codekeeper.ui.projectindex.IndexPathRef;
import ru.taximaxim.codekeeper.ui.projectindex.PackedDefinition;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectFileStamp;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexDefinitionSubject;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexIdentity;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexStringProbes;

interface IncrementalProjectReferenceIndex extends ProjectReferenceIndex {

    AnalysisLease acquireAnalysisLease();

    interface AnalysisLease extends AutoCloseable {

        ProjectIndexIdentity identity();

        long generation();

        List<ProjectFileStamp> fileStamps();

        Optional<IncrementalFileMetadata> file(IndexPathRef path);

        List<MetaStatement> definitions(
                ProjectIndexDefinitionSubject subject,
                IndexPathRef excludedPath);

        /**
         * How many packed definition blocks the index behind this lease has
         * walked while decoding, counted since the index was opened rather
         * than since this lease began. A caller that wants what its own work
         * cost has to subtract a reading of its own.
         *
         * <p>A block is walked whole, so this counts how much of the index the
         * lookups had to touch - a quantity the number of statements they
         * returned cannot express.
         *
         * @return blocks walked, {@code 0} for an index that packs none
         */
        default long definitionBlockTraversals() {
            return 0L;
        }

        /**
         * What the index behind this lease has spent turning names into
         * dictionary identifiers and back, counted since the index was opened
         * rather than since this lease began - so a caller that wants its own
         * share has to subtract a reading of its own, exactly as with
         * {@link #definitionBlockTraversals()}.
         *
         * <p>This is a cost no per-lookup counter sees. A lookup spells its
         * subject in characters and the index is keyed by identifiers, so
         * every lookup searches the dictionary before it reads a definition,
         * and a subject that is not in the dictionary ends the lookup right
         * there - having paid in full for an answer of nothing.
         *
         * @return probe totals, {@link ProjectIndexStringProbes#NONE} for an
         *         index that holds its strings in memory
         */
        default ProjectIndexStringProbes stringProbes() {
            return ProjectIndexStringProbes.NONE;
        }

        /**
         * Whether any file this index holds may hold a reference that never
         * bound. While that is true of even one file, a definition arriving in
         * a new file could bind it, and nothing here can say which file that
         * would be - so an increment that introduces a file must not proceed.
         *
         * <p>No index written by this plug-in can answer yes: a build whose
         * analysis reported anything never reaches the packer, and an
         * incremental replacement whose analysis reported anything is
         * discarded, so a flagged contribution is never published. The
         * question is asked anyway because that invariant lives elsewhere -
         * in the coordinator and in the batch collector - and this is the only
         * place that would notice if it were ever relaxed.
         */
        boolean anyFileMayHoldUnresolvedReferences();

        @Override
        void close();
    }
}

record IncrementalFileMetadata(
        ProjectFileStamp stamp,
        List<PackedDefinition> definitions) {

    IncrementalFileMetadata {
        Objects.requireNonNull(stamp, "stamp"); //$NON-NLS-1$
        definitions = List.copyOf(definitions);
        if (definitions.stream().anyMatch(definition ->
                !stamp.path().equals(definition.object().path()))) {
            throw new IllegalArgumentException(
                    "Incremental file definitions must use the stamped path"); //$NON-NLS-1$
        }
    }
}
