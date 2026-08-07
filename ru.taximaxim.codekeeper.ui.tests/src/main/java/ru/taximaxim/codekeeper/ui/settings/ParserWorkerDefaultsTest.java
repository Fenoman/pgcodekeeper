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
package ru.taximaxim.codekeeper.ui.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ParserWorkerDefaultsTest {

    @Test
    void halfTheProcessorsReproducesTheMeasuredStandaloneTuning() {
        // The standalone product used to hard-code six workers. The measurement
        // that produced that number ran on a twelve-CPU machine, so the same
        // machine must still get six.
        assertEquals(6, ParserWorkerDefaults.scale(12));
        assertEquals(3, ParserWorkerDefaults.scale(6));
        assertEquals(4, ParserWorkerDefaults.scale(9),
                "an odd CPU count rounds down, never up");
    }

    @Test
    void smallMachinesKeepTheConservativeGenericDefault() {
        assertEquals(2, ParserWorkerDefaults.scale(4));
        assertEquals(2, ParserWorkerDefaults.scale(3));
        assertEquals(2, ParserWorkerDefaults.scale(2));
        assertEquals(2, ParserWorkerDefaults.scale(1));
        assertEquals(2, ParserWorkerDefaults.scale(0),
                "a JVM that cannot count CPUs must not lose parallelism");
        assertEquals(2, ParserWorkerDefaults.scale(-1));
        assertEquals(ParserWorkerPreferences.GENERIC_DEFAULT_WORKERS,
                ParserWorkerDefaults.MIN_DEFAULT_WORKERS);
    }

    @Test
    void manyCoreMachinesStayBounded() {
        assertEquals(8, ParserWorkerDefaults.scale(16));
        assertEquals(8, ParserWorkerDefaults.scale(32));
        assertEquals(8, ParserWorkerDefaults.scale(256));
        assertEquals(8, ParserWorkerDefaults.scale(Integer.MAX_VALUE));
    }

    @Test
    void everyDefaultIsAcceptedByThePreferenceValidator() {
        for (int cpus = -8; cpus <= 512; cpus++) {
            int workers = ParserWorkerDefaults.scale(cpus);
            assertTrue(workers >= ParserWorkerPreferences.MIN_WORKERS
                    && workers <= ParserWorkerPreferences.MAX_WORKERS,
                    "out of the editable range for " + cpus + " CPUs");
        }
    }

    @Test
    void runtimeDefaultUsesTheSameFormula() {
        assertEquals(
                ParserWorkerDefaults.scale(
                        Runtime.getRuntime().availableProcessors()),
                ParserWorkerDefaults.defaultWorkers());
    }
}
