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

import static ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexFormat.MAX_RAW_BLOCK_BYTES;
import static ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexFormat.format;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.util.Objects;
import java.util.function.IntFunction;
import java.util.zip.CRC32C;

import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexFormat.AllocationBudget;

/**
 * Bounded random-access input used by the packed index decoder.
 */
abstract class ProjectIndexSource {

    static final long CHANNEL_SOURCE_RESIDENT_BYTES = 128
            + ProjectIndexFormat.arrayBytes(MAX_RAW_BLOCK_BYTES, Byte.BYTES);

    abstract long size();

    abstract int readUnsignedByte(long position) throws ProjectIndexFormatException;

    abstract void readFully(long position, byte[] target, int offset, int length)
            throws ProjectIndexFormatException;

    abstract int crc32c(long position, long length) throws ProjectIndexFormatException;

    /**
     * Points {@code run} at a stretch of bytes holding {@code position} that
     * the caller may keep reading from directly, and answers whether it could.
     *
     * <p>Reading a byte at a time is how the decoder asks its questions -
     * every varint of every record - and for the cached source each of those
     * asks costs an entry into the shared block cache: a monitor, a key, and
     * an access-ordered map lookup that reorders the list. Handing out the
     * block itself lets a reader answer the next several thousand of those
     * asks off an array, and enter the cache only when it leaves the block.
     *
     * <p>A source may only hand out a run over bytes that <b>do not change
     * under the reader</b>. That excludes every source that decodes into a
     * buffer it reuses, because a second reader - or the same reader's own
     * {@code crc32c} - would refill that buffer while the run still claims to
     * describe it, and the run would then answer with the wrong bytes at
     * positions it believes it holds. Such sources answer false and keep the
     * per-byte path, which reads through the buffer's own bookkeeping and is
     * therefore always correct.
     *
     * <p>A source answers the same way at every position - it either hands
     * out runs or it does not - so a reader may stop asking after one
     * refusal. A source that answered false only sometimes would cost its
     * readers the saving, never an answer: they fall back to reading a byte
     * at a time, and a run left over from an earlier position still describes
     * bytes this source really has.
     */
    boolean locate(ByteRun run, long position) throws ProjectIndexFormatException {
        return false;
    }

    /**
     * A reader's hold on a stretch of bytes it may read without asking the
     * source again.
     *
     * <p>Mutable and unsynchronized on purpose: it belongs to one
     * {@link ProjectIndexFormat.BinaryReader} and the slices cut from it,
     * which is exactly the scope the reader's own position already has. The
     * array it points at is shared and read-only; the bookkeeping around it
     * is not shared at all.
     */
    static final class ByteRun {
        private byte[] bytes;
        private long start;
        private int length;

        void hold(byte[] bytes, long start, int length) {
            this.bytes = bytes;
            this.start = start;
            this.length = length;
        }

        boolean holds(long position) {
            return bytes != null && position >= start && position - start < length;
        }

        boolean holds(long position, int span) {
            return bytes != null && position >= start
                    && position - start <= length - span;
        }

        int byteAt(long position) {
            return Byte.toUnsignedInt(bytes[(int) (position - start)]);
        }

        void copyTo(long position, byte[] target, int offset, int span) {
            System.arraycopy(bytes, (int) (position - start), target, offset, span);
        }
    }

    static ProjectIndexSource of(byte[] bytes) {
        return new ByteArraySource(bytes);
    }

    static ProjectIndexSource of(SeekableByteChannel channel, AllocationBudget budget,
            IntFunction<byte[]> pageAllocator) throws ProjectIndexFormatException {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(pageAllocator, "pageAllocator");
        budget.claim(CHANNEL_SOURCE_RESIDENT_BYTES, "channel source page");
        return new ChannelSource(channel, budget, pageAllocator);
    }

    final void requireRange(long position, long length) throws ProjectIndexFormatException {
        if (position < 0 || length < 0 || position > size() - length) {
            throw format("Project index read range is outside the file");
        }
    }

    private static final class ByteArraySource extends ProjectIndexSource {
        private final byte[] bytes;

        private ByteArraySource(byte[] bytes) {
            this.bytes = Objects.requireNonNull(bytes, "bytes");
        }

        @Override
        long size() {
            return bytes.length;
        }

        @Override
        int readUnsignedByte(long position) throws ProjectIndexFormatException {
            requireRange(position, 1);
            return Byte.toUnsignedInt(bytes[(int) position]);
        }

        @Override
        void readFully(long position, byte[] target, int offset, int length)
                throws ProjectIndexFormatException {
            Objects.checkFromIndexSize(offset, length, target.length);
            requireRange(position, length);
            System.arraycopy(bytes, (int) position, target, offset, length);
        }

        @Override
        int crc32c(long position, long length) throws ProjectIndexFormatException {
            requireRange(position, length);
            var crc = new CRC32C();
            crc.update(bytes, (int) position, (int) length);
            return (int) crc.getValue();
        }

        @Override
        boolean locate(ByteRun run, long position) throws ProjectIndexFormatException {
            // The whole source is one run: this array is the caller's, read
            // and never written here, and the per-byte path already reads it
            // directly - the run adds no reach that a reader did not have.
            requireRange(position, 1);
            run.hold(bytes, 0, bytes.length);
            return true;
        }
    }

    private static final class ChannelSource extends ProjectIndexSource {
        private final SeekableByteChannel channel;
        private final long size;
        /**
         * Refilled in place, so this source hands out no run: the full
         * decoder keeps several readers over one channel source at once and
         * {@link #crc32c} refills the page under all of them.
         */
        private final byte[] page;
        private final ByteBuffer pageBuffer;
        private long pageStart = -1;
        private int pageLength;
        private long channelPosition = -1;

        private ChannelSource(SeekableByteChannel channel, AllocationBudget budget,
                IntFunction<byte[]> pageAllocator) throws ProjectIndexFormatException {
            this.channel = Objects.requireNonNull(channel, "channel");
            Objects.requireNonNull(budget, "budget");
            Objects.requireNonNull(pageAllocator, "pageAllocator");
            try {
                size = channel.size();
            } catch (IOException ex) {
                throw format("Unable to determine project index size", ex);
            }
            if (size < 0) {
                throw format("Project index has a negative size");
            }
            page = Objects.requireNonNull(pageAllocator.apply(MAX_RAW_BLOCK_BYTES),
                    "pageAllocator result");
            if (page.length != MAX_RAW_BLOCK_BYTES) {
                throw new IllegalArgumentException("Channel page allocator returned the wrong size");
            }
            pageBuffer = ByteBuffer.wrap(page);
        }

        @Override
        long size() {
            return size;
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
            int targetOffset = offset;
            int remaining = length;
            while (remaining > 0) {
                ensurePage(cursor);
                int pageOffset = (int) (cursor - pageStart);
                int chunk = Math.min(remaining, pageLength - pageOffset);
                System.arraycopy(page, pageOffset, target, targetOffset, chunk);
                cursor += chunk;
                targetOffset += chunk;
                remaining -= chunk;
            }
        }

        @Override
        int crc32c(long position, long length) throws ProjectIndexFormatException {
            requireRange(position, length);
            var crc = new CRC32C();
            long cursor = position;
            long remaining = length;
            pageStart = -1;
            while (remaining > 0) {
                int chunk = (int) Math.min(page.length, remaining);
                readChannel(cursor, chunk);
                crc.update(page, 0, chunk);
                cursor += chunk;
                remaining -= chunk;
            }
            pageStart = -1;
            return (int) crc.getValue();
        }

        private void ensurePage(long position) throws ProjectIndexFormatException {
            if (pageStart >= 0 && position >= pageStart && position < pageStart + pageLength) {
                return;
            }
            long aligned = position / page.length * page.length;
            pageLength = (int) Math.min(page.length, size - aligned);
            readChannel(aligned, pageLength);
            pageStart = aligned;
        }

        private void readChannel(long position, int length)
                throws ProjectIndexFormatException {
            try {
                if (channelPosition != position) {
                    channel.position(position);
                    channelPosition = position;
                }
                pageBuffer.clear();
                pageBuffer.limit(length);
                while (pageBuffer.hasRemaining()) {
                    int read = channel.read(pageBuffer);
                    if (read <= 0) {
                        throw format("Unable to make progress while reading project index");
                    }
                    channelPosition += read;
                }
            } catch (ProjectIndexFormatException ex) {
                throw ex;
            } catch (IOException ex) {
                throw format("Unable to read project index", ex);
            }
        }
    }
}
