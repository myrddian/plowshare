package io.aeyer.plowshare.server.messaging;

import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.relay.Relay;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Internal messaging publication boundary. The owning send path supplies resolved, authorized
 * participants and the retained reply route, never identities asserted by a model or SDK caller.
 * Existing harness binding, project access, routing policy, lifecycle and send-call identity checks
 * must complete first. This boundary reuses that snapshot rather than querying permission or
 * participant records again. It grants no independent send or inspection authority.
 *
 * <p>Implementations do not create topics, register consumers, start turns or change allowances.
 * The owning messaging transaction must atomically retain the message/body and append its
 * publication. A publisher call makes one attempt; after uncertainty, reconcile read-only rather
 * than replaying.
 */
public interface MessagePublications {
  /** Resolved instance identity; project ID is the already authorized Relay destination scope. */
  record Participant(String address, String account, long projectId, String conversation) {
    public Participant {
      address = MessagePayload.identifier(address, "ins_");
      account = RelayPort.identity(account);
      if (projectId < 1) throw new IllegalArgumentException("Messaging requires a project scope");
      conversation = MessagePayload.identifier(conversation, "cnv_");
    }
  }

  /**
   * Original retained request route. A reply reverses these participants, not fresh routing input.
   */
  record Reply(String message, String sender, String recipient) {
    public Reply {
      message = MessagePayload.identifier(message, "bdm_");
      sender = MessagePayload.identifier(sender, "ins_");
      recipient = MessagePayload.identifier(recipient, "ins_");
    }
  }

  /**
   * Trusted send snapshot from messaging's owning path. A null reply means a new request and
   * requires the existing cross-project policy check. Replies use the retained reverse route even
   * if fresh routing has been revoked; they do not gain permission for another new request.
   *
   * <p>This value is an internal contract, not an authorization token that an external caller can
   * construct. Topic naming and registration belong to the inbox owner, not this boundary.
   */
  record Binding(Relay.TopicKey inbox, Participant sender, Participant recipient, Reply reply) {
    public Binding {
      Objects.requireNonNull(inbox, "inbox");
      Objects.requireNonNull(sender, "sender");
      Objects.requireNonNull(recipient, "recipient");
      if (!sender.account().equals(recipient.account()))
        throw new IllegalArgumentException(
            "Messaging participants must belong to the same account");
      if (!inbox.scope().equals(new Relay.ProjectScope(recipient.projectId())))
        throw new IllegalArgumentException("Messaging inbox must be in the recipient's project");
      if (reply != null
          && (!reply.recipient().equals(sender.address())
              || !reply.sender().equals(recipient.address())))
        throw new IllegalArgumentException("Only the original recipient may reply to its sender");
    }
  }

  /**
   * Appends one validated payload as TEXT. The message ID is the event ID; caller-supplied
   * occurrence time and causation must come from the retained message intent and remain stable on
   * equal reuse. Existing retained equal-content deduplication and conflicting-reuse refusal are
   * Relay's rules.
   */
  Relay.Publication publish(
      Binding binding, MessagePayload payload, Instant occurredAt, RelayCausation causation);

  /**
   * Returns empty for a foreign publisher without interpreting its carrier or JSON. Own-publisher
   * corrupt content fails visibly. The consumer must advance past skipped foreign rows under its
   * existing lease, and verify the decoded recipient against its inbox before dispatch. Reading
   * here changes no cursor and hydrates no retained body.
   */
  Optional<MessagePayload> read(String account, Relay.Publication publication);

  /**
   * Read-only reconciliation against the same retained send intent after uncertain delivery. A
   * conflicting retained publication fails rather than becoming a successful receipt. Empty means
   * no retained receipt; it does not prove that the message was never published and must not
   * trigger an automatic resend.
   */
  Optional<Relay.Publication> retained(
      Binding binding, MessagePayload payload, Instant occurredAt, RelayCausation causation);
}
