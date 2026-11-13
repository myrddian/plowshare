package io.aeyer.plowshare.server.messaging;

import io.aeyer.plowshare.protocol.RelayPort;
import java.util.Objects;

/**
 * Messaging-owned content carried opaquely by Relay TEXT. Addresses identify instances, not agent
 * topics. The destination conversation is a consistency check against messaging's resolved
 * instance; it grants no authority to inspect that conversation. Reply routing remains messaging
 * policy.
 *
 * <p>Identifiers retain their existing prefixes and bounded alphanumeric suffixes; they are not
 * UUIDs. Text is preserved verbatim, including whitespace around nonblank content. No transport or
 * JSON value crosses this immutable contract.
 */
public record MessagePayload(
    String message,
    String sender,
    String recipient,
    Body body,
    String replyTo,
    String conversation,
    boolean replyExpected,
    boolean finalReply) {
  public MessagePayload {
    message = identifier(message, "bdm_");
    sender = identifier(sender, "ins_");
    recipient = identifier(recipient, "ins_");
    conversation = identifier(conversation, "cnv_");
    Objects.requireNonNull(body, "body");
    if (replyTo != null) {
      replyTo = identifier(replyTo, "bdm_");
      if (replyTo.equals(message))
        throw new IllegalArgumentException("A message cannot reply to itself");
    } else if (finalReply) {
      throw new IllegalArgumentException("A final reply requires a request message");
    }
    if (body instanceof Retained retained && !retained.message().equals(message))
      throw new IllegalArgumentException("Retained body must name this message's owning record");
  }

  /**
   * A body is inline text or a reference to this message's retained body in messaging's records.
   */
  public sealed interface Body permits Inline, Retained {}

  /**
   * Inline content obeys the portable carrier ceiling. The codec additionally enforces the
   * explicitly configured messaging byte allowance before publication or delivery; no truncation is
   * permitted.
   */
  public record Inline(String text) implements Body {
    public Inline {
      text = RelayPort.text(text);
    }
  }

  /**
   * A reference never hydrates through Relay. The owning messaging record must already hold the
   * body; its authorized read and lifecycle remain the consumer's responsibility. This permits
   * required harness outcomes to retain their existing exemption from agent-authored body limits.
   */
  public record Retained(String message) implements Body {
    public Retained {
      message = identifier(message, "bdm_");
    }
  }

  static String identifier(String value, String prefix) {
    RelayPort.identity(value);
    if (!value.startsWith(prefix)
        || value.length() == prefix.length()
        || !value.substring(prefix.length()).matches("[A-Za-z0-9]+"))
      throw new IllegalArgumentException("Invalid messaging identifier");
    return value;
  }
}
