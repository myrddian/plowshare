package io.aeyer.plowshare.protocol;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Durable adapter ingress. Client identities are supplied by the authenticated adapter. */
public final class Incoming {
  private Incoming() {}

  public record Receive(
      String project,
      String client,
      String agent,
      UUID requestId,
      UUID context,
      String body,
      String command,
      Map<String, Object> source) {}

  public record Id(String project, String client, UUID id) {}

  public record Reply(
      String id,
      String body,
      boolean finalReply,
      boolean generated,
      String ending,
      Instant postedAt) {}

  public record Task(
      UUID id,
      UUID context,
      String agent,
      String message,
      String state,
      String ending,
      Map<String, Object> source,
      List<Reply> replies,
      Instant createdAt) {}
}
