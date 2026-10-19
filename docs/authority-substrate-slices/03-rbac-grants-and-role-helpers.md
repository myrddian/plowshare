# Slice 03: Scoped RBAC grants and standard role helpers

Status: implemented in this branch, 17 October 2026, following explicit
authorization for slice 03. No new public APIs or later slices are included.

Part of the [14-slice roadmap](../authority-substrate-implementation-plan.md).
Policy comes from [ADR 0030](../decisions/0030-authority-substrate-and-explicit-scopes.md);
the [impact report](../authority-substrate-impact-report.md) separates verified
source evidence from target behavior. The shared roadmap records source baseline,
test policy and cross-slice activation rules.

## Outcome and dependencies

Make ADMIN, MANAGER, CONTRIBUTOR and READER useful scoped helpers over the common substrate, with durable issuance/audit and the agent ceilings. Routine administration uses roles rather than custom ACL assembly.

**Depends on:** [02 — Explicit identities, scopes and locked built-ins](02-identities-and-scopes.md).

Dependency completion means its relevant interfaces, migrations and verification
are available; it does not grant authority to a caller or enable an unmigrated path.

## Implemented changes

- Represent scoped role bindings, issuer/provenance, permitted descendant coverage, version/expiry and revocation with typed repository contracts. Reuse existing project membership evidence and preserve narrower Application/token grants.
- Map current VIEWER/CONTRIBUTOR/MANAGER to the adopted helpers. Review legacy server-admin-as-MANAGER access explicitly; install authorized replacement control/data grants without silently widening access or disabling unrelated workflows.
- Provide the resource MANAGER/DRI's access-management authority, including GLOBAL_READ audience choice. ADMIN is scoped control, with Global's declared READ/WRITE/control helper; execution/private data is not generally implied.
- Enforce default agent maximum CONTRIBUTOR, explicit human-forced scoped MANAGER, rejection of ADMIN/SYSTEM assignment and no exception propagation to children. Bootstrap root authority as policy, not UUID allow branches.

## Reuse and likely source owners

These files identify current owning contracts, not a claim that they already
implement the proposed behavior. Keep services on typed interfaces and persistence
inside specialist repositories.

| Existing source | Reuse and boundary |
| --- | --- |
| [ProjectRole.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/ProjectRole.java) | Retain existing wire-role compatibility; do not append SYSTEM/GLOBAL_READ to the ordinal enum. |
| [JdbcProjectMembers.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/JdbcProjectMembers.java) | Current locked membership mutations, grant history and last-manager behavior. |
| [ApplicationProjectMembers.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/ApplicationProjectMembers.java) | Effective manifest/token ceilings remain part of every project decision. |
| [ProjectAuthorization.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/ProjectAuthorization.java) | Existing management/data classifications need explicit compatibility mapping, not wholesale new route APIs. |

## Exclusions and capability boundaries

No new role editor, arbitrary deny precedence, nested-group engine, complex ACL UI, token mass reissue or new grant-management API. Resource-level finer grants share the substrate where supported.

Standard scoped RBAC helpers remain the normal management path. Finer ACL needs
must use the same substrate; this slice does not silently add an ACL editor,
deny engine, SDK feature parity or an unsupported operation. Record a missing
capability as a gap before expanding implementation scope.

## Verification and completion

- Mock role matrices prove resource isolation, default helper behavior, issuer ceilings, live narrowing and no ADMIN data/execution shortcut.
- Agent tests prove descoping, forced-MANAGER auditing, rejection without partial effects and inability to reconstruct prohibited ADMIN via finer grants.
- Selected PostgreSQL tests prove atomic grant mutation/audit, concurrent last-manager protection and revocation/version behavior.

Existing regression owners to inspect and extend, selecting methods/tags that
match the eventual change:

- [ApplicationProjectMembersTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/archive/ApplicationProjectMembersTest.java)
- [ProjectMembersTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/archive/ProjectMembersTest.java)
- [ProjectMembershipTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/ws/ProjectMembershipTest.java)

**PostgreSQL justification:** Required only for new/changed grant persistence, constraints and locking. Keep service/agent policy tests mocked; select the smallest tagged persistence tests.

Verification includes mocked role/agent matrices, exact issuing-grant and
Application lineage limits, plus selected PostgreSQL migration, rollback,
revocation, expiry, concurrent version/last-manager and audit-failure cases.
Existing service-token, information-processing, Personal and membership
regressions are selected for their changed SQL/persistence boundaries. See the
verification record below for actual commands and results; no full database
suite or live deployment is implied.

## Compatibility, issuing authority and transactions

The [grant specification](03-rbac-grant-specification.md) was written before
changing enforcement. It defines the compatibility table, fixed role UUIDs,
exercise versus delegation ceilings, permitted scope coverage, human exception
authority and transaction/recovery rules.

[`V156__scoped_rbac_grants.sql`](../../plowshare-server/src/main/resources/db/migration/V156__scoped_rbac_grants.sql)
adds immutable version-1 role action policy, durable scoped bindings and audit.
`ScopedGrantRepository` owns persistence; `ScopedRoleGrants` is the internal
management contract. `AuthorizedRoleGrants` checks the specific issuing grant,
and `LiveScopedAuthority` resolves the target and checks live lineage. Unknown
scopes, disabled/expired/revoked authority and unsupported roles fail closed.
The Application-aware membership adapter also checks each MANAGER issuer's live
manifest limits, preserving the token owner's narrower effective grant.

Existing membership APIs retain VIEWER/CONTRIBUTOR/MANAGER wire values and their
locked mutations/history. VIEWER maps to internal READER. New administrators
receive explicit control grants; migration snapshots previously implicit data
access into per-project compatibility grants. Administrative revocation retires
these snapshots rather than reviving them on later promotion. Existing
`project.access` can show management metadata with `permissions:["manage"]`;
its legacy MANAGER display value supplies no data role. Personal/client data
boundaries stay distinct. Direct client/session scope migration remains deferred.

Agent grants are internal, explicit named recipients under an active ordinary
account, separate from direct account bindings. Their result reports requested
and effective role; force is audited and requires a live human issuer. No public
force field/API is added. Grant issuance does not start agents; runtime admission,
SYSTEM operation sandboxes and child retention remain slices 06/08/11. SYSTEM is
an intrinsic root-policy binding, not an agent assignment. GLOBAL_READ audience,
resource permissions and inference enforcement remain their later slices.

PostgreSQL fixture resets now preserve the exact migration-owned builtin rows
and root binding. The test-only `AuthorityFixtures` helper replaces destructive
CASCADE resets; it is not a production recovery/bootstrap capability.

## Rollout and rollback

Preserve role/grant history and explicit mappings. Never return to an older authorization path that would ignore narrowing or turn control-only ADMIN into implicit data access.

## Verification record

Verification uses synthetic fixtures and mocks, not a deployed system. PostgreSQL
was selected for V156/Flyway rollback, live SQL admission, immutable audit,
credential binding, scope deletion and actual concurrent persistence.

- `ScopedGrantPersistenceTest`: **16 passed**, including role/Global ADMIN action
  matrices, agent descoping/force/rejection, exact issuing authority, token and
  ancestor revocation, expiry, bounded chains, migration collision/interruption,
  audit rollback, concurrent last-manager/version protection, restart reads and
  known-ID reconciliation without duplicate issuance.
- `ProjectAuthorizationTest` and `ServerProjectFramesTest`: **18 passed** together
  in the final selected persistence run. Existing role, metadata, SQL, Personal,
  routing and provisioning boundaries are checked against the real new schema.
- Additional selected persistence owners: `IdentityMigrationTest` (9),
  `ProjectMembersTest` (5), `ServiceAccountsTest` (6), `PersonalSpacesTest` (7),
  `ServiceInformationProcessingTest` (11). These passed in focused runs, bringing
  distinct selected PostgreSQL cases to **72** across the verification stages.
  Two additional fixture cases passed: concurrent first-admin setup (including
  issuance of its two control bindings) and the existing shared schedule registry's
  owner invariant. They bring the distinct selected PostgreSQL total to **74**;
  no scheduling implementation changed.
  Slice 02's migration tests explicitly target V155; V156 has its own migration
  and interrupted-bootstrap proof. The full database suite was not run.
- Mock policy/lineage, Application, membership transport, information composition
  and reserved-identity cases run in the default test suite. They do not prove
  live adapter or deployed authorization behavior.

Focused verification can be rerun with:

```sh
./gradlew :plowshare-server:test -PfullDb \
  --tests '*ScopedGrantPersistenceTest' \
  --tests '*ProjectAuthorizationTest' \
  --tests '*ServerProjectFramesTest'
./gradlew :plowshare-server:test -PfullDb \
  --tests '*IdentityMigrationTest' \
  --tests 'io.aeyer.plowshare.server.archive.ProjectMembersTest' \
  --tests '*ServiceAccountsTest' \
  --tests '*PersonalSpacesTest' \
  --tests '*ServiceInformationProcessingTest'
```

`./gradlew format` and `./gradlew formatCheck` passed. The final repository-wide
`./gradlew formatCheck check` passed with **5,742 default server tests**, zero
failures/errors/skips, and the client, Javadoc and boundary checks included by
`check`. No PostgreSQL fixture runs in that default check.
An unchanged MCP deadline assertion failed on the first check and passed all
100 integration tests in isolation. An overlapping intermediate Gradle run lost
XML reports and is excluded as handoff evidence; final checks run sequentially.
No migration was applied to a deployment and no public SDK/agent-force capability
was added. Live deployment sizing/query performance was not benchmarked.
