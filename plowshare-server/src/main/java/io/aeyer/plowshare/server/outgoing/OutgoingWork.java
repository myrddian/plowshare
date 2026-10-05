package io.aeyer.plowshare.server.outgoing;

import io.aeyer.plowshare.protocol.Outgoing;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.*;
import org.springframework.stereotype.Service;

/** Application facade for the durable outbox; only the repository owns claims and SQL. */
@Service
public final class OutgoingWork {
  private final OutgoingRepository repository;

  public OutgoingWork(OutgoingRepository repository) {
    this.repository = Objects.requireNonNull(repository);
  }

  public static void peer(String peer) {
    if (peer == null || !peer.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}"))
      throw new CallerFault("outgoing peer must be a configured name, not a URL");
  }

  public Outgoing.Peers peers(String account, String project) {
    return repository.peers(account, project);
  }

  public void advertise(String account, String project, List<String> peers) {
    advertise(account, project, peers, null);
  }

  public void advertise(
      String account,
      String project,
      List<String> peers,
      Map<String, io.aeyer.plowshare.protocol.AgentCard> cards) {
    repository.advertise(account, project, peers, cards);
  }

  public Outgoing.Work send(String account, Outgoing.Send send) {
    return repository.send(account, send);
  }

  public Outgoing.Work get(String account, UUID id) {
    return repository.get(account, id);
  }

  public Outgoing.Claimed claim(String account, String project, List<String> peers, String worker) {
    return repository.claim(account, project, peers, worker);
  }

  public Outgoing.Work report(String account, String worker, Outgoing.Report report) {
    return repository.report(account, worker, report);
  }

  public Outgoing.Work cancel(String account, UUID id) {
    return repository.cancel(account, id);
  }
}
