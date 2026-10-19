# Runtime query implementation ledger

This records the implemented runtime portion of the query sweep, including deliberately retained reads. It supplements the [source audit](runtime.md); it is not a claim that every audited owner is fully optimized or a latency benchmark. Counts below are JDBC statements on the named branch, excluding transaction control, triggers, stored-function work and separately stated authorization. Bounded multi-row statements are distinguished from driver-dependent JDBC batches. No runtime owner below gains a shared authority cache.

## Manual selection and admission

`V178__batched_information_read_authority.sql` introduces invoker-rights, typed relational selection. Live grant/actor facts are materialized once per selection, readable scopes remain a relation, and normal root scope/audience filtering precedes dependency walks. Existing root privacy, Global/account/linked/Personal rules, token-owner intersection, ancestor expiry/revocation, retained ceilings, dependency cycles and the 1024 dependency ceiling remain fail-closed. Related neighbors retain their different dependency-only semantics. Existing mutation authority routines are unchanged. `InformationSelectionSql` provides the fixed bound argument contract; catalogue discovery and navigation selection use it.

`InformationCatalogue` reuses operation-local admission in code outline/symbol/read paths. It retains status write-admission fallback, run pinning/read audit and independently necessary mutation checks. `DocumentNavigation` uses the explicit single-projection read boundary only for document lists, facets and the newly fused relationship statement. That boundary sets read-only without lowering ambient isolation; multi-step navigation remains repeatable-read. Historical repeatable-read callers bypass the shared projection cache. External Application manifests are not converted into a permission lease.

Parent verification: all nine `InformationReadAuthorityDatabaseTest` cases passed, including old/new selection equivalence, revocation/expiry, dependency overflow/cycles, unrelated Personal corpus filtering and PostgreSQL function-call work reduction. These are SQL-specific tests, not mocked authorization evidence. Default service boundary/admission tests are `DocumentNavigationReadsTest`, `InformationCatalogueAdmissionTest`, `InformationSelectionSqlTest` and the existing navigation/facet tests. Parent owns final aggregate results.

## Runtime owners

### JdbcOperationRecords — implemented, with retention path deferred

Admission lookup joins ordered support into one typed extractor: existing `find` drops from two statements to one; conversation plus sealed admission/support drops from three to one. Successful bind now inserts with a narrow immutable binding tuple returned in one statement, instead of inserting then hydrating the unrelated admission/support graph. Conflict uses a fresh narrow comparison read after the insertion wait. Retaining a new support set still has its existing per-support writes; that distinct mutation path was not silently relabeled fixed. Current grants and admission sealing remain authoritative.

Default: `OperationRecordsReadBudgetTest` covers joined hydration and narrow bind. PostgreSQL: existing `OperationRecordsPersistenceTest` covers concurrent rebinding and durable child envelopes. The first default run exposed an invalid fingerprint in the new test fixture; it now uses a valid 64-hex fingerprint.

### JdbcCommandInvocationsRepository — implemented

Successful binding uses `INSERT ... ON CONFLICT DO NOTHING RETURNING` and the already validated request identity, eliminating its immediate reload. A lost conflict retains a fresh read and exact caller/command/arguments/mode/hash equality checks; claim and completion fencing are unchanged. Success is one statement, conflict two. `CommandBindingReadBudgetTest` asserts both branches; `SkillExecutionsTest.command_binding_is_durable_scoped_and_claimed_once` exercises PostgreSQL binding and claim behavior.

### RunApprovalStore — implemented for creation and answer winners

Ask, command-set ask and allowed-by inserts return the persisted row directly. Allow/deny state-CAS winners also return their changed row, so only the winning update triggers settlement with that row. The immediate read is removed; consume, revocation and retained dispatch checks remain unchanged. `ApprovalReturningTest` verifies return/callback behavior. Existing `RunApprovalStoreTest` tests require PostgreSQL for answer-at-most-once and persistent state.

### JdbcServiceAccountRepository — token listing implemented

Owner token listing joins token/scopes/projects once and groups immutable typed token DTOs, including tokens with no scopes. This changes `1 + 2N` listing statements to one; it does not cache credentials or live authorization. The locked individual-token operations retain their separate scope reads. `ServiceTokenListingTest` checks one-statement grouping. New PostgreSQL case `ServiceAccountsTest.ownerTokenListKeepsEmptyScopeTokensAndDoesNotMixOwners` verifies empty scope retention, stable scope ordering and owner exclusion.

### JdbcPricingRepository and ManagedPricing — implemented

Typed `(route, model)` keys select overrides in chunks of at most 128. A managed list of N built-in entries now needs `ceil(distinctKeys / 128)` override statements, rather than N individual reads; zero keys need none. Existing live administrator admission is retained. Set compares the locked current override without constructing/discarding a display entry; UPSERT returns its actual `updated_at`, avoiding the post-save fetch. History/audit writes and the post-lock authorization recheck remain. Default `ManagedPricingReadBudgetTest` covers list reuse and returned writes; existing `ManagedPricingTest` covers SQL persistence.

### JdbcUsageReportRepository — capped attempt cohort implemented

Selected call rows are retained before loading attempts. Up to 128 parent UUIDs share a bound `VALUES`/lateral statement, preserving an independent 51-row lookahead for every parent's 50-row page and cursor. For N returned calls, attempt loading changes N statements to `ceil(N / 128)`; caller admission, top-level page, transaction controls and other report queries are excluded from this formula. One-call attempt pagination retains its existing narrow read. `UsageQueryTest.deep_attempt_paging_is_lossless_and_rechecks_access_and_cursor_identity` now exercises two calls with 55 attempts each, independent truncation/cursors, paging and live access checks against PostgreSQL.

### JdbcProcRepository — capped attempt cohort implemented

Call detail pages use up to 128 bound UUID parent IDs per lateral statement and keep each parent's independent 100-attempt cap/order. The old cast-on-`call_id` per-item predicate is removed. Call/root/tree authorization and the repeatable-read page boundary remain live; displayed attempts do not become an authority cache. New PostgreSQL case `JdbcProcRepositoryTest.attemptBatchKeepsItsIndependentCapAndOrderForEveryAuthorizedCall` verifies two 101-attempt parents, exact per-parent caps and owner exclusion. Count is `ceil(N / 128)` attempt statements instead of N, excluding page/admission reads.

### UsageExecutionStore — operation-local ancestry implemented

Conversation ancestry is one bounded recursive snapshot rather than one parent read per depth. Orchestration lineage similarly uses one bounded recursive query. Resolve retains the already selected root and computed ancestry/lineage across its saved/historical branches; no shared lineage or permission cache is introduced. Cycle, missing-parent, depth, owner/project and changed-root checks fail closed. Immutable ownership registration and conflict verification remain separate persistence checks. All 22 `RunAttributionTest` cases pass after updating fixture composition to provide `UsageScopeRepository` and an exact test tokenizer; production tokenizer refusal remains unchanged.

### OrchestrationStore and StallSweep — batching implemented with fresh mark guard

One running-set read is followed by two bounded activity statements per 128 candidates: descendant last activity and narrow live-child state. This replaces two activity reads per run; approval lookups, acceptance checks, mark/clear mutations, delivery and recorder calls are excluded. Batch failure falls back to the previous per-run reads before any mutation, preserving failure isolation without replaying effects.

The batch is not a lease. `markStalled` rechecks newest descendant entry activity against the captured quiet timestamp and excludes currently working children using exactly the same person-only question set before its existing state/mark CAS. This check uses the UPDATE statement's current snapshot; it does not promise to block work beginning after that snapshot. Three new `StallSweepTest` PostgreSQL cases cover activity and child creation committed between batch and mark, plus the acceptance persistence case described below. The full class also covers once-per-period marks, later periods, child/person question distinctions and delivery failure recovery.

### JdbcScriptStore — latest-step recovery projection implemented, preliminary probe retained

The JDBC recovery override reads the latest step and tied failed/answered/missing-answer child facts in one statement, then reuses that step for the same no-replay decision. The latest sequence and aggregate evidence therefore share one statement snapshot. Failed/answered cardinality and malformed missing-answer checks retain their previous refusal semantics. The earlier `Orchestrations.resume` failed-delegate probe remains a distinct public decision; a future combined recovery capability needs its own contract rather than passing a potentially stale step into a later authority check. Existing `ScriptRuntimeTest` uncertain-effect and crash/recovery cases require the parent's focused PostgreSQL run.

### JdbcOrchestrationAcceptance — bounded multi-row replacement implemented

Replacement snapshots and validates all commands' run identity before mutation, then uses requirements UPSERT, old-set DELETE and chunks of at most 64 commands per bound multi-row INSERT: `2 + ceil(C / 64)` logical write statements. This is actual SQL grouping rather than an assumption about JDBC driver batch rewriting. The existing unit of work preserves whole-set rollback. `AcceptanceWriteBudgetTest` covers 65-command chunking and mismatched-owner refusal. New PostgreSQL case `StallSweepTest.bounded_acceptance_insert_preserves_order_and_rolls_back_the_whole_replacement` verifies typed round-trip order, failure in the second chunk restoring both the old commands and requirements, and empty replacement.

### JdbcOutgoingRepository — safe redundant reads removed; fresh result projections retained

Claim's locked selection now carries the work projection, avoiding the discarded ID-only selection followed by hydration. Report similarly uses its locked full projection before worker/revision/state checks. Both retain the post-write fresh result read: merging all joined data into a pre-wait modifying CTE would weaken the existing freshness boundary. Cancel's successful owner-filtered UPDATE returns identity, removing its unused preliminary hydration; no-match/terminal behavior and the post-commit result read remain. Worker claims, unknown-effect behavior, owner access and revision fences are unchanged. Send/peers project resolution and mutation-result joins are explicitly deferred pending a sound post-wait snapshot contract. `OutgoingEndToEndTest` concurrent claims/report fencing and uncertain reply/cancel cases are the SQL/transaction checks; both selected authenticated cases now pass after repairing repository class-proxy compatibility and declaring the isolated fixture's model identities explicitly.

### JdbcUsageScopeRepository — existing identity read implemented

Existing project registration uses a narrow identity SELECT with `FOR KEY SHARE`, replacing a no-op UPSERT on every attribution. The lock retains deletion/name-change protection without manufacturing a project mutation/invalidation. Missing identity uses INSERT/RETURNING; a conflict retains a fresh locked identity read after waiting. Global requires no SQL. `UsageScopeReadBudgetTest` covers existing/missing/conflict branches. No credentials or permission decisions are cached.

### RuntimeConfig — narrow live projection implemented; shared cache deferred

`get` selects only value; `authorOf` only author, rather than hydrating the full entry and timestamp. Both remain current every-call reads; `authorOf` has no current production callers. Existing absent/blank and outage last-good semantics remain. Sharing is intentionally deferred: no complete CONFIG version domain is installed, and several durable cache probes would cost more statements than this cheap single lookup. `RuntimeConfigProjectionTest` covers narrow projection and fallback. Controller/seed in-memory fixtures now implement their narrow methods explicitly; the corrected controller/seed fixtures pass the focused rerun.

## Verification ownership and remaining limits

The delegated agent ran no Gradle or PostgreSQL commands. The parent runs formatting, default checks and narrowly selected `full-db` tests serially. New database cases are justified by actual PostgreSQL RETURNING/join mapping, recursive SQL, lateral per-parent caps, atomic multi-row rollback and concurrent statement freshness; mocked query counts alone cannot establish those contracts. Parent-reported successful authority tests are recorded above; the runtime PostgreSQL reruns pass, including the fresh stall guards and multi-chunk rollback case. The full slice matrix has 301 distinct passing database cases; passing default/native SDK gate results are in the [slice receipt](../query-efficiency-reuse-sweep.md#verification). Partial retained/deferred paths above must remain visible in final reporting.
