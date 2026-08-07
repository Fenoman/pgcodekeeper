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
package ru.taximaxim.codekeeper.ui.sqledit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation.LocationType;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.base.schema.meta.MetaRelation;
import org.pgcodekeeper.core.database.base.schema.meta.MetaStatement;
import org.pgcodekeeper.core.monitor.NullMonitor;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.utils.Pair;

import ru.taximaxim.codekeeper.ui.DatabaseType;
import ru.taximaxim.codekeeper.ui.copiedclasses.CompletionProposal;
import ru.taximaxim.codekeeper.ui.sqledit.SQLEditorCompletionProcessor.Completion;
import ru.taximaxim.codekeeper.ui.sqledit.SQLEditorCompletionProcessor.RelationColumns;

/**
 * Column completion behind a dot: which relation the editor reads the columns
 * from, and which columns of it reach the proposal list.
 */
class SQLEditorCompletionProcessorTest {

    @Test
    void aTrailingDotLeavesTheQualifierAndAnEmptyNameToComplete() {
        String aliased = "SELECT u.";
        String qualified = "SELECT * FROM app.users.";

        assertEquals(List.of("", "U"),
                SQLEditorCompletionProcessor.getCursorText(aliased, aliased.length()));
        assertEquals(List.of("", "USERS", "APP"),
                SQLEditorCompletionProcessor.getCursorText(qualified, qualified.length()));
    }

    @Test
    void theTypedTextIsTheNameBeingCompleted() {
        String text = "SELECT u.log";

        assertEquals(List.of("LOG", "U"),
                SQLEditorCompletionProcessor.getCursorText(text, text.length()));
    }

    @Test
    void anAliasOffersTheColumnsOfTheRelationItStandsFor() {
        Relations relations = new Relations().with("app", "users", "id", "login");

        List<Pair<String, String>> columns = SQLEditorCompletionProcessor.getColumns(
                List.of("", "U"), 100, List.of(alias("u", 40, "app", "users")), relations);

        assertEquals(List.of("id", "login"), names(columns));
        assertEquals(List.of("APP.USERS"), relations.asked);
    }

    /**
     * One routine body may bind the same alias to a different relation in every
     * statement it holds, so the binding written above the caret is the answer,
     * not just any binding in the file.
     */
    @Test
    void theAliasBindingClosestAboveTheCaretWins() {
        Relations relations = new Relations()
                .with("app", "users", "login")
                .with("app", "orders", "total");
        List<ObjectLocation> references = List.of(
                alias("t", 10, "app", "users"),
                alias("t", 90, "app", "orders"),
                alias("t", 120, "app", "users"));

        List<Pair<String, String>> columns = SQLEditorCompletionProcessor.getColumns(
                List.of("", "T"), 100, references, relations);

        assertEquals(List.of("total"), names(columns));
    }

    @Test
    void anAliasBoundOnlyBelowTheCaretIsNotRead() {
        Relations relations = new Relations().with("app", "users", "login");

        List<Pair<String, String>> columns = SQLEditorCompletionProcessor.getColumns(
                List.of("", "T"), 100, List.of(alias("t", 120, "app", "users")), relations);

        assertEquals(List.of(), names(columns));
        assertEquals(List.of(), relations.asked, "an unresolved alias must not be looked up");
    }

    /**
     * The FROM clause that declares an alias binds it, and it is the only thing
     * that does until a column is written behind that alias. A routine still
     * being written has no such column anywhere - that is what is being typed.
     */
    @Test
    void anAliasDeclaredInAFromClauseIsBoundBeforeItIsEverUsed() {
        Relations relations = new Relations().with("app", "users", "id", "login");

        List<Pair<String, String>> columns = SQLEditorCompletionProcessor.getColumns(
                List.of("", "T"), 100, List.of(declared("t", 40, "app", "users")), relations);

        assertEquals(List.of("id", "login"), names(columns));
        assertEquals(List.of("APP.USERS"), relations.asked);
    }

    /**
     * A declaration binds an alias to a relation, and the columns behind a dot
     * come from a relation and from nothing else. A declaration that names
     * anything else is not a relation to read columns from, and must not be
     * looked up as one.
     */
    @Test
    void aDeclarationThatNamesNoRelationBindsNothing() {
        Relations relations = new Relations().with("app", "report", "id");
        ObjectLocation routine = new ObjectLocation.Builder()
                .setFilePath("schema/report.sql")
                .setOffset(40)
                .setAlias("t")
                .setReference(new ObjectReference("app", "report", DbObjType.FUNCTION))
                .setLocationType(LocationType.VARIABLE)
                .build();

        assertEquals(List.of(), names(SQLEditorCompletionProcessor.getColumns(
                List.of("", "T"), 100, List.of(routine), relations)));
        assertEquals(List.of(), relations.asked,
                "a variable that names no relation must not be looked up as one");
    }

    /**
     * The two kinds of binding are read as one list, so the nearest above the
     * caret wins across both: a declaration written below an earlier use of the
     * same alias is the later binding and answers.
     */
    @Test
    void aDeclarationAndAUseAreRankedByOffsetAlike() {
        Relations relations = new Relations()
                .with("app", "users", "login")
                .with("app", "orders", "total");

        assertEquals(List.of("total"), names(SQLEditorCompletionProcessor.getColumns(
                List.of("", "T"), 100, List.of(alias("t", 10, "app", "users"),
                        declared("t", 90, "app", "orders")), relations)));
        assertEquals(List.of("login"), names(SQLEditorCompletionProcessor.getColumns(
                List.of("", "T"), 100, List.of(declared("t", 10, "app", "orders"),
                        alias("t", 90, "app", "users")), relations)));
    }

    @Test
    void anUnknownAliasOffersNothing() {
        Relations relations = new Relations().with("app", "users", "login");

        assertEquals(List.of(), names(SQLEditorCompletionProcessor.getColumns(
                List.of("", "X"), 100, List.of(alias("u", 40, "app", "users")), relations)));
    }

    /**
     * The alias is what stands in the text, and it is the only thing a two
     * segment name may be matched against: the relation behind it is spelt
     * differently by definition, and its own name belongs to the three segment
     * path.
     */
    @Test
    void theRelationBehindAnAliasIsNotItsOwnQualifier() {
        Relations relations = new Relations().with("app", "users", "login");

        assertEquals(List.of(), names(SQLEditorCompletionProcessor.getColumns(
                List.of("", "USERS"), 100, List.of(alias("u", 40, "app", "users")), relations)));
    }

    /**
     * A name written out in full is recorded as a plain reference to the object
     * itself. Reading such a reference as an alias would offer the columns of
     * every relation the file merely mentions.
     */
    @Test
    void aPlainReferenceIsNotAnAliasBinding() {
        Relations relations = new Relations().with("app", "users", "login");
        ObjectLocation reference = new ObjectLocation.Builder()
                .setFilePath("schema/report.sql")
                .setOffset(40)
                .setAlias("users")
                .setReference(new ObjectReference("app", "users", DbObjType.TABLE))
                .setLocationType(LocationType.REFERENCE)
                .build();

        assertEquals(List.of(), names(SQLEditorCompletionProcessor.getColumns(
                List.of("", "USERS"), 100, List.of(reference), relations)));
    }

    @Test
    void aSchemaQualifiedRelationNeedsNoAlias() {
        Relations relations = new Relations().with("app", "users", "id", "login");

        List<Pair<String, String>> columns = SQLEditorCompletionProcessor.getColumns(
                List.of("", "USERS", "APP"), 100, List.of(), relations);

        assertEquals(List.of("id", "login"), names(columns));
        assertEquals(List.of("APP.USERS"), relations.asked,
                "the outer segment is the schema, the inner one the relation");
    }

    @Test
    void theTypedTextKeepsTheMatchesAndPutsTheStartingOnesFirst() {
        Relations relations = new Relations()
                .with("app", "users", "user_login", "login", "id", "login_hash");

        List<Pair<String, String>> columns = SQLEditorCompletionProcessor.getColumns(
                List.of("LOGIN", "USERS", "APP"), 100, List.of(), relations);

        assertEquals(List.of("login", "login_hash", "user_login"), names(columns));
    }

    /**
     * A field of a composite column - {@code app.users.settings.} - is four
     * segments, and none of the two paths fits it. The segment behind the dot
     * may well be a live alias elsewhere in the file, so the count is what
     * decides, not the failure of a lookup.
     */
    @Test
    void aDeeperNameOffersNothingEvenWhenItsQualifierIsAKnownAlias() {
        Relations relations = new Relations()
                .with("app", "users", "id", "login")
                .with("app", "settings", "theme");
        List<ObjectLocation> references = List.of(
                alias("settings", 40, "app", "settings"),
                alias("users", 50, "app", "users"));

        assertEquals(List.of(), names(SQLEditorCompletionProcessor.getColumns(
                List.of("", "SETTINGS", "USERS", "APP"), 100, references, relations)));
        assertEquals(List.of(), relations.asked,
                "a name this deep must not be read as a relation at all");
    }

    @Test
    void onlyTheNamedRelationAnswersAmongTheCandidates() {
        List<MetaStatement> candidates = List.of(
                relation("app", "users_history", "changed_at"),
                relation("audit", "users", "who"),
                relation("app", "users", "id", "login"),
                new MetaStatement(new ObjectReference("app", "users", DbObjType.FUNCTION)));

        assertEquals(List.of("id", "login"), names(
                SQLEditorCompletionProcessor.findRelationColumns(
                        candidates.stream(), "APP", "USERS")));
        assertEquals(List.of("who"), names(
                SQLEditorCompletionProcessor.findRelationColumns(
                        candidates.stream(), "audit", "users")));
        assertEquals(List.of("changed_at"), names(
                SQLEditorCompletionProcessor.findRelationColumns(
                        candidates.stream(), null, "users_history")),
                "an unknown schema must not narrow the search");
        assertEquals(List.of(), names(
                SQLEditorCompletionProcessor.findRelationColumns(
                        candidates.stream(), "app", "orders")));
    }

    /**
     * A view whose columns the analysis never worked out answers nothing, and
     * nothing is not an answer: a namesake in another schema may still hold the
     * columns the caret is asking for.
     */
    @Test
    void aRelationWithoutAnalyzedColumnsYieldsToItsNamesake() {
        List<MetaStatement> candidates = List.of(
                new MetaRelation("app", "users", DbObjType.VIEW),
                relation("audit", "users", "who"));

        assertEquals(List.of("who"), names(
                SQLEditorCompletionProcessor.findRelationColumns(
                        candidates.stream(), null, "users")));
    }

    /**
     * The two segment path rests on two things the analyzer does: at every
     * {@code alias.column} it resolves it writes down the relation the alias
     * stood for, at the offset the alias holds in the file; and the bare name of
     * such a record is the alias, while the bare name of every other record is
     * the name of the object itself. This proves both, on genuinely parsed SQL,
     * and runs the completion over what the analyzer wrote.
     */
    @Test
    void theAnalyzerRecordsAnAliasTheCompletionCanResolve() throws Exception {
        String path = "schema/report.sql";
        String sql = """
                CREATE SCHEMA app;

                CREATE TABLE app.users (
                    id bigint,
                    login text);

                CREATE FUNCTION app.report() RETURNS text
                    LANGUAGE sql AS $$
                        SELECT u.login FROM app.users u WHERE u.id > 0;
                    $$;
                """;
        Set<ObjectLocation> references = analyzed(sql, path);

        ObjectLocation recorded = references.stream()
                .filter(reference -> reference.getLocationType() == LocationType.LOCAL_REF)
                .filter(reference -> reference.getOffset() == sql.indexOf("u.login"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no local reference at the alias of 'u.login': " + references));
        assertEquals("u", recorded.getBareName());
        assertEquals(new ObjectReference("app", "users", DbObjType.TABLE),
                recorded.getObjectReference());

        ObjectLocation writtenOut = references.stream()
                .filter(reference -> reference.getLocationType() == LocationType.REFERENCE)
                .filter(reference -> reference.getOffset() == sql.indexOf("app.users u") + 4)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no plain reference at the relation of the FROM clause: " + references));
        assertEquals("users", writtenOut.getBareName(),
                "outside a local reference the bare name is the object, not an alias");

        int caret = sql.indexOf("u.id") + 2;
        List<Pair<String, String>> columns = SQLEditorCompletionProcessor.getColumns(
                SQLEditorCompletionProcessor.getCursorText(sql.substring(0, caret), caret),
                caret, references, new Relations().with("app", "users", "id", "login"));

        assertEquals(List.of("id", "login"), names(columns));
    }

    /**
     * The alias of a routine still being written: declared in a FROM clause and
     * used nowhere, because the use is what the caret is about to type. On
     * genuinely parsed SQL this pins what the analyzer leaves for such an alias
     * - one variable, at the alias, naming the relation - and that a routine's
     * own declared variables leave nothing at all, which is the reason a
     * variable may be read as a binding in the first place.
     */
    @Test
    void anAliasUsedNowhereYetIsStillTheOneTheCaretIsInside() throws Exception {
        String path = "schema/report.sql";
        String sql = """
                CREATE SCHEMA app;

                CREATE TABLE app.users (
                    id bigint,
                    login text);

                CREATE FUNCTION app.report() RETURNS text
                    LANGUAGE plpgsql AS $$
                    DECLARE
                        v_count integer;
                    BEGIN
                        SELECT count(*) INTO v_count FROM app.users AS t;
                        RETURN v_count::text;
                    END;
                    $$;
                """;
        Set<ObjectLocation> references = analyzed(sql, path);

        ObjectLocation binding = references.stream()
                .filter(reference -> "t".equals(reference.getBareName()))
                .reduce((first, second) -> {
                    throw new AssertionError("more than one record binds the alias: " + references);
                })
                .orElseThrow(() -> new AssertionError(
                        "no record binds the alias of 'app.users AS t': " + references));
        assertEquals(LocationType.VARIABLE, binding.getLocationType(),
                "an alias used nowhere is left as its declaration and nothing else");
        assertEquals(new ObjectReference("app", "users", DbObjType.TABLE),
                binding.getObjectReference());
        assertEquals(List.of(), references.stream()
                .filter(reference -> "v_count".equals(reference.getBareName())).toList(),
                "a declared variable of a routine leaves no record among the references");

        // The saved file as it was analyzed, plus the alias.column being typed.
        String typed = sql.substring(0, sql.indexOf("RETURN v_count")) + "t.";
        int caret = typed.length();
        List<Pair<String, String>> columns = SQLEditorCompletionProcessor.getColumns(
                SQLEditorCompletionProcessor.getCursorText(typed, caret), caret,
                references, new Relations().with("app", "users", "id", "login"));

        assertEquals(List.of("id", "login"), names(columns));
    }

    /**
     * A statement written straight into the editor leaves no alias to resolve:
     * the analyzer records one reference for the whole of it and nothing else.
     * Nothing above this method can offer columns there, and pinning it is what
     * keeps the two cases apart when the next one is investigated.
     */
    @Test
    void aTopLevelStatementRecordsNoAliasToResolve() throws Exception {
        String path = "schema/report.sql";
        String sql = "SELECT count(*) FROM app.users AS t;\n";

        Set<ObjectLocation> references = analyzed(sql, path);

        assertEquals(List.of(), references.stream()
                .filter(reference -> reference.getLocationType() == LocationType.VARIABLE
                        || reference.getLocationType() == LocationType.LOCAL_REF)
                .toList(), "a top level statement binds no alias the completion could read");

        String typed = sql + "SELECT t.";
        int caret = typed.length();

        assertEquals(List.of(), names(SQLEditorCompletionProcessor.getColumns(
                SQLEditorCompletionProcessor.getCursorText(typed, caret), caret,
                references, new Relations().with("app", "users", "id", "login"))));
    }

    /**
     * The statement being written into an editor of its own, which is where
     * the whole of this begins: the project records no alias for a top level
     * statement, so the statement under the caret is analyzed out of the
     * buffer instead. The tail is half typed - a bare dot is what asks for the
     * columns - and the parser's recovery is what still hands the FROM clause
     * over.
     */
    @Test
    void theStatementBeingWrittenBindsItsAliasOutOfTheBuffer() {
        String typed = "select * FROM dbo.sd_subscr as t where t.";
        Relations relations = new Relations().with("dbo", "sd_subscr", "id", "name");

        assertEquals(List.of("t -> dbo.sd_subscr"), bound(typed));
        assertEquals(List.of("id", "name"), names(columnsOf(typed, relations)));
        assertEquals(List.of("DBO.SD_SUBSCR"), relations.asked);
    }

    /**
     * A JOIN binds every alias it names, and each answers its own relation.
     */
    @Test
    void everyAliasOfAJoinIsBoundToItsOwnRelation() {
        String head = "SELECT * FROM app.users u JOIN app.orders o ON o.uid = u.id WHERE ";
        Relations relations = new Relations()
                .with("app", "users", "login")
                .with("app", "orders", "total");

        assertEquals(List.of("login"), names(columnsOf(head + "u.", relations)));
        assertEquals(List.of("total"), names(columnsOf(head + "o.", relations)));
    }

    /**
     * Every kind of data statement reaches its aliases through the same
     * machinery, each through the analyzer of its own kind.
     */
    @Test
    void anUpdateADeleteAndAnInsertBindTheirAliasesToo() {
        Relations relations = new Relations()
                .with("app", "users", "login")
                .with("app", "people", "name");

        assertEquals(List.of("login"), names(columnsOf(
                "UPDATE app.users AS t SET login = '' WHERE t.", relations)));
        assertEquals(List.of("login"), names(columnsOf(
                "DELETE FROM app.users AS t WHERE t.", relations)));
        assertEquals(List.of("name"), names(columnsOf(
                "INSERT INTO app.users (login) SELECT t.name FROM app.people t WHERE t.",
                relations)));
    }

    /**
     * The records are moved onto the document they were read from: the offsets
     * the parser hands back count from the start of the fragment, and the
     * completion ranks bindings by their place in the document. Left unmoved,
     * a binding of the statement being written would rank below every earlier
     * one in the file.
     */
    @Test
    void theBindingIsPlacedWhereTheDocumentHasIt() {
        String typed = """
                select 1;
                select * FROM app.users AS t where t.""";

        List<ObjectLocation> bindings = EditorStatementBindings.of(typed);

        assertEquals(1, bindings.size(), () -> "one binding was expected: " + bindings);
        ObjectLocation binding = bindings.get(0);
        assertEquals(typed.indexOf("AS t") + 3, binding.getOffset(),
                "the alias is at the offset the document holds it at");
        assertEquals(2, binding.getLineNumber(), "on the document's line, not the fragment's");
    }

    /**
     * The statement the caret is inside of is the one that is read, and where
     * it begins is answered by the lexer: a semicolon inside a string literal
     * is text, and a scan of the document for the character would cut the
     * statement in a place the parser never would - losing the FROM clause
     * that binds the alias.
     */
    @Test
    void theSemicolonThatBeginsTheStatementIsFoundByTheLexer() {
        Relations relations = new Relations()
                .with("app", "users", "login")
                .with("app", "orders", "total");

        assertEquals(List.of("total"), names(columnsOf(
                "SELECT * FROM app.users t; SELECT * FROM app.orders t WHERE t.", relations)),
                "the statement above the caret is not part of the one being written");
        assertEquals(List.of("login"), names(columnsOf(
                "SELECT 'a;b' FROM app.users t WHERE t.", relations)),
                "a semicolon inside a literal is not a statement boundary");
        assertEquals(List.of("login"), names(columnsOf(
                "SELECT * FROM app.users t -- a; comment\n WHERE t.", relations)),
                "nor is one inside a comment");
    }

    /**
     * One statement may bind the same alias to two relations - a subquery is
     * free to name its own source after the outer one - and the binding
     * closest above the caret, which is what answers, is then the inner and
     * wrong one. The columns of the wrong relation are worse than none, so
     * such an alias is answered by silence.
     */
    @Test
    void anAliasBoundToTwoRelationsInOneStatementOffersNothing() {
        String typed = "SELECT * FROM a.t1 x WHERE id IN (SELECT id FROM a.t2 x) AND x.";
        Relations relations = new Relations()
                .with("a", "t1", "one")
                .with("a", "t2", "two");

        assertEquals(List.of(), bound(typed), "an ambiguous alias is not offered at all");
        assertEquals(List.of(), names(columnsOf(typed, relations)));
        assertEquals(List.of(), relations.asked,
                "a relation must not be asked for on a guess between two");
    }

    /**
     * The declaration of an alias and every use of it below are two records of
     * the same binding, and they are the ordinary case: the guard above must
     * not read them as an alias bound twice, or the completion would fall
     * silent on the second column written behind any alias.
     */
    @Test
    void aDeclarationAndItsUseAreOneBindingNotTwo() {
        String typed = "select * FROM app.users AS t where t.login = '' and t.";
        Relations relations = new Relations().with("app", "users", "id", "login");

        assertEquals(List.of("t -> app.users", "t -> app.users"), bound(typed),
                "the declaration and the use are both recorded");
        assertEquals(List.of("id", "login"), names(columnsOf(typed, relations)));
    }

    /**
     * What the buffer leaves silent, all of it because the statement names no
     * relation the columns could come from. None of these is a lie: nothing is
     * offered rather than the columns of something else.
     */
    @Test
    void whatTheStatementUnderTheCaretCannotAnswer() {
        assertEquals(List.of(), bound("select t. FROM app.users AS t"),
                "a dot before the FROM clause loses the statement to the parser");
        assertEquals(List.of(), bound("SELECT * FROM (SELECT id FROM app.users) s WHERE s."),
                "a subquery is not a named relation");
        assertEquals(List.of(), bound("WITH c AS (SELECT 1) SELECT * FROM c WHERE c."),
                "nor is a common table expression");
        assertEquals(List.of(), bound("SELECT * FROM generate_series(1, 2) g WHERE g."),
                "nor is a function call");
        assertEquals(List.of(), bound("CREATE FUNCTION app.report() RETURNS text"
                + " LANGUAGE sql AS $$ SELECT * FROM app.users t WHERE t."),
                "a routine body is not a data statement, and the project answers it");
    }

    /**
     * A FROM clause that does not say its schema is recorded against the
     * system schema rather than against the one the object is declared in, so
     * the relation is asked for under a name no project holds. The alias binds
     * - there is a relation named - and the lookup answers nothing.
     */
    @Test
    void anUnqualifiedFromNamesTheSystemSchemaAndOffersNothing() {
        String typed = "select * from users as t where t.";
        Relations relations = new Relations().with("app", "users", "login");

        assertEquals(List.of("t -> pg_catalog.users"), bound(typed));
        assertEquals(List.of(), names(columnsOf(typed, relations)));
        assertEquals(List.of("PG_CATALOG.USERS"), relations.asked);
    }

    /**
     * The whole path, as the completion runs it: the project holds the
     * relation and no binding for it, the buffer holds the binding and nothing
     * else, and the line says which of the two answered - the one thing that
     * tells a working ad-hoc statement from a working routine body.
     */
    @Test
    void theLineOfADotSaysTheAliasCameFromTheBuffer() {
        Source source = new Source()
                .with(relation("app", "users", "id", "login"))
                .typing(declared("u", 40, "app", "users"));

        Completion completion = SQLEditorCompletionProcessor.getKeys(
                100, List.of("", "U"), source, List.of("SELECT"));

        assertEquals(List.of("id - text", "login - text"), displayed(completion));
        assertEquals("pgCodeKeeper completion: prefix_length=0 name_segments=2"
                + " objects_examined=0 objects_proposed=0"
                + " relation_asked=true alias_source=buffer columns_proposed=2",
                withoutTime(completion));
    }

    /**
     * The binding closest above the caret answers across both sources, so a
     * statement being written answers over what the saved file recorded above
     * it, and what the saved file recorded below the caret answers neither
     * way.
     */
    @Test
    void theNearestBindingWinsAcrossTheProjectAndTheBuffer() {
        Source source = new Source()
                .with(relation("app", "users", "login"))
                .with(relation("app", "orders", "total"))
                .seenIn(alias("u", 40, "app", "users"))
                .typing(declared("u", 90, "app", "orders"));

        assertEquals(List.of("total - text"), displayed(SQLEditorCompletionProcessor.getKeys(
                100, List.of("", "U"), source, List.of("SELECT"))));
    }

    /**
     * Only a two segment name is answered by an alias. A name that says its
     * schema and its relation outright must not cost a parse of the buffer,
     * and neither must a deeper one.
     */
    @Test
    void aNameThatNeedsNoAliasDoesNotReadTheBuffer() {
        Source source = new Source().with(relation("app", "users", "id"));

        assertEquals(List.of("id - text"), displayed(SQLEditorCompletionProcessor.getKeys(
                100, List.of("", "USERS", "APP"), source, List.of("SELECT"))));
        assertEquals(0, source.bufferReads, "a qualified name resolves no alias");

        SQLEditorCompletionProcessor.getKeys(
                100, List.of("", "SETTINGS", "USERS", "APP"), source, List.of("SELECT"));
        assertEquals(0, source.bufferReads, "a deeper name is not read as a relation at all");
    }

    /** The bindings of the statement being written, as {@code alias -> relation}. */
    private static List<String> bound(String typed) {
        return EditorStatementBindings.of(typed).stream()
                .map(binding -> binding.getBareName() + " -> "
                        + binding.getObjectReference().schema() + '.'
                        + binding.getObjectReference().table())
                .toList();
    }

    /**
     * The columns the completion offers behind the trailing dot of the given
     * text, resolved through the bindings of the statement being written.
     */
    private static List<Pair<String, String>> columnsOf(String typed, RelationColumns relations) {
        int caret = typed.length();
        return SQLEditorCompletionProcessor.getColumns(
                SQLEditorCompletionProcessor.getCursorText(typed, caret), caret,
                EditorStatementBindings.of(typed), relations);
    }

    /**
     * Loads and analyzes one file the way the project builder does.
     *
     * @return the references the analyzer recorded for it
     */
    private static Set<ObjectLocation> analyzed(String sql, String path) throws Exception {
        var settings = new CoreSettings();
        settings.setMonitor(new NullMonitor());
        try (var loader = DatabaseType.PG.getDatabaseProvider().getDumpLoader(
                () -> new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8)),
                path, settings)) {
            IDatabase analyzed = loader.loadAndAnalyze();
            assertEquals(List.of(), loader.getErrors());
            Set<ObjectLocation> references = analyzed.getObjReferences().get(path);
            assertNotNull(references, "the analyzed file must have left its references");
            return references;
        }
    }

    /**
     * Accepting a proposal must rewrite the text already typed behind the dot,
     * not append the column name to it.
     */
    @Test
    void theProposalRewritesTheTextTypedBehindTheDot() {
        CompletionProposal onTypedText = SQLEditorCompletionProcessor.getColumnProposal(
                100, "LOG", new Pair<>("login", "text"));

        assertEquals("login", onTypedText.getReplacementString());
        assertEquals(97, onTypedText.getReplacementOffset());
        assertEquals(3, onTypedText.getReplacementLength());
        assertEquals("login - text", onTypedText.getDisplayString());

        CompletionProposal onBareDot = SQLEditorCompletionProcessor.getColumnProposal(
                100, "", new Pair<>("login", "text"));

        assertEquals(100, onBareDot.getReplacementOffset());
        assertEquals(0, onBareDot.getReplacementLength());
    }

    /**
     * The text behind a trailing dot is empty, and an empty text used to reach
     * the project as a search for everything - which the packed index can only
     * answer by decoding itself entire. Nothing is asked now.
     */
    @Test
    void anEmptyNameAsksTheProjectForNoObjectsAtAll() {
        Source source = new Source();

        Completion completion = SQLEditorCompletionProcessor.getKeys(
                100, List.of("", "U"), source, List.of("SELECT"));

        assertNothingAsked(source, "an empty name must not be searched for");
        assertEquals(List.of(), displayed(completion));
    }

    @Test
    void aBareCaretOffersKeywordsAndNoObjects() {
        Source source = new Source();

        Completion completion = SQLEditorCompletionProcessor.getKeys(
                100, List.of(""), source, List.of("SELECT", "INSERT"));

        assertNothingAsked(source, "a bare caret must not be searched for");
        assertEquals(List.of("SELECT", "INSERT"), displayed(completion));
    }

    /**
     * Two letters are not a trigram, and the index cannot search its names by
     * less. Keywords are the editor's own list and stay.
     */
    @Test
    void oneOrTwoLettersOfferKeywordsAndNoObjects() {
        Source source = new Source().with(table("app", "selector"));

        assertEquals(List.of("SELECT"), displayed(SQLEditorCompletionProcessor.getKeys(
                100, List.of("S"), source, List.of("SELECT", "CREATE"))));
        assertEquals(List.of("SELECT"), displayed(SQLEditorCompletionProcessor.getKeys(
                100, List.of("SE"), source, List.of("SELECT", "CREATE"))));
        assertNothingAsked(source, "a name shorter than a trigram must not be searched for");
    }

    /**
     * The columns of a relation are asked for by naming it, not by reading the
     * project through. What is pinned is the question: one lookup, naming the
     * schema, the relation and the relation family.
     */
    @Test
    void theColumnsOfARelationAreAskedForByNamingIt() {
        Source source = new Source().with(relation("app", "users", "id", "login"));

        assertEquals(List.of("id", "login"), names(
                SQLEditorCompletionProcessor.relationColumns(source, "app", "users")));
        assertEquals(List.of("TABLE app.users"), source.objectsAsked);
    }

    /**
     * A name written out in the editor arrives upper cased, and an index is
     * keyed by the exact text of a name. The upper case is asked for first
     * because a project may well be written that way; the lower case, which is
     * what an unquoted identifier means, answers.
     */
    @Test
    void anUpperCasedNameIsAlsoAskedForInLowerCase() {
        Source source = new Source().with(relation("app", "users", "id"));

        assertEquals(List.of("id"), names(
                SQLEditorCompletionProcessor.relationColumns(source, "APP", "USERS")));
        assertEquals(List.of("TABLE APP.USERS", "TABLE APP.users",
                "TABLE app.USERS", "TABLE app.users"), source.objectsAsked);
    }

    /**
     * A relation the project does not hold costs the lookups its spellings
     * take and nothing else - there is no reading through to fall back on.
     */
    @Test
    void anUnknownRelationCostsItsLookupsAndNoMore() {
        Source source = new Source().with(relation("app", "users", "id"));

        assertEquals(List.of(), names(
                SQLEditorCompletionProcessor.relationColumns(source, "app", "orders")));
        assertEquals(List.of("TABLE app.orders"), source.objectsAsked);
    }

    /**
     * A name with no schema cannot be looked up by one, and there is nothing
     * left to look it up by. It asks nothing rather than reading the project
     * through, which is what answering it would have to cost.
     */
    @Test
    void aRelationWithoutASchemaAsksNothing() {
        Source source = new Source().with(relation("app", "users", "id"));

        assertEquals(List.of(), names(
                SQLEditorCompletionProcessor.relationColumns(source, null, "users")));
        assertEquals(List.of(), source.objectsAsked);
    }

    /**
     * The whole of the two segment path, end to end: the alias resolves out of
     * the file's own references, and the relation it stood for is the only
     * thing the project is asked about.
     */
    @Test
    void anAliasReachesItsColumnsThroughOneNamedLookup() {
        Source source = new Source().with(relation("app", "users", "id", "login"));

        List<Pair<String, String>> columns = SQLEditorCompletionProcessor.getColumns(
                List.of("", "U"), 100, List.of(alias("u", 40, "app", "users")),
                (schema, relation) -> SQLEditorCompletionProcessor.relationColumns(
                        source, schema, relation));

        assertEquals(List.of("id", "login"), names(columns));
        assertEquals(List.of("TABLE app.users"), source.objectsAsked);
    }

    /**
     * The count that had to be measured from outside the workbench before this
     * line existed. On the empty name a dot leaves behind it must be zero:
     * examining anything there meant the whole index had been decoded.
     */
    @Test
    void theLineOfAnEmptyNameShowsNothingWasExamined() {
        Source source = new Source().with(table("app", "users"));

        Completion completion = SQLEditorCompletionProcessor.getKeys(
                100, List.of("", "U"), source, List.of("SELECT"));

        assertEquals("pgCodeKeeper completion: prefix_length=0 name_segments=2"
                + " objects_examined=0 objects_proposed=0"
                + " relation_asked=false alias_source=none columns_proposed=0",
                withoutTime(completion));
    }

    @Test
    void theLineOfATooShortNameShowsTheLengthThatSilencedIt() {
        Source source = new Source().with(table("app", "selector"));

        Completion completion = SQLEditorCompletionProcessor.getKeys(
                100, List.of("SE"), source, List.of("SELECT"));

        assertEquals("pgCodeKeeper completion: prefix_length=2 name_segments=1"
                + " objects_examined=0 objects_proposed=0"
                + " relation_asked=false alias_source=none columns_proposed=0",
                withoutTime(completion));
    }

    /**
     * What the index answered and what the user was offered are different
     * numbers, and only the first says what the request cost. The keyword among
     * the proposals is counted in neither: it comes from a list held in memory.
     */
    @Test
    void theLineOfASearchSeparatesWhatWasExaminedFromWhatWasOffered() {
        Source source = new Source()
                .with(table("app", "selector"))
                .with(definition("app", "selector_idx", DbObjType.INDEX));

        Completion completion = SQLEditorCompletionProcessor.getKeys(
                100, List.of("SEL"), source, List.of("SELECT"));

        assertEquals(List.of("selector", "SELECT"), displayed(completion));
        assertEquals("pgCodeKeeper completion: prefix_length=3 name_segments=1"
                + " objects_examined=2 objects_proposed=1"
                + " relation_asked=false alias_source=none columns_proposed=0",
                withoutTime(completion));
    }

    @Test
    void theLineOfADotCountsTheColumnsItOffered() {
        Source source = new Source()
                .with(relation("app", "users", "id", "login"))
                .seenIn(alias("u", 40, "app", "users"));

        Completion completion = SQLEditorCompletionProcessor.getKeys(
                100, List.of("", "U"), source, List.of("SELECT"));

        assertEquals(List.of("id - text", "login - text"), displayed(completion));
        assertEquals("pgCodeKeeper completion: prefix_length=0 name_segments=2"
                + " objects_examined=0 objects_proposed=0"
                + " relation_asked=true alias_source=index columns_proposed=2",
                withoutTime(completion));
    }

    /**
     * The two ways a dot ends in no columns. Both offer nothing and both count
     * nothing, and until the line said which of them happened, telling them
     * apart meant running the completion by hand against the reported project.
     * The name behind the dot is the same in both runs below, and so is
     * everything else the line reports: only the field under test moves.
     */
    @Test
    void theLineOfADotSaysWhetherItGotAsFarAsARelation() {
        Source unresolved = new Source().with(relation("app", "users", "id", "login"));

        assertEquals("pgCodeKeeper completion: prefix_length=0 name_segments=2"
                + " objects_examined=0 objects_proposed=0"
                + " relation_asked=false alias_source=none columns_proposed=0",
                withoutTime(SQLEditorCompletionProcessor.getKeys(
                        100, List.of("", "U"), unresolved, List.of("SELECT"))));

        Source resolvedButUnknown = new Source()
                .with(relation("app", "users", "id", "login"))
                .seenIn(alias("u", 40, "app", "orders"));

        assertEquals("pgCodeKeeper completion: prefix_length=0 name_segments=2"
                + " objects_examined=0 objects_proposed=0"
                + " relation_asked=true alias_source=index columns_proposed=0",
                withoutTime(SQLEditorCompletionProcessor.getKeys(
                        100, List.of("", "U"), resolvedButUnknown, List.of("SELECT"))));
        assertEquals(List.of("TABLE app.orders"), resolvedButUnknown.objectsAsked,
                "the alias resolved, and the relation it named is what answered nothing");
    }

    /**
     * The line without its duration, which is the one number a test may not
     * pin. That it is there and is a count of milliseconds is checked here.
     */
    private static String withoutTime(Completion completion) {
        String line = completion.telemetry();
        int time = line.indexOf(" ms=");
        assertTrue(time > 0, () -> "the line must report a duration: " + line);
        assertTrue(line.substring(time + 4).matches("\\d+"),
                () -> "the duration must be a count of milliseconds: " + line);
        return line.substring(0, time);
    }

    private static void assertNothingAsked(Source source, String message) {
        assertEquals(List.of(), source.completionsAsked,
                () -> message + ", asked for " + source.completionsAsked.stream()
                        .map(text -> '\'' + text + '\'').toList());
    }

    @Test
    void threeLettersReachTheProjectAndItsAnswerIsOffered() {
        Source source = new Source().with(table("app", "selector"));

        Completion completion = SQLEditorCompletionProcessor.getKeys(
                100, List.of("SEL"), source, List.of("SELECT"));

        assertEquals(List.of("SEL"), source.completionsAsked);
        assertEquals(List.of("selector", "SELECT"), displayed(completion));
    }

    /**
     * A qualified name - {@code app.sel} - offers the objects of that schema,
     * and it is bounded by the same trigram: {@code app.se} asks nothing.
     */
    @Test
    void aQualifiedNameIsBoundedByTheSameTrigram() {
        Source source = new Source().with(table("app", "selector"));

        assertEquals(List.of("selector"), displayed(SQLEditorCompletionProcessor.getKeys(
                100, List.of("SEL", "APP"), source, List.of("SELECT"))));
        assertEquals(List.of("SEL"), source.completionsAsked);

        assertEquals(List.of(), displayed(SQLEditorCompletionProcessor.getKeys(
                100, List.of("SE", "APP"), source, List.of("SELECT"))),
                "a qualified name shorter than a trigram offers nothing, keywords included");
        assertEquals(List.of("SEL"), source.completionsAsked,
                "the qualified name must not be searched for either");
    }

    private static List<String> displayed(Completion completion) {
        return completion.proposals().stream()
                .map(ICompletionProposal::getDisplayString).toList();
    }

    private static MetaStatement table(String schema, String name) {
        return definition(schema, name, DbObjType.TABLE);
    }

    private static MetaStatement definition(String schema, String name, DbObjType type) {
        return new MetaStatement(new ObjectLocation.Builder()
                .setFilePath("SCHEMA/" + schema + '/' + type + '/' + name + ".sql")
                .setReference(new ObjectReference(schema, name, type))
                .setLocationType(LocationType.DEFINITION)
                .build());
    }

    /**
     * The project as the completion may ask it, remembering what it was asked.
     */
    private static final class Source
            implements SQLEditorCompletionProcessor.CompletionSource {

        private final List<String> completionsAsked = new ArrayList<>();
        private final List<String> objectsAsked = new ArrayList<>();
        private final List<MetaStatement> definitions = new ArrayList<>();
        private final Set<ObjectLocation> references = new LinkedHashSet<>();
        private final List<ObjectLocation> buffered = new ArrayList<>();
        private int bufferReads;

        private Source with(MetaStatement definition) {
            definitions.add(definition);
            return this;
        }

        private Source seenIn(ObjectLocation reference) {
            references.add(reference);
            return this;
        }

        /** A binding of the statement being written, as the buffer gives one. */
        private Source typing(ObjectLocation binding) {
            buffered.add(binding);
            return this;
        }

        @Override
        public List<ObjectLocation> bufferBindings() {
            bufferReads++;
            return buffered;
        }

        @Override
        public Stream<MetaStatement> completionCandidates(String text) {
            completionsAsked.add(text);
            return definitions.stream().filter(definition -> definition.getName()
                    .toUpperCase(Locale.ROOT).contains(text));
        }

        @Override
        public Set<ObjectLocation> editorReferences() {
            return references;
        }

        /**
         * Answers as the project index does: by the exact text of the name, and
         * on the relation family rather than on the exact type.
         */
        @Override
        public Stream<MetaStatement> definitionsFor(ObjectLocation object) {
            objectsAsked.add(asked(object));
            return definitions.stream()
                    .filter(definition -> definition.getObject().compare(object));
        }

        private static String asked(ObjectLocation object) {
            ObjectReference reference = object.getObjectReference();
            return reference.type() + " " + reference.schema() + '.' + reference.table();
        }
    }

    /** A use of an alias, as the analyzer records one. */
    private static ObjectLocation alias(String alias, int offset, String schema, String relation) {
        return binding(alias, offset, schema, relation, LocationType.LOCAL_REF);
    }

    /** The declaration of an alias in a FROM clause, as the analyzer records one. */
    private static ObjectLocation declared(String alias, int offset, String schema, String relation) {
        return binding(alias, offset, schema, relation, LocationType.VARIABLE);
    }

    private static ObjectLocation binding(String alias, int offset, String schema,
            String relation, LocationType type) {
        return new ObjectLocation.Builder()
                .setFilePath("schema/report.sql")
                .setOffset(offset)
                .setAlias(alias)
                .setReference(new ObjectReference(schema, relation, DbObjType.TABLE))
                .setLocationType(type)
                .build();
    }

    private static MetaRelation relation(String schema, String name, String... columns) {
        MetaRelation relation = new MetaRelation(schema, name, DbObjType.TABLE);
        relation.addColumns(columnsOf(columns));
        return relation;
    }

    private static List<Pair<String, String>> columnsOf(String... columns) {
        List<Pair<String, String>> pairs = new ArrayList<>();
        for (String column : columns) {
            pairs.add(new Pair<>(column, "text"));
        }
        return pairs;
    }

    private static List<String> names(List<Pair<String, String>> columns) {
        return columns.stream().map(Pair::getFirst).toList();
    }

    /**
     * Relation columns by name, remembering what it was asked for.
     */
    private static final class Relations implements RelationColumns {

        private final List<String> asked = new ArrayList<>();
        private final Map<String, List<Pair<String, String>>> relations = new LinkedHashMap<>();

        private Relations with(String schema, String relation, String... columns) {
            relations.put(key(schema, relation), columnsOf(columns));
            return this;
        }

        @Override
        public List<Pair<String, String>> get(String schema, String relation) {
            String key = key(schema, relation);
            asked.add(key);
            return relations.getOrDefault(key, List.of());
        }

        private static String key(String schema, String relation) {
            return (schema + '.' + relation).toUpperCase(Locale.ROOT);
        }
    }
}
