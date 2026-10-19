# Slice 02: Explicit identities, scopes and locked built-ins

Status: implemented in this branch, 17 October 2026, following explicit
authorization for slice 02 only. No new public APIs or later slices are included.

Part of the [14-slice roadmap](../authority-substrate-implementation-plan.md).
Policy comes from [ADR 0030](../decisions/0030-authority-substrate-and-explicit-scopes.md);
the [impact report](../authority-substrate-impact-report.md) separates verified
source evidence from target behavior. The shared roadmap records source baseline,
test policy and cross-slice activation rules.

## Outcome and dependencies

Introduce explicit durable scope/account/role identities alongside existing keys, with locked SYSTEM and Global records. No existing null row is automatically reclassified or made readable.

**Depends on:** [01 — Project READ/WRITE foundation](../authority-substrate-first-slice-plan.md).

Dependency completion means its relevant interfaces, migrations and verification
are available; it does not grant authority to a caller or enable an unmigrated path.

## Implemented changes

- Define typed project, Personal, account-knowledge and administration references. Keep omission as transport absence; an unresolved persisted target cannot become unrestricted scope.
- Add reviewed reserved UUID records and existing-key mappings through new Flyway migrations. Use the ADR's reserved registry; assign unresolved IDs before coding, never generate replacements at startup.
- Bootstrap idempotently and protect SYSTEM/Global from login, ordinary credentials, acting-subject selection, generic rename/delete and accidental Personal provisioning. SYSTEM's role remains intrinsic and non-assignable.
- Keep new identity records additive until consumers migrate. Record ambiguous legacy classifications as restricted; retain original IDs, account handles, references and provenance.

## Reuse and likely source owners

These files identify current owning contracts, not a claim that they already
implement the proposed behavior. Keep services on typed interfaces and persistence
inside specialist repositories.

| Existing source | Reuse and boundary |
| --- | --- |
| [AdminStore.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/auth/AdminStore.java) | Current account administration and credential boundary; extend lifecycle policy rather than manufacture a human login. |
| [JdbcPersonalSpaceRepository.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/personal/JdbcPersonalSpaceRepository.java) | Existing Personal provisioning must not run for locked built-ins. |
| [JdbcResourceScopeRepository.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/JdbcResourceScopeRepository.java) | Existing resource-to-project lookup provides migration evidence, not proof that null means Global knowledge. |
| [Home.java](../../plowshare-protocol/src/main/java/io/aeyer/plowshare/protocol/Home.java) | Current project/null contract needs a typed compatibility boundary, not a blind UUID substitution. |

## Exclusions and capability boundaries

No Global audience activation, root execution, universal resource-owner rewrite, new account-management API or first-class tenant model.

Standard scoped RBAC helpers remain the normal management path. Finer ACL needs
must use the same substrate; this slice does not silently add an ACL editor,
deny engine, SDK feature parity or an unsupported operation. Record a missing
capability as a gap before expanding implementation scope.

## Verification and completion

- Mock lifecycle tests refuse credentials/acting/Personal provisioning for built-ins and do not treat a reserved name/UUID as an allow.
- Selected PostgreSQL tests prove migration FK/uniqueness constraints, repeatable bootstrap, collision refusal and unchanged old IDs. Unknown legacy rows remain restricted.
- Restart/bootstrap rehearsal preserves the same reserved identities; interrupted migration resumes without creating replacements.

Existing regression owners to inspect and extend, selecting methods/tags that
match the eventual change:

- [PersonalSpacesTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/personal/PersonalSpacesTest.java)
- [ServiceAccountsTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/auth/ServiceAccountsTest.java)

**PostgreSQL justification:** Required for the new identity/scope tables, constraints and bootstrap persistence. Add focused tagged migration tests and inspect the existing test tags; do not run the whole database suite.

### Implementation and verification record

[`V155__explicit_authority_identities.sql`](../../plowshare-server/src/main/resources/db/migration/V155__explicit_authority_identities.sql) adds `authority_accounts`,
`authority_roles` and `authority_scopes`. `IdentityRepository` is the read-only
typed compatibility boundary, implemented by `JdbcIdentityRepository`;
`IdentityBootstrap` validates the migrated built-ins at startup. Runtime startup
does not create IDs or repair missing mappings. The existing permission adapter
continues to use effective project roles; no reserved identity grants access.

Ordinary account and project mappings are created in the source insertion's
transaction. Existing project metadata changes refresh their compatibility
classification while preserving the UUID and source key. They do not introduce
a new scope transfer operation. Direct scope reassignment and builtin mutation
are refused. The established account administration, credential, acting and
Personal provisioning boundaries now also refuse built-in aliases/reserved IDs.
First-human setup and seeding exclude built-in account rows.

Verification performed:

- **104 default focused tests passed**, including mocked lifecycle refusals for
  both aliases, all reserved UUIDs, credential/bootstrap/Personal/acting paths,
  missing-registry refusal and absence of a reserved-ID permission bypass, plus
  existing token, acting and slice 01 admission/role regressions.
- **23 focused PostgreSQL regression cases passed** across the migration,
  PersonalSpacesTest, ServiceAccountsTest and the two Personal-ownership methods
  of ProjectAuthorizationTest. Those tests exercise actual SQL/account lifecycle
  compatibility, not only constructor compilation.
- The final expanded **IdentityMigrationTest passed 9 PostgreSQL cases**:
  retained handles/numeric/resource/token IDs, typed Personal/knowledge separation,
  project rename/metadata continuity, fixed registry/bootstrap/restart validation,
  FK/uniqueness/mutation guards, reserved collisions with retry, and simulated
  interruption late in bootstrap with full Flyway rollback and successful retry.
- PostgreSQL ran only through the selected `-PfullDb --tests` commands, because
  the slice changes persistence and database-enforced lifecycle invariants.
  The disposable fixture explicitly disables TLS/GSS encryption and installs
  vector in its shared test schema; no production connection defaults changed.
- `format`, `formatCheck` and the full repository `check` passed. The final
  default server suite passed **5,720 tests**, with no failures or skips; client,
  Javadoc and repository boundary checks also passed. Documentation links,
  Flyway version uniqueness and diff whitespace were checked.

These checks do not prove a live deployment, a real process-kill recovery exercise,
new grant evaluation, a Global resource migration or independent SYSTEM execution.
Slice 03 and later remain unimplemented.

Reproduce the persistence and repository checks with:

```sh
./gradlew :plowshare-server:test -PfullDb \
  --tests 'io.aeyer.plowshare.server.access.IdentityMigrationTest' \
  --tests 'io.aeyer.plowshare.server.personal.PersonalSpacesTest' \
  --tests 'io.aeyer.plowshare.server.auth.ServiceAccountsTest' \
  --tests 'io.aeyer.plowshare.server.access.ProjectAuthorizationTest.personal_spaces_are_owner_only_even_for_server_administrators' \
  --tests 'io.aeyer.plowshare.server.access.ProjectAuthorizationTest.sql_information_admission_does_not_grant_admins_other_personal_spaces'
./gradlew format
./gradlew formatCheck check
```

## Reserved registry and compatibility specification

The reserved registry is finalized in ADR 0030 before implementation: account 1,
intrinsic SYSTEM role 2, administration scope 3, Global account 4, Global knowledge
scope 5 and GLOBAL_READ role 6 (the common UUID prefix is
`00000000-0000-0000-0000-`). SYSTEM and GLOBAL are locked, non-interactive
`BUILTIN` rows in the existing account store, mapped to those account UUIDs.
Their password fields contain an unusable sentinel, they remain disabled for
ordinary authentication, and they never participate in first-human setup counts.
Existing accounts with a case-insensitive SYSTEM/GLOBAL handle or one of the
reserved UUID strings stop migration for explicit collision resolution; they
are never adopted, renamed or silently converted. Ordinary projects named global
remain ordinary projects.

Add separate identity, role and scope catalogues alongside existing handles and
numeric project keys. Existing and newly inserted ordinary records receive a
durable UUID once, in the database transaction creating their mapping. Project
renames retain the scope UUID. Personal mappings retain both the original project
key and account UUID. USER/SERVICE account-knowledge scopes are separate from
Personal execution scopes; service-token principals receive an identity mapping
without a new knowledge/Personal home. This creates no grants or public selectors.

Client-session projects, malformed Personal locators and legacy Personal rows
whose owner is not a USER are recorded as RESTRICTED in the additive catalogue.
Legacy null project/resource values remain untouched and unresolved; the new
compatibility reader refuses them rather than mapping them to Global, Personal
or administration. Legacy consumers retain their existing semantics until their
own slices migrate. Scope identity lookup never authorizes an operation.

Flyway materializes the exact built-ins atomically and rejects collisions.
Restart validates their complete locked records through a repository interface;
it never generates replacement built-in identities or repairs a missing record
silently. Repeating migration bootstrap against intact records is a no-op.
Failed migration rolls back and can be retried after the collision is resolved.
Lifecycle checks and database constraints/triggers refuse builtin credentials,
acting subjects, Personal provisioning, generic mutation/deletion and SYSTEM role
reassignment. Rollback retains the additive tables and lifecycle guards; an older
binary is not a supported writer after this migration.

## Rollout and rollback

Keep additive records and their mappings. Only a version that enforces their locked lifecycle may access newly admitted records; do not delete identities or let an older binary reinterpret them as users.
