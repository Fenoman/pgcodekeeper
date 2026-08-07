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
package ru.taximaxim.codekeeper.ui.differ;

import java.util.Comparator;

import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IStatement;
import org.pgcodekeeper.core.model.difftree.TreeElement;
import org.pgcodekeeper.core.utils.Utils;

/**
 * Display order of child objects rendered under a container in the object comparison pane.
 * <p>
 * Loaded models keep children in load order: the remote side follows the database catalog,
 * the project side follows the order of statements in the .sql file. Rendering both sides in
 * their own load order shows permutations as textual differences even when the schemas match.
 * Both sides are therefore rendered through this order, so only real differences remain visible.
 * <p>
 * The order is display-only and never mutates the compared models. Migration script generation
 * is unaffected: it builds its own object list in the core.
 * <p>
 * The key is object type, then object name.
 * <ul>
 * <li>Types are ranked by {@link DbObjType} declaration order through {@link Enum#compareTo},
 * which compares ordinals. Identity-based {@link Enum#hashCode()} is deliberately avoided
 * because it is not reproducible between runs.</li>
 * <li>Names are compared by Unicode code point, so the result does not depend on the default
 * locale, on the collation data of the running JDK, or on case-mapping rules.</li>
 * </ul>
 */
final class DiffPaneChildOrder {

    private static final Comparator<DbObjType> TYPE_ORDER =
            Comparator.nullsFirst(Comparator.<DbObjType>naturalOrder());

    private static final Comparator<String> NAME_ORDER =
            Comparator.nullsFirst(DiffPaneChildOrder::compareByCodePoints);

    /** Order of child nodes of a container in the difference tree. */
    static final Comparator<TreeElement> TREE_ELEMENT_ORDER = Comparator
            .comparing(TreeElement::getType, TYPE_ORDER)
            .thenComparing(TreeElement::getName, NAME_ORDER);

    /** Order of child statements of a container in a loaded database model. */
    static final Comparator<IStatement> STATEMENT_ORDER = Comparator
            .comparing(IStatement::getStatementType, TYPE_ORDER)
            .thenComparing(IStatement::getName, NAME_ORDER);

    /**
     * Compares two names by Unicode code point.
     * <p>
     * The one implementation of this order lives in the core, where the display order of the
     * columns of a table needs the same reproducible answer.
     *
     * @param left  the first name, not null
     * @param right the second name, not null
     * @return a negative value, zero or a positive value as the first name precedes, equals
     *         or follows the second one
     */
    static int compareByCodePoints(String left, String right) {
        return Utils.compareByCodePoints(left, right);
    }

    private DiffPaneChildOrder() {
    }
}
