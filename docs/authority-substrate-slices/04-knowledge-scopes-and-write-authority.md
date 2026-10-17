# Slice 04: Knowledge scope, creator provenance and WRITE authority

Status: implemented in this branch, 17 October 2026, following explicit
authorization for slice 04. No new public APIs, later slices or deployment are included.

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

## Implemented changes

- Persist explicit knowledge home/scope independently of created_by, revision author and executor. Recover provenance from actual evidence; do not rename owner_handle blindly or infer authorship from the current home.
- Replace ordinary exact-owner mutation entry points with scoped READ/WRITE/control checks and protected repository contracts. Contributor task work can modify permitted area resources without gaining access-management rights.
- Adapt existing supported create/update/import/move/retire paths only. A home/control transfer needs applicable source/destination authority; audience changes remain separate.
- Classify document versus conversation/session/credential/receipt ownership. Preserve specialized exact-subject/private-record rules and existing input/reference/lifecycle gates.

V154 adds home scope UUIDs to resources/memories and evidence-labelled, immutable
creator UUIDs to resources/revisions. Existing project/account keys, revision
authorship, document attribution, formation/source history and receipt ownership
remain separate. Unclassified document homes remain unavailable for ordinary
writes; only the archive's documented Global memory tier maps to the fixed Global
knowledge scope. No Global audience or execution route is activated.

Ordinary existing create/revise/import, metadata copies, tags/groups, collection
links, processing requests, availability and report finalisation use current
home authority. Pending sources use scoped READ. Non-creator project
CONTRIBUTORs can work in their area; changing a revision's audience requires
MANAGER and still obeys its current input/publication restrictions. Java retains
Application-aware ceilings; SQL locks and rechecks current stored/token grants,
target home, mandatory policy and retained dependencies. Collection links require
source and destination WRITE and never move the resource's home.

## Reuse and source owners

The [knowledge-write specification](04-knowledge-write-specification.md) was
written before enforcement changes. It inventories converted owner predicates,
retained domain restrictions and unknown creator provenance. Services continue
to use typed contracts; specialist repositories own the SQL.

| Existing source | Reuse and boundary |
| --- | --- |
| [InformationCatalogue.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationCatalogue.java) | Existing admission and ordinary mutations now use requireWrite; share/unshare require scoped audience control. |
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

Regression owners extended or run for the changed boundaries:

- [InformationContentAdmissionTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/information/InformationContentAdmissionTest.java)
- [PortableInformationTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/information/PortableInformationTest.java)
- [AuthoredInformationPersistenceTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/information/AuthoredInformationPersistenceTest.java)
- [MemoryStoreTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/archive/MemoryStoreTest.java)

**PostgreSQL justification:** Required for changed scope/provenance row mapping, mutation SQL and transaction predicates. Test business authorization with mocks separately.

Verification completed against local PostgreSQL/Testcontainers, selected because
this change requires real constraints, row mappings, transaction rollback and
locking. **103 distinct selected PostgreSQL cases passed** across the stages:

| Test owner | Passed cases |
| --- | ---: |
| KnowledgeScopePersistenceTest | 14 |
| AuthoredInformationPersistenceTest | 8 |
| MemoryStoreTest | 39 |
| DocumentNavigationPersistenceTest | 16 |
| ServiceInformationProcessingTest | 18 |
| Selected DocumentPoliciesTest methods | 6 |
| Selected InformationFacetsTest methods | 2 |

These cover creator evidence/conflicts/unknowns across V153→V154 and restart,
non-creator writes and immutable provenance, wrong home/Reader refusal, mandatory
ownerOnly/private inputs/reference obligations, stable scope through project
rename, head conflicts, rollback, revocation fencing, service-token
ceilings, old owner-based audience-writer refusal, archive states and embeddings,
legacy assignment, idempotent receipts and denied publication. A separate
concurrent import/metadata test verifies policy-before-contract lock order. No
full database suite or live deployment test was run.

Mocked `KnowledgeWriteAuthorizationTest`, `InformationContentAdmissionTest`,
`PortableInformationTest`, `InformationAccessTest` and `ScopedPermissionsTest`
cover business admission and its effective-role boundary, including non-creator
import, a permission change after prepare, and refusal before mutation. Mocks do
not prove SQL enforcement or a live integration.

`./gradlew format formatCheck check` passed: **5,748 default server tests**,
zero failures/errors/skips, plus the client, Javadoc and repository-boundary checks
included by `check`. No PostgreSQL fixtures run in that default check. Flyway
migration versions are unique. Earlier fixture failures were corrected before
these final passing runs; no shipped migration or unrelated runtime was edited.

## Explicit gaps retained

No general document home/control-transfer operation exists. No move endpoint,
Global selector, ACL editor, arbitrary Personal sharing or type-registry rehome
operation was added. Existing memory admission/curation remains its own boundary;
storage has no authenticated-principal argument and promotion creates a separate
record. Worker execution still uses its current authority until its later slice.

Current root-document audience conversion, GLOBAL_READ, navigation-policy
administration conversion, SYSTEM processing and runtime/client cutover remain
later work. Wire owner/author fields retain their existing meaning; the new
created_by evidence is internal, with no new public field/API. Content-type
registry namespace renames and legacy namespace/name collisions after project
rename remain explicit compatibility gaps; no identity merging or immutable
contract rewrite was introduced.

## Rollout and rollback

The additive migration retains legacy keys and provenance. Home/creator rewrites
are refused; admitted records require matching explicit homes and live scope
permissions. Audience writes carry an exact actor/revision transaction-local
admission fence, so pre-cutover creator-based share/unshare writers are refused.
A rollback must retain enforcement parity; downgrading to owner-based binaries is
not a supported rollback. Preserve the schema and reconcile deployment versions
rather than removing constraints or silently reviving old writer assumptions.
No production migration or rollback was executed.
