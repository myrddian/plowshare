# Follow-on slice 17: memory writes and Scribe

Status: proposed implementation scope, 18 October 2026. Documentation only;
no runtime, API, configuration or database change is implemented by this plan.
Source baseline: `1c08347b0c564bf2147f114a648b4e6bd3e86226`.

Depends on implemented slices 04–08, 13, the shared SYSTEM embedding foundation
in [slice 15](15-semantic-query-budgets-and-read-authority.md), and the memory
read/repository work in [slice 16](16-memory-query-embedding-and-recall.md).
Read with [ADR 0030](../decisions/0030-authority-substrate-and-explicit-scopes.md),
the [roadmap](../authority-substrate-implementation-plan.md) and the
[impact report](../authority-substrate-impact-report.md).

## Outcome

Admit the existing memory-write pipeline as a bounded operation: validate the
proposal and destination, retrieve Scribe's same-home candidates, optionally
consult Scribe, commit the existing NEW/MERGED_INTO/SUPERSEDES result and provenance,
and publish only the embeddings owed by that committed write. SYSTEM performs
the declared internal inference through existing dispatch machinery. It does not
give the caller SYSTEM, arbitrary RUN_MODEL or broader resource permissions.

Spending is unlimited by default. A policy must explicitly select a disabled or
finite allowance before it can restrict this operation. A nearby conversation
budget, search budget, accounting owner or worker definition does not implicitly
become the memory-write operation's payer or embedding ceiling.

This slice completes the selected synchronous write consumers, not all memory
processing. Curator, promotion, independent repair/rebuild/reembed and document
ask/deliberation remain deferred. No new public operation, wire field, tool or
SDK capability is planned.

## Verified current source

| Owner | Current behavior and implication |
| --- | --- |
| [MemoryWriteHandler](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/MemoryWriteHandler.java), [MemoryController](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/api/MemoryController.java) | Validate before calling Scribe and Archive. They conditionally supply usage attribution, which is not authority. Bind authenticated identity and request lifetime independently of accounting. Existing transport authorization remains necessary. |
| [MemoryTools.Write](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/MemoryTools.java), [JobRuntime](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/JobRuntime.java) | The write tool has a document-input restriction, but its `forRun` currently carries that restriction rather than the full parent/account/call/cancellation binding. Supply those facts internally without widening tool grants or acting support. |
| [Learner.file](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/learner/Learner.java) | Files each extracted proposal through Scribe and Archive, attaching conversation/turn provenance in the write transaction. This is another existing write consumer. Bind its filing step to the trusted learning window; usage attribution or formed_by text cannot establish SYSTEM authority. |
| [Scribe](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/scribe/Scribe.java) | Calls `Archive.embedding` and `recall(Precomputed, ...)`, then filters candidates to the write's home. Slice 16 fences those unbound helpers in production, so candidate retrieval currently falls into the existing flat-filing fallback. The optional tool-free chat consult already uses SCRIBE SYSTEM authority, but creates a definition-sized allowance and supplies an empty validity callback. |
| [Archive](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/Archive.java) | Commits mutation, reason, provenance and demotion atomically, then embeds outside the transaction. Legacy publication accepts a precomputed vector after checking text; dual publication calls unbound `repair`. Embedding failure preserves the committed memory. A bare vector/text pair proves neither publication authority nor operation identity. |
| [MemoryStore](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/MemoryStore.java), [MemoryReadRepository](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/MemoryReadRepository.java) | Explicit homes, information-derived privacy predicates and read fences are reusable. Legacy `saveEmbedding` is keyed by ID; write publication needs a live source/version fence, not only an ID and successful inference. |
| [SemanticEmbedding](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/embedding/SemanticEmbedding.java), [BoundSemanticEmbedding](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/embedding/BoundSemanticEmbedding.java), [BoundSystemOperations](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/BoundSystemOperations.java) | Foundation dispatch supports SEMANTIC_QUERY/QUERY and DOCUMENT_PREPARATION/DOCUMENT. A memory write needs its own closed embedding-purpose binding; it must not impersonate document preparation or acquire a general repair permit. |
| [DualEmbeddings](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/embedding/DualEmbeddings.java), [DispatchingDualEmbeddings](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/embedding/DispatchingDualEmbeddings.java), [EmbeddingWorkRepository](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/embedding/EmbeddingWorkRepository.java) | Query permits cannot publish. Document publication already captures source revision, profiles, exact preprocessed inputs and a publication fence, including serving and replacement generations. Share those mechanisms without borrowing a document lease or activating maintenance. |
| [QuerySources](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/embedding/QuerySources.java), [JdbcQueryEmbeddingRepository](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/embedding/JdbcQueryEmbeddingRepository.java), [JdbcInferenceAdmissions](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/accounting/JdbcInferenceAdmissions.java) | Existing retained source UUID, nullable ceiling, atomic claims, exact-task binding and single-submission evidence are reusable. Query-specific error/configuration assumptions need a narrow internal adaptation; the foundation must not mint an allowance. |
| [ArchiveProperties](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/ArchiveProperties.java), [EmbeddingProperties](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/embedding/EmbeddingProperties.java) | Archive has no write spending selector. `search-budget` is explicitly a search/read policy, not a write policy. Do not silently apply it to indexing or a Scribe write. |

These are baseline findings, not claims that slice 17 is already active. In
particular, an existing flat-filing result does not prove that embeddings ran.

## Consumer and destination boundary

| Existing path | Planned treatment |
| --- | --- |
| `memory.write` and `POST /v1/memories` | Supply authenticated principal, existing Home selection and live request fence to one typed write service. Preserve validation, returned WriteResult and existing errors. |
| `memory_write` agent tool | Supply actual account, retained parent, original call identity, cancellation and existing information-input restriction. Require WRITE and exact parent target admission before optional inference and before content effects. |
| Learner's existing proposal-filing step | Use the same service with a trusted SYSTEM learning-window origin and stable proposal ordinal. Validate conversation/window/input provenance and destination independently of the creator retaining access. Preserve existing extraction, scheduling, notes, learned markers and per-proposal failure behavior. No new learning loop or extraction rollout. |
| Direct writes without a registry/Scribe definition, or with an optional Scribe failure | Retain flat filing. A valid write can commit without inference; any embeddings still owed require the same operation binding and selected spending policy. |
| Curator, PromotionQueue, explicit reembed and maintenance tick | Do not connect them to this admission or let them reuse a write permit. Their independent operations remain deferred. |

Use current supported project/personal destinations and existing admin-managed
Global memory selection. Resolve Global to its fixed knowledge identity; do not
create a Global project, broaden SDK/acting selectors or enable public Global
conversations. Project CONTRIBUTOR can write within its scope; READER cannot.
Global writes remain restricted to currently authorized human administration or
declared internal SYSTEM work. GLOBAL_READ never grants WRITE.

Candidate retrieval selects only the destination home, before ranking and prompt
assembly. Project writes do not need an implicit Global search whose results
Scribe later discards. Preserve the existing shortlist limit, order and mandatory
information-input/privacy filtering. Same-home filtering is not itself permission
to disclose restricted-derived material. Keep existing candidate use telemetry,
but update only bounded counters rather than saving a stale content snapshot.
Capture candidate identity/version evidence for verdict validation. A verdict
can merge/supersede only a candidate actually admitted to that consult and still
valid at mutation time; changed or withdrawn evidence refuses the stale mutation
without rerunning the model automatically.

## Operation authority and lifetime

Define a narrow internal MemoryWrites contract, with immutable proposal,
destination/scope incarnation, trusted origin, validity and provenance bindings.
Its origin distinguishes authenticated request, retained tool call and admitted
SYSTEM learning-window filing. Models cannot select executor, policy, operation
identity or origin through arguments. Keep raw JSON and SQL outside this contract.

- Before any inference, pin account/scope identity, validate the proposal and
  authorize the actual write and candidate-read selection. Standard RBAC helpers
  remain the routine configuration; no new memory role or ACL engine is needed.
- Scribe uses its existing deployment/bundle definition resolution, tool-free
  single consult, prompt/parser, candidate checks and queue timeout. Bind the
  consult's exact messages/model to the live write instead of an empty callback.
  Do not give it tools, skills, filesystem, delegation or arbitrary target reads.
- Interactive/tool work remains subject to live caller/credential, parent and
  cancellation checks until the content transaction commits. Check these inside
  the final repository-owned mutation fence; SYSTEM cannot rescue denied work.
- After commit, write-time indexing is declared SYSTEM processing of that exact
  committed source. Creator access loss does not invalidate it. Source existence,
  unchanged relevant content/version, scope incarnation, mandatory privacy,
  operation closure/cancellation and output fences still apply. Check recipient
  authority separately before returning protected results.
- Learner filing uses intrinsic SYSTEM authority bounded by its valid retained
  source window and destination, not a fresh grant or the original user's role.
  Deletion, input withdrawal or an invalid operation prevents effects. Keep
  conversation/turn provenance in the same transaction as the memory and reason.
- Close the synchronous operation after publication succeeds, fails or is skipped.
  This slice adds no durable background executor or restartable closure. Known
  retained calls/windows cannot obtain replacement allowances or duplicate content
  effects on replay; use existing owning evidence to refuse/reconcile them.
  Ordinary HTTP/frame retries gain no new idempotency guarantee without an
  existing retained invocation identity.

## Spending policy

Plan one narrow deployment setting, **`plowshare.archive.write-budget`**, owned
by ArchiveProperties. It is new configuration to be implemented in this slice,
not an existing feature. Reuse the existing unlimited/zero/positive parsing and
nullable-ceiling semantics; introduce no public policy parameter, editor, wallet,
daily quota or spending-pool framework.

| Selected write policy | Behavior |
| --- | --- |
| Omitted, empty or `unlimited` | Default. No spending ceiling; retain operation/source identity, monotonic usage and exact claims. |
| `0` | Disable optional inference for this write. File an otherwise authorized valid proposal as NEW with an explicit flat-filing reason; publish no new vectors and do not borrow another allowance. Existing searchable representations of unchanged memories are not removed. |
| Positive integer | One finite model-call allowance for this logical write, shared across candidate embedding, the optional Scribe chat consult and write-time embedding requests. Exhaustion prevents further inference; it cannot replace the policy, reset the source or undo a committed memory. |

Snapshot this policy once per admitted write. Later configuration applies to new
operations. The policy covers the filing pipeline only: Learner's earlier
extraction call is a separate existing LEARNING operation. Search/recall policy
continues to govern the slice 15/16 read services; it does not govern Scribe's
internal candidate lookup in an admitted write.

Count actual provider requests, not memories or vectors. Local ranking, merging,
reason/provenance writes and reuse of a compatible completed vector add no charge.
Dual slots/generations can require more than one request. Keep deterministic
phase order: candidate query when Scribe is available, optional consult if there
are candidates, then publication owed by the committed verdict. A small finite
allowance may fund only part of that pipeline; it is not a guarantee of full
index coverage. Preserve missing-vector reporting through existing recall.

Scribe's one-consult shape and queue timeout remain operational bounds. Do not
turn its `max-model-calls: 1` into a one-call limit on all write embeddings or
mint another fresh allowance for every helper. Its chat dispatch uses a claim
from the selected write source. The caller's finite conversation allowance is
not automatically charged. Unlimited removes only this spending ceiling, not
authority, exact-input, cancellation, tokenizer or capacity checks.

## Smallest implementation shape and reuse

1. Add the typed write admission/service and narrow memory-write repository
   contract. Compose through constructor-injected interfaces. MemoryStore owns
   affected SQL, account/scope pins, candidate/version checks and final locks;
   Archive retains its existing lifecycle and transaction rules. Avoid a full
   archive/store refactor.
2. Adapt the existing retained source/claim repository for this closed consumer.
   Reuse nullable ceilings, immutable identity, atomic ordinal claims and the
   inference ledger. Retain write admission before optional inference, including
   disabled/no-inference writes, so a known retained invocation cannot duplicate
   content just because it made no model call. Namespace producer invocation keys;
   include consumer, phase and exact task in the binding. Query-oriented table
   names are historical evidence storage, not permission to borrow a read permit.
   An append-only migration may narrowly permit a zero-call retained write source
   without admitting any claim from it; preserve existing read-policy refusal.
   Keep invocation admission and final mutation checks transactionally ordered.
   Do not add a parallel usage ledger or replay/result-recovery framework.
3. Reuse SEMANTIC_QUERY/QUERY execution for candidate embeddings under the write's
   admitted source and validity. Add a closed MEMORY_WRITE embedding purpose
   with DOCUMENT mode for write-time indexing, alongside existing preparation.
   Extend the SYSTEM purpose allowlist with a new Flyway migration at the then
   current version; never edit V162/V163 or relabel historical claims.
4. Bind Scribe's existing SCRIBE chat purpose to the same retained source and live
   write. Keep the optional judgement behavior, but move authority/cancellation
   failures outside the catch-all flat-filing fallback. A fallback is a filing
   choice, never permission to bypass a failed write admission.
5. Preserve mutation/reason/provenance/demotion atomicity and perform no provider
   IO in that transaction. Bind post-commit indexing to the exact output ID and
   embedding source revision. Final vector publication rechecks validity under
   repository-owned scope/source locks, ordered against invalidation/deletion
   and profile activation. Legacy publication needs this fence too.
6. Reuse dual source/version/profile handling and serving/replacement generation
   writes through a memory-scoped publication adapter. It may enumerate only
   outputs of this write, never arbitrary repair keys, other stores, promotion
   outputs or background pending work. Preserve tokenizer/prefix/dimension checks
   and short publication checkpoints; do not fake a document preparation lease.
7. Replace bare production Precomputed reuse with operation-bound completed
   embedding evidence. Legacy reuse requires matching exact canonical text,
   model/profile/preprocessing and the admitted output. Query authority alone
   never permits publication. Dual QUERY vectors cannot substitute for DOCUMENT
   vectors merely because raw summary/scope text matches. No duplicate charge
   for validated local reuse; no retry of an uncertain equivalent batch under
   another purpose, fallback or new source.
8. Wire all selected entry points and the Learner filing hook independently of
   accounting enablement. Require admitted context in production, preserving
   isolated fixture helpers only where they cannot activate a production bypass.
   Unbound helpers used by deferred consumers remain fenced. Update touched
   comments/manuals that currently promise universal query-vector reuse or a
   second provider call after a failed Scribe search.

## Failure and compatibility rules

Preserve distinguishable flat-filing reasons for absent definitions, unavailable
optional retrieval/consult, saturation and unreadable model verdicts. Add an
accurate policy-disabled/exhausted reason where needed; do not claim the archive
had no candidates when the search never ran. Invalid proposal, denied authority,
stale target, cancelled/closed origin or substituted input must not fall through
to an unauthorized NEW write.

A committed valid memory remains stored if indexing fails, policy is exhausted
or its output fence is lost. Report/log that condition through existing bounded
diagnostics without leaking source text/provider credentials. Preserve completed
claims, reasons and valid per-profile checkpoints. Do not mark an unembedded
memory searchable or reissue an uncertain request. No automatic repair worker,
public retry/resume operation or new response shape is added.

## Verification and completion gate

This planning change runs documentation checks only. Future implementation uses
mocks/small fakes for service decisions first. Candidate regression owners are
[ScribeTest](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/agents/scribe/ScribeTest.java),
[ArchiveTest](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/archive/ArchiveTest.java),
[MemoryFramesTest](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/ws/MemoryFramesTest.java),
[MemoryControllerTest](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/api/MemoryControllerTest.java),
[MemoryToolsTest](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/agents/MemoryToolsTest.java),
[LearnerTest](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/agents/learner/LearnerTest.java)
and existing embedding/claim tests. Extend focused owners rather than asserting
that a broad old suite proves the new admission.

- Prove human/service supported writes need WRITE, not caller RUN_MODEL. Cover
  READER refusal, unauthorized Global writes, wrong/replaced scopes and accounts,
  revoked credentials, retained sandbox/cancellation and existing acting refusal.
- Test exact same-home candidate selection, private-derived filtering, stable
  shortlist and bounded use telemetry. Refuse invented/cross-home/changed targets;
  retain NEW/merge/supersede, shadowing, reason and demotion semantics.
- Exercise absent Scribe, empty candidates, optional failures, malformed verdict,
  disabled policy and exhausted finite policy. Authority or cancellation failure
  cannot become flat-filing success. Validate before any claim or model dispatch.
- Test default/explicit unlimited, disabled, finite and invalid configuration.
  Retain usage under unlimited. A finite parent and disabled search policy do
  not silently restrict writes; finite write policy does not alter recall or
  Learner extraction. Both encoder modes share the selected write source.
- Bind exact proposal, candidate evidence, prompt/model, mode/profile, output and
  source revision. Reject bare or foreign precomputed vectors and reuse across
  repair/Curator/document consumers. Prove compatible legacy reuse costs no new
  request and dual publication does not reuse a query-prefixed vector.
- Cover request/parent closure, uncertain dispatch, repeated retained invocation,
  concurrent finite claims and operation close. No double submission, replacement
  source, duplicate memory/provenance or reset counter. Keep HTTP retry guarantees
  limited to what the existing transport actually provides.
- Revoke creator access after commit: valid SYSTEM indexing can finish. Change,
  invalidate, withdraw or delete the source, replace its scope, cancel the
  operation or activate a profile during IO: stale output cannot publish.
  Indexing failure preserves the content/reason transaction and incurs no replay.
- Verify Learner filing's valid window/ordinal and atomic provenance independently
  of attribution, without changing its extraction model, triggers or lifecycle.

PostgreSQL is needed during implementation for the append-only purpose migration,
retained-source constraints/claim concurrency, source-version publication locks,
privacy predicates and atomic mutation/reason/provenance rollback. State that
reason before running the smallest tagged `-PfullDb --tests` selection; reuse
existing persistence fixtures such as
[GlobalPreparationPersistenceTest](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/information/GlobalPreparationPersistenceTest.java)
and [TransactionBoundaryTest](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/TransactionBoundaryTest.java).
Deterministic providers must prove IO occurs outside database transactions.
Do not run the full database suite or dockerSmoke by habit.

Completion requires the selected consumers actually wired, focused positive and
negative tests, Java `format` and `formatCheck`, root `check`, and affected existing
contract checks. No public graph changes are planned. Record actual commands,
failures and remaining consumers; tests do not establish deployment acceptance.

## Exclusions and stop conditions

No Curator/promotion activation, new Global destination API, wider acting/tool
targets, archive-wide repair/rebuild/reembed, document ask/deliberation, memory
digest/navigation inference, nested orchestration, new scheduling/control surface,
tenant/group/ACL engine, spending pools, SDK parity, deployment or data move.

If binding an existing producer requires a new public target or lifecycle/recovery
capability, record the exact gap and revise this plan before expanding scope.
Historical evidence cannot reconstruct a live operation or justify uncertain
replay. This proposal authorizes no implementation or migration execution.
