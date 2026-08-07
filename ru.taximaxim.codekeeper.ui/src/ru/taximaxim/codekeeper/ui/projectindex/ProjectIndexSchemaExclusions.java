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

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import org.pgcodekeeper.core.utils.FileUtils;

/**
 * Parses the builder-only schema exclusion preference and supplies its stable
 * representation for the project-index configuration digest. The canonical
 * form is a version marker followed by a count and sorted, length-prefixed
 * UTF-8 names; case and Unicode code points are preserved exactly.
 */
public final class ProjectIndexSchemaExclusions {

    public enum InvalidReason {
        DOTTED,
        FILESYSTEM_UNSAFE
    }

    public record InvalidSchemaName(int lineNumber, String value,
            InvalidReason reason) { }

    private static final byte[] CANONICAL_MAGIC =
            "PGCK_SCHEMA_EXCLUSIONS_V1".getBytes(StandardCharsets.US_ASCII); //$NON-NLS-1$

    private ProjectIndexSchemaExclusions() {
    }

    public static Set<String> parse(String value) {
        var invalid = validate(value);
        if (invalid.isPresent()) {
            InvalidSchemaName error = invalid.get();
            throw new IllegalArgumentException(
                    "Invalid project-index schema exclusion on line " //$NON-NLS-1$
                            + error.lineNumber() + ": " + error.value()); //$NON-NLS-1$
        }
        return parseUnchecked(value);
    }

    public static Optional<InvalidSchemaName> validate(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }

        String[] lines = value.split("\\R", -1); //$NON-NLS-1$
        for (int index = 0; index < lines.length; index++) {
            String schemaName = lines[index].trim();
            if (schemaName.isEmpty() || schemaName.startsWith("#")) { //$NON-NLS-1$
                continue;
            }
            if (schemaName.indexOf('.') >= 0) {
                return Optional.of(new InvalidSchemaName(index + 1,
                        schemaName, InvalidReason.DOTTED));
            }
            if (!FileUtils.getValidFilename(schemaName).equals(schemaName)) {
                return Optional.of(new InvalidSchemaName(index + 1,
                        schemaName, InvalidReason.FILESYSTEM_UNSAFE));
            }
        }
        return Optional.empty();
    }

    public static byte[] canonicalForm(String value) {
        var names = parse(value).stream().sorted().toList();
        try {
            var bytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(bytes)) {
                output.write(CANONICAL_MAGIC);
                output.writeInt(names.size());
                for (String name : names) {
                    byte[] encoded = name.getBytes(StandardCharsets.UTF_8);
                    output.writeInt(encoded.length);
                    output.write(encoded);
                }
            }
            return bytes.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Unable to encode project-index schema exclusions", ex); //$NON-NLS-1$
        }
    }

    public static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256") //$NON-NLS-1$
                    .digest(canonicalForm(value)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex); //$NON-NLS-1$
        }
    }

    private static Set<String> parseUnchecked(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }

        Set<String> schemas = new LinkedHashSet<>();
        value.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#")) //$NON-NLS-1$
                .forEach(schemas::add);
        return Set.copyOf(schemas);
    }
}
