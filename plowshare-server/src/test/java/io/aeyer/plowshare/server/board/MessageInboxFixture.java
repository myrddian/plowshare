package io.aeyer.plowshare.server.board;

import io.aeyer.plowshare.server.events.FiringStore;
import io.aeyer.plowshare.server.messaging.JdbcMessageInboxRepository;
import io.aeyer.plowshare.server.messaging.JdbcMessagingRepository;
import io.aeyer.plowshare.server.messaging.MessageDefinitions;
import io.aeyer.plowshare.server.messaging.MessageInboxes;
import io.aeyer.plowshare.server.messaging.MessagePayloadCodec;
import io.aeyer.plowshare.server.messaging.MessagePublications;
import io.aeyer.plowshare.server.messaging.MessageRecord;
import io.aeyer.plowshare.server.messaging.MessageService;
import io.aeyer.plowshare.server.messaging.RelayMessageInboxes;
import io.aeyer.plowshare.server.messaging.RelayMessagePublications;
import io.aeyer.plowshare.server.relay.DurableRelay;
import io.aeyer.plowshare.server.relay.JdbcRelayConsumerRepository;
import io.aeyer.plowshare.server.relay.JdbcRelayDeliveryRepository;
import io.aeyer.plowshare.server.relay.JdbcRelayPayloadStore;
import io.aeyer.plowshare.server.relay.JdbcRelayRepository;
import io.aeyer.plowshare.server.relay.JdbcRelayRetirementRepository;
import java.time.Duration;
import java.util.function.Consumer;

/** Actual broker admission, pulsed after commit instead of starting a timer in database tests. */
final class MessageInboxFixture {
  static MessageInboxes inboxes(BoardFixture fixture) {
    return inboxes(fixture, fixture.drained::add);
  }

  static void pulse(BoardFixture fixture) {
    fixture.messageInboxes.forEach(
        inbox -> {
          inbox.poll();
          inbox.poll();
        });
  }

  static MessageInboxes inboxes(BoardFixture fixture, Consumer<String> drain) {
    return inboxes(fixture, drain, fixture.firings);
  }

  static MessageInboxes inboxes(BoardFixture fixture, Consumer<String> drain, FiringStore firings) {
    var payloads =
        new JdbcRelayPayloadStore(
            fixture.jdbc,
            fixture.work,
            Duration.ofHours(1),
            Duration.ofHours(24),
            Duration.ofDays(30));
    var broker = new JdbcRelayRepository(fixture.jdbc, fixture.work, payloads);
    var relay = new DurableRelay(broker, fixture.clock);
    var consumers =
        new JdbcRelayConsumerRepository(
            fixture.jdbc,
            fixture.work,
            new JdbcRelayDeliveryRepository(fixture.jdbc, fixture.work),
            broker);
    var inboxes =
        new RelayMessageInboxes(
            new JdbcMessagingRepository(fixture.jdbc),
            new JdbcMessageInboxRepository(fixture.jdbc),
            new RelayMessagePublications(relay, new MessagePayloadCodec(65536)),
            relay,
            consumers,
            firings,
            fixture.work,
            drain,
            fixture.clock,
            new JdbcRelayRetirementRepository(fixture.jdbc, fixture.work));
    fixture.messageInboxes.add(inboxes);
    return new MessageInboxes() {
      @Override
      public void publish(
          MessageRecord record,
          MessageService.Route route,
          MessageService.Instance sender,
          MessageService.Instance recipient,
          MessagePublications.Reply reply) {
        inboxes.publish(record, route, sender, recipient, reply);
      }

      @Override
      public int poll() {
        return inboxes.poll();
      }

      @Override
      public int retireMissing(MessageDefinitions definitions) {
        return inboxes.retireMissing(definitions);
      }

      @Override
      public int cleanupRetired() {
        return inboxes.cleanupRetired();
      }
    };
  }
}
