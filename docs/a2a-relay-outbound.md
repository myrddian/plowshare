# A2A outbound dispatch over Relay

This is the SDK-only outbound portion of Slice 5. It runs outside Plowshare core
and uses the existing Relay ports. It does not complete the inbound rewrite or
replace existing incoming/outgoing consumers. See
[ADR 0065](decisions/0065-a2a-relay-adapter-contracts-and-capability-gaps.md)
for the reviewed requirements and precise public capability gaps.

Build `:plowshare-a2a:installDist`; run its `bin/plowshare-a2a-relay CONFIG` launcher.
The existing `plowshare-a2a` launcher remains the legacy receiver/sender until the
inbound and dependent-consumer cutover can be completed. Java 21 is required.
No local Plowshare origin, human login, bind address or credential is inferred.
The replacement is an outbound worker and opens no HTTP listener.

## Deployment binding

Configure a durable private POSIX journal directory, explicit server origin,
service credential environment variable, project, request/reply topics, consumer
group and persistent consumer UUID. Configure the permitted producer publisher
identities and the adapter's expected SDK publisher identity. SDK publisher values
are `sdk:` followed by `RelayPort.hash(account)`. Use explicit known account
identities; do not trust a request body to select the publisher or reply topic.
Each topic set is an authority boundary: only give ingress access to producers
allowed to send/cancel work with these configured peer credentials.

The adapter account needs current project work access, an EGRESS port binding for
the request topic/group, an INGRESS binding for the reply topic, and public
`relay.log` access for reconciliation. Producers need their own request-topic
INGRESS grant, or the existing exact-topic `relay_publish` tool grant. A separate
reply consumer uses its own EGRESS grant/group. These are deployment configuration
and existing permissions, not additional server APIs.

Example configuration template; replace the directory, UUID and publisher values
with the actual binding before running:

```json
{
  "origin": "https://plowshare.example.invalid",
  "tokenEnv": "PLOWSHARE_SERVICE_TOKEN",
  "journal": "/REPLACE_WITH_A_DURABLE_PRIVATE_DIRECTORY",
  "project": "integration-project",
  "requests": "adapters.research.requests",
  "replies": "adapters.research.observations",
  "group": "research-outbound",
  "consumer": "11111111-1111-1111-1111-111111111111",
  "replyPublisher": "sdk:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "producers": ["sdk:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"],
  "maxDepth": 32,
  "peers": {
    "research": {
      "endpoint": "https://research.example.invalid/rpc",
      "bearerEnv": "RESEARCH_PEER_TOKEN",
      "timeoutMs": 20000
    }
  }
}
```

`agentCard` may explicitly select the peer's card URL. Otherwise discovery uses
that configured endpoint origin's well-known card path. `bearerEnv` is optional
for a public peer; configuring it requires the variable to exist. Peer timeouts
are 1..300000 milliseconds, defaulting to 20000. `maxDepth` must be explicitly
set to the deployment's applicable effect ceiling (1..32). Unknown settings,
coerced numbers, malformed addresses, missing credentials and duplicate JSON
fields fail before a connection or external dispatch.

One binding has one journal. A replacement process reuses that durable volume
and consumer identity. Replicas must share the same journal and exclusive file
lock; separate journals consuming the same group are unsafe. Different adapters
use disjoint topic/group sets and journals. Peer endpoints, credential fingerprints
and scope are part of journal ownership. Credential/destination changes therefore
require explicit reconciliation/migration; they cannot silently retarget old work.

## Producer contract

Publish a TEXT value using `RelayClient.publish`, another SDK's existing Relay
publication operation, or the granted `relay_publish` tool. The adapter-owned
`RelayWork.Request` / `RelayWorkCodec` are Java helpers; no shared SDK operation
or Relay envelope change is introduced.

```json
{
  "schema": "plowshare-a2a-dispatch/1",
  "requestId": "22222222-2222-2222-2222-222222222222",
  "peer": "research",
  "action": "SEND",
  "message": {"parts": [{"text": "Investigate this question."}]},
  "task": null,
  "context": null
}
```

Every outer field is required, including nulls. `requestId` is a retained
canonical UUID. A SEND message uses the existing validated external-content DTO;
its message ID and role must be omitted, because the adapter sets them from the
request identity and `ROLE_USER`. Optional remote context/task identities inside
that message must already have been observed for that peer. Explicit command
metadata retains the existing A2A command-extension negotiation; it never grants
local tools or approves an operator decision.

READ and CANCEL carry null `message`, plus the observed remote `task` and
`context`, and use a fresh operation UUID. The adapter binds remote identities to
the configured peer and authenticated producer publisher; another peer or producer
cannot adopt them. The producer must retain
both the operation UUID and original Relay publication identity/content/time.
Reusing an operation UUID with a different source publication or intent is a
conflict, not permission to send the external message again.

The adapter validates the complete request before peer dispatch and refuses
unknown fields. Foreign publishers are acknowledged without decoding their
content. Malformed authorized requests, unsupported carriers, unavailable effect
ancestry and retention gaps stop visibly without skipping the request.

## Observations, crash recovery and reconciliation

The configured reply topic receives a `plowshare-a2a-observation/1` TEXT record
with `requestId`, `sourceEvent`, authenticated `producer`, `peer` and typed
`observation`. Remote task states,
text artifacts and remote provenance are retained. `INPUT_REQUIRED` and
`AUTH_REQUIRED` require an explicit producer/operator response; they do not
approve work or resume a local turn. External usage/cost is not invented.

A locked journal commits and fsyncs intent before send/read/cancel. Restart after
that marker cannot prove whether I/O occurred, so it emits UNKNOWN without
repeating the operation. A new explicit READ may inspect a previously observed
remote task/context. No automatic HTTP mutation retry, redirect, reconnect,
credential renewal or protocol downgrade occurs.

Observations and stable reply publication intent are also persisted before the
SDK publish. If its acknowledgement is lost, the worker stops. Inspect an
existing reply-log page using:

```sh
plowshare-a2a-relay CONFIG reconcile AFTER
```

`AFTER` is the Relay decimal cursor, with `0` selecting the first retained page.
Each command reads at most 100 rows and prints a next cursor. It confirms only
an exact retained reply matching publisher, event ID, content, occurrence time,
correlation and parent source identity. It never republishes. If no retained
match exists, the outcome stays unresolved; an absent/expired row is not proof
that publication failed. After confirmation, restart normally to acquire a fresh
source batch and acknowledge without repeating peer I/O. Lost source-ack
responses likewise do not repeat external effects.

The journal retains at most 1024 operation identities and 64 MiB, reserves result
space before external dispatch, and never evicts identities automatically.
Capacity exhaustion requires operator action before more work. Do not delete or
reset it to resume an existing group. Archive/reconcile the old binding and
establish a new topic/group/journal binding deliberately. A changed broker group
incarnation is refused for the same reason.

The focused tests use a real public SDK WebSocket, an independent A2A HTTP
fixture and durable temporary journals. They establish protocol/dispatch behavior
and crash handling without server internals or PostgreSQL. They do not establish
a live deployment, inbound agent delivery or removal of the old core surfaces.
