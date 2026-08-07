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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPreferenceConstants;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.navigator.CommonNavigator;
import org.eclipse.ui.navigator.CommonViewer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ru.taximaxim.codekeeper.ui.Activator;
import ru.taximaxim.codekeeper.ui.UIConsts.PREF;
import ru.taximaxim.codekeeper.ui.localizations.Messages;
import ru.taximaxim.codekeeper.ui.utils.ProjectUtils;

/**
 * Sections shown under their table, in the real Project Explorer.
 *
 * <p>Everything here is read back off the SWT items of the real view, because
 * the claim being made is about what a person sees: which rows a folder has,
 * what hangs under a table, and where "Show In" lands. A model that agrees with
 * itself proves none of that - the two halves of this feature live in different
 * subsystems (a viewer filter and a content extension), and the failure worth
 * fearing is precisely that they disagree and a file ends up in neither
 * place.</p>
 *
 * <p>The section map is published straight into {@link PartitionIndex} through
 * its package-private seam. Building it for real needs a parsed project index,
 * which is a different subsystem with its own build cycle;
 * {@link PartitionLinkReaderTest} is where the map's own truthfulness is
 * argued.</p>
 */
class PartitionNestingStandTest {

    private static final String PROJECT_EXPLORER = "org.eclipse.ui.navigator.ProjectExplorer"; //$NON-NLS-1$

    private static final String PARENT = "tmp.orders"; //$NON-NLS-1$
    private static final String TABLE_FILE = "orders.sql"; //$NON-NLS-1$

    private IPreferenceStore store;
    private boolean wasNesting;
    private boolean wasOrdering;

    @BeforeEach
    void rememberTheSwitches() {
        store = Activator.getDefault().getPreferenceStore();
        wasNesting = store.getBoolean(PREF.NEST_PARTITIONS_UNDER_PARENT);
        wasOrdering = store.getBoolean(PREF.GROUP_PARTITIONS_IN_TREE);
    }

    @AfterEach
    void putEverythingBack() {
        store.setValue(PREF.NEST_PARTITIONS_UNDER_PARENT, wasNesting);
        store.setValue(PREF.GROUP_PARTITIONS_IN_TREE, wasOrdering);
        PartitionIndex.getInstance().clear();
    }

    /**
     * The picture from the issue. The sections are gone from the folder and
     * hang under their own table instead - and the tables that are left are in
     * the order they always were.
     */
    @Test
    void theSectionsLeaveTheFolderAndAppearUnderTheirTable(@TempDir Path temp)
            throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(temp, monitor);
        try {
            IFolder folder = createTableFolder(project, monitor,
                    "archive.sql", TABLE_FILE, "orders_1.sql", //$NON-NLS-1$ //$NON-NLS-2$
                    "orders_2.sql", "payments.sql"); //$NON-NLS-1$ //$NON-NLS-2$
            publish(folder, Map.of("orders_1.sql", PARENT, //$NON-NLS-1$
                    "orders_2.sql", PARENT), //$NON-NLS-1$
                    Map.of("orders_1.sql", TABLE_FILE, //$NON-NLS-1$
                            "orders_2.sql", TABLE_FILE)); //$NON-NLS-1$
            store.setValue(PREF.NEST_PARTITIONS_UNDER_PARENT, true);
            store.setValue(PREF.GROUP_PARTITIONS_IN_TREE, false);

            assertEquals(List.of(
                    "archive.sql", //$NON-NLS-1$
                    TABLE_FILE,
                    TABLE_FILE + " > " + sections(2), //$NON-NLS-1$
                    TABLE_FILE + " > " + sections(2) + " > orders_1.sql", //$NON-NLS-1$ //$NON-NLS-2$
                    TABLE_FILE + " > " + sections(2) + " > orders_2.sql", //$NON-NLS-1$ //$NON-NLS-2$
                    "payments.sql"), //$NON-NLS-1$
                    listing(folder),
                    "the sections did not move under their table");
        } finally {
            cleanUp(project, monitor);
        }
    }

    /**
     * The rule the whole approach rests on. This section really is a section -
     * of {@code other.orders}, which lives in another schema and therefore in
     * another folder, so there is no node here to put it under. It has to stay
     * exactly where it is. Hiding it would take the object out of the user
     * interface with nowhere to find it again, which is the failure that ruled
     * out rearranging the files in the first place.
     */
    @Test
    void aSectionWhoseTableIsNotInTheTreeStaysWhereItIs(@TempDir Path temp)
            throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(temp, monitor);
        try {
            IFolder folder = createTableFolder(project, monitor,
                    "orders_1.sql", "payments.sql"); //$NON-NLS-1$ //$NON-NLS-2$
            // a confirmed section, and no file in this folder defines its table
            publish(folder, Map.of("orders_1.sql", "other.orders"), //$NON-NLS-1$ //$NON-NLS-2$
                    Map.of());
            store.setValue(PREF.NEST_PARTITIONS_UNDER_PARENT, true);
            store.setValue(PREF.GROUP_PARTITIONS_IN_TREE, false);

            assertEquals(List.of("orders_1.sql", "payments.sql"), //$NON-NLS-1$ //$NON-NLS-2$
                    listing(folder),
                    "a section without a parent node in this tree disappeared");
        } finally {
            cleanUp(project, monitor);
        }
    }

    /**
     * The project this was reported from, with both switches on and an index
     * that says nothing.
     *
     * <p>Its links are all ones the text claims: it receives its changes from a
     * database, so no background index is built, and the schema of this folder
     * is excluded from the index besides. The ordering has to work anyway - it
     * only moves a row - and nothing may leave the folder, because a claim
     * nobody confirmed is not enough to take a file out of the place it lives
     * in. The parent file is deliberately supplied here: this asserts that an
     * unconfirmed section is not nested even when there is somewhere obvious to
     * nest it, and not merely that nothing was found to nest it under.</p>
     */
    @Test
    void anIndexThatSaysNothingOrdersTheFolderAndHidesNothing(@TempDir Path temp)
            throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(temp, monitor);
        try {
            IFolder folder = createTableFolder(project, monitor,
                    "archive.sql", TABLE_FILE, "orders_1.sql", //$NON-NLS-1$ //$NON-NLS-2$
                    "orders_2.sql", "payments.sql"); //$NON-NLS-1$ //$NON-NLS-2$
            publish(folder, Map.of("orders_1.sql", PARENT, //$NON-NLS-1$
                    "orders_2.sql", PARENT), //$NON-NLS-1$
                    Map.of("orders_1.sql", TABLE_FILE, //$NON-NLS-1$
                            "orders_2.sql", TABLE_FILE), //$NON-NLS-1$
                    Set.of());
            store.setValue(PREF.NEST_PARTITIONS_UNDER_PARENT, true);
            store.setValue(PREF.GROUP_PARTITIONS_IN_TREE, true);

            assertEquals(List.of("archive.sql", TABLE_FILE, "payments.sql", //$NON-NLS-1$ //$NON-NLS-2$
                    "orders_1.sql", "orders_2.sql"), //$NON-NLS-1$ //$NON-NLS-2$
                    listing(folder),
                    "an index that says nothing did not give back the ordering");
        } finally {
            cleanUp(project, monitor);
        }
    }

    /**
     * The rule the asymmetry is, at the only place it can be seen: one folder,
     * two sections of the same table, one link confirmed and one not.
     *
     * <p>The confirmed one goes under the table. The other stays in the folder
     * - not because there is nowhere to put it, since the confirmed one just
     * went there, but because nothing but the text says it belongs there at
     * all. This is the assertion that fails if nesting ever starts trusting an
     * unconfirmed link, and it is also the one that fails if nesting stops
     * working; a folder where nothing is confirmed cannot tell those two
     * apart.</p>
     */
    @Test
    void onlyAConfirmedSectionIsTakenOutOfTheFolder(@TempDir Path temp)
            throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(temp, monitor);
        try {
            IFolder folder = createTableFolder(project, monitor,
                    TABLE_FILE, "orders_1.sql", "orders_2.sql", //$NON-NLS-1$ //$NON-NLS-2$
                    "payments.sql"); //$NON-NLS-1$
            publish(folder, Map.of("orders_1.sql", PARENT, //$NON-NLS-1$
                    "orders_2.sql", PARENT), //$NON-NLS-1$
                    Map.of("orders_1.sql", TABLE_FILE, //$NON-NLS-1$
                            "orders_2.sql", TABLE_FILE), //$NON-NLS-1$
                    Set.of("orders_1.sql")); //$NON-NLS-1$
            store.setValue(PREF.NEST_PARTITIONS_UNDER_PARENT, true);
            store.setValue(PREF.GROUP_PARTITIONS_IN_TREE, false);

            assertEquals(List.of(
                    "orders_2.sql", //$NON-NLS-1$
                    TABLE_FILE,
                    TABLE_FILE + " > " + sections(1), //$NON-NLS-1$
                    TABLE_FILE + " > " + sections(1) + " > orders_1.sql", //$NON-NLS-1$ //$NON-NLS-2$
                    "payments.sql"), //$NON-NLS-1$
                    listing(folder),
                    "nesting did not separate a confirmed link from a claimed one");
        } finally {
            cleanUp(project, monitor);
        }
    }

    /**
     * The measured complaint, in miniature and at the shape it really has:
     * a hundred sections of one table. The folder has to lose all hundred rows
     * and the table has to gain exactly one child - not a hundred children next
     * to the statements of its own text, which is what hanging them on the file
     * directly would have produced.
     */
    @Test
    void aFamilyOfAHundredCostsTheTableOneRow(@TempDir Path temp)
            throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(temp, monitor);
        try {
            List<String> names = new ArrayList<>(List.of(TABLE_FILE,
                    "archive.sql", "payments.sql")); //$NON-NLS-1$ //$NON-NLS-2$
            Map<String, String> parents = new LinkedHashMap<>();
            Map<String, String> under = new LinkedHashMap<>();
            for (int i = 0; i < 100; i++) {
                String name = "orders_%02d.sql".formatted(i); //$NON-NLS-1$
                names.add(name);
                parents.put(name, PARENT);
                under.put(name, TABLE_FILE);
            }
            IFolder folder = createTableFolder(project, monitor,
                    names.toArray(String[]::new));
            publish(folder, parents, under);
            store.setValue(PREF.NEST_PARTITIONS_UNDER_PARENT, true);

            assertEquals(List.of("archive.sql", TABLE_FILE, "payments.sql"), //$NON-NLS-1$ //$NON-NLS-2$
                    rowsUnder(folder),
                    "the folder did not come down to its tables");
            assertEquals(List.of(sections(100)),
                    childrenOfTheTable(folder),
                    "the table did not gain exactly one child");
        } finally {
            cleanUp(project, monitor);
        }
    }

    /**
     * The switch is off by default, and off has to mean step one exactly -
     * every file back in its folder, ordered with the sections below the
     * tables, and not one grouping node anywhere.
     */
    @Test
    void nestingOffGivesBackExactlyTheOrderingOfStepOne(@TempDir Path temp)
            throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(temp, monitor);
        try {
            IFolder folder = createTableFolder(project, monitor,
                    "archive.sql", TABLE_FILE, "orders_1.sql", //$NON-NLS-1$ //$NON-NLS-2$
                    "orders_2.sql", "payments.sql"); //$NON-NLS-1$ //$NON-NLS-2$
            publish(folder, Map.of("orders_1.sql", PARENT, //$NON-NLS-1$
                    "orders_2.sql", PARENT), //$NON-NLS-1$
                    Map.of("orders_1.sql", TABLE_FILE, //$NON-NLS-1$
                            "orders_2.sql", TABLE_FILE)); //$NON-NLS-1$
            store.setValue(PREF.NEST_PARTITIONS_UNDER_PARENT, false);
            store.setValue(PREF.GROUP_PARTITIONS_IN_TREE, true);

            assertEquals(List.of("archive.sql", TABLE_FILE, "payments.sql", //$NON-NLS-1$ //$NON-NLS-2$
                    "orders_1.sql", "orders_2.sql"), //$NON-NLS-1$ //$NON-NLS-2$
                    listing(folder),
                    "the switch off was not step one");
        } finally {
            cleanUp(project, monitor);
        }
    }

    /**
     * Both switches on. The ordering has nothing left to order once the
     * sections are out of the folder, so the folder has to come out in the
     * platform's own order and not in some third thing.
     *
     * <p>The expectation is deliberately not the code-point order:
     * {@code orders_9.sql} sorts before {@code orders.sql} because the platform
     * weighs {@code _} below {@code .} through {@code Collator.getInstance()}.
     * Here {@code orders_9.sql} is a plain table, not a section, so it must
     * stay in the folder and must stay in front - which is what makes this
     * assertion able to tell "the platform's order" from "the sections ranked
     * last".</p>
     */
    @Test
    void bothSwitchesOnLeaveTheFolderInThePlatformsOrder(@TempDir Path temp)
            throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(temp, monitor);
        try {
            IFolder folder = createTableFolder(project, monitor,
                    TABLE_FILE, "orders_1.sql", "orders_9.sql"); //$NON-NLS-1$ //$NON-NLS-2$
            publish(folder, Map.of("orders_1.sql", PARENT), //$NON-NLS-1$
                    Map.of("orders_1.sql", TABLE_FILE)); //$NON-NLS-1$
            store.setValue(PREF.NEST_PARTITIONS_UNDER_PARENT, true);
            store.setValue(PREF.GROUP_PARTITIONS_IN_TREE, true);

            assertEquals(List.of("orders_9.sql", TABLE_FILE), //$NON-NLS-1$
                    rowsUnder(folder),
                    "the two switches did not agree about the folder");
        } finally {
            cleanUp(project, monitor);
        }
    }

    /**
     * "Show In" and "Link with Editor" ask the tree to reveal a resource, and
     * the tree gets there by walking upwards. A section taken out of its folder
     * has to be reachable through its new place or the reveal silently finds
     * nothing - the quiet failure this whole class exists to catch.
     */
    @Test
    void showInFindsTheSectionUnderItsTable(@TempDir Path temp)
            throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(temp, monitor);
        try {
            IFolder folder = createTableFolder(project, monitor,
                    TABLE_FILE, "orders_1.sql"); //$NON-NLS-1$
            publish(folder, Map.of("orders_1.sql", PARENT), //$NON-NLS-1$
                    Map.of("orders_1.sql", TABLE_FILE)); //$NON-NLS-1$
            store.setValue(PREF.NEST_PARTITIONS_UNDER_PARENT, true);

            assertEquals(
                    List.of("orders_1.sql", sections(1), TABLE_FILE, "TABLE"), //$NON-NLS-1$ //$NON-NLS-2$
                    revealChain(folder.getFile("orders_1.sql"), 4), //$NON-NLS-1$
                    "the tree could not reveal a section under its table");
        } finally {
            cleanUp(project, monitor);
        }
    }

    /**
     * The other half of the same question, and the one that is easy to get
     * wrong by being helpful. With the switch off nothing is hidden, so
     * offering the platform a second way up to the same file would leave two
     * routes to one resource and a reveal landing on whichever the framework
     * reached first. There must be exactly one, and it must be the folder.
     */
    @Test
    void withNestingOffTheOnlyRouteIsThroughTheFolder(@TempDir Path temp)
            throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(temp, monitor);
        try {
            IFolder folder = createTableFolder(project, monitor,
                    TABLE_FILE, "orders_1.sql"); //$NON-NLS-1$
            publish(folder, Map.of("orders_1.sql", PARENT), //$NON-NLS-1$
                    Map.of("orders_1.sql", TABLE_FILE)); //$NON-NLS-1$
            store.setValue(PREF.NEST_PARTITIONS_UNDER_PARENT, false);

            assertEquals(List.of("orders_1.sql", "TABLE"), //$NON-NLS-1$ //$NON-NLS-2$
                    revealChain(folder.getFile("orders_1.sql"), 2), //$NON-NLS-1$
                    "the section was reached by a route that should not exist");
        } finally {
            cleanUp(project, monitor);
        }
    }

    /**
     * The whole claim, at the size it is really made at, and for all four
     * settings of the two switches.
     *
     * <p>The shape is {@code SCHEMA/tmp/TABLE} of OmniX, counted off the tree
     * on 2026-08-06: 2101 files, of which 2020 are sections, in 21 families -
     * twenty of a hundred and one of twenty - and 81 files that are not
     * sections. All 21 parent tables are files of that same folder, so all 2020
     * sections have somewhere to go.</p>
     *
     * <p>81 rows, not the 101 this work was briefed with. 101 counted the
     * parent tables twice: they are tables of the folder, so they are already
     * among the 81, and the grouping node they gain is a child of theirs and
     * not a row of the folder.</p>
     *
     * <p>The ordering switch changes which row a file is on and never how many
     * there are, so it may not move either count - that is the other half of
     * what is asserted here, and it is the reason all four combinations are
     * measured rather than the two that were expected to differ.</p>
     */
    @Test
    void theFolderOfOmnixComesDownFrom2101RowsTo81(@TempDir Path temp)
            throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(temp, monitor);
        try {
            List<String> names = new ArrayList<>();
            Map<String, String> parents = new LinkedHashMap<>();
            Map<String, String> under = new LinkedHashMap<>();
            for (int t = 0; t < 81; t++) {
                String table = "t%02d.sql".formatted(t); //$NON-NLS-1$
                names.add(table);
                int family = t < 20 ? 100 : (t == 20 ? 20 : 0);
                for (int s = 0; s < family; s++) {
                    String section = "t%02d_%03d.sql".formatted(t, s); //$NON-NLS-1$
                    names.add(section);
                    parents.put(section, "tmp.t%02d".formatted(t)); //$NON-NLS-1$
                    under.put(section, table);
                }
            }
            assertEquals(2101, names.size(), "the fixture is not OmniX-shaped");
            assertEquals(2020, parents.size(), "the fixture is not OmniX-shaped");

            IFolder folder = createTableFolderOnDisk(project, monitor,
                    temp, names);
            publish(folder, parents, under);

            assertEquals(List.of(1001, 1001, 81, 81), fourCombinations(folder),
                    "the four combinations, with the platform's own cap on");

            setItemLimit(0);
            try {
                assertEquals(List.of(2101, 2101, 81, 81),
                        fourCombinations(folder),
                        "the four combinations, with nothing capped");
            } finally {
                setItemLimit(PlatformUI.getPreferenceStore()
                        .getInt(IWorkbenchPreferenceConstants.LARGE_VIEW_LIMIT));
            }
        } finally {
            cleanUp(project, monitor);
        }
    }

    /**
     * The counts in the order (ordering, nesting) = off/off, on/off, off/on,
     * on/on.
     */
    private List<Integer> fourCombinations(IFolder folder) throws Exception {
        return List.of(rowCount(folder, false, false),
                rowCount(folder, true, false),
                rowCount(folder, false, true),
                rowCount(folder, true, true));
    }

    private int rowCount(IFolder folder, boolean ordering, boolean nesting)
            throws Exception {
        store.setValue(PREF.GROUP_PARTITIONS_IN_TREE, ordering);
        store.setValue(PREF.NEST_PARTITIONS_UNDER_PARENT, nesting);
        return rowsUnder(folder).size();
    }

    /**
     * The Project Explorer stops drawing a folder after
     * {@code largeViewLimit} rows and adds one more that offers the rest, so
     * the folder people actually meet today is 1001 rows and a button, not
     * 2101 rows. Both numbers are worth having: the first is what changes for
     * them, the second is what the folder really holds.
     */
    private static void setItemLimit(int limit) {
        PlatformUI.getWorkbench().getDisplay()
                .syncExec(() -> projectExplorer().setDisplayIncrementally(limit));
    }

    /**
     * Turning the switch on has to change the tree that is already on screen.
     *
     * <p>Nothing about a preference is a resource change, and a tree viewer
     * asks its content provider once and keeps the answer, so an expanded
     * folder would go on showing the rows it was given until it was collapsed
     * and expanded again. On a setting that ships off, the very first thing
     * anyone does is turn it on - and it would look broken. So this reads the
     * tree back <em>without</em> expanding anything a second time; expanding is
     * what would have hidden the defect.</p>
     */
    @Test
    void turningTheSwitchOnChangesTheTreeThatIsAlreadyDrawn(@TempDir Path temp)
            throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(temp, monitor);
        try {
            IFolder folder = createTableFolder(project, monitor,
                    TABLE_FILE, "orders_1.sql", "payments.sql"); //$NON-NLS-1$ //$NON-NLS-2$
            publish(folder, Map.of("orders_1.sql", PARENT), //$NON-NLS-1$
                    Map.of("orders_1.sql", TABLE_FILE)); //$NON-NLS-1$
            store.setValue(PREF.NEST_PARTITIONS_UNDER_PARENT, false);
            store.setValue(PREF.GROUP_PARTITIONS_IN_TREE, false);

            assertEquals(List.of("orders_1.sql", TABLE_FILE, "payments.sql"), //$NON-NLS-1$ //$NON-NLS-2$
                    rowsUnder(folder),
                    "the folder did not start out plain");

            assertEquals(List.of(TABLE_FILE, "payments.sql"), //$NON-NLS-1$
                    rowsAfterTurningNestingOn(folder),
                    "the switch did not reach the tree that was already drawn");
        } finally {
            cleanUp(project, monitor);
        }
    }

    private List<String> rowsAfterTurningNestingOn(IFolder folder)
            throws Exception {
        AtomicReference<List<String>> read = new AtomicReference<>();
        AtomicReference<Exception> failed = new AtomicReference<>();
        Display display = PlatformUI.getWorkbench().getDisplay();
        display.syncExec(() -> {
            try {
                CommonViewer viewer = projectExplorer();
                // the folder is already expanded by the assertion before this
                store.setValue(PREF.NEST_PARTITIONS_UNDER_PARENT, true);
                drain(display);

                TreeItem item = findItem(viewer.getTree().getItems(), folder);
                assertNotNull(item, "the TABLE folder left the tree");
                List<String> rows = new ArrayList<>();
                for (TreeItem child : item.getItems()) {
                    if (!child.getText().isEmpty()) {
                        rows.add(child.getText());
                    }
                }
                read.set(rows);
            } catch (Exception e) {
                failed.set(e);
            }
        });
        if (failed.get() != null) {
            throw failed.get();
        }
        return read.get();
    }

    private static String sections(int count) {
        return Messages.PartitionGroupNode_label.formatted(count);
    }

    /**
     * Publishes a scan result the way a finished scan would on a project whose
     * index answers about every file.
     *
     * @param parents which file is a section of which table
     * @param under   which file in this folder each section is shown beneath;
     *                a section missing from here is one whose table is not a
     *                node of this folder
     */
    private static void publish(IFolder folder, Map<String, String> parents,
            Map<String, String> under) {
        publish(folder, parents, under, parents.keySet());
    }

    /**
     * The same, saying which of the links the parser confirmed.
     *
     * @param confirmed the section files whose link the parser confirmed; the
     *                  rest are links only the text of the file claims, which
     *                  is what every link of a project without a working index
     *                  is
     */
    private static void publish(IFolder folder, Map<String, String> parents,
            Map<String, String> under, Set<String> confirmed) {
        Map<IPath, String> parentByFile = new LinkedHashMap<>();
        Map<String, List<IFile>> filesByParent = new LinkedHashMap<>();
        parents.forEach((name, parent) -> {
            IFile file = folder.getFile(name);
            parentByFile.put(file.getFullPath(), parent);
            filesByParent.computeIfAbsent(
                    PartitionLinkReader.groupingKey(parent),
                    k -> new ArrayList<>()).add(file);
        });
        Map<IFile, IFile> parentFileBySection = new LinkedHashMap<>();
        under.forEach((name, parentName) -> parentFileBySection
                .put(folder.getFile(name), folder.getFile(parentName)));
        Set<IPath> confirmedPaths = new LinkedHashSet<>();
        confirmed.forEach(
                name -> confirmedPaths.add(folder.getFile(name).getFullPath()));
        PartitionIndex.getInstance().put(folder.getFullPath(), parentByFile,
                filesByParent, parentFileBySection, confirmedPaths);
    }

    /**
     * Everything visible under the folder, three levels deep, a row per line,
     * a child written as {@code parent > child}. One assertion then says both
     * what left the folder and what appeared under the table, which is the only
     * way to catch the two halves disagreeing.
     */
    private static List<String> listing(IFolder folder) throws Exception {
        return inTree(folder, item -> {
            List<String> rows = new ArrayList<>();
            collect(item.getItems(), "", rows); //$NON-NLS-1$
            return rows;
        });
    }

    /**
     * SWT gives an unexpanded expandable node a placeholder child with no text
     * - and every .sql file is expandable, because the outline extension says
     * so. Those are not rows anyone sees, so they are not rows here.
     */
    private static void collect(TreeItem[] items, String prefix,
            List<String> rows) {
        for (TreeItem item : items) {
            if (item.getText().isEmpty()) {
                continue;
            }
            String row = prefix + item.getText();
            rows.add(row);
            collect(item.getItems(), row + " > ", rows); //$NON-NLS-1$
        }
    }

    /** The direct rows of the folder, which is what the complaint counts. */
    private static List<String> rowsUnder(IFolder folder) throws Exception {
        return inTree(folder, item -> {
            List<String> rows = new ArrayList<>();
            for (TreeItem child : item.getItems()) {
                rows.add(child.getText());
            }
            return rows;
        });
    }

    private static List<String> childrenOfTheTable(IFolder folder)
            throws Exception {
        return inTree(folder, item -> {
            for (TreeItem child : item.getItems()) {
                if (TABLE_FILE.equals(child.getText())) {
                    List<String> rows = new ArrayList<>();
                    for (TreeItem grand : child.getItems()) {
                        rows.add(grand.getText());
                    }
                    return rows;
                }
            }
            return List.<String>of();
        });
    }

    /**
     * Asks the viewer to reveal the file the way "Show In" does, then reads the
     * chain of rows the tree actually built above it.
     */
    private static List<String> revealChain(IFile file, int depth)
            throws Exception {
        AtomicReference<List<String>> chain = new AtomicReference<>();
        AtomicReference<Exception> failed = new AtomicReference<>();
        Display display = PlatformUI.getWorkbench().getDisplay();
        display.syncExec(() -> {
            try {
                CommonViewer viewer = projectExplorer();
                viewer.setExpandedState(file.getProject(), true);
                drain(display);
                viewer.setSelection(new StructuredSelection(file), true);
                drain(display);

                TreeItem[] selected = viewer.getTree().getSelection();
                assertEquals(1, selected.length,
                        "the tree did not reveal the file at all");
                List<String> rows = new ArrayList<>();
                for (TreeItem walk = selected[0]; walk != null
                        && rows.size() < depth; walk = walk.getParentItem()) {
                    rows.add(walk.getText());
                }
                chain.set(rows);
            } catch (Exception e) {
                failed.set(e);
            }
        });
        if (failed.get() != null) {
            throw failed.get();
        }
        return chain.get();
    }

    private static <T> T inTree(IFolder folder, Function<TreeItem, T> reader)
            throws Exception {
        AtomicReference<T> read = new AtomicReference<>();
        AtomicReference<Exception> failed = new AtomicReference<>();
        Display display = PlatformUI.getWorkbench().getDisplay();
        display.syncExec(() -> {
            try {
                CommonViewer viewer = projectExplorer();
                viewer.setExpandedState(folder.getProject(), true);
                viewer.expandToLevel(folder, 3);
                drain(display);

                TreeItem item = findItem(viewer.getTree().getItems(), folder);
                assertNotNull(item, "the TABLE folder never appeared in the tree");
                read.set(reader.apply(item));
            } catch (Exception e) {
                failed.set(e);
            }
        });
        if (failed.get() != null) {
            throw failed.get();
        }
        return read.get();
    }

    private static CommonViewer projectExplorer() {
        var page = PlatformUI.getWorkbench().getActiveWorkbenchWindow()
                .getActivePage();
        IViewPart view;
        try {
            view = page.showView(PROJECT_EXPLORER);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertNotNull(view, "no Project Explorer");
        return ((CommonNavigator) view).getCommonViewer();
    }

    private static TreeItem findItem(TreeItem[] items, IFolder folder) {
        for (TreeItem item : items) {
            if (folder.equals(item.getData())) {
                return item;
            }
            TreeItem found = findItem(item.getItems(), folder);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static void drain(Display display) {
        for (int i = 0; i < 200; i++) {
            while (display.readAndDispatch()) {
                // let the tree settle
            }
        }
    }

    private static IFolder createTableFolder(IProject project,
            NullProgressMonitor monitor, String... names) throws Exception {
        IFolder schema = project.getFolder("SCHEMA"); //$NON-NLS-1$
        schema.create(true, true, monitor);
        IFolder tmp = schema.getFolder("tmp"); //$NON-NLS-1$
        tmp.create(true, true, monitor);
        IFolder table = tmp.getFolder("TABLE"); //$NON-NLS-1$
        table.create(true, true, monitor);
        for (String name : names) {
            table.getFile(name).create(new ByteArrayInputStream(
                    "-- fixture\n".getBytes(StandardCharsets.UTF_8)), //$NON-NLS-1$
                    true, monitor);
        }
        return table;
    }

    /**
     * The same folder, written straight to disk and picked up in one refresh.
     * Two thousand calls to {@code IFile.create} would each be their own
     * workspace operation; this fixture is about the size of the folder, not
     * about how it got there.
     */
    private static IFolder createTableFolderOnDisk(IProject project,
            NullProgressMonitor monitor, Path location, List<String> names)
            throws Exception {
        Path table = location.resolve("SCHEMA").resolve("tmp").resolve("TABLE"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        java.nio.file.Files.createDirectories(table);
        byte[] body = "-- fixture\n".getBytes(StandardCharsets.UTF_8); //$NON-NLS-1$
        for (String name : names) {
            java.nio.file.Files.write(table.resolve(name), body);
        }
        project.refreshLocal(IProject.DEPTH_INFINITE, monitor);
        return project.getFolder("SCHEMA").getFolder("tmp").getFolder("TABLE"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static IProject createProject(Path location,
            NullProgressMonitor monitor) throws Exception {
        var workspace = ResourcesPlugin.getWorkspace();
        String name = "pgck-partition-nesting-" + location.getFileName(); //$NON-NLS-1$
        IProject project = workspace.getRoot().getProject(name);
        if (project.exists()) {
            project.delete(true, true, monitor);
        }
        IProjectDescription description = workspace.newProjectDescription(name);
        description.setLocationURI(location.toUri());
        project.create(description, monitor);
        project.open(monitor);
        IProjectDescription open = project.getDescription();
        open.setNatureIds(new String[] { ProjectUtils.NATURE_ID });
        project.setDescription(open, monitor);
        return project;
    }

    private static void cleanUp(IProject project, NullProgressMonitor monitor) {
        try {
            if (project.exists()) {
                project.delete(true, true, monitor);
            }
        } catch (Exception e) {
            fail(e);
        }
    }
}
