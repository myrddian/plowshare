# All events and messaging publish through Relay

Status: accepted. Information events and `event.fire` have moved; `message.*` / outgoing has not,
and is now blocked on a reason this record did not know about. Delegation is named in this record's
own need statement but was never inventoried; see the reconciliation with
`0041-waiting-is-turn-eligibility.md` at the end.

## Need and boundary

Relay is the platform's durable delivery substrate: a per-topic append log
(`relay_publications`, keyed `(project_id, topic, position)`) with consumer leases, admissions, gap
recoveries and publication notifications. Events, messages and delegations are meant to be
wrappers over it — one mechanism for all of this work, because it *is* all the same work: append a
durable record in order, decide who receives it, hand it over once, acknowledge it, recover what
was missed.

When this record was written that was implemented for exactly one of four families. Three now are;
the table below carries both states, because the gap is the point of the record.

| family | on Relay | state as written | now |
|---|---|---|---|
| schedules (`ScheduledPublication`) | **yes** | `RelayScheduledPublication` is the only implementation and `EventsConfig` wires it unconditionally — no flag, no second path | unchanged; topic name derived by its owner (V190, V191) |
| `information.events` | **now yes** | `information_events` (one global `BIGSERIAL`), `information_event_recipients`, an installation-wide advisory fence, and its own cursor | moved in V188, carried on the general message in V189; `information_events` is `DROP`ped (`V188:96`), so the clean break this record demanded actually happened |
| `event.fire` | **now yes** | `EventFireHandler` → `Intake` → `TriggerStore`, `FiringStore`, `Dispatcher`. No relay reference. | publishes through `RelayEventPublication`, consumed by the existing firing inbox; authority unit is the installation, scope `SystemScope.SERVER` |
| `message.*` / outgoing | no | `outgoing_work`, `outgoing_peers`, `message_external_tasks`. The `outgoing` package has no relay reference. | unchanged — `server/outgoing/` still holds zero Relay references — and now known to be *blocked*, not merely unstarted |
| **delegation** | **partial** | not inventoried — named in the need statement above and then omitted | inbound external root starts, pinned destinations, idempotency and receipts (V121), the effect-depth bound and nested causation (V127), and lifecycle notices out (V124) are all on Relay, shipped 2026-10-06. The delegation mechanics are not: `startNested` is reached only from the agent tool, the report that wakes a parent travels `Delivery` → `ParentVoice` in process, and `orchestration.child.completed` is published with no consumer. See 0041's table |

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

Relay scopes are `Relay.ProjectScope(projectId)` or `Relay.SystemScope.SERVER`
(`RelayScopeCodec`), so `authority_scopes.kind` for the revision's home scope maps as:

| home scope kind | Relay topic scope |
|---|---|
| PROJECT | `ProjectScope(scope.project_id)` |
| PERSONAL | `ProjectScope(scope.project_id)` — a personal space is a project |
| ACCOUNT_KNOWLEDGE | `ProjectScope(owner's personal project)`, via the V185 alias |
| GLOBAL_KNOWLEDGE | none exists; see the blockers below |
| ADMINISTRATION, RESTRICTED | fail loudly; no fallback topic |

### Blocked on two Relay authority changes

Recorded separately because their surfaces differ:

- **`0038-relay-read-tier-subscribers-project-scoped.md`.** Relay gates consumption on
  `members.mayWork` (CONTRIBUTOR and above) because consuming takes a lease and a lease is a write,
  while information read authority admits VIEWER. Without it, moving information events onto
  project topics would silently narrow who receives them. It unblocks PROJECT, PERSONAL and
  ACCOUNT_KNOWLEDGE — under the V185 alias, every information resource on the installation.
- **`0039-relay-audiences-beyond-a-project.md`.** GLOBAL_KNOWLEDGE has no project, and
  `SystemScope.SERVER` is the internal worker scope, so global-read content has no expressible
  audience. Deferred: the case is reachable but currently empty. Until it lands, this
  migration's implementation must **fail loudly** on a GLOBAL_KNOWLEDGE home scope rather than
  guess a topic — a refused publication is a visible defect, one delivered to the wrong audience
  is not.

### Order of work

1. ~~`information.events`~~ — **done** (V188, V189). It held an installation-wide lock and was
   measured at 3,996 ms per event. Publisher, consumer and table removal were one change.
2. ~~`event.fire`~~ — **done**. `Intake` kept triggers and firings directly; no ordering fence, so
   this was reshaping rather than unblocking.
3. `message.*` / outgoing — largest surface, and external peers make its delivery contract the most
   exposed. **Now known to be blocked**: see the 0041 reconciliation below. Delegation goes before
   it.

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

## Records this one must be read with

Two were missed when this record was written, and both change how it should be executed.

- **`0008-relay-external-work-and-a2a-mapping.md`** already decided that the outgoing family moves
  onto Relay, in eight slices (EW01–EW08), with status "architecture direction agreed;
  implementation planned". None shipped — `server/outgoing/` contains no Relay reference. This
  record was written without knowledge of 0008. They are reconciled below rather than left to
  collide.

### Reconciling 0008 and this record

They do not conflict; they sit at different altitudes. **0008 is the specific plan for the
external-work and A2A family**; this record is the general rule for every family. Where both speak,
0008's slices are the more detailed instruction for that family and should be followed; this record
constrains *how*, and 0040 constrains what Relay may be asked to do.

0008 was recorded on 2026-10-09 against snapshot `35ffa6e0` and instructs that "implementation must
recheck current main and reconcile changes before proceeding". That recheck is now overdue, and
these are the changes it has to absorb:

| since 0008's snapshot | what EW01–EW08 must absorb |
|---|---|
| Read-tier subscribers (0038, V187) | EW01/EW03's authorization decisions: consumption is `mayUse`, not `mayWork`, and a reader's consumer group is namespaced per account |
| Information events on Relay (V188), then on the general message (V189) | EW01 need not re-derive the carrier: **no new payload kind**, a codec owned by the consuming system, content opaque to the broker |
| Relay is mechanism, not policy (0040) | EW01's "payload references and authorization decisions": publish only what the topic's whole audience may see, and verify your own publishers rather than asking the broker to |
| Topic names are private to the owning system (0040) | the external-work topic is not a shared literal |
| Messaging still lives in `server/board/` on the v0 substrate (0040) | EW04's "migrate existing work" is larger than 0008 assumed — it is finishing a v0→v1 replacement, not porting a module |
| No non-project Relay audience exists (0039) | a dependency 0008 does not name: if an external peer's audience is not a project, EW03 is blocked on 0039 |

Two further notes for whoever picks this up:

- 0008's subject is the integration whose core growth `0001-integration-boundary.md` forbids. EW01's
  audit should establish what A2A put into core and whether it belongs there, rather than treating
  the current surface as the baseline to preserve.
- The ordering in this record — information events, then `event.fire`, then messaging — stands, and
  0008's slices are the inside of the third step. Nothing in 0008 needs to precede the first two.
- **`0040-relay-is-the-boards-substrate-rebuilt.md`** records the lineage this record assumed away.
  The Board substrate is Relay v0 and Relay is v1, built fresh in parallel rather than by
  retrofitting the Board; board and messaging were to be rebuilt on v1 and never were. So the
  messaging work named below is **finishing a replacement**, not migrating a foreign system.

Two corrections to this record's own framing follow from 0040:

- **Messaging's ordering is weaker than Relay's.** An earlier version of this note claimed it was
  "already Relay's mechanism — a topic-row `FOR UPDATE` then an insert, with per-topic cursors".
  That was false. The messaging send path takes no topic lock, `board_messages.seq` is a global
  `GENERATED ALWAYS AS IDENTITY` sequence, and messaging reads no cursor at all; it serialises on a
  per-mailbox advisory lock. See 0040. Moving it onto Relay would therefore **improve** its
  ordering. It still has no recipient fan-out, and nothing has been measured failing, so it is not
  urgent in the way information events were — but it must not be described as already sound.
- **Messaging still lives in `server/board/`**, on `board_messages` and `board_message_*` tables,
  not in a separately extracted module. The rule below still holds; the size of the move is larger
  than this record implied.

### Reconciling 0041 and this record

`0041-waiting-is-turn-eligibility.md` changes two things here.

**Delegation was never inventoried.** This record's own need statement says "Events, messages *and
delegations* are meant to be wrappers over it", and then the four-family table and the order of work
omit delegation entirely. That omission is why a sender-side wait was specified against messaging:
with delegation absent from the inventory, the synchronous construct looked like something messaging
had to grow, rather than something delegation already owns. Delegation is the fifth family, and it
is further along than this record's ordering implies: inbound external root starts, pinned
destinations, idempotency and receipts (V121), the Relay effect-depth bound, nested causation (V127)
and the lifecycle outbox (V124) all shipped on 2026-10-06 in "Bound lifecycle feedback across
Relay-started work".

What it is *not* is finished, and the unfinished part is easy to mistake for finished.
`orchestration.child.completed` is published as a `Lifecycle` notice carrying its parent, and
nothing consumes it: the report that actually wakes a parent still travels `Delivery` →
`ParentVoice.speak` in process, and `startNested` is reached only from the agent tool. So Relay
carries delegation's notices as an observation feed beside the in-process delivery that does the
work — two paths for one fact. Completing it is a consumer and the eligibility owner, not a
publisher.

**The ordering no longer holds, and messaging is blocked rather than merely last.** This record says
the sequence "information events, then `event.fire`, then messaging" stands. Two of those are done,
and the third cannot proceed on this record's own terms: a message body is account-private mailbox
content, and publishing it at project scope would expose it through `relay.log`
(`RelayPortEvents.java:64` renders a `Text` payload's text). That is not a sequencing preference but
an authority problem, and it is the kind of thing this record's rule about publishing only what a
topic's whole audience may see already forbids.

Delegation does not share it. `orchestration.status`, `.record` and `.receipt` are already in a
project-scoped read set (`ProjectAuthorization.java:115-119`), so delegation moves a **notice** and
not a body — `RelayPayload.Lifecycle`, with the text left in `orchestration_messages`. Verified
against the consuming code: `Delivery` composes its utterance from a freshly re-read record
(`Delivery.java:475-480`) and `ParentVoice.speak` receives an already-composed string, so a
reference-only notice is the input that path is written for.

So the revised order for what remains is **delegation, then `message.*` / outgoing**, with
messaging's move gated on an audience for account-private content that does not exist yet — the same
shape of gap as `0039-relay-audiences-beyond-a-project.md`, against a different authority unit.

One requirement carries over from 0041 and belongs in any delegation slice: `Delivery`'s in-flight
guard is an in-process `ConcurrentHashMap` keyset (`Delivery.java:116`), not a database claim, so
delegation's notices must be consumed under a single leased consumer group or that guard must become
durable first.

## Scope note

`information_log_readable`'s per-row cost is a separate defect, fixed independently by
`V186__log_readability_walks_the_ancestry.sql`. It is a row filter in thirteen places and is wrong
whether or not events move to Relay.

Two further per-row predicates were sampled live at 7–10 s and are not addressed here: the
`tagGroups` scheduler, which evaluates `information_visible_tags(...)` and a correlated `EXISTS`
twice per row, and the step scheduler, which calls `information_system_processing_valid(r.id)` per
row beneath several correlated `NOT EXISTS`. Both are recorded in
`docs/desktop-performance-measurements.md`.
