# Native MCP hosting

CLI, TUI and desktop main processes can own MCP HTTP or STDIO sessions. In-process
calls use the shared MCP client directly, without a Plowshare connection or Relay
round trip. Explicit **share** exposes a host to server-owned jobs through the
existing authenticated provider/scopes/Relay boundary; its native handler still
calls MCP directly. Server-hosted MCP remains HTTP-only.

These are explicit process lifetimes, not background services or another durable
agent runtime. Configure and review a private profile before starting. The native
client owns its connections, selected secrets, subprocesses and private SDK receipt
journals. Closing the client/socket, switching login or stopping the host ends local
availability. Starting after exit/reconnect requires another explicit action.

## Private profile

Use an absolute profile filename outside synced projects/source. Files/directories
use the SDK's private-file policy. This STDIO example contains placeholders to
replace with your selected values; it is not a production default:

```json
{
  "owner": {"origin": "https://plowshare.example.org", "account": "your-account"},
  "instance": "your-cli-instance",
  "profile": "your-mcp-profile",
  "project": "your-project",
  "prefix": "local_",
  "protocol": "2026-07-28",
  "journalDirectory": "/operator-selected/private/mcp-journals",
  "timeoutMs": 15000,
  "timeoutSeconds": 30,
  "shutdownGraceMs": 3000,
  "transport": {
    "kind": "stdio",
    "executable": "/operator-selected/bin/mcp-server",
    "args": ["--operator-selected-argument"],
    "directory": "/operator-selected/mcp-working-directory",
    "environment": {"UPSTREAM_TOKEN": "YOUR_MCP_TOKEN_ENVIRONMENT"}
  }
}
```

The environment map is child-variable name → parent-variable reference. Only these
selected values are passed; there is no inherited environment or shell invocation.
Executables and working directories must exist before launch. STDIO stdout carries
only bounded newline-delimited UTF-8 JSON-RPC. Stderr is drained privately and is
not forwarded into client diagnostics. A working directory is not an OS sandbox;
configure a trusted executable or your own isolation wrapper in argv.

For HTTP, replace `transport` with:

```json
{
  "kind": "http",
  "endpoint": "https://mcp.example.org/mcp",
  "allowHttp": false,
  "allowPrivateNetwork": false,
  "authorization": {"header": "Authorization", "environment": "YOUR_MCP_AUTH_HEADER"}
}
```

The selected authorization value supplies the complete header value (for example,
a bearer scheme plus token). Authorization is optional for an explicitly anonymous
endpoint. Credentials in URLs, redirects and silent endpoint fallback are refused.
Private addresses and HTTP require explicit policy. No server-held credential is
exported into this native profile. Local OAuth/keychain UI is a separate management
increment; current profiles use selected environment references.

Both `2026-07-28` and `2025-11-25` are explicitly supported profiles. No protocol
fallback or second process launch is used after failed negotiation. `clientInfo`
can override the integration name/version. `limits` uses the same configurable
admission, pagination, wire, catalogue and result budgets as the
[outbound adapter](../integrations/mcp/README.md). Timeouts and shutdown grace are
required configuration. Existing shared schema/value/result bounds still apply. Direct native discovery
has no server catalogue-count restriction; server sharing retains its 128-tool
public provider contract.
`maxRecoveryCatalogues` defaults to 128 and is configurable; a full archive refuses
a new catalogue without deleting old receipts.

POSIX shutdown closes stdin, then terminates the owned process group using the
configured grace period. This also stops descendants holding pipes after their
parent exits. Processes that deliberately escape that group require OS isolation.
Windows STDIO requires an explicit absolute `treeTerminationExecutable` pointing
to `taskkill.exe`, validated before launch. Windows native process-tree acceptance
remains separate from the currently supported macOS Apple Silicon distribution.

## CLI foreground execution

```sh
plowshare-cli mcp local inspect /absolute/private/profile.json
plowshare-cli mcp local start /absolute/private/profile.json
plowshare-cli mcp local call /absolute/private/profile.json --payload -
```

`inspect` reads a redacted launch summary without launch: executable basename,
argument count, selected environment names or credential-free HTTP endpoint,
without argument/secret values. `start` is explicit local
consent: it prints a serving snapshot and accepts NDJSON invocations on stdin until
EOF, signal or host failure. Use the exact offered tool name from that snapshot.
`call` reads one invocation JSON object from stdin, opens its own consented host,
returns its result and exits; it does not advertise continuing availability.
Each invocation contains:

```json
{
  "invocationId": "11111111-1111-1111-1111-111111111111",
  "tool": "exact_offered_name_from_this_snapshot",
  "arguments": {"your_input": "value"},
  "deadline": "2099-01-01T00:00:00Z"
}
```

Choose a fresh caller-owned UUID for new work and an appropriate deadline. Retain
that exact JSON for recovery: identical requests return their retained result;
changed request text or reuse under a different catalogue refuses. Requests follow
the SDK's portable envelope; invalid arguments fail before execution intent or an
MCP tool effect. A received MCP tool error remains a received result with `isError`;
an unverifiable dispatched call becomes UNKNOWN and is never retried.

Explicit startup records interrupted SDK receipts as UNKNOWN under the exclusive
host lock before opening a new MCP session. Existing requests and results remain
retained. See [cancellation and recovery](mcp-recovery.md) for abort/close behavior,
exact STDIO cancellation and the distinction between stopping a wait and undoing
an upstream effect.

### Explicit server sharing

Server sharing preserves the owning account, exact route, tool name and schema
fingerprint at the native handler boundary. It does not turn launch/share consent
into a per-call human approval. See [grants, hooks and invocation
evidence](mcp-tool-authority.md) for the existing authorization and approval limits.

`mcp local share /absolute/private/profile.json --payload - --timeout-ms DURATION`
reads a complete sharing command from stdin:

```json
{
  "action": "share",
  "file": "/absolute/private/profile.json",
  "consent": true,
  "scope": {
    "project": "your-project",
    "scope": "your-local-scope",
    "provider": "your-provider",
    "prefix": "local_",
    "agents": ["your-agent"],
    "grants": ["*"],
    "leaseSeconds": 60
  },
  "pollMs": 250,
  "renewalFraction": 0.5
}
```

`['*']` deliberately grants all tools from this provider, including future additions;
use reviewed exact names when appropriate. Project/prefix must match the profile.
Sharing authenticates to its configured Plowshare origin and verifies the actual
account before connecting its scope. Server membership, dynamic-agent opt-in and
exact grants remain required. Local launch consent does not grant an agent access.
Signal/deadline/socket loss stops intake, withdraws the owning scope and closes the
native session. Ambiguous scope/catalogue writes close the original owning socket;
no automatic metadata retry or host restart occurs. Original private intent and
SDK receipts are preserved. This does not cancel a server job or prove rollback.

## Client controls

In Desktop, open **Settings** at the bottom of the sidebar. **Connection** manages
saved Plowshare server logins. **Client MCP** configures local HTTP/STDIO connections
and their explicit host lifetimes. Server-hosted MCP is a separate administration
section, described below.

Choose **User settings** or **Project settings**. Both Desktop and TUI use the same
files:

- User: `mcp.json` in the shared `userConfigDirectory()` root, selected by
  `PLOWSHARE_CONFIG_DIR` (the existing legacy `PLOWSHARE_DESKTOP_CONFIG` override is
  also recognized), otherwise `~/.plowshare/mcp.json`.
- Project: `.plowshare/mcp.json` inside the attached local project folder. A project
  known only to the server has no local project file until you attach its folder.

The selected location is explicit; neither file silently overrides the other.
**Saved connection → Load profile** reads an entry for the current server/account.
**New profile** starts an empty editor. **Save profile** writes the selected file
without connecting or launching. Existing arbitrary private profile files remain
available through **Private profile file**; no existing file is migrated or rewritten
just by opening Settings.

Shared files have the versioned shape `{"version":1,"profiles":[...]}`. Each entry is
a complete native profile with the fields shown above, including `owner` and a
`profile` connection name. Names are unique per server/account within each file.
Reads filter the chooser to that login; edits preserve other entries and reject
stale revisions. Entries retain explicit execution projects, process identity,
private receipt locations, network policies and environment references. These
are Plowshare files; other harness formats are not imported or rewritten.

Project files should contain only non-secret configuration and environment-variable
references. Do not put secret values in process arguments. Receipts and process
locks belong in the configured private journal directory, outside source control.
Files are written atomically with mode 0600; existing project-directory permissions
are preserved. Shared authoring does not share process ownership: stop a running
host before editing it or opening the same receipt journal from another client.

**Review launch** shows the configured endpoint or executable basename, argument
count and selected environment names. Review the full profile and check launch
consent before **Start local host**. Discovered tools and their nested schemas are
read-only inventory; inspecting them does not invoke a tool. **Refresh local hosts**
reads only this process registry. Closing the dialog retains a running host; disconnect,
account switch and app exit stop it. A changed file cannot be launched under an older
loaded revision. Receipt directories and profile files remain private native state.

**Share this profile with server-owned work** is a separate, explicit action with
scope, provider, agents, grants, lease, polling interval and renewal fraction. Stop
a local-only host before starting it with sharing. The host inventory distinguishes
local-only availability from server sharing or unconfirmed sharing. Stop withdraws
sharing and closes the upstream connection/process. Sharing does not bypass server
grants, relabel accounting or prove readiness of a future invocation.

Administrators open **Server administration → Server MCP**, then choose an execution
project. Non-administrators use **Server tools** in the project Info → Files panel, or
**Find → My server tools**, for their own authorizations and assignments. Both use the existing server owner capabilities for HTTP endpoint
forms, OAuth settings, masked credentials, authorized service subjects and assignments.
These server credentials follow the authenticated account across clients. The web
console's Projects **Server connections** panel exposes only those server operations.
It cannot launch, edit or adopt a native host. Owner management replies do not include
a remote tool catalogue or worker health; the UI reports these as unavailable rather
than treating a saved assignment as proof of availability.

In TUI, `/mcp` opens guided **Client MCP settings (HTTP/STDIO)** and **Server (HTTP)** menus.
Choose **Shared user and project MCP settings** in the client menu to add, edit,
review/start or explicitly share named connections from the same files as Desktop.
Project settings use the TUI’s current rooted local project. The native menu also
supports standalone private profile create/edit, review/start, host/tool snapshots,
stop and explicit sharing. The server menu supports endpoint forms, credential
status/update/browser authorization, assignments and original unconfirmed receipt
inspection. `/cancel` returns without another submission. Configurable native
budgets use the same form defaults and validation as desktop.

## TUI and desktop process ownership

TUI accepts `/mcp local COMMAND_JSON`. Supported commands are `inspect` with `file`,
`start` with `file`/`consent`, `list`, `stop` with `instance`/`profile`, `call` with
`instance`/`profile`/`invocation`, and `share` using the JSON above. Native command
inputs are redacted in terminal history. Direct commands do not call Plowshare;
`profile-read` and `profile-save` provide owned, revision-checked authoring
without launch. Supply `entry` with the connection name when `file` is a shared
`mcp.json`; omit it for a standalone native profile. `configuration-files` lists
the user location and, with an explicit canonical local `root`, its project location.
It never creates files or starts hosts. GUI/menu launch carries that reviewed revision. Share borrows the original authenticated socket and cannot migrate to a reconnect.

Desktop main accepts the generated IPC action `{action:'native-mcp', command:JSON}`
from the main workspace window. It uses the same commands and direct host registry.
The renderer cannot launch STDIO; auxiliary project/report windows are refused.
Account replacement, socket loss and app shutdown dispose the original registry.
The main window composes the controls above through this boundary; renderer views
never own processes or server credentials. Live organization, additional platform
and cross-client acceptance remain separate.

## Receipts and SDK composition

Native journals partition canonical origin/account, instance, project and profile.
An exclusive lock prevents concurrent host adoption. Old SDK receipts are inspected
before launch; a UUID retained by a different catalogue cannot become a fresh effect.
An interrupted executing receipt becomes UNKNOWN on explicit recovery without
running the handler again. Changed secrets/environment or catalogue metadata stops
new effects and requires Stop/Review/Start. Neither reconnect nor credential refresh
replays an uncertain call. Keep original receipts; do not delete a busy lock or an
uncertain journal to make a launch succeed.

SDK consumers can compose `McpToolClient`, `NativeMcpHost`, `NativeHostCommands` and
`NativeMcpSessions` without a Plowshare transport. `NativeProviderBridge` is the
optional cross-process adapter. See the [decision](decisions/0024-native-mcp-direct-execution.md)
and the official [STDIO transport specification](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/stdio).

Verification entry points: `./gradlew check manualCheck
:plowshare-mcp-integration:mcpIntegrationConsumerCheck` and the opt-in
`./gradlew nativeMcpDistributionCheck` for actual standalone HTTP/STDIO execution
with no installed runtime on PATH. These checks require no database.
