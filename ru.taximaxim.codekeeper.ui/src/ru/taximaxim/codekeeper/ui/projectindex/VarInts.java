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

final class VarInts {

    private VarInts() {
    }

    static void writeUnsignedInt(ByteArrayOutputStream output, int value) {
        if (value < 0) {
            throw new IllegalArgumentException("Unsigned varint must not be negative");
        }
        while ((value & ~0x7F) != 0) {
            output.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        output.write(value);
    }

    static void writeUnsignedLong(ByteArrayOutputStream output, long value) {
        if (value < 0) {
            throw new IllegalArgumentException("Unsigned varlong must not be negative");
        }
        while ((value & ~0x7FL) != 0) {
            output.write((int) (value & 0x7F) | 0x80);
            value >>>= 7;
        }
        output.write((int) value);
    }
}
