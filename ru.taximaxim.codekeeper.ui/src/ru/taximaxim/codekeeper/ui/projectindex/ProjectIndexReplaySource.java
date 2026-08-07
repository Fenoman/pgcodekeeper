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

import static ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexFormat.DEFINITION_ORDER;
import static ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexFormat.LOCATION_ORDER;
import static ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexFormat.PATH_ORDER;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexDelta.Change;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexDelta.Operation;

/**
 * Replayable, path-ordered input used while compacting an on-disk index.
 *
 * A consumer sees only one canonical contribution at a time. Replaying the
 * source lets the writer build canonical sections without retaining the full
 * project object graph.
 */
final class ProjectIndexReplaySource {

    @FunctionalInterface
    interface FileConsumer {
        void accept(FileContribution contribution) throws IOException;
    }

    private final ProjectIndexManifest manifest;
    private final ProjectIndexView view;
    private final Map<IndexPathRef, Change> changes;
    private final BooleanSupplier cancelled;

    private ProjectIndexReplaySource(ProjectIndexManifest manifest,
            ProjectIndexView view, Map<IndexPathRef, Change> changes,
            BooleanSupplier cancelled) {
        this.manifest = Objects.requireNonNull(manifest, "manifest");
        this.view = Objects.requireNonNull(view, "view");
        this.changes = Map.copyOf(changes);
        this.cancelled = Objects.requireNonNull(cancelled, "cancelled");
    }

    static ProjectIndexReplaySource merged(ProjectIndexView view, ProjectIndexDelta delta,
            BooleanSupplier cancelled) throws ProjectIndexFormatException {
        Objects.requireNonNull(view, "view");
        Map<IndexPathRef, Change> changes = new HashMap<>();
        if (delta != null) {
            delta.changes().forEach(change -> changes.put(change.path(), change));
        }

        ProjectIndexManifest current = view.manifest();
        List<Change> orderedChanges = changes.values().stream()
                .sorted(Comparator.comparing(Change::path, PATH_ORDER))
                .toList();
        List<ProjectFileStamp> orderedStamps = orderedChanges.isEmpty()
                ? current.files() : mergeStamps(current.files(), orderedChanges);
        ProjectIndexManifest manifest = new ProjectIndexManifest(
                current.formatMajor(), current.formatMinor(), current.parserAbi(),
                current.coreVersion(), current.uiVersion(), current.databaseType(),
                current.projectIdentity(), current.configSha256(), current.generation(),
                orderedStamps);
        return new ProjectIndexReplaySource(
                manifest, view, changes, cancelled);
    }

    private static List<ProjectFileStamp> mergeStamps(
            List<ProjectFileStamp> current, List<Change> changes) {
        List<ProjectFileStamp> result = new ArrayList<>(current.size());
        int changeIndex = 0;
        for (ProjectFileStamp stamp : current) {
            while (changeIndex < changes.size()
                    && PATH_ORDER.compare(
                            changes.get(changeIndex).path(), stamp.path()) < 0) {
                addReplacement(result, changes.get(changeIndex++));
            }
            if (changeIndex < changes.size()
                    && changes.get(changeIndex).path().equals(stamp.path())) {
                addReplacement(result, changes.get(changeIndex++));
            } else {
                result.add(stamp);
            }
        }
        while (changeIndex < changes.size()) {
            addReplacement(result, changes.get(changeIndex++));
        }
        return List.copyOf(result);
    }

    private static void addReplacement(List<ProjectFileStamp> target,
            Change change) {
        if (change.operation() == Operation.REPLACE) {
            target.add(change.stamp());
        }
    }

    ProjectIndexManifest manifest() {
        return manifest;
    }

    void forEach(FileConsumer consumer) throws IOException {
        Objects.requireNonNull(consumer, "consumer");
        List<Change> orderedChanges = changes.values().stream()
                .sorted(Comparator.comparing(Change::path, PATH_ORDER))
                .toList();
        int[] changeIndex = {0};
        int[] emitted = {0};
        view.forEachContribution(current -> {
            requireNotCancelled();
            while (changeIndex[0] < orderedChanges.size()
                    && PATH_ORDER.compare(
                            orderedChanges.get(changeIndex[0]).path(),
                            current.path()) < 0) {
                acceptChange(orderedChanges.get(changeIndex[0]++),
                        consumer, emitted);
            }
            if (changeIndex[0] < orderedChanges.size()
                    && orderedChanges.get(changeIndex[0]).path()
                            .equals(current.path())) {
                acceptChange(orderedChanges.get(changeIndex[0]++),
                        consumer, emitted);
            } else {
                acceptStored(current, consumer, emitted);
            }
        });
        while (changeIndex[0] < orderedChanges.size()) {
            acceptChange(orderedChanges.get(changeIndex[0]++),
                    consumer, emitted);
        }
        if (emitted[0] != manifest.files().size()) {
            throw new ProjectIndexFormatException(
                    "Compaction source did not provide every manifest contribution");
        }
        requireNotCancelled();
    }

    private void acceptChange(Change change, FileConsumer consumer,
            int[] emitted) throws IOException {
        requireNotCancelled();
        if (change.operation() == Operation.REPLACE) {
            acceptCanonical(change.contribution(), consumer, emitted);
        }
    }

    private void acceptStored(FileContribution loaded,
            FileConsumer consumer, int[] emitted) throws IOException {
        requireNotCancelled();
        requireExpectedPath(loaded, emitted[0]);
        requireCanonicalOrder(loaded.definitions(), DEFINITION_ORDER,
                "definitions");
        requireCanonicalOrder(loaded.locations(), LOCATION_ORDER,
                "locations");
        consumer.accept(loaded);
        emitted[0]++;
    }

    private void acceptCanonical(FileContribution loaded,
            FileConsumer consumer, int[] emitted) throws IOException {
        requireNotCancelled();
        requireExpectedPath(loaded, emitted[0]);
        List<PackedDefinition> definitions =
                new ArrayList<>(loaded.definitions());
        definitions.sort(DEFINITION_ORDER);
        List<PackedLocation> locations =
                new ArrayList<>(loaded.locations());
        locations.sort(LOCATION_ORDER);
        FileContribution canonical = new FileContribution(loaded.path(),
                definitions, locations, loaded.unresolvedCandidates(),
                loaded.unresolvedAny());
        consumer.accept(canonical);
        emitted[0]++;
    }

    private void requireExpectedPath(FileContribution loaded, int index)
            throws ProjectIndexFormatException {
        if (loaded == null || index >= manifest.files().size()
                || !manifest.files().get(index).path()
                        .equals(loaded.path())) {
            throw new ProjectIndexFormatException(
                    "Compaction source contribution does not match its manifest path");
        }
    }

    private static <T> void requireCanonicalOrder(List<T> values,
            Comparator<T> order, String label)
            throws ProjectIndexFormatException {
        for (int i = 1; i < values.size(); i++) {
            if (order.compare(values.get(i - 1), values.get(i)) > 0) {
                throw new ProjectIndexFormatException(
                        "Stored project index " + label
                                + " are not in canonical order");
            }
        }
    }

    private void requireNotCancelled() throws ProjectIndexStore.WriteCancelledException {
        if (cancelled.getAsBoolean()) {
            throw new ProjectIndexStore.WriteCancelledException();
        }
    }
}
