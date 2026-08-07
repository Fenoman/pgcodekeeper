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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.loader.IDumpLoader;
import org.pgcodekeeper.core.database.api.parser.ParserListenerMode;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.settings.CoreSettings;

/**
 * Pins what {@code routine_names_resolved} counts. The number exists to be
 * divided into the {@code ROUTINE} statement count of the lookup summary, so
 * every filter that decides its magnitude is asserted against a real analysis
 * of real SQL rather than against a hand-built location set.
 */
class ResolvedRoutineNamesTest {

    private static final String ABSOLUTE = "/project/SCHEMA/app/changed.sql";

    /**
     * Two overloads called under two argument lists are one name, and a
     * routine the file defines but never calls is not demand at all.
     *
     * <p>The fixture is arranged so that both statements bite: dropping the
     * bare-name collapse would count {@code pick(integer)} and
     * {@code pick(text)} apart, and dropping the definition filter would count
     * {@code never_called} - which no lookup ever asked for - alongside them.
     */
    @Test
    void overloadsCollapseToOneNameAndDefinitionsAreNotDemand()
            throws Exception {
        assertEquals(2, PgDbParser.resolvedRoutineNames(analyze("""
                CREATE SCHEMA app;
                CREATE TABLE app.item (id bigint, payload jsonb);

                CREATE FUNCTION app.pick(a integer) RETURNS integer AS
                    $$ SELECT a; $$ LANGUAGE sql;
                CREATE FUNCTION app.pick(a text) RETURNS text AS
                    $$ SELECT a; $$ LANGUAGE sql;
                CREATE FUNCTION app.other() RETURNS void AS
                    $$ BEGIN END; $$ LANGUAGE plpgsql;
                CREATE FUNCTION app.never_called() RETURNS void AS
                    $$ BEGIN END; $$ LANGUAGE plpgsql;

                CREATE VIEW app.uses AS
                    SELECT app.pick(1) AS a,
                           app.pick('x'::text) AS b,
                           app.other() AS c
                    FROM app.item;
                """)));
    }

    /**
     * A routine is not only a {@code FUNCTION}. A called procedure and an
     * aggregate used in a view are recorded under their own object types, and
     * both are demand the index has to answer.
     *
     * <p>The same fixture holds a table, a column and a schema reference,
     * which the analysis records next to the routines: counting references
     * without filtering their type would count those too.
     */
    @Test
    void calledProceduresAndAggregatesCountAsRoutines() throws Exception {
        assertEquals(3, PgDbParser.resolvedRoutineNames(analyze("""
                CREATE SCHEMA app;
                CREATE TABLE app.item (id bigint);
                CREATE PROCEDURE app.touch() AS
                    $$ BEGIN END; $$ LANGUAGE plpgsql;
                CREATE FUNCTION app.pick_first(jsonb, jsonb) RETURNS jsonb AS
                    $$ SELECT $1; $$ LANGUAGE sql;
                CREATE AGGREGATE app.first_id (bigint) (
                    SFUNC = app.pick_first, STYPE = jsonb);
                CREATE FUNCTION app.caller() RETURNS void AS $$
                    BEGIN CALL app.touch(); END;
                $$ LANGUAGE plpgsql;
                CREATE VIEW app.rollup AS
                    SELECT app.first_id(id) AS a FROM app.item;
                """)));
    }

    /**
     * The system-schema guard, pinned on a hand-built database because no
     * analysis produces its input.
     *
     * <p>This is the one filter of the count that never fires in production:
     * the analyser refuses to record a dependency on a system schema at all
     * ({@code AbstractExpr.addDependency}), so a {@code pg_catalog.f(...)}
     * call leaves no routine reference to drop - verified by analysing
     * {@code CREATE OPERATOR ... PROCEDURE = pg_catalog.int4pl} and
     * {@code CREATE CAST ... WITH FUNCTION pg_catalog.int8}, neither of which
     * records one. The guard is therefore defensive, and this test is what
     * says so: were the analyser's own guard to move, system routines would
     * otherwise start counting as project demand and the number would drift
     * without anything failing.
     */
    @Test
    void systemSchemaRoutinesAreNotProjectDemand() {
        var database = new PgDatabase();
        database.addReference(ABSOLUTE,
                reference("pg_catalog", "length(text)", DbObjType.FUNCTION));
        database.addReference(ABSOLUTE,
                reference("information_schema", "_pg_expandarray(anyarray)",
                        DbObjType.FUNCTION));
        database.addReference(ABSOLUTE,
                reference("app", "kept(integer)", DbObjType.FUNCTION));

        assertEquals(1, PgDbParser.resolvedRoutineNames(database));
    }

    private static ObjectLocation reference(String schema, String signature,
            DbObjType type) {
        return new ObjectLocation.Builder()
                .setFilePath(ABSOLUTE)
                .setReference(new ObjectReference(schema, signature, type))
                .setLocationType(ObjectLocation.LocationType.REFERENCE)
                .build();
    }

    private static IDatabase analyze(String sql) throws Exception {
        var settings = new CoreSettings();
        IDumpLoader loader = new PgDatabaseProvider().getDumpLoader(
                () -> new ByteArrayInputStream(
                        sql.getBytes(StandardCharsets.UTF_8)),
                ABSOLUTE, settings);
        loader.setMode(ParserListenerMode.SINGLE);
        try {
            return loader.loadAndAnalyze();
        } finally {
            loader.close();
        }
    }
}
