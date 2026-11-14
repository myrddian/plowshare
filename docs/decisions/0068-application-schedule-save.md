# ADR 0068: Project managers save Application schedules

Status: accepted. The owner explicitly authorized this platform change after
`schedule.save` refused a hand-registered Application's schedule. That left no
supported way to change its timing: the refusal pointed at a new deployment
revision, and `application.deploy` refuses hand-registered (DISJOINT) projects.

## Platform need

An Application's packaged schedule (`schedules/<name>.json`) is the only place its
timing, actions and initial pause state are defined. Operators must be able to
change them through the public API on every kind of install.

Today `RegisteredScheduleFiles.write` refuses every Application-owned server
source ("Update Application schedules through a new deployment revision"). That
leaves:

- **DISJOINT and other hand-registered Applications:** no API route at all. The
  only route is an out-of-band file edit on the server host.
- **MANAGED Applications:** a full `application.deploy` of every file by a server
  administrator with FileStore Manager access. That is far more authority than a
  schedule change needs.

`schedule.pause` (ADRs 0014 and 0020) controls runtime state only, never timing.

## Decision

`schedule.save` with `source: "server"` on a project whose schedules folder is an
Application source root now succeeds for a **current project Manager**. Server
administration is not required. The Manager need not be the account that
registered the schedule source. The save never changes that registration: the
existing `schedule_sources` row and its owner stay as they are, and the
definition's actions are still validated under the registered owner
(`ScheduleActionAuthority`), as today.

How the change lands depends on the project type:

- **MANAGED (deployment-managed):** the save creates a **new deployment revision**.
  - The revision is the active revision's retained files byte for byte, with only
    `schedules/<name>.json` added or replaced.
  - It is staged and validated like any deployment and committed through the
    existing deployment store, under the head-revision fence, with a generated
    request id and a receipt. The deployment limits apply: 128 files, 64 KiB per
    file and 128 KiB total.
  - The destination, writable areas, placement, service identity and
    `plowshare.json` are unchanged. The manifest is copied, never re-resolved.
    Acting grants are re-bound to the new head from the unchanged manifest.
  - The deployment history therefore records every timing change.
  - Like every revision change, the save is refused while a contained Application
    beneath the project is live (`SourceContainmentFence`, ADR 0042). A
    schedule-only revision is not exempt: the fence protects the revision that
    authorized the child, and the Manager destroys the child first.
  - The deployment store itself enforces the invariant under the project lock:
    only `schedules/*.json` differ from the head, `plowshare.json` and the service
    identity are byte-identical to the head, and the caller is a current project
    Manager. A violation is refused with "A schedule-only revision may change only
    schedules/*.json". Every other deployment still requires server administration.
    A later administrator deploy or rollback works as before.
- **Any other Application root (DISJOINT, or STANDARD with an Application
  manifest):** the file is written **in place** in the registered root's
  `schedules/` folder, with an atomic temporary file and rename.
  - The existing `safe()` path checks apply: the path must stay inside the root
    and no segment may be a symlink.
  - So do the size and count limits: 64 KiB per file, 256 files, 1 MiB.
  - `schedules/` is created inside the root if missing, and the real path is
    re-checked before the temporary file is created and again before the move.

In both cases:

- `overwrite: false` refuses an existing file and `overwrite: true` replaces it,
  as for other sources.
- The cron is parsed and the actions are validated before anything is written.
- The save is refused before writing if the registered owner has lost execution
  access, with the same message reconcile gives. With no registered source the
  refusal is "No registered schedule source for this Application".
- The source is reconciled immediately, and the effective file is returned.
- As today, a changed definition clears any pause override (ADR 0014). The
  saved `definition.paused` value then applies.
- Every Application schedule save writes an `admin_audit` row: actor,
  `schedule.save.application`, and target `<project>/<name>`, plus `@<revision>`
  for MANAGED projects. The row is written after the commit (or in-place write)
  and reconciliation, in its own transaction, not in the deployment commit. A
  crash in that window leaves no audit row: for MANAGED the deployment receipt,
  which records the account, is then the record; an in-place write has no other
  record. A save whose commit outcome is uncertain is not audited and is never
  retried; the caller inspects state before deciding.
- The saved `paused` replaces the current Pause/Resume state, so a definition
  copied from a package that ships paused stops a running schedule unless the
  caller sets `"paused": false`.
- A later `application.deploy` from a source tree that still holds the old file,
  or a DISJOINT pipeline's next sync, replaces the saved schedule. Operators
  carry the change into their source tree.

Unchanged:

- `schedule.forget` and deletes of Application schedules are still refused: "Application
  schedules cannot be deleted; save a replacement definition instead".
- Files other than `schedules/*.json` are never written by this path.
- Workspace and global schedules behave as before.
- The refusal message for any path that still refuses names the route that
  works.

## Alternatives considered

- **A timing override beside the pause override.** It keeps files immutable, but
  it creates a second source of truth for timing that a deployment silently
  discards. Owners asked for `schedule.save` to work.
- **Writing in place for MANAGED projects too.** This breaks the retained
  revision's digest and lets the next deployment silently revert the change.
  Rejected.
- **Requiring server administration** (as `application.deploy` does). This keeps
  the gap for the people who actually operate an Application. Rejected: a
  schedule-only revision cannot change code, agents, grants or identity.

## Compatibility and tests

- The protocol and SDK contracts are unchanged: same operation, same payload,
  same reply.
- Clients that already call `schedule.save` (CLI, TUI, desktop) work for
  Application projects without changes.
- No migration is needed: existing tables (`application_releases`, heads,
  receipts, `admin_audit`) hold everything.
- Unit tests cover:
  - authority: Manager admitted; Contributor, non-member and service tokens
    refused;
  - DISJOINT in-place writes and refusals;
  - the MANAGED schedule-only revision: identical other files, copied manifest,
    head fence;
  - the audit row.
- A `full-db` test covers the MANAGED commit and fence against PostgreSQL,
  because it depends on the project-row lock and the receipt tables.
