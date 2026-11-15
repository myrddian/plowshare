# Messaging and Relay

Read this for message instances, delivery/replies, topic publications,
subscriptions, workers, native wakes or external-work routing. Read
[AUTH](auth.md) when changing project audiences, publisher/subscriber grants,
instance identity, retained execution or read/disclosure checks.

## Mental model

Relay retains scoped publications and consumer positions; messaging adds
addressed instances, reply relationships and delivery lifecycle over that
substrate. Workers and receivers turn eligible publications into work under their
own retained authority rather than treating a broker offset as execution.

## Start in code

- [MessageService](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/messaging/MessageService.java) is the messaging capability;
  [MessageIngress](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/messaging/MessageIngress.java) and
  [RelayMessageIngress](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/messaging/RelayMessageIngress.java) own admission into Relay messaging.
- [Relay](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/Relay.java) is durable pub/sub, not an execution permit;
  [RelayOperations](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/RelayOperations.java) owns manager-authorized controls.
- [RelayDeliveries](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/RelayDeliveries.java) owns delivery state;
  [RelayReceivers](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/RelayReceivers.java) owns receiver selection.
- [RelayPorts](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/RelayPorts.java) owns SDK port sessions and
  [RelayPayloadStore](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/RelayPayloadStore.java) owns staged payload bytes.

## Rules that are easy to miss

Retained event identity deduplicates equal content only while retained; conflicting
reuse fails. An absent read-only receipt cannot prove publication never happened
and cannot authorize an automatic resend. Preserve request/event identity across
explicit recovery instead of inventing a fresh mutation.

Seen positions represent availability, not successful handling. Reads do not
advance offsets. Advance and expired-gap acknowledgement are explicit operations;
independent consumers must not share a group unless work sharing is intended.
Read-tier groups remain account-owned and distinct from work-tier execution.

Publication, subscription, worker activation and runtime authority are different
capabilities. A topic, route or wake cannot authorize a model/tool effect. Keep
message instance lifetimes, reply/final-reply identity, retained caller lineage
and delivery leases separate from broker offsets.

Recipient execution errors owe the original sender a generated status notice regardless of
`reply_expected`, under the original request's lock and terminal transition. An existing explicit
final reply stays final; a later error is one correlated generated non-final status with an actual
error ending and no reply expectation. Generated outcome handling failures never produce another
automatic error notice. Keep notices generic: no failed model/diagnostic text or private tool data,
and current participant log/input visibility still fences disclosure. A recipient failure becomes
asynchronous sender input, never an exception that stops the sending agent.

Payload staging and topic generation checks have a lock order. Use the owning
append operation rather than pre-locking a topic or converting storage errors
into a successful unavailable/empty result. Retention and topic recreation must
not silently target a replacement incarnation.

## Bounded work

Required contract: active publish/read/control/delivery units need configured
positive finite deadlines including queueing, waits, IO and result delivery. Short reads,
controls and acceptance use a 2-second default ceiling and normally finish sooner.
Payload transfer, receiver execution and external work need appropriate configured
deadlines and leases, not a 2-second cap on the entire exchange. Long-lived ports
and workers use bounded polls/renewals; parked reply waits hold no active resource.
Retries/nested calls consume the earlier remaining deadline rather than reset it.
After active expiry, cleanup has a separate positive finite configured drain budget.
On expiry stop new appends/effects, request owned cancellation and withhold late
acknowledgements/publication under current delivery/topic-generation fences.
Account physical capacity until work ends; uncertain sends keep original event
identity and receipt reconciliation without automatic replay or offset advance.
Current: [RelayDeliveryRepository](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/RelayDeliveryRepository.java) leases fence ownership;
they neither bound all receiver IO nor prove caller timeout stopped physical work.

## Legacy traps

Messaging is Relay-backed; legacy cutover tables and historical designs do not
justify a second active broker. Board/forum, agent inboxes, child completions and
external work have distinct handlers. An external instance is not an ordinary
agent loop, and an event being available is not proof a recipient ran or replied.
Read [workflows](workflows.md) for schedule, orchestration and inbox delivery.

## Checks

```sh
./gradlew :plowshare-server:test --tests 'io.aeyer.plowshare.server.messaging.RelayMessageIngressTest' --tests 'io.aeyer.plowshare.server.relay.RelayDeliveryContractTest'
./gradlew :plowshare-server:test --tests 'io.aeyer.plowshare.server.relay.ReadTierRelayConsumptionTest' --tests 'io.aeyer.plowshare.server.relay.OwnedRelaySubscriptionWorkTest'
```

Use default mocked service tests for routing and authority. PostgreSQL is justified
only for the actual append/deduplication/locking/recovery assertion. See
[Relay](../../docs/relay.md), [messaging](../../docs/agent-messaging.md) and
[messaging receivers](../../docs/relay-messaging-receivers.md); external adapters
also follow the [integration boundary](../../docs/decisions/0001-integration-boundary.md).
