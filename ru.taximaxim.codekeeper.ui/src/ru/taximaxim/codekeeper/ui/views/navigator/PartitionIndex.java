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
package ru.taximaxim.codekeeper.ui.views.navigator;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IResourceDeltaVisitor;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;

import ru.taximaxim.codekeeper.ui.Log;
import ru.taximaxim.codekeeper.ui.PerformanceTelemetry;
import ru.taximaxim.codekeeper.ui.pgdbproject.parser.PgDbParser;

/**
 * Which files of a folder are sections, and of which table.
 *
 * <p>The map is per folder because that is the unit everything asks about: a
 * comparator sorting a folder needs every answer at once, and a content
 * provider showing sections under their parent needs both directions of the
 * same folder. Building it is a folder scan, so building it per folder is also
 * the only way to pay for the folders that are actually looked at.</p>
 *
 * <p><b>Nothing here runs on the UI thread.</b> A miss does not block and does
 * not compute: it schedules and answers {@code null}, which every caller has
 * to read as "no section information", not as "no sections". The folder keeps
 * its plain ordering until the scan lands and the viewer is refreshed. That is
 * the whole reason the answer is nullable.</p>
 */
public final class PartitionIndex {

    private static final PartitionIndex INSTANCE = new PartitionIndex();

    private static final String SQL_EXTENSION = "sql"; //$NON-NLS-1$

    /**
     * What a folder scan produced. Both directions are built together because
     * both come from the same pass, and the second one is what step two of
     * this feature shows under the parent.
     *
     * <p><b>Two kinds of link, and they are not interchangeable.</b> A link the
     * parser confirmed is one where a reference it really produced sits where
     * the text spells the clause. A link only the text claims is what is left
     * when the project index has nothing to say about the file at all - it is
     * switched off, bypassed, or the schema is excluded from it - and on such a
     * project that is every file of the folder. Ordering is answered from both:
     * a text that lied puts a row in the wrong place, which is visible and
     * costs nothing else. Nesting is answered from the confirmed ones alone: a
     * text that lied there takes the file out of the folder it lives in, and
     * losing an object with nowhere to find it again is the failure that ruled
     * out rearranging the files instead.</p>
     */
    public static final class Folder {

        private final Map<IPath, String> parentByFile;
        private final Map<String, List<IFile>> filesByParent;
        private final Map<IPath, IFile> parentFileByFile;
        private final Map<IPath, List<IFile>> sectionsByParentFile;

        /**
         * @param confirmed the files whose link the parser confirmed; the only
         *                  ones that may be nested
         */
        Folder(Map<IPath, String> parentByFile,
                Map<String, List<IFile>> filesByParent,
                Map<IFile, IFile> parentFileBySection, Set<IPath> confirmed) {
            this.parentByFile = parentByFile;
            this.filesByParent = filesByParent;
            // the one place the asymmetry is enforced. Both nesting maps are
            // derived from this filtered one and not from the argument, so the
            // node that hides a file and the node that shows it cannot come to
            // different answers about whether it may be hidden at all
            Map<IFile, IFile> nested = new LinkedHashMap<>();
            parentFileBySection.forEach((section, parent) -> {
                if (confirmed.contains(section.getFullPath())) {
                    nested.put(section, parent);
                }
            });
            Map<IPath, IFile> byPath = new HashMap<>();
            nested.forEach(
                    (section, parent) -> byPath.put(section.getFullPath(), parent));
            this.parentFileByFile = Collections.unmodifiableMap(byPath);
            this.sectionsByParentFile = invert(nested);
        }

        /**
         * The qualified name of the table this file is a section of, or
         * {@code null} when it is not a section.
         *
         * <p>Answers from both kinds of link, which is what lets a folder be
         * ordered on a project whose index says nothing. Not a permission to
         * hide anything - {@link #parentFileOf} is the question that grants
         * that, and it is a different question.</p>
         */
        public String parentOf(IFile file) {
            return file == null ? null : parentByFile.get(file.getFullPath());
        }

        /**
         * The sections of a table, by its qualified name, in no particular
         * order. Empty when the table has none in this folder.
         *
         * <p>The ordering view, so it holds sections whose link only the text
         * claims. Nothing that removes a file from the tree may be built on it;
         * {@link #sectionsUnder} is the view that may.</p>
         */
        public List<IFile> sectionsOf(String qualifiedName) {
            List<IFile> found = filesByParent
                    .get(PartitionLinkReader.groupingKey(qualifiedName));
            return found == null ? List.of() : found;
        }

        /**
         * The file that defines the table this one is a section of, when that
         * file is in this same folder, and {@code null} otherwise - including
         * for a file that is a section of a table this project does not hold,
         * or holds somewhere else.
         *
         * <p>This is the one question that decides whether anything may be
         * hidden. A section whose parent is not a node of this tree has to
         * stay where it is: hiding it would take the object out of the user
         * interface altogether, and that is the whole reason the layout of the
         * files was not changed instead. A section whose link only the text
         * claims is the same case for the same reason and is answered the same
         * way - it never entered this map.</p>
         */
        public IFile parentFileOf(IFile file) {
            return file == null ? null : parentFileByFile.get(file.getFullPath());
        }

        /**
         * The sections shown under this file, by name. Empty for a file that is
         * not the parent of anything in this folder.
         *
         * <p>Deliberately the inverse of {@link #parentFileOf(IFile)} and not a
         * second lookup by name: the node that hides a file and the node that
         * shows it have to be the same node or a file disappears, so they are
         * two readings of one map rather than two computations.</p>
         */
        public List<IFile> sectionsUnder(IFile file) {
            if (file == null) {
                return List.of();
            }
            List<IFile> found = sectionsByParentFile.get(file.getFullPath());
            return found == null ? List.of() : found;
        }

        /** Whether the folder holds any section at all. */
        public boolean isEmpty() {
            return parentByFile.isEmpty();
        }

        /**
         * How many sections this folder will actually nest. Read back off the
         * built maps rather than counted while scanning, so the telemetry line
         * cannot claim a nesting the tree does not perform.
         */
        int nestedCount() {
            return parentFileByFile.size();
        }

        private static Map<IPath, List<IFile>> invert(Map<IFile, IFile> byFile) {
            if (byFile.isEmpty()) {
                return Map.of();
            }
            Map<IPath, List<IFile>> inverted = new HashMap<>();
            byFile.forEach((section, parent) -> inverted
                    .computeIfAbsent(parent.getFullPath(), k -> new ArrayList<>())
                    .add(section));
            Collator collator = Collator.getInstance();
            for (Map.Entry<IPath, List<IFile>> entry : inverted.entrySet()) {
                List<IFile> sections = new ArrayList<>(entry.getValue());
                sections.sort((a, b) -> collator.compare(a.getName(), b.getName()));
                entry.setValue(List.copyOf(sections));
            }
            return Collections.unmodifiableMap(inverted);
        }
    }

    private final Map<IPath, Folder> folders = new ConcurrentHashMap<>();
    private final Set<IPath> building = ConcurrentHashMap.newKeySet();

    private final IResourceChangeListener invalidator = this::onResourceChange;
    private final Consumer<IProject> republished = this::forgetProject;
    private volatile boolean listening;

    private PartitionIndex() {
    }

    public static PartitionIndex getInstance() {
        return INSTANCE;
    }

    /**
     * What is known about the folder, or {@code null} when nothing is known
     * yet. A {@code null} schedules the scan; the caller is expected to be
     * called again once {@code whenReady} fires.
     *
     * @param whenReady run after the scan lands, on whatever thread the job
     *                  ended on; may be {@code null}
     */
    public Folder folderOf(IFolder folder, Runnable whenReady) {
        if (folder == null || !folder.isAccessible()) {
            return null;
        }
        IPath key = folder.getFullPath();
        Folder known = folders.get(key);
        if (known != null) {
            return known;
        }
        schedule(folder, key, whenReady);
        return null;
    }

    /** Forgets everything. Test seam and the answer to a closing workspace. */
    public void clear() {
        folders.clear();
        building.clear();
    }

    /**
     * Drops what is known about one folder. Package-visible so the resource
     * listener and the tests use the same door.
     */
    void invalidate(IPath folder) {
        folders.remove(folder);
    }

    /**
     * Drops everything known about one project, because a build has just
     * published a new index of it.
     *
     * <p>A scan is half text and half index. Which files spell the clause is
     * read from the files, and a file that changes is dropped by the resource
     * listener above; but <em>whether the parser confirms the link</em> is
     * read from the project index, and that answer moves for files nothing
     * happened to. The case this exists for is the ordinary one: the navigator
     * is opened while the first build is still running, every folder is
     * scanned against an index that answers nothing, and the result -
     * {@code answered=0}, text-only links, no nesting at all - would then
     * stand for the life of the workbench, because no file ever changed.</p>
     *
     * <p><b>Narrow on purpose, in two directions.</b> Only this project is
     * forgotten, so a build of one project does not make the others re-read
     * their folders. And only a published <em>whole</em> index gets here: an
     * increment, which is what saving a file produces, leaves the folders
     * alone, because re-reading the head of two thousand files to learn that
     * one file moved is the cost this feature was measured to avoid - and that
     * one file's folder was dropped by the resource delta anyway.</p>
     *
     * <p>Deliberately <em>not</em> hung on the invalidation that schedules a
     * rebuild. That call empties the index and asks for a new one; forgetting
     * here would send the scans off against an index on its way to nothing and
     * quietly settle the tree into the state that has no nesting in it. The
     * publication that follows is the honest moment, and it is the one wired
     * up.</p>
     */
    void forgetProject(IProject project) {
        if (project != null) {
            forgetUnder(project.getFullPath());
        }
    }

    /**
     * Publishes a scan result without running one. The seam the unit tests use
     * to talk about ordering without a workspace and without a parser.
     *
     * <p>This overload resolves no parent file, so it describes a folder whose
     * sections all have their parent elsewhere: ordering applies, nesting does
     * not. That is what the ordering tests want to talk about.</p>
     */
    void put(IPath folder, Map<IPath, String> parentByFile,
            Map<String, List<IFile>> filesByParent) {
        put(folder, parentByFile, filesByParent, Map.of());
    }

    /**
     * Publishes a scan result including which file each section is shown
     * under, with every link confirmed by the parser. The seam the nesting
     * tests use, and the shape a project with a working index produces.
     */
    void put(IPath folder, Map<IPath, String> parentByFile,
            Map<String, List<IFile>> filesByParent,
            Map<IFile, IFile> parentFileBySection) {
        put(folder, parentByFile, filesByParent, parentFileBySection,
                parentByFile.keySet());
    }

    /**
     * Publishes a scan result that says which of its links the parser
     * confirmed. The seam for talking about a project whose index answers
     * partly or not at all.
     */
    void put(IPath folder, Map<IPath, String> parentByFile,
            Map<String, List<IFile>> filesByParent,
            Map<IFile, IFile> parentFileBySection, Set<IPath> confirmed) {
        folders.put(folder, new Folder(parentByFile, filesByParent,
                parentFileBySection, confirmed));
    }

    private void schedule(IFolder folder, IPath key, Runnable whenReady) {
        listen();
        if (!building.add(key)) {
            return;
        }
        Job job = Job.create("pgCodeKeeper: sections of " + key, //$NON-NLS-1$
                (IProgressMonitor monitor) -> {
                    try {
                        Folder scanned = scan(folder);
                        if (scanned != null) {
                            folders.put(key, scanned);
                        }
                    } catch (Exception e) {
                        // an ordering is not worth an error dialog; the folder
                        // keeps the ordering it has
                        Log.log(Log.LOG_WARNING,
                                "Could not read the sections of " + key, e); //$NON-NLS-1$
                    } finally {
                        building.remove(key);
                    }
                    if (whenReady != null) {
                        whenReady.run();
                    }
                    return Status.OK_STATUS;
                });
        job.setSystem(true);
        job.setPriority(Job.DECORATE);
        job.schedule();
    }

    /**
     * The scan. Two passes on purpose: the cheap one reads the head of every
     * file and throws away everything that does not even spell the clause, so
     * a folder without sections - which is nearly every folder - never asks
     * the parser anything at all.
     *
     * <p>The second pass asks the parser and takes the text as the answer when
     * the parser has none. That is the whole of what a project index being off
     * costs the ordering, and it is deliberately not the whole of what it costs
     * the nesting: which links the parser confirmed is carried out of here, and
     * {@link Folder} builds the nesting from those alone.</p>
     */
    private Folder scan(IFolder folder) throws Exception {
        if (!folder.isAccessible()) {
            return null;
        }
        long started = System.nanoTime();
        int files = 0;
        Map<IFile, String> heads = new LinkedHashMap<>();
        Map<String, IFile> byName = new HashMap<>();
        for (IResource member : folder.members()) {
            if (member.getType() != IResource.FILE
                    || !SQL_EXTENSION.equalsIgnoreCase(member.getFileExtension())) {
                continue;
            }
            files++;
            IFile file = (IFile) member;
            byName.putIfAbsent(file.getName().toLowerCase(Locale.ROOT), file);
            String head = head(file);
            if (PartitionLinkReader.mentionsPartitionOf(head)) {
                heads.put(file, head);
            }
        }
        if (heads.isEmpty()) {
            return new Folder(Map.of(), Map.of(), Map.of(), Set.of());
        }

        PgDbParser parser = PgDbParser.getParser(folder);
        int answered = 0;
        Map<IPath, String> parentByFile = new HashMap<>();
        Map<String, List<IFile>> filesByParent = new HashMap<>();
        Set<IPath> confirmed = new HashSet<>();
        for (Map.Entry<IFile, String> entry : heads.entrySet()) {
            IFile file = entry.getKey();
            Set<ObjectLocation> objects = objectsOf(parser, file);
            if (!objects.isEmpty()) {
                answered++;
            }
            PartitionLinkReader.Link link =
                    PartitionLinkReader.linkOf(entry.getValue(), objects);
            if (link == null) {
                continue;
            }
            if (link.confirmed()) {
                confirmed.add(file.getFullPath());
            }
            parentByFile.put(file.getFullPath(), link.parent());
            filesByParent
                    .computeIfAbsent(
                            PartitionLinkReader.groupingKey(link.parent()),
                            k -> new ArrayList<>())
                    .add(file);
        }
        for (Map.Entry<String, List<IFile>> entry : filesByParent.entrySet()) {
            entry.setValue(List.copyOf(entry.getValue()));
        }
        Map<IFile, IFile> parentFiles =
                resolveParentFiles(parser, byName, filesByParent);
        Folder scanned = new Folder(Collections.unmodifiableMap(parentByFile),
                Collections.unmodifiableMap(filesByParent), parentFiles,
                confirmed);
        publish(folder, files, heads.size(), answered, confirmed.size(),
                parentByFile.size(), filesByParent.size(),
                scanned.nestedCount(), started);
        return scanned;
    }

    /**
     * One line per scanned folder that claims any section at all.
     *
     * <p>{@code answered} is the number this exists for. Everything the nesting
     * does is downstream of one question - whether the project index has
     * anything to say about these files - and a project whose index is switched
     * off, bypassed or missing the schema used to look exactly like a folder
     * with no sections in it: no error, no warning, no difference in the tree.
     * {@code candidates=2020 answered=0} names that case.</p>
     *
     * <p>{@code sections} can no longer name it, which is why {@code confirmed}
     * is here. The ordering now falls back to the text, so {@code sections}
     * counts links of both kinds and is high on exactly the project the line
     * was added to diagnose. The three numbers still separate the three states
     * they have to: {@code answered=0} is an index that says nothing at all,
     * {@code answered} high with {@code confirmed=0} is an index that answered
     * and refused every clause in the folder, and {@code confirmed} high with
     * {@code nested=0} is a folder in which no file was found defining those
     * tables - because they live elsewhere, or because the file that holds
     * them is not named after them.</p>
     *
     * <p>Deliberately not published for a folder with no candidates. That is
     * nearly every folder of a project, it has nothing to diagnose, and a
     * rolling log is a budget. This is a filter on the input - what the folder
     * holds - and not on the outcome, so the failing scan is exactly the one
     * that is always reported.</p>
     */
    private static void publish(IFolder folder, int files, int candidates,
            int answered, int confirmed, int sections, int families, int nested,
            long started) {
        PerformanceTelemetry.publish(new StringBuilder(200)
                .append("pgCodeKeeper partition index: folder=") //$NON-NLS-1$
                .append(folder.getFullPath())
                .append(" files=").append(files) //$NON-NLS-1$
                .append(" candidates=").append(candidates) //$NON-NLS-1$
                .append(" answered=").append(answered) //$NON-NLS-1$
                .append(" confirmed=").append(confirmed) //$NON-NLS-1$
                .append(" sections=").append(sections) //$NON-NLS-1$
                .append(" families=").append(families) //$NON-NLS-1$
                .append(" nested=").append(nested) //$NON-NLS-1$
                .append(" ms=") //$NON-NLS-1$
                .append(TimeUnit.NANOSECONDS.toMillis(
                        System.nanoTime() - started))
                .toString());
    }

    /**
     * Which of the folder's own files defines each parent table.
     *
     * <p>Asked once per distinct parent, not once per section: a folder of two
     * thousand sections has twenty parents. The name is only a way to shortlist
     * a candidate - what settles it is the parser saying that this file defines
     * that table. Nothing weaker would do. A section of {@code other.orders}
     * sitting in the folder of schema {@code tmp} would otherwise be nested
     * under {@code tmp/TABLE/orders.sql}, which is a different table; and a
     * file whose name the exporter had to sanitise is simply not found, which
     * costs the nesting and nothing else.</p>
     *
     * <p>Asked about every family, including one no member of which has a
     * confirmed link. Sorting those out here as well would put one rule in two
     * places, where a fault in either is hidden by the other; the answer for
     * such a family is dropped where the two nesting maps are built, which is
     * the one place that decides it. What that costs is a couple of index
     * lookups per family, against a folder every file of which was just looked
     * up anyway.</p>
     */
    private static Map<IFile, IFile> resolveParentFiles(PgDbParser parser,
            Map<String, IFile> byName,
            Map<String, List<IFile>> filesByParent) {
        if (filesByParent.isEmpty()) {
            return Map.of();
        }
        Map<IFile, IFile> resolved = new HashMap<>();
        for (Map.Entry<String, List<IFile>> family : filesByParent.entrySet()) {
            String key = family.getKey();
            IFile parentFile = null;
            for (String candidateName : candidateFileNames(key)) {
                IFile candidate = byName.get(candidateName);
                if (candidate == null || family.getValue().contains(candidate)) {
                    // a table is not a section of itself; a name that lands on
                    // one of its own sections is the wrong file
                    continue;
                }
                if (PartitionLinkReader.defines(objectsOf(parser, candidate), key)) {
                    parentFile = candidate;
                    break;
                }
            }
            if (parentFile != null) {
                for (IFile section : family.getValue()) {
                    resolved.put(section, parentFile);
                }
            }
        }
        return withoutCycles(resolved);
    }

    /**
     * The names a file defining {@code schema.table} may carry in one folder:
     * the bare one when the project is split by schema, the qualified one when
     * it is not. Both are probed because {@code structure.properties} decides
     * which, per project.
     */
    private static List<String> candidateFileNames(String groupingKey) {
        String qualified = groupingKey + '.' + SQL_EXTENSION;
        int dot = groupingKey.lastIndexOf('.');
        if (dot < 0) {
            return List.of(qualified);
        }
        return List.of(groupingKey.substring(dot + 1) + '.' + SQL_EXTENSION,
                qualified);
    }

    /**
     * Drops any section whose chain of parents comes back to itself. Sections
     * of sections are legal - PostgreSQL sub-partitions a table freely - so the
     * chain is walked rather than forbidden; a cycle is not legal and would
     * hang a viewer walking upwards, so it is removed rather than trusted.
     */
    private static Map<IFile, IFile> withoutCycles(Map<IFile, IFile> resolved) {
        for (IFile start : List.copyOf(resolved.keySet())) {
            Set<IFile> seen = new HashSet<>();
            IFile walk = start;
            while (walk != null && seen.add(walk)) {
                walk = resolved.get(walk);
            }
            if (walk != null) {
                resolved.remove(start);
            }
        }
        return resolved;
    }

    private static Set<ObjectLocation> objectsOf(PgDbParser parser, IFile file) {
        IPath location = file.getLocation();
        if (location == null) {
            return Set.of();
        }
        return parser.getObjsForPath(location.toOSString());
    }

    private static String head(IFile file) {
        IPath location = file.getLocation();
        if (location != null) {
            Path path = location.toFile().toPath();
            if (Files.isReadable(path)) {
                try (Reader reader = Files.newBufferedReader(path,
                        StandardCharsets.UTF_8)) {
                    return read(reader);
                } catch (IOException e) {
                    // an unreadable or non-UTF-8 file simply has no sections
                    return null;
                }
            }
        }
        try (InputStream stream = file.getContents(true);
                Reader reader = new InputStreamReader(stream,
                        StandardCharsets.UTF_8)) {
            return read(reader);
        } catch (Exception e) {
            return null;
        }
    }

    private static String read(Reader reader) throws IOException {
        char[] buffer = new char[PartitionLinkReader.HEAD_CHARS];
        int filled = 0;
        while (filled < buffer.length) {
            int got = reader.read(buffer, filled, buffer.length - filled);
            if (got < 0) {
                break;
            }
            filled += got;
        }
        // deliberately not stripping a byte order mark: whether the parser
        // counted it is not known here, and PartitionLinkReader matches the
        // parser's offset against the whole span of the clause, which absorbs
        // a shift of one either way
        return new String(buffer, 0, filled);
    }

    private void listen() {
        if (listening) {
            return;
        }
        synchronized (this) {
            if (listening) {
                return;
            }
            ResourcesPlugin.getWorkspace().addResourceChangeListener(
                    invalidator, IResourceChangeEvent.POST_CHANGE);
            PgDbParser.addProjectIndexPublicationListener(republished);
            listening = true;
        }
    }

    private void onResourceChange(IResourceChangeEvent event) {
        IResourceDelta delta = event.getDelta();
        if (delta == null) {
            return;
        }
        try {
            delta.accept((IResourceDeltaVisitor) visited -> {
                IResource resource = visited.getResource();
                if (resource.getType() == IResource.FILE) {
                    if (SQL_EXTENSION.equalsIgnoreCase(resource.getFileExtension())) {
                        invalidate(resource.getFullPath().removeLastSegments(1));
                    }
                    return false;
                }
                if (resource.getType() == IResource.PROJECT
                        && !resource.isAccessible()) {
                    forgetUnder(resource.getFullPath());
                    return false;
                }
                return true;
            });
        } catch (Exception e) {
            // a delta we could not walk means we do not know what moved
            clear();
        }
    }

    private void forgetUnder(IPath root) {
        folders.keySet().removeIf(root::isPrefixOf);
    }
}
