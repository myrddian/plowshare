# HA-02 v2 independent re-review

Node: `HA-02`. Reviewer: `/root/ha_reads_reviewer`.
Implementer: `/root/ha_reads_implementer`.
Candidate: `ha-ha-02-v2`.
Manifest: `docs/reviews/home-assistant-ha-02-v2-candidate.json`.
Manifest SHA-256: `0cf20cf8f129e6f10fda75fb25c284d11575b33bca78abdf5a75add287cb3c01`.
Verdict: **APPROVE**.

## Scope and exact evidence

Verified the manifest digest, all twelve bound file/snapshot hashes and the frozen
gzip diff digest. Re-read the actual adapter generation/loss paths, new observation
owner, updated resource contract, lifecycle regressions and implementation report.
The catalogue/source/codec and approved HA-01 policy accessor remain unchanged
from the prior reviewed candidate; their earlier scope assessment still applies.

Independently inspected current JUnit XML for all five selected classes:

| Class | Tests | Failures/errors/skips |
| --- | ---: | --- |
| Resource contracts | 10 | 0/0/0 |
| Resource catalogue | 8 | 0/0/0 |
| Native resource transport | 9 | 0/0/0 |
| Legacy adapter | 10 | 0/0/0 |
| Observation lifecycle | 9 | 0/0/0 |
| **Total** | **46** | **0/0/0** |

The current focused-check log records global formatCheck passing in 12 seconds.
I also checked whitespace with `git diff --check`. I did not run Gradle, a database,
a model, live HA or device operations. The earlier full repository check belongs
to v1 and is not represented as full-check evidence for v2.

I recompiled and reran both original independent in-process probes against the
actual built v2 classes. The generation probe uses the newly supplied WebSocket
factory seam; neither probe requires network access or edits implementation/test
sources. Temporary probe files remain outside source under
`/private/tmp/ha-review-probes/`.

## Finding dispositions

**HA02-R1 — resolved.** Connection loss first captures and fences the owning
attempt under the adapter monitor. Captured-socket cancellation and pending-future
failure no longer wait for arbitrary observer work. The observer receives the
retained Gap through a separate bounded FIFO worker rather than under the
connection monitor or expired caller's abort stack.

The independent blocked-observer probe now reports:

```text
CALLER_FINISHED_WHILE_CALLBACK_BLOCKED=true
SOCKET_CANCELLED_BEFORE_CALLBACK_RELEASE=1
SOCKET_CANCELLED_AFTER_CALLBACK_RELEASE=1
```

The new owner reserves Gap capacity, retains accepted observation order, fences
new intake/reconnect while physical delivery is unresolved, and does not replay
an uncertain observer invocation. Callback expiry cannot interrupt a subsequent
delivery. Cleanup has a separate finite budget and reports incomplete physical
work rather than claiming that arbitrary callback/journal work was aborted.
The code retains the original active/unconfirmed identity when a callback ignores
interruption. The nine lifecycle tests cover blocked Gap/state observers,
capacity, expiry, cleanup, uncertainty and generation races.

**HA02-R2 — resolved.** State mutation receives and checks the captured epoch
under the same monitor as snapshot/version mutation. Authentication and result
association also recheck generation; retirement prevents late authentication from
reviving the same failed attempt before any replacement epoch exists. Socket
assignment and failed-start cleanup retain the corresponding fences.

The independent callback/reconnect probe now reports:

```text
REPLACEMENT_STATE=2
STATE_AFTER_OLD_CALLBACK=2
NEW_SOURCE_STATE_VERSION=0
```

Deterministic lifecycle tests additionally inspect the owning authentication flag,
so a status projection cannot hide an illegal late `connected = true` mutation.
The tests exercise both old-generation and same-retired-attempt authentication.

## Acceptance and limits

No blocking finding remains for HA-02. The accepted implementation provides the
configured native discovery/batched-read capability with policy-first disclosure,
bounded snapshots and caller-owned budgets. It does not establish SDK named tool
publication, grants, live deployment or physical device behavior.

The documented legacy manual timeout/frame bounds remain explicit. Observer work
that ignores interruption can outlive finite cleanup while retaining its physical
slot and original identity; safe journal-owner replacement still requires the
owning lifecycle to reconcile that work. This is not represented as transparent
cancellation, durable observation replay or a second persistent runtime.
