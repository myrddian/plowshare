# Discord integration

One standalone Discord bot connects to Plowshare through the public Node/TypeScript
SDK. Fixed Discord channel mappings select a server project and exported bot.
The Discord Application owns its scoped service identity and token. Discord
participants do not need, acquire
or impersonate human Plowshare accounts. The server owns execution, grants,
accounting, approvals, contexts, tasks and cancellation. This module contains no
server imports, database access, core API changes or inference loop.

## Configure and run

1. Create a Discord application and bot in the Developer Portal. Enable **Message
   Content Intent**. Invite the bot only to the intended server, with View Channels,
   Send Messages, Read Message History and Send Messages in Threads for the selected
   channels. The adapter requests Guilds, Guild Messages and Message Content;
   it does not request Server Members or Presence. It does not register slash commands.
2. Configure and deploy the [Discord Application](#discord-application) as a server
   administrator. Its manifest declares an owned Contributor identity; deployment
   provisions the account, initial membership and project token scope. Explicitly
   issue its `pss_` credential through the Application controls or public
   `application.token.issue` operation. Add your human operator's manifest grant
   and matching membership so they can see/use the Application. Receiving bots
   must be exported and present in its server/project definition catalogue.
   Credentials cannot supply client-local bot definitions or local workspace presence.
3. Copy [the configuration template](config.template.json) outside the checkout.
   Replace the reserved placeholder origin, placeholder absolute state path and fictitious
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
its own retained incoming context. The first accepted message asks the server to
create that context, including a persistent receiving bot instance/conversation.
The adapter saves its returned context ID; subsequent messages pass that ID to
`incoming.receive`. Each message gets a fresh task in the retained conversation,
with previous conversation history available under the server's context projection
rules. Completing a task does not discard the context or create a new bot instance.

Bindings survive adapter restarts when private state and the same service token
principal are preserved. Another user, channel/thread, parent, project or bot gets
a separate context. Sharing a Discord channel does not share model history.
Replies stay in the original channel/thread and reference the triggering message.
Actual Discord DMs are unsupported in this version. There is no automatic thread
creation, linked-user mode, native conversation adoption, agent picker or arbitrary
project/bot selection from message text.

```text
@Plowshare Help me compare these two approaches: ...
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

## Discord Application

[application](application/plowshare.json) is the deployable server Application
named `discord`. Its root manifest and
[exported `discord_assistant` bot](application/bots/discord_assistant.md)
match the initial channel mapping. The bot has no tools, delegation or filesystem
scopes; it answers conversation text with a four-call limit. Its `reasoning` model
specifier must be served by your server; choose a configured model specifier
before deploying if needed. Local and server command execution are disabled.

This is the initial conversation capability. The shared service account does not
require the bot to stay minimal: additional exported bots, skills, tools and
project resources can use existing public contracts as their declarations and
server grants permit. Add those capabilities deliberately to the Application,
and map approved channels to their concrete bot names. Tool grants and approvals
still apply alongside account access. All Discord users execute under the same
service identity, so capabilities exposed through a mapped bot are available to
its allowlisted participants; server account permissions do not distinguish them.

Copy the Application source outside this checkout. Its initial declaration is:

```json
{
  "service": {"handle": "discord-worker", "role": "CONTRIBUTOR"}
}
```

Choose an **unused** service handle before first deployment if `discord-worker`
is already reserved. Do not pre-create the account or paste a token principal
into `executionAccount`: the server provisions and resolves both during deployment.
Keep this declaration on later updates and rollbacks; an owned identity cannot be
removed, substituted or rebound to an existing account.

Configure `access.accounts` with your human operator's real handle:

```json
{
  "access": {
    "accounts": [
      {"handle": "your-operator-account", "role": "MANAGER"}
    ]
  }
}
```

The `service` declaration already supplies the service owner's manifest grant;
repeating that handle in `access.accounts` is refused. The shipped empty accounts
list grants no human access. Human grants cap existing membership and do not
create it; add matching operator membership as needed. Discord-only people are
not entries in this list and acquire no Plowshare account.

Deploy that copied folder through **Applications → Deploy** or the public
[Application deployment commands](../../docs/projects.md#deploy-update-and-activate-an-application).
Select new project `discord`, an explicitly configured FileStore destination and
a fresh retained deployment request UUID. Check deployment status before submitting,
and read the original receipt after uncertain delivery. Deployment activates source
and initial service membership/token scope together. It returns identity metadata;
the reserved token cannot authenticate until an administrator issues its credential.

Use **Execution identity and credential** in the deployment/Application controls,
or run the following under your signed-in administrator connection for the same
server origin. Replace the request placeholder with a fresh UUID and retain it
privately **before** submitting:

```sh
bin/plowshare-cli application identity status '{"project":"discord"}'
bin/plowshare-cli application token issue '{"project":"discord","requestId":"<fresh-retained-UUID>","expiresInDays":30,"rotate":false}'
```

Save the one-time returned `credential` in the configured
`PLOWSHARE_DISCORD_SERVICE_TOKEN` secret, and retain its receipt metadata privately.
The status/receipt principal identifies the Application's stable execution owner.
The adapter checks the authenticated service principal and binds private state to it;
it does not request administrator-only identity management operations at runtime.
No secret is embedded in the manifest, adapter configuration or receipt journal.

If the issuance response is lost, read its receipt with the same server,
submitting administrator, project and original request UUID:

```sh
bin/plowshare-cli application token receipt '{"project":"discord","requestId":"<original-retained-UUID>"}'
```

Never automatically repeat issuance. A missing receipt does not prove non-admission.
If a receipt confirms issuance but the secret was lost, inspect identity status and
explicitly rotate with a **new** retained UUID and `rotate:true`. Receipts contain
metadata only. Use `application.token.issue` for all later owned-token rotations;
generic service-token rotation refuses owned tokens. Rotation preserves the token
UUID/principal, retained assistant contexts and task ownership. Source updates do
not restore revoked service membership.

This owned token's ceiling is the **Discord Application project only**. Multiple
channels and exported bots may map within that project. Other projects require
separately scoped credentials/deployments; adding membership or a manifest grant
alone does not widen this token. The adapter retains its explicit route validation
and lets the server enforce current membership, manifest grants and token ceilings.

An existing manual-token project cannot be converted in place by adding `service`.
Preserve its source, credential and private state to settle/inspect retained work.
For a new owned deployment, choose a fresh project and unused service handle,
update channel mappings to that project and configure a separate private state
directory. A new principal cannot adopt the old tasks or conversation bindings;
do not copy context IDs, delete uncertain receipts or replay old messages to migrate.
Existing manual-token adapter deployments remain supported by the unchanged SDK path.

The server deploys the manifest and bot definitions. Run the external Discord
adapter process separately with its owned credential and configured mapping.
Deployment does not launch the Node process. No core changes are required.
Linked-user execution remains deferred until the public SDK exposes acting contexts
and subject delegation; this Application declares no acting grants and continues
to execute Discord-only requests under its own service principal.

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

Rotate the **same Application-owned token** through `application.token.issue`
with a fresh retained request UUID and `rotate:true`, then restart with the
replacement environment credential. This retains its principal and owning tasks.
For existing manual-token deployments, use their existing administrative rotation
operation instead. A different token has a different principal and cannot adopt
this state. Expiry/revocation requires explicit renewal/rotation; the adapter has
no interactive login or automatic credential refresh. Socket reconnection verifies
the same principal and performs fresh reads only.

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
owned service declarations, execution-principal resolution, explicit human grants,
role ceilings and the initial bot's absence of tool authority.
The consumer check compiles and tests against packed public SDK artifacts outside
the checkout. These checks start no database and do not prove live Discord login,
intents, channel permissions or a configured Plowshare service token. Live
acceptance requires private deployment configuration and those external services.
