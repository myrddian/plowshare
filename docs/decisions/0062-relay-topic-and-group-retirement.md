# Relay topic and SDK group retirement

Status: implemented for Slice 4 of [Finishing Relay](../relay-finishing.md),
13 November 2026. Follows the messaging ownership decision in
[0061](0061-relay-agent-inboxes-and-messaging-cutover.md). This is general broker
lifecycle functionality; it does not implement the A2A rewrite.

## Owner-requested topic retirement

`RelayRetirementRepository.retireOwned` is an internal persistence capability.
It is not an authentication bypass or a public topic-delete operation. The
owning domain must hold its binding/lifecycle lock in the enclosing transaction
and supply its exact subscription. Publication and retirement take locks in the
same order: owning binding, topic, subscription/dependent state.

Retirement waits for normal publication retention; it never forces expiry. It
removes the owner's subscription only when its cursor has caught up and no
admission, native consumer, approval recovery record or live consumer/SDK batch
protects it. Other subscriptions keep the topic alive. The topic is removed only
when it has neither publications nor subscriptions. Absence means retirement is
already complete. Database failures propagate and the enclosing transaction
rolls back subscription, topic and owning-binding removal together.

This does not turn all empty topics into garbage. Unowned topics remain under
explicit operator control. `REMOVE_TOPIC` retains its inspected-generation and
empty-topic guards; `REMOVE_SUBSCRIPTION` retains its operator-managed namespace.
SDK and native groups cannot be removed by pretending to be such a subscription.

Messaging discovers at most 32 retired bindings per pass through a partial index
and numeric project/topic keyset. Under the binding lock it rechecks the retired
marker, requests broker retirement and forgets the binding only once the topic
is absent. A concurrent restored-definition send either wins that check or
waits for cleanup and registers its normal binding afterward. If the topic was
physically removed, registration gives it and its subscriptions fresh UUID
incarnations. Retained message bodies, route IDs, instances, allowance claims and
paid execution evidence are independent records and survive. Cleanup never
replays a handled message or changes `message.*`.

## Public SDK group deletion

The new WebSocket operation is `relay.group.delete`:

```json
{"project":"research","topic":"collector.inbox","group":"collector","generation":"11111111-1111-1111-1111-111111111111"}
```

Use the actual UUID returned as `generation` by `relay.consume`, including EMPTY
or BUSY results. `relay.log`'s `sdk.collector` subscriber also exposes it, but its
bounded subscriber list is not required for deletion. Consumption reuses the
already authorized cursor snapshot and performs no extra generation query. It is the
subscription incarnation, not the topic UUID, batch UUID or decimal batch fence.
All four fields are required; unknown fields, invalid names and noncanonical
UUIDs fail before business logic. SDK contracts and validators are updated
together. The result echoes those coordinates and includes `removed`: true for
this deletion, false if the group is already absent from an existing topic.

The account needs current project Contributor authority, Personal ownership when
applicable, and an exact deployment EGRESS grant for the topic and group. A
dynamic tool-provider grant is insufficient; tool groups retain their own owning
lifecycle. The operation can address only `sdk.<group>`, never a reader, routing
or built-in subscription. Accounts sharing an explicitly granted group share
that group's lifecycle; the last batch's account is not a permanent owner.

Deletion locks the topic, verifies the inspected subscription generation, and
refuses unread publications, unacknowledged retention gaps, live batches, live
worker leases and retained work/recovery records. There is no offset reset or
implicit acknowledgement. Consume and acknowledge the intended records, or
explicitly inspect and acknowledge a retention gap, before retiring the group.
Deletion does not claim that external effects succeeded.

The generation comparison fences a delayed deletion against a recreated group.
Old batch UUID/fence pairs cannot acknowledge a successor's batch. An in-flight
long poll retains its initial subscription UUID internally; after deletion it
fails instead of committing a new cursor. Ordinary explicit consumption can
register a fresh group using its requested start policy. No consumer identity or Relay envelope field is added. Consumption's optional
`generation` result field is populated by this server; older result DTOs that
omit it or carry null remain decodable. Legacy Java Batch constructors are retained.

SDKs validate successful results against the requested project, topic, group and
generation. Refusal and uncertain delivery do not trigger automatic replay.
After uncertainty, use `relay.group.inspect` with project/topic/group. It returns
an exact current `exists`/nullable `generation` snapshot, including when the topic
is absent, without creating a cursor or lease. It uses the same live EGRESS and
project authority as consumption/deletion. This one-row lookup is independent of
bounded log pages and does not fetch publications. Results remain observations,
not authorization leases or proof of external effects. There is no separate
deletion receipt ledger: the inspected incarnation is the mutation precondition,
and an absent group is the reconciliation result.

## Indexed retention discovery

The old candidate query joined every live topic to its oldest publication,
derived expiry across tables, combined age and count pressure with an `OR`, and
sorted the result. Short-lived topic churn therefore imposed work on later
sweeps even when few topics were due.

V245 backfills `oldest_published_at` and `prune_at` once from retained prefixes.
The broker maintains both under the existing topic lock:

- The first append to an empty retained suffix sets its oldest publication and
  deadline. Subsequent appends preserve those values.
- A policy change recomputes the deadline from that retained oldest timestamp.
- Prefix pruning fetches the next oldest timestamp once and updates the expiry
  boundary and deadline together. An empty suffix has no deadline.
- A stored generated `over_record_limit` flag follows the existing counters and
  record cap, including policy changes and rollback.

Discovery makes two bounded metadata reads: the partial `prune_at` index for age
expiry and a partial oldest-publication index for count pressure. It merges and
deduplicates those candidates, ordered by due time and scope/name, to the existing
100-topic bound. Neither query probes publications or reads payload bodies.
Pruning remains a bounded contiguous-prefix operation under a fresh topic lock;
candidate snapshots are hints. A topic removed after discovery yields zero
pruned rows, rather than aborting the sweep. Concurrent sweepers retain the same
row-lock correctness; this decision adds no cluster leader or dispatch retry.

The migration is append-only. New metadata is internal; topic inspection,
publication envelopes, cursor acknowledgement, retention loss and payload
reachability contracts retain their existing shapes and meaning.

## Verification and measurement

Mocked tests cover strict conversion, live grants, result-coordinate validation,
waiting-poll fencing and restored-owner cleanup. Shared generated fixtures cover
Python, Go and .NET input/result validation and foreign-generation correlation;
TypeScript and Java cover the same boundary and no-replay behavior.

Targeted PostgreSQL tests cover populated migration backfill, policy/append/prune
deadline maintenance, rollback, deletion racing publication, stale deletion and
acknowledgement tokens, deletion beyond bounded inspection pages, retained uncertain
input, live leases, other groups and
messaging cleanup/restoration. The real WebSocket fixture binds a fixture account and consumes, acknowledges and
deletes an SDK group through port validation and the actual repositories. It does
not exercise production login; application startup and authenticated ingress have
separate compatibility tests.

A local PostgreSQL 16 fixture with 5,001 topics, two publications and one due
topic used index-only scans for both candidate queries. Each returned one row
with two shared-buffer hits; observed execution times were 0.012 ms and 0.010 ms.
These are database query measurements from one test run, excluding transport,
queueing and application work. They establish the indexed query path at that
cardinality, not production latency or a comparison with the earlier 600,000-topic
measurement. No live model inference is required for these checks.
