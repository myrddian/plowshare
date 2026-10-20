# Slice 08 Conformance and rollout

Status: source implemented; verification and rollout are tracked in the
[validation record](../async-request-dispatch-validation.md). Read the [slice index](README.md) and
[architecture plan](../async-request-dispatch-plan.md). This slice integrates the
completed server, SDK, view and MCP changes; it does not make an unaudited
operation concurrent merely to complete a test matrix.

## Outcome and prerequisites

Prove that one authenticated connection supports independently completing
requests, bounded contention, current authorization and safe recovery. Preserve
the existing separation between an operation response and completion of durable
jobs, Relay deliveries or other external work.

Requires slices 01 through 07 to meet their relevant exit criteria. Conformance
fixtures can develop alongside them once slice 01 freezes the contract. Actual
server integration depends on slice 03; effect/observer assertions depend on
slice 04, and public SDK/UI/MCP assertions depend on slices 05 through 07.

The runtime is implemented inside Plowshare. Neither Ciotola nor Pasta is added
as a build/runtime dependency. Source-derived code retains its attribution and
is tested against the same Plowshare contracts as newly written code.

## Existing verification owners

| Owner | Extension in this slice |
| --- | --- |
| [EventReadDispatchTest](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/ws/EventReadDispatchTest.java) and [EventRequestsTest](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/ws/EventRequestsTest.java) | Deterministic admission, independent progress, contention, fairness, drain and release assertions. Preserve the old-mode barrier cases. |
| [EventChannelTest](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/ws/EventChannelTest.java) and [PacketTransportTest](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/ws/PacketTransportTest.java) | Actual channel negotiation, both encodings, same-ID replies, packet-credit progress, connection generation and delivery races. |
| [Java PlowshareTest](../../sdk/java/src/test/java/io/aeyer/plowshare/sdk/PlowshareTest.java) and [TypeScript connection tests](../../sdk/typescript/src/binding/connection.test.ts) | Public async submission, blocking-wrapper parity, reverse-order completion and reentrant callback behavior. |
| [Shared SDK conformance](../../scripts/sdk-conformance.mjs), [packet fixture](../../scripts/sdk-packet-fixture.mjs) and [contract cases](../../test-support/contracts/sdk-conformance.json) | One coherent old/new mode matrix across Node, Python, .NET and Go, using their typed public APIs. |
| [Console events tests](../../plowshare-console/src/events.test.ts), [Desktop schedules tests](../../plowshare-desktop/src/schedules.test.ts) and [Information collection tests](../../plowshare-desktop/src/renderer/information-collection.test.ts) | Pending admission, independent rendering, stale successes/errors and preserved mutation uncertainty. |
| [MCP socket tests](../../plowshare-mcp/src/socket.test.ts) and new focused main-loop tests beside its owning implementation | Concurrent JSON-RPC task lifecycle, initialization, cancellation, whole-record stdout writes and bounded overload. |

Keep the transport fixture a boundary test, not a mock assertion that the real
server dispatcher was concurrent. Both kinds of evidence are required. Use
controller futures/latches and observable state transitions rather than sleeps
or tight wall-clock thresholds to prove operation ordering.

## Required conformance matrix

1. **Negotiation:** old mode preserves its documented behavior; new mode is
   acknowledged before domain submission; an unsupported mode fails clearly.
   Malformed selection, repeated selection and mid-flight mode change follow
   slice 01's explicit refusal/close rules. Capability mismatch never triggers
   silent fallback or replay.
2. **Correlation:** issue two requests for the same operation. Hold A, complete B,
   and assert B's typed completion is settled while A is still pending. Then
   release A. Deliver a notification between the replies. Verify ID, operation,
   version and response DTO checks; a notification's domain ID is not correlation.
3. **Terminal races:** release reply, timeout, cancellation and close from
   controlled competing paths. Assert one terminal winner, one reservation
   release, no wrong-ID completion and no mutation replay. Exercise duplicate
   active IDs, unknown/late replies, valid refusal, malformed correlated result,
   null/void success and a completion observer registered after the result.
4. **Capacity and reentrancy:** saturate queued/running requests, retained bytes,
   resource keys, observers, writers and completion execution independently.
   Admission refusal starts no operation. An owed completion is never dropped or
   executed on the reader as an overload fallback. A continuation can submit
   another request without holding a lifecycle lock; saturation returns the
   declared result instead of deadlocking.
5. **Effects and authority:** conflicting mutations obey their resource policy;
   another resource progresses. A waiting observer cannot hold the lane or
   execution permit needed by its unblocking mutation. Revoke queued authority,
   replace the connection, and overlap acting selections from different subjects.
   Verify execution and disclosure fences, retained receipt identities, leases,
   attribution and durable accounting through their owning APIs.
6. **Transport and shutdown:** bidirectional messages still receive packet
   credits while handlers wait. Large response serialization is measured
   separately from handler completion. Close/removal discards unstarted work,
   drains the declared started work, rejects new admission and exposes completion
   before dependent resources are destroyed. Restart uses existing durable
   recovery; it does not replay unknown client submissions.
7. **Clients and MCP:** hold facets or files and verify useful independent content
   renders. Switch scope/account/socket before delayed success and failure;
   neither can overwrite the new view. Verify mutation-to-reconciliation and
   subscription-to-submission dependencies. MCP B completes while A waits, stdout
   remains parseable whole JSON-RPC records, and one cancellation does not cancel
   unrelated calls or alter shared workspace identity.

## Validation commands and database boundary

Use repository mocks/fakes for dispatcher, actor, SDK and UI behavior. Real
WebSocket fixtures need no database. Run focused owner tests during each slice,
then the repository's normal gates after integration:

```sh
./gradlew format
./gradlew formatCheck
./gradlew check
./gradlew sdkCheck
```

Use documented local toolchain configuration for native SDKs. Do not put machine
paths, credentials or private deployment configuration in test commands here.
Regenerate transport/SDK artifacts through their owning generators if slice 01
changes those contracts; do not edit generated DTOs or inflate domain catalogs
for a transport-private control exchange.

PostgreSQL is an exception only when the changed assertion requires real lease,
locking, transaction or durable-version behavior. Explain that reason, tag the
test `full-db`, and select the smallest relevant cases with `-PfullDb --tests`.
Transport confidence alone is not a reason to run full database or Docker smoke
coverage. Fix failures while preserving existing authorization and no-replay
assertions; do not weaken a fixture to conceal a lifecycle race.

## Measurements and staged live acceptance

Record distinct receive/admission, queue, handler, SQL/provider, completion queue,
reply write, SDK decode and first useful render times. Include count and byte
reservations, current resource contention, waiting observers, timeouts, late
replies, cache hit/coalescing behavior and drain duration. Use low-cardinality
operation labels and aggregate measurements; never retain private payloads or
credentials in the plan or repository.

First run a controlled held-request proof against the real server through the
public SDK. Use a small B response while A is held before producing a response;
this isolates execution concurrency from the intentional serialized packet
writer. Separately exercise a large transfer to verify credit/output behavior.

Then exercise Manual/list/facet, document search, Relay topics/messages and
schedules in the client evaluation environment. Verify visible controls, loading
and failure recovery, account/scope switching, saturation and connection loss.
Compare query count, provider dispatch and cache work as well as elapsed time:
additional concurrency must not reintroduce fetch-discard-refetch, duplicate
permission resolution, repeated inference or unconditional refreshes.

Roll out the implemented negotiated mode through the normal release workflow.
Record exact source/image provenance, selected dispatch mode, configuration and
health, then repeat the focused authenticated read/mutation/recovery workflows.
A rollback changes the selected code/mode through an explicit release or new
connection; it never replays an uncertain request or changes mode mid-flight.

## Exit and handoff

Deliver source-check and cross-language conformance results, real-server
concurrency/authority evidence, and visible client/MCP workflow results. Separate
local verification from merged and deployed status. Record residual physical
writer serialization and any still-ordered operation categories with their
owners; do not describe those as independently concurrent.

Update [ADR 0009](../decisions/0009-segmented-sdk-message-transport.md),
[SDK documentation](../sdks.md), client guidance and the slice index to match the
implemented mode. Mark each slice complete only for its actual acceptance scope.
SQL/cache performance changes beyond this plan remain separate owner work.
