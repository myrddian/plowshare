# Plowshare SDKs

The implementation order is JavaScript/TypeScript, the Java/JVM SDK, Python,
C#, then Go. These are clients of Plowshare's `plowshare-v1` WebSocket protocol.
The A2A adapter is a separate process on the Java SDK. Follow the
[A2A sending manual](a2a-sending.md) to configure a peer and send work.

| Language | Package | Runtime | Scope |
| --- | --- | --- | --- |
| JavaScript/TypeScript | `plowshare-client-ts`, `plowshare-client-node` | Node 22.12+ for the Node transport | Neutral typed operation catalog plus a ready-to-connect Node SDK |
| Java/JVM | `io.aeyer:plowshare-sdk` | Java 21+ | Generic transport and typed conversation/run/job/outgoing clients |
| Python | `plowshare-sdk` / import `plowshare` | Python 3.11+ | Async generic client and convenience methods |
| C# | `Plowshare.Sdk` | .NET 8+ | Async generic client and convenience methods |
| Go | `io.aeyer/plowshare/sdk` | Go 1.23+ | Context-aware generic client and convenience methods |

The new generic clients expose all registered operations from
the generated catalog. That means wire access;
it doesn't mean every response has a language-specific DTO, every operation has
a convenience method, or the SDK supplies a local filesystem/process provider.
Typed Java and TypeScript helpers coexist with the generic interface. The generic
Python, C# and Go APIs preserve JSON rather than recreating every domain model.

## Shared contract

- Credentials travel in the WebSocket upgrade's bearer header; authenticated
  redirects are refused. Application operations use `/v1/events`, never an HTTP fallback.
- Tokens are supplied by callers. The new core SDK entry points don't persist or
  refresh them. Existing Node platform credential/file adapters are separate exports.
- Each request has an ID, type and protocol version. Replies are correlated by ID
  and checked for matching type/version and a known outcome code.
- `Reply.raw` / `Reply.Raw` retains the complete envelope, future fields and JSON
  nulls. Refusal codes remain replies until `requirePayload` / `require_payload` /
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

Filesystem lending, process execution, binary uploads and Git transfers remain
platform integrations. Calling a frame generically does not install its provider
or assert that a remote system has an accessible filesystem.

## Build and verify

Install Java 21, Node 22.12+, the pinned pnpm, Python 3.11+, .NET SDK 8+ and Go 1.23+.
Prepare the Python environment from the repository root:

```sh
python3 -m venv build/sdk-python-env
build/sdk-python-env/bin/python -m pip install 'websockets>=17,<18' 'setuptools>=77' build
./gradlew --no-daemon sdkCheck
./gradlew --no-daemon sdkDistributions
./gradlew --no-daemon sdkPackageCheck
```

`sdkCheck` compiles the native clients and exercises real WebSocket upgrades,
bearer headers, redirect refusal, concurrent out-of-order replies, refusals,
future fields/nulls, malformed envelopes, push delivery, deadlines, disconnects,
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
under `plowshare-sdk/build/libs`; its Maven POM is under
`plowshare-sdk/build/publications/sdk`; its companion protocol artifacts are
under `plowshare-protocol/build/libs` and `plowshare-protocol/build/publications/protocol`.
Nothing is published remotely. Go discovery hosting is not configured: use the
[documented local replace directive](../plowshare-sdk-go/README.md) until release
hosting is selected.

## External integration runtime

The [shared external integration runtime](../plowshare-integrations/README.md)
and [first HA adapter](../plowshare-integration-home-assistant/README.md) are implemented
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
