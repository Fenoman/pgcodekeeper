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

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.pgcodekeeper.core.api.ComparisonDepth;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.base.loader.AbstractProjectLoader;
import org.pgcodekeeper.core.ignorelist.IgnoreList;
import org.pgcodekeeper.core.ignorelist.IgnoredObject;
import org.pgcodekeeper.core.settings.ISettings;

import ru.taximaxim.codekeeper.ui.Log;
import ru.taximaxim.codekeeper.ui.UIConsts.PROJ_PREF;
import ru.taximaxim.codekeeper.ui.dbstore.DbInfo;
import ru.taximaxim.codekeeper.ui.prefs.ignoredobjects.InternalIgnoreList;
import ru.taximaxim.codekeeper.ui.properties.OverridablePrefs;
import ru.taximaxim.codekeeper.ui.xmlstore.IgnoreListsXmlStore;

/**
 * The rules a project hides by, and the way they reach the settings of an
 * operation.
 * <p>
 * Core reads the project {@code .pgcodekeeperignore} while it loads the project
 * side, but the workspace-wide list, the lists a database connection carries and
 * the extra lists configured for the project are known to the UI alone. Both the
 * comparison and the writer of project files are built from settings and now
 * answer to these rules - the comparison drops what they hide, the export leaves
 * a hidden column out of a project file - so a rule that never reaches the
 * settings cannot take effect.
 * <p>
 * The assembly lives here rather than in either caller on purpose: a comparison
 * that hides an object and an export that writes it back are the same defect
 * seen twice, and two copies of these four sources are exactly how the two would
 * come to disagree.
 * <p>
 * {@link #dropColumnRulesIfStructural} lives beside {@link #read} and
 * {@link #install} for the same reason: a comparison loaded without analysis
 * cannot trust a {@code type=COLUMN} rule, and that has to be true of every
 * reader of these settings at once or the same disagreement returns through a
 * different door. See that method for why - and for why it answers to the
 * depth of one load rather than to a preference that outlives it.
 */
public final class ProjectIgnoreLists {

    /**
     * Assembles every ignore list the given project hides by: the workspace-wide
     * list when the project asks for it, the project {@code .pgcodekeeperignore},
     * the lists the database of this run carries, and the extra lists configured
     * for the project.
     *
     * @param project        the project whose rules are wanted
     * @param oneTimePrefs   preferences overriding the stored ones for this run,
     *                       or {@code null}
     * @param remoteSnapshot the remote side of this run, may carry its own lists,
     *                       {@code null} when there is none
     * @return the assembled rules, empty when nothing is configured anywhere
     */
    public static IgnoreList read(IProject project, Map<String, Object> oneTimePrefs,
            Object remoteSnapshot) {
        Objects.requireNonNull(project, "project"); //$NON-NLS-1$

        var prefs = new OverridablePrefs(project, oneTimePrefs);
        boolean isGlobal = (boolean) prefs.get(PROJ_PREF.USE_GLOBAL_IGNORE_LIST);
        IgnoreList ignoreList = isGlobal ? InternalIgnoreList.readInternalList() : new IgnoreList();

        InternalIgnoreList.readAppendList(
                Paths.get(project.getLocationURI()).resolve(AbstractProjectLoader.IGNORE_FILE), ignoreList);

        if (remoteSnapshot instanceof DbInfo dbInfo) {
            dbInfo.appendIgnoreFiles(ignoreList);
        }

        try {
            for (String path : new IgnoreListsXmlStore(project).readObjects()) {
                InternalIgnoreList.readAppendList(Paths.get(path), ignoreList);
            }
        } catch (IOException e) {
            Log.log(e);
        }
        return ignoreList;
    }

    /**
     * Merges the given rules into the ignore list of the settings.
     * <p>
     * Rules are copied rather than shared: the settings are copied again for each
     * comparison side and every list merges rules in place, so a shared rule could
     * be rewritten under whoever handed it over.
     *
     * @param settings the settings of the operation, receive the merged rules
     * @param rules    the rules to hide by
     */
    public static void install(ISettings settings, IgnoreList rules) {
        Objects.requireNonNull(settings, "settings"); //$NON-NLS-1$
        Objects.requireNonNull(rules, "rules"); //$NON-NLS-1$

        IgnoreList target = settings.getIgnoreList();
        if (!rules.isShow()) {
            // a white list hides by default and takes precedence, exactly as
            // when several list files are parsed into one list
            target.setShow(false);
        }
        rules.getList().stream()
                .map(rule -> rule.copy(rule.getName()))
                .forEach(target::add);
    }

    /**
     * Turns off every {@code type=COLUMN} rule already merged into {@code
     * settings}, for a comparison that was loaded at {@link
     * ComparisonDepth#STRUCTURAL_ONLY} - after every source has had its say,
     * so that no rule merged in later can bring one back.
     * <p>
     * <b>Why columns and not the rest.</b> A rule that hides a whole object is
     * decided from the model alone: the difference tree and the panel's child
     * filtering ask only whether a name and a type match, which a structural
     * load answers exactly as well as a full one - that is the whole of what
     * {@code type=TABLE}, {@code type=TRIGGER} and every other object-level
     * rule need, and it is why this method leaves them alone. A {@code
     * type=COLUMN} rule cannot be decided that cheaply: before a column is
     * hidden, {@code ColumnVisibility} must first ask whether anything else in
     * the database still reads it - a view, a foreign key, an index on an
     * expression - and that answer is read off {@code
     * IStatement#getDependencies()}, which only the analysis phase fills in.
     * Measured on a project carrying such a rule: a column a view still read
     * was reported managed by every other measure and then left out of the
     * table's project file regardless, because the one thing that would have
     * kept it was never asked. Guessing "probably still needed" is not an
     * option either - the reason the analysis phase exists at all is that the
     * answer depends on the rest of the database, not on the table alone - so
     * a rule that needs it is turned off here rather than trusted on a guess.
     * <p>
     * <b>Why depth, and not the receive-only preference.</b> The preference
     * decides how a comparison would load by default, but it does not decide
     * how any one comparison actually did: {@code
     * ProjectEditorDiffer.reloadForScript()} leaves the preference on and
     * still forces exactly one load to {@link ComparisonDepth#FULL}, because a
     * migration script needs the dependencies a structural load never
     * carries - see {@code forceFullDepthOnce}. That recomputed load carries
     * real dependencies again, so the reason a {@code type=COLUMN} rule was
     * ever turned off is gone, preference or no preference; asking the
     * preference instead of the depth this one load actually reached would
     * keep the rule off regardless and make the recomputed script disagree
     * with an ordinary one, which is the one thing the mode may never change:
     * a script built with it on has to be identical to one built with it off.
     * So this asks the one thing that is actually true of the settings in
     * hand: whether {@code
     * getDependencies()} was ever going to be filled in for them.
     * <p>
     * <b>Why in place, and why here.</b> {@code ColumnVisibility.of(ISettings)},
     * the comparison pane, the object table and the project writer all read
     * {@link ISettings#getIgnoreList()} of this same settings instance, at
     * different times and from different classes. Mutating the one list every
     * one of them reads, once, the moment a comparison is ready to be shown, is
     * what keeps the tree, the pane and the file this comparison eventually
     * writes from disagreeing about a column - which is exactly the failure
     * the mode must not cause a second time. Calling this any earlier would
     * miss rules a loader has not merged in yet: the project's own {@code
     * .pgcodekeeperignore} is added by the loader itself while a comparison
     * loads, not by {@link #read}, so a call made before loading finishes would
     * see only part of what this settings instance ends up carrying.
     * <p>
     * A rule that names {@code COLUMN} together with another type keeps that
     * other type: only the facet of it that would have hidden a column is
     * removed, so a rule written as {@code type=TABLE,COLUMN} still hides the
     * table. A rule that names {@code COLUMN} alone is dropped outright rather
     * than left with an empty type set, because an empty set means "every
     * type" everywhere else this list is read - turning a retired column rule
     * into one that suddenly hides tables, views, anything sharing its name
     * would be the opposite of retiring it.
     *
     * @param depth    the depth the comparison {@code settings} belongs to was
     *                 actually loaded at - not a preference, an outcome; only
     *                 {@link ComparisonDepth#STRUCTURAL_ONLY} turns anything
     *                 off, {@code null} included, so a rolled-back comparison
     *                 ({@code depth == null}) is left untouched rather than
     *                 mistaken for one
     * @param settings the settings already carrying every ignore list this
     *                 operation hides by, from whichever sources contributed
     *                 to it and in whatever order; mutated in place, or left
     *                 untouched entirely when the depth is not structural or
     *                 no rule of it names a column
     * @return how many rules lost their column facet here, which is what a
     *         reader of the comparison is told - a rule turned off in silence
     *         is a rule that reads as broken, and the columns it names then
     *         reach a project file with nothing on the screen having said so.
     *         Zero whenever nothing was turned off, the untouched cases
     *         included
     */
    public static int dropColumnRulesIfStructural(ComparisonDepth depth, ISettings settings) {
        if (settings == null || depth != ComparisonDepth.STRUCTURAL_ONLY) {
            return 0;
        }

        IgnoreList ignoreList = settings.getIgnoreList();
        List<IgnoredObject> current = ignoreList.getList();
        if (current.stream().noneMatch(rule -> rule.getObjTypes().contains(DbObjType.COLUMN))) {
            // the common case: nothing here ever named a column, so there is
            // nothing to retire and the list is left exactly as assembled
            return 0;
        }

        int retired = 0;
        List<IgnoredObject> snapshot = new ArrayList<>(current);
        ignoreList.clearList();
        for (IgnoredObject rule : snapshot) {
            Set<DbObjType> types = rule.getObjTypes();
            if (!types.contains(DbObjType.COLUMN)) {
                ignoreList.add(rule);
                continue;
            }

            // a rule keeping its other types is counted here too: the facet
            // that would have hidden a column is gone from it either way, and
            // that is the whole of what the reader is being told about
            retired++;
            Set<DbObjType> otherTypes = EnumSet.copyOf(types);
            otherTypes.remove(DbObjType.COLUMN);
            if (otherTypes.isEmpty()) {
                // named COLUMN and nothing else: retiring its one facet
                // retires the whole rule, so it is dropped rather than kept
                // with an empty type set - see the class javadoc above for why
                // empty is not the same as "retired" for this list
                continue;
            }
            ignoreList.add(new IgnoredObject(rule.getName(),
                    rule.getDbRegex() == null ? null : rule.getDbRegex().pattern(),
                    rule.isShow(), rule.isRegular(), rule.isIgnoreContent(), rule.isQualified(), otherTypes));
        }
        return retired;
    }

    private ProjectIgnoreLists() {
    }
}
