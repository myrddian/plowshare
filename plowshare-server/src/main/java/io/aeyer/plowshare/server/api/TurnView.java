package io.aeyer.plowshare.server.api;

import io.aeyer.plowshare.server.archive.TurnRecord;

/**
 * One turn of a conversation on the wire: what was said, what came back, and
 * what the prompt cost.
 *
 * <h2>{@code promptTokens} is a boxed {@code Integer}, and null is a real
 * answer</h2>
 *
 * <p>{@link TurnRecord} argues this at length and this record only has to not
 * undo it: the number is what the model's own tokenizer counted, and a turn that
 * never reached a model call — one that ended at its allowance, or with the
 * endpoint down — has <em>no measurement</em> rather than a measurement of zero.
 * An {@code int} here would collapse the two and send {@code 0}, which is
 * exactly what {@code turns_prompt_tokens_are_a_measurement} refuses one layer
 * down.
 *
 * <p><b>It matters at this layer and not only at that one, because this is the
 * layer a person reads.</b> A console rendering the absence as {@code 0} would
 * be stating the opposite of what happened — that a prompt was measured and
 * found to be free, rather than that nothing was ever sent — and it is the one
 * number in a history from which a reader judges how near a conversation is to
 * the fold.
 *
 * <p><b>It arrives as {@code "promptTokens": null} and not as a missing key</b>
 * — measured, because an earlier version of this sentence claimed Jackson omits
 * nulls and it does not: nothing here sets {@code @JsonInclude} and no {@code
 * default-property-inclusion} is configured. An explicit null is the better wire
 * form anyway, since a console distinguishing "no measurement" from "zero" wants
 * the field to be there and empty rather than to infer the difference from a key
 * that is not present. Note that MockMvc's {@code doesNotExist()} is satisfied
 * by a JSON null as well as by an absence, so it cannot pin this either way —
 * the test asserts {@code nullValue()} for that reason.
 *
 * <p>{@code ending} is sent as the constant's own name, and nothing here
 * enumerates them: an ending added on the server reaches a console without a
 * change to this record, which is why {@code JobView.OutcomeView} stopped
 * counting them in its own javadoc.
 *
 * <p><b>There is no {@code conversationId}.</b> {@link TurnRecord} carries one
 * because it is a row; every turn in this answer belongs to the conversation in
 * the path, so repeating it on each element would be a field that cannot vary —
 * the same reading {@code CompactionView} makes of its own.
 *
 * @param ordinal where in the conversation this turn came; 1 is the first thing
 *     said. What a console orders by, and what {@code CompactionView.throughOrdinal}
 *     points at when it says how far back a seam reaches
 * @param utterance what the person said. Never blank
 * @param answer what the turn came to, whatever its ending. Blank only for an
 *     {@code ANSWERED} turn, which is a model that answered with nothing
 * @param ending which ending this turn reached. <b>The bit a reader has to
 *     believe before believing {@code answer}</b>: a turn that stopped says how
 *     it stopped in its answer text, and a history that showed the text without
 *     the ending would dress a truncation as a reply
 * @param promptTokens what this turn's prompt cost by the model's own
 *     tokenizer, or {@code null} for a turn nobody measured. Never zero
 */
public record TurnView(
        int ordinal, String utterance, String answer, String ending, Integer promptTokens) {

    /**
     * This record from that row.
     *
     * <p>Public and not package-private, on {@code ProjectView.of}'s precedent
     * and for the same reason: {@code ConversationTurnsHandler} renders the same
     * view from the same rows, and the parity this server is measured by is that
     * the two surfaces answer one shape. A second copy of this record under
     * {@code ws/} would agree on the day it was written.
     */
    public static TurnView of(TurnRecord turn) {
        return new TurnView(
                turn.ordinal(),
                turn.utterance(),
                turn.answer(),
                turn.ending().name(),
                turn.promptTokens());
    }
}
