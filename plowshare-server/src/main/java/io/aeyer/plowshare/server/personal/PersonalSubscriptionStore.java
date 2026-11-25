package io.aeyer.plowshare.server.personal;

import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.server.relay.Relay;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Active owner-approved bindings and one durable in-flight dispatch per subscription. */
interface PersonalSubscriptionStore {
  record Key(long project, String name) {}

  enum SourceType {
    MAILBOX,
    RELAY,
    APPLICATION_INBOX,
    ATTENTION_COPY,
    JOB,
    ORCHESTRATION,
    BOARD,
    INFORMATION;

    boolean nativeTopic() {
      return this != MAILBOX && this != RELAY;
    }

    boolean applicationItem() {
      return this == APPLICATION_INBOX || this == ATTENTION_COPY;
    }
  }

  record Spec(
      String name,
      String sourceProject,
      String schedule,
      String ending,
      Relay.TopicKey relayTopic,
      SourceType sourceType,
      Long attentionProject,
      String agent,
      String skill,
      String mode,
      String skillHash,
      String messageRoute,
      boolean paused) {
    boolean sameSource(Spec other) {
      return sourceProject.equals(other.sourceProject)
          && Objects.equals(schedule, other.schedule)
          && Objects.equals(relayTopic, other.relayTopic)
          && sourceType == other.sourceType
          && Objects.equals(attentionProject, other.attentionProject);
    }
  }

  record Binding(
      Key key,
      String owner,
      UUID consumer,
      UUID topicGeneration,
      Spec spec,
      Instant enabledAt,
      long relaySeen,
      String state,
      String event,
      String position,
      RelayCausation cause,
      UUID attempt,
      UUID claim,
      String job,
      String detail,
      Instant updatedAt,
      String jobEnding) {}

  /** Joins the same owner lock used by native Mailbox outbox capture. */
  void lock(long project);

  /** Serializes the activation cutoff with capture in all selected Application inboxes. */
  void lockApplicationInboxes(List<Long> projects);

  String revision(long project);

  Instant now();

  List<Binding> bindings(long project);

  Optional<Binding> find(Key key);

  List<Binding> workAfter(Key after, int limit);

  void activate(long project, String revision);

  void save(
      long project,
      UUID consumer,
      UUID topicGeneration,
      Spec spec,
      Instant enabledAt,
      long relaySeen);

  void remove(long project, List<String> keep);

  /** Caller holds the owner lock and commits this together with the Relay cursor advance. */
  void prepare(
      Binding binding,
      String event,
      String position,
      String relayInput,
      RelayCausation cause,
      UUID attempt,
      UUID claim);

  /** Loads a pinned topic argument only for the current unpaused claim, after source admission. */
  Optional<String> relayInput(Binding binding);

  /** The owner lock serializes positions. An unavailable event is recorded in the same commit. */
  void advanceRelay(Binding binding, long position, String unavailableEvent, String detail);

  /** Reclaims only a dispatch with no linked job and fences the previous claimant. */
  boolean reclaim(Binding binding, UUID claim);

  /** The owning job IDs are reserved before its worker can run. */
  boolean started(Binding binding, String job);

  /** Refresh a live worker's claim without overwriting a concurrent disposition. */
  void renewRunning(Binding binding);

  /** A stale linked job is inspectable, and must never be launched a second time. */
  void unconfirmed(Binding binding);

  void settle(Binding binding, String state, String detail);
}
