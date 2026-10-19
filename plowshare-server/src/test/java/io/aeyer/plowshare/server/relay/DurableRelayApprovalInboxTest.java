package io.aeyer.plowshare.server.relay;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.approvals.ApprovalNotifications;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DurableRelayApprovalInboxTest {
  @Test
  void missing_notifications_are_reconciled_without_a_publication_or_socket_signal() {
    var relay = mock(Relay.class);
    var consumers = mock(RelayConsumerRepository.class);
    var repository = mock(RelayApprovalInboxRepository.class);
    var notifications = mock(ApprovalNotifications.class);
    var topic = new Relay.TopicKey(7, "approval.requested");
    var subscription = new Relay.SubscriptionKey(topic, RelayApprovalInboxRepository.SUBSCRIBER);
    var lease =
        new RelayConsumerRepository.Lease(
            subscription, "worker", "builtin.approval-inbox", 1, Instant.now().plusSeconds(120));
    when(consumers.acquire(eq(subscription), anyString(), anyString(), any()))
        .thenReturn(Optional.of(lease));
    when(repository.missing(7, null, 10)).thenReturn(List.of("apr_failed", "apr_ready"));
    when(notifications.requested("apr_failed", 7))
        .thenThrow(new IllegalStateException("Inbox unavailable"));
    when(notifications.requested("apr_ready", 7)).thenReturn(true);
    var read = mock(Relay.Read.class);
    when(read.gap()).thenReturn(Optional.empty());
    when(read.publications()).thenReturn(List.of());
    when(relay.read(subscription, 10)).thenReturn(read);
    var adapter = new DurableRelayApprovalInbox(relay, consumers, repository, notifications);

    adapter.consume(topic, 10);
    adapter.consume(topic, 10);

    verify(notifications, times(2)).requested("apr_failed", 7);
    verify(notifications, times(2)).requested("apr_ready", 7);
    verify(consumers, times(2)).release(lease);
    verify(relay, times(2)).subscribe(subscription, Relay.Start.OLDEST_RETAINED);
    verify(repository, never()).advance(any(), anyLong());
  }

  @Test
  void a_human_notification_consumer_never_accepts_system_or_unrelated_topics() {
    var relay = mock(Relay.class);
    var adapter =
        new DurableRelayApprovalInbox(
            relay,
            mock(RelayConsumerRepository.class),
            mock(RelayApprovalInboxRepository.class),
            mock(ApprovalNotifications.class));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            adapter.consume(
                new Relay.TopicKey(Relay.SystemScope.SERVER, "approval.requested"), 10));
    assertThrows(
        IllegalArgumentException.class,
        () -> adapter.consume(new Relay.TopicKey(7, "job.started"), 10));
    verifyNoInteractions(relay);
  }
}
