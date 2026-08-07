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
package ru.taximaxim.codekeeper.ui.views;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Array;
import java.lang.reflect.Proxy;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.ui.IWorkbenchPart;
import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.model.graph.SimpleDepcyResolver;

class DepcyGraphSelectionStateTest {

    @Test
    void clearReleasesComparisonReferencesAndKeepsSelectedProject() {
        DepcyGraphSelectionState state = new DepcyGraphSelectionState();
        IWorkbenchPart part = stub(IWorkbenchPart.class);
        ISelection selection = new StructuredSelection("selected object");
        IProject oldProject = stub(IProject.class);
        IProject selectedProject = stub(IProject.class);
        SimpleDepcyResolver resolver = new SimpleDepcyResolver(new PgDatabase(), null, false, List.of());
        state.remember(part, selection, oldProject, resolver);

        state.clear(selectedProject);

        assertNull(state.resolver());
        assertNull(state.selectionPart());
        assertNull(state.selection());
        assertSame(selectedProject, state.project());
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
