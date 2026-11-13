# Finishing Relay

What Relay is meant to be, what it is today, and how to get it there properly.

**Review remediation, 13 November 2026:** the latest execution state is the
[review task graph](relay-review-task-graph.md). Native receivers, exact retained
requester returns, consumer replacements, legacy transport retirement and integrated
verification are independently accepted under that graph's active scope. The user
explicitly deferred fresh reads from manually started Home Assistant
orchestrations. Older dated slice reports and the baseline inventory below
describe their historical checkpoint, not the current API surface. Follow the
[legacy cutover contract](relay-legacy-cutover.md) for upgrade and history rules.

Written 2026-11-12 against branch `privacy-deep-scan-design`. Every factual claim cites
`path:line`. **(inference)** marks a reading of the evidence. **(owner)** marks intent stated by
the owner directly; it is the original intent, not a new direction, but no single record states it.
Paths beginning `relay/`, `board/`, `events/`, `agents/`, `ws/` are
under `plowshare-server/src/main/java/io/aeyer/plowshare/server/`; `V…sql` files are under
`plowshare-server/src/main/resources/db/migration/`. Decision records are cited by number:
`0040:25` is `docs/decisions/0040-relay-is-the-boards-substrate-rebuilt.md` line 25.

**Slice 0 completed, 13 November 2026.**
[0057](decisions/0057-board-forum-and-relay-messaging-substrate.md) supersedes
0040 and corrects the board/messaging direction;
[0058](decisions/0058-a2a-rebuild-on-relay.md) supersedes 0008 with the A2A rewrite
direction. A2A remains a feature: its implementation must be rewritten because
adapter responsibilities entered Plowshare core. The related records, current
manuals, backlog and cache-spec correction are updated. No later implementation
slice was authorized by that documentation change.

**Slice 1 completed, 13 November 2026, under separate implementation
authorization.** [0059](decisions/0059-typed-messaging-payload-contract.md) records
the typed payload, strict versioned codec and internal publication boundary.
They use Relay TEXT and the existing envelope/API unchanged. The live sender
was still board-backed at that slice; Slice 3 below implements its cutover.

**Slice 2 completed, 13 November 2026, under separate implementation
authorization.** [0060](decisions/0060-relay-board-bridge-and-authorized-close.md)
records the three board receivers, full threaded replies and one terminal,
publisher attribution, durable receipts/outbox and project-authorized
`board.close`. Forum storage and Relay's envelope remain intact.

**Slice 3 completed, 13 November 2026.**
[0061](decisions/0061-relay-agent-inboxes-and-messaging-cutover.md) records
messaging-owned records and allowances, one Relay inbox per account/project/definition,
atomic publication and leased native-wake admission. V239 transfers retained threads,
queued work and paid evidence without replay; V240 reserves shared-conversation turn
ordinals before execution. Existing `message.*` and SDK contracts remain unchanged.
Logical drained-inbox retirement is included; physical cleanup is recorded in Slice 4 below.
Google formatting, `formatCheck check`, generated contract audits, targeted PostgreSQL
cutover/lifecycle/concurrency regressions and authenticated application ingress tests passed.
The default checks did not enable database tests; database verification used selected `-PfullDb`
classes. No live model inference was required.

**Slice 4 completed, 13 November 2026.**
[0062](decisions/0062-relay-topic-and-group-retirement.md) records owner-requested
physical retirement, generation-fenced `relay.group.delete`, exact read-only
`relay.group.inspect` and V241's indexed
retention deadlines. Existing operator removal guards, retained input and paid
execution records remain protected. `formatCheck check sdkCheck`, generated contract
audits, and targeted PostgreSQL migration, locking, retirement and WebSocket tests passed.
The default/SDK gate did not enable database tests. The A2A rewrite remains Slice 5.

**Slice 6 completed, 13 November 2026.**
[0063](decisions/0063-granted-execution-relay-publishing.md) records the ordinary
`relay_publish` tool and its journaled script command form. Exact operator-installed
project/definition/topic grants supplement current definition tool grants and
project authority. V242 retains atomic publication receipts beyond broker retention;
execution identity and causation come from the harness. Existing feature tools,
Relay envelopes and SDK operations retain their contracts. Remaining priority:
Slice 7, then Slice 8, then the deferred A2A rewrite in Slice 5.
`formatCheck check` passed without database tests enabled. All 20 focused tests
passed, including six selected PostgreSQL migration, concurrency, rollback and
retention-recovery cases under `-PfullDb`. Boot wiring and image-reachability
inventory checks passed. No live model inference was required.

**Slice 7 completed, 13 November 2026.**
[0064](decisions/0064-relay-child-completion-and-turn-eligibility.md) records the
single leased completion group, all-terminal capture and V243's durable recovery
rotation. Callback and boot/free drains cannot deliver child terminal results.
The shared eligibility decision includes pending-report holds so a nudge or
plain-text auto-finish cannot overtake the report. Questions, wait legality,
depth/cancellation policy and existing APIs retain their owners. Remaining work:
Slice 8, then the deferred A2A rewrite in Slice 5.
Google formatting and `formatCheck check` passed. Targeted `-PfullDb` checks
passed for V243 upgrade/backfill, terminal capture rollback, scoped distributed
leases, retention recovery, fixed-cycle rotation, wait/wake policy, delivery
fallback and parent nudge/auto-finish fencing. Default checks did not enable
database tests; no live model inference was required.

**Slice 8 completed, 13 November 2026.**
Java now has typed `BoardClient`, `SwarmClient` and `MessagingClient` facades,
including async request options, bounded pages and correlated receipts. Python,
Go and .NET have explicit binary Relay HTTP helpers using generated metadata and
descriptor contracts. They validate inputs before submission, refuse redirects
and automatic replay, bound transfers and verify returned identity and bytes.
[SDKs](sdks.md) and [binary delivery](relay-blobs.md) document the public surfaces.
Google formatting, `formatCheck`, `check`, `sdkCheck` and `sdkPackageCheck` passed,
including native race/type checks, local HTTP/WS fixtures and fresh installed
consumers. The package consumer harness now includes Node's pinned runtime typings
and the complete .NET conformance driver. No database tests or live deployment
were needed. Only the deferred A2A rewrite in Slice 5 remains.

**Slice 5 partially implemented, 13 November 2026.**
[0065](decisions/0065-a2a-relay-adapter-contracts-and-capability-gaps.md) records the
fresh capability review and [SDK-only Relay outbound adapter](a2a-relay-outbound.md).
It has typed dispatch/observation contracts, private durable intent/reply evidence,
producer/peer-bound remote identities, no replay after uncertainty and explicit
read-only reconciliation. The A2A module's tests no longer import server internals.
Focused crash/authorization/codec tests, a real SDK WebSocket with an independent
A2A HTTP peer fixture, both installed launchers, Google formatting, `formatCheck`,
`check` and `integrationBoundaryCheck` passed. No database tests were required.

The inbound/core cutover remains unfinished. Existing `incoming.receive` already
provides authenticated durable admission, and `agent.list` exposes granted served
commands. Arbitrary SDK publications deliberately cannot impersonate trusted
inbox records. The work is to relocate native admission behind registered Relay
receivers and retain requester returns, under the separately authorized general
platform decision [0066](decisions/0066-relay-messaging-receiver-and-review-remediation.md).
Existing incoming/outgoing consumers, external storage and the legacy launcher
remain until replacements and consumer migrations can be tested.
Slice 5 and the overall plan are **not complete**.

Sections 1–3 retain the original review's evidence and drift inventory. Their
`path:line` citations describe the pre-correction documents at source baseline
`7ca27b48ac496b0027923b34b48d3044bddd8b49`; corrected records now have different
line numbers and superseded claims are not current guidance. Follow the linked
replacement decisions for the governing direction.

## 1. Intent

### The original intent

- **Relay is the substrate: plain publish/subscribe.** Topics, publications, subscribers. Its
  envelope does not grow features for the things built on it. **(owner)**
- **Messaging is a façade on Relay.** The `message.*` API — its operations, events, lifecycle
  notices, the `send_message` tool and the SDK calls — is stable and stays. Only the
  implementation beneath it moves to Relay: API versus implementation. The reply reference and
  any correlation live in the **messaging payload**, not in Relay's envelope. **(owner)**
- **An agent's inbox is a Relay topic.** The pipeline is `send_message` → Relay → agent topic →
  Messaging → the instance named by the address. Messages to an agent and replies to its own
  messages land in the agent's topic. Messaging, the layer above Relay, reads that topic and
  dispatches. Instances and conversations are messaging-level routing, not topic boundaries.
  **(owner)**
- **Access is permission, and visibility is project-wide.** There is no "private", only what
  holds permission. Messages on an agent's topic are readable by the project's members under
  Relay's existing scope-level read. The history: project membership was a broad RBAC grant from
  early on, and it used to be stricter in the wrong way — reading needed write permission.
  **(owner)**
- **The board is a forum, not a messaging system.** Its topics, threads, seats, sequencing and
  storage are its own. It exists independently and is not rebuilt onto Relay. **(owner)**
- **A Relay → Board bridge** lets a Relay message from an SDK client, a collector or an agent
  arrive as a post on a board topic, while the board stays the forum. **(owner)**
- **Other features use Relay wherever they can**: events, delegation notices, external work,
  tools. **(owner; recorded in `0037:63-65`)**

### What the record says that agrees

| Role | Recorded intent | Source |
|---|---|---|
| Substrate | "Relay is the only durable delivery substrate"; family APIs are wrappers over it. | `0037:63-65` |
| Mechanism, not policy | Content-agnostic; publish only what the whole audience may see; consumers verify publishers; private topic names; no new payload kinds. | `0040:150-185`, `0040:64` |
| Events | Schedules, information events and `event.fire` publish through Relay. | `0037:20-24` |
| Messaging | `message.*` and outgoing work move onto Relay. | `0037:25`, `0040:30` |
| Board | "Board remains a message board and swarms keep their existing behavior." | `docs/relay.md:12-13` |
| Delegation | A reference-only notice on Relay; Relay never parks a consumer. | `0041:103-116`, `0041:14-19` |
| External work, A2A | One outbound mechanism over Relay, correlated by receipt UUID. | `0008:29-33`, `0008:120` |
| Tools | A named model tool over Relay ports, with a ledger and no replay. | `0002:6-15`, `0002:49-56` |
| One policy | "Relay has exactly one policy — scope-level read on a topic." Kept: it is the decided access model. | `0040:162-163` |
| Reply-to | A messaging field. No decision puts it in Relay's envelope (searched `docs/decisions/`). | `docs/agent-messaging.md:83-90`, `0040:126-128` |

### How the record drifted

**Owner testimony.** `send_message` was meant to be a message to another agent, but it was built
on the forum: a forum post, then a post whose viewer is private, then private messages, then
`send_message` on top. The owner does not recall the forum behaviours being intended for
messaging. The code corroborates the lineage: "A private topic is the transport"
(`V93__board_direct_messages.sql:1-3`); a private board topic per instance (`:10`); each message
a board post (`board/BoardMessaging.java:816-829`); the wake a board firing to `conversation:<id>`
(`:848-852`); the allowance a board pot (`:845-847`).

The records then reinterpreted the intent. "v0" is 0040's name for the Board's substrate, with
Relay as "v1" (`0040:9-25`); no identifier in the source is called v0 (verified by search).

| Where | Drifting phrase | What it should have said | In code? |
|---|---|---|---|
| `0040:1` (title) | "Relay is the Board's substrate, rebuilt" | Relay is the messaging substrate; the board is a separate forum | Paper only |
| `0040:25` | "the Board substrate is Relay v0, and Relay is v1" | The two share a design lineage; neither replaces the other | Paper only |
| `0040:29-30` | "Board and messaging were to be rebuilt on v1" | Messaging moves onto Relay; the board stays | Paper only for the board |
| `0040:38`, `0040:342-343` | board storage "still on v0"; "finishing a replacement" | Board storage is where it belongs; what remains is messaging's move and a bridge | Paper only |
| `0037:218`, `0037:229-231` | "finishing a v0→v1 replacement" | Moving messaging off the board's transport | Paper only |
| `0041:104` | "messaging is itself still on v0" | Messaging is still on the board's transport | Describes real code |
| `docs/platform-gaps-todo.md:375-381` | delegation and `outgoing_work` "still use board tables" | Only messaging uses board tables; the other two have their own | Paper only; factually wrong (`0041:65-67`, `0008:48-52`) |
| `docs/superpowers/specs/2026-10-23-db-admission-and-board-cache-design.md:539-540` | "board and swarm are built on relay" (reported as the owner's words) | Messages to and from a board travel over Relay; the board is not built on it | Paper only; I cannot tell whether this was said or misheard |
| `docs/agent-messaging.md:7-9` | "Direct messages use the board's durable messages" | Direct messages use Relay; today they still ride the board | **Implemented** — this is the divergence itself |
| `docs/manual/13-messaging-and-routing.md:26-27`, `docs/manual/03-architecture.md:177-179` | messaging uses "the same board storage and wake scheduler" | The same, presented as a temporary state, not the design | **Implemented** |
| `board/BoardMessaging.java:31` | "Private messaging is a board transport" | Messaging is a façade over Relay | **Implemented** |
| `0040:258-260` | "Mailboxes are not a Relay construct" | An inbox is a Relay topic | Paper only |
| `0040:303` | "should want a subscriber per principal" | True for broadcast families; an inbox is its own topic. The measurements beside it (`0040:277-299`) remain real constraints | Paper only |
| `V93__board_direct_messages.sql:10`, `board/BoardMessaging.java:848-852` | a private board topic per instance; firings owed to `conversation:<id>` | Board mechanism leaking into messaging: one topic per agent, with Messaging dispatching to the instance | **Implemented** |
| `docs/manual/14-swarm-board.md:223-226`, `:255`; `board/BoardRunExtras.java:278` | "Ordinary members cannot close the parent" — only the opener seat closes | Closing is governed by permission, not by who opened the topic | **Implemented** — forum-era leakage |
| `0037:270-271`, `0040:367-372`, `docs/relay.md:44` | "account-private mailbox content"; notices kept on the system scope | Access to a topic is whatever its permissions grant; "private" is not a category | **Implemented** as the system-scope workaround |
| This document, earlier drafts, Slice 3 | "one inbox topic per conversation"; a Personal-scope privacy option | One topic per agent, readable project-wide | Paper only; **corrected** |
| This document, first two drafts, Slice 1 | a `reply_to` column on Relay's envelope | Reply-to belongs in the messaging payload | Paper only; **corrected in this revision** |

#### Where it started: A2A

The owner says A2A led to this and must be redone. The causal links below are **(inference)**
from migration order; 0040 says causation is "inferred from sequence" (`0040:385-387`).

| Step | Evidence |
|---|---|
| Messaging was already on the board when A2A arrived | `V93__board_direct_messages.sql:1-3` ("A private topic is the transport") |
| A2A receiving was built into board messaging, before Relay existed | `V108__adapter_message_ingress.sql:1-12` adds an `external` instance lifetime and context tables keyed to `board_message_instances`; Relay is `V117`. The ingress lives in `board/BoardMessaging.java:429-610`; "the adapter creates durable Plowshare messages" (`docs/a2a-receiving.md:5-6`) |
| That ingress path then became shared plumbing | Scheduled agent work enters through it too (`board/BoardMessaging.java:436`, `events/AgentRunner.java:77`) |
| A2A sending got a second, separate queue | `outgoing/` has its own repository and no board or Relay reference (verified by search); the adapter "claims work and reports observations" (`docs/a2a-sending.md:12-13`, `:283-284`) |
| 0008 then planned to move external work onto Relay, and noted the two mechanisms | `0008:23-27`, `0008:29-33`; "the current adapter still uses outgoing claims" (`docs/a2a-sending.md:256-258`) |
| 0037 read 0008's migration as replacing the board | "EW04's 'migrate existing work' … is finishing a v0→v1 replacement" (`0037:218`); it also says A2A's core growth is what `0001` forbids (`0037:223-225`) |
| The adapter's tests import server internals | `integrations/a2a/src/test/java/io/aeyer/plowshare/a2a/ApplicationExamplesTest.java:5-7`; main sources do not (verified by search) |

So A2A did not put messaging on the board; it deepened the coupling, added a parallel queue, and
its migration plan is where "replace the board" entered the record. **(Inference.)**

Not drift: the `message.*` API. The drift is `BoardMessaging` as its implementation.

Already in code and consistent with the intent: board and message **wakes** on Relay (`0040:40`,
`relay/RelayInternalWorkers.java:84`) and reference-only **lifecycle notices**
(`V126__relay_board_message_lifecycle.sql:9-35`). `docs/relay.md:12-13` never drifted. No plan in
`docs/` schedules a board transport rebuild beyond the items above (verified by search).

## 2. Today

### Core

| Area | What the code does | Source |
|---|---|---|
| Interface | Eleven methods over topics, publications and subscriptions. | `relay/Relay.java:15-77` |
| Scopes | Two only: `ProjectScope(projectId)` or `SystemScope.SERVER`. | `relay/Relay.java:85-96`, `0039:9-12` |
| Payload kinds | Six: `EMPTY`, `TEXT`, `BLOB`, `SCHEDULE_DUE`, `LIFECYCLE`, `WAKE_REQUESTED`; `Text` is the general opaque message. Pinned by a test. | `relay/RelayPayload.java:9-16`, `:25-32`; `plowshare-server/src/test/java/io/aeyer/plowshare/server/relay/RelayContractTest.java:31` |
| Envelope | `eventId`, `publisher`, `occurredAt`, optional `correlationId`, optional `causationId`, payload, causation `{rootId, parentId, depth}`. No reply-to field, which is as intended. | `relay/Relay.java:193-200` |
| Idempotent publish | A retained event ID with equal content returns the original; conflicting reuse fails; an expired ID is no longer deduplicated. | `relay/Relay.java:29-33`, `relay/JdbcRelayRepository.java:207-210` |
| Delivery | Ordered, at-least-once per topic and group; same group competes, groups are independent. No partitions, lease renewal, exactly-once or automatic retry after uncertain delivery. | `docs/relay.md:573-579`, `docs/relay.md:549-550` |
| Retention | Four days by default; a lagging subscriber sees an explicit gap it must acknowledge. | `relay/Relay.java:145-147`, `relay/Relay.java:70-74` |

### Receivers (what a route can start)

Exactly four: `relay.publish` (forward), `agent.run`, `script.run`, `orchestration.start`
(`relay/RelayConfiguration.java:321`, `:399`, `:431`, `:463`; `relay/WorkRelayReceiver.java:46`).
They are one-way submissions that use the delivery UUID as request identity and reconcile
uncertainty read-only (`relay/RelayReceiver.java:7-27`), under one causation budget
(`docs/relay.md:193-196`). **No receiver posts to or opens a board topic.**

### Authority

Inspection needs `mayUse`, and server administration for the system scope
(`relay/ScopedRelayInspection.java:25-30`). Processing needs `mayWork`
(`relay/ProjectRelayProcessing.java:27`). SDK ports need `mayWork` and an exact deployment
binding, with none by default and no system-scope port (`relay/ProjectRelayPorts.java:102-104`,
`docs/relay.md:513-517`). Topic policy is manager-only (`0010:21-30`). Topic and subscription
removal are operator actions
(`plowshare-protocol/src/main/java/io/aeyer/plowshare/protocol/RelayControl.java:14-20`).
Audiences beyond a project do not exist (`0039:19-20`). Blobs are project-scoped, off by default
and gated by the same port grants (`0048:6-10`, `0048:33-40`).

### Who uses Relay, and the board and messaging as they stand

| Family | State | Source |
|---|---|---|
| Schedules, information events | On Relay | `events/RelayScheduledPublication.java:19`, `information/RelayInformationEvents.java:41` |
| `event.fire` | On Relay, on the system scope (audience: server administrators) | `events/RelayEventPublication.java:47`, `:70` |
| Tool façade | On Relay: `tool.<provider>.<name>.request` / `.result`, correlation = invocation ID | `relay/tools/RelayToolDefinition.java:109-113`, `relay/tools/JdbcRelayToolRepository.java:126-134` |
| Message review | On Relay: request/response topics matched on `correlationId` and publisher | `security/RelayMessageReview.java:102-125` |
| Lifecycle notices | Job, approval, orchestration, board and message changes, reference-only | `docs/relay.md:38-45`, `V126__relay_board_message_lifecycle.sql:9-35` |
| Board and message wakes | On Relay, private system topics | `relay/RelayInternalWorkers.java:84`, `relay/Relay.java:181-188` |
| **Board (forum)** | Its own storage, topic lock and sequence — as intended | `board/BoardStore.java:286-288`, `V74__the_project_board.sql:72` |
| **Messaging** | On the board's transport; see the next subsection | `board/BoardMessaging.java:31` |
| Outgoing / A2A | Not on Relay; `outgoing/` has no Relay reference (verified by search) | `0008:18-19` |
| Delegation report | `orchestration.child.completed` is published and has no Java consumer (verified by search) | `V124__relay_lifecycle_outbox.sql:68`, `0041:133` |

### How `send_message` works today (from code)

"Keep" marks API behaviour the redo must preserve; "leakage" marks board mechanism it removes.

| Step | What happens | Keep or leakage | Source |
|---|---|---|---|
| Tool | Parameters `to`, `to_project`, `route`, `body`, `reply_to`, `reply_expected`, `final`, `lifetime`, `timeout_seconds`; `body` plus one of `to`, `route`, `reply_to`. `from`, `return_address`, `project`, `home` are refused. | **Keep** | `agents/SendMessageTool.java:92-139`, `:161-166`, `:195-196` |
| Address | An instance (`ins_…`): account, project, definition, conversation — unique per (conversation, definition) — plus a private board topic. | **Keep** the address; the board topic is **leakage** | `board/BoardMessaging.java:38-46`, `V93__board_direct_messages.sql:10`, `:15` |
| Sender binding | The harness finds or creates the instance for the sending conversation and definition; the conversation must be account-owned and in the sending project. | **Keep** | `board/BoardMessaging.java:231-266` |
| Resolving `to` | `ins_…` is used directly (same account). A definition name resolves to the default persistent instance for (account, project, definition), created with a new conversation if absent. `lifetime: task` makes a fresh instance. A route may pin a conversation. | **Keep** | `board/BoardMessaging.java:275-285`, `:297-306`, `:309-323`, `V93__board_direct_messages.sql:18-19` |
| Reply | Only the recipient may reply; the target is the original sender's instance from the stored route; a supplied `to` is refused; one final reply per request. | **Keep** | `board/BoardMessaging.java:773-791`, `V93__board_direct_messages.sql:36` |
| Storage | The body is posted to the recipient's private board topic; a route row records sender, recipient, `reply_to` and flags. The sender gets only the receipt. | Board post is **leakage**; route record and receipt **keep** | `board/BoardMessaging.java:816-842`, `:887` |
| Wake | A firing is owed on the board topic to target `conversation:<recipient conversation>`; Relay's wake topic has one group per such target. | **Leakage** | `board/BoardMessaging.java:848-852`; `relay/JdbcRelayNativeRepository.java:60` |
| Handling | The turn runs in the recipient instance's conversation; the message is rendered as data with `from` and `return_address`. Busy is judged per conversation, per recipient and per topic pot. | Rendering **keep**. A busy recipient waiting for its own work: **keep**. Waiting on another definition that shares the conversation, and the pot check: **leakage**, removed (owner) | `board/BoardMessaging.java:1119-1122`, `:902-921`, `:1002-1010` |
| Limits | Call ID is the send identity; refusals for body size, a full recipient queue and 256 active instances per account and project; each message adds model-call allowance to the board topic's pot. | Refusals and the per-instance, per-message allowance: **keep** (owner). The board pot holding it is **leakage** | `board/BoardMessaging.java:766-772`, `:804-813`, `:390-392`, `:845-847`; `board/MessagingProperties.java:9` |

Board facts the bridge must respect: a closed topic refuses posts (`board/Board.java:274-276`);
`board.open` and `board.post` are idempotent per account and request UUID
(`board/BoardPostRepository.java:30-32`); only an agent opener seat can close
(`board/BoardRunExtras.java:278`; no `board.close` operation, `ws/FrameTypes.java:506-516`;
`docs/manual/14-swarm-board.md:186`).

### SDK coverage

Relay ports are in every SDK: TypeScript (`sdk/typescript/src/operations/relay-ports.ts:6-15`),
Python (`sdk/python/src/plowshare/contracts.py:8589`), Go (`sdk/go/contracts_generated.go:14204`),
.NET (`sdk/dotnet/Plowshare.Sdk/Contracts.g.cs:1360`) and Java
(`sdk/java/src/main/java/io/aeyer/plowshare/sdk/RelayClient.java:65-179`). Board and swarm
operations are in TypeScript, Python, Go and .NET (`sdk/python/src/plowshare/contracts.py:6887`,
`sdk/go/contracts_generated.go:11757`, `sdk/dotnet/Plowshare.Sdk/Contracts.g.cs:8227`); none was found in `sdk/java/src/main`. Blob HTTP helpers
exist for Java and Node only (`0048:41-45`). The `message.*` operations manage instances,
deliveries and cancellation; none sends a message (`sdk/typescript/src/operations/messaging.ts:54`;
verified by listing operation names).

## 3. The gaps

| Capability | Intended | Actual | Gap |
|---|---|---|---|
| Board as an independent forum | Its own storage (owner; `docs/relay.md:12-13`) | It is | **None**; the drift is in the record |
| Messaging implementation | `message.*` is a façade over Relay (owner; `0037:25`) | The API is right; it runs on the board's transport (`board/BoardMessaging.java:31`) | **Diverged** underneath — the main gap; no API gap |
| Reply-to and correlation | Fields of the messaging payload (owner) | Right in the API (`agents/SendMessageTool.java:105-107`); stored in `board_message_routes`; no messaging payload on Relay. Tools and review built their own request/response (`relay/tools/JdbcRelayToolRepository.java:126-134`, `security/RelayMessageReview.java:102-125`) | **Diverged** underneath |
| Agent inbox topic | One Relay topic per agent; Messaging dispatches to the instance (owner) | A private *board* topic per instance (`V93__board_direct_messages.sql:10`); Relay carries only a wake reference (`relay/RelayPayload.java:86-93`) | **Diverged** |
| Relay → Board bridge | A Relay message becomes a post or opens a topic (owner) | No such receiver (`relay/WorkRelayReceiver.java:46`) | **Missing** |
| Board → requester | Replies land in the requester's inbox (owner) | Uncorrelated reference notices only (`V126__relay_board_message_lifecycle.sql:9-35`, `docs/relay.md:43`) | **Partial** |
| Closing a board topic | Project write permission or manager (owner) | No `board.close` operation; only an agent opener seat closes | **Diverged** — forum-era rule |
| Generic pub/sub and events | One substrate for every family (`0037:63-65`) | Schedules, information events, `event.fire`, tools, review and wakes use it; `event.fire` and `message.*` notices sit on the system scope, which 0039 rejects (`0039:54-59`) | **Partial** |
| Agents and orchestrations publish or subscribe | Wrappers over Relay (`0037:12-13`) | Relay starts agents and orchestrations; nothing lets one publish (only the tool façade in `agents/`; verified by search) | **Missing** (publish); **partial** (subscribe) |
| Delegation | A consumer and one eligibility owner (`0041:141-152`) | Notice published, unconsumed | **Partial** |
| A2A and external work | An adapter over the substrate: inbound to an inbox topic, outbound over Relay (owner; `0008:29-33`) | Inbound is wired into board messaging (`board/BoardMessaging.java:429-610`); outbound has its own queue (`outgoing/`); 0008 unshipped | **Diverged** — to be redone |
| Retiring topics and groups | Needed by inboxes **(inference)** | No group deletion on ports (`docs/relay.md:571`); nothing collects a drained topic (`0040:296-299`) | **Missing** |
| SDK parity | All SDKs together (`0003:73`) | Ports at parity; Java lacks board clients; Python, Go, .NET lack blob helpers | **Partial** |

### What project-wide visibility exposes, concretely

The owner's test: a real exposure is someone in the project seeing what they would not otherwise
be entitled to; if they have project access, they are entitled.

**Who can read at all (owner, verified).** Agents cannot. An agent can write to another agent
with `send_message` (same account, routing permitting; `board/BoardMessaging.java:275-287`) and
has no way to read another instance's messages: conversation tools refuse other participants'
logs (`docs/agent-messaging.md:102-106`). No agent tool reads Relay. The only Relay classes in
`agents/` are the tool façade and `relay_tool_read`, which reads the agent's own tool invocation
by ID (`agents/RelayInvocationReadTool.java:15`; verified by search). The façade's port path,
`allowTools && tools.permits(...)`, serves the external **provider account**: consume on its
request topic, publish on its result topic (`relay/ProjectRelayPorts.java:102-104`,
`relay/tools/RelayToolProperties.java:68-84`). Direct read is `relay.log` for accounts with a
project role and `relay.consume` for accounts with a port grant
(`relay/ScopedRelayInspection.java:29-30`, `relay/ProjectRelayPorts.java:102-104`). One indirect
path exists: a project's own Relay route package can subscribe to a project topic and hand the
event to `agent.run` (`docs/relay.md:185-195`); that is project configuration, not an agent
capability.

So project-wide visibility exposes message bodies only to people and external clients holding a
project role, never to agents on their own.

| Scenario | Evidence | Call |
|---|---|---|
| Every role reads `relay.log`, including Viewer and member service accounts | `relay/ScopedRelayInspection.java:29-30`, `0038:27` | **Non-issue**: they are project members |
| Another member's messages. Today messaging and conversation logs are account-owned inside a shared project: `message.*` reads require the owning account, and "Conversation histories retain their account ownership" | `board/BoardMessaging.java:1315-1320`, `docs/projects.md:674-679`, `docs/manual/05-projects-and-workspaces.md:10-13` | **Real under today's code**, for people only: member B, or B's SDK client, would read bodies exchanged by member A's agents, though not A's conversations; B's agents would not. A non-issue if account ownership inside a project is itself not intended |
| Bodies that quote document inputs later revoked or quarantined. Messaging rechecks those inputs; a publication is immutable until retention ends | `docs/agent-messaging.md:117-124`, `0040:241-244` | **Real but narrow**: readable by role-holders, not agents, for up to the retention window |
| Cross-project bodies | Sent only under two-sided `sendTo`/`acceptFrom`; "Cross-project delivery transfers the message body" (`docs/agent-messaging.md:121-122`) | **Non-issue**: deliberately sent into the project |
| A2A inbound from external parties | External callers are not project members and cannot read `relay.log`; their content enters the project by design (`docs/a2a-receiving.md:184-188`) | **Non-issue** |
| Personal projects | Logs are owner-only and messages never cross accounts (`relay/ScopedRelayInspection.java:29-30`, `docs/agent-messaging.md:108`) | **Non-issue** |
| Why the records worried | "a message body is account-private mailbox content" (`0037:270-272`); the same in `0040:367-372` and `docs/relay.md:50-51` | Rests entirely on the second row, not on a separate threat |

Only the second and third rows would justify the future permission-system work.

## 4. How to get there properly

**Execution priority, updated by the owner on 13 November 2026:**
Slice 6 (agent and orchestration publishing), then Slice 7 (delegation's consumer),
then Slice 8 (SDK parity), then Slice 5 (the A2A rewrite). Slice numbers remain
stable identifiers, not execution order. Slices 6–8 take priority over A2A;
the rewrite remains planned and is deferred until those slices are complete.

Slice 0 corrects documentation only. Later slices that change the platform need
the owner's explicit authorisation under
`docs/decisions/0001-integration-boundary.md`; the documentation correction does
not authorize those implementation changes.

### Slice 0 — Make the record true (**completed 13 November 2026**)

The original intent is now recorded before implementation work. The following
documentation corrections are complete; `BoardMessaging` still describes the
actual board-backed implementation.

| Record | Correction recorded |
|---|---|
| `0040` | Superseded by [0057](decisions/0057-board-forum-and-relay-messaging-substrate.md). Retains mechanism rules, historical cardinality measurements and messaging findings; removes the board-retirement framing and corrects the current six payload kinds and 0039 filename. |
| `0037` | Reconciles messaging as extraction from board transport, not board replacement. Withdraws the audience blocker: visibility is project-wide. Separates messaging, outgoing work and delegation. |
| `0040` (also) | An agent inbox is a topic; broadcast families keep shared topics and independent subscribers. |
| `0008` | Superseded by [0058](decisions/0058-a2a-rebuild-on-relay.md), the replacement direction for Slice 5. A2A remains; the old plan becomes input to rewriting its implementation outside core. Detailed Slice 5 contracts are still future work. |
| `0041` | Says "on the board's transport" and withdraws the repeated mailbox-privacy blocker, retaining the eligibility and single-group requirements. |
| `0038`, `0039` | 0038 is marked implemented with the correct 0039 filename. 0039 separates general-carrier extensibility from its deferred non-project audience vocabulary. |
| `docs/platform-gaps-todo.md` | Corrects D1 and A2A rewrite scope; adds the bridge and its replies. |
| `docs/agent-messaging.md`, manuals 03 and 13, `docs/relay.md` | Keep current API behavior and board-backed transport explicit, with the decided Relay target marked unimplemented. |
| `board/BoardMessaging.java` | Left unchanged by this slice; its comment describes the implementation until Slice 3 changes it. |
| The 2026-10-23 cache spec | Adds a correction beside the mistaken board/swarm-on-Relay attribution; writer/invalidation auditing still governs cache-domain choice. |

### Slice 1 — The messaging payload contract (**completed 13 November 2026**)

Internal to the implementation. Relay's envelope, operations and schema do not change, and
neither does the `message.*` API.

Implemented in `plowshare-server/src/main/java/io/aeyer/plowshare/server/messaging/`:
`MessagePayload`, `MessagePayloadCodec`, `MessagePublications` and
`RelayMessagePublications`. See [0059](decisions/0059-typed-messaging-payload-contract.md)
for the exact wire fields, body/reference variants, trusted binding preconditions,
publisher checks, byte bounds and uncertainty rules. The capability is staged
for the later cutover and is not wired into the board-backed sender.

- **Contract.** A typed `MessagePayload`: message ID, sender address, recipient address, body or a
  reference to a body held in messaging's record, `reply_to` (message ID), conversation or
  correlation ID, `reply_expected`, `final`. Published as `RelayPayload.Text` through a versioned
  codec owned by messaging, as tools and information events do (`relay/RelayPayload.java:25-32`,
  `0040:64`). No new payload kind.
- **Validation.** Decoded and fully validated before business logic; unknown fields, oversize
  bodies and malformed addresses refused. The consumer checks the publisher before decoding and
  skips what it did not issue (`0040:176-179`, `0040:245-248`).
- **Idempotency.** Relay event ID = message ID, so the existing rule applies
  (`relay/Relay.java:29-33`), behind the unchanged call-ID send identity
  (`board/BoardMessaging.java:766-772`).
- **Authority.** The sender is bound by the harness, never self-declared
  (`board/BoardMessaging.java:231-266`, `agents/SendMessageTool.java:161-166`). Who may publish to
  an inbox is today's rule: same account, plus routing policy across projects
  (`board/BoardMessaging.java:275-287`). A reply comes only from the original recipient (`:781-782`).
- **Failure.** A refused payload publishes nothing. After uncertain delivery, reconcile by message
  ID; never resend (`relay/Relay.java:35-39`).
- **Tests.** Mocked: field validation, forged sender, foreign publisher skipped, repeat and
  conflicting repeat.

### Slice 2 — The Relay → Board bridge (**completed**)

Implemented through `BoardBridge`, `BoardClosing`, `BoardRelayReceiver` and the
V238 reply outbox. [0060](decisions/0060-relay-board-bridge-and-authorized-close.md)
defines the versioned request/reply payloads, explicit reply-topic requirement,
source-project opening constraint and exact transitive-thread disclosure rule.

- **Changes.** Receivers `board.post` and `board.open` that call the board's own API, and a
  permission-checked `board.close` operation with a matching receiver. Board storage, lock and
  sequence are untouched.
- **Contract.** Route branches `{receiver: 'board.post', work: {topic}}` and
  `{receiver: 'board.open', work: {swarm}}`. The publication carries a bridge-owned payload: body
  and optional `reply_to`, naming the requester's agent topic or another topic. Nothing joins Relay's
  envelope.
- **Authority (owner).** A post made through the bridge appears as the **publishing account**.
  Permission to publish to the Relay topic is enough to post, and so to trigger the swarm; it
  grants no read or participation rights in the swarm. The route itself is project configuration
  and grants nothing beyond that (`docs/relay.md:218-219`). Outside clients always come in through
  Relay ports, which are established in code and every SDK (`relay/ProjectRelayPorts.java:102-104`,
  `0003:24-32`).
- **Idempotency.** The delivery UUID is the board request UUID, already deduplicated per account
  (`board/BoardPostRepository.java:30-32`); uncertainty is reconciled, never reposted.
- **Ordering.** The board's own sequence governs (`board/BoardStore.java:286-288`); bridge posts
  are ordered by arrival. **(Inference.)**
- **Failure.** A closed or missing topic, exhaustion or an unavailable swarm is a definite refusal
  on the branch (`board/Board.java:274-276`). No fallback to a new topic.
- **Replies (owner).** Replies carry the **post message itself**, since the requester may have no
  access to the swarm. The board publishes to the requester's agent topic, or the topic it named:
  each post answering the bridged one and one terminal message, with the board message ID and
  `reply_to` in the payload. The record must say which posts are disclosed this way; existing
  notices are unchanged (`V126__relay_board_message_lifecycle.sql:9-35`).
- **Closing (decided by the owner).** "Write permission or manager": an RBAC rule with no
  opener-seat rule, which is forum-era leakage. In code that is `CONTRIBUTOR` or above —
  `ProjectRole` is `VIEWER`, `CONTRIBUTOR`, `MANAGER`, each allowing the ones below
  (`archive/ProjectRole.java:4-10`), and `members.mayWork` is the existing write check
  (`archive/ProjectMembers.java:53`). Other board writes already require it
  (`board/Board.java:404-407`, `:1001-1002`). There is no per-topic write permission today, so
  "write on the topic" means project write. The `board.close` operation checks the calling
  account; the `board.close` Relay message checks the publishing account at dispatch.
- **Tests.** Mocked: receiver contract, duplicate delivery, closed-topic refusal, a publisher
  without swarm access posts but cannot read, a Viewer cannot close and a Contributor can, terminal
  reply once.

### Slice 3 — Messaging on Relay, with one inbox topic per agent (**implemented; supersedes 0040's mailbox guidance**)

**No API change.** The `message.*` operations, events and lifecycle notices, the `send_message`
tool and its parameters, and every SDK call keep their names, payloads and behaviour. No client or
SDK changes. The swap happens behind the existing seam (`agents/SendMessageTool.java:60`).

- **What "agent" means here.** (account, project, definition name). That is the unit today's
  resolution already uses once the board topic is taken away: a definition-name send resolves to
  one default instance per (account, project, agent) (`V93__board_direct_messages.sql:18-19`,
  `board/BoardMessaging.java:299-306`), and addresses cannot cross accounts
  (`docs/agent-messaging.md:108`). **(Inference; chosen because it is the coarsest unit that never
  mixes two accounts' mail.)**
- **Pipeline.** `send_message` resolves the recipient instance exactly as now, writes messaging's
  record, and publishes a `MessagePayload` to the recipient agent's topic in one transaction.
  Messaging consumes that topic and dispatches to the instance named in the payload. Instances
  (persistent, task, caller, external) and their conversations are rows in messaging, not topics.
- **Naming.** Derived privately by messaging from the agent identity (`0040:183-194`); well
  within the 1,024-character grammar (`docs/relay.md:3-6`, `0021:7-9`).
- **Capacity.** Topics = accounts × projects × definitions that have messaged, far fewer than
  today's one board topic per instance, and instances no longer create topics. This sits well
  inside the measured range for default retention (`0040:290-294`).
- **Creation and retirement.** The topic is registered on the agent's first send or receipt and
  lives as long as the agent does. It is retired when the project goes, or when the definition is
  removed and the topic is drained. Task instances come and go without touching it, so Slice 4 is
  no longer a prerequisite.
- **Dispatch and waking.** Arrival is the publication (`0041:80-84`). Messaging's consumer admits
  each message into its own record and owes the turn in the transaction that advances it
  (`0037:75-78`), so a busy instance never blocks others on the same topic. One leased consumer
  group per agent topic. **Decided by the owner:** a busy recipient still waits for its own work
  (`docs/agent-messaging.md:12-13`); the wait across two definitions sharing a conversation is
  dropped. `message.wake.requested` and the `message.*` notices stay; only how they
  are produced may change. Eligibility stays outside Relay (`0041:14-19`); the one-running and
  one-handling-request indexes stay (`0040:361-366`).
- **Allowance (decided by the owner).** It stays a per-instance, per-message budget, held in
  messaging's record instead of a board pot. It is not a hard wall: it is topped up through the
  existing authority path like any other budget. Automated: the project caps `autoIncrease`
  policy, "project authorization to renew exhausted step and model-call allowances"
  (`agents/ProjectCaps.java:23`, `docs/projects.md:640-654`). Human: the approval continuation,
  which already grants a fresh allowance (`board/BoardMessaging.java:1014-1060`). Today both
  writes go to the board pot (`:845-847`, `:1060`), `board.topup` refuses messaging topics
  (`board/Board.java:999-1000`), and message leases are excluded from `autoIncrease`
  (`docs/projects.md:648-650`) — more leakage. Spending pools are proposed only (`0025:3-5`).
- **Access (decided by the owner).** Project-wide: an agent topic sits in its project's scope
  and is readable by the project's members under Relay's one policy (`0040:162-163`,
  `relay/ScopedRelayInspection.java:29-30`). No per-topic permission is added. Cross-project
  sending stays governed by configuration: the source's `sendTo`, the destination's `acceptFrom`
  and the account's access to both (`board/RoutingConfiguration.java:17`,
  `board/ProjectMessageRouting.java:70-73`, `docs/projects.md:448-453`), rechecked for instance
  addresses (`board/BoardMessaging.java:284`). Finer, per-resource access inside a project, if
  ever wanted, is work on the permission system, not a Relay feature, and is out of scope here.
- **Replies.** Published to the **original sender's agent topic**, addressed to the sender's
  instance from the retained route as today (`board/BoardMessaging.java:783`), never from the
  replier's input, so a reply survives routing revocation (`docs/agent-messaging.md:113-115`).
- **Ordering.** Per agent topic, gapless and commit-ordered under the topic row lock
  (`0037:80-81`), so each instance sees its messages in that order; stronger than today's global
  sequence and advisory lock (`0040:344-354`). None across agents.
- **Failure.** Unchanged for callers: asynchronous, no sender-side wait (`0041:156`), interrupted
  handling not replayed (`docs/agent-messaging.md:96-98`). An inbox gap is surfaced, never skipped.
- **Migration from `BoardMessaging`.** Routes and bodies stay as the record. One migration
  registers a topic per agent that has an instance. **Clean break (owner):** existing messages
  and threads move over in one cutover, outstanding ones included; no dual running
  (`0037:146-154`). Private board topics for messaging stop being created.
- **Tests.** First pin the existing `message.*` behaviour — operations, events, notices, tool
  results, SDK fixtures — against `BoardMessaging`; then run the same suite unchanged against the
  Relay implementation and require identical results. Add: right-instance dispatch; a busy instance
  does not delay another; allowance top-up by policy and by approval; a non-member cannot read an
  agent topic. `full-db`: constraints, cutover, concurrent final replies.

### Slice 4 — Topic and group retirement (**implemented**)

Owning systems can request physical retirement after normal publication retention
and their subscriptions/work drain. Operator `REMOVE_TOPIC` keeps its existing
empty-topic guard. [0062](decisions/0062-relay-topic-and-group-retirement.md) records
generation-fenced SDK group deletion, bounded owner cleanup and V241's indexed
retention discovery. Messaging history and paid evidence survive; this no longer
gates Slice 3.

### Slice 5 — A2A rethought and rebuilt on Relay (**outbound implemented; inbound/core cutover blocked; supersedes 0008**)

**Owner:** A2A should not have modified core; that it did led to the whole API approach, and the
current implementation "should be entirely rethought". This is a rebuild from fresh requirements,
not a migration of today's design. Governing rule: an integration that needs core changes is a
failed integration task (`docs/decisions/0001-integration-boundary.md`). Depends on Slices 1–3.
A2A remains a feature; the rewrite removes adapter responsibilities from core.
The replacement direction is recorded in [0058](decisions/0058-a2a-rebuild-on-relay.md);
[0065](decisions/0065-a2a-relay-adapter-contracts-and-capability-gaps.md) now records
the detailed outbound contract, implementation and the public capability review.
The SDK-only Relay outbound worker is implemented under `integrations/a2a`, with
its own typed dispatch/observation protocol, durable intent journal and explicit
reconciliation. See [its manual](a2a-relay-outbound.md).

The full rewrite still requires the structural admission relocation authorized
by [0066](decisions/0066-relay-messaging-receiver-and-review-remediation.md).
`incoming.receive` already admits authenticated work, `agent.list` provides the
served-command projection, and message schedules share native admission currently
typed with `Incoming` DTOs. Registered Relay receivers must reuse that admission
without weakening private inbox checks, and preserve durable requester returns.
Inbound `incoming.*`, outgoing consumers, external-lifetime storage and affected
adapters remain until replacements can be tested and cut over. This slice is
**not complete**.

- **Shape (owner's direction).** Adapters are SDK consumers of Relay topics through ports. There
  may be several adapters, each with its own topic set, behind one API. Inbound work lands on the
  receiving agent's topic as a `MessagePayload`; replies return on the adapter's topic.
- **Candidates for removal from core.** The A2A-driven surface: `incoming.*` and its ingress in
  `board/BoardMessaging.java:429-610`; the `external` lifetime and context/task tables
  (`V108__adapter_message_ingress.sql:1-12`); `outgoing.*`, the `outgoing/` package and its tables
  (`V107__outgoing_agent_cards.sql:1-2`). Scheduled work must be rehomed first
  (`events/AgentRunner.java:77`).
- **Requirements to start from** (inputs from current docs, not constraints): one conversation
  per A2A context across tasks (`docs/a2a-receiving.md:44-66`); idempotent receipt by `messageId`
  (`:268-271`); task read, cancel and state mapping (`:308-315`); isolation per authenticated
  client alias (`:184-188`); approvals stay an operator action (`:67-69`); Agent Card from current
  grants (`:131-134`); explicit command extension (`:326-358`); dispatch intent before external
  I/O and no replay after uncertainty (`0008:160-170`, `docs/a2a-sending.md:397-404`); remote
  identities bound per peer (`0008:118`); stable text artifacts (`0008:189-196`).
- **Home Assistant and the shared runtime (owner: rebuild "if the HA adapter is crap").**
  Assessed against what A2A failed. Main sources import no server internals and use only the
  public SDK (`integrations/runtime/build.gradle.kts:4`,
  `integrations/home-assistant/build.gradle.kts:4`). The runtime does depend on the surface to be
  removed: `OutgoingClient` advertise, claim and report
  (`integrations/runtime/src/main/java/io/aeyer/plowshare/integrations/Gateway.java:30-46`).
  Tests are substantial: 62 in the runtime, 13 in the adapter. Two adapter tests import server
  internals, one booting the server (`integrations/home-assistant/src/test/java/io/aeyer/plowshare/integrations/ha/HarnessPipelineTest.java:9-14`,
  `ProjectExamplesTest.java:6-7`), the same fault as A2A's test. **Verdict: keep and adapt** —
  re-point `Gateway` at the new outbound path and move the test imports behind the SDK.
- **Also affected.** Discord uses `incoming.*` (`integrations/discord/src/gateway.ts:51-80`) and
  Network Privacy Watch's peer module uses the outgoing path
  (`integrations/network-privacy/src/plowshare_privacy/peer.py:1`, `:65-67`). Both must be adapted in step.
- **Tests.** Adapter tests through the public SDK only; crash windows and no repeated external
  mutation (`0008:268-285`); an independent A2A client.

### Slice 6 — Agents and orchestrations publish (**implemented**)

Agents keep feature tools such as `send_message` and may explicitly grant
`relay_publish`; scripts use that same tool through the existing command journal.
[0063](decisions/0063-granted-execution-relay-publishing.md) defines exact topic
grants, current definition/project checks, inherited effect budgets, deterministic
call identities and V242's retention-independent receipts. Neither models nor
scripts choose an envelope, account, project or fresh causation root. See
[Relay publishing](relay.md#agent-and-orchestration-publishing) for configuration.

### Slice 7 — Delegation's consumer (**implemented under 0041**)

Implemented through the built-in `builtin.orchestrations.children` group and the
shared `TurnEligibility` decision. [0064](decisions/0064-relay-child-completion-and-turn-eligibility.md)
defines trusted notice selection, retained terminal-attempt recovery, distributed
ownership, callback-path removal and the parent scheduling hold. V243 captures
finished, failed, capped and cancelled children without replaying delivered
history. Detailed configuration and failure behavior are in [Relay](relay.md#child-orchestration-completion).

### Slice 8 — SDK parity (**implemented**)

Java's typed board, swarm and messaging clients cover the existing operations,
with synchronous/asynchronous calls and shared request options. Immutable records
validate nested forum ownership, retained swarm selection, instance/reply lifecycle
and page/receipt correlation; server authorization remains authoritative. Sending
continues through the granted agent tool API.

Python, Go and .NET binary helpers use generated HTTP DTO roots from the owning
TypeScript graph. They submit once, retain delivery uncertainty on cancellation,
refuse redirects and validate receipts and byte integrity. The existing HTTP
endpoints and WS operations retain their contracts. See [SDKs](sdks.md) and
[binary delivery](relay-blobs.md) for configuration, timeout and ownership details.
Each earlier slice shipped its own SDK changes.

Decision records: Slice 0 is complete through 0057 and the amendments above;
0058 records the replacement A2A direction and supersedes 0008. Detailed records
for Slices 1–3 are 0059–0061; Slice 6 is recorded in 0063; Slice 4 is recorded in 0062. Slice 5 must complete its contracts before
implementation. Slice 7 proceeds under 0041. The Slice 3 record settles allowance renewal through the existing caps policy.

## 5. Open questions

None of the earlier questions remains; each answer is recorded in the slice it affects. One arose
from the last check:

1. Discord and Network Privacy Watch also use the `incoming.*` and `outgoing.*` surface. Are they
   adapted inside Slice 5, or does that surface stay until each is moved?

## 6. What this unlocks

Network Privacy Watch's collector already uses Relay ports
(`integrations/network-privacy/src/plowshare_privacy/worker.py:109`, `:235`). It gets a topic of
its own as its inbox and publishes one task whose payload carries `reply_to` = that inbox. The
bridge opens or posts to an analyst swarm's board topic; the swarm deliberates on the board as
now; each answer and the closing resolution land in the collector's inbox as ordinary Relay
messages. With project write permission the collector can also close the topic, which no SDK caller can
do today.
