package io.aeyer.plowshare.server.events;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.messaging.MessageWake;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DispatcherWakeOwnershipTest {
  private final FiringStore firings = mock(FiringStore.class);
  private final Dispatcher dispatcher =
      new Dispatcher(
          firings,
          mock(TriggerStore.class),
          mock(Dispatcher.Runner.class),
          mock(Inbox.class),
          Instant::now);

  @Test
  void typedMessagingWakeUsesItsOwnHandlerWithoutABoardMultiplexer() {
    var firing = mock(FiringRecord.class);
    when(firing.id()).thenReturn("fir_Message");
    when(firing.topic()).thenReturn("ins_Recipient");
    when(firing.data()).thenReturn(new EventPayload.Message(new MessageWake("bdm_Request")));
    when(firings.oldestWaiting("target")).thenReturn(Optional.of(firing));
    var messages = mock(Dispatcher.Wakes.class);
    var board = mock(Dispatcher.Wakes.class);
    when(messages.start(eq(firing), any())).thenReturn("job_Message");
    dispatcher.registerWakes(
        "messaging", wake -> wake.data() instanceof EventPayload.Message, messages);
    dispatcher.registerWakes("board", wake -> wake.data() instanceof EventPayload.Seat, board);
    dispatcher.consume("target", (id, at) -> true);
    verify(messages).start(eq(firing), any());
    verify(board, never()).busy(any());
    verify(board, never()).start(any(), any());
    verify(firings).startedAs("fir_Message", "job_Message");
  }

  @Test
  void overlappingAndDuplicateOwnersFailBeforeAnyEffect() {
    var firing = mock(FiringRecord.class);
    when(firing.topic()).thenReturn("ins_Recipient");
    when(firings.oldestWaiting("target")).thenReturn(Optional.of(firing));
    var handler = mock(Dispatcher.Wakes.class);
    dispatcher.registerWakes("one", wake -> true, handler);
    assertThrows(
        IllegalStateException.class, () -> dispatcher.registerWakes("one", wake -> true, handler));
    dispatcher.registerWakes("two", wake -> true, handler);
    assertThrows(IllegalStateException.class, () -> dispatcher.consume("target", (id, at) -> true));
    verify(handler, never()).start(any(), any());
  }

  @Test
  void oneOwnersRecoveryFailureDoesNotSuppressAnotherOwner() {
    var first = mock(Runnable.class);
    var second = mock(Runnable.class);
    doThrow(new IllegalStateException("fixture")).when(first).run();
    dispatcher.registerWakeRecovery("messaging", first);
    dispatcher.registerWakeRecovery("board", second);
    dispatcher.recoverWakes();
    verify(first).run();
    verify(second).run();
  }
}
