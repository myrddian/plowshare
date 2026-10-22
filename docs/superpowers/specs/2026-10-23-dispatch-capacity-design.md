# Dispatch capacity and honest backpressure

Date: 2026-10-23
Status: design, awaiting approval

## Why this exists

The desktop client reports bursts of identical faults:

```
ConnectionFault [Error]: request deadline expired; submitted work is not replayed
  code: 'RESPONSE_LOST'
```

`RESPONSE_LOST` is produced in `sdk/typescript/src/binding/connection.ts` only when
`asker.submitted === true`: the frame was fully written to a live socket and the
server never answered within the client's 30s deadline (`plowshare-desktop/src/client.ts`).

This was confirmed as a server-side stall, not a client defect. During the fault
the desktop held six ESTABLISHED sockets to the server and the server's HTTP layer
answered in 3ms. Frames arrive, the server holds them, and it says nothing.

The diagnosis is not "capacity is too low". It is that the dispatch contract
cannot express congestion at all, so congestion is delivered as silence.

## The three defects

### 1. Admission and execution are decoupled, with no feedback path

Two independent dimensions, four numbers, in two different subsystems:

|                                     | Per connection                | Global                      |
| ----------------------------------- | ----------------------------- | --------------------------- |
| **Queue depth** (may be outstanding)| `ConcurrentDispatch.MAX_PENDING = 64` | `ConcurrentRequests.GLOBAL_PENDING = 1024` |
| **Concurrency** (actually executing)| `requestPerConnection = 2`    | `requestGlobal = 8`         |

A request is admitted freely up to the depth limit, then queues behind the
concurrency limit. Queue time counts against the client's deadline, because the
client's timer starts at submission rather than at execution. Nothing tells the
client this happened.

The server therefore has exactly two behaviours under load, and neither is
backpressure:

- **Under 64 per connection:** accept silently, queue behind a global execution
  limit of 8, never reply. The client's deadline expires. This is the reported bug.
- **Over 64 per connection:** `EventChannelHandler` catches the admission refusal
  and calls `delivery.stop()` then `delivery.disconnect("invalid request admission")`
  — tearing down the **whole connection** and stranding every in-flight request on
  it as `RESPONSE_LOST`. Ordinary congestion is treated as a protocol violation.

There is no per-request refusal, no "retry after", and no queue-depth signal.

### 2. The negotiation carries only depth, and the server refuses to use it

Depth-as-a-hint is **already implemented on the wire and in most clients**. Four of
five SDKs accept any advertised `maxPending` in `[1, 64]` and adopt it:

| SDK | Behaviour |
| --- | --- |
| TypeScript (`connection.ts:578`) | accepts `[1,64]`, `maximum = body.maxPending` |
| Go (`client.go:263`) | accepts `[1,64]`, `c.maximum = selected.MaxPending` |
| .NET (`Client.cs:152`) | accepts `[1,64]` |
| Python (`client.py:102`) | accepts `1 <= maximum <= 64` |
| Java SDK (`Plowshare.java:395`) | **rejects anything but 64** (`asInt() != MAX_PENDING`) |

The blocker is Java-side only. `ConcurrentDispatch.Selected` is documented as
*"Effective per-connection bounds confirmed before any domain request is
submitted"*, but its constructor is:

```java
if (!MODE.equals(mode) || maxPending != MAX_PENDING || maxIdBytes != MAX_ID_BYTES)
  throw new IllegalArgumentException("Invalid dispatch selection");
```

So the server cannot *construct* a value it is already entitled to send and that
four clients would already honour. Relaxing that record and the Java SDK's matching
check is a small change, not a six-SDK migration.

Two real gaps remain:

- The one number on the wire is a **queue depth** — permission to pile up, which
  bounds nothing the client cares about.
- The numbers that decide whether a request runs before the deadline — the
  concurrency pair — are **never transmitted and not nameable on the wire**.

### 2a. Lowering the advertised depth converts silence into hard local failure

This is the pivotal constraint on any depth work, and it is not hypothetical.
`connection.ts:396` fails **locally and synchronously** the moment the window is
full, before anything reaches the socket:

```ts
if (requestOptions.signal?.aborted || admitted >= maximum)
  throw new ConnectionFault(
    'NOT_SUBMITTED',
    'request cancelled or capacity exhausted before submission',
  );
```

The client never queues past the negotiated depth and never waits for a slot.
Therefore:

- Advertising 4–8 instead of 64 does not produce polite backpressure. It converts
  today's 30-second silence into an **immediate flood of local `NOT_SUBMITTED`
  rejections** at the desktop's six polling connections — with per-connection
  concurrency of 2 and barrier serialisation behind it.
- The server's new "out of slots" refusal is consequently **largely unreachable**
  for the depth case. The client rejects first.

A second mismatch sits underneath it: `ConnectionFault` is a locally-raised
transport fault with a fixed three-value `code` union. A server *reply* cannot set
it. So a congestion refusal either resolves the caller's promise as an `Outcome`
failure — callers see a domain-style error and `delivery` is never consulted — or it
must be translated into a thrown `ConnectionFault`, which is new plumbing in every
SDK. The earlier claim in this document that the distinction "already exists in
`ConnectionFault.delivery`" is only half true: the state exists, but nothing can
reach it from a frame.

### 3. `requestGlobal` is one number standing in for fifteen pools

`requestGlobal = 8` is not a concurrency policy. It is an unwritten proxy for the
main Hikari pool, whose size is Hikari's **default of 10** because
`application.yml` sets no `maximum-pool-size`. The invariant
`requestGlobal(8) < mainPool(10)` is real, load-bearing, and recorded nowhere —
the 8 is in `EventChannelHandler`, the 10 is an absent line in config, and no
comment connects them.

Worse, there is no single bottleneck for it to proxy. Besides the two Hikari pools
(main, and the explicitly-bounded `projection-versions` at 4), fifteen files carry
their own bounded executor or semaphore: `LlmPool` lanes, `RelayWorkers`,
`RelayInternalWorkers`, `JobStore`, `ScriptHooks`, `FileContents`, `PasswordHasher`,
`LoginAttempts`, `EventReadCapacity`, `BoundedSnapshotCache`, and the search and
embedding providers behind them.

One number cannot be right for fifteen independent resources. For a `board.topics`
read, 8 is absurdly conservative. For a transactional path with an embedding call
inside it, 8 is too generous. No value fixes it, which is why it is deleted here
rather than tuned.

Configuration compounds this: `useReadConcurrency(perConnection, global)` sets the
request dispatcher's concurrency from `plowshare.transport.event-read-concurrency`,
a property named for a different subsystem. The governing throughput number for
all domain requests is a side effect of tuning event reads.

## The model this replaces it with

Capacity is owned by whoever actually has it. The transport stops guessing.

**The handshake carries depth.** Per-connection slots are genuinely a transport
property, static and honestly knowable at connect time. The server advertises its
own value; the client's `MAX_PENDING` becomes a hint it may offer, not a constant
it asserts.

**Replies carry pressure.** Resource saturation is dynamic, per-operation, and
owned by whichever pool the work lands in. It is unknown at connect time and
changes per request, so it belongs in a reply — an explicit refusal naming the
resource — not in the handshake.

The transport's honest answer to "how much can you do" is therefore *"I will hold
N for you, and I will tell you per request when something downstream is full."*
Not a throughput promise it is in no position to make.

This also repairs the delivery semantics. A refusal is `NOT_SUBMITTED` — never
started, safe for the client to retry — rather than a 30-second silence that the
client can only report as `RESPONSE_LOST` with unknown delivery. That distinction
already exists in `ConnectionFault.delivery`; today's design just cannot reach it.

### Precedent already in the codebase

`LlmPool` already solved this, and its shape is the one to follow:

```java
public record Occupancy(int queued, int running, int capacity) {}
public Occupancy occupancy(Lane lane)
```

Three numbers, per lane, with the javadoc arguing exactly why depth alone is
insufficient: *"depth alone cannot tell a saturated pool from an idle one... an
eight-slot lane with all eight slots occupied reports a queue depth of zero — the
same zero as an idle eight-slot lane."* The transport layer never learned this.

`ProjectionCacheConfiguration` is the precedent for pool sizing: a separate,
explicitly bounded pool with a documented reason, isolated from a different
workload's contention.

## Goals

- A request is never admitted and then met with silence. Every admitted request
  gets a reply, or an explicit refusal, within a bounded time.
- Queue depth and execution concurrency are distinct, separately named, and never
  conflated in configuration or on the wire.
- The server advertises the depth it will hold; the client treats its own
  `MAX_PENDING` as a hint.
- Congestion refusals are per-request and carry `NOT_SUBMITTED`, never a
  connection teardown.
- Each bounded resource answers for its own capacity. The transport holds no
  global concurrency number.
- Pool and dispatch pressure are observable on a running server.

## Non-goals

- Raising `event-read-global-concurrency` as a fix. It moves the cliff without the
  client ever learning, and leaves the contract defect in place.
- A global throughput promise in the handshake. There are fifteen pools; no single
  number is honest.
- Hand-managing database concurrency in application code. Hikari already blocks
  correctly when contended and is the only component that knows the truth; a second
  model of it would be a worse copy.
- Changing durable lifecycle, idempotency, or authorization contracts. This is
  transport scheduling only.
- Reclassifying operations. That is slice 04 audit work, tracked separately below.

## Anti-goals

Things that would make this a failure even if it technically works:

- Replacing silence with a connection kill. A refusal that drops in-flight work is
  not an improvement.
- A refusal the client cannot distinguish from a genuine failure, or that leaves
  delivery ambiguous when the server knows the work never started.
- Moving the stall one layer down. Removing the transport cap without addressing
  Hikari's 30s `connectionTimeout` reproduces the same 30-second symptom as a DB
  failure, and `ConcurrentRequests.finished()` turns a failure into a closed
  connection.
- A second policy map competing with slice 01's contract, or capacity policy drifting
  away from the handler that owns the operation.

## Constraints

- Wire changes touch `plowshare-protocol` and all six SDKs plus their clients.
  Shared operation contracts and affected clients update together.
- `concurrent-v1` is deployed. Either the negotiation stays compatible or the mode
  is versioned; a client and server of different vintages must not silently
  disagree about depth.
- Schema changes, if any, are new Flyway migrations. No shipped migration is edited.
- Google Java Style via the Gradle formatter. `./gradlew format` and `formatCheck`
  after Java edits; `check` before handing off.
- Database tests stay behind `@Tag("full-db")` and `-PfullDb`, and are only run if
  this change requires real PostgreSQL behaviour.
- Deployment values stay configuration. No hard-coded hosts, ports, or pool sizes
  in runtime code.

## Decided design

Three pieces. They are independent in implementation but ordered by coupling.

### C. Delete the global cap; let each pool answer for itself

- Remove `requestGlobal` as a bound on request execution. If a global bound remains
  at all, it is on concurrent **connections**, where 0 means unlimited.
- Keep per-connection concurrency as a real, separately named property. Stop
  deriving request bounds from `plowshare.transport.event-read-concurrency`; event
  reads and request dispatch get their own keys.
- Size the main Hikari pool explicitly, with a comment giving the reason, matching
  the treatment `projection-versions` already receives. The number comes from
  PostgreSQL `max_connections` and measured holding time.
- Lower Hikari's `connectionTimeout` well below the client deadline, so pool
  starvation arrives as a fast refusal rather than a 30-second silence that mimics
  the current bug.
- Expose pool and dispatch pressure. `HikariPoolMXBean` already reports the
  `LlmPool.Occupancy` triple — `getActiveConnections()`, `getThreadsAwaitingConnection()`,
  `getMaximumPoolSize()` — and nothing in main code reads it. `DispatchTelemetry` is
  package-private with no endpoint. Note that `application.yml` declares
  `management.endpoints.web.exposure.include: health,info,metrics` while
  `build.gradle.kts` has **no actuator dependency**, so that configuration is dead
  and the health probe 404s.
- Policy on holding time rather than connection count: no provider, model, or other
  network call inside a database transaction. `Archive.java` already defends this
  case and explains why; it becomes a stated rule.

### A. Classify operations

`DispatchPolicy.decode` matches ~53 explicitly-listed types and then falls through
to `barrier()`. **223 of 270 operation types are exclusive barriers.**

A barrier carries an empty resource list — it makes no claim about what it touches.
It is the "not yet classified" bucket, and because nothing is known it blocks
everything rather than anything specific. It may only start when
`connection.running + connection.observing == 0`, and while running sets
`connection.barrier = true`, which is the first condition every other request on
that connection fails. So `requestPerConnection = 2` is fiction for those
operations: they execute one at a time with a full drain between each.

Barrier scope is the **connection, not the data**. Two clients on two connections
hitting the same rows are not serialized against each other by barriers at all.
Cross-process correctness is PostgreSQL's, per slice 04: *"PostgreSQL
transactions/locks/version fences remain the cross-process authority."* A barrier
reproduces pre-concurrency per-connection ordering and nothing more.

Each barrier also occupies one of the global slots while running. With the cap at
8, **eight stop-the-world reads from eight connections saturate the whole server** —
and the desktop alone holds six connections, polling `board.topics` and
`swarm.status`, both unclassified, both plain reads that conflict with nothing.

Scope here is the desktop's read-only hot path first — `board.topics`,
`swarm.status`, `admin.status` — as `FINITE`. Multi-resource operations are
`RESOURCE` with the complete typed key set, never `BARRIER`: barrier is both too
strong (blocks unrelated work on the connection) and too weak (does not serialize
the other connection touching the same rows).

This is slice 04 audit work and follows its process. It is listed here because it
is the dominant utilisation limit and because of its coupling to C: freeing reads
to overlap increases concurrent DB use, so the pool must be sized first or the
stall relocates into Hikari.

### B. The dispatch contract

- **Relax the Java-side depth rigidity.** `ConcurrentDispatch.Selected` stops
  validating equality against a constant and becomes the server's statement of its
  own value; the Java SDK's matching `!= MAX_PENDING` check relaxes to the `[1,64]`
  range the other four SDKs already accept. No wire change, no migration.
- **Stop the teardown.** Replace the `catch (RuntimeException invalid)` →
  `delivery.stop()` + `disconnect` path in `EventChannelHandler` with a correlated
  refusal reply. Teardown is reserved for genuine protocol violations. This is the
  piece that fixes silence-or-kill and is worth doing on its own merits.
- **When a downstream resource is saturated, the reply names the resource** and is
  retryable, with delivery semantics stated.
- **Do not lower the advertised depth until the client's behaviour at the bound is
  decided** (§2a). Lowering it alone converts silence into immediate local
  `NOT_SUBMITTED` rejections and makes the server's refusal unreachable. Depth
  advertisement is cheap; depth *reduction* is the part that needs the decision.

Mechanism constraint for the refusal path: admission failure today throws out of
`Connection.offer` inside `concurrentFrame`, for a frame that never entered the
dispatcher. But the dispatcher owns reply handoff (`Connection.handingOff`, which
throws `IllegalStateException` on "a reply handoff is already settling") and reply
bytes are reserved through `PacketBudget` at admission. A refusal for a
never-admitted request therefore has no reservation and no handoff slot. Either
refusals take an explicit, documented exemption from budget and handoff ownership —
without risking the one-handoff invariant throwing precisely when refusals are
frequent — or admission partially succeeds to reserve reply bytes, which changes
what "not admitted" means and interacts with `GLOBAL_PENDING`/`CONNECTION_BYTES`
accounting. The builder chooses, but must not leave the invariant reachable under
load.

## Sequencing

**C, then A, then B.** C makes the real resource explicit, sized, and observable.
A then takes advantage of it — and must not precede it, or classification moves the
stall from the dispatcher into a 10-connection pool with a 30s timeout, turning a
silent stall into a dropped connection. B is the largest and the only one touching
the wire and all six SDKs; it is also the only one that fixes silence rather than
merely making it rarer.

Classification alone does not close the reported bug. It widens the pipe and leaves
the cliff unlit.

## Left to the builder

- Per-connection depth: 4 or 8, and whether it varies by client kind.
- Whether `concurrent-v1` is extended compatibly or versioned, and the
  mixed-vintage handshake behaviour.
- The wire shape of a refusal: outcome code, whether it carries a retry-after, and
  how it maps onto each SDK's error type.
- Whether a global concurrent-connection bound is introduced at all, and if so its
  default (0 = unlimited).
- The main pool size and `connectionTimeout`, derived from PostgreSQL
  `max_connections` and measured holding time.
- Whether observability arrives as the actuator dependency the dead config already
  assumes, or as a purpose-built diagnostic surface.
- Whether the client's deadline should start at execution rather than submission,
  or whether honest refusals make that unnecessary.

## Calls made while writing this

- **Trust Hikari for admission rather than hand-managing DB concurrency.** It
  already blocks correctly and is the only component that knows. The work is to
  size it, read what it reports, and make it refuse quickly — not to build a
  control loop.
- **Repo diversity does not reduce pool contention**, so DB concurrency is not moot:
  all 77 `Jdbc*` repositories share the one autoconfigured pool, and ten concurrent
  statements against ten tables exhaust ten connections exactly as fast as ten
  against one. Pressure is `holding time × arrival rate`; repo count is not in it.
  The practical mitigation is that most of the 255 call sites are single-statement
  autocommit holding for milliseconds, which concentrates the risk in the 23
  transactional files.
- **Observability is folded into C** rather than standing alone, because C cannot be
  sized responsibly without it.
- **A extends the existing `DispatchPolicy` map for its three types** rather than
  moving classification onto `FrameHandler`. Slice 01 names `OperationExecutionPolicy`
  as the intended seam and handler-owned policy is the right end state, but that is a
  270-operation interface change across every handler in `server/ws/` — two orders of
  magnitude larger than the fix, and slice 04/01 structural work. Recorded here as a
  known tension with this document's own anti-goal about competing policy maps.
- **Ordered/legacy-mode connections are out of scope.** They route inline through
  `routeFrame` with `EventReadCapacity`, whose `tryAcquire(100ms)` loop waits
  unboundedly while the socket is open — the same silence by another route, and C
  splits the very property that drives it. Stated explicitly because the reported
  cause is still reachable there; closing it needs a refusal with no handshake to
  carry depth, so it is its own piece of work.
- **"No network call inside a database transaction" lands as a stated rule now**,
  with enforcement across the 23 transactional files as a follow-up. Flagged because
  holding time is the input to C's pool sizing, so until it is enforced the sizing
  rests on an assumption nothing defends.

## Decided by the owner

- **The server reports its queue depth to the client.** Four of five SDKs already
  adopt an advertised `maxPending` in `[1,64]`; the Java SDK and
  `ConcurrentDispatch.Selected` relax to match.
- **Concurrent connections are unlimited, expressed as 0.** No global cap on
  request execution; fan-out is admitted and the pools refuse.
- **Main Hikari pool goes to 20.** Note the stated rationale ("just under half the
  tables") does not hold — there are 189 tables and all of them are reached through
  the one pool, where a connection is held per statement in flight, not per table.
  20 stands on its own basis: 2× the current implicit default, and 20 main plus 4
  `projection-versions` = 24 against PostgreSQL's default `max_connections` of 100,
  leaving headroom for Flyway, psql and other sessions. Nothing in the repository
  pins `max_connections`, so confirm it on the deployment before committing.
- **At the depth bound the client blocks itself** rather than hard-failing at the
  API boundary. See the prerequisites below.

### Client blocking is backpressure, not a failure mode

**The async request does not error out. It blocks.** Waiting for a slot is flow
control: the caller's promise simply does not settle until there is room. No fault,
no refusal, no retry for the caller to write. The poll loops then throttle
themselves to the rate the server can actually serve, which is the mechanism doing
the work here — the GUI stops generating load faster than the server absorbs it,
without any call site learning about capacity.

This makes one change a hard requirement rather than an option:

- **The request deadline must start at launch, not at `ask()`.** Today
  `connection.ts` schedules the timer and only then calls `launch()` (directly for
  control and ordered mode, otherwise via `ready().then(launch)`). So the 30s budget
  is already running while a request sits unlaunched. Left as is, blocking would
  convert flow control back into a timeout. The deadline governs the *request* once
  submitted; it does not govern the wait for a slot.

Consequences worth stating:

- The depth-bound refusal path on the server becomes unnecessary for well-behaved
  clients, since a blocking client never offers past the window. The refusal remains
  for protocol-violating or non-blocking clients, and the teardown-on-congestion
  path is still wrong and still removed.
- `connection.ts:396`'s local `admitted >= maximum` hard-fail is replaced by a wait.
  `ConnectionFault('NOT_SUBMITTED', 'request cancelled or capacity exhausted before
  submission')` stops being reachable through congestion, and should remain only for
  genuine cancellation and closure.
- Call sites that already guard against overlap keep working unchanged — `board.ts`
  dedupes per key via `flights`, `sync.ts` returns the existing `flight`, and
  `client.ts`'s job poll has a `busy` flag. `activity.ts` fires
  `background(this.refresh())` every 15s with no such guard, so each call site should
  be checked for waiter accumulation under a sustained block, even though blocking
  itself is bounded by the window.

### `NOT_SUBMITTED` is the backpressure signal

The model is NiFi's: a processor does not run when the downstream queue is full.
Two layers, and they reinforce each other:

- **The server always answers.** When the per-connection queue is full it replies
  `NOT_SUBMITTED` — never silence, never teardown. Any client, including foreign or
  non-blocking ones, gets an honest, unambiguous "this was never started".
- **Our SDK is smart about it.** On a capacity `NOT_SUBMITTED` it blocks until it can
  submit, rather than propagating an error to the caller. The local
  `admitted >= maximum` check becomes a wait for the same reason.

So `NOT_SUBMITTED` is not an error path to be handled at each call site; it is the
flow-control signal the SDK consumes. No call site learns about capacity, and the
server is never in the position of having accepted work it will not speak about.

This resolves the refusal-shape question: the depth refusal is an `Outcome` the SDK
interprets, not a new thrown-error path across six languages.

## Scope calls made to proceed

The owner asked to plan and implement; these were settled narrowly rather than
blocking on them, and each is reversible.

- **C covers Hikari only**, not all fifteen bounded resources. Notably,
  `EventReadCapacity`'s `tryAcquire(100ms)` loop — previously filed as an unbounded
  wait defect — is *consistent* with the blocking philosophy: it waits rather than
  erroring. It is left alone. The remaining pools are a separate audit.
- **No new server-side time-to-reply budget.** `ConcurrentRequests` gains no
  per-request deadline or cancellation machinery. Blocking as flow control plus
  classification of the hot path addresses the reported fault; a server-side budget
  is its own concern and would entangle slice 03's completion ownership.
- **The `barrier()` fall-through default stays.** Three hot-path reads are
  classified `FINITE`. Accepted consequence, stated rather than discovered later:
  `requestPerConnection` remains fiction for roughly 220 unclassified operations
  until slice 04's audit proceeds.
- **The shipped `plowshare.transport.request-depth` is 8.** Depth is the admission
  window (`connection.depth`, checked in `ConcurrentRequests.Connection.offer`): how
  many requests may be queued on a connection. Concurrency (`request-per-connection`,
  checked as `connection.running < connectionLimit`) is how many execute. At the
  defaults, 8 are admitted, 2 execute and 6 wait. A shallow queue refuses sooner and
  more visibly instead of letting 64 requests pile up behind two executing ones. An
  earlier version of this scope call implied that depth affects connection-pool
  pressure; that is retracted. Pool pressure follows the executing count, so depth
  has no database implication. The known consequence: four of the six SDKs (Java, Go,
  Python, .NET) hard-fail with `NOT_SUBMITTED` at the depth bound instead of
  blocking, and all four adopt the advertised depth, so their external consumers are
  refused at the 9th concurrent request where they were previously refused at the
  65th. Only the TypeScript SDK blocks, and every first-party client uses it, so none
  is affected. Depth is configurable, so a deployment serving the other four SDKs can
  raise it. Teaching them to block is separate work.
- **`request-per-connection = 2` is inherited, not chosen.** At the base commit the
  handler had `requestPerConnection = 2` fed from `event-read-concurrency:2`, the
  event-read property request dispatch piggybacked on. The split kept 2 to preserve
  behaviour. It is now more load-bearing: with `request-global` at 0 it is the only
  transport execution bound. Raising it is what would pressure the pool, gated on
  expressing Hikari starvation as `CAPACITY_EXHAUSTED` capacity, which this branch
  does not do.
- **`max_connections` must be confirmed on the deployment** before the pool is fixed
  at 20. If it cannot be read, 20 still fits a default of 100 with wide margin.
2. **Scope of "each resource answers for itself"** — all fifteen bounded resources,
   or Hikari only. `EventReadCapacity` waits unboundedly; `LlmPool` already times out
   ("saturation is a timeout, not a wait"); the rest are uninspected. All fifteen
   makes C a long pole rather than a prerequisite. Hikari only leaves the
   "moving the stall one layer down" anti-goal live for every non-DB path.
3. **Is there a server-side time-to-reply budget?** `ConcurrentRequests` has no
   per-request deadline, no queue-time timeout and no cancellation for a request that
   cannot start — a barrier queued behind a long barrier waits arbitrarily, and the
   client's 30s timer is the only clock. A real budget means deadline, expiry refusal
   and cancellation interacting with slice 03's completion and `executionFinished`
   ownership; that is absent from the three-part breakdown.
4. **Does the `barrier()` fall-through default change?** If it stays, `requestPerConnection`
   remains fiction for ~220 operations even after all three pieces land, and that should
   be an accepted outcome rather than a later discovery. If it flips, that is a
   correctness decision about those operations, not an audit entry.
5. **Verification gate and deployment facts** — whether a capacity/load test is the
   gate (slice 08 mentions capacity evidence) and its pass condition; and whether any
   configuration or telemetry can be obtained from the reporting deployment before C's
   numbers are chosen. The `requestGlobal(8) < mainPool(10)` invariant this argument
   rests on may not hold there at all. Asserting that Hikari's `connectionTimeout`
   yields a fast refusal under starvation needs real PostgreSQL, so it is also the
   stated database-specific reason for the one expected `-PfullDb` case.

## How the scope calls compose

Two statements the sections above never make together.

**The two congestion waits are bounded differently.** A request waiting on the
locally known depth is unbounded flow control: no deadline is armed until it is
transmitted. A request waiting after a *server* refusal is charged to the deadline
armed at its first transmit, because arming is once-only and a resubmission does not
renew it. After enough refusals the caller does see a failure. The asymmetry is
deliberate; it was not written down.

**The goal "every admitted request gets a reply, or an explicit refusal, within a
bounded time" is not delivered by this branch.** There is no server-side
time-to-reply budget (descoped above), `requestPerConnection` is still 2, and the
`barrier()` fall-through default still covers about 220 of 270 operations and is
still connection-exclusive: `!connection.barrier` is the first eligibility term for
every kind. One slow unclassified BARRIER operation therefore still blocks the
newly `FINITE` `board.topics`, `swarm.status` and `admin.status` reads completely on
that connection. Every admitted request beyond the two executing — up to the
advertised depth, 8 by default — can therefore sit unanswered behind two slow
barriers until the client's deadline expires as `RESPONSE_LOST`, which is the
originally reported symptom, still reachable. The shallower default makes the residual
window smaller, not larger: fewer requests can pile up behind a slow barrier, and
the rest are refused sooner and more visibly, which is the trade recorded in the
scope call above. Each ingredient is an
accepted scope call; their composition is what needed stating.

## Unverified

The server at the reporting deployment was not inspected. The mechanism is certain
from the source in this worktree; that it is the active cause there is inference
from the burst pattern and the six connections. `DispatchTelemetry.contentions` and
the queue timings would show it directly but are package-private with no endpoint,
and an environment override such as `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE`
would change the pool numbers used above.
