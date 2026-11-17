# HA-01 v5 independent re-review

Verdict: **APPROVE**. No findings remain for this candidate.
Node: `HA-01`. Additive capability author: `/root/ha_provider_implementer`.
Original contract author: `/root/ha_discovery_implementer`; v3 accessor author:
`/root/ha_reads_implementer`.
Reviewer: `/root/ha_discovery_reviewer` (no implementation authorship).
Candidate: `ha-ha-01-v5`, manifest `docs/reviews/home-assistant-ha-01-v5-candidate.json`.
Manifest SHA-256: `d8babe9febaae69e1d9b630d3de86b16e7c300e95cc122c5f9b9585cf927ada8`.

Verified this manifest, all six bound file hashes and the frozen compressed
working-tree diff digest. All five HA-01 Java source/test hashes are identical to
the statically reviewed v4. V4's pending report is preserved as historical
evidence; its absent formatting proof is now supplied by an actual separate check,
not by reinterpreting the earlier failed combined invocation.

The additive `OperationBudget.capped(Duration)` retains the earlier absolute
monotonic deadline and only tightens it; validation rejects zero, negative and
over-one-day caps. Bounded deadline differences make signed subtraction safe
across nano-time wraparound. Expired parents cannot be revived, and later caps
return the existing budget. The previous independent local Java fixture verified
these behaviors on this unchanged source. Existing HA01-R1/R2 fixes and the
immutable selected-reference accessor remain intact. No source compatibility or
privacy regression was found.

Independently inspected current JUnit: all eleven
`HomeAssistantResourceContractsTest` cases pass with zero failures, errors or
skips. Independently inspected
`/private/tmp/ha-delivery-current-format-check.log`: it explicitly records
`> Task :formatCheck`, `BUILD SUCCESSFUL in 4s`, and 43 tasks (3 executed,
40 up-to-date). Rechecked the candidate source hashes against these results.
No unchanged test was unnecessarily rerun by this reviewer.

This approves only HA-01's contract/policy deliverable and the additive budget
method. Other provider/server tests and their combined task status do not form a
whole-build acceptance claim here. No Gradle, database, live HA/model/device
request, implementation fix or ledger edit was performed by this reviewer.
No merge, deployment, live validation or prompt-fidelity gate satisfaction is
asserted. Only this report was authored; root owns delivery state.
