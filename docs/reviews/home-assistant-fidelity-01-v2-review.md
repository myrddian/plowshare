# FIDELITY-01 user-directed documentation re-review

Verdict: **APPROVE**. No findings.

- Reviewer: `/root/ha_fidelity_reviewer`.
- Implementer: `/root/ha_fidelity_implementer`.
- Candidate: `ha-fidelity-01-v2`.
- Manifest: `docs/reviews/home-assistant-fidelity-01-v2-candidate.json`.
- Manifest SHA-256: `7a0bfe2f35c1c9bae60ca78b436b14772575ff163bf27d60ef7d81e4b5928ef7`.
- Investigation report SHA-256: `aec6320d4866751a58a7142887abbd38bf76e60c7e2de56c0e2d0c1cbd607471`.
- Shared design SHA-256: `277e3a26ae576e22856582f377fa4f017956210e67e1286b66e0aa922344fbf3`.

Independently verified the manifest digest, all three bound file hashes and the frozen tracked-diff hash. Read both revised documents, including the public capability finding, preservation contract, deferred successor, user decision and cache-responsibility wording. The documents correctly record the user's direction to leave prompt-dependent features gated. The externalContext successor remains deferred and requires separate authorization; neither the documentation update nor approval authorizes a prompt-role API, prompt optimization or transformation.

The new wording places prompt quality and cache efficiency primarily with the integrator/application constructing the request. It permits reusable common patterns to motivate SDK support, understanding or an explicit correction without claiming that changing another application's prompt is transparent or safe. Future transformations require explicit semantics, compatibility evidence and opt-in. HA's original rendering remains preserved for impact assessment; it is not rewritten, reordered, frozen or demoted for cache performance.

The original role-capability finding is unchanged: task/prompt.pre is USER content, log.open is fixed, and no current public route provides refreshed native-system context for both entry points. Rechecked all 17 recorded repository/installed-HA/probe evidence hashes; they are unchanged. The independent source review and six-case helper verification from the v1 review remain applicable to those exact unchanged sources. No probe or runtime suite was rerun for this prose-only change. Helper character measurements remain distinct from actual backend cache or latency evidence, and the documented missing optional AI Task import dependency remains explicit.

Verified ADR 0077 and `docs/sdks.md` still match their accepted CORE-01 v2 hashes. Read the design's Ex summary and confirmed it remains consistent with that unchanged alias contract: authenticated account-local namespace, explicit project/source/external ID, atomic get-or-open, resolve-only Run-Ex, ordinary result shapes, existing-limit preservation and no utterance deduplication. The prompt clarification adds no alias or runtime contract change.

Local Markdown links, balanced fences and trailing-whitespace checks passed for both revised documents; `git diff --check` passed. No implementation, design fixes, ledger writes, Gradle, database, live HA, model, device or deployment operations were performed by this reviewer. Approval accepts the updated investigation/documentation scope only. `prompt_public_path_verified` remains unsatisfied and prompt-dependent deliverables remain gated.
