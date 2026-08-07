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

import static ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexFormat.format;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32C;

final class ProjectIndexBlockCache {

    static final int BLOCK_BYTES = 64 << 10;

    private final long maximumBytes;
    private final Map<BlockKey, byte[]> blocks = new LinkedHashMap<>(32, 0.75f, true);
    private long residentBytes;
    private long bytesRead;
    private long blocksRead;

    ProjectIndexBlockCache(long maximumBytes) {
        if (maximumBytes < BLOCK_BYTES || maximumBytes > ProjectIndexStore.DEFAULT_CACHE_BYTES) {
            throw new IllegalArgumentException("Project index cache must be between 64 KiB and 32 MiB");
        }
        this.maximumBytes = maximumBytes;
    }

    synchronized byte[] read(FileChannel channel, long channelId, long blockStart,
            int length, int expectedCrc)
            throws ProjectIndexFormatException {
        BlockKey key = new BlockKey(channelId, blockStart);
        byte[] cached = blocks.get(key);
        if (cached != null) {
            return cached;
        }
        if (blockStart < 0 || length <= 0 || length > BLOCK_BYTES) {
            throw format("Invalid project index cache block range");
        }
        byte[] loaded = new byte[length];
        ByteBuffer target = ByteBuffer.wrap(loaded);
        try {
            long position = blockStart;
            while (target.hasRemaining()) {
                int read = channel.read(target, position);
                if (read <= 0) {
                    throw format("Unable to make progress while reading project index");
                }
                position += read;
            }
        } catch (ProjectIndexFormatException ex) {
            throw ex;
        } catch (IOException ex) {
            throw format("Unable to read project index", ex);
        }
        CRC32C crc = new CRC32C();
        crc.update(loaded, 0, loaded.length);
        if ((int) crc.getValue() != expectedCrc) {
            throw format("Project index data block CRC mismatch");
        }
        while (!blocks.isEmpty() && residentBytes > maximumBytes - loaded.length) {
            var iterator = blocks.entrySet().iterator();
            residentBytes -= iterator.next().getValue().length;
            iterator.remove();
        }
        blocks.put(key, loaded);
        residentBytes += loaded.length;
        bytesRead += loaded.length;
        blocksRead++;
        return loaded;
    }

    synchronized long residentBytes() {
        return residentBytes;
    }

    synchronized long bytesRead() {
        return bytesRead;
    }

    synchronized long blocksRead() {
        return blocksRead;
    }

    synchronized void clearChannel(long channelId) {
        var iterator = blocks.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getKey().channelId() == channelId) {
                residentBytes -= entry.getValue().length;
                iterator.remove();
            }
        }
    }

    private record BlockKey(long channelId, long offset) {
    }
}
