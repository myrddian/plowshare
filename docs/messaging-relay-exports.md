# Native requester returns for execution exports

An explicitly configured execution export creates a native messaging request before
its Relay publication commits. An authenticated adapter can return text to that
retained request through `message.return`. The server selects the original native
requester from the exact retained execution source. A payload cannot select a
conversation, instance, account, agent, tool grant or approval.

This is the T20B platform capability in [0066](decisions/0066-relay-messaging-receiver-and-review-remediation.md).
Adapter translation and consumer cutover remain separately reviewed tasks.

## Explicit policy

Configure `plowshare.messaging.exports.policies` with records containing all five
fields: `project`, `requestTopic`, `channel`, `returnTopic`, `adapterAccount`.
There are no default export destinations or principals. An empty list leaves
ordinary granted `relay_publish` behavior unchanged. Duplicate project/request
policies, duplicate return/channel routes and request/return topic loops refuse
configuration. Unknown configuration fields refuse binding.

`requestTopic` is the exact granted execution publication topic. `returnTopic`
must already be a TEXT topic in the same project, with the adapter account's exact
live ingress grant. `channel` is the trusted routing branch name selecting
`message.return`; the receiver accepts no script or instance/work target. For a
channel called `adapter-return`, the route branch is:

```javascript
return [{name: 'adapter-return', receiver: 'message.return'}];
```

The policy grants no authority by itself. Existing execution tool grants,
authenticated run ownership, project work access and Personal ownership govern
export. Return admission checks the configured adapter's live ingress grant,
current policy, the retained return-topic incarnation and the requester's current
project/Personal authority. A renamed or recreated topic does not inherit an old
return binding.

## Return text contract

Publish TEXT with exactly these fields:

```json
{"schema":"plowshare-message-return/1","requestId":"22222222-2222-2222-2222-222222222222","sourceEvent":"11111111-1111-1111-1111-111111111111","body":"Remote result text","finalReply":true}
```

Both UUIDs must be canonical. `sourceEvent` is the server-derived event UUID of
the original execution publication, not an adapter protocol ID or a model-written
request ID embedded in its body. `requestId` identifies this return mutation and
must remain stable through acknowledgment loss and reconciliation. Changed
content or scope under the same return UUID conflicts. `body` is nonblank text,
subject to the configured native `bodyBytes` limit (65,536 bytes by default).
Deployments expecting larger integration reports must explicitly choose a native
limit that fits them. Oversized returns refuse admission; their full evidence stays
in the adapter journal. The platform does not truncate, split or bypass that limit. `finalReply` closes only the exported native
request, never the source agent's execution. Remote states and artifacts must be
translated into text by the adapter; the server does not decode adapter-specific
observation envelopes here.

The source's native request, Relay publication and export binding commit together.
The return's native reply, broker inbox publication and immutable receipt also
commit together. Equal retries recover the original receipt after broker expiry;
uncertain dispatch inspection reads it without another mutation. Missing evidence
is inconclusive and does not authorize replaying an external action.

## Source and lifecycle limits

Supported sources are an exact native participant proved by its retained run/turn
and server-authored message speaker, including a participant whose configured route
retains a TURN conversation, or an explicit account-owned TURN caller. A missing
or historical speaker is unavailable evidence and is not guessed.
Sharing a conversation or naming the same definition is insufficient. Unsupported
origins, including an approval source without exact message ownership evidence,
refuse the configured export before broker append. Unrelated publication topics
retain their ordinary behavior.

The native exported request has a passive external participant and owes no paid
wake. A returned message uses the ordinary native correlated reply lifecycle.
The source turn can finish first. A persistent participant or active caller can
then receive a normal reply wake. A stopped task, inactive instance, archived
conversation or revoked requester cannot be revived by a late result. Existing
AWAITING and approval barriers remain authoritative; a result does not infer a
wait-for-export continuation or approve work.

Native cancellation settles the local exported request and prevents later return
admission. It does not cancel an external effect. An explicit remote CANCEL is a
separate adapter request with its own durable external-effect evidence.

Export bindings retain the server-owned causal snapshot. The authenticated return
event advances that snapshot within the existing maximum depth, even when the
original broker publication has expired. A fresh SDK root or a body assertion
cannot reset the request's effect budget. The resulting paid turn uses its own
retained turn ancestry; another definition sharing its conversation cannot replace
that root.

V252 adds export/return bindings and receipts without rewriting older migrations.
References follow native message retention and authorized project deletion. No
production data rehearsal or deployment is implied by this capability's tests.
