# Computer observation provider

This integration supplies bounded browser and macOS screenshots to a Plowshare
agent through the public SDK and Relay `content/1` path. It is a reusable library:
TUI, Desktop GUI and standalone hosts use the same provider and backends. It does
not add a client-side agent runtime or require a core/server dependency.

This P0/P1 slice exposes only `computer_targets`, `computer_open`,
`computer_observe` and `computer_close`. It does not advertise pointer, keyboard,
navigation or general computer-input tools. See the
[design](../../docs/computer-control-design-and-plan.md) and
[review graph](IMPLEMENTATION.md) for the dependencies, independent approvals and
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
work stopped. This is P1 capture exclusion; the P2/P3 input/session broker remains
separate work.

The ScreenCaptureKit helper requires macOS 14.2+, a Swift toolchain to build and
OS screen-capture permission to return pixels. It reports permission state without
requesting consent and never injects input. Accessibility permission is reported
for the future input/structured-inspection slice; capture does not imply it.

```sh
python3 integrations/computer-control/scripts/native-build.py --output /absolute/private/path/capture-helper
python3 integrations/computer-control/scripts/native-check.py
```

The explicit native check compiles and tests status/invalid-request framing only;
it does not capture the desktop. Code approval does not establish OS permission,
signed packaging or native live screenshot acceptance.

Default control and observation budgets are 2 and 10 seconds, with a separate
2-second cleanup budget. One capture is at most 1 MiB and 1600 pixels on its
longest edge, constrained further by the server's actual limits. Captured geometry
must match the server admission receipt before the frame can be used. Session and
frame references bind account/project/run/provider/generation; stale or foreign
references are refused. Capture intent consumes the provider's eight-images-per-run
allowance even after uncertainty; reopening a session does not reset it.

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

The consumer check compiles the integration against packed public SDK artifacts
in a fresh directory and imports its package self-reference through the exports
map. Its shared deadline defaults to 300 seconds and can be set with
`PLOWSHARE_COMPUTER_CONSUMER_TIMEOUT_SECONDS` (1–1800). It does not publish packages.

The default suite uses SDK transports and backend/process fakes; it does not
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
