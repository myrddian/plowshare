# JdbcProjectDirectories

Status: Proposed. Baseline: `fc36351f239b62984363ababaa1de1139402aafb`.

[This adapter](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/JdbcProjectDirectories.java#L18) delegates `existing` and `register` to `ProjectIds`; it contains no independent SQL. Data layout/export code uses the typed interface. Keep one identity resolver/cache capability shared with [ProjectIds](archive-ProjectIds.md), rather than creating a second adapter cache.

Pass an already resolved project ID into directory construction where the existing operation owns it. Preserve three distinct results: global uses no numeric project directory, an existing project uses its ID, and an unknown named project must fail rather than write into the missing sentinel's directory. Registration must retain its race-safe upsert and transaction rollback behavior. No adapter-specific index or stored procedure is needed.

If shared resolution is later admitted, key immutable name-to-ID values by database and canonical home plus project identity generation. Resolve current authorization separately; an ID mapping never authorizes filesystem access. Creation must invalidate negative membership, and every project identity/deletion writer must emit after-commit invalidation. Do not publish IDs registered in a transaction that later rolls back. Reconnect bypasses mappings until the project namespace baseline is recovered. Use the proposed bounded expected O(1) LRU at the owning resolver.

Verify adapter delegation with mocks, one resolution per directory operation and strict unknown/global behavior. PostgreSQL races belong in the focused ProjectIds tests only if its persistence implementation changes; duplicating those tests here provides no new coverage.
