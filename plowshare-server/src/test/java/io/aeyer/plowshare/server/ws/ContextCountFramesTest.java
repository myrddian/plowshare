package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.frames.*;
import io.aeyer.plowshare.server.agents.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.llm.accounting.*;
import io.aeyer.plowshare.server.llm.counting.PromptCount;
import io.aeyer.plowshare.server.llm.dispatch.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ContextCountFramesTest {
  final UsageQueryService access = mock(UsageQueryService.class);
  final Callers callers = mock(Callers.class);
  final Compaction compaction = mock(Compaction.class);
  final JobRuntime runtime = mock(JobRuntime.class);
  final LlmDispatcher models = mock(LlmDispatcher.class);
  final FrameRouter router =
      new FrameRouter(
          new ContextCountFrames(access, callers, compaction, runtime, models).frames());
  final Asking asking = new Asking("session", "alice", "socket");

  Envelope frame(Map<String, Object> payload) {
    return new Envelope(
        "count-request", FrameTypes.CONVERSATION_CONTEXT_COUNT, Envelope.CURRENT_VERSION, payload);
  }

  @Test
  void counts_the_authorized_projection_and_actual_tools_without_generating() {
    var agent =
        new AgentDefinition(
            "talker", "fixture", "fast", List.of(), List.of(), List.of(), 2, 4, "system");
    var caller = new DefinitionResolver.Caller(null, "session");
    var messages = List.of(ChatMessage.system("system"), ChatMessage.user("stored question"));
    var tools = List.of(ToolSchema.from("lookup", "actual offered tool", Map.of("type", "object")));
    var owner =
        UsageAttribution.global("alice", UsageAttribution.Operation.AGENT_CHAT)
            .withExecution(
                UsageLineage.root("conversation"),
                UsageLineage.NONE,
                UsageLineage.NONE,
                null,
                null,
                null);
    when(callers.callerForConversation("conversation", "session")).thenReturn(caller);
    when(callers.readAgent("talker", caller)).thenReturn(agent);
    when(compaction.projectionFor("conversation", agent)).thenReturn(messages);
    when(runtime.withAgentRules(any(), any(), any(), any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(runtime.schemasOfferedTo(agent)).thenReturn(tools);
    when(access.countOwner("alice", "conversation")).thenReturn(owner);
    when(models.count(any(ChatRequest.class)))
        .thenReturn(PromptCount.unknown("pool", "model", "not_configured"));

    var outcome =
        router.route(
            frame(
                Map.of(
                    "conversation",
                    "conversation",
                    "agent",
                    "talker",
                    "account",
                    "bob",
                    "messages",
                    List.of("injected"),
                    "url",
                    "https://untrusted.example")),
            asking);
    assertEquals(Code.OK, outcome.code());
    var captured = ArgumentCaptor.forClass(ChatRequest.class);
    verify(models).count(captured.capture());
    assertEquals(messages, captured.getValue().messages());
    assertEquals(tools, captured.getValue().tools());
    assertEquals(owner, captured.getValue().attribution());
    var order = inOrder(access, callers, models);
    order.verify(access).requireConversation("alice", "conversation");
    order.verify(callers).callerForConversation("conversation", "session");
    order.verify(callers).readAgent("talker", caller);
    order.verify(access).countOwner("alice", "conversation");
    order.verify(models).count(any(ChatRequest.class));
    verifyNoMoreInteractions(models);
  }

  @Test
  void next_context_count_uses_the_same_rule_prompt_as_execution() {
    var original =
        new AgentDefinition(
            "talker", "fixture", "fast", List.of(), List.of(), List.of(), 2, 4, "system");
    var ruled = original.withPrompt("system\nProject rules");
    var caller = new DefinitionResolver.Caller(7L, "session");
    var home = io.aeyer.plowshare.protocol.Home.of("project");
    when(callers.callerForConversation("conversation", "session")).thenReturn(caller);
    when(callers.readAgent("talker", caller)).thenReturn(original);
    when(callers.homeOfConversation("conversation")).thenReturn(home);
    when(runtime.withAgentRules(original, home, "session", "conversation")).thenReturn(ruled);
    when(runtime.schemasOfferedTo(ruled)).thenReturn(List.of());
    var messages = List.of(ChatMessage.system(ruled.prompt()), ChatMessage.user("stored question"));
    when(compaction.projectionFor("conversation", ruled)).thenReturn(messages);
    when(access.countOwner("alice", "conversation")).thenReturn(UsageAttribution.LEGACY);
    when(models.count(any(ChatRequest.class)))
        .thenReturn(PromptCount.unknown("pool", "model", "not_configured"));
    assertEquals(
        Code.OK,
        router
            .route(frame(Map.of("conversation", "conversation", "agent", "talker")), asking)
            .code());
    var captured = ArgumentCaptor.forClass(ChatRequest.class);
    verify(models).count(captured.capture());
    assertEquals(messages, captured.getValue().messages());
    verifyNoMoreInteractions(models);
  }

  @Test
  void denied_or_unsigned_count_never_resolves_content_or_contacts_a_model() {
    doThrow(new CallerFault("unavailable")).when(access).requireConversation("alice", "private");
    assertEquals(
        Code.BAD_REQUEST,
        router.route(frame(Map.of("conversation", "private", "agent", "talker")), asking).code());
    assertEquals(
        Code.BAD_REQUEST,
        router
            .route(
                frame(Map.of("conversation", "private", "agent", "talker")), new Asking("session"))
            .code());
    verifyNoInteractions(callers, compaction, runtime, models);
  }

  @Test
  void snapshot_preserves_text_tool_wiring_and_images_without_counting_or_generation() {
    var agent =
        new AgentDefinition(
            "talker", "fixture", "fast", List.of(), List.of(), List.of(), 2, 4, "system");
    var caller = new DefinitionResolver.Caller(null, "session");
    var messages =
        List.of(
            ChatMessage.system("exact system"),
            ChatMessage.user(
                "stored question",
                List.of(new Content.Image("image-1", "data:image/png;base64,aGVsbG8="))),
            ChatMessage.assistant(
                "",
                List.of(
                    new io.aeyer.plowshare.protocol.ToolCall(
                        "call-1", "lookup", "{\"q\":\"stored\"}"))),
            ChatMessage.tool("call-1", "exact result"));
    var tools = List.of(ToolSchema.from("lookup", "offered tool", Map.of("type", "object")));
    when(callers.callerForConversation("conversation", "session")).thenReturn(caller);
    when(callers.readAgent("talker", caller)).thenReturn(agent);
    when(compaction.projectionFor("conversation", agent)).thenReturn(messages);
    when(runtime.withAgentRules(any(), any(), any(), any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(runtime.schemasOfferedTo(agent)).thenReturn(tools);
    when(access.countOwner("alice", "conversation")).thenReturn(UsageAttribution.LEGACY);
    var asked =
        new Envelope(
            "snapshot",
            FrameTypes.CONVERSATION_CONTEXT_SNAPSHOT,
            Envelope.CURRENT_VERSION,
            Map.of(
                "conversation",
                "conversation",
                "agent",
                "talker",
                "prompt",
                "unsent draft",
                "messages",
                List.of("injected")));
    var outcome = router.route(asked, asking);
    assertEquals(Code.OK, outcome.code());
    var result = (Map<?, ?>) outcome.payload();
    assertNull(result.get("count"));
    assertEquals(tools, result.get("tools"));
    assertEquals("fast", result.get("model"));
    var projection = ((List<?>) result.get("messages")).stream().map(v -> (Map<?, ?>) v).toList();
    assertEquals("system", projection.get(0).get("role"));
    assertEquals(
        List.of(Map.of("type", "text", "text", "exact system")), projection.get(0).get("parts"));
    assertEquals(
        List.of(
            Map.of("type", "text", "text", "stored question"),
            Map.of("type", "image", "uid", "image-1", "omitted", true)),
        projection.get(1).get("parts"));
    assertEquals(messages.get(2).toolCalls(), projection.get(2).get("tool_calls"));
    assertEquals("call-1", projection.get(3).get("tool_call_id"));
    assertFalse(result.toString().contains("unsent draft"));
    assertFalse(result.toString().contains("aGVsbG8="));
    verifyNoInteractions(models);
    var measured =
        router.route(
            new Envelope(
                "measured",
                FrameTypes.CONVERSATION_CONTEXT_SNAPSHOT,
                Envelope.CURRENT_VERSION,
                Map.of("conversation", "conversation", "agent", "talker", "measure", true)),
            asking);
    assertEquals(Code.OK, measured.code());
    var capture = ArgumentCaptor.forClass(ChatRequest.class);
    verify(models).count(capture.capture());
    assertEquals(messages, capture.getValue().messages());
    assertEquals(tools, capture.getValue().tools());
    verifyNoMoreInteractions(models);
  }

  @Test
  void new_conversation_can_preview_its_system_block_without_a_future_prompt() {
    var agent =
        new AgentDefinition(
            "talker", "fixture", "fast", List.of(), List.of(), List.of(), 2, 4, "system");
    var caller = new DefinitionResolver.Caller(null, "session");
    when(callers.callerForConversation("conversation", "session")).thenReturn(caller);
    when(callers.readAgent("talker", caller)).thenReturn(agent);
    when(compaction.projectionFor("conversation", agent))
        .thenReturn(List.of(ChatMessage.system("system")));
    when(runtime.withAgentRules(any(), any(), any(), any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(runtime.schemasOfferedTo(agent)).thenReturn(List.of());
    when(access.countOwner("alice", "conversation")).thenReturn(UsageAttribution.LEGACY);
    var result =
        router.route(
            new Envelope(
                "snapshot",
                FrameTypes.CONVERSATION_CONTEXT_SNAPSHOT,
                Envelope.CURRENT_VERSION,
                Map.of("conversation", "conversation", "agent", "talker", "measure", true)),
            asking);
    assertEquals(Code.OK, result.code());
    assertEquals(
        "UNKNOWN", ((PromptCount) ((Map<?, ?>) result.payload()).get("count")).basis().name());
    verifyNoInteractions(models);
  }

  @Test
  void snapshot_authorizes_before_resolving_content_or_measuring() {
    doThrow(new CallerFault("unavailable")).when(access).requireConversation("alice", "private");
    var frame =
        new Envelope(
            "snapshot",
            FrameTypes.CONVERSATION_CONTEXT_SNAPSHOT,
            Envelope.CURRENT_VERSION,
            Map.of("conversation", "private", "agent", "talker", "measure", true));
    assertEquals(Code.BAD_REQUEST, router.route(frame, asking).code());
    assertEquals(Code.BAD_REQUEST, router.route(frame, new Asking("session")).code());
    verifyNoInteractions(callers, compaction, runtime, models);
  }
}
