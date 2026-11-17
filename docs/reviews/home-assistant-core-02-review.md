# CORE-02 independent storage review

Reviewer: `/root/ha_core_storage_reviewer`.
Implementer: `/root/ha_core_storage_implementer`.
Candidate: `ha-core-02-v1`.
Manifest: `docs/reviews/home-assistant-core-02-v1-candidate.json`.
Manifest SHA-256: `a71f72cc4317ce185c4560066fcb141af293c2855baf67bbbd25f9e253f3cf8e`.
Verdict: **CHANGES_REQUIRED**.

## Scope and checks

Independently verified the manifest digest, all 13 bound file/snapshot hashes and
frozen tracked-diff hash. Reviewed the alias contract, configuration, properties,
private cancellation owner, JDBC repository, V274 migration, ordinary
ConversationStore creation/hydration and allowance binding, pool/transaction
composition, mock/guard tests and selected PostgreSQL tests against CORE-02 and
ADR 0077. The ordinary store change only adds the narrow creation interface;
conversation and allowance INSERT ownership remains with that existing store.

The recorded focused check contains 26 alias mock/guard and 37 HA passing cases.
The saved actual PostgreSQL JUnit result contains 11 invocations across seven
scenarios, zero failures/errors/skips, including five bounded repetitions of the
lock/rollback cancellation race. Its fixtures use PostgreSQL 16 and Flyway, not a
mock database. Verified the root's final repository check log: `check` passed on
this frozen v1 source in 3m31s, with 141 tasks and no full-db flag. Formatting and
focused/database evidence are consistent with the frozen source hashes. No
Gradle, database, live, device or model operation was started by this reviewer.

The complete key, source/ID validation, immutable creation choices and fresh
allowance identity, shared transaction, advisory-lock partition, rollback of a
failed final insert, target shape/lifecycle checks, no automatic retry, typed
snapshot reuse and deferred target-deletion constraint match the storage slice.
Keys confer no authority; current project/target admission remains a required
CORE-03 responsibility before lookup/disclosure. The report correctly distinguishes
that later work from implemented storage.

Cancellation inspection covers acquisition before connection registration,
statement/materialization groups, token-fenced query alarms, afterCompletion
connection detachment before pool cleanup, late-result refusal, caller interrupt
preservation, shutdown registration/cancellation fencing and rollback overriding
cancellation. Fixed metric labels do not include external identities. The
PostgreSQL-specific abort assumption, aggregate-group rather than per-statement
metrics, pre-existing project purge constraint and wider admission/deadline gaps
are disclosed accurately. These checks establish neither facade/SDK behavior nor
production deployment.

## Required correction

**CORE02-R1 — P2: Pin the isolation required by concurrent get-or-open.**

Location: `JdbcConversationAliasRepository.java:58–61` (transaction template
construction), and its `scope`/advisory-lock/target-lookup ordering.

The private TransactionTemplate sets a timeout but retains ISOLATION_DEFAULT.
PoolAdmissionConfiguration composes the transaction manager over the configured
Hikari source without guaranteeing READ COMMITTED. The connection/database default
can therefore be REPEATABLE READ or SERIALIZABLE.

Each open reads `scope` before waiting for its full-key advisory lock. Under
REPEATABLE READ, that first SELECT establishes the transaction snapshot. A second
opener waiting behind the winner retains the pre-commit snapshot after acquiring
the advisory lock, so its target lookup cannot see the winner's alias. It creates
another ordinary root/allowance and reaches the primary-key conflict instead of
returning the already-created root. Rollback prevents an orphan, but the promised
serialized get-or-open operation no longer has the specified successful existing
mapping path. SERIALIZABLE can similarly refuse the stale transaction. Current
concurrency tests use the default PostgreSQL READ COMMITTED setting and do not
exercise this inherited-isolation condition.

This is a source-confirmed transaction-contract gap; this reviewer did not run a
new database reproduction or claim that the default-isolation tests failed. The
repository explicitly owns and rejects an enclosing transaction, so it can set
READ COMMITTED on its own TransactionTemplate and document why the lookup after
lock acquisition needs a fresh statement snapshot. A clear fail-fast isolation
restriction is another possible contract, but silently inheriting an incompatible
setting is not.

Add the narrow regression needed to establish the selected contract. Prefer a
selected tagged PostgreSQL case with a pool/database default of REPEATABLE READ
(and SERIALIZABLE if separately necessary), concurrent first opens and evidence
that both return the same committed root with one allowance. A controlled lock or
barrier should ensure the losing transaction starts before the winner commits;
a timing-only race is insufficient. Verify the repository's explicit transaction
isolation and that normal authority/current-statement semantics remain intact.
Retain all existing rollback/cancellation checks and coordinate the smallest
necessary database run with root.

Disposition: **open**. Implementer correction and a new exact candidate are
required before approval. No other blocking findings were identified.
