package io.aeyer.plowshare.server.agents;

import java.util.Objects;

/**
 * Who spoke an utterance: a person, with their handle, or the harness, with its source.
 *
 * <p>The log records the speaker so that a harness delivery ("The orchestration '…' stopped") can
 * never again be read back as something the person typed. {@code implementation rationale} §2.
 *
 * @param kind who
 * @param name the person's handle, or null when nobody knows it; for the harness, its source —
 *     {@code orchestration <id>}, {@code approval <id>}, {@code event <id>} or {@code harness}
 */
public record Speaker(Kind kind, String name) {

  /** The source of a harness utterance that names no run, approval or event. */
  public static final String PLAIN_SOURCE = "harness";

  /** The two speakers, spelled as {@code entries.speaker} and the wire spell them. */
  public enum Kind {
    PERSON("person"),
    HARNESS("harness");

    private final String wireName;

    Kind(String wireName) {
      this.wireName = wireName;
    }

    public String wireName() {
      return wireName;
    }

    public static Kind of(String wireName) {
      for (Kind kind : values()) {
        if (kind.wireName.equals(wireName)) {
          return kind;
        }
      }
      throw new IllegalArgumentException("no speaker is called '" + wireName + "'");
    }
  }

  public Speaker {
    Objects.requireNonNull(kind, "kind");
    if (name != null && name.isBlank()) {
      name = null;
    }
    if (kind == Kind.HARNESS && name == null) {
      throw new IllegalArgumentException(
          "a harness utterance names its source:"
              + " orchestration <id>, approval <id>, event <id> or harness");
    }
  }

  /** A person's utterance; {@code handle} may be null when nobody knows who they are. */
  public static Speaker person(String handle) {
    return new Speaker(Kind.PERSON, handle);
  }

  /** The harness, for an utterance that names no run, approval or event. */
  public static Speaker harness() {
    return new Speaker(Kind.HARNESS, PLAIN_SOURCE);
  }

  /** A run's question or ending, delivered by {@code orchestrations.Delivery}. */
  public static Speaker orchestration(String run) {
    return new Speaker(Kind.HARNESS, "orchestration " + Objects.requireNonNull(run, "run"));
  }

  /** A board wake: the harness, speaking for topic {@code topic} (spec 2026-09-29 §6). */
  public static Speaker board(String topic) {
    return new Speaker(Kind.HARNESS, "board " + Objects.requireNonNull(topic, "topic"));
  }

  /** An approval's question or answer, spoken to a run. */
  public static Speaker approval(String approval) {
    return new Speaker(Kind.HARNESS, "approval " + Objects.requireNonNull(approval, "approval"));
  }

  /** An event trigger's task. */
  public static Speaker event(String trigger) {
    return new Speaker(Kind.HARNESS, "event " + Objects.requireNonNull(trigger, "trigger"));
  }

  /** A direct message's durable identity, redeemable through authorized messaging reads. */
  public static Speaker message(String message) {
    return new Speaker(Kind.HARNESS, "message " + sourceId(message));
  }

  /** A Relay delivery's identity; distinct from its event and effect-budget ancestry. */
  public static Speaker relay(java.util.UUID delivery) {
    return new Speaker(Kind.HARNESS, "relay " + Objects.requireNonNull(delivery));
  }

  private static String sourceId(String value) {
    if (value == null
        || value.isBlank()
        || value.length() > 256
        || value.chars().anyMatch(Character::isWhitespace)) {
      throw new IllegalArgumentException("source identity must be a bounded nonblank token");
    }
    return value;
  }

  /**
   * What a stored row says about who spoke it, read back. Only an utterance has a speaker, and one
   * written before {@code V57} has none recorded and is read as a person's — which is what every
   * utterance was taken for until then.
   */
  public static Speaker read(EntryKind kind, String speaker, String name) {
    if (kind != EntryKind.UTTERANCE) {
      return null;
    }
    return speaker == null ? person(null) : new Speaker(Kind.of(speaker), name);
  }
}
