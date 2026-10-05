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
    WAKE_REQUESTED
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
      if (text == null || text.isBlank() || text.length() > 65536 || text.indexOf('\0') >= 0)
        throw new IllegalArgumentException("text must be bounded nonblank content");
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
      source = RelayValues.name(source, "lifecycle source");
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
