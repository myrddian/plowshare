# Legacy messaging transport retirement

This is the operator cutover contract for accepted task T24 in the
[review task graph](relay-review-task-graph.md). The graph records implementation,
independent review and actual verification evidence. This guide
does not claim a deployment or a rehearsal against production data.

## Replacement and compatibility

External consumers use the existing public SDK Relay operations. Native
`message.*` operations and the Relay envelope retain their contracts. The
registered [messaging receivers](relay-messaging-receivers.md) own admission,
inspection, cancellation and replies; [retained exports](messaging-relay-exports.md)
bind external observations to the exact original native requester. A return queues
ordinary message work. It does not resume or complete an earlier paid run.

Retirement removes `incoming.catalog/receive/status/cancel` and
`outgoing.send/status/cancel/peers/advertise/claim/report`, their SDK clients,
agent tools and generated registrations together. Old clients receive an
unsupported-operation refusal; there is no HTTP fallback or automatic replay.
Deploy clients and adapters with explicit project, topic, generation, publisher
and channel policy before retiring the old server surface.

Discovery uses `agent.list` and the external peer's Agent Card. Outbound polling is
an explicitly identified READ request, and remote cancellation is an explicitly
authorized CANCEL for an observed remote identity. A broker acknowledgement does
not cancel external work. The old server's advertisement TTL and queued-send
cancellation are retired semantics, not capabilities implicitly provided by Relay.
See the [acceptance coverage mapping](../integrations/acceptance/README.md).

Native message contexts, task IDs, source evidence, private inboxes, routes and
allowances survive retirement. Message-target schedules retain the native
admission service, firing receipt deduplication and context reuse. Historical
`message_external_*` table names do not make that native state disposable.
Historical A2A provenance must be explicitly imported and verified through the
adapter's documented migration procedure; retirement does not infer ownership or
invent a fresh admission for an old task.

## Upgrade and history

The retirement migration must acquire exclusive locks on the old outgoing tables
before checking their state. Stop old servers and adapters during this schema
cutover. Mixed old/new writers are unsupported.

Any QUEUED, DISPATCHED, WORKING, INPUT_REQUIRED, AUTH_REQUIRED or UNKNOWN work
stops the migration before archival DDL. Keep the existing version and inspect
retained evidence. Complete or cancel through that version's owning lifecycle only
when the observed remote outcome supports it. UNKNOWN is unresolved uncertainty;
absence of a remote or broker record is not proof of non-delivery. Never edit a
row to a terminal state merely to make the upgrade pass, reset a journal or replay
an uncertain mutation. If safe settlement is unavailable, defer the cutover and
design an explicit evidence-preserving recovery procedure.

Settled outgoing work and peer advertisements become immutable `retired_outgoing_*`
archives. Every original column is retained, including bodies, results, request
and remote IDs, revisions, leases and timestamps. Project names are captured while
their original references still resolve. A null name alongside a positive peer
project key means unresolved historical scope; key zero alone denotes global
scope. Archive foreign keys must not cascade, clear evidence or prevent later
deletion of an account, project or conversation. Inserts, updates, deletes and
truncation are refused. There is no active queue, core decoder or public archive
API; controlled database backup/audit remains an operator responsibility.

Back up the database and adapter journals before the cutover and retain their
matching application versions. An application-only rollback cannot restore
renamed queue tables or resurrect removed operation contracts. Roll back using a
coherent pre-cutover database/journal snapshot and matching binaries, with external
effects reconciled from retained evidence. Never restore a snapshot and assume
that already observed external effects may be repeated.

## Explicit remaining limitation

The user deferred fresh Home Assistant reads from manually started
orchestrations. Direct TURN/MESSAGE requests support fresh reads; event-triggered
workflows reuse supplied authorized evidence. A manually started orchestration
without that evidence must report the limitation. A future SDK tool-provider
migration is separate work; this cutover does not widen native export source kinds.

## Verification evidence

Acceptance requires independent review, actual SDK/A2A request and return loops,
selected PostgreSQL archive/preflight and native identity/schedule regressions,
generated contract checks and the repository/SDK/package gates. Synthetic
fixtures establish those local contracts. A real deployment-data-copy rehearsal
is separate and remains unperformed without an authorized sanitized copy.
