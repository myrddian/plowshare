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
  void deadlineAndScopeEndInvalidateRetainedEvidence() {
    var expired =
        ReadDeadline.within(
            Duration.ofNanos(1),
            () ->
                authorization.requireExternalConversation(
                    "agent.run.ex", AccessRequest.project("house"), "alice", "socket"));
    assertFalse(expired.consume("house", "alice", "socket", "agent.run.ex"));
    var leftScope =
        ReadDeadline.within(
            Duration.ofSeconds(2),
            () ->
                authorization.requireExternalConversation(
                    "agent.run.ex", AccessRequest.project("house"), "alice", "socket"));
    assertFalse(leftScope.consume("house", "alice", "socket", "agent.run.ex"));
    var scoped =
        authorization.requireExternalConversation(
            "agent.run.ex", AccessRequest.project("house"), "alice", "socket");
    scoped.close();
    assertFalse(scoped.consume("house", "alice", "socket", "agent.run.ex"));
  }
}
