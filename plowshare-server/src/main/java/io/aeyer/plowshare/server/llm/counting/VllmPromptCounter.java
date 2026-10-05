package io.aeyer.plowshare.server.llm.counting;

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
    Observation post(int input, Duration remaining) throws Exception;
  }

  /** A transport has validated this response before it can affect the count or cache. */
  public record Observation(Long count, String model) {
    public Observation {
      if (count == null || count < 0)
        throw new IllegalArgumentException("Nonnegative token count required");
      if (model != null
          && (model.isBlank()
              || !model.equals(model.strip())
              || model.length() > 1024
              || model.codePoints().anyMatch(Character::isISOControl)))
        throw new IllegalArgumentException("Invalid counted model");
    }
  }

  /** Cache identities are SHA-256 digests, never serialized request bodies or credentials. */
  public record Inputs(List<String> digests, String configurationDigest) {
    public Inputs {
      digests = List.copyOf(digests);
      if (digests.isEmpty() || digests.size() > 10000)
        throw new IllegalArgumentException("Bounded counter inputs required");
      for (String value : digests) hash(value);
      hash(configurationDigest);
    }

    private static void hash(String value) {
      if (value == null || !value.matches("[a-f0-9]{64}"))
        throw new IllegalArgumentException("SHA-256 cache identity required");
    }
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
      Inputs inputs,
      UsageAttribution owner,
      CountingProperties settings,
      Exchange exchange) {
    long start = System.nanoTime();
    try {
      String fingerprint = inputs.configurationDigest();
      String key =
          digest(
              mapper.writeValueAsBytes(List.of(fingerprint, pool, model, owner, inputs.digests())));
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
        for (int input = 0; input < inputs.digests().size(); input++) {
          long remaining = settings.getTimeout().toNanos() - (System.nanoTime() - start);
          if (remaining <= 0) return PromptCount.unknown(pool, model, "counter_timeout");
          Observation response = exchange.post(input, Duration.ofNanos(remaining));
          if (response.model() != null && !model.equals(response.model()))
            return PromptCount.unknown(pool, model, "counter_model_mismatch");
          count = Math.addExact(count, response.count());
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
