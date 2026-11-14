package io.aeyer.plowshare.server.relay;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Thread-safe set of at most {@code capacity} keys; the least recently used key is evicted. */
final class BoundedRecents<K> {
  private final Map<K, Boolean> keys;

  BoundedRecents(int capacity) {
    if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
    this.keys =
        new LinkedHashMap<>(16, 0.75f, true) {
          @Override
          protected boolean removeEldestEntry(Map.Entry<K, Boolean> eldest) {
            return size() > capacity;
          }
        };
  }

  synchronized boolean contains(K key) {
    return keys.get(Objects.requireNonNull(key)) != null;
  }

  synchronized void add(K key) {
    keys.put(Objects.requireNonNull(key), Boolean.TRUE);
  }

  synchronized void remove(K key) {
    keys.remove(Objects.requireNonNull(key));
  }
}
