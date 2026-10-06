# Plowshare desktop

A runnable Electron application with the selected amber/charcoal styling,
the current application icon and a floating conversation panel. Navigation, controls and trajectory entry types share a local outline SVG icon
set in `src/renderer/icons.ts`. Icons inherit text colour; icon-only controls
keep accessible labels and tooltips. Repeated conversation subtitles and heavy
action borders are omitted to keep the interface quiet.
The sidebar shows Plowshare beside the same icon as the native app. The window
titlebar holds the breadcrumb, Find, Refresh, Context and the server dropdown.
Assistant replies use a neutral label rather than repeating the app name.
Drag the edges of the navigation sidebar and context panel to resize them. The
dividers in Activity, Trajectory, Board and Library also resize their lists.
Widths are remembered across launches and adapt to smaller windows. Focus a
divider to use the arrow keys (Shift for larger steps), Home/End for its limits,
or double-click it to restore the default width.
Authentication, WS bindings, typed requests, response readers, job lifecycle,
Markdown, trajectory and board models come from `plowshare-client-ts`. The
main process uses `plowshare-client-node` for fenced files and shared saved login.
Desktop imports neither TUI source nor its transport composition.

## Run

Requires Java 21, Node.js 22.13+ and pnpm 10.34.5 on `PATH`. From the repository root:

```sh
./gradlew :plowshare-desktop:assemble
./bin/plowshare-desktop
```

Gradle installs the desktop and shared SDK dependencies from their frozen
lockfiles. The desktop install automatically recreates an incompatible
`node_modules` directory when needed, including after moving a checkout. Initial
dependency installation requires network access.

Or run `pnpm start` inside this directory. The launcher builds the app before
opening it. Electron downloads its platform runtime on first launch if it is
not already installed. Development profile data lives in ignored
`plowshare-desktop/build/profile`; `PLOWSHARE_DESKTOP_PROFILE` can select an
isolated profile. This is a development app, not a signed installer.

The application uses the approved black-on-ivory atomic ploughshare emblem as
its native icon. On macOS, `pnpm start` and `./bin/plowshare-desktop` build and
launch a dedicated `Plowshare.app` bundle so the Dock and application menu use
**Plowshare**. This preserves the development profile and uses the same icon as
the sidebar and distribution. The first launch may download the native runtime;
each launch rebuilds the bundle with the current source.
Icon sources and platform exports live in `assets/icons`. After changing them,
quit and relaunch the app (`pnpm start` or `./bin/plowshare-desktop`); for an
installed `.app`, rebuild with `./gradlew clientDistributions` and replace it
with the newly packaged application. No icon-generation tools are needed for
normal builds.

## Try the interface

It starts in **Offline demo**, with visibly labelled sample conversations,
tool records, document cards and memory. Demo responses are generated locally;
they never invoke a model or call your server.

- Switch workspaces and conversations; each keeps its draft, including across reloads.
- Start two conversations and send messages while another demo run streams.
  Demo runs include a short simulated thinking pause before their local reply.
- Drag the chat grip slightly, or focus it and use arrow keys. Dock or expand the panel.
- Open **Trajectory** in the main content area: select steps at left to inspect
  published reasoning, messages, paired tool inputs/results and timings at right.
  Model folds do not hide user history: original entries remain visible and name the
  model summary that superseded them.
  Search or filter steps, use arrow keys, and toggle following the latest entry.
- Open **Inbox** or **Runs** in the main content area. Inspect
  sample notices, research stages and results independently of chat. **Mark read**
  changes only the displayed item; opening Inbox sends no receipt.
- Open **Board** or **Swarm** in the main content area. Select a topic to
  read full messages, documents, decisions and resolutions; navigate children and
  ancestors, and open a seat's live trajectory. Swarm shows active topics, queued
  model calls and shared pool occupancy. Use the Plowshare demo workspace for samples.
- Click the toolbar **Help** icon for **Library → Manual**. Install the shared
  [manual](../docs/manual/installation.md) first; chapter links resolve current
  revision UUIDs from stable supplied names and chapter tags.
- Open **Library** from the sidebar. **Reports** contains generated reports, including drafts.
  A compact collection filter sits beside the tabs; source questions sit above
  the reader, and the saved text fills the available reading height. Reports
  render Markdown after reading the complete text over WebSocket; **Show source**
  exposes the original Markdown for exact quotations across formatting or repeated text.
  **Documents** reads consumed corpus text with generated summaries beside it;
  **Manage document sources and imports** contains source acquisition, saved text,
  questions, evidence and extraction recovery. Document search stays in Library.
  **Memories** is a separate sidebar page with memory/proposal maintenance and
  recall/navigation searches.
- In **Board**, choose **Post to board**, then select a project and open topic,
  write your message and submit. The server attributes the post to your account.
  The draft and request UUID survive a refused/unknown acknowledgment and app
  reload. Only an explicit retry resubmits, using the same durable receipt.
  Edits create a new request identity; no post is retried automatically. This
  requires the updated server (`board.post`, migration V89). Reports filtering
  also requires server support for `information.list.kind`.
- Choose **New topic** to select a project, enter its title, label and opening
  message, and optionally limit model calls. The server opens it as you and
  wakes the project's configured swarm. `board.open` uses the V89 receipt store
  to prevent duplicate topics or wakes on an explicit retry; drafts survive
  unconfirmed replies. Projects need configured swarm members to start work.
- Select a failed member in **Swarm** or a topic's seat in **Board**, then choose
  **Retry member…**. Set the step limit for that retry (default 24). Each step is
  one model response and its requested tools. It keeps the member's conversation
  and uses the topic's remaining shared model-call allowance; it adds no budget.
  Unconfirmed requests keep their identity across reloads: choose **Check pending
  member retry…** to reconcile explicitly without creating another wake. Requires
  the updated server's `board.retry` operation.
- Open **Manage work** from the sidebar to save memories, manage conversations,
  review permissions or adjust work limits. Options load automatically when you
  choose a task and workspace; each change still has a separate review and confirmation.
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
- Open **Info → Context** for the current server-constructed projection before your next
  prompt: system block, full projected messages, tool calls/results and actual
  offered schemas. Drafts are excluded; Count tokens explicitly measures that
  captured projection. Snapshots retain their capture time and errors preserve
  the last successful value. Memories has its own main-area screen. In compact windows context opens as a drawer;
  close it with its button, the backdrop or Escape.
- Use **Find** or **⌘/Ctrl K** to switch between loaded conversation names,
  workspaces and existing actions. Arrow keys select, Enter opens and Escape
  closes. This is local navigation, not conversation-content search.
- Toggle the sidebar with its toolbar button or **⌘/Ctrl B** for more chat
  space. That layout preference survives reloads in this desktop profile.

The movement is bounded inside the workspace; it does not create a separate
native chat window.

Inbox, Runs, Scheduled work, Orchestration, Library, Memories, Board, Swarm and
Manage work replace the chat area while
keeping the sidebar and workspace header. Trajectory uses the breadcrumb
**Workspace / Conversation / Trajectory**; click the conversation to return to
chat with its draft preserved. Inspection pages retain their selection when
reopened. Trajectories stay attached to their conversation, are read-only, share
the neutral core trajectory model, and clear when you switch accounts/servers or
return to demo. Changing views does not cancel a job.

The trajectory timeline contains recorded conversation steps. A separate current
run panel shows live reasoning, response text and stopped or uncertain outcomes.
Successful completion returns **Follow latest** to the recorded answer. Raw
lifecycle events and heartbeats remain available under the collapsed **Job
diagnostics** disclosure, with expandable, copyable records for each job.

In **Runs**, click a stage to open its conductor trajectory filtered to that stage's
recorded visits, including returns to an earlier stage. Older entries load when needed.
Pending agent questions also appear in chat. A popup opens for the active workspace
or caller conversation; questions from other workspaces remain in a waiting queue.
The popup supports choices, multiple selections, other answers, notes, and free text.
Later, Escape, and the close button preserve the draft without answering or cancelling.
A changed question requires review, and another client's answer closes the old form.

Questions and their answers appear once under the relevant stage; the current question
has its answer controls there. Historical questions are expandable. Journals that lack
unambiguous stage timing keep their questions in a separate disclosure rather than
assigning them to an arbitrary stage.

Conductor/caller links remain beside the run details. Delegations are navigated in
place: **>** marks an agent call and **<** marks its recorded result. Each spawn call
has an inline trajectory shortcut and a **Trajectory** link in the top right of the
selected step. Repeated calls to the same agent open their specific child logs; nested
calls work the same way. An `orchestrate_*` call's recorded receipt opens its conductor
after server status confirms the caller relationship. Opening a trajectory reads and
follows existing work; it starts no agent turn. Project children keep their parent's
authenticated session. Active runs have a highlighted **Cancel run** button in the
top right; it also cancels descendants. The redundant run-record list is omitted from
this view; stage and conductor trajectories provide the detailed activity.

## Use a Plowshare server

Choose **Connect server**, then enter your server origin, account handle and
password once. Leave the password blank to use a saved CLI/desktop session. The default address is `http://127.0.0.1:8091`; use the address of
your actual instance. This app does not launch a Java server or a database.

Live mode supports scoped conversation/agent listings, new conversations,
trajectory history with earlier-entry loading, streamed answers, job status
and cancellation, and account approval questions with allow-once/deny controls.
Independent jobs stay attached to their conversation when you switch views.
Real server operations retain the server's permissions and allowance behavior.

The selected chat and retained trajectory pages share a complete set of
live conversation follows. Switching chats keeps inspection pages followed;
closing a page removes its follow without cancelling work. Later harness or
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

Live document browsing, memory management and search use the connected server.
Closing the application disconnects it and does not cancel server jobs. Active job handles are retained for reconnects within the same
window, but are not restored after app quit. The Chat jobs list tracks those
jobs; the separate Runs view discovers account orchestrations.

The **Inbox** and **Runs** pages are separate sidebar destinations. **Scheduled work**
is separate too; **Orchestration** shares only **Definitions** and **Orchestration builder** tabs. Inbox shows up to
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
receipts or decisions. Leaving Activity does not cancel work. Switching
accounts/servers or returning to demo clears its live data.

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
it checks embedded trajectory and account Activity pages, live badges,
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

`pnpm test:navigation` verifies that sidebar sections and trajectories use one
native window, breadcrumbs return to chat, drafts survive navigation, modal
dialogs cover embedded pages, and inspection pages retain their IPC permissions.
`pnpm test:panes` verifies drag and keyboard resizing, width persistence after
restart, reset, compact drawers and the dividers inside inspection pages.

`./gradlew :plowshare-desktop:check :plowshare-desktop:assemble` reaches the
headless checks and build. Native smoke testing stays opt-in because it opens
a window and needs a desktop session. Native testing for this initial slice
was performed on macOS Apple Silicon; other platform distributions remain work
in the consolidation plan.

## Board and swarm inspection

The sidebar and Find palette open the inspector, initially scoped to the selected
project. Its project and state filters, topic search and Load more control browse
all topics owned by your account. Child topics share their root's call allowance.
The Swarm page lists members on current topics, with state and recent recorded
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
Explicit **New topic** and **Post to board** actions use `board.open` and
`board.post`; both require an authenticated project member and a durable UUID.

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

The context meter labels the latest measured **turn peak**, including its turn
number when available. A successful model fold reduces later projections but does
not rewrite the peak of an earlier request. Exact next-projection counting is a
separate explicit usage operation and may be unavailable for the selected provider.

Click **Projects** to collapse or expand its search and project/conversation list.
The choice is saved in the desktop profile. Account destinations move up beneath
Projects while its list is collapsed.
**Manage work** remains the workspace action screen. Server administration (users,
permissions and server settings) is a separate product area; these navigation
changes do not add a server administration API or screen.

### Usage and token recording

Open Usage from Connection settings, the sidebar or Find. It uses the main
workspace width, defaults to accessible account queries, and separates recorded
tokens/calls from optional reference cost comparisons. Overview, Calls, Pricing
and Recording have separate tabs. Pricing offers provider and model dropdowns
plus custom USD rates; comparisons use the current recorded token subtotal,
recalculate locally, and never replace saved cost estimates. Rates show their
check date and source, and incomplete measurements stay marked as partial.
Initial reports, live
replacement snapshots and reconnects use WS only. The server now enables durable
token recording by default; optional configured prices determine detailed cost
estimates. Capture-disabled, unknown, empty and stale states stay explicit.
Historical activity from before recording began is not reconstructed as zero.

The context preview uses the authenticated `conversation.context.snapshot` WS
frame, routed through the conversation's owning project session. Refresh builds
current context without generating or counting; explicit Count tokens measures
the same snapshot. Image IDs are shown while image bytes are explicitly omitted.
Temporary execution prompts are not anticipated.

Run `node --experimental-strip-types scripts/usage-smoke.mjs` after building to
verify the full workspace UI, push updates, reconnects and context preview.

## Skills and orchestration commands

The right **Info** panel has exactly three tabs: **Context**, **Commands**, and
**Files**. Context retains the constructed projection preview and token count.
Files contains project file access, saved folder controls and synchronization.
The tabs support arrow keys, Home and End. Adding a project opens Files.

The selected Bot's server-provided command catalog appears directly in **Info → Commands**.
Commands include help and executor details. Clicking one prepares an editable
draft; Send uses ordinary conversation transport. A portable skill without a
package mode requires an explicit context selection. The server binds the
invocation and the handling Agent calls `command_dispatch`.

Orchestration commands also have a **Start workflow** button. It submits the
editable message through `orchestration.start`, using the selected Bot's grant
and the conversation's project, without a caller-model dispatch. The server
creates the workflow's caller conversation; existing chat history is not passed
as context. Follow progress and plan-review questions in Runs. The desktop saves
the request ID before submission and keeps it after an uncertain acknowledgment,
including across page reloads; retrying the same work recovers the same run.
After a confirmed receipt, another explicit launch creates a new run.

`pnpm test:info-board` verifies the three tabs, readable Post button, new topic
selection, retained request identity after failure and a subsequent person post.

Run `node scripts/build.mjs` then
`node --experimental-strip-types scripts/commands-smoke.mjs` to verify command
help, explicit mode selection, preserved multiline arguments, compact-window
visibility, direct workflow launch and recovery without duplicate runs. See
[server skill conventions](../docs/skills-and-agent-rules.md).

Server administrators can open **Server administration** from the sidebar to manage accounts, roles, password resets, sessions and audit history. See the [administration manual](../docs/server-administration.md).

Service accounts are managed under **Server administration → Service accounts**. Grant server project roles, issue expiring scoped credentials, rotate or revoke individual tokens, and disable an integration account. Machine credentials are revealed once and excluded from desktop snapshots and saved preferences. See [server administration](../docs/server-administration.md#service-accounts-and-scoped-tokens).

Server pricing is under **Server administration → Pricing**. Edit currency, input/output/cache rates, per-attempt fees and tiers for a served route/model. The editor prevents stale changes and preserves recorded cost snapshots. Its usage button opens recorded statistics. CLI/TUI equivalents are documented in [server administration](../docs/server-administration.md#model-pricing-and-usage-statistics).
