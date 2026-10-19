# JdbcProcRepository

Status: proposed; no runtime implementation. Baseline: `fc36351f239b62984363ababaa1de1139402aafb`.

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/proc/JdbcProcRepository.java#L180) · [caller/contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/proc/ProcAudit.java#L1) · [schema/routines](../../plowshare-server/src/main/resources/db/migration/V85__durable_inference_accounting.sql#L1). Cache mechanics and recovery follow the [shared strategy](../query-efficiency-and-cache-design.md#server-reuse-and-tracked-database-dtos).

`ProcAudit` provides conversation, queue, inference-call and tool views. Its recursive `TREE` begins at every root, then applies visibility and selection filters. Conversation listing builds that tree once for total and again for page; the page already uses `MATERIALIZED` to bound later lateral enrichment. Queue views aggregate steps/paragraphs/chunks and embedding-slot readiness; tools expand JSON and correlate result entries. These are plausible expensive audit reads, not live timing evidence.

Prioritize one authorized candidate tree per operation feeding total and page, using a bounded materialized CTE or typed table-returning routine; retain root lifecycle and fleet/mine custody rules. Push safe root/owner/selection filters into recursion only after equivalence proof. Batch page enrichment aggregates instead of repeated per-row lateral scans where plans show repetition. Inspect project/account casts, latest-job ordering, active-call and entries result predicates against existing indexes; V85's root-conversation time index is not automatically the direct-conversation predicate. Keep payload projections narrow and preserve keyset limits. Share resolved account/scope DTOs within the read transaction.

Treat active queues, leases, in-memory live-run input and current visibility as authoritative. Initially cache only immutable historical call/tool detail, keyed by database/account/authorized scope/filter/projection/version. Broader proc pages require a generation incorporating conversation, jobs, accounting, information, embedding slots and entries; this fan-out may make shared page caching unattractive. Every contributing writer must invalidate entity and result membership after commit, with durable recovery before bounded LRU admission. Client coalescing and async dispatch can help without caching authority.

Mock duplicate-tree/selector budgets. Focused PostgreSQL tests cover recursive visibility equivalence, bounded enrichments and index plans when implemented.
