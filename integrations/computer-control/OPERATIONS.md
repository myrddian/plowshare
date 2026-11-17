# Computer provider operations

Use the same SDK provider in a TUI, GUI or foreground Node host. This runbook
covers bounded hosting and recovery, not a second agent runtime or automatic
computer takeover. The [P4 graph](P4-IMPLEMENTATION.md) records actual review and
verification. P3 positive native input and configured Plowshare/model acceptance
remain separate gates.

## Build and install offline

Build the SDKs and provider first. Node.js 22.13+ and Python 3.9+ are operator
prerequisites. Supply unused absolute output and installation directories:

```sh
./gradlew :plowshare-computer-control:computerControlDistribution \
  -PcomputerDistributionOutput=REPLACE_WITH_UNUSED_ABSOLUTE_DISTRIBUTION \
  -PcomputerDistributionNode=REPLACE_WITH_ABSOLUTE_NODE_EXECUTABLE
python3 REPLACE_WITH_DISTRIBUTION/install.py \
  --prefix REPLACE_WITH_UNUSED_ABSOLUTE_INSTALL_PREFIX \
  --node REPLACE_WITH_ABSOLUTE_NODE_EXECUTABLE
REPLACE_WITH_ABSOLUTE_NODE_EXECUTABLE REPLACE_WITH_INSTALL_PREFIX/bin/computer-control.mjs \
  REPLACE_WITH_ABSOLUTE_PRIVATE_CONFIG
```

The distribution contains the provider, both public JavaScript SDKs and the exact
installed Playwright, Playwright Core and ws production packages. Its manifest
records package versions, source revision/dirty state, member sizes and SHA-256
hashes. Both publication and installation refuse an existing destination. The
installer validates member paths, kinds, hashes and aggregate limits before
publishing the staged installation. Installation executes no package manager,
network request or package lifecycle script. The checksum manifest detects
corruption; it is not a release signature or independent source attestation.

Provider/SDK tests, test support, live fixture programs, private configuration,
journals and credentials are excluded. Existing package licences and notices
are retained. Browser binaries and the native helper are explicit prerequisites;
installation does not download them or grant OS permissions. See [native helper
packaging](NATIVE.md) for its independent signing and permission identity.

Build/install each use one 120-second default deadline, configurable with
`--timeout` up to 300 seconds, plus bounded cleanup. Limits are 10,000 regular
files, 16 MiB per member, 256 MiB total extracted bytes, 128 MiB per package
archive, 256 MiB of archives and a 4 MiB manifest. Links, special files and
traversal are refused. Discovery stops after 20,000 visited entries per package and 64 directory levels;
SDK selectors support literal parent directories and leaf globs, not recursive
wildcard traversal. This packaging path was exercised on macOS; it does not
qualify native Windows/Linux support.

If a packaging child cannot be reaped, PROCESS_CLEANUP_UNCERTAIN retains its
original `.computer-pack-`, `.computer-install-` or `computer-offline-consumer-`
staging directory. Inspect the owned process before manual recovery; elapsed
time does not authorize stale-stage deletion or a replacement publication.

The installed `node_modules/plowshare-computer-control` package exposes the
reusable provider and `plowshare-computer-control/main` foreground composition.
An embedding host continues to supply its existing authenticated SDK connection,
exact scopes and backend capabilities. Installing a package creates no grants,
project, login, browser session or native readiness grant.
Supplied backend handles remain caller-owned if admission fails or an existing
active marker refuses startup. Ownership transfers after this host acquires its
own marker; its cleanup then owns those handles through physical settlement.

## Private configuration and bounded diagnostics

Keep the configuration in an owner-only regular file, with an explicit server
origin, binding, credential source, journal and backend paths. The foreground
loader uses one no-follow/nonblocking file descriptor, verifies owner and private
permissions, caps input at 64 KiB and strictly decodes UTF-8 and JSON. A symlink
at the configured file path, special files, duplicate JSON keys and unexpected fields refuse before login or
backend startup. The initial read has its own two-second budget because the
configuration's startup budget is not available until decoding succeeds.
This loader requires POSIX owner/mode semantics; it does not assert equivalent
Windows ACL enforcement.

Embedded hosts can reuse `FileComputerConfigReader`; keep its owner alive through
`read` and `drain`. A timed-out read or failed descriptor close does not authorize
another read while the original resource remains unsettled.

`createHostDiagnostics` emits only allowlisted stage/code pairs. Its finite edge
set is deduplicated per host instance; no tool arguments, screenshots, tokens,
account/project identities, paths or raw exceptions enter these lines. A failed
sink becomes unavailable and receives no further writes. Inspect its
`unavailable` property separately. `READY` means provider catalogue/startup
completed, not that native permissions or a live model loop passed. Native input
still requires the [local readiness mechanism](READINESS.md).

## Renewal, connection loss and shutdown

`catalogue.leaseSeconds` is the operator-declared expected **server-configured**
lease. Publication does not negotiate or set that server value. Configure the
same value as the server and leave the documented renewal/pass headroom. The
host measures its conservative local cutoff from the beginning of the confirmed
publication using a monotonic clock. A pending or late renewal cannot bridge an
expired lease, revive intake or extend already admitted work. Clock-wall changes
do not renew availability. Native local grants are never automatically renewed.

The event connection is terminal after loss. The host stops intake, cancels
owned work and drains under one separate finite cleanup budget. A backend that
ignores cancellation remains physically owned; a caller timeout is not proof of
quiescence. Unknown physical cleanup retains the active marker. Its late
completion cannot silently remove the fence or restart serving.

An explicitly authorized Application-bound host may be restarted with the exact
original binding, journal and catalogue intent after physical cleanup is proven.
Startup reads retained evidence for the original catalogue/result UUIDs before
publishing a fresh catalogue. Missing, conflicting, expired or unavailable
publication evidence is not permission to resend an uncertain effect. No input,
screenshot collection, credential refresh or login is replayed to recover a
reply. A pending result publication must be resolved before serving resumes.

Interactive scopes belong to their original socket. A new login with the same
account cannot adopt them. A TUI/GUI must use the supported scope workflow on its
new session and retain the old receipts for recovery; this provider cannot repair
that authority boundary by reconnecting on its own.

Send SIGINT/SIGTERM to the foreground host, or abort the embedded host's supplied
signal. Provider stop, physical drain, backend close and catalogue withdrawal are
distinct. Proven physical cleanup can release the empty active marker even when
withdrawal metadata remains uncertain; the original metadata intent survives and
restart still refuses until it is reconciled. If marker removal succeeded but
parent-directory fsync failed, the path can already be absent despite a failed
shutdown. Inspect actual durability/receipt evidence; do not infer action success
or replay permission from marker presence alone.

## Journal capacity and recovery

`provider.journal.status()` is a synchronous, payload-free local aggregate. An
AVAILABLE result includes encoded bytes, reserved bytes, free bytes, receipt
count, phase counts and fixed limits. SAVING/SAVE_UNCERTAIN expose only limits;
there is no verified occupancy snapshot during an unsettled write. This report
is not an admission promise for a future request of unknown size.

The journal ceiling is 2,000,000 encoded bytes and 1,000 receipts. Execution
intent reserves 262,144 bytes for its future result plus a 128-byte total slot
for phase/timestamp values. That checkpoint slot remains reserved through READY,
PUBLISHING and proven NOT_SUBMITTED rollback, then retires at DONE. Timestamps
are bounded by their JSON-encoded size. Restored unfinished receipts must also
fit their reservations. A full journal refuses before acknowledging a new
request or invoking its handler; it does not discard old receipts.

When capacity is exhausted:

1. Stop intake using the owning host, preserve the original private directory
   and inspect physical cleanup. Do not remove active/backend/kernel fences
   because a PID disappeared or a file is old.
2. Inspect original receipts through the public SDK journal/provider recovery
   path. DONE confirms result publication, not successful UI state. UNKNOWN
   effects remain unknown; reconcile their real state read-only before deciding
   on any new action. Do not recapture to repair a missing result reply.
3. Preserve a private immutable backup of receipts, catalogue intent and relevant
   ownership evidence after the writer has settled. Keep original identities,
   results and image-intent counts. Do not paste payloads into logs or a PR.
4. Resume only with original evidence and proven physical quiescence. A failed
   save requires reopening the original journal, not replacing it with an empty
   one. The SDK supplies no general archival/pruning contract for a continuing
   provider identity. If it is still full, leave it stopped and retain evidence.
   Starting a new authorized deployment is a separate lifecycle decision, not a
   capacity-reset button for the same run or old pending requests.

There is no automatic pruning, stale-lock deletion or blank-journal rotation.
The actual process-crash test removes only its own empty disposable fixture lock
following a verified child reap and no external resources. That test procedure
is not a production native-lock recovery algorithm.

## Supported operational envelope

The default image-intent allowance is eight per run (at most 16 when explicitly
configured). Close/open and process reconstruction do not reset retained counts.
A bounded soak is not unlimited unattended operation: active model context can
refuse images earlier, and the provider cannot roll old frames out of it.

Default acceptance uses real public SDK polling and private file journals with
synthetic Relay/image-admission peers and a durable fake-capture counter. It
covers receipt/image ceilings, duplicate delivery, stale generations, lease
expiry, stop under load and actual Node process death/restart without replay.
It does not prove configured server retention/authorization, network reconnect,
native compositor/HID effects or real held-input cleanup. Immediate remote
cancellation, general journal archival and rolling image context remain separate
[platform work](../../docs/computer-control-design-and-plan.md#platform-gaps-and-disposition).
