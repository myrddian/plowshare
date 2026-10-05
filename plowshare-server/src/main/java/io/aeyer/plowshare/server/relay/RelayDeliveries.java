package io.aeyer.plowshare.server.relay;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Internal durable routing admission and delivery claims. Trusted adapters authorize subscribers,
 * receiver identities and source reads before calling this capability. Receiver names identify
 * registered adapters; they do not grant access or encode unrestricted destination commands.
 * Dispatch acceptance is distinct from receiver execution completion.
 */
public interface RelayDeliveries {
  /**
   * Pins a complete pure routing decision and the next publication, then advances availability in
   * the same transaction. Zero branches is a durable decision. Repeated identical admission returns
   * the original identities even after topic expiry; conflicting decisions fail. Admissions must
   * follow publication order and cannot bypass an unacknowledged expiry gap.
   */
  Admission admit(AdmissionKey key, Decision decision);

  /** Reads retained admission input and decisions, independently of topic-log retention. */
  Optional<Admission> admission(AdmissionKey key);

  /** Scoped delivery status, retained input, sources and receipt. */
  Optional<Delivery> delivery(DeliveryKey key);

  /**
   * Claims an available branch with a new fencing generation. Branches can run concurrently;
   * admission order is preserved, but effect completion order is not promised. Expired claims are
   * recovered by maintenance before they can be claimed again.
   */
  Optional<Delivery> claim(Relay.SubscriptionKey subscription, String worker, Duration lease);

  /** Records intent before dispatch. After this transition, expiry requires reconciliation. */
  Delivery prepareDispatch(Claim claim);

  /** Extends a live claim; a stale generation, owner or expired lease cannot mutate work. */
  Delivery renew(Claim claim, Duration lease);

  /** Records durable acceptance by the owning API, not the target's processing result. */
  Delivery accepted(Claim claim, Receipt receipt);

  /**
   * Records a definite refusal/failure. Callers must know the operation did not produce an effect.
   */
  Delivery failed(Claim claim, String code);

  /** Records uncertain dispatch. It is never automatically returned to available work. */
  Delivery uncertain(Claim claim, String code);

  /**
   * An authorized receiver reconciles through its owning API before supplying this resolution. The
   * broker accepts a receipt or definite failure, never a request to replay uncertain work.
   */
  Delivery reconcile(DeliveryKey key, Resolution resolution);

  enum State {
    READY,
    CLAIMED,
    DISPATCHING,
    ACCEPTED,
    FAILED,
    UNCERTAIN,
    ABANDONED,
    ABANDONED_UNCERTAIN
  }

  record AdmissionKey(Relay.SubscriptionKey subscription, long position) {
    public AdmissionKey {
      Objects.requireNonNull(subscription, "subscription");
      if (position < 1) throw new IllegalArgumentException("admission position must be positive");
    }
  }

  record DeliveryKey(Relay.SubscriptionKey subscription, UUID id) {
    public DeliveryKey {
      Objects.requireNonNull(subscription, "subscription");
      Objects.requireNonNull(id, "id");
    }
  }

  /**
   * Exact source relative to the Relay folder and its hash; the loader owns filesystem authority.
   */
  record SourcePin(String path, String source, String sha256) {
    public SourcePin {
      if (path == null
          || path.length() > 256
          || !path.matches(
              "[a-z][a-z0-9-]*/(?:routes\\.js|scripts/[a-zA-Z0-9_-]+(?:/[a-zA-Z0-9_-]+)*\\.js)"))
        throw new IllegalArgumentException("invalid relay-relative JavaScript path");
      requireSource(source);
      if (!hash(source).equals(sha256))
        throw new IllegalArgumentException("Relay source hash differs from pinned content");
    }

    public static SourcePin of(String path, String source) {
      // Bound the value before encoding/hashing it, including calls outside the file loader.
      requireSource(source);
      return new SourcePin(path, source, hash(source));
    }

    private static void requireSource(String source) {
      if (source == null
          || source.length() > 131072
          || source.indexOf('\0') >= 0
          || source.codePoints().anyMatch(code -> code >= 0xD800 && code <= 0xDFFF)
          || source.getBytes(StandardCharsets.UTF_8).length > 131072)
        throw new IllegalArgumentException(
            "Relay source must be valid UTF-8 content within 128 KiB");
    }

    private static String hash(String source) {
      try {
        return HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
      } catch (NoSuchAlgorithmException unavailable) {
        throw new IllegalStateException("SHA-256 is required by the Java runtime", unavailable);
      }
    }
  }

  /** A broker branch addresses a registered receiver; its handler source is entirely optional. */
  record Branch(String name, String receiver, SourcePin handler, String publishTo, RelayWork work) {
    public Branch(String name, String receiver, SourcePin handler) {
      this(name, receiver, handler, null, null);
    }

    public Branch(String name, String receiver, SourcePin handler, String publishTo) {
      this(name, receiver, handler, publishTo, null);
    }

    public Branch {
      name = RelayValues.name(name, "branch");
      receiver = RelayValues.name(receiver, "receiver");
      RelayWork.require(receiver, work, handler != null);
      if (publishTo != null) publishTo = RelayValues.name(publishTo, "publication target");
      if (receiver.equals("relay.publish") != (publishTo != null))
        throw new IllegalArgumentException(
            "only relay.publish requires a pinned publication target");
      if (handler != null && !handler.path().contains("/scripts/"))
        throw new IllegalArgumentException("handler source must be inside scripts");
    }
  }

  /** Complete routing result, bounded before admission; branch ordering is part of its identity. */
  record Decision(SourcePin routing, List<Branch> branches) {
    public Decision {
      Objects.requireNonNull(routing, "routing");
      if (!routing.path().endsWith("/routes.js"))
        throw new IllegalArgumentException("routing source must be routes.js");
      branches = List.copyOf(branches);
      if (branches.size() > 32)
        throw new IllegalArgumentException("at most 32 Relay branches may fan out");
      String folder = routing.path().substring(0, routing.path().indexOf('/'));
      var names = new HashSet<String>();
      int bytes = routing.source().getBytes(StandardCharsets.UTF_8).length;
      for (var branch : branches) {
        if (!names.add(branch.name()))
          throw new IllegalArgumentException("duplicate Relay branch name");
        if (branch.handler() != null) {
          if (!branch.handler().path().startsWith(folder + "/scripts/"))
            throw new IllegalArgumentException("handler source escaped its relay folder");
          bytes += branch.handler().source().getBytes(StandardCharsets.UTF_8).length;
        }
      }
      if (bytes > 1048576)
        throw new IllegalArgumentException("Relay admission sources exceed 1 MiB");
    }
  }

  record Admission(
      AdmissionKey key,
      Relay.Publication publication,
      Decision decision,
      Instant admittedAt,
      List<DeliveryKey> deliveries) {
    public Admission {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(publication, "publication");
      Objects.requireNonNull(decision, "decision");
      admittedAt = RelayValues.time(admittedAt);
      deliveries = List.copyOf(deliveries);
      if (!publication.topic().equals(key.subscription().topic())
          || publication.position() != key.position()
          || deliveries.size() != decision.branches().size()
          || deliveries.stream()
              .anyMatch(delivery -> !delivery.subscription().equals(key.subscription()))
          || new HashSet<>(deliveries).size() != deliveries.size())
        throw new IllegalArgumentException("admission input or branches differ from their scope");
    }
  }

  record Claim(DeliveryKey key, String worker, long fence) {
    public Claim {
      Objects.requireNonNull(key, "key");
      worker = RelayValues.identity(worker, "worker");
      if (fence < 1) throw new IllegalArgumentException("claim fence must be positive");
    }
  }

  /** A stable handle from the destination's owning API. The namespace identifies that API. */
  record Receipt(String namespace, String id) {
    public Receipt {
      namespace = RelayValues.name(namespace, "receipt namespace");
      id = RelayValues.identity(id, "receipt ID");
    }
  }

  sealed interface Resolution permits Accepted, Failed {}

  record Accepted(Receipt receipt) implements Resolution {
    public Accepted {
      Objects.requireNonNull(receipt, "receipt");
    }
  }

  record Failed(String code) implements Resolution {
    public Failed {
      code = RelayValues.name(code, "failure code");
    }
  }

  record Delivery(
      DeliveryKey key,
      AdmissionKey admission,
      Relay.Publication publication,
      SourcePin routing,
      Branch branch,
      State state,
      long fence,
      String worker,
      Instant leaseUntil,
      Instant admittedAt,
      Instant updatedAt,
      Receipt receipt,
      String failureCode) {
    public Delivery {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(admission, "admission");
      Objects.requireNonNull(publication, "publication");
      Objects.requireNonNull(routing, "routing");
      Objects.requireNonNull(branch, "branch");
      Objects.requireNonNull(state, "state");
      if (!key.subscription().equals(admission.subscription())
          || !publication.topic().equals(key.subscription().topic())
          || publication.position() != admission.position())
        throw new IllegalArgumentException("delivery differs from its admission scope");
      // Reuse the routing contract to validate the handler's folder at the persistence boundary.
      new Decision(routing, List.of(branch));
      if (fence < 0) throw new IllegalArgumentException("invalid delivery fence");
      if (worker != null) worker = RelayValues.identity(worker, "worker");
      boolean active = state == State.CLAIMED || state == State.DISPATCHING;
      if (active && (worker == null || fence < 1 || leaseUntil == null)
          || !active && leaseUntil != null)
        throw new IllegalArgumentException("invalid delivery lease state");
      if (leaseUntil != null) leaseUntil = RelayValues.time(leaseUntil);
      admittedAt = RelayValues.time(admittedAt);
      updatedAt = RelayValues.time(updatedAt);
      if ((state == State.ACCEPTED) != (receipt != null)
          || (state == State.FAILED
                  || state == State.UNCERTAIN
                  || state == State.ABANDONED
                  || state == State.ABANDONED_UNCERTAIN)
              != (failureCode != null))
        throw new IllegalArgumentException("invalid delivery outcome state");
      if (failureCode != null) failureCode = RelayValues.name(failureCode, "failure code");
    }

    /** Token for this active owner/generation; it becomes stale when the lease expires. */
    public Claim claim() {
      if (state != State.CLAIMED && state != State.DISPATCHING)
        throw new IllegalStateException("delivery has no live claim");
      return new Claim(key, worker, fence);
    }
  }

  /** Receiver leases are explicit and bounded to one second through five minutes. */
  static Duration requireLease(Duration lease) {
    Objects.requireNonNull(lease, "lease");
    if (lease.compareTo(Duration.ofSeconds(1)) < 0
        || lease.compareTo(Duration.ofMinutes(5)) > 0
        || lease.getNano() % 1000 != 0)
      throw new IllegalArgumentException(
          "Relay lease must be 1 second through 5 minutes at microsecond precision");
    return lease;
  }
}
