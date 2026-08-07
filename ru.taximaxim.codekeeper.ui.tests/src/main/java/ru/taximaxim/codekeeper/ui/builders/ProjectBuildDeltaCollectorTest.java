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
package ru.taximaxim.codekeeper.ui.builders;

import static org.eclipse.core.resources.IResource.FILE;
import static org.eclipse.core.resources.IResource.FOLDER;
import static org.eclipse.core.resources.IResource.PROJECT;
import static org.eclipse.core.resources.IResourceDelta.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IResourceDeltaVisitor;
import org.eclipse.core.runtime.CoreException;
import org.junit.jupiter.api.Test;

import ru.taximaxim.codekeeper.ui.builders.ProjectBuildDeltaClassifier.Mode;
import ru.taximaxim.codekeeper.ui.builders.ProjectBuildDeltaClassifier.Reason;
import ru.taximaxim.codekeeper.ui.builders.ProjectBuildDeltaClassifier.Result;
import ru.taximaxim.codekeeper.ui.builders.ProjectBuildDeltaCollector.Delta;
import ru.taximaxim.codekeeper.ui.builders.ProjectBuildDeltaCollector.Layout;

class ProjectBuildDeltaCollectorTest {

    private static final Layout SPLIT = new Layout(
            List.of("SCHEMA", "CAST", "EXTENSION"),
            List.of("SCHEMA"), true, Set.of(), false, false);

    @Test
    void acceptsOnlyOneExistingSqlContentOrEncodingChange() {
        assertEquals(Mode.INCREMENTAL, classify(SPLIT,
                file("SCHEMA/app/FUNCTION/calculate.sql",
                        CHANGED, CONTENT, true)));
        assertEquals(Mode.INCREMENTAL, classify(SPLIT,
                file("SCHEMA/app/FUNCTION/calculate.sql",
                        CHANGED, ENCODING, true)));
        assertEquals(Mode.INCREMENTAL, classify(SPLIT,
                file("SCHEMA/app/FUNCTION/calculate.SQL",
                        CHANGED, CONTENT, true)));

        assertEquals(Mode.FULL, classify(SPLIT,
                file("SCHEMA/app/FUNCTION/calculate.sql",
                        CHANGED, CONTENT, false)));
        assertEquals(Mode.FULL, classify(SPLIT,
                file("SCHEMA/app/FUNCTION/calculate.sql",
                        CHANGED, CONTENT | REPLACED, true)));
        assertEquals(Mode.FULL, classify(SPLIT,
                file("SCHEMA/app/FUNCTION/calculate.sql",
                        ADDED, MOVED_FROM, true)));
        assertEquals(Mode.FULL, classify(SPLIT,
                file("SCHEMA/app/FUNCTION/calculate.sql",
                        REMOVED, MOVED_TO, false)));
    }

    @Test
    void markerOnlyAndUnrelatedDeltasAreNoOp() {
        assertEquals(Mode.NO_OP, classify(SPLIT,
                file("SCHEMA/app/FUNCTION/calculate.sql",
                        CHANGED, MARKERS, true),
                file(".settings/project.prefs",
                        CHANGED, CONTENT, true)));
    }

    @Test
    void nonSqlChangesInsideIndexedHierarchyForceFull() {
        assertEquals(Mode.FULL, classify(SPLIT,
                file("SCHEMA/app/README.md",
                        CHANGED, CONTENT, true)));
        assertEquals(Mode.FULL, classify(SPLIT,
                file("SCHEMA/app/FUNCTION/calculate.sql",
                        CHANGED, CONTENT, true),
                file("SCHEMA/app/README.md",
                        CHANGED, CONTENT, true)));

        Layout excluded = new Layout(
                SPLIT.topLevelDirectories(),
                SPLIT.schemaContainer(), true,
                Set.of("dummy_tmp"), false, false);
        assertEquals(Mode.NO_OP, classify(excluded,
                file("SCHEMA/dummy_tmp/README.md",
                        CHANGED, CONTENT, true)));
        assertEquals(Mode.NO_OP, classify(SPLIT,
                file("docs/README.md",
                        CHANGED, CONTENT, true)));
    }

    @Test
    void rootConfigurationContentOrStructuralChangesForceFull() {
        for (String path : List.of(
                ".pgcodekeeper",
                ".pgcodekeeperignore",
                ".pgcodekeeperignoreschema",
                ".pgcodekeeperdependencies",
                ".dependencies",
                ".project",
                ".settings/ru.taximaxim.codekeeper.ui.prefs",
                "structure.properties")) {
            assertEquals(Mode.FULL, classify(SPLIT,
                    file(path, CHANGED, CONTENT, true)), path);
            assertEquals(Mode.NO_OP, classify(SPLIT,
                    file(path, CHANGED, MARKERS, true)), path);
        }
    }

    /**
     * Pins the delta shape produced by "save to project" for objects that already exist in the project.
     * <p>
     * The exporter rewrites each affected .sql in place, which is incremental-eligible. It used to also rewrite the
     * {@code .pgcodekeeper} version marker unconditionally, and that single redundant write downgraded the whole build
     * to a full reindex. {@code AbstractModelExporter.writeProjVersion} now leaves an already-current marker untouched;
     * this test is the reason it must keep doing so.
     */
    @Test
    void savedExistingObjectsStayIncrementalUnlessTheMarkerIsRewritten() {
        assertEquals(Mode.INCREMENTAL, classify(SPLIT,
                file("SCHEMA/app/FUNCTION/calculate.sql",
                        CHANGED, CONTENT, true),
                file("SCHEMA/app/FUNCTION/report.sql",
                        CHANGED, CONTENT, true)));

        assertEquals(Mode.FULL, classify(SPLIT,
                file("SCHEMA/app/FUNCTION/calculate.sql",
                        CHANGED, CONTENT, true),
                file("SCHEMA/app/FUNCTION/report.sql",
                        CHANGED, CONTENT, true),
                file(".pgcodekeeper", CHANGED, CONTENT, true)));
    }

    @Test
    void directoryOnlyStructuralChangesForceFull() {
        assertEquals(Mode.FULL, classify(SPLIT,
                folder("SCHEMA/app/FUNCTION", ADDED, 0, true)));
        assertEquals(Mode.FULL, classify(SPLIT,
                folder("SCHEMA/app", REMOVED, MOVED_TO, false)));
        assertEquals(Mode.NO_OP, classify(SPLIT,
                folder("SCHEMA/app", CHANGED, 0, true)));
    }

    @Test
    void splitExclusionsAreExactForProjectAndOverrides() {
        Layout excluded = new Layout(SPLIT.topLevelDirectories(),
                SPLIT.schemaContainer(), true, Set.of("dummy_tmp"), false, false);

        assertEquals(Mode.NO_OP, classify(excluded,
                file("SCHEMA/dummy_tmp/FUNCTION/generated.sql",
                        CHANGED, CONTENT, true),
                file("SCHEMA/dummy_tmp/FUNCTION/generated.SQL",
                        CHANGED, CONTENT, true),
                folder("SCHEMA/dummy_tmp", REMOVED, 0, false)));
        assertEquals(Mode.FULL, classify(excluded,
                file("OVERRIDES/SCHEMA/dummy_tmp/TABLE/generated.sql",
                        ADDED, 0, true)));
        assertEquals(Mode.INCREMENTAL, classify(excluded,
                file("SCHEMA/dummy_tmp2/FUNCTION/real.sql",
                        CHANGED, CONTENT, true)));
    }

    @Test
    void excludedDirectoryTreesArePrunedBeforeVisitingChildren() {
        Layout excluded = new Layout(
                SPLIT.topLevelDirectories(),
                SPLIT.schemaContainer(), true,
                Set.of("dummy_tmp"), false, false);
        Layout ignoredPrivileges = new Layout(
                SPLIT.topLevelDirectories(),
                SPLIT.schemaContainer(), true,
                Set.of(), true, false);

        assertEquals(true,
                ProjectBuildDeltaCollector.shouldPruneDirectory(
                        "SCHEMA/dummy_tmp", FOLDER,
                        excluded));
        assertEquals(false,
                ProjectBuildDeltaCollector.shouldPruneDirectory(
                        "SCHEMA/dummy_tmp2", FOLDER,
                        excluded));
        assertEquals(true,
                ProjectBuildDeltaCollector.shouldPruneDirectory(
                        "OVERRIDES", FOLDER,
                        ignoredPrivileges));
        assertEquals(false,
                ProjectBuildDeltaCollector.shouldPruneDirectory(
                        "SCHEMA/dummy_tmp/generated.sql",
                        FILE, excluded));
        assertEquals(false,
                ProjectBuildDeltaCollector.shouldPruneDirectory(
                        "OVERRIDES/SCHEMA/dummy_tmp",
                        FOLDER, excluded));
    }

    @Test
    void realDeltaTraversalPrunesOnlySemanticallyIgnoredTrees()
            throws Exception {
        Layout excluded = new Layout(
                SPLIT.topLevelDirectories(),
                SPLIT.schemaContainer(), true,
                Set.of("dummy_tmp"), false, false);
        Layout ignoredPrivileges = new Layout(
                SPLIT.topLevelDirectories(),
                SPLIT.schemaContainer(), true,
                Set.of("dummy_tmp"), true, false);

        DeltaTree nonIgnoredOverride = deltaTree(
                "OVERRIDES/SCHEMA/dummy_tmp",
                "OVERRIDES/SCHEMA/dummy_tmp/TABLE/generated.sql");
        assertEquals(Mode.FULL,
                ProjectBuildDeltaCollector.classify(
                        nonIgnoredOverride.root(), excluded).mode());
        assertEquals(true,
                nonIgnoredOverride.visited().contains(
                        nonIgnoredOverride.leaf()));

        DeltaTree ignoredOverride = deltaTree(
                "OVERRIDES",
                "OVERRIDES/SCHEMA/dummy_tmp/TABLE/generated.sql");
        assertEquals(Mode.NO_OP,
                ProjectBuildDeltaCollector.classify(
                        ignoredOverride.root(),
                        ignoredPrivileges).mode());
        assertEquals(false,
                ignoredOverride.visited().contains(
                        ignoredOverride.leaf()));

        DeltaTree excludedProject = deltaTree(
                "SCHEMA/dummy_tmp",
                "SCHEMA/dummy_tmp/TABLE/generated.sql");
        assertEquals(Mode.NO_OP,
                ProjectBuildDeltaCollector.classify(
                        excludedProject.root(), excluded).mode());
        assertEquals(false,
                excludedProject.visited().contains(
                        excludedProject.leaf()));
    }

    @Test
    void customMultiSegmentSchemaContainerSupportsExactExclusions() {
        Layout custom = new Layout(
                List.of("DB", "CAST", "EXTENSION"),
                List.of("DB", "SCHEMAS"), true,
                Set.of("dummy_tmp"), false, false);

        assertEquals(Mode.NO_OP, classify(custom,
                file("DB/SCHEMAS/dummy_tmp/FUNCTION/generated.sql",
                        CHANGED, CONTENT, true)));
        assertEquals(Mode.FULL, classify(custom,
                file("OVERRIDES/DB/SCHEMAS/dummy_tmp/TABLE/generated.sql",
                        CHANGED, CONTENT, true)));
        assertEquals(Mode.INCREMENTAL, classify(custom,
                file("DB/SCHEMAS/dummy_tmp2/FUNCTION/real.sql",
                        CHANGED, CONTENT, true)));
    }

    @Test
    void topLevelNonSchemaSqlContentChangeIsSingleFile() {
        assertEquals(Mode.INCREMENTAL, classify(SPLIT,
                file("CAST/app.item_to_text.sql",
                        CHANGED, CONTENT, true)));
        assertEquals(Mode.FULL, classify(SPLIT,
                file("OVERRIDES/CAST/app.item_to_text.SQL",
                        CHANGED, CONTENT, true)));
        assertEquals(Mode.NO_OP, classify(SPLIT,
                file("OVERRIDES/random/not_loaded.sql",
                        CHANGED, CONTENT, true)));
    }

    @Test
    void flatLayoutWithExclusionsIsConservative() {
        Layout flatWithoutExclusions = new Layout(
                List.of("FUNCTION"), List.of("SCHEMA"),
                false, Set.of(), false, false);
        Layout flatWithExclusions = new Layout(
                List.of("FUNCTION"), List.of("SCHEMA"),
                false, Set.of("dummy_tmp"), false, false);

        assertEquals(Mode.INCREMENTAL, classify(flatWithoutExclusions,
                file("FUNCTION/app.calculate().sql",
                        CHANGED, CONTENT, true)));
        assertEquals(Mode.FULL, classify(flatWithExclusions,
                file("FUNCTION/app.calculate().sql",
                        CHANGED, CONTENT, true)));
        assertEquals(Mode.FULL, classify(flatWithExclusions,
                file("FUNCTION/dummy_tmp.calculate().sql",
                        CHANGED, CONTENT, true)));
    }

    @Test
    void ignoredPrivilegesExcludeWholeOverridesTree() {
        Layout ignoredPrivileges = new Layout(
                SPLIT.topLevelDirectories(), SPLIT.schemaContainer(),
                true, Set.of(), true, false);

        assertEquals(Mode.NO_OP, classify(ignoredPrivileges,
                file("OVERRIDES/SCHEMA/app/TABLE/item.sql",
                        CHANGED, CONTENT, true),
                folder("OVERRIDES/SCHEMA/app", REMOVED, 0, false)));
        assertEquals(Mode.NO_OP, classify(ignoredPrivileges,
                new Delta("OVERRIDES", REMOVED, 0, FOLDER, false)));
        assertEquals(Mode.FULL, classify(SPLIT,
                new Delta("OVERRIDES", REMOVED, 0, FOLDER, false)));
    }

    @Test
    void nullAndUnknownDeltaDataFailClosed() throws Exception {
        assertEquals(Mode.FULL,
                ProjectBuildDeltaCollector.classify(
                        (IResourceDelta) null, SPLIT).mode());
        assertEquals(Mode.FULL, classify(SPLIT,
                file("SCHEMA/app/FUNCTION/calculate.sql",
                        CHANGED, CONTENT | 0x40000000, true)));
        assertEquals(Mode.FULL, classify(SPLIT,
                new Delta("SCHEMA/app/FUNCTION/calculate.sql",
                        999, CONTENT, FILE, true)));
        assertEquals(Mode.FULL, classify(SPLIT,
                new Delta("SCHEMA/app/FUNCTION/calculate.sql",
                        CHANGED, CONTENT, 999, true)));
        assertEquals(Mode.FULL, classify(SPLIT,
                folder("SCHEMA/app/FUNCTION",
                        CHANGED, CONTENT, true)));
        assertEquals(Mode.FULL, classify(SPLIT,
                folder("SCHEMA/app/FUNCTION",
                        CHANGED, CONTENT | 0x40000000, true)));
        assertEquals(Mode.NO_OP, classify(SPLIT,
                new Delta("", CHANGED, MARKERS, PROJECT, true)));
        assertEquals(Mode.FULL, classify(SPLIT,
                new Delta("", CHANGED, DESCRIPTION, PROJECT, true)));
        assertEquals(Mode.FULL, classify(SPLIT,
                new Delta("", CHANGED, CONTENT, PROJECT, true)));
        assertEquals(Mode.FULL, classify(SPLIT,
                new Delta("", CHANGED, 0x40000000, PROJECT, true)));
    }

    /**
     * A file that appears carries no resource flags of its own, so the
     * collector has to leave it unmarked structurally - otherwise the
     * classifier would refuse it whatever the project permits. The pair of
     * layouts differs in nothing but the preference.
     */
    @Test
    void theAddedFilesPreferenceReachesTheClassifierThroughTheLayout() {
        Layout permitted = new Layout(SPLIT.topLevelDirectories(),
                SPLIT.schemaContainer(), true, Set.of(), false, true);

        assertEquals(Mode.INCREMENTAL, classify(permitted,
                file("SCHEMA/app/TABLE/item.sql", ADDED, 0, true)));
        assertEquals(Mode.INCREMENTAL, classify(permitted,
                file("SCHEMA/app/TABLE/item.sql", ADDED, 0, true),
                file("SCHEMA/app/FUNCTION/calculate.sql",
                        CHANGED, CONTENT, true)));
        assertEquals(Mode.FULL, classify(SPLIT,
                file("SCHEMA/app/TABLE/item.sql", ADDED, 0, true)));
        assertEquals(Mode.FULL, classify(permitted,
                file("SCHEMA/app/TABLE/item.sql", ADDED, MOVED_FROM, true)));
        assertEquals(Mode.FULL, classify(permitted,
                file("SCHEMA/app/TABLE/old.sql", REMOVED, 0, false)));
    }

    @Test
    void namesTheMissingDelta() throws Exception {
        Result result = ProjectBuildDeltaCollector.classify(
                (IResourceDelta) null, SPLIT);

        assertEquals(Mode.FULL, result.mode());
        assertEquals(Reason.NO_DELTA, result.reason());
        assertNull(result.evidence());
    }

    @Test
    void namesTheMissingDeltaCollection() {
        Result result = ProjectBuildDeltaCollector.classify(
                (Collection<Delta>) null, SPLIT);

        assertEquals(Mode.FULL, result.mode());
        assertEquals(Reason.NULL_DELTAS, result.reason());
        assertNull(result.evidence());
    }

    /**
     * A visitor that cannot describe a resource leaves the collector without
     * the delta it was asked to classify, which is not the same as there being
     * no delta at all.
     */
    @Test
    void namesTheDeltaItCouldNotVisit() throws Exception {
        Result result = ProjectBuildDeltaCollector.classify(
                unreadableDelta(), SPLIT);

        assertEquals(Mode.FULL, result.mode());
        assertEquals(Reason.DELTA_VISIT_FAILED, result.reason());
        assertEquals("entries=0", result.evidence());
    }

    @Test
    void namesTheNullEntryAndWhereItSat() {
        Result result = ProjectBuildDeltaCollector.classify(
                Arrays.asList(file("SCHEMA/app/FUNCTION/calculate.sql",
                        CHANGED, CONTENT, true), null), SPLIT);

        assertEquals(Mode.FULL, result.mode());
        assertEquals(Reason.NULL_ENTRY, result.reason());
        assertEquals("entries=2,at=1", result.evidence());
    }

    @Test
    void namesTheUnknownKindAndWhereItSat() {
        Result result = result(SPLIT,
                new Delta("SCHEMA/app/FUNCTION/calculate.sql",
                        999, CONTENT, FILE, true));

        assertEquals(Mode.FULL, result.mode());
        assertEquals(Reason.UNKNOWN_KIND, result.reason());
        assertEquals("entries=1,at=0,kind=999,segment=SCHEMA",
                result.evidence());
    }

    @Test
    void namesTheProjectRootChange() {
        Result result = result(SPLIT,
                new Delta("", CHANGED, DESCRIPTION, PROJECT, true));

        assertEquals(Mode.FULL, result.mode());
        assertEquals(Reason.PROJECT_ROOT_CHANGED, result.reason());
        assertEquals("entries=1,at=0,kind=" + CHANGED + ",flags=0x"
                + Integer.toHexString(DESCRIPTION), result.evidence());
    }

    @Test
    void namesTheUnsupportedResourceType() {
        Result result = result(SPLIT,
                new Delta("SCHEMA/app/FUNCTION/calculate.sql",
                        CHANGED, CONTENT, 999, true));

        assertEquals(Mode.FULL, result.mode());
        assertEquals(Reason.UNSUPPORTED_RESOURCE, result.reason());
        assertEquals("entries=1,at=0,type=999,segment=SCHEMA",
                result.evidence());
    }

    @Test
    void namesThePrivilegeOverride() {
        Result result = result(SPLIT,
                file("OVERRIDES/SCHEMA/app/TABLE/item.sql",
                        CHANGED, CONTENT, true));

        assertEquals(Mode.FULL, result.mode());
        assertEquals(Reason.PRIVILEGE_OVERRIDE, result.reason());
        assertEquals("entries=1,at=0", result.evidence());
    }

    /**
     * The delta a pull is suspected of carrying. The line has to name the flags
     * nobody recognised and the tree they came from, and it may not carry the
     * path itself: diagnostics stay free of project content.
     */
    @Test
    void namesTheUnknownFlagsAndTheirTreeWithoutThePath() {
        Result result = result(SPLIT,
                file(".git/objects/pack/x.pack",
                        CHANGED, CONTENT | 0x40000000, true));

        assertEquals(Mode.FULL, result.mode());
        assertEquals(Reason.UNKNOWN_FLAGS, result.reason());
        assertTrue(result.evidence().contains("flags=0x40000000"),
                result.evidence());
        assertTrue(result.evidence().contains("segment=.git"),
                result.evidence());
        assertFalse(result.evidence().contains("objects"),
                result.evidence());
        assertFalse(result.evidence().contains("x.pack"),
                result.evidence());
        assertEquals("entries=1,at=0,flags=0x40000000,segment=.git",
                result.evidence());
    }

    /**
     * Evidence rides along with a verdict that refused nothing too: how many
     * deltas arrived and how many of them reached the classifier is what tells
     * a narrowed build apart from a quiet one. The two counts differ whenever
     * the collector settled a delta on its own, as it does for the project root
     * here, and a line that conflated them would hide exactly that.
     */
    @Test
    void aSuccessfulIncrementCarriesItsCountsAndNothingElse() {
        Result result = result(SPLIT,
                new Delta("", CHANGED, MARKERS, PROJECT, true),
                file("SCHEMA/app/FUNCTION/calculate.sql",
                        CHANGED, CONTENT, true),
                file("SCHEMA/app/FUNCTION/report.sql",
                        CHANGED, CONTENT, true),
                file("docs/README.md", CHANGED, CONTENT, true));

        assertEquals(Mode.INCREMENTAL, result.mode());
        assertEquals(List.of("SCHEMA/app/FUNCTION/calculate.sql",
                "SCHEMA/app/FUNCTION/report.sql"),
                result.relativePaths());
        assertEquals(Reason.NONE, result.reason());
        assertEquals("entries=4,candidates=3", result.evidence());
    }

    @Test
    void aQuietDeltaCarriesItsCountsToo() {
        Result result = result(SPLIT,
                file("SCHEMA/app/FUNCTION/calculate.sql",
                        CHANGED, MARKERS, true));

        assertEquals(Mode.NO_OP, result.mode());
        assertEquals(Reason.NONE, result.reason());
        assertEquals("entries=1,candidates=1", result.evidence());
    }

    private static Mode classify(Layout layout, Delta... deltas) {
        return result(layout, deltas).mode();
    }

    private static Result result(Layout layout, Delta... deltas) {
        return ProjectBuildDeltaCollector.classify(
                List.of(deltas), layout);
    }

    /**
     * A delta whose resource the platform will not describe. The collector
     * cannot tell what changed, so it must refuse the whole traversal.
     */
    private static IResourceDelta unreadableDelta() {
        return (IResourceDelta) Proxy.newProxyInstance(
                IResourceDelta.class.getClassLoader(),
                new Class<?>[] { IResourceDelta.class },
                (proxy, method, args) -> switch (method.getName()) {
                case "getResource" -> null;
                case "accept" -> {
                    ((IResourceDeltaVisitor) args[0])
                            .visit((IResourceDelta) proxy);
                    yield null;
                }
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "unreadable";
                default -> DeltaNode.defaultValue(
                        method.getReturnType());
                });
    }

    private static Delta file(String path, int kind, int flags,
            boolean exists) {
        return new Delta(path, kind, flags, FILE, exists);
    }

    private static Delta folder(String path, int kind, int flags,
            boolean exists) {
        return new Delta(path, kind, flags, FOLDER, exists);
    }

    private record DeltaTree(IResourceDelta root,
            IResourceDelta leaf, Set<IResourceDelta> visited) { }

    private static DeltaTree deltaTree(String folderPath,
            String filePath) throws CoreException {
        Set<IResourceDelta> visited =
                java.util.Collections.newSetFromMap(
                        new IdentityHashMap<>());
        DeltaNode root = new DeltaNode("", CHANGED,
                MARKERS, PROJECT, true, visited);
        DeltaNode folder = new DeltaNode(folderPath,
                CHANGED, 0, FOLDER, true, visited);
        DeltaNode leaf = new DeltaNode(filePath,
                CHANGED, CONTENT, FILE, true, visited);
        root.children = List.of(folder);
        folder.children = List.of(leaf);
        return new DeltaTree(root.delta, leaf.delta,
                visited);
    }

    private static final class DeltaNode {

        private final IResourceDelta delta;
        private final Set<IResourceDelta> visited;
        private List<DeltaNode> children = List.of();

        private DeltaNode(String path, int kind, int flags,
                int resourceType, boolean exists,
                Set<IResourceDelta> visited) {
            this.visited = visited;
            IResource resource = (IResource) Proxy.newProxyInstance(
                    IResource.class.getClassLoader(),
                    new Class<?>[] { IResource.class },
                    (proxy, method, args) -> switch (method.getName()) {
                    case "getType" -> resourceType;
                    case "exists" -> exists;
                    case "hashCode" ->
                        System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> path;
                    default -> defaultValue(
                            method.getReturnType());
                    });
            delta = (IResourceDelta) Proxy.newProxyInstance(
                    IResourceDelta.class.getClassLoader(),
                    new Class<?>[] { IResourceDelta.class },
                    (proxy, method, args) -> switch (method.getName()) {
                    case "getResource" -> resource;
                    case "getProjectRelativePath" ->
                        org.eclipse.core.runtime.Path
                                .fromPortableString(path);
                    case "getKind" -> kind;
                    case "getFlags" -> flags;
                    case "accept" -> {
                        walk((IResourceDeltaVisitor) args[0]);
                        yield null;
                    }
                    case "hashCode" ->
                        System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> path;
                    default -> defaultValue(
                            method.getReturnType());
                    });
        }

        private void walk(IResourceDeltaVisitor visitor)
                throws CoreException {
            visited.add(delta);
            if (visitor.visit(delta)) {
                for (DeltaNode child : children) {
                    child.walk(visitor);
                }
            }
        }

        private static Object defaultValue(
                Class<?> returnType) {
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
}
