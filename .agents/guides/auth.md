# AUTH: identity, grants and current authority

## When to read

Read this when changing credentials, account/service identity, grant issuance or
revocation, scoped permissions, retained admissions or authority caching. Follow
the relevant owner/test below; other subsystems need not read this by default.

## Mental model

Authentication proves **who** presents a credential. A role names actions; a live
grant authorizes a recipient at a scope. Creator, issuer, executor, effective
subject and accounting owner are distinct identities, not permission shortcuts.
New effects need current authority plus resource, tool and admission ceilings.

**Human role issuance is not generally an ongoing dependency on the issuer.**
A USER with no agent/service token exercising a live role to grant to a different
account creates a SYSTEM-rooted grant. `issuer_account` and `exercised_grant`
retain authorization/audit evidence. Later issuer demotion, disablement or loss
of that exercised grant does not withdraw the issued grant. Its own revocation,
expiry, recipient validity and mandatory policies still apply. See [ADR 0071]
and [V261]; do not restore blanket issuer-cascade behavior.

Self/own-account-agent, agent, SERVICE-account and service-token issuance retain
delegated `issuing_grant` lineage and expiry caps. That actual parent must remain
live. Exercised self-issuance is refused: a temporary grant cannot make itself
durable. Both paths enforce current issuance authority, role and scope ceilings.
[V263] is the latest grant-insert trigger, retaining the exercised branch.

## Owners and entry points

| Change | Start here |
| --- | --- |
| Credentials/session identity | [AuthFilter], [TokenStore], [ServiceAccounts] |
| Account flags/admin controls | [ServerAdministration], [AccountAdministrationRepository] |
| Typed scope/recipient/action checks | [AuthorityScope], [GrantRecipient], [LiveScopedAuthority] |
| Grant issue/revoke/backstop | [AuthorizedRoleGrants], [RoleGrantPolicy], [JdbcScopedGrantRepository], [V263] |
| Application grant ceilings | [ApplicationGrantLineage] |
| Request/resource and retained work | [ProjectAuthorization], [PinnedOperationAuthority], [OperationInference] |
| Actor acting for a subject | [LiveActingAuthorization], [ADR 0026] |
| Cached/transaction grant reads | [CachedScopedGrantRepository], [transaction design] |

## Non-obvious rules and legacy traps

- **Recipient validity stays live.** Disabling a recipient can make its grant
  unusable without erasing it; re-enabling can restore otherwise valid authority.
  Service access also depends on exact token/principal/owner state. Preserve grant
  and audit evidence; disable/revoke/session retirement defines no general
  account-hard-delete contract.
- **Historical migration exceptions matter.** [V262] preserves dormant lineage
  and its live ancestors instead of reviving/re-rooting everything. Retained rows
  can still depend on the old issuer. ADR 0071 explains re-issue and the accepted
  mutual-issuance/expiry risk; do not silently change that policy.
- **Manifest policy still intersects access.** A person's exercised grant is
  capped by its own recipient's manifest entry, not a later demoted issuer.
  Delegated lineage still intersects issuing-manager manifest limits.
- **Agent identity is explicit.** A display/definition name is not a retained
  recipient identity. Requested MANAGER normally narrows to CONTRIBUTOR; an
  authorized human exception is explicit and does not propagate automatically.
  Agents cannot receive ADMIN/SYSTEM; SERVICE alone does not mean “agent”.
- **Creator is provenance, not rights.** `created_by` implies no READ/WRITE or
  MANAGER entitlement. `PROJECT_CREATOR` is a separate single-action role at
  PROJECT_CREATION, conferring no existing-project or administrative access.
  Administrators do not receive it implicitly.
- **SYSTEM, administration and accounting differ.** SYSTEM is the locked,
  non-assignable root; ADMIN is not SYSTEM or universal private-data access.
  SYSTEM work retains an operation sandbox. Billing ownership/upstream credential
  selection grants no authority. [ADR 0025] keeps immutable accounting distinct
  from the deferred general monetary spending-pool capability.
- **Admission is a ceiling, not a permanent allow.** Keep executor, subject,
  scope, supporting grants and capability fences; recheck at new effects. Never
  replace withdrawn authority with another grant or retry as SYSTEM/service.
  Acting consent is another intersection, not ordinary identity inheritance.
  Contained work still follows its parent-operation fences ([ADR 0042]).
- **Omission is not Global/SYSTEM permission.** Legacy `Home.global()`/null
  locators remain. Resolve explicit `AuthorityScope` at supported boundaries;
  `PinnedOperationAuthority.scope(Home.global())` refuses. Do not mechanically
  rename all nulls or turn a missing project into an allow branch.
- **Cache entries are not grant leases.** Transaction confirmation needs the full
  projection token, statement snapshot/expiry checks and authority-domain dirty
  detection. Preserve own-write/late-commit/rollback/isolation behavior; a scalar
  sequence alone cannot prove fresh authorization.

## Bounded work

Authentication, session checks, role resolution and admission use the
[bounded-work rule](../../docs/coding-standards.md#bound-active-work-in-every-subsystem):
actual query/transaction units have a configurable two-second default ceiling.
Nested SQL shares the transaction remaining time. Slow composed authorization
work warns without a workflow timeout. Query expiry is a typed failed read,
never an allow, fabricated revocation or permission to fall back to another
identity. Do not replay uncertain login/refresh/grant mutations. Retained work
still needs current effect/disclosure fences after queueing or resumption.

Current session-queue and credential-transport limits cover specific phases;
grant expiry/notification staleness is not a request execution deadline. Trace
[session admission](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/SocketSessionProperties.java)
and the actual credential/SQL owner before claiming a universal two-second bound.
Use the owner's cancellation and retain physical capacity until work settles.

The two external-conversation Ex operations reuse only their immediate common
namespace work check. [ProjectAuthorization] first runs its complete ordinary
policy, including service-operation, Personal and effective-role rules, then
mints an opaque `ExternalAdmission` for the synchronous handler. Only that owner
can construct it. Internal Asking/Caller values carry it with exact project,
account, transport session and Ex-operation binding; consumption also requires
the original thread, one use and the identity of the still-open handler scope.
The router closes it when that scope ends. It is neither a wire field nor a
cache/continuing grant. Direct typed calls and wrong, reused or closed-scope evidence
perform the live namespace work check. Actual-target work after alias commit,
post-definition run admission, post-handler and queued-output authority fences
retain their distinct freshness purposes. See
[ExternalConversationService](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/ExternalConversationService.java)
and [FrameRouter](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/FrameRouter.java).

After definition loading, an Ex turn's work check runs against Turn's freshly
loaded/claimed row before any allowance access or raise. The aliased root's
project identity is fixed by its database constraint; its current Home name can
change. A stale name reused for another project is not authority over that row.
Successful Ex result fences derive the internal conversation ID from the typed
result and resolve its current project through ResourceScopeRepository. They
reuse that narrow scope for common and Personal checks rather than hydrating and
discarding a conversation with an attached allowance callback. Post-handler and
queued-output checks remain independent fresh boundaries.

## Change and verification path

Trace credential → exact recipient/scope → current grant/lineage → retained
admission/resource fence. Check service authorization and the DB trigger; change
persisted behavior with a new migration, never shipped SQL. For docs-only work,
check claims/links, not PostgreSQL. For behavior changes, select [ScopedRolePolicyTest],
[ApplicationGrantLineageTest], [LiveScopedAuthorityTest], [ServiceAccountsTest] or
[TokenStoreTest] using `:plowshare-server:test --tests 'fully.qualified.Test'`.
Only SQL/migration/concurrency changes justify selected `-PfullDb` cases:
[RoleExercisedIssuanceDatabaseTest], [RerootPersonIssuedGrantsMigrationTest],
[ProjectCreatorGrantsDatabaseTest] or [CheckedAuthorityGrantsDatabaseTest].
The root coding standards still govern formatting and normal verification.

## Historical context

Use [ADR 0071] with current owners. [ADR 0030], the [handover], [admission plan],
[Global migration map] and [role proposal] retain dated/proposed contracts, defect
pins and task counts. They do not override today's exercised/delegated split.
[ADR 0016] remains declaration-default behavior, not an implicit permission grant.

[ADR 0071]: ../../docs/decisions/0071-role-exercised-issuance-and-project-creator.md
[ADR 0030]: ../../docs/decisions/0030-authority-substrate-and-explicit-scopes.md
[ADR 0026]: ../../docs/decisions/0026-service-execution-on-behalf-of-an-account.md
[ADR 0025]: ../../docs/decisions/0025-service-account-attribution-and-spending-pools.md
[ADR 0016]: ../../docs/decisions/0016-application-execution-account-inheritance.md
[handover]: ../../docs/authority-roles-handover.md
[admission plan]: ../../docs/authority-scope-admission-plan.md
[Global migration map]: ../../docs/global-scope-migration-map.md
[role proposal]: ../../docs/role-scope-permissions-proposal.md
[transaction design]: ../../docs/query-efficiency-authority-in-transaction-design.md
[AuthorityScope]: ../../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/AuthorityScope.java
[GrantRecipient]: ../../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/GrantRecipient.java
[LiveScopedAuthority]: ../../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/LiveScopedAuthority.java
[AuthorizedRoleGrants]: ../../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/AuthorizedRoleGrants.java
[RoleGrantPolicy]: ../../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/RoleGrantPolicy.java
[JdbcScopedGrantRepository]: ../../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/JdbcScopedGrantRepository.java
[ApplicationGrantLineage]: ../../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/ApplicationGrantLineage.java
[ProjectAuthorization]: ../../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/ProjectAuthorization.java
[PinnedOperationAuthority]: ../../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/PinnedOperationAuthority.java
[OperationInference]: ../../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/OperationInference.java
[CachedScopedGrantRepository]: ../../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/CachedScopedGrantRepository.java
[AuthFilter]: ../../plowshare-server/src/main/java/io/aeyer/plowshare/server/auth/AuthFilter.java
[TokenStore]: ../../plowshare-server/src/main/java/io/aeyer/plowshare/server/auth/TokenStore.java
[ServiceAccounts]: ../../plowshare-server/src/main/java/io/aeyer/plowshare/server/auth/ServiceAccounts.java
[ServerAdministration]: ../../plowshare-server/src/main/java/io/aeyer/plowshare/server/auth/ServerAdministration.java
[AccountAdministrationRepository]: ../../plowshare-server/src/main/java/io/aeyer/plowshare/server/auth/AccountAdministrationRepository.java
[LiveActingAuthorization]: ../../plowshare-server/src/main/java/io/aeyer/plowshare/server/acting/LiveActingAuthorization.java
[ScopedRolePolicyTest]: ../../plowshare-server/src/test/java/io/aeyer/plowshare/server/access/ScopedRolePolicyTest.java
[ApplicationGrantLineageTest]: ../../plowshare-server/src/test/java/io/aeyer/plowshare/server/access/ApplicationGrantLineageTest.java
[LiveScopedAuthorityTest]: ../../plowshare-server/src/test/java/io/aeyer/plowshare/server/access/LiveScopedAuthorityTest.java
[RoleExercisedIssuanceDatabaseTest]: ../../plowshare-server/src/test/java/io/aeyer/plowshare/server/access/RoleExercisedIssuanceDatabaseTest.java
[RerootPersonIssuedGrantsMigrationTest]: ../../plowshare-server/src/test/java/io/aeyer/plowshare/server/access/RerootPersonIssuedGrantsMigrationTest.java
[ProjectCreatorGrantsDatabaseTest]: ../../plowshare-server/src/test/java/io/aeyer/plowshare/server/access/ProjectCreatorGrantsDatabaseTest.java
[CheckedAuthorityGrantsDatabaseTest]: ../../plowshare-server/src/test/java/io/aeyer/plowshare/server/access/CheckedAuthorityGrantsDatabaseTest.java
[ServiceAccountsTest]: ../../plowshare-server/src/test/java/io/aeyer/plowshare/server/auth/ServiceAccountsTest.java
[TokenStoreTest]: ../../plowshare-server/src/test/java/io/aeyer/plowshare/server/auth/TokenStoreTest.java
[V261]: ../../plowshare-server/src/main/resources/db/migration/V261__role_exercised_issuance.sql
[V262]: ../../plowshare-server/src/main/resources/db/migration/V262__reroot_person_issued_grants.sql
[V263]: ../../plowshare-server/src/main/resources/db/migration/V263__project_creator_role.sql
[ADR 0042]: ../../docs/decisions/0042-nested-application-containment.md
