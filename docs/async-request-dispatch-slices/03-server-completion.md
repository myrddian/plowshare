# Slice 03 Asynchronous server routing and protected completion

Status: source implemented; verification and rollout are tracked in the
[validation record](../async-request-dispatch-validation.md).
Depends on slices 1 and 2. The first vertical proof coordinates with slice 5's
Java and TypeScript transports; it does not require parallelizing every handler.

## Purpose and verified seams

Make receiving independent requests asynchronous on both encodings while keeping
the server's complete authority, failure and reply pipeline intact.

| Existing source | Behavior to preserve or replace |
| --- | --- |
| [EventChannelHandler](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/EventChannelHandler.java) | Segmented requests use `EventRequests`; legacy requests route inline. New mode delegates both to the same bounded contract. |
| [PacketEventTransport](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/PacketEventTransport.java) | Credits are processed directly during receive and share serialized physical writes; handlers must never obstruct this path. |
| [FrameRouter](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/FrameRouter.java) | Owns authorization, Personal/workspace fences, handler dispatch, listing filtering and centralized `Faults` mapping. |
| [FrameHandler](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/FrameHandler.java), [FrameArea](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/FrameArea.java) and [FrameRoutingConfig](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/FrameRoutingConfig.java) | Define synchronous handler registrations; add narrow typed async registrations/adapters without socket access. |
| [Asking](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/Asking.java) and [SocketAuthorization](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/SocketAuthorization.java) | Capture session/connection scope and recheck account session validity; strengthen query validity beyond physical socket openness. |
| [ActingRequests](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/ActingRequests.java) and [ProjectListingFilter](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/ProjectListingFilter.java) | Preserve per-request delegated subject checks and current disclosure filtering after completion. |
| [EventReadPolicy](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/EventReadPolicy.java) | Existing explicit read allowlist is the initial finite-operation audit baseline, not proof that all other reads are pure. |

## Routing and handler changes

Add a typed completion capability, such as `AsyncFrameOperation<T>`, whose input
has been decoded and validated at the owning transport boundary and whose output
is `CompletionStage<Outcome>`. Its metadata supplies the slice 1 execution policy.
Keep raw envelope maps inside codecs and the explicit legacy-handler adapter;
they must not become coordinator or worker application messages.

Do not run domain authorization/JDBC on the socket reader to calculate a policy.
Cheap envelope/mode checks occur there; bounded work performs typed preparation,
authority resolution and resource admission. Reuse resolved authorized DTOs for
the immediately following operation where their freshness contract permits it.
Current authority and version fences remain explicit, not cached permission grants.

Legacy synchronous handlers execute on bounded virtual-thread workers through
the same routing pipeline. This is an incremental transport adapter, not an
external Ciotola/Pasta adapter. Preserve current barriers for unaudited paths and
old-mode connections. Initial parallel candidates are the already audited
Information list/facet/read/document/navigation/relationship, Relay topics/log
and schedule list/files paths. Verify their full current implementations before
registering each; local work may have changed them since the earlier audit.

The completion pipeline is one owned sequence:

1. Validate the envelope/mode/ID and atomically admit under connection limits.
2. At actual work start, recheck connection generation and live authenticated
   session, then decode/validate and resolve operation authority.
3. Execute under the declared policy and worker budget; pass immutable actor and
   optional acting selection. Do not set mutable socket-wide subject context.
4. Receive completion on the owning coordination path. Apply operation-specific
   current authorization, Personal/workspace and delegated checks plus
   `ProjectListingFilter` before a protected result becomes deliverable.
5. Convert asynchronous failures through `Faults`, matching synchronous behavior;
   map wrapped completion exceptions without hiding their cause. Fatal JVM errors
   retain the existing failure behavior rather than becoming fabricated success.
   Successful void/null payloads have explicit outcomes; a null stage/outcome is
   an implementation failure, not a nullable success contract.
6. Queue exactly one correlated terminal reply and retain its delivery ownership
   until transport handoff/termination. Serialization failure produces the
   existing correlated error or closes the unusable connection.

Do not expose the handler's unchecked future directly to delivery. A typed
request/future does not replace validation, authorization or output encoding.
Spring transaction context stays inside the service/repository worker call;
never assume it follows an arbitrary stage continuation.

## Revocation, disconnect and output races

`Asking.queryValidity` currently checks physical openness in the event channel.
Extend it to the captured connection generation/current authenticated session and
stopping state. Invoke it at meaningful boundaries in long work; do not introduce
an extra database permission query on every pure local computation.

Revocation/displacement invalidates queued work and protected results waiting in
reply storage. Mark generation invalid before detaching/draining output, so a
completion racing teardown cannot reinsert sensitive data. Reply handoff checks
that generation. Already written bytes cannot be recalled; document that boundary
and do not claim revocation atomically retracts prior disclosure.

Close discards unstarted requests but does not interrupt/replay an admitted durable
mutation merely because its caller stopped waiting. Running completion checks its
execution identity, releases ownership once and cannot reach a replacement socket.
A refused/late request cannot settle an unrelated request, even if the operation
type is identical. Pushes and packet credits remain distinct from terminal replies.

Keep output semantics honest: the single writer serializes logical replies and
segmented packet credit waiting. A large response can delay a small response's
wire transmission even after concurrent execution. Transfer interleaving is a
separate protocol change; measure execution completion and output wait separately.

## Default tests and acceptance gate

Extend [EventReadDispatchTest](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/ws/EventReadDispatchTest.java),
[EventChannelTest](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/ws/EventChannelTest.java),
[FrameRouterTest](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/ws/FrameRouterTest.java)
and [PacketTransportTest](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/ws/PacketTransportTest.java).
Use mocked repositories/authority services and controlled stages. No real
PostgreSQL is required for scheduling, mapping or transport lifecycle assertions.

The vertical gate holds A open, submits independent B on the same connection,
and proves B executes and reaches its own reply while A remains pending. Run
the proof for both encodings and two requests with the same operation type.
Verify old mode retains its barriers; selected new mode does not bypass policy.

Also cover authority revoked while queued, revoked after execution before reply
handoff, connection replacement, distinct acting subjects, malformed payload
refusal, asynchronous exception/null/void outcome handling, reply serialization
failure, duplicate active IDs and simultaneous packet traffic. Assert no worker
starts for revoked queued work and no protected result enters a new generation.

Handoff to slice 4: completion and policy seams with conservative fallback.
Handoff to slice 5: executable envelope/negotiation/reverse-order fixtures.
Out of scope: broad effects reclassification, observer implementation, new
domain cancellation, SQL redesign, production deployment or latency claims.
