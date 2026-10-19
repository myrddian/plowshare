# JdbcScriptStore

Status: proposed; no runtime implementation. Baseline: `fc36351f239b62984363ababaa1de1139402aafb`.

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/scripted/JdbcScriptStore.java#L20) · [caller/contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/scripted/ScriptStore.java#L1) · [schema/routines](../../plowshare-server/src/main/resources/db/migration/V84__scripted_orchestrations.sql#L1). Cache mechanics and recovery follow the [shared strategy](../query-efficiency-and-cache-design.md#server-reuse-and-tracked-database-dtos).

`ScriptStore` is a durable step journal: prepare validates pinned source hash and previous completed state, inserts the next command, and reads latest, delegate results/failures and resume utterances. Started/executed/completed transitions are conditional; raw paid results survive later post-hook failure. `prepareNext` rereads the completed receipt to verify input/result, which is a concurrency/integrity check rather than automatically wasteful.

Carry the typed `Step` returned from prepare/latest through execution instead of reloading it solely for command/hash fields. Classify any previous-receipt reread before removing it; a typed verified preparation snapshot can avoid duplicate reads only within an equivalent transaction/version fence. V84's conversation/sequence key supports latest descending and exact step reads. Profile delegate joins on parent/opened_by_call and latest turn before adding indexes. Replace wildcard latest projection with mapped fields; use `RETURNING` for transitions only when a proven follow-up read is removed. Keep JavaTimeModule and JSON boundary validation intact.

Immutable source-hash/completed-state or completed-result snapshots can use database/conversation/sequence/source-hash/projection keys under bounded LRU. Running/started flags, readiness-observer retries and execution eligibility remain authoritative. Prepare/started/executed/retry/completed plus conversation/turn writes invalidate step/latest/delegate-result collections after commit. Conditional retry paths mean started state is not immutable. Durable generation and listener recovery must precede shared admission; no cache miss or notification authorizes replaying paid/uncertain work.

Mock DTO reuse and post-hook retry without another effect. Focused PostgreSQL tests cover conditional journal transitions, delegate selection and transaction rollback if SQL changes.
