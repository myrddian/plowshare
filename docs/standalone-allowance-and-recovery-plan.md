# Standalone allowances and execution recovery

Status: implementation authorized, 19 October 2026. Packages A–E are implemented
in this checkout; final local verification is recorded below. No live deployment
or data adoption is claimed.
Source baseline for the original audit: `1a075d51a87108ecfd8c927542a5a27c663a13cf`.
The inventory below describes the pre-change source; the implementation mapping
supersedes its storage descriptions.

This follows the authority-substrate work and the
[slice 18 inventory](authority-substrate-slices/18-deferred-scope-and-cutover.md).
It scopes the remaining coupling between execution and allowance storage,
including existing swarm recovery. It does not reopen the eighteen historical
slices or claim that their supported paths never worked.

## Implementation mapping

- **A:** `AllowanceId`, `AllowanceSnapshot` and `AllowanceRepository` define the
  internal contract. `JdbcAllowanceRepository` and migration V170 own standalone
  `execution_allowances` rows and immutable `execution_allowance_claims`. A claim
  atomically increments one counter and retains its ordinal/ceiling before
  inference admission; admission validates the claim without charging twice. Source
  retention is explicit; attaching/restoring an existing Budget is read-only and
  does not insert rows during history/status queries.
- **B:** operation envelopes carry a typed allowance ID, separately from actual
  input/parent conversation IDs. Orchestration start/recovery reconstructs that
  ID through the repository. Conversations are reference adapters and projected
  status, never spending stores. Turn completion updates its timestamp only.
  Applicable automatic increases update the canonical ceiling without resetting
  usage. A stale reader refreshes shared usage before an exhausted-source decision.
  Board continuations retain their owning lease callback and its closing floor.
  Child binding accepts the retained parent or a proven derived continuation of
  it, while retaining the exact allowance and scoped grant ancestry.
- **C:** board trees and message instances share canonical rows. Unlimited roots
  have no finite total or percentage reserve; every wake still has a finite
  scheduling window. Root claim ordinals are distinct from a wake's local spent
  count. A retry step override does not enlarge that call window. Exhaustion
  leaves the tree recoverable; top-up retains history/spent, alerts agent openers
  through the existing exhausted-notice route and re-owes eligible unread work.
  Failed members require explicit retry. Explicit close remains terminal.
- **D:** processing, query and memory-write repositories claim their selected
  source directly. Processing logs do not mirror spending. Source closure,
  generation/lease validity, target fences and current read authority still apply.
  Query closure serializes with its last claim. Existing SYSTEM consumers with
  live `Budget` objects join canonical storage at retained inference admission;
  independent service operations remain independent sources. The older ingest
  and summariser stages keep their distinct existing selected policies; this is
  not permission to merge them into one payer.
- **E:** established status DTOs read canonical projections. Old counter columns
  retain insertion/backfill snapshots and reject subsequent mutations. Legacy
  string budget references and conversation-binding helpers are removed from
  `Budget`; historical string evidence remains stored. No new public operation
  was added. The changed swarm catalog/selection `budget` fields use JSON null
  for unlimited, with regenerated TypeScript/Python/Go/.NET contracts. Existing
  `BoardTopic.potTotal` was already nullable. Desktop adds an affordance for
  existing `board.topup` and reconciles uncertain delivery by reading, with no
  automatic mutation replay. Desktop and TUI display unlimited explicitly.

New conversation admissions default to `unlimited` through
`PLOWSHARE_CONVERSATION_BUDGET`; configure a positive value to retain a finite
new-admission default, or zero to disable inference. Explicit request limits and
existing stored policies survive unchanged. Omitted swarm-definition budgets
also mean unlimited; explicit finite definition/deployment policies still apply.
Existing processing/ask/service policy selection is preserved. Unlimited does not
remove step limits, capacity routing, current authority or operation validity.

Operation controls use the existing scoped role helpers: Contributor-or-higher
for top-up/retry, Reader-or-higher for explicit project/topic inspection. This
allows an authorized operator to manage another account's public project topic
without becoming its executor or gaining access to private messaging topics.
Account-wide aggregate inspection remains account scoped. Token/application and
acting ceilings still apply at the existing request boundaries.

An explicit-only metadata revision's zero allocation remains allocatable through
its existing authorized processing control. This is different from a disabled
SYSTEM/native source. Automatic increases cannot enable either zero source;
source allocation does not authorize processing outside the existing lifecycle.

### Cutover and retained evidence

V170 preserves existing conversation, processing and query UUIDs/usage. Board
roots receive one canonical ID; existing seat/delegate references migrate to that
root. Historical per-wake inference UUIDs/ordinals stay separate because merging
them would collide. Historical input conversation attribution is retained as
provenance, not reinterpreted as an allowance owner or funding check.

Migration must run with old workers stopped and pending/unknown provider work
reconciled through its existing receipts. Take the deployment's normal verified
backup before adoption. Mixed old/new writers are unsupported: historical-counter
writes are refused. Restart only the new binary. Old-binary rollback requires
restoring the pre-migration backup; no live rollback or rolling upgrade has been
proved here. Unknown legacy source references are left unavailable with an
explicit reconciliation error; the migration never invents unlimited authority.
Historical closed swarms are not reopened. Deployment adoption and live provider
restart/operator acceptance remain deployment work.

Independent orchestration from board-backed work remains refused: a spending ID
alone cannot reconstruct its owning wake window, reserve, closure or scheduling
lifecycle. This preserves the earlier unsupported boundary, now with a specific
lifecycle refusal instead of the missing-conversation-budget error. Board wakes
and resumed delegates continue through their existing lease adapter. Enabling an
independent asynchronous board consumer needs a separately scoped lifecycle bridge.

The previously documented delegated-runtime capability repair and monetary
accounting/pools remain deferred. This change neither grants missing capabilities
nor creates a new resume, reservation or billing API.

## Local acceptance evidence, 19 October 2026

- `format`, `formatCheck` and repository `check` passed. The default server test
  run contains 5,909 passing cases; no full-db suite is part of that gate.
- `sdkCheck` passed, including Node/Python/.NET/Go typed codec and real WebSocket
  fixture conformance. Generated contracts retain 276 operations and the shared
  DTO fixture has 485 cases. Existing toolchains were selected through the
  documented overrides; no SDK was published.
- The selected PostgreSQL sweep passed 298 cases across 13 classes, covering
  conversation/source persistence, migration, concurrent claims, immutable
  lineage, board pots/lifecycle/inspection/retry, messaging, authored information
  and Global preparation. The final read-only/lineage follow-up passed 119 cases,
  including derived-continuation delegation, the independent-board refusal and
  read-only history restoration. These overlap the sweep; they are not additive
  test totals. All used disposable Testcontainers databases; the entire database
  suite and live deployment database were not run.
- Desktop's 14 board tests passed, including uncertain top-up without replay.
  The native Electron board smoke passed the top-up dialog, existing retry
  recovery, isolation, reconnect and compact layout against its mock server.
  The focused TUI/TypeScript allowance checks passed 11 cases.
- Generated manifests/contracts, unique migration versions, whitespace and
  repository documentation checks passed. The older board fixture was repaired
  to preserve migration-owned builtin authority while truncating test data.

One unchanged CLI login concurrency case failed during a broad run, then passed
its isolated rerun and full-gate rerun. Its authentication guards were not changed.
This is local source/fixture acceptance: live provider execution, deployment
adoption, production backup/restore and old-binary rollback remain unverified.

## Intended result

An allowance has its own durable row and identity. Conversations, orchestration
runs, swarm trees and other operations reference it. The existing `Budget` object
remains the runtime interface to that allowance. A conversation is an execution
record and history source; it need not be the storage owner of spending state.

If a parent has a finite allowance of 240 calls, spends 10 and delegates task
execution to a child which spends 30, both see 200 remaining. They share one row,
not copied ceilings or two counters. This example concerns delegated task work,
not compaction or SYSTEM services. An unlimited allowance has the same identity
and claim/recovery rules, with no finite ceiling. Independent operations keep
their independently selected allowance; SYSTEM is not a common wallet.

Budget exhaustion must not accidentally destroy an otherwise recoverable swarm.
An authorized operator should be able to raise its existing allowance and use
the existing member retry mechanism, retaining history and consumed calls.
Explicitly resolved/closed topics remain terminal.

## Fixed requirements

- Reuse `Budget`, including unlimited, limited, no-call and mutable finite-limit
  behavior. Do not introduce a second execution runtime or a competing budget
  object. Unlimited must not be represented by `INT_MIN` or a very large ceiling.
- Spending limits apply only where the operation's selected policy says they
  apply. Unconfigured spending policy is unlimited. Preserve explicitly selected
  finite policies and existing records; do not silently lift every stored limit.
- Preserve existing automatic increases, their policy precedence and their
  step/call distinction. An increase changes the ceiling without resetting spent.
  An unlimited allowance needs no automatic increase; a disabled allowance must
  not be activated by an increase callback.
- Compaction, skill-context summarisation and other SYSTEM service work do not
  consume the conversation's allowance or a swarm/member task allowance. Each
  uses its own service-operation allowance and applicable policy, unlimited by
  default. The conversation is an input/provenance reference, not the payer.
- Keep authorization, operation validity, inference admission and allowance
  availability separate. Having a row does not confer RUN_MODEL or resource
  access. Unlimited does not bypass cancellation, sandbox limits or tools.
- Preserve durable claims, at-most-once submission evidence, request receipts
  and reconciliation. This is model-call allowance work, not a new monetary
  accounting, billing, pooled-funding or settlement product.
- Reuse existing control operations. Do not assume a new public resume endpoint,
  a new execution permission, or universal SDK/client feature parity.
- Use explicit types at changed system boundaries, including allowance identity
  and policy selection. Avoid `var` where it obscures those relationships; an
  unrelated repository-wide style rewrite is outside this plan.

### Conversation spending versus SYSTEM service spending

Recording that a SYSTEM call processed a conversation is usage attribution. It
does **not** mean subtracting a call from that conversation's allowance. Keep
these three identities distinct: the input/provenance conversation, the SYSTEM
operation performing the work, and the allowance selected for that operation.
They can be linked for diagnosis without sharing a spending counter.

For example, a conversation with 200 calls remaining still has 200 after a
compaction call. The compaction operation records its own call against its own
allowance. It can run when the conversation's allowance is exhausted, provided
its own policy, source validity and operation fences permit it. That does not
allow the user's task execution to resume without its applicable allowance.

SYSTEM service limits, when configured, apply to the service operation; they do
not borrow from the triggering conversation. A disabled/exhausted service policy
does not fall back to that conversation's remaining calls. Other stages of one
SYSTEM operation may share its selected operation allowance, as processing and
embedding already do. Retry/recovery retains that service source and spent;
it must not mint another allowance simply to escape an applicable finite limit.

The source audit found an actual runtime dependency introduced in `a3eb771`:
compaction selected the conversation-owned/supplied allowance in
`Compaction.summarisingCall`, and skill-context summarisation claimed the budget
supplied through `SkillContexts`. SYSTEM admission additionally required a
conversation-backed claim. The correction now selects a separate allowance for
each summary operation using the existing `Budget`/`SpendingPolicy` and SYSTEM
admission. The caller-budget transcript/handoff plumbing and conversation-backed
spending requirement are removed. Actual input, cancellation and operation
validity fences remain; usage attribution is unchanged.
Skill context preparation consumes the narrow internal `SkillSummaries` contract,
which accepts source/provenance and cancellation, with no task-budget parameter.

Local acceptance: `format`, `formatCheck` and repository `check` passed. The
16 focused summary/context/SYSTEM-authority tests cover independent claims,
finite/exhausted/unlimited/no-call source allowances, asynchronous and in-turn
folding, disabled service policy retaining full history, cancellation and late
source invalidation. Repositories are mocked; no SQL/schema changed and no
full-db tests or live deployment were run. Python bytecode generation was
disabled during final checks to keep generated cache files out of the existing
source-text guard.

The deployment policy is `plowshare.agents.compaction-budget` (environment:
`PLOWSHARE_COMPACTION_BUDGET`): `unlimited` by default, `0` disabled, or a positive
call ceiling per summary operation. Each existing summary operation makes one
model call, with no automatic replay. Its claim/source is retained in existing
inference admission records. This correction does not implement a general mutable
standalone allowance row or a new summary-resume API. Moving the original task
budget into a new table alone would not have corrected the spending selection.

## Current storage and execution map

The inventory distinguishes direct conversation coupling from other allowance
stores that must participate in the same design. A separate operation row is
already better than a conversation counter, but duplicated claim implementations
still need an adapter to the shared foundation.

| Area | Current implementation and consequence | Planned change / reuse |
| --- | --- | --- |
| Ordinary conversations and root jobs/events | `ConversationStore` persists `budget_id`, `budget_total`, `budget_spent`, `budget_lifted` on `conversations`. Its bound claim callback updates that row. Root factories select finite, unlimited or native no-call budgets before logging. | Retain the selected `Budget`; attach its standalone allowance ID to the execution. Move claim/limit writes into an allowance repository. Preserve request policy and native no-call behavior. |
| Caller/nested orchestration | `Orchestrations` calls `allowanceFor(callerConversation)` and refuses a caller with no durable conversation allowance. Shared orchestration logs resolve the original owner through `operation_conversations.budget_conversation`. Recovery reconstructs that relationship. | Retain the allowance ID alongside parent authority at admission. Start and recovery resolve that ID directly. No fresh child allowance when policy requires sharing. History/input references remain separate. |
| Delegated children and acting work | Synchronous children can share a live `Budget`, but durable lookup still depends on conversation lineage. Personal acting work creates a subject conversation and finite allowance. | Persist the applicable allowance reference through existing execution/recovery records. Preserve acting proof intersection, subject identity and revocation. This does not repair the separate retained-agent capability-copy limitation. |
| Turn continuation and operator limits | `Turn` resolves conductor/delegated allowances through `allowanceFor`, can receive a board lease, and grants finite increases before resumption. Job/conversation limit controls and their comments assume spending is stored on a conversation. | Adapt continuation, grant and usage inspection together. Preserve allowed endings, active-turn exclusion and distinctions between an owning allowance and a temporary lease. Changing a wake lease must not mutate an unrelated root allowance. |
| Automatic increases | `AutomaticLimits` walks conversation/delegation parents to locate an owner and persists an increase on that conversation. Orchestration continuation has separate policy handling. | Keep owning lifecycle and policy decisions; target the same allowance row atomically. Resolve policy from retained operation context, not from whichever conversation happens to carry a counter. |
| Compaction and skill summaries | Audit baseline: compaction/skill summary borrowed task spending and required a conversation-backed claim. Correction implemented 19 October: separate SYSTEM allowances selected by service policy; no task Budget in transcript/handoff. | Preserve that separation through the standalone storage migration. Actual source conversation and purpose restrictions remain evidence, never a reason to debit task spending. |
| Swarm topic trees | `BoardPot` uses the root topic's `pot_total`/`pot_spent`, local leases and opener reserve. Member logs do not own the common pot. Full exhaustion closes the tree. | One root allowance row shared by all member/subtopic work; retain swarm lease/reserve policy and per-wake attribution. Change exhaustion recovery as specified below. |
| Board messaging | Each newly committed incoming message adds the recipient definition's allowance to its message-instance pot. `BoardMessaging` leases `BoardPot`; `JdbcBoardMessagingRepository` increments `pot_total`. | Reference the instance's allowance row. Preserve committed-message idempotency, delivery, approval, deadlines and continuation. Do not mint another allowance on poll, retry or recovery, or apply swarm closing rules to messaging. |
| Document processing | `information_revisions` stores `processing_budget_id`, `allowance_total`, `allowance_spent`. The processing repository mirrors usage into its processing conversation; catalogue allowance changes update both. | Processing revision/operation references one allowance. Remove counter mirroring; project usage from the canonical row. Existing preparation, hooks and embeddings continue sharing the selected processing allowance. |
| Document acquisition | `information_acquisitions.allowance_total` captures processing policy before admission. | Keep the captured request/policy and admission idempotency. Reference/create the processing allowance at the existing owning boundary, not a fresh allowance for each fetch, stage or embedding. Preserve explicit-only processing and metadata-only behavior. |
| Older document ingest/summariser cascade | `DocumentsConfig` still composes `IngestService`. It reads a finite ingest setting and constructs the summariser's `Budget`; chunk embedding and the cascade have distinct call paths. | Include this entry point in the source/policy audit rather than assuming the newer processing repository covers it. Preserve existing ingest outcomes and selected source boundaries; do not merge its budgets merely because both stages process one document. |
| Document ask/deliberation | Request/operation budgets cover retrieval and model stages. Ask policy defaults to unlimited unless limited explicitly. | Preserve the operation-selected source across all stages; no extra semantic-query budget when a stage already carries the ask allowance. |
| Semantic queries and memory writes | `embedding_queries` already has `budget_id`, nullable `call_limit`, `spent`, consumer kind and closure/invocation fences. It supports unlimited and selected zero-call memory-write behavior. | Keep query/write identity, invocation receipts, input selection and publication validity in the owning repository. Move only shared spending state behind the allowance repository. Query and memory-write source identities must not collapse into a budget ID. |
| Digest, Curator and other SYSTEM maintenance | SYSTEM passes select/receive budgets; some persist them through SYSTEM conversation logs. Embedding maintenance can share an existing pass allowance. | Retain operation-selected policy and validity; stop using the trace conversation as spending storage. Keep each operation's current authority/output fences and genuine shared source. |
| Harness/model consultations | Checkers, model judges, validators, schedule interpretation and other internal consumers construct or receive bounded budgets. | Audit every `Budget.of`/restore/binding site: distinguish explicit operation bounds from implicit defaults, persist where recovery requires it, and do not regenerate a spent allowance on retry. No universal SYSTEM pool. |
| Inference admission evidence | `operation_inference_budgets`, `inference_admissions` and database validation bind budget identity, ordinal and sometimes source conversation to an operation. | Point active claim validation at the canonical allowance and retained operation binding. Preserve historical IDs, ordinals, ceilings-at-claim, submission states and uncertain outcomes. |

Independent scheduled, submitted, event and Relay-triggered roots follow their
existing admission and allowance selection. They must not borrow a nearby
conversation's allowance merely because one exists. Existing recovery must
reopen the same selected allowance, rather than apply current defaults again.

### Limits that must remain distinct

Model-call allowance is different from model-step/turn limits, swarm wake quantum,
pool slot concurrency, deadlines, Relay causation hops, response byte limits and
storage capacity. A recovered budget does not repair a missing worker slot or a
TURN_CAP ending. Monetary accounting and provider quotas are separate too.

A factory search is not proof of execution: `JobRuntime.schemasOfferedTo` builds
a `Budget.of(1)` only to construct and inspect tool schemas. It must not become a
durable spending operation. Conversely, runtime gates and snapshot readers
matter even when they never construct a budget. The completion inventory must
cover `Budget` factories/binders, `allowanceFor`, counter SQL, automatic raises,
source-validation triggers, resume gates and public usage serializers.

## Shared contract and repository boundary

1. Introduce a typed standalone allowance identity and immutable snapshot. A row
   contains its ID, total spent and spending ceiling: absent ceiling means
   unlimited, zero means no calls, positive means limited. Validate nonnegative
   spent and ceilings. Keep mode explicit at the domain boundary; do not make
   callers decode a magic integer. Reuse existing `SpendingPolicy` semantics.
2. A narrow repository owns creation/reconstruction, atomic claims and ceiling
   changes. Domain services consume that contract through constructor injection;
   JDBC and transaction mechanics stay in its implementation. `Budget` binds to
   it through its existing claim and mutable-limit behavior, extended only where
   necessary. Records and private helpers need no artificial interfaces.
3. Each admitted operation retains the applicable allowance ID. Real resource
   references, operation authority, policy context and allowance identity are
   separate typed fields. Removing `budget_conversation` must not remove an
   actual input/parent conversation from authorization or add a budget ID to the
   resource target set.
4. `hasAllowance` means a reference exists; inspection of remaining calls is an
   observation. The authoritative spend decision is an atomic claim against the
   current row, before provider dispatch. No check-then-decrement race. Finite
   claims must enforce the ceiling across concurrent objects/processes; unlimited
   claims still have durable, unique ordinals and the existing evidence trail.
5. Reconstructing or retrying an operation preserves its ID, allowance and spent.
   Claim/submission evidence is reconciled under existing lifecycle rules. Do
   not refund/reset a claim or replay an uncertain provider submission just
   because a caller lost its response. Preserve existing integer overflow guards;
   changing counter width is not a reason to encode unlimited as a sentinel.
6. Limit mutation is separate from inference permission. Existing owning services
   decide whether an operator or automatic policy may increase a ceiling, and
   whether the operation remains valid. The repository applies a monotonic,
   concurrency-safe change without losing claims or duplicating increases for
   one exhausted boundary. Current-policy rechecks must not remint the allowance.
7. Usage views read the canonical counter. Deleting/archiving a trace conversation
   must not cascade away an allowance referenced by surviving work or erase its
   admission evidence. Preserve existing retention rules; this is not a new
   public allowance-management API.

## Swarms: confirmed gaps and the required recovery behavior

### What exists now

- `SwarmDefinitions.DEFAULT_BUDGET` is 100 calls. Opening uses the lower of an
  explicit request and the definition's budget. Requesting more at open does not
  raise the definition cap. The definition/parser currently requires a finite
  integer, at least two.
- `SwarmProperties` defaults to a 12-step wake cap and a 10 percent closing
  reserve. `SeatRunner` also uses that step cap to size its model-call lease.
  A retry's `maxTurns` therefore changes both the step cap and the possible call
  lease. These defaults are source defaults, not confirmed deployment values.
- `board.topup` exists over WebSocket/HTTP, and the CLI exposes a top-up command.
  Its `maxModelCalls` is the new **absolute total**, not additional calls. It can
  raise an EXHAUSTED root and reowe eligible members, but failed members still
  require retry. Framework resource/project authorization applies to top-up;
  the service method itself takes no actor.
- `board.retry` exists, with a UUID request receipt, editable `maxTurns` and the
  member's existing conversation. It requires a failed, idle, non-opener seat,
  available member allowance and an unclosed root. Both handler and service
  restrict it to the account that opened the topic. A different project manager
  or administrator is therefore not automatically a rescue operator.
- The Desktop has a member retry dialog and uncertain-request reconciliation.
  No dedicated top-up control was found in its board view. Retry can be offered
  before the server explains that allowance or ownership prevents recovery.
- Failed opener seats use the existing reply/alert wake route, rather than
  member retry. Current top-up reowes non-openers only. Recovering the pot must
  also account for an opener that stopped on the last call; otherwise an open
  tree could still have nobody able to finish it.
- `Board.settled` marks the root EXHAUSTED when only the opener's reserve remains,
  but closes the entire open tree when spent reaches the full total. Top-up and
  retry reject closed topics. This converts spending exhaustion into a terminal
  business outcome, leaving no existing reopen path.
- `BoardPot` holds unused leases in process memory and charges the root pot
  before dispatch. `BoardStore.spend` increments spent without a finite-ceiling
  predicate; current ceiling enforcement depends on the leases/local budgets.
  This needs an atomic canonical-row check, independently of operator recovery.

These are several possible reasons for the reported symptom. This audit does
not identify the recorded ending or active policy of a particular deployed swarm.

### Proposed correction, within the existing swarm lifecycle

| Situation | Required behavior |
| --- | --- |
| Finite shared allowance exhausted | Retain a recoverable EXHAUSTED state, seats, owed work and history. Do not synthesize final resolutions or close the tree solely because calls ran out. No further model dispatch without a valid claim. |
| Only the finite opener reserve remains | Preserve the existing member/opener distinction and notification behavior. Exhausting the reserve must still leave a recoverable root. Reserve affects swarm admission, not resource permissions. |
| Operator raises allowance | Use existing `board.topup` absolute-total semantics, changing the canonical row and reserve consistently. Resume existing eligible owed work; keep failed-member retry explicit. Do not reset spent or clone conversations. |
| Opener stopped for lack of calls | Reuse the opener's existing alert/reply wake route after replenishment, preserving its conversation and validity. Define one recovery notification/wake under the existing transaction/dispatch rules; do not leave it stranded or add it to the non-opener retry operation implicitly. |
| Failed member is retried | Reuse `board.retry`, its receipt and the same conversation. Authorize control against scoped WRITE and existing lifecycle rules, replacing opening-account identity as the sole gate. Managers/admins with applicable scoped authority can rescue work; this does not transfer execution authority to the operator. |
| Original executor revoked or operation invalid | Refuse further execution even if an authorized operator increased the allowance. Operator control does not impersonate the opener or silently substitute operator credentials. Reuse the original executor's live authority checks. |
| Explicit close, resolution or cancellation | Preserve terminal lifecycle rules. A budget increase must not reopen it. Do not reinterpret old closed records by matching the harness's resolution text. |
| Unlimited policy | No call ceiling or percentage reserve derived from a fictitious total. Preserve step limits, wake fairness, pool limits, validity and explicit closure. A finite wake lease remains a scheduling bound, not a replacement root spending policy. |
| Automatic increase enabled by applicable policy | Preserve the policy's finite increase semantics and continue eligible work once, with current authority. Do not make every swarm auto-increase merely because the mechanism exists elsewhere. |

Separate the wake's step limit from the call lease calculation in the contract,
even if existing configured values initially coincide. `maxTurns` remains a step
limit, not the operator's shared spending control. Keep per-wake claim attribution
for endings and settlement: subtracting two root spent snapshots is incorrect
when members run concurrently. Preserve release of unused leases and guard
stale leases after closure. The allowance repository is the final finite-ceiling
guard; swarm scheduling still owns reserve and fairness. Do not add a general
distributed reservation product to complete this change.

## Existing interfaces and necessary contract work

| Boundary | Reuse | Change that must be explicit before implementation |
| --- | --- | --- |
| Conversation requests | Existing finite `maxModelCalls` and `noBudget: true` unlimited request semantics. | Internal lookup takes allowance ID, not a conversation storage owner. Preserve invalid-input rejection. |
| Orchestration start/continuation/resume | Existing start receipts, parent lineage, cap continuation and failed-root resume. | Replace storage-dependent allowance reconstruction. Failed-root resume currently requires the original caller and remaining allowance; moving the row alone does not provide a new operator resume capability. Audit this separately from swarm retry. |
| Job/operation controls | Existing WRITE plus lifecycle rules and existing budget/cap controls. | Every recovery reader must retain the chosen source and policy. Do not invent a generic pause/resume API for a consumer that has none. |
| Swarm configuration/open/inspection | Existing definitions, selection and board operations. | Finite integer-only definition and response contracts cannot honestly represent unlimited. Define the smallest explicit unlimited form using existing spending-policy vocabulary; propagate it through protocol/SDK DTOs and affected clients/generators. Do not overload a negative integer. |
| Swarm top-up/retry | Existing `board.topup` and receipt-based `board.retry`. | Carry typed actor/resource context to the control boundary and replace creator-only retry authorization as above. Finite top-up keeps its current meaning. If changing a live finite allowance to unlimited is required through this operation, specify that extension explicitly; do not treat null as an undocumented mutation. |
| Swarm UI | Existing retry dialog, pending request reconciliation and inspection. | Expose allowance state/recovery prerequisites and access to existing top-up/retry where the client already supports board management. Distinguish exhausted, capped, slot-waiting, unavailable and closed states. Do not assume every SDK exposes every control. |
| Document processing/query sources | Existing processing allowance mutation, invocation/source repositories and SYSTEM foundation. | Preserve each operation's public policy syntax and source validity. Remove duplicated counters internally; any previously unsupported unlimited processing input needs explicit contract review rather than silent acceptance. |

`board.topup` currently has no request UUID. After an uncertain reply, reconcile
the current total before issuing another explicit change. Do not add automatic
mutation replay or claim that top-up has the retry operation's receipt semantics.
Keep control authority separate from permission to edit the policy itself:
CONTRIBUTOR execution rights must not become permission-administration rights.

The standalone storage extraction can keep current public operation names and
request shapes. End-to-end unlimited swarms and operator recovery need the
contract work above; a blanket “no API-facing impact” claim would be incorrect.
Those changes are part of this plan, not already implemented capabilities.

## Migration and cutover

1. Add new Flyway migrations after the latest version at implementation time.
   Never edit shipped migrations. Define the canonical table, references,
   constraints, claim transactions and retention rules before changing callers.
2. Preserve existing allowance UUIDs, ceilings, spent and unlimited/no-call state
   for conversations, processing revisions and query/write sources. Backfill
   shared references to one row, not one row per child. Preserve explicit finite
   values, including old swarm pots; do not retroactively make them unlimited.
   Do not create an active allowance merely because a non-owning conversation
   received a default `budget_id`. For processing-log mirrors, select the actual
   processing source as canonical and preserve any historical log-source IDs in
   the evidence mapping, rather than creating two spendable copies.
3. Give each existing root swarm/message pot a canonical allowance reference
   using its recorded total/spent. Keep topic identity, reserve and lifecycle in
   the board domain. Historical per-wake `Budget` IDs have their own ordinals;
   rewriting them to one root ID would collide and corrupt inference evidence.
   Preserve those historical identities/bindings; new claims use the canonical
   source with operation attribution.
4. Replace active inference/source validation together with its writers.
   Migrations V157, V161–V164, V166 and V168 contain relevant admission/source
   rules; verify the full current schema when designing the successor. Preserve
   actual resource fences, immutable prior submissions and unknown outcomes.
5. Cut over writers and readers without two independently authoritative counters.
   Old counters may remain as historical columns or derived compatibility views,
   but runtime must not fall back to reading/writing them. A trace/log update is
   not a second budget commit. Test rollback on failed claim/publication paths.
6. Use a controlled cutover that accounts for in-flight claims and outstanding
   work. Restart must reconstruct existing operations and reconcile receipts;
   do not replay uncertain external effects. Missing references require explicit
   classification, not a new unlimited allowance or invented authority.
7. Do not automatically reopen historical closed swarms. New exhaustion is
   recoverable under the revised lifecycle. Any historical repair needs a
   separately reviewed, evidence-based repair plan. Backups, real deployment
   adoption and old-binary compatibility require their own deployment proof.

## Implementation work packages and completion gates

These are bounded packages within this follow-on plan, not a renumbering of the
eighteen authority slices. The follow-on user instruction authorized implementation
of these packages; the implementation mapping below records the resulting boundaries.

| Package | Changes | Completion gate |
| --- | --- | --- |
| A — Contract and conversation-backed foundation | Typed allowance repository, canonical row/claims and conversation adapter; preserve existing `Budget` behavior and initial migration evidence. | Finite/unlimited/no-call reconstruction and concurrent claims work without conversation-owned counters. No active double writer. |
| B — Execution lineage and recovery | Orchestration/delegation references, root factories, automatic increases, separate SYSTEM compaction/summary allowances and retained target separation. | Parent/child task execution shares its applicable allowance across restart; compaction/summary never debit it. SYSTEM input/operation fences survive. Missing durable conversation storage no longer causes the quoted orchestration refusal when a valid allowance is retained. |
| C — Swarms and messaging | Canonical pots, finite/unlimited contracts, distinct wake limits, recoverable exhaustion, scoped operator retry/top-up and affected client controls. | Exhaust → top-up → retry works without a new conversation, lost usage or authority substitution. Explicit close stays terminal. Messaging retains its distinct committed-message lifecycle. This package is required, not optional UI polish. |
| D — Other operation sources | Processing/acquisition, query/write sources, ask, digest/Curator/maintenance and internal consultations; remove mirrored counters and storage-dependent lookups. | Every affected consumer selects policy once and retains/reuses the correct source. No fresh allowance on stage transition, retry or reconstruction. |
| E — Complete cutover evidence | Remove remaining active legacy counter paths, update generated contracts/manuals and verify cross-consumer migration/restart behavior. | Source inventory has no unexplained allowance factories, claim writers or conversation-only recovery assumptions. Supported public controls and typed decoders pass. |

A–B come first. C and D can follow once their shared contract and migration
boundaries are stable. Do not declare the work complete at A: it fixes storage
for one family but leaves the reported swarm behavior unchanged.

## Verification plan

Start with mocked repository contracts and small in-memory fakes for domain
behavior. Real database tests are required only for the new migration, SQL claim
atomicity/locking, constraints and transaction rollback; tag them `full-db` and
run the smallest selected set with `-PfullDb`, stating that reason at execution.
No database, build or runtime tests are needed to approve this documentation.

| Proof | Required assertions |
| --- | --- |
| Budget modes and mutation | Unlimited reconstructs and claims beyond a former finite default; finite stops exactly at the ceiling; no-call never dispatches. Limit increases preserve spent. Overflow and invalid ceiling/identity fail explicitly. |
| Shared concurrency | Parent and two concurrent children use one ID with unique durable ordinals. Final usage equals actual claims, not a sum of overlapping counter snapshots. Two repository clients cannot overspend a finite ceiling. |
| Recovery and evidence | Reopen after restart with unchanged usage and source. Lost responses/duplicate receipts cannot mint a second allowance or duplicate provider submission. Unknown submissions remain reconciled, not replayed. |
| Automatic increases | Enabled finite policy increases once at its existing boundary and resumes eligible work; disabled/overridden policy refuses; unlimited requires no increase. Step increases do not silently change call policy. |
| Authority separation | Having/mutating a budget never grants resource access. Revoked executor, cancelled operation and invalid source still block inference under finite and unlimited policy. A manager's control does not replace executor identity. |
| SYSTEM spending isolation | Run compaction/skill summary with the conversation finite, exhausted and unlimited: its spent remains unchanged. The service claims only its own allowance, defaulting to unlimited. A finite/disabled service policy is enforced without borrowing conversation calls; service retry/restart retains its own source. Invalid source/cancelled service still refuses. Usage attribution to a conversation must not change its spending counter. |
| Swarm finite recovery | Spend the last call with concurrent member work; root remains recoverable. Top-up retains spent, then retry continues the same member conversation. Cover a call-exhausted opener through the existing alert/reply route too. Reserve exhaustion and full exhaustion are covered separately; failed and merely owed seats behave differently. |
| Swarm limits and leases | Test a small pot with overlapping leases, lease release/restart, top-up during a wake and stale work after close. Temporary capacity held by another lease must not be mistaken for spent exhaustion. `maxTurns`, call ceiling and slot unavailability have distinct observable outcomes. |
| Swarm control authorization | Opening account and authorized scoped operator can perform permitted controls; reader/unrelated account cannot. Existing agent/token/acting ceilings still apply. Invalid retry state/opener retry and explicitly closed roots remain refused. |
| Unlimited swarms | Definition, open, inspection, DTO round-trip and wake paths represent unlimited explicitly. No fake percentage reserve or hidden 100-call fallback; finite old roots remain finite. Step/concurrency limits continue to work. |
| Messaging | Duplicate message delivery does not add allowance again; recovery does not mint more. Charged calls and unused lease release retain recipient authority and instance lifecycle. |
| Other SYSTEM sources | Processing hooks/embedding share one source without log mirroring; ask retrieval shares ask policy; query/write source closure, revision generation, profile and current-root publication fences remain effective. |
| Migration | Mixed finite/unlimited/native/shared records reconstruct correctly; historical claim IDs/ordinals and submission states survive. Backfill does not clone counters. Deleting a trace does not erase an allowance still referenced by work. |
| Client recovery | Desktop/CLI distinguish exhaustion from TURN_CAP/UNAVAILABLE/closed state, show actionable refusal, retain pending retry identity, and reconcile uncertain top-up. Test a manager rescuing another account's eligible swarm. |

Extend existing `BudgetTest`, `AutomaticLimitsTest`, `ConversationStoreTest`,
`OrchestrationResumptionTest`, `CallerOrchestrationsTest`, `BoardPotTest`,
`SeatRunnerTest`, `BoardLifecycleTest`, `BoardMessagingTest`,
`ServiceInformationProcessingTest`, `BoundQueryEmbeddingsTest`,
`BoundSystemOperationsTest` and `InferenceAdmissionPersistenceTest` where their
contracts change. Add focused repository persistence/migration tests for the new
SQL; mocks cannot prove row locking or migration correctness.

After Java implementation run `format`, `formatCheck` and the affected focused
tests; run repository `check` before handoff. If public contracts change,
regenerate their owned artifacts and run relevant protocol/SDK/client checks,
including the repository's SDK validation entry point. Source acceptance and
live restart/operator acceptance must be reported separately.

## Policy and compatibility choices to settle with the contract

- **Defaults:** use unlimited for unconfigured spending policy, as already
  agreed. Current conversation configuration defaults to 240 and swarm fallback
  to 100; explicitly replace implicit finite fallbacks for new admissions while
  preserving deliberate deployment/definition limits and existing rows. Document
  how operators retain the previous finite behavior. Never infer unlimited solely
  from an absent legacy row.
- **Unlimited wire representation:** reuse existing unlimited vocabulary and
  explicit policy/limit DTOs rather than negative sentinels. Specify the exact
  changed swarm fields and affected serializers before implementation. An
  unlimited initial swarm does not require a new live-mode-switch API.
- **Recovery control:** scoped WRITE plus existing lifecycle is the proposed
  control rule, consistent with the authority proposal. Replace the two
  creator-only retry checks deliberately; keep permission/policy administration
  separate. No generic operator takeover of orchestration or acting identity is
  implied by solving swarm recovery.

Deferred: monetary accounting/pools, new generic lifecycle APIs, historical closed
swarm repair, and the separately documented
[delegated runtime capability/authority repair](delegated-runtime-authority-todo.md).
Those are not prerequisites for extracting allowance storage, and their remaining
impact must not be described as fixed by a new budget row.

## Source pointers

- [Budget](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/Budget.java),
  [ConversationStore](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/ConversationStore.java),
  [AutomaticLimits](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/AutomaticLimits.java),
  [RequestedBudget](../plowshare-server/src/main/java/io/aeyer/plowshare/server/requests/RequestedBudget.java).
- [Turn continuation/grants](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/Turn.java),
  [compaction and skill summary](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/Compaction.java),
  [skill context preparation](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/SkillContexts.java),
  [job root admission](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/JobStore.java),
  [acting work](../plowshare-server/src/main/java/io/aeyer/plowshare/server/acting/JdbcActingWorkRepository.java),
  [document ingest](../plowshare-server/src/main/java/io/aeyer/plowshare/server/documents/IngestService.java),
  [document composition](../plowshare-server/src/main/java/io/aeyer/plowshare/server/documents/DocumentsConfig.java),
  [schema-only budget use](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/JobRuntime.java).
- [Orchestrations](../plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/Orchestrations.java),
  [operation authority](../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/OperationAuthority.java),
  [operation records](../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/JdbcOperationRecords.java),
  [SYSTEM operation purposes](../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/SystemOperations.java).
- [Board](../plowshare-server/src/main/java/io/aeyer/plowshare/server/board/Board.java),
  [BoardPot](../plowshare-server/src/main/java/io/aeyer/plowshare/server/board/BoardPot.java),
  [BoardStore](../plowshare-server/src/main/java/io/aeyer/plowshare/server/board/BoardStore.java),
  [SeatRunner](../plowshare-server/src/main/java/io/aeyer/plowshare/server/board/SeatRunner.java),
  [swarm definitions](../plowshare-server/src/main/java/io/aeyer/plowshare/server/board/SwarmDefinitions.java),
  [swarm defaults](../plowshare-server/src/main/java/io/aeyer/plowshare/server/swarm/SwarmProperties.java).
- [Retry handler](../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/BoardPostingFrames.java),
  [top-up handler](../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/BoardFrames.java),
  [project authorization](../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/ProjectAuthorization.java),
  [Desktop board controls](../plowshare-desktop/src/board.ts),
  [Desktop board rendering](../plowshare-desktop/src/renderer/board.ts).
- [Board messaging](../plowshare-server/src/main/java/io/aeyer/plowshare/server/board/BoardMessaging.java),
  [messaging repository](../plowshare-server/src/main/java/io/aeyer/plowshare/server/board/JdbcBoardMessagingRepository.java),
  [processing repository](../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/JdbcInformationProcessingRepository.java),
  [catalogue repository](../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/JdbcInformationCatalogueRepository.java),
  [acquisition repository](../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/JdbcAcquisitionRepository.java).
- [Query/write source repository](../plowshare-server/src/main/java/io/aeyer/plowshare/server/embedding/JdbcQueryEmbeddingRepository.java),
  [spending policy](../plowshare-server/src/main/java/io/aeyer/plowshare/server/embedding/SpendingPolicy.java),
  [inference binding](../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/dispatch/InferenceBinding.java),
  [inference admission repository](../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/accounting/JdbcInferenceAdmissions.java).
