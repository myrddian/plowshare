package io.aeyer.plowshare.server.messaging;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.protocol.ScheduledWork;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Native addressed admission shared by trusted transports and schedules; never an adapter runtime.
 */
public interface MessageIngress {
  String LEGACY_CHANNEL = "legacy";

  /** Generic origin evidence. Protocol-specific bodies stay at their owning transport boundary. */
  record Source(String kind, String identity, String fingerprint) {
    public Source {
      kind = RelayPort.identity(kind);
      identity = RelayPort.identity(identity);
      if (kind.length() > 64 || fingerprint == null || !fingerprint.matches("[a-f0-9]{64}"))
        throw new IllegalArgumentException("Invalid native source descriptor");
    }
  }

  record Request(
      String project,
      String channel,
      String client,
      String agent,
      UUID requestId,
      UUID context,
      String body,
      String command,
      Source source,
      RelayCausation causation) {
    public Request {
      if (Home.of(project).isGlobal())
        throw new IllegalArgumentException("Ingress requires a project");
      channel = RelayPort.identity(channel);
      client = MessageIngress.client(client);
      agent = RelayPort.identity(agent);
      Objects.requireNonNull(requestId, "requestId");
      body = MessageIngress.body(body);
      command = MessageIngress.command(command);
    }

    public Request(
        String project,
        String channel,
        String client,
        String agent,
        UUID requestId,
        UUID context,
        String body,
        String command,
        Source source) {
      this(project, channel, client, agent, requestId, context, body, command, source, null);
    }

    /** Native callers using the retained legacy scope during the explicit cutover. */
    public Request(
        String project,
        String client,
        String agent,
        UUID requestId,
        UUID context,
        String body,
        String command,
        Source source) {
      this(project, LEGACY_CHANNEL, client, agent, requestId, context, body, command, source, null);
    }
  }

  record Reply(
      String id,
      String body,
      boolean finalReply,
      boolean generated,
      String ending,
      Instant postedAt) {
    public Reply {
      id = MessagePayload.identifier(id, "bdm_");
      body = RelayPort.text(body);
      Objects.requireNonNull(postedAt, "postedAt");
      if (ending != null) RelayPort.identity(ending);
    }
  }

  /** Revision is an authoritative monotonic view version, distinct from broker log positions. */
  record Task(
      UUID id,
      UUID context,
      UUID requestId,
      long revision,
      String agent,
      String message,
      String state,
      String ending,
      Source source,
      List<Reply> replies,
      Instant createdAt) {
    public Task {
      Objects.requireNonNull(id);
      Objects.requireNonNull(context);
      Objects.requireNonNull(requestId);
      if (revision < 1) throw new IllegalArgumentException("Invalid native task revision");
      agent = RelayPort.identity(agent);
      message = MessagePayload.identifier(message, "bdm_");
      if (!List.of("SUBMITTED", "WORKING", "INPUT_REQUIRED", "COMPLETED", "FAILED", "CANCELED")
          .contains(state)) throw new IllegalArgumentException("Invalid native task state");
      if (ending != null) RelayPort.identity(ending);
      replies = List.copyOf(replies);
      if (replies.size() > 200
          || replies.stream().map(Reply::id).distinct().count() != replies.size())
        throw new IllegalArgumentException("Invalid native task replies");
      Objects.requireNonNull(createdAt);
    }
  }

  Task receive(String account, Request request);

  Task receiveScheduled(
      String account,
      String sourceProject,
      String schedule,
      String firing,
      ScheduledWork definition);

  Task task(String account, String project, String channel, String client, UUID id);

  Task summary(String account, String project, String channel, String client, UUID id);

  void cancel(String account, String project, String channel, String client, UUID id);

  Task cancelTask(String account, String project, String channel, String client, UUID id);

  static String body(String value) {
    value = RelayPort.text(value);
    if (value.getBytes(StandardCharsets.UTF_8).length > 65536)
      throw new IllegalArgumentException("Ingress body exceeds its text contract");
    return value;
  }

  static String command(String value) {
    if (value != null
        && !value.matches(
            "/(skill|orchestration):[a-zA-Z0-9_.-]+(?: --mode=(INHERITED|SUMMARISED|NEW|DIRECT))?"))
      throw new IllegalArgumentException("Only an explicit qualified command can be requested");
    return value;
  }

  static String client(String value) {
    if (value == null || !value.matches("[a-zA-Z0-9_.-]{1,64}"))
      throw new IllegalArgumentException("Invalid native client alias");
    return value;
  }
}
