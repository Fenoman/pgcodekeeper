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

import java.util.Objects;
import java.util.zip.CRC32C;

/**
 * A fixed-memory sequential read window that is independent from the shared
 * random-access block cache.
 */
final class ReplayProjectIndexSource extends ProjectIndexSource {

    private final ProjectIndexSource source;
    private final byte[] page =
            new byte[ProjectIndexBlockCache.BLOCK_BYTES];
    private long pageStart = -1;
    private int pageLength;

    ReplayProjectIndexSource(ProjectIndexSource source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    @Override
    long size() {
        return source.size();
    }

    @Override
    int readUnsignedByte(long position) throws ProjectIndexFormatException {
        requireRange(position, 1);
        ensurePage(position);
        return Byte.toUnsignedInt(page[(int) (position - pageStart)]);
    }

    @Override
    void readFully(long position, byte[] target, int offset, int length)
            throws ProjectIndexFormatException {
        Objects.checkFromIndexSize(offset, length, target.length);
        requireRange(position, length);
        long cursor = position;
        int destination = offset;
        int remaining = length;
        while (remaining > 0) {
            ensurePage(cursor);
            int pageOffset = (int) (cursor - pageStart);
            int chunk = Math.min(remaining, pageLength - pageOffset);
            System.arraycopy(page, pageOffset, target, destination, chunk);
            cursor += chunk;
            destination += chunk;
            remaining -= chunk;
        }
    }

    @Override
    int crc32c(long position, long length)
            throws ProjectIndexFormatException {
        requireRange(position, length);
        CRC32C crc = new CRC32C();
        long cursor = position;
        long remaining = length;
        while (remaining > 0) {
            ensurePage(cursor);
            int pageOffset = (int) (cursor - pageStart);
            int chunk = (int) Math.min(remaining,
                    pageLength - pageOffset);
            crc.update(page, pageOffset, chunk);
            cursor += chunk;
            remaining -= chunk;
        }
        return (int) crc.getValue();
    }

    private void ensurePage(long position)
            throws ProjectIndexFormatException {
        if (pageStart >= 0 && position >= pageStart
                && position < pageStart + pageLength) {
            return;
        }
        long aligned = position / page.length * page.length;
        pageLength = (int) Math.min(page.length, size() - aligned);
        source.readFully(aligned, page, 0, pageLength);
        pageStart = aligned;
    }
}
