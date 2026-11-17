# HA-01 independent re-review

Verdict: **APPROVE**. No findings remain for this candidate.
Node: `HA-01`. Implementer: `/root/ha_discovery_implementer`.
Reviewer: `/root/ha_discovery_reviewer` (no implementation authorship).
Candidate: `ha-ha-01-v2`, manifest `docs/reviews/home-assistant-ha-01-v2-candidate.json`.
Manifest SHA-256: `55f10437ab8fde48dfecf7284256dda51c04868f72f27d119c2090b5268f3840`.

This replaces the verdict for the authored contract slice with the candidate
above; the original v1 report and its findings remain historical evidence.
Verified this manifest, all six bound file hashes and the frozen dirty-tree diff
hash. Reviewed the three changed Java files against the v1 findings and rechecked
the unchanged discovery/read interfaces. No shared protocol/runtime extension or
server dependency was introduced.

## Finding dispositions

- **HA01-R1: resolved.** `MAX_ATTRIBUTES = 257` consistently bounds effective
  selections, metadata, primitive attributes and unsupported-attribute names.
  Existing settings still reject more than 256 explicitly configured attributes;
  the extra effective slot preserves the implicit `unit_of_measurement` rather
  than dropping requested evidence. The new regression covers 256 explicit
  attributes both with and without that unit, metadata/state projection, and
  rejection of excessive explicit configuration. Independently reran the original
  local Java fixture over compiled v2: legacy settings report 257 effective
  attributes and `fromSettings` now accepts them.
- **HA01-R2: resolved.** The immutable policy derives its disclosable entity set
  from its copied resource selection and offers a profile only when every fixed
  target is disclosable. It omits a mixed-target profile entirely; it neither
  discloses the hidden target nor silently narrows the private execution profile.
  The new regression checks permitted, denied and absent references, original
  target preservation, and the complete profile when both resources are admitted.
  Independently reran a local Java fixture: the private resource remains absent,
  the offered profile count is zero, and the original private action still has
  both targets.

No new blocking issue was found in the fixes. Metadata admission remains separate
from execution authority, and the contract still requires current snapshot/policy
and tool authority checks in later transport/provider work.

## Verification and limits

Inspected the coordinator's sequential formatter and post-format check logs:
The HA module formatter passed, followed by the focused HA contract test plus
`formatCheck`.
JUnit records ten tests with zero failures, errors or skips, including both new
regressions. Reviewer independently checked the two boundary behaviors with local
Java source-launcher fixtures using the compiled candidate; no Gradle invocation
was repeated while CORE-02 owned Java editing. `git diff --check` passed.

This approves only HA-01's typed contract/policy deliverable. HA-02 remains
responsible for live snapshot epoch/revision validation, batched transport,
cache/paging behavior and physical cancellation; later nodes own tool grants,
action execution and deployment. No database, live HA/model/device action,
prompt-fidelity gate satisfaction, merge or live validation is asserted.
Only this review report was authored by the reviewer; root owns delivery state.
