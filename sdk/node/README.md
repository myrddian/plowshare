# Plowshare Node SDK and platform

The package root exports the standalone WebSocket SDK. It requires Node 22.12+
and sends caller-supplied bearer credentials in the upgrade header.

```ts
import { connectPlowshare, requirePayload } from 'plowshare-client-node'
const client = await connectPlowshare({ origin, token })
try {
    const reply = await client.request('project.list', {})
    const projects = requirePayload(reply)
} finally { client.close() }
```

Requests preserve the full raw envelope, correlate replies and have a 30-second
default deadline (`timeoutMs` overrides it). `ConnectionFault.delivery` distinguishes
not submitted, unknown and invalid response. Pass `onPush` for notifications.
There is no automatic reconnect, application HTTP fallback or mutation replay.
The root SDK does not persist/refresh credentials or lend a directory. Build a
local npm archive with `pnpm pack`. The published dependency metadata uses the
core package version; the local pnpm workspace override keeps repository development
linked. See [the shared SDK contract](../../docs/sdks.md).

The client exposes `publishRelay(request)`, `consumeRelay(request)` and
`acknowledgeRelay(request)` for configured topic ingress and consumer-group egress.
The package root exports `RelayPublishRequest`, `RelayConsumeRequest`,
`RelayAckRequest`, `RelayBatch`, `FilterReviewRequest` and `FilterReviewResponse`.
Consumption is a bounded long poll; handle the issued batch before explicitly
acknowledging it. See [Relay topic ports](../../docs/relay.md#sdk-topic-ports) for
delivery leases, retention gaps and recovery.


Filesystem and process adapters extracted from the TUI. This package depends
on the neutral `plowshare-client-ts` runtime, Node built-ins and the `ws` transport; it imports
no terminal, React or Electron code. The TUI re-exports these helpers, retaining
its existing tests and attended command default.

- `enforcer`: rooted file serving with traversal, hidden-path and symlink fences;
  local command policy and cancellation. `enforcing(root, false)` defaults
  commands off for headless consumers; the default attended mode remains `ask`.
- `runner`: bounded, shell-free command execution with explicit environment,
  output limits, deadlines and process-tree termination.
- `rooter`: one per-session file claim, bounded replacement/teardown, readiness
  acknowledgement and unexpected-loss callbacks. `requireReady` rejects a
  readiness timeout instead of supporting older unacknowledged servers.
- `session`: noninteractive authentication and native WS composition for MCP and CLI;
  rotating credentials, explicit absolute-directory rooting and lost-presence
  submission protection.

Build with `./gradlew :plowshare-client-node:assemble`. Regression coverage runs
through `:plowshare-tui:check`, `:plowshare-mcp:check` and `:plowshare-cli:check`. Rooted clients advertise
source-byte serving over the existing file WS. The enforcer checks the fence and
streams bounded byte ranges; the server converts PDFs, stores images and caches
text for the existing window operations. This package needs no PDF/image parser.
The `sync/*` exports share the existing Git union workflow between TUI and CLI:
shadow repository, fences, conflict reconciliation and WS readiness. Headless
strict mode reports failure; abort signals kill Git process groups and `stop()`
withdraws timers. Union controls use WS; Git objects use the server's existing
smart HTTP endpoint because it has no supported WS equivalent. CLI acceptance
covers real authenticated Git upload and WS commit acknowledgement.
The `credentials` export supplies origin-specific mode-0600 token files,
cross-process renewal locks and atomic rotation writes. `authenticateConfigured`
uses saved login when explicit environment credentials are absent. See
[shared login](../../docs/client-login.md) for custody and recovery.
The neutral core continues to contain no Node/filesystem imports.

Named external tools use the same declaration, handler, durable journal and
read-only reconciliation façade in every language. See [Relay tools](../../docs/relay-tools.md)
for configuration, grants, examples and recovery semantics.
