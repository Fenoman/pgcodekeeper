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
package ru.taximaxim.codekeeper.ui.pgdbproject.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jface.preference.PreferenceStore;
import org.junit.jupiter.api.Test;

import ru.taximaxim.codekeeper.ui.UIConsts.PREF;

class ParserCacheCleanerTest {

    @Test
    void interruptedCleanerLoopExitsWithoutSpinning() throws InterruptedException {
        var store = new PreferenceStore();
        store.setValue(PREF.PARSER_CACHE_CLEANING_INTERVAL, 10);
        var sleepCalls = new AtomicInteger();
        var cleanCalls = new AtomicInteger();

        Thread cleanerThread = Thread.ofVirtual().start(() -> ParserCacheCleaner.runCleanerLoop(store, millis -> {
            sleepCalls.incrementAndGet();
            throw new InterruptedException();
        }, millis -> cleanCalls.incrementAndGet()));

        cleanerThread.join(1000);
        assertFalse(cleanerThread.isAlive());
        assertEquals(1, sleepCalls.get());
        assertEquals(0, cleanCalls.get());
    }

    @Test
    void zeroCleaningIntervalKeepsManualOnlyMode() throws InterruptedException {
        var store = new PreferenceStore();
        store.setValue(PREF.PARSER_CACHE_CLEANING_INTERVAL, 0);
        var sleepCalls = new AtomicInteger();
        var cleanCalls = new AtomicInteger();

        Thread cleanerThread = Thread.ofVirtual().start(() -> ParserCacheCleaner.runCleanerLoop(store, millis -> {
            if (sleepCalls.incrementAndGet() == 2) {
                throw new InterruptedException();
            }
        }, millis -> cleanCalls.incrementAndGet()));

        cleanerThread.join(1000);
        assertFalse(cleanerThread.isAlive());
        assertEquals(2, sleepCalls.get());
        assertEquals(0, cleanCalls.get());
    }

    @Test
    void cleaningIntervalIsConvertedFromMinutes() throws InterruptedException {
        var store = new PreferenceStore();
        store.setValue(PREF.PARSER_CACHE_CLEANING_INTERVAL, 10);
        var sleepCalls = new AtomicInteger();
        var cleaningInterval = new AtomicLong();

        Thread cleanerThread = Thread.ofVirtual().start(() -> ParserCacheCleaner.runCleanerLoop(store, millis -> {
            if (sleepCalls.incrementAndGet() == 2) {
                throw new InterruptedException();
            }
        }, cleaningInterval::set));

        cleanerThread.join(1000);
        assertFalse(cleanerThread.isAlive());
        assertEquals(TimeUnit.MINUTES.toMillis(10), cleaningInterval.get());
    }
}
