# Slice 02 Bounded internal coordination and drain

Status: source implemented; verification and rollout are tracked in the
[validation record](../async-request-dispatch-validation.md).
Depends on slice 1's negotiated-mode, typed admission and terminal-state contract.
The design below records the intended ownership and acceptance boundaries;
`ConcurrentRequests` and `DispatchTelemetry` now implement this source slice.

## Purpose and source ownership

Implement the connection and resource ownership model inside Plowshare. Keep
coordination turns short and fair while blocking work runs elsewhere. Use the
Ciotola/Pasta design or selectively adapted source without an external dependency,
runtime or compatibility adapter.

| Existing seam | Scope of the change |
| --- | --- |
| [EventRequests](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/EventRequests.java) | Currently bounds retained frames/bytes, overlaps audited reads and runs other work as barriers; replace new-mode coordination behind the slice 1 dispatcher contract. |
| [EventReadCapacity](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/EventReadCapacity.java) | Currently acquires global read permits before creating workers; preserve admission-before-worker behavior and separate execution classes. |
| [EventChannelHandler](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/EventChannelHandler.java) | Delivery owns bounded reply queues and one writer; integrate connection admission and transport handoff completion. |
| [EventChannelConfig](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/EventChannelConfig.java) | Own validated configuration and constructor composition for dispatcher capacity and shutdown. |
| [PacketBudget](../../plowshare-protocol/src/main/java/io/aeyer/plowshare/protocol/transport/PacketBudget.java) | Shared memory reservation remains authoritative; define how logical request/reply reservations coexist with packet assembly. |
| [WorkerShutdown](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/lifecycle/WorkerShutdown.java) | Local work already provides bounded periodic-worker stop; integrate explicit dispatcher drain ordering rather than create competing shutdown paths. |

Add an internal coordination package in the server, selected by composition.
Proposed contracts are `RequestDispatcher`, bounded mailbox scheduling and typed
execution admission. Do not expose actors to SDKs, repositories or domain code.
Keep old-mode dispatch behavior until the negotiated new path is selected.

## Connection and request state

The connection owner tracks its generation, selected mode, active IDs, queued
count/bytes, outstanding replies and drain completion. Store cross-thread indexes
in `ConcurrentMap`/concurrent queues. Actor-owned temporary state need not use
concurrent collections merely for appearance. A narrow atomic protocol covers
admission, registry insertion, capacity counters and closure together.

Recommended request progression is
`QUEUED -> RUNNING -> COMPLETING -> REPLY_HELD -> RELEASED`.
`DISCARDED` is available only before execution. Connection closure
can detach output ownership while running durable work finishes. Every transition
must identify which owner releases count, bytes, worker permits and delivery
capacity; a single catch/finally shared by all stages is insufficient.

- Validate/reserve atomically before queueing or creating a worker. Overload
  rejects new work without consuming the capacity reserved for admitted work.
- Duplicate active IDs protocol-close; never overwrite an entry or answer with
  that ID. Remove entries by execution identity, not an unconditional key remove.
- Retain the ID until terminal reply transport handoff or connection termination.
  SDKs never reuse IDs even after retirement during that generation.
- Worker completion is a typed message to the owning Role. It cannot mutate
  connection/resource state from an arbitrary completion thread.
- Reserve a bounded completion path during admission. Completion queue saturation
  cannot drop an owed result or run callbacks inline on the socket reader.
- Detach callbacks/future actions under the atomic protocol and invoke them
  outside it. A callback that reenters admission or closes the dispatcher must
  not deadlock or corrupt the transition it follows.

## Resource ownership and fair scheduling

The process-wide resource registry coordinates the same resource across
connections to this server. Slice 2 implements the mechanism using fake typed
keys; slice 4 supplies operation-specific policies. PostgreSQL remains responsible
for correctness across processes and for transactions/version fences.

Bound total resource keys, per-key queue count/bytes, total queued/running work,
ready roles and execution slots. Idle-key reclamation is atomic with acquisition:
remove a key only when no queued, running or completion references remain. A
late completion cannot revive a retired generation of a key. Reject additional
keys/work explicitly when capacity is exhausted; do not allocate one unbounded
queue for every request's unique resource.

One turn owns Role state at a time. A finite work quantum returns a hot Role to
the ready queue behind other eligible Roles. Wake ready work on admission or
completion rather than scan all Roles on a timer. Hash collisions in scheduler
placement must not serialize blocking IO for unrelated resource identities.
Keep a conservative connection barrier policy for unaudited operations, without
turning every key into a global project queue.

Resolve multi-key conflicts through one documented atomic admission or canonical
key ordering. Never wait while partially holding a conflicting key set. Detect
recursive dependencies rather than deadlock. Worker admission, conflict ownership
and observer capacity are separate reservations; observers cannot hold the lane
required by their unblocking mutation.

## Capacity and shutdown boundaries

Configure finite-work execution, observer waits, callback/completion execution,
mailboxes and reply storage separately. Size database-heavy work against pool
capacity and reserve lifecycle/recovery progress. Do not create one virtual
thread per queued request before execution admission. JDBC transactions live
inside a worker's service/repository call, never across Role turns.

Shutdown is owned, idempotent and reentrant:

1. Close admission and report the same drain completion to concurrent callers.
2. Discard never-started requests and release their reservations exactly once.
3. Detach observers and close connection-local subscription admission.
4. Allow running operations and deliverable replies to drain until a configured
   absolute deadline; distinguish settled execution from completed output.
5. Close transport/complete remaining caller ownership exceptionally and finish
   owned executors before Spring tears down their database/provider dependencies.

A deadline does not prove that an admitted mutation was undone. Record unfinished
work honestly and retain durable leases/outboxes for restart recovery. Never
resubmit operations from a shutdown catch, reconnect or generic actor restart.
Avoid self-join when close is called from an owned worker/continuation.

## Verification and measurable acceptance

Extend [EventRequestsTest](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/ws/EventRequestsTest.java)
and [EventReadDispatchTest](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/ws/EventReadDispatchTest.java)
or move their mechanisms into new focused coordinator tests. Use latches,
controllable workers and fake delivery acknowledgements, not timing-only sleeps.

Required default tests cover count/byte rejection before worker creation,
duplicate-ID close, enqueue/close and reply/close races, recursive callbacks,
completion saturation, hot-role fairness, colliding scheduler hashes, multi-key
conflicts, key reclamation versus late completion, and repeated shutdown.
Hold workers/replies open to prove all counters remain charged until their
specified release point. After drain, owned slots/bytes/keys return to baseline.
No PostgreSQL is required for these coordination invariants.

Handoff to slice 3: a typed dispatcher fake and real internal implementation with
bounded ownership and observable drain, but no broad handler parallelism.
Handoff to slice 4: resource policy admission and observer slot primitives.
Out of scope: handler classification, domain transactions, SDK APIs, cache
replacement, deployment and throughput claims from synthetic fixtures.
