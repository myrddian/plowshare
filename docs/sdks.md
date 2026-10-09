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

## Shared contract

- Credentials travel in the WebSocket upgrade's bearer header; authenticated
  redirects are refused. Application operations use `/v1/events`, never an HTTP fallback.
- Tokens are supplied by callers. The new core SDK entry points don't persist or
  refresh them. Existing Node platform credential/file adapters are separate exports.
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
the installed artifacts. This task may download runtime/build dependencies.
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

Integrations can authenticate with an expiring `pss_` bearer issued to a [Plowshare service account](server-administration.md#service-accounts-and-scoped-tokens). Grant the service account the required server project roles and issue a token with project ceilings. Use the same token identity across integration restarts and rotate it to retain owned work and ingress receipts. Service tokens have no password login, refresh credential or Personal space; send an explicit project or an existing resource in an allowed project. Information requests use `includeShared:false`. Project grants, scope ceilings, expiry and revocation apply to subsequent execution as well as SDK requests.

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
