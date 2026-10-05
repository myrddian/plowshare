package io.aeyer.plowshare.server.orchestrations;

import io.aeyer.plowshare.protocol.Orchestration.Structure;
import java.time.Instant;

/**
 * One question the conductor asked, or one answer it was given — {@code orchestration_messages},
 * V48.
 *
 * @param id {@link OrchestrationStore#MESSAGE_PREFIX}-prefixed
 * @param orchestration the run this message belongs to
 * @param kind whether this is the question or the answer
 * @param text the question or the answer, whole
 * @param author who wrote it — the conductor for a question, whoever answered for an answer
 * @param createdAt when this message was written
 * @param deliveredAt when this message was delivered to whoever needed to see it, or {@code null}
 *     while still undelivered
 * @param capKind which cap this message answered — {@code turn_cap} or {@code call_budget}, or
 *     {@code stuck} (V58) — or {@code null} for a question, or an answer to no cap at all. Copied
 *     from the row's own {@code pending_cap} at the moment the answer was written, because that
 *     same write clears it — V49, {@link OrchestrationStore#answer}
 * @param structure the question's options or the answer's choices, as JSON text (V70), or {@code
 *     null} for a plain question or answer
 */
public record OrchestrationMessage(
    String id,
    String orchestration,
    Kind kind,
    String text,
    String author,
    Instant createdAt,
    Instant deliveredAt,
    String capKind,
    Structure structure) {

  /** A message with no structure — every message written before V70, and every plain one. */
  public OrchestrationMessage(
      String id,
      String orchestration,
      Kind kind,
      String text,
      String author,
      Instant createdAt,
      Instant deliveredAt,
      String capKind) {
    this(id, orchestration, kind, text, author, createdAt, deliveredAt, capKind, null);
  }

  /**
   * Which side of the exchange this message is. {@code orchestration_messages.kind} stores the wire
   * name.
   */
  public enum Kind {
    QUESTION("question"),
    ANSWER("answer");

    private final String wire;

    Kind(String wire) {
      this.wire = wire;
    }

    public String wire() {
      return wire;
    }

    /**
     * @throws IllegalArgumentException if no kind is spelled {@code wire}
     */
    public static Kind of(String wire) {
      for (Kind kind : values()) {
        if (kind.wire.equals(wire)) {
          return kind;
        }
      }
      throw new IllegalArgumentException(
          "no orchestration message kind is spelled '"
              + wire
              + "'; this row was"
              + " written by something that knows a kind this build does not, and"
              + " orchestration_messages_kind_is_known should have refused it");
    }
  }
}
