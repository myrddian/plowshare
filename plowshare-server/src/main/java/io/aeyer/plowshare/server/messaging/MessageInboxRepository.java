package io.aeyer.plowshare.server.messaging;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.relay.Relay;
import java.util.List;
import java.util.Objects;

/** Durable agent-to-inbox identity and bounded discovery; no topic is named per instance. */
public interface MessageInboxRepository {
  record Inbox(String account, String project, String agent, Relay.SubscriptionKey subscription) {
    public Inbox {
      account = RelayPort.identity(account);
      if (Home.of(project).isGlobal())
        throw new IllegalArgumentException("Inbox requires a project");
      agent = RelayPort.identity(agent);
      Objects.requireNonNull(subscription);
      if (!(subscription.topic().scope() instanceof Relay.ProjectScope))
        throw new IllegalArgumentException("Agent inboxes require project scope");
    }
  }

  /** Numeric project/topic keyset; lexical project strings cannot use the broker's B-tree. */
  record Cursor(long project, String topic) {
    public Cursor {
      if (project < 1) throw new IllegalArgumentException("Invalid inbox project cursor");
      topic = RelayPort.topic(topic);
    }

    public static Cursor after(Inbox inbox) {
      return new Cursor(
          inbox.subscription().topic().projectId(), inbox.subscription().topic().name());
    }
  }

  /**
   * Retains one deterministic binding; caller registers its broker topic in the same transaction.
   */
  Inbox retain(String account, String project, String agent, String topic);

  List<Inbox> ready(Cursor after, int limit);

  List<Inbox> drained(Cursor after, int limit);

  /** Rechecks the drained state under the publication binding lock; retains historical content. */
  boolean retire(Inbox inbox);

  /** Bounded retired bindings; callers recheck under lock before deleting broker metadata. */
  List<Inbox> retired(Cursor after, int limit);

  /** Locks a still-retired binding, serializing cleanup with a restored definition's send. */
  boolean lockRetired(Inbox inbox);

  /** Removes the locked retired binding only after its broker topic has disappeared. */
  void forgetRetired(Inbox inbox);
}
