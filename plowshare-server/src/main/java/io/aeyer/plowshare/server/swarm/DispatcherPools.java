package io.aeyer.plowshare.server.swarm;

import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.llm.dispatch.LlmException;
import io.aeyer.plowshare.server.llm.dispatch.LlmPool;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The scheduler's view of the dispatcher's pools. Ceilings are read once: pools are fixed at boot.
 */
public final class DispatcherPools implements SwarmScheduler.Pools {

  private static final Logger log = LoggerFactory.getLogger(DispatcherPools.class);

  private final LlmDispatcher dispatcher;
  private final Map<String, Integer> slots = new LinkedHashMap<>();

  public DispatcherPools(LlmDispatcher dispatcher) {
    this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    for (LlmPool pool : dispatcher.pools()) {
      if (pool.swarmSlots() > 0) {
        slots.put(pool.name(), pool.swarmSlots());
      }
    }
  }

  /**
   * Pools with swarm slots that serve {@code specifier}. A specifier the dispatcher refuses —
   * {@link LlmException}, {@code poolsServing}'s own documented refusal for a specifier nothing
   * serves — is served by none: the run waits and is reported overdue rather than thrown out of its
   * loop, and the refusal is logged so the operator sees why. Anything wider is a bug and is left
   * to surface, not logged and swallowed as "no pool can serve".
   */
  @Override
  public List<String> serving(String specifier) {
    try {
      return dispatcher.poolsServing(specifier).stream()
          .map(LlmPool::name)
          .filter(slots::containsKey)
          .toList();
    } catch (LlmException refused) {
      log.warn("swarm: no pool can serve '{}': {}", specifier, refused.toString());
      return List.of();
    }
  }

  @Override
  public int slots(String pool) {
    return slots.getOrDefault(pool, 0);
  }

  @Override
  public List<String> all() {
    return List.copyOf(slots.keySet());
  }
}
