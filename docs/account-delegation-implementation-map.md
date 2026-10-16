# Account delegation: implementation map for ADR 0026

Status: five slices are implemented: authority/policy, declarations/consent
persistence, socket-bound contexts with synchronous turn-history reads, and
retained-authority Personal turns, and standard synchronous tools with child-agent propagation.
[ADR 0026](decisions/0026-service-execution-on-behalf-of-an-account.md) remains
proposed beyond that scope. Broader effect capabilities and client consent
management remain separate slices.

The unpublished acting migrations use V149–V151 after integration with current main;
SQL contents are unchanged by the renumbering.

Original audit baseline: merged main
`9f772f55e5779b2061a6a407de55b7d2e06bfa28`. Existing code references describe
that snapshot. The slice sections record the new internal implementation
added afterwards. Remaining proposed types and operations are labelled as such.

This is a coordinated 0.0.1 server, protocol, SDK and client change. Supporting
older releases, compatibility shims and a staged production rollout are not design
priorities. Update the contracts together and test the resulting behavior. The
important invariants are identity isolation, bounded authority, durable provenance,
revocation and recovery without repeating uncertain effects.

## First slice: typed authority and policy

The server's new `acting` package defines immutable
[RequestAuthority](../plowshare-server/src/main/java/io/aeyer/plowshare/server/acting/RequestAuthority.java)
variants for direct and delegated requests, explicit ordinary-project/Personal
targets, and bounded admitted scopes. Delegation refers to a durable token ID;
it carries neither bearer material nor a mutable socket user selection.

[ActingAuthorization](../plowshare-server/src/main/java/io/aeyer/plowshare/server/acting/ActingAuthorization.java)
is the capability contract.
[LiveActingAuthorization](../plowshare-server/src/main/java/io/aeyer/plowshare/server/acting/LiveActingAuthorization.java)
reads current identity, grant, eligibility and token metadata through
[ActingPolicySource](../plowshare-server/src/main/java/io/aeyer/plowshare/server/acting/ActingPolicySource.java)
and existing `ProjectMembers` role reads. It intersects live scope with the scope
admitted earlier; Personal requires explicit grant/token/admission consent and the
subject's current ownership, without granting actor membership.

`ActingPermission` classifies semantic capabilities. Only READ and WORK are
admitted; owner/admin controls, approvals, schedule/trigger creation and
unclassified capabilities refuse. A future caller must classify both external
operations and internal tools using their actual semantics. The policy is now
composed with the persistent source by the second slice. It does not decode
`actingFor` or enable delegated requests. A returned role describes one check, not
a transaction lease.

Mocked tests cover independent ceilings, immutable scopes, exact actor and token
bindings, active human subjects, expiry/revocation, missing records, Personal
isolation, interleaved subjects and source failures. No PostgreSQL is required.
The role ceiling is explicit in internal admitted and token scopes. The lifecycle
slice below defines internal issuance and receipt contracts; wire DTOs remain later
work.

The focused tests contain 78 passing cases across `RequestAuthorityTest` and
`LiveActingAuthorizationTest`. The `format`, `formatCheck` and server Javadoc
tasks also pass. These checks do not start PostgreSQL.

The default repository gate passed with `./gradlew check --max-workers=2`, including
5,521 server tests. An initial concurrent run had an unchanged Relay test timeout
and a code-symbol scan failure; both passed on the reduced-concurrency run.
No database tests were enabled.

## Second slice: declarations and consent lifecycle

[ActingManifest](../plowshare-server/src/main/java/io/aeyer/plowshare/server/acting/ActingManifest.java)
strictly decodes bounded, unique grant declarations, shared by deployment identity
reads and `WorkspaceApplicationPolicy.parse`. Nonempty declarations require an
owned `service`. Unsupported fields/proofs, wrong types, MANAGER ceilings,
duplicate declarations and malformed JSON refuse before business logic.

[ActingTokens](../plowshare-server/src/main/java/io/aeyer/plowshare/server/acting/ActingTokens.java)
defines direct-human issuance, owner/admin revocation, admin eligibility replacement,
owner token metadata and original-submitter receipt reads.
[OwnedActingTokens](../plowshare-server/src/main/java/io/aeyer/plowshare/server/acting/OwnedActingTokens.java)
checks current authority and runs mutation plus receipt/audit in one `UnitOfWork`.
The issue request supplies an explicit role ceiling, project subset, Personal flag,
1–365 day expiry and optional bounded external subject label. Subject identity is
always derived from the authenticated direct caller. The label is not proof of an
external user's identity.

[ActingRepository](../plowshare-server/src/main/java/io/aeyer/plowshare/server/acting/ActingRepository.java)
and its JDBC implementation own normalized grants, eligibility rules, SHA-256
credential digests, token project bindings, receipts and successful lifecycle audit.
[V149](../plowshare-server/src/main/resources/db/migration/V149__acting_grants_and_tokens.sql)
adds their tables. Deployment activates declarations in the same transaction as the
source head, after owned identity activation. Removed declarations become inert;
reads join the active source revision. Membership eligibility and project consent
bind project row IDs rather than names. Every later permission check reads current
state. Application credential rotation preserves the exact token principal.

The `psa_` credential is returned only from a successful new issue and is never
stored in receipts, audit or plain text. Repeating an issue UUID refuses to reveal
it again. Changed submissions under a retained UUID refuse. Revoke and eligibility
retries return the original receipt without repeating the effect. Receipt recovery
and owner/admin revocation remain available while disabled or after grant removal.
Metadata listings are owner-only and bounded to the latest 256 records.

`ActingConfig` composes the internal services and policy. Operator configuration
`plowshare.acting.enabled` defaults to false. This lifecycle slice supplies internal
consent operations; the next slice below adds public context exchange. Broader
runtime propagation and external identity linking remain later work. Successful
lifecycle actions are audited here; proof exchange audit follows below.

Mocked lifecycle and manifest tests cover issuance authorization, Personal consent,
withdrawn proof authority, UUID mismatch/recovery, redacted output, eligibility
administration and source failures. Focused PostgreSQL tests in
`ApplicationDeploymentPersistenceTest.acting*` cover migration constraints, row
mapping/restart reads, active-source rollback/narrowing, transaction rollback,
concurrent issue/revoke receipts, stable identity through rotation, live eligibility
membership and project recreation. These are explicitly selected with `-PfullDb`;
the default suite continues to use mocks/in-memory dependencies.

Validation passed: `format`, `formatCheck`, server Javadoc, 35 new mocked
manifest/lifecycle cases (113 acting cases including the first slice), all six
selected `ApplicationDeploymentPersistenceTest.acting*` PostgreSQL cases, and
`./gradlew check --max-workers=2` with 5,556 default server tests. The default
repository gate did not enable database tests. These internal checks do not prove
a public bridge integration or delegated request execution.

## Third slice: socket-bound contexts and synchronous history

[ActingContexts](../plowshare-server/src/main/java/io/aeyer/plowshare/server/acting/ActingContexts.java)
owns proof exchange and immutable subject admissions. `BoundActingContexts` stores
only typed metadata, with 64 contexts per connection, 8,192 per server and five-minute
expiry capped by proof expiry. Actor, session and physical connection must all match;
reconnecting cannot adopt a context. Connection teardown releases its contexts.
Opening intersects the token and current grant, rechecks current target roles and
retains that admitted ceiling so later policy changes cannot widen it.

[ActingFrames](../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/ActingFrames.java)
strictly decodes open/close payloads. The common Java/SDK envelope carries optional
top-level `actingFor.context`. Malformed, null, extra-field, duplicate-key and
noncanonical UUID selections refuse. `FrameRouter` sends selected requests through
`ActingRequests` before ordinary handler dispatch, preserving the Application actor.
Only `conversation.turns` is admitted. Unsupported operations refuse even if their
ordinary handlers exist; the new path does not impersonate a subject across the
legacy handler surface.

[ActingHistory](../plowshare-server/src/main/java/io/aeyer/plowshare/server/acting/ActingHistory.java)
resolves the conversation's owning project through `ResourceScopeRepository`, checks
current policy before and after loading existing turn records through the read-only
`TurnHistoryRepository` contract, and refuses relocation,
global work or another subject's Personal space. Context liveness is checked again
before returning the response. It creates no jobs, model calls, subscriptions or
durable work. Ordinary requests keep their original actor path.

[V150](../plowshare-server/src/main/resources/db/migration/V150__acting_context_audit.sql)
adds metadata-only proof-exchange audit. Admission waits for its successful audit
write; audit/storage errors propagate. Refused well-formed proofs record no subject,
secret or digest. Explicit closes audit their retained metadata. Transport syntax
refusals occur before this boundary.

Java `ActingClient`, the shared TypeScript/Node transport, Python's `ActingSelection`,
Go's `WithActingContext` and .NET's `RequestOptions` support explicit per-request
selection. Native operation contracts and portable validation fixtures are regenerated
through their owners. Open/close remain persistent-connection SDK capabilities.
Consent-management wire operations and user interfaces remain later work.

Focused mocked tests cover concurrent subjects, connection/session/actor ownership,
Personal isolation, malformed selections, expiry, revocation, unsupported operations,
relocation, close during reads and no actor fallback. A real segmented WebSocket test
runs the public Java SDK against the production handshake/channel/router with mocked
repositories, including close and reconnect. A selected PostgreSQL test verifies the
new migration, digest lookup and audit records; it does not substitute for mocked
permission tests. Native SDK conformance tests check selected/ordinary request
isolation through the shared transport fixture.

Validation passed for the completed slice: `format`, `formatCheck` and
`./gradlew check --max-workers=2`, including 5,579 default server tests and all 136
acting cases. The selected
`ApplicationDeploymentPersistenceTest.actingProofExchangeAuditContainsOnlyMetadata`
case passed with `-PfullDb`; it verifies V150 and actual proof/audit row mapping.
`./gradlew sdkCheck --max-workers=2` verifies generated contracts, strict native
builds, DTO fixtures, race tests and shared transport conformance. These checks
cover the first synchronous read; they do not establish delegated agent execution
or a live external integration.

## Fourth slice: retained Personal conversation turns

`acting.turn.start`, selected with `actingFor`, accepts `requestId`, `agent`, `task`
and an optional `conversation`. Omission opens a subject-owned Personal
conversation using the configured finite conversation budget. A continuation must
name a conversation previously bound to the same Application, subject, token and
admitted Personal ceiling. The concrete exported agent resolves from the subject's
Personal definitions without a bridge session or client filesystem.

`PersonalActingWork` reserves the UUID under the consent transaction's shared lock,
commits the owning conversation and authority binding, then submits through the
existing `Turn`, `JobStore` and `JobRuntime`. It never substitutes the subject for
the transport actor. Migration V151 retains Application/actor/subject/grant/proof
metadata and a Personal project **row identity**. Receipts and binding tombstones
survive history deletion, so forgetting a conversation cannot make its request
UUID executable again. Tasks and credentials are absent from these metadata tables.

The job ID is committed before the executor can start. Duplicate starts return the
original receipt; changed parameters or authority refuse. `acting.turn.receipt` is
read only. `ADMITTED`, `STARTED` and `UNCERTAIN` are evidence of admission, not a
lease or permission to retry. Process restart does not resume or replay these jobs;
the existing runtime is in memory, while receipts and conversation history persist.
Job completion records `FINISHED` for every terminal outcome, including refusal or
cancellation. Result/history reads remain independently authorized.

The retained guard rechecks current consent, eligibility, account/Application state,
Personal ownership and the concrete agent definition at execution boundaries. It
also crosses the inference queue in `ChatRequest`: `LlmPool` checks immediately
before dispatch, and the runtime checks again before accepting output. Accounting
attribution is a separate immutable carrier, including SYSTEM processing. Temporary
context expiry, close and socket loss do not revoke an admitted job; token/grant
revocation or loss of live authority stops subsequent effects. Rotation that retains
the Application principal does not change the retained actor.

The execution profile admits exported roots with supported synchronous tools and
ordinary `calls:`/filesystem scopes. `RetainedAgent` rejects independent consumers
that have not yet gained retained authority; it does not add another permission
grant. The existing runtime, budget, turn cap, usage ownership, cancellation,
agent tool declarations, hooks, file instructions, provider grants and command
environment gate remain authoritative. Selected `job.status` and `job.cancel`
accept only jobs with matching retained provenance; subscriptions and direct
`agent.run` remain unsupported under acting selection.

## Fifth slice: standard tool checks and child agents

The retained profile is selected only for an explicit `RunAdmission`. Ordinary
server-owned SYSTEM workers keep their existing runtime and accounting paths;
refusing an acting request does not mutate the shared runtime. The bundled private `conversation_diagnostician`
declares `is-system: true`; the default is false and authored definitions cannot
declare this reserved key. Typed loader provenance, rather than a name, alias or
origin string, controls acceptance. User-authored namesakes remain ordinary.
The `diagnose-conversation` skill delegates with fresh `NEW` context and keeps
the independent verifier. Application discovery/admission and
retained acting work refuse SYSTEM-classified definitions, including aliases and
Application attempts to resume a human-owned diagnostic conversation or use it
as a skill parent. The dedicated TUI `/diagnose` command is retired.
These exposure checks do not remove tools or add permission lookups to internal
SYSTEM execution.

`RunAdmission` carries a typed `RunAuthority` alongside its live admission check.
`PersonalRunAuthority` checks the fixed Personal home and resolves resource IDs
through `ResourceScopeRepository`. Unknown, global, other-project and other-user
resources refuse. The runtime checks the final hook-rewritten arguments immediately
before dispatch and rechecks scope and consent before accepting results. Standard
agent tool grants still decide what is offered; an acting token grants no tool.

Supported tools are `get_date`, `fetch`, `search`, `memory_read`, `memory_index`,
`conversation_list`, `conversation_chat`, `result_read`, `result_list`,
`todo_read`, `todo_write`, filesystem tools including `code_map`, `run`, and
`agent_run`. `memory_read` explicitly checks each referenced memory's owning scope;
its ordinary ID-based implementation alone is insufficient for retained work.
File providers receive the admitted definition's grants, the human subject and
no client session. Commands pass through the ordinary environment and hook gate.
New approval questions/continuations remain unavailable; a command needing one
refuses through the existing gate rather than obtaining implicit approval.

A child uses the existing `calls:` grant and caller-scoped delegate resolver; it
may be private. It inherits the same home, subject, retained authority, budget and
cancellation. Each execution pins its concrete definition and checks it again at
model dispatch and tool boundaries, including the queued model-dispatch check.
Compaction opens a durable child conversation linked to its parent and propagates
the retained check. A failed child-log write refuses instead of executing with an
unbound transcript. Child log stages, follower delivery and automatic folds remain
closed, as do skills, dynamic/external tools, implicit recall, information-processing
jobs, orchestration and inbox announcements. Those consumers must retain authority
in their own admission and recovery contracts before they can be enabled.

Mocked tests cover admission/recovery, changed UUID parameters and authority,
source/ownership changes, revocation, real turn/runtime cancellation, budget and
accounting retention, ordinary grant/hook enforcement, rewritten resource IDs,
resource scope changes, file-provider identity and child execution. The real model
queue boundary and a Java SDK/socket admission/reconnect path are also tested.
Two focused PostgreSQL cases from the durable-admission slice verify V151,
persisted mappings, concurrent admission, rollback and receipt survival after
physical history deletion. This tool-propagation slice changes no SQL/schema.

## 1. Start with the current request path

The Application's identity enters through the existing authentication path:

```text
Application token
  -> AuthFilter / TokenStore / ServiceCredentials
  -> HandleInterceptor copies the authenticated account onto the socket
  -> EventChannelHandler constructs Asking for each request
  -> FrameRouter checks project, account and Personal access
  -> FrameHandler calls the owning service
  -> service admits work and writes its owning records
  -> runtime, queues and delivery paths perform their later checks
```

[AuthFilter][auth] resolves authentication before the WebSocket upgrade.
[ServiceCredentials][credentials] identifies machine credentials and their
`@service/<token UUID>` principals. [HandleInterceptor][handshake] copies that
identity and session version onto the socket. [SocketAuthorization][socket-auth]
rechecks the socket's account session and closes revoked sessions.

[EventChannelHandler][events] creates [Asking][asking] with a session ID, one
account handle and a physical connection ID. [FrameRouter.route][router] runs
authorization before dispatch and filters listings afterwards. Its checks use
`asking.handle()` throughout. [AgentRunHandler][agent-frame], for example, passes
that same account into `Runs.Ask` as both caller and speaker.

There are no `actingFor`, `acting.context.*` or `acting.token.*` implementations
in the protocol, server or SDK source at this baseline. The Application identity
lifecycle exists; account delegation is new platform work.

## 2. Add a request subject without replacing the actor

The intended request path adds a decision before ordinary operation dispatch:

```text
Application-authenticated connection
  |
  +-- no actingFor -> existing service-account path
  |
  +-- actingFor.context
       -> resolve context on this exact connection
       -> recheck Application, grant, eligibility and proof
       -> build immutable request authority: actor + subject + delegation
       -> authorize the operation and resolve its actual resource scope
       -> dispatch with subject ownership and retained actor provenance
```

Keep the authenticated socket and [SessionRegistry][sessions] owned by the
Application. Never switch the socket handle to a human when a context opens, and
never store a connection-wide mutable "current user". Two requests on one socket
must be able to carry different subjects without affecting each other.

Proposed internal values are an immutable `RequestAuthority` containing the actor,
effective subject and optional admitted delegation, and a durable
`ActingRecord` identifying Application, grant, proof kind, proof record and optional
external subject. Names are provisional. The record must retain enough validated
scope information to prevent later grant or token changes from widening work that
was already admitted. Domain services receive these typed values, not raw
`actingFor` JSON. Carry authority explicitly across asynchronous boundaries.

There are three separate identifiers:

| Identifier | Existing or proposed | Purpose |
| --- | --- | --- |
| Socket session and physical connection ID | Existing | Authenticate the Application and own the live transport. |
| `actingFor.context` | Proposed | Select temporary subject authorization on that connection. |
| `incoming.receive.context` | Existing | Retain external message routing to instances/conversations and recover incoming tasks. |

[IncomingFrames][incoming-frames] and [BoardMessaging.receive][incoming-service]
already implement the third row. Its UUID is not a subject proof. Keep incoming
routing contexts and acting contexts distinct in the protocol, SDK types and
adapter state.

## 3. Map the implementation to its current owners

| Area | Existing implementation point | Required work |
| --- | --- | --- |
| Application authentication | [AuthFilter][auth], [TokenStore][tokens], [ServiceCredentials][credentials], [SocketAuthorization][socket-auth] | Retain the Application login. Verify acting proofs through a separate capability; a `psa_` credential must not become an ordinary human or Application login. |
| Application identity and token binding | [ApplicationIdentities][identities], [OwnedApplicationIdentities][owned-identities], [ApplicationIdentityRepository][identity-repository] | Resolve the authenticated principal to its exact owned Application, require ACTIVE state, and bind acting proofs to that Application and named grant. Do not bind by display handle. |
| Manifest declarations | [ApplicationServiceManifest][service-manifest], [WorkspaceApplicationPolicy.parse][manifest-policy], [RuntimeApplicationPackageValidator][package-validator] | Add one strict typed decoder for `acting` declarations and reuse it during deployment and live policy reads. Validate bounded unique names, supported proof kinds, role ceilings, Personal flag and owned-identity requirement. Existing parsers do not validate an `acting` policy. |
| Grant activation and rollback | [SourceApplicationDeployments][deployments], [JdbcApplicationDeploymentStore][deployment-commit] | Activate the validated grant declaration with the source head and receipt in the deployment transaction. Removing/narrowing a grant must affect rechecks; activating an older source must not restore authority absent from that active source. |
| Eligibility | [ProjectMembers][members] and its current membership queries | Add a specialist eligibility repository and typed rule evaluation for explicit accounts/project membership. Eligibility belongs to an exact Application/grant. Recheck on issuance, context opening and later authorized work. |
| User-issued acting tokens | [OwnedApplicationIdentities.issue][owned-identities] and [ApplicationIdentityRepository][identity-repository] are lifecycle precedents | Add owner-issued token service/repository with digests, expiry, requested scope, actor/grant binding, revocation and retained mutation receipts. The existing Application token issuer is administrator-only and must not issue human consent. |
| Context lifecycle | [Asking][asking], [EventChannelHandler][events], [SocketAuthorization][socket-auth] | Add a bounded context registry keyed to physical connection identity as well as actor. Open/close contexts over existing WS operations; close/displacement/revocation must discard the connection's contexts. Resolve/recheck on every delegated request. |
| Wire contract and dispatch | [Envelope][envelope], [FrameTypes][frame-types], [FrameRoutingConfig][routing-config], [FrameRouter][router] | Define typed request metadata for `actingFor`, decode it before business handlers and register an acting frame area. Dispatch direct account-management operations using the actual authenticated actor. Apply the delegated-operation policy before any side effect. |
| Project authorization and listings | [ProjectAuthorization.require][project-auth], [JdbcProjectMembers.role][member-roles], [ProjectListingFilter][listing-filter] | Combine subject role, grant ceiling, proof ceiling and actor's project role. Resolve resource IDs through the existing scope repository. Filter delegated listings using the same effective authority. Leave ordinary membership and service-only semantics intact. |
| Personal scope and caller resolution | [PersonalSpaces.home][personal-home], [PersonalAccess.check][personal-access], [CallerAccess][caller-access], [Callers][callers] | Admit the subject's Personal scope only when grant/proof permit it. Resolve subject-owned defaults and definitions. Separate actor-session ownership from subject-resource authority; do not give the service account Personal membership. |
| Work admission and execution | [Runs.start][runs], [JobStore][jobs], [JobRuntime][runtime], [RunExtras.Context][run-extras], [Orchestrations.Start][orchestration-start] | Retain delegation with each admitted unit of work and propagate it into child runs, tools, orchestration records and recovery. Add live authority checks at execution/effect boundaries rather than relying only on frame admission. |
| Incoming messages and routes | [IncomingFrames][incoming-frames], [BoardMessaging][incoming-service], [BoardMessagingRepository][message-repository], [ProjectMessageRouting][message-routing] | Retain subject ownership plus originating delegation on incoming tasks, messages and delayed delivery. Include delegation identity in request matching so the same subject/request UUID cannot be reinterpreted under another grant or actor. |
| Information admission and processing | [InformationContext][information-context], [InformationAccess][information-access], [InformationCatalogue][information-catalogue], [InformationLifecycle.Lease][information-lease], [JdbcInformationProcessingRepository][processing-authority] | Carry acting provenance into admissions/revisions and leases. Current queue authority checks know owner/project, not grant/proof; extend those repository-owned checks and stage fences. Enforce delegated restrictions on owner-only controls. |
| Events, streams and subscriptions | [EventChannelHandler.projectDelivery][delivery], [UsageSubscriptions][usage-subscriptions], [SocketAuthorization][socket-auth] | Authorize each delegated delivery/subscription against its own subject and retained delegation. The current event and usage paths derive authority from the socket/session account. Never broadcast subject data merely because the same Application socket opened another subject's context. |
| Usage and audit | [UsageAttribution][usage], [UsageExecutionStore][usage-store], [ServerAdministration.Audit][audit], [JdbcServiceAccountRepository.audit][audit-write] | Keep subject ownership and separate Application provenance. Add structured delegation audit details without secrets. Existing account-audit DTOs cannot express all grant/proof/context events. Preserve existing SYSTEM processing attribution. |
| Public SDKs and clients | [TypeScript transport][ts-transport], [operation catalog][ts-catalog], [schema generator][schemas], [SDK generator][sdk-generator], client identity controls | Add typed acting operations and immutable per-request context selection. Update all six SDKs and generated contracts together, then add owner-facing Connected services and administrator eligibility controls. |

Repository/store contracts own all new SQL. Authentication, grant validation,
proof verification, context resolution and policy checks should have narrow
interfaces composed through configuration. Pure immutable authority values do not
need artificial interfaces. Existing concrete coupling is not permission to pass
JDBC or generic JSON into new domain services.

The new capability can live in a focused server `acting` package. Candidate
contracts are `ActingTokens` with an `ActingTokenRepository`, `ActingContexts` for
the ephemeral registry, and `ActingAuthorization` for request and durable-work
checks. Grant/eligibility and acting-record persistence belong in specialist
repositories. A proposed `protocol.Acting` DTO family and `ws.ActingFrames` would
expose these capabilities. These names identify responsibilities, not a requirement
to create a framework or one interface per value.

The concrete SDK transport edits span the following owners:

| SDK | Existing code to update |
| --- | --- |
| TypeScript | [Envelope and request builder](../sdk/typescript/src/binding/envelope.ts#L44), [connection request](../sdk/typescript/src/binding/connection.ts#L351), [OperationTransport](../sdk/typescript/src/operations/response.ts#L65) and checked transport. Add common request options outside operation payloads. |
| Node | Reuses the TypeScript transport/contracts. Preserve the authenticated origin/account identity in [connection configuration](../sdk/node/src/connections.ts); a subject context is not another login profile. Direct integration credential provisioning remains explicit. |
| Java | [Plowshare.request](../sdk/java/src/main/java/io/aeyer/plowshare/sdk/Plowshare.java#L282), typed public client facades and common protocol Envelope. |
| Python | [Client.request](../sdk/python/src/plowshare/client.py#L171), generated request DTO methods and private codec. |
| Go | [Client.request](../sdk/go/client.go#L163), generated operation methods and private codec. |
| .NET | [RequestTypedAsync and RequestWireAsync](../sdk/dotnet/Plowshare.Sdk/Client.cs#L81), generated operation methods and private codec. |

For human controls, use the existing [shared Application credential lifecycle
client](../sdk/typescript/src/operations/application-identity-client.ts) as the
receipt-handling precedent. The [console identity
screen](../plowshare-console/src/screens/application-identities.ts) and [desktop
identity control](../plowshare-desktop/src/renderer/application-identity.ts) show
current secret display/dismissal behavior. Add separate owner-facing acting-token
controls; do not reuse their administrator-only authorization. CLI and TUI command
vocabulary comes from the shared TypeScript operation catalog. Update desktop
`shared.ts`/request schemas and console `transport.ts`/transport schemas through
the schema generator when adding those actions.

## 4. Important details the current code exposes

### Session ownership and subject ownership currently coincide

[CallerAccess.requireSession][caller-access] refuses a caller whose account differs
from the session owner. [Runs.start][runs] and conversation opening call that check.
Simply passing the subject as today's caller handle would therefore conflict with
an Application-owned connection.

Introduce an explicit authority-aware path: authenticate the transport against the
actor, then authorize the subject's selected resources. Preserve the existing
cross-account session refusal for direct requests. Do not turn it into a blanket
exception for service accounts. [Callers.callerForConversation][callers] also derives
definition context from the durable conversation owner; admission must prove that
the requested subject may use that conversation before adopting its owner context.

[AgentsConfig.runProviders][providers] uses the owner to choose filesystem providers
and rooting. Subject authorization must not accidentally attach the bridge's local
filesystem, hooks or machine session to the human's Personal work. Include
FileStore, client-private projects and file-channel operations in the operation
inventory; support them explicitly or refuse them under delegation.

### Context expiry is different from durable authorization

An acting context is temporary and ends with its socket. Admitted work must retain
its own acting record rather than depending on the live context UUID. Otherwise a
disconnect would discard the information needed to authorize later work.

For the first `acting-token` verifier, later work checks the durable token record's
expiry/revocation, current subject access, eligibility, active manifest grant and
Application principal validity. Context expiry stops use of that context for new
requests. Rotation preserves the Application token UUID/principal while retiring
old sessions; reuse that existing lifecycle instead of storing the bearer secret
in work records. Future federation remains separate work.

### A front-door check does not constrain all later tools

[ProjectAuthorization][project-auth] checks the external operation. An allowed
`agent.run` can subsequently invoke internal services through
[JobRuntime][runtime] and [RunExtras.Context][run-extras]. Delegation must follow
those calls; otherwise checking the subject alone could discard the actor/grant
ceiling after admission.

Inventory both WS operations and transitive capabilities. Enforce the ADR's
restrictions at their owning services as well as transport admission: account and
token management, grant/identity management, approvals, membership changes,
schedule/trigger creation, owner-only information controls and future type
management. Agent tool grants continue to narrow authority. They cannot restore
operations forbidden by delegation.

### Provenance belongs to admissions, not just conversations

A conversation can receive one delegated turn and later a direct human turn.
Preserve subject ownership and attach the acting record to each admitted job,
turn, message, orchestration and information revision as appropriate. A single
mutable actor field on a conversation would misattribute later work. Persist
references with the owning admission/intent transaction; propagate them into
descendants and recovery instead of reconstructing them from the currently open
socket or an external alias.

[BoardMessaging.receive][incoming-service] already compares repeated incoming
request UUIDs against retained work. Extend that comparison with the admitted
delegation and scope; do not bypass the existing receipt lifecycle. Metadata
reads used to reconcile uncertain mutations must not resubmit effects.

### Information has a second authorization boundary in its queue

[InformationAccess][information-access] rechecks selected resource access, while
[JdbcInformationProcessingRepository][processing-authority] selects/renews/fences
queued processing using owner and project authority. Both layers need the acting
record. Updating only `InformationContext.account` would let processing retain the
human's access after the Application grant or proof was revoked.

The ADR's authored-knowledge section is contingent on another proposal. At this
snapshot, [InformationOperations][information-operations] does not register
`information.content.revise` or content-type lifecycle operations. Map delegation
onto the current information lifecycle first; add the typed-revision rules when
those contracts exist. Do not implement authored knowledge as hidden delegation
scope.

### Usage ownership and model attribution need separate fields

[UsageAttribution][usage] carries one account handle or SYSTEM status, plus execution
lineage. Add delegation provenance without overwriting that account or changing a
retained execution's owner. Ordinary delegated model work belongs to the subject.

[ADR 0025](decisions/0025-service-account-attribution-and-spending-pools.md#relationship-to-acting-grants-and-system)
explicitly preserves existing internal document processing as SYSTEM-attributed;
the admission owner still controls authorization and allowance. The delegation
plan must preserve that distinction. Spending pools are separate scope. Clarify
ADR 0026's broad accounting wording before implementing those fields.

## 5. Remaining contract details before persistence and transport

The internal policy now has explicit role ceilings and refuses every delegated
check while disabled. The following wire and lifecycle details remain for later
slices; they do not require compatibility machinery.

| Detail | Current gap | Proposed resolution for implementation planning |
| --- | --- | --- |
| Subject token ceilings | Internal issue requests now carry explicit role, project subset and Personal consent. | Preserve these fields in the future wire/SDK contracts; validate against the active grant and current account access. |
| Mutation receipt reads | Internal metadata receipts are retained and scoped to the original human submitter with request fingerprint matching. | Define the public receipt-read operation alongside the other wire/SDK contracts. Secret material is returned only at initial issuance. |
| Request metadata | The ADR names `actingFor` without pinning its wire placement. | Put a typed context reference in common request metadata, decoded before operation payloads. Update Envelope, transport builders and all SDK request options together. |
| Context limits | Maximum context lifetime/count and closed-context behavior are unspecified. | Make limits explicit server configuration; bind to physical connection, validate UUIDs and distinguish expired/refused from successful opening. Reopening must not replay business requests. |
| Direct owner access after revocation | The ADR mentions rechecking receipts/work but also gives the subject ownership and cancellation rights. | Distinguish continued delegated execution/delivery from a human directly reading or cancelling their own retained work. Record the precise rule before changing read paths. |
| Disabled feature behavior | The ADR describes refusing new contexts but does not fully define existing context/work checks. | Define the effect of disabling delegation at request and later execution boundaries, including retained reconciliation reads. |
| Operation inventory | A list of refused families does not classify every existing operation or internal tool. | Produce an explicit admission matrix. Initially refuse any unclassified delegated capability; include indirect actions performed by agents and background callbacks. |
| Accounting | Subject charging language intersects with existing SYSTEM pipelines and ADR 0025. | Keep admission owner, model attribution and Application provenance distinct; document the SYSTEM exception explicitly. |

Keep identity linking narrow. Direct provisioning to an integration configured for
one account identifies the subject through the token. A multi-user adapter must
authenticate and bind its external callers before selecting a context, but a
self-service external-linking ceremony is not required for the first platform
slice. No server API may trust a submitted external identity as account authority.

## 6. Implementation order and acceptance gates

1. **Typed authority and policy.** Implemented internally through the types and
   policy described above, with mocked membership and live-state sources. The
   permission matrix checks each independent ceiling and refusal. There is no
   request routing in this slice; persistence follows in step 2.
2. **Declarations and consent lifecycle.** Implemented internally as described
   above, including new migration V149 and source-head activation. Public wire
   operations, context proof verification and client adoption remain later work.
3. **One complete synchronous request path.** Implemented for `conversation.turns`,
   including context exchange, strict metadata, live scope checks and SDK selection.
   Background-producing and other delegated operations remain refused.
4. **One complete durable path.** Implemented for subject-owned Personal
   conversations, standard synchronous tools and child agents as described above.
   Independent background work and delivery remain explicitly closed.
5. **Remaining durable consumers.** Extend incoming messages, routes, delayed
   events, orchestrations, information processing and delivery/subscriptions using
   their existing owning records. Each capability becomes delegated only after
   its admission, child work, later checks, receipts and reads preserve authority.
6. **SDKs and direct human controls.** Ship all six SDKs with typed operations and
   immutable context selection on a shared connection. Add Connected services,
   receipt recovery, revocation and administrator eligibility controls to clients.
   Regenerate contract outputs and client IPC schemas through their owners.
7. **Public-consumer acceptance.** Use both a chat fixture and a non-chat fixture
   through configured public SDK connections. Prove ordinary-user and subject-user
   paths, direct credential provisioning, Personal isolation and independent
   connection/server placement. Adapter adoption follows the platform contracts.

Steps may have internal development branches, but the user-facing 0.0.1 release
can update server and clients together. No legacy protocol translation layer or
compatibility deployment project is needed.

## 7. Verification map

| Assertions | Existing test home or fixture to extend |
| --- | --- |
| Invalid token/grant declarations, active identity, issuance receipts and rotation | `OwnedApplicationIdentitiesTest`, `SourceApplicationDeploymentsTest`, `WorkspaceApplicationPolicyTest`; add acting lifecycle tests beside them. |
| Actor/subject isolation, malformed context metadata, closed context, no-context service behavior | `FrameRouterTest`, `CallerParityTest`, `SessionRegistryTest`; add real WS context tests with fake repositories and a new mocked `ActingAuthorizationTest`. |
| Every project ceiling and Personal restriction | New mocked policy tests and `RunsTest` for behavior; existing `ProjectAuthorizationTest` and `PersonalSpacesTest` are `full-db` and are relevant only when actual scope persistence needs testing. |
| Durable message/context isolation and UUID reuse under changed delegation | `IncomingRequestsTest` and new mocked messaging-authority tests for behavior; `BoardMessagingTest` is `full-db` for actual retained routing/receipt assertions. Preserve the distinct routing context. |
| Queued information checks after revocation | `InformationProcessingAuthorityTest` for behavior with mocked dependencies; focused database tests for lease/transaction races only. |
| Subject ownership, Application provenance and preserved SYSTEM attribution | `UsageAttributionTest` and `ServiceUsageOwnershipTest` for behavior; `RunAttributionTest` is `full-db` for persisted lineage. Extend structured audit and history tests. |
| Same connection, concurrent subjects, invalid inputs and no replay on uncertain delivery | TypeScript binding/operation tests, Java public SDK tests and `scripts/sdk-conformance.mjs` across supported SDKs. |

Default tests mock database repositories. New database tests are justified only
for actual PostgreSQL constraints, digest/receipt races, transaction ordering,
lease fences and durable round trips. Tag those `full-db`, explain the specific
reason, then run only their selected classes with `-PfullDb --tests`. Do not start
PostgreSQL for the policy matrix or WebSocket context registry.

After implementation changes, run the affected focused tests, `format`,
`formatCheck` and `check`. Shared SDK/contract edits additionally require
`sdkCheck`; package changes require `sdkPackageCheck`. Generated operation schemas
come from `sdk/typescript/scripts/generate-operation-schemas.mjs`; native SDK DTOs
and catalogs come from `scripts/generate-sdk-contracts.mjs`. Never edit their
outputs by hand.

The original source audit used source searches and document checks. The first
slice adds focused mocked Java tests; it does not establish runtime acceptance
or complete the proposed feature.

<!-- Source references are pinned to the baseline recorded above. -->

[auth]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/auth/AuthFilter.java#L313
[credentials]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/auth/ServiceCredentials.java#L9
[handshake]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/HandleInterceptor.java#L18
[socket-auth]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/SocketAuthorization.java#L29
[events]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/EventChannelHandler.java#L526
[asking]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/Asking.java#L54
[router]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/FrameRouter.java#L174
[agent-frame]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/AgentRunHandler.java#L55
[sessions]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/session/SessionRegistry.java#L228
[incoming-frames]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/IncomingFrames.java#L47
[incoming-service]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/board/BoardMessaging.java#L465
[message-repository]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/board/BoardMessagingRepository.java#L16
[message-routing]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/board/ProjectMessageRouting.java#L70
[tokens]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/auth/TokenStore.java#L460
[identities]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/applications/ApplicationIdentities.java#L7
[owned-identities]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/applications/OwnedApplicationIdentities.java#L136
[identity-repository]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/applications/ApplicationIdentityRepository.java#L11
[service-manifest]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/applications/ApplicationServiceManifest.java#L21
[manifest-policy]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/WorkspaceApplicationPolicy.java#L87
[package-validator]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/RuntimeApplicationPackageValidator.java#L38
[deployments]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/applications/SourceApplicationDeployments.java#L21
[deployment-commit]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/JdbcApplicationDeploymentStore.java#L163
[members]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/ProjectMembers.java#L11
[frame-types]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/FrameTypes.java#L35
[routing-config]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/FrameRoutingConfig.java#L66
[project-auth]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/ProjectAuthorization.java#L267
[member-roles]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/JdbcProjectMembers.java#L129
[listing-filter]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/ProjectListingFilter.java#L7
[personal-home]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/personal/PersonalSpaces.java#L85
[personal-access]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/personal/PersonalAccess.java#L34
[caller-access]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/CallerAccess.java#L20
[callers]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/Callers.java#L208
[runs]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/Runs.java#L244
[jobs]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/JobStore.java#L98
[runtime]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/JobRuntime.java#L146
[run-extras]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/RunExtras.java#L64
[providers]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/AgentsConfig.java#L493
[orchestration-start]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/Orchestrations.java#L232
[information-context]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationContext.java#L8
[information-access]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationAccess.java#L46
[information-catalogue]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationCatalogue.java#L116
[information-lease]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationLifecycle.java#L46
[processing-authority]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/JdbcInformationProcessingRepository.java#L19
[delivery]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/EventChannelHandler.java#L717
[usage-subscriptions]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/UsageSubscriptions.java#L56
[usage]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/accounting/UsageAttribution.java#L10
[usage-store]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/accounting/UsageExecutionStore.java#L26
[audit]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/auth/ServerAdministration.java#L55
[audit-write]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/auth/JdbcServiceAccountRepository.java#L64
[envelope]: ../plowshare-protocol/src/main/java/io/aeyer/plowshare/protocol/frames/Envelope.java#L78
[information-operations]: ../plowshare-protocol/src/main/java/io/aeyer/plowshare/protocol/frames/InformationOperations.java#L10
[ts-transport]: ../sdk/typescript/src/operations/transport.ts#L12
[ts-catalog]: ../sdk/typescript/src/operations/catalog.ts#L170
[schemas]: ../sdk/typescript/scripts/generate-operation-schemas.mjs#L10
[sdk-generator]: ../scripts/generate-sdk-contracts.mjs#L19
