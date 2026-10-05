package io.aeyer.plowshare.server.relay;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Project-owned pure routing and admission. No effects or automatic producers run here. Registered
 * receiver names select adapters; configuration cannot grant their execution authority.
 */
public interface RelayRouting {
  /** Validates every active package before returning; inactive folders are never inspected. */
  Project load(RelayProjectFiles.Access access);

  /**
   * Loads current activation, evaluates one matching subscription, pins selected handler sources
   * and admits all branches atomically. Existing admission retries return the pinned original
   * without rerunning edited code. The caller owns topic/subscriber registration and explicit gap
   * handling.
   */
  RelayDeliveries.Admission admit(
      RelayProjectFiles.Access access, Relay.SubscriptionKey subscription, Relay.Publication input);

  /** Commit boundary used to fence automatic and explicit admission after pure routing finishes. */
  @FunctionalInterface
  interface AdmissionCommit {
    RelayDeliveries.Admission admit(
        RelayDeliveries.AdmissionKey key, RelayDeliveries.Decision decision);
  }

  /** Routes outside a transaction, then invokes the caller's atomic ownership-checked commit. */
  RelayDeliveries.Admission admit(
      RelayProjectFiles.Access access,
      Relay.SubscriptionKey subscription,
      Relay.Publication input,
      AdmissionCommit commit);

  record Project(List<Package> relays, Map<String, Relay.Policy> policies) {
    public Project {
      relays = List.copyOf(relays);
      policies = Map.copyOf(policies);
      if (relays.size() > 32 || policies.size() > 256)
        throw new IllegalArgumentException("Relay project exceeds configuration limits");
      var names = new HashSet<String>();
      var kinds = new java.util.HashMap<String, RelayPayload.Kind>();
      for (var relay : relays) {
        if (!names.add(relay.name())) throw new IllegalArgumentException("duplicate active relay");
        for (var sub : relay.manifest().subscriptions()) {
          var previous = kinds.putIfAbsent(sub.topic(), sub.kind());
          if (previous != null && previous != sub.kind())
            throw new IllegalArgumentException("conflicting Relay topic payload families");
        }
      }
      policies.forEach((name, policy) -> RelayValues.name(name, "policy topic"));
    }

    public Relay.Policy policy(String topic) {
      return policies.getOrDefault(RelayValues.name(topic, "topic"), Relay.Policy.systemDefault());
    }
  }

  record Package(String name, RelayDeliveries.SourcePin routing, Manifest manifest) {
    public Package {
      name = folder(name);
      Objects.requireNonNull(routing, "routing");
      Objects.requireNonNull(manifest, "manifest");
      if (!routing.path().equals(name + "/routes.js"))
        throw new IllegalArgumentException("routing source must belong to its relay");
    }

    public Relay.SubscriptionKey key(long projectId, Subscription subscription) {
      if (!manifest.subscriptions().contains(subscription))
        throw new IllegalArgumentException("subscription is not declared by this relay");
      return new Relay.SubscriptionKey(
          new Relay.TopicKey(projectId, subscription.topic()),
          "relay." + name + "." + subscription.name());
    }
  }

  record Manifest(List<Subscription> subscriptions) {
    public Manifest {
      subscriptions = List.copyOf(subscriptions);
      if (subscriptions.size() > 32)
        throw new IllegalArgumentException("too many Relay subscriptions");
      var names = new HashSet<String>();
      for (var sub : subscriptions)
        if (!names.add(sub.name()))
          throw new IllegalArgumentException("duplicate subscription name");
    }
  }

  record Subscription(String name, String topic, RelayPayload.Kind kind, Relay.Start start) {
    public Subscription {
      name = folder(name);
      topic = RelayValues.name(topic, "topic");
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(start, "start");
      if (topic.equals("schedule.due") != (kind == RelayPayload.Kind.SCHEDULE_DUE))
        throw new IllegalArgumentException("schedule.due requires its registered payload family");
    }
  }

  /** A guest selects an adapter and optional local handler, passing the original typed event. */
  record Selection(String name, String receiver, String script, String publishTo, RelayWork work) {
    public Selection(String name, String receiver, String script) {
      this(name, receiver, script, null, null);
    }

    public Selection(String name, String receiver, String script, String publishTo) {
      this(name, receiver, script, publishTo, null);
    }

    public Selection {
      name = RelayValues.name(name, "branch");
      receiver = RelayValues.name(receiver, "receiver");
      RelayWork.require(receiver, work, script != null);
      if (publishTo != null) publishTo = RelayValues.name(publishTo, "publication target");
      if (receiver.equals("relay.publish") != (publishTo != null))
        throw new IllegalArgumentException(
            "only relay.publish requires a pinned publication target");
      if (script != null) {
        RelayProjectFiles.path("relay/scripts/" + script);
      }
    }
  }

  static String folder(String name) {
    if (name == null || name.length() > 64 || !name.matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)*"))
      throw new IllegalArgumentException(
          "relay and subscription names must be bounded folder names");
    return name;
  }
}
