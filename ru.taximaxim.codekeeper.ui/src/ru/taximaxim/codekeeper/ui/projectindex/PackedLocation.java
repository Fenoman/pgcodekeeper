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

import org.pgcodekeeper.core.DangerStatement;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;

public record PackedLocation(
        IndexPathOrigin origin,
        String relativePath,
        int offset,
        int lineNumber,
        int charPositionInLine,
        int length,
        ObjectReference reference,
        String action,
        String alias,
        ObjectLocation.LocationType locationType,
        DangerStatement danger) {

    public PackedLocation {
        Objects.requireNonNull(origin, "origin");
        relativePath = IndexPathRef.normalize(relativePath);
        if (offset < 0 || lineNumber < 0 || charPositionInLine < 0 || length < 0) {
            throw new IllegalArgumentException("Location coordinates must not be negative");
        }
        Objects.requireNonNull(locationType, "locationType");
    }

    public static PackedLocation from(ObjectLocation location, IndexPathRef path) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(path, "path");
        return new PackedLocation(path.origin(), path.relativePath(), location.getOffset(),
                location.getLineNumber(), location.getCharPositionInLine(), location.getObjLength(),
                location.getObjectReference(), location.getAction(), location.getAlias(),
                location.getLocationType(), location.getDanger());
    }

    public IndexPathRef path() {
        return new IndexPathRef(origin, relativePath);
    }

    public boolean isGlobal() {
        return locationType == ObjectLocation.LocationType.DEFINITION
                || locationType == ObjectLocation.LocationType.REFERENCE;
    }

    public ObjectLocation toObjectLocation(ProjectIndexPathResolver resolver) {
        Objects.requireNonNull(resolver, "resolver");
        ObjectLocation location = new ObjectLocation.Builder()
                .setFilePath(Objects.requireNonNull(resolver.resolve(path()), "resolved path"))
                .setOffset(offset)
                .setLineNumber(lineNumber)
                .setCharPositionInLine(charPositionInLine)
                .setLength(length)
                .setReference(reference)
                .setAction(action)
                .setAlias(alias)
                .setLocationType(locationType)
                .build();
        if (danger != null) {
            location.setWarning(danger);
        }
        return location;
    }

    String canonicalForm() {
        return origin + "|" + relativePath + "|" + offset + "|" + lineNumber + "|"
                + charPositionInLine + "|" + length + "|" + reference + "|" + action + "|"
                + alias + "|" + locationType + "|" + danger;
    }
}
