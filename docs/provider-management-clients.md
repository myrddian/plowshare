# Managing tool connections from clients

CLI, TUI, desktop and console manage server-held provider credentials and durable
tool assignments through the existing typed WebSocket operations. These controls
do not install an MCP adapter, connect to an upstream endpoint, start an executor,
or launch a native HTTP/STDIO host. The [provider mechanism](tool-scopes.md),
[credential lifecycle](provider-credentials.md) and [hosted bindings](hosted-tool-scopes.md)
remain authoritative.

## Location, authority and evidence

Choose an **execution project**. Choose an ordinary **credential project** separately
when using Personal work. The shared endpoint definition contains no credential.
An empty subject means your authenticated identity; the SDK rejects other human
subjects. Administrators may select an exact permitted `@service/<token UUID>`
subject. This does not change the assignment owner or the caller's accounting pool.
The executor is an exact operator-approved principal, supplied by the operator;
there is no public executor roster to populate a dropdown.

Server administration plus credential-project Manager access is required for
endpoint changes. Contributors manage their eligible authorization and own
assignments. The server checks current membership, token ceilings, ownership,
dynamic agent assignments, grants and expected revisions/generations on every
operation. UI controls are guidance, never authorization.

Saved assignments and available routes are separate snapshots. The controls join
a saved binding to its exact returned route in `tool.scope.list`. An enabled
binding alone does not establish availability. Worker health, MCP compatibility
and upstream failures are **Not reported** by the owner API. No management screen
calls worker credential release or worker status to manufacture this evidence.

`READY` is displayed with expiry independently. For CLIENT_CREDENTIALS with no
expiry it can mean **Machine secret stored; obtain access token**. Obtaining or
refreshing an access token is explicit. An UNCERTAIN exchange requires deliberate
reauthorization; it is never automatically refreshed or replayed.

## CLI and TUI

The CLI retains the full typed command names:

```text
provider connection list|status|save|change|receipt [JSON]
provider credential status|put|authorize|refresh|revoke|rekey [JSON]
tool scope host list|status|save|change|receipt [JSON]
tool scope list [JSON]
```

Use `--payload -` to feed credential.put JSON from a trusted stdin producer. Online
credential.put refuses an argv body. Do not type material into shell history or
an echoed terminal input. Its JSON has `project`, `connection`, explicit `subject`
(null for self), `requestId`, explicit `expectedGeneration` and `secret`.
`--validate` is offline and redacts the secret rather than printing it.
The invocation closes its socket after its result; server assignments persist.

The TUI exposes the same owner families as slash commands on its current socket.
`/tools` reads saved bindings, active scopes and pending request metadata for the
current execution project. For `/provider credential put`, supply only the five
coordinate fields above, omitting `secret`. The TUI then requests masked material;
a surface unable to mask input refuses entry. An inline secret is rejected.
Masked input is never added to the transcript or sent to an agent. Worker operations
are excluded from this management command surface.

CLI/TUI `provider credential authorize` is an explicit request to open the returned
URL once in the system browser. The opener has a finite timeout, invokes no shell
and prints neither the URL nor its parameters. CLI reports a confirmed authorization
receipt separately if browser opening fails; inspect current status before a new
authorization. The original receipt cannot recover the once-only URL.

## Desktop and console

In desktop, select an execution project and choose **Tool connections** beside the
existing project controls. The **This client** tab manages private HTTP/STDIO profiles
and process-owned hosts; the **Server** tab manages shared HTTP endpoints, credentials
and assignments. See [native controls](native-mcp-hosting.md#client-controls). Desktop main owns the socket and private bookmarks;
renderer IPC exposes only owner management requests, metadata and an ephemeral
authorization URL. Credentials and URLs never enter DesktopState or state events.
Closing the dialog clears material and restores focus; identity/disconnection
changes discard drafts and snapshots. Secondary windows cannot dispatch management.
Revoking authorization, disabling or deleting an assignment, and disabling or
deleting a shared endpoint require a review dialog naming the affected identity
and resource. Cancel or Escape submits nothing.

In console, expand **Server connections** in the existing Projects card. Personal
cards use an ordinary credential project entered separately. The panel borrows
the tab socket and does not reorder navigation or create a separate runtime/socket.
Browser authorization presents an explicit once-only link after the reply, avoiding
a background popup. Closing/changing selection clears the link and password field.
The browser cannot start or adopt another native session's host.

Refresh shared endpoints, select one, and inspect authorization before supplying
material. Assignment fields name scope, provider, prefix, dynamic agents, exact
grants (or deliberate `*`), lease and operator executor. Select an existing assignment
to edit it; a blank selection creates a new UUID. Endpoint definitions are edited
through HTTP endpoint and OAuth forms, including the complete profile described
in the credential manual. Input is decoded by the shared SDK before submission. Endpoint writes are disabled for non-administrators;
the server additionally requires project Manager authority.

Refresh after opening/reconnecting or changing selection, after an upstream OAuth
callback and whenever a newer snapshot is needed. Mutation receipts trigger fresh
metadata reads; a failed follow-up read does not erase a confirmed receipt. Reads
are bounded to the selected projects/subject, no polling timer is created, and
late replies from closed/changed views are discarded. No provider management event
subscription exists; ordinary events do not prove provider readiness.

The native TUI `/mcp` menu offers the same location choice and guided forms. Native
profile drafts reference selected environment variables; credentials for server
connections are entered masked or authorized through the upstream browser. Local
launch and server sharing each require deliberate consent. The CLI retains its
existing typed management commands and foreground native host commands.

## Lost replies and compatibility

The shared `ProviderManagementClient` validates input and persists a redacted
bookmark **before** submitting a mutation once. Bookmarks contain only operation,
project, resource/request UUID, subject and expected revision/generation. No request
body, definition, secret/hash, token or OAuth URL is stored. Native clients use
private atomic files under the configured local root, partitioned by canonical
origin/authenticated actor. Cross-process locks fence concurrent native actions.
Console uses origin/actor-scoped session storage, retained across a tab reload.
These are recovery bookmarks, not another execution ledger.

Unknown transport failures, unreadable successes and INTERNAL_ERROR retain the original
bookmark. A second unresolved action on that resource is refused before submission.
An explicit NOT_SUBMITTED connection fault clears the bookmark without retrying.
Inspect **Unconfirmed management requests** and choose **Read original receipt**.
CLI/TUI use the original `project`/`requestId` with `provider connection receipt`
or `tool scope host receipt`. A matching receipt clears that bookmark. A missing,
foreign or unreadable receipt leaves it fenced. A receipt is immutable evidence
of the original action, while status is present state; never substitute one for
the other. No mutation is resumed or resent during reconciliation.

The actual older-server `NOT_FOUND` / no-handler response is reported explicitly
as unsupported. No HTTP fallback occurs. Java, Python, Go and .NET preserve the
same refusal through their typed facades; TypeScript/Node management adds the
same message for interactive controls. Unrecognized envelopes remain transport
uncertainty, not success. Additional output fields are projected away, including
secret material attached to ordinary metadata or refusal replies.

Supported management operations use WebSockets. Plowshare login and the upstream
OAuth redirect/landing page/authenticated completion POST are the documented
[HTTP exceptions](provider-credentials.md). Clients do not create callback servers
or automatically exchange a user's Plowshare token for an upstream credential.

## Verification

Shared lifecycle tests cover pre-send persistence, one-send fencing, malformed or
foreign replies, refusal/redaction, original-receipt recovery and older servers.
Native store tests cover concurrent clients, private modes, service principals and
origin/actor separation. SDK conformance exercises unsupported owner operations
across published native clients. Repository `check`, `sdkCheck` and `sdkPackageCheck`
include the owning generated contracts and installed-consumer boundaries.

Explicit UI fixture acceptance:

```sh
cd plowshare-desktop
node scripts/build.mjs
node --experimental-strip-types scripts/tool-connections-smoke.mjs
cd ..
node --experimental-strip-types test-support/provider-management/browser-probe.mjs /absolute/path/to/chromium
```

The browser probe runs the actual Projects screen over one segmented socket;
the native probe runs isolated Electron/main/preload/renderer with a regular owner.
They verify masked input, redacted state/bookmarks, no replay/fallback/worker
authority and lifecycle/focus. They are controlled fixtures, not deployed MCP,
upstream OAuth or native-host distribution acceptance.
