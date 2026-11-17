# CORE-02 implementation evidence

Implementer: `/root/ha_core_storage_implementer`. Date: 17 November 2026.
Scope: the storage slice of [ADR 0077](../decisions/0077-conversation-external-aliases.md).
Status: **READY FOR REVIEW, v3**. Current server formatting and all 20 owning
ConversationAliasRepositoryTest cases passed after the configured-zero correction.
Unchanged guard and PostgreSQL evidence is carried explicitly from v2 below.
Independent review and final candidate hashes remain
coordinator-owned. No Ex facade, public request DTO or SDK operation
is implemented by this node.

## Delivered contract and persistence

`ConversationAliasRepository` accepts a strictly validated positive project ID,
nonzero authority account ID, account-local source label and opaque external ID.
It exposes atomic get-or-open and resolve-only operations, with no public reset,
rebind, alias enumeration or import API. Opening choices are immutable snapshots;
each newly created root gets a fresh ordinary Budget identity even when callers
reuse one Opening for different keys. Existing mappings reuse their current
canonical target snapshot and never change spending, limits, history or lifecycle.

CORE-03 integration identified an ordinary policy compatibility gap: the configured
conversation default may be zero (inference disabled), while explicit request
`maxModelCalls: 0` is refused. Opening now accepts the already-resolved nonnegative
ordinary policy and calls the existing SpendingPolicy budget factory for every
new allowance. Zero therefore creates a fresh capped zero-call budget, never an
unlimited budget; reusing creation choices still produces distinct identities.
The public DTOs and RequestedBudget's explicit positive-limit validation are
unchanged. A focused value test covers configured-default admission, disabled
spending, distinct identities, explicit-zero refusal and negative internal bounds.
Its focused verification passed; independent reapproval remains required. No SQL, migration,
transaction/deadline code or persistence test was changed by this correction.

`ConversationCreation` is the narrow ordinary storage capability implemented by
the existing ConversationStore. Alias creation calls that owner inside the same
Spring transaction; it adds no second conversation or allowance INSERT recipe.
The repository returns after transaction completion and connection cleanup. Its
internal created disposition lets CORE-03 call log.open only for a committed new
root, outside the transaction. No hook/model/provider/external service callback is invoked.

V274 creates `conversation_id_map` with a complete-key primary key, explicit C
collation, strict input CHECKs, project/account namespace FKs, target lookup index,
ordinary-root/project/owner agreement trigger and rebind/root-movement protection.
A transaction-scoped advisory lock over a SHA-256-derived full-key identity
serializes first opens before ordinary creation. Hash collisions only delay two
otherwise different keys; the database compares the complete key independently.
A failed insert rolls the root and its canonical allowance back with the alias.

CORE02-R1 identified that a pool default of REPEATABLE READ or SERIALIZABLE could
retain the scope query's snapshot across the advisory-lock wait. The losing opener
would then fail to observe the winner and attempt another insertion. The repository
now explicitly uses READ COMMITTED for its owned transaction, so the post-wait
lookup receives a fresh committed snapshot. Existing outer transactions remain
refused; this does not lower an enclosing caller transaction's isolation. Ordinary
creation and current authority checks join the same per-statement snapshots;
existing SHARE-lock identity fences and live authorization checks remain unchanged.
The controlled PostgreSQL regression starts four openers from pools defaulting to
REPEATABLE READ and SERIALIZABLE, observes all three waiters blocked before the
first creator can commit, then requires all four to return the same root. It also
checks restoration of the pool's original isolation after cleanup. Both cases
passed the coordinator's v2 PostgreSQL verification.

Target protection is deferred NO ACTION, checked at commit. A direct target delete
is refused. An explicit maintenance transaction can remove its roots and project
namespace together without cascade-order dependence. Existing conversation-to-
project FKs already prevent deleting a populated project by deleting only its
project row; this change supplies no new hard-purge API. Account deletion removes
the alias namespace and leaves the conversation history retained. Ordinary
lifecycle/ejection does not remove or recycle aliases.

## Authorization and reuse boundaries

The repository key is an identifier, never authority. CORE-03 must derive the
account from current authentication, authorize the explicit project before alias
lookup and check current target authority before disclosure or run admission.
The repository additionally verifies current project/account rows, refuses
built-in/disabled accounts and contained/restricted scopes, and holds SHARE locks
on the selected project/account rows through the transaction. This prevents a
rename/delete from changing the meaning of the resolved project ID while ordinary
creation confirms the same Home. No application/domain caller receives JDBC.

Alias lookup reads only the stored target ID. The ordinary owner then hydrates one
target snapshot, which is retained and returned rather than discarded and fetched
again. It verifies the expected project and ordinary TURN-root shape. Lifecycle
is retained internally, including archived/ejected roots; ordinary ConversationView
remains unchanged. Actual run lifecycle/exclusion/budget checks belong to CORE-03's
ordinary admission path. No cache or authorization lease was introduced.

## Deadline ownership and metrics

`plowshare.conversation-aliases.timeout` defaults to two seconds; `query-timeout`
defaults to one second and cannot exceed one second. The repository inherits an
earlier enclosing ReadDeadline and refuses an existing outer transaction so its
commit boundary cannot silently move beyond the facade's post-commit handling.

The caller-thread guard spans transaction acquisition, SQL groups, materialization
and commit. Its watchdog interrupts the owner during interruptible admission/pool
acquisition, and aborts a registered still-owned JDBC connection on expiry.
Transaction afterCompletion detaches the handle before pool cleanup can recycle
it; stale query alarms are fenced by tokens and cannot abort another query or
borrower. Pre-existing/caller/deadline interrupts are never cleared. A late commit
does not become success; failure preserves uncertainty and never triggers replay.

Shutdown fences new registration, cancels every still-registered scope before
discarding scheduler alarms and then drains the watchdog executor with a finite
500 ms wait. An already detached/completed scope cannot abort a reused connection.
The shutdown correction and its focused regression passed the coordinator's
final verification; independent approval is still required.

Real-driver cancellation exposed a second race: Spring can replace a statement
timeout with its rollback failure after an interrupted/aborted socket closes.
The alias boundary preserves typed unavailable/uncertain failure when its guard
has expired or rollback retains an application cancellation (SQLSTATE 57014 or
55P03). Unrelated transaction/business failures retain their original type; the
global archive exception mapper is unchanged. Deterministic mock regressions
cover expired guard, server-timeout-before-watchdog and unrelated failure. The
selected blocked-lock PostgreSQL case now repeats five times to exercise that
actual driver race, with no retries of application work.

Each private SQL group includes timeout configuration plus the whole selected
operation. The ordinary_create and ordinary_hydrate groups also contain the
ordinary owner's nested statements and result materialization. Their aggregate
one-second ceiling is stricter than giving each statement a new second. Transaction-
local statement/lock timeouts and driver network timeouts supplement the watchdog.
These metrics describe bounded groups, not individual SQL statement timings:

- `plowshare.conversation.alias.sql.operation`: whole operation duration histogram.
- `plowshare.conversation.alias.sql.group`: group duration histogram, including
  configuration, execution and materialization.
- `plowshare.conversation.alias.sql.slow`: 500 ms and one-second group breaches.
- `plowshare.conversation.alias.sql.cancellations`: expiry cancellation attempts.

Labels are fixed operation/group/threshold names. Prompts, external identifiers,
account/project identities and credential values are not labels or log messages.
Existing pool statement metrics still have their documented execute-only scope.

The physical cancellation assumption is PostgreSQL-specific, not a claim about
arbitrary JDBC drivers. The pinned PostgreSQL 42.7.4 source's PgConnection.abort
delegates AbortCommand to QueryExecutorCloseAction.abort, which atomically detaches
the stream and closes its socket without sending SQL or waiting for a result drain.
The guard holds its ownership fence until that driver abort settles; it does not
claim safety for a replacement driver whose abort blocks indefinitely. Network
timeout and selected real-driver tests provide additional evidence. A JVM timer
cannot safely preempt arbitrary non-cooperative Java code; the composition uses
the fixed ordinary repository owner, not caller-provided callbacks.

The alias guard starts at repository entry. Existing credential/project admission
performed before that point is not newly bounded by this slice. CORE-03 must open
the shared deadline before its own admission steps and preserve remaining time.
The existing wider pool/admission/read paths and their documented enforcement
gaps are unchanged; this node does not claim universal SQL cancellation.

## Verification performed

Root serialized formatting and checks, with server edits paused:

- On v3, the server formatter, all 20 owning ConversationAliasRepositoryTest cases
  and server spotlessCheck passed in the coordinator's eight-second run. The new
  configured-zero regression passed with the other owning repository rules. No
  database rerun was needed for this immutable-value/factory correction.
- The server formatter, server spotlessCheck and focused ConversationAliasRepositoryTest /
  ConversationAliasSqlDeadlineTest passed on v2: 26 test cases,
  including all lifecycle
  states, exact snapshot reuse, resolve miss, incompatible/missing target,
  namespace refusal, strict identifiers, inherited deadline, bounded ordinary
  creation/hydration, cancellation/abort failure, detached/reused handle safety,
  stale query alarms, caller interruption, shutdown cancellation/new-work refusal,
  rollback overriding deadline/server cancellation and unrelated rollback failures.
  The current scoped checks passed after the isolation correction.
- Selected `-PfullDb` ConversationAliasesDatabaseTest passed on v2: 13 invocations
  across eight scenarios (the blocked-lock case repeats five times and the new
  isolation case covers REPEATABLE READ and SERIALIZABLE), zero
  failures/errors/skips, using PostgreSQL 16 and Flyway V274. PostgreSQL was
  necessary for complete-key uniqueness, concurrent atomic first-open, canonical
  allowance isolation, rollback after final insert failure, actual CHECK/trigger/FK
  behavior, namespace purge, blocked-lock cancellation and exhausted-pool acquisition.
  The controlled isolation cases establish that post-wait lookup observes the
  winner and that cleanup restores each non-default pool isolation.
  This final real-driver run passed after the rollback-classification correction;
  the earlier race failure is resolved rather than counted as a pass.

The v3 correction changes only ConversationAliasRepository.java, its owning test
and this report. Compared with the v2 file manifest, the ordinary creation/store,
JDBC alias repository, deadline guard, configuration/properties, migration,
seven-case guard test and 13-invocation PostgreSQL test are byte-identical. Their
v2 evidence is retained as prior unchanged-path proof, not described as a v3 rerun.

Root serialized these checks with Java edits held. Repository-wide check also
passed on v1 before CORE02-R1's isolation correction, as did global formatCheck and
37 HA fixture tests; those earlier results are not proof of the new revision or a
live integration. Current repository-wide validation and future facade/SDK
validation remain later delivery evidence, not results claimed by this storage slice.

Mocks do not prove live integration. No live HA/server installation, model call,
device action, production migration, merge or deployment was performed. The next
node implements public facades and admission integration after independent approval.

## Authored files

- `plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/ConversationCreation.java`
- `plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/ConversationAliasRepository.java`
- `plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/ConversationAliasProperties.java`
- `plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/ConversationAliasConfiguration.java`
- `plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/ConversationAliasSqlDeadline.java`
- `plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/JdbcConversationAliasRepository.java`
- `plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/ConversationStore.java`
  (implements the ordinary creation interface only).
- `plowshare-server/src/main/resources/db/migration/V274__conversation_external_aliases.sql`
- `plowshare-server/src/test/java/io/aeyer/plowshare/server/archive/ConversationAliasRepositoryTest.java`
- `plowshare-server/src/test/java/io/aeyer/plowshare/server/archive/ConversationAliasSqlDeadlineTest.java`
- `plowshare-server/src/test/java/io/aeyer/plowshare/server/archive/ConversationAliasesDatabaseTest.java`
- This implementation evidence report.
