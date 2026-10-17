# Slice 03: Scoped RBAC grants and standard role helpers

Status: proposed scope, 17 October 2026. Planning only; no implementation,
new public capability or deployment is authorized by this document.

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

## Changes

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

These are future checks, not tests run for this planning document. Add mocked
boundary/authorization tests for new behavior; existing suite links do not imply
every test is non-database. Follow the shared roadmap's focused-test, formatting
and check requirements. Completion requires the outcome and negative/recovery
cases above, with unsupported paths still explicit and no uncertain-effect replay.

## Specification required before implementation

Write the current-to-target role compatibility table, exact issuing/agent-exception authority and grant transaction semantics before changing enforcement.

## Rollout and rollback

Preserve role/grant history and explicit mappings. Never return to an older authorization path that would ignore narrowing or turn control-only ADMIN into implicit data access.

