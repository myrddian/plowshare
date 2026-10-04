package io.aeyer.plowshare.server.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.agents.Speaker;
import io.aeyer.plowshare.server.agents.Turn;
import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.hooks.Handover;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PersonDeliveryTest {

  @Test
  void a_machine_conversation_goes_to_its_pinned_accounts_inbox() {
    ConversationStore conversations = mock(ConversationStore.class);
    ConversationRecord record = mock(ConversationRecord.class);
    PersonDelivery.Voice voice = mock(PersonDelivery.Voice.class);
    PersonDelivery.Inbox inbox = mock(PersonDelivery.Inbox.class);
    when(conversations.find("cnv_event")).thenReturn(Optional.of(record));
    when(record.origin()).thenReturn(Origin.EVENT);

    PersonDelivery.Result result =
        new PersonDelivery(conversations, voice, inbox)
            .deliver(
                "cnv_event",
                "coder",
                "enzo",
                "approval",
                "May I run it?",
                Speaker.approval("apr_1"));

    assertEquals(PersonDelivery.Result.DELIVERED, result);
    verify(inbox).notify("enzo", "approval", "May I run it?", null);
  }

  /** V68: a question's notice names what it asks about, so it can leave the inbox once settled. */
  @Test
  void a_question_reaches_the_inbox_saying_what_it_asks_about() {
    ConversationStore conversations = mock(ConversationStore.class);
    PersonDelivery.Inbox inbox = mock(PersonDelivery.Inbox.class);

    PersonDelivery.Result result =
        new PersonDelivery(conversations, mock(PersonDelivery.Voice.class), inbox)
            .deliver(
                null,
                "coder",
                "enzo",
                "approval",
                "May I run it?",
                Speaker.approval("apr_1"),
                "approval:apr_1");

    assertEquals(PersonDelivery.Result.DELIVERED, result);
    verify(inbox).notify("enzo", "approval", "May I run it?", "approval:apr_1");
  }

  @Test
  void a_busy_person_conversation_is_left_for_its_when_free_drain() {
    ConversationStore conversations = mock(ConversationStore.class);
    ConversationRecord record = mock(ConversationRecord.class);
    PersonDelivery.Voice voice = mock(PersonDelivery.Voice.class);
    PersonDelivery.Inbox inbox = mock(PersonDelivery.Inbox.class);
    when(conversations.find("cnv_turn")).thenReturn(Optional.of(record));
    when(record.origin()).thenReturn(Origin.TURN);
    when(voice.isSpeaking("cnv_turn")).thenReturn(true);

    PersonDelivery.Result result =
        new PersonDelivery(conversations, voice, inbox)
            .deliver(
                "cnv_turn",
                "coder",
                "enzo",
                "approval",
                "May I run it?",
                Speaker.approval("apr_1"));

    assertEquals(PersonDelivery.Result.BUSY, result);
  }

  @Test
  void an_idle_person_conversation_is_spoken_into_as_whoever_delivers() {
    ConversationStore conversations = mock(ConversationStore.class);
    ConversationRecord record = mock(ConversationRecord.class);
    PersonDelivery.Voice voice = mock(PersonDelivery.Voice.class);
    when(conversations.find("cnv_turn")).thenReturn(Optional.of(record));
    when(record.origin()).thenReturn(Origin.TURN);

    PersonDelivery.Result result =
        new PersonDelivery(conversations, voice, mock(PersonDelivery.Inbox.class))
            .deliver(
                "cnv_turn",
                "coder",
                "enzo",
                "approval",
                "May I run it?",
                Speaker.approval("apr_1"));

    assertEquals(PersonDelivery.Result.DELIVERED, result);
    verify(voice).speak("cnv_turn", "coder", "May I run it?", Speaker.approval("apr_1"));
  }

  @Test
  void a_send_says_where_it_went_and_the_route_says_where_it_will_try_first() {
    ConversationStore conversations = mock(ConversationStore.class);
    ConversationRecord turn = mock(ConversationRecord.class);
    when(conversations.find("cnv_turn")).thenReturn(Optional.of(turn));
    when(turn.origin()).thenReturn(Origin.TURN);
    PersonDelivery people =
        new PersonDelivery(
            conversations, mock(PersonDelivery.Voice.class), mock(PersonDelivery.Inbox.class));

    assertEquals(Handover.CONVERSATION, people.routeFor("cnv_turn", "enzo"));
    assertEquals(Handover.INBOX, people.routeFor(null, "enzo"));
    assertEquals(Handover.NOWHERE, people.routeFor(null, null));
    assertEquals(
        new PersonDelivery.Sent(PersonDelivery.Result.DELIVERED, Handover.CONVERSATION),
        people.send(
            "cnv_turn", "coder", "enzo", "orchestration", "done", Speaker.orchestration("orc_1")));
    assertEquals(
        new PersonDelivery.Sent(PersonDelivery.Result.DELIVERED, Handover.INBOX),
        people.send(
            null, "coder", "enzo", "orchestration", "done", Speaker.orchestration("orc_1")));
    assertEquals(
        new PersonDelivery.Sent(PersonDelivery.Result.NOWHERE, Handover.NOWHERE),
        people.send(null, "coder", null, "orchestration", "done", Speaker.orchestration("orc_1")));
  }

  /**
   * Ruling F8: the route is where a send will try first, and the send says where it went. A refused
   * speak falls back to the inbox, so the two differ, and each says what it knew.
   */
  @Test
  void a_refused_speak_is_routed_to_the_conversation_and_sent_to_the_inbox() {
    ConversationStore conversations = mock(ConversationStore.class);
    ConversationRecord turn = mock(ConversationRecord.class);
    PersonDelivery.Voice voice = mock(PersonDelivery.Voice.class);
    PersonDelivery.Inbox inbox = mock(PersonDelivery.Inbox.class);
    when(conversations.find("cnv_turn")).thenReturn(Optional.of(turn));
    when(turn.origin()).thenReturn(Origin.TURN);
    doThrow(new Turn.Refused("budget spent"))
        .when(voice)
        .speak("cnv_turn", "coder", "done", Speaker.orchestration("orc_1"));
    PersonDelivery people = new PersonDelivery(conversations, voice, inbox);

    assertEquals(Handover.CONVERSATION, people.routeFor("cnv_turn", "enzo"));
    assertEquals(
        new PersonDelivery.Sent(PersonDelivery.Result.DELIVERED, Handover.INBOX),
        people.send(
            "cnv_turn", "coder", "enzo", "orchestration", "done", Speaker.orchestration("orc_1")));
    verify(inbox).notify("enzo", "orchestration", "done", null);
  }
}
