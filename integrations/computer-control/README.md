# Computer control provider

This integration supplies bounded browser and macOS screenshots to a Plowshare
agent through the public SDK and Relay `content/1` path. It is a reusable library:
TUI, Desktop GUI and standalone hosts use the same provider and backends. It does
not add a client-side agent runtime or require a core/server dependency.

The default observation mode exposes `computer_targets`, `computer_open`,
`computer_observe` and `computer_close`. P2 adds explicitly enabled browser
control using the same SDK provider. See the
[design](../../docs/computer-control-design-and-plan.md) and
[P0/P1](IMPLEMENTATION.md), [P2](P2-IMPLEMENTATION.md), [P3](P3-IMPLEMENTATION.md)
and [P4](P4-IMPLEMENTATION.md) review graphs for dependencies, independent approvals and
the distinction between code approval and live acceptance.
Current live bootstrap/permission findings are in [platform gaps](PLATFORM-GAPS.md).

## Reuse an existing SDK connection

`createComputerProvider` accepts an authenticated public SDK request facade,
explicit binding, backend instances, private journal, generation, server origin
and `currentBearer` capability. It does not log in or open another event socket.
`computerDeclarations` supplies the exact tool declarations used to derive the
SDK journal identity before opening `FileToolJournal`.

For clients that already own a Node `Session`, use the exported
`computerProviderConnection(checkedTransport(session))` adapter. `checkedTransport`
is the public TypeScript SDK adapter. Supply `currentBearer: () => session.bearer()`
so image admission uses the host's credential lifecycle. The provider never owns
or persists that token. Native helpers receive neither credentials nor the host
environment.

Hold the public Node SDK's `localLock` around the private journal and provider's
entire lifetime. Derive its identity with
`nodeToolHost.sha256(toolDeploymentConfig(binding, declarations))`; reopening with
a changed binding/declaration set is refused. The integration's capacity wrapper
reserves future result space before execution intent, enforces immutable receipt
transitions and preserves the SDK's UNKNOWN/no-replay lifecycle. It does not add
automatic receipt pruning. Stop and retain recovery state when capacity is full.

The host owns catalogue publication/renewal, bounded polling, stop/drain and
backend cleanup. Reconcile an uncertain publication using the original journal;
do not recapture or republish it under a new identity. Calling registration
handlers directly bypasses that lifecycle and is not the embedding API. The
[provider tests](src/provider.test.ts) exercise the real SDK provider boundary,
including an existing socket owner, rather than another local execution route.

Interactive scopes belong to their original event socket/session. A supervised
provider with a different login/socket cannot adopt that scope merely because
the account matches. The initial TUI/GUI composition embeds the provider and
supervises only native helpers; separately connected hosts require supported
explicit Application bindings. No TUI/GUI controls are added in this slice.

## Foreground host

The [operations runbook](OPERATIONS.md) covers offline installation, private
configuration, bounded diagnostics, lease renewal, shutdown and original-receipt
recovery. Those controls remain reusable by an embedding SDK host.

Build the module, then supply an explicit private configuration file:

```sh
./gradlew :plowshare-computer-control:computerControlBuild
node integrations/computer-control/build/computer/main.js /absolute/private/computer-config.json
node integrations/computer-control/build/computer/main.js /absolute/private/computer-config.json --once
```

`--once` performs one bounded serving pass and cleanup; it does not establish a
live model/screenshot round trip. Before starting a separately connected host,
configure an authorized non-hosted Application provider binding and matching
agent scope/grants through the supported owning workflow. Publishing a catalogue
does not create that authority. For an interactive TUI/GUI scope use the original
socket embedding API above instead of opening this executable's new connection.

### Explicit browser control

Use `createBrowserControlBackend` for control. The original `createBrowserBackend`
remains observation-only. An embedded host supplies the controlled backend in
both `backends` and `browserControls`, and its exact configured tool list in
`browserTools`. Derive the journal identity from
`computerDeclarations(prefix, budgets, browserTools)` before opening the journal;
the provider rejects mismatched declarations or backend instances. TUI, GUI and
standalone SDK hosts use this same composition and original authenticated socket.

For the foreground host, replace the `browser` section in the configuration
template below with an explicit `browserControl` section:

```json
{
  "browserControl": {
    "executablePath": "REPLACE_WITH_ABSOLUTE_BROWSER_EXECUTABLE",
    "headless": true,
    "ownershipDirectory": "REPLACE_WITH_PRIVATE_ABSOLUTE_OWNERSHIP_DIRECTORY",
    "control": {
      "enabled": true,
      "tools": ["windows", "focus", "navigate", "pointer", "type", "key", "scroll"],
      "allowedOrigins": ["REPLACE_WITH_ALLOWED_HTTP_OR_HTTPS_ORIGIN"]
    },
    "targets": [{
      "targetId": "controlled_browser",
      "label": "Controlled browser",
      "url": "REPLACE_WITH_HTTP_OR_HTTPS_URL_IN_ALLOWED_ORIGIN",
      "viewport": { "width": 1280, "height": 720 }
    }]
  },
  "sessions": { "frameFreshMs": 5000, "idleMs": 60000 },
  "budgets": { "browserObservationReserveMs": 3000 }
}
```

This fragment is replace-before-use configuration. Retain the template's server,
binding, credential, journal and catalogue fields. The ownership directory needs
an existing real parent. Its target markers are independent of provider routing
names and survive a crash; all cooperating hosts for the same configured browser
domain must use the same directory. A fresh lease creates fresh isolated browser
storage. A controlled backend refuses bare observation-only capture, preventing
another injected provider from bypassing its lease.

Give eligible agents exact grants for the selected full tool names, such as
`computer_pointer`. Observation grants alone do not grant control. The local
policy narrows server authority; it does not create a server approval workflow.
`computer_targets` reports each controlled target's exact `browserTools` as well
as its coarse capabilities. A wildcard includes future tools and is not read-only.

| Tool | Reference and behavior |
| --- | --- |
| `computer_observe` | Session/generation/surface, optional `structured` (default true for controlled browsers) and an exact element `root` for a bounded subtree. Capture-only targets accept omitted structure or `structured:false`. |
| `computer_windows` | Session/generation; bounded tabs with a fresh `windowsId`, readiness and quarantine state. |
| `computer_focus` | Fresh `windowsId` plus returned `surfaceId`. A quarantined tab can be focused without claiming pixels. |
| `computer_navigate` | URL plus `observation:{kind:"frame",frameId}` or `{kind:"window",windowsId,surfaceId}`. The window lane permits a new explicit navigation of a quarantined tab. |
| `computer_pointer` | `action` is move/click/double_click with a point/element `target`, or drag with a `frameId` and timed points. Buttons default to left; modifiers default to none. |
| `computer_type` | Exact element target, explicit insert/replace mode and bounded text. Both modes require that exact editable element to hold focus. |
| `computer_key` | Fresh `frameId`, explicit `expectedFocus` element or observed body/null, one allowlisted key and modifiers. No exposed held keys or clipboard/file/browser-chrome shortcuts. |
| `computer_scroll` | Fresh point/element target, nonzero deltas and explicit pixels/lines. Pixel deltas are bounded to 32768; line deltas to 128; actual CSS-pixel conversion is reported. |

Every tool also requires `sessionId` and `generation`. A point target is
`{kind:"point",frameId,x,y}` in integer image pixels; an element target is
`{kind:"element",snapshotId,elementId}`. Copy returned references; owner identity,
leases, state guards, transforms and deadlines are never model arguments. DOM
replacement, document/focus/layout changes and stale snapshots cause refusal.
There are no arbitrary selectors or JavaScript execution tools.

Structured output is explicitly an approximate semantic DOM snapshot, bounded to
200 nodes and the encoded metadata limit. It omits sensitive values and marks
truncation; a subtree request uses a previously observed exact element. It is not
a complete accessibility tree. Missing or omitted elements cannot be targeted.

Pointer/type/key/scroll/navigation may carry a one-use `dialogDecision` with
expected type, accept/dismiss decision and optional exact message. Prompt text is
allowed only for an explicitly accepted prompt with the local type capability.
This dialog action belongs to the invoking tool's authority; it does not invoke
a separate server `computer_type` grant or approval. There is no permanent
accept-all policy. Unexpected, mismatched or second dialogs fail-stop the owned
context. Arbitrary interactive modal recovery is outside this initial envelope.

Document navigation is checked before dispatch, including redirect destinations.
An unambiguous initial popup request is aborted and the owned tab quarantined;
a new explicit navigation is required after its guard is installed. Ambiguous
multiple unbound popup requests fail-stop the context instead of guessing a tab.
Child-frame navigation and
targeting, file selection/downloads, clipboard, personal profiles and native or
browser-chrome input are unsupported. Subresource networking is not a sandbox.

Actions reserve image allowance, journal/result space and post-observation time
before input. `DISPATCHED` means established input delivery; it does not prove a
form was saved. `PARTIAL` reports a known delivered extent. Failed capture/upload
after known input is a `COMPLETED` text-only result with a failed observation and
retained dialog evidence. Unresolved delivery is `UNKNOWN`, text-only and fenced.
Inspect original receipts and use a separate authorized observation when safe;
do not repeat the input to recover a reply.

Default action time is 10 seconds, including a 3-second reserved observation
allowance and binary admission. Session controls default to 2 seconds. Configure
larger finite budgets explicitly when justified; freshness is still checked
against live document/layout/focus state. Idle expiry blocks effects exactly at
the configured boundary; local cleanup maintenance is scheduled within one
second plus event-loop delay and retains ownership until physical settlement.
Local stop cancels intake and requests cleanup. Uncertain physical cleanup or a
crash retains the durable target marker; never clear it based on age, a reconnect
or a PID guess. After proven quiescence, marker removal can succeed before its
parent-directory fsync fails. That instance stays fenced even if the path is
already absent; another process may then safely acquire the quiet resource.
This does not resolve the original action's outcome or authorize replay.

This is a replace-before-use template, not runtime defaults. Every `REPLACE_`
value must be replaced; the template intentionally fails validation as written.
The service credential's account is its exact `@service/<token UUID>` principal,
not the service's display handle:

```json
{
  "version": 1,
  "origin": "REPLACE_WITH_HTTP_OR_HTTPS_ORIGIN",
  "binding": {
    "project": "REPLACE_WITH_AUTHORIZED_PROJECT",
    "provider": "REPLACE_WITH_PROVIDER_ROUTE",
    "account": "@service/REPLACE_WITH_TOKEN_UUID"
  },
  "credential": { "kind": "service-token", "environment": "PLOWSHARE_COMPUTER_TOKEN" },
  "journalDirectory": "REPLACE_WITH_PRIVATE_ABSOLUTE_DIRECTORY",
  "catalogue": { "leaseSeconds": 300, "renewalMs": 30000 },
  "budgets": {
    "startupMs": 30000,
    "controlMs": 2000,
    "observeMs": 10000,
    "passMs": 30000,
    "cleanupMs": 2000
  },
  "browser": {
    "executablePath": "REPLACE_WITH_ABSOLUTE_BROWSER_EXECUTABLE",
    "headless": true,
    "targets": [{
      "targetId": "browser",
      "label": "Configured browser",
      "url": "REPLACE_WITH_HTTP_OR_HTTPS_TARGET_URL",
      "viewport": { "width": 1280, "height": 720 }
    }]
  }
}
```

For a saved human login, use a human account in the binding and replace
`credential` with `{"kind":"saved","directory":"REPLACE_WITH_ABSOLUTE_CREDENTIAL_DIRECTORY"}`.
Use the public Node SDK credential-store directory; do not copy access/refresh
tokens into configuration. Serial renewal must leave room for the bounded poll
and renewal passes within the actual configured server lease. The loader refuses
insufficient headroom before authentication or launch. Native targets additionally
require the helper and shared ownership directory described below.

## Backends and limits

### Native macOS control

The [native packaging and diagnostics guide](NATIVE.md) describes stable helper
identity, explicit signing, configured no-input checks and their limits.

`createMacOSControlBackend` supplies the native control port to the same
`createComputerProvider` used by TUI, GUI and standalone SDK hosts. Supply its
exact backend instance in both `backends` and `nativeControls`, and its configured
tools in `nativeTools`. Pass those tools as the fourth argument to
`computerDeclarations` when deriving the private SDK journal identity. Browser
tools remain the third argument; a shared tool name appears once in the catalogue.
The foreground host accepts the equivalent explicit `macosControl` section:

```json
{
  "macosControl": {
    "helperExecutable": "REPLACE_WITH_PRIVATE_ABSOLUTE_NATIVE_HELPER",
    "ownershipDirectory": "REPLACE_WITH_PRIVATE_ABSOLUTE_OWNERSHIP_DIRECTORY",
    "targets": [{
      "targetId": "desktop",
      "label": "Configured desktop display",
      "surfaceId": "display:REPLACE_WITH_EXPLICIT_DISPLAY_ID"
    }],
    "control": {
      "enabled": true,
      "tools": ["windows", "focus", "pointer", "type", "key", "scroll"],
      "allowedBundleIds": ["REPLACE_WITH_EXACT_ALLOWED_APPLICATION_BUNDLE_ID"]
    }
  },
  "budgets": { "nativeObservationReserveMs": 3000 }
}
```

Retain the earlier template's server, binding, credential, catalogue and journal
fields. Every placeholder requires replacement. The target names an explicit
display; AX window references returned by `computer_windows` are separate focus
references, not guessed capture-window IDs. Native dialogs are observed UI and
require explicit subsequent actions. Native control has no `navigate` or automatic
browser `dialogDecision` policy. Text insertion needs the exact observed focused
nonsecure editable AX element; it never uses the clipboard. Native scroll deltas
are integer pixels or lines. Keyboard chords are plain/Shift navigation and
macOS Meta+A, Meta+Z and Meta+Shift+Z; browser-only chord variants are refused.

**Native readiness requires a short local interactive grant.** Hosts explicitly
request local confirmation through the shared SDK facade, or enable the
foreground host's `nativeArming` configuration. Prompts are not enabled by default;
configuration or a model cannot assert readiness. The helper checks live own-GUI
routing/compositor evidence and revokes on observed loss. Public console flags
alone remain insufficient. See
[native readiness](READINESS.md) for host composition, permissions, evidence,
revocation and supported-envelope limits. The
[readiness graph](READINESS-IMPLEMENTATION.md) records source review and actual
native acceptance separately. Positive native input acceptance remains OPEN.
Configured Plowshare/model validation remains deferred.

Native control requires macOS 15.2+ for composited display capture. The earlier
capture-only helper retains its separate macOS 14.2 floor. Helpers receive private
typed stdio requests and remaining budgets, with no Plowshare credentials or
inherited environment. Local foreground stop requests bounded cleanup. A killed
helper does not prove that posted keys or buttons were released. Missing
input-release proof retains the global fence and never retries input. After
proven quiescence, semaphore unlink or marker removal can succeed before a
descriptor close or directory fsync fails. That instance remains fenced even if
the resource name is already absent; another process may safely acquire the
quiet resource. This never resolves or authorizes replay of the original action.

One kernel exclusion domain covers the physical desktop across all configured
displays, windows, provider bindings and ownership directories. Native control
holds it for the lease. Cooperating capture-only helpers join the same domain
for captures. The synchronous `createMacOSBackend` remains unverified for mixed
composition; `createCoordinatedMacOSBackend` performs an explicit bounded helper
handshake before claiming broker cooperation. Required coordination fields make
old helpers reject before capture, including after an executable swap.

An embedding host must supply truthful `ComputerBackend.desktopIsolation`:
`isolated` means no physical desktop access; `physical` means uncoordinated
access; `native-broker` means every physical capture/input joins the shared
kernel domain. Missing metadata means unknown. With native controls, composition
refuses unknown/physical companions and requires native ports themselves to
declare `native-broker`, before SDK or filesystem effects. Shipped headless
browser factories declare isolated; headful browsers cannot currently share the
native controller. Third-party backends must implement this contract; the flag
does not grant server authority or make an old helper cooperative.

### Observation backends

`createBrowserBackend` takes an explicit browser executable, headless selection,
configured HTTP(S) target URLs and viewport dimensions. It creates isolated
provider-owned contexts; it never adopts a user's browser profile. Playwright is
pinned to 1.63.0. Ordinary checks do not download a browser. Startup, screenshot
and cleanup have finite budgets; failed cleanup remains an explicit failure.

`createMacOSBackend` takes an absolute helper executable, explicit display/window
IDs and an absolute shared `ownershipDirectory`. All cooperating providers for
the same OS session must use that directory. Its durable capture lock is acquired
before spawning a helper and released only after physical completion and cleanup;
failed drain or a crash leaves a marker requiring explicit operator recovery.
Never remove it merely because a timeout, PID check or reconnect suggests the
work stopped. This durable capture marker remains part of compatibility with
earlier capture hosts; coordinated native composition additionally requires the
shared kernel domain described above.

The capture-only ScreenCaptureKit protocol requires macOS 14.2+, a Swift toolchain
to build and OS screen-capture permission to return pixels. This mode reports
permission state without requesting consent and never injects input. Native
control uses the separate opt-in protocol and newer OS floor above; capture
permission does not imply Accessibility or input permission.

```sh
python3 integrations/computer-control/scripts/native-build.py --output /absolute/private/path/capture-helper
python3 integrations/computer-control/scripts/native-check.py
```

The explicit native check compiles and tests status/invalid-request framing;
it also checks the separate protocol-3 preflight and no-post memory fixtures.
It does not capture the desktop. Code approval does not establish OS permission
or native live screenshot/input acceptance. Use the configured-bundle checks in
the [native guide](NATIVE.md) to inspect the helper actually selected by a host.

Default control and observation budgets are 2 and 10 seconds, with a separate
2-second cleanup budget. One capture is at most 1 MiB and 1600 pixels on its
longest edge, constrained further by the server's actual limits. Captured geometry
must match the server admission receipt before the frame can be used. Session and
frame references bind account/project/run/provider/generation; stale or foreign
references are refused. Image-bearing observation/action intent consumes the
provider's eight-images-per-run allowance even after uncertainty; reopening a
session does not reset it. Recovery conservatively counts receipted attempts.

## Plowshare/model prerequisites

Use an explicit ordinary project and current owner/provider work membership.
Personal and Global invocation-image admission are not currently supported.
Configure non-hosted provider declarations/bindings with `resultProfile: content/1`
and the current v3 catalogue. Hosted workers and other producer SDKs do not yet
have equivalent typed-image production. See [Relay tools](../../docs/relay-tools.md),
[provider scopes](../../docs/tool-scopes.md) and
[image admission](../../docs/invocation-image-admission.md).

Enable `plowshare.images.invocation-admission-enabled` in operator configuration.
The actual serving pool must declare the exact wire model under `vision` and
provide a known context length. A matching version-2 sampling profile needs the
verified input MIME, text output, an image-context policy and an explicit supported
output-token reserve. Preserve existing sampling modes/reasoning settings when
adding media support; version 2 requires a `default` mode. Test an image against
the actual endpoint before declaring a format. Do not change Mac/other pool
routing merely to enable a separate model's vision.

The retained observation path supports PNG, JPEG and WebP, but each model/backend
may support a narrower set. A socket-prefill LM Studio route remains text-only.
Downscaling lowers bytes, not image occurrences. Providers cannot request a
rolling latest-frame context window; full-prompt admission may refuse before the
local image allowance is reached. Hooks that rewrite/withhold a result exclude
its pixels. Invocation/result reads return descriptors, not redeemed model pixels.

## Verification

```sh
./gradlew :plowshare-computer-control:computerControlTest integrationBoundaryCheck
./gradlew :plowshare-computer-control:computerControlConsumerCheck
./gradlew formatCheck
./gradlew check
```

The consumer check builds and installs the exact offline provider/public SDK
artifacts in a fresh directory, verifies runtime exports and the foreground CLI,
and compiles a separate typed consumer. It executes no npm install, network,
authentication, browser or helper startup. Its shared deadline defaults to 120
seconds; the owning script accepts `--timeout` up to 300 seconds. It does not
rerun the entire integration suite in that consumer or publish packages.

The default suite uses SDK transports, backend fakes and controlled child
processes, including a real Node crash/restart with a synthetic effect. It does not
require PostgreSQL, Docker, native permission or a running browser. Explicit real
browser evidence uses an isolated changing canvas fixture:

```sh
PLOWSHARE_COMPUTER_BROWSER_EXECUTABLE=/absolute/path/to/browser ./gradlew :plowshare-computer-control:computerControlTest --rerun-tasks
```

The test runner has a shared 60-second default budget, configurable through
`PLOWSHARE_COMPUTER_TEST_TIMEOUT_MS` (1–300000), with a separate bounded drain.

Record real-model and cross-host evidence separately from these tests. A direct
vLLM image test establishes endpoint input only; P1 live acceptance additionally
requires a real provider capture, invocation admission, retained observation and
the next model request through a configured Plowshare server. No test should
bypass managed Application source ownership or current scope/membership checks
to obtain that result.

### Configured SDK acceptance

The explicit acceptance executable uses a saved human connection and its original
SDK session for workspace rooting, agent definition, scope connection and provider
serving. It checks the authenticated server identity before those mutations. A
connection-registry entry is insufficient: sign in privately through the owning
client first. The executable never selects another account or extracts tokens.

Supply a private JSON configuration with these fields, replacing every value:
`workDirectory` must be an unused path, and `evidenceFile` must not already exist.
`budgetMs` defaults to 180000 when omitted and accepts 1–300000 milliseconds.

```json
{
  "connectionName": "REPLACE_WITH_SAVED_HUMAN_CONNECTION_NAME",
  "project": "REPLACE_WITH_FRESH_ORDINARY_PROJECT_NAME",
  "model": "REPLACE_WITH_EXACT_VISION_MODEL_SELECTION",
  "browserExecutable": "REPLACE_WITH_ABSOLUTE_BROWSER_EXECUTABLE",
  "workDirectory": "REPLACE_WITH_PRIVATE_ABSOLUTE_FRESH_WORKSPACE",
  "journalDirectory": "REPLACE_WITH_PRIVATE_ABSOLUTE_JOURNAL_DIRECTORY",
  "evidenceFile": "REPLACE_WITH_PRIVATE_ABSOLUTE_EVIDENCE_FILE",
  "budgetMs": 180000
}
```

```sh
./gradlew :plowshare-computer-control:computerControlBuild
node integrations/computer-control/build/computer/acceptance.js /absolute/private/acceptance-config.json
```

This is an explicit live operation: it creates its own ordinary project/agent and
short-lived provider scope, starts an isolated browser fixture, and runs two
planned visual trials. Each trial displays a new random value only in canvas
pixels. The harness requires a correct visual answer, exact admitted-image
receipt linkage, retained observation and selected model-route evidence. A
completed job alone is insufficient. Failure or uncertain delivery stops the
run; the second planned trial is not an automatic retry of the first.

The overall deadline includes setup, captures, model work and evidence reads;
cleanup has a separate finite budget. Private evidence and journal records are
retained for inspection. The evidence-file state `CHECKS_PASSED` is provisional:
live acceptance requires the complete linked evidence and a successful
`runAcceptance` return or zero CLI exit. Filesystem writes cannot be instantly
cancelled; a snapshot that finishes after a timeout does not close live acceptance.
This browser acceptance does not establish native OS permission, desktop capture,
input capability or a production deployment.
