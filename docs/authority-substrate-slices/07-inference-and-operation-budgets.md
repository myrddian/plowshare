# Slice 07: All-inference dispatch checks and operation budgets

Status: dispatcher gate and ordinary operation bindings implemented on this branch,
18 October 2026, under explicit authorization. SYSTEM consumers cross-activate in
slice 08. Missing configured budget sources remain explicit activation gaps; no
new public API, budget configuration mechanism or deployment was added.

Part of the [14-slice roadmap](../authority-substrate-implementation-plan.md).
Policy comes from [ADR 0030](../decisions/0030-authority-substrate-and-explicit-scopes.md);
the [impact report](../authority-substrate-impact-report.md) separates verified
source evidence from target behavior. The shared roadmap records source baseline,
test policy and cross-slice activation rules.

## Outcome and dependencies

Check RUN_MODEL for the actual executor on every inference route and retain the operation's configured budget. SYSTEM and created_by do not implicitly pay.

**Depends on:** [06 — Retained operation authority and conversation actions](06-retained-operation-authority.md).

Dependency completion means its relevant interfaces, migrations and verification
are available; it does not grant authority to a caller or enable an unmigrated path.

## Changes

- Carry executor authority and sandbox through chat, streaming, embedding, reranking/validation and other existing inference side calls, including queue-time dispatch and result disclosure.
- Bind configured operation-budget references/limits at admission and retain them through queues, continuation and recovery. Compaction, scoped preparation, Global maintenance and read-side inference follow the adopted budget-source policy.
- Reuse existing model-call allowances, document processing allowance/spent records and durable inference accounting. Keep capacity pools, usage attribution and monetary spending reservations distinct.
- Refuse further inference when applicable budget/authority is missing, invalid or exhausted; preserve incurred costs and uncertain attempts without silent payer/pool switching.

## Reuse and likely source owners

These files identify current owning contracts, not a claim that they already
implement the proposed behavior. Keep services on typed interfaces and persistence
inside specialist repositories.

| Existing source | Reuse and boundary |
| --- | --- |
| [LlmDispatcher.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/dispatch/LlmDispatcher.java) | Chat callback seams already exist; embed and other paths need equivalent carriage. |
| [EmbeddingRequest.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/dispatch/EmbeddingRequest.java) | Current attribution is not authorization; preserve typed requests while adding required operation authority. |
| [DurableInferenceAccounting.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/accounting/DurableInferenceAccounting.java) | Intent/cost capture before transport and immutable attempt history. |
| [Budget.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/Budget.java) | Existing per-execution allowance is useful but is not a monetary reservation ledger. |

## Exclusions and capability boundaries

No new billing API, user-facing budget editor, global SYSTEM wallet, external-provider invoice interpretation or full monetary reservation engine from ADR 0025.

Standard scoped RBAC helpers remain the normal management path. Finer ACL needs
must use the same substrate; this slice does not silently add an ACL editor,
deny engine, SDK feature parity or an unsupported operation. Record a missing
capability as a gap before expanding implementation scope.

## Verification and completion

- Mock each inference path: unauthorized/missing budget never reaches transport; revocation while queued prevents dispatch; a bounded reader search service uses SYSTEM authority without lending it to the reader.
- Budget binding survives retry/recovery and creator access removal; exhaustion cannot switch to SYSTEM or another budget.
- Selected PostgreSQL tests, where records change, prove atomic spend/checkpoint/attempt binding and retained budget identities. Unknown prices/usage remain unknown.
- Do not claim a hard monetary limit from model-call counts or existing capacity pools.

Existing regression owners to inspect and extend, selecting methods/tags that
match the eventual change:

- [UsageAttributionTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/llm/accounting/UsageAttributionTest.java)
- [AccountingRecorderTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/llm/accounting/AccountingRecorderTest.java)
- [InferenceCaptureTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/llm/accounting/InferenceCaptureTest.java)
- [InformationProcessingAccountingTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/information/InformationProcessingAccountingTest.java)

**PostgreSQL justification:** Required when operation-budget/attempt persistence or concurrent spending predicates change; use targeted tagged persistence tests. Dispatch behavior stays mocked.

These are future checks, not tests run for this planning document. Add mocked
boundary/authorization tests for new behavior; existing suite links do not imply
every test is non-database. Follow the shared roadmap's focused-test, formatting
and check requirements. Completion requires the outcome and negative/recovery
cases above, with unsupported paths still explicit and no uncertain-effect replay.

## Specification required before implementation

Resolve each operation's configured existing budget source and record shape. If the required source/mechanics do not exist, record the exact gap and revise scope; do not invent a ledger or assert monetary guarantees.
Inventory dispatcher entry points and their direct side-call owners, including
Scribe/Learner, digest models, schedule readers and model-based validators. A
JobRuntime-only check or positive accounting allowance is not complete inference
authorization.

## Rollout and rollback

Preserve spend/attempt records and budget bindings. Stop incompatible new dispatch rather than recharge a creator, switch pools, treat unknown usage as zero or replay inference.

## Implementation and activation evidence

The [pre-enforcement specification](07-inference-budget-specification.md) records
all dispatcher routes and direct helper owners. Production composition requires
an `InferenceBoundary`; isolated fixtures retain explicit compatibility wiring.
Chat complete/stream/fold/pinned-pool and embedding refuse missing evidence. Live
checks run before queueing, on the lane worker and before result disclosure.
Bounded streaming buffers output until that final check. Metadata and model
selectors confer no authority.

V160 retains immutable operation-to-budget identities, claimed ordinals/ceiling
snapshots, single-submission fences and optional durable accounting call IDs.
Existing shared `Budget` claims now return their exact ordinal under the claim
lock; rechecking never spends again, including the last permitted call. Existing
explicit limit changes preserve budget identity. Costs/unknown outcomes remain in
the existing accounting system. Receipt records do not introduce restartable jobs.

Default `formatCheck check` passed with 5,763 server tests. Five new fake-transport
tests cover all dispatch families, real queued chat/embedding revocation, withheld
output, missing evidence and last-claim behavior. Two selected PostgreSQL tests
passed for restart-readable records, immutable source bindings, duplicate ordinal
refusal and concurrent single submission.

Activation remains gated: slice 08 classifies trusted SYSTEM purposes; the missing
search/embedding call-budget source is recorded rather than supplied from a queue
timeout, SYSTEM identity, creator or an invented default. Complete service rollout
cannot be claimed until configured sources exist. No monetary limit is claimed.
