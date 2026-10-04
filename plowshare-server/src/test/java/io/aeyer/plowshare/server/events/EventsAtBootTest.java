package io.aeyer.plowshare.server.events;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Boot recovery and the ticker thread are two switches: a server with the ticker off must still
 * free what a restart left started, or a target holding an abandoned run waits forever.
 */
class EventsAtBootTest {

  private FiringStore firings;
  private Dispatcher dispatcher;
  private Ticker ticker;
  private ScheduledExecutorService thread;
  private EventsProperties properties;

  @BeforeEach
  void mocks() {
    firings = mock(FiringStore.class);
    dispatcher = mock(Dispatcher.class);
    ticker = mock(Ticker.class);
    thread = mock(ScheduledExecutorService.class);
    properties = new EventsProperties();
    when(firings.targetsWaiting()).thenReturn(List.of("trigger:morning"));
  }

  private void boot() {
    new EventsConfig()
        .eventsAtBoot(firings, dispatcher, ticker, properties, thread)
        .onApplicationEvent(null);
  }

  @Test
  void with_the_ticker_off_a_restart_still_abandons_unfinished_runs_and_drains_what_waits() {
    properties.setTickerEnabled(false);
    boot();
    verify(firings).abandonUnfinished(any(), any());
    verify(ticker).rollForward(any(), any());
    verify(dispatcher).drain("trigger:morning");
    var ordered = org.mockito.Mockito.inOrder(firings, ticker, dispatcher);
    ordered.verify(firings).abandonUnfinished(any(), any());
    ordered.verify(ticker).rollForward(any(), any());
    ordered.verify(dispatcher).recoverWakes();
    ordered.verify(dispatcher).drain("trigger:morning");
    verifyNoInteractions(thread);
  }

  @Test
  void with_recovery_off_nothing_is_touched_but_the_ticker_still_starts() {
    properties.setRecoverAtBoot(false);
    boot();
    verifyNoInteractions(firings);
    verify(dispatcher).recoverWakes();
    verify(ticker, never()).rollForward(any(), any());
    verify(thread).schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS));
  }

  @Test
  void with_both_off_as_on_the_test_classpath_boot_touches_nothing() {
    properties.setRecoverAtBoot(false);
    properties.setTickerEnabled(false);
    boot();
    verifyNoInteractions(firings, ticker, thread);
    verify(dispatcher).recoverWakes();
  }
}
