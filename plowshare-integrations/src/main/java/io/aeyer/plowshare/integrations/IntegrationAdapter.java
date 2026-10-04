package io.aeyer.plowshare.integrations;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.function.Consumer;

/** External capabilities, without inference, routing or Plowshare credentials. */
public interface IntegrationAdapter extends AutoCloseable {
  record Observation(String identity, JsonNode data) {}

  record Result(String state, JsonNode data) {
    public Result {
      if (!java.util.Set.of("COMPLETED", "FAILED", "REJECTED", "UNKNOWN").contains(state)
          || !data.isObject()) throw new IllegalArgumentException("invalid adapter result");
    }
  }

  void start(Consumer<Observation> observations) throws IOException;

  /** Validate aliases, parameter bounds and permissions without I/O. */
  void validate(String operation, JsonNode arguments);

  Result execute(String operation, JsonNode arguments) throws IOException;

  /**
   * Selected alias-to-reading map. Held thresholds require availability, numeric state/unit and a
   * nonblank epoch. Mark cached readings stale on a gap and change epoch on reconnect; an available
   * unchanged reading may remain current while its subscription is connected.
   */
  JsonNode snapshot();

  /** Reconnect transport only. An action is never replayed. */
  default void maintain() throws IOException {}

  @Override
  void close();
}
