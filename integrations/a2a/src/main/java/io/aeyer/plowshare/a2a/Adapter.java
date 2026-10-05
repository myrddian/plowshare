package io.aeyer.plowshare.a2a;

import io.aeyer.plowshare.protocol.Outgoing;
import io.aeyer.plowshare.sdk.OutgoingClient;
import java.io.IOException;
import java.util.*;

/** One outbound adapter tick. A failed send is unknown, never an automatic retry. */
public final class Adapter {
  private final OutgoingClient outgoing;
  private final String project;
  private final Map<String, A2aClient> peers;

  public Adapter(OutgoingClient outgoing, String project, Map<String, A2aClient> peers) {
    this.outgoing = Objects.requireNonNull(outgoing);
    this.project = project;
    this.peers = Map.copyOf(peers);
    if (peers.isEmpty()) throw new IllegalArgumentException("at least one A2A peer required");
  }

  public boolean tick() throws IOException {
    var names = peers.keySet().stream().sorted().toList();
    var cards = new LinkedHashMap<String, io.aeyer.plowshare.protocol.AgentCard>();
    for (String name : names) cards.put(name, peers.get(name).agentCard());
    outgoing.advertise(project, names, cards);
    Outgoing.Claimed claimed = outgoing.claim(project, names);
    if (claimed.work() == null) return false;
    var work = claimed.work();
    A2aClient.Observation observed;
    try {
      var peer = peers.get(work.peer());
      observed =
          switch (claimed.action()) {
            case "send" -> peer.send(work.id(), work.message());
            case "observe" -> peer.status(work.remoteTask(), work.remoteContext());
            case "cancel" -> peer.cancel(work.remoteTask(), work.remoteContext());
            default -> throw new IOException("unknown outgoing action");
          };
    } catch (A2aClient.InvalidMessage invalid) {
      observed =
          new A2aClient.Observation(
              "FAILED", null, null, null, "Invalid A2A message; nothing was sent to the peer.");
    } catch (IOException | RuntimeException failure) {
      // Do not persist arbitrary remote bodies/credentials in diagnostics.
      observed =
          new A2aClient.Observation(
              "UNKNOWN",
              work.remoteTask(),
              work.remoteContext(),
              work.result(),
              "External operation outcome is unconfirmed; the initial send was not replayed.");
    }
    outgoing.report(
        new Outgoing.Report(
            work.id(),
            work.revision(),
            observed.state(),
            observed.task(),
            observed.context(),
            observed.result(),
            observed.error()));
    return true;
  }
}
