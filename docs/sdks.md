# Plowshare SDKs

All variants live under [sdk/](../sdk/README.md). External adapters live under
[integrations/](../integrations/README.md); `extensions/` contains services that
extend platform capabilities. Published package names and Gradle task names
remain stable.

The implementation order is JavaScript/TypeScript, the Java/JVM SDK, Python,
C#, then Go. These are clients of Plowshare's `plowshare-v1` WebSocket protocol.
The A2A adapter is a separate process on the Java SDK. Follow the
[A2A sending manual](a2a-sending.md) to configure a peer and send work.

| Language | Package | Runtime | Scope |
| --- | --- | --- | --- |
| JavaScript/TypeScript | `plowshare-client-ts`, `plowshare-client-node` | Node 22.12+ for the Node transport | Neutral typed operation catalog plus a ready-to-connect Node SDK |
| Java/JVM | `io.aeyer:plowshare-sdk` | Java 21+ | Validated DTO clients for server operations; private WebSocket transport |
| Python | `plowshare-sdk` / import `plowshare` | Python 3.11+ | Async generated DTO requests and results |
| C# | `Plowshare.Sdk` | .NET 8+ | Async generated DTO requests and results |
| Go | `io.aeyer/plowshare/sdk` | Go 1.23+ | Context-aware generated DTO operations |

Every registered operation in the generated catalog
has a native request and response contract. Java provides validated DTO facades;
TypeScript and the native SDKs provide typed operation APIs. Raw wire envelopes
remain private to their owning transports and codecs. Follow the
[native SDK standard](native-sdk-standards.md) for Python, Go and .NET changes.
Filesystem/process providers remain separate platform integrations.

[Segmented SDK transport](decisions/0009-segmented-sdk-message-transport.md) is the
default on new event connections: the upgrade must select `plowshare-segments-v1`.
Requests, replies and pushes are assembled from 64 KiB ranges before normal typed
decoding. Relay TEXT DTOs accept the portable 50 MiB UTF-8 ceiling; server admission
uses `plowshare.relay.max-text-bytes` (5 MiB by default). See [Relay configuration](relay.md).
The encoded inner message is bounded at 320 MiB; domain limits still
apply. Unsupported negotiation fails before an application request is submitted.

Explicit legacy compatibility is available through these connection options:

| SDK | Legacy option |
| --- | --- |
| Java | `Plowshare.TransportMode.LEGACY` on the six-argument `connect` overload; `WsServerClient` accepts the mode in its third constructor argument |
| Node | `connectPlowshare({ ..., transport: 'legacy' })` |
| Python | `Client.connect(..., legacy_transport=True)` |
| Go | `Options{LegacyTransport: true}` |
| .NET | `Client.ConnectAsync(..., legacyTransport: true)` |

Legacy messages retain the 1 MiB allowance. No SDK silently falls back or resends
a mutation after negotiation or delivery failure. Neutral TypeScript consumers
wrap their explicitly negotiated platform socket with `packetSocket`; the Node
SDK and console/desktop compositions supply platform codecs and timers.

## Concurrent request dispatch

New public SDK connections select `concurrent-v1` with a private
`transport.dispatch` exchange before submitting application operations. The server
acknowledges the mode and its pending-request/ID bounds. This selection is
independent of segmented versus legacy message encoding. An unsupported server
fails opening clearly; the SDK never silently downgrades or replays a request.

| SDK | Explicit ordered dispatch |
| --- | --- |
| Java | `Plowshare.DispatchMode.ORDERED` on the transport/dispatch overload |
| Node | `connectPlowshare({ ..., dispatchMode: 'ordered' })`; platform sessions accept the same option |
| Python | `Client.connect(..., dispatch_mode="ordered")` |
| Go | `Options{DispatchMode: OrderedDispatch}` |
| .NET | `Client.ConnectAsync(..., dispatchMode: DispatchMode.Ordered)` |

Ordered dispatch preserves the previous server scheduling policy. Connections
without selection retain that policy too. Concurrent dispatch permits independent
responses to overtake each other; conflicting resources and unaudited operations
retain explicit ordering. Neither a request ID nor concurrent admission grants
access. Authentication, account/scope checks and disclosure fences remain on the
server. Physical logical-message writes remain serialized, including large replies.

Java offers `connectAsync`, `closeAsync`, `AsyncServerClient` and typed `*Async`
facade methods returning `CompletionStage`. Blocking methods await the same
exchange engine. Completions run off the socket reader and coordination locks;
reserved completion capacity bounds callbacks that block. `RequestOptions`
provides a per-call deadline and `RequestCancellation` detaches a caller without
cancelling durable server work.

TypeScript/Node expose `closeAsync()` to await transport-owned physical buffers;
`close()` retains immediate stop semantics. Python `close`, .NET `DisposeAsync`,
and Go `Close` share their shutdown drain and wait for admitted converters,
writers and byte leases, without waiting for application code to consume results.

TypeScript requests accept local `signal` and `timeoutMs` options; neither appears
in the wire payload or acting selection. `timeoutMs` overrides the connection's
default for that call. Neutral TypeScript compositions supply a deadline scheduler
and await `ready()` when selecting concurrent mode. Python accepts an optional
per-request timeout and task cancellation; .NET uses cancellation tokens, and Go
uses contexts. Admission includes calls waiting for the writer and retained
physical transfers, with count and byte limits. Cancellation before a possible
write is `NOT_SUBMITTED`; after a possible write the outcome remains `UNKNOWN`.
Late replies cannot settle a later request, and cancellation never triggers replay.

See the [dispatch implementation and validation record](async-request-dispatch-validation.md)
for scope, checks and rollout status.

Provider declarations also share the [typed schema vocabulary](tool-schemas.md)
with model dispatch. All SDKs support recursive arguments and schema-pinned
requests while retaining the scalar provider facade.

## Shared contract

- Credentials travel in the WebSocket upgrade's bearer header; authenticated
  redirects are refused. Application operations use `/v1/events`, never an HTTP fallback.
- WebSocket clients receive caller-managed bearers. Optional native `Credentials`
  and HTTP authentication capabilities manage shared user sessions separately;
  they never reconnect or replay application operations.
- Each request has an ID, type and protocol version. Replies are correlated by ID
  and checked for matching type/version and a known outcome code.
- Native Python/C#/Go replies contain a known code, optional text and a validated
  operation-specific payload. Unknown output fields are projected away; omitted
  fields remain distinct from explicit null. Refusal codes remain replies until `requirePayload` / `require_payload` /
  `RequirePayload` is explicitly called. `ACCEPTED` is pending work.
- At most 64 requests are pending. Network clients have a 30-second default deadline.
  Neutral TS transports must inject their own deadline scheduler when needed.
- No client automatically reconnects and replays application requests. Delivery
  failures distinguish `NOT_SUBMITTED`, `UNKNOWN`, and `INVALID_RESPONSE` (idiomatic
  enum names in .NET). Cancellation after submission preserves uncertainty.
- Native Python/C#/Go push queues hold 256 notifications and count overflow. Node
  delivers notifications through `onPush`; the neutral job lifecycle has its own
  bounded event buffer. Reconcile missing notifications using durable WS status.

A normal `request` resolves the server outcome, not the eventual completion of a
job. Follow the returned job identity through status reads. Retain an outgoing
request UUID before submitting; recovery using the same UUID and identical payload
is an explicit caller decision, never a transport retry. Cancellation of a waiting
SDK call doesn't cancel remote work; `job.cancel` / `outgoing.cancel` are explicit.

`information.search` returns a list of passages, each carrying cosine `distance`
and a `chunk` with paragraph identity and section/chapter placement. Synthetic
structural titles carry `synthetic: true` and no title; an unplaced passage has
an empty placement. This scoped WebSocket result differs from the legacy HTTP
document retrieval wrapper. All SDKs decode the scoped result before returning
it to application code.

Filesystem lending, process execution, binary uploads and Git transfers remain
platform integrations. Calling an operation does not install its provider
or assert that a remote system has an accessible filesystem.

## Portable information

All SDKs expose typed `information.interchange.capabilities`, `.export`, `.readable`,
`.import` and `.origin` operations. Use explicit information scope and exact revision
identities; import needs a stable request UUID, explicit destination/type/reuse maps
and a relationship-restoration choice. The [portable exchange guide](portable-information.md)
explains byte preservation, unverified foreign provenance, collisions and the
`explicit_only` processing policy. Node's `plowshare-client-node/interchange` exports
file helpers and the existing byte/crypto codec. Helpers return bytes/relative paths;
the caller owns filesystem access and workspace fencing.

## Explicit execution scope

External systems configure a named project and use an Application-owned service
credential (or another scoped service token). SDK admission never derives a runtime
destination from the bearer or socket session.

`agent.run` requires a non-null, nonblank `project` for standalone or fresh
conversation work, including `newConversation:true`. To continue an existing
conversation, supply `conversation` and omit `project`: the retained conversation
owns its scope. Supplying both selectors is refused. `conversation.open` and
`orchestration.start` also require an explicit project. Read-only
`conversation.list` and `conversation.latest` require an explicit selector:
a project name or `project:null` for Global history. Java image uploads require
their destination project. Missing/null execution destinations are refused locally
before submitting the operation.
These checks apply with human credentials as well as service credentials.

```ts
await client.request('agent.run', {
  project: configuredProject,
  agent: 'interlocutor',
  task: 'Read the current runbook and retain an answer with evidence.',
  newConversation: true,
});
```

Information operations use an explicit `{ kind: 'project', project:
configuredProject, includeGlobal: false }` scope. An Application manager configures
types and policies; a CONTRIBUTOR service identity ingests and classifies records,
then discovers, traverses and consumes information within its grants. Project
membership and token ceilings remain server-enforced.

Personal clients must deliberately select their own Personal project `name`
returned by `project.list`; they cannot use omission/null as an execution shortcut.
This is a breaking
admission change for callers relying on server defaults: retain the selected
project in configuration and pass it explicitly, or use a conversation ID for
continuation. Existing global setup/memory contracts remain unchanged. Server
wire defaults are unchanged; no record is moved and no retry is introduced.
Explicit reply-selector checks still reject a foreign project without replay.

## Server FileStores

`filestore.list` accepts an empty request and returns `stores`, each with an
`alias` and the authenticated account's `role` (`VIEWER`, `CONTRIBUTOR` or
`MANAGER`). It omits stores without an account grant, host paths and other
accounts. An unconfigured registry refuses the request; a configured registry
with no grants returns an empty list. Discovery reads the current configuration;
file operations still recheck grants, paths and physical roots before use.

Desktop Application creation and deployment select aliases from this catalogue.
Source placement requires MANAGER access; writable-area references use granted
stores. Relative paths are entered separately. All SDK languages expose this
operation; Java also provides `ApplicationClient.stores()`. The CLI exposes
`filestore list` for server discovery; `filestore status`, `setup`, `default` and
`resolve` manage the local registry.

## Application source deployment

All SDKs expose `application.deploy`, `application.activate`,
`application.deployment.status` and `application.deployment.receipt` with typed
requests/results and the same package limits. Java uses `ApplicationClient` and
`ApplicationDeployment` records; TypeScript/Node uses typed operations, and the
native SDKs generate DTOs from that shared operation graph. Node additionally
exports `applicationFiles(directory)` from `plowshare-client-node/applications`
for a bounded folder snapshot. Other callers supply the explicit `{path,text}`
file list through their typed request.

Retain a new request UUID before mutation. Read status for the expected active
revision, use `null` for first deployment, and inspect the receipt after uncertain
delivery. No SDK automatically repeats a submission. Only server administrators
can deploy; FileStore grants and ordinary Application permissions remain explicit.
See [deployment and recovery](projects.md#deploy-update-and-activate-an-application)
for the complete lifecycle.

## Build and verify

Install Java 21, Node 22.12+, the pinned pnpm, Python 3.11+, .NET SDK 8+ and Go 1.23+.
Prepare the Python environment from the repository root:

```sh
python3 -m venv build/sdk-python-env
build/sdk-python-env/bin/python -m pip install 'websockets>=17,<18' 'setuptools>=77' 'build==1.6.1' 'mypy==2.4.0' 'ruff==0.16.10'
./gradlew --no-daemon sdkCheck
./gradlew --no-daemon sdkDistributions
./gradlew --no-daemon sdkPackageCheck
```

`sdkCheck` runs strict Python mypy, .NET nullable/warnings-as-errors compilation,
Go vet and race-enabled DTO tests against shared canonical boundary examples.
It compiles the native clients and exercises real WebSocket upgrades,
bearer headers, redirect refusal, concurrent out-of-order replies, refusals,
projection of future output fields, omitted/null fields, malformed nested DTOs and
envelopes, validated push delivery, deadlines, disconnects,
and caller cancellation/close after submission. The fixture counts wire requests
to reject replay. Go conformance runs with the race detector. Java's separate SDK
checks also run. `sdkPackageCheck` installs the npm, Python, NuGet and Go
distributions into fresh consumers, checks TypeScript declarations and repeats the WebSocket fixture using
the installed artifacts. The authentication fixture also exercises installed Java jars
and all five native stores against one shared credential directory. This task may download runtime/build dependencies.
These checks prove client transport behavior against the controlled fixture; it does not claim deployment or model-provider validation.

Native toolchains are required by these explicit SDK tasks. Ordinary `check`
continues to verify existing Java/TS applications without requiring .NET or Go;
the existing server CI jobs don't yet run `sdkCheck`. No toolchain is installed
implicitly. Overrides are `PLOWSHARE_SDK_PYTHON`, `PLOWSHARE_SDK_DOTNET`,
`PLOWSHARE_SDK_GO`, `PLOWSHARE_SDK_GOFMT` and `PLOWSHARE_SDK_PNPM`. The build also
recognizes a local .NET SDK at `build/dotnet/dotnet`. NuGet/CLI state stays in
`build/nuget` and `build/dotnet-home`.

Regenerate catalogs after changing server operations/outcome codes:

```sh
./gradlew :plowshare-client-ts:clientBuild
node scripts/generate-sdk-contracts.mjs
```

Local npm archives, Python wheel/sdist, NuGet package/symbols and the Go source
archive are written to `build/distributions`. Java's jar, sources and javadoc are
under `sdk/java/build/libs`; its Maven POM is under
`sdk/java/build/publications/sdk`; its companion protocol artifacts are
under `plowshare-protocol/build/libs` and `plowshare-protocol/build/publications/protocol`.
Nothing is published remotely. Go discovery hosting is not configured: use the
[documented local replace directive](../sdk/go/README.md) until release
hosting is selected.

## External integration runtime

The [shared external integration runtime](../integrations/runtime/README.md)
and [first HA adapter](../integrations/home-assistant/README.md) are implemented
as separate processes above the current SDK. The runtime owns
binding configuration, optional JavaScript routing, permissions, journals and
Plowshare delivery. Home Assistant is its first adapter, supplying selected states,
actions and events through HA WebSocket. HA remains an ordinary project with its
own agents, skills, orchestrations and user-defined mappings.

The runtime and HA pipeline design
and implementation plan
build the shared runtime before the HA adapter. Reads/actions use existing
protocol-neutral outgoing work; events use `orchestration.start`/receipt recovery.
JS returns validated effect plans that the external runtime records before dispatch.
This requires no Plowshare core runtime/API/tool/database modifications. The
separately requested interlocutor `run` grant is independent. The runtime/HA
fixtures, package checks and example resource loaders pass; live HA
acceptance remains pending. Existing claim-recovery limits remain explicit. The
runtime has opt-in retention, adjacent equivalent-reading coalescing and held
thresholds with explicit restart/resync/gap resets. Opt-in acknowledged-context
feedback suppression preserves observations while preventing pipeline feedback.
Deeper causal ancestry and other event types remain later work.

## A2A receipt status

Messaging and Skills are implemented, and the separate Java A2A adapter now supports
inbound text messages, durable tasks, context continuity, task reads and cancellation.
It publishes a public Agent Card from the selected exported agent's granted catalog.
Follow the [A2A receiving manual](a2a-receiving.md) for configuration, authentication,
explicit skill commands and the supported subset. Task follow-ups, streaming, task
listing, push notifications and binary/data inputs remain deferred.

## Machine identity

Integrations can authenticate with an expiring `pss_` bearer issued to a [Plowshare service account](server-administration.md#service-accounts-and-scoped-tokens). Grant the service account the required server project roles and issue a token with project ceilings. Use the same token identity across integration restarts and rotate it to retain owned work and ingress receipts. Service tokens have no password login, refresh credential or Personal space; send an explicit project or an existing resource in an allowed project. Information requests include generally readable knowledge by default; use `includeGlobal:false` for project-only discovery. Project grants, scope ceilings, expiry and revocation apply to subsequent execution as well as SDK requests.

Event producers use the [explicit event payload contracts](event-payloads.md).
Deployments must pass the restored-copy compatibility preflight before typed
event reads are enabled; arbitrary manual event objects are no longer accepted.

### Node connection configuration

`plowshare-client-node/connections` owns the version 1 local registry, strict
configuration decoding, canonical origin/account storage keys, selection precedence,
private atomic writes and cross-process locks. `Connections` implements
`ConnectionRegistry`; `resolveConnection` combines explicit selectors, environment
and saved selection while refusing identity conflicts. `Credentials` accepts an
explicit account as its fourth constructor argument and keeps refresh locking and
uncertain-renewal fencing within that scope. See [client login](client-login.md).

### Python shared user login

Python exports `Authentication`, `HttpAuthentication`, immutable auth/session DTOs
and `Credentials`. `Credentials.resolve(origin, account=..., connection=...)`
selects the native registry without copying credential handling into consumers.
`authenticated()` checks saved-session standing and renews only after explicit
401; `session()` is a local read, not proof of current authority. Login, refresh,
status and logout use existing public HTTP auth boundaries. Application operations
still use `Client.connect(origin, bearer)` over WebSocket with no implicit retry.

The version-1 files, account keys, locks and migration/logout fences are shared
with Node. Renewal persists before access reaches a consumer; ambiguous delivery,
cancellation and interrupted persistence require explicit login. See the
[Python SDK guide](../sdk/python/README.md#shared-user-login) and
[ADR 0035](decisions/0035-python-sdk-shared-login.md) for selection and recovery.

## Relay topic ports

All SDKs expose typed `relay.publish`, `relay.consume` and `relay.ack` operations.
They provide configured project-topic ingress and bounded leased consumer-group
egress. Java exposes them through `RelayClient`; TypeScript/Node expose
`Plowshare.publishRelay`, `consumeRelay` and `acknowledgeRelay` alongside the typed
`Plowshare.request` surface. Both package roots export the Relay request/batch and
filter-review types. Generated Python, Go and .NET request/result DTOs
include all three operations. See [Relay](relay.md#sdk-topic-ports) for explicit
acknowledgement, retention gaps, fencing and uncertain-delivery recovery, and
[message filtering](message-filtering.md) for external detector protocols.

## Application identity and scoped inspection

Every SDK exposes `application.identity.status`, `application.token.issue` and
`application.token.receipt`. Java provides `ApplicationClient.identity`, `issue`
and `tokenReceipt`; TypeScript uses typed operations and the shared
`ApplicationIdentityClient` control lifecycle; Python, Go and .NET have generated
typed requests/results. Issuance is an explicit administrator mutation, reveals a
secret once and requires a retained UUID. Never replay unknown delivery. Read the
original receipt and explicitly rotate the same token if its secret was lost.

`schedule.list` and `schedule.files` accept optional `project` selection. Scoped
reads check live VIEWER membership and include the project's registered server
schedule source/projection even when its owner differs from the caller. Service
tokens must select a granted project; omission does not grant global enumeration.
`schedule.list` returns effective pause/timing state, while `schedule.files` explains
source registration and refusals. Both are read-only and do not resume schedules.
Both operations return arrays with a default maximum of 50 rows. Optional `limit`
is 1..100; `after` is an exclusive stable key (`name` for `schedule.list`, unique
`internalName` for `schedule.files`). Each page checks current authority and does
not retain a cross-request snapshot. Java exposes bounded reads through
`ScheduleClient.listPage(project, after, limit)` and `filesPage(project, after, limit)`;
null cursors start at the beginning and null limits use 50. Its existing
`list(project)` and `files(project)` conveniences explicitly traverse pages of 100,
retain at most 10,000 rows, and fail rather than return partial data on refusal,
non-progress or overflow. Prefer pages for UI reads. See [schedule inspection
pages](scheduling.md#inspection-pages) for lookahead and concurrent-edit behavior.
`document.detail` likewise supports Information
project scope for metadata/outline; Java offers the scoped `describeDocument`
overload, and the other SDKs use their typed operation/request DTOs.

See [server-held provider credentials](provider-credentials.md) for the implemented
connection and grant management contract, SDK operations and OAuth callback boundary.

Durable provider assignments and delegated workers use the thirteen public
[hosted-scope operations](hosted-tool-scopes.md). All SDKs retain typed request/reply
parity, coordinate validation and no automatic mutation replay. Java provides
`HostedToolScopeClient`; TypeScript/Node reuse `ToolProvider` with a hosted
registration, including its original journal fence for reconciliation.

[Client management](provider-management-clients.md) documents the shared typed
`ProviderManagementClient`, private redacted recovery bookmarks, safe credential
entry and initial CLI/TUI/desktop/console controls. It uses the existing socket
and owning receipts; native MCP transports and host lifetime remain separate work.

## Acting contexts and retained Personal turns

The Application keeps its own authenticated connection. A separately provisioned
acting token is exchanged through `acting.context.open`; its typed request carries
`application`, `grant` and `proof: {kind: "acting-token", credential}`. Keep that
credential in private integration configuration. The reply contains `context`,
`subject` and `expiresAt`, with no reusable credential material. The server setting
`plowshare.acting.enabled` must be enabled explicitly.

`conversation.turns`, `acting.turn.start`, `acting.turn.receipt`, and the retained
turn's `job.status` / `job.cancel` support delegated selection. Select a context
on each request; omit the selection for an ordinary Application request:

| SDK | Per-request selection on the existing connection |
| --- | --- |
| Java | `new ActingClient(connection).turns(new ActingContext.Selection(context), conversation)` |
| TypeScript | `client.request('conversation.turns', {conversation}, {actingFor: {context}})` |
| Node | The shared TypeScript SDK or session `ask` accepts the same third options argument. |
| Python | `client.request(ConversationTurnsRequest(conversation=conversation), acting_for=ActingSelection(context))` |
| Go | `selected, err := WithActingContext(ctx, contextID)`, then `client.ConversationTurns(selected, request)` |
| .NET | `client.ConversationTurnsAsync(request, options: new RequestOptions(new ActingSelection(contextGuid)))` |

Open/close require a persistent connection and are not one-shot CLI commands.
Contexts last at most five minutes, are bound to the physical socket, and end on
close, disconnect or expiry. Reconnecting requires a new explicit exchange. Invalid
selections and unsupported delegated operations refuse; callers must not drop the
selection and retry as the Application. Current grants, consent, eligibility and
account permissions are rechecked for every read.

`acting.turn.start` requires a caller-owned UUID `requestId`, a `task`, and an
exported `agent` whose capabilities retain authority. Omit `conversation` to open
the subject's Personal conversation;
include a previously bound conversation for another turn. Retain the UUID before
submission. The receipt names the conversation, concrete agent, admission state
and nullable job ID. Recover lost responses using `acting.turn.receipt({requestId})`;
never automatically replay the mutation. Pending or UNCERTAIN receipts after a
restart do not authorize resubmission. Java exposes `ActingClient.start` and
`receipt`; the generated Python/Go/.NET operations and shared TypeScript operations
carry the same contracts and per-request selection.

Closing a context or disconnecting does not cancel admitted work. Its retained
Personal authority is rechecked before execution, after model queueing and before
accepting output; revoking consent prevents further effects. Reconnect and exchange
a fresh context to read a receipt, history or job, or explicitly cancel the job.
Current and retained authority must both permit the read/cancellation. Existing
conversation budgets and human/SYSTEM accounting remain separate from authority.
Standard synchronous tools and child agents use the existing agent grants, hooks,
resource checks, file-provider fences and command environment gate. Tool arguments
cannot widen the admitted Personal scope; file providers receive no client session.
New approval continuations, information-processing jobs, dynamic/external tools,
skills, orchestration, implicit recall, streamed subscriptions and automatic folds
remain closed until their consumers retain authority. See the
[supported execution capabilities](account-delegation-implementation-map.md#fifth-slice-standard-tool-checks-and-child-agents).
Human-facing token management and external identity linking remain later work; see
[ADR 0026](decisions/0026-service-execution-on-behalf-of-an-account.md).

## Optional native user authentication

Node, Python, Go, .NET and Java can explicitly reuse the same native user login.
Use the SDK's `Credentials` resolver to select a named connection or explicit
origin/account. Configuration precedence, version-1 files, mkdir locks, canonical
keys and migration/logout markers follow [shared local login](client-login.md).
Go exposes `Authentication`, .NET `IAuthentication`, and Java `UserAuthentication`;
each has a native `HttpAuthentication` and typed sign-in, token and status results.
The existing Java access-only `AuthClient` remains compatible.

`session` loads local state without proving server authority. `authenticated`
checks the public session endpoint and renews only after explicit rejection.
`renew` is an explicit rotation; supply the rejected access token to reuse a newer
pair saved by another client. Every rotation reloads under the shared lock and
persists renewal intent before HTTP, then the returned pair before exposing access.
These new stores conservatively fence every failed refresh, including connection
failures. Cancellation and failed persistence require explicit recovery; an
unproven final write retains the lock. Never automatically delete stale locks.

Application reconnect and mutation recovery remain explicit. Retain original UUIDs
and payloads, reconcile durable receipts, and make a deliberate recovery decision.
Service credentials remain an explicit alternative and never fall back to a saved
human login. See [ADR 0036](decisions/0036-native-sdk-shared-login.md) and the SDK
README examples. No new server authentication system is introduced.
