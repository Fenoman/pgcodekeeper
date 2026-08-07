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

import ru.taximaxim.codekeeper.ui.DatabaseType;

public record ProjectIndexIdentity(
        int parserAbi,
        String coreVersion,
        String uiVersion,
        DatabaseType databaseType,
        String projectIdentity,
        byte[] configSha256) {

    public ProjectIndexIdentity {
        if (parserAbi < 0) {
            throw new IllegalArgumentException("Parser ABI must not be negative");
        }
        Objects.requireNonNull(coreVersion, "coreVersion");
        Objects.requireNonNull(uiVersion, "uiVersion");
        Objects.requireNonNull(databaseType, "databaseType");
        Objects.requireNonNull(projectIdentity, "projectIdentity");
        configSha256 = ProjectFileStamp.requireSha256(configSha256, "configSha256");
    }

    public static ProjectIndexIdentity from(ProjectIndexManifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        return new ProjectIndexIdentity(manifest.parserAbi(), manifest.coreVersion(),
                manifest.uiVersion(), manifest.databaseType(), manifest.projectIdentity(),
                manifest.configSha256());
    }

    public boolean matches(ProjectIndexManifest manifest) {
        return parserAbi == manifest.parserAbi()
                && coreVersion.equals(manifest.coreVersion())
                && uiVersion.equals(manifest.uiVersion())
                && databaseType == manifest.databaseType()
                && projectIdentity.equals(manifest.projectIdentity())
                && Arrays.equals(configSha256, manifest.configSha256());
    }

    @Override
    public byte[] configSha256() {
        return configSha256.clone();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof ProjectIndexIdentity other
                && parserAbi == other.parserAbi
                && coreVersion.equals(other.coreVersion)
                && uiVersion.equals(other.uiVersion)
                && databaseType == other.databaseType
                && projectIdentity.equals(other.projectIdentity)
                && Arrays.equals(configSha256, other.configSha256);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(parserAbi, coreVersion, uiVersion, databaseType, projectIdentity)
                + Arrays.hashCode(configSha256);
    }
}
