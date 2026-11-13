package io.aeyer.plowshare.server.board;

import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayPublisherAccounts;
import java.util.Objects;
import java.util.UUID;

/** Bridge-only native messaging boundary; forum storage never resolves private requester state. */
public interface BoardNativeRequests {
  BoardNativeRequests NONE =
      new BoardNativeRequests() {
        public Principal actor(
            Relay.Publication publication,
            String project,
            String channel,
            String receiver,
            RelayPublisherAccounts.Execution execution,
            boolean inspection) {
          throw new Unavailable();
        }

        public Reply reply(BoardBridgeRepository.Pending pending) {
          return Reply.UNAVAILABLE;
        }

        public Refusal refuse(UUID delivery, BoardBridge.Agent actor) {
          throw new Unavailable();
        }

        public boolean refused(BoardBridge.Agent actor) {
          return false;
        }

        public boolean accepted(BoardBridge.Agent actor) {
          return false;
        }

        public boolean publishNextOutcome() {
          return false;
        }
      };

  /** Resolves an immutable execution export, checking current route policy before a new effect. */
  Principal actor(
      Relay.Publication publication,
      String project,
      String channel,
      String receiver,
      RelayPublisherAccounts.Execution execution,
      boolean inspection);

  /** Native reply publication and board outbox deletion join the caller's owning transaction. */
  Reply reply(BoardBridgeRepository.Pending pending);

  record Principal(BoardBridge.Agent actor, boolean authorized) {
    public Principal {
      Objects.requireNonNull(actor);
    }
  }

  enum Reply {
    PUBLISHED,
    UNAVAILABLE
  }

  /** Fixed safe-code debt is retained only after committed broker dispatch intent. */
  Refusal refuse(UUID delivery, BoardBridge.Agent actor);

  boolean refused(BoardBridge.Agent actor);

  enum Refusal {
    RECORDED,
    EFFECT_ACCEPTED
  }

  boolean accepted(BoardBridge.Agent actor);

  boolean publishNextOutcome();

  /**
   * Definitive absence/revocation/lifecycle refusal; infrastructure failures must still propagate.
   */
  final class Unavailable extends RuntimeException {
    public Unavailable() {
      super("The retained native board requester is unavailable");
    }
  }
}
