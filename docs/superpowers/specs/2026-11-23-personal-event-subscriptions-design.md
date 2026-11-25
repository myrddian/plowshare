# Personal event subscriptions

Draft design, 23 November 2026.

A Personal subscription lets an account owner choose which published events run
work in their Personal space. The owner selects a source and a handler in a
Personal configuration file. An Application that already publishes an event
the owner can read can be integrated through that file alone. Plowshare owns
authorization, delivery and recovery. Application names, schedules, filters and
handlers are configuration.

The first use case is a scheduled price-research task. Once the result reaches
the owner's Mailbox, a Personal skill reads that exact report, compares it with
the saved price history and records meaningful changes. The user can still open
and acknowledge the report themselves. Mailbox here is the account inbox exposed
by `inbox.list`. A scheduled occurrence becoming due, its
job ending and its report arriving in the Mailbox are three separate triggers.
The last one fits this workflow. [Schedule delivery](../../scheduling.md#actions-and-destinations) · [Relay lifecycle publishers](../../relay.md#lifecycle-publishers-and-native-wakes)

## Personal configuration

Proposed file at `Resources/subscriptions.json` in the authoritative Personal
workspace. The two subscriptions illustrate the first Mailbox source and a
subsequent source using an existing project topic.

```json
{
  "version": 1,
  "subscriptions": [
    {
      "id": "track-tool-prices",
      "source": {
        "kind": "mailbox",
        "event": "item.added",
        "schedule": {"project": "research", "name": "tool-price-research"},
        "ending": "ANSWERED"
      },
      "handler": {"kind": "skill", "agent": "assistant", "name": "update-price-tracker"},
      "paused": false
    },
    {
      "id": "summarize-reviewed-invoices",
      "source": {"kind": "relay", "project": "invoices", "topic": "invoice.review.completed"},
      "handler": {"kind": "skill", "agent": "assistant", "name": "update-invoice-tracker"},
      "paused": true
    }
  ]
}
```

- `id` is stable within Personal. Admission resolves source names to their
  canonical identities. A schedule selector uses existing schedule resolution
  and must resolve uniquely. Topic bindings pin the project and topic generation.
  A source change requires a new subscription ID so its cursor has a clear origin.
- A handler names an eligible Personal agent and a skill that agent may invoke.
  Resolve the command using the existing skill catalog and execution mode. The
  initial handler uses a separate background conversation. A skill can leave a
  durable briefing for the next foreground chat.
- The authenticated Personal owner activates an exact validated file revision.
  This is a one-time step for each revision, using a preview of sources,
  destinations and run limits. An agent or local sync process can prepare an edit.
  Only the owner's activation can arm it. The server stores the active revision
  and delivery state so dispatch can continue with the desktop offline.
- Activation captures the current source position for each new subscription.
  The Mailbox service registers its reserved topic on first use, including for
  an empty Mailbox. A project-topic subscription binds an existing topic. Pause
  stops admission and new handler starts. Resume continues from the retained
  position, subject to retention and current authority. Removing an entry in an
  active revision retires the binding and its queued starts. Already started
  runs retain their source revision and use the owning runtime's cancellation and
  authority rules.

The server validates a versioned file with unique IDs, bounded subscriptions,
exact source selectors and typed handler arguments. New activations fail as a
unit if any entry is invalid. It reports draft validation errors beside the active
revision. Source text enters the handler as evidence. Handler selection and
authority come exclusively from the active configuration and existing grants.

## Event sources and outputs

| Source | Proposed trigger and authority |
| --- | --- |
| Account Mailbox | First built-in source. A committed eligible item in the owner's Mailbox wakes a matching subscription. A schedule-result selector matches the stable schedule identity and actual ending. |
| Existing project Relay topic | Personal follows one exact topic it has permission to read. Process the existing payload contract for allowlisted topic families. A project can keep its own subscribers and cursor. |
| Application inbox | Add the same item-added contract to project-owned output. A subscriber needs current read authority on the Application inbox and the item. A project item and a Manager's personal copy have distinct identities. |
| Job, orchestration, information and Board events | Expose stable, eligible lifecycle sources through the same subscription contract. Resolve references through the owning subsystem. Each event keeps its existing audience and source-specific rules. |
| External integrations | An authorized SDK ingress port can publish to a project topic. Personal uses the same project-topic selector and authority checks for that topic. |

Schedule due events describe a timer occurrence and job-end events describe a
terminal run. Both are separate from the result being available for a reader.
Application schedule receipts can also precede the workflow's actual findings.
Use the owning workflow's output event when Personal needs that outcome.
Use addressed delivery for private messaging and the owning human decision flow
for approvals and questions. A socket count update is a client refresh signal.
Other subsystems can supply a new source by publishing a durable, authorized
transition. [Application inbox ownership and attention](../../decisions/0072-application-inbox-ownership-and-attention.md) · [Relay scopes and audiences](../../relay.md#lifecycle-publishers-and-native-wakes) · [SDK topic ports](../../relay.md#sdk-topic-ports)

A handler can write an authorized Personal file or knowledge record, prepare a
briefing, message an allowed destination, or publish to an exact granted Relay
topic. An integration can consume that publication through its authorized egress
port. Those outputs use existing tools and approval rules. `agent_list` helps the
handler resolve messaging destinations when it has the tool grant. Sensitive
source inputs retain their applicable disclosure and dependency restrictions on
derived outputs. [Personal workflows](../../personal-space.md#managing-personal-through-existing-capabilities) · [Agent and orchestration publishing](../../relay.md#agent-and-orchestration-publishing) · [Information read dependencies](../../decisions/0067-information-discovery-and-read-selection.md)

## Notify a Personal bot after delivery

Schedule option for a schedule in the owner's Personal workspace:

```json
{
  "target": {"kind": "mailbox"},
  "notifyPersonalBot": {"route": "personal-alerts"}
}
```

The optional field selects a named route to a bot in that same Personal
workspace. The route can pin the user's existing conversation. The schedule
runs its configured action and the Mailbox retains the result with its original
read state. A committed result queues an alert containing a reference to that
item. Messaging wakes an idle destination and waits for a busy conversation
to become free. [Schedule destinations](../../scheduling.md#actions-and-destinations) · [Named routes](../../internal-messaging.md#keep-a-routes-conversation)

The same alert can be configured as a Personal reaction by using a
`{"kind":"message","route":"personal-alerts"}` handler in
`Resources/subscriptions.json`. The owner selects the source and activates the
reaction. Use this for output from an existing Application. Application schedule
files keep their project audience, and each eligible user can decide which
events should reach their own bot. Where the schedule starts an orchestration,
subscribe to the actual result when the initial receipt only confirms a start.

Schedule delivery retains the selected route atomically with the Mailbox item.
Reactions admit alerts from the retained source event. Both use existing message
delivery and recheck source visibility before queueing. A retry for the same
retained item and route can recover the accepted message. Alert status is
independent of schedule success and Mailbox delivery.

For a scheduled Mailbox item, the receiving turn gets `inbox_alert_read` bound
to that item and its owner. The tool preserves read state and retains the
result's information dependencies on the receiving conversation. An Application
inbox alert carries the result reference. Interactive `inbox_read` continues to
acknowledge the ordinary items it returns.

## Mailbox publication

The inbox repository writes an outbox entry in the same transaction that first
commits an eligible item. A failed transaction rolls back both rows. Give new
scheduled results a stable delivery identity keyed by destination, result kind
and firing ID. An identical retry reuses the retained inbox item. Conflicting
reuse fails and preserves the original report. A publisher appends a reference
to Relay with an event identity derived from the inbox item. Its audience is the
owner's existing Personal project scope. Mailbox delivery can complete without
publication for an account whose Personal project has yet to be created.
The Application inbox extension uses the owning project's scope and authorizes
each referenced item through the Application inbox.

The native event carries the item ID, item category, occurrence time, and retained
source identifiers where applicable. A scheduled result carries the firing and
stable schedule identity from the owning occurrence. The result body stays in
the inbox. The native source validates the publisher identity as well as the
event shape. Use the existing server-owned publication convention so the
subscription accepts only the Mailbox publisher for this native source.
Private log and document references keep their own read checks.

The item-added event is tied to creation. Duplicate delivery with the same item
identity yields the same publication. Reading or settling an item leaves that
event identity alone. The handler reads the exact authorized item through a
read-only internal inbox capability that leaves human read state independent.
The existing model `inbox_read` tool consumes ordinary unread items and serves a
different interaction. [Inbox delivery and read state](../../../plowshare-server/src/main/java/io/aeyer/plowshare/server/events/Inbox.java) · [Inbox tool](../../../plowshare-server/src/main/java/io/aeyer/plowshare/server/events/InboxTool.java) · [Relay publication and audience decisions](../../decisions/0037-events-and-messaging-on-relay.md)

## Authority and durable delivery

1. **Bind two scopes.** Derive the subscriber account from the authenticated
   owner and its Personal project from server ownership. Authorize the source
   with topic and resource read rules. Authorize the handler in Personal with
   the existing execution, tool, budget and workspace rules. A project Viewer
   can follow an eligible event they can read while any new work runs in
   Personal. The publisher's service account stays on the source side.
2. **Admit one delivery.** Under a bounded consumer lease, evaluate the filter,
   pin the approved handler revision and retain delivery intent before moving the
   source position. Keep the position and admission atomic, including a filtered
   event that selects no handler. Deduplicate by Personal project, subscription
   ID, source topic generation and event ID. Keep admitted work recoverable after
   topic retention. Pinning a new config revision leaves existing delivery keys
   intact.
3. **Start through the owning runtime.** Pass one retained delivery identity
   to the normal agent and skill start path. Recheck the active binding, live
   source read and eligible Personal owner before disclosure or a new start.
   Recover an uncertain start from its owning receipt. A confirmed prior start
   links the original job, including after a worker restart. A grant withdrawal
   blocks queued work. A blocked binding requires explicit owner reactivation
   after access returns. An unavailable item settles its delivery with a visible
   source-unavailable status. An expired source segment or replaced topic
   generation requires an explicit gap decision. Admission, job start and job
   completion have distinct receipts and statuses.
4. **Bound fan-out and resulting work.** Apply configured limits per owner and
   subscription to pending work, dispatch rate, concurrency and model usage.
   Default to one active handler per subscription. When its queue fills, pause
   admission at the current position and resume as capacity returns. Use
   existing backpressure and finite budgets at their owning boundaries.
   Carry verified causation and the existing Relay effect limits into follow-up
   publications. Match the exact schedule in the first Mailbox slice so a
   subscription's own briefing cannot wake the same subscription.

Relay already has read-tier subscription authority, leases, pinned admissions
and topic generations. Native project routing today binds its subscription to
the project's topic. Add a Personal-owned admission path that can consume a
readable source scope and target only the subscribing owner's Personal project.
Reuse the existing Relay delivery and receipt primitives where their scope
contracts permit. The owner of a broker position, the reader of the source, and
the executor are bound explicitly in retained state. [Read-tier subscriptions](../../decisions/0038-relay-read-tier-subscribers-project-scoped.md) · [Relay routing](../../../plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/RelayRouting.java) · [Atomic admission](../../../plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/RelayDeliveries.java) · [Topic retirement and generations](../../decisions/0062-relay-topic-and-group-retirement.md)

## Implementation and acceptance

| Slice | Acceptance |
| --- | --- |
| 1. Personal Mailbox results | Activate the price-tracker example and run the schedule through the normal Mailbox path. The exact result starts one Personal skill, the original can still be acknowledged by the human, and failed runs or other schedules can be excluded by filters. Verify a duplicate publication, a restart between admission and job acceptance, and a pause with work queued. |
| 2. Existing project topics | Add a subscription from Personal to a topic in an already running Application through Personal configuration and activation. Verify a viewer can read an eligible publication and run in Personal. Revoke read authority with work queued and verify the run cannot read or act on the source. Check isolation between two owners and test a recreated topic and retention gap. |
| 3. Additional built-in sources | Add Application inbox results and selected lifecycle sources with tests for each source's audience and identifier contract. Verify that the same Application result can be followed in the project inbox or via the recipient's attention copy, with each subscription receiving its selected copy. |
| 4. Board and information | Follow public Board posts and resolutions and explicit report finalisations. Check the exact Board message or information revision before dispatch. Retain the information revision's read dependency on the resulting Personal log and verify a project Viewer without revision access cannot trigger the skill. |

Keep one run trace from subscription ID and event reference to disposition and
job receipt. Show active revision, source, current state, last event and any
actionable block. Scope inspection to the Personal owner and apply current read
checks to linked source content. Use mocked owning capabilities for routing and
authority tests, plus selected PostgreSQL tests for transaction rollback,
deduplication, leases and cursor recovery. Run an end-to-end scenario with two
accounts before deployment.
