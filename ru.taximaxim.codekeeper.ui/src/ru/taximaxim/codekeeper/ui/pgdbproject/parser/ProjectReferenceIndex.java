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
import java.util.Set;
import java.util.stream.Stream;

import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.base.schema.meta.MetaStatement;

interface ProjectReferenceIndex extends AutoCloseable {

    /**
     * Shortest text {@link #completionCandidates(String)} answers.
     * <p>
     * This is the width of the trigram the packed index is keyed by, and it is
     * a limit of the format rather than a policy: a name shorter than this is
     * written under a key of its own length, so a shorter query would answer
     * with the objects whose whole name is that short and with nothing else -
     * a wrong answer that looks like an answer. The only way to answer it
     * properly is to decode every file, which on a project the size of the one
     * this was found on costs some forty seconds.
     * <p>
     * Every implementation holds to this, including the ones that keep their
     * definitions in memory and could answer a shorter text cheaply. What is
     * offered must not depend on whether the project has an index.
     */
    int MIN_COMPLETION_PREFIX_LENGTH = 3;

    Set<ObjectLocation> referencesForPath(String path);

    List<MetaStatement> definitionsForPath(String path);

    List<MetaStatement> definitionsMatching(ObjectLocation object);

    List<ObjectLocation> referencesMatching(ObjectLocation object);

    /**
     * Definitions whose name holds the given text, ignoring case.
     *
     * @param text text to look for
     * @return matching definitions, empty when {@code text} is shorter than
     *         {@link #MIN_COMPLETION_PREFIX_LENGTH}
     */
    List<MetaStatement> completionCandidates(String text);

    /**
     * Every definition the index holds.
     * <p>
     * Whole-index queries decode the whole index: on a live project of some
     * twenty thousand files that is tens of seconds and tens of megabytes, and
     * the block cache does not hold the working set, so a second call costs the
     * same as the first. This belongs to whole-project work - a rebuild, a
     * parity check - and must not be reached from anything a keystroke starts.
     * Interactive callers ask {@link #definitionsMatching(ObjectLocation)} or
     * {@link #completionCandidates(String)} instead.
     */
    Stream<MetaStatement> allDefinitions();

    /**
     * Every reference the index holds. Costs what
     * {@link #allDefinitions()} costs, and is subject to the same rule.
     */
    Stream<ObjectLocation> allReferences();

    ProjectReferencesStorage mutableCopy();

    @Override
    default void close() {
        // In-memory indexes own no external resources.
    }
}
