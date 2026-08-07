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

import java.util.ArrayDeque;
import java.util.Objects;

public record IndexPathRef(IndexPathOrigin origin, String relativePath) {

    public IndexPathRef {
        Objects.requireNonNull(origin, "origin");
        relativePath = normalize(relativePath);
    }

    static String normalize(String value) {
        Objects.requireNonNull(value, "relativePath");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Index path must not be blank");
        }
        if (isCanonicalRelativePath(value)) {
            return value;
        }

        String path = value.replace('\\', '/');
        if (path.startsWith("/") || path.startsWith("//")
                || hasUriOrDrivePrefix(path)) {
            throw new IllegalArgumentException("Index path must be relative: " + value);
        }

        var parts = new ArrayDeque<String>();
        for (String part : path.split("/")) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                if (parts.isEmpty()) {
                    throw new IllegalArgumentException("Index path escapes its root: " + value);
                }
                parts.removeLast();
            } else {
                parts.addLast(part);
            }
        }
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("Index path must name a file: " + value);
        }
        return String.join("/", parts);
    }

    private static boolean isCanonicalRelativePath(String path) {
        if (path.charAt(0) == '/' || hasUriOrDrivePrefix(path)) {
            return false;
        }

        int segmentStart = 0;
        for (int i = 0; i < path.length(); i++) {
            char character = path.charAt(i);
            if (character == '\\') {
                return false;
            }
            if (character == '/') {
                if (isNonCanonicalSegment(path, segmentStart, i)) {
                    return false;
                }
                segmentStart = i + 1;
            }
        }
        return !isNonCanonicalSegment(path, segmentStart, path.length());
    }

    private static boolean isNonCanonicalSegment(String path, int start, int end) {
        int length = end - start;
        return length == 0
                || length == 1 && path.charAt(start) == '.'
                || length == 2 && path.charAt(start) == '.' && path.charAt(start + 1) == '.';
    }

    private static boolean hasUriOrDrivePrefix(String path) {
        int colon = path.indexOf(':');
        int slash = path.indexOf('/');
        if (colon <= 0 || slash >= 0 && colon > slash || !Character.isLetter(path.charAt(0))) {
            return false;
        }
        for (int i = 1; i < colon; i++) {
            char character = path.charAt(i);
            if (!Character.isLetterOrDigit(character)
                    && character != '+' && character != '-' && character != '.') {
                return false;
            }
        }
        return true;
    }
}
