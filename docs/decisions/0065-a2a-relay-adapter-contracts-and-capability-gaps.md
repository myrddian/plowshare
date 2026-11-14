# A2A Relay adapter contracts and public capability gaps

Status: outbound adapter contract accepted under the authorized Slice 5 rewrite;
inbound/core cutover is being remediated through the separately authorized
[general messaging receiver task](0066-relay-messaging-receiver-and-review-remediation.md).
The table below is the **pre-implementation capability review**. Native receiver,
requester-return and consumer replacement tasks have since passed their independent
acceptance gates; see the [task graph](../relay-review-task-graph.md). Legacy
retirement and integrated verification are now accepted there, with the explicit
user-deferred orchestration-read limitation and separate operational rehearsal. This
historical table is not a current operation catalog.

Follows [0058](0058-a2a-rebuild-on-relay.md) and
[0001](0001-integration-boundary.md). A2A is retained and rewritten outside core.
The adapter owns protocol translation, peer credentials, remote identities and
external dispatch evidence. Relay owns transport ordering and consumer leases;
Plowshare owns conversations, turns, permissions, approvals and accounting.

## Capability review before implementation

| Requirement | Existing public SDK route | Decision |
| --- | --- | --- |
| Adapter request/reply topics, multiple topic sets | `relay.publish`, `relay.consume`, `relay.ack`, `relay.log` with explicit project/account port grants | Available; separate configured topics/groups per adapter binding |
| A2A peer discovery/send/read/cancel and stable remote artifacts | Adapter-owned A2A 1.0 JSON-RPC HTTP transport and typed external content codecs | Available; remote identities remain bound to the configured peer |
| Durable intent before external I/O, crash evidence and no repeated mutation | Adapter-owned locked/fsynced journal, Relay source identity and typed receipts | Available without an outgoing core queue |
| Inbound delivery to a receiving agent topic | Raw Relay TEXT cannot directly enter the trusted inbox; incoming.receive already provides authenticated admission | **Direct raw publication blocked**: the inbox skips `sdk:<hash>` publications and only accepts `messaging:<hash>` publications with existing authorized messaging records/routes |
| Context continuity, idempotent receipt, task read/cancel | `incoming.*` supplies today's adapter-specific implementation; `message.*` supplies instance inspection/lifecycle, not external message admission | **Blocked for removal**: no independent public messaging admission operation binds an external sender/context to an authorized instance, creates its record/route and returns a durable receipt |
| Agent Card and explicit commands from current grants | `agent.list` already projects served commands; incoming.catalog duplicates that discovery | **Admission relocation required**: preserve current-grant command checks inside the owning messaging receiver, rather than creating a new catalog |
| Home Assistant/shared runtime, Discord, Network Privacy Watch | Existing outgoing/incoming SDK consumers | Hold their existing implementations until the corresponding replacement ingress/dispatch contracts are usable; never remove a live dependency first |
| Scheduled agent work | Agent/command targets call the native turn runtime; message targets call receiveScheduled and share incoming native admission | Preserve and rehome shared native admission before removing Incoming DTO coupling or historical context/task storage |

Evidence: `RelayMessagePublications.read` checks the exact server-derived
`messaging:` publisher **before decoding**; `ProjectRelayPorts.publish` derives
`sdk:` publisher identity. `MessagePayload` addresses existing `ins_` instances
and its retained body requires a messaging-owned record. `MessageInboxes.publish`
accepts an already authorized internal participant snapshot, not external
assertions. Knowing an inbox topic or copying the JSON cannot confer this authority.

SDK-only alternatives were considered. `agent.run` does not provide the required
message receipt/routing and context admission semantics. Calling `incoming.*`
preserves the coupling being removed. Publishing a model prompt to a generic
Relay topic does not create an authorized message or a receiving turn. An
orchestration wrapper would create a separate execution path and would not
establish messaging reply ownership. None is an inbound cutover.

The separately authorized remediation task is now specified in
[0066](0066-relay-messaging-receiver-and-review-remediation.md): a messaging-owned
registered Relay receiver using existing publish/consume/log operations. It rehomes
native admission shared with message schedules, current command-grant checks and
a complete retained return path. It does not add another receive/status/cancel WS
family or another command catalog. Existing incoming.receive works; replacing that
surface is a structural task, not proof that SDK inbound delivery is impossible.

The original alternatives review omitted the registered-receiver pattern already
used by the board bridge. The supplied claims review correctly identifies that
omission and that the outbound observation topic alone cannot deliver results to
an agent. A new receiver must verify original requester/instance return bindings;
publishing arbitrary observations onto an agent topic remains forbidden.

## Outbound contract

The replacement outbound worker consumes one TEXT record at a time from its
explicit request topic. Its `plowshare-a2a-dispatch/1` payload has every field:
`requestId` (canonical UUID), `peer` (configured alias), `action` (`SEND`, `READ`,
`CANCEL`), nullable `message`, `task`, `context`. `SEND` requires validated external
message content and null top-level task/context. `READ` and `CANCEL` require a
previously observed task and context for that peer and null message. Send role
and message identity are adapter-controlled. The configured producer publisher
allowlist is checked before parsing; a foreign publication is skipped without
external effects. Malformed authorized inputs and definite pre-effect refusals receive a durable,
source-correlated REJECTED diagnostic before acknowledgement. Read-only peer
discovery uses bounded retries before rejection. Retention gaps, journal failures
and uncertain effects remain operator stops.

A typed `plowshare-a2a-observation/1` reply contains the request UUID, original
source event, authenticated producer publisher, peer and a validated remote observation. Its destination comes
only from configuration. Replies carry remote provenance; they do not resume a
local turn or claim remote usage/cost. A2A's `INPUT_REQUIRED` and `AUTH_REQUIRED`
remain explicit remote states; approvals remain operator actions.

The v2 journal pins server origin, project, topics, group, durable consumer UUID,
producer publishers, peer endpoints and declared stable peer principal labels.
Credential digests are separate; an explicit locked migration requires the exact
previous digest and independent operator verification that replacement credentials
name the same principals. The peer label is an operator assertion, not remote
identity introspection. Timeout changes do not change binding identity. Legacy v1
requires one load under its original matching configuration. No credentials are stored. The journal is
single-owner and must live on the same durable volume across restarts; replicas
must share that journal/lock, not independently consume one group with separate
journals. Separate adapter bindings use separate topic/group sets and journals.
A lease alone cannot prevent an external effect from being repeated after expiry.

Before send/read/cancel I/O, persist/fsync an intent with the canonical request hash and
source identity. A recovered intent becomes `UNKNOWN`; neither send nor cancel
is repeated. A retained task/context can be inspected by an explicit new READ
request for the same peer. A new operation UUID cannot claim another peer's or producer's
observed remote task. Confirmed full records compact into durable direct-lookup UUID receipts and
producer/peer remote-ownership metadata. The active record bound is not a lifetime
operation cap; retained metadata disk usage grows until deliberate retirement of
the whole binding. Storage failures stop before another effect. Dedup evidence
is never silently evicted to make room.

Persist the observation and a stable reply publication intent before publishing.
A definitely NOT_SUBMITTED reply can retry publication only. Unknown submission
stops without automatic replay. Explicit operator abandonment retains evidence
and does not claim delivery. Bounded paged reconciliation to a captured log head
accepts only authorized typed `relay.log` evidence for the configured project/topic
containing the exact event/publisher/content/time/correlation receipt. Once
confirmed, a replacement source lease can acknowledge the original request without
repeating peer I/O. Lost acknowledgement never justifies repeating the external
effect. Request UUID reuse with different content or source identity conflicts.

## Protocol and compatibility

The existing pinned [A2A 1.0 specification](https://a2a-protocol.org/v1.0.0/specification/)
defines `SendMessage`, `GetTask`, `CancelTask`, context/task identity and Agent Card
interfaces. Reuse the adapter's HTTP translation and validated public DTO codecs;
do not migrate the old outgoing queue or expose server classes in acceptance tests.
No redirects, automatic HTTP mutation retries, credential fallback or downgrades.
The outbound worker is an explicit separate process entry point. Existing adapter
entry points stay available until inbound and all dependent consumers can cut over.
This partial implementation must not be represented as completion of Slice 5.

## Verification and remaining work

The SDK-only outbound portion is implemented in `integrations/a2a/.../relay`.
Focused tests cover dispatch-before-I/O persistence, interrupted-intent recovery,
peer response loss, lost reply/source acknowledgements, explicit receipt
reconciliation, producer/peer identity isolation, UUID conflicts, JSON validation,
journal locks/ownership and broker incarnation changes. A real public SDK socket
and independent A2A HTTP fixture verify the adapter path without server imports.
Both legacy and replacement launch scripts build; the replacement script loads its
entry point and validates arguments. Google formatting, `formatCheck`, repository
`check` and `integrationBoundaryCheck` passed without database opt-in.

These are local transport/behavior checks, not evidence of a live deployment or
inbound cutover. The module no longer uses server classes in its tests; the bundled
manifest declaration check makes no claim to prove server authorization.
The server HTTP-client inventory now names the existing A2A HTTP boundary, which
had previously been hidden from its import scan by a wildcard import.
No SDK, shared protocol or core production implementation was extended for this
adapter. The platform receiver/command-admission relocation, complete agent return loop,
dependent consumer migrations and legacy core removal remain unfinished until
the task graph reviewers accept them; Slice 5 must not be marked complete.

## Review corrections

The reviews are preserved with task traceability in the task graph. Public
agent.list command discovery and scheduled message dependencies were missed in the
original assessment. SDK socket tests use a fake broker and establish no server
RBAC proof. Agent Card discovery is read-only authenticated network I/O preceding
the send marker. First-send UNKNOWN without observed remote IDs cannot be repaired
by inventing a READ target. The baseline adapter had a hard journal cap and strict credential fingerprint;
the remediation implements v2 compaction, explicit identity-preserving credential
migration and uncertainty recovery. Acceptance remains subject to the task graph
reviewers and actual checks. Batch.generation existed in the saved pre-Slice-5 snapshot for group retirement;
one squash commit obscures that chronology. Server test-only reverse dependencies
are allowed by 0001, but independent acceptance ownership is reviewed separately.
