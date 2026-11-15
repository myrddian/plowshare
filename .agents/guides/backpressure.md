# Backpressure

Read this when changing admission, concurrency, queue/byte bounds, saturation,
refusal/retry behavior, physical release or downstream capacity configuration.
The current layered contract is in [backpressure behavior](../../docs/backpressure.md).
Read the relevant owner, not every subsystem guide by default.

## Mental model

Backpressure governs demand for a scarce resource, separately from authority,
completion deadlines and durable lifecycle. Overlapping layers have different
physical owners; ending one caller's wait does not settle every occupied resource.

## Start at the occupied resource

| Boundary | Current owners |
| --- | --- |
| SDK admission and physical writes | [SDK connection], [SDK packets] |
| Server intake, ordering and output | [ConcurrentRequests], [EventChannelHandler] |
| Logical reads, reports and cache fill | [BoundedReadAdmission], [VersionedProjectionCache] |
| Primary DB connection pacing | [AdmissionThrottle], [ThrottledDataSource] |
| Provider lanes and queued start | [LlmPool] |
| Durable inference capture | [AccountingRecorder], [AccountingJournal] |

## Rules that are easy to miss

- Name what is bounded: pending requests, encoded bytes, physical transfers,
  connections, running handlers, provider lanes or durable metadata. A limit on
  one does not bound all producer promises, jobs or downstream work.
- `CAPACITY_EXHAUSTED` is the explicit pre-handler outer refusal. Only its owning
  safe flow-control contract permits paced resubmission with original identity
  and deadline. Timeout/disconnect/packet failure never supplies that proof.
- `ReadUnavailableException` → WS `ARCHIVE_UNAVAILABLE` / HTTP 503 is different:
  a mutation may already have committed before its projection fails. Reconcile
  that receipt/effect; retry only the separately safe read. Busy is not permission
  denial, empty data or authority to use stale allows.
- Logical settlement is not physical release. SDK admitted bytes/counts may stay
  owned until the physical writer settles. Server running work, cache loaders,
  provider calls and DB checks can outlive their caller. Keep occupancy until the
  owning completion/cleanup actually retires it.
- DB admission is WAIT-only: its default 3s ceiling caps added pacing delay, not
  total acquire time. Saturation waits on an unbounded semaphore before Hikari's
  separate acquire timer. Do not claim universal two-second enforcement.
- Same-thread nested DB borrows may proceed permit-free at saturation to avoid
  self-deadlock. Margin is not a bound on those borrows. Never hold a connection
  while joining another thread that needs a DB permit; preserve inline FETCH.
- Foreground reports and FETCH wait FIFO among eligible owners, not globally.
  Background reports yield; per-connection barriers and process-wide conflicting
  resource-key ordering must survive concurrent intake changes.
- Provider job queues are count-unbounded; submit timeout covers queue start,
  not an already running call. Journal pressure gates tracked inference even
  with zero pricing; retrying metadata must never repeat inference.

## Before changing a limit

Identify the admission unit, deadline start, physical resource owner, refusal
proof and release on success/error/cancel/close. Inspect current occupancy,
connection holds, query cost, queue age, output blockage and retries before
raising limits. Pool `.overdraft` counts nested admissions; it is not a live
occupancy gauge. Avoid unbounded principal/query/job metric labels.

Follow the [completion policy](../../docs/coding-standards.md#bound-active-work-in-every-subsystem):
short interactive work has a configurable 2s default budget and nested work must
inherit the earlier remaining budget. Current request, cache, DB and provider
limits cover different phases; this policy is not a claim that every boundary
already cancels physical work end-to-end. Cleanup needs its own finite bound.

## Verify the changed boundary

Use the focused non-DB tests linked in the [deep document](../../docs/backpressure.md#deadline-gap-and-change-checks).
Cover saturation, slow/silent peers, expiry before/after effects, late replies,
cancellation failure, same-thread nesting, cross-thread holds and physical release.
Prefer controlled clocks/transports over sleeps. Docs-only edits need source/link
review; do not start PostgreSQL, real providers or live capacity experiments.

[SDK connection]: ../../sdk/typescript/src/binding/connection.ts
[SDK packets]: ../../sdk/typescript/src/binding/packets.ts
[ConcurrentRequests]: ../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/ConcurrentRequests.java
[EventChannelHandler]: ../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/EventChannelHandler.java
[BoundedReadAdmission]: ../../plowshare-server/src/main/java/io/aeyer/plowshare/server/reads/BoundedReadAdmission.java
[VersionedProjectionCache]: ../../plowshare-server/src/main/java/io/aeyer/plowshare/server/cache/VersionedProjectionCache.java
[AdmissionThrottle]: ../../plowshare-server/src/main/java/io/aeyer/plowshare/server/pool/AdmissionThrottle.java
[ThrottledDataSource]: ../../plowshare-server/src/main/java/io/aeyer/plowshare/server/pool/ThrottledDataSource.java
[LlmPool]: ../../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/dispatch/LlmPool.java
[AccountingRecorder]: ../../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/accounting/AccountingRecorder.java
[AccountingJournal]: ../../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/accounting/AccountingJournal.java
