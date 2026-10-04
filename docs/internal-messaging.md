# Internal project messaging: user manual

Plowshare agents and bots can send tasks, information and results between
projects on the same server. Each message runs in an account's context. The
recipient handles it with its own project, definitions and permissions.

Use this capability to connect integration projects, request work from another
project's agent, or exchange information with your Personal space. An integration
decides when to send a message and what its body means; routing configuration
defines the permitted destinations and optional address aliases.

## 1. Addresses and account scope

There are three different kinds of identity:

| Identity | Example | Use |
| --- | --- | --- |
| Ordinary project | `notifications` | Destination project, manifest name and routing allowlists |
| Personal routing identity | `Personal:alice` | Alice's Personal destination, route files and routing allowlists |
| Agent or bot instance | `ins_...` | One account/project/definition/conversation binding |

`to_project` selects the project. `to` selects an agent or bot definition in
that project, or an existing instance address. A definition name such as
`notifier` is resolved in the destination project.

Each account has exactly one Personal project. Its readable routing identity
is `Personal:<account-name>`; the prefix and account name must match exactly.
The display label **Personal** alone is not an address. `Personal:alice` and
`Personal:bob` identify different private spaces, and Alice cannot message
Bob's Personal.

`project.list` exposes a Personal row with:

```json
{
  "name": "personal:616c696365",
  "kind": "personal",
  "displayName": "Personal",
  "routingIdentity": "Personal:alice"
}
```

This is an excerpt of a listing row. `name` remains the compatible internal
project key. Use `routingIdentity` for `send_message.to_project`, route-file
destinations and Personal entries in routing allowlists. Operator operations
such as `message.instances` and `message.instance.open` still take the listing's
`name` in their `project` field.

Messages stay within the sending account. Sharing an ordinary server project
does not share its agent instance addresses or messaging history across accounts.

## 2. Which messages are permitted

The account must have access to both projects. Routing permission does not
grant project membership, access to another account, or file permissions.

| New message | Routing requirement |
| --- | --- |
| Within one project | Ordinary same-project messaging |
| Ordinary project → ordinary project | Source `sendTo` and destination `acceptFrom` must both permit the other project |
| Your Personal → ordinary project | Permitted by the Personal send default; explicit two-sided rules are needed when that default is disabled |
| Ordinary project → your Personal | Permitted by the Personal receive default; explicit two-sided rules are needed when that default is disabled |
| Any project → another account's project instance or Personal | Refused |
| Client-only DISJOINT project ↔ another project | Refused, including Personal traffic |

For ordinary projects, absent or empty allowlists permit no new cross-project
messages. Entries are exact names, not wildcard patterns. Incoming permission
does not imply outgoing permission.

Personal's enabled defaults allow its traffic without ordinary project
allowlist entries. They still require the account's project access. Personal
is recognized through stored account ownership; writing a Personal label or
extra property into an ordinary manifest does not make it Personal.

## 3. Configure Personal defaults

Both defaults are enabled in the server configuration:

```yaml
plowshare:
  messaging:
    personal:
      send-to-any-project: true
      accept-from-any-project: true
```

| Setting | Environment variable | Default |
| --- | --- | --- |
| `send-to-any-project` | `PLOWSHARE_PERSONAL_SEND_TO_ANY_PROJECT` | `true` |
| `accept-from-any-project` | `PLOWSHARE_PERSONAL_ACCEPT_FROM_ANY_PROJECT` | `true` |

Set either direction to `false` to require explicit source and destination
manifest rules for that direction. For example, disabling Personal's receive
default requires the sending project's `sendTo` to include `Personal:alice`
and Alice's Personal `acceptFrom` to include the source project's name.

Apply server configuration changes with a server restart. The Docker deployment
passes these variables from its deployment environment file.

## 4. Put configuration in the authoritative workspace

For ordinary server projects, Plowshare reads the registered **server
workspace**. A client checkout cannot change the deployed routing policy by
opening a local copy of the same project.

Within that workspace, the first nonempty marker takes precedence:

1. `.plowshare/plowshare`
2. `.plowshare/project`
3. `plowshare` at the workspace root

These files are alternatives; their configuration is not merged. A generated
name-only `.plowshare/project` marker takes precedence over a root `plowshare`
file. For a MANAGED project, write the formal JSON in `.plowshare/plowshare`,
or replace the existing `.plowshare/project` content with the JSON form.

A server DISJOINT project with no local identity marker can use its root
`plowshare` file. Its pipeline can manage the manifest and route files along
with the rest of the checkout. Registering this project is an administrator
action; a manifest alone does not register a shared server project.

Personal's routing files are read from its server union working tree. Its
manifest name may be the qualified identity, such as `Personal:alice`, or its
existing encoded internal project name.

The server reads policy files for new sends. Deploying updated files takes
effect without a server restart. Updating a policy affects new requests;
cancel an already accepted request explicitly if it should stop.

See [Projects and writable areas](projects.md) for creation, pipeline ownership
and file-write restrictions.

## 5. Set up a route between two projects

This example connects `home-assistant` to `notifications`. The names illustrate
an integration workflow; install and configure the integration and destination
agent separately.

### Register projects and prepare the recipient

An administrator can create MANAGED projects using the CLI:

```sh
bin/plowshare-cli project create '{"name":"home-assistant"}'
bin/plowshare-cli project create '{"name":"notifications"}'
bin/plowshare-cli project list
```

Grant the working account membership in both projects as needed. Make an agent
or bot called `notifier` available in `notifications`, with the tools and scopes
it needs to handle the message. See [Skills and agent rules](skills-and-agent-rules.md)
for definitions and grants.

For pipeline-owned projects, use the existing server DISJOINT creation workflow
instead. Use the server workspace reported by the project listing when writing
the following configuration.

### Configure the sender

In `home-assistant`'s selected manifest:

```json
{
  "version": 1,
  "name": "home-assistant",
  "routing": {
    "acceptFrom": [],
    "sendTo": ["notifications"],
    "routeFiles": ["routes/internal.json"]
  },
  "integration": {
    "name": "home-assistant"
  }
}
```

The additional `integration` property is application configuration. Plowshare
retains extra JSON properties; they do not execute an integration or grant
permissions by themselves.

### Configure the receiver

In `notifications`' selected manifest:

```json
{
  "version": 1,
  "name": "notifications",
  "routing": {
    "acceptFrom": ["home-assistant"],
    "sendTo": []
  }
}
```

This allows requests from `home-assistant`. A correlated reply can return to
its sender without enabling unsolicited messages in the reverse direction.

### Define a named route

Create `routes/internal.json` inside the **sender's workspace root**:

```json
{
  "version": 1,
  "routes": [
    {
      "name": "notify",
      "project": "notifications",
      "agent": "notifier"
    }
  ]
}
```

Route-file paths are relative to the workspace root, even when the manifest
is inside `.plowshare`. Multiple files can separate an integration's routes
by purpose. Route names must be unique across all of the project's route files.

A named route supplies a project and agent definition. Both projects' policy
still applies. Route files do not perform event filtering, scheduling or
automatic forwarding; the integration or sending agent makes the send call.

### Keep a route's conversation

By default, a route uses the destination definition's shared persistent instance
for the sending account and destination project. This preserves the existing
behavior. `lifetime: "task"` on an unconfigured route creates a fresh log for
that message instead.

To give a route its own continuous history, add `retainConversation: true`:

```json
{
  "version": 1,
  "routes": [
    {
      "name": "notify",
      "project": "notifications",
      "agent": "notifier",
      "retainConversation": true
    }
  ]
}
```

The first accepted send creates one dedicated persistent instance and log.
Later sends through that route use the same agent and conversation, including
after a server restart or a change to the definition's project default.
The binding belongs to the sending account, source project and route name.
Different accounts, source projects or route names get separate bindings.
Concurrent first sends and retried tool calls do not create duplicate logs.

Alternatively, target an existing conversation:

```json
{
  "version": 1,
  "routes": [
    {
      "name": "notify",
      "project": "notifications",
      "agent": "notifier",
      "conversation": "cnv_0123456789ABCDEF"
    }
  ]
}
```

Replace the example ID with an existing conversation ID. It must be an active
root conversation owned by the sending account in the destination project.
A log belonging to a particular agent must match the route's `agent`; a human
conversation can bind the selected definition. Its existing history must also
be readable by the account. Task instances and delegated child conversations
cannot serve as retained destinations. No new log is created for this option.

| Route configuration | Conversation selection |
| --- | --- |
| Neither option, or `retainConversation: false` | Shared account/project/agent default; `lifetime: "task"` requests a fresh task |
| `retainConversation: true` | Dedicated conversation created once and durably pinned to this route |
| `conversation: "cnv_..."` | The specified existing conversation |

Use only one of `conversation` and `retainConversation`. For either retained
option, omit `lifetime` or use `persistent`; `task` is refused because it would
contradict the retained destination.

Each new send still checks project access and routing permission. Stopping or
archiving a pinned instance causes future sends to be refused; the server does
not silently replace it with an empty log. Changing a retained route's project
or agent also refuses delivery. Use a new route name to create a new dedicated
log, or configure `conversation` explicitly to select another existing log.
Renaming the route file alone does not change a binding.

### Send a message

An agent running in `home-assistant` can call `send_message` with:

```json
{
  "route": "notify",
  "body": "The office light changed to on. Record the event.",
  "reply_expected": true
}
```

Or it can use the explicit destination:

```json
{
  "to_project": "notifications",
  "to": "notifier",
  "body": "The office light changed to on. Record the event.",
  "reply_expected": true
}
```

These JSON objects are **agent tool arguments**, not standalone CLI commands.
Run or converse with the sending agent through the CLI, TUI, desktop or SDK;
the agent invokes the tool in its authenticated project context.

`body` must be a string. An integration may serialize a structured event into
that string, provided the recipient knows how to interpret it.

## 6. Send to and from Personal

With the receive default enabled, an agent in a project Alice can access can
send to Alice's Personal without adding manifest allowlist entries:

```json
{
  "to_project": "Personal:alice",
  "to": "reviewer",
  "body": "Review the latest integration result.",
  "reply_expected": true
}
```

The `reviewer` definition must resolve in Alice's Personal context. With the
send default enabled, an agent running in Alice's Personal can use the explicit
`notifications` destination from the previous section.

A route file can also target Personal:

```json
{
  "version": 1,
  "routes": [
    {"name": "personal-review", "project": "Personal:alice", "agent": "reviewer"}
  ]
}
```

That route is account-specific. It is refused when used by another account.
Use that account's qualified Personal identity when adapting the configuration.

## 7. Delivery, replies and permissions

Sending commits a durable message and its owed wake, then returns a receipt.
It does not wait for the recipient's result. A busy recipient waits until its
conversation is free.

A receipt has this shape; the IDs below are placeholders:

```json
{
  "message": "bdm_...",
  "from": "ins_sender...",
  "to": "ins_recipient...",
  "from_project": "home-assistant",
  "to_project": "notifications",
  "to_conversation": "cnv_recipient...",
  "return_address": "ins_sender...",
  "reply_expected": true,
  "queued": true
}
```

A receipt confirms acceptance, not successful completion. Preserve the message
ID to inspect the delivery. The harness supplies sender identity and the return
address; callers cannot replace them with their own fields.
`to_conversation` identifies the recipient's log. Existing instance inspection
also returns its conversation ID.

The recipient gets message data including its ID, sender, project identities,
body and return address. It runs with its own project and definition's tools,
file-write areas, environment controls and history. Cross-project delivery
does not inherit the sender's document selections or expose the sender's
conversation history.

To complete the request, the recipient calls:

```json
{"reply_to":"bdm_...","body":"The event was recorded.","final":true}
```

To report progress without completing it:

```json
{"reply_to":"bdm_...","body":"Checking the destination.","final":false}
```

Only the original recipient can reply to that message. Omit `to_project` and
`lifetime` for replies; the harness supplies the original return destination.
Correlated replies remain possible after routing permission is revoked. This
does not authorize a new, unsolicited reverse request.
Cancelled or expired requests reject explicit replies; inspect their recorded
terminal outcome instead.

If `reply_expected` is true and handling ends without a final reply, the harness
generates the handling outcome as the final reply, including its actual ending
status. Progress messages do not suppress this result. An interrupted run is
reported during recovery rather than automatically replayed.

## 8. Agent tool reference

| Field | Meaning and default |
| --- | --- |
| `body` | Required message string |
| `to` | Definition name or existing `ins_...` address |
| `to_project` | Destination project or qualified Personal identity; defaults to the sending project |
| `route` | Named route in the sending project's route files; supplies project and definition |
| `reply_to` | Incoming message ID to reply to |
| `reply_expected` | Defaults to `false`; request a terminal outcome when `true` |
| `final` | Defaults to `true` on replies and `false` on new requests |
| `lifetime` | `persistent` by default; `task` creates a fresh bounded instance for a definition destination |
| `timeout_seconds` | Optional deadline, from 1 to 604800 seconds, starting when sent |

For a named route, omit `to`, `to_project` and `reply_to`. For an existing
instance address, omit `lifetime`; that address already has a lifetime.
Addressing an instance in another project still checks routing permission.
Routes with `conversation` or `retainConversation: true` retain their configured
destination and refuse `lifetime: "task"`.

Persistent definition destinations reuse their account/project default
instance. A task instance ends after its terminal handling outcome. Start a
new task or use a persistent instance for later independent requests.

Timeouts include queue and approval waits. Expiry cancels handling and provides
an expected terminal outcome. Cancellation is cooperative. See
[Agent and bot messaging](agent-messaging.md) for approval and lifecycle details.

## 9. Operate and inspect through the clients

Control and inspection use authenticated WebSocket operations. The CLI provides
the following commands:

```sh
bin/plowshare-cli project list
bin/plowshare-cli message instances '{"project":"notifications"}'
bin/plowshare-cli message instance ins_...
bin/plowshare-cli message deliveries ins_...
bin/plowshare-cli message delivery bdm_...
bin/plowshare-cli message cancel bdm_...
```

In the TUI, prefix the operation with `/`:

```text
/message instances {"project":"notifications"}
/message delivery bdm_...
```

Replace placeholder IDs with values returned by Plowshare. To inspect Personal,
use the internal `name` from its project listing in the `project` field.

The desktop's **Manage work** provides instance creation, default selection,
stop/archive, delivery inspection and cancellation. Clients surface uncertain
mutation outcomes rather than silently replaying them. Inspect a delivery after
an uncertain response before deciding whether another request is needed.

| Control | Purpose |
| --- | --- |
| `message open` | Create a persistent instance without starting inference; requires a UUID `requestId` |
| `message default` | Select a persistent instance as the definition's project default |
| `message stop` | Stop an instance and cancel pending handling |
| `message archive` | Stop and archive the instance and its conversation |
| `message cancel` | Cancel one pending delivery |

Operator inspection remains subject to account ownership and readable logs.
Messaging does not make another participant's history available to an agent.

## 10. Configuration limits and compatibility

Formal manifests require `version: 1` and a valid `name`. A legacy first-line
project name remains supported, but cannot declare routing. Neither form is
automatically rewritten. Unknown JSON properties can hold SDK/application
configuration; Plowshare interprets the routing fields described here.

| Configuration | Limit |
| --- | --- |
| Each manifest or route file | 64 KiB |
| Manifest plus referenced route files | 1 MiB total |
| Entries in each routing list | 256, without duplicates |
| Named routes across all referenced files | 256, with unique names |
| Project and route names | At most 512 characters, without surrounding whitespace, line breaks or NUL |

Route files must be regular files inside permitted, visible workspace areas.
Absolute paths, traversal components, symbolic links, excluded files and
malformed JSON are refused. Use forward-slash relative paths such as
`routes/internal.json`. Ordinary hidden subdirectories such as
`.plowshare/routes/` are outside the route-file read fence.

Transport limits are separately configurable under `plowshare.messaging`:
`queue-limit` defaults to 256 pending messages per instance, `instance-limit`
to 256 active instances per account/project, and `body-bytes` to 65536 UTF-8
bytes. A required harness outcome can bypass body/queue limits to preserve the
terminal result.

Schemas: Project manifest and
Route files.

## 11. Troubleshooting

| Symptom or refusal | Check |
| --- | --- |
| `Both projects must permit this message route` | Both ordinary allowlists, exact names, and the selected manifest; for Personal, check whether the relevant default is disabled |
| `Cross-project messaging is not permitted` | Project existence, account access and whether either scope is a private client-only DISJOINT project |
| `This Personal project belongs to another account` | Use the current account's `Personal:<account-name>` identity |
| `No configured route with that name` | Source manifest selection, its `routeFiles`, and the route's exact `name` |
| Invalid routing manifest identity or version | Use version 1 and the registered project name, or the owned Personal identity |
| Routing configuration could not be read | File deployment, missing references, readability and JSON syntax |
| Route outside the permitted workspace | Relative paths, hidden directories and workspace exclusions |
| Duplicate route name | Give each alias one unique name across the project's files |
| A root manifest change has no effect | Look for a higher-precedence `.plowshare` marker; confirm that you changed the deployed server workspace |
| A message was accepted but there is no result yet | Inspect the delivery and recipient instance for queued work, running work, approval waits or an ended instance |
| The task instance has ended | Use a persistent instance or start a fresh task |
| The route's retained conversation is not active | Select an active conversation or use a new route name for a new dedicated log |
| The route's retained instance is stopped or task-scoped | Select an active persistent/caller instance's conversation, or a new route name |
| This retained route is pinned to another destination | Restore its project/agent configuration or use a new route name |
| No accessible conversation at this route destination | Check the conversation ID, account ownership, project, agent and that it is a root conversation |

For more detail, see [Projects](projects.md), [Personal space](personal-space.md)
and [Agent and bot messaging](agent-messaging.md). Integration authors can also
use the [SDK guide](sdks.md) for authenticated execution and inspection.
