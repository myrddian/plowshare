# Slice 04: Knowledge scope, creator provenance and WRITE authority

Status: proposed scope, 17 October 2026. Planning only; no implementation,
new public capability or deployment is authorized by this document.

Part of the [14-slice roadmap](../authority-substrate-implementation-plan.md).
Policy comes from [ADR 0030](../decisions/0030-authority-substrate-and-explicit-scopes.md);
the [impact report](../authority-substrate-impact-report.md) separates verified
source evidence from target behavior. The shared roadmap records source baseline,
test policy and cross-slice activation rules.

## Outcome and dependencies

Admit existing document/memory mutations through scoped authority rather than creator/legacy-owner equality, while retaining mandatory data and lifecycle restrictions.

**Depends on:** [02 — Explicit identities, scopes and locked built-ins](02-identities-and-scopes.md), [03 — Scoped RBAC grants and standard role helpers](03-rbac-grants-and-role-helpers.md).

Dependency completion means its relevant interfaces, migrations and verification
are available; it does not grant authority to a caller or enable an unmigrated path.

## Changes

- Persist explicit knowledge home/scope independently of created_by, revision author and executor. Recover provenance from actual evidence; do not rename owner_handle blindly or infer authorship from the current home.
- Replace ordinary exact-owner mutation entry points with scoped READ/WRITE/control checks and protected repository contracts. Contributor task work can modify permitted area resources without gaining access-management rights.
- Adapt existing supported create/update/import/move/retire paths only. A home/control transfer needs applicable source/destination authority; audience changes remain separate.
- Classify document versus conversation/session/credential/receipt ownership. Preserve specialized exact-subject/private-record rules and existing input/reference/lifecycle gates.

## Reuse and likely source owners

These files identify current owning contracts, not a claim that they already
implement the proposed behavior. Keep services on typed interfaces and persistence
inside specialist repositories.

| Existing source | Reuse and boundary |
| --- | --- |
| [InformationCatalogue.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationCatalogue.java) | Existing admission, ordinary mutations and current requireOwner entry point define the consumer inventory. |
| [JdbcInformationCatalogueRepository.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/JdbcInformationCatalogueRepository.java) | Specialist repository owns namespace/owner predicates, locks, receipts and SQL changes. |
| [InformationWriteGates.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationWriteGates.java) | Durable prepared transitions, rechecks and request receipts remain authoritative. |
| [MemoryStore.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/MemoryStore.java) | Existing memory identity/project references need explicit home mapping without deleting source history. |

## Exclusions and capability boundaries

No ownership-as-authorship rewrite, implicit creator MANAGER, arbitrary Personal sharing, Global publication approval workflow or general curation-mode redesign.

Standard scoped RBAC helpers remain the normal management path. Finer ACL needs
must use the same substrate; this slice does not silently add an ACL editor,
deny engine, SDK feature parity or an unsupported operation. Record a missing
capability as a gap before expanding implementation scope.

## Verification and completion

- Mocks prove authorized non-creator project contributors can pass supported mutation checks, while readers and unrelated projects cannot; content WRITE never changes permissions.
- Selected PostgreSQL tests prove SQL mutations bind the admitted scope, expected revision and provenance, preserving rollback and concurrent update fences.
- Current mandatory ownerOnly/input/reference restrictions remain effective until their deliberate policy conversion in slice 05; no partial bypass is accepted.

Existing regression owners to inspect and extend, selecting methods/tags that
match the eventual change:

- [InformationContentAdmissionTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/information/InformationContentAdmissionTest.java)
- [PortableInformationTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/information/PortableInformationTest.java)
- [AuthoredInformationPersistenceTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/information/AuthoredInformationPersistenceTest.java)
- [MemoryStoreTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/archive/MemoryStoreTest.java)

**PostgreSQL justification:** Required for changed scope/provenance row mapping, mutation SQL and transaction predicates. Test business authorization with mocks separately.

These are future checks, not tests run for this planning document. Add mocked
boundary/authorization tests for new behavior; existing suite links do not imply
every test is non-database. Follow the shared roadmap's focused-test, formatting
and check requirements. Completion requires the outcome and negative/recovery
cases above, with unsupported paths still explicit and no uncertain-effect replay.

## Specification required before implementation

Inventory every affected legacy-owner predicate and classify it as ordinary knowledge authorization or a retained domain restriction. Define provenance treatment for unknown historical creators.

## Rollout and rollback

Retain old/new key mappings and provenance. Rollback must preserve authorization parity for admitted records; incompatible old writers must be refused rather than regain owner shortcuts.

