package io.aeyer.plowshare.server.cache;

import static io.aeyer.plowshare.server.cache.CacheTestSupport.limits;
import static io.aeyer.plowshare.server.cache.CacheTestSupport.loaded;
import static io.aeyer.plowshare.server.cache.CacheTestSupport.namespace;
import static io.aeyer.plowshare.server.cache.CacheTestSupport.request;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.cache.CacheTestSupport.HeldExecutor;
import io.aeyer.plowshare.server.cache.CacheTestSupport.ManualTimer;
import io.aeyer.plowshare.server.cache.CacheTestSupport.Value;
import org.junit.jupiter.api.Test;

class SnapshotExistingReadTest {
  @Test
  void missDoesNotElectAndExistingJoinCancellationIsCallerLocal() {
    HeldExecutor executor = new HeldExecutor();
    try (ManualTimer timer = new ManualTimer();
        var cache =
            new BoundedSnapshotCache<Value>(
                namespace(), limits(), executor, timer, Runnable::run)) {
      assertTrue(cache.completeRecovery(cache.beginRecovery()));
      var request = request("existing");
      assertTrue(cache.readExisting(request).isEmpty());
      assertEquals(0, executor.size());
      assertEquals(0, cache.statistics().loads());
      var ordinary = cache.read(request, () -> loaded(request, "shared"));
      var joined = cache.readExisting(request).orElseThrow();
      joined.cancel();
      assertFalse(ordinary.result().toCompletableFuture().isDone());
      assertEquals(1, executor.size());
      executor.runNext();
      assertEquals(new Value("shared"), ordinary.result().toCompletableFuture().join().value());
      var hit = cache.readExisting(request).orElseThrow();
      assertEquals(new Value("shared"), hit.result().toCompletableFuture().join().value());
      assertEquals(0, executor.size());
    }
  }
}
