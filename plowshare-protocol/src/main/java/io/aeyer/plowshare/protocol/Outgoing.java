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

  public record PeerQuery(String project) {
    public PeerQuery {
      project = project == null ? null : Home.of(project).project();
    }
  }

  public record Send(
      UUID requestId, String peer, ExternalMessage message, String project, String conversation) {
    public Send {
      java.util.Objects.requireNonNull(requestId, "requestId");
      peer = checkedPeer(peer);
      java.util.Objects.requireNonNull(message, "message");
      project = project == null ? null : Home.of(project).project();
      conversation = ContractValues.optionalIdentity(conversation, "conversation", 1024);
    }
  }

  public record Advertise(String project, List<String> peers, Map<String, AgentCard> agentCards) {
    public Advertise {
      project = project == null ? null : Home.of(project).project();
      peers = checkedPeers(peers);
      if (agentCards != null) {
        agentCards = Map.copyOf(agentCards);
        if (!peers.containsAll(agentCards.keySet()))
          throw new IllegalArgumentException("agent cards must belong to advertised peers");
      }
    }

    public Advertise(String project, List<String> peers) {
      this(project, peers, null);
    }
  }

  public record Claim(String project, List<String> peers) {
    public Claim {
      project = project == null ? null : Home.of(project).project();
      peers = checkedPeers(peers);
    }
  }

  public record Id(UUID id) {
    public Id {
      java.util.Objects.requireNonNull(id, "id");
    }
  }

  public record Report(
      UUID id,
      long revision,
      String state,
      String remoteTask,
      String remoteContext,
      ExternalResult result,
      String error) {
    public Report {
      java.util.Objects.requireNonNull(id, "id");
      if (revision < 0) throw new IllegalArgumentException("revision must be nonnegative");
      state = checkedState(state, true);
      remoteTask = ContractValues.optionalIdentity(remoteTask, "remote task", 1024);
      remoteContext = ContractValues.optionalIdentity(remoteContext, "remote context", 1024);
      error = ContractValues.text(error, "error", 2000, false);
      if (java.util.Set.of("WORKING", "INPUT_REQUIRED", "AUTH_REQUIRED").contains(state)
          && remoteTask == null)
        throw new IllegalArgumentException("nonterminal report requires remote task identity");
      checkedObservation(remoteTask, remoteContext, result);
      if (result instanceof ExternalResult.TaskResult observed && !state.equals("UNKNOWN")) {
        String remoteState = observed.task().status().state().substring("TASK_STATE_".length());
        if (remoteState.equals("SUBMITTED")) remoteState = "WORKING";
        if (!state.equals(remoteState))
          throw new IllegalArgumentException("reported state differs from observed task state");
      }
    }
  }

  public record Work(
      UUID id,
      UUID requestId,
      String peer,
      String project,
      String conversation,
      ExternalMessage message,
      String state,
      boolean cancelRequested,
      String remoteTask,
      String remoteContext,
      ExternalResult result,
      String error,
      long revision,
      Instant createdAt) {
    public Work {
      java.util.Objects.requireNonNull(id, "id");
      java.util.Objects.requireNonNull(requestId, "requestId");
      peer = checkedPeer(peer);
      project = project == null ? null : Home.of(project).project();
      conversation = ContractValues.optionalIdentity(conversation, "conversation", 1024);
      java.util.Objects.requireNonNull(message, "message");
      state = checkedState(state, false);
      remoteTask = ContractValues.optionalIdentity(remoteTask, "remote task", 1024);
      remoteContext = ContractValues.optionalIdentity(remoteContext, "remote context", 1024);
      error = ContractValues.text(error, "error", 2000, false);
      if (revision < 0) throw new IllegalArgumentException("revision must be nonnegative");
      java.util.Objects.requireNonNull(createdAt, "createdAt");
      checkedObservation(remoteTask, remoteContext, result);
    }
  }

  public record Claimed(Work work, String action) {
    public Claimed {
      if (work == null) {
        if (action != null) throw new IllegalArgumentException("empty claim cannot have an action");
      } else if (!java.util.Set.of("send", "observe", "cancel")
              .contains(action == null ? "" : action)
          || action.equals("send")
              && (!work.state().equals("DISPATCHED") || work.remoteTask() != null)
          || !action.equals("send") && work.remoteTask() == null)
        throw new IllegalArgumentException("invalid outgoing claim action");
    }
  }

  public record Peer(String peer, AgentCard agentCard) {
    public Peer {
      peer = checkedPeer(peer);
    }
  }

  public record Peers(List<String> peers, List<Peer> details) {
    public Peers {
      peers = ContractValues.list(peers, "peers", 32).stream().map(Outgoing::checkedPeer).toList();
      details = ContractValues.list(details, "peer details", 32);
      if (peers.stream().distinct().count() != peers.size()
          || details.stream().map(Peer::peer).distinct().count() != details.size()
          || !peers.containsAll(details.stream().map(Peer::peer).toList()))
        throw new IllegalArgumentException("peer details must name unique listed peers");
    }

    public Peers(List<String> peers) {
      this(peers, List.of());
    }
  }

  /**
   * Retained evidence may have an older state, but may never belong to a different task/context.
   */
  private static void checkedObservation(
      String remoteTask, String remoteContext, ExternalResult result) {
    if (result instanceof ExternalResult.TaskResult observed) {
      if (!observed.task().id().equals(remoteTask)
          || !observed.task().contextId().equals(remoteContext))
        throw new IllegalArgumentException("foreign remote task observation");
    } else if (result instanceof ExternalResult.MessageResult observed) {
      var message = observed.message();
      if (message.taskId() != null && !message.taskId().equals(remoteTask)
          || message.contextId() != null && !message.contextId().equals(remoteContext))
        throw new IllegalArgumentException("foreign remote message observation");
    }
  }

  private static String checkedPeer(String value) {
    if (value == null || !value.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}"))
      throw new IllegalArgumentException("peer must be a configured name");
    return value;
  }

  private static List<String> checkedPeers(List<String> values) {
    values = ContractValues.list(values, "peers", 32).stream().map(Outgoing::checkedPeer).toList();
    if (values.isEmpty() || values.stream().distinct().count() != values.size())
      throw new IllegalArgumentException("one to 32 distinct configured peers required");
    return values;
  }

  private static String checkedState(String value, boolean report) {
    if (!java.util.Set.of(
                "QUEUED",
                "DISPATCHED",
                "WORKING",
                "INPUT_REQUIRED",
                "AUTH_REQUIRED",
                "COMPLETED",
                "FAILED",
                "CANCELED",
                "REJECTED",
                "UNKNOWN")
            .contains(value == null ? "" : value)
        || report && java.util.Set.of("QUEUED", "DISPATCHED").contains(value))
      throw new IllegalArgumentException("invalid outgoing state");
    return value;
  }
}
