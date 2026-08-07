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

import java.util.Arrays;
import java.util.Objects;

public record ProjectFileStamp(
        IndexPathRef path,
        long eclipseModificationStamp,
        long size,
        long lastModifiedMillis,
        byte[] contentSha256) {

    public ProjectFileStamp {
        Objects.requireNonNull(path, "path");
        if (size < 0) {
            throw new IllegalArgumentException("File size must not be negative");
        }
        contentSha256 = requireSha256(contentSha256, "contentSha256");
    }

    @Override
    public byte[] contentSha256() {
        return contentSha256.clone();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof ProjectFileStamp other
                && path.equals(other.path)
                && eclipseModificationStamp == other.eclipseModificationStamp
                && size == other.size
                && lastModifiedMillis == other.lastModifiedMillis
                && Arrays.equals(contentSha256, other.contentSha256);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(path, eclipseModificationStamp, size, lastModifiedMillis)
                + Arrays.hashCode(contentSha256);
    }

    static byte[] requireSha256(byte[] value, String name) {
        Objects.requireNonNull(value, name);
        if (value.length != 32) {
            throw new IllegalArgumentException(name + " must contain a SHA-256 digest");
        }
        return value.clone();
    }
}
