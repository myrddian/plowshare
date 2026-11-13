package io.aeyer.plowshare.server.orchestrations;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayConsumerRepository;
import io.aeyer.plowshare.server.relay.RelayPayload;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RelayChildCompletionsTest {
  private final Relay relay = mock(Relay.class);
  private final RelayConsumerRepository consumers = mock(RelayConsumerRepository.class);
  private final ChildCompletions repository = mock(ChildCompletions.class);
  private final ChildCompletionDelivery delivery = mock(ChildCompletionDelivery.class);
  private final Instant now = Instant.parse("2026-11-13T00:00:00Z");
  private final Relay.TopicKey topic = new Relay.TopicKey(7, ChildCompletions.TOPIC);
  private final Relay.SubscriptionKey group =
      new Relay.SubscriptionKey(topic, ChildCompletions.GROUP);
  private final RelayConsumerRepository.Lease lease =
      new RelayConsumerRepository.Lease(
          group, "worker", ChildCompletions.GROUP, 1, now.plusSeconds(120));
  private final ChildCompletions.Completion completion =
      new ChildCompletions.Completion(7, "orc_child", "orc_parent", now);
  private final UnitOfWork work =
      new UnitOfWork() {
        public <T> T inTransaction(Supplier<T> body) {
          return body.get();
        }
      };

  @BeforeEach
  void ready() {
    when(consumers.acquire(eq(group), any(), eq(ChildCompletions.GROUP), any()))
        .thenReturn(Optional.of(lease));
    when(repository.pending(lease, 8)).thenReturn(List.of(completion));
    when(repository.current(any(), any())).thenReturn(List.of());
    when(relay.read(group, 8))
        .thenReturn(
            new Relay.Read(new Relay.Subscription(group, 0, now), Optional.empty(), List.of()));
  }

  @Test
  void another_live_consumer_prevents_all_reads_and_parent_effects() {
    when(consumers.acquire(eq(group), any(), any(), any())).thenReturn(Optional.empty());
    try (var consumer = new RelayChildCompletions(relay, consumers, repository, delivery, work)) {
      consumer.consume(7, 8);
    }
    verifyNoInteractions(repository, delivery);
    verify(relay, never()).read(any(), anyInt());
  }

  @Test
  void owning_recovery_delivers_without_a_retained_publication_and_keeps_one_group_identity() {
    doAnswer(
            call -> {
              ((Runnable) call.getArgument(1)).run();
              return null;
            })
        .when(delivery)
        .childCompleted(eq(completion), any());
    try (var consumer = new RelayChildCompletions(relay, consumers, repository, delivery, work)) {
      consumer.consume(7, 8);
    }
    verify(delivery).childCompleted(eq(completion), any());
    verify(relay).subscribe(group, Relay.Start.OLDEST_RETAINED);
    verify(consumers).release(lease);
    verify(consumers, never()).advanceSeen(any(), anyLong());
  }

  @Test
  void a_gap_acknowledges_availability_after_the_durable_recovery_scan_not_delivery() {
    when(relay.read(group, 8))
        .thenReturn(
            new Relay.Read(
                new Relay.Subscription(group, 0, now), Optional.of(new Relay.Gap(0, 4)), List.of()),
            new Relay.Read(new Relay.Subscription(group, 4, now), Optional.empty(), List.of()));
    try (var consumer = new RelayChildCompletions(relay, consumers, repository, delivery, work)) {
      consumer.consume(7, 8);
    }
    var order = inOrder(repository, delivery, consumers);
    order.verify(repository).pending(lease, 8);
    order.verify(delivery).childCompleted(eq(completion), any());
    order.verify(consumers).acknowledgeGap(lease, 4);
    verify(consumers).release(lease);
  }

  @Test
  void a_foreign_notice_cannot_supply_a_child_parent_or_text() {
    when(repository.pending(lease, 8)).thenReturn(List.of());
    var event =
        new Relay.Draft(
            "foreign",
            "account:other",
            now,
            null,
            null,
            new RelayPayload.Lifecycle(
                ChildCompletions.TOPIC, "orc_foreign", "finished", "cnv_foreign", "orc_wrong"));
    when(relay.read(group, 8))
        .thenReturn(
            new Relay.Read(
                new Relay.Subscription(group, 0, now),
                Optional.empty(),
                List.of(new Relay.Publication(topic, 1, now, event))));
    try (var consumer = new RelayChildCompletions(relay, consumers, repository, delivery, work)) {
      consumer.consume(7, 8);
    }
    verifyNoInteractions(delivery);
    verify(consumers).advanceSeen(lease, 1);
  }

  @Test
  void trusted_notices_select_scoped_terminal_attempts_before_offset_advancement() {
    var event =
        new Relay.Draft(
            "source:fixture",
            "system.source",
            now,
            null,
            null,
            new RelayPayload.Lifecycle(
                ChildCompletions.TOPIC,
                completion.child(),
                "failed",
                "cnv_child",
                completion.parent()));
    when(relay.read(group, 8))
        .thenReturn(
            new Relay.Read(
                new Relay.Subscription(group, 0, now),
                Optional.empty(),
                List.of(new Relay.Publication(topic, 1, now, event))));
    when(repository.current(lease, List.of(completion))).thenReturn(List.of(completion));
    try (var consumer = new RelayChildCompletions(relay, consumers, repository, delivery, work)) {
      consumer.consume(7, 8);
    }
    var order = inOrder(repository, delivery, consumers);
    order.verify(repository).current(lease, List.of(completion));
    order.verify(delivery).childCompleted(eq(completion), any());
    order.verify(consumers).advanceSeen(lease, 1);
    // The pending recovery page may contain the same busy attempt. It is never tried twice in one
    // pass.
    verify(delivery).childCompleted(eq(completion), any());
  }

  @Test
  void lost_ownership_or_a_delivery_failure_does_not_advance_the_cursor() {
    var lost = new AtomicBoolean();
    doAnswer(
            call -> {
              if (lost.get()) throw new IllegalStateException("lease lost");
              return null;
            })
        .when(consumers)
        .requireOwned(lease);
    doAnswer(
            call -> {
              lost.set(true);
              ((Runnable) call.getArgument(1)).run();
              return null;
            })
        .when(delivery)
        .childCompleted(eq(completion), any());
    try (var consumer = new RelayChildCompletions(relay, consumers, repository, delivery, work)) {
      assertThrows(IllegalStateException.class, () -> consumer.consume(7, 8));
    }
    verify(consumers, never()).advanceSeen(any(), anyLong());
    verify(consumers).release(lease);
  }
}
