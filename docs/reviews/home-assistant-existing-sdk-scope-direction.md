# HA adapter scope: existing SDK, no caching/fidelity prerequisite

Date: 22 November 2026. Implementer/coordinator: `/root`.
Status: scope correction ready for independent SCOPE-01 review; no feature or
runtime guard is enabled by this documentation change.

The user clarified that the prompt fidelity gate concerned caching awareness.
It was not a requirement to change Plowshare to accommodate potentially poor
upstream prompts. The coordinator incorrectly turned that concern into native
system-role equivalence and a new platform prerequisite. That interpretation is
superseded; the earlier mandatory prompt-mapping question is withdrawn as a gate.

The only authorized core extension is external-to-internal conversation ID
mapping via SDK Ex operations, which is already accepted. No new system-context
API, runtime, schema, shared protocol or cache mechanism is part of this work.

The current SDK `AgentRunRequestEx` accepts `task`, agent/project/source and
external conversation identity with ordinary options. It has no native per-run
system field. Use a typed adapter request envelope serialized into that existing
task input. Preserve supplied HA text unchanged, identify provenance and document
its actual task/user semantics and ordinary retention. Do not promise native
system-message priority, special compaction persistence or transparent equivalence.
No prompt text can change credentials, tool grants or authorization. Unsupported
HA options and tool APIs fail explicitly before submission.

Prompt quality and cache efficiency belong to the input producer. Do not rewrite,
split, reorder, freeze or optimize HA prompts to manufacture cache reuse. Any
cache assessment is observational and optional. Actual backend measurements keep
their separate target/workload authorization and do not block release readiness.

Re-plan HA-05–08, HA-10–11, VERIFY-01 and RELEASE-01 as unfinished adapter work
under this supported contract. Retire `prompt_public_path_verified` rather than
marking a native capability implemented. Existing setup/configuration/packaging
guards remain closed until adapter implementation and independent verification;
this plan correction is not a runtime guard removal or an installation.
Deployment, HA restarts, model calls and device actions retain explicit operational
scope. Existing accepted outbound implementation/reviews remain unchanged.

The current plan and ledger are the scheduling authority. The previous plan/state
are archived for historical candidate verification. SCOPE-01 requires independent
APPROVE before the revised dependent gateway is dispatched; this is the ordinary
delivery-review protocol, not a replacement prompt/caching or user-choice gate.
