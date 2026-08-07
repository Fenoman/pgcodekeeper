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
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.BooleanSupplier;

public final class ProjectIndexStoreTestSupport {

    private ProjectIndexStoreTestSupport() {
    }

    /**
     * Builds a store whose next open fails with the supplied exception. A
     * transient failure must leave the persistent index in place, while a
     * {@link ProjectIndexFormatException} may clean it.
     */
    public static ProjectIndexStore failOpen(Path directory,
            IoFailure failure) {
        Objects.requireNonNull(failure, "failure");
        return new ProjectIndexStore(directory,
                ProjectIndexStore.DEFAULT_CACHE_BYTES,
                point -> {
                    if (point == ProjectIndexStore.IoPoint.BEFORE_OPEN) {
                        throw failure.next();
                    }
                },
                ProjectIndexStore.DEFAULT_COMPACTION_BYTES);
    }

    @FunctionalInterface
    public interface IoFailure {
        IOException next();
    }

    public static ProjectIndexStore failCurrentDirectorySync(
            Path directory, BooleanSupplier armed,
            Runnable beforeFailure) {
        Objects.requireNonNull(armed, "armed");
        Objects.requireNonNull(beforeFailure, "beforeFailure");
        return new ProjectIndexStore(directory,
                ProjectIndexStore.DEFAULT_CACHE_BYTES,
                ProjectIndexStore.IoHook.NONE,
                ProjectIndexStore.DEFAULT_COMPACTION_BYTES,
                (path, stage) -> {
                    if (stage == ProjectIndexDirectorySync.Stage.CURRENT
                            && armed.getAsBoolean()) {
                        beforeFailure.run();
                        throw new IOException(
                                "simulated CURRENT directory sync failure");
                    }
                });
    }
}
