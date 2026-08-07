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

/**
 * What resolving strings out of the packed dictionary has cost an index since
 * it was opened.
 *
 * <p>Every lookup names its subject in strings and the index stores string
 * identifiers, so a lookup first has to turn its schema and object name into
 * identifiers. That is a binary search over the dictionary, and each of its
 * steps asks for one entry. The lookup pays this before it reads a single
 * definition, and pays it in full even when the name turns out not to be in
 * the index at all - which is the case a lookup that returns nothing is made
 * of.
 *
 * <p>The same dictionary also serves the definitions a lookup did find, whose
 * fields are string identifiers too. Both are counted here: this is the price
 * of strings across the whole of an index's work, not of one phase of it.
 *
 * <p>The two counts answer different questions and move independently. An
 * entry is reached through its whole decoded block, and the block is kept, so
 * asking for an entry twice costs two probes and no records at all. Probes
 * therefore measure how much the index was asked, and records measure what
 * answering cost.
 *
 * @param probes  dictionary entries asked for, whether or not decoding was
 *                needed to answer
 * @param records dictionary records decoded, summed - a block is decoded
 *                whole and once, so a probe into a block already resident
 *                adds none
 * @param nanos   time spent inside those lookups
 */
public record ProjectIndexStringProbes(
        long probes, long records, long nanos) {

    /** An index that has decoded nothing, and the reading a non-packed one gives. */
    public static final ProjectIndexStringProbes NONE =
            new ProjectIndexStringProbes(0L, 0L, 0L);

    public ProjectIndexStringProbes {
        if (probes < 0 || records < 0 || nanos < 0) {
            throw new IllegalArgumentException(
                    "Project index string probe counts must not be negative"); //$NON-NLS-1$
        }
    }

    /**
     * What happened between an earlier reading and this one.
     *
     * <p>Two readings of one index can only be subtracted this way round: the
     * counters behind them are add-only, so a later reading is never the
     * smaller one however the two interleave with concurrent work. The check
     * the constructor makes is therefore not a guard against that but against
     * subtracting readings that came from two different indexes.
     *
     * @param earlier reading taken before the work being measured
     * @return the difference, per counter
     * @throws IllegalArgumentException if the earlier reading is not earlier
     */
    public ProjectIndexStringProbes since(ProjectIndexStringProbes earlier) {
        return new ProjectIndexStringProbes(probes - earlier.probes,
                records - earlier.records, nanos - earlier.nanos);
    }
}
