# Coordination, messaging and workflows

## Choose the coordination contract

| You need | Use |
| --- | --- |
| A specialist's result for the current task | Authorized agent delegation |
| An asynchronous exchange with an addressed assistant | Direct agent/bot messaging |
| Shared discussion among specialists | A swarm topic and participant seats |
| A staged repeatable procedure | An orchestration |
| Work sent to another external agent/service | Outgoing work through its adapter |

These mechanisms can use some of the same persistence and scheduling primitives
without granting the same authority. A message is not permission to delegate,
share a private result or bypass a workflow's acceptance checks.

## Direct messaging

Start with [Messaging and routing between projects](13-messaging-and-routing.md)
for a complete sender/receiver setup, named routes, correlated replies and inspection.

Agents and bots can use granted message tools to address a definition in a project
or a particular instance. An instance binds its account, project, definition and
conversation. Persistent instances retain their identity and history; task/caller
lifetimes have different cleanup and return-address rules.

Messages remain within the sending account. Cross-project exchanges need the
account's Contributor-or-higher access to both projects and both projects'
routing policies. Personal's
readable route identity is `Personal:<account-name>`; the label `Personal` alone
is not an address. Instance IDs from another account are not transferable rights.

Sending is asynchronous. `reply_expected` asks for a final handling outcome; it
does not block the sender like a child delegation. Replies identify the original
message, can carry non-final progress, and preserve the original return address.
Only the intended recipient may reply. If an expected final reply is not explicitly
written by the terminal handling outcome, the harness records the outcome fallback
under the original request's uniqueness contract.

The recipient wakes for bounded handling under its own definition, grants and
allowance. Caps, interruptions and failures remain real outcomes. Private transport
topics and participant histories are fenced from ordinary board listings and other
participants' conversation tools. Read the messaging manuals for instance creation,
routing bindings, approvals, deadlines and cancellation.

## Swarm board

Follow [Use and configure the swarm board](14-swarm-board.md) for member placement,
complete opening/inspection commands, seat tools, budgets and explicit recovery.

A swarm is a shared deliberation. A topic carries the public problem and messages;
each seated member has its own private conversation and tools. The server admits
seats, schedules wakes and accounts for a shared reserve. A participant can request
a subtopic or an authorized decision rather than recursively creating unlimited
agents.

Members can be any effective agent definitions with served swarm model bindings.
The shipped research team is one example. A coding or operations team keeps each
member's own tools, scopes and approvals; swarm participation does not impose a
universal read-only role or grant extra authority.

Inspect topic messages, participant state and the current budget. Quiet, exhausted,
capped, interrupted and resolved states mean different things. A stalled member
may need an explicit retry; adding a top-up is not the same operation. Retries
preserve the member's conversation and durable receipt semantics rather than
creating an unrelated fresh discussion.

Useful operational CLI families include `board topics`, `board messages`,
`board post`, `board open`, `board topup`, `board retry` and `swarm status`.
Use their current `--help`/offline schemas for payloads. Board tools offered to
models can be narrower than administrative controls exposed to a person.

## Markdown orchestrations

Follow [Use and build orchestrations](11-orchestration-authoring.md) for the full
authoring walkthrough, placement, start commands, stage grammar and build checks.
The [hook guide](12-hooks.md) explains stage entry/completion gates and their exact
callback placement.

An orchestration defines stages, requested output, acceptance criteria, available
tools/delegates and limits. The conductor works through a durable run. Acceptance
checks distinguish command-observable results from concerns or product judgments
that require a person. It cannot finish simply because its last model response
says the work is done.

The server retains stage state, questions, records and pinned source. The person's
answer must apply to the current pending question; stale keys are not reusable.
Work limits can prompt an explicit continuation or top-up according to policy.
Cancelling a workflow stops further work cooperatively while preserving what
already committed and any incomplete result.

## JavaScript orchestrations

JavaScript can select the next command deterministically while delegating judgment
to agents. A shipped script exports a manifest and a synchronous step function;
the engine journals commands and results, validates payloads and applies the same
grants, hooks and limits. It is a bounded server evaluator, not Node running on
the person's desktop. Arbitrary imports, filesystem and network host access are
not provided to the script.

Start only an effective, granted orchestration. Keep the stable request identity
and retain its receipt/run handle. A run pins exact source bytes and their hash.
Editing the file changes future invocations, not the pinned program already
executing. Recover through its durable command journal and status rather than
reissuing every earlier effect.

Use the detailed orchestration guide for manifests, command shapes, resolver tiers,
approval questions, start receipts, source bounds and authoring/trial behavior.
The small catalogue inventory example is a good first script because it finishes
from existing reads without a model call or document mutation.

## Inspection and acceptance

Watch progress to understand current work; inspect the durable record for what
actually happened. Tool calls, stage milestones, model trajectories, approval
questions and final results are related views rather than identical timelines.
Use `orchestration status`, `orchestration record`, `orchestration receipt` and
the client's work inspection surfaces for their owning records.

An absent result, a denied stage and an uncertain external effect must remain
visible. Retrying a model judgment and retrying a physical command have different
risks and different receipt contracts. Do not recover a workflow by making its
unavailable result look successful.
