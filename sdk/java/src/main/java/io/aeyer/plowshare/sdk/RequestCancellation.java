package io.aeyer.plowshare.sdk;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A caller-owned cancellation signal. A new signal may be shared deliberately by several calls;
 * cancellation detaches only those callers and never replays or cancels remote mutations.
 */
public final class RequestCancellation {
  private final AtomicBoolean cancelled = new AtomicBoolean();
  private final ConcurrentMap<Object, Runnable> listeners = new ConcurrentHashMap<>();

  public boolean isCancelled() {
    return cancelled.get();
  }

  public void cancel() {
    if (!cancelled.compareAndSet(false, true)) return;
    for (var entry : listeners.entrySet()) {
      if (listeners.remove(entry.getKey(), entry.getValue())) entry.getValue().run();
    }
  }

  AutoCloseable register(Runnable listener) {
    Object key = new Object();
    listeners.put(key, listener);
    if (cancelled.get() && listeners.remove(key, listener)) listener.run();
    return () -> listeners.remove(key, listener);
  }
}
