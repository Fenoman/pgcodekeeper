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

import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.compare.CompareConfiguration;
import org.eclipse.compare.structuremergeviewer.DiffNode;
import org.eclipse.core.resources.IProject;
import org.eclipse.jface.action.ToolBarManager;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.pgcodekeeper.core.database.api.loader.ILoader;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.ignorelist.IgnoreList;
import org.pgcodekeeper.core.model.difftree.ChildVisibility;
import org.pgcodekeeper.core.model.difftree.ColumnMark;
import org.pgcodekeeper.core.model.difftree.ColumnVisibility;
import org.pgcodekeeper.core.model.difftree.IgnoredValues;
import org.pgcodekeeper.core.model.difftree.SqlMark;
import org.pgcodekeeper.core.model.difftree.TreeElement;
import org.pgcodekeeper.core.model.difftree.TreeElement.DiffSide;
import org.pgcodekeeper.core.database.api.schema.IStatement;
import org.pgcodekeeper.core.database.api.schema.ITable;
import org.pgcodekeeper.core.database.ms.schema.MsAssembly;
import org.pgcodekeeper.core.settings.ISettings;

import ru.taximaxim.codekeeper.ui.Activator;
import ru.taximaxim.codekeeper.ui.ProjectIcon;
import ru.taximaxim.codekeeper.ui.UIConsts;
import ru.taximaxim.codekeeper.ui.UIConsts.PG_EDIT_PREF;
import ru.taximaxim.codekeeper.ui.comparetools.PaneMarks;
import ru.taximaxim.codekeeper.ui.comparetools.CompareItem;
import ru.taximaxim.codekeeper.ui.comparetools.SqlMergeViewer;
import ru.taximaxim.codekeeper.ui.localizations.Messages;
import ru.taximaxim.codekeeper.ui.prefs.PrefChangeAction;
import ru.taximaxim.codekeeper.ui.settings.UISettings;

public final class DiffPaneViewer extends Composite {

    // see ComparePreferencePage.SWAPPED
    private static final String PREF_SWAP = "org.eclipse.compare.Swapped";

    private final SqlMergeViewer diffPane;
    private final PaneMarkLegend legend;
    private ILoader dbProject;
    private ILoader dbRemote;

    /**
     * The list the shown comparison hides by, see
     * {@code ProjectEditorDiffer.installIgnoreList}. It never reaches the
     * settings a rendering is built with: those are made per rendering and are
     * used for SQL generation alone, which never reads an ignore list. The pane
     * does read it, to tell a reader what it did, see {@link #marksOf}.
     */
    private IgnoreList ignoreList;

    /**
     * The columns of a table the shown comparison manages, read from the list
     * above once for the whole comparison.
     * <p>
     * Once, and not once per object, because deciding this reads a whole
     * database - a view, a function or a sequence anywhere in it may be the
     * reason a column is kept, see {@link ColumnVisibility}. The reading is held
     * by the instance built here and is shared with every answer derived from
     * it, so one comparison pays for one reading of each of its two states
     * however many objects the reader clicks through. It goes with the models,
     * see {@link #clearComparisonReferences()}, because it is a reading of them.
     * <p>
     * An ignore list without a {@code type=COLUMN} rule that hides cannot name a
     * column, and this then costs nothing at all - no reading, no question asked
     * per object, and a legend that is not there.
     */
    private ColumnVisibility managedColumns = ColumnVisibility.all();

    private TreeElement currentEl;
    private Collection<TreeElement> availableElements;

    private final IProject project;

    private final IPreferenceStore store = Activator.getDefault().getPreferenceStore();

    private final PrefChangeAction swapAction;
    private final PrefChangeAction showFullCodeAction;

    /**
     * Attaches the pane to one comparison.
     *
     * @param dbProject  the project side of it
     * @param dbRemote   the database side of it
     * @param ignoreList the objects that comparison does not manage, may be
     *                   {@code null}
     */
    public void setComparison(ILoader dbProject, ILoader dbRemote, IgnoreList ignoreList) {
        this.dbProject = dbProject;
        this.dbRemote = dbRemote;
        this.ignoreList = ignoreList;
        // rules scoped to a database are resolved with the name of the database
        // side, exactly as childVisibilityOf resolves them
        this.managedColumns = dbRemote == null ? ColumnVisibility.of(ignoreList)
                : ColumnVisibility.of(ignoreList, dbRemote.getDatabaseName());
    }

    public DiffPaneViewer(Composite parent, IProject project) {
        super(parent, SWT.BORDER);
        this.project = project;

        setLayoutData(new GridData(GridData.FILL_BOTH));
        GridLayout filterLayout = new GridLayout();
        filterLayout.marginWidth = filterLayout.marginHeight = 0;
        setLayout(filterLayout);

        CompareConfiguration conf = new CompareConfiguration();
        conf.setLeftLabel(Messages.database);
        conf.setRightLabel(Messages.DiffPaneViewer_project);

        Composite header = new Composite(this, SWT.NONE);
        GridLayout headerLayout = new GridLayout(2, false);
        headerLayout.marginWidth = headerLayout.marginHeight = 0;
        header.setLayout(headerLayout);
        header.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        legend = new PaneMarkLegend(header);

        Composite container = new Composite(header, SWT.NONE);
        var gl = new GridLayout();
        gl.marginWidth = gl.marginHeight = 0;
        gl.marginBottom = -3;
        container.setLayout(gl);
        container.setLayoutData(new GridData(SWT.RIGHT, SWT.TOP, false, false));

        swapAction = new PrefChangeAction(Messages.DiffPaneViewer_btn_switch,
                PREF_SWAP, conf.getPreferenceStore(), container, ProjectIcon.SWITCH);

        showFullCodeAction = new PrefChangeAction(Messages.GeneralPrefPage_show_full_code,
                PG_EDIT_PREF.SHOW_FULL_CODE, store, container, ProjectIcon.SHOW_CHILDREN) {

            @Override
            public void refresh() {
                if (currentEl != null && currentEl.isContainer()) {
                    setInput(currentEl, availableElements);
                }
            }
        };

        var toolBar = new ToolBarManager();
        toolBar.add(swapAction);
        toolBar.add(showFullCodeAction);
        toolBar.createControl(container);

        diffPane = new SqlMergeViewer(this, SWT.NONE, conf);
        diffPane.getControl().setLayoutData(new GridData(GridData.FILL_BOTH));
        diffPane.refresh(); // load labels
    }

    @Override
    public void dispose() {
        clearComparisonReferences();
        swapAction.dispose();
        showFullCodeAction.dispose();
        super.dispose();
    }

    /** Releases loaded models without updating an already disposed viewer. */
    public void clearComparisonReferences() {
        dbProject = null;
        dbRemote = null;
        ignoreList = null;
        // holds what it read out of both models, so it goes with them
        managedColumns = ColumnVisibility.all();
        currentEl = null;
        availableElements = null;
    }

    public void setInput(TreeElement el, Collection<TreeElement> availableElements) {
        this.currentEl = el;
        this.availableElements = availableElements;

        // before the input, because setting one builds the presentation of every
        // side and that is when the shades are read
        PaneMarks marks = marksOf(el);
        diffPane.setMarks(marks);

        String contentsLeft = null;
        String contentsRight = null;
        if (el != null) {
            contentsLeft = getSql(el, false, true);
            contentsRight = getSql(el, true, true);
            if (contentsLeft != null && contentsRight != null
                    && contentsLeft.equals(contentsRight)) {
                contentsLeft = getSql(el, false, false);
                contentsRight = getSql(el, true, false);
            }
            diffPane.setInput(new DiffNode(new CompareItem(Messages.database, contentsLeft),
                    new CompareItem(Messages.DiffPaneViewer_project, contentsRight)));
        } else {
            diffPane.setInput(null);
        }

        // after the input, because a margin takes its width from the rendering
        // its side has been given
        diffPane.refreshMargins();
        showLegend(marks, contentsLeft, contentsRight);
    }

    /**
     * Explains the marks the reader is about to see, and explains no others.
     * <p>
     * What the comparison has to say and what it has to say <em>here</em> are two
     * questions. A rule may name a column of a table whose rendering says nothing
     * about it beyond the one line, and a setting may overlook a statistics
     * target of a column that states none at all - so the legend is told which
     * marks really fall on one of the two renderings, and a shade nobody can see
     * gets no line of the screen to be explained on.
     */
    private void showLegend(PaneMarks marks, String... renderings) {
        Set<SqlMark> present = EnumSet.noneOf(SqlMark.class);
        for (String rendering : renderings) {
            if (rendering != null) {
                marks.rangesIn(rendering).forEach(range -> present.add(range.mark()));
            }
        }
        if (legend.setMarks(marks, present, diffPane.getPaneBackground())) {
            // the row took a line of the pane or gave one back
            layout(true, true);
        }
    }

    /**
     * What this comparison has to say about one object: which of its columns the
     * rules name, and which of its values the settings overlook.
     * <p>
     * <b>Bound to both states and never to one.</b> A column stays managed while
     * anything in its database still needs it, and the index, the view or the
     * sequence that needs it may exist on one side only - it is being created or
     * dropped by the very migration on display. Asked about one state at a time
     * the two sides would answer differently, and the same column would be
     * marked as leaving on one side of the screen and as staying on the other:
     * a statement about the migration dressed up as a statement about the
     * column.
     * <p>
     * <b>And the values only where there are two states to compare.</b> Both
     * settings drop a <em>difference</em>, so an object one side does not hold is
     * created or dropped whole and every value of it goes with it, see
     * {@link IgnoredValues}. That is why the settings are not even read for a
     * one-sided object.
     * <p>
     * Only a table has columns a rule can name, and only a list holding such a
     * rule is asked anything at all, so an object of any other kind and a
     * project of any other ignore list pay one comparison and stop.
     */
    private PaneMarks marksOf(TreeElement el) {
        if (el == null) {
            return PaneMarks.NONE;
        }

        IStatement inDatabase = stateIn(el, DiffSide.RIGHT, dbRemote);
        IStatement inProject = stateIn(el, DiffSide.LEFT, dbProject);
        // the migration runs from the database to the project, see the Differ
        // built by ProjectEditorDiffer
        IgnoredValues values = inDatabase == null || inProject == null ? IgnoredValues.NONE
                : IgnoredValues.of(createDisplaySettings(project), inDatabase, inProject);

        Map<String, ColumnMark> marks = new LinkedHashMap<>();
        Map<String, String> kept = new LinkedHashMap<>();
        if (el.getType() == DbObjType.TABLE && managedColumns.hidesAnything()) {
            ColumnVisibility bound = managedColumns.forPair(asTable(inDatabase), asTable(inProject));
            for (ITable side : new ITable[] { asTable(inDatabase), asTable(inProject) }) {
                if (side != null) {
                    bound.marksIn(side).forEach(marks::putIfAbsent);
                    bound.pinnedColumns(side).forEach(kept::putIfAbsent);
                }
            }
        }
        return marks.isEmpty() && values.isEmpty() ? PaneMarks.NONE : new PaneMarks(marks, kept, values);
    }

    private static ITable asTable(IStatement state) {
        return state instanceof ITable table ? table : null;
    }

    /**
     * The state of an object one side of the comparison holds, {@code null} when
     * it holds none.
     * <p>
     * The side is decided exactly as {@link #getElementSql} decides which side to
     * render from, and for the same reason: an element that is on one side only
     * has no state on the other, and asking the model of that other side for it
     * is not merely fruitless but throws, the parent it would be looked up in
     * being absent as well.
     *
     * @param present the side of the tree that means the object is in this model
     */
    private static IStatement stateIn(TreeElement el, DiffSide present, ILoader db) {
        if (db == null || (el.getSide() != present && el.getSide() != DiffSide.BOTH)) {
            return null;
        }
        return el.getStatement(db.getDatabase());
    }

    private String getSql(TreeElement el, boolean isProject, boolean format) {
        String elSql = getElementSql(el, isProject, format);
        if (elSql == null || availableElements == null || !el.hasChildren() || !el.isContainer()
                || store.getBoolean(PG_EDIT_PREF.SHOW_FULL_CODE)) {
            return elSql;
        }

        ChildVisibility visible = childVisibilityOf(el);
        List<TreeElement> children = el.getChildren().stream()
                .filter(visible::isVisible)
                .sorted(DiffPaneChildOrder.TREE_ELEMENT_ORDER)
                .toList();

        StringBuilder sb = new StringBuilder(elSql);
        for (TreeElement child : children) {
            if (availableElements.contains(child)) {
                String childSql = getElementSql(child, isProject, format);
                if (childSql != null) {
                    sb.append(UIConsts._NL).append(UIConsts._NL).append(childSql);
                }
            }
        }

        return sb.toString();
    }

    /**
     * Settings of one rendering in this pane, and of nothing else.
     * <p>
     * Every rendering builds its own, so a display-only relaxation set here
     * cannot reach the settings a migration script is generated with: those are
     * built by {@code UISettings.forGetChanges} and never passed to this pane.
     * <p>
     * The relaxation is the name order of table columns. It is asked for only
     * while the order of the columns is not a difference anyway, because the
     * two sides are then free to be rendered in one order, and a permutation of
     * a dozen columns must not bury the one line that really differs.
     * <p>
     * These settings carry no ignore list and are not meant to: SQL generation
     * never reads one. What the comparison hides is held by the pane itself, see
     * {@link #setComparison}, and reaches the rendering through
     * {@link ChildVisibility}. What it says about a column reaches nothing at
     * all: the text of a table is rendered whole, every column of it, and the
     * marks only decide what some of its lines are drawn on and what stands in
     * the margin beside them, see {@link #marksOf}.
     *
     * @param project the project whose preferences the rendering follows
     * @return fresh settings for a single rendering
     */
    static UISettings createDisplaySettings(IProject project) {
        return applyDisplayPolicy(new UISettings(project));
    }

    static UISettings applyDisplayPolicy(UISettings settings) {
        settings.setSortColumnsForDisplay(settings.isIgnoreColumnOrder());
        return settings;
    }

    private String getElementSql(TreeElement el, boolean isProject, boolean isFormatted) {
        if ((el.getSide() == DiffSide.LEFT) != isProject && el.getSide() != DiffSide.BOTH) {
            return null;
        }

        ILoader db = isProject ? dbProject : dbRemote;
        IStatement st = el.getStatement(db.getDatabase());
        ISettings settings = createDisplaySettings(project);
        if (st.getStatementType() == DbObjType.ASSEMBLY) {
            return ((MsAssembly) st).getPreview(settings);
        }

        String sql = st.getSQL(isFormatted, settings);
        if (!el.isContainer() || !store.getBoolean(PG_EDIT_PREF.SHOW_FULL_CODE)) {
            return sql;
        }

        return appendVisibleChildren(sql, st, childVisibilityOf(el), isFormatted, settings);
    }

    /**
     * Appends the children of a container to its own code, the way the full code
     * of an object is shown.
     * <p>
     * The children come from the loaded model, where nothing was ever dropped:
     * the difference tree hides what the ignore list hides, but a model holds
     * every object it was loaded with. Rendering them all would put objects the
     * project declares it does not manage back in front of the user, on one side
     * of the comparison only, as a difference that no migration script will ever
     * carry. The list decides here as well, and by the same rules.
     *
     * @param sql         the code of the container itself
     * @param st          the container in the loaded model
     * @param visible     which of its children the ignore list keeps
     * @param isFormatted whether the code is formatted
     * @param settings    settings of this one rendering
     * @return the code of the container followed by the code of its visible
     * children, in display order
     */
    static String appendVisibleChildren(String sql, IStatement st, ChildVisibility visible,
            boolean isFormatted, ISettings settings) {
        StringBuilder sb = new StringBuilder(sql);
        st.getChildren()
                .filter(visible::isVisible)
                .sorted(DiffPaneChildOrder.STATEMENT_ORDER)
                .forEach(c -> sb.append(UIConsts._NL).append(UIConsts._NL).append(c.getSQL(isFormatted, settings)));
        return sb.toString();
    }

    /**
     * Resolves the ignore list for the children of one container.
     * <p>
     * Rules scoped to a database are resolved with the name of the database side
     * of the comparison, exactly as {@code DiffTableViewer} resolves them for the
     * object list above this pane, so the pane and that list never disagree about
     * an object.
     */
    private ChildVisibility childVisibilityOf(TreeElement container) {
        return ChildVisibility.of(ignoreList, container, dbRemote == null ? null : dbRemote.getDatabaseName());
    }
}
