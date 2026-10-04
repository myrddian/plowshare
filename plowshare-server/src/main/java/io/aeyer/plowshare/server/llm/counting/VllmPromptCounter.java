package io.aeyer.plowshare.server.llm.counting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.llm.CountingProperties;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

/** Bounded ephemeral cache. Only a hash of input is retained; no hash is written to accounting. */
public final class VllmPromptCounter {
  @FunctionalInterface
  public interface Exchange {
    JsonNode post(Map<String, Object> body, Duration remaining) throws Exception;
  }

  private record Entry(PromptCount count, long expires) {}

  private final Map<String, Entry> cache = new LinkedHashMap<>(16, .75f, true);
  private final Semaphore slots = new Semaphore(2);
  private final ObjectMapper mapper;
  private String configuration;

  public VllmPromptCounter(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public synchronized void clear() {
    cache.clear();
    configuration = null;
  }

  public PromptCount count(
      String pool,
      String model,
      List<Map<String, Object>> bodies,
      UsageAttribution owner,
      CountingProperties settings,
      Object configurationSalt,
      Exchange exchange) {
    long start = System.nanoTime();
    try {
      String fingerprint =
          digest(mapper.writeValueAsBytes(List.of(settings.fingerprint(), configurationSalt)));
      String key =
          digest(mapper.writeValueAsBytes(List.of(fingerprint, pool, model, owner, bodies)));
      synchronized (this) {
        if (!fingerprint.equals(configuration)) {
          cache.clear();
          configuration = fingerprint;
        }
        Entry hit = cache.get(key);
        if (hit != null && hit.expires > System.nanoTime()) return hit.count.asCached();
        if (hit != null) cache.remove(key);
      }
      if (!slots.tryAcquire()) return PromptCount.unknown(pool, model, "counter_busy");
      try {
        long count = 0;
        for (Map<String, Object> body : bodies) {
          long remaining = settings.getTimeout().toNanos() - (System.nanoTime() - start);
          if (remaining <= 0) return PromptCount.unknown(pool, model, "counter_timeout");
          JsonNode response = exchange.post(body, Duration.ofNanos(remaining));
          if (response.has("model") && !model.equals(response.path("model").asText()))
            return PromptCount.unknown(pool, model, "counter_model_mismatch");
          JsonNode n = response.get("count");
          if (n == null || !n.isIntegralNumber() || !n.canConvertToLong() || n.longValue() < 0)
            return PromptCount.unknown(pool, model, "counter_invalid_response");
          count = Math.addExact(count, n.longValue());
        }
        PromptCount answer =
            new PromptCount(
                count,
                PromptCount.Basis.MEASURED,
                "VLLM_TOKENIZE_0_15_1",
                pool,
                model,
                settings.getRevision(),
                Instant.now(),
                Duration.ofNanos(System.nanoTime() - start).toMillis(),
                false,
                settings.getRevision() == null
                    ? List.of("unverified_template_revision")
                    : List.of());
        synchronized (this) {
          if (settings.getCacheEntries() > 0 && !settings.getCacheTtl().isZero()) {
            cache.put(key, new Entry(answer, System.nanoTime() + settings.getCacheTtl().toNanos()));
            while (cache.size() > settings.getCacheEntries())
              cache.remove(cache.keySet().iterator().next());
          }
        }
        return answer;
      } finally {
        slots.release();
      }
    } catch (Exception failure) {
      if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
      return PromptCount.unknown(pool, model, "counter_unavailable");
    }
  }

  private static String digest(byte[] bytes) throws Exception {
    return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }
}
