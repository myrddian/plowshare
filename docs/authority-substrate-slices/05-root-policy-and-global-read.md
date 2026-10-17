# Slice 05: Current root-document policy and GLOBAL_READ audience

Status: proposed scope, 17 October 2026. Planning only; no implementation,
new public capability or deployment is authorized by this document.

Part of the [14-slice roadmap](../authority-substrate-implementation-plan.md).
Policy comes from [ADR 0030](../decisions/0030-authority-substrate-and-explicit-scopes.md);
the [impact report](../authority-substrate-impact-report.md) separates verified
source evidence from target behavior. The shared roadmap records source baseline,
test policy and cross-slice activation rules.

## Outcome and dependencies

Use current root-document permissions for all revision reads and existing audience-management paths. GLOBAL_READ authorizes all authenticated active principal types with no individual audience exclusions.

**Depends on:** [04 — Knowledge scope, creator provenance and WRITE authority](04-knowledge-scopes-and-write-authority.md).

Dependency completion means its relevant interfaces, migrations and verification
are available; it does not grant authority to a caller or enable an unmigrated path.

## Changes

- Store/version the authoritative root-document policy and reconcile per-revision Shared flags deliberately. Current policy governs new, historical and pinned revisions; content writes cannot silently change it.
- Map resource MANAGER/DRI audience authority to existing supported permission/share commands, including GLOBAL_READ without extra ADMIN approval. Global-account management retains human Global ADMIN control.
- Coordinate Java admission, SQL filtering, counts/paging, references, inputs, events and supported exports. Allow general-read knowledge only when its policy authorizes the audience, within credential/task ceilings.
- Reconcile ownerOnly/mandatory policy explicitly. Keep private dependencies and processing logs private; refuse unauthorized existing Global-promotion effects while retaining curation proposals/receipts.

## Reuse and likely source owners

These files identify current owning contracts, not a claim that they already
implement the proposed behavior. Keep services on typed interfaces and persistence
inside specialist repositories.

| Existing source | Reuse and boundary |
| --- | --- |
| [InformationAccess.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationAccess.java) | Selection admission is one layer; changing it alone cannot override SQL or retained-input restrictions. |
| [InformationSql.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationSql.java) | Filter before counts/paging; do not fetch private results and redact after aggregation. |
| [InformationJobs.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationJobs.java) | Retained consumed inputs and current log/result disclosure checks. |
| [JdbcInformationCatalogueRepository.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/JdbcInformationCatalogueRepository.java) | Current Shared changes preserve resource home; migrate policy without silently transferring ownership. |

## Exclusions and capability boundaries

No arbitrary group directory, deny/blocklist engine, new ACL editor, Global type/schema registry, new search product or automatic conversion of all old Shared rows.

Standard scoped RBAC helpers remain the normal management path. Finer ACL needs
must use the same substrate; this slice does not silently add an ACL editor,
deny engine, SDK feature parity or an unsupported operation. Record a missing
capability as a gap before expanding implementation scope.

## Verification and completion

- Mock audience tests separate publication state from group/project/general reading, deny Contributor permission changes and preserve manager scope.
- Selected PostgreSQL tests cover current-root policy on historical/pinned revisions, concurrent policy change/read, transitive private inputs and no disclosure through counts/references.
- Human, Application and service eligibility matches the universal audience; restricted readership removes GLOBAL_READ rather than introducing per-account denies.

Existing regression owners to inspect and extend, selecting methods/tags that
match the eventual change:

- [DocumentNavigationTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/information/DocumentNavigationTest.java)
- [DocumentPoliciesTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/information/DocumentPoliciesTest.java)
- [DocumentNavigationPersistenceTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/information/DocumentNavigationPersistenceTest.java)
- [InformationAccessTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/information/InformationAccessTest.java)

**PostgreSQL justification:** Required for policy SQL, recursive dependency predicates, policy-version transactions and revision mapping. Existing V147 is evidence only; use new migrations.

These are future checks, not tests run for this planning document. Add mocked
boundary/authorization tests for new behavior; existing suite links do not imply
every test is non-database. Follow the shared roadmap's focused-test, formatting
and check requirements. Completion requires the outcome and negative/recovery
cases above, with unsupported paths still explicit and no uncertain-effect replay.

## Specification required before implementation

Classify legacy audience inconsistencies and mandatory ownerOnly settings. Use only verified project/group mechanisms; information grouping/tag facets must not be assumed to be security groups.

## Rollout and rollback

Preserve root-policy versions and reviewed audience mappings. Never union historical audiences or roll back to a path that reads an old permissive revision.

