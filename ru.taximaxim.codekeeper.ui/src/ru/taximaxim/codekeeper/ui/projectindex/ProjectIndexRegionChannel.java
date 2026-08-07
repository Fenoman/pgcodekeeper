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
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;

final class ProjectIndexRegionChannel implements SeekableByteChannel {

    private final SeekableByteChannel delegate;
    private final long start;
    private long position;

    ProjectIndexRegionChannel(SeekableByteChannel delegate, long start) {
        if (start < 0) {
            throw new IllegalArgumentException(
                    "Negative project index region start");
        }
        this.delegate = delegate;
        this.start = start;
    }

    @Override
    public synchronized int read(ByteBuffer destination) throws IOException {
        synchronized (delegate) {
            long restore = delegate.position();
            delegate.position(start + position);
            int read = delegate.read(destination);
            if (read > 0) {
                position += read;
            }
            delegate.position(restore);
            return read;
        }
    }

    @Override
    public synchronized int write(ByteBuffer source) throws IOException {
        synchronized (delegate) {
            long restore = delegate.position();
            delegate.position(start + position);
            int written = delegate.write(source);
            if (written > 0) {
                position += written;
            }
            delegate.position(restore);
            return written;
        }
    }

    @Override
    public long position() {
        return position;
    }

    @Override
    public SeekableByteChannel position(long newPosition) {
        if (newPosition < 0) {
            throw new IllegalArgumentException("Negative project index region position");
        }
        position = newPosition;
        return this;
    }

    @Override
    public long size() throws IOException {
        return Math.max(0, delegate.size() - start);
    }

    @Override
    public SeekableByteChannel truncate(long size) throws IOException {
        if (size < 0) {
            throw new IllegalArgumentException("Negative project index region size");
        }
        delegate.truncate(start + size);
        if (position > size) {
            position = size;
        }
        return this;
    }

    @Override
    public boolean isOpen() {
        return delegate.isOpen();
    }

    @Override
    public void close() {
        // The container owns the underlying channel.
    }
}
