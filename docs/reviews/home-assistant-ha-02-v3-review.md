# HA-02 v3 affected-source re-review

Node: `HA-02`. Reviewer: `/root/ha_reads_reviewer`.
Affected-source implementer: `/root/ha_action_implementer`.
Candidate: `ha-ha-02-v3`.
Manifest: `docs/reviews/home-assistant-ha-02-v3-candidate.json`.
Manifest SHA-256: `9612efe6a189eb2f0b61d3e353d5feb5714cfb4f35fe4459a73fd5c0b4061997`.
Verdict: **APPROVE**.

## Scope and source verification

Verified the manifest, five bound file/snapshot hashes and frozen gzip diff hash.
Compared current hashes with the accepted HA-02 v2 manifest: only Adapter, Wire
and ResourceCatalog changed within that candidate's file set. Its observation
owner, other source contracts/codec portions, prior reports and tests remain
unchanged. Inspected the three additions against their actual action contracts
and current focused evidence; this is an affected HA-02 approval, not approval of
the separate complete HA-04 provider/journal/action deliverable.

The original read/manual command overload delegates with no action guard or
attempt. Added remaining-budget checks preserve that original budget and refuse
expired work rather than creating a new timer. The action-only guard runs after
slot admission, outside the adapter monitor. Its captured epoch/request identity
and conservative possible-send marker do not alter read/manual wire shapes.
Observer isolation, retired-generation checks and captured-socket cancellation
remain intact.

The typed optional action-context helper reuses the existing acknowledgement
codec; it does not expose HA user identity or equate acknowledgement with observed
physical success. The additive cached-offer validator performs no refresh/read/
network work, compares the full configured profile and exact revision, and takes
source snapshots outside its catalogue lock. It preserves lock ordering and
refuses dirty, replaced or expired evidence. It is explicitly an admission fence,
not a lock held across a remote effect or a promise to undo later revocation.

## Current verification

Independently inspected the preserved 13 JUnit XML results under
`/private/tmp/ha04-verification-evidence/`: 90 cases, zero failures/errors/skips,
including the 26 selected existing HA-02 catalogue/native/observation cases and
24 new action cases. The separate formatCheck log reports success. Those results
were not substituted for the legacy tests absent from that selection.

Because the shared command helper changed, requested current manual-path
regression evidence. Root then ran the ten `HomeAssistantAdapterTest` cases and
two parameterized mock `PipelineTest` cases. Independently inspected their
current XML and `/private/tmp/ha04-legacy-regression-check.log`: all 12 passed,
zero failures/errors/skips, 15 seconds. The first attempted invocation failed
before tests due to unrelated server scaffolding compilation; it is not counted
as a test result. No database Harness test was necessary or run for this review.

Recompiled and reran the two original independent in-process probes against the
actual current built HA classes. Blocked observer cleanup still returns promptly
with socket cancellation already issued; an old state callback still leaves the
replacement value and source revision unchanged. These bounded probes use fixture
identities, no network and temporary files outside source.

Rechecked all frozen HA source hashes after the legacy run. No runtime, test or
ledger source was edited by the reviewer; no Gradle, database, model, live HA or
device operation was initiated by the reviewer.

## Disposition

No blocking finding remains in the affected HA-02 source. The v1 observer and
callback-generation findings remain resolved. Existing manual aliases and typed
read/discovery contracts have current relevant regression evidence.

This approval does not establish HA-04 acceptance, a current repository-wide
check, live grants, physical action verification or deployment. Existing documented
manual timeout/frame and uncooperative-observer cleanup limits remain unchanged.
