package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.Origin;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RunningConversationsTest {
  @Test
  void a_question_waits_for_close_callbacks_and_a_restart_does_not_inherit_a_live_claim()
      throws Exception {
    var runtime = mock(JobRuntime.class);
    var definition = mock(AgentDefinition.class);
    when(definition.name()).thenReturn("fixture");
    var transcript = mock(Transcript.class);
    when(transcript.conversationId()).thenReturn("cnv_fixture");
    when(runtime.run(
            eq(definition),
            anyString(),
            any(Home.class),
            any(Budget.class),
            any(),
            isNull(),
            any(JobWatch.class),
            eq(transcript),
            any(TurnCap.class),
            anyList(),
            isNull(),
            eq(false)))
        .thenReturn(new Outcome(Outcome.Ending.AWAITING, "Approval requested", 1, 1, ""));
    var closing = new CountDownLatch(1);
    var finish = new CountDownLatch(1);
    try (var jobs = new JobStore(runtime)) {
      String id =
          jobs.submit(
              definition,
              "fixture",
              Home.global(),
              null,
              Budget.of(2),
              transcript,
              Origin.TURN,
              outcome -> {
                closing.countDown();
                try {
                  if (!finish.await(5, TimeUnit.SECONDS))
                    throw new IllegalStateException("Close not released");
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                }
              },
              TurnCap.of(2),
              List.of());
      try {
        assertTrue(closing.await(3, TimeUnit.SECONDS));
        assertTrue(jobs.hasRunningJob("cnv_fixture"));
        assertFalse(jobs.hasRunningJob("cnv_other"));
      } finally {
        finish.countDown();
      }
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (jobs.get(id).state() != Job.State.DONE && System.nanoTime() < deadline)
        java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
      assertEquals(Job.State.DONE, jobs.get(id).state());
      assertFalse(jobs.hasRunningJob("cnv_fixture"));
    }
    try (var restarted = new JobStore(runtime)) {
      assertFalse(restarted.hasRunningJob("cnv_fixture"));
    }
  }
}
