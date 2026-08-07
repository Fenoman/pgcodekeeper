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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import org.pgcodekeeper.core.database.api.schema.DbObjType;

import ru.taximaxim.codekeeper.ui.pgdbproject.parser.ProjectComparisonProfile.EffectiveVersion;

/**
 * Digest of everything that must be identical for a persisted analysis result
 * to describe the current project.
 * <p>
 * The digest is written explicitly, field by field, rather than derived from a
 * generated {@code toString}: it is the gate that decides whether a cached
 * model may be served, so it has to break when a semantic field is added and
 * stay stable when nothing changed. Adding a field to
 * {@link ProjectComparisonProfile} without adding it here is a compile-time
 * error, because this encoder destructures the record.
 */
final class ProjectAnalysisIdentity {

    /**
     * Bumped whenever the meaning of the encoding changes, so an old digest can
     * never accidentally equal a new one.
     */
    private static final String ENCODING_VERSION = "analysis-identity-1"; //$NON-NLS-1$

    /** Unit separator: it cannot occur in a path, charset name or identifier. */
    private static final char SEPARATOR = '\u001F';

    private ProjectAnalysisIdentity() {
    }

    /**
     * Returns the hex SHA-256 over the canonical encoding of a profile.
     *
     * @param profile comparison profile of a candidate model
     * @return stable digest of every semantic field of the profile
     */
    static String digest(ProjectComparisonProfile profile) {
        Objects.requireNonNull(profile, "profile"); //$NON-NLS-1$
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256") //$NON-NLS-1$
                    .digest(encode(profile).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex); //$NON-NLS-1$
        }
    }

    private static String encode(ProjectComparisonProfile profile) {
        var text = new StringBuilder(ENCODING_VERSION);
        append(text, profile.databaseType().name());
        append(text, profile.inputCharsetName());
        append(text, profile.keepNewlines());
        append(text, profile.ignorePrivileges());
        append(text, profile.ignoreColumnOrder());
        append(text, profile.enableFunctionBodiesDependencies());
        append(text, profile.disableCheckFunctionBodies());
        append(text, profile.collectObjectReferences());
        append(text, profile.disableAutoLoad());
        appendTypes(text, profile.allowedTypes());
        appendVersion(text, profile.effectiveVersion());
        append(text, profile.simplifyView());
        append(text, profile.simplifyNotNull());
        append(text, profile.timeZone());
        append(text, profile.useActualVersionSyntax());
        appendNames(text, profile.additionalExcludedSchemas());
        append(text, profile.ignoreListCode());
        append(text, profile.projectConfigurationDigest());
        return text.toString();
    }

    private static void appendVersion(StringBuilder text, EffectiveVersion version) {
        append(text, Integer.toString(version.value()));
        append(text, version.text());
        append(text, version.implementationClass());
    }

    private static void appendTypes(StringBuilder text, List<DbObjType> types) {
        append(text, Integer.toString(types.size()));
        types.forEach(type -> append(text, type.name()));
    }

    private static void appendNames(StringBuilder text, List<String> names) {
        append(text, Integer.toString(names.size()));
        names.forEach(name -> append(text, name));
    }

    private static void append(StringBuilder text, boolean value) {
        append(text, Boolean.toString(value));
    }

    /**
     * Appends one field, length-prefixed so that no combination of values can
     * be read as a different combination.
     */
    private static void append(StringBuilder text, String value) {
        text.append(SEPARATOR);
        if (value == null) {
            text.append('-');
            return;
        }
        text.append(value.length()).append(SEPARATOR).append(value);
    }
}
