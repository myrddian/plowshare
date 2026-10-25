# Dispatcher read audit

Source baseline: `4af3f892f58623353a8ce373fbd0cccda19b92b3`.
Scope: event-socket scheduling, including both negotiated concurrent dispatch and
the legacy independent-read scheduler. PostgreSQL query volume, permission-query
reuse, SQL plans and shared authority caches are separate work.

## Cause and change

The bounded dispatcher introduced in `e91181ef0172e1bb12194efeb1d691043e138587`
uses a connection-wide barrier for unclassified operations. Later handlers also
inherit that default. A running barrier prevents an unrelated read from using a
free execution slot; a queued barrier prevents later reads overtaking it. The
Library/activity fix in `63bfd32e` classified four reads, but many other UI
listings, reports and inspection requests still fell through to that default.

This audit adds 83 independent operations to the explicit read inventory in
`DispatchPolicy`. `EventReadPolicy` now uses that same inventory, so the two paths
cannot silently disagree about these read effects. The shared inventory contains
102 operations, including the prior Library reads, catalogue reads and three
existing finite status polls. It is an exact operation allowlist, never a suffix,
prefix, HTTP verb or authorization-role heuristic.

Three more operations (`conversation.context.count`,
`conversation.context.snapshot`, `todos.read`) now share the existing
`conversation` resource key. They remain ordered with lifecycle/resume and
inspection of that conversation across connections, while another conversation
can progress. Legacy scheduling retains barriers for these operations because it
does not implement resource keys.

Existing per-connection execution, admission depth, observer limits, byte budgets,
resource-key bounds and failure cleanup are unchanged. This changes eligibility
within those bounds; it makes no claim to reduce SQL calls or server CPU.

## Independent operation inventory

The following groups are the newly classified operations. All optional branches
of each listed handler were checked for effects. Ordinary repository reads may
span statements and retain their existing consistency; a scheduling change does
not turn them into an atomic snapshot. In-memory definition/projection caches
remain owned by their existing concurrency-safe loaders.

| Operations | Handler and complete read path |
| --- | --- |
| `admin.accounts`, `admin.sessions`, `admin.audit`, `admin.service.accounts`, `admin.service.tokens`, `admin.pricing.list` | `AdminFrames`, `ServiceAccountFrames`, `PricingFrames` → `ServerAdministration`, `ServiceAccounts`, `PricingAdministration` → listing/session/history/pricing repositories. Current server-admin admission remains required; no token issuance, reset or audit append on these paths. |
| `project.list`, `project.access` | `ProjectListHandler` / `ProjectFrames` → `ProjectStore`, `ProjectMembers`. Session/account/project filtering, memberships, roles and placement projections only. |
| `conversation.list`, `conversation.latest` | `ConversationListHandler`, `ConversationLatestHandler` → `ConversationStore.inHome`, `Conversations.toContinue`. Tier/lifecycle/agent selection only; latest never opens or resumes a conversation. |
| `agent.list` | `AgentListHandler` → `DefinitionResolver.refreshForCaller`, `AgentRows`, skill/orchestration resolvers. Intentionally refreshes the caller's in-memory definition entry and reads current server/client definitions; does not save definitions, run agents or change grants. |
| `job.list`, `job.status` | `JobListHandler`, `JobStatusHandler` → account-scoped `JobStore.jobsFor` / `getFor`. No cancellation, stream registration or limit changes. |
| `board.messages`, `swarm.types` | `BoardInspectionFrames.messages`, `SwarmTypeFrames` → board rows, scheduler snapshot and `SwarmCatalog`; current project role checked before disclosure. No seat wake/admission or publication. |
| `proc.list`, `proc.inspect`, `proc.calls`, `proc.queues`, `proc.tools` | `ProcFrames` → `ProcAudit` → `ProcRepository` and `ProcRuntime` snapshots. Fleet/custody/project scopes remain repository-authorized; selectors and pagination never grant content access or claim work. |
| `usage.conversation`, `usage.project`, `usage.agent`, `usage.run`, `usage.orchestration`, `usage.models`, `usage.pools`, `usage.calls` | `UsageFrames` → `UsageQueryService` → `UsageReportRepository`, with recorder/journal/projector health snapshots. Filters, call-attempt pagination and lineage selectors are reads; no subscription, model invocation or accounting projection is started. |
| `document.chunk`, `document.citations` | `DocumentChunkHandler`, `DocumentCitationsHandler` and scoped `DocumentFrames` wrappers → corpus/chunk/citation repositories. The citation operation reads retained citations; it does not record new evidence. |
| `approval.list` | `ApprovalFrames.list` → account-open, conversation-open or project-standing approval reads, caller/log authority and `ProjectListingFilter`. No decision, delivery acknowledgement or continuation. |
| `orchestration.status`, `orchestration.receipt`, `orchestration.definitions`, `orchestration.record` | `OrchestrationFrames`, `RecordFrames` → owned run/start receipt, todo/message/child/stall reads, definition resolvers and `RecordReads`/`RecordStore`. Records are paged reads; no cap application, answer, resume or retained-work replay. |
| `memory.index`, `proposal.list`, `provider.list` | Their handlers → `Archive.index`/`MemoryStore.index`, `PromotionQueue.waiting`, `ProviderStore.all`. No memory-use counters, proposal settlement or provider deregistration. |
| `trigger.list`, `firing.list`, `union.conflict.list` | `TriggerListHandler`, `FiringListHandler`, `UnionFrames.listConflicts` → stores, with existing owner/project result filtering. No trigger execution, conflict resolution or sync claim changes. |
| `message.instances`, `message.instance`, `message.deliveries`, `message.delivery`, `incoming.status` | `MessageFrames` / `IncomingFrames` → `BoardMessaging` account-owned instances/routes, current participant/log visibility, listing/delivery/state/reply reads. Neither delivery inspection nor external-task inspection settles or cancels a message. |
| `incoming.catalog` | `IncomingFrames.catalog` → session/project/agent checks and definition/skill/orchestration catalogues. No external message receipt or work creation. |
| `outgoing.status`, `outgoing.peers` | `OutgoingFrames` → owned `OutgoingWork.get`/`peers` → repository reads. No advertise, claim, send, cancel or report. |
| `application.files`, `application.file.read`, `filestore.list` | `ApplicationFileFrames`, `FileStoreFrames` → `WorkspaceApplicationFiles` bounded directory/UTF-8 reads and `FileStores.catalog`. Source/placement/role/fence checks remain; no save, staging file or directory creation. |
| `application.identity.status`, `application.token.receipt`, `application.deployment.status`, `application.deployment.receipt` | Application frames → `OwnedApplicationIdentities` / `SourceApplicationDeployments` → current binding/status or retained receipt reads. Receipt ownership remains checked; absence never authorizes replay or token issuance. |
| `provider.connection.list`, `provider.connection.status`, `provider.connection.receipt` | `ProviderCredentialFrames` → `OwnedProviderConnections` → access checks and unlocked connection/receipt reads. The receipt branch checks any retained credential subject but does not retrieve/expire live credentials. |
| `tool.scope.list`, `tool.scope.host.list`, `tool.scope.host.status`, `tool.scope.host.receipt`, `tool.scope.worker.status` | Tool-scope frames → `SessionToolScopes` / `CombinedToolScopeConnections`, `OwnedHostedToolScopes`. Current attachment/binding/owner/executor/lease inspection only. Worker status calls `live` without renewing or acquiring a lease. |
| `information.inventory`, `information.acquisitions`, `information.outline`, `information.symbols`, `information.events`, `information.evidence.read`, `information.content.get` | `InformationFrames` → `InformationCatalogue` / `InformationAcquisitions` → current authorized selection, inventory, acquisition, syntax, event, evidence or content projections. Evidence reads recheck the source revision; no evidence recording or processing. |
| `information.migration.list` | `InformationFrames` → `DocumentPolicyAssignments.inventory` → operator-only inventory. Unlike migration inspection, inventory does not append an audit row. |
| `information.types.capabilities`, `information.types.list`, `information.types.get`, `information.types.validate` | `InformationFrames` → `InformationContentTypeService` → selection/draft-manager/attached-revision checks, schema reads and pure validation. No draft creation, activation, deprecation or assignment locks. |
| `information.policy.get`, `information.views.list`, `information.views.get` | `InformationFrames` → `DocumentNavigation` → read-only snapshot, active account, scope/policy authority and repository reads. No policy/view update. |
| `information.interchange.capabilities`, `information.interchange.export`, `information.interchange.readable`, `information.interchange.origin` | `InformationFrames` → `PortableInformation` → bounded read-only snapshot and current revision/dependency/type/origin reads. Export creates an in-memory bundle identifier, not a persisted import or receipt. |

Existing catalogue, navigation, Relay topic/log, schedule list/file and
Library/activity reads keep their independent classification. Existing
`document.search` / `information.search`, `web.search`, revision mutations,
schedule mutations, conversation operations, connection-state operations and
observers keep their separate policies; this audit does not reinterpret them as
side-effect-free operations.

## Reads intentionally kept ordered

| Operations | Reason |
| --- | --- |
| `information.status` | Hidden-owner fallback acquires write-admission locks. |
| `provider.credential.status` | Locks connection/credential rows and can store a new UNCERTAIN credential generation after expiry. |
| `memory.read` | `Archive.read` increments usage counters and stamps each memory inside a unit of work. |
| `memory.recall`, `memory.navigate`, `document.retrieve`, `document.rank`, `document.stance`, `information.rank`, `conversation.search` | Embedding/inference/accounting, memory telemetry or persisted retrieval snapshots; these need an effects/resource policy, not admission as pure reads. Existing classified search policies are unchanged. |
| `information.migration.inspect` | Appends an operator audit row in the inspection transaction. |
| `orchestration.caps` | Applies caps to current runs through `Orchestrations.applyCaps`. |
| `union.status` | `UnionGate.state` can expire a sync claim and signal waiters. `union.hidden` is a setter. |
| `tool.scope.worker.read`, `tool.scope.worker.credential` | Retained invocation/result reconciliation and credential-release transactions belong to the worker effect/lease lifecycle. They are not ordinary metadata inspection. |
| `job.stream`, `conversation.follow`, usage subscribe/unsubscribe, acting-context operations | Change connection registrations or authority lifecycle; existing resource/ordering policy retained. |
| `schedule.read`, `web.fetch` | Schedule reconciliation or remote fetch/buffer persistence; existing resource/barrier policy retained. |

All mutations retain their existing barrier/resource policy, including misleading
names such as `job.limits`, `project.member.role`, `inbox.read`,
`information.tags`, `information.tags.groups`, `information.views.save` and
`union.hidden`. Unknown operations remain barriers. Invalid envelopes and explicit
null/invalid acting selections keep existing refusal and ordering behavior.

## Ordering and authorization invariants

- An earlier barrier drains prior executing reads; it blocks subsequent reads
  and mutations until execution completes. Queued barriers cannot be overtaken by
  newly independent reads. Reply-byte ownership remains separate from execution.
- Conversation resource inspection shares the existing process-wide key with
  lifecycle/resume, across accounts and sockets. An acting selection takes the
  acting-context lane before independent-read classification, as before.
- `FrameRouter`, authenticated socket identity, `ProjectAuthorization`,
  Personal/workspace fences, repository scopes and result filtering are unchanged.
  A finite policy never supplies authorization.
- Async completion and physical reply disclosure retain their current authority
  fences. Revocation during execution or while a reply is queued must withhold
  protected data; a later read must be authorized again. Older unnegotiated
  clients now apply the existing `FrameRouter.requireDisclosure` and live socket
  session fence to independent replies at the physical writer too. Their
  synchronous handler contract remains intact. This adds the required fresh
  authorization check at that delivery boundary, without permission queries for
  scheduling, an allow-decision cache or changes to SQL implementations.
- Independent reads can observe concurrent changes from other connections and
  existing resource operations. As with previously independent reads, a client
  needing a completed mutation's state submits its read after the mutation reply;
  PostgreSQL/owner transactions and expected revisions remain authoritative.

## Regression coverage

`DispatcherReadAuditTest` holds each of the 83 newly independent operations open
and proves a later Library read enters a free slot. Every operation also retains
its acting-context lane. `EventReadDispatchTest` repeats the held-read proof for
all 83 operations without concurrent-dispatch negotiation.

`ConcurrentResourceOrderingTest` tests a running read followed by two mutations
and a later read, including a queued barrier with free capacity. It also holds a
conversation mutation and proves same-conversation inspection waits while another
conversation progresses. Tests check reservation cleanup; existing capacity,
failure and cross-connection ordering tests continue to apply.

`DispatcherReadAuthorizationTest` exercises real router/project authorization
with mocked repositories through both legacy and segmented transports: an
unrelated read completes, membership is revoked while the first read is pending,
the protected result is withheld, and a subsequent request never reaches its
handler. `ConcurrentEventChannelTest` additionally holds the physical writer and
revokes permission with a protected response queued, on both encodings and on
the older segmented path without dispatch negotiation. The authorization tests
also exercise that older scheduler with real project admission and revocation
while a read is running.

These tests require no PostgreSQL because SQL, migrations and repository
transactions are unchanged. Run `format`, focused dispatcher/authorization tests,
`formatCheck` and repository `check`. Local tests establish scheduling and
authorization behavior; they do not establish host CPU improvement or live
Desktop timings for this additional audit.

Local verification on 2026-10-26: `./gradlew --no-daemon --max-workers=2 format
formatCheck check` passed with Java 21 and Node 22. The seven dispatcher/read
policy/authorization test classes contain 339 passing cases, zero failures and
zero errors in that run. No `-PfullDb` or database-backed smoke task was used.
