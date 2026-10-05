package io.aeyer.plowshare.server.outgoing;

import io.aeyer.plowshare.protocol.Outgoing;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Account-owned durable external-work outbox. It never calls peers or replays uncertain sends. */
public interface OutgoingRepository {
  Outgoing.Peers peers(String account, String project);

  void advertise(
      String account,
      String project,
      List<String> peers,
      Map<String, io.aeyer.plowshare.protocol.AgentCard> cards);

  /** Locks the account/request identity and rejects receipt reuse with different work. */
  Outgoing.Work send(String account, Outgoing.Send send);

  Outgoing.Work get(String account, UUID id);

  /**
   * Initial sends are claimed once; only remote observations may be reclaimed after lease expiry.
   */
  Outgoing.Claimed claim(String account, String project, List<String> peers, String worker);

  /** Fenced by claimant, revision and immutable remote task/context identities. */
  Outgoing.Work report(String account, String worker, Outgoing.Report report);

  Outgoing.Work cancel(String account, UUID id);
}
