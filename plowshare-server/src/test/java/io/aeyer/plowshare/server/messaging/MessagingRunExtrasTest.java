package io.aeyer.plowshare.server.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.RunExtras;
import io.aeyer.plowshare.server.agents.SendMessageTool;
import io.aeyer.plowshare.server.archive.ConversationStore;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MessagingRunExtrasTest {
  private final MessageService messages = mock(MessageService.class);
  private final ConversationStore conversations = mock(ConversationStore.class);
  private final MessagingRunExtras extras = new MessagingRunExtras(messages, conversations);

  @Test
  void retainedMessagingContextReceivesItsToolWithoutAnOwnerRefetch() {
    var context = new RunExtras.Context(null, "cnv_Message", null, null);
    var tool = mock(SendMessageTool.class);
    when(messages.isTransport("cnv_Message")).thenReturn(true);
    when(messages.tool(context)).thenReturn(tool);
    assertEquals(List.of(tool), extras.forRun(context).tools());
    verify(conversations, never()).ownerOf("cnv_Message");
  }

  @Test
  void accountOwnedProjectRunReceivesMessagingIndependentlyOfBoardOptIn() {
    var context =
        new RunExtras.Context(null, "cnv_Person", null, null, Home.of("project"), "alice");
    var tool = mock(SendMessageTool.class);
    when(conversations.ownerOf("cnv_Person")).thenReturn(Optional.of("alice"));
    when(messages.tool(context)).thenReturn(tool);
    assertEquals(List.of(tool), extras.forRun(context).tools());
  }

  @Test
  void globalAndUnownedContextsDoNotReceiveAMessageTool() {
    var global = new RunExtras.Context(null, "cnv_Global", null, null, Home.global(), "alice");
    assertSame(RunExtras.Extras.NONE, extras.forRun(global));
    var unowned = new RunExtras.Context(null, "cnv_Unowned", null, null, Home.of("project"), null);
    when(conversations.ownerOf("cnv_Unowned")).thenReturn(Optional.empty());
    assertSame(RunExtras.Extras.NONE, extras.forRun(unowned));
    verify(messages, never()).tool(global);
    verify(messages, never()).tool(unowned);
  }
}
