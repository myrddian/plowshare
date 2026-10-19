# Runtime JDBC analysis

Analysis source: `7bc567c2282188925ac07c81e6fcae81b41a0a40`; runtime base: `fc36351f239b62984363ababaa1de1139402aafb`. This is independent static analysis of the non-Relay runtime owners, not implementation or a current latency benchmark.

Counts describe synchronous JDBC statements on the stated branch. They exclude transaction begin/commit, SQL inside functions and trigger work. N means returned/processed rows, not total database cardinality; ancillary authorization is stated separately. Proposed counts require the described mapping/consistency assumptions.

Cache verdicts distinguish operation-local DTO reuse, sharing content-addressed immutable values, blocked mutable-head caching and authoritative no-cache operations. All shared admission additionally requires the [delivery contracts](../query-efficiency-plans/delivery.md) and bounded LRU/in-flight/byte controls. Notification receipt alone never proves current authority.

## R01 — JdbcIdentityRepository

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/JdbcIdentityRepository.java#L17) · [caller / contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/LiveScopedAuthority.java#L1) · [schema / function](../../plowshare-server/src/main/resources/db/migration/V155__explicit_authority_identities.sql#L1)

Verdict: **no-material-waste**; cache: **operation-local**.

`LiveScopedAuthority` and `PinnedOperationAuthority` resolve typed identity mappings. Scope SQL joins account kind and selects `s.*`; project lookup adds the project-name join. V155 already has unique legacy handle/project identity and partial account indexes for Personal/knowledge scope, matching those predicates. These are bounded lookups, not proven slow scans.

`Account`, `Role` and scope records contain values rather than live behavior, but their data is not universally immutable: account kind, project deletion and restricted scope shape affect results. There is no returned mapping version. Request-local reuse is feasible; shared mapping heads are blocked until account/project/registry writers expose durable generations and current authority fences. `verifyBuiltIns` must read the database at startup; a cached prior registry validation cannot establish health. Negative/restricted results must not survive identity creation or restoration. No unnecessary SQL function/index is established.

**Count model:** Each account/role/scope/project/personal/knowledge lookup is 1 SELECT; verifyBuiltIns is 1 function call. Reusing one already resolved mapping can replace K identical reads with 1 within the same valid operation.

**Uncertainty:** No caller trace proves K>1 in a specific admission, and function internals are not timed.

## R02 — JdbcOperationRecords

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/JdbcOperationRecords.java#L77) · [caller / contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/PinnedOperationAuthority.java#L1) · [schema / function](../../plowshare-server/src/main/resources/db/migration/V159__retained_operation_authority.sql#L1)

Verdict: **confirmed-waste**; cache: **operation-local**.

`PinnedOperationAuthority` rehydrates sealed evidence. `find` performs the support query inside its admission mapper; `conversation` calls `find` inside its own mapper. More decisively, `bind` inserts and invokes that full three-read graph although comparisons use conversation scalar fields and admission ID only. This over-hydration is provable; support is not needed for binding comparison. Support insertion also loops rather than batching. Blame locates the nested shape in `d5f3843f`; later global-read changes did not introduce it.

V159 support primary key `(operation,action,grant_id)` already supports ordered support retrieval. Admission/conversation records are value snapshots; sealed fields are protected, but `job_id` changes independently and conversation targets grow. Sharing immutable sealed evidence is plausible, not a substitute for live grant checks. Bind/parent/started writers and retained deletions need explicit publication/recovery; target collections cannot be cached from current member IDs alone.

**Count model:** Existing admission find=2 reads (row+support), missing find=1; existing conversation=3; bind=1 INSERT+3 hydration reads=4; retain with S support rows=S+2 statements. A narrow bind comparison reduces 4 to 2; a joined typed conversation loader can reduce 3 to 1.

**Uncertainty:** Counts exclude transaction/trigger work. Joined loader must preserve complete support and identical-binding rejection; runtime repetition frequency is unmeasured.

## R03 — JdbcResourceScopeRepository

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/JdbcResourceScopeRepository.java#L20) · [caller / contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/ProjectAuthorization.java#L1) · [schema / function](../../plowshare-server/src/main/resources/db/migration/V88__orchestration_start_receipts.sql#L1)

Verdict: **candidate**; cache: **operation-local**.

`ProjectAuthorization` resolves allowlisted resource kinds through exact-ID queries; MESSAGE may return both sender and recipient projects. Receipt lookup binds authenticated account plus request UUID, so a resource-only cache key would be wrong. `triggerOwnedBy` projects the owner and compares in memory; it is a narrow result, not full-entity hydration. Current resource placement is an authorization input.

No source generation is returned with project strings. A scoped request-local resolution can be passed downstream, but sharing these lists would require every resource owner to version moves/deletion/route changes and preserve null/global semantics. Receipt projection needs account identity in the key and still current project/token checks. The message `IN(sender,recipient)` join is an index-plan candidate; the source alone proves no missing index. No claim is made that mapping work dominates document latency.

**Count model:** projects(resource)=1 SELECT; triggerOwnedBy=1; receipt projects=1 bound SELECT. A trigger path requesting both scope and ownership would cost 2 and could use 1 combined typed projection.

**Uncertainty:** The two-call trigger composition is a conditional model, not a verified hot-path trace. Message-route fan-out/index cost is unmeasured.

## R04 — JdbcScopedGrantRepository

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/JdbcScopedGrantRepository.java#L35) · [caller / contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/LiveScopedAuthority.java#L1) · [schema / function](../../plowshare-server/src/main/resources/db/migration/V156__scoped_rbac_grants.sql#L1)

Verdict: **candidate**; cache: **no-cache**.

`LiveScopedAuthority` consumes live bindings; issuance selects an exact authority UUID in Java after `live(issuer)` returns every live issuer binding. The SQL function computes recursive liveness from root policy, account activity, expiry and token scope. V156 already indexes recipient and issuing parent. Targeting a requested lineage can avoid unrelated hydration, but changing recursion without equivalent ancestor checks would be incorrect.

`Binding` defensively copies its action set and includes grant/role versions. Those versions do not encode time expiry, account disablement, ancestor revocation, token scope or project classification. Consequently a version-keyed cached binding is not a live allow decision. Historical records may be shared only as evidence with separate disclosure checks. Issue/revoke preserve project-before-scope locking, live issuing authority and last-manager protection; no cache removes those. Read `find` before locks and post-update `find` have different purposes, so they are not labelled identical-read waste.

**Count model:** live/lineage/projectBindings each issue 1 JDBC SELECT, potentially evaluating recursive authority_live_grants internally. issue=7+P statements on success, P=1 for a project lock, 0 otherwise. A targeted issuer query changes rows/function work, not necessarily statement count.

**Uncertainty:** Function rows/calls and scope cardinality determine cost; no live plan was run for this owner. Issue count excludes triggers and transaction statements.

## R05 — JdbcSystemOperationRecords

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/JdbcSystemOperationRecords.java#L18) · [caller / contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/BoundSystemOperations.java#L1) · [schema / function](../../plowshare-server/src/main/resources/db/migration/V161__bounded_system_operations.sql#L1)

Verdict: **no-material-waste**; cache: **operation-local**.

`BoundSystemOperations` retains/validates an exact immutable purpose envelope: ID, scope, grant, agent, model and binding hash. V161 protects evidence immutability. The primary key limits verification to one row and the result is already boolean; adding an index or wrapping it in a procedure cannot remove a demonstrated scan.

`Envelope` is an immutable value, so the caller can retain it locally after verified admission. It is retained purpose evidence rather than current executor authority. Sharing the object is possible only after durable identity/retention recovery and current scope/grant checks; there is no source-version field to cover exceptional repair/deletion. A cached successful `require` must not manufacture SYSTEM permission on restart. Ordinary envelope insertion is the sole local writer; scope/grant lifecycle belongs to other repositories and remains authoritative.

**Count model:** retain=1 INSERT; require=1 ID-bound EXISTS. One retain followed by require=2 statements; no reduction is established across restart/reconciliation boundaries.

**Uncertainty:** Duplicate require within a single valid operation is a hypothesis; no call trace establishes it.

## R06 — JdbcActingRepository

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/acting/JdbcActingRepository.java#L276) · [caller / contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/acting/OwnedActingTokens.java#L1) · [schema / function](../../plowshare-server/src/main/resources/db/migration/V149__acting_grants_and_tokens.sql#L1)

Verdict: **confirmed-waste**; cache: **no-cache**.

`OwnedActingTokens` exposes the subject list. `tokens` maps IDs through `token`; that row mapper reads each token’s project names. The same token DTO is reconstructed twice per ID path, and `proof` adds digest->ID before hydration. This is direct N+1 evidence, introduced in `06fdf45c4b0d0f558bee0709639fb72a5f532c3e`, not inferred from UI slowness. V149 already has digest uniqueness, token-project primary key and subject/created/id index.

Activation and eligibility replacement also loop inserts, but the subject list is the clearest fixed target. `Token` stores non-secret identifiers, scope, expiry and revoked flag; it has no mutation version, and project names may change. Do not globally cache proof/digest/credential or an allow decision. Request-local token DTO reuse is viable under consent/administration transaction fencing. Revoke, eligibility, deployment head/declaration activation, token scopes, account and membership writers all change eligibility; expiry needs a clock boundary even without a write. The shared advisory lock is necessary for mutations.

**Count model:** tokens(subject)=1 ID query+2N row/project queries, N<=256: N=256 gives513 statements. proof(found)=3; absent proof=1. Bulk token rows+all project rows can yield2 queries independent of N; an aggregate join could yield1.

**Uncertainty:** No database time measured here. Constant-count alternatives assume complete empty scope sets and preserved created_at DESC,id order; live consent checks remain additional.

## R07 — JdbcActingWorkRepository

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/acting/JdbcActingWorkRepository.java#L54) · [caller / contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/acting/PersonalActingWork.java#L1) · [schema / function](../../plowshare-server/src/main/resources/db/migration/V151__acting_personal_turns.sql#L1)

Verdict: **candidate**; cache: **operation-local**.

`PersonalActingWork` reads binding/receipt records and checks current Personal ownership plus active conversation lifecycle. Receipt reads already join binding fields; there is no per-receipt N+1. V151 primary keys cover conversation and actor/request; job is unique. Retained binding tombstones deliberately survive deleted projects/conversations, so caching a binding cannot prove those live records still exist.

DTOs are immutable records, but `Admission` embeds a mutable receipt-state snapshot and neither source has a general revision. Retain/reuse binding data within one admission; keep current owns/active checks under the required authority boundary. `started`, `finished` and `uncertain` alter receipt state without a version, while conversation/project writers alter ownership/liveness elsewhere. Sharing mutable receipt heads is blocked. Claim/result uncertainty is durable evidence and never means execution did not occur; collapsing receipt transitions or replaying on cache miss is invalid.

**Count model:** binding=1; owns=1 joined EXISTS; active=1 EXISTS. Reading a binding and both current checks is3; a combined typed ownership/lifecycle read could be1 with equivalent transaction fences. Receipt and job hydration already cost1 each.

**Uncertainty:** A combined result must not extend current ownership/activity across a dispatch boundary; frequency and latency are unmeasured.

## R08 — JdbcCommandInvocationsRepository

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/JdbcCommandInvocationsRepository.java#L33) · [caller / contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/BoundCommands.java#L1) · [schema / function](../../plowshare-server/src/main/resources/db/migration/V91__command_invocations.sql#L1)

Verdict: **confirmed-waste**; cache: **operation-local**.

`BoundCommands` binds a command then consumes the `Bound` DTO. The new-insert branch performs a full query despite knowing inputs; the conflict branch genuinely must verify caller, command, arguments, mode and hash. Statement reduction is branch-specific rather than permission to remove reconciliation. Pending returns full arguments/results and has no row limit, so retained failures can increase bytes. The partial-index mismatch cannot be fixed by assuming PostgreSQL will use the narrower predicate for a three-state result.

`Bound` is a value record, but state/result are mutable and have no source revision. Definition hash versions the definition only. Request-local descriptor reuse is safe; shared state requires another generation and account/conversation/caller key scope. Claim is a bound->dispatching CAS and must remain authoritative. Bind/claim/ended plus conversation deletion change the pending collection; an empty cached list requires insertion invalidation too.

**Count model:** bind=1 INSERT+1 full reload=2; new insert can return its DTO in1 statement, conflict path still needs retained comparison. find/pending/claim/ended each=1. Pending query includes bound,dispatching,failed; V91 partial index includes only bound.

**Uncertainty:** RETURNING savings require preserving trigger/default values and conflict matching. Predicate mismatch is certain, but its latency impact needs a plan/cardinality measurement.

## R09 — JdbcSkillExecutionsRepository

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/JdbcSkillExecutionsRepository.java#L33) · [caller / contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/SkillRuntime.java#L1) · [schema / function](../../plowshare-server/src/main/resources/db/migration/V90__skill_executions.sql#L1)

Verdict: **no-material-waste**; cache: **operation-local**.

`SkillRuntime` and `SkillContexts` consume typed `Execution` records. Claim already uses `INSERT ... ON CONFLICT DO NOTHING`, avoiding a preflight read. Active lookup uses a conversation/state partial-index shape; V90’s `(conversation,created_at)` index supports that predicate, though the invocation tie-break may still sort. `contextResult` scans the JSON snapshot through `jsonb_array_elements`, which is a candidate for expensive repeated payload work rather than multiple queries.

`Execution` includes mutable state/result and reconstructed skill metadata, with no general revision; it is not an immutable execution identity merely because Java uses a record. Context is pinned once by a conditional update and could be content-versioned immutable data, but account/child-conversation redemption remains current. No shared execution-state admission until running/closed/failed/context and conversation deletion writers have durable generations. Claims, context-pin CAS and execution uncertainty stay uncached. A normalized handle projection needs evidence of M before schema work.

**Count model:** find,claim,running,active,closed,failed,context,contextResult each issue1 statement on their ordinary path. contextResult expands M stored JSON items inside that1 statement; M is SQL/bytes work, not JDBC N+1.

**Uncertainty:** Context size, redemption repetition and active-list cardinality are unknown. No redundant live-state read is proven.

## R10 — JdbcAllowanceRepository

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/allowances/JdbcAllowanceRepository.java#L26) · [caller / contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/allowances/AllowanceRepository.java#L1) · [schema / function](../../plowshare-server/src/main/resources/db/migration/V173__standalone_execution_allowances.sql#L1)

Verdict: **candidate**; cache: **no-cache**.

`AllowanceRepository.attach` checks existence before returning an already durable budget; `restore` wires callbacks from a loaded snapshot. SQL functions own atomic finite spending and acceptance, already avoiding Java read-then-write races. V173’s primary key matches the source lookup; ceiling updates include a current spent predicate and explicit-only information policy check.

`AllowanceSnapshot` is a value record, but spent and ceiling mutate on every relevant claim/control operation and have no revision. `Budget` itself has live callbacks and mutable usage behavior, so it is not shared immutable cache data. Retain a current snapshot locally when attaching the same known source, but never cache reserve admission, expected-spent CAS or spending authority. Writers include this repository, inference acceptance and V173 attachment/backfill triggers across board/conversation/information owners. Notifications cannot authorize another call, and adding a stored procedure duplicates existing function responsibility without proven benefit.

**Count model:** find/retain/claim/accept/ceiling/increase/add each=1 statement. attach(budget) always does1 find, including already durable budgets; restore does1 find. Claim/accept function internals are additional SQL work. Reusing a just-loaded verified snapshot can remove1 attach read only within equivalent fencing.

**Uncertainty:** Caller budget hydration multiplicity is unmeasured. Current spent/ceiling reads often have a necessary freshness reason.

## R11 — JdbcApplicationIdentityRepository

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/applications/JdbcApplicationIdentityRepository.java#L23) · [caller / contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/applications/OwnedApplicationIdentities.java#L1) · [schema / function](../../plowshare-server/src/main/resources/db/migration/V138__application_owned_identities.sql#L1)

Verdict: **candidate**; cache: **no-cache**.

`OwnedApplicationIdentities` holds optional identity/token row locks and reconciles one-time request receipts. `find` already returns a bounded joined descriptor rather than fetching token status separately. V138’s project-name key, token uniqueness and receipt UUID support its exact predicates. Activation deliberately verifies the current project row; a same-name replacement cannot inherit reserved authority.

`Binding` is a value snapshot containing token lifecycle fields and issuance state, not a permanently immutable mapping. It exposes no durable row generation. Shared identity heads are blocked by reserve/activate/issued, service token rotation/revocation, project recreation and account lifecycle writers. Credentials/digests never belong in the shared DTO cache. Request-local binding reuse must retain locks and exact receipt fencing; replacing `find(...,true)` with cached data would remove serialization. A combined issuance routine might reduce protocol trips, but would need to preserve the existing recovery receipt and credential-issued preconditions.

**Count model:** find/receipt each=1; reserve=2 writes; activate=1 project read+1 identity update+optional scope insert=2 or3; issued=3 writes. Reusing already locked project/binding identity may remove a repeated read only where caller supplies equivalent evidence.

**Uncertainty:** No repeated caller read trace is established; activation count depends on requested role/scope branch.

## R12 — JdbcSuspendedRunCalls

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/approvals/JdbcSuspendedRunCalls.java#L43) · [caller / contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/approvals/RunCallResumption.java#L1) · [schema / function](../../plowshare-server/src/main/resources/db/migration/V154__suspended_run_calls.sql#L1)

Verdict: **no-material-waste**; cache: **operation-local**.

`RunCallResumption` gets the original call and current approval decision in one join. Pending uses the V154 partial conversation/sequence index, while global recovery filters answered approvals with no conversation prefix; that different shape needs a measured plan. Splitting IDs and payload must not create per-call hydration.

`Call` and `ToolCall` are immutable value snapshots, but decision/state/result/released fields mutate without a common revision. The original arguments can be retained locally and potentially content-versioned, whereas execution claim must read current allowed/released/waiting state. Retain/release/complete/acknowledgement plus approval answer/revoke and conversation deletion invalidate different projections. The started-without-result condition is intentionally uncertain: cache expiry or absent recovery data must never cause command replay. No shared whole-Call admission is supported by current version fields.

**Count model:** retain,pending,find,recoverable,claim,release,complete,acknowledged each=1 statement. pending/recoverable hydrate N calls in1 joined query, so they are not JDBC N+1.

**Uncertainty:** Global recovery scan cardinality and retained argument/result bytes are unknown; no latency or caller duplicate is established.

## R13 — RunApprovalStore

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/approvals/RunApprovalStore.java#L232) · [caller / contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/ApprovalFrames.java#L1) · [schema / function](../../plowshare-server/src/main/resources/db/migration/V50__run_approvals.sql#L1)

Verdict: **confirmed-waste**; cache: **operation-local**.

`ApprovalFrames` and delivery/continuation services use `RunApproval` value DTOs. Creation and successful answer explicitly refetch the row to build event payloads; `RETURNING` can deliver that payload without a second round trip. `consume` is different: one-shot CAS, conversation preference and stdin equivalence are required concurrency semantics, not disposable duplicate checks. Standing prefixes are loaded and matched in Java. V50/V152 indexes cover conversation/state, project/side/state and pending project identity; `openFor`’s owner OR legacy conversation lookup is a separate plan candidate.

Whole approvals cannot be treated as immutable cache values: scope, state, prefix, decision and delivery timestamps change without a general version. Current consent and one-shot usage remain uncached. Ask/allow/deny/revoke/consume/delivered and ownership changes affect account/conversation/project collections. Event callbacks alone are process-local integration, not durable cross-writer cache recovery. Operation-local payload reuse yields immediate savings without a shared permission cache.

**Count model:** ask=1 INSERT+find=2; successful allow=1 UPDATE+find=2 (before external callback work). consume=1 exact SELECT+C one-shot CAS attempts+optional1 standing SELECT. RETURNING can reduce ask/allow success to1 while preserving callback DTO; claim/consent checks stay live.

**Uncertainty:** Callbacks may add external reads excluded here. Candidate CAS count C depends on contention and matching stdin; prefix scan cost is unmeasured.

## R14 — AdminStore

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/auth/AdminStore.java#L35) · [caller / contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/SocketAuthorization.java#L1) · [schema / function](../../plowshare-server/src/main/resources/db/migration/V37__admins.sql#L1)

Verdict: **candidate**; cache: **no-cache**.

`SocketAuthorization`, filters and administration consume different account checks. `byHandle` includes password hash; the boolean authority methods correctly avoid that overfetch. Exact handle predicates use the account key; function cost inside `account_active` is separate from JDBC count. A single typed admission snapshot could replace independently repeated checks, but the current source alone does not establish which checks are redundant across lock or operation boundaries.

`AdminRecord` is a value, but password/hash and permission-bearing flags are mutable and session version changes for specific lifecycle events. That version does not certify every profile field. No shared authentication or admin-allow cache is justified. Password, setup, account-administration, service-token principal and membership writers span owners. Reuse only the current admitted operation’s non-secret DTO; a later frame must still check revocation. Startup account setup retains its advisory serialization, and broad index/procedure work has no measured need.

**Count model:** byHandle/isServerAdmin/isEnabled/sessionVersion/sessionCurrent each=1 SELECT. K separate checks for the same admission cost K; a combined current-account DTO could use1, assuming one equivalent authority snapshot.

**Uncertainty:** No concrete K-check transport sequence is asserted; bootstrap/administration calls deliberately recheck under locks.

## R15 — JdbcAccountAdministrationRepository

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/auth/JdbcAccountAdministrationRepository.java#L25) · [caller / contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/auth/ServerAdministration.java#L1) · [schema / function](../../plowshare-server/src/main/resources/db/migration/V81__durable_login_sessions.sql#L1)

Verdict: **candidate**; cache: **operation-local**.

`ServerAdministration` gets typed accounts, live session rows and audit pages. History already validates a 1–100 limit and uses descending ID cursor; its optional target/cursor OR predicates can hide selectivity but are not proof of a slow plan. V81’s expiry index serves pruning; it does not automatically satisfy handle plus created/id listing order. Account lookup excludes non-USER/bootstrap rows as required.

Account/Session DTOs are immutable snapshots without a complete source revision. Session expiration changes query membership without a write, and admin disable/revoke operations change authority. Reuse loaded account DTOs inside the existing administration lock for audit construction; do not cache enabled-administrator counts or session-current permission. Shared historical audit pages would require watermark/owner authorization and append-membership semantics. Account administration, AdminStore and service-account writers change account/session generations; sessionVersion is not a universal profile version.

**Count model:** account/list/sessions/history each=1 SELECT; revokeSessions=2 UPDATEs; update/reset/create/audit each=1. Narrow history remains1 after any predicate/index change, so no statement saving is claimed.

**Uncertainty:** Session/audit size and query plans are unknown. Duplicate account reads inside one administration action need a caller trace.

## R16 — JdbcDatabaseReadiness

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/auth/JdbcDatabaseReadiness.java#L17) · [caller / contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/auth/ReadinessController.java#L1)

Verdict: **no-material-waste**; cache: **no-cache**.

`ReadinessController` asks `DatabaseReadiness.available`; this implementation issues the constant query and maps database access failure to false. There is no DTO to share, predicate to index, row projection to narrow or stored procedure to optimize. The relevant potential delay is obtaining/using the current connection, not materializing application content.

Caching success would hide a database outage and caching failure would hide recovery. The correct cache-layer integration is explicit bypass. Listener status, cached account data or a remembered last success cannot prove current JDBC availability. A single HTTP response can reuse its local boolean if genuinely needed twice, but no such duplicate is established here. There are no domain writers or invalidation dependencies; probe frequency/pool timeout measurement is the outstanding operational evidence.

**Count model:** Each available() invocation performs1 SELECT1; no table/row fan-out. Required current probes remain1, with no proposed cache reduction.

**Uncertainty:** Pool acquisition and connectivity latency are unmeasured; the SQL itself has no application-table scan.

## R17 — JdbcDurableSessions

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/auth/JdbcDurableSessions.java#L48) · [caller / contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/auth/TokenStore.java#L1) · [schema / function](../../plowshare-server/src/main/resources/db/migration/V81__durable_login_sessions.sql#L1)

Verdict: **candidate**; cache: **no-cache**.

`TokenStore` offers the typed authentication result, enabling one read rather than multiple accessor calls. Login always locks/checks the current account, removes expired chains, inserts a chain and two grants. Refresh intentionally rereads `spent` after acquiring the chain lock: its comment explains a waiting transaction may have seen the old grant snapshot. That second read must not be labelled fetch-discard-refetch. V81 indexes expiry cleanup and grant chain; digest primary keys serve authentication.

Foreground unbounded expired-chain deletion on every issue is a candidate workload problem, not a measured regression. Credentials, digests, authentication allows and refresh state must stay out of shared LRU. The typed authentication snapshot is reusable within one admitted operation only. Account reset/disable/session-version changes and session issue/refresh/revoke affect liveness; expiration additionally needs current time. No async invalidation can replace immediate revocation or refresh reuse detection.

**Count model:** access/authentication=1 joined SELECT; separate valid+handle+restricted calls would be3. Successful issue=5 statements. Successful refresh=7; spent-token reuse branch=3 (locked read,spent read,revoke).

**Uncertainty:** Legacy accessor triple is conditional, not a verified caller sequence. Login cleanup row count and pool time are unknown.

## R18 — JdbcServiceAccountRepository

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/auth/JdbcServiceAccountRepository.java#L35) · [caller / contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/auth/ServiceAccounts.java#L1) · [schema / function](../../plowshare-server/src/main/resources/db/migration/V111__service_accounts.sql#L1)

Verdict: **confirmed-waste**; cache: **no-cache**.

`ServiceAccounts` lists owner tokens by mapping IDs through `token(handle,id,false)`, whose mapper calls `scopes`. This is an explicit nested-query chain. It appears in earliest available public snapshot `f78e4f048f0b2bc1087ffc87399eb504919871d0`; that snapshot does not prove original introduction. V111 token owner/name uniqueness and scope primary key support the joins; adding indexes does not remove the 1+2N round trips.

Token DTOs contain no credential string but do contain current expiry/revocation/scope state and no source version. Sharing them as authority is unsafe. Request-local typed snapshots can be retained, and owner-bound display summaries could later be versioned separately. `issue`, `rotate`, `revoke`, bulk revocation, principal retirement, Application identity issuance and project/token-scope writers are all relevant; expiry changes liveness without a notification. Optional FOR UPDATE reads and administration/project locks cannot be served from cache. Bulk revocation already avoids refetching principal IDs through RETURNING.

**Count model:** tokens(owner)=1 ID query+2N token/scope queries; N=100 gives201. A bulk row query plus all selected scopes can yield2 independent of N. Single token=2; revokeOwnedTokens=1 UPDATE RETURNING, preserved.

**Uncertainty:** N is returned token count; list is currently unbounded. Constant-count loader must retain empty scopes, owner filtering and created_at,id order.

## R19 — JdbcServiceCredentialsRepository

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/auth/JdbcServiceCredentialsRepository.java#L21) · [caller / contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/auth/TokenStore.java#L1) · [schema / function](../../plowshare-server/src/main/resources/db/migration/V111__service_accounts.sql#L1)

Verdict: **no-material-waste**; cache: **no-cache**.

`TokenStore` delegates service authentication to a digest-bound token/principal join. It returns only principal handle and session version, already a narrow typed projection. Unique digest access exists in V111. This is not full token/scope hydration and must not be conflated with the service-account token-list N+1.

Returned `Authentication` is an immutable identity snapshot, but the decision depends on expiry, revoked/issued token state and active account ancestry. Session version alone does not version all token scopes or issuance changes. No shared credential/allow cache is admissible. Retain authentication locally for the same operation, then let project/tool/acting authority remain separate. Rotate/revoke/issue, Application token issuance and owner/principal lifecycle are multiple writers; a delayed notification would otherwise permit a revoked credential. No unnecessary index, function wrapper or DTO reload is proven in this owner.

**Count model:** Well-formed credential authentication=1 joined SELECT; malformed input=0. A fresh protected admission still requires authoritative authentication; no reduced count is established.

**Uncertainty:** account_active internal work and aggregate frame rate are unmeasured; fallback/combined transport authentication is outside this count.

## R20 — RuntimeConfig

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/config/RuntimeConfig.java#L195) · [caller / contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/config/RuntimeConfigSeed.java#L1) · [schema / function](../../plowshare-server/src/main/resources/db/migration/V28__runtime_config.sql#L1)

Verdict: **confirmed-waste**; cache: **cache-blocked**.

`RuntimeConfigSeed` binds live accessors, which call `currentOrLastKnownGood` -> get -> entry. The ConcurrentHashMap is consulted only on database failure, so it does not suppress normal reads. This behavior is in earliest public snapshot `d38d55f4943f180dfbe7d4d9ff2249e2a8902874`, not an attributed original regression. `get` and `authorOf` each load value/time/author; `all` already returns every field in one query.

`Entry` is immutable, but its `updated_at` timestamp is not a monotonic commit/recovery generation. Upsert, boot seed and direct writers change the same head. The existing last-known-good map also has separate outage semantics: absent versus unreachable and malformed numeric values cannot collapse into cache misses. Reuse a loaded Entry immediately; shared head cache is blocked until durable per-key/set generations, writer coverage and reconnect handling are defined. Key primary index is already sufficient; a procedure does not remove repeated accessor calls by itself.

**Count model:** M healthy intOr/get/authorOf calls each execute entry: M SELECTs, even for one unchanged key. get+authorOf same key=2 full Entry loads; retaining Entry gives1. A valid shared unchanged-key hit could give0 SQL only after freshness/invalidation semantics exist.

**Uncertainty:** M invocation frequency is not measured; healthy-read caching deliberately changes current freshness and needs an explicit consistency contract.

## R21 — JdbcCodeWorkspaceStore

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/files/JdbcCodeWorkspaceStore.java#L42) · [caller / contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/files/CodeWorkspaceMonitor.java#L1) · [schema / function](../../plowshare-server/src/main/resources/db/migration/V98__workspace_code_tracking.sql#L1)

Verdict: **candidate**; cache: **cache-blocked**.

`CodeWorkspaceMonitor` already gets claim metadata directly rather than selecting IDs then rehydrating. Claim’s cleanup/select/generation-update pair is cohesive lease work; `FOR UPDATE SKIP LOCKED` and anti-mutation predicates are required. Begin’s separate existence/global/owner counts are a set-based consolidation candidate under its global capacity lock. V98 due/expiry/project indexes address portions of predicates; nullable-session anti-join and ID tie-break need actual plans.

Generation exists and advances on begin/claim/invalidate, but `publish` changes state/issues/sources at the same ticket generation. Therefore `(workspace,generation)` alone cannot version every status projection: checking and ready can share it. Immutable manifest content could be shared with a publish/content version; status cache is blocked pending that coverage. Publish/unavailable/forget/index and mutation fences, plus project changes, alter dependent views. Locks, capacity admission and mutation exclusion stay authoritative. Sources already use batchUpdate, so proposing batching there as a new savings would be wrong.

**Count model:** begin existing workspace=4+P statements, P=project lookup0/1; new admitted workspace=6+P when both capacity counts run. claim empty=2,selected=3. publish success=2 ordinary statements plus source batch (S logical inserts; driver determines wire batching).

**Uncertainty:** Capacity, mutation cleanup and scan sizes are unknown. API batch call count is not equivalent to one SQL statement/round trip.

## R22 — AccountingStore

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/accounting/AccountingStore.java#L54) · [caller / contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/accounting/AccountingProjector.java#L1) · [schema / function](../../plowshare-server/src/main/resources/db/migration/V85__durable_inference_accounting.sql#L1)

Verdict: **candidate**; cache: **operation-local**.

`AccountingProjector` projects a batch while locking the journal watermark. Every event checks prior event identity; new non-created events lock/read call state and validate call sequence/lifecycle. AttemptStarted additionally counts running attempts and updates the call twice. Some transaction-local state reuse is plausible, but ordered duplicate/conflict detection and rollback are necessary, so this is a candidate rather than blanket waste.

V85 already has UNIQUE `(call_id,attempt_number)`: it can serve latest-attempt ordering in reverse. A new identical latest-attempt index is unsupported. Private `CallState` is immutable as a Java record but advances per event; only transaction-local reuse is currently justified. RateCard versions are immutable/shareable; call lifecycle, watermark and recovery locks are not. Projection/recovery plus price and report dependencies require durable invalidation; sum/max sequence values do not establish commit-order recovery across journals. Failed projection must publish no shared intermediate DTO.

**Count model:** For E replayed existing events, project=E+3 statements (register,watermark lock,E duplicate checks,watermark update). E new AttemptStarted events=7E+3. New AttemptFinished events=5E+3. A transaction-local call state may save some reads, but no exact reduced count is proven.

**Uncertainty:** Only homogeneous successful branches are counted; triggers/price writes/mixed event kinds alter work. No live timings or retained batch distribution available.

## R23 — JdbcInferenceAdmissions

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/accounting/JdbcInferenceAdmissions.java#L33) · [caller / contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/OperationInference.java#L1) · [schema / function](../../plowshare-server/src/main/resources/db/migration/V160__operation_inference_claims.sql#L1)

Verdict: **candidate**; cache: **no-cache**.

`OperationInference` passes a typed `InferenceBinding` across paid-work admission. Retain’s relation check protects against changing an operation’s budget after conflict; submitted’s require checks operation/budget/ordinal/ceiling before the single-winner transition. A cached prior admission cannot grant dispatch. V160/V173 keys and existing allowance functions already own atomic spending; no broad report-style scan is present.

`InferenceBinding` is value evidence, but submitted and accounting correlation mutate separately and have no returned revision. Reuse immutable identity fields locally; do not cache allowance acceptance, submitted eligibility or accounting ownership. A cohesive SQL routine could combine exact evidence predicates with the transition, preserving mismatch refusal and no-replay semantics. Retain/submitted/accounted and allowance/operation writers affect reporting projections; any future historical evidence cache still needs outer-commit publication and current disclosure. No claim is made that the four retention statements are all avoidable.

**Count model:** retain=4 statements including allowances.accept,relation insert,relation EXISTS,admission insert; submitted=2 (require+CAS UPDATE); require/accounted each=1. A combined retained-binding/CAS predicate could make submitted1 if it proves all original fields atomically.

**Uncertainty:** Stored allowance/trigger work is excluded. Reducing evidence checks requires a tested equivalence proof, not merely trusting the supplied record.

## R24 — JdbcPricingRepository

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/accounting/JdbcPricingRepository.java#L21) · [caller / contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/accounting/ManagedPricing.java#L1) · [schema / function](../../plowshare-server/src/main/resources/db/migration/V112__admin_pricing.sql#L1)

Verdict: **confirmed-waste**; cache: **shared-immutable**.

`ManagedPricing.list` maps every configured target through `entry`, which always calls repository.find, including absent overrides. Thus this owner has a confirmed caller N+1 beyond repeated per-call lookups. Save writes current override, history and admin audit atomically; set then rereads the just-written entry for its response. V112 composite `(billing_route,model)` key serves lookup; an index cannot solve per-target trips.

`RateCard` defensively copies tiers and defines a canonical content hash `version()` across all price fields, making pinned card content a strong immutable shared-cache candidate. `PriceOverride.updatedAt` does not safely version the mutable route/model head or absence. Save and boot pricing changes must invalidate head/list generations; current administrator authority remains uncached. Already admitted calls must retain the selected card version, so a head refresh must not mutate a DTO held by accounting/retry work. Unknown/missing overrides need collection invalidation before negative caching.

**Count model:** find=1; ManagedPricing.list with T configured route/model targets=1 admin check+T finds. Bulk findAll(targets) could make2 total. ManagedPricing.set=8 statements including2 admin checks,lock,2 entry reads and3 save writes; final entry reload can be removed with committed return mapping.

**Uncertainty:** T is configured target count; no list timing measured. Pre-lock and under-lock admin checks have distinct freshness purpose and are not automatically redundant.

## R25 — JdbcUsageReportRepository

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/accounting/JdbcUsageReportRepository.java#L109) · [caller / contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/accounting/UsageQueryService.java#L1) · [schema / function](../../plowshare-server/src/main/resources/db/migration/V85__durable_inference_accounting.sql#L1)

Verdict: **confirmed-waste**; cache: **cache-blocked**.

`UsageQueryService`’s audit page maps each selected call through `attemptRows(...,51)`, then applies the response byte bound. This is explicit per-call N+1, present in earliest snapshot `f78e4f04`; original introduction is unknown. A batch must preserve the first50 plus truncation/cursor per call, not limit attempts globally. V85 already has UNIQUE `(call_id,attempt_number)`, matching attempt pagination/order.

Selector resolution also loops retained ownership snapshots and issues project existence/member/conversation reads per snapshot; repeated same project/log checks can be operation-local reused without skipping current-source revocation. Project ID text casts and totals/groups repeat scans are plan candidates. Result records are typed, but mutable calls/attempts and current memberships lack a unified report version. Source-independent history remains readable only under the documented current-source fence. Shared active report caching is blocked by accounting projection/recovery, ownership and membership writers; the health watermark sums journal sequences and is not a commit-order invalidation cursor. Closed immutable details require separately proven versions and disclosure checks.

**Count model:** Basic unfiltered calls page:1 account check+1 call page+N attemptRows+1 health=N+3, N returned page rows before byte bounding. Bulk per-call capped attempts can make4 total, or3 with cohesive call+attempt SQL. Additional selector checks are A, so general count=N+3+A.

**Uncertainty:** N+3 assumes no fleet/project/lineage selector branches and successful decoding. Attempt lookup still runs for rows later dropped by byte bounding. SQL/function time is unmeasured.

## R26 — JdbcUsageScopeRepository

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/accounting/JdbcUsageScopeRepository.java#L18) · [caller / contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/accounting/AccountingConfig.java#L1) · [schema / function](../../plowshare-server/src/main/resources/db/migration/V48__orchestrations.sql#L1)

Verdict: **confirmed-waste**; cache: **cache-blocked**.

`AccountingConfig` exposes this adapter to attribution. Named project resolution uses conflict UPDATE name=name solely to RETURNING the ID; existing projects therefore incur a write/row lock rather than a plain lookup. This shape is in earliest public snapshot `f78e4f04`, not a proven introduction commit. Project-name uniqueness already serves resolution.

Returned String identities are immutable values, but names can map to different IDs after deletion/recreation; no generation is supplied. Request-local known project IDs eliminate repeat resolution immediately. Shared name->ID heads are blocked until ProjectStore/project lifecycle/rename writers publish durable identity generations and current membership checks remain outside cache. Conductor mapping similarly depends on orchestration lifecycle. Replacing the upsert with a CTE that inserts-or-reads needs a concurrency proof: a snapshot may not see a row whose concurrent insert caused ON CONFLICT DO NOTHING. A procedure is only useful with that exact reconciliation contract.

**Count model:** project(global)=0 SQL; project(named)=1 UPSERT even when already present. conductorConversation=1 SELECT. Reusing an already resolved project identity makes0; lookup-first costs1 existing read but up to2+ reconciliation on create/race.

**Uncertainty:** The no-op update is certain, but row-lock/trigger cost is not measured. Lookup-first is not automatically fewer statements or race-equivalent.

## R27 — UsageExecutionStore

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/accounting/UsageExecutionStore.java#L138) · [caller / contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/RunUsage.java#L1) · [schema / function](../../plowshare-server/src/main/resources/db/migration/V87__immutable_run_ownership.sql#L1)

Verdict: **confirmed-waste**; cache: **operation-local**.

`RunUsage` walks both conversation and orchestration parent chains in Java. Every ancestor triggers a query even though the operation needs the complete path. Historical-parent discovery can repeat conversation/turn ownership reads; `resolve` also tests starting-turn ownership again before register. These shapes are present in earliest public snapshot `d38d55f4`; it is not proof of original introduction.

V87 primary key serves retained `(conversation_id,turn_ordinal)` ownership. Conversation ID/project/account reads enforce cross-tree invariants; any recursive replacement must preserve MAX_DEPTH/cycle rejection and historical calling-turn identity. `UsageAttribution` is an immutable value with copied lineages, while current conversation placement/owner is mutable and unversioned. Register’s post-insert read detects conflicting ownership on idempotent conflict, so only the new-insert branch is a RETURNING savings candidate. Share retained attribution as evidence only with current source disclosure fences. Cross-owner conversation/orchestration/project changes and registration lack a single durable lineage generation; operation-local identity maps are immediately feasible, shared head caching is not.

**Count model:** conversations(current) adds D ancestor SELECTs; orchestration lineage with D ancestors costs D+2 (conductor lookup,root parent lookup,D parent lookups). register always INSERT+read=2; reusing one immutable operation-local ancestry result can avoid repeated D traversals. A recursive loader can make each lineage1 statement with equivalent checks.

**Uncertainty:** D is ancestry depth excluding current node. Full start/source count also includes saved-turn,history and registration branches, so no single global count is asserted.

## R28 — JdbcOrchestrationAcceptance

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/JdbcOrchestrationAcceptance.java#L47) · [caller / contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/AcceptanceGate.java#L1) · [schema / function](../../plowshare-server/src/main/resources/db/migration/V60__verifier_caps_and_rules.sql#L1)

Verdict: **confirmed-waste**; cache: **cache-blocked**.

`AcceptanceGate`/`Orchestrations` consume an ordered set of `Registered` commands and requirements. Replace writes requirements, deletes the old set and executes an INSERT per command; unlike CodeWorkspace source publication this is not already batched. Reads are bounded by orchestration and order position. V60 supplies the composite set key and approval index, so indexing is not a substitute for the per-command write loop.

`Registered` copies argv and is an immutable snapshot. However replace deletes/recreates the entire set and neither the requirements nor command DTO carries a durable set revision; `set_at` can differ per command and is not commit ordering. Shared current-set cache is blocked by replace/deletion plus approval/run lifecycle. A cached registered approval cannot imply live consent. Locally reuse the loaded complete set across gate helpers, publishing a replacement only after outer transaction commit; empty sets require membership invalidation.

**Count model:** replace with C commands=2+C ordinary statements. One JDBC batch replaces C individual API executions (logical row writes remain C). find+requirements=2 reads; one complete typed set projection could make1 when both are needed.

**Uncertainty:** Driver batching wire count is not assumed1; no measured command-set cardinality. Combined read requires callers actually needing both.

## R29 — JdbcOrchestrationChecks

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/JdbcOrchestrationChecks.java#L31) · [caller / contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/StageChecks.java#L1) · [schema / function](../../plowshare-server/src/main/resources/db/migration/V54__orchestration_checks.sql#L1)

Verdict: **no-material-waste**; cache: **operation-local**.

`StageChecks`, hooks and gates read one retained check per orchestration. Set uses conflict protection and clear binds both orchestration and approval, preventing clearing a different decision. V54 orchestration primary key directly matches lookup; the query already selects check fields rather than a broad run record.

The typed `Check` is a value snapshot, but approval/consent and check existence are mutable without a general generation. It can be passed through one stage operation; it cannot be shared as execution authority. Set/clear, acceptance changes, approval answer/revoke and orchestration deletion affect the result and any negative lookup. No stored routine or extra index has a proven benefit. If current-check display caching is later added, timestamp alone is insufficient for stale-fill/reconnect recovery; maintain actual row/set versions and current consent fences.

**Count model:** set/clear/find each=1 statement; no nested SQL. A set followed by caller find would be2 and could be1 with RETURNING, but that composition is not established here.

**Uncertainty:** No repeated caller sequence or timing establishes a material issue; set conflict/refusal semantics constrain any returned projection.

## R30 — JdbcOrchestrationRecovery

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/JdbcOrchestrationRecovery.java#L24) · [caller / contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/Orchestrations.java#L1) · [schema / function](../../plowshare-server/src/main/resources/db/migration/V113__orchestration_resume.sql#L1)

Verdict: **no-material-waste**; cache: **no-cache**.

`Orchestrations` resumes an exact failed root/account/request UUID. The receipt key matches V113 `(account,request_id)`; the CAS also requires the observed ended-at timestamp and no parent. Rechecking the receipt after acquiring the request lock is not an arbitrary duplicate: another resume may have committed while waiting. A table-returning routine could reduce protocol trips, but cannot remove serialization or exact receipt reconciliation.

Receipt identity is immutable value evidence; current failed/live state is not. No shared cache may answer resume eligibility or absence. An existing receipt does not authorize another execution, and a terminal run may legitimately become running through this owner. Thus historical run display caches also need resume invalidation. Claim publishes run/receipt changes only after commit; current account, project and run disclosure remain necessary even if a retained receipt is reused locally.

**Count model:** received=1 SELECT; successful new claim=4 statements (request advisory lock,received,CAS update,receipt insert). Existing receipt inside claim=2; external preflight received+new claim=5 if caller takes that route.

**Uncertainty:** The extra preflight is only a conditional caller model; the in-lock receipt read is required for concurrency correctness.

## R31 — OrchestrationConcerns

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/OrchestrationConcerns.java#L29) · [caller / contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/AcceptanceChecker.java#L1) · [schema / function](../../plowshare-server/src/main/resources/db/migration/V77__the_acceptance_checker.sql#L1)

Verdict: **candidate**; cache: **cache-blocked**.

`AcceptanceChecker` reads concerns ordered by created time and parsed numeric ID. Raise calculates `MAX(substring(id FROM 2)::int)+1` and returns the DTO in the INSERT, already avoiding a post-write read. V77 key `(orchestration,id)` bounds the run, but lexical ID order is not numeric suffix order; it does not automatically remove expression aggregate/sort work.

`Concern` is a value snapshot, yet every answer/check/resolution updates state and `updated_at`. Timestamp equality/clock order is not a durable version guarantee. Current acceptance/person-consent cannot be served from stale cached state. Local set reuse is feasible; shared set caching is blocked until durable per-run generation covers every update/raise and run deletion, including insertion into previously empty sets. A numeric ordinal column/index is a measured-plan candidate rather than an immediate recommendation. The existing RETURNING behavior should be retained.

**Count model:** of/raise/each update=1 statement. raise scans existing same-run numeric suffixes inside1 SQL; N concern rows increase database work but do not create JDBC N+1. No statement reduction is proven.

**Uncertainty:** Concern cardinality, concurrent raisers and expression-sort cost are unknown. Existing run serialization may constrain allocation races outside this owner.

## R32 — OrchestrationStore

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/OrchestrationStore.java#L1072) · [caller / contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/StallSweep.java#L1) · [schema / function](../../plowshare-server/src/main/resources/db/migration/V48__orchestrations.sql#L1)

Verdict: **confirmed-waste**; cache: **cache-blocked**.

`StallSweep.sweep` iterates `store.running`; every `sweepOne` recursively scans descendant entry activity through quietSince and loads live children. Child DTOs can be large when the caller needs only whether an eligible child exists. This is an actual caller loop, present in earliest public snapshot `d38d55f4`, not proof of original introduction. V48/V51/V52 live/caller/child indexes address filters but cannot remove repeated trips. A set-based activity/child batch must retain the acceptance-stage exception rules and current mark CAS.

This owner also has numerous narrow getters and full run loads, but no blanket duplicate-count claim is made. `addMessage` already constructs its DTO without reload; counter mutations often already RETURNING. `OrchestrationRecord` copies stages but contains mutable lifecycle/counter/result fields with no general revision. Even ended runs can resume. Message delivered-at mutates too, so immutable bodies must be split from delivery state. Every state/question/cap/counter/placement/delivery/resume writer and child/message membership affects cached heads. Current admission, live child exclusion and stall-time state remain authoritative; shared run/head caches are blocked, while operation-local metadata reuse is immediate.

**Count model:** StallSweep reads running once, then quietSince+liveChildren for each of N runs:1+2N statements before approval/mark work. Per-run stalledSince/mark/clear and approval queries are additional. Bulk activity+child projections could reduce that component to3 bounded statements.

**Uncertainty:** No stall-sweep latency measured; N is running records visited. Final reporting/delivery and exception branches are excluded from the component count.

## R33 — RecordStore

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/RecordStore.java#L186) · [caller / contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/RecordKeeper.java#L1) · [schema / function](../../plowshare-server/src/main/resources/db/migration/V59__orchestration_record.sql#L1)

Verdict: **no-material-waste**; cache: **operation-local**.

`RecordKeeper` already appends under lock-before-snapshot ordering and returns ordinal; settlement returns watermark in the UPDATE. Paging intentionally reads reach then limits rows to that reach, using limit+1 instead of a full count. Those two reads are not accidental duplicate hydration. V59 `(root,ordinal)` and `(root,kind,ordinal)` keys match page order; run/conversation filters are separate index candidates only with evidence.

`RecordRow` is an immutable Java snapshot, but a tool-call row’s detail/output can settle later at the same ordinal. Consequently ordinal is not a universal content version. Reuse a known Tree/Place locally; sharing a settled content projection requires an immutable-settled identity or checksum and current root-owner disclosure. Append and settle invalidate root/run/conversation page membership/content, including captured historical pages where a row settles after capture. MAX ordinal alone does not detect such updates or establish commit-aware invalidation recovery.

**Count model:** append=2 statements per attempt (advisory lock+INSERT MAX RETURNING); settle=1; through=1; page=2 (reach+bounded rows); treeOfRun=1 recursive SELECT; placeOf found=2 (conversation placement+treeOfRun).

**Uncertainty:** Retrial count and per-root journal size are unknown. Reach read has explicit concurrent-append consistency purpose.

## R34 — JdbcScriptStore

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/scripted/JdbcScriptStore.java#L20) · [caller / contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/scripted/ScriptStore.java#L1) · [schema / function](../../plowshare-server/src/main/resources/db/migration/V84__scripted_orchestrations.sql#L1)

Verdict: **confirmed-waste**; cache: **operation-local**.

`ScriptStore.requireRecoverable` has already loaded latest Step when it invokes failedDelegate, whose implementation calls latest again. This is a concrete fetch-through-ID repetition at the interface/implementation seam. The previous completed-state read in prepareNext is different: it checks source hash and expected result before advancing the journal, so it has an integrity reason. V84 conversation/sequence primary key supports exact and latest reads. Delegate result intentionally checks whether more than one answer exists; adding LIMIT1 would hide corruption.

`Step`/Command are immutable value records with bounded strings, but raw/result/started/arguments mutate and retries clear started/raw fields at the same sequence/hash. Shared whole-Step caching is blocked by missing generation. Prepared command or fully completed receipt content can be reused locally; raw paid results must survive post-hook failure. Started execution, readiness-observer retry classification and no-replay reconciliation remain authoritative. Conversation/turn writes also change failed/delegate projections; source hash alone does not version those dependencies.

**Count model:** prepare first step=1 INSERT; later step=1 previous receipt read+1 INSERT=2. failedDelegate eligible=2 (latest+child query). requireRecoverable uncertain agent_run with no failed child or answer=4:latest,latest again,child,delegateResult; passing same verified Step can reduce to3 within one valid snapshot.

**Uncertainty:** Readiness/Wait/Finish/completed branches exit earlier; counts exclude sandbox CPU. Concurrent step changes require a defined snapshot before deleting the second read.

## R35 — JdbcOutgoingRepository

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/outgoing/JdbcOutgoingRepository.java#L99) · [caller / contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/outgoing/OutgoingWork.java#L1) · [schema / function](../../plowshare-server/src/main/resources/db/migration/V92__outgoing_work.sql#L1)

Verdict: **confirmed-waste**; cache: **cache-blocked**.

`OutgoingWork` claim loads a locked ID, rehydrates full Work for action selection and reloads after update; report similarly separates lock-only lookup from pre-state hydration. These shapes appear in earliest public snapshot `f78e4f04`; original introduction is unknown. Narrow locked pre-state and typed RETURNING can remove the extra reads while preserving worker/revision ownership and exact remote task/context identity. V92 claim index covers account/state/lease, not automatically project-nullability/peer/created-order; split queued versus expired observation branches is an unmeasured plan candidate.

`Work` is a value record with JSON-decoded typed payloads, but its revision advances only claim/report. Cancellation changes cancel_requested and sometimes state without incrementing revision; project conversation deletion can also change mapped data. A revision-only display cache would therefore serve stale values. Add a separate display/change generation rather than blindly incrementing execution revision and invalidating legitimate worker reports. Peers additionally expire by wall clock. Current claim/lease/peer/send authority and UNKNOWN no-replay semantics remain uncached. Multiple writers require durable after-commit entity and account/project/peer membership invalidation.

**Count model:** Named-project successful claim=1 project read+1 locked ID select+1 get+1 update+1 get=5; global=4. report=locked ID,get,update,get=4. send new=5+P+C (P project0/1,C conversation check0/1); cancel=3. Returning pre-state under lock plus post-update DTO can reduce claim by2 and report by2, preserving semantics.

**Uncertainty:** Send count includes request advisory lock,receipt lookup,peers SELECT,INSERT,get, but not validation-only work. No wire/DB latency measured.

## R36 — JdbcProcRepository

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/proc/JdbcProcRepository.java#L394) · [caller / contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/proc/ProcRuntime.java#L1) · [schema / function](../../plowshare-server/src/main/resources/db/migration/V85__durable_inference_accounting.sql#L1)

Verdict: **confirmed-waste**; cache: **cache-blocked**.

`ProcRuntime`/`ProcAudit` expose a second audit N+1: calls loops returned calls and selects attempts per call, max100 each. Blame identifies `780b143086319e8196592539a888152c31a6ab36` introducing this audit code. Its predicate casts UUID `call_id` to text, so the existing V85 UUID `(call_id,attempt_number)` unique key does not directly match the expression; migrations contain no matching text-expression index. Bind a UUID rather than immediately adding a duplicate index.

Conversation total/page each build the global recursive tree. Page enrichment is already MATERIALIZED before lateral aggregates, so an analysis must not claim all conversations are enriched before LIMIT. Narrow selected roots or one candidate snapshot may reduce work only after custody/lifecycle equivalence checks. ProcViews records defensively copy collections, but snapshotAt is wall time, not a source version; views combine local running sets, jobs, entries, accounting, information and expiring leases. Broad shared caching is blocked by absent unified generation and current visibility fences. Operation-local results are reusable; active queues/liveness remain authoritative.

**Count model:** conversations=3 statements (account gate,total tree,page tree); calls with N selected page rows=2+N (account,call page,N attempt queries); tools=2. Batched per-call capped attempts can reduce calls to3 total. conversations can be2 if one typed query returns total+page without losing empty-page totals.

**Uncertainty:** Correlated/lateral subqueries add database work within statements. N is bounded page length; full recursion/enrichment cost requires EXPLAIN and cardinalities.

## R37 — JdbcProviderCredentialRepository

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/providers/JdbcProviderCredentialRepository.java#L59) · [caller / contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/providers/OwnedProviderConnections.java#L1) · [schema / function](../../plowshare-server/src/main/resources/db/migration/V139__provider_credentials.sql#L1)

Verdict: **no-material-waste**; cache: **no-cache**.

`OwnedProviderConnections` reads bounded connection lists and optional row-locked connection/credential records. Credential CAS and receipt uniqueness are deliberate, not preflight existence/refetch. V139 `(project_id,id)` index, `(connection_id,subject)` key and unique state_hash match those current shapes; project-name joins use project uniqueness. Wildcards carry secret storage fields in credential-only projections, but no evidence shows those fields exposed to non-secret callers.

Connection revision and credential generation are UUID identities returned with typed metadata. They support exact conflict fencing, but are not commit-order recovery cursors. Stored contains encrypted material, nonce and authorization intent and should not enter the general shared cache. Non-secret revisioned connection descriptors could later share immutable values, while enabled/deleted/current subject authorization remains live. Update/store/revokeAll, project-deletion revocation trigger, account and receipt writers affect dependencies. No globally cached credential, state hash or grant may bypass locking, generation CAS or revocation.

**Count model:** list/connection/credential/store/revokeAll/authorization/commit/receipt each=1 statement; project lock adds1 where requested. No nested mapper SQL or per-row list queries in this owner.

**Uncertainty:** Caller rehydration and JSON definition size are unmeasured; no reduced count is established.

## R38 — UnionStore

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/union/UnionStore.java#L82) · [caller / contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/union/UnionFrames.java#L1) · [schema / function](../../plowshare-server/src/main/resources/db/migration/V46__project_union.sql#L1)

Verdict: **confirmed-waste**; cache: **cache-blocked**.

`UnionFrames` status calls open(...).size, decoding every conflict path/blob/author/time although only the count is used. `UnionRouting` also independently resolves the union in providers, mirrorPlace and conflictNote; that is a conditional composition reuse opportunity. V46 composite conflict key already supports MAX(n) and project/n order; partial open index serves filtering, so another same-key index is unsupported without a plan.

Union and Conflict are immutable Java values, but project union_since/hidden/exclusions and conflict resolution change without a source version. Gate LIVE/session state belongs to an in-memory lock, not this DTO. Disable deletes conflicts then clears union_since in two statements; the visible caller uses UnionGate.locked, not a database UnitOfWork, so crash atomicity is an explicit unresolved boundary rather than a proven transaction. Shared heads are blocked until ProjectStore/UnionStore/conflict writers have durable generations and current gate/authority fences. No cache can decide push or mirror-write admission.

**Count model:** find/enabled/enable/setHidden/open/resolve each=1; disable=2 writes; openConflict=1 per attempt, up to4 attempts. UnionFrames status loads find+all open conflicts merely for size=2; count-only would remain2 but avoid full bodies/blob IDs. K separate routing mirrored calls costK finds; local shared context can make1.

**Uncertainty:** K>1 in one routed operation is not traced. The status count overfetch is direct source evidence; conflict/list cardinality is unknown.
