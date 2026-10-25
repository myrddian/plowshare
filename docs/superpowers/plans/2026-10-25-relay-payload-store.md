# Relay content-addressed payload store — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Store each distinct Relay payload once, in a content-addressed table that
publications and admissions reference by digest, reclaimed by a swept reachability
predicate with no reference count.

**Architecture:** A new `relay_payloads` table keyed by SHA-256 digest holds the bytes.
`relay_publications.payload` and `relay_admissions.payload` become `payload_digest`
foreign keys, so one event admitted by *n* subscribers stores one copy instead of
*n*+1, and identical republished content collapses too. The payload row commits ahead
of the append in its own transaction; an orphan from a failed append is reclaimed by
the sweep, which deletes only rows no publication or admission references.

**Tech Stack:** Java 21, Spring Boot, Spring JDBC (`JdbcTemplate`), Flyway, PostgreSQL 16,
JUnit, Testcontainers for the `full-db` tier.

**Spec:** [`docs/superpowers/specs/2026-10-25-relay-payload-store-design.md`](../specs/2026-10-25-relay-payload-store-design.md)

## Corrections to the spec found while grounding this plan

The spec was written against `V188`. Main now carries `V189` and `V190`, which change
two of its statements. **Both corrections are assumed by the tasks below.**

1. **There are five payload families, not six.** `V189__relay_general_message_payload.sql`
   removed `INFORMATION_CHANGED`, returning the vocabulary to `EMPTY`, `TEXT`,
   `SCHEDULE_DUE`, `LIFECYCLE`, `WAKE_REQUESTED` — which is what `RelayPayload.Kind`
   declares. The spec's "current six families" is stale.

2. **The migration justification was wrong, though its conclusion stands.** The spec
   resolved the migration question by citing decision 0037 — no installation being
   migrated, existing rows "instance history, not a contract". `V189` is later, concerns
   these exact tables, and refuses to drop their rows: an `information.changed` topic's
   publications are "a live four-day delivery window that readers still hold durable
   positions into, so dropping them would turn a carrier change into unacknowledged
   retention loss for every subscriber." That is 0037's own functional-need exception.
   The conclusion is unaffected because this migration deletes nothing — it preserves
   every payload by reference — but the reason is "rows must be preserved and they are",
   not "rows do not matter". `V189` is also the direct precedent for rewriting payloads
   in place on these tables.

3. **Reuse the existing hash.** `RelayPort.hash(String)` in `plowshare-protocol` is
   already SHA-256 over UTF-8 as lowercase hex, and `V189` uses it as a convention. The
   digest uses it rather than a new utility; the spec's `handler_hash` reference was only
   about the column's shape.

## Proposed refinement for review

The spec says **one** Flyway migration performing create → backfill → enforce → drop.
This plan uses **two migrations within the one release** (`V192` additive, `V194` the
drop), because the Java cutover cannot land in the same commit as a column drop without
a window where the deployed code selects a column the schema no longer has. Expand,
cut over, contract — each task independently testable, no transient broken build.

This is **not** the cross-release dual write that decision 0037 rejected; nothing is
written twice and no compatibility layer serves an old client. If the reviewer prefers
the spec's single migration, Tasks 2 and 4 merge into one larger, atomic task.

## Global Constraints

- New Flyway migrations only; never edit a shipped migration. Next free number is `V192`.
- Only repositories and database configuration may touch `JdbcTemplate`. No SQL in
  services, tools or adapters.
- Raw JSON stays at the persistence boundary. `RelayPayloadCodec` is package-private and
  must remain the only decoder; no `JsonNode`, `Map<String,Object>` or JSON text in a
  domain contract.
- Google Java Style via `./gradlew format`; `./gradlew formatCheck` must pass.
- Database tests are mocked by default. Real-PostgreSQL tests carry `@Tag("full-db")`
  and run only under `-PfullDb`, selected with `--tests`. Never run the full database
  suite.
- No hard-coded deployment values. New settings go on `RelayProperties`
  (`@ConfigurationProperties("plowshare.relay")`) with validation in their setters,
  matching `setCleanupInterval` / `setPublicationBatchSize`.
- Payload byte bounds stay exactly as `V135` set them: `text` ≤ `52428800`, encoded
  envelope ≤ `314573824`.

## Review Focus

Input classes the spec implies but whose tests no task would otherwise own. Each has its
test placed in the task named.

1. **A payload whose JSONB rendering differs from the application's serialization** —
   migration digests come from `payload::text`, runtime digests from
   `RelayPayloadCodec.write`. Expected: both resolve, worst case two rows of equivalent
   JSON; never a failed read. *(Task 4)*
2. **A sweep running concurrently with an append of the same digest** — expected: the
   payload survives, because the referencing insert and the foreign key serialise
   against the delete. *(Task 5)*
3. **A topic or project deleted by cascade, orphaning payloads unobserved** — expected:
   reclaimed within the deferral ceiling, not after `retention_seconds`, which `V117`
   permits to be ten years. *(Task 3)*
4. **A read page whose payloads sum past the byte budget** — expected: `byte_length`
   produces byte-identical page boundaries to today's `octet_length(payload::text)`,
   since the page size is observable. *(Task 4)*
5. **An append that fails after its payload commits** — expected: no error about the
   payload, the orphan is reclaimed, and a retry with the same content inserts nothing
   new. *(Task 2)*

---

### Task 1: The payload store, standing alone

Additive only. Nothing references `relay_payloads` yet, so the application is unaffected.

**Files:**
- Create: `plowshare-server/src/main/resources/db/migration/V192__relay_payload_store.sql`
- Create: `plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/RelayPayloadStore.java`
- Create: `plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/RelayStoredPayload.java`
- Create: `plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/JdbcRelayPayloadStore.java`
- Modify: `plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/RelayConfiguration.java`
- Modify: `plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/RelayProperties.java`
- Test: `plowshare-server/src/test/java/io/aeyer/plowshare/server/relay/RelayPayloadStoreTest.java`

**Interfaces:**
- Consumes: `RelayPayload`, `RelayPayloadCodec.write`, `RelayPort.hash`, `UnitOfWork`.
- Produces:
  ```java
  public record RelayStoredPayload(String digest, String json, long byteLength) {}

  public interface RelayPayloadStore {
    String store(RelayPayload payload, Instant now);
    RelayStoredPayload load(String digest);
    Map<String, RelayStoredPayload> load(Collection<String> digests);
    int sweep(Instant now, int limit);
  }
  ```
  `RelayProperties` gains `payloadStagingWindow` (`Duration`, default 1 hour),
  `payloadDeferralCeiling` (`Duration`, default 24 hours) and `payloadSweepBatch`
  (`int`, default 100), each validated in its setter.

- [ ] **Step 1: Write the failing tests**

`RelayPayloadStoreTest`, against a mocked `JdbcTemplate`/in-memory fake:
```java
@Test void storeReturnsLowercaseHexDigestOfTheEncodedPayload()
@Test void storeIsIdempotentForIdenticalContent()        // same digest, one insert attempt
@Test void storeTruncatesEjectionToTheHour()
@Test void loadMissingDigestThrows()
@Test void batchLoadIssuesOneQueryForManyDigests()       // not one per digest
@Test void propertiesRejectNonPositiveStagingWindow()
@Test void propertiesRejectSweepBatchAboveOneThousand()
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :plowshare-server:test --tests 'io.aeyer.plowshare.server.relay.RelayPayloadStoreTest'`
Expected: FAIL — `RelayPayloadStore` does not exist.

- [ ] **Step 3: Write `V192__relay_payload_store.sql`**

Creates `relay_payloads` exactly as the spec's DDL block gives it: `digest` TEXT primary
key checked against `^[0-9a-f]{64}$`, `payload` JSONB carrying `V135`'s two byte bounds,
`byte_length` BIGINT NOT NULL positive, `ejection_at` TIMESTAMPTZ NOT NULL,
`created_at` TIMESTAMPTZ NOT NULL DEFAULT `now()`, plus
`CREATE INDEX relay_payloads_ejection ON relay_payloads(ejection_at)`.

Comment the governing invariant on the table, in the spec's words: `ejection_at` defers
the next examination and never authorizes a deletion; reachability is the only authority.

Do **not** add `relay_payloads` to `V176`'s projection-change trigger set.

- [ ] **Step 4: Implement the store**

`JdbcRelayPayloadStore` implements `RelayPayloadStore`. `store` encodes with
`RelayPayloadCodec.write`, digests with `RelayPort.hash`, and inserts with
`ON CONFLICT (digest) DO NOTHING`, setting `byte_length` from
`octet_length((?::jsonb)::text)` so the stored length is PostgreSQL's rendering, not the
digest input's. `sweep` is the spec's two queries: select matured candidates ordered by
`ejection_at`, then a guarded `DELETE … WHERE NOT EXISTS` per candidate. Until Task 2
adds the referencing columns, have `sweep` take the candidate query only and return 0;
Task 3 completes it.

> **Superseded during Task 5:** the `DO NOTHING` clause above was reversed. A repeat append of an already-matured digest was left sweepable across the commit-ahead window, and the sweep's delete made the appender's publication insert fail its foreign key. The shipped clause is a conditional `DO UPDATE` that re-arms `ejection_at`; the design's "Writing" section records why. The text above is kept as the record of intent.

Register the bean in `RelayConfiguration` beside the other relay repositories.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :plowshare-server:test --tests 'io.aeyer.plowshare.server.relay.RelayPayloadStoreTest'`
Expected: PASS

- [ ] **Step 6: Format and commit**

```bash
./gradlew format formatCheck
git add plowshare-server/src/main/resources/db/migration/V192__relay_payload_store.sql \
        plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/RelayPayloadStore.java \
        plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/RelayStoredPayload.java \
        plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/JdbcRelayPayloadStore.java \
        plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/RelayConfiguration.java \
        plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/RelayProperties.java \
        plowshare-server/src/test/java/io/aeyer/plowshare/server/relay/RelayPayloadStoreTest.java
git commit -m "Add the Relay content-addressed payload store"
```

---

### Task 2: Reference the store from the append path

`payload_digest` arrives populated alongside the existing `payload` column. Readers
still read `payload`, so this task changes what is written, not what is read.

**Files:**
- Create: `plowshare-server/src/main/resources/db/migration/V193__relay_payload_digest.sql`
- Modify: `plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/JdbcRelayRepository.java` (`append`)
- Modify: `plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/JdbcRelayDeliveryRepository.java` (`admit`)
- Test: `plowshare-server/src/test/java/io/aeyer/plowshare/server/relay/RelayPayloadWritePlacementTest.java`

**Interfaces:**
- Consumes: `RelayPayloadStore.store` from Task 1.
- Produces: both tables carry `payload_digest TEXT` (nullable in this migration,
  enforced in Task 4) with a foreign key to `relay_payloads(digest)` and an index on it.

- [ ] **Step 1: Write the failing tests**

```java
@Test void appendCallsStoreBeforeTheRepositoryOpensItsOwnUnitOfWork()   // store called outside the append's UnitOfWork
@Test void appendWritesTheDigestReturnedByTheStore()
@Test void admissionCopiesTheDigestAndStoresNoBytes()
@Test void aFailedAppendLeavesTheCommittedPayloadWithoutReportingIt()
@Test void retryingAFailedAppendReusesTheSameDigest()
```
The last two are Review Focus item 5.

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :plowshare-server:test --tests 'io.aeyer.plowshare.server.relay.RelayPayloadWritePlacementTest'`
Expected: FAIL — `append` does not call the store.

- [ ] **Step 3: Write `V193__relay_payload_digest.sql`**

Adds `payload_digest TEXT` to `relay_publications` and `relay_admissions`, each
`REFERENCES relay_payloads(digest)`; backfills both from
`encode(sha256(convert_to(payload::text,'UTF8')),'hex')` after inserting the distinct
payloads those rows imply into `relay_payloads`; creates
`relay_publications(payload_digest)` and `relay_admissions(payload_digest)` indexes.
Leave `payload` in place and the new column nullable.

Comment why rows are rewritten rather than discarded, citing `V189`: these publications
are a live retention window that subscribers hold durable positions into.

- [ ] **Step 4: Move the payload write ahead of the append**

In `JdbcRelayRepository.append`, call `store` **before** entering
`transactions.inTransaction`, and write the returned digest inside the existing insert
alongside `payload`. The idempotent-republish branch that returns a retained publication
must not store anything. In `JdbcRelayDeliveryRepository.admit`, copy the publication's
digest into the admission insert.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :plowshare-server:test --tests 'io.aeyer.plowshare.server.relay.RelayPayloadWritePlacementTest'`
Expected: PASS

- [ ] **Step 6: Format and commit**

```bash
./gradlew format formatCheck
git commit -am "Write Relay payloads to the store ahead of the append"
```

---

### Task 3: Swept reclamation

**Files:**
- Modify: `plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/JdbcRelayPayloadStore.java` (`sweep`)
- Modify: `plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/RelayMaintenance.java`
- Test: `plowshare-server/src/test/java/io/aeyer/plowshare/server/relay/RelayPayloadSweepTest.java`

**Interfaces:**
- Consumes: `payload_digest` columns from Task 2; `RelayProperties` settings from Task 1.
- Produces: `RelayMaintenance.sweep()` includes the payload pass in its returned count.

- [ ] **Step 1: Write the failing tests**

```java
@Test void deletesAnUnreferencedMaturedPayload()
@Test void keepsAPayloadReferencedByAPublication()
@Test void keepsAPayloadReferencedOnlyByAnAdmission()
@Test void ignoresAPayloadWhoseEjectionIsInTheFuture()
@Test void extendsASurvivorToItsFurthestReferenceHorizon()
@Test void clampsASurvivorExtensionToTheDeferralCeiling()      // ten-year retention, Review Focus 3
@Test void fallsBackToTheStagingWindowForAnUnsettledAdmission()
@Test void honoursTheConfiguredBatchLimit()
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :plowshare-server:test --tests 'io.aeyer.plowshare.server.relay.RelayPayloadSweepTest'`
Expected: FAIL — `sweep` returns 0 unconditionally.

- [ ] **Step 3: Complete `sweep`**

Delete guarded by `NOT EXISTS` against both referencing tables. For a survivor, compute
the new `ejection_at` as the maximum earliest-possible-removal across its references —
`published_at + relay_topics.retention_seconds` for a publication,
`admitted_at + settledRetention` for a settled admission, `now + stagingWindow` when any
admission is unsettled — then `LEAST` that against `now + payloadDeferralCeiling` and
truncate to the hour.

- [ ] **Step 4: Register the pass in `RelayMaintenance.sweep()`**

Add the store as a constructor dependency and call
`payloads.sweep(now, properties.getPayloadSweepBatch())`, adding its result to `deleted`,
placed after `repository.prune` so a pass reclaims payloads the same pass unreferenced.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :plowshare-server:test --tests 'io.aeyer.plowshare.server.relay.RelayPayloadSweepTest'`
Expected: PASS

- [ ] **Step 6: Format and commit**

```bash
./gradlew format formatCheck
git commit -am "Reclaim unreferenced Relay payloads by reachability"
```

---

### Task 4: Cut the readers over and drop the column

The atomic cutover: all three readers stop selecting `payload` in the same commit the
column is dropped.

**Files:**
- Create: `plowshare-server/src/main/resources/db/migration/V194__relay_payload_column_drop.sql`
- Modify: `plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/JdbcRelayRepository.java` (`PUBLICATION_COLUMNS:25-26`, the bounded page at `:276-281`)
- Modify: `plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/JdbcRelayLogRepository.java` (`EVENT_COLUMNS`, its bounded page, `boundedProjection`, `snapshotWeight`)
- Modify: `plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/JdbcRelayDeliveryRepository.java` (the admission read)
- Test: `plowshare-server/src/test/java/io/aeyer/plowshare/server/relay/RelayPayloadReadPathTest.java`

**Interfaces:**
- Consumes: `payload_digest` and the store from Tasks 1–2.
- Produces: no reader selects `payload`; `payload_digest` is `NOT NULL` on both tables.

- [ ] **Step 1: Write the failing tests**

```java
@Test void publicationPageIssuesOneJoinNotOneQueryPerRecord()
@Test void logPageIssuesOneJoinNotOneQueryPerRecord()
@Test void admissionReadIssuesOneJoinNotOneQueryPerRecord()
@Test void pageBoundariesMatchTheOctetLengthBudgetTheyReplace()   // Review Focus 4
@Test void projectionBypassTriggersAtTheSameThreshold()
@Test void aJsonbRenderingDigestAndAnApplicationDigestBothResolve() // Review Focus 1
@Test void snapshotWeightCountsDecodedPayloadsAsBefore()
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :plowshare-server:test --tests 'io.aeyer.plowshare.server.relay.RelayPayloadReadPathTest'`
Expected: FAIL — readers still select `payload`.

- [ ] **Step 3: Join in all three readers**

Replace `payload::text` in `PUBLICATION_COLUMNS` and `EVENT_COLUMNS` with a join to
`relay_payloads` on `payload_digest`, selecting its `payload::text` and `byte_length`.
Both bounded pages and `boundedProjection` take their running sum from the joined
`byte_length` instead of `octet_length(payload::text)`, keeping the `+ 4096` per-row
headroom and the same thresholds. Decoding stays with each reader, using the family from
its own row — the store never learns a family.

- [ ] **Step 4: Write `V194__relay_payload_column_drop.sql`**

Backfills any `payload_digest` still null, sets both columns `NOT NULL`, drops `payload`
from `relay_publications` and `relay_admissions`, and with it the `V135` payload `CHECK`s
those tables carry.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :plowshare-server:test --tests 'io.aeyer.plowshare.server.relay.RelayPayloadReadPathTest'`
Expected: PASS

- [ ] **Step 6: Format and commit**

```bash
./gradlew format formatCheck
git commit -am "Read Relay payloads through the store and drop the copied columns"
```

---

### Task 5: Real-PostgreSQL verification

The assertions that are about PostgreSQL behaviour rather than service logic.

**Files:**
- Create: `plowshare-server/src/test/java/io/aeyer/plowshare/server/relay/RelayPayloadStoreDatabaseTest.java`

**Interfaces:**
- Consumes: everything above.
- Produces: nothing; this task adds coverage only.

- [ ] **Step 1: Write the tagged tests**

`@Tag("full-db")`, using the `pgvector/pgvector:…-pg16-…` image the existing end-to-end
tests use, not stock `postgres:16`:
```java
@Test void migrationsBackfillEveryExistingPayloadByDigest()
@Test void deletingAReferencedPayloadIsRefusedByTheForeignKey()
@Test void concurrentIdenticalAppendsStoreOnePayload()
@Test void aSweepConcurrentWithAnAppendOfTheSameDigestKeepsThePayload()  // Review Focus 2
@Test void retentionDeletesAPrefixWhileAdmissionsKeepTheirPayload()
@Test void aCascadedProjectDeleteLeavesPayloadsReclaimable()
```

- [ ] **Step 2: Run them and verify they pass**

Run: `./gradlew :plowshare-server:test -PfullDb --tests 'io.aeyer.plowshare.server.relay.RelayPayloadStoreDatabaseTest'`
Expected: PASS. State the database-specific reason when enabling the flag: these assert
Flyway migration behaviour, foreign-key refusal, unique-index dedup under concurrency,
and cascade interaction — none reachable without real PostgreSQL.

- [ ] **Step 3: Whole-module check**

Run: `./gradlew check`
Expected: PASS, no Docker tests.

- [ ] **Step 4: Commit**

```bash
git commit -am "Verify the Relay payload store against PostgreSQL"
```

---

### Task 6: Correct the spec

**Files:**
- Modify: `docs/superpowers/specs/2026-10-25-relay-payload-store-design.md`

- [ ] **Step 1: Apply the three corrections**

Five families not six; the migration reason replaced with `V189`'s — rows are preserved
because subscribers hold durable positions into the retention window, not because they
do not matter; and `RelayPort.hash` named as the digest function. If the reviewer
accepted the two-migration refinement, record that too, with its reasoning.

- [ ] **Step 2: Commit**

```bash
git commit -am "Correct the payload store design against V189"
```

## Self-review notes

- **Spec coverage:** table, digest definition, commit-ahead write, family-agnostic store,
  required `byte_length`, three readers joining, capped deferral, swept reclamation,
  configuration, `V176` exclusion, migration, and the full-db list all map to a task. The
  spec's "left to the builder" items — final names, whether the backfill expression is
  shared or documented as independent — stay open by design.
- **Type consistency:** `RelayStoredPayload(digest, json, byteLength)` and the four
  `RelayPayloadStore` methods are used under those exact names in Tasks 2–4.
- **Proportion:** the plan is shorter than the spec and carries no method bodies; the one
  algorithm not determined by its signature — the survivor horizon — is given as prose in
  Task 3 Step 3 rather than code.
- **Not covered by any task:** whether the prune due-index design is worth building, which
  needs measurement, not implementation.
