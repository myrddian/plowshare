# Follow-on slice 15: Semantic-query budgets and read authority

Status: proposed implementation scope, 18 October 2026. Planning only, requested
after the bounded slice 14 completion. No runtime, configuration, schema, SDK or
deployment changes are implemented by this document. Source baseline:
`dc9db60e18d3566c57ded528a64a012da7e2be09`.

The original [roadmap](../authority-substrate-implementation-plan.md) contained
14 slices. This is a separately scoped follow-on, not unfinished work silently
added to slice 14. Policy comes from [ADR 0030](../decisions/0030-authority-substrate-and-explicit-scopes.md#operation-specific-budgets-and-accounting)
and the [role matrix](../role-scope-permissions-proposal.md): read-side inference
uses the search service's configured operation budget; a READER may request the
service without receiving arbitrary RUN_MODEL authority.

## Outcome and dependencies

Restore the existing supported document query-embedding paths through retained,
bounded SYSTEM search authority and an explicitly configured per-query allowance.
Keep current caller selection, root/input/privacy filtering, result shapes,
ranking, coverage and retained-input obligations. Missing or exhausted allowance,
invalid authority or an uncertain submitted attempt must never become a successful
empty search or an automatic redispatch.

**Depends on:** implemented slices 07–08, 13 and the
[slice 14 completion](14-completion-plan.md). Reuse their dispatch/claim and
embedding validation contracts; do not lend document-preparation authority to a
query. Implementation needs a subsequent explicit instruction.

## Source findings

| Current source | Verified behavior and consequence |
| --- | --- |
| [RetrievalService](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/documents/RetrievalService.java) | `search`, `within`, `retrieve`, `rank` and `stance` reach embedding dispatch. `scoped`, `audited` and `checkingConfiguration` copy service state; all must preserve any new query binding. Usage attribution supplies no allowance or execution authority. |
| [DocumentFrames](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/DocumentFrames.java), [InformationFrames](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/InformationFrames.java), [DocumentController](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/api/DocumentController.java) | Existing authenticated selection and response contracts reach the same retrieval service. Information search/rank accept their existing corpus/filter/view context. The explicit Global account selector still refuses these operations. |
| [InformationTool](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/InformationTool.java), [DocumentTools](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/DocumentTools.java), [JobRuntime](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/JobRuntime.java) | Run-bound tools derive their reader from the server-owned home/account and record consumed inputs. Runtime composition has the parent admission, cancellation and call identity; the tools do not currently carry a query allowance. Bind the search service internally rather than exposing authority or payer fields to model arguments. |
| [DispatchingEmbeddingClient](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/DispatchingEmbeddingClient.java), [DispatchingDualEmbeddings](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/embedding/DispatchingDualEmbeddings.java) | Unbound query requests have attribution but no retained claim. The preparation-scoped dual client explicitly rejects queries. Dual retrieval captures one active corpus slot and batches stance's query/negation together; preparation's two-slot write behavior is not query behavior. |
| [SystemOperations](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/SystemOperations.java), [BoundSystemOperations](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/BoundSystemOperations.java) | No declared query purpose exists. Embedding claim carriage permits DOCUMENT_PREPARATION only. The immutable SYSTEM evidence table also allowlists purposes in V158. A Java enum change alone cannot activate this path. |
| [JdbcInferenceAdmissions](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/accounting/JdbcInferenceAdmissions.java), [Budget](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/Budget.java) | Existing operation/source binding, unique source/ordinal, one submission and accounting correlation are reusable. They do not configure or supply a query allowance. `Budget.retained` can restore a persisted identity/counter, but is not itself persistence. |
| [DocumentStore](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/documents/DocumentStore.java), [JdbcEmbeddingWorkRepository](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/embedding/JdbcEmbeddingWorkRepository.java) | Scoped SQL applies current selection/root/input policy before ranking/paging; the dual read fences its captured profile. Preserve both independently of SYSTEM inference authority. |
| [DocumentsProperties](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/documents/DocumentsProperties.java) | Ingest and ask allowances exist, but neither is a search budget. Token ceilings, embedding batch size and queue capacity cannot substitute. |

The current `search` implementation embeds before choosing lexical/vector/fused
results, including `Mode.LEXICAL`. Its empty searchable-corpus shortcut avoids a
call, but other retrieval methods do not all share that shortcut. This plan
counts actual dispatcher requests; it does not assume every read is free or add
a lexical-only optimization. Model failures retain their existing explicit error
semantics; they do not trigger an unrequested fallback to lexical results.

## Supported boundary

| Entry point | Planned treatment |
| --- | --- |
| Existing `information.search` and `information.rank` | Bind one search operation to the admitted Personal/Project/Shared selection, corpus, facets/view and optional revision. Preserve existing defaults, limits and payloads. |
| Existing `document.search`, `document.retrieve`, `document.rank`, `document.stance` and their current HTTP equivalents | Use the same internal admission and allowance capability through the authenticated scoped reader. Preserve each surface's existing selectors; do not add HTTP scope fields or SDK methods. |
| Existing `information_read` search/rank and `document_search` agent tools | Bind the service at runtime composition to the existing executor/subject, retained run, call identity, home, cancellation and result-input ledger. Recheck parent validity and existing target limits; model arguments cannot select another account, budget or scope. |
| Internal `within` calls from ask/deliberation | Remain fenced unless supplied a separately supported binding. Do not silently create a standalone search allowance inside an existing ask job. Ask-stage budgeting and that consumer's authority/lifecycle are separate work. |
| Global-held publicly readable documents | Discoverable only through an existing supported Shared/includeShared path and current GLOBAL_READ policy. No new `scope.kind=account` search support, global conversation or Global execution grant. |

The information surface already supports code as a corpus. Preserve its existing
slot choice and filters where the requested operation is supported; do not add
new code navigation or infer prose summary/stance support for code. Existing
syntax outline/symbol reads remain independent of query inference.

## Proposed budget contract

Add one explicit deployment configuration for this document search service:
`plowshare.documents.query-budget` (proposed environment spelling
`PLOWSHARE_DOCUMENT_QUERY_BUDGET`). This is a model-call allowance per admitted
logical query, not a monetary pool or an allowance per document/vector/result.

- Absent/zero means query inference is disabled with a clear configuration
  refusal when dispatch would be required. Negative or malformed values fail
  configuration validation. No implicit one-call fallback or unlimited value.
- A positive configured integer is captured once at query admission. Current
  successful single-query and batched-stance paths need one actual embedding
  request; an operator may explicitly configure `1`. The plan does not select an
  enabled production value or add a caller `maxModelCalls` parameter.
- Retain one source UUID, configured total and monotonic spent count for the
  logical query. All internal helper calls share it. Actual requests consume
  claims, including a failed/uncertain attempt once charged; vector count, hits,
  lexical ranking and local reuse of captured vectors do not create new charges.
- Snapshot configuration for the query. Later configuration changes affect new
  admissions, not in-flight counters. No live per-query limit-edit endpoint,
  automatic increase/refund, document allowance borrowing or wallet for SYSTEM.
- Agent-invoked search is the same declared read service and uses this search
  operation source, as ADR 0030 specifies. Record its parent/call relationship
  and preserve the parent's validity, tools and target sandbox. This does not
  allocate a new agent-child/orchestration budget or alter their shared-budget
  rules. A missing parent binding must refuse rather than masquerade as a direct
  authenticated search.
- A per-operation cap is not a daily tenant/account spending quota or a monetary
  reservation. Existing authentication, tool/loop limits, capacity, accounting and
  rate controls still apply. Shared spending-pool mechanics remain deferred.

## Authority, targets and disclosure

Declare one closed internal purpose, provisionally `DOCUMENT_QUERY`, for query
embedding only. SYSTEM retains its intrinsic authority; no agent receives the
SYSTEM role and no caller gains ADMIN, WRITE, CONVERSATION_CREATE or RUN_MODEL.
The initiator/acting subject remains distinct from the actual executor and budget.

Retain a typed query admission containing the authenticated recipient context,
immutable scope identities/incarnations, operation kind, optional revision,
corpus/filter/view, bounded request options, model/profile identity and input
digest. For run-bound use, retain the existing parent/call binding as well. The
provider receives only the validated query batch with the owning client's exact
task preprocessing; it receives no tools or authority to read document bodies.
Bind the actual final model/input batch, including stance's derived negation.

The service envelope may use the explicit administration scope for this
deployment-owned read service, including Shared selections that span homes. Its
authority scope is not the data selection. Never use legacy `Home.global()` or a
null project to stand in for the queried account/corpus or grant access. Retain
the exact data selection separately and enforce its resource policy. Do not
re-resolve a deleted project by a reused name.

Admission and queued dispatch recheck recipient/credential validity, selection
admission, explicit target readability, the live SYSTEM purpose and the owning
run/cancellation fence when present. Queries are short-lived read requests;
recipient validity is a disclosure/request-lifetime condition, not borrowed user
RUN_MODEL authority. This does not change independent document preparation's
creator-independent continuation rule or reassign its budget.

After inference, apply current root/input/privacy/reference policy to candidate
selection, metadata, coverage and returned content. Recheck explicit targets and
recipient access before returning results and before recording tool inputs.
GLOBAL_READ never discloses private SYSTEM logs, quarantined data, restricted
inputs or reference-only bodies. Keep existing acting-consumer restrictions;
this slice adds no operation to the acting whitelist.

The final policy-checked repository read/disclosure fence is the authorization
linearization point. A revocation/withdrawal committed before that fence must
prevent the affected disclosure. Use existing policy locks in a short transaction
where required to order the race; no database transaction may enclose provider IO.
A later revocation governs later reads; already delivered bytes cannot be recalled.
Do not claim policy-atomic result assembly merely because each earlier SQL query
was independently filtered.

## Smallest implementation and reuse

1. Define a narrow internal query-admission/allowance interface and immutable DTOs.
   Separate direct authenticated requests from run-bound requests explicitly.
   Bind it to scoped retrieval copies at the existing composition boundaries;
   an unbound production reader must not silently gain a standalone allowance.
2. Add the validated configuration and capture it at admission. Add a specialist
   query repository only for the missing retained source/counter, immutable query
   binding and bounded attempt lifetime. Reuse `Budget.retained`,
   `SystemOperationRecords`, `operation_inference_budgets`, `inference_admissions`
   and existing accounting correlations. No duplicate inference/cost ledger.
3. Extend the closed purpose allowlist and embedding claim carriage for the query
   purpose. Query authorization must be distinct from preparation's writable
   target/publication permit; possession of one must never enable the other.
   Reject chat/stream authorization for the query purpose as well; adding the
   enum must not make existing generic SYSTEM chat carriage a second dispatch lane.
   Reuse exact request validation, tokenizer limits and dispatcher queue checks.
4. Atomically validate the query/source, advance its counter and retain the
   inference claim before provider submission. Rechecks consume no extra claim.
   Reuse the unique source/ordinal and one-submission constraints. Closing or
   invalidating an attempt prevents further claims without erasing spending.
5. Carry the binding through both legacy and dual query clients. Pin the selected
   active profile once, use its query preprocessing, and reuse captured vectors
   inside `dualRead` without another dispatch. Preserve the profile's fenced
   database read; a raced activation refuses rather than re-embedding silently.
6. Wire the existing frame/HTTP/tool consumers listed above and their final
   read/input fences. No ranking redesign, public API, SDK graph expansion or
   tool-grant change is needed. Update manuals/configuration documentation to
   distinguish preparation availability from search availability.

Any needed schema change is a new Flyway migration selected against the current
integration baseline. Do not edit V157, V158, V160 or V161 or reserve a version in
this plan. Retained query records must not cascade-delete historical inference or
cost evidence when an account/project/request ends. Do not backfill old attribution
as valid query authority or fabricate historical spending.

## Failure, lifetime and recovery

- Validate the authenticated selection, question, target and tokenizer/model
  bounds before paid dispatch. Missing authority/configuration, invalid input and
  exhausted allowance prevent a provider call. Preserve existing refusal/error
  shapes and distinguish inability to search from a valid empty result.
- Capture one internal logical-query identity. Within that attempt, helpers,
  concurrent claims and queue callbacks cannot mint a replacement source. A known
  repeated internal/tool invocation must reuse its retained binding or refuse;
  changed selection/query under that identity conflicts before spending.
- Reuse available runtime invocation identity for tool calls. Existing read APIs
  do not universally supply a durable idempotency key. Do not claim exactly-once
  search across client resubmission or turn a WebSocket envelope ID into a new
  public receipt contract. A separately submitted unkeyed read is a new query.
- No transparent provider retry, HTTP fallback, alternate-model redispatch or
  automatic retry after an activation race, disconnect or unknown outcome.
  Reconcile existing claim/accounting evidence; never replace the budget or
  refund a charge because an answer was lost. A known completed internal read
  may be repeated with already captured vectors only while those are available
  and after current access/profile checks; add no durable vector/result cache.
- Close synchronous query validity on completion/failure/cancellation. After
  restart, retained claims remain evidence, not permission to recreate the live
  request or its recipient session. Bind the live capability to the existing
  request/run lifetime and invalidate it in the owning completion/failure path;
  persisted evidence alone cannot reconstruct that capability. An abandoned
  record is not a claimable replacement request. If an entry point cannot supply
  a trustworthy lifetime/recipient fence, leave it unactivated and report that
  concrete gap. No generic query job, resumable search, result-retrieval API,
  extra TTL configuration or scheduler is introduced.
- Store hashes and necessary typed identifiers for authority evidence, not raw
  query text, embeddings, source content or credentials in new receipts/logs.
  Preserve existing private accounting/diagnostic access rules.

## Verification and completion gate

Use mocks/fakes by default for the new policy service and both embedding clients:

- Human and service READER/GLOBAL_READ can use supported reads without their own
  RUN_MODEL. Invalid/expired recipients, wrong targets, scopes or parent bindings
  cannot dispatch. No client label or accounting actor can supply authority.
- Missing/zero/invalid configuration, exhausted allowance, changed model/input,
  closed attempt and invalid SYSTEM purpose refuse before provider IO. Test the
  last valid claim, one charge per actual batch, helper/copy propagation and
  stance's two vectors in one request. Existing empty-corpus shortcuts cost zero;
  current lexical-mode behavior is accounted for, not silently optimized away.
- Search, retrieval, rank and stance retain existing response shapes, coverage,
  selection filters and ranking in legacy and dual modes. Cover code slot choice
  on supported information operations; reject unsupported Global account targets.
- Tool tests retain parent/cancellation/target limits, original call identity and
  input dependencies, with no model-selectable authority or budget fields. Ask,
  archive recall, preparation, repair and chat/stream dispatch cannot borrow a
  query permit.
- Queue revocation, withdrawal, profile activation races and provider failures
  preserve incurred spending, refuse stale output and never repeat dispatch.
  New records/logs expose no raw private query/source text.

Selected `@Tag("full-db")` tests are justified only for the new migration,
atomic counter/claim transaction, unique ordinal/single-submission constraints,
retained evidence, real policy filtering and disclosure/revocation races. Reopen
repositories to prove a claim/source cannot be reset or rebound; do not restart
provider calls to manufacture recovery evidence. Reuse focused coverage from
[InferenceAdmissionPersistenceTest](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/llm/accounting/InferenceAdmissionPersistenceTest.java),
[RootDocumentPolicyPersistenceTest](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/information/RootDocumentPolicyPersistenceTest.java),
[RetrievalServiceTest](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/documents/RetrievalServiceTest.java),
[DualEmbeddingsTest](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/embedding/DualEmbeddingsTest.java),
and existing frame/controller/tool tests. A test name is not evidence it ran.

Acceptance: a scoped human ADMIN admits and prepares a Global manual document
using slice 14's source; an authenticated human and service reader search for it
through an existing supported Shared/includeShared path. Prove distinct retained
preparation and query sources, SYSTEM query execution, private-data filtering and
no Global conversation or writer authority. Revoke the audience/recipient or
withdraw the document during the query and verify no stale disclosure. Separately
prove an admitted agent tool search retains its parent sandbox and consumed inputs.
Use deterministic providers for database acceptance; any live deployment/provider
exercise requires separate authorization and must be reported separately.

After implementation run focused behavior tests, `format`, `formatCheck` and
root `check`; run only the named necessary PostgreSQL cases with `-PfullDb --tests`
after stating their SQL/transaction reason. Verify existing request/response
graphs stay unchanged; run `sdkCheck` for the affected existing public query
contracts without asserting SDK/client feature parity. Record actual commands,
results and limits in this document before declaring completion.

## Exclusions and rollout

No memory/passage/archive recall or writes, independent embedding maintenance,
ask/deliberation budgeting, new reranker/model navigation, Global account search
selector, type registry, group/tenant/ACL editor, broader acting targets, nested
orchestration allocation, transient-child recovery, monetary reservation/spending
pool or new public controls. Standard roles remain the routine access mechanism;
no new role or per-tool RBAC layer.

Keep other inference consumers fenced until their own declared purpose/source
contracts exist. Existing compatibility and curation gates remain. Ship configuration
documentation with an explicit enablement choice; do not silently activate query
spending for every existing deployment. Setting the query budget to zero fences
new inference without deleting prior evidence. No data move, credential issuance,
deployment, old-binary rollback or live schema migration is authorized here.

## Planning verification

Source and existing architecture records were inspected at the baseline above.
This task changes documentation only. No Java, database, SDK, provider or live
integration tests were run for this plan; the acceptance checks above are future
implementation requirements.
Changed documentation file links and `git diff --check` passed.
