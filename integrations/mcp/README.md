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
separate inbound MCP server. Native clients also compose the shared `McpToolClient` directly over HTTP/STDIO.
In-process calls do not use Relay. An explicitly shared native host uses the separate
`NativeProviderBridge` for server-owned work; see [native hosting](../../docs/native-mcp-hosting.md).

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
  "requestTimeoutMs": 30000,
  "clientInfo": { "name": "plowshare-mcp-integration", "version": "0.1.0" },
  "maxClientInfoLength": 128,
  "maxWorkers": 32,
  "maxRecoveryCatalogues": 128,
  "defaults": {
    "protocol": "2026-07-28",
    "allowHttp": false,
    "allowPrivateNetwork": false,
    "timeoutMs": 10000,
    "timeoutSeconds": 30,
    "pollMs": 250,
    "renewalFraction": 0.3333333333333333,
    "limits": {
      "maxToolNameLength": 128,
      "maxDescriptionLength": 1024,
      "maxCursorLength": 1024,
      "maxOfferedNameLength": 256,
      "maxPages": 32,
      "maxWireBytes": 1048576,
      "maxCatalogueBytes": 1048576,
      "maxResultBytes": 16384,
      "maxContentBlocks": 128
    }
  },
  "workers": [
    {
      "binding": "11111111-1111-1111-1111-111111111111",
      "generation": 1,
      "timeoutMs": 20000,
      "limits": { "maxPages": 64 }
    }
  ]
}
```

`defaults` applies to every binding. Worker fields override it; `limits` merges
field by field, so overriding `maxPages` preserves the other budgets. Existing
policy and timeout fields directly on each worker remain supported. Each deployment
must supply `requestTimeoutMs` at process level, and both `timeoutMs` and
`timeoutSeconds` either in `defaults` or on every worker. There are no built-in
timeout fallback values. When migrating an earlier file, add `requestTimeoutMs`
and explicitly supply any MCP timeouts that were previously omitted. Unknown
fields, null values, invalid types and out-of-range values fail before connection.
Defaults are validated even when every worker overrides them. Resolved settings
are immutable and never shared as mutable state between credential contexts.

| Process setting | Default | Allowed value |
| --- | --- | --- |
| `requestTimeoutMs` | Required explicitly | Integer 1–2,147,483,647; public Plowshare SDK request timeout |
| `clientInfo` | Package name and version shown above | Explicit `name` and `version`, each bounded by `maxClientInfoLength` (128 by default) with no control characters |
| `maxClientInfoLength` | 128 | Positive safe integer; admission budget for client name/version |
| `maxWorkers` | 32 | Positive safe integer; maximum configured bindings |
| `maxRecoveryCatalogues` | 128 | Positive safe integer per binding; bounds retained archive reads and new catalogue admission |
| `pollMs` | 250 | Integer 1–2,147,483,647; compatibility fallback when no worker/default poll interval is supplied |

These settings belong in `defaults` or individual workers:

| Worker setting | Default | Allowed value |
| --- | --- | --- |
| `protocol` | Required explicitly | `2026-07-28` or `2025-11-25` |
| `allowHttp`, `allowPrivateNetwork` | Required explicitly | Boolean network permissions |
| `timeoutMs` | Configured shared default or required per worker | Integer 1–2,147,483,647; total discovery/refresh deadline and individual MCP HTTP request timeout |
| `timeoutSeconds` | Configured shared default or required per worker | Integer 1–300; offered invocation timeout, also bounded by the server invocation deadline |
| `pollMs` | Process value | Integer 1–2,147,483,647; idle intake interval |
| `renewalFraction` | 1/3 | Number strictly between 0 and 1; renew after this fraction of the smaller of remaining lifetime and server lease |
| `limits.maxToolNameLength` | 128 | Integer 1–4,096; upstream tool-name admission policy, not an MCP requirement |
| `limits.maxDescriptionLength` | 1,024 | Integer 1–4,096; preserved description admission policy |
| `limits.maxCursorLength` | 1,024 | Positive safe integer; pagination cursor budget, also bounded by the wire envelope |
| `limits.maxOfferedNameLength` | 256 | Integer 36–256; full Plowshare name including prefix, slug and hash; actual prefix must leave room |
| `limits.maxPages` | 32 | Positive safe integer per discovery/refresh |
| `limits.maxWireBytes` | 1,048,576 | Positive safe integer per HTTP request or response, including JSON/SSE framing |
| `limits.maxCatalogueBytes` | 1,048,576 | Integer 1–52,428,800 of UTF-8 encoded complete catalogue, including version and tools envelope |
| `limits.maxResultBytes` | 16,384 | Integer 1–16,384; encoded result must fit both character and UTF-8 byte budgets |
| `limits.maxContentBlocks` | 128 | Positive safe integer of text blocks; aggregate result budget still applies |

Polling waits are shortened for renewal and expiry. In-flight SDK operations still
have their own finite deadlines; choose timeouts and renewal cadence to fit the
server-owned lease. Very small budgets can deliberately refuse negotiation,
discovery or results. Nothing is truncated or partially published to fit a budget.

There is no adapter `maxTools` setting. The existing shared ToolProvider accepts
at most 128 declarations per catalogue, so discovery fails early when an upstream
catalogue cannot be published through that contract. MCP itself does not require
this count limit. Lifting it requires a separate shared provider-contract change.

The 128-tool and result-text ceilings come from the public provider contracts.
The catalogue budget defaults to 1 MiB and may be configured up to the Relay TEXT
portable ceiling of 50 MiB. The server's deployment Relay admission defaults to
5 MiB; publication must fit that policy and the adapter's private recovery-file
budgets too. Transport uses 64 KiB chunks, with no one-chunk catalogue ceiling.
The metadata profile bounds
upstream names/descriptions at 4,096; offered names use the 256-character platform
contract and provider prefixes use its 48-character limit. Larger-than-128 MCP
names are accepted when configured; no recommendation is converted into a protocol
requirement. Millisecond deadlines cannot exceed Node's timer overflow threshold.
Configuration admission retains a 64 KiB bootstrap file, 2,048-character server
origin/credential endpoint, 100-character token environment name and 4,096-character
absolute journal path. These are local/public-contract bounds rather than MCP name
recommendations. Schema/value limits, the 1,000-receipt SDK journal bound, 2 MiB private
file bound, name/hash format and zero effect retries remain contract invariants.
Operator settings cannot expand those contracts or enable redirects, automatic
authentication retries, input continuation or disabled TLS verification.

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
publication: by default 32 pages, 128 tools and 1 MiB per HTTP request/response,
with configurable budgets and a finite total discovery deadline. Duplicate names/cursors, unsupported schema constraints,
task-required tools, parameter header extensions or an oversized publication
refuse the catalogue. Nothing is published partially.

Nested input schemas and arguments use the same shared SDK schema and value
validators as native Plowshare tools. Constraints are preserved. Each offered name
contains a readable upstream slug and 128 bits of a SHA-256 contract hash covering
the protocol, binding generation, owner/subject, connection/revision, credential
generation, timeout, resolved discovery/result budgets and complete tool metadata,
including input/output schemas.
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
the configured recovery archive bound (128 by default) or 2 MiB per private file
are reached. A full archive refuses a new generation before saving it. Lowering
the archive bound below existing retained state blocks startup; raise the bound
or deliberately archive settled state. Configuration changes never delete old
catalogues or receipts.

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
