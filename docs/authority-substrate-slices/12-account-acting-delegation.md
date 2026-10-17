# Slice 12: Account acting delegation and action ceilings

Status: proposed scope, 17 October 2026. Planning only; no implementation,
new public capability or deployment is authorized by this document.

Part of the [14-slice roadmap](../authority-substrate-implementation-plan.md).
Policy comes from [ADR 0030](../decisions/0030-authority-substrate-and-explicit-scopes.md);
the [impact report](../authority-substrate-impact-report.md) separates verified
source evidence from target behavior. The shared roadmap records source baseline,
test policy and cross-slice activation rules.

## Outcome and dependencies

Map the existing Application-on-behalf-of-user flow to common actions while preserving separate actor/subject proof and the currently supported target/operation set.

**Depends on:** [03 — Scoped RBAC grants and standard role helpers](03-rbac-grants-and-role-helpers.md), [06 — Retained operation authority and conversation actions](06-retained-operation-authority.md), [07 — All-inference dispatch checks and operation budgets](07-inference-and-operation-budgets.md).

Dependency completion means its relevant interfaces, migrations and verification
are available; it does not grant authority to a caller or enable an unmigrated path.

## Changes

- Map existing READ/WORK semantics to the actions actually required: supported reads; conversation WRITE plus CREATE when needed and RUN_MODEL for inference; existing status/cancel contracts.
- Intersect live actor eligibility, subject authority, manifest/token/proof limits and admitted ceiling. Subject consent does not copy roles or mutate resource membership.
- Keep Personal delegation subject-bound without adding Application Personal membership; project policy still uses the actor/subject intersection where supported.
- Preserve expiry/revocation, original request/receipt identities and resource target checks through tools/children. Invalid/unsupported acting context never falls back to service identity or SYSTEM.

## Reuse and likely source owners

These files identify current owning contracts, not a claim that they already
implement the proposed behavior. Keep services on typed interfaces and persistence
inside specialist repositories.

| Existing source | Reuse and boundary |
| --- | --- |
| [LiveActingAuthorization.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/acting/LiveActingAuthorization.java) | Current live actor/subject/grant/token policy. |
| [PersonalRunAuthority.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/acting/PersonalRunAuthority.java) | Exact Personal home/resource confinement. |
| [PersonalActingWork.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/acting/PersonalActingWork.java) | Existing durable admitted turn and receipt lifecycle. |
| [ActingRequests.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/ActingRequests.java) | Current operation whitelist; role/action mapping does not expand it. |
| [RetainedToolAccess.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/RetainedToolAccess.java) | Current resource extraction fails closed for unsupported/malformed targets. |

## Exclusions and capability boundaries

No DELEGATE role, new acting API, general project acting runtime, implicit Global read-source widening, admin impersonation or new consent workflow.

Standard scoped RBAC helpers remain the normal management path. Finer ACL needs
must use the same substrate; this slice does not silently add an ACL editor,
deny engine, SDK feature parity or an unsupported operation. Record a missing
capability as a gap before expanding implementation scope.

## Verification and completion

- Mock revocation/expiry/disabled-identity and role ceiling tests refuse new effects and output disclosure while preserving usage and receipts.
- Personal tests retain no-membership delegation and reject other users' resources; project policy tests retain both authority sides without claiming a new project runtime.
- Unsupported admin/credential/membership/tools/background operations remain refused; no acting-proof removal fallback.
- Selected PostgreSQL tests only if existing acting admission/proof/receipt persistence changes.

Existing regression owners to inspect and extend, selecting methods/tags that
match the eventual change:

- [LiveActingAuthorizationTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/acting/LiveActingAuthorizationTest.java)
- [PersonalActingWorkTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/acting/PersonalActingWorkTest.java)
- [AdmittedTurnTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/agents/AdmittedTurnTest.java)
- [RetainedExecutionTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/agents/RetainedExecutionTest.java)

**PostgreSQL justification:** Conditional: only changed persistent proof/admission/receipt records justify selected tagged PostgreSQL tests. Live policy and retained-tool behavior use mocks/fakes.

These are future checks, not tests run for this planning document. Add mocked
boundary/authorization tests for new behavior; existing suite links do not imply
every test is non-database. Follow the shared roadmap's focused-test, formatting
and check requirements. Completion requires the outcome and negative/recovery
cases above, with unsupported paths still explicit and no uncertain-effect replay.

## Specification required before implementation

List supported operations and targets explicitly. Global/project read-source expansion requires a separately reviewed supported contract and remains a gap here.

## Rollout and rollback

Retain proofs and admitted ceilings; never strip acting context to make an old consumer run. Preserve current authorized receipt reads without replay.

