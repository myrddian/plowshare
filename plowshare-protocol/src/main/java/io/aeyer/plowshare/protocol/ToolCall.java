package io.aeyer.plowshare.protocol;

import java.util.Objects;

/**
 * One tool call a model asked for.
 *
 * <p>{@code arguments} is the raw JSON string the model emitted, deliberately unparsed. The
 * transport has no schema for an arbitrary tool, and parsing there would turn a tool's problem into
 * a transport failure — discarding a completion the box already generated and was paid for. The
 * runtime parses it against the tool it dispatches to, and a parse failure becomes that tool's
 * error result, which the model can see and correct on the next turn.
 *
 * <p>All three are required, and {@code arguments} is required as an empty string rather than as a
 * null. A model that calls a zero-parameter tool has said something — "no parameters" — and null
 * would make that indistinguishable from a field this runtime failed to read. The transport is what
 * turns an absent {@code arguments} into {@code ""}; see {@code
 * a_tool_call_with_no_arguments_field_carries_an_empty_string}.
 *
 * <p>In the protocol module rather than beside the dispatcher because a tool call crosses the MCP
 * boundary in a job log: the client renders what an agent asked for, and a type only the server can
 * name would have to be copied there.
 */
public record ToolCall(String id, String name, String arguments) {

  public ToolCall {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(arguments, "arguments");
  }
}
