# Authority substrate implementation roadmap

Status: implementation roadmap, 17 October 2026. Slices 01–07 have been explicitly
authorized and implemented in this branch; slices 08–10 are authorized for sequential implementation;
slices 11–14 remain proposed and
unauthorized. This roadmap does not authorize their execution.

## Count and scope

Recommend **14 implementation slices in total: the first documented slice plus
13 follow-on slices**. Each has its own scope document. These are reviewable
planning units, not a promise of one PR per slice, an effort estimate or a request
to execute all work. Revalidate concrete scope and source dependencies before a
later implementation instruction; split a unit further if evidence requires it.

[ADR 0030](decisions/0030-authority-substrate-and-explicit-scopes.md) defines the
settled architecture. The [impact report](authority-substrate-impact-report.md)
supplies source evidence, reuse and deferred gaps; the
[role matrices](role-scope-permissions-proposal.md) define role-facing policy.
The original runtime source baseline was main
`269854a7c5bcc5f7080bc04de01ea8ccce46eaa7`; the first-slice plan was committed
as documentation in `8f57b54`. This branch now implements only its typed project
READ/WRITE seam, slice 02's additive identity catalogue and builtin lifecycle
locks, slice 03's scoped RBAC bindings/role helpers, and slice 04's knowledge
homes, creator evidence and scoped writes; see their documents for
verification and limits.
This roadmap inventories source owners;
it is not a new exhaustive runtime/SDK audit or evidence of live deployment.

## Slice documents and dependencies

| Slice | Scope document | Direct prerequisites | Completion outcome |
| --- | --- | --- | --- |
| 01 | [Project READ/WRITE foundation](authority-substrate-first-slice-plan.md) | None | Typed READ/WRITE adapter at the existing information gate; preserve effective RBAC and current behavior. |
| 02 | [Explicit identities, scopes and locked built-ins](authority-substrate-slices/02-identities-and-scopes.md) | 01 | Introduce explicit durable scope/account/role identities alongside existing keys, with locked SYSTEM and Global records. No existing null row is automatically reclassified or made readable. |
| 03 | [Scoped RBAC grants and standard role helpers](authority-substrate-slices/03-rbac-grants-and-role-helpers.md) | 02 | Make ADMIN, MANAGER, CONTRIBUTOR and READER useful scoped helpers over the common substrate, with durable issuance/audit and the agent ceilings. Routine administration uses roles rather than custom ACL assembly. |
| 04 | [Knowledge scope, creator provenance and WRITE authority](authority-substrate-slices/04-knowledge-scopes-and-write-authority.md) | 02, 03 | Admit existing document/memory mutations through scoped authority rather than creator/legacy-owner equality, while retaining mandatory data and lifecycle restrictions. |
| 05 | [Current root-document policy and GLOBAL_READ audience](authority-substrate-slices/05-root-policy-and-global-read.md) | 04 | Use current root-document permissions for all revision reads and existing audience-management paths. GLOBAL_READ authorizes all authenticated active principal types with no individual audience exclusions. |
| 06 | [Retained operation authority and conversation actions](authority-substrate-slices/06-retained-operation-authority.md) | 02, 03 | Retain the actual executor, targets, required actions and operation sandbox across existing conversation/run/job boundaries. Enforce CONVERSATION_CREATE and RUN_MODEL independently where those actions occur. |
| 07 | [All-inference dispatch checks and operation budgets](authority-substrate-slices/07-inference-and-operation-budgets.md) | 06 | Check RUN_MODEL for the actual executor on every inference route and retain the operation's configured budget. SYSTEM and created_by do not implicitly pay. |
| 08 | [Sandboxed SYSTEM services and independent processing](authority-substrate-slices/08-sandboxed-system-services.md) | 04, 05, 07 | Execute declared internal preparation/compaction/maintenance under SYSTEM's existing authority, limited by operation inputs, tools and output destinations, independently of creator access. |
| 09 | [Scheduled and cron operation authority](authority-substrate-slices/09-scheduled-operations.md) | 07, 08 | Apply the common account/action/sandbox/budget rules when existing schedules fire, while retaining scheduling's own definition, lease and control contracts. |
| 10 | [Relay and message-triggered operation authority](authority-substrate-slices/10-relay-and-message-operations.md) | 07, 08 | Integrate common authority at existing message/Relay effect boundaries while leaving the Relay/provider capability model intact. |
| 11 | [Orchestration and agent-child authority lineage](authority-substrate-slices/11-orchestration-and-agent-lineage.md) | 06, 07 | Carry common authority, action limits and operation budgets through existing orchestration and agent-child lifecycles without automatic privilege inheritance. |
| 12 | [Account acting delegation and action ceilings](authority-substrate-slices/12-account-acting-delegation.md) | 03, 06, 07 | Map the existing Application-on-behalf-of-user flow to common actions while preserving separate actor/subject proof and the currently supported target/operation set. |
| 13 | [Existing API, scope-selection and client contract cutover](authority-substrate-slices/13-existing-contract-and-client-cutover.md) | 05, 08, 09, 10, 11, 12 | Expose the migrated semantics only through verified existing supported operations, coordinating affected contracts without requiring SDK/client parity or creating new APIs. |
| 14 | [Legacy classification, cutover and integrated migration proof](authority-substrate-slices/14-legacy-cutover-and-integrated-proof.md) | 13 | Finish the core migration with reviewed data classification, compatible enforcement and one supported end-to-end Global knowledge path, preserving durable identities and private data. |

The existing first-slice document remains authoritative for slice 01; it is not
duplicated in the slice directory. Slice 14 depends transitively on 01–13
through 13. Dependency numbers describe implementation readiness, not automatic
role inheritance, grants or operation authority.
These slice IDs are not Flyway version reservations; choose unique migration
versions against the implementation/integration baseline when that work occurs.

## Delivery milestones

| Milestone | Slices | Evidence gained |
| --- | --- | --- |
| Existing-project foundation | 01 | One production READ/WRITE consumer uses the shared contract with existing effective roles and unchanged supported access. |
| Identity and knowledge substrate | 02–05 | Explicit identities, scoped role helpers, knowledge WRITE/provenance and current-root audience policy are verified behind controlled supported boundaries. |
| Internal execution and inference | 06–08 | Actual action admission, queued inference authority, retained operation budgets and sandboxed SYSTEM services have focused proof. |
| Existing consumer integration | 09–12 | Scheduling, Relay/messaging, orchestration/children and acting retain their distinct authority and recovery contracts. |
| Supported contracts and cutover | 13–14 | Affected existing interfaces represent the new semantics; legacy classification, restart/rollback and the supported Global knowledge path are rehearsed. |

Slices 04–05 can follow the identity/grant work while 06 establishes the operation
contract. Consumer families use 06–08 as applicable and retain separate review
boundaries. Slice 13 waits for the backend consumers it exposes. Do not enable
new Global audience or SYSTEM execution through an unmigrated consumer simply
because an earlier schema or role exists.

## Boundaries that apply to every slice

- **RBAC first:** use sensible scoped ADMIN/MANAGER/CONTRIBUTOR/READER helpers.
  Complex ACLs can refine resource access through the same substrate; routine
  setup does not require custom ACLs or a new policy engine/editor.
- **Authority and sandbox are separate:** SYSTEM already has root authority;
  each operation limits its reachable inputs, tools, outputs and actions. Never
  replace that with fresh per-task SYSTEM grants or a reserved-UUID bypass.
- **Provenance is not control:** created_by confers no rights. Account home,
  creator/reviser, executor, initiating actor/subject and budget source stay distinct.
- **Audience is not publication:** current root-document policy governs revision
  access. MANAGER can choose GLOBAL_READ for managed resources without extra
  ADMIN approval, subject to mandatory/private-dependency constraints. GLOBAL_READ
  has no individual exclusions.
- **Operation validity:** independent SYSTEM preparation continues after creator
  access loss while document/operation remain valid. Current recipient authority
  still governs results/logs/notifications; delegated-user work retains live proof.
- **Actual actions and existing controls:** RUN_MODEL covers all inference;
  non-model work checks its actual actions. Supported controls use WRITE plus
  lifecycle rules. No EXECUTE_JOB, CONTROL_EXECUTION or missing control surface is
  inferred.
  Existing bounded read telemetry/index bookkeeping retains its domain contract;
  it must not require giving readers content-edit permission.
- **Operation budget:** retain an explicitly configured budget source and incurred
  usage through queueing/recovery. Model-call allowance, capacity pools and money
  reservations are different controls. Missing mechanics are recorded as gaps,
  not quietly replaced with a new billing engine or creator/SYSTEM payer.
- **Existing interfaces only:** map verified supported operations. Account/role
  decides authority, not client label. Coordinate affected existing contracts,
  but do not require SDK parity, invent methods/APIs or use private integration
  access to bypass a public gap.
- **No uncertain replay:** preserve resource/request/receipt identities, current
  delivery controls and historical costs. Recovery reconciles durable owning
  records instead of relaunching an effect to reconstruct authority.
- **Per-slice safety:** each persistence change has its own migration proof and
  rollback boundaries. Slice 14 consolidates evidence; it cannot defer safety,
  compatibility or real persistence tests needed by earlier slices.

## Verification policy

Each slice document names its security outcomes, existing regression owners and
the precise reason PostgreSQL may be needed. These lists are candidate test
owners, not commands to run entire suites or claims that all tests are mocks.

For future implementation:

1. Define the typed interface, state transitions and affected operation/target
   inventory before editing business logic. Preserve specialist repository and
   constructor-injection boundaries.
2. Use mocked repositories/effective role sources or small fakes for role/action,
   sandbox, invalid-input, revocation and capability-boundary tests. Include the
   denied-effects and disclosure cases, not just a successful helper mapping.
3. Add/select PostgreSQL tests only for actual migrations, SQL predicates,
   constraints, row mapping, transaction races, leases or durable recovery.
   State that reason, use tagged tests with `-PfullDb --tests` and select the
   smallest relevant set. Compiling an existing full-db fixture is not a reason
   to start PostgreSQL. Do not run database-backed dockerSmoke by habit.
4. After Java changes run `./gradlew format` and `./gradlew formatCheck`, the
   owning focused tests, and `./gradlew check` for handoff. Run affected existing
   SDK/contract/client checks and owning generators only where their contracts
   change; report independent failures rather than claiming skipped work passed.
5. Rehearse migration/restart/rollback with synthetic or appropriately protected
   retained-state fixtures for the affected slice. New Flyway migrations only;
   never rewrite a shipped migration or relabel historical usage as new authority.

The slice 01–07 documents record implementation checks. The original roadmap
planning task ran no Java, PostgreSQL, SDK or live integration tests; its
documentation checks are distinct from implementation verification.

## Deferred capabilities and stop conditions

The count excludes first-class tenant rollout, arbitrary ACL deny/group engines,
new ACL or billing-management UIs, a Global type/schema registry, general project
acting runtime, public SYSTEM-work continuation, new lifecycle controls and
SDK/client feature parity. General curation grant-versus-copy-versus-transfer
product behavior and a full search presentation redesign also remain deferred.
Existing dangerous release/fallback paths must still be fenced by the adopted
rules; deferral does not authorize their continued bypass during migration.

A missing public target representation, configured budget mechanism, group
authority or recovery contract is a concrete gap. The affected exposure waits
for an explicit supported solution; it does not justify silently adding a new
feature or treating document grouping as an ACL group. If such a gap changes a
slice's scope, update its plan and dependencies before implementation proceeds.

## Completion and authorization

The core migration is complete only when all affected existing consumers enforce
the adopted account/scope/action and sandbox rules, required migration/negative/
recovery evidence passes, and the supported end-to-end path in slice 14 works.
Unsupported capabilities remain explicitly unsupported.

Slices 01–07 are implemented under subsequent explicit requests. Slices 08–10
are now authorized for sequential implementation; slices 11–14 remain proposed.
Each later implementation request determines which further slices to execute; no migration
execution, credential issuance, external notification, production deployment or
automatic follow-up is scheduled by this roadmap.
