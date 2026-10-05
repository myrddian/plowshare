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
| Run / log | One execution, with model steps, tools, outcomes and lineage |
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

Recorded history can outgrow the model's available context. Compaction/folding
creates a bounded working projection while preserving the underlying recorded
entries. Redeemable result handles and retrieval can recover relevant retained
material. A folded summary is useful context, but precise quotations and claims
still need the original retained evidence.

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

## Persistence and projections

The durable record and its retrieval projection are different things. A source
retains bytes and immutable extracted text; paragraphs, chunks, outlines, vectors,
summaries and tags are derived capabilities. A failed embedding does not mean
the original text was never saved. Configuration fingerprints determine whether
derived vectors remain compatible with the selected model space.

Conversation entries retain execution history. Future model context can be a
projection of that history: folds, summaries and redeemable result handles keep
the context bounded. A fold does not erase the transcript or turn a summary into
the original evidence. Retrieval and retention each have explicit coverage and
lifecycle semantics.

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

Direct agent messaging has an actor-like shape: a stable addressed instance owns
a conversation, receives messages and wakes for bounded handling. The internal
board provides much of that storage and wake machinery, but its private transport
topics are hidden from ordinary public swarm deliberation. Delegation, messaging,
swarm topics and outbound work remain distinct contracts.

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
| Swarm and direct messaging | `server/board`, `server/swarm`, `server/agents/Messaging` |
| Workflows and external work | `server/orchestrations`, `server/outgoing` |
| Accounts, projects and files | `server/auth`, `server/personal`, `server/files`, `server/union` |
| Shared TS transport and operations | `sdk/typescript` |
| Node file presence and credentials | `sdk/node` |
| Java SDK and A2A adapter | `sdk/java`, `integrations/a2a` |
| Integration policies and HA binding | `integrations/runtime`, `integrations/home-assistant` |

Use the coding standards for contributions. The architecture is the implemented
system; a proposed plan in the repository is not evidence that a capability ships.
