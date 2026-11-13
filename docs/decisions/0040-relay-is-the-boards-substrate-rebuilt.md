# Relay and the board: historical evidence

Status: superseded by [0057 — The board is an independent forum; Relay is the
messaging substrate](0057-board-forum-and-relay-messaging-substrate.md), recorded
13 November 2026 in Slice 0 of [Finishing Relay](../relay-finishing.md).
This record retains the mechanism rules, synthetic cardinality measurements and
messaging implementation findings. Its earlier board-replacement framing and
account-private audience blocker were withdrawn; they are not standing intent.

## Design lineage and implementation

The board shipped first. `V74__the_project_board.sql` gave it topics, per-seat
cursors, messages and sequence allocation under a topic-row lock. Messaging was
built on that forum transport in V93, with a private board topic per instance.
Relay was built separately in V117, using a related append-log and subscriber
position design. Shared design lineage does not make the board a broker to retire.

The board remains a forum with its own storage, threads, seats and ordering.
Messaging's board-backed transport is the part to extract: its API stays, and its
messages move through Relay agent topics. A Relay-to-board bridge connects the
systems through the board's own API. See 0057 for the governing direction.

| Area | Current implementation |
| --- | --- |
| Schedules and information events | Relay publications; names derived by their owning subsystems |
| `event.fire` | Relay publications on the server-administration scope |
| Board and message wakes | Relay carries availability references; owning inboxes retain work |
| Board forum | Its own `board_topics`, `board_messages`, `board_seats` and `board_reads`, as intended |
| Direct messaging | `BoardMessaging` and board-message records; bodies have not moved to Relay |
| Delegation | Its own orchestration records; lifecycle notices on Relay, parent delivery still in process |
| Outgoing work | Its own `outgoing_work` records and adapter claims; not board storage or Relay delivery |

This table describes inspected source, not a live deployment. The old inference
that all these stores represented unfinished board replacement was incorrect.
The forum's storage and the owning lifecycle records above Relay have independent
purposes; the remaining delivery moves must preserve those boundaries.

## Opaque payloads and the information-event correction

Relay stores the general message opaquely; the consuming family owns its codec.
A new family adds no payload kind, broker codec branch, topic-name reservation or
SDK enum value. It publishes through an existing general carrier.

V188's information-event move initially added `INFORMATION_CHANGED`. V189 removed
it, rewrote retained rows and carried the notice as `RelayPayload.Text` with an
`InformationEventCodec` envelope. That restored the then-five-kind surface.
The current six are `EMPTY`, `TEXT`, `BLOB`, `SCHEDULE_DUE`, `LIFECYCLE` and
`WAKE_REQUESTED`; `BLOB` is the general immutable binary reference added later,
not a family-specific information or messaging kind. `RelayContractTest` pins
that set. Historical five-kind statements must not be copied as the current set.

The information-event correction also exposed a redaction bypass: a typed
carrier's rendering had accidentally hidden `detail` from inspection. The fix
kept restricted detail in the owning record rather than teaching Relay to apply
information-specific policy. The reasoning and failure lessons follow.

## Relay is mechanism, not policy

Relay carries a contract. It does not interpret content and it does not enforce a consumer's rules.
"Built on top" means layers above it, not features inside it:

```
information.events.api      the surface callers use, internal or external; enforces its boundary
information.events.system   the subsystem's own records, logic and checks
Relay                       the mechanism by which the events get sent
```

A family's API is not its Relay topic. The topic is how messages travel; the API is what a caller
is entitled to see, and only the owning system can decide that. Relay has exactly one policy —
scope-level read on a topic — and that is the only policy it should ever have.

### The rule that follows

**Publish only what the topic's entire audience may see. Anything restricted more narrowly stays
server-side, and the owning API joins it under its own check.**

Relay's read surface is `relay.log`, gated at the scope: `ScopedRelayInspection.require` →
`members.mayUse`, which is any role present. So whatever is handed to Relay is visible to the
topic's whole audience by construction. A field restricted by a finer rule than that audience
cannot be protected by the broker, because protecting it is not something a content-agnostic
substrate can do — and asking it to would put policy back in the mechanism.

The corollary: **a consumer verifies its own publishers.** Relay records
`relay_publications.publisher`; deciding whether a publisher is authoritative for a family is that
family's business. Reserving a consumer's topic name inside the broker, or having the broker check a
signature over a consumer's payload, is the same mistake from the other direction.

### A topic name is private to its owning system

A Relay topic name is an implementation detail of the system that owns it, not an identifier any
other party holds. It is not part of a contract, and nothing outside the owning system should know
it, construct it, or be able to name it.

`information.changed` was the counter-example. Declared as a shared literal inside `Relay.java`, it
was addressable by anything that could publish to a project topic, and an operator could have
configured it as an external ingress port — `RelayPortProperties.permits` is pure data with no
code-owned reservation of any topic name. The name had become a contract nobody meant to offer.

So a family derives its topic name from the scope it is publishing for, inside its own package. The
derivation is deterministic, because publisher and consumer must agree independently, and opaque,
because the name carries no meaning anyone else is entitled to.

What this does and does not buy, stated plainly so it is not over-trusted:

- It keeps the derivation out of other systems' contracts. It does not prevent
  a caller who discovers a topic name from addressing it; names are not an access
  control or a reservation against a deployment port binding.
- It does **not** make the topic confidential. `ScopedRelayInspection.topics` lists a scope's
  topics, so a member can still discover the name. Opacity is an abstraction boundary, not a
  secret.
- So it is not sufficient on its own. The mechanism that actually stops a forged notice being acted
  on is the consumer verifying the publisher before decoding — the corollary above. Derivation keeps
  the name out of everyone's contract; publisher verification keeps a forgery uninterpreted. Both,
  not either.

The same reasoning applies to anything else a family might be tempted to expose through Relay's
shared surface: if another system can name it, it is a contract, and a contract in the mechanism is
policy by another route.

### Interfaces first, applied to a substrate

This is the general rule — interfaces first — read against a shared mechanism. A family's interface
is its own API. Relay is the transport beneath it, and every detail of how a family uses Relay
(its topic names, its payload encoding, its publisher identity, what it chooses to put in a
publication) belongs behind that family's interface rather than in Relay's surface.

Each failure recorded here is the same omission: a detail that should have been private became
shared. A payload kind in the broker's enum, a topic name in the broker's domain record, an
owner-only field in a publication the broker serves to the whole audience. None of them were
decisions anyone defended on the merits; they were details that leaked upward because no interface
was holding them down.

### Both halves were learned from live defects

- **A redaction bypass.** `information.events` withholds an event's `detail` from every account but
  the home resource's owner. The notice was published with `detail` inside it, so a project member
  who did not own the resource could read it through `relay.log`. The audience was always entitled
  to the notices — just not to that field on resources they do not own. The fix was not to teach
  `relay.log` about ownership: `detail` no longer enters a publication at all, and the owner reads
  it from `information_revision_events` under the owning API's check.
- **Accidental opacity is not a guarantee.** While the notice had its own payload type, `relay.log`
  reported the family's *name* only, so `detail` was hidden — and a comment on `RelayLog` asserted
  the redaction "must not be bypassed by inspecting the log". That protection was a side effect of
  a payload type that should not have existed. Removing the type removed the accident and revealed
  the real exposure. Confidentiality must never rest on the shape of a carrier.

Two further consequences worth keeping:

- Keeping restricted fields out of the broker also keeps **deletion honest**. A publication is
  immutable and outlives the record it describes by the topic's retention, so a field published
  there survives the deletion of its source. `detail` living only in `information_revision_events`,
  which cascades from the revision, means deleting a revision deletes its detail.
- A consumer should **skip** a publication it did not issue and let its cursor advance, rather than
  refuse the page: a single foreign row must not be able to wedge a subscriber's feed for a whole
  retention window. A publication the consumer *did* issue that will not decode is different — that
  is corruption of its own writing and should fail loudly.

## Clarifications this record exists to pin down

- **Inspired by Kafka; not Kafka.** The correspondence that holds: `subscriber` is a consumer group,
  `seen_through` is its committed offset, `expired_through` is the log start offset, and gap
  recovery is the out-of-range case. **Partitions are a deliberate non-goal**, not a shortfall —
  traffic here is peer-to-peer and needs no partition key. They could be added; nothing currently
  wants them. An analysis that reports "no partitions" as a deviation has mistaken a choice for an
  omission.
- **An agent inbox is a Relay topic.** Messaging owns one per (account, project,
  definition name) and dispatches to instances identified in the payload. Instance
  and conversation records are messaging concerns, not separate Relay topics.
  Broadcast families use their own scope topics and independent subscribers.
- **A consumer group is shared state.** Two consumers under one group name divide the stream and
  either can commit past events the other never read. This is why decision 0038 namespaces a
  read-tier subscriber's group per account, and it is a Kafka-derived property, not a Relay quirk.

## Topic cardinality: historical measurements and family-specific granularity

This has been asked twice in design discussion and answered by guesswork both times, so the
measurements are recorded here. They come from a synthetic PostgreSQL 16 carrying the two tables'
exact DDL, not from the repository suite, and the extrapolations past 600k topics are linear from
two points.

**Reads do not care how many topics exist.** `topic` is a leading column of
`relay_publications`' primary key (`scope_key, topic, position` — `scope_key` since V120 renamed
and retyped it), so one topic's page is an index range scan: 0.086 ms and 11 buffers at 600k
topics.

**Retention is where cardinality bites, and it is not the topic count that drives it.**
`topicsToPrune` asks which topics have an expirable prefix by joining each `relay_topics` row to
its oldest unexpired publication, and the defect is that **due-ness is derived across two tables,
so no index can answer it**. The join itself is an exact primary-key probe on
`(scope_key, topic, position)` — one row per topic, not a cartesian product. So the cost is
**O(live topics) plus a sort, on every poll, forever**.

At 600k topics over 3M publications the planner switches to a parallel hash join and the poll
measures **1,558 ms, 61,359 buffers, roughly 500 MB of temp traffic, to return 100 candidates** —
but that is a plan change at scale rather than the shape of the query, and an earlier version of
this record wrongly generalised it to "cost tracks the whole publication log". Forcing the
index-probe plan on the current query is worse still (803,212 buffers).

**The gating number is retention, not topic count.** Oldest-first ordering gives each eligible
topic a lag proportional to the eligible set, so overshoot stays within the retention window only
while the eligible count stays near `1.667 × retention_seconds`. At the four-day default that is
around 576,000 topics — but **about 500 at a five-minute retention and about 100 at sixty seconds.**
A topic per conversation with short retention fails in the hundreds, not the hundred-thousands.

**Slice 4 implementation update, 13 November 2026:**
[0062](0062-relay-topic-and-group-retirement.md) implements the maintained-deadline
correction with bounded partial-index reads, owner-requested physical retirement
and SDK group deletion. The measurements and absence described below are historical
evidence; they are not the current candidate query or retirement capability.

**Nothing garbage-collects a drained topic.** The only delete is operator-initiated and refuses a
topic that still has a subscription or a retained publication. So short-lived topics leave a
permanent residue that every later sweep reads. The cost of churn is paid after the churn,
indefinitely. That absence is the real capability gap, not the sweep's shape.

### The conclusion, and it follows the rule above

**Broadcast families use a subscriber per principal on a shared topic.** This is
not a prohibition on messaging's agent inbox topics. Decision 0038 put the
broadcast reader's per-principal entity in `relay_subscriptions` — `reader:<account>:<group>` names, a
`reader_account` column, an index on `(scope_key, reader_account)` — and read-tier consumption is a
bare range read with no admission row and no payload copy. The sweep scans **topics**; it never
touches subscribers. Decision 0037 made exactly this call for information events: one topic per
scope, audience from subscriptions, deliberately replacing a per-recipient substrate.

Per-publish writes are not a concern either way: `relay_topics.last_position` and
`relay_subscriptions.seen_through` are single-row HOT-eligible updates, and higher cardinality
*reduces* contention on them.

### If Relay ever must carry topic cardinality itself

- The measured fix is an application-maintained deadline column with partial indexes: **0.041 ms
  and 104 buffers against 1,558 ms and 61,359** — about four orders of magnitude. The `OR` and the
  single `ORDER BY` are what force the bad plan; two index-driven queries merged in the application
  is the shape that works.
- A generated column or expression index cannot express it: `timestamptz + interval` is STABLE, not
  IMMUTABLE, so PostgreSQL refuses both.
- **Partitioning is a dead end** — it does not change the sweep's cost shape and it collides with
  the contiguous-retained-prefix invariant.
- **Retuning the knobs is a dead end** — the topic batch size is hard-capped at 100 in two places.
  Recorded so it is not attempted a third time.

### Deferred, and why

`RelayMaintenance` schedules the sweep with a bare fixed delay and has no lease or leader election,
unlike leased consumption, which is carefully fenced. On a multi-node deployment every node would
run the whole sweep and the scan cost would multiply by node count. **There is no cluster setup, so
this is not a current consideration** — recorded only so it is not rediscovered as a surprise if one
ever appears.

Two smaller findings from the same analysis: `relay_topics.project_id` is the family's only
unindexed foreign-key child column, so cascading one project costs a sequential scan (50 ms at
600k topics); and `RelayWorkers` refuses past 256 subscriptions per node, which is a separate wall
for any work-tier per-principal design.

## Consequences for the work that remains

- The remaining messaging move is an extraction from the board's transport,
  behind the existing messaging API. Board storage remains the forum's own.
  The bridge connects the forum to Relay rather than replacing it.
- **Messaging's ordering is weaker than Relay's, not equivalent to it.** An earlier version of this
  record claimed `Board.post` takes `FOR UPDATE` on the topic row and reads per-topic cursors.
  That is false, and the error flattered the current design. Verified instead:
  `BoardStore.lockTopic` is called only from `Board.java`'s public-board paths, never on the
  messaging send path; `board_messages.seq` is `BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE`
  (`V74:72`) — a **global** sequence, structurally the construct 0037 condemns in
  `information_events.sequence`; and messaging reads **no cursor at all**, taking work by
  `arrived_at` through `firings.oldestWaiting`. What it does have is a **per-mailbox** advisory
  lock: `lockMailbox` → `pg_advisory_xact_lock(hashtextextended(?,0))`
  (`JdbcBoardMessagingRepository:112`), which is at least scoped per mailbox rather than
  installation-wide.

  So **moving messaging onto Relay would improve its ordering rather than preserve it**, because
  Relay's per-topic row lock is precisely what the messaging path lacks. It is still not primarily
  a performance case — nothing has been measured failing — but it must not be argued as "already
  sound".

- **Messaging's real invariants are unique partial indexes, and they are stronger than a lease.**
  `board_message_one_handling_request` on `board_message_routes(recipient) WHERE started_at IS NOT
  NULL AND handled_at IS NULL`, and `firings_one_running_per_target` on `firings(target) WHERE
  status='started' AND finished_at IS NULL`. These are unconditional invariants with no time
  dimension; a Relay consumer lease is a timed claim resolved by expiry. Any migration must keep
  them rather than substitute a lease for them.
- **The former audience blocker is withdrawn.** Messaging's agent topics are
  project-readable by decision 0057, under Relay's existing scope policy. Current
  account-owned conversation and `message.*` reads keep their checks; they do not
  imply a new Relay privacy category. V126 still publishes message lifecycle
  notices on the system scope in the current implementation. That workaround is
  not the intended project audience and must be addressed by the later move.
  Decision 0039's genuinely non-project audience gap remains separate.
- **No further family-specific payload kinds.** The next family publishes the
  general message with its own codec. The existing six-kind surface does not
  block that extension and this correction proposes no carrier removal.

## Related records

- [0057](0057-board-forum-and-relay-messaging-substrate.md) — governing board,
  messaging, inbox and visibility direction, superseding this record.
- [0058](0058-a2a-rebuild-on-relay.md) — A2A rebuild direction, superseding 0008's
  unshipped external-work plan. The current outgoing implementation is unchanged.
- `0037-events-and-messaging-on-relay.md` — the general delivery rule, amended
  to distinguish messaging extraction from the independent board forum.
- `0038-relay-read-tier-subscribers-project-scoped.md` — the read tier, implemented.
- `0039-relay-audiences-beyond-a-project.md` — the audience gap, reframed against this history.
- `0001-integration-boundary.md` — the rule that an integration needing core changes is a failed
  integration task. Relay's generalisation is entangled with A2A's core growth; the commits carry
  empty bodies, so causation is inferred from sequence and not established here.
