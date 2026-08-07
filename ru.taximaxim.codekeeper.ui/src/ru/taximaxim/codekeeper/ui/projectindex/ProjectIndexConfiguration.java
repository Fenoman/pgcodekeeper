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
import java.util.Objects;

import ru.taximaxim.codekeeper.ui.DatabaseType;

/**
 * Complete identity of settings that affect the background project index.
 * <p>
 * {@code incrementalAddedFiles} belongs here even though it changes no
 * contribution: it changes the rule by which contributions are invalidated, and
 * indexes built under different invalidation rules must not be interchangeable.
 * Its cost is one full build the first time it is switched.
 * <p>
 * {@code receiveOnly} belongs here for a stronger reason: while it is on no
 * build maintains the index at all, so the contributions stop following the
 * files the moment a database pull overwrites them. An index carried across
 * that switch is not stale in the ordinary sense - it is an index of a project
 * that no longer exists, answering outline structure, hover hints and error
 * markers from definitions nobody can see anymore. Naming the mode in the
 * identity is what retires it, in both directions: turning the mode on drops
 * the index that is about to stop being maintained, and turning it off orders
 * the full build that makes one true again.
 */
public record ProjectIndexConfiguration(DatabaseType databaseType,
        boolean projectPreferencesEnabled, boolean ignorePrivileges,
        boolean bodyDependencies, boolean incrementalAddedFiles,
        boolean receiveOnly, String excludedSchemas) {

    public record GlobalSettings(boolean ignorePrivileges,
            boolean bodyDependencies, boolean incrementalAddedFiles,
            boolean receiveOnly, String excludedSchemas) {

        public GlobalSettings {
            excludedSchemas = Objects.requireNonNullElse(excludedSchemas, ""); //$NON-NLS-1$
        }
    }

    public record ProjectOverrides(Boolean ignorePrivileges,
            Boolean bodyDependencies, Boolean incrementalAddedFiles,
            Boolean receiveOnly, String excludedSchemas) { }

    // V4 carries receiveOnly, V3 carried incrementalAddedFiles. The magic is
    // the version of the encoding, so bumping it makes a V3 byte stream unable
    // to hash to a V4 one no matter how the fields line up.
    private static final byte[] CANONICAL_MAGIC =
            "PGCK_PROJECT_INDEX_CONFIG_V4" //$NON-NLS-1$
                    .getBytes(StandardCharsets.US_ASCII);

    public ProjectIndexConfiguration {
        Objects.requireNonNull(databaseType, "databaseType"); //$NON-NLS-1$
        excludedSchemas = Objects.requireNonNullElse(excludedSchemas, ""); //$NON-NLS-1$
    }

    public static ProjectIndexConfiguration resolve(DatabaseType databaseType,
            boolean projectPreferencesEnabled, GlobalSettings global,
            ProjectOverrides project) {
        Objects.requireNonNull(global, "global"); //$NON-NLS-1$
        Objects.requireNonNull(project, "project"); //$NON-NLS-1$

        boolean ignorePrivileges = projectPreferencesEnabled
                && project.ignorePrivileges() != null
                        ? project.ignorePrivileges()
                        : global.ignorePrivileges();
        boolean bodyDependencies = projectPreferencesEnabled
                && project.bodyDependencies() != null
                        ? project.bodyDependencies()
                        : global.bodyDependencies();
        boolean incrementalAddedFiles = projectPreferencesEnabled
                && project.incrementalAddedFiles() != null
                        ? project.incrementalAddedFiles()
                        : global.incrementalAddedFiles();
        // The same three-way resolution ProjectReceiveOnlyMode.isEnabled gets
        // from OverridablePrefs, and it has to stay the same one: an identity
        // that disagreed with the gate would retire an index the gate is still
        // maintaining, or keep one it stopped maintaining.
        boolean receiveOnly = projectPreferencesEnabled
                && project.receiveOnly() != null
                        ? project.receiveOnly()
                        : global.receiveOnly();
        String excludedSchemas = projectPreferencesEnabled
                && project.excludedSchemas() != null
                        ? project.excludedSchemas()
                        : global.excludedSchemas();
        return new ProjectIndexConfiguration(databaseType,
                projectPreferencesEnabled, ignorePrivileges,
                bodyDependencies, incrementalAddedFiles, receiveOnly,
                excludedSchemas);
    }

    /**
     * The bytes an index identity is stamped from.
     *
     * <p>The exclusions are written for every database type, and every type now
     * acts on them. They were written this way before that was true, when only
     * a PostgreSQL project had an index at all: the field was kept because
     * every identity on every machine had been stamped with it, and dropping it
     * would have changed the digest of a configuration still in use - a silent
     * full rebuild for every developer, to describe a difference nobody could
     * see. Keeping it cost nothing and is what makes the identity of a MS SQL
     * or ClickHouse index right today without a format change.</p>
     *
     * @return the canonical encoding, stable across runs and machines
     */
    public byte[] canonicalForm() {
        byte[] databaseTypeBytes = databaseType.name()
                .getBytes(StandardCharsets.US_ASCII);
        byte[] exclusions = ProjectIndexSchemaExclusions
                .canonicalForm(excludedSchemas);
        try {
            var bytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(bytes)) {
                output.write(CANONICAL_MAGIC);
                writeBytes(output, databaseTypeBytes);
                output.writeBoolean(projectPreferencesEnabled);
                output.writeBoolean(ignorePrivileges);
                output.writeBoolean(bodyDependencies);
                output.writeBoolean(incrementalAddedFiles);
                output.writeBoolean(receiveOnly);
                writeBytes(output, exclusions);
            }
            return bytes.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Unable to encode project-index configuration", ex); //$NON-NLS-1$
        }
    }

    public String digest() {
        try {
            return HexFormat.of().formatHex(MessageDigest
                    .getInstance("SHA-256").digest(canonicalForm())); //$NON-NLS-1$
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex); //$NON-NLS-1$
        }
    }

    private static void writeBytes(DataOutputStream output, byte[] value)
            throws IOException {
        output.writeInt(value.length);
        output.write(value);
    }
}
