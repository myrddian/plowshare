# General messaging admission and returns through Relay receivers

Status: authorized platform design for remediation tasks T19/T20 in
[the task graph](../relay-review-task-graph.md). The user's 13 November 2026 request
explicitly asks parallel agents to implement the TODOs in the two supplied reviews.
This record scopes the general platform task separately from SDK-only adapter work
under [0001](0001-integration-boundary.md). Implementation and independent review
must complete before cutover; this record does not assert they have happened.

## General platform need

Messaging owns durable participant instances, conversation continuity, correlated
replies, cancellation, allowances, approvals and paid execution. Public Relay
producers and native message schedules need to enter that same owning admission
path without assuming A2A, manufacturing messaging IDs or creating another runtime.
The need includes SDK applications, Discord/integration producers, native scheduled
messages and return delivery from external work, independently of any A2A test.
The forum remains independent of messaging storage and authority.

The existing `incoming.receive/status/cancel/catalog` implementation already delivers
messages. Raw `relay.publish` into the private agent inbox does not: its publisher
and record checks intentionally reject external assertions. `agent.list` already
projects served commands. Message-target schedules already share native incoming
admission. The replacement problem is architecture and complete reply ownership,
not the absence of all public delivery or catalog operations.

## Existing alternatives

- Retaining `incoming.*` works during migration but preserves the separate ingress
  surface and transport-shaped native DTO coupling being retired.
- `agent.run` lacks the durable message request/context/return contract. An
  orchestration wrapper cannot stand in for the owning message allowance or reply.
- Letting SDK publishers claim `messaging:` or assert `ins_`/`bdm_`/`cnv_` values
  would weaken authentication and is rejected.
- A replacement WS receive/status/cancel family would reproduce `incoming.*`.
  No new WS operation, Relay envelope field or general carrier kind is chosen.
- Registered owning receivers already supply durable dispatch intent, live authority,
  receipt inspection and bounded recovery. The platform extends that mechanism.

## Ingress contract and authority

Register messaging-owned `message.receive`, `message.inspect` and `message.cancel` receivers. The
operator route pins the agent and return topic; source project remains authoritative.
Only an explicitly designed, consent-checked cross-project route may widen this.
Payloads cannot override account, publisher, project, agent, return topic or instance.
A `plowshare-message-request/1` TEXT command identifies a canonical request UUID,
client alias, optional context UUID, body and optional explicit qualified command.
Cancellation identifies its own stable request UUID and the scoped task UUID.
Inspection identifies its own query UUID and a scoped task or original request
UUID, so a lost first receipt can be recovered without repeating admission.
Each snapshot has a retained monotonic revision; a repeated query UUID recovers
its original snapshot and a fresh query UUID reads current state. Broker log
expiry never proves task absence or turns a stale adapter projection authoritative.
Exact DTO fields, null semantics, bounds and schema are validated before admission.

Resolve the publishing principal from server-owned provenance and recheck current
project work access, the exact source ingress grant and any configured return-topic
authority. Route configuration is not permission. Execution publishers remain a
distinct server-only provenance namespace; they are not interpreted as human SDK
publishers. Context/task ownership includes authenticated principal, project,
route/channel identity and client alias. An alias supplied by a producer is not
independent authentication; the adapter must bind it from its own authenticated
client mapping, and distinct bridges use separate trusted channels/credentials.

At dispatch, resolve the current receiving definition and validate an explicitly
requested command against the same CommandCatalog used by `agent.list`. Never
infer a command from plain text, trust a stale advertised card, inherit arbitrary
tools from an external sender or approve work. Existing workspace, membership,
tool grants and approval services remain authoritative.

## Native admission, replies and scheduling

Extract a typed, transport-independent native admission capability from the current
receive implementation. Both message-target schedules and the Relay receiver use
it. Native participant creation, context continuity, two-sided routing where
applicable, request locks, per-message allowances, native wakes and paid execution
retain their owners. The receiver transaction retains the request/participant/return
binding and owning receipt with the message publication. A duplicate request
recovers that immutable receipt; different reuse is a conflict, not another run.

Keep the private agent inbox and MessagePayload publisher/record validation intact.
Native admission is the only route from an accepted external command into it.
The return binding is minted from authorized server snapshots, never from a body
claiming the original instance. Receipts, replies and terminal outcomes are
published by messaging to the route-pinned return topic in an atomic outbox path.
Cancellation races, generated final replies and permission loss must not produce
an invented completion or duplicate paid turn. Retained task history survives
broker retention.

External consumers read the return topic through existing SDK consume/log operations.
An adapter may retain a bounded projection for its public GetTask view; that is not
an execution queue. After restart it reconciles authoritative retained events and
receipts. A first external send whose remote identity is unknown remains UNKNOWN;
no remote identity is fabricated and no external mutation is repeated.

Outbound observations and forum results enter through a messaging-owned retained
return binding associated with the original request. Merely routing arbitrary
observation JSON to a pinned agent is insufficient: the server must correlate the
original producer/run/instance and accepted request, reject forged returns, and
preserve final/reply semantics. The board never manufactures trusted inbox payloads.

## Retained execution exports

An explicit, typed `plowshare.messaging.exports` policy binds project, request
topic, trusted channel, return topic and adapter account. An empty list disables
exports. Duplicate/looping bindings are rejected. The execution-publication owner
calls a typed observer before appending a new export inside its existing
transaction; ordinary publications without a matching policy retain their meaning.
The messaging implementation validates the exact retained execution and creates
an owning native message/export binding atomically with the execution receipt.
Only an exact native MESSAGE source or explicitly account-owned TURN caller is
supported initially; other source kinds fail before the configured export.

A `plowshare-message-return/1` TEXT command contains only `requestId` (its own
mutation UUID), `sourceEvent` (the authoritative exported event UUID), `body` and
`finalReply`. It cannot assert an instance, account, agent, approval or execution
ending. A separately configured `message.return` receiver authenticates the exact
adapter publisher, live return-topic ingress and exporting account authority, and
uses the retained participants and native original message. Return UUID/fingerprint
receipts survive broker expiry. The original export pins the return topic's
incarnation; deletion/recreation cannot retarget an old request.

`relay_publish` does not suspend a source turn. A reply is an ordinary correlated
native message, queued until the owning lifecycle can admit it. In particular,
AWAITING/approval cannot be interpreted as an external wait or automatically
continued. A final external reply settles only its exported native message, not
the source agent execution. Board-owned returns can reuse this native capability
through their own retained binding; they do not gain the adapter receiver's SDK
identity or manufacture trusted inbox payloads.

## Bounded snapshots and exact execution ancestry

Task events use schema `plowshare-message-task/1`, an immutable operation UUID and
source event, client, monotonic revision, task or typed refusal, and nullable
`next`/`resume` cursors. A positive page contains at most two reply chunks, each
at most 16384 Unicode codepoints, with immutable reply identity, exact codepoint
offset and completion flag. A traversal cursor fixes the captured reply head;
current header state remains revisioned independently. Once that head is fully
read, a FOLLOW cursor captures only subsequent replies on the next fresh query.
Adapters commit exact assembled chunks and cursor before acknowledging/advancing
local projections. Older revisions cannot overwrite newer metadata. A negative
snapshot discloses no foreign task and is never evidence authorizing mutation replay.

The native message body-byte limit still applies to returned reports. Large
reports require an explicitly suitable deployment limit; they are not silently
truncated or converted into many automatically paid wakes. Adapter evidence keeps
the complete report when native admission is refused.

Causal native turns reserve an ordinal and immutable source atomically before job
registration. The job receives that exact source; the shared source repository
checks authenticated accounting run, project, account, definition and turn before
returning it. The conversation's first root stays historical evidence rather
than a mutable slot for whichever definition last woke. MESSAGE history without
an exact source cannot borrow another root. Ordinary historical conversation
fallback remains only where the existing non-message contract allowed it.
The job trigger admits a divergent explicit cause only with matching retained
source evidence; typed reservation/submission, rather than a job-table turn column,
enforces exact job-to-turn identity. Selected SQL and concurrent-source tests
verify this boundary and rollback.

## Migration and compatibility

Introduce replacement native contracts before migrating A2A inbound, shared runtime,
Home Assistant, Discord and Network Privacy Watch. Keep current operations working
until every consumer and schedule has a tested replacement. Separate platform
stores from adapter-owned peer credentials/protocol mappings. Preserve historical
message/context/task IDs and required evidence rather than dropping records because
they were created through the old transport.

Only after consumer acceptance remove obsolete incoming/outgoing WS operations,
SDK clients, tools, queue wiring and derived catalogs together. Regenerate native
contracts with owning scripts; do not hand-edit generated files. Schema changes are
new Flyway migrations. Old migration files are not rewritten to make tests pass.
Test-only server/adapter dependencies are not themselves a production boundary
violation; any relocation must retain independent end-to-end coverage.

## Verification and gates

Mocked tests cover malformed DTOs, scoped actor/context/task authority, current
command grants, source/return grant revocation, private/personal scope, conflict
recovery, approvals and unsupported provenance. Selected PostgreSQL tests are
required for new migration conservation, transactional publication/outbox/receipt
rollback, locking, cancellation/reply races and retained identity beyond broker
expiry. State the reason and select the owning test classes with `-PfullDb`.

Independent SDK transport tests cover the full request→message→reply loop and an
independent A2A client covers the translated protocol. A fake broker test establishes
framing/adapter behavior, not server RBAC. Concurrent context/compaction tests must
cover two definitions sharing a conversation before existing entry points disappear.
Default check, formatting, integration boundaries and any changed SDK/package gates
must pass on the final combined tree. No live deployment or production-data rehearsal
is claimed from mocked or synthetic data.

Adapter recovery design must not infer non-delivery from absent/expired log rows,
use a local lease epoch to claim global fencing, or erase credential/principal
identity checks to permit rotation. These are independent remediation tasks with
independent reviewers; the task graph is the completion authority.

## Owning implementation guides

See [native messaging receivers](../relay-messaging-receivers.md) for strict wire
fields, channel configuration and explicit historical adoption. See
[retained requester returns](../messaging-relay-exports.md) for execution export
policy, native board returns and lifecycle limits. Consumer migration decisions
remain SDK-only; the [runtime cutover decision](../../integrations/runtime/docs/relay-cutover.md)
records its public capabilities and verified limitations.
