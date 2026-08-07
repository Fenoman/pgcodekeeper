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
import java.util.List;
import java.util.Objects;

import ru.taximaxim.codekeeper.ui.DatabaseType;

public record ProjectIndexManifest(
        int formatMajor,
        int formatMinor,
        int parserAbi,
        String coreVersion,
        String uiVersion,
        DatabaseType databaseType,
        String projectIdentity,
        byte[] configSha256,
        long generation,
        List<ProjectFileStamp> files) {

    public ProjectIndexManifest {
        if (formatMajor < 0 || formatMinor < 0 || parserAbi < 0 || generation < 0) {
            throw new IllegalArgumentException("Manifest numeric fields must not be negative");
        }
        Objects.requireNonNull(coreVersion, "coreVersion");
        Objects.requireNonNull(uiVersion, "uiVersion");
        Objects.requireNonNull(databaseType, "databaseType");
        Objects.requireNonNull(projectIdentity, "projectIdentity");
        if (!isLowercaseSha256(projectIdentity)) {
            throw new IllegalArgumentException(
                    "Project identity must be a lowercase SHA-256 hex value");
        }
        configSha256 = ProjectFileStamp.requireSha256(configSha256, "configSha256");
        files = List.copyOf(files);
    }

    @Override
    public byte[] configSha256() {
        return configSha256.clone();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof ProjectIndexManifest other
                && formatMajor == other.formatMajor && formatMinor == other.formatMinor
                && parserAbi == other.parserAbi && generation == other.generation
                && coreVersion.equals(other.coreVersion) && uiVersion.equals(other.uiVersion)
                && databaseType == other.databaseType && projectIdentity.equals(other.projectIdentity)
                && Arrays.equals(configSha256, other.configSha256) && files.equals(other.files);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(formatMajor, formatMinor, parserAbi, coreVersion, uiVersion,
                databaseType, projectIdentity, generation, files) + Arrays.hashCode(configSha256);
    }

    private static boolean isLowercaseSha256(String value) {
        if (value.length() != 64) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (!(current >= '0' && current <= '9' || current >= 'a' && current <= 'f')) {
                return false;
            }
        }
        return true;
    }
}
