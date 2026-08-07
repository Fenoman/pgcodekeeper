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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Stable on-disk location for one physical project.
 */
public final class ProjectIndexState {

    private static final String DIRECTORY = "projects-v2"; //$NON-NLS-1$

    private ProjectIndexState() {
    }

    public static String projectIdentity(Path projectRoot) throws IOException {
        Path canonical = Objects.requireNonNull(projectRoot, "projectRoot")
                .toRealPath();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256") //$NON-NLS-1$
                    .digest(canonical.toString()
                            .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex); //$NON-NLS-1$
        }
    }

    public static Path directory(Path stateRoot, Path projectRoot)
            throws IOException {
        return Objects.requireNonNull(stateRoot, "stateRoot")
                .toAbsolutePath().normalize().resolve(DIRECTORY)
                .resolve(projectIdentity(projectRoot));
    }
}
