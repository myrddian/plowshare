# macOS helper identity and checks

The SDK provider launches the helper executable supplied in configuration. It does
not install an app, grant consent, unlock a keychain or change the helper's signing
identity. Package the helper first, then configure its exact executable path.
The helper supports Apple Silicon and Intel macOS. Legacy capture requires macOS
14.2; protocol-3 desktop capture requires macOS 15.2. These are build/runtime
requirements, not proof that desktop control is usable.

## Build an operator-owned identity

Supply an unused absolute `.app` destination whose parent already exists, a
stable reverse-DNS bundle identifier and an explicit signing identity:

```sh
python3 integrations/computer-control/scripts/native-build.py \
  --bundle "$HELPER_BUNDLE" \
  --bundle-id "$HELPER_BUNDLE_ID" \
  --signing-identity "$HELPER_SIGNING_IDENTITY" \
  --timeout 120
```

The executable is `Contents/MacOS/plowshare-computer-helper` inside that bundle.
Keep the bundle identifier and production signing identity consistent across
rebuilds. Permission persistence is controlled by macOS; a matching identifier
alone does not guarantee that existing consent survives a rebuild or relocation.
For an explicitly development-only ad-hoc build, pass `--signing-identity -`.
Ad-hoc signatures depend on the build's code hash and provide no stable
production trust or consent identity. The script does not notarize or distribute
the bundle.

A real signing identity must already be available and authorized in the operator's
keychain. Signing can still involve an OS-mediated keychain dialog; this script
cannot guarantee silent real-identity signing. It never imports credentials,
unlocks keychains or changes key ACLs. Use an appropriately preauthorized signing
session. Verification and the status checks below never request permission.

The compile, signing, signature verification and pre-publication cache removal
share one monotonic budget (`--timeout`, default 60 seconds, maximum 300). Child
process groups are owned through physical reap, with a separate finite
`--cleanup-timeout` (default 2 seconds, maximum 10). Expiry kills the owned group;
a timeout alone never establishes cleanup. Tool output is bounded and withheld,
and only fixed error codes reach the terminal. The child environment contains
only tool paths, a private temporary directory and the C locale, rather than
inheriting account credentials.

The script stages the six explicit Swift source files, builds and verifies the
artifact, removes the toolchain cache, then exclusively renames the artifact into
an unused destination. It never replaces an existing destination, even if another
process creates one during the build. On uncertain process cleanup the private
`.plowshare-native-*` staging directory is retained. Filesystem operations cannot
be forcibly cancelled: if publication or final cleanup fails or finishes after
the deadline, the command fails and the destination may already exist. Inspect
it before a new build; do not treat a failed command as successful publication.

The existing standalone, unsigned P1 build interface remains available:

```sh
python3 integrations/computer-control/scripts/native-build.py \
  --output "$HELPER_EXECUTABLE" --timeout 120
```

It also requires an unused absolute destination and an existing parent. Temporary
or unsigned helpers do not establish the deployed helper's TCC identity.

## Inspect the configured bundle without taking control

Use the actual configured executable and expected bundle identifier. Supply
canonical absolute paths, without symlink components:

```sh
python3 integrations/computer-control/scripts/native-check.py \
  --executable "$HELPER_BUNDLE/Contents/MacOS/plowshare-computer-helper" \
  --bundle-id "$HELPER_BUNDLE_ID" --timeout 120
```

This verifies the bundle signature, identifier and executable metadata. It then
sends only a correlated protocol-3 `preflight`, followed by a sequential `stop`,
and requires a clean helper exit. It does not compile a replacement, run input
fixtures, capture pixels, create an event tap, post input, request consent or
acquire the production ownership semaphore. The kernel-fence value comes from a
read-only presence check; a clear fence is not a claim of ownership or proof of a
usable unlocked desktop.

The output reports four independent permission flags: screen capture,
accessibility, event posting and event listening. None implies another. It also
reports readiness, secure-input state and kernel-fence presence. A fresh helper
without a locally confirmed grant reports `unknown`, even if permissions are
granted. The [shared SDK readiness controls](READINESS.md) request local
confirmation and verify a scoped live witness. This read-only diagnostic does
not request that grant or prove capture, input, held-input cleanup or model vision.

## Test source contracts separately

With no `--executable`, the checker compiles a temporary helper and exercises
strict protocol-1 status/invalid requests, protocol-3 preflight/stop and malformed
preflight requests, plus the helper's `--check-input-memory` entry point:

```sh
python3 integrations/computer-control/scripts/native-check.py --timeout 120
python3 integrations/computer-control/scripts/native-scripts-test.py
```

The first command is an explicit macOS check. Its shared budget defaults to 120
seconds and is capped at 300; each protocol exchange inherits a 2-second ceiling.
The memory fixture constructs and inspects CGEvent objects without posting them.
It checks scalar-safe Unicode payloads, negative desktop coordinates, pixel-wheel
fields, key encoding, cancelled budgets, strict nested protocol decoding and pure
acknowledgement accounting. Six injected executor fault cases also exercise
cancellation, foreign held input, interference, dropped acknowledgements, partial
Unicode dispatch and partial drag dispatch, using in-memory ports only. It never
starts a broker reader, installs a tap, takes a capture or acquires the production
fence. CoreGraphics may emit bounded framework
diagnostics in a sandbox; their contents are suppressed and their presence is
reported separately. Protocol/status checks require empty diagnostic output.

Readiness fixtures separately exercise local gesture/nonce handling, auxiliary
session-field validation, grant expiry, event routing, hung witness ownership,
Inspector serialization and unresolved-probe shutdown fencing. They use injected
ports and fixed event data; they do not establish actual GUI/compositor evidence.
The strict checker requires the exact current fixture result schema and counts.

The helper owns its main run loop until its single asynchronous mode worker
actually finishes. A stopped run-loop turn is neither successful completion nor
physical settlement. Two no-GUI regressions stop that loop and require the
original worker's later continuations and final result. A real own-UI comparison
motivated this correction; the related [Swift runtime report](https://github.com/swiftlang/swift/issues/91716)
does not establish a particular compiler-version regression or the exact caller
that stopped the earlier UI probe's loop.

The second command tests the Python lifecycle and validation logic with harmless
child processes and mocked signing operations. Its exclusive-rename case is
Darwin-only. Neither command proves real OS input delivery, an unlocked GUI
session, configured TCC permissions, crash recovery or end-to-end Relay/model
acceptance. Those remain distinct reviewed acceptance gates.
