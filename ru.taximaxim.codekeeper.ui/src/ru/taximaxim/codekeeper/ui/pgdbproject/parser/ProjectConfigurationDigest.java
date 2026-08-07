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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import org.pgcodekeeper.core.database.base.loader.AbstractProjectLoader;

/**
 * Exact digest of the project configuration files that every comparison reads
 * through {@code AbstractProjectLoader.contributeCommonConfiguration}.
 * <p>
 * The ignore-schema list drops whole schemas before parsing, so it changes the
 * analyzed project model. Neither the loader's consumed-input fingerprints nor
 * {@code listInputFiles()} cover these files, and a reused model never reads
 * them again, so this digest is the only thing that can retire a model built
 * under different ignore rules.
 */
final class ProjectConfigurationDigest {

    /**
     * Files in a fixed order. Additional dependencies are deliberately absent:
     * they feed the dependency graph and the diff tree, both of which are
     * rebuilt from the current settings on every run, and never the project
     * model itself.
     */
    private static final List<String> FILES = List.of(
            AbstractProjectLoader.IGNORE_FILE,
            AbstractProjectLoader.IGNORE_SCHEMA_FILE);

    private static final String ABSENT = "absent"; //$NON-NLS-1$

    private ProjectConfigurationDigest() {
    }

    /**
     * @param projectRoot absolute project directory
     * @return a stable digest of the shared configuration files
     * @throws IOException if a present configuration file cannot be read
     */
    static String of(Path projectRoot) throws IOException {
        Objects.requireNonNull(projectRoot, "projectRoot"); //$NON-NLS-1$
        MessageDigest digest = sha256();
        for (String name : FILES) {
            digest.update(name.getBytes(StandardCharsets.UTF_8));
            Path file = projectRoot.resolve(name);
            if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                digest.update(Files.readAllBytes(file));
            } else {
                digest.update(ABSENT.getBytes(StandardCharsets.UTF_8));
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256"); //$NON-NLS-1$
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
