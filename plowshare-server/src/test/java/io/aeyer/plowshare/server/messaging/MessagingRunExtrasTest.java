package io.aeyer.plowshare.server.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.RunExtras;
import io.aeyer.plowshare.server.agents.SendMessageTool;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.events.InboxItem;
import io.aeyer.plowshare.server.events.InboxStore;
import io.aeyer.plowshare.server.information.InformationJobs;
import io.aeyer.plowshare.server.personal.PersonalSpaces;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MessagingRunExtrasTest {
  private final MessageService messages = mock(MessageService.class);
  private final ConversationStore conversations = mock(ConversationStore.class);
  private final InboxStore inbox = mock(InboxStore.class);
  private final InformationJobs information = mock(InformationJobs.class);
  private final MessagingRunExtras extras =
      new MessagingRunExtras(messages, conversations, () -> inbox, () -> information);

  @Test
  void a_bound_alert_reads_only_the_selected_item_and_preserves_its_read_state() {
    var home = Home.of(PersonalSpaces.name("alice"));
    var context = new RunExtras.Context(null, "cnv_Chat", null, null, home, "alice");
    when(messages.isTransport("cnv_Chat")).thenReturn(true);
    when(messages.tool(context)).thenReturn(mock(SendMessageTool.class));
    when(messages.mailboxAlertFor(context))
        .thenReturn(Optional.of(new MessageService.PersonalAlert("inb_Result", "fir_One")));
    when(inbox.scheduledResult("alice", "inb_Result", "fir_One"))
        .thenReturn(
            Optional.of(
                new InboxItem(
                    "inb_Result",
                    "alice",
                    "run",
                    "fir_One",
                    "cnv_Source",
                    "ANSWERED",
                    "The result",
                    Instant.EPOCH,
                    null)));
    var read = extras.forRun(context).tools().getLast();
    assertEquals("inbox_alert_read", read.schema().name());
    assertTrue(read.run("{\"item\":\"another\"}", home).contains("The result"));
    verify(inbox).scheduledResult("alice", "inb_Result", "fir_One");
    verify(inbox, never()).markRead(any(), any(), any());
    verify(information).inherit("cnv_Source", "cnv_Chat", "alice");
  }

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
