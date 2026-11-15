# ADR 0072: Application inbox ownership and Manager attention

Status: accepted. The user authorized the platform and Privacy Watch changes,
then specified application-owned routine output, inspectable application inboxes
and attention delivery to current RBAC Managers. Relay-only schedules were
rejected in favor of retaining the existing schedule execution path.

## Ownership and configuration

A root Application opts into project-owned background output through its manifest:

```json
"inbox": {
  "attentionOrchestrations": ["investigate_network", "assess_network_device"]
}
```

The bounded, unique list names successful orchestration workflows whose results
need human review. Other successful background output, including schedule start
receipts, remains in the application inbox. Failed/capped orchestration outcomes,
failed event outcomes, approval requests and human questions always require
attention. The server does not classify model prose or infer importance from words
in a message. Producers can explicitly publish `finding`, `failure` or `attention`
through the public `inbox.publish` operation.

Omitting `inbox` preserves existing behavior. The setting changes output ownership;
it does not impersonate the application executor, transfer historical source
registration, expand tool grants or change accounting. Explicit foreground
conversations retain their existing delivery path. Existing mailbox schedules,
occurrence identities, Relay intake, pause/resume and execution lifecycle remain
unchanged. A `none` target is unnecessary here: routine output must remain
inspectable rather than being discarded.

## Application and personal inboxes

Application rows carry a project foreign key; their account handle records producer
provenance. Personal list/count/read/bot-noticing queries exclude application rows.
Application output is a project-readable publication, separate from private
execution logs. A source-log reference does not grant access to the log.

`inbox.list` accepts an optional `project` selection for an application inbox.
Current project Viewers can inspect it; only current Managers can acknowledge its
shared read state through `inbox.read`. Service-token project ceilings apply.
Omitting `project` retains account-owned list/read behavior. The console exposes
application selection beside My inbox; CLI and every SDK use the same operations.

Attention delivery resolves current human Managers from live RBAC grants and the
Application boundary, including enabled-account and manifest ceilings. It does
not use the installer, a configured handle list, historical grants or server-admin
status as an implied data role. Role changes affect future alerts. Previously
published personal copies remain ordinary personal messages.

One repository transaction retains the application item and personal copies for
the selected Managers. Stable lifecycle keys prevent duplicate copies on retries;
conflicting identity reuse fails. An application acknowledgement never acknowledges
a Manager's personal copy or vice versa. Human-question settlement removes both
copies through the existing question identity. Approval socket-alert reservations
preserve unread/settlement state. Managers can answer published background
questions under current project authority without adopting the executor identity;
normal command approvals and continuation fences remain in force.

## Public producers and recovery

`inbox.publish` requires project Contributor authority and an opted-in Application.
The request supplies project, UUID, category and bounded text; it cannot choose
recipients. `inbox.receipt` reads admission under the exact project, authenticated
producer and original UUID. Another account cannot claim that receipt. The same
UUID with changed content is refused. An absent receipt does not authorize
automatic resubmission.

Privacy Watch retains a private attention outbox before sending a collector stop
alert. Each stopped episode has one UUID, and repeated stopped polls or restarts do
not create another alert. Exception messages, credentials and provider payloads
are excluded; the alert contains an adapter-owned operation and error category.
Positive receipts reconcile unknown delivery. Pending alerts remain local after
an outage; the dashboard reports unconfirmed delivery. Explicit
`send-pending-alerts` inspects receipts and submits remaining notices with their
original identities and bytes. It never repeats collection. Routine collection
publishes no personal alert.

## Compatibility, verification and rollout

V265 adds application ownership/attention columns and an application page index,
and extends the complete V74 inbox category constraint. Existing rows retain
personal ownership. It does not rewrite shipped migrations or move inbox history.
Shared TypeScript contracts and generated Java/Python/Go/.NET catalogs include
project selection, publication and exact receipt inspection.

Mock tests cover routing, current Manager selection, foreground behavior, failure
and question escalation, strict configuration and access denial. A focused
PostgreSQL test covers ownership isolation, personal unread/read exclusion,
atomic copies, idempotency, conflicting replay and receipt scoping. Python tests
cover durable intent, stopped-episode deduplication and explicit recovery. UI tests
cover selection and personal/application acknowledgement routing.

Before deployment, verify server/schema and Application/collector revisions. Stage
the compatible server, Application manifest and collector together, preserving
cron, pause state and schedule identity. Verify a routine occurrence is retained
only in the application inbox and a controlled attention event reaches current
Managers. Keep rollback artifacts; neither existing personal inbox history nor
private log ownership is rewritten.
