# JdbcCommandInvocationsRepository

Status: proposed; no runtime implementation. Baseline: `fc36351f239b62984363ababaa1de1139402aafb`.

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/JdbcCommandInvocationsRepository.java#L33) · [caller/contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/BoundCommands.java#L1) · [schema/routines](../../plowshare-server/src/main/resources/db/migration/V91__command_invocations.sql#L1). Cache mechanics and recovery follow the [shared strategy](../query-efficiency-and-cache-design.md#server-reuse-and-tracked-database-dtos).

`BoundCommands` binds account/conversation/source-run invocations, reads by account/conversation/caller/UUID and lists pending states ordered by creation time. Binding inserts with conflict protection then reloads the retained row to reject changed arguments/definition. Claim and end use state-conditional updates. V91's pending partial index covers only `state='bound'`, whereas `pending` requests bound, dispatching and failed: that mismatch is confirmed in code, with performance still unmeasured.

Keep immutable command identity/arguments from the bound DTO across dispatch. Use `INSERT ... RETURNING` for the inserted path and a conflict-only retained lookup, preserving exact identity comparison. Compare a pending index matching conversation/account/caller plus requested states and ordering against realistic retained history; add only through a new migration if beneficial. Bound pagination or retention should be specified before pending lists grow without limit. A procedure adds little beyond existing conditional claims unless it replaces measured round trips.

The bound descriptor can enter a bounded LRU keyed by database/account/invocation plus definition hash; mutable state/result need an explicit generation and must not share an immutable key. Claim eligibility and uncertain-dispatch reconciliation always read authoritative state. Bind/claim/ended and conversation deletion invalidate invocation and pending collection dependencies after commit, with durable recovery before shared use. Do not serve cached `bound` as permission to dispatch.

Mock first-bind versus conflict query budgets, cross-account isolation and failed CAS. Focused database tests are needed for new indexes/RETURNING and competing claims, not for planning.
