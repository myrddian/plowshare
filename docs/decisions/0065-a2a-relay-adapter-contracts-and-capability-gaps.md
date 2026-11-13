# A2A Relay adapter contracts and public capability gaps

Status: outbound adapter contract accepted under the authorized Slice 5 rewrite;
inbound cutover and removal remain blocked by the public capability gaps below.
No platform extension is authorized or implemented by this record.

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
| Inbound delivery to a receiving agent topic | Publishing TEXT through Relay is available, but authenticated messaging admission is not | **Blocked**: the inbox skips `sdk:<hash>` publications and only accepts `messaging:<hash>` publications with existing authorized messaging records/routes |
| Context continuity, idempotent receipt, task read/cancel | `incoming.*` supplies today's adapter-specific implementation; `message.*` supplies instance inspection/lifecycle, not external message admission | **Blocked for removal**: no independent public messaging admission operation binds an external sender/context to an authorized instance, creates its record/route and returns a durable receipt |
| Agent Card and explicit commands from current grants | `incoming.catalog` is the current served-agent/command projection | **Blocked for removal**: general agent/run and Relay operations do not replace its current served-command admission contract |
| Home Assistant/shared runtime, Discord, Network Privacy Watch | Existing outgoing/incoming SDK consumers | Hold their existing implementations until the corresponding replacement ingress/dispatch contracts are usable; never remove a live dependency first |
| Scheduled agent work | Current `events/AgentRunner` directly calls the native turn runtime | Already independent of `incoming.*`; no adapter workaround is needed |

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

A separate general platform task is needed before the inbound rewrite can ship:
define authenticated external message admission with explicit project/agent and
external principal/context identity, idempotent receipts and authorized task
inspection/cancellation; keep instance routing, definitions, conversation
visibility, budgets and approvals owned by their existing services. Define a
public current-grant command projection/admission contract independently of A2A.
That task must settle identity namespaces, principal isolation, reply routing,
retention, cancellation, migrations and compatibility tests before implementation.
This document proposes no permission bypass, internal publisher selection,
transport escape hatch or adapter-specific replacement endpoint.

## Outbound contract

The replacement outbound worker consumes one TEXT record at a time from its
explicit request topic. Its `plowshare-a2a-dispatch/1` payload has every field:
`requestId` (canonical UUID), `peer` (configured alias), `action` (`SEND`, `READ`,
`CANCEL`), nullable `message`, `task`, `context`. `SEND` requires validated external
message content and null top-level task/context. `READ` and `CANCEL` require a
previously observed task and context for that peer and null message. Send role
and message identity are adapter-controlled. The configured producer publisher
allowlist is checked before parsing; a foreign publication is skipped without
external effects. Unknown fields, malformed JSON, invalid content, unsupported
carriers and retention gaps stop the worker without acknowledgement.

A typed `plowshare-a2a-observation/1` reply contains the request UUID, original
source event, authenticated producer publisher, peer and a validated remote observation. Its destination comes
only from configuration. Replies carry remote provenance; they do not resume a
local turn or claim remote usage/cost. A2A's `INPUT_REQUIRED` and `AUTH_REQUIRED`
remain explicit remote states; approvals remain operator actions.

The journal binds to the server origin, caller credential fingerprint, project,
topics, group, durable consumer UUID, producer publishers and configured peer
endpoints/credential fingerprints. It never stores credentials. The journal is
single-owner and must live on the same durable volume across restarts; replicas
must share that journal/lock, not independently consume one group with separate
journals. Separate adapter bindings use separate topic/group sets and journals.
A lease alone cannot prevent an external effect from being repeated after expiry.

Before external I/O, persist/fsync an intent with the canonical request hash and
source identity. A recovered intent becomes `UNKNOWN`; neither send nor cancel
is repeated. A retained task/context can be inspected by an explicit new READ
request for the same peer. A new operation UUID cannot claim another peer's or producer's
observed remote task. The journal never evicts operation identities automatically;
capacity exhaustion stops before another external effect.

Persist the observation and a stable reply publication intent before publishing.
After an uncertain reply publication, stop instead of republishing. Reconciliation
accepts only an authorized typed `relay.log` page for the configured project/topic
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
adapter. The platform admission/catalog gaps, dependent consumer migrations and
legacy core removal remain unfinished; Slice 5 must not be marked complete.
