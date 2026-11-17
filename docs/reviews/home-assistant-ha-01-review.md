# HA-01 independent contract review

Verdict: **CHANGES_REQUIRED**.
Node: `HA-01`. Implementer: `/root/ha_discovery_implementer`.
Reviewer: `/root/ha_discovery_reviewer` (no implementation authorship).
Candidate: `ha-ha-01-v1`, manifest `docs/reviews/home-assistant-ha-01-v1-candidate.json`.
Manifest SHA-256: `f5c3e6b41d507e19965b9e5344cfa869c87a226f544697694d7cedbdeeb74a14`.

Reviewed all five authored Java files, the frozen ledger/diff, relevant existing
settings/value contracts, the integration guide and HA-01 acceptance contract.
Verified the manifest, six bound file hashes and frozen diff hash. The new domain
surface uses typed immutable values and interfaces, stays adapter-local, adds no
shared payload variant or server dependency, and preserves primitive-only action
schemas. The following findings prevent acceptance of this exact candidate.

## Findings

### HA01-R1 — Preserve the existing maximum attribute selection (P2)

Location: `HomeAssistantDiscoveryPolicy.java:34` and `:72–74`; corresponding
`Description` and `State` attribute-set bounds in `HomeAssistantResources.java`.

An existing valid `HomeAssistantSettings.Entity` may select 256 attributes other
than `unit_of_measurement`. Its `selectedAttributes()` adds the implicit unit,
producing 257 entries. `fromSettings` passes this to the new `Selection`, which
refuses anything above 256. The initial compatibility factory therefore rejects
an existing valid configuration despite its documented compatibility guarantee.
Simply increasing `Selection` alone would leave the same boundary in returned
Description/State projections.

Independently reproduced against the current compiled candidate using a local
Java source-launcher fixture: legacy settings accepted 256 explicit selections
and reported 257 effective attributes; `fromSettings` threw
`IllegalArgumentException: HA selection exceeds its bound`.

Required correction: consistently account for the implicit unit across selection,
metadata and state contracts, preserving the existing 256 explicit-attribute
configuration ceiling without silently dropping configured evidence. Add a
boundary regression for 256 distinct attributes with and without an explicit unit,
and assert effective projected metadata/state remain valid and bounded.
Disposition: open; implementer correction and independent re-review required.

### HA01-R2 — Keep action descriptions inside the metadata disclosure ceiling (P2)

Location: `HomeAssistantDiscoveryPolicy.java:156–160` and
`HomeAssistantResources.ConfiguredAction`.

`actions(reference, revision)` checks whether one configured target matches the
selected resource, then exposes the entire internal `HomeAssistantSettings.Action`
including every fixed target ID. A profile targeting an allowed resource and an
otherwise undisclosed resource therefore discloses that second identifier through
the allowed reference. Execution configuration and metadata disclosure are
separate ceilings in this contract; the action profile currently has no explicit
permission to disclose those otherwise hidden IDs.

Independently reproduced with resource policy containing only `light.office` and
a configured profile targeting `light.office` plus `light.private_bedroom`:
selection of the private reference returned empty, while the offered profile's
public target list contained both IDs. The existing single-target test does not
cover this route. This finding concerns disclosure, not a claim that a new device
action has already been executed.

Required correction: define a safe public action-description projection that does
not leak undisclosed target IDs (or explicitly intersect the offer with the
metadata ceiling without changing the fixed execution targets). Keep the private
execution profile separate, and test a mixed-target profile plus an absent/denied
reference. Do not solve this by silently expanding resource disclosure or by
narrowing the actual action's effects behind its public description.
Disposition: open; implementer correction and independent re-review required.

## Evidence and limits

Inspected the coordinator's post-format Gradle log and JUnit result: eight focused
contract tests passed with no skips/failures/errors, and `formatCheck` passed.
No Gradle invocation was repeated by this reviewer; its shared execution slot was
reserved for the coordinator. The two independent local Java reproducers above
used the already compiled candidate and did not alter repository source.
A default JShell launch first hit the sandbox's local-listener restriction;
source-launcher fixtures avoided listeners and reproduced both findings normally.

No database, live HA, model, provider, network or device check was performed.
This node is a contract slice: transport freshness/cancellation, snapshot caching,
tool grants/provider composition and action execution remain later deliverables.
No merge, deployment, prompt-fidelity gate satisfaction or live validation is
asserted. The reviewer changed only this report; root alone updates delivery state.
