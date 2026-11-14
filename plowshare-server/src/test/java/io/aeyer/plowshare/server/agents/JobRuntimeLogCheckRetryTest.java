package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.aeyer.plowshare.server.faults.NotFoundFault;
import io.aeyer.plowshare.server.information.InformationJobs;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.reads.BusyReadRetry;
import io.aeyer.plowshare.server.reads.ReadUnavailableException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The run's per-step log check is side-effect free, so a busy refusal from its cached read is
 * retried a bounded number of times instead of ending the job (the PROD failure "Job runtime failed
 * ... Read capacity is busy"). A real denial is never retried.
 */
class JobRuntimeLogCheckRetryTest {
  private final InformationJobs inputs = mock(InformationJobs.class);
  private final List<Duration> pauses = new ArrayList<>();
  private final JobRuntime runtime = new JobRuntime(mock(LlmDispatcher.class), List.of());

  JobRuntimeLogCheckRetryTest() {
    runtime.useInformationInputs(inputs);
    runtime.useLogCheckRetry(new BusyReadRetry(3, Duration.ofMillis(50), pauses::add));
  }

  @Test
  void aTransientBusyLogCheckIsRetriedAndTheStepProceeds() {
    doThrow(new ReadUnavailableException())
        .doThrow(new ReadUnavailableException())
        .doNothing()
        .when(inputs)
        .requireLog("log-1", "alice");
    runtime.requireLogReadable("log-1", "alice");
    verify(inputs, times(3)).requireLog("log-1", "alice");
    assertEquals(List.of(Duration.ofMillis(50), Duration.ofMillis(100)), pauses);
  }

  @Test
  void aPersistentlyBusyLogCheckGivesUpAfterTheBound() {
    doThrow(new ReadUnavailableException()).when(inputs).requireLog("log-1", "alice");
    assertThrows(
        ReadUnavailableException.class, () -> runtime.requireLogReadable("log-1", "alice"));
    verify(inputs, times(3)).requireLog("log-1", "alice");
  }

  @Test
  void aDeniedLogIsNotRetried() {
    doThrow(new NotFoundFault("log inputs are unavailable to this account"))
        .when(inputs)
        .requireLog("log-1", "alice");
    assertThrows(NotFoundFault.class, () -> runtime.requireLogReadable("log-1", "alice"));
    verify(inputs, times(1)).requireLog("log-1", "alice");
  }

  @Test
  void noInformationInputsMeansNoCheck() {
    var bare = new JobRuntime(mock(LlmDispatcher.class), List.of());
    bare.requireLogReadable("log-1", "alice");
    verify(inputs, times(0)).requireLog("log-1", "alice");
  }
}
