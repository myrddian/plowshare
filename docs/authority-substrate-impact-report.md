# Authority substrate and Global impact report

Status: source-grounded impact assessment, 17 October 2026. Proposal only; no
runtime, database, permission or client implementation is included.

Read with [ADR 0030](decisions/0030-authority-substrate-and-explicit-scopes.md),
the [migration map](global-scope-migration-map.md) and
[permission matrices](role-scope-permissions-proposal.md).

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

Rechecked against the corrected model at proposal commit `af89b7f`, using the same
three server segments. No runtime has broken because this remains documentation
only. The compatibility findings describe what fails if the new identities or
grants are enabled against unchanged contracts. Missing action checks and optional
new defaults are capability gaps, not evidence that existing explicit-project
workflows are broken. No new runtime, wholesale role replacement, mandatory token
reissuance or processing-scope relocation follows from this design.

Runtime baseline: main `269854a7c5bcc5f7080bc04de01ea8ccce46eaa7`, merged into this
proposal branch. PR #74 enables existing information features by default; PR #75
fixes test completion timing. Neither implements the new authority model.

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

## Adopted requirements assessed

- SYSTEM is the locked root account; its role cannot be assigned to agents.
- SYSTEM execution is reserved for platform functions. A memory worker remains
  SYSTEM with its existing root authority, but an operation sandbox limits writes
  to the admitted memory area and bounds other effects. The sandbox is not a new
  per-task grant to SYSTEM and cannot be expanded by the running worker.
- For compaction, the SYSTEM summariser already has conversation READ and summary
  WRITE authority. Selected inputs and bound tools constrain its reachable
  resources and outputs. Preserve/enforce those capability fences rather than
  invent per-compaction account grants or an account-role hierarchy for tools.
- `GLOBAL_ACCOUNT` is a knowledge owner, not a project or execution identity.
  Human ADMIN controls its knowledge without logging in as that account.
- `GLOBAL_READ` is held by authenticated humans, Applications and service
  principals; resources authorize it independently of ownership. Read grants do
  not grant publication, execution or unrestricted model spending.
- `EXECUTION` contains `CONVERSATION_CREATE` and `RUN_MODEL`, independently
  checked as grants to the executing account over the resolved scope. SYSTEM can
  execute bounded Global work. GLOBAL_READ grants READ only; Global ADMIN supplies
  READ/WRITE/control without implicit EXECUTION. No reserved-ID allow branch.
- Role/Application-role helpers use that common substrate. Ordinary knowledge
  READ/WRITE checks replace actor-equals-owner authorization. `created_by` records
  original creation provenance with no implicit rights; account home, revision
  author, actor and source/input policy remain independent. Legacy owner columns
  cannot be blindly renamed into creator provenance.
- Omitted execution targets resolve to the admitted Application/project itself,
  or a direct human's Personal project. Missing/ambiguous context refuses. No
  ordinary default may choose Global. SYSTEM processing can explicitly select
  the typed Global scope under retained grants.
- Resource ownership, execution destination, authorized read sources, output
  destinations, actor/subject and sponsor are separate facts.
- Deployment overrides and bundles supply SYSTEM worker definitions; project or
  client namesakes cannot select the worker or acquire SYSTEM authority.

## Compatibility classification

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
| Implicit server-admin MANAGER | Deliberate compatibility policy | Decide explicit replacement grants; removing this privilege is not a prerequisite to unrelated Global reads |
| Digest job recorded with default Global home | Existing persistence mismatch | Store its real scope/admission; do not reconstruct authority from a vanished worker closure |
| Disk, accounting and legacy rows | Classification/identity extension | Preserve existing lifecycles, IDs, private records and costs; do not move/relabel everything called global |

## Contracts and capability gaps requiring attention

### 1. Project-only scopes cannot express the new ownership model

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

### 2. Existing role checks do not independently represent execution actions

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

### 3. Ordinary omitted-target Application routing needs a new resolver

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

### 4. SYSTEM processing needs scoped authority, not a Global-wide ban

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

### 5. Reuse chat dispatch authorization; extend authority carriage

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
misses them. Decide the model/embedding/counting/sponsorship action contract,
carry the retained authorization to queued work and recheck before effects. Do
not treat an attribution record or positive budget as permission.

Concrete side-call owners include
[Scribe.java:320](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/scribe/Scribe.java),
[Learner.java:324](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/learner/Learner.java),
[ScheduleReader.java:247](../plowshare-server/src/main/java/io/aeyer/plowshare/server/events/ScheduleReader.java)
and [ModelCallValidator.java:86](../plowshare-server/src/main/java/io/aeyer/plowshare/server/harness/ModelCallValidator.java).

### 6. Administrator management conflicts with exact-owner mutation APIs

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

### 7. Universal read needs coordinated Java, SQL and retained admission changes

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

### 8. Shared conversion and Global ownership transfer are different migrations

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

### 9. Read-source selection and curation currently widen or move data

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

### 10. Durable jobs need explicit scope and retained grants

[Digests.java:137](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/digests/Digests.java)
captures the requested home in a closure but submits through an overload that
[defaults the durable job to Global:919](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/JobStore.java).
After restart only the durable record remains; generic JobStore work does not
resume its closure, and that closure is not an authoritative scope record. Other async
consumers must retain execution owner, resource read/output targets, grant
references and original request identities. Classify existing jobs rather than
assigning their null home to GLOBAL_ACCOUNT. Never replay uncertain effects to
reconstruct missing authority.

### 11. Internal and resumed admissions need the same action checks

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

### 12. Built-in accounts and ADMIN grants need lifecycle migration

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

[JdbcProjectMembers.java:138](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/JdbcProjectMembers.java)
gives server administrators implicit MANAGER in ordinary projects. SQL also
contains administrative project admission. Removing only one bypass disagrees
with the other; removing both without grant migration removes existing operator
access. Decide the explicit replacement grants without widening Personal access.

### 13. Definition trust, disk layout and accounting remain separate migrations

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

## Assumptions and decisions still uncovered

| Decision | Why implementation depends on it |
| --- | --- |
| Scope kind and account knowledge representation | Define typed account/project/execution targets and stable mappings, rather than reuse project/null or account handles as project names |
| Built-in identity lifecycle | Reserve SYSTEM/Global/role/scope UUIDs; specify collisions, bootstrap, disable/delete/recovery, credential refusal and acting-subject rules |
| Trusted Application default | Identify the authenticated binding and precedence; define generic multi-project service behavior and default resolution timing |
| Root-document policy compatibility | Settled: publication does not imply GLOBAL_READ; the root document's current permissions govern its revisions, including historical/pinned reads. Reconcile existing per-revision Shared audiences and define atomic policy-version enforcement; lifecycle/draft eligibility and audience-change issuance remain separate |
| Meaning of RUN_MODEL | Cover chat, embeddings, reranking, model validators, preparation and side calls; decide whether bounded read-service inference uses requester grants or an explicit service sponsor |
| Non-model execution | CREATE plus RUN_MODEL is the adopted bundle, but does not by itself classify tool-only jobs, scripts, CPU stages, messaging or schedule effects |
| Independent service admission versus delegated children | A permitted WRITE/read trigger may authorize a declared SYSTEM workflow under its own scoped grant; do not require the caller to hold worker execution, lend it to the caller, or auto-escalate arbitrary denied work |
| Processing/log scope and service sponsorship | Settled: SYSTEM already has root authority; an operation sandbox restricts its use without issuing fresh per-task account grants. Define each workflow's trigger, inputs/outputs, sandbox, sponsor and private logs; billing policy remains unspecified |
| SYSTEM-derived writes versus initial publication | Derived summary/index writes use scoped worker WRITE; initial admission, audience widening and ownership transfer need their separately specified control/release authority. Settle any curation approval policy explicitly |
| Internal conversation-shaped logs | Decide how CREATE applies to worker/hook/audit logs versus user-facing conversations without granting execution merely because a log must be recorded |
| Grant narrowing and queued work | Revalidate live authority within retained ceilings at enqueue/dispatch/result disclosure/recovery; decide grant-version and expiry semantics without replaying effects |
| SYSTEM continuation versus creator revocation | Settled: an admitted independent SYSTEM operation continues while the document and operation remain valid, despite creator/initiator access removal. Replace legacy owner-access lease checks, preserve operation fences and current recipient disclosure checks; delegated user work still needs live acting authority |
| Global write control | Replace exact-owner mutation authorization with common actions; ordinary reads already support membership/shared audiences. Specify control/release actions and audit |
| Legacy owner/creator and ownerOnly migration | Settled: created_by is provenance and grants no permissions. Existing owner_handle/ownerOnly semantics must be reconciled into explicit scope/root-policy grants and any mandatory audience restrictions, preserving actual creation evidence; never substitute caller == created_by or silently remove mandatory policy |
| Public conversation APIs versus internal SYSTEM work | Internal Global processing does not require exposing public Global create/resume. Decide any controlled continuation API and log visibility separately |
| Compatibility role mapping | Specify which old VIEWER/CONTRIBUTOR/MANAGER grants map to READ/WRITE/EXECUTION; retain manifest/token identities where valid and distinguish new grants from previously retained ceilings |
| Default public role versus individual exceptions | Every authenticated principal holds GLOBAL_READ; specify resource-level withdrawal/mandatory constraints and support for intentionally excluding a principal without confusing role membership with resource authorization |
| Tenant isolation and release | Global's audience is settled: all currently authenticated, active principals in the deployment, across project/Application and future tenant boundaries. Preserve private scoped access and source-release/input/reference restrictions; define future tenant mappings separately, without reinterpreting Global as tenant-local |
| Search aggregation | Decide selected read set, project-plus-public convenience, paging/ranking/coverage, duplicate revisions and reference-only hits before changing current Personal-plus-Shared defaults |
| Global schema/type registry | Current type management is Personal/Project-scoped; decide where Global-owned typed documents get immutable schemas and how attached access/import behaves |
| Curation outcome | Choose general-read grant, copy or ownership transfer; define supersession/ID consequences and human approval separately from model assessment |
| Administration through SDK | Human ADMIN remains the write authority. Clarify whether CLI/TUI/GUI are supported management surfaces or a security allowlist: a shared SDK package/client label cannot prove first-party identity |
| Compatibility and rollback | Define mixed-version refusal, coordinated generated contracts, retained-work migration and a rollback that can still enforce new grants/reference records |

Existing role/token/manifest declarations can be preserved through an explicit,
reviewed compatibility mapping to actions over their already authorized scopes.
This does not require reissuing every credential or widening old retained tasks.
New Global read sources and any changed ADMIN rights need intentional policy.
Client labels establish no authority. Public read does not release consumed
private inputs; retained dependencies and reference obligations still apply.

### Minimum decisions for the first end-to-end cutover

The larger table is not a requirement to redesign every subsystem before making
progress. The first working Global ADMIN upload, SYSTEM preparation and SDK
librarian read path needs these decisions:

1. **Typed targets and identity mapping:** account knowledge versus project scopes,
   owner versus executor, and fixed built-in IDs alongside existing keys.
2. **Role-to-action compatibility:** which existing grants supply READ, WRITE,
   CREATE and RUN_MODEL; Global ADMIN has control/write without implicit execution.
3. **Declared SYSTEM service admission:** which authorized upload/read triggers
   which worker, its scoped grants, inputs/outputs, sponsor/budget and private logs.
4. **Effective read policy:** implement the adopted current root-document policy
   across revisions and group/project/general-read audiences; settle supported
   Application/acting read targets, ownerOnly semantics and retained input rules.
5. **Built-in lifecycle:** noninteractive SYSTEM/Global capabilities, no accidental
   Personal provisioning, current credential validity and safe grant issuance.
6. **Cutover policy:** legacy Shared audience expansion and manual ownership
   adoption, existing role compatibility and narrow retained admissions. Disk,
   old job/log and historical accounting classification must preserve identities.

Broader CPU/tool-only work, optional public SYSTEM-workflow continuation and
general curation transfers can remain separately scoped decisions. They must not
be silently inferred from this first knowledge-processing slice. Any rollout of
deployment-wide reading must enforce the settled authenticated audience and its
source-release/private-resource boundaries. First-class tenant entities remain
separate design work; they must preserve this deployment-wide audience.

## What can be reused

These are existing mechanisms to extend behind their typed contracts, not claims
that they already implement the complete substrate.

| Existing mechanism | Reuse and limit |
| --- | --- |
| [ApplicationServiceManifest:21](../plowshare-server/src/main/java/io/aeyer/plowshare/server/applications/ApplicationServiceManifest.java), [Application activation:56](../plowshare-server/src/main/java/io/aeyer/plowshare/server/applications/JdbcApplicationIdentityRepository.java) and [live role resolution:129](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/JdbcProjectMembers.java) | Applications already declare a role, bind an exact project and receive ordinary service-token scopes. Map those role helpers to common actions; no second Application permission engine or wholesale manifest rewrite |
| [ProjectRole:4](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/ProjectRole.java) and [membership mutation:208](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/JdbcProjectMembers.java) | Preserve role-facing helpers, transactional grant changes, audit and last-manager checks while introducing the action mapping |
| [LiveActingAuthorization:28](../plowshare-server/src/main/java/io/aeyer/plowshare/server/acting/LiveActingAuthorization.java) | Reuse actor/subject proof, Application identity, current consent/membership and narrowing ceilings; extend read targets deliberately instead of dropping the proof model |
| [JobRuntime retained admission:215](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/JobRuntime.java) and [JobStore admitted submission:762](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/JobStore.java) | Keep checks around steps, tools, inference and disclosure, plus receipt-before-start ordering; generalize from supported retained turns to common actions |
| [Chat dispatch:172](../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/dispatch/LlmDispatcher.java) and [beforeDispatch:64](../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/dispatch/InferenceObserver.java) | Live callback checks already exist for chat dispatch variants, including after queueing. Supply actual grant authority where requests currently default DIRECT; embeddings need equivalent carriage if required by their action policy |
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

## Suggested implementation order and future proof

These are review dependencies, not authorization to implement:

1. Settle the decision table, typed target/action contracts and identity lifecycle.
2. Map role/Application-role helpers to common account/scope/action grants and
   migrate resource READ/WRITE authorization away from actor-equals-owner checks.
   Keep owner/actor attribution and mandatory input policies.
3. Add CREATE/RUN_MODEL checks to every owning admission and model dispatch path,
   including queued and non-conversational work. Give SYSTEM bounded Global worker
   grants and explicit service-trigger admission before replacing blanket gates.
4. Introduce Global ownership and general-read policy across Java, SQL, retained
   consumers and public contracts together; preserve narrow legacy admissions.
5. Classify database and disk records, review audience expansion, migrate the
   manual separately, and coordinate server/SDK/client cutover.

Focused mocked/fake acceptance coverage should include:

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
- Removing public authorization or withdrawing a required input stops future
  disclosure, including saved views, delayed work, references and message delivery.
- Publishing to a group does not grant GLOBAL_READ. Current root-document
  permissions govern new and historical/pinned revisions; removing an audience
  blocks subsequent old-revision reads. Content-only updates cannot widen access,
  and changing document permissions cannot bypass retained-input restrictions.
- Human ADMIN writes a Global-owned resource through scoped action grants without
  impersonating Global or becoming its owner; Application roles use the same
  evaluator rather than separate authority logic.
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

The three server segments were rechecked against the corrected scoped-grant model,
including a parent review of Application role binding, user-facing Global resume
fences, summarizer home/owner parameters and the queue-time chat callback. Findings
were classified as contract incompatibilities, new capability gaps, policy choices
or existing mechanisms to reuse. The source scans were read-only. This task changes only the
proposal, source map, permission matrix, report and documentation index. Document
structure, local links and diff whitespace are checked before commit. No runtime
or database tests were newly run for these documentation changes.

Earlier in this conversation, the unchanged runtime baseline passed 28 focused
cases across InformationFeatureConfigTest, DocumentNavigationTest,
InformationAccessTest and AdmittedTurnTest. That validates current behavior only;
it does not validate the proposed authority substrate. Test expectations for old
Global recall, promotion and service refusals need intentional migration, not
blanket deletion. No live database, tenant inventory, token issuance or deployment
was inspected or changed.
