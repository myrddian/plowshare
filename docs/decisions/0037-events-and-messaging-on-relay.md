# All events and messaging publish through Relay

Status: accepted; amended 13 November 2026 by
[0057](0057-board-forum-and-relay-messaging-substrate.md), during Slice 0 of
[Finishing Relay](../relay-finishing.md). Information events and `event.fire`
have moved; messaging bodies and outgoing delivery have not. The former
account-private audience blocker is withdrawn: agent topics are project-readable.
The board remains an independent forum. Delegation is partially on Relay; its
remaining consumer and eligibility work are described in 0041.

## Need and boundary

Relay is the platform's durable delivery substrate: a per-topic append log
(`relay_publications`, keyed `(scope_key, topic, position)`) with consumer leases, admissions, gap
recoveries and publication notifications. Events, messages and delegations are meant to be
wrappers over it — one mechanism for all of this work, because it *is* all the same work: append a
durable record in order, decide who receives it, hand it over once, acknowledge it, recover what
was missed.

When this record was written, schedules were on Relay; information events and
`event.fire` have since moved. Messaging and outgoing delivery are separate
families, and delegation was omitted from the original inventory. The table
separates those responsibilities.

| family | on Relay | state as written | now |
|---|---|---|---|
| schedules (`ScheduledPublication`) | **yes** | `RelayScheduledPublication` is the only implementation and `EventsConfig` wires it unconditionally — no flag, no second path | unchanged; topic name derived by its owner (V190, V191) |
| `information.events` | **now yes** | `information_events` (one global `BIGSERIAL`), `information_event_recipients`, an installation-wide advisory fence, and its own cursor | moved in V188, carried on the general message in V189; `information_events` is `DROP`ped (`V188:96`), so the clean break this record demanded actually happened |
| `event.fire` | **now yes** | `EventFireHandler` → `Intake` → `TriggerStore`, `FiringStore`, `Dispatcher`. No relay reference. | publishes through `RelayEventPublication`, consumed by the existing firing inbox; authority unit is the installation, scope `SystemScope.SERVER` |
| `message.*` bodies | no | `BoardMessaging` stores bodies as board posts and routes through `board_message_*` records | unchanged; extraction behind the stable messaging API is planned, with one Relay inbox per agent |
| outgoing delivery | no | `outgoing_work` and `outgoing_peers`, with adapter claims and reports | unchanged; A2A remains a feature but its implementation is to be rewritten under 0058 because adapter responsibilities entered core |
| **delegation** | **partial** | not inventoried — named in the need statement above and then omitted | inbound external root starts, pinned destinations, idempotency and receipts (V121), the effect-depth bound and nested causation (V127), and lifecycle notices out (V124) are all on Relay, shipped 2026-10-06. The delegation mechanics are not: `startNested` is reached only from the agent tool, the report that wakes a parent travels `Delivery` → `ParentVoice` in process, and `orchestration.child.completed` is published with no consumer. See 0041's table |

The unfinished delivery paths duplicate parts of Relay's mechanism. Owning
lifecycle records above the broker still have a purpose; moving delivery does
not remove those records or make the board forum a replacement target.

### What the duplication costs, measured

These are historical measurements of the pre-V188 implementation, retained to
explain the decision rather than describe the current runtime. At that time,
`JdbcInformationCatalogueRepository.event()` took `pg_advisory_xact_lock('information-event-order',
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

These rules govern delivery families moving onto Relay, not the board forum's
own post ordering, threads, seats or storage. The board stays independent; a
bridge calls its API. Owning systems retain lifecycle and recovery state above
the broker.

Concretely, no parallel delivery log outside `relay/` may:

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

`event.fire` uses the system scope for its server-administration authority.
Messaging uses one inbox topic per (account, project, definition name), readable
project-wide under the existing Relay scope policy, as decided in 0057. Instances
and conversations remain routing within messaging, not topic boundaries.

Relay scopes are `Relay.ProjectScope(projectId)` or `Relay.SystemScope.SERVER`
(`RelayScopeCodec`), so `authority_scopes.kind` for the revision's home scope maps as:

| home scope kind | Relay topic scope |
|---|---|
| PROJECT | `ProjectScope(scope.project_id)` |
| PERSONAL | `ProjectScope(scope.project_id)` — a personal space is a project |
| ACCOUNT_KNOWLEDGE | `ProjectScope(owner's personal project)`, via the V185 alias |
| GLOBAL_KNOWLEDGE | none exists; see the blockers below |
| ADMINISTRATION, RESTRICTED | fail loudly; no fallback topic |

### Authority dependencies and their current status

Recorded separately because their surfaces differ:

- **`0038-relay-read-tier-subscribers-project-scoped.md` — implemented (V187).**
  Read-tier subscribers use `members.mayUse` and account-namespaced groups, so
  information-event delivery does not narrow its audience to contributors.
  Work-tier ports, publishing and receiver dispatch retain their work checks.
  PROJECT, PERSONAL and ACCOUNT_KNOWLEDGE map to project scopes under V185.
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
3. Messaging extraction — preserve `message.*` and `send_message` while moving
   bodies off board transport, following the payload, bridge and messaging slices
   in Finishing Relay. The project audience is decided; there is no mailbox-privacy
   blocker or audience-based requirement for delegation to precede it.
4. A2A rewrite — retain the feature and rebuild its implementation outside core
   under 0058, after the earlier contracts. Delegation's consumer proceeds
   independently under 0041; its single leased group and eligibility requirements
   remain.

The reason for the order is the measured cost, not risk appetite. A system that takes fourteen
seconds to open a document does not scale, and no amount of preserved behaviour from the mechanism
causing it is worth keeping.

### Consequences accepted

- **Historical visibility follows subscription.** Before V188, information-event recipients were frozen at
  emit time and read back by sequence. Under Relay, who receives a publication follows the
  subscription at publication time, so an account that gains read access later does not
  retroactively receive earlier events. This is Relay's existing semantics for every other
  publication and is accepted rather than special-cased.
- **The information-event cutover replaced its log without shadowing it.** Publisher and consumer moved
  in one change and the old tables were dropped with it. A dual-write release was considered and
  rejected: it keeps the fence, the global sequence and the cursor alive for a release, which is
  the substrate this record exists to remove, and it buys only the continuity of in-flight cursors
  over one deployment. That cutover was planned without an installation migration,
  so no compatibility layer was retained — existing `information_events` rows were
  instance history, not a contract, and a
  shim for their shape is cruft with no reader. Retaining a subscriber inbox is justified only by a
  functional need, such as topic expiration losing input a consumer still requires, which is why
  the schedules migration kept firing records. Messaging's future clean break must
  move existing messages and threads, outstanding ones included, under 0057;
  the information-event history decision is not permission to discard them.
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

## Reconciliation recorded in Slice 0

### Board and messaging: 0057 supersedes 0040's replacement framing

[0057](0057-board-forum-and-relay-messaging-substrate.md) corrects the earlier
reading of the board/Relay lineage. Messaging is being extracted from the
board's transport, not finishing a replacement of the board. The forum keeps
its storage and sequence; the bridge posts through its owning API.

Two implementation findings from 0040 remain relevant:

- Messaging's send path has a per-mailbox advisory lock and a global board
  message sequence, not Relay's topic-row append lock or a consumption cursor.
  Moving delivery to Relay improves its ordering.
- `BoardMessaging` still owns board posts and `board_message_*` records. Its
  one-handling-request and one-running-target invariants must survive extraction;
  a timed Relay consumer lease does not replace them.

### A2A: 0058 supersedes 0008's EW01–EW08 plan

A2A stays as a feature. Its implementation needs a rewrite because adapter
responsibilities went into Plowshare core. [0058](0058-a2a-rebuild-on-relay.md)
records the replacement direction for Slice 5; the retired
[0008](0008-relay-external-work-and-a2a-mapping.md) is requirements and recovery
evidence, not a plan to keep extending that core surface. Adapters consume public
SDK Relay ports under [0001](0001-integration-boundary.md); any missing platform
capability needs its own decision and authorization.

The already-implemented read tier, opaque general carrier and consumer-owned
codec remain available. Preserve the mechanism-not-policy rule and private
family topic derivation. A project's inbox audience requires no new Relay scope;
0039 remains the separate vocabulary gap for genuinely non-project audiences.

### Delegation and the withdrawn audience blocker

0041 correctly separates arrival, turn eligibility and wait legality. Delegation
already has Relay-started external roots, pinned destinations, idempotent
receipts, effect-depth bounds, nested causation and lifecycle publications.
`orchestration.child.completed` still has no consumer; the parent report travels
`Delivery` → `ParentVoice.speak` in process, and the tool owns internal child
starts. Completing the move needs a consumer and an eligibility owner, not
another publisher.

The former instruction to do delegation first because message bodies are
account-private is withdrawn. Agent inbox visibility is project-wide under 0057.
Current conversation and `message.*` API reads retain their checks; the future
Relay publication is readable by the project's members. That is the decided
message audience, not an authority gap analogous to GLOBAL_KNOWLEDGE.

Delegation can use Relay directly without first moving through messaging. Its
reference-only `Lifecycle` notice remains appropriate: the owning record holds
the request/report text and delivery composes from a fresh read. `Delivery`'s
in-flight guard is still process-local, so consuming its notices requires a single
leased consumer group or a separately designed durable claim. None of this puts
eligibility or wait legality into Relay.

## Scope note

`information_log_readable`'s per-row cost is a separate defect, fixed independently by
`V186__log_readability_walks_the_ancestry.sql`. It is a row filter in thirteen places and is wrong
whether or not events move to Relay.

Two further per-row predicates were sampled live at 7–10 s and are not addressed here: the
`tagGroups` scheduler, which evaluates `information_visible_tags(...)` and a correlated `EXISTS`
twice per row, and the step scheduler, which calls `information_system_processing_valid(r.id)` per
row beneath several correlated `NOT EXISTS`. Both are recorded in
`docs/desktop-performance-measurements.md`.
