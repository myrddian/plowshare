# CORE-03A implementation evidence

Implementer: `/root/ha_sql_budget_implementer`. Independent reviewer:
`/root/ha_sql_budget_reviewer`. Status: implementation, selected tests and final
formatting checks verified for the R1 correction. Independent re-review remains
pending. This report does not approve the deliverable or
claim Ex facade wiring.

## Delivered contract

`pool/SqlWorkBudget.within(Duration, Supplier<T>)` provides same-thread, nested
monotonic completion scopes without opening an outer transaction. Database
composition retains the raw primary Hikari bean and the exact shared template /
transaction-manager DataSource identity, including admission-disabled mode.
Independent authoritative projection-version leases receive the same scoped
instrumentation; independently owned LISTEN/background work does not inherit it.
Entering a new scope inside a preexisting transaction is refused, because its
already borrowed connection cannot safely be retroactively instrumented.

Connection, statement, metadata and result wrappers retain owned handle getters,
refuse vendor unwrap in scoped mode, clamp network timeouts before query/fetch
work, and reject expired results. Scope/query/watchdog/lease/cleanup registrations
are finite. Ex checkouts preserve existing nested throttle semantics. A private
admission-depth capability detaches the logical caller before asynchronous
physical cleanup; it retains its physical permit until successful close. Failed
close remains quarantined within the finite scope/lease bounds. Unscoped close,
unwrap, admission and cache-fill behavior retain their existing contracts.

`PostgresSqlSessionDeadlines` supplies server-side statement/lock cancellation,
using bound configuration queries and preserving earlier existing settings.
Transaction settings are local; touched autocommit settings restore before pool
return. Failed/uncertain restore evicts the exact still-owned Hikari lease before
outer close could recycle its proxy. Configuration, setup adjustment and restore
round trips consume remaining operation/query/cleanup allowances. No domain SQL
is replayed. A normal command adds one configuration query; a touched autocommit
lease adds one restore query. At most three setup attempts share the same query
budget. Per-fetch checks do not add configuration queries.

Expiration schedules lease cleanup separately from its bounded interruption
executor. A channel interruptor can block synchronously inside Thread.interrupt;
no interrupt runs under the scope monitor or on an alarm thread. A completion
fence prevents an executing/queued interrupt from reaching a caller's next task.
Physical cancellation ownership stays registered until it settles. This cannot
promise a hard physical stop for arbitrary callback/driver code.

Owned JDBC socket/server timeout evidence maps to `ARCHIVE_UNAVAILABLE`, retaining
uncertain-effect advice rather than the ancestor's false no-write assurance. No
CAPACITY replay path, empty-success substitution, hidden fallback or public API
is added. Genuine grammar/integrity failures retain their identity.

## Cache ownership and query reuse

`SnapshotCache.readExisting` atomically reuses a resident value or existing
compatible fill without electing a loader on miss. Ex joins wait only for their
own remaining budget and cancel only local interest. A scoped actual miss uses
the existing private uncached loader once; it neither installs a shared fill nor
cancels an ordinary reader's work. Stored-token confirmation paths use that same
bounded join. Trusted authorized hits remain reusable. Fixtures count zero new
loader calls for warm hits/joins and one per private cold request; private misses
sacrifice cross-caller coalescing and record the bounded `SQL_PRIVATE_MISS` event.
No unconditional refresh or fetch-discard-refetch was introduced.

## Verification

Earlier checkpoint: 78 selected non-database cases passed plus server formatting.
The first five-case tagged PostgreSQL run passed four and exposed a timeout
classification race. It also showed why socket-only cancellation was insufficient:
the disconnected backend retained an uncommitted row lock until `pg_sleep(2)`
finished. The scoped PostgreSQL control implementation and stronger backend / row
lock / settings-reuse assertions were added in response. Those historical runs
are not evidence for the final candidate.

Before the R1 correction, the canonical focused run passed **130 non-database cases across ten
classes**, including reserved interruption exhaustion/recovery, blocking
interruptor shutdown, exact reset eviction, failed-close permit/depth ownership,
definitive SQL defects racing expiry, and refreshed commit/autocommit command
limits without prepending setup to failed-transaction rollback. Production and
test compilation passed;
coordinator log: `/private/tmp/core03a-focused-current.log`. That candidate's six-scenario
PostgreSQL run passed with zero failures/errors (9s Gradle run; 1.399s in test
cases). Log:
`/private/tmp/core03a-postgres-current.log`. The transaction cancellation scenario
completed in 0.224s including rollback/reuse assertions, and verifies row-lock
release before the two-second backend sleep could finish. It also verifies actual
grammar/integrity rollback without losing the original error. Earlier-settings
restoration and unscoped reuse passed in 0.034s. Server Google formatting was
applied successfully. The separate final repository `formatCheck` passed in four
seconds (43 tasks): `/private/tmp/core03a-final-format-check.log`. The coordinator
saved the PostgreSQL XML under `/private/tmp/core03a-verification-evidence`.
Broader repository verification and independent approval remain coordinator-owned.
Actual PostgreSQL is justified only for backend command cancellation, row-lock release,
rollback, independent pool acquisition and clean post-scope settings/reuse. The
remaining service/cache/lifecycle behavior is tested with mocks or in-memory
fixtures.

## Limits

This is explicit opt-in enforcement for authorized generic Ex admission. It does
not rewrite legacy SQL or establish universal deadline enforcement for unscoped
operations. SQL budgets do not bound arbitrary hook/file/provider loading or CPU
callbacks. PostgreSQL server milliseconds, pgjdbc socket/cancel behavior and JVM
scheduling are practical boundaries, not hard real-time guarantees. A failed
physical close/interruptor consumes its finite retained ownership instead of
claiming release. Concurrent commit cancellation remains uncertain and must not
be replayed. CORE-03 still owns full-path wiring and whole-call acceptance review.

Each active scope registration reserves one interruption worker before caller
work or database acquisition. The finite worker maximum equals configured scope
capacity; workers start lazily on expiry and time out when idle. A registration
cannot retire until its interruption has physically returned or been proven
ineligible to run. When every reservation is held by blocked interruptors, new
scopes fail unavailable before checkout; they do not queue behind those owners.
The bounded executor queue handles only dispatch bookkeeping, not admission of
additional unreserved scopes. A deterministic fixture exercises complete
interrupt-capacity exhaustion, no-checkout refusal and subsequent recovery.

Commit and a transition to autocommit=true refresh the server command limit using
that transaction command's remaining query/operation budget before it can commit.
Rollback deliberately does not prepend configuration SQL: PostgreSQL rejects
SELECT/SET inside an already-aborted transaction. It retains the live remaining
network/watchdog deadline and owned abort/close path. The selected transaction
scenario also executes genuine grammar and duplicate-key failures, proving that
normal rollback preserves the original error, removes partial writes and permits
clean reuse. A commit whose response races expiry remains uncertain; none of
these limits authorize replay or claim that a caller timeout proves remote abort.

## CORE03A-R1 review correction

The v1 reviewer reproduced alarm coupling with two deliberately blocked
setNetworkTimeout calls: each setter held its lease fence while synchronous
retirement tried to acquire that fence on an alarm thread. A third cooperative
scope then missed its own deadline. Earlier passing tests did not exercise that
structural coupling.

Logical lease retirement now uses one atomic claim and enqueues its single
bounded physical-cleanup task without acquiring the driver-held fence. The
cleanup worker acquires the original fence before touching a setter's connection
or returning its handle. Thus alarms and bounded shutdown dispatch remain free,
while an in-flight setter still prevents abort/close/reuse until it settles.
No raw driver/caller-depth callback runs on that alarm retirement path. Explicit
caller close and scope finish detach logical throttle nesting; the worker also
detaches it before physical cleanup. Retained leases, exact eviction and reserved
interruption capacity keep their existing bounds and uncertainty rules.

A two-branch barrier regression blocks both setters, observes both logical
expirations, and enforces an unrelated 80ms scope before releasing either setter.
It checks no premature abort/close, retained physical occupancy, bounded shutdown
in one branch, physical settlement after release, and safe reuse of the same fake
pool handle under a recovered owner.

Current R1 verification passed **154 non-database invocations across eleven
classes** (132 owning cases and 22 Faults cases), including both blocked-setter
regression branches. Log: `/private/tmp/core03a-v2-focused-check.log` (19s).
The six selected PostgreSQL scenarios also passed with zero failures/errors,
retaining the backend/row-lock, rollback, acquisition, materialization and clean
settings/reuse evidence: `/private/tmp/core03a-v2-postgres-check.log` (9s).
Global `./gradlew format` passed, followed by a separate successful
`./gradlew formatCheck`: `/private/tmp/core03a-v2-format-check.log`. The first
sandbox attempt could not access the wrapper cache; the coordinator's authorized
rerun exited successfully. The v1 results above are historical, not acceptance
of this correction, and R1 still requires independent re-review.
