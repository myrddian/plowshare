package io.aeyer.plowshare.server.messaging;

import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.server.messaging.MessageService.Instance;
import io.aeyer.plowshare.server.messaging.MessageService.Route;

/**
 * Messaging-owned agent topics. Publication joins the send transaction; admission never runs a
 * model.
 */
public interface MessageInboxes {
  /** Resolved participants are the owning send's authorized snapshot, never caller assertions. */
  default MessageInboxRepository.Inbox publish(
      MessageRecord record,
      Route route,
      Instance sender,
      Instance recipient,
      MessagePublications.Reply reply) {
    return publish(record, route, sender, recipient, reply, null);
  }

  MessageInboxRepository.Inbox publish(
      MessageRecord record,
      Route route,
      Instance sender,
      Instance recipient,
      MessagePublications.Reply reply,
      RelayCausation causation);

  /** Signals already committed work using its authorized inbox snapshot; no repeat lookup. */
  void admitSoon(MessageInboxRepository.Inbox inbox);

  /** Bounded admission for one committed inbox; the worker coalesces duplicate signals. */
  int poll(MessageInboxRepository.Inbox inbox);

  /** Bounded discovery/admission pass. A gap is surfaced without acknowledging or skipping it. */
  int poll();

  /** Retires only drained inboxes whose definition is absent from an authoritative inventory. */
  int retireMissing(MessageDefinitions definitions);

  /** Retires a bounded set of requested inboxes after broker retention and other groups drain. */
  int cleanupRetired();
}
