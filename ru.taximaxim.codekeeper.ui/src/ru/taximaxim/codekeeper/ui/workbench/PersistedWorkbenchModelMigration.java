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
package ru.taximaxim.codekeeper.ui.workbench;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import org.eclipse.e4.ui.model.application.MApplication;
import org.eclipse.e4.ui.model.application.ui.MElementContainer;
import org.eclipse.e4.ui.model.application.ui.MSnippetContainer;
import org.eclipse.e4.ui.model.application.ui.MUIElement;
import org.eclipse.e4.ui.model.application.ui.advanced.MPerspective;
import org.eclipse.e4.ui.model.application.ui.advanced.MPlaceholder;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.model.application.ui.basic.MWindow;

import ru.taximaxim.codekeeper.ui.UIConsts;

final class PersistedWorkbenchModelMigration {

    static final String JDT_BUNDLE = "org.eclipse.jdt.ui"; //$NON-NLS-1$
    static final String JAVA_PERSPECTIVE =
            "org.eclipse.jdt.ui.JavaPerspective"; //$NON-NLS-1$
    static final String MYLYN_BUNDLE = "org.eclipse.mylyn.tasks.ui"; //$NON-NLS-1$
    static final String TASK_LIST =
            "org.eclipse.mylyn.tasks.ui.views.tasks"; //$NON-NLS-1$

    record MigrationResult(int removedJavaElements,
            int removedTaskElements) {

        static final MigrationResult NONE = new MigrationResult(0, 0);
    }

    static MigrationResult migrate(MApplication application,
            Predicate<String> bundleAvailable) {
        Objects.requireNonNull(application, "application"); //$NON-NLS-1$
        Objects.requireNonNull(bundleAvailable, "bundleAvailable"); //$NON-NLS-1$

        boolean removeJava = !bundleAvailable.test(JDT_BUNDLE);
        boolean removeTaskList = !bundleAvailable.test(MYLYN_BUNDLE);
        if (!removeJava && !removeTaskList) {
            return MigrationResult.NONE;
        }

        int taskElements = 0;
        if (removeTaskList) {
            taskElements += new ModelTraversal(
                    (element, location) -> element instanceof MPlaceholder
                            && isTaskListId(element.getElementId()))
                    .remove(application);
            taskElements += new ModelTraversal(
                    (element, location) -> location == Location.SHARED
                            && element instanceof MPart
                            && isTaskListId(element.getElementId()))
                    .remove(application);
        }

        int javaElements = removeJava
                ? new ModelTraversal((element, location) ->
                        element instanceof MPerspective
                        && isJavaPerspectiveId(element.getElementId()))
                        .remove(application)
                : 0;

        return javaElements == 0 && taskElements == 0
                ? MigrationResult.NONE
                : new MigrationResult(javaElements, taskElements);
    }

    static boolean isJavaPerspectiveId(String elementId) {
        return JAVA_PERSPECTIVE.equals(elementId)
                || elementId != null
                && elementId.startsWith(JAVA_PERSPECTIVE + '.');
    }

    static boolean isTaskListId(String elementId) {
        return TASK_LIST.equals(elementId);
    }

    private enum Location {
        CHILDREN,
        WINDOWS,
        SNIPPETS,
        SHARED
    }

    @FunctionalInterface
    private interface RemovalPredicate {

        boolean test(MUIElement element, Location location);
    }

    private static final class ModelTraversal {

        private final RemovalPredicate removalPredicate;
        private final Set<MUIElement> visited = Collections.newSetFromMap(
                new IdentityHashMap<>());
        private int removed;

        private ModelTraversal(RemovalPredicate removalPredicate) {
            this.removalPredicate = removalPredicate;
        }

        private int remove(MApplication application) {
            visit(application);
            return removed;
        }

        private void visit(MUIElement element) {
            if (!visited.add(element) || element instanceof MPlaceholder) {
                return;
            }

            if (element instanceof MElementContainer<?> container) {
                visitList(container.getChildren(), Location.CHILDREN,
                        container);
            }
            if (element instanceof MSnippetContainer snippets) {
                visitList(snippets.getSnippets(), Location.SNIPPETS, null);
            }
            if (element instanceof MWindow window) {
                visitList(window.getWindows(), Location.WINDOWS, null);
                visitList(window.getSharedElements(), Location.SHARED, null);
            }
            if (element instanceof MPerspective perspective) {
                visitList(perspective.getWindows(), Location.WINDOWS, null);
            }
        }

        private void visitList(List<? extends MUIElement> elements,
                Location location, MElementContainer<?> owner) {
            for (MUIElement element : new ArrayList<>(elements)) {
                if (removalPredicate.test(element, location)) {
                    selectReplacementBeforeRemoval(owner, element);
                    if (elements.remove(element)) {
                        removed++;
                    }
                } else {
                    visit(element);
                }
            }
        }

        private static void selectReplacementBeforeRemoval(
                MElementContainer<?> owner, MUIElement removedElement) {
            if (!(removedElement instanceof MPerspective)
                    || owner == null
                    || owner.getSelectedElement() != removedElement) {
                return;
            }

            MPerspective replacement = owner.getChildren().stream()
                    .filter(MPerspective.class::isInstance)
                    .map(MPerspective.class::cast)
                    .filter(element -> UIConsts.PERSPECTIVE.MAIN.equals(
                            element.getElementId()))
                    .findFirst()
                    .orElse(null);
            setSelectedElement(owner, replacement);
        }

        @SuppressWarnings({ "rawtypes", "unchecked" })
        private static void setSelectedElement(MElementContainer<?> owner,
                MPerspective replacement) {
            ((MElementContainer) owner).setSelectedElement(replacement);
        }
    }

    private PersistedWorkbenchModelMigration() {
    }
}
