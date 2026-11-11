# Desktop Application hub

Status: implemented in Plowshare Desktop, 8 November 2026. Client UX redesign for
Plowshare Desktop. This document describes the shipped product shape. Verify
claims against current Desktop code; operator manuals in `docs/manual/` and
`docs/projects.md` reflect this behavior.

## Intent

Applications in Desktop today look like a cramped Projects sibling: Deploy and
Add Application sit in the Applications header, rows mix local-folder cues with
server Applications, and Info → **Files** opens local folder claim plus Project
sync — not the Application's server source. The real server file browser already
exists on `#application-panel`, but opening an Application lands on a thin hub
(Runtime / Memory / Boards / Access) that does not present the AI workflow the
Application actually is.

The product shape this design locks is: **an Application is a server-running AI
workflow you already have access to.** Desktop lists those Applications, and
opening one is managing that workflow — conversations, schedules, Relay, and the
files that belong to it — without pretending it is a local checkout.

### Why this, and why now

Operators hit the confusion at the Applications / conversation boundary: admin
actions compete with the list, "Files" means sync, and server-active status is
invisible except inside Deploy. Applications are already contentious; the UI
must stop teaching the wrong model. Server contracts for Application files,
scoped conversations, schedules and Relay already exist. This is a Desktop
composition and labeling problem first, not a new core capability.

## Goals

- List Applications the signed-in account may access; each row reads as
  **Active on the server** (or equivalent honest status), not as a local folder.
- Opening an Application shows a workflow hub: at least **Overview**,
  **Conversations**, **Schedules**, **Relay**, **Files**, and **Access**.
- **Files** means the Application's server source tree (existing
  `application.files` / read / save path). No local folder claim is required.
- Remove **Deploy** and **Add Application** from the Applications sidebar
  heading; place them under **Server administration** (admin-gated as today).
- When an Application is selected, Info must not offer Connect folder / Project
  sync. Sync and local-folder controls remain for client-rooted **Projects**
  (and Personal) only.
- Info may expose an **App files** companion tab while an Application is open so
  Conversations / Schedules / Relay are not buried under a full-pane file
  browser; naming must not collide with Project sync.
- Preserve existing authorization: membership, writable areas, MANAGER rules for
  protected paths, and admin gates for deploy/create. Client chrome is not a
  substitute for server checks.
- Update Desktop-facing docs that still say Deploy lives beside Applications or
  that imply Applications use Info → Files sync.

## Non-goals

- Redesigning the TUI into an Applications hub. TUI remains a cwd-aware slash
  shell over the same server operations; any later copy clarifications are
  separate and optional.
- Expanding or reorganizing the CLI command set.
- Changing Application deploy, activate, create, or union-sync **server**
  contracts. Neither MANAGED nor DISJOINT server Applications participate in
  client union sync ([projects.md](../../projects.md); ownership table in
  [05-projects-and-workspaces.md](../../manual/05-projects-and-workspaces.md)).
- Adding "connect a local checkout" for Applications inside this hub. Attaching
  a checkout for development remains an advanced/external workflow if it exists
  elsewhere; it is not part of the Application interior.
- Nested / contained Applications UX (separate designs).
- Console (`plowshare-console`) parity in this cut.
- Broad visual retheme of Desktop outside the Applications sidebar, Application
  panel, Info tab labeling for Application vs Project, and Server administration
  entry points for Deploy / Add.

## Anti-goals

- Teaching that Applications sync like Projects.
- Leaving Deploy / Add in the Applications header "for convenience."
- Using the label **Files** in Info for Project sync while an Application is
  selected.
- Requiring a local working directory to browse or manage an Application you
  already can access on the server.

## Constraints

- Desktop only for this project.
- Prefer composing existing renderer surfaces and WebSocket operations
  (conversations already scoped by project; schedules and Relay already have
  Desktop views; Application files client already exists). Do not invent parallel
  APIs.
- Follow repository coding standards; no speculative abstractions; keep
  unrelated refactors out of the change.
- Mocked Desktop tests for UI wiring; no `-PfullDb` unless a change truly
  requires PostgreSQL behavior (this design should not).
- Existing Access / project-membership dialogs and deployment dialogs stay the
  authority UI; this design relocates entry points and clarifies the hub.

## Agreed product shape

### Sidebar

- **Applications** section heading is a collapse control only (plus chevron). No
  Deploy / Add text buttons in that row.
- Rows: Applications the account can access. Show server-active status honestly
  (at minimum a clear Active/available-on-server cue; richer revision text when
  already loaded without new server round-trips as a hard requirement).
- Do not surface local-folder presence / sync status as the primary subtitle for
  Applications the way Projects do.

### Application interior (main pane)

Default open view is an **Overview** of the Application as an AI workflow, with
navigation to:

| View | Role |
| --- | --- |
| Overview | Summary tiles / entry points into the surfaces below |
| Activity | Process inventory of every LLM-bearing conversation for this Application; nest own chats; Trajectory only for account-owned logs |
| Schedules | Schedules belonging to this Application (empty state if none) |
| Relay | Relay messages / routes scoped to this Application |
| Files | Server source browser/editor (existing Application files flow) |
| Access | Existing access / membership UI for this Application |

Runtime definitions, Memory and Boards remain reachable if they already are for
Applications, but they are not the primary story of the hub; the builder may
place them as secondary actions on Overview or under an overflow rather than as
peer primary tabs that drown Activity / Schedules / Relay / Files.

Header **Close** returns to the chat stage so the hub does not trap the
operator. Hub **Activity** keeps the Application open: own person chats nest
the existing chat panel; other rows stay inventory metadata until the account
owns the log and can open Trajectory.

### Info panel

| Context | Info tabs |
| --- | --- |
| Application selected | Context, Commands, **App files** (server source companion). No Sync, no Choose folder. |
| Project / Personal selected | Context, Commands, and local folder / sync under a name that means local access (not "Application files"). Today's Project sync stays here. |

Builder call: exact Project-tab label (`Local`, `Folder`, or keep `Files` only
when a Project is selected) as long as Application and Project never share a
misleading identical **Files** meaning on screen at the same time.

### Server administration

- **Deploy** and **Add Application** entry points move here (same dialogs and
  gates as today: admin / connected visibility).
- Everyday Application browsing does not depend on these actions.

## Current code anchors (verify while building)

- Applications header with Deploy / Add:
  [`plowshare-desktop/src/renderer/index.html`](../../../plowshare-desktop/src/renderer/index.html)
  (`#application-deploy-open`, `#server-project-add`).
- Application panel / files:
  [`plowshare-desktop/src/renderer/application.ts`](../../../plowshare-desktop/src/renderer/application.ts),
  [`plowshare-desktop/src/application-files.ts`](../../../plowshare-desktop/src/application-files.ts).
- Info → Files / sync:
  [`index.html`](../../../plowshare-desktop/src/renderer/index.html) `#info-files`,
  [`app.ts`](../../../plowshare-desktop/src/renderer/app.ts) `renderFiles`,
  [`sync.ts`](../../../plowshare-desktop/src/renderer/sync.ts).
- Deploy dialog:
  [`application-deployment.ts`](../../../plowshare-desktop/src/renderer/application-deployment.ts).
- Docs to update when behavior ships: Desktop README; manual
  [`02-clients-and-work.md`](../../manual/02-clients-and-work.md) Deploy-beside-Applications
  wording; [`projects.md`](../../projects.md) Desktop Application workspace section.

## Reference mock

Brainstorm mock accepted 8 November 2026: Application list of accessible
server-active apps; interior Overview with Conversations / Schedules / Relay /
Files tiles; Info companion for App files; no Connect folder; Deploy/Add not in
the Applications header. Session artifacts under
`.superpowers/brainstorm/` (gitignored).

## Left to the builder

- Exact Overview layout (tile grid vs compact summary), empty states, and how
  Schedules / Relay reuse existing activity/relay hosts vs in-panel embeds.
- How Active status is sourced (deployment status already used by Deploy dialog
  vs project list fields already on the wire) without N+1 refresh anti-patterns.
- Whether opening an Application auto-opens Info on App files or leaves Info as
  the user left it.
- Secondary placement of Runtime / Memory / Boards.
- Project Info tab rename spelling.
- Test plan details (renderer/unit and any existing Desktop smoke scripts worth
  extending).
- Implementation plan sequencing after this design is approved.
