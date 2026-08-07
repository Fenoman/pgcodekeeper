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
package ru.taximaxim.codekeeper.ui.comparetools;

import java.util.List;
import java.util.Map;

import org.pgcodekeeper.core.model.difftree.ColumnMark;
import org.pgcodekeeper.core.model.difftree.IgnoredValues;
import org.pgcodekeeper.core.model.difftree.SqlMarkup;
import org.pgcodekeeper.core.model.difftree.SqlMarkup.Marked;

/**
 * What the comparison on display has to say about the object on display, as the
 * pane needs it: read once for both states of that object, so that neither side
 * of the screen can mark a line the other does not.
 * <p>
 * Two answers, and they come from opposite ends of the settings. The columns
 * come from the ignore list of the comparison, see {@code ColumnVisibility}; the
 * values come from the two settings that declare a difference uninteresting, see
 * {@link IgnoredValues}. Both end up as stretches of the rendering, and from
 * there on nothing tells them apart but the words a reader is given for them.
 *
 * @param marks  the marked columns of the object, in the order its states hold
 *               them; a column no rule names is not among them
 * @param kept   what keeps each of the columns that a rule names and something
 *               needs, see {@code ColumnVisibility.pinnedColumns}
 * @param values the values of this object the settings overlook
 */
public record PaneMarks(Map<String, ColumnMark> marks, Map<String, String> kept, IgnoredValues values) {

    /** Nothing to mark: this comparison says nothing about this object, or about any. */
    public static final PaneMarks NONE = new PaneMarks(Map.of(), Map.of(), IgnoredValues.NONE);

    /**
     * @return whether the comparison said nothing about this object, which is
     * the answer for very nearly every project there is
     */
    public boolean isEmpty() {
        return marks.isEmpty() && values.isEmpty();
    }

    /**
     * The stretches of one rendering these marks fall on.
     *
     * @param sql one state of the object, as a reader is shown it
     * @return the stretches to mark, ordered and disjoint, empty when there is
     * nothing to mark
     */
    public List<Marked> rangesIn(String sql) {
        return isEmpty() ? List.of() : SqlMarkup.rangesIn(sql, marks, values);
    }
}
