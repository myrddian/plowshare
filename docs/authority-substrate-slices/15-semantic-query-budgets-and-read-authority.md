# Follow-on slice 15: SYSTEM embedding foundation and initial search consumers

Status: implemented under the subsequent explicit instruction, 18 October 2026.
This records the revised shared foundation and initial consumer boundary after
slice 14. No new public API or deployment is included. Planning source baseline:
`dc9db60e18d3566c57ded528a64a012da7e2be09`.

The original [roadmap](../authority-substrate-implementation-plan.md) contained
14 slices. This is a separately scoped follow-on, not unfinished work silently
added to slice 14. Policy comes from [ADR 0030](../decisions/0030-authority-substrate-and-explicit-scopes.md#operation-specific-budgets-and-accounting)
and the [role matrix](../role-scope-permissions-proposal.md): read-side inference
uses the search service's configured operation budget; a READER may request the
service without receiving arbitrary RUN_MODEL authority.

Clarification agreed after the first draft: semantic embedding is a shared SYSTEM
foundation, not a document-specific permission or execution service. This revision
supersedes the draft's `DOCUMENT_QUERY` purpose and document-owned query-budget
configuration. The first consumer rollout remains bounded; the foundation must
also serve existing preparation and be reusable by memory and other admitted
operations without another embedding permission system.

## Outcome and dependencies

Establish shared, bounded SYSTEM embedding execution over the existing dispatcher,
then connect the existing preparation path and selected document-search consumers.
Each consumer supplies its admitted operation, scope/validity binding and retained
operation-budget source. The foundation neither grants the caller RUN_MODEL nor
chooses a payer or resource audience. Initial search adoption adds the missing
configured search-operation allowance and retained query admission.
Keep current caller selection, root/input/privacy filtering, result shapes,
ranking, coverage and retained-input obligations. Missing or exhausted allowance,
invalid authority or an uncertain submitted attempt must never become a successful
empty search or an automatic redispatch.

**Depends on:** implemented slices 07–08, 13 and the
[slice 14 completion](14-completion-plan.md). Reuse their dispatch/claim and
embedding validation contracts. Sharing execution machinery does not allow a
claim from one operation to be reused by another. Implementation and verification
are recorded below.

## Source findings

| Current source | Verified behavior and consequence |
| --- | --- |
| [RetrievalService](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/documents/RetrievalService.java) | `search`, `within`, `retrieve`, `rank` and `stance` reach embedding dispatch. `scoped`, `audited` and `checkingConfiguration` copy service state; all must preserve any new query binding. Usage attribution supplies no allowance or execution authority. |
| [DocumentFrames](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/DocumentFrames.java), [InformationFrames](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/InformationFrames.java), [DocumentController](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/api/DocumentController.java) | Existing authenticated selection and response contracts reach the same retrieval service. Information search/rank accept their existing corpus/filter/view context. The explicit Global account selector still refuses these operations. |
| [InformationTool](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/InformationTool.java), [DocumentTools](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/DocumentTools.java), [JobRuntime](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/JobRuntime.java) | Run-bound tools derive their reader from the server-owned home/account and record consumed inputs. Runtime composition has the parent admission, cancellation and call identity; the tools do not currently carry a query allowance. Bind the search service internally rather than exposing authority or payer fields to model arguments. |
| [DispatchingEmbeddingClient](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/DispatchingEmbeddingClient.java), [DispatchingDualEmbeddings](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/embedding/DispatchingDualEmbeddings.java) | Unbound query requests have attribution but no retained claim. The preparation-scoped dual client explicitly rejects queries. Dual retrieval captures one active corpus slot and batches stance's query/negation together; preparation's two-slot write behavior is not query behavior. |
| [SystemOperations](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/SystemOperations.java), [BoundSystemOperations](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/BoundSystemOperations.java) | No declared query purpose exists. Embedding claim carriage permits DOCUMENT_PREPARATION only. Extend the shared SYSTEM machinery with bounded query-operation evidence rather than constructing a document-specific embedding authority. The immutable SYSTEM evidence table also allowlists purposes in V158. A Java enum change alone cannot activate this path. |
| [BoundPreparationEmbeddings](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/BoundPreparationEmbeddings.java), [EmbeddingAuthorization](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/embedding/EmbeddingAuthorization.java) | Preparation already binds exact model/input, retained allowance, lease/generation and output targets. Its permit combines dispatch authorization with preparation publication checkpoints. Reuse the dispatch portion through the foundation; keep publication/lease semantics with the preparation consumer. |
| [JdbcInferenceAdmissions](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/accounting/JdbcInferenceAdmissions.java), [Budget](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/Budget.java) | Existing operation/source binding, unique source/ordinal, one submission and accounting correlation are reusable. They do not configure or supply a query allowance. `Budget.retained` can restore a persisted identity/counter, but is not itself persistence. |
| [DocumentStore](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/documents/DocumentStore.java), [JdbcEmbeddingWorkRepository](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/embedding/JdbcEmbeddingWorkRepository.java) | Scoped SQL applies current selection/root/input policy before ranking/paging; the dual read fences its captured profile. Preserve both independently of SYSTEM inference authority. |
| [DocumentsProperties](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/documents/DocumentsProperties.java) | Ingest and ask allowances exist, but neither is a search budget. Token ceilings, embedding batch size and queue capacity cannot substitute. |

The current `search` implementation embeds before choosing lexical/vector/fused
results, including `Mode.LEXICAL`. Its empty searchable-corpus shortcut avoids a
call, but other retrieval methods do not all share that shortcut. This plan
counts actual dispatcher requests; it does not assume every read is free or add
a lexical-only optimization. Model failures retain their existing explicit error
semantics; they do not trigger an unrequested fallback to lexical results.

## Shared foundation and consumer responsibilities

| Responsibility | Owner |
| --- | --- |
| Authenticate the requester; authorize the requested search, recall, preparation or other operation; select permitted inputs/targets | The consuming operation, using current scoped RBAC/resource and lifecycle checks. No new embedding role or tool-level ACL. |
| Resolve and retain the configured budget source and operation validity | The consuming operation's admission. Search uses its configured search-operation source; preparation keeps its retained revision allowance. The foundation never creates an allowance because it received an embedding request. |
| Perform the exact admitted embedding batch under SYSTEM RUN_MODEL authority | One shared internal embedding execution capability over the existing legacy/dual clients and dispatcher. It validates the operation binding, task mode, model/profile, final inputs, claim and live validity. It exposes no tools, arbitrary model execution or document-reader interface. |
| Filter and disclose search/recall results; publish prepared vectors | The consumer's existing read policy or fenced writer. Returning a vector does not authorize a resource read, mutation, audience change or output publication. |

The internal contract accepts a typed admitted operation and embedding task,
including QUERY versus DOCUMENT preprocessing. These are input/purpose boundaries,
not account permissions. Closed operation-purpose evidence remains useful for
sandboxing and audit; it must not become a separate role/grant system per feature.
Keep model/profile resolution in its existing owner and claim persistence in
specialist repositories. Do not add a second provider runtime, generic execution
framework or externally callable embedding endpoint.

## Initial consumer boundary

| Entry point | Planned treatment |
| --- | --- |
| Existing document preparation | Use the shared execution contract through its existing operation adapter. Preserve the revision allowance, exact lease/generation/targets, pending-claim replay fence and atomic publication. This is a reuse/refactoring boundary, not a new preparation capability or budget. |
| Existing `information.search` and `information.rank` | Bind one search operation to the admitted Personal/Project/Shared selection, corpus, facets/view and optional revision. Preserve existing defaults, limits and payloads. |
| Existing `document.search`, `document.retrieve`, `document.rank`, `document.stance` and their current HTTP equivalents | Use the same internal admission and allowance capability through the authenticated scoped reader. Preserve each surface's existing selectors; do not add HTTP scope fields or SDK methods. |
| Existing `information_read` search/rank and `document_search` agent tools | Bind the service at runtime composition to the existing executor/subject, retained run, call identity, home, cancellation and result-input ledger. Recheck parent validity and existing target limits; model arguments cannot select another account, budget or scope. |
| Internal `within` calls from ask/deliberation | Remain fenced unless supplied a separately supported binding. Do not silently create a standalone search allowance inside an existing ask job. Ask-stage budgeting and that consumer's authority/lifecycle are separate work. |
| Global-held publicly readable documents | Discoverable only through an existing supported Shared/includeShared path and current GLOBAL_READ policy. No new `scope.kind=account` search support, global conversation or Global execution grant. |
| Memory recall and other embedding consumers | The same foundation is available for later admission adapters. Activation still needs that consumer's supported scope, lifetime, budget and read/write contract; this plan does not activate them merely because shared execution exists. |

The information surface already supports code as a corpus. Preserve its existing
slot choice and filters where the requested operation is supported; do not add
new code navigation or infer prose summary/stance support for code. Existing
syntax outline/symbol reads remain independent of query inference.

## Initial search consumer's budget contract

The admitted semantic-search operation uses explicit configuration,
`plowshare.embeddings.search-budget` (environment spelling
`PLOWSHARE_SEMANTIC_SEARCH_BUDGET`). The search admission adapter reads it;
the foundation does not. This replaces the draft's document-specific key and
applies consistently across the initial frame/HTTP/tool search consumers.
It is a model-call allowance per admitted logical query, not a monetary pool,
a universal embedding allowance or an allowance per document/vector/result.
Preparation continues using its existing source; later consumers must explicitly
bind their own supported operation source rather than inherit this setting.

- Absent/zero means query inference is disabled with a clear configuration
  refusal when search dispatch would be required. It does not disable preparation
  or another independently admitted consumer. Negative or malformed values fail
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

Extend existing SYSTEM operation evidence with a closed read-query purpose,
`SEMANTIC_QUERY`, used by admitted search consumers. Preserve
DOCUMENT_PREPARATION evidence for that operation; both use the same embedding
foundation. Purpose identifies and constrains the operation, not a separate
permission to embed. SYSTEM retains its intrinsic authority; no agent receives the
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

1. Define one narrow internal SYSTEM embedding execution contract with typed
   operation/task bindings. Extract/reuse the existing preparation dispatch
   authorization through it; leave source selection, lease/target checks and
   publication checkpoints in the preparation adapter. Add the search admission
   adapter, separating direct authenticated and run-bound requests explicitly.
   Bind it to scoped retrieval copies at existing composition boundaries; an
   unbound production reader must not silently gain a standalone allowance.
2. Add the validated configuration and capture it at admission. Add a specialist
   query repository only for the missing retained source/counter, immutable query
   binding and bounded attempt lifetime. Reuse `Budget.retained`,
   `SystemOperationRecords`, `operation_inference_budgets`, `inference_admissions`
   and existing accounting correlations. No duplicate inference/cost ledger.
3. Extend the existing closed purpose allowlist and shared embedding claim carriage
   for SEMANTIC_QUERY. Both consumer adapters use the same execution checks;
   claims remain bound to their operation/source and exact batch. A query binding
   cannot be exchanged for preparation's writable target/publication permit.
   Reject chat/stream authorization for the query purpose as well; adding the
   enum must not make existing generic SYSTEM chat carriage a second dispatch lane.
   Reuse exact request validation, tokenizer limits and dispatcher queue checks.
4. Atomically validate the query/source, advance its counter and retain the
   inference claim before provider submission. Rechecks consume no extra claim.
   Reuse the unique source/ordinal and one-submission constraints. Closing or
   invalidating an attempt prevents further claims without erasing spending.
5. Carry the shared execution binding through both legacy and dual clients for
   preparation and query tasks, preserving each adapter's boundaries. Pin the
   selected active profile once, use its query preprocessing, and reuse captured vectors
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
- Foundation tests exercise preparation and query adapters through the same
  execution contract. No caller/account embedding grant is needed or accepted.
  Changing operation, budget source, task mode, profile or final input cannot
  reuse a claim; the foundation cannot mint a budget or publish stored vectors.
  Retain slice 14's preparation success, exhaustion, creator-independent work,
  withdrawal, uncertain replay and publication-race coverage in both embedding
  modes. Missing search configuration must not disable valid preparation.
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

The shared foundation is not limited to documents. Its initial adoption excludes
activation of memory/passage/archive recall or writes, independent embedding
maintenance,
ask/deliberation budgeting, new reranker/model navigation, Global account search
selector, type registry, group/tenant/ACL editor, broader acting targets, nested
orchestration allocation, transient-child recovery, monetary reservation/spending
pool or new public controls. Standard roles remain the routine access mechanism;
no new role or per-tool RBAC layer.

Keep other inference consumers fenced until their own declared purpose/source
contracts exist. Existing compatibility and curation gates remain. Ship configuration
documentation with an explicit enablement choice; do not silently activate query
spending for every existing deployment. Setting the search budget to zero fences
new search inference without disabling the foundation, preparation or deleting
prior evidence. No data move, credential issuance, deployment, old-binary rollback
or live schema migration is authorized here.

## Initial planning verification

The original planning task inspected the baseline above and checked documentation
links/whitespace. It ran no implementation tests. The subsequent implementation
record below supplies separate evidence; prior slice 14 counts do not verify the
new behavior.

## Implementation record, 18 October 2026

- `SemanticEmbedding` and `BoundSemanticEmbedding` share closed SYSTEM embedding
  claim carriage across preparation and semantic queries. Typed tasks bind mode,
  profile and exact final inputs. Length-delimited hashes prevent batch-shape
  aliases. Preparation retains its revision allowance, generation/lease, exact
  output set, unresolved-claim fence and atomic publication adapter.
- Search admission is mandatory in production composition and explicit at the
  supported information/document frame, existing HTTP and retained tool entry
  points. Scope alone creates no allowance. Tools retain the parent validity,
  cancellation, original call identity and consumed-input ledger. Socket query
  validity follows the originating connection; live query capabilities close at
  their owning operation boundary and cannot be rebuilt from historical rows.
- V162 extends the closed SYSTEM purpose allowlist and adds only the missing
  retained query source/counter and task binding on `inference_admissions`.
  Accounting and one-submission evidence remain in their existing stores.
  Counters/identity cannot be reset, rebound or deleted; known repeated tool
  calls refuse redispatch. Public unkeyed resubmissions remain new queries.
- Both embedding modes use the common binding. Dual queries retain the active
  profile through queued dispatch and the fenced read, with no silent re-embed.
  SEMANTIC_QUERY cannot acquire chat/stream carriage, preparation publication or
  independent maintenance. Unsupported consumers remain unbound.
- Result assembly uses the existing navigation policy advisory fence, scoped
  authority locks and short SHARE locks on information resources/revisions/inputs.
  These order withdrawal and policy changes with the final read. The table locks
  briefly delay concurrent information writes during assembly; no provider IO
  runs in this transaction. Current root/input/privacy filters still apply before
  ranking, paging, counts and returned content.

Configuration is `plowshare.embeddings.search-budget`, with environment override
`PLOWSHARE_SEMANTIC_SEARCH_BUDGET`. The shipped default is zero (query inference
inactive); positive finite values are captured once per logical query. Invalid
negative/non-integer values refuse binding. This does not alter preparation's
existing source or enable other embedding consumers.

The public request/response graphs, operation names, roles and SDK methods are
unchanged. Tests use deterministic providers and controlled PostgreSQL fixtures;
no production data, real provider acceptance, live process restart, credentials,
API extension, deployment or broader consumer activation is claimed.

### Performed verification

- `./gradlew format formatCheck check sdkCheck` passed. The default server run
  passed 5,849 tests with zero failures/errors/skips; the root client,
  architecture, Javadoc and configuration checks passed. Node, Python, .NET and
  Go SDK conformance passed using installed toolchain overrides and cached Go
  dependencies. Shared contracts remain at 276 operations, 17 outcome codes and
  483 canonical DTO cases; no public graph regeneration/change was needed.
- Selected PostgreSQL verification passed 27 cases across two invocations:
  `GlobalPreparationPersistenceTest` (12 cases including the subsequent retained
  reader-tool acceptance), `RootDocumentPolicyPersistenceTest` (13), and
  `InferenceAdmissionPersistenceTest` (2). These used `-PfullDb --tests` because
  the new migration, source/claim constraints, atomic counters, real policy SQL
  and withdrawal/read locking require PostgreSQL. No full database suite or
  database-backed deployment smoke test ran.
- The Global acceptance uses real public information frames, policy/repositories,
  retained SYSTEM inference boundaries and deterministic providers. Human and
  service readers search Global-held knowledge in both embedding modes, then
  rank/stance through the same query source contract. Preparation and query
  spending stay separate. Other cases cover exhaustion/concurrency, closed or
  repeated tool claims, withdrawal during provider IO, source immutability,
  private processing logs and final disclosure ordering.
- The retained reader-tool acceptance uses a real READ-only parent admission,
  query source, SYSTEM dispatcher and durable consumed-input ledger. The parent
  has no RUN_MODEL action and its conversation allowance is not spent. Repeated
  call identity and cancellation cannot dispatch another embedding request.
  Mocked boundary tests additionally cover exact task/profile binding, queued
  profile invalidation, consumer-copy propagation, invalid configuration and
  denial of chat/stream or publication through query authority.
- Formatting passed again after the final tagged acceptance test. Changed
  documentation file/section links and diff whitespace were checked separately.

Verification repaired isolated fixtures for the mandatory query dependency,
Spring's canonical configuration constructor selection and transport-owned
request lifetime. The database fixture now explicitly clears Global-owned corpus
state and activates empty serving profiles before preparation, matching startup;
it does not bypass coverage or enable independent repair to make queries pass.
