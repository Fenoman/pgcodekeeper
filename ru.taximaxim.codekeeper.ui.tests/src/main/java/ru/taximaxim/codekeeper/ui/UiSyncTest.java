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
package ru.taximaxim.codekeeper.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Widget;
import org.junit.jupiter.api.Test;

class UiSyncTest {

    @Test
    void tryExecReportsDisposedWidgetBeforeEnqueue() {
        Widget widget = mock(Widget.class);
        when(widget.getDisplay())
                .thenThrow(new SWTException(SWT.ERROR_WIDGET_DISPOSED));

        assertFalse(UiSync.tryExec(widget, () -> {
            throw new AssertionError("callback must not run");
        }));
    }

    @Test
    void tryExecReportsDisposedDisplayDuringEnqueue() {
        Display display = mock(Display.class);
        doThrow(new SWTException(SWT.ERROR_DEVICE_DISPOSED))
                .when(display).asyncExec(org.mockito.ArgumentMatchers.any());

        assertFalse(UiSync.tryExec(display, () -> {
            throw new AssertionError("callback must not run");
        }));
    }

    @Test
    void tryExecDoesNotHideUnexpectedSwtFailures() {
        Display display = mock(Display.class);
        doThrow(new SWTException(SWT.ERROR_THREAD_INVALID_ACCESS))
                .when(display).asyncExec(org.mockito.ArgumentMatchers.any());

        assertThrows(SWTException.class,
                () -> UiSync.tryExec(display, () -> {
                    // not invoked
                }));
    }
}
