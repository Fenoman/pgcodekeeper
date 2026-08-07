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

import java.util.Objects;
import java.util.Optional;

import org.pgcodekeeper.core.database.api.schema.DbObjType;

import ru.taximaxim.codekeeper.ui.projectindex.ReferenceMatchKey.MatchFamily;

/**
 * A canonical prefix of the definition keys stored in the project index.
 *
 * @param family object family
 * @param exactType exact object type for {@link MatchFamily#EXACT}
 * @param schema schema component of the match key, or {@code null}
 * @param objectName object component of the match key, or {@code null}
 */
public record ProjectIndexDefinitionSubject(
        MatchFamily family,
        DbObjType exactType,
        String schema,
        String objectName) {

    public ProjectIndexDefinitionSubject {
        Objects.requireNonNull(family, "family");
        if ((family == MatchFamily.EXACT) != (exactType != null)) {
            throw new IllegalArgumentException(
                    "Exact definition subjects require an exact object type");
        }
        if (objectName != null && schema == null) {
            throw new IllegalArgumentException(
                    "An object definition subject requires a schema component");
        }
    }

    /**
     * The subject a definition claims, or empty when it cannot be named.
     *
     * <p>Derived through the definition's own match key but deliberately
     * dropping that key's alias and global flag: a subject has to identify the
     * object regardless of how a particular reference spelled it. Callers use
     * an empty result as a refusal, so a definition whose reference is
     * malformed can never pass for a fresh one.
     */
    static Optional<ProjectIndexDefinitionSubject> of(PackedDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        try {
            ReferenceMatchKey key = ReferenceMatchKey.from(definition.object());
            return Optional.of(new ProjectIndexDefinitionSubject(key.family(),
                    key.exactType(), key.schema(), key.table()));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    boolean matches(ReferenceMatchKey key) {
        return family == key.family()
                && exactType == key.exactType()
                && (schema == null || schema.equals(key.schema()))
                && (objectName == null || objectName.equals(key.table()));
    }
}
