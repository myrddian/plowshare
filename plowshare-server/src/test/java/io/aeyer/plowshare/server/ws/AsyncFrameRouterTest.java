package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Envelope;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.access.ProjectAuthorization;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AsyncFrameRouterTest {
  private FrameHandler deferred(CompletionStage<Outcome> answer) {
    return new FrameHandler() {
      @Override
      public Outcome handle(Map<String, Object> payload, Asking asking) {
        throw new AssertionError("Async route must use the completion contract");
      }

      @Override
      public CompletionStage<Outcome> handleAsync(Map<String, Object> payload, Asking asking) {
        return answer;
      }
    };
  }

  @Test
  void asynchronous_authority_revocation_is_checked_before_disclosure() throws Exception {
    var answer = new CompletableFuture<Outcome>();
    var authority = mock(ProjectAuthorization.class);
    var router = new FrameRouter(Map.of("relay.topics", deferred(answer)));
    router.useAuthorization(authority);
    var outcome =
        router.routeAsync(
            new Envelope(
                "id", "relay.topics", Envelope.CURRENT_VERSION, Map.of("project", "research")),
            new Asking("session", "alice", "socket"));
    assertFalse(outcome.toCompletableFuture().isDone());
    doThrow(new CallerFault("Membership revoked"))
        .when(authority)
        .require(anyString(), any(), anyString());
    answer.complete(Outcome.ok(Map.of("protected", "content")));
    var result = outcome.toCompletableFuture().get(5, TimeUnit.SECONDS);
    assertEquals(Code.BAD_REQUEST, result.code());
    assertNull(result.payload());
  }

  @Test
  void deferred_failures_use_the_same_fault_mapping_and_explicit_null_payload_success()
      throws Exception {
    var answer = new CompletableFuture<Outcome>();
    var router = new FrameRouter(Map.of("example.read", deferred(answer)));
    var outcome =
        router.routeAsync(
            new Envelope("id", "example.read", Envelope.CURRENT_VERSION, Map.of()),
            new Asking("session"));
    answer.completeExceptionally(new CallerFault("Invalid input"));
    assertEquals(Code.BAD_REQUEST, outcome.toCompletableFuture().get(5, TimeUnit.SECONDS).code());
    var empty =
        new FrameRouter(
            Map.of(
                "example.read",
                deferred(CompletableFuture.completedFuture(new Outcome(Code.OK, null, null)))));
    assertEquals(
        Code.OK,
        empty
            .routeAsync(
                new Envelope("id", "example.read", Envelope.CURRENT_VERSION, Map.of()),
                new Asking("session"))
            .toCompletableFuture()
            .get(5, TimeUnit.SECONDS)
            .code());
  }
}
