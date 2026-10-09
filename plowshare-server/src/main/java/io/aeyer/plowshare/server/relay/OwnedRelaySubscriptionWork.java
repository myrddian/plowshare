package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Subscription-owned admission and dispatch; no global work queue or shared dispatch loop. */
public final class OwnedRelaySubscriptionWork implements RelaySubscriptionWork {
  private static final Duration LEASE = Duration.ofMinutes(2);
  private final RelayProjectFiles files;
  private final ProjectMembers members;
  private final RelayRouting routing;
  private final Relay relay;
  private final RelayDispatch dispatch;
  private final RelayConsumerRepository consumers;

  public OwnedRelaySubscriptionWork(
      RelayProjectFiles files,
      ProjectMembers members,
      RelayRouting routing,
      Relay relay,
      RelayDispatch dispatch,
      RelayConsumerRepository consumers) {
    this.files = Objects.requireNonNull(files);
    this.members = Objects.requireNonNull(members);
    this.routing = Objects.requireNonNull(routing);
    this.relay = Objects.requireNonNull(relay);
    this.dispatch = Objects.requireNonNull(dispatch);
    this.consumers = Objects.requireNonNull(consumers);
  }

  @Override
  public List<Relay.SubscriptionKey> subscriptions(RelayProjectFiles.Access access) {
    var configuration = configuration(access);
    var keys = new ArrayList<Relay.SubscriptionKey>();
    for (var bundle : configuration.relays())
      for (var declared : bundle.manifest().subscriptions())
        keys.add(bundle.key(access.projectId(), declared));
    return List.copyOf(keys);
  }

  @Override
  public Result process(
      RelayProjectFiles.Access access,
      Relay.SubscriptionKey key,
      String worker,
      int admissionLimit,
      int dispatchLimit) {
    RelayValues.identity(worker, "consumer worker");
    if (admissionLimit < 0 || admissionLimit > 32 || dispatchLimit < 0 || dispatchLimit > 32)
      throw new IllegalArgumentException("subscription limits must be 0..32");
    if (!key.topic().scope().equals(new Relay.ProjectScope(access.projectId())))
      throw new CallerFault("Relay subscription belongs to another project");
    var configuration = configuration(access);
    boolean active = false;
    for (var bundle : configuration.relays())
      for (var declared : bundle.manifest().subscriptions())
        if (bundle.key(access.projectId(), declared).equals(key)) {
          var policy = configuration.policies().get(declared.topic());
          if (policy != null && members.mayManage(access.project(), access.account())) {
            // A declaration requests policy; it does not grant a processing identity authority
            // to apply it. Only a current manager pass can create/update this explicit policy.
            relay.configureTopic(key.topic(), declared.kind(), policy);
          } else {
            // Registration preserves the stored policy. Contributors may introduce an active
            // topic using the standard default, but cannot apply source-controlled retention.
            relay.registerTopic(key.topic(), declared.kind(), Relay.Policy.systemDefault());
          }
          relay.subscribe(key, declared.start());
          active = true;
        }
    if (!active) return new Result(0, 0, null);
    var acquired = consumers.acquire(key, worker, access.account(), LEASE);
    if (acquired.isEmpty()) return new Result(0, 0, null);
    var lease = acquired.get();
    int admitted = 0, dispatched = 0;
    Relay.Gap gap = null;
    try {
      // Drain retained work first: a pending-branch cap must not prevent freeing queue capacity.
      for (; dispatched < dispatchLimit; dispatched++) {
        if (Thread.currentThread().isInterrupted()) break;
        var ownership = lease;
        if (dispatch
            .next(access, key, worker, LEASE, () -> consumers.claim(ownership, LEASE))
            .isEmpty()) break;
        lease =
            consumers
                .acquire(key, worker, access.account(), LEASE)
                .orElseThrow(() -> new IllegalStateException("Relay consumer ownership changed"));
      }
      for (; admitted < admissionLimit; admitted++) {
        if (Thread.currentThread().isInterrupted()) break;
        var read = relay.read(key, 1);
        if (read.gap().isPresent()) {
          gap = read.gap().get();
          break;
        }
        if (read.publications().isEmpty()) break;
        var ownership = lease;
        routing.admit(
            access,
            key,
            read.publications().getFirst(),
            (input, decision) -> consumers.admit(ownership, input, decision));
      }
      // Newly admitted work uses any remaining dispatch budget. Branch claims check the live lease.
      for (; dispatched < dispatchLimit; dispatched++) {
        if (Thread.currentThread().isInterrupted()) break;
        lease =
            consumers
                .acquire(key, worker, access.account(), LEASE)
                .orElseThrow(() -> new IllegalStateException("Relay consumer ownership changed"));
        var ownership = lease;
        if (dispatch
            .next(access, key, worker, LEASE, () -> consumers.claim(ownership, LEASE))
            .isEmpty()) break;
      }
      return new Result(admitted, dispatched, gap);
    } finally {
      consumers.release(lease);
    }
  }

  private RelayRouting.Project configuration(RelayProjectFiles.Access access) {
    if (!members.mayWork(access.project(), access.account()))
      throw new CallerFault("Relay processing requires project contributor access");
    files.requireAccess(access);
    return routing.load(access);
  }
}
