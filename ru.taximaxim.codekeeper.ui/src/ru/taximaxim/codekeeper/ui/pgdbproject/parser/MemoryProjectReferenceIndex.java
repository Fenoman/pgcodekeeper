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
package ru.taximaxim.codekeeper.ui.pgdbproject.parser;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.base.schema.meta.MetaStatement;

import ru.taximaxim.codekeeper.ui.projectindex.FileContribution;
import ru.taximaxim.codekeeper.ui.projectindex.IndexPathOrigin;
import ru.taximaxim.codekeeper.ui.projectindex.IndexPathRef;
import ru.taximaxim.codekeeper.ui.projectindex.PackedDefinition;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectFileStamp;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexData;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexDelta;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexDefinitionSubject;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexIdentity;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexIncrementalPlanner;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexPathResolver;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetry.PersistenceReason;
import ru.taximaxim.codekeeper.ui.projectindex.ReferenceMatchKey;

final class MemoryProjectReferenceIndex
        implements IncrementalProjectReferenceIndex {

    private static final int MAX_INCREMENTAL_BATCH_SIZE =
            ProjectIndexIncrementalPlanner.MAX_BATCH_SIZE;

    private final State state;
    private final PersistenceReason persistenceReason;
    private boolean open = true;

    private MemoryProjectReferenceIndex(State state,
            PersistenceReason persistenceReason) {
        this.state = Objects.requireNonNull(state, "state"); //$NON-NLS-1$
        this.persistenceReason = requireFailureReason(persistenceReason);
    }

    static MemoryProjectReferenceIndex fromPrepared(
            ProjectReferencesStorage storage, ProjectIndexData data,
            Path projectRoot, Path libraryRoot,
            PersistenceReason persistenceReason) {
        Objects.requireNonNull(storage, "storage"); //$NON-NLS-1$
        Objects.requireNonNull(data, "data"); //$NON-NLS-1$
        PersistenceReason failureReason =
                requireFailureReason(persistenceReason);
        Path normalizedProject =
                normalizeRoot(projectRoot, "projectRoot"); //$NON-NLS-1$
        Path normalizedLibrary =
                normalizeRoot(libraryRoot, "libraryRoot"); //$NON-NLS-1$
        ProjectIndexIdentity identity =
                ProjectIndexIdentity.from(data.manifest());

        Map<IndexPathRef, ProjectFileStamp> stamps = new HashMap<>();
        for (ProjectFileStamp stamp : data.manifest().files()) {
            stamps.put(stamp.path(), stamp);
        }
        Map<IndexPathRef, IncrementalFileMetadata> files =
                new LinkedHashMap<>();
        Set<IndexPathRef> unresolved = new LinkedHashSet<>();
        for (var file : data.files()) {
            ProjectFileStamp stamp = stamps.remove(file.path());
            if (stamp == null) {
                throw new IllegalArgumentException(
                        "Project index data has no matching file stamp"); //$NON-NLS-1$
            }
            files.put(file.path(), new IncrementalFileMetadata(
                    stamp, file.definitions()));
            if (file.unresolvedAny()) {
                unresolved.add(file.path());
            }
        }
        if (!stamps.isEmpty()) {
            throw new IllegalArgumentException(
                    "Project index manifest has no matching file data"); //$NON-NLS-1$
        }

        State state = new State(storage.freeze(), identity,
                data.manifest().generation(),
                List.copyOf(data.manifest().files()),
                immutableMap(files), buildSubjects(files.values()),
                Set.copyOf(unresolved),
                normalizedProject, normalizedLibrary);
        return new MemoryProjectReferenceIndex(state, failureReason);
    }

    PersistenceReason persistenceReason() {
        return persistenceReason;
    }

    synchronized MemoryProjectReferenceIndex withReplacement(
            AnalysisLease lease, ProjectFileStamp replacementStamp,
            FileContribution replacement) {
        Objects.requireNonNull(replacementStamp, "replacementStamp"); //$NON-NLS-1$
        Objects.requireNonNull(replacement, "replacement"); //$NON-NLS-1$
        return withReplacements(lease, new ProjectIndexDelta(
                List.of(ProjectIndexDelta.Change.replace(
                        replacementStamp, replacement))));
    }

    /**
     * Derives the next snapshot from this one by applying a batch.
     *
     * <p>A change may name a path this index does not hold, and that is
     * deliberate: a batch introduces a file as readily as it replaces one, and
     * whether an introduction is safe was decided before the batch was built,
     * against the index the batch came from. Do not add a presence check here
     * as an obvious hardening - it would refuse every addition, and the tests
     * that would notice are the ones that pin this paragraph.
     */
    synchronized MemoryProjectReferenceIndex withReplacements(
            AnalysisLease lease, ProjectIndexDelta replacements) {
        Objects.requireNonNull(replacements, "replacements"); //$NON-NLS-1$
        if (!(lease instanceof MemoryLease memoryLease)
                || memoryLease.owner != this
                || memoryLease.state != state) {
            throw new IllegalArgumentException(
                    "Analysis lease does not belong to this memory index"); //$NON-NLS-1$
        }
        memoryLease.requireOpen();

        List<ProjectIndexDelta.Change> changes = replacements.changes();
        if (changes.size() > MAX_INCREMENTAL_BATCH_SIZE
                || changes.stream().anyMatch(change ->
                        change.operation()
                                != ProjectIndexDelta.Operation.REPLACE)) {
            throw new IllegalArgumentException(
                    "Memory incremental updates require 1 to " //$NON-NLS-1$
                            + MAX_INCREMENTAL_BATCH_SIZE
                            + " replacements"); //$NON-NLS-1$
        }
        Map<IndexPathRef, Integer> stampIndexes = new HashMap<>();
        for (int i = 0; i < state.stamps.size(); i++) {
            stampIndexes.put(state.stamps.get(i).path(), i);
        }
        Map<IndexPathRef, IncrementalFileMetadata> files =
                new LinkedHashMap<>(state.files);
        List<ProjectFileStamp> stamps = new ArrayList<>(state.stamps);
        Set<IndexPathRef> unresolved =
                new LinkedHashSet<>(state.unresolvedFiles);
        Map<IndexPathRef, IncrementalFileMetadata> previousFiles =
                new LinkedHashMap<>();
        Map<String, List<MetaStatement>> replacementStatements =
                new LinkedHashMap<>();
        Map<String, Set<ObjectLocation>> replacementLocations =
                new LinkedHashMap<>();
        for (ProjectIndexDelta.Change change : changes) {
            IndexPathRef path = change.path();
            FileContribution replacement = change.contribution();
            IncrementalFileMetadata replacementMetadata =
                    new IncrementalFileMetadata(
                            change.stamp(), replacement.definitions());
            previousFiles.put(path, files.put(path,
                    replacementMetadata));
            // A batch may introduce a path this index never held. Its stamp has
            // nowhere to overwrite, so it joins the end of the manifest.
            Integer stampIndex = stampIndexes.get(path);
            if (stampIndex == null) {
                stamps.add(change.stamp());
            } else {
                stamps.set(stampIndex, change.stamp());
            }
            if (replacement.unresolvedAny()) {
                unresolved.add(path);
            } else {
                unresolved.remove(path);
            }

            String absolutePath = state.resolve(path);
            replacementStatements.put(absolutePath,
                    replacement.definitions().stream()
                            .map(definition ->
                                    definition.toMetaStatement(
                                            state.resolver))
                            .toList());
            replacementLocations.put(absolutePath,
                    replacement.locations().stream()
                            .map(location ->
                                    location.toObjectLocation(
                                            state.resolver))
                            .collect(java.util.stream.Collectors
                                    .toCollection(
                                            LinkedHashSet::new)));
        }
        ProjectReferencesStorage storage =
                state.storage.frozenCopyReplacing(
                        replacementStatements, replacementLocations);

        Map<SubjectKey, List<PackedDefinition>> subjects =
                replaceSubjects(state.subjects,
                        previousFiles, files, changes);
        State updated = new State(storage, state.identity,
                Math.addExact(state.generation, 1L), List.copyOf(stamps),
                immutableMap(files), subjects, Set.copyOf(unresolved),
                state.projectRoot, state.libraryRoot);
        return new MemoryProjectReferenceIndex(updated, persistenceReason);
    }

    @Override
    public synchronized AnalysisLease acquireAnalysisLease() {
        requireOpen();
        return new MemoryLease(this, state);
    }

    @Override
    public synchronized Set<ObjectLocation> referencesForPath(String path) {
        requireOpen();
        return state.storage.referencesForPath(path);
    }

    @Override
    public synchronized List<MetaStatement> definitionsForPath(String path) {
        requireOpen();
        return state.storage.definitionsForPath(path);
    }

    @Override
    public synchronized List<MetaStatement> definitionsMatching(
            ObjectLocation object) {
        requireOpen();
        return state.storage.definitionsMatching(object);
    }

    @Override
    public synchronized List<ObjectLocation> referencesMatching(
            ObjectLocation object) {
        requireOpen();
        return state.storage.referencesMatching(object);
    }

    @Override
    public synchronized List<MetaStatement> completionCandidates(String text) {
        requireOpen();
        return state.storage.completionCandidates(text);
    }

    @Override
    public synchronized Stream<MetaStatement> allDefinitions() {
        requireOpen();
        return state.storage.allDefinitions();
    }

    @Override
    public synchronized Stream<ObjectLocation> allReferences() {
        requireOpen();
        return state.storage.allReferences();
    }

    @Override
    public synchronized ProjectReferencesStorage mutableCopy() {
        requireOpen();
        return state.storage.copy();
    }

    @Override
    public synchronized void close() {
        open = false;
    }

    private synchronized void requireOpen() {
        if (!open) {
            throw new IllegalStateException(
                    "Project reference index is closed"); //$NON-NLS-1$
        }
    }

    private static Map<SubjectKey, List<PackedDefinition>> buildSubjects(
            Iterable<IncrementalFileMetadata> files) {
        Map<SubjectKey, List<PackedDefinition>> subjects =
                new HashMap<>();
        for (IncrementalFileMetadata file : files) {
            for (PackedDefinition definition : file.definitions()) {
                for (SubjectKey key : keys(definition)) {
                    subjects.computeIfAbsent(key,
                            ignored -> new ArrayList<>()).add(definition);
                }
            }
        }
        subjects.replaceAll((key, definitions) ->
                definitions.stream()
                        .sorted(PackedDefinition.canonicalOrder())
                        .toList());
        return immutableMap(subjects);
    }

    private static Map<SubjectKey, List<PackedDefinition>> replaceSubjects(
            Map<SubjectKey, List<PackedDefinition>> existing,
            Map<IndexPathRef, IncrementalFileMetadata> previousFiles,
            Map<IndexPathRef, IncrementalFileMetadata> updatedFiles,
            List<ProjectIndexDelta.Change> changes) {
        Set<SubjectKey> changed = new LinkedHashSet<>();
        Set<IndexPathRef> changedPaths = new LinkedHashSet<>();
        for (ProjectIndexDelta.Change change : changes) {
            IndexPathRef path = change.path();
            changedPaths.add(path);
            // Null for a path the index did not have: an introduced file
            // vacates no subject, it only claims one.
            IncrementalFileMetadata previous = previousFiles.get(path);
            if (previous != null) {
                previous.definitions().forEach(definition ->
                        changed.addAll(keys(definition)));
            }
            updatedFiles.get(path).definitions()
                    .forEach(definition ->
                            changed.addAll(keys(definition)));
        }
        if (changed.isEmpty()) {
            return existing;
        }
        Map<SubjectKey, List<PackedDefinition>> replacementBySubject =
                new HashMap<>();
        for (IndexPathRef path : changedPaths) {
            for (PackedDefinition definition :
                    updatedFiles.get(path).definitions()) {
                for (SubjectKey key : keys(definition)) {
                    replacementBySubject.computeIfAbsent(key,
                            ignored -> new ArrayList<>()).add(definition);
                }
            }
        }
        Map<SubjectKey, List<PackedDefinition>> updated =
                new HashMap<>(existing);
        for (SubjectKey key : changed) {
            List<PackedDefinition> definitions = new ArrayList<>(
                    existing.getOrDefault(key, List.of()));
            definitions.removeIf(definition -> changedPaths.contains(
                    definition.object().path()));
            definitions.addAll(replacementBySubject.getOrDefault(
                    key, List.of()));
            if (definitions.isEmpty()) {
                updated.remove(key);
            } else {
                updated.put(key, definitions.stream()
                        .sorted(PackedDefinition.canonicalOrder())
                        .toList());
            }
        }
        return immutableMap(updated);
    }

    private static List<SubjectKey> keys(PackedDefinition definition) {
        ReferenceMatchKey key;
        try {
            key = ReferenceMatchKey.from(definition.object());
        } catch (IllegalArgumentException ex) {
            return List.of();
        }
        SubjectKey family = new SubjectKey(
                key.family(), key.exactType(), null, null);
        if (key.schema() == null) {
            return List.of(family);
        }
        SubjectKey schema = new SubjectKey(
                key.family(), key.exactType(), key.schema(), null);
        if (key.table() == null) {
            return List.of(family, schema);
        }
        return List.of(family, schema, new SubjectKey(
                key.family(), key.exactType(), key.schema(), key.table()));
    }

    private static PersistenceReason requireFailureReason(
            PersistenceReason reason) {
        Objects.requireNonNull(reason, "persistenceReason"); //$NON-NLS-1$
        if (reason == PersistenceReason.NONE) {
            throw new IllegalArgumentException(
                    "Memory incremental indexes require a persistence failure"); //$NON-NLS-1$
        }
        return reason;
    }

    private static Path normalizeRoot(Path path, String name) {
        return Objects.requireNonNull(path, name)
                .toAbsolutePath().normalize();
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    private static final class State {

        private final ProjectReferencesStorage storage;
        private final ProjectIndexIdentity identity;
        private final long generation;
        private final List<ProjectFileStamp> stamps;
        private final Map<IndexPathRef, IncrementalFileMetadata> files;
        private final Map<SubjectKey, List<PackedDefinition>> subjects;
        private final Set<IndexPathRef> unresolvedFiles;
        private final Path projectRoot;
        private final Path libraryRoot;
        private final ProjectIndexPathResolver resolver;

        private State(ProjectReferencesStorage storage,
                ProjectIndexIdentity identity, long generation,
                List<ProjectFileStamp> stamps,
                Map<IndexPathRef, IncrementalFileMetadata> files,
                Map<SubjectKey, List<PackedDefinition>> subjects,
                Set<IndexPathRef> unresolvedFiles,
                Path projectRoot, Path libraryRoot) {
            this.storage = storage;
            this.identity = identity;
            this.generation = generation;
            this.stamps = stamps;
            this.files = files;
            this.subjects = subjects;
            this.unresolvedFiles = unresolvedFiles;
            this.projectRoot = projectRoot;
            this.libraryRoot = libraryRoot;
            resolver = path -> resolve(path);
        }

        private String resolve(IndexPathRef path) {
            Path root = path.origin() == IndexPathOrigin.PROJECT
                    ? projectRoot : libraryRoot;
            return root.resolve(path.relativePath()).toString();
        }
    }

    private static final class MemoryLease implements AnalysisLease {

        private final MemoryProjectReferenceIndex owner;
        private final State state;
        private volatile boolean open = true;

        private MemoryLease(MemoryProjectReferenceIndex owner, State state) {
            this.owner = owner;
            this.state = state;
        }

        @Override
        public ProjectIndexIdentity identity() {
            requireOpen();
            return state.identity;
        }

        @Override
        public long generation() {
            requireOpen();
            return state.generation;
        }

        @Override
        public List<ProjectFileStamp> fileStamps() {
            requireOpen();
            return state.stamps;
        }

        @Override
        public Optional<IncrementalFileMetadata> file(IndexPathRef path) {
            requireOpen();
            return Optional.ofNullable(state.files.get(
                    Objects.requireNonNull(path, "path"))); //$NON-NLS-1$
        }

        @Override
        public List<MetaStatement> definitions(
                ProjectIndexDefinitionSubject subject,
                IndexPathRef excludedPath) {
            requireOpen();
            SubjectKey key = SubjectKey.from(
                    Objects.requireNonNull(subject, "subject")); //$NON-NLS-1$
            return state.subjects.getOrDefault(key, List.of()).stream()
                    .filter(definition -> excludedPath == null
                            || !excludedPath.equals(
                                    definition.object().path()))
                    .map(definition -> definition.toMetaStatement(
                            state.resolver))
                    .toList();
        }

        @Override
        public boolean anyFileMayHoldUnresolvedReferences() {
            requireOpen();
            // Answered from the contributions this snapshot owns, not from a
            // constant: a memory index is built from a full build's data and
            // then carries replacements, and both sides describe every file.
            return !state.unresolvedFiles.isEmpty();
        }

        @Override
        public void close() {
            open = false;
        }

        private void requireOpen() {
            if (!open) {
                throw new IllegalStateException(
                        "Project reference index lease is closed"); //$NON-NLS-1$
            }
        }
    }

    private record SubjectKey(
            ReferenceMatchKey.MatchFamily family,
            org.pgcodekeeper.core.database.api.schema.DbObjType exactType,
            String schema,
            String objectName) {

        private static SubjectKey from(
                ProjectIndexDefinitionSubject subject) {
            return new SubjectKey(subject.family(), subject.exactType(),
                    subject.schema(), subject.objectName());
        }
    }
}
