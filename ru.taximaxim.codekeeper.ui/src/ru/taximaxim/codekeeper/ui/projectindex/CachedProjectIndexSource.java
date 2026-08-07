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

import java.nio.channels.FileChannel;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32C;

final class CachedProjectIndexSource extends ProjectIndexSource {

    private static final AtomicLong NEXT_CHANNEL_ID = new AtomicLong();

    private final FileChannel channel;
    private final ProjectIndexBlockCache cache;
    private final long channelId;
    private final long regionOffset;
    private final long regionLength;
    private final ProjectIndexLocator locator;

    CachedProjectIndexSource(FileChannel channel, ProjectIndexBlockCache cache,
            long regionOffset, long regionLength, ProjectIndexLocator locator)
            throws ProjectIndexFormatException {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.cache = Objects.requireNonNull(cache, "cache");
        channelId = NEXT_CHANNEL_ID.incrementAndGet();
        long fileSize;
        try {
            fileSize = channel.size();
        } catch (Exception ex) {
            throw format("Unable to determine project index size", ex);
        }
        if (regionOffset < 0 || regionLength < 0 || regionOffset > fileSize - regionLength) {
            throw format("Project index region is outside the file");
        }
        this.regionOffset = regionOffset;
        this.regionLength = regionLength;
        this.locator = Objects.requireNonNull(locator, "locator");
    }

    @Override
    long size() {
        return regionLength;
    }

    @Override
    int readUnsignedByte(long position) throws ProjectIndexFormatException {
        requireRange(position, 1);
        int blockIndex = (int) (position / ProjectIndexBlockCache.BLOCK_BYTES);
        long relativeStart = (long) blockIndex * ProjectIndexBlockCache.BLOCK_BYTES;
        byte[] block = readBlock(blockIndex, relativeStart);
        return Byte.toUnsignedInt(block[(int) (position - relativeStart)]);
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
            int blockIndex = (int) (cursor / ProjectIndexBlockCache.BLOCK_BYTES);
            long relativeStart = (long) blockIndex * ProjectIndexBlockCache.BLOCK_BYTES;
            byte[] block = readBlock(blockIndex, relativeStart);
            int blockOffset = (int) (cursor - relativeStart);
            int chunk = Math.min(remaining, block.length - blockOffset);
            System.arraycopy(block, blockOffset, target, destination, chunk);
            cursor += chunk;
            destination += chunk;
            remaining -= chunk;
        }
    }

    @Override
    int crc32c(long position, long length) throws ProjectIndexFormatException {
        requireRange(position, length);
        var crc = new CRC32C();
        long cursor = position;
        long remaining = length;
        while (remaining > 0) {
            int blockIndex = (int) (cursor / ProjectIndexBlockCache.BLOCK_BYTES);
            long relativeStart = (long) blockIndex * ProjectIndexBlockCache.BLOCK_BYTES;
            byte[] block = readBlock(blockIndex, relativeStart);
            int blockOffset = (int) (cursor - relativeStart);
            int chunk = (int) Math.min(remaining, block.length - blockOffset);
            crc.update(block, blockOffset, chunk);
            cursor += chunk;
            remaining -= chunk;
        }
        return (int) crc.getValue();
    }

    /**
     * Hands out the cache block holding {@code position}.
     *
     * <p>A block is filled once, checked against the CRC its locator records,
     * and then never written again - eviction only drops the map entry, and
     * the file behind an open index is never edited, because publishing
     * writes a new one. So the bytes a reader keeps reading stay the bytes
     * that block always had, whether or not the cache still lists it. What a
     * held block costs is at most one block per reader outliving its
     * eviction, against an entry into the shared monitor for every byte.
     */
    @Override
    boolean locate(ByteRun run, long position) throws ProjectIndexFormatException {
        requireRange(position, 1);
        int blockIndex = (int) (position / ProjectIndexBlockCache.BLOCK_BYTES);
        long relativeStart = (long) blockIndex * ProjectIndexBlockCache.BLOCK_BYTES;
        byte[] block = readBlock(blockIndex, relativeStart);
        run.hold(block, relativeStart, block.length);
        return true;
    }

    void closeCacheEntries() {
        cache.clearChannel(channelId);
    }

    private byte[] readBlock(int blockIndex, long relativeStart)
            throws ProjectIndexFormatException {
        if (blockIndex < 0 || blockIndex >= locator.codecBlockCount()) {
            throw format("Project index data block is outside the locator");
        }
        int length = (int) Math.min(ProjectIndexBlockCache.BLOCK_BYTES,
                regionLength - relativeStart);
        return cache.read(channel, channelId, regionOffset + relativeStart,
                length, locator.codecBlockCrc(blockIndex));
    }
}
