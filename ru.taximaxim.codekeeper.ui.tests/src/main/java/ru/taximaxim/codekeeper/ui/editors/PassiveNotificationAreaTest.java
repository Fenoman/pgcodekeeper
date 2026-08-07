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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.PlatformUI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

class PassiveNotificationAreaTest {

    @Test
    void showUpdateAndHideManagePresentation() {
        runOnUiThread(display -> {
            Shell shell = new Shell(display);
            try {
                shell.setLayout(new GridLayout());
                Group notification = new Group(shell, SWT.NONE);
                notification.setLayout(new GridLayout());
                GridData notificationData = new GridData(GridData.FILL_HORIZONTAL);
                notificationData.exclude = true;
                notification.setLayoutData(notificationData);
                notification.setVisible(false);
                Label message = new Label(notification, SWT.NONE);
                Link refresh = new Link(notification, SWT.NONE);
                refresh.setText("<a>Refresh</a>"); //$NON-NLS-1$

                PassiveNotificationArea area = new PassiveNotificationArea(
                        shell, notification, message);
                shell.pack();
                shell.open();

                area.show("changed"); //$NON-NLS-1$
                assertEquals("changed", message.getText()); //$NON-NLS-1$
                assertTrue(notification.isVisible());
                assertFalse(notificationData.exclude);

                area.show("updated"); //$NON-NLS-1$
                assertEquals("updated", message.getText()); //$NON-NLS-1$
                assertTrue(notification.isVisible());
                assertFalse(notificationData.exclude);

                area.show(null);
                assertEquals("updated", message.getText()); //$NON-NLS-1$
                assertTrue(notification.isVisible());
                assertFalse(notificationData.exclude);

                area.hide();
                assertFalse(notification.isVisible());
                assertTrue(notificationData.exclude);
            } finally {
                shell.dispose();
            }
        });
    }

    @Test
    @DisabledOnOs(value = OS.MAC,
            disabledReason = "Background Cocoa test runners cannot own native keyboard focus")
    void showUpdateAndHideKeepExistingFocus() {
        runOnUiThread(display -> {
            Shell shell = new Shell(display);
            try {
                shell.setLayout(new GridLayout());
                Text sentinel = new Text(shell, SWT.SINGLE | SWT.BORDER);
                sentinel.setText("sentinel"); //$NON-NLS-1$

                Group notification = new Group(shell, SWT.NONE);
                notification.setLayout(new GridLayout());
                GridData notificationData = new GridData(GridData.FILL_HORIZONTAL);
                notificationData.exclude = true;
                notification.setLayoutData(notificationData);
                notification.setVisible(false);
                Label message = new Label(notification, SWT.NONE);
                Link refresh = new Link(notification, SWT.NONE);
                refresh.setText("<a>Refresh</a>"); //$NON-NLS-1$

                PassiveNotificationArea area = new PassiveNotificationArea(
                        shell, notification, message);
                shell.pack();
                shell.open();
                shell.setActive();
                shell.forceActive();
                dispatchPendingEvents(display);
                assertTrue(sentinel.forceFocus());
                dispatchPendingEvents(display);
                assertSame(sentinel, display.getFocusControl());

                area.show("changed"); //$NON-NLS-1$
                assertSame(sentinel, display.getFocusControl());

                area.show("updated"); //$NON-NLS-1$
                assertSame(sentinel, display.getFocusControl());

                area.show(null);
                assertSame(sentinel, display.getFocusControl());

                area.hide();
                assertSame(sentinel, display.getFocusControl());
            } finally {
                shell.dispose();
            }
        });
    }

    private static void runOnUiThread(Consumer<Display> action) {
        Display display = PlatformUI.getWorkbench().getDisplay();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        display.syncExec(() -> {
            try {
                action.accept(display);
            } catch (Throwable ex) {
                failure.set(ex);
            }
        });
        if (failure.get() != null) {
            throw new AssertionError("SWT assertion failed", failure.get()); //$NON-NLS-1$
        }
    }

    private static void dispatchPendingEvents(Display display) {
        while (display.readAndDispatch()) {
            // Drain activation events before asserting focus.
        }
    }
}
