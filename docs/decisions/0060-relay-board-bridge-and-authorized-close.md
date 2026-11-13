# Relay-to-board bridge, replies and project-authorized closure

Status: implemented for Slice 2 of [Finishing Relay](../relay-finishing.md),
13 November 2026. Follows [0057](0057-board-forum-and-relay-messaging-substrate.md).
This is general platform forum functionality. The native requester extension is
reviewed as T02A under [0066](0066-relay-messaging-receiver-and-review-remediation.md);
[the task graph](../relay-review-task-graph.md) records its actual acceptance state.
This bridge does not implement adapter protocol translation.

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
swarm in the source project. Configured post/close destinations must belong to
that same authorized project; a route cannot transfer source-project rights into
another project. A missing/closed forum topic,
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
For authenticated SDK callers, `reply_to` is optional; absent/null requests no
replies. If supplied, it must name an existing TEXT topic in the source project,
using Relay's normal topic grammar. No topic is guessed or created. Proven native
execution exports omit `reply_to`: their requester destination comes from an
immutable messaging-owned binding, never a claimed inbox or body instance ID.

## Authority and attribution

External callers publish through the existing authenticated SDK Relay ports.
The receiver resolves the original SDK publisher and rechecks current ingress
on both source and explicit reply topic before preflight/dispatch. Matching an
existing TEXT topic alone is not publishing authority. Reply inspection after
uncertainty is read-only and does not recheck a revoked source/reply grant.

Every forum write requires current project write permission and personal-project
ownership. Openings use the authorized source project. Posts/closures pin their
retained forum snapshot to the source project's immutable ID; source membership
cannot authorize a foreign destination or disclose its replies. No route grants a forum member seat. SDK publishers remain Person actors.
Execution publishers become Agent actors only with exact execution-ledger
provenance and an explicit native board export binding; unknown/historical
executions without that binding remain refused. The owning account supplies
write/Personal authority separately from definition authorship. Agent post/close
records use `authorKind=agent`, the definition name and null conversation/entry.
They neither impersonate a person nor gain a member seat. Agent openings use the
existing `BY_AGENT` opening lifecycle and its normal opener seat.

Closure uses the publishing account's live source-project
write permission (`ProjectMembers.mayWork`, Contributor or Manager, including
existing server-admin behavior). Personal ownership remains enforced. The
WebSocket `board.close` operation applies the same policy to its authenticated
calling account. The model `board_close` tool is offered to ordinary member
seats as well as opener seats and checks its bound execution account. An opener
seat confers no special close permission. Existing subtopic approval rules remain.

## Receipts, transactions and uncertainty

SDK/person calls use the Relay delivery UUID as the board request UUID. Native
executions derive a separate canonical mutation UUID from the authoritative source
event: different consumer groups recover one effect, not duplicate forum writes. The existing per-account
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

For each request with an authorized topic or native reply destination, the bridge
discloses only:

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

SDK topic replies use TEXT objects with `schema: "plowshare-board-reply/1"`,
`kind: "post" | "terminal"`, `topic`, `message`, `body`, `authorKind`, `author`
and `reply_to` naming the original Relay request **event ID**. Board message
identity is separate from the return Relay topic. The publisher is `board:bridge`.
Reply event IDs derive deterministically from request UUID, message ID and kind.
The request's effect causation is retained; reply publication does not mint a
fresh effect budget.

V242 adds disclosure bindings, materialized thread membership and a publication
outbox. Triggers capture answers and terminal closure in the owning board
transaction, without adding a second forum/delivery engine. A bounded worker
locks one binding, appends its oldest pending answer and removes that outbox row
in the same database transaction. Concurrent drains cannot reorder that request.
Failure rolls both back, retaining the debt across restart; retrying this internal
append never repeats a forum mutation. The failed request receives a durable
backoff using `plowshare.relay.workers.failure-backoff` (30 seconds by default),
so an unavailable destination does not block other requests. V248 pins new reply bindings to the destination topic generation. Pending scans
start directly from the `(message_seq, request_id)` outbox index with `LIMIT 1`,
rather than sorting every outstanding request head or visiting quiet retained
requests. Binding-wide eligibility means the first eligible row is the oldest
answer for that request; its request-row lock makes concurrent workers skip all
other answers for that request.
A typed generation-conditional broker append checks the exact topic incarnation
under its existing exclusive topic lock **after payload staging**. The board does
not pre-lock topic metadata: that would invert the broker's payload/topic order
and introduce shared-lock upgrade cycles. Definitive missing/replaced/kind outcomes
are values, not transaction-poisoning exceptions, so failure settlement can commit
atomically. Deletion/recreation cannot redirect owed content to a new topic owner.

A missing, replaced or invalid-kind destination settles the binding with a typed,
account-scoped failure and archives all owed message references outside the hot
queue. Old bindings lacking generation proof settle as `UNVERIFIED`, never by
adopting whatever topic now has that name. These states are inspectable through
`BoardBridgeRepository.failure(requestId, account)` and retain request/receipt
identity and owed references until normal forum retention. They do not claim
successful delivery. No new answers are enqueued for a failed binding. Transient
append/storage failures still roll back and back off without abandoning debt.

An operator must inspect a failed binding and its retained forum messages before
establishing a newly authorized disclosure request. The worker neither rebinds a
failed request nor republishes its answers automatically. Normal forum retention
deletes dependent bindings and archived evidence through foreign keys.

The failure transition also serializes with forum reply and close triggers through request-row
locks. A trigger that wins first adds its debt before archiving; a trigger that waits rechecks the
terminal predicate and cannot recreate active debt after settlement.

## Native requester bindings and refusal outcomes

`plowshare.messaging.board-exports.policies` explicitly pins `project`,
`requestTopic`, `channel` and `receiver` (`board.open`, `board.post` or
`board.close`). The channel is the trusted routing branch name. Native board and
external adapter export policies cannot own the same project/request topic.
Before the granted execution publication commits, the observer proves the exact
run/turn source, retains a native external request and stores its immutable board
binding. Supported source proof and lifecycle limits are described in
[native execution exports](../messaging-relay-exports.md).

The bridge retains a sealed Topic or Native reply destination. Topic destinations
keep their generation fence. Native destinations contain only an opaque server
source event and are resolved by the bridge's messaging boundary; public forum
stores do not query private instances. Full board answers and the terminal outcome
become ordinary native correlated replies with stable per-answer UUIDs. They use
the retained original requester and effect ancestry. They never pass through the
SDK adapter return receiver or assert `messaging:` identities. Native configured
body limits remain enforced; nothing is truncated or split into extra paid turns.

Native reply publication and board outbox removal commit together. Expected
prewrite native refusal is a typed value returned under the native owning locks,
so it does not mark the enclosing transaction rollback-only. Closed, cancelled,
archived, withheld or revoked requesters settle as inspectable
`NATIVE_UNAVAILABLE` and archive owed forum references. Queue capacity and storage
failures roll back and back off, retaining debt. Existing AWAITING/approval and
source execution completion remain authoritative; no return guesses a continuation
or revives a finished task.

A proven native request also needs an outcome when its forum destination refuses
before any mutation. Its preflight remains read-only and narrowly defers a known
refusal until durable dispatch intent. Dispatch rechecks live authority, gives an
existing forum receipt precedence, and otherwise retains a fixed safe-code native
refusal intent. Invalid or unproven bindings are not deferred. SDK/person
preflight keeps its existing early refusal behavior.

V254's dedicated refusal debt table contains no fake forum messages. The existing
board reply worker fairly alternates forum answers and refusal outcomes; there
is no second durable runtime. After restart it sends one native final refusal or
records scoped `UNAVAILABLE` evidence if the requester cannot receive it.
Capacity/storage errors keep this outcome pending with backoff. Native acceptance
and refusal compete under the same account/canonical-request advisory lock.
Exactly one canonical forum effect or refusal can win. A changed target conflicts
without a forum mutation or a false final refusal to an already accepted request.
Matching uncertain inspection reads retained evidence only and cannot mint another
refusal or forum mutation. Diagnostics contain stable IDs and codes, never refused bodies or
exception text.

## Verification

Mocked tests cover strict codecs/routes/WS conversion, original publisher
attribution, destination and role refusals, duplicate recovery, read-only
inspection, exact source/reply authorization and append failures. Tests also
cover deleted/recreated destination generations, unverified historical bindings,
scoped terminal failure evidence and rollback of archive settlement. Targeted `full-db` tests exercise V242, typed
destination row mapping, actual read-role isolation, thread/terminal triggers,
concurrent closes and rollback of mutation/receipt/binding and append/outbox
transactions. Shared SDK catalogs, DTO fixtures and operation inventory include
`board.close`; the messaging API is unchanged.
