# Headless TypeScript CLI

Development launcher: `bin/plowshare-cli`. It uses the shared TypeScript client's
WS operations and runs without a TTY, Ink, React or Electron. The existing
`bin/plowshare` server and `bin/plowshare-talk` launchers remain available.

For `outgoing send`, `outgoing peers`, `outgoing status` and `outgoing cancel`,
follow the [A2A sending manual](../docs/a2a-sending.md).

Build from the repository root with Node 22.12+ and the pinned pnpm:

```sh
./gradlew :plowshare-cli:assemble :plowshare-cli:check
bin/plowshare-cli --help
```

Separate pnpm installs link the locally built shared client. Gradle builds that
package first. The CLI depends on `plowshare-client-ts` and `plowshare-client-node`; `ws`
and TypeScript are development/test dependencies. Publishing/distribution and
typed response DTOs and MCP parity remain later work.

Run `bin/plowshare-cli --server <server-origin> login` once to save tokens shared
by CLI, TUI, MCP and desktop. Only login prompts; passwords are hidden and never saved. `logout`
revokes the shared session and removes its local file. See
[shared login](../docs/client-login.md) for origins, persistence,
renewal coordination and recovery. `PLOWSHARE_HANDLE` and `PLOWSHARE_PASSWORD`
remain ephemeral automation overrides. Login, refresh, logout and ticket
acquisition use HTTP. Durable
operations and file serving use WS. Git object transfer uses the server's existing
authenticated smart HTTP endpoint; union control and conflict operations use WS.
There is no supported WS Git-object contract. The CLI serves local files only
with an explicit `--root`; local commands default to off.

Select the server with `--server ORIGIN` (`--url ORIGIN` is an alias), or set
`PLOWSHARE_URL` for repeated commands. An explicit flag overrides the environment;
if repeated, the last `--server`/`--url` wins. The value must be an HTTP(S) origin
without credentials, a path, query or fragment. Online commands require a server;
there is no default endpoint. Help, version and offline validation need none.
Saved credentials are selected by the chosen origin, so switching servers uses
that server's login.

```sh
bin/plowshare-cli --server https://plowshare.example.com login
bin/plowshare-cli project list --server https://plowshare.example.com
export PLOWSHARE_URL=https://plowshare.example.com
bin/plowshare-cli project list
```

Options may precede or follow the command. Use `--` before literal arguments
beginning with a dash. `--project` overrides
`PLOWSHARE_PROJECT`, and `--global` explicitly overrides it with global scope.
JSON payloads may name another project, including `project:null` for global.
Omitted recall limits, budgets and optional pagination retain the server's defaults.
`web search` requires explicit `pageSize`, `max` and `page`: that service has no
paging defaults.

```sh
bin/plowshare-cli --json --project repo memory index
bin/plowshare-cli --project repo memory recall 'build instructions'
bin/plowshare-cli --global search '{"q":"build failure","offset":0,"limit":20}'
bin/plowshare-cli --project repo --wait --timeout-ms 300000 memory digest
bin/plowshare-cli --project repo memory curate '{"maxModelCalls":20}'
bin/plowshare-cli job status job_1
bin/plowshare-cli --wait job cancel job_1
bin/plowshare-cli --poll-ms 1000 --timeout-ms 300000 job wait job_1
```

`--json --help` emits command schemas generated from shared request/reply contracts,
including required/optional fields, examples, scopes, paging, mutation classification
and exit semantics. Human help lists every implemented ordinary command from the same
command catalog. `--validate` parses and resolves the effective operation and scope
without credentials or a server; grants and retained-reference semantics remain
server checks. To regenerate contracts after changing types, run
`pnpm --dir plowshare-client-ts schemas`; a coverage test rejects stale schemas.

```sh
bin/plowshare-cli information acquire '{"scope":{"kind":"personal"},"requestId":"00000000-0000-0000-0000-000000000001","url":"https://example.com/source"}' --json --validate
bin/plowshare-cli agent run '{"agent":"my_agent","task":"Review the evidence"}' --project repo --json --validate
```

Direct orchestration starts name a caller agent with an existing grant and a stable
UUID `requestId`. The authenticated socket supplies the account/session; project
membership, caller availability, resolved grants, pinned source and normal runtime
hooks are enforced. The start submits directly to the service and returns an
`orchestration` ID (`orc_…`); a script has no conductor model calls. Start itself
exits **3: accepted/running**, which is a successful submission to follow, not a
failure. Model-driven definitions may still use conductor model calls to execute.

```sh
bin/plowshare-cli orchestration start '{"agent":"farnsworth","definition":"deep_research","request":"Compare evidence and contradictory findings","requestId":"00000000-0000-0000-0000-000000000001"}' --json --project repo
bin/plowshare-cli orchestration receipt 00000000-0000-0000-0000-000000000001 --json
bin/plowshare-cli orchestration status orc_RETURNED_ID --json
bin/plowshare-cli orchestration wait orc_RETURNED_ID --json --timeout-ms 300000
```

Retain the key before submitting. Receipt lookup is a read and recovers the original
run after a lost acknowledgment. Explicitly repeating the same account/key/input
returns that run without reopening hooks or speaking another first turn. A different
agent, definition, project, request or context with the same key is refused. Socket
sessions may change on reconnect. Replays remain disabled: no timeout, disconnect or
interrupt repeats a mutation. Wait reads status until finished (exit 0), failed (1),
asking/cancelled/capped (4), or transport/deadline/protocol uncertainty (5). Asking
responses preserve the question text and structured choices in `outcome.payload.messages`.
Interrupting wait leaves the run inspectable and running.

JSON failures retain `status`, `said`, `outcome` and IDs while adding `code`, effective
`scope`, typed `ids`, `submission`, `nextActions` and `automaticReplay:false`.
Codes include `INVALID_INPUT`, `AUTHENTICATION_REQUIRED`, `CONNECTION_BEFORE_SUBMISSION`,
`SUBMISSION_UNCERTAIN`, `READ_FAILED`, `TIMEOUT`, `INTERRUPTED`, `INVALID_SERVER_RESPONSE`
and `SERVER_REFUSED`. Before-submission connection failure permits retry; uncertain
orchestration submission directs receipt lookup; known jobs/runs direct inspect/wait.
A read failure permits retrying the read. Refusals direct input/authority correction.
Native transport error text is excluded from CLI diagnostics to protect credentials.

The twelve memory verbs are `index`, `read`, `recall`, `write`, `navigate`,
`digest`, `curate`, `proposals`, `resolve`, `reconsider`, `invalidate`, and
`reembed`. Read takes a memory id; recall/navigation take question text or JSON.
Curation requires a project. Complex mutations take a JSON payload using the
server WS names (`memory`, `proposal`, `accept`, `by`, `reason`), rather than
HTTP/legacy adapter field names. Memory write judgement stays server-owned.

The CLI covers 139 WS operations: 129 one-shot operations, two
persistent observer subscriptions and eight mutations through rooted sync workflows. Additional verbs are:

| Family | Verbs |
| --- | --- |
| conversation | open, list, latest, lifecycle, turns, compactions, chat, trajectory, context, projection, resume, follow |
| agent | list, define, run |
| job | list, limits, status, cancel, watch (poll/result/wait alias status) |
| document | ask, retrieve, list, detail/outline, chunk, rank, stance, citations, search |
| information | all 34 scoped source, evidence, report, lifecycle and migration controls; see [information guide](../docs/information-system.md) |
| web | search, fetch |
| project | list, define, lend, unlend, workspace, move, forget, member-add, member-remove |
| approval | list, answer, revoke |
| orchestration | definitions, start, receipt, list, status, wait, answer, cancel, caps, record |
| usage | conversation, project, agent, run, orchestration, models, pools, calls (authenticated ledger reads) |
| inbox / todos | inbox list/read; todos read |
| schedule | list, define, read, pause, forget |
| trigger | list, define, pause, forget |
| event / firing | event fire; firing list |
| provider / board | provider list/deregister; board topup |
| maintenance | buffer purge; retention sweep |
| union | status, conflicts (reads) |

Single-id reads and document queries accept text. Complex requests use the exact
WS payload names as JSON; unknown fields in these additional operations fail
before authentication. Project administration requires explicit payload names;
workspace/lent/roots paths refer to the server's disk, never the CLI's cwd.

```sh
bin/plowshare-cli --project repo conversation open
bin/plowshare-cli conversation trajectory '{"conversation":"c","tail":true,"limit":20}'
bin/plowshare-cli --project repo --wait agent run '{"agent":"a","task":"Review the build"}'
bin/plowshare-cli --wait agent run '{"agent":"a","task":"Continue","conversation":"c"}'
bin/plowshare-cli --wait conversation resume '{"conversation":"c","maxModelCalls":100}'
bin/plowshare-cli document search '{"query":"build","mode":"HYBRID","limit":5}'
bin/plowshare-cli --wait document ask '{"document":"d","question":"What is the claim?"}'
bin/plowshare-cli web search '{"query":"build","pageSize":10,"max":30,"page":1}'
bin/plowshare-cli web fetch '{"url":"https://example.com","offset":809}'
bin/plowshare-cli project define '{"name":"repo","workspace":"/server/repo"}'
```

An `agent run` without `conversation` opens a fresh conversation and submits its first
turn in one authenticated request. No selected project means global; an explicit
payload project, `--project` or configured `PLOWSHARE_PROJECT` selects that project.
Use `--new-conversation` to state this choice explicitly. It conflicts with an existing
conversation id. `--standalone` (or JSON `newConversation:false`) retains a job-only
run and supports images. New and reused conversation turns retain the existing image
restriction. The accepted response includes both job and conversation ids; `--wait`
keeps the conversation id in its final output. Offline `--validate` shows the resolved
`newConversation` choice without creating anything.

```sh
bin/plowshare-cli agent run '{"agent":"a","task":"Review the evidence"}' --json
bin/plowshare-cli agent run '{"agent":"a","task":"Review the build"}' --new-conversation --project repo --json
bin/plowshare-cli agent run '{"agent":"a","task":"Continue","conversation":"cnv_RETURNED_ID"}' --json
bin/plowshare-cli agent run '{"agent":"a","task":"Inspect this image","images":["img_1"]}' --standalone --json
```

Other job families and orchestrations already own their conversation logs; the
conversation options apply to `agent run`. Opening and submitting use existing
account/project checks, operator budgets, turn caps and log hooks. A lost response
remains uncertain; the CLI never repeats the submission automatically.
A conversation turn uses the conversation's project and does not inherit
`--project` or the environment tier. Non-null project or images beside a
conversation are refused. `agent run` cannot override `maxModelCalls`; conversation
open/resume and job limits own those budget fields. Agent run/resume default to
the event socket's session identity, with no local file presence; an explicitly
supplied session (including null) is preserved and authorized by the server.
Document citation scope is one conversation, one document, or neither for all.
Document search retains coverage counts and source identifiers. Web provider
refusals embedded in an `OK` payload produce a refused status/exit while retaining
the original outcome. Fetch retains the server's real `nextOffset` without
inventing a continuation or automatically fetching another page.

Approval listing names exactly one of `conversation`, `project`, or `mine:true`.
An omitted scope inherits `--project` only when neither conversation nor mine is
selected. Decisions are `once`, `conversation`, `project`, or `deny`; a project
decision needs the actual command prefix. An `OK` decision with a continuation
job is shown as accepted (original code preserved); `--wait` reads that job's
status. A busy decision with no job remains a completed decision, with its busy
flag and note preserved. No extra agent run is submitted.

Schedule reading returns a proposal and saves nothing. Define/pause/forget are
separate explicit requests. Trigger definitions without a conversation inherit
the current project; conversation triggers inherit their conversation's home and
limits and refuse project/budget/cap overrides. Event firing returns the server's
firings without waiting for their jobs. Orchestration answers accept text or the
server's structured JSON choices. Record paging preserves the root, direction,
entry kinds and returned cursor; it never folds orchestration ids into job ids.

```sh
bin/plowshare-cli approval list '{"mine":true}'
bin/plowshare-cli --wait approval answer '{"id":"approval_1","decision":"once"}'
bin/plowshare-cli orchestration record '{"root":"run_1","tail":true,"limit":20}'
bin/plowshare-cli schedule read '{"text":"every weekday at nine","zone":"UTC"}'
bin/plowshare-cli schedule define '{"schedule":"weekday","cron":"0 0 9 * * MON-FRI","zone":"UTC","emits":"daily"}'
bin/plowshare-cli trigger pause '{"trigger":"daily-check","paused":false}'
bin/plowshare-cli event fire '{"event":"daily","data":{"reason":"manual"}}'
bin/plowshare-cli inbox read '{"items":["item_1"]}'
bin/plowshare-cli --project repo union conflicts
```

Union mutations run through the shared Node Git synchronization workflow below,
which owns the rooted claim, transfer, commit acknowledgement and conflict
resolution. Raw union mutation JSON remains withheld. `job.stream` and
`conversation.follow` use the persistent observer commands. Server permissions
remain authoritative for approvals, account-owned operations and project
administration. Retention sweep, buffer purge and provider administration have
deployment scope, independent of the current project.

For multiline or large payloads, use stdin explicitly:

```sh
bin/plowshare-cli --json --project repo --payload - memory write <<'JSON'
{"proposal":{"summary":"Build uses pnpm","scope":"this repo","body":"Run pnpm build","formedBy":"person","formedWhere":"local build"}}
JSON
```

Without `--wait`, digest/curation, agent runs, conversation resumes and document
questions return their accepted handle immediately.
`job status`, `job poll`, and `job result` read one durable status; `job wait`
or `--wait` polls until an outcome or refusal. A cancellation acknowledgement
is still pending until a status response carries a terminal outcome. The default
whole invocation deadline is 30 seconds; set a longer one for synchronous
reembedding or explicit waiting.

Ordinary unrooted commands produce one result. Readable output names its status and keeps
the server payload. `--json` emits one JSON object on stdout:

```json
{"operation":"memory.digest","status":"accepted","outcome":{"code":"ACCEPTED","payload":{"id":"job_1"}},"job":"job_1"}
```

Server payloads retain provenance, unsearchable counts, navigation completeness,
job endings and additional fields. Local errors use `status:"error"`; uncertainty
after submission uses `status:"unknown"` and retains any known job handle. Native
errors, credentials, upgrade URLs and stack traces are never printed.

| Exit | Meaning |
| --- | --- |
| 0 | Synchronous operation completed, or job ended `ANSWERED` |
| 1 | Server refused the request, or job ended with a known failure |
| 2 | Usage, configuration or explicit authentication refusal/password change |
| 3 | Job accepted, running, or cancellation still pending |
| 4 | Navigation incomplete, or a terminal job was truncated, cancelled, awaiting, stuck, or ended with a future name |
| 5 | Protocol/transport failure, deadline, interrupt, or completion unknown |

Timeout, disconnect, Ctrl-C and process termination detach the local observer.
They never replay a mutation or cancel a server job automatically. Inspect a
known handle through `job status` in another invocation. A submission that lost
its reply may have taken effect even when no handle was received.

## Persistent observation

```sh
bin/plowshare-cli --global --watch --timeout-ms 300000 agent run '{"agent":"a","task":"Review this question"}'
bin/plowshare-cli --json --global --timeout-ms 300000 job watch job_1
bin/plowshare-cli --json --global --timeout-ms 300000 conversation follow cnv_1
```

`--watch` implies `--wait` and subscribes to `job.stream` before submitting the
operation. It emits matching progress and token deltas, preserving thinking and
answer parts, then reads durable `job.status` until an outcome is available.
Early progress is buffered with a 64-event bound and matched to the accepted job.
An `ended` push or assembled token text never supplies the final answer. Partial
or cancelled durable outcomes retain the same exits as ordinary waiting.

`job watch` observes an existing handle through durable status. Token delivery
is scoped to the server's original run session: a new CLI process does not
receive a replay of another session's tokens. Polling still works when no
progress pushes arrive. Watching an existing job does not open a conversation or cancel
a job when stopped.

`conversation follow` holds the `conversation.follow` subscription and emits
matching `conversation.appended` notifications with their `through` cursor.
These notifications announce log growth; they contain no message content and
provide no replay. Read `conversation trajectory` or `conversation chat` to
inspect durable content. Unknown fields are preserved; foreign conversation
notifications and invalid cursors are excluded.

Observer `--json` output is newline-delimited JSON, with separate accepted,
running/following, event and final records. Ordinary commands still emit one
JSON result. Observers use the whole invocation deadline (30 seconds by default)
and end on interrupt or connection loss. The final status is `stopped` for an
interrupt/deadline or `unknown` for a lost observer; both exit 5. There is no
automatic reconnect, resubscription, mutation replay or job cancellation.

## Explicit local rooting and synchronization

```sh
bin/plowshare-cli --project repo --root /absolute/repo --timeout-ms 3600000 client root
bin/plowshare-cli --project repo --root /absolute/repo --wait agent run '{"agent":"a","task":"Read the files"}'
bin/plowshare-cli --project repo --root /absolute/repo --timeout-ms 3600000 sync on
bin/plowshare-cli --project repo --root /absolute/repo --sync --timeout-ms 3600000 client root
bin/plowshare-cli --project repo --root /absolute/repo sync status
bin/plowshare-cli --project repo --root /absolute/repo sync hidden .github .eslintrc
bin/plowshare-cli --project repo --root /absolute/repo sync conflicts
bin/plowshare-cli --project repo --root /absolute/repo sync resolve src/a.ts theirs
bin/plowshare-cli --project repo --root /absolute/repo sync off
```

`--root` requires an absolute existing directory and a named project. It never
infers cwd. Invalid roots fail before authentication. The file channel must
acknowledge that exact project before the CLI reports it rooted or submits a job.
The server owns competing-claim policy. Files remain fenced; traversal, symlink
escapes and hidden paths are refused, and local commands default to off. PDF/image
bytes stream unchanged over the file WS; conversion, image storage and the
text-window cache remain server-side.

`client root` holds the claim until interrupt, deadline or either socket is lost.
Named-project agents in other sessions can use the presence according to server
permissions. `--root --wait` or `--root --watch` keeps files served during a job.
Detached rooted job operations are refused before signing in; ordinary commands
withdraw their claim when complete. Payload project overrides must match the root.

`sync on` explicitly enables a union, transfers the Git snapshot and acknowledges
the pushed commit over WS, then holds the root. `--sync` reconnects an already
enabled union and never enables one. Git 2.50+ is required. Sync uses the isolated
`.plowshare/sync.git` shadow, its existing path fences and conflict handling.
Git bearer tokens rotate in memory and are supplied only to the child environment;
they are never stored in the repository config. `sync off` explicitly removes the
server mirror and local sync shadow. `hidden` replaces the server's hidden-path
allowlist; an empty list is not available through this grammar. `resolve merge`
stages a merge file and prints its location; edit it, then use `resolve <path> done`.

Root/sync commands and sync-enabled invocations emit NDJSON with `--json`.
Root and sync progress precede their completed/serving record. Lost presence,
failed sync or a missing/invalid acknowledgement produces exit 5 and withdraws
local serving. Interrupt kills in-flight Git processes and stops queued ticks;
there is no automatic reconnect, mutation replay or job cancellation. A stopped
sync process does not disable an enabled server union. Inspect `union status`
and `sync conflicts` before retrying after uncertain completion.

Verification uses real HTTP-auth/WS fixtures and compiled subprocesses with
piped stdin/stdout. It covers the one-shot/observer catalogs and rooted sync workflows, scope and session identity,
embedded refusals, paging/provenance, accepted/partial
outcomes, durable waiting, cancellation, disconnect, deadline, Ctrl-C and headless
authentication. It does not validate a deployed Plowshare server or model.
Legacy CLI grammar/rendering parity,
typed response DTOs, credential storage, MCP parity and production packaging
remain work. Multipart
document/image upload has no supported WS equivalent and is not exposed here.

Information commands take an explicit scope in the JSON payload, independently
of the conversation `--project` option. The shared validator preserves admission
receipts as pending work; only `information ask` returns a job suitable for
`--wait`/`--watch`. Source processing uses `information status` with its revision
or acquisition ticket. Keep mutation UUIDs across uncertain delivery.

```sh
bin/plowshare-cli --json information list '{"scope":{"kind":"personal"},"limit":20}'
bin/plowshare-cli --json information status '{"scope":{"kind":"project","project":"research"},"revision":"REVISION_UUID"}'
bin/plowshare-cli --wait --timeout-ms 300000 information ask '{"scope":{"kind":"personal"},"revision":"REVISION_UUID","question":"What supports this claim?"}'
```
