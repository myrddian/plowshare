# Slice 11: Orchestration and agent-child authority lineage

Status: proposed scope, 17 October 2026. Planning only; no implementation,
new public capability or deployment is authorized by this document.

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

These are future checks, not tests run for this planning document. Add mocked
boundary/authorization tests for new behavior; existing suite links do not imply
every test is non-database. Follow the shared roadmap's focused-test, formatting
and check requirements. Completion requires the outcome and negative/recovery
cases above, with unsupported paths still explicit and no uncertain-effect replay.

## Specification required before implementation

Specify each existing independent consumer's retained-authority contract; a consumer lacking one remains refused rather than dropping to ambient account authority.

## Rollout and rollback

Preserve parent/child records, request IDs and budgets. Incompatible continuations stop new effects and use existing authorized receipt/status recovery.

