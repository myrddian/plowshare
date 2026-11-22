# Home Assistant package delivery graph

Created: 17 November 2026. Coordinator: `/root` (this chat's primary agent).
Last reconciled: 22 November 2026 against main `6de685f63e060f3003cb93233d2567e004269755`.
Approval and current delivery progress are recorded in the linked ledger.
The node table below is the initial planning snapshot, not a live status board.
Baseline commit: `2d95d9ce4fb75e906b61e8f2afa940d04cf9bf2e` **plus the existing dirty working tree**. A commit ID alone
is insufficient to identify the current prototype or any review candidate.

The [design document](home-assistant-integration-design-and-plan.md) owns intended
behavior. This file owns the executable deliverable graph and review protocol;
[home-assistant-delivery-state.json](home-assistant-delivery-state.json) is the
coordinator-owned progress ledger. The older HA-01–HA-08 outline in the design is
a coarse roadmap; this graph is the scheduling/closure authority and gives finer
node IDs. Preserve existing unrelated work.

## Scope and controlling decisions

The user requested a graph whose deliverables have independent implementer and
reviewer agents, with reviewer approval required before completion, and appointed
`/root` to coordinate delivery. The initial plan preceded dispatch; subsequent
implementation authorizations are recorded in the ledger. The user separately
proposed the generic core
conversation-map and `ConversationOpenRequestEx` / `AgentRunRequestEx` facades.
Their general platform decision/contract is a prerequisite node under
[ADR 0001](decisions/0001-integration-boundary.md); HA-only convenience does not
justify unrelated core changes.

The only core extension in this delivery is external-to-internal conversation ID
mapping through the public Ex SDK calls; that work is already accepted. The HA
adapter uses existing SDK task input. Preserve supplied HA text without cache
rewriting, and document its actual task/user semantics rather than claiming a
native system-message role. Cache awareness and native-role equivalence are not
prerequisites for supported Conversation or AI Task delivery. No new prompt API,
runtime, schema or cache mechanism is authorized. The current correction below
supersedes the earlier native-fidelity prerequisite and pending mapping-choice
question; neither is an active delivery gate.

Prefix work is **impact assessment of HA's original prompt**, not a proposal to
correct HA's prefix behavior. Preserve its rendering, ordering and freshness.
Attachments, image generation, evaluation and HA configuration editing remain
outside this delivery graph until separately scoped.

The user has authorized committing and publishing this HA integration/recovery
working tree as a pull request, with control disabled and validation evidence
included. Merge is not part of the current request. The separately authorized
read-only Production installation, Assist connection and automatic provider
recovery are complete and independently approved below. Broader LIVE-01/device
control and optional cache measurements retain their explicit scope boundaries.
The earlier PR #300 merge and frozen source-readiness evidence are historical;
the current publication handoff records the later adapter fixes and recovery.

Implementation amendment: the user subsequently instructed “Start it”; the
coordinator-owned ledger records that authorization and the later decision to
leave prompt-dependent features gated under the earlier interpretation, now
superseded by SCOPE-01. During CORE-03 implementation, owning
source inspection found unbounded identity/authority SQL outside CORE-02's
private alias transaction. The proposed CORE-03A below is a necessary internal
SQL-budget dependency of the authorized Ex facades and the repository's existing
completion policy. It adds no public capability or prompt transformation.
Its [scoped design](reviews/home-assistant-ex-sql-budget-design.md) and this graph
amendment require independent plan approval before code dispatch. This amendment
does not claim a universal deadline fix or change unrelated unscoped operations.

## 22 November caching-gate correction — current scope

The user clarified that the prompt fidelity gate concerned caching awareness.
It must not turn potentially poor external prompt behavior into Plowshare
changes or integration barriers. SCOPE-01 implements this documentation/graph
correction under independent review. Its accepted result controls the earlier
requirements and historical outbound amendment below.

Retire `prompt_public_path_verified`; do not mark native prompt support as
implemented. The correction reopened HA-05–08, HA-10–11, VERIFY-01 and RELEASE-01
as ordinary SDK-only adapter deliverables requiring implementation, tests and
independent approval. The ledger records their current acceptance; the correction
alone did not finish them. HA-05 depends on the reviewed SCOPE-01 contract.
Preserve HA input in the existing SDK task envelope with explicit semantics;
unsupported options fail honestly and prompt content grants no authority.

PERF-01 is optional and no longer blocks RELEASE-01. Actual backend measurements,
deployment and live actions retain their separately scoped authorizations.
HA-11 subsequently removed the obsolete setup/configuration/packaging guards
under independent approval after the required adapter nodes were accepted.
This enables the existing SDK path, without adding native prompt roles or cache
mechanisms. Earlier manifests/reviews are immutable. The
[previous plan](reviews/home-assistant-cache-gate-correction-v1-previous-plan.md)
preserves the exact source used for prior outbound/SQL-fixture approvals.

## Current SDK-only delivery

The Conversation, text/flat-structured AI Task, explicit Application profiles,
installable Assist component and installed cross-boundary validation nodes have
independent approval. The [final validation evidence](reviews/home-assistant-final-validation-evidence.json)
binds the exact working-tree source and both artifact contents; the base commit
alone does not identify the reviewed delivery. The ledger owns node completion
and separate merged/deployed/live milestones. RELEASE-01 adds the
[release runbook](../integrations/home-assistant/RELEASE-RUNBOOK.md) and source
readiness report; it is complete only after its independent review is accepted.

The final checks passed 135 outer HA tests, including three isolated installed
consumer cases, Ruff, the default repository check and HA distribution build,
eight fresh installed outbound checks, and unchanged SDK package conformance.
The validated HA baseline is 2026.9.4/Python 3.14. The later installation
preflight confirmed that target version; no HA upgrade was needed. No further core, SDK, shared-runtime or schema extension was
needed for these remaining adapter features. Existing HA text is preserved as
ordinary task/user input without prefix-cache rewriting.

PERF-01 remains optional backend measurement. The user subsequently authorized
the dedicated Production Application and Assist connection described below.
The separately authorized read provider is installed and its first scoped read
has passed with independent deployment approval. Broader LIVE-01
voice/device acceptance remains incomplete.

## 23 November live Assist follow-up

The user authorized the dedicated read-only HA Application on Production and
completion of Assist. The component is installed; the Application, scoped service
credential, HA Conversation/AI Task entities and preferred read-only pipeline are
configured. A live no-tool first-turn check exposed a baseline adapter defect:
HA's native Assist pipeline opens its session and chat log before invoking the
entity, so its first input already has a conversation ID. The adapter rejected
that fresh ID before any SDK/model submission.

HA-FIX-01 depends on HA-06, HA-11 and VERIFY-01. Its independent implementer and
reviewer are `/root/ha_assist_first_turn_implementer` and
`/root/ha_assist_first_turn_reviewer`. The adapter correction is independently
approved: 141 outer HA tests include six real native-pipeline regressions and
three installed-consumer cases; Ruff and the default repository check passed.
The refreshed 0.1.1 component was installed after a supported configuration
backup and source/configuration/journal checks. Normal HA stop/start succeeded.
No core, SDK, shared-runtime, schema, cache mechanism or device-control extension
was required. Earlier candidates and reviews remain immutable.

The dedicated `home-assistant-readonly` Application is registered permanently as
DISJOINT with a separate project-scoped Contributor service credential. Both
`house_reader` and `house_task` are served; the HA entry selects them for
Conversation and AI Task respectively. The preferred **Plowshare (read-only)**
pipeline has control disabled and local-intent preference off, preserving the
original HA assistant and existing STT/TTS settings. A fresh native Assist text
chat returned **“Plowshare Assist is connected.”** Its retained Production job
confirms one model call, zero tool calls and an answered completion. The
[Production setup receipt](reviews/home-assistant-production-assist-setup.md)
binds the configuration and artifact evidence; the ledger records the separate
independent phase approval.

The user subsequently supplied the separate HA token and selected the current
68 Assist-exposed entities as a fixed state/unit allowlist. The provider is now
installed on Production as a separate container, with no control block, actions
or workflow subscriptions. New HA exposure does not silently expand its scope.
No core, SDK, shared-runtime, schema or main-server/HA restart was needed for
this provider continuation.

HA-FIX-02 depends on HA-03 and OUT-VERIFY-01. Its implementer is `/root`, with
independent reviewer `/root/ha_deployment_reviewer`. The first live discovery
worked but the served model sent revision objects as JSON strings. The adapter
now inlines the same strict object schema in all read tools, including cursors;
strings remain invalid. Independent source approval, 185 HA Java cases, the
repository check and eight fresh installed checks bind this correction. This is
adapter schema presentation; no core reference-conversion defect is claimed.

DEPLOY-01 depends on both accepted follow-ups. After the initial job finished,
the old provider stopped normally with confirmed withdrawal and no unresolved
receipts. Its code, configuration, container and journal are retained. The new
schema fingerprint has a separate owned journal. The replacement is running;
a fresh HA Conversation API request through house_reader returned an available,
nonstale selected sensor reading and unit. Its retained Production job confirms
four model calls, three searches and one successful read, with no control tools.
The [read-provider receipt](reviews/home-assistant-read-provider-production-setup.md)
binds actual artifact/config/runtime and public-protocol evidence. Final
independent deployment acceptance is recorded in the ledger.

No microphone/audio/TTS, AI Task generation or physical-device acceptance is
claimed; the broader LIVE-01 matrix remains open. The existing project token's
expiry still requires rotation.

## 23 November automatic read-provider recovery

RECOVER-01 depends on DEPLOY-01 and HA-FIX-02. Root implements the provider-only
operations and documentation; `/root/ha_recovery_implementer` owns the real-SDK
recovery regression cases, with `/root/ha_deployment_reviewer` independent of
both authors. The existing container now uses `unless-stopped`, retaining its
exact configuration, credentials, image and persistent journal. Docker is enabled
at boot. No runtime/core/SDK change or second provider is required.

Process-exit and provider-only connection-outage checks both recovered the same
container and confirmed a fresh unexpired catalogue. Journal identity/owner and
original replay fences remain; no existing jobs changed or were duplicated.
A fresh HA Conversation read completed successfully afterward. All 189 HA Java
tests and the repository checks passed. The [recovery receipt](reviews/home-assistant-read-provider-recovery.md)
binds the actual evidence; final independent acceptance is recorded in the ledger.

Ordinary outages recover automatically; absent uncertain publication receipts,
invalid credentials and corrupt/mismatched journals remain fenced for inspection.
An operator stop remains respected. Host boot recovery is configured and Docker's
boot setting verified; the shared host was not rebooted for acceptance. Control
remains disabled; the broader LIVE-01 matrix and optional cache measurements are
unchanged.

## 22 November outbound-only amendment — historical scope

The user authorized updating this graph/ledger and implementing independently
reviewed outbound-only packaging, profiles and validation. PR #300 merged the
original thirteen accepted deliverables as `16dec24b`; their merge milestones now
reflect that fact. Historical manifests, reports and frozen ledger snapshots are
immutable. Acceptance records remain evidence for their exact candidates, not a
claim that every subsequent main revision was independently reviewed by those
original agents.

Current main owns the timeout contract: actual SQL queries/transactions have a
configurable two-second default limit; nested statements share their transaction's
remaining budget. Independent transactions, hooks, common authorization, durable
job admission and reply delivery do not inherit an aggregate two-second workflow
lifetime. Slow accepted workflows warn without cancellation or replacement.
CORE-02/03A/03 acceptance text below is reconciled to that current contract; old
reports retain the older requirements as historical evidence. Adapter/provider
IO, polling and cleanup keep their own explicit finite resource budgets.

The new branch consists of OUT-PLAN-01, OUT-PACK-01, OUT-PROFILE-01 and
OUT-VERIFY-01. OUT-PLAN-01 freezes the common operator contract and independent
review before source dispatch. Packaging and profiles may then proceed in parallel
with disjoint ownership. Validation starts after both are independently accepted.
This branch has no prompt-fidelity dependency because it invokes existing
Plowshare agents/tools rather than offering an HA model endpoint. It neither
satisfies nor removes any original gate/dependency.

At outbound acceptance, all eleven original awaiting-gate nodes remained
awaiting_gate. HA-05–08, HA-10–11, VERIFY-01, PERF-01 and RELEASE-01 were
blocked under the superseded faithful prompt/context requirement;
DEPLOY-01 and LIVE-01 retain release dependencies and their separate operational
authorizations. No external system-context API, task-text substitute, prompt
rewriting, Assist enablement or installable HA custom component is authorized.
Prefix caching remains impact assessment of integrator-supplied prompts. Outbound
source/artifacts may be prepared and tested locally; this amendment authorizes no
new publication, target-branch merge, deployment, HA restart or live model/device
operation.

The named provider host is isolated from the legacy runtime's event workflows.
It admits no routes, scripts, causal-feedback configuration or selected event
subscriptions. It does not synthesize cross-host effect correlation. Operators
must retain the existing documented separation from legacy feedback-dependent
workflows; a future shared correlation owner requires its own reviewed scope.

## Independence and closure protocol

1. Root dispatches a ready node to its named implementer role only after its
   predecessor nodes are accepted and any external gate is satisfied. Each
   dispatch records actual agent ID, file ownership and the candidate base.
2. The implementer owns only its assigned files, implements and verifies the
   contract, then reports **READY FOR REVIEW** with artifact hashes, changed files,
   tests actually run, failures/limits and remaining findings. It cannot close
   its own node or write its own approval.
3. Root freezes an exact candidate manifest: base commit, working-tree diff and
   SHA-256 of every authored file (including untracked files), dependency candidate
   manifests and relevant artifact/package hashes. Freeze a pre-review copy of the
   ledger and hash that snapshot in the external review manifest; do not hash the
   active ledger into itself. Do not review HEAD alone when the tree is dirty.
   Mutable ledger bookkeeping is separate from reviewed implementation artifacts;
   the frozen snapshot preserves the exact plan/assignment state the reviewer saw.
4. Root dispatches a **different actual agent** in the dedicated reviewer pool.
   The reviewer reads the owning sources, actual diff and evidence independently;
   the implementer's narrative is not proof. The reviewer must not have authored
   any implementation being approved. Review roles never rotate into implementation
   of the same reviewed material, and reciprocal approvals are not a substitute
   for independence. Root assigns a fresh reviewer if authorship overlaps.
5. The reviewer issues `APPROVE` or `CHANGES_REQUIRED`, bound to that candidate
   manifest. Approval requires acceptance cases and checks to pass, blocking
   findings to be resolved, and all limitations to match the node's explicit goal.
   Each finding has an ID, severity, location, correction/evidence and reviewer
   disposition. The reviewer proposes changes; the implementer authors fixes.
6. Root checks the verdict, candidate hashes, predecessor approvals, gate state
   and evidence completeness before changing node status to `accepted`. Root
   cannot waive reviewer rejection or supply reviewer approval on its own. A node
   may be marked finished **only after independent reviewer APPROVE**.
7. Any substantive source, generated contract, packaging or dependency integration
   change invalidates the affected approval and reopens review on the new
   candidate. Pure ledger progress bookkeeping does not alter an implementation
   candidate. Re-run only checks whose evidence changed or remains unresolved.
8. Root alone updates the ledger after receiving agent reports. This Markdown
   graph and initial node table are the reviewed plan specification; current
   progress changes only in the JSON ledger. Preserve history instead of replacing
   an old verdict with a new claim. A plan/graph change is a new review candidate.
   Keep merge, deployment and live validation evidence separate from `accepted`.

Implementer responsibility persists after review. A reviewer pass does not mean
merged, deployed or live validated. Prototype files, prior tests and an installed
component are starting evidence, not automatic closure of any feature node.

### Status and evidence fields

Node status moves `planned → implementing → ready_for_review → in_review →
accepted`, with `changes_required` returning to implementation. `awaiting_gate`
records a concrete external prerequisite; `reopened` records invalidated approval.
The ledger also retains separate implementation (`not_started`, `ready`), review
(`not_started`, `in_review`, `APPROVE`, `CHANGES_REQUIRED`), merge, deployment and
live-validation states. Only root records changes and timestamps.

Every review report records node ID, actual reviewer agent ID, implementation
agent ID, candidate manifest/digest, scope reviewed, checks actually run, results,
findings and explicit verdict. Review report paths are assigned on dispatch under
`docs/reviews/`; reports contain no credentials, private addresses, prompts or
runtime dumps. Root records report path and digest in the ledger.

### Gate registry

| Gate | Required proof before consuming nodes can start/close |
| --- | --- |
| `prompt_public_path_verified` | Retired by SCOPE-01 following user clarification; no current consumer and no native-role capability claim |
| `cache_backend_measurement_authorized` | Explicitly authorized non-side-effecting backend access, finite workload and protected evidence handling; local prompt-variation fixtures alone do not establish real cache impact |
| `deployment_authorized` | Exact target/artifact scope, configuration/backup/rollback plan and any required restart authorization |
| `live_acceptance_authorized` | Exact live model/device/voice/restart checks and permitted reversible target actions |

The three operational authorization gates remain `not_satisfied` until their
actual scope is authorized. The former prompt gate is `retired`, not satisfied.
No node may require it. Missing native prompt-role semantics are documented SDK
limitations, not permission to extend core or to hold supported adapter work.
Backend measurement remains optional and cannot block release readiness.

## Graph

An arrow means the predecessor must be accepted before implementation/acceptance
of the successor. Dashed external-gate edges add prerequisites; they are not
completed tasks. All nodes are deliverables, including investigation/decision
nodes with explicit evidence goals.

```mermaid
flowchart TD
  P["PLAN-00 Delivery plan"] --> A["AUDIT-01 Baseline audit"]
  A --> C1["CORE-01 Facade decision/contracts"]
  C1 --> C2["CORE-02 Alias repository/migration"]
  C2 --> SB["CORE-03A Scoped SQL completion"]
  SB --> C3["CORE-03 Ex facades"]
  C3 --> C4["CORE-04 All SDKs/catalogues"]
  A --> F["FIDELITY-01 Prompt public-path decision"]
  A --> D1["HA-01 Discovery contracts"]
  D1 --> D2["HA-02 Discovery/reads"]
  D2 --> D3["HA-03 Read ToolProvider"]
  D3 --> D4["HA-04 Action provider"]
  P --> OP["OUT-PLAN-01 Outbound amendment"]
  A --> OP
  OP --> OH["OUT-PACK-01 Provider host/package"]
  D3 --> OH
  D4 --> OH
  EV --> OH
  C4 --> OH
  OP --> OT["OUT-PROFILE-01 Outbound profiles"]
  D3 --> OT
  D4 --> OT
  EV --> OT
  C4 --> OT
  OH --> OV["OUT-VERIFY-01 Outbound validation"]
  OT --> OV
  SQ["PLAT-SQL-01 SQL cleanup fixture"] --> OV
  C4 --> G["HA-05 Shared gateway"]
  OV --> SC["SCOPE-01 Cache gate correction"]
  SC --> G
  G --> CV["HA-06 Conversation Entity"]
  G --> AI["HA-07 AI Task text"]
  AI --> ST["HA-08 Structured data"]
  D4 --> EV["HA-09 Events/feedback"]
  D4 --> AP["HA-10 Application profiles"]
  CV --> AP
  AI --> AP
  EV --> AP
  CV --> PK["HA-11 Package/docs"]
  AI --> PK
  ST --> PK
  AP --> PK
  C4 --> V["VERIFY-01 Integrated validation"]
  D4 --> V
  CV --> V
  AI --> V
  ST --> V
  EV --> V
  AP --> V
  PK --> V
  V --> PF["PERF-01 Cache impact"]
  F --> PF
  CV --> PF
  AI --> PF
  BG{{"Authorized backend measurement"}} -.-> PF
  V --> R["RELEASE-01 Readiness/runbook"]
  CV --> FX1["HA-FIX-01 Native first turn"]
  PK --> FX1
  V --> FX1
  D3 --> FX2["HA-FIX-02 Inline read revision schemas"]
  OV --> FX2
  R --> DP["DEPLOY-01 Installation"]
  FX1 --> DP
  FX2 --> DP
  DG{{"Authorized deployment"}} -.-> DP
  DP --> RC["RECOVER-01 Read-provider automatic recovery"]
  FX2 --> RC
  DP --> LV["LIVE-01 Live acceptance"]
  LG{{"Authorized live checks"}} -.-> LV
```

## Initial node/role snapshot

Roles in the next table are planned assignments, not a claim that those agents
are already running. Root records actual IDs at dispatch. The role pools ending
`_implementer` and `_reviewer` are separate; a reviewer agent must be independent
of authorship in the reviewed candidate. PLAN-00 has the actual implementer shown
and a reviewer reserved for independent dispatch.

| Node | Deliverable | Dependencies | Implementer agent role | Independent reviewer agent role | Initial status |
| --- | --- | --- | --- | --- | --- |
| PLAN-00 | Delivery graph and review protocol | — | `/root/ha_plan_implementer` | `/root/ha_plan_reviewer` | `ready_for_review` |
| AUDIT-01 | Baseline and capability audit | PLAN-00 | `ha_audit_implementer` | `ha_audit_reviewer` | `planned` |
| CORE-01 | Generic facade decision and contracts | AUDIT-01 | `ha_core_contract_implementer` | `ha_core_contract_reviewer` | `planned` |
| CORE-02 | Conversation alias repository and migration | CORE-01 | `ha_core_storage_implementer` | `ha_core_storage_reviewer` | `planned` |
| CORE-03A | Scoped SQL completion for Ex admission | CORE-02 | `ha_sql_budget_implementer` | `ha_sql_budget_reviewer` | `planned` |
| CORE-03 | Thin Ex operation facades | CORE-03A | `ha_core_facade_implementer` | `ha_core_facade_reviewer` | `planned` |
| CORE-04 | Shared catalogues and every SDK | CORE-03 | `ha_sdk_implementer` | `ha_sdk_reviewer` | `planned` |
| FIDELITY-01 | Prompt-role fidelity and public capability decision | AUDIT-01 | `ha_fidelity_implementer` | `ha_fidelity_reviewer` | `planned` |
| HA-01 | Discovery and read contracts | AUDIT-01 | `ha_discovery_implementer` | `ha_discovery_reviewer` | `planned` |
| HA-02 | Discovery and batched reads | HA-01 | `ha_read_implementer` | `ha_read_reviewer` | `planned` |
| HA-03 | Named read tool provider | HA-02 | `ha_read_provider_implementer` | `ha_read_provider_reviewer` | `planned` |
| HA-04 | Action provider and receipts | HA-03 | `ha_action_implementer` | `ha_action_reviewer` | `planned` |
| HA-05 | Shared typed request gateway | CORE-04, SCOPE-01 | `ha_gateway_implementer` | `ha_gateway_reviewer` | `planned` |
| HA-06 | Conversation Entity lifecycle | HA-05 | `ha_conversation_implementer` | `ha_conversation_reviewer` | `planned` |
| HA-07 | AI Task text provider | HA-05 | `ha_ai_task_implementer` | `ha_ai_task_reviewer` | `planned` |
| HA-08 | Structured AI Task subset | HA-07 | `ha_structured_implementer` | `ha_structured_reviewer` | `planned` |
| HA-09 | Observation routing and feedback | HA-04 | `ha_events_implementer` | `ha_events_reviewer` | `planned` |
| HA-10 | Reusable Application profiles | HA-04, HA-06, HA-07, HA-09 | `ha_profiles_implementer` | `ha_profiles_reviewer` | `planned` |
| HA-11 | Installable package and operator docs | HA-06, HA-07, HA-08, HA-10 | `ha_package_implementer` | `ha_package_reviewer` | `planned` |
| VERIFY-01 | Integrated independent contract validation | CORE-04, HA-04, HA-06, HA-07, HA-08, HA-09, HA-10, HA-11 | `ha_verification_implementer` | `ha_verification_reviewer` | `planned` |
| PERF-01 | Optional HA prefix-cache impact assessment | FIDELITY-01, HA-06, HA-07, VERIFY-01 | `ha_performance_implementer` | `ha_performance_reviewer` | `planned` |
| RELEASE-01 | Release readiness and rollout runbook | VERIFY-01 | `ha_release_implementer` | `ha_release_reviewer` | `planned` |
| DEPLOY-01 | Separately authorized installation | RELEASE-01 | `ha_deployment_implementer` | `ha_deployment_reviewer` | `planned` |
| LIVE-01 | Separately authorized live acceptance | DEPLOY-01 | `ha_live_implementer` | `ha_live_reviewer` | `planned` |

## Deliverable contracts

Paths with angle-bracket components are proposed files/directories to be finalized
at dispatch, not claims that those files already exist. Directory ownership is
exclusive while the node is active; root splits or serializes overlapping paths.

### PLAN-00 — Delivery graph and review protocol

Produce this acyclic graph, ownership assignments, ledger schema, gates and independent-review closure protocol.

**Owned outputs:** `docs/home-assistant-delivery-plan.md`; `docs/home-assistant-delivery-state.json`.

**Acceptance evidence:** Parse JSON, validate dependency references/acyclicity, check local links and ensure every node has different implementer/reviewer roles. Independent reviewer checks the actual plan and ledger snapshot; root records APPROVE before closing PLAN-00.

### AUDIT-01 — Baseline and capability audit

Inventory the dirty starting tree, existing Assist prototype, adapter/runtime, public SDKs and HA 2026.9.4 contracts. Record exact artifact hashes and existing test evidence, remaining gaps, supported action/selector subset and MCP-versus-native transport decision.

**Owned outputs:** `docs/reviews/home-assistant-baseline-audit.md`.

**Acceptance evidence:** Source pointers for every current-behavior claim; compare existing tests to actual files; identify every unimplemented capability and dependency. Do not import earlier test counts as current acceptance. No live mutation, model call or database suite needed.

### CORE-01 — Generic facade decision and contracts

Record the general platform need and exact Ex wire/DTO contract before code: authenticated project/source/owner key, external ID grammar, authorization, lifecycle, atomic get-or-open, existing-limit semantics, resolve-only Run-Ex, ordinary result shapes and normal API compatibility.

**Owned outputs:** `docs/decisions/<new-conversation-alias-decision>.md`; `docs/home-assistant-integration-design-and-plan.md`; `docs/sdks.md`.

**Acceptance evidence:** Independent review approves ADR 0001 rationale using this conversation’s explicit generic core proposal. Resolve on-miss and namespace/ownership choices. Explain why no pre-selection hook or adapter database access is used, and that alias lookup does not deduplicate utterances.

### CORE-02 — Conversation alias repository and migration

Implement a narrow typed resolver/repository and a new migration enforcing the approved full alias key and target integrity. Coordinate ordinary conversation creation and alias binding transactionally; serialize conflicting opens without orphan conversations or duplicate open notifications.

**Owned outputs:** `plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/<new-alias-contract-and-repository>`; `plowshare-server/src/main/resources/db/migration/<new-version>`; `plowshare-server/src/test/java/<selected-alias-tests>`.

**Acceptance evidence:** Mock service tests cover scope/ownership denial, deleted/archived targets and explicit retention/reset policy. Selected @Tag("full-db") / -PfullDb tests prove uniqueness, concurrent first-open races, rollback and migration constraints. State the database-specific reason before each selected run. Verify each actual SQL query/transaction uses its configured two-second default completion limit, normally completes within 500 ms, and includes acquisition/waits/materialization. Nested SQL shares its transaction remaining time; independent SQL units and the surrounding workflow do not share an aggregate deadline. Inspect owning enforcement and report gaps.

### CORE-03A — Scoped SQL completion for Ex admission

Retain the internal SQL-budget infrastructure under the current owning contract. Each hard scope covers one actual query or SQL transaction, including acquisition, execution/materialization and bounded physical cleanup. Nested SQL shares the transaction's earlier deadline. Preserve raw primary/pool identity, repository ownership and unscoped compatibility; never hold a connection across hooks, network calls or scheduling. The original design/report records its historical Ex-wide scope and must not override the current contract in ADR 0077 and the persistence/transport guides.

**Owned outputs:** `plowshare-server/src/main/java/io/aeyer/plowshare/server/pool/<scoped-sql-budget-interface-infrastructure-and-composition>`; `plowshare-server/src/main/java/io/aeyer/plowshare/server/cache/ProjectionCacheConfiguration.java` (only necessary lease composition); `plowshare-server/src/main/java/io/aeyer/plowshare/server/cache/<Ex-private-miss-mode-and-atomic-existing-read>`; `plowshare-server/src/test/java/<scoped-budget-and-selected-PostgreSQL-tests>`; `docs/reviews/home-assistant-ex-sql-budget-design.md`; `docs/reviews/<scoped-sql-budget-implementation-evidence>`. CORE-03 owns wiring the accepted interface into its facade and its facade fixtures; coordinate that handoff instead of concurrent edits.

**Acceptance evidence:** Mock/barrier tests prove finite actual SQL-unit deadlines, earlier nested transaction deadlines, materialization cancellation, transaction and independent lease coverage, bounded physical occupancy, cancellation/close/reuse fencing, preserved caller interrupts, typed query failures with commit uncertainty and bounded cleanup. Backpressure applies before dispatch and must not cancel accepted work; workflow duration warnings cannot replace an accepted job/result. Preserve warm authorized cache hits and existing-fill ownership. Select tagged PostgreSQL tests only for actual locking, cancellation, rollback or pool reuse that mocks cannot establish; retain exact historical evidence separately. A SQL-only scope does not establish universal cancellation or a whole-call deadline for hooks/file loading.

This node is a prerequisite discovered during implementation. Existing CORE-03 semantic work was dispatched against accepted CORE-02; its completion and new scoped-budget wiring now require accepted CORE-03A. Root records that transition in the ledger, not as an implicit approval or a finished facade.

### CORE-03 — Thin Ex operation facades

Deliver the canonical wire/request/schema definitions, FrameTypes/dispatch registration and owning generated outputs required by the Ex handlers, then implement both server facades over the resolver and existing conversation/run owners. This node must compile and test without a later SDK node. Translate the external locator correctly and preserve ordinary post-commit/lifecycle ownership; avoid server self-RPC.

**Owned outputs:** `plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/<Ex-handlers-FrameTypes-and-routing>`; `plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/<owning-admission-seams>`; `plowshare-server/src/main/java/io/aeyer/plowshare/server/api/<Ex-request-contracts>`; `plowshare-protocol/<required-shared-contracts>`; `sdk/typescript/src/operations/<canonical-Ex-contracts>`; `scripts/generate-sdk-contracts.mjs`; `sdk/<owning-generated-contract-outputs>`; `plowshare-server/src/test/java/<facade-tests>`.

**Acceptance evidence:** Prove one client request reaches the ordinary lifecycle; include authorization before disclosure, actual target scope validation, incompatible routing, run-on-miss refusal, existing allowance preservation, no repeated log.open, concurrent opens, run exclusion, cancellation and uncertain submission. Assert useful resolved snapshots are reused; repeated reads require an authority freshness/concurrency reason. Use accepted CORE-03A infrastructure at actual repository SQL-unit boundaries without an outer facade transaction or aggregate workflow timeout. Verify bounded intake/reply ownership, existing non-SQL resource budgets and warning-only slow workflow observations; SQL-only enforcement must not become a whole-call deadline claim. Existing non-Ex API tests remain passing.

### CORE-04 — Shared catalogues and every SDK

Consume the canonical, compiling Ex wire contract from CORE-03 and complete all public SDK/client conveniences, command surfaces, documentation and cross-language conformance. Ex requests use explicit external selectors, typed replies and compatible old operations in Java, TypeScript/Node, Python, Go and .NET. Regenerate through owning scripts for any approved upstream correction; canonical changes reopen CORE-03 review.

**Owned outputs:** `sdk/typescript/<client-command-surfaces-and-tests>`; `sdk/java/<public-helpers-and-tests>`; `sdk/node/<public-helpers-and-tests>`; `sdk/python/<public-helpers-and-tests>`; `sdk/go/<public-helpers-and-tests>`; `sdk/dotnet/<public-helpers-and-tests>`; `test-support/contracts/<owning-fixtures>`; `docs/sdks.md`.

**Acceptance evidence:** Generator drift check, malformed/missing/conflicting selectors, refusal and unknown-delivery conformance, actual transport facade round trips and old-request compatibility. Run check, sdkCheck and required installed-consumer sdkPackageCheck when package surfaces change; missing toolchains are unresolved evidence, not a PASS. Verify generated files are not hand-edited.

### FIDELITY-01 — Historical prompt-role investigation

This accepted investigation and its frozen report record the earlier requirement.
SCOPE-01 supersedes its proposed native-role delivery prerequisite. It does not
authorize a platform successor or make cache behavior an integration blocker.

Specify exact Conversation and AI Task prompt/context semantics and inspect available public routes. Decide whether a documented task-envelope adaptation satisfies the requirement or native system-role fidelity is necessary. Identify the existing SDK limitation explicitly; do not silently demote HA system content.

**Owned outputs:** `docs/reviews/home-assistant-prompt-fidelity-decision.md`; `docs/home-assistant-integration-design-and-plan.md`.

**Acceptance evidence:** Review actual request fields and prompt assembly, versioned HA behavior, trust/precedence, compaction and resume. Publish a supported public-path decision or an exact capability-gap report. APPROVE of this investigation is not approval of an unimplemented capability. The earlier native-role capability finding remains historical evidence; the retired gate has no current consumers. Use existing SDK semantics for supported adapter work.

### HA-01 — Discovery and read contracts

Define adapter-owned typed resource/action descriptions, policy filters, references, revision/freshness and bounded paging. Establish the reviewed executable action/selector subset and per-credential/binding snapshot identity. Existing explicit aliases remain compatible.

**Owned outputs:** `integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/<typed-discovery-reader-contracts>`; `integrations/home-assistant/src/test/java/<discovery-contract-tests>`.

**Acceptance evidence:** Fixtures cover denied metadata/counts, duplicate names, renamed/deleted entities, missing registries, unavailable states, primitive versus unsupported nested attributes, credential/policy isolation and input bounds. Confirm no new shared IntegrationPayload variant or generic object domain contract is required.

### HA-02 — Discovery and batched reads

Implement policy-filtered search/descriptions and batched reads using the configured HA connection and supported registries/services. Reuse authorized typed snapshots with explicit freshness/version reasons for refresh; bound cache size, IO and connection maintenance.

**Owned outputs:** `integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/<discovery-and-read-implementation>`; `integrations/home-assistant/src/test/java/<discovery-read-tests>`.

**Acceptance evidence:** Fake HA transport cases verify paging consistency, read batching/no N+1, stale-on-disconnect, registry/policy invalidation, reconnect epochs, slow peers and shared finite deadlines. Discovery does not grant actions and leaks no denied identifiers or counts. No live device mutation or database test.

### HA-03 — Named read tool provider

Publish and serve search/describe/read/list-action tools through the existing SDK ToolProvider and explicit Application/provider scopes. Use configured prefixes for multiple HA bindings and current catalogue lease/revision rules.

**Owned outputs:** `integrations/home-assistant/<SDK-provider-composition-and-journal>`; `integrations/home-assistant/<provider-config-and-tests>`.

**Acceptance evidence:** Test catalogue expiry/withdrawal, schema changes, granted/denied agent visibility, source/credential separation, bounded catalogue size, reconnect and receipt reconciliation. Public SDK only; no server dependency in production classpath. Existing manual alias runtime remains working.

### HA-04 — Action provider and receipts

Implement reviewed action schemas and ha_execute_action over explicit targets/parameters with deterministic authorization checks. Keep delivery, actual effect and observed verification separate; preserve existing causal context and unknown-effect journal semantics.

**Owned outputs:** `integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/<typed-action-profiles-and-execution>`; `integrations/home-assistant/<action-provider-and-tests>`.

**Acceptance evidence:** Exercise malformed parameters, unauthorized targets, stale offered schemas, revoked policy, queue/timeout/cancellation before and after possible effects, restart and lost acknowledgement. A fake reversible action executes once, an UNKNOWN action is never automatically resent, and selected observation verifies separately. Unsupported selectors remain non-executable.

### HA-05 — Shared typed request gateway

Refactor the current bridge into separate conversation/task request and result contracts sharing SDK transport and recovery. Carry admitted HA system/context text and provenance in a typed adapter envelope serialized into the existing SDK task field, with actual task/user semantics documented. Use Ex APIs for durable external conversation mapping; retain accepted-job/uncertain-send correlation locally.

**Owned outputs:** `integrations/home-assistant/assist/custom_components/plowshare/agent.py`; `integrations/home-assistant/assist/custom_components/plowshare/models.py`; `integrations/home-assistant/assist/tests/<gateway-tests>`.

**Acceptance evidence:** Prove exact source-text preservation and actual task/user semantics of the existing SDK route, no discarded extra prompt, profile/caller/task isolation, bounded admission/storage/cleanup, restart reconciliation and no replay. Demonstrate a facade call removes the client resolve round trip. Native role fidelity must not be claimed; its absence and cache behavior do not block the supported adapter contract.

### HA-06 — Conversation Entity lifecycle

Complete Conversation Entity adaptation for Assist/frontend/API calls, preserving language, effective system/extra prompts and supported context. Bind returned HA session IDs through core aliases and configure profile-specific control reporting and anonymous-source policy.

**Owned outputs:** `integrations/home-assistant/assist/custom_components/plowshare/conversation.py`; `integrations/home-assistant/assist/custom_components/plowshare/config.py`; `integrations/home-assistant/assist/custom_components/plowshare/config_flow.py`; `integrations/home-assistant/assist/tests/<conversation-tests>`.

**Acceptance evidence:** Actual isolated HA Conversation API tests cover first turn/follow-up, session expiry/new returned ID, caller and source partitioning, control-disabled routing, chat-log updates, refusal, unsupported inputs and no duplicate tool execution. Expiring HA session state never cancels accepted durable jobs or erases pending/uncertain effect records.

### HA-07 — AI Task text provider

Add an independent AI Task entity/profile for supplied-data generation and optional explicit read-only evidence. Preserve HA-prepared task system/context and use an isolated request/conversation per invocation. Expose standard HA action/helper selection without requiring Assist.

**Owned outputs:** `integrations/home-assistant/assist/custom_components/plowshare/<ai_task-platform>`; `integrations/home-assistant/assist/custom_components/plowshare/__init__.py`; `integrations/home-assistant/assist/custom_components/plowshare/manifest.json`; `integrations/home-assistant/assist/tests/<ai-task-tests>`.

**Acceptance evidence:** Real isolated HA generate_data action and async_generate_data helper return completed text; distinct callers and concurrent identical instructions remain isolated. Timeout, failed/capped run, unsupported llm_api or attachment, invalid context, cancellation and lost acknowledgement yield explicit errors, never pending prose as data. No implicit device-changing permission.

### HA-08 — Structured AI Task subset

Implement the approved limited HA selector/type profile and strict generated-data validation. Preserve required/optional distinctions and closed field sets. Retain invalid completed work; use a public shared output-recovery path only if supported and admitted, otherwise return a truthful error.

**Owned outputs:** `integrations/home-assistant/assist/custom_components/plowshare/<typed-output-spec-and-validator>`; `integrations/home-assistant/assist/tests/<structured-output-tests>`.

**Acceptance evidence:** Exercise unsupported schemas before submission, invalid JSON, wrong types, extra/missing fields, parser/byte limits, modest prose overruns, retained failed results and original allowance. No re-running research or actions to fix presentation; no direct inference/internal recovery call. Return only validated completed data.

### HA-09 — Observation routing and feedback

Generalize configured selected observations/workflow routes while preserving threshold holds, startup/reconnect suppression, cooldowns, bounded retention and causal feedback. Prevent own Plowshare-backed conversation/AI Task endpoints from recursively invoking themselves.

**Owned outputs:** `integrations/home-assistant/<observation-and-route-contracts>`; `integrations/runtime/<only-required-compatible-local-routing-seams>`; `integrations/home-assistant/src/test/java/<event-and-feedback-tests>`.

**Acceptance evidence:** Fake events prove continuity gaps cancel holds, startup is not historical crossing, retained events are not lost at bounds, causal acknowledgements do not cause loops and UNKNOWN actions are not replayed. Existing captured-input orchestration restriction remains explicit; do not promise unsupported fresh remote waits.

### HA-10 — Reusable Application profiles

Provide optional reusable house-agent, supplied-data generation and read-only task profiles with explicit dynamic-tool assignments, scopes, credential references, workflow notification routing and installation instructions. Keep connection policy out of agent prose and real deployment values out of source.

**Owned outputs:** `integrations/home-assistant/examples/application/`; `integrations/home-assistant/examples/config.json`; `integrations/home-assistant/src/test/java/<example-loader-tests>`.

**Acceptance evidence:** Current loaders validate complete examples. Agents can share provider capabilities without shared private histories; plain generation works without outbound device provider; control and read-only profiles remain separate; no grants arise from discovery or prompt metadata. Templates are not asserted deployed.

### HA-11 — Installable package and operator docs

Package the reviewed external adapter and HA component with correct public SDK dependencies/resources, separate feature flags and configuration entry/profile lifecycle. Document transition from local alias maps to core ownership without replay or competing map owners.

**Owned outputs:** `integrations/home-assistant/assist/scripts/package.py`; `integrations/home-assistant/assist/README.md`; `integrations/home-assistant/README.md`; `integrations/home-assistant/assist/custom_components/plowshare/translations/`; `integrations/home-assistant/assist/tests/<install-and-migration-tests>`.

**Acceptance evidence:** Fresh artifact install, SDK byte/resource/dependency integrity, baseline HA config flow, token rotation, reconfigure/unload/restart, old-state migration and pending-job preservation pass. Unsupported evaluation, image and attachment flags stay off. No restart or deployment of live HA is performed by this node.

### VERIFY-01 — Integrated independent contract validation

Assemble the exact candidate revision and independently validate core facades, SDKs, provider, both HA entry points, templates and distributions together. Review the earlier prototype against current requirements; prior passes are evidence to recheck, not automatic completion.

**Owned outputs:** `docs/reviews/home-assistant-integrated-validation.md`; `integrations/home-assistant/<only-new-cross-boundary-fixtures>`; `test-support/<only-required-contract-fixtures>`.

**Acceptance evidence:** Run appropriate check/formatCheck/integrationBoundaryCheck, sdkCheck, package checks and HA explicit gate on the exact source. Actual SDK transport fixtures prove one-request alias facade, state/action flow, task completion, isolation, prompt preservation and no replay. Select only justified full-db constraint/race tests. Reviewer must not have authored any feature implementation being validated.

### PERF-01 — Optional HA prefix-cache impact assessment

This optional report is not a release prerequisite. Investigate the original HA prompts as supplied: how often they change, earliest divergence, following history size, chosen public handoff roles, and backend reuse/latency implications. Use the earlier six-case source probe only as initial evidence. Do not rewrite/split/reorder/freeze/relocate HA prompt content to improve caching.

**Owned outputs:** `docs/reviews/home-assistant-prefix-cache-impact.md`; `integrations/home-assistant/assist/<bounded-investigation-fixtures-if-needed>`.

**Acceptance evidence:** Repeat isolated source/helper probes with recorded version/hash and show actual model-request layout for the approved route. Finite, non-side-effecting measurement on an explicitly authorized backend records model/template/configuration, actual cache observations or unknown, cold/warm TTFT/prefill/end-to-end distributions and load. Character common prefixes and count previews are not cache-hit metrics. If backend access is unavailable, report the remaining external prerequisite; no fabricated live report.

**Additional gate for actual backend measurements:** `cache_backend_measurement_authorized`. Source-only observations can be prepared independently; neither measurements nor cache efficiency blocks supported adapter delivery.

### RELEASE-01 — Release readiness and rollout runbook

Produce the exact source/artifact manifest, all reviewer approvals, compatibility/known-limit report, target preflight, backup/rollback procedure and proposed reversible live acceptance actions. Separate implementation approval from merge, deployment and live-validation states.

**Owned outputs:** `docs/reviews/home-assistant-release-readiness.md`; `integrations/home-assistant/<rollout-and-rollback-runbook>`.

**Acceptance evidence:** Confirm accepted dependencies and exact revision/artifact hashes; no unresolved blocking findings against the declared existing-SDK adapter contract. Unsupported native-role semantics are a documented limitation, not a release prerequisite. Review backup validation versus restore rehearsal honestly, target versions, active-work handling and rollback without deleting uncertain receipts. This node prepares a reviewable rollout and does not merge, publish, deploy, restart HA or act on devices.

### DEPLOY-01 — Separately authorized installation

After explicit target-specific authorization, install the approved server/SDK/provider/HA artifacts and configured Application through supported operator paths. Preserve durable data, configuration, resource settings and pending/uncertain work. Restart HA only within the authorized rollout scope.

**Owned outputs:** `docs/reviews/home-assistant-deployment-receipt.md`; `<private operator config and deployment receipt outside source>`.

**Acceptance evidence:** Record actual deployed source/artifact/schema/HA identities and compatibility, private backup evidence and bounded readiness results. No raw secrets/addresses/config dumps in source. Do not cancel active work for convenience, replay uncertain effects or mark live validation from health alone. Capture rollback state and unresolved failure precisely.

**Additional gates:** `deployment_authorized`.

### LIVE-01 — Separately authorized live acceptance

Exercise the public deployed package with the agreed permitted targets: scoped discovery/read, one reversible action with observation, Conversation API follow-up/session handling, standard AI Task action/helper and supported structured result. Validate configured voice providers if voice deployment is in scope.

**Owned outputs:** `docs/reviews/home-assistant-live-acceptance.md`.

**Acceptance evidence:** Record exact artifact identity and actual public results; confirm caller isolation, faithful prompt/context, feedback protection and status after timeout/restart without replacement work. Device changes/model calls/restarts require the explicit live-acceptance scope. Distinguish unavailable test equipment or missing STT/TTS from a passing check.

**Additional gates:** `live_acceptance_authorized`.

## Outbound-only deliverables

| Node | Dependencies | Implementer | Independent reviewer |
| --- | --- | --- | --- |
| OUT-PLAN-01 | PLAN-00, AUDIT-01 | `/root` | `/root/ha_outbound_plan_reviewer` |
| OUT-PACK-01 | OUT-PLAN-01, CORE-04, HA-03, HA-04, HA-09 | `/root/ha_outbound_pack_implementer` | `/root/ha_outbound_pack_reviewer` |
| OUT-PROFILE-01 | OUT-PLAN-01, CORE-04, HA-03, HA-04, HA-09 | `/root/ha_outbound_profiles_implementer` | `/root/ha_outbound_profiles_reviewer` |
| PLAT-SQL-01 | None (separate platform test scope) | `/root/sql_cleanup_implementer` | `/root/sql_cleanup_reviewer` |
| OUT-VERIFY-01 | OUT-PACK-01, OUT-PROFILE-01, PLAT-SQL-01 | `/root/ha_outbound_validation_implementer` | `/root/ha_outbound_validation_reviewer` |

These are separate deliverables, not partial acceptance of HA-10/11 or VERIFY-01.
All use the same frozen-candidate/independent-approval protocol. Validation's
implementer/reviewer must not have authored either feature implementation. Root
records actual IDs at dispatch; role reservation is not a running-agent claim.

### OUT-PLAN-01 — Current-main reconciliation and operator contract

**Owned outputs:** this plan, the coordinator-owned ledger and the
[outbound host contract](reviews/home-assistant-outbound-host-contract.md).
Reconcile PR #300 merge milestones and current SQL/accepted-work semantics without
rewriting prior evidence. Add these nodes/edges with no inherited prompt gate.
Freeze strict CLI/configuration, file ownership, dependencies and evidence limits.
Independent review must approve exact plan/contract/ledger snapshot before code.

**Historical acceptance evidence at the outbound revision:** JSON/schema
consistency, acyclic graph, dependency and gate references, independent role
identities, the then-original eleven gated nodes unchanged and four gates
unsatisfied, historical evidence unchanged,
and explicit authorization/merge/deployment distinctions. No runtime/DB check is
required for this documentation node.

### OUT-PACK-01 — Standalone outbound provider host and package

Expose named read and optional explicit control providers using the existing
public Java SDK and HA adapter. Preserve the legacy executable via delegation;
new `--provider CONFIG` and `--provider --check CONFIG` select isolated provider
mode. Strict typed configuration supplies explicit origins, binding/policy,
credential environment-variable names, project/provider/principal/prefix, private
journal paths, selected fixed action profiles and finite owning timing budgets.
Read/control providers share one authenticated SDK connection and the explicit
Application execution principal, as required by the current provider registry.
Logical provider names, prefixes, journals and exact grant sets remain separate;
read grants never authorize control.

Offline `--check` validates complete inputs without credential lookup, network,
journal creation or catalogue mutation. Provider mode rejects event subscriptions,
routes/scripts/feedback fields and unsupported recursive model endpoints. The
supervisor owns finite adapter maintenance, catalogue publication/renewal,
read-only uncertainty reconciliation and bounded polls/cleanup. Unknown mutation
outcomes never trigger replay/replacement, silent account fallback or journal
reset. Fail closed and retain ownership/receipts if physical cleanup cannot settle.
Use narrow lifecycle interfaces for testable effects, not raw JSON domain APIs.

**Owned outputs:** new HA-specific main/configuration/host interfaces and
composition under `integrations/home-assistant/src/main/java/`; their new unit
fixtures; HA `build.gradle.kts`; `PROVIDER-HOST.md`; narrow README links and correct
explicit `-PfullDb` instructions for existing PostgreSQL harness tests. No edits
to core, public SDK/shared contracts, legacy runtime behavior, Assist code or
profile-owned example files.

**Acceptance evidence:** invalid/unknown/null config and identity/path/prefix
collisions refuse before effects; offline checks need no secrets; read-only has
no actuator; control opt-in is exact; startup/renew/expiry/uncertainty/restart,
bounded physical shutdown and legacy invocation preservation use fakes/local
transports. Build the distribution and inspect its production dependency boundary.
No live server/HA/model/device operation and no real database test by default.

### OUT-PROFILE-01 — Read-only and explicit-control Application templates

Supply separate outbound-only Application roots and matching provider configs in
`examples/outbound/readonly` and `examples/outbound/control`. They use local
`dynamic: true` agents, exact named external tool grants, static provider
permissions with `bindings: []`, explicit execution/provider principal
placeholders, empty access grants and separate private journals. No wildcard,
implicit control, Assist/AI Task, event routes, shared private conversation
history, built-in tools or automatic authority provisioning. Connection/policy
belongs in typed configuration, not agent prose.

**Owned outputs:** all `examples/outbound/**`, example-specific operator guidance
and `OutboundProfilesTest.java`. Packaging host/configuration code is owned by
OUT-PACK-01; the approved common contract permits parallel work.

**Acceptance evidence:** current real manifest/provider/agent loaders accept both
complete roots; exact assignments deny wrong agent/principal/project/control;
missing grants confer no authority; invalid examples fail clearly. Match host
configurations to the typed parser, exact provider names/prefixes/journals and
installed files once OUT-PACK-01 is ready. No model fleet or deployment claim from
parsing a template; placeholders must be replaced in private operator source.

### PLAT-SQL-01 — Deterministic SQL cleanup regression fixture

Continuation authorizes a separate owning-platform test investigation of the
repository-check blocker. Its general purpose is reliable verification of SQL
network deadlines and physical cleanup ownership; it adds no adapter capability.
Diagnose hidden acquisition/worker failures with finite phase/Future evidence
before correction. Initially own only `ScopedSqlWorkBudgetTest.java` and new
`docs/reviews/home-assistant-plat-sql-01-*` evidence. No production runtime, public
API, SDK or schema change is authorized by this test scope.

**Acceptance evidence:** independently review actual diagnostic failure evidence;
retain the short tested SQL deadline, execute and fetch coverage, observed
occupied cleanup worker, rejection of late results and exact lease ownership.
Setup may have its own finite fixture budget; do not loosen the tested boundary,
exclude tests or retry until green. A fresh method-only JVM, owning class and
repository `check` must pass on frozen bytes, with formatting checks. Mock JDBC
is sufficient; no PostgreSQL run is justified. Any discovered runtime defect
requires an explicitly scoped platform decision before production edits.

### OUT-VERIFY-01 — Integrated outbound-only validation

Assemble exact independently accepted packaging/profile candidates. Verify a fresh
installed distribution runs offline config validation without credential/network
or journal side effects, ships only public SDK/runtime production dependencies,
and includes the approved examples. Exercise host lifecycle and public SDK
request/recovery contracts over controlled local WebSocket/HA fixtures, retaining
server-side authorization ownership and distinct delivery/effect/observation states.
Recheck the Ex SDK contract and existing server authorization tests with mocked
repositories. Template/source checks cannot establish actual live grant issuance
or physical device behavior; record those as later operational prerequisites.

**Owned outputs:** new cross-boundary fixtures only under the HA module, and
`docs/reviews/home-assistant-outbound-validation.md` plus text candidate/source/
package hash manifests. Feature source corrections return to their original
implementers and reviewers; validation agents may not self-approve them.

**Acceptance evidence:** current `check`, `formatCheck`, `integrationBoundaryCheck`,
canonical SDK-contract drift checks and scoped fresh installed-consumer fixtures
pass against one frozen source. Real SDK transport tests prove public request
shapes/correlation and no replay after uncertainty; no manual internal-service or
SQL access from production. Use mocked repositories by default; a selected
`full-db` test requires an independently stated PostgreSQL-specific assertion,
not general integration confidence. Guard Assist setup/configuration/packaging
remains disabled; do not install HA dependencies or enable the component merely
to claim complete-package validation. Record missing explicit Python/HA checks
honestly. This node does not close original VERIFY/PERF/RELEASE/DEPLOY/LIVE nodes.

### SCOPE-01 — Correct the caching gate and SDK-only adapter contract

**Dependencies:** OUT-VERIFY-01. **Implementer:** `/root`. **Independent reviewer:**
`/root/ha_cache_gate_correction_reviewer`. Correct the active plan/design/ledger
and scope direction; preserve the exact prior plan/state and historical reviews.
Acceptance requires a valid DAG, unchanged accepted implementation sources,
no active retired-gate consumers, optional cache assessment outside the release
critical path, preserved operational authorizations and an independent APPROVE.
No runtime source or publication guard changes are part of this deliverable.

## Coordinator scheduling and tracking

There are four concurrent agent slots: root plus at most three workers. The safe
normal schedule is one implementer/reviewer pair and one optional implementer on
a disjoint ready branch. Reviewer slots are reserved before opening more work;
root never leaves reviews starved behind unreviewed implementation batches.
Roles are reused sequentially only where independence and file ownership hold.

Outbound amendment schedule: OUT-PLAN-01 review first, then OUT-PACK-01 and
OUT-PROFILE-01 on disjoint paths, each with independent review, followed by
OUT-VERIFY-01. Its initial repository check failed; PLAT-SQL-01 now resolves the
separate owning-platform test blocker before a new validation candidate. Those
outbound reviews remain historical exact-source evidence. SCOPE-01 now corrects the unrelated caching/native-fidelity prerequisite for remaining work.

Initial waves after PLAN-00 approval:

1. AUDIT-01 establishes the exact baseline. No feature node inherits an assumed
   completion from the existing dirty prototype.
2. CORE-01, FIDELITY-01 and HA-01 can be queued as independent branches, but root
   schedules only what fits the worker/reviewer slots. Ready nodes wait for review
   capacity rather than claiming progress by opening unlimited agents.
3. CORE-01 approves the platform contract. CORE-02 supplies storage; CORE-03A
   supplies the scoped physical SQL completion boundary discovered during
   implementation and must be accepted before CORE-03 closure. CORE-03
   owns the canonical wire definitions/registration and generated types needed
   to compile its server facades. CORE-04 follows with complete SDK/client usage
   and cross-language evidence; it is not a prerequisite hidden inside CORE-03.
   Upstream canonical fixes reopen the affected approval. HA discovery/read/
   provider/action work proceeds independently. Gateway work waits
   for accepted core SDK delivery and the SCOPE-01 adapter contract review.
4. Once HA-05 is accepted, Conversation and AI Task can be assigned to separate
   implementers with disjoint files. Shared configuration/setup files are owned
   by one node at a time and handed off explicitly. Each node gets its own reviewer.
5. Root assembles one integration candidate for VERIFY-01, then release preparation
   can proceed. Cache-impact investigation is optional and separately scoped.
   Operational nodes wait for their independent authorization gates.

Root serializes Gradle runs and shared generated-catalog/migration allocation
work. Pause overlapping Java edits during a compiled candidate's checks. Assign
the next migration version against current source at dispatch; never edit a shipped
migration or reserve a version from stale history. Separate SDK generated outputs
from handwritten owning contracts and review the generator diff.

At each dispatch root records task ID, goal, exact baseline, files, dependencies,
gates, actual agent IDs, required checks and stop conditions. During work root
tracks findings and blockers, routes reviewer feedback to the implementer, and
keeps the user informed of meaningful changes. On completion root records the
exact evidence and reviewer verdict, then schedules newly ready successors.

The machine ledger contains dependency IDs, roles, actual agent IDs, candidate
revision/evidence, finding records, reviewer verdict/report and distinct delivery
milestones. Empty/null fields mean no evidence has been supplied, never implicit
approval. PLAN-00 starts ready for review; every feature node starts planned.

## Verification policy

Follow [delivery/review](../.agents/delivery-and-review.md),
[coding standards](coding-standards.md),
[native SDK standards](native-sdk-standards.md) and the owning subsystem guides.
Tests use mocks/fakes by default. Real PostgreSQL tests are justified only for
CORE-02 assertions requiring actual migration/constraint/locking/rollback behavior,
CORE-03A assertions requiring actual scoped cancellation/transaction/pool behavior,
or a later separately stated database-specific reason; select the smallest set
with `-PfullDb --tests` and keep `@Tag("full-db")`. Never run the entire database
suite or dockerSmoke for general confidence.

Java edits require format and formatCheck; repository-wide delivery requires
check. Core contract/SDK edits require the owning generator plus check and
sdkCheck; package-surface changes require fresh installed-consumer sdkPackageCheck.
The HA package has its explicit Python runtime gate at
`integrations/home-assistant/assist/scripts/check.py`. Confirm target HA versions
and native toolchains at dispatch; a missing .NET/Go/HA toolchain is an unresolved
check, not permission to weaken coverage. Scope-specific commands and exact
fully qualified tests are recorded by each implementer and verified by its reviewer.

Do not broaden/repeat passing checks without a changed candidate, failure or
unresolved concern. Documentation-only PLAN-00 validates links, JSON, graph,
role independence and status invariants; it does not run Gradle or database tests.

## Completion definition

The supported SDK-only package is implementation-complete when all required
adapter features and integrated validation have independent approval on the
integrated candidate. RELEASE-01 prepares that candidate for a separately scoped
rollout. Cache measurements are optional; native system-role parity is not a
requirement. Document actual task/user semantics and unsupported options. No
incomplete adapter node may be marked accepted merely because its former gate
was retired. The accepted outbound branch is only its own completed scope.

Deployment-complete and live-validated are later explicit milestones with their
own agents, approvals, target/artifact receipts and user authorization. Root keeps
coordinating until the authorized scope is complete, preserving the ledger across
handoffs rather than resetting incomplete tasks.
