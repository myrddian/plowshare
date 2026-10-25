# Waiting is turn eligibility, not a Relay capability

Status: standing intent, and a correction to two framings made while investigating it. No code
change is proposed here. It exists because "an agent waits for an answer" has been implemented
twice, in two different layers, and a third implementation was written and not landed.

## What a wait actually is

A waiting agent is a conversation that **takes no turn until a particular thing arrives, and whose
next turn carries that thing**.

Two things follow, and both were got wrong before they were got right.

**A wait is not a log position.** Relay's whole surface is eleven methods — `publish`, `retained`,
`read`, `subscribe`, `subscribeAsReader`, `advanceSeen`, `acknowledgeGap`, `unread`, `topic`,
`configureTopic`, `registerTopic`. That is a durable ordered log with per-subscriber positions.
`unread` can answer whether a wait is satisfiable, but only if something else thinks to ask: a
position does not stop a turn being scheduled. Nothing in Relay parks a consumer, and nothing in
Relay should.

**A wait is not inherited.** It is a fact about one conversation at one moment — eligible, or not,
right now. `relay_causation` (V127) models inheritance: a root, a parent, a depth. Eligibility has
no lineage, so causation is the wrong *kind* of fact for it, independently of any mechanism. This
is why an attempt to bound a wait chain with `relay_valid_causation`'s depth found nothing to read:
`relay_inherit_job` fixes a mailbox's conversation as a root at `depth 0` from its first job and
propagates that root back onto the conversation, so no depth is ever carried across a send. The
mechanical reason is real; the category error came first.

## The gap

There is no single owner of turn eligibility.

`Dispatcher` declares the question twice, once per caller shape, and is told the answer through a
post-construction setter:

```java
public interface Runner { boolean busy(TriggerRecord trigger); String start(...); }   // :24
public interface Wakes  { boolean busy(FiringRecord wake);     String start(...); }   // :62
public void useWakes(Wakes wakes)  // :95 — "Set once at wiring, by the board."
```

Three implementations answer it, and one of them does not go through the seam at all:

| answerer | how it says "not now" | reached through |
|---|---|---|
| `SeatRunner.busy` (:139) | board seat occupancy | `Dispatcher.Wakes` |
| `BoardMessaging.busy` (:1002-1007) | `voice.busy(conversation)` **and** `repository.otherStarted(...)` | `Dispatcher.Wakes` |
| orchestrations | `orchestrations.state='waiting'` | nothing — `route` reads the row directly |

Because the seam does not own eligibility, each system that needs to wait builds its own waiter and
its own resumption:

| | delegation (V48/V52, shipped) | messaging (written, not landed) |
|---|---|---|
| waiter state | `orchestrations.state='waiting'` + `waiting_for`, with `CHECK ((state='waiting') = (waiting_for IS NOT NULL))` | `board_message_routes.waiting_request` + `wait_resumed_at` |
| resumption | `store.wake(parent)`, then the report is spoken in | a `firings` row keyed `message_reply`, through the continuation queue |
| backstop | `StallSweep` | the message's own `deadline_at` |
| legality | tree: `waiting_for` names a child the caller just created, `max-depth` default 2 — a cycle is unexpressible | general graph: any mailbox may message any mailbox in the account, so A↔B needs a send-time walk and an advisory lock |

Both are turn-scheduling gates wearing different clothes. `WAITING` is entered only through
`OrchestrationStore.waitFor` and left only through `wake`, with two call sites
(`Orchestrations.java:1200`, `:1374`); the messaging version keeps a request started-unhandled and
leans on `board_message_one_handling_request` to stop the mailbox starting anything else. Same
shape, different table.

## The layering

```
Relay            →  arrival: the awaited thing is a publication
turn scheduling  →  eligibility: this conversation takes no turn until X; the next turn carries X
owning system    →  legality: may this wait be taken at all?
```

Arrival is already Relay's for two families — `schedule.due` and `event.fire` both publish to a
topic and are consumed by the firing inbox. Messaging's own move onto Relay remains blocked for an
unrelated reason: a message body cannot be published at project scope without `relay.log` exposing
account-private mailboxes.

**Legality stays with the owning system, and Relay cannot take it.** Relay has no way to know that
A waiting on B closes a cycle in messaging's mailbox graph, or that delegation's tree makes that
cycle impossible to construct. The asymmetry in the table above is the proof: the same capability
needs a send-time cycle walk in one policy and nothing at all in the other. Pushing either answer
into the substrate would make Relay the policy.

So the following belong to their owners permanently, not to the substrate: the cycle refusal,
`ROOT_CANNOT_WAIT` (`CallerOrchestrations.java:37`), `max-depth`, one-wait-per-row, and
`StallSweep`'s judgement about what a stall means.

## How delegation should be implemented

Delegation was long intended to be rebuilt on messaging, keeping its API. **That intermediate step
should be skipped.** Delegation goes to Relay directly, because messaging is itself still on v0 and
is blocked from moving, and routing delegation through it would put delegation behind a blocker it
does not share.

**Messaging's blocker is not delegation's.** Messaging cannot publish at project scope because a
message body is account-private mailbox content and `relay.log` would expose it
(`RelayPortEvents.java:64` renders a `Text` payload's text). Delegation has no equivalent exposure:
`orchestration.status`, `orchestration.record` and `orchestration.receipt` are already in a
project-scoped read set (`ProjectAuthorization.java:115-119`), so an orchestration's state and
records are project-readable today. Delegation therefore needs to move a **notice**, not a body —
which is exactly `RelayPayload.Lifecycle`, "a bounded change notice with references to owning
records, never command/result text". The request and report text stay in the orchestration record,
read through its existing authority.

Much of the inbound leg already exists, which is why this is a conversion rather than a build:

| already built | where |
|---|---|
| `orchestration.start` as a Relay work receiver | `WorkRelayReceiver.java`, `RelayConfiguration.java:430` |
| pinned work destinations | `relay_deliveries.work_agent`/`work_project`/`work_definition`, `relay_work_destination` CHECK (V121) |
| idempotent starts and receipts | `relay_executions` (V121), `RelayExecutions.begin`/`accepted`, receipt namespaces `job` and `orchestration` |
| orchestration lifecycle notices | `orchestration.changed` (`OrchestrationsConfig.java:98`), `orchestration.recorded` (`RecordKeeper.java:48`) |
| the orchestration family in the source log | `relay_inherit_source`'s `topic LIKE 'orchestration.%'` branch (V127) |

So the conversion is:

- **`orchestration_start(name, request, context, wait)` is unchanged.** Agents see the same tool.
- **Starts and reports travel Relay** as `Lifecycle` publications naming the run, not its text.
- **The wait stays delegation's policy**, expressed through the one eligibility owner rather than a
  bespoke row state only `route` consults. `state='waiting'` is the right *decision* in the wrong
  *place*; it does not become a Relay concept.
- **Delegation keeps its tree.** `max-depth`, `ROOT_CANNOT_WAIT`, the cancel-descendants cascade,
  `StallSweep`, `Started.waiting()`'s asked-for-versus-happened distinction: all policy, all
  delegation's.
- **Messaging is untouched**, and stays asynchronous with no sender-side wait on its tool surface.
  Its own move to Relay remains blocked on the mailbox-privacy problem, independently of this.

The consequence worth stating plainly: **delegation needs no cycle check**, because you may only
wait on a child you just created, in a tree capped at depth 2. A substrate-level waiter would have
charged delegation for a defence its own structure already provides.

What this does not settle: whether the lifecycle notice alone carries enough for a conductor's turn
to be resumed usefully, or whether the resumption must read the record to compose the utterance. The
latter is likely and is not a problem — it is the same shape as a firing carrying a reference the
turn then reads — but it has not been verified against `Delivery` and `ParentVoice`.

## What was written and not landed

A sender-side `wait_for_reply` on `SendMessageTool`, with `V192`, a send-time cycle walk, a
`wait:<account>` advisory lock, and 19 tests that pass against a real PostgreSQL — including a pin
that fails, and fails alone, when the cycle check is removed. It is a correct implementation of the
wrong thing: it mounts a synchronous construct on the asynchronous tool agents call, and it is a
third durable waiter where the gap is a missing owner for the first two.

Its findings are kept here because they outlive the code: causation is not the right kind of fact
for a wait; a messaging cycle wedges *mailboxes* and not merely turns, because
`board_message_one_handling_request` leaves both parties unable to start anything else; and
`AWAITING` plus the existing continuation queue is sufficient to park and resume a messaging turn.

The `WAIT_CHAIN_LIMIT = 32` it borrowed from `relay_valid_causation` should not be read as a
principled bound for a wait chain. It is a loop guard that happens to share a number with an
unrelated concept.

## Corrections recorded

Two framings were wrong on the way here, and both were corrected by the same observation — that
what makes delegation wait is the scheduling of its turn.

1. **The task was dispatched asking for the causation depth to be reused as the wait bound.** A
   wait is not inherited; no depth was ever going to be there to read.
2. **The missing primitive was first identified as a Relay-level waiter.** Building it there would
   have pulled turn scheduling into the substrate — the policy-into-mechanism mistake this record
   exists to prevent.
