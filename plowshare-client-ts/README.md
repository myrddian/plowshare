# Shared TypeScript client

The shared core owns the neutral WebSocket connection, request envelopes,
authentication/bootstrap interfaces, file-channel wire types and job identity
lifecycle, shared request builders and reusable response readers.
TUI, headless CLI and Electron main use its compiled JavaScript through public
package exports. It has no runtime dependencies; installing it does not install a
terminal renderer or Electron.

From the repository root:

```sh
cd plowshare-client-ts
pnpm install --frozen-lockfile
pnpm build
pnpm test
```

Or run `./gradlew :plowshare-client-ts:check :plowshare-client-ts:assemble`.
Use Node 22.12+ and the pinned pnpm version. Consumers use separate installs
with `link:../plowshare-client-ts`; build this package before checking or
launching them. Gradle declares that build dependency and the shared source
inputs. No root workspace install is required.

Gradle resolves Node and pnpm from the invoking shell's `PATH` before running
each task, so an existing daemon picks up installed tools and version changes.
If a tool is missing, the task reports which one to install.

```ts
import { connect } from 'plowshare-client-ts/binding/connection'
import { signIn, openSocket } from 'plowshare-client-ts/binding/auth'
import { JobLifecycle } from 'plowshare-client-ts/jobs'
```

Sockets and HTTP authentication are supplied through structural interfaces.
The package has no platform globals, credential persistence, automatic
reconnection, HTTP operation fallback, or mutation replay. Authentication's
existing refresh-before-ticket behavior is preserved.

Every operation with a supported server WebSocket contract must use it.
HTTP authentication/bootstrap, multipart uploads and Node Git object transfers
remain documented exceptions where the server lacks an equivalent WS contract. Wire declarations
do not establish frontend coverage or a functioning local filesystem claim.

`JobLifecycle` routes early and ongoing events by server handle, independently
of the selected conversation. Its bounded buffer reports loss requiring durable
WS status/trajectory reconciliation. Connection generations reject stale replies;
disconnect retains known handles as uncertain and prevents automatic submission
replay. A cancellation acknowledgement or ended push does not establish a
terminal outcome: only a readable server status outcome does. Approval
continuations get their own ticket and returned job handle.

TUI and desktop both use this lifecycle. Rendering, polling timers and HTTP/WS
platform implementations remain with their consumers. TUI preserves its existing
behavior of exiting on a lost connection; desktop reconnects and reconciles
retained known handles. Neither polls temporary submission ids. TUI reconnect
ergonomics, broader credential policy and frontend recovery remain work before
phase 1 is complete.

`operations/session` owns the 43 existing conversation, agent/job, approval,
inbox, orchestration and schedule/trigger request builders. `operations/views`
owns 14 reusable response readers and 11 domain types for projects, conversations,
agents, approvals, trajectory pages and stream events. `binding/job-view` mirrors
the Java job, outcome, limits and pace records, checked against those sources.
Job replies require matching identity and a boolean terminal answered flag;
unreadable replies leave completion unresolved. Unknown server fields survive
validation, and direct dispatch retains the original outcome. Desktop consumes
these readers through package exports without importing TUI source. Markdown
rendering and the remaining response families stay in the frontends. `operations/direct` provides a typed WS catalog,
adapter-neutral command parsing and one-request dispatch for all twelve direct
memory verbs, conversation search, and job status/cancel. It retains the full
server outcome and distinguishes accepted handles, incomplete navigation,
running jobs and cancellation intent. There is no implicit wait or retry.

`operations/retrieval` defines 33 wire DTOs and checked reply types for 21
memory, proposal, document, conversation-search and web operations. The DTOs are
held to the actual Java record fields and types. `retrievalReply(type, payload)`
returns the original checked value or `undefined`; it preserves future fields,
nullable/stale evidence and explicit coverage without coercion or invented
arrays/counts. Shared dispatch returns `invalid-response` for damaged replies,
including unreadable mutation acknowledgements, and never replays them. It
keeps embedded web refusals and incomplete memory navigation distinct from
successful findings. Legacy metadata omissions remain absent. Remaining
administrative/inbox/orchestration/schedule/record response domains are still work.

The TUI exposes `/memory`, `/search` and `/job` without opening a conversation
or running an agent. Simple forms include `/memory index`, `/memory read mem_1`,
`/memory recall build instructions`, `/memory navigate build instructions`,
`/memory digest`, and `/job status job_1`. Complex requests take a JSON object:

```text
/memory write {"proposal":{"summary":"Build uses pnpm","scope":"this repo","body":"Run pnpm build","formedBy":"person","formedWhere":"local build"}}
/memory resolve {"proposal":"prp_1","accept":false,"by":"person","reason":"stale"}
/memory invalidate {"memory":"mem_1","reason":"stale","by":"person"}
/search {"q":"build failure","offset":0,"limit":20,"project":null}
```

Tiered commands use the current project unless JSON explicitly names another
project or `project:null` for global. Curation requires a project. Defaults for
budgets, recall limits and pagination remain server-owned. Digest/curation return
handles immediately; `/job status` reads the durable outcome and `/job cancel`
requests cancellation. The [headless CLI](../plowshare-cli/README.md) now consumes
this same catalog and parser, plus `operations/catalog` and
`operations/commands` for 82 one-shot WS operations including administrative families and union reads.
`Payloads` additionally includes ten operations for persistent observers/rooted
filesystem adapters, covering all 129 registered WS request types. CLI `job watch`,
`--watch` and `conversation follow` now use two of these subscriptions through
`operations/observation`, `operations/union` and the Node platform supply eight rooted sync mutations,
bringing CLI coverage to all 129 registered WS operations. The shared helpers bound
early progress and filter actual job/conversation identities; the frontend owns
socket lifetime, output and durable status polling.
`operations/administration` owns these payload shapes and named adapter boundaries. `parseCommand` preserves the narrower
TUI `parseDirect` boundaries. `withSession` binds run/resume requests to an explicit
event socket identity without replacing a supplied session. It uses shared `waitForJob` for explicit durable status polling with
platform-supplied pacing/interruption, environment authentication, JSON/readable
output and distinct exit statuses. Remaining response DTOs, secure credential
storage and live MCP validation remain work. File presence advertises source-byte
serving; conversion and the text-window cache belong to the server. The
[TypeScript MCP adapter](../plowshare-mcp/README.md) now uses the same WS core and
[Node platform](../plowshare-client-node/README.md) as the TUI file helpers. Approval answers preserve their `OK` decision
and track a returned continuation job; a busy/no-job acknowledgement completes
only the decision. No mutation is replayed and no agent run is invented.

Orchestration records now use shared `operations/records` request builders and
response readers. `RecordView` and `RecordPageView` mirror the full Java wire
contracts, including nullable paging fields and omitted full bodies. CLI dispatch
validates the whole page and retains the raw response, order, provenance and future
fields; malformed rows never become a completed empty result. TUI record views
reuse the same package while keeping their established display projection.

`operations/information` provides the neutral scoped socket adapter and TUI command
parser. `operations/information-payloads` and `operations/information-replies`
cover all 34 registered information frames. `parseCommand` exposes the complete
operator family with explicit JSON scope; `resultOf` validates response codes,
source/evidence identity, exact text windows and durable receipts. An accepted
source/report/acquisition remains pending. `information.ask` carries a job handle
for explicit waiting, independently of the selected conversation.

The MCP information wrapper exposes only the server's reviewed model subset;
publication and migration remain operator controls. See the
[information guide](../docs/information-system.md) and
[JavaScript research guide](../docs/scripted-research.md).
