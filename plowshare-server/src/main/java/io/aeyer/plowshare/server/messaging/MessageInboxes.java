package io.aeyer.plowshare.server.messaging;

import io.aeyer.plowshare.server.messaging.MessageService.Instance;
import io.aeyer.plowshare.server.messaging.MessageService.Route;

/**
 * Messaging-owned agent topics. Publication joins the send transaction; admission never runs a
 * model.
 */
public interface MessageInboxes {
  /** Resolved participants are the owning send's authorized snapshot, never caller assertions. */
  void publish(
      MessageRecord record,
      Route route,
      Instance sender,
      Instance recipient,
      MessagePublications.Reply reply);

  /** Bounded discovery/admission pass. A gap is surfaced without acknowledging or skipping it. */
  int poll();

  /** Retires only drained inboxes whose definition is absent from an authoritative inventory. */
  int retireMissing(MessageDefinitions definitions);

  /** Retires a bounded set of requested inboxes after broker retention and other groups drain. */
  int cleanupRetired();
}
