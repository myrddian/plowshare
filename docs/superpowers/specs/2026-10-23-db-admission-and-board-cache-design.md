# Database admission pressure: cache the hot path, gate the rest

Date: 2026-10-23
Status: design, awaiting approval
Follows: [dispatch capacity and honest backpressure](2026-10-23-dispatch-capacity-design.md) (merged as #105)

## Why this exists

The owner reports repeatedly hitting Hikari connection-pool limits in production. That
is a recurring operational problem, not a theoretical one, and the previous change
(#105) deliberately left it open: it noted that pool starvation is not expressed as
capacity, and that raising per-connection execution concurrency above 2 is gated on
fixing exactly that.

This design addresses it in two parts, and the ordering matters because they are not
equally valuable. **Caching the hot read path removes load. The admission gate only
makes the remaining load survivable and legible.** If only one ships, it should be the
cache.

## What was found

### 1. Board and swarm are relay projections that bypass relay's existing cache

Board and swarm are not a separate subsystem — they are views over relay. Almost
everything that is a message goes through relay, and `relay/JdbcRelayNativeRepository`
is one of the writers of `board_topics` and `board_messages`. `BoardStore`'s own javadoc
describes it as "every statement against the board's tables", using plain
`JdbcTemplate` and a `TransactionTemplate`.

Relay already has a working, invalidating cache:

- `relay/JdbcRelayLogRepository` holds `ProjectionCache<List<Relay.Topic>> topicsCache`
  and `ProjectionCache<Snapshot> pagesCache`.
- Relay writes already signal invalidation on `ProjectionVersions.Domain.RELAY` —
  `NotificationRelayPublicationSignals` publishes it, `RelayConfiguration` wires it, and
  `PostgresProjectionNotificationSource` maps the `"relay"` notification to that domain.

So the machinery, including the hard part — correct invalidation on write — is built and
running. The gap is that **the board read path queries the relay-written tables directly
and uses none of it**: `board/BoardStore.java` contains **zero** cache references.

The asymmetry is visible in one pair of operations:

| Operation | Reads | Cached? | Dispatch class |
| --- | --- | --- | --- |
| `relay.topics` | `JdbcRelayLogRepository` | **yes**, `topicsCache` | `FINITE` before #105 |
| `board.topics` | `BoardStore` → `board_topics` | **no** | `FINITE` only as of #105 |

Both read the same substrate. One goes through the cache; the hotter one does not.

`ProjectionVersions.Domain` currently enumerates
`DOCUMENTS, SEARCH, SCHEDULES, RELAY, TRANSCRIPTS, AUTHORITY`. Because relay writes are
exactly what make a board view stale, `RELAY` is very likely the correct domain for the
board reads too, and **a new `BOARD` domain is probably unnecessary** — which makes this
materially cheaper than inventing one. Confirm against what board reads actually depend
on before deciding (see "Left to the builder").

Meanwhile the desktop client polls that uncached path hardest:

- `board.topics` — every **4 seconds** per open board view (`plowshare-desktop/src/board.ts`).
- `swarm.status` — the same 4-second timer, and it then **fans out a per-conversation
  activity read for every visible member**, in batches of four (`board.ts`, the
  `at += 4` loop calling `readingSwarmActivity`).

So the most frequent reads in the system go straight to the shared pool on a 4-second
timer, multiplied by open views and by the member fan-out, with no caching whatsoever.
The pool was Hikari's implicit default of **10** until #105 raised it to 20.

This is the most likely explanation for the reported limit-hitting, and it is a gap
rather than a misconfiguration: board was simply never added as a cacheable domain.

**An interaction #105 introduced and nobody flagged.** That change reclassified
`board.topics`, `swarm.status` and `admin.status` as `FINITE` so they may execute
concurrently instead of serializing behind the barrier default. That improves their
latency and was correct — but it also **increases concurrent pool demand on precisely
the one hot path with no cache**. The benefit and the new pressure land on the same code.

### 2. Pool starvation is indistinguishable from a sick database

Queries execute in one place. 135 files hold ~1,185 `JdbcTemplate` call sites (488
`update`, 272 `query`, 240 `queryForObject`, 158 `queryForList`, plus `execute` and
`queryForMap`), but all of them share **one singleton `JdbcTemplate`** over the primary
`HikariDataSource`, and every connection is borrowed at the same point:
`DataSourceUtils.getConnection(dataSource)` → `dataSource.getConnection()`.

Nothing wraps or instruments that. The only existing `DataSource` decorator is
`FreshConnections` inside `JdbcProjectionVersions`, and it serves the *separate*
projection-versions pool for read-only lease configuration, not metering. So:

- We know the 1,185 static call sites. At runtime we cannot say **which path** holds a
  connection. Hikari reports aggregate pending/active and nothing more.
- A failure to obtain a connection surfaces as an opaque timeout after
  `connection-timeout` (5000ms as of #105). The caller cannot distinguish **"the queue
  was exhausted"** from **"the database is unreachable"** — they are the same error.

That ambiguity is the owner's stated concern, and it is what the gate removes.

### 3. The holding-time hazard is defended only by prose

This codebase has already had an incident of exactly this shape. From
`api/MemoryController.java`:

> "these methods reach `Archive` calls that talk to an embedding model, so the model
> call was inside the write transaction... It also pinned a pooled database connection
> for the length of every model stall — **eighty seconds, worst case, against a default
> pool of ten**."

Four places now warn about it — `MemoryController` ("**Do not put `@Transactional` back
on these methods**"), `Archive.embedding`, `SummaryEmbeddings` and
`DispatchingEmbeddingClient`, the last of which states the bug returns "the moment any
transaction encloses the embedding call". All four are currently correct. **None is
protected by a test or a lint rule.** #105 wrote the rule into
`docs/coding-standards.md`; enforcement across the 23 files using
`@Transactional`/`TransactionTemplate` remains unaudited.

One hazard is already handled: the Postgres `LISTEN` session deliberately uses an
unpooled `DriverManagerDataSource`, commented "LISTEN owns one unpooled session. It must
not pin a main or version-pool connection." So no long-lived holder occupies the primary
pool.

## Builder check: premises of this design that did not survive the code

A builder review traced the implementation and found several of this document's
premises wrong. They are recorded here, verified, **before** the design above is
revised, because two of them change what gets built rather than how.

1. **Invalidation is trigger-driven, not writer-driven — verified.** No Java code
   signals a domain. `V175`, `V176` and `V179` install per-table AFTER-statement
   triggers that bump the counter and `pg_notify('plowshare_projection_changes', …)`.
   `V176`'s trigger set covers the `relay_*` tables plus `conversations`, `projects` and
   `firings` — **and no board table**. So the "audit four Java writers" framed above as
   "the one correctness question" is the wrong question: keying board reads off `RELAY`
   needs a **new Flyway migration** attaching triggers to the board tables either way.
   Reusing `RELAY` is therefore *not* materially cheaper than a new domain, which was the
   stated reason for preferring it.
2. **Reusing `RELAY` couples board writes to relay worker wakeups — verified.** The
   `relay` notification is not only cache invalidation:
   `NotificationRelayPublicationSignals` subscribes to `Domain.RELAY` and calls
   `local.wakeAll()`. Attaching board tables to that domain wakes every relay worker on
   every board write and flushes relay's own `topicsCache`/`pagesCache`.
3. **Any new projection cache fails at startup as configured — verified.**
   `ProjectionCacheProperties.maxProjections` defaults to **16**, `ProjectionCaches.create`
   throws past it, and there are exactly **16** `caches.create` calls today
   (information ×10, archive ×1, documents ×1, relay ×2, schedules ×2). Part A must raise
   that default, which also feeds `maxProjections + 2` for signal waiters. Separately
   `entries: 256` and `residentBytes: 32MB` are shared across all projections, so a board
   projection would evict the information caches unless budgets are revisited.
4. **An exact-version board cache may have a near-zero hit rate — plausible, not
   measured.** `board.topics` calls `store.summaries(...)`; `swarm.status` calls
   `summaries(...)` **and** `inspectSeats(...)`. Those span `board_allowance_state` (a view
   over allowances, which `BoardStore.spend`/`claim` moves on **every model call**, in its
   own committed transaction) and `firings` (live wake state, via a LATERAL join). Under an
   active swarm those churn continuously, so a version-exact cache invalidates on nearly
   every 4-second poll — the hit rate collapses exactly when it is needed. This directly
   undercuts the "removes most of the 4-second polling load" claim and the
   cache-then-remeasure-then-size sequencing.
5. **A mid-handler refusal cannot reuse `CAPACITY_EXHAUSTED` — verified as a real
   hazard.** `connection.ts` treats any `CAPACITY_EXHAUSTED` reply as flow control and
   **resubmits under the same request id**, on the documented premise that "the server
   refused before owning anything, so sending it again is not a mutation replay". A
   `DataSource` decorator refuses inside `getConnection()` — after authorization and
   validation, and potentially after earlier committed statements in the same operation.
   A handler that commits one statement and is then refused on its next acquisition would
   be silently replayed. Only the *first* acquisition of a logical operation is safe to
   refuse, and a per-statement permit cannot tell that it is the first.
6. **One wrap also covers background and durable work.** Relay workers, the firings
   dispatcher, `JobStore`, schedulers and maintenance sweeps all acquire from the primary
   pool. They have no client, no reply frame and no retry contract, so a refusal becomes a
   dropped or arbitrarily-retried durable step — which `AGENTS.md` forbids — and a
   multi-second throttle slows work nobody is waiting on.
7. **Decorating the primary bean silently misconfigures the second pool — verified.**
   `ProjectionCacheConfiguration` does `if (primary instanceof HikariDataSource configured)`
   to copy the JDBC URL, credentials, driver and init SQL into the `projection-versions`
   pool. A decorator fails that `instanceof` with no error, and the second pool falls back
   to generic `DataSourceProperties`. The gated source must therefore be a separate,
   non-primary bean with `JdbcTemplate` and `DataSourceTransactionManager` rewired onto it
   — which is also what makes the Flyway/health-probe margin argument hold.
8. **`FreshConnections` is a weaker precedent than claimed.** It extends
   `AbstractDataSource` and only *configures* the returned `Connection`; it never wraps it.
   Releasing a permit needs a `Connection` proxy whose `close()` decrements, making
   leak-on-unclosed, close-from-another-thread and `unwrap()` callers live concerns.
9. **Correction to the builder check itself:** it reported that no projection cache uses
   `Domain.TRANSCRIPTS`. That is wrong — `ArchiveConfig` references it. Whether
   `conversation.trajectory` specifically reaches a cache is still untraced, so the
   fan-out's caching remains an open question rather than a settled "uncached".

## Goals

- Remove the dominant source of pool load rather than only throttling it.
- Make "the queue is exhausted" attributable and separable from "the database is unwell"
  **for an operator**, through metrics on a single acquire point. Not as a client-visible
  retryable outcome: the gate waits rather than refusing, so no new protocol code exists.
- Give the common case zero added latency; apply cost only under real pressure.
- Make runtime attribution possible: which paths consume the pool.
- Keep a bound on added latency, so pressure never silently becomes a timeout.
- **Make every tunable visible in configuration with its default.** No value that
  governs dispatch, cache or pool behaviour may exist only as a Java annotation default
  or a framework default. An operator must be able to read the config file and see what
  knobs exist, what they are set to, and which ones are derived from which.

## Non-goals

- Raising `request-per-connection` above 2. That becomes safe *after* this, and is its
  own change with its own measurement.
- Refusing an acquire, emitting `CAPACITY_EXHAUSTED` from the database layer, or any new
  outcome code. The gate waits. Refusal mid-handler would let the SDK replay a
  partially-committed operation (see finding 5), and it is not needed to make pressure
  legible.
- Caching the board read path, or adding a signalling domain for it. See Part A.
- Hand-managing database concurrency in application code beyond a single admission
  point. Hikari remains the authority on connections; this gates entry to it.
- Reworking the cache subsystem. It is healthy and its invalidation works; the board
  read path simply does not use it.
- Changing the desktop's polling cadence. Caching makes the cadence affordable; if it
  still is not, that is a separate client decision.
- Enforcing the holding-time rule. Recommended below as a follow-up, not taken on here.

## Anti-goals

Things that would make this a failure even if it technically works:

- Adding latency to healthy traffic. Below the threshold the gate must be effectively
  free.
- Throttling without a bound, which converts pool pressure into the opaque deadline
  expiry this design exists to eliminate.
- A gate that can deadlock against itself through re-entrant acquisition.
- A cached board projection that serves stale data across a write, or that bypasses
  authorization. Cache correctness is not negotiable for a latency win.
- Replacing one unwritten proxy with another: the previous change's root cause was
  `requestGlobal = 8` standing in for a pool of 10 with nothing connecting them. Every
  bound here must be **derived from** the pool size, not configured independently.

## Constraints

- `AGENTS.md`: only repositories/stores and database configuration may touch JDBC
  infrastructure. Controllers, tools, adapters and domain services must not receive a
  `JdbcTemplate` or execute SQL. The gate is therefore configuration-level, not
  repository-level.
- Shared caches must stay bounded and preserve current authorization and documented
  consistency. Adding a cache is not a substitute for fixing wasteful SQL.
- Google Java Style via the Gradle formatter, including tests.
- Database tests stay behind `@Tag("full-db")` and `-PfullDb`, with a stated
  database-specific reason. Most of this is testable without PostgreSQL; pool-exhaustion
  behaviour is the exception.
- Deployment values are configuration, with no hard-coded sizes in runtime code.
- New Flyway migrations only; never edit a shipped one.

## Decided design

### Part A — DEFERRED, not being built

The owner's decision, after the builder check: **do not pursue the board cache now, and
do not add another messaging system around the board.**

That is the right call on the evidence. Caching the board reads requires a new Flyway
migration attaching triggers to the board tables whichever domain is chosen, and the two
available choices are both bad:

- **Reuse `Domain.RELAY`** and every board write wakes every relay worker through
  `NotificationRelayPublicationSignals.wakeAll()` and flushes relay's own caches.
- **Add a `BOARD` domain** and you are standing up a second signalling path around the
  board — new triggers, a new notification parameter, new `Domain` plumbing threaded
  through `SnapshotCache.Limits`, `Token` canonicalisation and the notification listener's
  exhaustive `switch`. That is a messaging system, and it is not wanted.

On top of which the win is unproven: an exact-version cache over
`board_allowance_state` and `firings` would invalidate on nearly every poll, because
allowances move on every model call and firings track live wake state.

The reasoning and the measurements are preserved above so this can be picked up
deliberately later, with a staleness decision made first rather than discovered. The
remainder of this design does not depend on it.

### Part A (original proposal, retained for the record) — put the board read path on relay's existing cache

The approach is to reuse what relay already proves works, not to build a parallel
mechanism.

- **Follow `JdbcRelayLogRepository`'s pattern**, which is the closest precedent and
  operates on the same relay substrate: a `ProjectionCache<T>` per logical read,
  constructor-injected with `ProjectionCache.direct()` as the default so an
  unconfigured deployment behaves exactly as today, and wired to a real cache in the
  owning configuration class.
- **Key off `Domain.RELAY`** rather than adding a `BOARD` domain, unless the audit
  below shows board reads depend on something `RELAY` does not cover. Relay writes are
  what make a board view stale, and that invalidation already fires end to end.
- Cache the reads behind `board.topics` and `swarm.status` — the two the desktop polls
  every four seconds.
- The existing `Token` contract must be used as documented: validate the token stored
  alongside the loaded DTO, never substitute a freshly captured token for an old DTO's
  evidence. It already closes the late-commit hole, and that reasoning is not to be
  re-derived.
- Preserve authorization exactly. A cached projection must not become a way to read rows
  the caller could not read uncached; membership and role checks stay on the read path.
  `BoardInspectionFrames` currently calls `members.requireRole` before `store.summaries`,
  and that ordering must survive.

**The swarm activity fan-out is a separate question.** The desktop's per-member fan-out
calls `conversation.trajectory` (`SWARM_ACTIVITY` in `sdk/typescript/src/operations/swarm.ts`),
not a board or relay operation — so it is transcript data, and `TRANSCRIPTS` is already a
cache domain. `ConversationTrajectoryHandler` shows no cache reference, but the caching
may live deeper in the conversation store; that was not traced. Since this fan-out is the
highest-multiplier query in the polling loop — one read per visible member, every four
seconds — establish whether it is already cached before doing anything to it.

Expected effect: removes most of the 4-second polling load from the pool rather than
pacing it.

### Part B — a graduated, WAIT-ONLY throttle over the primary pool

**Decided by the owner: the gate waits, it never refuses.** That one choice resolves
three of the builder check's objections at once, and it is worth recording why:

- **No replay hazard** (finding 5). Nothing emits `CAPACITY_EXHAUSTED`, so the SDK's
  resubmit-under-the-same-id path is never triggered, and a mid-handler delay cannot
  replay a partially-committed operation. No new outcome code, no six-SDK change.
- **Background and durable work is safe** (finding 6). Relay workers, the firings
  dispatcher, `JobStore`, schedulers and sweeps simply wait. Nothing is dropped or
  arbitrarily retried, so the durable lifecycle contract holds, and the gate needs no
  caller classes or thread-origin detection.
- **The pressure signal cannot latch shut** (finding 6 of the builder's questions). A
  refusing gate stops generating acquire-latency samples exactly when pressure peaks,
  so the level cannot decay back down. A wait-only gate always lets work through
  eventually, so it keeps sampling and can recover.

**What the throttle ceiling means without refusal.** The ceiling caps *added* delay: past
it the gate stops adding delay and passes the acquire straight through to Hikari. It is
not a refusal point. So the worst case is bounded at `ceiling + Hikari connection-timeout`,
and the terminal failure is Hikari's own — which is the honest "the database did not give
me a connection" signal.

**The disambiguation is therefore operator-side, not client-side.** This is the trade the
owner accepted in exchange for dropping refusal:

| Condition | Visible as |
| --- | --- |
| queue deep / throttling | the gate's own metrics — occupancy, added delay, time at ceiling |
| database not answering | Hikari `connection-timeout`, now rare and meaningful |

A client no longer learns "the queue was exhausted" as a distinct retryable outcome. An
operator does, from metrics, which is what the original complaint was actually about: a
SQL timeout that could not be attributed. Attribution labels on the single acquire point
are what make this real, so they are not optional here.

A `DataSource` decorator over the primary `HikariDataSource`, following the existing
`FreshConnections` precedent. Repositories keep the same `JdbcTemplate` and change
nothing; all ~1,185 call sites are covered by one wrap.

**Transaction-scoped lifetime falls out for free.** Spring's
`DataSourceTransactionManager` calls `getConnection()` exactly once per transaction and
binds it to the thread; every `jdbc.*` call inside reuses the bound connection without
touching the `DataSource` again. So one permit per transaction, released at commit or
rollback, and per-statement acquisition for non-transactional calls. No
transaction-synchronization hooks are needed. The decorator must wrap the Hikari
`DataSource` with the transaction manager configured on the **gated** one, or binding
bypasses the gate.

**Three levels**, as the owner specified:

| Occupancy | Behaviour |
| --- | --- |
| below threshold (~½ pool) | grant immediately; effectively zero cost |
| threshold → ceiling | pressure-proportional delay before acquiring |
| at ceiling | stop adding delay; pass through to Hikari (no refusal) |

**The delay curve.** Base quantum 20ms, growing geometrically across the throttle band
to a ceiling just under the maximum. The ratio is **derived**, not hard-coded:

```
ratio = (maxDelay / baseDelay) ^ (1 / (steps - 1))
```

With a pool of 20, threshold 10 and base 20ms this gives a ratio of roughly 1.8:

```
10→20ms   11→36   12→65   13→117   14→210
15→378    16→680  17→1224 18→2203  19→3966
```

Deriving the ratio is deliberate: a hard-coded doubling silently breaks the moment the
pool is resized, which is precisely how `requestGlobal = 8` became an unwritten proxy
for a pool of 10. Note that exact doubling from 20ms across a ten-wide band overshoots
5s at occupancy 18, so pure doubling and an asymptotic ceiling are not simultaneously
satisfiable — hence the derived ratio.

**Jitter must scale with the quantum.** The actual wait is drawn from `[q/2, q]`. A
fixed ±few milliseconds is meaningless at 2.2s, and an unjittered delay makes every
waiter sample the same pressure and wake together — the lockstep failure already fixed
once in the SDK resubmit backoff.

**The throttle is bounded, then passes through.** Crossing the maximum cumulative wait
stops the throttling rather than refusing: the acquire goes to Hikari and succeeds or
hits `connection-timeout`. Unbounded throttling would let a request consume its whole
30s request deadline and fail opaquely, which is the ambiguity this exists to remove, so
the ceiling is what prevents that.

**Pressure is measured as acquire latency, not occupancy** — the CoDel insight that
delay is the signal that matters. This is chosen for a reason specific to this codebase:
the documented incident was an 80-second model stall holding a connection, a *holding
time* problem. Occupancy barely moves when two threads hold connections for 80 seconds;
acquire latency moves immediately. Since a permit is held for a whole transaction,
a latency signal throttles exactly when long transactions are the cause.

**The permit must be re-entrant per thread.** Track depth in a thread-local; acquire on
0→1 and release on 1→0. Without this, a non-transactional path holding a connection that
issues a nested query — a `RowMapper` that queries, for instance — takes a second permit
while holding one, and N such paths deadlock a semaphore of N. Re-entrancy makes the
unit "one slot per logical unit of work".

**The ceiling is derived from the pool, minus a margin for un-gated consumers.** At
exact parity the disambiguation still holds in principle — the gate refuses before
Hikari is ever asked, so a Hikari timeout can only mean a connection that should have
existed did not. But some consumers bypass any gated repository path: **Flyway** takes
primary-pool connections at boot, and Spring Boot's `DataSourceHealthIndicator` borrows
one on every `/actuator/health` probe, which #105 newly exposed. The margin exists for
them, not for races.

**Gate only the primary pool.** The separate `projection-versions` pool stays ungated so
a transactional path that also reads it cannot self-contend.

**Attribution.** With one acquire point, label each acquire with its operation and emit
a counter. This is what finally answers "which path is eating the pool" at runtime
rather than statically.

### Part C — every parameter visible in configuration, with its default

**This is a requirement, not a nicety.** The root cause of the predecessor bug was an
invisible number: `requestGlobal = 8` existed only as a Java field fed by a `@Value`
default, and the pool size it was standing in for was an *absent* line in
`application.yml` taking Hikari's framework default of 10. Nothing in the config file
revealed either, so nobody could see the invariant between them, and it drifted.

Today `application.yml` has a `plowshare.transport` block containing **only** `keepalive`.
Everything that governs dispatch and cache behaviour is invisible to an operator reading
the file:

| Currently invisible | Lives as | Default |
| --- | --- | --- |
| `plowshare.transport.request-depth` | `@Value` in `EventChannelConfig` | 8 |
| `plowshare.transport.request-per-connection` | `@Value` | 2 |
| `plowshare.transport.request-global` | `@Value` | 0 (unlimited) |
| `plowshare.transport.request-observers` | `@Value` | 256 |
| `plowshare.transport.event-read-concurrency` | `@Value` | 2 |
| `plowshare.transport.event-read-global-concurrency` | `@Value` | 8 |
| `plowshare.transport.packet-memory-bytes` | `@Value` | 2147483648 |
| `plowshare.transport.request-shutdown-drain` | `@Value` | 10s |
| `plowshare.projection-cache.*` (14 keys) | `@DefaultValue` record | see `ProjectionCacheProperties` |

So:

- **Every parameter this design introduces** — the gate's threshold, ceiling, base
  quantum, maximum delay and pressure window — appears in `application.yml` with its
  default written out and an environment override, never only as a Java default.
- **The existing dispatch and cache parameters above are surfaced too.** They are the
  ones an operator diagnosing pool pressure needs to read *together*, and they are the
  same class of hidden value that caused the original incident. Surfacing them is part
  of this change, not a separate cleanup.
- Follow the file's established style: the `keepalive` block's
  `key: ${ENV_VAR:default}` form with a comment explaining what the value means and what
  changing it costs. Note the dispatch properties currently have no environment variable
  at all; give them one, consistently named.
- A derived value must be shown as derived. The gate's ceiling and threshold come *from*
  the pool size; the config must make that relationship visible rather than inviting an
  operator to set two numbers that can silently disagree — which is exactly how
  `requestGlobal(8) < pool(10)` became an unwritten invariant.
- `docs/server-administration.md` already documents the `request-*` and pool keys from
  the predecessor change; extend it to the cache budgets and the gate, so the config file
  and the manual agree.

### Separating the two failures

The point of the whole throttle, now that it waits rather than refusing:

| Condition | Means | Surfaces as |
| --- | --- | --- |
| throttling, up to and at the ceiling | the queue is deep | the gate's own metrics: occupancy, added delay, time spent at the ceiling, and the attribution label of what is holding permits |
| Hikari `connection-timeout` fires | a connection that should have been free was not | genuine database trouble — now rare, and meaningful because the throttle has already absorbed ordinary queueing |

This is an **operator-side** disambiguation. No new client-visible outcome exists, which
is the deliberate consequence of waiting rather than refusing. The original complaint was
that a SQL timeout could not be attributed to a cause; metrics on a single acquire point
answer that, and they are the part of this design that actually closes the complaint.

The throttle ceiling must still be clearly **smaller and distinct** from Hikari's
`connection-timeout`. #105 set that timeout to 5000ms; a 5s ceiling would collide
numerically — indistinguishable in logs, which defeats the attribution this exists for —
and would allow 5s of throttle plus 5s of acquire before failure, against a 30s request
deadline.

## Sequencing

1. **Measure.** Read `hikaricp.connections.pending` and `.active` from the merged build
   via the actuator #105 added. This is the first time pool pressure has been observable
   at all, and it should size the threshold and ceiling rather than a guess.
2. **Part B, the wait-only throttle, with Part C alongside it.** Part A is deferred, so
   there is no cache step to measure around. The throttle's parameters are chosen from
   step 1 where it is available and from reasoned defaults where it is not, and the
   attribution labels it adds are what make a later re-tune possible.

**Part C runs alongside whichever part ships first**, not at the end. Surfacing the
existing dispatch and cache parameters is what makes step 1's measurement interpretable
— you cannot reason about observed pool pressure without being able to see the bounds
currently in force. Any parameter a part introduces is surfaced in that part's own change,
never deferred.

## Left to the builder

- Threshold as a fraction of pool size: ½ is the owner's figure; confirm against
  measurement.
- The throttle ceiling. 3s is my call (see below); the only hard requirement is that it
  is clearly distinct from and smaller than Hikari's `connection-timeout`, and that
  ceiling plus acquire timeout sits comfortably inside the request deadline.
- The exact margin between gate ceiling and pool size, given Flyway and health-probe
  consumption.
- How acquire latency is smoothed into a pressure level (a CoDel-style sojourn-time
  interval, or an EWMA) and over what window.
- Which board queries are cached at what granularity, and the invalidation key shape.
- **Whether `Domain.RELAY` alone covers everything the board reads depend on.** Board
  tables are written by `relay/JdbcRelayNativeRepository`, but also by
  `board/JdbcBoardMessagingRepository`, `board/BoardStore` itself and
  `access/JdbcResourceScopeRepository`. If any of those can change a board view without
  firing a `RELAY` signal, the cache would serve stale data — so either those writes
  must signal `RELAY` too, or board needs its own domain. **Audit all four writers before
  choosing.** This is the one correctness question that decides Part A's shape.
- Whether the `conversation.trajectory` fan-out is already cached through `TRANSCRIPTS`,
  and if not, whether it should be.
- Whether attribution labels are a bounded enum of operations or derived from the call
  site, bearing in mind fixed cardinality is required for metrics.

## Calls made while writing this

- **Part A before Part B**, and stated as such. The owner asked for the gate; the cache
  gap was found while investigating and is very likely the larger cause. Shipping only
  the gate would throttle traffic that should not exist. Reversible: the parts are
  independent.
- **Reuse `Domain.RELAY` rather than adding a `BOARD` domain**, subject to the
  four-writer audit above. My first draft of this design proposed a new `BOARD` domain,
  on the mistaken premise that board was a subsystem the cache had simply never covered.
  The owner corrected it: board and swarm are built on relay, and almost everything that
  is a message goes through relay. That makes the existing `RELAY` domain and its
  already-wired invalidation the natural fit, and avoids a second mechanism over the same
  substrate. Cost if wrong: if a non-relay writer can stale a board view, reusing `RELAY`
  serves stale data — which is why the audit gates the choice rather than following it.
- **Throttle ceiling 3s rather than the owner's 5s.** 5s collides exactly with Hikari's
  `connection-timeout`, making the two conditions indistinguishable in logs and
  permitting a 10s worst case. The owner has not confirmed 3s.
- **Pressure as acquire latency rather than occupancy**, agreed with the owner, on the
  grounds that the codebase's documented incident was a holding-time failure that
  occupancy would barely register.
- **Ratio derived rather than exactly 2.** The owner specified "20ms doubling" with a 5s
  asymptote across a ten-wide band; those three are arithmetically incompatible, since
  doubling from 20ms reaches 5120ms at occupancy 18. The derived ratio preserves the
  intended shape and self-adjusts when the pool is resized.

## Unverified

- No measurement of actual pool pressure was taken. The deployment's actuator returned
  404 for `health`, `env` and `hikaricp.connections.max` — consistent with it running a
  build predating #105 — and `max_connections` could not be read without credentials.
  Every number here is reasoned from the code and from the documented incident, not from
  production data. Step 1 of the sequencing exists to fix that before the gate is tuned.
- The claim that the board polling load is the dominant pool consumer is inference from
  the client's 4-second timers, the member fan-out, and `BoardStore` holding no cache
  reference while the relay repository over the same substrate does. It is consistent
  with the reported symptom but not confirmed by attribution data — which is itself one
  of the things this design adds. Step 1 of the sequencing should confirm or refute it
  before Part A is sized.
- Whether every writer of the board tables signals `RELAY` invalidation. Four writers
  exist (`relay/JdbcRelayNativeRepository`, `board/JdbcBoardMessagingRepository`,
  `board/BoardStore`, `access/JdbcResourceScopeRepository`) and only relay's signalling
  was traced. This is the correctness gate on Part A's shape and is listed for the
  builder.
- Whether `conversation.trajectory` — the per-member swarm fan-out, and the highest
  multiplier in the polling loop — is already cached via `TRANSCRIPTS`. Its handler shows
  no cache reference, but the caching could live deeper in the conversation store. Not
  traced.
- Whether any current path encloses a network call in a transaction was not audited.
  The four known sites are correct; the other 19 transactional files were not read.

## Decisions made by the owner after the builder check

- **The throttle waits; it never refuses.** Recorded in Part B with the three builder
  findings it resolves. Accepted consequence: no client-visible capacity outcome, so the
  disambiguation is operator-side via metrics.
- **The board cache is deferred, and no new signalling system is to be added around the
  board.** Part A is retained for the record but is not being built. This removes the
  `RELAY`-versus-`BOARD` domain question, the board trigger migration, the
  `maxProjections` ceiling problem and the shared cache-budget question from scope.
- **Every parameter visible in configuration with its default** (Part C) stands, and runs
  alongside Part B rather than after it.

## What remains open

- **The throttle ceiling: settled at 3s, but the number is not the requirement.** The
  owner confirmed 3s and stated the actual constraint: the ceiling must be below Hikari's
  `connection-timeout` and far enough below it to be distinguishable. So 3s is an example
  satisfying the invariant, not a magic value.

  **This must be validated at boot, not merely documented.** Fail startup with a clear
  message if the configured ceiling is greater than or equal to `connection-timeout`, or
  within a stated margin of it. The predecessor bug was precisely an unenforced
  relationship between two numbers in different files — `requestGlobal(8) < pool(10)` —
  and this is the same shape. Validating it is what stops it drifting when either value is
  tuned, and it makes the dependency visible to whoever changes one of them.
- **The acquire-latency sample definition.** Wait-only removes the latch-shut failure, but
  it must still be stated whether a Hikari timeout registers as a maximum sample or is
  excluded, and over what window the level decays. Left to the builder with that
  constraint named.
- **Measurement.** Still unobtainable from the deployment. The parameters ship as reasoned
  defaults, and the attribution labels this adds are what make a later re-tune possible
  rather than another guess.
