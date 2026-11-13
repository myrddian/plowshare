# Native messaging through Relay

`message.receive`, `message.cancel` and `message.inspect` expose the native
messaging lifecycle through existing Relay TEXT publications. They do not add a
WebSocket operation or change the Relay envelope. The native message service owns
participants, allowances, execution, approvals, cancellation and retained tasks;
Relay owns delivery and receipt recovery. See [ADR 0066](decisions/0066-relay-messaging-receiver-and-review-remediation.md)
for the platform decision and [Relay](relay.md) for subscriptions and port grants.

## Configure one trusted channel

Create a TEXT command topic and a TEXT return topic in the source project. Grant
the publishing principal the exact deployment ingress permissions for both. The
return topic discloses native replies to that project's authorized Relay readers;
choose its readers accordingly. Receiving an explicit command also requires that
command to remain available in the current receiving definition's command catalog.

The route pins the agent, return topic and channel. The payload cannot override
them. All three operations must share the same command topic, subscription and
configured channel. For example, a project's `Relay/native-messages/routes.js`
can select the receiver by the strictly validated payload schema:

```js
export const manifest = {
  version: 1,
  subscriptions: [
    {name: 'commands', topic: 'native.commands', kind: 'text', start: 'oldest-retained'}
  ]
};

export function route(event) {
  const command = JSON.parse(event.payload.text);
  const receivers = {
    'plowshare-message-request/1': 'message.receive',
    'plowshare-message-cancel/1': 'message.cancel',
    'plowshare-message-inspect/1': 'message.inspect'
  };
  const receiver = receivers[command.schema];
  if (!receiver) return [];
  return [{
    name: 'native',
    receiver,
    work: {
      agent: 'support',
      replyTopic: 'native.results',
      channel: 'support-bridge'
    }
  }];
}
```

These are illustrative project identifiers, not server configuration defaults.
Routes accept no script or project override for these receivers. Schema routing
selects a receiver; it does not authenticate the body or grant execution rights.

The server resolves the actual publishing account from server-owned SDK or
execution provenance. Execution publications remain distinct from people. A
channel family is scoped by numeric project, command-topic incarnation,
subscription, authenticated publisher and configured channel. Receiver and branch
names do not divide the family, so controls can find tasks created by receive.
The first retained binding also pins the agent and return-topic incarnation.
Changing either requires an explicit new channel and reconciliation, rather than
silently retargeting retained work. Different topics, credentials or channels do
not adopt one another's contexts.

`client` is a producer-owned alias, not another authentication credential. A
multi-user adapter must derive it from its authenticated client mapping. Sharing
credentials and a channel intentionally shares that producer's client namespace.

## Commands

Every shown field is required, including fields whose value is `null`. Unknown
fields, duplicate JSON keys, coercions and malformed values are refused. UUIDs
use canonical lowercase form. Request UUIDs identify operations; they are distinct
from the Relay event identity of the publication carrying the operation.

Receive accepts a nonblank body of at most 65536 UTF-8 bytes, a client alias of
1–64 permitted identifier characters, an optional context UUID and an optional
explicit qualified command:

```json
{"schema":"plowshare-message-request/1","requestId":"11111111-1111-4111-8111-111111111111","client":"client-1","context":null,"body":"Review this request.","command":null}
```

The configured native body-byte limit also applies. Text is never interpreted as
an implicit command or approval. A supplied context must belong to this principal,
project, channel and client. A duplicate operation recovers the retained receipt;
different reuse of the UUID is a conflict.

Cancel uses its own mutation UUID and the retained native task UUID:

```json
{"schema":"plowshare-message-cancel/1","requestId":"22222222-2222-4222-8222-222222222222","client":"client-1","task":"33333333-3333-4333-8333-333333333333"}
```

Inspection uses its own query UUID, a nullable history cursor and exactly one of
`task` or `originalRequest`. The original receive UUID is sufficient when its first
acknowledgement was lost:

```json
{"schema":"plowshare-message-inspect/1","requestId":"44444444-4444-4444-8444-444444444444","client":"client-1","task":null,"originalRequest":"11111111-1111-4111-8111-111111111111","cursor":null}
```

Repeating a query UUID recovers its original immutable page. Use a fresh query
UUID for a current view or the next page. Read-only recovery retains live access
checks and does not admit another native task. A negative response, an expired
broker record or an empty log is never permission to replay a receive mutation.

## Snapshots and reply pages

The server publishes `plowshare-message-task/1` TEXT records to the pinned return
topic with the exact reserved publisher `messaging:ingress`. Consumers must verify
that publisher, the expected project/topic incarnation, client and operation
identity before applying the payload. Routing arbitrary TEXT to a return topic
does not make it a trusted native result.

The root fields are `schema`, `requestId`, `sourceEvent`, `client`, `revision`,
`task`, `refusal`, `next` and `resume`; all are required. `requestId` and
`sourceEvent` identify the operation producing the snapshot and its actual Relay
publication. Initial and asynchronous notifications use the original receive
identity. Inspection and cancellation responses use the control operation's
identity. The nested `task.requestId` always identifies the original receive.

A positive response has a positive decimal-string `revision`, a task and a null
refusal. Task fields are `id`, `context`, `requestId`, `agent`, `message`, `state`,
`ending`, `replies` and `createdAt`. `message` is a native message identifier for
rendering and correlation; it grants no authority. States are `SUBMITTED`,
`WORKING`, `INPUT_REQUIRED`, `COMPLETED`, `FAILED` and `CANCELED`. `ending` is a
nullable native outcome. Timestamps use ISO-8601 instants.

A negative response has null task and revision, null cursors, and one bounded
refusal: `NOT_ACCESSIBLE`, `REQUEST_CONFLICT`, `COMMAND_REFUSED` or
`INVALID_REQUEST`. It discloses no inaccessible task. Invalid payloads or missing
source/return authority can instead refuse the Relay branch without publishing an
unauthorized negative snapshot.

Each positive page contains at most two reply chunks. Each chunk has `id`, `body`,
`offset`, `complete`, `finalReply`, `generated`, `ending` and `postedAt`. A chunk is
at most 16384 Unicode codepoints; `offset` is a nonnegative decimal-string
codepoint offset, not a UTF-16 index or UTF-8 byte count. Whitespace-only chunks
are valid parts of a larger body. A reply may require several pages, and
`finalReply` describes the immutable reply on every chunk. It does not imply that
the current chunk is complete.

When `next` is nonnull, continue that finite captured reply head with a fresh
inspection UUID and the returned cursor. `resume` is then null. When the head is
complete, `next` is null and `resume` is a nonnull FOLLOW cursor. Persist that
cursor only after the exact assembled reply bodies and their projection are
durable. A later fresh inspection with `resume` reads only subsequent replies and
the current header; it does not reread the task's lifetime body history. Cursors
are opaque progress values and never grant access.

Assemble by immutable reply identity and exact codepoint offset. Reject gaps,
conflicting overlaps and changed metadata. Accept matching duplicate chunks
without repeating an external effect. Header revisions are monotonic: an older
page must not overwrite newer task metadata. Headers describe current native
state independently of the captured body head. In particular, a `COMPLETED`
header can accompany an unfinished body page; drain `next` and complete every
reply before emitting final artifacts or treating that head as fully consumed.
If that header advanced while traversing a fixed head, a newer final reply can be
beyond that captured head. Reconcile its FOLLOW cursor before emitting terminal
artifacts, rather than treating an old head as all current history. Persist an
unfinished `next` separately from the last completed FOLLOW cursor so a bounded
call or process restart continues its unread page instead of rescanning a prefix.
Native history is not silently truncated to 200 replies.

## Commit, recovery and lifecycle

Native admission, retained source identity, task/return binding and owning receipt
commit in one transaction. The private native inbox remains the only execution
path. A return snapshot is captured into an immutable transactional outbox;
publication retries append that notice and never repeat native execution. Durable
task revisions and a bounded periodic worker recover after process interruption.

Return publication rechecks current authority and appends against the retained
return-topic generation. Deleted, replaced or incompatible destinations are
terminal publication outcomes with retained evidence; an old task cannot disclose
to a newly created topic of the same name. Restoring a transient permission or
transport failure permits retry without manufacturing an execution result.

Before first publication, consumers capture the actual return-topic head and
generation. After an uncertain receive, inspect by the original receive UUID;
never guess a task ID or repeat paid work. A retained operation receipt can settle
an uncertain Relay branch even when its source broker record has expired.

Message-target schedules use the same native admission capability and retain their
existing request/context identity. Native command grants, per-message allowances,
approval blocks and cancellation remain authoritative. External reports are
ordinary correlated replies; they cannot resume an approval or invent a source
execution ending. See ADR 0066 for execution export/return binding.

## Adopt retained legacy tasks explicitly

Existing legacy WebSocket receive records keep their native context/task/message
IDs and full source evidence at the compatibility boundary. A new Relay channel
cannot inspect or adopt those records merely by supplying their old client alias.
An operator must configure an exact mapping under
`plowshare.messaging.relay.legacy-mappings`:

```yaml
plowshare:
  messaging:
    relay:
      legacy-mappings:
        - account: bridge-account
          project: example-project
          client: client-1
          source-topic: native.commands
          subscriber: relay.native-messages.commands
          source-generation: 55555555-5555-4555-8555-555555555555
          channel: support-bridge
          agent: support
          reply-topic: native.results
          reply-generation: 66666666-6666-4666-8666-666666666666
```

Use actual retained topic generations and the authenticated SDK account. Execution
publishers cannot claim this SDK migration. At most 256 mappings are accepted,
with one destination per legacy account/project/client scope. The server checks
the complete mapping and existing agent before moving that scope; IDs, historical
source evidence and bodies remain intact. With no matching mapping the old scope
stays inaccessible through the new channel, with no automatic replay.

The first authorized inspection of a migrated task establishes its notification
binding using that inspection's source event. No historical Relay receive event
is invented. Recreating topics or moving already-bound Relay families requires
separate operator reconciliation; the legacy mapping is not a general credential
or topic-incarnation transfer mechanism.
