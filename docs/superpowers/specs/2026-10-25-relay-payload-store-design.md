# Relay content-addressed payload store

Date: 2026-10-25
Status: design, awaiting approval

## Why this exists

A Relay payload may carry 50 MiB of raw UTF-8 TEXT
(`V135__relay_text_byte_limit.sql`), and the schema stores those bytes twice over
by construction. `relay_publications` holds the appended record.
`relay_admissions` then copies the payload once **per subscriber** that admits the
position, in `JdbcRelayDeliveryRepository.admit`, because the copy is what allows
retention to delete a prefix of the log while work already admitted keeps its
input. One event near the ceiling, admitted by *n* subscribers, is 50 MiB × (1 +
*n*) on disk.

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
`relay_deliveries.handler_hash` use the same form.

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
disjoint field sets across the current six families.

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

`DO NOTHING` rather than `DO UPDATE`. A repeat publish of a hot digest needs no
row version written — the referencing publication row it is about to insert is
what protects the payload from the next sweep, and the sweep's own survivor
extension maintains the horizon. `DO UPDATE` would add a dead tuple per repeat on
the hottest row in the table for no correctness gain. *(My call.)*

Admission writes the same digest it read from the publication. It stores no bytes
at all; the *n*-way amplification disappears here rather than being compressed.

`ejection_at` on insert is `now()` plus the configured staging window, truncated
to the hour.

**The insert commits ahead of the append, in its own transaction.** *(Decided.)*

`JdbcRelayRepository` joins the caller's domain transaction, so an append can sit
inside a long-lived one. Were the payload insert to share it, `ON CONFLICT
(digest) DO NOTHING` against a digest another uncommitted transaction had just
inserted would block on the unique index until that transaction ended — two
publishers in unrelated topics and scopes, contending today on nothing,
serialising on identical content. Dedup makes that collision *more* likely, since
identical content is the case it exists to exploit.

Committing the payload first removes that, and makes the staging path a 50 MiB
payload will eventually want reachable rather than hypothetical. Three
consequences follow and must be built deliberately:

- **The staging window is load-bearing now.** Between the payload commit and the
  referencing insert the bytes are unreferenced, and only the window protects
  them from the sweep. It must comfortably exceed the longest append.
- **A rolled-back or failed append leaves an orphan payload.** This is correct and
  self-correcting: the sweep reclaims it once the window matures. It is never a
  wrong read, because nothing references it.
- **Append's failure handling grows a case.** A payload committed for an append
  that then fails is not an error to report; the retry re-derives the same digest
  and the `ON CONFLICT` makes the second insert a no-op.

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
DELETE FROM relay_payloads p WHERE p.digest = ?
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

#### Why the deferral is capped

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
  String store(RelayPayload payload, Instant now);          // digest; idempotent
  RelayStoredPayload load(String digest);
  Map<String, RelayStoredPayload> load(Collection<String> digests);
  int sweep(Instant now, int limit);
}
```

`RelayStoredPayload(String digest, String json, long byteLength)` is a
persistence-boundary record, not a domain type: the relay repositories decode it
with `RelayPayloadCodec.read(kind, version, json)` using the family from their own
row, exactly as they decode a `ResultSet` column today. JSON text therefore never
crosses into a domain contract, and the store never needs to know a family.

The batch `load` exists so the join-not-loop requirement has somewhere to live.
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

One new Flyway migration:

1. Create `relay_payloads`.
2. Backfill distinct payloads from both source tables, using
   `encode(sha256(convert_to(payload::text,'UTF8')),'hex')`.
3. Add `payload_digest` to both tables, populate from the same expression, set
   `NOT NULL`, add the foreign keys and indexes.
4. Drop `payload` from `relay_publications` and `relay_admissions`, and with it
   the `V135` payload `CHECK`s those tables carry.

Steps 2–4 rewrite both of the largest tables in the schema, holding `ACCESS
EXCLUSIVE` while doing so, over rows that may individually be 50 MiB.

**One migration is correct, and a dual-write release is not merely unnecessary
but contrary to accepted policy.**
[Decision 0037](../../decisions/0037-events-and-messaging-on-relay.md) settles
this: "No installation is being migrated, so there is nothing for a compatibility
layer to serve", and it considered and rejected a dual-write release for exactly
the continuity this one would buy. It also sets the bar for the one case that
would justify retaining data — a functional need, such as topic expiration losing
input a consumer still requires — and states that backward compatibility is never
that justification.

No such functional need arises here. The backfill derives digests from the rows
already present, so the clean break loses nothing: existing publications and
admissions keep their payloads, by reference, across the migration. The
`ACCESS EXCLUSIVE` stall is therefore bounded by whatever a development
installation holds, not by a production corpus.

If that ever stops being true, the dual-write path is the documented fallback:
nullable `payload_digest`, dual write, a bounded background backfiller, then a
second migration to enforce `NOT NULL` and drop `payload`. It should not be built
pre-emptively, and adopting it would need its own decision against 0037.

## Configuration

| Setting | Default | Meaning |
| --- | --- | --- |
| `plowshare.relay.payload-staging-window` | 1 hour | How long unreferenced bytes may sit before becoming sweepable |
| `plowshare.relay.payload-deferral-ceiling` | 24 hours | Maximum examination deferral for a referenced payload |
| `plowshare.relay.payload-sweep-batch` | 100 | Candidates examined per pass |

The staging window must exceed the longest possible gap between a payload insert
and its referencing insert. Because the payload commits ahead of the append, that
gap is real on every publish and the window is what keeps the sweep off bytes
about to be referenced. One hour is generous against any plausible append, which
is the intent: the only cost of generosity is that an orphan from a failed append
lingers that long, and the only cost of stinginess is reclaiming bytes a publish
still needs.

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
- `ON CONFLICT (digest) DO NOTHING` dedup under concurrent identical appends, and
  whichever contention behaviour decision 2 selects
- a sweep concurrent with an append of the same digest
- retention deleting a publication prefix while admissions keep their payload
  reachable
- a cascaded topic or project delete leaving payloads reclaimable within the
  deferral ceiling

Run the smallest relevant set with `--tests`, and state the database-specific
reason when enabling the flag.

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
