package io.aeyer.plowshare.server.relay;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * An owning API's one-way acceptance adapter, not a new execution runtime. Implementations enforce
 * live destination authority, grants and fences, and use the delivery UUID as request identity.
 * Neither acceptance nor a broker fence certifies recipient completion or exactly-once effects.
 */
public interface RelayReceiver {
  /** Read-only preflight; a Refused exception guarantees no dispatch has been attempted. */
  void require(Request request);

  /**
   * Called only after durable dispatch intent. Return a receipt, a definite no-effect refusal, or
   * uncertainty. Exceptions and missing results are uncertain. Acceptance should be bounded;
   * exceeding the broker lease leaves work requiring reconciliation rather than automatic replay.
   */
  Result dispatch(Request request);

  /**
   * Read-only reconciliation through the owning API. Absence is inconclusive, especially after
   * retention; it must never cause a new dispatch. Exceptions leave broker state untouched.
   */
  Optional<RelayDeliveries.Resolution> inspect(Request request);

  /** Explicit opt-in for receivers backed by the durable, fenced handler runtime. */
  default boolean handlesScript() {
    return false;
  }

  record Request(RelayProjectFiles.Access access, RelayDeliveries.Delivery delivery) {
    public Request {
      Objects.requireNonNull(access, "access");
      Objects.requireNonNull(delivery, "delivery");
      if (!new Relay.ProjectScope(access.projectId())
          .equals(delivery.key().subscription().topic().scope()))
        throw new IllegalArgumentException("receiver request differs from its authorized project");
      if (delivery.state() != RelayDeliveries.State.CLAIMED
          && delivery.state() != RelayDeliveries.State.DISPATCHING
          && delivery.state() != RelayDeliveries.State.UNCERTAIN)
        throw new IllegalArgumentException(
            "receiver request has no pending dispatch or uncertainty");
    }

    public UUID identity() {
      return delivery.key().id();
    }
  }

  sealed interface Result permits Settled, Uncertain {}

  record Settled(RelayDeliveries.Resolution resolution) implements Result {
    public Settled {
      Objects.requireNonNull(resolution, "resolution");
    }
  }

  record Uncertain(String code) implements Result {
    public Uncertain {
      code = RelayValues.name(code, "uncertainty code");
    }
  }

  /** A stable refusal code without private caller/source/payload diagnostics. */
  final class Refused extends RuntimeException {
    private final String code;

    public Refused(String code) {
      super(RelayValues.name(code, "receiver refusal"));
      this.code = code;
    }

    public String code() {
      return code;
    }
  }
}
