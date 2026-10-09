# Provider scopes and interactive tool policy

Status: accepted following explicit authorization for a general platform extension.

## Need and alternatives

Static agent tool names cannot describe a provider catalogue discovered on connection.
An SDK adapter could export startup JSON, but it cannot supply unseen tool schemas
or implement account/project/agent authority itself. A prompt, client filter or
provider handler is not server authorization. The existing live Relay registry is
the common substrate; neither a separate adapter-owned runtime nor another durable
execution store is appropriate.

## Decision

Agent tool lists retain named built-in grants. `dynamic: true`, absent by default,
accepts external tools assigned through the owner's provider scopes without listing
each external name in `tools`. Scope permissions grant both visibility and execution;
loading validates policy, not the runtime existence or availability of tools.
Built-in and harness capabilities keep their existing named/lifecycle grants and
cannot be acquired through a provider wildcard. Narrowing
skill and orchestration tool wrappers disable implicit dynamic additions.

Application root manifests declare an execution principal, provider scopes and
explicit scope-to-agent assignments. Exact grants or a provider-only wildcard are
independent of catalogue discovery. Deployment validates local agent assignments
without requiring schemas. Current manifest authority, source activation/rollback,
project membership and agent definitions are rechecked at offering and invocation.
Network Privacy Watch assigns its six network capabilities only to the coordinator;
its collector publishes schemas rather than shipping bootstrap bindings.

Interactive owners have default authority to connect a provider scope on their
authenticated event socket and select eligible agents in their own project/session.
A requested provider wildcard covers changing discovered tools. Applications do not
inherit that default authority: their manifest must declare provider permissions and
agent assignments. Shared CLI/TUI/desktop commands and all SDK languages call the
same typed `tool.scope.connect/list/disconnect` operations.
The server derives owner and socket identity. Applications can use these operations
only within their declared execution identity and assignments. Personal scopes retain
the Personal owner fence. This is an explicit connection policy; connecting does not
implicitly select all agents or install provider-specific OAuth UI.

Runtime attachments are bounded and ephemeral. Each connection gets an isolated,
fresh Relay provider namespace; the provider publishes its catalogue and serves
calls using the returned binding. Identical requests on the same live socket return
the current attachment. A changed attachment must be disconnected first. Socket
loss/restart stops availability and requires explicit reconnect, with no mutation
replay. Relay remains the durable owner of publications and invocation receipts.

Every external call verifies current assignment, agent opt-in, owner/provider project
access, pinned schema and connection/catalogue availability. Revocation returns
`E_NO_ACCESS`, lost connection `E_NO_CONNECTION`, withdrawal/schema changes `E_NO_EXEC`.
An already admitted effect remains subject to its existing cancellation and recovery
contract. UNKNOWN outcomes require receipt reconciliation and never imply safe replay.

## Compatibility and verification

Built-in tools retain their explicit named grants. Application external tools require
dynamic opt-in and explicit manifest scopes and assignments; absent scopes grant no
external authority. Legacy global startup bindings remain supported. Application providers
can still use explicit provider/prefix/lease declarations and `bindings: []`.
Runtime scope prefixes cannot shadow built-ins or overlap static application providers.
No Flyway migration or database lifecycle is added.

Mocked tests cover unknown-name deployment, schema discovery, wildcard/account/project/
agent/session fences, source-policy changes, dynamic opt-in revocation, disconnected
sockets and namespace replacement. Shared request fixtures cover invalid bounds,
impersonation and ambiguous wildcards in all native SDKs. Repository `check` and
`sdkCheck` cover compiler, boundary and WebSocket conformance; no database tests or
live provider deployment are necessary to verify these policy changes.

Outbound MCP transport and arbitrary MCP JSON Schema remain separate work. The
existing Relay facade supports four scalar parameter types; discovery plumbing
alone must not be presented as a complete MCP client.
