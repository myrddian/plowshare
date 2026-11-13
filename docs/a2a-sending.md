# Send A2A work over Relay

Run the SDK-only [outbound Relay adapter](a2a-relay-outbound.md). It translates
explicit SEND, READ and CANCEL requests into A2A HTTP calls to configured peers.
Peer URLs, credentials, Agent Cards and the durable dispatch journal belong to
that external process. The server owns ordinary native messages and execution;
it does not operate a second external-work queue for this path.

Configure the adapter's server origin, authenticated principal, project,
request/reply topics, consumer identity, permitted producers and peer aliases
explicitly. Grant only the exact Relay topics needed by each producer and
consumer. An alias selects a configured peer; a request cannot supply an endpoint,
credential or another producer's authority. Remote descriptions are untrusted
information and never grant execution permissions.

A public SDK consumer can publish the typed dispatch body and read the adapter's
observation topic. A native Plowshare agent uses its explicit `relay_publish`
grant. To return the observation to that agent, configure a
[retained messaging export and message.return receiver](messaging-relay-exports.md)
and select `MESSAGE_RETURN` in the adapter. The server records the exact native
source and adapter/topic/generation policy before accepting a return. An accepted return
queues ordinary message work subject to native lifecycle, allowance and approval
limits; it cannot resume or finish the
source job, approve tools or claim that remote work is complete.

Retain the request UUID before publishing. A restart never repeats an uncertain
SEND or CANCEL. READ and CANCEL require an observed remote task/context belonging
to the configured peer and authenticated producer. Polling is a new explicit READ,
not automatic server execution. An UNKNOWN first send without a remote identity
cannot safely be polled or canceled using guessed IDs. Broker acknowledgement
alone is not remote cancellation or task completion.

The adapter keeps complete observations and artifact references in its private
journal. Native message and Relay body limits remain explicit; oversized results
are never truncated or split into automatic paid turns. See the outbound manual
for configuration, discovery retries, capacity, identity-preserving credential
rotation, positive-evidence publication reconciliation and supervised exits.

For receiving, use the [A2A receiving manual](a2a-receiving.md). For an old outgoing
queue deployment, follow the [legacy cutover contract](relay-legacy-cutover.md)
and its acceptance gate in the [task graph](relay-review-task-graph.md). Retain the
matching old binaries until unresolved work is safely settled; do not reuse its
old tools or SDK examples with the new transport.
