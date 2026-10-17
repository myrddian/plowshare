# Slice 05: Current root-document policy and GLOBAL_READ audience

Status: implemented in this branch, 17 October 2026, following explicit
authorization for slice 05. No new public APIs, later slices or deployment are included.

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

## Implemented changes

- Store/version the authoritative root-document policy and reconcile per-revision Shared flags deliberately. Current policy governs new, historical and pinned revisions; content writes cannot silently change it.
- Map resource MANAGER/DRI audience authority to existing supported permission/share commands, including GLOBAL_READ without extra ADMIN approval. Global-account management retains human Global ADMIN control.
- Coordinate Java admission, SQL filtering, counts/paging, references, inputs, events and supported exports. Allow general-read knowledge only when its policy authorizes the audience, within credential/task ceilings.
- Reconcile ownerOnly/mandatory policy explicitly. Keep private dependencies and processing logs private; refuse unauthorized existing Global-promotion effects while retaining curation proposals/receipts.

## Reuse and source owners

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

The implementation uses mocked admission, promotion and transport tests plus the
selected PostgreSQL policy/consumer suites. Database coverage is limited to the
policy SQL, recursive dependencies, revision mappings, immutable audit, event
ordering and concurrent transactions named above. Completion evidence is recorded
below; mocked checks do not prove live deployment behavior.

## Specification and implemented boundary

The [root-policy specification](05-root-policy-specification.md) was written before
enforcement changes. V155 adds root-policy versions and immutable history without
rewriting shipped migrations or inferring authorship from home. Consistent legacy
Shared stays human-only LEGACY_SHARED until explicit Manager review through the
existing share command; mixed histories narrow to HOME for review. Conflicting
private subjects and unclassified/quarantined homes remain unavailable.

Existing share/unshare changes the root under source Manager authority. Every
revision uses that policy, including pending sources, history, pinned references,
counts/facets, portable reads and events. Content projection cannot reset the root.
Mandatory ownerOnly uses an explicit reconciled account UUID, independently of
created_by; private inputs and retained reference obligations still constrain reads.
Processing logs and diagnostic event details retain private custodian boundaries.

Shared/includeShared now admit GLOBAL_READ to active authenticated humans,
Applications and service credentials. The gateway delegates only a closed list of
existing information read operations to their typed handlers. Project data roles,
Application/token ceilings, mutation gates and private receipt checks remain.
Human Global Admin control uses a live exact scoped ADMIN binding; project Manager
is not destination publication authority. Model recommendations file proposals;
existing acceptance requires authenticated source Manager and human Global Admin
before the queue claims the existing copy-and-retire action. The submitted `by`
label is provenance. Queue claims, rejection and recovery behavior are reused.

RBAC helpers remain the ordinary management path; no ACL editor or security-group
directory was added. Tag groups are facets, not security groups. Existing legacy
assignment still uses its configured migration operator and audited transaction;
unknown creator provenance stays unknown. Global creation/move/type selectors,
independent SYSTEM processing, retained runtime/acting authority and client scope
cutover remain deferred to their documented slices.

## Verification results

- `./gradlew format` and `./gradlew formatCheck check` passed. The default server
  suite passed 5,752 tests, including mocked role/admission, curation and HTTP/WS
  provenance tests. Repository-wide Java/Javadoc, formatting and client checks passed.
- 131 selected PostgreSQL checks were verified across the policy and consumer
  suites, with fixture failures corrected and rerun. Selection covered
  RootDocumentPolicyPersistenceTest, RootDocumentPolicyMigrationTest,
  DocumentNavigationPersistenceTest, DocumentPoliciesTest,
  KnowledgeScopePersistenceTest, InformationFacetsTest, InformationCollectionsTest,
  InformationEventConcurrencyTest, AuthoredInformationPersistenceTest and
  ServiceInformationProcessingTest. The full database suite was not run.
- PostgreSQL evidence includes V154-to-V155 reconciliation/restart, legacy flags
  remaining evidence, current-root historical/pinned reads, universal principal
  eligibility/revocation, private inputs/references/logs, counts/facets/event
  filtering, policy audit rollback and concurrent audience/read transactions.
- Stale fixtures were corrected for immutable homes/revisions, bounded tokenizers
  and embedding readiness/fingerprints. Production retrieval and privacy checks
  were not weakened. The default check includes portable-export refusal coverage;
  this slice adds no export format or live deployment claim.

No live deployment, new public operation, independent SYSTEM processing, new Global
creation/move selector or later-slice runtime behavior was implemented.

## Rollout and rollback

Preserve root-policy versions and reviewed audience mappings. Never union historical audiences or roll back to a path that reads an old permissive revision.
