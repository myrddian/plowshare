# Bounded tracked DTO cache foundation

Status: implemented locally on 2026-10-20; deployment and repository adoption
are separate. This follows the [second slice](query-efficiency-second-slice.md)
and implements the cache-foundation phase of the
[delivery plan](query-efficiency-plans/delivery.md).
The [following adoption slice](query-efficiency-cache-adoption.md) adds the
document/search/schedule bindings and PostgreSQL version/notification bridge;
the boundaries below describe this foundation's original standalone delivery.

## Capability and ownership

[`SnapshotCache<T>`](../plowshare-server/src/main/java/io/aeyer/plowshare/server/cache/SnapshotCache.java)
is the typed capability. Composition constructs
[`BoundedSnapshotCache<T>`](../plowshare-server/src/main/java/io/aeyer/plowshare/server/cache/BoundedSnapshotCache.java)
for one configured database/server namespace and repository projection/schema.
The instance owns its loader executor and deadline scheduler and must be closed
with its owner. Limits are explicit; there is no automatically enabled cache or
generic SQL loader. Each repository remains responsible for authorization,
current version evidence, deeply immutable DTOs and complete dependency coverage.

A request identifies the domain object, full visibility/selection context,
equality-only durable source version and all entity/collection dependencies.
The version must come from the owner's real source contract, not a wall clock,
schema version, local counter or hash invented to make an unversioned row eligible.
Keys and tokens have bounded string lengths; requests defensively copy a bounded
dependency set. Different versions occupy distinct identities and never overwrite
one another by guessing which opaque token is newer.

The loader returns the actual source version, immutable DTO and declared weight.
Successful tracking retains that value together with namespace, request,
dependencies, total charged weight and local epoch/load-generation fence.
The returned source version must exactly match the requested version. Null values,
failed loads and over-reservation results are not successful cache entries.
Eviction removes cache ownership without mutating a DTO already held by a caller.

## Bounds and LRU

An indexed hash map and doubly linked recency list give expected O(1) lookup,
recency updates and the LRU part of one known eviction/removal. There is no scan
or sort to choose an eviction victim. Removing reverse-index edges costs O(d)
for the bounded d dependencies; evicting k entries costs O(k*d). Key/dependency
invalidation visits only indexed affected references. Whole-projection clearing
is intentionally linear in that projection's retained work.

Limits cover resident entries and weight, physical loads and their reserved
weight, global/per-load waiters, dependencies per request and total tracked
key/dependency links. Weight includes a conservative metadata charge in addition
to the DTO weight supplied by its owner. Invalidated or abandoned physical work
still retains its reservation. A resident value and a delivery still retaining
the same data are conservatively charged separately until delivery exits.

These are declared-weight budgets, not JVM heap instrumentation. Loader owners
must conservatively account nested objects and bound transient allocations and
captured loading context themselves. Allocation cannot be made safe merely by
reporting a small weight after an oversized read. Global/per-load waiter counts
also bound timer/future/completion-task bookkeeping independently of DTO weight.

A value within its load reservation but too large for resident admission is
returned without retention. Metadata capacity can also require bypassing
residency. A load that cannot reserve capacity is refused explicitly; it does
not silently launch an uncached request outside the limits.

## Coalesced loads and caller isolation

The same key/version coalesces only when dependencies and reservation match.
Dependencies are registered before dispatch, so an invalidation during a load
cannot be missed because its dependency list arrived too late. Loaders run
outside the recency lock. Distinct source versions do not share a load.

Each caller gets an independent read handle and read-only completion view.
Use the handle's cancellation method to detach that caller; cancelling a copied
completion future does not cancel the shared work. Deadlines detach only their
waiter. The final waiter leaving abandons the load without interrupting or
replaying it, and its physical reservation stays charged until execution exits.
Failures permit an explicit later request; the cache performs no automatic retry.

Future completion may run arbitrary synchronous application callbacks. Each
settled waiter therefore receives a separate virtual completion task, bounded
by the retained waiter limit. One blocked callback cannot stall sibling
completions or the deadline scheduler. Waiter and physical-load reservations
remain charged until their completion callbacks actually exit. A visible result
can precede final accounting cleanup. The owned timer removes cancelled tasks
from its queue. Exceptional delivery-dispatch failures are reported as failures;
sibling dispatch is attempted before emergency synchronous failure completion.

## Invalidation and recovery fences

Local invalidation is distinct from a durable source version. Every physical load
captures a recovery epoch and unique local generation. Key invalidation covers
all resident and joinable versions of that key; dependency invalidation also
covers pending loads and collection membership dependencies. Invalidated loads
leave the join index, fail their pending waiters and retain their invalid marker
until execution and completion tasks exit. A new explicit request may start with
available capacity. The old fill cannot overwrite it, even after eviction and
recreation. No unbounded tombstone map is retained for absent keys.

Admission and read selection linearize under the cache lock. An invalidation
that wins before that decision rejects the fill and its result. An invalidation
afterwards cannot revoke a DTO already logically returned to an operation,
including one whose completion callback has not run yet. Current disclosure and
revocation checks remain the owner's responsibility; this cache is never an
authorization lease or proof of commits not observed by this process.

Instances start in recovery and refuse cache reads. `beginRecovery()` clears the
projection, advances its epoch and returns an opaque instance-owned ticket.
After authoritative baseline/catch-up work, only that exact current ticket can
complete recovery. A foreign or stale ticket cannot reopen a newer outage.
`invalidateAll()` advances/clears without proving recovery or reopening it.
`close()` blocks new admission, settles pending waiters and prevents late fills;
running loaders are not interrupted. Physical accounting remains until they exit.

The future PostgreSQL notification bridge must drive these recovery/invalidation
operations from committed changes and durable catch-up evidence. Listening being
healthy is insufficient. Notifications, transaction hooks, durable change
generations and commit-order recovery are not implemented in this slice.

## Repository adoption gates

The [second-slice DTOs](query-efficiency-second-slice.md#linking-dtos-to-the-later-cache)
remain operation-local. Their processing generation, file schema version or
embedding-profile version does not version all displayed data or current policy.
They are not automatically admitted into this foundation. Owners must first add
complete durable versions, all-writer invalidation, authoritative disclosure
fences and conservative weights for each eligible projection. Changes committed
by other server processes and membership changes must be covered too.

Do not cache active grants, credential state, locks, claims, leases, execution
cursors, readiness or mutation/idempotency decisions. No successful warm-read or
production latency improvement is claimed by this foundation alone.

## Verification

Deterministic in-memory tests cover LRU/weights, typed tracking, coalescing,
cancellation/deadlines, failure cleanup, invalidation and recovery races, bounds
and blocked callbacks. They use controlled executors/timers and latches rather
than a database or provider. The following passed on 2026-10-20:

- `./gradlew format` and `./gradlew formatCheck`.
- `./gradlew :plowshare-server:test --tests 'io.aeyer.plowshare.server.cache.*'`:
  all 35 focused cache tests passed.
- `./gradlew check --continue --max-workers=1`: the complete repository check,
  including all 6,041 server tests with no failures or skips, passed.

No SQL or schema is changed. PostgreSQL tests were not run because this slice
only changes in-memory bookkeeping and requires no database-specific assertion.
