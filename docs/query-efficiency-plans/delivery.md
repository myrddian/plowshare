# Delivery across repository boundaries

Status: proposed implementation work. See the [owner inventory](README.md) and
[query and cache strategy](../query-efficiency-and-cache-design.md). Repository
plans describe candidates, not measured improvements or implemented mechanisms.
The [first implementation receipt](../query-efficiency-first-slice.md),
[second implementation receipt](../query-efficiency-second-slice.md) and
[cache foundation receipt](../query-efficiency-cache-foundation.md) record the
completed local slices, verification and remaining cache-admission gates.
The [initial adoption receipt](../query-efficiency-cache-adoption.md) records
document/search/schedule bindings and the PostgreSQL version/notification bridge.

## Start with operation costs and ownership

Capture a bounded trace for Manual loading, document search, Relay topics/messages
and schedules. Separate socket queue time, service time, SQL time, rows and bytes,
mapping, transport and rendering. Count repeated permission functions as well as
JDBC statements: one statement can still perform thousands of nested reads.
Record parameter shapes and cardinalities without retaining private values.

Give each operation a query budget and explicit freshness contract. A warm object
reuse test must demonstrate the saved work; cold queries must also improve.
For a regression, identify the introducing commit before implementing its fix and
retain a focused reproduction; an old inefficiency is not automatically a new
regression.
Prefer the existing authorized typed DTO in an operation over a new query,
separate version check or shared-cache lookup for the same fields. Cache hit rate
alone is insufficient evidence of reduced latency.

Start delivery with catalogue facets and authorization functions, duplicate
document hydration/coverage, schedule file application and client/socket blocking.
Relay SQL, durable-work discovery and the remaining owners follow their measured
cost and correctness risk. A plan for an infrequent, already bounded lookup may
conclude that no new index or shared cache is justified.

## SQL and typed read contracts

1. Inventory the fields each caller uses. Return bounded immutable projections
   with their durable source version in the same read. Carry those snapshots
   through composition instead of discarding them and looking up their IDs.
2. Consolidate facet aggregates around one authorized candidate relation. Evaluate
   a materialized CTE, grouping sets or a table-returning SQL function against
   equivalent results. Keep per-dimension filter semantics explicit: excluding a
   dimension's own filter can require distinct candidate sets, not one blindly
   shared aggregate. Do not persist a caller's allowed set as durable authority.
3. Inspect current definitions of readability, dependency and project/account
   permission functions. Compute invariant authority once under a declared
   operation snapshot. Preserve the exact global, personal, project, membership,
   dependency and delegation rules, including revocations and cycles.
4. Use set operations, explicit column lists, keyset paging where contractually
   appropriate and `RETURNING` for committed write results. Avoid replacing one
   JDBC N+1 with a SQL function that still queries once per row.
5. Compare existing composite, partial, expression and GIN indexes with actual
   predicates and ordering. A joined search expression cannot simply become a
   generated column spanning other tables: a maintained search projection needs
   complete writer coverage, transaction semantics and backfill/rebuild ownership.
6. Introduce new indexes/functions/procedures only through new Flyway migrations.
   Record read/write costs and production rollout behavior, including PostgreSQL
   restrictions on concurrent index creation inside a migration transaction.
   Use invoker authority by default; any elevated routine needs a separate
   privilege and search-path review. Routines remain behind typed repositories.

The owner plans identify local SQL changes. A shared contract change must update
its owning interface and all callers; moving SQL into a controller or generic
cache loader would violate the repository boundary.

## Shared cache foundation and repository linkage

Define narrow typed cache capabilities and database change infrastructure before
adopting them in individual repositories. Composition roots bind implementations;
application services receive repository interfaces and immutable DTOs. Do not
expose SQL, tables, raw JSON or JDBC to the cache's consumers.

Each repository opts in by defining its projection and key type, source version,
visibility context, admission/weight policy, consistency contract and dependency
set. Immutable revision content is distinct from mutable heads, lists, access
policy and processing state. A shared entry must never authorize disclosure by
itself. Keep current authority checks or an equally strong version fence; a
listener connection being healthy does not close the interval after a revocation
commits and before its notification is processed.

Audit every mutation before treating an existing revision column as a display
version. For example, outgoing cancellation changes displayed state without
advancing its worker revision. Introduce a separate change generation where
necessary; do not alter claim/report fencing merely to obtain a cache key.

Track an admitted DTO snapshot with its identity, version, projection, dependencies,
invalidation generation, memory weight and LRU node. Concurrent hash indexes and a
doubly linked recency list provide expected O(1) lookup, known removal and
individual eviction.
Evicting k entries costs O(k). Bound entry count, total weight, key/dependency
metadata, in-flight loads and waiters. Oversized values bypass admission.
Eviction releases cache ownership without changing a DTO held by a caller.

Coalesce loads of the same key and version without holding the recency lock during
SQL. Before filling, capture the relevant generation; admit the returned version
only if it still satisfies generation and consistency fences. Generation state
must survive entry eviction while older loads remain in flight. Define bounded
retry, cancellation and error cleanup; failed loads are not successful entries.

Every writer must participate in invalidation, including another repository that
updates the same table, cascade/trigger effects, background work and another server.
Publish committed replacements or invalidations after commit; rollbacks publish
nothing. Include collection membership changes and formerly absent objects, not
only DTOs already present in a result. There is no automatic dirty checking.

Use a dedicated infrastructure connection for LISTEN. Persist mutation and durable
change/version evidence atomically and emit an opaque hint within that transaction.
Do not send content, credentials or unfiltered entity identifiers to clients.
Sequence allocation order alone does not establish commit order: a durable catch-up
protocol must close late-commit gaps or clear the affected namespace when proof is
unavailable. Register LISTEN before establishing the baseline, reconcile on reconnect
and bypass affected entries until recovery is authoritative. Notifications prompt
invalidation and on-demand loading; they do not require eager refetch of every DTO.

Initially exclude live grants, acting/admission decisions, credentials, readiness,
locks, worker claims, leases, idempotency decisions and execution cursors from
shared read caching. Reusing immutable descriptive data does not replace the
owning transaction's current lifecycle decision. Owner plans can justify stronger
contracts later without turning cache misses into a second durable runtime.

## Durable discovery, client refresh and request dispatch

Relay, information events, schedules, embedding, outgoing work and executions keep
their durable rows, leases, cursors, retries, acknowledgments and idempotency.
Notifications wake bounded discovery; periodic reconciliation, startup recovery
and timed eligibility still find work. A future lease expiry or schedule becoming
due has no new database mutation, so it also needs a timer or bounded sweep.

The [connection dispatcher](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/EventRequests.java)
now permits a bounded allowlist of independent reads. Unknown operation types,
malformed envelopes and acting-context requests remain ordered barriers. Retain
that explicit policy when adding operations; apparent reads that subscribe,
advance cursors, acquire leases or mutate bookkeeping need separate treatment.
Preserve mutation order, read-after-write barriers, execution-time authority and
global/per-connection task and memory bounds. Packet acknowledgments remain
responsive and out-of-order replies use existing request IDs. Closing/cancelling
a read must not replay an operation of uncertain completion.

The [Desktop information view](../../plowshare-desktop/src/renderer/information.ts)
now renders lists independently of facets and coalesces pending facet requests.
It still refreshes the active view every four seconds. Retain DTOs already
returned, and invalidate affected views through scoped WebSocket hints with bounded
reconciliation. Fence late responses by connection, principal, scope and selection
generation; clear private state on logout/account/server changes. A local hint
cannot expand server authorization or reveal protected resource existence.

These changes require affected SDK contracts and client tests together. Clients
never subscribe directly to database notifications. The owner repository remains
responsible for the authoritative read and its typed result.

## Reviewable phases and acceptance

| Phase | Deliverable | Required evidence |
| --- | --- | --- |
| Baseline | Operation traces and owner query budgets | Queue/SQL/function/bytes/render costs separated; no secrets in traces |
| Query fixes | Snapshot reuse, facet/function consolidation, schedule batching, matching indexes | Fewer reads and equivalent typed results/authority; representative cold plans |
| Client/dispatch | Independent rendering, request coalescing and bounded independent reads | A slow facet no longer holds Relay responses for its entire duration; ordered writes retain behavior |
| Cache foundation | Typed tracked DTOs, bounded LRU, load coalescing, generation fences | Mocked deterministic race, cancellation, eviction, memory-bound and failed-load tests |
| Notification bridge | Committed change evidence, dedicated listener and recovery | Focused PostgreSQL tests for rollback, cross-process delivery, late commits and reconnect gaps |
| Repository adoption | Small groups of opt-in owner projections | Explicit writer/dependency coverage, revocation proof, warm query savings and cold-query health |
| Discovery adoption | Notification wakeups plus reconciliation and timers | No loss/duplicate effects across disconnected listeners, restart or lease expiry |

Keep mock repository/service/client tests as the default. Only SQL result
equivalence, indexes/routines, transactions, locking or real notification behavior
justify a focused `-PfullDb --tests ...` run, with the database-specific reason
stated first. Do not run the full database suite for documentation or cache
bookkeeping. Run required formatting and the ordinary `check` gate for later
implementation changes. Report benchmark environment and source revision, and
distinguish proposed targets from measured outcomes.
