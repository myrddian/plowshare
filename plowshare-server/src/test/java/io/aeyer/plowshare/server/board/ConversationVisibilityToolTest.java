package io.aeyer.plowshare.server.board;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.AgentTool;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ConversationVisibilityToolTest {
  @Test
  void malformed_selection_never_reaches_policy_or_underlying_tool() {
    var underlying = mock(AgentTool.class);
    var calls = new AtomicInteger();
    var boundary =
        new ConversationVisibilityTool(
            underlying,
            "current",
            (target, current) -> {
              calls.incrementAndGet();
              return true;
            });
    for (String input :
        new String[] {
          "{\"conversation\":7}",
          "{\"conversation\":\" padded \"}",
          "{} {}",
          "{\"conversation\":\"a\",\"conversation\":\"b\"}"
        }) assertTrue(boundary.run(input, Home.global()).contains("unavailable"));
    assertEquals(0, calls.get());
    verifyNoInteractions(underlying);
  }

  @Test
  void malformed_later_row_never_reaches_policy_or_exposes_partial_results() {
    var underlying = mock(AgentTool.class);
    when(underlying.schema()).thenReturn(ToolSchema.from("conversation_list", "list", Map.of()));
    when(underlying.run(any(), any(), any()))
        .thenReturn("[{\"id\":\"valid\"},{\"id\":7,\"body\":\"secret\"}]");
    var calls = new AtomicInteger();
    var boundary =
        new ConversationVisibilityTool(
            underlying,
            "current",
            (target, current) -> {
              calls.incrementAndGet();
              return true;
            });
    var result = boundary.run("{}", Home.global());
    assertTrue(result.contains("unavailable"));
    assertFalse(result.contains("secret"));
    assertEquals(0, calls.get());
  }

  @Test
  void an_absent_current_conversation_retains_the_policys_global_read_semantics() {
    var underlying = mock(AgentTool.class);
    when(underlying.schema()).thenReturn(ToolSchema.from("conversation_list", "list", Map.of()));
    when(underlying.run(any(), any(), any()))
        .thenReturn("[{\"id\":\"public\"},{\"id\":\"private\"}]");
    var boundary =
        new ConversationVisibilityTool(
            underlying, null, (target, current) -> current == null && target.equals("public"));
    assertEquals("[{\"id\":\"public\"}]", boundary.run("{}", Home.global()));
  }
}
