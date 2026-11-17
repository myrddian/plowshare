package io.aeyer.plowshare.server.access;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectRole;
import io.aeyer.plowshare.server.auth.AdminStore;
import io.aeyer.plowshare.server.reads.ReadDeadline;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ExternalConversationProjectAdmissionTest {
  private final ProjectMembers members = mock(ProjectMembers.class);
  private final ProjectAuthorization authorization =
      new ProjectAuthorization(
          mock(ResourceScopeRepository.class), members, mock(AdminStore.class));

  @Test
  void evidenceIsOpaqueAndOneUseForTheExactProjectAccountSessionOperationAndThread()
      throws Exception {
    for (var constructor : ProjectAuthorization.ExternalAdmission.class.getDeclaredConstructors())
      assertTrue(Modifier.isPrivate(constructor.getModifiers()));
    ReadDeadline.within(
        Duration.ofSeconds(2),
        () -> {
          try (var evidence =
              authorization.requireExternalConversation(
                  "agent.run.ex", AccessRequest.project("house"), "alice", "socket")) {
            verify(members, times(1)).requireRole("house", "alice", ProjectRole.CONTRIBUTOR);
            assertFalse(evidence.consume("other", "alice", "socket", "agent.run.ex"));
            assertFalse(evidence.consume("house", "bob", "socket", "agent.run.ex"));
            assertFalse(evidence.consume("house", "alice", "other", "agent.run.ex"));
            assertFalse(evidence.consume("house", "alice", "socket", "conversation.open.ex"));
            var otherThread = new CompletableFuture<Boolean>();
            Thread.ofVirtual()
                .start(
                    () ->
                        otherThread.complete(
                            evidence.consume("house", "alice", "socket", "agent.run.ex")));
            assertFalse(otherThread.orTimeout(2, TimeUnit.SECONDS).join());
            assertTrue(evidence.consume("house", "alice", "socket", "agent.run.ex"));
            assertFalse(evidence.consume("house", "alice", "socket", "agent.run.ex"));
          }
          return true;
        });
  }

  @Test
  void sqlDeadlineDoesNotEndTheExplicitRequestScopeAndClosingStillInvalidatesEvidence() {
    var scoped =
        ReadDeadline.within(
            Duration.ofNanos(1),
            () ->
                authorization.requireExternalConversation(
                    "agent.run.ex", AccessRequest.project("house"), "alice", "socket"));
    try (scoped) {
      assertTrue(scoped.consume("house", "alice", "socket", "agent.run.ex"));
    }
    assertFalse(scoped.consume("house", "alice", "socket", "agent.run.ex"));
  }

  @Test
  void anotherExplicitRequestScopeCannotConsumeTheOuterScopesEvidence() {
    var outer =
        authorization.requireExternalConversation(
            "agent.run.ex", AccessRequest.project("house"), "alice", "socket");
    try (outer) {
      var inner =
          authorization.requireExternalConversation(
              "agent.run.ex", AccessRequest.project("house"), "alice", "socket");
      try (inner) {
        assertFalse(outer.consume("house", "alice", "socket", "agent.run.ex"));
        assertTrue(inner.consume("house", "alice", "socket", "agent.run.ex"));
      }
      assertTrue(outer.consume("house", "alice", "socket", "agent.run.ex"));
    }
    assertFalse(outer.consume("house", "alice", "socket", "agent.run.ex"));
  }
}
