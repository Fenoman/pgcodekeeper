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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.navigator.CommonNavigator;
import org.eclipse.ui.navigator.CommonViewer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ru.taximaxim.codekeeper.ui.Activator;
import ru.taximaxim.codekeeper.ui.UIConsts.PREF;
import ru.taximaxim.codekeeper.ui.utils.ProjectUtils;

/**
 * The stand, and then the thing itself.
 *
 * <p>All of this rests on one platform claim that had never been run: that a
 * navigator extension contributing nothing but a sorter is consulted for
 * children the <em>resource</em> extension put in the tree. The bytecode of
 * {@code NavigatorSorterService.findComparator} says so - it walks every
 * active sort-only descriptor before it looks at whoever contributed the
 * elements - but bytecode is a prediction. So this asks the real Project
 * Explorer, in the real target platform, with a real project on disk, and
 * reads the order back off the SWT items. What the user sees is a list of
 * rows, so a list of rows is what is asserted; nothing here measures time.</p>
 *
 * <p>The section map is not built here. Building it needs a parsed project
 * index, which is a different subsystem with its own build cycle; what this
 * fixture proves is that the ordering the map implies is the ordering the
 * widget shows. The map is published straight into {@link PartitionIndex}
 * through its package-private seam, and {@link PartitionLinkReaderTest} is
 * where the map's own truthfulness is argued.</p>
 */
class PartitionOrderStandTest {

    private static final String PROJECT_EXPLORER = "org.eclipse.ui.navigator.ProjectExplorer"; //$NON-NLS-1$

    private static final String PARENT = "tmp.orders"; //$NON-NLS-1$
    private static final String OTHER_PARENT = "tmp.archive"; //$NON-NLS-1$

    @AfterEach
    void forgetTheMap() {
        PartitionIndex.getInstance().clear();
    }

    /**
     * The measured complaint, in miniature: the sections stand between the
     * tables, and a person looking for a table reads past them. Alphabetically
     * {@code orders_1} and {@code orders_2} sit between {@code orders} and
     * {@code payments}. They must end up below every table instead, and the
     * tables must keep the order they always had.
     */
    @Test
    void sectionsGoBelowTheTablesOfTheirFolder(@TempDir Path temp)
            throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(temp, monitor);
        try {
            IFolder folder = createTableFolder(project, monitor,
                    "archive.sql", "archive_1.sql", "orders.sql", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "orders_1.sql", "orders_2.sql", "payments.sql"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            publish(folder, Map.of(
                    "archive_1.sql", OTHER_PARENT, //$NON-NLS-1$
                    "orders_1.sql", PARENT, //$NON-NLS-1$
                    "orders_2.sql", PARENT)); //$NON-NLS-1$

            assertEquals(List.of(
                    "archive.sql", "orders.sql", "payments.sql", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "archive_1.sql", "orders_1.sql", "orders_2.sql"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    orderShownInProjectExplorer(folder),
                    "the sections did not go below the tables");
        } finally {
            cleanUp(project, monitor);
        }
    }

    /**
     * A family has to stay whole and has to follow its own table, or the
     * ordering has only traded one wall for another. Here the section names
     * alone would interleave the two families - {@code a_2} sorts between
     * {@code a_1} and {@code b_1} only if the parent is ignored - so the
     * assertion fails the moment the parent stops being the first key.
     */
    @Test
    void sectionsAreGroupedByTheTableTheyBelongTo(@TempDir Path temp)
            throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(temp, monitor);
        try {
            IFolder folder = createTableFolder(project, monitor,
                    "a_1.sql", "a_2.sql", "b_1.sql", "b_2.sql"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            // names interleave, parents do not: a_1/b_2 belong to one table,
            // a_2/b_1 to the other
            publish(folder, Map.of(
                    "a_1.sql", "tmp.first", //$NON-NLS-1$ //$NON-NLS-2$
                    "b_2.sql", "tmp.first", //$NON-NLS-1$ //$NON-NLS-2$
                    "a_2.sql", "tmp.second", //$NON-NLS-1$ //$NON-NLS-2$
                    "b_1.sql", "tmp.second")); //$NON-NLS-1$ //$NON-NLS-2$

            assertEquals(
                    List.of("a_1.sql", "b_2.sql", "a_2.sql", "b_1.sql"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    orderShownInProjectExplorer(folder),
                    "the families were not kept whole");
        } finally {
            cleanUp(project, monitor);
        }
    }

    /**
     * The same folder on the project this was reported from, where nothing
     * confirms anything.
     *
     * <p>That project receives its changes from a database, so no background
     * index is built for it, and the schema this folder belongs to is excluded
     * from the index besides. Every link in the map is therefore one the text
     * claims and the parser never saw - and the ordering has to be exactly the
     * ordering above, because a row in the wrong place is the whole of what a
     * false claim can cost here. Ordering the folder by nothing is what it did
     * before, with both settings switched on.</p>
     */
    @Test
    void sectionsGoBelowTheTablesWithNothingConfirmingThem(@TempDir Path temp)
            throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(temp, monitor);
        try {
            IFolder folder = createTableFolder(project, monitor,
                    "archive.sql", "archive_1.sql", "orders.sql", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "orders_1.sql", "orders_2.sql", "payments.sql"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            publishUnconfirmed(folder, Map.of(
                    "archive_1.sql", OTHER_PARENT, //$NON-NLS-1$
                    "orders_1.sql", PARENT, //$NON-NLS-1$
                    "orders_2.sql", PARENT)); //$NON-NLS-1$

            assertEquals(List.of(
                    "archive.sql", "orders.sql", "payments.sql", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "archive_1.sql", "orders_1.sql", "orders_2.sql"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    orderShownInProjectExplorer(folder),
                    "an unconfirmed link did not order the folder");
        } finally {
            cleanUp(project, monitor);
        }
    }

    /**
     * The control, and the promise the feature makes to the other fifty-five
     * schemas: a folder the map says nothing about is ordered exactly as the
     * resource extension would have ordered it. This comparator outranks that
     * extension for every folder of a pgCodeKeeper project, so this is the
     * assertion that keeps the win narrow.
     */
    @Test
    void aFolderWithoutSectionsKeepsThePlatformOrder(@TempDir Path temp)
            throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(temp, monitor);
        try {
            IFolder folder = createTableFolder(project, monitor,
                    "aaa.sql", "bbb.sql", "ccc.sql"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            publish(folder, Map.of());

            assertEquals(List.of("aaa.sql", "bbb.sql", "ccc.sql"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    orderShownInProjectExplorer(folder),
                    "a folder without sections was reordered");
        } finally {
            cleanUp(project, monitor);
        }
    }

    /**
     * The switch, which is on by default because nothing disappears when it
     * is. Turning it off has to give back exactly the tree that existed before
     * this feature - not something close to it.
     *
     * <p>The expected order is deliberately not alphabetical by code point.
     * {@code orders_1.sql} comes before {@code orders.sql} because the
     * platform sorts resource names through {@code Collator.getInstance()},
     * which weighs {@code _} below {@code .}, and this comparator repeats that
     * collator on purpose. Writing the ASCII order here instead is what caught
     * it: the run answered {@code [orders_1, orders, payments]} and it was the
     * expectation that was wrong. Delete {@code sortOnly="true"} from
     * plugin.xml, so that the platform's own comparator sorts the folder, and
     * this test still passes - which is the actual claim being made.</p>
     */
    @Test
    void theSwitchGivesBackThePlainOrder(@TempDir Path temp) throws Exception {
        var monitor = new NullProgressMonitor();
        IProject project = createProject(temp, monitor);
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        boolean was = store.getBoolean(PREF.GROUP_PARTITIONS_IN_TREE);
        try {
            IFolder folder = createTableFolder(project, monitor,
                    "orders.sql", "orders_1.sql", "payments.sql"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            publish(folder, Map.of("orders_1.sql", PARENT)); //$NON-NLS-1$

            store.setValue(PREF.GROUP_PARTITIONS_IN_TREE, false);
            assertEquals(
                    List.of("orders_1.sql", "orders.sql", "payments.sql"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    orderShownInProjectExplorer(folder),
                    "the switch did not give back the platform's own order");
        } finally {
            store.setValue(PREF.GROUP_PARTITIONS_IN_TREE, was);
            cleanUp(project, monitor);
        }
    }

    /**
     * Publishes a scan result the way a finished scan would on a project whose
     * index answers, so the widget can be asked about ordering without waiting
     * on a build.
     */
    private static void publish(IFolder folder, Map<String, String> parents) {
        PartitionIndex.getInstance().put(folder.getFullPath(),
                parentByFile(folder, parents), filesByParent(folder, parents));
    }

    /**
     * The same, on a project whose index says nothing: every link is one the
     * text claims and nothing confirmed.
     */
    private static void publishUnconfirmed(IFolder folder,
            Map<String, String> parents) {
        PartitionIndex.getInstance().put(folder.getFullPath(),
                parentByFile(folder, parents), filesByParent(folder, parents),
                Map.of(), Set.of());
    }

    private static Map<IPath, String> parentByFile(IFolder folder,
            Map<String, String> parents) {
        Map<IPath, String> byFile = new LinkedHashMap<>();
        parents.forEach((name, parent) -> byFile
                .put(folder.getFile(name).getFullPath(), parent));
        return byFile;
    }

    private static Map<String, List<IFile>> filesByParent(IFolder folder,
            Map<String, String> parents) {
        Map<String, List<IFile>> byParent = new LinkedHashMap<>();
        parents.forEach((name, parent) -> byParent.computeIfAbsent(
                PartitionLinkReader.groupingKey(parent),
                k -> new ArrayList<>()).add(folder.getFile(name)));
        return byParent;
    }

    private static List<String> orderShownInProjectExplorer(IFolder folder)
            throws Exception {
        AtomicReference<List<String>> shown = new AtomicReference<>();
        AtomicReference<Exception> failed = new AtomicReference<>();
        Display display = PlatformUI.getWorkbench().getDisplay();
        display.syncExec(() -> {
            try {
                var page = PlatformUI.getWorkbench()
                        .getActiveWorkbenchWindow().getActivePage();
                IViewPart view = page.showView(PROJECT_EXPLORER);
                assertNotNull(view, "no Project Explorer");
                CommonViewer viewer = ((CommonNavigator) view).getCommonViewer();
                viewer.setExpandedState(folder.getProject(), true);
                viewer.expandToLevel(folder, 1);
                drain(display);

                TreeItem item = findItem(viewer.getTree().getItems(), folder);
                assertNotNull(item, "the TABLE folder never appeared in the tree");
                List<String> names = new ArrayList<>();
                for (TreeItem child : item.getItems()) {
                    names.add(child.getText());
                }
                shown.set(names);
            } catch (Exception e) {
                failed.set(e);
            }
        });
        if (failed.get() != null) {
            throw failed.get();
        }
        return shown.get();
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

    private static IProject createProject(Path location,
            NullProgressMonitor monitor) throws Exception {
        var workspace = ResourcesPlugin.getWorkspace();
        String name = "pgck-partition-order-" + location.getFileName(); //$NON-NLS-1$
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
