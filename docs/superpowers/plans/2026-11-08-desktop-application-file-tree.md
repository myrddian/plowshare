# Desktop Application File Tree Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Application Files chip browsing with a reusable tree+editor file browser for morning prototype testing.

**Architecture:** Self-contained `file-browser` renderer component with a port adapter (list/read/save). Application hub mounts one instance. Unit tests prove a second mount with a fake port works independently. Project Files host is out of scope.

**Tech Stack:** TypeScript, Node test runner, plowshare-desktop renderer + CSS, applications smoke.

---

### Task 1: Reusable file-browser component + unit tests

**Files:**
- Create: `plowshare-desktop/src/renderer/file-browser.ts`
- Create: `plowshare-desktop/src/renderer/file-browser.test.ts`
- Create: `plowshare-desktop/src/renderer/file-browser.css` (or section in `desktop.css`)

- [ ] **Step 1:** Write failing tests for expand/list, open/edit/save, two independent mounts
- [ ] **Step 2:** Implement `installFileBrowser(host, port)` with tree left / editor right
- [ ] **Step 3:** Run tests until green

### Task 2: Application hub host

**Files:**
- Modify: `plowshare-desktop/src/renderer/application.ts`
- Modify: `plowshare-desktop/src/renderer/desktop.css`

- [ ] **Step 1:** Replace chip `data-source` / `data-entries` UI with file-browser mount
- [ ] **Step 2:** Port adapter via `request` + `applicationFiles` state
- [ ] **Step 3:** Keep Info panel “App files” jump; clear browser on hub leave

### Task 3: Smoke + docs

**Files:**
- Modify: `plowshare-desktop/test/applications-smoke.mjs`
- Modify: `docs/desktop.md` (brief Files tree note)

- [ ] **Step 1:** Update smoke assertions for tree/editor DOM
- [ ] **Step 2:** Run applications smoke
- [ ] **Step 3:** Commit and push PR
