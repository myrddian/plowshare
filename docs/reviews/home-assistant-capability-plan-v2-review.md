# CAP-PLAN v2 independent review

Date: 24 November 2026. Reviewer: `/root/ha_capability_reviewer`.
Implementer: `/root`, with the Java seam supplied by
`/root/ha_capability_foundation_implementer`.
Verdict: **APPROVE** for design/contract and source implementation dispatch.
The earlier v1 review remains immutable historical evidence; this review
supersedes its nine-tool composition proposal.

The actual v2 design, plan, behavioral contract and concrete Java interface were
reviewed. Capability mode now publishes only `health`, `resolve`,
`describe_fields` and `read_fields`. Legacy data handlers are absent in that mode
because their frozen selection could bypass effective-exposure revocation or new
field exclusions. With discovery configuration absent, the existing six tools
and their schemas remain unchanged. This is a correctness fix to the adapter
composition and introduces no core, public SDK or shared-runtime change.

The typed seam supplies immutable resource/field DTOs, full per-field descriptors
and provenance, whole-result resource/field ambiguity before pagination, bounded
20-total-field reads and 16-field description pages. Registry-less references
are epoch-scoped rather than claiming restart continuity. An anchored selector
that encounters a replacement cannot disclose that replacement's metadata or
values under its old handle. The identity store's owner separately binds the
full selection/profile configuration, while stable references do not include a
random per-connection epoch. Temporary missing/unavailable observations preserve
established field descriptions. Unknown semantics and unsupported types remain
explicit.

The v1 review's authority, native effective-exposure helper, complete bounded
endpoint output, no-fallback behavior, independent ownership and separated
operational milestones remain valid. Exact source-node approval still requires
implementation tests, malformed/scoped input, rename/replacement/restart,
revocation, schema-change and public SDK evidence. No implementation, package or
live acceptance is inferred from this planning verdict.

Reviewed source SHA-256 values:

| Path | SHA-256 |
| --- | --- |
| `docs/home-assistant-integration-design-and-plan.md` | `966a0c2c64fcf7cffa9aa2ffac8517fced700e38279d1bed3344398cb4593358` |
| `docs/home-assistant-delivery-plan.md` | `575b5f02b6d7c40f477294225901fd6c40cc12c45b5a7ae4e1d283c493868bef` |
| `docs/reviews/home-assistant-capability-contract.md` | `13c6c521069587ebab64c69f5573c7c3d1e7fe2667c5d5f70121abc4e8dc25fa` |
| `docs/reviews/home-assistant-capability-interface.md` | `251ef5b1a94d21714a2a7076ddc8ca9fcef731e15c1e524b6465f58d90f74846` |

Ledger closure metadata is excluded from recursive source hashes. Existing
diagnostics evidence and the v1 report were not modified.
