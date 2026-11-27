# Goal Agents

Dedicated inboxes, caller-bound launch, recurring schedule wakes, durable Relay
batching, retained waits, and authenticated returns are available behind
`plowshare.goals.enabled`. The original conversation-goal controls remain
available.

## Security starting point

Use Orchestration's caller-bound launch pattern. Orchestration resolves the
workflow against the caller definition's grants, passes the admitted turn to
`startFromRun` and derives the conductor's authority with
`OperationAuthority.derive`. It retains that admission on the new conversation
and rechecks it before later conductor turns.
[CallerOrchestrations.java](../plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/CallerOrchestrations.java)
· [Orchestrations.java](../plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/Orchestrations.java)

`goal_start` requires the calling agent's `skills:` grant for `goal` and an exact
target in its `calls:` grants. The Goal Agent's tool, delegation and filesystem
permissions must fit within that calling definition's grants. The server uses
the admission of the invoking turn for both
`skill_run` and a user command routed through that bot. A direct authenticated
user launch establishes an admission through the ordinary user authorization
path. Bots under the same account are checked against their own grants.

Derive the goal's admission within the caller's existing action and resource
ceilings. Commit it with the goal conversation, retaining the exact executor,
effective subject, scope, supporting grant lineage and capability fingerprint.
Record the accounting owner separately. The goal retains that binding after
the launcher finishes its turn. Widening its execution authority requires a
separately authorized launch.

Cron, Relay and addressed messages supply typed inputs to the same goal
instance. Before each turn, including after restart, load the exact retained
admission and recheck its supporting grants, scope and capabilities. The
existing owners also check authority at registration, effects and disclosure.
If the admission no longer authorizes the goal, stop automatic admission and
show an authorized operator the reason. Workers use their own authority to
deliver inputs. Goal execution uses the retained admission.
[Authority guide](../.agents/guides/auth.md)

## Launch skill

Use `/skill:goal` from an existing project conversation. A bot with the skill
grant and effective `agentVisible: true` can invoke it through `skill_run`.

```text
/skill:goal Review the pending release notes and record any gaps with supporting file references.
```

The DIRECT skill runs under the agent already speaking in the conversation.
It lists eligible definitions with `goal_candidates`. Name an eligible target
in the request or let the caller choose one. The shipped Interlocutor grants
`goal_worker`, a bounded worker with workspace read access. If the handoff needs
clarification, the skill keeps its invocation and original admission while it
waits for the reply. The reply cannot substitute a different grant or a broader
calling definition.

`goal_start` returns the committed goal ID, message address and conversation.
The server uses the retained skill invocation UUID as the launch request ID,
so retries recover the same handoff. The dedicated instance keeps its own
allowance. Later activations check the original admission and the calling
definition's live handoff grant.
[Skills and bound commands](skills-and-agent-rules.md#bound-commands)

## Handoff and ownership

Each goal uses an existing persistent messaging instance with its own durable
conversation. A goal record binds the objective and lifecycle to that instance
and supplies the Goal Agents list. Several goals can use the same definition
while keeping their objectives, logs, TODOs, inputs and usage attributable to
their own instances. The start records the objective, any completion criteria,
the launcher's identity, the execution scope and authorized return route. The
goal remains available after the launcher's turn settles or the client
disconnects. [Persistent instances and routes](agent-messaging.md#addresses-and-lifetimes)

The agent plans with the existing conversation-scoped `todo_read` and
`todo_write` tools. It can add, revise, reorder or drop items, clear its active
plan and build another. The list carries across turns and wakes. Formal
Orchestrations own declared stages, stage locks and their own completion
checks. A Goal Agent can start one for a piece of work and receive its
correlated result. The Goal Agent still owns its objective and decides when its
assignment or cycle is done.
[TODO tools](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/TodoTools.java)
· [Orchestration authoring](manual/11-orchestration-authoring.md)

## Ad hoc and standing

| Lifecycle | Entry and progression | Ending |
| --- | --- | --- |
| Ad hoc | Starts with an assignment. The agent takes successive bounded turns and can wait for a specific result, approval or user reply. | An explicit completion settles the goal. Cancellation can end it earlier. |
| Standing | Starts once a recurring schedule, durable Relay subscription, or both are attached to the goal. Each qualifying wake can start a work cycle in the same instance and conversation. | Completing a cycle leaves the goal idle with its wake sources retained. Pausing preserves those bindings. Removing the final binding ends recurring eligibility. |

**The agent decides whether it needs a schedule, a listener, or both.** Ad hoc
is the default. Either recurring binding makes the goal standing. A one-off
wait or the ability to receive user messages leaves that lifecycle unchanged.
Removing the final binding lets an admitted cycle finish as ad hoc. Already
queued direct messages remain eligible. A parked goal closes when that
steering is handled. A paused schedule remains registered and stops admitting
occurrences until resumed.

The dedicated Goal Agent can propose a schedule with `goal_schedule` and
attach an eligible Relay source with `message_listen`. Plowshare resolves the
destination from the invoking goal and applies the owning subsystem's rules.
An authorized user or deployment can also configure the binding through the
same owner. The assignment can continue while a schedule proposal awaits a
Manager. Schedule and Relay retain their configuration and delivery state.

## Schedule definitions

The `goal_schedule` tool takes six-field `cron`, `zone` and `input` and
returns a `schedule.save` request for its exact Goal Agent. A project Manager
can apply it while the Goal Agent is open if the schedule source executes as
the account that owns the Goal mailbox. The proposal selects the server
source and requests a new file. Managers can also use `schedule.save` or an
authorized deployment to register matching definitions through their normal
source.

Use a plain `agent` action and a `message` target addressed to the exact
persistent instance within its source account and project. The schedule owner
advances the timer and records each firing with the definition generation.
Edits, pause changes and deletion invalidate never-claimed occurrences of
that generation. An admitted turn keeps its receipt and can settle. Pausing
preserves standing membership and blocks delivery until resume. A valid occurrence uses the Goal Agent's retained authority, joins the
same inbox as other input, and runs after queued direct messages. A settled
cycle leaves a standing Goal Agent idle in its original conversation.

The generation applies to firings admitted by the schedule owner after this
change. Older queued firings require current evidence to reach a Goal inbox.
Messages already accepted into that inbox keep their recorded input type.
[Schedule files and monitoring](scheduling.md#folders-and-monitoring)
· [Schedule destinations](scheduling.md#actions-and-destinations)

## Relay topic discovery

Use the Relay scoped topic catalog as the source for the listener picker.
`relay.topics` lists inspectable topics in a project or the system scope.
`goal.sources` uses the goal's retained project scope and filters by topic
prefix and supported payload kind. It applies source eligibility and current
Relay access before paging and returns the project, topic name, payload kind,
and exact generation needed to select the source.

### Topic ownership registry

Relay now records a trusted subsystem owner and its Goal feed declaration on
the topic row. The scoped key and current generation identify that registration.
Ordinary project and application data topics are general-purpose. The topic
owner can declare a feed eligible when creating its generation.

Repeating an owning registration preserves its classification. Conflicting
claims are refused. Retirement removes the classification along with the topic.
The migration classifies existing native feeds from exact scoped identities and
bindings, including the Messaging inbox registry. Ambiguous historical topics
stay unclassified. A trusted owner can register such a topic as internal.

The internal Goal source selector combines Relay registry eligibility with live
Relay visibility and the retained Goal execution admission. It derives the
numeric source scope from that admission, filters before paging, and locks the
exact selected generation for a binding transaction. It rechecks Relay and
execution authority after taking the lock. The listener picker and
`message.listen` use this selector for discovery and registration.
Built-in registrations currently keep their feeds internal. An explicitly
exposed feed still needs a supported payload kind and an authorized consumer.
Name prefix and payload kind are discovery filters within the retained scope.
[Relay topic contract](../plowshare-protocol/src/main/java/io/aeyer/plowshare/protocol/RelayLog.java)
· [Messaging inbox registry](../plowshare-server/src/main/java/io/aeyer/plowshare/server/messaging/MessageInboxRepository.java)

| Source | How a goal uses it |
| --- | --- |
| Project or application data topic | Include it in the picker when the goal can consume its payload and the existing owner policy permits the subscription. |
| Messaging or Board | Use addressed messaging or the Board API. Their internal inboxes, wake topics and delivery channels stay with their owners. An intentional public feed can appear when its owner supports it as a subscription source. |
| Events or scheduled occurrences | Use JSON cron definitions for scheduled wakes. A future named-event wake would enter through an accepted Events trigger firing. The derived Relay topics stay internal to those APIs. |

Use the same eligibility check for direct `message.listen` requests and
validate the exact topic generation when binding. Recheck current source and
execution authority at delivery, including existing scope and owner rules.
Apply any confidentiality restriction at the Relay read boundary. For private
goal messages on a project-readable topic, reuse the retained message
reference form and resolve the body through Messaging's participant and
conversation checks.
[Relay inspection](relay.md#inspection-and-clients)
· [Messaging](agent-messaging.md) · [Board bridge](relay.md#board-bridge)
· [Events and triggers](manual/12-hooks.md)

## Listen to a Relay topic

`goal.start` creates the dedicated Goal Agent, a non-default persistent
Messaging instance, and its retained conversation in one transaction. An
identical launch request ID returns the original goal and inbox. Direct
launch requires a human project account with the required live grants.

`goal.sources` is the listener picker for a selected Goal Agent. It takes the
goal ID and its returned conversation ID, and can filter by prefix, cursor and
payload kind. It defaults to `TEXT` and also supports an explicit `EMPTY`
selection. `message.listen` takes the same goal and conversation, plus the
topic and generation from the picker. The response includes the binding UUID.
Omit the batch fields for one wake per event. Supply both `batchCount` (2–16)
and `maxDelaySeconds` (1–86400) to batch. The dedicated Goal Agent can use
`goal_sources` and `message_listen` during its turns with the same source
checks and options.

The owner can use `message.unlisten` with `goal`, `conversation`, `binding`,
the current goal `version`, and a `requestId`. An identical retry returns the
retained removal receipt. Rebinding the same feed creates a new binding and
starts reading at the current broker head. Cleanup retires only the removed
listener's exact consumer group.

Relay commits staged input and the consumer offset together. The goal
rechecks current read authority, topic generation and consumer generation at
admission. If the source changes or a retention gap opens, the listener stops.
The goal surfaces the reason and any missing position range. Remove a failed
listener before resuming the goal. Each inbox accepts up to 64 unsettled Relay
events and 16 registered listeners.

The existing `message.receive` receiver accepts a native message request
through a configured Relay command channel. An addressed message can also
reach the Goal Agent through ordinary messaging. A `message.listen` binding
adds a further source of input that can wake the goal while it is idle.
[Native messaging through Relay](relay-messaging-receivers.md)

## Sources and delivery

| Input | How it reaches the Goal Agent |
| --- | --- |
| Schedule | Reuse the existing `message` schedule target addressed to the goal's persistent instance. The schedule owner keeps cron, time zone and the per-firing delivery receipt. The goal recognizes the trusted firing and binding revision when admitting the message as a wake. |
| Relay subscription | A `message.listen` binding connects the existing Relay subscription path to the goal's exact instance and feeds its normal message intake. |
| User message | The normal addressed message path commits the message with its sender and wakes an eligible idle goal. During an active turn it queues for the next safe boundary in the same conversation. |
| Specific dependency | An exact file-read approval or a granted orchestration started with `goal_orchestrate` wakes its retained activation. Its owner keeps the invocation and outcome. |

Use one wake per eligible Relay event by default. If a binding needs batching,
apply it on the subscription delivery path. Queue a wake when `N` matching
events have accumulated or `T` has elapsed since the first pending event,
whichever happens first. The first staged event sets the deadline. A bounded
scan matures quiet batches after that deadline, including after restart.
Membership freezes when the batch becomes eligible, and later arrivals go into
a subsequent batch. Its stable ID and event positions accompany the input in
the conversation's provenance.

Advance the Relay consumer position only once Plowshare can recover the
durably staged input independently of another delivery. Record successful
handling separately after the turn settles. Duplicate deliveries converge on
the same staged event and batch. A retention gap or topic recreation stops
admission for the binding and surfaces the missing range or source change to
an authorized operator. Schedule wakes, Relay batches and user messages share
one turn gate. Direct messages take priority at every activation boundary.
An ongoing cycle can accept steering while fresh automatic work waits for
the next cycle. Pending input holds no provider slot.
[Relay workers](relay.md#automatic-subscription-workers)

Preserve the sender and the recorded body of user messages. Represent
launches, ticks, batches and dependency results with typed provenance in the
append-only conversation log. Keep the objective and current TODOs available
to the model after compaction. Only reachable and authorized payloads enter
the projection.
[Conversation log and projection](../.agents/guides/runtime-and-inference.md#log-trajectory-and-model-projection)

## Retained dependency waits

A dedicated root enters `WAITING` after parking an existing file-read approval
or starting a declared orchestration with `goal_orchestrate`. The tool takes
the exact declared orchestration name and a request. Call it by itself. The
orchestration start derives authority from the Goal's retained admission and
has its own allowance. A bot launching a Goal through the skill must also
hold every orchestration grant declared by that Goal definition. Ordinary
conversation goals and synchronous delegates keep their original tool ceilings.

The parked Goal releases the conversation and execution job. It keeps the
original cycle, activation, invocation and wake source generation. A decision
or terminal result that arrives before the park can be recovered by the
scheduler. Approval recovery runs the original file read in its recorded tool
slot after checking the Goal admission and source. Orchestration recovery reads
the exact start receipt and terminal attempt, then records its outcome with
the original invocation's provenance. The Goal inherits the result's
information access requirements. Reopening a child before the Goal claims its
result fences the old terminal attempt.

Manual pause preserves the wait and delays admission until resume. Cancellation
records cleanup for the exact owner and fences any later answer. If the original
effect cannot be established, the Goal blocks for inspection. A continuation can
settle its owner receipt with no remaining model calls. It uses the existing
turn step cap, and creating another wait requires another charged model call.
New automatic wakes remain queued during the wait. Queued steering can enter
once the dependency is safe to reconcile and the cycle has model allowance.
Addressed messages use the normal inbox and queue behind a parked dependency.
`goal_ask` parks a dedicated Goal with an authenticated return route. The
question is recorded in the Goal conversation before delivery. Only its exact
authenticated answer can reopen the original cycle.

## Steering and notifications

When a user messages a running Goal Agent, let the current turn or its owning
continuation reach a safe boundary. Process queued user steering before
admitting fresh automatic work. Check for steering committed during the turn
before closing an ad hoc goal. The next turn sees the messages, the existing
objective, the current TODO list and any settled dependency result. The goal
continues from that state. A direct message can wake an idle standing agent.
A question uses its exact authenticated answer route. Pause and cancel remain
available while a turn is running.

An authenticated `goal.start` or a person’s `/skill:goal` command in their
active project conversation binds their account Inbox at launch. The Inbox
receives a reference when an assignment completes, the Goal uses `goal_ask`,
or the Goal records an explicit blocker. Open the Goal conversation to read
the result or question with its evidence. Routine standing cycles settle
silently in the Goal conversation.

To answer an Inbox question, use `goal.answer` with the exact `goal`,
`conversation`, `question`, a new `requestId`, and `answer`. The question
UUID appears in the Inbox notice and the waiting status. The question must
have a committed Inbox receipt. A retry with the same request ID and answer
recovers the original receipt under current access. A different answer or a
different question under that request ID is refused. An answer received
during manual pause is retained until resume. Other messages remain steering
for the next eligible turn.

An active persistent bot handling an authenticated native message can opt in
with `replyExpected: true` on `goal_start`. The caller must hold
`send_message`. The skill returns a native launch message ID for the exact
calling instance. In a later turn, that bot can use `goal_return_read` with
the launch ID in `message`. An account operator can inspect its Messaging
deliveries. A bot
answers a question with one explicit final `send_message` reply to that
question’s message ID. Goal returns to the bot are retained without starting
another paid turn. A blocker is a non-final notice. Completion or
cancellation closes the launch’s reply expectation.

Goal status and return delivery have separate durable receipts. Publication
rechecks the Goal’s current authority and the retained destination. A
withdrawn route or superseded blocker can withhold a return while the
committed conversation and result remain available to authorized readers.
[Agent and bot messaging](agent-messaging.md)

## Admission, recovery and limits

The goal controller owns durable lifecycle and ready inputs. Reuse the ordinary
turn runtime for conversation exclusion, inference, tools and accounting.
Event and scheduler workers signal eligible work after commit. A bounded
server scan recovers missed wake signals without calling a model to check for
work.

- Claim each eligible activation once against the goal's generation and
  activation number. Persist the exact job identity before inference or
  effectful work. Fold a committed turn settlement into goal state once.
  Then continue, register a specific wait, finish the assignment or cycle, or
  record a blocker. A committed completion carries result or evidence
  references. Ordinary progress prose and TODO status alone leave the
  lifecycle unchanged.
- A specific wait stores the dependency reference and checks its durable
  state when parking. Both notifications and restart recovery reconcile through
  that reference. Resume the original invocation through its owner where
  required. A wait releases idle execution capacity.
- Reconcile committed settlements and work proven unstarted against the same
  identity. Reconcile an uncertain effect using its original receipt and
  owner. Block if safe continuation cannot be proved. A worker crash or
  expired lease is insufficient evidence to repeat an effect.
- Pause fences future admissions at the current safe boundary. A committed
  turn can still settle while paused, and incoming inputs retain their own
  delivery status. Resume rechecks the bindings and current authority. Cancel
  fences new work immediately, disarms wakes and asks the owner to stop active
  work. Track its capacity until it settles. Validate the binding revision at
  admission so disarmed or superseded bindings cannot start queued work. Late
  results can close their own receipts without starting another goal turn.
- Bound each active assignment or cycle with finite activation, step and
  model-call limits. A standing registration can outlive a work cycle. Each
  automatic cycle draws from the owner's finite retained allowance. The
  `plowshare.goals.cycle-model-calls` setting defaults to 8, bounded
  by the agent definition and remaining canonical calls. The
  `plowshare.goals.automatic-cycle-interval` setting defaults to `PT1M` and
  sets the minimum interval between automatic claims within the same goal.
  Direct steering can wake a parked goal during that interval. Continuing a
  cycle preserves earlier spending. Repeated no-progress turns pause the goal.
  Queued work remains subject to the existing capacity policies.
  [Backpressure](backpressure.md)
  · [Budget continuation](decisions/0073-agent-budget-consent-and-continuation.md)

## Discovery and controls

The browser Console's **Goal Agents** view lists dedicated goals in the
selected project. It shows the authorized objective, instance address,
lifecycle, current state, registered wakes, next scheduled wake, pending Relay
batch, latest result and any reason that work is paused or blocked. A
readable entry offers a link to the exact conversation's read-only history,
Trajectory and TODOs. Metadata-only entries expose lifecycle and state without
opening conversation content. The goal keeps the same ID when its lifecycle
changes.

Provide typed WebSocket and SDK operations to start, list, inspect, pause,
resume and cancel Goal Agents. Use existing messaging for direct conversation
and existing schedule and Relay owners for binding controls. A start carries a
caller-supplied request ID and returns the original handle on an identical
retry. Changed arguments under the same ID are refused. Mutations target the
exact goal and expected version. After uncertain launch delivery, read
`goal.receipt` with the original project and request ID to recover the launch
handle and its initial version. Read `goal.status` for the current version
before issuing a control. An uncertain control can be checked by its original
request ID through `conversation.goal.get` and compared with current status.
The server checks current read authority for discovery and conversation history.
[Transport and SDK guide](../.agents/guides/transport-sdks.md)

## Delivery sequence and verification

1. **Dedicated ad hoc instances.** Reuse existing persistent instances,
   addressed messages and ordinary TODOs, plus the prototype's activation
   and settlement seams. Add the launch skill. Verify
   that an explicit user command and a bot skill call reach the same start
   contract, denied callers create no instance, and retrying either
   invocation returns the same goal. Test two bots under the same account
   that have different launch or execution grants for the same target
   definition. Check that the bot can accept another request, the goal can
   revise its plan and span multiple turns across a restart, and completion
   prevents further automatic turns.
2. **Standing wake bindings.** Use scheduled messages and add exact-instance
   delivery from Relay through `message.listen`. Connect binding requests to
   the existing owner controls. Verify registry recovery after restart,
   conflicting owner claims, filtered discovery, and refusal of internal
   or inaccessible topics even by exact name. Test authority changes and
   topic recreation. Verify schedule-only, Relay-only and combined
   bindings, per-cycle settlement, final binding removal, and user steering
   before fresh automatic work. Test duplicate occurrences, count- and
   age-triggered batches, events arriving during a turn, and recovery after
   missed wakes and retention gaps. Revoke one bot's supporting grant while
   a cron or Relay wake is queued. Verify that the wake stays fenced across
   restart and the other bot's authorized goals can still run.
3. **Retained waits.** Use the exact approval call journal and orchestration
   receipt. Verify early decisions, duplicate and late results, stale terminal
   attempts, source withdrawal, cancellation while waiting and recovery after an
   uncertain effect. Use PostgreSQL tests for durable claims and transaction
   boundaries.
4. **Authenticated returns.** Retain a human Inbox or exact native Messaging
   audience at launch. Commit return intent with Goal settlement, then let the
   owner publish it. Verify exact question answers, pause and restart recovery,
   revoked destinations, non-final blockers, and silent routine standing cycles.
5. **Goal Agent discovery.** Verify project-scoped pagination, metadata-only
   disclosure, latest result retention, and recovery of uncertain controls and
   launches. Check that Console history and TODOs open the selected conversation.

## Current prototype

The working-tree prototype gates both goal paths behind
`plowshare.goals.enabled`. The original conversation goal loop admits a direct
user account with an existing
active project conversation and a server-resolved exported agent with finite
allowances. Its default goal limit is eight activations and its default
scheduler concurrency is four.
Start, get, pause, resume and cancel are available under `conversation.goal.*`.
Start and control requests carry request UUIDs, and controls use the goal ID
and version. Read an original receipt using `conversation.goal.get` with the
conversation and its request ID.

```sh
plowshare conversation goal start --payload - <<'JSON'
{"conversation":"cnv_EXAMPLE","requestId":"6bb0ae58-d47a-4bba-93e6-929e45c1ea26","agent":"reviewer","objective":"Assess the available evidence and explain the unresolved gaps","maxActivations":8}
JSON
plowshare conversation goal get cnv_EXAMPLE
```

The prototype admits turns into the supplied conversation and records a
committed settlement, model-call spend and explicit completion evidence.
Supported synchronous tools and delegates run through the confined runtime.
A started activation without a committed settlement blocks for inspection.
Dedicated instances use `goal.start`, persisted addressed messaging and
Relay listeners. Run `plowshare goal start --payload -` with the project,
agent, objective and launch request ID. The browser Console **Goal Agents**
view provides the project list, status details, controls and read-only
conversation history with TODOs. The CLI accepts JSON on stdin for these reads.

| Command | JSON fields |
| --- | --- |
| `plowshare goal list --payload -` | Required `project`, optional `after` and `limit`. Use the returned `next` as the next page's `after`. |
| `plowshare goal status --payload -` | Required `project` and `goal`. Use the current status version for controls. |
| `plowshare goal receipt --payload -` | Required `project` and the original launch `requestId`. Returns the original start response. |

V301 retains the latest explicit completion from newly committed dedicated
Goal settlements. The result includes its job, turn, summary and evidence.
Older completions without an exact retained result stay absent after upgrade.

Pass the launch handle's goal and conversation to
`plowshare goal sources --payload -`. Include a returned topic and its
generation with the same goal and conversation in
`plowshare message listen --payload -`. The `/skill:goal` command also launches
a dedicated instance from an existing project conversation. Apply schedule proposals through the existing schedule
owner controls. Read the current goal version before removing a listener.
Missing Relay groups from older versions require binding removal before
resuming.

Use optional `maxModelCalls` on a dedicated `conversation.goal.resume` request
to authorize an absolute positive allowance up to 1,000,000 calls. The owner
can increase the total or explicitly extend an exhausted active cycle within
its already authorized remaining allowance. Previously spent calls still count.
Change a Goal cycle's allowance through resume. Resuming a parked goal restores
its idle state.

When upgrading from V297, the server cannot recover the exact inbox item
claimed by an interrupted old turn. Pending inputs for those interrupted turns
are recorded as uncertain. Old paused rows keep their previous resume
behavior because their storage did not distinguish a parked cycle from an
active one. The new cycle record preserves that distinction.
