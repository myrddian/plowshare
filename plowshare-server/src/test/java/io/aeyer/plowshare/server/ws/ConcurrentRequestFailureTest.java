package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.frames.Envelope;
import io.aeyer.plowshare.protocol.transport.PacketBudget;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;

class ConcurrentRequestFailureTest {
  @Test
  void deferred_fatal_error_releases_ownership_and_reaches_uncaught_error_owner() throws Exception {
    var registered = new CompletableFuture<Void>();
    var deferred =
        new CompletableFuture<Void>() {
          @Override
          public CompletableFuture<Void> whenComplete(
              BiConsumer<? super Void, ? super Throwable> callback) {
            var result = super.whenComplete(callback);
            registered.complete(null);
            return result;
          }
        };
    var failed = new CompletableFuture<Void>();
    var reported = new CompletableFuture<Throwable>();
    var budget = new PacketBudget(1024 * 1024);
    try (var dispatcher = new ConcurrentRequests(budget, 1, 1)) {
      var connection =
          dispatcher.connect("fatal", (frame, delivered) -> deferred, () -> failed.complete(null));
      connection.offer(
          "fatal",
          new Envelope("fatal", "relay.topics", Envelope.CURRENT_VERSION, Map.of()),
          "alice");
      registered.get(5, TimeUnit.SECONDS);
      var error = new AssertionError("Controlled deferred failure");
      Thread.ofVirtual()
          .uncaughtExceptionHandler((thread, failure) -> reported.complete(failure))
          .start(() -> deferred.completeExceptionally(new CompletionException(error)));
      failed.get(5, TimeUnit.SECONDS);
      assertSame(error, reported.get(5, TimeUnit.SECONDS));
      dispatcher.stopAdmission();
      dispatcher.drained().toCompletableFuture().get(5, TimeUnit.SECONDS);
      assertEquals(0, budget.heldBytes());
    }
  }
}
