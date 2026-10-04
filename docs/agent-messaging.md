# Agent and bot messaging

For configuration and a complete cross-project example, see the
[Internal project messaging manual](internal-messaging.md).

Agents and bots in account-owned project conversations can use `send_message`.
The harness binds the sender and supplies its return address. Direct messages use
the board's durable messages, topic allowances, queued firings and wake scheduler;
the tool exposes participant addresses and message IDs, not boards or seats.

Messaging is asynchronous. Sending commits a message and its owed wake together,
then returns a receipt. A busy recipient waits for its conversation to become
free. Each direct message has a bounded handling run, so its terminal
outcome is attributable to exactly one incoming message. Direct wakes are never
coalesced like ordinary board notification wakes.

## Addresses and lifetimes

An `ins_...` address identifies a specific instance, bound to an account, project,
definition and durable conversation. A definition name selects its default
persistent instance in the sending account and project. Concurrent first sends
resolve the same default; restarts retain its address and history.

For an explicitly permitted server project, `to_project` selects the destination
project's definition/default. Alternatively, `route` selects a named destination
from this project's route files. See [Internal message routing](projects.md#internal-message-routing)
for the two-sided project policies and route file format. An instance address
also rechecks those policies on each new cross-project request.

Routes can target an existing conversation with `conversation`, or create and
retain a dedicated log with `retainConversation: true`. Without either setting,
the shared default behavior stays the same. Retained routes refuse task lifetime
and never silently replace a stopped or archived binding. See the manual's
[conversation retention examples](internal-messaging.md#keep-a-routes-conversation).

```json
{"to":"code_reviewer","body":"Review the change in src/parser.java.","reply_expected":true}
```

The receipt includes `message`, `from`, `to`, `to_conversation`, `return_address`, `reply_expected`
and `queued`. The destination's `to` address can be used in subsequent sends.
An existing caller gets its own return address bound to the conversation and
definition that sent the message; replies do not select the project's default
instance instead. Different definitions in a human conversation have distinct
addresses, while preserving that conversation's existing shared history.

Use `lifetime: "task"` with a definition-name destination to create a fresh
instance for one task. It ends when handling reaches a terminal outcome.
Waiting for approval leaves the task open; answering resumes the same request,
including approvals asked by a delegated agent. Persistent instances remain addressable between handling turns.
Replies to an ended task are retained but do not wake it again. A new request to
an ended task is refused; select a persistent address or create another task.

```json
{"to":"coder","lifetime":"task","body":"Fix the parser regression.","reply_expected":true}
```

## Replies and outcomes

Incoming message data contains its `id`, `from`, sender definition name,
`return_address`, `from_project`, `to_project`, `body`, and `reply_expected`. Replies also carry `reply_to`,
`final`, and `generated`; generated outcome replies include the actual `ending`.
The harness identifies the message as data rather than impersonating a person's
utterance or presenting its body as a system instruction.

```json
{"reply_to":"bdm_...","body":"The review is complete.","final":true}
```

`reply_to` resolves the original return address. Only the original recipient can
reply. `final` defaults to true on replies; set it to false for progress or a
question. New messages default to `reply_expected: false`; recipients can still
reply voluntarily.

If `reply_expected` is true and no explicit final reply has been recorded when
handling reaches a terminal outcome, the harness records its outcome as the final reply and
owes the original sender a wake. Progress does not suppress this fallback.
The original request is locked for both explicit final replies and fallbacks;
a database uniqueness constraint permits only one final reply. Completion can
be retried without creating another final reply or wake.

Failures and caps keep their actual outcome status. A generated reply is marked
as harness-generated, rather than represented as an explicit message written by
the recipient. An interrupted handling turn is reported as unavailable during
restart recovery; it is not automatically replayed and charged again.

## Visibility, permissions and allowances

Direct transport topics are omitted from public board listings and normal board
recovery. A recipient is given only its incoming message and its own permitted
history. The model-facing conversation tools refuse other messaging
participants' conversations and their delegated child histories and filter those histories out of search/list
results. A filtered search window is explicitly marked incomplete.

Addresses cannot cross accounts. Ordinary cross-project requests require both projects'
routing policies and the sending account's access to both server projects.
Personal's configurable send/receive defaults allow traffic with any project
its account can access. Its qualified routing identity is `Personal:<account-name>`;
other accounts' Personal projects remain private. See [Personal routing](personal-space.md#internal-message-routing).
Client-only DISJOINT attachments remain isolated. Replies use their original
request's correlated return address, even after routing policy revocation;
fresh reverse requests require their own permissions.

Within a project, document input selections follow
the source log into the recipient log, preserving later revocation and
quarantine checks. The destination runs with its own definition's tools, scopes,
hooks and environment controls; sending does not transfer the sender's grants.
Cross-project delivery transfers the message body, without inheriting document
selections or granting access to the sender's files or conversation history.
If inputs become unavailable before outcome delivery, the result is withheld
and the request is recorded as unavailable. A reply expectation does not bypass
revocation, and recovery does not replay that work.

Each committed incoming message grants at most the receiving definition's
`max-model-calls` for its handling turn. Grants and spending accumulate durably
in the existing board pot; retries grant no extra calls. Persistent instances
retain their history across these bounded turns. Handling turns use the
definition's normal turn cap. Models with
dedicated swarm capacity use its fair scheduler; direct messaging can also use
ordinary model pools without requiring dedicated swarm slots.

Swarm members are normal agents too: the experimental prohibition on command
execution, file mutation, memory writes and delegation has been removed. Their
ordinary definition grants and harness controls still govern those operations.

Delegation remains a task-and-outcome call with shared caller budget and
cancellation. Only delegable agents can be its targets; a bot cannot become a
delegation target even if its definition says `delegable: true`. Both kinds can
receive messages.

This adds direct messaging on the board transport. Existing human chat,
orchestration question/answer and account-inbox records retain their current
storage models; migrating those records to shared message identities is separate
work. There is no new HTTP messaging endpoint or A2A protocol adapter: agents
invoke the tool through their existing harness execution paths.

## Approval, cancellation and deadlines

An approval pause retains the original message, reply expectation and return
address. Approval answers queue a durable continuation ahead of unrelated
messages. A delegated agent resumes in its own conversation; its terminal result
continues the receiving agent, which finishes the original message. Retries of an
approval answer do not queue another continuation. Restart recovery also catches
answers committed before their continuation was queued.

A sender can set `timeout_seconds` from 1 to 604800. The deadline starts at send,
including time queued or awaiting approval. Expired messages get state `expired`
and ending `CANCELLED`; if a final reply is expected and absent, the harness
creates one. The server checks deadlines before starting work and sweeps pending
deadlines every 30 seconds.

```json
{"to":"reviewer","body":"Review this change.","reply_expected":true,"timeout_seconds":3600}
```

Cancelling a message or stopping its instance completes pending handling with
`CANCELLED` and requests cancellation of its exact running job. Cancellation is
cooperative; a late completion cannot create another final reply. Archiving also
archives the instance's conversation. Caller instances belong to an existing
human conversation and use its ordinary lifecycle controls instead of instance
archive. Stopped instances cannot be restarted through these controls; create a
new persistent instance and select it as the default.

## Operator controls and clients

All controls are authenticated WebSocket operations scoped to the owning account
and accessible project. Both participants' readable logs are checked before
exposing a delivery. Private continuation firings are excluded from general
firing listings.

| Operation | Purpose |
| --- | --- |
| `message.instances` | Page through project instances; optionally include archived instances |
| `message.instance` | Inspect one instance's state, pending count and running job |
| `message.instance.open` | Create a persistent instance using a UUID `requestId`; optionally make it default |
| `message.instance.default` | Replace the default for this agent or bot in the account/project |
| `message.instance.stop` | Stop the instance and cancel pending handling |
| `message.instance.archive` | Stop and archive the instance |
| `message.deliveries` | Page through an instance's sent and received messages |
| `message.delivery` | Inspect a message, final reply identity, deadline and outcome |
| `message.cancel` | Cancel pending message handling |

Opening is idempotent for the same account/project/request UUID and arguments;
conflicting reuse is refused. It creates an address without starting inference.
Inspection pages default to 100 items and allow at most 200. Page offsets advance
through underlying records even when revoked inputs hide an item.

The shared TypeScript client validates requests and replies and exposes these
operations to the CLI. The TUI uses `/message` commands. Desktop **Manage work**
provides creation, default selection, stop/archive, delivery inspection and
reviewed cancellation. Clients surface ambiguous mutation outcomes without
silently replaying them.

```sh
plowshare message instances '{"project":"my-project"}'
plowshare message open '{"project":"my-project","agent":"reviewer","requestId":"89de8280-2b2b-4cf5-883b-0a0a7da855ce","makeDefault":true}'
plowshare message deliveries ins_...
plowshare message cancel bdm_...
```

Server configuration under `plowshare.messaging` bounds each instance's pending
queue (`queue-limit`, default 256), active instances per account/project
(`instance-limit`, default 256), and agent-authored UTF-8 message bodies
(`body-bytes`, default 65536). A required harness outcome bypasses body and queue
limits so a full terminal reply is retained. Limits refuse the send transaction
before committing partial messages, wake firings or budget grants.
