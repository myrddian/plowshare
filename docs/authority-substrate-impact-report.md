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
  READ/WRITE checks replace actor-equals-owner authorization, retaining ownership,
  author, actor and source/input policy independently.
- Omitted execution targets resolve to the admitted Application/project itself,
  or a direct human's Personal project. Missing/ambiguous context refuses. No
  ordinary default may choose Global. SYSTEM processing can explicitly select
  the typed Global scope under retained grants.
- Resource ownership, execution destination, authorized read sources, output
  destinations, actor/subject and sponsor are separate facts.
- Deployment overrides and bundles supply SYSTEM worker definitions; project or
  client namesakes cannot select the worker or acquire SYSTEM authority.

## What breaks under a naive cutover

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

### 2. EXECUTION is not expressible as a new enum position

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
CREATE; a first model turn needs RUN_MODEL too. Existing Global resume refusal
must be replaced with scoped action evaluation for authorized SYSTEM work,
while preserving refusal for ordinary readers and protecting private logs.

### 3. Application-relative default routing does not exist yet

[Asking.java:15](../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/Asking.java)
deliberately carries no implicit project.
[FrameRouter.java:193](../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/FrameRouter.java)
checks central authorization before handler home resolution.
[ProjectAuthorization.java:363](../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/ProjectAuthorization.java)
refuses ordinary service operations lacking an explicit/resource-derived project.
[PersonalSpaces.java:85](../plowshare-server/src/main/java/io/aeyer/plowshare/server/personal/PersonalSpaces.java)
defaults humans to Personal but refuses service Personal provisioning at line 103.

The human default can stay. Applications need a trusted admitted execution-project
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
SYSTEM can receive scoped EXECUTION and required READ/WRITE grants there; there
is no mandatory move to a new operational scope. They still need explicit typed
targets, executor and retained action authority. Personal documents also use
these null paths, so their execution/log location does not prove Global knowledge
ownership. Preserve distinct input owner, execution scope, initiating actor,
sponsor, output grants and private log policy.

An authorized human upload can trigger a declared SYSTEM service under its own
worker grants. Requiring the human's RUN_MODEL grant for every internal service
would break that workflow; silently lending SYSTEM to an arbitrary denied run
would defeat authorization. Specify the service trigger, inputs/outputs and
spending contract without changing ordinary child-delegation ceilings.

### 5. RUN_MODEL needs a lower enforcement seam than agent admission

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

### 8. Shared conversion and Global ownership transfer are different migrations

[JdbcInformationCatalogueRepository.java:830](../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/JdbcInformationCatalogueRepository.java)
changes a revision's Shared visibility and policy project_id while retaining the
resource owner/home; all consumed inputs must already satisfy Shared reads.
This can inform GLOBAL_READ authorization, but does not authorize an ownership
transfer. Admitting previously excluded Application/service readers also expands
the old audience and needs an explicit migration policy.

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
| Read grants on revisions versus resources | Existing Shared is per revision; decide inheritance to future revisions, drafts, grant removal and author-controlled general-read issuance |
| Meaning of RUN_MODEL | Cover chat, embeddings, reranking, model validators, preparation and side calls; decide whether bounded read-service inference uses requester grants or an explicit service sponsor |
| Non-model execution | CREATE plus RUN_MODEL is the adopted bundle, but does not by itself classify tool-only jobs, scripts, CPU stages, messaging or schedule effects |
| Independent service admission versus delegated children | A permitted WRITE/read trigger may authorize a declared SYSTEM workflow under its own scoped grant; do not require the caller to hold worker execution, lend it to the caller, or auto-escalate arbitrary denied work |
| Processing/log scope and service sponsorship | SYSTEM can process within Global through scoped grants; define authorized triggers, exact inputs/outputs, sponsor and private logs without lending execution to callers |
| Internal conversation-shaped logs | Decide how CREATE applies to worker/hook/audit logs versus user-facing conversations without granting execution merely because a log must be recorded |
| Grant narrowing and queued work | Revalidate live authority within retained ceilings at enqueue/dispatch/result disclosure/recovery; decide grant-version and expiry semantics without replaying effects |
| Global write control | Replace exact-owner authorization with common READ/WRITE/control grants; specify transfer/release actions, audit and Java/SQL migration |
| Default public role versus individual exceptions | Every authenticated principal holds GLOBAL_READ; specify resource-level withdrawal/mandatory constraints and support for intentionally excluding a principal without confusing role membership with resource authorization |
| Tenant isolation | Deployment-wide general reading crosses potential tenant boundaries; define first-class tenant membership and which resources may authorize that audience |
| Search aggregation | Decide selected read set, project-plus-public convenience, paging/ranking/coverage, duplicate revisions and reference-only hits before changing current Personal-plus-Shared defaults |
| Global schema/type registry | Current type management is Personal/Project-scoped; decide where Global-owned typed documents get immutable schemas and how attached access/import behaves |
| Curation outcome | Choose general-read grant, copy or ownership transfer; define supersession/ID consequences and human approval separately from model assessment |
| Administration through SDK | Human ADMIN remains the write authority. Clarify whether CLI/TUI/GUI are supported management surfaces or a security allowlist: a shared SDK package/client label cannot prove first-party identity |
| Compatibility and rollback | Define mixed-version refusal, coordinated generated contracts, retained-work migration and a rollback that can still enforce new grants/reference records |

No existing token, Application manifest or client label is assumed to grant the
new actions automatically. Public read does not make consumed private inputs
public; retained dependencies and reference obligations remain mandatory.

## Preserve the useful boundaries already present

- Resource-derived scopes are checked in addition to request selection. A payload
  cannot rehome a conversation, memory or job by naming another project.
- Personal privacy, live service-token owner membership and acting proof checks
  remain independent of broad public reading. Resource READ/WRITE policy may change
  from owner-equality to scoped grants without weakening these separate fences.
- Information predicates apply before totals, ranking, paging, metadata and export.
  Withdrawal and input/reference obligations constrain current and saved outputs.
- Provider tools retain explicit owner/project/agent/credential assignments, Relay
  receipts and uncertainty handling. GLOBAL_READ grants no provider capability.
- Retained execution never widens because an account later receives another grant;
  historical receipts, accounting and uncertain external effects keep their IDs.
- Metadata-only revisions keep their model-free save/reuse contract. A new read or
  execution vocabulary must not schedule paid work merely to save metadata.

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
- Human, Application/service and supported retained-acting reads work through list,
  direct revision, schema attachment, relationships, jobs and export; GLOBAL_READ
  supplies no mutation or execution authority and no private-resource disclosure.
- Removing public authorization or withdrawing a required input stops future
  disclosure, including saved views, delayed work, references and message delivery.
- Human ADMIN writes a Global-owned resource through scoped action grants without
  impersonating Global or becoming its owner; Application roles use the same
  evaluator rather than separate authority logic.
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

The source scans were read-only. This task changes only the
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
