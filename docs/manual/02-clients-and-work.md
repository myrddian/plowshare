# Clients and everyday work

## One server, several interfaces

The desktop, TUI, web console, CLI and MCP adapter expose the same server-owned
work through different interfaces. Capability coverage and presentation differ.
A client reconnect establishes a new connection to durable state; it does not
repeat the last mutation to reconstruct the screen.

| Client | Useful for | Work stays where? |
| --- | --- | --- |
| Desktop | Conversations, project navigation, Library reading, reports and inspection | Server; explicit local file presence stays on the client |
| TUI | Terminal conversations, commands, project work and live run inspection | Server; attached checkout stays on the client |
| CLI | Explicit operations, scripting, status and repair | Server; local files only with an explicit root |
| Web console | Browser access to its implemented conversation and management surfaces | Server |
| MCP | Exposing Plowshare operations to another tool-using host | Plowshare owns the submitted work; the host owns its own session |

The repository clients use shared TypeScript transport and operation contracts.
The Java client remains available for existing tooling. SDK consumers can use
the same operations without embedding a GUI.

## Conversations, projects and bots

Select the project before starting work that should live there. A conversation
retains its identity, transcript, runs and context history. Selecting another bot
changes which definition handles future work; it does not rewrite past entries.
Delegated children have their own logs and remain connected to the parent work.

The desktop restores saved server preferences and supported durable handles.
Client-only project attachments and local file presence have their own lifetime;
do not assume a saved account-wide project entry means a folder is currently
being served. Refreshing a roster or listing reads state; it does not submit a
new agent run.

The TUI supports slash commands and completion. Use its help and command catalog
for the current project/session; bound skill and orchestration commands depend
on the effective definitions and grants. Type a normal message to converse.
An administrative command performs the named operation and need not involve a
model. `/information` is the terminal path for retained documents and the manual.

Desktop's trajectory shows each recorded model response, including responses that
only request tools. Model rows carry the recorded model duration and provider-reported
input and output token counts; selecting one also shows any recorded reasoning-token
count, which is a subset of output tokens. A response requesting several tools is one
inference. Each tool has its own execution time. Missing usage is shown as not recorded,
and a recorded zero remains zero. These counts describe past inference calls; the
Context panel constructs the next request and its separate token count.

## Follow work and read its result

For explicit CLI work, choose a project with `--project NAME` and use the command
help to construct the payload. `--validate` checks an operation offline without
signing in or submitting it. `--json` exposes structured outcomes.

```sh
bin/plowshare-cli --help
bin/plowshare-cli agent run --help
bin/plowshare-cli --project my-research --validate agent run '{"agent":"interlocutor","task":"Explain the project goals."}'
bin/plowshare-cli job status <job-id>
bin/plowshare-cli job result <job-id>
bin/plowshare-cli job watch <job-id>
bin/plowshare-cli conversation follow <conversation-id>
```

`--wait` follows supported accepted jobs; `--watch` also presents progress.
Leaving a watch is not cancellation. Explicit cancellation requests stop future
work cooperatively and can leave useful partial results or already committed
effects. After cancellation, inspect the job rather than treating it as though
it never ran.

JSON CLI exit codes distinguish completed (0), refused/failed (1), usage/auth (2),
accepted or running (3), incomplete/cancelled/awaiting (4), and uncertain transport,
deadline or protocol (5). A process exiting 3 can have successfully submitted
work. Scripts should retain the returned IDs and interpret the result shape,
not assume every nonzero exit means nothing happened.

## Library, reports and knowledge

Click the toolbar **Help** question-mark icon to open **Library → Manual**. A
shared [manual installation](installation.md) supplies the index and chapter
links; the reader resolves links to the current revision of each chapter.

In the desktop Library, open **Documents → Manage document sources and imports**
to browse retained sources, including the installed manual. Choose the source
scope and use tags, author, format, saved date or text filters. The **Reports**
tab browses generated reports. Source and report collections expose different
document kinds. A source can have several retained revisions; a report may be
a readable draft or a final published report. Inspect the revision's status if
its text or derived projections are unavailable.

The source reader can show rendered text or the retained text window. Reading a
long chapter may require another window. Asking a source question is model work
with a job and allowance. Reading saved text or filtering the catalogue is not a
request to re-run research or publish a draft.

User tags belong to the resource. Automatic tags and category suggestions are
revision-specific model output and navigation aids; they are not evidence.
Manual chapters use the user tag `plowshare-manual`, so they remain discoverable
without depending on model-generated tagging.

## Approvals, inbox and background work

An approval presents the particular pending operation and its identity. Answer
that displayed request; do not reuse another run's approval key. Declining,
leaving an approval for later and granting a request have different effects.
Hooks and access checks can still deny work that a person approved.

Command approvals suspend the original tool call in the harness. Approving resumes that
retained call with its original arguments, input, timeout and client session; the model
does not submit `run` again. The model receives the command's result afterward. Denying
returns the answering identity and optional denial reason as the tool result. Approval
requests and decisions remain visible in the trajectory and Inbox, without retry
instructions entering the model conversation. If execution was interrupted after its
durable start fence, recovery reports an unknown outcome and does not replay the command.
Recovery keeps the original execution owner and client session. If a delegated run's
owner cannot restore its execution lease, the owning account receives an Inbox notice;
the harness does not restart the root model to reconstruct the delegation.

Desktop reads pending approvals from both the signed-in account and each open
conversation. Account discovery includes foreground questions in conversations owned
by that account, even when no view is open, as well as pinned background requests.
Conversation reads also support servers predating account-wide foreground discovery.
Opening or refreshing a conversation rediscovers its pending requests without
submitting another agent turn. A run
ending `AWAITING` also refreshes its conversation's questions.

Every command approval also reaches the owning account's durable Inbox through
Relay. The Inbox alert is shared across clients; chat controls are another view of
the same stored decision. Lost alerts and expired publications are reconciled from
the retained approval, without starting a model turn or executing the command.
Desktop also refreshes questions when a followed conversation grows, including work
started in another window. A failed listing leaves previously read requests visible
and does not hide requests successfully read from another scope. Use **Check command
approvals** to retry discovery; failures remain visible beside that control.

Before sending chat text, Desktop checks that conversation's pending command approvals.
Chat text, including `yes` or `no`, is held while a command request is pending.
Use that request's **Approve** or **Deny** control; discovery cannot authorize a
command the person has not seen. A decision that overtakes the check causes a fresh
read and leaves the chat text unsent. Approval continuation includes the exact command argument array
and directory so embedded spaces and literal quote characters remain distinct.

The inbox holds durable deliveries and read receipts. Opening the application or
seeing an unread count is not proof that an item was read. Open the item to read
its actual result, and inspect its source work when more context is needed.
Scheduled work, messaging and orchestration can produce background results while
another conversation is selected.

## Local files are an explicit capability

Desktop **Add project folder**, TUI attachment commands, and CLI `--root` open a
fenced file channel. The server sees operations on that root through the client;
it does not receive unrestricted access to your computer. A path on the client
is not a path on the server. Withdrawing local presence removes that capability
without deleting the server's project, conversation or retained knowledge.

Git-backed synchronization is a separate explicit mode. Attaching files does not
enable it. Resolve a conflict using the reviewed file and conflict state; a stale
preview is not permission to overwrite a changed file.

For a source checkout, run `./gradlew :plowshare-tui:assemble` before
`./bin/plowshare-talk`. This installs the TUI dependencies and builds its linked
SDK and native MCP runtime packages. Run it again after pulling dependency changes;
the launcher reports this remedy when a required runtime package is missing.

### Named connections

Desktop, CLI and TUI use the same named server/account registry. The desktop
connection dropdown lists saved connections and offers Add / manage connection.
Use `plowshare-cli connection add NAME SERVER ACCOUNT`, then `--connection NAME`
for login or work. `plowshare-talk` accepts the same selection and connection
management verbs. `connection select NAME` saves the default for future launches.
Two accounts on one server and the same handle on different servers have separate
sessions, Personal checkouts, bookmarks, drafts and job receipts. Switching closes
local access without cancelling server work or replaying an uncertain submission.
See [shared local login](../client-login.md) for precedence, storage, migration,
rename/remove and recovery behavior.

## Deploy an Application

Desktop offers **Deploy** beside Applications for connected server administrators.
Choose a folder containing `plowshare.json`, enter the project and explicit server
FileStore destination, and read deployment status before submitting. The CLI
provides `application deploy ./folder '<deployment JSON>'`. Server resource loading
continues after the client closes. Updates retain project identity and its durable
work; activation selects a retained source revision.

After an uncertain reply, inspect the retained request receipt. Desktop preserves
its pending submission across reload and provides **Read pending receipt**. It
never automatically repeats the deployment. Deployment does not start an external
Python service or grant access/tool permissions. See [Application deployment](../projects.md#deploy-update-and-activate-an-application)
for package limits, commands, FileStore authorization and rollback semantics.

### MCP tool connections

Desktop's project **Tool connections** dialog separates **This client** HTTP/STDIO
profiles from **Server** HTTP endpoints, per-account credentials and assignments.
Saving a local profile does not start it; launch and sharing with server-owned work
require separate consent. Closing the dialog retains hosts, while disconnect/account
change/app exit stops them. TUI `/mcp` provides guided forms for the same choices.
Console Projects exposes **Server connections** only. See [native MCP controls](../native-mcp-hosting.md#client-controls)
and [server credential management](../provider-management-clients.md).
