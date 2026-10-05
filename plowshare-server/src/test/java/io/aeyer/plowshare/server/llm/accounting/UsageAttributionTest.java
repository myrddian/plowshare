package io.aeyer.plowshare.server.llm.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.aeyer.plowshare.server.llm.accounting.UsageAttribution.Operation;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution.Scope;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution.Status;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.ChatRequest;
import io.aeyer.plowshare.server.llm.dispatch.EmbeddingRequest;
import io.aeyer.plowshare.server.llm.dispatch.Sampling;
import io.aeyer.plowshare.server.llm.dispatch.ToolChoice;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UsageAttributionTest {

  @Test
  void model_hook_context_copies_keep_metadata_but_existing_json_shapes_do_not_expose_it()
      throws Exception {
    var owner =
        UsageAttribution.project("alice", "42", Operation.AGENT_CHAT)
            .withExecution(
                UsageLineage.root("conversation"),
                UsageLineage.root("run"),
                UsageLineage.root("orch"),
                "worker",
                2L,
                3L);
    var context =
        new io.aeyer.plowshare.server.hooks.HookContext(
                "worker", false, java.util.Set.of("read"), "research", "conversation", "server")
            .withUsage(owner)
            .holding(List.of("read", "write"))
            .inLog("turn")
            .with(
                new io.aeyer.plowshare.server.hooks.HookContext.RunEnvironment(
                    "server", "ask", false, "none"))
            .about(
                new io.aeyer.plowshare.server.hooks.HookContext.Orchestration(
                    "orch", "workflow", "work"));
    assertSame(owner, context.usage());
    var json = AccountingFixtures.MAPPER.valueToTree(context);
    assertEquals(false, json.has("usage"));
    assertEquals(false, json.toString().contains("alice"));
    var question =
        new io.aeyer.plowshare.server.agents.CallValidator.Question(
            "held", ToolSchema.from("read", "read", Map.of()), "task", owner);
    assertEquals(false, AccountingFixtures.MAPPER.valueToTree(question).has("usage"));
    var side = owner.forOperation(Operation.REVIEW, "validator");
    assertEquals(owner.runs(), side.runs());
    assertEquals(3L, side.stepOrdinal());
    assertEquals("validator", side.agentName());
    assertEquals(Operation.AGENT_CHAT, owner.operation());
    assertSame(
        UsageAttribution.LEGACY,
        UsageAttribution.LEGACY.forOperation(Operation.REVIEW, "validator"));
  }

  @Test
  void every_chat_copy_preserves_ownership_and_ancestry() {
    UsageAttribution attribution =
        UsageAttribution.project("alice", "research", Operation.AGENT_CHAT)
            .withExecution(
                UsageLineage.root("c-root").child("c-parent").child("c-leaf"),
                UsageLineage.root("r-root").child("r-parent").child("r-leaf"),
                UsageLineage.root("o-root").child("o-leaf"),
                "researcher",
                3L,
                2L);
    ChatRequest original = ChatRequest.of("model", null, "question").withAttribution(attribution);
    List<ChatRequest> copies =
        List.of(
            original.withBudget(Duration.ofSeconds(2)),
            original.withSampling(Sampling.NONE),
            original.withTools(List.of(ToolSchema.from("search", "search", Map.of()))),
            original.withToolChoice(ToolChoice.REQUIRED),
            original.withMessages(List.of(ChatMessage.user("next question"))));
    for (ChatRequest copy : copies) {
      assertSame(attribution, copy.attribution());
      assertEquals(List.of("r-parent", "r-root"), copy.attribution().runs().ancestors());
    }
    assertEquals("alice", original.attribution().accountHandle());
    assertEquals("research", original.attribution().projectId());
    assertEquals("r-root", original.attribution().runs().rootId());
    assertEquals("c-root", original.attribution().conversations().rootId());
  }

  @Test
  void embedding_batches_keep_owner_when_rebudgeted() {
    UsageAttribution attribution =
        UsageAttribution.project("alice", "research", Operation.EMBEDDING_QUERY);
    EmbeddingRequest request =
        EmbeddingRequest.of("embed", List.of("query"))
            .withAttribution(attribution)
            .withBudget(Duration.ofSeconds(3));
    assertSame(attribution, request.attribution());
    assertEquals(Operation.EMBEDDING_QUERY, request.attribution().operation());
    assertEquals(List.of("query"), request.input());
    assertEquals(
        attribution.withOperation(Operation.EMBEDDING_WRITE).accountHandle(),
        attribution.accountHandle());
    assertEquals(Operation.EMBEDDING_QUERY, attribution.operation());
  }

  @Test
  void legacy_calls_are_explicit_and_cannot_claim_owned_work() {
    assertSame(UsageAttribution.LEGACY, ChatRequest.of("model", null, "hi").attribution());
    assertSame(UsageAttribution.LEGACY, EmbeddingRequest.of("embed", List.of("hi")).attribution());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            UsageAttribution.LEGACY.withExecution(
                UsageLineage.root("c"), UsageLineage.NONE, UsageLineage.NONE, null, null, null));
    assertThrows(
        NullPointerException.class,
        () -> ChatRequest.of("model", null, "hi").withAttribution(null));
  }

  @Test
  void ancestry_survives_a_parent_that_did_not_itself_make_a_call() {
    var ancestors = new ArrayList<>(List.of("no-inference-parent", "root"));
    UsageLineage lineage = new UsageLineage("leaf", ancestors);
    ancestors.clear();
    assertEquals(List.of("no-inference-parent", "root"), lineage.ancestors());
    assertEquals("no-inference-parent", lineage.parentId());
    assertEquals("root", lineage.rootId());
    assertThrows(UnsupportedOperationException.class, () -> lineage.ancestors().clear());
    assertThrows(IllegalArgumentException.class, () -> lineage.child("root"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new UsageLineage("leaf", List.of("parent", "parent")));
    assertThrows(IllegalArgumentException.class, () -> new UsageLineage(null, List.of("parent")));
  }

  @Test
  void system_global_and_user_global_are_distinct_scopes() {
    UsageAttribution system = UsageAttribution.system(null, Operation.EMBEDDING_REPAIR);
    assertEquals(Status.SYSTEM, system.status());
    assertEquals(Scope.GLOBAL, system.scope());
    assertNull(system.accountHandle());
    assertNull(system.projectId());
    UsageAttribution account = UsageAttribution.global("alice", Operation.EMBEDDING_QUERY);
    assertEquals(Status.ATTRIBUTED, account.status());
    assertEquals("alice", account.accountHandle());
    assertEquals(Scope.GLOBAL, account.scope());
    assertThrows(
        IllegalArgumentException.class,
        () -> UsageAttribution.project(" ", "research", Operation.AGENT_CHAT));
    assertThrows(
        IllegalArgumentException.class,
        () -> UsageAttribution.project("alice", null, Operation.AGENT_CHAT));
    assertThrows(
        IllegalArgumentException.class,
        () -> UsageAttribution.global("alice", Operation.LEGACY_UNKNOWN));
  }

  @Test
  void ordinals_require_an_existing_identity_and_nonnegative_position() {
    UsageAttribution owner = UsageAttribution.project("alice", "p", Operation.AGENT_CHAT);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            owner.withExecution(
                UsageLineage.NONE, UsageLineage.NONE, UsageLineage.NONE, "worker", 1L, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            owner.withExecution(
                UsageLineage.root("c"),
                UsageLineage.root("r"),
                UsageLineage.NONE,
                "worker",
                1L,
                -1L));
  }
}
