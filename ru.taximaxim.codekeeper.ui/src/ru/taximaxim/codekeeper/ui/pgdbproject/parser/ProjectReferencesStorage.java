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

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.base.schema.meta.MetaStatement;

class ProjectReferencesStorage implements Serializable, ProjectReferenceIndex {

    private static final long serialVersionUID = 2641369996771272168L;

    private Map<String, List<MetaStatement>> objDefinitions = new ConcurrentHashMap<>();
    private Map<String, Set<ObjectLocation>> objReferences = new ConcurrentHashMap<>();
    private boolean frozen;

    Set<ObjectLocation> getObjReferencesForPath(String pathToFile) {
        return objReferences.getOrDefault(pathToFile, Collections.emptySet());
    }

    @Override
    public Set<ObjectLocation> referencesForPath(String path) {
        return getObjReferencesForPath(path);
    }

    List<MetaStatement> getObjDefinitionsForPath(String pathToFile) {
        return objDefinitions.getOrDefault(pathToFile, Collections.emptyList());
    }

    @Override
    public List<MetaStatement> definitionsForPath(String path) {
        return getObjDefinitionsForPath(path);
    }

    Stream<MetaStatement> getAllObjDefinitions() {
        return objDefinitions.values().stream().flatMap(List<MetaStatement>::stream);
    }

    @Override
    public Stream<MetaStatement> allDefinitions() {
        return getAllObjDefinitions();
    }

    Stream<ObjectLocation> getAllObjReferences() {
        return objReferences.values().stream().flatMap(Set<ObjectLocation>::stream);
    }

    @Override
    public Stream<ObjectLocation> allReferences() {
        return getAllObjReferences();
    }

    @Override
    public List<MetaStatement> definitionsMatching(ObjectLocation object) {
        return getAllObjDefinitions()
                .filter(statement -> statement.getObject().compare(object))
                .toList();
    }

    @Override
    public List<ObjectLocation> referencesMatching(ObjectLocation object) {
        return getAllObjReferences().filter(object::compare).toList();
    }

    /**
     * {@inheritDoc}
     * <p>
     * This storage could answer a shorter text - it holds its definitions in a
     * map and reads them all anyway. It refuses one all the same, because a
     * project with an index cannot, and completion that offers more objects
     * whenever the index happens to be off is the same defect seen from the
     * other side.
     */
    @Override
    public List<MetaStatement> completionCandidates(String text) {
        String upper = text.toUpperCase(java.util.Locale.ROOT);
        if (upper.length() < MIN_COMPLETION_PREFIX_LENGTH) {
            return List.of();
        }
        return getAllObjDefinitions()
                .filter(statement -> statement.getName()
                        .toUpperCase(java.util.Locale.ROOT).contains(upper))
                .toList();
    }

    Map<String, List<MetaStatement>> getObjDefinitions() {
        return objDefinitions;
    }

    Map<String, Set<ObjectLocation>> getObjReferences() {
        return objReferences;
    }

    synchronized void clear() {
        requireMutable();
        objDefinitions.clear();
        objReferences.clear();
    }

    synchronized void putReferences(Map<String, List<MetaStatement>> objDefinitions,
            Map<String, Set<ObjectLocation>> objReferences) {
        requireMutable();
        this.objDefinitions.putAll(objDefinitions);
        this.objReferences.putAll(objReferences);
    }

    ProjectReferencesStorage copy() {
        var copy = new ProjectReferencesStorage();
        copy.putReferences(objDefinitions, objReferences);
        return copy;
    }

    synchronized ProjectReferencesStorage freeze() {
        if (!frozen) {
            Map<String, List<MetaStatement>> immutableDefinitions =
                    new LinkedHashMap<>(objDefinitions.size());
            objDefinitions.forEach((path, definitions) ->
                    immutableDefinitions.put(path, List.copyOf(definitions)));
            Map<String, Set<ObjectLocation>> immutableReferences =
                    new LinkedHashMap<>(objReferences.size());
            objReferences.forEach((path, references) ->
                    immutableReferences.put(path, Collections.unmodifiableSet(
                            new LinkedHashSet<>(references))));
            objDefinitions = Collections.unmodifiableMap(immutableDefinitions);
            objReferences = Collections.unmodifiableMap(immutableReferences);
            frozen = true;
        }
        return this;
    }

    synchronized ProjectReferencesStorage frozenCopyReplacing(String path,
            List<MetaStatement> definitions,
            Set<ObjectLocation> references) {
        return frozenCopyReplacing(
                Map.of(path, definitions),
                Map.of(path, references));
    }

    synchronized ProjectReferencesStorage frozenCopyReplacing(
            Map<String, List<MetaStatement>> definitions,
            Map<String, Set<ObjectLocation>> references) {
        if (!frozen) {
            throw new IllegalStateException(
                    "Project reference storage is not frozen"); //$NON-NLS-1$
        }
        if (!definitions.keySet().equals(references.keySet())) {
            throw new IllegalArgumentException(
                    "Replacement definition and reference paths differ"); //$NON-NLS-1$
        }
        var copy = new ProjectReferencesStorage();
        Map<String, List<MetaStatement>> copiedDefinitions =
                new LinkedHashMap<>(objDefinitions);
        Map<String, Set<ObjectLocation>> copiedReferences =
                new LinkedHashMap<>(objReferences);
        definitions.forEach((path, values) ->
                copiedDefinitions.put(path, List.copyOf(values)));
        references.forEach((path, values) ->
                copiedReferences.put(path, Collections.unmodifiableSet(
                        new LinkedHashSet<>(values))));
        copy.objDefinitions =
                Collections.unmodifiableMap(copiedDefinitions);
        copy.objReferences =
                Collections.unmodifiableMap(copiedReferences);
        copy.frozen = true;
        return copy;
    }

    @Override
    public ProjectReferencesStorage mutableCopy() {
        return copy();
    }

    synchronized void remove(String path) {
        requireMutable();
        objReferences.remove(path);
        objDefinitions.remove(path);
    }

    private void requireMutable() {
        if (frozen) {
            throw new IllegalStateException(
                    "Project reference storage is frozen"); //$NON-NLS-1$
        }
    }
}
