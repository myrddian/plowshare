# Persistence, SQL and cache

Read this for repositories, SQL/migrations, projection caches or read capacity.
Read [AUTH](auth.md) as well when changing permission predicates or authority
freshness. This guide does not replace each repository's domain contract.
For admission queues, saturation or capacity changes, read
[backpressure](backpressure.md) for wait/refusal and physical ownership rules.

## Mental model

Specialist repositories own SQL and typed row mapping. PostgreSQL owns durable
facts, constraints and atomic transitions; caches reuse immutable projections
under explicit consistency evidence. Read admission bounds logical work before
connection acquisition, while the pool separately bounds physical database work.

## Start in code

- [ProjectionCache](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/cache/ProjectionCache.java)
  defines read reuse and the resident candidate contract;
  [ProjectionVersions](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/cache/ProjectionVersions.java)
  owns capture evidence, domains and item dependencies.
- [SnapshotCache](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/cache/SnapshotCache.java),
  [VersionedProjectionCache](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/cache/VersionedProjectionCache.java)
  and [ItemProjectionCache](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/cache/ItemProjectionCache.java)
  implement the different reuse shapes. Start with the consumer repository to
  determine which shape actually applies.
- [ReadAdmission](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/reads/ReadAdmission.java)
  and [AdmissionThrottle](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/pool/AdmissionThrottle.java)
  own work and connection admission respectively.
- [Flyway migrations](../../plowshare-server/src/main/resources/db/migration)
  own schema history. The [query rules](../../docs/coding-standards.md#query-efficiency-and-reuse)
  and [read capacity contract](../../docs/server-read-capacity.md) explain the boundaries.

## Rules

- Individual request-serving queries should normally finish within **500 ms** and
  use a configurable **2-second default completion limit**, including locks,
  execution and materialization. Statements inside one actual SQL transaction
  share its remaining budget. Separate transactions, cache coordination and
  provider/workflow stages do not share an invented two-second lifetime.
- On query expiry, cancel that SQL unit and roll back its active transaction.
  Retain earlier useful work and possible commit uncertainty; do not kill the
  entire job or replay prior effects. `ReadFailedException` carries query timeout
  or cancellation; `ReadUnavailableException` means real backpressure only.
  Whole-workflow duration is an isolated performance warning, never an exception.
  Other SQL needs an explicit finite budget appropriate to its maintenance work.
  Follow the [deadline rule and enforcement gap](../../docs/coding-standards.md#bound-sql-work-by-a-completion-deadline).
- Remove fetch-discard-refetch, N+1 reads and repeated collection/permission
  resolution at their owner. Pass already-loaded typed values where their scope
  and freshness allow. A repeated read needs an explicit concurrency reason.
- Authorize before counts, ranking and pagination can disclose protected facts.
  Preserve scope predicates, deterministic ordering and empty-result semantics.
- Cache keys include the complete selection and visibility identity required by
  the owner. Bound entries/bytes and concurrent fills; keep loaders, callbacks
  and future completion outside cache bookkeeping locks.
- Retain the original loader token with its value. Allocation counters are not
  commit order; pending/future writers, pruning and database identity matter.
  Never attach a fresh token to old facts and call them current.
- Notifications are change hints. Only the existing documented notification-trust
  protocol permits bounded reuse; it is not a general permission lease. Preserve
  reconnect, probe, expiry, transaction and final disclosure fences.
- Checked authority reads inside write transactions confirm per statement with
  the caller's MVCC/time semantics. Dirty or ineligible state takes its SQL path;
  SQL errors must not become an allow. Never publish uncommitted caller facts.
- Do not call providers/models or wait on an independent refresh while holding a
  database connection. Preserve the owning bounded refresh/admission protocol.
- Logical read admission and connection pressure have different failure contracts.
  `AdmissionThrottle` waits; it must not return `CAPACITY_EXHAUSTED` mid-handler,
  which could trigger replay after earlier statements already committed. Preserve
  the read owner's unavailable mapping without pretending the whole request was unstarted.
- Add new Flyway migrations; never edit shipped ones. After integrating main,
  check duplicate versions even when Git reports no conflict. A divergent live
  history requires an explicitly reviewed, rehearsed reconciliation; never blindly
  relabel/repair rows or falsify the record of what ran.

## Explicit SQL-unit scope

`SqlWorkBudget` is an explicit opt-in query/actual-transaction completion scope,
not a facade/workflow lifetime, grant or arbitrary SQL executor. Hooks, provider
IO and job admission stay outside it. Its pool
infrastructure decorates repository and independent authoritative-version leases;
the raw primary Hikari source, Flyway and LISTEN lifecycle stay intact. A shared
JdbcTemplate and transaction manager must retain the same decorated source
identity, including when ordinary pool admission is disabled.

An active scope shares the earlier actual SQL deadline with nested ReadDeadline
work and bounds acquisition, statement execution/materialization and owned
cleanup. Deadline/cancellation/closure use `ReadFailedException`; only occupied scope
capacity uses `ReadUnavailableException`. Logical timeout can be uncertain and
never supplies the outer CAPACITY_EXHAUSTED proof for mutation replay. Abort/close retains physical lease
and throttle occupancy until it settles; late alarms cannot target a recycled
lease or worker. Supported driver assumptions and exact test evidence belong in
the implementation report, not a claim of universal JDBC termination.

Projection-cache owners preserve authorized resident/trusted hits and join an
existing compatible shared load with caller-local cancellation. An active SQL
scope never elects a new shared fill: an actual miss uses its existing private
uncached loader path. This ownership boundary prevents cancellation of another
caller's fill and records the private-miss/query-count cost. Existing unscoped
inline loaders keep their original completion/coalescing contract. Reuse typed
facts and stored freshness tokens; do not bypass warm valid entries or refresh
unconditionally merely because this scope is active.

Scoped PostgreSQL leases additionally clamp server statement/lock limits before
commands, preserving earlier settings. Transaction-local limits follow Spring's
transaction; touched autocommit settings must be restored before pool return.
Control queries consume the same remaining budget (normally one per command,
plus restoration), and ordinary unscoped SQL gains no control queries. Commit and
possible autocommit commits refresh the server limit. Rollback cannot prepend
configuration SQL inside an aborted PostgreSQL transaction; it retains the live
network/watchdog deadline and owned physical abort/close path. Failed
reset discards the exact still-owned pool lease before close can recycle it.
Client expiry/socket closure alone does not prove backend cancellation; selected
PostgreSQL tests must check remote activity/row-lock release and clean reuse.
SQL watchdogs cancel only owned JDBC resources; they never inject an interrupt into
the enclosing workflow thread. Java cannot distinguish that bit from genuine
caller cancellation. Server statement/lock limits, driver network deadlines and
owned abort/close enforce the SQL boundary. Connection acquisition and a broken
driver can return after logical expiry; physical ownership remains until they
settle, and a late result is withheld. This is not a claim of instantaneous abort.
Genuine caller interruption is preserved and never cleared.

These contracts do not establish universal SQL cancellation for unscoped callers,
and SQL-only deadlines do not bound arbitrary hook/file loading or user callbacks.
See the [scoped design](../../docs/reviews/home-assistant-ex-sql-budget-design.md)
and independent delivery evidence before asserting that an entry point is wired
or verified.

## Legacy traps

Do not copy direct JDBC from an old service, treat a proposed operation-pipeline
document as an implemented API, or introduce a shared cache to hide expensive
cold SQL. Follow the current repository and its actual cache adoption points.
The explicit asynchronous projection-cache API wait budget is not universal SQL
cancellation: a loader can continue after its caller times out. Accepted inline
reads have no new workflow timer; only an existing caller SQL deadline applies. Trace and verify
the actual execution/cancellation path before claiming the deadline is enforced.

Collection log visibility can discard a successful tentative cached page after a
raced global version fence and evaluate one pure authoritative query within the
callback's SQL-unit budget, narrowed by an already active transaction deadline.
It never repeats SQL/cancellation failures or mutations.
Accepted inline cache hits, bypasses and coalesced loads create no hard workflow
timer. They honor a real caller SQL deadline only when one already exists; slow
cache work warns through the bounded performance observer instead of failing.

## Checks

Start with the affected repository/cache tests using mocks or fakes. Useful
examples are `ProjectionCandidateTest`, `SnapshotCacheConcurrencyTest` and
`BoundedReadAdmissionTest` under `io.aeyer.plowshare.server`.

Only when the assertion needs PostgreSQL, state why and select the owning tagged
test with `-PfullDb --tests`; migrations, MVCC, triggers and locking require real
evidence. Do not enable the entire database suite for a routine confidence check.

For a performance claim, record query/function counts, cardinality and SQL time
separately from queue/provider/client time. Use comparable measurement windows;
a synthetic hot-path speedup is not proof of whole-system p95 or capacity.
