# ADR 0003: SDK topic ports and model-boundary message filtering

Status: accepted. The user explicitly authorized a platform extension: generic
Relay EGRESS/INGRESS configuration, SDK topic listening, external message approval,
and basic local prompt injection security. This is separate core authorization
under [ADR 0001](0001-integration-boundary.md).

## General platform need

An external SDK application needs to consume a project topic durably and publish
application-owned text to a configured project topic. This applies to filtering,
telemetry and application protocols independently of Plowshare lifecycle notices.
An acknowledgement of delivery must remain separate from a content verdict.

Existing `relay.log` supports inspection without a consumer cursor or delivery
lease. `incoming.receive` creates integration work; outgoing work has a different
execution lifecycle. Repeated SDK log reads with an adapter-owned checkpoint
cannot atomically fence competing consumers against the server's retained stream.
Neither adapters importing server services nor direct database access satisfies
the public integration boundary.

## Decision

Add three typed WebSocket operations: `relay.publish`, `relay.consume` and
`relay.ack`. Exact deployment grants select project, topic, authenticated account,
direction and, for egress, consumer groups. Membership remains a live requirement.
Examples use JSON through the existing `SPRING_APPLICATION_JSON` deployment
source; YAML server files remain supported. Project Relay files retain their
JSON contracts and cannot grant deployment authority.
Ingress publishes the existing TEXT family, allowing opaque application JSON
without adding a new Plowshare event family. Egress supports existing topic
families. System scope is inaccessible through these ports.

The server owns cursors, leases, UUID batch tokens and monotonic fences. Locks are
ordered topic then batch, and are released before any long-poll wait. Reading
never advances the cursor. Acknowledging advances only the issued whole batch or
its inspected exact retention gap. Expired or superseded tokens cannot advance
it. Different groups are independent; consumers of one group compete. This is a
single ordered topic stream per group, without Kafka partition assignment or
exactly-once external effects. Deployment grants and cursors are independent:
removing a grant does not erase its cursor or retained data.

The server derives SDK publisher identity from authentication. A derived
publication requires egress authority and a retained parent with known, bounded
ancestry; independent ingress starts a fresh root. A port alone grants no tools,
job starts or bypass of project runtime permissions.

A model-boundary interface applies bundled MIT keyword rules, configurable
sensitive-data regex policies, and optional external input review. The external
review protocol is application JSON in configured TEXT topics. Acceptance
requires the configured authenticated reviewer, exact request identity and source
hash, and a response derived from the retained request. Review chains use a
reserved server-owned root and refuse native work and
forwarding, so routing a held review cannot recursively start filtered work.
SDK request/reply publication preserves that root. An accepted response
returns the full replacement text, which is checked again locally. A delivery ack
is never acceptance. Reviewer subjects cannot recursively filter each other in
the same project.

Review waits happen before model routing and inference capacity reservation.
They have bounded timeouts and live authority/cancellation checks. Timeout,
malformed verdict, retention loss or refusal stops the model call. Publication is
committed before waiting; uncertain transport delivery and restart never trigger
automatic mutation replay. Rejected model output retains actual usage accounting.
Configured output disclosure policies withhold answer and reasoning deltas until
inspection; the approved answer is then released as one complete text result.

## Compatibility, migration and verification

V131 adds a separate batch table referencing existing subscriptions. It changes
no shipped migration or historical publications. Old clients keep their existing
operations; new SDK peers share strict generated contracts for the added
operations. Java, TypeScript/Node, Python, Go and .NET clients are updated together.
Rolling application back leaves the additive table and external cursors intact;
disable port grants and external review before reverting code that cannot serve
them. Existing lifecycle consumers keep their separate subscriber namespace.

Mocked tests cover boundary validation, membership, configured authority,
ancestry, review identities, cancellation and withheld streaming output. Focused
PostgreSQL tests cover the migration, competing consumers, retention, rollback and
fencing. Canonical SDK fixtures cover port DTOs across generated native clients.
See [Relay](../relay.md) and [message filtering](../message-filtering.md) for
configuration, limitations and consumer recovery.
