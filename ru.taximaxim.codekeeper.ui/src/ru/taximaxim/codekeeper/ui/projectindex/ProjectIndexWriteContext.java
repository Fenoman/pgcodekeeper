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

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Immutable operation scope for bounded project-index encoding.
 * {@code tupleBudgetBytes} is the aggregate primitive-buffer budget for all
 * simultaneously prepared auxiliary sections.
 */
record ProjectIndexWriteContext(
        Path stateDirectory,
        String publicationId,
        BooleanSupplier cancelled,
        long tupleBudgetBytes,
        ProjectIndexTelemetry.Run telemetry) {

    ProjectIndexWriteContext {
        stateDirectory = Objects.requireNonNull(
                stateDirectory, "stateDirectory")
                .toAbsolutePath().normalize();
        publicationId = requirePublicationId(publicationId);
        Objects.requireNonNull(cancelled, "cancelled");
        if (tupleBudgetBytes <= 0
                || tupleBudgetBytes
                        > ProjectIndexFormat.MAX_PROSPECTIVE_BUILD_BYTES) {
            throw new IllegalArgumentException(
                    "Project index tuple budget must be between 1 byte and 256 MiB");
        }
    }

    void requireNotCancelled()
            throws ProjectIndexStore.WriteCancelledException {
        if (cancelled.getAsBoolean()) {
            throw new ProjectIndexStore.WriteCancelledException();
        }
    }

    /**
     * {@code packedBytes} is the complete format-2 codec payload, including
     * its header, sections, directory and footer, but not the outer container.
     */
    void recordWriterMetrics(ProjectIndexSpillStore.Metrics metrics,
            long packedBytes) {
        ProjectIndexSpillStore.Metrics checked =
                Objects.requireNonNull(metrics, "metrics");
        if (packedBytes < 0) {
            throw new IllegalArgumentException(
                    "Packed project index bytes must not be negative");
        }
        if (telemetry != null) {
            telemetry.writerMetrics(tupleBudgetBytes,
                    checked.materializedRuns(),
                    checked.physicalSpillBytes(), packedBytes);
        }
    }

    void recordPackedRereadBytes(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException(
                    "Packed project index reread bytes must not be negative");
        }
        if (telemetry != null) {
            telemetry.addPackedRereadBytes(bytes);
        }
    }

    private static String requirePublicationId(String value) {
        Objects.requireNonNull(value, "publicationId");
        if (value.length() != 32) {
            throw new IllegalArgumentException(
                    "Project index publication id must contain 32 lowercase hex characters");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!(character >= '0' && character <= '9'
                    || character >= 'a' && character <= 'f')) {
                throw new IllegalArgumentException(
                        "Project index publication id must contain 32 lowercase hex characters");
            }
        }
        return value;
    }
}
