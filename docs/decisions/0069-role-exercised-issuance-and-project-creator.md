# ADR 0069: Role-exercised issuance and the project creator role

Status: accepted (2026-11-14, amended 2026-11-15 for Application manifests). This
is a platform change to the authority model. Two things drive it: an ordinary
account could not add a new project folder, and a grant a manager issued fell
whenever that manager's own grant fell. Migrations V260, V261 and V262 implement
it. The design is in `docs/superpowers/specs/2026-11-14-project-creator-role-design.md`.

## Problem

1. TUI `/here` and desktop **Add project folder** claim an unregistered folder
   under its plain name. The server refused the claim, because only an existing
   project member or a server administrator could root a name. There was no
   permission for "may create a project".
2. Grants hung off the grant of the person who issued them. Demoting or
   disabling a manager silently removed every membership that manager had added,
   though the memberships belonged to the project, not to the manager.

## Decision

### 1. Role-exercised issuance

The role is permanent; the authority to exercise it is temporary. A person
(a USER account, with no agent and no token) who issues a grant to someone else
by exercising a live role that permits it produces a SYSTEM-rooted grant.
`issuer_account` names the person and `exercised_grant` records the grant that
authorized the act, both for audit. Liveness depends only on the SYSTEM root, the
grant's own revocation and its own expiry, never on the issuer afterwards.

Authorization is as strict as before. At issue time the issuer must hold a live
grant whose role may issue the requested role at that scope, under the existing
`RoleGrantPolicy` and trigger ceilings. A MANAGER still cannot issue ADMIN,
CONTRIBUTOR and READER still issue nothing, and replacing your own issuing
membership still needs another manager. The trigger verifies the named issuer's
grant when the row is written.

Grants issued by agents, by service tokens or by SERVICE accounts keep their
delegated lineage and expiry caps. They act inside someone else's bounded
authority, and that narrowing is their purpose.

### 2. Self-issuance is refused on the exercised path

A person cannot exercise a grant for themselves. Otherwise a temporary grant
could re-issue itself as a permanent SYSTEM-rooted one. Self-assignment of
memberships, and an administrator granting themselves the creator role, stay
bound to the authorizing grant: delegated lineage, ending with it. The trigger
refuses exercised self-issuance, and `authority_issue_membership` routes
self-assignment through the delegated path.

### 3. No expiry cap derived from the issuer

A person-issued grant carries only its own explicit expiry. A cap at the
issuer's expiry would be the same account dependency in another form. Agent- and
token-issued grants keep their caps.

Known, accepted, open risk: two people can make each other's expiring
memberships permanent. The path exists today:

- A service token is a time-limited proxy for its service account. Its effective
  access is the lower of the account's project role and the token's ceiling,
  and both may be MANAGER (V111), so a service account can manage project
  membership through a token.
- A membership issued through a token is capped at the token's expiry and stays
  bound to that token-issued lineage.
- A USER who holds such an expiring membership is a person exercising a role, so
  under role-exercised issuance they can issue uncapped SYSTEM-rooted grants to
  others. Two such users can therefore make each other permanent before the
  token expires.

Open question, deferred by the user for their own analysis: whether
token-issued grants should follow the service account's role (SYSTEM-rooted,
with no token-expiry cap); whether exercised issuance should be capped at the
exercised grant's expiry; and whether service accounts should be limited (for
example to CONTRIBUTOR) or barred from membership management. Until that is
decided this risk is accepted and open. It must also be revisited before any
feature creates expiring person grants.

### 4. Application manifests

Manifest narrowing no longer follows the issuer for person-issued grants (user
decision, 2026-11-15). A person-issued membership in an Application project does
not fall when the manifest demotes the manager who added it. The child keeps its
own role, capped only by the manifest's entry for the child. `ApplicationGrantLineage`
still narrows through delegated (agent or token) lineage, because those grants
keep their issuer in `issuing_grant`.

### 5. V261 re-roots existing grants

- Live person-issued grants are re-issued from SYSTEM. Grants that are already
  dead by lineage are explicitly revoked, never revived.
- Delegated descendants of a re-issued grant are re-parented onto the new ids,
  so the migration removes no access that was live before it ran.
- Re-issue processes the deepest grants first, so each grant's exercised grant
  is still live when the copy is written.
- A self-check makes the migration fail if the live grant set differs before
  and after.
- Dormant grants are kept. A grant that is not live only because an account on
  its path is disabled (its subject, an ancestor's subject, or an issuing
  token's principal or owning service) revives when that account is re-enabled,
  so V261 neither revokes nor re-roots it. It also leaves every live ancestor of
  a dormant grant on its old lineage, so the dormant subtree stays intact. Only
  grants that are dead even with every account enabled (revoked or expired
  lineage, a revoked token) are revoked. A second self-check compares access
  over "live if every account were enabled" before and after.
- Operator note: a kept live grant still depends on its issuer, as before V261.
  Accounts disabled at deploy time keep that old shape; re-enabling them does
  not re-root anything. Re-issue such a grant (remove and add the member again)
  if it must outlive its issuer.
- Operator note: V261 re-checks each moved grant through the grant trigger. Time
  it against a pre-deploy dump before a production rollout.
- Operator note: the audit log records the revoke and the re-issue, but has no
  direct old-to-new id link.

### 6. PROJECT_CREATOR

- It sits on its own isolated `PROJECT_CREATION` scope, never ADMINISTRATION.
  The ADMINISTRATION scope is the root of authority and takes part in
  delegation, so a creator there would be one rule away from administrative
  authority. The new scope contains one action, `PROJECT_CREATE`, and nothing
  else may target it.
- It is a single action and is not delegable. A holder issues nothing and
  satisfies no administrator, `MANAGE_ACCESS` or `ADMINISTER` check.
- It is issued by the ADMIN role's authority (an active, non-bootstrap USER
  administrator), as a SYSTEM-rooted grant. A grant to another account survives
  the issuing administrator's demotion. An administrator's grant to themselves
  is a bound self-grant and ends if they stop being an administrator; another
  administrator's grant replaces it with the durable form.
- Administrators get no implicit grant.
- The administration view lists a disabled account that holds an unrevoked
  grant (with a lineage that would be live if accounts were enabled) as a
  holder, and revoking it is audited. It still cannot create projects while
  disabled: `authority_may_create_projects` and its snapshot mirror remain the
  creation decision, and `authority_holds_project_creator` only answers "holds".
- An administrator who demotes themselves and revokes their own creator role in
  one `admin.account.update` call is refused and the whole call rolls back: the
  demotion applies first, so the revoke no longer has administrator authority.
  Revoke the role first, then demote, in two calls.

### 7. Rooting

- "Unregistered" means no `projects` row. A creator creates the row insert-only
  and the creator becomes the sole MANAGER. A row that appears in the meantime
  is refused, never adopted.
- An orphan row with no members (memory writes can create one) stays
  admin-only.
- Any member may re-root an existing client-rooted project, so a creator can
  reconnect after their creator grant lapses.
- Personal, private `client:` and server-project branches are unchanged.
- The 1008 refusal reason stays one generic sentence for "new name without the
  role" and "existing project without membership", so it does not reveal whether
  a name exists. The TUI and desktop append a fixed hint to ask an administrator
  for the project creator role.
- A refusal during creation (another creator won the race) gives the claim
  back and the socket closes with 1003.
- An archive outage during creation makes `FileChannelHandler.declared`
  withdraw the presence and return true. The socket stays open and lends its
  files only; its ready frame carries no project, so a client that requires a
  ready project reports 1003. A project with no row and no MANAGER is never
  served.

## Alternatives rejected

1. **Role granted at the ADMINISTRATION scope.** That scope holds the SYSTEM
   root-policy coverage and participates in delegation.
2. **A `PROJECT_CREATE` action on an existing role.** On ADMIN it would limit
   creation to administrators; on CONTRIBUTOR or READER at an administrative
   scope it would hand out that scope's READ/WRITE.
3. **A per-account ACL entry.** Routine permissions are roles, not ACL entries.
4. **Keeping grants bound to their issuer.** This is the problem being fixed.

## Known gaps

- The CLI path through `sdk/node/src/session.ts` drops the WebSocket close code,
  so a CLI refusal shows no creator-role hint.
- After a creation outage, a later write can leave an orphan row (a `projects`
  row with no members). Only an administrator can recover it.

## Compatibility and tests

- Migrations V260 (exercised issuance), V261 (re-root) and V262 (scope, role,
  trigger branch, registry self-check). Shipped migrations are unchanged.
- Administrators grant or revoke the role with `admin account update` and
  `projectCreator`; the account listing reports it, and the desktop server
  administration dialog has a control for it.
- Mocked tests cover the rooting handler, policy, snapshot and clients. Real
  PostgreSQL tests (`@Tag("full-db")`) cover the migrations, trigger checks,
  the creation race and the V261 live-set invariant.
