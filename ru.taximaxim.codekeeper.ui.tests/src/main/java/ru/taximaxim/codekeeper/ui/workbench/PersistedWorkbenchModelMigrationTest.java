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

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;

import org.eclipse.e4.ui.model.application.MApplication;
import org.eclipse.e4.ui.model.application.MApplicationFactory;
import org.eclipse.e4.ui.model.application.ui.MUIElement;
import org.eclipse.e4.ui.model.application.ui.advanced.MAdvancedFactory;
import org.eclipse.e4.ui.model.application.ui.advanced.MPerspective;
import org.eclipse.e4.ui.model.application.ui.advanced.MPerspectiveStack;
import org.eclipse.e4.ui.model.application.ui.advanced.MPlaceholder;
import org.eclipse.e4.ui.model.application.ui.basic.MBasicFactory;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.model.application.ui.basic.MPartSashContainer;
import org.eclipse.e4.ui.model.application.ui.basic.MPartStack;
import org.eclipse.e4.ui.model.application.ui.basic.MWindow;
import org.junit.jupiter.api.Test;

import ru.taximaxim.codekeeper.ui.UIConsts;
import ru.taximaxim.codekeeper.ui.workbench.PersistedWorkbenchModelMigration.MigrationResult;

class PersistedWorkbenchModelMigrationTest {

    private static final String SUPPORTED_PERSPECTIVE = "supported.perspective"; //$NON-NLS-1$
    private static final String SUPPORTED_PART = "supported.part"; //$NON-NLS-1$

    @Test
    void absentBundlesRemoveStaleElementsAndPreserveSupportedState() {
        MApplication application = application();
        MWindow window = window("main.window"); //$NON-NLS-1$
        application.getChildren().add(window);

        MPerspectiveStack perspectives = perspectiveStack("perspectives"); //$NON-NLS-1$
        MPerspective java = perspective(
                PersistedWorkbenchModelMigration.JAVA_PERSPECTIVE);
        MPerspective javaInstance = perspective(
                PersistedWorkbenchModelMigration.JAVA_PERSPECTIVE
                + ".instance"); //$NON-NLS-1$
        MPerspective supported = perspective(SUPPORTED_PERSPECTIVE);
        perspectives.getChildren().addAll(List.of(
                java, javaInstance, supported));
        window.getChildren().add(perspectives);

        MPerspective javaSnippet = perspective(
                PersistedWorkbenchModelMigration.JAVA_PERSPECTIVE
                + ".snippet"); //$NON-NLS-1$
        MPerspective supportedSnippet = perspective("supported.snippet"); //$NON-NLS-1$
        application.getSnippets().addAll(List.of(
                javaSnippet, supportedSnippet));

        MPart taskPart = part(PersistedWorkbenchModelMigration.TASK_LIST);
        MPart supportedPart = part(SUPPORTED_PART);
        window.getSharedElements().addAll(List.of(taskPart, supportedPart));
        MPartStack views = partStack("views"); //$NON-NLS-1$
        MPlaceholder taskPlaceholder = placeholder(
                PersistedWorkbenchModelMigration.TASK_LIST, taskPart);
        MPlaceholder supportedPlaceholder = placeholder(
                "supported.view", supportedPart); //$NON-NLS-1$
        views.getChildren().addAll(List.of(
                taskPlaceholder, supportedPlaceholder));
        supported.getChildren().add(views);

        MigrationResult result = PersistedWorkbenchModelMigration.migrate(
                application, bundleId -> false);

        assertEquals(new MigrationResult(3, 2), result);
        assertEquals(List.of(supported), perspectives.getChildren());
        assertEquals(List.of(supportedSnippet), application.getSnippets());
        assertEquals(List.of(supportedPlaceholder), views.getChildren());
        assertEquals(List.of(supportedPart), window.getSharedElements());
    }

    @Test
    void selectedJavaPerspectiveSelectsExactPgCodeKeeperSibling() {
        MApplication application = application();
        MWindow window = window("main.window"); //$NON-NLS-1$
        application.getChildren().add(window);
        MPerspectiveStack stack = perspectiveStack("perspectives"); //$NON-NLS-1$
        window.getChildren().add(stack);
        MPerspective other = perspective(SUPPORTED_PERSPECTIVE);
        MPerspective main = perspective(UIConsts.PERSPECTIVE.MAIN);
        MPerspective java = perspective(
                PersistedWorkbenchModelMigration.JAVA_PERSPECTIVE);
        stack.getChildren().addAll(List.of(other, main, java));
        stack.setSelectedElement(java);

        MigrationResult result = PersistedWorkbenchModelMigration.migrate(
                application, bundleId -> false);

        assertEquals(new MigrationResult(1, 0), result);
        assertSame(main, stack.getSelectedElement());
    }

    @Test
    void selectedJavaPerspectiveWithoutPgCodeKeeperSiblingBecomesNull() {
        MApplication application = application();
        MWindow window = window("main.window"); //$NON-NLS-1$
        application.getChildren().add(window);
        MPerspectiveStack stack = perspectiveStack("perspectives"); //$NON-NLS-1$
        window.getChildren().add(stack);
        MPerspective other = perspective(SUPPORTED_PERSPECTIVE);
        MPerspective java = perspective(
                PersistedWorkbenchModelMigration.JAVA_PERSPECTIVE);
        stack.getChildren().addAll(List.of(other, java));
        stack.setSelectedElement(java);

        PersistedWorkbenchModelMigration.migrate(
                application, bundleId -> false);

        assertNull(stack.getSelectedElement());
        assertFalse(stack.getChildren().contains(java));
        assertSame(other, stack.getChildren().getFirst());
    }

    @Test
    void bundleAvailabilityControlsJavaAndMylynPassesIndependently() {
        assertAvailability(Set.of(), new MigrationResult(1, 2),
                false, false);
        assertAvailability(
                Set.of(PersistedWorkbenchModelMigration.JDT_BUNDLE),
                new MigrationResult(0, 2), true, false);
        assertAvailability(
                Set.of(PersistedWorkbenchModelMigration.MYLYN_BUNDLE),
                new MigrationResult(1, 0), false, true);
        assertAvailability(Set.of(
                PersistedWorkbenchModelMigration.JDT_BUNDLE,
                PersistedWorkbenchModelMigration.MYLYN_BUNDLE),
                MigrationResult.NONE, true, true);
    }

    @Test
    void boundaryIdsAreRetained() {
        assertFalse(PersistedWorkbenchModelMigration
                .isJavaPerspectiveId(null));
        assertTrue(PersistedWorkbenchModelMigration.isJavaPerspectiveId(
                PersistedWorkbenchModelMigration.JAVA_PERSPECTIVE));
        assertTrue(PersistedWorkbenchModelMigration.isJavaPerspectiveId(
                PersistedWorkbenchModelMigration.JAVA_PERSPECTIVE
                + ".one")); //$NON-NLS-1$
        assertFalse(PersistedWorkbenchModelMigration.isJavaPerspectiveId(
                PersistedWorkbenchModelMigration.JAVA_PERSPECTIVE
                + "X")); //$NON-NLS-1$
        assertFalse(PersistedWorkbenchModelMigration.isJavaPerspectiveId(
                "x." + PersistedWorkbenchModelMigration.JAVA_PERSPECTIVE)); //$NON-NLS-1$
        assertTrue(PersistedWorkbenchModelMigration.isTaskListId(
                PersistedWorkbenchModelMigration.TASK_LIST));
        assertFalse(PersistedWorkbenchModelMigration.isTaskListId(
                PersistedWorkbenchModelMigration.TASK_LIST + ".extra")); //$NON-NLS-1$

        MApplication application = application();
        MWindow window = window("main.window"); //$NON-NLS-1$
        application.getChildren().add(window);
        MPerspectiveStack stack = perspectiveStack("perspectives"); //$NON-NLS-1$
        window.getChildren().add(stack);
        MPerspective suffixBoundary = perspective(
                PersistedWorkbenchModelMigration.JAVA_PERSPECTIVE + "X"); //$NON-NLS-1$
        MPerspective prefixBoundary = perspective(
                "x." + PersistedWorkbenchModelMigration.JAVA_PERSPECTIVE); //$NON-NLS-1$
        stack.getChildren().addAll(List.of(
                suffixBoundary, prefixBoundary));
        MPart taskBoundary = part(
                PersistedWorkbenchModelMigration.TASK_LIST + ".extra"); //$NON-NLS-1$
        window.getSharedElements().add(taskBoundary);

        assertEquals(MigrationResult.NONE,
                PersistedWorkbenchModelMigration.migrate(
                        application, bundleId -> false));
        assertEquals(List.of(suffixBoundary, prefixBoundary),
                stack.getChildren());
        assertEquals(List.of(taskBoundary), window.getSharedElements());
    }

    @Test
    void traversesAllPublicContainmentSurfacesWithoutFollowingPlaceholderRefs() {
        MApplication application = application();
        MWindow top = window("top"); //$NON-NLS-1$
        MWindow nested = window("nested"); //$NON-NLS-1$
        application.getChildren().add(top);
        top.getWindows().add(nested);

        MPerspective applicationSnippet = perspective(
                PersistedWorkbenchModelMigration.JAVA_PERSPECTIVE
                + ".applicationSnippet"); //$NON-NLS-1$
        MPerspective windowSnippet = perspective(
                PersistedWorkbenchModelMigration.JAVA_PERSPECTIVE
                + ".windowSnippet"); //$NON-NLS-1$
        application.getSnippets().add(applicationSnippet);
        top.getSnippets().add(windowSnippet);

        MPerspectiveStack topStack = perspectiveStack("top.stack"); //$NON-NLS-1$
        MPerspective host = perspective(SUPPORTED_PERSPECTIVE);
        topStack.getChildren().add(host);
        top.getChildren().add(topStack);
        MWindow detached = window("detached"); //$NON-NLS-1$
        host.getWindows().add(detached);

        MPerspectiveStack nestedStack = perspectiveStack("nested.stack"); //$NON-NLS-1$
        nestedStack.getChildren().add(perspective(
                PersistedWorkbenchModelMigration.JAVA_PERSPECTIVE
                + ".nested")); //$NON-NLS-1$
        nested.getChildren().add(nestedStack);
        MPerspectiveStack detachedStack = perspectiveStack(
                "detached.stack"); //$NON-NLS-1$
        detachedStack.getChildren().add(perspective(
                PersistedWorkbenchModelMigration.JAVA_PERSPECTIVE
                + ".detached")); //$NON-NLS-1$
        detached.getChildren().add(detachedStack);

        MPart taskPart = part(PersistedWorkbenchModelMigration.TASK_LIST);
        nested.getSharedElements().add(taskPart);
        MPlaceholder taskPlaceholder = placeholder(
                PersistedWorkbenchModelMigration.TASK_LIST, taskPart);
        taskPart.setCurSharedRef(taskPlaceholder);
        MPartStack viewStack = partStack("nested.views"); //$NON-NLS-1$
        viewStack.getChildren().add(taskPlaceholder);
        MPartSashContainer outer = MBasicFactory.INSTANCE
                .createPartSashContainer();
        outer.setElementId("outer"); //$NON-NLS-1$
        MPartSashContainer inner = MBasicFactory.INSTANCE
                .createPartSashContainer();
        inner.setElementId("inner"); //$NON-NLS-1$
        inner.getChildren().add(viewStack);
        outer.getChildren().add(inner);
        host.getChildren().add(outer);

        MigrationResult result = PersistedWorkbenchModelMigration.migrate(
                application, bundleId -> false);

        assertEquals(new MigrationResult(4, 2), result);
        assertTrue(application.getSnippets().isEmpty());
        assertTrue(top.getSnippets().isEmpty());
        assertTrue(nestedStack.getChildren().isEmpty());
        assertTrue(detachedStack.getChildren().isEmpty());
        assertTrue(viewStack.getChildren().isEmpty());
        assertTrue(nested.getSharedElements().isEmpty());
        assertEquals(List.of(outer), host.getChildren());
        assertEquals(List.of(inner), outer.getChildren());
        assertEquals(List.of(viewStack), inner.getChildren());
    }

    @Test
    void secondMigrationIsNoOpAndPreservesSupportedElements() {
        MApplication application = application();
        MWindow window = window("main.window"); //$NON-NLS-1$
        application.getChildren().add(window);
        MPerspectiveStack stack = perspectiveStack("perspectives"); //$NON-NLS-1$
        window.getChildren().add(stack);
        MPerspective supported = perspective(SUPPORTED_PERSPECTIVE);
        MPerspective java = perspective(
                PersistedWorkbenchModelMigration.JAVA_PERSPECTIVE);
        stack.getChildren().addAll(List.of(supported, java));
        MPart supportedPart = part(SUPPORTED_PART);
        MPart taskPart = part(PersistedWorkbenchModelMigration.TASK_LIST);
        window.getSharedElements().addAll(List.of(
                supportedPart, taskPart));

        MigrationResult first = PersistedWorkbenchModelMigration.migrate(
                application, bundleId -> false);
        List<MPerspective> perspectivesAfterFirst =
                List.copyOf(stack.getChildren());
        List<MUIElement> sharedAfterFirst =
                List.copyOf(window.getSharedElements());
        MigrationResult second = PersistedWorkbenchModelMigration.migrate(
                application, bundleId -> false);

        assertEquals(new MigrationResult(1, 1), first);
        assertEquals(MigrationResult.NONE, second);
        assertEquals(perspectivesAfterFirst, stack.getChildren());
        assertEquals(sharedAfterFirst, window.getSharedElements());
        assertEquals(List.of(supported), stack.getChildren());
        assertEquals(List.of(supportedPart), window.getSharedElements());
    }

    @Test
    void migrateValidatesArgumentsAndLifecycleNeverPropagatesRuntimeFailure() {
        assertThrows(NullPointerException.class,
                () -> PersistedWorkbenchModelMigration.migrate(
                        null, bundleId -> false));
        assertThrows(NullPointerException.class,
                () -> PersistedWorkbenchModelMigration.migrate(
                        application(), null));
        assertDoesNotThrow(() -> new StandaloneWorkbenchLifecycle()
                .processAdditions(null));
    }

    private static void assertAvailability(Set<String> availableBundles,
            MigrationResult expected, boolean javaRetained,
            boolean taskRetained) {
        MApplication application = application();
        MWindow window = window("main.window"); //$NON-NLS-1$
        application.getChildren().add(window);
        MPerspectiveStack stack = perspectiveStack("perspectives"); //$NON-NLS-1$
        window.getChildren().add(stack);
        MPerspective java = perspective(
                PersistedWorkbenchModelMigration.JAVA_PERSPECTIVE);
        MPerspective supported = perspective(SUPPORTED_PERSPECTIVE);
        stack.getChildren().addAll(List.of(java, supported));
        MPart taskPart = part(PersistedWorkbenchModelMigration.TASK_LIST);
        window.getSharedElements().add(taskPart);
        MPartStack views = partStack("views"); //$NON-NLS-1$
        MPlaceholder taskPlaceholder = placeholder(
                PersistedWorkbenchModelMigration.TASK_LIST, taskPart);
        views.getChildren().add(taskPlaceholder);
        supported.getChildren().add(views);

        MigrationResult result = PersistedWorkbenchModelMigration.migrate(
                application, availableBundles::contains);

        assertEquals(expected, result);
        assertEquals(javaRetained, stack.getChildren().contains(java));
        assertEquals(taskRetained,
                window.getSharedElements().contains(taskPart));
        assertEquals(taskRetained,
                views.getChildren().contains(taskPlaceholder));
        assertTrue(stack.getChildren().contains(supported));
        assertEquals(javaRetained ? 2 : 1, stack.getChildren().size());
    }

    private static MApplication application() {
        return MApplicationFactory.INSTANCE.createApplication();
    }

    private static MWindow window(String id) {
        MWindow window = MBasicFactory.INSTANCE.createWindow();
        window.setElementId(id);
        return window;
    }

    private static MPerspective perspective(String id) {
        MPerspective perspective =
                MAdvancedFactory.INSTANCE.createPerspective();
        perspective.setElementId(id);
        return perspective;
    }

    private static MPerspectiveStack perspectiveStack(String id) {
        MPerspectiveStack stack =
                MAdvancedFactory.INSTANCE.createPerspectiveStack();
        stack.setElementId(id);
        return stack;
    }

    private static MPartStack partStack(String id) {
        MPartStack stack = MBasicFactory.INSTANCE.createPartStack();
        stack.setElementId(id);
        return stack;
    }

    private static MPart part(String id) {
        MPart part = MBasicFactory.INSTANCE.createPart();
        part.setElementId(id);
        return part;
    }

    private static MPlaceholder placeholder(String id, MPart ref) {
        MPlaceholder placeholder =
                MAdvancedFactory.INSTANCE.createPlaceholder();
        placeholder.setElementId(id);
        placeholder.setRef(ref);
        return placeholder;
    }
}
