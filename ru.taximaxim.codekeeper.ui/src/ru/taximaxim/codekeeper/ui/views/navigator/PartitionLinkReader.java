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
package ru.taximaxim.codekeeper.ui.views.navigator;

import java.util.Collection;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation.LocationType;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;

/**
 * Decides which table a file is a section of, out of two sources that have to
 * agree.
 *
 * <p>Neither source can answer alone. The parser knows every table a file
 * refers to and it knows them properly - a {@code PARTITION OF} inside a
 * comment or a string never becomes a reference - but it files that reference
 * next to the foreign keys, the views and the functions, with nothing to say
 * which of them is the parent. The text of the file says which one is the
 * parent, and says it about anything that looks like the words, comment or
 * not. So the parent is the reference the parser really produced <em>at the
 * place</em> where the text spells {@code PARTITION OF}: a commented-out
 * clause has no reference to land on, and a foreign key to the same table
 * sits at a different offset.</p>
 *
 * <p>This is deliberately not a parse. It is a one-bit discriminator over an
 * answer the parser already gave, which is why it can afford to look at the
 * head of the file only, and why failing to find anything costs nothing but
 * the ordering.</p>
 */
public final class PartitionLinkReader {

    /**
     * How much of a file is read looking for the clause. {@code CREATE TABLE
     * ... PARTITION OF} is the opening statement of a section file, so the
     * clause is at the top or it is not a section file. A file whose header
     * comment is longer than this simply keeps its plain ordering.
     */
    static final int HEAD_CHARS = 4096;

    /**
     * The clause, with the parent name captured. Qualified or bare, quoted or
     * not - the name is only used to pick among references the parser already
     * produced, so it does not have to be canonicalised here.
     */
    private static final Pattern PARTITION_OF = Pattern.compile(
            "PARTITION\\s+OF\\s+((?:\"[^\"]+\"|[\\w$]+)(?:\\s*\\.\\s*(?:\"[^\"]+\"|[\\w$]+))?)", //$NON-NLS-1$
            Pattern.CASE_INSENSITIVE);

    /**
     * A table a file is a section of, and which of the two sources said so.
     *
     * <p>The flag is the whole point of the type. Both kinds of link order a
     * folder and only a confirmed one may hide a file, so the two have to be
     * distinguishable everywhere the answer travels - a plain string would let
     * the difference be forgotten at any hand-off between here and the tree,
     * and the way it is forgotten is a file vanishing from its folder.</p>
     *
     * @param parent    the qualified or bare name of the table
     * @param confirmed whether the parser produced a reference where the text
     *                  spells the clause
     */
    public record Link(String parent, boolean confirmed) {
    }

    private PartitionLinkReader() {
    }

    /**
     * The qualified name of the parent table, or {@code null} when the file is
     * not a section of anything the parser confirmed.
     *
     * @param head      the first {@link #HEAD_CHARS} characters of the file
     * @param locations everything the parser recorded for that file
     */
    public static String parentOf(String head, Collection<ObjectLocation> locations) {
        if (head == null || locations == null || locations.isEmpty()) {
            return null;
        }
        Matcher matcher = PARTITION_OF.matcher(head);
        if (!matcher.find()) {
            return null;
        }
        String spelled = normalize(matcher.group(1));
        // the whole span of the clause, not the name token alone: the parser
        // may point at the schema or at the table, and a byte order mark or a
        // line ending the two sides counted differently would shift the offset
        // by one. A span of twenty-odd characters absorbs that; a folder whose
        // encoding shifts it further simply keeps its plain ordering
        int from = matcher.start();
        int to = matcher.end();

        for (ObjectLocation location : locations) {
            if (location.getLocationType() != LocationType.REFERENCE) {
                continue;
            }
            ObjectReference reference = location.getObjectReference();
            if (reference == null || !isRelation(reference.type())) {
                continue;
            }
            int offset = location.getOffset();
            if (offset < from || offset > to) {
                continue;
            }
            String qualified = qualifiedName(reference);
            if (qualified != null && endsWithName(spelled, reference)) {
                return qualified;
            }
        }
        return null;
    }

    /**
     * Whether the file even claims to be a section. Cheap enough to run over a
     * whole folder before anything asks the parser, and it is what keeps a
     * folder without sections from costing a single index lookup.
     */
    public static boolean mentionsPartitionOf(String head) {
        return head != null && PARTITION_OF.matcher(head).find();
    }

    /**
     * The table the text of the file claims it is a section of, with nothing
     * confirming it, or {@code null} when the text does not spell the clause.
     *
     * <p>This is the answer {@link #parentOf} refuses to give, and the reason
     * it refuses is still true: the text believes a commented-out clause. It
     * exists because a project whose index is switched off, bypassed or missing
     * the schema hands {@link #parentOf} an empty set of locations, and an
     * empty set makes every file of the folder look like a plain table. On such
     * a project the confirmed answer is not merely unavailable for one file, it
     * is unavailable for all of them, so a caller that has only this one has no
     * better source to prefer.</p>
     *
     * <p>What may be built on it is fixed by what it costs to be wrong.
     * Ordering may: a text that lied puts a file on the wrong row and nothing
     * else, and the row is visible. Hiding may not: a text that lied takes the
     * file out of the folder it lives in, which is the failure that ruled out
     * rearranging the files in the first place. Hence a caller has to be able
     * to tell this answer from {@link #parentOf}'s, and the two are separate
     * methods rather than one method with a fallback for exactly that
     * reason.</p>
     */
    public static String spelledParentOf(String head) {
        if (head == null) {
            return null;
        }
        Matcher matcher = PARTITION_OF.matcher(head);
        return matcher.find() ? normalize(matcher.group(1)) : null;
    }

    /**
     * The link this file has to a table, from the better of the two sources
     * that can say, or {@code null} when the text does not spell the clause at
     * all.
     *
     * <p>The confirmed answer is asked for first and is never overridden - a
     * project whose index works keeps exactly the behaviour it had, and the
     * flag on the result is what stops the weaker answer from being spent on
     * anything the stronger one was required for.</p>
     *
     * <p>The fallback is taken even when the parser did answer for this file
     * and declined this clause. That case is distinguishable here - an index
     * that answered hands back a set that is not empty - and it is deliberately
     * not distinguished, because a clause the parser declined is either not a
     * clause, which costs one row, or a clause it failed to link, and refusing
     * the text there would leave the second one standing in the wall this
     * exists to clear. The price is a commented-out clause sinking its file to
     * the bottom of the folder, and it is a price in row numbers only. Whether
     * a folder is in that state at all is a question about the folder and not
     * about one file of it, and it is answered by the {@code answered} and
     * {@code confirmed} counts of the telemetry line the scan publishes.</p>
     *
     * @param head      the first {@link #HEAD_CHARS} characters of the file
     * @param locations everything the parser recorded for that file
     */
    public static Link linkOf(String head, Collection<ObjectLocation> locations) {
        String confirmed = parentOf(head, locations);
        if (confirmed != null) {
            return new Link(confirmed, true);
        }
        String spelled = spelledParentOf(head);
        return spelled == null ? null : new Link(spelled, false);
    }

    /**
     * Whether the parser says this file defines that table.
     *
     * <p>The other half of the discriminator, and the one that decides whether
     * a section may be hidden. Finding a file by the name a table would be
     * exported under is a guess; a file may be named {@code orders.sql} and
     * define {@code tmp.orders} while the section is a section of
     * {@code other.orders}. Only a definition the parser produced settles it,
     * so only a definition is accepted.</p>
     *
     * @param locations     everything the parser recorded for the candidate
     * @param qualifiedName the table the candidate is expected to define
     */
    public static boolean defines(Collection<ObjectLocation> locations,
            String qualifiedName) {
        String wanted = groupingKey(qualifiedName);
        if (wanted == null || locations == null) {
            return false;
        }
        boolean bare = wanted.indexOf('.') < 0;
        for (ObjectLocation location : locations) {
            if (location.getLocationType() != LocationType.DEFINITION) {
                continue;
            }
            ObjectReference reference = location.getObjectReference();
            if (reference == null || !isRelation(reference.type())) {
                continue;
            }
            String qualified = qualifiedName(reference);
            if (qualified == null) {
                continue;
            }
            String defined = groupingKey(qualified);
            if (bare) {
                // a parent named without a schema was resolved from the search
                // path, so the schema of the definition is not contradicted by
                // anything and only the table may be compared
                int dot = defined.lastIndexOf('.');
                defined = dot < 0 ? defined : defined.substring(dot + 1);
            }
            if (wanted.equals(defined)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRelation(DbObjType type) {
        return type == DbObjType.TABLE;
    }

    private static String qualifiedName(ObjectReference reference) {
        String table = reference.table();
        if (table == null) {
            return null;
        }
        String schema = reference.schema();
        return schema == null ? table : schema + '.' + table;
    }

    /**
     * The spelled name has to be the one the reference is about. The clause
     * may be qualified where the reference splits schema from table, or bare
     * where the parser resolved the schema from the search path, so the table
     * part is what must agree; a spelled schema must agree too when it is
     * there.
     */
    private static boolean endsWithName(String spelled, ObjectReference reference) {
        int dot = spelled.lastIndexOf('.');
        String spelledTable = dot < 0 ? spelled : spelled.substring(dot + 1);
        if (!spelledTable.equalsIgnoreCase(reference.table())) {
            return false;
        }
        if (dot < 0) {
            return true;
        }
        String spelledSchema = spelled.substring(0, dot);
        return reference.schema() == null
                || spelledSchema.equalsIgnoreCase(reference.schema());
    }

    /**
     * Strips the quoting and the whitespace a qualified name may carry. Case
     * is left alone: the comparison against the reference is case-insensitive
     * anyway, and lowering here would break a quoted upper-case name.
     */
    private static String normalize(String name) {
        StringBuilder out = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c != '"' && !Character.isWhitespace(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * The key sections are grouped by. Two spellings of one table must not
     * make two families.
     */
    public static String groupingKey(String qualifiedName) {
        return qualifiedName == null ? null
                : qualifiedName.toLowerCase(Locale.ROOT);
    }
}
