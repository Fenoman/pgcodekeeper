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
package ru.taximaxim.codekeeper.ui.pgdbproject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.pgcodekeeper.core.database.api.IDatabaseProvider;
import org.pgcodekeeper.core.database.api.project.IWorkDirs;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.model.difftree.DiffTree;
import org.pgcodekeeper.core.model.difftree.TreeElement;
import org.pgcodekeeper.core.model.difftree.TreeFlattener;
import org.pgcodekeeper.core.settings.ISettings;

/**
 * The two ways every file of a project is written at once, and the line between
 * them.
 * <p>
 * The line is which side the model being written comes from:
 * <p>
 * <b>The rules a project hides by apply when the source is a database.</b> A
 * column a {@code type=COLUMN} rule hides is not pgCodeKeeper's to write and
 * never enters a project file; an object a rule hides gets no file at all. That
 * is what the project said when it declared them unmanaged, and a project
 * created out of a database says it from its first commit rather than from the
 * first time something is applied to it.
 * <p>
 * <b>They must not apply when the source is the project itself.</b> Rewriting a
 * project into another directory layout hands its own model back to the writer,
 * and applying the rules there would delete from its files the very columns the
 * rules exist to protect - a relayout that quietly drops what the project alone
 * declares.
 * <p>
 * Both calls live here because the difference between them is one word at each
 * call site and the damage of getting it wrong is silent. The core draws the
 * same line and documents it on {@code AbstractModelExporter#exportFull()}.
 *
 * @see ru.taximaxim.codekeeper.ui.settings.UISettings#forExport(org.eclipse.core.resources.IProject)
 */
public final class WholeProjectExport {

    /**
     * Writes the whole of a database into the files of a project, applying the
     * rules the given settings carry.
     * <p>
     * The selection is built exactly as {@code PgCodeKeeperApi} builds it for
     * the CLI export: everything the database holds, less what the rules hide.
     * The rules then have their second say inside the export itself, where a
     * hidden column is left out of the body of the table that holds it.
     * <p>
     * The exporter is asked for without a structure file, which makes it read
     * the {@code structure.properties} of the target directory - the layout the
     * project already carries, and the layout the path this replaced used. The
     * core overload that takes a structure file answers a {@code null} one with
     * the default layout instead of reading that file, so it is not the one to
     * call here.
     *
     * @param provider    the provider of the DBMS being exported
     * @param database    the model to write, loaded from a database or a dump
     * @param projectPath the project directory, which must hold none of the
     *                    directories of the layout yet
     * @param settings    the settings of the export, which carry the rules the
     *                    project hides by
     * @throws IOException          if the files cannot be written
     * @throws InterruptedException if the operation is cancelled
     */
    public static void fromDatabase(IDatabaseProvider provider, IDatabase database, Path projectPath,
            ISettings settings) throws IOException, InterruptedException {
        TreeElement root = DiffTree.create(settings, null, database, settings.getMonitor());
        root.setAllChecked();

        List<TreeElement> selected = new TreeFlattener()
                .onlySelected()
                .useIgnoreList(settings.getIgnoreList())
                .onlyTypes(settings.getAllowedTypes())
                .flatten(root);

        provider.getModelExporter(projectPath, database, selected, settings).exportProject();
    }

    /**
     * Rewrites the files of a project into another directory layout, leaving
     * every rule unasked.
     * <p>
     * The model handed over is the project itself, read back from the very
     * files about to be replaced, so there is nothing here a rule could have an
     * opinion about: whatever a rule hid would simply be deleted from the
     * project that declares it. {@code updateFull} is the one export that asks
     * the settings nothing for exactly this reason - do not route this call
     * through {@link #fromDatabase}, and do not teach {@code exportFull} the
     * rules.
     *
     * @param provider        the provider of the DBMS of the project
     * @param project         the model of the project, loaded from its files
     * @param projectPath     the project directory
     * @param keepOverrides   whether the overrides directory survives the rewrite
     * @param currentWorkDirs the layout the files are in now, whose directories
     *                        are cleaned along with those of the new layout
     * @param settings        the settings of the export
     * @throws IOException if the files cannot be written
     */
    public static void relayout(IDatabaseProvider provider, IDatabase project, Path projectPath,
            boolean keepOverrides, IWorkDirs currentWorkDirs, ISettings settings) throws IOException {
        provider.getProjectUpdater(project, null, null, projectPath, false, settings)
                .updateFull(keepOverrides, currentWorkDirs);
    }

    private WholeProjectExport() {
    }
}
