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
import java.nio.channels.SeekableByteChannel;

public final class ProjectIndexCodec {

    static final int MAX_RAW_BLOCK_BYTES = ProjectIndexFormat.MAX_RAW_BLOCK_BYTES;

    private ProjectIndexCodec() {
    }

    public static byte[] encodeBase(ProjectIndexData source) {
        try (var channel = new ProjectIndexFormat.MemoryChannel()) {
            writeBase(source, channel);
            return channel.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to write the in-memory project index", ex);
        }
    }

    public static void writeBase(ProjectIndexData source, SeekableByteChannel channel)
            throws IOException {
        ProjectIndexBaseWriter.write(source, channel);
    }

    static ProjectIndexBaseWriter.WriteResult writeBase(
            ProjectIndexData source,
            SeekableByteChannel channel, ProjectIndexWriteContext context)
            throws IOException {
        return ProjectIndexBaseWriter.write(source, channel, context);
    }

    static ProjectIndexBaseWriter.WriteResult writeBase(
            ProjectIndexReplaySource source,
            SeekableByteChannel channel)
            throws IOException {
        return ProjectIndexBaseWriter.write(source, channel);
    }

    static ProjectIndexBaseWriter.WriteResult writeBase(
            ProjectIndexReplaySource source,
            SeekableByteChannel channel, ProjectIndexWriteContext context)
            throws IOException {
        return ProjectIndexBaseWriter.write(source, channel, context);
    }

    public static ProjectIndexData decodeBase(byte[] bytes, long maxResidentBytes)
            throws ProjectIndexFormatException {
        return ProjectIndexBaseReader.read(bytes, maxResidentBytes);
    }

    public static ProjectIndexData readBase(SeekableByteChannel channel, long maxResidentBytes)
            throws ProjectIndexFormatException {
        return ProjectIndexBaseReader.read(channel, maxResidentBytes);
    }
}
