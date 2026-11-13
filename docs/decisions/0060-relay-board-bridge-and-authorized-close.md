# Relay-to-board bridge, replies and project-authorized closure

Status: implemented for Slice 2 of [Finishing Relay](../relay-finishing.md),
13 November 2026. Follows [0057](0057-board-forum-and-relay-messaging-substrate.md).
This is general platform forum functionality. It does not implement the later
messaging cutover or A2A rewrite.

## Destination and request contract

The receivers `board.post`, `board.open` and `board.close` call the board's own
mutation APIs. The board retains its tables, topic locks, sequence, seats,
allowances, wakes and deepest-first tree closure. Relay retains its envelope,
payload kinds and delivery state machine.

Routes select exactly one destination:

```js
{name: 'ask', receiver: 'board.post', work: {topic: 'bdt_...'}}
{name: 'investigate', receiver: 'board.open', work: {swarm: 'research'}}
{name: 'resolve', receiver: 'board.close', work: {topic: 'bdt_...'}}
```

Use actual retained `bdt_` IDs. These receivers accept no handler, agent,
orchestration definition or project override. `board.open` selects the named
swarm in the source project; a configured post/close may target an existing
forum topic in another project. A missing/closed/private messaging topic,
exhausted post allowance or unavailable swarm refuses the branch. There is no
fallback opening. Agent execution still uses existing model and grant checks.

Requests use existing Relay TEXT with a bridge-owned object:

```json
{"schema":"plowshare-board-request/1","body":"Investigate this question.","reply_to":"collector.inbox"}
```

`body` is nonblank, at most 16,000 UTF-16 code units and 64,000 strict UTF-8 bytes.
NUL and unpaired surrogates are refused. The codec rejects unknown, missing,
duplicate or mistyped fields, unsupported versions, excessive nesting and
trailing input before business logic. Diagnostics omit source content.
`reply_to` is optional; absent/null requests no replies. If supplied, it must
name an existing TEXT topic in the source project, using Relay's normal topic
grammar. No topic is guessed or created. A later agent-facing wrapper can supply
its originating inbox; Slice 3 still owns that inbox lifecycle.

## Authority and attribution

External callers publish through the existing authenticated SDK Relay ports.
They cannot choose their publisher field. A specialist repository retains the
account-to-`sdk:<SHA-256(account)>` mapping when a port publication is admitted.
The bridge resolves that provenance and rechecks the account's current source
project/ingress grant at dispatch. Payloads and routing scripts cannot supply an
account, and the worker's identity is never substituted. Historical publishers
without retained provenance are refused, not inferred.

Posts and openings appear as person-authored messages from that publishing
account. Posting permission on the source Relay topic is sufficient to trigger
the configured forum; destination membership or a seat is not required. This
does not grant destination reads, participation tools or visibility into other
posts. Openings remain in the authorized source project, whose publishing
account becomes the ordinary board execution principal.

Closure additionally requires the publishing account's live destination-project
write permission (`ProjectMembers.mayWork`, Contributor or Manager, including
existing server-admin behavior). Personal ownership remains enforced. The
WebSocket `board.close` operation applies the same policy to its authenticated
calling account. The model `board_close` tool is offered to ordinary member
seats as well as opener seats and checks its bound execution account. An opener
seat confers no special close permission. Existing subtopic approval rules remain.

## Receipts, transactions and uncertainty

The Relay delivery UUID is the board request UUID. The existing per-account
receipt repository locks that identity and compares the full action, including
source event/scope, operation, destination, body and reply destination. An equal
delivery recovers its message receipt; changed intent is refused. The forum
mutation, receipt and reply binding commit in one transaction, before after-commit
wakes. Board locks still recheck terminal state before writing.

`board.close` takes `project`, `topic`, `body`, optional `cites` (up to 200
same-topic `bdm_` IDs) and a stable `requestId` UUID. It returns
`{requestId, topic, message}` with the closed topic and resolution. Citation
scope is resolved in one query without fetching bodies. Concurrent equal closes
return one retained resolution and committed closed state. An equal request
still checks current authorization; an unrelated close of an already closed
topic is refused.

Uncertain Relay dispatch performs only a matching receipt read. It does not
reapply a write, recheck an expired source ingress grant, or require an open
destination to recognize an already committed effect. Absence stays uncertain;
it never authorizes replay. A known transactional refusal produces a definite
failed branch. Stable receipt inspection takes precedence over a failed
after-commit callback.

## Exact disclosure and durable replies

For each request with `reply_to`, the bridge discloses only:

- Full messages replying directly to the bridged message, and their transitive
  reply descendants on that same topic, in the board's sequence order.
- Exactly one terminal resolution when that topic closes, including closure
  inherited from a parent. A close request itself receives that terminal.

Unrelated posts, mentions, private conversations, other topics and the opening
history are not copied. Authors should treat a reply to a bridged thread as a
disclosure to the source Relay audience. The resolution is also disclosed even
when it is not a reply descendant. Existing lifecycle reference notices remain
unchanged. Source-project readers may read replies under Relay's existing scope
policy; naming a reply topic does not make it account-private.

Replies use TEXT objects with `schema: "plowshare-board-reply/1"`,
`kind: "post" | "terminal"`, `topic`, `message`, `body`, `authorKind`, `author`
and `reply_to` naming the original Relay request **event ID**. Board message
identity is separate from the return Relay topic. The publisher is `board:bridge`.
Reply event IDs derive deterministically from request UUID, message ID and kind.
The request's effect causation is retained; reply publication does not mint a
fresh effect budget.

V238 adds disclosure bindings, materialized thread membership and a publication
outbox. Triggers capture answers and terminal closure in the owning board
transaction, without adding a second forum/delivery engine. A bounded worker
locks one binding, appends its oldest pending answer and removes that outbox row
in the same database transaction. Concurrent drains cannot reorder that request.
Failure rolls both back, retaining the debt across restart; retrying this internal
append never repeats a forum mutation. The failed request receives a durable
backoff using `plowshare.relay.workers.failure-backoff` (30 seconds by default),
so an unavailable destination does not block other requests. A missing reply
topic leaves debt pending until it is available; no alternate destination is selected. Forum retention
deletes dependent bindings and outbox rows through foreign keys.

## Verification

Mocked tests cover strict codecs/routes/WS conversion, original publisher
attribution, destination and role refusals, duplicate recovery, read-only
inspection and append failures. Targeted `full-db` tests exercise V238, typed
destination row mapping, actual read-role isolation, thread/terminal triggers,
concurrent closes and rollback of mutation/receipt/binding and append/outbox
transactions. Shared SDK catalogs, DTO fixtures and operation inventory include
`board.close`; the messaging API is unchanged.
