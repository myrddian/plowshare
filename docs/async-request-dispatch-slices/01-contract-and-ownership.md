# Slice 01 Contract and coordination ownership

Status: source implemented; verification and rollout are tracked in the
[validation record](../async-request-dispatch-validation.md).
The design was based on the umbrella plan checkout and its local query/cache work.

## Purpose and prerequisites

Freeze the contract used by the server coordinator and every SDK before their
implementations diverge. This is the prerequisite for slices 02 through 08;
implementation owners may work concurrently once its contract gate is settled.
The [umbrella plan](../async-request-dispatch-plan.md) owns the overall design.

Use Ciotola/Pasta as inspiration or selectively copy and adapt useful source into
Plowshare. There is no external framework dependency, runtime or compatibility
adapter. Retain applicable notices and upstream revision provenance for copied
source. A coordination Role owns execution state; it is unrelated to access roles.

## Verified seams and files to change

| Existing owner | Current seam and proposed responsibility |
| --- | --- |
| [Envelope](../../plowshare-protocol/src/main/java/io/aeyer/plowshare/protocol/frames/Envelope.java) | Already carries correlation ID and immutable acting selection; define strict new-mode request validation without changing push identity semantics. |
| [SegmentedMessages](../../plowshare-protocol/src/main/java/io/aeyer/plowshare/protocol/transport/SegmentedMessages.java) | Owns packet transfer IDs and byte credits; its encoding remains independent of dispatch mode. |
| [EventChannelHandler](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/EventChannelHandler.java) | Currently advertises the segmented subprotocol and builds connection delivery state; own a once-selected dispatch mode per physical connection. |
| [FrameHandler](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/FrameHandler.java) and [FrameArea](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/FrameArea.java) | Define the synchronous transport boundary and registration; introduce typed asynchronous execution registration beside it. |
| [FrameRouter](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/FrameRouter.java) | Owns authority, disclosure filtering and failure mapping; preserve these responsibilities across completion. |
| [ADR 0009](../decisions/0009-segmented-sdk-message-transport.md) | Documents existing independent reads and ordered barriers; specify the selected new-mode behavior and retained old-mode behavior. |

If a public domain operation is added, its definitions remain owned by
[TypeScript operations](../../sdk/typescript/src/operations/administration.ts)
and the existing generators. The proposed private transport control has its own
typed codec/fixtures and does not by itself require a public operation catalog
rewrite. Regenerate affected derived files through their owners, never by hand.

## Proposed wire decision and first implementation gate

Recommend a private typed transport-control `transport.dispatch` exchange carrying
`{mode: "concurrent-v1"}` before application requests. A successful correlated
reply confirms that exact mode, capabilities and effective bounds for this
physical connection. Negotiation is
encoding-independent: it works inside either legacy text or segmented messages.
The WebSocket subprotocol continues to select encoding, not dispatch semantics.

Finalize this proposed name and DTO through the contract fixtures in this slice.
Its owner sits before the domain router and checks only the current
authenticated connection for this narrow transport action. It grants no blanket
domain-authorization bypass. Confirm its transport codec and fixtures before
slice 3; use public catalog generation only if a public operation is actually
chosen instead. Selection/acknowledgement/close races use bounded normal
correlation slots and an opening deadline, not a second unbounded pending map.

| Peer combination | Required behavior |
| --- | --- |
| Old client, updated server | No selection: retain documented old-mode ordering. |
| Updated client, updated server | Confirm selection before submitting application work. |
| Updated client, old server | A legacy `NOT_FOUND` or selection mismatch fails new-mode opening clearly; no application mutation has been submitted. |
| Caller explicitly chooses old mode | Skip new-mode selection and retain old semantics. |
| Selection after application admission or repeated selection | Refuse according to the finalized protocol; never change an active connection's mode. |

Do not silently reconnect, downgrade, switch to HTTP or replay a mutation after
an uncertain exchange. A lost selection acknowledgement closes that opening;
the SDK must not assume which mode was established.

## Narrow internal contracts

Final names may follow existing naming conventions; responsibilities are fixed:

- `RequestContext`: immutable connection generation, correlation ID, operation,
  authenticated actor and optional acting selection. Identity is not a grant.
- `AdmittedOperation<T>`: validated typed operation input and its execution
  policy. Raw JSON/maps stay in the transport decoder/legacy adapter, never in
  actor or worker application messages. Reuse authorized typed snapshots already
  resolved by that operation; key derivation must not repeat permission queries.
- `OperationExecutionPolicy`: independent finite work, typed resource conflicts,
  observer, connection lifecycle or conservative unaudited barrier. The server
  derives the policy and keys; clients cannot select arbitrary lanes.
- `RequestDispatcher`: bounded submission, admission closure and asynchronous
  drain completion. Submission reports admitted/rejected without waiting for IO.
- `OperationExecutor<T>`: typed input to `CompletionStage<Outcome>` through the
  owning routing pipeline. Keep socket handles and output callbacks out of it.
- `ReplyDelivery`: typed terminal outcome handoff and delivery completion, owned
  by transport composition. It does not authorize handlers to write sockets.

Interfaces describe ownership, concurrency, failures and cancellation. Immutable
records need no artificial interfaces. Composition selects implementations;
domain services do not depend on the internal actor implementation.

## Correlation, completion and causality invariants

1. Every new-mode request ID is nonblank, exact, at most 128 UTF-8 bytes, with no
   surrounding whitespace or control characters. Validate before admission.
2. IDs never repeat within a physical connection generation. Keep active IDs
   through queueing, execution, completion and reply transport handoff.
3. Duplicate active IDs or missing/invalid IDs cause protocol close. A same-ID
   refusal could settle the original caller's future with the wrong outcome.
4. A valid ID with an invalid operation payload receives a correlated refusal.
   Pushes remain distinct and cannot satisfy a waiting request.
5. One terminal winner owns reply, timeout, cancellation or close cleanup.
   Delivery failure completes the client exceptionally; no response delivery is
   promised after connection loss. Late results cannot target a new generation.
6. Envelope ID, packet `transferId` and durable mutation `requestId` are distinct.
   Correlation supplies neither idempotency nor authority. An `ACCEPTED` receipt
   is the request's outcome; durable work finishes through its owning API.
7. Independent requests may complete in any order. Clients await a mutation's
   reply before a read that must observe it. Resource policies supply only their
   declared conflict ordering; send order is not a general dependency graph.

Logical Role ownership means one state-mutating turn at a time, not permanent
thread affinity. Use concurrent registries/queues where shared. Compound capacity
transitions need a documented atomic protocol; callbacks and public future
completion run outside its locks and away from socket readers.

## Verification and acceptance

Default tests use pure protocol fixtures and in-memory coordination fakes. Cover
ID byte/control bounds, malformed request versus malformed operation, mode
selection state, all peer combinations and push/reply discrimination. Extend
the [Envelope tests](../../plowshare-protocol/src/test/java/io/aeyer/plowshare/protocol/frames/EnvelopeTest.java)
and shared SDK contract fixtures through their owners. No PostgreSQL is needed.

The gate is a reviewable state-transition table and executable contract fixtures
shared with slices 2, 3 and 5. Include null/void success, failure and late
completion semantics, and separate negotiation from application submission.
The gate must settle the exact selection encoding and compatibility matrix;
those choices must not remain implicit in SDK implementations.

Out of scope: coordinator execution, operation-by-operation effects audit, SDK
facade implementation, transport transfer interleaving, deployment or latency
claims. Physical packet serialization remains unchanged.
