package io.aeyer.plowshare.server.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import org.junit.jupiter.api.Test;

class HookContextTest {
  @org.junit.jupiter.api.Test
  void document_and_usage_survive_context_transformations_together() {
    var owner =
        io.aeyer.plowshare.server.llm.accounting.UsageAttribution.global(
            "alice",
            io.aeyer.plowshare.server.llm.accounting.UsageAttribution.Operation.HOOK_MODEL);
    var document = new HookContext.Document("summarise", "resource", "revision", 1, "paragraph", 1);
    var context =
        HookContext.forLog("submission", "document_pipeline", false, null, "log")
            .withUsage(owner)
            .about(document)
            .holding(java.util.Set.of("read"))
            .inLog("delegation")
            .about(new HookContext.Orchestration("run", "research", "review"))
            .with(new HookContext.RunEnvironment("server", "gated", false, "none"));
    org.junit.jupiter.api.Assertions.assertEquals(owner, context.usage());
    org.junit.jupiter.api.Assertions.assertEquals(document, context.document());
  }

  @Test
  void a_run_stage_context_still_needs_an_agent() {
    assertThrows(
        NullPointerException.class,
        () -> new HookContext(null, false, Set.of(), "ledger", "cnv_1", HookContext.SERVER));
  }

  /** A TURN log names no agent (Origin.TURN), so its log stages see none. */
  @Test
  void a_log_stage_context_may_name_no_agent() {
    HookContext turn = HookContext.forLog("turn", null, false, "ledger", "cnv_1");

    assertNull(turn.agent());
    assertEquals("turn", turn.origin());
    assertEquals("cnv_1", turn.conversation());
    assertEquals(HookContext.SERVER, turn.side());
  }

  @Test
  void with_an_environment_keeps_the_origin() {
    HookContext log = HookContext.forLog("submission", "scribe", false, null, "cnv_2");

    assertEquals(
        "submission",
        log.with(new HookContext.RunEnvironment("server", "gated", false, "none")).origin());
  }

  /** Spec 2026-09-28-hooks-reach-the-log §3: `orchestration?` when one is involved. */
  @Test
  void an_in_turn_stage_keeps_the_run_and_adds_its_log_s_origin_and_orchestration() {
    HookContext run =
        new HookContext(
            "conductor", true, Set.of("todo_write"), "story", "cnv_c", HookContext.SERVER);
    HookContext.Orchestration orchestration =
        new HookContext.Orchestration("orc_1", "code_implementation", "code");

    HookContext staged = run.inLog("orchestration").about(orchestration);

    assertEquals("conductor", staged.agent());
    assertEquals(Set.of("todo_write"), staged.tools());
    assertEquals("orchestration", staged.origin());
    assertEquals(orchestration, staged.orchestration());
    assertEquals(
        orchestration,
        staged.with(new HookContext.RunEnvironment("server", "ask", false, "none")).orchestration(),
        "an environment added later keeps it");
    assertNull(run.orchestration());
  }

  @Test
  void an_orchestration_needs_an_id_and_a_definition_and_may_name_no_stage() {
    assertThrows(
        NullPointerException.class,
        () -> new HookContext.Orchestration(null, "code_implementation", null));
    assertThrows(
        NullPointerException.class, () -> new HookContext.Orchestration("orc_1", null, null));
    assertNull(new HookContext.Orchestration("orc_1", "code_implementation", null).stage());
  }
}
