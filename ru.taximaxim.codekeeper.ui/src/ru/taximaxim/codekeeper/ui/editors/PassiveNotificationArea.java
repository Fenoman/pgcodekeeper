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

import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;

final class PassiveNotificationArea {

    private final Composite parent;
    private final Group container;
    private final Label message;

    PassiveNotificationArea(Composite parent, Group container, Label message) {
        this.parent = parent;
        this.container = container;
        this.message = message;
    }

    void show(String text) {
        if (text != null) {
            message.setText(text);
            container.pack();
        }
        setVisible(true);
    }

    void hide() {
        setVisible(false);
    }

    private void setVisible(boolean visible) {
        ((GridData) container.getLayoutData()).exclude = !visible;
        container.setVisible(visible);
        parent.layout();
    }
}
