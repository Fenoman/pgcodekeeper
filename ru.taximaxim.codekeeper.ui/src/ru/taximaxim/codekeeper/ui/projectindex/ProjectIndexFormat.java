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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32C;

import org.pgcodekeeper.core.database.api.schema.ICast.CastContext;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;

import ru.taximaxim.codekeeper.ui.projectindex.PackedDefinition.NameType;
import ru.taximaxim.codekeeper.ui.projectindex.PackedDefinition.PackedArgument;

final class ProjectIndexFormat {

    static final int FORMAT_MAJOR = 2;
    /**
     * Deliberately not bumped when unresolvedAny became honest. The readers
     * compare it for equality, so a bump makes every existing index report
     * CORRUPT — which wipes the store and puts "validation" in the telemetry of
     * an index that is merely older. The preference that changed the semantics
     * enters configSha256 instead, so an index built under the old rule is
     * reported STALE and rebuilt, and until it is, its all-true unresolvedAny
     * refuses every addition on its own.
     */
    static final int FORMAT_MINOR = 0;
    static final int HEADER_SIZE = 44;
    static final int SECTION_FRAME_SIZE = 12;
    static final int DIRECTORY_ENTRY_SIZE = 20;
    static final int FOOTER_SIZE = 20;
    static final int MAX_RAW_BLOCK_BYTES = 64 << 10;
    static final int MAX_SECTION_BYTES = 512 << 20;
    static final int MAX_RECORD_BYTES = 64 << 20;
    static final int MAX_BINARY_WRITER_BYTES = MAX_RECORD_BYTES + 5;
    static final int MAX_IN_MEMORY_INDEX_BYTES = 1 << 30;
    static final int MAX_RECORD_COUNT = 10_000_000;
    static final int MAX_STRING_COUNT = 5_000_000;
    static final int MAX_NESTED_COUNT = 1_000_000;
    static final long MAX_DECODE_ALLOCATION = 1L << 30;
    static final long MAX_PROSPECTIVE_BUILD_BYTES = 256L << 20;

    static final byte[] HEADER_MAGIC = "PGCKIDX2".getBytes(StandardCharsets.US_ASCII);
    static final byte[] DIRECTORY_MAGIC = "PGCKDIR2".getBytes(StandardCharsets.US_ASCII);
    static final byte[] FOOTER_MAGIC = "PGCKEND2".getBytes(StandardCharsets.US_ASCII);

    static final Comparator<IndexPathRef> PATH_ORDER = Comparator
            .comparing(IndexPathRef::origin)
            .thenComparing(IndexPathRef::relativePath);

    private static final Comparator<String> NULLABLE_STRING_ORDER =
            Comparator.nullsFirst(Comparator.naturalOrder());

    private static final Comparator<ObjectReference> REFERENCE_ORDER = Comparator
            .nullsFirst(Comparator.comparing(ObjectReference::schema, NULLABLE_STRING_ORDER)
                    .thenComparing(ObjectReference::table, NULLABLE_STRING_ORDER)
                    .thenComparing(ObjectReference::column, NULLABLE_STRING_ORDER)
                    .thenComparing(ObjectReference::type,
                            Comparator.nullsFirst(Comparator.naturalOrder())));

    private static final Comparator<PackedArgument> ARGUMENT_ORDER = Comparator
            .comparing(PackedArgument::mode)
            .thenComparing(PackedArgument::name, NULLABLE_STRING_ORDER)
            .thenComparing(PackedArgument::dataType)
            .thenComparing(PackedArgument::defaultExpression, NULLABLE_STRING_ORDER)
            .thenComparing(PackedArgument::readOnly);

    private static final Comparator<NameType> NAME_TYPE_ORDER = Comparator
            .comparing(NameType::name)
            .thenComparing(NameType::type);

    private static final Comparator<CastContext> CAST_CONTEXT_ORDER =
            Comparator.nullsFirst(Comparator.naturalOrder());

    static final Comparator<PackedLocation> LOCATION_ORDER = Comparator
            .comparing(PackedLocation::origin)
            .thenComparing(PackedLocation::relativePath)
            .thenComparingInt(PackedLocation::offset)
            .thenComparingInt(PackedLocation::lineNumber)
            .thenComparingInt(PackedLocation::charPositionInLine)
            .thenComparingInt(PackedLocation::length)
            .thenComparing(PackedLocation::reference, REFERENCE_ORDER)
            .thenComparing(PackedLocation::action, NULLABLE_STRING_ORDER)
            .thenComparing(PackedLocation::alias, NULLABLE_STRING_ORDER)
            .thenComparing(PackedLocation::locationType)
            .thenComparing(PackedLocation::danger,
                    Comparator.nullsFirst(Comparator.naturalOrder()));

    static final Comparator<PackedDefinition> DEFINITION_ORDER = Comparator
            .comparing(PackedDefinition::object, LOCATION_ORDER)
            .thenComparing(PackedDefinition::kind)
            .thenComparing(ProjectIndexFormat::compareDefinitionDetails);

    private ProjectIndexFormat() {
    }

    static int crc32c(byte[] bytes, int offset, int length) {
        var crc = new CRC32C();
        crc.update(bytes, offset, length);
        return (int) crc.getValue();
    }

    static int varIntSize(int value) {
        int size = 1;
        while ((value & ~0x7F) != 0) {
            size++;
            value >>>= 7;
        }
        return size;
    }

    static int nextInMemoryCapacity(int current, long required, int limit) {
        if (current < 0 || limit <= 0 || current > limit) {
            throw new IllegalArgumentException("Invalid in-memory buffer capacity limit");
        }
        if (required < 0 || required > limit) {
            throw new IllegalArgumentException(
                    "In-memory project index exceeds the configured size limit");
        }
        if (required <= current) {
            return current;
        }

        long doubled = Math.max(1L, current) * 2;
        return Math.toIntExact(Math.max(required, Math.min(limit, doubled)));
    }

    static long arrayBytes(long elements, int elementBytes) {
        if (elements < 0 || elementBytes < 0) {
            throw new IllegalArgumentException("Array size components must not be negative");
        }
        try {
            long payload = Math.multiplyExact(elements, elementBytes);
            return Math.addExact(16, Math.addExact(payload, 7)) & ~7L;
        } catch (ArithmeticException ex) {
            return Long.MAX_VALUE;
        }
    }

    static long listBytes(long elements, int backingCopies) {
        try {
            return Math.multiplyExact(backingCopies,
                    Math.addExact(32, arrayBytes(elements, Long.BYTES)));
        } catch (ArithmeticException ex) {
            return Long.MAX_VALUE;
        }
    }

    static long setBytes(long elements) {
        try {
            return Math.addExact(64,
                    Math.addExact(arrayBytes(Math.multiplyExact(elements, 2), Long.BYTES),
                            Math.multiplyExact(elements, 32)));
        } catch (ArithmeticException ex) {
            return Long.MAX_VALUE;
        }
    }

    static <E> E checkedEnum(E[] values, int ordinal, String label)
            throws ProjectIndexFormatException {
        if (ordinal < 0 || ordinal >= values.length) {
            throw format("Invalid " + label + " ordinal: " + ordinal);
        }
        return values[ordinal];
    }

    static ProjectIndexFormatException format(String message) {
        return new ProjectIndexFormatException(message);
    }

    static ProjectIndexFormatException format(String message, Throwable cause) {
        return new ProjectIndexFormatException(message, cause);
    }

    private static int compareDefinitionDetails(PackedDefinition left, PackedDefinition right) {
        int result = NULLABLE_STRING_ORDER.compare(left.bareName(), right.bareName());
        if (result == 0) {
            result = left.comment().compareTo(right.comment());
        }
        if (result == 0) {
            result = compareLists(left.arguments(), right.arguments(), ARGUMENT_ORDER);
        }
        if (result == 0) {
            result = compareLists(left.orderBy(), right.orderBy(), ARGUMENT_ORDER);
        }
        if (result == 0) {
            result = compareLists(left.returnColumns(), right.returnColumns(), NAME_TYPE_ORDER);
        }
        if (result == 0) {
            result = NULLABLE_STRING_ORDER.compare(left.returns(), right.returns());
        }
        if (result == 0) {
            result = Boolean.compare(left.setof(), right.setof());
        }
        if (result == 0) {
            result = compareLists(left.relationColumns(), right.relationColumns(), NAME_TYPE_ORDER);
        }
        if (result == 0) {
            result = Boolean.compare(left.relationColumnsKnown(), right.relationColumnsKnown());
        }
        if (result == 0) {
            result = compareLists(left.compositeAttributes(), right.compositeAttributes(), NAME_TYPE_ORDER);
        }
        if (result == 0) {
            result = Boolean.compare(left.primaryKey(), right.primaryKey());
        }
        if (result == 0) {
            result = compareLists(left.constraintColumns(), right.constraintColumns(),
                    Comparator.naturalOrder());
        }
        if (result == 0) {
            result = NULLABLE_STRING_ORDER.compare(left.operatorLeft(), right.operatorLeft());
        }
        if (result == 0) {
            result = NULLABLE_STRING_ORDER.compare(left.operatorRight(), right.operatorRight());
        }
        if (result == 0) {
            result = NULLABLE_STRING_ORDER.compare(left.operatorReturns(), right.operatorReturns());
        }
        if (result == 0) {
            result = NULLABLE_STRING_ORDER.compare(left.castSource(), right.castSource());
        }
        if (result == 0) {
            result = NULLABLE_STRING_ORDER.compare(left.castTarget(), right.castTarget());
        }
        if (result == 0) {
            result = CAST_CONTEXT_ORDER.compare(left.castContext(), right.castContext());
        }
        return result;
    }

    private static <T> int compareLists(List<T> left, List<T> right, Comparator<T> comparator) {
        int shared = Math.min(left.size(), right.size());
        for (int i = 0; i < shared; i++) {
            int result = comparator.compare(left.get(i), right.get(i));
            if (result != 0) {
                return result;
            }
        }
        return Integer.compare(left.size(), right.size());
    }

    static void writeAt(SeekableByteChannel channel, long position, byte[] bytes) throws IOException {
        long restore = channel.position();
        channel.position(position);
        writeFully(channel, ByteBuffer.wrap(bytes));
        channel.position(restore);
    }

    static void writeFully(SeekableByteChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            int written = channel.write(buffer);
            if (written <= 0) {
                throw new IOException("Unable to make progress while writing project index");
            }
        }
    }

    record DirectoryEntry(
            SectionType type,
            int flags,
            long offset,
            int payloadLength,
            int payloadCrc,
            long directoryPosition) {
    }

    abstract static class BinaryOutput {

        abstract void writeByte(int value) throws IOException;

        abstract void writeBoolean(boolean value) throws IOException;

        abstract void writeVarInt(int value) throws IOException;

        abstract void writeStringId(ProjectIndexRecords.StringTable strings, String value)
                throws IOException;

        abstract void writeBytes(byte[] bytes) throws IOException;

        abstract void writeBytes(byte[] bytes, int offset, int length) throws IOException;
    }

    static final class BinaryWriter extends BinaryOutput {
        private final WriterBuffer output;
        private final int limit;

        BinaryWriter() {
            this(MAX_BINARY_WRITER_BYTES);
        }

        BinaryWriter(int limit) {
            this(limit, Math.min(32, limit));
        }

        BinaryWriter(int limit, int initialCapacity) {
            if (limit <= 0 || limit > MAX_IN_MEMORY_INDEX_BYTES) {
                throw new IllegalArgumentException("Invalid in-memory binary writer limit");
            }
            if (initialCapacity < 0 || initialCapacity > limit) {
                throw new IllegalArgumentException("Invalid in-memory binary writer capacity");
            }
            this.limit = limit;
            output = new WriterBuffer(initialCapacity);
        }

        int size() {
            return output.size();
        }

        void writeByte(int value) {
            requireCapacity(1);
            output.write(value);
        }

        void writeBoolean(boolean value) {
            writeByte(value ? 1 : 0);
        }

        void writeShort(int value) {
            requireCapacity(Short.BYTES);
            output.write(value >>> 8);
            output.write(value);
        }

        void writeInt(int value) {
            requireCapacity(Integer.BYTES);
            output.write(value >>> 24);
            output.write(value >>> 16);
            output.write(value >>> 8);
            output.write(value);
        }

        void writeLong(long value) {
            requireCapacity(Long.BYTES);
            output.write((int) (value >>> 56));
            output.write((int) (value >>> 48));
            output.write((int) (value >>> 40));
            output.write((int) (value >>> 32));
            output.write((int) (value >>> 24));
            output.write((int) (value >>> 16));
            output.write((int) (value >>> 8));
            output.write((int) value);
        }

        void writeVarInt(int value) {
            if (value < 0) {
                throw new IllegalArgumentException("Varint value must not be negative");
            }
            requireCapacity(varIntSize(value));
            VarInts.writeUnsignedInt(output, value);
        }

        void writeStringId(ProjectIndexRecords.StringTable strings, String value) {
            writeVarInt(strings.id(value));
        }

        void writeBytes(byte[] bytes) {
            Objects.requireNonNull(bytes, "bytes");
            writeBytes(bytes, 0, bytes.length);
        }

        @Override
        void writeBytes(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            requireCapacity(length);
            output.write(bytes, offset, length);
        }

        byte[] bytes() {
            return output.toByteArray();
        }

        void writeTo(BinaryOutput target) throws IOException {
            target.writeBytes(output.array(), 0, output.size());
        }

        void reset() {
            output.reset();
        }

        private void requireCapacity(int additionalBytes) {
            if (additionalBytes < 0 || (long) output.size() + additionalBytes > limit) {
                throw new IllegalArgumentException(
                        "In-memory binary writer exceeds the configured size limit");
            }
        }

        private static final class WriterBuffer extends ByteArrayOutputStream {

            WriterBuffer(int initialCapacity) {
                super(initialCapacity);
            }

            byte[] array() {
                return buf;
            }
        }
    }

    static final class ChannelWriter extends BinaryOutput {
        private static final int BUFFER_SIZE = 64 << 10;

        private final SeekableByteChannel channel;
        private final CRC32C crc;
        private final long limit;
        private final byte[] buffer = new byte[BUFFER_SIZE];
        private int buffered;
        private long written;

        ChannelWriter(SeekableByteChannel channel, boolean checksummed, long limit) {
            this.channel = Objects.requireNonNull(channel, "channel");
            this.crc = checksummed ? new CRC32C() : null;
            if (limit < 0) {
                throw new IllegalArgumentException("Writer limit must not be negative");
            }
            this.limit = limit;
        }

        long size() {
            return written;
        }

        int crc() {
            if (crc == null) {
                throw new IllegalStateException("This writer does not calculate a checksum");
            }
            if (buffered != 0) {
                throw new IllegalStateException("The project index writer must be flushed before reading its checksum");
            }
            return (int) crc.getValue();
        }

        void writeByte(int value) throws IOException {
            requireCapacity(1);
            if (buffered == buffer.length) {
                flushBuffer();
            }
            buffer[buffered++] = (byte) value;
            written++;
        }

        void writeBoolean(boolean value) throws IOException {
            writeByte(value ? 1 : 0);
        }

        void writeLong(long value) throws IOException {
            writeByte((int) (value >>> 56));
            writeByte((int) (value >>> 48));
            writeByte((int) (value >>> 40));
            writeByte((int) (value >>> 32));
            writeByte((int) (value >>> 24));
            writeByte((int) (value >>> 16));
            writeByte((int) (value >>> 8));
            writeByte((int) value);
        }

        void writeVarInt(int value) throws IOException {
            while ((value & ~0x7F) != 0) {
                writeByte((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            writeByte(value);
        }

        void writeStringId(ProjectIndexRecords.StringTable strings, String value) throws IOException {
            writeVarInt(strings.id(value));
        }

        void writeBytes(byte[] bytes) throws IOException {
            writeBytes(bytes, 0, bytes.length);
        }

        void writeBytes(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            requireCapacity(length);
            int remaining = length;
            int sourceOffset = offset;
            while (remaining > 0) {
                if (buffered == buffer.length) {
                    flushBuffer();
                }
                int chunk = Math.min(remaining, buffer.length - buffered);
                System.arraycopy(bytes, sourceOffset, buffer, buffered, chunk);
                buffered += chunk;
                sourceOffset += chunk;
                remaining -= chunk;
            }
            written += length;
        }

        void finish() throws IOException {
            flushBuffer();
        }

        private void flushBuffer() throws IOException {
            if (buffered == 0) {
                return;
            }
            writeFully(channel, ByteBuffer.wrap(buffer, 0, buffered));
            if (crc != null) {
                crc.update(buffer, 0, buffered);
            }
            buffered = 0;
        }

        private void requireCapacity(int length) {
            if (written > limit - length) {
                throw new IllegalArgumentException("Project index section exceeds the size limit");
            }
        }
    }

    static final class BinaryReader {
        private final ProjectIndexSource source;
        private final long end;
        private final String context;
        private final AllocationBudget budget;
        /**
         * The stretch of bytes this reader may read without asking the source,
         * shared with every slice cut from it so that a record and the block
         * it sits in do not each pay their own way back to the source.
         *
         * <p>It reaches no further than the reader's own position does: both
         * are the state of one decode on one thread.
         */
        private final ProjectIndexSource.ByteRun run;
        /** Set once a source has answered that it hands out no run. */
        private boolean runless;
        private long position;

        BinaryReader(ProjectIndexSource source, long offset, int length, String context,
                AllocationBudget budget)
                throws ProjectIndexFormatException {
            this(source, offset, length, context, budget,
                    new ProjectIndexSource.ByteRun());
        }

        private BinaryReader(ProjectIndexSource source, long offset, int length,
                String context, AllocationBudget budget, ProjectIndexSource.ByteRun run)
                throws ProjectIndexFormatException {
            Objects.requireNonNull(source, "source");
            if (offset < 0 || length < 0 || offset > source.size() - length) {
                throw format(context + " range is outside the project index");
            }
            this.source = source;
            this.position = offset;
            this.end = offset + length;
            this.context = context;
            this.budget = budget;
            this.run = run;
        }

        long absolutePosition() {
            return position;
        }

        int remaining() {
            return Math.toIntExact(end - position);
        }

        boolean hasRemaining() {
            return position < end;
        }

        int readUnsignedByte() throws ProjectIndexFormatException {
            require(1);
            if (run.holds(position)) {
                return run.byteAt(position++);
            }
            if (!runless && source.locate(run, position)) {
                return run.byteAt(position++);
            }
            runless = true;
            return source.readUnsignedByte(position++);
        }

        boolean readBoolean() throws ProjectIndexFormatException {
            int value = readUnsignedByte();
            if (value > 1) {
                throw format(context + " contains an invalid boolean: " + value);
            }
            return value == 1;
        }

        int readUnsignedShort() throws ProjectIndexFormatException {
            return readUnsignedByte() << 8 | readUnsignedByte();
        }

        int readInt() throws ProjectIndexFormatException {
            require(Integer.BYTES);
            int value = readUnsignedByte() << 24
                    | readUnsignedByte() << 16
                    | readUnsignedByte() << 8
                    | readUnsignedByte();
            return value;
        }

        long readUnsignedInt() throws ProjectIndexFormatException {
            return Integer.toUnsignedLong(readInt());
        }

        long readLong() throws ProjectIndexFormatException {
            require(Long.BYTES);
            return (long) readInt() << 32 | Integer.toUnsignedLong(readInt());
        }

        int readVarInt() throws ProjectIndexFormatException {
            int value = 0;
            for (int shift = 0; shift < 35; shift += 7) {
                int current = readUnsignedByte();
                if (shift == 28 && (current & 0xF8) != 0) {
                    throw format(context + " contains an overflowing varint");
                }
                value |= (current & 0x7F) << shift;
                if ((current & 0x80) == 0) {
                    if (shift != 0 && (current & 0x7F) == 0) {
                        throw format(context + " contains a non-minimal varint");
                    }
                    return value;
                }
            }
            throw format(context + " contains an unterminated varint");
        }

        int readCount(String label, int maximum, int estimatedBytes)
                throws ProjectIndexFormatException {
            int count = readVarInt();
            if (count > maximum) {
                throw format(context + ' ' + label + " count exceeds the limit: " + count);
            }
            if (budget != null) {
                budget.claim((long) count * estimatedBytes, context + ' ' + label + " records");
            }
            return count;
        }

        byte[] readBytes(int length) throws ProjectIndexFormatException {
            require(length);
            if (budget != null) {
                budget.claim(arrayBytes(length, Byte.BYTES), context + " byte array");
            }
            byte[] value = new byte[length];
            copyAtPosition(value, 0, length);
            position += length;
            return value;
        }

        String readUtf8(int length) throws ProjectIndexFormatException {
            require(length);
            claim(64 + arrayBytes(length, Character.BYTES), "retained UTF-8 string");
            try (var ignored = reserve(256 + arrayBytes(length, Byte.BYTES)
                    + arrayBytes(length, Character.BYTES), "temporary UTF-8 decoder buffers")) {
                byte[] encoded = new byte[length];
                copyAtPosition(encoded, 0, length);
                var decoder = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT);
                String value = decoder.decode(ByteBuffer.wrap(encoded)).toString();
                position += length;
                return value;
            } catch (CharacterCodingException ex) {
                throw format(context + " contains invalid UTF-8", ex);
            }
        }

        String readString(ProjectIndexRecords.StringTable strings) throws ProjectIndexFormatException {
            return strings.value(readVarInt());
        }

        String readRequiredString(ProjectIndexRecords.StringTable strings, String label)
                throws ProjectIndexFormatException {
            String value = readString(strings);
            if (value == null) {
                throw format(context + " has a null " + label);
            }
            return value;
        }

        <E> E readEnum(E[] values, String label) throws ProjectIndexFormatException {
            return checkedEnum(values, readVarInt(), label);
        }

        void claim(long bytes, String label) throws ProjectIndexFormatException {
            if (budget != null) {
                budget.claim(bytes, context + ' ' + label);
            }
        }

        AllocationBudget.Reservation reserve(long bytes, String label)
                throws ProjectIndexFormatException {
            return budget == null ? AllocationBudget.Reservation.NONE
                    : budget.reserve(bytes, context + ' ' + label);
        }

        BinaryReader slice(int length, String label) throws ProjectIndexFormatException {
            return slice(length, label, budget);
        }

        /**
         * A reader over the next {@code length} bytes, sharing this reader's
         * hold on the source. A record is read straight after the varint that
         * announced it, so the slice starts where the parent already is, and
         * the parent resumes where the slice stopped - one hold serves all of
         * it, and neither pays to re-find bytes the other just had.
         */
        BinaryReader slice(int length, String label, AllocationBudget sliceBudget)
                throws ProjectIndexFormatException {
            require(length);
            BinaryReader slice = new BinaryReader(
                    source, position, length, label, sliceBudget, run);
            slice.runless = runless;
            position += length;
            return slice;
        }

        void requireMagic(byte[] magic, String label) throws ProjectIndexFormatException {
            require(magic.length);
            for (byte expected : magic) {
                if (source.readUnsignedByte(position++) != Byte.toUnsignedInt(expected)) {
                    throw format("Invalid project index " + label);
                }
            }
        }

        void requireEnd() throws ProjectIndexFormatException {
            if (position != end) {
                throw format(context + " has " + remaining() + " unexpected trailing bytes");
            }
        }

        /**
         * Copies the {@code length} bytes at the current position, off the
         * held run when it covers all of them and through the source
         * otherwise. Leaves the position where it was: the callers advance it
         * once they have a value, so that a failed decode does not.
         */
        private void copyAtPosition(byte[] target, int offset, int length)
                throws ProjectIndexFormatException {
            if (run.holds(position, length)) {
                run.copyTo(position, target, offset, length);
            } else {
                source.readFully(position, target, offset, length);
            }
        }

        private void require(int length) throws ProjectIndexFormatException {
            if (length < 0 || length > remaining()) {
                throw format(context + " length exceeds its remaining bytes");
            }
        }
    }

    static final class AllocationBudget {
        private final long limit;
        private long retained;
        private long temporary;

        AllocationBudget(long limit) {
            this.limit = limit;
        }

        void claim(long bytes, String label) throws ProjectIndexFormatException {
            if (bytes < 0 || retained > limit - temporary
                    || retained + temporary > limit - bytes) {
                throw format("Project index allocation limit exceeded by " + label);
            }
            retained += bytes;
        }

        Reservation reserve(long bytes, String label) throws ProjectIndexFormatException {
            if (bytes < 0 || retained > limit - temporary
                    || retained + temporary > limit - bytes) {
                throw format("Project index allocation limit exceeded by " + label);
            }
            temporary += bytes;
            return new Reservation(this, bytes);
        }

        private void release(long bytes) {
            temporary -= bytes;
        }

        static final class Reservation implements AutoCloseable {
            private static final Reservation NONE = new Reservation(null, 0);

            private AllocationBudget budget;
            private final long bytes;

            private Reservation(AllocationBudget budget, long bytes) {
                this.budget = budget;
                this.bytes = bytes;
            }

            @Override
            public void close() {
                if (budget != null) {
                    budget.release(bytes);
                    budget = null;
                }
            }
        }
    }

    static final class MemoryChannel implements SeekableByteChannel {
        private byte[] bytes = new byte[1024];
        private int size;
        private int position;
        private boolean open = true;

        byte[] toByteArray() {
            return Arrays.copyOf(bytes, size);
        }

        @Override
        public int read(ByteBuffer destination) {
            ensureOpen();
            if (position >= size) {
                return -1;
            }
            int length = Math.min(destination.remaining(), size - position);
            destination.put(bytes, position, length);
            position += length;
            return length;
        }

        @Override
        public int write(ByteBuffer source) {
            ensureOpen();
            int length = source.remaining();
            long required = (long) position + length;
            ensureCapacity(required);
            source.get(bytes, position, length);
            position += length;
            size = Math.max(size, position);
            return length;
        }

        @Override
        public long position() {
            ensureOpen();
            return position;
        }

        @Override
        public SeekableByteChannel position(long newPosition) {
            ensureOpen();
            if (newPosition < 0 || newPosition > MAX_IN_MEMORY_INDEX_BYTES) {
                throw new IllegalArgumentException("Invalid in-memory channel position");
            }
            position = (int) newPosition;
            return this;
        }

        @Override
        public long size() {
            ensureOpen();
            return size;
        }

        @Override
        public SeekableByteChannel truncate(long newSize) {
            ensureOpen();
            if (newSize < 0 || newSize > MAX_IN_MEMORY_INDEX_BYTES) {
                throw new IllegalArgumentException("Invalid in-memory channel size");
            }
            size = Math.min(size, (int) newSize);
            position = Math.min(position, size);
            return this;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }

        private void ensureCapacity(long required) {
            if (required <= bytes.length) {
                return;
            }
            int capacity = nextInMemoryCapacity(bytes.length, required,
                    MAX_IN_MEMORY_INDEX_BYTES);
            bytes = Arrays.copyOf(bytes, capacity);
        }

        private void ensureOpen() {
            if (!open) {
                throw new IllegalStateException("Channel is closed");
            }
        }
    }
}
