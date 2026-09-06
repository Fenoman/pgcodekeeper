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

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import org.pgcodekeeper.core.database.api.loader.ProjectInputFingerprint;

import ru.taximaxim.codekeeper.ui.projectindex.ProjectFileStamp;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexFiles;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexPathResolver;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexWarmValidator;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexWarmValidator.CurrentFile;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexWarmValidator.DigestReader;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexWarmValidator.Result;

/**
 * Exact raw-byte snapshot of every SQL file consumed by a Get Changes project
 * loader, including schemas intentionally omitted from the background index.
 */
final class ProjectComparisonInputSnapshot {

    private final List<ProjectFileStamp> files;

    private ProjectComparisonInputSnapshot(List<ProjectFileStamp> files) {
        this.files = List.copyOf(files);
    }

    static Optional<ProjectComparisonInputSnapshot> capture(
            List<CurrentFile> current,
            List<ProjectInputFingerprint> consumed,
            ProjectIndexPathResolver resolver,
            BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        Objects.requireNonNull(current, "current"); //$NON-NLS-1$
        Objects.requireNonNull(consumed, "consumed"); //$NON-NLS-1$
        Objects.requireNonNull(resolver, "resolver"); //$NON-NLS-1$
        Objects.requireNonNull(cancelled, "cancelled"); //$NON-NLS-1$

        Optional<List<ProjectFileStamp>> expected =
                ProjectIndexFiles.stampsFromCapturedFingerprints(
                        current, consumed, resolver);
        if (expected.isEmpty()) {
            return Optional.empty();
        }
        List<ProjectFileStamp> reread = ProjectIndexFiles.hashAll(
                current, resolver, cancelled);
        return expected.get().equals(reread)
                ? Optional.of(new ProjectComparisonInputSnapshot(reread))
                : Optional.empty();
    }

    /**
     * Wraps stamps that were persisted by an earlier session. The stamps are
     * the whole snapshot, so a restored snapshot validates exactly like the one
     * that was captured in this process.
     *
     * @param files persisted per-file stamps
     * @return snapshot over those stamps
     */
    static ProjectComparisonInputSnapshot of(List<ProjectFileStamp> files) {
        return new ProjectComparisonInputSnapshot(
                Objects.requireNonNull(files, "files")); //$NON-NLS-1$
    }

    Result validate(List<CurrentFile> current,
            ProjectIndexPathResolver resolver,
            BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        Objects.requireNonNull(resolver, "resolver"); //$NON-NLS-1$
        DigestReader reader = ProjectIndexFiles.digestReader(resolver, cancelled);
        return validate(current, reader,
                cancelled);
    }

    Result validate(List<CurrentFile> current,
            DigestReader digestReader,
            BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        return ProjectIndexWarmValidator.validate(
                files, current, digestReader, cancelled);
    }

    Result validateContent(List<CurrentFile> current,
            ProjectIndexPathResolver resolver, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        return ProjectIndexWarmValidator.validateContent(files, current,
                ProjectIndexFiles.digestReader(resolver, cancelled), cancelled);
    }

    int fileCount() {
        return files.size();
    }

    List<ProjectFileStamp> files() {
        return files;
    }
}
