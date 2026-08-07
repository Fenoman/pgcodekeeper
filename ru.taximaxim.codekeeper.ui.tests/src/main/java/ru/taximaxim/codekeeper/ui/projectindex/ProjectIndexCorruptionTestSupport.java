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
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ProjectIndexCorruptionTestSupport {

    private ProjectIndexCorruptionTestSupport() {
    }

    public static void corruptFirstBaseDefinitionBlock(ProjectIndexView view)
            throws IOException {
        flipByte(view.basePathForTests(),
                view.firstDefinitionBlockOffsetForTests());
    }

    public static void corruptLastBaseLocationBlock(ProjectIndexView view)
            throws IOException {
        try (FileChannel channel = FileChannel.open(
                view.basePathForTests(), StandardOpenOption.READ,
                StandardOpenOption.WRITE)) {
            ProjectIndexContainer.Opened container =
                    ProjectIndexContainer.open(channel, 0, channel.size());
            corruptLastBlock(channel, container, SectionType.LOCATIONS);
        }
    }

    public static void corruptBasePayloadBlocks(ProjectIndexView view)
            throws IOException {
        try (FileChannel channel = FileChannel.open(
                view.basePathForTests(), StandardOpenOption.READ,
                StandardOpenOption.WRITE)) {
            ProjectIndexContainer.Opened container =
                    ProjectIndexContainer.open(channel, 0, channel.size());
            corruptRecordBlocks(channel, container);
        }
    }

    public static void corruptFirstJournalDefinitionBlock(
            ProjectIndexView view, IndexPathRef path) throws IOException {
        corruptJournalPayloadBlocks(view, path, true);
    }

    public static void corruptLastJournalLocationBlock(
            ProjectIndexView view, IndexPathRef path) throws IOException {
        byte[] pathBytes = path.relativePath()
                .getBytes(StandardCharsets.UTF_8);
        byte[] journalBytes = Files.readAllBytes(
                view.journalPathForTests());
        int pathOffset = indexOf(journalBytes, pathBytes);
        if (pathOffset < 0) {
            throw new IOException("Unable to locate journal path");
        }
        long payloadStart = pathOffset + pathBytes.length;
        try (FileChannel channel = FileChannel.open(
                view.journalPathForTests(), StandardOpenOption.READ,
                StandardOpenOption.WRITE)) {
            ProjectIndexContainer.Opened container =
                    ProjectIndexContainer.open(channel, payloadStart,
                            channel.size() - payloadStart
                                    - Long.BYTES);
            corruptLastBlock(channel, container, SectionType.LOCATIONS);
        }
    }

    public static void corruptJournalPayloadBlocks(ProjectIndexView view,
            IndexPathRef path) throws IOException {
        corruptJournalPayloadBlocks(view, path, false);
    }

    private static void corruptJournalPayloadBlocks(ProjectIndexView view,
            IndexPathRef path, boolean definitionOnly) throws IOException {
        byte[] pathBytes = path.relativePath()
                .getBytes(StandardCharsets.UTF_8);
        byte[] journalBytes = Files.readAllBytes(
                view.journalPathForTests());
        int pathOffset = indexOf(journalBytes, pathBytes);
        if (pathOffset < 0) {
            throw new IOException("Unable to locate journal path");
        }
        long payloadStart = pathOffset + pathBytes.length;
        try (FileChannel channel = FileChannel.open(
                view.journalPathForTests(), StandardOpenOption.READ,
                StandardOpenOption.WRITE)) {
            ProjectIndexContainer.Opened container =
                    ProjectIndexContainer.open(channel, payloadStart,
                            channel.size() - payloadStart
                                    - Long.BYTES);
            if (definitionOnly) {
                ProjectIndexLocator.BlockEntry block = container.locator()
                        .blocks(SectionType.DEFINITIONS).block(0);
                flipByte(channel,
                        container.codecOffset() + block.offset());
            } else {
                corruptRecordBlocks(channel, container);
            }
        }
    }

    private static void corruptRecordBlocks(FileChannel channel,
            ProjectIndexContainer.Opened container) throws IOException {
        Set<Long> offsets = new LinkedHashSet<>();
        for (SectionType section : List.of(SectionType.STRINGS,
                SectionType.DEFINITIONS, SectionType.LOCATIONS)) {
            ProjectIndexLocator.PackedBlocks blocks =
                    container.locator().blocks(section);
            for (int i = 0; i < blocks.blockCount(); i++) {
                offsets.add(container.codecOffset()
                        + blocks.block(i).offset());
            }
        }
        if (offsets.isEmpty()) {
            throw new IOException("Project index has no payload blocks");
        }
        for (long offset : offsets) {
            flipByte(channel, offset);
        }
    }

    private static void corruptLastBlock(FileChannel channel,
            ProjectIndexContainer.Opened container, SectionType section)
            throws IOException {
        ProjectIndexLocator.PackedBlocks blocks =
                container.locator().blocks(section);
        if (blocks.blockCount() < 2) {
            throw new IOException(
                    "Project index section must contain multiple payload blocks");
        }
        ProjectIndexLocator.BlockEntry block =
                blocks.block(blocks.blockCount() - 1);
        flipByte(channel, container.codecOffset() + block.offset());
    }

    private static void flipByte(java.nio.file.Path file, long offset)
            throws IOException {
        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            flipByte(channel, offset);
        }
    }

    private static void flipByte(FileChannel channel, long offset)
            throws IOException {
        ByteBuffer value = ByteBuffer.allocate(1);
        if (channel.read(value, offset) != 1) {
            throw new IOException("Unable to read corruption target");
        }
        value.flip();
        value.put(0, (byte) (value.get(0) ^ 1));
        if (channel.write(value, offset) != 1) {
            throw new IOException("Unable to write corruption target");
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer: for (int i = 0; i <= haystack.length - needle.length;
                i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
