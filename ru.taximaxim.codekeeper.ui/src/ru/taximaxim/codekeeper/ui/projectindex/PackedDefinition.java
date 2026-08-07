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

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.pgcodekeeper.core.database.api.schema.ArgMode;
import org.pgcodekeeper.core.database.api.schema.IArgument;
import org.pgcodekeeper.core.database.api.schema.ICast.CastContext;
import org.pgcodekeeper.core.database.base.schema.Argument;
import org.pgcodekeeper.core.database.base.schema.meta.MetaCast;
import org.pgcodekeeper.core.database.base.schema.meta.MetaCompositeType;
import org.pgcodekeeper.core.database.base.schema.meta.MetaConstraint;
import org.pgcodekeeper.core.database.base.schema.meta.MetaFunction;
import org.pgcodekeeper.core.database.base.schema.meta.MetaOperator;
import org.pgcodekeeper.core.database.base.schema.meta.MetaRelation;
import org.pgcodekeeper.core.database.base.schema.meta.MetaStatement;

public record PackedDefinition(
        MetaKind kind,
        PackedLocation object,
        String bareName,
        String comment,
        List<PackedArgument> arguments,
        List<PackedArgument> orderBy,
        List<NameType> returnColumns,
        String returns,
        boolean setof,
        List<NameType> relationColumns,
        boolean relationColumnsKnown,
        List<NameType> compositeAttributes,
        boolean primaryKey,
        List<String> constraintColumns,
        String operatorLeft,
        String operatorRight,
        String operatorReturns,
        String castSource,
        String castTarget,
        CastContext castContext) {

    public static Comparator<PackedDefinition> canonicalOrder() {
        return ProjectIndexFormat.DEFINITION_ORDER;
    }

    public enum MetaKind {
        STATEMENT,
        FUNCTION,
        RELATION,
        COMPOSITE_TYPE,
        CONSTRAINT,
        OPERATOR,
        CAST
    }

    public record PackedArgument(
            ArgMode mode,
            String name,
            String dataType,
            String defaultExpression,
            boolean readOnly) {

        public PackedArgument {
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(dataType, "dataType");
        }

        static PackedArgument from(IArgument argument) {
            return new PackedArgument(argument.getMode(), argument.getName(), argument.getDataType(),
                    argument.getDefaultExpression(), argument.isReadOnly());
        }

        Argument toArgument() {
            var argument = new Argument(mode, name, dataType);
            argument.setDefaultExpression(defaultExpression);
            argument.setReadOnly(readOnly);
            return argument;
        }
    }

    public record NameType(String name, String type) {
        public NameType {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
        }
    }

    public PackedDefinition {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(object, "object");
        Objects.requireNonNull(comment, "comment");
        arguments = List.copyOf(arguments);
        orderBy = List.copyOf(orderBy);
        returnColumns = List.copyOf(returnColumns);
        relationColumns = List.copyOf(relationColumns);
        compositeAttributes = List.copyOf(compositeAttributes);
        constraintColumns = List.copyOf(constraintColumns);
        validateShape(kind, bareName, arguments, orderBy, returnColumns, returns, setof,
                relationColumns, relationColumnsKnown, compositeAttributes, primaryKey,
                constraintColumns, operatorLeft, operatorRight, operatorReturns,
                castSource, castTarget, castContext);
    }

    public static PackedDefinition from(MetaStatement statement, IndexPathRef path) {
        Objects.requireNonNull(statement, "statement");
        PackedLocation object = PackedLocation.from(statement.getObject(), path);
        String comment = Objects.requireNonNullElse(statement.getComment(), "");
        if (statement.getClass() == MetaStatement.class) {
            return empty(MetaKind.STATEMENT, object, null, comment);
        }
        if (statement instanceof MetaFunction function) {
            List<PackedArgument> arguments = function.getArguments().stream()
                    .map(PackedArgument::from).toList();
            List<PackedArgument> orderBy = function.getOrderBy().stream()
                    .map(PackedArgument::from).toList();
            List<NameType> returnColumns = function.getReturnsColumns().entrySet().stream()
                    .map(entry -> new NameType(entry.getKey(), entry.getValue())).toList();
            return new PackedDefinition(MetaKind.FUNCTION, object, function.getBareName(), comment,
                    arguments, orderBy, returnColumns, function.getReturns(), function.isSetof(),
                    List.of(), false, List.of(), false, List.of(), null, null, null, null, null, null);
        }
        if (statement instanceof MetaRelation relation) {
            var columnStream = relation.getRelationColumns();
            boolean columnsKnown = columnStream != null;
            List<NameType> columns = columnsKnown
                    ? columnStream.map(pair -> new NameType(pair.getFirst(), pair.getSecond())).toList()
                    : List.of();
            return new PackedDefinition(MetaKind.RELATION, object, null, comment,
                    List.of(), List.of(), List.of(), null, false, columns, columnsKnown, List.of(), false,
                    List.of(), null, null, null, null, null, null);
        }
        if (statement instanceof MetaCompositeType composite) {
            List<NameType> attributes = composite.getAttrs().stream()
                    .map(pair -> new NameType(pair.getFirst(), pair.getSecond())).toList();
            return new PackedDefinition(MetaKind.COMPOSITE_TYPE, object, null, comment,
                    List.of(), List.of(), List.of(), null, false, List.of(), false, attributes, false,
                    List.of(), null, null, null, null, null, null);
        }
        if (statement instanceof MetaConstraint constraint) {
            List<String> columns = constraint.getColumns().stream().sorted().toList();
            return new PackedDefinition(MetaKind.CONSTRAINT, object, null, comment,
                    List.of(), List.of(), List.of(), null, false, List.of(), false, List.of(),
                    constraint.isPrimaryKey(), columns, null, null, null, null, null, null);
        }
        if (statement instanceof MetaOperator operator) {
            return new PackedDefinition(MetaKind.OPERATOR, object, null, comment,
                    List.of(), List.of(), List.of(), null, false, List.of(), false, List.of(), false,
                    List.of(), operator.getLeftArg(), operator.getRightArg(), operator.getReturns(),
                    null, null, null);
        }
        if (statement instanceof MetaCast cast) {
            return new PackedDefinition(MetaKind.CAST, object, null, comment,
                    List.of(), List.of(), List.of(), null, false, List.of(), false, List.of(), false,
                    List.of(), null, null, null, cast.getSource(), cast.getTarget(), cast.getContext());
        }
        throw new IllegalArgumentException("Unsupported metadata class: " + statement.getClass().getName());
    }

    public MetaStatement toMetaStatement(ProjectIndexPathResolver resolver) {
        var location = object.toObjectLocation(resolver);
        MetaStatement statement = switch (kind) {
            case STATEMENT -> new MetaStatement(location);
            case FUNCTION -> {
                var function = new MetaFunction(location, bareName);
                arguments.stream().map(PackedArgument::toArgument).forEach(function::addArgument);
                orderBy.stream().map(PackedArgument::toArgument).forEach(function::addOrderBy);
                returnColumns.forEach(column -> function.addReturnsColumn(column.name(), column.type()));
                function.setReturns(returns);
                function.setSetof(setof);
                yield function;
            }
            case RELATION -> {
                var relation = new MetaRelation(location);
                if (relationColumnsKnown) {
                    relation.addColumns(relationColumns.stream()
                            .map(column -> new org.pgcodekeeper.core.utils.Pair<>(column.name(), column.type()))
                            .toList());
                }
                yield relation;
            }
            case COMPOSITE_TYPE -> {
                var composite = new MetaCompositeType(location);
                compositeAttributes.forEach(attribute -> composite.addAttr(attribute.name(), attribute.type()));
                yield composite;
            }
            case CONSTRAINT -> {
                var constraint = new MetaConstraint(location);
                constraint.setPrimaryKey(primaryKey);
                constraintColumns.forEach(constraint::addColumn);
                yield constraint;
            }
            case OPERATOR -> {
                var operator = new MetaOperator(location);
                operator.setLeftArg(operatorLeft);
                operator.setRightArg(operatorRight);
                operator.setReturns(operatorReturns);
                yield operator;
            }
            case CAST -> new MetaCast(castSource, castTarget, castContext, location);
        };
        statement.setComment(comment);
        return statement;
    }

    public PackedDefinition withObject(PackedLocation replacement) {
        return copy(replacement, returns);
    }

    public PackedDefinition withReturns(String replacement) {
        return copy(object, replacement);
    }

    String canonicalForm() {
        return kind + "|" + object.canonicalForm() + "|" + bareName + "|" + comment + "|"
                + arguments + "|" + orderBy + "|" + returnColumns + "|" + returns + "|"
                + setof + "|" + relationColumns + "|" + relationColumnsKnown + "|"
                + compositeAttributes + "|" + primaryKey
                + "|" + constraintColumns + "|" + operatorLeft + "|" + operatorRight + "|"
                + operatorReturns + "|" + castSource + "|" + castTarget + "|" + castContext;
    }

    private PackedDefinition copy(PackedLocation replacementObject, String replacementReturns) {
        return new PackedDefinition(kind, replacementObject, bareName, comment, arguments, orderBy,
                returnColumns, replacementReturns, setof, relationColumns, relationColumnsKnown,
                compositeAttributes,
                primaryKey, constraintColumns, operatorLeft, operatorRight, operatorReturns,
                castSource, castTarget, castContext);
    }

    private static PackedDefinition empty(MetaKind kind, PackedLocation object, String bareName,
            String comment) {
        return new PackedDefinition(kind, object, bareName, comment, List.of(), List.of(), List.of(),
                null, false, List.of(), false, List.of(), false, List.of(), null, null, null,
                null, null, null);
    }

    private static void validateShape(MetaKind kind, String bareName,
            List<PackedArgument> arguments, List<PackedArgument> orderBy,
            List<NameType> returnColumns, String returns, boolean setof,
            List<NameType> relationColumns, boolean relationColumnsKnown,
            List<NameType> compositeAttributes, boolean primaryKey,
            List<String> constraintColumns, String operatorLeft, String operatorRight,
            String operatorReturns, String castSource, String castTarget,
            CastContext castContext) {
        switch (kind) {
            case STATEMENT -> {
                requireNoFunctionFields(bareName, arguments, orderBy, returnColumns, returns, setof);
                requireNoRelationFields(relationColumns, relationColumnsKnown);
                requireNoCompositeFields(compositeAttributes);
                requireNoConstraintFields(primaryKey, constraintColumns);
                requireNoOperatorFields(operatorLeft, operatorRight, operatorReturns);
                requireNoCastFields(castSource, castTarget, castContext);
            }
            case FUNCTION -> {
                requirePresent("bareName", bareName);
                requireNoRelationFields(relationColumns, relationColumnsKnown);
                requireNoCompositeFields(compositeAttributes);
                requireNoConstraintFields(primaryKey, constraintColumns);
                requireNoOperatorFields(operatorLeft, operatorRight, operatorReturns);
                requireNoCastFields(castSource, castTarget, castContext);
            }
            case RELATION -> {
                requireNoFunctionFields(bareName, arguments, orderBy, returnColumns, returns, setof);
                if (!relationColumnsKnown && !relationColumns.isEmpty()) {
                    throw invalid("relationColumns", kind);
                }
                requireNoCompositeFields(compositeAttributes);
                requireNoConstraintFields(primaryKey, constraintColumns);
                requireNoOperatorFields(operatorLeft, operatorRight, operatorReturns);
                requireNoCastFields(castSource, castTarget, castContext);
            }
            case COMPOSITE_TYPE -> {
                requireNoFunctionFields(bareName, arguments, orderBy, returnColumns, returns, setof);
                requireNoRelationFields(relationColumns, relationColumnsKnown);
                requireNoConstraintFields(primaryKey, constraintColumns);
                requireNoOperatorFields(operatorLeft, operatorRight, operatorReturns);
                requireNoCastFields(castSource, castTarget, castContext);
            }
            case CONSTRAINT -> {
                requireNoFunctionFields(bareName, arguments, orderBy, returnColumns, returns, setof);
                requireNoRelationFields(relationColumns, relationColumnsKnown);
                requireNoCompositeFields(compositeAttributes);
                requireNoOperatorFields(operatorLeft, operatorRight, operatorReturns);
                requireNoCastFields(castSource, castTarget, castContext);
            }
            case OPERATOR -> {
                requireNoFunctionFields(bareName, arguments, orderBy, returnColumns, returns, setof);
                requireNoRelationFields(relationColumns, relationColumnsKnown);
                requireNoCompositeFields(compositeAttributes);
                requireNoConstraintFields(primaryKey, constraintColumns);
                if (operatorLeft == null && operatorRight == null) {
                    throw new IllegalArgumentException(
                            "OPERATOR requires operatorLeft or operatorRight");
                }
                requireNoCastFields(castSource, castTarget, castContext);
            }
            case CAST -> {
                requireNoFunctionFields(bareName, arguments, orderBy, returnColumns, returns, setof);
                requireNoRelationFields(relationColumns, relationColumnsKnown);
                requireNoCompositeFields(compositeAttributes);
                requireNoConstraintFields(primaryKey, constraintColumns);
                requireNoOperatorFields(operatorLeft, operatorRight, operatorReturns);
                requirePresent("castSource", castSource);
                requirePresent("castTarget", castTarget);
                if (castContext == null) {
                    throw invalid("castContext", kind);
                }
            }
        }
    }

    private static void requireNoFunctionFields(String bareName, List<PackedArgument> arguments,
            List<PackedArgument> orderBy, List<NameType> returnColumns, String returns,
            boolean setof) {
        requireNull("bareName", bareName);
        requireEmpty("arguments", arguments);
        requireEmpty("orderBy", orderBy);
        requireEmpty("returnColumns", returnColumns);
        requireNull("returns", returns);
        requireFalse("setof", setof);
    }

    private static void requireNoRelationFields(List<NameType> columns, boolean known) {
        requireEmpty("relationColumns", columns);
        requireFalse("relationColumnsKnown", known);
    }

    private static void requireNoCompositeFields(List<NameType> attributes) {
        requireEmpty("compositeAttributes", attributes);
    }

    private static void requireNoConstraintFields(boolean primaryKey, List<String> columns) {
        requireFalse("primaryKey", primaryKey);
        requireEmpty("constraintColumns", columns);
    }

    private static void requireNoOperatorFields(String left, String right, String returns) {
        requireNull("operatorLeft", left);
        requireNull("operatorRight", right);
        requireNull("operatorReturns", returns);
    }

    private static void requireNoCastFields(String source, String target, CastContext context) {
        requireNull("castSource", source);
        requireNull("castTarget", target);
        if (context != null) {
            throw new IllegalArgumentException("Unexpected castContext");
        }
    }

    private static void requirePresent(String field, String value) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    private static void requireNull(String field, Object value) {
        if (value != null) {
            throw new IllegalArgumentException("Unexpected " + field);
        }
    }

    private static void requireEmpty(String field, List<?> value) {
        if (!value.isEmpty()) {
            throw new IllegalArgumentException("Unexpected " + field);
        }
    }

    private static void requireFalse(String field, boolean value) {
        if (value) {
            throw new IllegalArgumentException("Unexpected " + field);
        }
    }

    private static IllegalArgumentException invalid(String field, MetaKind kind) {
        return new IllegalArgumentException("Invalid " + field + " for " + kind);
    }
}
