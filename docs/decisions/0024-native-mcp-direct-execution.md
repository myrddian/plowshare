# Native MCP execution uses direct calls inside a client process

Status: accepted; the user authorized native hosting and explicitly required that
clients do not use Relay for in-process calls.

## Decision and boundaries

The shared `McpToolClient` owns bounded discovery, immutable snapshots, shared
schema validation and MCP invocation. Both the existing server-provider adapter
and native client hosts consume it. A direct native invocation needs no Plowshare
socket, scope, catalogue publication or Relay operation. Server-only catalogue-count
constraints are applied by the provider bridge, not the direct client; native
catalogues remain bounded by configured wire/discovery budgets. HTTP and STDIO refer to
the external MCP transport, independently of Plowshare transport.

Native CLI, TUI and desktop main processes compose `NativeHostCommands` and an
instance-owned host registry. The browser renderer receives typed native IPC
status/results; it cannot spawn a process itself. Desktop native actions are
limited to its main workspace window. Connection loss, account replacement and
shutdown dispose local hosts; there is no automatic start, host adoption or device
fallback. Explicit CLI foreground hosting ends at EOF, signal or host failure.

Server-owned jobs can use an explicitly shared native host through the separate
`NativeProviderBridge`. This is an actual server-to-client invocation boundary:
existing authenticated `tool.scope.connect`, exact agent/tool grants, public SDK
catalogues, leases and `ToolProvider` receipts remain authoritative. The handler
then calls the native MCP client directly in process. Discovery and local consent
create no server grant. Native calls create no second durable agent runtime,
server accounting pool or MCP-specific execution ledger.

No core API, persistence, shared protocol or SDK operation is added. The integration
remains an external SDK consumer. Native client package composition includes the
integration library, independently of server/protocol production classpaths.

## Configuration and lifecycle

Private profiles require canonical owner origin/account, explicit instance and
profile identity, project, prefix, journal directory and configured deadlines and
shutdown grace. HTTP destinations and private-network policy are explicit. Local
HTTP credentials and STDIO environment values come only from selected environment
references; the native host never releases server-held credentials. Client info
and existing admission/result budgets can be configured. Secrets and sessions are
never written into profile summaries, offered names, catalogue identities or logs.

STDIO requires an absolute executable, argv vector, absolute working directory
and explicit environment selection, with no shell or inherited secret environment.
The pinned SDK's STDIO transport merges an inherited environment and fixes its kill
timers, so a narrow owned transport supplies those policies while retaining the
SDK's protocol client, pinned versions and cancellation semantics. Protocol stdout
is strictly bounded newline-delimited UTF-8 JSON-RPC; stderr is drained privately.
Malformed messages stop the transport. No negotiation/profile fallback, effect
retry, automatic input fulfillment or hidden credential refresh is introduced.

POSIX hosts terminate their owned process group, including remaining descendants
when the parent exits first. Descendants that deliberately escape that group are
outside this primitive's guarantee, matching the existing SDK runner boundary.
Windows requires an explicitly configured `taskkill.exe` path before launching
STDIO. Desktop distributions currently target macOS Apple Silicon; Windows native
process-tree behavior requires its own platform acceptance.

## Intent and recovery

The existing private SDK receipt format and file journal record direct native
execution intent before calling MCP. An instance/profile/owner partition and
exclusive local lock prevent concurrent host adoption. Identical request UUIDs
return retained results; changed request text or reuse across a different catalogue refuses. Old SDK journals
are scanned under the same exclusive partition lock before launch, with an operator
archive budget and no new ledger. An interrupted executing
receipt becomes UNKNOWN without calling the tool again. No uncertain receipt is
deleted or normalized. Local and server-shared calls have distinct owning receipts:
the optional bridge's ToolProvider owns server-call intent before entering its
native handler, while direct calls use their native SDK journal.

Credential/environment rotation or catalogue changes stop further effects and
require an explicit stop/review/start. Direct host close stops intake and cancels
waiting, then retains the outcome before releasing its lock. Cancellation and
process exit do not prove upstream rollback or cancel a server job.

Scope/catalogue metadata writes have no owning receipt read. On ambiguous sharing
setup or withdrawal, close the original owning socket to remove its ephemeral
server authority; never reconnect or replay the write here. Catalogue submission
intent and original invocation journals remain private and available for inspection.

## Verification and remaining UX

Independent HTTP and STDIO fixtures exercise both pinned protocol profiles, nested
values, direct execution with a Plowshare transport that refuses every operation,
credential isolation/rotation, invalid arguments, duplicate IDs, restart and
interrupted receipts, malformed frames, cancellation and process-tree shutdown.
Foreground CLI and desktop lifecycle tests verify direct calls and owner disposal.
SDK-only sharing tests verify scope/grant publication and uncertain metadata fencing.
Run repository checks, packed-public-SDK consumer checks and native client packaging
verification. No SQL or persistence schema changes require PostgreSQL.

Friendly connection-management screens are a separate increment; cross-client
browser, live endpoint and additional platform acceptance remain separate. The runtime,
CLI verbs, TUI native command boundary and desktop main IPC are delivered here.
