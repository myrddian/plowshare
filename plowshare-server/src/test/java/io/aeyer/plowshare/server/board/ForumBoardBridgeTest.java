package io.aeyer.plowshare.server.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayPayload;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ForumBoardBridgeTest {
  private final BoardForum board = mock(BoardForum.class);
  private final BoardRecords records = mock(BoardRecords.class);
  private final BoardPostRepository receipts = mock(BoardPostRepository.class);
  private final BoardBridgeRepository replies = mock(BoardBridgeRepository.class);
  private final BoardClosing closing = mock(BoardClosing.class);
  private final SwarmCatalog swarms = mock(SwarmCatalog.class);
  private final Relay relay = mock(Relay.class);
  private final BoardBridge bridge =
      new ForumBoardBridge(
          board, records, receipts, replies, closing, swarms, relay, UnitOfWork.NONE);
  private final UUID request = UUID.randomUUID();

  private BoardBridge.Command command(BoardBridge.Target target) {
    return new BoardBridge.Command(
        request,
        "publisher",
        7,
        "source-event",
        target,
        new BoardBridge.Payload("Request body", "requester.inbox"),
        null);
  }

  @BeforeEach
  void setup() {
    when(records.topic("bdt_Topic")).thenReturn(Optional.of(BoardBridgeFixtures.topic("open")));
    when(records.message("bdm_Post"))
        .thenReturn(Optional.of(BoardBridgeFixtures.message("bdm_Post", BoardMessage.POST)));
    when(relay.topic(new Relay.TopicKey(7, "requester.inbox")))
        .thenReturn(
            new Relay.Topic(
                new Relay.TopicKey(7, "requester.inbox"),
                RelayPayload.Kind.TEXT,
                Relay.Policy.systemDefault(),
                0,
                0));
  }

  @Test
  void post_is_attributed_to_the_publisher_and_retains_the_reply_destination_atomically() {
    when(board.post(any()))
        .thenReturn(
            new Board.Posted(
                BoardBridgeFixtures.message("bdm_Post", BoardMessage.POST), List.of(), false));
    var result = bridge.accept(command(new BoardBridge.Post("bdt_Topic")));
    assertEquals(new BoardBridge.Receipt("bdt_Topic", "bdm_Post"), result);
    verify(board)
        .post(
            new Board.Post(
                "bdt_Topic",
                BoardMessage.BY_PERSON,
                "publisher",
                null,
                null,
                BoardMessage.POST,
                null,
                "Request body",
                null,
                List.of(),
                false));
    verify(replies)
        .bind(
            new BoardBridgeRepository.Binding(
                request,
                "publisher",
                "bdm_Post",
                "bdt_Topic",
                7,
                "requester.inbox",
                "source-event",
                null));
    verifyNoInteractions(
        closing, swarms); // A post does not demand a destination membership or seat.
  }

  @Test
  void open_uses_the_named_swarm_and_original_publisher_without_an_opener_seat() {
    var topic = BoardBridgeFixtures.topic("open");
    var message = BoardBridgeFixtures.message("bdm_Post", BoardMessage.POST);
    when(board.open(any())).thenReturn(new Board.Opened(topic, message, List.of()));
    assertEquals(
        new BoardBridge.Receipt(topic.id(), message.id()),
        bridge.accept(command(new BoardBridge.Open("destination", "research"))));
    var selected = ArgumentCaptor.forClass(Board.Open.class);
    verify(board).open(selected.capture());
    assertEquals("publisher", selected.getValue().account());
    assertEquals(BoardTopic.BY_PERSON, selected.getValue().openerKind());
    assertEquals("research", selected.getValue().swarm());
    assertEquals("destination", selected.getValue().home().project());
  }

  @Test
  void unavailable_swarm_and_absent_or_wrong_kind_reply_topic_refuse_before_writing() {
    when(swarms.select("destination", "missing")).thenThrow(new Board.Refused("Unavailable"));
    assertThrows(
        Board.Refused.class,
        () -> bridge.require(command(new BoardBridge.Open("destination", "missing"))));
    when(relay.topic(new Relay.TopicKey(7, "requester.inbox")))
        .thenReturn(
            new Relay.Topic(
                new Relay.TopicKey(7, "requester.inbox"),
                RelayPayload.Kind.EMPTY,
                Relay.Policy.systemDefault(),
                0,
                0));
    assertThrows(
        Board.Refused.class, () -> bridge.accept(command(new BoardBridge.Post("bdt_Topic"))));
    verifyNoInteractions(board, replies);
  }

  @Test
  void a_duplicate_delivery_recovers_its_receipt_without_another_post_or_binding() {
    when(receipts.lockAndRead(eq("publisher"), eq(request), any()))
        .thenReturn(Optional.of("bdm_Post"));
    when(records.topic("bdt_Topic")).thenReturn(Optional.of(BoardBridgeFixtures.topic("closed")));
    assertEquals("bdm_Post", bridge.accept(command(new BoardBridge.Post("bdt_Topic"))).message());
    verifyNoInteractions(board, replies);
  }

  @Test
  void closed_missing_private_and_exhausted_topics_refuse_without_open_fallback() {
    var cmd = command(new BoardBridge.Post("bdt_Topic"));
    when(records.topic("bdt_Topic")).thenReturn(Optional.of(BoardBridgeFixtures.topic("closed")));
    assertThrows(Board.Refused.class, () -> bridge.require(cmd));
    when(records.topic("bdt_Topic")).thenReturn(Optional.empty());
    assertThrows(Board.Refused.class, () -> bridge.require(cmd));
    when(records.topic("bdt_Topic"))
        .thenReturn(Optional.of(BoardBridgeFixtures.topic("exhausted")));
    assertThrows(Board.Refused.class, () -> bridge.require(cmd));
    when(records.topic("bdt_Topic")).thenReturn(Optional.of(BoardBridgeFixtures.topic("open")));
    when(records.messagingTopic("bdt_Topic")).thenReturn(true);
    assertThrows(Board.Refused.class, () -> bridge.require(cmd));
    verifyNoInteractions(board, replies, receipts);
  }

  @Test
  void close_uses_the_publishing_accounts_live_permission_without_an_opener_seat() {
    var selected = BoardBridgeFixtures.topic("open");
    when(closing.require(eq("publisher"), any(), eq(selected))).thenReturn(selected);
    when(board.close(any(), eq(selected)))
        .thenReturn(
            new Board.Closed(
                BoardBridgeFixtures.topic("closed"),
                BoardBridgeFixtures.message("bdm_Resolution", BoardMessage.RESOLUTION)));
    bridge.accept(command(new BoardBridge.Close("bdt_Topic")));
    verify(closing).require(eq("publisher"), any(), eq(selected));
    verify(board)
        .close(
            new Board.Close(
                "bdt_Topic", BoardMessage.BY_PERSON, "publisher", null, "Request body", List.of()),
            selected);
    when(closing.require(eq("publisher"), any(), eq(selected)))
        .thenThrow(new Board.Refused("denied"));
    assertThrows(
        Board.Refused.class, () -> bridge.accept(command(new BoardBridge.Close("bdt_Topic"))));
    verify(board, times(1)).close(any(), any());
  }

  @Test
  void uncertain_receipt_inspection_does_not_recheck_destination_availability_or_mutate() {
    when(records.topic("bdt_Topic")).thenReturn(Optional.of(BoardBridgeFixtures.topic("closed")));
    when(receipts.find(eq("publisher"), eq(request), any())).thenReturn(Optional.of("bdm_Post"));
    assertTrue(bridge.inspect(command(new BoardBridge.Post("bdt_Topic"))).isPresent());
    verifyNoInteractions(board, closing, replies, relay);
    verify(receipts, never()).lockAndRead(any(), any(), any());
  }
}
