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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.ICast;
import org.pgcodekeeper.core.database.api.schema.ICast.CastContext;
import org.pgcodekeeper.core.database.api.schema.ICompositeType;
import org.pgcodekeeper.core.database.api.schema.IConstraintPk;
import org.pgcodekeeper.core.database.api.schema.IFunction;
import org.pgcodekeeper.core.database.api.schema.IOperator;
import org.pgcodekeeper.core.database.api.schema.IRelation;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.api.schema.meta.IMetaContainer;
import org.pgcodekeeper.core.database.base.schema.meta.MetaStatement;

import ru.taximaxim.codekeeper.ui.pgdbproject.parser.IncrementalProjectReferenceIndex.AnalysisLease;
import ru.taximaxim.codekeeper.ui.projectindex.IndexPathRef;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexDefinitionSubject;
import ru.taximaxim.codekeeper.ui.projectindex.ProjectIndexStringProbes;
import ru.taximaxim.codekeeper.ui.projectindex.ReferenceMatchKey.MatchFamily;

/**
 * Per-analysis metadata view over the changed file, persistent project index
 * and PostgreSQL system metadata.
 */
final class IncrementalMetaContainer
        implements IMetaContainer, AutoCloseable {

    private static final MatchFamily[] FAMILIES = MatchFamily.values();
    private static final DbObjType[] EXACT_TYPES = DbObjType.values();
    private static final int FAMILY_SLOTS =
            FAMILIES.length + EXACT_TYPES.length;

    /**
     * What a subject left unbound, which decides how much of the index has to
     * answer it.
     *
     * <p>Read off the subject's own components, not off the caller's
     * intention: {@code loadImplicitCast} spells a cast's simple name into
     * the schema component and leaves the object component empty, so a cast
     * counts as {@link #SCHEMA} here even though it names one object. The
     * per-family split reports that lookup under {@code EXACT.CAST} and lets
     * a reader subtract it.
     */
    private enum SubjectShape {
        /** Both components bound: one named object in one schema. */
        NAMED,
        /** Only the schema bound: every object of the family it holds. */
        SCHEMA,
        /** Neither bound: every object of the family in every schema. */
        INDEX
    }

    private static final SubjectShape[] SHAPES = SubjectShape.values();

    /**
     * The distinct subjects an analysis asked about, in the order
     * {@link #currentAskedCounts()} produces them.
     */
    private static final String[] ASKED_LABELS = {
            "relation", "routine", "routine_name", "routine_schema", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "operator", "operator_schema", "type", "primary_key", "cast" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    };

    private final Object metadataLock = new Object();
    private final Object relationsLock = new Object();
    private final AnalysisLease packed;
    private final boolean ownsPackedLease;
    private final IndexPathRef excludedChangedPath;
    private final ReentrantReadWriteLock lifecycle =
            new ReentrantReadWriteLock();
    private final ConcurrentMap<LookupKey, Optional<IRelation>> relations =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<LookupKey, Optional<IFunction>> functions =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<LookupKey, Optional<IOperator>> operators =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<LookupKey, Optional<ICompositeType>> types =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<SchemaKey, List<IFunction>> availableFunctions =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<SchemaKey, List<IOperator>> availableOperators =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<LookupKey, List<IConstraintPk>> primaryKeys =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<CastKey, Boolean> implicitCasts =
            new ConcurrentHashMap<>();

    private Supplier<? extends IMetaContainer> metadataSupplier;
    private volatile IMetaContainer localAndSystem;
    private volatile Map<String, Map<String, IRelation>> allRelations;
    private final AtomicLong packedLookups = new AtomicLong();
    private final AtomicLong packedNanos = new AtomicLong();
    private final AtomicLong packedStatements = new AtomicLong();
    private final SubjectCounters byShape =
            new SubjectCounters(SHAPES.length);
    private final SubjectCounters byFamily =
            new SubjectCounters(FAMILY_SLOTS);
    private final long blockTraversalsAtStart;
    private final ProjectIndexStringProbes stringProbesAtStart;
    private volatile long blockTraversalsAtClose = -1L;
    private volatile ProjectIndexStringProbes stringProbesAtClose;
    private volatile int[] askedAtClose;
    private boolean open = true;

    IncrementalMetaContainer(
            Supplier<? extends IMetaContainer> metadataSupplier,
            AnalysisLease packed, IndexPathRef excludedChangedPath) {
        this(metadataSupplier, packed, excludedChangedPath, true);
    }

    static IncrementalMetaContainer borrowed(
            Supplier<? extends IMetaContainer> metadataSupplier,
            AnalysisLease packed, IndexPathRef excludedChangedPath) {
        return new IncrementalMetaContainer(
                metadataSupplier, packed, excludedChangedPath, false);
    }

    private IncrementalMetaContainer(
            Supplier<? extends IMetaContainer> metadataSupplier,
            AnalysisLease packed, IndexPathRef excludedChangedPath,
            boolean ownsPackedLease) {
        this.metadataSupplier =
                Objects.requireNonNull(metadataSupplier, "metadataSupplier");
        this.packed = Objects.requireNonNull(packed, "packed");
        this.excludedChangedPath = excludedChangedPath;
        this.ownsPackedLease = ownsPackedLease;
        this.blockTraversalsAtStart = packed.definitionBlockTraversals();
        this.stringProbesAtStart = packed.stringProbes();
    }

    @Override
    public IRelation findRelation(String schemaName, String relationName) {
        return read(() -> relations.computeIfAbsent(
                new LookupKey(schemaName, relationName),
                this::loadRelation).orElse(null));
    }

    @Override
    public Collection<IFunction> availableFunctions(String schemaName) {
        return read(() -> availableFunctions.computeIfAbsent(
                new SchemaKey(schemaName), this::loadFunctions));
    }

    @Override
    public Collection<IOperator> availableOperators(String schemaName) {
        return read(() -> availableOperators.computeIfAbsent(
                new SchemaKey(schemaName), this::loadOperators));
    }

    @Override
    public ICompositeType findType(String schemaName, String typeName) {
        return read(() -> types.computeIfAbsent(
                new LookupKey(schemaName, typeName),
                this::loadType).orElse(null));
    }

    @Override
    public IFunction findFunction(String schemaName, String functionName) {
        return read(() -> functions.computeIfAbsent(
                new LookupKey(schemaName, functionName),
                this::loadFunction).orElse(null));
    }

    @Override
    public IOperator findOperator(String schemaName, String operatorName) {
        return read(() -> operators.computeIfAbsent(
                new LookupKey(schemaName, operatorName),
                key -> availableOperators(key.schema()).stream()
                        .filter(operator -> key.name().equals(
                                operator.getName()))
                        .findFirst()).orElse(null));
    }

    @Override
    public Collection<IConstraintPk> getPrimaryKeys(
            String schemaName, String tableName) {
        return read(() -> primaryKeys.computeIfAbsent(
                new LookupKey(schemaName, tableName),
                this::loadPrimaryKeys));
    }

    @Override
    public boolean containsCastImplicit(String source, String target) {
        return read(() -> implicitCasts.computeIfAbsent(
                new CastKey(source, target), this::loadImplicitCast));
    }

    @Override
    public Map<String, Map<String, IRelation>> getRelations() {
        return read(this::loadRelations);
    }

    @Override
    public void close() {
        var write = lifecycle.writeLock();
        write.lock();
        try {
            if (!open) {
                return;
            }
            open = false;
            // The summary is read off the memo maps and off a lease that
            // closing is about to take away, so it is frozen first.
            askedAtClose = currentAskedCounts();
            blockTraversalsAtClose =
                    packed.definitionBlockTraversals() - blockTraversalsAtStart;
            stringProbesAtClose =
                    packed.stringProbes().since(stringProbesAtStart);
            if (ownsPackedLease) {
                packed.close();
            }
            relations.clear();
            functions.clear();
            operators.clear();
            types.clear();
            availableFunctions.clear();
            availableOperators.clear();
            primaryKeys.clear();
            implicitCasts.clear();
            allRelations = null;
            localAndSystem = null;
            metadataSupplier = null;
        } finally {
            write.unlock();
        }
    }

    private Optional<IRelation> loadRelation(LookupKey key) {
        IRelation local =
                metadata().findRelation(key.schema(), key.name());
        if (local != null) {
            return Optional.of(local);
        }
        return packed(MatchFamily.RELATION, null,
                key.schema(), key.name()).stream()
                        .filter(IRelation.class::isInstance)
                        .map(IRelation.class::cast)
                        .findFirst();
    }

    private List<IFunction> loadFunctions(SchemaKey key) {
        Map<String, IFunction> merged = new LinkedHashMap<>();
        metadata().availableFunctions(key.schema()).forEach(
                function -> merged.put(function.getName(), function));
        packed(MatchFamily.ROUTINE, null,
                key.schema(), null).stream()
                        .filter(IFunction.class::isInstance)
                        .map(IFunction.class::cast)
                        .forEach(function -> merged.putIfAbsent(
                                function.getName(), function));
        return baseFileOrderedValues(merged);
    }

    private List<IOperator> loadOperators(SchemaKey key) {
        Map<String, IOperator> merged = new LinkedHashMap<>();
        metadata().availableOperators(key.schema()).forEach(
                operator -> merged.put(operator.getName(), operator));
        packed(MatchFamily.EXACT, DbObjType.OPERATOR,
                key.schema(), null).stream()
                        .filter(IOperator.class::isInstance)
                        .map(IOperator.class::cast)
                        .forEach(operator -> merged.putIfAbsent(
                                operator.getName(), operator));
        return baseFileOrderedValues(merged);
    }

    private Optional<ICompositeType> loadType(LookupKey key) {
        ICompositeType local =
                metadata().findType(key.schema(), key.name());
        if (local != null) {
            return Optional.of(local);
        }
        return packed(MatchFamily.TYPE, null,
                key.schema(), key.name()).stream()
                        .filter(ICompositeType.class::isInstance)
                        .map(ICompositeType.class::cast)
                        .findFirst();
    }

    private Optional<IFunction> loadFunction(LookupKey key) {
        IFunction local =
                metadata().findFunction(key.schema(), key.name());
        if (local != null) {
            return Optional.of(local);
        }
        return packed(MatchFamily.ROUTINE, null,
                key.schema(), key.name()).stream()
                        .filter(IFunction.class::isInstance)
                        .map(IFunction.class::cast)
                        .filter(function -> key.name().equals(
                                function.getName()))
                        .findFirst();
    }

    private List<IConstraintPk> loadPrimaryKeys(LookupKey key) {
        Map<String, IConstraintPk> merged = new LinkedHashMap<>();
        metadata().getPrimaryKeys(key.schema(), key.name()).forEach(
                primaryKey -> merged.put(primaryKey.getName(), primaryKey));
        packed(MatchFamily.EXACT, DbObjType.CONSTRAINT,
                key.schema(), key.name()).stream()
                        .filter(IConstraintPk.class::isInstance)
                        .map(IConstraintPk.class::cast)
                        .filter(IConstraintPk::isPrimaryKey)
                        .forEach(primaryKey -> merged.putIfAbsent(
                                primaryKey.getName(), primaryKey));
        return baseFileOrderedValues(merged);
    }

    private boolean loadImplicitCast(CastKey key) {
        if (metadata().containsCastImplicit(key.source(), key.target())) {
            return true;
        }
        String name = ICast.getSimpleName(key.source(), key.target());
        return packed(MatchFamily.EXACT, DbObjType.CAST,
                name, null).stream()
                        .filter(ICast.class::isInstance)
                        .map(ICast.class::cast)
                        .anyMatch(cast -> cast.getContext() == CastContext.IMPLICIT
                                && key.source().equals(cast.getSource())
                                && key.target().equals(cast.getTarget()));
    }

    private Map<String, Map<String, IRelation>> loadRelations() {
        Map<String, Map<String, IRelation>> result = allRelations;
        if (result != null) {
            return result;
        }
        synchronized (relationsLock) {
            result = allRelations;
            if (result == null) {
                Map<String, Map<String, IRelation>> merged =
                        new LinkedHashMap<>();
                packed(MatchFamily.RELATION, null, null, null).stream()
                        .filter(IRelation.class::isInstance)
                        .map(IRelation.class::cast)
                        .forEach(relation -> merged
                                .computeIfAbsent(
                                        relation.getSchemaName(),
                                        ignored -> new LinkedHashMap<>())
                                .put(relation.getName(), relation));
                metadata().getRelations().forEach((schema, schemaRelations) ->
                        merged.computeIfAbsent(schema,
                                ignored -> new LinkedHashMap<>())
                                .putAll(schemaRelations));
                result = freezeRelations(merged);
                allRelations = result;
            }
        }
        return result;
    }

    private List<MetaStatement> packed(MatchFamily family,
            DbObjType exactType, String schema, String objectName) {
        long started = System.nanoTime();
        List<MetaStatement> found = packed.definitions(
                new ProjectIndexDefinitionSubject(
                        family, exactType, schema, objectName),
                excludedChangedPath);
        long elapsed = System.nanoTime() - started;
        int size = found.size();
        packedNanos.addAndGet(elapsed);
        packedLookups.incrementAndGet();
        packedStatements.addAndGet(size);
        byShape.record(shape(schema, objectName).ordinal(), size, elapsed);
        byFamily.record(familySlot(family, exactType), size, elapsed);
        return found;
    }

    private static SubjectShape shape(String schema, String objectName) {
        if (objectName != null) {
            return SubjectShape.NAMED;
        }
        return schema == null ? SubjectShape.INDEX : SubjectShape.SCHEMA;
    }

    private static int familySlot(MatchFamily family, DbObjType exactType) {
        return exactType == null
                ? family.ordinal()
                : FAMILIES.length + exactType.ordinal();
    }

    private static String familyLabel(int slot) {
        return slot < FAMILIES.length
                ? FAMILIES[slot].name()
                : "EXACT." + EXACT_TYPES[slot - FAMILIES.length].name(); //$NON-NLS-1$
    }

    /**
     * How much of an analysis was spent asking the packed index, and how much
     * the index handed back for it.
     *
     * <p>A changed file resolves its references against everything else, and
     * everything else lives in the index rather than in memory. Each miss in
     * the local metadata becomes one lookup here, so a file with many
     * references pays many of them. The plugin reports the analysis as a single
     * number; this says how much of it was the index answering.
     *
     * <p>The totals are then split three ways. Each split covers the same
     * lookups from a different angle, so numbers taken from two of them must
     * not be added together:
     * <ul>
     * <li>by subject shape - {@code index_named}, {@code index_schema}
     * and {@code index_wholeindex}, each {@code lookups/statements/ms}. A
     * schema-shaped subject makes the index answer with every object of its
     * family in that schema, whatever the caller then keeps;</li>
     * <li>by match family, under {@code index_by_family}, in the same triple.
     * This is what says whether the statements were routines;</li>
     * <li>{@code index_asked}, the distinct subjects the analysis asked this
     * container about, read off the memo maps rather than counted in the hot
     * path. {@code routine} counts distinct signatures, {@code routine_name}
     * counts the bare names behind them and {@code routine_schema} counts the
     * schemas whose routines were lifted whole.</li>
     * </ul>
     *
     * <p>{@code index_block_traversals} is what the index walked, not what it
     * returned: a block is decoded whole, so the two differ by the decoding's
     * own inflation. It is the whole index's counter minus a reading taken
     * when this container was built, so a concurrent lookup on the same index
     * would be counted here too.
     *
     * <p>{@code index_string_probes}, {@code probes/records/ms}, is a
     * <b>third and independent cut of the same lookups</b>, and not a fourth
     * slot of either split above. It must not be added to anything: it does
     * not partition the lookups, it prices a step every one of them takes.
     * A lookup names its subject in characters while the index is keyed by
     * dictionary identifiers, so before it can read a definition it searches
     * the dictionary - a binary search whose every step asks for one entry. A
     * named subject pays two such searches, a schema-shaped one pays one, and
     * a subject that is not in the dictionary pays its search in full and then
     * returns nothing, which is why a family whose statements are zero can
     * still hold milliseconds. The identifiers inside the definitions a lookup
     * did return are resolved the same way and counted here too. Like the
     * blocks walked, it is a difference of two readings of an index-wide
     * counter.
     *
     * <p>The middle number is records <b>decoded</b>, not entries reached: a
     * dictionary block is decoded whole and kept, so the probes that land in
     * one after the first add nothing to it. Probes rising while records stay
     * flat is the shape of an analysis whose dictionary is already resident,
     * and the two must not be read as one ratio.
     *
     * <p>These are counts, not a verdict. Dividing statements by the distinct
     * names asked shows how much wider an answer was than its question;
     * dividing the string milliseconds into the total says whether the answer
     * or the question was the expensive half. Only a measurement on a real
     * project can say whether narrowing either would pay.
     *
     * @return the totals, the per-shape and per-family splits, the blocks
     *         walked, the string probes, and the distinct subjects asked
     */
    public String packedLookupSummary() {
        int[] asked = askedCounts();
        var askedText = new StringJoiner(" ", "[", "]"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        for (int i = 0; i < ASKED_LABELS.length; i++) {
            askedText.add(ASKED_LABELS[i] + '=' + asked[i]);
        }
        var familyText = new StringJoiner(", ", "[", "]"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        for (int slot = 0; slot < FAMILY_SLOTS; slot++) {
            if (byFamily.used(slot)) {
                familyText.add(familyLabel(slot) + ' ' + byFamily.triple(slot));
            }
        }
        return "index_lookups=" + packedLookups.get() //$NON-NLS-1$
                + " index_statements=" + packedStatements.get() //$NON-NLS-1$
                + " index_lookup_ms=" //$NON-NLS-1$
                + (packedNanos.get() / 1_000_000L)
                + " index_block_traversals=" + blockTraversals() //$NON-NLS-1$
                + " index_string_probes=" + stringProbes() //$NON-NLS-1$
                + " index_named=" //$NON-NLS-1$
                + byShape.triple(SubjectShape.NAMED.ordinal())
                + " index_schema=" //$NON-NLS-1$
                + byShape.triple(SubjectShape.SCHEMA.ordinal())
                + " index_wholeindex=" //$NON-NLS-1$
                + byShape.triple(SubjectShape.INDEX.ordinal())
                + " index_by_family=" + familyText //$NON-NLS-1$
                + " index_asked=" + askedText; //$NON-NLS-1$
    }

    private long blockTraversals() {
        long closed = blockTraversalsAtClose;
        return closed >= 0
                ? closed
                : packed.definitionBlockTraversals() - blockTraversalsAtStart;
    }

    /**
     * The string probes this container's lookups paid for, as
     * {@code probes/records/ms}, read live while it is open and off the
     * closing snapshot afterwards.
     */
    private String stringProbes() {
        ProjectIndexStringProbes closed = stringProbesAtClose;
        ProjectIndexStringProbes spent = closed != null
                ? closed
                : packed.stringProbes().since(stringProbesAtStart);
        return spent.probes() + "/" + spent.records() //$NON-NLS-1$
                + "/" + spent.nanos() / 1_000_000L; //$NON-NLS-1$
    }

    private int[] askedCounts() {
        int[] frozen = askedAtClose;
        return frozen != null ? frozen : currentAskedCounts();
    }

    private int[] currentAskedCounts() {
        return new int[] {
                relations.size(), functions.size(), distinctRoutineNames(),
                availableFunctions.size(), operators.size(),
                availableOperators.size(), types.size(),
                primaryKeys.size(), implicitCasts.size(),
        };
    }

    /**
     * How many distinct bare routine names the analysis asked about, as
     * opposed to how many signatures. A file that calls one overloaded
     * function under three argument lists asks three signatures and one name,
     * and it is the name that a schema-wide lift would have to cover.
     */
    private int distinctRoutineNames() {
        Set<String> names = new HashSet<>();
        for (LookupKey key : functions.keySet()) {
            names.add(key.schema() + '.' + bareName(key.name()));
        }
        return names.size();
    }

    private static String bareName(String signature) {
        int arguments = signature.indexOf('(');
        return arguments < 0 ? signature : signature.substring(0, arguments);
    }

    /**
     * Lookups, the statements they returned and the time they took, kept per
     * slot of some classification of the subject. Three arrays rather than one
     * array of triples: the recording happens on the analysis hot path, and an
     * array of longs costs an atomic add per number and no allocation at all.
     */
    private static final class SubjectCounters {

        private final AtomicLongArray lookups;
        private final AtomicLongArray statements;
        private final AtomicLongArray nanos;

        private SubjectCounters(int slots) {
            lookups = new AtomicLongArray(slots);
            statements = new AtomicLongArray(slots);
            nanos = new AtomicLongArray(slots);
        }

        private void record(int slot, int found, long elapsedNanos) {
            lookups.incrementAndGet(slot);
            statements.addAndGet(slot, found);
            nanos.addAndGet(slot, elapsedNanos);
        }

        private boolean used(int slot) {
            return lookups.get(slot) != 0;
        }

        private String triple(int slot) {
            return lookups.get(slot) + "/" + statements.get(slot) //$NON-NLS-1$
                    + "/" + nanos.get(slot) / 1_000_000L; //$NON-NLS-1$
        }
    }

    private IMetaContainer metadata() {
        IMetaContainer result = localAndSystem;
        if (result != null) {
            return result;
        }
        synchronized (metadataLock) {
            result = localAndSystem;
            if (result == null) {
                result = Objects.requireNonNull(
                        metadataSupplier.get(),
                        "metadataSupplier result");
                localAndSystem = result;
                metadataSupplier = null;
            }
        }
        return result;
    }

    private <T> T read(Supplier<T> operation) {
        var read = lifecycle.readLock();
        read.lock();
        try {
            if (!open) {
                throw new IllegalStateException(
                        "Incremental metadata container is closed");
            }
            return operation.get();
        } finally {
            read.unlock();
        }
    }

    /**
     * Replays the candidate order a full rebuild would produce instead of
     * ordering by object name. The full-build {@link
     * org.pgcodekeeper.core.database.base.schema.meta.MetaContainer} keeps
     * project statements in load order and appends system metadata last, and
     * the packed index stores its definitions in file and offset order. The
     * core analyzer resolves an ambiguous call with
     * {@code Collections.max}, which keeps the first best-ranked candidate, so
     * a name-derived order could resolve two tie-ranked overloads differently
     * from a full rebuild. Statements without a source location are system
     * metadata and keep their original relative order at the end.
     */
    private static <T> List<T> baseFileOrderedValues(Map<String, T> values) {
        List<Ordered<T>> ordered = new ArrayList<>(values.size());
        int sequence = 0;
        for (T value : values.values()) {
            ordered.add(new Ordered<>(value, location(value), sequence++));
        }
        ordered.sort(IncrementalMetaContainer::compareBaseFileOrder);
        return ordered.stream().map(Ordered::value).toList();
    }

    private static int compareBaseFileOrder(Ordered<?> left, Ordered<?> right) {
        ObjectLocation leftLocation = left.location();
        ObjectLocation rightLocation = right.location();
        if (leftLocation == null || rightLocation == null) {
            if (leftLocation != null) {
                return -1;
            }
            if (rightLocation != null) {
                return 1;
            }
            return Integer.compare(left.sequence(), right.sequence());
        }
        int comparison = leftLocation.getFilePath()
                .compareTo(rightLocation.getFilePath());
        if (comparison == 0) {
            comparison = Integer.compare(
                    leftLocation.getOffset(), rightLocation.getOffset());
        }
        return comparison == 0
                ? Integer.compare(left.sequence(), right.sequence())
                : comparison;
    }

    private static ObjectLocation location(Object value) {
        if (!(value instanceof MetaStatement statement)) {
            return null;
        }
        ObjectLocation object = statement.getObject();
        return object == null || object.getFilePath() == null ? null : object;
    }

    private record Ordered<T>(T value, ObjectLocation location, int sequence) {
    }

    private static Map<String, Map<String, IRelation>> freezeRelations(
            Map<String, Map<String, IRelation>> relations) {
        Map<String, Map<String, IRelation>> result =
                new LinkedHashMap<>();
        relations.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    Map<String, IRelation> schema =
                            new LinkedHashMap<>();
                    entry.getValue().entrySet().stream()
                            .sorted(Map.Entry.comparingByKey())
                            .forEach(relation -> schema.put(
                                    relation.getKey(),
                                    relation.getValue()));
                    result.put(entry.getKey(),
                            Collections.unmodifiableMap(schema));
                });
        return Collections.unmodifiableMap(result);
    }

    private record LookupKey(String schema, String name) {
        private LookupKey {
            Objects.requireNonNull(schema, "schema");
            Objects.requireNonNull(name, "name");
        }
    }

    private record SchemaKey(String schema) {
        private SchemaKey {
            Objects.requireNonNull(schema, "schema");
        }
    }

    private record CastKey(String source, String target) {
        private CastKey {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(target, "target");
        }
    }
}
