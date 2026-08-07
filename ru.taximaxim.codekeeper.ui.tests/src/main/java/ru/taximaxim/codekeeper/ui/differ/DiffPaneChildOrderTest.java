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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IStatement;
import org.pgcodekeeper.core.model.difftree.TreeElement;
import org.pgcodekeeper.core.model.difftree.TreeElement.DiffSide;

class DiffPaneChildOrderTest {

    private static final long SHUFFLE_SEED = 20260726L;

    private final Locale defaultLocale = Locale.getDefault();

    @AfterEach
    void restoreDefaultLocale() {
        Locale.setDefault(defaultLocale);
    }

    @Test
    void catalogAndFileOrderCollapseToTheSameChildSequence() {
        List<TreeElement> catalogOrder = List.of(
                element("fd_debts_transfers_pkey", DbObjType.CONSTRAINT),
                element("fd_debts_transfers_doc_fkey", DbObjType.CONSTRAINT),
                element("fd_debts_transfers_state_idx", DbObjType.INDEX),
                element("fd_debts_transfers_doc_idx", DbObjType.INDEX),
                element("fd_debts_transfers_audit", DbObjType.TRIGGER));
        List<TreeElement> fileOrder = List.of(
                element("fd_debts_transfers_doc_idx", DbObjType.INDEX),
                element("fd_debts_transfers_state_idx", DbObjType.INDEX),
                element("fd_debts_transfers_audit", DbObjType.TRIGGER),
                element("fd_debts_transfers_doc_fkey", DbObjType.CONSTRAINT),
                element("fd_debts_transfers_pkey", DbObjType.CONSTRAINT));

        assertEquals(List.of(
                "INDEX fd_debts_transfers_doc_idx",
                "INDEX fd_debts_transfers_state_idx",
                "CONSTRAINT fd_debts_transfers_doc_fkey",
                "CONSTRAINT fd_debts_transfers_pkey",
                "TRIGGER fd_debts_transfers_audit"),
                sortedLabels(catalogOrder));
        assertEquals(sortedLabels(catalogOrder), sortedLabels(fileOrder));
    }

    @Test
    void typesFollowDeclarationOrderInsteadOfEnumHashCode() {
        List<TreeElement> shuffled = new ArrayList<>();
        for (DbObjType type : DbObjType.values()) {
            shuffled.add(element("same_name", type));
        }
        Collections.shuffle(shuffled, new Random(SHUFFLE_SEED));

        List<DbObjType> sorted = shuffled.stream()
                .sorted(DiffPaneChildOrder.TREE_ELEMENT_ORDER)
                .map(TreeElement::getType)
                .toList();

        assertEquals(List.of(DbObjType.values()), sorted);
    }

    @Test
    void repeatedPermutationsOfTheSameChildrenProduceOneOrder() {
        List<TreeElement> children = new ArrayList<>(List.of(
                element("b_idx", DbObjType.INDEX),
                element("a_idx", DbObjType.INDEX),
                element("c_check", DbObjType.CONSTRAINT),
                element("a_check", DbObjType.CONSTRAINT)));
        List<String> expected = sortedLabels(children);

        Random random = new Random(SHUFFLE_SEED);
        for (int attempt = 0; attempt < 50; attempt++) {
            Collections.shuffle(children, random);
            assertEquals(expected, sortedLabels(children));
        }
    }

    /**
     * U+0130 is the Turkish dotted capital I, U+0131 is the dotless small i. Case mapping and
     * collation of these letters differ between locales, code point order does not.
     */
    @Test
    void nameOrderIsIndependentOfTheDefaultLocale() {
        List<TreeElement> children = List.of(
                element("id_index", DbObjType.INDEX),
                element("ıd_index", DbObjType.INDEX),
                element("ID_index", DbObjType.INDEX),
                element("İD_index", DbObjType.INDEX));
        List<String> expected = List.of(
                "INDEX ID_index",
                "INDEX id_index",
                "INDEX İD_index",
                "INDEX ıd_index");

        for (Locale locale : List.of(Locale.ROOT, Locale.of("tr", "TR"), Locale.of("ru", "RU"))) {
            Locale.setDefault(locale);
            assertEquals(expected, sortedLabels(children), "broken by locale " + locale);
        }
    }

    @Test
    void supplementaryCharactersSortAfterEveryBasicPlaneCharacter() {
        String supplementary = new String(Character.toChars(0x1F389));
        String basicPlane = "�";

        assertTrue(DiffPaneChildOrder.compareByCodePoints(supplementary, basicPlane) > 0);
        assertTrue(supplementary.compareTo(basicPlane) < 0, "String.compareTo orders by UTF-16 unit");
        assertEquals(0, DiffPaneChildOrder.compareByCodePoints(supplementary, supplementary));
        assertTrue(DiffPaneChildOrder.compareByCodePoints("idx", "idx_2") < 0);
    }

    @Test
    void missingNamesAndTypesAreOrderedWithoutFailure() {
        List<TreeElement> children = Arrays.asList(
                element("a_idx", DbObjType.INDEX),
                element(null, DbObjType.INDEX),
                new TreeElement(null, null, DiffSide.BOTH));

        List<TreeElement> sorted = children.stream()
                .sorted(DiffPaneChildOrder.TREE_ELEMENT_ORDER)
                .toList();

        assertEquals(Arrays.asList(null, DbObjType.INDEX, DbObjType.INDEX),
                sorted.stream().map(TreeElement::getType).toList());
        assertEquals(List.of("a_idx"),
                sorted.stream().map(TreeElement::getName).filter(Objects::nonNull).toList());
    }

    @Test
    void loadedStatementsUseTheSameOrderAsTreeElements() {
        List<IStatement> statements = List.of(
                statement("fd_debts_transfers_pkey", DbObjType.CONSTRAINT),
                statement("fd_debts_transfers_state_idx", DbObjType.INDEX),
                statement("fd_debts_transfers_doc_idx", DbObjType.INDEX));

        List<String> sorted = statements.stream()
                .sorted(DiffPaneChildOrder.STATEMENT_ORDER)
                .map(st -> st.getStatementType() + " " + st.getName())
                .toList();

        assertEquals(List.of(
                "INDEX fd_debts_transfers_doc_idx",
                "INDEX fd_debts_transfers_state_idx",
                "CONSTRAINT fd_debts_transfers_pkey"),
                sorted);
    }

    private static List<String> sortedLabels(List<TreeElement> children) {
        return children.stream()
                .sorted(DiffPaneChildOrder.TREE_ELEMENT_ORDER)
                .map(child -> child.getType() + " " + child.getName())
                .toList();
    }

    private static TreeElement element(String name, DbObjType type) {
        return new TreeElement(name, type, DiffSide.BOTH);
    }

    private static IStatement statement(String name, DbObjType type) {
        IStatement statement = mock(IStatement.class);
        when(statement.getName()).thenReturn(name);
        when(statement.getStatementType()).thenReturn(type);
        return statement;
    }
}
