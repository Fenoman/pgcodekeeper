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
package ru.taximaxim.codekeeper.ui.views;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.ActionContributionItem;
import org.eclipse.jface.action.IStatusLineManager;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.ISelectionListener;
import org.eclipse.ui.IViewSite;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.part.ViewPart;
import org.eclipse.zest.core.viewers.AbstractZoomableViewer;
import org.eclipse.zest.core.viewers.GraphViewer;
import org.eclipse.zest.core.viewers.IGraphEntityContentProvider;
import org.eclipse.zest.core.viewers.IZoomableWorkbenchPart;
import org.eclipse.zest.core.widgets.ZestStyles;
import org.eclipse.zest.layouts.algorithms.SpringLayoutAlgorithm;
import org.pgcodekeeper.core.api.ComparisonDepth;
import org.pgcodekeeper.core.model.difftree.TreeElement;
import org.pgcodekeeper.core.model.difftree.TreeElement.DiffSide;
import org.pgcodekeeper.core.model.graph.SimpleDepcyResolver;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.api.schema.IStatement;

import ru.taximaxim.codekeeper.ui.Activator;
import ru.taximaxim.codekeeper.ui.DatabaseType;
import ru.taximaxim.codekeeper.ui.ProjectIcon;
import ru.taximaxim.codekeeper.ui.dialogs.CommitDialog;
import ru.taximaxim.codekeeper.ui.dialogs.ExceptionNotifier;
import ru.taximaxim.codekeeper.ui.localizations.Messages;
import ru.taximaxim.codekeeper.ui.utils.FileUtilsUi;

public class DepcyGraphView extends ViewPart implements IZoomableWorkbenchPart, ISelectionListener {

    private final Action projectAction;
    private final Action remoteAction;
    private final Action addColumnAction;
    private GraphViewer gv;
    private DepcyGraphLabelProvider labelProvider;

    private final DepcyGraphSelectionState selectionState = new DepcyGraphSelectionState();
    private boolean isShowColumns;

    public DepcyGraphView() {
        projectAction = new ProjectAction(Messages.DepcyGraphView_project,
                Activator.getRegisteredDescriptor(ProjectIcon.BALL_BLUE));
        remoteAction = new ToggleAction(Messages.DepcyGraphView_remote,
                Activator.getRegisteredDescriptor(ProjectIcon.BALL_GREEN));
        addColumnAction = new ShowColumnAction(Messages.DepcyGraphView_show_columns,
                Activator.getRegisteredDescriptor(ProjectIcon.COLUMN));
    }

    @Override
    public void init(IViewSite site) throws PartInitException {
        super.init(site);

        IToolBarManager toolman = getViewSite().getActionBars().getToolBarManager();

        toolman.add(new ActionContributionItem(addColumnAction));

        ActionContributionItem ac = new ActionContributionItem(projectAction);
        ac.setMode(ActionContributionItem.MODE_FORCE_TEXT);
        toolman.add(ac);

        ac = new ActionContributionItem(remoteAction);
        ac.setMode(ActionContributionItem.MODE_FORCE_TEXT);
        toolman.add(ac);
    }

    @Override
    public void createPartControl(Composite parent) {
        gv = new GraphViewer(parent, SWT.NONE);
        gv.setNodeStyle(ZestStyles.NODES_NO_ANIMATION);
        gv.setConnectionStyle(ZestStyles.CONNECTIONS_DIRECTED);
        gv.setLayoutAlgorithm(new SpringLayoutAlgorithm(), true);

        labelProvider = new DepcyGraphLabelProvider(gv.getControl());
        gv.setLabelProvider(labelProvider);
        gv.setContentProvider(new DepcyGraphViewContentProvider());

        // listen to node/connection selection events
        gv.getGraphControl().addSelectionListener(new SelectionAdapter() {
        });

        // register listener to pages post selection
        getSite().getPage().addPostSelectionListener(this);

        gv.getGraphControl().addMouseListener(new MouseAdapter() {

            @Override
            public void mouseDoubleClick(MouseEvent e) {
                ISelection selection = gv.getSelection();
                if (selectionState.project() != null && !selection.isEmpty()
                        && selection instanceof IStructuredSelection ss
                        && ss.getFirstElement() instanceof IStatement st) {
                    try {
                        FileUtilsUi.openFileInSqlEditor(
                                st.getLocation(), selectionState.project().getName(), DatabaseType.fromStatement(st),
                                st.isLib());
                    } catch (PartInitException ex) {
                        ExceptionNotifier.notifyDefault(ex.getLocalizedMessage(), ex);
                    }
                }
            }
        });
    }

    @Override
    public void setFocus() {
        gv.getControl().setFocus();
    }

    @Override
    public void dispose() {
        getSite().getPage().removePostSelectionListener(this);
        super.dispose();
    }

    @Override
    public AbstractZoomableViewer getZoomableViewer() {
        return gv;
    }

    @Override
    public void selectionChanged(IWorkbenchPart part, ISelection selection) {
        // Cleared unconditionally, before any branch below can return early:
        // this listener is registered page-wide, not just on the comparison
        // table, so any of those early returns - a click landing on a
        // selection with no comparison at all, chief among them - is easy to
        // reach right after the branch below posted a message. Left to only
        // that branch, an explanation would survive the very selection change
        // that made it stop applying, which is the same silent-lie shape this
        // view exists to rule out, just aimed at itself instead of a receive-
        // only comparison.
        statusLineManager().setMessage(null);

        if (!(selection instanceof IStructuredSelection)) {
            return;
        }

        IProject selectedProj = null;
        DBPair dbPair = null;
        List<?> selected = ((IStructuredSelection) selection).toList();

        for (Object object : selected) {
            if (object instanceof DBPair pair) {
                dbPair = pair;
            } else if (object instanceof IProject proj) {
                selectedProj = proj;
            }
        }
        if (dbPair == null) {
            selectionState.clear(selectedProj);
            if (labelProvider != null) {
                labelProvider.setCurrentRootSet(Set.of());
            }
            if (gv != null) {
                gv.setInput(null);
            }
            return;
        }

        if (!isDependencyGraphAvailable(dbPair.depth())) {
            // A structurally loaded comparison resolves the edges readable off
            // the model and none of the edges the analysis phase writes into
            // getDependencies(), so SimpleDepcyResolver below would answer a
            // selection with a structural fragment rather than with nothing:
            // the object's own schema and children, and none of the routines
            // or expressions that name it. Rendered without a word that is a
            // graph that looks whole and is not - worse than the silence it
            // would replace - so the status line says so on every selection
            // change instead.
            selectionState.clear(selectedProj);
            if (labelProvider != null) {
                labelProvider.setCurrentRootSet(Set.of());
            }
            if (gv != null) {
                gv.setInput(null);
            }
            statusLineManager().setMessage(Messages.DiffTableViewer_depcy_unavailable_in_receive_only);
            return;
        }
        // The unconditional clear at the top of this method already covers
        // this path - this comparison resolves dependencies fine, so nothing
        // here needs to re-post anything.

        boolean showProject = projectAction.isChecked();
        IDatabase newDb = showProject ? dbPair.dbProject().getDatabase() : dbPair.dbRemote().getDatabase();
        IDatabase currentDb = newDb;
        SimpleDepcyResolver resolver = new SimpleDepcyResolver(currentDb, null, isShowColumns,
                dbPair.settings().getAdditionalDependencies());
        selectionState.remember(part, selection, selectedProj, resolver);
        if (currentDb == null) {
            gv.setInput(null);
            return;
        }

        Set<IStatement> newInput = new HashSet<>();
        Set<IStatement> rootSet = new HashSet<>();
        for (Object object : selected) {
            if (object instanceof TreeElement el) {
                // does el exist in the chosen graph (or DB)
                boolean elIsProject = el.getSide() == DiffSide.LEFT;
                if (elIsProject == showProject || el.getSide() == DiffSide.BOTH) {
                    IStatement root = el.getStatement(currentDb);
                    rootSet.add(root);
                    for (IStatement dependant : resolver.getDropDepcies(root)) {
                        newInput.add(dependant);
                    }
                }
            }
        }
        labelProvider.setCurrentRootSet(rootSet);
        gv.setInput(newInput);
    }

    /**
     * Whether the graph this view would render for a selection can be
     * trusted.
     * <p>
     * Delegates to {@link CommitDialog#isDependencySetComplete(ComparisonDepth)}
     * instead of defining a second copy of the same rule: whether a comparison
     * carries every dependency edge does not depend on which widget is asking.
     * What the two widgets then do with the same answer differs, and it differs
     * for a reason. The dialog lists a set it also writes, so a short set there
     * is still worth showing and is only labelled short. This view draws a
     * picture, a picture of a closure has no visible edge that says "and some
     * more you cannot see", and the reverse direction it is chiefly opened for
     * is exactly the one a structural walk under-answers - so here the same
     * incompleteness is a refusal rather than a caption.
     *
     * @param depth how deeply the comparison behind the selection was loaded
     * @return true if a graph built from this depth can be trusted
     */
    static boolean isDependencyGraphAvailable(ComparisonDepth depth) {
        return CommitDialog.isDependencySetComplete(depth);
    }

    /**
     * This view has no dedicated message area of its own - unlike a dialog, it
     * stays open across many selections, so a modal warning on every click
     * would be worse than the silence it replaces. The status line is the
     * standard Eclipse channel for exactly this: a passive, non-blocking
     * explanation that updates with the selection and clears itself the
     * moment there is nothing left to explain.
     */
    private IStatusLineManager statusLineManager() {
        return getViewSite().getActionBars().getStatusLineManager();
    }

    private class DepcyGraphViewContentProvider implements IGraphEntityContentProvider {

        @Override
        public Object[] getElements(Object inputElement) {
            return ArrayContentProvider.getInstance().getElements(inputElement);
        }

        @Override
        public Object[] getConnectedTo(Object entity) {
            if (entity instanceof IStatement st) {
                SimpleDepcyResolver resolver = selectionState.resolver();
                return resolver == null ? null : resolver.getConnectedTo(st).toArray();
            }
            return null;
        }
    }

    private static class ToggleAction extends Action {

        public ToggleAction(String text, ImageDescriptor imgDesc) {
            super(text, AS_RADIO_BUTTON);
            setImageDescriptor(imgDesc);
        }
    }

    private class ShowColumnAction extends Action {

        public ShowColumnAction(String text, ImageDescriptor imgDesc) {
            super(text, AS_CHECK_BOX);
            setImageDescriptor(imgDesc);
            setToolTipText(text);
        }

        @Override
        public void run() {
            isShowColumns = addColumnAction.isChecked();
            selectionChanged(selectionState.selectionPart(), selectionState.selection());
        }
    }

    private class ProjectAction extends ToggleAction {

        public ProjectAction(String text, ImageDescriptor imgDesc) {
            super(text, imgDesc);
            setChecked(true);
        }

        @Override
        public void run() {
            labelProvider.setIsSource(isChecked());
            selectionChanged(selectionState.selectionPart(), selectionState.selection());
        }
    }
}
