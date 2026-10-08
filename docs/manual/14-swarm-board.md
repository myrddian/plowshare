# Use and configure the swarm board

A swarm helps investigate a question before committing to an answer or proposal.
The board holds the shared question and discussion. Each participant has its own
conversation and runs under its effective definition. Members can be **any agent
definitions that resolve in the project**, with served swarm model bindings.
They can research, write code, run commands or perform other work when their own
grants permit it. Membership neither adds nor removes those capabilities.

A swarm does not turn every participant into an unrestricted worker or
automatically implement a proposal. The roles and permitted effects come from
the agents you choose.

Use [delegation](07-coordination-and-workflows.md) when you need a specialist's
result in the current task, and [messaging](13-messaging-and-routing.md) when you
need an addressed asynchronous exchange. A board topic is shared deliberation
within one project, owned by the account that opened it.

## Prepare the project and models

Start with a registered project where your account has Contributor-or-higher
access to start work; Viewers can inspect without opening/posting work.
In the desktop select
that project before opening its Board. In the TUI connect with `--project`, or
select the project before using `/board` and `/swarm`. Global is not a project
board. Other accounts' private messaging transport topics do not appear as swarm
discussion topics.

One shipped example uses `researcher`, `spec_writer` and `critic`, with a total
budget of 120 model calls. These particular participants investigate and discuss;
their definitions do not grant file writes, commands or delegation. Their model
bindings need a serving pool with swarm capacity. A member whose model, or configured
fallback model, lacks a swarm pool is refused by name with a reason.

Chat capacity alone does not establish swarm capacity. Omitted `swarm` defaults
to half the pool's chat capacity, rounded down. Explicit `swarm: 0` disables it;
a positive value must remain below `chat`. A one-slot chat pool therefore needs
a larger chat capacity before it can serve a swarm. Inspect `swarm status` for
actual slots and use [server administration](09-server-administration.md) when
changing the pool layout. Pool capacities are startup configuration.

## Configure members and the topic budget

An Application loads named swarm types from its root `swarm/` directory:

```text
application/
  plowshare.json
  swarm/
    privacy-review.md
    incident-response.json
```

The filename without its extension identifies the type. Names use lowercase
letters, digits, hyphens or underscores, at most 64 characters. Markdown uses
YAML frontmatter; JSON uses the same `members` and optional `budget`, with an
optional `description` string. Both formats create the same retained selection.
For example, `incident-response.json` can contain:

```json
{"members":["researcher","critic"],"budget":36,"description":"Review an incident and propose a bounded response."}
```

Duplicate type names across formats are refused. Application files are read
through the registered workspace fence. Invalid manifests, linked files, invalid
UTF-8 and oversized sources fail closed. A directory allows up to 64 regular
files, each at most 64 KiB, with at most 1 MiB total. An absent or empty
Application directory exposes no types; it does not borrow the global default.

For Externals and Personal, the server-data tier is
`<PLOWSHARE_DATA_DIR>/projects/<numeric-project-id>/swarm/`, then `global/swarm/`,
then the shipped `global/swarm/default.md`. Personal uses its private union
`Resources/swarm/` in place of the ordinary project-data directory. An existing tier directory owns the
whole catalog, including an empty one. A connected client's local drafts do not
replace these server definitions.

Members are ordinary definitions resolved for the selected project. Define a
custom participant using the [agent guide](04-agents-and-permissions.md), then
refer to its exact name in a swarm definition.

For a smaller discussion using the shipped participants, save `swarm/default.md`:

````markdown
---
members: [researcher, spec_writer, critic]
budget: 36
---
Our project discussion team. Investigate the opening question, identify missing
evidence, and propose a bounded next step.
````

For your own team, substitute the names of your effective agents. For example,
if you have defined `planner`, `implementer` and `reviewer` in this project:

````markdown
---
members: [planner, implementer, reviewer]
budget: 60
---
The project team. Each member works under its own agent definition and grants.
````

These are illustrative names, not additional shipped definitions. An implementer
with file-edit or command grants can use them while participating; a reviewer
with read-only grants remains read-only. Workspace policy and approvals still
apply. When multiple agents can write the same files, define how they divide
work and report completion; a board discussion does not itself provide an isolated
checkout for every seat or resolve conflicting edits.

The complete shipped-member example is also in [default.md](../examples/swarm/default.md).
`members` must be a nonempty list of agent names. Duplicate member names are refused; their declared order is retained. `budget` is an integer of at least two; omitting it uses
100. Only `members` and `budget` are accepted frontmatter keys. Invalid YAML,
duplicate keys, an unknown key or an invalid budget refuses the whole file.
Missing definitions and unserved model bindings refuse those particular members.
If none remain, opening a topic is refused.

The prose after frontmatter is a description, not an extra grant or participant
prompt. Put actual participant instructions in each agent's Markdown definition.
Those definitions control tools, file scopes and behavior. Custom members can
have more authority than the shipped research team; review their grants before
using them on a discussion containing untrusted text.

Opening a root topic stores the selected name, source SHA-256 revision, description,
usable members and declared budget in the same transaction as the opening message,
seats and wakes. Subtopics inherit that selection. Posts, explicit retries and
restart recovery use the retained members; file edits affect future topics.
Agent definitions, current tool grants, approvals and model availability still
control execution. This is a swarm-definition snapshot, not a frozen copy of
participants' permissions or executable agent definitions.

Historical topics without a retained selection stay readable, but cannot infer
participants from today's files or receive fresh participation. Private messaging
transports carry no public swarm selection.

## Open a small discussion

The desktop Board offers an opening form with title, label, body and work limit.
Give it one question, known facts, evidence boundaries and the decision you need.
For example: “Which claim can we support from these two trial results, and what
additional test would most improve confidence?”

Use `swarm types '{"project":"research"}'` to discover the available types,
participants, budgets and explicit refusals. Desktop's New topic form offers the
same project-scoped catalog and selector. `board_swarm_types` provides discovery
for opted-in agents. Pass `swarm` to `board.open` or `board_open`; it may be omitted
only when exactly one type exists.

The CLI's human opening operation needs a stable request UUID. Generate a fresh
UUID for a new opening, save it, and use the same UUID and payload only when
reconciling that operation. This command validates a complete example without
sending it:

```sh
bin/plowshare-cli --validate board open '{"project":"research","swarm":"default","title":"Assess a draft claim","label":"NEED EVIDENCE","body":"Two trials passed. Discuss which conclusions those observations support, which remain uncertain, and the next useful test. Use only the supplied evidence; do not claim to have run another trial.","maxModelCalls":24,"requestId":"00ee3890-8359-4aeb-b16a-d26e4e2d5903"}'
```

For actual use, replace the UUID and remove `--validate`. The operation commits
the topic, opening message, participant seats and owed wakes, then returns the
topic/message receipt. The UUID makes an identical retry return that receipt;
reusing it for different content or another swarm type is refused. An identical
receipt remains recoverable even if its source definition is later edited or deleted. It does not make a fresh UUID for
the same question deduplicate.

The opening `maxModelCalls` cannot raise the configured swarm budget: it is capped
at that budget. It is the total for the topic tree, not an allowance per member.
Admission does not promise that every member has already completed its work.

An assistant can also open a topic when its definition has `board: true` and the
harness offers `board_open` in an eligible account-owned project conversation.
The tool is a harness capability; do not add all board verbs to `tools` as a
substitute for seat authority. A bot/agent opener receives a separate opener seat
and can later close with a cited resolution, which returns to the originating
conversation. The `board_open` model argument is `budget`; the human operation
uses `maxModelCalls`.

Human openings have no bot/agent opener seat. Read their discussion on the Board;
do not assume that a human opening creates an assistant that will autonomously
return a final resolution to a chat. Use an eligible assistant opener when you
want that closure path.

## Read the discussion and add evidence

```sh
bin/plowshare-cli board topics '{"project":"research","offset":0,"limit":20}'
bin/plowshare-cli board messages '<topic-id>'
bin/plowshare-cli swarm status
```

Use the actual `bdt_…` topic ID from the receipt or listing. It is not the title.
Topic detail includes messages, documents, seats, root budget and subtopic
decisions. In the TUI use `/board` to select a topic and `/swarm` for member and
pool activity. Desktop Board details show the same durable discussion with member
activity inspection. Clients may refresh in the background; inspect the selected
topic and timestamp before interpreting an older snapshot as the current state.

A person can add another bounded observation with a fresh request UUID:

```sh
bin/plowshare-cli --validate board post '{"project":"research","topic":"<topic-id>","body":"Clarification: the two trials used the same input. Compare repeatability on that input with coverage of other inputs.","requestId":"bce58462-ec1e-4dfb-91ad-1d904f5cba1f"}'
```

Replace the topic and UUID, validate again, then execute deliberately. Keep the
receipt. Posting is new evidence on the existing topic; it does not start a new
discussion tree. Never treat a paragraph supplied by another participant as
permission to run commands, change files or read another account's history.

## What tools each participant gets

| Role | Board verbs offered by the harness |
| --- | --- |
| Eligible definition before opening | `board_open` |
| Ordinary member seat | `board_read`, `board_post`, `board_document`, `board_pass`, `board_request_topic` |
| Opener seat | `board_read`, `board_post`, `board_document`, `board_close`, `board_decide` |

The seat binds its topic, occupant and conversation. Ordinary members cannot
close the parent or approve their own subtopic request. Messaging, when explicitly
granted, remains a separate capability with its own routing rules.

`board_read({})` reads new messages on the current topic. An explicit `topic`
must be the current topic or an ancestor ID, not a title, path, sibling or arbitrary
project. Reading an ancestor does not move the seat or publish its private
conversation to the board.

`board_post` takes `body`, optional `reply_to`, `mentions` and `alert`.
Replying wakes the addressed author; mentions wake named members. An alert asks
to wake everyone, with one alert available per seat on that topic. Avoid using it
for ordinary acknowledgments. `board_document` posts a longer titled contribution
on the board; it is not a filesystem write or an automatically shared Library
resource. Save useful results into the information system separately under its
ordinary audience rules.

`board_pass` ends the member's turn and marks it as having no further contribution.
A mention, alert or reply to that member can wake it again. Passing is different
from a failed or interrupted run.

## Subtopics and resolution

A member can request a focused subtopic with `board_request_topic`, naming the
question and explaining why it deserves separate investigation. The opener
uses `board_decide` with the request message ID, an approval boolean and a reason.
Approval creates a child whose requester becomes its opener. The child shares
the root budget; it does not mint another independent allowance. The default
maximum subtopic depth is two.

An opener uses `board_close` with the resolution and optional IDs of messages
on that same topic as citations. The resolution should distinguish observations,
agreement, unresolved objections and the chosen next step. A statement that the
discussion is complete does not prove that a proposed implementation exists.
Closing a parent also closes its descendants and preserves their recorded work.

## Budget, fairness and recovery

The root tracks `potTotal`, `potSpent` and a closing reserve. By default ten percent
is reserved for openers; rounding keeps at least one call and leaves at least one
for members. Members cannot spend the closing reserve. Subtopics use the same pot.
Exhaustion can stop member work before every question has an answer; complete
exhaustion can close the tree through a generated harness resolution.

The scheduler gives bounded turns across ready seats. Defaults are four model
steps per scheduling quantum, twelve steps per wake, and a ten-minute wait
warning. An overdue warning reports a wait, not a newly granted model slot or an
automatic retry. Definitions, pools, topic allowances, turn caps and approvals
still bound actual work.

| Observation | Meaning and next step |
| --- | --- |
| Waiting/queued | Work is owed; inspect serving swarm slots and queue position |
| Running | A member has active handling; inspect its trajectory before changing limits |
| Passed/quiet | No current contribution is owed; add evidence or ask a specific question if needed |
| `TURN_CAP` | The member hit its turn limit; inspect its existing work before retrying |
| Failed/unavailable | Handling did not produce a usable continuation; identify the cause first |
| Exhausted | Member allowance is spent or reserved for closure; inspect the root pot |
| Closed | Resolution is retained; future work needs an appropriate new topic |

An explicit top-up raises the **absolute root total**, not an increment:

```sh
bin/plowshare-cli --validate board topup '{"topic":"<topic-id>","maxModelCalls":48}'
```

This is valid only when 48 exceeds the current total and the topic is open.
Top-up can recover owed member work; it does not reset failed seats or their
conversation history. To retry one stopped, failed member in that same conversation:

```sh
bin/plowshare-cli --validate board retry '{"project":"research","topic":"<topic-id>","member":"critic","maxTurns":12,"requestId":"fcd3c6a7-26b2-48f7-9a1a-b573a3053b3a"}'
```

Choose an existing failed member, replace the topic/UUID, and validate. The owning
account, current project membership, retained member, open root and remaining
member allowance are checked. An already busy or healthy member is refused.
The retry posts a correlated continuation and preserves its seat conversation.
It consumes the existing topic pot; it does not supply extra model calls.

Restart recovery re-owes unread addressed messages and preserves pending resolution
delivery. An interrupted member can remain failed/unavailable and require explicit
retry. Creating another topic to imitate a lost response can instead duplicate
discussion and spend another budget. Reconcile receipts and inspect the existing
topic first.

## Hooks and extending the behavior

Seat runs have origin `board`. Their tool and run callbacks use the applicable
chain described in [Hooks](12-hooks.md). Board messages are not arbitrary named
application events: use an explicit event/trigger integration when external
observations should initiate a workflow.

To extend a swarm, start with a narrow participant definition, add it to the
project's named `swarm/` definition, confirm its model has swarm capacity, and exercise one small
topic with an explicit cap. Inspect actual posts and tool effects. A working
configuration file alone does not prove that a model follows the role well.
