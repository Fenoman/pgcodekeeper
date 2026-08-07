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
package ru.taximaxim.codekeeper.ui.prefs;

import java.nio.file.Path;
import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

import ru.taximaxim.codekeeper.ui.UiSync;
import ru.taximaxim.codekeeper.ui.localizations.Messages;
import ru.taximaxim.codekeeper.ui.settings.CatalogCacheStorage;
import ru.taximaxim.codekeeper.ui.settings.AnalysisCacheStorage;
import ru.taximaxim.codekeeper.ui.settings.CatalogCacheStorage.ClearResult;

/**
 * Preference-page block that manages the persistent PostgreSQL catalog cache.
 * <p>
 * The cache is written by every comparison and pruned automatically, but until
 * now nothing showed where it lives or how large it grew, and nothing could
 * drop it without closing the application and deleting workspace folders by
 * hand. Both the measurement and the deletion walk a tree that may hold
 * gigabytes, so both run in a {@link Job} and never on the UI thread.
 */
final class CatalogCachePrefGroup {

    private static final int PATH_WIDTH_HINT = 320;

    private final Path cacheRoot;
    private final Path analysisRoot;
    private final List<String> sizeUnits;

    private final Group group;
    private final Text pathText;
    private final Label sizeLabel;
    private final Label analysisSizeLabel;
    private final Label statusLabel;
    private final Button clearButton;

    private volatile Job sizeJob;
    private volatile Job clearJob;

    CatalogCachePrefGroup(Composite parent, int horizontalSpan) {
        this.cacheRoot = CatalogCacheStorage.root();
        this.analysisRoot = AnalysisCacheStorage.root().orElse(null);
        this.sizeUnits = CatalogCacheStorage.parseUnits(
                Messages.GeneralPrefPage_catalog_cache_size_units);

        group = new Group(parent, SWT.NONE);
        group.setText(Messages.GeneralPrefPage_catalog_cache_group);
        group.setLayout(new GridLayout(2, false));
        group.setLayoutData(new GridData(SWT.FILL, SWT.DEFAULT, true, false,
                horizontalSpan, 1));
        group.setToolTipText(Messages.GeneralPrefPage_catalog_cache_tooltip);

        new Label(group, SWT.NONE)
                .setText(Messages.GeneralPrefPage_catalog_cache_location);
        pathText = new Text(group, SWT.READ_ONLY | SWT.BORDER);
        var pathData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        pathData.widthHint = PATH_WIDTH_HINT;
        pathText.setLayoutData(pathData);
        pathText.setText(cacheRoot == null
                ? Messages.GeneralPrefPage_catalog_cache_unavailable
                : cacheRoot.toString());

        new Label(group, SWT.NONE)
                .setText(Messages.GeneralPrefPage_catalog_cache_size);
        sizeLabel = new Label(group, SWT.NONE);
        sizeLabel.setLayoutData(
                new GridData(SWT.FILL, SWT.CENTER, true, false));

        // The analyzed-model cache is written next to the catalog cache and by
        // the same comparisons, so it is accounted for and cleared here too.
        new Label(group, SWT.NONE)
                .setText(Messages.GeneralPrefPage_analysis_cache_size);
        analysisSizeLabel = new Label(group, SWT.NONE);
        analysisSizeLabel.setLayoutData(
                new GridData(SWT.FILL, SWT.CENTER, true, false));

        clearButton = new Button(group, SWT.PUSH);
        clearButton.setText(Messages.GeneralPrefPage_catalog_cache_clear);
        clearButton.setLayoutData(
                new GridData(SWT.BEGINNING, SWT.CENTER, false, false, 2, 1));
        clearButton.addSelectionListener(new SelectionAdapter() {

            @Override
            public void widgetSelected(SelectionEvent e) {
                clear();
            }
        });

        statusLabel = new Label(group, SWT.WRAP);
        var statusData = new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1);
        statusData.widthHint = PATH_WIDTH_HINT;
        statusLabel.setLayoutData(statusData);
        statusLabel.setText(""); //$NON-NLS-1$

        if (cacheRoot == null) {
            clearButton.setEnabled(false);
            sizeLabel.setText(Messages.GeneralPrefPage_catalog_cache_unavailable);
            analysisSizeLabel.setText(
                    Messages.GeneralPrefPage_catalog_cache_unavailable);
        } else {
            refreshSize();
        }
    }

    /**
     * Cancels the background work. A preference page may be closed long before
     * a multi-gigabyte tree has been walked.
     */
    void dispose() {
        cancel(sizeJob);
        cancel(clearJob);
        sizeJob = null;
        clearJob = null;
    }

    private static void cancel(Job job) {
        if (job != null) {
            job.cancel();
        }
    }

    private void refreshSize() {
        cancel(sizeJob);
        sizeLabel.setText(
                Messages.GeneralPrefPage_catalog_cache_size_calculating);
        Job job = new Job(Messages.GeneralPrefPage_catalog_cache_size_job) {

            @Override
            protected IStatus run(IProgressMonitor monitor) {
                long bytes;
                long analysisBytes;
                try {
                    bytes = CatalogCacheStorage.sizeInBytes(
                            cacheRoot, monitor::isCanceled);
                    analysisBytes = analysisRoot == null ? 0
                            : AnalysisCacheStorage.sizeInBytes(
                                    analysisRoot, monitor::isCanceled);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return Status.CANCEL_STATUS;
                }
                showSize(bytes, analysisBytes);
                return Status.OK_STATUS;
            }
        };
        job.setSystem(true);
        sizeJob = job;
        job.schedule();
    }

    private void showSize(long bytes, long analysisBytes) {
        UiSync.tryExec(sizeLabel, () -> {
            if (!sizeLabel.isDisposed()) {
                sizeLabel.setText(
                        CatalogCacheStorage.formatBytes(bytes, sizeUnits));
            }
            if (!analysisSizeLabel.isDisposed()) {
                analysisSizeLabel.setText(CatalogCacheStorage.formatBytes(
                        analysisBytes, sizeUnits));
            }
            if (!sizeLabel.isDisposed()) {
                sizeLabel.getParent().layout();
            }
        });
    }

    private void clear() {
        if (cacheRoot == null || clearJob != null) {
            return;
        }
        boolean confirmed = MessageDialog.openConfirm(group.getShell(),
                Messages.GeneralPrefPage_catalog_cache_clear_confirm_title,
                Messages.GeneralPrefPage_catalog_cache_clear_confirm_message
                        .formatted(confirmedRoots()));
        if (!confirmed) {
            return;
        }
        clearButton.setEnabled(false);
        statusLabel.setText(""); //$NON-NLS-1$
        Job job = new Job(Messages.GeneralPrefPage_catalog_cache_clear_job) {

            @Override
            protected IStatus run(IProgressMonitor monitor) {
                ClearResult result = CatalogCacheStorage.clear(cacheRoot);
                long analysisFreed = analysisRoot == null ? 0
                        : AnalysisCacheStorage.clear(analysisRoot);
                showClearResult(result, analysisFreed);
                return Status.OK_STATUS;
            }
        };
        job.setUser(true);
        clearJob = job;
        job.schedule();
    }

    private String confirmedRoots() {
        return analysisRoot == null ? cacheRoot.toString()
                : cacheRoot + System.lineSeparator() + analysisRoot;
    }

    private void showClearResult(ClearResult result, long analysisFreed) {
        UiSync.tryExec(statusLabel, () -> {
            clearJob = null;
            if (statusLabel.isDisposed()) {
                return;
            }
            statusLabel.setText(describe(result)
                    + System.lineSeparator()
                    + Messages.GeneralPrefPage_analysis_cache_cleared.formatted(
                            CatalogCacheStorage.formatBytes(
                                    analysisFreed, sizeUnits)));
            statusLabel.getParent().layout();
            if (!clearButton.isDisposed()) {
                clearButton.setEnabled(true);
            }
            if (!sizeLabel.isDisposed()) {
                refreshSize();
            }
        }, () -> clearJob = null);
    }

    private String describe(ClearResult result) {
        if (result.busy()) {
            return Messages.GeneralPrefPage_catalog_cache_clear_busy;
        }
        if (result.isEmpty()) {
            return Messages.GeneralPrefPage_catalog_cache_clear_empty;
        }
        String freed = CatalogCacheStorage.formatBytes(
                result.freedBytes(), sizeUnits);
        if (result.keptTargets() > 0) {
            return Messages.GeneralPrefPage_catalog_cache_clear_kept
                    .formatted(freed, result.removedTargets(),
                            result.keptTargets());
        }
        return Messages.GeneralPrefPage_catalog_cache_clear_done
                .formatted(freed, result.removedTargets());
    }
}
