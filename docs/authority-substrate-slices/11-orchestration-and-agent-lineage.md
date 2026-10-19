# Slice 11: Orchestration and agent-child authority lineage

Status: implemented for supported consumers in this branch, 18 October 2026,
under explicit authorization. No new public API or deployment. Nested/model-started
orchestration allowances remain an explicit unsupported sharing contract below.

Part of the [14-slice roadmap](../authority-substrate-implementation-plan.md).
Policy comes from [ADR 0030](../decisions/0030-authority-substrate-and-explicit-scopes.md);
the [impact report](../authority-substrate-impact-report.md) separates verified
source evidence from target behavior. The shared roadmap records source baseline,
test policy and cross-slice activation rules.

## Outcome and dependencies

Carry common authority, action limits and operation budgets through existing orchestration and agent-child lifecycles without automatic privilege inheritance.

**Depends on:** [06 — Retained operation authority and conversation actions](06-retained-operation-authority.md), [07 — All-inference dispatch checks and operation budgets](07-inference-and-operation-budgets.md).

Dependency completion means its relevant interfaces, migrations and verification
are available; it does not grant authority to a caller or enable an unmigrated path.

## Changes

- Bind parent/child scope, actor/executor, inputs/outputs, role/action ceiling and budget to current durable orchestration/child records and existing admissions.
- Revalidate actual actions at child start, continuation and result disclosure; revoked authority blocks new effects within the retained ceiling.
- Preserve explicit calls/capability declarations, child conversation lineage, cancellation and request-receipt recovery. A forced MANAGER exception does not propagate automatically.
- Keep declared independent SYSTEM services separate from ordinary child delegation; service admission is not a way to escape parent limits.

## Reuse and likely source owners

These files identify current owning contracts, not a claim that they already
implement the proposed behavior. Keep services on typed interfaces and persistence
inside specialist repositories.

| Existing source | Reuse and boundary |
| --- | --- |
| [Orchestrations.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/Orchestrations.java) | Existing durable start/resume and lifecycle ownership. |
| [OrchestrationRecovery.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/OrchestrationRecovery.java) | Existing recovery contract rather than a new generic job resume engine. |
| [RetainedAgent.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/RetainedAgent.java) | Current retained child restrictions and refusal for unsupported consumers. |
| [AgentDelegates.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/AgentDelegates.java) | Existing delegation boundary and declared child capabilities. |

## Exclusions and capability boundaries

No new orchestration/delegation modes, swarms, generic resume API, automatic MANAGER exception propagation or broader capabilities for restricted workers.

Standard scoped RBAC helpers remain the normal management path. Finer ACL needs
must use the same substrate; this slice does not silently add an ACL editor,
deny engine, SDK feature parity or an unsupported operation. Record a missing
capability as a gap before expanding implementation scope.

## Verification and completion

- Mocks prove child calls cannot expand scopes/actions, acquire parent human ADMIN or mint SYSTEM, and narrower budgets/roles survive delegation.
- Interrupted starts retain the original receipt and child lineage; retry does not create duplicate children or effects.
- Creator identity supplies no control privilege; supported operation controls retain domain eligibility.
- Selected PostgreSQL tests cover only changed start/child/receipt records and concurrent recovery fences.

Existing regression owners to inspect and extend, selecting methods/tags that
match the eventual change:

- [RetainedExecutionTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/agents/RetainedExecutionTest.java)
- [OrchestrationResumptionTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/orchestrations/OrchestrationResumptionTest.java)
- [OrchestrationsTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/orchestrations/OrchestrationsTest.java)
- [OrchestrationStoreTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/orchestrations/OrchestrationStoreTest.java)

**PostgreSQL justification:** Conditional: selected tagged persistence tests for changed durable lineage, start receipts or transactional recovery. Policy/delegate behavior stays mocked.

The implementation evidence below distinguishes completed checks from the original
planned matrix. Existing fixture links do not imply every test is non-database.
Unsupported consumers remain refused, with no uncertain-effect replay.

## Specification required before implementation

The [retained-lineage specification](11-lineage-authority-specification.md) was
recorded before enforcement. It classifies independent roots, synchronous children,
resumption and the missing nested-orchestration budget contract. A consumer lacking
a supported retained contract remains refused instead of using ambient authority.

## Rollout and rollback

Preserve parent/child records, request IDs and budgets. Incompatible continuations stop new effects and use existing authorized receipt/status recovery.


## Implementation and activation limits

This records the original slice 11 activation. The
[19 October orchestration correction](../standalone-allowance-and-recovery-plan.md#orchestration-contract-correction-19-october-2026)
supersedes its nested/model-triggered budget restriction: orchestration runs keep
their own selected allowance while deriving retained caller authority.

V162 adds immutable parent-admission edges and conversation envelopes to the existing
operation records. Envelopes retain agent identity/capability fingerprints, executor,
exact scope, parent conversation, at-most-CONTRIBUTOR actions and original budget
source. Repositories own atomic writes, ancestry locks/constraints, bounded reads and
idempotent binding checks. Historical conversation IDs do not prevent deletion;
deleted or moved retained inputs/output/budget conversations refuse new effects.
No old conversation is automatically adopted or granted replacement authority.

Independent orchestration roots retain admission inside the existing start-receipt
transaction. First turns, queued work, later conductor turns and failed-root resume
use that envelope. Every job gets a new child admission linked to the original
ceiling; it cannot replace its supporting grants. The conductor uses its retained
executor rather than its log custodian. Existing account-owned failed-root resume,
WRITE and lifecycle restrictions remain independent eligibility checks. Original
request/resume receipts suppress duplicates, including after interrupted starts.

Synchronous agent/skill/review/native-script children check the parent's actions
before log creation, acquire distinct retained child conversations and share the
existing Budget object/source and cancellation. Actual native/model actions remain
separate: a native child needs no inference action for its own CPU work and can
continue with its original zero-call allowance. A declared native intermediary
can pass on an already-retained RUN_MODEL ceiling; it cannot acquire one from
ambient grants. Management actions and forced MANAGER exceptions do not propagate. A
changed capability declaration under the same agent name refuses continuation;
existing budget/turn-limit controls do not replace its source or capability ceiling.
Acting proofs and explicit calls/file/provider/tool capabilities remain additional
bounds. SYSTEM model-only work cannot enter this ordinary delegation path.

Slice 10's new Relay orchestration-start fence is removed for independent roots:
they now enter the same retained owning start API. Original uncertainty/receipt
inspection remains read-only, and missing receipts never trigger replacement work.

Two existing consumers remain explicitly bounded gaps:

- Nested or model-triggered orchestration starts currently create fresh independent
  allowances. They cannot satisfy the retained parent-budget rule. Such starts
  refuse before creating a conversation; this slice adds no budget subdivision,
  reservation ledger or allocation API. Direct independent user/event/Relay roots
  retain their existing configured allowance. Caller-conversation launches,
  including existing bound/slash launches that use that shape, are conservatively
  refused until a supported explicit budget contract distinguishes them.
- A synchronous child with an allowance lacking a durable conversation source
  retains that live Budget identity. It cannot resume using a fresh lease/object
  after approval or restart. A retained non-conversation lease/recovery contract
  must be supported before that continuation is enabled.

These gaps are not resolved by granting more account authority or selecting SYSTEM.
The embedding/search budget gap from slices 07–08 is unchanged. Slices 12–14 remain
outside this implementation.

## Verification evidence

Mocked policy/runtime tests cover parent/child scope and action ceilings, missing
ancestry, replacement-grant refusal, builtin SYSTEM rejection, unchanged claim
identity/ordinals and budget, executor-versus-custodian separation, capability
changes on resume, current authority before recovery receipts, and unsupported
child-orchestration refusal. Existing acting/calls/cancellation/uncertainty suites
remain regression owners. PostgreSQL tests exercise V162 retention, exact supporting
grants, immutable bindings, concurrent original start keys, restart reads and late
start rollback in the existing transaction.

- `./gradlew format` and focused policy/runtime/recovery tests passed.
- `./gradlew formatCheck check` passed on the final source with 5,802 default
  server tests, zero failures and zero skips, including the affected client,
  integration, formatting and Javadoc checks in that task.
- `./gradlew :plowshare-server:test -PfullDb --tests '*OperationRecordsPersistenceTest'`
  passed all 10 selected PostgreSQL tests on the final source. No full database
  suite or database-backed deployment smoke test was run.
- `git diff --check` passed. No deployment, public API/schema catalogue change or
  client/SDK feature-parity claim is included.


The native delegate continuation guard predates native-only execution in source
snapshot `d38d55f4943f180dfbe7d4d9ff2249e2a8902874`. Commit
`261126ab1fa18d6b9b2b1f27d9c32709b59f05c4` introduced the zero-call native contract
and adjusted the ordinary Turn paths; the delegated continuation still used the
old unconditional model-budget guard. Slice 11 applies the same actual-action rule
at that child boundary and tests preservation of its zero-call source.
