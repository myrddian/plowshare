# Discord integration

One standalone Discord bot connects to Plowshare through the public Node/TypeScript
SDK. Fixed Discord channel mappings select a server project and exported bot.
The adapter uses a scoped service token; Discord participants do not need, acquire
or impersonate human Plowshare accounts. The server owns execution, grants,
accounting, approvals, contexts, tasks and cancellation. This module contains no
server imports, database access, core API changes or inference loop.

## Configure and run

1. Create a Discord application and bot in the Developer Portal. Enable **Message
   Content Intent**. Invite the bot only to the intended server, with View Channels,
   Send Messages, Read Message History and Send Messages in Threads for the selected
   channels. The adapter requests Guilds, Guild Messages and Message Content;
   it does not request Server Members or Presence. It does not register slash commands.
2. Deploy the [basic receiving Application](#basic-receiving-application), or use
   an existing authorized server Application. Create a
   [Plowshare service account](../../docs/server-administration.md#service-accounts-and-scoped-tokens),
   declare its owner handle in each Application's manifest, grant Contributor
   project membership, and issue a `pss_` token with those project ceilings.
   Grant your human operator manifest access and membership too, so the Application
   appears in their Applications view. The configured bots must be exported
   and present in the server/project definition catalogue. Credentials cannot
   supply client-local bot definitions or a local workspace presence.
3. Copy [the illustrative configuration](examples/config.json) outside the checkout.
   Replace the reserved example origin, placeholder absolute state path and fictitious
   Discord IDs. Select real project and concrete exported bot names. List immutable
   Discord user IDs explicitly; this version has no role-based or open-access mode.
4. Supply the two credentials through the configured environment variable names,
   using your private deployment secret store. Do not put either token in JSON,
   Discord chat, source, command arguments or logs. The configuration and state
   directory should also be private. Node.js 22.13+ and pnpm are required.

```sh
./gradlew :plowshare-discord-integration:discordBuild
node integrations/discord/build/discord/main.js /absolute/path/discord-config.json
```

Both the server origin and private state directory are required; there is no
local-server fallback. Plowshare work uses authenticated WebSocket SDK operations.
Discord uses its Gateway for input and HTTP API for replies, with ambiguous HTTP
effect retries disabled. Discord's safe rate-limit pacing remains enabled. Each
delivery rechecks fresh channel/thread metadata and has an overall timeout covering
that read and the send, including rate-limit waits.

Startup verifies that the authenticated identity is a service principal, binds
state to that principal/server/Discord bot, and checks each receiving bot's
catalogue. Server membership, token ceilings and the deployed manifest determine
effective access at submission and on later reads; successful catalogue discovery
is not a grant to run work. No credentials are persisted in adapter state.

## Chat, threads and controls

Set `requireMention` explicitly for every channel. When true, mention the bot in
each message, including controls and thread follow-ups. When false, each allowed
user's text in that channel is input. An exact channel mapping wins over a parent
mapping; otherwise an existing Discord thread inherits its parent's route.
Unmapped guilds/channels, unlisted users, DMs, other bots and webhook messages do
not submit work. Only text is forwarded; attachment-only messages are ignored.

Each `(guild, channel or thread, thread parent, Discord user, project, bot)` has
its own retained incoming context. Sharing a Discord channel does not share model history. Replies
stay in the original channel/thread and reference the triggering message. There
is no automatic thread creation, linked-user mode, native conversation adoption,
agent picker or arbitrary project/bot selection from message text.

```text
@Plowshare Investigate this claim using the retained project evidence.
@Plowshare !plowshare status
@Plowshare !plowshare cancel
```

Controls optionally accept the returned task UUID. They apply only to the same
user's tasks in the same mapped context, and cannot read/cancel another user's
task even though execution uses a shared service principal. Status is read-only;
cancellation is submitted once and then observed. Edits to old Discord messages
do not revise or restart admitted work. Approval waits remain paused; Discord
text does not answer approvals, and an unrelated human account is not assumed
to own a service-principal approval.

All output disables user, role, `@here` and `@everyone` pings, including reply-author
pings. Outputs are split into at most 2,000 UTF-16 units per message without
splitting surrogate pairs. A reply exceeding 40,000 units produces an explicit
oversize notice rather than a silently truncated result. Inspect its full retained
reply with `incoming.status` under the integration's SDK identity.

The user allowlist controls who may trigger work; the **Discord channel audience**
controls who can see its answers. Select projects and channels whose contents may
be disclosed to that audience. Routing and sender separation do not create
server-enforced permissions for individual Discord people. The shared token
retains its project authority; per-bot exposure is adapter policy.

## Basic receiving Application

[examples/application](examples/application/plowshare.json) is a small deployable
server Application named `discord`. Its root manifest and
[exported `discord_assistant` bot](examples/application/bots/discord_assistant.md)
match the example channel mapping. The bot has no tools, delegation or filesystem
scopes; it answers conversation text with a four-call limit. Its `reasoning` model
specifier must be served by your server; choose a configured model specifier
before deploying if needed. Local and server command execution are disabled.

Copy the template outside this checkout and configure its `access.accounts` with
the **existing service account owner's handle** and your human operator's handle:

```json
{
  "access": {
    "accounts": [
      {"handle": "your-discord-service-account", "role": "CONTRIBUTOR"},
      {"handle": "your-operator-account", "role": "MANAGER"}
    ]
  }
}
```

Replace both illustrative handles with real accounts. Use the service account
owner, not `@service/<token UUID>`. The shipped empty accounts list intentionally
grants nobody access. Manifest grants cap existing project membership; they do
not create it or override the token ceiling. Discord-only people are not entries
in this list and acquire no Plowshare account. An operator also needs both grants
to see/use the Application after deployment.

Deploy that copied folder through **Applications → Deploy** or the public
[Application deployment commands](../../docs/projects.md#deploy-update-and-activate-an-application).
Select project `discord`, an explicitly configured FileStore destination and a
fresh retained deployment request UUID; check deployment status before submitting,
and read the receipt after uncertain delivery. Add the service and operator
membership through normal project administration, then issue/configure the scoped
token. For an already registered project, use its existing source-management
workflow and root manifest rather than submitting a first deployment over it.

The server deploys the manifest and bot definitions. Run the external Discord
adapter process separately with the example mapping targeting that project.
Deployment does not launch the Node process. Giving the adapter checkout a
manifest would not authorize other mapped projects: every receiving Application
needs its own deployed manifest, membership and token scope. No core changes
are required for this template or the adapter.

## Optional proactive notifications

Job replies and completion notices are polled automatically. For independent
application or lifecycle events, add explicit notification routes:

```json
{
  "channelId": "100000000000000002",
  "project": "research",
  "topic": "research.completed",
  "group": "discord.results",
  "start": "LATEST"
}
```

This entry belongs in `notifications`. Its channel must already be mapped to the
same project. Configure the server's [SDK Relay egress port](../../docs/relay.md#sdk-topic-ports)
for that exact project/topic/authenticated account/group. No egress grants exist
by default, and no server configuration is changed by this adapter. Multiple
routes may observe different topics; a `(project, topic, group)` has one destination.
`start` applies to first group registration, not to every reconnect.

The adapter reads one event per batch, forwards TEXT or a bounded lifecycle
summary, saves confirmed Discord receipts, then acknowledges the exact live
batch/fence. Notification delivery never starts agent work. Redelivery finds its
saved send receipt rather than sending again. A GAP, uncertain Discord send or
uncertain acknowledgment holds that route for operator inspection, survives
restart and never auto-acknowledges missing evidence.
Relay holds are bound to `(project, topic, group)` and survive destination edits;
moving a channel cannot bypass uncertainty. Confirmed receipts still suppress
redelivery to a changed destination. Work exceeding the 30-second
Relay lease is delivered with retained receipts and acknowledged only on a later
fresh batch. Notification destinations must be approved for the event audience.

## Recovery and operational bounds

Private state uses the SDK's atomic private-file writer plus directory fsync,
with a process-exclusive `adapter.lock` directory. Only one process may own it.
Inspect a stale lock after proving the old process has stopped; never run two
instances against the same directory. Back up private state before deliberate
operator repairs. Invalid state, changed origin/principal/bot or a symlink state
file fails startup instead of creating an empty replacement.

Save occurs **before** each SDK submission/cancellation and Discord send. A crash
with an admission/send in flight becomes `UNKNOWN`. Known task IDs are read on
restart; requests and Discord sends are never automatically replayed. Duplicate
Gateway message IDs cannot submit twice. An unresolved admission/cancellation
blocks new effects in that user's context. An uncertain send blocks the remaining
chunks of that output; other contexts may continue. Changed destination mappings
or removed allowlist users fence retained delivery rather than reroute it.

There is no public incoming receipt lookup by request UUID. If the first
`incoming.receive` reply was lost, the adapter cannot invent a task ID or recover
by resubmitting. It retains an unresolved record for inspection. The same policy
holds an uncertain Relay acknowledgment; inspect broker positions and receipts
before any operator repair. Deleting uncertain records or state is not recovery.

Rotate the **same service token** to retain its principal and owning tasks; restart
with the replacement environment credential. A newly issued token has a different
principal and cannot adopt this state. Expired/revoked credentials require explicit
renewal/rotation; there is no interactive login or automatic credential refresh.
Socket reconnection verifies the same principal and performs fresh reads only.

`pollMs` defaults to 2,000 (250–60,000); `requestTimeoutMs` defaults to 30,000
(1,000–60,000). `maxRecords` defaults to 500 (1–1,000), including deduplication
receipts for settled work. Each record permits at most 100 output chunks; the
private state file has the SDK's 2 MB limit. Capacity exhaustion stops intake
before new work. Archive settled state deliberately while stopped, preserving
deduplication requirements; no automated retention deletes uncertain work.
The owned input queue is bounded to 32 operations. Overflow/offline events are
not admitted, produce an operator warning, and do not call Plowshare. Shutdown
stops intake, waits for owned work, closes connections, then releases the lock.

## Verification

```sh
./gradlew :plowshare-discord-integration:discordTest
./gradlew :plowshare-discord-integration:discordConsumerCheck
./gradlew formatCheck check
```

Typed fakes test routing, input refusal, per-user/thread isolation, receipt
ordering, duplicate admission, uncertainty, cancellation boundaries, notification
fencing and restart behavior. Filesystem tests check exclusive/private state.
Test-only Java dependencies exercise current manifest and bot loaders, including
empty grants, role ceilings and the template's absence of tool authority.
The consumer check compiles and tests against packed public SDK artifacts outside
the checkout. These checks start no database and do not prove live Discord login,
intents, channel permissions or a configured Plowshare service token. Live
acceptance requires private deployment configuration and those external services.
