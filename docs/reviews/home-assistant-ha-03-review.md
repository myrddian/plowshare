# HA-03 independent review

Verdict: **CHANGES_REQUIRED**. One acceptance-evidence finding remains; this review
does not establish a production-code defect.

Node: `HA-03`. Implementer: `/root/ha_provider_implementer`.
Independent reviewer: `/root/ha_provider_reviewer` (no implementation authorship).
Candidate: `ha-ha-03-v1`, manifest
`docs/reviews/home-assistant-ha-03-v1-candidate.json`.
Manifest SHA-256:
`25ae4382d22cdc71e4046dbc9cfc916cc8e2b0ad91ba773103b0bf69b5f3ed5b`.
Date: 17 November 2026.

## Finding HA03-R1 — P2: sustained-service evidence must use distinct invocations

`HomeAssistantToolProviderTest.java:753` repeatedly calls the shared fixture,
whose `HomeAssistantReadToolsTest.java:147` supplies the same constant invocation
UUID. The loop advances the broker position from 1 through 80, including across
restart. This is outside the public immutable invocation-ledger contract: an
existing invocation never republishes its request at another position. The test
does exercise compaction and checks that full receipts are empty after each read,
but it does not establish the claimed sustained stream of normal distinct
invocations beyond the 32-receipt bound. The generation and post-reconciliation
follow-up fixtures likewise reuse a retired invocation at a new position.

Give every new logical call in these normal-traffic scenarios a fresh UUID and
retain its exact request identity for actual duplicate/replay checks. Keep the
80-call, two-lifetime scenario, assert completed reads/results and empty full
receipts, and replay the saved first invocation at its original retired position
before and after restart to verify refusal before a handler. Use a distinct
invocation for the successor position in the generation and reconciliation tests.
Rerun the focused canonical cases, update the implementation evidence and freeze
a new exact candidate. Deliberate same-invocation duplicate tests should retain
their existing identity. No production change is requested unless those realistic
fixtures expose one.

## Reviewed implementation and evidence

Verified the manifest digest, all 15 bound file hashes, the frozen compressed
tracked-diff digest, and its HA-02 dependency manifest digest. Rechecked authored
source hashes after inspection. Reviewed the HA-03 graph acceptance contract,
TOOL-PROVIDER manual, implementation report, six new production classes, focused
tests and the additive HA-01 operation-budget method. The HA-01 v5 independent
approval and its exact source hashes were also checked.

The four read declarations have configured prefixes, closed schemas and typed
conversion into the accepted resource contracts. Full binding/credential/policy/
epoch/version references fence selection. Attribute projection intersects the
host-configured resource ceiling; tools cannot supply credentials, entity targets
or a replacement policy. Result-size rejection does not truncate or refetch.
Installation declares provider permission separately from agent tool grants;
discovery itself creates none. Per-invocation server authorization remains a
server-owned contract, not something the fake SDK fixtures establish live.

The host consumes the public SDK and resource interfaces. Production imports and
the unchanged Gradle production dependency declarations contain no server/core
dependency. A usable public Java composition path exists; a new packaged executable
is outside this node, and the existing manual-alias runtime is unchanged. No
action execution or prompt-context extension was added.

The configurable two-second default pass budget covers SDK calls, journal work
and resource execution. The earlier admitted invocation budget is carried through
ACK, handler and publication without resetting the outer deadline. Async SDK calls
use explicit NON_BLOCKING admission and cancellation, with no automatic replay.
The physical pass stays owned until worker and cancellation settle. Cancellation
and potentially blocking interruption run outside the caller return path and
admission lock. Late callbacks are fenced. Cleanup retains the journal lock when
physical work remains; reentrant pass/self-join paths refuse promptly.

The journal uses an exclusive lock, strict typed versioned decoding, bounded
receipts/fences, configuration binding, atomic replacement and file/directory
fsync. Write uncertainty prevents continuing from an older in-memory snapshot.
Catalogue intents persist before send; generic Java SDK IOException remains
uncertain, while only structured NOT_SUBMITTED evidence permits clearing an
unsent intent. Catalogue and result uncertainty use original-identity read-only
reconciliation, without resubmission.

Static review traced retirement through the actual SDK Provider: intent precedes
ACK/handler; validated result publication or exact reconciliation precedes DONE.
The host separately checks exact ACK coordinates, issued through-position and
absence of a gap. Only acknowledged DONE receipts with a known non-UNKNOWN result
and matching cursor generation retire atomically into a same-generation watermark.
Exceptional receipts remain addressable below the watermark, old retired positions
refuse before execution, and missing/changed generations cannot inherit a fence.
The public Relay/Relay-tools manuals and owning immutable invocation repository
corroborate the ordered-position and no-republication premises. These sources were
inspected read-only; no internal access was added to the adapter. UNKNOWN receipts
remain bounded at 32 and are not evicted. HA03-R1 concerns realistic acceptance
coverage of sustained completed traffic, not a discovered contradiction in this
retirement algorithm.

Independently inspected current JUnit XML: 40 cases pass with zero failures,
errors or skips (ReadTools 5, Provider 17, Journal 5, Transport 2, ResourceContracts
11). The provider timestamp is `2026-11-17T00:41:19`, the transport timestamp is
`00:41:27`, and the other three are `00:41:25`. Reviewed the corresponding combined
log at `/private/tmp/core03a-ha03-focused-check.log`: its overall invocation FAILED
on a separate server test-compilation warning treated as error; this is not a
passing combined-build claim. Separately inspected
`/private/tmp/ha-delivery-current-format-check.log`: explicit `:formatCheck`,
BUILD SUCCESSFUL in four seconds, 43 tasks (3 executed, 40 up-to-date).
Reviewer `git diff --check` passed.

No Gradle, database, live server/HA/model/device request, installation or deployment
was run by this reviewer. Existing passing checks were inspected rather than
unnecessarily repeated while coordinated server work continued. Live grants,
cross-host installation and physical behavior remain later integrated acceptance;
fixtures and formatting do not prove them. Only this review report was authored.
Root owns delivery state and node closure. No merge, deployment, live validation
or prompt-fidelity gate satisfaction is asserted.
