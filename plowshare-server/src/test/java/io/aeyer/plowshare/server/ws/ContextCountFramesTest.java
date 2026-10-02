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
    final FrameRouter router = new FrameRouter(new ContextCountFrames(access, callers, compaction, runtime, models).frames());
    final Asking asking = new Asking("session", "alice", "socket");

    Envelope frame(Map<String,Object> payload) {
        return new Envelope("count-request", FrameTypes.CONVERSATION_CONTEXT_COUNT, Envelope.CURRENT_VERSION, payload);
    }

    @Test void counts_the_authorized_projection_and_actual_tools_without_generating() {
        var agent = new AgentDefinition("talker", "fixture", "fast", List.of(), List.of(), List.of(), 2, 4, "system");
        var caller = new DefinitionResolver.Caller(null, "session");
        var messages = List.of(ChatMessage.system("system"), ChatMessage.user("stored question"));
        var tools = List.of(new ToolSchema("lookup", "actual offered tool", Map.of("type", "object")));
        var owner = UsageAttribution.global("alice", UsageAttribution.Operation.AGENT_CHAT)
                .withExecution(UsageLineage.root("conversation"), UsageLineage.NONE, UsageLineage.NONE, null, null, null);
        when(callers.callerForConversation("conversation", "session")).thenReturn(caller);
        when(callers.readAgent("talker", caller)).thenReturn(agent);
        when(compaction.projectionFor("conversation", agent)).thenReturn(messages);
        when(runtime.schemasOfferedTo(agent)).thenReturn(tools);
        when(access.countOwner("alice", "conversation")).thenReturn(owner);
        when(models.count(any(ChatRequest.class))).thenReturn(PromptCount.unknown("pool", "model", "not_configured"));

        var outcome = router.route(frame(Map.of("conversation", "conversation", "agent", "talker",
                "account", "bob", "messages", List.of("injected"), "url", "https://untrusted.example")), asking);
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

    @Test void denied_or_unsigned_count_never_resolves_content_or_contacts_a_model() {
        doThrow(new CallerFault("unavailable")).when(access).requireConversation("alice", "private");
        assertEquals(Code.BAD_REQUEST, router.route(frame(Map.of("conversation", "private", "agent", "talker")), asking).code());
        assertEquals(Code.BAD_REQUEST, router.route(frame(Map.of("conversation", "private", "agent", "talker")), new Asking("session")).code());
        verifyNoInteractions(callers, compaction, runtime, models);
    }
}
