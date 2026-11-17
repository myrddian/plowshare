package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.server.access.OperationAuthority;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.faults.Faults;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ExternalConversationAdmissionTest {
  @Test
  void expiredExIsRefusedBeforeAnyJobLinkageOrRuntimeCall() {
    var authority = mock(OperationAuthority.class);
    var runtime = mock(JobRuntime.class);
    var transcript = mock(Transcript.class);
    try (var jobs = new JobStore(authority, runtime)) {
      var expired =
          assertThrows(
              ExternalConversationUnavailableException.class,
              () ->
                  ExternalConversationAdmission.within(
                      Duration.ofNanos(1),
                      () ->
                          jobs.submit(
                              mock(AgentDefinition.class),
                              "work",
                              Home.of("house"),
                              null,
                              Budget.of(10),
                              transcript,
                              Origin.TURN,
                              ignored -> {})));
      verifyNoInteractions(authority, runtime, transcript);
      var fault = Faults.of(expired);
      assertEquals(Code.ARCHIVE_UNAVAILABLE, fault.code());
      assertFalse(fault.detail().contains("Nothing was read"));
      assertFalse(fault.detail().contains("nothing was written"));
    }
    // The context is thread-confined and restored, not a permanent ban on subsequent submissions.
    ExternalConversationAdmission.requireAdmission();
  }

  @Test
  void admittedWorkMayPhysicallyStartAfterCallerExpiryWithoutANewRejectionBranch()
      throws Exception {
    var runtime = mock(JobRuntime.class);
    var definition = mock(AgentDefinition.class);
    when(definition.name()).thenReturn("worker");
    when(definition.maxTurns()).thenReturn(8);
    var transcript = mock(Transcript.class);
    var linked = new CompletableFuture<Void>();
    var release = new CompletableFuture<Void>();
    var ran = new CompletableFuture<Void>();
    doAnswer(
            call -> {
              linked.complete(null);
              release.join();
              return null;
            })
        .when(transcript)
        .startedAs(any());
    when(runtime.run(
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            anyBoolean()))
        .thenAnswer(
            ignored -> {
              ran.complete(null);
              return new Outcome(Outcome.Ending.ANSWERED, "done", 0, 0, "");
            });
    try (var jobs = new JobStore(runtime)) {
      var accepted = new CompletableFuture<String>();
      Thread submitter =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      accepted.complete(
                          ExternalConversationAdmission.within(
                              Duration.ofMillis(100),
                              () ->
                                  jobs.submit(
                                      definition,
                                      "work",
                                      Home.of("house"),
                                      null,
                                      Budget.of(10),
                                      transcript,
                                      Origin.TURN,
                                      ignored -> {},
                                      null,
                                      List.of(),
                                      "alice",
                                      true)));
                    } catch (Throwable failed) {
                      accepted.completeExceptionally(failed);
                    }
                  });
      try {
        linked.get(2, TimeUnit.SECONDS);
        Thread.sleep(130);
        assertFalse(ran.isDone());
      } finally {
        release.complete(null);
      }
      assertTrue(accepted.get(2, TimeUnit.SECONDS).startsWith("job_"));
      ran.get(2, TimeUnit.SECONDS);
      submitter.join(2000);
      assertFalse(submitter.isAlive());
    }
  }
}
