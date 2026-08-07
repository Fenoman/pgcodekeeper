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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IStatement;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.ignorelist.IIgnoreList;
import org.pgcodekeeper.core.ignorelist.IgnoreList;
import org.pgcodekeeper.core.model.difftree.ChildVisibility;
import org.pgcodekeeper.core.model.difftree.TreeElement;
import org.pgcodekeeper.core.model.difftree.TreeElement.DiffSide;
import org.pgcodekeeper.core.settings.ISettings;

import ru.taximaxim.codekeeper.ui.DatabaseType;
import ru.taximaxim.codekeeper.ui.UIConsts;
import ru.taximaxim.codekeeper.ui.UIConsts.PREF;
import ru.taximaxim.codekeeper.ui.settings.UISettings;

/**
 * The full code of a container is rendered from the loaded model, which holds
 * every object it was loaded with - including the ones the project declares it
 * does not manage. Those must not reach the comparison pane: the difference tree
 * no longer offers them, and no migration script will ever carry them, so a side
 * that happens to have one would be shown differing over nothing.
 */
class DiffPaneHiddenChildrenTest {

    private static final String TABLE_WITH_AUDIT = """
            CREATE TABLE public.sd_close_periods (
                id integer,
                c_host_name text
            );

            CREATE FUNCTION public.audit_fn() RETURNS trigger
                LANGUAGE plpgsql
                AS $$ BEGIN RETURN NEW; END; $$;

            CREATE INDEX sd_close_periods_host_idx ON public.sd_close_periods (c_host_name);

            CREATE TRIGGER t_business_rule
                BEFORE INSERT ON public.sd_close_periods
                FOR EACH ROW
                EXECUTE PROCEDURE public.audit_fn();

            CREATE TRIGGER t_3stat_after_99_d_audit_data
                AFTER DELETE ON public.sd_close_periods
                FOR EACH ROW
                EXECUTE PROCEDURE public.audit_fn();

            CREATE TRIGGER t_0stat_before_00_iud_audit_transaction
                BEFORE INSERT ON public.sd_close_periods
                FOR EACH ROW
                EXECUTE PROCEDURE public.audit_fn();""";

    private static final String AUDIT_TRIGGERS_HIDDEN = """
            SHOW ALL
            HIDE NONE t_0stat_before_00_iud_audit_transaction type=TRIGGER
            HIDE NONE t_3stat_after_99_d_audit_data type=TRIGGER""";

    private static final String TABLE = "sd_close_periods";
    private static final String SCHEMA = "public";
    private static final String DATABASE_NAME = "omnix";

    private static final String BUSINESS_TRIGGER = "t_business_rule";
    private static final String AUDIT_TRIGGER = "t_0stat_before_00_iud_audit_transaction";
    private static final String OTHER_AUDIT_TRIGGER = "t_3stat_after_99_d_audit_data";
    private static final String INDEX = "sd_close_periods_host_idx";

    @TempDir
    Path tempDir;

    private ISettings settings;
    private IStatement table;
    private TreeElement container;

    @BeforeEach
    void loadFixture() throws IOException, InterruptedException {
        settings = DiffPaneViewer.applyDisplayPolicy(
                new UISettings(null, Map.of(PREF.IGNORE_COLUMN_ORDER, true), DatabaseType.PG));

        table = DatabaseType.PG.getDatabaseProvider()
                .getDumpLoader(() -> new ByteArrayInputStream(TABLE_WITH_AUDIT.getBytes(StandardCharsets.UTF_8)),
                        "test/DiffPaneHiddenChildrenTest", settings)
                .load()
                .getStatement(new ObjectReference(SCHEMA, TABLE, DbObjType.TABLE));
        assertNotNull(table, "fixture must offer the table");

        container = containerNode();
    }

    /**
     * Without a list the rendering is the one that was there before hiding was
     * introduced: every child, in display order.
     */
    @Test
    void withoutAnIgnoreListEveryChildIsRendered() {
        for (ChildVisibility visibility : List.of(
                ChildVisibility.of(null, container, DATABASE_NAME),
                ChildVisibility.of(new IgnoreList(), container, DATABASE_NAME))) {
            assertEquals(List.of(INDEX, AUDIT_TRIGGER, OTHER_AUDIT_TRIGGER, BUSINESS_TRIGGER),
                    renderedChildren(visibility));
        }
    }

    @Test
    void hiddenChildrenAreNotRenderedWhileTheOthersAre() throws IOException {
        assertEquals(List.of(INDEX, BUSINESS_TRIGGER), renderedChildren(visibility(AUDIT_TRIGGERS_HIDDEN)));
    }

    /**
     * Hiding decides on the object, not on the side it happens to be on: the same
     * container is rendered for the database and for the project, and a hidden
     * child is gone from both renderings.
     */
    @Test
    void aHiddenChildIsGoneFromEveryRendering() throws IOException {
        ChildVisibility visibility = visibility(AUDIT_TRIGGERS_HIDDEN);

        for (boolean formatted : List.of(false, true)) {
            String rendered = DiffPaneViewer.appendVisibleChildren(
                    table.getSQL(formatted, settings), table, visibility, formatted, settings);

            assertFalse(rendered.contains(AUDIT_TRIGGER), rendered);
            assertFalse(rendered.contains(OTHER_AUDIT_TRIGGER), rendered);
            assertTrue(rendered.contains(BUSINESS_TRIGGER), rendered);
        }
    }

    /**
     * What is left keeps the reproducible display order, so hiding cannot turn
     * into ordering noise of its own.
     */
    @Test
    void theOrderOfWhatIsLeftIsTheDisplayOrder() throws IOException {
        assertEquals(List.of(INDEX, AUDIT_TRIGGER, BUSINESS_TRIGGER),
                renderedChildren(visibility("""
                        SHOW ALL
                        HIDE NONE t_3stat_after_99_d_audit_data type=TRIGGER""")));
    }

    /**
     * Hiding only takes children away: the code of the container itself, and the
     * code of every child that stays, is rendered exactly as before.
     */
    @Test
    void hidingOnlyTakesChildrenAway() throws IOException {
        String tableSql = table.getSQL(false, settings);

        assertEquals(tableSql + separated(INDEX) + separated(BUSINESS_TRIGGER),
                DiffPaneViewer.appendVisibleChildren(tableSql, table, visibility(AUDIT_TRIGGERS_HIDDEN),
                        false, settings));
    }

    private String separated(String childName) {
        IStatement child = table.getChildren()
                .filter(st -> childName.equals(st.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("fixture must offer the child " + childName));
        return UIConsts._NL + UIConsts._NL + child.getSQL(false, settings);
    }

    /**
     * A rule that names the child through its container proves the pane hides by
     * the same name the difference tree hides by.
     */
    @Test
    void qualifiedRulesAddressTheChildThroughItsContainer() throws IOException {
        assertEquals(List.of(INDEX, OTHER_AUDIT_TRIGGER, BUSINESS_TRIGGER),
                renderedChildren(visibility("""
                        SHOW ALL
                        HIDE QUALIFIED "public.sd_close_periods.t_0stat_before_00_iud_audit_transaction" \
                        type=TRIGGER""")));
    }

    /**
     * The database name of the shown comparison decides a scoped rule, exactly as
     * it decides the object list above the pane.
     */
    @Test
    void databaseScopedRulesFollowTheComparedDatabase() throws IOException {
        IgnoreList list = parse("""
                SHOW ALL
                HIDE NONE t_0stat_before_00_iud_audit_transaction db=omnix type=TRIGGER""");

        assertEquals(List.of(INDEX, OTHER_AUDIT_TRIGGER, BUSINESS_TRIGGER),
                renderedChildren(ChildVisibility.of(list, container, DATABASE_NAME)));
        assertEquals(List.of(INDEX, AUDIT_TRIGGER, OTHER_AUDIT_TRIGGER, BUSINESS_TRIGGER),
                renderedChildren(ChildVisibility.of(list, container, "another_database")));
    }

    /**
     * Names of the children the pane renders, in the order it renders them.
     */
    private List<String> renderedChildren(ChildVisibility visibility) {
        String rendered = DiffPaneViewer.appendVisibleChildren(
                table.getSQL(false, settings), table, visibility, false, settings);

        return table.getChildren()
                .map(IStatement::getName)
                .filter(rendered::contains)
                .sorted((left, right) -> Integer.compare(rendered.indexOf(left), rendered.indexOf(right)))
                .toList();
    }

    private ChildVisibility visibility(String rules) throws IOException {
        return ChildVisibility.of(parse(rules), container, DATABASE_NAME);
    }

    private IgnoreList parse(String rules) throws IOException {
        Path file = Files.createTempFile(tempDir, "rules", ".pgcodekeeperignore");
        Files.writeString(file, rules, StandardCharsets.UTF_8);
        return IIgnoreList.parseIgnoreList(file, new IgnoreList());
    }

    /**
     * The node of the container in a difference tree, shaped exactly as
     * {@code DiffTree} shapes it: the pane always holds one.
     */
    private static TreeElement containerNode() {
        TreeElement database = new TreeElement("Database", DbObjType.DATABASE, DiffSide.BOTH);
        TreeElement schema = new TreeElement(SCHEMA, DbObjType.SCHEMA, DiffSide.BOTH);
        database.addChild(schema);
        TreeElement table = new TreeElement(TABLE, DbObjType.TABLE, DiffSide.BOTH);
        schema.addChild(table);
        return table;
    }
}
