package io.aeyer.plowshare.server.relay;

import java.util.List;

/** Built-in notification consumer. It creates human Inbox items, never model or command work. */
public interface RelayApprovalInbox {
  RelayApprovalInbox NONE =
      new RelayApprovalInbox() {
        public List<Relay.TopicKey> ready(String after, int limit) {
          return List.of();
        }

        public void consume(Relay.TopicKey topic, int limit) {}
      };

  /** Keyset discovery includes retained pending approvals whose publication has expired. */
  List<Relay.TopicKey> ready(String after, int limit);

  /** One bounded pass, with a distributed subscription lease and idempotent Inbox admission. */
  void consume(Relay.TopicKey topic, int limit);
}
