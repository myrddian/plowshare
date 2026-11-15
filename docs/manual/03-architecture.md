# Architecture and concepts

## The server is the durable runtime

Plowshare separates the interface a person uses from the runtime that owns agent
work. Java 21 and Spring Boot host the server. PostgreSQL stores the durable
domain through Spring JDBC; pgvector holds comparable embedding projections.
The data directory retains state that belongs on disk, including Git unions,
binary content and the inference accounting journal.

```text
People and external systems
  desktop / TUI / CLI / web console / MCP / SDK adapters
                    |
  HTTP authentication -> authenticated WebSocket operations
                    |
            server access and frame routing
                    |
    agents and harness / jobs / orchestrations / messaging
             |                 |                  |
      model dispatcher    information/memory   workspace providers
             |                 |                  |
      configured pools   PostgreSQL/pgvector   server disk or file channel
                         + retained disk state
```

The diagram describes ownership rather than an installation requirement that all
components share a machine. Model endpoints, clients, adapters and server storage
can live in separate processes or hosts. Search adapters and A2A integrations are
separate services with their own failure and credential boundaries.

## Domain vocabulary

| Concept | Meaning |
| --- | --- |
| Account | Authenticated principal whose access and work ownership are checked |
| Project / home | Scope for definitions, history, knowledge and optional files |
| Personal | One account-owned private project with reusable resources |
| Agent definition | Prompt, model binding, tools, delegation, scopes and limits |
| Bot | Human-facing definition that receives conversation messages |
| Conversation | Durable exchange and the context lineage future turns use |
| Run | One execution, with model steps, tools, outcomes and lineage |
| Conversation log | Retained, ordered entries recording work in a conversation across turns |
| Trajectory | Human inspection view of recorded conversation entries |
| Model projection | Messages assembled from eligible history and current request context |
| Job | Durable admitted work that clients can inspect, follow or cancel |
| Skill | Scoped instruction package invoked under explicit grants and context mode |
| Orchestration | A staged workflow with its own state, checks, gates and recovery |
| Agent instance | Bound definition, project, account and conversation used for messaging |
| Swarm topic / seat | Shared deliberation and a participant's private execution history |
| Information resource | Named source or report with immutable content revisions |
| Evidence | A quotation tied to a revision and exact retained-text coordinates |
| Memory | A retained lesson or knowledge item with provenance and lifecycle |

Definitions are configuration, not executable permissions by themselves. A file's
presence does not authorize a caller to invoke it. A project name supplied by a
model does not establish membership.

## A request's path through the system

1. The client authenticates over HTTP and opens a ticketed WebSocket connection.
2. A correlated operation frame reaches the server router and its owning handler.
3. The handler resolves the authenticated caller, project/session and operation
   contract. Access checks precede discovery or execution.
4. Immediate reads return an outcome. Long-running work is admitted into its
   durable owner and returns a handle.
5. The harness resolves definitions, grants and model bindings, then runs bounded
   steps. Hooks, workspace policy and approval gates apply at the relevant stage.
6. Model attempts and tool effects record their own results, accounting and state.
7. Clients read or follow progress and the final durable outcome. A lost reply
   does not automatically cause the client to replay the original operation.

An SDK changes how a caller reaches these capabilities; it does not bypass their
authority or install a second server scheduler. A2A maps external contexts and
tasks to server-owned work using its adapter and SDK contracts.

## Execution, context and effects

The effective definition supplies the agent's prompt, model binding, tools,
delegates, scopes and limits. Resolution uses the relevant server, project,
Personal and connected-session tiers. It is distinct from admission: finding a
definition does not establish that the current caller may invoke every operation
it describes.

A run builds model context from the conversation's permitted history, current
instructions and tool results. The model requests a supported tool; the runtime
parses its arguments and executes it under the run's account, project, grants,
workspace fence and relevant approval policy. An external document, file or
message can enter context as data without becoming an authority source.

The model dispatcher resolves the logical model binding to a serving pool and
records the inference attempt. Budgets, turn caps, placement and provider timeouts
constrain different parts of this path. A configured fallback is another explicit
model attempt under the owning contract, rather than a claim that the first
provider succeeded. Missing usage remains unknown in accounting.

Harness profiles attach configured guidance to a run, such as detecting repeated
failures or a run that keeps reading without progressing. Authored hooks operate
at particular lifecycle seams. The profile and hook chain can guide, refuse or
ask according to their event contract; a new prompt instruction does not bypass
the tool's actual authorization. The [hook guide](12-hooks.md) lists the callback
placements because in-turn, standalone and information-processing chains differ.

The [conversation layers](#log-trajectory-and-model-projection) below
explain how retained history becomes bounded model context. Retaining an entry
and presenting it to a model are separate decisions.

Tool effects and final answers are separate observations. A filesystem operation
can commit before the run later reaches a cap. A remote action can have uncertain
delivery even when the local tool times out. Cancellation is cooperative and
does not undo effects already committed. Read the actual tool outcome and durable
work state when deciding what to retry or accept.

## Scope and dependency boundaries

| Boundary | What is selected or enforced |
| --- | --- |
| Account | Identity, owned work, private conversations and Personal |
| Project | Membership, definitions, working context and registered workspace policy |
| Client session | Explicit local file presence and eligible session definitions |
| Definition | Offered tools, permitted delegates/procedures and bounded execution |
| Information audience | Live access to retained revisions, evidence and dependent outputs |
| Workflow/message/topic | Its particular state, return path, budget owner and recovery contract |

These boundaries intersect rather than replacing one another. A project member
does not automatically own every other member's document or messaging instance.
A local client root does not make private files available to all server sessions.
An information UUID or instance address is an identifier, not an access token.

Information consumed by work has lineage. Removing an input's availability can
withhold a dependent result rather than merely remove it from search. Workspace
files, immutable information revisions and derived memory each have their own
retention and authorization rules. The system preserves those distinctions even
when a person organizes all three as one personal wiki.

Cross-project messaging sends the permitted body to a receiver in its own project;
it does not carry the sender's private tool grants or document selections across
the boundary. Delegation instead returns a bounded child outcome under its
specific inheritance contract. Board discussion exposes posted contributions
while keeping seat histories distinct. Use the [routing](13-messaging-and-routing.md)
and [swarm](14-swarm-board.md) guides when choosing the boundary your workflow needs.

## Log, Trajectory and model projection

A conversation has a durable log, human views of that log and a model-facing
projection. A run can append several entries within one turn: a model response,
its tool calls and results, published reasoning, or a runtime observation.
Delegated work has its own linked history. The log is not a process log file and
is not one run: it records conversation work across turns.

| Layer | Purpose | What happens when history is folded |
| --- | --- | --- |
| Durable conversation log | Retain recorded entries, their order, kind and available provenance | Original entries remain; a replacement summary and fold relationship are recorded |
| Trajectory | Let a person inspect recorded work, including non-model events and folded originals | Originals remain inspectable, with their replacement relationship |
| Model projection | Construct the model-facing messages from eligible history, summaries, instructions and result handling | Covered history is replaced by the applicable summary in working context |

Here **projection** means a model message construction, not a database read cache,
embedding index or document retrieval projection. Trajectory is a presentation of
the retained record, not another independently executed history. Access checks,
filters and pagination still apply to what a caller can inspect. Folding is not
a promise of permanent payload retention: a separate retention sweep can eject
a tool result's payload while preserving its recorded identity and ejection
metadata.

Recorded does not mean replayed. Utterances, model answers, tool results and
summary/notice kinds can contribute messages. Recorded thinking, hook decisions,
approval diagnostics and failed-attempt diagnostics are not automatically model
messages. A runtime note may have reached the active run without being replayed
on future turns. **Model context eligible** describes entry policy and current
fold state; it is not a receipt proving a particular inference received it.
Current result-handling policy can also replace older tool output with a
redeemable reference or omit it from working history.

For example, a conversation might retain a request to compare two reports, a
model's tool request, the returned excerpts, its answer and an approval record.
After a fold, Trajectory can still show those originals. The next model context
may instead contain a summary, newer messages and result references; the approval
record remains available to the person inspecting the work. A readable original
excerpt is not evidence that the next call included it verbatim. A summary is
working context, not the original evidence needed for precise quotations.

### Chat and context inspection are different views

Chat presents a narrower exchange than Trajectory. The `conversation.chat` read
returns currently projecting entries without folded originals or roleless kinds;
it is still an entry view, not a fully assembled provider request. For example,
it can show an original tool result where model construction substitutes a
reference. Desktop's human chat also selects what to draw, so a simple chat is
not an execution audit. Use [the client inspection table](02-clients-and-work.md#choose-the-right-history-view)
for practical navigation.

A next-context snapshot (`conversation.context.snapshot`) constructs messages,
offered tools and settings for the selected agent **now**, before an unsent user
draft. Its `captured_at` timestamps that preview read, and `projection: next`
identifies a prospective view. Optional counting measures that construction; it
never generates. Saving or displaying this snapshot does not make it an actual
provider-attempt recording. Definition, rules, history or offered tools can
change before the next run, and runtime processing can affect the actual request.

A historical `conversation.projection` with a turn reconstructs the history that
turn opened with, ending at its opening utterance rather than including its later
answer and tool results. When a system block was recorded, `systemBlockAsSent`
identifies that recorded block; otherwise today's definition is explicitly used.
The flag is about the system block alone. Current tool declarations can still
change summary seams and result references, so even this historical view is not
an exact recording of every request byte or every inference within the turn.
Use retained call/attempt provenance and provider-reported usage for actual
inference observations; do not treat an eligibility label, preview count or
system-block flag as proof of provider receipt.

Live thinking and answer deltas are temporary progress. They may be dropped,
truncated or withheld; the durable recorded outcome is authoritative. A recorded
thinking entry can later appear in Trajectory, but a live preview itself is not
a separate durable transcript or model input.

The owning source contracts are [conversation entry storage](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/EntryStore.java),
[compaction and message assembly](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/Compaction.java)
and [next-context preview/count reads](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/ContextCountFrames.java).

## Persistence and projections

The durable record and its retrieval projection are different things. A source
retains bytes and immutable extracted text; paragraphs, chunks, outlines, vectors,
summaries and tags are derived capabilities. A failed embedding does not mean
the original text was never saved. Configuration fingerprints determine whether
derived vectors remain compatible with the selected model space.

Conversation compaction follows the [log/projection distinction](#log-trajectory-and-model-projection):
folding changes working context while retaining recorded originals. Retrieval,
retention and model-context selection have different coverage and lifecycle rules.

Database migrations are append-only Flyway changes. Runtime state machines use
transactions, conditional updates, receipts and leases where needed. These
mechanisms protect particular invariants; there is no universal guarantee of
exactly-once physical effects across an external network.

## Scheduling and coordination

Model pools govern placement and concurrency. Separate lanes and budgets prevent
one kind of work from consuming every slot. Waiting for a child does not need
to monopolize the parent's model lane. Durable leases and fences prevent a late
worker writing into a replaced generation. Restart recovery differs by subsystem:
some queue work resumes, while interrupted execution can be marked unavailable
rather than replayed and charged again.

Schedules and event triggers admit firings into durable work. A firing, job,
orchestration run, outgoing task and message receipt are related handles with
different owners and state machines. Inspect their owning status API; one generic
"running" flag cannot represent an approval wait, unavailable input, pending
remote acknowledgment and a finished partial result accurately.

Direct messaging retains addressed instances and conversations in messaging-owned
records. One project-readable Relay inbox serves each (account, project,
definition); messaging admits each publication into the existing native wake
queue. Instances have separate handling gates and per-message allowances, while
shared conversations reserve unique turn ordinals before execution. The board
keeps its own forum storage, sequencing and [Relay bridge](../decisions/0060-relay-board-bridge-and-authorized-close.md).
See [the messaging cutover](../decisions/0061-relay-agent-inboxes-and-messaging-cutover.md).
Delegation, messaging, swarm topics and outbound work retain distinct lifecycles.

## Module map for developers

The [source-build guide](15-building-and-extending.md) explains setup, build entry
points, extension choices and contribution checks.

| Area | Primary module/package |
| --- | --- |
| Shared Java contracts and operation names | `plowshare-protocol` |
| Server runtime and operation handlers | `plowshare-server`; `server/ws` |
| Agent definitions and execution | `server/agents`, `server/harness`, `server/llm` |
| Persistence and conversation history | `server/archive` |
| Documents and information lifecycle | `server/documents`, `server/information` |
| Swarm and direct messaging | `server/board`, `server/swarm`, `server/messaging`, `server/agents/Messaging` |
| Workflows and external work | `server/orchestrations`, `server/outgoing` |
| Accounts, projects and files | `server/auth`, `server/personal`, `server/files`, `server/union` |
| Shared TS transport and operations | `sdk/typescript` |
| Node file presence and credentials | `sdk/node` |
| Java SDK and A2A adapter | `sdk/java`, `integrations/a2a` |
| Integration policies and HA binding | `integrations/runtime`, `integrations/home-assistant` |

Use the coding standards for contributions. The architecture is the implemented
system; a proposed plan in the repository is not evidence that a capability ships.
