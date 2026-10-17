# Authority substrate and Global impact report

Status: source-grounded impact assessment, updated 18 October 2026. Separately
authorized slices 01–12 are implemented in this branch: project READ/WRITE,
explicit identities and scoped RBAC, knowledge home/provenance and current-root
audiences, retained operation/inference authority and budgets, bounded SYSTEM
services, scheduling, Relay/message integration, retained independent
orchestration roots and synchronous agent children, and action checks for the
existing account-acting consumers. SYSTEM processing no longer depends on its
creator retaining access; ordinary execution uses its actual bound
account. No new public APIs or deployment are included.

This is not a complete cutover. Configured embedding/search operation allowances
remain missing; production inference without supported evidence refuses.
Independent Relay orchestration roots now use retained lineage. Nested/model-triggered
orchestration starts still refuse: their existing fresh allowances do not retain a parent budget.
Non-conversation child allowances cannot resume under replacement leases. Original
receipts remain inspectable without replay. Slices 13–14 remain proposed and
unauthorized, covering client-contract and integrated legacy migration. Acting
now intersects scoped actions with its retained proof; additional Global sources
and general project acting execution remain unsupported.

Slice 05 adds V155 root audience versions/history, deliberate legacy Shared and
ownerOnly reconciliation, live filtering for every revision, and explicit general
reads for active authenticated human, Application and service principals. Existing
Manager share/unshare commands govern the root; private inputs, retained reference
obligations and processing logs remain protected. Curation retains proposals but
human acceptance requires source Manager and Global Admin admission. This is the
existing copy-and-retire action; no generic copy/transfer capability was added.
See the [slice 05 implementation and verification](authority-substrate-slices/05-root-policy-and-global-read.md).
The baseline findings below remain historical evidence where a slice explicitly
supersedes them; they are not claims that unmigrated runtime consumers are fixed.

Read with [ADR 0030](decisions/0030-authority-substrate-and-explicit-scopes.md),
the [migration map](global-scope-migration-map.md) and
[permission matrices](role-scope-permissions-proposal.md).

Navigate by status: [settled decisions](#settled-decisions),
[migration requirements and source evidence](#migration-requirements),
[reusable mechanisms](#what-can-be-reused) and [deferred gaps](#deferred-gaps).

The [first implementation slice plan](authority-substrate-first-slice-plan.md)
records the existing project-information READ/WRITE gate as the initial
production consumer. It is implemented as a compatibility foundation, with no
Global cutover. [Slice 02](authority-substrate-slices/02-identities-and-scopes.md)
now adds explicit identity mappings and locked built-ins; it leaves legacy null
resources and their current policy untouched. [Slice 03](authority-substrate-slices/03-rbac-grants-and-role-helpers.md)
adds live scoped bindings, immutable role helpers and audited issuance/revocation.
[Slice 04](authority-substrate-slices/04-knowledge-scopes-and-write-authority.md)
adds explicit knowledge home/creator evidence and scoped ordinary document writes,
while retaining mandatory ownerOnly/input/reference and private-record rules.
Its [predicate inventory](authority-substrate-slices/04-knowledge-write-specification.md)
distinguishes converted checks from retained restrictions. The existing archive
Global tier now has an explicit scope mapping; this does not activate Global
access or SYSTEM processing. Slice 05 now implements the root audience; slice 06
implements ordinary retained execution. Slices 07–08 add dispatch/budget evidence
and bounded SYSTEM processing. Slices 09–10 bind scheduled/message executors and
recheck Relay effects without replacing route/provider capabilities. Slice 11 adds
immutable orchestration/child authority and budget lineage for supported consumers.
[Slice 12](authority-substrate-slices/12-account-acting-delegation.md) maps the
existing acting whitelist to scoped actions while retaining actor/subject proof,
Personal confinement and original receipt recovery. Slices 13–14 remain proposed.

The detailed source findings below retain their audit baseline. Slice 04 replaces
the listed ordinary catalogue/content/import/relationship owner-mutation checks.
Slices 05 and 08 supersede the baseline's per-revision audience and creator-bound
processing findings respectively. Navigation-policy administration and supported-client
cutover remain deferred as documented.
The [14-slice roadmap](authority-substrate-implementation-plan.md) gives every
follow-on slice its own changes, reuse, verification and rollback document.

## Result and audit boundaries

The proposed separation is viable, but it is a coordinated authority and ownership
migration, not a Global rename. The revised substrate checks account, scope and
action; roles, including Application roles, are helpers over those grants. SYSTEM
can process Global knowledge through scoped READ/WRITE/EXECUTION authority, while
GLOBAL_READ and human ADMIN write/control bindings do not imply execution.

This assessment supersedes its earlier universal Global-execution prohibition
and mandatory separate-processing-scope workaround. The source findings remain:
project/null homes conflate several meanings, exact-owner APIs block scoped admin
writes, and common grants are not carried across all model/admission paths.

The earlier segmented recheck used proposal commit `af89b7f`; subsequent policy
clarifications are consolidated in the settled decision record below. The broader
identity/Global cutover remains unimplemented. The compatibility
findings describe what fails if the new identities or
grants are enabled against unchanged contracts. Missing action checks and optional
new defaults are capability gaps, not evidence that existing explicit-project
workflows are broken. No new runtime, wholesale role replacement, mandatory token
reissuance or processing-scope relocation follows from this design.

Runtime baseline: main `269854a7c5bcc5f7080bc04de01ea8ccce46eaa7`, merged into this
proposal branch. PR #74 enables existing information features by default; PR #75
fixes test completion timing. Neither implements the new authority model.
Slice 01 now adds a typed project READ/WRITE adapter at the existing information
gate, retaining effective RBAC, resource checks and current routing. Its focused
verification is recorded in the first-slice document; this is not a fresh audit
of all runtime/client consumers or proof of the remaining Global migration.

Three read-only sub-agents scanned separate server segments; the parent reviewed
the findings and sampled the protocol/client boundary:

| Segment | Source areas inspected |
| --- | --- |
| Identity and admission | Accounts/bootstrap, Personal, project roles, central authorization, conversations, Applications and acting admission |
| Knowledge and persistence | Memories, recall/digests/curation, Information/Shared/reference/schema/import, DataLayout and unions |
| Runtime consumers | Jobs, model dispatch, internal workers, definitions, orchestrations, schedules, messaging, Relay/providers, approvals and accounting |
| Parent integration | Proposed contracts, public request/discovery assumptions, manual installer and Library presentation |

This is an implementation/test-source scan, not proof about live rows, installed
manual ownership, deployment configuration or restored-backup compatibility.
Client/native SDK coverage is an impact inventory, not a complete client audit.
The adopted account/role authorization rule does not assert SDK/client feature
parity or require new APIs/SDK methods. Management coverage remains unverified
unless individually inspected; unsupported surfaces remain capability gaps.

## Settled decisions

Core policy is settled for the proposal; implementation remains separately
scoped. RBAC is the normal management path, using sensible standard role helpers.
Complex ACLs provide finer resource grants when needed, through the same substrate;
routine setup should not require custom per-action ACLs or a second policy engine.
See the [role profiles](decisions/0030-authority-substrate-and-explicit-scopes.md#standard-role-helpers-and-acl-exceptions)
and [action matrices](role-scope-permissions-proposal.md).

| Policy | Settled position |
| --- | --- |
| Account/scope/actions | Common substrate; ADMIN/MANAGER/CONTRIBUTOR/READER and Application-facing roles are standard helpers over it, with explicit issuing authority and no ambient cross-scope privilege. |
| Normal role defaults | Human scoped administration uses ADMIN; resource/project DRI uses MANAGER; ordinary task work uses CONTRIBUTOR; read-only use uses READER. These are authorized setup profiles, not automatic grants or new provisioning features. |
| SYSTEM | Locked root account, intrinsic non-assignable role. Existing root authority is narrowed by operation inputs/tools/output bounds, not re-granted per task. |
| Agent limits | Default maximum CONTRIBUTOR; lower requested roles stay lower. MANAGER needs an explicit authorized human exception; ADMIN/SYSTEM cannot be assigned. |
| Provenance and documents | created_by is historical provenance with no rights. Current root-document permissions govern revisions, including historical/pinned reads; publication state is separate from audience. |
| Global knowledge | GLOBAL_ACCOUNT is an admin-managed account/home, not a project, login or executor. Global management requires human scoped ADMIN. |
| General-read audience | Every authenticated active principal type holds GLOBAL_READ. Resources authorizing it have no individual exclusions; restricted readership uses project/group permissions. |
| Manager responsibility | Resource MANAGER/DRI can grant/remove GLOBAL_READ without extra expansion authority or ADMIN approval, preserving home/created_by and private dependency restrictions. |
| Actions and controls | EXECUTION bundles CREATE/RUN_MODEL, checked independently. All inference needs RUN_MODEL for its executor; other work checks actual actions. Existing controls use WRITE plus domain rules; no EXECUTE_JOB or CONTROL_EXECUTION is added. |
| Internal services | Declared SYSTEM services use existing SYSTEM authority within their operation sandbox. Callers need the requested operation's permission, not internal RUN_MODEL. Independent processing continues while document/operation remain valid despite creator access loss. |
| Operation budgets | The operation pays through its configured budget. Retain budget binding and usage across queues/recovery, without implicit SYSTEM/creator payer or silent pool switching. |
| Context and acting | Human omission may resolve Personal; admitted Application/project resolves itself; ambiguous/invalid targets refuse. Acting requests retain separate actor/subject proof and live ceilings. |
| Interfaces and capabilities | Authority follows account/role/policy, not client label. Use existing supported APIs; SDK parity and missing control APIs are not required. Tools, filesystem/providers and Relay retain their capability boundaries. |
| Disclosure and recovery | Preserve mandatory input/reference policy, current recipient access, durable receipts and uncertain-effect reconciliation. Account authority alone does not widen a retained operation. |

The [ADR decision record](decisions/0030-authority-substrate-and-explicit-scopes.md#settled-decisions)
is the policy reference. None of these target rules is a claim that the audited
runtime, every SDK, a generic ACL editor or a new control surface implements it.

## Migration requirements

These are compatibility requirements for separately authorized implementation
slices, not another list of open architectural policy choices. The source
findings below identify what must be adapted and what must remain bounded.

| Requirement | Concrete work before enabling the affected target behavior |
| --- | --- |
| Identity and typed scopes | Reserve remaining UUIDs, map existing keys to explicit account/project/Personal scopes and define locked-account bootstrap, collision and recovery behavior. Avoid login/acting-subject or accidental Personal provisioning for built-ins. |
| RBAC/action mapping | Map current VIEWER/CONTRIBUTOR/MANAGER, server-admin state, Application manifests and token scopes to standard scoped helpers. Review implicit admin access and agent exception issuance; retain narrower grants without wholesale credential reissue. |
| Knowledge authorization/provenance | Replace ordinary exact-owner checks with scoped actions and current root policy; separate created_by from home/control and reconcile ownerOnly without fabricating provenance or dropping mandatory privacy. |
| Audience and revisions | Reconcile per-revision Shared audiences, preserving IDs and input/reference obligations. Review service-reader audience expansion and the manual's separate Global home/control migration. |
| SYSTEM operation validity | Retain executor authority and sandbox independently of creator access. Keep generation/availability/input/budget fences, supported control semantics and current recipient disclosure checks. |
| Actual effects and budgets | Carry action checks through supported APIs, model dispatch/embeddings/side calls, queues and recovery. Bind each operation to its configured budget, preserving accounting attempts and historical costs. |
| Existing boundaries | Preserve acting proofs, provider/file/tool grants, schedule/Relay lifecycles, private session/credential subjects and uncertain-effect receipts. Map existing controls only; missing interfaces remain gaps. |
| Cutover and rollback | Classify null-Global database/disk/job/log rows, preserve protected unknowns and stable IDs, specify mixed-version refusal and rollback, and coordinate only affected existing contracts through new migrations/generated outputs. |

### Compatibility classification

| Area | Classification | Minimum consequence under the corrected model |
| --- | --- | --- |
| Global account knowledge through project/null fields | Representation incompatibility | Add typed account/resource scope and stable mappings; preserve ordinary project contracts |
| Global ADMIN writing another account's knowledge | Authorization incompatibility | Migrate exact-owner mutation checks to scoped action authority while retaining owner/actor/audit |
| SDK/Application general reading | Authorization incompatibility | Coordinate Java, SQL and supported retained read sources; a role assignment alone is insufficient |
| Existing roles, manifests and project tokens | Reusable inputs plus compatibility decision | Map current roles to common actions deliberately; do not discard them or put GLOBAL_READ into the ordered enum |
| CREATE/RUN_MODEL separation | New capability gap | Introduce action checks using existing admission/dispatch seams; current CONTRIBUTOR combines work actions |
| Omitted Application conversation/run target | Optional new admission behavior | Human Personal and explicit Application/project requests already work; select a trusted Application binding for omission |
| Global summarization/preparation | Reuse with authority/sandbox integration | Keep processing in Global using existing SYSTEM authority restricted by an operation sandbox; no new execution scope is inherently needed |
| Public Global conversation resume | Separate API/exposure decision | Existing user-facing refusal can remain; internal summarization does not use that entry point |
| Existing Shared audience | Deliberate policy/data migration | Preserve ownership; admitting formerly excluded service readers is an explicit audience expansion |
| Implicit server-admin MANAGER | Deliberate compatibility mapping | Review explicit replacement role/data grants against existing effective access; removing this privilege is not a prerequisite to unrelated Global reads |
| Digest job recorded with default Global home | Existing persistence mismatch | Store its real scope/admission; do not reconstruct authority from a vanished worker closure |
| Disk, accounting and legacy rows | Classification/identity extension | Preserve existing lifecycles, IDs, private records and costs; do not move/relabel everything called global |

### Source evidence by area

#### 1. Project-only scopes cannot express the new ownership model

[Home.java:9](../plowshare-protocol/src/main/java/io/aeyer/plowshare/protocol/Home.java)
encodes Global as a null project.
[AccessRequest.java:10](../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/AccessRequest.java)
and [JdbcResourceScopeRepository.java:23](../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/JdbcResourceScopeRepository.java)
resolve protected resources through projects, not account knowledge scopes.
[MemoryStore.java:88](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/MemoryStore.java)
uses nullable project IDs and reconstructs project-name homes.

Putting a Global account UUID into a project field is not a migration: it either
fails lookup or reaches ordinary project registration semantics. Keeping null and
calling it the account preserves the ambiguity. Introduce typed targets and
explicit mappings, preserving existing memory/resource IDs and provenance. A
normal project named global must remain a normal project.

#### 2. Existing role checks do not independently represent execution actions

[ProjectRole.java:4](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/ProjectRole.java)
has ordinal VIEWER/CONTRIBUTOR/MANAGER roles.
[ProjectAuthorization.java:245](../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/ProjectAuthorization.java)
classifies named reads/management and defaults other mutations to CONTRIBUTOR.
[ConversationOpenHandler.java:88](../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/ConversationOpenHandler.java)
checks ordinary work access before creation;
[Runs.java:278](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/Runs.java)
uses work authority for fresh and standalone runs.

Adding GLOBAL_READ to the ordered enum risks implying write/manage access. Adding
only an EXECUTION flag at conversation-open misses resume, turns and standalone
model work. Define action grants and mandatory destination workload support,
then classify each admission and retained continuation. An empty open needs
CREATE; a first model turn needs RUN_MODEL too. Existing project roles can remain
compatibility helpers mapped to those actions. Adding an enum value alone does
not supply that model.

The existing user-facing Global conversation resume refusal can remain. Internal
SYSTEM summarization runs without that public entry point; enabling controlled
continuation through it is a separate API/exposure decision. Scoped internal
processing admission must not accidentally expose worker logs to ordinary callers.

#### 3. Ordinary omitted-target Application routing needs a new resolver

[Asking.java:15](../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/Asking.java)
deliberately carries no implicit project.
[FrameRouter.java:193](../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/FrameRouter.java)
checks central authorization before handler home resolution.
[ProjectAuthorization.java:363](../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/ProjectAuthorization.java)
refuses ordinary service operations lacking an explicit/resource-derived project.
[PersonalSpaces.java:85](../plowshare-server/src/main/java/io/aeyer/plowshare/server/personal/PersonalSpaces.java)
defaults humans to Personal but refuses service Personal provisioning at line 103.

The human default and explicit Application project requests can stay. Application
identity/project binding already exists; ordinary conversation/run omission does
not consult it. That convenience needs a trusted admitted execution-project
binding resolved before authorization. Generic multi-project service credentials
cannot choose their first project or infer a destination from an account name.
Preserve explicit resource ownership and retained acting targets over defaults.

#### 4. SYSTEM processing needs scoped authority, not a Global-wide ban

[InformationWriteGates.java:108](../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationWriteGates.java)
and [InformationStageHooks.java:56](../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationStageHooks.java)
create document_pipeline logs using Home.global when no project is selected.
[InformationLifecycle.java:487](../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationLifecycle.java)
also maps null document projects to Global for summarization; tagging/grouping
follow the same pattern.
[Deliberation.java:303](../plowshare-server/src/main/java/io/aeyer/plowshare/server/documents/Deliberation.java)
uses Information selection to choose a document-question execution home.

These paths are not inherently invalid because their processing scope is Global.
SYSTEM's existing authority covers EXECUTION and required READ/WRITE there; an
operation sandbox limits each run. There is no mandatory move to a new operational
scope. They still need explicit typed
targets, executor and retained action authority. Personal documents also use
these null paths, so their execution/log location does not prove Global knowledge
ownership. Preserve distinct input owner, execution scope, initiating actor,
sponsor, output grants and private log policy.

An authorized human upload can trigger a declared SYSTEM service using SYSTEM's
existing authority within its operation sandbox. Requiring the human's RUN_MODEL grant for every internal service
would break that workflow; silently lending SYSTEM to an arbitrary denied run
would defeat authorization. Specify the service trigger, inputs/outputs and
spending contract without changing ordinary child-delegation ceilings.

The target also makes admitted SYSTEM processing independent of the creator's
continued access. Current [processing authorization:19](../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/JdbcInformationProcessingRepository.java)
requires an active legacy owner with project membership/access; [renewal and checkpoint checks:239](../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/JdbcInformationProcessingRepository.java)
repeat that dependency and input checks. Losing the owner's access can therefore
fence processing today. Replace that dependency with SYSTEM authority, retained
operation bounds and current document/operation validity. Preserve generation,
availability, input, cancellation and budget fences. A former creator's inability
to read results must not cancel otherwise valid SYSTEM preparation or disclose
those results back to that creator. See the [adopted continuation policy](decisions/0030-authority-substrate-and-explicit-scopes.md#system-processing-validity-and-creator-access-changes).

#### 5. Reuse chat dispatch authorization; extend authority carriage

[ChatRequest.java:88](../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/dispatch/ChatRequest.java)
defaults ordinary callers to the no-op
[CallAuthorization.DIRECT:6](../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/dispatch/CallAuthorization.java).
[LlmDispatcher.java:172](../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/dispatch/LlmDispatcher.java)
already accepts chat authorization callbacks, a useful queue-time enforcement seam.
Its [embedding path:352](../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/dispatch/LlmDispatcher.java)
has accounting but no equivalent authorization callback in
[EmbeddingRequest.java:11](../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/dispatch/EmbeddingRequest.java).

Scribe, Learner, digest models, schedule readers and harness validators also call
model dispatch outside a normal conversational turn. A gate only in JobRuntime
misses them. Apply the adopted all-inference RUN_MODEL action to the actual
executor, including SYSTEM services with operation bounds; carry retained
authorization to queued work and recheck before effects. Non-inference counting
remains a separate classification; apply operation-specific budget sponsorship
independently of account permission. Do
not treat an attribution record or positive budget as permission.

Concrete side-call owners include
[Scribe.java:320](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/scribe/Scribe.java),
[Learner.java:324](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/learner/Learner.java),
[ScheduleReader.java:247](../plowshare-server/src/main/java/io/aeyer/plowshare/server/events/ScheduleReader.java)
and [ModelCallValidator.java:86](../plowshare-server/src/main/java/io/aeyer/plowshare/server/harness/ModelCallValidator.java).

#### 6. Administrator management conflicts with exact-owner mutation APIs

[JdbcInformationCatalogueRepository.java:65](../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/JdbcInformationCatalogueRepository.java)
locks a resource only when owner_handle equals the caller. Admission chooses an
actor-owned namespace at line 663 and records/enforces that owner at line 718.
[InformationCatalogue.java:1555](../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationCatalogue.java)
uses that contract for managed operations;
[PortableInformation.java:336](../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/PortableInformation.java)
requires the same ownership on replacement imports.

The target explicitly replaces this owner-equality gate with scoped READ/WRITE
and control grants. A Global ADMIN can then maintain the manual while its owner
remains GLOBAL_ACCOUNT and the human remains the actual actor. The present APIs
need migration to evaluate those grants, preserve source/input restrictions and
record the human action. Logging in as
Global, rewriting the author, or making all owners ADMIN would defeat the design.

#### 7. Universal read needs coordinated Java, SQL and retained admission changes

[InformationAccess.java:51](../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationAccess.java)
refuses service Personal/Shared selection;
[InformationContext.java:67](../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationContext.java)
has only Personal/Project/Shared selections.
[V147:99](../plowshare-server/src/main/resources/db/migration/V147__document_navigation_policy.sql)
independently keeps service content/reference traversal inside the selected project.
Changing only the Java gateway leaves SQL traversal refusing the manual.
The same navigation policy also enforces effective owner-only content settings
and retained reference obligations. Owner-only is false by default; references
are hidden and propagation enabled by default (V147 lines 73–74). GLOBAL_READ
does not override an effective owner-only or mandatory input/reference restriction.
An authorized audience change must respect those policies rather than bypass the
predicates; not every public resource needs a policy relaxation.

[PersonalRunAuthority.java:25](../plowshare-server/src/main/java/io/aeyer/plowshare/server/acting/PersonalRunAuthority.java)
also restricts every requested/resource home to exact Personal;
[ActingRequests.java:42](../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/ActingRequests.java)
currently admits only a small delegated operation set. A librarian with an ambient
GLOBAL_READ role can still be refused by its retained ceiling. Add explicit
supported read-source admission without loosening the execution home, actor/subject
consent, private Personal isolation or existing live-proof checks. Existing strict
Personal admissions must not silently expand after a role change.

The [explicit account-delegation mapping](decisions/0030-authority-substrate-and-explicit-scopes.md#account-delegation-application-acting-for-a-user)
now records the intended action translation. Current
[ActingPermission.java:9](../plowshare-server/src/main/java/io/aeyer/plowshare/server/acting/ActingPermission.java)
and [LiveActingAuthorization.java:98](../plowshare-server/src/main/java/io/aeyer/plowshare/server/acting/LiveActingAuthorization.java)
admit READ/WORK through VIEWER/CONTRIBUTOR; they do not yet carry independent
WRITE, CREATE or RUN_MODEL grants. Reuse actor/subject proof and target-specific
checks, replacing only the coarse action ceiling through an explicit compatibility
contract. Personal delegation deliberately requires no actor Personal membership;
project delegation still intersects actor and subject project authority. Subject
consent does not turn ordinary bot interaction into resource access management.

#### 8. Shared conversion and Global ownership transfer are different migrations

[JdbcInformationCatalogueRepository.java:830](../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/JdbcInformationCatalogueRepository.java)
changes a revision's Shared visibility and policy project_id while retaining the
resource owner/home; all consumed inputs must already satisfy Shared reads.
This can inform GLOBAL_READ authorization, but does not authorize an ownership
transfer. Admitting previously excluded Application/service readers also expands
the old audience and needs an explicit migration policy.

The target now adopts live root-document permissions, represented by the most
current revision, for all revision reads. Publication can remain group/project
scoped and does not imply GLOBAL_READ. Per-revision Shared flags therefore need
explicit reconciliation into the root policy; unioning historical audiences would
widen access. Historical/pinned reads must use the current root permissions while
preserving revision lifecycle and input/reference restrictions.

The [manual installer:306](../scripts/install-manual.mjs) publishes from the human
account's Personal namespace and verifies Shared reads. The
[manual reader:21](../plowshare-desktop/src/manual.ts) finds a unique shared chapter.
Manual adoption must preserve resource/revision identities, tags, ownership audit
and original receipts, and update installer ownership/control and reader discovery
contracts together. Otherwise administrators cannot update it, or publish a second
competing manual under their own namespace. No live installation was inspected.

#### 9. Read-source selection and curation currently widen or move data

[Archive.java:559](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/Archive.java)
appends Global results to project recall/survey.
[Navigator.java:82](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/digests/Navigator.java)
starts a model operation under the requested home and falls back to Global at
line 125. General read permission does not mean every task selected that corpus.
Unscoped aggregation needs a documented ranking/count/paging contract, preserving
[repository-side filtering:10](../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationSql.java).

[Curator.java:827](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/curator/Curator.java)
autonomously approves promotion;
[Archive.java:1080](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/Archive.java)
creates a Global memory and supersedes the source. Authorizing GLOBAL_READ on
that source instead would preserve owner and identity, a materially different
effect. Decide what curation proposes and who can approve it. Recall also
[updates use counters:389](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/Archive.java):
read telemetry/index maintenance must not require granting readers content-edit
rights or permit them to change audience/claims.

#### 10. Durable jobs need explicit scope and retained grants

[Digests.java:137](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/digests/Digests.java)
captures the requested home in a closure but submits through an overload that
[defaults the durable job to Global:919](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/JobStore.java).
After restart only the durable record remains; generic JobStore work does not
resume its closure, and that closure is not an authoritative scope record. Other async
consumers must retain execution owner, resource read/output targets, grant
references and original request identities. Classify existing jobs rather than
assigning their null home to GLOBAL_ACCOUNT. Never replay uncertain effects to
reconstruct missing authority.

#### 11. Internal and resumed admissions need the same action checks

[JobRuntime.java:206](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/JobRuntime.java)
defaults ordinary admission checks to no-op; its retained path installs and
rechecks authority, including
[model dispatch and publication:4705](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/JobRuntime.java).
Ordinary [JobStore submission:864](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/JobStore.java)
uses the unconfined runtime. Public endpoint checks alone do not cover those
independent admissions.

[AgentRunner.java:118](../plowshare-server/src/main/java/io/aeyer/plowshare/server/events/AgentRunner.java),
[WorkRelayReceiver.java:74](../plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/WorkRelayReceiver.java)
and [Orchestrations.java:800](../plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/Orchestrations.java)
currently rely on work/membership and their owning lifecycle checks. Schedules,
message-triggered conversations, child jobs, approvals and orchestration resume
need explicit retained workload/action authority at their actual firing/dispatch
boundaries. A membership snapshot or prior approval is not a perpetual RUN_MODEL
grant. Preserve existing receipt reconciliation and do not recover by relaunching
work whose first admission may already have committed.

Operation-control authority maps to WRITE plus each existing control's domain
rules, with no CONTROL_EXECUTION permission. [JobCancelHandler](../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/JobCancelHandler.java)
uses the current job eligibility lookup and requests cancellation; it does not
promise immediate termination. [SchedulePauseHandler](../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/SchedulePauseHandler.java)
supports schedule pause/resume, with [Application control authority](../plowshare-server/src/main/java/io/aeyer/plowshare/server/events/ApplicationScheduleControlAuthority.java)
preserved. These do not establish generic running-job pause/resume or direct
cancellation of every document-processing operation. Map verified controls only;
missing controls remain absent, not a new implementation requirement, and this
proposal does not assume a generic operation ACL/resource registry exists.

#### 12. Built-in accounts and ADMIN grants need lifecycle migration

[V111:1](../plowshare-server/src/main/resources/db/migration/V111__service_accounts.sql)
supports USER/SERVICE/SERVICE_TOKEN account kinds, while
[JdbcPersonalSpaceRepository.java:50](../plowshare-server/src/main/java/io/aeyer/plowshare/server/personal/JdbcPersonalSpaceRepository.java)
provisions Personal for ordinary users. SYSTEM and Global must have explicit
noninteractive capability/lifecycle policies; Global must not gain a Personal
execution workspace through normal account creation. Existing account identity
is handle-based, so fixed UUIDs need mappings, not a destructive foreign-key swap.

Universal role membership also requires a current authenticated, active principal.
[ServiceAccounts.java:90](../plowshare-server/src/main/java/io/aeyer/plowshare/server/auth/ServiceAccounts.java)
revokes tokens/sessions on disable; rotation at line 166 retires the prior
credential, while Application-owned token issuance retains its own receipt path.
New public reads must preserve those revocation boundaries. Role universality is
not anonymous access or a reason to accept retired credentials.

The audited baseline gave server administrators implicit MANAGER in ordinary
projects in both Java and SQL. Slice 03 replaces that path with explicit control
grants and per-existing-project data compatibility bindings. New administration
authority supplies control without implicit data access. Both the Java role
source and SQL information admission use the live grant model; Application
manifest limits remain an additional Java boundary. See the
[compatibility specification](authority-substrate-slices/03-rbac-grant-specification.md).
No Personal access was added.

#### 13. Definition trust, disk layout and accounting remain separate migrations

[LayeredDefinitions.java:43](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/LayeredDefinitions.java)
selects the first named definition;
[AgentRegistry.java:1143](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/AgentRegistry.java)
rejects authored is-system metadata, and
[AgentExposure.java:15](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/AgentExposure.java)
uses the resolved classification. Preserve authored provenance while representing
the trusted worker purpose independently of an override's name or flag.

[DataLayout.java:26](../plowshare-server/src/main/java/io/aeyer/plowshare/server/data/DataLayout.java)
places deployment definitions, exports and images under the same physical global/
tree. Do not relabel/rename the whole directory into Global-owned public knowledge.
Keep versioned disk migration separate from Flyway and preserve protected roots,
project unions, stored images and private legacy logs.

[UsageAttribution.java:44](../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/accounting/UsageAttribution.java)
currently forbids an account handle on SYSTEM attribution; this is an accounting
classification, not the proposed SYSTEM login identity. Preserve historical
sponsor/cost facts while adding executor/owner mappings. Reading Global must not
bill the Global owner by implication or make private processing logs public.
The adopted [operation budget policy](decisions/0030-authority-substrate-and-explicit-scopes.md#operation-specific-budgets-and-accounting)
adds an explicit operation-to-configured-budget binding retained through queues
and recovery. Preserve existing attribution/attempt records while adding that
binding; SYSTEM execution or creator provenance must not silently select a payer.
Model-call allowance, capacity routing and monetary reservations are distinct.

### Global cutover: later milestone prerequisites

The later Global ADMIN upload, SYSTEM preparation and supported reader milestone
needs the following concrete specifications before its code is authorized. These
are not additions to the [first READ/WRITE foundation slice](authority-substrate-first-slice-plan.md):

1. Typed account/project/resource targets, reserved identity mapping and safe
   built-in bootstrap/recovery, alongside existing persistence keys.
2. Existing role/manifest/token-to-action compatibility and default role profiles,
   including explicit admin-data and agent-exception treatment.
3. Each declared service's permitted trigger, SYSTEM executor, operation sandbox,
   input/output targets, configured operation budget and private-log policy.
4. Current root-policy enforcement across supported read paths, revision and
   ownerOnly reconciliation, retained input/reference checks and supported acting
   read targets. Unsupported consumers stay refused; SDK coverage is not assumed.
5. Legacy data/audience/manual migration and an auditable rollout/rollback plan
   preserving resource, request, receipt and historical accounting identities.

Resolve grant-version/expiry and dispatch-versus-revocation races in the affected
consumer contract. Preserve existing log/control semantics while classifying
internal conversation-shaped logs; this does not imply a new CREATE exemption,
new control API or generic job-resume capability. Implementation specifications
must honor the settled rules rather than silently widen access or require custom
ACL configuration for routine roles.

## What can be reused

These are existing mechanisms to extend behind their typed contracts, not claims
that they already implement the complete substrate.

| Existing mechanism | Reuse and limit |
| --- | --- |
| [ApplicationServiceManifest:21](../plowshare-server/src/main/java/io/aeyer/plowshare/server/applications/ApplicationServiceManifest.java), [Application activation:56](../plowshare-server/src/main/java/io/aeyer/plowshare/server/applications/JdbcApplicationIdentityRepository.java) and [live role resolution:129](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/JdbcProjectMembers.java) | Applications already declare a role, bind an exact project and receive ordinary service-token scopes. Map those role helpers to common actions; no second Application permission engine or wholesale manifest rewrite |
| [ProjectRole:4](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/ProjectRole.java) and [membership mutation:208](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/JdbcProjectMembers.java) | Preserve role-facing helpers, transactional grant changes, audit and last-manager checks while introducing the action mapping |
| [LiveActingAuthorization:28](../plowshare-server/src/main/java/io/aeyer/plowshare/server/acting/LiveActingAuthorization.java) | Reuse actor/subject proof, Application identity, current consent/membership and narrowing ceilings; extend read targets deliberately instead of dropping the proof model |
| [JobRuntime retained admission:215](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/JobRuntime.java) and [JobStore admitted submission:762](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/JobStore.java) | Keep checks around steps, tools, inference and disclosure, plus receipt-before-start ordering; generalize from supported retained turns to common actions |
| [Chat dispatch:172](../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/dispatch/LlmDispatcher.java) and [beforeDispatch:64](../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/dispatch/InferenceObserver.java) | Live callback checks already exist for chat dispatch variants, including after queueing. Supply actual executor authority and operation bounds where requests default DIRECT; embeddings also need equivalent carriage under the adopted all-inference RUN_MODEL rule |
| [Information lifecycle:148](../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationLifecycle.java) and [Summariser.forRevision:310](../plowshare-server/src/main/java/io/aeyer/plowshare/server/documents/Summariser.java) | Keep claims, generation fences, lease renewal, pre/post gates, budgets/checkpoints and the separately supplied home/owner/parent fields. Retain SYSTEM identity/authority and enforce the operation sandbox; processing can remain in Global |
| [Shared transition:830](../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/JdbcInformationCatalogueRepository.java) | Existing audience changes preserve owner/home. Reuse that distinction and receipts for GLOBAL_READ migration without treating it as ownership transfer |
| [InformationSql:10](../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationSql.java), [reference composition:147](../plowshare-server/src/main/resources/db/migration/V147__document_navigation_policy.sql) and [InformationJobs:46](../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationJobs.java) | Reuse filtering before paging/counts, retained-input/reference obligations and current disclosure checks; adapt their target vocabulary rather than bypass predicates |
| [Metadata-only revisions:501](../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationCatalogue.java) and [projection reuse:461](../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/JdbcInformationProjectionRepository.java) | Preserve immutable revision IDs, explicit_only processing and compatible committed output reuse. Saving metadata must not acquire a new model-execution requirement or schedule paid work |
| [Curator proposals:810](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/curator/Curator.java) | Keep durable proposal/settlement machinery; decide whether the approved action is general-read authorization, copy or transfer and check its specific authority |
| [SYSTEM model-only ceiling:8](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/SystemModelTasks.java) and [deployment snapshots:35](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/ReloadingGlobalAgents.java) | Keep restricted worker capabilities, deployment/bundle lookup and invalid-replacement refusal. Scoped EXECUTION must not turn a model-only worker into an arbitrary tool agent |
| [Processing usage ownership:19](../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/accounting/UsageOwners.java) and [durable inference accounting:107](../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/accounting/DurableInferenceAccounting.java) | Preserve executor/source-access/billing distinctions and attempts recorded before transport. Add identity mappings without rebuilding accounting or relabelling historical spend |
| [Relay recovery:119](../plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/WorkRelayReceiver.java) and [provider invocation:130](../plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/tools/ProjectRelayToolInvocations.java) | Preserve owning receipts, correlation, deadlines and read-only uncertainty reconciliation. A new role must never replay effects or grant provider capabilities |

Personal privacy, resource-derived scopes, active-account/token revocation,
private sessions, log/input disclosure policy and ordinary filesystem fences
remain separate restrictions. Owner-equality mutation checks can change without
removing these boundaries. GLOBAL_READ applies to intended knowledge resources,
not every log, prompt, receipt or accounting row sharing a scope.

## Deferred gaps

These are optional behavior, capability gaps or separately scoped design, not
requirements to finish a new feature before the core permission mapping can
proceed. A later slice must resolve the relevant gap before exposing that behavior.

| Topic | What stays deferred |
| --- | --- |
| First-class tenant model | Tenant/Personal membership and existing account/project mappings. Deployment-wide GLOBAL_READ and private-scope isolation are settled; do not infer tenant identity from project names. |
| Generic Application defaults | New omitted-target convenience for multi-project service contexts. Explicit targets remain usable and ambiguous omission refuses; choosing the first token project is not a fallback. |
| Search aggregation presentation | Paging/ranking/coverage, duplicate/current revisions, reference-only hits and optional project-plus-general-read selection. All-readable unscoped selection remains bounded by admission; no hidden fallback. |
| Global type/schema authoring | Existing type management is Personal/Project-scoped. A Global registry or new API/SDK authoring surface is not inferred. |
| General curation outcome | Choose audience grant, copy or home/control transfer, with ID/supersession/approval consequences, before changing promotion. MANAGER audience authority and human Global ADMIN destination control remain fixed. |
| Broader acting consumers/read targets | Existing retained turns are Personal-scoped and operation-whitelisted. General project work or new Global read targets require an explicit supported contract; never discard acting proof to make them work. |
| Public SYSTEM-work continuation | Existing public Global conversation refusal can remain; internal processing needs no new public create/resume API. |
| SDK/client coverage and missing controls | Inventory existing interfaces only. No feature-parity mandate, new APIs/methods, generic job pause/resume/cancel service, operation ACL registry or restartable closure runtime is implied. |
| Advanced ACL facilities | Resource-specific ACLs fit the substrate, but arbitrary denies, nested groups and new management interfaces are not assumed or required. RBAC helpers remain the routine path, and GLOBAL_READ has no individual exclusions. |
| Detailed spending-pool mechanics | Operation sponsorship is settled. Pool configuration, reservation/settlement races and migration remain separate technical work under ADR 0025. |

Concrete activation limits after slice 11:

- Embedding/search query, write and repair calls need an explicitly configured
  operation-budget source. The dispatcher refuses unclassified calls; slices
  07–08 do not supply an invented allowance or wallet. This branch is not ready
  for deployment-wide activation of those paths.
- Independent Relay orchestration roots now retain authority. Nested/model-started
  orchestration allowances still require a supported parent-budget contract, and
  non-conversation child leases cannot be replaced on resume. Existing caller-log
  launch shapes are conservatively refused where that distinction is absent.
  Original receipts are reconciliation evidence, never a reason to repeat a start.
- Slice 06's ordinary retained target envelope admits only its exact execution
  home. Explicit Global/cross-project memory tool references refuse under
  `PinnedOperationAuthority.requireTargets`; slice 05's standalone knowledge
  GLOBAL_READ policy does not widen that sandbox. Broader model-tool read targets
  need an explicit supported envelope in later contract/cutover work.

## Suggested implementation order and future proof

These are review dependencies, not authorization to implement:

The scoped [first slice](authority-substrate-first-slice-plan.md) covers the
initial READ/WRITE contract and one existing consumer. The broader steps below
describe subsequent migration dependencies, not requirements to include them all
in that first change.

Use the [numbered roadmap](authority-substrate-implementation-plan.md) as the
implementation decomposition, with 14 slices including the existing first slice:

| Workstream | Slices |
| --- | --- |
| Project foundation, identities and scoped RBAC helpers | 01–03 |
| Knowledge scope/provenance, WRITE and current root-document audience policy | 04–05 |
| Retained operations, all-inference/budget checks and sandboxed SYSTEM services | 06–08 |
| Scheduled work, Relay/messages, orchestration/children and account acting | 09–12 |
| Existing contract/client cutover and integrated legacy migration proof | 13–14 |

Each slice carries its own negative/persistence/recovery evidence. The final
cutover consolidates that proof; it does not defer earlier security checks or
authorize new APIs, SDK parity or any deferred capability.

Focused mocked/fake acceptance coverage should include:

- Routine authorized setup uses ADMIN/MANAGER/CONTRIBUTOR/READER profiles and
  Application helpers without custom ACLs. Optional finer resource grants use
  the same evaluator, scope/issuer limits and audit; they cannot bypass mandatory
  policy, agent ceilings or GLOBAL_READ's no-individual-exclusions rule. Profiles
  do not automatically create membership, provision new capabilities or widen
  narrower token/acting limits.
- Empty conversation creation without inference; first turn/standalone model work
  without RUN_MODEL refused. GLOBAL_READ and human Global ADMIN without execution
  cannot create/run there; bounded SYSTEM EXECUTION succeeds. Check actions before
  effects and recheck later-turn/child/queued revocation without identity bypasses.
- Human omission routes Personal; admitted Application omission routes itself;
  ambiguous/missing or invalid explicit context refuses without Global fallback.
- Global manual preparation executes as SYSTEM within its admitted scope and
  READ/WRITE/EXECUTION grants; GLOBAL_ACCOUNT retains ownership and worker logs
  stay private. Authorized writes trigger only declared bounded processing, never
  arbitrary SYSTEM prompts or tool work.
- Removing the creator/initiator's membership or READ/WRITE does not stop an
  otherwise valid admitted SYSTEM operation, including queued/recovered work.
  Document/operation invalidation fences obsolete commits; the former creator
  cannot read results/logs or receive content notifications without current access.
  Delegated user work still refuses when its live acting authority is revoked.
- Human, Application/service and supported retained-acting reads work through list,
  direct revision, schema attachment, relationships, jobs and export; GLOBAL_READ
  supplies no mutation or execution authority and no private-resource disclosure.
- Every inference path checks RUN_MODEL for its actual executor, including
  embeddings, reranking, validators and queued side calls. A READER without
  RUN_MODEL can request authorized search with bounded SYSTEM-service inference;
  the same caller cannot invoke arbitrary model work or expand the service's
  targets/tools. Queue-time checks retain executor authority, sandbox and limits.
- Non-model scripts, tool-only jobs, CPU stages and scheduled/Relay-triggered
  operations check their actual READ/WRITE/control actions without EXECUTE_JOB or
  an unconditional EXECUTION gate. Creating a conversation or later calling a
  model still checks CREATE/RUN_MODEL; existing tool/file/provider/route and
  lifecycle restrictions remain in force. Timers/messages confer no new authority.
- Existing lifecycle controls require WRITE plus their own domain rules, without
  CONTROL_EXECUTION or creator privilege. READER status access grants no mutation.
  Preserve cancellation-request and schedule-control semantics; do not invent
  missing job pause/resume/cancel surfaces, operation ACLs or restart capabilities.
- Operation-specific budget bindings survive queueing, creator access removal and
  recovery. Compaction, scoped preparation, Global maintenance and semantic search
  use their configured operation budget sources; missing/exhausted budgets prevent
  further inference without switching payer. Preserve incurred costs/receipts and
  historical attribution; a budget balance is not permission to infer or replay.
- Removing public authorization or withdrawing a required input stops future
  disclosure, including saved views, delayed work, references and message delivery.
- Publishing to a group does not grant GLOBAL_READ. Current root-document
  permissions govern new and historical/pinned revisions; removing an audience
  blocks subsequent old-revision reads. Content-only updates cannot widen access,
  and changing document permissions cannot bypass retained-input restrictions.
- A resource MANAGER/DRI can grant/remove GLOBAL_READ without separate expansion
  authority or ADMIN approval, preserving home/created_by. CONTRIBUTOR cannot
  change that audience, and manager authority over one resource cannot change
  unrelated/private source permissions or Global-account administration.
- GLOBAL_READ authorizes the authenticated deployment-wide audience without
  per-account audience exclusions. Restricted readership removes GLOBAL_READ and
  uses project/group permissions. Disabled/revoked identities, retained operation
  limits and mandatory input/reference restrictions remain enforced; no deny-entry
  mechanism or new group-management interface is inferred.
- Human ADMIN writes a Global-owned resource through scoped action grants without
  impersonating Global or becoming its owner; Application roles use the same
  evaluator rather than separate authority logic.
- The same authenticated human with Global ADMIN is evaluated by account/actions
  through an existing supported management API regardless of client label. Client
  choice cannot elevate another principal. This does not assume every SDK/client
  exposes that API or require new endpoints, methods or feature parity.
- A creator who loses scoped access cannot recover READ/WRITE/control through
  created_by. Content updates and authorized scope moves preserve original
  creation provenance; Application/Global home is not substituted as creator.
- Shared-to-general-read authorization and ownership transfer have separate
  receipts; promotion, IDs, supersession and audience expansion are intentional.
- Built-in accounts cannot log in, become acting subjects, receive Personal
  workspaces or be deleted/renamed through generic human lifecycle routes.
- Expired/revoked credentials and disabled principals cannot retain public reads
  merely because all authenticated principals hold GLOBAL_READ; Application token
  rotation keeps the existing receipt and session-retirement contract.
- Java and SQL decisions agree; old/new contract clients receive explicit failures
  rather than null fallbacks; uncertain mutations are reconciled, never replayed.

Later PostgreSQL tests are needed for identity/FK migrations, policy SQL, row
mapping, grant/effect races, rollback and durable receipts. Run only the smallest
relevant tagged suite with explicit `-PfullDb` and a stated persistence reason.
Disk-layout migrations additionally need retained-tree rehearsal and restart proof.
None of those properties is established by this source scan.

## Verification performed

The following records the original proposal audit. Subsequent slice 01 runtime
verification is tracked in the [first-slice document](authority-substrate-first-slice-plan.md).

The earlier three-segment review checked the corrected scoped-grant model,
including a parent review of Application role binding, user-facing Global resume
fences, summarizer home/owner parameters and the queue-time chat callback. Findings
were classified as contract incompatibilities, new capability gaps, policy choices
or existing mechanisms to reuse. The source scans were read-only. This
consolidation separates settled policy, migration requirements, reuse and deferred
gaps; it was not a new exhaustive runtime/client audit. That consolidation changed
only the proposal, source map, permission matrix, report and documentation index.
Document structure, local links and diff whitespace were checked before commit.
No runtime or database tests were newly run for that documentation-only task.

Earlier in this conversation, the unchanged runtime baseline passed 28 focused
cases across InformationFeatureConfigTest, DocumentNavigationTest,
InformationAccessTest and AdmittedTurnTest. That validates current behavior only;
it does not validate the proposed authority substrate. Test expectations for old
Global recall, promotion and service refusals need intentional migration, not
blanket deletion. No live database, tenant inventory, token issuance or deployment
was inspected or changed.
