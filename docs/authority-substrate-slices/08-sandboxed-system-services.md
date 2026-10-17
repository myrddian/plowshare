# Slice 08: Sandboxed SYSTEM services and independent processing

Status: proposed scope, 17 October 2026. Planning only; no implementation,
new public capability or deployment is authorized by this document.

Part of the [14-slice roadmap](../authority-substrate-implementation-plan.md).
Policy comes from [ADR 0030](../decisions/0030-authority-substrate-and-explicit-scopes.md);
the [impact report](../authority-substrate-impact-report.md) separates verified
source evidence from target behavior. The shared roadmap records source baseline,
test policy and cross-slice activation rules.

## Outcome and dependencies

Execute declared internal preparation/compaction/maintenance under SYSTEM's existing authority, limited by operation inputs, tools and output destinations, independently of creator access.

**Depends on:** [04 — Knowledge scope, creator provenance and WRITE authority](04-knowledge-scopes-and-write-authority.md), [05 — Current root-document policy and GLOBAL_READ audience](05-root-policy-and-global-read.md), [07 — All-inference dispatch checks and operation budgets](07-inference-and-operation-budgets.md).

Dependency completion means its relevant interfaces, migrations and verification
are available; it does not grant authority to a caller or enable an unmigrated path.

## Changes

- Admit trusted existing service purposes under the locked SYSTEM identity. Validate the caller's permitted trigger separately from the internal executor; do not reroute arbitrary denied user prompts to SYSTEM.
- Bind SYSTEM worker definitions only from authorized deployment overrides and bundles. Ordinary authored/deployment bots continue under their admitted user/Application identities.
- Replace legacy creator/owner membership checks for independent processing continuation with valid document/revision generation, operation state, required inputs, SYSTEM authority, sandbox and configured budget.
- Preserve existing worker capability ceilings, hooks, stages, leases and private logs. Existing no-tool/no-delegation document workers stay model-only even though the conceptual sandbox can use inputs/capabilities.

## Reuse and likely source owners

These files identify current owning contracts, not a claim that they already
implement the proposed behavior. Keep services on typed interfaces and persistence
inside specialist repositories.

| Existing source | Reuse and boundary |
| --- | --- |
| [InformationLifecycle.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationLifecycle.java) | Existing stage queue, leases, checkpoints and captured allowance. |
| [JdbcInformationProcessingRepository.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/JdbcInformationProcessingRepository.java) | Current owner-access claim/renew/checkpoint dependency is the concrete migration target. |
| [Summariser.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/documents/Summariser.java) | Existing explicit home/account/parent inputs and derived-output writer. |
| [SystemModelTasks.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/SystemModelTasks.java) | Preserve current model-only worker restrictions. |
| [ReloadingGlobalAgents.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/ReloadingGlobalAgents.java) | Deployment/bundle snapshots and invalid-authoritative-definition refusal. |

## Exclusions and capability boundaries

No new internal worker purposes, arbitrary SYSTEM login/role assignment, public Global execution, missing cancellation API or wider model-worker tool/delegation support.

Standard scoped RBAC helpers remain the normal management path. Finer ACL needs
must use the same substrate; this slice does not silently add an ACL editor,
deny engine, SDK feature parity or an unsupported operation. Record a missing
capability as a gap before expanding implementation scope.

## Verification and completion

- Mock compaction/preparation tasks cannot read unrelated resources, change destination, self-expand or acquire new tools, despite SYSTEM account authority.
- Remove creator READ/WRITE/membership: valid queued/running SYSTEM preparation continues, but former-creator result/log/notification disclosure refuses.
- Withdraw/delete/invalidate the document or supported operation: obsolete output commits are fenced; paid attempts remain accounted.
- Selected PostgreSQL tests prove generation/lease/input validity and output commit races after removal of the owner-access dependency.

Existing regression owners to inspect and extend, selecting methods/tags that
match the eventual change:

- [CompactionTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/agents/CompactionTest.java)
- [SummariserTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/documents/SummariserTest.java)
- [InformationProcessingAuthorityTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/information/InformationProcessingAuthorityTest.java)
- [ServiceInformationProcessingTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/information/ServiceInformationProcessingTest.java)

**PostgreSQL justification:** Required for changed processing claim/renew/checkpoint SQL and generation/availability races. Keep definition/admission/tool restriction tests mocked.

These are future checks, not tests run for this planning document. Add mocked
boundary/authorization tests for new behavior; existing suite links do not imply
every test is non-database. Follow the shared roadmap's focused-test, formatting
and check requirements. Completion requires the outcome and negative/recovery
cases above, with unsupported paths still explicit and no uncertain-effect replay.

## Specification required before implementation

Specify each existing purpose's trigger, executor, input/output bindings, budget and private-log audience. Unclassified work stays refused rather than receiving SYSTEM automatically.

## Rollout and rollback

Retain SYSTEM operation identity, bounds, stage receipts and costs. An older owner-dependent worker cannot resume new operations; reconcile/hold them instead of changing executor or replaying stages.

