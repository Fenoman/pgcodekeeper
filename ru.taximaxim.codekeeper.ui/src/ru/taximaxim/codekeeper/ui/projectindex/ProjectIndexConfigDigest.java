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

import java.io.BufferedInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

public final class ProjectIndexConfigDigest {

    private static final byte[] MAGIC =
            "PGCK_PROJECT_INDEX_INPUTS_V1".getBytes(StandardCharsets.US_ASCII);
    private static final int BUFFER_BYTES = 16 << 10;
    private static final List<String> CONFIG_FILES = List.of(
            ".pgcodekeeper",
            ".pgcodekeeperignore",
            ".pgcodekeeperignoreschema",
            ".pgcodekeeperdependencies",
            ".dependencies",
            "structure.properties");

    private ProjectIndexConfigDigest() {
    }

    public static byte[] calculate(Path projectRoot,
            ProjectIndexConfiguration configuration,
            BooleanSupplier cancelled) throws IOException, InterruptedException {
        Path root = Objects.requireNonNull(projectRoot, "projectRoot")
                .toAbsolutePath().normalize();
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(cancelled, "cancelled");
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
        try (var output = new DataOutputStream(
                new DigestOutputStream(java.io.OutputStream.nullOutputStream(),
                        digest))) {
            output.write(MAGIC);
            writeBytes(output, configuration.canonicalForm());
            byte[] buffer = new byte[BUFFER_BYTES];
            for (String fileName : CONFIG_FILES) {
                checkCancelled(cancelled);
                writeBytes(output, fileName.getBytes(StandardCharsets.UTF_8));
                Path path = root.resolve(fileName);
                boolean exists = Files.exists(path);
                output.writeBoolean(exists);
                if (!exists) {
                    continue;
                }
                if (!Files.isRegularFile(path)) {
                    throw new IOException(
                            "Project index configuration input is not a regular file");
                }
                output.writeLong(Files.size(path));
                try (InputStream input = new BufferedInputStream(
                        Files.newInputStream(path), BUFFER_BYTES)) {
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        checkCancelled(cancelled);
                        if (read != 0) {
                            output.write(buffer, 0, read);
                        }
                    }
                }
            }
            output.flush();
        }
        checkCancelled(cancelled);
        return digest.digest();
    }

    private static void writeBytes(DataOutputStream output, byte[] value)
            throws IOException {
        output.writeInt(value.length);
        output.write(value);
    }

    private static void checkCancelled(BooleanSupplier cancelled)
            throws InterruptedException {
        if (cancelled.getAsBoolean()) {
            throw new InterruptedException(
                    "Project index configuration hashing cancelled");
        }
    }
}
