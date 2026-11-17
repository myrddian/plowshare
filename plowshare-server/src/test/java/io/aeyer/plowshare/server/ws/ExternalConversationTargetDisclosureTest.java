package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Envelope;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.protocol.transport.ConcurrentDispatch;
import io.aeyer.plowshare.server.access.AccessRequest;
import io.aeyer.plowshare.server.access.ProjectAuthorization;
import io.aeyer.plowshare.server.access.ResourceScopeRepository;
import io.aeyer.plowshare.server.agents.ExternalConversationUnavailableException;
import io.aeyer.plowshare.server.api.ConversationView;
import io.aeyer.plowshare.server.api.StartedJob;
import io.aeyer.plowshare.server.archive.ArchiveUnavailableException;
import io.aeyer.plowshare.server.archive.ConversationAliasProperties;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectRole;
import io.aeyer.plowshare.server.auth.AdminStore;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.faults.Faults;
import io.aeyer.plowshare.server.personal.PersonalAccess;
import io.aeyer.plowshare.server.reads.ReadAdmission;
import io.aeyer.plowshare.server.relay.RelayReadBudget;
import io.aeyer.plowshare.server.telemetry.FailureReporter;
import io.aeyer.plowshare.server.testing.LogicalSqlWorkBudget;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;

class ExternalConversationTargetDisclosureTest {
  private static final class Target {
    final ResourceScopeRepository scopes = mock(ResourceScopeRepository.class);
    final ProjectMembers members = mock(ProjectMembers.class);
    final ConversationStore rows = mock(ConversationStore.class);
    final FailureReporter failures = mock(FailureReporter.class);
    final AtomicReference<String> project = new AtomicReference<>("house");
    final AtomicBoolean unavailable = new AtomicBoolean();
    final AtomicInteger houseChecks = new AtomicInteger();
    final CompletableFuture<Void> postHandlerChecked = new CompletableFuture<>();
    final ArchiveUnavailableException outage =
        new ArchiveUnavailableException("private resource diagnostic", null);
    final ProjectAuthorization authorization;

    Target(boolean originalAuthorized) {
      authorization = new ProjectAuthorization(scopes, members, mock(AdminStore.class));
      when(scopes.projects(any()))
          .thenAnswer(
              call -> {
                var resource = call.<AccessRequest.Resource>getArgument(0);
                assertEquals(ResourceScopeRepository.Kind.CONVERSATION, resource.kind());
                assertEquals("cnv_stable", resource.id());
                if (unavailable.get()) throw outage;
                return List.of(project.get());
              });
      doAnswer(
              call -> {
                String selected = call.getArgument(0);
                if (project.get().equals("renamed")) {
                  if (selected.equals("renamed") && !originalAuthorized)
                    throw new CallerFault("Original project work was withdrawn");
                  if (selected.equals("house") && originalAuthorized)
                    throw new CallerFault("Replacement project work is unrelated");
                }
                if (selected.equals("house") && houseChecks.incrementAndGet() == 2)
                  postHandlerChecked.complete(null);
                return null;
              })
          .when(members)
          .requireRole(anyString(), eq("alice"), eq(ProjectRole.CONTRIBUTOR));
    }

    FrameRouter router(
        String operation, FrameHandler handler, ExternalConversationCompletions completions) {
      var router =
          new FrameRouter(
              Map.of(operation, handler, "example.run", (payload, asking) -> Outcome.ok()),
              null,
              ReadAdmission.direct(),
              failures,
              new ScopedExternalConversationRequestWork(
                  new LogicalSqlWorkBudget(),
                  new ConversationAliasProperties(Duration.ofSeconds(2), Duration.ofSeconds(1)),
                  completions));
      router.useAuthorization(authorization);
      router.usePersonalAccess(new PersonalAccess(rows));
      return router;
    }
  }

  private static Outcome success(String operation) {
    return FrameTypes.CONVERSATION_OPEN_EX.equals(operation)
        ? Outcome.ok(new ConversationView("cnv_stable", "house", 20, 0, null, false, false, null))
        : new Outcome(
            Code.ACCEPTED, null, new StartedJob("job_started", "worker", null, "cnv_stable"));
  }

  private static Envelope request(String operation) {
    return new Envelope("ex", operation, Envelope.CURRENT_VERSION, Map.of("project", "house"));
  }

  @ParameterizedTest
  @CsvSource({
    "conversation.open.ex,false",
    "conversation.open.ex,true",
    "agent.run.ex,false",
    "agent.run.ex,true"
  })
  void postHandlerUsesTheActualTargetAfterRenameAndOldNameReuse(
      String operation, boolean originalAuthorized) {
    var target = new Target(originalAuthorized);
    var calls = new AtomicInteger();
    var router =
        target.router(
            operation,
            (payload, asking) -> {
              calls.incrementAndGet();
              target.project.set("renamed");
              return success(operation);
            },
            mock(ExternalConversationCompletions.class));
    var result =
        router
            .routeAsync(request(operation), new Asking("session", "alice", "socket"))
            .toCompletableFuture()
            .join();
    if (originalAuthorized) {
      assertEquals(success(operation).code(), result.code());
      assertEquals(success(operation).payload(), result.payload());
    } else {
      assertEquals(Code.BAD_REQUEST, result.code());
      assertNull(result.payload());
    }
    assertEquals(1, calls.get());
    verify(target.members).requireRole("renamed", "alice", ProjectRole.CONTRIBUTOR);
    verify(target.scopes, times(1)).projects(any());
    verifyNoInteractions(target.rows);
  }

  @ParameterizedTest
  @CsvSource({
    "conversation.open.ex,false",
    "conversation.open.ex,true",
    "agent.run.ex,false",
    "agent.run.ex,true"
  })
  void queuedSuccessCannotUseTheReplacementProjectsWorkRole(
      String operation, boolean originalAuthorized) throws Exception {
    queuedScenario(operation, originalAuthorized, false);
  }

  @Test
  void queuedProjectionOutageWithholdsMetadataAndClosesWithoutReexecutingTheMutation()
      throws Exception {
    queuedScenario(FrameTypes.CONVERSATION_OPEN_EX, true, true);
  }

  private void queuedScenario(
      String operation, boolean originalAuthorized, boolean availabilityFailure) throws Exception {
    var target = new Target(originalAuthorized);
    var blocked = new CompletableFuture<Void>();
    var release = new CompletableFuture<Void>();
    var executions = new AtomicInteger();
    try (var completions = new BoundedExternalConversationCompletions(Duration.ofSeconds(2), 2)) {
      var router =
          target.router(
              operation,
              (payload, asking) -> {
                executions.incrementAndGet();
                return success(operation);
              },
              completions);
      try (var fixture = new ConcurrentEventChannelTest.Fixture(false, router)) {
        fixture.send("select", ConcurrentDispatch.TYPE, Map.of("mode", ConcurrentDispatch.MODE));
        fixture.reply();
        var first = new AtomicBoolean(true);
        fixture.beforeWrite =
            () -> {
              if (first.compareAndSet(true, false)) {
                blocked.complete(null);
                release.orTimeout(2, TimeUnit.SECONDS).join();
              }
            };
        fixture.send("blocker", "example.run", Map.of());
        blocked.get(2, TimeUnit.SECONDS);
        fixture.send("ex", operation, Map.of("project", "house"));
        target.postHandlerChecked.get(2, TimeUnit.SECONDS);
        if (availabilityFailure) target.unavailable.set(true);
        else target.project.set("renamed");
        release.complete(null);
        assertEquals(
            "blocker", FrameJson.answering().readTree(fixture.reply()).path("id").asText());
        if (originalAuthorized && !availabilityFailure) {
          var reply = FrameJson.answering().readTree(fixture.reply());
          assertEquals("ex", reply.path("id").asText());
          assertEquals(
              "cnv_stable",
              reply
                  .path("payload")
                  .path("payload")
                  .path(FrameTypes.CONVERSATION_OPEN_EX.equals(operation) ? "id" : "conversation")
                  .asText());
        } else {
          verify(fixture.socket, timeout(2000)).close(any(CloseStatus.class));
          assertFalse(fixture.open.get());
          assertNull(fixture.received.poll(100, TimeUnit.MILLISECONDS));
        }
        if (!availabilityFailure)
          verify(target.members).requireRole("renamed", "alice", ProjectRole.CONTRIBUTOR);
        verify(target.scopes, times(2)).projects(any());
        assertEquals(1, executions.get());
        verifyNoInteractions(target.rows);
      } finally {
        release.complete(null);
      }
    }
  }

  @Test
  void postHandlerScopeOutageAfterSuccessKeepsUncertaintyAndOneFailureObservation() {
    var target = new Target(true);
    var committed = new AtomicInteger();
    var router =
        target.router(
            FrameTypes.CONVERSATION_OPEN_EX,
            (payload, asking) -> {
              committed.incrementAndGet();
              target.unavailable.set(true);
              return success(FrameTypes.CONVERSATION_OPEN_EX);
            },
            mock(ExternalConversationCompletions.class));
    var outcome =
        router
            .routeAsync(
                request(FrameTypes.CONVERSATION_OPEN_EX), new Asking("session", "alice", "socket"))
            .toCompletableFuture()
            .join();
    assertEquals(Code.ARCHIVE_UNAVAILABLE, outcome.code());
    assertNull(outcome.payload());
    assertTrue(outcome.said().contains("may have committed"));
    assertTrue(outcome.said().contains("do not replay"));
    assertFalse(outcome.said().contains("Nothing was read"));
    assertFalse(outcome.said().contains("nothing was written"));
    assertFalse(outcome.said().contains("private resource diagnostic"));
    assertEquals(1, committed.get());
    var observed = ArgumentCaptor.forClass(Throwable.class);
    verify(target.failures, times(1))
        .report(eq(FailureReporter.Source.WEBSOCKET), observed.capture());
    assertSame(target.outage, observed.getValue());
    verify(target.scopes, times(1)).projects(any());
  }

  @Test
  void postHandlerAndQueuedSessionOutagesShareTheSameNarrowUncertaintyPolicy() {
    var target = new Target(true);
    var committed = new AtomicInteger();
    var router =
        target.router(
            FrameTypes.CONVERSATION_OPEN_EX,
            (payload, asking) -> {
              committed.incrementAndGet();
              return success(FrameTypes.CONVERSATION_OPEN_EX);
            },
            mock(ExternalConversationCompletions.class));
    var asking =
        new Asking(
            "session",
            "alice",
            "socket",
            RelayReadBudget.LEGACY,
            () -> {
              throw target.outage;
            });
    var result =
        router
            .routeAsync(request(FrameTypes.CONVERSATION_OPEN_EX), asking)
            .toCompletableFuture()
            .join();
    assertEquals(Code.ARCHIVE_UNAVAILABLE, result.code());
    assertNull(result.payload());
    assertTrue(result.said().contains("do not replay"));
    assertFalse(result.said().contains("Nothing was read"));
    assertEquals(1, committed.get());
    verify(target.failures, times(1)).report(FailureReporter.Source.WEBSOCKET, target.outage);
    var failure =
        assertThrows(
            ExternalConversationUnavailableException.class,
            () ->
                router.requireExternalDisclosure(
                    "", asking, success(FrameTypes.CONVERSATION_OPEN_EX)));
    assertSame(target.outage, failure.getCause());
    assertTrue(Faults.of(failure).detail().contains("do not replay"));
    verifyNoInteractions(target.scopes);
  }

  @Test
  void failedAndBodylessOutcomesDoNotProbeAResource() throws Exception {
    var target = new Target(true);
    var router =
        target.router(
            FrameTypes.CONVERSATION_OPEN_EX,
            (payload, asking) -> Outcome.ok(),
            mock(ExternalConversationCompletions.class));
    String frame =
        FrameJson.answering().writeValueAsString(request(FrameTypes.CONVERSATION_OPEN_EX));
    for (var outcome :
        List.of(
            Outcome.ok(),
            Outcome.failed(Code.ARCHIVE_UNAVAILABLE, "unavailable"),
            new Outcome(Code.BAD_REQUEST, "refused", Map.of("diagnostic", true))))
      router.requireExternalDisclosure(frame, new Asking("session", "alice", "socket"), outcome);
    verifyNoInteractions(target.scopes, target.rows);
  }
}
