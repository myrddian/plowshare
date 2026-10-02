# Plowshare desktop

A runnable Electron application with the selected amber/charcoal styling,
an atomic Plowshare mark and a floating conversation panel. Navigation, controls and trajectory entry types share a local outline SVG icon
set in `src/renderer/icons.ts`. Icons inherit text colour; icon-only controls
keep accessible labels and tooltips. Repeated conversation subtitles and heavy
action borders are omitted to keep the interface quiet.
Authentication, WS bindings, typed requests, response readers, job lifecycle,
Markdown, trajectory and board models come from `plowshare-client-ts`. The
main process uses `plowshare-client-node` for fenced files and shared saved login.
Desktop imports neither TUI source nor its transport composition.

## Run

Requires Node 22.12+ and pnpm. From the repository root:

```sh
./gradlew :plowshare-desktop:assemble
./bin/plowshare-desktop
```

Or run `pnpm start` inside this directory. The launcher builds the app before
opening it. Electron downloads its platform runtime on first launch if it is
not already installed. Development profile data lives in ignored
`plowshare-desktop/build/profile`; `PLOWSHARE_DESKTOP_PROFILE` can select an
isolated profile. This is a development app, not a signed installer.

## Try the interface

It starts in **Offline demo**, with visibly labelled sample conversations,
tool records, document cards and memory. Demo responses are generated locally;
they never invoke a model or call your server.

- Switch workspaces and conversations; each keeps its draft, including across reloads.
- Start two conversations and send messages while another demo run streams.
  Demo runs include a short simulated thinking pause before their local reply.
- Drag the chat grip slightly, or focus it and use arrow keys. Dock or expand the panel.
- Open **Trajectory ↗** in its own native window: select steps at left to inspect
  published reasoning, messages, paired tool inputs/results and timings at right.
  Search or filter steps, use arrow keys, and toggle following the latest entry.
- Open **Inbox** or **Runs** in their shared native Activity window. Inspect
  sample notices, research stages and results independently of chat. **Mark read**
  changes only the displayed item; opening Inbox sends no receipt.
- Open **Board** or **Swarm** in their shared native inspector. Select a topic to
  read full messages, documents, decisions and resolutions; navigate children and
  ancestors, and open a seat's live trajectory. Swarm shows active topics, queued
  model calls and shared pool occupancy. Use the Plowshare demo workspace for samples.
- Stop a running job or filter the conversation list.
- Read formatted Markdown, including tables, nested lists, quotes and code blocks.
  The demo context meter is labelled as a sample.
- Hover an answer or code block, or focus its copy button, to copy the answer
  as Markdown or the code with its spacing preserved. Trajectory inputs,
  results and record details also have copy controls for their displayed text.
  A brief check mark confirms the copy without adding a message to the chat.
- Write a longer draft: the composer grows to a bounded height and shrinks again.
  Use **Latest** to return from scrollback. Empty conversations offer prompt
  suggestions that fill the draft without sending it.
- Open context for prompt usage, model and declared tools. Conversation IDs and
  tool lists are expandable. In compact windows context opens as a drawer;
  close it with its button, the backdrop or Escape.
- Use **Find** or **⌘/Ctrl K** to switch between loaded conversation names,
  workspaces and existing actions. Arrow keys select, Enter opens and Escape
  closes. This is local navigation, not conversation-content search.
- Toggle the sidebar with its toolbar button or **⌘/Ctrl B** for more chat
  space. That layout preference survives reloads in this desktop profile.

The movement is bounded inside the workspace; it does not create a separate
native chat window.

Trajectory windows stay attached to their conversation when the main chat
switches. Opening the same trajectory focuses its existing window. They are
read-only, share the neutral core trajectory model, and close when you switch
accounts/servers or return to demo. Closing one does not cancel a job. The main
chat keeps execution out of the transcript and the context sidebar.

The trajectory timeline contains recorded conversation steps. A separate current
run panel shows live reasoning, response text and stopped or uncertain outcomes.
Successful completion returns **Follow latest** to the recorded answer. Raw
lifecycle events and heartbeats remain available under the collapsed **Job
diagnostics** disclosure, with expandable, copyable records for each job.

## Use a Plowshare server

Choose **Connect server**, then enter your server origin, account handle and
password once. Leave the password blank to use a saved CLI/desktop session. The default address is `http://127.0.0.1:8091`; use the address of
your actual instance. This app does not launch a Java server or a database.

Live mode supports scoped conversation/agent listings, new conversations,
trajectory history with earlier-entry loading, streamed answers, job status
and cancellation, and account approval questions with allow-once/deny controls.
Independent jobs stay attached to their conversation when you switch views.
Real server operations retain the server's permissions and allowance behavior.

The selected chat and open native trajectory windows share a complete set of
live conversation follows. Switching chats keeps inspection windows followed;
closing a window removes its follow without cancelling work. Later harness or
other-client turns arrive automatically, preserving their speaker labels.
Reconnect restores the open views and reads missed entries by ordinal without
resubmitting turns or approvals. Loaded earlier history stays in memory.
An answer committed before its own job settles waits behind the active stream
in chat, then appears once; a later turn with identical text remains a new turn.

History returns complete utterances, answers, refusals and summaries, including
long Markdown responses. Tool results, diagnostics and tool arguments still use
bounded excerpts. This requires the updated server; an older server can still
return truncated messages marked **Excerpt shown**. Rebuild and restart the
server, then reconnect the desktop to reload its history.

This needs the server's multi-conversation `conversation.follow` extension.
An older server produces a visible **Live updates unavailable** notice while
foreground chat remains usable; the desktop does not silently replace follows
one at a time. **Refresh** retries subscriptions and reads open-view histories.

The composer shows the selected model, the model-reported prompt token count
and context limit, and the current reasoning/answer/tool phase. Unknown counts
stay unknown; disconnected or failed readings are labelled as last measured.
A small orbit turns while reasoning, bars move while an answer arrives and
the tool icon gently pulses during tool use. The indicator stays mounted during
streaming, stops on cancellation or an uncertain connection, and becomes still
when the system requests reduced motion. Its phase follows observed run events
and published deltas; it does not estimate progress or expose hidden reasoning.
Context pressure, interrupted runs, uncertain outcomes and server answer notes
appear beside the composer. Model-call allowances are shown separately from
token usage. Unsent drafts are not included in the server's prompt count.
Successful completion follows the server's `answered` flag, preserving its
uppercase ending name for trajectory records. Answers appear once as Markdown;
stopped-run notices appear once beside the composer. A successful outcome clears
old polling errors while retaining any genuine answer note from the server.

Below context usage, **Last measured run** uses the TUI's `reached` reader and
`describePace` formatter: gear/tool calls, thinking tokens, output tokens,
time to first thinking/answer token, and tokens per second. `~` marks a server
estimate and `—` marks an unmeasured value; measured zero remains zero.
The server reports these on completed outcomes. The latest measured row stays
with its conversation while another run starts or when you switch away and back.
These measurements cover that job's own model calls. The Chat jobs sidebar counts
tracked jobs separately; the Runs badge counts active root orchestrations in the
latest bounded account snapshot. Demo runs do not invent token or timing measurements;
pace is available for server runs observed during the current desktop session.

Passwords are cleared from the form on submission. Tokens remain on the main
process side, are never exposed to the renderer and are saved in the shared local
credential store. See [shared login](../docs/client-login.md). Initial
password changes must currently be completed through interactive CLI login, the TUI or web console.
Reconnect with the saved session after a lost connection; known job handles are
polled again, and mutations are never automatically replayed. An uncertain
submission without a returned job handle needs inspection through another
client before retrying.

The renderer has no Node integration, uses a sandboxed preload bridge and
loads only bundled app resources. See the [Electron security guidance](https://www.electronjs.org/docs/latest/tutorial/security)
for the process boundaries used here.

## Current limits

Live document browsing, memory management and semantic search are not implemented
in this test build. Union/sync integration is also pending. Their demo cards remain
labelled examples. Closing the application disconnects it and does not cancel
server jobs. Active job handles are retained for reconnects within the same
window, but are not restored after app quit. The Chat jobs list tracks those
jobs; the separate Runs view discovers account orchestrations.

The native **Activity** window has **Inbox** and **Runs** tabs. Inbox shows up to
20 read and unread results/notices initially, rendered as Markdown. **Load older
items** extends the mailbox history without changing the unread counter.
The **Status** selector defaults to **Unread**; choose **All items** or **Read**
to filter the loaded history. **Type** uses the server’s `kind` field: run
results, orchestration notices, approvals, hook notices and sync conflicts.
Other kinds found in loaded history also appear as options. Status, type and
text search combine; older-item loading stays available. Orchestration
questions and outcomes share one kind, so they are not separate type options. Changing the filter does not change the unread count
or mark anything read. Startup and account pushes
update its unread badge without marking anything read. **Mark read** receipts
only the selected displayed item. Failure keeps its content visible. Read
status comes from the server and remains visible after refresh or reopening.
**Refresh** rereads the loaded history, so marking an item never removes it.
Runs combines the 20 most recent account runs with separate running, asking
and waiting listings, up to 200 per state; saturation is explicitly warned.
This keeps an older active root visible behind newer completed phases, across
projects. Inspect stages, results/failures, parent/child runs and question
options/drafts. Account notifications and a 15-second timer refresh activity;
read failures retain previous snapshots, and reconnect rereads without replaying
receipts or decisions. Closing Activity does not cancel work. Switching
accounts/servers or returning to demo closes the window and clears live data.

A successful chat reply can leave an orchestration working. Full orchestration
records, actor/conductor conversations, definitions, human decisions and
scheduling controls remain separate gaps. Answer questions and change run
limits through the TUI for now. Later results delivered into a followed
conversation appear automatically; inbox-routed results appear in Activity.
Live subscriptions cover
open views, not an account-wide feed of every conversation. Subscriptions and
loaded histories are restored across reconnects in this session, not app quit.

Desktop drafts are local profile data. Logging into a different account or
server uses separate draft keys. Returning to demo clears live data from the
visible workspace. Assistant messages use the neutral core Markdown parser
with escaped HTML and fixed rendering tags; raw HTML is displayed literally.
Tool results stay plain text. Explicit clicks on validated HTTP/HTTPS links
open the system browser.
Copy controls write text through the main process after an explicit click;
neither frontend reads the clipboard. They copy the currently displayed source
or excerpt, without fetching omitted history or full tool results.

## Verify

```sh
pnpm typecheck
pnpm test
pnpm build
pnpm test:electron
```

The twenty-two client tests cover scope/configuration validation, concurrent early
events and separate published thinking/answer streams, cancellation confirmation, refusals, disconnect/reconnect, mode isolation
and the limited command/approval surface, including continuation job tracking,
plus conversation/agent-scoped context readings and stale-response protection.
They also cover complete-set follow ownership, serialized acknowledgements,
closing during pending reads, multi-page ordinal catch-up with earlier history,
pushes during reads, refusal/retry, older servers and reconnect/mode boundaries.
Twelve account-activity tests cover quiet startup, validated pages and explicit
receipts, unread-count races, stale account replies, old live roots, partial
read refusal and selected status notifications. Three Markdown tests cover
formatting, literal partial streams and unsafe markup/links.
The native smoke test launches
Electron against an isolated temporary profile and a local **protocol fixture**;
it checks separate trajectory and account Activity windows, live badges,
explicit receipts/refusal, persisted read history and older mailbox pages, old active roots, structured
question inspection, parent/child navigation, activity permissions and compact
layout, plus paired tool records and search/filtering,
conversation binding, read-only permissions, renderer isolation, floating controls, persisted drafts,
automatic creation on first message, concurrent jobs, cancellation and disconnect,
Markdown tables/code blocks, measured/unknown context usage and visible alerts.
It also checks bounded draft resizing, scrollback return, context disclosure
preservation, the compact drawer and empty-chat suggestions.
Keyboard navigation also exercises modal focus, shortcut isolation, workspace
roster loading and sidebar preference persistence.
Copy checks cover Markdown source, code spacing, trajectory excerpts and
invalid request rejection. The test captures writes in the isolated main
process instead of reading or replacing the user's system clipboard.
Phase checks exercise the running animation, transitions between reasoning,
tools and answers, reduced motion, and stopping on cancellation or connection loss.
Completion checks use canonical `ANSWERED`/`CANCELLED` server values and verify
one formatted answer, no false stopped warning or duplicate trajectory outcome,
and one notice for a cancelled run. Genuine answer notes and stale polling
error cleanup also have a client regression test.
Pace checks cover measured zeros, absent values and estimates in the shared
reader, plus the formatted row, conversation isolation and compact layout in Electron.
It does not establish a successful run against a real Plowshare deployment.
Screenshots are written under `build/smoke`.

`./gradlew :plowshare-desktop:check :plowshare-desktop:assemble` reaches the
headless checks and build. Native smoke testing stays opt-in because it opens
a window and needs a desktop session. Native testing for this initial slice
was performed on macOS Apple Silicon; other platform distributions remain work
in the consolidation plan.

## Board and swarm inspection

The sidebar and Find palette open the inspector, initially scoped to the selected
project. Its project and state filters, topic search and Load more control browse
all topics owned by your account. Child topics share their root's call allowance.
The Swarm tab lists members on current topics, with state and recent recorded
actions. Clicking a member opens its trajectory directly. Queue arrival positions
and waits, plus shared pool occupancy, provide supporting diagnostics; fair sharing can serve a later arrival first. Seat states come
from durable firings and the current scheduler snapshot, not from locally started
chat jobs. Closed topics remain available in Board.

Board reads `board.topics` and `board.messages`; Swarm reads `swarm.status` and
20-entry `conversation.trajectory` tails for its loaded members every four seconds
while open. Member previews load in pages of 40 with at most four simultaneous
reads. Search covers that loaded prefix; Load more members expands it. Activity
previews are latest recorded actions, and the full trajectory follows live updates. Manual Refresh is also available. Closing it stops those
reads; reconnect reconciles without replaying work. Failures retain the last
snapshot and show an error. Update/restart the server to load these new frames.
Inspection sends no posts, decisions, top-ups or agent read receipts. Seat/message
links open an author's conversation; selecting a specific writing entry is a
later enhancement. Board hooks and TUI write controls remain separate stages.

`pnpm test:board` exercises the native inspector against an isolated local
protocol fixture: demo/live topics, full Markdown messages, children/ancestors,
live updates, seat trajectories, refresh refusals, reconnect, account reset,
window permissions, compact layout and polling lifetime. Screenshots are in
`build/smoke/swarm-demo.png`, `swarm-live.png` and `board-compact.png`.


## Projects and local files

The sidebar has a **Projects** section. Use its **+** button to add a folder;
its `.plowshare/project` marker supplies the server project name, or its folder
name creates a project. Conversations sit under their project. Selecting a
project changes the view while other projects and their jobs keep running.
The project tree is the only sidebar project selector. Its chevrons expand or
collapse groups independently of the selected chat; expansion is remembered per
account. Search temporarily reveals groups without changing their saved expansion.
Project names show the server's recorded machine and workspace path, with saved
local folder metadata as a fallback when the server has no path.
Selecting a project connects its recorded folder automatically when it belongs
to this machine and no local mapping exists. Enabled saved folders reconnect;
an explicitly disconnected folder stays paused. Discovery then reads
`.plowshare/bots` and `bots/default` through that project's own session, so
project-local conversational bots appear and the preferred bot is selected
before sending. Refresh reloads the connected clients' bot rosters and defaults.
The server's WebSocket agent listing re-reads the asking session's local
definitions, so edited or repaired bot files appear without reconnecting the
folder. Other sessions' definition caches are kept separate.

Each project uses an ordinary `DesktopClient` instance with its own authenticated
session, job lifecycle and `/v1/files` channel. The desktop composition routes
conversation requests and trajectory subscriptions to that owner and combines
state for display. Global conversations and account Inbox/Runs/Board inspection
use an account client. Authentication is shared in main: event/file channel
opens serialize token rotation; project clients do not log in again or retain
passwords. There is no worker process or renderer filesystem access.

Folder mappings are saved in **`~/.config/plowshare/desktop-projects.json`**
(or `$XDG_CONFIG_HOME/plowshare/desktop-projects.json`). Each entry contains the
server origin, account handle, server project name, canonical local path,
machine and enabled state. Atomic writes preserve other accounts and servers;
files have mode `0600`. Passwords, access/refresh tokens and conversation contents
are not stored there. `PLOWSHARE_DESKTOP_CONFIG` overrides the directory for tests.
An unreadable or corrupt file is reported and never reset or overwritten.

After a fresh application launch, sign in again. Enabled projects restore their
own clients and folders from that account/server's mappings. A missing, moved,
conflicting or other-machine folder remains unavailable with its saved path.
When no local mapping exists, selecting the project or using **Connect files →
Connect recorded folder** recovers the server's folder on this computer, validates
its canonical path and project marker, then connects and saves it. Another machine's path (or a server-owned
folder with no client machine) is displayed but requires choosing a local folder.
Server paths never silently replace an explicitly selected folder. Saved mappings also
allow local projects to restore when the server's project list is unavailable.
Agent turns are never replayed. Known jobs can reconcile after reconnect within
the same process; job handles are not yet restored after app quit.

Click **Files connected** or **Connect files** to inspect the current project's
folder. **Disconnect files** pauses only that project and saves the pause.
**Reconnect files** resumes its remembered folder; **Choose folder** changes its
location. **Forget folder** removes the local mapping, without deleting the
folder, server project or conversations. A lost project connection leaves the
other project clients running. Account disconnect or app quit withdraws every
project and waits for its local commands to stop.

The shared Node file service supplies roots/read/stat/glob/grep/write/edit/delete/move/
run/cancel, harness definitions/hooks, canonical path and symlink fencing.
Commands obey `.plowshare/environment.yml`: absent settings use `local.mode: ask`
with server approval, and shells require local permission. Exact server
readiness is required; markers are written after acceptance. Accepted file
changes may finish during withdrawal. One server project still permits one
serving session, so a TUI rooting that *same* project can refuse a desktop claim;
different projects now run simultaneously. Union/sync, conflict browsing, an
environment editor remain separate capabilities. PDF/image conversion happens on
the server; the shared Node file client streams fenced source bytes over WS.

`pnpm test:projects` verifies two native project clients, independent jobs and
files, shared refresh rotation, folder persistence across fresh Electron
processes, pause, server-list fallback, isolated connection loss and missing
folder handling. `pnpm test:files` checks file operations and boundaries.
`pnpm test:bots` uses actual `.plowshare/bots` files in a temporary project and
reads them over the desktop file channel. It checks automatic same-session
attachment, preferred bot selection, Refresh, isolation and restart recovery
against the protocol fixture; the Java definition parser is outside that test.
All native tests use isolated profiles/configuration and temporary folders;
they do not modify your real project configuration or files.

Saved login survives a desktop restart: the app remembers its server/account and
opens the shared token session automatically. Explicit Disconnect or Use demo
keeps the next launch offline. Connection/project preferences follow
`PLOWSHARE_CONFIG_DIR` (or the XDG default); `PLOWSHARE_DESKTOP_CONFIG` overrides
those desktop preferences only. Tokens remain in the shared private credentials
store. `pnpm test:login` exercises fresh-process restoration and failure recovery
with an isolated fixture account.
