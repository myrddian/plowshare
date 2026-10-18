# Follow-on slice 16: memory query embedding and recall

Status: implemented under the subsequent explicit instruction, 18 October 2026.
The original scope and planning evidence follow; the implementation record below
identifies activated consumers and verification. No API change or deployment.
Planning source baseline:
`dce9ddd37526f9b9a2d14e6a4d0ec6f3d8bf0bbb`.

Depends on implemented slices 04–08, 13 and
[slice 15](15-semantic-query-budgets-and-read-authority.md), including its
disabled/limited/unlimited spending-policy correction. Read with
[ADR 0030](../decisions/0030-authority-substrate-and-explicit-scopes.md) and the
[roadmap](../authority-substrate-implementation-plan.md).

## Outcome

Connect query embedding for existing memory recall and automatic reminders to
the shared SYSTEM embedding foundation. Authenticate and admit the read with its
actual recipient, permitted archive tiers, operation lifetime and applicable
spending policy. SYSTEM embeds the exact admitted query; it does not grant the
recipient broader memory access or make the caller an inference operator.

This slice covers query embeddings only. Memory write publication, Scribe
candidate retrieval, curation surveys, re-embedding and background repair are
separate consumers even when they call the same Archive methods. Do not activate
them through an unbound convenience overload or merely because recall now works.

## Verified source findings at planning baseline

| Owner | Current behavior and consequence |
| --- | --- |
| [Archive](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/Archive.java) | `recall(String, Home, int)` embeds before opening a read transaction. Project search combines project and Global tiers; `survey` returns index lines without counting uses. Neither a Home nor usage attribution establishes a recipient or a retained inference claim. |
| [MemoryStore](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/MemoryStore.java) | Tier selection uses explicit `home_scope_id` through `memory_knowledge_scope`. Legacy and dual queries filter state and, when protected, `information_memory_safe`. Methods take Home, not an authenticated principal. Safety filtering and tier identity alone cannot prove current caller READ. Counts and telemetry must share the admitted scope. |
| [ArchiveConfig](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/ArchiveConfig.java) | Composes Archive with the generic embedding client and transaction owner. Production composition must require the memory read admission dependency; isolated fixture constructors must not become a production bypass. |
| [MemoryRecallHandler](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/MemoryRecallHandler.java), [MemoryController](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/api/MemoryController.java) | Existing recall accepts question/project/limit and supplies usage attribution conditionally. Wire inputs contain no authority, spending-policy or payer fields. Supply authenticated context and request lifetime internally, independently of accounting enablement. |
| [MemoryTools.Recall](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/MemoryTools.java), [JobRuntime](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/JobRuntime.java) | The tool receives Home and usage attribution; the runtime owns the retained parent, account, tool-call identity and cancellation. Bind those trusted facts before execution; model arguments do not select authority. |
| [Reminder](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/learner/Reminder.java), [Reminding](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/Reminding.java) | Existing automatic recall uses `Archive.survey`, only for definitions satisfying `canBeReminded`. It supplies summary/index lines without use increments, stays volatile at the end of the prompt and is best effort. Preserve these independent behavior gates. |
| [Scribe](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/scribe/Scribe.java) | Uses `Archive.embedding` and `recall(Precomputed, ...)` to choose write candidates. Legacy writes may reuse that vector; dual publication repairs document-prefixed vectors separately. This is not an ordinary recall request and must not receive a standalone recall permit accidentally. |
| [Curator](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/curator/Curator.java) | Uses Global `Archive.survey` for promotion candidates. Admitting Reminder's survey does not admit curation or change copy/retirement/approval behavior. |
| [PinnedOperationAuthority](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/PinnedOperationAuthority.java) | Ordinary retained operations require their exact home and reject explicit Global/cross-project memory references outside that envelope. GLOBAL_READ is resource authority, not permission to enlarge an operation sandbox. |
| [QueryEmbeddings](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/embedding/QueryEmbeddings.java), [BoundQueryEmbeddings](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/embedding/BoundQueryEmbeddings.java) | Slice 15 binds information/document contexts and optional information-revision UUIDs. Do not represent a memory ID as a document revision or reuse document policy checks as memory authorization. Share the suitable source/claim machinery through a typed memory adapter. |
| [SemanticEmbedding](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/embedding/SemanticEmbedding.java), [BoundSemanticEmbedding](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/embedding/BoundSemanticEmbedding.java) | Existing SEMANTIC_QUERY plus QUERY preprocessing is the shared execution lane. It already accepts the consumer's claim and validity callback. Memory recall needs no new SYSTEM role, account grant, model runtime or per-feature permission. |

These findings describe the baseline, not completed slice 16 behavior. The plan
does not infer missing interfaces or revive old unbound inference paths.

## Supported consumer boundary

| Existing consumer | Slice 16 treatment |
| --- | --- |
| `memory.recall` and its current HTTP equivalent | Admit the authenticated read for the existing requested Home and permitted tier set. Retain question/limit validation, response shapes, ranking and unsearchable semantics. No new selector or endpoint. |
| `memory_recall` agent tool | Bind to current executor/subject, retained parent, home, original call identity, cancellation and applicable read sandbox. Preserve its existing tool grant and limits. |
| Automatic Reminder survey | Bind one existing reminder attempt to its retained turn/request and recipient. Keep `canBeReminded`, current enablement, volatile placement, summary-only output and best-effort behavior. No new reminder activation or scheduler. |
| Embedding-free memory reads/index/navigation | Preserve their existing authority and lifecycle checks. Adjust shared helpers only where necessary to keep recall disclosure consistent; do not add inference or broader targets to them. |
| Scribe/write candidate search, standalone `Archive.embedding` or precomputed-vector entry points | No new admission in this slice. A live recall permit must not escape into write judgment or stored-vector publication. |
| Curator surveys, memory write embeddings, explicit reembed, independent repair/rebuild | Remain deferred under their own existing gates and operation contracts. |

## Authority and selected tiers

- Resolve account identity, current READ and the allowed tier set before provider
  IO. Pin account and selected scope incarnations so deletion/recreation under
  the same legacy name cannot reuse admission. Include the current SYSTEM
  authority check through the foundation, without granting the reader RUN_MODEL.
- Keep legacy Home as an existing transport selector. Resolve its Global tier
  through the fixed Global knowledge identity; do not interpret it as ordinary
  execution in the Global account or create a project named Global.
- For standalone reads, use only tiers that existing transport/account/token
  admission and current resource policy allow. Project-plus-Global recall may
  retain its existing ordering where both tiers are authorized. Existing service
  token and acting restrictions remain; this slice does not extend their targets.
- For run-bound reads and reminders, intersect archive tiers with the retained
  parent sandbox as well as current recipient READ. An implicit Global fallback
  is not a sandbox exemption. If Global lies outside that envelope, exclude it
  from ranking, counts, telemetry and prompt/tool output before querying. Do not
  change ordinary `requireTargets` to admit arbitrary Global/cross-project reads.
  Existing explanatory tool text must describe only the admitted tiers.
- Mandatory information-input/privacy filtering still applies to every selected
  tier. SYSTEM's broad authority must not expose private-derived memories, counts,
  summaries, IDs or existence through this read service.
- Recheck live recipient/parent validity before queued dispatch and final
  disclosure. Result assembly and bounded usage telemetry need a repository-owned
  read fence ordered against membership loss, deletion/invalidation, source/input
  changes and profile activation. Perform no provider IO while holding it.
- Explicit cross-home memory reads, broader account acting and a general read
  target envelope remain separate scope. A recall capability cannot authorize
  `memory_read`, mutate content, change permissions or publish vectors.

The narrower run-bound tier intersection is an intentional correction where a
legacy implicit fallback would exceed retained scope. Do not silently widen the
parent because the recipient has GLOBAL_READ. Permission exclusions are not
missing-vector counts: `unsearchable` counts only eligible live memories lacking
the required serving representation, without disclosing excluded tiers.

## Spending policy, then any applicable budget

The presence of a Budget object or a source record does not decide whether a
finite limit applies. Resolve the operation's spending policy first, retain that
decision, then enforce its applicable ceiling. Authority and model admission are
separate from that decision.

For these selected read-side consumers, explicitly reuse the search service's
existing `plowshare.embeddings.search-budget` configuration and
`PLOWSHARE_SEMANTIC_SEARCH_BUDGET` environment override. This is a declared mapping
of memory query operations to that service policy, not automatic inheritance from
whatever budget or usage owner happens to be nearby. No separate memory budget
key, policy engine, caller parameter or spending pool is introduced.

| Admitted policy | Meaning for this memory read |
| --- | --- |
| Unlimited: omitted, empty or `unlimited` | Default. No finite spending ceiling. Retain source identity, monotonic usage, exact attempt claims and accounting through the existing nullable-ceiling contract. |
| Disabled: `0` | No query inference. Explicit recall reports the existing refusal/error contract when inference is required; automatic Reminder skips through its existing best-effort path. Neither borrows another budget. |
| Limited: positive integer | Apply this finite call allowance to the logical memory query. All its helper/provider batches use the same admitted source. Exhaustion refuses further inference without switching policy. |

- The parent conversation's finite budget does not automatically apply to this
  declared read service. Conversely, unlimited service policy does not remove
  the parent's own applicable model-call ceiling. Retain the distinction between
  caller, SYSTEM executor, operation policy/source and usage attribution.
- Capture the selected policy once per operation. Source identity and nullable
  ceiling remain immutable for the attempt. Later configuration affects new
  admissions, not pending claims; no live limit-edit capability is added.
- Reuse the existing inference admission ledger and accounting correlation. A
  model/provider request consumes one claim under the selected policy, including
  an uncertain attempt. Vector count, hits, local ranking and use-counter writes
  do not create additional embedding charges.
- Preserve existing no-inference shortcuts such as a nonpositive internal
  limit. Do not add a new empty-corpus optimization, lexical fallback or cache.
  An absent authority binding must never be treated as an unlimited policy.
- Known retained tool/reminder attempts cannot obtain replacement sources on
  replay or restart. Retained rows are evidence; they do not reconstruct a live
  request, permit a provider retry after uncertain delivery or recreate a turn.

## Smallest implementation shape

1. Define a narrow typed memory-read admission contract carrying recipient,
   selected/pinned tiers, read kind (recall versus reminder survey), exact query,
   limit and trusted request/run origin. Reuse slice 15's live permit/disclosure
   lifecycle where appropriate. No raw JSON or generic payload bag enters it.
2. Reuse the shared SEMANTIC_QUERY foundation and existing legacy/dual embedding
   wrappers in QUERY mode. Capture the active prose profile and exact final input;
   retain tokenizer, dimensions, prefix and queued-profile checks. Do not reuse a
   QUERY permit for DOCUMENT preprocessing or stored-vector publication.
3. Share existing query-source counters/claim retention through specialist
   repositories. Keep memory tier/access/disclosure checks in a typed memory
   adapter; do not force them through document-revision authorization. The current
   `embedding_queries` evidence is reusable; introduce no parallel cost ledger or
   generic execution framework. Add an append-only Flyway migration only if a
   concrete evidence invariant needs it, choosing its version at implementation.
4. Move affected memory-read queries behind a narrow repository interface if the
   touched concrete MemoryStore dependency otherwise carries persistence into
   admission. Keep SQL, locks, bounded use updates and row mapping in repositories;
   do not refactor unrelated archive writes or the full store as part of this slice.
5. Wire production Archive composition and the selected HTTP/frame/tool/Reminder
   entry points to require the typed context, independently of accounting being
   enabled. Bind socket/request closure or retained turn/cancellation and original
   invocation identity internally. No model-selected principal, payer or policy.
6. Assemble summaries/bodies, IDs, counts and use bookkeeping under the live read
   fence. Keep recall use behavior distinct from survey's no-use behavior. Preserve
   prompt placement and existing error behavior: explicit reads do not report a
   failed search as an empty successful search; automatic recall remains optional
   and observable without failing the owning turn.
7. Reject unbound production helper calls and cross-consumer permit reuse. Fixture
   overloads may remain isolated. An arbitrary `Precomputed` vector is not read
   authority; source/profile checks and live consumer admission still apply. Do
   not allow Scribe/Curator/write callers to obtain a recall source as a side effect.

## Verification and completion gate

Use mocks and small fakes first. Candidate existing owners include
[ArchiveTest](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/archive/ArchiveTest.java),
[MemoryFramesTest](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/ws/MemoryFramesTest.java),
[MemoryControllerTest](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/api/MemoryControllerTest.java),
[MemoryToolsTest](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/agents/MemoryToolsTest.java),
[ReminderTest](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/agents/learner/ReminderTest.java),
[PinnedOperationAuthorityTest](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/access/PinnedOperationAuthorityTest.java),
and existing embedding permit/dispatcher tests. Extend owning tests or add focused
policy tests; this is not an instruction to run all database tests.

- Prove human/service READER uses an allowed existing recall without its own
  RUN_MODEL, while unauthenticated/expired/wrong-home or unsupported token/acting
  requests cannot dispatch. Revoke read membership or replace an account/scope
  during queueing and reject stale dispatch/disclosure.
- Test standalone permitted project-plus-Global reads and run-bound tier
  intersection separately. A reader's GLOBAL_READ must not enlarge the retained
  sandbox. No hidden-tier summaries, IDs, missing-vector counts or use increments
  survive filtering. Explicit Global/cross-project reference gates stay intact.
- Cover default/explicit unlimited, disabled and finite policies in both encoder
  modes, with usage retained even under unlimited. A finite parent does not get
  charged merely because it exists. Disabled/exhausted policy cannot borrow or
  fall through to unlimited; captured policy survives helper copies and queueing.
- Bind the exact query, profile, source and mode; reject substituted inputs,
  publication and Scribe/Curator/repair permit reuse. Concurrent finite claims
  cannot overdraw; unlimited claims still have distinct monotonic ordinals.
- Verify stable response contracts, ranking, limits and eligible unsearchable
  counts. Recall preserves use accounting; Reminder survey increments no uses,
  keeps its gate/volatile prompt position, and does not fail the parent on an
  unavailable or policy-disabled optional read. Missing origin never authorizes it.
- Test cancellation/closure, known invocation replay and uncertain submission
  refusal without a fresh allowance or duplicate provider call. Reminder attempt
  identity must come from the retained turn, not query text or a new random retry ID.

Where new SQL/persistence behavior is necessary, select tagged `-PfullDb --tests`
cases only after stating the database-specific reason: real access/privacy
predicates, immutable source/claim constraints, atomic counters, transaction
rollback or revocation/disclosure ordering. Use deterministic providers and prove
provider IO happens outside database transactions. Reuse existing migration/claim
and scoped-grant fixtures rather than introducing a live provider requirement.

Completion requires focused checks, Java formatting and `formatCheck`, root
`check`, and only the affected existing contract/client checks. No request/response
graph change is planned; inspect generator output if implementation touches a
shared graph rather than editing generated files by hand. Record commands,
results, actual activated consumers and remaining gaps before declaring completion.

## Exclusions and original planning verification

No memory write/Scribe/promotion rollout, broader survey activation, background
repair/rebuild, document ask/deliberation, new Global selector, wider acting or
ordinary operation-target envelope, tenant/group/ACL engine, nested orchestration,
new tool grant, spending pool, public API, SDK parity, deployment or data move.

This planning pass inspected current source and checked documentation links and
diff whitespace. No Java, PostgreSQL, provider or deployment tests ran for this
plan. Prior slice 15 results are evidence for the shared foundation, not proof
that the consumers planned here are already admitted.

## Implementation record

Implemented from `a0e7a4b8e722871a69d9d151ec5df70d34ade50e` under the explicit
slice 16 implementation request. The supported consumers are existing frame/HTTP
recall, `memory_recall` and the existing eligible automatic Reminder survey.

- `MemoryQueries.Request` carries the actual recipient, exact Home, read kind,
  query, limit and trusted live origin. `BoundMemoryQueries` uses existing READ
  permissions, account/scope incarnation pins and SEMANTIC_QUERY execution through
  `SemanticEmbedding`. Callers acquire no RUN_MODEL or publication permission.
- `MemoryReadRepository` owns typed tier admission, privacy/read fences, existing
  ranking/count queries and telemetry. MemoryStore implements that contract.
  Production Archive composition requires query admission and privacy protection.
  Unbound production embedding/recall/survey/precomputed entry points refuse;
  isolated fixture constructors retain their previous behavior.
- Standalone permitted reads preserve project-before-Global ordering. Retained
  tool/reminder reads select only their exact home, excluding implicit Global
  fallback before ranking, missing-vector counts, output and use increments.
  Current information-derived privacy filters remain mandatory. Ordinary target
  envelopes and explicit cross-home reference gates are unchanged.
- Existing `embedding_queries` source/claim methods are shared through
  `QuerySources`; information/document queries retain their own access adapter.
  No new ledger, table, Flyway migration, budget key or public contract is needed.
  Memory queries explicitly select the existing read-side spending policy:
  default unlimited, `0` disabled, positive finite. Unlimited retains nullable
  ceilings and usage. A parent's finite allowance is neither selected nor charged
  merely because it exists.
- Request/socket lifetime and retained parent/cancellation fences are independent
  of accounting enablement. Tool identities use the original conversation/call;
  Reminder identities use the retained conversation and turn, including its
  continuation identity. Missing origins cannot create a source. Known repeated
  attempts refuse without provider replay or another allowance.
- Both embedding modes reuse existing input/tokenizer/profile validation. Dual
  recall captures the active prose profile; query permits cannot publish or repair
  document-prefixed vectors. SYSTEM performs only the admitted query batch.
- Final result assembly takes the existing navigation/scope fences, then short
  memory and privacy-evidence table locks. These serialize memory result assembly
  and briefly delay concurrent mutations; no provider IO occurs while held.
  Recall updates only `uses` and `last_used`, preserving source/lifecycle fields.
  Survey increments no uses. Validity is checked again after assembly, so a
  cancelled transaction rolls back telemetry while retaining the paid claim.

Memory writes, Scribe candidates, Curator surveys, reembed/independent repair,
ask/deliberation, navigation extensions, broader acting/read targets and deployment
remain outside this implementation. Existing Reminder eligibility, summary-only
output, volatile placement and optional failure behavior are preserved.

### Performed verification

- `./gradlew format formatCheck check sdkContractCheck` passed. The default server
  suite passed 5,860 tests with no failures, errors or skips. Repository client,
  architecture, formatting and Javadoc checks passed. SDK contracts remain at
  276 operations and 17 outcome codes; SDK/protocol sources and public graphs
  were not changed. Native SDK conformance and live provider/deployment tests
  were not run for this internal consumer change.
- Focused non-database verification passed `BoundMemoryQueriesTest` (6) and
  `MemoryQueryEntryPointsTest` (2). Coverage includes default unlimited and
  explicit finite/disabled policy, retained usage, scope/cancellation checks,
  no-inference shortcuts, exact input/mode binding, fixture-only unbound helpers,
  HTTP/frame lifetime binding without accounting, and optional Reminder origin.
- The selected `-PfullDb --tests` invocation passed 11 PostgreSQL cases in
  `GlobalPreparationPersistenceTest`. PostgreSQL is necessary for the actual
  memory/private-input predicates, row mapping, shared immutable query counters,
  revocation/disclosure locking and transaction rollback. Both encoder modes
  exercise human and service reads with project/Global/private/missing-vector
  fixtures. A READ-only parent can recall and receive a reminder without its own
  RUN_MODEL, and its finite conversation budget remains unspent.
- Database cases prove invocation replay refusal, account replacement despite
  restored membership, revocation during provider IO, invalidation ordered after
  a fenced result, bounded telemetry preserving changed content, and cancellation
  rolling back use updates while retaining the submitted claim. Existing finite
  and unlimited query-source concurrency/replay cases also passed. Deterministic
  provider fixtures assert that inference never runs inside a database transaction.
- The first root check stopped at Desktop's process-shutdown assertion in
  `files.test.ts`, introduced by `f78e4f04`. The unchanged Desktop task passed all
  235 tests on rerun, and the final root check passed. No client fix was included.
- Changed documentation file links and diff whitespace passed. No full database
  suite, migration execution against retained deployment data, credentials,
  external provider calls or deployment was involved.
