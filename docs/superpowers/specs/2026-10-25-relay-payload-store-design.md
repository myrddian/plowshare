# Relay content-addressed payload store

Date: 2026-10-25
Status: implemented on this branch (V191-V193). Corrected against the code after
implementation; where the text records a decision that was reversed, it says so.

## Why this exists

A Relay payload may carry 50 MiB of raw UTF-8 TEXT
(`V135__relay_text_byte_limit.sql`), and the schema stores those bytes twice over
by construction. `relay_publications` holds the appended record.
`relay_admissions` then copies the payload once **per subscriber** that admits the
position, in `JdbcRelayDeliveryRepository.admit`, because the copy is what allows
retention to delete a prefix of the log while work already admitted keeps its
input. One event near the ceiling, admitted by *n* subscribers, is 50 MiB × (1 +
*n*) on disk.

That amplification is a **work-tier** cost only. Only the work tier
admits: `ReadTierRelayConsumption.consume` calls `relay.read(key, limit)` directly,
which creates no admission row and no payload copy. "Per admitting subscriber" is
literally accurate (this point is established by reading the code, not by a test that
would fail if it stopped being true), but read as a sizing figure it can suggest every subscriber
copies the payload; read-tier subscribers are free in exactly the dimension this
store exists to fix. **The read path must not be changed to resemble the work path.**
Giving a read-tier subscriber an admission, to unify the two or to make reads
"durable", would reintroduce a per-subscriber reference and the lifecycle that goes
with it for a tier that has none by design.

The copy is correct and load-bearing. What is wrong is that the bytes are the
unit being copied. Nothing in the fan-out needs a *distinct* payload per
subscriber: every admission at a position stores bytes identical to the
publication's, and a republished identical event stores them again.

This design moves payload bytes into one content-addressed table that
publications and admissions reference by digest, and reclaims unreferenced bytes
with a swept reachability predicate deferred by a stored examination date. Every
current Relay semantic is preserved unchanged; only the location of the bytes
moves.

The immediate driver is scale rather than a present failure. Nothing observed
says a publisher approaches the ceiling today. The amplification is arithmetic in
the schema, and the Relay migrations are under analysis now, which is the moment
to fix the shape rather than after it is loaded.

## Goals

- One stored copy of any distinct payload, regardless of how many subscribers
  admit it or how many times identical content is published.
- Dedup identical content **server-wide**, across scopes and topics.
- Preserve every established Relay semantic exactly: prefix-delete retention,
  subscriber offsets outliving expired publications, admitted work keeping its
  input, idempotent republish by `event_id`, and position allocation under the
  topic row lock.
- Reclamation that can never delete reachable bytes, with reachability — not a
  date, not a counter — as the sole authority for deletion.
- A bounded sweep: each pass examines a configured number of matured candidates,
  and reclamation latency after a payload becomes unreachable is bounded by
  configuration rather than by a topic's retention policy.
- No change to any observable size limit, page boundary or cache threshold.

## Non-goals

- Moving bytes out of PostgreSQL. Separate work exists for the filestore; this
  is not it.
- Changing the SDK or protocol. `RelayPayload` and every published DTO stay as
  they are; this is a storage change behind the repository boundary.
- Changing the 50 MiB ceiling or the `plowshare.relay.max-text-bytes` admission
  allowance.
- Fixing `topicsToPrune`. Topic prune cost with many live topics is a real and
  separate finding (see "Adjacent finding") and must not be folded in.
- Partitioning `relay_publications`, natively or otherwise.

## Anti-goals

These would make the change a failure even if it functioned:

- A reference-count column. Counting forces every delete path — retention
  prefix sweep, settled-admission cleanup, topic removal, project deletion — to
  decrement, and `ON DELETE CASCADE` cannot decrement a counter. Counting means
  replacing working cascades with triggers.
- Any scope or tenancy reasoning inside the sweep. A payload is reachable or it
  is not. Authorization is carried by the referencing topic and subscription,
  which the existing contract already governs; the bytes do not re-derive it.
- Any path in which the examination date authorizes a deletion.
- Raw JSON crossing into a domain contract. JSON text stays inside the
  persistence boundary, as it does today.
- The substrate learning anything new about payload families. The platform is
  layered: a system that exposes an API and involves messaging publishes through
  Relay, but Relay is never that system's interface. The information system uses
  Relay for its events and exposes its own API to its own clients, which is why
  encoding and decoding belong to the using system and not here.
  [Decision 0037](../../decisions/0037-events-and-messaging-on-relay.md) states
  the first half — events and messaging are wrappers over Relay. This design
  depends on the second half: a codec added to Relay on the information system's
  behalf imported that system's concerns into the substrate, and removing it is
  why `information_events` went away on this branch.
- Per-topic physical tables or dynamic SQL identifiers in this path.
- A read path that fetches payloads per record. Batched reads (consume returns
  up to 100) must join, not loop.

## Constraints

- New Flyway migration only. No shipped migration is edited.
- Repositories own persistence. A new narrow repository interface owns the
  payload table; no SQL leaves it.
- Google Java Style via `./gradlew format`; `formatCheck` and `check` before
  handoff.
- Database tests are mocked by default. The full-database exceptions here are
  justified and named below.
- The staging window, deferral ceiling and sweep batch size are configuration,
  with no hard-coded deployment values.
- PostgreSQL 16 (`pgvector/pgvector:0.8.7-pg16-bookworm`), so the core
  `sha256()` function is available without an extension.

## Design

### The table

```sql
CREATE TABLE relay_payloads (
    digest TEXT PRIMARY KEY CHECK (digest ~ '^[0-9a-f]{64}$'),
    payload JSONB NOT NULL CHECK (
        jsonb_typeof(payload) = 'object'
        AND octet_length(convert_to(payload::text, 'UTF8')) <= 314573824
        AND (NOT (payload ? 'text') OR (
            jsonb_typeof(payload->'text') = 'string'
            AND octet_length(convert_to(payload->>'text', 'UTF8')) <= 52428800
        ))
    ),
    byte_length BIGINT NOT NULL CHECK (byte_length > 0),
    ejection_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

The payload `CHECK` is the one `V135` placed on both `relay_publications` and
`relay_admissions`, moved here so it continues to be enforced in exactly one
place per stored byte. SHA-256 as 64 lower-case hex characters is existing
precedent in this schema: `relay_executions.handler_hash` and
`relay_deliveries.handler_hash` use the same *column shape*. The digest *function* is
not a new one: `RelayPort.hash` in `plowshare-protocol` is already SHA-256 over the
UTF-8 bytes of a string as lowercase hex, `V189` treats it as the convention
(`'information:' || sha256(actor)` "matches `RelayPort.hash`"), and the store reuses
it rather than defining a second digest.

`relay_publications.payload` and `relay_admissions.payload` are replaced by
`payload_digest TEXT NOT NULL REFERENCES relay_payloads(digest)`. The foreign key
is deliberate: it makes "do not delete referenced bytes" a database guarantee
rather than a property of the sweep's `WHERE` clause. A referencing insert takes
only a `KEY SHARE` lock on the payload row, so concurrent publishes of the same
hot digest do not block each other — only a concurrent delete of that digest
does, which is precisely the race to serialise.

**The table stores no `payload_kind`.** The first reason is mechanical: the digest
addresses content, and a family is a property of the *referencing* row, not of the
bytes. A publication's family comes from `relay_topics.payload_kind` and an
admission's from its own `payload_kind` column. Storing the family beside the
bytes would let one digest assert a family that a differently-familied reference
contradicts, and would need a new invariant — that two families can never produce
identical bytes — which holds today only as an accident of `RelayPayloadCodec`'s
disjoint field sets across the current five families (`EMPTY`, `TEXT`,
`SCHEDULE_DUE`, `LIFECYCLE`, `WAKE_REQUESTED`). An earlier draft counted six; `V189`
removed `INFORMATION_CHANGED` when information events moved onto the general
message, and `RelayPayload.Kind` is the authority.

The second reason is the architectural one, and it is the stronger: Relay is a
substrate. The payload store's job is to hold bytes and reclaim them, and
encoding is the using system's responsibility. A family-agnostic store is
therefore the correct shape independently of the collision argument, and stays
correct as families are added.

### One known leak, left as it is

The `CHECK` above inspects `payload->'text'`, so the substrate does know that one
family keeps its content in a member called `text`. That knowledge is inherited
from `V135`, not introduced here, and this design moves it rather than adding to
it.

A substrate-pure version of this table would bound `octet_length` alone and leave
the 50 MiB text bound where the family is known — the application already enforces
it on every new append through `RelayTextLimit.validateNew`, so the database
`CHECK` is belt-and-braces rather than the only guard.

*(My call: keep `V135`'s constraint unchanged, because changing an enforced limit
is a non-goal of this change and semantic preservation is the point. Recorded
here because it is a live example of the class of leak being removed elsewhere,
and it is a one-line change to the migration if the substrate boundary should win
instead.)*

### byte_length is load-bearing, not decorative

Three live read paths bound database materialization by payload size before
decoding, and all three must keep measuring exactly what they measure today:

- `JdbcRelayRepository.read` pages with
  `SUM(octet_length(payload::text) + 4096) OVER (ORDER BY position)` against
  `SegmentedMessages.MAX_MESSAGE_BYTES - 1 MiB`.
- `JdbcRelayLogRepository` repeats that window for its own page.
- `JdbcRelayLogRepository.boundedProjection` sizes a `MATERIALIZED` CTE and
  throws `ProjectionCache.Bypass` over the bound.

Once the bytes move, that budget must come from a stored length, because taking
it from a join that materializes the payload defeats the purpose of the bound.
Therefore `byte_length` is **required**, and is defined as
`octet_length(payload::text)` of the stored JSONB — the same quantity the
existing windows compute, so page sizes and the projection-cache bypass
threshold do not shift.

Note this is deliberately *not* the digest input length. The digest is taken over
the application's serialization; `byte_length` describes PostgreSQL's rendering
of the stored JSONB. They can differ, and each is used only for its own purpose.

### Writing

The append path (`JdbcRelayRepository.append`) computes the digest over the exact
UTF-8 bytes it is about to store, then:

```sql
INSERT INTO relay_payloads(digest, payload, byte_length, ejection_at)
VALUES (?, ?::jsonb, octet_length((?::jsonb)::text), ?)
ON CONFLICT (digest) DO NOTHING
```

**This clause was first specified as `DO NOTHING`, and that decision was wrong.** The
earlier text read: "`DO NOTHING` rather than `DO UPDATE`. A repeat publish of a hot
digest needs no row version written — the referencing publication row it is about to
insert is what protects the payload from the next sweep ... *(My call.)*" The
justification does not hold for a *repeat* append. `DO NOTHING` neither re-arms
`ejection_at`, so a repeat append of an already-matured digest —
one whose earlier references were pruned — left it a live sweep candidate across the whole commit-ahead window. The referencing insert is not yet there to
protect it: it comes after the payload commits, after the topic lock, the locked
retained read and position allocation. A sweep in that window found no reference and
deleted the row, and the appender's publication insert then failed its foreign key,
**rejecting the publish**.

The shipped clause is conditional:

```sql
INSERT INTO relay_payloads(digest, payload, byte_length, ejection_at)
VALUES (?, ?::jsonb, octet_length((?::jsonb)::text), ?)
ON CONFLICT (digest) DO UPDATE
  SET ejection_at = GREATEST(relay_payloads.ejection_at, excluded.ejection_at)
  WHERE relay_payloads.ejection_at < excluded.ejection_at
```

It re-arms the staging window when the stored date is behind the new one, and it
still writes no new row version for a hot digest whose date is already at or beyond
`now() + window`: the predicate is false and the update is skipped. That case needs no
re-arming, because a row that far out is not a sweep candidate until after the window,
by which time the caller's reference is committed or its append has failed. *(That
last step is reasoned from the candidate query's `ejection_at <= ?` and the
hour-bucketed dates, not from a test that would fail if it stopped holding.)* The
original objection to `DO UPDATE` — a dead tuple per repeat on the hottest row in the
table — is answered by the predicate, not ignored.

**The sweep's `DELETE` requires the date as well, and that is what makes the re-arm
effective.** The row lock a `DO UPDATE` takes would not have been enough on its own.
A lock only *delays* a delete that has already selected the digest; when that delete
resumes, under READ COMMITTED it follows the update chain and re-evaluates its
predicate. Were the predicate only `digest = ?` and the two `NOT EXISTS` subqueries,
it would still find no reference — the appender's publication insert comes later,
after the topic lock — and delete. Worse, the sweep selects its whole candidate list in
one query and then reclaims one candidate per transaction, so a digest can wait behind
up to `payload-sweep-batch` earlier candidates, not a statement's worth of time. So the
delete also carries `p.ejection_at <= ?`, bound to the same instant the candidate
query used. A row re-armed after selection fails that predicate, deletes nothing and
falls through to the deferral. A re-armed row therefore cannot be reclaimed by the
pass that selected it, and the append-side hazard is closed rather than narrowed. The
date is a *necessary* condition for deletion and never a *sufficient* one —
reachability is still required — so this is strictly more conservative than the
predicate it replaces and does not contradict the invariant below. A database test
(`aCandidateReArmedAfterSelectionSurvivesThePassThatSelectedIt`) holds a second
candidate to be re-armed while the first is processed and fails if the date is
removed from the predicate.

Deferral likewise only ever moves a date later: it writes
`GREATEST(ejection_at, next)`. A re-armed row reaches the deferral unreferenced, where
the horizon falls back to a staging window truncated *down*, which can be up to an
hour earlier than the date the re-arm wrote; without `GREATEST` the deferral would pull
the examination forward.

Admission writes the same digest it read from the publication. It stores no bytes
at all; the *n*-way amplification disappears here rather than being compressed.

`ejection_at` on insert is `now()` plus the configured staging window, truncated
to the hour.

**The insert is issued ahead of the append's topic transaction, and commits ahead of it only when the appending thread has no transaction open.** *(Decided.)*

`JdbcRelayRepository` joins the caller's domain transaction, so an append can sit
inside a long-lived one. Were the payload insert to share it, `ON CONFLICT
(digest) DO NOTHING` against a digest another uncommitted transaction had just
inserted would block on the unique index until that transaction ended — two
publishers in unrelated topics and scopes, contending today on nothing,
serialising on identical content. Dedup makes that collision *more* likely, since
identical content is the case it exists to exploit.

Storing the payload first removes that when the appender has no transaction open.
`store` issues a bare statement with no transaction of its own, and
`UnitOfWork.inTransaction` is a `TransactionTemplate` with default
`PROPAGATION_REQUIRED`, so under an enclosing transaction both join it and the payload
commits only when the outer transaction does. That is the case for
`RelayInformationEvents.publish`, which publishes inside its caller's transaction by
design. Known limitation of the current propagation, deliberately not changed here
because that is a design decision of its own:

- Two concurrent transactional publishers whose payloads encode to identical bytes
  serialise on the unique index until the *outer* transaction ends, which is the
  blocking this decision set out to remove.
- A transaction that publishes several events locks several payload rows in append
  order, so two such transactions sharing two digests in opposite orders can deadlock
  on the unique index. Content addressing makes that collision possible where it was
  not before.
- Nothing is mis-stored or lost in either case. Identical payloads across distinct
  notices are expected by design, not merely possible: the information notice's event ID
  carries a fresh UUID, but the payload is built from the revision, generation, stage,
  action and document fields, and `RelayInformationEvents` records that the catalogue
  legitimately repeats the same revision, stage and action ("two retries of one stage are
  two events"). What is unquantified is only how often two such publishes overlap
  concurrently.

Where the appender has no transaction open, committing the payload first makes the staging path a 50 MiB
payload will eventually want reachable rather than hypothetical. Three
consequences follow and must be built deliberately:

- **The staging window is load-bearing now.** Between the payload commit and the
  referencing insert the bytes are unreferenced, and only the window protects
  them from the sweep. It must comfortably exceed the longest append.
- **A rolled-back or failed append leaves an orphan payload.** This is correct and
  self-correcting: the sweep reclaims it once the window matures. It is never a
  wrong read, because nothing references it.
- **Append has two store call sites and one probe.** `JdbcRelayRepository.isRetained` is an
  unlocked existence probe run on every publish; an identical repeat of a retained event
  stores nothing. The first `store` runs before the topic lock. The second, inside the
  topic transaction, is reached only when the probe saw an event that pruning removed
  before the lock was taken; it is the only path that stores under a lock, and the
  reason the lock-ordering claim (topic first, then payload; the sweep takes no topic
  lock) exists.
- **Append's failure handling grows a case.** A payload committed for an append
  that then fails is not an error to report; the retry re-derives the same digest
  and the `ON CONFLICT` finds the existing row, so the second store adds no row
  (it re-arms the staging window when the stored date is earlier).

### Payload bytes are immutable

At runtime, every production write to `relay_payloads` is one of three: the conditional insert
above, the guarded `DELETE` in the sweep, or an `UPDATE` of `ejection_at` alone.
Nothing ever updates payload bytes, and that is load-bearing. Every publication and
admission that references a digest reads the same bytes, so a writer that mutated a
row in place would silently rewrite history for every referencing row at once. (This
covers runtime writers only; the `V192` and `V193` backfills insert rows.)

The corollary is uncomfortable and stated plainly: a corrupted `relay_payloads` row is
**permanent for every row sharing that digest**. The conflict clause never repairs
bytes — it does not touch them — and the product has no detection path for it: the
digest is not re-verified on read, and nothing compares stored bytes with the digest
that names them.

### Digest definition

The digest input is the UTF-8 encoding of the canonical JSON serialization the
application produces for that payload — the same bytes passed as the `payload`
parameter. The migration's backfill computes digests from PostgreSQL's
`payload::text` rendering instead, which normalizes key order and whitespace and
may therefore differ from the application's rendering for equivalent content.

That divergence is benign and must be documented where the digest is defined: the
worst outcome is two payload rows holding equivalent JSON under different
digests, which is a missed dedup, never a wrong read and never a wrong delete.
Content addressing is an optimization of storage, not an identity the rest of the
system depends on.

### Reclamation

The sweep is reachability, deferred by date:

```sql
SELECT digest FROM relay_payloads
WHERE ejection_at <= ?
ORDER BY ejection_at
LIMIT ?
```

and for each candidate, under its own transaction:

```sql
DELETE FROM relay_payloads p WHERE p.digest = ? AND p.ejection_at <= ?
  AND NOT EXISTS (SELECT 1 FROM relay_publications WHERE payload_digest = p.digest)
  AND NOT EXISTS (SELECT 1 FROM relay_admissions   WHERE payload_digest = p.digest)
```

A candidate that survives has its examination deferred rather than being
re-tested next pass. The new `ejection_at` is the furthest-out *earliest possible
removal* among its references, **capped by the configured deferral ceiling**:

| Reference | Earliest possible removal |
| --- | --- |
| `relay_publications` row | `published_at + relay_topics.retention_seconds` |
| settled `relay_admissions` row | `admitted_at + RelayProperties.settledRetention` (30 days by default), the cut `JdbcRelayDeliveryRepository.pruneSettled` uses |
| unsettled `relay_admissions` row | unbounded — fall back to `now()` + staging window |

Both computed dates are **lower bounds on removal**, not schedules: retention is
also driven by `max_records`, and it executes as a sweep, so a publication can
outlive its computed expiry. This is why the date defers only and never deletes.

The governing invariant, which belongs in a comment on the table:

> `ejection_at` defers the next examination. It never authorizes a deletion.
> Reachability is the only authority. A date that is too early costs one wasted
> check; a date that is too late leaves bytes for one more window. Neither can
> remove something referenced.

The date appears in the `DELETE` as a necessary condition only: it can stop a
deletion (see "Writing"), never permit one.

#### Why the deferral is capped

`relay_payloads` is **not cascade-reachable from `relay_topics`**: the references
point from publications and admissions *to* the payload, so no `ON DELETE CASCADE`
runs the other way. Deleting a topic, subscription or project removes the references
and leaves the payload row exactly as it was. That is the design — reclamation is the
sweep's job — and it is also why a test that truncates `relay_topics` must name
`relay_payloads` explicitly, or every payload outlives it.

References also disappear through paths the sweep never observes. `ON DELETE
CASCADE` reaches publications from `relay_topics` and admissions from
`relay_subscriptions` (`V120`), and transitively from `projects`. Deleting a
topic, a subscription or a project therefore makes payloads unreachable without
touching `relay_payloads` at all — and a counter or a trigger on those paths is
an explicit anti-goal.

Uncapped, a survivor's deferral is `published_at + retention_seconds`, and `V117`
permits `retention_seconds` up to 315360000 — ten years. A dropped project could
leave 50 MiB payloads unreferenced and unexamined for a decade.

So the extension is `LEAST(computed_horizon, now() + deferral_ceiling)`, default
24 hours. The cost is explicit and bounded: every stored payload is re-examined
about once a day, so a sweep pass is proportional to total payloads per ceiling
period rather than strictly to matured candidates. That is the price of having no
counter, and 24 hours of lingering bytes after an unobserved cascade is the
latency it buys. *(My call: cap rather than add a second full-table pass, which
would cost the same scan without bounding the latency as directly.)*

Hour granularity is deliberate. A day is both coarser than needed and, early in a
day, shorter than wanted; an hour bucket is low-cardinality enough that each pass
is a batch rather than a scan.

### Indexes and their cost

- `relay_publications(payload_digest)`
- `relay_admissions(payload_digest)`
- `relay_payloads(ejection_at)`

The first two make the anti-joins index lookups instead of scans, and are what
the foreign keys want anyway. They are also the honest cost of this design: one
additional index write per publication and per admission insert, on the hot write
path, bought against removing up to 50 MiB of copied bytes per subscriber. The
third bounds each sweep pass to the matured slice.

### Reading

Three readers change, not two:

- `JdbcRelayRepository` — `PUBLICATION_COLUMNS` and the byte-bounded page.
- `JdbcRelayDeliveryRepository` — the admission read.
- `JdbcRelayLogRepository` — `EVENT_COLUMNS`, its own byte-bounded page, and the
  `ProjectionCache`-backed `Snapshot` DTOs that hold decoded payloads, including
  `snapshotWeight`. This reader was missing from the first draft of this design;
  leaving it out would leave it selecting a dropped column. *(My call: in
  scope.)*

Each becomes a join to `relay_payloads`, with the page and projection budgets
reading `byte_length` instead of computing `octet_length` over the payload. Every
batched read — `consume` returning up to 100 records, the delivery input read,
and the log page — must join once, not fetch per record. This is the specific
anti-pattern the repository rules name, and it is the likeliest way to implement
this change badly.

### Boundary

A new narrow interface, implemented in the relay package and injected by
configuration:

```java
public interface RelayPayloadStore {
  String store(RelayPayload payload, Instant now);          // digest; idempotent, re-arms
  Map<String, RelayStoredPayload> load(Collection<String> digests);
  int sweep(Instant now, int limit);
}
```

`RelayStoredPayload(String digest, String json, long byteLength)` is a
persistence-boundary record, not a domain type: the relay repositories decode it
with `RelayPayloadCodec.read(kind, version, json)` using the family from their own
row, exactly as they decode a `ResultSet` column today. JSON text therefore never
crosses into a domain contract, and the store never needs to know a family.

`load(Collection)` is the store's read half. The three readers do not call it: they
resolve payloads in the SQL join, which is what the join-not-loop requirement
demands, so it has no production caller and exists for tests and for any caller that holds digests directly. A single-digest `load` was specified earlier and removed
as dead: nothing used it, and a caller wanting one digest passes a one-element
collection.
The sweep is a bounded pass registered in `RelayMaintenance.sweep()`, which
already composes bounded passes on the one-minute `cleanupInterval`; it needs no
scheduling of its own.

### Projection-cache triggers

`V176__relay_projection_versions_and_wakeups.sql` installs statement triggers
bumping `snapshot_cache_relay` across `relay_topics`, `relay_publications`,
`relay_admissions` and others, and `JdbcRelayLogRepository` caches DTOs holding
decoded payloads.

`relay_payloads` is **not** added to that trigger set. No cached DTO is keyed by a
payload row: a cached snapshot changes only when a publication or admission
changes, and those tables still bump the counter. A sweep deletes only
unreferenced bytes, which by definition appear in no snapshot. *(My call.)*

This holds under the out-of-band payload commit for the same reason — a committed
payload that nothing references yet invalidates no snapshot, and the referencing
insert that follows bumps the counter as it always did. A payload insert that
fired the trigger would invalidate every cached relay snapshot on every publish,
which is the concrete reason to keep the table out of the set.

Separately, the migration's backfill `UPDATE` of both large tables does fire those
statement triggers inside the Flyway transaction, firing `nextval` and one
`pg_notify('plowshare_projection_changes','relay')` per statement. That is once
per statement, not per row, and is harmless.

## Migration

Three Flyway migrations shipped. The design specified one and the plan proposed two;
reality is:

- `V191__relay_payload_store.sql` — creates `relay_payloads` and its `ejection_at`
  index. Nothing reads it yet.
- `V192__relay_payload_digest.sql` — **expand.** Adds a nullable `payload_digest`
  foreign key to both tables, backfills the table and the digests from the rows
  already present, and adds the two reference indexes. The `payload` column stays, and
  a server that predates the digest keeps working against this schema unchanged.
- `V193__relay_payload_column_drop.sql` — **contract.** Takes `ACCESS EXCLUSIVE` on
  both tables, repeats the backfill for anything written without a digest, sets
  `payload_digest` `NOT NULL` and drops `payload` with the `V135` payload `CHECK`s it
  carried.

Expand, cutover, contract is the shape because the destructive step must not be
applied to servers that still need what it removes:

- `V191` and `V192` are **additive**. They can be applied while instances predating
  the digest are still serving: the old column survives, the new one is nullable, and
  old code neither knows nor needs to know about either.
- The reader cutover and `V193` are the **contract** step and share one commit
  (`bfa205b9`) on purpose. Within a commit there is no window: after `V193` the code
  and the schema have to agree, so they change together.
- The residual risk is **deploy ordering, which no migration can enforce.** `V193`
  must not be applied while any instance predating the cutover is still serving,
  because such an instance selects a column that no longer exists. This is an
  operational constraint on deployment: `V191` and `V192` must be live, and old
  instances drained, before the release carrying the cutover and `V193`. `V193`'s
  `LOCK TABLE` and null-digest backfill cover only the narrow case of an old
  instance *appending* during the migration; they do nothing about an old instance
  *reading* after it. The commit structure does not prevent this problem.
- `V192`'s cost: it is an unqualified `UPDATE` of both of the largest tables, rewriting
  every row in one Flyway transaction and holding row locks on all of them, so concurrent
  `prune` and `admit` block until it completes. (Reasoned from the
  migration's shape, not run.) The dual-write fallback described under
  "Why a data-preserving migration is still right" is offered only for `V193`'s stall;
  whether it would also remove this one was not analysed.

### Why a data-preserving migration is still right

The earlier draft justified a single migration with
[decision 0037](../../decisions/0037-events-and-messaging-on-relay.md): no
installation is being migrated, so existing rows are "instance history" and a clean
break loses nothing that matters. **That reason was wrong for these tables, though
the conclusion stands.** `V189` is later than 0037, concerns exactly these two tables,
and *refuses* to drop their rows: a topic's publications are a live retention window
that subscribers hold durable positions into, and dropping them would turn a carrier
change into unacknowledged retention loss. That is 0037's own functional-need
exception — the case it says does justify retaining data. An admission is likewise
input already handed to a subscriber.

The conclusion survives because nothing is deleted. The backfill derives digests from
the rows already present, so every payload is preserved by reference across the
migration. The reason is "rows must be preserved, and are", not "rows do not matter".
`V189` is also the direct precedent for rewriting payloads in place on these tables,
and `V192` and `V193` say so.

The cost the single-migration framing hid is real: `V193` holds `ACCESS EXCLUSIVE` on
the two largest tables while it backfills, sets `NOT NULL` and drops, over rows that
may each be 50 MiB. The lock is taken first deliberately, so a server that predates
the digest cannot commit a null-digest row between the backfill and `SET NOT NULL`.
When `V192` has already been applied, the backfill in `V193` has only
rows written without a digest to repair; the `NOT NULL` check and the column drop
still run under the lock.

A dual-write release with a bounded background backfiller remains the documented
fallback if a corpus ever makes that stall unacceptable. It was not built
pre-emptively, and adopting it would need its own decision.

## Configuration

| Setting | Default | Meaning |
| --- | --- | --- |
| `plowshare.relay.payload-staging-window` | 1 hour (minimum 5 minutes) | How long unreferenced bytes may sit before becoming sweepable |
| `plowshare.relay.payload-deferral-ceiling` | 24 hours | Maximum examination deferral for a referenced payload |
| `plowshare.relay.payload-sweep-batch` | 100 | Candidates examined per pass |

The last two, with `plowshare.relay.cleanup-interval` (default 1 minute), are bound by
the capacity relation below and are not independent knobs.

The staging window must exceed the longest possible gap between a payload insert
and its referencing insert. Because the payload commits ahead of the append, that
gap is real on every publish and the window is what keeps the sweep off bytes
about to be referenced. One hour is generous against any plausible append, which
is the intent: the only cost of generosity is that an orphan from a failed append
lingers that long, and the only cost of stinginess is reclaiming bytes a publish
still needs.

**The window must exceed the longest append and the expected inter-node clock skew.**
`store` rounds the ejection up to the hour, so protection is between W and W + 1 hour,
and exactly W at an hour boundary. The appender's `now` is the server clock via
`DurableRelay.publish`, while the sweep's is the maintenance node's clock, so a sweep
can delete inside the window only if skew is at least W. At the default that is more
than an hour plus rounding, which is not a real exposure; at a small W it is governed
entirely by W. The setter's floor is therefore 5 minutes: a one-second floor made a
failed publish (a foreign-key violation on the publication insert) configurable.

**Sweep capacity.** Every referenced payload is re-examined at least once per
`payloadDeferralCeiling`, so the sweep must examine the whole corpus in that time.
Throughput is `payloadSweepBatch / cleanupInterval`, and the corpus fits only while

    totalPayloads <= payloadSweepBatch * payloadDeferralCeiling / cleanupInterval

For the defaults that is 100 * 1440 = 144,000 examinations per node-day. Lowering the
ceiling reduces capacity proportionally (at the 1 hour minimum it is 1/24 of that).
Past it the candidate query, `ORDER BY ejection_at LIMIT ?`, serves the backlog of
overdue referenced rows first, so newly orphaned payloads sort behind it and are not
reached, and unreferenced bytes accumulate, which is the growth this design exists to
remove. A saturated pass used to look identical to an idle one, because `sweep` returns
only removals; it now logs a WARN when the candidate list comes back full. That makes
saturation visible; it does not raise capacity.

## Verification

Mocked, by default:

- digest computation and stability for each payload family
- the survivor-extension horizon, including the unsettled-admission fallback and
  the deferral ceiling clamping a ten-year retention
- sweep batching and limit behaviour
- the read path issuing one join rather than per-record loads
- page and projection budgets computed from `byte_length` matching the sizes the
  current `octet_length` windows produce

Behind `@Tag("full-db")` and `-PfullDb`, justified because each assertion is
about actual PostgreSQL behaviour rather than service logic:

- the migration, including backfill correctness and digest agreement
- foreign-key refusal of a referenced delete
- `ON CONFLICT (digest) DO UPDATE ... WHERE` dedup under concurrent identical
  appends, and a repeat append of an already-matured digest being re-armed rather
  than swept (the case `DO NOTHING` failed)
- a sweep concurrent with an append of the same digest
- retention deleting a publication prefix while admissions keep their payload
  reachable
- a cascaded topic or project delete leaving payloads reclaimable within the
  deferral ceiling

Run the smallest relevant set with `--tests`, and state the database-specific
reason when enabling the flag.

## What remains unproven

Stated exactly, so the next reader does not take absence of a failure for a proof.

- **`Relay.Read`'s 319 MiB budget is not observed under a straddle.** Reaching it takes
  seven maximal payloads, and no test builds that. The log inspection page's boundary is
  covered: `theBoundedPageBoundaryMovesByExactlyTheStoredPayloadBytes` binary-searches the
  `boundedProjection` bound and shows it moves 64 bytes for 64 bytes of payload, and
  `page_bytes` is inside that sized projection, so the bigint-to-numeric type change is
  measured there. `storedByteLengthIsTheSameNumberThePagesUsedToComputeInline` asserts
  `byte_length = octet_length(payload::text)` over real rows.
- **`byte_length` is not tied to the bytes by the schema.** No constraint relates it to
  `octet_length(payload::text)`. The store computes it correctly, and every shipped
  migration does, but a future writer that set it wrongly would shift page
  boundaries and the projection-cache bypass threshold silently.
- **`V193`'s `LOCK TABLE` executes but its blocking effect is not asserted.** The
  migration runs under the test, so the statement is valid; no test shows a
  concurrent writer actually waiting on it.
- **Corruption of a stored payload is undetected and permanent** for every row sharing
  its digest (see "Payload bytes are immutable").
- **Deploy ordering is not enforced by anything.** `V193` must not be applied while any
  instance predating the reader cutover is still serving (see "Migration"). No
  migration, lock or test can enforce that.

### Claims established by reading, not by a test

These are believed and reasoned from the code or the schema. None has a test that
would fail if it stopped being true, so check them against the code rather than
trusting them.

- Only the work tier admits; `ReadTierRelayConsumption.consume` calls
  `relay.read(key, limit)` directly and creates no admission row or payload copy.
- Locks are always taken topic first, then payload, and the sweep takes no topic lock,
  so the append's fallback store under the topic lock cannot form a lock cycle.
- A hot digest whose `ejection_at` is already at or beyond `now() + window` cannot be
  a sweep candidate until after the window. This assumes the candidate query's
  `ejection_at <= ?` filter and the hour bucketing of dates.
- A blocked `DELETE` re-evaluates its predicate after the lock wait (PostgreSQL's
  READ COMMITTED behavior of following the update chain). The re-arm in
  `aCandidateReArmedAfterSelectionSurvivesThePassThatSelectedIt` lands before the delete
  statement begins, so a re-arm landing during the wait is unexercised. It is no longer
  load-bearing: the date in the predicate makes correctness independent of it.
- `relay_payloads` is not reachable by cascade from `relay_topics`. This is checkable
  from the schema: the foreign keys point from publications and admissions to the
  payload, never the other way.

## Left to the builder

- Final table and column names.
- Whether the backfill expression is shared with the application digest
  definition or documented as deliberately independent.
- Slice boundaries once decision 1 is settled.

## Companion change, separately scoped

`JdbcRelayRepository.topicsToPrune` has the same defect in a different place:
due-ness derived across two tables, so nothing can index it, costing O(live
topics) plus a sort on every poll. It is fixed by the same mechanism — a stored,
indexed examination time, with the lock holder re-deriving the truth — and is
designed separately in
[relay topic prune due-time index](2026-10-25-relay-topic-prune-due-index-design.md).

The two designs share one invariant, stated there and restated here because both
depend on it: a stored examination time schedules work and never authorizes it.
Here the re-derived truth is reachability; there it is the retained-prefix walk.

They remain separate changes with separate migrations and separate measurement.
