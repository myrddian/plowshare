# Asynchronous dispatch implementation and validation

Source implementation and repository verification are complete on the checkout based on
`b586dc3a291ebcfbbe46861da8fb51627c65add8`, alongside the existing query fixes and
schedule pagination. This record distinguishes implementation, local tests and
release acceptance. It is not a deployment receipt.

## Implemented ownership

| Slice | Implementation |
| --- | --- |
| 01 Contract | Private `ConcurrentDispatch` selection, explicit ordered compatibility, bounded ID validation and response ownership (64 SDK calls plus one physical reply handoff). No public operation-catalog addition. |
| 02 Coordination | Internal `ConcurrentRequests` process coordinator, bounded count/byte admission, resource keys, separate finite/observer capacity and drain. Concurrent collections own shared registries; short locked turns protect compound invariants. |
| 03 Server | `FrameHandler.handleAsync`, asynchronous `FrameRouter` authorization/fault/disclosure pipeline, both event encodings and serialized bounded reply output. |
| 04 Effects | `DispatchPolicy` explicitly classifies reads, accounted search, Relay waits/leases, schedule/conversation mutations, subscriptions and acting contexts. Unclassified operations preserve a connection barrier. |
| 05 SDKs | Typed Java async facades and shared opening; bounded TypeScript/Node/Python/.NET/Go request lifecycles, explicit selection and no replay. |
| 06 Views | Desktop schedule/trigger/file/dead-letter collections and Console schedule/file collections complete independently with view generations and panel-specific loading/error/page state. |
| 07 MCP | Bounded concurrent JSON-RPC tasks, per-call cancellation, physical root/run dependency lane and serialized whole-record stdout with finite output deadlines. |
| 08 Integration | Shared public SDK held-request and capacity fixtures, real server/SDK integration, source gates and release evidence are tracked below. |

The implementation uses the Gelato/Ciotola ownership ideas internally. It adds no
external dependency on either project and does not introduce a second durable
runtime. Resource coordination complements repository/PostgreSQL authority. It
does not coalesce paid operations, leases or mutations, nor replace the tracked
DTO cache's version and scope fences.

## Verification receipts

The integrated `./gradlew format formatCheck check sdkCheck` gate passed on 2026-10-20 (155 tasks; 2m 48s). Completed checks:

- Shared TypeScript suite: 938 tests passed, including held A/B, failed selection,
  smaller negotiated capacity, cancellation before submission and physical writer
  reservation retention.
- Java SDK suite: 148 tests passed, including strict negotiation and synchronous interruption cleanup. Focused server
  coverage includes the corrected subscription disconnect race, observer coordination
  and queued-disclosure checks.
- Full `sdkCheck` passed for Node, Python, .NET and Go, including B completing
  while A remains held until an explicit third request, 64 active calls with
  local refusal of the 65th, close uncertainty and large segmented publication/read.

- The embedded Tomcat/public Java SDK held-request test passed in both segmented
  and legacy encodings, with a typed schedule page and an intervening notification.
- Native validation passed after conversion admission changes: Ruff, mypy,
  30 Python tests, .NET build/DTO/tool/conversion checks and Go vet/race tests. Additional native/TypeScript close regressions prove that
  close observes owned reservation release before returning, including queued
  converters and writers.

The plain-Spring boot composition regression now passes. The final refreshed
server/client gate also includes physical handoff/refill races in both encodings.

The refreshed server/client tests, `formatCheck`, `check` and `sdkCheck` all passed.
Remote workflow and release acceptance remain pending. Database tests
are not required for these transport changes: no persistence SQL, transaction or
migration behavior is changed by this slice.

The TUI's detached sync cleanup race was introduced in `f78e4f048`; shutdown
now joins the same bounded leave after it detaches from the active view. Its
existing five-second leave bound remains explicit. The focused regression asserts
that no sync lock writer remains at normal completion before temporary cleanup.

## Acceptance and release boundary

Local WebSocket fixtures establish transport behavior. Mocked repository/grant
fixtures establish specific server contracts. Neither proves a production release,
provider behavior or visible remote client workflows.

Production rollout and evaluation must record exact source/image provenance,
selected mode and configuration, then exercise Manual/facets, document search,
Relay topics/messages and schedules, including account/scope changes, cancellation,
connection loss and recovery. Compare first useful result, request counts and
cache reuse separately from total page completion. Physical reply serialization
and intentionally ordered operations remain documented limitations. The final
queued-disclosure check may itself require repository-backed authority reads and
can delay the serialized writer; the execution-concurrency proof does not establish
production authority-query latency. Count/total/max queue, execution, encoding,
reply queue, write and drain durations, plus admission/contention/count/byte gauges,
are emitted as fixed aggregate telemetry when changed. The configurable interval
is `plowshare.transport.request-metrics-interval` (default `PT30S`); no request,
resource or account identities are retained in those measurements. Transport byte
budgets bound owned buffers and conversion concurrency, not total JVM/process RSS
or caller-owned DTO objects.

Publication and deployment receipts will record the final committed source revision
and remote acceptance separately from these successful source gates.

## Remote workflow follow-up

PR #88 merged the implementation at `14583cf4f79be4fa4207917a66395b2dc41e00ae`.
The first isolated Linux VM fixture run retained all 27 scenario results:
14 passed and 13 failed. Manual, Relay and TUI passed. Failures identified an
operator Global-history caller missing explicit `project: null` (introduced with
the scope validation in `9a3d100d`) and a quit-time command reentry/modal race
(`f78e4f048`). The follow-up fixes those callers while preserving authorization,
shutdown cleanup and visible reporting of unexpected errors.

Fixtures now follow current connection captions, versioned project markers and
pending-approval fences. The project reconnect scenario explicitly expires access,
requires an HTTP 401, proves one shared refresh rotation across concurrent project
connections and checks that no agent turn is replayed. A real Electron quit
scenario covers Usage, Board, Activity and trajectory views. The MCP deadline
regression now controls credential entry/release and expiry instead of racing a
20 ms wall-clock window (test originally added in `71dfe880`).

The refreshed `formatCheck check` gate passed after these changes. The new VM
workflow acceptance remains pending. Report filters and metadata now preserve the
reading area at embedded-window sizes while scope, errors and processing warnings
stay visible. Confirmed archive acknowledgements retire only the corresponding
retained active-chat metadata; history remains available, and stale connection
completions cannot alter the replacement view. The subsequent `formatCheck check`
gate passed with these changes (132 tasks; 43 seconds).
