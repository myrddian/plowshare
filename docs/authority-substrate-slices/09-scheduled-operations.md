# Slice 09: Scheduled and cron operation authority

Status: implemented on this branch, 18 October 2026, under explicit authorization.
No new public API, scheduler, lifecycle control or deployment.

Part of the [14-slice roadmap](../authority-substrate-implementation-plan.md).
Policy comes from [ADR 0030](../decisions/0030-authority-substrate-and-explicit-scopes.md);
the [impact report](../authority-substrate-impact-report.md) separates verified
source evidence from target behavior. The shared roadmap records source baseline,
test policy and cross-slice activation rules.

## Outcome and dependencies

Apply the common account/action/sandbox/budget rules when existing schedules fire, while retaining scheduling's own definition, lease and control contracts.

**Depends on:** [07 — All-inference dispatch checks and operation budgets](07-inference-and-operation-budgets.md), [08 — Sandboxed SYSTEM services and independent processing](08-sandboxed-system-services.md).

Dependency completion means its relevant interfaces, migrations and verification
are available; it does not grant authority to a caller or enable an unmigrated path.

## Changes

- Resolve the existing trigger/execution account and concrete targets at firing; recheck current authority within captured limits before effects and inference.
- Retain operation budget and authority through existing firing/recovery records. A schedule's creator/controller and its executor remain separate.
- Keep schedule definition/sync management distinct from supported pause/resume controls. WRITE control does not lend RUN_MODEL for resumed/new protected work.
- Preserve cron/time semantics, registered sources, Application execution-account/manager controls, firing IDs and uncertain-effect handling.

## Reuse and likely source owners

These files identify current owning contracts, not a claim that they already
implement the proposed behavior. Keep services on typed interfaces and persistence
inside specialist repositories.

| Existing source | Reuse and boundary |
| --- | --- |
| [AgentRunner.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/events/AgentRunner.java) | Current defining-account execution path. |
| [FileScheduleDefinitions.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/events/FileScheduleDefinitions.java) | Registered source and definition-management boundaries. |
| [ApplicationScheduleControlAuthority.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/events/ApplicationScheduleControlAuthority.java) | Live manager or admitted execution-account eligibility. |
| [ScheduleStore.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/events/ScheduleStore.java) | Existing schedule control and firing lifecycle; do not create generic running-job controls. |

## Exclusions and capability boundaries

No CRON/EXECUTE_JOB/CONTROL_EXECUTION permission, new scheduler, new schedule-control API or change to cron semantics.

Standard scoped RBAC helpers remain the normal management path. Finer ACL needs
must use the same substrate; this slice does not silently add an ACL editor,
deny engine, SDK feature parity or an unsupported operation. Record a missing
capability as a gap before expanding implementation scope.

## Verification and completion

- Mock firing tests deny revoked/downgraded authority, invalid targets and missing budget; valid configured tasks still execute.
- READ/status and WRITE control do not permit definition authoring; existing schedule pause/resume does not imply pausing/resuming a running job.
- Recovery retains original firing/admission/budget identity without duplicate effects.
- Selected PostgreSQL tests only for changed firing/source/receipt persistence and concurrency.

Existing regression owners to inspect and extend, selecting methods/tags that
match the eventual change:

- [AgentRunnerTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/events/AgentRunnerTest.java)
- [ApplicationScheduleControlAuthorityTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/events/ApplicationScheduleControlAuthorityTest.java)
- [ScheduleStoreTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/events/ScheduleStoreTest.java)
- [ScheduleFilesDatabaseTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/events/ScheduleFilesDatabaseTest.java)

**PostgreSQL justification:** Conditional: selected tagged tests for changed firing/receipt persistence, claiming and recovery races. No PostgreSQL for cron arithmetic or mocked authority policy.

These are future checks, not tests run for this planning document. Add mocked
boundary/authorization tests for new behavior; existing suite links do not imply
every test is non-database. Follow the shared roadmap's focused-test, formatting
and check requirements. Completion requires the outcome and negative/recovery
cases above, with unsupported paths still explicit and no uncertain-effect replay.

## Specification required before implementation

Classify actual actions for each existing firing family and preserve existing definition-versus-control eligibility; unsupported recovered authority refuses.

## Rollout and rollback

Fence incompatible firings and retain leases/receipts. Never manufacture a replacement firing/request identity or replay uncertain work to obtain new authority.

## Implementation and verification

The [pre-enforcement firing specification](09-scheduled-authority-specification.md)
classifies existing action families, execution identity and recovery. Targeted
firings now require conversation WRITE/work authority and explicitly pass their
trigger executor into common admission. The event remains the speaker; conversation
creator/custodian supplies no authority. Registered managed sources cannot replace
their execution principal. Conversation-targeted work retains the existing shared
allowance; untargeted work keeps configured event/worker limits.

Existing Application execution-account/manager control, definition-management and
registered-source checks are reused. Firing -> job -> retained admission -> call
claim/source correlation uses existing receipts plus slices 06–08 records. Missing
receipts/unsupported recovered authority remain held/refused, without replay or
manufactured identities. Existing scheduled-message receipt semantics are unchanged.

Focused mocked tests passed for executor-versus-creator separation, unchanged
allowance, missing executor, READ/revocation refusal, registered-principal mismatch
and Application control behavior. `formatCheck check` passed with 5,773 default
server tests. The unchanged timing-sensitive MCP credential test failed once under
parallel check load; all 100 MCP integration tests then passed in isolation and the
full check passed. No TypeScript implementation was changed. No PostgreSQL run was
needed: this slice changes no firing, receipt or budget persistence predicate.
