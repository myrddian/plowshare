package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.JobLog;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class JobOriginBindingTest {
  @Test
  void owning_job_is_bound_through_accounting_wrapper_before_runtime_work() throws Exception {
    var runtime = mock(JobRuntime.class);
    var rows = mock(JobLog.class);
    var agent = mock(AgentDefinition.class);
    when(agent.name()).thenReturn("worker");
    when(agent.maxTurns()).thenReturn(1);
    var delegate = mock(Transcript.class);
    when(delegate.conversationId()).thenReturn("cnv_fixture");
    var transcript =
        new AttributedTranscript(delegate, UsageAttribution.LEGACY, UsageAttribution.LEGACY);
    var completed = new CompletableFuture<Outcome>();
    var answer = new Outcome(Outcome.Ending.ANSWERED, "done", 1, 1, "");
    when(runtime.run(
            eq(agent),
            eq("message"),
            any(),
            any(),
            any(),
            isNull(),
            any(),
            eq(transcript),
            any(),
            anyList(),
            isNull(),
            eq(false)))
        .thenReturn(answer);
    try (var jobs = new JobStore(runtime, JobEvents.NONE, null, rows)) {
      String job =
          jobs.submit(
              agent,
              "message",
              Home.global(),
              null,
              Budget.of(5),
              transcript,
              Origin.TURN,
              completed::complete);
      assertEquals(answer, completed.get(5, TimeUnit.SECONDS));
      var order = inOrder(delegate, rows, runtime);
      order.verify(delegate).startedAs(job);
      order
          .verify(rows)
          .started(eq(job), eq("worker"), eq(Home.global()), any(), eq("cnv_fixture"), any());
      order
          .verify(runtime)
          .run(
              eq(agent),
              eq("message"),
              any(),
              any(),
              any(),
              isNull(),
              any(),
              eq(transcript),
              any(),
              anyList(),
              isNull(),
              eq(false));
    }
  }
}
