# HA-04 v2 affected-source independent review

Verdict: **APPROVE**

- Candidate: `ha-ha-04-v2`.
- Manifest: `docs/reviews/home-assistant-ha-04-v2-candidate.json`.
- Manifest SHA-256: `a8a46645535397cf3b01b2298b7e96af1b441f78377d97acb07a899647928c96`.
- Affected-source implementer: `/root/ha_events_implementer`.
- Independent reviewer: `/root/ha_action_reviewer`.
- Reviewed on 2026-11-17. No implementation or ledger edits by the reviewer.

## Scope and findings

No blocking findings. All candidate-bound file hashes and the frozen tracked-diff
digest match. Comparing the original approved HA-04 v1 manifest with current
source shows only `HomeAssistantActionPolicy.java` and `ACTION-PROVIDER.md`
changed within its bound file set. The new pure service validator, focused test
and separate affected-source report complete this narrow change. This review
does not approve the other HA-09 routing changes.

The named-control policy refuses `conversation.process`, `ai_task.generate_data`
and `ai_task.generate_image` during both construction and replacement. Validation
finishes before assigning the replacement map or advancing its generation, so
refusal preserves the prior policy and outstanding generation fences. There is
no override that could turn a fixed entity target into an unsupported inference
destination guarantee. The existing handler's exact profile equality and policy
checks prevent read-discovered inference profiles from becoming named-control
execution permits.

I independently inspected the installed offline HA source: the Conversation
component registers `SERVICE_PROCESS`, whose value is `process`, and AI Task
registers `SERVICE_GENERATE_DATA` and `SERVICE_GENERATE_IMAGE`, whose values are
`generate_data` and `generate_image`. The guard uses these actual service names.
No HA import, remote service call or optional dependency installation was needed.

The documentation correctly limits the guarantee to these direct inference
services. Arbitrary scripts and indirect service chains are not claimed to have
universal recursion detection. Read discovery and the separately configured
legacy manual profile retain their contracts. This change does not alter native
transport, complete intent/fingerprint matching, SDK journal recovery, receipt
delivery/effect semantics, final queued revocation checks or fresh verification.
Those source paths remain at the original approved hashes.

## Verification inspected

The saved evidence under `/private/tmp/ha09-verification-evidence` records
**125 cases in 15 classes**, zero failures, errors or skips: 82 runtime cases in
seven classes and 43 HA cases in eight classes. The HA selection includes the
seven new service-policy cases, all five existing action test classes, ten
manual adapter cases and two pipeline invocations. The new fixture checks each
blocked service, atomic failed replacement, refusal through both named tool
operations, ordinary device controls and preservation of the manual value
contract.

- Focused check: BUILD SUCCESSFUL in 49s; log SHA-256
  `5efc87aabe673be22459b4358fd91848d92cdb4ad65bdc122a37cf96c8f7580b`.
- Global format: BUILD SUCCESSFUL in 4s; log SHA-256
  `50c67403ce9ad4e9e43f93d34ad3dda3fa0144888fd47dc32fc37514ce21c78f`.
- Separate formatCheck: BUILD SUCCESSFUL in 4s; log SHA-256
  `ed44261c28861cf1c8e7a306e0b9ed263acf3be4e0b32cb7dc450bb1801498b0`.

I inspected the saved XML totals and logs, ran no Gradle or database tests and
made no model or live-device calls. This approves only the exact affected HA-04
candidate. Repository-wide verification, complete HA-09 review, prompt-dependent
features, packaging, deployment and live acceptance remain separate.
