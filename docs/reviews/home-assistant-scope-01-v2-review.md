# SCOPE-01 independent review — v2

Verdict: **APPROVE**. The documentation and ledger now retire the misapplied
caching/native-fidelity prerequisite without claiming a new platform capability.
The v1 findings are resolved. Approval covers this scope correction only; the
eight adapter, validation and release deliverables remain unfinished.

Reviewer: `/root/ha_cache_gate_correction_reviewer`.
Implementer/coordinator: `/root`.
Date: 22 November 2026 (Australia/Melbourne).
Candidate: `docs/reviews/home-assistant-scope-01-v2-candidate.json`.
Candidate SHA-256:
`ec5279dccc4897a1c44e564346cae39f364495d8df53beb9bce18b9ff6c19516`.
Base: `6de685f63e060f3003cb93233d2567e004269755`.

## Independence and frozen evidence

The reviewer authored none of the implementation's plan, design, ledger, scope
note, source archives or structural-check records. The v2 candidate also binds
this reviewer's historical v1 rejection report as immutable evidence; that report
is not implementation authored or approved by the reviewer. Only this new v2
review report is written in this review turn. No build, database, model, device,
live endpoint or deployment operation was run.

Independently verified the v2 candidate digest, all seven bound file hashes,
frozen ledger snapshot and scoped diff hashes. The accepted OUT-VERIFY-01 v2
dependency manifest matches its digest and the accepted dependency node. The v1
candidate, ledger, diff and CHANGES_REQUIRED report still have their original
hashes; no earlier rejection or evidence was overwritten.

The prior-plan archive has SHA-256
`ee2b26a0554a1c74a7a3925ba05e46a7ddfd4dec105d9f1203c4938974cba749`,
matching the exact plan source bound by accepted PLAT-SQL-01 v1. The archived
prior ledger is bound by the candidate. All 18 previously accepted node objects
are unchanged against that archive, and their historical review reports match
all 18 recorded digests. Current plan evolution is a new independently reviewed
scope revision; it does not rewrite those historical exact-source approvals.

Verified all 23 HA/validation source hashes, four runtime publication guard hashes
and the one SQL-test hash against the accepted outbound v2 source snapshot.
Documentation-only changes justify content, hash, link-reference and graph checks;
there is no reason to rerun builds or actual database tests. Prior successful
builds remain exact-source evidence for their unchanged implementation, not new
execution claimed by this review.

## Graph and current-state checks

Independently validated 30 unique nodes and 56 edges, dependency references,
acyclicity, separate implementer/reviewer identities and exact Mermaid-to-ledger
edge equivalence. The frozen SCOPE-01 node remains in review and unaccepted.
The eight nodes HA-05, HA-06, HA-07, HA-08, HA-10, HA-11, VERIFY-01 and RELEASE-01
are planned, with no falsely completed implementation/review milestones.
HA-05 depends on accepted CORE-04 and the independently reviewed SCOPE-01 scope.

The former `prompt_public_path_verified` gate is retired rather than marked
satisfied and has zero current consumers. No mandatory prompt-mapping user-choice
gate replaces it. PERF-01 is explicitly optional; RELEASE-01 depends only on
VERIFY-01, with no cache-measurement prerequisite on its critical path. The three
actual backend-measurement, deployment and live-acceptance authorization records
remain unchanged. Historical completion audit and gate-preservation counts are
explicitly labeled historical; the current summary accurately records the newly
planned work and does not claim all current delivery work is complete.

## Contract and resolved findings

The active plan/design/scope note agree that external-to-internal conversation
ID mapping through the existing Ex SDK operations is the only authorized core
extension and is already accepted. The supported HA adapter uses a typed,
versioned envelope in existing SDK task input, preserves supplied text without
cache rewriting, and documents actual task/user semantics and ordinary platform
retention. It does not claim native system priority, special compaction retention
or transparent HA equivalence. Prompt content cannot change authorization,
credentials or tool grants; unsupported capabilities are rejected explicitly,
and durable identity, accepted-job ownership and uncertain-delivery/no-replay
requirements remain intact.

SCOPE-R1 (P2), resolved: design section 10 now verifies the already selected
SDK-only envelope rather than reopening a native-role choice. Its item 9, the
conversation-ID section and concluding paragraph correctly identify the Ex mapping
support as implemented/accepted platform work to reuse, not another core project.

SCOPE-R2 (P2), resolved: live acceptance now verifies exact admitted task-envelope
text and ordinary documented compaction/recovery. It explicitly excludes native
system priority, special persistence and cache observations as live-acceptance
requirements. Cache observations remain optional and separately authorized.
The stale outbound gate count is clearly named as preservation at historical
outbound acceptance, while current summaries show the revised graph.

No open blocking findings remain for the exact v2 scope correction.

## Limits and closure

The actual setup/configuration/packaging guard source remains unchanged and the
component is not enabled by this prose correction. Those guards must be updated
and verified as part of implementing the supported adapter contract, not simply
flipped to claim features work. This approval does not implement or accept any
of the eight remaining feature/validation/release nodes, satisfy backend access,
authorize installation or live actions, publish, merge or deploy anything.

Root may mark SCOPE-01 accepted against this exact candidate and dispatch newly
ready supported-adapter work under the normal independent review protocol.
