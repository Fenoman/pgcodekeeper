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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgcodekeeper.core.analysis.AnalysisReplayPayload;
import org.pgcodekeeper.core.analysis.AnalysisReplayPayload.FileReferences;
import org.pgcodekeeper.core.analysis.AnalysisReplayPayload.StatementDependencies;
import org.pgcodekeeper.core.analysis.StatementAddress;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation.LocationType;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;

import ru.taximaxim.codekeeper.ui.pgdbproject.parser.ProjectAnalysisStore.OpenResult;
import ru.taximaxim.codekeeper.ui.pgdbproject.parser.ProjectAnalysisStore.Status;
import ru.taximaxim.codekeeper.ui.pgdbproject.parser.ProjectComparisonProfile.EffectiveVersion;
import ru.taximaxim.codekeeper.ui.projectindex.IndexPathOrigin;
import ru.taximaxim.codekeeper.ui.projectindex.IndexPathRef;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectFileStamp;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexPathResolver;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexWarmValidator.CurrentFile;

/**
 * The persistent analyzed-model store: what it serves, and every way it must
 * refuse to serve rather than answer with a model that no longer fits.
 */
class ProjectAnalysisStoreTest {

    private static final String DIGEST = "digest-of-the-current-settings";
    private static final EffectiveVersion VERSION =
            new EffectiveVersion(160000, "16.0", "TestVersion");
    private static final IndexPathRef PATH =
            new IndexPathRef(IndexPathOrigin.PROJECT, "SCHEMA/app/table.sql");
    private static final BooleanSupplier NOT_CANCELLED = () -> false;

    @Test
    void aPublishedResultIsServedBackUnchanged(@TempDir Path directory)
            throws Exception {
        var store = new ProjectAnalysisStore(directory);
        AnalysisReplayPayload payload = payload();
        long bytes = store.publish(DIGEST, VERSION, stamps(), payload);
        assertTrue(bytes > 0, "publication must write something");

        OpenResult result = store.open(expect(DIGEST), inputs(current()), NOT_CANCELLED);
        assertEquals(Status.HIT, result.status());
        assertEquals(DIGEST, result.storedProfileDigest());
        assertEquals(payload.statementCount(), result.payload().statementCount());
        assertEquals(payload.dependencies(), result.payload().dependencies());
        assertEquals(payload.references().get(0).locations(),
                result.payload().references().get(0).locations());
        assertEquals(payload.suppressedRoutines(),
                result.payload().suppressedRoutines());
    }

    @Test
    void publishingTwiceReplacesTheStoredResult(@TempDir Path directory)
            throws Exception {
        var store = new ProjectAnalysisStore(directory);
        store.publish(DIGEST, VERSION, stamps(), payload());
        store.publish(DIGEST, VERSION, stamps(), emptyPayload());

        OpenResult result = store.open(expect(DIGEST), inputs(current()), NOT_CANCELLED);
        assertEquals(Status.HIT, result.status());
        assertEquals(0, result.payload().dependencyCount(),
                "the newer publication must win");
        assertEquals(1, publishedFiles(directory),
                "publication must not leave temporaries behind");
    }

    @Test
    void anEmptyStoreIsAMiss(@TempDir Path directory) throws Exception {
        OpenResult result = new ProjectAnalysisStore(directory)
                .open(expect(DIGEST), inputs(current()), NOT_CANCELLED);
        assertEquals(Status.MISS, result.status());
        assertNull(result.payload());
    }

    @Test
    void changedSettingsMakeTheStoredResultStale(@TempDir Path directory)
            throws Exception {
        var store = new ProjectAnalysisStore(directory);
        store.publish(DIGEST, VERSION, stamps(), payload());

        OpenResult result = store.open(expect("another-digest"),
                inputs(current()), NOT_CANCELLED);
        assertEquals(Status.STALE, result.status());
        assertNull(result.payload());
        assertTrue(Files.exists(store.file()),
                "a stale store is not damaged and must be kept");
    }

    @Test
    void settingsThatCannotFormAProfileMakeTheStoredResultStale(
            @TempDir Path directory) throws Exception {
        var store = new ProjectAnalysisStore(directory);
        store.publish(DIGEST, VERSION, stamps(), payload());

        OpenResult result = store.open(version -> java.util.Optional.empty(),
                inputs(current()), NOT_CANCELLED);
        assertEquals(Status.STALE, result.status());
    }

    @Test
    void theStoredEffectiveVersionIsHandedToTheCaller(@TempDir Path directory)
            throws Exception {
        var store = new ProjectAnalysisStore(directory);
        store.publish(DIGEST, VERSION, stamps(), payload());

        var seen = new ArrayList<EffectiveVersion>();
        store.open(version -> {
            seen.add(version);
            return java.util.Optional.of(DIGEST);
        }, inputs(current()), NOT_CANCELLED);
        assertEquals(List.of(VERSION), seen,
                "the caller must be able to re-derive its digest for that version");
    }

    @Test
    void aChangedFileMakesTheStoredResultStale(@TempDir Path directory)
            throws Exception {
        var store = new ProjectAnalysisStore(directory);
        store.publish(DIGEST, VERSION, stamps(), payload());

        OpenResult result = store.open(expect(DIGEST),
                inputs(List.of(new CurrentFile(PATH, 10, 21, 30))),
                NOT_CANCELLED);
        assertEquals(Status.STALE, result.status(),
                "a file whose size changed cannot describe the stored result");
    }

    @Test
    void anAddedFileMakesTheStoredResultStale(@TempDir Path directory)
            throws Exception {
        var store = new ProjectAnalysisStore(directory);
        store.publish(DIGEST, VERSION, stamps(), payload());

        var added = new IndexPathRef(IndexPathOrigin.PROJECT, "SCHEMA/app/new.sql");
        OpenResult result = store.open(expect(DIGEST),
                inputs(List.of(new CurrentFile(PATH, 10, 20, 30),
                        new CurrentFile(added, 10, 20, 30))),
                NOT_CANCELLED);
        assertEquals(Status.STALE, result.status());
    }

    @Test
    void aRemovedFileMakesTheStoredResultStale(@TempDir Path directory)
            throws Exception {
        var store = new ProjectAnalysisStore(directory);
        store.publish(DIGEST, VERSION, stamps(), payload());

        OpenResult result = store.open(expect(DIGEST), inputs(List.of()),
                NOT_CANCELLED);
        assertEquals(Status.STALE, result.status());
    }

    @Test
    void aFlippedByteInThePayloadIsDamageAndTheStoreIsDiscarded(
            @TempDir Path directory) throws Exception {
        var store = new ProjectAnalysisStore(directory);
        store.publish(DIGEST, VERSION, stamps(), payload());
        flipLastByte(store.file());

        OpenResult result = store.open(expect(DIGEST), inputs(current()), NOT_CANCELLED);
        assertEquals(Status.CORRUPT, result.status());
        store.discard();
        assertFalse(Files.exists(store.file()),
                "proven damage may be discarded");
    }

    @Test
    void aFlippedByteInTheHeaderIsDamage(@TempDir Path directory)
            throws Exception {
        var store = new ProjectAnalysisStore(directory);
        store.publish(DIGEST, VERSION, stamps(), payload());
        byte[] bytes = Files.readAllBytes(store.file());
        bytes[30] ^= 0x40;
        Files.write(store.file(), bytes);

        assertEquals(Status.CORRUPT, store.open(expect(DIGEST),
                inputs(current()), NOT_CANCELLED).status());
    }

    @Test
    void aTruncatedStoreIsDamage(@TempDir Path directory) throws Exception {
        var store = new ProjectAnalysisStore(directory);
        store.publish(DIGEST, VERSION, stamps(), payload());
        try (var channel = java.nio.channels.FileChannel.open(store.file(),
                StandardOpenOption.WRITE)) {
            channel.truncate(channel.size() / 2);
        }

        assertEquals(Status.CORRUPT, store.open(expect(DIGEST),
                inputs(current()), NOT_CANCELLED).status());
    }

    @Test
    void aForeignFileIsDamage(@TempDir Path directory) throws Exception {
        var store = new ProjectAnalysisStore(directory);
        Files.createDirectories(directory);
        Files.writeString(store.file(), "this is not an analysis cache at all",
                StandardCharsets.UTF_8);

        assertEquals(Status.CORRUPT, store.open(expect(DIGEST),
                inputs(current()), NOT_CANCELLED).status());
    }

    @Test
    void anUnreadableInputFileIsRetryableAndKeepsTheStore(
            @TempDir Path directory) throws Exception {
        var store = new ProjectAnalysisStore(directory);
        store.publish(DIGEST, VERSION, stamps(), payload());

        // An eclipse stamp of -1 disables the metadata fast path, so validation
        // has to read the file - and this one cannot be read.
        ProjectIndexPathResolver failing = path -> {
            throw new IllegalStateException("unreadable");
        };
        OpenResult result = store.open(expect(DIGEST),
                () -> new ProjectAnalysisStore.Inputs(
                        List.of(new CurrentFile(PATH, -1, 20, 30)), failing),
                NOT_CANCELLED);
        assertEquals(Status.RETRYABLE, result.status(),
                "the project could not be read; that says nothing about the store");
        assertTrue(Files.exists(store.file()),
                "open must never delete on its own");
    }

    @Test
    void anInputFileThatVanishedIsRetryableAndKeepsTheStore(
            @TempDir Path directory) throws Exception {
        var store = new ProjectAnalysisStore(directory);
        store.publish(DIGEST, VERSION, stamps(), payload());

        OpenResult result = store.open(expect(DIGEST),
                () -> new ProjectAnalysisStore.Inputs(
                        List.of(new CurrentFile(PATH, -1, 20, 30)), resolver()),
                NOT_CANCELLED);
        assertEquals(Status.RETRYABLE, result.status(),
                "a project file that is gone is not a damaged store");
        assertTrue(Files.exists(store.file()));
    }

    @Test
    void aStoreThatCannotBeOpenedIsRetryable(@TempDir Path directory)
            throws Exception {
        var store = new ProjectAnalysisStore(directory);
        store.publish(DIGEST, VERSION, stamps(), payload());
        // A directory in place of the file is readable metadata but an
        // unreadable stream: exactly the shape of a transient storage fault.
        Files.delete(store.file());
        Files.createDirectories(store.file());

        OpenResult result = store.open(expect(DIGEST), inputs(current()), NOT_CANCELLED);
        assertEquals(Status.RETRYABLE, result.status());
        assertTrue(Files.exists(store.file()),
                "a store that could not be read must be kept");
    }

    @Test
    void theProfileDigestIsStableAndSensitive(@TempDir Path directory)
            throws Exception {
        String baseline = ProjectAnalysisIdentityTestSupport.digest();
        assertEquals(baseline, ProjectAnalysisIdentityTestSupport.digest(),
                "the digest must be stable for unchanged settings");
        assertNotEquals(baseline, ProjectAnalysisIdentityTestSupport
                .digestWithIgnoreList(directory, "hidden_table"),
                "a changed ignore list must invalidate the stored result");
        assertNotEquals(baseline, ProjectAnalysisIdentityTestSupport
                .digestWithConfiguration("another-configuration"),
                "changed project configuration files must invalidate it");
        assertNotEquals(
                ProjectAnalysisIdentityTestSupport
                        .digestWithIgnoreList(directory, "one_table"),
                ProjectAnalysisIdentityTestSupport
                        .digestWithIgnoreList(directory, "other_table"),
                "different ignore lists must produce different digests");
    }

    private static long publishedFiles(Path directory) throws IOException {
        try (var children = Files.list(directory)) {
            return children.count();
        }
    }

    private static void flipLastByte(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length - 1] ^= 0x01;
        Files.write(file, bytes);
    }

    private static java.util.function.Function<EffectiveVersion,
            java.util.Optional<String>> expect(String digest) {
        return version -> java.util.Optional.of(digest);
    }

    private static ProjectAnalysisStore.CurrentInputs inputs(
            List<CurrentFile> files) {
        return () -> new ProjectAnalysisStore.Inputs(files, resolver());
    }

    private static List<CurrentFile> current() {
        return List.of(new CurrentFile(PATH, 10, 20, 30));
    }

    private static ProjectIndexPathResolver resolver() {
        return path -> "/nowhere/" + path.relativePath();
    }

    private static List<ProjectFileStamp> stamps() throws Exception {
        return List.of(new ProjectFileStamp(PATH, 10, 20, 30, sha256("content")));
    }

    private static byte[] sha256(String value) throws Exception {
        return MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
    }

    private static AnalysisReplayPayload payload() {
        var address = new StatementAddress(List.of(
                new StatementAddress.Segment(DbObjType.SCHEMA, "app"),
                new StatementAddress.Segment(DbObjType.VIEW, "v")));
        var location = new ObjectLocation.Builder()
                .setFilePath("SCHEMA/app/table.sql")
                .setOffset(1).setLineNumber(2).setCharPositionInLine(3)
                .setLength(4).setAction("SELECT").setAlias("t")
                .setLocationType(LocationType.REFERENCE)
                .setReference(new ObjectReference("app", "t", DbObjType.TABLE))
                .build();
        return new AnalysisReplayPayload(
                List.of(new StatementDependencies(address,
                        List.of(new ObjectReference("app", "t", DbObjType.TABLE)))),
                List.of(new FileReferences("SCHEMA/app/table.sql",
                        List.of(location))),
                List.of(address), 7);
    }

    private static AnalysisReplayPayload emptyPayload() {
        return new AnalysisReplayPayload(List.of(), List.of(), List.of(), 7);
    }
}
