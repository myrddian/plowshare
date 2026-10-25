package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.protocol.transport.ConcurrentDispatch;
import io.aeyer.plowshare.server.access.ProjectAuthorization;
import io.aeyer.plowshare.server.access.ResourceScopeRepository;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectRole;
import io.aeyer.plowshare.server.auth.AdminStore;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.socket.CloseStatus;

/** Real router/authorization and transport, with no PostgreSQL needed for scheduling semantics. */
class DispatcherReadAuthorizationTest {
  @ParameterizedTest
  @ValueSource(strings = {"usage.calls", "provider.connection.list"})
  void older_segmented_clients_recheck_membership_after_an_overlapping_read(String type)
      throws Exception {
    var members = mock(ProjectMembers.class);
    var revoked = new AtomicBoolean();
    doAnswer(
            call -> {
              if (revoked.get()) throw new CallerFault("Membership revoked");
              return null;
            })
        .when(members)
        .requireRole("research", "alice", ProjectRole.VIEWER);
    var authority =
        new ProjectAuthorization(
            mock(ResourceScopeRepository.class), members, mock(AdminStore.class));
    var entered = new CompletableFuture<Void>();
    var result = new CompletableFuture<Outcome>();
    var calls = new AtomicInteger();
    FrameHandler read =
        (p, a) -> {
          calls.incrementAndGet();
          entered.complete(null);
          return result.orTimeout(5, TimeUnit.SECONDS).join();
        };
    var router =
        new FrameRouter(
            Map.of(type, read, "information.read", (p, a) -> Outcome.ok(Map.of("done", true))));
    router.useAuthorization(authority);
    try (var fixture = new ConcurrentEventChannelTest.Fixture(true, router)) {
      // No dispatch handshake: this uses EventRequests and synchronous legacy routing.
      fixture.send("slow", type, Map.of("project", "research"));
      entered.get(5, TimeUnit.SECONDS);
      fixture.send("fast", "information.read", Map.of("project", "research"));
      assertEquals("fast", FrameJson.answering().readTree(fixture.reply()).path("id").asText());
      assertFalse(result.isDone());
      revoked.set(true);
      result.complete(Outcome.ok(Map.of("protected", "private result")));
      verify(fixture.socket, timeout(5000)).close(any(CloseStatus.class));
      assertTrue(fixture.received.stream().noneMatch(frame -> frame.contains("private result")));
      assertEquals(1, calls.get());
    } finally {
      result.complete(Outcome.ok());
    }
  }

  @ParameterizedTest
  @CsvSource({
    "false,usage.calls", "true,usage.calls",
    "false,conversation.list", "true,conversation.list",
    "false,provider.connection.list", "true,provider.connection.list",
    "false,application.files", "true,application.files"
  })
  void concurrent_reads_keep_live_membership_admission_and_completion_fences(
      boolean segmented, String type) throws Exception {
    var members = mock(ProjectMembers.class);
    var revoked = new AtomicBoolean();
    doAnswer(
            call -> {
              if (revoked.get()) throw new CallerFault("Membership revoked");
              return null;
            })
        .when(members)
        .requireRole("research", "alice", ProjectRole.VIEWER);
    var authority =
        new ProjectAuthorization(
            mock(ResourceScopeRepository.class), members, mock(AdminStore.class));
    var entered = new CompletableFuture<Void>();
    var result = new CompletableFuture<Outcome>();
    var calls = new AtomicInteger();
    var read =
        new FrameHandler() {
          public Outcome handle(Map<String, Object> payload, Asking asking) {
            throw new AssertionError("Use async routing");
          }

          public CompletionStage<Outcome> handleAsync(Map<String, Object> payload, Asking asking) {
            calls.incrementAndGet();
            entered.complete(null);
            return result;
          }
        };
    var router =
        new FrameRouter(
            Map.of(type, read, "information.read", (p, a) -> Outcome.ok(Map.of("done", true))));
    router.useAuthorization(authority);
    try (var fixture = new ConcurrentEventChannelTest.Fixture(segmented, router)) {
      fixture.send("select", ConcurrentDispatch.TYPE, Map.of("mode", ConcurrentDispatch.MODE));
      fixture.reply();
      fixture.send("slow", type, Map.of("project", "research"));
      entered.get(5, TimeUnit.SECONDS);
      fixture.send("fast", "information.read", Map.of("project", "research"));
      assertEquals("fast", FrameJson.answering().readTree(fixture.reply()).path("id").asText());
      assertFalse(result.isDone(), "Independent read must finish before the held read");
      revoked.set(true);
      result.complete(Outcome.ok(Map.of("protected", "private result")));
      var refused = FrameJson.answering().readTree(fixture.reply());
      assertEquals("slow", refused.path("id").asText());
      assertEquals("BAD_REQUEST", refused.path("payload").path("code").asText());
      assertFalse(refused.toString().contains("private result"));
      // Finite scheduling is never a retained allow decision: a subsequent request is refused
      // before its handler too, even on the same already authenticated connection.
      fixture.send("denied", type, Map.of("project", "research"));
      var denied = FrameJson.answering().readTree(fixture.reply());
      assertEquals("denied", denied.path("id").asText());
      assertEquals("BAD_REQUEST", denied.path("payload").path("code").asText());
      assertEquals(1, calls.get());
      verify(members, atLeast(3)).requireRole("research", "alice", ProjectRole.VIEWER);
    } finally {
      result.complete(Outcome.ok());
    }
  }
}
