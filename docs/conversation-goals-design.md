# Goal Agents

**Status** — This design describes dedicated Goal Agents, their launch skill
and their wake sources. The working-tree prototype runs goals inside an
existing conversation. See [the prototype](#current-prototype) for the
available controls and its scope.

## Security starting point

Use Orchestration's caller-bound launch pattern. Orchestration resolves the
workflow against the caller definition's grants, passes the admitted turn to
`startFromRun` and derives the conductor's authority with
`OperationAuthority.derive`. It retains that admission on the new conversation
and rechecks it before later conductor turns.
[CallerOrchestrations.java](../plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/CallerOrchestrations.java)
· [Orchestrations.java](../plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/Orchestrations.java)

Apply the same pattern to `goal_start`. Authorize the selected Goal Agent
definition against the invoking bot's launch grant in the current scope.
Use the exact server-retained admission for the invoking turn for both
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

Ship a `goal` skill in `DIRECT` mode. A user can invoke it explicitly in the
current conversation. A bot with the skill grant and effective
`agentVisible: true` can invoke the same skill with `skill_run` when a user
asks it to hand off work.

```text
/skill:goal Review the pending release notes and message me with any gaps.
```

The skill takes an objective and any constraints the caller supplies. It uses
the specified agent definition if eligible, or selects an eligible one through
the agent directory. If the objective or target is ambiguous enough to prevent
a valid start, the skill asks for clarification. Otherwise it calls the
proposed `goal_start` tool and returns the goal ID, message address and initial
status from the committed receipt. The skill's foreground work ends at that
receipt. The Goal Agent decides later whether it needs a standing wake source.

Both invocation paths use the same typed start contract as the WebSocket and
SDK operations and enforce the caller-bound authorization above. Launch and
directory access require their own grants. Use the retained skill invocation
identity to correlate its single start, so resuming or retrying the same
invocation can recover the original goal without creating another one.
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
When the final binding is removed, an already admitted cycle can finish as ad
hoc. If no work remains, close the assignment and retain its history. A paused
binding remains registered and cannot admit a cycle until resumed.

The missing agent-facing capability is to request a durable wake binding for
its own instance. The request names the desired schedule or Relay subscription.
Plowshare resolves the destination from the invoking goal and applies the
existing owner's rules. An authorized user or deployment can also attach a
binding through the owning configuration path. Where that policy reserves
control to a person, show the proposed binding for an authorized person to
apply. The agent can continue its ad hoc assignment while registration is
pending. Once the owner confirms the registration, the goal records the binding
reference and derives its lifecycle and next-wake information from the owners'
status. Schedule and Relay keep their own configuration and delivery state.

## Schedule definitions

Use Plowshare's existing JSON schedule files. A wake uses the normal `cron`
and `zone` fields and a `message` target addressed to the goal instance. Save
through `schedule.save` in the authorized source, under `schedules/` for
server definitions or `.plowshare/schedules/` for a rooted workspace. The
goal keeps the resulting schedule identity. The schedule owner advances the
timer and records each firing and its delivery receipt.
[Schedule files and monitoring](scheduling.md#folders-and-monitoring)
· [Schedule destinations](scheduling.md#actions-and-destinations)

## Relay topic discovery

Use the Relay scoped topic catalog as the source for the listener picker.
Today `relay.topics` lists inspectable topics in a project or the system
scope. Add a filtered discovery view that lists existing topics the goal is
authorized to use as wake sources. Support narrowing by project, topic name
or prefix, and supported payload kind. Apply source eligibility and current
Relay access on the server before paging. Return the scope, name, payload kind
and topic generation needed to select the source.

### Topic ownership registry

Extend Relay's durable topic registry so each subsystem records ownership
when it registers a topic. Store the scoped topic key, topic generation and
a server-assigned subsystem identifier in the same transaction as topic
registration. Ordinary project and application topic registrations identify
those topics as general-purpose. Only trusted server registration code can
assign subsystem ownership or expose a subsystem feed for subscriptions.

Repeated registration by the same owner is idempotent. Conflicting ownership
is refused. A recreated topic registers ownership for its new generation.
Populate ownership for existing topics from authoritative subsystem bindings
or registrations, such as the Messaging inbox registry. Discovery requires an
established classification.

The listener picker combines topic ownership with current Relay access. It
includes eligible general-purpose topics and any feeds their subsystem owner
explicitly exposes. Scope, name and payload kind remain search filters.
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

Use `message.listen` as the proposed name for attaching an authorized Relay
topic to the current Goal Agent's persistent inbox. The request selects a
topic and its generation from discovery and returns a durable binding
reference when accepted. Plowshare resolves the destination from the
invoking goal. Relay owns the subscription, cursor and delivery. Each
eligible publication enters the goal's normal inbox as a typed wake with
Relay provenance.

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
| Specific dependency | A known child run, approval or other supported dependency wakes the exact wait, with its original invocation and outcome. The dependency owner still controls resumption and cancellation. |

Use one wake per eligible Relay event by default. If a binding needs batching,
apply it on the subscription delivery path. Queue a wake when `N` matching
events have accumulated or `T` has elapsed since the first pending event,
whichever happens first. Preserve that deadline and the staged references
across restart. Freeze membership and give the batch a stable ID when queued.
Later arrivals belong to a subsequent batch, including while the current one
runs.

Advance the Relay consumer position only once Plowshare can recover the
durably staged input independently of another delivery. Record successful
handling separately after the turn settles. Duplicate deliveries converge on
the same staged event and batch. A retention gap or topic recreation stops
admission for the binding and surfaces the missing range or source change to
an authorized operator. Schedule wakes, Relay batches and user messages share
one turn gate. Pending work holds no active provider slot.
[Relay workers](relay.md#automatic-subscription-workers)

Preserve the sender and the recorded body of user messages. Represent
launches, ticks, batches and dependency results with typed provenance in the
append-only conversation log. Keep the objective and current TODOs available
to the model after compaction. Only reachable and authorized payloads enter
the projection.
[Conversation log and projection](../.agents/guides/runtime-and-inference.md#log-trajectory-and-model-projection)

## Steering and notifications

When a user messages a running Goal Agent, let the current turn or its owning
continuation reach a safe boundary. Process queued user steering before
admitting fresh automatic work. Check for steering committed during the turn
before closing an ad hoc goal. The next turn sees the messages, the existing
objective, the current TODO list and any settled dependency result. The goal
continues from that state. A direct message can wake an idle standing agent or
answer an ad hoc question. Pause and cancel are explicit controls that remain
available while a turn is running.

The Goal Agent can message the user with a result, a question, a blocker or a
condition the user asked to hear about. Use the existing authorized return
route to the user or initiating conversation. A bot that launches the goal can
request a correlated outcome through the normal reply contract. Routine cycles
can settle in the goal conversation and remain visible from the goal list.
Status and message delivery have their own receipts. A failed delivery leaves
the committed work and its result available to authorized readers.
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
  later cycle draws from the owner's authorized budget and configured rate
  limits. Continuing a cycle preserves consumed allowances. Repeated
  no-progress turns pause with a diagnostic. Bound active and queued work with
  the existing capacity policies.
  [Backpressure](backpressure.md)
  · [Budget continuation](decisions/0073-agent-budget-consent-and-continuation.md)

## Discovery and controls

Expose a **Goal Agents** list in Plowshare, separate from the general agent
directory. The directory helps the bot find definitions and addressable
instances. The goal list shows each authorized goal's objective, instance
address, lifecycle, current state, registered wakes, next scheduled wake or
pending Relay batch, latest result and any reason that work is paused or
blocked. Opening an entry leads to the goal's conversation and TODO list.
The goal record keeps the same ID when its lifecycle changes.

Provide typed WebSocket and SDK operations to start, list, inspect, pause,
resume and cancel Goal Agents. Use existing messaging for direct conversation
and existing schedule and Relay owners for binding controls. A start carries a
caller-supplied request ID and returns the original handle on an identical
retry. Changed arguments under the same ID are refused. Mutations target the
exact goal and expected version. A client can look up the original receipt
after uncertain delivery and read current status after reconnect. Apply
current read authority to status, payloads and conversation history.
[Transport and SDK guide](../.agents/guides/transport-sdks.md)

## Delivery sequence and verification

1. **Dedicated ad hoc instances.** Reuse existing persistent instances,
   addressed messages and ordinary TODOs, plus the prototype's activation
   and settlement seams. Add the launch skill and goal discovery. Verify
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
3. **Retained waits.** Wire each supported dependency through its owning
   continuation contract. Verify early, duplicate and late results, authority
   withdrawal, cancellation while waiting and recovery after an uncertain
   effect. Reserve PostgreSQL tests for durable claim races, uniqueness and
   transaction boundaries.

## Current prototype

The working-tree prototype gates its conversation goal loop behind
`plowshare.goals.enabled`. It admits a direct user account with an existing
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
Dedicated instances, the launch skill, standing wakes and retained dependency
waits are target capabilities described above.
