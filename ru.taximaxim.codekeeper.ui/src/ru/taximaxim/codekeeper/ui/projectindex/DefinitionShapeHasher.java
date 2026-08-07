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
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import org.pgcodekeeper.core.database.api.schema.ObjectReference;

import ru.taximaxim.codekeeper.ui.projectindex.PackedDefinition.NameType;
import ru.taximaxim.codekeeper.ui.projectindex.PackedDefinition.PackedArgument;

public final class DefinitionShapeHasher {

    private DefinitionShapeHasher() {
    }

    public static byte[] hash(PackedDefinition definition) {
        try {
            var bytes = new ByteArrayOutputStream(256);
            try (var output = new DataOutputStream(bytes)) {
                writeString(output, definition.kind().name());
                writeReference(output, definition.object().reference());
                writeMatchKey(output, definition.object());
                writeString(output, definition.bareName());
                writeArguments(output, definition.arguments());
                writeArguments(output, definition.orderBy());
                writeNameTypes(output, definition.returnColumns());
                writeString(output, definition.returns());
                output.writeBoolean(definition.setof());
                writeNameTypes(output, definition.relationColumns());
                output.writeBoolean(definition.relationColumnsKnown());
                writeNameTypes(output, definition.compositeAttributes());
                output.writeBoolean(definition.primaryKey());
                output.writeInt(definition.constraintColumns().size());
                for (String column : definition.constraintColumns()) {
                    writeString(output, column);
                }
                writeString(output, definition.operatorLeft());
                writeString(output, definition.operatorRight());
                writeString(output, definition.operatorReturns());
                writeString(output, definition.castSource());
                writeString(output, definition.castTarget());
                writeString(output, definition.castContext() == null
                        ? null : definition.castContext().name());
            }
            return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
        } catch (IOException ex) {
            throw new IllegalStateException("Unexpected in-memory shape encoding failure", ex);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private static void writeReference(DataOutputStream output, ObjectReference reference)
            throws IOException {
        output.writeBoolean(reference != null);
        if (reference != null) {
            writeString(output, reference.schema());
            writeString(output, reference.table());
            writeString(output, reference.column());
            writeString(output, reference.type() == null ? null : reference.type().name());
        }
    }

    private static void writeMatchKey(DataOutputStream output, PackedLocation location)
            throws IOException {
        ObjectReference reference = location.reference();
        if (reference == null || reference.type() == null) {
            output.writeBoolean(false);
            writeString(output, location.alias());
            output.writeBoolean(location.isGlobal());
            return;
        }
        output.writeBoolean(true);
        ReferenceMatchKey key = ReferenceMatchKey.from(location);
        writeString(output, key.family().name());
        writeString(output, key.exactType() == null ? null : key.exactType().name());
        writeString(output, key.schema());
        writeString(output, key.table());
        writeString(output, key.column());
        writeString(output, key.alias());
        output.writeBoolean(key.global());
    }

    private static void writeArguments(DataOutputStream output, List<PackedArgument> arguments)
            throws IOException {
        output.writeInt(arguments.size());
        for (PackedArgument argument : arguments) {
            writeString(output, argument.mode().name());
            writeString(output, argument.name());
            writeString(output, argument.dataType());
            writeString(output, argument.defaultExpression());
            output.writeBoolean(argument.readOnly());
        }
    }

    private static void writeNameTypes(DataOutputStream output, List<NameType> values)
            throws IOException {
        output.writeInt(values.size());
        for (NameType value : values) {
            writeString(output, value.name());
            writeString(output, value.type());
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        if (value == null) {
            output.writeInt(-1);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }
}
