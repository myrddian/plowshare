# Role and scope permissions proposal

Status: proposed permission matrix, 17 October 2026. Documentation only. These
tables do not change current role names, grants, APIs or enforcement.

This is the action-level companion to
[ADR 0030](decisions/0030-authority-substrate-and-explicit-scopes.md) and the
[Global scope migration map](global-scope-migration-map.md). It maps the scopes
and permission owners in the current implementation, then proposes permissions
for ADMIN, MANAGER, CONTRIBUTOR and READER. Runtime source baseline:
`efddb12a0cfd28c6aa1464f01b884d15597102d5`; the subsequent proposal commits change
documentation only.

## Rules for reading the matrices

A role applies only through a valid grant naming its scope and any permitted
descendant coverage. Role names are not account-wide permission levels. Current
VIEWER is the candidate for proposed READER; current ADMIN is a human
`server_admin` property, not a `ProjectRole` value.

Proposed ADMIN provides scoped administrative control. It does not automatically
include private content access or execution. A human administrator can also hold
a separate READER, CONTRIBUTOR or MANAGER grant for the relevant data scope.
MANAGER includes ordinary CONTRIBUTOR and READER actions inside its scope;
CONTRIBUTOR includes ordinary READER actions. Resource ownership, ACLs, mandatory
policy, source/destination permission, retained execution ceilings, tool/file
grants and lifecycle constraints can still refuse any action.

In the proposed tables, **Allowed** means the role supplies the action, subject
to those restrictions. **Data grant** means ADMIN alone does not supply it.
**Owner** requires the exact authenticated owner or a separately supported
delegation. **Release** requires explicit source-release and destination/audience
authority beyond ordinary edit permission. **Human** means the action remains
human-only even for an agent with forced MANAGER.

Read access includes discovery, counts, event payloads and metadata only when the
corresponding resource policy permits their disclosure. A reference-visible
document is not necessarily body-readable. None of these tables grants tools,
filesystem paths, credentials or budget merely by listing a permitted action.

A permitted retrieval read may consume its admitted query-embedding allowance.
READER does not thereby gain permission to start an independent agent, processing
pipeline, digest or curation job. Classify model-directed navigation separately
from a bounded retrieval read rather than treating every operation that returns
text as the same capability.

## Scopes and boundaries present today

| Current boundary | Identity and enforcement today | Proposed interpretation |
| --- | --- | --- |
| Server administration | Enabled human server-admin account; specific operation/service gates | Explicit server-administration scope; ADMIN grants named control actions |
| Internal server Relay | `Relay.SystemScope.SERVER`, separate from project topics | Explicit server scope; no implicit project/tenant data coverage |
| Legacy Global | Null project in `Home`, memories, conversations and jobs; shared setup and inherited data | Built-in memory-only Global Project with fixed reserved project/scope UUIDs; move other meanings to server configuration, separate publication policies or restricted legacy history; no Global conversations |
| Ordinary project | Project row/name, live project roles and resource-derived project resolution | Explicit project scope with scoped role grants |
| Application project | Ordinary project roles plus active manifest, execution identity, placement and source rules | Same project role vocabulary with additional Application boundaries |
| Personal execution workspace | Account-owned private project/union | Explicit Personal scope with fixed owner control; no ordinary membership sharing |
| Personal information | Account namespace and document/resource ownership | Same person's Personal authority, with resource-specific controls preserved; not a second public project |
| Private client project | `client:` identity tied to authenticated account and physical client session | Session-bound scope; roles cannot substitute for the required session |
| Shared information | `Selection(SHARED)` and publication visibility; human selections can include it | Explicit publication audience and corpus selection, not an administrative role or automatic project import |
| Individual document/resource | Resource/revision IDs, owner, policy, retained input/reference restrictions | Resource grants/ACLs constrained by owning scope and mandatory policy |
| Account-owned operations | Session, approval, inbox, credential connection, schedule, outgoing work or receipt owner | Owner/action overlays on explicit scope; not one universal account-wide work bucket |
| FileStore and filesystem | Alias grants, admitted writable areas, exclusions and provider/session fences | Independent capability boundary intersecting role/scope authority |

Tenant scope is not a first-class identity in the audited account/project
contracts. It remains a design decision in ADR 0030. Do not present a tenant ADMIN
or tenant MANAGER matrix as already implemented, infer tenants from project names,
or merge multiple users' Personal scopes into one administrative data scope.

## Current role enforcement

The current central classifier is
[ProjectAuthorization.required/require](../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/ProjectAuthorization.java).
The role below is its baseline gate, not the final permission decision of every
owning service. Its unlisted-operation default is CONTRIBUTOR; the proposal
requires explicit semantic classification rather than preserving that default
for new capabilities.

| Current action family | Current baseline authority | Additional limits and proposed treatment |
| --- | --- | --- |
| Read project conversations, memories, definitions, work status and board messages | VIEWER | Resource-derived scope, Personal/session fences and log/input policies still apply; proposed READER |
| Open conversations, start/resume agents, post messages, run orchestration, cancel/update work | CONTRIBUTOR | Existing lifecycle, owner, routing, grant and budget checks remain; proposed CONTRIBUTOR |
| Define agents/orchestrations, manage project members/roles, operate Relay policy | MANAGER | Current `MANAGE` set; source/definition and last-manager constraints remain; proposed MANAGER with explicit delegation rights |
| Save or synchronize schedule definitions | MANAGER in `required` | Registered source owner, Application control and execution target checks also apply |
| Create/activate/deprecate content-type versions | MANAGER for project namespace; authenticated owner for Personal namespace | Shared registry authoring is not admitted; preserve namespace and immutable-version rules |
| Account administration, ordinary project provisioning/move/write-area administration, Application storage/identity issuance | Server administrator | Currently operation-specific server gates; proposed scoped ADMIN control actions |
| Server schedule definition/event emission, buffer purge, retention sweep, information quarantine migration | Server administrator | Project MANAGER cannot invoke platform maintenance merely by naming its project |
| Read Global memory/conversation data | Generally authenticated human read admission | Service tokens refuse explicit Global work; Global conversations cannot resume; legacy behavior is not the target role matrix |
| Mutate Global memory/definitions | Generally server administrator | Outgoing/account-owned paths are separate exceptions; replace with explicit target and action authority |
| Project memory recall | VIEWER on requested project | Archive still appends Global internally; proposed query scope enforcement removes that hidden expansion |
| Curate/promote memory | CONTRIBUTOR for explicit project curation and normal proposal mutations | Current confident promotion can publish to Global without separate release authority; proposed curation and publication become separate actions |
| Information write, sharing, processing and lifecycle commands | CONTRIBUTOR at project work gate | Many mutations require exact resource owner; input readability, processing gates and receipts remain authoritative |
| Document navigation policy administration | Human server admin or qualifying human project manager in the repository | Service tokens refuse; body readability is separate; retained restrictions constrain policy widening |
| Conversation context projection/count/snapshot | VIEWER at central gate | Exact conversation owner and live read authority are required by usage/context service; no administrator or sibling-token owner bypass |
| Usage reports | VIEWER classification for several query names | Reporting is human-account-only; fleet pool statistics require server admin; narrower reports additionally filter memberships and ownership |
| Provider connection metadata | VIEWER project access | Endpoint configuration requires server admin plus project MANAGER; credential subject checks remain separate |
| Personal provider credential lifecycle | Exact subject plus project read/work authority | Another subject requires server admin; no raw secret disclosure is implied by connection metadata reads |
| Approval answer/revoke | CONTRIBUTOR central mutation classification | Addressed account/session, conversation scope and input-log policy restrict who can answer; generic project MANAGER is insufficient |
| Schedule pause/resume/remove | Account-owned exception in central gate | Mutable source is normally owner-controlled; Application schedule executor with CONTRIBUTOR or project MANAGER may control an Application schedule; authoring still needs its own authority |
| Outgoing send/claim/report/cancel | Project work admission plus exact account ownership | Null-project storage is still account-bound; ownership is not interchangeable with project MANAGER |
| Saved information views | Included in the central read set even for save/delete | These are account-owned navigation state; do not classify every command containing save/delete as content contribution |

[JdbcProjectMembers.role](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/JdbcProjectMembers.java)
currently treats a server admin as MANAGER in ordinary non-Personal/non-client
projects. This grants more ordinary project access than the proposed explicit
ADMIN control/data separation. Usage reports and document policies have further
checks, so that behavior is not universal read access to all retained data.

## Proposed project action matrix

This matrix applies to an ordinary project and, with the additional controls
below, an Application project. No column permits acting outside the grant's scope.

| Action | ADMIN control grant | MANAGER | CONTRIBUTOR | READER |
| --- | --- | --- | --- | --- |
| Discover/read permitted content, conversations, memories, definitions and status | Data grant | Allowed | Allowed | Allowed |
| Read another principal's private context or owner-only receipt | No | No | No | No |
| Export currently readable content to the requesting principal | Data grant | Allowed | Allowed | Allowed |
| Open a conversation; run an agent, skill, orchestration or board | Data grant | Allowed | Allowed | No |
| Post messages, receive admitted external work, send outgoing work | Data grant | Allowed | Allowed | No |
| Write/update content, memories and permitted ordinary metadata | Data grant | Allowed | Allowed | No |
| Start paid document processing, digest or curation assessment | Data grant | Allowed | Allowed | No |
| Cancel/control eligible work in the project | Data grant | Allowed | Allowed | No |
| Change a resource's lifecycle, delete or invalidate it | Data grant | Allowed where domain permits | Allowed where domain permits | No |
| Define agents, orchestrations, permitted hooks/instructions or local routing | Control action if named in ADMIN grant | Allowed | No | No |
| Manage project content-type definitions and activation | Control action if named in ADMIN grant | Allowed | No | No |
| Author/sync project schedules and trigger definitions | Control action if named in ADMIN grant | Allowed | No | No |
| Pause/resume existing work schedules | Data/control action as explicitly granted | Allowed within source/control rules | Owner or admitted Application executor | No |
| Change Relay retention/topic policy or operate control state | Control action if named in ADMIN grant | Allowed | No | No |
| Publish/consume/ack admitted project Relay work | Data grant | Allowed with route/topic authority | Allowed with route/topic authority | No |
| Inspect ordinary project membership | Control metadata grant | Allowed | Allowed | Allowed |
| Issue/revoke READER, CONTRIBUTOR or MANAGER grants | Explicit issuing/delegation authority | Explicit issuing/delegation authority | No | No |
| Issue scoped ADMIN grants to humans | Explicit issuing/delegation authority | No | No | No |
| Force an agent's MANAGER exception | Human with explicit exception-issuing authority | Human with explicit exception-issuing authority | No | No |
| Manage permitted local document/reference policy | Human control authority | Human within platform limits | No | No |
| Share/publish to another scope or a broader audience; broaden a resource ACL | Release | Release | Release | No |
| Restrict/revoke a grant the actor controls | Explicit grant-control authority | Explicit grant-control authority | Owner only where policy permits | No |
| Provision/move/remove server project storage or change mandatory workspace fences | Explicit infrastructure ADMIN authority | No | No | No |
| View access-control audit for the scope | Named audit action | Allowed | No by default | No by default |

Cancellation and deletion do not bypass another principal's owner-only record,
input restrictions or domain lifecycle. Managers can manage project work without
acquiring arbitrary approvals, OAuth connections or Personal content. Bulk
destructive maintenance needs its separately named action and lifecycle rather
than an assumption that every content-edit role includes it.

Export to a principal who already has body-read authority is a disclosure to that
principal. Publishing a copy to a new audience is a separate release. The server
cannot revoke an external copy already delivered to an authorized reader.

The schedule/trigger and hook rows are proposed classifications, not claims of
uniform current MANAGER enforcement. Trigger definition currently falls through
the generic CONTRIBUTOR gate. Hooks/instructions are often editable files, so
their effective authority also depends on protected paths and source configuration.
Treat changes that can alter future tools, execution identity or routing as
management actions even when submitted through a generic file-write operation.

## Proposed server administration matrix

The server scope covers platform control and internal operational metadata. It
does not contain every tenant/project's content by implication.

| Action | ADMIN | MANAGER | CONTRIBUTOR | READER |
| --- | --- | --- | --- | --- |
| Read sanitized operational metadata admitted to the server scope | Named read action | No initial server binding | No initial server binding | Explicit server READER grant |
| Create/disable/recover human or service accounts and revoke sessions/tokens | Named identity-control action | No | No | No |
| Issue human ADMIN grants and manage grant policy | Named issuing authority | No | No | No |
| Configure pricing, platform processing limits and mandatory policy | Named platform-control action | No | No | No |
| Admit project/Application storage and change deployment fences | Named infrastructure action | No | No | No |
| Inspect internal server Relay logs and fleet usage | Named operational-audit action | No | No | No |
| Define server schedules or admit maintenance/event effects | Named operational-control action | No | No | No |
| Run retention/buffer maintenance or quarantine adoption/release | Named maintenance action and targets | No | No | No |
| Manage the SYSTEM-owned installed definition catalogue | Named catalogue-management action | No | No | No |
| Manage Deployment defaults and permitted SYSTEM worker overrides | Named deployment/worker-configuration action | No | No | No |
| Read private project/Personal bodies merely because server administration is granted | No | No | No | No |
| Log in as SYSTEM, assign SYSTEM or grant ADMIN to an agent | No | No | No | No |

The initial proposal does not create generic server MANAGER or CONTRIBUTOR
bindings. Routine agents operate in resource scopes, while server maintenance
uses bounded workload admission from an explicit operator policy. A server READER
grant is an optional proposed diagnostic projection; it excludes secrets,
internal Relay payloads, fleet usage and private data unless separately reviewed.
Current code does not yet expose that general role/projection.

Deployment files and provider secrets that are currently operator-only files do
not become remotely editable just because this table names an administrative
action. A public configuration capability would need its own design and contracts.

Deployment definition placement does not grant an execution role. Ordinary bots
there run under the admitted user's account. A trusted internal SYSTEM worker
resolves only authorized Deployment overrides and bundled defaults, retaining its
SYSTEM executor and bounded task authority when its definition is overridden.
That resolution excludes project, Personal, client-session, Application and
Global-memory sources. Managing the override does not assign SYSTEM to its author
or make the worker callable as SYSTEM through the user catalogue.

## Personal and private client scope matrix

Ownership/session binding is a prerequisite here. A role held elsewhere supplies
no access. A Personal owner has local control without becoming a server ADMIN.

| Action or scope | ADMIN held elsewhere | MANAGER in the exact scope | CONTRIBUTOR in the exact scope | READER in the exact scope |
| --- | --- | --- | --- | --- |
| Enter another account's Personal workspace | No | No ordinary membership grant | No ordinary membership grant | No ordinary membership grant |
| Read/work in one's own Personal workspace | Owner authority, independent of ADMIN | Owner may manage local resources | Bounded owner-authorized agent/work grant | Bounded owner-authorized read grant |
| Change Personal membership | No | No | No | No |
| Configure one's own Personal bots, skills and local types | Owner management authority | Owner management authority; forced agent exception only for supported actions | No management by default | No |
| Read or edit a deliberately shared individual Personal document | Separate resource grant | Separate resource grant | Separate resource grant | Separate resource grant |
| Release Personal content to a project or published audience | Owner consent plus Release | Owner consent plus Release | Owner consent plus Release | No |
| Set mandatory platform policy over Personal data | Explicit platform-policy ADMIN action; no body-read implication | No | No | No |
| Enter a private client project | Required exact account/session, not ADMIN | Required exact account/session | Required exact account/session | Required exact account/session |
| Borrow another account/session's file provider | No | No | No | No |

Ordinary Personal membership remains fixed to its owner. Resource sharing is a
separate explicit path, not a grant of access to the rest of Personal. Existing
acting Personal access uses proof/context consent and does not add Application
membership. Current document/reference policy administration is human-only and
may require platform administration for Personal information; local owner control
of bots/types is not permission to override that policy. Any broader owner policy
override is a separate proposal decision.

Client-private scopes remain session-bound. This matrix does not add remote
membership or acting support to them. Ordinary server-project checkouts still
use the separate current checkout/provider/session fences.

## Publication and account ownership overlays

### Global memory permissions

Global is a proposed built-in memory Project with fixed reserved project/scope
UUIDs, separate from the SYSTEM account, SYSTEM role, server-administration scope
and shared-document catalogue. Its
initial admitted kinds are memories and their derived retrieval/index views.

The matrix below applies only to eligible human work through the CLI, TUI or GUI,
or explicitly admitted internal work. These are the only interactive Global
clients; human ADMIN management still requires actual collection/action authority.
External SDK integrations, Applications, service-token integrations
and provider/client-tool workers have no Global access at any role. Acting for a
human does not change the external actor's exclusion. No explicit role grant,
forced MANAGER, provider assignment, SDK tool call or internal-worker proxy can
override it. The CLI/TUI/GUI may use SDK libraries internally, but an arbitrary
external client using human credentials is not thereby eligible. Client names,
User-Agent strings and CLI proxies do not establish the required trusted
authentication/admission provenance.

| Interactive entry point | Global eligibility |
| --- | --- |
| Plowshare CLI | Eligible human admission; ADMIN manages, reads require their audience grant |
| Plowshare TUI | Same human admission, role and audience requirements |
| Plowshare GUI | Same human admission, role and audience requirements |
| External SDK application, including a human acting subject or generic human login | Ineligible |

| Global memory action | ADMIN | MANAGER | CONTRIBUTOR | READER |
| --- | --- | --- | --- | --- |
| Read explicitly selected published memories | Separate audience/read grant | Audience/read grant | Audience/read grant | Audience/read grant |
| Propose a candidate from an authorized source | Source data/work grant | Source work authority | Source work authority | No |
| Create/promote/edit/retire a Global memory or change its audience | Human ADMIN action over this collection, with source Release where applicable | No | No | No |
| Create/resume/list/search Global conversations | No | No | No | No |
| Treat shared documents or server configuration as Global memories | No | No | No | No |
| Grant an external SDK integration Global discovery/read/write/tool-binding access | No | No | No | No |

Curator may submit a proposal but cannot approve its own publication. A forced
MANAGER agent cannot acquire a Global publication action. An authorized server
executor can apply a retained human administrative decision without granting its
agent ADMIN. Read counters and derived index maintenance remain bounded
bookkeeping, separate from content/lifecycle/audience administration. Maintenance
jobs have an explicit administrative owning scope and Global-memory targets.

The following general publication matrix applies to other resource publication
policies. Its Release cells do not weaken the human ADMIN rule for Global memory.
Adding non-memory kinds to Global requires a separate scope/policy decision.

### Other publication and owner controls

| Resource or action | ADMIN | MANAGER | CONTRIBUTOR | READER |
| --- | --- | --- | --- | --- |
| Read explicitly published information | Audience/data grant | Audience grant | Audience grant | Audience grant |
| Discover unpublished/private resources through a published selection | No | No | No | No |
| Edit the source of a published resource | Separate source data grant and controller permission | Source/controller permission | Source/controller permission | No |
| Choose a broader publication audience or copy into another scope | Release | Release | Release | No |
| Import permitted material into a project | Destination data grant | Destination write plus source/input permissions | Destination write plus source/input permissions | No |
| Read own inbox, outgoing status, mutation receipts and private context | Owner | Owner | Owner | Owner for read-only operations |
| Send/claim/report/cancel own outgoing work | Owner plus data/action grant | Owner plus work authority | Owner plus work authority | No |
| Manage own saved views and non-authorizing UI preferences | Owner | Owner | Owner | Owner |
| Answer an addressed human approval | Addressed human plus work authority | Addressed human plus work authority | Addressed human plus work authority | No |
| Issue Personal acting consent/tokens | Exact consenting human, within eligibility and grant limits | Same owner/consent rule | Same owner/consent rule | Only a bounded read consent where permitted |
| Revoke another person's acting consent token | Explicit security-revocation ADMIN action | No | No | No |
| Complete own provider OAuth/credential lifecycle | Exact subject plus required project work grant | Exact subject plus work authority | Exact subject plus work authority | Status only where explicitly allowed |
| Configure provider endpoints or select a different credential subject | Explicit provider-admin action and target authority | No by role alone | No | No |
| Read raw stored credentials, token digests or passwords | No | No | No | No |

Roles do not make an actor the owner or approval addressee. Issuance of one-time
credentials follows its dedicated lifecycle; the final row does not prohibit
that authorized one-time delivery. Administrative revocation/recovery is separate
from reading another owner's secrets or replaying their mutation. Shared audiences
and owner-only records must retain independent live checks.

## Application and independent capability restrictions

Application projects use the project matrix, intersected with current manifest
roles, token ceilings, active deployment identity, routing, writable areas and
FileStore grants. A host FileStore VIEWER grant is needed for direct source read;
CONTRIBUTOR is additionally required for direct write and admitted writable areas.
Configuration files need Application MANAGER as well. A project role cannot grant
direct host paths, silently widen exclusions or manufacture a missing alias.

Agent actions additionally require the tool/skill/orchestration grant and admitted
capability. Permission to run an agent is not permission to install definitions,
change its tool grants or alter server configuration. Network privacy and provider
endpoint restrictions also remain independent. Cross-project messaging/Relay
requires both source/destination authority and configured routing, not just a
CONTRIBUTOR role at one end.

Built-in tools available across eligible projects are installed platform
capabilities. MCP/client tools already use provider catalogues and interactive or
hosted scope bindings; reuse those mechanisms without a second tool registry.
Global does not store or grant either kind of tool. Availability permits selection;
the appropriate definition/workflow or external provider assignment permits
offering; role, actual target scope and other capability restrictions govern
invocation. External tools retain explicit owner/project/provider/agent assignments
and credential subjects, even when configuration is reused by several projects.
The current contracts require concrete projects, not a project-less wildcard grant.
The proposed Global project is not an eligible external execution/tool-binding
project despite having a concrete UUID. External project retrieval cannot include
Global implicitly or acquire it through an acting subject or a retained view.

Current ordinary service tokens may have effective MANAGER project ceilings;
current acting scopes explicitly reject MANAGER and only support VIEWER or
CONTRIBUTOR. The proposed forced-agent-MANAGER rule does not override that acting
ceiling. A future expansion of acting capabilities requires a separate contract
decision. Direct non-agent Application operations are not automatically treated
as agents, but an agent cannot escape its ceiling through an Application identity
or a direct API tool call.

## Agent role admission

| Requested effective agent role | Default outcome | Can force change it? |
| --- | --- | --- |
| READER | Keep READER within current grants | Not needed |
| CONTRIBUTOR | Keep CONTRIBUTOR within current grants | Not needed |
| MANAGER | Descope to CONTRIBUTOR and report requested/effective roles | Yes, by an explicitly authorized human for that agent, scope and supported action set |
| ADMIN | Reject before any grant effects | No |
| SYSTEM | Reject as non-assignable | No |

Forced MANAGER does not authorize human-only policy management, security consent
or approvals; does not bypass a lower token/acting ceiling; and does not propagate
automatically to children. A child needs its own authorized exception. ACL entries,
tool permissions and granular actions cannot reconstruct ADMIN under another name.

## Permission boundaries that require explicit review

1. **ADMIN and data access:** replace current ordinary-project admin-as-MANAGER
   behavior with explicit control and data grants. Review and migrate grants;
   do not silently widen existing private content access.
2. **Publication:** define the source-release and destination/audience actions
   and who may issue them. Global memory administration/publication is human ADMIN
   only, with source authority still required. Other Release cells deliberately do not assign these
   rights automatically to every editor or manager. Preserve authorized export
   and existing publication/input/reference restrictions.
3. **Management through files:** classify executable hooks, manifests, routing,
   definitions and security-relevant configuration as management actions even
   when normal data writes in the same workspace are CONTRIBUTOR actions.
4. **Scheduling:** explicitly classify trigger creation and durable execution
   authorization. Keep low-risk operational pause separate from authoring,
   resuming authority, owner control and Application schedule control.
5. **Human-only actions:** define exact actions eligible for forced MANAGER
   agents. Current human-only navigation policy, consent and approval boundaries
   remain until separately reviewed; a role exception alone cannot remove them.
6. **Tenant introduction:** specify tenant ADMIN scope and descendant coverage
   after tenant identity exists. The present project matrices do not grant an
   organization administrator automatic Personal/project body reads.

## Source owners and verification required for implementation

| Contract | Current source owners |
| --- | --- |
| Central roles, operations and resource scope | [ProjectAuthorization](../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/ProjectAuthorization.java), [AccessRequestDecoder](../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/AccessRequestDecoder.java), [JdbcResourceScopeRepository](../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/JdbcResourceScopeRepository.java), [ProjectRole](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/ProjectRole.java) |
| Membership and server identity | [JdbcProjectMembers](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/JdbcProjectMembers.java), [AdminStore](../plowshare-server/src/main/java/io/aeyer/plowshare/server/auth/AdminStore.java), [current account manual](server-administration.md) |
| Personal and client identity | [PersonalSpaces](../plowshare-server/src/main/java/io/aeyer/plowshare/server/personal/PersonalSpaces.java), [PersonalAccess](../plowshare-server/src/main/java/io/aeyer/plowshare/server/personal/PersonalAccess.java), [ClientProjects](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/ClientProjects.java) |
| Resource ownership, publication and policies | [InformationAccess](../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationAccess.java), [JdbcInformationCatalogueRepository](../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/JdbcInformationCatalogueRepository.java), [DocumentNavigation](../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/DocumentNavigation.java), [JdbcInformationNavigationRepository](../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/JdbcInformationNavigationRepository.java), [InformationContentTypeService](../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationContentTypeService.java) |
| Schedules and Application controls | [FileScheduleDefinitions](../plowshare-server/src/main/java/io/aeyer/plowshare/server/events/FileScheduleDefinitions.java), [ApplicationScheduleControlAuthority](../plowshare-server/src/main/java/io/aeyer/plowshare/server/events/ApplicationScheduleControlAuthority.java), [ScheduleActionAuthority](../plowshare-server/src/main/java/io/aeyer/plowshare/server/events/ScheduleActionAuthority.java) |
| Files and provider credentials | [WorkspaceApplicationFiles](../plowshare-server/src/main/java/io/aeyer/plowshare/server/files/WorkspaceApplicationFiles.java), [FileStores](../plowshare-server/src/main/java/io/aeyer/plowshare/server/files/FileStores.java), [CurrentProviderCredentialAccess](../plowshare-server/src/main/java/io/aeyer/plowshare/server/providers/CurrentProviderCredentialAccess.java), [OwnedHostedToolScopes](../plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/tools/OwnedHostedToolScopes.java) |
| Owner-only controls and reporting | [ApprovalFrames](../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/ApprovalFrames.java), [JdbcOutgoingRepository](../plowshare-server/src/main/java/io/aeyer/plowshare/server/outgoing/JdbcOutgoingRepository.java), [JdbcUsageReportRepository](../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/accounting/JdbcUsageReportRepository.java), [ConversationContextAccess](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/ConversationContextAccess.java) |
| Acting ceilings and internal scopes | [ActingValues](../plowshare-server/src/main/java/io/aeyer/plowshare/server/acting/ActingValues.java), [LiveActingAuthorization](../plowshare-server/src/main/java/io/aeyer/plowshare/server/acting/LiveActingAuthorization.java), [Relay](../plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/Relay.java), [OwnedRelaySubscriptionWork](../plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/OwnedRelaySubscriptionWork.java) |

For each future operation contract, record its action, actual resource resolver,
permitted scope kinds, role/grant requirements, owner/human-only restrictions,
admitted effect/output targets and retained authority. New operations without a
classification refuse; an operation prefix or payload scope is not sufficient.
These family-level tables are not a generated exhaustive wire-operation manifest.

Verification should cross every proposed action with role, scope, principal kind,
ownership, token/acting ceilings and agent exception state. Include operations
reached through generic files/tools, counts/events, queues, children and receipts,
and changes to ownership/membership during execution. Preserve last-admin and
last-manager recovery, concurrent grant changes and uncertain-effect handling.
Use mocked repositories for policy behavior; reserve explicitly selected full-db
tests for real SQL, locking, migrations and transaction assertions. No runtime or
database tests implement or validate the proposed matrix in this documentation change.
