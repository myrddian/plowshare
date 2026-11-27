# Use Goal Agents

A Goal Agent takes responsibility for an objective across turns. It has its own
persistent inbox, conversation and TODO list, so you can keep working in the
conversation that launched it. Use an **ad hoc** Goal Agent for a one-off assignment.
Attach a recurring schedule or Relay listener to make it **standing**. It then
returns to idle after each completed cycle and uses the same conversation for the
next wake.

## Start a Goal Agent

The server operator must enable `plowshare.goals.enabled` in the server
configuration. It defaults to false. Use a human account with current permission
to run work in the project and an eligible exported agent with a finite model-call
allowance.

In an active project conversation, enter a command such as:

```text
/skill:goal Use goal_worker to review release-notes.md against CHANGELOG.md. Record any missing entries with file references. Finish when every discrepancy is accounted for.
```

The shipped Interlocutor can hand off to `goal_worker`, which can read workspace
files, keep TODOs and cite evidence. A custom caller needs the `goal` skill and
the exact target in its `calls` grants. The target's tools, delegates, scopes
and any orchestration grants must fit within the caller's permissions. If the
skill needs a decision about the target or objective, answer in the original
conversation to continue that handoff.

You can also launch directly from the CLI. These examples use the packaged
`plowshare` command. From a built source checkout, use `bin/plowshare-cli`.
Substitute your project, agent and returned identifiers. Give each new mutation
its own nonzero request UUID and keep it with the original arguments.

```sh
plowshare goal start --payload - <<'JSON'
{
  "project": "release-review",
  "agent": "goal_worker",
  "objective": "Review release-notes.md against CHANGELOG.md.",
  "criteria": "Record each missing entry with a file reference.",
  "requestId": "550e8400-e29b-41d4-a716-446655440001"
}
JSON
```

Keep the returned `goal` UUID, `conversation` ID and `instance` address.
Use the instance address for messages, the goal UUID for discovery and both the
goal and conversation IDs for controls. The original conversation remains
available for other work.

## Inspect work and read the result

In the browser Console, open **Work → Goal Agents**, select the project, then
choose **Inspect Goal Agent**. The entry shows its state, wake sources, next
scheduled wake or pending Relay batch, latest result and any blocker. Select
**Open conversation history and TODOs** to read the exact Goal conversation,
Trajectory and task list. History and TODOs require current read access.

```sh
plowshare goal list '{"project":"release-review","limit":10}'
plowshare goal status '{"project":"release-review","goal":"550e8400-e29b-41d4-a716-446655440010"}'
```

Use the list's `next` value as `after` for the next page. Use status for the
current version before sending a control. When an explicit completion commits,
the latest result records the summary, turn and evidence. Routine standing
cycles leave their results in the Goal conversation.

For a direct CLI launch or a person's `/skill:goal` command, the account
Mailbox gets a reference on completion, an explicit blocker or a question.
Open the Goal conversation to read the contents. If authorized to send an
[addressed message](13-messaging-and-routing.md), use `send_message` with
`to` set to the exact `ins_…` address to steer the work. Steering queues
behind an active turn and takes priority over fresh automatic wakes at the
next safe boundary.

## Answer a waiting Goal

A Goal can park while a file read awaits approval, a granted orchestration runs
or the Goal needs a reply. Resolve approvals through the usual approval controls.
For a Goal question, read the full question in its conversation and use the exact
question UUID from the Mailbox notice or waiting status. The Mailbox notice must
have been delivered before the answer is accepted.

```sh
plowshare goal answer --payload - <<'JSON'
{
  "goal": "550e8400-e29b-41d4-a716-446655440010",
  "conversation": "cnv_Example",
  "question": "550e8400-e29b-41d4-a716-446655440011",
  "requestId": "550e8400-e29b-41d4-a716-446655440002",
  "answer": "Use the 2.4 release section in CHANGELOG.md."
}
JSON
```

An accepted answer to a paused Goal waits for Resume. An ordinary addressed
message is queued as steering. If the answer reply is lost, retry the same
answer and request UUID to recover its receipt under current access.

## Make work recur on a schedule

Ask an open Goal Agent to propose a schedule, for example to review changes at
09:00 Melbourne time on weekdays. Its `goal_schedule` tool returns an object
containing a `request` for `schedule.save`. Save that inner request as
`goal-schedule.json` and have a project Manager apply it while the Goal is
open. The schedule source must execute as the account that owns the Goal inbox.

```sh
plowshare schedule save --payload - < goal-schedule.json
```

To write the request yourself, use a plain `agent` action for the Goal's
definition and a `message` target with `to` set to its exact instance address.
Cron has six fields with seconds first. This is a complete
`goal-schedule.json` for the weekday example.

```json
{
  "name": "weekday-release-review",
  "project": "release-review",
  "source": "server",
  "definition": {
    "version": 1,
    "cron": "0 0 9 * * MON-FRI",
    "zone": "Australia/Melbourne",
    "paused": false,
    "action": {
      "kind": "agent",
      "agent": "goal_worker",
      "name": null,
      "input": "Check release notes for changes since the last review.",
      "mode": null
    },
    "target": {
      "kind": "message",
      "project": null,
      "conversation": null,
      "to": "ins_Example",
      "route": null
    },
    "limits": {"maxModelCalls": null, "maxTurns": null, "queueCap": 1}
  },
  "overwrite": false
}
```

Apply the binding before the ad hoc Goal completes. Manage the timer under
**Schedules** in Console or with the
[schedule lifecycle commands](../scheduling.md#cli-and-tui).

Pausing the schedule keeps the Goal standing and stops that timer. Editing,
pausing or removing it invalidates its queued, unclaimed wakes from the previous
definition. A turn already admitted can settle.

## Wake on Relay publications

Discover eligible feeds for the exact Goal. The picker uses current access and
defaults to `TEXT` feeds. Pass `"kind":"EMPTY"` to discover eligible empty
signals. Select a topic returned by this picker for this Goal.

```sh
plowshare goal sources '{"goal":"550e8400-e29b-41d4-a716-446655440010","conversation":"cnv_Example","prefix":"release."}'
```

Copy the topic and generation from that response. This example wakes on four
publications or after five minutes from the first pending publication, whichever
comes first.

```sh
plowshare message listen --payload - <<'JSON'
{
  "goal": "550e8400-e29b-41d4-a716-446655440010",
  "conversation": "cnv_Example",
  "topic": "release.changes",
  "generation": "550e8400-e29b-41d4-a716-446655440012",
  "batchCount": 4,
  "maxDelaySeconds": 300
}
JSON
```

Keep the returned binding UUID. Omit both batch fields for one wake per event,
or supply both with `batchCount` from 2 through 16 and `maxDelaySeconds`
from 1 through 86400. A new binding starts at the current broker head. It stops
if access or the exact topic changes, or if retention leaves a gap. Inspect the
listener diagnosis and remove the failed binding before resuming. To subscribe
again, discover the current generation and create a new binding.

```sh
plowshare message unlisten --payload - <<'JSON'
{
  "goal": "550e8400-e29b-41d4-a716-446655440010",
  "conversation": "cnv_Example",
  "binding": "550e8400-e29b-41d4-a716-446655440013",
  "version": 4,
  "requestId": "550e8400-e29b-41d4-a716-446655440003"
}
JSON
```

Use the current Goal version from status. Removing the last recurring binding
ends recurring eligibility. An active cycle can settle as ad hoc and already
queued steering remains eligible. If the Goal is parked and has no steering left,
it completes.

## Pause, resume, cancel and recover receipts

Use the controls in the Console's Goal entry or the corresponding CLI commands.

| Control | What happens |
| --- | --- |
| Pause | Stops future turns at a safe boundary. Bindings, pending input and dependency waits remain available. |
| Resume | Rechecks current permissions, wake sources and allowances. A retained wait continues from its original dependency. |
| Cancel | Fences new work, disarms wakes and requests that active work stop. Committed history and results remain available to authorized readers. |

The CLI commands are `conversation goal pause`, `conversation goal resume`
and `conversation goal cancel`. Each takes `goal`, `conversation`,
the current `version` and a new `requestId`. Only Resume accepts new
limits. If the Goal reached its call allowance, `maxModelCalls` is the total
authorized model calls, including calls already spent. If the activation limit
was reached, supply a higher `maxActivations` within the server's configured maximum.

```sh
plowshare conversation goal resume --payload - <<'JSON'
{
  "goal": "550e8400-e29b-41d4-a716-446655440010",
  "conversation": "cnv_Example",
  "version": 5,
  "requestId": "550e8400-e29b-41d4-a716-446655440004",
  "maxModelCalls": 48
}
JSON
```

Automatic cycles draw from the Goal's retained allowance. By default each cycle
can use up to eight model calls within that allowance, and automatic cycle starts
are spaced at least one minute apart.

An idle standing Goal waits for input and can be quiet between cycles.
`WAITING` means a specific dependency is outstanding. For `PAUSED` or
`BLOCKED`, inspect the reason before resuming or granting more work. If the
server could not confirm an earlier effect, inspect the original conversation
and the dependency owner's receipt before taking further action.

If the start response was lost, look up the original launch by project and
request ID. For an uncertain control, look up the original control receipt by
conversation and request ID. Compare the receipt with current status before
sending a new control.

```sh
plowshare goal receipt '{"project":"release-review","requestId":"550e8400-e29b-41d4-a716-446655440001"}'
plowshare conversation goal get '{"conversation":"cnv_Example","requestId":"550e8400-e29b-41d4-a716-446655440004"}'
```

## Receive results as a bot

A persistent bot handling an authenticated native message can request a correlated
outcome if its definition grants `send_message`. From the `goal` skill it
sets `replyExpected: true` on `goal_start` and retains the native launch
message ID. On a later turn the same calling instance can use
`goal_return_read` with `{"message":"bdm_Example"}` to see related notices,
questions and the final outcome. The account operator can read the delivery
through the normal Messaging inspection commands.

To answer a question, send one explicit `send_message` reply with
`reply_to` set to the question's message ID and `final: true`. A blocker
is a non-final notice. Completion or cancellation closes the launch request.
Replies are retained for inspection on a later turn. The Goal's committed
result is available to authorized readers even if return delivery is withheld.
