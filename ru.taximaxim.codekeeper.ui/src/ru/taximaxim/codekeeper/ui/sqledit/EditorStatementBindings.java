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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.base.parser.CodeUnitToken;
import org.pgcodekeeper.core.database.base.schema.meta.MetaContainer;
import org.pgcodekeeper.core.database.pg.parser.PgParserUtils;
import org.pgcodekeeper.core.database.pg.parser.expr.PgDelete;
import org.pgcodekeeper.core.database.pg.parser.expr.PgInsert;
import org.pgcodekeeper.core.database.pg.parser.expr.PgSelect;
import org.pgcodekeeper.core.database.pg.parser.expr.PgUpdate;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLLexer;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.Data_statementContext;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.StatementContext;

import ru.taximaxim.codekeeper.ui.Log;

/**
 * The alias bindings of the statement the caret stands in, read out of the
 * editor buffer rather than out of the project.
 * <p>
 * A statement written straight into the editor leaves the analyzer no binding
 * to record, and not by accident: references are a by-product of analyzing an
 * object of the model, a top level DML is no such object, and there is nothing
 * to attach an analysis launcher to. The columns behind {@code alias.} are
 * therefore silent in a file no project owns - which is where a query is
 * written before it is a routine.
 * <p>
 * What is missing is only the trigger, not the machinery: the expression
 * analyzer resolves a FROM clause out of the text alone, without asking the
 * project for anything, so it answers on an empty meta container and it answers
 * on a statement that is still half written - the parser's own recovery hands
 * it the FROM clause even when the tail is a bare dot. This calls it directly,
 * on the one statement the caret is inside of, and hands the result to the
 * completion as one more collection of references. Nothing is written into any
 * index: the records live for the length of one keystroke.
 * <p>
 * Only PostgreSQL is served. The other dialects have the same machinery behind
 * a different set of classes, and their editors keep the behaviour they had.
 */
final class EditorStatementBindings {

    /**
     * The name the parser reports its errors under. They are dropped: a caret
     * inside a statement being written is an error by construction.
     */
    private static final String PARSED_NAME = "editor buffer"; //$NON-NLS-1$

    /**
     * Reads the bindings the statement under the caret declares.
     *
     * @param textUpToCaret document text from its start up to the caret
     * @return bindings at their offsets in the document, empty when the
     *         statement declares none or cannot be read
     */
    static List<ObjectLocation> of(String textUpToCaret) {
        try {
            return bindings(textUpToCaret);
        } catch (RuntimeException ex) {
            // Half written text is what this is asked about, so a parser
            // giving up on it is the expected case rather than a defect, and
            // a completion that throws would take the assistant down with it.
            Log.log(Log.LOG_DEBUG, "Failed to read the statement under the caret", ex); //$NON-NLS-1$
            return List.of();
        }
    }

    private static List<ObjectLocation> bindings(String textUpToCaret) {
        int start = statementStart(textUpToCaret);
        String fragment = textUpToCaret.substring(start);
        if (fragment.isBlank()) {
            return List.of();
        }

        SQLParser parser = PgParserUtils.createSqlParser(fragment, PARSED_NAME, new ArrayList<>());
        Set<ObjectLocation> found = new LinkedHashSet<>();
        for (StatementContext statement : parser.sql().statement()) {
            Data_statementContext data = statement.data_statement();
            if (data != null) {
                analyze(data, found);
            }
        }

        return unambiguous(shifted(found, textUpToCaret, start));
    }

    /**
     * Finds where the statement under the caret begins: right behind the last
     * semicolon above it.
     * <p>
     * The lexer is what answers this, because a semicolon is not a boundary
     * wherever it stands. Inside a string literal, inside a dollar quoted body
     * and inside either kind of comment it is text, and a scan of the document
     * for the character would cut the statement in a place the parser never
     * would.
     *
     * @param head document text up to the caret
     * @return offset the statement starts at, zero when no semicolon precedes
     *         the caret
     */
    private static int statementStart(String head) {
        SQLLexer lexer = new SQLLexer(CharStreams.fromString(head));
        // The text is being written, so it is allowed to be wrong; the console
        // listener would report every keystroke that is not a statement yet.
        lexer.removeErrorListeners();
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        tokens.fill();

        List<Token> read = tokens.getTokens();
        for (int i = read.size() - 1; i >= 0; i--) {
            Token token = read.get(i);
            if (SQLLexer.SEMI_COLON == token.getType() && token instanceof CodeUnitToken unit) {
                return unit.getCodeUnitStop() + 1;
            }
        }

        return 0;
    }

    /**
     * Analyzes one data statement, adding whatever it resolved to the given
     * set.
     * <p>
     * The four kinds handled here are the four the analyzer offers a public
     * constructor for. MERGE is left out: its analyzer is built from a parent
     * expression rather than from a meta container, and reaching it would mean
     * changing the core for the one statement nobody completes an alias in.
     */
    private static void analyze(Data_statementContext data, Set<ObjectLocation> found) {
        MetaContainer meta = new MetaContainer();
        if (data.select_stmt() != null) {
            PgSelect select = new PgSelect(meta);
            select.analyze(data.select_stmt());
            found.addAll(select.getDependencies());
        } else if (data.insert_stmt_for_psql() != null) {
            PgInsert insert = new PgInsert(meta);
            insert.analyze(data.insert_stmt_for_psql());
            found.addAll(insert.getDependencies());
        } else if (data.update_stmt_for_psql() != null) {
            PgUpdate update = new PgUpdate(meta);
            update.analyze(data.update_stmt_for_psql());
            found.addAll(update.getDependencies());
        } else if (data.delete_stmt_for_psql() != null) {
            PgDelete delete = new PgDelete(meta);
            delete.analyze(data.delete_stmt_for_psql());
            found.addAll(delete.getDependencies());
        }
    }

    /**
     * Moves the records from the fragment they were parsed in onto the
     * document, and keeps the ones that bind an alias.
     * <p>
     * The offsets the parser hands back count from the start of the fragment,
     * and the completion ranks bindings by their place in the document. The
     * three offsets are what the analysis of a routine body applies to the
     * same records for the same reason - the fragment's first line is the
     * remainder of a document line, the rest are lines of their own.
     */
    private static List<ObjectLocation> shifted(Collection<ObjectLocation> found,
            String head, int start) {
        int lineOffset = 0;
        int lineStart = 0;
        for (int i = 0; i < start; i++) {
            if ('\n' == head.charAt(i)) {
                lineOffset++;
                lineStart = i + 1;
            }
        }

        int inLineOffset = start - lineStart;
        List<ObjectLocation> shifted = new ArrayList<>();
        for (ObjectLocation location : found) {
            // A record the parser gave no position to cannot be ranked by one.
            if (SQLEditorCompletionProcessor.bindsAnAlias(location) && location.getLineNumber() != 0) {
                shifted.add(location.copyWithOffset(start, lineOffset, inLineOffset, PARSED_NAME));
            }
        }

        return shifted;
    }

    /**
     * Drops the aliases the statement binds more than once.
     * <p>
     * One statement may bind the same alias to two relations - a subquery is
     * free to name its own source after the outer one - and the completion
     * answers with the binding closest above the caret, which for a subquery
     * written above the caret is the inner and wrong one. The scope that would
     * tell them apart is not in this flat set of records: it lives in the
     * nested namespaces of the analyzer and is gone by the time the records
     * are collected.
     * <p>
     * So the ambiguity is answered by silence rather than by a guess. Two
     * records naming the same relation are not ambiguous - the declaration of
     * an alias and every use of it below are exactly that, and they are the
     * ordinary case, not the doubtful one.
     */
    private static List<ObjectLocation> unambiguous(List<ObjectLocation> bindings) {
        Map<String, ObjectReference> bound = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (ObjectLocation binding : bindings) {
            String alias = aliasOf(binding);
            ObjectReference first = bound.putIfAbsent(alias, binding.getObjectReference());
            if (first != null && !first.equals(binding.getObjectReference())) {
                ambiguous.add(alias);
            }
        }

        if (ambiguous.isEmpty()) {
            return bindings;
        }

        return bindings.stream()
                .filter(binding -> !ambiguous.contains(aliasOf(binding)))
                .toList();
    }

    private static String aliasOf(ObjectLocation binding) {
        String bare = binding.getBareName();
        return bare == null ? "" : bare.toUpperCase(Locale.ROOT); //$NON-NLS-1$
    }

    private EditorStatementBindings() {
    }
}
