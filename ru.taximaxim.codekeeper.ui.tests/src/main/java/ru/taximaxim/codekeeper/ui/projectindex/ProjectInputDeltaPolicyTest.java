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
package ru.taximaxim.codekeeper.ui.projectindex;

import static org.eclipse.core.resources.IResource.FILE;
import static org.eclipse.core.resources.IResource.FOLDER;
import static org.eclipse.core.resources.IResource.PROJECT;
import static org.eclipse.core.resources.IResource.ROOT;
import static org.eclipse.core.resources.IResourceDelta.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IResourceDeltaVisitor;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.junit.jupiter.api.Test;

import ru.taximaxim.codekeeper.ui.utils.ProjectUtils;

class ProjectInputDeltaPolicyTest {

    private static final IProject TARGET = project("target-default"); //$NON-NLS-1$
    private static final List<String> DIRECTORIES =
            List.of("SCHEMA", "FUNCTION"); //$NON-NLS-1$ //$NON-NLS-2$

    @Test
    void recognizesAllConfigurationInputs() throws Exception {
        for (String path : List.of(
                ".pgcodekeeper", //$NON-NLS-1$
                ".pgcodekeeperignore", //$NON-NLS-1$
                ".pgcodekeeperignoreschema", //$NON-NLS-1$
                ".pgcodekeeperdependencies", //$NON-NLS-1$
                ".dependencies", //$NON-NLS-1$
                ".project", //$NON-NLS-1$
                ".settings/ru.taximaxim.codekeeper.ui.prefs", //$NON-NLS-1$
                "structure.properties")) { //$NON-NLS-1$
            assertEquals(true, ProjectInputDeltaPolicy.isConfigurationInput(path),
                    path);
            assertEquals(true, affects(file(path, CHANGED, CONTENT)), path);
        }
        assertEquals(false,
                ProjectInputDeltaPolicy.isConfigurationInput(
                        ".settings/project.prefs")); //$NON-NLS-1$
    }

    @Test
    void configurationInputsUseCanonicalExactProjectPaths() {
        for (String path : List.of(
                "./.pgcodekeeper", //$NON-NLS-1$
                ".settings\\./ru.taximaxim.codekeeper.ui.prefs", //$NON-NLS-1$
                "metadata/../structure.properties")) { //$NON-NLS-1$
            assertEquals(true,
                    ProjectInputDeltaPolicy.isConfigurationInput(path),
                    path);
        }
        for (String path : List.of(
                "config/.pgcodekeeper", //$NON-NLS-1$
                ".pgcodekeeper.bak", //$NON-NLS-1$
                ".settings2/ru.taximaxim.codekeeper.ui.prefs", //$NON-NLS-1$
                ".settings/ru.taximaxim.codekeeper.ui.prefs.bak", //$NON-NLS-1$
                "my-structure.properties", //$NON-NLS-1$
                "", "/.project", "../.project", "C:\\.project")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            assertEquals(false,
                    ProjectInputDeltaPolicy.isConfigurationInput(path),
                    path);
        }
        assertEquals(false,
                ProjectInputDeltaPolicy.isConfigurationInput(null));
    }

    @Test
    void recognizesEffectiveSqlAndStructuralHierarchyChanges() throws Exception {
        assertEquals(true, affects(file("SCHEMA/app/FUNCTION/item.sql", //$NON-NLS-1$
                CHANGED, CONTENT | MARKERS)));
        assertEquals(true, affects(file("OVERRIDES/SCHEMA/app/TABLE/item.SQL", //$NON-NLS-1$
                ADDED, 0)));
        assertEquals(true, affects(folder("SCHEMA/app", CHANGED, MOVED_TO)));
        assertEquals(true, affects(folder("SCHEMA/app/TABLE", ADDED, 0))); //$NON-NLS-1$
        assertEquals(true, affects(folder("SCHEMA/app/VIEW", REMOVED, 0))); //$NON-NLS-1$
        assertEquals(true, affects(folder("OVERRIDES", REMOVED, 0)));
        assertEquals(true, affects(file("SCHEMA/dummy_tmp/TABLE/item.sql", //$NON-NLS-1$
                CHANGED, CONTENT)));
    }

    @Test
    void ignoresHarmlessAndUnrelatedInputs() throws Exception {
        assertEquals(false, affects(file("SCHEMA/app/TABLE/item.sql", //$NON-NLS-1$
                CHANGED, MARKERS | SYNC | DERIVED_CHANGED)));
        for (String path : List.of(
                ".idea/workspace.xml", ".git/index", "docs/guide.sql", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "CI/check.sql", "README.sql", "SCHEMA/app/README.md")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertEquals(false, affects(file(path, CHANGED, CONTENT)), path);
        }
        assertEquals(false, affects(file("SCHEMA/app/TABLE/item.txt", //$NON-NLS-1$
                CHANGED, CONTENT)));
        assertEquals(false, affects(folder("docs/generated", REMOVED, 0))); //$NON-NLS-1$
    }

    @Test
    void recognizesSettingsAncestorLifecycleChanges() throws Exception {
        assertEquals(true, affects(folder(".settings", ADDED, 0))); //$NON-NLS-1$
        assertEquals(true, affects(folder(".settings", REMOVED, 0))); //$NON-NLS-1$
        assertEquals(false, affects(folder(".settings", CHANGED, MARKERS))); //$NON-NLS-1$
    }

    @Test
    void failsClosedForUnknownAndIncompleteTargetData() throws Exception {
        assertEquals(true, ProjectInputDeltaPolicy.affectsComparison(null,
                TARGET, DIRECTORIES));
        assertEquals(true, affects(file(".idea/workspace.xml", //$NON-NLS-1$
                99, CONTENT)));
        assertEquals(true, affects(file(".idea/workspace.xml", //$NON-NLS-1$
                CHANGED, CONTENT | 0x40000000)));
        assertEquals(true, affects(delta("SCHEMA/app", CHANGED, 0, //$NON-NLS-1$
                resource("SCHEMA/app", 99)))); //$NON-NLS-1$
        assertEquals(true, affects(delta(null, CHANGED, CONTENT,
                resource("SCHEMA/app", FILE)))); //$NON-NLS-1$
        assertEquals(true, affects(delta("SCHEMA/app/TABLE/item.sql", CHANGED, //$NON-NLS-1$
                CONTENT, null)));
        assertEquals(true, affects(delta("", CHANGED, DESCRIPTION, //$NON-NLS-1$
                resource("", PROJECT)))); //$NON-NLS-1$
        assertEquals(true, affects(delta("", ADDED, 0, TARGET))); //$NON-NLS-1$
        assertEquals(true, affects(delta("", REMOVED, 0, TARGET))); //$NON-NLS-1$
    }

    @Test
    void skipsOtherProjectsAndTraversesWorkspaceRoots() throws Exception {
        IProject target = project("target"); //$NON-NLS-1$
        IProject other = project("other"); //$NON-NLS-1$
        assertEquals(false, ProjectInputDeltaPolicy.affectsComparison(
                delta("SCHEMA/app/TABLE/item.sql", CHANGED, CONTENT, //$NON-NLS-1$
                        resource("SCHEMA/app/TABLE/item.sql", FILE, other)), //$NON-NLS-1$
                target));
        assertEquals(false, ProjectInputDeltaPolicy.affectsComparison(
                delta(null, CHANGED, CONTENT,
                        resource("SCHEMA/app/TABLE/item.sql", FILE, other)), //$NON-NLS-1$
                target));

        IResourceDelta targetFile = delta("SCHEMA/app/TABLE/item.sql", //$NON-NLS-1$
                CHANGED, CONTENT,
                resource("SCHEMA/app/TABLE/item.sql", FILE, target)); //$NON-NLS-1$
        IResourceDelta root = delta("", CHANGED, MARKERS, //$NON-NLS-1$
                resource("", ROOT), targetFile); //$NON-NLS-1$
        assertEquals(true,
                ProjectInputDeltaPolicy.affectsComparison(root, target));
    }

    @Test
    void harmlessTargetProjectDeltaTraversesNestedMetadata() throws Exception {
        IProject target = project("target-metadata"); //$NON-NLS-1$
        IResourceDelta metadata = delta(".idea/workspace.xml", //$NON-NLS-1$
                CHANGED, CONTENT,
                resource(".idea/workspace.xml", FILE, target)); //$NON-NLS-1$
        IResourceDelta projectDelta = delta("", CHANGED, MARKERS, //$NON-NLS-1$
                target, metadata);
        IResourceDelta root = delta("", CHANGED, MARKERS, //$NON-NLS-1$
                resource("", ROOT), projectDelta); //$NON-NLS-1$

        assertEquals(false,
                ProjectInputDeltaPolicy.affectsComparison(root, target));
    }

    @Test
    void harmlessTargetProjectDeltaFindsNestedSql() throws Exception {
        IProject target = project("target-sql"); //$NON-NLS-1$
        IResourceDelta sql = delta("SCHEMA/app/TABLE/item.sql", //$NON-NLS-1$
                CHANGED, CONTENT,
                resource("SCHEMA/app/TABLE/item.sql", FILE, target)); //$NON-NLS-1$
        IResourceDelta projectDelta = delta("", CHANGED, 0, //$NON-NLS-1$
                target, sql);
        IResourceDelta root = delta("", CHANGED, MARKERS, //$NON-NLS-1$
                resource("", ROOT), projectDelta); //$NON-NLS-1$

        assertEquals(true,
                ProjectInputDeltaPolicy.affectsComparison(root, target));
    }

    @Test
    void unknownWorkspaceRootKindFailsClosed() throws Exception {
        IProject target = project("target-root-kind"); //$NON-NLS-1$
        IResourceDelta metadata = delta(".idea/workspace.xml", //$NON-NLS-1$
                CHANGED, CONTENT,
                resource(".idea/workspace.xml", FILE, target)); //$NON-NLS-1$
        for (IResourceDelta root : List.of(
                delta("", 99, 0, resource("", ROOT), metadata), //$NON-NLS-1$ //$NON-NLS-2$
                delta("", CHANGED, 0x40000000, //$NON-NLS-1$
                        resource("", ROOT), metadata))) { //$NON-NLS-1$
            assertEquals(true,
                    ProjectInputDeltaPolicy.affectsComparison(root, target));
        }
    }

    @Test
    void publicEntryPointResolvesLayoutOnlyForNonNullRoot() throws Exception {
        IProject unreachableLayout = projectWithFailingLayout(
                "unreachable-layout"); //$NON-NLS-1$
        assertEquals(true, ProjectInputDeltaPolicy.affectsComparison(
                null, unreachableLayout));

        IResourceDelta root = delta("", CHANGED, MARKERS, //$NON-NLS-1$
                resource("", ROOT)); //$NON-NLS-1$
        assertThrows(IllegalStateException.class,
                () -> ProjectInputDeltaPolicy.affectsComparison(
                        root, unreachableLayout));
    }

    @Test
    void natureReadFailureEscapesInsteadOfAssumingPostgreSqlLayout()
            throws Exception {
        for (NatureFailure failure : List.of(
                new NatureFailure(ProjectUtils.NATURE_MS,
                        "Tables/item.sql"), //$NON-NLS-1$
                new NatureFailure(ProjectUtils.NATURE_CH,
                        "DATABASE/app/TABLE/item.sql"))) { //$NON-NLS-1$
            IProject unreadableNature = projectWithFailingNature(
                    "unreadable-nature", failure.nature()); //$NON-NLS-1$
            IResourceDelta sql = delta(failure.sqlPath(), CHANGED, CONTENT,
                    resource(failure.sqlPath(), FILE, unreadableNature));

            assertThrows(CoreException.class,
                    () -> ProjectInputDeltaPolicy.affectsComparison(
                            sql, unreadableNature),
                    failure.sqlPath());
        }
    }

    private static boolean affects(IResourceDelta delta) throws CoreException {
        return ProjectInputDeltaPolicy.affectsComparison(
                delta, TARGET, DIRECTORIES);
    }

    private static IResourceDelta file(String path, int kind, int flags) {
        return delta(path, kind, flags, resource(path, FILE));
    }

    private static IResourceDelta folder(String path, int kind, int flags) {
        return delta(path, kind, flags, resource(path, FOLDER));
    }

    private static IResourceDelta delta(String path, int kind, int flags,
            IResource resource, IResourceDelta... children) {
        return (IResourceDelta) Proxy.newProxyInstance(
                IResourceDelta.class.getClassLoader(),
                new Class<?>[] { IResourceDelta.class },
                (proxy, method, args) -> switch (method.getName()) {
                case "getProjectRelativePath" -> path == null ? null //$NON-NLS-1$
                        : Path.fromPortableString(path);
                case "getKind" -> kind; //$NON-NLS-1$
                case "getFlags" -> flags; //$NON-NLS-1$
                case "getResource" -> resource; //$NON-NLS-1$
                case "accept" -> { //$NON-NLS-1$
                    walk((IResourceDeltaVisitor) args[0],
                            (IResourceDelta) proxy, children);
                    yield null;
                }
                default -> defaultValue(method.getReturnType());
                });
    }

    private static IResource resource(String path, int type) {
        return resource(path, type, type == ROOT ? null : TARGET);
    }

    private static IResource resource(String path, int type,
            IProject project) {
        return (IResource) Proxy.newProxyInstance(
                IResource.class.getClassLoader(),
                new Class<?>[] { IResource.class },
                (proxy, method, args) -> switch (method.getName()) {
                case "getType" -> type; //$NON-NLS-1$
                case "getProject" -> project; //$NON-NLS-1$
                case "toString" -> path; //$NON-NLS-1$
                default -> defaultValue(method.getReturnType());
                });
    }

    private static IProject project(String name) {
        return (IProject) Proxy.newProxyInstance(
                IProject.class.getClassLoader(),
                new Class<?>[] { IProject.class },
                (proxy, method, args) -> switch (method.getName()) {
                case "getName" -> name; //$NON-NLS-1$
                case "getLocationURI" -> URI.create("file:///tmp/" + name); //$NON-NLS-1$
                case "getType" -> PROJECT; //$NON-NLS-1$
                case "getProject" -> proxy; //$NON-NLS-1$
                case "equals" -> proxy == args[0]; //$NON-NLS-1$
                case "hashCode" -> System.identityHashCode(proxy); //$NON-NLS-1$
                default -> defaultValue(method.getReturnType());
                });
    }

    private static IProject projectWithFailingLayout(String name) {
        return (IProject) Proxy.newProxyInstance(
                IProject.class.getClassLoader(),
                new Class<?>[] { IProject.class },
                (proxy, method, args) -> switch (method.getName()) {
                case "getName" -> name; //$NON-NLS-1$
                case "getLocationURI" -> throw new IllegalStateException(
                        "layout unavailable"); //$NON-NLS-1$ //$NON-NLS-2$
                case "getType" -> PROJECT; //$NON-NLS-1$
                case "getProject" -> proxy; //$NON-NLS-1$
                case "equals" -> proxy == args[0]; //$NON-NLS-1$
                case "hashCode" -> System.identityHashCode(proxy); //$NON-NLS-1$
                default -> defaultValue(method.getReturnType());
                });
    }

    private static IProject projectWithFailingNature(String name,
            String failingNature) {
        return (IProject) Proxy.newProxyInstance(
                IProject.class.getClassLoader(),
                new Class<?>[] { IProject.class },
                (proxy, method, args) -> switch (method.getName()) {
                case "getName" -> name; //$NON-NLS-1$
                case "exists" -> true; //$NON-NLS-1$
                case "hasNature" -> { //$NON-NLS-1$
                    if (failingNature.equals(args[0])) {
                        throw new CoreException(new Status(
                                IStatus.ERROR, "test", //$NON-NLS-1$
                                "nature unavailable")); //$NON-NLS-1$
                    }
                    yield false;
                }
                case "getLocationURI" -> URI.create("file:///tmp/" + name); //$NON-NLS-1$
                case "getType" -> PROJECT; //$NON-NLS-1$
                case "getProject" -> proxy; //$NON-NLS-1$
                case "equals" -> proxy == args[0]; //$NON-NLS-1$
                case "hashCode" -> System.identityHashCode(proxy); //$NON-NLS-1$
                default -> defaultValue(method.getReturnType());
                });
    }

    private record NatureFailure(String nature, String sqlPath) {
    }

    private static void walk(IResourceDeltaVisitor visitor,
            IResourceDelta delta, IResourceDelta[] children)
            throws CoreException {
        if (visitor.visit(delta)) {
            for (IResourceDelta child : children) {
                child.accept(visitor);
            }
        }
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == char.class) {
            return '\0';
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == float.class) {
            return 0F;
        }
        if (returnType == double.class) {
            return 0D;
        }
        return 0;
    }
}
