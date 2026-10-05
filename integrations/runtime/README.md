# External integration runtime

This Java 21 executable connects external adapters to Plowshare's existing SDK.
It owns binding configuration, optional JavaScript mappings, a private journal,
outgoing requests and orchestration starts/results. Adapters own external protocols,
credentials, states, actions and subscriptions. Plowshare project agents and
orchestrations perform inference. No server, protocol, SDK or database change is
required.

The first packaged consumer is [Home Assistant](../home-assistant/README.md).
Build that distribution to include its adapter. The generic runtime distribution
alone contains no adapter implementations.

## Bindings and effects

Configuration version 1 names the Plowshare origin, a credential environment
variable, private journal directory and one to 32 bindings. Each binding fixes its
adapter, project, outgoing peer, adapter configuration, routes and optional local
`script` file. Unknown configuration fields refuse. Each project/peer pair must be
unique; reachable adapters come from optional `allowBindings`, which defaults to
self and can include only bindings in the same project. Credentials remain in the
parent runtime and never enter the script worker.

The runtime accepts one structured part under existing outgoing work:

```json
{"parts":[{"data":{"schema":"plowshare-integration/1","binding":"house","operation":"states.read","arguments":{"entities":["office.temperature"]}}}]}
```

Operations are `states.read` and `actions.execute`. Adapters validate their arguments
before execution. Results use existing COMPLETED, FAILED, REJECTED or UNKNOWN
reports, with no invented external task. Existing project tools are sufficient:
outgoing_peers, outgoing_send and outgoing_read. Selected subscriptions are operator
configuration. The runtime never uses the global event.fire operation.

## JavaScript version 1

A registered file under the configuration directory exports a default handler object:

```js
export default {
  onEvent(event, ctx) {
    if (!['state_changed', 'threshold.held'].includes(event.type) || event.resync) return [];
    ctx.state.observations = (ctx.state.observations ?? 0) + 1;
    return [ctx.startPipeline('office_heat', { evidence: event }, { key: 'heat' })];
  },
  onCompletion(run, ctx) {
    if (run.state !== 'completed') return [];
    return [ctx.executeAction('house', 'send_report', {
      message: run.reportText.slice(0, 4096),
    }, { key: 'notify' })];
  },
};
```

Handlers return an array synchronously; omitted handlers return no effects.
`ctx.state` is explicit per-binding state: at most 256 named primitive variables
(strings, finite bounded numbers and booleans). Nested objects, arrays and null
variables require a registered state DTO and are refused. Strings preserve text
with no NUL and are bounded to 65,536 characters; numbers have at most 36 digits
of precision and a scale from -18 to 18. The 64 KiB persisted binding-state limit
also applies. `ctx.readStates(binding, aliases)` and
`ctx.getRunStatus(id)` read captured snapshots of reachable bindings and recorded
runs. They perform no I/O. Snapshots expose availability, source/observation time
and adapter epoch. A handler must decide whether cached evidence is fresh enough.

Pipeline input must be a validated event, `{evidence: event}`, or
`{reading: ctx.readStates(binding, aliases)}`. Arbitrary property bags are refused.

`ctx.startPipeline(route, input, {key})`,
`ctx.executeAction(binding, action, parameters, {key})` and
`ctx.read(binding, aliases, {key})` construct effect descriptions. A fresh read
returns a subsequent onEvent observation with `type: "read.result"` and `result`;
read errors retain their diagnostic. Guard that event type to avoid read loops.
Each key must be unique within the invocation. Starting a pipeline still requires
the configured route's threshold edge, held duration, unit and cooldown. Scripts cannot choose
the project, executor, orchestration definition, endpoint, credentials or arbitrary
device targets. Completion observations use completed, failed, capped or cancelled.

Before evaluating a handler the runtime records its event, captured context, state
and evaluation time. It validates all effects before atomically committing plans
and state; failed handlers commit neither effects nor script state. A failed source
handler cancels pending held intervals as a separate continuity safeguard. Recovery dispatches
committed plans without rerunning code. Pending work with a changed configuration
is refused. Already-dispatched actions retain UNKNOWN; accepted starts can still be
recovered without launching a new run. Binding fingerprints pin configuration and
script source; configuration changes take effect on process restart.

Each invocation runs in a fresh credential-free JVM/GraalJS worker. Host classes,
filesystem/network I/O, environment, native access, package installation and guest
thread/process creation are unavailable. The parent terminates workers after five
seconds wall time or reported CPU time. The child uses a 128 MiB Java heap and
16 MiB direct-memory limit; these are JVM limits, not an OS-wide process memory
quota. Scripts are at most 128 KiB, worker input/output 256 KiB, effects 32 per
handler and persisted binding state 64 KiB. External imports are refused.

## Held thresholds

An optional `holdSeconds` on a threshold route delays eligibility until its selected
reading stays above the threshold for that observed interval. It defaults to zero
(the existing immediate edge); supplied values must be integral seconds from zero
to 86,400 and require `entity` and `above`:

```json
{"entity":"office.temperature","above":28,"unit":"°C","holdSeconds":300,"cooldownSeconds":900}
```

Merge this fragment into a complete route. A live crossing arms the interval when
the runtime processes it, so time spent waiting in the input queue does not count.
Repeated high readings do not extend the deadline. At expiry, the runtime checks the
selected cached reading's availability, unit and adapter epoch, then journals one
`onEvent` callback with `type: "threshold.held"`, `route`, `heldSeconds`, and
`heldSince` (epoch seconds). It can fire without another sensor update. The callback
uses the latest captured reading; scripts must handle this event type if they want
held starts. Early `startPipeline` plans are suppressed by the route policy.

Low readings, invalid/missing evidence, changed units/epochs, resync, connection gaps,
clock rollback and failed source handlers cancel pending intervals. Restart cancels
unfinished intervals and queued timers; observations captured or queued in an older
runtime session cannot arm a new interval. Startup/reconnect high snapshots establish
high state without arming a timer; a new live crossing is required. Downtime never
counts toward a hold. Cooldowns survive these resets; crossings during cooldown do
not defer a start until cooldown expiry.

Queued source events take priority over timers, including events received while a
snapshot is being captured. Timer admission and consumption are atomic: capacity
failure leaves the interval pending. Once admitted, a callback is consumed even if
the script returns no start or fails. It cannot repeat after duplicate IDs expire.
The journal protects the originating input while its interval is pending, then the
callback/effect lineage through completion. Already committed starts retain their
existing receipt recovery across restart.

Adapters supporting held routes must expose an alias-to-reading snapshot with
availability, numeric state/unit and a nonblank epoch, mark cached readings stale
on gaps and change epoch on reconnect. HA supplies this contract. An unchanged
reading may remain current while its selected subscription is connected. A hold
describes the available stream evidence; it does not independently measure a
physical condition between sensor reports.

## Causal feedback policy

Feedback suppression is disabled by default. Opt in on each selected binding:

```json
{"feedback":{"suppressPipelineStarts":true,"windowSeconds":300,"maxDepth":3}}
```

The boolean is required; the window defaults to 300 integral seconds and must be
from 1 to 86,400. `maxDepth` defaults to 1 (exact IDs and direct parents), and
accepts integral values from 1 to 16. Values above 1 retain observed parent links
for longer automation chains. For a completed configured action, the runtime records
a nonblank
`result.context.id` (at most 128 characters) atomically with its result, before
outgoing reporting. It matches that ID against a later observation's `context.id`
or `context.parent_id`, within the same binding and configuration fingerprint.
For example, after observing `child -> acknowledged action` and then
`grandchild -> child`, a depth limit of 2 or more can suppress the grandchild.
Each link and its observation classification are captured atomically before JS.
All retained parents must still lead to the same unambiguous acknowledged root.
HA already supplies these filtered context fields. Unknown/failed actions, missing
intermediate links, ancestry beyond the limit, timing alone and unrelated bindings
do not establish a match. Missing/invalid child IDs can match a known parent but
cannot teach a further link. At the default depth of 1, child links are not stored.
If independent acknowledgments reuse an ID or observed parents contradict retained
lineage, that ID becomes ambiguous and correlation through its descendants is
disabled. Completed action results and observations are retained.

Matching observations continue through `onEvent`. Scripts receive `event.causality`
with the recorded operation identity, matched context, relation, acknowledged
`rootContext`, `depth` (0 for the root), expiry time in epoch seconds and
`pipelineStartsSuppressed: true`. The match is captured before evaluation
and survives handler recovery. The route policy suppresses every `startPipeline`
plan from that handler, including unconditional routes. It updates threshold state
and cancels pending held intervals for the matching alias. Held callbacks also check
the captured expiry reading's context. A later fresh crossing can start normally.
Reads, verification observations, script state updates and permitted actions remain
available; scripts must handle any action-to-action feedback they create.

Correlation identifies an acknowledged action's context; it does not prove its
physical outcome. Lost acknowledgments cannot be correlated by this policy. Results
and action uncertainty retain their existing recovery rules and are never replayed.
Changing binding configuration invalidates prior matches. Expiration is measured
from acknowledgment recording and is not extended by echoes or new descendant
links. Events whose intermediate contexts were never observed cannot reconstruct
ancestry; delivery order and gaps therefore limit correlation.

The private index survives restart and payload compaction, with at most 64 retained
contexts per binding and 256 overall, including learned descendants, within the
journal's 8 MiB limit. Expired contexts are removed independently of automatic payload retention. Unexpired
contexts are never evicted for space: with suppression enabled, a full index refuses
an additional action before submission. This conservatively reserves room even for
actions that may return no usable context. If a new observed link would exceed
capacity, planning stops before JS and preserves the uncaptured queued observation
and existing index; inspection/restart after expiry can resume it. Matching known
links requires no additional capacity. Choose the window and depth for your rate;
old configuration entries occupy capacity until their original expiry. `--inspect`
reports `causalContexts` and any context-reuse diagnostic without exposing IDs.

## Journal and recovery

Use a private persistent directory outside the source tree. A file lock admits one
owner. Atomic snapshots and fsync retain observations, effects, exact start
payloads/UUIDs, state and dispatch evidence. The launcher binds recovery to a hash
of the Plowshare origin and credential; changing either refuses journal reuse.
Inspect existing work before a credential rotation and deliberately migrate recovery
state; starting with a new empty journal does not recover old work.

The journal allows 1,024 full entries, 8,192 retained duplicate identities and 8 MiB
overall; it stops intake when any bound is reached. Automatic retention and
coalescing are disabled by default. For frequent sensors, explicitly configure a
root retention policy and an optional queue policy on each binding:

```json
{
  "retention": {"settledSeconds": 3600, "dedupeSeconds": 604800},
  "bindings": {
    "house": {
      "queue": {"coalesce": "same-state", "maxPendingEvents": 128}
    }
  }
}
```

This is a fragment to merge into a complete configuration. Retention compacts a
whole invocation lineage after every record has settled and remained settled for
`settledSeconds`. It preserves per-binding script/route state, including cooldowns.
Pending work, active held intervals, UNKNOWN outcomes and their connected lineage remain in full.
Old journals without timestamps start their age window on first maintenance.
Durations are integral seconds up to one year; `settledSeconds` may be zero,
and `dedupeSeconds` must be positive.

Compacted or coalesced inputs retain their IDs and payload hashes for
`dedupeSeconds` after compaction. During that window, duplicates are ignored and
an ID with a changed payload refuses intake. After expiration, an old ID may be
accepted as new work. Choose this window for your expected replay delay and event
volume; every compacted record uses an index slot, and the runtime never evicts an
unexpired identity to make space. The bound can stop intake before the selected
duration expires. Persistent binding state remains subject to its separate limit.

`same-state` combines only adjacent queued available `state_changed` inputs with
identical alias, state, unit, attributes, context and adapter epoch. It ignores
only `last_updated`, `last_changed` and `observed_at` timestamps, keeping the newest
input. A changed reading, resync, gap, context, epoch, another alias or captured
handler prevents coalescing. Scripts receive `coalescedCount` (superseded inputs)
and `coalescedSince` (first queued time in epoch milliseconds) on the kept event.
Handlers that need every timestamp should keep `coalesce: "none"`. Coalescing
requires explicit retention so duplicate IDs have a configured lifetime.

`maxPendingEvents` bounds queued adapter events per binding from 1 to 1,024. An
omitted queue keeps the previous 1,024 limit; a supplied queue defaults to 128 and
`coalesce: "none"`. Overflow stops intake without discarding transitions, including
when many distinct readings cannot be combined. Completion/read callbacks also
consume the global journal limit. Inspect status without printing observation data:

```sh
plowshare-integration-home-assistant --inspect /path/to/config.json
plowshare-integration-home-assistant --prune /path/to/config.json 'settled-entry-id'
```

Stop the runtime before these commands; they acquire the same lock. Only DONE,
FAILED and REJECTED entries may be pruned. UNKNOWN, pending runs and every record
connected to unfinished effects remain. Inspect includes full-record and retained-ID
counts. Manual pruning removes duplicate history without creating tombstones; keep entries
for the replay/recovery window you need. A retained effect identity cannot be
overwritten by replanning its pruned handler.

Orchestration response loss recovers with the same UUID through orchestration.receipt,
then identical start submission if absent. An interrupted or unacknowledged external
action is UNKNOWN and never automatically resent. HA acceptance and observed physical
outcomes are different evidence.

Initial outgoing claims are tied to an SDK session and cannot be reclaimed by a
replacement session. After a lost report the runtime accepts an exactly matching
durable terminal result. Otherwise it stops with a stranded claim for inspection.
There is no automatic SDK reconnection/replay. HA transport reconnects within its
adapter, with fresh state and a gap observation.

## Implement another adapter

Implement IntegrationAdapter with lifecycle, pure argument validation, selected
snapshot, execute and normalized observations. Register an AdapterFactory using
META-INF/services/io.aeyer.plowshare.integrations.AdapterFactory in an explicitly
installed module. Keep SDK delivery and JS mapping logic in this shared runtime.
The tests exercise a second fake adapter and both permitted and refused routing.

Verify with:

```sh
./gradlew :plowshare-integrations:check :plowshare-integration-home-assistant:check
```

## Typed boundaries and journal compatibility

Installed factories decode their configuration into explicit adapter-owned DTOs
before credentials, connections or listeners are created. The adapter API takes
validated operation arguments and returns immutable readings, event families and
result DTOs. Runtime routing, threshold and feedback policies, journal contracts
and the public SDK gateway contain no raw JSON payloads. JSON remains inside the
configuration, vendor protocol, journal and worker-pipe codecs.

The retained event families are selected state changes, connection gaps, held
threshold callbacks, read results and orchestration completions. Script plans
contain only pipeline starts, configured actions and selected reads. The existing
version-1 journal fields and completion event format are retained. Missing legacy
route flags/causal depth are interpreted only according to their declared defaults;
wrong types, unsupported fields/families and raw nested script state refuse.

Before upgrading, stop the old runtime and back up its private journal. Configure
a restored copy and run `plowshare-integrations --preflight CONFIG.json`. This
requires an existing journal file, acquires its single-owner lock, validates all
retained entries, binding state and causal links, and opens no transports. It does
not rewrite, prune, replay or quarantine records. Runtime startup and each recovery
pass use the same validation gate before effects. A rejection requires an explicit
DTO for the retained shape before cutover; do not delete queued or uncertain work.
The supplied tests exercise synthetic journals; an actual deployment journal has
not been certified by them.
