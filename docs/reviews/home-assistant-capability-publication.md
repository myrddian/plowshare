# HA diagnostics and capability discovery publication

The user authorized commit, push and pull-request creation on 24 November 2026,
with control disabled and validation evidence included. This is a precommit
checkpoint; Git/GitHub metadata identifies the later commit and PR without a
recursive commit hash in this document. No merge or rollout is part of this request.

The [final capability evidence](home-assistant-capability-evidence.json), SHA-256
`145ac9594783ee7df3816bdddf342e927f2fcf4918c87ab7c54cf72483073d3f`,
matches all 154 reviewed source inputs and both built payloads. It includes the
earlier diagnostic implementation. The
[diagnostic evidence](home-assistant-diagnostics-evidence.json) and its reviews
remain immutable historical records of that earlier candidate; source affected
by capability work is revalidated by the final candidate.

Independent approval is recorded in the
[foundation review](home-assistant-capability-foundation-review.md),
[tools review](home-assistant-capability-tools-review.md),
[exposure review](home-assistant-capability-exposure-review.md) and
[integrated review](home-assistant-capability-integrated-review.md).
The [delivery ledger](../home-assistant-delivery-state.json) records accepted nodes,
exact report hashes and separate source/publication/deployment milestones.

Validation passed: 250 Java HA tests, 159 HA Python tests plus Ruff,
`formatCheck check installDist`, all eight installed outbound-package checks,
and complete Assist archive/unchanged public SDK verification. The source evidence
contains exact commands, log hashes and artifact manifests. No full-db tests,
live model/device tests, HA/server restart or deployment was performed.

The tested source base is `f33a10daeaf09f84189edfba1039ee98c0b8ec9e`.
Before publication, main was refreshed to
`368d59e15be9186b2c4270b701248bd4fce0c96e`; its intervening change does not
touch the HA adapter or its design/delivery documents. The reviewed candidate is
published without changing its implementation or claiming validation of a future
merge result. No core, SDK, shared-runtime or database changes are included.

Control remains disabled and live entity/attribute policy is unchanged. Assist
version 0.2.0 and the separate provider are built artifacts awaiting installation.
Optional follow-Assist mode requires the companion endpoint; fixed selection does
not. New catalogue/identity owners must use the documented drain, reconciliation
and journal-preservation process. Source/package checks do not establish live
availability, physical sensor health or voice acceptance.
