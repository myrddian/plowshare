# Slice 12: Account acting delegation and action ceilings

Status: implemented for the existing acting consumers in this branch,
18 October 2026, under explicit authorization. No new public API or deployment.

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

The implementation evidence below distinguishes completed checks from the original
planned matrix. Existing suite links do not imply every test is non-database.
Unsupported paths remain explicit, with no uncertain-effect replay.

## Specification required before implementation

The [acting authority specification](12-acting-authority-specification.md) was
recorded before enforcement. It lists every supported operation and target.
Global/additional project read-source expansion requires a separately reviewed
supported contract and remains a gap here.

## Rollout and rollback

Retain proofs and admitted ceilings; never strip acting context to make an old consumer run. Preserve current authorized receipt reads without replay.


## Implementation and activation limits

`LiveActingAuthorization` now requires live scoped actions in addition to the
existing actor credentials, human subject, Application manifest/eligibility,
proof expiry/revocation and admitted role ceiling. READ maps to substrate READ;
WORK maps to WRITE. Internal creation and inference classifications map separately
to CONVERSATION_CREATE and RUN_MODEL. Project policy checks both accounts, using
the service token's owning grant while retaining the token's current role ceiling.
Personal policy checks only the exact subject's Personal scope and ownership;
consent creates no Application membership or role assignment.

`PersonalActingWork` checks the actual supported model agent's READ/WRITE/RUN_MODEL
requirements before opening or recording new work. New conversations and declared
synchronous child capability additionally require CONVERSATION_CREATE. A turn in
an existing bound conversation does not need creation unless its agent can create
children. Cancellation keeps WRITE and the existing job/lifecycle contract, without
requiring inference; its returned job view separately checks delegated READ before
and after the effect. Receipt/status/history reads keep READ and their existing
proof/resource correlations. An original retry returns its receipt without
re-resolving an agent or replaying an uncertain submission.

Queued work, tools, child agents and disclosure retain the fixed admitted action
requirements and live acting check. Slice 06/11 operation admissions independently
pin the subject executor and supporting grants; they never replace the acting
proof. Existing standard tool/calls/provider/filesystem fences, shared allowance,
usage settlement and private-output checks are reused unchanged. No proof,
admission, receipt, budget or database schema changed.

The five existing transport operations remain the complete whitelist. General
project acting execution, additional Global sources, independent background work,
Relay, schedules, information processing, orchestrations, approvals, native/scripted
agents and new consent/client capabilities remain unsupported under acting
requests. Direct-consumer support from earlier slices does not enable delegated
use. No new API or SDK parity commitment is included. Slices 13–14 remain proposed.

## Verification evidence

- All 205 focused acting, admitted-turn and retained-execution tests passed using
  mocks/fakes, including live scoped action refusal, actor/subject intersections,
  Personal isolation, immutable admitted ceilings, post-inference revocation with
  incurred usage preserved, cancellation, resource relocation, synchronous child
  checks, invalid-context no-fallback and uncertain receipt recovery.
- No PostgreSQL tests were run: existing proof/admission/receipt persistence is
  unchanged. Existing tagged database fixtures were updated for the mandatory
  policy dependencies and compiled with the default tests; that is not a claim
  that those database-backed scenarios were executed.
- `./gradlew format formatCheck check` passed on the final source: 5,830
  default server tests, zero failures and zero skips, plus the client/integration,
  Javadoc and formatting checks in that task. This includes the two added
  cancellation-disclosure regressions: READ before the effect and after it,
  independently of WRITE and without creation/inference authority.
- `git diff --check` and the slice documents' local-link checks passed. No live
  Application integration, push or deployment was run.
