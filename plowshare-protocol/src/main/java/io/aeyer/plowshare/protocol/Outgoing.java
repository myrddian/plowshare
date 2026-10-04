package io.aeyer.plowshare.protocol;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Protocol-neutral external work. Peers are configured by adapters, not by model URLs. Remote
 * output is a reported observation, never evidence of local tool execution.
 */
public final class Outgoing {
  private Outgoing() {}

  public record Send(
      UUID requestId,
      String peer,
      Map<String, Object> message,
      String project,
      String conversation) {}

  public record Advertise(
      String project, List<String> peers, Map<String, Map<String, Object>> agentCards) {
    public Advertise(String project, List<String> peers) {
      this(project, peers, null);
    }
  }

  public record Claim(String project, List<String> peers) {}

  public record Id(UUID id) {}

  public record Report(
      UUID id,
      long revision,
      String state,
      String remoteTask,
      String remoteContext,
      Map<String, Object> result,
      String error) {}

  public record Work(
      UUID id,
      UUID requestId,
      String peer,
      String project,
      String conversation,
      Map<String, Object> message,
      String state,
      boolean cancelRequested,
      String remoteTask,
      String remoteContext,
      Map<String, Object> result,
      String error,
      long revision,
      Instant createdAt) {}

  public record Claimed(Work work, String action) {}

  public record Peer(String peer, Map<String, Object> agentCard) {}

  public record Peers(List<String> peers, List<Peer> details) {
    public Peers(List<String> peers) {
      this(peers, List.of());
    }
  }
}
