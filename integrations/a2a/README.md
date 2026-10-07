# Plowshare A2A adapter

For setup, CLI/SDK examples, follow-ups, cancellation and recovery, use the
[A2A sending manual](../../docs/a2a-sending.md).

Plowshare sends and receives A2A messages through a separate Java process.
For inbound authentication, public Agent Cards and task handling, follow the
[A2A receiving manual](../../docs/a2a-receiving.md).

Outgoing work:

```
agent / SDK / CLI -- Plowshare WS --> durable outgoing work
                         ^
                         | WS claim/report
                    A2A adapter -- A2A 1.0 JSON-RPC/HTTP --> configured remote agent
```

The adapter depends on `plowshare-sdk`; it imports no server classes. Plowshare knows
peer aliases, fetched Agent Cards and durable messages/results. The adapter owns endpoints, authentication
and A2A wire translation. Remote execution and files remain opaque external work.

Build with `./gradlew :plowshare-a2a:installDist`. Set `PLOWSHARE_TOKEN`, configure an
operator-owned JSON file, and launch
`integrations/a2a/build/install/plowshare-a2a/bin/plowshare-a2a /path/to/config.json`.

The distribution includes [an A2A Application template](examples/application/plowshare.json).
Copy it to the server's intended Application root and add explicit `access.accounts`
grants for the operator and adapter account (a service token uses its owner's
handle), with matching server membership. The template is hidden until granted.
Register the root through [Application creation or adoption](../../docs/projects.md#create-or-adopt-an-alias-based-application).
The adapter configuration's `project` field selects that Application; a binding
alone does not create or classify it. The adapter's private endpoint and credential
configuration remains outside the Application template.

```json
{
  "plowshare": "https://plowshare.example.invalid",
  "project": "my-project",
  "peers": {
    "research": {
      "endpoint": "https://agent.example.invalid/rpc",
      "agentCard": "https://agent.example.invalid/.well-known/agent-card.json",
      "bearerEnv": "RESEARCH_AGENT_TOKEN"
    }
  }
}
```

Omit `project` for global work and `bearerEnv` for a peer needing no authentication.
Credentials are read from the named environment variables. No token belongs in the
configuration file, a URL or command-line argument. The Plowshare account must have
access to the configured project. Advertisements are account/project scoped and
expire after two minutes without renewal. The adapter operates one claim at a time;
run additional instances for capacity.

The sender pins the [A2A 1.0 JSON-RPC binding](https://a2a-protocol.org/v1.0.0/specification/),
using `A2A-Version: 1.0`, `SendMessage` with `returnImmediately: true`, `GetTask` polling
and `CancelTask`. Before advertising/claiming, the adapter fetches each Agent Card,
validates the pinned endpoint's A2A 1.0 JSONRPC interface and publishes its description,
skills and capabilities to Plowshare's `outgoing_peers` tool and `outgoing.peers`
WebSocket operation. Omit `agentCard` to use the endpoint origin's
`/.well-known/agent-card.json`. Expired cards use conditional revalidation; invalid
cards stop dispatch. See the manual for cache, size and credential boundaries.
Version negotiation, authenticated extended cards, SSE, webhooks, OAuth flows are deferred. Inbound text serving is described in the receiving manual.
This is a sender subset, not an implementation of every A2A operation. No downgrade,
redirect or automatic HTTP send retry is performed.

## Inbound status

Messaging and Skills are implemented. Inbound text receipt, context continuity,
task reads, cancellation and public Agent Cards are now supported. The receiver
publishes granted commands and binds explicitly requested commands through the
existing skill/orchestration path. Follow the
[receiving manual](../../docs/a2a-receiving.md) for the supported subset and
operator-owned approval handling.

## Send and observe

Using the Java SDK on the same account/project:

```java
var outgoing = new OutgoingClient(sdk);
var receipt = outgoing.send(new Outgoing.Send(
    UUID.randomUUID(), "research",
    Map.of("parts", List.of(Map.of("text", "Research this question"))),
    "my-project", null));
var observed = outgoing.status(receipt.id());
// outgoing.cancel(receipt.id()) requests cancellation; check status for its outcome.
```

Retain the request UUID before submitting. Repeating the identical `outgoing.send`
with that UUID recovers the same receipt; changed work with that UUID is refused.
The adapter supplies A2A `messageId` from the durable work ID and `ROLE_USER`. Content
supports A2A 1.0 text, URL and base64 raw bytes. Structured data parts use the
registered integration DTOs; unsupported object shapes are refused. Metadata is
limited to the declared command, ending, generated and final fields. Remote
messages and artifacts are validated against the supported result DTOs. URLs are
references; neither Plowshare nor this adapter downloads them or makes remote
filesystem claims.

The TS headless CLI also exposes `outgoing send`, `outgoing status`, `outgoing cancel`
and `outgoing peers`. Send takes a JSON payload with `requestId`, `peer`, `message`
and optional `project`/`conversation`. Agent definitions can explicitly grant
`outgoing_peers`, `outgoing_send`, `outgoing_read`, `outgoing_cancel`. These use the
runtime's immutable account, project and conversation; a model cannot choose its
own owner or project. Existing shipped bot grants are unchanged.

## Delivery and state

`QUEUED` becomes `DISPATCHED` in a committed transaction before remote I/O. Only one
adapter can claim the initial send. Reports require the claiming session and current
revision; stale or foreign reports are refused. Queued cancellation is local and
prevents dispatch. After dispatch, cancellation is a remote request whose outcome
must be observed.

Remote task states map to the corresponding local state; `SUBMITTED` maps to
`WORKING`. A direct agent message completes that outgoing message without inventing
a remote task. `INPUT_REQUIRED` and `AUTH_REQUIRED` preserve the remote task and
context. To respond, explicitly send a new message/receipt containing the returned
`taskId` and `contextId`; terminal tasks cannot be reopened. This first version does
not automatically resume a Plowshare run or satisfy remote authentication requests.

A lost initial remote response becomes `UNKNOWN` and is never resent. A process that
dies after claiming but before reporting can leave `DISPATCHED` with no remote task;
that also means uncertain delivery and requires operator reconciliation. If a remote
task ID was durably reported, a replacement adapter can resume reads/cancellation
after the 30-second claim lease. Initial sends are never reclaimed. A lost WS report
acknowledgment stops that adapter; inspect the durable record before starting another.

Remote observations may be retried through `GetTask`. Neither a timeout nor malformed
remote response is recorded as successful completion. Remote usage/cost, internal
steps, filesystem execution and artifact availability are not asserted by Plowshare.
Results represent what the peer reported, with their remote provenance intact.
