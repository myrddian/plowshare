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
