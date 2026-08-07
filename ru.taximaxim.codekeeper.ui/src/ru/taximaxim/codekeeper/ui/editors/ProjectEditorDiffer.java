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
package ru.taximaxim.codekeeper.ui.editors;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.NotEnabledException;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.core.commands.common.NotDefinedException;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.IEclipsePreferences.IPreferenceChangeListener;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IMenuCreator;
import org.eclipse.jface.action.IStatusLineManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.action.ToolBarManager;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.MessageDialogWithToggle;
import org.eclipse.jface.layout.PixelConverter;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.resource.FontDescriptor;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.resource.LocalResourceManager;
import org.eclipse.jface.viewers.DecorationOverlayIcon;
import org.eclipse.jface.viewers.IDecoration;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IEditorSite;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.WorkbenchException;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.contexts.IContextService;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.part.EditorPart;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.ui.progress.IProgressConstants2;
import org.eclipse.ui.statushandlers.StatusManager;
import org.osgi.service.prefs.BackingStoreException;
import org.pgcodekeeper.core.Consts;
import org.pgcodekeeper.core.api.ComparisonDepth;
import org.pgcodekeeper.core.database.api.IDatabaseProvider;
import org.pgcodekeeper.core.database.api.loader.ComparisonLoaderFactories;
import org.pgcodekeeper.core.database.api.loader.ILoader;
import org.pgcodekeeper.core.database.api.loader.ILoaderFactory;
import org.pgcodekeeper.core.database.api.schema.IStatement;
import org.pgcodekeeper.core.database.api.schema.ObjectOverride;
import org.pgcodekeeper.core.database.base.loader.LoaderFactories;
import org.pgcodekeeper.core.ignorelist.IgnoreList;
import org.pgcodekeeper.core.model.difftree.TreeElement;
import org.pgcodekeeper.core.model.difftree.TreeElement.DiffSide;
import org.pgcodekeeper.core.model.graph.DepcyTreeExtender;
import org.pgcodekeeper.core.monitor.IMonitor;
import org.pgcodekeeper.core.settings.ISettings;
import org.pgcodekeeper.core.utils.FileUtils;

import ru.taximaxim.codekeeper.ui.Activator;
import ru.taximaxim.codekeeper.ui.DatabaseType;
import ru.taximaxim.codekeeper.ui.Log;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexConfigurationDiagnostics;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexConfigurationDiagnostics.PreferenceNode;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectInputDeltaPolicy;
import ru.taximaxim.codekeeper.ui.ProjectIcon;
import ru.taximaxim.codekeeper.ui.UIConsts.COMMAND;
import ru.taximaxim.codekeeper.ui.UIConsts.CONTEXT;
import ru.taximaxim.codekeeper.ui.UIConsts.DB_BIND_PREF;
import ru.taximaxim.codekeeper.ui.UIConsts.DB_UPDATE_PREF;
import ru.taximaxim.codekeeper.ui.UIConsts.EDITOR;
import ru.taximaxim.codekeeper.ui.UIConsts.PERSPECTIVE;
import ru.taximaxim.codekeeper.ui.UIConsts.PG_EDIT_PREF;
import ru.taximaxim.codekeeper.ui.UIConsts.PLUGIN_ID;
import ru.taximaxim.codekeeper.ui.UIConsts.PREF;
import ru.taximaxim.codekeeper.ui.UIConsts.PREF_PAGE;
import ru.taximaxim.codekeeper.ui.UIConsts.PROJ_PATH;
import ru.taximaxim.codekeeper.ui.UIConsts.PROJ_PREF;
import ru.taximaxim.codekeeper.ui.UIConsts.VIEW;
import ru.taximaxim.codekeeper.ui.UiSync;
import ru.taximaxim.codekeeper.ui.database.base.jdbc.IDbInfoConnector;
import ru.taximaxim.codekeeper.ui.dbstore.DBStoreMenu;
import ru.taximaxim.codekeeper.ui.dbstore.DbInfo;
import ru.taximaxim.codekeeper.ui.dbstore.DbMenuStorePicker;
import ru.taximaxim.codekeeper.ui.dialogs.ApplyCustomDialog;
import ru.taximaxim.codekeeper.ui.dialogs.CommitDialog;
import ru.taximaxim.codekeeper.ui.dialogs.ExceptionNotifier;
import ru.taximaxim.codekeeper.ui.dialogs.GetChangesCustomDialog;
import ru.taximaxim.codekeeper.ui.differ.DiffPaneViewer;
import ru.taximaxim.codekeeper.ui.differ.DiffTableViewer;
import ru.taximaxim.codekeeper.ui.differ.Differ;
import ru.taximaxim.codekeeper.ui.job.SingletonEditorJob;
import ru.taximaxim.codekeeper.ui.libraries.LibraryUtils;
import ru.taximaxim.codekeeper.ui.localizations.Messages;
import ru.taximaxim.codekeeper.ui.pgdbproject.PgDbProject;
import ru.taximaxim.codekeeper.ui.pgdbproject.parser.PgDbParser;
import ru.taximaxim.codekeeper.ui.pgdbproject.parser.ProjectReceiveOnlyMode;
import ru.taximaxim.codekeeper.ui.pgdbproject.parser.ReusableProjectComparison;
import ru.taximaxim.codekeeper.ui.pgdbproject.parser.UIComparisonLoader;
import ru.taximaxim.codekeeper.ui.prefs.PreferenceChangeCoalescer;
import ru.taximaxim.codekeeper.ui.prefs.Preferences;
import ru.taximaxim.codekeeper.ui.properties.OverridablePrefs;
import ru.taximaxim.codekeeper.ui.propertytests.ChangesJobTester;
import ru.taximaxim.codekeeper.ui.settings.EclipseComparisonTelemetry.UiStage;
import ru.taximaxim.codekeeper.ui.settings.ProjectIgnoreLists;
import ru.taximaxim.codekeeper.ui.settings.UISettings;
import ru.taximaxim.codekeeper.ui.sqledit.SQLEditor;
import ru.taximaxim.codekeeper.ui.utils.ConcurrentUIMonitor;
import ru.taximaxim.codekeeper.ui.utils.FileUtilsUi;
import ru.taximaxim.codekeeper.ui.utils.GetChangesProgressSink;
import ru.taximaxim.codekeeper.ui.utils.GetChangesProgressSink.Phase;
import ru.taximaxim.codekeeper.ui.utils.ProjectUtils;
import ru.taximaxim.codekeeper.ui.utils.UIMonitor;
import ru.taximaxim.codekeeper.ui.views.DBPair;
import ru.taximaxim.codekeeper.ui.xmlstore.DbXmlStore;

public final class ProjectEditorDiffer extends EditorPart
        implements IResourceChangeListener, IPreferenceChangeListener {

    /**
     * How long a preference node is left untouched before this editor reacts to
     * what it now holds.
     * <p>
     * The window has to outlast the re-application of a whole preference file,
     * because it guards a fingerprint of the node as a whole and any key of it
     * may be momentarily absent. A workspace refresh of an untouched project
     * file was measured as fourteen routed changes carrying one and the same
     * second stamp, which makes them consecutive in-memory node updates rather
     * than anything separated by I/O -- three orders of magnitude below this
     * window. Each event restarts it, so the burst has to stall for the better
     * part of a second in the middle to be split in two.
     * <p>
     * The other end is a person who did change a setting by hand and is
     * watching for something to happen. Together with the rebuild coalescing
     * that follows it, this leaves under a second before the reindex job
     * appears.
     */
    private static final long PREFERENCE_SETTLE_DELAY_MILLIS = 750;

    /**
     * Names the two reactions a preference change may have, so that a burst
     * touching both coalesces into one of each rather than into one of them.
     */
    private enum PreferenceReaction {
        PROJECT_INDEX,
        COMPARISON
    }

    private final PreferenceChangeCoalescer preferenceReactions =
            new PreferenceChangeCoalescer(PREFERENCE_SETTLE_DELAY_MILLIS,
                    ProjectEditorDiffer::schedulePreferenceReaction);
    private final IPreferenceStore mainPrefs = Activator.getDefault().getPreferenceStore();
    private final GetChangesJobCoordinator getChangesJobs = new GetChangesJobCoordinator();
    private final ReusableProjectComparison reusableProjectComparison =
            new ReusableProjectComparison();
    private final AtomicReference<UiPublicationHandoff<
            ReusableProjectComparison.PreparedComparison>>
            pendingUiPublication = new AtomicReference<>();
    /**
     * Thread that is currently running this editor's own workspace refresh.
     * Deltas broadcast by that refresh describe bytes this run is about to
     * read, so they must not cancel the run that produced them.
     */
    private final AtomicReference<Thread> selfRefreshThread =
            new AtomicReference<>();
    private final AtomicReference<GetChangesRunLifecycle> activeRunLifecycle =
            new AtomicReference<>();

    private PgDbProject proj;
    private ProjectEditorSelectionProvider sp;
    /**
     * Written once by {@code createPartControl} on the display thread and read
     * from a settling window that runs on a job, which is the one read of it
     * that shares no lock with that write.
     */
    private volatile Composite parent;
    private IEclipsePreferences mainPrefsNode;
    private IEclipsePreferences projectPrefsNode;
    /**
     * do not read directly, use {@link #getCurrentDb()}
     */
    private Object currentRemote;
    private ILoader dbProject;
    private ILoader dbRemote;
    private TreeElement diffTree;
    private ISettings settings;
    /**
     * How deeply the currently displayed comparison was loaded, captured at
     * load time alongside {@link #settings}. Script generation must judge the
     * comparison actually on screen, not whatever the receive-only preference
     * happens to read at the later moment it runs.
     */
    private ComparisonDepth comparisonDepth;
    private Object loadedRemote;
    private ReusableProjectComparison.DisplayLease displayedProjectModel;
    /**
     * Guards a single automatic re-run of Get Changes with full routine
     * analysis, so a rejected or still unsafe result can never loop.
     */
    private boolean fullAnalysisRerunPending;
    private Set<TreeElement> rerunCheckedElements = Set.of();
    /**
     * Consumed by exactly the next {@link #getChanges()} to make it ask for
     * {@link ComparisonDepth#FULL} regardless of the receive-only preference,
     * see {@link #reloadForScript()}. The preference itself is left alone -
     * only this one comparison is forced full.
     */
    private boolean forceFullDepthOnce;

    private PassiveNotificationArea notificationArea;

    private DbMenuStorePicker dbStorePicker;
    private Label lblApplyTo;
    private Action getChangesAction;
    private DiffTable diffTable;
    private DiffPaneViewer diffPane;
    private boolean isCommitCommandAvailable;

    private DatabaseType dbType;
    private final Map<String, Object> oneTimePrefs = new HashMap<>();

    public IProject getProject() {
        return proj.getProject();
    }

    public void changeMigrationDireciton(boolean isApplyToProj, boolean showWarning) {
        diffTable.changeMigrationDireciton(isApplyToProj, showWarning);
    }

    @Override
    public void init(IEditorSite site, IEditorInput input) throws PartInitException {
        if (!(input instanceof ProjectEditorInput in)) {
            throw new PartInitException(Messages.ProjectEditorDiffer_error_bad_input_type);
        }

        Exception ex = in.getError();
        if (ex != null) {
            throw new PartInitException(in.getError().getLocalizedMessage(), ex);
        }

        setInput(input);
        setSite(site);
        setPartName(in.getName());

        proj = new PgDbProject(in.getProject());
        sp = new ProjectEditorSelectionProvider(getProject());
        dbType = ProjectUtils.getDatabaseType(getProject());
        PgDbParser.observeProjectIndexConfiguration(getProject());

        // message box
        if (!PERSPECTIVE.MAIN.equals(site.getPage().getPerspective().getId())) {
            askPerspectiveChange(site);
        }
        getSite().setSelectionProvider(sp);

        mainPrefsNode = InstanceScope.INSTANCE.getNode(PLUGIN_ID.THIS);
        if (mainPrefsNode != null) {
            mainPrefsNode.addPreferenceChangeListener(this);
        }

        projectPrefsNode = proj.getPrefs();
        if (projectPrefsNode != null) {
            projectPrefsNode.addPreferenceChangeListener(this);
        }
    }

    @Override
    public void createPartControl(Composite parent) {
        this.parent = parent;

        parent.setLayout(new GridLayout());
        LocalResourceManager lrm = new LocalResourceManager(JFaceResources.getResources(), parent);

        SashForm sashOuter = new SashForm(parent, SWT.VERTICAL | SWT.SMOOTH);
        sashOuter.setLayoutData(new GridData(GridData.FILL_BOTH));

        IStatusLineManager manager = getEditorSite().getActionBars().getStatusLineManager();

        diffTable = new DiffTable(sashOuter, false, manager, Paths.get(getProject().getLocationURI()),
                proj.getProject());

        diffTable.setLayoutData(new GridData(GridData.FILL_BOTH));
        diffTable.getViewer().addPostSelectionChangedListener(e -> {
            IStructuredSelection selection = (IStructuredSelection) e.getSelection();
            if (selection.size() != 1) {
                diffPane.setInput(null, null);
            } else {
                TreeElement el = (TreeElement) selection.getFirstElement();
                diffPane.setInput(el, diffTable.getElements());
            }
        });

        diffTable.getViewer().addDoubleClickListener(
                e -> openElementInEditor((TreeElement) ((IStructuredSelection) e.getSelection()).getFirstElement()));

        diffTable.getViewer().addPostSelectionChangedListener(
                e -> sp.fireSelectionChanged(e, new DBPair(dbProject, dbRemote, settings, comparisonDepth)));

        diffPane = new DiffPaneViewer(sashOuter, getProject());

        // notifications container
        // simplified for 1 static notification
        // refactor into multiple child composites w/ description class
        // for multiple dynamic notifications if necessary
        Group contNotifications = new Group(parent, SWT.NONE);
        contNotifications.setLayout(new GridLayout(4, false));

        GridData gd = new GridData(GridData.FILL_HORIZONTAL);
        gd.exclude = true;
        contNotifications.setVisible(false);
        contNotifications.setLayoutData(gd);

        Label lblNotification = new Label(contNotifications, SWT.NONE);
        lblNotification.setText(Messages.DiffPresentationPane_attention);
        lblNotification.setFont(lrm.create(FontDescriptor.createFrom(lblNotification.getFont()).withStyle(SWT.BOLD)));

        Label lblNotificationText = new Label(contNotifications, SWT.NONE);

        Link linkRefresh = new Link(contNotifications, SWT.NONE);
        linkRefresh.setText(Messages.DiffPresentationPane_refresh_link);
        gd = new GridData();
        gd.horizontalIndent = 10;
        linkRefresh.setLayoutData(gd);

        // Event handling when users click on links.
        linkRefresh.addSelectionListener(new SelectionAdapter() {

            @Override
            public void widgetSelected(SelectionEvent e) {
                getChanges();
            }

        });

        Link linkClose = new Link(contNotifications, SWT.NONE);
        linkClose.setText(Messages.DiffPresentationPane_close_link);
        gd = new GridData();
        gd.horizontalIndent = 5;
        linkClose.setLayoutData(gd);

        // Event handling when users click on links.
        linkClose.addSelectionListener(new SelectionAdapter() {

            @Override
            public void widgetSelected(SelectionEvent e) {
                hideNotificationArea();
            }

        });
        notificationArea = new PassiveNotificationArea(parent,
                contNotifications, lblNotificationText);
        // end notifications container

        diffTable.changeMigrationDireciton(getLastDirection(), false);

        ResourcesPlugin.getWorkspace().addResourceChangeListener(this,
                IResourceChangeEvent.PRE_CLOSE | IResourceChangeEvent.PRE_DELETE | IResourceChangeEvent.POST_CHANGE);

        ICommandService commandService = PlatformUI.getWorkbench().getService(ICommandService.class);
        @SuppressWarnings("unchecked")
        Collection<String> commandIds = commandService.getDefinedCommandIds();
        isCommitCommandAvailable = commandIds.contains(COMMAND.COMMIT_COMMAND_ID);

        getSite().getService(IContextService.class).activateContext(CONTEXT.MAIN);
    }

    private void updateWorkWith() {
        dbStorePicker.setSelection(getCurrentDb(), false);
        lblApplyTo.setText(diffTable.isApplyToProj() ? Messages.ProjectEditorDiffer_apply_project
                : Messages.ProjectEditorDiffer_apply_db);
        diffTable.layout(true, true);
    }

    @Override
    public boolean isDirty() {
        return false;
    }

    @Override
    public void setFocus() {
        diffTable.getViewer().getControl().setFocus();
        diffTable.updateObjectsLabels();
        updateSelection();
    }

    private void updateSelection() {
        updateSelection(dbProject, dbRemote, settings, comparisonDepth);
    }

    /**
     * @param candidateDepth how deeply {@code candidateProject}/{@code candidateRemote}
     *                       were loaded - the caller's own {@link #comparisonDepth}
     *                       field when reporting the currently published
     *                       comparison, but a not-yet-published candidate's own
     *                       depth when called from {@link #showOverrideView},
     *                       which can differ from what is on screen right now
     */
    private void updateSelection(ILoader candidateProject,
            ILoader candidateRemote,
            ISettings candidateSettings,
            ComparisonDepth candidateDepth) {
        if (candidateProject != null) {
            ISelection selection = diffTable.getViewer().getSelection();
            if (selection.isEmpty()) {
                sp.fireComparisonChanged(new DBPair(
                        candidateProject, candidateRemote,
                        candidateSettings, candidateDepth));
            }
        }
    }

    @Override
    public void doSave(IProgressMonitor monitor) {
        // no impl
    }

    @Override
    public void doSaveAs() {
        // no impl
    }

    @Override
    public boolean isSaveAsAllowed() {
        return false;
    }

    @Override
    public void dispose() {
        releaseComparisonReferences();
        ResourcesPlugin.getWorkspace().removeResourceChangeListener(this);
        if (mainPrefsNode != null) {
            mainPrefsNode.removePreferenceChangeListener(this);
        }
        if (projectPrefsNode != null) {
            projectPrefsNode.removePreferenceChangeListener(this);
        }
        // The comparison this reset would retire is being torn down here
        // anyway. The index outlives the editor, so a change already observed
        // has to be settled rather than dropped: no listener is left to
        // observe it a second time.
        preferenceReactions.cancel(PreferenceReaction.COMPARISON);
        preferenceReactions.flush(PreferenceReaction.PROJECT_INDEX);
        dbStorePicker.dispose();
        diffPane.dispose();
        super.dispose();
    }

    private void releaseComparisonReferences() {
        getChangesJobs.cancel();
        // A queued UI callback is dropped when the display dies, so terminate
        // the run explicitly instead of waiting for a callback that will
        // never arrive.
        GetChangesRunLifecycle lifecycle = activeRunLifecycle.getAndSet(null);
        if (lifecycle != null) {
            lifecycle.abandon();
        }
        closePendingUiPublication();
        dbProject = null;
        dbRemote = null;
        diffTree = null;
        settings = null;
        comparisonDepth = null;
        loadedRemote = null;
        if (diffTable != null) {
            diffTable.clearComparisonReferences();
        }
        if (diffPane != null) {
            diffPane.clearComparisonReferences();
        }
        if (sp != null) {
            sp.clearSelection();
        }
        releaseDisplayedProjectModel();
        reusableProjectComparison.close();
    }

    @Override
    public void resourceChanged(IResourceChangeEvent event) {
        switch (event.getType()) {
        case IResourceChangeEvent.PRE_CLOSE, IResourceChangeEvent.PRE_DELETE:
            handlerCloseProject(event);
            break;
        case IResourceChangeEvent.POST_CHANGE:
            handleChangeProject(event.getDelta());
            break;
        default:
            break;
        }
    }

    private void handlerCloseProject(IResourceChangeEvent event) {
        if (event.getResource().getProject().equals(getProject())) {
            UiSync.exec(parent, () -> {
                if (!parent.isDisposed()) {
                    getSite().getPage().closeEditor(ProjectEditorDiffer.this, true);
                }
            });
        }
    }

    private void handleChangeProject(IResourceDelta rootDelta) {
        boolean comparisonInputChanged;
        try {
            comparisonInputChanged = ProjectInputDeltaPolicy
                    .affectsComparison(rootDelta, getProject());
        } catch (CoreException | RuntimeException ex) {
            Log.log(ex);
            comparisonInputChanged = true;
        }

        if (!comparisonInputChanged) {
            return;
        }

        // The cached model was built from the previous bytes either way, so it
        // is retired even for a self-inflicted delta. The warm validator then
        // rejects it and the run falls back to a cold load of the new bytes.
        reusableProjectComparison.invalidate();

        if (Thread.currentThread() == selfRefreshThread.get()) {
            // Our own refreshLocal published these bytes into the workspace.
            // Cancelling here would make every Get Changes cancel itself as
            // soon as the project changed outside Eclipse.
            return;
        }

        // Supersede a queued UI publication immediately on the resource
        // event thread. Waiting for the asynchronous notification would
        // leave a window in which a result based on the old bytes could
        // still be accepted.
        GetChangesJobCoordinator.Cancellation cancellation =
                getChangesJobs.cancelForNotification();
        UiSync.exec(parent, () -> cancellation.publish(
                () -> notifyProjectChanged(
                        cancellation
                            .cancelledActiveRequest())));
    }

    public void getChanges() {
        // Only a run that is actually started may resume a pending migration.
        boolean resumeMigration = fullAnalysisRerunPending;
        fullAnalysisRerunPending = false;
        // Consumed here and nowhere else: 'depth' below is the only thing
        // that reads it, so clearing the field the moment it is captured
        // cannot lose it to a later re-entrant call the way restoring
        // 'fullAnalysisRerunPending' late (below) has to.
        boolean forceFullDepth = forceFullDepthOnce;
        forceFullDepthOnce = false;

        Object currentRemote = getCurrentDb();
        if (currentRemote == null) {
            MessageDialog.openInformation(parent.getShell(), Messages.GetChanges_select_source,
                    Messages.GetChanges_select_source_msg);
            return;
        }

        boolean isDbInfo = currentRemote instanceof DbInfo;
        DatabaseType dbType = ProjectUtils.getDatabaseType(getProject());
        if (isDbInfo && (((DbInfo) currentRemote).getDbType() != dbType)) {
            MessageDialog.openInformation(parent.getShell(), Messages.ProjectEditorDiffer_different_types,
                    Messages.ProjectEditorDiffer_different_types_msg);
            return;
        }

        var settings = UISettings.forGetChanges(getProject(), oneTimePrefs, dbType);
        // The diff tree is built from these settings and it now drops what the
        // editor hides, so every list the editor hides by must be known before
        // the comparison starts. Core adds the project file itself while it
        // loads; the workspace, database and extra lists live in the UI alone
        // and would otherwise never reach the comparison.
        installIgnoreList(settings, currentRemote);
        IDatabaseProvider provider = dbType.getDatabaseProvider();
        Path projectPath = getProject().getLocation().toFile().toPath();
        ILoaderFactory oldFactory = LoaderFactories.project(projectPath,
                sideSettings -> provider.getProjectLoader(projectPath, sideSettings,
                        Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                        LibraryUtils.META_PATH));

        ILoaderFactory newFactory;
        String newDatabaseName;
        if (isDbInfo) {
            DbInfo dbInfo = (DbInfo) currentRemote;
            IDbInfoConnector connector = IDbInfoConnector.createConnector(dbInfo);
            newFactory = LoaderFactories.of(sideSettings ->
                    provider.getJdbcLoader(connector, sideSettings));
            newDatabaseName = dbInfo.getDbName();
            saveLastDb(dbInfo);
        } else {
            File file = (File) currentRemote;
            newFactory = LoaderFactories.of(sideSettings ->
                    provider.getDumpLoader(file.toPath(), sideSettings));
            newDatabaseName = file.toPath().toString();
        }
        var loaderFactories = new ComparisonLoaderFactories(oldFactory, newFactory);

        // Captured once for this request, the same way 'settings' above is:
        // the job below runs asynchronously, and a run already in flight must
        // keep judging itself by the mode this request started with, not by
        // whatever the preference becomes before the job returns. A pending
        // reloadForScript overrides the preference outright: the mode stays
        // on, but a script needs FULL for this one comparison.
        ComparisonDepth depth = forceFullDepth ? ComparisonDepth.FULL
                : ProjectReceiveOnlyMode.depth(getProject());

        if (!ProjectUtils.checkVersionAndWarn(getProject(), parent.getShell(), true)) {
            return;
        }

        Log.log(Log.LOG_INFO, Messages.ProjectEditorDiffer_getting_changes);

        var comparisonTelemetry = Objects.requireNonNull(
                settings.getComparisonRunTelemetry(),
                "comparisonTelemetry"); //$NON-NLS-1$
        var requestRef =
                new AtomicReference<GetChangesJobCoordinator.Request>();
        var runHandoffRef = new AtomicReference<
                UiPublicationHandoff<
                        ReusableProjectComparison.PreparedComparison>>();
        var lifecycleRef =
                new AtomicReference<GetChangesRunLifecycle>();
        var runLifecycle = new GetChangesRunLifecycle(
                comparisonTelemetry, () -> {
                    UiPublicationHandoff<
                            ReusableProjectComparison.PreparedComparison>
                            handoff =
                                    runHandoffRef.getAndSet(null);
                    if (handoff != null) {
                        pendingUiPublication.compareAndSet(
                                handoff, null);
                        handoff.close();
                    }
                    activeRunLifecycle.compareAndSet(
                            lifecycleRef.get(), null);
                    GetChangesJobCoordinator.Request request =
                            requestRef.get();
                    if (request != null) {
                        request.complete();
                    }
                });
        lifecycleRef.set(runLifecycle);
        activeRunLifecycle.set(runLifecycle);
        fullAnalysisRerunPending = resumeMigration;
        boolean hasOneTimePreferences =
                !oneTimePrefs.isEmpty();
        reset();
        hideNotificationArea();

        Job job = new SingletonEditorJob(Messages.diffPresentationPane_getting_changes_for_diff, this,
                ChangesJobTester.EVAL_PROP) {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                runLifecycle.jobStarted();
                GetChangesJobCoordinator.Request request = requestRef.get();
                if (!request.isCurrent() || monitor.isCanceled()) {
                    return Status.CANCEL_STATUS;
                }

                ReusableProjectComparison.PreparedComparison prepared = null;
                boolean handedToUi = false;
                try {
                    // One weighted plan owns the whole run. Every stage reports
                    // into its own slice, so the bar keeps moving while the
                    // databases load instead of freezing until they are done.
                    GetChangesProgressSink progress =
                            Objects.requireNonNullElseGet(
                                    comparisonTelemetry.progress(),
                                    GetChangesProgressSink::new);
                    progress.begin(monitor);
                    progress.enterPhase(Phase.WORKSPACE_REFRESH);
                    long refreshStart = comparisonTelemetry.startTimer();
                    // The workspace broadcasts this refresh synchronously on
                    // the refreshing thread, so marking the thread lets the
                    // resource listener tell our own delta from a foreign one.
                    Thread refreshThread = Thread.currentThread();
                    selfRefreshThread.set(refreshThread);
                    try {
                        getProject().refreshLocal(IResource.DEPTH_INFINITE,
                                progress.slice(Phase.WORKSPACE_REFRESH, 100));
                    } finally {
                        selfRefreshThread.compareAndSet(refreshThread, null);
                        progress.completePhase(Phase.WORKSPACE_REFRESH);
                        comparisonTelemetry.uiStageFinished(
                                UiStage.WORKSPACE_REFRESH, refreshStart);
                    }

                    IProgressMonitor workerMonitor = progress.workerMonitor();
                    IMonitor uiMonitor = settings.isParallelLoad()
                            ? new ConcurrentUIMonitor(workerMonitor)
                            : new UIMonitor(SubMonitor.convert(workerMonitor,
                                    ConcurrentUIMonitor.WORK_TICKS));
                    IMonitor.checkCancelled(uiMonitor);
                    settings.setMonitor(uiMonitor);
                    long coreLoadStart = comparisonTelemetry.startTimer();
                    UIComparisonLoader.Result loaded;
                    ComparisonDepth loadedDepth;
                    try {
                        var reusable = reusableProjectComparison.load(
                                getProject(), dbType, provider, projectPath,
                                newFactory, settings,
                                projectPath.getFileName().toString(),
                                newDatabaseName, monitor,
                                hasOneTimePreferences, depth);
                        if (reusable.isPresent()) {
                            prepared = reusable.orElseThrow();
                            loaded = prepared.result();
                            // The reusable pipeline now declines outright
                            // unless this request asked for FULL (its sixth
                            // early exit), and on every path it does take it
                            // still calls Core without a depth of its own, so
                            // a run it served is always FULL.
                            loadedDepth = ComparisonDepth.FULL;
                        } else {
                            // The reusable pipeline declined this run, so the
                            // plain loader owns the load and tree phases.
                            progress.enterPhase(Phase.CORE_LOAD);
                            boolean requiresFactories =
                                    settings.requiresComparisonLoaderFactories();
                            loadedDepth = plainLoaderDepth(requiresFactories, depth);
                            if (requiresFactories) {
                                loaded = UIComparisonLoader.createResult(
                                        UIComparisonLoader.loadModels(
                                                loaderFactories, settings, depth),
                                        projectPath.getFileName().toString(),
                                        newDatabaseName);
                            } else {
                                // The legacy path predates ComparisonDepth and
                                // always resolves every dependency.
                                loaded = UIComparisonLoader.loadLegacy(loaderFactories, settings,
                                        projectPath.getFileName().toString(), newDatabaseName);
                            }
                            progress.completePhase(Phase.DIFF_TREE);
                        }
                    } finally {
                        comparisonTelemetry.uiStageFinished(
                                UiStage.CORE_LOAD, coreLoadStart);
                    }
                    progress.enterPhase(Phase.PUBLISH);

                    IMonitor.checkCancelled(uiMonitor);
                    if (!request.isCurrent()
                            || prepared != null
                                    && !prepared.isCurrent()
                            || !runLifecycle.queueUiPublication()) {
                        return Status.CANCEL_STATUS;
                    }
                    UiPublicationHandoff<
                            ReusableProjectComparison.PreparedComparison>
                            uiHandoff = null;
                    if (prepared != null) {
                        uiHandoff = new UiPublicationHandoff<>(
                                prepared);
                        var displaced = new AtomicReference<
                                UiPublicationHandoff<
                                        ReusableProjectComparison.PreparedComparison>>();
                        UiPublicationHandoff<
                                ReusableProjectComparison.PreparedComparison>
                                installedHandoff = uiHandoff;
                        if (!request.publish(() -> {
                            runHandoffRef.set(
                                    installedHandoff);
                            displaced.set(
                                    pendingUiPublication
                                            .getAndSet(
                                                    installedHandoff));
                        })) {
                            uiHandoff.close();
                            return Status.CANCEL_STATUS;
                        }
                        UiPublicationHandoff<
                                ReusableProjectComparison.PreparedComparison>
                                previous = displaced.get();
                        if (previous != null
                                && previous != installedHandoff) {
                            previous.close();
                        }
                        handedToUi = true;
                    }
                    UIComparisonLoader.Result uiLoaded = loaded;
                    ComparisonDepth uiLoadedDepth = loadedDepth;
                    UiPublicationHandoff<
                            ReusableProjectComparison.PreparedComparison>
                            callbackHandoff = uiHandoff;
                    boolean uiEnqueued;
                    try {
                        uiEnqueued = UiSync.tryExec(parent, () -> {
                            ReusableProjectComparison.PreparedComparison
                                    claimedPrepared = null;
                            if (callbackHandoff != null) {
                                claimedPrepared = callbackHandoff
                                        .claim().orElse(null);
                                pendingUiPublication.compareAndSet(
                                        callbackHandoff, null);
                                runHandoffRef.compareAndSet(
                                        callbackHandoff, null);
                                if (claimedPrepared == null) {
                                    runLifecycle.supersede();
                                    return;
                                }
                            }
                            try (ReusableProjectComparison.PreparedComparison
                                    acceptedPrepared =
                                            claimedPrepared) {
                                if (parent.isDisposed()
                                        || !request.isCurrent()
                                        || acceptedPrepared != null
                                                && !acceptedPrepared
                                                        .isCurrent()) {
                                    runLifecycle.uiPublicationRejected();
                                    return;
                                }
                                // Override confirmation may open modal
                                // dialogs. It runs before the coordinator
                                // monitor is taken: a nested event loop under
                                // that monitor would block the resource-change
                                // thread and let a re-entrant Get Changes
                                // commit stale state.
                                boolean confirmed = confirmOverrides(uiLoaded, uiLoadedDepth);
                                boolean published = runLifecycle.publishUi(
                                        () -> confirmed
                                                ? publishComparison(
                                                        request, uiLoaded,
                                                        acceptedPrepared,
                                                        currentRemote,
                                                        uiLoadedDepth)
                                                : rejectComparison());
                                if (!published) {
                                    runLifecycle.uiPublicationRejected();
                                }
                            }
                        });
                    } catch (RuntimeException | Error ex) {
                        runLifecycle.uiPublicationFailed();
                        throw ex;
                    }
                    if (!uiEnqueued) {
                        if (callbackHandoff != null) {
                            runHandoffRef.compareAndSet(
                                    callbackHandoff, null);
                            pendingUiPublication.compareAndSet(
                                    callbackHandoff, null);
                            callbackHandoff.close();
                        }
                        runLifecycle.uiPublicationRejected();
                    }
                    progress.done();
                } catch (CoreException | IOException e) {
                    return new Status(IStatus.ERROR, PLUGIN_ID.THIS, Messages.error_in_differ_thread, e);
                } catch (InterruptedException e) {
                    GetChangesFailurePolicy.notice(e)
                            .ifPresent(message -> {
                                Log.log(Log.LOG_INFO,
                                        e.getLocalizedMessage());
                                UiSync.exec(parent,
                                        () -> request.publish(() -> {
                                            if (!parent.isDisposed()) {
                                                showNotificationArea(
                                                        true, message, true);
                                            }
                                        }));
                            });
                    return Status.CANCEL_STATUS;
                } finally {
                    if (!handedToUi && prepared != null) {
                        prepared.close();
                    }
                }
                return Status.OK_STATUS;
            }

            @Override
            protected void canceling() {
                runLifecycle.cancelRequested();
            }
        };
        job.addJobChangeListener(new JobChangeAdapter() {

            @Override
            public void aboutToRun(IJobChangeEvent event) {
                GetChangesJobCoordinator.Request request = requestRef.get();
                UiSync.exec(parent, () -> request.publish(() -> {
                    if (!parent.isDisposed()) {
                        getChangesAction.setEnabled(false);
                    }
                }));
            }

            @Override
            public void done(IJobChangeEvent event) {
                GetChangesJobCoordinator.Request request = requestRef.get();
                IStatus result = event.getResult();
                runLifecycle.jobFinished(result.isOK()
                        ? GetChangesRunLifecycle.JobOutcome.SUCCESS
                        : result.getSeverity() == IStatus.CANCEL
                                ? GetChangesRunLifecycle.JobOutcome.CANCELLED
                                : GetChangesRunLifecycle.JobOutcome.FAILED);
                UiSync.exec(parent, () -> request.publish(() -> {
                    if (!parent.isDisposed()) {
                        getChangesAction.setEnabled(true);
                    }
                }));

                request.publish(() -> {
                    if (event.getResult().isOK() && mainPrefs.getBoolean(PG_EDIT_PREF.SHOW_DIFF_ERRORS)) {
                        settings.getErrors().forEach(e -> StatusManager.getManager()
                                .handle(new Status(IStatus.WARNING, PLUGIN_ID.THIS, e.toString()), StatusManager.SHOW));
                    }
                });
            }
        });
        job.setRule(ResourcesPlugin.getWorkspace().getRuleFactory().refreshRule(getProject()));
        job.setProperty(IProgressConstants2.SHOW_IN_TASKBAR_ICON_PROPERTY, Boolean.TRUE);
        job.setUser(true);
        requestRef.set(getChangesJobs.start(() -> {
            runLifecycle.supersede();
            job.cancel();
        }));
        job.schedule();
    }

    /**
     * Decides the depth {@code loadedDepth} ends up at once the reusable
     * pipeline has declined a run and the plain loader owns it - the second
     * half of the three-way branch in {@link #getChanges()} that {@code
     * comparisonDepth}, and therefore {@link #diff()}'s guard, ultimately
     * answers to. Extracted so it can be pinned down by a test without a
     * workbench, unlike the branch itself.
     * <p>
     * The first half needs no such test: a run the reusable pipeline served
     * is always {@link ComparisonDepth#FULL}, a bare constant - see the
     * comment at that call site.
     *
     * @param requiresLoaderFactories whether this run's settings use the
     *                                depth-aware coordinator entry point
     *                                rather than the legacy loader that
     *                                predates {@link ComparisonDepth}
     * @param requestedDepth          the depth this request asked {@link
     *                                ProjectReceiveOnlyMode} for
     * @return the depth the plain loader path actually leaves the models at
     */
    static ComparisonDepth plainLoaderDepth(boolean requiresLoaderFactories,
            ComparisonDepth requestedDepth) {
        // The legacy path predates ComparisonDepth and always resolves every
        // dependency; only the depth-aware entry point can honor anything
        // but FULL.
        return requiresLoaderFactories ? requestedDepth : ComparisonDepth.FULL;
    }

    /**
     * Commits an accepted comparison to the editor state. Runs on the UI
     * thread and must stay free of modal dialogs: the coordinator monitor is
     * held for the whole publication.
     */
    private GetChangesRunLifecycle.PublicationOutcome publishComparison(
            GetChangesJobCoordinator.Request request,
            UIComparisonLoader.Result loaded,
            ReusableProjectComparison.PreparedComparison prepared,
            Object remoteSnapshot,
            ComparisonDepth depth) {
        boolean[] accepted = { false };
        boolean current = request.publish(() -> {
            if (parent.isDisposed()
                    || prepared != null && !prepared.isCurrent()) {
                return;
            }
            setInput(loaded.oldLoader(), loaded.newLoader(),
                    loaded.diffTree(), loaded.settings(), depth, remoteSnapshot);
            if (prepared != null) {
                var published = prepared.publish();
                if (!published.accepted()) {
                    rollbackComparisonPublication();
                    return;
                }
                setDisplayedProjectModel(
                        published.displayLease().orElse(null));
            }
            loadedRemote = remoteSnapshot;
            if (diffTable.getElements().isEmpty()) {
                showNotificationArea(true,
                        Messages.ProjectEditorDiffer_no_differences);
            }
            accepted[0] = true;
        });

        if (!current) {
            // A newer run owns 'oneTimePrefs' now, so it must survive.
            return GetChangesRunLifecycle.PublicationOutcome.REJECTED;
        }
        // clearing because this preferences must be used only once
        oneTimePrefs.clear();
        if (!accepted[0]) {
            return GetChangesRunLifecycle.PublicationOutcome.REJECTED;
        }
        if (fullAnalysisRerunPending) {
            // Continue outside the coordinator monitor and outside the
            // publication: resuming opens dialogs and starts new jobs.
            UiSync.exec(parent, this::resumeMigrationAfterFullAnalysis);
        }
        return GetChangesRunLifecycle.PublicationOutcome.ACCEPTED;
    }

    /**
     * Detaches the editor from a comparison the user refused to publish.
     */
    private GetChangesRunLifecycle.PublicationOutcome rejectComparison() {
        rollbackComparisonPublication();
        // clearing because this preferences must be used only once
        oneTimePrefs.clear();
        return GetChangesRunLifecycle.PublicationOutcome.REJECTED;
    }

    /**
     * Asks the user about library overrides before a comparison is published.
     * May open modal dialogs, so it must never run under the coordinator
     * monitor.
     *
     * @param depth how deeply {@code loaded} was loaded - this comparison is
     *              not yet published to {@link #comparisonDepth} at this
     *              point, so that field cannot be read here; the caller passes
     *              its own {@code uiLoadedDepth} local instead
     * @return {@code false} when the comparison must not be published
     */
    private boolean confirmOverrides(UIComparisonLoader.Result loaded, ComparisonDepth depth) {
        return loaded.oldLoader() == null
                || showOverrideView(loaded.oldLoader(), loaded.newLoader(),
                        loaded.settings(), depth);
    }

    private boolean showOverrideView(ILoader candidateProject,
            ILoader candidateRemote,
            ISettings candidateSettings,
            ComparisonDepth candidateDepth) {
        Collection<ObjectOverride> overrides =
                candidateProject.getDatabase().getOverrides();
        if (overrides.isEmpty()) {
            return true;
        }

        try {
            getSite().getPage().showView(VIEW.OVERRIDE_VIEW, null, IWorkbenchPage.VIEW_VISIBLE);
            updateSelection(candidateProject, candidateRemote,
                    candidateSettings, candidateDepth);
        } catch (PartInitException e) {
            ExceptionNotifier.notifyDefault(e.getLocalizedMessage(), e);
        }

        if (proj.getPrefs().getBoolean(PROJ_PREF.LIB_SAFE_MODE, true)) {
            var changeSettings = MessageDialog.openQuestion(parent.getShell(),
                    Messages.ProjectEditorDiffer_library_duplication_title,
                    Messages.ProjectEditorDiffer_library_duplication_exception);

            if (changeSettings) {
                PreferencesUtil.createPropertyDialogOn(parent.getShell(), proj.getProject(), PREF_PAGE.DEPENDENCIES,
                        null, null).open();
            }
            return false;
        }

        return true;
    }

    private void askPerspectiveChange(IEditorSite site) {
        String mode = mainPrefs.getString(PG_EDIT_PREF.PERSPECTIVE_CHANGING_STATUS);
        // if select "YES" with toggle
        if (MessageDialogWithToggle.ALWAYS.equals(mode)) {
            changePerspective(site);
            // if not select "NO" with toggle, show choice message dialog
        } else if (!MessageDialogWithToggle.NEVER.equals(mode)) {
            MessageDialogWithToggle dialog = MessageDialogWithToggle.openYesNoQuestion(site.getShell(),
                    Messages.change_perspective_title, Messages.change_perspective_message,
                    Messages.remember_choice_toggle, false, mainPrefs, PG_EDIT_PREF.PERSPECTIVE_CHANGING_STATUS);
            if (dialog.getReturnCode() == IDialogConstants.YES_ID) {
                changePerspective(site);
            }
        }
    }

    private void changePerspective(IEditorSite site) {
        // change perspective to pgCodeKeeper
        try {
            site.getWorkbenchWindow().getWorkbench().showPerspective(PERSPECTIVE.MAIN, site.getWorkbenchWindow());
        } catch (WorkbenchException e) {
            Log.log(Log.LOG_ERROR, Messages.ProjectEditorDiffer_change_perspective_error, e);
        }
    }

    private void openElementInEditor(TreeElement el) {
        if ((el == null) || (el.getSide() == DiffSide.RIGHT)) {
            return;
        }

        try {
            IStatement st = el.getStatement(dbProject.getDatabase());
            IProject project = getProject();
            FileUtilsUi.openFileInSqlEditor(st.getLocation(), project.getName(), ProjectUtils.getDatabaseType(project),
                    st.isLib());
        } catch (CoreException e) {
            ExceptionNotifier.notifyCoreException(e);
        }
    }

    /**
     * @param remote remote DB schema: either {@link File} or {@link DbInfo}
     * @throws IllegalArgumentException invalid remote type
     */
    public void setCurrentDb(Object currentRemote) {
        if ((currentRemote != null) && !(currentRemote instanceof DbInfo) && !(currentRemote instanceof File)) {
            throw new IllegalArgumentException(Messages.ProjectEditorDiffer_remote_db_error);
        }

        if (!Objects.equals(this.currentRemote, currentRemote)) {
            this.currentRemote = currentRemote;
            if (diffTable != null) {
                reset();
                loadedRemote = null;
                hideNotificationArea();
            }
        }
        updateWorkWith();
    }

    /**
     * @return currently set remote for this editor
     */
    public Object getCurrentDb() {
        IEclipsePreferences prefs = proj.getDbBindPrefs();
        DbInfo boundDb = DbInfo.getLastDb(prefs.get(DB_BIND_PREF.NAME_OF_BOUND_DB, ""), dbType); //$NON-NLS-1$
        if (boundDb != null) {
            dbStorePicker.setEnabled(false);
            return boundDb;
        }

        if (currentRemote != null) {
            return currentRemote;
        }

        return DbInfo.getLastDb(prefs.get(DB_BIND_PREF.LAST_DB_STORE, ""), dbType); //$NON-NLS-1$
    }

    public void saveLastDb(DbInfo lastDb) {
        saveLastDb(lastDb, getProject());
    }

    public static void saveLastDb(DbInfo lastDb, IProject project) {
        IEclipsePreferences prefs = PgDbProject.getPrefs(project, false);
        if (prefs != null) {
            prefs.put(DB_BIND_PREF.LAST_DB_STORE, lastDb.getName());
            try {
                prefs.flush();
            } catch (BackingStoreException ex) {
                Log.log(ex);
            }
        }
    }

    public boolean getLastDirection() {
        IEclipsePreferences prefs = proj.getDbBindPrefs();
        return prefs.getBoolean(DB_BIND_PREF.LAST_DIRECTION, true);
    }

    /**
     * Whether a comparison loaded at this depth has to be recomputed before a
     * migration script is built from it.
     * <p>
     * The single line that keeps a structurally loaded model out of script
     * generation, and the only thing standing between a receive-only project
     * and a script whose statements are in whatever order two models with no
     * dependencies between them happened to be walked in. Extracted so both
     * places that ask it - {@link #diff()} before it starts, and {@link
     * #resumeMigrationAfterFullAnalysis()} after the recomputation it ordered -
     * ask the same question, and so a test can hold that question still.
     * <p>
     * Asked as {@code != FULL} rather than {@code == STRUCTURAL_ONLY}, which
     * is what {@code ReusableProjectComparison.load} already asks and what a
     * depth added later would answer correctly without anyone remembering this
     * method. It also decides the one case neither form describes: a {@code
     * null} depth, which {@code resetRemoteChanged} leaves behind when a
     * comparison is dropped, means no comparison was loaded at all - and a
     * script is even less buildable from that than from a structural one.
     *
     * @param depth the depth the displayed comparison was loaded at, or
     *              {@code null} when none is displayed
     * @return {@code true} if a full reload has to happen first
     */
    static boolean scriptNeedsFullReload(ComparisonDepth depth) {
        return depth != ComparisonDepth.FULL;
    }

    public void diff() {
        Log.log(Log.LOG_INFO, Messages.ProjectEditorDiffer_started_db_update);
        if ((warnCheckedElements() < 1) || !ProjectUtils.checkVersionAndWarn(getProject(), parent.getShell(), true)) {
            return;
        }

        if (!UIComparisonLoader.isMigrationGenerationSafe(settings)) {
            requestFullAnalysisRerun();
            return;
        }

        if (scriptNeedsFullReload(comparisonDepth)) {
            // The comparison on screen was loaded without the analysis, so
            // dbProject/dbRemote carry no dependencies for a script to order
            // by. Recompute in full before building one rather than build it
            // from what is currently displayed.
            Log.log(Log.LOG_INFO, Messages.ProjectEditorDiffer_script_needs_full_load);
            reloadForScript();
            return;
        }

        IEclipsePreferences pref = proj.getPrefs();
        final Differ differ = new Differ(dbRemote.getDatabase(), dbProject.getDatabase(), diffTree.getRevertedCopy(),
                pref.get(PROJ_PREF.TIMEZONE, Consts.UTC), getProject(), oneTimePrefs, dbType, settings);

        // The differ job reads the possibly reused OLD model after this diff
        // stops being displayed, so it holds the exclusive lease itself.
        Runnable modelHold = retainDisplayedProjectModel();
        Job job = differ.getDifferJob();
        job.addJobChangeListener(new JobChangeAdapter() {

            @Override
            public void done(IJobChangeEvent event) {
                try {
                    Log.log(Log.LOG_INFO,
                            Messages.ProjectEditorDiffer_job_finished.formatted(event.getResult().getSeverity()));
                    if (event.getResult().isOK()) {
                        UiSync.exec(parent, () -> {
                            if (!parent.isDisposed()) {
                                try {
                                    showEditor(differ);
                                } catch (PartInitException ex) {
                                    ExceptionNotifier
                                            .notifyDefault(Messages.ProjectEditorDiffer_error_opening_script_editor, ex);
                                }
                            }
                        });
                    }

                    // clearing because this preferences must be used only once
                    oneTimePrefs.clear();
                } finally {
                    modelHold.run();
                }
            }
        });
        job.setUser(true);
        job.schedule();
    }

    /**
     * Offers to recompute the comparison with full routine analysis instead of
     * refusing migration generation outright. The default preferences enable
     * both hash-first loading and skipping the analysis of matching routine
     * bodies, which alone would make the script unavailable forever.
     */
    private void requestFullAnalysisRerun() {
        if (fullAnalysisRerunPending) {
            // One automatic re-run already happened, so report the settings.
            MessageDialog.openWarning(parent.getShell(),
                    Messages.ProjectEditorDiffer_incomplete_routine_analysis_title,
                    Messages.ProjectEditorDiffer_incomplete_routine_analysis_msg);
            return;
        }

        MessageDialog dialog = new MessageDialog(parent.getShell(),
                Messages.ProjectEditorDiffer_incomplete_routine_analysis_title, null,
                Messages.ProjectEditorDiffer_incomplete_routine_analysis_msg, MessageDialog.WARNING,
                new String[] { Messages.ProjectEditorDiffer_recompute_full_analysis,
                        IDialogConstants.CANCEL_LABEL },
                0);
        if (dialog.open() != 0) {
            return;
        }

        rerunCheckedElements = Set.copyOf(diffTable.getElements().stream()
                .filter(TreeElement::isSelected).toList());
        fullAnalysisRerunPending = true;
        // The one-time path bypasses the reusable-model cache, so this run
        // always analyzes every routine body.
        oneTimePrefs.put(PREF.PG_ROUTINE_BODY_SKIP_MATCHED_ANALYSIS,
                Boolean.FALSE);
        getChanges();
    }

    /**
     * Recomputes the comparison at {@link ComparisonDepth#FULL} before a
     * script is built from one that was loaded without the analysis, then
     * resumes {@link #diff()} once the recomputed comparison is on screen.
     * <p>
     * Unlike {@link #requestFullAnalysisRerun()} this never asks: the
     * receive-only preference promises that only this one comparison is
     * recomputed and that the preference itself stays on, so there is
     * nothing here for the user to decide. Get Changes runs exactly the path
     * it always does - same job, same progress, same telemetry - the only
     * difference is the depth this one request forces.
     */
    private void reloadForScript() {
        rerunCheckedElements = Set.copyOf(diffTable.getElements().stream()
                .filter(TreeElement::isSelected).toList());
        fullAnalysisRerunPending = true;
        forceFullDepthOnce = true;
        getChanges();
    }

    /**
     * Restores the objects checked before an automatic full-analysis re-run
     * and generates the script the user originally asked for.
     */
    private void resumeMigrationAfterFullAnalysis() {
        Set<TreeElement> checked = rerunCheckedElements;
        rerunCheckedElements = Set.of();
        if (parent.isDisposed() || !fullAnalysisRerunPending) {
            return;
        }
        if (!UIComparisonLoader.isMigrationGenerationSafe(settings)
                || scriptNeedsFullReload(comparisonDepth)) {
            // Keep the guard armed: the recomputed run is still unsafe,
            // either because a matched routine body was still skipped or -
            // this should not happen, since reloadForScript forces FULL -
            // because the depth this run came back with is still not FULL.
            MessageDialog.openWarning(parent.getShell(),
                    Messages.ProjectEditorDiffer_incomplete_routine_analysis_title,
                    Messages.ProjectEditorDiffer_incomplete_routine_analysis_msg);
            fullAnalysisRerunPending = false;
            return;
        }

        fullAnalysisRerunPending = false;
        List<TreeElement> restored = diffTable.getElements().stream()
                .filter(checked::contains).toList();
        if (restored.isEmpty()) {
            // Every checked object disappeared from the recomputed diff.
            return;
        }
        diffTable.setElementsChecked(restored, true, false);
        diff();
    }

    /**
     * Installs an already confirmed comparison, see
     * {@link #confirmOverrides(UIComparisonLoader.Result)}. Never interactive.
     */
    private void setInput(ILoader dbProject, ILoader dbRemote,
            TreeElement diffTree, ISettings settings, ComparisonDepth depth,
            Object remoteSnapshot) {
        this.dbProject = dbProject;
        this.dbRemote = dbRemote;
        this.diffTree = diffTree;
        this.settings = settings;
        this.comparisonDepth = depth;
        diffTable.setComparisonSettings(settings);
        diffTable.setComparisonDepth(depth);

        // Every source that contributes to settings.getIgnoreList() - the
        // project's own .pgcodekeeperignore included - has had its say by the
        // time a loaded comparison reaches this method, so this is the first
        // point that can retire a type=COLUMN rule and the last one before
        // the pane, the table and, later, the commit dialog all start reading
        // the very same list. Answers to 'depth', the depth this comparison
        // actually loaded at, not to the receive-only preference: reloadForScript()
        // leaves the preference on but forces exactly this one load to FULL,
        // and a rule must come back to full strength for that load - see
        // dropColumnRulesIfStructural's javadoc for why only columns are
        // turned off, never the object-level rules beside them.
        // The count is carried to the table rather than dropped: a rule turned
        // off here is gone from the list every widget below reads, so nothing
        // downstream can work out that it ever existed, and the columns it
        // named go into the project's files with no mark and no line beside
        // the object count to say why.
        int retiredColumnRules = ProjectIgnoreLists.dropColumnRulesIfStructural(depth, settings);
        diffTable.setRetiredColumnRules(retiredColumnRules);

        // The very list the comparison was built with, see installIgnoreList:
        // reading the files again here could answer differently and let the
        // table show objects the tree no longer holds.
        IgnoreList ignoreList = diffTree == null || settings == null
                ? null : settings.getIgnoreList();

        // The pane renders children straight from the loaded models, where
        // hidden objects are still present, so it hides by the same list.
        diffPane.setComparison(dbProject, dbRemote, ignoreList);
        diffPane.setInput(null, null);

        diffTable.setInput(dbProject, dbRemote, diffTree, ignoreList);
    }

    /**
     * Puts every ignore list this editor hides by into the settings the
     * comparison runs with, see {@link ProjectIgnoreLists}.
     *
     * @param settings       comparison settings, receive the merged rules
     * @param remoteSnapshot the remote side of this run, may carry its own lists
     */
    private void installIgnoreList(ISettings settings, Object remoteSnapshot) {
        ProjectIgnoreLists.install(settings,
                ProjectIgnoreLists.read(getProject(), oneTimePrefs, remoteSnapshot));
    }

    private void reset() {
        getChangesJobs.cancel();
        closePendingUiPublication();
        // A cancelled stale job is no longer allowed to publish its done callback.
        // Restore the action here when reset does not immediately start a replacement.
        if (getChangesAction != null) {
            getChangesAction.setEnabled(true);
        }
        rollbackComparisonPublication();
        releaseDisplayedProjectModel();
    }

    /**
     * Detaches every UI consumer from a comparison that was not accepted.
     */
    private void rollbackComparisonPublication() {
        setInput(null, null, null, null, null, null);
        sp.clearSelection();
    }

    private void resetInvalidatingProjectModel() {
        reusableProjectComparison.invalidate();
        reset();
    }

    private void closePendingUiPublication() {
        UiPublicationHandoff<
                ReusableProjectComparison.PreparedComparison>
                handoff = pendingUiPublication.getAndSet(null);
        if (handoff != null) {
            handoff.close();
        }
    }

    private void setDisplayedProjectModel(
            ReusableProjectComparison.DisplayLease lease) {
        releaseDisplayedProjectModel();
        displayedProjectModel = lease;
    }

    /**
     * Stops displaying the reused OLD model. The exclusive lease itself is
     * released only after every consumer that still reads the model has
     * finished, see
     * {@link ReusableProjectComparison.DisplayLease#retain()}.
     */
    private void releaseDisplayedProjectModel() {
        if (displayedProjectModel != null) {
            displayedProjectModel.close();
            displayedProjectModel = null;
        }
    }

    /**
     * Keeps the reused OLD model leased while a consumer outside the diff
     * table reads it. Script generation and project update outlive the diff
     * that produced them, and the retained model is not immutable.
     *
     * @return a token that releases this consumer's hold exactly once
     */
    private Runnable retainDisplayedProjectModel() {
        return displayedProjectModel == null
                ? () -> {
                    // nothing reusable is displayed
                }
                : displayedProjectModel.retain();
    }

    private void hideNotificationArea() {
        showNotificationArea(false, null);
    }

    private void showEditor(Differ differ) throws PartInitException {
        try {
            boolean inProj = false;
            String creationMode = mainPrefs.getString(DB_UPDATE_PREF.CREATE_SCRIPT_IN_PROJECT);
            // if select "YES" with toggle
            if (MessageDialogWithToggle.ALWAYS.equals(creationMode)) {
                inProj = true;
                // if not select "NO" with toggle, show choice message dialog
            } else if (!MessageDialogWithToggle.NEVER.equals(creationMode)) {
                MessageDialogWithToggle dialog = MessageDialogWithToggle.openYesNoQuestion(parent.getShell(),
                        Messages.ProjectEditorDiffer_script_creation_title,
                        Messages.ProjectEditorDiffer_script_creation_message, Messages.remember_choice_toggle, false,
                        mainPrefs, DB_UPDATE_PREF.CREATE_SCRIPT_IN_PROJECT);
                if (dialog.getReturnCode() == IDialogConstants.YES_ID) {
                    inProj = true;
                }
            }

            String content = differ.getDiffDirect();
            String filename = generateScriptName();
            if (inProj) {
                IEditorInput file = createProjectScriptFile(content, filename);
                if (loadedRemote instanceof DbInfo info) {
                    SQLEditor.saveLastDb(info, file);
                }
                getSite().getPage().openEditor(file, EDITOR.SQL);
            } else {
                FileUtilsUi.saveOpenTmpSqlEditor(content, filename, ProjectUtils.getDatabaseType(getProject()));
            }
        } catch (CoreException | IOException ex) {
            ExceptionNotifier.notifyDefault(Messages.ProjectEditorDiffer_error_creating_file, ex);
        }
    }

    private String generateScriptName() {
        String name = FileUtils.getFileDate() + " migration"; //$NON-NLS-1$
        if (loadedRemote != null) {
            name += " for " + getRemoteName(loadedRemote); //$NON-NLS-1$
        }
        return FileUtils.sanitizeFilename(name);
    }

    private IEditorInput createProjectScriptFile(String content, String filename) throws CoreException, IOException {
        Log.log(Log.LOG_INFO, Messages.ProjectEditorDiffer_creating_file.formatted(filename));
        IFolder folder = getProject().getFolder(PROJ_PATH.MIGRATION_DIR);
        if (!folder.exists()) {
            folder.create(IResource.NONE, true, null);
        }
        IFile file = folder.getFile(filename + ".sql"); //$NON-NLS-1$
        InputStream source = new ByteArrayInputStream(content.getBytes(proj.getProjectCharset()));
        file.create(source, IResource.NONE, null);
        return new FileEditorInput(getProject().getFile(file.getProjectRelativePath()));
    }

    private void showNotificationArea(boolean visible, String message) {
        showNotificationArea(visible, message, false);
    }

    private void showNotificationArea(boolean visible, String message,
            boolean allowWithoutDiff) {
        if (diffTree == null && visible && !allowWithoutDiff) {
            // since there's only one notification about diff sides changing
            // we can skip showing it if the pane is empty (has no diff loaded)
            return;
        }
        if (visible) {
            notificationArea.show(message);
        } else {
            notificationArea.hide();
        }
    }

    private void notifyProjectChanged(
            boolean activeComparisonCancelled) {
        if (!parent.isDisposed()) {
            GetChangesFailurePolicy.projectChangeNotice(
                    activeComparisonCancelled, diffTree != null)
                    .ifPresent(message -> showNotificationArea(
                            true, message,
                            activeComparisonCancelled));
            resetInvalidatingProjectModel();
        }
    }

    public void commit() {
        Log.log(Log.LOG_INFO, Messages.ProjectEditorDiffer_started_project_update);
        if ((warnCheckedElements() < 1) || !ProjectUtils.checkVersionAndWarn(getProject(), parent.getShell(), true)) {
            return;
        }

        boolean forceSave = false;
        boolean saveOverrides = false;

        if (diffTable.checkLibChange()) {
            if (proj.getPrefs().getBoolean(PROJ_PREF.LIB_SAFE_MODE, true)) {
                boolean modifyObject = MessageDialog.openQuestion(parent.getShell(),
                        Messages.ProjectEditorDiffer_lib_change_warning_title,
                        Messages.ProjectEditorDiffer_lib_change_error_message);

                if (!modifyObject) {
                    return;
                }
                forceSave = true;
                saveOverrides = true;
            } else {
                MessageDialog mb = new MessageDialog(parent.getShell(),
                        Messages.ProjectEditorDiffer_lib_change_warning_title, null,
                        Messages.ProjectEditorDiffer_lib_change_warning_message, MessageDialog.WARNING,
                        new String[] { Messages.ProjectEditorDiffer_override_privileges,
                                Messages.ProjectEditorDiffer_override_objects,
                                Messages.ProjectEditorDiffer_override_cancel },
                        0);
                int override = mb.open();
                if ((Window.OK == override) || (Window.CANCEL == override)) {
                    saveOverrides = override == Window.OK;
                } else {
                    // cancelled
                    return;
                }
            }
        }

        TreeElement treeCopy = diffTree.getCopy();
        Log.log(Log.LOG_INFO, Messages.ProjectEditorDiffer_processing_depcies_for_project_update);
        // The dependency walk and the dialog both read the possibly reused OLD
        // model, and the modal dialog pumps events that can reset this editor,
        // so the exclusive lease is held until the dialog is closed.
        //
        // Unlike diff(), no blocking full-analysis gate is needed here: the
        // project writer never orders DDL and never reads dependency sets, it
        // copies object definitions into project files. The edges missing from an
        // unanalyzed routine body can therefore only change the MEMBERSHIP of
        // the dependency set offered below, never the content written for a
        // member. What an incomplete set leaves behind is a project file that
        // is stale rather than applied anywhere, and it reappears in the next
        // comparison, so the dialog reports the gap instead of blocking on it.
        Runnable modelHold = retainDisplayedProjectModel();
        try {
            DepcyTreeExtender extender = new DepcyTreeExtender(dbProject.getDatabase(), dbRemote.getDatabase(),
                    treeCopy, settings.getAdditionalDependencies());
            Set<TreeElement> sumNewAndDelete = extender.getDepcies();

            Log.log(Log.LOG_INFO, Messages.ProjectEditorDiffer_querying_user_for_project_update);
            // display commit dialog
            //
            // The very rules this comparison hid by, not a fresh reading of the
            // same four sources: the writer of project files leaves a hidden
            // column out of a file, and a rule it learns while the comparison
            // did not - or the other way round - is a project file that does
            // not match the tree the user just agreed to.
            CommitDialog cd = new CommitDialog(parent.getShell(), sumNewAndDelete, dbProject, dbRemote, treeCopy,
                    mainPrefs, isCommitCommandAvailable, forceSave, saveOverrides, proj,
                    isDepcyExpansionIncomplete(settings, extender.isBodyDependencyTruncated()),
                    comparisonDepth, settings.getIgnoreList());
            if (cd.open() != Window.OK) {
                return;
            }
        } finally {
            modelHold.run();
        }
        callEgitCommitCommand();
    }

    /**
     * Reports whether the dependency set offered for the project update may be
     * incomplete. The truncation flag decides alone: it is a direct observation of
     * the models actually traversed, raised only when a collected closure really
     * did walk a routine whose body dependencies were suppressed. The settings are
     * deliberately not consulted. They are a proxy for the same fact, and ANDing a
     * direct observation with a proxy can only suppress a report the observation
     * legitimately raised, never add one, which is exactly the class of assumption
     * the flag was introduced to remove.
     * <p>
     * A reused project model cannot be truncated today, because
     * {@code PgProjectLoader.loadInternal} disables the skip on every routine
     * launcher once capture is enabled, but that guarantee rests on a single
     * unguarded line. The {@code PreanalyzedProjectLoader} check is no defence
     * here: it only rejects a model that still holds analysis launchers, and a
     * skipped body consumes its launcher exactly like an analyzed one, so a
     * truncated but completed model passes it unchanged. The report is
     * non-blocking and informational, so a false positive costs a line of text
     * while a false negative costs a silently stale project file.
     *
     * @param settings                effective comparison settings, kept for the
     *                                call site and intentionally not consulted
     * @param bodyDependencyTruncated whether a closure reached an unanalyzed routine
     * @return true if the dialog must report an incomplete dependency set
     */
    static boolean isDepcyExpansionIncomplete(ISettings settings, boolean bodyDependencyTruncated) {
        return bodyDependencyTruncated;
    }

    private void callEgitCommitCommand() {
        if (!isCommitCommandAvailable || !mainPrefs.getBoolean(PREF.CALL_COMMIT_COMMAND_AFTER_UPDATE)) {
            return;
        }
        try {
            getSite().getSelectionProvider().setSelection(new StructuredSelection(getProject()));
            getSite().getService(IHandlerService.class).executeCommand(COMMAND.COMMIT_COMMAND_ID, null);
        } catch (ExecutionException | NotDefinedException | NotEnabledException | NotHandledException e) {
            Log.log(Log.LOG_WARNING,
                    Messages.ProjectEditorDiffer_command_execute_error.formatted(COMMAND.COMMIT_COMMAND_ID), e);
            ExceptionNotifier.notifyDefault(Messages.ProjectEditorDiffer_failed_egit_commit, e);
        }
    }

    private void resetRemoteChanged() {
        // may be called off UI thread so check that we're still alive
        if (!parent.isDisposed()) {
            showNotificationArea(true, Messages.DiffPresentationPane_remote_changed_notification);
            reset();
        }
    }

    public void updateRemoteChanged() {
        // may be called off UI thread so check that we're still alive
        if (!parent.isDisposed()) {
            getChanges();
            showNotificationArea(true, Messages.DiffPresentationPane_remote_changed_notification);
        }
    }

    /**
     * @return number of checked elements
     */
    private int warnCheckedElements() {
        int checked = diffTable.getCheckedElementsCount();

        if (checked < 1) {
            IStructuredSelection selection = diffTable.getViewer().getStructuredSelection();
            if (selection.isEmpty()) {
                MessageDialog.openInformation(parent.getShell(), Messages.empty_selection,
                        Messages.please_check_at_least_one_row);
            } else {
                diffTable.setElementsChecked(selection.toList(), true, false);
                return selection.size();
            }
        }
        return checked;
    }

    public static void notifyDbChanged(DbInfo dbinfo) {
        String action = Activator.getDefault().getPreferenceStore().getString(PG_EDIT_PREF.EDITOR_UPDATE_ACTION);
        if (PG_EDIT_PREF.NO_ACTION.equals(action)) {
            return;
        }
        for (IWorkbenchWindow wnd : PlatformUI.getWorkbench().getWorkbenchWindows()) {
            for (IWorkbenchPage page : wnd.getPages()) {
                for (IEditorReference ref : page.getEditorReferences()) {
                    IEditorPart ed = ref.getEditor(false);
                    if (ed instanceof ProjectEditorDiffer differ) {
                        notifyDbChanged(dbinfo, differ, PG_EDIT_PREF.UPDATE.equals(action));
                    }
                }
            }
        }
    }

    private static void notifyDbChanged(DbInfo dbinfo, ProjectEditorDiffer editor, boolean update) {
        UiSync.exec(editor.parent, () -> {
            if (dbinfo.equals(editor.getCurrentDb())) {
                if (update) {
                    editor.updateRemoteChanged();
                } else {
                    editor.resetRemoteChanged();
                }
            }
        });
    }

    private static String getRemoteName(Object remote) {
        if (remote instanceof DbInfo info) {
            return info.getName();
        }
        if (remote instanceof File file) {
            return file.getName();
        }
        throw new IllegalArgumentException(Messages.ProjectEditorDiffer_remote_db_error);
    }

    private class DiffTable extends DiffTableViewer {

        private Action applyAction;
        private Action actionToProj;
        private Action actionToDb;

        private ImageDescriptor imgDescrApplyIcon;
        private ImageDescriptor imgDescrProj;
        private ImageDescriptor imgDescrDb;

        public DiffTable(Composite parent, boolean viewOnly, IStatusLineManager lineManager, Path location,
                IProject proj) {
            super(parent, viewOnly, lineManager, location, dbType, new UISettings(proj, oneTimePrefs, null));
        }

        @Override
        public void createRightSide(Composite container) {
            GridLayout layout = new GridLayout(2, false);
            layout.marginHeight = 0;
            layout.marginWidth = 0;
            container.setLayout(layout);

            Composite labelCont = new Composite(container, SWT.NONE);
            GridLayout labelLayout = new GridLayout(4, false);
            labelLayout.marginHeight = 0;
            labelLayout.marginWidth = 0;
            labelCont.setLayout(labelLayout);

            PixelConverter pc = new PixelConverter(labelCont);
            GridData gd = new GridData(SWT.END, SWT.CENTER, true, false);
            gd.horizontalIndent = pc.convertWidthInCharsToPixels(4);
            labelCont.setLayoutData(gd);

            Label l = new Label(labelCont, SWT.NONE);
            l.setText(Messages.ProjectEditorDiffer_work_with);
            l.setEnabled(false);

            // current remote

            dbStorePicker = new DbMenuStorePicker(labelCont, true, false);
            dbStorePicker.filter(dbType);
            labelCont.addMouseListener(new MouseAdapter() {

                @Override
                public void mouseDown(MouseEvent e) {
                    if (!dbStorePicker.isEnabled()) {
                        MessageDialog.openInformation(parent.getShell(),
                                Messages.DbStoreCombo_db_binding_property_title,
                                Messages.DbStoreCombo_db_binding_property);
                    }
                }
            });

            dbStorePicker.addSelectionListener(() -> {
                Object selection = dbStorePicker.getDbInfo();
                if (selection == null) {
                    selection = dbStorePicker.getPathOfFile();
                }
                ProjectEditorDiffer.this.setCurrentDb(selection);
            });

            l = new Label(labelCont, SWT.NONE);
            l.setText(Messages.ProjectEditorDiffer_apply_to);
            l.setEnabled(false);

            // change apply action
            lblApplyTo = new Label(labelCont, SWT.NONE);
            lblApplyTo.setCursor(getDisplay().getSystemCursor(SWT.CURSOR_HAND));

            createApplyActions();
            MenuManager menuMg = new MenuManager();
            menuMg.add(actionToProj);
            menuMg.add(actionToDb);

            lblApplyTo.setMenu(menuMg.createContextMenu(lblApplyTo));

            lblApplyTo.addMouseListener(MouseListener.mouseDownAdapter(e -> {
                if (e.button == 1) {
                    lblApplyTo.getMenu().setVisible(true);
                }
            }));

            final ToolBarManager mgrTblBtn = new ToolBarManager(SWT.FLAT | SWT.RIGHT);
            addBtnApplyWithMenu(container, mgrTblBtn);
            mgrTblBtn.add(new Separator());
            addBtnGetChangesWithMenu(container, mgrTblBtn);

            ToolBar toolbar = mgrTblBtn.createControl(container);
            gd = new GridData(SWT.END, SWT.CENTER, false, false);
            gd.horizontalIndent = pc.convertWidthInCharsToPixels(2);
            toolbar.setLayoutData(gd);

            // ensure toolbar is never hidden
            gd.minimumWidth = toolbar.computeSize(SWT.DEFAULT, SWT.DEFAULT).x;
        }

        private void createApplyActions() {
            actionToProj = new Action(Messages.DiffTableViewer_to_project, IAction.AS_RADIO_BUTTON) {

                @Override
                public void run() {
                    changeMigrationDireciton(true, false);
                }
            };

            actionToProj.setImageDescriptor(Activator.getRegisteredDescriptor(ProjectIcon.APP_SMALL));
            actionToDb = new Action(Messages.DiffTableViewer_to_database, IAction.AS_RADIO_BUTTON) {

                @Override
                public void run() {
                    changeMigrationDireciton(false, false);
                }
            };
            actionToDb.setImageDescriptor(Activator.getRegisteredDescriptor(ProjectIcon.DATABASE));
        }

        /**
         * Adds [Apply] button with drop-down menu, which contains main button [Apply]
         * and additional button for applying with custom settings.
         */
        private void addBtnApplyWithMenu(Composite container, ToolBarManager mgrTblBtn) {
            imgDescrApplyIcon = Activator.getRegisteredDescriptor(ProjectIcon.APPLY_TO);

            imgDescrProj = Activator.getRegisteredDescriptor(ProjectIcon.DECOR_PGCODEKEEPER);
            imgDescrDb = Activator.getRegisteredDescriptor(ProjectIcon.DECOR_DATABASE);

            applyAction = new Action("", IAction.AS_DROP_DOWN_MENU) { //$NON-NLS-1$
                @Override
                public void run() {
                    if (!diffTable.isApplyToProj()) {
                        diff();
                    } else {
                        commit();
                    }
                }
            };
            applyAction.setToolTipText(Messages.DiffTableViewer_apply_to + ' ' + Messages.DiffTableViewer_to_project);
            applyAction.setImageDescriptor(
                    new DecorationOverlayIcon(imgDescrApplyIcon, imgDescrProj, IDecoration.TOP_RIGHT));

            applyAction.setMenuCreator(new IMenuCreator() {

                private MenuManager menuMgrApplyCustom;

                @Override
                public void dispose() {
                    if (menuMgrApplyCustom != null) {
                        menuMgrApplyCustom.dispose();
                        menuMgrApplyCustom = null;
                    }
                }

                @Override
                public Menu getMenu(Control parent) {
                    if (menuMgrApplyCustom != null) {
                        menuMgrApplyCustom.dispose();
                    }

                    menuMgrApplyCustom = new MenuManager();
                    IAction applyCustomAction = new Action(Messages.DiffTableViewer_apply_to_custom) {

                        @Override
                        public void run() {
                            ApplyCustomDialog dialog = new ApplyCustomDialog(container.getShell(),
                                    new OverridablePrefs(proj.getProject(), null), dbType, oneTimePrefs);
                            if (dialog.open() == Window.OK) {
                                // 'oneTimePrefs' filled by one-time preferences
                                // will be used in 'diff()'
                                diff();
                            }
                        }
                    };

                    Action applyTitle = new Action(Messages.DiffTableViewer_apply_to) {
                    };
                    applyTitle.setEnabled(false);
                    menuMgrApplyCustom.add(applyTitle);

                    menuMgrApplyCustom.add(actionToProj);
                    menuMgrApplyCustom.add(actionToDb);
                    menuMgrApplyCustom.add(new Separator());
                    applyCustomAction.setEnabled(actionToDb.isChecked());

                    menuMgrApplyCustom.add(applyCustomAction);
                    return menuMgrApplyCustom.createContextMenu(parent);
                }

                @Override
                public Menu getMenu(Menu parent) {
                    return null;
                }
            });
            mgrTblBtn.add(applyAction);
        }

        private void addBtnGetChangesWithMenu(Composite container, ToolBarManager mgrTblBtn) {
            getChangesAction = new Action("", IAction.AS_DROP_DOWN_MENU) { //$NON-NLS-1$

                @Override
                public void run() {
                    getChanges();
                }
            };
            getChangesAction.setImageDescriptor(Activator.getRegisteredDescriptor(ProjectIcon.REFRESH));
            getChangesAction.setToolTipText(Messages.DiffTableViewer_get_changes);
            getChangesAction.setMenuCreator(new IMenuCreator() {

                private MenuManager menuMgrGetChangesCustom;

                @Override
                public void dispose() {
                    if (menuMgrGetChangesCustom != null) {
                        menuMgrGetChangesCustom.dispose();
                        menuMgrGetChangesCustom = null;
                    }
                }

                @Override
                public Menu getMenu(Control parent) {
                    if (menuMgrGetChangesCustom != null) {
                        menuMgrGetChangesCustom.dispose();
                    }

                    menuMgrGetChangesCustom = new MenuManager();
                    menuMgrGetChangesCustom.add(new Action(Messages.DiffTableViewer_get_changes_custom) {

                        @Override
                        public void run() {
                            GetChangesCustomDialog dialog = new GetChangesCustomDialog(container.getShell(),
                                    new OverridablePrefs(proj.getProject(), null), dbType, oneTimePrefs);
                            if (dialog.open() == Window.OK) {
                                // 'oneTimePrefs' filled by one-time preferences
                                // will be used in 'getChanges()'
                                getChanges();
                            }
                        }
                    });
                    menuMgrGetChangesCustom.add(new Separator());
                    DBStoreMenu dbMenu = new DBStoreMenu(menuMgrGetChangesCustom, true, false, dbType,
                            parent.getShell(), getCurrentDb());
                    dbMenu.fillDbMenu(DbXmlStore.getStore());
                    dbMenu.addSelectionListener(ProjectEditorDiffer.this::setCurrentDb);
                    return menuMgrGetChangesCustom.createContextMenu(parent);
                }

                @Override
                public Menu getMenu(Menu parent) {
                    return null;
                }
            });

            mgrTblBtn.add(getChangesAction);
        }

        public void changeMigrationDireciton(boolean isApplyToProj, boolean showWarning) {
            if (showWarning && (isApplyToProj != diffTable.isApplyToProj())) {
                String message = Messages.ProjectEditorDiffer_changed_direction_of_roll_on.formatted(
                        isApplyToProj ? Messages.ProjectEditorDiffer_project : Messages.ProjectEditorDiffer_database);

                MessageDialog.openWarning(parent.getShell(),
                        Messages.ProjectEditorDiffer_changed_direction_of_roll_on_title, message);
            }
            diffTable.setApplyToProj(isApplyToProj);
            diffTable.getViewer().refresh();
            diffTable.updateObjectsLabels();
            saveLastDirection(getProject(), isApplyToProj);
            updateWorkWith();
            actionToProj.setChecked(isApplyToProj);
            actionToDb.setChecked(!isApplyToProj);

            String message = isApplyToProj ? Messages.DiffTableViewer_to_project : Messages.DiffTableViewer_to_database;
            ImageDescriptor descr = isApplyToProj ? imgDescrProj : imgDescrDb;

            applyAction.setToolTipText(Messages.DiffTableViewer_apply_to + ' ' + message);
            applyAction.setImageDescriptor(new DecorationOverlayIcon(imgDescrApplyIcon, descr, IDecoration.TOP_RIGHT));
        }

        private void saveLastDirection(IProject project, boolean isProj) {
            IEclipsePreferences prefs = PgDbProject.getPrefs(project, false);
            if (prefs != null) {
                prefs.putBoolean(DB_BIND_PREF.LAST_DIRECTION, isProj);
                try {
                    prefs.flush();
                } catch (BackingStoreException ex) {
                    Log.log(ex);
                }
            }
        }
    }

    @Override
    public void preferenceChange(IEclipsePreferences.PreferenceChangeEvent event) {
        // Sampled here rather than inside the report: the answer is a property
        // of the thread that runs this handler, and a workspace refresh
        // re-applies the project file from a thread that is not the UI one.
        boolean fromWorkspaceNode = event.getNode() == mainPrefsNode;
        ProjectIndexConfigurationDiagnostics.INSTANCE.publishPreferenceChange(
                event.getKey(), Preferences.getChangeImpact(event.getKey()),
                fromWorkspaceNode
                        ? PreferenceNode.MAIN
                        : PreferenceNode.PROJECT,
                Display.getCurrent() != null);
        Preferences.routeChange(event.getKey(),
                this::deferProjectIndexInvalidation,
                this::deferComparisonReset);
    }

    private void deferProjectIndexInvalidation() {
        deferProjectIndexInvalidation(preferenceReactions, getProject());
    }

    /**
     * Asks for the project index configuration to be re-read once the node
     * stops changing.
     * <p>
     * Nothing is decided here. The event arrives while the platform is applying
     * a preference file key by key, and it is fired from inside the removal of
     * a key that is about to be put back unchanged: read at this instant, the
     * node reports an inherited default and the fingerprint guard sees a change
     * nobody made. What the configuration became is a question only the settled
     * node can answer.
     * <p>
     * Which of the two nodes the change came from is not asked either, and used
     * to be. A change of the workspace node was ignored for a project of any
     * type but PostgreSQL, because such a project had no index for a global
     * setting to retire. It has one now - {@code ProjectIndexSupportPolicy}
     * decides that and no longer decides it by dialect - and every setting the
     * index answers to is offered to every dialect, so the node a change
     * arrives from says nothing about whether it matters. What it costs when
     * it does not is one fingerprint: the invalidator compares before it
     * retires anything, so a change that resolved to the same configuration
     * ends there.
     *
     * @param reactions the coalescer that holds this reaction until the node
     *                  settles
     * @param project   project whose index configuration is to be re-read
     * @return whether this request opened a settling window rather than
     *         restarting one already open
     */
    static boolean deferProjectIndexInvalidation(
            PreferenceChangeCoalescer reactions, IProject project) {
        boolean opened = reactions.request(
                PreferenceReaction.PROJECT_INDEX,
                () -> PgDbParser.invalidateProjectIndexConfiguration(project,
                        ProjectIndexConfigurationDiagnostics
                                .ORIGIN_EDITOR_PREFERENCE));
        if (opened) {
            // One line per burst rather than one per key: a re-read of the
            // project file routes every key it holds, and the bounded
            // telemetry buffer is the only place this evidence survives.
            ProjectIndexConfigurationDiagnostics.INSTANCE.publishDeferral(
                    ProjectIndexConfigurationDiagnostics
                            .ORIGIN_EDITOR_PREFERENCE,
                    PREFERENCE_SETTLE_DELAY_MILLIS);
        }
        return opened;
    }

    /**
     * Asks for the displayed comparison to be retired once the node stops
     * changing. Unlike the index this reaction has no guard of its own and used
     * to run once per key of a re-applied file, tearing the diff table down
     * fourteen times over for a file nobody edited.
     */
    private void deferComparisonReset() {
        preferenceReactions.request(PreferenceReaction.COMPARISON,
                this::resetProjectModelOnUiThread);
    }

    /**
     * Retires the displayed comparison from the display thread. The reaction
     * runs on a job, and every consumer this reset detaches is a widget --
     * which is how the same handler threw {@code SWTException} while running on
     * the thread of a workspace refresh.
     */
    private void resetProjectModelOnUiThread() {
        Composite editorParent = parent;
        if (editorParent == null || editorParent.isDisposed()) {
            return;
        }
        UiSync.tryExec(editorParent, () -> {
            if (!editorParent.isDisposed()) {
                resetInvalidatingProjectModel();
            }
        });
    }

    private static PreferenceChangeCoalescer.Cancellation
            schedulePreferenceReaction(Runnable window, long delayMillis) {
        // A settling window carries no progress and reaches nobody, so it is
        // named for a log rather than for a person and kept out of the
        // progress surfaces.
        Job job = new Job("pgCodeKeeper preference change settling") { //$NON-NLS-1$

            @Override
            protected IStatus run(IProgressMonitor monitor) {
                window.run();
                return Status.OK_STATUS;
            }
        };
        job.setSystem(true);
        job.schedule(delayMillis);
        return job::cancel;
    }
}
