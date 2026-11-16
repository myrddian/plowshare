package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.images.ImageStore;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class InputProvenanceTest {
  @Test
  void explicitContextPreservesUnicodeInstructionsAndRoundTripsItsReferences() {
    String prefix = "Investigate 🌱 the failed run.\n";
    var context =
        new SuppliedContext(
            "Copied documentation about read capacity.",
            List.of(new InputProvenance.Reference("evidence-1", "revision-1")));
    String input = prefix + SuppliedContext.SEPARATOR + context.text();
    var provenance = context.provenance(input);
    assertEquals(prefix + SuppliedContext.SEPARATOR + "\n\n", provenance.evidenceText(input));
    assertEquals(provenance, InputProvenanceCodec.read(InputProvenanceCodec.write(provenance)));
    var entry = LoggedEntry.utterance(input, Speaker.harness()).withInputProvenance(provenance);
    assertEquals(input, entry.content());
    assertEquals(provenance, entry.took(Duration.ofMillis(1)).inputProvenance());
  }

  @Test
  void delegationRetainsContextVerbatimAndClassifiesOnlyItsFramedSpan() {
    var registry = mock(AgentRegistry.class);
    var runtime = mock(JobRuntime.class);
    var caller = mock(AgentDefinition.class);
    var callee = mock(AgentDefinition.class);
    var parent = mock(Transcript.class);
    var child = mock(Transcript.class);
    var budget = Budget.of(10);
    var home = Home.of("research");
    when(caller.calls()).thenReturn(List.of("analyst"));
    when(caller.name()).thenReturn("conductor");
    when(callee.name()).thenReturn("analyst");
    when(callee.delegable()).thenReturn(true);
    when(callee.maxTurns()).thenReturn(10);
    when(registry.find("analyst")).thenReturn(Optional.of(callee));
    when(parent.delegate(callee, home, null)).thenReturn(child);
    when(runtime.activity()).thenReturn(RunActivity.NONE);
    when(runtime.taskFacts(isNull(), same(callee))).thenReturn(Optional.of("Harness facts 🌱"));
    when(runtime.handoffNote(isNull())).thenReturn(Optional.of("Handoff coordinates"));
    var outcome = new Outcome(Outcome.Ending.ANSWERED, "done", 1, 1, "");
    when(runtime.run(
            same(callee),
            anyString(),
            eq(home),
            same(budget),
            any(),
            isNull(),
            same(JobWatch.UNWATCHED),
            same(child),
            any(TurnCap.class),
            eq(List.of())))
        .thenReturn(outcome);
    var tool =
        new AgentRunTool(
            registry,
            runtime,
            caller,
            budget,
            () -> false,
            null,
            parent,
            List.of(),
            ImageStore.NONE,
            new TurnEnd());
    tool.run(
        "{\"agent\":\"analyst\",\"task\":\"Investigate the failed run\",\"context\":{\"text\":\"Copied capacity documentation\",\"sources\":[{\"id\":\"evidence-1\",\"revision\":\"revision-1\"}]}}",
        home);
    var provenance = ArgumentCaptor.forClass(InputProvenance.class);
    var input = ArgumentCaptor.forClass(String.class);
    verify(child).prepareInput(provenance.capture());
    verify(child).closed(input.capture(), same(outcome));
    assertTrue(input.getValue().endsWith("Copied capacity documentation"));
    String evidence = provenance.getValue().evidenceText(input.getValue());
    assertTrue(evidence.contains("Harness facts 🌱"));
    assertTrue(evidence.contains("Investigate the failed run"));
    assertTrue(evidence.contains("Handoff coordinates"));
    assertTrue(!evidence.contains("Copied capacity documentation"));
    assertEquals("evidence-1", provenance.getValue().sources().getFirst().id());
  }

  @Test
  void malformedContextNeverStartsADelegate() {
    var registry = mock(AgentRegistry.class);
    var runtime = mock(JobRuntime.class);
    var caller = mock(AgentDefinition.class);
    var tool =
        new AgentRunTool(
            registry,
            runtime,
            caller,
            Budget.of(10),
            () -> false,
            null,
            Transcript.NONE,
            List.of(),
            ImageStore.NONE,
            new TurnEnd());
    for (String context :
        List.of(
            "null",
            "[]",
            "{\"text\":\"x\",\"sources\":null}",
            "{\"text\":\"x\",\"sources\":[],\"extra\":true}",
            "{\"text\":\"x\",\"sources\":[{\"id\":\"\",\"revision\":\"r\"}]}")) {
      String result =
          tool.run(
              "{\"agent\":\"analyst\",\"task\":\"Assess evidence\",\"context\":" + context + "}",
              Home.global());
      assertTrue(result.contains("Invalid agent_run context"), result);
    }
    verifyNoInteractions(runtime);
  }

  @Test
  void invalidSpansAndReferencesAreRejectedWithoutGuessingLegacyProvenance() {
    assertEquals(null, InputProvenanceCodec.read(null));
    assertThrows(IllegalArgumentException.class, () -> new InputProvenance(2, 2, List.of()));
    assertThrows(
        IllegalArgumentException.class, () -> new InputProvenance.Reference("id\nspoof", "r"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            LoggedEntry.utterance("short", Speaker.harness())
                .withInputProvenance(new InputProvenance(1, 99, List.of())));
    assertThrows(
        IllegalArgumentException.class,
        () -> InputProvenanceCodec.read("{\"end\":3,\"sources\":[]}"));
  }
}
