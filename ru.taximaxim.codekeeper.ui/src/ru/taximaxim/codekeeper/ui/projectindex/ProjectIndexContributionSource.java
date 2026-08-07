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
import static ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexFormat.MAX_RECORD_COUNT;
import static ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexFormat.PATH_ORDER;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Replayable canonical input for bounded project-index encoding.
 * Implementations must expose a stable snapshot for their whole lifetime.
 */
interface ProjectIndexContributionSource {

    ProjectIndexManifest manifest();

    PassCounts replay(BooleanSupplier cancelled,
            ContributionConsumer consumer) throws IOException;

    static ProjectIndexContributionSource from(ProjectIndexData source) {
        return new DataSource(source);
    }

    static ProjectIndexContributionSource from(
            ProjectIndexReplaySource source) {
        return new ReplaySource(source);
    }

    @FunctionalInterface
    interface ContributionConsumer {

        void accept(int fileId, int definitionStart, int locationStart,
                FileContribution contribution) throws IOException;
    }

    record PassCounts(int files, int definitions, int locations,
            int unresolvedFiles) {
    }

    final class DataSource implements ProjectIndexContributionSource {

        private final ProjectIndexManifest manifest;
        private final List<FileContribution> files;

        private DataSource(ProjectIndexData source) {
            ProjectIndexData checked =
                    Objects.requireNonNull(source, "source");
            files = checked.files().stream()
                    .sorted(Comparator.comparing(
                            FileContribution::path, PATH_ORDER))
                    .toList();
            ProjectIndexManifest original = checked.manifest();
            List<ProjectFileStamp> stamps = original.files().stream()
                    .sorted(Comparator.comparing(
                            ProjectFileStamp::path, PATH_ORDER))
                    .toList();
            manifest = new ProjectIndexManifest(
                    original.formatMajor(), original.formatMinor(),
                    original.parserAbi(), original.coreVersion(),
                    original.uiVersion(), original.databaseType(),
                    original.projectIdentity(), original.configSha256(),
                    original.generation(), stamps);
        }

        @Override
        public ProjectIndexManifest manifest() {
            return manifest;
        }

        @Override
        public PassCounts replay(BooleanSupplier cancelled,
                ContributionConsumer consumer) throws IOException {
            var pass = new Pass(manifest, cancelled, consumer);
            var polling = new CancellationPolling(cancelled);
            polling.checkNow();
            for (FileContribution file : files) {
                pass.acceptValidated(
                        canonicalize(file, polling));
            }
            return pass.finish();
        }

        private static FileContribution canonicalize(
                FileContribution file, CancellationPolling polling)
                throws ProjectIndexStore.WriteCancelledException {
            List<PackedDefinition> definitions = canonicalize(
                    file.definitions(), DEFINITION_ORDER, polling);
            List<PackedLocation> locations = canonicalize(
                    file.locations(), LOCATION_ORDER, polling);
            if (definitions == file.definitions()
                    && locations == file.locations()) {
                return file;
            }
            polling.checkNow();
            FileContribution canonical = new FileContribution(
                    file.path(), definitions, locations,
                    file.unresolvedCandidates(), file.unresolvedAny());
            polling.checkNow();
            return canonical;
        }

        private static <T> List<T> canonicalize(List<T> values,
                Comparator<T> order, CancellationPolling polling)
                throws ProjectIndexStore.WriteCancelledException {
            if (isCanonical(values, order, polling)) {
                return values;
            }
            List<T> canonical = new ArrayList<>(values.size());
            for (T value : values) {
                polling.operation();
                canonical.add(value);
            }
            polling.checkNow();
            try {
                canonical.sort((left, right) -> {
                    polling.sortOperation();
                    return order.compare(left, right);
                });
            } catch (SortCancelledException ex) {
                throw ex.cancelled;
            }
            polling.checkNow();
            return canonical;
        }

        private static <T> boolean isCanonical(List<T> values,
                Comparator<T> order, CancellationPolling polling)
                throws ProjectIndexStore.WriteCancelledException {
            for (int index = 1; index < values.size(); index++) {
                polling.operation();
                if (order.compare(values.get(index - 1),
                        values.get(index)) > 0) {
                    return false;
                }
            }
            return true;
        }

        private static final class CancellationPolling {
            private static final int POLL_MASK = 1024 - 1;

            private final BooleanSupplier cancelled;
            private int operations;

            private CancellationPolling(BooleanSupplier cancelled) {
                this.cancelled =
                        Objects.requireNonNull(cancelled, "cancelled");
            }

            private void operation()
                    throws ProjectIndexStore.WriteCancelledException {
                if ((++operations & POLL_MASK) == 0) {
                    checkNow();
                }
            }

            private void sortOperation() {
                try {
                    operation();
                } catch (ProjectIndexStore.WriteCancelledException ex) {
                    throw new SortCancelledException(ex);
                }
            }

            private void checkNow()
                    throws ProjectIndexStore.WriteCancelledException {
                if (cancelled.getAsBoolean()) {
                    throw new ProjectIndexStore.WriteCancelledException();
                }
            }
        }

        private static final class SortCancelledException
                extends RuntimeException {
            private static final long serialVersionUID = 1L;

            private final ProjectIndexStore.WriteCancelledException cancelled;

            private SortCancelledException(
                    ProjectIndexStore.WriteCancelledException cancelled) {
                super(cancelled);
                this.cancelled = cancelled;
            }
        }
    }

    final class ReplaySource implements ProjectIndexContributionSource {

        private final ProjectIndexReplaySource source;

        private ReplaySource(ProjectIndexReplaySource source) {
            this.source = Objects.requireNonNull(source, "source");
        }

        @Override
        public ProjectIndexManifest manifest() {
            return source.manifest();
        }

        @Override
        public PassCounts replay(BooleanSupplier cancelled,
                ContributionConsumer consumer) throws IOException {
            var pass = new Pass(manifest(), cancelled, consumer);
            source.forEach(pass::accept);
            return pass.finish();
        }
    }

    final class Pass {

        private final ProjectIndexManifest manifest;
        private final BooleanSupplier cancelled;
        private final ContributionConsumer consumer;
        private int files;
        private int definitions;
        private int locations;
        private int unresolvedFiles;
        private long validationOperations;

        Pass(ProjectIndexManifest manifest,
                BooleanSupplier cancelled,
                ContributionConsumer consumer) {
            this.manifest = Objects.requireNonNull(manifest, "manifest");
            this.cancelled =
                    Objects.requireNonNull(cancelled, "cancelled");
            this.consumer =
                    Objects.requireNonNull(consumer, "consumer");
        }

        void accept(FileContribution contribution)
                throws IOException {
            accept(contribution, false);
        }

        private void acceptValidated(
                FileContribution contribution) throws IOException {
            accept(contribution, true);
        }

        private void accept(FileContribution contribution,
                boolean canonicalized) throws IOException {
            requireNotCancelled();
            FileContribution file =
                    Objects.requireNonNull(contribution, "contribution");
            if (files >= manifest.files().size()
                    || !manifest.files().get(files).path()
                            .equals(file.path())) {
                throw new IllegalArgumentException(
                        "Project index contribution does not match manifest order");
            }
            if (!canonicalized) {
                requireCanonical(file.definitions(), DEFINITION_ORDER,
                        "definitions");
                requireCanonical(file.locations(), LOCATION_ORDER,
                        "locations");
            }
            int nextDefinitions = checkedCount("definition", definitions,
                    file.definitions().size());
            int nextLocations = checkedCount("location", locations,
                    file.locations().size());
            consumer.accept(files, definitions, locations, file);
            files++;
            definitions = nextDefinitions;
            locations = nextLocations;
            if (file.unresolvedAny()
                    || !file.unresolvedCandidates().isEmpty()) {
                unresolvedFiles++;
            }
            requireNotCancelled();
        }

        private PassCounts finish() throws IOException {
            requireNotCancelled();
            if (files != manifest.files().size()) {
                throw new IllegalArgumentException(
                        "Project index source did not provide every manifest contribution");
            }
            return new PassCounts(files, definitions, locations,
                    unresolvedFiles);
        }

        private void requireNotCancelled()
                throws ProjectIndexStore.WriteCancelledException {
            if (cancelled.getAsBoolean()) {
                throw new ProjectIndexStore.WriteCancelledException();
            }
        }

        private <T> void requireCanonical(List<T> values,
                Comparator<T> order, String label)
                throws ProjectIndexStore.WriteCancelledException {
            for (int index = 1; index < values.size(); index++) {
                if ((++validationOperations & 1023) == 0) {
                    requireNotCancelled();
                }
                if (order.compare(values.get(index - 1),
                        values.get(index)) > 0) {
                    throw new IllegalArgumentException(
                            "Project index " + label
                                    + " are not in canonical order");
                }
            }
        }

        private static int checkedCount(String label, int current,
                int additional) {
            int result = Math.addExact(current, additional);
            if (result > MAX_RECORD_COUNT) {
                throw new IllegalArgumentException(
                        "Project index " + label
                                + " count exceeds the format limit");
            }
            return result;
        }
    }

}
