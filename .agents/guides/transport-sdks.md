# Transport and SDK contracts

Read this for WebSocket dispatch, operation DTOs, SDKs or shared authentication
transport. Read [AUTH](auth.md) when changing admission, identity or disclosure.
For request windows, queues or capacity retries, read [backpressure](backpressure.md).

## Mental model

SDKs expose server capabilities through typed contracts. The neutral TypeScript
SDK owns shared operations and transport primitives; Node adds credentials,
filesystem and platform composition. Other SDKs follow their own public surface
and the shared contract checks; feature parity is not permission to invent APIs.

## Start in code

- [FrameRouter](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/FrameRouter.java),
  [FrameHandler](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/FrameHandler.java)
  and [DispatchPolicy](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/DispatchPolicy.java)
  own server routing and dispatch classification. Handlers call typed capabilities.
- [Shared operations](../../sdk/typescript/src/operations) own request/reply DTOs,
  validators and command mappings; [binding](../../sdk/typescript/src/binding)
  owns envelopes, correlation, packets and transport failures.
- [Node credentials](../../sdk/node/src/credentials.ts) and
  [session composition](../../sdk/node/src/session.ts) own saved-login renewal.
- [SDK instructions](../../docs/sdks.md), [TypeScript standards](../../docs/typescript-standards.md)
  and [native standards](../../docs/native-sdk-standards.md) name generation and checks.
- [External conversation SDK calls](../../docs/sdks.md#external-conversation-aliases)
  use the canonical Ex request/result graph and the Java
  [ExternalConversationClient](../../sdk/java/src/main/java/io/aeyer/plowshare/sdk/ExternalConversationClient.java).
  Their account-local selectors resolve in one server operation. Open-Ex requires
  the complete `OK` view; Run-Ex requires an `ACCEPTED` internal job/conversation
  receipt. Optional agent metadata is distinct from explicit null, and uncertainty
  never permits replay. Ordinary operation/result policy remains unchanged.

## Rules

- Use existing WebSocket operations where supported. Keep required HTTP
  boundaries explicit; do not add a silent alternate transport or API.
- Decode and validate at the boundary into operation-specific DTOs. Preserve
  required/optional/null semantics, strict request allowlists and validated replies.
  A cast or successful JSON parse is not validation or authorization.
- Change shared contracts and affected consumers together. Regenerate derived
  schemas/catalogs with their owning scripts; do not patch generated outputs.
- Preserve request correlation, count/byte bounds, resource ordering, cancellation
  and physical send ownership. Classify operations by their actual effects;
  receipt/acknowledgement writes are not shareable reads.
- A deadline or disconnect after possible transmission leaves uncertain delivery.
  Preserve request identity and inspect the owning receipt; never automatically
  replay a mutation or rotating credential refresh.
- Keep neutral TypeScript free of Node/DOM dependencies. Platform-specific login,
  filesystem and listener composition belongs in the platform owner.
- Authentication transport and authorization are separate. Reconnect must not
  reuse an allow after session revocation, nor infer a session from an account name.

HTTP advice and frame routing report thrown server/dependency failures through
`FailureReporter` after the shared `Faults` mapping. Caller refusals keep their existing
status and are excluded. Reporting uses fixed source/kind labels and sampled ERROR
code locations without messages or request/session identifiers; it changes neither
wire outcomes nor replay rules. Directly returned failure outcomes are outside this
exception observation counter. See [server failure diagnostics](../../docs/server-administration.md#server-failure-diagnostics).

## Bounded work

Apply the [bounded-work rule](../../docs/coding-standards.md#bound-active-work-in-every-subsystem):
actual SQL query/transaction units use a configurable two-second default ceiling.
Slow accepted requests and workflows warn without an execution cutoff. Negotiation,
packet transfers, providers and SDK caller waits retain their explicit finite
owning budgets; allowed retries do not renew them. Preserve physical send/work
ownership after caller timeout and report uncertainty after possible effects.
Backpressure paces or refuses incoming work before dispatch and does not cancel
accepted work.

Current SDK [connection deadlines](../../sdk/typescript/src/binding/connection.ts)
start at transmission and exclude slot waits; packet/negotiation timers cover
other phases. Those mechanisms do not yet prove the broader end-to-end policy.
Caller cancellation is not remote server cancellation. Preserve existing public
timeout semantics and receipt recovery when addressing the gap.

For `conversation.open.ex` and `agent.run.ex`, assembled requests retain the exact
[QueuedExternalConversationRequest](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/QueuedExternalConversationRequest.java)
token across negotiated and segmented legacy queueing. The token preserves
request ownership rather than installing an elapsed workflow deadline. Session,
common authorization and actual-target disclosure checks retain their separate
freshness purposes. Repositories own finite actual SQL-unit budgets; hooks and
job admission do not inherit a two-second SQL timer.

[BoundedExternalConversationCompletions](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/BoundedExternalConversationCompletions.java)
bounds retained replies. Occupied slots refuse incoming work before dispatch,
allowing proven capacity flow control. Accepted work keeps its outcome and
physical ownership until routing and notification settle, including blocked
notifications and observer cancellation. Shutdown stops intake and does not
replace accepted success. Queued disposal closes its token; started work stays
owned until it settles. Slow request duration emits warnings only through the
router's performance observation. SQL or caller timeout never proves remote
abort or permits replay after possible effects. An admitted job owns its ordinary
durable lifecycle.

ARCHIVE_UNAVAILABLE describes result availability, not mutation certainty. Ex
facade and result/session fences normalize only typed archive-availability
failures to their shared safe uncertainty policy, retaining exact Ex/SQL outcomes
and original causes. They never add the ordinary archive mapper's no-write
assurance after possible commit/admission. Other caller, constraint and
programming failures retain their existing mapping; queued failures withhold the
protected result and follow the existing connection-close path without replay.

## Legacy traps

An operation constant or old HTTP controller does not prove it is the supported
consumer path. Trace an actual client and the current handler. In particular,
conversation turns/compactions are not substitutes for trajectory provenance.
Do not repair an SDK capability gap by calling server internals from an adapter.

## Checks

Use the affected SDK's existing tests and type checks. The TypeScript suite runs
with `pnpm --dir sdk/typescript test`; server examples include `FrameRouterTest`
and `ConcurrentResourceOrderingTest` in `io.aeyer.plowshare.server.ws`.
For contract changes run the owning generator and relevant contract/conformance
checks documented in [SDKs](../../docs/sdks.md), then inspect generated diffs.

Exercise invalid fields, early/late replies, disconnect before/after submission
and uncertain mutations. Default verification needs no PostgreSQL. Native SDK
toolchain checks are explicit entry points, not implied by Java compilation.
