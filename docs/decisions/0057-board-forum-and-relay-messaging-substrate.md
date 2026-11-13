# The board is an independent forum; Relay is the messaging substrate

Status: accepted direction, recorded 13 November 2026 as Slice 0 of
[Finishing Relay](../relay-finishing.md). This is a documentation correction;
the messaging extraction and bridge have not shipped.

Supersedes [0040](0040-relay-is-the-boards-substrate-rebuilt.md). Amends the
interpretation and sequencing in [0037](0037-events-and-messaging-on-relay.md)
and [0041](0041-waiting-is-turn-eligibility.md). The historical measurements and
implementation findings retained in 0040 remain evidence, with their stated
limits; its board-replacement and mailbox-audience conclusions do not govern work.

## Problem and decision

The board and Relay share design lineage: both retain ordered records and
subscriber positions. That resemblance was incorrectly recorded as a plan to
replace board storage with Relay. Messaging's use of private board topics was
then treated as the intended design, and account-private mailbox visibility was
treated as a blocker to moving it.

The owner's direction is:

- **Relay provides durable publish/subscribe.** Topics, publications and
  subscriptions are its mechanism. Family APIs and policy live above it.
- **The board remains an independent forum.** Its topics, threads, seats,
  ordering, storage and swarm deliberation belong to the board. They are not a
  delivery subsystem to retire or rebuild onto Relay.
- **Messaging is a façade on Relay.** Keep `message.*` operations, events and
  lifecycle notices, the `send_message` tool, and existing client/SDK contracts.
  Extract the implementation from the board's transport behind those contracts.
- **An agent inbox is a Relay topic.** One topic per (account, project,
  definition name), with messaging dispatching to the instance addressed in the
  payload. Conversations and instance lifetimes remain messaging records rather
  than topic boundaries. Replies return to the original sender's agent topic,
  addressed through the retained request route.
- **Visibility is project-wide.** Project members may read an agent topic under
  Relay's existing scope-level read policy. Topic-name opacity does not confer
  confidentiality. Personal projects retain their existing owner check;
  cross-project sends retain the two-sided routing policy and account access
  checks. Publishing a body does not grant access to its source conversation,
  documents or tools.
- **A Relay-to-board bridge joins the two systems.** It posts to or opens a
  board topic through the board's API; replies return through Relay. The bridge
  does not replace the forum's storage or sequencing.

The pipeline is `send_message` → Relay → recipient agent topic → messaging →
addressed instance. Reply-to and correlation belong to the messaging payload,
never a new Relay envelope field. The payload is a versioned, validated DTO
encoded as the existing general `TEXT` carrier, not a new broker payload kind.
The typed payload contract is now implemented as a staged internal boundary in
[0059](0059-typed-messaging-payload-contract.md), under the separate Slice 1
authorization. Bridge and cutover contracts remain later work; live messages
still use board transport.

## Mechanism and policy remain separate

The useful rules from 0040 continue to apply:

- Publish only fields the entire topic audience may read. More restricted fields
  stay in their owning records and are read through the owning API. A retained
  immutable publication can outlive deletion or revocation of the source.
- Consumers verify authoritative publishers before decoding. Skip foreign
  publications without wedging the feed; fail visibly on corrupt publications
  from the consumer's own publisher.
- A family's derived topic name and codec stay behind its interface. An opaque
  topic name is an implementation boundary, not a secret or permission check.
- No family adds a payload kind. The current six are `EMPTY`, `TEXT`, `BLOB`,
  `SCHEDULE_DUE`, `LIFECYCLE` and `WAKE_REQUESTED`, pinned by `RelayContractTest`.
  `BLOB` is a general binary reference, not a messaging-specific feature.
- Arrival belongs to Relay; eligibility belongs to turn scheduling; wait legality
  belongs to the owning system, as 0041 records. Relay does not park an agent.

Project-wide message-body visibility is a decided audience, not an unresolved
mailbox-privacy blocker. Existing `message.*` inspection and conversation reads
still enforce their current account and input checks. The future project topic
will additionally expose its published body to project members through Relay;
Slice 3 must test that deliberate Relay visibility while preserving the owning
API's authorization checks. Finer resource visibility, if required later, is a
permission-system task, not a new Relay policy.

## Evidence and current implementation

The inspected checkout still has:

| Area | Current owner and mechanism |
| --- | --- |
| Board forum | `board/BoardStore.java` and `V74__the_project_board.sql`; its own messages, topic lock and sequencing |
| Direct messaging | `board/BoardMessaging.java`, `board_message_instances`, `board_message_routes` and board posts; a private board topic per instance |
| Messaging agent seam | `agents/SendMessageTool.java` consumes the messaging contract; callers need no transport change |
| Wakes and lifecycle notices | Relay already carries board/message wake references and lifecycle notices; it does not yet carry direct-message bodies |
| Project log visibility | `relay/ScopedRelayInspection.java` checks `members.mayUse` and the exact Personal owner |
| Read-tier subscriptions | `relay/ReadTierRelayConsumption.java` and V187 implement 0038; work-tier ports and dispatch retain their work authority |
| Delegation | Its own orchestration records and in-process parent delivery; the published `orchestration.child.completed` notice still needs a consumer |
| External work | `outgoing/JdbcOutgoingRepository.java` owns `outgoing_work`; it is not stored in board tables and has not moved to Relay |
| Route receivers | Forward publication, agent run, script run and orchestration start; no board bridge yet |

Java paths above are relative to
`plowshare-server/src/main/java/io/aeyer/plowshare/server/`; migrations are under
`plowshare-server/src/main/resources/db/migration/`. These are source findings,
not a deployment claim. The source comment on `BoardMessaging` must continue to
describe the board-backed implementation until the extraction changes it.

## Cardinality and lifecycle constraints retained

0040's synthetic PostgreSQL 16 measurements remain useful, not production
capacity guarantees. At 600,000 topics its recorded single-topic read was
0.086 ms and 11 buffers. Its retention poll over three million publications was
1,558 ms, 61,359 buffers and roughly 500 MB of temporary traffic for 100 candidates.
The recorded eligibility estimate was approximately `1.667 × retention_seconds`:
about 576,000 eligible topics at four days, 500 at five minutes and 100 at a minute.
The deadline/index prototype measured 0.041 ms and 104 buffers; it is not shipped
by this decision. See 0040 for assumptions, query shape and deferred sweep work.

Those findings constrain topic lifetime and retention. They do not prohibit an
agent inbox topic. Broadcast families such as information events keep one topic
per scope and independent subscriber state; messaging has one inbox per agent,
not one per conversation or transient instance. Topic/group retirement and the
sweep fix remain Slice 4. Instance churn must not create topic churn.

Messaging's current send path uses a per-mailbox advisory lock and global board
message sequence, not Relay's commit-ordered topic append. Moving it improves
transport ordering. Preserve the one-handling-request and one-running-target
database invariants: an expiring consumer lease is not a replacement for them.

## Consequences and order of work

Slice 0 corrects the record only. Slices 1–3 define the messaging payload, build
the bridge and extract messaging while preserving its API. Messaging allowances
remain per instance/message, with policy and approval top-ups, rather than a
shared board pot. The cutover includes retained messages and threads and has no
dual running; its migration and compatibility tests are later implementation work.

Delegation's direct Relay consumer can proceed under 0041 without going through
messaging. Its single leased consumer group and eligibility requirements remain;
there is no audience-based rule requiring delegation to precede messaging.
Non-project audiences remain the separate deferred scope-vocabulary issue in
[0039](0039-relay-audiences-beyond-a-project.md).

The A2A rebuild direction is recorded in
[0058](0058-a2a-rebuild-on-relay.md). It depends on the earlier messaging/bridge
contracts and preserves [the integration boundary](0001-integration-boundary.md).
This documentation change implements none of those later slices.
