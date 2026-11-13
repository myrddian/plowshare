# Network Privacy Watch: Ask as a full-window chat

Approved by the owner on 14 Nov 2026 (short design, dashboard-only change).

## Intent

The Ask tab works but is cramped. The page is capped at 1320px, Ask splits that
60/40 with the Waiting on and Done panels, and the conversation is a fixed box
(62% of the window height, 680px at most) with the question form stacked below.
Answer tables are cut off on the right. The owner wants Ask to behave like a
traditional chatbot: the conversation uses the whole browser window and the
question box floats over its bottom edge. Keep the existing look (obsidian, gilt,
chamfers, Cormorant/structure type).

## Goals

- On Ask, the conversation fills the window below the tabs and scrolls on its own.
  The 1320px cap, the large page heading and the footer are hidden on this tab.
- Messages read in a centred column about 760px wide. Answer tables may grow
  past the column up to the window width, and scroll sideways only past that.
- The question box floats, centred and as wide as the column, at the bottom. The
  conversation fades out behind it. The working row, the "New reply" chip and
  the paused/top-up notice sit just above it. The Ready/Working marker moves
  into the box's footer.
- Waiting on and Done move into a right-hand drawer, opened by a "Handed off"
  button beside the question box that shows the waiting count and pulses while
  something is waiting. Escape or Close dismisses it.
- At phone width the box spans the screen and the drawer covers it.

## Non-goals

- No collector, API, server or Application change. Same element ids and
  behaviour (pending bubble, working row, 1.5 s following, Enter to send).
- The floating chat bubble on other tabs (option B) is a possible follow-up, not
  part of this change.

## Anti-goals

- No hidden messages under the box, no horizontal page scroll, no inline styles
  (the CSP forbids them), no new motion beyond the console-design allowance.

## Approaches

- **A, chosen by the owner:** the Ask tab becomes the full-window chat.
- B: a floating chat bubble on every tab. Rejected for now: a ~420px panel is
  cramped again for tables; the owner sees it as a later addition.
- Builder's call within A: fill the window with CSS only
  (`body:has(#view-ask:not([hidden]))` makes the body a 100dvh flex column, as
  the Devices side pane already uses `:has`), rather than measuring offsets in
  JavaScript. The box sits in flow at the bottom with a gradient fade over the
  conversation, so it looks like it floats without ever covering the last
  message. The drawer appears without a slide, to stay within console-design's
  motion rule.
