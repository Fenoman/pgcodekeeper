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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation.LocationType;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;

/**
 * Whether a file is a section of a table, and of which one.
 *
 * <p>The question has exactly one honest answer and two dishonest ones that
 * are easy to reach. Trusting the text alone believes a commented-out clause.
 * Trusting the parser alone cannot tell the parent from a foreign key, because
 * both arrive as a plain reference to a table with no mark on either - the
 * mark would have to go in {@code PackedLocation.action}, and anything with a
 * non-null action starts appearing in the file's outline
 * ({@code NavigatorOutlineContentProvider.getChildren} filters on exactly
 * that). So the answer is the reference the parser produced <em>where</em> the
 * text spells the clause, and each test below is one way of being wrong about
 * that.</p>
 *
 * <p>There is a second question underneath, and it is not the same one: what
 * the text <em>claims</em>, with nothing confirming it. On a project whose
 * index is switched off there is no confirmed answer to be had for any file, so
 * that claim is the only thing left to order a folder by - and it is enough to
 * order by, because a claim that turns out to be false puts a row in the wrong
 * place and does nothing else. It is not enough to hide a file by. Which of the
 * two answered is therefore part of the answer, and the tests below say so
 * about each case rather than about the pair.</p>
 */
class PartitionLinkReaderTest {

    private static final String FILE = "/p/SCHEMA/tmp/TABLE/orders_1.sql"; //$NON-NLS-1$

    /** The plain case: text and parser agree, at the same place. */
    @Test
    void aSectionIsTheTableTheClausePointsAt() {
        String head = "CREATE TABLE tmp.orders_1 PARTITION OF tmp.orders\n" //$NON-NLS-1$
                + "FOR VALUES WITH (modulus 100, remainder 1);"; //$NON-NLS-1$

        assertEquals("tmp.orders", //$NON-NLS-1$
                PartitionLinkReader.parentOf(head,
                        List.of(reference("tmp", "orders", //$NON-NLS-1$ //$NON-NLS-2$
                                head.indexOf("tmp.orders", 20)))), //$NON-NLS-1$
                "the clause the parser confirmed was not read");
    }

    /**
     * The reason the parser is asked at all. The words are there, in a
     * comment, and there is no reference under them because the parser never
     * saw a clause. A reader that only matched text would answer here.
     */
    @Test
    void aCommentedOutClauseIsNotASection() {
        String head = "-- was: PARTITION OF tmp.orders\n" //$NON-NLS-1$
                + "CREATE TABLE tmp.orders_1 (id int);"; //$NON-NLS-1$

        assertNull(PartitionLinkReader.parentOf(head, Set.of()),
                "a clause inside a comment was taken for a section");
    }

    /**
     * The reason the text is read at all. Here the parser really does hold a
     * reference to {@code tmp.orders} - a foreign key - and the words
     * {@code PARTITION OF} appear in a comment about something else. Name
     * agreement alone would join them; the offset is what keeps them apart.
     */
    @Test
    void aForeignKeyToTheSameTableIsNotAParent() {
        String head = "-- see PARTITION OF tmp.orders elsewhere\n" //$NON-NLS-1$
                + "CREATE TABLE tmp.orders_1 (id int REFERENCES tmp.orders);"; //$NON-NLS-1$
        int keyAt = head.indexOf("tmp.orders", head.indexOf("REFERENCES")); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull(PartitionLinkReader.parentOf(head,
                List.of(reference("tmp", "orders", keyAt))), //$NON-NLS-1$ //$NON-NLS-2$
                "a foreign key was taken for the parent table");
    }

    /**
     * A definition is not a reference. The section's own {@code CREATE TABLE}
     * sits inside the matched span when the clause is on the same line, and it
     * names a table, so nothing but the location type keeps the file from
     * being declared a section of itself.
     */
    @Test
    void theSectionsOwnDefinitionIsNotItsParent() {
        String head = "CREATE TABLE tmp.orders_1 PARTITION OF tmp.orders_1;"; //$NON-NLS-1$
        ObjectLocation definition = new ObjectLocation.Builder()
                .setFilePath(FILE)
                .setOffset(head.indexOf("tmp.orders_1", 20)) //$NON-NLS-1$
                .setAction("CREATE") //$NON-NLS-1$
                .setReference(new ObjectReference("tmp", "orders_1", //$NON-NLS-1$ //$NON-NLS-2$
                        DbObjType.TABLE))
                .setLocationType(LocationType.DEFINITION)
                .build();

        assertNull(PartitionLinkReader.parentOf(head, List.of(definition)),
                "a definition was taken for the parent reference");
    }

    /**
     * A parent in another schema is legal in PostgreSQL and the section has to
     * end up under that name, not under a bare one - two tables of the same
     * name in two schemas must not collapse into one family.
     */
    @Test
    void aParentInAnotherSchemaKeepsItsSchema() {
        String head = "CREATE TABLE tmp.orders_1 PARTITION OF other.orders;"; //$NON-NLS-1$

        assertEquals("other.orders", //$NON-NLS-1$
                PartitionLinkReader.parentOf(head,
                        List.of(reference("other", "orders", //$NON-NLS-1$ //$NON-NLS-2$
                                head.indexOf("other.orders")))), //$NON-NLS-1$
                "the parent lost the schema it lives in");
    }

    /** Quoting is spelling, not identity. */
    @Test
    void aQuotedParentIsTheSameTable() {
        String head = "CREATE TABLE tmp.o_1 PARTITION OF \"tmp\".\"Orders\";"; //$NON-NLS-1$

        assertEquals("tmp.Orders", //$NON-NLS-1$
                PartitionLinkReader.parentOf(head,
                        List.of(reference("tmp", "Orders", //$NON-NLS-1$ //$NON-NLS-2$
                                head.indexOf("\"tmp\"")))), //$NON-NLS-1$
                "a quoted parent name was not recognised");
    }

    /**
     * Two spellings of one table are one family. Without this the tree would
     * show a wall broken in two for no reason a person could see.
     */
    @Test
    void spellingsOfOneTableGroupTogether() {
        assertEquals(PartitionLinkReader.groupingKey("TMP.Orders"), //$NON-NLS-1$
                PartitionLinkReader.groupingKey("tmp.orders"), //$NON-NLS-1$
                "one table was split into two families by its spelling");
    }

    /**
     * The cheap first pass. It decides whether a folder costs any index
     * lookups at all, so it has to say yes on anything that might be a section
     * and no on a folder that holds none.
     */
    @Test
    void theCheapPassAdmitsWhatTheExpensiveOneJudges() {
        assertEquals(true, PartitionLinkReader.mentionsPartitionOf(
                "create table a partition of b;"), //$NON-NLS-1$
                "a lower-case clause was not even considered");
        assertEquals(false, PartitionLinkReader.mentionsPartitionOf(
                "CREATE TABLE tmp.orders (id int);"), //$NON-NLS-1$
                "a plain table was sent to the parser for nothing");
    }

    /**
     * The case the whole fallback exists for, at the shape it really has.
     *
     * <p>This head is copied from {@code SCHEMA/tmp/TABLE} of OmniX, and the
     * empty set is what the parser hands back for every one of its 2020
     * sections: that project is in receive-only mode, so no background index is
     * built, and the schema it would need is excluded from it besides. Nothing
     * is available to confirm anything, for any file of the folder - so a
     * caller that insisted on confirmation would order the folder by nothing at
     * all, which is what it did.</p>
     */
    @Test
    void theTextAnswersWhenTheIndexHasNothingToSay() {
        String head = "CREATE TABLE tmp.pe_rollback_charges_44 " //$NON-NLS-1$
                + "PARTITION OF tmp.pe_rollback_charges\n" //$NON-NLS-1$
                + "FOR VALUES WITH (modulus 100, remainder 44);"; //$NON-NLS-1$

        assertEquals(
                new PartitionLinkReader.Link("tmp.pe_rollback_charges", false), //$NON-NLS-1$
                PartitionLinkReader.linkOf(head, Set.of()),
                "a silent index left the file with no link at all");
    }

    /**
     * The confirmed answer is the answer, in content and not only in the flag.
     *
     * <p>The two sources really can differ: the text spells a bare name, the
     * parser resolved the schema it was written in. Taking the text when there
     * is a confirmed reading would split one table into two families - {@code
     * orders} and {@code tmp.orders} - on a project whose index works
     * perfectly, which is the behaviour that was there before the fallback and
     * has to survive it.</p>
     */
    @Test
    void aConfirmedLinkIsNotReplacedByWhatTheTextSpells() {
        String head = "CREATE TABLE tmp.orders_1 PARTITION OF orders;"; //$NON-NLS-1$

        assertEquals(new PartitionLinkReader.Link("tmp.orders", true), //$NON-NLS-1$
                PartitionLinkReader.linkOf(head,
                        List.of(reference("tmp", "orders", //$NON-NLS-1$ //$NON-NLS-2$
                                head.indexOf("orders", 20)))), //$NON-NLS-1$
                "the weaker answer was taken over the confirmed one");
    }

    /**
     * The price, written down rather than discovered. A commented-out clause
     * has nothing to confirm it and never will, so the text is all there is and
     * the file sinks to the bottom of its folder. That is a row number; the
     * same answer may not be spent on hiding the file, which is what the flag
     * on it is for.
     */
    @Test
    void aCommentedOutClauseIsAnUnconfirmedLink() {
        String head = "-- was: PARTITION OF tmp.orders\n" //$NON-NLS-1$
                + "CREATE TABLE tmp.orders_1 (id int);"; //$NON-NLS-1$

        assertEquals(new PartitionLinkReader.Link("tmp.orders", false), //$NON-NLS-1$
                PartitionLinkReader.linkOf(head, Set.of()),
                "the accepted price of the fallback changed");
    }

    /**
     * The fallback may not invent a section. A file that does not spell the
     * clause has no link from either source - and it is nearly every file of a
     * project, so answering here would rank the whole tree.
     */
    @Test
    void aFileThatSpellsNoClauseHasNoLink() {
        assertNull(PartitionLinkReader.linkOf(
                "CREATE TABLE tmp.orders (id int);", Set.of()), //$NON-NLS-1$
                "a plain table was given a link to something");
    }

    /** Quoting is spelling, with or without a parser to agree. */
    @Test
    void aQuotedParentIsTheSameTableWithoutTheParser() {
        assertEquals("tmp.Orders", //$NON-NLS-1$
                PartitionLinkReader.spelledParentOf(
                        "CREATE TABLE tmp.o_1 PARTITION OF \"tmp\" . \"Orders\";"), //$NON-NLS-1$
                "a quoted parent name survived the parser but not the text");
    }

    /**
     * The other half, the one that decides whether a file may be hidden. A
     * section is only taken out of its folder when some file in that folder
     * defines its table, and this is how that file is recognised.
     */
    @Test
    void theFileThatDefinesTheTableIsTheParent() {
        assertEquals(true,
                PartitionLinkReader.defines(
                        List.of(definition("tmp", "orders")), //$NON-NLS-1$ //$NON-NLS-2$
                        "tmp.orders"), //$NON-NLS-1$
                "the file defining the parent table was not recognised");
    }

    /**
     * The trap the name alone walks into, and the reason this asks the parser
     * at all. A section of {@code other.orders} sits in the folder of schema
     * {@code tmp}, because a section lives in its own schema. That folder may
     * well hold an {@code orders.sql} - and it is a different table. Matching
     * by file name would nest the section under it; the definition says no.
     */
    @Test
    void aTableOfTheSameNameInAnotherSchemaIsNotTheParent() {
        assertEquals(false,
                PartitionLinkReader.defines(
                        List.of(definition("tmp", "orders")), //$NON-NLS-1$ //$NON-NLS-2$
                        "other.orders"), //$NON-NLS-1$
                "a table of the same bare name in another schema was accepted");
    }

    /**
     * Mentioning a table is not defining it. Every section file holds a
     * reference to its parent, so a reader that accepted references would
     * declare each section the parent of its own family.
     */
    @Test
    void aReferenceToTheTableIsNotItsDefinition() {
        assertEquals(false,
                PartitionLinkReader.defines(
                        List.of(reference("tmp", "orders", 0)), //$NON-NLS-1$ //$NON-NLS-2$
                        "tmp.orders"), //$NON-NLS-1$
                "a reference to the table was taken for its definition");
    }

    private static ObjectLocation definition(String schema, String table) {
        return new ObjectLocation.Builder()
                .setFilePath(FILE)
                .setOffset(0)
                .setAction("CREATE") //$NON-NLS-1$
                .setReference(new ObjectReference(schema, table, DbObjType.TABLE))
                .setLocationType(LocationType.DEFINITION)
                .build();
    }

    private static ObjectLocation reference(String schema, String table,
            int offset) {
        return new ObjectLocation.Builder()
                .setFilePath(FILE)
                .setOffset(offset)
                .setReference(new ObjectReference(schema, table, DbObjType.TABLE))
                .setLocationType(LocationType.REFERENCE)
                .build();
    }
}
