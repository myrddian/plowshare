# Project Creator Role Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Grants a person issues by exercising a role no longer depend on that person's account, and a `PROJECT_CREATOR` role lets an account create its own project by rooting a folder.

**Architecture:** A new `authority_grants.exercised_grant` column separates *authorization* (the issuer's live grant, checked at write time) from *lineage* (`issuing_grant`, now the SYSTEM `ROOT_POLICY` grant for person issuers). `PROJECT_CREATOR` is a single-action role confined to a new isolated built-in `PROJECT_CREATION` scope. An admin issues it through the same exercised-grant mechanism, the database answers `authority_may_create_projects(handle)`, and the authority snapshot mirrors that function. The file channel and `ProjectStore.rootOn` use the check to create unregistered projects, with the creator as sole MANAGER.

**Tech Stack:** Java 21 / Spring Boot, Spring JDBC, PostgreSQL + Flyway, JUnit 5 + Mockito, Testcontainers (`@Tag("full-db")`); TypeScript SDK (`sdk/typescript`, `sdk/node`), Electron desktop, TUI.

**Spec:** `docs/superpowers/specs/2026-11-14-project-creator-role-design.md`

## Global Constraints

- Read `AGENTS.md`, `docs/coding-standards.md`, `docs/typescript-standards.md` first.
- New Flyway migrations only: `V260`, `V261`, `V262` in `plowshare-server/src/main/resources/db/migration`. Never edit a shipped migration. No baseline edit (B233 precedes V234+).
- Only repositories/stores and migrations touch SQL. Handlers, services and tools take interfaces by constructor injection.
- Import Java types; no fully qualified names outside imports unless resolving a real clash (explain it beside the use).
- After Java edits: `./gradlew format` then `./gradlew formatCheck`.
- Database tests: `@Tag("full-db")`, run only with `-PfullDb` and an explicit `--tests` selection, and only for the classes each task names. Reason in every case: migration constraints, triggers, lineage liveness or row locking.
- Reserved IDs: `PROJECT_CREATION` scope `00000000-0000-0000-0000-00000000000b`, `PROJECT_CREATOR` role `00000000-0000-0000-0000-00000000000c`.
- Names: action `PROJECT_CREATE`; scope kind and `builtin` value `PROJECT_CREATION`; role `PROJECT_CREATOR`; grant provenance `ROLE`.
- Agent-issued (`issuer_agent`) and token-issued (`issuing_token`) grants keep today's lineage and expiry caps.
- Refusal copy appended by clients, verbatim: `If this is a new project, a server administrator must grant you the project creator role.`
- `AuthoritySnapshot` methods mirror an SQL function clause by clause, and `AuthoritySnapshotDifferentialDatabaseTest` must cover each new one.

## Review Focus

1. **A manager removed after adding members:** the members keep access. Tested in Task 2 (`member_survives_issuing_manager_removal`).
2. **Upgrading a server that already has lineage-dead memberships:** the dead memberships stay dead, revoked explicitly, never revived. Tested in Task 3 (`lineage_dead_grants_are_revoked_not_revived`).
3. **The issuing admin is demoted or disabled after granting the creator role:** the role still works. Tested in Task 6 (`creator_grant_survives_issuer_demotion_and_disable`).
4. **A holder roots a name that differs from an existing project only by surrounding whitespace, or is a reserved `personal:`/`client:` name:** refused, and no project is created. Tested in Task 8 (`creator_cannot_create_reserved_or_unnormalised_names`).
5. **A creator whose role was later revoked reconnects their own project:** allowed, because they are its member. Tested in Task 8 (`member_reroots_after_creator_role_revoked`).

---

### Task 1: Commit the desktop project-connection fix from this session

**Files:**
- Modify (already edited, uncommitted): `plowshare-desktop/src/client.ts`, `plowshare-desktop/src/workspace.test.ts`

- [ ] **Step 1: Verify**

Run: `cd plowshare-desktop && pnpm run typecheck && pnpm test`
Expected: typecheck clean; `ℹ fail 0`.

- [ ] **Step 2: Commit**

```bash
git add plowshare-desktop/src/client.ts plowshare-desktop/src/workspace.test.ts
git commit -m "fix(desktop): skip project listings until the project is listed"
```

### Task 2: Role-exercised issuance in the database (V260)

**Files:**
- Create: `plowshare-server/src/main/resources/db/migration/V260__role_exercised_issuance.sql`
- Test: `plowshare-server/src/test/java/io/aeyer/plowshare/server/access/RoleExercisedIssuanceDatabaseTest.java` (`@Tag("full-db")`; reuse `GrantLineageFixture` for setup)

**Interfaces:**
- Produces:
  - Column `authority_grants.exercised_grant UUID NULL REFERENCES authority_grants(id)`, with `CHECK (exercised_grant IS NULL OR (issuer_agent IS NULL AND issuing_token IS NULL))`.
  - Updated `authority_grant_insert()`, `authority_audit_grant()` and `authority_issue_membership(project, target, actor, next_role)`, all with unchanged signatures.

- [ ] **Step 1: Write the failing tests**

All in `RoleExercisedIssuanceDatabaseTest`:
- `member_survives_issuing_manager_removal`: manager `mia` adds `sam` through `authority_issue_membership`. Revoke `mia`'s membership. Assert `sam`'s MEMBERSHIP grant is in `authority_live_grants()`. Assert its `issuing_grant` equals the ROOT_POLICY id and its `exercised_grant` equals `mia`'s grant id.
- `issued_membership_has_no_issuer_expiry_cap`: `mia`'s grant expires in 1 day. Add `sam`. Assert `sam`'s `expires_at IS NULL`.
- `exercised_grant_must_be_live_and_held_by_issuer`: a direct INSERT naming a revoked exercised grant fails with `Issuing authority is not live`. One naming another account's grant fails with `Issuing grant belongs to a different principal`.
- `exercised_issuance_keeps_role_ceilings`: a MANAGER exercised grant requesting ADMIN fails with `Role exceeds issuing authority`. A CONTRIBUTOR exercised grant fails the same way. An exercised grant on project A cannot issue on project B (`Grant exceeds issuing scope`).
- `exercised_grant_forbids_agent_and_token_issuers`: insert with `exercised_grant` plus `issuer_agent`, then plus `issuing_token`. Both fail the CHECK.
- `audit_records_exercised_grant_as_authority`: the ISSUE audit row for `sam` has `actor_account = mia` and `authority = mia`'s grant id.
- `token_issued_membership_is_unchanged`: the service-token path keeps `issuing_grant = owner's grant`, `exercised_grant IS NULL`, and its expiry cap.

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :plowshare-server:test -PfullDb --tests 'io.aeyer.plowshare.server.access.RoleExercisedIssuanceDatabaseTest'`
Expected: FAIL. The column does not exist yet.

- [ ] **Step 3: Write V260**

- **Trigger:** when `NEW.exercised_grant IS NOT NULL`, run every existing authorization check against the exercised grant rather than the issuing grant: liveness, depth, principal, role ceiling and coverage. Lock `authority_lock_lineage(NEW.exercised_grant)` instead of the root. Additionally require:
  - `NEW.issuing_grant` is the live `ROOT_POLICY` grant;
  - `issuer_kind = 'USER'`;
  - skip the `Grant outlives issuing authority` check.

  Leave the existing branch for `exercised_grant IS NULL` byte-for-byte equivalent.
- **Audit:** `authority = coalesce(NEW.revoking_grant, NEW.exercised_grant, NEW.issuing_grant)`.
- **`authority_issue_membership`:** when `token IS NULL`, insert with `issuing_grant = root.id`, `exercised_grant = authority`, and `expires_at = NULL`. The token path stays as in V156:358-382.

- [ ] **Step 4: Run tests to verify they pass**

Run the Step 2 command, plus `--tests 'io.aeyer.plowshare.server.access.ScopedGrantPersistenceTest' --tests 'io.aeyer.plowshare.server.archive.ProjectMembersTest' --tests 'io.aeyer.plowshare.server.access.AuthoritySnapshotDifferentialDatabaseTest'`.
Expected: PASS. Existing lineage and differential tests still pass.

- [ ] **Step 5: Commit**

```bash
git add plowshare-server/src/main/resources/db/migration/V260__role_exercised_issuance.sql plowshare-server/src/test/java/io/aeyer/plowshare/server/access/RoleExercisedIssuanceDatabaseTest.java
git commit -m "feat(authority): root person-issued grants at SYSTEM, recording the exercised grant"
```

### Task 3: Re-root existing person-issued grants (V261)

**Files:**
- Create: `plowshare-server/src/main/resources/db/migration/V261__reroot_person_issued_grants.sql`
- Test: `plowshare-server/src/test/java/io/aeyer/plowshare/server/access/RerootPersonIssuedGrantsMigrationTest.java` (`@Tag("full-db")`; migrate to target `259`, seed, then migrate to `261`, following `ProjectMembersTest.migration_backfills_every_existing_project`)

**Interfaces:**
- Consumes: V260's `exercised_grant` and trigger branch.

- [ ] **Step 1: Write the failing tests**

- `live_person_issued_grants_are_reissued_from_root`: at V259, `mia` (MANAGER) issues `sam` a membership. After V261:
  - `sam` has exactly one live MEMBERSHIP grant, whose `issuing_grant` is the root and whose `exercised_grant` is `mia`'s grant;
  - the old grant is revoked with `revoked_by` = the SYSTEM account;
  - `sam`'s `expires_at` is unchanged.
- `lineage_dead_grants_are_revoked_not_revived`: at V259, `mia` adds `sam`, then `mia` is removed (sam's grant is dead by lineage, not revoked). After V261, `sam`'s grant has `revoked_at IS NOT NULL`, and `sam` has no live grant on the project.
- `agent_and_token_issued_grants_are_untouched`: their rows are byte-identical before and after.
- `system_minted_grants_are_untouched`: `ROOT_POLICY`-parented MEMBERSHIP and `ADMIN_CONTROL` rows are unchanged.

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :plowshare-server:test -PfullDb --tests 'io.aeyer.plowshare.server.access.RerootPersonIssuedGrantsMigrationTest'`
Expected: FAIL. No V261 yet.

- [ ] **Step 3: Write V261**

Select unrevoked grants with `issuer_agent IS NULL`, `issuing_token IS NULL`, `provenance IN ('MEMBERSHIP','ROLE')`, and an `issuing_grant` that is not the root. For each:
- **Live** (in `authority_live_grants()`): revoke it, attributed to the root, then insert a copy (same subject, scope, role, requested role, coverage, `expires_at`, `manager_forced`, provenance) with `issuing_grant` = root and `exercised_grant` = the old `issuing_grant`. Process parents before children (order by depth) so a child's exercised grant is still live.
- **Not live:** revoke it, attributed to the root.

- [ ] **Step 4: Run tests to verify they pass**

Run the Step 2 command.
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add plowshare-server/src/main/resources/db/migration/V261__reroot_person_issued_grants.sql plowshare-server/src/test/java/io/aeyer/plowshare/server/access/RerootPersonIssuedGrantsMigrationTest.java
git commit -m "feat(authority): re-root live person-issued grants; revoke lineage-dead ones"
```

### Task 4: Person issuance through the Java grant repository

**Files:**
- Modify: `plowshare-server/src/main/java/io/aeyer/plowshare/server/access/JdbcScopedGrantRepository.java` (`issue`, `insert`)
- Test: `plowshare-server/src/test/java/io/aeyer/plowshare/server/access/ScopedGrantPersistenceTest.java` (existing, `@Tag("full-db")`)

**Interfaces:**
- Consumes: V260 columns.
- Produces: `ScopedGrantRepository.issue(Issue)` keeps its signature. For an issuer with no agent, the stored row has `issuing_grant` = root and `exercised_grant` = `request.authority()`.

- [ ] **Step 1: Write the failing tests**

- `person_issued_role_grant_survives_issuer_revocation`: issue CONTRIBUTOR to `sam` with `mia`'s MANAGER grant as authority, then revoke `mia`'s grant. Assert `grants.live(sam)` still contains the new grant.
- `agent_issued_role_grant_still_follows_its_issuer`: the same with `issuer.agent()` present. After revocation the grant is absent from `live`.

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :plowshare-server:test -PfullDb --tests 'io.aeyer.plowshare.server.access.ScopedGrantPersistenceTest'`
Expected: the first new test FAILS.

- [ ] **Step 3: Implement**

In `insert`, when `request.issuer().agent().isEmpty()`, write `issuing_grant` = the root policy id (read once within the transaction with `SELECT id FROM authority_grants WHERE provenance='ROOT_POLICY'`) and `exercised_grant` = `request.authority()`. Otherwise keep today's columns.

- [ ] **Step 4: Run tests to verify they pass**

Run the Step 2 command.
Expected: PASS.

- [ ] **Step 5: Format and commit**

```bash
./gradlew format formatCheck
git add plowshare-server/src/main/java/io/aeyer/plowshare/server/access/JdbcScopedGrantRepository.java plowshare-server/src/test/java/io/aeyer/plowshare/server/access/ScopedGrantPersistenceTest.java
git commit -m "feat(authority): person-issued scoped grants are role-exercised"
```

### Task 5: PROJECT_CREATOR role, PROJECT_CREATION scope and Java vocabulary (V262)

**Files:**
- Create: `plowshare-server/src/main/resources/db/migration/V262__project_creator_role.sql`
- Modify:
  - `plowshare-server/src/main/java/io/aeyer/plowshare/server/access/BuiltInIdentities.java`
  - `.../access/StandardRole.java`
  - `.../access/AuthorityAction.java`
  - `.../access/AuthorityScope.java`
  - `.../access/JdbcIdentityRepository.java` (`read`, `verifyBuiltIns` expectations)
  - `.../access/RoleGrantPolicy.java`
  - the scope switches in `LiveScopedAuthority`, `PinnedOperationAuthority`, `LiveActingAuthorization` and `AccountRequestProjects`
- Test:
  - `plowshare-server/src/test/java/io/aeyer/plowshare/server/access/ScopedRolePolicyTest.java` (unit)
  - `.../access/ProjectCreatorSchemaTest.java` (`@Tag("full-db")`)
  - `.../access/IdentityMigrationTest.java` (`@Tag("full-db")`)

**Interfaces:**
- Produces:
  - `BuiltInIdentities.PROJECT_CREATION` (`ScopeId`, id 11) and `BuiltInIdentities.PROJECT_CREATOR` (`RoleId`, id 12), both in `RESERVED`;
  - `StandardRole.PROJECT_CREATOR`;
  - `AuthorityAction.PROJECT_CREATE`;
  - `record AuthorityScope.ProjectCreation(ScopeId id) implements AuthorityScope`;
  - SQL `authority_may_create_projects(handle TEXT) RETURNS BOOLEAN`.

- [ ] **Step 1: Write the failing tests**

- `ScopedRolePolicyTest`:
  - `project_creator_is_not_assignable_through_scoped_role_management`: `RoleGrantPolicy.effective(PROJECT_CREATOR, …)` throws `CallerFault`.
  - `project_creator_issues_nothing`: `effective(READER, …, issuingRole = PROJECT_CREATOR)` throws `CallerFault("Issuing grant cannot delegate that role")`.
- `ProjectCreatorSchemaTest`:
  - `migration_registers_creator_role_scope_and_single_action`: one role row with id …0c; its action set is exactly `{PROJECT_CREATE}`; one scope row of kind `PROJECT_CREATION` with id …0b; `authority_builtin_registry_valid()` is true.
  - `creator_grant_requires_admin_exercised_grant`: an insert exercised from a MANAGER membership grant fails with `Role exceeds issuing authority`. Exercised from the admin's live `ADMIN_CONTROL` grant at ADMINISTRATION, it succeeds.
  - `creation_scope_accepts_only_creator_grants`: inserting READER, CONTRIBUTOR, MANAGER or ADMIN at the scope fails. A `PROJECT_CREATOR` grant anywhere else fails.
  - `creator_grant_recipient_must_be_a_user_without_agent`: a SERVICE subject fails; `subject_agent` set fails.
  - `one_live_creator_grant_per_account`: a second live insert for the same subject violates the unique index.
  - `may_create_projects_function`: true for a live holder; false after revoke, after expiry, for a disabled account, for a bootstrap account and for a non-holder admin.
  - `creator_grant_confers_nothing_else`: a holder who is not an admin gets false from `JdbcProjectMembers.isServerAdmin`, `mayAdminister` on any project and `mayUse` on any existing project. `ScopedAuthority.allowsUsing` is false for `MANAGE_ACCESS` and `ADMINISTER` at both the ADMINISTRATION and PROJECT_CREATION scopes.
- `IdentityMigrationTest`: the existing `verifyBuiltIns` test passes after V262.

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :plowshare-server:test --tests 'io.aeyer.plowshare.server.access.ScopedRolePolicyTest'`, then `./gradlew :plowshare-server:test -PfullDb --tests 'io.aeyer.plowshare.server.access.ProjectCreatorSchemaTest' --tests 'io.aeyer.plowshare.server.access.IdentityMigrationTest'`.
Expected: FAIL (compile errors for the missing constants; no V262 yet).

- [ ] **Step 3: Write V262**

Per the spec's "Authority model" section:
- Extend these: the action CHECK; the kind CHECK; the unnamed `builtin IN (...)` column CHECK and the unnamed built-in shape CHECK (find both by definition, as V216:20-42 does); `authority_scope_kind_shape` (`account_id` = SYSTEM, `project_id` NULL); `authority_role_registry_shape`; `authority_reserved_id`; `authority_grants.requested_role`; the V156 reserved-identity collision guard for …0b and …0c.
- Insert the scope, the role, and the role action `(…0c, 1, 'DEFAULT', 'PROJECT_CREATE')`.
- Redefine `authority_builtin_registry_valid()`: role count 4 → 5 (add `PROJECT_CREATOR`), non-GLOBAL_READ action rows 26 → 27, built-in scopes +1.
- In `authority_grant_insert()`, add a branch for `effective = 'PROJECT_CREATOR'` or `destination.kind = 'PROJECT_CREATION'`. It requires all of:
  - both conditions hold together;
  - `subject_kind = 'USER'` and `NEW.subject_agent IS NULL`;
  - `NEW.coverage = 'SELF'` and `provenance = 'ROLE'`;
  - `exercised_grant` names a live `ADMIN_CONTROL` grant with role ADMIN at the ADMINISTRATION scope, held by `NEW.issuer_account`, and that issuer is an active non-bootstrap USER with `server_admin`.

  Otherwise raise `Role exceeds issuing authority`. The branch replaces the general ceiling and coverage checks for this role only.
- Add a partial unique index on `(subject_account)` where `scope_id` is the creation scope and `revoked_at IS NULL`.
- Define `authority_may_create_projects(handle TEXT)`: EXISTS a live (`authority_live_grants()`) `PROJECT_CREATOR` grant at the creation scope whose subject maps to `handle` with `account_active(handle)`, `NOT bootstrap` and `account_kind = 'USER'`.

- [ ] **Step 4: Implement the Java vocabulary**

- `JdbcIdentityRepository.read`: `case "PROJECT_CREATION" -> new AuthorityScope.ProjectCreation(id)`.
- Every scope switch denies every action on `ProjectCreation`.
- `RoleGrantPolicy`: add `PROJECT_CREATOR` to the not-assignable refusal and to the issuing `case CONTRIBUTOR, READER, GLOBAL_READ, PROJECT_CREATOR -> false`.

- [ ] **Step 5: Run tests to verify they pass**

Run both Step 2 commands, plus `./gradlew :plowshare-server:test`.
Expected: PASS.

- [ ] **Step 6: Format and commit**

```bash
./gradlew format formatCheck
git add plowshare-server/src/main/resources/db/migration/V262__project_creator_role.sql plowshare-server/src/main/java/io/aeyer/plowshare/server/access plowshare-server/src/test/java/io/aeyer/plowshare/server/access
git commit -m "feat(authority): add PROJECT_CREATOR role on an isolated PROJECT_CREATION scope"
```

### Task 6: Creator check, snapshot mirror and grant/revoke service

**Files:**
- Create:
  - `plowshare-server/src/main/java/io/aeyer/plowshare/server/access/ProjectCreation.java`
  - `.../access/CachedProjectCreation.java`
  - `.../access/ProjectCreatorGrants.java`
  - `.../access/JdbcProjectCreatorGrants.java`
- Modify:
  - `.../access/AuthoritySnapshot.java` (add `projectCreator`)
  - the Spring configuration that wires `CachedProjectMembers` (same file; add beans)
  - `.../auth/ServerAdministration.java` (`Account`, `update`, `list`)
  - `.../auth/AccountAdministrationRepository.java` and its JDBC implementation (list column)
  - `.../ws/AdminFrames.java` (`ADMIN_ACCOUNT_UPDATE` passes `flag(p, "projectCreator")`)
- Test:
  - `.../access/CachedProjectCreationTest.java` (unit)
  - `.../auth/ServerAdministrationTest.java` (unit; create if absent)
  - `.../access/ProjectCreatorGrantsDatabaseTest.java` (`@Tag("full-db")`)
  - `.../access/AuthoritySnapshotDifferentialDatabaseTest.java` (add a case)

**Interfaces:**
- Consumes: `authority_may_create_projects`; V262 grant branch.
- Produces:
  - `public interface ProjectCreation { boolean mayCreate(String handle); }`. Null or blank → false.
  - `public boolean AuthoritySnapshot.projectCreator(String handle)`, mirroring `authority_may_create_projects` (V262).
  - `public interface ProjectCreatorGrants { boolean holds(String handle); void grant(String actor, String handle); void revoke(String actor, String handle); }`. `grant` and `revoke` are idempotent. Both throw `CallerFault("Only a server administrator may manage server resources")` for a non-admin actor.
  - `ServerAdministration.Account` gains `boolean projectCreator`.
  - `ServerAdministration.update(String actor, String handle, Boolean enabled, Boolean serverAdmin, Boolean projectCreator)`.

- [ ] **Step 1: Write the failing tests**

- `CachedProjectCreationTest`:
  - `answers_from_snapshot_when_available`
  - `falls_back_to_repository_without_snapshot`
  - `null_or_blank_handle_is_false`
- `ServerAdministrationTest`:
  - `update_grants_and_revokes_project_creator`: with a mocked `ProjectCreatorGrants`, `update(admin, "sam", null, null, true)` calls `grant(admin, "sam")`; `false` calls `revoke`; `null` calls neither. The audit entry records the change.
  - `non_admin_cannot_change_project_creator`
- `ProjectCreatorGrantsDatabaseTest`:
  - `admin_grants_and_audit_names_admin`: the ISSUE audit row has `actor_account` = the admin and `authority` = the admin's `ADMIN_CONTROL` grant.
  - `creator_grant_survives_issuer_demotion_and_disable`: grant, then set the issuer's `server_admin = FALSE`, then `enabled = FALSE`. `authority_may_create_projects('sam')` stays true.
  - `any_admin_may_revoke`: a second admin revokes; `holds` becomes false.
  - `grant_is_idempotent`
- Differential test: `projectCreator` agrees with `authority_may_create_projects` for every generated account.

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :plowshare-server:test --tests '*CachedProjectCreationTest' --tests '*ServerAdministrationTest'`
Expected: FAIL (types missing).

- [ ] **Step 3: Implement**

- `JdbcProjectCreatorGrants`: in one transaction, lock the creation scope row, then read the actor's live `ADMIN_CONTROL` grant at ADMINISTRATION.
  - `grant` inserts the `ROLE` grant with `issuing_grant` = root and `exercised_grant` = that grant.
  - `revoke` sets `revoked_by` = actor and `revoking_grant` = that grant.
  - `holds` calls `authority_may_create_projects`.
- `CachedProjectCreation` follows the `CachedProjectMembers.decide` pattern.
- `ServerAdministration.list` reads `projectCreator` from `ProjectCreatorGrants.holds` in one batched query per listing, not per row. Add `holdersAmong(Collection<String>)` to the repository if needed.

- [ ] **Step 4: Run tests to verify they pass**

Run the Step 2 command, then `./gradlew :plowshare-server:test -PfullDb --tests '*ProjectCreatorGrantsDatabaseTest' --tests '*AuthoritySnapshotDifferentialDatabaseTest'`.
Expected: PASS.

- [ ] **Step 5: Format and commit**

```bash
./gradlew format formatCheck
git add plowshare-server/src
git commit -m "feat(admin): grant and revoke the project creator role"
```

### Task 7: SDK contract and admin clients

**Files:**
- Modify:
  - `sdk/typescript/src/operations/payload-validation.ts` (`'admin.account.update': [['handle'], ['enabled', 'serverAdmin', 'projectCreator']]`)
  - `sdk/typescript/src/operations/conversation-replies.ts` (`ServerAccount.projectCreator: boolean`)
  - `sdk/typescript/src/operations/discovery.ts` (example)
  - `plowshare-desktop/src/renderer/server-admin.ts`
- Regenerate: `sdk/typescript/src/operations/operation-schemas.ts`, native SDK contracts, `plowshare-desktop/src/request-schemas.ts`
- Test: `sdk/typescript/src/operations/commands.test.ts`, `plowshare-desktop/src/renderer/*.test.ts` covering server-admin (create `server-admin.test.ts` if absent)

**Interfaces:**
- Consumes: Task 6's wire field `projectCreator` on `admin.account.update` and on each account in `admin.accounts`.

- [ ] **Step 1: Write the failing tests**

- `commands.test.ts`: `admin.account.update` with `{handle:'sam', projectCreator:true}` validates; `projectCreator:'yes'` is refused.
- Desktop: the account detail shows `May create projects` or `Cannot create projects`. Clicking `[data-creator]` sends `admin.account.update` with `projectCreator` toggled.

- [ ] **Step 2: Run to verify they fail**

Run: `cd sdk/typescript && pnpm test`, then `cd plowshare-desktop && pnpm test`.
Expected: the new tests FAIL.

- [ ] **Step 3: Implement and regenerate**

- Add the field, then run `node sdk/typescript/scripts/generate-operation-schemas.mjs`, `node scripts/generate-sdk-contracts.mjs`, and the desktop schema generation (`--desktop`) as documented in the generated file header.
- Desktop: add `['[data-creator]', 'projectCreator']` to the toggle list beside `[data-role]`, a `<button data-creator>` in the detail markup, and status text.
- The CLI needs no code: `admin account update '{"handle":"sam","projectCreator":true}'` works through the catalog. Add that example to `plowshare-cli/src/options.ts` help.

- [ ] **Step 4: Run tests to verify they pass**

Run the Step 2 commands, plus `pnpm run typecheck` in both packages.
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add sdk/typescript plowshare-desktop/src plowshare-cli/src scripts sdk
git commit -m "feat(clients): show and toggle the project creator role"
```

### Task 8: Creating a project by rooting a folder

**Files:**
- Create: `plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/ProjectRegistry.java`
- Modify:
  - `.../archive/ProjectStore.java` (implements `ProjectRegistry`; `rootOn(String,String,String,String)`; new private `withSoleCreator`)
  - `.../ws/FileChannelHandler.java` (constructor, `declared`)
  - `.../ws/FileChannelConfig.java` (wiring)
  - `.../agents/AgentsConfig.java` (a `ProjectRegistry` bean if not exposed through `ProjectStore`)
- Test:
  - `plowshare-server/src/test/java/io/aeyer/plowshare/server/ws/ProjectMembershipTest.java` (unit)
  - `.../archive/ProjectMembersTest.java` (`@Tag("full-db")`)

**Interfaces:**
- Consumes: `ProjectCreation.mayCreate` (Task 6), `authority_may_create_projects` (Task 5).
- Produces:
  - `public interface ProjectRegistry { boolean registered(String project); }`: true when any `projects` row has that name.
  - `FileChannelHandler(SessionRegistry, PresenceRegistry, ProjectRoots, ProjectMembers, ProjectCreation, ProjectRegistry)`. Keep the deadline overloads, adding the two parameters to each.

- [ ] **Step 1: Write the failing tests**

- `ProjectMembershipTest`:
  - `creator_roots_an_unregistered_project`: `mayUse` false, `registered` false, `mayCreate` true. The claim is served and `roots.rootOn` is called.
  - `non_creator_is_refused_for_unregistered_project`: reason `this account may not root 'ledger'`, code 1008, no `rootOn` call.
  - `creator_is_refused_for_existing_project_without_membership`: `registered` true, `mayUse` false, `mayCreate` true. Code 1008 with the same reason.
  - `creation_refusal_withdraws_presence`: `rootOn` throws `ArchiveRefusedException`. Code 1003, and `presences.serving("ledger")` is empty.
  - `creation_outage_roots_nothing`: `rootOn` throws `ArchiveUnavailableException` while creating. The socket stays open, the presence is empty, and the ready frame is `{"ready":true}`.
- `ProjectMembersTest`:
  - `creator_becomes_sole_manager`: grant `carol` the creator role; `rootOn("sundial", "laptop", "/repo", "carol")`; members are `["carol"]` with role MANAGER.
  - `second_concurrent_creator_is_refused`: two threads, `carol` and `dave` (both creators), same name and machine. Exactly one succeeds; the other throws `ArchiveRefusedException`; members has one entry.
  - `member_reroots_after_creator_role_revoked`: `carol` creates, the role is revoked, then `rootOn` again from another root on the same machine succeeds.
  - `orphan_row_stays_admin_only`: a `projects` row with no members, created by `ProjectIds.toWrite`. A creator gets `ArchiveRefusedException`; a server admin adopts it, as today.
  - `creator_cannot_create_reserved_or_unnormalised_names`: `" sundial"`, `"personal:x"` and `"client:x"` are refused, and no row is created.
  - Update `creators_are_members_and_failed_writes_roll_back_membership_and_project`: grant `bob` the creator role before `rootOn("remote", …, "bob")`; `"unknown"` is still refused.

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :plowshare-server:test --tests 'io.aeyer.plowshare.server.ws.ProjectMembershipTest'`
Expected: FAIL (constructor and behaviour).

- [ ] **Step 3: Implement**

- **`declared`:** permit when `visible && (members.mayUse(project, handle) || (!registry.registered(project) && creation.mayCreate(handle)))`. When the permit came from creation, catch `ArchiveUnavailableException` from `roots.rootOn`, withdraw the presence and return true.
- **`rootOn` with a handle, non-server-project branch**, replacing today's `if (recorded.isEmpty()) requireServerAdmin`:
  - no `projects` row: `ordinary(name)`, then require `authority_may_create_projects(handle)` (else `ArchiveRefusedException("This account may not create projects")`), then `withSoleCreator`;
  - a row with no members: `requireServerAdmin`, then `withCreator`, as today;
  - a row with members: require `JdbcProjectMembers.mayUse(name, handle)` (else `ArchiveRefusedException("This account may not attach this project")`), then `rootOn(name, machine, root)`.
- **`withSoleCreator`:** `withCreator`, then, inside the same transaction after the conditional insert, assert that `project_members` holds `(id, handle)`. Otherwise throw `ArchiveRefusedException("Another account created this project first")`, which rolls back.

- [ ] **Step 4: Run tests to verify they pass**

Run the Step 2 command, then `./gradlew :plowshare-server:test -PfullDb --tests 'io.aeyer.plowshare.server.archive.ProjectMembersTest'`, then `./gradlew :plowshare-server:test`.
Expected: PASS.

- [ ] **Step 5: Format and commit**

```bash
./gradlew format formatCheck
git add plowshare-server/src
git commit -m "feat(projects): creators create a project by rooting an unregistered folder"
```

### Task 9: Explain a refused folder claim in the TUI and desktop

**Files:**
- Modify:
  - `sdk/node/src/rooter.ts` (throw `RootRefused`)
  - `plowshare-tui/src/logic/wording.ts` (`describeRootRefused`)
  - `plowshare-tui/src/view/main.ts:4223` (pass the code)
  - `plowshare-desktop/src/files.ts` (`root`)
- Test:
  - `sdk/node/src/rooter.test.ts` (create if absent)
  - `plowshare-tui/src/view/composition.test.ts` (existing `annex` refusal case)
  - `plowshare-desktop/src/workspace.test.ts` (`a server refusal of a newly chosen folder…`)

**Interfaces:**
- Produces:
  - `export class RootRefused extends Error { readonly code: number | undefined }` from `sdk/node/src/rooter.ts`. `message` keeps today's text.
  - `export const PROJECT_CREATOR_HINT = 'If this is a new project, a server administrator must grant you the project creator role.'`
  - `describeRootRefused(project: string, reason: string | undefined, code?: number): string`

- [ ] **Step 1: Write the failing tests**

- Rooter: a 1008 close rejects with `RootRefused` whose `code === 1008`.
- TUI: `describeRootRefused('annex', "this account may not root 'annex'", 1008)` ends with `PROJECT_CREATOR_HINT`. With code 1003 it does not.
- Desktop: the refusal fixture closes with 1008. The rejection message contains `PROJECT_CREATOR_HINT`, and no marker is written.

- [ ] **Step 2: Run to verify they fail**

Run `pnpm test` in `sdk/node`, `plowshare-tui` and `plowshare-desktop`.
Expected: the new assertions FAIL.

- [ ] **Step 3: Implement**

The rooter throws `RootRefused(refused.reason ?? <today's text>, refused.code)`. The TUI and desktop append `PROJECT_CREATOR_HINT` on a new line when `code === 1008`.

- [ ] **Step 4: Run tests to verify they pass**

Run the Step 2 commands, plus `pnpm run typecheck` in each package.
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add sdk/node/src plowshare-tui/src plowshare-desktop/src
git commit -m "feat(clients): explain a refused project folder claim"
```

### Task 10: Documentation and ADR

**Files:**
- Create: `docs/decisions/0069-role-exercised-issuance-and-project-creator.md`
- Modify:
  - `docs/server-administration.md` ("Project access and Personal scopes": the role, who issues it, what it does not grant, the role-exercised issuance rule)
  - `docs/projects.md` (creating a project by rooting a folder; the refusal hint)
  - `docs/role-scope-permissions-proposal.md` (server matrix row for project creation)

- [ ] **Step 1: Write the ADR**

Cover: the isolated scope rather than ADMINISTRATION (and why); role-exercised issuance, meaning the role is permanent and the authority to exercise it temporary; agent and token issuers staying bound; the re-root migration's no-resurrection rule; the removal of the issuer expiry cap; the alternatives rejected in the spec.

- [ ] **Step 2: Verify**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL (Java, Javadoc, formatting and client checks).

- [ ] **Step 3: Commit**

```bash
git add docs
git commit -m "docs: project creator role and role-exercised issuance"
```
