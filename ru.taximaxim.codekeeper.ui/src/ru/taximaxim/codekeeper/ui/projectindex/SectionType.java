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

enum SectionType {
    MANIFEST(1),
    STRINGS(2),
    FILES(3),
    DEFINITIONS(4),
    LOCATIONS(5),
    BY_PATH(6),
    BY_MATCH_KEY(7),
    COMPLETION_TRIGRAMS(8),
    REVERSE_DEPENDENCIES(9),
    UNRESOLVED_FILES(10);

    private final int id;

    SectionType(int id) {
        this.id = id;
    }

    int id() {
        return id;
    }

    static SectionType fromId(int id) {
        for (SectionType type : values()) {
            if (type.id == id) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown project index section type: " + id);
    }
}
