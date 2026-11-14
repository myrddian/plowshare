# Application `schedule.save` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a project Manager use `schedule.save` on an Application's schedule.
- On a MANAGED project, the save becomes a schedule-only deployment revision.
- On any other Application root (DISJOINT, hand-registered), the file is written in place.

**Architecture:**
- `FileScheduleDefinitions.save` detects an Application-owned server source and takes a new branch. That branch checks Manager authority against the registered source (`store.sourceOf`), not `enroll`. It validates the definition under the registered owner, then writes.
- For a non-MANAGED project, the write goes through a new `RegisteredScheduleFiles.writeApplication`.
- For MANAGED, it goes through a new `ApplicationScheduleRevisions` port. Its implementation, in the applications package, rebuilds the active revision with one file replaced and commits it through the existing deployment store. A new Manager-gated `ProjectStore.activateScheduleRevision` admits it.
- An audit port records each save.
- The protocol is unchanged.

**Tech Stack:** Java 21, Spring Boot, Spring JDBC (repositories only), JUnit 5 with Mockito, and Testcontainers behind `@Tag("full-db")`.

**Spec:** `docs/decisions/0068-application-schedule-save.md`. This is the binding decision. It amends ADR 0014 ("deployed source writes refused") for `schedules/*.json` saves only.

Paths below use these abbreviations:
- `S` = `plowshare-server/src/main/java/io/aeyer/plowshare/server`
- `T` = `plowshare-server/src/test/java/io/aeyer/plowshare/server`

## Global Constraints

**Authority**
- A save on an Application source requires a current project Manager (`members.mayManage`), and nothing more. Server admin is not required.
- Contributors, non-members and service tokens are refused. Service tokens stay excluded from `schedule.save` in `ProjectAuthorization`.
- The registration is never changed: never call `store.register`/`enroll` on this branch. Use the existing registered source (`store.sourceOf`/the registered source for the project), and refuse with `No registered schedule source for this Application` if none exists.
- Actions are validated with `ScheduleActionAuthority.validate(source, definition)` under the registered owner. The cron is parsed with `CronSchedule.parse(...).nextAfter(now)`. Both happen before any write.

**What may be written**
- Only `schedules/<name>.json`, with `name` passing the existing `identity()` rule.
- The existing limits apply: 64 KiB per file, 256 files, 1 MiB total.
- `overwrite=false` refuses an existing file with `A schedule named <name> already exists`. `overwrite=true` replaces it.

**MANAGED revisions**
- A revision is the active revision's retained files, byte for byte, plus `schedules/<name>.json` added or replaced.
- `plowshare.json` is copied verbatim. Never call `resolveServiceManifest` on it.
- Destination, writable areas and placement are taken from the active release.
- Commit under the existing head fence (`expectedRevision` = the active head read at the start).
- Use a generated `requestId` with the usual receipt. If the head moved, refuse with the existing `Application revision changed…` message.

**Non-MANAGED writes**
- In place, atomic: write a temp file in the folder, then `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`. Use the existing `safe()` checks: the path stays inside the root and no segment is a symlink.

**After the write**
- Immediately `requireComplete(reconcile(source))` and return `store.snapshot(...).effective()`.
- A changed definition clears the pause override. This is existing behaviour; keep it.

**Audit**
- Every Application schedule save records `admin_audit(actor, "schedule.save.application", "<projectName>/<name>")`. MANAGED saves append `@<revision>` to the target.
- Record through a narrow injected interface. No SQL outside repositories.

**Unchanged**
- `schedule.forget`/delete of Application schedules stays refused.
- Workspace and global schedules behave exactly as before.
- The refusal text that remains (delete) becomes `Application schedules cannot be deleted; save a replacement definition instead`.

**Project rules**
- Interfaces first, constructor injection, and repositories own JDBC (AGENTS.md).
- Google Java Format (`./gradlew format`, `formatCheck`).
- Mock DB dependencies by default. Use full-db only for the commit and fence test in Task 2, with the reason stated.

**Commands**
- `./gradlew :plowshare-server:test --tests '<FQN>'`
- `./gradlew format formatCheck`
- full-db: `./gradlew :plowshare-server:test -PfullDb --tests '<FQN>'`

## Review Focus

1. A Manager who is not the account that registered the source saves a schedule. It succeeds without touching `schedule_sources`. This is today's `enroll` "registered to another account" trap. Test in Task 1.
2. A MANAGED save races a concurrent deployment, so the head moved. It is refused with no partial revision visible, and the staged directory is cleaned up. Test in Task 2 (full-db).
3. A schedule definition that grants actions the registered owner lacks is refused before any write or revision. Test in Task 1 and Task 2.
4. DISJOINT root with `schedules/` missing, or `schedules/` as a symlink. The folder is created inside the root when it is missing, and a symlink is refused. Test in Task 1.
5. Service-token caller. Refused at admission, unchanged. Test in Task 1.

---

### Task 1: Manager saves on non-MANAGED Application roots (in place) + audit

**Files:**
- Modify: `S/events/FileScheduleDefinitions.java` (save ~34-52): add the Application branch.
- Modify: `S/events/RegisteredScheduleFiles.java` (~85-144):
  - add `writeApplication(ScheduleSource source, String name, String text, boolean overwrite)`;
  - the existing `write` keeps refusing Application roots;
  - update the delete refusal text.
- Modify: `S/events/ScheduleFiles.java` (the interface): declare `writeApplication`.
- Create: `S/events/ApplicationScheduleAudit.java`, an interface with `void saved(String actor, String target)`.
  - Implement it in the existing repository that owns `admin_audit`, `S/auth/JdbcServiceAccountRepository.java`, or a small new `JdbcApplicationScheduleAudit` beside it.
  - Wire it in the events configuration (`S/events/EventsConfig.java`).
- Create: `S/events/ApplicationProjects.java`, an interface:
  - `Optional<ApplicationProject> find(UUID projectId)`;
  - `record ApplicationProject(String name, ProjectType type)`.
  - Implement it over `ProjectWorkspaces`/`ProjectRecord.type()`, so `FileScheduleDefinitions` does not read project rows itself.
- Test:
  - `T/events/ScheduleDefinitionsTest.java`: extend it.
  - `T/events/RegisteredScheduleFilesTest.java`: extend it.

**Interfaces:**
- Produces:
  - `ScheduleFiles.writeApplication(...)`
  - `ApplicationScheduleAudit.saved(actor, target)`
  - `ApplicationProjects.find(projectId)`
  - `ApplicationScheduleRevisions`, declared in Task 2. Leave the MANAGED branch throwing `UnsupportedOperationException("MANAGED Application schedule saves arrive in the next task")`. Task 2 replaces it.

- [ ] **Step 1: Write failing tests.**
  - `ScheduleDefinitionsTest` (mocks):
    - `managerSavesDisjointApplicationScheduleInPlace`: `writeApplication` is called with the encoded definition; `register` is never called; `reconcile` runs; audit `saved(caller, "<project>/<name>")`.
    - `managerWhoIsNotTheRegistrarCanSave`: the registered source owner differs from the caller, and it succeeds.
    - `contributorCannotSaveApplicationSchedule`: refused before any write.
    - `missingRegisteredSourceIsRefused`: exact message.
    - `actionsTheOwnerLacksAreRefusedBeforeWriting`: `ScheduleActionAuthority` throws, and `writeApplication` is never called.
    - `invalidCronIsRefusedBeforeWriting`.
    - `workspaceAndGlobalSavesUnchanged`: the existing path is used.
  - `RegisteredScheduleFilesTest` (`@TempDir`):
    - `applicationWriteReplacesAtomicallyWithOverwrite`
    - `applicationWriteRefusesExistingWithoutOverwrite`: exact message.
    - `applicationWriteCreatesMissingSchedulesFolderInsideRoot`
    - `applicationWriteRefusesSymlinkedSchedulesFolder`
    - `applicationWriteEnforcesSizeLimit`
    - `deleteStillRefusedWithNewMessage`
    - `deployedWriteStillRefusesThroughOrdinaryWrite`: update the existing test, which asserts only the type.
  - Admission:
    - `serviceTokensStillCannotSaveSchedules`, in `T/access/ServiceScheduleAuthorizationTest.java`.
- [ ] **Step 2: Run them.** `./gradlew :plowshare-server:test --tests '*ScheduleDefinitionsTest' --tests '*RegisteredScheduleFilesTest' --tests '*ServiceScheduleAuthorizationTest'`. Expected: FAIL.
- [ ] **Step 3: Implement.** Follow the Global Constraints.
  - Detect an Application-owned server source with the same predicate as `applicationOwned`.
  - Branch on `ApplicationProjects.find(projectId).type() == MANAGED`.
  - Document the transaction and ordering in the `save` Javadoc: validate, then write, then reconcile, then audit. A crash between the write and reconcile is recovered by the ticker.
- [ ] **Step 4: Run** those tests, then `./gradlew :plowshare-server:test`, then `./gradlew format formatCheck`. Expected: PASS.
- [ ] **Step 5: Commit:** `feat(server): let project managers save hand-registered Application schedules`.

### Task 2: MANAGED schedule-only deployment revision

**Files:**
- Create: `S/applications/ApplicationScheduleRevisions.java`, an interface:
  - `String revise(String caller, UUID projectId, String scheduleName, String text, boolean overwrite)`;
  - it returns the new revision id.
- Modify: `S/applications/SourceApplicationDeployments.java`:
  - implement `ApplicationScheduleRevisions`;
  - read the active release (`store.status`, `store.release`) and the retained files via the existing private `read(Path)`;
  - replace or add the schedule file;
  - stage into `destination.path + "/revisions/" + newUuid` with the same `createDirectory` / `CREATE_NEW|NOFOLLOW` writes;
  - `WorkspaceApplicationPolicy.parse` and `validator.validate`;
  - compute `digest`/`fingerprint`;
  - commit through `store.commit(...)` with a Mutation flagged schedule-only.
  - On any failure, delete the staged directory.
- Modify: `S/archive/JdbcApplicationDeploymentStore.java` (commit ~133-228): when the mutation is schedule-only, call `projects.activateScheduleRevision(...)` instead of `activateDeployment(...)`. Everything else is unchanged: head fence, receipt, release row and head upsert. No schedule `register` call, because `active != null`.
- Modify: `S/archive/ProjectStore.java`:
  - add `activateScheduleRevision(String account, String project, ApplicationPlacement placement, Path root)`;
  - require `type == MANAGED` and a placement identical to the current one;
  - require `members.mayManage(project, account)`;
  - require the root inside the existing destination;
  - then update `workspace` only.
  - `activateDeployment` keeps `requireServerAdmin`.
- Modify: `S/events/FileScheduleDefinitions.java`: replace the Task 1 `UnsupportedOperationException` with `revisions.revise(...)`, then reconcile, then audit with `@<revision>`.
- Test:
  - `T/applications/SourceApplicationDeploymentsTest.java`: extend it.
  - `T/archive/ApplicationDeploymentPersistenceTest.java` (full-db): extend it. Reason: the commit fence relies on the PostgreSQL project-row lock and the receipt tables.
  - `T/events/ScheduleDefinitionsTest.java`: extend it.

**Interfaces:**
- Consumes: Task 1's branch, `ApplicationProjects` and `ApplicationScheduleAudit`.
- Produces: `ApplicationScheduleRevisions.revise(...)`.

- [ ] **Step 1: Write failing tests.**
  - Unit (`@TempDir` and mocked store):
    - `scheduleRevisionCopiesEveryOtherFileByteForByte`
    - `scheduleRevisionCopiesManifestWithoutResolving`
    - `scheduleRevisionAddsNewScheduleFile`
    - `scheduleRevisionRefusesExistingWithoutOverwrite`
    - `scheduleRevisionKeepsDestinationAndWritableAreas`
    - `failedValidationRemovesStagedRevision`
    - `contributorRefused`
  - Unit in `ScheduleDefinitionsTest`: `managerSavesManagedApplicationScheduleAsRevision`. It calls `revise`, then reconcile, and audits the target with `@rev`.
  - Full-db:
    - `managerScheduleRevisionCommitsUnderHeadFence`: a new head, a release row and a receipt; the `schedule_sources` row is unchanged.
    - `scheduleRevisionRefusedWhenHeadMoved`: a concurrent deploy commits first, and nothing new becomes head.
    - `nonManagerCannotActivateScheduleRevision`.
- [ ] **Step 2: Run them.** Unit as above. Full-db: `./gradlew :plowshare-server:test -PfullDb --tests '*ApplicationDeploymentPersistenceTest'`. Expected: FAIL.
- [ ] **Step 3: Implement.** Follow the Global Constraints. Javadoc the fence, cleanup and idempotency.
- [ ] **Step 4: Run** the unit and full-db tests above, then `./gradlew :plowshare-server:test`, then `format formatCheck`. Expected: PASS.
- [ ] **Step 5: Commit:** `feat(server): save MANAGED Application schedules as schedule-only revisions`.

### Task 3: Docs and clients

**Files:**
- Modify: `docs/scheduling.md` (~26-54, ~164-190) and `docs/projects.md` (~116, ~163-168, ~322-324). Describe:
  - Manager `schedule.save` on Application schedules;
  - the MANAGED revision versus the in-place write;
  - the pause-override reset;
  - that delete stays refused;
  - CLI examples (`plowshare-cli --project P --payload - schedule save < request.json`).
- Modify: `docs/decisions/0014-application-schedule-runtime-controls.md`: add a "Superseded in part by ADR 0068 (schedule saves)" line.
- Modify: `integrations/network-privacy/OPERATIONS.md` and `README.md`. The schedule-frequency text says frequency changes need the bootstrap editor or the CLI. Add that a project Manager can change it with `schedule save` on any install. Docs only; the editor is unchanged.
- Test: the existing `./gradlew check`-run doc and contract checks. No new tests unless a docs consistency test lists the decisions.

- [ ] **Step 1:** Update the docs.
- [ ] **Step 2: Run** `./gradlew :plowshare-server:test format formatCheck` plus any docs checks. Expected: PASS.
- [ ] **Step 3: Commit:** `docs: Application schedules can be saved by project managers`.

---

## Not in this plan
- Teaching Privacy Watch's dashboard frequency editor to call `schedule.save` instead of a full admin `application.deploy`. That is a later integration change, and it would also make it work on PROD.
- Deleting Application schedules.
- A UI for `schedule.save` in the web console.
