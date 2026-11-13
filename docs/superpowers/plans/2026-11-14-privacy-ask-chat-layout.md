# Ask as a full-window chat Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Ask tab a full-window chat with a floating question box and a handed-off drawer.

**Architecture:** Dashboard-only. `index.html` regroups the existing Ask elements (same ids) into a conversation, a bottom dock and a drawer. `style.css` makes the page a 100dvh flex column only while Ask is shown, through `body:has(#view-ask:not([hidden]))`. `app.js` gains the drawer toggle and its count; rendering of entries is unchanged.

**Tech Stack:** Plain JS/CSS/HTML served by the collector; node --test with the vm DOM harness in `tests/web_ask.test.cjs`.

**Spec:** `docs/superpowers/specs/2026-11-14-privacy-ask-chat-layout-design.md`

## Global Constraints

- No collector, API, server or Application change; keep every existing Ask element id.
- No inline styles or `element.style` writes (CSP); no new motion beyond console-design principle 2.
- Reading column `760px`; tables may widen to `min(1240px, 100vw - 2 * var(--gutter))`.
- The box never covers the last message; no horizontal page scroll at 375px.
- Gate: `python scripts/check.py` in `integrations/network-privacy` exits 0.

## Review Focus

- A very long answer table: wider than the column, scrolls inside its own wrapper, page never scrolls sideways. (Preview check, Task 1.)
- A paused conversation: the Top up notice must stay visible above the box, not hidden in the drawer. (Test in Task 2.)
- Disconnecting with the drawer open leaves no drawer behind. (Test in Task 2.)
- Keyboard: Escape closes the drawer and returns focus to its button. (Test in Task 2.)
- Leaving Ask restores the normal page (cap, heading, footer, page scroll). (Preview check, Task 1.)

---

### Task 1: Full-window layout

**Files:**
- Modify: `integrations/network-privacy/src/plowshare_privacy/web/index.html` (the `#view-ask` section)
- Modify: `integrations/network-privacy/src/plowshare_privacy/web/style.css` (Ask block)
- Modify: `integrations/network-privacy/docs/console-design.md` (Ask layout note)

**Interfaces:**
- Produces: markup `#view-ask.ask-view` > `#ask-conversation` (full width, children centred in the column) + `.ask-dock` (`#ask-jump`, `#ask-pause`, `#ask-working`, `#ask-form` whose foot holds `#ask-state`, `#ask-keys`, `#ask-handed`, `#ask-send`) + `aside#ask-drawer[hidden]` (`#ask-drawer-close`, `#ask-waiting`, `#ask-done`). A visually hidden `h2` "Conversation" keeps the region named.

- [ ] **Step 1:** Restructure `#view-ask` as above. `#ask-handed` is `button.quiet.ask-handed-toggle[type=button][aria-expanded=false][aria-controls=ask-drawer]` with text `Handed off`.
- [ ] **Step 2:** CSS. While Ask is shown: body is a `100dvh` flex column with `overflow: hidden`; `main`, `#dashboard`, `#view-ask` are `flex: 1; min-height: 0` columns; `main` loses the 1320px cap; `.view-heading` uses the `.vh` treatment (kept for focus and the accessible title); footer hidden. `#ask-conversation` scrolls (`flex: 1`), children `max-width: 760px; margin-inline: auto`. `.ask-dock` sits in flow, column-wide, with a `::before` gradient (transparent to `--obsidian`) over the conversation. `.ask-answer .markdown-table` breaks out centred to the table-width bound. Drawer: absolute, right, full height of `#view-ask`, `width: min(400px, 100%)`, panel styling. Phone (`max-width: 800px` block): dock full width, drawer `width: 100%`.
- [ ] **Step 3:** Run `node --test tests/web_ask.test.cjs` — all existing tests PASS (ids unchanged).
- [ ] **Step 4:** Preview (`privacy-preview`) at 1440x900 and 375x812: conversation fills the window; last message clear of the box; a wide table (sample answer) extends past the column; no horizontal page scroll; switching to Overview restores the cap, heading, footer and page scroll.
- [ ] **Step 5:** Commit `Make Ask a full-window chat with a floating question box`.

### Task 2: Handed-off drawer

**Files:**
- Modify: `integrations/network-privacy/src/plowshare_privacy/web/app.js`
- Modify: `integrations/network-privacy/src/plowshare_privacy/web/style.css`
- Test: `integrations/network-privacy/tests/web_ask.test.cjs`

**Interfaces:**
- Consumes: Task 1 ids.
- Produces: `renderAskHanded(view)` called from `renderAsk`; `setAskDrawer(open: boolean, returnFocus = false)`.

- [ ] **Step 1: Write the failing tests** in `web_ask.test.cjs`:
  - `the handed-off button counts waiting work and opens the drawer`: with two waiting items, `node('ask-handed').textContent === 'Handed off · 2'` and its className contains `waiting`; with none, text `Handed off` and no `waiting`. Clicking `ask-handed` sets `node('ask-drawer').hidden === false`; clicking again hides it.
  - `Escape and Close shut the drawer and return focus`: open, dispatch drawer `keydown` `{key:'Escape'}` → hidden, and `ask-handed` received `focus()`; same for `ask-drawer-close` click.
  - `a paused conversation keeps Top up beside the box`: paused view → `node('ask-pause').hidden === false` while the drawer stays hidden.
  - `disconnect closes the drawer`: open, disconnect (as the existing disconnect test does) → `ask-drawer` hidden and `ask-handed` text reset to `Handed off`.
- [ ] **Step 2:** Run `node --test tests/web_ask.test.cjs` — the four new tests FAIL.
- [ ] **Step 3:** Implement in `app.js`: the two functions, listeners on `ask-handed` (click toggles), `ask-drawer-close` (click closes with focus return), `ask-drawer` (`keydown` Escape closes with focus return; no document listener). Disconnect adds `ask-drawer` to the hidden list and resets the button. `aria-expanded` follows the drawer. CSS: `.ask-handed-toggle.waiting::before` pulsing diamond (reduced-motion stills it).
- [ ] **Step 4:** Run `python scripts/check.py` — exit 0.
- [ ] **Step 5:** Preview: drawer opens beside the conversation at desktop, covers the screen at 375px; Escape closes it.
- [ ] **Step 6:** Commit `Move handed-off work into a drawer beside the Ask box`.
