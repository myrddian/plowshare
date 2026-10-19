package io.aeyer.plowshare.server.orchestrations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.Callers;
import io.aeyer.plowshare.server.agents.DefinitionResolver;
import io.aeyer.plowshare.server.agents.Speaker;
import io.aeyer.plowshare.server.agents.Turn;
import io.aeyer.plowshare.server.faults.CallerFault;
import org.junit.jupiter.api.Test;

/**
 * Notification transport is existing behavior; current recipient authority admits its model turn.
 */
class NotificationRecipientAuthorityTest {
  Turn turn = mock(Turn.class);
  Callers callers = mock(Callers.class);
  CallerVoice voice = OrchestrationsConfig.callerVoice(turn, callers);
  Speaker source = Speaker.orchestration("run");

  @Test
  void result_delivery_uses_the_explicit_recipient_and_current_agent_definition() {
    var resolved = new DefinitionResolver.Caller(null, null);
    var definition = mock(AgentDefinition.class);
    when(turn.homeOf("conversation")).thenReturn(Home.of("project"));
    when(callers.callerForConversation("conversation", null)).thenReturn(resolved);
    when(callers.requireAgent("interlocutor", resolved)).thenReturn(definition);
    when(turn.isSpeaking("conversation")).thenReturn(true);
    assertTrue(voice.isSpeaking("conversation"));
    voice.speakForAccount("conversation", "interlocutor", "result", source, "recipient");
    verify(callers).requireConversationProject("conversation", "recipient");
    verify(callers).requireWork("project", "recipient");
    verify(turn)
        .deliverForExecutor(
            eq("conversation"), eq(definition), eq("result"), eq(source), any(), eq("recipient"));
    when(callers.requireAgent("gone", resolved))
        .thenThrow(new IllegalArgumentException("agent removed"));
    assertThrows(
        Turn.Refused.class,
        () -> voice.speakForAccount("conversation", "gone", "result", source, "recipient"));
  }

  @Test
  void legacy_delivery_cannot_infer_an_execution_account_from_custody() {
    assertThrows(Turn.Refused.class, () -> voice.speak("conversation", "agent", "result", source));
    verifyNoInteractions(turn, callers);
  }

  @Test
  void lost_read_access_is_a_disclosure_denial_before_any_model_turn() {
    doThrow(new CallerFault("READ revoked"))
        .when(callers)
        .requireConversationProject("conversation", "recipient");
    assertThrows(
        CallerFault.class,
        () ->
            voice.speakForAccount("conversation", "agent", "private result", source, "recipient"));
    verifyNoInteractions(turn);
  }

  @Test
  void read_access_does_not_supply_permission_to_execute_a_notification() {
    when(turn.homeOf("conversation")).thenReturn(Home.of("project"));
    doThrow(new CallerFault("WRITE revoked")).when(callers).requireWork("project", "recipient");
    assertThrows(
        Turn.Refused.class,
        () -> voice.speakForAccount("conversation", "agent", "result", source, "recipient"));
    verify(turn, never()).deliverForExecutor(any(), any(), any(), any(), any(), any());
    verify(callers, never()).requireAgent(any(), any());
  }
}
