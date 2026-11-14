# Query efficiency deployment follow-ups

These changes address findings from the deployment of `b586dc3a`. They cover SQL,
provider dispatch, client authentication, worker shutdown and Manual publication.
The cache remains an additional layer; it cannot repair an expensive cold query,
an unnecessary provider call or an incorrectly published source.

## Legacy document and retrieval reads

`DocumentStore` collection reads now use the same statement-local
`knowledge_readable_selection` relation as the catalogue. Permission selection
precedes counting, ranking and pagination. Typed-content, exclusion, report,
account, scope, retained-authority and vector-generation predicates remain in the
query. Exact document/source-name reads retain narrow permission checks.

`RetrievalService.retrieve`, used by `information.search`, checks for an eligible
passage before submitting an embedding. The gate uses the actual document
selection and, with dual embeddings, one captured profile. An empty gate passes
through the current disclosure/profile fences without creating an inference
source or spending a claim. A nonempty gate grants no read lease: provider
dispatch, accounting and the final authorization fence still run normally.

A bounded, read-only PostgreSQL comparison against the deployed data found the
following warm execution times. These are query measurements, not client or
provider latency guarantees:

| Query shape | Per-row authority | Batched authority |
| --- | ---: | ---: |
| Document total | 666 ms | 6.2 ms |
| Retrieval existence gate | 657 ms | 6.6 ms |

The total query matches the legacy list count. The existence comparison applies
the old and new authority predicates to the proposed gate; it does not represent
the previous complete ranked search. Shared buffer hits fell from 36,478 to 978
for both shapes. Plans, account parameters and operational dumps remain private.
Nonempty search can still require provider work and must be measured separately.

## Reconnect and rotating credentials

Socket and file-channel bootstrap request a ticket using the saved access token.
Only an explicit ticket HTTP 401 permits one coordinated renewal. The credential
owner reloads under its existing account/server lock and reuses a newer stored
pair when another client already renewed the rejected access token.

The native authentication transport refuses redirects and never retries. It can
attest that DNS resolution or connection refusal prevented submission, allowing
the credential owner to retain its saved pair. A timeout, reset, abort, malformed
response or arbitrary custom transport error preserves the interrupted-renewal
fence and requires sign-in. An uncertain rotating refresh is never replayed.

## Shutdown ordering and recovery

Information workers drain in Spring's lifecycle stop phase before datasource
destruction. Information, embedding and retrieval workers stop concurrently, each
allowing ten seconds for its current pass and five seconds after forced
cancellation. Terminal close prevents a destruction fallback from repeating that
deadline. Concurrent lifecycle callbacks share the owning drain's completion;
workers remain running for Spring's purposes until that drain finishes. Failures
to terminate remain visible; durable leases and outboxes own recovery after restart.

File-channel session-close listeners are captured after singleton initialization.
Socket cleanup uses the immutable snapshot instead of resolving Spring beans
while the application context is closing.

## Manual publication

The installer distinguishes private Personal installation from generally
available publication. A Global or explicitly shared installation must verify
chapter text through `global_read` before marking it complete or excluding its
previous edition. A successful administrative account read alone is insufficient.
Private installation no longer reports that Help is ready. Existing Personal
sources are not automatically shared; publish the intended checked-in chapters
through the installer's documented Global/shared workflow.

## Targeted log readability and job listings

On a deployed `b630d96b` installation, one PostgreSQL backend stayed saturated.
Sampling active statements attributed most backend time to permission checks
rather than data reads: `service_project_role`, `information_log_readable` and the
per-revision catalogue `information_readable` check. Over one minute the database
answered 341 transactions per second, with about 3,200 `admins` lookups per second.
Neither cache version domain changed during that minute.

Two separate causes were involved:

- **JIT compilation.** V235 redefined `information_log_readable` and
  `information_readable_input_selections` with `CREATE OR REPLACE` and no `SET`
  clause, so neither carried the function-local `jit=off` that V196 gave the
  collection routines. With JIT, one check over a log with 82 retained inputs took
  1,983 ms; without it, 47 ms. For a single retained revision, compilation was
  78 ms of 89 ms. V240 restores the setting on both routines only.
  `service_project_role` and the catalogue check timed the same with JIT on or off.
  `FunctionJitSettingsTest` replays the migrations and fails when a later
  redefinition drops the setting.
- **Repeated questions.** Targeted log checks bypassed the account's deny set
  (`information_unreadable_logs`), which feed reads already cache under READABILITY
  and AUTHORITY. `InformationLogVisibility.readable` now answers point checks from
  that set when it is current.
  - Loading the set cost 85–130 ms, about two point checks.
  - A dirty or historical transaction, an oversized set or a missing account uses
    the statement predicate.
  - On deployed data, set membership matched `information_log_readable` for
    768 of 768 checks across both service accounts.
  - Job listings also resolved a selection and checked a revision once per input
    of every job. `InformationJobs.listing` now asks each distinct question once
    per listing, against current authority.

## Authority decided in server memory

Project role resolution used to issue three statements per check, and every
permission check repeated it:
- the service-owner lookup;
- `service_project_role` or `authority_project_role`, each walking
  `authority_live_grants()` with `account_active()` evaluated per grant;
- the `projectLineages` recursive CTE.

Those tables hold tens of rows on deployed installations.

**The snapshot.** `AuthoritySnapshot` holds a typed copy of the authority tables
and evaluates those functions in Java, clause by clause: grant liveness, account
activity, project and service roles, the service owner, server administration,
membership and lineages. `CachedProjectMembers` and `ApplicationGrantLineage` read
from it. Mutations, history, administrative control and manifest limits are
unchanged.

**Consistency.**
- **Versioning.** The snapshot is one entry in the bounded projection cache under
  the AUTHORITY domain. Every table it copies moves that counter on insert, update
  and delete (V175). The cache validates the token captured before loading, then
  validates the stored token again on every hit. A write during or after loading
  therefore prevents use.
- **Expiry.** The token's window contains no grant or token expiry, so evaluating
  expiry at the loading statement's `now()` gives the verdict for any instant the
  snapshot is served.
- **Fallbacks.** A dirty, historical or expiry-spanning transaction, a disabled
  cache, or a table over 10,000 rows receives no snapshot and runs the SQL
  functions as before.
- **Capacity.** The snapshot is the twenty-first projection cache, so the default
  `max-projections` rises from 20 to 24. An installation that sets a lower value
  fails at startup with "Projection cache capacity exceeded". Per-cache budgets are
  unchanged; the snapshot itself holds a few kilobytes on deployed installations.

`AuthoritySnapshotDifferentialDatabaseTest` compares every method with its SQL
function after each of 20 fixture states. These cover account enablement, server
administration, bootstrap, credential withdrawal, token revocation and expiry,
owner disablement, ceilings, membership removal, delegated revocation and expiry,
and issuer removal. A changed authority function must change its Java mirror in
the same change, or that test fails.

## Notification-trusted authority

On BETA the snapshot removed 85–92% of authority-table reads, but every check still
issued three small statements: caller eligibility, version capture and the hit's
version validation. Authority changes are rare and act on a human timescale, so
the authority snapshot and targeted log checks now trust database notifications
for repeat reads (see the bounded exception in the
[cache design](query-efficiency-and-cache-design.md)). A trusted check issues no
SQL. A committed revocation, role change or account disable takes effect within
`plowshare.projection-cache.notified.staleness`, 2 s by default, not instantly.

**Why the listener needed probes.** The listener only read its socket. A
connection lost without a reset, as after a VM pause or a dropped NAT entry, made
every poll return empty indefinitely: no notification, no error and no recovery.
The per-read version fence hid this. The listener now sends itself a probe every
500 ms on a session with a network timeout. An answered probe proves delivery, and
a probe unanswered for 3 s ends the session. Silent loss therefore recovers in
about the timeout plus the reconnect delay, and trusted hits stop as soon as
proof is older than the staleness bound. Probes run whenever
`plowshare.projection-cache.notified.enabled` is true, the default, including for
caches that stay versioned. Setting it to false restores the previous behavior.

Writes in the caller's own transaction, transactions above READ COMMITTED and
expiries inside the bound still use the version fence. `NotifiedProjectionDatabaseTest`
checks commit-order echoes and a revocation reaching a trusted cache within the
bound on real PostgreSQL.

## SYSTEM processing decided once

After trusted authority checks, `admins` was still scanned about 56 times a second
on BETA. The information worker's claim and sweep statements begin with
`information_system_processing_revisions()`, which starts from
`authority_live_grants()` and evaluates `account_active()` for every grant on
every poll. `information_system_scope_valid` repeats the same grant test once per
owner and project. Both answers are installation-wide facts; on a valid
installation the SYSTEM root policy, built-in roles and BUILTIN account are
immutable, so they never change.

`AuthoritySnapshot` now decides both, mirroring V236 and V161 clause by clause,
and the worker passes the decision to V241's
`information_system_processing_revisions(system_authorized)`. With a decision,
the live-grant CTEs never execute; with NULL, SQL decides exactly as before. A
transaction that cannot use the snapshot passes NULL. The zero-argument function
remains for older binaries during a rolling upgrade. Per-revision retained-input
and target-scope predicates are unchanged. The PROC audit view still uses the
SQL-decided form.

`AuthoritySnapshotDifferentialDatabaseTest` compares both decisions with their
SQL functions in every fixture state and asserts that a decided call reads
`admins` zero times.

## Regression provenance and validation

The per-row document guard and unconditional retrieval dispatch originate in
`d38d55f49`; `f78e4f048` added the dual-provider branch and unconditional connection
refresh. Worker interruption and lazy close-listener resolution originate in
`cd7f68ab`. The unconditional shared-Help success message was introduced in
`dc9db60e1`.

Focused tests cover query budgets, profile changes, scope/withdrawal parity,
provider accounting after withdrawal, credential concurrency and uncertain
delivery, worker drain ordering, close callbacks and resumable Manual publication.
PostgreSQL tests are required for actual permission SQL, paging, vector-generation
matching and durable claim behavior. Client fixtures retain mutation counts and
no-replay assertions. A source test result does not establish deployed reconnect,
shutdown or Help behavior; those workflows require a subsequent live check.
