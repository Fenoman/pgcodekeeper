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

import java.lang.reflect.Array;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.jface.viewers.StructuredSelection;
import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.api.ComparisonDepth;
import org.pgcodekeeper.core.database.api.loader.ILoader;
import org.pgcodekeeper.core.settings.ISettings;

import ru.taximaxim.codekeeper.ui.views.DBPair;

class ProjectEditorSelectionProviderTest {

    @Test
    void explicitCandidatePairIsPublishedWithoutReadingEditorFields() {
        IProject project = stub(IProject.class);
        ILoader candidateProject = stub(ILoader.class);
        ILoader candidateRemote = stub(ILoader.class);
        ISettings candidateSettings = stub(ISettings.class);
        var provider =
                new ProjectEditorSelectionProvider(project);
        var pair = new DBPair(candidateProject,
                candidateRemote, candidateSettings, ComparisonDepth.FULL);

        provider.fireComparisonChanged(pair);

        IStructuredSelection selection =
                (IStructuredSelection) provider.getSelection();
        assertEquals(List.of(project, pair),
                selection.toList());
        assertSame(candidateProject,
                ((DBPair) selection.toList().get(1))
                        .dbProject());
    }

    @Test
    void clearSelectionDropsComparisonReferencesAndNotifiesListeners() {
        IProject project = stub(IProject.class);
        ProjectEditorSelectionProvider provider = new ProjectEditorSelectionProvider(project);
        DBPair pair = new DBPair(null, null, null, null);

        provider.fireSelectionChanged(
                new SelectionChangedEvent(provider, new StructuredSelection("selected object")), pair);
        assertTrue(((IStructuredSelection) provider.getSelection()).toList().contains(pair));

        AtomicInteger listenerCalls = new AtomicInteger();
        AtomicInteger postListenerCalls = new AtomicInteger();
        AtomicReference<SelectionChangedEvent> lastEvent = new AtomicReference<>();
        provider.addSelectionChangedListener(event -> {
            listenerCalls.incrementAndGet();
            lastEvent.set(event);
        });
        provider.addPostSelectionChangedListener(event -> postListenerCalls.incrementAndGet());

        provider.clearSelection();

        IStructuredSelection selection = (IStructuredSelection) provider.getSelection();
        assertEquals(List.of(project), selection.toList());
        assertSame(selection, lastEvent.get().getSelection());
        assertEquals(1, listenerCalls.get());
        assertEquals(1, postListenerCalls.get());
    }

    @Test
    void rejectedComparisonRollbackDropsTheExactCandidatePair() {
        IProject project = stub(IProject.class);
        var provider =
                new ProjectEditorSelectionProvider(project);
        var pair = new DBPair(stub(ILoader.class),
                stub(ILoader.class), stub(ISettings.class), ComparisonDepth.FULL);

        provider.fireComparisonChanged(pair);
        provider.clearSelection();

        List<?> retained = ((IStructuredSelection)
                provider.getSelection()).toList();
        assertEquals(List.of(project), retained);
        assertFalse(retained.contains(pair));
    }

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (proxy, method, args) -> {
            return switch (method.getName()) {
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> type.getSimpleName() + " stub";
            default -> method.getReturnType().isPrimitive()
                    ? Array.get(Array.newInstance(method.getReturnType(), 1), 0)
                    : null;
            };
        });
    }
}
