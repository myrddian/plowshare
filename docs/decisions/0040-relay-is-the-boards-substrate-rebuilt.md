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

## Why it stalled, which is the part worth fixing

**Relay cannot be extended from outside, so nothing was ever built "on top" of it.**
`RelayPayload.java` states the cost directly — each new system topic needs a validated DTO and a
codec version — and `Relay.java:147,152` hard-codes the topic literals `message.wake.requested`,
`board.wake.requested` and `schedule.due` inside the core domain record. Adding a family requires a
new enum value, a codec case and a CHECK-constraint migration.

Of the five payload kinds, **one** (`TEXT`) is a general message. The other four were added inside
Relay core. So moving a family onto Relay is a core change by construction, which is the opposite
of the stated intent that systems build on top. That is the gap between intent and mechanism, and
it explains the stall better than neglect does: every migration onto Relay has had to pay a core
change, including the two this project just completed.

Any plan that assumes families can be added from outside is wrong until that changes.

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
- **Extensibility should be settled before the next family moves.** Otherwise each one keeps paying
  a core change, and the intent that systems build on top of Relay never becomes true.

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
