# JdbcActingWorkRepository

Status: proposed; no runtime implementation. Baseline: `fc36351f239b62984363ababaa1de1139402aafb`.

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/acting/JdbcActingWorkRepository.java#L54) · [caller/contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/acting/PersonalActingWork.java#L1) · [schema/routines](../../plowshare-server/src/main/resources/db/migration/V151__acting_personal_turns.sql#L1). Cache mechanics and recovery follow the [shared strategy](../query-efficiency-and-cache-design.md#server-reuse-and-tracked-database-dtos).

`PersonalActingWork` loads retained conversation bindings, joins account/request receipts to bindings and checks current ownership and conversation activity. Receipt transitions are conditional monotonic writes; `open` delegates to `ConversationStore`, and admission joins the consent transaction while later receipt writes use their own transaction. Receipt and job reads already join binding data rather than loading it per row.

Trace binding/receipt -> owns -> active sequences and pass the supplied immutable binding through authorization helpers. Consider a narrow combined ownership/lifecycle snapshot in one query, preserving the Personal owner, project identity and `origin='turn'` checks. Do not replace the shared consent lock with an optimistic cache check. V151's receipt keys and job uniqueness should cover exact reads; measure orphan/recovery paths before adding indexes. If callers reload a receipt after `started`, return the exact transitioned projection with `RETURNING`, retaining failed-CAS behavior.

Cache candidates are retained non-secret binding metadata, keyed by database/conversation identity and durable generation. Mutable admission state, current ownership/activity and whether dispatch may start remain authoritative and uncached. A historical receipt may be cached only as explicitly versioned display data, never replay authority. Bind/admit/started/finished/uncertain plus conversation/project lifecycle changes invalidate entity and actor/request/job collections after outer commit. Because source rows lack a general revision, version/generation plumbing and listener recovery are prerequisites to bounded LRU admission.

Mock one combined scope/lifecycle read, stale receipt transitions and uncertain completion without replay. Focused database checks cover conditional transitions and consent-transaction rollback when changing SQL.
