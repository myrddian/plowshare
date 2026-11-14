package io.aeyer.plowshare.server.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayPayload;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

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
    return pending(terminal, BoardBridgeFixtures.REPLY_GENERATION);
  }

  private BoardBridgeRepository.Pending pending(boolean terminal, UUID generation) {
    return new BoardBridgeRepository.Pending(
        UUID.fromString("11111111-1111-1111-1111-111111111111"),
        7,
        "collector.inbox",
        generation,
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

  @BeforeEach
  void destination() {
    when(relay.publish(any(), any(), any()))
        .thenAnswer(
            call ->
                new Relay.Appended(
                    new Relay.Publication(
                        call.getArgument(0), 1, BoardBridgeFixtures.NOW, call.getArgument(2))));
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
    order.verify(relay).publish(any(), any(), draft.capture());
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
    doThrow(new IllegalStateException("failure")).when(relay).publish(any(), any(), any());
    assertThrows(IllegalStateException.class, replies::publishNext);
    verify(repository, never()).published(any());
    verify(repository).defer(pending(true), BoardBridgeFixtures.NOW.plusSeconds(30));
  }

  @ParameterizedTest
  @EnumSource(
      value = BoardBridgeRepository.Destination.class,
      names = {"MISSING", "REPLACED", "UNVERIFIED", "INVALID_KIND"})
  void unavailable_or_recreated_destinations_are_terminal_without_publishing_elsewhere(
      BoardBridgeRepository.Destination destination) {
    var next =
        pending(
            false,
            destination == BoardBridgeRepository.Destination.UNVERIFIED
                ? null
                : BoardBridgeFixtures.REPLY_GENERATION);
    when(repository.pending()).thenReturn(Optional.of(next));
    if (destination != BoardBridgeRepository.Destination.UNVERIFIED)
      doReturn(
              new Relay.DestinationUnavailable(
                  Relay.DestinationFailure.valueOf(destination.name())))
          .when(relay)
          .publish(any(), any(), any());
    assertTrue(replies.publishNext());
    verify(repository).undeliverable(next, destination);
    verify(repository, never()).published(any());
    verify(repository, never()).defer(any(), any());
    if (destination == BoardBridgeRepository.Destination.UNVERIFIED) verifyNoInteractions(relay);
    else verify(relay).publish(any(), any(), any());
  }

  @Test
  void empty_outbox_does_not_publish_or_create_topics() {
    assertFalse(replies.publishNext());
    verifyNoInteractions(relay);
  }

  @Test
  void pending_failure_diagnostic_names_request_and_destination_without_exception_content() {
    var next = pending(true);
    when(repository.pending()).thenReturn(Optional.of(next));
    doThrow(new IllegalStateException("SECRET-BODY-AND-TOKEN"))
        .when(relay)
        .publish(any(), any(), any());
    var logger = (Logger) LoggerFactory.getLogger(RelayBoardReplies.class);
    var recorded = new ListAppender<ILoggingEvent>();
    recorded.start();
    logger.addAppender(recorded);
    try {
      assertThrows(IllegalStateException.class, replies::publishNext);
      var event =
          recorded.list.stream()
              .filter(e -> e.getFormattedMessage().contains("APPEND_FAILED"))
              .findFirst()
              .orElseThrow();
      assertTrue(event.getFormattedMessage().contains(next.requestId().toString()));
      assertTrue(
          event
              .getFormattedMessage()
              .contains("project=7 topic=collector.inbox message=bdm_Answer"));
      assertFalse(event.getFormattedMessage().contains("SECRET"));
      assertNull(event.getThrowableProxy());
    } finally {
      logger.detachAppender(recorded);
      recorded.stop();
    }
  }

  @ParameterizedTest
  @EnumSource(BoardNativeRequests.Reply.class)
  void native_destination_uses_typed_no_effect_outcome_before_atomic_outbox_settlement(
      BoardNativeRequests.Reply outcome) {
    var source = UUID.fromString("55555555-5555-5555-5555-555555555555");
    var old = pending(true);
    var next =
        new BoardBridgeRepository.Pending(
            old.requestId(),
            new BoardBridgeRepository.NativeDestination(source),
            old.replyTo(),
            old.topic(),
            old.message(),
            old.body(),
            old.authorKind(),
            old.author(),
            old.terminal(),
            old.postedAt(),
            old.causation());
    var nativeRequests = mock(BoardNativeRequests.class);
    var worker =
        new RelayBoardReplies(
            repository,
            relay,
            UnitOfWork.NONE,
            Duration.ofSeconds(30),
            () -> BoardBridgeFixtures.NOW,
            nativeRequests);
    when(repository.pending()).thenReturn(Optional.of(next));
    when(nativeRequests.reply(next)).thenReturn(outcome);
    assertTrue(worker.publishNext());
    if (outcome == BoardNativeRequests.Reply.PUBLISHED) verify(repository).published(next);
    else
      verify(repository).undeliverable(next, BoardBridgeRepository.Destination.NATIVE_UNAVAILABLE);
    verifyNoInteractions(relay);
  }

  @Test
  void native_outcome_debt_and_forum_answers_are_drained_fairly() {
    var nativeRequests = mock(BoardNativeRequests.class);
    var worker =
        new RelayBoardReplies(
            repository,
            relay,
            UnitOfWork.NONE,
            Duration.ofSeconds(30),
            () -> BoardBridgeFixtures.NOW,
            nativeRequests);
    when(nativeRequests.publishNextOutcome()).thenReturn(true);
    when(repository.pending()).thenReturn(Optional.of(pending(false)));
    assertTrue(worker.publishNext());
    assertTrue(worker.publishNext());
    verify(repository).published(pending(false));
    verify(nativeRequests).publishNextOutcome();
  }
}
