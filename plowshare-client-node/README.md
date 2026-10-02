# Plowshare Node platform

Filesystem and process adapters extracted from the TUI. This package depends
only on the neutral `plowshare-client-ts` runtime and Node built-ins; it imports
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
[shared login](../docs/client-login.md) for custody and recovery.
The neutral core continues to contain no Node/filesystem imports.
