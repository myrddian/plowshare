# Slice 06: Retained operation authority and conversation actions

Status: implemented on this branch, 18 October 2026, under explicit user authorization.
No new public API or deployment. The [operation specification](06-operation-authority-specification.md)
records admission and effect boundaries before enforcement.

Part of the [14-slice roadmap](../authority-substrate-implementation-plan.md).
Policy comes from [ADR 0030](../decisions/0030-authority-substrate-and-explicit-scopes.md);
the [impact report](../authority-substrate-impact-report.md) separates verified
source evidence from target behavior. The shared roadmap records source baseline,
test policy and cross-slice activation rules.

## Outcome and dependencies

Retain the actual executor, targets, required actions and operation sandbox across existing conversation/run/job boundaries. Enforce CONVERSATION_CREATE and RUN_MODEL independently where those actions occur.

**Depends on:** [02 — Explicit identities, scopes and locked built-ins](02-identities-and-scopes.md), [03 — Scoped RBAC grants and standard role helpers](03-rbac-grants-and-role-helpers.md).

Dependency completion means its relevant interfaces, migrations and verification
are available; it does not grant authority to a caller or enable an unmigrated path.

## Changes

- Wire common action checks into existing conversation creation, ordinary turns and supported standalone admissions. Empty conversation creation does not imply inference or spend.
- Generalize the existing retained-authority seam for supported consumers, retaining fixed input/output/resource limits and current account/grant checks. Later grants cannot widen an existing operation.
- Preserve tool/file/provider restrictions and agent ceilings. Non-model work checks actual READ/WRITE/control actions; introduce no EXECUTE_JOB or new tool-level RBAC.
- Map existing operation controls to WRITE plus domain eligibility/state rules. Keep cancellation-request semantics and existing recovery receipts; add no missing pause/resume/cancel capability.

## Reuse and likely source owners

These files identify current owning contracts, not a claim that they already
implement the proposed behavior. Keep services on typed interfaces and persistence
inside specialist repositories.

| Existing source | Reuse and boundary |
| --- | --- |
| [RunAdmission.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/RunAdmission.java) | Existing retained callbacks persist identity before execution. |
| [RunAuthority.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/RunAuthority.java) | Current home/resource checks supply a confinement seam; unsupported targets stay refused. |
| [JobRuntime.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/JobRuntime.java) | Existing checks around steps/tools/inference/disclosure retain hooks, cancellation and capability limits. |
| [JobStore.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/JobStore.java) | Existing admitted submission and durable job/receipt identity; generic closures are not restartable by inference. |

## Exclusions and capability boundaries

No public Global create/resume API, generic job resume service, CONTROL_EXECUTION, new capabilities for model-only workers or wholesale rewriting of orchestration/schedules in this slice.

Standard scoped RBAC helpers remain the normal management path. Finer ACL needs
must use the same substrate; this slice does not silently add an ACL editor,
deny engine, SDK feature parity or an unsupported operation. Record a missing
capability as a gap before expanding implementation scope.

## Verification and completion

- Mocks prove separate CREATE versus RUN_MODEL admission, cross-project/Personal refusal and action checks before actual effects.
- Revoke authority before dispatch and during inference: no new protected effect or answer disclosure, with incurred usage/receipts preserved.
- Child/tool targets cannot exceed retained bounds; existing cancellation remains a request rather than an immediate termination guarantee.
- Use selected PostgreSQL tests only if retained admission/receipt persistence or commit fences change; no database for pure runtime policy.

Existing regression owners to inspect and extend, selecting methods/tags that
match the eventual change:

- [AdmittedTurnTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/agents/AdmittedTurnTest.java)
- [RetainedExecutionTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/agents/RetainedExecutionTest.java)
- [JobRuntimeTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/agents/JobRuntimeTest.java)

**PostgreSQL justification:** Conditional: only retained-record persistence or real transaction/locking assertions justify selected tagged PostgreSQL tests. Runtime policy, tools and cancellation use mocks/fakes.

These are future checks, not tests run for this planning document. Add mocked
boundary/authorization tests for new behavior; existing suite links do not imply
every test is non-database. Follow the shared roadmap's focused-test, formatting
and check requirements. Completion requires the outcome and negative/recovery
cases above, with unsupported paths still explicit and no uncertain-effect replay.

## Specification required before implementation

Inventory the supported admission/continuation paths, define dispatch/revocation boundaries and preserve the existing unsupported-consumer refusal set.

## Rollout and rollback

Keep retained operation records and ceilings. A consumer unable to interpret the new authority must refuse new effects and allow only authorized receipt reconciliation.

## Implementation evidence

Ordinary conversation/run admission now captures executor, immutable scope, actual
actions and exact supporting grants in V159. Grant replacement cannot widen old
work. Queued starts, model dispatch, reviewed resource targets and settled answer
disclosure recheck that envelope. The existing acting proof remains an additional
intersection; child tools retain their parent's limits. HTTP and WebSocket resume
pass the authenticated executor explicitly. CREATE is independent of model spend.

Production composition requires the authority interface. Compatibility
constructors support isolated runtime fixtures; they are not production defaults.
Generic job closures, SYSTEM purpose classification, budget binding and later
consumer recovery remain in their owning slices. Bounded streaming answers are
disclosed whole after the final check, avoiding database calls in delta sinks.

Three selected PostgreSQL tests passed for immutable/sealed admissions and
supporting grants, atomic rollback, revoked-grant replacement refusal and
concurrent single-job binding. Mocked admission/runtime tests cover action
separation, scope relocation, queue-time revocation and withheld inference output.

Final validation: `./gradlew format formatCheck check` passed, including 5,758
default server tests. The selected `OperationRecordsPersistenceTest` passed all
three PostgreSQL cases. No live deployment was performed.
