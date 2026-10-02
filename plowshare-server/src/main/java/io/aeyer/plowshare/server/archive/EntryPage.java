package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.server.agents.EntryKind;
import io.aeyer.plowshare.server.agents.Speaker;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One page of a conversation's entries, and how many there are in all.
 *
 * <h2>Why this is not a list of {@link EntryRecord}</h2>
 *
 * <p>An {@link EntryRecord} carries the whole of an entry's text and is right
 * to: it is what {@code Projection} builds a request out of, and a request
 * carrying two thirds of a tool result is not the request the model was given.
 * <b>A page is read by somebody looking at a conversation rather than by
 * something rebuilding one</b>. Rows are bounded by the page; tool results and
 * diagnostics are bounded by {@link EntryStore#MOST_CHARACTERS_PER_ENTRY},
 * because one {@code file_read} result is a hundred thousand characters and
 * fifty of them in one answer is not
 * a page of anything. Utterances, answers, refusals and summaries retain their
 * complete text so a reader can display the whole conversation.
 *
 * <p>So a row carries an <em>excerpt</em> and the length the entry really is,
 * and those are two fields rather than one truncated string, for the reason
 * {@link StoredResults} splits the same pair: a caller cannot tell a text that
 * fitted from one that was cut by looking at the text, and only one of the two
 * has anything more to fetch. {@link Row#cut()} is the question asked where the
 * answer is wanted.
 *
 * <p><b>Two readings and one record.</b> {@link EntryStore#pageOfLog} and {@link
 * EntryStore#pageOfProjection} answer with this shape and differ only in which
 * rows they select — the log and the part of it a model can be shown, which is
 * the distinction those two methods already carried and this one exposes. A
 * reader that wanted to tell them apart from the page alone can: a row the
 * projection returns is never superseded and never carries a kind with no role.
 *
 * @param listed the page. A forward reading is in conversation order — {@code
 *     turn_ordinal} then {@code ordinal}, which is the order the conversation
 *     happened in and not the order its rows arrived. <b>A backwards reading
 *     ({@link EntryStore#pageOfLogBefore}) is newest first by {@code ordinal}</b>,
 *     because "the last forty" is a question about arrival and the reader turns
 *     it round itself. Never null; empty for a conversation nothing has been
 *     said in and for a page past the end
 * @param total how many entries this reading holds, which is a number about the
 *     conversation and not about the page. Asked separately from the page for
 *     {@link StoredResults}' reason: a page past the end has no rows to carry a
 *     window function's count, and that is exactly where the number is needed
 * @param through the conversation's highest entry ordinal now, whatever this
 *     page holds — what a client counts as shown once it has shown this page
 * @param more whether this reading holds an entry older than {@link #oldest()}
 *     — what tells a reader of the tail whether to offer "earlier". <b>Null on
 *     a forward reading</b>, which pages by {@code offset} and {@code total} and
 *     was never asked what lies before it; a backwards reading always answers
 */
public record EntryPage(List<Row> listed, int total, int through, Boolean more) {

    /** A conversation with nothing in it, and the answer for a page of a
     *  reading that holds nothing. */
    public static final EntryPage NONE = new EntryPage(List.of(), 0, 0);

    public EntryPage {
        listed = listed == null ? List.of() : List.copyOf(listed);
        if (total < listed.size()) {
            throw new IllegalArgumentException(
                    "a page of " + listed.size() + " entries cannot come out of a conversation"
                            + " said to hold " + total);
        }
        if (through < 0) {
            throw new IllegalArgumentException("a log cannot reach ordinal " + through);
        }
    }

    /** A page of a forward reading, which says nothing of what lies before it. */
    public EntryPage(List<Row> listed, int total, int through) {
        this(listed, total, through, null);
    }

    /**
     * The smallest ordinal on this page, or null for an empty one — what a reader
     * reading backwards names as {@code before} to read the entries earlier than
     * these. Derived and not stored: it is a fact about the rows in hand, and a
     * component beside them could disagree with them.
     */
    public Integer oldest() {
        return listed.stream().map(Row::ordinal).min(Integer::compare).orElse(null);
    }

    /**
     * A page of the whole log, whose highest ordinal is its count: ordinals are
     * numbered from one and never reused. What a test that is not about {@code
     * through} builds.
     */
    public EntryPage(List<Row> listed, int total) {
        this(listed, total, total);
    }

    /**
     * One entry as a page shows it: where it came, what it is, as much of it as
     * fits, and everything about it that is not its text.
     *
     * <p><b>There is no {@code conversationId} and no {@code role}.</b> The
     * first is the conversation every row on the page belongs to, which {@code
     * CompactionView} declines to repeat per element for the same reason; the
     * second is derived from {@code kind} by {@link EntryKind#projects()} and by
     * the schema's own {@code entries_role_matches_kind}, so a page carrying it
     * would ship a third copy of a rule that already has two.
     *
     * @param ordinal where in the log this came; 1 is the first thing recorded.
     *     Arrival order, which is not the order the page is in
     * @param turnOrdinal which turn produced this, or for a {@code SUMMARY} the
     *     last turn it stands for
     * @param kind what this is, and therefore whether a model ever saw it
     * @param excerpt the whole transcript text, or other text cut at
     *     {@link EntryStore#MOST_CHARACTERS_PER_ENTRY},
     *     or {@code null} for a {@code TOOL_RESULT} whose payload has been
     *     ejected. Blank for an answer that was entirely tool calls — <b>and
     *     blank is not the ejected case</b>, which is why that one is an absence
     *     and not an empty string: a reader shown a blank cannot tell a tool that
     *     returned nothing from a payload that has gone
     * @param length how many characters the entry really is, counted by the
     *     database where the text already is. Equal to {@code excerpt.length()}
     *     exactly when nothing was cut. <b>Kept across an ejection</b>, out of
     *     {@code entries.ejected_chars}, so a page still says how large the
     *     result was
     * @param ejectedAt when this payload was ejected, or {@code null} while it is
     *     still here. Present for exactly the rows with no {@link #excerpt()}
     * @param supersededBy the ordinal of the summary that folded this entry
     *     away, or null for one no fold covers. <b>The one field that makes a
     *     page of the log say a fold happened</b>, and it is never set on a row
     *     the projection returns
     * @param toolCallId the id of the call this result answers, null for every
     *     kind but {@code TOOL_RESULT}
     * @param toolCalls what the model asked to call, in the order it asked, each
     *     with its arguments cut where the content is. Never null; empty for an
     *     answer that asked for nothing
     * @param handle the address {@code result_read} redeems this result at, null
     *     for every kind but {@code TOOL_RESULT} and for a result written before
     *     {@code V13} added the column
     * @param recordedAt when this was written, or null for an entry written
     *     before {@code V16} added the column
     * @param tookMillis how long the operation that produced this entry took, or
     *     null for a kind that records no operation and for one nobody measured.
     *     <b>Not the gap to the entry before it</b>
     * @param dispatch which target produced this — {@code primary} or {@code
     *     fallback} — or null for anything no model call produced and for a row
     *     written before {@code V39}. Execution provenance, not conversation:
     *     the actor a reader sees is still the assistant, and this says which
     *     model spoke for it. {@link #answeredByFallback()} reads it
     * @param wireModel the model that produced it, as the dispatcher named it,
     *     beside {@code dispatch} and on the same terms
     * @param completion what the completion was judged to be — {@code answered},
     *     {@code refused}, {@code cut_off}, {@code called_tools} — or null,
     *     likewise. What tells a fallback that answered from one that refused in
     *     turn
     * @param speaker who spoke this — set only on an {@code UTTERANCE}, and null
     *     for every other kind. {@code V57}: an utterance written before it has
     *     no speaker recorded and reads as {@link Speaker#person(String)
     *     Speaker.person(null)}
     * @param outcome a {@code TOOL_RESULT}'s outcome in a word, as the runtime
     *     told it — {@code ok}, {@code refused}, a delegation's ending, and the
     *     rest {@code ToolLines} names — or null for every other kind and for a
     *     result written before {@code V62}. Spec 2026-09-29 §2.3
     */
    public record Row(
            int ordinal,
            int turnOrdinal,
            EntryKind kind,
            String excerpt,
            int length,
            Instant ejectedAt,
            Integer supersededBy,
            String toolCallId,
            List<Asked> toolCalls,
            UUID handle,
            Instant recordedAt,
            Long tookMillis,
            String dispatch,
            String wireModel,
            String completion,
            Speaker speaker,
            String outcome) {

        public Row {
            Objects.requireNonNull(kind, "kind");
            if ((excerpt == null) != (ejectedAt != null)) {
                throw new IllegalArgumentException(excerpt == null
                        ? "an entry with no text and no ejection lost its content with nothing"
                                + " saying when"
                        : "an entry that says it was ejected still has text to show");
            }
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            if (excerpt != null && length < characters(excerpt)) {
                throw new IllegalArgumentException(
                        "an excerpt of " + characters(excerpt) + " characters cannot come out of an"
                                + " entry said to be " + length + " long");
            }
        }

        /** A row read before speakers were read, or built by a test that is not
         *  about them or about {@link #outcome()}. */
        public Row(
                int ordinal, int turnOrdinal, EntryKind kind, String excerpt, int length,
                Instant ejectedAt, Integer supersededBy, String toolCallId, List<Asked> toolCalls,
                UUID handle, Instant recordedAt, Long tookMillis, String dispatch,
                String wireModel, String completion) {
            this(ordinal, turnOrdinal, kind, excerpt, length, ejectedAt, supersededBy, toolCallId,
                    toolCalls, handle, recordedAt, tookMillis, dispatch, wireModel, completion,
                    null, null);
        }

        /** A row read before speakers were read <em>and</em> before {@code V57}
         *  had told it who spoke, built by a test that predates both — the
         *  15-arg overload's own caller, kept compiling on the same terms. */
        public Row(
                int ordinal, int turnOrdinal, EntryKind kind, String excerpt, int length,
                Instant ejectedAt, Integer supersededBy, String toolCallId, List<Asked> toolCalls,
                UUID handle, Instant recordedAt, Long tookMillis, String dispatch,
                String wireModel, String completion, Speaker speaker) {
            this(ordinal, turnOrdinal, kind, excerpt, length, ejectedAt, supersededBy, toolCallId,
                    toolCalls, handle, recordedAt, tookMillis, dispatch, wireModel, completion,
                    speaker, null);
        }

        /**
         * The row an entry that no model call produced has, and the shape this
         * record had before {@code V39} gave answers a target.
         *
         * <p>Kept so a caller with nothing to attribute -- a test building an
         * utterance, a tool result -- says so by not mentioning the three, the
         * same rule {@link EntryKind} and {@code AgentDefinition} keep for the
         * keys they added after the fact. A null provenance is "no model call",
         * which is true of every kind this constructor is reached for.
         */
        public Row(
                int ordinal, int turnOrdinal, EntryKind kind, String excerpt, int length,
                Instant ejectedAt, Integer supersededBy, String toolCallId, List<Asked> toolCalls,
                UUID handle, Instant recordedAt, Long tookMillis) {
            this(ordinal, turnOrdinal, kind, excerpt, length, ejectedAt, supersededBy, toolCallId,
                    toolCalls, handle, recordedAt, tookMillis, null, null, null, null, null);
        }

        /**
         * Whether a fallback target produced this, which is the one provenance
         * question a person reading a trajectory asks first. False for a
         * primary answer, and false for a row that carries no provenance at all
         * -- an utterance, a tool result, a pre-{@code V39} answer -- because
         * none of those is a fallback's, and a reader wants "was this the
         * fallback" answered yes or no rather than three-valued.
         */
        public boolean answeredByFallback() {
            return "fallback".equals(dispatch);
        }

        /**
         * Whether there is more of this entry than the page shows. Asked here
         * rather than computed at each reader, because the comparison is between
         * two fields whose relationship is this record's to state.
         *
         * <p><b>False for an ejected payload, which is not the same claim as
         * "you have all of it".</b> {@code cut} means "the page cut this", and
         * an ejected payload was not cut by anything on this page — the bytes
         * are not in the database at all, and {@link #ejectedAt()} is the field
         * that says so. A reader that showed "…" on one of these would be
         * offering to fetch the rest.
         */
        public boolean cut() {
            return excerpt != null && length > characters(excerpt);
        }
    }

    /**
     * One call a model asked for, as a page shows it: the address, the tool, and
     * as much of the arguments as fits.
     *
     * <p><b>The second unbounded dimension of an entry, and the one an
     * excerpt of the content does not cover.</b> {@code StoredResults.Result}
     * declines to carry arguments at all, and its reasoning applies here word
     * for word — {@code file_edit} sends a whole file as an argument, so a
     * listing that carried them is unbounded in exactly the dimension it exists
     * to bound. A page cannot take that way out: what a tool was asked for is
     * most of what a trajectory is worth reading for. So the arguments are cut
     * where {@code EntryPage.Row.excerpt} is cut, at {@link
     * EntryStore#MOST_CHARACTERS_PER_ENTRY}, in the database, so the file never
     * crosses to say how long it was.
     *
     * <p><b>Not {@code ToolCall}</b>, which this used to be. That record is the
     * call itself — its {@code arguments} is the raw JSON the model emitted, and
     * it has to stay whole, because the runtime parses it against the tool it
     * dispatches to. A record whose {@code arguments} may be two thirds of what
     * was sent must not be the type a dispatcher could be handed.
     *
     * @param id the address the model gave this call, which a {@code
     *     tool_result} answers by
     * @param name the tool asked for. <b>Model-supplied text</b>: a call to a
     *     tool that does not exist is still recorded, so this is not a name from
     *     any registry and is neither bounded nor free of line breaks. Whatever
     *     renders it flattens it
     * @param arguments the raw JSON the model emitted, cut at {@link
     *     EntryStore#MOST_CHARACTERS_PER_ENTRY}. Never null; empty for a call to
     *     a tool that takes none
     * @param length how many characters the arguments really were
     * @param salient the one argument {@code ToolLines.salient} picked out of
     *     the whole of what the model sent, computed at record time before this
     *     page cut the rest — so it survives a cut that would otherwise leave a
     *     truncated JSON string with nothing left to parse — or null for a call
     *     recorded before {@code V62}. Spec 2026-09-29 §2.3
     * @param opened the child conversation this call started, and the agent
     *     that ran in it, or null for a call nothing delegated to — every call
     *     but an {@code agent_run} that opened one
     */
    public record Asked(String id, String name, String arguments, int length, String salient,
            Opened opened) {

        public Asked {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(arguments, "arguments");
            if (length < characters(arguments)) {
                throw new IllegalArgumentException(
                        "arguments of " + characters(arguments) + " characters cannot come out of a"
                                + " call said to have sent " + length);
            }
        }

        /** A call read before salients and delegation were read, or built by a
         *  test that is not about them. */
        public Asked(String id, String name, String arguments, int length) {
            this(id, name, arguments, length, null, null);
        }

        /** Whether there is more of these arguments than the page shows. {@link
         *  Row#cut()}'s question, asked of the other bounded field. */
        public boolean cut() {
            return length > characters(arguments);
        }
    }

    /** The child conversation a call opened, and the agent that ran in it (spec 2026-09-29 §2.3). */
    public record Opened(String conversation, String agent) {
        public Opened {
            Objects.requireNonNull(conversation, "conversation");
            Objects.requireNonNull(agent, "agent");
        }
    }

    /**
     * How many characters a string holds, counted the way the database counted
     * the number it is about to be compared against.
     *
     * <p><b>Code points, and the unit is the whole point of this method.</b>
     * Every {@code length} on this page arrives from PostgreSQL — {@code
     * length(content)}, {@code length(call ->> 'arguments')}, and {@code
     * ejected_chars}, which {@code EntryStore} writes as {@code
     * length(content)} in SQL — and PostgreSQL counts a character as a CODE
     * POINT. {@link String#length()} counts UTF-16 CODE UNITS, which is two for
     * every character above U+FFFF. The two agree on ASCII and on everything
     * else in the Basic Multilingual Plane, which is why this was not noticed,
     * and disagree by one per emoji.
     *
     * <p><b>What the disagreement cost.</b> A bounded excerpt is {@code
     * left(content, ?)}, also code points, so an entry shorter than {@link
     * EntryStore#MOST_CHARACTERS_PER_ENTRY} comes back whole — and a whole
     * entry holding two emoji measured 3142 by {@link String#length()} against
     * a {@code length} of 3140, so the guard below refused a page that was
     * exactly right and {@code GET /v1/conversations/&#123;id&#125;/trajectory}
     * answered 500. The guards are correct; the unit was not.
     *
     * <p>Counted rather than stored because these records are built by a row
     * mapper per row and the string is already in hand: {@code codePointCount}
     * is one pass over text that has just been read out of a result set.
     */
    private static int characters(String text) {
        return text.codePointCount(0, text.length());
    }
}
