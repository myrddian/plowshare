package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.agents.EntryKind;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of {@code entries}: one message a conversation held, and whether a
 * fold has since covered it.
 *
 * <h2>{@link EntryKind} and not a string</h2>
 *
 * <p>The same argument {@link TurnRecord} makes about {@code Outcome.Ending}. A
 * record carrying the raw spelling would be the more literal translation of the
 * column and would move the failure {@code entries_kind_is_known} exists to
 * prevent out of this class and into every reader — a kind this build cannot
 * name would arrive as a plain string, and whoever asked whether it projects
 * would have to guess. Making the field the enum means the one place that parses
 * it is the one place that can produce the fault.
 *
 * <p><b>It is the fourth import of {@code server.agents} by {@code
 * server.archive} and it is the same edge rather than a new one.</b> {@code
 * ConversationRecord} records the first and names the tidy repair — these types
 * belong to neither package — and it is still not this task's.
 *
 * @param conversationId the conversation this was held in
 * @param ordinal where in that conversation's log it came; 1 is the first thing
 *     recorded. Assigned by {@link EntryStore#append} and never by its caller
 * @param kind what this is, and therefore whether it projects. See {@link
 *     EntryKind}: the answer is a property of the kind and never a decision
 *     taken about one entry
 * @param content the text, or {@code null} for a {@code TOOL_RESULT} whose
 *     payload has been ejected — see {@link #ejectedAt()}, which is present for
 *     exactly those rows. <b>Null is not "it said nothing"</b>: the empty string
 *     is that, and {@code entries_a_tool_result_is_not_blank} refuses it for a
 *     result outright because a blank result reads to a model as a tool that
 *     does not work. Otherwise never null. Blank only for an {@code ANSWER} — an
 *     assistant turn that is entirely tool calls carries no content, and a model
 *     that stopped on its first token carries neither content nor calls, which
 *     {@code Outcome} permits and the projection renders rather than drops
 * @param toolCallId the id of the call this result answers, and {@code null} for
 *     every kind but {@code TOOL_RESULT}. {@code
 *     entries_a_tool_result_is_exactly_what_answers_a_call} holds both
 *     directions of that in the database
 * @param toolCalls what the model asked to call, in the order it asked. Never
 *     null; empty for an answer that asked for nothing, which is the ordinary
 *     shape of the last answer of a turn. <b>Empty and NULL are the same fact
 *     here and the column is the one that spells it</b> — an answer with no
 *     calls stores NULL rather than {@code []}, so there is one representation
 *     of "asked for nothing" in the table
 * @param supersededBy the ordinal of the summary entry that folded this one
 *     away, or {@code null} for an entry no fold covers. The projection skips a
 *     superseded entry; nothing is deleted and no content is edited
 * @param handle the address a model redeems this result at, and {@code null}
 *     for every kind but {@code TOOL_RESULT} — and for a result written before
 *     {@code V13__entry_handles.sql} added the column. Minted by {@link
 *     EntryStore#append} and never by its caller, exactly as {@code ordinal} is:
 *     a caller that could choose a handle could choose a predictable one. It is
 *     a random UUID rather than the {@code (conversationId, ordinal)} pair that
 *     already addresses this row, because that pair is enumerable and a model
 *     holding one handle must not be able to write down another
 * @param turnOrdinal which turn produced this entry, or for a {@code SUMMARY}
 *     the last turn it stands for. <b>Not a foreign key</b>, and {@code
 *     V11__entries.sql} argues why at length: a turn row is written when the
 *     turn ends and entries are written as it runs
 * @param recordedAt when this was written, which for every kind this server
 *     appends is when the thing it records finished. Stamped by {@link
 *     EntryStore#append} from its own clock and never by a caller — {@code
 *     ordinal}'s rule and {@code handle}'s — and {@code null} for an entry
 *     written before {@code V16__entry_timing.sql} added the column
 * @param ejectedAt when this payload was ejected, or {@code null} while it is
 *     still here — which is every row but a {@code TOOL_RESULT} a retention
 *     sweep has reached. Exactly the rows with no {@link #content()} carry one,
 *     which {@code entries_an_ejected_payload_is_gone} holds in the database
 * @param export where the bytes were written, as the exporter wrote it down, or
 *     {@code null} both for a payload still here and for one ejected by a
 *     deployment that keeps no export. Nothing in this server resolves it: an
 *     export only Plowshare can find is not an export
 * @param tookMillis how long the operation that produced this entry took, in
 *     milliseconds, or {@code null} for a kind that records no operation and for
 *     an entry nobody measured. <b>Not the gap between this entry and the one
 *     before it</b>, which holds everything that happened in between; the number
 *     is measured around the model call or the tool call itself and carried here
 *     on {@code LoggedEntry.took}
 */
public record EntryRecord(
        String conversationId,
        int ordinal,
        EntryKind kind,
        String content,
        String toolCallId,
        List<ToolCall> toolCalls,
        Integer supersededBy,
        UUID handle,
        int turnOrdinal,
        Instant recordedAt,
        Long tookMillis,
        Instant ejectedAt,
        String export) {

    /**
     * Rejects the nulls that have no meaning, and copies the one list.
     *
     * <p>{@code toolCallId}, {@code supersededBy}, {@code handle}, {@code
     * recordedAt} and {@code tookMillis} are legitimately absent and are the
     * only five that are — {@code handle} on every kind but a result and on a
     * result older than the column, {@code recordedAt} on a row older than
     * {@code V16}, and {@code tookMillis} on every kind that performed no
     * operation.
     *
     * <p><b>{@code content} was the sixth and is not any more, and the change is
     * narrow.</b> It used to be rejected here with the note that a null "would
     * not be 'it said nothing' — the empty string is that", and that sentence is
     * still exactly right; what changed is that there is now a third thing a
     * null can mean, and it is neither of those two: the payload was ejected.
     * The pairing is what keeps it from being a gap — a null {@code content}
     * with no {@code ejectedAt} is a row that lost its text with nothing saying
     * when, and it is refused here as it is refused by {@code
     * entries_an_ejected_payload_is_gone}.
     */
    public EntryRecord {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(kind, "kind");
        if ((content == null) != (ejectedAt != null)) {
            throw new IllegalArgumentException(content == null
                    ? "an entry with no content and no ejection is a row that lost its text with"
                            + " nothing saying when, which is indistinguishable from corruption"
                    : "an entry that says it was ejected is still holding its content, which"
                            + " would make result_read report a payload gone while it has it");
        }
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }
}
