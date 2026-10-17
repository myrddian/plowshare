# Global scope migration map

Status: architecture proposal and source map, 17 October 2026. No implementation.
Read with [ADR 0030](decisions/0030-authority-substrate-and-explicit-scopes.md).
The [role and scope permission matrices](role-scope-permissions-proposal.md)
separate current operation gates from proposed action bundles and scope overlays.
The [segmented impact report](authority-substrate-impact-report.md) assesses
compatibility, assumptions, missing decisions and scoped execution grants.
The [account-delegation action mapping](decisions/0030-authority-substrate-and-explicit-scopes.md#account-delegation-application-acting-for-a-user)
preserves the existing Application/subject proof flow while translating coarse
acting READ/WORK ceilings into supported scoped actions; it is distinct from
child-agent delegation and does not enable unsupported acting APIs.

Source baseline: `efddb12a0cfd28c6aa1464f01b884d15597102d5`, merged main
containing account-delegation PR #73. Source links below identify current owners
and symbols at that revision; target behavior is proposed. Main through
`269854a7c5bcc5f7080bc04de01ea8ccce46eaa7` is now merged into this branch;
PR #74 enables existing information feature defaults and PR #75 fixes admitted-turn
test completion. Neither changes the audited Global/Shared ownership or selection
contracts below.

## Existing authority foundations

| Area | Current source and behavior | Proposed treatment |
| --- | --- | --- |
| Account identity and administration | [AdminStore](../plowshare-server/src/main/java/io/aeyer/plowshare/server/auth/AdminStore.java) uses account handles and `server_admin`; [V111](../plowshare-server/src/main/resources/db/migration/V111__service_accounts.sql) admits USER, SERVICE and SERVICE_TOKEN account kinds | Add a real locked SYSTEM principal and explicit root/admin grants. Today's SYSTEM accounting status is not that account |
| Project RBAC | [ProjectRole](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/ProjectRole.java) has ordered VIEWER, CONTRIBUTOR and MANAGER; [ProjectMembers](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/ProjectMembers.java) owns live memberships and grant history | Reuse scoped membership contracts; define action bundles and grant provenance. Human ADMIN remains possible; agent MANAGER is descoped to CONTRIBUTOR unless explicitly forced by an authorized human, and agent ADMIN is rejected. SYSTEM is never assignable |
| Administrative project access | [JdbcProjectMembers.role](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/JdbcProjectMembers.java) grants server admins MANAGER for ordinary non-client/non-Personal projects; [ProjectAuthorization](../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/ProjectAuthorization.java) has operation-specific admin rules | Replace ambient privileges with reviewed explicit administrative scope/action grants; preserve necessary operations without silently expanding data access |
| Personal isolation | [PersonalSpaces](../plowshare-server/src/main/java/io/aeyer/plowshare/server/personal/PersonalSpaces.java) maps an account to a private project/union, enforces exact ownership and supplies human runnable defaults | Preserve private control and explicit scope. Do not grant an Application Personal membership to enable acting work |
| Tenant isolation | Relevant current account, project, Personal and information contracts use account/project identities rather than a first-class tenant identity | Specify tenant entities and mappings separately; do not declare each existing project a tenant by assumption |
| Delegated identity | [RequestAuthority](../plowshare-server/src/main/java/io/aeyer/plowshare/server/acting/RequestAuthority.java) separates actor and subject; [LiveActingAuthorization](../plowshare-server/src/main/java/io/aeyer/plowshare/server/acting/LiveActingAuthorization.java) intersects current roles, grants, consent and admitted ceilings | Preserve this intersection as an existing specialized policy. General grant provenance must not weaken its actor/subject restrictions |
| Retained execution | [RunAdmission](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/RunAdmission.java), [RunAuthority](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/RunAuthority.java), [PersonalRunAuthority](../plowshare-server/src/main/java/io/aeyer/plowshare/server/acting/PersonalRunAuthority.java) and [JobRuntime.forAdmittedTurn](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/JobRuntime.java) retain bounded authority | Generalize coverage through existing consumers, with durable grant references. These contracts do not yet implement a universal grant substrate |
| Acting capability limits | [RetainedAgent](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/RetainedAgent.java) and [RetainedToolAccess](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/RetainedToolAccess.java) refuse unsupported consumers and extract exact resource targets | Preserve refusals until each independent consumer supports retained authority; no account-only downgrade |
| Trusted definition classification | [DefinitionSource.Provenance](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/DefinitionSource.java), [AgentRegistry.parseWith](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/AgentRegistry.java) and [AgentExposure](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/AgentExposure.java) enforce bundled-only `is-system` and Application/acting exposure | Already implemented. Preserve provenance and aliases; classification is distinct from the proposed SYSTEM account and role |
| Explicit server channel scope | [Relay.Scope](../plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/Relay.java) distinguishes project scope from `SystemScope.SERVER`; [ScopedRelayInspection](../plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/ScopedRelayInspection.java) requires admin authority for server inspection | Reuse the explicit distinction; later map it to durable scope identity without fabricating a project or weakening topic policy |
| Resource policy | [InformationAccess](../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationAccess.java), [JdbcInformationCatalogueRepository](../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/JdbcInformationCatalogueRepository.java) and [ADR 0028](decisions/0028-document-navigation-and-reference-policy.md) enforce readability, derived-input and reference policies | Keep content access, reference disclosure, propagation and publication separate. These policies are not yet general resource ACLs |
| Accounting | [UsageAttribution.system](../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/accounting/UsageAttribution.java) uses `Status.SYSTEM`, a null account handle and an optional project | Preserve historical records. Future real executor identity, sponsoring account and usage attribution must be mapped independently |

## Verified Global paths and their replacements

| Path | Current behavior | Target responsibility |
| --- | --- | --- |
| Null scope representation | [Home.global](../plowshare-protocol/src/main/java/io/aeyer/plowshare/protocol/Home.java) is `Home(null)`; [ProjectIds](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/ProjectIds.java) maps Global to null database project IDs | Explicit scope UUIDs, distinct from account and role identities; unknown project continues to be distinct from server/shared scope |
| Omitted request scope | [RequestedHome.in](../plowshare-server/src/main/java/io/aeyer/plowshare/server/requests/RequestedHome.java) maps null to Global; [PersonalSpaces.home](../plowshare-server/src/main/java/io/aeyer/plowshare/server/personal/PersonalSpaces.java) supplies Personal for human runnable defaults | One documented selection contract per operation family, resolved before effects. Omission never selects server scope |
| Project memory recall and survey | Both `Archive.search` paths append Global hits and missing-embedding counts; recall also updates usage counters on returned memories in [Archive](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/Archive.java) | Query only the admitted scope and explicitly authorized published selections; preserve bounded result counts and usage accounting |
| Ordinary Application recall | [ProjectAuthorization.require](../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/ProjectAuthorization.java) refuses explicit Global service-token work; [MemoryRecallHandler](../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/MemoryRecallHandler.java) calls the archive, which adds Global internally | Apply scope policy to actual internal query targets. Retained acting currently does not support the `memory_recall` tool, so this finding is about the ordinary service-token path |
| Digest navigation | [Navigator.walk](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/digests/Navigator.java) explicitly tries the requested home then Global | No cross-scope retry; include other corpora only through an authorized explicit selection |
| Curation publication | [Passes.start](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/curator/Passes.java) requires a project; [Curator.promote](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/curator/Curator.java) approves confident rulings; [PromotionQueue.approve](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/PromotionQueue.java) calls `Archive.promote`, which creates a Global memory and supersedes its origin | Preserve analysis/proposals, but require authorized source release and human ADMIN publication into the explicit Global memory collection. Personal has no exclusion in the current promotion path |
| Curation trigger | Production callers of `Passes.start` are [AgentController](../plowshare-server/src/main/java/io/aeyer/plowshare/server/api/AgentController.java) and [AgentCurateHandler](../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/AgentCurateHandler.java); no automatic nightly registration was found | Treat the issue as publication authority exercised by an explicit request, not automatic leakage after every write |
| Digest job ownership | [Digests.start](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/digests/Digests.java) retains the requested home inside its closure but uses `JobStore.submit(name, work)`, which assigns Global | Persist the actual owning scope and admission. Normal successful digest outcomes contain counts/status; scope still governs visibility and cancellation |
| Definition failure recovery | [DefinitionResolver.forCaller/readProject](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/DefinitionResolver.java) returns the boot set for missing projects and ordinary merge failures; [OrchestrationResolver.resolve](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/OrchestrationResolver.java) can recover to inherited definitions | Resolve intended identity first; refuse unknown/unavailable authoritative tiers. Preserve the existing Application definition merge refusal |
| Lenient project lookup | [RequestedProjectId.lenient/forListing](../plowshare-server/src/main/java/io/aeyer/plowshare/server/requests/RequestedProjectId.java) returns null for unresolved lookups; listing also absorbs archive outages | Typed absence/failure results cannot select another scope or registry. Note that resolvers may first map null IDs to caller Personal, rather than immediately returning boot definitions |
| Automatic definition/default inheritance | `DefinitionResolver.build` starts with inherited definitions; `defaultBot` searches nearer layers then Global/shipped; orchestration resolution also inherits | Explicit installed-catalogue availability, default bindings and imports, separate from worker registry lookup |
| Skill and prompt inheritance | [SkillResolver.forCaller](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/SkillResolver.java) includes Global/shipped; [AgentRules.forPath](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/AgentRules.java) appends Global instructions | Explicit instruction/import policies. Preserve skill grants and the existing refusal to fall through unreadable skill authority tiers |
| Shared information discovery | [InformationContext.Selection](../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationContext.java) defaults human Personal/project selections to `includeShared=true`; service selections must exclude it | Explicit audience and corpus selection. Preserve existing authenticated readability before pagination; shared inclusion is not permission to read unshared Personal content |
| Outgoing work | [OutgoingFrames](../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/OutgoingFrames.java) passes omitted project through; [JdbcOutgoingRepository](../plowshare-server/src/main/java/io/aeyer/plowshare/server/outgoing/JdbcOutgoingRepository.java) stores null project and uses project key zero for account-owned peer registration | Explicit owning scope, retaining account-bound reads/claims and original request UUIDs. Current null scope does not demonstrate cross-account access |
| Public selection contracts | [CLI options](../plowshare-cli/src/options.ts), [SDK discovery](../sdk/typescript/src/operations/discovery.ts), [MCP tools](../plowshare-mcp/src/tools.json), [desktop scheduling](../plowshare-desktop/src/renderer/schedule-controls.ts) and [operator controls](../plowshare-desktop/src/renderer/operator.ts) encode Global | Update typed operations, generated catalogues, help and UI together; remove contradictory omission semantics |

### Current omitted scope behavior

These are production human defaults at the baseline, not one consistent Global
selection contract. Service-token authorization separately requires permitted
project/resource scope and refuses Global work.

| Operation family | Current omission | Proposed selection rule |
| --- | --- | --- |
| Conversation open | Personal through production `PersonalSpaces` wiring | Omission routes to the admitted Application/project itself, otherwise to a direct human caller's Personal project; missing/ambiguous context refuses, never Global |
| Ordinary agent run, direct orchestration start | Personal through production `PersonalSpaces` wiring | Resolve the admitted context/default, then check scoped execution grants; ordinary omission does not select Global. SYSTEM processing selects its scope explicitly |
| Conversation list/latest/search | Global through `RequestedHome` | Use the documented authorized selection, never an omitted server/shared scope |
| Memory index/recall/write, proposal listing, digest/navigation | Global through `RequestedHome` | Resolve a permitted explicit work/corpus scope; do not append shared data automatically |
| Information/document selection | Personal plus shared for human defaults | Unscoped search selects all readable knowledge within admitted ceilings; project selection narrows the read set, independently of ownership |
| Outgoing send/advertise/claim | Account-bound null project | Resolve an explicit owning scope and retain account ownership |
| Retained acting turn | Explicit acting context and subject Personal authority | Preserve explicit actor/subject admission and supported capability limits |

The target separates `GLOBAL_ACCOUNT` ownership from the `GLOBAL_READ` role.
For ordinary knowledge, account ownership here means administrative home/scope;
`created_by` separately records original creation provenance and grants no
permissions. Do not blindly rename legacy owner columns: reconcile their current
namespace/control meaning, scoped grants and available creation evidence. See
[created-by provenance and scoped control](decisions/0030-authority-substrate-and-explicit-scopes.md#created-by-provenance-and-scoped-control).
Global is an ownership account, not a project. Its explicit fixed-identity account
knowledge scope admits memories, documents and derived retrieval/index views. Its
resources authorize `GLOBAL_READ`; human ADMIN manages them. Account and read-role
UUIDs must also be reserved separately before implementation. Bootstrap never
regenerates any of these identities.

Every authenticated human, Application and service principal holds `GLOBAL_READ`.
This audience is deployment-wide, including across project/Application and future
tenant boundaries. Scoped permissions on private resources remain independent;
source-release and retained input/reference policy must prevent private material
from leaking through general-read content or derived views. See the
[deployment-wide audience decision](decisions/0030-authority-substrate-and-explicit-scopes.md#deployment-wide-audience-and-isolation).
A resource authorizes that role to grant general reading. A resource owned elsewhere
can do so without entering `GLOBAL_ACCOUNT` or transferring control. SDK readers
are eligible through the same public read contracts as CLI/TUI/GUI readers; the
former blanket external exclusion is superseded. Administrative Global mutations
remain human ADMIN actions through existing supporting APIs, independently of
client, with exact scope/source authority. This does not assert SDK/client parity
or require new APIs/methods; unverified/missing SDK management coverage remains a
capability gap. See [account authorization and interface coverage](decisions/0030-authority-substrate-and-explicit-scopes.md#account-authorization-and-client-coverage).
Tool bindings, executable definitions, conversations and agent-run homes do not
become part of Global knowledge.

Publication is independent of audience: publishing to a group/project does not
grant GLOBAL_READ. Document revisions inherit the logical root document's current
permissions, represented by its most current revision; old/pinned revisions do
not preserve revoked historical access. Reconcile existing per-revision Shared
policies explicitly into this root policy without unioning old audiences. Keep
content WRITE separate from permission changes and retain lifecycle/input checks.
See the [root-document permission decision](decisions/0030-authority-substrate-and-explicit-scopes.md#publication-audience-and-root-document-permissions).

A librarian Application can retain an admitted read selection spanning its own
project and general-read knowledge while keeping writes confined to its project.
The general read role never overrides narrower token/acting ceilings, mandatory
policy or retained-input obligations. Unsupported consumers must gain an explicit
read-target contract before use, not an account-only authorization fallback.
Current service-token Personal/Shared refusals need a deliberate public-read
contract migration. Account knowledge targets need typed contracts distinct from
project targets; replacing null with an account UUID in a project parameter is
invalid.

Unscoped Library search is proposed to search all readable information within
admitted authority; scoped search narrows that selection. Current omitted
information selection is still Personal plus Shared, not all readable projects.
Search scope, owner/home, read-role authorization and execution authority remain
separate. No empty-search fallback silently adds another corpus.

Built-in tools available across projects remain in the installed platform catalogue, not
inside the Global account knowledge scope. [JobRuntime.knownTools/offeredTo](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/JobRuntime.java)
separates registered names from offered capabilities; [ScopedTools](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/ScopedTools.java)
and [ApplicationToolRegistry](../plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/tools/ApplicationToolRegistry.java)
add live project/provider tools under their own grants. Reuse these owners for
explicit availability and invocation authority rather than creating a Global-tool
fallback or a duplicate provider runtime.

For MCP and client-provided tools specifically, the provider-scope mechanism is
already the owning capability. `SessionToolScopes` handles interactive bindings;
`OwnedHostedToolScopes` handles durable hosted assignments, combined through
`CombinedToolScopeConnections`. Their concrete project, owner, agent and provider
bindings are retained. Broadly reusable configuration does not imply a current
project-less wildcard grant. The Global migration must preserve this mechanism,
not rehome external tools or their credentials into SYSTEM or the Global account.

The precise list/selection defaults require review before implementation. A read
may offer an explicit authorized aggregate selection without adopting the same
default as a mutation. The common invariant is that omission cannot select server
authority, published content or a fallback registry.

## Preserve the boundaries already working

Global conversation resume is refused by
[Callers.requireConversationProject](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/Callers.java)
and [ConversationResumeHandler](../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/ConversationResumeHandler.java).
Current source still supports legacy null-project conversation reads. This is not
evidence that any such rows exist in a running deployment. Public Global resume
refusal can remain: internal SYSTEM summarization does not use that public route.
Any future controlled continuation API needs its own scoped action/exposure
contract. Ordinary readers have no Global execution grant; admitted SYSTEM workers
can process there. Private worker logs are not generally readable knowledge.
Historical rows require classification rather than treating every null as public.
Omitted/null
conversation targets use admitted Application/project context, or Personal for a
direct human without project context. Resolve the effective project before effects;
missing/ambiguous context refuses instead of choosing Global. Preserve the existing
human Personal convenience. `GLOBAL_ACCOUNT` is not a conversation project;
account provisioning must not create a Personal conversation/workspace for this
knowledge ownership identity.
Board opening, messaging and commands require project context. A Global local
filesystem is refused; the legacy remote-provider path checks the submitting
owner's session in [AgentsConfig](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/AgentsConfig.java).
The proposal does not create a server-wide agent filesystem.

[FileScheduleDefinitions](../plowshare-server/src/main/java/io/aeyer/plowshare/server/events/FileScheduleDefinitions.java)
requires administration for Global schedule sources and refuses unknown named
projects. [WorkRelayReceiver](../plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/WorkRelayReceiver.java)
refuses unavailable destinations. Keep these failures explicit while replacing
their underlying scope representation.

[ReloadingGlobalAgents](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/ReloadingGlobalAgents.java)
and `GlobalAgentDefinitions` provide a server registry independent of caller
project overrides. Scribe, Curator, Learner and document workers still need trusted
lookup by internal worker purpose. Preserve worker execution while separating it from
user catalogue availability and published data.

Call the operator configuration layer **Deployment**, independently of Global
memory. [AgentsConfig.agentRegistry](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/AgentsConfig.java)
currently layers deployment files from `global/agents/` and `global/bots/` over
bundled resources; `ReloadingGlobalAgents` refreshes those same sources. Internal
Scribe/Curator/Learner and compaction wiring consumes these snapshots rather than
the caller's project registry. This supports the desired resolution boundary,
but is not evidence that the proposed SYSTEM account/admission model exists.

The proposed worker-purpose contract restricts internal definition discovery to
authorized deployment overrides followed by bundled defaults. Project, Personal,
client-session, Application and Global-knowledge sources cannot supply a SYSTEM
worker. An invalid authoritative override refuses; only absence permits the
bundled default. A deployment summarizer override remains deployment-authored
while its admitted internal task still runs under SYSTEM, with unchanged scope
and input ceilings. An ordinary deployment bot instead runs under its admitted
user's account. Preserve the distinction between trusted worker binding,
definition provenance and execution identity; do not implement the binding as
an authored name or `is-system` flag that grants root execution.

The audit's hardcoded `daedalus` assertion is obsolete at this baseline.
[AgentExposure.operatorOnly](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/AgentExposure.java)
uses `definition.isSystem()`. The bundled diagnostic is now the private
`conversation_diagnostician`, reached through the `diagnose-conversation` skill;
an authored namesake does not acquire its classification. Do not schedule an
already completed provenance change as migration work.

## Legacy classification and durable identity

Replacing every null project with the SYSTEM scope would put user history,
published knowledge and administration into one new container. Classify by owning
record and retained provenance instead:

| Existing record | Migration rule |
| --- | --- |
| Project and Personal resources | Map to the continuing explicit scope; preserve resource/revision identities, membership and history |
| Proven server configuration and internal channel state | Map to server-administration scope under explicit SYSTEM control |
| Proven administrator-managed memories | Classify for `GLOBAL_ACCOUNT` / Global knowledge with `GLOBAL_READ`; preserve provenance and require human ADMIN plus source authority for transfers |
| Proven deliberately shared documents | Review `GLOBAL_READ` authorization without changing owner/home; admitting previously excluded SDK/service readers is an explicit audience expansion. An intended Global-owned manual also needs an authorized ownership/home migration |
| Null-project conversations and jobs, if present | Preserve their IDs and content with restricted legacy scope; recover a narrower owning scope only from authoritative records; never admit them into the Global knowledge collection |
| Misclassified digest jobs | Use durable original project evidence where available; do not infer ownership from a job name or an in-memory closure that no longer exists |
| Null-project outgoing rows | Preserve account ownership and original receipt/request identities; select an explicit migrated scope without resending work |
| Historical model usage | Retain original attribution and billing facts. Add linkage/mapping where justified, never relabel historical SYSTEM or unattributed usage as a new account |
| Uncertain origin, audience or tenant | Restrict access pending operator classification; record the decision rather than guessing or inheriting a broader audience |

An explicit legacy publication audience is not a permanent Global fallback.
It records prior intended sharing for review and explicit selection. New policy
must never widen the established audience merely to complete a migration.

Use new Flyway migrations, classification reports, backups and scoped migration
receipts. Rehearse against a restored copy. A completed authority cutover must
reject active rows/work with missing scope or unresolved grant provenance;
quarantined historical records remain explicitly marked and unavailable to normal
execution. Coordinate readers/writers so a mixed-version deployment cannot
interpret the same null as both unrestricted legacy work and a refused scope.

## Proposed implementation sequence

These slices describe dependencies and review boundaries, not authorization to
implement them. Early scope/control repairs can be separately authorized while
the complete substrate remains under design.

1. **Settle the authority contract.** Approve tenant/Personal boundaries, the
   root identity registry, role action sets, grant delegation, publication audiences
   and admission invariants in ADR 0030. Record changes to ADRs 0025/0026/0028
   explicitly rather than silently superseding them.
2. **Bound the immediate exposure paths.** Give digest jobs their real scope;
   require human ADMIN for Global-owned knowledge management; keep curation assessment
   separate from that decision; stop hidden Global
   query/navigation expansion and refuse failed authoritative definition tiers.
   Keep internal workers functioning. Each behavioral change needs a reviewed
   public selection/default contract before client cutover.
3. **Introduce explicit identities and root grants.** Add durable scope mappings,
   the locked SYSTEM account/role, distinct `GLOBAL_ACCOUNT` and `GLOBAL_READ`
   identities, administrative grants and auditable grant provenance. Enforce
   agent role ceilings and record any explicit forced MANAGER
   exception. Preserve existing IDs and last-manager/recovery invariants.
4. **Carry bounded execution authority through consumers.** Extend the existing
   retained-authority model across ordinary jobs, schedules, orchestration,
   messaging, Relay, approvals and internal workers. Migrate a consumer only when
   its live checks, durable ceiling, output visibility and recovery are complete.
5. **Separate catalogues and publication.** Make user definition availability,
   defaults, instruction imports, resource ACL grants and published audiences
   explicit. Keep the internal worker registry independent of project overrides.
6. **Classify legacy rows and complete contract cutover.** Backfill proven scope,
   preserve restricted unknowns, remove null-as-Global paths and update every
   affected SDK, generated catalogue, client selector and manual. Enable database
   constraints only after the classification preflight succeeds.

## Acceptance criteria for future implementation

Default mocked/fake tests should cover:

- `EXECUTION` includes `CONVERSATION_CREATE` and `RUN_MODEL`; each action still
  requires a retained scoped principal grant and applicable workload validation. An empty
  conversation need not invoke or charge a model. Later turns, children and async
  model work recheck authority rather than reusing only conversation admission.
- Non-model work checks its actual resource/control actions with no EXECUTE_JOB
  permission. Scripts, tool-only/CPU jobs, scheduled maintenance and Relay-triggered
  processing need applicable READ/WRITE/control authority and existing capabilities,
  not an unconditional EXECUTION gate. CONVERSATION_CREATE and RUN_MODEL apply when
  those actions occur. Preserve executor identity, operation sandbox, configured
  budgets, lifecycle/receipts and tool/file/provider/Relay restrictions. See
  [non-model action checks](decisions/0030-authority-substrate-and-explicit-scopes.md#non-model-work-and-action-based-authorization).
- Existing operation controls map to scoped WRITE plus their owning lifecycle/
  eligibility rules, with no CONTROL_EXECUTION or created_by privilege. Preserve
  job.cancel as a stop request and schedule.pause as schedule pause/resume. Do not
  infer generic running-job pause/resume, universal cancellation, operation ACLs
  or new SDK controls from the permission mapping; absent capabilities remain
  absent. See [existing control boundaries](decisions/0030-authority-substrate-and-explicit-scopes.md#existing-operation-controls-and-write).
- RUN_MODEL covers all inference, including embeddings, reranking, model-based
  validators and hook/preparation side calls. Declared SYSTEM services use their
  existing authority bounded by the operation; callers need permission for the
  requested operation, not its internal inference. Authorized READER semantic
  search succeeds through bounded service inference without allowing arbitrary
  model work or widening targets/tools. Queue/dispatch paths enforce the actual
  executor and operation limits. The operation pays through its configured budget,
  independently of SYSTEM execution and creator provenance.
- Conversation compaction uses its conversation budget; project/Application
  preparation uses its scope processing budget; Global/maintenance uses configured
  deployment budgets; read-side inference uses its search-operation budget. Retain
  the admitted budget binding and usage through queues/recovery without silent
  payer/pool changes. Missing/exhausted budgets prevent further inference while
  receipts and incurred costs remain preserved. No historical attribution is
  relabeled. See [operation budget policy](decisions/0030-authority-substrate-and-explicit-scopes.md#operation-specific-budgets-and-accounting).
- Global SYSTEM processing succeeds under explicit scoped READ/WRITE/EXECUTION
  grants and bounded task admission. GLOBAL_READ holders cannot create/run/write;
  human Global ADMIN READ/WRITE/control supplies no implicit execution. No name/UUID
  allow branch or owner-equality check substitutes for the action evaluator.
- Authorized user knowledge writes can trigger a separately admitted SYSTEM worker
  with declared inputs/outputs and spending limits. The user does not receive
  SYSTEM/RUN_MODEL authority, and denied arbitrary execution is not auto-escalated.
- A SYSTEM memory worker admitted to one memory area cannot write another project,
  Global knowledge or policy/grant records outside its operation sandbox, or enlarge
  that sandbox. Audit retains SYSTEM as executor with its existing root authority;
  sandbox enforcement refuses those effects without fresh per-task SYSTEM grants.
- A compaction summariser has existing SYSTEM conversation READ/summary WRITE,
  but can reach only supplied inputs and bound tool targets. Attempts to supply
  another conversation/output identifier are refused by the capability binding;
  no per-compaction account grant or new tool RBAC is required.
- Admitted SYSTEM processing continues across creator/initiator access removal
  while the document and operation remain valid, including queue/recovery paths.
  Document withdrawal, invalid generation or cancelled operation fences affected
  commits; current recipient access still governs results/logs/notifications.
  Replace legacy owner-access processing gates without weakening those lifecycle
  fences or ordinary delegated-user revocation checks. See [continuation policy](decisions/0030-authority-substrate-and-explicit-scopes.md#system-processing-validity-and-creator-access-changes).
- Application and human role helpers resolve to common scoped grants. ADMIN writes
  Global knowledge through WRITE/control authority while retaining GLOBAL_ACCOUNT
  ownership and the initiating human's audit identity.
- SYSTEM cannot be assigned through any role/grant, agent configuration, inheritance,
  delegation, execution profile or operator/recovery path. Authorized scoped human
  ADMIN grants remain usable; agents cannot receive ADMIN. Trusted agent
  classification never confers the SYSTEM role.
- Agent READER/CONTRIBUTOR grants retain the requested level; unforced MANAGER
  descopes to CONTRIBUTOR and reports the effective grant. Forced MANAGER requires
  an explicit authorized human decision for that agent and scope. Force cannot
  override another ceiling, and ADMIN/SYSTEM reject without partial grant effects.
- ACL actions, tools, SYSTEM ownership/classification, human ADMIN sponsorship and
  child-agent delegation cannot bypass the agent ceiling. MANAGER exceptions do
  not automatically propagate to children and remain subject to live revocation.
- SYSTEM execution inside one admitted scope, refusing unrelated resources and
  publication even when the root role permits the action in principle.
- SYSTEM worker lookup uses only deployment and bundled sources. Valid deployment
  summarizer overrides retain the admitted SYSTEM executor and task limits;
  project/Personal/client/Application namesakes cannot replace the worker or gain
  its execution identity. Invalid authoritative overrides refuse. Ordinary
  deployment bots execute under the user's account; authored overrides retain
  their provenance without acquiring bundled-only classification.
- ADMIN management without implicit Personal/content access; role bundles versus
  resource ACL grants; delegation allowed only within the issuing authority.
- Expired/revoked parents, grant cycles, unknown provenance and attempted widening
  of scope, action, time, recipient or delegation rights.
- Same-project/different-account, different-project/same-tenant, cross-tenant,
  Personal, service-token and acting-context matrices.
- Omitted, null, blank, unknown, deleted, offline and malformed scope selections;
  invalid overrides must not recover to SYSTEM, legacy shared or shipped content.
- No hidden expansion in recall, survey, navigation, prompts, information selection,
  counts, events or pagination; authorized explicit imports still work.
- Scope and authority retained through queues, children, cancellation, approvals,
  restarts and receipt reads; uncertain effects are never replayed.
- Curation analysis remains usable while unauthorized publication refuses; derived
  output policies and source/destination audiences are checked independently.
- Global admits memories, documents and derived retrieval views, with human ADMIN
  management under `GLOBAL_ACCOUNT`. SYSTEM worker processing/logs use explicit
  scope/action grants and remain separately private. Historical null-project rows retain identity and restricted access
  until classified, without guessed ownership.
- `GLOBAL_READ` is held by authenticated humans and Application/service principals,
  but only resources authorizing that role become generally readable. Authorizing
  it on a user/project resource retains ownership/home and requires an authorized
  audience change; it grants no write or administrative action.
- The resource MANAGER/DRI may choose group/project/general-read audiences and
  grant/remove GLOBAL_READ without an extra expansion grant or ADMIN approval.
  CONTRIBUTOR has no audience-management authority. Home/created_by and private
  dependency restrictions remain intact; this does not authorize Global-account
  management or unrelated-resource changes. See [manager audience authority](decisions/0030-authority-substrate-and-explicit-scopes.md#resource-manager-responsibility-for-audience-changes).
- GLOBAL_READ permits the deployment-wide authenticated audience with no
  individual exclusions. A restricted audience removes GLOBAL_READ and uses
  project/group permissions; it does not add per-account denies or remove the
  universal role selectively. Authentication/token validity, operation ceilings
  and mandatory restrictions remain effective. No deny/blocklist or new group
  capability is introduced. See [GLOBAL_READ audience semantics](decisions/0030-authority-substrate-and-explicit-scopes.md#global_read-has-no-individual-exclusions).
- `GLOBAL_ACCOUNT`, its account knowledge scope and `GLOBAL_READ` have distinct
  fixed registry UUIDs on every startup/installation. No Global project is created;
  existing ordinary projects named global retain their ordinary identities and
  workload rules. Missing built-in records never create replacement identities.
- Omitted/null conversation targets route an admitted Application/project request
  to itself, or a direct human request without project context to its own Personal.
  Effective home is explicit before persistence/admission. Missing/ambiguous
  context refuses; no first-project guess or Global fallback. Retained acting
  targets and ceilings do not change when resolving defaults.
- Explicit blank, unknown, unavailable or account-valued conversation project
  references refuse without defaulting. Supported authorized project targets work.
  GLOBAL_READ supplies no execution; an account UUID in a project-only field is
  invalid. Trusted SYSTEM processing selects the typed Global scope explicitly.
- Account-owned memory/document read targets remain distinct from conversation
  project targets across server, SDK and clients; Global reads require no fake
  project, null sentinel, account impersonation or implicit execution home.
- A platform tool available in multiple projects still requires an agent/workflow
  grant and target/action authority. Its catalogue entry creates no knowledge
  read permission and no cross-project provider/credential authority.
- SDK/Application librarians can discover/read Global and other `GLOBAL_READ`
  resources through supported public read operations, within retained read targets,
  token/acting ceilings, lifecycle and input/reference constraints. Private resources
  remain unavailable, including through IDs, counts, facets and navigation.
- A librarian with project write authority and project-plus-general-read authority
  cannot mutate Global, obtain ADMIN, bind tools there or change its executor.
  Acting for a human and forced MANAGER do not add Global write authority.
- Global management through an existing supporting API requires human ADMIN over the target plus source
  release where applicable. Agents can propose; a bounded executor may carry out
  an admitted administrative effect. Read bookkeeping supplies no content or
  audience mutation authority.
- Account/scoped role and policy determine authorization independently of client.
  No client-name/SDK-package allowlist, new API/SDK method or feature-parity
  requirement is inferred. SDK management coverage stays unverified unless checked.
- Unscoped search enumerates only admitted readable information; project-scoped
  search cannot broaden on an empty result. Omitted mutation/execution scope never
  becomes Global, and a read grant never changes resource ownership.
- Legacy Shared mapping preserves owner, revision IDs and retained dependencies;
  expansion to previously excluded Application/service readers requires an
  authorized audience decision, not a blind visibility conversion.
  The manual's move to `GLOBAL_ACCOUNT` is an explicit ownership/home transition,
  not a side effect of converting its old Shared visibility to `GLOBAL_READ`.
- Trusted classification/aliases remain protected; authored namesakes remain
  ordinary; internal workers and ordinary explicitly available shipped bots work.
- Account-owned outgoing receipts and claims remain isolated during scope migration.

Focused `full-db` tests are justified later only for migrations, UUID/FK/constraint
enforcement, scope-filtered row mapping, grant/effect transaction races, locking,
rollback and retained receipts. Follow the repository's explicit `-PfullDb` policy
and state the specific database assertion before running them. Mocked behavior
tests cannot establish those persistence properties.

The source audit was checked with 380 passing cases across 23 focused suites at
the baseline above, including acting authority, retained execution, classification,
definition resolution, skills, curation and digest submission. The console asset
build was excluded after an unrelated TypeScript dependency failure. No database
tests ran. This is baseline evidence for existing behavior, not validation of the
proposed substrate or a successful legacy-data migration.

After the main merge, 28 focused non-database cases passed across
`InformationFeatureConfigTest`, `DocumentNavigationTest`, `InformationAccessTest`
and `AdmittedTurnTest`. This validates feature defaults and existing boundary
behavior, not the proposed `GLOBAL_ACCOUNT`/`GLOBAL_READ` model.
