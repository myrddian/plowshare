# Plowshare MCP over WebSocket

The TypeScript stdio adapter is the supported MCP server. It preserves the reviewed
35-tool compatibility menu and schemas; the superseded Java MCP server is removed.
All 34 server-facing tools use typed requests through `plowshare-client-ts` and
`/v1/events`. `client_root_project_here` explicitly opens `/v1/files` through
`plowshare-client-node`. HTTP is used only for login, refresh and socket tickets;
there is no operation fallback or automatic mutation replay.

Requires Node 22.12+ and pnpm. Build from the repository root:

```sh
./gradlew :plowshare-mcp:assemble
./gradlew :plowshare-mcp:check
```

Configure a harness's stdio command as the absolute path to `bin/plowshare-mcp`
(or `node /absolute/path/plowshare-mcp/build/main.js`). Run
`bin/plowshare-cli --url ORIGIN login` once as the local user that launches MCP.
The adapter reads the shared saved session without prompting. Configure only the
server origin; passwords need not be included in the harness configuration:

```json
{
  "mcpServers": {
    "plowshare": {
      "command": "/absolute/path/plowshare/bin/plowshare-mcp",
      "env": {
        "PLOWSHARE_URL": "http://127.0.0.1:8091"
      }
    }
  }
}
```

See [shared login](../docs/client-login.md) for token storage and logout.
Explicit handle/password environment variables remain ephemeral automation overrides.
Authentication happens before MCP starts. Missing credentials, sign-in refusal
or a required password change exits promptly without terminal prompts. Stdout
contains only newline-delimited JSON-RPC; diagnostics go to stderr. The adapter
supports the legacy MCP protocol version `2024-11-05`. Initialize, ping, the
ordered tool menu, unknown methods/tools and notification silence are checked
against Java and an independent MCP SDK client.

`--url ORIGIN` overrides the server origin. `--timeout-ms N` or
`PLOWSHARE_TIMEOUT_MS` sets the bootstrap/per-message deadline (default 120000).
A deadline or signal closes the channels and exits 5; it does not cancel a
server job. Startup errors exit 2 and normal EOF exits 0. Tool errors use MCP
content with `isError=true`, leaving the daemon available for subsequent calls
unless interrupted. JSON-RPC protocol errors use error envelopes.

## Scope, jobs and local presence

Omitted project selects global home where supported. `PLOWSHARE_PROJECT` is
ignored, and a blank project does not become global. `agent_run` submits a
standalone job with `conversation=null`; it carries a session only while this
process holds an acknowledged file claim. Accepted maintenance/document jobs
return their handle immediately. Poll/result/cancel remain separate tools, and
a cancellation acknowledgement is not a finished result.

Rooting requires an explicit absolute directory. The directory is canonicalized,
and the server must acknowledge the claim before success is reported. Each
process holds at most one claim; a move closes the previous channel first.
Unexpected presence loss blocks new `agent_run` submissions until explicit
rooting succeeds again. Failed replacement leaves the process serving no files.
Invalid local paths are rejected before changing an existing claim. Local file
requests retain the TUI's traversal, hidden-path and symlink fences.

Headless local commands default **off**, including the generated environment
definition. An explicit local `.plowshare/environment.yml` may configure the
command policy; the Node runner enforces that policy and kills running commands
when presence closes. The TUI retains its existing attended default of `ask`.
File bytes stream over the existing file WS in bounded source ranges. The server
extracts PDF text, stores images through its existing image service and caches
converted content for read/stat/grep windows. Each access rechecks fenced source
metadata and its content hash; a cache hit avoids retransmission and conversion.
Image reads retain the existing one-line image reference for vision tools.
Production distribution, OS keychain integration, live deployment/model probes and Java-client
retirement remain separate work.

## Compatibility evidence

The [retained compatibility fixtures](../test-support/contracts/mcp-compatibility.json)
pin output, defaults, errors and effective Java backend arguments. Tests translate
those arguments independently to WS payloads rather than treating Java DTOs as
wire requests. All normal fixture renderings match, with documented transport
changes: WS refusal code names replace HTTP status numbers, and connection
failures report redacted uncertainty without replay or cancellation. A null WS
conversation allowance renders `unlimited`; fetched text quotes all Unicode line
separators. Local presence has stricter acknowledgement and headless defaults.

The SDK socket tests exercise every server tool with minimal and explicit
options, inspect operation frames and allow only auth/bootstrap HTTP traffic.
Additional tests cover real fenced file serving, token rotation, claim refusal,
missing acknowledgement, loss/re-rooting, uncertain completion, deadlines, EOF,
idle signals, password-change refusal and nonempty evidence/retention rendering.
`:plowshare-mcp:check` also runs the Node enforcer against the real Java file WS
handler, checking PDF windows, edits, image storage and path refusals.
These are local protocol fixtures, not live Plowshare/model validation.

Shared `operations/retrieval` validates memory, proposal, document, conversation
search and web replies before formatting. A malformed finding or mutation
acknowledgement is a model-visible error with completion unresolved; no request
is replayed. Stale citation null ids are preserved. Unknown citation status does
not become a stale finding or quoted evidence. The SDK tests also exercise all
16 MCP retrieval tools with nonempty source-backed synthetic replies.
