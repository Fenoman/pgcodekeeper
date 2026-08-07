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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import java.io.IOException;
import java.util.function.BooleanSupplier;

import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.base.schema.meta.MetaStatement;

import ru.taximaxim.codekeeper.ui.projectindex.FileContribution;
import ru.taximaxim.codekeeper.ui.projectindex.IndexPathOrigin;
import ru.taximaxim.codekeeper.ui.projectindex.IndexPathRef;
import ru.taximaxim.codekeeper.ui.projectindex.PackedLocation;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexDefinitionSubject;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexDelta;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexFormatException;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexIdentity;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexPathResolver;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexRevision;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectFileStamp;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexTelemetry;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexStore;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexStringProbes;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexView;
import ru.taximaxim.codekeeper.ui.projectindex.ReferenceMatchKey;

final class PackedProjectReferenceIndex
        implements IncrementalProjectReferenceIndex {

    private final ProjectIndexView view;
    private final Path projectRoot;
    private final Path libraryRoot;
    private final ProjectIndexPathResolver resolver;
    private boolean open = true;
    private boolean viewOpen = true;
    private int activeLeases;

    PackedProjectReferenceIndex(ProjectIndexView view, Path projectRoot, Path libraryRoot) {
        this.view = Objects.requireNonNull(view, "view");
        this.projectRoot = normalizeRoot(projectRoot, "projectRoot");
        this.libraryRoot = normalizeRoot(libraryRoot, "libraryRoot");
        resolver = path -> root(path.origin()).resolve(path.relativePath()).toString();
    }

    @Override
    public synchronized Set<ObjectLocation> referencesForPath(String path) {
        return contribution(path).map(file -> file.locations().stream()
                .map(location -> location.toObjectLocation(resolver))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)))
                .map(Set::copyOf)
                .orElseGet(Set::of);
    }

    @Override
    public synchronized List<MetaStatement> definitionsForPath(String path) {
        return contribution(path).map(file -> file.definitions().stream()
                .map(definition -> definition.toMetaStatement(resolver))
                .toList()).orElseGet(List::of);
    }

    @Override
    public synchronized List<MetaStatement> definitionsMatching(ObjectLocation object) {
        Objects.requireNonNull(object, "object");
        try {
            requireOpen();
            return view.matches(ReferenceMatchKey.from(object)).definitions().stream()
                    .map(definition -> definition.toMetaStatement(resolver))
                    .filter(definition -> definition.getObject().compare(object))
                    .toList();
        } catch (ProjectIndexFormatException ex) {
            throw new ProjectIndexAccessException(ex);
        }
    }

    @Override
    public synchronized List<ObjectLocation> referencesMatching(ObjectLocation object) {
        Objects.requireNonNull(object, "object");
        try {
            requireOpen();
            return view.matches(ReferenceMatchKey.from(object)).locations().stream()
                    .map(location -> location.toObjectLocation(resolver))
                    .filter(object::compare)
                    .toList();
        } catch (ProjectIndexFormatException ex) {
            throw new ProjectIndexAccessException(ex);
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Answered from the trigram index alone. A text shorter than
     * {@link #MIN_COMPLETION_PREFIX_LENGTH} is refused rather than walked for:
     * this used to fall back to decoding every file, which is what made the
     * empty text a dot leaves behind - {@code alias.} - cost tens of seconds
     * and freeze the editor.
     */
    @Override
    public synchronized List<MetaStatement> completionCandidates(String text) {
        String upper = Objects.requireNonNull(text, "text").toUpperCase(Locale.ROOT);
        if (upper.length() < MIN_COMPLETION_PREFIX_LENGTH) {
            return List.of();
        }
        try {
            requireOpen();
            return view.completion(
                    upper.substring(0, MIN_COMPLETION_PREFIX_LENGTH)).stream()
                    .filter(definition -> {
                        String name = definition.object().reference() == null
                                ? null : definition.object().reference().getName();
                        return name != null
                                && name.toUpperCase(Locale.ROOT).contains(upper);
                    })
                    .map(definition -> definition.toMetaStatement(resolver))
                    .toList();
        } catch (ProjectIndexFormatException ex) {
            throw new ProjectIndexAccessException(ex);
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * This decodes every file of the index. Measured on a project of 22 447
     * files holding 48 594 definitions it took some forty seconds and eighty
     * megabytes, twice over - the block cache is far too small to hold that
     * working set, so a repeat costs the same again. Nothing an editor does
     * between two keystrokes may call this.
     */
    @Override
    public synchronized Stream<MetaStatement> allDefinitions() {
        return allContributions()
                .flatMap(file -> file.definitions().stream())
                .map(definition -> definition.toMetaStatement(resolver))
                .toList().stream();
    }

    @Override
    public synchronized Stream<ObjectLocation> allReferences() {
        return allContributions()
                .flatMap(file -> file.locations().stream())
                .map(location -> location.toObjectLocation(resolver))
                .toList().stream();
    }

    @Override
    public synchronized ProjectReferencesStorage mutableCopy() {
        throw new UnsupportedOperationException(
                "Packed project indexes must not be materialized eagerly"); //$NON-NLS-1$
    }

    @Override
    public synchronized void close() {
        if (open) {
            open = false;
            closeViewIfUnused();
        }
    }

    @Override
    public synchronized AnalysisLease acquireAnalysisLease() {
        requireOpen();
        activeLeases++;
        return new AnalysisLeaseImpl(this);
    }

    synchronized PublicationLease acquirePublicationLease() {
        requireOpen();
        activeLeases++;
        return new PublicationLease(this);
    }

    synchronized ProjectIndexRevision revision() {
        requireOpen();
        return view.revision();
    }

    synchronized void recordBlockCache(ProjectIndexTelemetry.Run telemetry) {
        if (open) {
            telemetry.blockCache(view);
        }
    }

    private java.util.Optional<ru.taximaxim.codekeeper.ui.projectindex.FileContribution>
            contribution(String absolutePath) {
        IndexPathRef path = toIndexPath(absolutePath);
        return path == null ? java.util.Optional.empty() : contribution(path);
    }

    private java.util.Optional<ru.taximaxim.codekeeper.ui.projectindex.FileContribution>
            contribution(IndexPathRef path) {
        try {
            requireOpen();
            return view.contribution(path);
        } catch (ProjectIndexFormatException ex) {
            throw new ProjectIndexAccessException(ex);
        }
    }

    private Stream<ru.taximaxim.codekeeper.ui.projectindex.FileContribution>
            allContributions() {
        try {
            requireOpen();
            return view.fileStamps().stream()
                    .flatMap(stamp -> contribution(stamp.path()).stream());
        } catch (ProjectIndexFormatException ex) {
            throw new ProjectIndexAccessException(ex);
        }
    }

    private IndexPathRef toIndexPath(String value) {
        Path path = Path.of(Objects.requireNonNull(value, "path"))
                .toAbsolutePath().normalize();
        if (path.startsWith(projectRoot)) {
            return new IndexPathRef(IndexPathOrigin.PROJECT,
                    projectRoot.relativize(path).toString());
        }
        if (path.startsWith(libraryRoot)) {
            return new IndexPathRef(IndexPathOrigin.LIBRARY,
                    libraryRoot.relativize(path).toString());
        }
        return null;
    }

    private Path root(IndexPathOrigin origin) {
        return origin == IndexPathOrigin.PROJECT ? projectRoot : libraryRoot;
    }

    private void requireOpen() {
        if (!open) {
            throw new IllegalStateException("Project reference index is closed");
        }
    }

    private synchronized List<MetaStatement> definitions(BaseLease lease,
            ProjectIndexDefinitionSubject subject,
            IndexPathRef excludedChangedPath) {
        lease.requireOpen();
        try {
            return view.definitions(subject, excludedChangedPath).stream()
                    .map(definition -> definition.toMetaStatement(resolver))
                    .toList();
        } catch (ProjectIndexFormatException ex) {
            throw new ProjectIndexAccessException(ex);
        }
    }

    private synchronized long definitionBlockTraversals(BaseLease lease) {
        lease.requireOpen();
        return view.definitionBlockTraversals();
    }

    private synchronized ProjectIndexStringProbes stringProbes(
            BaseLease lease) {
        lease.requireOpen();
        return view.stringProbes();
    }

    private synchronized boolean anyFileMayHoldUnresolvedReferences(
            BaseLease lease) {
        lease.requireOpen();
        try {
            return view.anyFileMayHoldUnresolvedReferences();
        } catch (ProjectIndexFormatException ex) {
            throw new ProjectIndexAccessException(ex);
        }
    }

    private synchronized java.util.Optional<IncrementalFileMetadata> file(
            BaseLease lease, IndexPathRef path) {
        lease.requireOpen();
        try {
            return view.file(path).map(metadata ->
                    new IncrementalFileMetadata(metadata.stamp(),
                            metadata.definitions()));
        } catch (ProjectIndexFormatException ex) {
            throw new ProjectIndexAccessException(ex);
        }
    }

    private synchronized java.util.Optional<FileContribution> contribution(
            PublicationLease lease, IndexPathRef path) {
        lease.requireOpen();
        try {
            return view.contribution(path);
        } catch (ProjectIndexFormatException ex) {
            throw new ProjectIndexAccessException(ex);
        }
    }

    private synchronized ProjectIndexRevision revision(BaseLease lease) {
        lease.requireOpen();
        return view.revision();
    }

    private synchronized List<ProjectFileStamp> fileStamps(
            BaseLease lease) {
        lease.requireOpen();
        try {
            return view.fileStamps();
        } catch (ProjectIndexFormatException ex) {
            throw new ProjectIndexAccessException(ex);
        }
    }

    private synchronized ProjectIndexView view(PublicationLease lease) {
        lease.requireOpen();
        return view;
    }

    private synchronized void release(BaseLease lease) {
        if (!lease.open) {
            return;
        }
        lease.open = false;
        activeLeases--;
        closeViewIfUnused();
    }

    private void closeViewIfUnused() {
        if (!open && activeLeases == 0 && viewOpen) {
            viewOpen = false;
            view.close();
        }
    }

    private static Path normalizeRoot(Path path, String name) {
        return Objects.requireNonNull(path, name).toAbsolutePath().normalize();
    }

    private abstract static class BaseLease implements AnalysisLease {

        final PackedProjectReferenceIndex owner;
        private boolean open = true;

        private BaseLease(PackedProjectReferenceIndex owner) {
            this.owner = owner;
        }

        @Override
        public ProjectIndexIdentity identity() {
            return owner.revision(this).identity();
        }

        @Override
        public long generation() {
            return owner.revision(this).generation();
        }

        @Override
        public List<ProjectFileStamp> fileStamps() {
            return owner.fileStamps(this);
        }

        @Override
        public java.util.Optional<IncrementalFileMetadata> file(
                IndexPathRef path) {
            return owner.file(this,
                    Objects.requireNonNull(path, "path")); //$NON-NLS-1$
        }

        @Override
        public List<MetaStatement> definitions(
                ProjectIndexDefinitionSubject subject,
                IndexPathRef excludedChangedPath) {
            return owner.definitions(this,
                    Objects.requireNonNull(subject, "subject"),
                    excludedChangedPath);
        }

        @Override
        public long definitionBlockTraversals() {
            return owner.definitionBlockTraversals(this);
        }

        @Override
        public ProjectIndexStringProbes stringProbes() {
            return owner.stringProbes(this);
        }

        @Override
        public boolean anyFileMayHoldUnresolvedReferences() {
            return owner.anyFileMayHoldUnresolvedReferences(this);
        }

        @Override
        public void close() {
            owner.release(this);
        }

        final void requireOpen() {
            if (!open) {
                throw new IllegalStateException(
                        "Project reference index lease is closed");
            }
        }
    }

    private static final class AnalysisLeaseImpl extends BaseLease {

        private AnalysisLeaseImpl(PackedProjectReferenceIndex owner) {
            super(owner);
        }
    }

    static final class PublicationLease extends BaseLease {

        private PublicationLease(PackedProjectReferenceIndex owner) {
            super(owner);
        }

        java.util.Optional<FileContribution> contribution(
                IndexPathRef path) {
            return owner.contribution(this,
                    Objects.requireNonNull(path, "path")); //$NON-NLS-1$
        }

        ProjectIndexRevision revision() {
            return owner.revision(this);
        }

        void recordBlockCache(ProjectIndexTelemetry.Run telemetry) {
            owner.recordBlockCache(telemetry);
        }

        ProjectIndexStore.IncrementalAppendResult appendIncremental(
                ProjectIndexStore store,
                ProjectIndexDelta.Change replacement,
                BooleanSupplier cancelled,
                ProjectIndexTelemetry.Run telemetry) throws IOException {
            return appendIncremental(store,
                    new ProjectIndexDelta(
                            java.util.List.of(replacement)),
                    cancelled, telemetry);
        }

        ProjectIndexStore.IncrementalAppendResult appendIncremental(
                ProjectIndexStore store,
                ProjectIndexDelta replacements,
                BooleanSupplier cancelled,
                ProjectIndexTelemetry.Run telemetry) throws IOException {
            Objects.requireNonNull(store, "store"); //$NON-NLS-1$
            Objects.requireNonNull(replacements, "replacements"); //$NON-NLS-1$
            Objects.requireNonNull(cancelled, "cancelled"); //$NON-NLS-1$
            Objects.requireNonNull(telemetry, "telemetry"); //$NON-NLS-1$
            ProjectIndexView leasedView = owner.view(this);
            return store.appendIncremental(
                    leasedView, replacements, cancelled, telemetry);
        }
    }

    static final class ProjectIndexAccessException extends IllegalStateException {

        private static final long serialVersionUID = 8132727109397297031L;

        private ProjectIndexAccessException(ProjectIndexFormatException cause) {
            super("Persistent project index cannot be read", cause);
        }
    }
}
