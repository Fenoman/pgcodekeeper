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

import java.util.Comparator;
import java.util.Objects;

import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;

public record ReferenceMatchKey(
        MatchFamily family,
        DbObjType exactType,
        String schema,
        String table,
        String column,
        String alias,
        boolean global) {

    public enum MatchFamily {
        RELATION,
        ROUTINE,
        TYPE,
        EXACT
    }

    public static final Comparator<ReferenceMatchKey> CANONICAL_ORDER = Comparator
            .comparing((ReferenceMatchKey key) -> key.family())
            .thenComparing(ReferenceMatchKey::exactType,
                    Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(ReferenceMatchKey::schema,
                    Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(ReferenceMatchKey::table,
                    Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(ReferenceMatchKey::column,
                    Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(ReferenceMatchKey::alias,
                    Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(ReferenceMatchKey::global);

    public ReferenceMatchKey {
        Objects.requireNonNull(family, "family");
        if (family == MatchFamily.EXACT && exactType == null) {
            throw new IllegalArgumentException("EXACT match key requires an object type");
        }
        if (family != MatchFamily.EXACT && exactType != null) {
            throw new IllegalArgumentException("Family match key must not retain an exact type");
        }
    }

    public static ReferenceMatchKey from(PackedLocation location) {
        Objects.requireNonNull(location, "location");
        ObjectReference reference = location.reference();
        if (reference == null || reference.type() == null) {
            throw new IllegalArgumentException("A match key requires an object reference and type");
        }
        MatchFamily family = family(reference.type());
        return new ReferenceMatchKey(family,
                family == MatchFamily.EXACT ? reference.type() : null,
                reference.schema(), reference.table(), reference.column(),
                location.alias(), location.isGlobal());
    }

    public static ReferenceMatchKey from(ObjectLocation location) {
        Objects.requireNonNull(location, "location");
        ObjectReference reference = location.getObjectReference();
        if (reference == null || reference.type() == null) {
            throw new IllegalArgumentException("A match key requires an object reference and type");
        }
        MatchFamily family = family(reference.type());
        return new ReferenceMatchKey(family,
                family == MatchFamily.EXACT ? reference.type() : null,
                reference.schema(), reference.table(), reference.column(),
                location.getAlias(), location.isGlobal());
    }

    public static MatchFamily family(DbObjType type) {
        Objects.requireNonNull(type, "type");
        if (type.in(DbObjType.TABLE, DbObjType.VIEW, DbObjType.SEQUENCE)) {
            return MatchFamily.RELATION;
        }
        if (type.in(DbObjType.FUNCTION, DbObjType.AGGREGATE, DbObjType.PROCEDURE)) {
            return MatchFamily.ROUTINE;
        }
        if (type.in(DbObjType.TYPE, DbObjType.DOMAIN)) {
            return MatchFamily.TYPE;
        }
        return MatchFamily.EXACT;
    }
}
