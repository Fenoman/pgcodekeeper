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
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Objects;

/**
 * Makes atomic publication renames durable in their containing directory.
 *
 * <p>Java NIO cannot open a Windows directory as a {@link FileChannel}, so the
 * Windows implementation deliberately degrades to the file-force and atomic
 * rename guarantees already provided by the store. POSIX implementations open
 * and force the directory and propagate every I/O failure. No exception-based
 * fallback is used, so a permissions or storage error cannot be mistaken for
 * an unsupported directory handle.</p>
 *
 * <p><b>Windows durability contract.</b> Directory entries are not forced on
 * Windows and no native call is used to emulate it. Payload bytes and the
 * CURRENT record are still forced individually and every publication is an
 * atomic rename, so an operating-system crash or power loss can only lose the
 * directory entry of a freshly published generation, never expose a partially
 * written one. A workspace that lost that entry opens as a miss or as a
 * verified corruption and rebuilds the index from scratch. A full rebuild
 * after an unclean Windows shutdown is therefore expected behavior and not an
 * index defect. POSIX platforms force the directory and do not have this
 * window.</p>
 */
@FunctionalInterface
interface ProjectIndexDirectorySync {

    enum Stage {
        PAYLOADS,
        CURRENT
    }

    void sync(Path directory, Stage stage) throws IOException;

    static ProjectIndexDirectorySync platform() {
        return forOs(System.getProperty("os.name", ""));
    }

    static ProjectIndexDirectorySync forOs(String osName) {
        Objects.requireNonNull(osName, "osName");
        if (osName.toLowerCase(Locale.ROOT).startsWith("windows")) {
            return (directory, stage) -> {
                Objects.requireNonNull(directory, "directory");
                Objects.requireNonNull(stage, "stage");
                // Windows directory handles are not exposed by FileChannel.
            };
        }
        return (directory, stage) -> {
            Objects.requireNonNull(stage, "stage");
            try (FileChannel channel = FileChannel.open(
                    Objects.requireNonNull(directory, "directory"),
                    StandardOpenOption.READ)) {
                channel.force(true);
            } catch (UnsupportedOperationException ex) {
                throw new IOException(
                        "Directory synchronization is not supported", ex);
            }
        };
    }
}
