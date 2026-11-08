# Desktop Application file tree

Status: implemented on `cursor/desktop-application-file-tree` (8 November 2026).
Client UX redesign for Plowshare Desktop Application **Files**. Builds on the
Application hub in
[2026-11-08-desktop-application-hub-design.md](2026-11-08-desktop-application-hub-design.md).
Verify claims against current Desktop code.

## Intent

Application **Files** today is not a file browser: a relative-path field, Browse /
Read buttons, and a flat horizontal run of `Folder · name` chips. Operators
cannot see hierarchy, expand folders in place, or keep context while editing.

The product shape this design locks is: **an Explorer-style tree on the left and
the file editor beside it**, delivered as a **reusable Desktop file-browser
component**. Folders expand in the tree; selecting a file opens its content on
the right without dismissing the tree. Navigation is the tree, not typed paths.
Application Files is the first host; **Projects** (and other Desktop surfaces)
must be able to mount the same component without forking the tree UI.

### Why this, and why now

The Application hub just made Files a primary surface. Operators now land there
expecting to manage Application source, and the current chrome fails that
expectation against every familiar file browser (Norton, Explorer, Codex-style
trees). Server list/read/save already exist for Applications; Projects will need
the same browsing chrome against their own list/read/save (or local) adapters.
Building the tree twice would freeze the bad pattern into two places.

## Goals

- Extract a **reusable file-browser component** (tree + beside editor) that hosts
  inject via a narrow filesystem adapter (list directory, read file, save file,
  optional location/root metadata).
- First host: Application hub **Files** — replace the path-form + chip listing
  with that component over existing `application.files` / read / save.
- Keep a **split view**: tree left, editor right. Selecting a file keeps the
  tree visible.
- Support **browse, open, and save** in this cut (drafts and revision conflict
  handling stay with the host or a shared editor helper as the builder prefers).
- Show the current path clearly (breadcrumb or equivalent) without making typed
  relative path the primary navigation.
- Preserve Info **App files** as a short companion that jumps into the Application
  Files tree view (does not become a second browser).
- Preserve existing authorization, listing limits (200 entries per directory where
  that contract applies), writable-area selection for Applications, and read-only
  presentation when the adapter reports not writable.
- Keep keyboard focus and selection usable enough for ordinary browsing (exact
  keys left to the builder).
- Design the component API so a **Project** host can mount it later (or in the
  same cut if a Project adapter is cheap) without rewriting the tree.

## Non-goals

- Create, rename, delete, move, upload, or download in this cut.
- Multi-select, drag-and-drop, or diff views.
- Changing `application.files` / `application.file.read` / `application.file.save`
  server contracts.
- TUI or console file-browser parity.
- Replacing Project Info → Files **local folder claim + sync** controls with the
  tree. The tree is for browsing content once a root/adapter exists; sync/claim
  stays separate chrome.
- Fully rebuilding every Project file surface in this cut if an adapter is not
  ready — the **component** must still be shareable; a Project host can land in
  a follow-up as long as the API is not Application-only.
- A full IDE file explorer (search-in-tree, git status badges, virtualized
  millions of nodes).

## Anti-goals

- Shipping another flat chip/button strip and calling it a browser.
- Making operators type relative paths as the main way to move around.
- Hiding the tree when a file opens.
- Turning Info **App files** into a duplicate full tree browser.
- Baking Application-only paths, project kinds, or `application-files` action
  names into the tree component itself (hosts own those).

## Constraints

- Desktop only.
- Component lives under the Desktop renderer (e.g. `file-browser.ts` + CSS); hosts
  pass an adapter and mount into their panel.
- Lazy or on-demand directory listing is preferred over loading the whole tree
  up front (Application listing is per path, capped at 200 entries).
- No `-PfullDb`. Extend Desktop applications smoke / focused renderer tests for
  the component and Application host.
- Follow repository coding standards; no unrelated refactors.

## Agreed product shape

### Reusable component

- One Desktop **file-browser** module: tree pane + editor pane + path cue.
- Hosts supply a small **adapter** for list / read / save (and optional
  “more entries” / error / writable signals). Application host adapts today’s
  `application-files` client. A Project host adapts whatever list/read/save
  (or local) surface Projects already have when wired.
- Component must not import Application hub state or hard-code Application
  action names. Application-specific location `<select>` stays in the
  Application host around the component (or as optional host chrome).

### Layout (Application host first)

When the Application hub **Files** view is active:

| Region | Role |
| --- | --- |
| Tree pane (left) | Expandable folder tree for the selected root; files as leaf rows |
| Editor pane (right) | Selected file title, permission/status, content, Read current / Save (and existing draft conflict controls) |
| Path cue | Current directory or selected path as breadcrumb / status — not a primary typed form |
| Location select (Application host) | Keep when multiple writable areas exist (Application source vs other admitted areas) |

The old primary **Relative path** form + Browse/Read as the main chrome go away.
A narrow power-user path jump is optional and must not replace the tree.

### Tree behavior

- Root is the host’s selected root (`/` of that area).
- Folders expand/collapse in place; expanding lists that directory via the
  host adapter.
- Files select and open via the host adapter’s read.
- Empty directories and listing caps stay honest in the UI (status or row cue).
- Selection and expanded state are per host instance; clearing on project /
  root switch is fine.

### Editor behavior

- Draft retention, revision check on save, read-only when not writable, Read
  current / discard-draft when the backing file moved — same contracts as today
  for Applications; component or shared helper owns the chrome, host adapter
  owns the wire.
- Editor stays beside the tree; Save / Read current remain available.

### Info companion

- Info **App files** remains a short companion that opens/focuses the Application
  hub Files tree view (existing jump behavior, updated to the new chrome).
- It does not embed a second tree.

## Current code anchors (verify while building)

- Hub Files chrome and listing:
  [`plowshare-desktop/src/renderer/application.ts`](../../../plowshare-desktop/src/renderer/application.ts)
  (`data-source`, `data-entries`, path form, editor).
- Client:
  [`plowshare-desktop/src/application-files.ts`](../../../plowshare-desktop/src/application-files.ts).
- Styles: [`plowshare-desktop/src/renderer/desktop.css`](../../../plowshare-desktop/src/renderer/desktop.css)
  (`#application-panel`).
- Smokes: [`plowshare-desktop/scripts/applications-smoke.mjs`](../../../plowshare-desktop/scripts/applications-smoke.mjs).
- Docs: Application Files wording in [`docs/projects.md`](../../projects.md)
  (Deployed Application files / hub Files).

## Reference mock

Brainstorm mock accepted 8 November 2026: tree left with expand/collapse, selected
`plowshare.json`, editor right with Save. Session under `.superpowers/brainstorm/`
(gitignored).

## Design calls (reuse)

Settled so planning does not stall on Project wiring:

- **This cut ships** the reusable component + **Application host**. Prove the
  adapter seam with a **test double / second mount in unit tests**. A live
  Project host is a follow-up unless an adapter is already trivial.
- **Future Project mount** targets browsing a **connected local project
  workspace** (content browser), not replacing Info claim/sync chrome.
- **Adapter capabilities vary:** Application uses revision-checked save;
  other hosts may be read-only or simpler save. The component degrades editor
  chrome from adapter signals.
- **One opaque root per component instance.** Multi-area switching stays
  Application host chrome around the component.
- **Per-instance state** so two mounts do not share one global browser slot.
- **Beyond-200 access:** tree is primary; keep an optional secondary path jump
  so hosts that still allow explicit paths outside the listing are not blocked.

## Left to the builder

- Exact tree DOM/CSS (indent, icons, chevrons), empty and error states, and how
  the 200-entry cap is shown on a node.
- Adapter TypeScript shape (list/read/save + capability signals) and file
  placement under `plowshare-desktop/src/renderer/`.
- Whether expanded directories cache listings in renderer state or always re-list.
- Optional secondary path jump control placement.
- Keyboard details beyond “usable for ordinary browsing.”
- Test plan details (component unit tests with a second adapter mount +
  applications smoke for tree + beside editor).
- Implementation plan sequencing after approval.
