package io.aeyer.plowshare.integrations;

import io.aeyer.plowshare.protocol.ExternalResult.IntegrationResult;
import io.aeyer.plowshare.protocol.IntegrationPayload.Arguments;
import io.aeyer.plowshare.protocol.IntegrationPayload.Reading;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** External capabilities without inference, routing or Plowshare credentials. */
public interface IntegrationAdapter extends AutoCloseable {
  /** One validated observation; stable identities must never be reused for different evidence. */
  record Observation(String identity, IntegrationContracts.EventEnvelope data) {
    public Observation {
      IntegrationContracts.identity(identity, 256);
      Objects.requireNonNull(data);
    }
  }

  /** An acknowledgment is delivery evidence, not physical verification. */
  record Result(String state, IntegrationResult data) {
    public Result {
      if (!Set.of("COMPLETED", "FAILED", "REJECTED", "UNKNOWN").contains(state))
        throw new IllegalArgumentException("invalid adapter result state");
      Objects.requireNonNull(data);
    }

    public static Result diagnostic(String state, String diagnostic) {
      return new Result(state, new IntegrationResult(null, null, null, null, diagnostic));
    }
  }

  /** Connect and deliver only sanitized, typed observations. */
  void start(Consumer<Observation> observations) throws IOException;

  /** Validate operation shape, aliases, parameter bounds and permissions without I/O. */
  void validate(String operation, Arguments arguments);

  /** Execute once; uncertain delivery must never cause an automatic action replay. */
  Result execute(String operation, Arguments arguments) throws IOException;

  /** Immutable alias-to-reading snapshot; gaps mark cached evidence stale and change the epoch. */
  Map<String, Reading> snapshot();

  /** Reconnect transport only. An action is never replayed. */
  default void maintain() throws IOException {}

  @Override
  void close();
}
