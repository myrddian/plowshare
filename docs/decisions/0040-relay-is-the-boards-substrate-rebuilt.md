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

### This record's own author did it too

The information-event cutover (V188) added `INFORMATION_CHANGED`: a sixth enum value, a codec case,
two CHECK constraints, and a widening of the `kind` allowlist across five SDK bindings. It should
have published the general message and let the consumer interpret it. Removing it is a follow-up
worth taking, and it would also delete that SDK change — and with it the version-skew risk that an
un-updated client rejects an information log page, which was the only client-visible consequence of
that work.

Recording it here rather than quietly is the point: the drift is easy to repeat, because adding a
type feels like modelling the domain properly, and the cost only shows up as the next family's
core change.

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
