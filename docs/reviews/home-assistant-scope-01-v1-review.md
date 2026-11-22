# SCOPE-01 independent review — v1

Verdict: **CHANGES_REQUIRED**. Two residual active design instructions contradict
the corrected SDK-only scope. The graph correction itself is sound; no runtime
source change is requested or authorized by this review.

Reviewer: `/root/ha_cache_gate_correction_reviewer`.
Implementer/coordinator: `/root`.
Date: 22 November 2026 (Australia/Melbourne).
Candidate: `docs/reviews/home-assistant-scope-01-v1-candidate.json`.
Candidate SHA-256:
`9f7f10e7a95aeb262238e702a0e704dd268cc7ca6d0d2a2026c1af048bc13578`.
Base: `6de685f63e060f3003cb93233d2567e004269755`.

## Independence and checks actually performed

The reviewer authored none of the six candidate files, plan/ledger correction,
adapter implementation or prior feature source. Only this new review report is
written. No build, database, model, device, live endpoint or deployment operation
was run.

Verified the candidate digest and all six frozen file hashes before author
corrections, plus the ledger snapshot and scoped diff hashes. Independently
validated the 30-node / 56-edge acyclic graph, exact Mermaid edge equivalence,
existing dependency references and separate implementation/review identities.
The retired prompt gate has no current consumers; it is not marked satisfied.
HA-05 depends on CORE-04 and SCOPE-01. All eight unfinished adapter, validation and
release nodes are planned with no falsely completed implementation milestone.
RELEASE-01 depends only on VERIFY-01; optional PERF-01 is outside its critical path.
The three backend/deployment/live authorization records remain unchanged.

Compared all 18 previous accepted node objects with the exact prior-state
archive; each is unchanged. All 18 historical accepted review reports match the
hashes recorded in that archive. The archived previous plan has SHA-256
`ee2b26a0554a1c74a7a3925ba05e46a7ddfd4dec105d9f1203c4938974cba749`,
matching the plan bytes bound by the accepted PLAT-SQL-01 candidate. Verified all
23 HA/validation source hashes, four runtime publication guard hashes and the
one SQL-test hash against the accepted outbound v2 source snapshot. There is no
new runtime enablement or core capability claim. No build rerun is justified by
this documentation-only correction.

The current contract correctly preserves supplied text through the SDK's task
input, documents actual task/user semantics and ordinary retention, rejects
unsupported capabilities before submission, and keeps authority and no-replay
behavior. It withdraws a mandatory user prompt-mapping choice as an additional
gate. These principles do not excuse contradictory active instructions below.

## Findings

### SCOPE-R1 — native-role choice remains an active implementation decision (P2)

Location: `docs/home-assistant-integration-design-and-plan.md`, section 10,
item 7. It still asks whether native system-role fidelity is required, although
the corrected scope has resolved that question to the supported SDK-only task
envelope. Item 9 and the concluding paragraph also describe the accepted Ex
mapping APIs as an unimplemented design proposal. Rewrite these as the current
SDK-only contract and reuse of already accepted mapping support, without opening
a new user-choice/core-capability gate.

### SCOPE-R2 — live checklist still requires cache measurements and special retention (P2)

Location: the same design document, Live acceptance checklist. The prompt check
still requires complete effective prompts after compaction and measured cache
impact. The declared contract instead proves exact admitted task-envelope text,
documents ordinary platform retention/recovery, and treats actual backend cache
observations as optional separately authorized work. Revise the checklist so
native/special retention or cache measurements cannot reappear as live-acceptance
prerequisites.

The implementer also agreed to label the outbound summary's old
`original_gated_nodes_unchanged` count as historical. Its previous value describes
outbound acceptance rather than the now-replanned graph; make that distinction
explicit to avoid a stale current-status claim.

## Required disposition

Keep SCOPE-01 unaccepted for this exact candidate. Return these prose/tracking
corrections to the implementer, preserve this v1 candidate and report, and freeze
a new revision for independent review. No core, SDK, schema, publication-guard,
HA runtime, backend, deployment or device change is needed to resolve them.
