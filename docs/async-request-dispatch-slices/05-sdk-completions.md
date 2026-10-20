# Slice 05 SDK completions and transport lifecycle

Status: source implemented; verification and rollout are tracked in the
[validation record](../async-request-dispatch-validation.md).
Parent: [asynchronous request dispatch plan](../async-request-dispatch-plan.md).
Starts after slice 01 fixes negotiation, lifecycle and admission contracts;
integrates with slices 02/03 for bounded independent server completion. Transport
fixtures support parallel SDK implementation before the server path is complete.
Slice 06 owns client rendering and workflow sequencing; slice 07 owns MCP tasks.
This slice owns Console's event transport as well as SDK transports.

## Outcome and existing owners

One connection supports multiple outstanding typed requests. Each caller receives
only its matching result, independently of reply order. Sending remains serialized;
an asynchronous call returns without waiting for packet transmission or a reply.
Ciotola/Pasta ownership patterns and useful source may be copied and adapted into
Plowshare with provenance/notices. Neither is a dependency or compatibility adapter.

| Source owner | Current behavior and change |
| --- | --- |
| [Java transport](../../sdk/java/src/main/java/io/aeyer/plowshare/sdk/Plowshare.java) | Already uses concurrent pending futures, but `requestActing` blocks in packet send and reply wait. Split submission from waiting. |
| [Java facade](../../sdk/java/src/main/java/io/aeyer/plowshare/sdk/WsServerClient.java), [contract](../../sdk/java/src/main/java/io/aeyer/plowshare/sdk/ServerClient.java), [schedule facade](../../sdk/java/src/main/java/io/aeyer/plowshare/sdk/ScheduleClient.java) | Blocking typed operations and synchronized lazy opening need asynchronous counterparts sharing one exchange engine. |
| [TS connection](../../sdk/typescript/src/binding/connection.ts), [typed API](../../sdk/typescript/src/operations/sdk.ts), [Node composition](../../sdk/node/src/sdk.ts) | Promise correlation and 64 pending requests already exist. Preserve them; add complete deadline/cancellation ownership and bounded queued work. |
| [Python](../../sdk/python/src/plowshare/client.py) | Async typed requests already exist. Pending-count admission happens after acquiring the send lock; bound callers before waiting there. |
| [.NET](../../sdk/dotnet/Plowshare.Sdk/Client.cs) | Typed Tasks and asynchronous continuations already exist. Bound admission before writer wait; prefer concurrent pending storage with atomic lifecycle coordination. |
| [Go](../../sdk/go/client.go), [packet sender](../../sdk/go/packets.go) | Concurrent goroutines already correlate replies. Make queued sender acquisition context-aware; retain typed map/mutex where compound lifecycle invariants justify it. |
| [Console events](../../plowshare-console/src/events.ts) | Already correlates promises, with reconnect ownership. Add shared count/byte limits, delivery classifications and per-generation cleanup; no second socket or replay. |

## Contract and lifecycle invariants

1. Validate typed input before a write. Reserve bounded request count and encoded
   bytes before waiting behind a writer; count queued, sending and reply-waiting
   calls, not only the existing 64 sent requests. Do not allocate an unbounded
   admission-waiter list or encoded-body queue. Fail excess local work explicitly.
2. Register correlation before submission. Use slice 01's ID bounds/negotiation;
   never reuse an ID within a physical connection generation. Keep envelope ID,
   segmented transfer ID and durable mutation receipt ID distinct.
3. A pending entry records immutable operation, generation, submission state,
   absolute deadline, terminal owner and reservation handles. Coordinate queued
   cancellation with the writer: proving no possible write yields NOT_SUBMITTED;
   a write race yields UNKNOWN. A local timeout never means server rollback.
4. Reply, timeout, cancellation and close compete for one terminal transition.
   Release each owned slot, byte reservation, timer and cancellation registration
   exactly once. Packet-transfer ownership may outlive caller detachment; retain
   its bytes until transfer finishes or the transport closes safely.
5. Validate ID/type/version and operation-specific DTO before public success.
   Preserve refusals, nullable results and explicit void success. Malformed
   correlated replies yield INVALID_RESPONSE. Unknown/duplicate/late replies
   cannot settle another call; diagnostics remain bounded and payload-free.
6. Close stops admission, settles all pending calls and exposes drain completion.
   Repeated close observes the same drain. Reopening creates a new generation;
   stale successes and failures cannot affect it. Never replay submitted work.
7. Caller cancellation stops that caller's wait; server work needs its owning
   explicit cancellation operation. ACCEPTED still completes the SDK request,
   while its durable job/lease has a separate lifecycle. Shared cache-load
   detachment must not cancel another waiter's valid load.

## Internal implementation steps

### Java typed asynchronous opening and exchange

Define a narrow asynchronous capability before implementation. Recommended public
shape: `AsyncServerClient` with operation-specific `...Async` methods returning
`CompletionStage<T>`, implemented by the existing WebSocket facade. Add equivalent
typed `...Async` methods to operation facades, such as `listPageAsync`; retain
current DTO and refusal semantics. The existing synchronous methods await those
same exchanges. No public string-operation/JSON escape hatch is introduced.

Add `Plowshare.connectAsync(...)` and one shared opening stage for lazy facade
connection establishment. Bound and expire callers waiting for opening; one
cancelled waiter cannot cancel siblings. Close/replacement fences publication of
an opening connection and closes a late socket. Synchronous connection access
awaits the same owner. Keep authenticated-upgrade redirect rejection and explicit
old/new-mode selection; opening never reconnects to replay an uncertain call.

The internal exchange returns its stage after admission/enqueue, before packet
credit waits. A separately owned writer performs blocking sends. Do not wrap the
old blocking `request()` in `supplyAsync`, keep a worker per response wait, or
start the response deadline only after sending. Use one absolute deadline from
invocation through admission, queued transmission and response waiting. Cancellation
is an explicit per-request option/control; cancelling a derived stage does not
silently imply transport cancellation or server cancellation.

### Writer reader and public completion ownership

Retain [segmented transport guarantees](../decisions/0009-segmented-sdk-message-transport.md).
One logical message occupies its direction's writer, one data packet awaits credit,
and credits bypass the logical writer. Keep receiving credits and notifications
while sends or handlers wait. Do not add packet interleaving in this slice.

Java currently completes responses on its reader and close failures under a
lifecycle monitor. Detach terminal state under the coordinator/atomic protocol;
complete public stages outside locks and off the reader. Use established JDK
completion semantics rather than copying Ciotola's queue-based future state.

Reserve bounded public-completion capacity during admission. Capacity must cover
terminal delivery even when arbitrary user continuations block or submit another
request; exhaustion rejects new work and cannot drop an owed completion or fall
back to running it on the reader. Separate protocol settlement from arbitrary
callback lifetime, account for both, and define graceful close behavior. A nested
request must progress or fail admission promptly; it must not wait on capacity
held by its own continuation. Prove this invariant before selecting executor
sizes. Late registration, multiple observers, exception and null/void outcomes
must work after terminal completion as standard stages do.

### Native TypeScript Node and Console parity

Retain TS typed promises, Python awaitables, .NET generated async methods and Go
context-based calls; another Future abstraction is unnecessary. Adopt slice 01's
deadline policy through idiomatic per-request options without mutating a socket's
actor or acting context. Preserve all existing resource-coordinate validation.

Use Java ConcurrentMap and .NET ConcurrentDictionary for shared pending state;
keep narrow atomic protocols for admission, generation change and close. .NET's
RunContinuationsAsynchronously remains mandatory; detach its drain before task
completion outside the gate. Python/TS dictionaries are event-loop-confined.
Do not replace Go's typed composite invariant mechanically with sync.Map. Replace
its uninterruptible logical-writer wait with context-aware bounded admission.

Console should reuse the owning shared correlation/lifecycle capability where
feasible while retaining browser authentication/reconnect and push decoding. Each
reconnect generation fails the previous pending set; it never resubmits it. Apply
the same default 64-request bound and bounded bytes, cancellation and typed
delivery errors before a writer wait. Screen rendering remains in slice 06.

### Contracts generators and validation

Keep operation DTOs unchanged unless an operation actually changes. Shared input,
reply and notification graphs remain owned by TS; transport options belong to
their owning public interfaces. If generated method signatures/options change,
edit [generation entrypoint](../../scripts/generate-sdk-contracts.mjs) and
[native templates](../../scripts/generate-native-sdk-contracts.mjs), then regenerate.
Maintain [portable constraints](../../scripts/native-sdk-constraints.mjs) and
[shared fixtures](../../test-support/contracts/sdk-conformance.json); never patch
generated DTOs/catalogs by hand or weaken public boundary checks.

Extend existing [Java tests](../../sdk/java/src/test/java/io/aeyer/plowshare/sdk/PlowshareTest.java),
[TS tests](../../sdk/typescript/src/binding/connection.test.ts),
[Console tests](../../plowshare-console/src/events.test.ts) and
[cross-language fixture](../../scripts/sdk-conformance.mjs). Native consumer owners:
[Python](../../sdk/python/tests/conformance.py), [.NET](../../sdk/dotnet/Conformance/Program.cs),
[Go](../../sdk/go/cmd/conformance/main.go). Use deterministic barriers and fake
clocks instead of timing sleeps. Exercise legacy and segmented encodings under
the negotiated mode, including delayed credits, simultaneous traffic and pushes.

## Done criteria and handoff

- Public Java async calls/opening return before held I/O; synchronous parity and
  typed external-consumer compilation prove equivalent DTO/refusal behavior.
- Held A allows same-operation B to finish first in its own completion. Late
  replies, invalid envelopes, different acting selections and reconnect generations
  never cross-complete. Null/void, errors and late callback registration settle.
- Saturated count/bytes/completion execution remain bounded; queued cancellation,
  reply/deadline/close races and nested callbacks leak no reservations or promises.
  Test callback overload and close without needing a cooperative user callback.
- Shared fixture proves no replay, one terminal winner and credit progress across
  all SDKs and Console. Go race tests, TS checks, Python strict checks and .NET
  warnings-as-errors remain enabled; test fresh compiler consumers as applicable.
- Planned gates: focused owning tests, Java `format`/`formatCheck`, then `check`
  and `sdkCheck`; packaging changes also require `sdkPackageCheck`. No database
  or `-PfullDb` is needed for this transport/SDK slice. These are future gates,
  not validation claimed by the planning document.

Hand slice 06 the typed deadline/cancellation/error interfaces, generation fences
and transport bounds. Hand slices 07 and 08 the negotiated compatibility matrix,
fixture cases, measured boundedness/drain evidence and updated SDK documentation scope.
