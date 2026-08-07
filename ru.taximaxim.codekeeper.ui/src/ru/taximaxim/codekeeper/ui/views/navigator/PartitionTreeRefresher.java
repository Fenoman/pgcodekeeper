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

import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.navigator.CommonNavigator;

import ru.taximaxim.codekeeper.ui.Activator;
import ru.taximaxim.codekeeper.ui.Log;
import ru.taximaxim.codekeeper.ui.UIConsts.PREF;

/**
 * Makes the two tree settings take effect when they are changed.
 *
 * <p>Without this they do not appear to work at all. A tree viewer asks its
 * content provider once and keeps the answer; nothing about changing a
 * preference produces a resource change, so an expanded folder goes on showing
 * the children it was given until it is collapsed and expanded again. That is
 * a bad enough first impression on a setting whose whole purpose is visible -
 * and it matters most for the nesting one, which is off by default, so the very
 * first thing anyone does with it is turn it on.</p>
 *
 * <p>Started and stopped by the bundle rather than by whoever happens to read
 * the setting first, so that turning a setting on before the project tree has
 * ever been drawn is not a special case.</p>
 */
public final class PartitionTreeRefresher {

    private static IPropertyChangeListener listener;

    private PartitionTreeRefresher() {
    }

    public static synchronized void start() {
        Activator activator = Activator.getDefault();
        if (listener != null || activator == null) {
            return;
        }
        listener = PartitionTreeRefresher::onPreferenceChange;
        activator.getPreferenceStore().addPropertyChangeListener(listener);
    }

    public static synchronized void stop() {
        Activator activator = Activator.getDefault();
        if (listener != null && activator != null) {
            activator.getPreferenceStore().removePropertyChangeListener(listener);
        }
        listener = null;
    }

    private static void onPreferenceChange(PropertyChangeEvent event) {
        String key = event.getProperty();
        if (!PREF.NEST_PARTITIONS_UNDER_PARENT.equals(key)
                && !PREF.GROUP_PARTITIONS_IN_TREE.equals(key)) {
            return;
        }
        refreshNavigators();
    }

    /**
     * Refreshes every Common Navigator that has actually been created. Asked
     * for by name would be narrower and wrong: the same content extensions are
     * bound to whatever navigator the user has open, and refreshing a viewer
     * that shows none of them costs a walk of what is already on screen.
     */
    private static void refreshNavigators() {
        if (!PlatformUI.isWorkbenchRunning()) {
            return;
        }
        Display display = PlatformUI.getWorkbench().getDisplay();
        if (display == null || display.isDisposed()) {
            return;
        }
        display.asyncExec(() -> {
            try {
                for (IWorkbenchWindow window : PlatformUI.getWorkbench()
                        .getWorkbenchWindows()) {
                    for (IWorkbenchPage page : window.getPages()) {
                        refreshNavigatorsOf(page);
                    }
                }
            } catch (Exception e) {
                // a setting is not worth an error dialog; the tree picks the
                // new one up the next time it asks
                Log.log(Log.LOG_WARNING,
                        "Could not refresh the project tree", e); //$NON-NLS-1$
            }
        });
    }

    private static void refreshNavigatorsOf(IWorkbenchPage page) {
        for (IViewReference reference : page.getViewReferences()) {
            // false on purpose: a view that was never opened has nothing to
            // refresh, and creating it here would be a side effect of a setting
            IViewPart view = reference.getView(false);
            if (view instanceof CommonNavigator navigator
                    && navigator.getCommonViewer() != null
                    && !navigator.getCommonViewer().getTree().isDisposed()) {
                navigator.getCommonViewer().refresh();
            }
        }
    }
}
