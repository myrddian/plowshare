# Relay agent inboxes and messaging-owned records

Status: implemented for Slice 3 of [Finishing Relay](../relay-finishing.md),
13 November 2026. Implements [0057](0057-board-forum-and-relay-messaging-substrate.md)
using the [typed payload contract](0059-typed-messaging-payload-contract.md).
Relay's envelope, `message.*` operations, lifecycle notices, tool arguments and
receipts, and SDK contracts stay unchanged. The A2A rewrite remains Slice 5.

## Ownership and audience

`MessageService` owns addressed instances, routing, delivery, inspection and
handling. `RelayMessaging` consumes typed repository, allowance and inbox
interfaces. Only specialist JDBC repositories execute messaging SQL. Instances
keep their existing account, project, definition, conversation and lifetime;
creating an instance creates no board topic, seat or post. Messaging configuration,
resource properties, routing validation, tool extras, refusals and deadline upkeep
belong to `messaging/`. The dispatcher registers the distinct typed messaging and
forum wake families independently; scheduling combines domain-owned share snapshots
without re-reading messaging state to determine model fallback policy.

Private participant conversations use origin `message`, share their instance's
allowance, remain open across individual wakes and have no automatic age policy.
The migration reclassifies only former private messaging contexts; ordinary person
conversations and retained forum seats keep their origins and authority. Neither
Origin.MESSAGE nor tool composition makes a context publicly speakable or grants
access to its inputs. Public forum capture and reads do not consult the historical
`former_topic` column. Historical records retain that column solely as cutover
evidence.

One TEXT topic serves each (account, project, definition name). Messaging derives
`messaging.agent.<SHA-256(account)>.<SHA-256(definition)>` inside the destination
project scope, and registers the `messaging.inbox` subscription on first send or
receipt. Instance IDs never appear in topic naming. Definition names with several
task instances still have one inbox. Topic bodies are readable through Relay's
existing project membership policy. This deliberately publishes bodies to that
audience without granting conversation, document or tool access. `message.*`
inspection retains its account and conversation authority checks.

New requests retain current two-sided routing and access checks, including sends
to instance IDs. Replies reverse the retained request's participants and project
bindings; revoking new routing does not remove that return path. A reply cannot
choose another sender, project or recipient. The original route snapshot is
reused by publication rather than resolved a second time.

## Atomic publication and admission

The owning transaction inserts a message record, route, canonical allowance and
one `MessagePayload` TEXT publication. Publication uses the message ID as its
stable event ID and the retained posting time. Equal invocation retries recover
their existing fingerprinted record; conflicts fail. No second runtime or
automatic publication replay is introduced. Author bodies use the bounded inline
form; generated outcomes can reference their retained message body.

Address/lifecycle transitions serialize through an account coordinator before
address and request locks. This protects limits and makes opposite-direction
cross-project sends acquire shared locks consistently. It covers only short
database work, never inference. Agent bindings are also acquired in deterministic
identity order. Publication and drained-inbox retirement serialize on those
bindings.

A committed send queues a bounded, coalescing hint for its already authorized inbox
snapshot. The hint runs admission after the owning transaction commits and the
log-open callback completes; it never drains a recipient before its firing exists.
Hints accepted before worker start wait for startup recovery. Overflow, a busy
admission pass or process loss leaves durable publication debt for periodic recovery.
Each worker batch admits at most 32 hinted inboxes, and one failed inbox does not
prevent another hint from running. The worker never invokes inference directly.

Periodic discovery uses a typed numeric `(project_id, topic)` keyset and matching
active/retired partial indexes rather than a computed text sort. Discovery wraps at
the end of each bounded pass to preserve fairness. It leases each consumer group. It
validates the complete bounded batch, reads retained admission snapshots in one
repository query, fetching bodies only for inline validation rather than hydrating
retained outcome references. It verifies payload participants, thread, flags, conversation
and inline body against those records. Foreign publishers are skipped without
interpreting their carrier. A corrupt own publication or retention gap fails
visibly without acknowledging the lost position.

The admission flag, native firing and cursor advance share one transaction.
Rollback leaves the message available for a later admission attempt. The wake
stores the inbox publication position so equal timestamps cannot reorder an
instance's requests. Continuations retain their existing priority. Dispatch runs
after commit, and the worker starts only after interrupted-work recovery; `queued` includes committed publication debt awaiting admission.
The consumer never waits for model execution or a busy recipient before advancing
to another instance's publication.

## Execution and allowances

Native firings bind directly to `message_instance`, independently of Board.
Their execution target contains both conversation and instance identity. A busy
instance or an outstanding handling request still blocks its own later request.
Different definitions may execute in a shared conversation concurrently; ordinary
conversation budgets still retain their existing single-writer gate. The same
definition cannot bypass its execution gate by using another entry point.

Shared conversations reserve unique turn ordinals before writing entries or
running inference. Endings are recorded at the reserved ordinal even when turns
finish in reverse order. Interrupted reservations are never reused, so retained
entries cannot be relabelled as a later execution. Reservation failure stops the
turn rather than silently producing unnumbered evidence.

Each new message owns a canonical allowance initialized from the receiving
definition. Spending and continuation grants retain that source. Project caps
`autoIncrease` renews exhausted message calls and steps through the existing
policy; it does not raise the history conversation's allowance or reset spending.
Approval continuation adds to the same message source. Board pots keep their
independent rules. Interrupted paid execution is settled as unavailable during
recovery and is never replayed.

## One storage cutover

V239 renames messaging instance/routing tables, copies retained bodies into
`message_records`, moves message foreign keys, registers existing agents' inboxes
and publishes retained threads in their original sequence. Historical bodies use
self-references, preserving large generated bodies without truncation. IDs,
timestamps, thread links, deadlines, invocation fingerprints and approval state
survive. Already-admitted requests retain their exact firings and admission state;
queued targets are rekeyed, while paid start/job evidence is unchanged.

Migrated routes retain their original allowance IDs and claims, including legacy
shared sources; inventing separate historical spending would falsify accounting.
Only new messages receive new independent sources. Historical private board IDs
remain as `former_topic` evidence, without foreign keys to live Board topics.
Their seats, posts and topics are removed after the record transfer. Forum capture
and privacy filters continue to recognize those historical identifiers.

V240 initializes a conversation turn sequence from both recorded endings and
retained entries, then allocates future reservations atomically. Shipped
migrations are untouched. Migration fixtures use the old schema directly before
upgrading, rather than running current repository SQL against obsolete columns.

## Retirement and verification

An inbox binding is marked retired only after its subscription catches up and
all recipient requests finish, with that state rechecked under the publication
lock. Definition absence must come from an authoritative inventory; an unavailable
inventory is a failure, not absence. Reappearance reactivates the binding. Passive
external identities remain until the separately scoped adapter rewrite. Project
deletion cascades its Relay scope. Physical removal of retired topics and groups
is Slice 4 and is not smuggled into this cutover.

Verification pins the existing messaging behavior before replacement and runs the
same contract assertions afterward, adapting only persistence fixtures and
asynchronous admission barriers. Mocked coverage exercises validation, cursor
ordering, foreign publishers, gaps, definition retirement, shared-history gates
and automatic renewal. Targeted PostgreSQL coverage verifies rollback, independent
instance admission/allowances, concurrent final replies, retained-route delivery,
reverse-completion reservations and a populated pre-cutover upgrade with queued
and interrupted paid work. Default repository checks remain database-free.
