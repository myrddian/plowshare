# Relay is the Board's substrate, rebuilt

Status: recorded history and standing intent. No code change is proposed here. It exists because
this lineage is not written down anywhere, and its absence has twice caused unfinished work to be
mistaken for neglect, and once caused a decision record to be framed against the wrong problem.

## What happened

**The Board shipped first and became a substrate.** `V74__the_project_board.sql` gave it topics
(`board_topics`), per-seat cursors (`board_seats.seen_through`), an append log (`board_messages`,
later `.seq`), and position allocation under a topic-row lock —
`SELECT id FROM board_topics WHERE id=? FOR UPDATE` (`BoardStore.java:287`). Messaging was then
built on top of it, and more was added after that.

**Rather than retrofit the Board into a general substrate, Relay was built fresh and in parallel.**
`V117__relay_log.sql` is a clean reimplementation of the same mechanism: `relay_topics`,
`relay_subscriptions.seen_through`, `relay_publications.position`, and the same topic-row
`FOR UPDATE` allocation. Its first line records the relationship as independence — "Relay is
independent of Board transport" — which is a true statement about v1 not *depending* on v0, and has
since been misread as a denial of descent.

The descent is plain in the artefacts: the same design, the same lock, and the same column name
`seen_through` in both `V74` and `V117`, forty-three migrations apart.

So: **the Board substrate is Relay v0, and Relay is v1.** Relay was meant to be the substrate the
same way the Board became one — a Kafka-inspired publish/subscribe layer that other systems use and
build what they need on top of.

**The intent was a complete move.** Board and messaging were to be rebuilt on v1 and the v0
substrate retired. All SDK messages are meant to travel over Relay.

## Where it actually stands

Partial, and it has been partial since the week both existed.

| on Relay v1 | still on v0 |
|---|---|
| schedules — `RelayScheduledPublication`, the only `ScheduledPublication`, wired unconditionally | board storage and transport — `board_topics`, `board_messages`, `board_seats`, `board_reads` |
| information events — decision 0037, V188 | messaging — `BoardMessaging.java` (1,679 lines) in `server/board/`, on `board_message_instances`, `board_message_routes`, `board_message_route_bindings` |
| v0's **wake** traffic — `board.wake.requested` and `message.wake.requested`, published by `RelayInternalWorkers` | everything else board and messaging own |

Messaging got its own API and lifecycle, but its storage and transport were never extracted; no
extraction commit exists. The wake traffic having already moved is the clearest evidence that the
replacement was begun and left unfinished rather than reconsidered.

## Why it stalled, and the part that is drift rather than design

**Relay is content-agnostic by intent. It should not know what a payload means.** In principle the
one general message kind is all it ever needed; a consumer interprets the content it receives.

What is in the tree does not match that. `RelayPayload.Kind` carries `EMPTY`, `TEXT`,
`SCHEDULE_DUE`, `LIFECYCLE`, `WAKE_REQUESTED` and `INFORMATION_CHANGED`; `RelayPayload` states the
cost — each new system topic needs a validated DTO and a codec version — and `Relay.java:147,152`
hard-codes the topic literals `message.wake.requested`, `board.wake.requested` and `schedule.due`
so that a topic name must agree with a payload kind.

**That accumulation is drift, not a design property.** Each family was given a type instead of
using the general one, and the operator has since instructed that it stop: Relay does not care what
the content is, and should not. So the earlier reading of this record — that Relay "cannot be
extended from outside" and a migration is "a core change by construction" — described the symptom
as though it were the architecture. It is not. A family that publishes the general message needs no
enum value, no codec case, no CHECK migration and no SDK change.

The practical rule that follows: **a new family adds no payload kind.** If a family appears to need
one, the content belongs in the payload and the interpretation belongs in the consumer. The
topic-name-to-payload-kind agreement constraints exist only to police the kinds, and they become
unnecessary as the kinds go.

### This record's own author did it too, and it has been undone

The information-event cutover (V188) added `INFORMATION_CHANGED`: a sixth enum value, a codec case,
two CHECK constraints, and a widening of the `kind` allowlist across five SDK bindings. It should
have published the general message and let the consumer interpret it.

It has since been removed. The notice is now `RelayPayload.Text` carrying an
`InformationEventCodec` envelope, following `RelayToolCodec`'s established pattern; V189 restores
the five-family CHECK constraints and rewrites stored rows. The SDK surface went back to five kinds
— a net removal — which also deleted the version-skew risk that an un-updated client would reject an
information log page.

The removal is also what surfaced the redaction bypass recorded above, because the typed payload had
been hiding `detail` from `relay.log` by accident. So the drift did not merely cost a core change:
it concealed a real exposure behind a type, and the exposure only became visible when the type went.

Recording it here rather than quietly is the point: the drift is easy to repeat, because adding a
type feels like modelling the domain properly, and the cost only shows up as the next family's
core change.

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

- It stops the topic being **addressable**. Nothing can hardcode it, configure a port for it, or
  publish to it by name, because nothing else can compute it.
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
- **Mailboxes are not a Relay construct.** They exist, and they are a separate thing. Relay's
  audience question must not be reasoned about from mailbox cardinality; conflating the two makes
  Relay look as though it needs per-mailbox topics when the requirement comes from elsewhere.
- **A consumer group is shared state.** Two consumers under one group name divide the stream and
  either can commit past events the other never read. This is why decision 0038 namespaces a
  read-tier subscriber's group per account, and it is a Kafka-derived property, not a Relay quirk.

## Consequences for the work that remains

- The remaining work is **finishing a replacement**, not migrating a foreign system onto a new one.
  Board and messaging run the previous generation of the same substrate.
- **Messaging's ordering is already sound and already Relay's mechanism** — `Board.post` takes
  `FOR UPDATE` on the topic row and then inserts, with per-topic cursors. There is no measured
  pathology there and no recipient fan-out. Moving it is consolidation; it is not a performance
  fix, and it should not be argued as one.
- **The blocker is the audience, not the log.** Messaging addresses a single principal. Relay's
  `ProjectScope` is too coarse — any member who may use the project can inspect the topic log — and
  the substrate has no conversation- or instance-keyed scope kind either. `V126` already met this
  and worked around it by publishing with a NULL project, which resolves to
  `SystemScope.SERVER` and therefore to server administrators only. Decision 0039 rejects that
  workaround.
- **No further payload kinds.** The next family publishes the general message. Nothing about
  extensibility needs to be designed first; what needs to stop is adding types, and the existing
  six are a cleanup rather than a blocker.

## Related records

- `0008-relay-external-work-and-a2a-mapping.md` — already decided the outgoing family moves onto
  Relay, in eight slices. None shipped: `server/outgoing/` has no Relay reference.
- `0037-events-and-messaging-on-relay.md` — the general rule. Written without knowledge of 0008;
  both now cross-reference.
- `0038-relay-read-tier-subscribers-project-scoped.md` — the read tier, implemented.
- `0039-relay-audiences-for-scopeless-content.md` — the audience gap, reframed against this history.
- `0001-integration-boundary.md` — the rule that an integration needing core changes is a failed
  integration task. Relay's generalisation is entangled with A2A's core growth; the commits carry
  empty bodies, so causation is inferred from sequence and not established here.
