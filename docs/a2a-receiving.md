# Receive A2A messages in Plowshare

Run the separate A2A adapter with a `receive` section selecting one project and one
exported Plowshare agent. External callers discover that agent's public Agent Card
and submit authenticated A2A messages to the adapter. The adapter creates durable
Plowshare messages and reads their outcomes over WebSocket through the Java SDK.

```text
external agent ── A2A JSON-RPC over HTTP(S) ──► plowshare-a2a receiver
                                                     │ authenticated WebSocket
                                                     ▼
                                           durable message and receipt
                                                     │ normal message turn
                                                     ▼
                                       selected Plowshare agent / granted skill
```

The server owns execution, message deadlines, budgets, approvals and transcripts.
The adapter owns its HTTP listener, public URL and external caller credentials.
A receiving context has an isolated persistent agent instance and a passive return
address. Replies remain readable without waking an external sender as a model.

### Upgrade from the earlier receiver branch

The merged migrations are V107 (Agent Cards) and V108 (message ingress). Earlier
receiver builds used V100 and V101; master independently assigned those versions
to information migrations. A database initialized by that earlier receiver
cannot be upgraded by simply replacing its server image: Flyway will reject its
migration history. Keep that deployment on its current image until a coordinated
upgrade has verified a backup, reconciled the two receiver history entries with
V107/V108, and applied the missing master migrations. Do not run a blanket Flyway
repair or reapply the ingress SQL against existing message tables. Fresh databases
and databases migrated by master use the merged sequence normally.

## 1. Configure Plowshare and the adapter

### One general-purpose receiving agent

The A2A entry point selects **one project and one exported agent**. For a broad
entry point, select `interlocutor`, the shipped general-purpose agent. It grants
all visible skills, selected specialist agents, and several workflows. Its tools,
delegation grants, scopes and budgets still govern every incoming message.
It does not give external callers direct access to every agent or project.

Configure the receiving project through the normal project tools. The workspace
must be a path inside the server container or an explicitly provided workspace
provider. To define a server-owned root with the Java SDK:

```java
sdk.request("project.define", Map.of(
    "name", "a2a",
    "workspace", "/workspaces/a2a"
)).requirePayload();
```

This path belongs to the server container, not the SDK process. A server-owned
workspace uses the server's local file provider and needs no live client presence.
For Docker, mount the directory containing the intended repository at that path;
creating an empty workspace directory does not create a repository checkout.
Client-owned workspaces instead require a file-provider session on their machine.

Server-owned project definitions live under
`<data>/projects/<project-id>/agents`, `skills`, and `orchestrations`; global
definitions live under `<data>/global`. Client-local definitions are not used by
the receiver. An agent's prompt can instruct it to delegate ordinary requests
through its granted agents; skill execution still requires explicit invocation.

The receiver generates its Agent Card from the selected agent's **current grants**.
Adding a granted, visible skill or workflow updates discovery after the card's
cache expires. It does not automatically select or execute a skill from a text
description. Use the command extension documented below for an explicit skill.

For Docker, run the adapter as the `a2a-receiver` sidecar from
[`compose.a2a.yaml`](../deploy/docker/compose.a2a.yaml), beside the Plowshare server.
See the [Docker receiving setup](../deploy/docker/README.md#a2a-general-purpose-receiver).

Use a server with database migration V108, an existing project accessible to the
adapter's account, and an **exported** agent served from the server's definitions
for that project. Client-local definitions and filesystem presence are not used.
The selected agent's existing tools, skill grants, orchestration grants and limits
still apply. Configure these through the normal
[agent and skill definitions](skills-and-agent-rules.md).

Build the adapter from the repository root with Java 21+, Node 22.12+ and pinned pnpm:

```sh
./gradlew :plowshare-a2a:installDist
```

Save an operator-owned configuration outside tracked source:

```json
{
  "plowshare": "https://plowshare.example.invalid",
  "project": "my-project",
  "receive": {
    "bind": "127.0.0.1",
    "port": 8093,
    "publicUrl": "https://receiving-agent.example.invalid/rpc",
    "agent": "interlocutor",
    "waitMs": 30000,
    "clients": {
      "research": { "bearerEnv": "RESEARCH_CALLER_TOKEN" }
    }
  }
}
```

| Setting | Meaning |
| --- | --- |
| `plowshare` | Server HTTP(S) origin; the SDK connects to `/v1/events` |
| `project` | Required receiving project; the account must have access |
| `receive.agent` | One exported, served agent in that project's server catalog |
| `receive.bind` | Required explicit listener address |
| `receive.port` | Required explicit listener port, 1–65535 |
| `receive.publicUrl` | Exact public HTTP(S) RPC URL; no credentials, query or fragment |
| `receive.waitMs` | Blocking response deadline, default 30,000 ms, allowed 1–300,000 |
| `receive.clients` | External client aliases, each with a distinct bearer-token environment variable |

Client aliases contain 1–64 letters, digits, underscores, dots or hyphens. An alias
is the caller's durable identity: keep it stable across adapter restarts and token
rotation. Each external client sees only its own tasks and contexts within the
configured account/project. The authenticated adapter supplies these identities to
Plowshare; a remote caller cannot choose another alias in the request body.

Supply `PLOWSHARE_TOKEN` with the adapter's Plowshare access token and
`RESEARCH_CALLER_TOKEN` with a separate token issued to that external caller. Read
secrets from your environment or secret manager; do not put token values in the
configuration, URLs, command-line arguments or agent definitions.

Alternatively, supply `PLOWSHARE_HANDLE` and `PLOWSHARE_PASSWORD` to log into an
existing account when the adapter starts. Authentication uses the existing HTTP
login bootstrap; all message operations still use WebSocket. The account must
have completed its first password change. A supplied `PLOWSHARE_TOKEN` takes
precedence. The Docker entrypoint reads the password from a mounted secret file.
The process authenticates again on supervisor restart; it does not replay
previous message mutations. No automatic credential renewal occurs in a running
process, and rotating/revoking the account credential remains an operator action.

```sh
integrations/a2a/build/install/plowshare-a2a/bin/plowshare-a2a /absolute/private/path/a2a.json
```

Route HTTPS through your reverse proxy to the configured listener. Forward
`Authorization`, `A2A-Version` and `A2A-Extensions`, and expose both `/rpc` and
`/.well-known/agent-card.json` on the advertised origin. `publicUrl` advertises the
external address; it does not configure TLS on the local listener. Set proxy
request timeouts to cover `waitMs`, or use immediate responses and polling.

You may configure outgoing `peers` in the same process. For independent listener
and outgoing availability, run separate configurations/processes. A lost Plowshare
connection ends the process; your supervisor can restart it. Previously accepted
messages and their outcomes remain in Plowshare. The adapter does not refresh its
Plowshare access token automatically.

## 2. Discover the Agent Card

Fetch `https://receiving-agent.example.invalid/.well-known/agent-card.json`.
The public card declares A2A 1.0 JSONRPC, the pinned `publicUrl`, HTTP bearer
security, text input/output, and the selected agent's currently granted skills and
orchestrations. Discovery refreshes at most every 60 seconds; execution checks the
current agent and grants again. The card contains descriptions and capability
metadata, never bearer credentials. Publish an agent whose descriptions and
skill names are suitable for public discovery.

The adapter does not sign the card or serve an authenticated extended card.
Cards are bounded to 64 KiB; an oversized catalog refuses discovery/startup.
Streaming and push notifications are advertised as unsupported. See the
[A2A 1.0 specification](https://a2a-protocol.org/v1.0.0/specification/).

## 3. Send a message and keep its task

Send `POST /rpc`, authenticated as the configured caller, with headers:

```http
Content-Type: application/json
Authorization: Bearer <external caller token>
A2A-Version: 1.0
```

```json
{
  "jsonrpc": "2.0",
  "id": "request-1",
  "method": "SendMessage",
  "params": {
    "message": {
      "messageId": "a57b680c-efaf-46b5-9582-08d34cf86e66",
      "role": "ROLE_USER",
      "parts": [{ "text": "Review the supplied proposal and explain your conclusion." }]
    },
    "configuration": { "returnImmediately": true }
  }
}
```

The result contains `result.task`, including the durable `id`, `contextId` and
current `status`. Retain both identifiers. A new message without `contextId`
creates an isolated context. A new message with that context's `contextId` reuses
its receiving instance and transcript. Contexts are never shared between client
aliases. Each message creates a separate task, and concurrent messages in one
context use Plowshare's normal serial message scheduling.

Within the same account, project, selected agent and client alias, the same
`messageId` and unchanged message recover the same durable task. Changed content
under that identity is refused. A new `messageId` names new work. HTTP JSON-RPC
`id` only correlates the request and response; it does not identify the work.

Only text parts are accepted initially. Text parts are joined with newlines and
must form nonblank text within 64 KiB and the server's Messaging body limit.
The complete RPC body is limited to 128 KiB. File URLs, raw bytes, structured data
and mixed parts are refused before message acceptance. No remote URL is downloaded
and no artifact or filesystem capability is inferred from a text reply.

Without `returnImmediately: true`, A2A defaults to blocking until a terminal or
interrupted state. If `waitMs` expires, the adapter returns HTTP 504 with no success
result. This does **not** cancel the accepted task. Recover explicitly by sending
the unchanged message with the same `messageId` and `returnImmediately: true`, then
poll its task. A lost HTTP or WebSocket response may also leave acceptance
uncertain; do not create a new message identity to retry uncertain work.

## 4. Read or cancel a task

Use the same caller credential and headers:

```json
{ "jsonrpc": "2.0", "id": "read-1", "method": "GetTask",
  "params": { "id": "<returned task id>", "historyLength": 20 } }
```

```json
{ "jsonrpc": "2.0", "id": "cancel-1", "method": "CancelTask",
  "params": { "id": "<returned task id>" } }
```

The result is the task itself. `historyLength` is 0–200, defaults to 200, and zero
omits history. The bridge reads the latest 200 replies; older replies remain in the
durable Plowshare message log. RPC results are bounded to 1 MiB; request a smaller
`historyLength` if the selected history exceeds that limit. Replies appear as agent messages in history and the latest reply
appears in `status.message`. `metadata.plowshareEnding` preserves the actual run
ending. Reply metadata distinguishes generated outcomes from explicit agent
replies and identifies final replies. Terminal tasks cannot be canceled or reopened.

| A2A state | Plowshare meaning |
| --- | --- |
| `TASK_STATE_SUBMITTED` | Durable delivery queued |
| `TASK_STATE_WORKING` | Message handling started |
| `TASK_STATE_INPUT_REQUIRED` | Original message waits for a Plowshare approval continuation |
| `TASK_STATE_COMPLETED` | Handler ended `ANSWERED` |
| `TASK_STATE_FAILED` | Other run ending, such as unavailable inference or exhausted limits |
| `TASK_STATE_CANCELED` | Explicit cancellation or message deadline expiry |

Stopping the adapter does not cancel tasks. Cancellation fences late outcomes and
uses Plowshare's existing job/delegate cancellation path. Approval decisions remain
with the Plowshare operator and original approval record. Sending text does not
approve a command. This first receiver refuses `SendMessage` with `taskId`, including
input-required task follow-ups; use `contextId` without `taskId` for a separate new
task, and answer approvals through Plowshare's existing approval UI/API.

## 5. Explicitly invoke a granted skill or orchestration

Ordinary text, including `/skill:...` text, is delivered as message data. To request
an explicit command, select a qualified command advertised in the Agent Card and
activate its optional extension:

```http
A2A-Extensions: urn:plowshare:a2a:commands:v1
```

```json
{
  "jsonrpc": "2.0", "id": "skill-1", "method": "SendMessage",
  "params": {
    "message": {
      "messageId": "ffbba6bc-26f9-4672-8098-4b7bf090112e",
      "role": "ROLE_USER",
      "parts": [{ "text": "Review these criteria: accuracy and completeness." }],
      "metadata": { "plowshareCommand": "/skill:review" }
    },
    "configuration": { "returnImmediately": true }
  }
}
```

The text becomes the command's arguments through the existing bound-command path.
An explicit skill may select a supported mode with
`/skill:review --mode=DIRECT` (or `INHERITED`, `SUMMARISED`, `NEW`); the normal skill
binding rules determine whether the selection is allowed. If the advertised skill
has no fixed mode, supply `--mode` explicitly; a fixed package mode cannot be
overridden. An orchestration uses
`/orchestration:qualified-name`. Grants, definition hashes, approval handling,
budget leases and command receipts use Plowshare's existing machinery. The command
starts no implicit filesystem provider; any tool execution requires the same
existing server/provider configuration and approvals as a normal run.

When sending from another Plowshare, put the same `metadata.plowshareCommand` on
`outgoing_send`'s message. Its A2A adapter adds the extension header. SDKs still
communicate with Plowshare over WebSocket; the adapter speaks A2A HTTP.

## Supported operations and boundaries

This first receiver supports `SendMessage`, `GetTask`, `CancelTask` and the public
Agent Card. It implements a text-only subset of A2A 1.0. Task listing, streaming,
subscriptions, push notifications, extended cards, OAuth negotiation, binary/data
parts and task follow-ups remain unsupported and return explicit protocol errors.
No silent protocol downgrade, request replay or approval bypass occurs.

The server's protocol-neutral adapter operations are `incoming.catalog`,
`incoming.receive`, `incoming.status` and `incoming.cancel`. Java's `IncomingClient`
provides typed receipt/read/cancel calls. The JS/TS, Python, C# and Go SDKs can use
the same generic operation catalog. These operations trust an authenticated adapter
to supply external client aliases; they are not anonymous A2A endpoints on the
Plowshare server itself.

The container health probe requires `PLOWSHARE_A2A_HEALTH_URL`, the complete
HTTP(S) `/.well-known/agent-card.json` URL reachable from the adapter container.
It does not infer an address or port and does not follow redirects. Configure
`PLOWSHARE_A2A_LISTEN_ADDRESS`, `PLOWSHARE_A2A_PUBLISHED_PORT` and
`PLOWSHARE_A2A_PORT` explicitly for the Compose overlay; the last must match
`receive.port`. Keep the public RPC URL distinct from this readiness URL.
