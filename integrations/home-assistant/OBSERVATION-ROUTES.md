# Selected observations and workflow routes

The adapter's existing `IntegrationAdapter.Observation` boundary emits only configured
selected state changes and explicit continuity gaps. The shared integration runtime
owns workflow admission, cooldowns, held conditions, retention and causal correlation.
There is no second scheduler, event log or inference runtime in the HA adapter.

A declarative route can now match an exact state instead of a numeric threshold:

```json
{
  "entity": "front_door",
  "whenState": "on",
  "holdSeconds": 30,
  "cooldownSeconds": 300,
  "definition": "investigate_open_door",
  "agent": "house_coordinator",
  "request": "Explain the captured door observation."
}
```

`entity` is a configured readable alias; observations must also be selected by the
binding's subscriptions. `whenState` is bounded, nonblank text compared exactly,
including case and whitespace. It is mutually exclusive with `above` and `unit`.
Existing numeric routes and constructor signatures retain their behavior. A route
with neither condition still needs an explicit script. Script-produced workflow
starts remain subject to the route's condition and cooldown; a script cannot make
a selected state or a discovery description an execution grant.

Both condition types use the existing state machine. Startup/reconnect snapshots
establish the current condition without launching historical work. A new live
nonmatching-to-matching edge can start work after the cooldown, or arm a configured
hold. The current selected evidence must still match when the hold expires.
Gaps, resynchronization, unavailable evidence, changed connection epochs and runtime
restart cancel pending intervals. Downtime does not count toward an observed hold.
The retained `threshold.held` event name and `high` state field are compatibility
names for the same matching-condition state; they do not introduce a second timer.

The runtime retains captured evidence and the original orchestration start identity.
Workflow starts use the public SDK and existing authority. A running ORCHESTRATION
source still cannot issue a fresh external export and wait for a remote result;
these routes use their captured authorized input. This change adds no remote wait
capability and no prompt-dependent Conversation or AI Task provider.

## Retention and causal feedback

The existing optional `same-state` queue policy combines only equivalent selected
readings. Matching/nonmatching transitions, gaps and resynchronization remain
distinct. At queue or journal capacity, intake stops and retained transitions remain
available for inspection; the runtime does not evict unfinished work to admit more.
Configure finite retention and the duplicate-ID window for the expected event rate.

For actions owned by this integration runtime, the existing feedback policy durably
records a valid acknowledgement's bounded HA context together with the action result.
Matching context/observed ancestry can suppress workflow starts and cancel held
work, while the selected event remains available to a script as evidence. Context
correlation is not physical verification. Missing, ambiguous, expired or unknown
action evidence does not justify a causal match, and UNKNOWN actions are never
automatically resent.

The separate named SDK control provider has its own authoritative SDK execution
journal. Its receipts preserve HA context for explicit correlation, but they are
not automatically inserted into the legacy runtime's feedback index. Do not compose
workflow starts over named-control effects assuming cross-host suppression. A safe
future bridge needs one bounded effect/planning owner, pre-effect index capacity,
and durable known-acknowledgement intake into the existing journal; an after-the-fact
callback alone can race an event that arrived before the acknowledgement. No such
bridge or additional journal is introduced here. Operators must keep those workflow
sources independent or use the existing runtime-owned action path when relying on
its causal feedback policy. This is a supported-composition limit, not a claim of
universal loop prevention.

## Inference endpoint recursion

Named device controls refuse profiles for `conversation.process`,
`ai_task.generate_data` and `ai_task.generate_image`, including explicitly configured
aliases. These services can select this package's own Plowshare-backed entities
through parameters or HA defaults, so a fixed target does not establish a safe
destination. There is no override until a typed destination/ancestry contract can
identify and fence deliberate cross-agent composition. Device controls remain
separate from model/task invocation.

Read discovery can still describe a private configured profile; that description
does not authorize named execution. The legacy manual alias schema is unchanged
and remains explicit operator composition. Operators own any deliberate calls
through that separate path; this named-control restriction does not promise to
detect arbitrary HA automations, scripts or indirect service chains.
