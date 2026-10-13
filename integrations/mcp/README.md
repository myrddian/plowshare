# Outbound MCP integration

One reusable Node integration connects Plowshare's existing hosted ToolProvider
mechanism to external MCP HTTP servers. Configure multiple hosted bindings in one
executor process. Each binding has its own connection, credential subject,
upstream client/session, discovery snapshot, offered tool names and SDK journal.
Two accounts using the same endpoint never share sessions or discovery caches,
even when the MCP server advertises a public cache scope.

This is an external consumer of `plowshare-client-node` and
`plowshare-client-ts`. It imports no server internals and adds no core API,
database table, tool registry or execution ledger. `plowshare-mcp` remains the
separate inbound MCP server. Native instance hosting and STDIO are separate work;
the `McpSessions` capability can support that future transport without replacing
the provider lifecycle.

## Configure and start

Build from a checkout with Java 21, Node 22.13+ and pnpm available:

```sh
./gradlew :plowshare-mcp-integration:mcpIntegrationBuild
node integrations/mcp/build/mcp/main.js /absolute/path/to/mcp-config.json
```

Supply these fields in the private configuration file. Values below are
placeholders to replace with deployment configuration:

```json
{
  "origin": "https://plowshare.example.invalid",
  "tokenEnvironment": "PLOWSHARE_MCP_EXECUTOR_TOKEN",
  "journalDirectory": "/absolute/private/mcp-journals",
  "pollMs": 250,
  "workers": [
    {
      "binding": "11111111-1111-1111-1111-111111111111",
      "generation": 1,
      "protocol": "2026-07-28",
      "allowHttp": false,
      "allowPrivateNetwork": false,
      "timeoutMs": 10000,
      "timeoutSeconds": 30
    }
  ]
}
```

The environment variable contains the configured executor service account's
Plowshare token. Upstream MCP credentials come exclusively from the server's
`tool.scope.worker.credential` operation. Keep the configuration, environment and
journal directory private. Each executor token can serve only its authorized
bindings; use separate processes for different executor accounts.

Create the server-owned provider connection and credentials through the existing
provider management clients, then create a hosted binding for that connection:

- The binding owner selects the connection, credential subject, executor,
  allowed agents and grants. An absent credential subject means the owner's
  credentials; a service account subject selects that account's credentials.
- The connection record owns the exact upstream endpoint. The adapter accepts
  HTTP(S) only, with no URL credentials, query secrets or fragments. HTTPS is
  required unless the operator explicitly allows HTTP. Private, loopback and
  reserved addresses require explicit `allowPrivateNetwork` permission. Every
  DNS answer is checked; the selected address is pinned for the session while
  TLS verifies the configured hostname. Redirects cannot widen the endpoint.
- Hosted prefixes must end in an underscore and contain at most 24 characters
  for this adapter, for example `mcp_`. Configure the existing binding's provider
  label and agent/grant policy; the server supplies the actual hosted route and
  executor identity used by ToolProvider.
- `generation` must match the current hosted binding. A changed binding requires
  updated configuration. No model argument can select a URL, subject or token.

Credential selection does not relabel the invocation's original caller or model
accounting. Service-account spending pools require the separate accounting
capability; using a service account's MCP token alone does not create a pool.

## Protocol and schema profile

The adapter pins the [official TypeScript MCP client](https://github.com/modelcontextprotocol/typescript-sdk)
to 2.3.1 and implements the repository's
[bounded tools profile](../../docs/mcp-provider-profile.md). Select exactly
`2026-07-28` (stateless discovery and per-request metadata) or `2025-11-25`
(initialize and private HTTP session). It never probes one era and silently falls
back to another. Only tools are offered; there is no roots, sampling, elicitation,
task execution, resource/file bridge or native process launch.

Discovery uses explicit pages through the SDK's typed protocol requests, bypassing
automatic pagination and cache writes. It validates the complete catalogue before
publication: at most 32 pages, 128 tools, 1 MiB per HTTP response and a configured
total discovery deadline. Duplicate names/cursors, unsupported schema constraints,
task-required tools, parameter header extensions or an oversized publication
refuse the catalogue. Nothing is published partially.

Nested input schemas and arguments use the same shared SDK schema and value
validators as native Plowshare tools. Constraints are preserved. Each offered name
contains a readable upstream slug and 128 bits of a SHA-256 contract hash covering
the protocol, binding generation, owner/subject, connection/revision, credential
generation, timeout and complete tool metadata, including input/output schemas.
An immutable catalogue pins the original upstream name. Changed contracts produce
different names; a collision refuses publication.

Results support bounded text blocks and portable JSON `structuredContent`.
Successful results with an output schema must validate against it; any supplied
structured error result is also validated. Images, resources, continuations,
invalid schemas/values and results exceeding the SDK's 16,384-character/byte text
budget are unsupported. Text and structured values are encoded in the existing
ToolResult text envelope, preserving `isError`. A valid received MCP error result
is `COMPLETED` with `isError: true`; failure to verify a post-dispatch response is
`UNKNOWN`. Authorization, stale credentials, expired deadlines or busy contexts
refuse before dispatch as `REJECTED`.

## Lifecycle and recovery

Each binding registers through public worker operations with a UUID durably saved
before submission. Every invocation requests an EXECUTION credential for its
exact invocation ID and checks it against the discovery context. A credential
rotation, connection revision, catalogue change or uncertain effect closes the
context and stops offering its tools. Start a new context after reviewing the
cause; there is no credential refresh or MCP effect retry.

The runner republishes only after fresh discovery validation and lease renewal.
There is at most one upstream effect in flight per binding. Invocation deadlines
abort physical HTTP requests and response streams; process shutdown stops intake,
aborts in-flight requests, attempts catalogue withdrawal and stops worker renewal.
The SDK WebSocket request timeout also bounds a pending credential or publication
request. The worker lease expires on shutdown; there is no invented unregister
operation. Core cancellation propagation remains separate work.

ToolProvider saves SDK receipts before execution and result submission. The private
directory uses an exclusive binding lock, atomic private files and file/directory
sync. It retains immutable catalogue declarations and original worker fences so
the SDK can reconcile result publication across catalogue or credential rotation
using read-only owning-receipt operations. Retired catalogues are never polled or
executed. Credentials and upstream session IDs are never persisted. Invocation
arguments/results are private recovery data and may themselves contain sensitive
user content.

Uncertain result publication is read-only reconciled. Recovery under an unchanged
catalogue uses the SDK's existing executing/ready/publishing rules and never
reexecutes a retained invocation. Retired executing/ready receipts remain retained
for inspection; the adapter does not invent result authority for them. Archive
settled state deliberately before the bounds of 1,000 receipts per catalogue,
128 recovery catalogues or 2 MiB per private file are reached.

The public API has no owning receipt read for catalogue publication or registration
on a disconnected socket. Their pending intents block automatic restart after
ambiguous delivery. Inspect the original request ID, server worker/catalogue state
and lease expiry before an operator clears the pending intent. A crash can also
leave the SDK's exclusive lock; inspect it before removing it. Do not delete
journals or replay MCP calls to recover either condition.

## Verification

```sh
./gradlew :plowshare-mcp-integration:check
./gradlew :plowshare-mcp-integration:mcpIntegrationConsumerCheck
./gradlew formatCheck check
```

Independent HTTP fixtures exercise both protocol eras, JSON/SSE, credential/session
isolation, nested arguments, response bounds, auth/redirect/loss/deadline failures
and unsupported continuation behavior. Mocked public SDK operations exercise
ToolProvider catalogue publication, Relay consumption/acknowledgement, durable
receipts and original-fence reconciliation. These tests use no database and do
not claim live organization-server or OAuth-provider interoperability; that is
the separate acceptance run.
