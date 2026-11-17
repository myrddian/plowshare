# CORE-01 independent contract review

Reviewer: `/root/ha_core_contract_reviewer`.
Implementer: `/root/ha_core_contract_implementer`.
Candidate: `ha-core-01-v1`.
Manifest: `docs/reviews/home-assistant-core-01-v1-candidate.json`.
Manifest SHA-256: `68019f5a3ee63c9aff3517c2a0a59b0969a3c244d99fa45a7f24c41547fe1585`.
Verdict: **CHANGES_REQUIRED**.

## Scope and checks

Reviewed ADR 0077, the matching design changes and SDK documentation against the
CORE-01 acceptance contract. Verified the candidate manifest digest, all four
file/snapshot digests and the frozen tracked diff digest. Inspected ordinary
ConversationOpenHandler, ConversationStore creation/allowance binding, Runs
admission, Callers, Asking, IdentityRepository/JdbcIdentityRepository, service
credential rotation, Retention and contained-project destruction. Compared the
ordinary Java ConversationView and canonical TypeScript conversation replies.

The generic external-ID slice has explicit user authorization independently of
HA adapter convenience. Account-qualified source labels are not grants; aliases
remain subject to current project/target authority. Creation and alias insertion
are specified as one transaction without duplicating conversation creation SQL,
with post-commit log.open only for the creating request. Resolve-only Run-Ex,
ordinary allowance raises, no automatic mutation replay, retained lifecycle and
phased migration are clearly distinguished. Prompt context remains a proposed
separate platform extension and its capability gate remains unsatisfied.

These are design findings, not implementation, PostgreSQL, transport or live
acceptance. No Gradle/database/model/live request or source change was performed.

## Required correction

**CORE01-R1 — P2: Keep the Open-Ex reply consistent with the ordinary result.**

Location: `docs/decisions/0077-conversation-external-aliases.md:92–94` and
`:131–132` (plus any corresponding design/SDK claim).

The ADR says ordinary ConversationView includes lifecycle and that Open-Ex returns
an existing root “with its real lifecycle.” Actual
`plowshare-server/src/main/java/io/aeyer/plowshare/server/api/ConversationView.java`
and `sdk/typescript/src/operations/conversation-replies.ts:77` expose ID, project,
limits, spending and title; neither exposes lifecycle. The stated contract cannot
both retain the ordinary result shape and return lifecycle in that shape.

Preserve the mapped root's lifecycle internally and keep the ordinary payload
unchanged. State explicitly that callers inspect lifecycle through its existing
public operation when they need that state. Remove the implied new lifecycle
field from the Ex result promise. A result-schema extension is unnecessary for
this thin facade and would need additional shared-contract scope/compatibility
work. Recheck the matching design and SDK prose for the same ambiguity.

Disposition: **open**; implementer correction and a new exact candidate required.
No other blocking findings were identified in this review.
