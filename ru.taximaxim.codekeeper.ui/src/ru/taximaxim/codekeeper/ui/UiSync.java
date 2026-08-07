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

import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Widget;

public final class UiSync {

    public static void exec(Widget w, Runnable r) {
        tryExec(w, r);
    }

    /**
     * Tries to enqueue work on the display that owns the widget.
     *
     * @return {@code false} when the widget or its display was already
     *         disposed before the callback could be enqueued
     */
    public static boolean tryExec(Widget w, Runnable r) {
        return tryExec(w, r, null);
    }

    /**
     * Tries to enqueue work on the display that owns the widget and reports a
     * callback that could not be enqueued.
     *
     * @param ifDropped runnable invoked when the callback was not enqueued;
     *                  may be {@code null}
     * @return {@code false} when the widget or its display was already
     *         disposed before the callback could be enqueued
     */
    public static boolean tryExec(Widget w, Runnable r, Runnable ifDropped) {
        try {
            return tryExec(w.getDisplay(), r, ifDropped);
        } catch (SWTException ex) {
            if (ex.code != SWT.ERROR_WIDGET_DISPOSED) {
                throw ex;
            }
            runQuietly(ifDropped);
            return false;
        }
    }

    public static void exec(Display d, Runnable r) {
        tryExec(d, r);
    }

    /**
     * Tries to enqueue work on a display.
     *
     * @return {@code false} when the display was already disposed before the
     *         callback could be enqueued
     */
    public static boolean tryExec(Display d, Runnable r) {
        return tryExec(d, r, null);
    }

    /**
     * Tries to enqueue work on a display and reports a callback that could not
     * be enqueued.
     * <p>
     * A display that is disposed after accepting a callback drops it without
     * running it, and SWT allows disposal hooks to be registered only from the
     * display thread. Callers that must be notified in that case have to
     * terminate their own state when their part is disposed, see
     * {@code GetChangesRunLifecycle#abandon()}.
     *
     * @param ifDropped runnable invoked when the callback was not enqueued;
     *                  may be {@code null}
     * @return {@code false} when the display was already disposed before the
     *         callback could be enqueued
     */
    public static boolean tryExec(Display d, Runnable r, Runnable ifDropped) {
        try {
            if (d.isDisposed()) {
                runQuietly(ifDropped);
                return false;
            }
            d.asyncExec(r);
            return true;
        } catch (SWTException ex) {
            if (ex.code != SWT.ERROR_DEVICE_DISPOSED) {
                throw ex;
            }
            runQuietly(ifDropped);
            return false;
        }
    }

    private static void runQuietly(Runnable r) {
        if (r == null) {
            return;
        }
        try {
            r.run();
        } catch (RuntimeException ex) {
            Log.log(ex);
        }
    }

    private UiSync() {
    }
}
