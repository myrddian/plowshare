# Project creator role

Status: approved 2026-11-14, including proposals 2 and 3 under "Open decisions". Covers two parts: role-exercised issuance (which corrects
MANAGER-issued grants) and the project creator role.

## Intent

An ordinary account cannot add a new project folder today. TUI `/here` and
desktop **Add project folder** claim an unregistered folder under its plain name
(for example `sundial`). `FileChannelHandler.declared` refuses the claim because
the account has no role in a project that does not exist yet:

```
the server would not let this client root sundial: this account may not root 'sundial'
```

The intended behaviour is an explicit RBAC permission. An account that holds it
can create a project by rooting a folder. The new project belongs to that
account: the creator becomes its only MANAGER and nobody else gains access. An
account without it is refused, as now.

## Goals

- A `PROJECT_CREATOR` role, expressed through the scoped RBAC grant system
  (`authority_roles`, `authority_role_actions`, `authority_grants`,
  `AuthoritySnapshot`). Not a boolean account column.
- Holders create a project by rooting an unregistered folder from the TUI,
  desktop or CLI. The project is created with the holder as sole MANAGER.
- Server administrators can issue and revoke the role from the CLI and the
  desktop account administration dialog.
- Clients explain a refusal in plain words instead of showing a raw close reason.

## Non-goals

- Server-workspace creation (`project.create`, `application.create`). These
  provision directories on the server's disk and stay server-admin-only. *(My
  call; raised in conversation and not objected to.)*
- Sharing. Adding further members remains the existing MANAGER membership flow.
- A general grant-management API for other roles or scopes.
- Migrating `server_admin` itself onto grants.

## Anti-goals

These would make the change a failure even if rooting works:

- **Any path from this role to administration.** The role is not granted at
  the ADMINISTRATION scope, carries no admin action, cannot be delegated, and
  must never satisfy `isServerAdmin`, `mayAdminister`, `MANAGE_ACCESS` or
  `ADMINISTER` checks.
- **Access to existing projects.** Holding the role must not let an account root,
  read or join a project that already exists. Rooting an existing project still
  requires membership, exactly as today.
- **Implicit grants.** Server admins do not receive the role implicitly, and
  creating a project grants nothing beyond MANAGER on that project.

## Approaches considered

1. **Role granted at the ADMINISTRATION scope.** Rejected by the user: that scope
   is the root of the authority tree (it holds the SYSTEM root-policy grant with
   ALL_SCOPES coverage) and participates in delegation, so a creator would sit
   one rule away from administrative authority.
2. **Add `PROJECT_CREATE` to an existing role.** On ADMIN it would restrict
   creation to administrators. Granting CONTRIBUTOR or READER at an
   administrative scope would also hand out that scope's READ/WRITE. Rejected.
3. **Per-account ACL entry.** The role/scope proposal makes routine permissions
   roles, not ACL entries. Rejected.
4. **Chosen: a dedicated, isolated built-in scope and a single-action role.**
   Holding the grant confers exactly one action, in a scope that contains nothing
   else and covers nothing else.

## Design

### Authority model (new Flyway migration, next free version after V259)

- **Action:** add `PROJECT_CREATE` to the `authority_role_actions.action` CHECK.
- **Scope kind:** add `PROJECT_CREATION` to the `authority_scopes.kind` CHECK
  (use the latest definition of that constraint, which later migrations such as
  V216 may have replaced).
- **Built-in scope:** one reserved scope row of kind `PROJECT_CREATION`, with the
  next reserved UUID after the current built-ins, `...000b`. Nothing other than
  `PROJECT_CREATOR` grants may target it (a CHECK or trigger). The row must also
  satisfy these constraints, each extended for the new built-in:
  - the unnamed `builtin IN (...)` column CHECK (V155:51);
  - the unnamed built-in shape CHECK (V155:59-64). Like V216:20-42, find both
    unnamed CHECKs by their definition and replace them;
  - `authority_scope_kind_shape` (V216:51-57). *My call:* `account_id` is
    SYSTEM and `project_id` is null, matching the other system-owned built-in.
- **Registry self-check:** redefine `authority_builtin_registry_valid()`
  (V158:4-10, V155:243-247) for one more role, one more role-action row and
  one more built-in scope. If it isn't redefined,
  `JdbcIdentityRepository.verifyBuiltIns` refuses to start the server.
- **Java scope type:** add a sealed `AuthorityScope` variant for the new kind,
  read by `JdbcIdentityRepository.read` (unknown kinds become `absent()`
  today). Handle it in every switch over scopes: `JdbcIdentityRepository`,
  `LiveScopedAuthority`, `PinnedOperationAuthority`,
  `LiveActingAuthorization` and `AccountRequestProjects`. In each it
  authorizes nothing except `PROJECT_CREATE`.
- **Grant trigger:** `authority_grant_insert` (current version V216:174-223)
  admits a grant only when its issuing grant is the issuer's own and covers the
  destination scope. No admin grant covers `PROJECT_CREATION`. It also admits
  SERVICE subjects, so the USER-only rule needs a new check. See "Issuing
  authority" below.
- **Role:** `PROJECT_CREATOR`, assignable, reserved UUID `...000c`, role version 1,
  with exactly one DEFAULT-profile action: `PROJECT_CREATE`. Extend
  `authority_role_registry_shape`, `authority_reserved_id`,
  `authority_grants.requested_role`, and the reserved-identity collision guard.
- **Grant shape:** `subject_account` is a USER account (no agent subject, no
  service tokens). Scope is the `PROJECT_CREATION` scope. Coverage is `SELF`.
  Provenance is the existing `ROLE`, which `issue()` already writes
  (`JdbcScopedGrantRepository.java:129`; allowed by the V156:76 CHECK). One live grant per
  account (partial unique index). Issue and revoke write
  `authority_grant_audit`, as other grants do.
- Shipped migrations are not edited. If a baseline (`db/baseline`) must stay
  equivalent, follow the repository's baseline procedure.

### Java contracts

- `BuiltInIdentities`: `PROJECT_CREATION` scope ID and `PROJECT_CREATOR` role ID,
  both added to the reserved list.
- `StandardRole.PROJECT_CREATOR`, `AuthorityAction.PROJECT_CREATE`.
- `RoleGrantPolicy.effective` takes no scope and no issuer-is-admin input
  (`RoleGrantPolicy.java:12-18`). *My call:* add the destination scope kind and
  an issuer-is-server-admin flag as explicit parameters; its only caller is
  `JdbcScopedGrantRepository.java:120`. Rules:
  - At `PROJECT_CREATION`, only `PROJECT_CREATOR` may be requested, and only by
    a USER server administrator, never an agent.
  - `PROJECT_CREATOR` is valid at no other scope.
  - A `PROJECT_CREATOR` issuing grant permits nothing.
- `AuthoritySnapshot`: load the new role, scope and grants through the existing
  facts path and answer `mayCreateProjects(handle)`. It is true only for an
  active, non-bootstrap USER account holding a live, unexpired
  `PROJECT_CREATOR` grant. No SQL fallback differs from the snapshot rule.
- A narrow interface, for example `ProjectCreation`, with
  `boolean mayCreate(String handle)`, implemented over the snapshot (cached
  path) with the JDBC equivalent behind it. Consumers take the interface by
  constructor injection.
- Issue/revoke service: a narrow service for this role, authorized by the
  acting account's current ADMIN role (see "Issuing authority").

### Issuing authority

**Decided (user):** everything inherits from SYSTEM. Authority runs in one chain:
SYSTEM → ADMIN → MANAGER → CONTRIBUTOR → READER, with SYSTEM the most powerful.
**Decided (user):** authority belongs to roles, not accounts. An account
exercises a role's authority, but a grant it issues is the role's act. So the
grant never depends on the issuing account afterwards: demoting that admin,
switching off their account or changing its flags leaves the grant live. The
role chain is SYSTEM → ADMIN → MANAGER → CONTRIBUTOR → READER, with SYSTEM the
most powerful, and every grant's lineage starts at SYSTEM.

What the code does today, checked:

- The only root is the SYSTEM `ROOT_POLICY` grant. A grant is live only while
  its `issuing_grant` chain back to that root is live
  (`authority_live_grants`, V216:148-170).
- **Account-bound:** grants a MANAGER issues through
  `authority_issue_membership` (V156) hang off that manager's own grant, and
  fall when it falls. Admin `ADMIN_CONTROL` grants are revoked and reissued on
  any `server_admin`, `bootstrap` or `account_kind` change (V156:265-294).
- **Role-shaped:** creator memberships minted by `authority_membership_changed`
  (V216:231-248) hang off the SYSTEM root directly.

`PROJECT_CREATOR` follows the role-shaped pattern:

- **Authorization:** at issue time, the acting account must currently exercise
  the ADMIN role: an active, non-bootstrap USER server administrator with a
  live `ADMIN_CONTROL` grant. The service checks this, and the database
  re-checks it in the trigger.
- **Lineage:** `issuing_grant` is the SYSTEM `ROOT_POLICY` grant, so liveness
  depends only on SYSTEM, the grant's own revocation and its expiry. Never on
  the issuing account.
- **Attribution:** `issuer_account` records the acting admin. The trigger
  currently requires `parent.subject_account = issuer_account` (V216:183), so
  the migration adds one narrow branch to `authority_grant_insert`. It applies
  only when the role is `PROJECT_CREATOR`, the scope is `PROJECT_CREATION`, the
  parent is `ROOT_POLICY`, and the issuer holds the ADMIN role at that moment.
  In that case the grant may be SYSTEM-parented while naming the admin as
  issuer. No other role or scope gets this branch. The audit rows then name
  the admin.
- **Revocation:** any account currently exercising the ADMIN role, not only the
  original issuer, attributed the same way.
- An admin may grant the role to themselves; that self-grant is bound to their
  ADMIN_CONTROL grant (delegated lineage) and ends if they stop being an admin,
  matching self-assignment of memberships.
- **Issue/revoke path:** a narrow service for this role. It doesn't go through
  `AuthorizedRoleGrants` and its `MANAGE_ACCESS`-on-scope check, because
  nobody, admins included, holds `MANAGE_ACCESS` at `PROJECT_CREATION`.
  Holders can't issue anything.

### Role-exercised issuance (general rule; corrects MANAGER-issued grants)

**Decided (user):** the role is permanent and the authority to exercise it is
temporary. An account that holds a role may exercise it; what it issues
belongs to the role and never depends on that account afterwards.

One mechanism serves both this role and the correction:

- **Rule:** when a person (a USER account acting with no agent) issues a grant
  by exercising a live role that permits it, the new grant's
  `issuing_grant` is the SYSTEM `ROOT_POLICY` grant. `issuer_account` records
  the person, for audit only. Liveness never depends on the issuer's grant,
  account state or later demotion.
- **Authorization stays exactly as strict:** at issue time, the issuer must hold
  a live grant whose role may issue the requested role at that scope, under the
  existing `RoleGrantPolicy` and trigger ceilings: MANAGER cannot issue ADMIN,
  CONTRIBUTOR and READER issue nothing, scope coverage applies, and
  "Replacing your issuing membership requires another manager" still holds.
  The trigger verifies that the named issuer held such a grant when the row was
  written, replacing today's `parent.subject_account = issuer_account` test for
  this case.
- **Sites changed:**
  - `authority_issue_membership` (V156:358), used for project membership by
    managers and admins.
  - `JdbcScopedGrantRepository.issue` (ROLE provenance), for person issuers.
  - `PROJECT_CREATOR` issuance (above).
- **Unchanged:** the SYSTEM-minted triggers (`authority_membership_changed`,
  `authority_refresh_admin`) are already SYSTEM-rooted.
- **Delegated issuers stay bound** (*my call*): grants issued by an agent
  (`issuer_agent`) or through a service token (`issuing_token`) keep today's
  lineage and expiry. An agent or token is not a person holding a role; it acts
  inside someone else's bounded authority, and its narrowing is the point.
- **Existing data** (*my call*): a new migration re-roots only person-issued
  grants that are live at migration time, recording the change in
  `authority_grant_audit`. Person-issued grants that are already dead because
  their lineage fell are explicitly revoked, so re-rooting never restores
  access that is already gone.
- **Expiry (decided, user):** a role-exercised grant carries only its own
  explicit `expires_at`. The cap at the issuer's grant expiry in
  `authority_issue_membership` is removed for person issuers; a cap tied to the
  issuer is the same account dependency in another form. Token- and
  agent-issued grants keep their caps. A person holding a token-issued (so
  expiring) membership can still issue permanent grants; that laundering path
  is a known, accepted, open risk, and its remedy is an open question in
  ADR 0071 §3.
- **Application manifest narrowing (decided, user, 2026-11-15):** narrowing
  no longer follows the issuer for person-issued grants. A person-issued
  membership in an Application project does not fall when the manifest demotes
  the manager who issued it. The child keeps its own role, capped only by the
  manifest's entry for the child. `ApplicationGrantLineage` still narrows
  through delegated (agent or token) lineage, because those grants keep their
  issuer in `issuing_grant`.
- **No self-issuance on the exercised path (security review):** a person cannot
  exercise a grant for themselves, otherwise a temporary grant could turn itself
  into a permanent SYSTEM-rooted one. Self-assignment through
  authority_issue_membership stays bound to the authorizing grant (delegated
  lineage and expiry); the trigger refuses exercised self-issuance.

### Rooting path

- `FileChannelHandler.declared`: permit the claim when
  `ClientProjects.visible(...) && (members.mayUse(project, handle) || (the project is unregistered && creation.mayCreate(handle)))`.
  "Unregistered" comes from a typed lookup behind a new sibling interface.
  `ProjectRoots` documents that it "is not a policy seam". The handler runs no
  SQL. What counts as unregistered is **open; decision needed** (see below).
- `ProjectStore.rootOn(name, machine, root, handle)`: `find` returns only server
  projects (`ProjectStore.java:166,1212-1226`). So the branch that today calls
  `requireServerAdmin` covers both brand-new names and existing client-rooted
  projects. Today even a project's own MANAGER cannot re-root it unless they
  are an admin. Split that branch:
  - **New project:** requires the project-creation check. Creation goes through
    `withCreator`, which inserts the first MANAGER. The membership trigger then
    issues the MEMBERSHIP grant and the snapshot version advances.
  - **Existing client-rooted project:** **open; decision needed** (see below).
  - Personal, private `client:` and server-project branches are unchanged.
- Races: `withCreator` inserts MANAGER only `WHERE NOT EXISTS` a member and then
  returns success silently, and the presence registry admits a second session
  at the same place (`ProjectStore.java:675-697`, `PresenceRegistry.java:53-56`).
  *My call:* after taking the lock, `rootOn` checks that the caller is a member.
  If not, it throws `ArchiveRefusedException`, so the second claimant is
  refused instead of being served without membership. Covered by a full-db test.
- Refusals and outages during creation:
  - Today `requireServerAdmin` throws `CallerFault`, which `declared` doesn't
    catch, and the presence is already declared at that point
    (`FileChannelHandler.java:1101,1123-1145`). *My call:* the creation path
    only throws `ArchiveRefusedException`, so the presence is withdrawn and
    the socket closes with 1003.
  - An archive outage during *creation* also withdraws the presence and roots
    nothing. A project with no row and no MANAGER must never be served. The
    existing serve-through-outage rule still applies to already-registered
    projects.
- Names: ordinary project-name validation (`ordinary`, `named`). Reserved
  prefixes (`personal:`, `client:`, Application names) cannot be created this
  way.

### Wire operations and clients

- New operations, for example `admin.account.project-creator.grant` and
  `admin.account.project-creator.revoke`, taking an account handle. A role
  field on `admin.account.update` is an acceptable alternative if it matches
  that operation's conventions better. Either way the account listing reports
  whether each account holds the role. Update the shared operation contracts in
  `sdk/typescript` and regenerate derived catalogs through their owning scripts.
- **CLI:** account update/list shows and changes the role.
- **Desktop** (`renderer/server-admin.ts`): a "May create projects" control
  beside the existing account controls, admin-only, as the dialog already is.
- **TUI `/here` and desktop Add project folder:** no flow change. *My call:*
  the server's 1008 close reason stays one generic sentence for "new name
  without the role" and "existing project without membership". Different
  wording would tell a non-member whether a project name exists. Clients add
  a fixed hint: "If this is a new project, a server administrator must grant
  you the project creator role."
- Desktop: keep the uncommitted fix from this session (a project connection
  skips project-scoped listings until the project is listed, and refresh drops
  rosters of unlisted projects). Keep the test fixture refusing listings for
  unregistered projects. Its "an accepted claim registers the project"
  behaviour becomes correct only for role holders, so add a refused,
  non-holder case.

### Documentation

- `docs/server-administration.md`: the role, who can issue it, and what it
  does not grant.
- `docs/projects.md`: creating a project by rooting a folder.
- An ADR in `docs/decisions/` recording why the role is an isolated scope and not
  ADMINISTRATION-scoped.

## Tests

Mocked by default, per the database test policy:

- Handler: a holder roots an unregistered project; a non-holder is refused (1008);
  a holder is refused for an existing project they are not a member of; a
  creation refusal from `rootOn` withdraws the presence (1003); an archive
  outage during the pre-check or creation roots nothing.
- `RoleGrantPolicy`: only an admin user issues; an agent issuer is refused;
  `PROJECT_CREATOR` cannot issue; no other role at the scope.
- Snapshot: grant live, revoked, expired, disabled account, bootstrap account,
  service account.
- Clients: refusal wording; admin control is shown only to administrators.

`@Tag("full-db")` (needs real PostgreSQL: migration constraints, triggers, row
locking): the migration applies and its CHECKs reject misuse; issuing writes
the audit row; the creation race yields one MANAGER and one refusal; the
membership trigger issues the MEMBERSHIP grant. Run only these with `--tests`.

## Open decisions (user)

1. ~~Issuing authority~~: decided. Issued by the ADMIN role's authority,
   SYSTEM-rooted, never dependent on the issuing account (see "Issuing authority").
2. Re-rooting existing client-rooted projects. *Proposed:* any member
   (`mayUse`) may re-root. Otherwise a creator could not reconnect their own
   project once their creator grant lapsed.
3. Unregistered name. *Proposed:* no `projects` row at all. An orphan row with
   no members, which memory writes can create (`ProjectIds.toWrite`), stays
   admin-only.
4. ~~Expiry~~: decided. There is no issuer-derived cap for person issuers (see
   "Role-exercised issuance").

Build order: role-exercised issuance first, because the creator role's issuing
depends on it; then the creator role and rooting path; then clients and docs.

## Left to the builder

- Exact operation names and payload shape, matched to neighbouring admin operations.
- Whether a CHECK or a trigger confines the scope to `PROJECT_CREATOR` grants.
- Desktop control placement and copy within the existing dialog.
- Case-only name differences follow the existing ordinary-name rules. A name
  already held by another account's live presence is refused by the presence
  registry, as today.
