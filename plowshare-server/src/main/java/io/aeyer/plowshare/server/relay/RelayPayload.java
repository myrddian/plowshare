package io.aeyer.plowshare.server.relay;

import java.time.Instant;

/** Explicit payload families; each new system topic needs a validated DTO and codec version. */
public sealed interface RelayPayload {
  Kind kind();

  enum Kind {
    EMPTY,
    TEXT,
    SCHEDULE_DUE,
    LIFECYCLE,
    WAKE_REQUESTED,
    INFORMATION_CHANGED
  }

  record Empty() implements RelayPayload {
    @Override
    public Kind kind() {
      return Kind.EMPTY;
    }
  }

  /** Custom prose, never interpreted by the broker as instructions or encoded structured data. */
  record Text(String text) implements RelayPayload {
    public Text {
      io.aeyer.plowshare.protocol.RelayPort.text(text);
    }

    @Override
    public Kind kind() {
      return Kind.TEXT;
    }
  }

  /** Intended occurrence time is separate from the broker's publication time. */
  record ScheduleDue(String schedule, String emits, Instant fireAt) implements RelayPayload {
    public ScheduleDue {
      schedule = scheduledIdentity(schedule);
      emits = scheduledIdentity(emits);
      fireAt = RelayValues.time(fireAt);
    }

    @Override
    public Kind kind() {
      return Kind.SCHEDULE_DUE;
    }
  }

  /** A bounded change notice with references to owning records, never command/result text. */
  record Lifecycle(String source, String subject, String state, String context, String related)
      implements RelayPayload {
    public Lifecycle {
      source = RelayValues.topic(source);
      subject = scheduledIdentity(subject);
      state = RelayValues.identity(state, "lifecycle state");
      if (context != null) context = scheduledIdentity(context);
      if (related != null) related = scheduledIdentity(related);
    }

    @Override
    public Kind kind() {
      return Kind.LIFECYCLE;
    }
  }

  /** Server-private wake availability; the owning firing retains all instruction and lease data. */
  record WakeRequested(String firing, String target, WakeKind type) implements RelayPayload {
    public WakeRequested {
      firing = scheduledIdentity(firing);
      target = scheduledIdentity(target);
      java.util.Objects.requireNonNull(type);
      if (!firing.startsWith("fir_")
          || !target.startsWith("conversation:")
          || target.length() <= 13)
        throw new IllegalArgumentException("invalid native wake reference");
    }

    @Override
    public Kind kind() {
      return Kind.WAKE_REQUESTED;
    }
  }

  enum WakeKind {
    MESSAGE,
    BOARD
  }

  /**
   * One information event (decision 0037). The catalogue retains the revision itself; this carries
   * only what a subscriber needs in order to invalidate what it holds and render the change.
   *
   * <p>{@code owner} is the home resource's author and exists for one reason: {@code detail} was
   * only ever shown to that account, and the publication is read back by every subscriber on the
   * topic. Carrying the owner in the payload lets the read path reapply that redaction in memory,
   * so no reader gains detail it could not see before and no per-event query is needed to decide
   * it. {@code detail} and {@code documentSubtype} are absent rather than empty when unset.
   *
   * <p>The event's position in the topic is its sequence and its {@code occurredAt} is its recorded
   * time, so neither is duplicated here.
   */
  record InformationChanged(
      String revision,
      long generation,
      String stage,
      String action,
      String detail,
      String documentType,
      String documentSubtype,
      String owner)
      implements RelayPayload {
    public InformationChanged {
      revision = java.util.UUID.fromString(scheduledIdentity(revision)).toString();
      if (generation < 1) throw new IllegalArgumentException("event generation must be positive");
      stage = scheduledIdentity(stage);
      action = scheduledIdentity(action);
      documentType = scheduledIdentity(documentType);
      if (documentSubtype != null) documentSubtype = scheduledIdentity(documentSubtype);
      owner = scheduledIdentity(owner);
      // Detail is free-form diagnostic text a stage wrote, so it is bounded by the broker's text
      // rule rather than the identity rule, and an empty one is carried as absent.
      if (detail != null && detail.isBlank()) detail = null;
      if (detail != null) detail = io.aeyer.plowshare.protocol.RelayPort.text(detail);
    }

    @Override
    public Kind kind() {
      return Kind.INFORMATION_CHANGED;
    }
  }

  /** Preserves the existing event runtime's 1,024-character schedule/event identity contract. */
  private static String scheduledIdentity(String value) {
    if (value == null
        || value.isBlank()
        || !value.equals(value.strip())
        || value.length() > 1024
        || value
            .codePoints()
            .anyMatch(c -> Character.isISOControl(c) || c == 0x2028 || c == 0x2029))
      throw new IllegalArgumentException(
          "scheduled identity must be bounded without controls or padding");
    return value;
  }
}
