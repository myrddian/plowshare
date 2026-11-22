# RELEASE-01 v1 independent review

Verdict: **APPROVE**.

Reviewer: `/root/ha_release_readiness_reviewer`.
Implementer: `/root/ha_release_readiness_implementer`.
Coordinator coauthor: `/root`, current plan/status paragraphs only.
Candidate: `docs/reviews/home-assistant-release-01-v1-candidate.json`.
Candidate SHA-256:
`c68faa34ed945401db4736fb794bf8d80348097d73801887c91ec61f8db581ff`.

I read the three frozen documents, working-tree diff, ledger snapshot, accepted
VERIFY-01 review and final validation evidence. I verified all three candidate
file hashes, frozen diff/snapshot hashes, independent author/reviewer identities
and the accepted dependency candidate/review hashes. I authored none of these
implementation documents and changed no source, artifacts or ledger. This
review report is my only authored file. No builds or runtime tests were run for
this documentation-only node.

## Findings

No blocking findings remain. The release report and runbook bind the validated
dirty working tree rather than treating HEAD or a version string as sufficient
identity. The final evidence SHA-256 is
`dbf472faa3aa0a62a832698e097ef77d0790da169b909bf062324dbc601a5e67`.
I independently verified its 116 current source hashes, the exact Assist ZIP
SHA and all 50 contents hashes in its 51 entries, all 56 outbound installation
file hashes, and the ten recorded dependency candidate/review pairs. The
VERIFY-01 candidate and independent APPROVE review match their recorded hashes.
All 4,042 protected core/protocol/SDK/shared-runtime source hashes still match
the starting snapshot. These documents add no runtime or artifact changes.

The recorded checks agree with the independently accepted VERIFY evidence:
135 outer HA cases including one wrapper requiring three installed subprocess
cases, Ruff lint/format, default repository check and HA distribution build,
eight fresh installed outbound checks, and unchanged SDK package conformance.
The report distinguishes recorded coordinator checks from tests run by its
author. I verified all 24 local documentation links in the candidate resolve.
No PostgreSQL test is justified or claimed by this node.

The runbook supplies explicit target/version/configuration/principal/grant
preflight, exact artifact comparison before transfer/switch, current-work
inventory, backups with honest validation limits, finite phase observation
allowances, clean owner shutdown and separately authorized public functional
checks. A phase observation timeout does not authorize killing accepted work or
replaying uncertain effects. HA control flags/catalogue visibility remain
separate from actual membership and exact tool grants.

Recovery preserves entry/provider journals, credential/source fences and original
receipts. The runbook explicitly records old HA chat-ID refusal after a process
restart, deliberate new-chat handling after reconciliation, and each repeated AI
Task action being a fresh invocation. Rollback requires compatibility with the
current journal/configuration and preserves newly written receipts; restoring a
stale backup over pending/uncertain work is prohibited. A stopped or reverted
client is not a remote-job cancellation or an undo of a physical action.

The coordinator's plan update correctly replaces obsolete closed-guard status
with the independently approved existing-SDK path, preserves historical evidence,
retains independent review before closure and separates source acceptance from
publication, merge, deployment and live validation. The retired prompt gate has
no active consumer; optional cache measurements do not block release readiness.
The three remaining optional/operational authorizations are not marked satisfied.

## Acceptance limits

Approval establishes source release readiness for the exact reviewed artifacts
and documented existing-SDK contract. HA 2026.9.4/Python 3.14 is the validated
baseline; the actual target version and compatibility remain unverified.
Prepared HA text retains ordinary task/user semantics without native system-role,
special compaction or prefix-cache rewriting promises. Unsupported image,
attachment, evaluation, nested/custom schema and HA LLM-tool features are explicit.

No publication, commit/merge, deployment, HA restart, live model/voice check,
device action or backend cache measurement is established by this approval.
DEPLOY-01 and LIVE-01 retain their explicit target/action gates; PERF-01 remains
optional. Root may mark RELEASE-01 accepted after checking this exact verdict and
candidate identity, with merged/deployed/live milestones unchanged.
