# Desktop Application hub Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Plowshare Desktop treat Applications as server-active AI workflow hubs (Overview / Conversations / Schedules / Relay / Files / Access), with Deploy/Add under Server administration and Info no longer confusing App files with Project sync.

**Architecture:** Compose existing Desktop surfaces and WebSocket ops. Relocate admin entry points, rewrite the Application panel navigation around a workflow Overview, gate Info folder/sync UI when `scope` is an Application, and extend the existing Electron applications / admin smokes. No server or TUI changes.

**Tech Stack:** TypeScript, Electron renderer (`plowshare-desktop`), Playwright smoke scripts, existing `application.files` / activity / relay clients.

**Spec:** [`docs/superpowers/specs/2026-11-08-desktop-application-hub-design.md`](../specs/2026-11-08-desktop-application-hub-design.md)

## Global Constraints

- Desktop only; no TUI hub, CLI expansion, or console parity.
- No new server contracts; no `-PfullDb`.
- Neither MANAGED nor DISJOINT Applications get Connect folder / Project sync chrome.
- Preserve admin gates: Deploy / Add remain `connected && serverAdmin`.
- Keep unrelated refactors out; follow Desktop coding patterns already in the renderer.

## Builder decisions locked by this plan

- **Active cue:** Application rows show subtitle `Active on server` (plus type when useful, e.g. `MANAGED` / `DISJOINT`). Do **not** call `application.deployment.status` per row.
- **Info labels:** When selected scope `kind === 'application'`, the third Info tab is **App files** and shows a short companion that opens the hub Files view (no Choose folder / sync). When Project/Personal, keep today's local folder + sync UI; tab label stays **Files**.
- **Open behavior:** Opening an Application shows Overview; do **not** auto-open Info.
- **Secondary surfaces:** Runtime / Memory / Boards become Overview secondary actions, not peer primary tabs.
- **Schedules / Relay:** Hub buttons open existing activity schedules and relay views, then bind the current Application (`openActivity('schedules')` plus client filter or project-aware refresh if the row already carries project; Relay opens and sets `#relay-scope` to the Application). Empty states stay honest if the shared list has nothing for that project.
- **Out of scope here:** Desktop blue/Print theme switcher (separate work item; borrow PR #13 switcher shape later).

## Review Focus

- Application selected but hub closed (Conversations returned to chat): Info must still not offer Project sync for that Application scope.
- Non-admin connected account: Deploy/Add absent from both Applications header and Server administration chrome.
- DISJOINT Application: still no sync UI; Files still uses server `application.files`.
- Switching from Application → Project restores Files tab label and folder/sync controls.
- Smoke reconnect / reload: Applications header remains without Deploy/Add; admin still reaches them from Server administration.

---

### Task 1: Move Deploy / Add into Server administration

**Files:**
- Modify: `plowshare-desktop/src/renderer/index.html` (Applications heading; admin host markup if needed)
- Modify: `plowshare-desktop/src/renderer/server-admin.ts` (admin actions for Deploy / Add)
- Modify: `plowshare-desktop/src/renderer/application-deployment.ts` (visibility targets admin controls)
- Modify: `plowshare-desktop/src/renderer/app.ts` (Add visibility; keep dialog open handlers on stable ids)
- Modify: `plowshare-desktop/scripts/admin-smoke.mjs` and `application-deployment-smoke.mjs` as needed
- Test: those smokes

**Interfaces:**
- Consumes: existing `#application-deployment-dialog`, `#server-project-dialog`, `state.serverAdmin`
- Produces: admin-visible controls `#application-deploy-open` and `#server-project-add` living under Server administration UI (not Applications `.sidebar-heading`)

- [ ] **Step 1: Extend admin smoke expectations**

Assert Applications heading has no visible Deploy/Add; with `serverAdmin: true`, Server administration exposes Deploy and Add and they still open the existing dialogs.

- [ ] **Step 2: Run smoke to verify it fails**

Run: `pnpm --dir plowshare-desktop test:admin` (or the package script that runs `admin-smoke.mjs`)
Expected: FAIL on missing admin Deploy/Add or still-present Applications-header buttons

- [ ] **Step 3: Relocate controls and wire visibility**

Remove Deploy/Add from Applications heading in `index.html`. Render equivalent buttons in `server-admin.ts` shell (heading actions or Work section). Keep element ids `#application-deploy-open` / `#server-project-add` so existing click handlers and `hidden` toggles keep working; update any selector that assumed they lived under Applications.

- [ ] **Step 4: Re-run admin + deployment smokes**

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add plowshare-desktop/src/renderer/index.html plowshare-desktop/src/renderer/server-admin.ts plowshare-desktop/src/renderer/application-deployment.ts plowshare-desktop/src/renderer/app.ts plowshare-desktop/scripts/admin-smoke.mjs plowshare-desktop/scripts/application-deployment-smoke.mjs
git commit -m "Move Application Deploy and Add under Server administration."
```

---

### Task 2: Application sidebar rows read as server-active

**Files:**
- Modify: `plowshare-desktop/src/renderer/app.ts` (`projectRows` when `application === true`)
- Modify: `plowshare-desktop/scripts/applications-smoke.mjs`
- Test: `applications-smoke.mjs`

**Interfaces:**
- Consumes: `Project.kind`, `Project.type`
- Produces: Application row subtitle without local-folder / sync-as-primary copy; includes `Active on server`

- [ ] **Step 1: Extend applications smoke**

After connect, assert an Application row shows `Active on server` and does not show `DISJOINT · no sync` as the primary location line.

- [ ] **Step 2: Run smoke to verify it fails**

Run: `pnpm --dir plowshare-desktop test:applications`
Expected: FAIL on missing Active copy

- [ ] **Step 3: Adjust `projectRows(true)` location/subtitle rendering**

For Applications, prefer `Active on server` (optionally append type). Keep Access button behavior working for Applications (fix Access click wiring if currently only bound on `#conversations`).

- [ ] **Step 4: Re-run applications smoke**

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add plowshare-desktop/src/renderer/app.ts plowshare-desktop/scripts/applications-smoke.mjs
git commit -m "Show Applications as active on the server in the sidebar."
```

---

### Task 3: Workflow hub interior (Overview + primary views)

**Files:**
- Modify: `plowshare-desktop/src/renderer/application.ts`
- Modify: `plowshare-desktop/src/renderer/desktop.css` (hub tiles / nav only as needed)
- Modify: `plowshare-desktop/src/renderer/app.ts` (callbacks to open schedules/relay with Application binding; Conversations via existing `chooseScope`)
- Modify: `plowshare-desktop/scripts/applications-smoke.mjs`
- Test: `applications-smoke.mjs`

**Interfaces:**
- Consumes: `installApplication(host, state, request, conversations)` plus new optional callbacks `openSchedules(project: string)` and `openRelay(project: string)` from `app.ts`
- Produces: hub views `overview | conversations | schedules | relay | files | access`; `open(name)` lands on Overview with `files === false`

- [ ] **Step 1: Extend applications smoke for hub chrome**

Click Application → `#application-panel` shows Overview primary nav (Conversations, Schedules, Relay, Files, Access). Files still opens server browser. Conversations control returns to chat stage scoped to that Application. Runtime/Memory/Boards are not peer primary tabs (may appear as secondary on Overview).

- [ ] **Step 2: Run smoke to verify it fails**

Expected: FAIL on missing Overview / Schedules / Relay controls

- [ ] **Step 3: Implement hub navigation in `application.ts`**

Replace the current peer button row with Overview + primary views per spec. Wire:
- Conversations → existing `conversations(project)` / close hub
- Files → existing `application-files` flow
- Access → existing project-access click
- Schedules / Relay → new callbacks from `app.ts` that open existing hosts and bind project (Relay: set `#relay-scope`; Schedules: open activity schedules and filter/bind by project when row data allows)

Keep Runtime/Memory/Boards as Overview secondary actions using existing request actions.

- [ ] **Step 4: Re-run applications smoke**

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add plowshare-desktop/src/renderer/application.ts plowshare-desktop/src/renderer/app.ts plowshare-desktop/src/renderer/desktop.css plowshare-desktop/scripts/applications-smoke.mjs
git commit -m "Turn the Application panel into a workflow hub."
```

---

### Task 4: Info panel — App files vs Project Files

**Files:**
- Modify: `plowshare-desktop/src/renderer/index.html` (tab label hooks if needed)
- Modify: `plowshare-desktop/src/renderer/app.ts` (`showInfoTab`, `renderFiles`, files-open button)
- Modify: `plowshare-desktop/scripts/applications-smoke.mjs` (and a Project path assertion if cheap in same smoke or `controls-smoke.mjs`)
- Test: applications smoke (+ existing controls smoke if touched)

**Interfaces:**
- Consumes: `state.projects.find(p => p.name === scope)?.kind`
- Produces: Application scope → Info tab **App files** companion (opens hub Files / no sync); Project/Personal → **Files** with existing folder + `#project-sync`

- [ ] **Step 1: Write failing smoke assertions**

With Application selected: Info third tab text is `App files`; `#files-choose` / project sync controls not offered. Switch to a Project: tab is `Files` and Choose folder remains available.

- [ ] **Step 2: Run smoke to verify it fails**

Expected: FAIL on tab label / sync still shown for Application

- [ ] **Step 3: Branch `renderFiles` / tab labeling on Application vs Project**

When Application: hide folder claim + `#project-sync`; show short App files companion that triggers the hub Files view (or focuses existing files UI). When Project/Personal: preserve current behavior. `#files-open` in chat footer must not push Project sync chrome for an Application scope.

- [ ] **Step 4: Re-run affected smokes**

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add plowshare-desktop/src/renderer/index.html plowshare-desktop/src/renderer/app.ts plowshare-desktop/scripts/applications-smoke.mjs
git commit -m "Separate Application App files from Project folder sync in Info."
```

---

### Task 5: Docs + branch check

**Files:**
- Modify: `docs/manual/02-clients-and-work.md` (~186–188)
- Modify: `docs/projects.md` (~126–129, ~260–261, ~297–298, ~660–664 as needed)
- Modify: `docs/superpowers/specs/2026-11-08-desktop-application-hub-design.md` status line to note implementation in progress/done when shipping
- Test: doc review only; re-run `pnpm --dir plowshare-desktop test:applications` and `test:admin`

- [ ] **Step 1: Update operator docs**

Replace “Deploy beside Applications” / “Applications → Deploy” with Server administration entry points. Describe Application hub views and that Files are server source without local claim. Note Info App files vs Project Files.

- [ ] **Step 2: Re-run Desktop applications + admin smokes**

Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add docs/manual/02-clients-and-work.md docs/projects.md docs/superpowers/specs/2026-11-08-desktop-application-hub-design.md
git commit -m "Document the Desktop Application hub and admin Deploy entry points."
```

---

## Spec coverage checklist

| Spec requirement | Task |
| --- | --- |
| Remove Deploy/Add from Applications header; place under Server administration | 1 |
| Rows read as Active on server; no local-folder-first subtitle | 2 |
| Hub: Overview, Conversations, Schedules, Relay, Files, Access | 3 |
| Files = server source via existing ops | 3 |
| Runtime/Memory/Boards secondary | 3 |
| Info: no Connect folder/sync for Applications; App files companion | 4 |
| Project Files/sync unchanged for Projects | 4 |
| Docs update | 5 |
| Desktop only / no server contract change | Global |
| Theme switcher | Explicitly deferred |
