package io.aeyer.plowshare.server.messaging;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayWorkerProperties;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MessageInboxWorkerTest {
  private static MessageInboxRepository.Inbox inbox(int project) {
    return new MessageInboxRepository.Inbox(
        "alice",
        "project" + project,
        "worker",
        new Relay.SubscriptionKey(
            new Relay.TopicKey(project, "messaging.agent.fixture"), "messaging.inbox"));
  }

  @Test
  void hintsWaitForLifecycleStartCoalesceAndRespectTheBound() throws Exception {
    var inboxes = mock(MessageInboxes.class);
    var properties = new RelayWorkerProperties();
    properties.setMaxSignalWaiters(1);
    properties.setIdleInterval(Duration.ofMinutes(5));
    var worker = new MessageInboxWorker(inboxes, properties, (account, project) -> Set.of());
    try {
      worker.signal(inbox(1));
      worker.signal(inbox(1));
      worker.signal(inbox(2));
      verifyNoInteractions(inboxes);
      worker.start();
      verify(inboxes, timeout(2000)).poll(inbox(1));
      verify(inboxes, never()).poll(inbox(2));
      // Overflow is only a lost hint: authoritative periodic discovery is still active.
      verify(inboxes, timeout(2000)).poll();
      worker.close();
      worker.signal(inbox(1));
      verify(inboxes, times(1)).poll(inbox(1));
    } finally {
      worker.close();
    }
  }

  @Test
  void signalArrivingDuringAdmissionQueuesAnotherPassWithoutWaitingForDiscovery() throws Exception {
    var inboxes = mock(MessageInboxes.class);
    var properties = new RelayWorkerProperties();
    properties.setIdleInterval(Duration.ofMinutes(5));
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var completed = new CountDownLatch(1);
    doAnswer(
            call -> {
              entered.countDown();
              assertTrue(release.await(2, TimeUnit.SECONDS));
              return 1;
            })
        .doAnswer(
            call -> {
              completed.countDown();
              return 0;
            })
        .when(inboxes)
        .poll(inbox(1));
    try (var worker = new MessageInboxWorker(inboxes, properties, (account, project) -> Set.of())) {
      worker.signal(inbox(1));
      worker.start();
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      worker.signal(inbox(1));
      release.countDown();
      assertTrue(completed.await(2, TimeUnit.SECONDS));
      verify(inboxes, times(2)).poll(inbox(1));
    } finally {
      release.countDown();
    }
  }

  @Test
  void failureOfOneInboxDoesNotBlockAnotherCommittedHint() {
    var inboxes = mock(MessageInboxes.class);
    var properties = new RelayWorkerProperties();
    properties.setIdleInterval(Duration.ofMinutes(5));
    when(inboxes.poll(inbox(1))).thenThrow(new IllegalStateException("fixture gap"));
    try (var worker = new MessageInboxWorker(inboxes, properties, (account, project) -> Set.of())) {
      worker.signal(inbox(1));
      worker.signal(inbox(2));
      worker.start();
      verify(inboxes, timeout(2000)).poll(inbox(2));
    }
  }
}
