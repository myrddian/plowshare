package io.aeyer.plowshare.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** What a tool call refuses to be built as, and the one thing it refuses to do. */
class ToolCallTest {

    /**
     * All three fields are required, and {@code arguments} is required as an
     * empty string rather than as a null.
     *
     * <p>A null in any of them would be a run-time failure in the turn loop
     * rather than at the transport that read the wire, several turns and one
     * thread away from the response that was malformed. Arguments in
     * particular: a model that calls a zero-parameter tool has said something,
     * and null would make "no parameters" indistinguishable from a field this
     * runtime failed to read.
     */
    @Test
    void a_tool_call_is_not_a_tool_call_without_all_three_fields() {
        assertThrows(NullPointerException.class, () -> new ToolCall(null, "memory_recall", "{}"));
        assertThrows(NullPointerException.class, () -> new ToolCall("c1", null, "{}"));
        assertThrows(NullPointerException.class, () -> new ToolCall("c1", "memory_recall", null));
    }

    /**
     * Arguments are kept exactly as the model emitted them, including when they
     * are not valid JSON.
     *
     * <p>This record deliberately does not parse or validate them. There is no
     * schema for an arbitrary tool at this layer, and rejecting bad JSON here
     * would throw away a completion the box already generated and was paid for
     * — turning a tool's problem into a transport failure. The runtime parses
     * them against the tool it dispatches to, and a parse failure becomes that
     * tool's error result, which the model can see and correct next turn.
     */
    @Test
    void arguments_are_kept_verbatim_however_malformed() {
        assertEquals("{not json", new ToolCall("c1", "memory_recall", "{not json").arguments());
        assertEquals("", new ToolCall("c1", "memory_recall", "").arguments());
    }
}
