# CORE-01 independent contract re-review

Reviewer: `/root/ha_core_contract_reviewer`.
Implementer: `/root/ha_core_contract_implementer`.
Candidate: `ha-core-01-v2`.
Manifest: `docs/reviews/home-assistant-core-01-v2-candidate.json`.
Manifest SHA-256: `d15c764b81a308f4365b8406619e3890555c8277218830324e10ce3d4bc1dcca`.
Verdict: **APPROVE**.

## Scope and evidence

Verified the manifest digest, all four authored/snapshot digests and its frozen
tracked-diff digest. Rechecked the corrected ADR 0077, design and SDK prose against
the ordinary Java ConversationView, canonical TypeScript ConversationView,
conversation.list contract and ConversationListHandler. The unchanged ordinary
view exposes no lifecycle field. Listing accepts an explicit project/lifecycle
filter; the correction properly describes membership as a point-in-time read,
not an admission or reservation, and explicitly says conversation.lifecycle is a
mutation and must not be used for inspection. `git diff --check` passed.

The independent v1 review remains preserved in
`home-assistant-core-01-review.md`; this report does not rewrite its verdict.
**CORE01-R1 is resolved**: the Ex reply keeps the ordinary result shape, while
mapped lifecycle remains internally validated and unchanged. No new shared
result field or lifecycle mutation is introduced.

Rechecked the design's FIDELITY-01 summary against
`home-assistant-prompt-fidelity-decision.md`. It accurately preserves the public
native-system-role capability gap, USER semantics of task/prompt.pre, fixed
log.open limitation, separately proposed externalContext successor and unsatisfied
prompt gate. The Ex identity slice does not authorize that successor or claim it
implemented. This consistency check does not satisfy the capability gate.

The v1 source review of generic platform scope, authenticated account partition,
current target/project authority, ordinary creation transaction, post-commit
log.open disposition, resolve-only run, unchanged existing limits, explicit
ordinary allowance raise, no replay and retention/migration remains applicable.
The implementation nodes must prove these required contracts with their own
exact-source checks; this approval accepts the design contract only.

No findings remain. No runtime code, ledger, database, model or live system was
changed. No Gradle/database/live suite was run for this documentation correction.
