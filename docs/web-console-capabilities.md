# Web console capabilities

The console is a browser client of Plowshare's authenticated server. Work,
records, permissions and approvals belong to the server. This inventory describes
the implementation in this revision, including the work overview and account
approvals screens. It is a source audit, not a claim that every workflow has been
verified against a deployed server.

**Supported** means the browser exposes the named workflow. **Partial** means
it exposes only part of the workflow or has a material limitation. **Missing**
means a public operation exists but the browser has no dedicated workflow.
**Unavailable** means the current public contracts cannot provide the named
capability. These labels describe client coverage, not a permission grant.

## Transport paths

Most application workflows use the shared WebSocket. Screen calls such as
`transport.get('/v1/jobs')` retain historical HTTP-looking names, but
[socketTransport](../plowshare-console/src/transport.ts) maps them to operations
such as `job.list` and calls the socket's `ask` method. The
[shell](../plowshare-console/src/screens/shell.ts) supplies that transport to
conversations, jobs, projects, proposals, memory and document search. Information,
usage, inbox and approval workflows also use socket operations.

Authentication/session/password flows, multipart document uploads and live
runtime configuration remain explicit HTTP boundaries. A stale comment mentioning
`GET /v1/jobs` is not evidence of an HTTP request. Source mapping and the packaged
client assets must be checked separately from server image revision labels and
authenticated runtime observations.

Client refresh calls are coordinated and never deliberately replayed. Rotation
refuses HTTP redirects and session probes require uncached, direct responses;
authentication responses carry `Cache-Control: no-store`. These safeguards do
not prove exactly-once HTTP delivery: abrupt-close fixture tracing observed
multiple arrivals carrying the same per-fetch diagnostic ID. Refresh now carries
one random intent; the server returns the same still-current pair for duplicate
deliveries within a fixed thirty-second maximum window. Changed/missing intents
retain reuse revocation, and uncertain application submissions remain blocked.
The [refresh-intent decision](decisions/0004-refresh-intent-receipts.md) documents
receipt storage, original expiry, account fences and the captured-request tradeoff. See
[browser connection recovery](client-login.md#browser-connection-recovery).

## Workflow inventory

| Workflow                            | Browser coverage                                                                                                                                                                                                                                                                                                                                                                     | Other client / public contract evidence                                                                                                                                         | Implementation and test evidence                                                                                                                                                                                                                                                                                                                                                                                                |
| ----------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Authentication and session recovery | **Supported:** cookie login, bootstrap, required password change, logout, session probing and refresh. HTTP is explicit. Unavailability is distinct from an invalid session. Web Locks coordinate refresh between tabs; uncertain rotation is shared across tabs and reloads. Automatic refresh is unavailable without Web Locks or writable browser storage.                                                                                             | Clients share the server authentication boundary; browser cookies differ from native credentials.                                                                               | [Authentication](../plowshare-console/src/auth.ts), [tests](../plowshare-console/src/auth.test.ts), [main](../plowshare-console/src/main.ts), [tests](../plowshare-console/src/main.test.ts), [login contract](client-login.md).                                                                                                                                                                                                |
| Navigation and project context      | **Partial:** twelve views, browser history and owning-record hash links. Chat state survives view changes. The shell selects the authenticated account's personal project; listing ordinary projects does not select one for chat and memory.                                                                                                                                        | Desktop has workspace and run navigation; TUI accepts explicit project commands. Existing project contracts support broader selection.                                          | [Shell](../plowshare-console/src/screens/shell.ts), [tests](../plowshare-console/src/screens/shell.test.ts), [view contract](../test-support/contracts/console-views.json), [desktop navigation](../plowshare-desktop/src/run-navigation.ts).                                                                                                                                                                                   |
| Conversations and trajectories      | **Partial:** create/select, submit, cancel, inspect turns, logs, context and cost; lifecycle controls and trajectory inspection. There is no dedicated conversation search workflow.                                                                                                                                                                                                 | Public `conversation.search` has validated retrieval contracts. Desktop/TUI expose additional retrieval workflows.                                                              | [Chat](../plowshare-console/src/screens/chat.ts), [tests](../plowshare-console/src/screens/chat.test.ts), [picker](../plowshare-console/src/screens/picker.ts), [trajectory](../plowshare-console/src/screens/trajectory.ts), [retrieval contract/tests](../sdk/typescript/src/operations/retrieval.test.ts).                                                                                                                   |
| Work overview and results           | **Partial:** current jobs, pending approvals, inbox outcomes and firing records link to their owning records. Reconnect reconciles retained snapshots; events are hints. It cannot enumerate every kind of durable work.                                                                                                                                                             | Desktop activity/runs and TUI run commands cover orchestration records. Public job, inbox, approval and firing operations are distinct owners.                                  | [Overview](../plowshare-console/src/screens/overview.ts), [tests](../plowshare-console/src/screens/overview.test.ts), [work adapter](../plowshare-console/src/work.ts), [reconciliation tests](../plowshare-console/src/screens/reconciliation.test.ts).                                                                                                                                                                        |
| Jobs and limits                     | **Partial:** current-process jobs, status, cancellation request and explicit budget/turn-limit changes. Client windows bound rendering; `job.list` itself is unpaged. **Unavailable:** historical job enumeration or lookup after a server restart through these operations.                                                                                                         | Job metadata is persisted, but the public list/status handlers address the in-process store. Desktop/TUI consumers have the same contract limit.                                | [Jobs](../plowshare-console/src/screens/jobs.ts), [tests](../plowshare-console/src/screens/jobs.test.ts), [list handler](../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/JobListHandler.java), [job store](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/JobStore.java), [durability test source](../plowshare-server/src/test/java/io/aeyer/plowshare/server/agents/DurableJobsTest.java). |
| Inbox                               | **Supported:** paged durable deliveries, retained result inspection, owning conversation links and explicit acknowledgement. An inbox outcome is not a universal job-history record.                                                                                                                                                                                                 | Public inbox operations preserve deliveries independently of live event receipt.                                                                                                | [Inbox](../plowshare-console/src/screens/inbox.ts), [tests](../plowshare-console/src/screens/inbox.test.ts), [typed operations](../sdk/typescript/src/operations/administration.ts).                                                                                                                                                                                                                                            |
| Approvals                           | **Supported:** account-wide pending approvals use validated DTOs, show command argument boundaries and distinguish decision receipt from continuation. Chat approvals use the same checked helper and retain explicit conversation/project decisions. Standing project grants use checked replies, bounded presentation, current connection/read guards and non-replaying revocation receipts.                                               | Desktop/TUI have approval controls; public `approval.list`, `approval.answer` and `approval.revoke` already support this responsibility.                                        | [Account approvals](../plowshare-console/src/screens/approvals.ts), [tests](../plowshare-console/src/screens/approvals.test.ts), [adapter](../plowshare-console/src/approvals.ts), [chat controls](../plowshare-console/src/repl/repl.ts), [standing grants](../plowshare-console/src/screens/projects.ts), [server tests](../plowshare-server/src/test/java/io/aeyer/plowshare/server/ws/ApprovalFramesTest.java).             |
| Projects and workspaces             | **Partial:** authorized projects, workspace moves, lent roots, leashes, exclusions and standing grants. There is no full project creation, membership or role administration workflow. Application projects support file browsing, reads and revision-fenced saves.                                                                                                                  | Desktop workspace controls and TUI project commands use existing project/access operations. File containment and save authority remain server responsibilities.                 | [Projects](../plowshare-console/src/screens/projects.ts), [tests](../plowshare-console/src/screens/projects.test.ts), [application files](../plowshare-console/src/screens/application-files.ts), [tests](../plowshare-console/src/screens/application-files.test.ts), [public catalog](../sdk/typescript/src/operations/catalog.ts).                                                                                           |
| Proposals                           | **Supported:** view and resolve proposals through the existing owner. Large-list presentation still needs a bounded window.                                                                                                                                                                                                                                                          | Existing proposal operations are shared with other clients.                                                                                                                     | [Proposals](../plowshare-console/src/screens/proposals.ts), [tests](../plowshare-console/src/screens/proposals.test.ts).                                                                                                                                                                                                                                                                                                        |
| Memory                              | **Partial:** index, lazy body reads, recall, navigation, digests and explicit invalidation. Index rendering is not windowed and inherits the shell's project context.                                                                                                                                                                                                                | The public memory operations already support these actions; broader project navigation is a client concern.                                                                     | [Memory](../plowshare-console/src/screens/memory.ts), [tests](../plowshare-console/src/screens/memory.test.ts), [digest tests](../plowshare-console/src/screens/digests.test.ts), [server tests](../plowshare-server/src/test/java/io/aeyer/plowshare/server/ws/MemoryFramesTest.java).                                                                                                                                         |
| Information and documents           | **Partial:** scoped catalogue, inventory/acquisitions, detail and operation requests with SDK validation. Documents support multipart upload, ingest status, cancellation and search. Information controls require structured payload entry and default to the documents corpus; there is no complete corpus selection workflow. Document polling continues when its view is hidden. | Public information operations cover acquisition, revisions, readiness, evidence and access changes. Desktop/TUI consume the same SDK contracts.                                 | [Information](../plowshare-console/src/screens/information.ts), [tests](../plowshare-console/src/screens/information.test.ts), [documents](../plowshare-console/src/screens/documents.ts), [tests](../plowshare-console/src/screens/documents.test.ts), [SDK information](../sdk/typescript/src/operations/information.ts).                                                                                                     |
| Usage                               | **Partial:** typed usage reports, scopes and live subscription are implemented, but initial mounting can emit an update before the panel binding exists. An unreadable deployed reply can also prevent measurements from loading. Neither failure should appear as zero usage or a permission grant.                                                                                 | Usage contracts are shared by desktop/TUI; unknown costs remain unknown.                                                                                                        | [Usage](../plowshare-console/src/screens/usage.ts), [tests](../plowshare-console/src/screens/usage.test.ts), [SDK tests](../sdk/typescript/src/operations/usage.test.ts).                                                                                                                                                                                                                                                       |
| Schedules and event firings         | **Partial:** firing history inspection in the overview. **Missing:** schedule files, authoring, sync, pause and forget workflows.                                                                                                                                                                                                                                                    | Desktop has schedule controls and TUI has typed commands. Public schedule operations exist.                                                                                     | [Overview](../plowshare-console/src/screens/overview.ts), [desktop schedules/tests](../plowshare-desktop/src/schedules.test.ts), [schedule file contracts](../sdk/typescript/src/operations/schedule-files.ts), [authorization tests](../plowshare-server/src/test/java/io/aeyer/plowshare/server/ws/FiringListAuthorizationTest.java).                                                                                         |
| Orchestration                       | **Missing:** dedicated run list, durable record, question/answer, resume and cancellation workflows. Starting an orchestration through a chat command does not provide these views.                                                                                                                                                                                                  | Desktop run navigation and TUI commands use public start/list/status/record/answer/resume/cancel contracts.                                                                     | [Public operations](../sdk/typescript/src/operations/administration.ts), [SDK tests](../sdk/typescript/src/operations/orchestration-direct.test.ts), [desktop run navigation](../plowshare-desktop/src/renderer/run-navigation.ts), [TUI session](../plowshare-tui/src/logic/session.ts).                                                                                                                                       |
| Swarms and boards                   | **Missing:** board navigation and swarm lifecycle controls.                                                                                                                                                                                                                                                                                                                          | Desktop board workflows and TUI swarm commands use public board/swarm contracts.                                                                                                | [Swarm contract](../sdk/typescript/src/operations/swarm.ts), [TUI implementation/tests](../plowshare-tui/src/logic/swarm.test.ts), [desktop board](../plowshare-desktop/src/board.ts).                                                                                                                                                                                                                                          |
| Relay                               | **Missing:** dedicated topic/process/log viewer and operation controls.                                                                                                                                                                                                                                                                                                              | Desktop embeds a Relay viewer; SDK and CLI operations provide public Relay access. This does not establish a dedicated TUI viewer.                                              | [Desktop viewer](../plowshare-desktop/src/renderer/relay.ts), [SDK operations](../sdk/typescript/src/operations/relay.ts), [SDK tests](../sdk/typescript/src/operations/relay.test.ts).                                                                                                                                                                                                                                         |
| Runtime/model configuration         | **Partial:** explicit HTTP live-runtime keys; no full model/pool editor. **Missing:** pricing administration UI despite public pricing operations. **Unavailable:** arbitrary boot model/pool editing through the current live configuration/public WS operations.                                                                                                                   | Desktop administration and native commands expose pricing. Deployment configuration owns model/pool definitions; search-provider listing is not model listing.                  | [Config](../plowshare-console/src/screens/config.ts), [tests](../plowshare-console/src/screens/config.test.ts), [runtime controller](../plowshare-server/src/main/java/io/aeyer/plowshare/server/config/RuntimeConfigController.java), [public admin catalog](../sdk/typescript/src/operations/catalog.ts), [model configuration manual](model-usage-accounting.md).                                                            |
| Accounts and service accounts       | **Missing:** account, session, audit and service-token administration. Normal human login/password screens are implemented.                                                                                                                                                                                                                                                          | Desktop administration and TUI typed commands use public administrator operations. Service tokens have their own scopes and role ceilings; a browser control cannot widen them. | [Desktop account administration](../plowshare-desktop/src/renderer/server-admin.ts), [service administration](../plowshare-desktop/src/renderer/service-admin.ts), [public catalog](../sdk/typescript/src/operations/catalog.ts), [server tests](../plowshare-server/src/test/java/io/aeyer/plowshare/server/auth/ServiceAccountsTest.java).                                                                                    |

## Retained work and authorization

The shell multiplexes its screens onto one listener socket for the tab. The
server allows one listener per session identifier and displaces an older socket
using that identifier. Job pushes target the initiating session; they are not an
account-wide durable work feed. Snapshot reconciliation also covers work started
by other clients. See the [shell](../plowshare-console/src/screens/shell.ts) and
[event channel](../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/EventChannelHandler.java).

`job.list` returns jobs in the running server's in-memory store that pass current
project and information/log access checks. `job.status` also requires a current
in-process job. Persisted job metadata prevents identifier reuse and preserves
accounting; it does not make either operation a historical job repository.
After a server restart, inspect the supported owning record: a conversation
trajectory, an inbox delivery, a firing record or an orchestration record.
Absence from the job list cannot establish completion, cancellation or failure.

Admission, execution, a cancellation request and a terminal outcome are separate
states. An accepted submission or approval receipt does not establish that a turn
finished. Unknown delivery must be reconciled by the owner; the browser must not
replay a mutation or synthesize a result from an event.

The server resolves authenticated project membership and resource visibility on
each operation. An approval answer must still be pending and visible to the
caller. An already answered approval fails without continuing work. A successful
approval decision can leave a conversation busy, and its receipt is not a job
outcome. Limits changes use the current job owner/access boundary and reject
finished jobs and limits below expenditure. See [project authorization](../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/ProjectAuthorization.java),
[approval frames](../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/ApprovalFrames.java)
and [job limits](../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/JobLimitsHandler.java).
Client visibility or a cached selection is never sufficient authority.

Firing history additionally filters results by trigger ownership and current
project access. This filtering happens after the bounded page read, so a short
or empty page does not necessarily mean there are no later pages. Continue using
the supplied paging contract rather than inferring an end from visible row count.

## Bounded refresh and known limitations

The work screens reconcile when they become visible and after reconnect. Their
refresh owner coalesces concurrent refreshes and pauses scheduled polling when
hidden. Inbox requests page fifty deliveries, firing requests page thirty
records, and jobs/approvals render bounded windows. These client windows do not
bound server work or response size for unpaged `job.list` and `approval.list`.
Adding server paging would be a separate contract decision, not a reason to
pretend the existing replies are pages.

Jobs, Inbox and the overview also pause reads when the browser tab is hidden.
Navigation, page changes and socket loss invalidate pending snapshots, including
replies from an older visit to the same page. Cached jobs and inbox deliveries
remain available for inspection, but cancellation, budget changes, continuation
and read-receipt controls require a fresh successful read on the active
connection. A refused or unreadable read does not re-enable these controls.
Server authorization still decides every effect; reconnect never resubmits one.

The Workbench keeps overview, inbox, approvals, chat and jobs in primary
navigation; the remaining views are reachable through Browse & settings. The
overview inspector is a selection within the current bounded window. A refreshed
or unreadable record replaces its previous contents; it never establishes a
historical outcome from cached job state.

Command discovery uses the selected agent's server-provided catalogue and the
same draft/completion model as Desktop. Completion only prepares a draft. A skill
without a prescribed context requires an explicit context choice. The browser
does not expose Desktop-only local commands or native file/credential adapters.
The catalogue renders forty matches and slash completion twenty; local search
finds other returned commands. This bounds rendering, not an unpaged server reply.

The trajectory breakdown projects validated retained entries into the shared
TUI/Desktop step model. It pairs calls with results on the current page and keeps
orphan results and compaction history. Missing results are explicitly page-local,
not proof of a running job. The full retained record, projection, timeline and
accounting remain accessible alongside the breakdown.

Chat approvals now use the account approvals screen's checked response helper.
Malformed lists fail a read; malformed, mismatched or lost decision receipts leave
controls closed until a fresh retained read. Neither path replays a decision.
Command display preserves argument boundaries and compound command lists.
Conversation and project grants require explicit controls and current server
authority. Standing grants render thirty per window, preserve argv boundaries,
reject malformed or mismatched replies, and retain blocked uncertain revocations
across refreshes and parent redraws. Inactive or disconnected controls stay closed
until a fresh read; hidden-tab account approval polls pause and late replies are fenced. Usage construction installs the renderer owner
after mounting and replays the initial synchronous watch state. Its first load
uses the subscription already selected during mount, avoiding a duplicate read.

Document job polling pauses on navigation and when the browser tab is hidden.
Returning or reconnecting reads the selected view, coalesces overlapping refreshes
and rejects replies from an earlier visit. Restart guidance distinguishes current
process jobs from the retained corpus; refresh never resubmits ingestion.

Memory, project and proposal
lists need bounded presentation where their current operations return a full
collection. Catalogue and retrieval usability, project context selection and
missing administration/workflow screens are separate client coverage changes.
Broader Desktop/TUI parity, including orchestration run/stage/question screens,
boards and schedules, remains separate work. The public-contract limitations are historical jobs, unpaged list response sizes
and arbitrary boot model/pool editing; they must remain explicit until separately
designed platform capabilities exist.

## Verification boundaries

The linked tests provide source-level evidence for the stated behavior. The
console's fake-transport and DOM tests do not prove deployed cookie behavior,
current membership enforcement or retained state across a server restart.
`DurableJobsTest` requires the explicit full-database test mode; linking its source
does not imply it ran in an ordinary check.

A deployed read-only baseline should identify the server/image revision, inspect
session and WS establishment, exercise navigation and existing retained records,
reload/reconnect and inspect empty/error states without submitting work or
changing approvals, budgets, grants or configuration. Record observations about
usability, authorization and missing workflows separately. Browser fixture
evidence and an unavailable deployment must be reported as such.
