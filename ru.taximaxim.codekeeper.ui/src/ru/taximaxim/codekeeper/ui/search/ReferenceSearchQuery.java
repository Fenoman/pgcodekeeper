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
package ru.taximaxim.codekeeper.ui.search;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.search.ui.ISearchQuery;
import org.eclipse.search.ui.ISearchResult;
import org.eclipse.search.ui.text.Match;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;

import ru.taximaxim.codekeeper.ui.Log;
import ru.taximaxim.codekeeper.ui.pgdbproject.parser.PgDbParser;

public class ReferenceSearchQuery implements ISearchQuery {

    private static final int GROUPING_CANCEL_CHECK_MASK = 1024 - 1;

    private final ReferenceSearchResult result;

    private final ObjectLocation ref;
    private final PgDbParser parser;

    public ReferenceSearchQuery(ObjectLocation ref, IProject proj) {
        this.ref = ref;
        this.parser = PgDbParser.getParser(proj);
        this.result = new ReferenceSearchResult(this);
    }

    @Override
    public IStatus run(IProgressMonitor monitor) {
        ReferenceSearchResult res = (ReferenceSearchResult) getSearchResult();
        res.removeAll();

        SubMonitor sub = SubMonitor.convert(monitor, 1);
        boolean completed = collectMatches(parser.getReferencesForObj(ref),
                location -> true, sub::isCanceled,
                res::addMatch,
                path -> Files.newBufferedReader(Paths.get(path)),
                ReferenceSearchQuery::logReadFailure);
        if (!completed) {
            return Status.CANCEL_STATUS;
        }
        sub.worked(1);
        sub.done();

        return Status.OK_STATUS;
    }

    static boolean collectMatches(List<ObjectLocation> locations,
            BooleanSupplier cancelled, Consumer<Match> visitor,
            ReaderOpener readerOpener,
            BiConsumer<String, Exception> failureHandler) {
        return collectMatches(locations.stream(), location -> true, cancelled,
                visitor, readerOpener, failureHandler);
    }

    static boolean collectMatches(Stream<ObjectLocation> locations,
            Predicate<ObjectLocation> filter, BooleanSupplier cancelled,
            Consumer<Match> visitor, ReaderOpener readerOpener,
            BiConsumer<String, Exception> failureHandler) {
        Map<String, List<IndexedLocation>> locationsByFile =
                new LinkedHashMap<>();
        int sourceCount = 0;
        int matchCount = 0;
        try (locations) {
            if (cancelled.getAsBoolean()) {
                return false;
            }
            Iterator<ObjectLocation> iterator = locations.iterator();
            while (true) {
                if ((sourceCount & GROUPING_CANCEL_CHECK_MASK) == 0
                        && cancelled.getAsBoolean()) {
                    return false;
                }
                if (!iterator.hasNext()) {
                    break;
                }
                ObjectLocation location = iterator.next();
                sourceCount++;
                if (!filter.test(location)) {
                    continue;
                }
                locationsByFile.computeIfAbsent(location.getFilePath(),
                        key -> new ArrayList<>()).add(
                                new IndexedLocation(matchCount++, location));
            }
        }
        if (cancelled.getAsBoolean()) {
            return false;
        }

        ResolvedMatches resolved = new ResolvedMatches(matchCount);
        for (var entry : locationsByFile.entrySet()) {
            if (cancelled.getAsBoolean()) {
                return false;
            }
            List<IndexedLocation> fileLocations = entry.getValue();
            fileLocations.sort(Comparator
                    .comparingInt((IndexedLocation indexed) ->
                            indexed.location().getLineNumber())
                    .thenComparingInt(indexed ->
                            indexed.location().getOffset()));

            BufferedReader reader;
            try {
                reader = readerOpener.open(entry.getKey());
            } catch (IOException | InvalidPathException ex) {
                failureHandler.accept(entry.getKey(), ex);
                if (!resolved.skipAll(fileLocations, visitor, cancelled)) {
                    return false;
                }
                continue;
            }

            boolean fileCompleted = true;
            try (reader) {
                int currentLineNumber = 0;
                String currentLine = null;
                fileScan:
                for (int i = 0; i < fileLocations.size(); i++) {
                    if (cancelled.getAsBoolean()) {
                        fileCompleted = false;
                        break;
                    }
                    IndexedLocation indexed = fileLocations.get(i);
                    int requestedLine = indexed.location().getLineNumber();
                    if (requestedLine < 1) {
                        resolved.skip(indexed);
                        if (!resolved.flush(visitor, cancelled)) {
                            fileCompleted = false;
                            break;
                        }
                        continue;
                    }
                    while (currentLineNumber < requestedLine) {
                        if (cancelled.getAsBoolean()) {
                            fileCompleted = false;
                            break fileScan;
                        }
                        currentLine = reader.readLine();
                        if (cancelled.getAsBoolean()) {
                            fileCompleted = false;
                            break fileScan;
                        }
                        if (currentLine == null) {
                            fileCompleted = resolved.skipAll(
                                    fileLocations.subList(i,
                                            fileLocations.size()),
                                    visitor, cancelled);
                            break fileScan;
                        }
                        currentLineNumber++;
                    }
                    resolved.resolve(indexed,
                            getLocationCopy(indexed.location(), currentLine));
                    if (!resolved.flush(visitor, cancelled)) {
                        fileCompleted = false;
                        break;
                    }
                }
            } catch (IOException ex) {
                failureHandler.accept(entry.getKey(), ex);
                fileCompleted = resolved.skipAll(fileLocations, visitor,
                        cancelled);
            }
            if (!fileCompleted || cancelled.getAsBoolean()) {
                return false;
            }
        }
        return !cancelled.getAsBoolean();
    }

    private static ObjectLocation getLocationCopy(ObjectLocation loc, String sql) {
        return new ObjectLocation.Builder()
                .setOffset(loc.getOffset())
                .setLineNumber(loc.getLineNumber())
                .setCharPositionInLine(loc.getCharPositionInLine())
                .setFilePath(loc.getFilePath())
                .setSql(sql)
                .build();
    }

    static String readFailureMessage(String filePath) {
        return "Unable to read reference source file: " + filePath; //$NON-NLS-1$
    }

    private static void logReadFailure(String filePath, Exception ex) {
        Log.log(Log.LOG_ERROR, readFailureMessage(filePath), ex);
    }

    @FunctionalInterface
    interface ReaderOpener {

        BufferedReader open(String path) throws IOException;
    }

    private record IndexedLocation(int index, ObjectLocation location) {
    }

    private static final class ResolvedMatches {

        private final Match[] matches;
        private final boolean[] completed;
        private int next;

        private ResolvedMatches(int size) {
            matches = new Match[size];
            completed = new boolean[size];
        }

        private void resolve(IndexedLocation indexed, ObjectLocation location) {
            ObjectLocation source = indexed.location();
            matches[indexed.index()] = new Match(location, source.getOffset(),
                    source.getObjLength());
            completed[indexed.index()] = true;
        }

        private void skip(IndexedLocation indexed) {
            completed[indexed.index()] = true;
        }

        private boolean skipAll(List<IndexedLocation> indexedLocations,
                Consumer<Match> visitor, BooleanSupplier cancelled) {
            for (IndexedLocation indexed : indexedLocations) {
                skip(indexed);
                if (!flush(visitor, cancelled)) {
                    return false;
                }
            }
            return true;
        }

        private boolean flush(Consumer<Match> visitor,
                BooleanSupplier cancelled) {
            while (next < completed.length && completed[next]) {
                if (cancelled.getAsBoolean()) {
                    return false;
                }
                Match match = matches[next];
                matches[next] = null;
                if (match != null) {
                    visitor.accept(match);
                    if (cancelled.getAsBoolean()) {
                        return false;
                    }
                }
                next++;
            }
            return !cancelled.getAsBoolean();
        }
    }

    public ObjectLocation getReference() {
        return ref;
    }

    @Override
    public String getLabel() {
        return "Searching for '" + ref.getObjectReference().getFullName() + '\''; //$NON-NLS-1$
    }

    @Override
    public boolean canRerun() {
        return true;
    }

    @Override
    public boolean canRunInBackground() {
        return true;
    }

    @Override
    public ISearchResult getSearchResult() {
        return result;
    }
}
