# Slice 02: Explicit identities, scopes and locked built-ins

Status: proposed scope, 17 October 2026. Planning only; no implementation,
new public capability or deployment is authorized by this document.

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

## Changes

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

These are future checks, not tests run for this planning document. Add mocked
boundary/authorization tests for new behavior; existing suite links do not imply
every test is non-database. Follow the shared roadmap's focused-test, formatting
and check requirements. Completion requires the outcome and negative/recovery
cases above, with unsupported paths still explicit and no uncertain-effect replay.

## Specification required before implementation

Finalize the reserved registry, durable mapping and built-in lifecycle/recovery rules. A compatibility reader must refuse records it cannot interpret.

## Rollout and rollback

Keep additive records and their mappings. Only a version that enforces their locked lifecycle may access newly admitted records; do not delete identities or let an older binary reinterpret them as users.

