# Scoped SQL completion for the Ex facades

Proposal for `CORE-03A`; implementer `/root/ha_sql_budget_implementer`.
17 November 2026. Design only: no runtime implementation or new acceptance is
claimed. Independent plan review must precede dispatch of its code deliverable.

## Need and scope

The separately authorized generic Ex facades must obey the repository's SQL
completion policy. Their current `ReadDeadline.within` establishes a logical
deadline, but does not cancel every database wait before or after alias lookup.
This is a necessary internal implementation dependency of those facades, not a
new Home Assistant capability, prompt API or authorization policy.

`JdbcIdentityRepository.account/project` use the shared JdbcTemplate directly.
`CallerAccess.requireSession/requireWork/requireProject` can perform live SQL;
the Ex service invokes them before entering alias storage and after its commit.
Ordinary run admission and synchronous `HookedLogStages.opened` can also use SQL.
The private alias watchdog only covers its own repository transaction.

The proposed primitive applies when the Ex service explicitly opens a scope.
Unscoped operations retain their existing behavior and are not declared bounded
by this change. There is no blanket repository rewrite, new SQL access from
domain services, new transport operation, permission shortcut or schema change.
If source inspection shows that a required Ex path cannot use this scope without
changing an independently owned background lifecycle, report that exact gap;
do not extend scope or claim success silently.

## Owning sources and composition

- `pool/PoolAdmissionConfiguration.java` deliberately keeps the primary
  HikariDataSource raw. Projection-pool cloning, Flyway and health depend on it.
  Decorate the repository connection holder, not the primary DataSource bean.
  Shared JdbcTemplate and JdbcTransactionManager must receive the **same**
  decorated source identity so transaction-bound calls do not borrow again.
- `pool/ThrottledDataSource.java` owns WAIT-only admission, interrupt handling and
  permit release on physical connection close. Its wait precedes Hikari's
  five-second default acquisition timer. Preserve these owners and never turn
  an inner timeout into replayable `CAPACITY_EXHAUSTED`.
- `pool/TracedDataSource.java` is an existing JDBC proxy precedent, but times only
  execute calls. Its spans do not establish completion through result iteration.
- `cache/ProjectionCacheConfiguration.java` supplies independent authoritative
  version leases to `JdbcProjectionVersions`. Those same-thread probes also need
  the scoped decorator; wrapping only the main repository pool leaves a gap.
  Preserve independent autocommit/READ COMMITTED freshness semantics and keep
  LISTEN sessions outside this request scope.
- `reads/ReadDeadline.java` is thread-confined. SnapshotCache.readInline explicitly
  lets the elected shared loader finish even after its caller's deadline, and
  caller-local cancellation must not cancel another reader. Merely running that
  loader on the Ex thread does not transfer ownership to this request. A scoped
  watchdog must not abort an existing or newly elected shared cache fill.
- `archive/JdbcConversationAliasRepository.java` owns its READ COMMITTED
  transaction, post-commit return and private cancellation fence. Keep that
  owner; nested timers must inherit the earlier deadline and cancellation must
  remain safe if both scopes observe the same connection.

Configuration must also cover `pool-admission.enabled=false`: repositories still
need the scoped wrapper while ordinary unscoped behavior and Boot's JDBC template
settings/transaction-manager customizers remain intact. Do not add a second
DataSource bean that disables Boot's primary-pool creation.

For the Ex path, add an explicit caller-owned **miss** mode at the projection-cache
owner. Do not infer it from ReadDeadline alone: ordinary cached reads already use
ReadDeadline and must remain unchanged. Preserve existing trusted/resident hits
with their original authorization/freshness evidence and typed values. Do not
refresh or bypass warm authorized cache entries unconditionally.

At the existing shared-load decision, provide an atomic cache-owner operation
(proposed `readExisting(Request)`) that returns a resident read, joins an existing
compatible load, or reports a miss **without electing a new loader**. Preserve
existing waiter limits, dependency/version checks and reentrant-load refusal.
An Ex joiner waits only for its remaining deadline and cancels only its local
interest; no Ex watchdog may abort the other owner's connections. On an actual
miss, invoke the existing uncached-loader path once under this request's SQL
scope, without registering a shared fill or replaying a failed load. Ordinary
unscoped `readInline` remains unchanged. Trace candidate/confirmation APIs as
well as read entry points so no Ex-elected shared fill is overlooked.

Private misses sacrifice shared-fill coalescing for that request; record the
query-count/latency cost and retain already authorized typed facts within the
operation. This is a necessary ownership tradeoff, not a cache-performance
improvement claim or permission for fetch-discard-refetch. Do not propagate a
request deadline to a shared loader or unrelated executor, and do not weaken
current authority/eligibility semantics to retain a cache hit.

## Narrow capability and lifecycle

Define an internal `SqlWorkBudget` interface, injected into the Ex service. Its
`within(Duration, Supplier<T>)` executes a typed caller action on the **same
thread**. It exposes no JDBC, SQL, table or query callback API. This is a budget
scope, not a generic SQL executor; repositories remain persistence owners.
The database/pool implementation and composition alone handle JDBC machinery.

1. Enter before the first session/authority/identity lookup; retain the scope
   through post-target checks, synchronous log-open SQL and normal run admission.
   Use one monotonic deadline, the earliest of the explicit finite budget,
   existing ReadDeadline and enclosing SQL scope. The Ex default remains two
   seconds; retries and nested scopes never restart it.
2. The scope creates **no transaction** and does not reserve a connection while
   hooks, file/provider operations or job scheduling run. Each actual checkout
   is accounted for until its owning close/cleanup physically settles.
3. Bound scope registrations, owned leases, cancellation tasks and scheduler
   queues explicitly. Nested same-thread scopes reuse the enclosing owner.
   Exhaustion/expiry is unavailable, not an allow, empty lookup or internal
   capacity/refusal signal that could authorize replay.
4. A watchdog covers pacing/semaphore/Hikari acquisition before a lease exists.
   It interrupts only the still-registered owner. A late acquired connection
   is aborted/closed before handoff, never published after expiry. Closing the
   scope fences a late alarm so it cannot interrupt a reused worker thread.
   Preserve a caller's existing/external interrupt; do not clear Java's shared
   interrupt bit to manufacture successful continuation.
5. During an active scope, owned Connection/Statement/ResultSet wrappers attach
   cancellation to the actual borrowed lease. Each statement has the earlier
   of one second, the remaining operation budget and any enclosing query's
   remaining budget. Cover execution, lock waits and result materialization,
   including getters/iteration, not just executeQuery returning a ResultSet.
   Reject late results before publication. Normal query latency remains below
   500 ms; a deadline is not an acceptable target latency.
6. Clamp driver/network deadlines to remaining time and use a guarded physical
   cancel/abort path for expiry. Track commit/rollback/cleanup under the same
   remaining operation budget; if cancellation races commit, preserve uncertainty.
   Spring remains transaction owner. Closing a wrapper must atomically retire
   its cancellation ownership **before** delegate close can recycle that lease.
   Abort itself does not release the underlying throttle permit.
7. Nested same-thread borrows retain existing throttle accounting. Scope bounds
   must include transaction-bound and independent projection leases; there must
   be no unbounded new work queue. Statement.getConnection, ResultSet.getStatement
   and metadata/unwrap paths cannot leak an uninstrumented scoped lease. Audit
   vendor unwrap requirements on the Ex call graph; support them safely or refuse
   explicitly. Unscoped unwrap/diagnostic behavior stays compatible.
8. Preserve error identity unless actual budget/cancellation evidence requires
   the existing unavailable outcome. A timeout must never hide a SQL grammar or
   integrity defect merely because it happened near a deadline. Rollback failure
   must not erase known cancellation/uncertain-effect evidence. No automatic
   retry, fallback identity or replay of open/run/hook work is introduced.
9. Cleanup has its own explicit finite budget and bounded physical-work owner.
   Do not release a slot or schedule replacement work while its abort/close is
   still running. Shutdown rejects new scopes, cancels admitted work before
   stopping watchdogs and fences late cleanup against pool reuse. Document the
   supported PostgreSQL driver cancellation assumptions; a Future timeout is
   not proof that arbitrary blocking driver code has physically terminated.

Do not allow JDBC handles or lazy result streams to escape a completed scope.
No database work is attached to an accepted background agent job by inheriting
the caller's ThreadLocal. That job retains its existing stage/lifecycle owners.

## Metrics and evidence

Publish operation/acquisition/query-through-materialization/transaction/cleanup
duration distributions, 500 ms and one-second query breaches, cancellations,
late-result refusals and retained physical occupancy. Labels are bounded fixed
categories/pool names, never SQL/binds, account/project/source identifiers or
prompt text. Keep existing execution-only tracing metrics truthful and separate.

Default tests use fake clocks, mocked JDBC leases and deterministic barriers:
nesting, expired entry, admission interruption, late acquire, statement/result
expiry, statement/getter/unwrap escapes, transaction reuse, close-versus-abort,
pre-existing interrupt, queue/owner bounds and shutdown. Prove unscoped calls
delegate normally and the raw primary Hikari/pool metrics/configuration survive
both admission-enabled and disabled composition. Existing authority/cache tests
must remain valid; no stale cache value or failed SQL can become an allow.
Prove warm authorized hits remain reusable, Ex-private misses start no shared
fill, a joining Ex read cannot cancel an ordinary fill/joiner, and uncached
misses preserve current authority semantics. Measure the miss/query-count cost.

Selected tagged PostgreSQL tests are justified for actual lock cancellation,
slow result materialization/streaming, rollback, uncertain commit handling where
observable, main/projection-pool acquisition and post-cancel pool reuse. State
the specific database reason and select the smallest classes/methods with
`-PfullDb --tests`; do not run a broad database suite. Prove pre-alias identity/
authority SQL, post-alias checks and nested alias transactions share one budget.

## Whole-call completion is still a distinct obligation

SQL cancellation does not bound arbitrary file access, hook loading, user
callbacks or CPU work which ignores cancellation. `hooks/Deadline.java` explicitly
bounds stage execution separately from loading. CORE-03 must inspect those
owning phases, inherit remaining finite budgets where supported, and test them
before promising a two-second active acceptance result. It must not silently
skip log.open, move it to a new asynchronous lifecycle or mark a shared hook
file broken because one caller ran out of time. If a required owning capability
is missing, report it as an unresolved prerequisite for CORE-03 closure.

Approval of this design/graph is not evidence that any deadline is implemented.
CORE-03A closes only after its exact implementation passes its independent
review. CORE-03 then integrates that accepted capability, verifies the complete
facade path and obtains its own independent approval.

## Implementation decision: scoped PostgreSQL command limits

The first selected PostgreSQL exercise exposed a concrete distinction: a client
socket timeout returned unavailable while the disconnected backend still finished
`pg_sleep(2)` before releasing its transaction's row lock. Client expiry alone is
not backend cancellation evidence. The coordinator authorized the following
narrow database-infrastructure completion of CORE-03A on 17 November.

A scoped lease lazily captures PostgreSQL `statement_timeout` and `lock_timeout`
and clamps both immediately before a command, preserving an earlier existing
limit. Configuration SQL uses bound parameters and the same query/operation
remaining budget, socket clamp and watchdog as the domain command. Transaction
settings use transaction-local `set_config`; Spring retains commit/rollback
ownership. Autocommit settings are restored before returning the lease, under
the earlier operation remainder and finite cleanup allowance. Failed or uncertain
configuration/reset discards the exact owned Hikari lease before outer close can
recycle its proxy. The private pool-capture seam is infrastructure-only; no domain
or repository contract receives a raw connection or new SQL escape hatch.

The usual cost is one control query before a domain command and one restoration
query for a touched autocommit lease. At most three clamping attempts share the
same query budget; they compensate measured setup latency, not a fresh deadline
or replay of the domain command. Fetch/getter calls do not repeat the control
query: they retain the command's materialization timer and refresh the network
clamp to remaining time. Private projection misses still invoke their loader
once per caller; resident hits and joins elect no new loader. Ordinary unscoped
queries perform none of this additional SQL.

This implementation targets PostgreSQL 16 and the repository's pgjdbc driver.
PostgreSQL server timeout granularity and JVM/network scheduling are not a hard
real-time guarantee. A socket timeout may beat the server response; its owned
JDBC timeout evidence maps to unavailable with explicit uncertain-effect advice.
Exact grammar/integrity defects observed at owned JDBC boundaries keep their
identity even when their response races expiry. Ordinary application failures
are not guessed from arbitrary cause chains; an actually expired/cancelled
operation still returns unavailable. Selected tests
must prove backend activity/row-lock release and clean settings on unscoped pool
reuse, in addition to logical reply timing.

Thread interruption itself can synchronously invoke a blocking channel
interruptor. Scope expiry therefore records ownership under its monitor, schedules
lease cleanup separately, and sends interruption through a separate bounded
executor. It never interrupts under the scope monitor or on a watchdog thread.
A completion fence prevents an executing/queued interrupt from reaching the
caller's next task; a blocking interruptor retains its physical scope/worker
rather than establishing successful cancellation. Arbitrary callbacks and
defective drivers still cannot be promised a hard two-second physical stop.

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
