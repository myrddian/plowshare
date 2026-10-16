package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.EntryStore;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.images.ImageStore;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The installed diagnostic skill uses ordinary durable delegation with a fresh, protected worker.
 */
class ConversationDiagnosisSkillTest {
  final AgentRegistry registry = ShippedDefinitions.registry(BoundTools.boundByThisServer());
  final AgentDefinition main = registry.get("interlocutor");
  final AgentDefinition diagnostician = registry.get("conversation_diagnostician");
  final SkillResolver.Catalog catalog =
      SkillResolver.load(
          List.of(
              new SkillResolver.Layer(
                  OrchestrationDefinition.Tier.SHIPPED, SkillSource.shipped())));
  final SkillDefinition skill = catalog.skills().get("diagnose-conversation").definition();
  final SkillResolver resolver = mock(SkillResolver.class);
  final Callers callers = mock(Callers.class);
  final SkillExecutions executions = mock(SkillExecutions.class);
  final JobRuntime runtime = mock(JobRuntime.class);
  final EntryStore entries = mock(EntryStore.class);
  final Compaction compaction = mock(Compaction.class);
  final Transcript parent = mock(Transcript.class, CALLS_REAL_METHODS);
  final Transcript child = mock(Transcript.class, CALLS_REAL_METHODS);
  final Home home = Home.of("fixture");
  final Budget budget = Budget.of(8);
  final UUID invocation = UUID.randomUUID();
  final DefinitionResolver.Caller owner = new DefinitionResolver.Caller(7L, "session", "alice");
  final SkillRuntime skills =
      new SkillRuntime(resolver, callers, executions, runtime, ImageStore.NONE);

  @BeforeEach
  void setup() {
    when(parent.conversationId()).thenReturn("cnv_PARENT");
    when(child.conversationId()).thenReturn("cnv_DIAGNOSIS");
    when(parent.delegate(any(), eq(home), isNull())).thenReturn(child);
    when(callers.callerForConversation("cnv_PARENT", "session")).thenReturn(owner);
    when(callers.readAgent("conversation_diagnostician", owner)).thenReturn(diagnostician);
    when(callers.visible(home, "session", "alice")).thenReturn(registry);
    when(callers.find(home, "session", "alice", diagnostician.name()))
        .thenReturn(Optional.of(diagnostician));
    when(resolver.forCaller(owner)).thenReturn(catalog);
    when(runtime.activity()).thenReturn(RunActivity.NONE);
    when(executions.claim(
            anyString(),
            eq(invocation),
            anyString(),
            eq("cnv_PARENT"),
            eq("conversation_diagnostician"),
            eq(skill),
            eq(SkillDefinition.Mode.NEW)))
        .thenReturn(true);
    skills.useContexts(new SkillContexts(entries, compaction, executions));
  }

  String invoke(String actor, String mode) {
    return skills
        .forRun(main, parent, budget, () -> false, "session", actor, new TurnEnd())
        .getFirst()
        .run(
            "{\"name\":\"diagnose-conversation\",\"arguments\":\"Why did the latest tool fail?\","
                + "\"invocation\":\""
                + invocation
                + "\""
                + (mode == null ? "" : ",\"mode\":\"" + mode + "\"")
                + "}",
            home);
  }

  @Test
  void main_agents_discover_the_skill_but_the_worker_is_not_a_public_bot() {
    assertEquals(SkillDefinition.Mode.NEW, skill.mode());
    assertEquals("conversation_diagnostician", skill.agent());
    assertTrue(skill.agentVisible());
    assertTrue(diagnostician.isSystem());
    assertFalse(diagnostician.bot());
    assertFalse(diagnostician.exported());
    assertTrue(diagnostician.delegable());
    assertEquals("diagnosis_verifier", diagnostician.reviewWith());
    assertFalse(registry.exportedNames().contains(diagnostician.name()));
    for (String name : List.of("interlocutor", "aristoxenus", "farnsworth")) {
      var caller = registry.get(name);
      assertTrue(caller.canUseSkill(skill.name()), name);
      assertTrue(AgentRegistry.escalatingGrant(caller, diagnostician).isEmpty(), name);
      assertTrue(
          CommandCatalog.of(caller, catalog, Map.of()).stream()
              .anyMatch(
                  entry ->
                      entry.name().equals(skill.name())
                          && entry.agentVisible()
                          && entry.executor().equals(diagnostician.name())
                          && entry.mode().equals("NEW")));
    }
    String discovery = skills.discovery(main, home, "session", "cnv_PARENT", "alice");
    assertTrue(discovery.contains("diagnose-conversation"));
    assertTrue(discovery.contains("conversation_diagnostician"));
    assertFalse(discovery.contains(skill.instructions()));
  }

  @Test
  void model_invocation_defaults_to_new_without_inheriting_parent_history() {
    var outcome = new Outcome(Outcome.Ending.ANSWERED, "Verified diagnostic result", 1, 1, "");
    when(runtime.run(
            eq(diagnostician),
            anyString(),
            eq(home),
            same(budget),
            any(),
            eq("session"),
            same(JobWatch.UNWATCHED),
            same(child),
            any(),
            eq(List.of()),
            eq("alice")))
        .thenAnswer(
            call -> {
              String request = call.getArgument(1);
              assertTrue(request.contains("Invoking conversation: cnv_PARENT"));
              assertTrue(request.contains("Why did the latest tool fail?"));
              assertTrue(child.before().isEmpty());
              return outcome;
            });
    assertTrue(invoke("alice", null).contains("Verified diagnostic result"));
    var order = inOrder(executions, runtime);
    order
        .verify(executions)
        .claim(
            eq("alice"),
            eq(invocation),
            anyString(),
            eq("cnv_PARENT"),
            eq(diagnostician.name()),
            eq(skill),
            eq(SkillDefinition.Mode.NEW));
    order.verify(executions).running("alice", invocation, "cnv_DIAGNOSIS");
    order
        .verify(runtime)
        .run(
            eq(diagnostician),
            anyString(),
            eq(home),
            same(budget),
            any(),
            eq("session"),
            same(JobWatch.UNWATCHED),
            same(child),
            any(),
            eq(List.of()),
            eq("alice"));
    verify(parent, never()).before();
    verifyNoInteractions(entries, compaction);
    verify(child).closed(anyString(), eq(outcome));
  }

  @Test
  void callers_cannot_override_the_fresh_context_mode() {
    for (String mode : List.of("DIRECT", "INHERITED", "SUMMARISED")) {
      assertTrue(invoke("alice", mode).contains("conflicts with the skill's declared mode"));
    }
    verify(executions, never()).claim(any(), any(), any(), any(), any(), any(), any());
    verify(parent, never()).delegate(any(), any(), any());
    verifyNoInteractions(runtime, entries, compaction);
  }

  @Test
  void revoked_delegate_authority_fails_the_receipt_without_starting_a_child() {
    when(callers.find(home, "session", "alice", diagnostician.name()))
        .thenThrow(new CallerFault("Diagnostic access revoked"));
    String result = invoke("alice", null);
    assertTrue(result.contains("E_NO_ACCESS"));
    verify(executions).failed("alice", invocation, result);
    verify(parent, never()).delegate(any(), any(), any());
    verifyNoInteractions(runtime, entries, compaction);
  }

  @Test
  void application_actor_cannot_use_a_human_owned_parent_to_acquire_the_system_worker() {
    String application = "@service/" + UUID.randomUUID();
    assertThrows(CallerFault.class, () -> invoke(application, null));
    verify(callers).readAgent(diagnostician.name(), owner);
    verify(executions, never()).claim(any(), any(), any(), any(), any(), any(), any());
    verify(parent, never()).delegate(any(), any(), any());
    verifyNoInteractions(runtime, entries, compaction);
    assertThrows(CallerFault.class, () -> RetainedAgent.require(diagnostician));
  }
}
