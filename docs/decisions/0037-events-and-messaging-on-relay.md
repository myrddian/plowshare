# All events and messaging publish through Relay

Status: accepted following explicit authorization to begin with information events. The remaining
families (`event.fire`, `message.*`) are accepted as direction and need their own authority-unit
decision before they move.

## Need and boundary

Relay is the platform's durable delivery substrate: a per-topic append log
(`relay_publications`, keyed `(project_id, topic, position)`) with consumer leases, admissions, gap
recoveries and publication notifications. Events, messages and delegations are meant to be
wrappers over it — one mechanism for all of this work, because it *is* all the same work: append a
durable record in order, decide who receives it, hand it over once, acknowledge it, recover what
was missed.

That is implemented for exactly one of four families.

| family | on Relay | what it uses instead |
|---|---|---|
| schedules (`ScheduledPublication`) | **yes** | `RelayScheduledPublication` is the only implementation and `EventsConfig` wires it unconditionally — no flag, no second path |
| `event.fire` | no | `EventFireHandler` → `Intake` → `TriggerStore`, `FiringStore`, `Dispatcher`. No relay reference. |
| `information.events` | no | `information_events` (one global `BIGSERIAL`), `information_event_recipients`, an installation-wide advisory fence, and its own cursor |
| `message.*` / outgoing | no | `outgoing_work`, `outgoing_peers`, `message_external_tasks`. The `outgoing` package has no relay reference. |

Each of the three that are not on Relay re-implements some part of what Relay already owns, and
each re-implements it differently. That is the cost this record is about: not one slow query, but
four delivery mechanisms where the design calls for one.

### What the duplication costs, measured

`information.events` is the worst case and is why this is urgent rather than tidy-up.
`JdbcInformationCatalogueRepository.event()` takes `pg_advisory_xact_lock('information-event-order',
0)` — a single key for the whole installation — and then, still holding it, computes the recipient
fan-out by asking `information_readable` once per admin and once per admin-project pair. Measured
on the live installation with 13 admins:

| | |
|---|---|
| recipient fan-out for **one** event | **3,996 ms** |
| the fan-out `INSERT`, sampled live | 1.6 s |
| sessions waiting on the advisory lock, sampled live | 1.3 s, `wait_event_type = Lock` |

`pg_advisory_xact_lock` is held until the transaction ends, so the fan-out is inside the critical
section by construction: `event()` needs the `sequence` that `INSERT … RETURNING` returns, and
everything after the fence is serialized whether it needs to be or not. Every information event
therefore serializes the whole installation for seconds, and the processing pipeline emits events
continuously. Raising the host from four cores to eight changed no client timing, so this is
wall-clock serialization rather than capacity.

The fence is not gratuitous in isolation. `information_events.sequence` is a `BIGSERIAL`, allocated
at `INSERT` and visible at `COMMIT`, and the consumer reads `WHERE sequence > ?` — so without a
commit-order fence a reader can advance past a lower sequence that commits later and lose it.
`docs/query-efficiency-relay-slice.md` states it: "a sequence maximum is not commit order." Relay
buys that same guarantee with `FOR UPDATE` on one `relay_topics` row, so its contention is one
topic in one project rather than the installation. The fence is the right answer to the wrong
question — the question is why there is a second log at all.

## Decision

**Relay is the only durable delivery substrate.** The event, message and information-event APIs are
wrappers over `Relay.publish` and Relay subscriptions. No family carries its own ordering fence,
its own position space, its own consumption cursor, or its own recipient computation.

Concretely, no delivery path outside `relay/` may:

- take a lock to order records — Relay's topic row lock is the ordering mechanism;
- allocate positions from a global sequence, or read records with a `position > cursor` scan;
- compute recipients per account by asking a permission predicate once per candidate.

### The pattern, from the migration that was finished

`RelayScheduledPublication` establishes it, and its own comment states the reasoning: *existing
firing records are the subscriber's durable inbox, so copied input survives topic expiration
without a second queue; claim, append, fan-out/queue policy and availability acknowledgement share
one transaction.* Each remaining family follows that shape:

- **Publish under the topic lock.** One append per event. Relay supplies gapless, commit-ordered
  positions per topic.
- **The family's existing records table becomes the subscriber's durable inbox**, not a publication
  log. It keeps what a subscriber has been handed so history survives topic expiration; it stops
  being the ordering contract and stops being read by cursor.
- **Claim, append, delivery policy and acknowledgement share one transaction.**
- **Recipients come from subscriptions.** This is what deletes the per-account permission loops.

### Topic granularity

For `information.events`, the topic is **the revision's home scope**. That is the choice that
removes the fan-out: a scope's subscribers are the accounts that may read the scope, and the home
scope is already the unit the read predicates resolve (`information_root_readable`,
`knowledge_readable_selection`), so no new authority concept is introduced.

`event.fire` and `message.*` each need the same decision made against their own authority unit
before they move; this record does not pre-empt them beyond the rule above.

### Order of work

1. `information.events` — it holds an installation-wide lock and is measured at 3,996 ms per event.
   Publisher, consumer and table removal are one change; see the consequences above.
2. `event.fire` — `Intake` keeps triggers and firings directly; no ordering fence, so this is
   reshaping rather than unblocking.
3. `message.*` / outgoing — largest surface, and external peers make its delivery contract the most
   exposed.

The reason for the order is the measured cost, not risk appetite. A system that takes fourteen
seconds to open a document does not scale, and no amount of preserved behaviour from the mechanism
causing it is worth keeping.

### Consequences accepted

- **Historical visibility follows subscription.** Today information-event recipients are frozen at
  emit time and read back by sequence. Under Relay, who receives a publication follows the
  subscription at publication time, so an account that gains read access later does not
  retroactively receive earlier events. This is Relay's existing semantics for every other
  publication and is accepted rather than special-cased.
- **The substrate is replaced, not shadowed, and the break is clean.** Publisher and consumer move
  in one change and the old tables are dropped with it. A dual-write release was considered and
  rejected: it keeps the fence, the global sequence and the cursor alive for a release, which is
  the substrate this record exists to remove, and it buys only the continuity of in-flight cursors
  over one deployment. No installation is being migrated, so there is nothing for a compatibility
  layer to serve — existing `information_events` rows are instance history, not a contract, and a
  shim for their shape is cruft with no reader. Retaining a subscriber inbox is justified only by a
  functional need, such as topic expiration losing input a consumer still requires, which is why
  the schedules migration kept firing records. It is never justified as backward compatibility.
- **Set-equality with the old fan-out is explicitly NOT a requirement.** It cannot be: frozen
  emit-time recipients and subscription-time delivery are different semantics, and demanding both
  would re-impose the design being removed. What must hold instead is the authorization invariant
  below.

### The one invariant that does not bend

No account may receive an information event for a scope it may not read. This is a security
property, not a compatibility guarantee, and it is the thing to pin: delivery is driven by
subscription, so the test is that a subscription cannot exist, or cannot deliver, for an account
that fails the scope's read authority. Everything else about the old mechanism's behaviour is
free to change.

## Alternatives rejected

All three are rejected on the same ground: they invest in substrates that should not exist.

- **Make the information-event fan-out cheap, keep the substrate.** Resolve the revision's home
  scope once and ask which accounts may read that scope, replacing 13 × (2 + N) predicate
  evaluations. It is the smallest change available and would remove most of the measured cost — but
  it leaves a global advisory lock, a global sequence, a bespoke cursor and a parallel recipients
  table standing. It was briefly adopted as a first step and withdrawn: making the wrong substrate
  cheaper is how it survives. Acceptable only if the migration is abandoned, which would need its
  own record.
- **Replace the fence with an XID watermark.** The primitives exist and are already used by the
  projection cache: `pg_snapshot_xip(pg_current_snapshot())` and `pg_xact_status(xid::xid8)`,
  documented in `V175__projection_cache_version_fences.sql`. A commit-settled watermark would make
  the cursor safe with no lock at all. Rejected because it fixes ordering for a log that should be
  a Relay topic, and leaves the fan-out and the parallel consumption path untouched.
- **Add a `NOTIFY` for information events.** Notifications are delivered at commit, so a
  notification-driven reader gets commit order without a fence. Rejected alone: `NOTIFY` is not
  durable, so a disconnected consumer still needs a commit-safe catch-up read — the original
  problem. Relay already combines both, a durable log plus
  `NotificationRelayPublicationSignals`; the only `NOTIFY` channel today is
  `plowshare_projection_changes`, for the projection cache.

## Scope note

`information_log_readable`'s per-row cost is a separate defect, fixed independently by
`V186__log_readability_walks_the_ancestry.sql`. It is a row filter in thirteen places and is wrong
whether or not events move to Relay.

Two further per-row predicates were sampled live at 7–10 s and are not addressed here: the
`tagGroups` scheduler, which evaluates `information_visible_tags(...)` and a correlated `EXISTS`
twice per row, and the step scheduler, which calls `information_system_processing_valid(r.id)` per
row beneath several correlated `NOT EXISTS`. Both are recorded in
`docs/desktop-performance-measurements.md`.
