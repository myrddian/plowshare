package io.aeyer.plowshare.server.relay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.events.Dispatcher;
import io.aeyer.plowshare.server.events.FiringStore;
import io.aeyer.plowshare.server.events.Inbox;
import io.aeyer.plowshare.server.events.TriggerStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RelayInternalWorkersTest {
  private RelayWorkerProperties properties() {
    var properties = new RelayWorkerProperties();
    properties.setIdleInterval(Duration.ofSeconds(1));
    properties.setMaxWorkers(4);
    properties.setConcurrency(2);
    return properties;
  }

  @Test
  void a_blocked_publisher_does_not_serialize_another_topic() throws Exception {
    var sources = mock(RelaySourceRepository.class);
    var inboxes = mock(RelayNativeRepository.class);
    var first = new Relay.TopicKey(1, "job.started");
    var second = new Relay.TopicKey(2, "job.started");
    when(sources.pendingTopics(any(), anyInt())).thenReturn(List.of(first, second));
    when(inboxes.ready(any(), anyInt())).thenReturn(List.of());
    var entered = new CountDownLatch(1);
    var other = new CountDownLatch(1);
    when(sources.publishNext(first))
        .thenAnswer(
            call -> {
              entered.countDown();
              new CountDownLatch(1).await(10, TimeUnit.SECONDS);
              return false;
            });
    when(sources.publishNext(second))
        .thenAnswer(
            call -> {
              other.countDown();
              return false;
            });
    try (var workers =
        new RelayInternalWorkers(
            sources,
            inboxes,
            mock(RelayConsumerRepository.class),
            mock(Relay.class),
            new LocalRelayPublicationSignals(),
            mock(Dispatcher.class),
            properties(),
            true)) {
      workers.start();
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      assertTrue(other.await(3, TimeUnit.SECONDS));
    }
  }

  @Test
  void separate_native_groups_run_independently_and_release_their_own_leases() throws Exception {
    var sources = mock(RelaySourceRepository.class);
    var inboxes = mock(RelayNativeRepository.class);
    var consumers = mock(RelayConsumerRepository.class);
    var relay = mock(Relay.class);
    var dispatcher = mock(Dispatcher.class);
    var topic = new Relay.TopicKey(Relay.SystemScope.SERVER, "message.wake.requested");
    var a =
        new RelayNativeRepository.Binding(
            new Relay.SubscriptionKey(topic, "builtin.wakes.a"), "conversation:a", "operator", 1);
    var b =
        new RelayNativeRepository.Binding(
            new Relay.SubscriptionKey(topic, "builtin.wakes.b"), "conversation:b", "operator", 1);
    when(inboxes.ready(any(), anyInt())).thenReturn(List.of(a, b));
    var aLease =
        new RelayConsumerRepository.Lease(
            a.subscription(), "native:a", "operator", 1, Instant.now().plusSeconds(60));
    var bLease =
        new RelayConsumerRepository.Lease(
            b.subscription(), "native:b", "operator", 1, Instant.now().plusSeconds(60));
    when(consumers.acquire(eq(a.subscription()), anyString(), eq("operator"), any()))
        .thenReturn(Optional.of(aLease));
    when(consumers.acquire(eq(b.subscription()), anyString(), eq("operator"), any()))
        .thenReturn(Optional.of(bLease));
    for (var binding : List.of(a, b))
      when(relay.read(binding.subscription(), 8))
          .thenReturn(
              new Relay.Read(
                  new Relay.Subscription(binding.subscription(), 0, Instant.now()),
                  Optional.empty(),
                  List.of()));
    var blocked = new CountDownLatch(1);
    var other = new CountDownLatch(1);
    doAnswer(
            call -> {
              blocked.countDown();
              new CountDownLatch(1).await(10, TimeUnit.SECONDS);
              return null;
            })
        .when(dispatcher)
        .consume(eq(a.target()), any());
    doAnswer(
            call -> {
              other.countDown();
              return null;
            })
        .when(dispatcher)
        .consume(eq(b.target()), any());
    try (var workers =
        new RelayInternalWorkers(
            sources,
            inboxes,
            consumers,
            relay,
            new LocalRelayPublicationSignals(),
            dispatcher,
            properties(),
            true)) {
      workers.start();
      assertTrue(blocked.await(3, TimeUnit.SECONDS));
      assertTrue(other.await(3, TimeUnit.SECONDS));
    }
    verify(consumers).release(aLease);
    verify(consumers, atLeastOnce()).release(bLease);
  }

  @Test
  void native_availability_is_redirected_without_an_unfenced_legacy_claim() {
    var firings = mock(FiringStore.class);
    var dispatcher =
        new Dispatcher(
            firings,
            mock(TriggerStore.class),
            mock(Dispatcher.Runner.class),
            mock(Inbox.class),
            Instant::now);
    var queue = mock(Dispatcher.NativeQueue.class);
    when(queue.routes("conversation:a")).thenReturn(true);
    dispatcher.useNativeQueue(queue);
    dispatcher.drain("conversation:a");
    verify(queue).signal("conversation:a");
    verifyNoInteractions(firings);
  }

  @Test
  void disabled_internal_workers_neither_discover_nor_redirect() {
    var sources = mock(RelaySourceRepository.class);
    var inboxes = mock(RelayNativeRepository.class);
    try (var workers =
        new RelayInternalWorkers(
            sources,
            inboxes,
            mock(RelayConsumerRepository.class),
            mock(Relay.class),
            new LocalRelayPublicationSignals(),
            mock(Dispatcher.class),
            properties(),
            false)) {
      workers.start();
      assertFalse(workers.routes("conversation:a"));
      workers.signal("conversation:a");
    }
    verifyNoInteractions(sources, inboxes);
  }
}
