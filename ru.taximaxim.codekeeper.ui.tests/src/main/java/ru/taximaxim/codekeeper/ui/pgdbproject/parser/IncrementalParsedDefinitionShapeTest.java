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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.loader.IDumpLoader;
import org.pgcodekeeper.core.database.api.parser.ParserListenerMode;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.base.schema.meta.MetaStatement;
import org.pgcodekeeper.core.database.base.schema.meta.MetaUtils;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.settings.CoreSettings;

import ru.taximaxim.codekeeper.ui.projectindex.DefinitionShapeHasher;
import ru.taximaxim.codekeeper.ui.projectindex.IndexPathOrigin;
import ru.taximaxim.codekeeper.ui.projectindex.IndexPathRef;
import ru.taximaxim.codekeeper.ui.projectindex.PackedDefinition;

/**
 * Proves that the dependency-visible shape of a definition is complete once
 * the file is parsed. The incremental batch relies on this: it turns a file
 * down between the parse and the analysis, and an analysis that still filled
 * shape fields would make that rejection premature.
 */
class IncrementalParsedDefinitionShapeTest {

    private static final IndexPathRef PATH =
            new IndexPathRef(IndexPathOrigin.PROJECT, "SCHEMA/app/changed.sql");
    private static final String ABSOLUTE = "/project/" + PATH.relativePath();

    private static final List<String> CORPUS = List.of(
            """
            CREATE SCHEMA app;
            CREATE TABLE app.item (
                id bigint NOT NULL,
                tenant_id bigint NOT NULL,
                payload jsonb,
                CONSTRAINT item_pk PRIMARY KEY (tenant_id, id));
            """,
            """
            CREATE SCHEMA app;
            CREATE TABLE app.item (id bigint, payload jsonb);
            CREATE VIEW app.item_view AS SELECT * FROM app.item;
            CREATE VIEW app.named_view (first_id, second_payload) AS
                SELECT id, payload FROM app.item;
            """,
            """
            CREATE SCHEMA app;
            CREATE TABLE app.item (id bigint, payload jsonb);
            CREATE FUNCTION app.pick(input_id integer DEFAULT 42)
                RETURNS TABLE (id bigint, payload jsonb) AS $$
                SELECT i.id, i.payload FROM app.item i WHERE i.id = input_id;
            $$ LANGUAGE sql;
            CREATE FUNCTION app.count_items() RETURNS bigint AS $$
                SELECT count(*) FROM app.item;
            $$ LANGUAGE sql;
            """,
            """
            CREATE SCHEMA app;
            CREATE TABLE app.parent (id bigint, tenant_id bigint)
                PARTITION BY RANGE (tenant_id);
            CREATE TABLE app.child PARTITION OF app.parent
                FOR VALUES FROM (1) TO (100);
            CREATE TABLE app.legacy_parent (id bigint, payload jsonb);
            CREATE TABLE app.legacy_child (extra text)
                INHERITS (app.legacy_parent);
            """,
            """
            CREATE SCHEMA app;
            CREATE TABLE app.item (id bigint NOT NULL, tenant_id bigint NOT NULL);
            ALTER TABLE app.item
                ADD CONSTRAINT item_pk PRIMARY KEY (tenant_id, id);
            ALTER TABLE app.item ADD CONSTRAINT item_uniq UNIQUE (id);
            """,
            """
            CREATE SCHEMA app;
            CREATE TYPE app.item_pair AS (left_id bigint, right_id bigint);
            CREATE TYPE app.item_state AS ENUM ('new', 'done');
            CREATE DOMAIN app.positive_id AS bigint CHECK (VALUE > 0);
            """,
            """
            CREATE SCHEMA app;
            CREATE TABLE app.item (id bigint, payload jsonb);
            CREATE FUNCTION app.compare_items(app.item, app.item)
                RETURNS boolean AS $$ SELECT true $$ LANGUAGE sql;
            CREATE OPERATOR app.<=> (
                PROCEDURE = app.compare_items,
                LEFTARG = app.item,
                RIGHTARG = app.item);
            CREATE CAST (bigint AS jsonb) WITH INOUT AS ASSIGNMENT;
            """,
            """
            CREATE SCHEMA app;
            CREATE SEQUENCE app.item_id_seq;
            CREATE TABLE app.item (
                id bigint DEFAULT nextval('app.item_id_seq'),
                payload jsonb);
            CREATE INDEX item_payload_idx ON app.item USING gin (payload);
            CREATE FUNCTION app.item_trigger() RETURNS trigger AS $$
                BEGIN RETURN NEW; END;
            $$ LANGUAGE plpgsql;
            CREATE TRIGGER item_touch BEFORE INSERT ON app.item
                FOR EACH ROW EXECUTE PROCEDURE app.item_trigger();
            """,
            """
            CREATE SCHEMA app;
            CREATE TABLE app.item (id bigint, payload jsonb);
            CREATE AGGREGATE app.first_payload (jsonb) (
                SFUNC = app.pick_first,
                STYPE = jsonb);
            CREATE MATERIALIZED VIEW app.item_summary AS
                SELECT id, payload FROM app.item WITH NO DATA;
            """);

    @Test
    void everyShapeFieldIsFilledByTheParseAlone() throws Exception {
        int shaped = 0;
        for (String sql : CORPUS) {
            IDumpLoader loader = loader(sql);
            List<String> parsed;
            List<String> analysed;
            try {
                IDatabase parsedDatabase = loader.load();
                parsed = shapes(MetaUtils.getObjDefinitions(parsedDatabase));
                IDatabase analysedDatabase = loader.loadAndAnalyze();
                analysed = shapes(
                        MetaUtils.getObjDefinitions(analysedDatabase));
            } finally {
                loader.close();
            }

            assertFalse(parsed.isEmpty(), sql);
            assertEquals(analysed, parsed, sql);
            shaped += parsed.size();
        }
        // Every meta kind the shape hasher knows takes part: statements,
        // relations, functions, composite types, constraints, operators and
        // casts, including partitioned and inheriting tables.
        //
        // Nine, not eighteen, schema definitions: upstream 15.3.0 (DBTOOLS-2178)
        // stopped registering the schema of a single-file parse as a definition of
        // its own - in SINGLE mode the schema is taken from the database instead.
        // Each of the nine corpus entries opens with CREATE SCHEMA, so the total
        // dropped by exactly nine. The kinds this test is about are unaffected.
        assertEquals(29, shaped);
    }

    /**
     * The definitions a parse produced are packed exactly like the analysed
     * ones, so the shape of a file may be judged before its analysis.
     */
    @Test
    void theParseAloneAlreadyDescribesEveryDefinitionOfTheFile()
            throws Exception {
        IDumpLoader loader = loader(CORPUS.get(2));
        List<String> parsedNames;
        List<String> analysedNames;
        try {
            parsedNames = names(MetaUtils.getObjDefinitions(loader.load()));
            analysedNames = names(
                    MetaUtils.getObjDefinitions(loader.loadAndAnalyze()));
        } finally {
            loader.close();
        }

        assertEquals(analysedNames, parsedNames);
        assertFalse(parsedNames.isEmpty());
    }

    private static IDumpLoader loader(String sql) {
        var settings = new CoreSettings();
        IDumpLoader loader = new PgDatabaseProvider().getDumpLoader(
                () -> new ByteArrayInputStream(
                        sql.getBytes(StandardCharsets.UTF_8)),
                ABSOLUTE, settings);
        loader.setMode(ParserListenerMode.SINGLE);
        return loader;
    }

    private static List<String> shapes(
            Map<String, List<MetaStatement>> definitions) {
        var hashes = new ArrayList<String>();
        for (MetaStatement statement : statements(definitions)) {
            hashes.add(HexFormat.of().formatHex(DefinitionShapeHasher.hash(
                    PackedDefinition.from(statement, PATH))));
        }
        return hashes.stream().sorted().toList();
    }

    private static List<String> names(
            Map<String, List<MetaStatement>> definitions) {
        return statements(definitions).stream()
                .map(statement -> statement.getObject().getObjectReference()
                        .toString())
                .sorted().toList();
    }

    private static List<MetaStatement> statements(
            Map<String, List<MetaStatement>> definitions) {
        return definitions.values().stream()
                .flatMap(List::stream).toList();
    }
}
