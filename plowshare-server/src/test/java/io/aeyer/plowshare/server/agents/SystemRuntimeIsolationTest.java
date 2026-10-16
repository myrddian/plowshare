package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.dispatch.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A confined integration run cannot install its permissions onto the shared SYSTEM runtime. */
class SystemRuntimeIsolationTest {
  @Test
  void refused_acting_profile_does_not_restrict_subsequent_internal_system_work() {
    var definition =
        AgentExposureTest.systemDefinition("internal_worker", null)
            .withTools(List.of("memory_recall"));
    assertTrue(definition.isSystem());
    var home = Home.global();
    var system =
        UsageAttribution.system(null, UsageAttribution.Operation.REVIEW)
            .withExecution(
                io.aeyer.plowshare.server.llm.accounting.UsageLineage.root("cnv_SYSTEM"),
                io.aeyer.plowshare.server.llm.accounting.UsageLineage.root("run:SYSTEM:1"),
                io.aeyer.plowshare.server.llm.accounting.UsageLineage.NONE,
                definition.name(),
                1L,
                null);
    var transcript = mock(Transcript.class, CALLS_REAL_METHODS);
    when(transcript.usage()).thenReturn(system);
    when(transcript.parentUsage()).thenReturn(system);
    var recall = mock(AgentTool.class);
    when(recall.schema())
        .thenReturn(ToolSchema.from("memory_recall", "Recall fixture", Map.of("type", "object")));
    when(recall.run(anyString(), eq(home), any())).thenReturn("Internal evidence");
    var dispatcher = mock(LlmDispatcher.class);
    when(dispatcher.stream(any(), any(), any()))
        .thenAnswer(
            call -> {
              ChatRequest request = call.getArgument(0);
              assertEquals(UsageAttribution.Status.SYSTEM, request.attribution().status());
              request.authorization().require();
              return new Completion(
                  null,
                  "tool_calls",
                  TokenUsage.UNKNOWN,
                  List.of(new ToolCall("one", "memory_recall", "{\"question\":\"Fixture\"}")));
            })
        .thenReturn(new Completion("Internal result", "stop", TokenUsage.UNKNOWN, List.of()));
    var runtime = new JobRuntime(dispatcher, List.of(recall));
    var denied =
        new RunAdmission(
            () -> {
              throw new CallerFault("Acting disabled");
            },
            job -> {},
            () -> {},
            "alice",
            (at, resources) -> {
              throw new CallerFault("Acting disabled");
            });
    assertThrows(
        CallerFault.class,
        () ->
            runtime
                .forAdmittedTurn(denied)
                .run(
                    definition,
                    "External request",
                    home,
                    Budget.of(4),
                    () -> false,
                    null,
                    JobWatch.UNWATCHED,
                    transcript,
                    TurnCap.of(4),
                    List.of(),
                    "alice",
                    false));
    verifyNoInteractions(dispatcher);
    var outcome =
        runtime.run(
            definition,
            "Internal request",
            home,
            Budget.of(4),
            () -> false,
            null,
            JobWatch.UNWATCHED,
            transcript,
            TurnCap.of(4),
            List.of(),
            null,
            false);
    assertEquals(Outcome.Ending.ANSWERED, outcome.ending(), outcome.toString());
    assertEquals("Internal result", outcome.text());
    verify(recall)
        .run(
            anyString(),
            eq(home),
            argThat(owner -> owner.status() == UsageAttribution.Status.SYSTEM));
  }
}
