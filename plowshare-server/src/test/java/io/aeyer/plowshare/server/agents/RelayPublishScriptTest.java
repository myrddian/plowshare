package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.accounting.UsageLineage;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.orchestrations.scripted.ScriptProgram;
import io.aeyer.plowshare.server.orchestrations.scripted.ScriptStore;
import io.aeyer.plowshare.server.relay.RelayPublishing;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Uses the real harness script-dispatch path with a mocked journal and publication capability. */
class RelayPublishScriptTest {
  private static final String CONVERSATION = "cnv_publish_script";
  private static final String INPUT = "{\"text\":\"script text\",\"topic\":\"reports.input\"}";
  private static final String SOURCE =
      ScriptProgram.MARKER
          + "\n"
          + "export const manifest={name:'writer',description:'publisher','native-only':true,tools:['relay_publish'],calls:[],scopes:[],'max-turns':10,'max-model-calls':0,stages:[{id:'publish'}]};\n"
          + "export function step(input){return {state:{sent:true},command:{tool:'relay_publish',arguments:{topic:'reports.input',text:'script text'}}};}";
  private final RelayPublishing publishing = mock(RelayPublishing.class);
  private final ScriptStore scripts = mock(ScriptStore.class);
  private final LlmDispatcher models = mock(LlmDispatcher.class);
  private final AtomicReference<ScriptStore.Step> journal = new AtomicReference<>();
  private final UsageAttribution owner =
      UsageAttribution.project("alice", "7", UsageAttribution.Operation.AGENT_CHAT)
          .withExecution(
              UsageLineage.root(CONVERSATION),
              UsageLineage.root("run:script:1"),
              UsageLineage.NONE,
              "writer",
              1L,
              0L);

  private Outcome execute() {
    var runtime = new JobRuntime(models, List.of(new RelayPublishTool(publishing)));
    runtime.useScripts(scripts);
    var definition =
        OrchestrationRegistry.parsePinned(
                "writer",
                "writer.js",
                SOURCE,
                Set.of(RelayPublishTool.NAME),
                OrchestrationDefinition.Tier.SHIPPED)
            .conductor();
    when(scripts.head(CONVERSATION))
        .thenAnswer(
            call -> new ScriptStore.Head(Optional.ofNullable(journal.get()), Optional.empty()));
    when(scripts.latest(CONVERSATION)).thenAnswer(call -> Optional.ofNullable(journal.get()));
    when(scripts.prepareNext(anyString(), any(), anyBoolean()))
        .thenAnswer(
            call -> {
              var step =
                  new ScriptStore.Step(
                      0,
                      ScriptProgram.sourceHash(SOURCE),
                      new ScriptStore.Tool(RelayPublishTool.NAME, INPUT, false),
                      null,
                      null,
                      false,
                      null);
              journal.set(step);
              return new ScriptStore.Prepared(step);
            });
    doAnswer(
            call -> {
              var previous = journal.get();
              journal.set(
                  new ScriptStore.Step(
                      0,
                      previous.hash(),
                      previous.command(),
                      call.getArgument(2),
                      call.getArgument(2),
                      true,
                      INPUT));
              return null;
            })
        .when(scripts)
        .completed(anyString(), anyInt(), anyString());
    Transcript transcript =
        new Transcript() {
          public List<ChatMessage> before() {
            return List.of();
          }

          public void promptMeasured(int tokens) {}

          public String conversationId() {
            return CONVERSATION;
          }

          public Origin origin() {
            return Origin.ORCHESTRATION;
          }

          public void record(LoggedEntry entry) {}

          public UsageAttribution usage() {
            return owner;
          }
        };
    return runtime.run(
        definition,
        "publish",
        Home.of("research"),
        Budget.noCalls(),
        () -> false,
        "session",
        JobWatch.UNWATCHED,
        transcript,
        TurnCap.of(1),
        List.of(),
        "alice");
  }

  @Test
  void native_script_uses_its_granted_tool_with_harness_identity_and_journals_the_receipt() {
    var receipt =
        new RelayPublishing.Receipt(
            UUID.randomUUID(), "reports.input", "1", Instant.parse("2026-11-13T00:00:00Z"));
    when(publishing.publish(any(), any(), any(), any())).thenReturn(receipt);
    assertEquals(Outcome.Ending.TURN_CAP, execute().ending());
    verify(publishing)
        .publish(
            eq("research"),
            eq(
                new RelayPublishing.Execution(
                    owner.withExecution(
                        owner.conversations(),
                        owner.runs(),
                        owner.orchestrations(),
                        "writer",
                        1L,
                        1L),
                    "session",
                    "script_0",
                    RelayPublishing.DefinitionKind.ORCHESTRATION)),
            eq(new RelayPublishing.Request("reports.input", "script text")),
            any());
    verify(scripts).started(CONVERSATION, 0, INPUT);
    assertTrue(journal.get().result().contains(receipt.eventId().toString()));
    verify(models, atLeastOnce()).wireModelFor(any());
    verifyNoMoreInteractions(models);
  }

  @Test
  void recovery_reuses_a_cached_receipt_without_publishing_again() {
    journal.set(
        new ScriptStore.Step(
            0,
            ScriptProgram.sourceHash(SOURCE),
            new ScriptStore.Tool(RelayPublishTool.NAME, INPUT, false),
            "{\"eventId\":\"retained\"}",
            null,
            true,
            INPUT));
    assertEquals(Outcome.Ending.TURN_CAP, execute().ending());
    verifyNoInteractions(publishing);
    verify(models, atLeastOnce()).wireModelFor(any());
    verifyNoMoreInteractions(models);
  }

  @Test
  void an_uncertain_started_command_is_refused_automatic_replay() {
    journal.set(
        new ScriptStore.Step(
            0,
            ScriptProgram.sourceHash(SOURCE),
            new ScriptStore.Tool(RelayPublishTool.NAME, INPUT, false),
            null,
            null,
            true,
            INPUT));
    assertThrows(IllegalStateException.class, this::execute);
    verifyNoInteractions(publishing, models);
  }
}
