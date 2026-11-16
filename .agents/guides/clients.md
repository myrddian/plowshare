# User-facing clients

Read this for CLI, TUI, browser console, Desktop or the MCP client surface.
Use [transport and SDKs](transport-sdks.md) only when changing shared contracts;
use [AUTH](auth.md) when changing access decisions or protected disclosure.
For request queueing, fail-fast behavior or capacity retries, read
[backpressure](backpressure.md).

## Mental model

Clients present server-owned work and maintain local presentation/connection
state. They do not create a second durable agent runtime. Shared operations and
validated SDK projections should supply common behavior across surfaces.

## Start in code

- [CLI](../../plowshare-cli/src) owns command dispatch, JSON results and watched work;
  [TUI](../../plowshare-tui/src) and [console](../../plowshare-console/src) own their surfaces.
- Desktop [client](../../plowshare-desktop/src/client.ts) consumes server events;
  [shared types](../../plowshare-desktop/src/shared.ts) cross the IPC boundary;
  [renderer](../../plowshare-desktop/src/renderer/app.ts) renders state.
- [MCP](../../plowshare-mcp/src) exposes supported server capabilities to MCP callers.
  Keep its invocation lifecycle distinct from external MCP provider hosting.
- Module READMEs and [client manual](../../docs/manual/02-clients-and-work.md)
  describe public behavior; [distributions](../../docs/distributions.md) owns packaging checks.

## Recorded and prospective views

When changing chat, Trajectory or Info → Context, read
[Log, Trajectory and model projection](runtime-and-inference.md#log-trajectory-and-model-projection)
and the [conversation manual](../../docs/manual/03-architecture.md#log-trajectory-and-model-projection).
Trajectory shows recorded work; Info → Context previews the selected agent's next
ordinary projection, excluding the draft. Preserve capture/stale labels and do not
present that snapshot or a context-eligible entry as an actual provider-request receipt.

## Rules

- Server authorization remains authoritative. Hiding a control or filtering a
  local list cannot replace a permission check. Keep credentials out of renderers,
  browser logs, screenshots and public diagnostics.
- Reuse already-loaded state and shared typed operations. Avoid unconditional
  refresh loops, duplicate reads and a slow secondary request blocking the main view.
- Fence asynchronous results by connection, selection and work identity. A late
  reply must not overwrite a newer conversation, account or resource selection.
- Subscribe before launching work where early deltas are possible. Verify both
  event acceptance and the render invalidation path; state updates alone do not
  prove text repaints during repeated same-phase chunks.
- Bound transient preview storage and escape it for its actual output sink.
  Preserve the reader's collapse/scroll choices. Durable completion replaces the
  preview; late chunks cannot resurrect completed state.
- Published reasoning is an authorized model preview, not a promise that every
  call streams. Protected-output policies can withhold it. A provider reasoning
  token count is not evidence that text reached a subscriber.
- Preserve unknown/zero token and price distinctions. Reuse shared usage views;
  partial cache measurements cannot support a whole-population cache-hit rate.
- Preserve drafts and mutation identities through uncertain delivery. Reconnect,
  timeout or a UI reload never authorizes automatic replay.
- Preserve installed profile, saved connections and native application identity
  during packaging updates. A development build is not an updated installed app.

## Personal approval controls

Desktop's Approvals editor and TUI's `/approval-mode` / `/approval policy` commands
use the same owner-scoped server policy. Save uses the loaded revision. Preserve
an uncertain draft and require explicit reconciliation; never replay a mode/rule
change automatically. Bot selection is in the owner's Personal context.

File questions are typed once/deny operations, not empty commands. Render their
kind, provider, paths, digest, available input preview and objection before an
answer, and echo the exact `fileDigest` on approval. Command/budget questions keep
their existing wire semantics. A client that cannot render a file question must
not approve it. Preserve unchanged approval buttons across state refreshes so a
pointer-down/up remains one click in Chat and Trajectory. See [personal approval
policies](../../docs/approval-policies.md) for current coverage and limitations.

## Bounded work

Apply the [bounded-work rule](../../docs/coding-standards.md#bound-active-work-in-every-subsystem):
short interactive reads/controls have a configurable two-second default ceiling;
uploads and watched jobs use explicitly appropriate finite request/transfer
budgets. Keep UI work responsive while a durable job runs. User-selected waiting
and connection lifetime are distinct from individual request deadlines. Abort
obsolete reads where supported and always fence late replies by current identity.
Timeout does not cancel server work or prove a write was unsubmitted; preserve
draft/intent, inspect status/receipts and never auto-resend the effect.

Current client and SDK request/watch timers differ and do not establish universal
two-second server abort. Trace the changed surface through the
[SDK connection](../../sdk/typescript/src/binding/connection.ts) and server owner.
Test stalled reads, selection changes, late events and pending mutation recovery
with fixture clocks/transports; release capacity on physical completion.

## Legacy traps

Demo data proves presentation only. An accepted job proves admission only. A
passing client-state test does not prove renderer updates, packaged IPC or live
provider streaming. Inspect each boundary relevant to the actual change.

## Checks

Use the changed module's package scripts for focused tests, type checks and lint.
For Desktop streaming, `pnpm --dir plowshare-desktop test:reasoning` exercises the
native fixture; use the relevant existing smoke script for other UI flows.
Fixtures are preferable to live model calls for rendering behavior.

Test early, repeated, malformed, wrong-job and late events plus final clearing.
For packaging changes use `desktopPackagingCheck` / `desktopDistributionCheck`
as documented, and distinguish fixture checks from an installed-app live check.
