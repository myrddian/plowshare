# HA-FIX-02 v2 independent review

Verdict: **APPROVE**.

Implementer: `/root`.
Reviewer: `/root/ha_deployment_reviewer`.
Candidate: `docs/reviews/home-assistant-ha-fix-02-v2-candidate.json`.
Candidate SHA-256:
`507b83649e387b25440cf7a1f7c5870b4292807863a5cf15f5d2f9a16dffd1ed`.

I read the actual adapter/test diff, documentation, implementation report,
retained failed read trajectory, schema transport/validation owners, actual
focused/full check logs, fresh installed-consumer report and conditional rollout.
I verified all four source/report hashes, five private evidence hashes, frozen
ledger/diff, base identity and both accepted dependency manifest identities.
Implementer and reviewer are independent. I authored no fix or tests, changed no
ledger or target and issued no live/model/device call. This review is my only
source-fix output.

## Findings and scope

No blocking implementation finding remains. The earlier v1 manifest lacked the
graph's complete frozen provenance; v2 supplies it without changing source or
check evidence. v1 remains superseded and was not accepted. Wording now separates
the observed model-emitted strings from an unproven backend/template cause.

The live retained search returned a revision object, while subsequent model calls
encoded revision as a string. Core preserves rich schemas through
`RelayToolDefinition.schema`, `ToolSchema.asDeclared` and `OpenAiTransport`;
shared `ToolSchemas.validate` correctly refused the strings before invocation.
The change adds no core conversion, fallback or new public SDK capability.

The adapter replaces reference-only revision positions with the same explicit
closed object in describe, read, list_actions and search-cursor schemas. All six
required fields, string/counter bounds, additional-property refusal and typed
decoding remain intact. There is no string-to-object coercion, revision-freshness
or ownership bypass, new tool grant, changed read handler or control path. The
short object-description hint describes the actual contract. Inlining exposes
that contract without relying on model-side reference rendering; it is not a
claim that every model will now call it correctly.

The regression serializes every declaration and checks explicit nested object
types without references/definitions, accepts the typed revision at all four
positions and rejects its JSON-encoded string. It confirms validation itself
performs no discovery/read calls. Existing boundary, foreign revision, counter,
attribute projection and output-bound tests remain enabled. New Java types are
imported; no fully qualified declaration or architectural responsibility change
was introduced.

## Verification and artifact identity

Actual focused checks passed six tests. The final formatter/formatCheck, default
repository check and refreshed HA installDist passed in 55 seconds across 165
tasks. Current HA Java results contain 185 tests, zero failures/errors/skips. The
fresh installed consumer passed eight checks against all 56 files, with unchanged
source distribution/topology. No real database tests were required or run.
All 4,042 protected core/protocol/SDK/shared-runtime source hashes still match.

I verified all 56 new distribution archive members and hashes against the fresh
installed report. Compared with the preserved previous distribution, only the HA
integration JAR and provider guide change. Other runtime JARs, launcher, fixed
68-entity configuration and secret-loading launcher remain identical. The source
candidate binds the conditional rollout manifest; that rollout has a separate
review rather than being treated as completed deployment.

The changed declaration changes its catalogue fingerprint. A new journal owner
must not erase or reuse the old owner's uncertainty. The documented rollout first
requires the original job terminal and original provider gracefully withdrawn
with settled receipts, preserves its code/config/container/journal, and creates a
separate generation only after those guards pass.

This approval closes the adapter source/package fix only. It establishes neither
a successful replacement nor an actual sensor value, voice or device result.
