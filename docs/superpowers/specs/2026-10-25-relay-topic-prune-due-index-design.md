# Relay topic prune due-time index

Date: 2026-10-25
Status: design, awaiting approval

## Why this exists

`JdbcRelayRepository.topicsToPrune` asks "which topics have an expirable prefix?"
by joining the log:

```sql
SELECT t.scope_key,t.name FROM relay_topics t
JOIN relay_publications p ON p.scope_key=t.scope_key AND p.topic=t.name
    AND p.position=t.expired_through+1
WHERE p.published_at <= CAST(? AS timestamptz) - t.retention_seconds * interval '1 second'
    OR (t.max_records IS NOT NULL AND t.last_position-t.expired_through > t.max_records)
ORDER BY p.published_at,t.scope_key,t.name LIMIT ?
```

This has been described as a cartesian product. It is not: the join is an exact
primary-key probe on `(scope_key, topic, position)`, one row per topic.

The actual defect is that **due-ness is derived across two tables**, so no index
can answer it. Every poll scans `relay_topics`, probes the log once per live
topic, and sorts the survivors before applying `LIMIT`. The cost is O(live
topics) plus a sort, on every poll, forever — which is precisely the axis that
hurts when many topics are alive at once.

## The approach

The same shape the payload store uses for reclamation
([payload store design](2026-10-25-relay-payload-store-design.md)): store an
indexed time that says *when this row next deserves examination*, select a batch
by that index, and let the existing code re-derive the truth under the lock it
already holds.

The two designs then share one invariant, which is worth stating once and citing
in both:

> A stored examination time schedules work. It never authorizes it. The holder of
> the lock re-derives what is actually true. A time that is too early costs one
> wasted examination; too late delays the work by one window. Neither can produce
> a wrong outcome.

For the payload store the re-derived truth is reachability. Here it is the
retained-prefix walk `prune` already performs, including its deliberate handling
of wall-clock correction ("Remove only a prefix, even if wall-clock correction
made a later timestamp older").

## Goals

- `topicsToPrune` becomes an indexed scan of `relay_topics` with no join and no
  sort of a derived column.
- Candidate selection cost tracks topics that are actually due, not topics that
  exist.
- No change to what gets pruned, when, or in what order relative to the current
  behaviour.
- Maintenance happens under the topic row lock the writer already holds, so no
  new locking and no new transaction boundaries.

## Non-goals

- Changing retention policy, `max_records` semantics, or the prefix-only rule.
- Changing `prune` itself beyond maintaining the new column.
- Partitioning `relay_publications`. Splitting the table leaves this predicate
  exactly as unindexable as it is now, and costs the composite foreign keys and
  cascades; if partitioning is ever wanted it should be native declarative
  partitioning, which the existing primary key already supports because it leads
  with `(scope_key, topic)`.

## Anti-goals

- A trigger on `relay_publications` maintaining the column. The writers already
  hold the topic row lock; a trigger would add a second mechanism for something
  one `UPDATE` in the existing statement can do.
- A column that is treated as authoritative about what may be deleted.
- Two separate indexed queries unioned to cover the two prune reasons.

## Design

### The column

```sql
ALTER TABLE relay_topics ADD COLUMN prune_due_at TIMESTAMPTZ;

CREATE INDEX relay_topics_prune_due
    ON relay_topics(prune_due_at)
    WHERE prune_due_at IS NOT NULL;
```

`NULL` means "nothing to prune" — an empty topic, or one whose whole retained
suffix is still inside its retention window. The partial index therefore contains
only topics that will become due, which is the set the worker cares about.

Truncate the stored value to the hour, as the payload store does, so a pass
collects a batch of topics sharing a bucket rather than dribbling one row at a
time.

### The query it replaces

```sql
SELECT scope_key,name FROM relay_topics
WHERE prune_due_at <= CAST(? AS timestamptz)
ORDER BY prune_due_at
LIMIT ?
```

An index-only range scan over the partial index, already in the order the current
query sorts for. `prune` is unchanged and remains the authority.

### Both prune reasons in one column

Retention and `max_records` are different questions, and the current `WHERE`
`OR`s them. Keeping one indexed column means folding both into the stored time:

- **Retention:** the oldest retained publication's `published_at +
  retention_seconds`.
- **Over `max_records`:** immediately due. Store the current time, so the row
  sorts ahead of time-based candidates.

So `prune_due_at` is the earlier of those two when both apply. The `max_records`
half is already computable from `relay_topics` columns alone, which is why it
needs no probe to maintain.

### Maintenance points

All three run inside transactions that already hold the topic row lock.

| Site | Action |
| --- | --- |
| `append` | If the topic had no retained rows (`last_position == expired_through` before the append), the new row is the oldest retained: set `prune_due_at` from its `published_at + retention_seconds`. Then, if `last_position - expired_through > max_records`, set it to now instead. |
| `prune` | After advancing `expired_through`, recompute from the new oldest retained row — one primary-key probe at `expired_through + 1` — or `NULL` if the topic is now empty. Re-apply the `max_records` check. |
| topic policy update | Recompute, because `retention_seconds` and `max_records` are inputs. This is the maintenance point most easily missed; the builder must find every writer of those two columns, including the `topics.json` override path. |

### Migration

One new Flyway migration: add the column and partial index, then backfill with
the existing derived query run exactly once —

```sql
UPDATE relay_topics t SET prune_due_at = date_trunc('hour', LEAST(
    p.published_at + t.retention_seconds * interval '1 second',
    CASE WHEN t.max_records IS NOT NULL
          AND t.last_position - t.expired_through > t.max_records
         THEN now() END))
FROM relay_publications p
WHERE p.scope_key = t.scope_key AND p.topic = t.name
  AND p.position = t.expired_through + 1;
```

Topics with no retained publication match nothing and keep `NULL`, which is
correct. The expensive join runs once at migration time instead of on every poll.

Note `LEAST` ignores `NULL` in PostgreSQL, so the `CASE` yielding `NULL` when not
over `max_records` behaves as intended — worth a comment, since the opposite
convention is common in other databases.

### A self-healing property worth keeping

Because `prune` re-derives the prefix under the lock and only advances
`expired_through` for rows that genuinely expired, a wrong `prune_due_at` cannot
delete anything early. A too-early value costs one `prune` call that deletes zero
rows and recomputes the column; a missed update delays expiry until the next
maintenance touch. That is what makes it safe to maintain this column in three
places rather than with a trigger.

## Verification

Mocked, by default:

- `prune_due_at` set on first append to an empty topic, and left alone on
  subsequent appends
- recomputation after `prune`, including the transition to `NULL` on an emptied
  topic
- `max_records` overflow making a topic immediately due, and the earlier-of rule
  when both reasons apply
- recomputation when retention or `max_records` changes
- a stale-early value producing a `prune` that deletes nothing and self-corrects

Behind `@Tag("full-db")` and `-PfullDb`, because each is about actual PostgreSQL
behaviour:

- the migration's backfill agreeing, topic for topic, with the query it replaces
- the partial index being used by the new query (`EXPLAIN`)
- `LEAST` with a `NULL` argument behaving as assumed
- concurrent append and prune on one topic leaving a consistent column

## Left to the builder

- Column name.
- Every writer of `retention_seconds` / `max_records` that needs the
  recomputation, which this design asserts exists but does not enumerate.
- Whether the recomputation is a small private helper on the repository or an
  inlined `UPDATE` at each of the three sites.

## What this design does not claim

The O(topics) characterisation is read from the SQL and the schema, not measured
with `EXPLAIN (ANALYZE)` against a loaded database. The shape is not in doubt; the
magnitude, and therefore the priority of this change against others in the Relay
analysis, is not established here.
