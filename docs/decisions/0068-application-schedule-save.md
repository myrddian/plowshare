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
    request id and a receipt.
  - The destination, writable areas, placement, service identity and
    `plowshare.json` are unchanged. The manifest is copied, never re-resolved.
  - The deployment history therefore records every timing change.
  - The deployment store admits this schedule-only revision for a project Manager.
    Every other deployment still requires server administration.
- **Any other Application root (DISJOINT, or STANDARD with an Application
  manifest):** the file is written **in place** in the registered root's
  `schedules/` folder, with an atomic temporary file and rename.
  - The existing `safe()` path checks apply: the path must stay inside the root
    and no segment may be a symlink.
  - So do the size and count limits.

In both cases:

- `overwrite: false` refuses an existing file and `overwrite: true` replaces it,
  as for other sources.
- The cron is parsed and the actions are validated before anything is written.
- The source is reconciled immediately, and the effective file is returned.
- As today, a changed definition clears any pause override (ADR 0014). The
  saved `definition.paused` value then applies.
- Every Application schedule save writes an `admin_audit` row: actor,
  `schedule.save.application`, and target `<project>/<name>`, plus the revision
  for MANAGED projects.

Unchanged:

- `schedule.forget` and deletes of Application schedules are still refused.
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
