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
package ru.taximaxim.codekeeper.ui.sqledit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.contentassist.ContentAssistEvent;
import org.eclipse.jface.text.contentassist.ContentAssistant;
import org.eclipse.jface.text.contentassist.ContextInformation;
import org.eclipse.jface.text.contentassist.ICompletionListener;
import org.eclipse.jface.text.contentassist.ICompletionListenerExtension;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension2;
import org.eclipse.jface.text.contentassist.IContentAssistProcessor;
import org.eclipse.jface.text.contentassist.IContextInformation;
import org.eclipse.jface.text.contentassist.IContextInformationValidator;
import org.eclipse.swt.graphics.Image;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation.LocationType;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.base.schema.meta.MetaRelation;
import org.pgcodekeeper.core.database.base.schema.meta.MetaStatement;
import org.pgcodekeeper.core.database.pg.utils.PgDiffUtils;
import org.pgcodekeeper.core.database.pg.utils.PgKeyword;
import org.pgcodekeeper.core.utils.Pair;

import ru.taximaxim.codekeeper.ui.Activator;
import ru.taximaxim.codekeeper.ui.DatabaseType;
import ru.taximaxim.codekeeper.ui.Log;
import ru.taximaxim.codekeeper.ui.PerformanceTelemetry;
import ru.taximaxim.codekeeper.ui.copiedclasses.CompletionProposal;
import ru.taximaxim.codekeeper.ui.localizations.Messages;
import ru.taximaxim.codekeeper.ui.pgdbproject.parser.PgDbParser;

public class SQLEditorCompletionProcessor implements IContentAssistProcessor {

    private static final char DELIMITER = '.';

    private static final Set<DbObjType> VALID_TYPES = Set.of(DbObjType.SCHEMA, DbObjType.TABLE, DbObjType.VIEW,
            DbObjType.FUNCTION, DbObjType.PROCEDURE, DbObjType.AGGREGATE, DbObjType.DICTIONARY, DbObjType.TYPE);

    protected final SQLEditor editor;
    private final ContentAssistant assistant;
    private final List<String> keywords;

    private final String tmplMsg;
    private final String keyMsg;

    private int repetition= -1;

    public SQLEditorCompletionProcessor(ContentAssistant assistant, SQLEditor editor,
            String hotKey) {
        this.editor = editor;
        this.assistant = assistant;

        keywords = PgKeyword.getKeywords().keySet().stream()
                .sorted()
                .map(s -> s.toUpperCase(Locale.ROOT))
                .toList();

        tmplMsg = Messages.SQLEditorCompletionProcessor_show_templates.formatted(hotKey);
        keyMsg = Messages.SQLEditorCompletionProcessor_show_keywords.formatted(hotKey);

        assistant.addCompletionListener(new CompletionListener());
    }

    @Override
    public ICompletionProposal[] computeCompletionProposals(ITextViewer viewer, int offset) {
        String part;
        try {
            part = viewer.getDocument().get(0, offset);
        } catch (BadLocationException ex) {
            Log.log(Log.LOG_ERROR, Messages.SQLEditorCompletionProcessor_offset_error, ex);
            return null;
        }

        List<String> splittedText = getCursorText(part, offset);

        ICompletionProposal[] res;
        if (repetition % 2 == 0) {
            res = getKeys(offset, splittedText, part);
            assistant.setStatusMessage(tmplMsg);
        } else {
            res = getTmpls(viewer, offset, splittedText.get(0));
            assistant.setStatusMessage(keyMsg);
        }

        repetition++;

        return res;
    }

    /**
     * Reads the qualified name the caret sits in, backwards from the caret.
     *
     * @param part   document text up to the caret
     * @param offset caret position
     * @return upper cased name segments, the one being typed first, empty
     *         string for the segment behind a trailing dot
     */
    static List<String> getCursorText(String part, int offset) {
        List<String> result = new ArrayList<>();
        int cursor = offset - 1;
        int last = offset;
        while (cursor > 0) {
            char currentChar = part.charAt(cursor);
            if (DELIMITER == currentChar) {
                result.add(part.substring(cursor + 1, last).toUpperCase(Locale.ROOT));
                last = cursor;
            } else if (!PgDiffUtils.isValidIdChar(currentChar)) {
                break;
            }
            cursor--;
        }

        result.add(part.substring(0 != cursor ? cursor + 1 : cursor, last).toUpperCase(Locale.ROOT));
        return result;
    }

    private ICompletionProposal[] getTmpls(ITextViewer viewer, int offset, String text) {
        if (text.isEmpty()) {
            return new SQLEditorTemplateAssistProcessor().getAllTemplates(viewer, offset)
                    .toArray(new ICompletionProposal[0]);
        }

        var templates = new SQLEditorTemplateAssistProcessor().computeCompletionProposals(viewer, offset);
        return templates != null ? templates : new ICompletionProposal[0];
    }

    private ICompletionProposal[] getKeys(int offset, List<String> splittedText, String part) {
        var parser = editor.getParser();
        var input = editor.getEditorInput();
        boolean ownBuffer = editor.parsesOnlyItsOwnBuffer()
                && DatabaseType.PG == editor.getDbType();
        Completion completion = getKeys(offset, splittedText,
                new CompletionSource() {

                    @Override
                    public Stream<MetaStatement> completionCandidates(String text) {
                        return parser.getCompletionCandidates(text);
                    }

                    @Override
                    public Set<ObjectLocation> editorReferences() {
                        return parser.getObjsForEditor(input);
                    }

                    @Override
                    public List<ObjectLocation> bufferBindings() {
                        return ownBuffer ? EditorStatementBindings.of(part) : List.of();
                    }

                    @Override
                    public Stream<MetaStatement> definitionsFor(ObjectLocation object) {
                        return parser.getDefinitionsForObj(object);
                    }
                }, keywords);
        PerformanceTelemetry.publish(completion.telemetry());
        List<ICompletionProposal> proposals = completion.proposals();
        return proposals.toArray(new ICompletionProposal[proposals.size()]);
    }

    /**
     * Builds the proposals for one completion request.
     * <p>
     * Objects are offered only from {@link PgDbParser#MIN_COMPLETION_PREFIX_LENGTH}
     * characters on. Below that the project is not asked at all, and that is
     * the whole of the answer: an index cannot search its names by less than a
     * trigram, and the text behind a trailing dot is empty by construction, so
     * without the boundary every {@code alias.} would ask the project for a
     * search it could only answer by decoding itself entire. Nothing is lost
     * that could have been answered - a shorter text reaches the objects whose
     * whole name is that short and no others - but the boundary does cost
     * searching by a single letter, which a map-backed storage allowed.
     *
     * @param splittedText name segments as read by {@link #getCursorText}
     * @param offset       caret position
     * @param source       what the project can be asked
     * @param keywords     keywords to offer, upper cased
     * @return proposals in the order they are to be shown, and what finding
     *         them cost
     */
    static Completion getKeys(int offset, List<String> splittedText,
            CompletionSource source, List<String> keywords) {
        long started = System.nanoTime();
        List<ICompletionProposal> result = new ArrayList<>();
        int size = splittedText.size();
        String secondName = size > 1 ? splittedText.get(1) : null;
        String lastName = splittedText.get(0);
        int examined = 0;

        if (size < 3 && lastName.length() >= PgDbParser.MIN_COMPLETION_PREFIX_LENGTH) {
            List<MetaStatement> candidates = source.completionCandidates(lastName).toList();
            examined = candidates.size();
            candidates.stream()
            .filter(d -> checkDefinition(d, lastName, secondName))
            .sorted(ProposalComparator.INSTANCE)
            .map(obj -> getProposal(offset, lastName, obj))
            .forEach(result::add);
        }
        int objects = result.size();

        var relations = new AskedRelations(
                (schema, relation) -> relationColumns(source, schema, relation));
        AliasSource aliasSource = AliasSource.NONE;
        if (size > 1) {
            // Only a two segment name is answered by an alias, and reading the
            // buffer is the one thing here that parses anything.
            List<ObjectLocation> buffered = 2 == size ? source.bufferBindings() : List.of();
            Resolved found = resolveColumns(splittedText, offset,
                    references(source.editorReferences(), buffered), relations);
            found.columns().forEach(column -> result.add(getColumnProposal(offset, lastName, column)));
            aliasSource = sourceOf(found.binding(), buffered);
        }
        int columns = result.size() - objects;

        // Keywords
        if (1 == size) {
            if (lastName.isEmpty()) {
                keywords.forEach(k -> result.add(new SqlEditorKeywordProposal(k, offset, 0, k.length())));
            } else {
                List<ICompletionProposal> partResult = new ArrayList<>();
                for (String keyword : keywords) {
                    int location = keyword.indexOf(lastName);
                    if (location != -1) {
                        CompletionProposal proposal = new SqlEditorKeywordProposal(keyword,
                                offset - lastName.length(), lastName.length(), keyword.length());
                        if (location == 0) {
                            result.add(proposal);
                        } else {
                            partResult.add(proposal);
                        }
                    }
                }

                result.addAll(partResult);
            }
        }

        return new Completion(result, summary(lastName.length(), size, examined,
                objects, relations.wasAsked(), aliasSource, columns,
                (System.nanoTime() - started) / 1_000_000L));
    }

    /**
     * The records an alias may be resolved through: what the project recorded
     * for this file, and what the statement under the caret declares. Both are
     * read as one list, so the binding closest above the caret wins across
     * them, and neither is copied when the other is empty - the project's
     * answer is the whole of a saved file and the usual case is that the
     * buffer adds nothing to it.
     */
    private static Collection<ObjectLocation> references(
            Collection<ObjectLocation> recorded, List<ObjectLocation> buffered) {
        if (buffered.isEmpty()) {
            return recorded;
        }
        if (recorded.isEmpty()) {
            return buffered;
        }

        List<ObjectLocation> both = new ArrayList<>(recorded.size() + buffered.size());
        both.addAll(recorded);
        both.addAll(buffered);
        return both;
    }

    /**
     * Which of the two collections the binding that answered came from. The
     * union is built out of these very objects, so identity is what tells them
     * apart, and nothing else could: a binding read from the buffer and one
     * recorded for a saved file describe the same alias in the same words.
     */
    private static AliasSource sourceOf(ObjectLocation binding, List<ObjectLocation> buffered) {
        if (binding == null) {
            return AliasSource.NONE;
        }

        for (ObjectLocation record : buffered) {
            if (record == binding) {
                return AliasSource.BUFFER;
            }
        }

        return AliasSource.INDEX;
    }

    /**
     * The line one completion request leaves behind.
     * <p>
     * Written because this path had no telemetry at all, which is why what a
     * dot cost had to be measured from outside the workbench, and why the
     * twenty seconds reported from use and the forty measured on the index
     * could not be reconciled. The prefix length is here because it is what
     * decides whether the project is asked; the examined count because it is
     * the size of the answer the index gave, which the number of proposals
     * cannot show; the two proposal counts separately because objects and
     * columns are found by different means and only one of them was ever slow.
     * Keywords are not counted - they are a list this class holds in memory.
     * <p>
     * Whether a relation was asked for at all is here because the count of
     * columns cannot say it. A dot that offers no columns offered none either
     * because the name behind it resolved to no relation or because the
     * relation it resolved to answered none, and the two are a different
     * defect entirely: the first was diagnosed by running the completion by
     * hand against the reported project, for want of this one field.
     *
     * @param asked whether the columns of a named relation were asked for,
     *              which is as far as a name behind a dot ever gets on its own
     * @param alias where the record that bound the alias came from, which the
     *              other fields cannot say: an alias answered out of the
     *              project and one answered out of the editor buffer reach the
     *              same relation by two different routes, and only one of them
     *              works in a file no project owns
     */
    private static String summary(int prefixLength, int segments, int examined,
            int objects, boolean asked, AliasSource alias, int columns, long millis) {
        return "pgCodeKeeper completion: prefix_length=" + prefixLength //$NON-NLS-1$
                + " name_segments=" + segments //$NON-NLS-1$
                + " objects_examined=" + examined //$NON-NLS-1$
                + " objects_proposed=" + objects //$NON-NLS-1$
                + " relation_asked=" + asked //$NON-NLS-1$
                + " alias_source=" + alias //$NON-NLS-1$
                + " columns_proposed=" + columns //$NON-NLS-1$
                + " ms=" + millis; //$NON-NLS-1$
    }

    /**
     * Picks the columns to offer once the caret stands behind a dot.
     * <p>
     * A three segment name - {@code schema.relation.column} - names its relation
     * outright. A two segment one - {@code alias.column} - does not, so the
     * alias is looked up among the bindings the analyzer left in this file: the
     * FROM clause that declared it, and every {@code alias.column} it managed to
     * resolve. The nearest such binding above the caret wins, because one
     * routine body may bind the same alias to a different relation in every
     * statement it holds, and the binding written above the caret is the one the
     * caret is most likely inside of.
     * <p>
     * The bindings the project recorded are read from the file as it lies on
     * disk, not as it stands in the editor, and a top level statement leaves
     * it none at all. Both gaps are filled by the caller rather than here: the
     * statement under the caret is analyzed out of the buffer and its bindings
     * arrive in the same collection. What is passed in is all this knows.
     * <p>
     * Two more cases stay silent, both because of what the project definitions
     * hold rather than because of anything decided here. A view offers nothing:
     * its columns are worked out by the analysis, and the definitions are built
     * from the parsed model, which has none of them yet. And a relation reached
     * through an unqualified {@code FROM} offers nothing either: with no schema
     * written down, the reference is recorded against the system schema rather
     * than the one the object is declared in, and no relation is found under
     * that name.
     *
     * @param splittedText name segments as read by {@link #getCursorText}
     * @param offset       caret position
     * @param references   references recorded for the edited file, and the
     *                     bindings of the statement under the caret
     * @param relations    lookup of relation columns by schema and relation name
     * @return columns to offer, those starting with the typed text first, empty
     *         if the relation is unknown
     */
    static List<Pair<String, String>> getColumns(List<String> splittedText, int offset,
            Collection<ObjectLocation> references, RelationColumns relations) {
        return resolveColumns(splittedText, offset, references, relations).columns();
    }

    /**
     * {@link #getColumns} keeping the record that answered, which the columns
     * cannot show: the same columns are reached whether the alias was bound by
     * the project or by the statement being written, and the telemetry is
     * about which of the two happened.
     */
    private static Resolved resolveColumns(List<String> splittedText, int offset,
            Collection<ObjectLocation> references, RelationColumns relations) {
        int size = splittedText.size();
        List<Pair<String, String>> columns;
        ObjectLocation binding = null;
        if (3 == size) {
            columns = relations.get(splittedText.get(2), splittedText.get(1));
        } else if (2 == size) {
            binding = findAliasBinding(splittedText.get(1), offset, references);
            ObjectReference relation = binding == null ? null : binding.getObjectReference();
            columns = relation == null ? List.of()
                    : relations.get(relation.schema(), relation.table());
        } else {
            return new Resolved(List.of(), null);
        }

        return new Resolved(filterColumns(columns, splittedText.get(0)), binding);
    }

    /**
     * Finds the relation an alias stands for, taking the binding closest above
     * the caret.
     * <p>
     * The record kind is what makes a bare name an alias, and it may not be
     * dropped from the condition: a record that was given no alias answers with
     * the name of its own object instead, so any kind would match those and
     * offer the columns of every relation the file merely mentions.
     * <p>
     * An alias reaches two kinds of record, and both bind it. Its declaration
     * in a FROM clause is written down as a variable, at the alias itself; each
     * later {@code alias.column} the analyzer resolves is written down as a
     * local reference. Reading the declaration is what answers an alias that
     * the saved file uses nowhere below it - which is every alias of a routine
     * still being written.
     * <p>
     * The variable kind is narrowed to a relation, because that kind is not
     * about aliases by name. The three places that write one - the FROM element
     * of each dialect - all name the relation the alias stands for, and a
     * variable that names anything else has no columns to offer. It is not the
     * declared variables of a routine that the narrowing keeps out: those leave
     * no record here at all, measured on an analyzed {@code DECLARE}, which the
     * completion tests pin.
     * <p>
     * A statement written straight into the editor rather than into a routine
     * body leaves the analyzer no record of either kind, so nothing of it is
     * ever found here. Such a statement is instead analyzed out of the buffer
     * by {@link EditorStatementBindings}, and its bindings are handed in among
     * the references - in the same two kinds, at their offsets in the
     * document, so that everything below reads them alike.
     *
     * @param alias      upper cased alias to resolve
     * @param offset     caret position
     * @param references references recorded for the edited file, and those the
     *                   statement under the caret declares
     * @return the record that bound the alias or null when it is unknown
     */
    private static ObjectLocation findAliasBinding(String alias, int offset,
            Collection<ObjectLocation> references) {
        ObjectLocation nearest = null;
        for (ObjectLocation reference : references) {
            if (bindsAnAlias(reference)
                    && reference.getOffset() < offset
                    && alias.equalsIgnoreCase(reference.getBareName())
                    && (nearest == null || reference.getOffset() > nearest.getOffset())) {
                nearest = reference;
            }
        }

        return nearest;
    }

    /**
     * Tells a record that binds an alias to a relation from every other record
     * the analyzer left in the file. Shared with
     * {@link EditorStatementBindings}, which keeps nothing else out of what it
     * reads from the buffer: the two must answer the same question, or the
     * buffer would offer records this cannot read.
     */
    static boolean bindsAnAlias(ObjectLocation reference) {
        LocationType type = reference.getLocationType();
        return LocationType.LOCAL_REF == type
                || (LocationType.VARIABLE == type
                        && DbObjType.TABLE == reference.getType());
    }

    /**
     * Keeps the columns whose name holds the typed text, offering those that
     * start with it before those that merely contain it, as the keyword
     * completion does. Columns keep the order of the relation otherwise.
     */
    private static List<Pair<String, String>> filterColumns(
            List<Pair<String, String>> columns, String text) {
        if (text.isEmpty()) {
            return columns;
        }

        List<Pair<String, String>> starting = new ArrayList<>();
        List<Pair<String, String>> containing = new ArrayList<>();
        for (Pair<String, String> column : columns) {
            int location = column.getFirst().toUpperCase(Locale.ROOT).indexOf(text);
            if (location == 0) {
                starting.add(column);
            } else if (location > 0) {
                containing.add(column);
            }
        }

        starting.addAll(containing);
        return starting;
    }

    /**
     * Asks the project for one relation and reads its columns.
     * <p>
     * The project is asked by name rather than read through: an index answers a
     * named object from its key, and reading it through means decoding all of
     * it - which is what the columns behind a dot used to cost, on top of the
     * search the empty name behind that same dot started.
     * <p>
     * A name is asked for in more than one spelling because an index is keyed
     * by the exact text of a name while the segments read out of the editor
     * arrive upper cased, and a project written in the usual lower case would
     * answer none of them. The spellings tried are the one given and its lower
     * case: a relation written in mixed case and quoted - {@code "Users"} -
     * still answers behind its own alias, where the name comes from what the
     * analyzer resolved rather than from what was typed, but not when written
     * out in full. The search that used to compare every name ignoring case
     * found it either way.
     *
     * @param source   what the project can be asked
     * @param schema   schema name; null offers nothing, since a name that does
     *                 not say its schema cannot be looked up by one
     * @param relation relation name
     * @return columns with their types, empty when no such relation is known
     */
    static List<Pair<String, String>> relationColumns(CompletionSource source,
            String schema, String relation) {
        if (schema == null) {
            return List.of();
        }

        for (String schemaName : spellings(schema)) {
            for (String relationName : spellings(relation)) {
                List<Pair<String, String>> columns = findRelationColumns(
                        source.definitionsFor(relationQuery(schemaName, relationName)),
                        schemaName, relationName);
                if (!columns.isEmpty()) {
                    return columns;
                }
            }
        }

        return List.of();
    }

    /**
     * The spellings of a name worth asking for: the one given, and its lower
     * case when that differs. An unquoted identifier reaches the project in
     * lower case however it was typed, so this is the spelling a typed name
     * most often has to be turned into.
     */
    private static List<String> spellings(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.equals(name) ? List.of(name) : List.of(name, lower);
    }

    /**
     * The question put to the index for one relation: a plain reference to it,
     * carrying no alias, because that is the shape a definition of it is
     * recorded and keyed under. The type is the relation family - a view and a
     * sequence answer a table's question.
     */
    private static ObjectLocation relationQuery(String schema, String relation) {
        return new ObjectLocation.Builder()
                .setReference(new ObjectReference(schema, relation, DbObjType.TABLE))
                .setLocationType(LocationType.REFERENCE)
                .build();
    }

    /**
     * Reads the columns of a relation out of the definitions offered for it.
     * <p>
     * The null check is not defensive: a view carries no column stream at all
     * until the analysis works its columns out, and a project definition is
     * built from the parsed model, where that has not happened. Dropping the
     * check turns every completion behind a view into a null dereference, and
     * a content assistant swallows nothing but a bad location.
     *
     * @param definitions definitions to search, the whole project
     * @param schema      schema name, matched ignoring case; null matches any
     * @param relation    relation name, matched ignoring case
     * @return columns with their types, empty when no such relation is known
     */
    static List<Pair<String, String>> findRelationColumns(Stream<MetaStatement> definitions,
            String schema, String relation) {
        return definitions
                .filter(MetaRelation.class::isInstance)
                .map(MetaRelation.class::cast)
                .filter(rel -> relation.equalsIgnoreCase(rel.getName())
                        && (schema == null || schema.equalsIgnoreCase(rel.getSchemaName())))
                .map(MetaRelation::getRelationColumns)
                .filter(Objects::nonNull)
                .map(Stream::toList)
                .findFirst()
                .orElseGet(List::of);
    }

    /**
     * Builds the proposal for one column. The replacement covers the text
     * already typed behind the dot, so accepting the proposal rewrites it
     * rather than appending to it.
     *
     * @param offset caret position
     * @param text   text typed behind the dot, possibly empty
     * @param column column name with its type
     * @return proposal inserting the column name
     */
    static CompletionProposal getColumnProposal(int offset, String text,
            Pair<String, String> column) {
        String name = column.getFirst();
        Image img = Activator.getDbObjImage(DbObjType.COLUMN);
        String displayText = name + " - " + column.getSecond(); //$NON-NLS-1$
        IContextInformation info = new ContextInformation(name, column.getSecond());

        return new SqlEditorKeywordProposal(name, offset - text.length(), text.length(),
                name.length(), img, displayText, info, name);
    }

    private static SqlEditorKeywordProposal getProposal(int offset, String text, MetaStatement obj) {
        Image img = Activator.getDbObjImage(obj.getStatementType());
        String displayText = obj.getName();
        if (!obj.getComment().isEmpty()) {
            displayText += " - " + obj.getComment(); //$NON-NLS-1$
        }
        IContextInformation info = new ContextInformation(obj.getName(), obj.getComment());

        int replacementOffset;
        int replacementLength;
        if (!text.isEmpty()) {
            replacementOffset = offset - text.length();
            replacementLength = text.length();
        } else {
            replacementOffset = offset;
            replacementLength = 0;
        }

        return new SqlEditorKeywordProposal(obj.getName(), replacementOffset, replacementLength,
                obj.getObjLength(), img, displayText, info, obj.getName());
    }

    private static boolean checkDefinition(MetaStatement definition, String text, String parentName) {
        if (definition.getFilePath() == null || !VALID_TYPES.contains(definition.getStatementType())) {
            return false;
        }

        var difName = definition.getName();

        if (!text.isEmpty() && !difName.toUpperCase(Locale.ROOT).contains(text)) {
            return false;
        }

        if (parentName != null && (difName.equalsIgnoreCase(parentName)
                || !definition.getObject().getSchema().equalsIgnoreCase(parentName))) {
            return false;
        }

        return true;
    }

    @Override
    public IContextInformation[] computeContextInformation(ITextViewer viewer, int offset) {
        return null;
    }

    @Override
    public char[] getCompletionProposalAutoActivationCharacters() {
        return new char[] { '.', '(' };
    }

    @Override
    public char[] getContextInformationAutoActivationCharacters() {
        return new char[] { '#' };
    }

    @Override
    public String getErrorMessage() {
        return null;
    }

    @Override
    public IContextInformationValidator getContextInformationValidator() {
        return null;
    }

    /**
     * What one completion request produced.
     *
     * @param proposals proposals to show, in order
     * @param telemetry what finding them cost, as one line
     */
    record Completion(List<ICompletionProposal> proposals, String telemetry) {
    }

    /**
     * The columns behind a dot together with the record that led to them.
     *
     * @param columns columns to offer, in the order they are to be shown
     * @param binding record that bound the alias, null when no alias was
     *                resolved - which a three segment name never needs
     */
    private record Resolved(List<Pair<String, String>> columns, ObjectLocation binding) {
    }

    /**
     * Where the record that bound an alias came from.
     */
    private enum AliasSource {

        /** No alias was resolved, or none had to be. */
        NONE,
        /** The references the project recorded for the edited file. */
        INDEX,
        /** The statement under the caret, analyzed out of the buffer. */
        BUFFER;

        @Override
        public String toString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * What the completion may ask the project, written out as the questions it
     * asks rather than as access to the parser - so that what one keystroke
     * costs can be read off this list.
     */
    interface CompletionSource {

        /**
         * @param text text being completed, at least
         *             {@link PgDbParser#MIN_COMPLETION_PREFIX_LENGTH} long
         * @return definitions whose name holds the text
         */
        Stream<MetaStatement> completionCandidates(String text);

        /**
         * @return references the analyzer recorded for the edited file
         */
        Set<ObjectLocation> editorReferences();

        /**
         * The bindings of the statement the caret stands in, read out of the
         * editor buffer. Nothing by default, because only an editor holding a
         * parser of nothing but its own file may read them: there the records
         * cannot reach a project index, since that editor has none.
         *
         * @return bindings at their offsets in the document, empty when none
         *         are to be read
         */
        default List<ObjectLocation> bufferBindings() {
            return List.of();
        }

        /**
         * @param object object to look up, named in full
         * @return definitions of that one object
         */
        Stream<MetaStatement> definitionsFor(ObjectLocation object);
    }

    /**
     * Columns of a relation, by schema and relation name.
     */
    @FunctionalInterface
    interface RelationColumns {

        /**
         * @param schema   schema name, may be null when unknown
         * @param relation relation name
         * @return column names with their types, empty when no such relation
         */
        List<Pair<String, String>> get(String schema, String relation);
    }

    /**
     * The relation lookup, remembering whether it was ever reached.
     * <p>
     * Reaching it is the one thing the proposals cannot show: a name behind a
     * dot that resolves to no relation and a relation that answers no columns
     * both end in no proposals, and the count of columns is the same zero for
     * either. This asks nothing of its own and counts nothing - it records the
     * one event it is asked about, which is why the line it feeds cannot say
     * back what the column count already says.
     */
    private static final class AskedRelations implements RelationColumns {

        private final RelationColumns relations;
        private boolean asked;

        AskedRelations(RelationColumns relations) {
            this.relations = relations;
        }

        @Override
        public List<Pair<String, String>> get(String schema, String relation) {
            asked = true;
            return relations.get(schema, relation);
        }

        /**
         * @return whether the columns of a named relation were asked for
         */
        boolean wasAsked() {
            return asked;
        }
    }

    /**
     * Listener for cyclic work of the content assistant ("Ctrl + Space").
     */
    private final class CompletionListener implements ICompletionListener,
    ICompletionListenerExtension {

        @Override
        public void assistSessionStarted(ContentAssistEvent event) {
            repetition = 0;
        }

        @Override
        public void assistSessionEnded(ContentAssistEvent event) {
            repetition = -1;
        }

        @Override
        public void selectionChanged(ICompletionProposal proposal,
                boolean smartToggle) {
            // no impl
        }

        @Override
        public void assistSessionRestarted(ContentAssistEvent event) {
            repetition = 0;
        }
    }

    private static final class SqlEditorKeywordProposal extends CompletionProposal
    implements ICompletionProposalExtension2 {

        public SqlEditorKeywordProposal(String replacementString, int replacementOffset,
                int replacementLength, int cursorPosition) {
            super(replacementString, replacementOffset, replacementLength, cursorPosition);
        }

        public SqlEditorKeywordProposal(String replacementString, int replacementOffset,
                int replacementLength, int cursorPosition, Image image, String displayString,
                IContextInformation contextInformation, String additionalProposalInfo) {
            super(replacementString, replacementOffset, replacementLength, cursorPosition,
                    image, displayString, contextInformation, additionalProposalInfo);
        }

        // This override is necessary for correct filtering the proposed keywords
        // while typing text (here used 'contains' method instead of 'startsWith').
        // Also this override is necessary to prevent calling
        // 'computeCompletionProposals'-method on each keyboard button pressing.
        @Override
        public boolean validate(IDocument document, int offset, DocumentEvent event) {
            try {
                int replaceOffset = getReplacementOffset();
                if (offset >= replaceOffset) {
                    String typedText = document.get(replaceOffset, offset - replaceOffset);
                    return getReplacementString().toLowerCase().contains(typedText.toLowerCase());
                }
            } catch (BadLocationException e) {
                // concurrent modification - ignore
            }
            return false;
        }

        @Override
        public void apply(ITextViewer viewer, char trigger, int stateMask, int offset) {
            try {
                viewer.getDocument().replace(getReplacementOffset(),
                        offset - getReplacementOffset(), getReplacementString());
            } catch (BadLocationException x) {
                // ignore
            }
        }

        @Override
        public void selected(ITextViewer viewer, boolean smartToggle) {
            // no impl
        }

        @Override
        public void unselected(ITextViewer viewer) {
            // no impl
        }

        /**
         * {@inheritDoc}
         *
         * @deprecated This method is no longer called by the framework and clients should overwrite
         *             {@link #apply(ITextViewer, char, int, int)} instead
         */
        @Deprecated
        @Override
        public final void apply(IDocument document) {
            // not called anymore
        }
    }

    private static final class ProposalComparator implements Comparator<MetaStatement> {

        static final ProposalComparator INSTANCE = new ProposalComparator();

        @Override
        public int compare(MetaStatement o1, MetaStatement o2) {
            int result = Integer.compare(getRank(o1), getRank(o2));
            if (result != 0) {
                return result;
            }

            return o1.getFilePath().compareTo(o2.getFilePath());
        }

        private int getRank(MetaStatement el) {
            return switch (el.getStatementType()) {
            case SCHEMA -> 0;
            case TABLE -> 1;
            case VIEW -> 2;
            default -> 3;
            };
        }

        private ProposalComparator() {
        }
    }
}
