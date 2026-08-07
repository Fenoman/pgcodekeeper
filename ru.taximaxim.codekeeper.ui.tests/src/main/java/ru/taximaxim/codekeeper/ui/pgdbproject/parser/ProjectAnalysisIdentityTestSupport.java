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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.pg.jdbc.PgSupportedVersion;
import org.pgcodekeeper.core.settings.CoreSettings;

import ru.taximaxim.codekeeper.ui.DatabaseType;

/**
 * Builds comparison-profile digests for tests that live outside the package of
 * {@link ProjectAnalysisIdentity}.
 */
public final class ProjectAnalysisIdentityTestSupport {

    private static final String CONFIGURATION = "configuration-digest";

    private ProjectAnalysisIdentityTestSupport() {
    }

    /** @return digest of the baseline settings */
    public static String digest() {
        return digest(baseline(), CONFIGURATION);
    }

    /**
     * @param directory      directory to write the ignore list into
     * @param ignoredObject  name of one hidden object
     * @return digest of the baseline settings with that ignore list
     * @throws IOException if the ignore list cannot be written or read
     */
    public static String digestWithIgnoreList(Path directory,
            String ignoredObject) throws IOException {
        Path list = directory.resolve(ignoredObject + ".pgcodekeeperignore"); //$NON-NLS-1$
        Files.writeString(list,
                "SHOW ALL\nHIDE NONE " + ignoredObject + " type=TABLE\n", //$NON-NLS-1$ //$NON-NLS-2$
                StandardCharsets.UTF_8);
        CoreSettings settings = baseline();
        settings.addIgnoreList(list);
        return digest(settings, CONFIGURATION);
    }

    /**
     * @param configuration digest of the shared project configuration files
     * @return digest of the baseline settings under that configuration
     */
    public static String digestWithConfiguration(String configuration) {
        return digest(baseline(), configuration);
    }

    private static String digest(CoreSettings settings, String configuration) {
        return ProjectAnalysisIdentity.digest(
                ProjectComparisonProfile.capture(
                        DatabaseType.PG, settings, configuration)
                        .orElseThrow());
    }

    private static CoreSettings baseline() {
        var settings = new CoreSettings();
        settings.setInCharsetName("UTF-8"); //$NON-NLS-1$
        settings.setTimeZone("UTC"); //$NON-NLS-1$
        settings.setAllowedTypes(List.of(DbObjType.TABLE, DbObjType.FUNCTION));
        settings.setVersion(PgSupportedVersion.VERSION_16);
        return settings;
    }
}
