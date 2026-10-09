# Provider scopes and dynamic agent tools

Built-in tools are registered at startup. External provider tools join the registry
at runtime, using the authenticated account, project and named agent as their
permission boundary. An agent's `tools` list requests specific capabilities.
`dynamic: true` additionally accepts capabilities assigned by its owner; the flag
itself authorizes nothing. Tool schemas come from provider discovery, independently
of these grants.

An internal tool's grant is declared in the admitted agent or conductor definition.
External provider policy does not reauthorize that grant. Internal tools retain
their own authenticated resource, callee, workspace and lifecycle checks; provider
membership, assignments, schema and lease checks apply to external provider calls.

## Explicit applications

An Application's root `plowshare.json` can declare:

```json
{
  "version": 1,
  "name": "ticket-worker",
  "executionAccount": "REPLACE_WITH_SERVICE_TOKEN_PRINCIPAL",
  "toolScopes": [
    {"scope": "linear", "provider": "linear", "grants": ["*"]}
  ],
  "toolGrants": [
    {"toolScope": "linear", "agent": "ticketer"}
  ]
}
```

`access.accounts` still grants management and work membership using account handles.
The execution identity above is the credential's authenticated principal, which is
`@service/<token UUID>` for a service token. Keep the human account as a MANAGER and
use a separate service account as CONTRIBUTOR. The
[Network Privacy Watch setup guide](../integrations/network-privacy/README.md#separate-the-human-manager-from-the-service-account)
prepares the source and provisions this separation using public SDK operations.

Assigned agents must be local Application definitions with `dynamic: true`.
Deployment validates assignments without requiring a connected provider or a known
tool catalogue. `grants` contains either exact model tool names or the singleton
`["*"]`. A wildcard covers only its logical provider, this execution principal,
this project and its assigned agents. It cannot grant built-ins or other providers.
Agent-defined workspace file tools cannot modify the manifest or server authority.

Providers may retain explicit account/prefix/lease authority in `server/tools.json`
with `bindings: []`, publishing catalogue schemas later. Alternatively, an
Application provider may use the scope connection operation below with its declared
execution credential. Its requested provider, grants and assigned agents must
match an explicit manifest scope (agents may be a subset). Missing or malformed
policy fails closed. Runtime connections must not overlap a statically declared
provider prefix; use one route for a provider.

## Interactive policy

CLI, TUI and desktop share these commands:

```text
tool scope connect {"project":"coding-project","scope":"linear","provider":"linear","prefix":"linear_","grants":["*"],"agents":["ticketer"],"leaseSeconds":300}
tool scope list {"project":"coding-project"}
tool scope disconnect {"project":"coding-project","scope":"linear"}
```

A client or integration can issue `tool.scope.connect` when the user connects its
provider. The server derives the account and socket identity from authentication.
Interactive policy automatically assigns that requested scope to the selected
eligible agents in this account/project/session. The client supplies the agent
selection and cannot substitute another account. Personal projects additionally
require their owning account. Work membership and the agent's dynamic opt-in remain
mandatory. Connecting a provider alone does not select every agent.

The reply's `sourceProvider` is the logical provider; `provider` is a fresh isolated
Relay routing name. Pass the returned `project`, `provider` and `account` into the
existing SDK `ToolProvider` / `RelayTools.Provider` binding. Publish/renew its
catalogue and serve requests on the same authenticated event socket. The `prefix`
reserves model tool names and prevents built-in or same-owner provider collisions.
Other accounts in a shared project have independent connections and routing names.

Connections are bounded in-memory attachments. A one-shot CLI connect ends when
its event socket closes; use a persistent SDK connection or the TUI/desktop's
persistent command session for an active provider scope. CLI list remains useful
for inspecting connections owned by this account. Scope connection does not start
an external provider process. Socket loss or restart removes availability; reconnect explicitly and use
the new routing name. Repeating an identical connect request on the same live
socket returns that existing connection. Changing it requires disconnecting first.
Do not automatically replay a timed-out connection mutation; inspect the scope list.
Relay owns durable publications, calls, receipts and UNKNOWN-effect reconciliation.
Keep the old provider journal for reconciling uncertain receipts; a new routing
binding needs its own journal identity. Never delete uncertain receipts to make
new calls appear safe.

## SDK parity

The same three operations and typed DTOs are available in every SDK:

| SDK | Public connection operation |
| --- | --- |
| Java | `new ToolScopeClient(connection).connect(new ToolScopes.Connect(...))` |
| TypeScript / Node | `client.request('tool.scope.connect', request)` |
| Python | `client.request(ToolScopeConnectRequest(...))` |
| Go | `client.ToolScopeConnect(ctx, ToolScopeConnectRequest{...})` |
| .NET | `client.ToolScopeConnectAsync(new ToolScopeConnectRequest(...))` |

All use the same required fields: `project`, `scope`, `provider`, `prefix`,
`grants`, `agents`, `leaseSeconds`. List and disconnect use the equivalent generated
request/result types. These are authenticated WebSocket operations; they introduce
no HTTP fallback or mutation replay. Shared client command surfaces expose them;
this change does not add provider-specific OAuth screens or a dedicated connection UI.

## Calls and current limits

Offering a schema requires the current owner assignment. Each invocation rechecks
project membership, provider membership, current agent opt-in and scope policy.
A change to provider schemas cannot reinterpret an already offered call. Revocation
prevents a later admission, not an effect already admitted.

- `E_NO_ACCESS`: owner, agent or scope grant changed; try another tool.
- `E_NO_CONNECTION`: socket or catalogue lease unavailable; reconcile submitted work.
- `E_NO_EXEC`: tool withdrawn, schema changed or invocation rejected.
- `E_GENERAL_TOOL_FAILURE`: unexpected execution failure; inspect diagnostics and receipts.

This provides the permission plumbing for providers whose tool names are unknown
until connection, including an MCP adapter. It does not implement an outbound MCP
transport or arbitrary MCP JSON Schema. Existing Relay tools support the four
scalar parameter types. External adapters translate supported catalogues through
the public SDK and remain outside the server runtime.
