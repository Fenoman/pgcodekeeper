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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import ru.taximaxim.codekeeper.ui.DatabaseType;

class ProjectIndexConfigDigestTest {

    @Test
    void relevantFileAndEffectiveSettingChangesInvalidateDigest(
            @TempDir Path project) throws Exception {
        ProjectIndexConfiguration first = configuration(false);
        byte[] baseline = ProjectIndexConfigDigest.calculate(
                project, first, () -> false);
        Files.writeString(project.resolve(".pgcodekeeperignore"), "first");
        byte[] withIgnore = ProjectIndexConfigDigest.calculate(
                project, first, () -> false);
        Files.writeString(project.resolve(".pgcodekeeperignore"), "second");
        byte[] changedIgnore = ProjectIndexConfigDigest.calculate(
                project, first, () -> false);
        byte[] changedSetting = ProjectIndexConfigDigest.calculate(
                project, configuration(true), () -> false);

        Assertions.assertFalse(Arrays.equals(baseline, withIgnore));
        Assertions.assertFalse(Arrays.equals(withIgnore, changedIgnore));
        Assertions.assertFalse(Arrays.equals(changedIgnore, changedSetting));
    }

    @Test
    void ordinarySqlContentIsExcludedFromConfigurationDigest(
            @TempDir Path project) throws Exception {
        byte[] baseline = ProjectIndexConfigDigest.calculate(
                project, configuration(false), () -> false);
        Files.writeString(project.resolve("ordinary.sql"), "SELECT 1");

        byte[] after = ProjectIndexConfigDigest.calculate(
                project, configuration(false), () -> false);

        Assertions.assertArrayEquals(baseline, after);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            ".pgcodekeeper",
            ".pgcodekeeperignore",
            ".pgcodekeeperignoreschema",
            ".pgcodekeeperdependencies",
            ".dependencies",
            "structure.properties"
    })
    void everyLoaderConfigurationFileInvalidatesDigest(
            String fileName, @TempDir Path project) throws Exception {
        byte[] baseline = ProjectIndexConfigDigest.calculate(
                project, configuration(false), () -> false);

        Files.writeString(project.resolve(fileName), "changed");

        Assertions.assertFalse(Arrays.equals(baseline,
                ProjectIndexConfigDigest.calculate(
                        project, configuration(false), () -> false)));
    }

    @Test
    void theAddedFilesSettingChangesTheIndexIdentity(@TempDir Path project)
            throws Exception {
        byte[] off = ProjectIndexConfigDigest.calculate(
                project, configuration(false, false), () -> false);
        byte[] on = ProjectIndexConfigDigest.calculate(
                project, configuration(false, true), () -> false);

        Assertions.assertFalse(Arrays.equals(off, on),
                "indexes built under different invalidation rules must not be interchangeable");
    }

    @Test
    void cancellationFailsClosed(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve(".dependencies"), "<dependencies/>");
        var cancelled = new AtomicBoolean(true);

        Assertions.assertThrows(InterruptedException.class,
                () -> ProjectIndexConfigDigest.calculate(project,
                        configuration(false), cancelled::get));
    }

    private static ProjectIndexConfiguration configuration(
            boolean bodyDependencies) {
        return configuration(bodyDependencies, false);
    }

    private static ProjectIndexConfiguration configuration(
            boolean bodyDependencies, boolean incrementalAddedFiles) {
        return new ProjectIndexConfiguration(DatabaseType.PG, true, true,
                bodyDependencies, incrementalAddedFiles, false, "dummy_tmp");
    }
}
