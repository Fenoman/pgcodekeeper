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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IStatement;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.settings.ISettings;

import ru.taximaxim.codekeeper.ui.DatabaseType;
import ru.taximaxim.codekeeper.ui.UIConsts.PREF;
import ru.taximaxim.codekeeper.ui.settings.UISettings;

/**
 * The comparison pane renders each object with settings of its own, and those
 * are the only ones allowed to carry a display-only relaxation.
 */
class DiffPaneDisplaySettingsTest {

    private static final String COLUMNS_IN_ONE_ORDER = """
            CREATE TABLE public.t (
                c_zebra text,
                c_alpha text,
                c_middle text
            );""";

    private static final String COLUMNS_IN_ANOTHER_ORDER = """
            CREATE TABLE public.t (
                c_middle text,
                c_zebra text,
                c_alpha text
            );""";

    /**
     * With the order of the columns significant, a moved column is a real
     * difference and the pane must show it.
     */
    @Test
    void theRenderedColumnOrderIsRelaxedOnlyWhileTheStoredOrderIsIgnored() {
        assertTrue(displaySettings(true).isSortColumnsForDisplay());
        assertFalse(displaySettings(false).isSortColumnsForDisplay());
    }

    /**
     * The point of the whole thing: two states of one table, stored in the order
     * each of them was read in, meet in one order on the screen.
     */
    @Test
    void bothStoredOrdersOfOneTableAreRenderedAlike() throws IOException, InterruptedException {
        ISettings display = displaySettings(true);

        assertArrayEquals(render(COLUMNS_IN_ONE_ORDER, display), render(COLUMNS_IN_ANOTHER_ORDER, display),
                "the two sides must meet in one order");

        ISettings stored = displaySettings(false);
        assertNotEquals(new String(render(COLUMNS_IN_ONE_ORDER, stored), StandardCharsets.UTF_8),
                new String(render(COLUMNS_IN_ANOTHER_ORDER, stored), StandardCharsets.UTF_8),
                "and the fixture must really store them in two orders");
    }

    @Test
    void theRenderedOrderIsTheNameOrderOfTheColumns() throws IOException, InterruptedException {
        String rendered = new String(render(COLUMNS_IN_ONE_ORDER, displaySettings(true)), StandardCharsets.UTF_8);

        assertTrue(rendered.indexOf("c_alpha") < rendered.indexOf("c_middle"), rendered);
        assertTrue(rendered.indexOf("c_middle") < rendered.indexOf("c_zebra"), rendered);
    }

    private static UISettings displaySettings(boolean ignoreColumnOrder) {
        return DiffPaneViewer.applyDisplayPolicy(
                new UISettings(null, Map.of(PREF.IGNORE_COLUMN_ORDER, ignoreColumnOrder), DatabaseType.PG));
    }

    private static byte[] render(String sql, ISettings settings) throws IOException, InterruptedException {
        IStatement table = DatabaseType.PG.getDatabaseProvider()
                .getDumpLoader(() -> new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8)),
                        "test/DiffPaneDisplaySettingsTest", settings)
                .load()
                .getStatement(new ObjectReference("public", "t", DbObjType.TABLE));
        return table.getSQL(false, settings).getBytes(StandardCharsets.UTF_8);
    }
}
