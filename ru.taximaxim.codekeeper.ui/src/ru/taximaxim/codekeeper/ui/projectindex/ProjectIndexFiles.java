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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.ToLongFunction;

import org.pgcodekeeper.core.database.api.loader.ProjectInputFingerprint;

import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexWarmValidator.CurrentFile;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexWarmValidator.DigestReader;

public final class ProjectIndexFiles {

    private static final int HASH_BUFFER_BYTES = 64 << 10;

    private ProjectIndexFiles() {
    }

    public static List<CurrentFile> inspect(List<Path> inputs, Path projectRoot,
            Path libraryRoot, ToLongFunction<IndexPathRef> modificationStamp)
            throws IOException {
        Objects.requireNonNull(inputs, "inputs");
        Path project = normalizeRoot(projectRoot, "projectRoot");
        Path library = normalizeRoot(libraryRoot, "libraryRoot");
        Objects.requireNonNull(modificationStamp, "modificationStamp");
        List<CurrentFile> result = new ArrayList<>(inputs.size());
        Set<IndexPathRef> paths = new HashSet<>();
        for (Path input : inputs) {
            Path absolute = Objects.requireNonNull(input, "input")
                    .toAbsolutePath().normalize();
            IndexPathRef path = toIndexPath(absolute, project, library);
            if (!paths.add(path)) {
                throw new IOException("Duplicate project index input path");
            }
            BasicFileAttributes attributes = Files.readAttributes(
                    absolute, BasicFileAttributes.class);
            if (!attributes.isRegularFile()) {
                throw new IOException("Project index input is not a regular file");
            }
            result.add(new CurrentFile(path,
                    modificationStamp.applyAsLong(path), attributes.size(),
                    attributes.lastModifiedTime().toMillis()));
        }
        return List.copyOf(result);
    }

    public static List<ProjectFileStamp> hashAll(List<CurrentFile> files,
            ProjectIndexPathResolver resolver, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        Objects.requireNonNull(files, "files");
        Objects.requireNonNull(resolver, "resolver");
        Objects.requireNonNull(cancelled, "cancelled");
        List<ProjectFileStamp> result = new ArrayList<>(files.size());
        DigestReader reader = digestReader(resolver, cancelled);
        for (CurrentFile file : files) {
            checkCancelled(cancelled);
            result.add(new ProjectFileStamp(file.path(),
                    file.eclipseModificationStamp(), file.size(),
                    file.lastModifiedMillis(), reader.sha256(file)));
        }
        return List.copyOf(result);
    }

    public static Optional<List<ProjectFileStamp>>
            stampsFromCapturedFingerprints(
                    List<CurrentFile> files,
                    List<ProjectInputFingerprint> fingerprints,
                    ProjectIndexPathResolver resolver) {
        Objects.requireNonNull(files, "files");
        Objects.requireNonNull(fingerprints, "fingerprints");
        Objects.requireNonNull(resolver, "resolver");
        Map<Path, ProjectInputFingerprint> byPath =
                new HashMap<>(fingerprints.size());
        for (ProjectInputFingerprint fingerprint :
                fingerprints) {
            ProjectInputFingerprint previous = byPath.put(
                    fingerprint.path()
                            .toAbsolutePath().normalize(),
                    fingerprint);
            if (previous != null) {
                return Optional.empty();
            }
        }
        if (byPath.size() != files.size()) {
            return Optional.empty();
        }
        List<ProjectFileStamp> result =
                new ArrayList<>(files.size());
        for (CurrentFile file : files) {
            Path absolute = Path.of(
                    resolver.resolve(file.path()))
                    .toAbsolutePath().normalize();
            ProjectInputFingerprint fingerprint =
                    byPath.remove(absolute);
            if (fingerprint == null
                    || fingerprint.byteCount()
                            != file.size()) {
                return Optional.empty();
            }
            result.add(new ProjectFileStamp(
                    file.path(),
                    file.eclipseModificationStamp(),
                    file.size(),
                    file.lastModifiedMillis(),
                    fingerprint.sha256()));
        }
        return byPath.isEmpty()
                ? Optional.of(List.copyOf(result))
                : Optional.empty();
    }

    public static byte[] sha256(CurrentFile file,
            ProjectIndexPathResolver resolver, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        Objects.requireNonNull(file, "file");
        return digestReader(resolver, cancelled).sha256(file);
    }

    /**
     * Creates a reader for one sequential validation pass. The reader reuses
     * its digest and read buffer across files; concurrent passes must each
     * create their own reader.
     */
    public static DigestReader digestReader(ProjectIndexPathResolver resolver,
            BooleanSupplier cancelled) {
        Objects.requireNonNull(resolver, "resolver");
        Objects.requireNonNull(cancelled, "cancelled");
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
        byte[] buffer = new byte[HASH_BUFFER_BYTES];
        return file -> {
            Objects.requireNonNull(file, "file");
            // A preceding file may have failed or been cancelled mid-read.
            digest.reset();
            checkCancelled(cancelled);
            Path path = Path.of(resolver.resolve(file.path()));
            try (InputStream input = Files.newInputStream(path)) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    checkCancelled(cancelled);
                    if (read != 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            checkCancelled(cancelled);
            return digest.digest();
        };
    }

    private static IndexPathRef toIndexPath(Path input, Path project,
            Path library) throws IOException {
        if (input.startsWith(project)) {
            return new IndexPathRef(IndexPathOrigin.PROJECT,
                    project.relativize(input).toString());
        }
        if (input.startsWith(library)) {
            return new IndexPathRef(IndexPathOrigin.LIBRARY,
                    library.relativize(input).toString());
        }
        throw new IOException("Project index input is outside supported roots");
    }

    private static Path normalizeRoot(Path root, String name) {
        return Objects.requireNonNull(root, name).toAbsolutePath().normalize();
    }

    private static void checkCancelled(BooleanSupplier cancelled)
            throws InterruptedException {
        if (cancelled.getAsBoolean()) {
            throw new InterruptedException("Project index hashing cancelled");
        }
    }
}
