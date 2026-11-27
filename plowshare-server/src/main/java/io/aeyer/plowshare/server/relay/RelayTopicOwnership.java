package io.aeyer.plowshare.server.relay;

import java.util.Objects;

/** Registration evidence for one Relay topic generation, supplied by trusted server code. */
public record RelayTopicOwnership(Owner owner, boolean goalFeed) {
  public enum Owner {
    UNCLASSIFIED,
    GENERAL_PURPOSE,
    RELAY,
    EVENTS,
    MESSAGING,
    BOARD,
    JOBS,
    APPROVALS,
    ORCHESTRATIONS,
    INFORMATION,
    APPLICATIONS,
    PERSONAL,
    SECURITY,
    HOSTED_TOOLS
  }

  static final RelayTopicOwnership UNCLASSIFIED =
      new RelayTopicOwnership(Owner.UNCLASSIFIED, false);
  private static final RelayTopicOwnership GENERAL =
      new RelayTopicOwnership(Owner.GENERAL_PURPOSE, false);

  public RelayTopicOwnership {
    Objects.requireNonNull(owner);
    if (goalFeed && (owner == Owner.UNCLASSIFIED || owner == Owner.GENERAL_PURPOSE))
      throw new IllegalArgumentException("Goal feed exposure belongs to a subsystem");
  }

  public static RelayTopicOwnership internal(Owner owner) {
    return new RelayTopicOwnership(owner, false);
  }

  static RelayTopicOwnership ordinary(Relay.TopicKey key, RelayPayload.Kind kind) {
    return key.scope() instanceof Relay.ProjectScope
            && (kind == RelayPayload.Kind.EMPTY
                || kind == RelayPayload.Kind.TEXT
                || kind == RelayPayload.Kind.BLOB)
            && !key.name().startsWith("tool.")
        ? GENERAL
        : UNCLASSIFIED;
  }

  void requireSubsystem(RelayPayload.Kind kind) {
    if (owner == Owner.UNCLASSIFIED || owner == Owner.GENERAL_PURPOSE)
      throw new IllegalArgumentException("An owning Relay registration requires a subsystem");
    if (goalFeed
        && (kind == RelayPayload.Kind.SCHEDULE_DUE || kind == RelayPayload.Kind.WAKE_REQUESTED))
      throw new IllegalArgumentException(
          "Native Relay wake payloads cannot be exposed to Goal Agents");
  }

  /** The typed outbox identifies native feeds by its own exact source names. */
  static RelayTopicOwnership captured(Relay.TopicKey topic, RelayPayload.Kind kind) {
    if (kind == RelayPayload.Kind.WAKE_REQUESTED) return internal(Owner.RELAY);
    return internal(
        switch (topic.name()) {
          case "job.started", "job.ended" -> Owner.JOBS;
          case "approval.requested", "approval.resolved" -> Owner.APPROVALS;
          case "orchestration.started",
                  "orchestration.ended",
                  "orchestration.resumed",
                  "orchestration.child.completed",
                  "orchestration.question.asked",
                  "orchestration.question.answered" ->
              Owner.ORCHESTRATIONS;
          case "board.opened", "board.closed", "board.resolved", "board.message.posted" ->
              Owner.BOARD;
          case "message.instance.changed",
                  "message.accepted",
                  "message.reply.recorded",
                  "message.handling.ended" ->
              Owner.MESSAGING;
          case "mailbox.item.added", "mailbox.application.item.added" -> Owner.PERSONAL;
          case "application.inbox.item.added" -> Owner.APPLICATIONS;
          // The outbox accepts trusted native notices. New sources remain internal by default.
          default -> Owner.RELAY;
        });
  }
}
