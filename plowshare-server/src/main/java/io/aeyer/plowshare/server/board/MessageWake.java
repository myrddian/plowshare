package io.aeyer.plowshare.server.board;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Persisted direct-message wake; a continuation consumes the original request's lease/receipt. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MessageWake(
    @JsonProperty("direct_message") String message,
    @JsonProperty("message_continuation") Continuation continuation,
    @JsonProperty("message_approval") String approval,
    String utterance,
    String delegate) {
  public enum Continuation {
    @JsonProperty("approval")
    APPROVAL,
    @JsonProperty("delegate_result")
    DELEGATE_RESULT
  }

  public MessageWake {
    message = identity(message, "message");
    if (approval != null) approval = identity(approval, "approval");
    if (delegate != null) delegate = identity(delegate, "delegate");
    if (continuation == null && (utterance != null || approval != null || delegate != null))
      throw new IllegalArgumentException("message wake cannot carry unrequested continuation data");
    if (continuation != null
        && (utterance == null || utterance.indexOf('\0') >= 0 || utterance.length() > 1048576))
      throw new IllegalArgumentException("continuation needs bounded utterance");
    if (continuation == Continuation.APPROVAL && approval == null)
      throw new IllegalArgumentException("approval continuation requires approval identity");
    if (continuation == Continuation.DELEGATE_RESULT && (approval != null || delegate != null))
      throw new IllegalArgumentException("delegate result cannot request approval execution");
  }

  public MessageWake(String message) {
    this(message, null, null, null, null);
  }

  private static String identity(String value, String field) {
    if (value == null
        || value.isBlank()
        || !value.equals(value.strip())
        || value.length() > 1024
        || value
            .codePoints()
            .anyMatch(c -> Character.isISOControl(c) || c == 0x2028 || c == 0x2029))
      throw new IllegalArgumentException(field + " must be a bounded message identity");
    return value;
  }
}
