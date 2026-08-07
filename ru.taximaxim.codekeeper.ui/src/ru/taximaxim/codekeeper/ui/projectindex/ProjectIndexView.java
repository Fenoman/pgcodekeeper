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

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.pgcodekeeper.core.database.api.schema.ObjectLocation;

public final class ProjectIndexView implements AutoCloseable {

    @FunctionalInterface
    interface ContributionConsumer {
        void accept(FileContribution contribution) throws IOException;
    }

    private final LazyProjectIndexBase base;
    private final Map<IndexPathRef, OverlayValue> overlay;
    private final ProjectIndexBlockCache cache;
    private final FileChannel baseChannel;
    private final FileChannel journalChannel;
    private final Path basePath;
    private final Path journalPath;
    private final String publicationId;
    private final long committedJournalLength;
    private final int committedJournalEntries;
    private final Set<IndexPathRef> decodedPaths = ConcurrentHashMap.newKeySet();
    private volatile List<ProjectFileStamp> mergedStamps;
    private volatile boolean open = true;

    ProjectIndexView(LazyProjectIndexBase base, Map<IndexPathRef, OverlayValue> overlay,
            ProjectIndexBlockCache cache, FileChannel baseChannel, FileChannel journalChannel,
            Path basePath, Path journalPath, String publicationId,
            long committedJournalLength, int committedJournalEntries) {
        this.base = base;
        this.overlay = Collections.unmodifiableMap(new HashMap<>(overlay));
        this.cache = cache;
        this.baseChannel = baseChannel;
        this.journalChannel = journalChannel;
        this.basePath = basePath;
        this.journalPath = journalPath;
        this.publicationId = publicationId;
        this.committedJournalLength = committedJournalLength;
        this.committedJournalEntries = committedJournalEntries;
    }

    public ProjectIndexManifest manifest() throws ProjectIndexFormatException {
        requireOpen();
        ProjectIndexManifest original = base.manifest();
        return new ProjectIndexManifest(original.formatMajor(), original.formatMinor(),
                original.parserAbi(), original.coreVersion(), original.uiVersion(),
                original.databaseType(), original.projectIdentity(), original.configSha256(),
                original.generation(), fileStamps());
    }

    public List<ProjectFileStamp> fileStamps() throws ProjectIndexFormatException {
        requireOpen();
        List<ProjectFileStamp> result = mergedStamps;
        if (result != null) {
            return result;
        }
        synchronized (this) {
            result = mergedStamps;
            if (result == null) {
                List<ProjectFileStamp> baseStamps = base.stamps();
                var changes = overlay.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey(
                                ProjectIndexFormat.PATH_ORDER))
                        .toList();
                List<ProjectFileStamp> merged =
                        new ArrayList<>(baseStamps.size() + changes.size());
                int baseIndex = 0;
                int changeIndex = 0;
                while (baseIndex < baseStamps.size()
                        && changeIndex < changes.size()) {
                    ProjectFileStamp stamp = baseStamps.get(baseIndex);
                    var change = changes.get(changeIndex);
                    int comparison = ProjectIndexFormat.PATH_ORDER.compare(
                            stamp.path(), change.getKey());
                    if (comparison < 0) {
                        merged.add(stamp);
                        baseIndex++;
                    } else {
                        if (!change.getValue().deleted()) {
                            merged.add(change.getValue().stamp());
                        }
                        changeIndex++;
                        if (comparison == 0) {
                            baseIndex++;
                        }
                    }
                }
                merged.addAll(baseStamps.subList(baseIndex, baseStamps.size()));
                while (changeIndex < changes.size()) {
                    OverlayValue change = changes.get(changeIndex++).getValue();
                    if (!change.deleted()) {
                        merged.add(change.stamp());
                    }
                }
                result = List.copyOf(merged);
                mergedStamps = result;
            }
        }
        return result;
    }

    public Optional<FileContribution> contribution(IndexPathRef path)
            throws ProjectIndexFormatException {
        requireOpen();
        FileContribution contribution = loadContribution(path);
        if (contribution != null) {
            decodedPaths.add(path);
        }
        return Optional.ofNullable(contribution);
    }

    public Optional<ProjectIndexFileMetadata> file(IndexPathRef path)
            throws ProjectIndexFormatException {
        requireOpen();
        Objects.requireNonNull(path, "path");
        OverlayValue replacement = overlay.get(path);
        ProjectIndexFileMetadata file = replacement == null
                ? base.file(path) : replacement.file();
        return Optional.ofNullable(file);
    }

    private FileContribution loadContribution(IndexPathRef path)
            throws ProjectIndexFormatException {
        requireOpen();
        OverlayValue replacement = overlay.get(path);
        FileContribution contribution;
        if (replacement == null) {
            contribution = base.contribution(path);
        } else if (replacement.deleted()) {
            contribution = null;
        } else {
            contribution = replacement.contribution();
        }
        return contribution;
    }

    void forEachContribution(ContributionConsumer consumer)
            throws IOException {
        requireOpen();
        Objects.requireNonNull(consumer, "consumer");
        var changes = overlay.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(
                        ProjectIndexFormat.PATH_ORDER))
                .toList();
        int[] changeIndex = {0};
        base.forEachContribution(baseContribution -> {
            while (changeIndex[0] < changes.size()
                    && ProjectIndexFormat.PATH_ORDER.compare(
                            changes.get(changeIndex[0]).getKey(),
                            baseContribution.path()) < 0) {
                acceptOverlay(
                        changes.get(changeIndex[0]++), consumer);
            }
            if (changeIndex[0] < changes.size()
                    && changes.get(changeIndex[0]).getKey()
                            .equals(baseContribution.path())) {
                acceptOverlay(
                        changes.get(changeIndex[0]++), consumer);
            } else {
                consumer.accept(baseContribution);
            }
        });
        while (changeIndex[0] < changes.size()) {
            acceptOverlay(changes.get(changeIndex[0]++), consumer);
        }
    }

    private static void acceptOverlay(
            Map.Entry<IndexPathRef, OverlayValue> entry,
            ContributionConsumer consumer)
            throws IOException {
        OverlayValue value = entry.getValue();
        if (!value.deleted()) {
            FileContribution contribution = value.contribution();
            if (!entry.getKey().equals(contribution.path())) {
                throw new ProjectIndexFormatException(
                        "Project index overlay contribution path is inconsistent");
            }
            consumer.accept(contribution);
        }
    }

    public ProjectIndexMatches matches(ReferenceMatchKey key)
            throws ProjectIndexFormatException {
        requireOpen();
        ProjectIndexMatches stored = base.matches(key);
        List<PackedDefinition> definitions = new ArrayList<>();
        List<PackedLocation> locations = new ArrayList<>();
        stored.definitions().stream()
                .filter(definition -> !overlay.containsKey(definition.object().path()))
                .forEach(definitions::add);
        stored.locations().stream()
                .filter(location -> !overlay.containsKey(location.path()))
                .forEach(locations::add);
        for (OverlayValue value : overlay.values()) {
            if (value.deleted()) {
                continue;
            }
            FileContribution contribution = value.contribution();
            contribution.definitions().stream()
                    .filter(definition -> hasKey(definition.object(), key))
                    .forEach(definitions::add);
            contribution.locations().stream()
                    .filter(location -> hasKey(location, key))
                    .forEach(locations::add);
        }
        definitions.sort(ProjectIndexFormat.DEFINITION_ORDER);
        locations.sort(ProjectIndexFormat.LOCATION_ORDER);
        return new ProjectIndexMatches(definitions, locations);
    }

    /**
     * Whether any file of this index may hold a reference that never bound.
     * While that is true of even one file, adding a definition could re-resolve
     * it, and the index cannot say which file that would be — so an increment
     * that adds a file must not proceed.
     *
     * <p>Both halves are cheap and neither decodes a contribution: the base
     * answers from its UNRESOLVED_FILES record count, and each journal entry is
     * itself a container that answers the same way. A non-empty count in the
     * base is reported even when the journal has since replaced those very
     * files with clean ones — resolving that would cost a scan, and erring
     * towards a full rebuild is the safe direction.
     */
    public boolean anyFileMayHoldUnresolvedReferences()
            throws ProjectIndexFormatException {
        requireOpen();
        if (base.unresolvedFileCount() > 0) {
            return true;
        }
        for (OverlayValue value : overlay.values()) {
            if (value.mayHoldUnresolvedReferences()) {
                return true;
            }
        }
        return false;
    }

    public List<PackedDefinition> definitions(
            ProjectIndexDefinitionSubject subject)
            throws ProjectIndexFormatException {
        return definitions(subject, null);
    }

    public List<PackedDefinition> definitions(
            ProjectIndexDefinitionSubject subject,
            IndexPathRef excludedChangedPath)
            throws ProjectIndexFormatException {
        requireOpen();
        Objects.requireNonNull(subject, "subject");
        List<PackedDefinition> result = new ArrayList<>();
        base.definitions(subject).stream()
                .filter(definition -> includePath(
                        definition.object().path(), excludedChangedPath))
                .forEach(result::add);
        for (Map.Entry<IndexPathRef, OverlayValue> entry : overlay.entrySet()) {
            IndexPathRef path = entry.getKey();
            OverlayValue value = entry.getValue();
            if (value.deleted() || path.equals(excludedChangedPath)) {
                continue;
            }
            result.addAll(value.definitions(subject));
        }
        result.sort(ProjectIndexFormat.DEFINITION_ORDER);
        return List.copyOf(result);
    }

    public List<PackedDefinition> completion(String trigram)
            throws ProjectIndexFormatException {
        requireOpen();
        String wanted = trigram.toUpperCase(Locale.ROOT);
        List<PackedDefinition> result = new ArrayList<>();
        base.completion(wanted).stream()
                .filter(definition -> !overlay.containsKey(definition.object().path()))
                .forEach(result::add);
        for (OverlayValue value : overlay.values()) {
            if (value.deleted()) {
                continue;
            }
            value.contribution().definitions().stream()
                    .filter(definition -> containsTrigram(
                            ProjectIndexRecords.completionName(definition), wanted))
                    .forEach(result::add);
        }
        result.sort(ProjectIndexFormat.DEFINITION_ORDER);
        return List.copyOf(result);
    }

    public Set<IndexPathRef> reverseDependencies(ReferenceMatchKey key)
            throws ProjectIndexFormatException {
        requireOpen();
        Set<IndexPathRef> result = new java.util.TreeSet<>(ProjectIndexFormat.PATH_ORDER);
        result.addAll(base.reverseDependencies(key));
        result.removeAll(overlay.keySet());
        for (OverlayValue value : overlay.values()) {
            if (value.deleted()) {
                continue;
            }
            boolean dependent = value.contribution().locations().stream()
                    .anyMatch(location -> location.locationType()
                            != ObjectLocation.LocationType.DEFINITION
                            && hasKey(location, key));
            if (dependent) {
                result.add(value.stamp().path());
            }
        }
        return Collections.unmodifiableSet(result);
    }

    public ProjectIndexData materialize() throws ProjectIndexFormatException {
        requireOpen();
        List<FileContribution> files = new ArrayList<>();
        for (ProjectFileStamp stamp : fileStamps()) {
            files.add(contribution(stamp.path()).orElseThrow(() ->
                    new IllegalStateException("Manifest path has no contribution")));
        }
        return new ProjectIndexData(manifest(), files);
    }

    public int decodedContributionCount() {
        return decodedPaths.size();
    }

    public long cachedBytes() {
        return cache.residentBytes();
    }

    public long cacheReadBytes() {
        return cache.bytesRead();
    }

    public long cacheBlocksRead() {
        return cache.blocksRead();
    }

    public ProjectIndexRevision revision() {
        requireOpen();
        ProjectIndexManifest manifest = base.manifest();
        return new ProjectIndexRevision(publicationId,
                committedJournalLength, manifest.generation(),
                ProjectIndexIdentity.from(manifest));
    }

    public long metadataBytes() {
        return base.metadataBytes()
                + overlay.values().stream()
                        .filter(value -> value.lazy != null)
                        .mapToLong(value -> value.lazy.metadataBytes())
                        .sum();
    }

    public int canonicalCodecBlocks() {
        return base.codecBlockCount();
    }

    long committedJournalLength() {
        return committedJournalLength;
    }

    int committedJournalEntries() {
        return committedJournalEntries;
    }

    int committedJournalEntriesForTests() {
        return committedJournalEntries;
    }

    String publicationId() {
        return publicationId;
    }

    Path basePathForTests() {
        return basePath;
    }

    Path journalPathForTests() {
        return journalPath;
    }

    long codecOffsetForTests() {
        return base.codecOffsetForTests();
    }

    long firstDefinitionBlockOffsetForTests() {
        return base.firstDefinitionBlockOffsetForTests();
    }

    int definitionBlockCountForTests() {
        return base.definitionBlockCountForTests();
    }

    /**
     * How many packed definition blocks this index has walked while decoding,
     * counted since the index was opened.
     *
     * <p>A block is walked whole: decoding one record in it reads past every
     * other record in it. The count therefore says how much of the index a
     * lookup had to touch, which the number of definitions it returned does
     * not.
     *
     * <p>Only the packed base is counted. A journal entry the overlay holds
     * lazily carries a base of its own and counts its own traversals, and
     * those are not added here, so this is a lower bound whenever the overlay
     * is not empty.
     *
     * @return definition blocks the packed base walked since this view opened
     */
    public long definitionBlockTraversals() {
        return base.definitionBlockTraversals();
    }

    /**
     * What resolving strings out of the packed dictionary has cost this index,
     * counted since it was opened.
     *
     * <p>Every lookup pays this before it can read anything: its subject names
     * a schema and an object in characters, and the index is keyed by
     * dictionary identifiers, so the names have to be searched for first. A
     * lookup whose subject is not in the dictionary at all pays the search and
     * stops there, which is why a lookup that returned nothing is not a lookup
     * that cost nothing.
     *
     * <p>Only the packed base is counted, on the same grounds as
     * {@link #definitionBlockTraversals()}: a journal entry in the overlay
     * carries a dictionary of its own, and an overlay lookup walks that entry
     * in memory besides. This is therefore a lower bound whenever the overlay
     * is not empty.
     *
     * @return the packed base's running totals since this view opened
     */
    public ProjectIndexStringProbes stringProbes() {
        return base.stringProbes();
    }

    List<PackedDefinition> definitionsWithBudgetForTests(
            ProjectIndexDefinitionSubject subject, long allocationLimit)
            throws ProjectIndexFormatException {
        requireOpen();
        Objects.requireNonNull(subject, "subject");
        if (!overlay.isEmpty()) {
            throw new IllegalStateException(
                    "A bounded base lookup requires an empty overlay");
        }
        return base.definitions(subject, allocationLimit);
    }

    @Override
    public void close() {
        if (!open) {
            return;
        }
        open = false;
        base.close();
        overlay.values().forEach(OverlayValue::close);
        try {
            baseChannel.close();
        } catch (IOException ex) {
            // Closing a cache view must remain best-effort.
        }
        try {
            journalChannel.close();
        } catch (IOException ex) {
            // Closing a cache view must remain best-effort.
        }
    }

    private void requireOpen() {
        if (!open) {
            throw new IllegalStateException("Project index view is closed");
        }
    }

    private static boolean hasKey(PackedLocation location, ReferenceMatchKey key) {
        return location.reference() != null && location.reference().type() != null
                && ReferenceMatchKey.from(location).equals(key);
    }

    private boolean includePath(IndexPathRef path,
            IndexPathRef excludedChangedPath) {
        return !overlay.containsKey(path) && !path.equals(excludedChangedPath);
    }

    private static boolean containsTrigram(String name, String wanted) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String upper = name.toUpperCase(Locale.ROOT);
        if (upper.length() <= 3) {
            return upper.equals(wanted);
        }
        if (wanted.length() != 3) {
            return false;
        }
        return upper.contains(wanted);
    }

    static final class OverlayValue {
        private final ProjectFileStamp stamp;
        private final LazyProjectIndexBase lazy;
        private final FileContribution direct;
        private final boolean deleted;
        private volatile boolean lazyStampValidated;

        private OverlayValue(ProjectFileStamp stamp, LazyProjectIndexBase lazy,
                FileContribution direct, boolean deleted) {
            this.stamp = stamp;
            this.lazy = lazy;
            this.direct = direct;
            this.deleted = deleted;
        }

        static OverlayValue tombstone() {
            return new OverlayValue(null, null, null, true);
        }

        static OverlayValue direct(ProjectFileStamp stamp, FileContribution contribution) {
            return new OverlayValue(stamp, null, contribution, false);
        }

        static OverlayValue lazy(ProjectFileStamp stamp, LazyProjectIndexBase lazy) {
            return new OverlayValue(stamp, lazy, null, false);
        }

        ProjectFileStamp stamp() {
            return stamp;
        }

        boolean deleted() {
            return deleted;
        }

        FileContribution contribution() throws ProjectIndexFormatException {
            if (deleted) {
                return null;
            }
            if (direct != null) {
                return direct;
            }
            validateLazyStamp();
            FileContribution contribution = lazy.contribution(stamp.path());
            if (contribution == null) {
                throw new ProjectIndexFormatException(
                        "Project index journal contribution path does not match its stamp");
            }
            return contribution;
        }

        ProjectIndexFileMetadata file()
                throws ProjectIndexFormatException {
            if (deleted) {
                return null;
            }
            if (direct != null) {
                if (!stamp.path().equals(direct.path())) {
                    throw new ProjectIndexFormatException(
                            "Project index overlay contribution path is inconsistent");
                }
                return new ProjectIndexFileMetadata(
                        stamp, direct.definitions());
            }
            validateLazyStamp();
            ProjectIndexFileMetadata file =
                    lazy.file(stamp.path());
            if (file == null) {
                throw new ProjectIndexFormatException(
                        "Project index journal contribution path does not match its stamp");
            }
            return file;
        }

        boolean mayHoldUnresolvedReferences()
                throws ProjectIndexFormatException {
            if (deleted) {
                return false;
            }
            if (direct != null) {
                return direct.unresolvedAny();
            }
            validateLazyStamp();
            return lazy.unresolvedFileCount() > 0;
        }

        List<PackedDefinition> definitions(
                ProjectIndexDefinitionSubject subject)
                throws ProjectIndexFormatException {
            if (deleted) {
                return List.of();
            }
            if (lazy != null) {
                validateLazyStamp();
                return lazy.definitions(subject);
            }
            return direct.definitions().stream()
                    .filter(definition -> subject.matches(
                            ReferenceMatchKey.from(definition.object())))
                    .toList();
        }

        private void validateLazyStamp()
                throws ProjectIndexFormatException {
            if (lazyStampValidated) {
                return;
            }
            synchronized (this) {
                if (!lazyStampValidated) {
                    if (!lazy.stamps().equals(List.of(stamp))) {
                        throw new ProjectIndexFormatException(
                                "Project index journal contribution stamp is inconsistent");
                    }
                    lazyStampValidated = true;
                }
            }
        }

        void close() {
            if (lazy != null) {
                lazy.close();
            }
        }
    }
}
