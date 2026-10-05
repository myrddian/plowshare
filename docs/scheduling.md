# Schedule definitions

Schedule stays in its current desktop Activity tab and TUI commands. New authoring
uses one portable JSON definition: `schedule.save` writes the file and projects it
into the existing durable schedule/trigger/firing runtime. The server owns timers,
next-fire state, jobs, cancellation, approvals and accounting.

Due occurrences now publish to Relay's internal `schedule.due` log. Project file
definitions use their stable project scope; legacy and global definitions use
an explicit server scope. Schedule advancement, publication, firing fan-out,
queue supersession and the built-in subscriber position commit together. A
failure rolls them back, so a claimed occurrence cannot disappear before its
firing rows are recorded. Jobs start after commit through the existing event
dispatcher. Topic retention defaults to four days and cannot remove already
copied firing input. [Relay's log operations and desktop viewer](relay.md) expose
these publications and subscriber positions. The existing schedule and firing
interfaces continue to expose execution status.

## Folders and monitoring

`source: "server"` places files in the project's existing definitions tier under
`schedules/`. `source: "workspace"` uses `.plowshare/schedules/` in the project's
rooted workspace, through its authenticated file provider. This supports local or
remote clients without assuming their filesystem is on the server. Global server
files use the global definitions tier; global workspace folders are unsupported.

Saving from desktop or TUI creates and registers the file automatically. For files
created by hand, register their folder once with `schedule.sync`. The same source
account owns the whole registered project folder; the shared global folder also
has one registered owner. Registration and editing require
project MANAGER access; global schedules require server administration authority.
JSON cannot supply an account identity or grants.

The events ticker scans registered folders before selecting due work, at the
configured events tick interval. It also reconciles before boot recovery drains
queued firings. An unchanged file preserves its next-fire time. Timing changes
calculate the next future fire. Deleting a file removes its schedule and trigger
and refuses waiting firings; historical firings remain. Pause/resume updates JSON
and both runtime rows together. Legacy schedule/trigger definitions still work.

Invalid JSON, lost execution authority or an offline/incomplete remote scan suspends
that source's affected schedules and refuses queued firings. An incomplete scan
never establishes deletion. A complete valid scan recovers the schedule at its next
future fire; missed occurrences are not replayed. Existing runs keep their existing
lifecycle. Inspect `schedule.files` for the path, internal runtime name, active/refused
status and reason. Remote workspace definitions require their owner's connected
provider; server definitions continue without a client. The ticker must be enabled
for automatic monitoring and firing.

Folders are bounded to 256 direct JSON files, 64 KiB per file and 1 MiB per scan.
Files cannot traverse folders or follow symbolic links. Model file tools do not get
access to the hidden schedule folder. The dedicated schedule file purpose serves
only these JSON files.

## Actions and destinations

The version 1 format has timing, an action, a target and optional limits:

```json
{
  "version": 1,
  "cron": "0 0 9 * * MON-FRI",
  "zone": "UTC",
  "paused": false,
  "action": {
    "kind": "skill",
    "agent": "reviewer",
    "name": "review",
    "input": "Review retained sources and report material changes.",
    "mode": "NEW"
  },
  "target": {
    "kind": "mailbox",
    "project": null,
    "conversation": null,
    "to": null,
    "route": null
  },
  "limits": { "maxModelCalls": 50, "maxTurns": 10, "queueCap": 1 }
}
```

Use an existing exported agent and an existing granted command. `agent` actions
use `input` as their task and take no command name or context mode. `skill` and
`orchestration` actions enter the existing qualified bound command path. Skill
modes are INHERITED, SUMMARISED, NEW and DIRECT; a declared mode cannot be overridden,
and a skill without a declared mode requires an explicit selection. Hidden skills
remain explicitly runnable if granted. Orchestrations retain their own approvals,
questions, budgets and lifecycle. Command input is preserved exactly; firing metadata
is not appended to skill arguments. Workspace actions resolve current definitions
through the current authenticated source session; server actions use server tiers.

Targets reuse ordinary authority rules:

- `mailbox`: run in the source project, or an explicitly permitted target project,
  and deliver the event outcome through the existing inbox.
- `conversation`: name an accessible conversation in the source project. It owns
  its home and ceilings; omit target project and model/turn limit overrides.
- `message`: choose `to` (agent, bot or instance) or a configured named `route`.
  A route supplies its project. Cross-project access follows project routing rules;
  the action's agent must match the resolved recipient. Delivery uses existing
  durable private messaging with a stable context and an idempotency key per firing.
  The firing records acceptance of the message task; its inbox receipt names the task and real message ID for
  `message.delivery` inspection of downstream handling, rather than promising the recipient has finished.

Grants, membership and destinations are validated on each scan and before starting.
A schedule does not expand tools or bypass an approval because it is unattended.

## CLI and TUI

The CLI uses the same authenticated WebSocket operations, offline validation and
uncertain-delivery rules as other commands. Create a request file with `name`,
`project`, `source`, `definition` (the JSON above) and optional `overwrite` (false by
default). Then:

```sh
plowshare-cli --project research --payload - schedule save < schedule-request.json
plowshare-cli --project research schedule sync '{"source":"server"}'
plowshare-cli --project research --root /path/to/project schedule sync '{"source":"workspace"}'
plowshare-cli schedule files
plowshare-cli schedule pause '{"schedule":"scheduled-1-weekday","paused":true}'
plowshare-cli schedule forget '{"schedule":"scheduled-1-weekday"}'
```

Use the actual `internalName` returned by `schedule.files` for pause/forget. `--root`
serves workspace files for that CLI session; keep a desktop/TUI or another persistent
owner provider connected for ongoing workspace monitoring. There is no HTTP fallback
or automatic replay after uncertain delivery; inspect file status before deciding
whether to retry.

TUI `/schedule <description>` still reviews timing before saving. It creates a server
schedule file. An explicit qualified skill/orchestration command in the proposal is
saved as a command action. `/schedule save <JSON>`, `/schedule sync <JSON>` and
`/schedule files` expose the full contract, including workspace sources and routing.
Desktop proposals can be edited for timing, action, mode, input, destination and file
source before saving. Existing files can be edited in place; the folder controls
register manually created definitions and display refused-file diagnostics.
