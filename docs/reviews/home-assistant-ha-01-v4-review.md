# HA-01 v4 independent re-review

Verdict: **PENDING FORMATTING EVIDENCE**. No source finding identified.
Node: `HA-01`. Additive capability author: `/root/ha_provider_implementer`.
Original contract author: `/root/ha_discovery_implementer`; v3 accessor author:
`/root/ha_reads_implementer`.
Reviewer: `/root/ha_discovery_reviewer` (no implementation authorship).
Candidate: `ha-ha-01-v4`, manifest `docs/reviews/home-assistant-ha-01-v4-candidate.json`.
Manifest SHA-256: `92e201de26647cf90b0094e31d40582749bd3933cb39b1d366e6b6ead883d745`.

Verified this manifest, all six bound files and the frozen compressed dirty-tree
diff digest. Against accepted v3, only `HomeAssistantResources` and its contract
tests changed. Existing HA01-R1/R2 resolutions and the immutable selected-reference
accessor remain unchanged.

The new `OperationBudget.capped(Duration)` preserves the earlier absolute deadline
and can only tighten it. It returns the parent instance for a later proposed cap;
a tighter child stores the new earlier absolute deadline, without resetting the
parent. Positive-duration validation remains shared with `start`, with a one-day
maximum. That bound keeps the difference between applicable deadlines well within
half the signed nano-time range, so subtraction-based comparison remains safe
across `System.nanoTime()` wraparound. Expired parents cannot be revived by a new
cap. No changed timeout semantics imply remote abort or authorize replay.

The new regression exercises tightening, retained identity for non-tightening
caps, expiry and invalid durations. Independently ran a local Java source-launcher
fixture over the compiled v4 that verifies cap tightening, no extension/reset and
rejection of zero/oversize caps. No source compatibility regression or new blocking
issue was found in this additive method.

Current JUnit records all eleven HA-01 contract cases passing, with zero failures,
errors or skips. The combined HA-03-focused run records 34 of 35 tests passing and
one provider-host fixture failure; its overall Gradle invocation failed. That is
not claimed as a whole-run pass or HA-03 acceptance. The supplied log
`/private/tmp/ha-03-focused-check.log` contains no executed `formatCheck` task; the
coordinator has been asked for the separately reported current formatting proof.
Final closure waits for that evidence.

No Gradle invocation, database, live HA/model/device request, source fix or ledger
edit was performed by this reviewer. Only this report was authored. This review's
scope is the HA-01 contract addition, not provider behavior, deployment, live
validation or prompt-fidelity gate satisfaction.
