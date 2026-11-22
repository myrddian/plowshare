# OUT-PLAN-01 v2 independent review

Verdict: **APPROVE**.

Reviewer: `/root/ha_outbound_plan_reviewer`.
Implementer: `/root`.
Candidate: `docs/reviews/home-assistant-out-plan-01-v2-candidate.json`.
Candidate SHA-256: `b3d57dbd71d59a91f0a77b582f49e9ef6bb6e59d4a1980eef4a3442689fe1ac3`.
Base: `6de685f63e060f3003cb93233d2567e004269755`.

This review approves the exact documentation/ledger planning candidate for
outbound-only source dispatch. It does not approve unimplemented feature code,
satisfy prompt fidelity or authorize publication, target merge, deployment or live
operations. The previous v1 rejection and its frozen artifacts remain unchanged.

## Findings resolved

**OUTPLAN-R1 (P1): resolved.** The plan and host contract now require the read and
control providers within one Application to share its explicit execution
principal, credential environment reference and authenticated SDK connection.
That agrees with `ApplicationToolRegistry.assigned`'s same-account fence and
`ApplicationToolScopes.permits`'s single-execution-account policy. Distinct logical
provider names, nonoverlapping prefixes, exact tool scopes, separate journals and
local control admission remain explicit. Different read-only and control
Applications may still use different execution principals. No server/SDK change
or cross-account authority bypass is proposed.

The reviewer parsed the revised example and verified equal project/account/token
references, different logical providers, nonoverlapping prefixes, separate journal
paths, selected control actions and empty event subscriptions. Implementation
acceptance still requires current loader/grant isolation and invalid-principal
fixtures; syntax alone is not proof of runtime authority.

Shared-connection cleanup is now explicit: both provider owners must physically
settle before SDK/HA resources are closed. An incomplete provider cleanup retains
shared resources and journals rather than invalidating the other provider's
physical ownership. Existing provider cancellation detaches its own interest;
public SDK lifecycle and provider composition support this design without a core
extension. Runtime implementations remain subject to their own independent review.

**OUTPLAN-R2 (P3): resolved.** The contract now identifies `access.accounts` as
empty while exact `toolScopes`/`toolGrants` are nonempty policy declarations.
Placeholders/declarations do not issue live membership or effective authority;
operator provisioning and current server checks remain separate.

No unresolved blocking plan findings remain.

## Independent verification

- Recomputed candidate digest and both reviewed source hashes, plus frozen ledger
  snapshot and diff digests. All match v2.
- Rechecked 28 nodes, 55 dependency edges, valid references and acyclicity;
  implementer/reviewer identities remain independent. OUT-PLAN-01 approval precedes
  packaging/profile source dispatch; validation waits for both feature approvals
  and has nonauthor implementer/reviewer roles.
- Compared the frozen v2 ledger with committed baseline: the eleven original gated
  nodes remain unchanged, all four gate objects remain unchanged/unsatisfied, and
  original candidate revisions, reviews and evidence are preserved. No original
  node gains deployment or live-validation status.
- Original thirteen accepted deliverables now have merge evidence for PR #300,
  whose merge is an ancestor of the baseline. This reconciliation does not
  reapprove later main source under older candidate hashes.
- Confirmed actual SQL-unit deadlines and warning-only accepted workflow duration
  agree with current owners; historical reports retain their old frozen wording.
- Reviewed existing HA resource/provider and SDK APIs, including adapter maintenance,
  provider reconciliation, renewal, polling, withdrawal, catalogue availability,
  cancellation and close. The proposed host stays within public capabilities and
  keeps named provider execution separate from legacy feedback-dependent workflows.
- Checked strict typed configuration, bounded input/UTF-8/unknown/null rejection,
  explicit origins/credentials, journal separation, no automatic uncertain mutation
  replay or grants, and no implicit local/default connection.
- Confirmed outbound validation is scoped to current repository checks, actual
  local SDK/HA transport and installed-artifact fixtures, truthful loader/grant
  proof limits and mocked repositories by default. A real database test still
  needs a precise PostgreSQL-specific assertion.
- Checked local plan links, JSON example consistency and `git diff --check`.

No Gradle, PostgreSQL, model, live HA, device, publication or deployment operation
was run for this documentation review. The reviewer authored only this report and
the preceding v1 review, not the plan, ledger, contract or feature implementation.
All original prompt-dependent feature gates remain in force.
