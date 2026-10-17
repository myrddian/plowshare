# Slice 14: Legacy classification, cutover and integrated migration proof

Status: proposed scope, 17 October 2026. Planning only; no implementation,
new public capability or deployment is authorized by this document.

Part of the [14-slice roadmap](../authority-substrate-implementation-plan.md).
Policy comes from [ADR 0030](../decisions/0030-authority-substrate-and-explicit-scopes.md);
the [impact report](../authority-substrate-impact-report.md) separates verified
source evidence from target behavior. The shared roadmap records source baseline,
test policy and cross-slice activation rules.

## Outcome and dependencies

Finish the core migration with reviewed data classification, compatible enforcement and one supported end-to-end Global knowledge path, preserving durable identities and private data.

**Depends on:** [13 — Existing API, scope-selection and client contract cutover](13-existing-contract-and-client-cutover.md).

Dependency completion means its relevant interfaces, migrations and verification
are available; it does not grant authority to a caller or enable an unmigrated path.

## Changes

- Consolidate per-slice migration evidence and classify remaining null-Global database/job/log/disk meanings. Preserve restricted unknowns; deployment files and private SYSTEM logs never become public knowledge merely by directory/home.
- Review legacy Shared audience expansion, manual home/control adoption and old admin/role mappings. Apply new constraints/remove obsolete compatibility paths only after classification and supported-consumer proof.
- Audit existing memory recall/navigation, digest scopes and curation promotion for hidden broadening. Keep assessment/proposals; refuse unauthorized existing release/transfer without inventing a new curation mode or approval UI.
- Rehearse the supported Global ADMIN admission, bounded SYSTEM preparation, configured operation budget and ordinary authenticated reader path. Validate current root permissions, creator-independent continuation and private result/log boundaries.

## Reuse and likely source owners

These files identify current owning contracts, not a claim that they already
implement the proposed behavior. Keep services on typed interfaces and persistence
inside specialist repositories.

| Existing source | Reuse and boundary |
| --- | --- |
| [DataLayout.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/data/DataLayout.java) | Existing physical global directory contains mixed public/private/configuration data; no wholesale rename/publication. |
| [Archive.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/Archive.java) | Current recall/curation paths need intentional selection and release authority. |
| [Digests.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/digests/Digests.java) | Existing durable-scope mismatch cannot be reconstructed from a vanished closure. |
| [Curator.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/curator/Curator.java) | Existing proposal/settlement receipts remain authoritative. |
| [install-manual.mjs](../../scripts/install-manual.mjs) | Existing installer/public operation path; no SQL import or Global impersonation workaround. |

## Exclusions and capability boundaries

No production deployment by implication, token replacement campaign, broad disk rename, tenant rollout, new curation/type features, arbitrary ACL engine or new controls/SDK parity.

Standard scoped RBAC helpers remain the normal management path. Finer ACL needs
must use the same substrate; this slice does not silently add an ACL editor,
deny engine, SDK feature parity or an unsupported operation. Record a missing
capability as a gap before expanding implementation scope.

## Verification and completion

- Use synthetic legacy fixtures and a protected migration rehearsal to prove classification completeness, stable IDs, creator provenance, FK constraints, retained jobs/receipts and unchanged historical costs.
- Selected PostgreSQL tests prove schema/policy/cutover transactions. Rehearse restart and any changed disk layout against retained state; health alone is insufficient.
- Through verified existing public APIs, demonstrate Global admin write, SYSTEM operation bounded to inputs/outputs, continuation after creator access removal, and authenticated human/service read without private-source/log leakage.
- Remove/expire authority or invalidate the document/operation: new effects/disclosure refuse and uncertain effects are only reconciled. Do not demonstrate absent interfaces by building them.

Existing regression owners to inspect and extend, selecting methods/tags that
match the eventual change:

- [DataLayoutTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/data/DataLayoutTest.java)
- [DocumentPoliciesTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/information/DocumentPoliciesTest.java)
- [ServiceInformationProcessingTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/information/ServiceInformationProcessingTest.java)
- [AuthoredInformationPersistenceTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/information/AuthoredInformationPersistenceTest.java)
- [MemoryStoreTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/archive/MemoryStoreTest.java)

**PostgreSQL justification:** Required for final migration/constraint and real policy SQL proof. Select the relevant tagged tests and a controlled fixture/rehearsal; whole-suite DB or live deployment needs its own breadth justification/authorization.

These are future checks, not tests run for this planning document. Add mocked
boundary/authorization tests for new behavior; existing suite links do not imply
every test is non-database. Follow the shared roadmap's focused-test, formatting
and check requirements. Completion requires the outcome and negative/recovery
cases above, with unsupported paths still explicit and no uncertain-effect replay.

## Specification required before implementation

All predecessor slices need evidence for their affected paths; no final rehearsal substitutes for their authorization tests. Unresolved classification/unsupported critical routes block cutover instead of receiving permissive defaults.

## Rollout and rollback

Specify tested reader/writer compatibility and safe refusal before removing adapters. Preserve new grants, receipts, policy/provenance and costs; never restore a binary/state combination that broadens access. This plan does not authorize backup restoration or deployment.
