package io.aeyer.plowshare.server.messaging;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.aeyer.plowshare.server.agents.Outcome;
import java.io.IOException;

/** Typed output boundary for the unchanged message tool receipts and incoming-message text. */
final class MessageToolCodec {
  private static final ObjectMapper JSON = new ObjectMapper();

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  record Receipt(
      String message,
      String from,
      String to,
      String fromProject,
      String toProject,
      String toConversation,
      String returnAddress,
      boolean replyExpected,
      boolean queued) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record IncomingMessage(
      String id,
      String from,
      String sender,
      String fromProject,
      String toProject,
      String returnAddress,
      String body,
      boolean replyExpected,
      @JsonProperty("final") boolean finalReply,
      boolean generated,
      String replyTo,
      String ending) {}

  private record DelegateResult(Outcome.Ending ending, String output) {}

  private MessageToolCodec() {}

  static String receipt(Receipt receipt) {
    return encode(receipt);
  }

  static String incoming(IncomingMessage message) {
    return encode(message);
  }

  static String delegate(Outcome outcome) {
    return encode(new DelegateResult(outcome.ending(), outcome.text()));
  }

  static String source(MessageIngress.Source source) {
    return encode(source);
  }

  private static String encode(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (IOException invalid) {
      throw new IllegalStateException("Messaging tool output could not be encoded");
    }
  }
}
