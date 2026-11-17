# Native session readiness

The [readiness graph](READINESS-IMPLEMENTATION.md) records implementation review
and actual native acceptance separately. Native input requires both ordinary
Plowshare authority and a short, helper-local interactive grant. Console-active
flags and a successful permission check alone do not establish readiness.

## Shared SDK host controls

`createMacOSControlBackend` returns a `NativeArmingBackend`. Supply that **same
instance** in `backends`, `nativeControls` and `nativeArmingControls` when calling
`createComputerProvider`. The returned `provider.nativeArming` exposes:

- `sessions()`: bounded immutable owner/session references and target IDs.
- `request(owner, session, { requestId, durationMs }, deadline)`: opens a local
  confirmation surface and promptly returns `PENDING`. The request ID is a
  canonical UUID; the duration is an integer from 1 through 30000 milliseconds.
- `status(owner, session, deadline)`: reads the original grant, without extending
  it or presenting another prompt.
- `revoke(owner, session, deadline)`: invalidates action references and uses a
  dedicated helper control lane, including while ordinary input is running.

These are host capabilities, never Relay tools or model arguments. A TUI, GUI or
other SDK consumer uses its existing connection/provider, resolves the actual
owner-bound session, and presents status through its own trusted host interface.
No new server operation, credential, database or client-specific input engine is
needed. Dedicated TUI/GUI settings and controls remain later client work.

Local confirmation/permission refresh shares the broker's inspector slot with
ordinary model operations. Status and revocation have read-only/control paths
that remain available during that work. Concurrent model observation/action may
receive a conservative BUSY/fence rather than wait or retry; the initial acceptance
workflow serializes setup before model control. Hosts should wait for fresh armed
proof after setup. A generic settled transport failure is never treated as proof
that a mutating action did not run.

Only a gesture in the helper-owned local surface grants input. Capture retains
its independently configured provider authority and OS permission. The human sees
the owner, selected display, allowed applications and exact bounded target
choices. The local **Check permissions** control refreshes Accessibility-backed
application/window choices; it does not display or refresh all four permission
flags. Grant verification rechecks all required permissions. Permission setup is
manual in macOS System Settings; preflight, host requests and model tools never
request OS consent automatically.
Opening a native session proves exclusion only, not permission or readiness, so
the local setup surface remains reachable before permissions have been granted.

A pending decision expires within 30 seconds. The interactive grant lasts at
most the requested duration, measured monotonically from the local gesture.
Status reads and witness refreshes never renew either lifetime. Another request
while pending or armed refuses. Revoke explicitly before requesting a new grant;
unlock, reconnect and helper restart cannot inherit or recreate one. The helper
process has its own random generation, independent of provider/session epochs.
Keep host proof details out of model content and tool journals.

The standalone foreground host supports this additional private configuration:

```json
{
  "nativeArming": { "mode": "local-confirmation", "durationMs": 30000 }
}
```

This requires an explicit `macosControl` configuration. It requests local
confirmation once after each known-open native session, under a separate bounded
host step. It never automatically confirms, refreshes a grant or retries an
uncertain request. Close/open starts another session and local decision. Embedded
hosts can omit this foreground policy and call the same SDK facade directly.

## Evidence and revocation

The readiness mechanism verifies a scoped ordinary GUI route and a fresh
composited own-window pixel witness. It does not attest every macOS lock state.
Initial verification uses a tagged harmless mouse-move received by the helper's
exact own view. Active input uses the pixel witness without moving the cursor or
interrupting a drag. At most one witness operation is physically in flight;
timeout revokes and retains ownership until it settles.

The grant binds the exact lease/helper, display, immutable policy and bounded
allowed process/launch incarnations. Observed permission, session, sleep,
display, application, secure-input, input-monitor, compositor or interference
changes revoke it. New input stops; already posted input can have uncertain
delivery. The helper checks the OS-annotated destination PID for its own tagged
events, with at most one unacknowledged event pending. OS event-tap stalls can
disable filtering; this does not promise that zero events can escape under an
arbitrary OS stall. Such loss retains UNKNOWN/no-replay and cleanup fences.

Host-facade revocation aborts model-image publication separately from the helper's input
deadline. Deferred input and uploads remain physically owned. Late pixels and
AX/frame references are discarded; established local dispatch or partial extent
can still be retained as text. A revoked status is not proof that held keys or
buttons were released. Only the existing cleanup and helper-stop proof authorizes
physical desktop reuse.

The helper's local **Stop input** control and automatic witness loss revoke input
readiness. They do not withdraw the provider's independent observation capability.
There is no unsolicited helper-to-host revocation notification in the private
protocol: a capture already transferred to Node can finish publication with its
earlier readiness snapshot after local revocation. Its old action references fail
the helper's fresh generation/state checks. The SDK host's `revoke` suppresses its
own in-flight publication; `provider.stop()` stops the whole provider. Do not label
the helper's local control as stopping all screenshots or all provider work.

Helper windows/processes are excluded from model discovery and action, even if
accidentally allowlisted. Normal captures mask exact expanded helper-owned UI
regions; private pixel nonces and event tags are never exported as structured
host/model/journal data. No universal mask guarantee is claimed during an
unobserved Mission Control/Spaces animation: compositor thumbnails may move
without changing the original window frame. Observed presentation uncertainty
revokes/refuses; unsupported operating modes must be qualified independently.

## Operating envelope and remaining qualification

Native control uses private helper protocol `computer-control-native/3`. Build
the matching Node provider and helper together. Before any acquire packet or
global desktop claim, the backend performs a strict read-only preflight exchange.
An older protocol-2 helper is refused without an acquire. Only proved helper exit
and zero acquire submission permit removal of that empty local reservation; a
failed reap retains its fence. Capture-only protocol 1 remains independent.

Native control uses macOS 15.2+ composited display capture. Capture, Accessibility,
event-posting and event-listening permissions must be checked in the actual host
launch path. macOS may attribute a helper's request to responsible host code;
bundle/signature verification alone does not identify the permission recipient.
Verify the operator-visible identity in the real OS prompt and record that launch
path. TUI, GUI and standalone hosts may need distinct grants. See Apple's
[responsible-code explanation](https://developer.apple.com/forums/thread/678819).
Build the final candidate before permission setup; changing an ad-hoc signature
can invalidate it. The public build scripts never grant TCC permissions, modify
its database, or borrow another application's identity.

Only windows wholly contained in the configured display are eligible for input.
Negative origins and scale transforms are supported by the coordinate contracts;
mixed display layouts, rotations, Spaces transitions, remote sessions, lock and
fast-user switching require actual operating-system qualification. Menus and
dialogs must resolve through exact observed accessibility/window ownership; an
unsupported hierarchy is a refusal, not a reason to broaden an application fence.
Windows and Linux native control remain separate implementation work. Headful
Playwright cannot share native control until it joins the physical broker.

## Controlled acceptance

The opt-in `native-control-sdk.live.test` launches two explicitly configured owned
AppKit fixture bundles. Independent application responders maintain actual
input/state counters; setup IPC cannot fabricate action counts. The test uses
the public SDK provider, polling and a private file journal, with an explicitly
synthetic Relay/image-admission peer. Its evidence boundary is
`LOCAL_SDK_NATIVE`, never a configured Plowshare/model vision claim.

Build separate fixture bundles with distinct explicit bundle IDs using
`scripts/native-fixture-build.py --bundle ABSOLUTE_APP_PATH --bundle-id ID
--signing-identity ID`. Supply a private JSON configuration containing exactly
`helperExecutable`, `leftFixtureExecutable`, `leftBundleId`,
`rightFixtureExecutable`, `rightBundleId`, and numeric `displayId`. Executables
are the corresponding bundle's `Contents/MacOS` entries. No credentials are needed.

```sh
PLOWSHARE_COMPUTER_NATIVE_ACCEPTANCE_CONFIG=/absolute/private/config.json \
PLOWSHARE_COMPUTER_TEST_TIMEOUT_MS=150000 \
node --test --test-timeout=150000 integrations/computer-control/build/computer/backends/native-control-sdk.live.test.js
```

The run waits for a local confirmation, performs only its planned fixture
actions, preserves private evidence/journal/fences and stops on uncertainty.
System lock, permission revocation and other disruptive qualification steps need
coordinated operator participation. Passing deterministic or no-post checks does
not close those native acceptance nodes; the review graph is authoritative about
what was actually run. Configured Plowshare/model validation remains deferred.
