# HA-02 independent review

Node: `HA-02`. Reviewer: `/root/ha_reads_reviewer`.
Implementer: `/root/ha_reads_implementer`.
Candidate: `ha-ha-02-v1`.
Manifest: `docs/reviews/home-assistant-ha-02-v1-candidate.json`.
Manifest SHA-256: `6a1e6a67f6c3a03952216df960d24365c4827fa064c3750a0cc89e6ffa3dff45`.
Verdict: **CHANGES_REQUIRED**.

## Scope and evidence

Verified the manifest, all ten bound file/snapshot hashes and the tracked diff
hash. Read the accepted discovery contracts/policy, source/catalogue, native
adapter/codec, operator contract, implementation report and both new test classes.
The implementation uses the configured native connection, keeps vendor JSON at
the transport/codec boundary, filters before paging, batches states, distinguishes
registry capability failures and separates private invalidation from projected
public metadata revisions. Explicit resource-policy configuration and the legacy
manual timeout are documented without claiming tool grants or live deployment.

Independently inspected the saved JUnit evidence: 10 contract, eight catalogue,
nine native and ten legacy adapter cases passed with zero failures/errors/skips.
The focused log records successful formatting checks. Root also reported the
full non-database `./gradlew check` passing on this frozen source (141 tasks,
3m31s; `/private/tmp/ha-delivery-repository-check.log`). Those checks do not cover
the two reproduced races below.

I compiled and ran two bounded, in-process Java probes against the actual built
candidate classes using the existing pinned dependencies. They use fixture
identities and no network, Gradle, database, model, live HA or device operation.
Temporary probe sources are outside the repository under
`/private/tmp/ha-review-probes/`. No implementation source or ledger was changed.

## Required corrections

### HA02-R1 — P2: Timeout cleanup blocks on the observer before cancellation

Location: `HomeAssistantAdapter.java:483–510`, `:309–334`.

The new bounded `await` timeout/interruption path calls `abortConnection`, which
calls synchronized `lost` before cancelling the captured socket. `lost` invokes
the externally supplied Gap observer while holding the adapter monitor, before
completing pending futures. A slow observer therefore holds the expired caller,
physical cancellation and other pending commands hostage. The actual integration
runtime observer synchronously persists the observation in its journal, so the
callback is not guaranteed to be a nonblocking in-memory operation.

The `AbortProbe.java` reproduction blocks only that observer and produces:

```text
CALLER_FINISHED_WHILE_CALLBACK_BLOCKED=false
SOCKET_CANCELLED_BEFORE_CALLBACK_RELEASE=0
SOCKET_CANCELLED_AFTER_CALLBACK_RELEASE=1
```

Separate connection fencing/pending completion and captured-socket cancellation
from observer execution. Preserve required Gap delivery through a bounded owning
lifecycle rather than dropping it, and do not run external observer work under
the connection monitor. Add a deterministic slow/blocked-observer regression
proving an expired read returns within its cleanup budget, cancellation is not
delayed, pending commands remain unconfirmed and no effect is replayed. A mere
reordering of `cancel` before synchronous `lost` does not bound the caller return.

Disposition: **open**.

### HA02-R2 — P2: An old state callback can mutate the replacement generation

Location: `HomeAssistantAdapter.java:148–177`, `:235–248`.

`onMessage` checks the captured epoch before parsing, but calls `event(data)`
without carrying that epoch. `event` acquires the adapter monitor later and never
rechecks the generation. A callback that passed the initial check can wait while
a reconnect replaces the epoch/snapshot, then apply old data and increment the
replacement source's state version. The new metadata callback correctly checks
its captured epoch under the monitor; the state path needs the same protection.

`GenerationProbe.java` injects a synchronous in-process WebSocket factory, lets an
old state callback pass the initial check and block at `event`'s monitor, installs
the next connection under that monitor, then releases the callback. It produces:

```text
REPLACEMENT_STATE=2
STATE_AFTER_OLD_CALLBACK=STALE_OLD_GENERATION
NEW_SOURCE_STATE_VERSION=1
```

Carry the captured epoch/socket identity through callback processing and check it
atomically with every mutation of connection-owned state. Include authentication
state mutation in this audit; its `connected = true` currently also follows only
the earlier unlocked check. Add a deterministic callback/reconnect regression
asserting stale callbacks cannot alter the replacement snapshot, state revision
or authentication state. Do not rely on timestamp ordering to substitute for a
generation fence.

Disposition: **open**.

No other blocking finding was established. Independent approval requires both
corrections, relevant current checks and a new exact candidate manifest.
