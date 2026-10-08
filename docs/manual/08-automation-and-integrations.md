# Automation and integrations

The [Network Privacy Watch Application](../../integrations/network-privacy/README.md)
shows a complete SDK consumer with its own web interface. A project schedule
publishes collection occurrences through Relay; Python retains bounded network
observations in the project information store and publishes completion. Relay
starts an agent investigation for a baseline or changed evidence. The collector
runs wherever its explicitly configured network scope is reachable, independently
of whether Plowshare and inference run locally or in the cloud. The
[named tool façade](../relay-tools.md) lets agents call external capabilities
through equivalent Java, TypeScript/Node, Python, Go and .NET SDK interfaces.

## Recurring work on the server

Schedules, triggers, events, firings and inbox deliveries connect ongoing work to
the same server projects and accounts. A schedule describes timing; a trigger
defines what an emitted event starts; a firing records an occurrence and its
outcome. A scheduled assistant still needs its effective definition, permissions
and model allowance.

Use [schedule definitions](../scheduling.md) to save a monitored project JSON file
from desktop, TUI or CLI. Files can run agents, explicit granted skills or
orchestrations, and route to inboxes, conversations or existing message destinations.
The CLI exposes `schedule save`, `schedule sync` and `schedule files`; legacy
schedule/trigger define operations remain available. Validate requests offline first. A newly defined trigger is active immediately; inspect its list and
use pause deliberately. List and pause controls let you stop future
starts without erasing earlier firings. An event source should provide bounded,
well-defined data and retain correlation so repeated delivery does not become
uncontrolled repeated work.

Unattended work cannot assume a person is present to answer every approval. Run
under a configured account and project and choose an explicit permitted command
policy. A human-required operation can remain refused or awaiting rather than
being silently granted because a schedule started it.

The [hook guide's event walkthrough](12-hooks.md#react-to-a-named-event) shows a
complete trigger definition, event emission and lifecycle notification. Application
event names start work through triggers; hook callback keys describe seams in that
work, rather than additional event subscriptions.

## SDKs

SDK clients reach Plowshare operations over the authenticated WebSocket contract.
The Java SDK and the JS/TS, Python, C# and Go surfaces have build/distribution and
conformance instructions in the SDK guide. Choose a supported operation and its
typed request/response, not a custom HTTP route added by guessing server internals.

A connector should map the external user's authority, project, input and reply
destination explicitly. Keep credentials in private configuration, separate
external caller identity from the Plowshare account, and retain durable work
handles. Discord or another chat transport could be an adapter, but this repository
does not thereby ship that connector or its identity/permission policy.

## A2A receiving

The separate A2A adapter publishes an Agent Card and exposes its configured
receiver. It selects an existing Plowshare project and exported agent; a
general-purpose receiving agent can use its granted specialists, skills and
workflows. External callers authenticate using adapter-configured credentials.
The adapter's Plowshare account password is not the external caller token.

Inbound messages create durable external contexts/tasks through SDK incoming
operations. Those operations bind the external sender and recipient to internal
messaging instances and bounded handling. Skill or workflow selection remains an
explicit advertised command, with the receiving agent's grants and supported
context modes. A client cannot gain extra tools by asserting another agent name.

Read the receiving manual for exact A2A operations, supported parts, waiting,
polling/cancellation, bearer configuration and command extensions. It is a
separate adapter process, not a hook that forwards arbitrary board messages.

## A2A sending and outgoing work

Outbound work has its own durable ledger, claim fencing, task/status correlation
and cancellation protocol. The adapter maps an authorized outgoing request to the
configured remote peer, then reports results through the SDK. It is distinct from
inbound handling and from direct internal messaging.

Retain the intended request UUID, outgoing work ID and actual remote task reference.
Lost acknowledgments create uncertainty; inventing a new request ID can repeat an
external effect. Poll the existing work and use its recovery contract. Never treat
a locally failed transport as proof that the remote task was not started.

## Shared integration runtime

`plowshare-integrations` provides selected external bindings, a durable local
journal, bounded queues, declarative routes and optional bounded JavaScript
handlers. Adapters produce structured observations. Routing policies decide when
an observation may start a pipeline; effects use explicit configured actions and
server-owned work identities.

The runtime's journal and queue limits are deliberate admission boundaries. It
stops intake at those bounds rather than discarding state transitions. Equivalent
queued sensor readings can be coalesced only under configured policies. Gaps,
resync observations and threshold transitions have different meaning and must
remain distinct. Read its manual for retention windows and restart recovery.

## Home Assistant

The Home Assistant binding uses selected entities from HA's authenticated
WebSocket state stream. It subscribes before reading the snapshot, reconciles
bounded buffered updates and emits selected aliases. Startup/reconnect snapshots
establish state without pretending historical crossings just happened. The
implementation is not an unrestricted custom-event or arbitrary HA API bridge.

An authorized action fixes its service, target entities and parameter schema in
configuration. Model arguments cannot override the entity/device/area target.
Scenes and notifications use the configured service capability. Acknowledgment
means the service call was accepted; it is not proof that the physical world
reached the desired state. A lost acknowledgment is `UNKNOWN` and is not
automatically resent.

A held threshold requires an observed interval above the configured threshold.
Gaps, unavailable evidence, changed epochs, low readings and restart cancel the
pending interval. Downtime does not count toward the hold. Optional causal
feedback suppression uses observed HA context lineage within configured depth
and time bounds so acknowledged actions need not trigger another investigation.
The observation remains available for verification; correlation does not prove
physical success.

Use the HA manual and examples to configure selected entities/actions, project
membership, the coordinator agent and pipelines. The adapter never needs broad
agent filesystem access merely to read a selected sensor.
