# ProjectIds

Status: Proposed. Baseline: `fc36351f239b62984363ababaa1de1139402aafb`.

[ProjectIds](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/ProjectIds.java#L112) centralizes name lookup for archive writes/reads and directories. Global maps to null; missing reads map to `NONE`; missing directories refuse; missing writes use `ON CONFLICT ... DO UPDATE ... RETURNING id`. The source explains why lookup-before-upsert is deliberate: unconditional upserts would lock the shared project row on every archive write. Do not label that two-step first-registration path wasteful without its concurrency reason.

First carry a resolved typed home/project ID through an operation needing multiple archive repositories, retaining each repository's ownership and validation. Prefer an explicit resolved identity contract over hidden thread-local state. Existing unique project-name lookup is suitable; neither another index nor wrapping the lookup in a stored routine fixes repeated callers. Measure name-resolution count separately from payload reads.

A shared immutable mapping can be keyed by database, canonical home and project identity generation. IDs alone are not permission grants. Existing projects are stable mappings, but creation must invalidate negative entries and all identity rename/delete/maintenance writers must publish after-commit changes. Newly registered IDs cannot enter shared state until the caller transaction commits. Preserve lookup/upsert race reconciliation; no cached absence skips the database conflict check. ProjectIds and JdbcProjectDirectories must use one cache owner and the proposed bounded expected O(1) LRU. Listener recovery clears/bypasses uncertain mappings until an authoritative baseline closes the registration race.

Mock one home resolution across a composed read and all three missing/global semantics. A changed resolver/version scheme needs focused PostgreSQL concurrent first registration, rollback and avoidance of unconditional project-row locking. No database tests are required merely to document the plan.
