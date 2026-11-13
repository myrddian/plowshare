package io.aeyer.plowshare.server.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayPayload;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class RelayBoardRepliesTest {
  private final BoardBridgeRepository repository = mock(BoardBridgeRepository.class);
  private final Relay relay = mock(Relay.class);
  private final BoardReplyPublications replies =
      new RelayBoardReplies(
          repository,
          relay,
          UnitOfWork.NONE,
          Duration.ofSeconds(30),
          () -> BoardBridgeFixtures.NOW);

  private BoardBridgeRepository.Pending pending(boolean terminal) {
    return new BoardBridgeRepository.Pending(
        UUID.fromString("11111111-1111-1111-1111-111111111111"),
        7,
        "collector.inbox",
        "original-event",
        "bdt_Topic",
        "bdm_Answer",
        "Full answer 😀",
        "member",
        "reviewer",
        terminal,
        BoardBridgeFixtures.NOW,
        null);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void publishes_full_answers_and_terminal_once_then_removes_only_after_append(boolean terminal) {
    var next = pending(terminal);
    when(repository.pending()).thenReturn(Optional.of(next));
    assertTrue(replies.publishNext());
    var order = inOrder(repository, relay);
    order.verify(repository).pending();
    var draft = ArgumentCaptor.forClass(Relay.Draft.class);
    order.verify(relay).publish(any(), draft.capture());
    order.verify(repository).published(next);
    var actual = (RelayPayload.Text) draft.getValue().payload();
    assertTrue(actual.text().contains("Full answer 😀"));
    assertTrue(actual.text().contains("\"reply_to\":\"original-event\""));
    assertTrue(actual.text().contains("\"kind\":\"" + (terminal ? "terminal" : "post") + "\""));
    assertEquals("board:bridge", draft.getValue().publisher());
  }

  @Test
  void a_failed_append_leaves_the_answer_pending_and_propagates_the_failure() {
    when(repository.pending()).thenReturn(Optional.of(pending(true)));
    when(relay.publish(any(), any())).thenThrow(new IllegalStateException("failure"));
    assertThrows(IllegalStateException.class, replies::publishNext);
    verify(repository, never()).published(any());
    verify(repository).defer(pending(true), BoardBridgeFixtures.NOW.plusSeconds(30));
  }

  @Test
  void empty_outbox_does_not_publish_or_create_topics() {
    assertFalse(replies.publishNext());
    verifyNoInteractions(relay);
  }
}
